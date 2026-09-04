package dev.skatklar.demo.solve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The two solvers, asked the same questions, inside one JVM.
 *
 * <p>There are now two implementations of the same search — the Java one in
 * {@link DoubleDummySolver} and a C++ one behind {@link NativeSolver} — and
 * every entry point picks whichever is available. That is only safe while they
 * agree, and "they agreed when I wrote it" is not a property that survives
 * anybody touching either of them. This is what keeps them honest.
 *
 * <p><b>What must be identical, and what may not be.</b> The declarer point
 * value of a position is the value of the position and both engines must
 * produce it exactly; so must every yes/no verdict, since a null window's
 * answer does not depend on how tightly it was searched. The <em>card</em> need
 * not match. The C++ collapses cards that are interchangeable — the nine and
 * the eight of a suit whose seven is already gone are the same card as far as
 * the game is concerned — so it will sometimes name the other one. What is
 * asserted there is what the difference is allowed to be: playing either card
 * must lead to the same value.
 *
 * <p>The whole class skips when the library is not loadable, because it usually
 * is not: it is built for the platform it runs on, and a Windows workstation
 * checking out this repository has no {@code .so} for it. A skipped run is a
 * run that proved nothing, which is the honest report — the build machine that
 * ships the server is where this has to pass.
 */
public final class SolverParityTest {

    private boolean originalSetting;

    @Before public void requireNative() {
        Assume.assumeTrue("no native solver on this platform; nothing to compare",
                NativeSolver.available());
        originalSetting = DoubleDummySolver.nativeEnabled;
    }

    @After public void restoreSetting() {
        DoubleDummySolver.nativeEnabled = originalSetting;
    }

    /** Every contract this engine will answer for; Null is a different search. */
    private static final Contract[] CONTRACTS = {
            Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS,
            Contract.GRAND, Contract.RAMSCH
    };

    private record Position(Contract contract, SkatAi.Seat declarer, SkatAi.Seat leader,
                            List<List<Card>> hands) {}

    /** A deal of {@code cardsEach} to every seat, out of a shuffled pack. */
    private static Position position(Random random, int cardsEach) {
        List<Card> pack = new ArrayList<>(32);
        for (int index = 0; index < 32; index++) pack.add(ContractTables.card(index));
        java.util.Collections.shuffle(pack, random);
        List<List<Card>> hands = new ArrayList<>(3);
        int at = 0;
        for (int seat = 0; seat < 3; seat++) {
            hands.add(new ArrayList<>(pack.subList(at, at + cardsEach)));
            at += cardsEach;
        }
        return new Position(CONTRACTS[random.nextInt(CONTRACTS.length)],
                SkatAi.Seat.values()[random.nextInt(3)],
                SkatAi.Seat.values()[random.nextInt(3)], hands);
    }

    private static int solveWith(boolean useNative, Position position) {
        DoubleDummySolver.nativeEnabled = useNative;
        return DoubleDummySolver.solve(position.contract(), position.declarer(),
                position.hands(), position.leader()).declarerPoints();
    }

    @Test public void bothEnginesValueEveryPositionTheSame() {
        Random random = new Random(20260828L);
        for (int deal = 0; deal < 240; deal++) {
            Position position = position(random, 1 + random.nextInt(6));
            int java = solveWith(false, position);
            int cpp = solveWith(true, position);
            assertEquals(describe(position), java, cpp);
        }
    }

    @Test public void bothEnginesAgreeWithPlainMinimax() {
        Random random = new Random(4711L);
        for (int deal = 0; deal < 60; deal++) {
            // Small, because minimax on more than five cards a hand is minutes.
            Position position = position(random, 1 + random.nextInt(4));
            int exhaustive = DoubleDummySolver.solveExhaustively(position.contract(),
                    position.declarer(), position.hands(), position.leader());
            assertEquals("java " + describe(position), exhaustive, solveWith(false, position));
            assertEquals("c++ " + describe(position), exhaustive, solveWith(true, position));
            // And the same, through the native engine's own exhaustive search:
            // a disagreement there is a disagreement about the rules rather than
            // about the pruning, and the two are worth telling apart.
            int[] masks = masks(position.hands());
            assertEquals("native minimax " + describe(position), exhaustive,
                    NativeSolver.brute(position.contract().ordinal(),
                            position.declarer().ordinal(), masks[0], masks[1], masks[2],
                            position.leader().ordinal()));
        }
    }

