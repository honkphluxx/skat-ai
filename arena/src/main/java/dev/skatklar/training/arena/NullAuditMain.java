package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.search.HandEvaluator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Why a player that can declare Null never does.
 *
 * <p>Phase R measured zero Nulls in about 1,800 declared games across three
 * seeds, while the double-dummy oracle prices Null as makeable on roughly 3% of
 * the same boards. The player is not incapable: on the best Null holding in the
 * pack it bids 18, holds 23, passes at 24 and announces Null. The hypothesis is
 * that it cannot <em>win an auction</em> with one, because
 * {@code SkatRules.guaranteedValue(NULL)} is a flat 23 and the bidder never
 * offers Null Hand (35), Null Ouvert (46) or Null Ouvert Hand (59) — and a hand
 * shaped like a Null is a hand whose jacks and aces are all sitting with the
 * other two.
 *
 * <p>This tool decides that, and prices the fix before anybody writes it. For
 * every board it asks all three seats two questions — how high will you go, and
 * what would you announce — then settles the auction between them and reports:
 *
 * <ul>
 *   <li>how often a seat <b>intends</b> Null at all. If this is near zero the
 *       hypothesis is wrong and the fault is upstream, in {@code makeChance} or
 *       in which contracts {@code candidates} offers, not in the ceiling;</li>
 *   <li>how often an intending seat <b>wins</b> the auction, and what beat it
 *       when it did not;</li>
 *   <li>the <b>counterfactual</b>: how many of those seats would have survived
 *       had Null been biddable to 35, 46 or 59.</li>
 * </ul>
 *
 * <p><b>The counterfactual is an upper bound and must be read as one.</b> It
 * replaces the ceiling and nothing else: it does not ask whether the hand
 * actually makes a Null Hand or a Null Ouvert, which are much stronger claims
 * than a plain Null. It answers "how much room is there above 23", which is the
 * question that decides whether the ceiling is worth lifting. What the hand can
 * support is the next measurement, and it belongs in the fix.
 *
 * <p>The auction here is modelled rather than played, because playing it needs a
 * whole engine per board to answer a question about one number. The model is the
 * ladder rule: the highest ceiling wins, and a tie goes to whoever was holding —
 * forehand over middlehand, and the survivor of those two over rearhand. That is
 * exact for players whose bidding is a ceiling, which is what every player here
 * has; a player that bid adaptively would need the real auction.
 *
 * <pre>
 *   ./gradlew :arena:nullAudit --args="--player=belief-32 --boards=2000 --threads=8"
 *   ./gradlew :arena:nullAudit --args="--player=belief-32 --boards=500 --oracle"
 * </pre>
 */
public final class NullAuditMain {

    private NullAuditMain() {}

    /**
     * What one seat said about one board.
     *
     * @param afterSkat what it announced once it had picked the skat up and
     *                  discarded, or null when it played a hand game or was
     *                  never asked. Only filled in for a seat that won.
     */
    private record Opinion(int ceiling, SkatAi.ContractType intends,
                           SkatAi.ContractType afterSkat, boolean tookSkat,
                           double nullChance, Contract rival, double rivalChance,
                           int rivalValue) {}

