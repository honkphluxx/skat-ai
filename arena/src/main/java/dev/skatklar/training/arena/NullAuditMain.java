package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
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

    /** What one seat said about one board. */
    private record Opinion(int ceiling, SkatAi.ContractType intends) {}

    /** What one board came to. */
    private record BoardVerdict(List<Opinion> opinions, int winner, Contract oracle) {}

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
        boolean oracle = options.containsKey("oracle");
        boolean quiet = options.containsKey("quiet");

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
                pending.add(pool.submit(audit(contestant, board, seed, oracleSource)));
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
    }

    private static Callable<BoardVerdict> audit(Contestant contestant, Board board,
                                                long seed, ContractSource oracleSource) {
        return () -> {
            List<List<Card>> hands = List.of(
                    board.deal().human, board.deal().opponentOne, board.deal().opponentTwo);
            List<Opinion> opinions = new ArrayList<>(3);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                // Seeded the way the arena seeds a seat, so an audit and a match
                // ask the same player rather than two samples of one.
                SkatAiProvider provider = contestant.newProvider(
                        Seeds.mix(seed, board.index(), seat.ordinal()));
                try (SkatAiSession session = provider.createSession()) {
                    opinions.add(ask(session, board, seat, hands.get(seat.ordinal())));
                }
            }
            Contract oracle = null;
            if (oracleSource != null) {
                ContractSource.FixedContract best = oracleSource.contractFor(board);
                oracle = best == null ? null : best.contract();
            }
            return new BoardVerdict(opinions, winnerOf(opinions, board.round()), oracle);
        };
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
        if (ceiling == 0) return new Opinion(0, null);

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
        return new Opinion(ceiling, intends);
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