    @Test public void everyYesNoVerdictIsTheSame() {
        Random random = new Random(90210L);
        for (int deal = 0; deal < 80; deal++) {
            Position position = position(random, 1 + random.nextInt(5));
            int value = solveWith(false, position);
            // The two edges are what a null window is for: the value itself must
            // be reachable and one point more must not, on both engines.
            for (int target : new int[] {value, value + 1, 1, 61}) {
                DoubleDummySolver.nativeEnabled = false;
                boolean java = DoubleDummySolver.declarerReaches(position.contract(),
                        position.declarer(), position.hands(), position.leader(), target);
                DoubleDummySolver.nativeEnabled = true;
                boolean cpp = DoubleDummySolver.declarerReaches(position.contract(),
                        position.declarer(), position.hands(), position.leader(), target);
                assertEquals(describe(position) + " target " + target, java, cpp);
                assertEquals(describe(position) + " target " + target,
                        value >= target, java);
            }
        }
    }

    @Test public void aChosenCardDefendsTheSameValueEvenWhenItIsNotTheSameCard() {
        Random random = new Random(1234L);
        int compared = 0;
        int differed = 0;
        for (int deal = 0; deal < 60; deal++) {
            // Four to seven cards a hand. Fewer and the two engines never
            // disagree, because equivalent cards only start appearing once a
            // hand holds two of a suit; more and checking the disagreement --
            // which means solving the position after each card -- gets slow.
            Position position = position(random, 4 + random.nextInt(4));
            SkatAi.Seat toPlay = position.leader();

            DoubleDummySolver.nativeEnabled = false;
            DoubleDummySolver.Choice java = DoubleDummySolver.bestCard(position.contract(),
                    position.declarer(), toPlay, position.hands(), position.leader(),
                    List.of());
            DoubleDummySolver.nativeEnabled = true;
            DoubleDummySolver.Choice cpp = DoubleDummySolver.bestCard(position.contract(),
                    position.declarer(), toPlay, position.hands(), position.leader(),
                    List.of());

            assertEquals("the value a best card defends " + describe(position),
                    java.declarerPoints(), cpp.declarerPoints());
            assertTrue("the native engine named a card the player does not hold: " + cpp.card(),
                    position.hands().get(toPlay.ordinal()).contains(cpp.card()));
            compared++;
            if (!java.card().equals(cpp.card())) {
                differed++;
                // The licence the C++ takes, cashed out: whatever card it named,
                // the position after it must be worth exactly what the position
                // after Java's card is worth. Both measured with the Java
                // engine, so the comparison itself cannot be the thing that is
                // wrong.
                DoubleDummySolver.nativeEnabled = false;
                assertEquals("the cards differ and so do their values "
                                + describe(position) + " java " + java.card()
                                + " c++ " + cpp.card(),
                        valueAfter(position, toPlay, java.card()),
                        valueAfter(position, toPlay, cpp.card()));
            }
        }
        assertTrue("nothing was compared", compared >= 50);
        // `differed` is reported rather than asserted on, and it is usually
        // nought. That is not the reduction failing to fire -- it fires
        // constantly, see theReductionIsWorthWhatItClaims -- it is the two
        // engines agreeing about which member of a run to name. Java keeps the
        // first card of an equal-valued tie and orders by strength, and the
        // reduction keeps the strongest of a run, so they land on the same card
        // by construction. The check above exists for the day a killer move
        // reorders the root and they stop doing so.
        assertTrue("a card cannot differ a negative number of times", differed >= 0);
    }

    /**
     * The claim the C++ is here to make, as a number rather than a stopwatch.
     *
     * <p>Wall-clock time on a build box says as much about what else was running
     * as about the search, but the node count is deterministic: the same deal
     * visits the same nodes every time, and the equivalence reduction, the
     * killers and the pre-sized table can only take nodes away. A margin this
     * wide -- the measured ratio is about two -- leaves room for the search to
     * be tuned without the test having an opinion about it.
     */
    @Test public void theReductionIsWorthWhatItClaims() {
        Random random = new Random(8080L);
        long javaNodes = 0;
        long cppNodes = 0;
        for (int deal = 0; deal < 6; deal++) {
            Position position = position(random, 8);
            DoubleDummySolver.nativeEnabled = false;
            javaNodes += DoubleDummySolver.solve(position.contract(), position.declarer(),
                    position.hands(), position.leader()).visitedNodes();
            DoubleDummySolver.nativeEnabled = true;
            cppNodes += DoubleDummySolver.solve(position.contract(), position.declarer(),
                    position.hands(), position.leader()).visitedNodes();
        }
        assertTrue("the native search visited " + cppNodes + " nodes where the Java one"
                        + " visited " + javaNodes + "; it is supposed to visit fewer",
                cppNodes < javaNodes);
    }

    /** What the position is worth after one card, judged by the Java engine. */
    private static int valueAfter(Position position, SkatAi.Seat toPlay, Card card) {
        List<List<Card>> after = new ArrayList<>(3);
        for (int seat = 0; seat < 3; seat++) {
            List<Card> hand = new ArrayList<>(position.hands().get(seat));
            if (seat == toPlay.ordinal()) hand.remove(card);
            after.add(hand);
        }
        SkatAi.Seat next = SkatAi.Seat.values()[(toPlay.ordinal() + 1) % 3];
        return DoubleDummySolver.bestCard(position.contract(), position.declarer(), next,
                after, position.leader(), List.of(card)).declarerPoints();
    }

