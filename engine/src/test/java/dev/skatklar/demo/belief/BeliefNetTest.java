package dev.skatklar.demo.belief;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

/**
 * The forward pass, against an implementation that shares nothing with it.
 *
 * <p>The fixture beside this class was produced by a NumPy reference written
 * from the PyTorch definition rather than from the Java: same LayerNorm with the
 * biased variance and eps inside the root, same exact erf-based GELU, same
 * float32 rounding between operations. If the two agree on eight random inputs
 * through three layers, every one of those details is right — and each of them
 * is a detail that would otherwise fail quietly, producing a model that is not
 * broken but merely a little wrong.
 *
 * <p>Small on purpose: twelve inputs, sixteen wide, nine out. The arithmetic is
 * the same arithmetic at 306 by 512, and a four-kilobyte fixture can live in the
 * repository. The real model is checked against the trainer's own outputs at
 * load time; that is a different check and it needs a trained model to run.
 */
public class BeliefNetTest {

    /**
     * The tolerance a wrong GELU would not survive.
     *
     * <p>The tanh approximation to GELU — the one most implementations use, and
     * the tempting shortcut here — differs from the exact function by around 1e-3
     * near |x| = 2. Three layers of that lands far outside this bound, which is
     * the point of choosing it.
     */
    private static final float TOLERANCE = 2e-4f;

    @Test public void itAgreesWithTheReferenceImplementation() throws IOException {
        BeliefNet net = load();
        Cases cases = cases();
        float worst = 0;
        for (int row = 0; row < cases.count; row++) {
            float[] got = net.logits(cases.inputs[row]);
            assertEquals(cases.outputs[row].length, got.length);
            for (int at = 0; at < got.length; at++) {
                worst = Math.max(worst, Math.abs(got[at] - cases.outputs[row][at]));
            }
        }
        assertTrue("the Java forward pass drifts from the reference by " + worst,
                worst < TOLERANCE);
    }

    @Test public void itReportsWhatItWasTrainedOn() throws IOException {
        BeliefNet net = load();
        assertEquals(12, net.inputs());
        assertEquals(2, net.encodingVersion());
    }

    @Test public void itRefusesAnInputOfTheWrongWidth() throws IOException {
        BeliefNet net = load();
        assertThrows(IllegalArgumentException.class, () -> net.logits(new float[11]));
    }

    /** A file that is not one of ours must fail on the magic, not on the maths. */
    @Test public void itRefusesSomethingThatIsNotAWeightFile() {
        byte[] rubbish = new byte[64];
        rubbish[0] = 'P';
        rubbish[1] = 'K';
        IOException failure = assertThrows(IOException.class,
                () -> BeliefNet.load(new ByteArrayInputStream(rubbish)));
        assertTrue(failure.getMessage(), failure.getMessage().contains("belief weight file"));
    }

    @Test public void itRefusesAFormatItCannotRead() throws IOException {
        byte[] weights = bytes("tiny-net.bin");
        weights[7] = (byte) (BeliefNet.FORMAT + 1);
        IOException failure = assertThrows(IOException.class,
                () -> BeliefNet.load(new ByteArrayInputStream(weights)));
        assertTrue(failure.getMessage(), failure.getMessage().contains("format"));
    }

    private static BeliefNet load() throws IOException {
        return BeliefNet.load(new ByteArrayInputStream(bytes("tiny-net.bin")));
    }

    private record Cases(int count, float[][] inputs, float[][] outputs) {}

    private static Cases cases() throws IOException {
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes("tiny-net-cases.bin")));
        int count = in.readInt();
        int width = in.readInt();
        int outputs = in.readInt();
        float[][] inputs = new float[count][width];
        float[][] expected = new float[count][outputs];
        for (float[] row : inputs) for (int at = 0; at < width; at++) row[at] = in.readFloat();
        for (float[] row : expected) for (int at = 0; at < outputs; at++) row[at] = in.readFloat();
        return new Cases(count, inputs, expected);
    }

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = BeliefNetTest.class.getResourceAsStream(name)) {
            assertNotNull("missing test resource " + name, in);
            return in.readAllBytes();
        }
    }
}
