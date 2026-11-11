package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The learned bidder (arena/python/train_bidder.py): from what a seat knows
 * when it bids -- its ten, its place, and the shipped bidder's verdict on the
 * ten -- the expected game points of declaring at every rung of the bid
 * ladder, and from those a ceiling.
 *
 * <p>The features, 37: the ten one-hot by card index (suit × 8 + rank, the
 * engine's enum order), the place one-hot (forehand, middlehand, rearhand),
 * the shipped ceiling / 100 (the highest bid it would hold against a bid, 0
 * for none), and 1 when the shipped bidder would say 18 into an empty
 * auction. The output, one value a rung of {@link #ladder()}, in units of
 * {@code scale} game points; the file may hold several nets (the training's
 * init seeds), and the prediction is their mean.
 *
 * <p>The ceiling is {@code learned-first} at 0: the last rung of the first run
 * of rungs predicted above zero -- it stops at the first rung it expects to
 * lose, since a defender scores nothing and declaring is worth it only while
 * the expectation is positive. {@link SearchAiProvider#withLearnedBidder} bids
 * the higher of this and the shipped ceiling (the report's {@code union}).
 *
 * <p>The file, little-endian: "SKBN", version 1, features, rungs, nets,
 * layers, the scale (a float), the ladder (rungs ints), then per net per layer
 * its output and input widths, the weights row by row and the bias. Every
 * hidden layer is a ReLU, the last is linear.
 */
public final class LearnedBidder {

    public static final int FEATURES = 32 + 3 + 2;
    private static final int MAGIC = 0x4E424B53;   // "SKBN", little-endian

    private final int[] ladder;
    private final float scale;
    /** [net][layer][out][in] and [net][layer][out]. */
    private final float[][][][] weights;
    private final float[][][] biases;

    public LearnedBidder(int[] ladder, float scale, float[][][][] weights, float[][][] biases) {
        if (weights.length == 0 || weights.length != biases.length) throw new IllegalArgumentException("nets");
        for (int n = 0; n < weights.length; n++) {
            if (weights[n].length == 0 || weights[n].length != biases[n].length) {
                throw new IllegalArgumentException("net " + n + ": one bias a layer, at least one layer");
            }
            for (int l = 0; l < weights[n].length; l++) {
                int in = l == 0 ? FEATURES : weights[n][l - 1].length;
                for (float[] row : weights[n][l]) {
                    if (row.length != in) throw new IllegalArgumentException("net " + n + " layer " + l + " width");
                }
                if (biases[n][l].length != weights[n][l].length) throw new IllegalArgumentException("a bias");
            }
            if (weights[n][weights[n].length - 1].length != ladder.length) {
                throw new IllegalArgumentException("net " + n + " predicts " + weights[n][weights[n].length - 1].length
                        + " rungs, the ladder has " + ladder.length);
            }
        }
        for (int i = 1; i < ladder.length; i++) {
            if (ladder[i] <= ladder[i - 1]) throw new IllegalArgumentException("the ladder rises");
        }
        this.ladder = ladder.clone();
        this.scale = scale;
        this.weights = weights;
        this.biases = biases;
    }

    public static LearnedBidder load(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        ByteBuffer h = little(data.readNBytes(28), 28, "the header");
        if (h.getInt() != MAGIC) throw new IOException("not a bidder net (magic)");
        if (h.getInt() != 1) throw new IOException("a bidder net of another version");
        int features = h.getInt();
        int rungs = h.getInt();
        int nets = h.getInt();
        int layers = h.getInt();
        float scale = h.getFloat();
        if (features != FEATURES || rungs < 1 || rungs > 256 || nets < 1 || nets > 64 || layers < 1 || layers > 16) {
            throw new IOException("a bidder net of " + features + " features, " + rungs + " rungs, " + nets
                    + " nets of " + layers + " layers");
        }
        ByteBuffer lb = little(data.readNBytes(4 * rungs), 4 * rungs, "the ladder");
        int[] ladder = new int[rungs];
        for (int i = 0; i < rungs; i++) ladder[i] = lb.getInt();
        float[][][][] w = new float[nets][layers][][];
        float[][][] b = new float[nets][layers][];
        for (int n = 0; n < nets; n++) {
            for (int l = 0; l < layers; l++) {
                ByteBuffer dims = little(data.readNBytes(8), 8, "layer dims");
                int out = dims.getInt();
                int width = dims.getInt();
                if (out < 1 || out > 4096 || width < 1 || width > 4096) throw new IOException("layer " + l + " dims");
                int bytes = 4 * (out * width + out);
                ByteBuffer body = little(data.readNBytes(bytes), bytes, "net " + n + " layer " + l);
                w[n][l] = new float[out][width];
                for (int o = 0; o < out; o++) for (int i = 0; i < width; i++) w[n][l][o][i] = body.getFloat();
                b[n][l] = new float[out];
                for (int o = 0; o < out; o++) b[n][l][o] = body.getFloat();
            }
        }
        if (data.read() != -1) throw new IOException("bytes after the last layer");
        try {
            return new LearnedBidder(ladder, scale, w, b);
        } catch (IllegalArgumentException e) {
            throw new IOException("a bidder net that does not fit together: " + e.getMessage(), e);
        }
    }

    private static ByteBuffer little(byte[] bytes, int expected, String what) throws IOException {
        if (bytes.length != expected) throw new IOException("a bidder net cut short in " + what);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The rungs the values are for: BidValues.LADDER, as the exporter wrote it. */
    public int[] ladder() {
        return ladder.clone();
    }

    static float[] features(Collection<Card> ten, int place, int shippedCeiling, boolean shippedOpens) {
        if (ten.size() != 10) throw new IllegalArgumentException("ten cards, not " + ten.size());
        if (place < 0 || place > 2) throw new IllegalArgumentException("place " + place);
        float[] x = new float[FEATURES];
        for (Card c : ten) x[c.suit.ordinal() * 8 + c.rank.ordinal()] = 1f;
        x[32 + place] = 1f;
        x[35] = shippedCeiling / 100f;
        x[36] = shippedOpens ? 1f : 0f;
        return x;
    }

    /** Expected game points of declaring at every rung, the nets' mean. */
    float[] values(float[] x) {
        if (x.length != FEATURES) throw new IllegalArgumentException(x.length + " features");
        float[] sum = new float[ladder.length];
        for (int n = 0; n < weights.length; n++) {
            float[] a = x;
            for (int l = 0; l < weights[n].length; l++) {
                float[] next = new float[weights[n][l].length];
                for (int o = 0; o < next.length; o++) {
                    float s = biases[n][l][o];
                    float[] row = weights[n][l][o];
                    for (int i = 0; i < row.length; i++) s += row[i] * a[i];
                    next[o] = l < weights[n].length - 1 ? Math.max(0f, s) : s;
                }
                a = next;
            }
            for (int r = 0; r < sum.length; r++) sum[r] += a[r];
        }
        for (int r = 0; r < sum.length; r++) sum[r] = sum[r] / weights.length * scale;
        return sum;
    }

    /** The last rung of the first run predicted above zero; 0 when the first rung is not. */
    int ceiling(float[] values) {
        int c = 0;
        for (int r = 0; r < ladder.length; r++) {
            if (values[r] > 0f) c = ladder[r];
            else break;
        }
        return c;
    }

    /** The ceiling for this ten at this place (0 forehand, 1 middlehand, 2 rearhand), given the shipped verdict. */
    public int ceiling(Collection<Card> ten, int place, int shippedCeiling, boolean shippedOpens) {
        return ceiling(values(features(ten, place, shippedCeiling, shippedOpens)));
    }

    /**
     * Checks the exporter's parity file: per line the ten card indices, the
     * place, the shipped ceiling, the shipped opening (0/1), the value at every
     * rung as Python computed it, and the ceiling. Returns the lines checked.
     */
    public int checkParity(BufferedReader parity, float tolerance) throws IOException {
        int checked = 0;
        String line;
        while ((line = parity.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length != 13 + ladder.length + 1) {
                throw new IOException("a parity line of " + f.length + " fields: " + line);
            }
            List<Card> ten = new ArrayList<>(10);
            for (int i = 0; i < 10; i++) ten.add(card(Integer.parseInt(f[i])));
            int place = Integer.parseInt(f[10]);
            int shippedCeiling = Integer.parseInt(f[11]);
            boolean shippedOpens = Integer.parseInt(f[12]) != 0;
            float[] got = values(features(ten, place, shippedCeiling, shippedOpens));
            boolean nearZero = false;
            for (int r = 0; r < ladder.length; r++) {
                float expected = Float.parseFloat(f[13 + r]);
                if (Math.abs(got[r] - expected) > tolerance * Math.max(1f, Math.abs(expected))) {
                    throw new IOException("rung " + ladder[r] + ": " + got[r] + ", the exporter's " + expected
                            + ": " + line);
                }
                if (Math.abs(expected) <= tolerance * Math.max(1f, Math.abs(expected))) nearZero = true;
            }
            int expectedCeiling = Integer.parseInt(f[13 + ladder.length]);
            // A value within the tolerance of zero may fall either side; its ceiling is no test.
            if (!nearZero && ceiling(got) != expectedCeiling) {
                throw new IOException("ceiling " + ceiling(got) + ", the exporter's " + expectedCeiling + ": " + line);
            }
            checked++;
        }
        return checked;
    }

    private static Card card(int index) {
        return new Card(Card.Suit.values()[index / 8], Card.Rank.values()[index % 8]);
    }
}
