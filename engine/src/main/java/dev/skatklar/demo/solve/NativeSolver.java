package dev.skatklar.demo.solve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The native double-dummy solver, and the decision whether to use it.
 *
 * <p>The search in {@link DoubleDummySolver} is the specification; the library
 * behind this class is a second implementation of it in C++, faster by roughly
 * three and a half times on the question the app and the server actually ask.
 * Most of that is not the language. A straight translation was measured at 1.4x;
 * the rest came from things the Java does not do — collapsing cards that are
 * interchangeable, killer-move ordering, and sizing the transposition table for
 * the question instead of growing into it.
 *
 * <p><b>Java remains the fallback and remains correct.</b> Every entry point
 * here can say "not me", and the caller then runs the Java search for that one
 * question. A platform with no library, a library that will not load, a library
 * built from different sources, a contract this engine refuses — all of them
 * end in the same place, with an answer, from Java. That is the whole reason
 * the Java code is kept rather than deleted.
 *
 * <p>Selection happens once, in a static initialiser, and never changes for the
 * life of the process. A solver that could flip between engines mid-match would
 * make a bug reproducible only for whoever saw it.
 *
 * <ul>
 *   <li>{@code -Dskatklar.solver=java} forces the Java search everywhere. The
 *       first thing to try when a result looks wrong.</li>
 *   <li>{@code -Dskatklar.solver.dir=<path>} says where to unpack the library
 *       when the temporary directory is not writable.</li>
 * </ul>
 */
final class NativeSolver {

    /**
     * What this build of the Java expects the library to call itself.
     *
     * <p>A stale {@code .so} left in a deployment directory, or one built from
     * a different revision, is the failure mode that costs a day: everything
     * loads, nothing crashes, and the answers are subtly those of an older
     * search. One string comparison at load turns that into a log line and a
     * fallback. Bump it here and in {@code native/CMakeLists.txt} together
     * whenever the search changes in a way a caller could notice.
     */
    private static final String EXPECTED_VERSION = "1";

    private static final String LIBRARY = "skatsolve";
    private static final String PROPERTY = "skatklar.solver";
    private static final String DIRECTORY_PROPERTY = "skatklar.solver.dir";

    private static final boolean AVAILABLE = load();

    private NativeSolver() {}

    static boolean available() { return AVAILABLE; }

    // ------------------------------------------------------------------ loading

    private static boolean load() {
        String requested = System.getProperty(PROPERTY, "").trim();
        if (requested.equalsIgnoreCase("java")) {
            report("The Java solver was requested with -D" + PROPERTY + "=java.");
            return false;
        }
        Throwable fromPath;
        try {
            System.loadLibrary(LIBRARY);
            return verify();
        } catch (Throwable notOnThePath) {
            fromPath = notOnThePath;
        }
        try {
            Path unpacked = unpack();
            if (unpacked == null) {
                report("No native solver for " + platform() + "; using the Java search."
                        + " (" + fromPath + ")");
                return false;
            }
            System.load(unpacked.toAbsolutePath().toString());
            return verify();
        } catch (Throwable unusable) {
            report("The native solver could not be loaded (" + unusable
                    + "); using the Java search.");
            return false;
        }
    }

    private static boolean verify() {
        try {
            String version = version();
            if (!EXPECTED_VERSION.equals(version)) {
                report("The native solver reports version " + version + " where this build"
                        + " expects " + EXPECTED_VERSION + "; using the Java search.");
                return false;
            }
            return true;
        } catch (Throwable wrongShape) {
            // A library that loaded but has no version symbol is not this
            // library. Anything built against a different header would fail
            // here rather than at the first search.
            report("The native solver did not answer a version query (" + wrongShape
                    + "); using the Java search.");
            return false;
        }
    }

