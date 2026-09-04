package dev.skatklar.demo.belief;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

/**
 * The check that stands between a corrupted asset and a quietly weak player.
 *
 * <p>Built against the same tiny network {@link BeliefNetTest} uses, with cases
 * generated from the model itself: a check that passes on its own output is
 * exactly what a correct model directory looks like. What matters is the other
 * half — that it fails, loudly, on each of the ways a weight file can be wrong
 * while still being loadable.
 */
public class BeliefParityTest {

    @Test public void itAcceptsAModelThatReproducesItsOwnAnswers() throws IOException {
        BeliefNet net = load();
        BeliefParity.check(net, new ByteArrayInputStream(cases(net, 0f)));
    }

    /**
     * A model that is off by more than the tolerance is refused.
     *
     * <p>The perturbation is 0.01 in a single logit — smaller than a wrong GELU
     * costs, and five times the tolerance. This is the magnitude the check has
     * to catch, because it is the magnitude that leaves a player merely a little
     * wrong rather than visibly broken.
     */
    @Test public void itRefusesAModelThatDrifts() throws IOException {
        BeliefNet net = load();
        IOException failure = assertThrows(IOException.class,
                () -> BeliefParity.check(net, new ByteArrayInputStream(cases(net, 0.01f))));
        assertTrue(failure.getMessage(), failure.getMessage().contains("disagree"));
    }

    /** Within tolerance is not drift: float32 summation order is allowed to differ. */
    @Test public void itToleratesTheLastDecimal() throws IOException {
        BeliefNet net = load();
        BeliefParity.check(net, new ByteArrayInputStream(cases(net, BeliefParity.TOLERANCE / 2)));
    }

    @Test public void itRefusesCasesThatBelongToAnotherModel() throws IOException {
        BeliefNet net = load();
        byte[] wrongWidth = cases(net, 0f);
        // The input width lives in the second int; claim one more than the model
        // has and the file is describing a different network.
        wrongWidth[7] = (byte) (net.inputs() + 1);
        IOException failure = assertThrows(IOException.class,
                () -> BeliefParity.check(net, new ByteArrayInputStream(wrongWidth)));
        assertTrue(failure.getMessage(),
                failure.getMessage().contains("do not belong to each other"));
    }

    @Test public void itRefusesAFileThatIsNotOne() {
        IOException failure = assertThrows(IOException.class, () -> BeliefParity.check(
                load(), new ByteArrayInputStream(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})));
        assertTrue(failure.getMessage(), failure.getMessage().contains("implausible"));
    }

    /** A truncated asset is the case this exists for; it must not read as valid. */
    @Test public void itRefusesATruncatedFile() throws IOException {
        BeliefNet net = load();
        byte[] full = cases(net, 0f);
        byte[] cut = new byte[full.length / 2];
        System.arraycopy(full, 0, cut, 0, cut.length);
        assertThrows(IOException.class,
                () -> BeliefParity.check(net, new ByteArrayInputStream(cut)));
    }

    /**
     * Cases in the shipped format, taken from the model and then nudged.
     *
     * @param drift added to one logit of every row, so a tolerance that is too
     *              loose shows up as a passing test rather than a passing model
     */
    private static byte[] cases(BeliefNet net, float drift) throws IOException {
        int count = 6;
        Random random = new Random(4);
        float[][] features = new float[count][net.inputs()];
        for (float[] row : features) {
            for (int at = 0; at < row.length; at++) row[at] = (float) random.nextGaussian();
        }
        float[][] logits = new float[count][];
        for (int row = 0; row < count; row++) {
            logits[row] = net.logits(features[row]);
            logits[row][row % logits[row].length] += drift;
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(count);
        out.writeInt(net.inputs());
        out.writeInt(logits[0].length);
        for (float[] row : features) for (float value : row) out.writeFloat(value);
        for (float[] row : logits) for (float value : row) out.writeFloat(value);
        out.flush();
        return bytes.toByteArray();
    }

    private static BeliefNet load() throws IOException {
        try (InputStream in = BeliefNetTest.class.getResourceAsStream("tiny-net.bin")) {
            assertNotNull("missing test resource tiny-net.bin", in);
            return BeliefNet.load(new ByteArrayInputStream(in.readAllBytes()));
        }
    }
}
