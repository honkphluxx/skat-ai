package dev.skatklar.demo.belief;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.Test;

/**
 * The batched forward pass answers exactly what the single pass answers.
 *
 * <p>A net of random weights, two hidden layers of an odd width (so the
 * batched kernel's four-rows-at-a-time loop has a remainder), and batches
 * that are smaller than, equal to and larger than its block of inputs, with
 * a ragged last block: every output must equal the single call's bit for bit.
 */
public class PolicyNetBatchTest {

    static PolicyNet randomNet(long seed, int hidden, int layers) throws IOException {
        Random random = new Random(seed);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int inputs = BeliefEncoding.SIZE;
        int outputs = PolicyNet.OUTPUTS;
        for (int v : new int[] {PolicyNet.MAGIC, 2, BeliefEncoding.VERSION, inputs, hidden, layers, outputs, 0x1F}) {
            out.writeInt(v);
        }
        int width = inputs;
        for (int layer = 0; layer < layers; layer++) {
            for (int at = 0; at < hidden * width; at++) out.writeFloat((float) (random.nextGaussian() * 0.1));
            for (int at = 0; at < hidden; at++) out.writeFloat((float) (random.nextGaussian() * 0.1));
            for (int at = 0; at < hidden; at++) out.writeFloat((float) (1 + random.nextGaussian() * 0.1));
            for (int at = 0; at < hidden; at++) out.writeFloat((float) (random.nextGaussian() * 0.1));
            width = hidden;
        }
        for (int at = 0; at < outputs * width; at++) out.writeFloat((float) (random.nextGaussian() * 0.1));
        for (int at = 0; at < outputs; at++) out.writeFloat((float) (random.nextGaussian() * 0.1));
        return PolicyNet.load(new ByteArrayInputStream(bytes.toByteArray()), null);
    }

    @Test public void theBatchIsTheSingleCallBitForBit() throws IOException {
        PolicyNet net = randomNet(5, 37, 2);
        Random random = new Random(9);
        for (int n : new int[] {1, 3, 64, 65, 130}) {
            float[][] batch = new float[n][BeliefEncoding.SIZE];
            for (float[] x : batch) for (int j = 0; j < x.length; j++) x[j] = random.nextInt(256) / 255f;
            float[][] answers = net.logits(batch);
            assertEquals(n, answers.length);
            for (int i = 0; i < n; i++) assertArrayEquals("batch of " + n + ", input " + i, net.logits(batch[i]), answers[i], 0f);
        }
    }
}
