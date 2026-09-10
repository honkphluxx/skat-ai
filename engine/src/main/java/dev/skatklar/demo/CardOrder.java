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

    /**
     * The suits in the order the game names them -- clubs, spades, hearts,
     * diamonds -- as a column per {@link Card.Suit} ordinal. This is the order
     * every hand has always been laid out in, and the one
     * {@link #alternatingColours()} measures its distance from.
     *
     * <p>Built from the declaration order of {@link Card.Suit}, which is the
     * same order {@link #suitPosition} spells out, so the two cannot drift.
     * Static fields are initialised after the constants, so nothing in the
     * constructor may read this -- and nothing does: {@link #alternatingColumns}
     * asks {@link #suitPosition} instead.
     */
    private static final int[] NATURAL_COLUMNS = columns(Card.Suit.values());

    private final Card.Suit trumpSuit;
    private final int[] alternatingColumns;

    CardOrder(Card.Suit trumpSuit) {
        this.trumpSuit = trumpSuit;
        this.alternatingColumns = alternatingColumns(trumpSuit);
    }

    @Override
    public int compare(Card left, Card right) {
        return Integer.compare(position(left, NATURAL_COLUMNS), position(right, NATURAL_COLUMNS));
    }

    /**
     * This same order with the suit blocks rearranged so that a red block never
     * touches a red one -- clubs, hearts, spades, diamonds for a Grand, and the
     * trump suit first where there is one.
     *
     * <p>A hand sorted the natural way puts the two black suits side by side and
     * then the two red ones, so the seam a player scans for is exactly where
     * there is no colour change. Alternating gives every block a border its own
     * colour does not cross, which is what makes a suit countable at a glance.
     *
     * <p>What it does not touch is anything the rules order: the jacks stay in
     * strength order at the head of the hand, and the ranks inside a suit stay
     * where the contract puts them. Only the blocks move, and only as far as
     * they must -- see {@link #alternatingColumns}.
     *
     * <p>This is a way of laying the hand out, not a different hand: it is used
     * where the app sorts what the player is looking at, never by the engine or
     * by a player deciding what to play.
     */
    public Comparator<Card> alternatingColours() {
        return (left, right) -> Integer.compare(
                position(left, alternatingColumns), position(right, alternatingColumns));
    }

    /**
     * The best alternating arrangement of the four suits for this order: the one
     * that is closest to the natural one among those that alternate in colour
     * and, where the game has a trump suit, lead with it.
     *
     * <p>Searched rather than written down, so that the rule is the thing stated
     * and the five arrangements are its consequence. "Closest" is the number of
     * pairs of suits that have to trade places -- move as few suits past each
     * other as the constraints allow. There are twenty-four arrangements, eight
     * of them alternate, and each set the search actually chooses from has a
     * single winner, so no tie-break is needed:
     *
     * <pre>
     *   Grand, Null   clubs   hearts  spades   diamonds
     *   Clubs         clubs   hearts  spades   diamonds
     *   Spades        spades  hearts  clubs    diamonds
     *   Hearts        hearts  clubs   diamonds spades
     *   Diamonds      diamonds clubs  hearts   spades
     * </pre>
     */
    private static int[] alternatingColumns(Card.Suit trumpSuit) {
        Card.Suit[] suits = Card.Suit.values();
        Card.Suit[] best = null;
        int fewestSwaps = Integer.MAX_VALUE;
        for (Card.Suit first : suits) {
            if (trumpSuit != null && first != trumpSuit) continue;
            for (Card.Suit second : suits) {
                if (second == first) continue;
                for (Card.Suit third : suits) {
                    if (third == first || third == second) continue;
                    for (Card.Suit fourth : suits) {
                        if (fourth == first || fourth == second || fourth == third) continue;
                        Card.Suit[] candidate = {first, second, third, fourth};
                        if (!coloursAlternate(candidate)) continue;
                        int swaps = swapsFromNatural(candidate);
                        if (swaps < fewestSwaps) {
                            fewestSwaps = swaps;
                            best = candidate;
                        }
                    }
                }
            }
        }
        // Unreachable with two suits of each colour -- a red suit can always be
        // slotted between the two black ones and the other way round -- but a
        // layout is not worth an exception, so fall back to the natural one.
        return columns(best == null ? suits : best);
    }

    /** True when no two neighbouring suits in this arrangement share a colour. */
    private static boolean coloursAlternate(Card.Suit[] arrangement) {
        for (int at = 1; at < arrangement.length; at++) {
            if (isRed(arrangement[at]) == isRed(arrangement[at - 1])) return false;
        }
        return true;
    }

    /** How many pairs of suits this arrangement moves past each other. */
    private static int swapsFromNatural(Card.Suit[] arrangement) {
        int swaps = 0;
        for (int left = 0; left < arrangement.length; left++) {
            for (int right = left + 1; right < arrangement.length; right++) {
                if (suitPosition(arrangement[left]) > suitPosition(arrangement[right])) swaps++;
            }
        }
        return swaps;
    }

    private static boolean isRed(Card.Suit suit) {
        return suit == Card.Suit.HEARTS || suit == Card.Suit.DIAMONDS;
    }

    /** An arrangement as a lookup: which column each suit is laid out in. */
    private static int[] columns(Card.Suit[] arrangement) {
        int[] columns = new int[Card.Suit.values().length];
        for (int at = 0; at < arrangement.length; at++) columns[arrangement[at].ordinal()] = at;
        return columns;
    }

    /**
     * Where a card sits in a hand laid out with the suit blocks in {@code
     * columns}. Only the blocks read that table: the jacks are ordered by the
     * strength the rules give them, which is never a layout choice.
     */
    private int position(Card card, int[] columns) {
        if (this == NULL) {
            return columns[card.suit.ordinal()] * 8 + nullRankPosition(card.rank);
        }
        if (card.rank == Card.Rank.JACK) {
            return suitPosition(card.suit);
        }
        int offset = 4;
        if (trumpSuit != null && card.suit == trumpSuit) {
            return offset + nonTrumpRankPosition(card.rank);
        }
        if (trumpSuit != null) offset += 7;
        int visibleSuit = columns[card.suit.ordinal()];
        if (trumpSuit != null && visibleSuit > columns[trumpSuit.ordinal()]) visibleSuit--;
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
