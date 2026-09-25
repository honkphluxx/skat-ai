package dev.skatklar.training.belief;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.arena.PlayerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Test;

/**
 * {@code belief-32-shipped-candidate} is the shipped player with another net
 * and nothing else changed.
 *
 * <p>It fell behind once. When the ladder shipped, {@code belief-32-shipped}
 * became the ladder variant and the candidate slot did not, so every net
 * measured through it for two days played without the Schneider defence the
 * shipped net had, and a net that placed the declarer's cards better read a
 * point a game worse. This builds both from a throwaway model directory and
 * reads the one card-play setting that drifted, plus the flat variant as the
 * control that proves the reading can tell the two apart.
 */
public class BeliefPlayersTwinTest {

    private String savedModel;
    private String savedCandidate;

    @After public void restoreProperties() {
        restore(BeliefPlayers.DIRECTORY_PROPERTY, savedModel);
        restore(BeliefPlayers.CANDIDATE_PROPERTY, savedCandidate);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    @Test public void theCandidateSlotIsTheShippedPlayerWithAnotherNet() throws Exception {
        Path shipped = tinyModel("shipped");
        Path candidate = tinyModel("candidate");
        savedModel = System.getProperty(BeliefPlayers.DIRECTORY_PROPERTY);
        savedCandidate = System.getProperty(BeliefPlayers.CANDIDATE_PROPERTY);
        System.setProperty(BeliefPlayers.DIRECTORY_PROPERTY, shipped.toString());
        System.setProperty(BeliefPlayers.CANDIDATE_PROPERTY, candidate.toString());

        PlayerRegistry registry = PlayerRegistry.withDefaults();
        boolean shippedLadder = ladder(registry.resolve("belief-32-shipped").newProvider(1));
        boolean candidateLadder = ladder(registry.resolve("belief-32-shipped-candidate").newProvider(1));
        boolean flatLadder = ladder(registry.resolve("belief-32-shipped-flat").newProvider(1));

        assertTrue("the shipped player has the ladder", shippedLadder);
        assertFalse("the flat variant has not -- so this reading can tell them apart", flatLadder);
        assertEquals("the candidate slot plays the shipped card play", shippedLadder, candidateLadder);
    }

    /** The ladder flag, read through the package-private accessor the engine's own tests use. */
    private static boolean ladder(SkatAiProvider provider) throws Exception {
        assertTrue("a belief player is a search player", provider instanceof SearchAiProvider);
        Method settings = SearchAiProvider.class.getDeclaredMethod("cardPlaySettings");
        settings.setAccessible(true);
        Object cardPlay = settings.invoke(provider);
        Field ladder = cardPlay.getClass().getDeclaredField("ladder");
        ladder.setAccessible(true);
        return ladder.getBoolean(cardPlay);
    }

    /**
     * A model directory the loader accepts: a one-neuron net of the right
     * width in belief.bin, and the model.json beside it. Never consulted --
     * newProvider builds the player, and no card is played here.
     */
    private static Path tinyModel(String name) throws IOException {
        Path directory = Files.createTempDirectory("belief-twin-" + name);
        int inputs = BeliefEncoding.SIZE, hidden = 1, layers = 1, outputs = 32 * BeliefEncoding.CLASSES;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x534B4257); out.writeInt(2); out.writeInt(BeliefEncoding.VERSION);
        out.writeInt(inputs); out.writeInt(hidden); out.writeInt(layers); out.writeInt(outputs);
        out.writeInt(0x7F);
        for (int at = 0; at < inputs * hidden + hidden + hidden + hidden; at++) out.writeFloat(0);
        for (int at = 0; at < hidden * outputs + outputs; at++) out.writeFloat(0);
        out.flush();
        Files.write(directory.resolve("belief.bin"), bytes.toByteArray());
        Files.writeString(directory.resolve("model.json"), "{\n  \"encoding_version\": "
                + BeliefEncoding.VERSION + ",\n  \"inputs\": " + inputs + ",\n  \"hidden\": " + hidden
                + ",\n  \"layers\": " + layers + ",\n  \"contracts_trained\": 127\n}\n", StandardCharsets.UTF_8);
        return directory;
    }
}
