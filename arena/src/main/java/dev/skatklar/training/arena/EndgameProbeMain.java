package dev.skatklar.training.arena;

import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.demo.solve.DoubleDummySolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where does the solver's work lie, and how much of it would a global endgame cache save?
 *
 * <p>docs/training-plan.md 2.10, T2. Philipp's proposal: a cache, shared by
 * every game, of positions with the last few tricks to go, each fully
 * resolved -- a Skat endgame table. A position (the three remaining hands and
 * who leads, at the start of a trick) is worth the same in every world and
 * every game that reaches it, so the cache is sound; whether it pays is two
 * numbers this measures on the player's own searches, in real games:
 *
 * <ol>
 *   <li>how the nodes of all searches split by tricks left -- what share lies
 *       at or below a cut of 2, 3 or 4 tricks;</li>
 *   <li>how often a position at the cut has been seen before -- within the
 *       same decision (another world, or another question of the same
 *       world), within the same game, or in an earlier game -- and how many
 *       nodes the searches below those repeats cost, which is what a cache
 *       would have saved.</li>
 * </ol>
 *
 * <p>The native engine is not instrumented, so this runs the Java search
 * ({@code -Dskatklar.solver=java}, set by the Gradle task); its algorithm is
 * the one the native engine ports, and shares are what is read here, not
 * times. Positions are tracked on a hash-sample (one key in {@code --sample}),
 * which keeps every repeat of a sampled key and none of an unsampled one, so
 * the rates are unbiased and the memory bounded; node counts are scaled back.
 *
 * <pre>./gradlew :arena:endgameProbe --args="--seed=14 --boards=60 --threads=16"</pre>
 */
public final class EndgameProbeMain {

    private EndgameProbeMain() {}

    static final int[] CUTS = {2, 3, 4};

    /** Where a repeat of a position was seen before, narrowest first. */
    static final String[] SCOPES = {"same search", "other search, this decision",
            "earlier decision, this game", "another game"};

    /** One thread's game, decision and search, and what it has seen in them. */
    static final class Context {
        int game;
        int search = -1;
        final List<LongSet> searchKeys = new ArrayList<>();
        final List<LongSet> decisionKeys = new ArrayList<>();
        final List<LongSet> gameKeys = new ArrayList<>();
        Context() {
            for (int ignored : CUTS) {
                searchKeys.add(new LongSet()); decisionKeys.add(new LongSet()); gameKeys.add(new LongSet());
            }
        }
    }

    /**
     * A set of longs without boxing: open addressing, linear probing, zero as
     * the empty slot (a key of zero is stored as one -- a collision of one in
     * 2^64 on a hash, which counts nothing wrong). Millions of positions a
     * game made HashSet<Long> run out of heap in the first smoke test.
     */
    static final class LongSet {
        private long[] slots = new long[1 << 12];
        private int size;

        /** Adds the key; true if it was not there. */
        boolean add(long key) {
            if (key == 0) key = 1;
            if (size * 2 >= slots.length) grow();
            int mask = slots.length - 1;
            for (int at = (int) (key ^ (key >>> 32)) & mask; ; at = (at + 1) & mask) {
                if (slots[at] == 0) { slots[at] = key; size++; return true; }
                if (slots[at] == key) return false;
            }
        }

        void clear() {
            if (size == 0) return;
            if (slots.length > (1 << 16)) slots = new long[1 << 12]; else java.util.Arrays.fill(slots, 0);
            size = 0;
        }

        private void grow() {
            long[] old = slots;
            slots = new long[old.length * 2];
            size = 0;
            for (long key : old) if (key != 0) add(key);
        }
    }

    /** Totals, added up across threads. */
    static final class Totals {
        final long[] nodesByTricks = new long[11];
        final long[] cutPositions = new long[CUTS.length];
        final long[] cutSubtree = new long[CUTS.length];
        /** Sampled positions, and those repeated, by the narrowest scope they were seen in before. */
        final long[] sampledPositions = new long[CUTS.length];
        final long[][] repeats = new long[CUTS.length][SCOPES.length];
        /** Nodes the repeat cost below its own: what a lookup would have skipped. */
        final long[][] saved = new long[CUTS.length][SCOPES.length];
        long decisions;
        long outside;

        synchronized void add(Totals other) {
            for (int i = 0; i < nodesByTricks.length; i++) nodesByTricks[i] += other.nodesByTricks[i];
            for (int c = 0; c < CUTS.length; c++) {
                cutPositions[c] += other.cutPositions[c];
                cutSubtree[c] += other.cutSubtree[c];
                sampledPositions[c] += other.sampledPositions[c];
                for (int k = 0; k < SCOPES.length; k++) {
                    repeats[c][k] += other.repeats[c][k];
                    saved[c][k] += other.saved[c][k];
                }
            }
            decisions += other.decisions;
            outside += other.outside;
        }
    }

