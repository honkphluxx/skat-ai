package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.search.HandEvaluator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What a bidding probability is worth, measured against what actually happens.
 *
 * <p>The bidder decides with
 * {@code declaringIsWorth(v, c) = v*c - 2*v*(1-c)*lossWeight}, and at the
 * reference aggression {@code lossWeight} is exactly 1.0, so it declares when
 * {@code c > 2/3}. That threshold is not a taste: a lost game is charged at
 * twice its value, so {@code c = 2(1-c)} is the arithmetic of the score sheet.
 *
 * <p><b>But {@code c} is on a different scale from the threshold it is compared
 * against, and always has been.</b> {@link HandEvaluator} says so in its own
 * first paragraphs: the number is a share of sampled worlds that hold up
 * <em>against perfect defence</em>, and so "systematically pessimistic — real
 * defenders let contracts through that a solver would beat &hellip; it means the
 * threshold a player bids at is not the win rate it will actually see. The arena
 * is what converts one into the other." That conversion was never built, so a
 * double-dummy probability has been going straight into a real-payoff
 * comparison. This is the arena doing the converting.
 *
 * <p><b>Measured, and the pessimism hypothesis did not survive it.</b> Over
 * 1,800 hands the gap is positive at the bottom and <em>negative</em> at the
 * top: 0.000 predicted is made 15.5% of the time, 1.000 predicted is made 89.2%
 * of the time. That is not a defence model that is too harsh, which would move
 * every bucket the same way. It is a six-sample estimate regressing to the mean
 * -- 41% of hands land on 0.000 or 1.000, because six worlds saturate easily,
 * and conditioning on a saturated count is what bends both ends inward.
 *
 * <p>Which leaves a sharper conclusion than the one this tool was built to
 * find. <b>Monotone recalibration cannot change a single decision here.</b> Map
 * each level to what it was worth and re-test against break-even and the
 * declared set is identical, because the cut falls between the same two
 * representable levels either way. With a quantised estimate, calibration is a
 * no-op for the bid; only resolution is a lever.
 *
 * <p>Method, per seat per board: ask the evaluator for the contract this hand
 * would intend and what it thinks the chance is, then <b>play the board at that
 * contract whether or not the bidder would have declared it</b>. Declining to
 * play the ones below the threshold would leave the curve unmeasured exactly
 * where the decision is made.
 *
 * <pre>
 *   ./gradlew :arena:calibration --args="--player=belief-32 --boards=600 --threads=8"
 * </pre>
 */
public final class CalibrationMain {

    /** The reference loss weight, {@code 1.5 - aggression} at aggression 0.5. */
    private static final double LOSS_WEIGHT = 1.0;

    /** Where declaring breaks even on the score sheet: c = 2(1-c). */
    private static final double BREAK_EVEN = 2.0 / 3.0;

    private CalibrationMain() {}

