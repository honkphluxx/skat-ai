package dev.skatklar.demo.ramsch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.search.SearchAiProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Pricing the alternative to declaring.
 *
 * <p>The property that matters is not the number but its <em>spread</em>: if a
 * Ramsch cost every hand the same, the constant it replaced would have been fine
 * and none of this would be worth its cost. So what is tested is that the price
 * moves with the hand, in the direction a Skat player would name — high cards are
 * what you get stuck with — and that it moves far enough to change a decision.
 */
public class RamschEvaluatorTest {

    private static final int WORLDS = 24;

    @Test public void rubbishIsCheapAndPictureCardsAreNot() {
        double rubbish = price(hand(
                Card.Rank.SEVEN, Card.Rank.EIGHT, Card.Rank.NINE));
        double heavy = price(hand(
                Card.Rank.ACE, Card.Rank.TEN, Card.Rank.KING));

        assertTrue("a hand that can duck out of every trick costs little: " + rubbish,
                rubbish > -20);
        assertTrue("a hand of aces and tens is what a Ramsch punishes: " + heavy,
                heavy < -40);
        assertTrue(heavy < rubbish - 20);
    }

    @Test public void theSameHandAtTheSameSeedPricesTheSame() {
        List<Card> hand = hand(Card.Rank.ACE, Card.Rank.NINE, Card.Rank.EIGHT);

        assertEquals(RamschEvaluator.expectedValue(SkatAi.Seat.HUMAN, hand,
                        SkatAi.Seat.HUMAN, WORLDS, new Random(4)),
                RamschEvaluator.expectedValue(SkatAi.Seat.HUMAN, hand,
                        SkatAi.Seat.HUMAN, WORLDS, new Random(4)), 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void itPricesTheDealtTenAndNothingElse() {
        RamschEvaluator.expectedValue(SkatAi.Seat.HUMAN,
                hand(Card.Rank.ACE, Card.Rank.KING, Card.Rank.QUEEN).subList(0, 8),
                SkatAi.Seat.HUMAN, 2, new Random(1));
    }

    /**
     * The staircase that turns the price into a cost. Passing is only expensive
     * when a Ramsch is actually what it leads to — the correction to the first
     * version of {@code docs/rules.md}, which assumed it always was.
     */
    @Test public void passingIsFreeOnceSomebodyWantsTheGame() {
        assertEquals("a defender scores nothing, so a pass costs nothing",
                0.0, SearchAiProvider.ramschRisk(18, 0), 1e-9);
        assertEquals("both out: it is mine whether I want it or not",
                1.0, SearchAiProvider.ramschRisk(0, 2), 1e-9);
        assertTrue("one still to pass beats two still to pass",
                SearchAiProvider.ramschRisk(0, 1) > SearchAiProvider.ramschRisk(0, 0));
    }

    private static double price(List<Card> hand) {
        return RamschEvaluator.expectedValue(SkatAi.Seat.HUMAN, hand,
                SkatAi.Seat.HUMAN, WORLDS, new Random(11));
    }

    /** Ten cards drawn from the named ranks, spread across the suits. */
    private static List<Card> hand(Card.Rank... ranks) {
        List<Card> cards = new ArrayList<>(10);
        for (Card.Rank rank : ranks) {
            for (Card.Suit suit : Card.Suit.values()) {
                if (cards.size() < 10) cards.add(new Card(suit, rank));
            }
        }
        for (Card.Rank rank : Card.Rank.values()) {
            for (Card.Suit suit : Card.Suit.values()) {
                Card card = new Card(suit, rank);
                if (cards.size() < 10 && !cards.contains(card)) cards.add(card);
            }
        }
        return cards;
    }
}
