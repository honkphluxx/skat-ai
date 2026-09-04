package dev.skatklar.demo;

/**
 * Playable contracts. Jacks are trump in every game except Null, which has no
 * trump at all and its own rank order.
 *
 * <p>Nothing here encodes which subset a variant offers: the stripped-down
 * auto-bidding mode allows the first five, the full game allows all of them, and
 * both state that as {@code SkatAi.ContractRules}.
 */
public enum Contract {
    DIAMONDS("Diamonds", Card.Suit.DIAMONDS, CardOrder.DIAMONDS),
    HEARTS("Hearts", Card.Suit.HEARTS, CardOrder.HEARTS),
    SPADES("Spades", Card.Suit.SPADES, CardOrder.SPADES),
    CLUBS("Clubs", Card.Suit.CLUBS, CardOrder.CLUBS),
    GRAND("Grand", null, CardOrder.GRAND),
    NULL("Null", null, CardOrder.NULL),
    /**
     * Schieberamsch: what is played when all three pass. Nobody declared it and
     * nobody plays alone, so this is the one contract with no declarer at all —
     * see {@code docs/rules.md}. The cards behave exactly as in Grand, the four
     * jacks and nothing else being trump, and everything built on two against
     * one — the solver, the search player, the bid values — does not apply.
     *
     * <p>Deliberately last in the enum: {@link ScoreSheet} persists contracts by
     * ordinal and a stored sheet outlives the code that wrote it.
     */
    RAMSCH("Ramsch", null, CardOrder.GRAND);

    /** The five trump games, in bidding-panel order: the auto-bidding subset. */
    public static final Contract[] TRUMP_GAMES = {
            CLUBS, SPADES, HEARTS, DIAMONDS, GRAND
    };

    /**
     * The games somebody can announce, in enum order — everything except Ramsch.
     *
     * <p>Use this wherever the old code said {@code Contract.values()} and meant
     * "the games you can play". A Ramsch is not on that list: it is not chosen,
     * it is what is left when nobody chooses. The order matches the ordinals, so
     * anything indexing by position keeps working.
     */
    public static final Contract[] DECLARED_GAMES = {
            DIAMONDS, HEARTS, SPADES, CLUBS, GRAND, NULL
    };

    public final String label;
    public final Card.Suit trumpSuit;
    public final CardOrder preferredCardOrder;

    Contract(String label, Card.Suit trumpSuit, CardOrder preferredCardOrder) {
        this.label = label;
        this.trumpSuit = trumpSuit;
        this.preferredCardOrder = preferredCardOrder;
    }

    public boolean isTrump(Card card) {
        if (this == NULL) return false;
        return card.rank == Card.Rank.JACK || card.suit == trumpSuit;
    }

    /** Null has no trump, no matadors and a fixed value; almost everything differs. */
    public boolean isNull() {
        return this == NULL;
    }

    /**
     * A Ramsch: three players, no declarer, no bid and no game value. Ask this
     * before anything that reads {@code GameDefinition.declarer}, which is null
     * here, or that computes a value from matadors, which do not exist here.
     */
    public boolean isRamsch() {
        return this == RAMSCH;
    }

    /** True for the games one player declares and plays alone. */
    public boolean hasDeclarer() {
        return this != RAMSCH;
    }

    @Override
    public String toString() {
        return label;
    }
}