    /** One hand, what it was promised, and what it got. */
    private record Sample(long board, Contract contract, double predicted,
                          int value, boolean won) {
        /** Game points this contract actually returned, as the score sheet pays. */
        double points() { return won ? value : -2.0 * value; }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help") || options.containsKey("h")) { usage(); return; }

        String playerId = options.getOrDefault("player", "belief-32");
        // Who defends. The default seats the player against copies of itself,
        // which measures declaring against OUR defence -- and ours is about
        // eight points tougher than XSkat's (94% made against theirs, 86%
        // against ours, at oracle contracts). A threshold tuned against the
        // harsher defence is too strict for the softer one. So the question
        // "is there a boldness dividend" has a different answer per opponent,
        // and this is how it is asked of a specific one.
        String opponentId = options.getOrDefault("opponents", playerId);
        int boards = intOption(options, "boards", 400);
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        int threads = intOption(options, "threads", Runtime.getRuntime().availableProcessors());
        // SearchAiProvider.biddingWorlds() is clamp(worlds/3, 2, 6): 6 for
        // belief-32. Stated rather than read off the contestant, as in NullAudit.
        int biddingWorlds = intOption(options, "bidding-worlds", 6);
        boolean quiet = options.containsKey("quiet");

        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant contestant = registry.resolve(playerId);
        Contestant opponents = registry.resolve(opponentId);

        System.out.printf(Locale.ROOT,
                "Calibration: %s, %d boards x 3 seats, seed %d, %d threads, %d bidding worlds%n",
                contestant.displayName(), boards, seed, threads, biddingWorlds);
        System.out.printf(Locale.ROOT, "Defended by: %s%n", opponents.displayName());
        System.out.println("Every seat's intended contract is played out, "
                + "including the ones it would decline.");
        System.out.println();

        long startedAt = System.nanoTime();
        List<Sample> samples = new ArrayList<>(boards * 3);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<List<Sample>>> pending = new ArrayList<>(boards);
            for (int index = 0; index < boards; index++) {
                pending.add(pool.submit(
                        measure(contestant, opponents, Board.of(seed, index), seed, biddingWorlds)));
            }
            int done = 0;
            for (Future<List<Sample>> future : pending) {
                samples.addAll(future.get());
                if (!quiet && ++done % 50 == 0) {
                    System.out.printf(Locale.ROOT, "  %d / %d boards%n", done, boards);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        report(samples, System.nanoTime() - startedAt);
    }

    private static Callable<List<Sample>> measure(Contestant contestant, Contestant opponents,
                                                  Board board, long seed, int biddingWorlds) {
        return () -> {
            List<List<Card>> hands = List.of(
                    board.deal().human, board.deal().opponentOne, board.deal().opponentTwo);
            List<Sample> found = new ArrayList<>(3);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                List<Card> hand = hands.get(seat.ordinal());
                Intention intention = intend(board, seat, hand, biddingWorlds);
                if (intention == null) continue;
                Boolean won = playOut(contestant, opponents, board, seat, intention, seed);
                if (won == null) continue;
                found.add(new Sample(board.index(), intention.contract, intention.chance,
                        intention.value, won));
            }
            return found;
        };
    }

    private record Intention(Contract contract, double chance, int value) {}

    /**
     * The contract this hand would announce, and what the evaluator thinks of it.
     *
     * <p>Mirrors {@code SearchAiProvider}'s own loop: the two best-looking trump
     * games and Null, ranked by expected value at the reference loss weight. It
     * is a mirror and not the thing itself, because the provider keeps the
     * number private and reaching for it would mean widening the shipped
     * interface for a diagnostic. The consequence is that this runs on its own
     * random stream, so an individual hand's number is close to the player's
     * rather than identical -- which does not matter to a curve fitted over
     * thousands of them.
     */
    private static Intention intend(Board board, SkatAi.Seat seat, List<Card> hand,
                                    int biddingWorlds) {
        try {
            HandEvaluator evaluator = new HandEvaluator(biddingWorlds,
                    new java.util.Random(Seeds.mix(board.index(), seat.ordinal(), 0xCA11B)));
            List<Contract> candidates = new ArrayList<>(
                    HandEvaluator.plausibleTrumpGames(hand, 2));
            candidates.add(Contract.NULL);
            double best = Double.NEGATIVE_INFINITY;
            Intention chosen = null;
            for (Contract contract : candidates) {
                double chance = evaluator.makeChance(
                        contract, hand, seat, board.round().forehand);
                if (Double.isNaN(chance)) continue;
                int value = SkatRules.guaranteedValue(contract, hand);
                double expected = value * chance - 2.0 * value * (1 - chance) * LOSS_WEIGHT;
                if (expected > best) {
                    best = expected;
                    chosen = new Intention(contract, chance, value);
                }
            }
            return chosen;
        } catch (RuntimeException unevaluated) {
            return null;
        }
    }

    /**
     * @return whether the declarer made it, or null if the board could not be
     *         played at that contract
     */
    private static Boolean playOut(Contestant contestant, Contestant opponents, Board board,
                                   SkatAi.Seat declarer, Intention intention, long seed) {
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            Contestant who = seat == declarer ? contestant : opponents;
            seating.put(seat, who.newProvider(
                    Seeds.mix(seed, board.index(), declarer.ordinal(), seat.ordinal())));
        }
        // The bid the contract must cover: what the hand guarantees, floored at
        // 18. Anything higher would price in an overbid the bidder never made,
        // and score a made contract as a loss.
        int bid = Math.max(18, intention.value);
        try {
            GameOutcome outcome = GameRunner.playFixed(board, seating,
                    new ContractSource.FixedContract(declarer, intention.contract, bid),
                    Seeds.mix(seed, board.index(), declarer.ordinal(), 0xE1E1E1L));
            return outcome.declarerWon();
        } catch (RuntimeException unplayable) {
            return null;
        }
    }

