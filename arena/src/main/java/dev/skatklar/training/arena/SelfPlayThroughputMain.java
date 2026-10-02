package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.SearchAiProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How fast can the CPU play network-only games? (plan 2.10, R: the GPU
 * throughput test, its CPU half.)
 *
 * <pre>
 *   gradlew :arena:selfPlayThroughput --args="--threads=1,4,8,16,24 --seconds=20"
 * </pre>
 *
 * <p>Self-play for a network-only player needs games by the hundred million,
 * not the teacher's ninety thousand. Its actors play every card with one
 * forward pass and no world, so the question is what such a game costs on
 * this machine's cores -- before any of it is built. Two numbers, each at
 * several thread counts:
 *
 * <ol>
 *   <li>the forward pass alone ({@link PolicyNet#logits}, one input at a
 *       time, as the player calls it): the floor, and whether it scales with
 *       threads or hits the memory bus -- every call streams the whole weight
 *       matrix, about 3 MB, through the cache;</li>
 *   <li>whole games at fixed suit and grand contracts through the shipped
 *       engine, a bare student actor in all three seats ({@link StudentActor}:
 *       perfect memory, the encoding, one forward pass, no world, nothing at
 *       the deal) -- what a self-play actor built on today's engine and
 *       today's forward pass would deliver, split into the actors' decision
 *       time and everything else (engine, snapshots, sessions);</li>
 *   <li>once, on one thread, the arena's {@code student-64} for comparison: it
 *       is the shipped search player with the student playing every card, and
 *       it still prices every deal for the auction with the solver even at a
 *       fixed contract -- 95% of its time in the first profile, none of it
 *       card play. Self-play would not carry that.</li>
 * </ol>
 *
 * <p>Nothing is learned and nothing is written: this measures the actor side
 * only. The learner's side is {@code arena/python/gpu_throughput.py}.
 */
public final class SelfPlayThroughputMain {

    private SelfPlayThroughputMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        List<Integer> threadCounts = new ArrayList<>();
        for (String part : options.getOrDefault("threads", "1,4,8,16," + cores).split(",")) {
            int count = Integer.parseInt(part.trim());
            if (count >= 1 && !threadCounts.contains(count)) threadCounts.add(count);
        }
        double seconds = Double.parseDouble(options.getOrDefault("seconds", "20"));
        int poolSize = Integer.parseInt(options.getOrDefault("pool", "400"));
        long seed = Long.parseLong(options.getOrDefault("seed", "11"));
        String playerId = options.getOrDefault("player", "student-64");
        System.out.println(Rules.apply(options));

        Path directory = locatePolicy();
        if (directory == null) {
            System.err.println("no policy-model/policy.bin found (or -Dpolicy.model.dir); run export_policy.py");
            System.exit(2);
        }
        PolicyNet net;
        try (InputStream weights = Files.newInputStream(directory.resolve("policy.bin"));
             InputStream parity = Files.newInputStream(directory.resolve("policy-parity.bin"))) {
            net = PolicyNet.load(weights, parity);
        }
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = null;
        try {
            player = registry.resolve(playerId);
        } catch (IllegalArgumentException missing) {
            // Only the comparison line needs it (and the belief model with it).
        }

        long poolStart = System.nanoTime();
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        List<Board> boards = new ArrayList<>();
        List<ContractSource.FixedContract> fixed = new ArrayList<>();
        int skipped = 0;
        for (int index = 0; boards.size() < poolSize && index < poolSize * 8; index++) {
            Board board = Board.of(seed, index);
            ContractSource.FixedContract contract = contracts.contractFor(board);
            if (contract == null) continue;
            if (!net.covers(contract.contract())) {
                skipped++; // a Null: the player would hand it to the search
                continue;
            }
            boards.add(board);
            fixed.add(contract);
        }
        System.out.printf(Locale.ROOT, "the student from %s; %d cores; %d boards (suit and grand, %d Null left out), "
                        + "seed %d, pool built in %.1f s; %.0f s a measurement%n%n",
                directory, cores, boards.size(), skipped, seed,
                (System.nanoTime() - poolStart) / 1e9, seconds);

        // 1. The forward pass alone, on inputs shaped like the encoding's.
        float[][] inputs = new float[256][];
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < inputs.length; i++) {
            float[] x = new float[BeliefEncoding.SIZE];
            for (int j = 0; j < x.length; j++) x[j] = random.nextInt(256) / 255f;
            inputs[i] = x;
        }
        runParallel(Math.max(threadCounts.get(threadCounts.size() - 1), 1), Math.min(3.0, seconds),
                worker -> forwardLoop(net, inputs, worker)); // warm-up, not reported
        System.out.println("forward pass alone (one input a call, as the player calls it)");
        System.out.printf(Locale.ROOT, "    %8s %14s %14s %12s%n", "threads", "passes / s", "per thread", "us a pass");
        for (int threads : threadCounts) {
            long[] done = runParallel(threads, seconds, worker -> forwardLoop(net, inputs, worker));
            double total = done[0] * 1e9 / done[1];
            System.out.printf(Locale.ROOT, "    %8d %,14.0f %,14.0f %12.1f%n",
                    threads, total, total / threads, 1e6 / (total / threads));
        }
        System.out.println();

        // 2. Whole games, the bare actor.
        Contestant actor = StudentActor.contestant(net);
        AtomicLong cursor = new AtomicLong();
        runParallel(threadCounts.get(threadCounts.size() - 1), Math.min(5.0, seconds),
                worker -> gameLoop(actor, boards, fixed, cursor, seed, worker)); // warm-up
        System.out.println("whole games, a bare student actor in all three seats, fixed contracts, the shipped engine");
        System.out.printf(Locale.ROOT, "    %8s %10s %14s %12s %12s %10s %14s%n", "threads", "games / s",
                "decisions / s", "ms a game", "of it: play", "rest", "games / day");
        double bestGames = 0;
        double bestDecisions = 0;
        for (int threads : threadCounts) {
            GameCounters counters = new GameCounters();
            long[] result = runParallel(threads, seconds,
                    worker -> gameLoop(actor, boards, fixed, cursor, seed, worker.with(counters)));
            double wall = result[1] / 1e9;
            double games = counters.games.get() / wall;
            double decisions = counters.decisions.get() / wall;
            double msGame = counters.threadNanos.get() / 1e6 / Math.max(1, counters.games.get());
            double play = counters.decisionNanos.get() / (double) Math.max(1, counters.threadNanos.get());
            bestGames = Math.max(bestGames, games);
            bestDecisions = Math.max(bestDecisions, decisions);
            System.out.printf(Locale.ROOT, "    %8d %10.0f %,14.0f %12.2f %11.0f%% %9.0f%% %,14.0f%n",
                    threads, games, decisions, msGame, 100 * play, 100 * (1 - play), games * 86400);
        }
        System.out.println();
        if (player == null) {
            System.out.println("(no " + playerId + " registered -- is belief-model/ there? -- so no comparison line)");
            System.out.println();
        } else if (!options.containsKey("skip-shipped")) {
            Contestant shipped = player;
            GameCounters counters = new GameCounters();
            long[] result = runParallel(1, seconds,
                    worker -> gameLoop(shipped, boards, fixed, cursor, seed, worker.with(counters)));
            double wall = result[1] / 1e9;
            double msGame = counters.threadNanos.get() / 1e6 / Math.max(1, counters.games.get());
            double play = counters.decisionNanos.get() / (double) Math.max(1, counters.threadNanos.get());
            System.out.printf(Locale.ROOT, "for comparison, %s on one thread: %.2f games / s, %.1f ms a game, "
                            + "%.0f%% of it card play (the rest is mostly its auction pricing)%n%n",
                    playerId, counters.games.get() / wall, msGame, 100 * play);
        }
        System.out.println("decisions: card choices the player made (a forced card is not one).");
        System.out.println("ms a game: thread time per game, so it rises when threads share the cores.");
        System.out.println("of it: play = the actors' own decision time (evidence, encoding, forward");
        System.out.println("pass); rest = engine, snapshots, sessions built per game.");
        double corpus = 1_197_380;
        System.out.printf(Locale.ROOT, "%nat the best rate: the v2 teacher corpus's %,.0f decisions every %.0f s; "
                        + "ten thousand times that in %.1f days%n",
                corpus, corpus / bestDecisions, corpus * 1e4 / bestDecisions / 86400);
    }

    /** One worker's view: its index, and the counters a game loop adds to. */
    private record Worker(int index, long deadline, GameCounters counters, long[] work) {
        Worker with(GameCounters c) { return new Worker(index, deadline, c, work); }
    }

    private static final class GameCounters {
        final AtomicLong games = new AtomicLong();
        final AtomicLong decisions = new AtomicLong();
        final AtomicLong decisionNanos = new AtomicLong();
        final AtomicLong threadNanos = new AtomicLong();
    }

    private interface Job {
        void run(Worker worker);
    }

    /** Runs the job on {@code threads} threads until the deadline; returns {units of work, wall nanos}. */
    private static long[] runParallel(int threads, double seconds, Job job) throws InterruptedException {
        long start = System.nanoTime();
        long deadline = start + (long) (seconds * 1e9);
        long[][] work = new long[threads][1];
        Thread[] pool = new Thread[threads];
        Throwable[] failure = new Throwable[1];
        for (int i = 0; i < threads; i++) {
            Worker worker = new Worker(i, deadline, null, work[i]);
            pool[i] = new Thread(() -> job.run(worker), "throughput-" + i);
            pool[i].setUncaughtExceptionHandler((t, e) -> failure[0] = e);
            pool[i].start();
        }
        for (Thread thread : pool) thread.join();
        long wall = System.nanoTime() - start;
        if (failure[0] != null) throw new IllegalStateException("a worker failed", failure[0]);
        long total = 0;
        for (long[] w : work) total += w[0];
        return new long[] {total, wall};
    }

    private static void forwardLoop(PolicyNet net, float[][] inputs, Worker worker) {
        long count = 0;
        float sink = 0;
        int at = worker.index() * 17;
        while (true) {
            for (int i = 0; i < 64; i++) {
                sink += net.logits(inputs[at++ & (inputs.length - 1)])[0];
                count++;
            }
            if (System.nanoTime() >= worker.deadline()) break;
        }
        if (sink == 1.2345f) System.out.print(""); // keeps the result alive
        worker.work()[0] = count;
    }

    private static void gameLoop(Contestant player, List<Board> boards, List<ContractSource.FixedContract> fixed,
                                 AtomicLong cursor, long seed, Worker worker) {
        GameCounters counters = worker.counters();
        long threadStart = System.nanoTime();
        long games = 0;
        long decisions = 0;
        long decisionNanos = 0;
        long[] local = new long[2];
        SearchAiProvider.CardPlayObserver clock = report -> {
            local[0]++;
            local[1] += report.nanos();
        };
        StudentActor.Clock actorClock = nanos -> {
            local[0]++;
            local[1] += nanos;
        };
        while (System.nanoTime() < worker.deadline()) {
            long n = cursor.getAndIncrement();
            int at = (int) (n % boards.size());
            Board board = boards.get(at);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                SkatAiProvider provider = player.newProvider(Seeds.mix(seed ^ n, board.index(), seat.ordinal()));
                if (provider instanceof SearchAiProvider search) provider = search.withCardPlayObserver(clock);
                if (provider instanceof StudentActor student) provider = student.withClock(actorClock);
                seating.put(seat, provider);
            }
            GameRunner.playFixed(board, seating, fixed.get(at), Seeds.mix(seed ^ n, board.index(), 0xE1E1E1L));
            games++;
        }
        worker.work()[0] = games;
        if (counters != null) {
            counters.games.addAndGet(games);
            counters.decisions.addAndGet(local[0]);
            counters.decisionNanos.addAndGet(local[1]);
            counters.threadNanos.addAndGet(System.nanoTime() - threadStart);
        }
    }

    /**
     * The student and nothing else, as a self-play actor would be: at every card
     * with a choice, the evidence of a perfect memory (every played card, every
     * shown void), the encoding, one forward pass, the top legal card. No world,
     * no solver, nothing at the deal, no auction -- the contract is fixed.
     *
     * <p>For the clock, not for the table: the declarer's own discard and the
     * bidding are left out of its evidence (the shipped player knows both), and
     * it would play a Null with a net that never saw one, which is why the pool
     * holds none. Its cost per card is what matters here, and those omissions
     * change no part of the encoding's or the forward pass's work.
     */
    static final class StudentActor implements SkatAiProvider {
        interface Clock {
            void decided(long nanos);
        }

        private static final SkatAi.AiDescriptor DESCRIPTOR =
                new SkatAi.AiDescriptor("student-actor", "Student actor (throughput test)", false);
        private final PolicyNet net;
        private final Clock clock;

        StudentActor(PolicyNet net, Clock clock) {
            this.net = net;
            this.clock = clock;
        }

        static Contestant contestant(PolicyNet net) {
            return new Contestant() {
                @Override public String id() { return "student-actor"; }
                @Override public String displayName() { return DESCRIPTOR.displayName; }
                @Override public SkatAiProvider newProvider(long seed) { return new StudentActor(net, null); }
                @Override public String toString() { return id(); }
            };
        }

        StudentActor withClock(Clock clock) { return new StudentActor(net, clock); }

        @Override public SkatAi.AiDescriptor descriptor() { return DESCRIPTOR; }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    if (context.legalCards.size() == 1) return context.legalCards.iterator().next();
                    long start = System.nanoTime();
                    Set<Card> seen = context.derived.playedCards;
                    BeliefEncoding.Evidence evidence = new BeliefEncoding.Evidence(context, seen,
                            context.derived.voidClasses, null, Map.of(), false,
                            BeliefEncoding.trumpsUnaccounted(context.game.contract, context.hand, seen),
                            BeliefEncoding.jacksUnseen(context.hand, seen));
                    Card card = net.choose(evidence, new ArrayList<>(context.legalCards));
                    if (clock != null) clock.decided(System.nanoTime() - start);
                    return card;
                }
            };
        }
    }

    private static Path locatePolicy() {
        String override = System.getProperty("policy.model.dir");
        if (override != null && !override.isBlank()) {
            Path named = Path.of(override);
            return Files.isRegularFile(named.resolve("policy.bin")) ? named : null;
        }
        for (Path candidate : List.of(Path.of("policy-model"), Path.of("..", "policy-model"),
                Path.of("..", "..", "policy-model"))) {
            if (Files.isRegularFile(candidate.resolve("policy.bin"))) return candidate;
        }
        return null;
    }
}
