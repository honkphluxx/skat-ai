package dev.skatklar.demo.ai;

import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSource;

/**
 * The four opponents a person can actually choose between.
 *
 * <p>The levels themselves have existed in {@link Personality} for months and
 * were measured in the arena for almost as long. What did not exist was any way
 * for a player to meet one: the app seated the vendored JSkat AI, so none of it
 * had ever reached a phone. This is the seam that changes that, and it is
 * deliberately the only one — the app should not know what a personality is, and
 * the arena should not know what a settings screen is.
 *
 * <p>The measured distance between the levels, in game points a game at fixed
 * contracts, three seeds of 200 boards each on 2026-08-22: 5.9 from beginner to
 * club, 7.5 from club to expert, 5.2 from expert to analyst. For scale, the
 * whole distance from expert to a player that sees all three hands is 15.
 */
public final class Opponents {

    private Opponents() {}

    /**
     * How the levels are meant to differ, which is the part worth stating.
     *
     * <p>Not by blunder rates. A player that throws away a good card at random is
     * not a weaker opponent, it is a broken one — its mistakes have no pattern to
     * exploit, so beating it teaches a person nothing. Each level instead weakens
     * what the player <em>knows</em> and how hard it thinks, so its mistakes are
     * the ones a human recognises: it miscounts trumps, leads into a suit somebody
     * showed out of, forgets the last ace. See {@link Personality}.
     */
    public enum Level {
        /** Forgets most of what it has seen and reasons about two possible deals. */
        BEGINNER,
        /** Remembers about three cards in five. */
        CLUB,
        /** Nearly complete recall, sixteen deals a decision. */
        EXPERT,
        /**
         * Perfect recall, thirty-two deals a decision, slightly cautious.
         *
         * <p>Measured on 2026-08-24 as level with JSkat's transformer — the
         * strongest opponent we have to measure against — at forced contracts:
         * +0.19 game points a game, 95% CI [−0.95, +1.33]. The arena knows this
         * player as {@code belief-32}, which at a fixed contract is this level
         * exactly: aggression moves only the auction and a negative risk aims at
         * the same target as a neutral one, so the two differ in nothing that
         * touches a card.
         */
        ANALYST;

        /**
         * Whether this level gets the learned belief, when the app has it.
         *
         * <p>A fifth dial, and the cheapest one there is: the belief model is
         * worth 1.5 to 2.1 game points a game, measured, and withholding it
         * costs nothing to implement. It is also exactly the right *shape* of
         * weakness — a player without it guesses worse about where the hidden
         * cards lie, which is a beginner's actual problem, rather than throwing
         * away a good card at random, which is nobody's.
         *
         * <p>It widens the bottom step of the ladder and lowers the entry level
         * at the same time, which is what that step needed after
         * {@code prefersTheHighCard()} was removed and made the beginner about
         * four points stronger.
         */
        public boolean usesBelief() { return this != BEGINNER; }

        /**
         * Whether this level gets the two mechanisms the arena cleared on
         * 2026-09-15: the auction re-pricing the hand, and card-play ties
         * broken by a cushion. Every level does, since the ladder re-run of
         * the same day. They were first measured on the top of the ladder
         * only ({@code belief-32}, which is {@link #ANALYST}: +1.07 [+0.32,
         * +1.83] in exact pairing, level with XSkat), and shipped at
         * {@link #EXPERT} and above while the two lower levels waited for a
         * measurement, on the worry that with two or six worlds the top vote
         * is tied on most decisions and a cushion firing on most of them might
         * hurt a player that guesses this much. It does the opposite:
         * {@link #BEGINNER} with the switches beats itself without them by
         * +1.73 [+0.11, +3.34], {@link #CLUB} by +1.23 [−0.36, +2.82], both
         * positive on every seed, and the mechanism is visible in the
         * declaring rates -- club declares 27% of games instead of 33% and wins
         * 79% of them instead of 71%, the jack floor saving the weak player
         * from declarations it loses. The ladder stays four levels a level
         * apart with the switches on everywhere. The cost worry was real but
         * small: the cushion is asked for only on the decisions where the
         * vote left a tie, and at six worlds those decisions are cheap.
         */
        public boolean playsAdaptively() { return true; }