    /** What one board came to. {@code winningBid} is what the runner-up pushed it to. */
    private record BoardVerdict(long index, List<Opinion> opinions, int winner,
                                int winningBid, Contract oracle) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help") || options.containsKey("h")) {
            usage();
            return;
        }
        String playerId = options.getOrDefault("player", "search");
        int boards = intOption(options, "boards", 1000);
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        int threads = intOption(options, "threads", Runtime.getRuntime().availableProcessors());
        boolean explainNull = "null".equals(options.get("explain"));
        // Explaining Null means explaining the boards the oracle calls Null, so
        // asking for the explanation asks for the oracle whether or not it was
        // requested. Silently producing an empty explanation would be worse.
        boolean oracle = options.containsKey("oracle") || explainNull;
        boolean quiet = options.containsKey("quiet");
        // clamp(worlds/3, 2, 6), which is SearchAiProvider.biddingWorlds(): 6 for
        // belief-32, search-32 and anything from 18 worlds up, 5 at 16. The audit
        // has no way to read it off a contestant, so it is stated rather than
        // guessed -- and the chance it produces is its own estimate on its own
        // random stream, close to the player's but not the identical float.
        int biddingWorlds = intOption(options, "bidding-worlds", 6);

        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant contestant = registry.resolve(playerId);
        ContractSource oracleSource = oracle ? new SolverContractSource() : null;

        System.out.printf(Locale.ROOT,
                "Null audit: %s, %d boards, seed %d, %d threads%s%n",
                contestant.displayName(), boards, seed, threads,
                oracle ? ", with the double-dummy oracle" : "");
        System.out.println("Asking every seat how high it would go and what it would announce.");
        System.out.println();

        long startedAt = System.nanoTime();
        List<BoardVerdict> verdicts = new ArrayList<>(boards);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<BoardVerdict>> pending = new ArrayList<>(boards);
            for (int index = 0; index < boards; index++) {
                Board board = Board.of(seed, index);
                pending.add(pool.submit(
                        audit(contestant, board, seed, oracleSource, explainNull, biddingWorlds)));
            }
            int done = 0;
            for (Future<BoardVerdict> future : pending) {
                verdicts.add(future.get());
                if (!quiet && ++done % 100 == 0) {
                    System.out.printf(Locale.ROOT, "  %d / %d boards%n", done, boards);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        report(verdicts, oracle, System.nanoTime() - startedAt);
        if (explainNull) explain(verdicts, biddingWorlds);
    }

    private static Callable<BoardVerdict> audit(Contestant contestant, Board board,
                                                long seed, ContractSource oracleSource,
                                                boolean explainNull, int biddingWorlds) {
        return () -> {
            List<List<Card>> hands = List.of(
                    board.deal().human, board.deal().opponentOne, board.deal().opponentTwo);
            List<Opinion> opinions = new ArrayList<>(3);
            List<SkatAiSession> sessions = new ArrayList<>(3);
            try {
                for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                    // Seeded the way the arena seeds a seat, so an audit and a
                    // match ask the same player rather than two samples of one.
                    SkatAiProvider provider = contestant.newProvider(
                            Seeds.mix(seed, board.index(), seat.ordinal()));
                    SkatAiSession session = provider.createSession();
                    sessions.add(session);
                    opinions.add(ask(session, board, seat, hands.get(seat.ordinal())));
                }
                int winner = winnerOf(opinions, board.round());
                int winningBid = winningBidOf(opinions, winner);
                // Follow the skat only for the seat that actually won, and only
                // when Null was on the table: that is the step the model cannot
                // see and the real matches apparently lose the contract at.
                if (winner >= 0 && opinions.get(winner).intends() == SkatAi.ContractType.NULL) {
                    opinions.set(winner, followSkat(sessions.get(winner), board,
                            SkatAi.Seat.values()[winner], hands.get(winner),
                            opinions.get(winner), winningBid));
                }
                Contract oracle = null;
                if (oracleSource != null) {
                    ContractSource.FixedContract best = oracleSource.contractFor(board);
                    oracle = best == null ? null : best.contract();
                }
                if (explainNull && oracle != null && oracle.isNull()) {
                    for (int i = 0; i < opinions.size(); i++) {
                        opinions.set(i, priced(opinions.get(i), board,
                                SkatAi.Seat.values()[i], hands.get(i), biddingWorlds));
                    }
                }
                return new BoardVerdict(board.index(), opinions, winner, winningBid, oracle);
            } finally {
                for (SkatAiSession session : sessions) {
                    try {
                        session.close();
                    } catch (RuntimeException ignored) {
                        // A session that will not close cannot spoil a reading
                        // that was already taken.
                    }
                }
            }
        };
    }

    /**
     * What the winner announces once it has seen the skat.
     *
     * <p>The modelled auction declares Null on about one board in 200 and the
     * real matches declared none at all, so something between winning and
     * playing loses it. This is that something: a seat intends Null from its
     * dealt ten, wins, picks up two cards that are probably high, and gets to
     * change its mind.
     */
    private static Opinion followSkat(SkatAiSession session, Board board, SkatAi.Seat seat,
                                      List<Card> hand, Opinion opinion, int winningBid) {
        try {
            boolean takes = session.pickUpSkat(
                    new SkatAi.SkatChoiceContext(board.round(), seat, winningBid));
            LinkedHashSet<Card> remaining = new LinkedHashSet<>(hand);
            if (takes) {
                List<Card> skat = board.deal().skat;
                LinkedHashSet<Card> twelve = new LinkedHashSet<>(hand);
                twelve.addAll(skat);
                java.util.Set<Card> away = session.discardSkat(new SkatAi.SkatExchangeContext(
                        board.round(), seat, new LinkedHashSet<>(hand), skat));
                remaining = new LinkedHashSet<>(twelve);
                remaining.removeAll(away);
            }
            SkatAi.ContractAnnouncement announced = session.announceContract(
                    new SkatAi.ContractContext(board.round(), seat, remaining,
                            winningBid, takes, GameEngine.FULL_CONTRACTS));
            return new Opinion(opinion.ceiling(), opinion.intends(),
                    announced == null ? null : announced.type, takes,
                    opinion.nullChance(), opinion.rival(), opinion.rivalChance(),
                    opinion.rivalValue());
        } catch (RuntimeException refused) {
            return opinion;
        }
    }

    /**
     * The two numbers that separate the remaining hypotheses: what this seat
     * thinks its Null is worth, and what it thinks the game it preferred is
     * worth. A high Null chance beside a preferred trump game means the value
     * comparison loses; a low one means {@code makeChance} is the harsh part.
     *
     * <p>Measured here rather than read off the player, which does not expose
     * it. Same world count, its own random stream: close to the player's number
     * and not the identical float.
     */
    private static Opinion priced(Opinion opinion, Board board, SkatAi.Seat seat,
                                  List<Card> hand, int biddingWorlds) {
        try {
            HandEvaluator evaluator = new HandEvaluator(biddingWorlds,
                    new java.util.Random(Seeds.mix(board.index(), seat.ordinal())));
            double nullChance = evaluator.makeChance(
                    Contract.NULL, hand, seat, board.round().forehand);
            List<Contract> trumps = HandEvaluator.plausibleTrumpGames(hand, 1);
            Contract rival = trumps.isEmpty() ? null : trumps.get(0);
            double rivalChance = rival == null ? Double.NaN
                    : evaluator.makeChance(rival, hand, seat, board.round().forehand);
            int rivalValue = rival == null ? 0
                    : dev.skatklar.demo.SkatRules.guaranteedValue(rival, hand);
            return new Opinion(opinion.ceiling(), opinion.intends(), opinion.afterSkat(),
                    opinion.tookSkat(), nullChance, rival, rivalChance, rivalValue);
        } catch (RuntimeException failed) {
            return opinion;
        }
    }

    /** One seat's ceiling and its intention, from the dealt ten and nothing else. */
    private static Opinion ask(SkatAiSession session, Board board, SkatAi.Seat seat,
                               List<Card> hand) {
        LinkedHashSet<Card> dealt = new LinkedHashSet<>(hand);
        session.prepareDeal(new SkatAi.DealContext(
                board.round(), seat, dealt, GameEngine.FULL_CONTRACTS));

        // Walk the ladder rather than ask for a number: what a bidder will hold
        // to is only visible through the answers it gives, and a player is free
        // to decide each rung on its own.
        int ceiling = 0;
        for (int value : dev.skatklar.demo.BidValues.LADDER) {
            if (value <= ceiling) continue;
            int answer;
            try {
                answer = session.bid(new SkatAi.BidRequest(
                        board.round(), seat, SkatAi.BidRole.ANNOUNCE, ceiling, value));
            } catch (RuntimeException refused) {
                break;
            }
            if (answer != value) break;
            ceiling = value;
        }
        if (ceiling == 0) return new Opinion(0, null, null, false, Double.NaN, null,
                Double.NaN, 0);

        SkatAi.ContractType intends = null;
        try {
            SkatAi.ContractAnnouncement announced = session.announceContract(
                    new SkatAi.ContractContext(board.round(), seat, dealt, 18, false,
                            GameEngine.FULL_CONTRACTS));
            if (announced != null) intends = announced.type;
        } catch (RuntimeException unsupported) {
            // A player that will bid but not announce from the dealt ten says
            // nothing here rather than being counted as intending something.
        }
        return new Opinion(ceiling, intends, null, false, Double.NaN, null, Double.NaN, 0);
    }

    /**
     * What the winner was actually pushed to: the runner-up's ceiling, or 18
     * when nobody pushed at all. Not the winner's own ceiling, which is only
     * what it would have gone to.
     */
    private static int winningBidOf(List<Opinion> opinions, int winner) {
        if (winner < 0) return 0;
        int runnerUp = 0;
        for (int i = 0; i < opinions.size(); i++) {
            if (i != winner) runnerUp = Math.max(runnerUp, opinions.get(i).ceiling());
        }
        return Math.max(18, runnerUp);
    }

    /**
     * The ladder rule: the highest ceiling wins, and a tie goes to whoever was
     * holding. Forehand holds against middlehand and the survivor holds against
     * rearhand, so the tie-break is bidding order -- which is the round's order
     * and not seat ordinals, since the deal rotates. Returns a seat ordinal, or
     * -1 when all three pass.
     */
    private static int winnerOf(List<Opinion> opinions, SkatAi.RoundPosition round) {
        int winner = -1;
        int best = 0;
        for (SkatAi.Seat seat : List.of(round.forehand, round.middlehand, round.rearhand)) {
            int index = seat.ordinal();
            // Strictly greater, so the earlier bidder keeps the tie: that is
            // exactly what "the holder only has to match" means.
            if (opinions.get(index).ceiling() > best) {
                best = opinions.get(index).ceiling();
                winner = index;
            }
        }
        return winner;
    }

    private static void report(List<BoardVerdict> verdicts, boolean oracle, long nanos) {
        int boards = verdicts.size();
        int seats = boards * 3;
        int intendNull = 0;
        int nullWon = 0;
        int passedIn = 0;
        Map<Integer, Integer> beatenBy = new TreeMap<>();
        Map<String, Integer> intentions = new TreeMap<>();
        Map<String, Integer> declared = new TreeMap<>();
        int oracleNull = 0;
        int oracleAny = 0;
        int nullWonAndKept = 0;
        int nullWonTookSkat = 0;
        Map<String, Integer> afterSkat = new TreeMap<>();
        int[] wouldWinAt = new int[4];
        int[] ceilings = {35, 46, 59};

        for (BoardVerdict verdict : verdicts) {
            List<Opinion> opinions = verdict.opinions();
            if (verdict.winner() < 0) passedIn++;
            else {
                SkatAi.ContractType type = opinions.get(verdict.winner()).intends();
                declared.merge(type == null ? "(would not say)" : type.name(), 1, Integer::sum);
            }
            for (int i = 0; i < opinions.size(); i++) {
                Opinion opinion = opinions.get(i);
                intentions.merge(opinion.ceiling() == 0 ? "(passes)"
                        : opinion.intends() == null ? "(would not say)"
                        : opinion.intends().name(), 1, Integer::sum);
                if (opinion.intends() != SkatAi.ContractType.NULL) continue;
                intendNull++;
                if (verdict.winner() == i) {
                    nullWon++;
                    for (int c = 0; c < ceilings.length; c++) wouldWinAt[c]++;
                    if (opinion.tookSkat()) nullWonTookSkat++;
                    SkatAi.ContractType kept = opinion.afterSkat();
                    afterSkat.merge(kept == null ? "(not asked)" : kept.name(), 1, Integer::sum);
                    if (kept == SkatAi.ContractType.NULL) nullWonAndKept++;
                    continue;
                }
                int beat = verdict.winner() < 0 ? 0 : opinions.get(verdict.winner()).ceiling();
                beatenBy.merge(beat, 1, Integer::sum);
                for (int c = 0; c < ceilings.length; c++) {
                    // Strictly above: the holder only has to match, and the
                    // Null seat is not the holder once it has been outbid.
                    if (ceilings[c] > beat) wouldWinAt[c]++;
                }
            }
            if (verdict.oracle() != null) {
                oracleAny++;
                if (verdict.oracle().isNull()) oracleNull++;
            }
        }

        System.out.println();
        System.out.printf(Locale.ROOT, "%d boards, %d seats asked, %.1f s%n",
                boards, seats, nanos / 1e9);
        System.out.println();
        System.out.println("What each seat intended, from its dealt ten");
        intentions.forEach((name, count) -> System.out.printf(Locale.ROOT,
                "  %-18s %6d  %6.2f%%%n", name, count, 100.0 * count / seats));
        System.out.println();
        System.out.println("What the modelled auction declared");
        System.out.printf(Locale.ROOT, "  %-18s %6d  %6.2f%%%n",
                "(passed in)", passedIn, 100.0 * passedIn / boards);
        declared.forEach((name, count) -> System.out.printf(Locale.ROOT,
                "  %-18s %6d  %6.2f%%%n", name, count, 100.0 * count / boards));

        System.out.println();
        System.out.println("Null");
        System.out.printf(Locale.ROOT, "  seats intending it        %6d  %6.2f%% of seats%n",
                intendNull, 100.0 * intendNull / seats);
        System.out.printf(Locale.ROOT, "  ... that won the auction  %6d  %6.2f%% of those%n",
                nullWon, intendNull == 0 ? 0.0 : 100.0 * nullWon / intendNull);
        if (!beatenBy.isEmpty()) {
            System.out.println("  ... outbid, by the winning ceiling:");
            beatenBy.forEach((value, count) -> System.out.printf(Locale.ROOT,
                    "        %4d  %5d%n", value, count));
        }
        if (nullWon > 0) {
            System.out.println();
            System.out.printf(Locale.ROOT,
                    "  ... and still announced Null after the skat  %5d of %d%n",
                    nullWonAndKept, nullWon);
            System.out.printf(Locale.ROOT, "        (%d of them picked the skat up)%n",
                    nullWonTookSkat);
            if (!afterSkat.isEmpty()) {
                System.out.println("        what they announced once they had seen it:");
                afterSkat.forEach((name, count) -> System.out.printf(Locale.ROOT,
                        "          %-16s %4d%n", name, count));
            }
        }
        if (intendNull > 0) {
            System.out.println();
            System.out.println("  If Null could be bid higher -- an UPPER BOUND: this lifts the");
            System.out.println("  ceiling and asks nothing about whether the hand makes the harder");
            System.out.println("  contract, which is a much stronger claim than a plain Null.");
            for (int c = 0; c < ceilings.length; c++) {
                System.out.printf(Locale.ROOT,
                        "        to %2d (%s)  %5d of %d would survive  %6.2f%%%n",
                        ceilings[c],
                        ceilings[c] == 35 ? "Null Hand" : ceilings[c] == 46 ? "Null Ouvert"
                                : "Null Ouvert Hand",
                        wouldWinAt[c], intendNull, 100.0 * wouldWinAt[c] / intendNull);
            }
        }
        if (oracle && oracleAny > 0) {
            System.out.println();
            System.out.printf(Locale.ROOT,
                    "The oracle makes Null the best contract on %d of %d boards (%.2f%%)%n",
                    oracleNull, oracleAny, 100.0 * oracleNull / oracleAny);
            System.out.println("That is the rate a bidder with room to say it could approach.");
        }
        System.out.println();
        if (intendNull == 0) {
            System.out.println("Nothing intends Null at all, so the ceiling is not the problem.");
            System.out.println("Look at HandEvaluator.makeChance and at which contracts");
            System.out.println("SearchAiProvider.candidates offers, not at guaranteedValue.");
        } else if (nullWon == 0) {
            System.out.println("Null is intended and never survives the auction. The 23-point");
            System.out.println("ceiling is the binding constraint, and the counterfactual above");
            System.out.println("prices what lifting it could buy.");
        }
    }

    /**
     * The boards the oracle calls Null, one line each: what the seat holding
     * that hand would go to, what it meant to play, and the two chances that
     * say why. Printed rather than summarised because there are a dozen of
     * them and the shape of a dozen rows is the finding.
     */
    private static void explain(List<BoardVerdict> verdicts, int biddingWorlds) {
        List<BoardVerdict> nulls = new ArrayList<>();
        for (BoardVerdict verdict : verdicts) {
            if (verdict.oracle() != null && verdict.oracle().isNull()) nulls.add(verdict);
        }
        System.out.println();
        System.out.printf(Locale.ROOT,
                "The %d boards where the oracle makes Null the best contract%n", nulls.size());
        if (nulls.isEmpty()) {
            System.out.println("  None in this sample. Deal more boards.");
            return;
        }
        System.out.printf(Locale.ROOT,
                "Chances are this audit's own estimate at %d bidding worlds, not the%n"
                + "player's exact float. P(null) is what the holder thinks of its Null;%n"
                + "the rival is the trump game it would rather have, with its own chance%n"
                + "and its guaranteed value. A high P(null) beside a preferred rival means%n"
                + "the value comparison loses. A low one means makeChance is the harsh part.%n",
                biddingWorlds);
        System.out.println();
        System.out.printf(Locale.ROOT, "  %6s %5s %8s %-10s %8s  %-9s %8s %6s%n",
                "board", "seat", "ceiling", "intends", "P(null)", "rival", "P(rival)", "value");
        int intendedNull = 0;
        double nullChanceWhenNot = 0;
        int counted = 0;
        for (BoardVerdict verdict : nulls) {
            for (int i = 0; i < verdict.opinions().size(); i++) {
                Opinion opinion = verdict.opinions().get(i);
                // Only the seats that would bid at all: a seat that passes on a
                // Null board is a different question and a much quieter one.
                if (opinion.ceiling() == 0 && !Double.isFinite(opinion.nullChance())) continue;
                boolean meansNull = opinion.intends() == SkatAi.ContractType.NULL;
                if (meansNull) intendedNull++;
                else if (Double.isFinite(opinion.nullChance())) {
                    nullChanceWhenNot += opinion.nullChance();
                    counted++;
                }
                System.out.printf(Locale.ROOT, "  %6d %5d %8d %-10s %8.2f  %-9s %8.2f %6d%s%n",
                        verdict.index(), i, opinion.ceiling(),
                        opinion.intends() == null ? "-" : opinion.intends().name(),
                        opinion.nullChance(),
                        opinion.rival() == null ? "-" : opinion.rival().name(),
                        opinion.rivalChance(), opinion.rivalValue(),
                        verdict.winner() == i ? "  <- won" : "");
            }
        }
        System.out.println();
        System.out.printf(Locale.ROOT,
                "  Seats meaning Null on a board the oracle calls Null: %d%n", intendedNull);
        if (counted > 0) {
            System.out.printf(Locale.ROOT,
                    "  Mean P(null) among the seats that meant something else: %.2f%n",
                    nullChanceWhenNot / counted);
            System.out.println("  Well under a half says makeChance is the harsh part and the");
            System.out.println("  ceiling was never the question; near one says the comparison is.");
        }
    }

    private static void usage() {
        System.out.println("""
                Why a player that can declare Null never does.

                Usage:
                  nullAudit [options]

                Options:
                  --player=<id>   contestant to audit          (default search)
                  --boards=<n>    boards to deal               (default 1000)
                  --seed=<n>      match seed; the same boards the arena uses
                  --threads=<n>   parallel boards
                  --oracle        also price each board with the double-dummy
                                  oracle, for the rate a bidder could approach.
                                  Adds about a quarter of a second per board.
                  --explain=null  dump the boards the oracle calls Null, one
                                  line per seat, with the Null chance beside the
                                  chance and value of the game it preferred.
                                  Turns --oracle on by itself.
                  --bidding-worlds=<n>
                                  worlds for those chances; clamp(worlds/3,2,6),
                                  so 6 for belief-32 and 5 at 16  (default 6)
                  --quiet         no progress lines
                """);
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2)
                    : arg.startsWith("-") ? arg.substring(1) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        return options;
    }
}
