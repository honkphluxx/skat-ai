package dev.skatklar.demo.belief;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Contract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.Test;

/**
 * A model says which contracts it has seen, and an old one says nothing.
 *
 * <p>The rule this replaced was a constant in the sampler: never consult the
 * belief about a Null. True when it was written -- 0 Null decision points in
 * 89,438 -- and silent the moment it stopped being true. A whole night was
 * spent measuring a Null-trained model against the shipped one and it came back
 * +0.000 on 539 of 539 boards, because the sampler sent both sides to the
 * uniform baseline and never asked either model anything.
 *
 * <p>So the fact moved into the weight file, and the two things worth pinning
 * are that a format 2 file carries it and that a format 1 file -- which cannot
 * -- is read as having seen nothing rather than as having seen everything. The
 * direction of that default is the whole safety property: a model asked about a
 * contract it never met returns an arbitrary belief, not a weak one.
 */
public class ContractsTrainedTest {

    private static final int MAGIC = 0x534B4257;

    /** A minimal but real weight file: one tiny layer and a head. */
    private static byte[] weights(int format, int contracts) throws IOException {
        int inputs = 2, hidden = 2, layers = 1, outputs = 3;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(format);
        out.writeInt(BeliefEncoding.VERSION);
        out.writeInt(inputs);
        out.writeInt(hidden);
        out.writeInt(layers);
        out.writeInt(outputs);
        if (format >= 2) out.writeInt(contracts);
        // trunk: Linear weight and bias, LayerNorm gamma and beta
        for (int at = 0; at < inputs * hidden; at++) out.writeFloat(0.1f);
        for (int at = 0; at < hidden; at++) out.writeFloat(0.0f);
        for (int at = 0; at < hidden; at++) out.writeFloat(1.0f);
        for (int at = 0; at < hidden; at++) out.writeFloat(0.0f);
        // head
        for (int at = 0; at < hidden * outputs; at++) out.writeFloat(0.1f);
        for (int at = 0; at < outputs; at++) out.writeFloat(0.0f);
        out.flush();
        return bytes.toByteArray();
    }

    private static BeliefNet read(int format, int contracts) throws IOException {
        return BeliefNet.load(new ByteArrayInputStream(weights(format, contracts)));
    }

    @Test public void aFormatTwoFileCarriesWhatItWasTrainedOn() throws IOException {
        int mask = BeliefModel.contractBit(Contract.CLUBS)
                | BeliefModel.contractBit(Contract.NULL);
        assertEquals(mask, read(2, mask).trainedOnContracts());
    }

    /**
     * The load-bearing default, and the direction of it is the whole migration.
     *
     * <p>A format 1 file is read as having seen every contract but Null, which
     * is not a guess about what it saw -- it is the rule that was in force when
     * it was written, since the sampler consulted the model on everything and
     * refused only on a Null. Read the other way, as "saw nothing", every model
     * shipped to date would silently lose its belief on every contract, which
     * is a far larger change than the one intended.
     *
     * <p>Asserted on the format that actually exists in the wild: the shipped
     * belief.bin is format 1.
     */
    @Test public void aFormatOneFileMeansWhatTheOldRuleMeant() throws IOException {
        BeliefModel old = asModel(read(1, 0));
        assertEquals(BeliefModel.LEGACY_CONTRACTS, old.trainedOnContracts());
        for (Contract contract : Contract.values()) {
            if (contract == Contract.NULL) {
                assertFalse("the old rule refused on Null", old.sawContract(contract));
            } else {
                assertTrue("the old rule consulted the model on " + contract,
                        old.sawContract(contract));
            }
        }
    }

    /** Wraps a net as the model interface, which is what the sampler holds. */
    private static BeliefModel asModel(BeliefNet net) {
        return new BeliefModel() {
            @Override public float[] logits(float[] features) { return net.logits(features); }
            @Override public int trainedOnContracts() { return net.trainedOnContracts(); }
        };
    }

    /** A format 2 file that genuinely saw no contract can say so, and is believed. */
    @Test public void aFormatTwoFileMaySayItSawNothing() throws IOException {
        BeliefModel empty = asModel(read(2, 0));
        assertEquals(0, empty.trainedOnContracts());
        for (Contract contract : Contract.values()) assertFalse(empty.sawContract(contract));
    }

    /** The bits are per contract and do not bleed into one another. */
    @Test public void oneContractDoesNotImplyAnother() {
        BeliefModel onlyNull = new BeliefModel() {
            @Override public float[] logits(float[] features) { return new float[96]; }
            @Override public int trainedOnContracts() {
                return BeliefModel.contractBit(Contract.NULL);
            }
        };
        assertTrue(onlyNull.sawContract(Contract.NULL));
        for (Contract contract : Contract.values()) {
            if (contract != Contract.NULL) assertFalse(onlyNull.sawContract(contract));
        }
    }

    /** A bare lambda inherits the old rule too, so no existing caller shifts. */
    @Test public void theBareFunctionalFormInheritsTheOldRule() {
        BeliefModel bare = features -> new float[96];
        assertEquals(BeliefModel.LEGACY_CONTRACTS, bare.trainedOnContracts());
        assertFalse(bare.sawContract(Contract.NULL));
        assertTrue(bare.sawContract(Contract.GRAND));
        assertTrue(bare.sawContract(Contract.CLUBS));
    }

    /** A format from the future is refused rather than misread. */
    @Test public void anUnknownFormatIsRefused() {
        try {
            read(3, 0);
            org.junit.Assert.fail("format 3 must not be read as format 2");
        } catch (IOException refused) {
            assertTrue(refused.getMessage().contains("format 3"));
        }
    }
}
