package dev.skatklar.demo.belief;

import dev.skatklar.demo.ai.SkatAi;
import java.util.Arrays;

/**
 * The play so far, as the deciding seat saw it: the teacher record's format-3
 * block (docs/training-plan.md 2.10, format 3).
 *
 * <p>{@link BeliefEncoding} says which cards each seat has played but not who
 * won which trick, so a student reading it cannot see the card points already
 * banked -- and the teacher's whole question is whether the declarer still
 * reaches 61. SkatZero, the strongest card player measured here, reads the
 * points and the play history in order. This block carries both, raw: points
 * and tricks won per seat, and every completed trick -- who led, the cards in
 * the order they fell, who won it. How a network reads it is the trainer's
 * choice, made on the same bytes; the exporter and the player that will one
 * day feed a format-3 student both write them here, so they cannot drift.
 *
 * <p>Seats are relative to the deciding seat, as everywhere in the encoding:
 * 0 me, 1 my left (the seat after me), 2 my right. Everything here is face-up
 * information; nothing reads a hidden card. It is the whole history, not the
 * part a forgetful personality remembers -- the teacher remembers everything,
 * and a player that forgets will have to mask it the way the trainer's
 * forgetting masks the encoding.
 *
 * <pre>
 *   0..2    card points won so far by me, left, right (0..120)
 *   3..5    tricks won so far by me, left, right (0..10)
 *   6..50   nine completed tricks, five bytes each, in order:
 *           leader, first card, second card, third card, winner
 *           (seats relative, cards by {@link BeliefEncoding#index}); 255 for a
 *           trick not yet played
 * </pre>
 */
public final class PlayHistory {

    private PlayHistory() {}

    public static final int POINTS = 0;
    public static final int TRICKS_WON = 3;
    public static final int TRICKS = 6;
    public static final int PER_TRICK = 5;
    /** Only nine can be complete when a card is still to be chosen. */
    public static final int MAX_TRICKS = 9;
    public static final int SIZE = TRICKS + PER_TRICK * MAX_TRICKS;
    /** A trick not yet played. */
    public static final int NONE = 255;

    /** The block for one decision. */
    public static byte[] encode(SkatAi.DecisionContext context) {
        byte[] out = new byte[SIZE];
        Arrays.fill(out, TRICKS, SIZE, (byte) NONE);
        SkatAi.Seat me = context.mySeat;
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            int relative = BeliefEncoding.relative(me, seat);
            out[POINTS + relative] = (byte) clamp(context.derived.cardPoints.getOrDefault(seat, 0), 120);
            out[TRICKS_WON + relative] = (byte) clamp(context.derived.tricksWon.getOrDefault(seat, 0), 10);
        }
        int trick = 0;
        for (SkatAi.CompletedTrick done : context.history.completedTricks) {
            if (trick >= MAX_TRICKS) break;
            int at = TRICKS + PER_TRICK * trick;
            out[at] = (byte) BeliefEncoding.relative(me, done.leader);
            for (int i = 0; i < 3 && i < done.plays.size(); i++) {
                out[at + 1 + i] = (byte) BeliefEncoding.index(done.plays.get(i).card);
            }
            out[at + 4] = (byte) BeliefEncoding.relative(me, done.winner);
            trick++;
        }
        return out;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
