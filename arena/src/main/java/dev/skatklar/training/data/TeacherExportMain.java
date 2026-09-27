package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The teacher's decisions, written down for a student to imitate (docs/training-plan.md 2.10, I1).
 *
 * <p>The teacher -- {@code belief-64-shipped} by default, the player T1 picked
 * -- sits in all three seats and plays whole games, auction included, so the
 * positions are the ones its own bidding leads to. At every card decision
 * with more than one legal card it searches, and this records, through the
 * player's own observer and nothing else:
 *
 * <ul>
 *   <li>the position as the seat saw it: the belief encoding of the evidence
 *       the player itself built for the decision, so a student reads exactly
 *       what the teacher read and nothing more;</li>
 *   <li>the legal cards, the card played, and the primary vote per card with
 *       the number of worlds -- the teacher's judgement as a distribution, which
 *       is what a student learns best from;</li>
 *   <li>the cushion tally where it was asked;</li>
 *   <li>the vote world by world for the first 64 worlds, one bit a world per
 *       legal card: the vote of a 4-, 8-, 16- or 32-world player is the count of
 *       the first 4, 8, 16 or 32 bits, so weaker levels can be trained from the
 *       same games without playing one more.</li>
 * </ul>
 *
 * <p>Watching does not move a card: the observer is told after the tally, and
 * {@code TeacherExportTest} holds the recorded games to the unwatched ones.
 *
 * <p>Flat binary, little-endian, one record {@link #RECORD_BYTES} long; see
 * {@link #layout()} and {@code teacher-format.json}, written beside the shards.
 *
 * <pre>./gradlew :arena:exportTeacher --args="--boards=50000 --seed=101 --threads=16 --out=teacher-data"</pre>
 */
public final class TeacherExportMain {

    private TeacherExportMain() {}

    /** Legal cards a record has room for: a hand never holds more than ten. */
    static final int MAX_LEGAL = 10;

    /** Byte offsets of the record's fields, in order. */
    static final int FEATURES = 0;
    static final int LEGAL_MASK = FEATURES + BeliefEncoding.SIZE;     // int32, bit per card index
    static final int CHOSEN = LEGAL_MASK + 4;                         // card index
    static final int WORLDS = CHOSEN + 1;                             // worlds tallied, capped at 255
    static final int VERDICT_WORLDS = WORLDS + 1;                     // worlds with a verdict bit, <= 64
    static final int CUSHION_ASKED = VERDICT_WORLDS + 1;              // 0 or 1
    static final int CONTRACT = CUSHION_ASKED + 1;                    // Contract ordinal
    static final int DECLARER = CONTRACT + 1;                         // 1 if the seat declares
    static final int TRICK = DECLARER + 1;                            // 1..10
    static final int VOTES = TRICK + 1;                               // 32 bytes, by card index
    static final int CUSHION = VOTES + 32;                            // 32 bytes, by card index
    static final int VERDICTS = CUSHION + 32;                         // MAX_LEGAL int64s, legal cards by index
    static final int DEAL = VERDICTS + 8 * MAX_LEGAL;                 // int32, the deal's identity
    static final int RECORD_BYTES = DEAL + 4;

    static String layout() {
        return "{\n  \"version\": 1,\n  \"record_bytes\": " + RECORD_BYTES
                + ",\n  \"encoding_version\": " + BeliefEncoding.VERSION
                + ",\n  \"fields\": {\n"
                + "    \"features\": [" + FEATURES + ", " + BeliefEncoding.SIZE + ", \"uint8, divide by " + BeliefEncoding.SCALE + "\"],\n"
                + "    \"legal_mask\": [" + LEGAL_MASK + ", 4, \"uint32 LE, bit i = card index i\"],\n"
                + "    \"chosen\": [" + CHOSEN + ", 1, \"card index\"],\n"
                + "    \"worlds\": [" + WORLDS + ", 1, \"worlds the vote was tallied over, capped at 255\"],\n"
                + "    \"verdict_worlds\": [" + VERDICT_WORLDS + ", 1, \"worlds with a verdict bit, at most 64\"],\n"
                + "    \"cushion_asked\": [" + CUSHION_ASKED + ", 1, \"0/1\"],\n"
                + "    \"contract\": [" + CONTRACT + ", 1, \"Contract ordinal\"],\n"
                + "    \"declarer\": [" + DECLARER + ", 1, \"1 if the deciding seat declares\"],\n"
                + "    \"trick\": [" + TRICK + ", 1, \"1..10\"],\n"
                + "    \"votes\": [" + VOTES + ", 32, \"worlds each card held, by card index\"],\n"
                + "    \"cushion\": [" + CUSHION + ", 32, \"the cushion tally, by card index, when asked\"],\n"
                + "    \"verdicts\": [" + VERDICTS + ", " + (8 * MAX_LEGAL) + ", \"uint64 LE per legal card in ascending card index; bit w = held in world w\"],\n"
                + "    \"deal\": [" + DEAL + ", 4, \"int32 LE, the deal's identity (seed and board mixed), for the validation split\"]\n"
                + "  }\n}\n";
    }

    /** Where records go. */
    interface Sink {
        void accept(ByteBuffer record);
    }

    /** A shard on disk; thread-safe. */
    static final class ShardWriter implements Sink, AutoCloseable {
        private final OutputStream out;
        private long written;

        ShardWriter(Path path) throws IOException {
            out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
        }

        @Override public synchronized void accept(ByteBuffer record) {
            try {
                out.write(record.array(), 0, RECORD_BYTES);
                written++;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        long records() { return written; }

        @Override public void close() throws IOException { out.close(); }
    }

    /**
     * One seat's observer. The three callbacks of a decision arrive on the
     * deciding thread in order -- sampled, worldVerdicts, voted -- and nothing
     * else runs on that seat between them, so the pieces are held in fields
     * and written when the vote arrives.
     */
    static final class Recorder implements SearchAiProvider.CardPlayObserver {
        private final Sink sink;
        private final int deal;
        private BeliefEncoding.Evidence evidence;
        private List<Card> legal;
        private long[] holds;
        private int verdictWorlds;

        Recorder(Sink sink, int deal) { this.sink = sink; this.deal = deal; }

        @Override public void decided(SearchAiProvider.CardPlayReport report) {}

        @Override public void sampled(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                                      List<WorldSampler.World> worlds) {
            evidence = seen;
            legal = null;
            holds = null;
            verdictWorlds = 0;
        }

        @Override public boolean wantsWorldVerdicts() { return true; }

        @Override public void worldVerdicts(SkatAi.DecisionContext context, List<Card> legalCards,
                                            long[] bits, int worlds) {
            legal = new ArrayList<>(legalCards);
            holds = bits.clone();
            verdictWorlds = worlds;
        }

        @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                    Map<Card, Integer> cushion, int worlds, boolean cushionAsked,
                                    Card chosen) {
            if (evidence == null || evidence.context() != context || legal == null) {
                // A decision this recorder did not see from the start (alpha-mu
                // scores, or no worlds): nothing consistent to write.
                evidence = null;
                return;
            }
            sink.accept(encode(evidence, legal, holds, verdictWorlds, votes, cushion, worlds,
                    cushionAsked, chosen, deal));
            evidence = null;
        }
    }

    /** One record. Package-private for the test, which reads it back field by field. */
    static ByteBuffer encode(BeliefEncoding.Evidence evidence, List<Card> legal, long[] holds,
                             int verdictWorlds, Map<Card, Integer> votes, Map<Card, Integer> cushion,
                             int worlds, boolean cushionAsked, Card chosen, int deal) {
        SkatAi.DecisionContext context = evidence.context();
        ByteBuffer out = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put(FEATURES, BeliefEncoding.quantise(BeliefEncoding.encode(evidence)));
        int mask = 0;
        for (Card card : legal) mask |= 1 << BeliefEncoding.index(card);
        out.putInt(LEGAL_MASK, mask);
        out.put(CHOSEN, (byte) BeliefEncoding.index(chosen));
        out.put(WORLDS, (byte) Math.min(255, worlds));
        out.put(VERDICT_WORLDS, (byte) verdictWorlds);
        out.put(CUSHION_ASKED, (byte) (cushionAsked ? 1 : 0));
        out.put(CONTRACT, (byte) context.game.contract.ordinal());
        out.put(DECLARER, (byte) (context.mySeat == context.game.declarer ? 1 : 0));
        out.put(TRICK, (byte) (context.history.completedTricks.size() + 1));
        for (Map.Entry<Card, Integer> vote : votes.entrySet()) {
            out.put(VOTES + BeliefEncoding.index(vote.getKey()), (byte) Math.min(255, vote.getValue()));
        }
        if (cushionAsked) {
            for (Map.Entry<Card, Integer> held : cushion.entrySet()) {
                out.put(CUSHION + BeliefEncoding.index(held.getKey()), (byte) Math.min(255, held.getValue()));
            }
        }
        // The verdicts in ascending card index, so a reader pairs them with the
        // set bits of the legal mask in order.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < legal.size(); i++) order.add(i);
        order.sort((x, y) -> Integer.compare(BeliefEncoding.index(legal.get(x)), BeliefEncoding.index(legal.get(y))));
        for (int k = 0; k < Math.min(MAX_LEGAL, order.size()); k++) {
            out.putLong(VERDICTS + 8 * k, holds[order.get(k)]);
        }
        out.putInt(DEAL, deal);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        int boards = Integer.parseInt(options.getOrDefault("boards", "1000"));
        long seed = Long.parseLong(options.getOrDefault("seed", "101"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        int shardSize = Integer.parseInt(options.getOrDefault("shard", "1000"));
        int first = Integer.parseInt(options.getOrDefault("first", "0"));
        boolean voidPassedIn = "void".equals(options.getOrDefault("passed-in", "void"));
        Path out = Path.of(options.getOrDefault("out", "teacher-data")).toAbsolutePath();
        System.out.println(dev.skatklar.training.arena.Rules.apply(options));
        Contestant teacher = PlayerRegistry.withDefaults().resolve(options.getOrDefault("teacher", "belief-64-shipped"));

        Files.createDirectories(out);
        Files.writeString(out.resolve("teacher-format.json"), layout());
        Files.writeString(out.resolve("encoding-v" + BeliefEncoding.VERSION + ".json"),
                BeliefEncoding.specificationJson());
        Files.writeString(out.resolve("teacher.txt"), teacher.id() + "\n");
        System.out.printf(Locale.ROOT, "Teacher export: %s in all seats, boards %d-%d, seed %d, %d thread(s), "
                        + "%d bytes a record, passed-in boards %s, into %s%n", teacher.id(), first, first + boards - 1,
                seed, threads, RECORD_BYTES, voidPassedIn ? "void" : "Ramsch", out);

        long started = System.nanoTime();
        long total = 0;
        for (int from = first; from < first + boards; from += shardSize) {
            int to = Math.min(first + boards, from + shardSize);
            Path shard = out.resolve(String.format(Locale.ROOT, "teacher-s%d-%06d.bin", seed, from));
            if (Files.exists(shard)) {
                // Written a shard at a time and skipped when present, so a run
                // stopped overnight carries on where it was.
                System.out.printf(Locale.ROOT, "  %s exists, skipped%n", shard.getFileName());
                continue;
            }
            Path partial = out.resolve(shard.getFileName() + ".partial");
            try (ShardWriter writer = new ShardWriter(partial)) {
                play(teacher, from, to, seed, threads, writer, voidPassedIn);
                total += writer.records();
                System.out.printf(Locale.ROOT, "  %s: %,d records (%,d boards), %.0f s so far%n",
                        shard.getFileName(), writer.records(), to - from, (System.nanoTime() - started) / 1e9);
            }
            Files.move(partial, shard);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(Locale.ROOT, "%n%,d records in %.0f s (%,.1f a second)%n", total, seconds, total / seconds);
    }

    static void play(Contestant teacher, int from, int to, long seed, int threads, Sink sink,
                     boolean voidPassedIn) throws Exception {
        List<Callable<Void>> work = new ArrayList<>();
        for (int index = from; index < to; index++) {
            final Board board = Board.of(seed, index);
            work.add(() -> {
                playBoard(teacher, board, seed, sink, voidPassedIn);
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            for (Future<Void> f : pool.invokeAll(work)) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** One board, the teacher in all seats, auction and all, every decision recorded. */
    static dev.skatklar.training.arena.GameOutcome playBoard(Contestant teacher, Board board, long seed,
                                                            Sink sink, boolean voidPassedIn) {
        int deal = (int) Seeds.mix(seed, board.index(), 0xDEA1);
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            SkatAiProvider provider = teacher.newProvider(Seeds.mix(seed, board.index(), seat.ordinal()));
            if (provider instanceof SearchAiProvider search) {
                provider = search.withCardPlayObserver(new Recorder(sink, deal));
            }
            seating.put(seat, provider);
        }
        return GameRunner.play(board, seating, Seeds.mix(seed, board.index(), 0xE1E1E1L), voidPassedIn);
    }
}