    // ------------------------------------------------------------------- report

    private static void report(List<Sample> samples, long nanos) {
        if (samples.isEmpty()) {
            System.out.println("No hand could be both priced and played.");
            return;
        }
        System.out.printf(Locale.ROOT, "%d hands priced and played in %.0f s%n%n",
                samples.size(), nanos / 1e9);

        System.out.println("Reliability: what a predicted chance was actually worth");
        System.out.printf(Locale.ROOT, "  %-14s %6s %10s %10s %9s%n",
                "predicted", "hands", "predicted", "actual", "gap");
        for (int bucket = 0; bucket < 10; bucket++) {
            double low = bucket / 10.0;
            double high = low + 0.1;
            List<Sample> in = new ArrayList<>();
            for (Sample sample : samples) {
                if (sample.predicted() >= low && (sample.predicted() < high || bucket == 9)) {
                    in.add(sample);
                }
            }
            if (in.isEmpty()) continue;
            double predicted = in.stream().mapToDouble(Sample::predicted).average().orElse(0);
            double actual = in.stream().mapToDouble(s -> s.won() ? 1 : 0).average().orElse(0);
            System.out.printf(Locale.ROOT, "  %.2f - %.2f    %6d %9.3f %10.3f %+9.3f%n",
                    low, high, in.size(), predicted, actual, actual - predicted);
        }
        System.out.println();

        System.out.println("By contract, because the gap need not be the same for each");
        System.out.printf(Locale.ROOT, "  %-10s %6s %10s %10s %9s%n",
                "contract", "hands", "predicted", "actual", "gap");
        Map<Contract, List<Sample>> byContract = new LinkedHashMap<>();
        for (Sample sample : samples) {
            byContract.computeIfAbsent(sample.contract(), any -> new ArrayList<>()).add(sample);
        }
        byContract.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().ordinal()))
                .forEach(entry -> {
                    List<Sample> in = entry.getValue();
                    double predicted = in.stream()
                            .mapToDouble(Sample::predicted).average().orElse(0);
                    double actual = in.stream()
                            .mapToDouble(s -> s.won() ? 1 : 0).average().orElse(0);
                    System.out.printf(Locale.ROOT, "  %-10s %6d %9.3f %10.3f %+9.3f%n",
                            entry.getKey(), in.size(), predicted, actual, actual - predicted);
                });
        System.out.println();

        // The question the sweep was going to cost two nights to answer, asked
        // of one dataset instead. Cut at the levels the estimate can actually
        // take rather than at round numbers: N bidding worlds means the answer
        // is a count out of N, so it is quantised to multiples of 1/N and most
        // "thresholds" between two of them select the identical set of hands.
        // A sweep over round numbers reports a plateau as if it were a result,
        // and names whichever end of it came first.
        List<Double> levels = new ArrayList<>();
        for (Sample sample : samples) {
            if (!levels.contains(sample.predicted())) levels.add(sample.predicted());
        }
        java.util.Collections.sort(levels);

        System.out.println("What the estimate can actually say, and what each level was worth");
        System.out.printf(Locale.ROOT, "  %-9s %7s %9s%n", "predicted", "hands", "actual");
        for (double level : levels) {
            List<Sample> at = samples.stream()
                    .filter(sample -> sample.predicted() == level).toList();
            System.out.printf(Locale.ROOT, "  %9.3f %7d %9.3f%n", level, at.size(),
                    at.stream().mapToDouble(sample -> sample.won() ? 1 : 0)
                            .average().orElse(0));
        }
        System.out.println();

        System.out.println("Where to put the cut");
        System.out.println("  Points are game points over ALL " + samples.size()
                + " hands: a hand not declared scores nothing, one");
        System.out.println("  declared scores its value, or twice its value against.");
        System.out.printf(Locale.ROOT, "  %-14s %8s %8s %9s %12s%n",
                "declare at", "declared", "share", "won", "points/hand");
        double bestPoints = Double.NEGATIVE_INFINITY;
        double bestLevel = Double.NaN;
        double todayPoints = Double.NaN;
        double todayLevel = Double.NaN;
        for (double level : levels) {
            List<Sample> declared = samples.stream()
                    .filter(sample -> sample.predicted() >= level).toList();
            double points = declared.stream().mapToDouble(Sample::points).sum() / samples.size();
            double won = declared.isEmpty() ? 0
                    : declared.stream().mapToDouble(s -> s.won() ? 1 : 0).average().orElse(0);
            // The bidder declares on `declaring > passing`, STRICTLY. A holding
            // seat has passing = 0 (ramschRisk is zero once anyone has bid), so
            // it needs the expected value strictly above zero -- which means a
            // chance strictly above 2/3, not at it. The level it actually cuts
            // at is therefore the lowest one greater than break-even, and a hand
            // sitting exactly on 2/3 is refused.
            boolean today = level > BREAK_EVEN
                    && levels.stream().noneMatch(other -> other > BREAK_EVEN && other < level);
            if (today) { todayPoints = points; todayLevel = level; }
            if (points > bestPoints) { bestPoints = points; bestLevel = level; }
            System.out.printf(Locale.ROOT, "  p >= %.3f    %8d %7.1f%% %9.3f %12.2f%s%n",
                    level, declared.size(), 100.0 * declared.size() / samples.size(), won,
                    points, today ? "   <- today, holding" : "");
        }
        System.out.println();
        System.out.printf(Locale.ROOT,
                "Best cut in this sample: p >= %.3f at %.2f points a hand;"
                + " today's cut pays %.2f.%n", bestLevel, bestPoints, todayPoints);
        System.out.println("Both chosen and scored on the same hands, so that comparison"
                + " is not yet worth");
        System.out.println("anything. The one below is.");

        // Choose on the even boards, pay on the odd ones. Boards and not hands:
        // the three seats of one board share a deal, and splitting them would
        // let the same cards inform both halves.
        //
        // This is not ceremony. The first version of this tool reported the best
        // in-sample cut as a finding worth 1.28 game points a hand, off nine
        // hands in one bucket, and it evaporated at scale. A finer estimate
        // makes that worse rather than better: more levels means more candidates
        // to maximise over, and the maximum of many noisy numbers is biased
        // upward by construction.
        List<Sample> fit = samples.stream().filter(x -> x.board() % 2 == 0).toList();
        List<Sample> test = samples.stream().filter(x -> x.board() % 2 == 1).toList();
        if (fit.isEmpty() || test.isEmpty()) return;

        double chosen = Double.NaN;
        double chosenPoints = Double.NEGATIVE_INFINITY;
        for (double level : levels) {
            double points = pointsPerHand(fit, level);
            if (points > chosenPoints) { chosenPoints = points; chosen = level; }
        }
        double chosenOnTest = pointsPerHand(test, chosen);
        double todayOnTest = pointsPerHand(test, todayLevel);

        System.out.println();
        System.out.println("Held out: the cut chosen on half the boards, paid on the other");
        System.out.printf(Locale.ROOT,
                "  chosen on %4d hands   p >= %.3f, worth %.2f there%n",
                fit.size(), chosen, chosenPoints);
        System.out.printf(Locale.ROOT,
                "  paid on   %4d hands   %.2f points%n", test.size(), chosenOnTest);
        System.out.printf(Locale.ROOT,
                "  today's cut, same hands %.2f points%n", todayOnTest);
        System.out.println();

        // How many held-out hands the two cuts actually disagree about. Without
        // this the verdict reads an edge off the whole sample when the sample
        // that produced it is a handful: the first run of this block reported
        // +0.11 points a hand as "the one to act on", and the two cuts differed
        // over nine hands. An edge is only as trustworthy as the hands that
        // separate the two things being compared.
        double lower = Math.min(chosen, todayLevel);
        double upper = Math.max(chosen, todayLevel);
        long separating = test.stream()
                .filter(x -> x.predicted() >= lower && x.predicted() < upper).count();

        double edge = chosenOnTest - todayOnTest;
        if (Math.abs(chosen - todayLevel) >= 1e-9) {
            System.out.printf(Locale.ROOT,
                    "  the two cuts differ over %d of those hands%n", separating);
            System.out.println();
        }
        if (Math.abs(chosen - todayLevel) < 1e-9) {
            System.out.println("The search picked today's cut. The threshold is right and the"
                    + " auction gap is");
            System.out.println("somewhere else -- which is a result, not a null one.");
        } else if (edge > 0) {
            System.out.printf(Locale.ROOT,
                    "A different cut survived the holdout, worth %+.2f points a hand --%n"
                    + "decided by %d hands.%n", edge, separating);
            if (separating < 40) {
                System.out.println("Which is far too few to act on. Two cuts that disagree"
                        + " about a handful of");
                System.out.println("hands cannot be separated by those hands, whichever way"
                        + " the sign falls.");
                System.out.println("Read this as \"no difference found\", and if the"
                        + " difference matters, run");
                System.out.println("enough boards that the disputed band holds a few hundred.");
            }
        } else {
            System.out.printf(Locale.ROOT,
                    "The chosen cut does NOT survive the holdout: %+.2f points a hand against%n"
                    + "today's. It beat today only on the hands that chose it, which is what%n"
                    + "overfitting looks like from the inside. Today's cut stands.%n", edge);
        }

        System.out.printf(Locale.ROOT,
                "%nSample size is %d hands, and the cut is decided by the few hundred nearest%n"
                + "the threshold. Read the direction here and the size from a longer run.%n",
                samples.size());
    }

    /** Game points a cut returns per hand, over every hand in {@code over}. */
    private static double pointsPerHand(List<Sample> over, double level) {
        if (over.isEmpty() || Double.isNaN(level)) return Double.NaN;
        return over.stream().filter(sample -> sample.predicted() >= level)
                .mapToDouble(Sample::points).sum() / over.size();
    }

    // ------------------------------------------------------------------- options

    private static void usage() {
        System.out.println("""
                Measures what the bidder's probability is worth against what happens.

                  --player=<id>          the contestant to price and to declare (belief-32)
                  --opponents=<id>       who defends; default is the player itself.
                                         Use xskat to ask whether the threshold is
                                         right AGAINST XSKAT rather than against us.
                  --boards=<n>           boards; each gives up to 3 hands      (400)
                  --seed=<n>             the usual match seed                    (1)
                  --threads=<n>          parallel boards            (all cores)
                  --bidding-worlds=<n>   worlds the estimate is drawn from       (6)
                  --quiet                no progress lines
                """);
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String argument : args) {
            String trimmed = argument.startsWith("--") ? argument.substring(2) : argument;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        return options;
    }
}
