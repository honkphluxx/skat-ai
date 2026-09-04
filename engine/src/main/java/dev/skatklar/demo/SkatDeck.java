package dev.skatklar.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class SkatDeck {
    public static final int CARD_COUNT = 32;

    private SkatDeck() {}

    public static List<Card> ordered() {
        ArrayList<Card> cards = new ArrayList<>(CARD_COUNT);
        for (Card.Suit suit : Card.Suit.values()) {
            for (Card.Rank rank : Card.Rank.values()) {
                cards.add(new Card(suit, rank));
            }
        }
        return Collections.unmodifiableList(cards);
    }

    /**
     * A deal from a known order: ten cards each, then the two in the skat.
     *
     * <p>Exists so a specific arrangement can be replayed rather than searched
     * for — a test that needs two deals differing only in the hidden half, and,
     * later, an archived game being replayed card for card.
     */
    public static Deal dealFrom(List<Card> ordered) {
        if (ordered.size() != CARD_COUNT) {
            throw new IllegalArgumentException("A deal is " + CARD_COUNT + " cards, got "
                    + ordered.size());
        }
        if (new java.util.LinkedHashSet<>(ordered).size() != CARD_COUNT) {
            throw new IllegalArgumentException("A deal holds each card once");
        }
        return new Deal(ordered.subList(0, 10), ordered.subList(10, 20),
                ordered.subList(20, 30), ordered.subList(30, 32));
    }

    /**
     * A shuffled deal.
     *
     * <p>The quality of the shuffle is entirely the caller's: this is a
     * Fisher-Yates over whatever generator is handed in. Pass an {@link Rng} —
     * a 48-bit generator such as a bare {@link java.util.Random} cannot reach
     * more than a vanishing fraction of the {@code 32!} deals that exist, and
     * {@link Collections#shuffle} draws through exactly the bits an LCG is
     * weakest in. The server passes a {@link java.security.SecureRandom}, which
     * is stronger still.
     */
    public static Deal deal(Random random) {
        ArrayList<Card> shuffled = new ArrayList<>(ordered());
        Collections.shuffle(shuffled, random);
        return new Deal(
                shuffled.subList(0, 10),
                shuffled.subList(10, 20),
                shuffled.subList(20, 30),
                shuffled.subList(30, 32));
    }

    public static final class Deal {
        public final List<Card> human;
        public final List<Card> opponentOne;
        public final List<Card> opponentTwo;
        public final List<Card> skat;

        private Deal(List<Card> human, List<Card> opponentOne, List<Card> opponentTwo, List<Card> skat) {
            this.human = Collections.unmodifiableList(new ArrayList<>(human));
            this.opponentOne = Collections.unmodifiableList(new ArrayList<>(opponentOne));
            this.opponentTwo = Collections.unmodifiableList(new ArrayList<>(opponentTwo));
            this.skat = Collections.unmodifiableList(new ArrayList<>(skat));
        }
    }
}
