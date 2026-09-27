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
 * -- plays whole games, auction included. It does not play only itself: a
 * student that has only seen positions strong players make meets different
 * ones at the app's table, where two AI seats sit with a person. So every
 * board draws how many seats the teacher takes -- one, two or three, weighted
 * by {@code --mix}, two most often as at the app's table -- and fills the rest
 * from a population of the arena's own players, the belief corpus's mix
 * ({@code --population}). Only the teacher's seats are recorded; who sat where
 * goes into each record as a diagnostic, never an input. At every card
 * decision of a teacher seat with more than one legal card it searches, and
 * this records, through the player's own observer and nothing else:
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
    static final int SEATING = DEAL + 4;                              // players at me, left, right; 0 = teacher
    static final int TEACHER_SEATS = SEATING + 3;                     // how many seats the teacher had, 1..3
    static final int RECORD_BYTES = TEACHER_SEATS + 1;
    /** The record layout's version, in the shard names so an old shard is never resumed into a new corpus. */
    static final int FORMAT = 2;

    static String layout() {
        return "{\n  \"version\": " + FORMAT + ",\n  \"record_bytes\": " + RECORD_BYTES
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
                + "    \"deal\": [" + DEAL + ", 4, \"int32 LE, the deal's identity (seed and board mixed), for the validation split\"],\n"
                + "    \"seating\": [" + SEATING + ", 3, \"player index (players.json; 0 = the teacher) at the deciding seat, its left, its right -- a diagnostic, never an input\"],\n"
                + "    \"teacher_seats\": [" + TEACHER_SEATS + ", 1, \"seats the teacher held at this board, 1..3\"]\n"
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
        private final byte[] seating;
        private BeliefEncoding.Evidence evidence;
        private List<Card> legal;
        private long[] holds;
        private int verdictWorlds;

        /** @param seating the four seating bytes of this seat's records: me, left, right, teacher seats */
        Recorder(Sink sink, int deal, byte[] seating) {
            this.sink = sink;
            this.deal = deal;
            this.seating = seating.clone();
        }

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
            if (!consistent(legal, holds, votes, worlds)) {
                // The world-by-world verdicts do not add up to the vote. Never
                // seen in 8,000 records under stress, seen once in a test run and
                // not again: counted and left out rather than written wrong.
                INCONSISTENT.incrementAndGet();
                evidence = null;
                return;
            }
            sink.accept(encode(evidence, legal, holds, verdictWorlds, votes, cushion, worlds,
                    cushionAsked, chosen, deal, seating));
            evidence = null;
        }
    }

    /** Decisions left out because their verdicts did not add up to their votes. */
    static final java.util.concurrent.atomic.AtomicLong INCONSISTENT = new java.util.concurrent.atomic.AtomicLong();

    /** Whether each legal card's verdict bits count its vote, where every world has a bit (64 worlds or fewer). */
    static boolean consistent(List<Card> legal, long[] holds, Map<Card, Integer> votes, int worlds) {
        if (worlds > 64) return true;
        for (int i = 0; i < legal.size(); i++) {
            if (Long.bitCount(holds[i]) != votes.getOrDefault(legal.get(i), 0)) return false;
        }
        return true;
    }

    /** One record. Package-private for the test, which reads it back field by field. */
    static ByteBuffer encode(BeliefEncoding.Evidence evidence, List<Card> legal, long[] holds,
                             int verdictWorlds, Map<Card, Integer> votes, Map<Card, Integer> cushion,
                             int worlds, boolean cushionAsked, Card chosen, int deal, byte[] seating) {
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
        out.put(SEATING, seating[0]).put(SEATING + 1, seating[1]).put(SEATING + 2, seating[2]);
        out.put(TEACHER_SEATS, seating[3]);
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
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant teacher = registry.resolve(options.getOrDefault("teacher", "belief-64-shipped"));
        List<Contestant> population = new ArrayList<>();
        for (String id : options.getOrDefault("population", "greedy,search-4,club,expert,jskat-new").split(",")) {
            if (id.isBlank()) continue;
            try {
                population.add(registry.resolve(id.trim()));
            } catch (IllegalArgumentException absent) {
                System.out.println("skipping unavailable player " + id.trim());
            }
        }
        int[] mix = parseMix(options.getOrDefault("mix", "25,50,25"));

        Files.createDirectories(out);
        Files.writeString(out.resolve("teacher-format.json"), layout());
        Files.writeString(out.resolve("encoding-v" + BeliefEncoding.VERSION + ".json"),
                BeliefEncoding.specificationJson());
        Files.writeString(out.resolve("teacher.txt"), teacher.id() + "\n");
        StringBuilder players = new StringBuilder("[\"" + teacher.id() + "\"");
        for (Contestant c : population) players.append(", \"").append(c.id()).append('"');
        Files.writeString(out.resolve("players.json"), players.append("]\n").toString());
        System.out.printf(Locale.ROOT, "Teacher export: %s, boards %d-%d, seed %d, %d thread(s), %d bytes a record, "
                        + "passed-in boards %s, into %s%n", teacher.id(), first, first + boards - 1,
                seed, threads, RECORD_BYTES, voidPassedIn ? "void" : "Ramsch", out);
        System.out.printf(Locale.ROOT, "  the teacher takes 1, 2 or 3 seats in the proportion %d : %d : %d; the other seats from %s%n",
                mix[0], mix[1], mix[2], population.isEmpty() ? "nobody (all teacher)" : population);

        long started = System.nanoTime();
        long total = 0;
        for (int from = first; from < first + boards; from += shardSize) {
            int to = Math.min(first + boards, from + shardSize);
            Path shard = out.resolve(String.format(Locale.ROOT, "teacher-v%d-s%d-%06d.bin", FORMAT, seed, from));
            if (Files.exists(shard)) {
                // Written a shard at a time and skipped when present, so a run
                // stopped overnight carries on where it was.
                System.out.printf(Locale.ROOT, "  %s exists, skipped%n", shard.getFileName());
                continue;
            }
            Path partial = out.resolve(shard.getFileName() + ".partial");
            try (ShardWriter writer = new ShardWriter(partial)) {
                play(teacher, population, mix, from, to, seed, threads, writer, voidPassedIn);
                total += writer.records();
                System.out.printf(Locale.ROOT, "  %s: %,d records (%,d boards), %.0f s so far%s%n",
                        shard.getFileName(), writer.records(), to - from, (System.nanoTime() - started) / 1e9,
                        INCONSISTENT.get() > 0 ? String.format(Locale.ROOT,
                                ", %d decision(s) left out so far: verdicts did not add up to the vote",
                                INCONSISTENT.get()) : "");
            }
            Files.move(partial, shard);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(Locale.ROOT, "%n%,d records in %.0f s (%,.1f a second)%n", total, seconds, total / seconds);
    }

    static int[] parseMix(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) throw new IllegalArgumentException("--mix takes three weights, for 1, 2 and 3 teacher seats");
        int[] mix = new int[3];
        for (int i = 0; i < 3; i++) mix[i] = Integer.parseInt(parts[i].trim());
        if (mix[0] + mix[1] + mix[2] <= 0) throw new IllegalArgumentException("--mix weights must add up to more than zero");
        return mix;
    }

    /**
     * Who sits where on a board, by seat ordinal: 0 for the teacher, i for the
     * population's player i-1. Drawn from the board and the seed alone, so a
     * shard written again is the same shard. With nobody in the population the
     * teacher takes every seat.
     */
    static int[] seatPlayers(Board board, long seed, int populationSize, int[] mix) {
        int[] players = new int[3];
        if (populationSize == 0) return players;
        java.util.Random random = new java.util.Random(Seeds.mix(seed, board.index(), 0x5EA7L));
        int roll = random.nextInt(mix[0] + mix[1] + mix[2]);
        int teacherSeats = roll < mix[0] ? 1 : roll < mix[0] + mix[1] ? 2 : 3;
        List<Integer> seats = new ArrayList<>(List.of(0, 1, 2));
        java.util.Collections.shuffle(seats, random);
        for (int k = teacherSeats; k < 3; k++) players[seats.get(k)] = 1 + random.nextInt(populationSize);
        return players;
    }

    static void play(Contestant teacher, List<Contestant> population, int[] mix, int from, int to, long seed,
                     int threads, Sink sink, boolean voidPassedIn) throws Exception {
        List<Callable<Void>> work = new ArrayList<>();
        for (int index = from; index < to; index++) {
            final Board board = Board.of(seed, index);
            work.add(() -> {
                playBoard(teacher, population, mix, board, seed, sink, voidPassedIn);
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

    /** One board, auction and all, the teacher's decisions recorded. */
    static dev.skatklar.training.arena.GameOutcome playBoard(Contestant teacher, List<Contestant> population,
                                                            int[] mix, Board board, long seed,
                                                            Sink sink, boolean voidPassedIn) {
        int deal = (int) Seeds.mix(seed, board.index(), 0xDEA1);
        int[] players = seatPlayers(board, seed, population.size(), mix);
        int teacherSeats = 0;
        for (int p : players) if (p == 0) teacherSeats++;
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            int who = players[seat.ordinal()];
            Contestant contestant = who == 0 ? teacher : population.get(who - 1);
            SkatAiProvider provider = contestant.newProvider(Seeds.mix(seed, board.index(), seat.ordinal()));
            if (who == 0 && provider instanceof SearchAiProvider search) {
                byte[] seatingBytes = {
                        (byte) players[seat.ordinal()],
                        (byte) players[seat.next().ordinal()],
                        (byte) players[seat.next().next().ordinal()],
                        (byte) teacherSeats};
                provider = search.withCardPlayObserver(new Recorder(sink, deal, seatingBytes));
            }
            seating.put(seat, provider);
        }
        return GameRunner.play(board, seating, Seeds.mix(seed, board.index(), 0xE1E1E1L), voidPassedIn);
    }
}
