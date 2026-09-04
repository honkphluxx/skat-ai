package dev.skatklar.demo.belief;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Checks a loaded model against the answers its trainer recorded for it.
 *
 * <p>The training module has done this since the beginning, and everything it
 * measures is checked this way. What ships was not: the app opened
 * {@code belief.bin}, confirmed that the header said the right number of inputs
 * and the right encoding version, and played with whatever came after it. Those
 * two integers are the cheap part of the file. Nothing looked at the two and a
 * half megabytes of weights.
 *
 * <p>What that leaves open is narrow but real, and every case is silent. An
 * asset truncated by a packaging step still has a valid header. So does one
 * written by a future exporter that reorders a block, or one carried across a
 * change to the forward pass — the LayerNorm epsilon, the GELU, the order of
 * the trunk's weights — where the file is intact and the arithmetic reading it
 * has moved. In every one of those the model loads, answers, and is wrong; the
 * player is not broken, merely weak, which is the one failure this project has
 * learnt it cannot see from the outside.
 *
 * <p>So the trainer's own inputs and outputs travel with the weights, and the
 * thing that loads them replays a handful before it lets them play. Sixteen
 * forward passes at load time, once, against a file of twenty-five kilobytes.
 *
 * <h2>The format</h2>
 *
 * <p>Big-endian, like the weights, and deliberately simpler than the
 * {@code .npz} the trainer keeps for itself:
 *
 * <pre>
 *   int count, int inputs, int outputs
 *   count x inputs  float32   the features
 *   count x outputs float32   the logits the trainer produced for them
 * </pre>
 *
 * <p>Written by {@code arena/python/export_weights.py} beside
 * {@code belief.bin}. A model directory without one is a model from before this
 * existed; see {@link #check} for what that means.
 */
public final class BeliefParity {

    /**
     * How far a logit may sit from the trainer's.
     *
     * <p>The same 2e-3 the training module uses, and for the same reason: it is
     * loose enough for float32 summation order to differ between two correct
     * implementations, and far tighter than any of the mistakes above. A wrong
     * GELU misses by about 1e-3 per activation and three layers of it land
     * nowhere near here.
     */
    public static final float TOLERANCE = 2e-3f;

    private BeliefParity() {}

    /**
     * Replays the recorded cases, and throws if the model disagrees.
     *
     * <p>Fatal rather than advisory, which is the whole point: a check that
     * merely logs is a check that ships a broken model with a note about it in
     * a file nobody reads. The caller decides what to do about the exception —
     * both callers here fall back to uniform sampling, because a player without
     * the belief is weaker and still correct.
     *
     * <p>The stream is not closed; the caller opened it.
     *
     * @param net      the model to test
     * @param recorded the trainer's cases, in the format above
     */
    public static void check(BeliefNet net, InputStream recorded) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(recorded));
        int count = in.readInt();
        int inputs = in.readInt();
        int outputs = in.readInt();
        if (count <= 0 || inputs <= 0 || outputs <= 0 || count > 4096) {
            throw new IOException("implausible parity file: " + count + " cases of "
                    + inputs + " -> " + outputs);
        }
        if (inputs != net.inputs()) {
            throw new IOException("the parity cases have " + inputs
                    + " inputs and the model wants " + net.inputs()
                    + "; they do not belong to each other");
        }

        float[][] features = new float[count][inputs];
        float[][] expected = new float[count][outputs];
        for (float[] row : features) for (int at = 0; at < inputs; at++) row[at] = in.readFloat();
        for (float[] row : expected) for (int at = 0; at < outputs; at++) row[at] = in.readFloat();

        float worst = 0;
        for (int row = 0; row < count; row++) {
            float[] got = net.logits(features[row]);
            if (got.length != outputs) {
                throw new IOException("the model answered with " + got.length
                        + " numbers where " + outputs + " were recorded");
            }
            for (int at = 0; at < outputs; at++) {
                worst = Math.max(worst, Math.abs(got[at] - expected[row][at]));
            }
        }
        if (worst > TOLERANCE) {
            throw new IOException("this build and the trainer disagree about the same input,"
                    + " by up to " + worst + " in the logits. The weights are not usable"
                    + " until that is explained.");
        }
    }
}
