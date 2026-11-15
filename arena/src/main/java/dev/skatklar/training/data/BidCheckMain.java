package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.solve.ContractTables;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Scoring;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.demo.belief.PolicyNet;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The bid labels checked at the table (arena/python/bid_check.py writes the
 * tasks and reads the result; tools/bid-check.sh runs the three).
 *
 * <p>A task is one seat of a bid-labels board at one bid: the game and discard
 * the learned declarer chose there, and what the student's play-outs made of
 * it. This plays the same game -- the seat declaring at that bid, the other
 * two having passed, the same two cards buried -- k times with a table player
 * (--player, belief-64-shipped: the card play of every player at the bidding
 * table test) in all three seats, and writes beside each task the game value,
 * the win, the declarer's card points and the declarer's tournament points of
 * every play-out. The boards are rebuilt from the labels' seed, and each one's
 * ten and skat checked against the task.
 *
 * <p>Task lines, space-separated: kind, board, place (0 forehand, 1
 * middlehand, 2 rearhand), the seat's ten and the board's skat as card indices
 * joined by commas, the bid, the contract, the discard, then whatever the
 * Python side wants back (passed through). Output: the task line, then a tab
 * and four comma lists -- values, wins, points, tournament points.
 *
 * <p>--declarer and --defence seat another player, or the student, in that
 * role alone (bid_check.py compare sets the runs side by side): which side of
 * the student's play-outs the labels' optimism comes from. --stride=N plays
 * every N-th task.
 *
 * <pre>
 * ./gradlew :arena:bidCheck --args="--tasks=t.txt --out=o.txt --seed=9201"
 * </pre>
 */
public final class BidCheckMain {

    private BidCheckMain() {}

    record Task(String line, String kind, long board, int place, List<Card> ten, List<Card> skat, int bid,
                Contract contract, List<Card> discard) {}

    static Task parse(String line) {
        String[] f = line.strip().split(" ");
        if (f.length < 8) throw new IllegalArgumentException("a task of " + f.length + " fields: " + line);
        return new Task(line.strip(), f[0], Long.parseLong(f[1]), Integer.parseInt(f[2]), cards(f[3]), cards(f[4]),
                Integer.parseInt(f[5]), Contract.valueOf(f[6]), cards(f[7]));
    }

    static List<Card> cards(String joined) {
        List<Card> out = new ArrayList<>();
        for (String s : joined.split(",")) out.add(ContractTables.card(Integer.parseInt(s)));
        return out;
    }

    private static List<Integer> sorted(List<Card> cards) {
        List<Integer> out = new ArrayList<>();
        for (Card c : cards) out.add(BeliefEncoding.index(c));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * Who sits where: the declarer and the two defenders, each a table player
     * or the student ("student": the labels' student, the declarer by its top
     * card and a defender drawn at temperature 1; "student-greedy": a defender
     * by its top card too).
     */
    record Seats(Contestant declarer, Contestant defence, PolicyNet student, double defenceTemperature) {
        static Seats of(Contestant player) {
            return new Seats(player, player, null, 1.0);
        }

        boolean studentDeclares() { return declarer == null; }
        boolean studentDefends() { return defence == null; }
    }

    /** "student", "student-greedy" or a registered player. */
    static Seats seats(String declarerId, String defenceId, PolicyNet student, PlayerRegistry registry) {
        Contestant declarer = isStudent(declarerId) ? null : registry.resolve(declarerId);
        Contestant defence = isStudent(defenceId) ? null : registry.resolve(defenceId);
        if ((declarer == null || defence == null) && student == null) {
            throw new IllegalArgumentException("a student seat needs --model");
        }
        double temperature = "student-greedy".equals(defenceId) ? 0.0 : 1.0;
        return new Seats(declarer, defence, student, temperature);
    }

    static boolean isStudent(String id) {
        return "student".equals(id) || "student-greedy".equals(id);
    }

    /** The k play-outs of one task: values, wins, points, tournament points. */
    static int[][] play(Contestant player, Task task, long seed, int k) {
        return play(Seats.of(player), task, seed, k);
    }

    static int[][] play(Seats seats, Task task, long seed, int k) {
        Board board = Board.of(seed, task.board());
        SkatAi.RoundPosition round = board.round();
        SkatAi.Seat declarer = BidLabelMain.seatAt(round, task.place());
        List<Card> ten = new ArrayList<>(DeclarerChoiceMain.handOf(board, declarer));
        if (!sorted(ten).equals(sorted(task.ten())) || !sorted(board.deal().skat).equals(sorted(task.skat()))) {
            throw new IllegalStateException("board " + task.board() + " at seed " + seed + " is not the labels' board: "
                    + "another --seed?");
        }
        List<Card> twelve = new ArrayList<>(ten);
        twelve.addAll(board.deal().skat);
        if (!twelve.containsAll(task.discard())) throw new IllegalStateException("the discard is not in the twelve");
        ContractSource.FixedContract fixed = new ContractSource.FixedContract(declarer, task.contract(), task.bid(),
                BidLabelMain.wonAt(round, declarer, task.bid()));
        int[][] out = new int[4][k];
        for (int d = 0; d < k; d++) {
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                long s = Seeds.mix(seed, task.board(), task.place(), d, seat.ordinal(), 0xB1C4L);
                boolean declares = seat == declarer;
                SkatAiProvider p;
                if (declares ? seats.studentDeclares() : seats.studentDefends()) {
                    p = new DeclarerChoiceMain.StudentSeat(seats.student(), declares, task.discard(),
                            new SplittableRandom(s), declares ? 0.0 : seats.defenceTemperature());
                } else {
                    Contestant player = declares ? seats.declarer() : seats.defence();
                    p = player.newProvider(s);
                    if (declares) {
                        if (!(p instanceof SearchAiProvider search)) {
                            throw new IllegalArgumentException(player.id() + " is not a search player: its discard "
                                    + "cannot be forced");
                        }
                        p = search.withForcedDiscard(task.discard());
                    }
                }
                seating.put(seat, p);
            }
            GameOutcome o = GameRunner.playFixed(board, seating, fixed,
                    Seeds.mix(seed, task.board(), task.place(), d, 0xB1C5L));
            if (o.ruleViolations().values().stream().anyMatch(n -> n > 0)) {
                throw new IllegalStateException("board " + task.board() + ": a rule violation");
            }
            out[0][d] = o.gameValue();
            out[1][d] = o.declarerWon() ? 1 : 0;
            out[2][d] = o.declarerPoints();
            out[3][d] = Scoring.tournamentPoints(o, declarer);
        }
        return out;
    }

