package dev.skatklar.training.belief;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Just enough of NumPy's {@code .npz} to read the parity fixtures.
 *
 * <p>An {@code .npz} is a zip of {@code .npy} members, and an {@code .npy} is a
 * short ASCII header followed by raw little-endian data. Reading that needs no
 * library, and the alternative — shipping a JSON dump of the same numbers beside
 * the real one — would be a second format that can drift from the arrays the
 * trainer actually saw.
 *
 * <p>Deliberately narrow: little-endian {@code float32}, C order, two dimensions
 * at most. Anything else throws, because a silently misread fixture is worse than
 * no fixture — it would pass a parity check the model does not deserve.
 */
public final class Npz {

    private Npz() {}

    /** One array: its shape and its values, flattened in C order. */
    public record Array(int[] shape, float[] values) {
        public int rows() { return shape.length > 0 ? shape[0] : 1; }
        public int columns() { return shape.length > 1 ? shape[1] : 1; }
        public float at(int row, int column) { return values[row * columns() + column]; }
    }

    /** Every member of the archive, by name without the {@code .npy} suffix. */
    public static Map<String, Array> read(Path file) throws IOException {
        Map<String, Array> arrays = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!name.endsWith(".npy")) continue;
                arrays.put(name.substring(0, name.length() - 4), readNpy(zip));
            }
        }
        return arrays;
    }

    /**
     * One {@code .npy} member from an already-positioned stream.
     *
     * <p>The entry size is not consulted: a zip written by NumPy stores it in the
     * data descriptor after the payload, so {@code ZipEntry.getSize()} is -1 here
     * and the length has to come from the header instead. Getting that wrong is
     * how a reader ends up returning half an array and no error.
     */
    private static Array readNpy(InputStream in) throws IOException {
        byte[] magic = in.readNBytes(8);
        if (magic.length != 8 || magic[0] != (byte) 0x93
                || magic[1] != 'N' || magic[2] != 'U' || magic[3] != 'M') {
            throw new IOException("not a .npy member");
        }
        int major = magic[6] & 0xFF;
        int headerLength;
        if (major == 1) {
            byte[] two = in.readNBytes(2);
            headerLength = (two[0] & 0xFF) | ((two[1] & 0xFF) << 8);
        } else {
            byte[] four = in.readNBytes(4);
            headerLength = (four[0] & 0xFF) | ((four[1] & 0xFF) << 8)
                    | ((four[2] & 0xFF) << 16) | ((four[3] & 0xFF) << 24);
        }
        String header = new String(in.readNBytes(headerLength), java.nio.charset.StandardCharsets.US_ASCII);
        if (!header.contains("'<f4'") && !header.contains("\"<f4\"")) {
            throw new IOException("fixtures must be little-endian float32: " + header);
        }
        if (header.replace(" ", "").contains("'fortran_order':True")) {
            throw new IOException("fixtures must be in C order: " + header);
        }

        int[] shape = shapeOf(header);
        int total = 1;
        for (int extent : shape) total *= extent;
        byte[] raw = in.readNBytes(total * 4);
        if (raw.length != total * 4) throw new IOException("fixture truncated");
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[total];
        for (int at = 0; at < total; at++) values[at] = buffer.getFloat();
        return new Array(shape, values);
    }

    private static int[] shapeOf(String header) throws IOException {
        int open = header.indexOf("'shape'");
        if (open < 0) open = header.indexOf("\"shape\"");
        if (open < 0) throw new IOException("no shape in header: " + header);
        int from = header.indexOf('(', open);
        int to = header.indexOf(')', from);
        if (from < 0 || to < 0) throw new IOException("no shape in header: " + header);
        String inside = header.substring(from + 1, to).trim();
        if (inside.isEmpty()) return new int[0];
        String[] parts = inside.split(",");
        int count = 0;
        int[] shape = new int[parts.length];
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            shape[count++] = Integer.parseInt(trimmed);
        }
        int[] exact = new int[count];
        System.arraycopy(shape, 0, exact, 0, count);
        return exact;
    }
}