    /** The probe: per-thread context and totals, one global map of first sightings. */
    static final class Probe implements DoubleDummySolver.SearchProbe {
        final int sample;
        final ThreadLocal<Context> context = new ThreadLocal<>();
        final List<Totals> all = Collections.synchronizedList(new ArrayList<>());
        /** Each thread's totals, registered once when the thread first counts. */
        final ThreadLocal<Totals> totals = ThreadLocal.withInitial(() -> {
            Totals t = new Totals();
            all.add(t);
            return t;
        });
        /** For each sampled key, the game that first reached it. */
        final FirstGame firstGame = new FirstGame();

        Probe(int sample) { this.sample = sample; }

        Totals mine() {
            return totals.get();
        }

        @Override public void node(int tricksLeft) {
            mine().nodesByTricks[Math.min(10, tricksLeft)]++;
        }

        @Override public void trickStart(int solver, long position, int leader, int contract, int declarer,
                                         int tricksLeft, long subtreeNodes) {
            int c = -1;
            for (int i = 0; i < CUTS.length; i++) if (CUTS[i] == tricksLeft) c = i;
            if (c < 0) return;
            Totals t = mine();
            t.cutPositions[c]++;
            t.cutSubtree[c] += subtreeNodes;
            long key = mix(position, leader | (declarer << 2) | (contract << 4));
            if ((key & (sample - 1)) != 0) return;
            Context ctx = context.get();
            if (ctx == null) { t.outside++; return; }
            if (solver != ctx.search) {
                // Solvers are built one per question, one after another on this
                // thread, so a new number is a new search with an empty table.
                ctx.search = solver;
                for (LongSet keys : ctx.searchKeys) keys.clear();
            }
            t.sampledPositions[c]++;
            boolean inSearch = !ctx.searchKeys.get(c).add(key);
            boolean inDecision = !ctx.decisionKeys.get(c).add(key);
            boolean inGame = !ctx.gameKeys.get(c).add(key);
            int first = firstGame.putIfAbsent(key, ctx.game);
            boolean otherGame = first >= 0 && first != ctx.game;
            int scope = inSearch ? 0 : inDecision ? 1 : inGame ? 2 : otherGame ? 3 : -1;
            if (scope < 0) return;
            t.repeats[c][scope]++;
            t.saved[c][scope] += subtreeNodes - 1;
        }