    /**
     * Writes the library for this platform out of the jar, once.
     *
     * <p>The server ships as a single jar and is installed by copying that one
     * file, so the library has to travel inside it. Unpacking is idempotent:
     * the name carries the version, so a second process finds the file already
     * there and loads it, and an upgrade writes a different name rather than
     * fighting over the same one.
     *
     * @return where it was written, or null when the jar carries none for this
     *         platform
     */
    private static Path unpack() throws IOException {
        String platform = platform();
        String resource = "/dev/skatklar/native/" + platform + "/" + fileName();
        try (InputStream in = NativeSolver.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            Path directory = unpackDirectory();
            Path target = directory.resolve(
                    "skatsolve-" + EXPECTED_VERSION + "-" + platform + suffix());
            if (Files.isReadable(target)) return target;
            Path partial = Files.createTempFile(directory, "skatsolve", suffix());
            try (OutputStream out = Files.newOutputStream(partial)) {
                in.transferTo(out);
            }
            try {
                // Two processes starting together both write, and one of them
                // wins the rename. Both then load the same finished file rather
                // than one of them loading a half-written one.
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException raced) {
                Files.deleteIfExists(partial);
                if (!Files.isReadable(target)) throw raced;
            }
            return target;
        }
    }

    private static Path unpackDirectory() throws IOException {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path directory = Path.of(configured.trim());
            Files.createDirectories(directory);
            return directory;
        }
        // The unit's PrivateTmp gives the server its own /tmp, which is where
        // this lands and where it is cleaned up when the core exits.
        return Path.of(System.getProperty("java.io.tmpdir", "."));
    }

    /** The platform key the jar's resource directories are named after. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String family = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macos"
                : "linux";
        String cpu = switch (arch) {
            case "amd64", "x86_64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm", "armv7l" -> "arm";
            default -> arch;
        };
        return family + "-" + cpu;
    }

    private static String suffix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return ".dll";
        if (os.contains("mac") || os.contains("darwin")) return ".dylib";
        return ".so";
    }

    private static String fileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? LIBRARY + ".dll" : "lib" + LIBRARY + suffix();
    }

    /**
     * Says once what engine this process is using, and never again.
     *
     * <p>Once, because the alternative is a line per solve. On the server this
     * lands in the journal beside the core's startup line, which is where an
     * operator wondering why a host is suddenly slower will look.
     */
    private static void report(String message) {
        System.out.println("SkatKlar solver: " + message);
    }

    // ------------------------------------------------------------------ the API

    /** Status codes the native side returns. Negative means "ask Java". */
    static final int OK = 0;
    static final int UNSUPPORTED = -1;
    static final int EXPIRED = -2;

    static native String version();

    static native int solve(int contract, int declarer, int hand0, int hand1, int hand2,
                            int leader, long[] out);

    static native int reaches(int contract, int declarer, int hand0, int hand1, int hand2,
                              int toPlay, int leader, int trickCards, int trickSize,
                              int target);

    static native int reachesWithin(int contract, int declarer, int hand0, int hand1,
                                    int hand2, int toPlay, int leader, int trickCards,
                                    int trickSize, int target, long budgetNanos);

    static native int bestCard(int contract, int declarer, int toPlay, int hand0, int hand1,
                               int hand2, int leader, int trickCards, int trickSize,
                               long[] out);

    static native int bestCardForResult(int contract, int declarer, int toPlay, int hand0,
                                        int hand1, int hand2, int leader, int trickCards,
                                        int trickSize, int banked, long[] out);

    static native int movesReaching(int contract, int declarer, int toPlay, int hand0,
                                    int hand1, int hand2, int leader, int trickCards,
                                    int trickSize, int target, int[] outCards,
                                    int[] outBounds);

    /** Plain minimax on the native side. Exists so the tests can compare like with like. */
    static native int brute(int contract, int declarer, int hand0, int hand1, int hand2,
                            int leader);

    static native long createSolver(int contract, int declarer);

    static native void destroySolver(long handle);

    static native int solverReaches(long handle, int hand0, int hand1, int hand2, int toPlay,
                                    int leader, int trickCards, int trickSize, int target);

    static native void setTranspositions(long handle, boolean enabled);
}
