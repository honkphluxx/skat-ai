package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * The one hand that tells the two matador rules apart: four jacks, then the
 * ace and ten of trumps. Under the ISkO the run continues into the suit and
 * that is "with six"; under the canon it stops at the jacks and it is "with
 * four".
 */
public final class MatadorRuleTest {

    private static final List<Card> FOUR_JACKS_ACE_AND_TEN = List.of(
            new Card(Card.Suit.CLUBS, Card.Rank.JACK),
            new Card(Card.Suit.SPADES, Card.Rank.JACK),
            new Card(Card.Suit.HEARTS, Card.Rank.JACK),
            new Card(Card.Suit.DIAMONDS, Card.Rank.JACK),
            new Card(Card.Suit.CLUBS, Card.Rank.ACE),
            new Card(Card.Suit.CLUBS, Card.Rank.TEN),
            new Card(Card.Suit.HEARTS, Card.Rank.SEVEN),
            new Card(Card.Suit.HEARTS, Card.Rank.EIGHT),
            new Card(Card.Suit.SPADES, Card.Rank.SEVEN),
            new Card(Card.Suit.DIAMONDS, Card.Rank.SEVEN));

    /** The same hand without the jack of clubs: "without one" under both. */
    private static final List<Card> WITHOUT_ONE = List.of(
            new Card(Card.Suit.SPADES, Card.Rank.JACK),
            new Card(Card.Suit.HEARTS, Card.Rank.JACK),
            new Card(Card.Suit.DIAMONDS, Card.Rank.JACK),
            new Card(Card.Suit.CLUBS, Card.Rank.ACE),
            new Card(Card.Suit.CLUBS, Card.Rank.TEN),
            new Card(Card.Suit.CLUBS, Card.Rank.KING),
            new Card(Card.Suit.HEARTS, Card.Rank.SEVEN),
            new Card(Card.Suit.HEARTS, Card.Rank.EIGHT),
            new Card(Card.Suit.SPADES, Card.Rank.SEVEN),
            new Card(Card.Suit.DIAMONDS, Card.Rank.SEVEN));

    @After public void restoreTheCanon() {
        SkatRules.setMatadorRule(SkatRules.MatadorRule.JACKS_ONLY);
    }

    @Test public void theCanonIsTheDefault() {
        assertEquals(SkatRules.MatadorRule.JACKS_ONLY, SkatRules.matadorRule());
    }

    @Test public void theRunStopsAtTheJacksUnderTheCanon() {
        SkatRules.setMatadorRule(SkatRules.MatadorRule.JACKS_ONLY);
        assertEquals("four jacks, ace and ten is 'with four', not six",
                4, SkatRules.matadorCount(Contract.CLUBS, FOUR_JACKS_ACE_AND_TEN));
        assertEquals("so a Clubs game is base times five",
                12 * 5, SkatRules.guaranteedValue(Contract.CLUBS, FOUR_JACKS_ACE_AND_TEN));
    }

    @Test public void theRunContinuesIntoTheSuitUnderTheIsko() {
        SkatRules.setMatadorRule(SkatRules.MatadorRule.OFFICIAL);
        assertEquals("the ace and ten extend the run to six",
                6, SkatRules.matadorCount(Contract.CLUBS, FOUR_JACKS_ACE_AND_TEN));
        assertEquals(12 * 7, SkatRules.guaranteedValue(Contract.CLUBS, FOUR_JACKS_ACE_AND_TEN));
    }

    @Test public void grandIsTheSameUnderBoth() {
        // Only jacks are trumps in a Grand, so there is no suit for the run to
        // continue into and the two rules cannot differ.
        SkatRules.setMatadorRule(SkatRules.MatadorRule.OFFICIAL);
        int official = SkatRules.matadorCount(Contract.GRAND, FOUR_JACKS_ACE_AND_TEN);
        SkatRules.setMatadorRule(SkatRules.MatadorRule.JACKS_ONLY);
        assertEquals(official, SkatRules.matadorCount(Contract.GRAND, FOUR_JACKS_ACE_AND_TEN));
        assertEquals(4, official);
    }

    @Test public void withoutIsUnchangedWhereTheRunEndsInsideTheJacks() {
        // A hand missing the jack of clubs is "without one" and the run ends
        // at the first jack it does hold, well before the suit is reached.
        SkatRules.setMatadorRule(SkatRules.MatadorRule.OFFICIAL);
        int official = SkatRules.matadorCount(Contract.CLUBS, WITHOUT_ONE);
        SkatRules.setMatadorRule(SkatRules.MatadorRule.JACKS_ONLY);
        assertEquals(official, SkatRules.matadorCount(Contract.CLUBS, WITHOUT_ONE));
        assertEquals(1, official);
    }
}
