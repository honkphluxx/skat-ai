package dev.skatklar.demo.belief;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

/**
 * {@link PolicyNet}: what it loads, what it refuses, and the card it names.
 *
 * <p>The forward pass is the belief net's and is checked against an
 * independent reference in {@code BeliefNetTest}; the trained student is
 * checked against its trainer's own answers at load time. What is left to test
 * here is the policy's own part, on a net built so its answer is known: a
 * zero trunk, whose LayerNorm and GELU then pass a constant along, and a head
 * whose bias is the score of each card -- whatever the input, card i scores
 * {@code bias[i]}.
 */
public class PolicyNetTest {

    /** A policy file whose scores are {@code bias} for every input; hidden 4, one layer. */
    public static byte[] constant(float[] bias, int contracts, int magic, int outputs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int inputs = BeliefEncoding.SIZE;
        int hidden = 4;
        out.writeInt(magic);
        out.writeInt(2);
        out.writeInt(BeliefEncoding.VERSION);
        out.writeInt(inputs);
        out.writeInt(hidden);
        out.writeInt(1);
        out.writeInt(outputs);
        out.writeInt(contracts);
        for (int at = 0; at < hidden * inputs; at++) out.writeFloat(0f);   // linear weight
        for (int at = 0; at < hidden; at++) out.writeFloat(0f);            // linear bias
        for (int at = 0; at < hidden; at++) out.writeFloat(1f);            // gamma
        for (int at = 0; at < hidden; at++) out.writeFloat(0f);            // beta
        for (int at = 0; at < outputs * hidden; at++) out.writeFloat(0f);  // head weight
        for (int at = 0; at < outputs; at++) out.writeFloat(at < bias.length ? bias[at] : 0f);
        out.flush();
        return bytes.toByteArray();
    }

    /** The trainer's answers, in the parity format: one input, the given outputs. */
    static byte[] parity(float[] expected) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeInt(BeliefEncoding.SIZE);
        out.writeInt(expected.length);
        for (int at = 0; at < BeliefEncoding.SIZE; at++) out.writeFloat(at % 7 == 0 ? 1f : 0f);
        for (float value : expected) out.writeFloat(value);
        out.flush();
        return bytes.toByteArray();
    }

    /** Scores rising with the card index, the value head's half negative. */
    public static float[] rising() {
        float[] bias = new float[PolicyNet.OUTPUTS];
        for (int at = 0; at < 32; at++) bias[at] = at / 4f;
        for (int at = 32; at < 64; at++) bias[at] = -at / 8f;
        return bias;
    }

    private static PolicyNet load(float[] bias, int contracts) throws IOException {
        return PolicyNet.load(new ByteArrayInputStream(constant(bias, contracts, PolicyNet.MAGIC, 64)),
                new ByteArrayInputStream(parity(bias)));
    }

    @Test public void itAnswersWithTheHeadItWasGiven() throws IOException {
        float[] bias = rising();
        PolicyNet net = load(bias, 0x1F);
        float[] got = net.logits(new float[BeliefEncoding.SIZE]);
        assertEquals(64, got.length);
        assertArrayEquals(bias, got, 1e-6f);
    }

    @Test public void itNamesTheLegalCardItScoresHighest() throws IOException {
        float[] bias = new float[64];
        Card ace = new Card(Card.Suit.HEARTS, Card.Rank.ACE);
        Card seven = new Card(Card.Suit.CLUBS, Card.Rank.SEVEN);
        Card jack = new Card(Card.Suit.CLUBS, Card.Rank.JACK);
        bias[BeliefEncoding.index(ace)] = 2f;
        bias[BeliefEncoding.index(seven)] = 3f;
        bias[BeliefEncoding.index(jack)] = 9f;        // the best card of all, and not legal
        bias[32 + BeliefEncoding.index(ace)] = 50f;   // the value head never plays
        PolicyNet net = load(bias, 0x1F);
        float[] x = new float[BeliefEncoding.SIZE];
        assertEquals(seven, net.choose(x, List.of(ace, seven)));
        assertEquals(seven, net.choose(x, List.of(seven, ace)));
        assertEquals(ace, net.choose(x, List.of(ace)));
        assertEquals(null, net.choose(x, List.of()));
    }

    @Test public void itKnowsWhichContractsItWasShown() throws IOException {
        PolicyNet net = load(rising(), 0x1F);
        for (Contract contract : List.of(Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES,
                Contract.CLUBS, Contract.GRAND)) {
            assertTrue(contract.name(), net.covers(contract));
        }
        assertFalse("no Null in the corpus", net.covers(Contract.NULL));
    }

    @Test public void itRefusesTheWrongFile() throws IOException {
        byte[] belief = constant(rising(), 0x1F, 0x534B4257, 64);  // "SKBW", a belief file
        assertThrows(IOException.class, () -> PolicyNet.load(new ByteArrayInputStream(belief), null));
        byte[] policy = constant(rising(), 0x1F, PolicyNet.MAGIC, 64);
        assertThrows("a policy is not a belief", IOException.class,
                () -> BeliefNet.load(new ByteArrayInputStream(policy)));
        byte[] wide = constant(new float[96], 0x1F, PolicyNet.MAGIC, 96);
        assertThrows("96 outputs is a belief's shape", IOException.class,
                () -> PolicyNet.load(new ByteArrayInputStream(wide), null));
    }

    @Test public void itRefusesWeightsTheTrainerDisagreesWith() throws IOException {
        float[] bias = rising();
        float[] recorded = bias.clone();
        recorded[5] += 0.01f;
        assertThrows(IOException.class, () -> PolicyNet.load(
                new ByteArrayInputStream(constant(bias, 0x1F, PolicyNet.MAGIC, 64)),
                new ByteArrayInputStream(parity(recorded))));
    }
}
