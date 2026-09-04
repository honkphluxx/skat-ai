package dev.skatklar.training.arena;

import dev.skatklar.demo.ai.SkatAi;

/**
 * Seeger-Fabian tournament scoring for a three-player table.
 *
 * <p>Raw game value alone is a misleading measure of a Skat player, because it
 * only ever credits the declarer: a program that declares recklessly and one
 * that defends brilliantly are indistinguishable under it. Seeger-Fabian is the
 * scoring the literature reports as "tournament points per game" (TP/G), and it
 * is what this arena optimises against.
 *
 * <ul>
 *   <li>Declarer wins: game value + 50</li>
 *   <li>Declarer loses: the (already negative, already doubled) game value - 50</li>
 *   <li>Each defender, when the declarer loses: +40 (three-player table)</li>
 * </ul>
 *
 * <p>{@code SkatRules.gameValue} already returns the signed value, negative and
 * doubled on a loss, so the bonus is all this has to add.
 */
public final class Scoring {
    /** Defender bonus at a three-player table; a four-player table uses 30. */
    public static final int DEFENDER_BONUS = 40;
    public static final int DECLARER_BONUS = 50;

    private Scoring() {}

    /**
     * Seeger-Fabian tournament points for one seat in one game.
     *
     * <p>A Ramsch has no Seeger-Fabian value -- no declarer to bonus, no
     * defenders to reward -- so its card points are carried through as they
     * stand. That is a stitch between two scoring systems, and it is visible on
     * purpose: the canon settles classically ({@code docs/rules.md} 1), and when
     * this arena's headline metric follows it, the stitch goes away with it.
     */
    public static int tournamentPoints(GameOutcome outcome, SkatAi.Seat seat) {
        if (outcome.ramsch()) return outcome.isScored(seat) ? outcome.gameValue() : 0;
        if (outcome.isDeclarer(seat)) {
            return outcome.gameValue() + (outcome.declarerWon() ? DECLARER_BONUS : -DECLARER_BONUS);
        }
        return outcome.declarerWon() ? 0 : DEFENDER_BONUS;
    }

    /**
     * Plain signed game value for one seat, the way a paper list keeps it: the
     * declarer's column, or in a Ramsch the loser's. This is the number the canon
     * actually plays for.
     */
    public static int gamePoints(GameOutcome outcome, SkatAi.Seat seat) {
        if (outcome.ramsch()) return outcome.isScored(seat) ? outcome.gameValue() : 0;
        return outcome.isDeclarer(seat) ? outcome.gameValue() : 0;
    }
}
