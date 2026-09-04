package dev.skatklar.demo;

import java.util.Objects;
import java.util.Locale;

/** Immutable identity of one card in a 32-card French-suited Skat pack. */
public final class Card {
    public enum Suit {
        CLUBS, SPADES, HEARTS, DIAMONDS
    }

    public enum Rank {
        SEVEN("7"), EIGHT("8"), NINE("9"), TEN("10"), JACK("J"), QUEEN("Q"), KING("K"), ACE("A");

        public final String label;

        Rank(String label) {
            this.label = label;
        }
    }

    public final Suit suit;
    public final Rank rank;

    public Card(Suit suit, Rank rank) {
        this.suit = Objects.requireNonNull(suit);
        this.rank = Objects.requireNonNull(rank);
    }

    /** Column is rank, row is suit in the bundled 8 x 4 texture atlas. */
    public int atlasColumn() {
        return rank.ordinal();
    }

    public int atlasRow() {
        return suit.ordinal();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Card)) return false;
        Card card = (Card) other;
        return suit == card.suit && rank == card.rank;
    }

    @Override
    public int hashCode() {
        return 31 * suit.ordinal() + rank.ordinal();
    }

    @Override
    public String toString() {
        return rank.label + " of " + suit.name().toLowerCase(Locale.ROOT);
    }
}