        static long mix(long position, int meta) {
            long z = position ^ (meta * 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    /** Key to the first game that reached it, primitive, 64 lock stripes. */
    static final class FirstGame {
        private final long[][] keys = new long[64][];
        private final int[][] games = new int[64][];
        private final int[] sizes = new int[64];

        FirstGame() {
            for (int i = 0; i < 64; i++) { keys[i] = new long[1 << 10]; games[i] = new int[1 << 10]; }
        }

        /** The game already stored for the key, or -1 after storing {@code game}. */
        int putIfAbsent(long key, int game) {
            if (key == 0) key = 1;
            int stripe = (int) (key >>> 58);
            synchronized (keys[stripe]) {
                long[] k = keys[stripe];
                if (sizes[stripe] * 2 >= k.length) grow(stripe);
                k = keys[stripe];
                int[] g = games[stripe];
                int mask = k.length - 1;
                for (int at = (int) (key ^ (key >>> 29)) & mask; ; at = (at + 1) & mask) {
                    if (k[at] == 0) { k[at] = key; g[at] = game; sizes[stripe]++; return -1; }
                    if (k[at] == key) return g[at];
                }
            }
        }

        private void grow(int stripe) {
            long[] oldK = keys[stripe];
            int[] oldG = games[stripe];
            long[] k = new long[oldK.length * 2];
            int[] g = new int[oldK.length * 2];
            int mask = k.length - 1;
            for (int i = 0; i < oldK.length; i++) {
                long key = oldK[i];
                if (key == 0) continue;
                int at = (int) (key ^ (key >>> 29)) & mask;
                while (k[at] != 0) at = (at + 1) & mask;
                k[at] = key;
                g[at] = oldG[i];
            }
            keys[stripe] = k;
            games[stripe] = g;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String rules = Rules.apply(options);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve(options.getOrDefault("player", "belief-32-shipped"));
        long seed = Long.parseLong(options.getOrDefault("seed", "14"));
        int boards = Integer.parseInt(options.getOrDefault("boards", "60"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        int sample = Integer.parseInt(options.getOrDefault("sample", "8"));
        if (Integer.bitCount(sample) != 1) throw new IllegalArgumentException("--sample must be a power of two");
        Probe probe = new Probe(sample);
        DoubleDummySolver.setSearchProbe(probe);
        System.out.printf(Locale.ROOT, "Endgame probe: %s in all three seats, seed %d, %d boards, greedy's contracts, "
                + "Java search, one position in %d tracked, %s%n", player.id(), seed, boards, sample, rules);
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        AtomicInteger games = new AtomicInteger();
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < boards; i++) {
                final Board board = Board.of(seed, i);
                final int gameId = i;
                work.add(() -> {
                    ContractSource.FixedContract fixed = contracts.contractFor(board);
                    if (fixed == null) return null;
                    play(board, seed, fixed, player, probe, gameId);
                    games.incrementAndGet();
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(work)) f.get();
        } finally {
            pool.shutdownNow();
            DoubleDummySolver.setSearchProbe(null);
        }
        Totals sum = new Totals();
        for (Totals t : new ArrayList<>(probe.all)) sum.add(t);
        System.out.print(report(sum, games.get(), sample));
        System.out.printf(Locale.ROOT, "%.0f s%n", (System.nanoTime() - started) / 1e9);
    }

    static void play(Board board, long seed, ContractSource.FixedContract fixed, Contestant player,
                     Probe probe, int gameId) {
        Context ctx = new Context();
        ctx.game = gameId;
        probe.context.set(ctx);
        try {
            SkatAi.Seat declarer = fixed.declarer();
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                SkatAiProvider provider = player.newProvider(Seeds.mix(seed, board.index(), declarer.ordinal(), seat.ordinal()));
                if (provider instanceof SearchAiProvider search) {
                    provider = search.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                        @Override public void decided(SearchAiProvider.CardPlayReport report) {}
                        @Override public void sampled(SkatAi.DecisionContext context, BeliefEncoding.Evidence evidence,
                                                      List<WorldSampler.World> worlds) {
                            // A new decision: its worlds are about to be searched.
                            for (LongSet keys : ctx.decisionKeys) keys.clear();
                            probe.mine().decisions++;
                        }
                    });
                }
                seating.put(seat, provider);
            }
            GameEngine engine = GameEngine.headless(new Random(Seeds.mix(seed, board.index(), declarer.ordinal(), 0xE1E1E1L)),
                    SeatedAiProviders.of(seating));
            for (SkatAiProvider provider : seating.values()) {
                if (provider instanceof TableObserver observer) {
                    observer.observe(engine);
                    observer.observeFixedContract(board, fixed);
                }
            }
            try {
                engine.restartWithContract(board.deal(), board.round(), declarer, fixed.contract(), fixed.bidValue(),
                        Collections.emptySet(), fixed.auction());
                for (int step = 0; step < 128; step++) {
                    GameEngine.Snapshot now = engine.snapshot();
                    if (now.gameComplete()) break;
                    if (now.trickComplete()) engine.finishCompletedTrick(); else engine.playAiCard();
                }
            } finally {
                engine.close();
            }
        } finally {
            probe.context.remove();
        }
    }

    static String report(Totals t, int games, int sample) {
        StringBuilder out = new StringBuilder();
        long all = 0;
        for (long n : t.nodesByTricks) all += n;
        double total = Math.max(1, all);
        out.append(String.format(Locale.ROOT, "%n%d games, %d card decisions searched, %,d nodes%n", games, t.decisions, all));
        out.append("\n1. WHERE THE NODES ARE   share of all nodes, by tricks left (the one in progress included)\n   ");
        for (int k = 10; k >= 1; k--) out.append(String.format(Locale.ROOT, "%d: %4.1f%%  ", k, 100.0 * t.nodesByTricks[k] / total));
        out.append('\n');
        out.append("\n2. A CACHE AT THE CUT   positions at the start of a trick with that many tricks to go\n");
        for (int c = 0; c < CUTS.length; c++) {
            double sp = Math.max(1, t.sampledPositions[c]);
            out.append(String.format(Locale.ROOT, "   cut %d: %,d positions, %,.0f a decision, %.1f nodes below each on average; "
                            + "%.1f%% of all nodes lie below the cut%n", CUTS[c], t.cutPositions[c],
                    t.cutPositions[c] / (double) Math.max(1, t.decisions),
                    t.cutSubtree[c] / (double) Math.max(1, t.cutPositions[c]), 100.0 * t.cutSubtree[c] / total));
            double cumulative = 0;
            for (int k = 0; k < SCOPES.length; k++) {
                double savedShare = 100.0 * sample * t.saved[c][k] / total;
                if (k > 0) cumulative += savedShare;
                out.append(String.format(Locale.ROOT, "      seen before in the %-30s %5.1f%% of positions, their subtrees again: %5.1f%% of all nodes%s%n",
                        SCOPES[k] + ":", 100.0 * t.repeats[c][k] / sp, savedShare,
                        k == 0 ? "   (the search's own table missed these)" : String.format(Locale.ROOT,
                                "   cache kept that long saves %.1f%%", cumulative)));
            }
        }
        out.append("   A repeat is a position already reached before in that scope (the narrowest one counts); what\n"
                + "   it saves is the nodes below it the second time, since a lookup costs about one node. \"same\n"
                + "   search\" repeats are the ones the solver's own table should have caught and did not (evicted,\n"
                + "   or held only a bound the new window could not use). The cumulative column is a cache that\n"
                + "   outlives the search -- kept for the decision, the game, or for good -- holding exact values.\n"
                + "   \"another game\" grows with the games played: games run in parallel, so it is a floor.\n");
        if (t.outside > 0) out.append(String.format(Locale.ROOT, "   (%d tracked positions arose outside a game's thread and are left out)%n", t.outside));
        return out.toString();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) options.put(body, "true"); else options.put(body.substring(0, eq), body.substring(eq + 1));
        }
        return options;
    }
}
