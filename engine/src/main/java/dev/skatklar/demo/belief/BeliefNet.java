package dev.skatklar.demo.belief;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The trained belief, as three matrix multiplications.
 *
 * <p>Why this exists when there is already a working ONNX loader: the runtime
 * that loads it drags native libraries for every architecture into the APK, for
 * a network of 735,000 parameters whose forward pass is a hundred lines of
 * arithmetic. The phone build should not pay a platform dependency to multiply
 * four matrices. So the model ships as a plain array of numbers and this class
 * reads it.
 *
 * <p>It is also the only implementation the arena needs. One forward pass, used
 * by the measurement and by the product, is one fewer way for the two to
 * disagree — and the disagreement they could have had is exactly the one that is
 * invisible from the outside: a player that is merely a bit weak.
 *
 * <h2>Matching the trainer exactly</h2>
 *
 * <p>Three details decide whether this agrees with PyTorch to the last decimal,
 * and all three are easy to get subtly wrong:
 *
 * <ul>
 *   <li><b>LayerNorm</b> normalises with the <em>biased</em> variance — divided
 *       by n, not n−1 — and adds {@code eps} inside the square root. Torch's
 *       default eps is 1e-5.</li>
 *   <li><b>GELU</b> is the exact one, {@code 0.5·x·(1 + erf(x/√2))}, not the
 *       tanh approximation. They differ by about 1e-3 around |x| = 2, which is
 *       far more than the parity check tolerates. Java has no {@code erf}, so
 *       there is one here.</li>
 *   <li><b>Dropout</b> is the identity once the model is in eval mode, so it
 *       does not appear below at all.</li>
 * </ul>
 *
 * <p>Whether all three are right is not a matter of reading the code: the
 * trainer writes a handful of real inputs and its own outputs for them, and the
 * loader replays those. See {@code BeliefNetTest} and {@code model.json}.
 */
public final class BeliefNet {

    /** File magic: "SKBW", SkatKlar belief weights. */
    private static final int MAGIC = 0x534B4257;
    /** The layout this class understands. */
    public static final int FORMAT = 1;
    /** LayerNorm's epsilon, inside the square root, as PyTorch does it. */
    private static final float EPS = 1e-5f;

    private final int inputs;
    private final int encodingVersion;
    private final Layer[] trunk;
    private final Layer head;

    private BeliefNet(int inputs, int encodingVersion, Layer[] trunk, Layer head) {
        this.inputs = inputs;
        this.encodingVersion = encodingVersion;
        this.trunk = trunk;
        this.head = head;
    }

    /** One {@code Linear}, and for the trunk the {@code LayerNorm} after it. */
    private record Layer(int in, int out, float[] weight, float[] bias,
                         float[] gamma, float[] beta) {}

    public int inputs() { return inputs; }

    /** The encoding the model was trained against; compare with {@link BeliefEncoding#VERSION}. */
    public int encodingVersion() { return encodingVersion; }

