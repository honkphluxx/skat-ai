package dev.skatklar.training.belief;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

/**
 * The fixture reader, read against a file NumPy actually wrote.
 *
 * <p>Hand-rolling the archive in the test would prove only that the reader
 * agrees with itself. The resource beside this class came out of
 * {@code np.savez}, so what is checked is the format as it arrives — including
 * the part that catches readers out: NumPy writes the member sizes into a data
 * descriptor <em>after</em> the payload, so the zip entry claims a length of -1
 * and a reader that trusts it returns nothing.
 */
public class NpzTest {

    @Test public void readsWhatNumpyWrote() throws Exception {
        Map<String, Npz.Array> arrays = Npz.read(fixture());

        Npz.Array features = arrays.get("features");
        assertNotNull("np.savez names its members", features);
        assertArrayEquals(new int[] {2, 5}, features.shape());
        assertEquals(0.125f, features.at(0, 1), 0);
        assertEquals(0.75f, features.at(1, 1), 0);

        // Three dimensions, flattened in C order: the shape the trainer saves
        // logits in, and the order the Java side reads them back in.
        Npz.Array logits = arrays.get("logits");
        assertArrayEquals(new int[] {2, 3, 2}, logits.shape());
        assertEquals(12, logits.values().length);
        assertEquals(-0.25f, logits.values()[1], 0);
        assertEquals(-2.75f, logits.values()[11], 0);
    }

    private static Path fixture() throws Exception {
        try (InputStream in = NpzTest.class.getResourceAsStream("npz-fixture.npz")) {
            assertNotNull("the .npz test resource is missing", in);
            Path copy = Files.createTempFile("npz-fixture", ".npz");
            copy.toFile().deleteOnExit();
            Files.write(copy, in.readAllBytes());
            return copy;
        }
    }
}
