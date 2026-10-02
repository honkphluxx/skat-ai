package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Rules;
import dev.skatklar.training.arena.Seeds;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network-only self-play, written down for the learner (docs/training-plan.md
 * 2.10, R: the Deep Monte-Carlo pilot).
 *
 * <pre>
 *   gradlew :arena:selfPlayExport --args="--model=dmc-pilot/net-00 --out=dmc-pilot/data-01 --games=500000"
 * </pre>
 *
 * <p>Every seat is the same net. At each card with a choice it reads the
 * evidence a perfect memory has -- the teacher's own encoding: every card
 * played, every void shown, the bids heard, and as declarer its own discard --
 * and plays the legal card its value head rates highest (the estimated chance
 * that the card keeps its side winning), or with probability {@code epsilon} a
 * legal card at random. When the game is over every decision is written with
 * what actually happened to the decider's side: won or lost, and its card
 * points. That outcome is the learner's whole target; no world is sampled and
 * no solver runs at the card.
 *
 * <p>The contracts are fixed, one per board, by {@code greedy}'s auction at
 * all three seats (as the arena's {@code --fixed-contract}); the bids of that
 * auction are what the seats hear. Greedy also does the declarer's skat
 * exchange. A Null is left out: the net never saw one. The boards come from
 * {@code --seed}, which must not be the teacher corpus's (101), so no deal of
 * the yardstick's held-out set is ever played here.
 *
 * <p>Record layout ({@link #RECORD_BYTES} bytes, little-endian, described in
 * {@code selfplay-format.json} beside the shards):
 *
 * <pre>
 *     0  306  the belief encoding, a byte a feature (the trainer divides by 255)
 *   306    4  legal cards, a bit per card index
 *   310    1  the card played
 *   311    1  flags: 1 = explored (a random card), 2 = the decider is the declarer
 *   312    1  trick, 1-10
 *   313    1  contract ordinal
 *   314    1  1 if the decider's side won the game
 *   315    1  the decider's side's card points at the end, 0-120
 *   316    1  the net's own estimate for the card played, 0-255 (sigmoid x 255)
 *   317    1  unused, 0
 *   318    8  game id: iteration << 40 | game number
 * </pre>
 */
public final class SelfPlayExportMain {

    static final int FORMAT = 1;
    static final int ENCODING = 0;
    static final int LEGAL = BeliefEncoding.SIZE;
    static final int CHOSEN = LEGAL + 4;
    static final int FLAGS = CHOSEN + 1;
    static final int TRICK = FLAGS + 1;
    static final int CONTRACT = TRICK + 1;
    static final int WON = CONTRACT + 1;
    static final int POINTS = WON + 1;
    static final int ESTIMATE = POINTS + 1;
    static final int GAME = ESTIMATE + 2;
    static final int RECORD_BYTES = GAME + 8;

    static final int EXPLORED = 1;
    static final int DECLARER = 2;

    /** The teacher corpora's board seed; self-play must never play their deals. */
    static final long TEACHER_SEED = 101;

    private SelfPlayExportMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        Path model = Path.of(options.getOrDefault("model", "policy-model"));
        Path out = Path.of(require(options, "out"));
        long games = Long.parseLong(options.getOrDefault("games", "100000"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        int iteration = Integer.parseInt(options.getOrDefault("iteration", "0"));
        long seed = Long.parseLong(options.getOrDefault("seed", String.valueOf(7000 + iteration)));
        double epsilon = Double.parseDouble(options.getOrDefault("epsilon", "0.02"));
        String act = options.getOrDefault("act", "value");
        if (!act.equals("value") && !act.equals("policy")) {
            throw new IllegalArgumentException("--act is value or policy, not " + act);
        }
        if (seed == TEACHER_SEED) {
            throw new IllegalArgumentException("seed " + seed + " is the teacher corpus's: its held-out deals "
                    + "are the yardstick, and self-play must not play them");
        }
        String rules = Rules.apply(options);

        PolicyNet net = load(model);
        Files.createDirectories(out);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant greedy = registry.resolve("greedy");
        ContractSource contracts = new AuctionContractSource(greedy, seed);

        System.out.println(rules);
        System.out.printf(Locale.ROOT, "self-play: %s, act by the %s head, epsilon %.3f, %,d games, seed %d, "
                + "iteration %d, %d threads -> %s%n", model, act, epsilon, games, seed, iteration, threads, out);

        AtomicLong nextBoard = new AtomicLong();
        AtomicLong played = new AtomicLong();
        Stats total = new Stats();
        long start = System.nanoTime();
        Thread[] pool = new Thread[threads];
        Throwable[] failure = new Throwable[1];
        for (int t = 0; t < threads; t++) {
            int worker = t;
            pool[t] = new Thread(() -> {
                Path shard = out.resolve(String.format(Locale.ROOT, "selfplay-%02d.bin", worker));
                try (OutputStream sink = new BufferedOutputStream(Files.newOutputStream(shard), 1 << 20)) {
                    Stats stats = new Stats();
                    SplittableRandom random = new SplittableRandom(Seeds.mix(seed, worker, 0x5E1F));
                    while (true) {
                        long game = played.getAndIncrement();
                        if (game >= games) break;
                        playOne(net, greedy, contracts, nextBoard, seed, iteration, game, act.equals("value"),
                                epsilon, random, sink, stats);
                        if (worker == 0 && game > 0 && game % 50_000 == 0) {
                            System.out.printf(Locale.ROOT, "  %,d games, %.0f s%n", game,
                                    (System.nanoTime() - start) / 1e9);
                        }
                    }
                    synchronized (total) { total.add(stats); }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, "selfplay-" + t);
            pool[t].setUncaughtExceptionHandler((th, e) -> failure[0] = e);
            pool[t].start();
        }
        for (Thread thread : pool) thread.join();
        if (failure[0] != null) throw new IllegalStateException("a worker failed", failure[0]);
        double seconds = (System.nanoTime() - start) / 1e9;

        Files.writeString(out.resolve("selfplay-format.json"), layout(model, act, epsilon, seed, iteration,
                total, seconds));
        System.out.printf(Locale.ROOT, "%,d games (%,d boards drawn, %,d without a suit or grand contract), "
                        + "%,d decisions, %.0f s, %.0f games/s%n",
                total.games, nextBoard.get(), total.skipped, total.decisions, seconds, total.games / seconds);
        System.out.printf(Locale.ROOT, "declarer won %.1f%%; %.1f%% of decisions explored; "
                        + "the net's estimate for its own card %.3f, the outcome %.3f%n",
                100.0 * total.declarerWins / Math.max(1, total.games),
                100.0 * total.explored / Math.max(1, total.decisions),
                total.estimate / Math.max(1, total.decisions),
                (double) total.won / Math.max(1, total.decisions));
    }

    static void playOne(PolicyNet net, Contestant greedy, ContractSource contracts, AtomicLong nextBoard,
                                long seed, int iteration, long game, boolean byValue, double epsilon,
                                SplittableRandom random, OutputStream sink, Stats stats) throws IOException {
        Board board;
        ContractSource.FixedContract contract;
        while (true) {
            board = Board.of(seed, nextBoard.getAndIncrement());
            contract = contracts.contractFor(board);
            if (contract != null && net.covers(contract.contract())) break;
            stats.skipped++;
        }
        List<Pending> decisions = new ArrayList<>(30);
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            long providerSeed = Seeds.mix(seed, board.index(), seat.ordinal());
            seating.put(seat, new Actor(net, greedy.newProvider(providerSeed), byValue, epsilon,
                    new SplittableRandom(random.nextLong()), decisions));
        }
        GameOutcome outcome = GameRunner.playFixed(board, seating, contract,
                Seeds.mix(seed, board.index(), 0xE1E1E1L));
        long id = ((long) iteration << 40) | game;
        ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Pending d : decisions) {
            boolean declarer = d.seat == outcome.declarer();
            boolean won = declarer == outcome.declarerWon();
            int points = declarer ? outcome.declarerPoints() : 120 - outcome.declarerPoints();
            record.clear();
            record.put(d.encoding);
            record.putInt(d.legal);
            record.put((byte) d.chosen);
            record.put((byte) ((d.explored ? EXPLORED : 0) | (declarer ? DECLARER : 0)));
            record.put((byte) d.trick);
            record.put((byte) outcome.contract().ordinal());
            record.put((byte) (won ? 1 : 0));
            record.put((byte) Math.max(0, Math.min(120, points)));
            record.put((byte) Math.round(255 * d.estimate));
            record.put((byte) 0);
            record.putLong(id);
            sink.write(record.array());
            stats.decisions++;
            if (d.explored) stats.explored++;
            if (won) stats.won++;
            stats.estimate += d.estimate;
        }
        stats.games++;
        if (outcome.declarerWon()) stats.declarerWins++;
    }

    /** One decision, kept until the game's outcome is known. */
    record Pending(SkatAi.Seat seat, byte[] encoding, int legal, int chosen, boolean explored, int trick,
                           double estimate) {}

    static final class Stats {
        long games;
        long skipped;
        long decisions;
        long explored;
        long won;
        long declarerWins;
        double estimate;

        void add(Stats other) {
            games += other.games;
            skipped += other.skipped;
            decisions += other.decisions;
            explored += other.explored;
            won += other.won;
            declarerWins += other.declarerWins;
            estimate += other.estimate;
        }
    }

    /**
     * The net in one seat. Everything before the first card -- the auction it
     * only hears, and as declarer the skat exchange -- is greedy's; this keeps
     * what a perfect memory keeps of it (the highest bid each seat made, its own
     * discard), because the engine runs the exchange, the listening and the card
     * play in separate sessions of the same provider.
     */
    static final class Actor implements SkatAiProvider {
        private static final SkatAi.AiDescriptor DESCRIPTOR =
                new SkatAi.AiDescriptor("selfplay-actor", "Self-play actor", true);
        private final PolicyNet net;
        private final SkatAiProvider greedy;
        private final boolean byValue;
        private final double epsilon;
        private final SplittableRandom random;
        private final List<Pending> decisions;
        private final Map<SkatAi.Seat, Integer> highestBids = new EnumMap<>(SkatAi.Seat.class);
        private List<Card> buried;

        Actor(PolicyNet net, SkatAiProvider greedy, boolean byValue, double epsilon, SplittableRandom random,
              List<Pending> decisions) {
            this.net = net;
            this.greedy = greedy;
            this.byValue = byValue;
            this.epsilon = epsilon;
            this.random = random;
            this.decisions = decisions;
        }

        @Override public SkatAi.AiDescriptor descriptor() { return DESCRIPTOR; }

        @Override public SkatAiSession createSession() {
            SkatAiSession delegate = greedy.createSession();
            return new SkatAiSession() {
                @Override public void prepareDeal(SkatAi.DealContext context) { delegate.prepareDeal(context); }
                @Override public int bid(SkatAi.BidRequest request) { return delegate.bid(request); }
                @Override public void bidObserved(SkatAi.BidEvent event) {
                    if (!event.passed) highestBids.merge(event.seat, event.value, Math::max);
                    delegate.bidObserved(event);
                }
                @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
                    return delegate.pickUpSkat(context);
                }
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    Set<Card> discarded = delegate.discardSkat(context);
                    buried = List.copyOf(discarded);
                    return discarded;
                }
                @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
                    return delegate.announceContract(context);
                }
                @Override public void startGame(SkatAi.GameStartContext context) { delegate.startGame(context); }
                @Override public Card chooseCard(SkatAi.DecisionContext context) { return choose(context); }
                @Override public void cardPlayed(SkatAi.CardPlayedEvent event) { delegate.cardPlayed(event); }
                @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
                    delegate.trickCompleted(event);
                }
                @Override public void endGame(SkatAi.GameResult result) { delegate.endGame(result); }
                @Override public void close() { delegate.close(); }
            };
        }

        private Card choose(SkatAi.DecisionContext context) {
            List<Card> legal = new ArrayList<>(context.legalCards);
            if (legal.size() == 1) return legal.get(0);
            byte[] encoding = BeliefEncoding.quantise(BeliefEncoding.encode(
                    perfectMemory(context, highestBids, buried)));
            float[] scores = net.logits(BeliefEncoding.dequantise(encoding));
            int offset = byValue ? PolicyNet.OUTPUTS / 2 : 0;
            Card best = null;
            float top = Float.NEGATIVE_INFINITY;
            int mask = 0;
            for (Card card : legal) {
                int index = BeliefEncoding.index(card);
                mask |= 1 << index;
                float score = scores[offset + index];
                if (best == null || score > top) {
                    best = card;
                    top = score;
                }
            }
            boolean explored = random.nextDouble() < epsilon;
            Card card = explored ? legal.get(random.nextInt(legal.size())) : best;
            double estimate = sigmoid(scores[PolicyNet.OUTPUTS / 2 + BeliefEncoding.index(card)]);
            decisions.add(new Pending(context.mySeat, encoding, mask, BeliefEncoding.index(card), explored,
                    context.history.completedTricks.size() + 1, estimate));
            return card;
        }
    }

    /**
     * The evidence the teacher's encoding was made of, for a seat that forgets
     * nothing: every card played, every void shown, the bids heard, and as
     * declarer its own discard -- what {@code SearchAiProvider} builds at
     * memory 1, checked against it by {@code SelfPlayExportTest}.
     */
    static BeliefEncoding.Evidence perfectMemory(SkatAi.DecisionContext context,
                                                 Map<SkatAi.Seat, Integer> highestBids, List<Card> buried) {
        Set<Card> seen = context.derived.playedCards;
        boolean declarer = context.game.hasDeclarer() && context.mySeat == context.game.declarer;
        return new BeliefEncoding.Evidence(context, seen, context.derived.voidClasses,
                declarer ? buried : null, highestBids, !highestBids.isEmpty(),
                BeliefEncoding.trumpsUnaccounted(context.game.contract, context.hand, seen),
                BeliefEncoding.jacksUnseen(context.hand, seen));
    }

    static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    static PolicyNet load(Path directory) throws IOException {
        try (InputStream weights = Files.newInputStream(directory.resolve("policy.bin"));
             InputStream parity = Files.newInputStream(directory.resolve("policy-parity.bin"))) {
            return PolicyNet.load(weights, parity);
        }
    }

    static String layout(Path model, String act, double epsilon, long seed, int iteration, Stats total,
                         double seconds) {
        return "{\n"
                + "  \"format\": " + FORMAT + ",\n"
                + "  \"record_bytes\": " + RECORD_BYTES + ",\n"
                + "  \"encoding_version\": " + BeliefEncoding.VERSION + ",\n"
                + "  \"encoding_size\": " + BeliefEncoding.SIZE + ",\n"
                + "  \"scale\": 255,\n"
                + "  \"fields\": {\"encoding\": [" + ENCODING + ", " + BeliefEncoding.SIZE + "], \"legal\": ["
                + LEGAL + ", 4], \"chosen\": [" + CHOSEN + ", 1], \"flags\": [" + FLAGS + ", 1], \"trick\": ["
                + TRICK + ", 1], \"contract\": [" + CONTRACT + ", 1], \"won\": [" + WON + ", 1], \"points\": ["
                + POINTS + ", 1], \"estimate\": [" + ESTIMATE + ", 1], \"game\": [" + GAME + ", 8]},\n"
                + "  \"flags\": {\"explored\": " + EXPLORED + ", \"declarer\": " + DECLARER + "},\n"
                + "  \"model\": \"" + model.toString().replace('\\', '/') + "\",\n"
                + "  \"act\": \"" + act + "\",\n"
                + String.format(Locale.ROOT, "  \"epsilon\": %s,%n", epsilon)
                + "  \"seed\": " + seed + ",\n"
                + "  \"iteration\": " + iteration + ",\n"
                + "  \"games\": " + total.games + ",\n"
                + "  \"decisions\": " + total.decisions + ",\n"
                + String.format(Locale.ROOT, "  \"declarer_won\": %.4f,%n",
                        (double) total.declarerWins / Math.max(1, total.games))
                + String.format(Locale.ROOT, "  \"seconds\": %.1f%n", seconds)
                + "}\n";
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + "=... is required");
        return value;
    }
}