    static String line(Task task, int[][] r) {
        StringBuilder b = new StringBuilder(task.line()).append('\t');
        for (int i = 0; i < r.length; i++) {
            if (i > 0) b.append(' ');
            for (int d = 0; d < r[i].length; d++) b.append(d == 0 ? "" : ",").append(r[i][d]);
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        Path tasksPath = Path.of(required(options, "tasks"));
        Path out = Path.of(required(options, "out"));
        long seed = Long.parseLong(required(options, "seed"));
        int k = Integer.parseInt(options.getOrDefault("k", "2"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        String playerId = options.getOrDefault("player", "belief-64-shipped");
        String declarerId = options.getOrDefault("declarer", playerId);
        String defenceId = options.getOrDefault("defence", playerId);
        int stride = Integer.parseInt(options.getOrDefault("stride", "1"));
        if (k < 1) throw new IllegalArgumentException("k is at least 1");
        if (stride < 1) throw new IllegalArgumentException("stride is at least 1");
        List<Task> tasks = new ArrayList<>();
        int read = 0;
        for (String l : Files.readAllLines(tasksPath)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            if (read++ % stride == 0) tasks.add(parse(l));
        }
        PolicyNet student = isStudent(declarerId) || isStudent(defenceId)
                ? AllCardsExportMain.load(Path.of(options.getOrDefault("model", "policy-oc3-ev"))) : null;
        Seats seats = seats(declarerId, defenceId, student, PlayerRegistry.withDefaults());
        System.out.printf(Locale.ROOT, "bid check: %,d tasks (every %d), declarer %s, defence %s, k %d, seed %d, "
                + "%d threads -> %s%n", tasks.size(), stride, declarerId, defenceId, k, seed, threads, out);
        long started = System.nanoTime();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (BufferedWriter w = Files.newBufferedWriter(partial)) {
            List<Future<int[][]>> futures = new ArrayList<>();
            for (Task t : tasks) futures.add(pool.submit(() -> play(seats, t, seed, k)));
            for (int i = 0; i < futures.size(); i++) {
                w.write(line(tasks.get(i), futures.get(i).get()));
                w.write('\n');
                futures.set(i, null);
                if ((i + 1) % 200 == 0) {
                    w.flush();
                    System.out.printf(Locale.ROOT, "  %,d of %,d tasks, %.0f s%n", i + 1, tasks.size(),
                            (System.nanoTime() - started) / 1e9);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Files.move(partial, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(Locale.ROOT, "  %,d tasks, %,d play-outs in %.0f s%n", tasks.size(), (long) tasks.size() * k,
                (System.nanoTime() - started) / 1e9);
    }

    private static String required(Map<String, String> options, String key) {
        String v = options.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("--" + key + " is required");
        return v;
    }
}