        public Personality personality() {
            switch (this) {
                case BEGINNER: return Personality.beginner();
                case CLUB: return Personality.clubPlayer();
                case EXPERT: return Personality.expert();
                default: return Personality.analyst();
            }
        }
    }

    /** The level a person who has not chosen meets first. */
    public static final Level DEFAULT = Level.CLUB;

    /**
     * A provider for one level.
     *
     * @param worlds where the player's hypotheses about the hidden cards come
     *               from. {@link WorldSource#UNIFORM} is the honest baseline;
     *               a belief-backed source is worth about two game points a game
     *               and is what the app supplies when the trained weights are
     *               installed
     * @param seed   fixes the sampling, so a game can be replayed
     */
    public static SkatAiProvider seat(Level level, WorldSource worlds, long seed) {
        return seat(level, worlds, seed, UNBOUNDED);
    }

    /** No ceiling on a bidding evaluation: the answer must not depend on the clock. */
    public static final long UNBOUNDED = 0L;

    /** Card points of cushion the shipped tiebreak asks for; the arena's belief-32-margin. */
    public static final int SHIPPED_CUSHION = 15;

    /**
     * The same seat, with a ceiling on what one hand's evaluation may cost.
     *
     * <p>Only for a caller with a person waiting on the result — the app. The
     * arena and the server pass {@link #UNBOUNDED}, because a match there has to
     * replay from its seed and a deadline would make the play depend on the
     * machine.
     *
     * @param biddingBudgetNanos wall-clock one seat may spend measuring its hand
     *                           before it bids from the fallback player's opinion
     *                           instead of a measured one
     */
    public static SkatAiProvider seat(Level level, WorldSource worlds, long seed,
                                      long biddingBudgetNanos) {
        return seat(level, worlds, seed, biddingBudgetNanos, level.playsAdaptively());
    }

    /**
     * The same seat, with the two shipped switches stated rather than taken
     * from the level. For the arena, which measures a level with them on and
     * off on the same boards; the app never calls this.
     *
     * @param adaptively the auction re-prices the hand and card-play ties are
     *                   broken by the cushion and then by position, whatever
     *                   {@link Level#playsAdaptively} says
     */
    public static SkatAiProvider seat(Level level, WorldSource worlds, long seed,
                                      long biddingBudgetNanos, boolean adaptively) {
        WorldSource believed = level.usesBelief() && worlds != null ? worlds : WorldSource.UNIFORM;
        // One search player per seat, not one for the table. The engine seats a
        // single provider at every AI seat, and a search player keeps evidence on
        // the provider -- shared, the two AI seats would overwrite each other's
        // private knowledge of the Schieben. See PerSeatAiProvider.
        // The switches the arena cleared for shipping, where the ladder was
        // measured with them: the auction re-prices the hand (+0.14 in exact
        // pairing, about half a point better against both outside engines)
        // and card-play ties are broken by a fifteen-point cushion (+1.10);
        // together +1.07 [+0.32, +1.83] against the same player without them,
        // level with XSkat for the first time, and non-negative against every
        // outsider. The ties the cushion still leaves are broken by the
        // position in the trick (2026-09-16: +0.23 [-0.19, +0.66] in exact
        // pairing on top of the two, +0.80 [+0.26, +1.34] paired by board
        // against XSkat, non-negative against JSkat and go-skat). See
        // arena/README.md, 2026-09-14 to 2026-09-16, and Level.playsAdaptively.
        return new PerSeatAiProvider(seat -> {
            SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(),
                    level.personality(), seed * 31L + seat.ordinal(), believed);
            if (adaptively) {
                player = player.withAdaptiveBidding().withMarginTiebreak(SHIPPED_CUSHION)
                        .withRuleTiebreak();
            }
            return player.withBiddingBudget(biddingBudgetNanos);
        });
    }
}
