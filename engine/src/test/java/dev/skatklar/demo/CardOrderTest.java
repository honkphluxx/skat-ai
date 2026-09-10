package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

/**
 * How a hand is laid out on screen. None of this is a rule -- the engine and the
 * players never see the alternating layout -- but a hand a player cannot count
 * at a glance is a hand they misplay, so the arrangement is worth pinning down.
 */
public class CardOrderTest {

    /**
     * The suit blocks of a sorted deck, left to right. The Grand trumps head
     * the hand and belong to no block, so outside Null the jacks are left out.
     */
    private static List<Card.Suit> blocks(CardOrder order, Comparator<Card> layout) {
        List<Card> deck = new ArrayList<>();
        for (Card.Suit suit : Card.Suit.values()) {
            for (Card.Rank rank : Card.Rank.values()) {
                if (rank == Card.Rank.JACK && order != CardOrder.NULL) continue;
                deck.add(new Card(suit, rank));
            }
        }
        deck.sort(layout);
        List<Card.Suit> blocks = new ArrayList<>();
        for (Card card : deck) {
            if (blocks.isEmpty() || blocks.get(blocks.size() - 1) != card.suit) {
                blocks.add(card.suit);
            }
        }
        return blocks;
    }

    /** A whole hand of one suit plus the four jacks, sorted by the given layout. */
    private static List<Card> sorted(Comparator<Card> order, List<Card> cards) {
        List<Card> copy = new ArrayList<>(cards);
        copy.sort(order);
        return copy;
    }

    private static boolean isRed(Card.Suit suit) {
        return suit == Card.Suit.HEARTS || suit == Card.Suit.DIAMONDS;
    }

    private static void assertAlternates(List<Card.Suit> blocks) {
        for (int at = 1; at < blocks.size(); at++) {
            assertFalse("two " + (isRed(blocks.get(at)) ? "red" : "black")
                            + " blocks touch in " + blocks,
                    isRed(blocks.get(at)) == isRed(blocks.get(at - 1)));
        }
    }

    @Test
    public void naturalOrderIsUntouched() {
        assertEquals(List.of(Card.Suit.CLUBS, Card.Suit.SPADES,
                        Card.Suit.HEARTS, Card.Suit.DIAMONDS),
                blocks(CardOrder.GRAND, CardOrder.GRAND));
        assertEquals(List.of(Card.Suit.HEARTS, Card.Suit.CLUBS,
                        Card.Suit.SPADES, Card.Suit.DIAMONDS),
                blocks(CardOrder.HEARTS, CardOrder.HEARTS));
        assertEquals(List.of(Card.Suit.CLUBS, Card.Suit.SPADES,
                        Card.Suit.HEARTS, Card.Suit.DIAMONDS),
                blocks(CardOrder.NULL, CardOrder.NULL));
    }

    @Test
    public void grandAlternatesAndStaysAsCloseToNaturalAsItCan() {
        // Clubs, hearts, spades, diamonds: one pair of suits changes places,
        // and no alternating arrangement changes fewer.
        assertEquals(List.of(Card.Suit.CLUBS, Card.Suit.HEARTS,
                        Card.Suit.SPADES, Card.Suit.DIAMONDS),
                blocks(CardOrder.GRAND, CardOrder.GRAND.alternatingColours()));
        assertEquals(List.of(Card.Suit.CLUBS, Card.Suit.HEARTS,
                        Card.Suit.SPADES, Card.Suit.DIAMONDS),
                blocks(CardOrder.NULL, CardOrder.NULL.alternatingColours()));
    }

    @Test
    public void everyTrumpSuitLeadsAndEveryLayoutAlternates() {
        assertEquals(List.of(Card.Suit.CLUBS, Card.Suit.HEARTS,
                        Card.Suit.SPADES, Card.Suit.DIAMONDS),
                blocks(CardOrder.CLUBS, CardOrder.CLUBS.alternatingColours()));
        assertEquals(List.of(Card.Suit.SPADES, Card.Suit.HEARTS,
                        Card.Suit.CLUBS, Card.Suit.DIAMONDS),
                blocks(CardOrder.SPADES, CardOrder.SPADES.alternatingColours()));
        assertEquals(List.of(Card.Suit.HEARTS, Card.Suit.CLUBS,
                        Card.Suit.DIAMONDS, Card.Suit.SPADES),
                blocks(CardOrder.HEARTS, CardOrder.HEARTS.alternatingColours()));
        assertEquals(List.of(Card.Suit.DIAMONDS, Card.Suit.CLUBS,
                        Card.Suit.HEARTS, Card.Suit.SPADES),
                blocks(CardOrder.DIAMONDS, CardOrder.DIAMONDS.alternatingColours()));
        for (CardOrder order : CardOrder.values()) {
            assertAlternates(blocks(order, order.alternatingColours()));
        }
    }

