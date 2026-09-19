package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.solve.DoubleDummySolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Weighing a hand over threads gives the same number as weighing it on one.
 *
 * <p>The auction could not be spread while card play could, and the difference
 * was one line: {@code HandEvaluator} shuffled inside the loop that solved, so
 * the draws and the solves were interleaved and the random stream ran through
 * the middle of the work. Drawing every world first is the whole change, and
 * this is what says the change was behaviour-preserving — same seed, same
 * worlds, same share, on any number of threads.
 *
 * <p>Identical to the digit rather than close, because the share is
 * {@code made / solved} over the same worlds in a different order, and integer
 * counts do not care about order.
 */
public class BiddingThreadsTest {

    /** The ten cards dealt to one seat from a seeded pack. */
    private static List<Card> hand(long seed) {
        List<Card> pack = new ArrayList<>(SkatDeck.ordered());
        java.util.Collections.shuffle(pack, new Random(seed));
        return new ArrayList<>(pack.subList(0, 10));
    }

    private static double chanceOn(int threads, Contract contract, long seed, int worlds) {
        return new HandEvaluator(worlds, new Random(4711L), threads)
                .makeChance(contract, hand(seed), SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN);
    }

    @Test public void aTrumpGameWeighsTheSameOnFourThreadsAsOnOne() {
        for (long seed : new long[] {1L, 7L, 23L}) {
            assertEquals(chanceOn(1, Contract.CLUBS, seed, 6),
                    chanceOn(4, Contract.CLUBS, seed, 6), 0.0);
        }
    }

    @Test public void grandWeighsTheSameOnFourThreadsAsOnOne() {
        assertEquals(chanceOn(1, Contract.GRAND, 11L, 6),
                chanceOn(4, Contract.GRAND, 11L, 6), 0.0);
    }

    /** Null goes down its own solver, so it is asked separately. */
    @Test public void nullWeighsTheSameOnFourThreadsAsOnOne() {
        assertEquals(chanceOn(1, Contract.NULL, 3L, 6),
                chanceOn(4, Contract.NULL, 3L, 6), 0.0);
    }

    /**
     * More threads than worlds is legal, and so is a count below one.
     *
     * <p>The chunking divides the worlds among the threads rather than handing
     * one to each, so asking for more threads than there are worlds has to
     * degrade to fewer chunks instead of producing empty ones.
     */
    @Test public void absurdThreadCountsWeighTheSame() {
        double one = chanceOn(1, Contract.CLUBS, 1L, 4);
        assertEquals(one, chanceOn(64, Contract.CLUBS, 1L, 4), 0.0);
        assertEquals(one, chanceOn(0, Contract.CLUBS, 1L, 4), 0.0);
        assertEquals(one, chanceOn(-3, Contract.CLUBS, 1L, 4), 0.0);
    }

    /**
     * A budget already gone reads as silence, and reads the same on any threads.
     *
     * <p>Not the rule card play has, and the difference is the point. A card
     * must be chosen, so there the search always does at least one world. A bid
     * need not be made: {@code price} reads NaN as "not a single world was
     * solved in time", which is silence rather than a low chance, and the seat
     * bids the delegate's way instead. {@link DoubleDummySolver#declarerReaches
     * Before} returns null the moment it is handed a deadline in the past, so
     * that is what a spent budget produces. Spreading the worlds over threads
     * must not quietly turn that silence into a number.
     */
    @Test public void aSpentBudgetIsSilenceOnAnyNumberOfThreads() {
        for (int threads : new int[] {1, 4}) {
            double chance = new HandEvaluator(6, new Random(4711L), threads).makeChanceBefore(
                    Contract.CLUBS, hand(1L), SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN,
                    System.nanoTime() - 1_000_000_000L);
            assertTrue("a trump game out of time says nothing at all, on " + threads
                    + " threads", Double.isNaN(chance));
        }
    }

    /**
     * Null is the exception, and it is deliberate rather than an oversight.
     *
     * <p>{@code NullSolver} is handed no deadline -- it is decided on tricks
     * rather than points, prunes far harder, and has no tail worth guarding
     * against -- so a Null world always finishes once it is started. What bounds
     * it is the check between worlds, and that check never fires before the
     * first, so a spent budget leaves a Null answered from one world rather than
     * from none.
     */
    @Test public void aSpentBudgetStillAnswersAboutNull() {
        for (int threads : new int[] {1, 4}) {
            double chance = new HandEvaluator(6, new Random(4711L), threads).makeChanceBefore(
                    Contract.NULL, hand(3L), SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN,
                    System.nanoTime() - 1_000_000_000L);
            assertFalse("a Null out of time still answers, on " + threads + " threads",
                    Double.isNaN(chance));
            assertTrue(chance >= 0.0 && chance <= 1.0);
        }
    }

    /** Unbounded, nothing is ever silent: every world gets solved. */
    @Test public void anUnboundedEvaluationIsNeverSilent() {
        for (int threads : new int[] {1, 4}) {
            assertFalse(Double.isNaN(chanceOn(threads, Contract.CLUBS, 1L, 6)));
            assertFalse(Double.isNaN(chanceOn(threads, Contract.NULL, 3L, 6)));
        }
    }
}
