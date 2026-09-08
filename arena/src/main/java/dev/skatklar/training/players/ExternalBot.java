package dev.skatklar.training.players;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One helper process playing one seat, spoken to a line at a time.
 *
 * <p>The two outside engines the arena can seat -- XSkat and go-skat -- are
 * whole programs rather than libraries, and both keep their game state in
 * process-wide globals. That decides the shape here: a process per seat per
 * deal, never shared, never threaded. It is also why this is not JNI. XSkat's
 * state is a file-scope C array; two arena threads in one address space would
 * quietly play each other's cards.
 *
 * <p>Processes are pooled rather than spawned per deal. A duplicate match is
 * six games a board and three seats a game, so a 200-board run would otherwise
 * fork 3,600 times for a helper that answers in microseconds.
 *
 * <p>The protocol is in {@code docs/external-bots.md}.
 */
final class ExternalBot implements AutoCloseable {

    /** Long enough that a real hang is still caught, short enough to not stall a match. */
    private static final long REPLY_TIMEOUT_MS = 30_000;

    private static final Map<String, Deque<ExternalBot>> POOLS = new HashMap<>();

    private final List<String> command;
    private final Process process;
    private final BufferedWriter out;
    private final BufferedReader in;
    private long sequence;

    private static volatile long desynchronised;

    private ExternalBot(List<String> command) throws IOException {
        this.command = command;
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        this.process = builder.start();
        this.out = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.US_ASCII));
        this.in = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.US_ASCII));
    }

    /** A pooled helper, started if the pool is empty. */
    static ExternalBot borrow(List<String> command) {
        String key = String.join(" ", command);
        synchronized (POOLS) {
            Deque<ExternalBot> pool = POOLS.get(key);
            while (pool != null && !pool.isEmpty()) {
                ExternalBot bot = pool.pop();
                if (bot.process.isAlive()) return bot;
            }
        }
        try {
            return new ExternalBot(command);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot start helper " + command, failure);
        }
    }

    void release() {
        if (!process.isAlive()) return;
        synchronized (POOLS) {
            POOLS.computeIfAbsent(String.join(" ", command), ignored -> new ArrayDeque<>())
                    .push(this);
        }
    }

    /**
     * Sends one request and returns the reply to it.
     *
     * <p>Every request carries a sequence number the helper repeats, and a reply
     * that does not match is skipped and counted. A pipe one reply out of step
     * is otherwise invisible: the helper keeps answering, the answers are just
     * all one command late, and the only symptom is a player that measures
     * weaker than it is. It happened once during this adapter's development,
     * which is why it is checked rather than assumed.
     */
    String ask(String request) {
        try {
            long id = ++sequence;
            out.write(Long.toString(id));
            out.write(' ');
            out.write(request);
            out.write('\n');
            out.flush();
            for (;;) {
                String reply = readLine();
                if (reply == null) {
                    throw new IllegalStateException(
                            command + " closed its output after: " + request);
                }
                int space = reply.indexOf(' ');
                String tag = space < 0 ? reply : reply.substring(0, space);
                if (tag.equals(Long.toString(id))) {
                    return space < 0 ? "" : reply.substring(space + 1);
                }
                desynchronised++;
                if (desynchronised > 64) {
                    throw new IllegalStateException(command + " is out of step: expected "
                            + id + ", got '" + reply + "'");
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Talking to " + command, failure);
        }
    }

    /** Replies skipped because they answered an earlier request. Should stay zero. */
    static long desynchronisedReplies() { return desynchronised; }

    private String readLine() throws IOException {
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        while (!in.ready()) {
            if (!process.isAlive()) return in.readLine();
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(command + " did not answer within "
                        + REPLY_TIMEOUT_MS + " ms");
            }
            try {
                Thread.sleep(0, 200_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for " + command);
            }
        }
        return in.readLine();
    }

    @Override public void close() {
        try {
            out.write((++sequence) + " QUIT\n");
            out.flush();
        } catch (IOException ignored) {
            // Already gone; destroy below is the answer either way.
        }
        process.destroy();
    }

    /**
     * Where a helper binary is, or null when this checkout has not built it.
     *
     * <p>Absence is a supported state, exactly as an unfetched JSkat submodule
     * is: the contestant is not registered and the arena runs without it.
     */
    static String locate(String envVar, String... relativePaths) {
        String named = System.getenv(envVar);
        if (named == null) named = System.getProperty(envVar.toLowerCase().replace('_', '.'));
        if (named != null && Files.isExecutable(Path.of(named))) return named;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        for (String relative : relativePaths) {
            for (String suffix : windows ? new String[] {".exe", ""} : new String[] {"", ".exe"}) {
                Path candidate = Path.of(relative + suffix).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }
}
