package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The two things the bidding help has to get right: which values exist, and
 * what a holding is actually worth once the unseen skat is taken into account.
 */
public class BidValuesTest {

    private static Card card(Card.Suit suit, Card.Rank rank) {
        return new Card(suit, rank);
    }

    /** Jacks of hearts and diamonds: without 2 in clubs, and vulnerable to the skat. */
    private static List<Card> withoutTwoInClubs() {
        return new ArrayList<>(List.of(
                card(Card.Suit.HEARTS, Card.Rank.JACK),
                card(Card.Suit.DIAMONDS, Card.Rank.JACK),
                card(Card.Suit.CLUBS, Card.Rank.ACE),
                card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.KING),
                card(Card.Suit.SPADES, Card.Rank.ACE),
                card(Card.Suit.SPADES, Card.Rank.TEN),
                card(Card.Suit.HEARTS, Card.Rank.ACE),
                card(Card.Suit.HEARTS, Card.Rank.KING),
                card(Card.Suit.DIAMONDS, Card.Rank.ACE)));
    }

    /**
     * The ladder is generated from the scoring rules rather than typed out. This
     * pins it to the list the engine used to carry, so a change in either has to
     * be a deliberate one.
     */
    @Test
    public void generatedLadderMatchesTheKnownBidValues() {
        int[] known = {
                18, 20, 22, 23, 24, 27, 30, 33, 35, 36, 40, 44, 45, 46, 48, 50,
                54, 55, 59, 60, 63, 66, 70, 72, 77, 80, 81, 84, 88, 90, 96, 99,
                100, 108, 110, 117, 120, 121, 126, 130, 132, 135, 140, 143, 144,
                150, 153, 154, 156, 160, 162, 165, 168, 170, 176, 180, 187, 192,
                198, 204, 216, 240, 264
        };
        List<Integer> expected = new ArrayList<>();
        for (int value : known) expected.add(value);
        assertEquals(expected, BidValues.LADDER);
    }

    @Test
    public void ladderStepsIncludeTheNullValues() {
        assertEquals(18, BidValues.next(0));
        assertEquals(23, BidValues.next(22));
        assertEquals(35, BidValues.next(33));
        assertEquals(46, BidValues.next(45));
        assertEquals(59, BidValues.next(55));
        assertEquals(0, BidValues.next(264));
        assertEquals(23, BidValues.previous(24));
        assertEquals(27, BidValues.lowestReaching(25));
        assertEquals(24, BidValues.lowestReaching(24));
    }

    /**
     * The part most apps hide or get wrong: on a "without" hand the skat can
     * take multipliers away, so the guaranteed value is below the face value.
     */
    @Test
    public void withoutHandLosesValueToAnUnseenSkat() {
        BidValues.Range clubs = BidValues.evaluate(
                Declaration.of(Contract.CLUBS), withoutTwoInClubs());
        assertEquals(36, clubs.expected());
        assertEquals(3, clubs.multiplier());
        assertFalse(clubs.with());
        // With the jack of clubs in the skat the hand becomes with 1, game 2.
        assertEquals(24, clubs.guaranteed());
        assertTrue(clubs.best() >= clubs.expected());
        assertTrue(clubs.risky(30));
        assertTrue(clubs.covers(24));
        assertFalse(clubs.unreachable(36));
    }

    @Test
    public void withHandCanOnlyGain() {
        List<Card> hand = withoutTwoInClubs();
        hand.remove(card(Card.Suit.HEARTS, Card.Rank.JACK));
        hand.add(card(Card.Suit.CLUBS, Card.Rank.JACK));
        BidValues.Range clubs = BidValues.evaluate(Declaration.of(Contract.CLUBS), hand);
        assertTrue(clubs.with());
        assertEquals(clubs.expected(), clubs.guaranteed());
        assertTrue(clubs.best() > clubs.guaranteed());
    }

    /**
     * A hand game is not exempt. The skat belongs to the declarer for the
     * matador count whether or not it is ever picked up.
     */
    @Test
    public void handGamesStillHaveARange() {
        BidValues.Range hand = BidValues.evaluate(
                Declaration.hand(Contract.CLUBS), withoutTwoInClubs());
        assertEquals(48, hand.expected());
        assertFalse(hand.exact());
        assertTrue(hand.guaranteed() < hand.expected());
    }

    /** Twelve known cards leave nothing for the skat to change. */
    @Test
    public void twelveKnownCardsAreExact() {
        List<Card> twelve = withoutTwoInClubs();
        twelve.add(card(Card.Suit.CLUBS, Card.Rank.JACK));
        twelve.add(card(Card.Suit.SPADES, Card.Rank.JACK));
        BidValues.Range clubs = BidValues.evaluate(Declaration.of(Contract.CLUBS), twelve);
        assertTrue(clubs.exact());
        assertEquals(clubs.guaranteed(), clubs.best());
        // Four jacks plus the ace, ten and king of clubs: with 7, game 8.
        assertEquals(96, clubs.guaranteed());
    }

    @Test
    public void nullValuesAreFixed() {
        List<Card> hand = withoutTwoInClubs();
        assertEquals(23, BidValues.evaluate(Declaration.of(Contract.NULL), hand).guaranteed());
        assertEquals(35, BidValues.evaluate(Declaration.hand(Contract.NULL), hand).guaranteed());
        assertEquals(46, BidValues.evaluate(
                new Declaration(Contract.NULL, false, false, false, true), hand).guaranteed());
        assertEquals(59, BidValues.evaluate(
                new Declaration(Contract.NULL, true, false, false, true), hand).guaranteed());
        assertTrue(BidValues.evaluate(Declaration.of(Contract.NULL), hand).exact());
    }

    @Test
    public void reachableCeilingsStopAtTheBestCase() {
        BidValues.Range clubs = BidValues.evaluate(
                Declaration.of(Contract.CLUBS), withoutTwoInClubs());
        List<Integer> reachable = BidValues.reachable(clubs, 18);
        assertFalse(reachable.isEmpty());
        assertTrue(reachable.get(0) > 18);
        assertTrue(reachable.get(reachable.size() - 1) <= clubs.best());
    }

    /** Toggling an announcement repairs what it depends on, in both directions. */
    @Test
    public void announcementsRepairTheirOwnPrerequisites() {
        Declaration ouvert = Declaration.of(Contract.CLUBS).with(false, false, false, true);
        assertTrue(ouvert.hand());
        assertTrue(ouvert.schneiderAnnounced());
        assertTrue(ouvert.schwarzAnnounced());
        assertTrue(ouvert.legal());

        Declaration released = ouvert.with(true, true, false, false);
        assertFalse(released.schwarzAnnounced());
        assertFalse(released.ouvert());
        assertTrue(released.legal());

        Declaration nullOuvert = Declaration.of(Contract.NULL).with(false, true, true, true);
        assertFalse(nullOuvert.schneiderAnnounced());
        assertFalse(nullOuvert.schwarzAnnounced());
        assertTrue(nullOuvert.ouvert());
        assertTrue(nullOuvert.legal());
    }
}