    /**
     * Reads a weight file. See {@code arena/python/export_weights.py}, which
     * is the only thing that writes one.
     *
     * <p>Big-endian, because that is what {@link DataInputStream} reads without
     * ceremony and this file is written once and read on a phone. The stream is
     * not closed here; the caller opened it and owns it.
     */
    public static BeliefNet load(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source));
        if (in.readInt() != MAGIC) throw new IOException("not a belief weight file");
        int format = in.readInt();
        if (format != FORMAT) {
            throw new IOException("weight file format " + format + ", this build reads " + FORMAT);
        }
        int encodingVersion = in.readInt();
        int inputs = in.readInt();
        int hidden = in.readInt();
        int layers = in.readInt();
        int outputs = in.readInt();
        if (inputs <= 0 || hidden <= 0 || layers <= 0 || outputs <= 0 || layers > 16) {
            throw new IOException("implausible shape: " + inputs + "x" + hidden
                    + "x" + layers + "->" + outputs);
        }

        Layer[] trunk = new Layer[layers];
        int width = inputs;
        for (int layer = 0; layer < layers; layer++) {
            trunk[layer] = new Layer(width, hidden,
                    floats(in, width * hidden), floats(in, hidden),
                    floats(in, hidden), floats(in, hidden));
            width = hidden;
        }
        Layer head = new Layer(width, outputs,
                floats(in, width * outputs), floats(in, outputs), null, null);
        return new BeliefNet(inputs, encodingVersion, trunk, head);
    }

    private static float[] floats(DataInputStream in, int count) throws IOException {
        float[] values = new float[count];
        for (int at = 0; at < count; at++) values[at] = in.readFloat();
        return values;
    }

    /**
     * Class scores for every card, as {@code 32 x 3} flattened row-major.
     *
     * <p>Thread-safe: nothing here is retained between calls, and the scratch
     * space is a few kilobytes per call. The arena runs a board per thread and
     * three seats per board off one model, so a shared buffer would be a race
     * that only a long match would ever find.
     */
    public float[] logits(float[] features) {
        if (features.length != inputs) {
            throw new IllegalArgumentException(
                    "this model wants " + inputs + " inputs, not " + features.length);
        }
        float[] activations = features;
        for (Layer layer : trunk) {
            activations = gelu(normalise(linear(layer, activations), layer));
        }
        return linear(head, activations);
    }

    /** {@code y = W·x + b}, with W stored row-major as (out, in), the way torch does. */
    private static float[] linear(Layer layer, float[] input) {
        float[] out = new float[layer.out];
        for (int row = 0; row < layer.out; row++) {
            int at = row * layer.in;
            // Accumulated in double. The rows are hundreds of terms long and the
            // parity check is tight enough that the summation order shows.
            double sum = layer.bias[row];
            for (int column = 0; column < layer.in; column++) {
                sum += (double) layer.weight[at + column] * input[column];
            }
            out[row] = (float) sum;
        }
        return out;
    }

    /** LayerNorm over the whole row, biased variance, eps inside the root. */
    private static float[] normalise(float[] values, Layer layer) {
        double mean = 0;
        for (float value : values) mean += value;
        mean /= values.length;
        double variance = 0;
        for (float value : values) {
            double centred = value - mean;
            variance += centred * centred;
        }
        variance /= values.length;
        double scale = 1.0 / Math.sqrt(variance + EPS);
        for (int at = 0; at < values.length; at++) {
            values[at] = (float) ((values[at] - mean) * scale * layer.gamma[at] + layer.beta[at]);
        }
        return values;
    }

    private static float[] gelu(float[] values) {
        for (int at = 0; at < values.length; at++) {
            double x = values[at];
            values[at] = (float) (0.5 * x * (1.0 + erf(x / Math.sqrt(2.0))));
        }
        return values;
    }

    /**
     * The error function, to about seven digits.
     *
     * <p>Chebyshev fit of {@code erfc}, the standard one from Numerical Recipes.
     * Seven digits is far more than this needs — the tolerance downstream is
     * 2e-3 on a logit — but an approximation that is merely close, such as the
     * tanh form of GELU, is not: that one is out by around 1e-3 per activation
     * and three layers of it fail the parity check outright.
     */
    private static double erf(double x) {
        double z = Math.abs(x);
        double t = 2.0 / (2.0 + z);
        double ty = 4.0 * t - 2.0;
        double[] coefficients = {
            -1.3026537197817094, 6.4196979235649026e-1, 1.9476473204185836e-2,
            -9.561514786808631e-3, -9.46595344482036e-4, 3.66839497852761e-4,
            4.2523324806907e-5, -2.0278578112534e-5, -1.624290004647e-6,
            1.303655835580e-6, 1.5626441722e-8, -8.5238095915e-8,
            6.529054439e-9, 5.059343495e-9, -9.91364156e-10,
            -2.27365122e-10, 9.6467911e-11, 2.394038e-12,
            -6.886027e-12, 8.94487e-13, 3.13092e-13,
            -1.12708e-13, 3.81e-16, 7.106e-15
        };
        double d = 0;
        double dd = 0;
        for (int at = coefficients.length - 1; at > 0; at--) {
            double previous = d;
            d = ty * d - dd + coefficients[at];
            dd = previous;
        }
        double erfc = t * Math.exp(-z * z + 0.5 * (coefficients[0] + ty * d) - dd);
        return x >= 0 ? 1.0 - erfc : erfc - 1.0;
    }
}
