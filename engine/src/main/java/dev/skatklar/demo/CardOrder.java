package dev.skatklar.demo;

import java.util.Comparator;

/** Preferred visual hand orders. Kept separate because contracts group cards differently. */
public enum CardOrder implements Comparator<Card> {
    /**
     * Grand: the four permanent trumps first, strongest to weakest, followed by
     * clubs, spades, hearts, and diamonds in their non-trump trick order.
     */
    GRAND(null),
    CLUBS(Card.Suit.CLUBS),
    SPADES(Card.Suit.SPADES),
    HEARTS(Card.Suit.HEARTS),
    DIAMONDS(Card.Suit.DIAMONDS),
    /**
     * Null: no trump at all, so the jacks return to their own suits and the ten
     * drops back between the nine and the jack. The suits stay in the same
     * left-to-right order as every other game, which keeps the preview re-sort
     * readable: the jacks travel home and the tens sink, nothing else moves.
     */
    NULL(null);

    private final Card.Suit trumpSuit;

    CardOrder(Card.Suit trumpSuit) {
        this.trumpSuit = trumpSuit;
    }

    @Override
    public int compare(Card left, Card right) {
        return Integer.compare(position(left), position(right));
    }

    private int position(Card card) {
        if (this == NULL) {
            return suitPosition(card.suit) * 8 + nullRankPosition(card.rank);
        }
        if (card.rank == Card.Rank.JACK) {
            return suitPosition(card.suit);
        }
        int offset = 4;
        if (trumpSuit != null && card.suit == trumpSuit) {
            return offset + nonTrumpRankPosition(card.rank);
        }
        if (trumpSuit != null) offset += 7;
        int visibleSuit = suitPosition(card.suit);
        if (trumpSuit != null && visibleSuit > suitPosition(trumpSuit)) visibleSuit--;
        return offset + visibleSuit * 7 + nonTrumpRankPosition(card.rank);
    }

    private static int suitPosition(Card.Suit suit) {
        return switch (suit) {
            case CLUBS -> 0;
            case SPADES -> 1;
            case HEARTS -> 2;
            case DIAMONDS -> 3;
        };
    }

    private static int nonTrumpRankPosition(Card.Rank rank) {
        return switch (rank) {
            case ACE -> 0;
            case TEN -> 1;
            case KING -> 2;
            case QUEEN -> 3;
            case NINE -> 4;
            case EIGHT -> 5;
            case SEVEN -> 6;
            case JACK -> throw new IllegalArgumentException("Jacks are Grand trumps");
        };
    }

    /** Null ranks, strongest first: A K Q J 10 9 8 7. */
    private static int nullRankPosition(Card.Rank rank) {
        return switch (rank) {
            case ACE -> 0;
            case KING -> 1;
            case QUEEN -> 2;
            case JACK -> 3;
            case TEN -> 4;
            case NINE -> 5;
            case EIGHT -> 6;
            case SEVEN -> 7;
        };
    }
}