    @Test
    public void theJacksKeepTheStrengthTheRulesGiveThem() {
        List<Card> jacks = new ArrayList<>();
        for (Card.Suit suit : Card.Suit.values()) jacks.add(new Card(suit, Card.Rank.JACK));
        List<Card> expected = List.of(
                new Card(Card.Suit.CLUBS, Card.Rank.JACK),
                new Card(Card.Suit.SPADES, Card.Rank.JACK),
                new Card(Card.Suit.HEARTS, Card.Rank.JACK),
                new Card(Card.Suit.DIAMONDS, Card.Rank.JACK));
        for (CardOrder order : CardOrder.values()) {
            if (order == CardOrder.NULL) continue;
            assertEquals(order + " moved the jacks",
                    expected, sorted(order.alternatingColours(), jacks));
        }
    }

    @Test
    public void theRanksInsideASuitDoNotMove() {
        List<Card> hearts = new ArrayList<>();
        for (Card.Rank rank : Card.Rank.values()) {
            if (rank != Card.Rank.JACK) hearts.add(new Card(Card.Suit.HEARTS, rank));
        }
        List<Card> expected = List.of(
                new Card(Card.Suit.HEARTS, Card.Rank.ACE),
                new Card(Card.Suit.HEARTS, Card.Rank.TEN),
                new Card(Card.Suit.HEARTS, Card.Rank.KING),
                new Card(Card.Suit.HEARTS, Card.Rank.QUEEN),
                new Card(Card.Suit.HEARTS, Card.Rank.NINE),
                new Card(Card.Suit.HEARTS, Card.Rank.EIGHT),
                new Card(Card.Suit.HEARTS, Card.Rank.SEVEN));
        for (CardOrder order : CardOrder.values()) {
            if (order == CardOrder.NULL) continue;
            assertEquals(order + " reordered a suit",
                    expected, sorted(order.alternatingColours(), hearts));
        }
        List<Card> nullRanks = new ArrayList<>();
        for (Card.Rank rank : Card.Rank.values()) nullRanks.add(new Card(Card.Suit.HEARTS, rank));
        assertEquals(List.of(
                        new Card(Card.Suit.HEARTS, Card.Rank.ACE),
                        new Card(Card.Suit.HEARTS, Card.Rank.KING),
                        new Card(Card.Suit.HEARTS, Card.Rank.QUEEN),
                        new Card(Card.Suit.HEARTS, Card.Rank.JACK),
                        new Card(Card.Suit.HEARTS, Card.Rank.TEN),
                        new Card(Card.Suit.HEARTS, Card.Rank.NINE),
                        new Card(Card.Suit.HEARTS, Card.Rank.EIGHT),
                        new Card(Card.Suit.HEARTS, Card.Rank.SEVEN)),
                sorted(CardOrder.NULL.alternatingColours(), nullRanks));
    }

    @Test
    public void theTrumpBlockIsStillOneBlockBehindTheJacks() {
        List<Card> hand = new ArrayList<>();
        hand.add(new Card(Card.Suit.DIAMONDS, Card.Rank.JACK));
        hand.add(new Card(Card.Suit.CLUBS, Card.Rank.ACE));
        hand.add(new Card(Card.Suit.SPADES, Card.Rank.SEVEN));
        hand.add(new Card(Card.Suit.HEARTS, Card.Rank.ACE));
        hand.add(new Card(Card.Suit.HEARTS, Card.Rank.SEVEN));
        List<Card> laid = sorted(CardOrder.HEARTS.alternatingColours(), hand);
        assertEquals(List.of(
                        new Card(Card.Suit.DIAMONDS, Card.Rank.JACK),
                        new Card(Card.Suit.HEARTS, Card.Rank.ACE),
                        new Card(Card.Suit.HEARTS, Card.Rank.SEVEN),
                        new Card(Card.Suit.CLUBS, Card.Rank.ACE),
                        new Card(Card.Suit.SPADES, Card.Rank.SEVEN)),
                laid);
    }

    @Test
    public void everyCardStillHasAPlaceOfItsOwn() {
        for (CardOrder order : CardOrder.values()) {
            List<Card> deck = new ArrayList<>();
            for (Card.Suit suit : Card.Suit.values()) {
                for (Card.Rank rank : Card.Rank.values()) deck.add(new Card(suit, rank));
            }
            deck.sort(order.alternatingColours());
            assertEquals(32, deck.size());
            for (int at = 1; at < deck.size(); at++) {
                assertTrue(order + " gave two cards the same place",
                        order.alternatingColours().compare(deck.get(at - 1), deck.get(at)) < 0);
            }
        }
    }
}