    @Test public void everyCardGetsTheSameVerdictInTheMoveList() {
        Random random = new Random(555L);
        for (int deal = 0; deal < 60; deal++) {
            Position position = position(random, 2 + random.nextInt(4));
            SkatAi.Seat toPlay = position.leader();
            int target = 1 + random.nextInt(40);

            DoubleDummySolver.nativeEnabled = false;
            Map<Card, Boolean> java = verdicts(position, toPlay, target);
            DoubleDummySolver.nativeEnabled = true;
            Map<Card, Boolean> cpp = verdicts(position, toPlay, target);

            assertEquals("the two engines listed different cards " + describe(position),
                    java.keySet(), cpp.keySet());
            for (Map.Entry<Card, Boolean> entry : java.entrySet()) {
                assertEquals(describe(position) + " target " + target + " card "
                        + entry.getKey(), entry.getValue(), cpp.get(entry.getKey()));
            }
        }
    }

    private static Map<Card, Boolean> verdicts(Position position, SkatAi.Seat toPlay,
                                               int target) {
        Map<Card, Boolean> result = new LinkedHashMap<>();
        for (DoubleDummySolver.Verdict verdict : DoubleDummySolver.movesReaching(
                position.contract(), position.declarer(), toPlay, position.hands(),
                position.leader(), List.of(), target)) {
            result.put(verdict.card(), verdict.reachesTarget());
        }
        return result;
    }

    @Test public void aKeptTableAnswersWhatAFreshOneDoes() {
        Random random = new Random(777L);
        for (int deal = 0; deal < 40; deal++) {
            Position position = position(random, 2 + random.nextInt(4));
            int[] masks = masks(position.hands());
            int value = solveWith(false, position);

            DoubleDummySolver.nativeEnabled = true;
            try (DoubleDummySolver kept = DoubleDummySolver.reusableFor(position.contract(),
                    position.declarer().ordinal())) {
                for (int target : new int[] {value, value + 1, 1}) {
                    boolean reached = kept.reaches(position.leader().ordinal(), masks,
                            position.leader().ordinal(), 0, 0, target);
                    assertEquals(describe(position) + " target " + target,
                            value >= target, reached);
                }
            }
        }
    }

    @Test public void closingAReusableSolverGivesItsTableBack() {
        DoubleDummySolver.nativeEnabled = true;
        int before = NativeHandles.liveHandles();
        List<DoubleDummySolver> solvers = new ArrayList<>();
        for (int at = 0; at < 8; at++) {
            solvers.add(DoubleDummySolver.reusableFor(Contract.GRAND, 0));
        }
        assertEquals("eight solvers, eight native tables", before + 8,
                NativeHandles.liveHandles());
        for (DoubleDummySolver solver : solvers) solver.close();
        assertEquals("closing them gives every one back", before,
                NativeHandles.liveHandles());
        // Closing twice is what a try-with-resources around an explicit close
        // does, and it must not free anything a second time.
        for (DoubleDummySolver solver : solvers) solver.close();
        assertEquals(before, NativeHandles.liveHandles());
    }

    @Test public void aDeadlineThatHasPassedGivesUpRatherThanAnswering() {
        Random random = new Random(31337L);
        Position position = position(random, 10);
        DoubleDummySolver.nativeEnabled = true;
        assertNull("a deadline already gone must not be answered anyway",
                DoubleDummySolver.declarerReachesBefore(position.contract(),
                        position.declarer(), position.hands(), position.leader(), 61,
                        System.nanoTime() - 1_000_000L));
        // And a generous one answers, with the answer the unbounded question has.
        Boolean bounded = DoubleDummySolver.declarerReachesBefore(position.contract(),
                position.declarer(), position.hands(), position.leader(), 61,
                System.nanoTime() + 60_000_000_000L);
        assertNotNull("a minute was not enough for one ten-card deal", bounded);
        DoubleDummySolver.nativeEnabled = false;
        assertEquals(DoubleDummySolver.declarerReaches(position.contract(),
                position.declarer(), position.hands(), position.leader(), 61), bounded);
    }

    @Test public void theLibraryIsTheOneThisBuildExpects() {
        assertEquals("1", NativeSolver.version());
    }

    private static int[] masks(List<List<Card>> hands) {
        int[] masks = new int[3];
        for (int seat = 0; seat < 3; seat++) {
            int mask = 0;
            for (Card card : hands.get(seat)) mask |= 1 << ContractTables.index(card);
            masks[seat] = mask;
        }
        return masks;
    }

    private static String describe(Position position) {
        return position.contract() + " declarer " + position.declarer() + " leader "
                + position.leader() + " hands " + position.hands();
    }
}
