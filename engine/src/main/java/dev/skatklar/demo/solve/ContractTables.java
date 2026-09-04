package dev.skatklar.demo.solve;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-contract lookup tables over the 32-card pack, indexed by {@link #index(Card)}.
 *
 * <p>The solver visits millions of positions, so it cannot afford to ask
 * {@code Contract.isTrump} or build {@code FollowClass} objects per card. These
 * tables are the same rules as {@link dev.skatklar.demo.SkatRules}, flattened into
 * arrays — the strength numbers are deliberately identical, so a solved line and
 * a played line agree on who wins a trick.
 */
public final class ContractTables {

    /** Trump, or one of the four suits. Used to decide what must be followed. */
    public static final int TRUMP = 0;

    private static final Map<Contract, ContractTables> CACHE = new EnumMap<>(Contract.class);

    private final boolean[] trump = new boolean[32];
    private final int[] followClass = new int[32];
    private final int[] strength = new int[32];
    private final int[] points = new int[32];

    private ContractTables(Contract contract) {
        for (Card.Suit suit : Card.Suit.values()) {
            for (Card.Rank rank : Card.Rank.values()) {
                Card card = new Card(suit, rank);
                int i = index(card);
                trump[i] = contract.isTrump(card);
                followClass[i] = trump[i] ? TRUMP : suit.ordinal() + 1;
                strength[i] = strengthOf(contract, card);
                points[i] = pointsOf(rank);
            }
        }
    }

    public static ContractTables of(Contract contract) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(contract, ContractTables::new);
        }
    }

    /** Stable 0..31 index: suit-major, rank-minor. */
    public static int index(Card card) {
        return card.suit.ordinal() * 8 + card.rank.ordinal();
    }

    public static Card card(int index) {
        return new Card(Card.Suit.values()[index / 8], Card.Rank.values()[index % 8]);
    }

    public boolean isTrump(int index) { return trump[index]; }
    public int followClass(int index) { return followClass[index]; }
    public int strength(int index) { return strength[index]; }
    public int points(int index) { return points[index]; }

    /** Bit mask of every card belonging to {@code followClass}. */
    public int classMask(int followClass) {
        int mask = 0;
        for (int i = 0; i < 32; i++) if (this.followClass[i] == followClass) mask |= 1 << i;
        return mask;
    }

    /**
     * Index of the winning card among a complete trick, given what was led.
     * Mirrors {@code SkatRules.beats}: a trump beats any non-trump, otherwise
     * only the led class can win, and within a class the higher strength wins.
     */
    public int trickWinner(int led, int second, int third) {
        int best = led;
        if (beats(led, best, second)) best = second;
        if (beats(led, best, third)) best = third;
        return best;
    }

    private boolean beats(int led, int incumbent, int challenger) {
        int ledClass = followClass[led];
        int incumbentClass = followClass[incumbent];
        int challengerClass = followClass[challenger];
        if (challengerClass != incumbentClass) {
            if (challengerClass == TRUMP) return true;
            if (incumbentClass == TRUMP) return false;
            return challengerClass == ledClass;
        }
        if (challengerClass != ledClass && challengerClass != TRUMP) return false;
        return strength[challenger] > strength[incumbent];
    }

    private static int strengthOf(Contract contract, Card card) {
        // Same order as SkatRules: a solved Null line and a played one must
        // agree on who takes the trick.
        if (contract.isNull()) return dev.skatklar.demo.SkatRules.nullStrength(card.rank);
        if (card.rank == Card.Rank.JACK) {
            return 100 + switch (card.suit) {
                case CLUBS -> 4;
                case SPADES -> 3;
                case HEARTS -> 2;
                case DIAMONDS -> 1;
            };
        }
        int rank = switch (card.rank) {
            case ACE -> 7;
            case TEN -> 6;
            case KING -> 5;
            case QUEEN -> 4;
            case NINE -> 3;
            case EIGHT -> 2;
            case SEVEN -> 1;
            case JACK -> throw new AssertionError();
        };
        return contract.isTrump(card) ? 50 + rank : rank;
    }

    private static int pointsOf(Card.Rank rank) {
        return switch (rank) {
            case ACE -> 11;
            case TEN -> 10;
            case KING -> 4;
            case QUEEN -> 3;
            case JACK -> 2;
            case NINE, EIGHT, SEVEN -> 0;
        };
    }
}
