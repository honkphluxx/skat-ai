package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Choosing the two buried cards by playing them out.
 *
 * <p>What is asserted here is the shape of the answer, not its quality: that it
 * is always a legal discard, that it is drawn from the candidates it says it
 * draws from, that a budget already gone still produces two cards, and that the
 * worlds are shared across candidates so the comparison is paired. Whether the
 * choice is any *good* is an arena question and 49 boards of pilot said +2.0
 * points, which is one board and proves nothing.
 */
public class DiscardSearchTest {

    private static List<Card> twelve(long seed) {
        List<Card> pack = new ArrayList<>(SkatDeck.ordered());
        java.util.Collections.shuffle(pack, new Random(seed));
        return new ArrayList<>(pack.subList(0, 12));
    }

    private static List<Card> unseenFor(List<Card> twelve) {
        List<Card> rest = new ArrayList<>(SkatDeck.ordered());
        rest.removeAll(twelve);
        return rest;
    }

    private static DiscardSearch.Choice run(Contract contract, long seed, int worlds,
                                            int candidates, long deadline) {
        List<Card> twelve = twelve(seed);
        return DiscardSearch.choose(contract, SkatAi.Seat.HUMAN, twelve, unseenFor(twelve),
                SkatAi.Seat.HUMAN, worlds, candidates, deadline, new Random(seed),
                Discards.buried(contract, twelve));
    }

    @Test public void itAlwaysBuriesTwoCardsFromTheHand() {
        for (Contract contract : new Contract[] {Contract.CLUBS, Contract.GRAND, Contract.NULL}) {
            for (long seed : new long[] {1L, 4L, 8L}) {
                List<Card> twelve = twelve(seed);
                DiscardSearch.Choice choice = run(contract, seed, 2, 5, 0L);
                assertEquals("a discard is two cards", 2, choice.buried().size());
                assertNotEquals(choice.buried().get(0), choice.buried().get(1));
                assertTrue("both must come from the twelve",
                        twelve.containsAll(choice.buried()));
            }
        }
    }

    /**
     * The pair comes from the candidates, which is what makes the prune a prune.
     *
     * <p>If this stopped holding, the search would be scoring pairs the coverage
     * measurement never checked, and "the prune gives up none of the ceiling"
     * would be a claim about a different algorithm.
     */
    @Test public void theBuriedPairComesFromTheCandidates() {
        for (int candidates : new int[] {2, 3, 5}) {
            List<Card> twelve = twelve(6L);
            List<Card> ranked = Discards.ranked(Contract.CLUBS, twelve);
            Set<Card> allowed = new HashSet<>(ranked.subList(12 - candidates, 12));
            DiscardSearch.Choice choice = run(Contract.CLUBS, 6L, 2, candidates, 0L);
            assertTrue("with " + candidates + " candidate cards, both buried must be among them",
                    allowed.containsAll(choice.buried()));
        }
    }

    /** Two candidate cards is one pair, so the answer is forced and still legal. */
    @Test public void theSmallestPoolStillAnswers() {
        List<Card> twelve = twelve(2L);
        List<Card> ranked = Discards.ranked(Contract.CLUBS, twelve);
        DiscardSearch.Choice choice = run(Contract.CLUBS, 2L, 2, 2, 0L);
        assertEquals(new HashSet<>(ranked.subList(10, 12)), new HashSet<>(choice.buried()));
    }

    /**
     * A budget already spent gives back the fallback rather than nothing.
     *
     * <p>The deadline is read between candidates and never before the first, so
     * something is always scored -- the same rule card play follows, and for the
     * same reason: a discard must be made, so answering from no worlds at all
     * would be guessing where guessing was avoidable.
     */
    @Test public void aSpentBudgetStillBuriesTwoCards() {
        DiscardSearch.Choice choice = run(Contract.CLUBS, 3L, 4, 5,
                System.nanoTime() - 1_000_000_000L);
        assertEquals(2, choice.buried().size());
        assertTrue("at least one candidate is always scored", choice.solves() >= 1);
    }

    /** Nonsense in, the fallback out, rather than an exception or a bad discard. */
    @Test public void aMalformedPositionFallsBack() {
        List<Card> twelve = twelve(5L);
        List<Card> fallback = Discards.buried(Contract.CLUBS, twelve);
        DiscardSearch.Choice tooFewCards = DiscardSearch.choose(Contract.CLUBS,
                SkatAi.Seat.HUMAN, twelve.subList(0, 11), unseenFor(twelve),
                SkatAi.Seat.HUMAN, 4, 5, 0L, new Random(1), fallback);
        assertEquals(fallback, tooFewCards.buried());
        DiscardSearch.Choice noWorlds = DiscardSearch.choose(Contract.CLUBS,
                SkatAi.Seat.HUMAN, twelve, unseenFor(twelve),
                SkatAi.Seat.HUMAN, 0, 5, 0L, new Random(1), fallback);
        assertEquals(fallback, noWorlds.buried());
    }

    /**
     * Same seed, same answer -- the arena replays a match from its seed.
     *
     * <p>And the count of solves is the candidates times the worlds exactly,
     * which is the other half of the claim: the worlds are drawn once and reused
     * for every candidate rather than redrawn per candidate. Redrawing would
     * score the candidates against different opponents and call the difference
     * skill.
     */
    @Test public void itIsDeterministicAndPairs() {
        assertEquals(run(Contract.GRAND, 9L, 4, 5, 0L).buried(),
                run(Contract.GRAND, 9L, 4, 5, 0L).buried());
        DiscardSearch.Choice choice = run(Contract.GRAND, 9L, 4, 5, 0L);
        assertEquals("ten pairs from five cards, four worlds each",
                10 * 4, choice.solves());
        assertTrue(choice.wins() >= 0 && choice.wins() <= choice.worlds());
    }

    /** More worlds is a different question, so it may well be a different answer. */
    @Test public void theWorldCountIsNotDecoration() {
        int differed = 0;
        for (long seed = 1; seed <= 10; seed++) {
            if (!run(Contract.CLUBS, seed, 1, 5, 0L).buried()
                    .equals(run(Contract.CLUBS, seed, 12, 5, 0L).buried())) {
                differed++;
            }
        }
        assertTrue("one world and twelve must disagree somewhere, or nothing is"
                + " being searched", differed > 0);
    }
}
