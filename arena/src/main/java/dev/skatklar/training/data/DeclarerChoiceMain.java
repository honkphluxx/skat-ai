package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.Discards;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.training.players.TrueDealLabeller;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The declarer's choice after the auction, labelled on the true deal
 * (docs/training-plan.md, after route 1).
 *
 * <pre>
 * ./gradlew :arena:declarerChoice --args="--model=policy-oc3-ev --out=arena-logs/declarer-choice/x.jsonl --boards=2000"
 * </pre>
 *
 * <p>For every board greedy's auction declares (the arena's fixed contracts,
 * {@link AuctionContractSource}): the declarer's twelve cards and the winning
 * bid. The <em>options</em> are every suit and grand contract whose guaranteed
 * value on the twelve covers the bid (Null is left out: the student does not
 * play it), each with the ten discards drawn from the five cards
 * {@link Discards#ranked} wants least for that contract (which held a makeable
 * discard on every board of Phase D's count) -- plus, should they fall
 * outside, the heuristic's own pair and the two references below. Every
 * option is played out {@code k} times from the first card on the true
 * deal, the student in every seat: the declarer by its top card, the
 * defenders drawn at temperature 1, each seat hearing the auction and the
 * declarer knowing its discard, as the all-cards labels are made. Draw d uses
 * the same random streams for every option of a board.
 *
 * <p>The two references:
 * <ul>
 *   <li><b>shipped</b> -- what {@code --shipped} (default belief-64-shipped)
 *       does in the declarer's seat at this bid: it prices its ten at the deal,
 *       hears the auction, picks up, discards and announces, exactly the calls
 *       {@code GameEngine} makes after an auction. Its price is the one made
 *       at the deal: in its own auction an adaptive bidder re-prices on each
 *       bid it is asked for, which a replayed auction does not ask.</li>
 *   <li><b>fixed</b> -- the auction's own contract with the heuristic's
 *       discard for it: what every route-1 game was played at.</li>
 * </ul>
 *
 * <p>One JSON line a declared board: the board, the declarer, the bid, the
 * auction's contract, the shipped choice, and per option its contract, its
 * discard (card indices), whether it is the heuristic's pair, and per draw
 * the game value, the win and the card points from the declarer's side.
 * {@code arena/python/declarer_choice.py} reads it. Boards run in parallel and
 * are written in board order; the file is the same bytes at any thread count.
 */
public final class DeclarerChoiceMain {

    /** The arena's auction game: suits and grand, no hand games (GameEngine's auto-bidding rules). */
    static final SkatAi.ContractRules RULES = new SkatAi.ContractRules(EnumSet.of(
            SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS, SkatAi.ContractType.SPADES,
            SkatAi.ContractType.CLUBS, SkatAi.ContractType.GRAND), false, false, false);
    static final Contract[] TRUMP_GAMES = {Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS,
            Contract.GRAND};
    /** The discard's candidates: the five cards the heuristic wants least, ten pairs. */
    static final int POOL = 5;

    private DeclarerChoiceMain() {}

    /** Tests only: hears every decision a play-out's student makes, with whether it is the declarer's. */
    static final ThreadLocal<java.util.function.BiConsumer<SkatAi.DecisionContext, Boolean>> WATCH = new ThreadLocal<>();

    /** One way to go on after the auction: the contract and the two cards buried. */
    record Option(Contract contract, List<Card> discard, boolean heuristic) {
        Option {
            discard = pair(discard);
        }

        boolean same(Contract c, List<Card> d) {
            return contract == c && discard.equals(pair(d));
        }
    }

    /** What a player in the declarer's seat chose. */
    record Choice(Contract contract, List<Card> discard) {}

    /** One option played out k times: per draw, from the declarer's side. */
    record Scored(Option option, int[] value, int[] wins, int[] points) {}

    /** One declared board. */
    record Result(long board, SkatAi.Seat declarer, int bid, Contract auctionContract, Choice shipped,
                  List<Scored> scored) {}

    /** Two cards in card-index order, so a pair has one spelling. */
    static List<Card> pair(List<Card> cards) {
        if (cards.size() != 2) throw new IllegalArgumentException("a discard is two cards, not " + cards);
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparingInt(BeliefEncoding::index));
        return List.copyOf(sorted);
    }

    /**
     * Every contract covering the bid that the student plays, times the ten
     * pairs from its five least-wanted cards; the heuristic's pair added when
     * the pool lacks it; then each of {@code required} that is not yet there.
     */
    static List<Option> options(List<Card> twelve, int bid, PolicyNet net, List<Choice> required) {
        List<Option> out = new ArrayList<>();
        for (Contract c : TRUMP_GAMES) {
            if (!net.covers(c) || SkatRules.guaranteedValue(c, twelve) < bid) continue;
            List<Card> ranked = Discards.ranked(c, twelve);
            List<Card> pool = ranked.subList(12 - POOL, 12);
            List<Card> heuristic = pair(Discards.buried(c, twelve));
            boolean held = false;
            for (int i = 0; i < POOL; i++) {
                for (int j = i + 1; j < POOL; j++) {
                    List<Card> d = pair(List.of(pool.get(i), pool.get(j)));
                    boolean isHeuristic = d.equals(heuristic);
                    held |= isHeuristic;
                    out.add(new Option(c, d, isHeuristic));
                }
            }
            if (!held) out.add(new Option(c, heuristic, true));
        }
        for (Choice r : required) {
            if (indexOf(out, r) < 0) {
                out.add(new Option(r.contract(), r.discard(), pair(Discards.buried(r.contract(), twelve))
                        .equals(pair(r.discard()))));
            }
        }
        return out;
    }

    static int indexOf(List<Option> options, Choice choice) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).same(choice.contract(), choice.discard())) return i;
        }
        return -1;
    }

    /**
     * What {@code player} does in the declarer's seat: priced on its ten at the
     * deal, the auction heard, the skat picked up, two cards buried, a game
     * announced at the bid -- the calls GameEngine makes after an auction, in
     * its order.
     */
    static Choice shippedChoice(SkatAiProvider player, Board board, ContractSource.FixedContract fixed,
                                List<Card> ten, List<Card> skat) {
        SkatAi.Seat declarer = fixed.declarer();
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(board.round(), declarer, new LinkedHashSet<>(ten), RULES));
            for (SkatAi.BidEvent event : fixed.auction()) session.bidObserved(event);
            session.pickUpSkat(new SkatAi.SkatChoiceContext(board.round(), declarer, fixed.bidValue()));
            List<Card> twelve = new ArrayList<>(ten);
            twelve.addAll(skat);
            Set<Card> discarded = session.discardSkat(new SkatAi.SkatExchangeContext(board.round(), declarer,
                    new LinkedHashSet<>(twelve), skat, null));
            if (discarded == null || discarded.size() != 2 || !twelve.containsAll(discarded)) {
                throw new IllegalStateException("board " + board.index() + ": " + player + " discarded " + discarded);
            }
            List<Card> kept = new ArrayList<>(twelve);
            kept.removeAll(discarded);
            SkatAi.ContractAnnouncement announced = session.announceContract(new SkatAi.ContractContext(
                    board.round(), declarer, new LinkedHashSet<>(kept), fixed.bidValue(), true, RULES));
            if (announced == null || !RULES.permits(announced)) {
                throw new IllegalStateException("board " + board.index() + ": " + player + " announced " + announced);
            }
            return new Choice(Contract.valueOf(announced.type.name()), pair(new ArrayList<>(discarded)));
        }
    }

    /** The declarer's dealt ten, by the engine's seat order. */
    static List<Card> handOf(Board board, SkatAi.Seat seat) {
        return switch (seat) {
            case HUMAN -> board.deal().human;
            case OPPONENT_ONE -> board.deal().opponentOne;
            case OPPONENT_TWO -> board.deal().opponentTwo;
        };
    }

    /** One option, k times, the student in every seat; draw d's streams depend on the board and d only. */
    static Scored playOut(PolicyNet net, Board board, ContractSource.FixedContract fixed, Option option, int k,
                          long seed) {
        int[] value = new int[k];
        int[] wins = new int[k];
        int[] points = new int[k];
        ContractSource.FixedContract game = new ContractSource.FixedContract(fixed.declarer(), option.contract(),
                fixed.bidValue(), fixed.auction());
        for (int d = 0; d < k; d++) {
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, new StudentSeat(net, seat == fixed.declarer(), option.discard(),
                        new SplittableRandom(Seeds.mix(seed, board.index(), d, seat.ordinal(), 0xDC5EA7L))));
            }
            GameOutcome outcome = GameRunner.playFixed(board, seating, game, Seeds.mix(seed, board.index(), d, 0xE7L));
            if (!outcome.ruleViolations().isEmpty() && outcome.ruleViolations().values().stream().anyMatch(n -> n > 0)) {
                throw new IllegalStateException("board " + board.index() + ": a rule violation in a play-out");
            }
            value[d] = outcome.gameValue();
            wins[d] = outcome.declarerWon() ? 1 : 0;
            points[d] = outcome.declarerPoints();
        }
        return new Scored(option, value, wins, points);
    }

    /** One board: null when the auction passed in or the student does not play the shipped contract. */
    static Result board(PolicyNet net, Contestant shipped, ContractSource contracts, Board board, int k, long seed,
                        Stats stats) {
        ContractSource.FixedContract fixed = contracts.contractFor(board);
        if (fixed == null || fixed.contract().isNull()) {
            stats.skipped++;
            return null;
        }
        List<Card> ten = handOf(board, fixed.declarer());
        List<Card> skat = board.deal().skat;
        List<Card> twelve = new ArrayList<>(ten);
        twelve.addAll(skat);
        Choice choice = shippedChoice(shipped.newProvider(Seeds.mix(seed, board.index(), 0x5417L)), board, fixed,
                ten, skat);
        if (!net.covers(choice.contract()) || !net.covers(fixed.contract())) {
            stats.uncovered++;
            return null;
        }
        Choice asFixed = new Choice(fixed.contract(), Discards.buried(fixed.contract(), twelve));
        List<Option> options = options(twelve, fixed.bidValue(), net, List.of(choice, asFixed));
        List<Scored> scored = new ArrayList<>(options.size());
        for (Option option : options) scored.add(playOut(net, board, fixed, option, k, seed));
        stats.boards++;
        stats.options += options.size();
        return new Result(board.index(), fixed.declarer(), fixed.bidValue(), fixed.contract(), choice, scored);
    }

    static final class Stats {
        long boards;
        long skipped;
        long uncovered;
        long options;

        void add(Stats other) {
            boards += other.boards;
            skipped += other.skipped;
            uncovered += other.uncovered;
            options += other.options;
        }
    }

    static String json(Result r) {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"board\": ").append(r.board()).append(", \"declarer\": \"").append(r.declarer().name())
                .append("\", \"bid\": ").append(r.bid()).append(", \"auction_contract\": \"")
                .append(r.auctionContract().name()).append("\", \"shipped\": {\"contract\": \"")
                .append(r.shipped().contract().name()).append("\", \"discard\": ").append(cards(r.shipped().discard()))
                .append("}, \"options\": [");
        for (int i = 0; i < r.scored().size(); i++) {
            Scored s = r.scored().get(i);
            if (i > 0) b.append(", ");
            b.append("{\"contract\": \"").append(s.option().contract().name()).append("\", \"discard\": ")
                    .append(cards(s.option().discard())).append(", \"heuristic\": ").append(s.option().heuristic())
                    .append(", \"value\": ").append(ints(s.value())).append(", \"wins\": ").append(ints(s.wins()))
                    .append(", \"points\": ").append(ints(s.points())).append('}');
        }
        return b.append("]}").toString();
    }

    private static String cards(List<Card> cards) {
        return "[" + BeliefEncoding.index(cards.get(0)) + ", " + BeliefEncoding.index(cards.get(1)) + "]";
    }

    private static String ints(int[] values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) b.append(i == 0 ? "" : ", ").append(values[i]);
        return b.append(']').toString();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        Path model = Path.of(options.getOrDefault("model", "policy-oc3-ev"));
        Path out = Path.of(options.getOrDefault("out", "arena-logs/declarer-choice/declarer-choice.jsonl"));
        long boards = Long.parseLong(options.getOrDefault("boards", "2000"));
        long first = Long.parseLong(options.getOrDefault("first-board", "0"));
        int k = Integer.parseInt(options.getOrDefault("k", "8"));
        long seed = Long.parseLong(options.getOrDefault("seed", "9101"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        String shippedId = options.getOrDefault("shipped", "belief-64-shipped");
        if (k < 2 || k % 2 != 0) throw new IllegalArgumentException("k is even and at least 2 (it is split in halves)");
        if (seed < 1000) throw new IllegalArgumentException("seed " + seed + " is below 1000: those boards are the arena's");

        PolicyNet net = AllCardsExportMain.load(model);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant greedy = registry.resolve("greedy");
        Contestant shipped = registry.resolve(shippedId);
        ContractSource contracts = new AuctionContractSource(greedy, seed);
        System.out.printf(Locale.ROOT, "declarer choice: student %s, shipped %s, k %d, seed %d, boards %,d-%,d, "
                + "%d threads -> %s%n", model, shippedId, k, seed, first, first + boards - 1, threads, out);

        long started = System.nanoTime();
        List<Result> results = new ArrayList<>();
        Stats total = new Stats();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object[]>> futures = new ArrayList<>();
            for (long i = first; i < first + boards; i++) {
                Board board = Board.of(seed, i);
                futures.add(pool.submit(() -> {
                    Stats stats = new Stats();
                    Result r = board(net, shipped, contracts, board, k, seed, stats);
                    return new Object[] {r, stats};
                }));
            }
            for (Future<Object[]> f : futures) {
                Object[] done = f.get();
                if (done[0] != null) results.add((Result) done[0]);
                total.add((Stats) done[1]);
            }
        } finally {
            pool.shutdownNow();
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        try (BufferedWriter w = Files.newBufferedWriter(partial)) {
            for (Result r : results) {
                w.write(json(r));
                w.write('\n');
            }
        }
        Files.move(partial, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(Locale.ROOT, "  %,d declared boards labelled (%,d passed in or Null, %,d the student does not "
                        + "play); %.1f options a board, k %d: %,d play-outs in %.0f s%n", total.boards, total.skipped,
                total.uncovered, total.boards == 0 ? 0.0 : (double) total.options / total.boards, k,
                total.options * k, seconds);
    }

    /**
     * The student in one seat of a play-out: the declarer by its top card, a
     * defender drawn at temperature 1; the auction heard on the provider (the
     * engine tells the defenders through a session of its own), the declarer's
     * discard forced and remembered.
     */
    static final class StudentSeat implements SkatAiProvider {
        private static final SkatAi.AiDescriptor DESCRIPTOR =
                new SkatAi.AiDescriptor("declarer-choice-student", "Declarer-choice play-out", true);
        private final PolicyNet net;
        private final boolean declarer;
        private final List<Card> forced;
        private final SplittableRandom random;
        private final Map<SkatAi.Seat, Integer> highestBids = new EnumMap<>(SkatAi.Seat.class);
        private List<Card> buried;

        StudentSeat(PolicyNet net, boolean declarer, List<Card> forced, SplittableRandom random) {
            this.net = net;
            this.declarer = declarer;
            this.forced = forced;
            this.random = random;
        }

        @Override public SkatAi.AiDescriptor descriptor() { return DESCRIPTOR; }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public void bidObserved(SkatAi.BidEvent event) {
                    if (!event.passed) highestBids.merge(event.seat, event.value, Math::max);
                }
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    if (!context.hand.containsAll(forced)) {
                        throw new IllegalStateException("the forced discard " + forced + " is not in " + context.hand);
                    }
                    buried = List.copyOf(forced);
                    return new LinkedHashSet<>(forced);
                }
                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    var watch = WATCH.get();
                    if (watch != null) watch.accept(context, declarer);
                    List<Card> legal = new ArrayList<>(context.legalCards);
                    if (legal.size() == 1) return legal.get(0);
                    legal.sort(Comparator.comparingInt(BeliefEncoding::index));
                    float[] scores = net.logits(new float[][] {PolicyNet.features(
                            SelfPlayExportMain.perfectMemory(context, highestBids, declarer ? buried : null))})[0];
                    return declarer ? TrueDealLabeller.topCard(scores, legal)
                            : TrueDealLabeller.drawCard(scores, legal, 1.0, random);
                }
            };
        }
    }
}
