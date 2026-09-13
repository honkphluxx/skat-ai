package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;

import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
        SkatRules.setHandValueRule(SkatRules.HandValueRule.AS_DECLARED);
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

    // ---------------------------------------------------------- hand games

    /** "Without two" as dealt: no club or spade jack, the heart jack held. */
    private static final List<Card> WITHOUT_TWO_HAND = List.of(
            new Card(Card.Suit.HEARTS, Card.Rank.JACK),
            new Card(Card.Suit.DIAMONDS, Card.Rank.JACK),
            new Card(Card.Suit.CLUBS, Card.Rank.ACE),
            new Card(Card.Suit.CLUBS, Card.Rank.TEN),
            new Card(Card.Suit.CLUBS, Card.Rank.KING),
            new Card(Card.Suit.CLUBS, Card.Rank.QUEEN),
            new Card(Card.Suit.SPADES, Card.Rank.ACE),
            new Card(Card.Suit.HEARTS, Card.Rank.ACE),
            new Card(Card.Suit.HEARTS, Card.Rank.SEVEN),
            new Card(Card.Suit.DIAMONDS, Card.Rank.SEVEN));

    /** The trap: the jack of clubs was lying in the skat all along. */
    private static final List<Card> SKAT_WITH_THE_CLUB_JACK = List.of(
            new Card(Card.Suit.CLUBS, Card.Rank.JACK),
            new Card(Card.Suit.SPADES, Card.Rank.SEVEN));

    private static SkatAi.GameDefinition clubsHandGame(int bid) {
        return new SkatAi.GameDefinition(SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN, Contract.CLUBS,
                new SkatAi.RoundPosition(0, SkatAi.Seat.HUMAN), bid, true, false, false, false);
    }

    private static Map<SkatAi.Seat, Integer> tricks(int declarer) {
        Map<SkatAi.Seat, Integer> map = new EnumMap<>(SkatAi.Seat.class);
        map.put(SkatAi.Seat.HUMAN, declarer);
        map.put(SkatAi.Seat.OPPONENT_ONE, (10 - declarer) / 2);
        map.put(SkatAi.Seat.OPPONENT_TWO, 10 - declarer - (10 - declarer) / 2);
        return map;
    }

    @Test public void theSkatJoinsAHandGameUnderTheIsko() {
        SkatRules.setHandValueRule(SkatRules.HandValueRule.AS_PLAYED);
        List<Card> counted = SkatRules.matadorCards(clubsHandGame(18),
                WITHOUT_TWO_HAND, SKAT_WITH_THE_CLUB_JACK);
        assertEquals(12, counted.size());
        // With the club jack now counted, the run is broken at the spade jack:
        // "without one", hand -- game three, thirty-six. The bid was 48,
        // "without two, hand, game four": the declarer is overbid after the
        // fact, on a card they never saw.
        assertEquals(1, SkatRules.matadorCount(Contract.CLUBS, counted));
        SkatRules.GameScore score = SkatRules.score(clubsHandGame(48), counted, 75, tricks(7));
        assertEquals(true, score.overbid());
    }

    @Test public void aHandGameIsValuedAsDeclaredUnderTheCanon() {
        SkatRules.setHandValueRule(SkatRules.HandValueRule.AS_DECLARED);
        List<Card> counted = SkatRules.matadorCards(clubsHandGame(18),
                WITHOUT_TWO_HAND, SKAT_WITH_THE_CLUB_JACK);
        assertEquals("the skat stays out of a hand game's count", 10, counted.size());
        // "Without two, hand": game four, forty-eight. A bid of 48 is exactly
        // covered and there is no retrospective overbid.
        assertEquals(2, SkatRules.matadorCount(Contract.CLUBS, counted));
        SkatRules.GameScore score = SkatRules.score(clubsHandGame(48), counted, 75, tricks(7));
        assertEquals(false, score.overbid());
        assertEquals(true, score.declarerWon());
        assertEquals(48, score.gameValue());
    }

    @Test public void aSkatGameCountsAllTwelveUnderBoth() {
        // Not a hand game: the declarer saw the skat and discarded into it, so
        // all twelve were known at declaration and both rules count them.
        SkatAi.GameDefinition skatGame = new SkatAi.GameDefinition(SkatAi.Seat.HUMAN,
                SkatAi.Seat.HUMAN, Contract.CLUBS, new SkatAi.RoundPosition(0, SkatAi.Seat.HUMAN),
                18, false, false, false, false);
        for (SkatRules.HandValueRule rule : SkatRules.HandValueRule.values()) {
            SkatRules.setHandValueRule(rule);
            assertEquals(rule.toString(), 12, SkatRules.matadorCards(
                    skatGame, WITHOUT_TWO_HAND, SKAT_WITH_THE_CLUB_JACK).size());
        }
    }

    @Test public void schneiderStillCountsUnderTheCanon() {
        // "As declared" freezes the matadors, not the outcome: Schneider and
        // Schwarz are earned at the table and still lift the multiplier.
        SkatRules.setHandValueRule(SkatRules.HandValueRule.AS_DECLARED);
        List<Card> counted = SkatRules.matadorCards(clubsHandGame(18),
                WITHOUT_TWO_HAND, SKAT_WITH_THE_CLUB_JACK);
        // Without two, hand, Schneider: game five, sixty.
        SkatRules.GameScore score = SkatRules.score(clubsHandGame(18), counted, 95, tricks(9));
        assertEquals(60, score.gameValue());
    }
}
