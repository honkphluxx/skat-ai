package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.BeliefModel;
import dev.skatklar.demo.search.BeliefWorldSource;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.demo.search.WorldSource;
import dev.skatklar.training.belief.NetBeliefModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * How often the worlds a player samples are the world on the table.
 *
 * <p>Phase B2's first step, and the one number the plan has never printed:
 * of the worlds a decision draws, what share is exactly the true deal, and
 * how many of the unseen cards does the average world place where they
 * really are. The oracle sweep ({@code belief-5} .. {@code belief-25}, the
 * truth mixed in at that percentage) is the yardstick -- the arena already
 * knows at what mixing rate the curve starts paying, so the belief's share
 * here says directly whether it is above or below that rung, trick by trick.
 *
 * <p>Two players on the same games, the belief and the uniform sampler, each
 * seated in all three chairs at fixed contracts from greedy's auction, seeded
 * exactly as {@link DuplicateMatch}'s rotation for the declarer's seat. The
 * sampled worlds are read from each player's own session through
 * {@link SearchAiProvider.CardPlayObserver#sampled}, a default no-op, so the
 * probe watches the decision the player actually made and moves nothing.
 *
 * <p>What the two columns mean. <b>Exact</b> is the plan's "true-world
 * share": all twenty unseen cards in their true places (the skat included).
 * <b>Placed</b> is per card: of the unseen cards, the share a sampled world
 * puts in the right hand, averaged over the worlds. Exact is what the vote
 * would need to be a statement about the real deal; placed is what a
 * belief can realistically move, and it is what held-out accuracy in
 * {@code train_belief.py} measures, so the two can be read against each
 * other. A uniform sampler places an unseen card right about half the time
 * with two hands to choose from and worse with the skat in play; its exact
 * share at trick one is the reciprocal of the number of consistent deals,
 * which is millions.
 *
 * <pre>./gradlew :arena:beliefShare --args="--seeds=11,12,13 --boards=200 --threads=16"</pre>
 */
public final class BeliefShareMain {

    private BeliefShareMain() {}

    /**
     * One decision's reading: how the sampled worlds compared with the truth.
     *
     * <p>{@code argmax} and {@code confident} are the probe's two extra
     * readings on the same decision, both against the same unseen cards:
     * the share the net's own most likely place gets right, ignoring that
     * the places have capacities (the held-out number, in play), and the
     * placement of worlds drawn by the confident-first sampler from the same
     * belief. NaN where the probe had no model to ask.
     */
    record Reading(int trick, boolean declarer, int worlds, int exact, double placed, int unseen,
                   double argmax, double confident) {}

    /** Sums for one cell of the table. */
    static final class Cell {
        long decisions, worlds, exact; double placed; long unseen;
        double argmax, confident; long probed;
        void add(Reading r) {
            decisions++; worlds += r.worlds(); exact += r.exact(); placed += r.placed() * r.worlds();
            unseen += r.unseen();
            if (!Double.isNaN(r.argmax())) { probed++; argmax += r.argmax(); confident += r.confident(); }
        }
        double exactShare() { return worlds == 0 ? 0 : 100.0 * exact / worlds; }
        double placedShare() { return worlds == 0 ? 0 : 100.0 * placed / worlds; }
        double argmaxShare() { return probed == 0 ? Double.NaN : 100.0 * argmax / probed; }
        double confidentShare() { return probed == 0 ? Double.NaN : 100.0 * confident / probed; }
    }

    /**
     * The model the probe asks beside the player: the candidate directory when
     * one is named, else the shipped weights. Null when neither loads, and the
     * two extra columns are then blank rather than the run refused.
     */
    private static BeliefWorldSource probeSource() {
        String named = System.getProperty("belief.model.candidate.dir");
        for (Path dir : named != null && !named.isBlank()
                ? List.of(Path.of(named))
                : List.of(Path.of("belief-model"), Path.of("..", "belief-model"), Path.of("..", "..", "belief-model"))) {
            if (!Files.isRegularFile(dir.resolve("belief.bin"))) continue;
            try {
                BeliefModel model = NetBeliefModel.load(dir);
                System.out.println("probe model: " + dir.toAbsolutePath().normalize());
                return new BeliefWorldSource(model);
            } catch (Exception failed) {
                System.out.println("probe model at " + dir + " did not load: " + failed.getMessage());
            }
        }
        System.out.println("probe model: none; the argmax and confident columns stay blank");
        return null;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String rules = Rules.apply(options);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        List<String> ids = new ArrayList<>();
        for (String part : options.getOrDefault("players", "belief-32-shipped,search-32").split("[ ,]+")) {
            if (!part.isBlank()) ids.add(part.trim());
        }
        int boards = Integer.parseInt(options.getOrDefault("boards", "200"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        List<Long> seeds = new ArrayList<>();
        for (String part : options.getOrDefault("seeds", options.getOrDefault("seed", "11")).split("[ ,]+")) {
            if (!part.isBlank()) seeds.add(Long.parseLong(part.trim()));
        }
        String out = options.get("out");
        System.out.printf(Locale.ROOT, "Belief share: %s, %d boards a seed, seeds %s, %s%n",
                ids, boards, seeds, rules);

        BeliefWorldSource probe = probeSource();
        // player -> role -> trick -> cell
        Map<String, Map<Boolean, Map<Integer, Cell>>> table = new LinkedHashMap<>();
        StringBuilder csv = new StringBuilder("player,seed,board,contract,trick,role,worlds,exact,placed,unseen,argmax,confident\n");
        for (String id : ids) {
            Contestant player = registry.resolve(id);
            for (long seed : seeds) {
                ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
                List<Callable<List<String>>> work = new ArrayList<>();
                for (int i = 0; i < boards; i++) {
                    final int index = i;
                    work.add(() -> probe(Board.of(seed, index), seed, player, contracts, table, id, csv, probe));
                }
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
                try {
                    for (Future<List<String>> f : pool.invokeAll(work)) f.get();
                } finally {
                    pool.shutdownNow();
                }
                System.out.printf(Locale.ROOT, "  %s seed %d done%n", id, seed);
            }
        }
        System.out.print(report(table));
        if (out != null) {
            Path dir = Path.of(out).toAbsolutePath();
            Files.createDirectories(dir);
            Path file = dir.resolve("belief-share-decisions.csv");
            synchronized (csv) { Files.writeString(file, csv.toString()); }
            System.out.println("Written: " + file);
        }
    }

    private static List<String> probe(Board board, long seed, Contestant player, ContractSource contracts,
                                      Map<String, Map<Boolean, Map<Integer, Cell>>> table, String id,
                                      StringBuilder csv, BeliefWorldSource probe) {
        ContractSource.FixedContract fixed = contracts.contractFor(board);
        if (fixed == null) return List.of();
        SkatAi.Seat declarer = fixed.declarer();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        GameEngine[] table_ = new GameEngine[1];
        List<Reading> readings = Collections.synchronizedList(new ArrayList<>());
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            SkatAiProvider provider = player.newProvider(Seeds.mix(seed, board.index(), declarer.ordinal(), seat.ordinal()));
            if (provider instanceof SearchAiProvider search) {
                provider = search.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                    @Override public void decided(SearchAiProvider.CardPlayReport report) {}
                    @Override public void sampled(SkatAi.DecisionContext context,
                                                  BeliefEncoding.Evidence evidence,
                                                  List<WorldSampler.World> worlds) {
                        readings.add(read(context, evidence, worlds, table_[0].snapshot(), probe,
                                new Random(Seeds.mix(seed, board.index(), context.mySeat.ordinal(),
                                        table_[0].snapshot().completedTricks))));
                    }
                });
            }
            seating.put(seat, provider);
        }
        long engineSeed = Seeds.mix(seed, board.index(), declarer.ordinal(), 0xE1E1E1L);
        GameEngine engine = GameEngine.headless(new Random(engineSeed), SeatedAiProviders.of(seating));
        table_[0] = engine;
        try {
            engine.restartWithContract(board.deal(), board.round(), fixed.declarer(),
                    fixed.contract(), fixed.bidValue(), Collections.emptySet());
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot s = engine.snapshot();
                if (s.gameComplete()) break;
                if (s.trickComplete()) engine.finishCompletedTrick(); else engine.playAiCard();
            }
        } finally {
            engine.close();
        }
        synchronized (table) {
            for (Reading r : readings) {
                table.computeIfAbsent(id, k -> new LinkedHashMap<>())
                        .computeIfAbsent(r.declarer(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(r.trick(), k -> new Cell()).add(r);
            }
        }
        synchronized (csv) {
            for (Reading r : readings) {
                csv.append(id).append(',').append(seed).append(',').append(board.index()).append(',')
                        .append(fixed.contract()).append(',').append(r.trick()).append(',')
                        .append(r.declarer() ? "declarer" : "defender").append(',')
                        .append(r.worlds()).append(',').append(r.exact()).append(',')
                        .append(String.format(Locale.ROOT, "%.4f", r.placed())).append(',')
                        .append(r.unseen()).append(',')
                        .append(Double.isNaN(r.argmax()) ? "" : String.format(Locale.ROOT, "%.4f", r.argmax())).append(',')
                        .append(Double.isNaN(r.confident()) ? "" : String.format(Locale.ROOT, "%.4f", r.confident())).append('\n');
            }
        }
        return List.of();
    }

    /**
     * One decision: the sampled worlds against the engine's hands. The
     * snapshot is read at the moment of sampling, before the card is played,
     * so the unseen cards are the other two hands plus the skat as they stand.
     */
    static Reading read(SkatAi.DecisionContext context, BeliefEncoding.Evidence evidence,
                        List<WorldSampler.World> worlds, GameEngine.Snapshot truth,
                        BeliefWorldSource probe, Random random) {
        int me = context.mySeat.ordinal();
        Map<Card, Integer> where = new LinkedHashMap<>();
        for (int seat = 0; seat < 3; seat++) {
            if (seat == me) continue;
            for (Card card : truth.hands.get(seat)) where.put(card, seat);
        }
        // The declarer buried the skat and knows it; a hand game's declarer
        // and every defender have to guess it.
        boolean knowsTheSkat = context.mySeat == context.game.declarer && !context.game.hand;
        if (!knowsTheSkat) for (Card card : truth.skat) where.put(card, 3);
        int unseen = where.size();
        int exact = 0;
        double placed = 0;
        for (WorldSampler.World world : worlds) {
            int right = 0;
            boolean all = true;
            for (int seat = 0; seat < 3; seat++) {
                if (seat == me) continue;
                for (Card card : world.hands().get(seat)) {
                    Integer real = where.get(card);
                    if (real != null && real == seat) right++; else all = false;
                }
            }
            if (!knowsTheSkat) {
                for (Card card : world.skat()) {
                    Integer real = where.get(card);
                    if (real != null && real == 3) right++; else all = false;
                }
            }
            if (all) exact++;
            placed += unseen == 0 ? 1.0 : (double) right / unseen;
        }
        double argmax = Double.NaN;
        double confident = Double.NaN;
        if (probe != null && unseen > 0) {
            double[][] belief = probe.belief(evidence);
            if (belief != null) {
                // The net's most likely place per unseen card, capacities ignored.
                int left = context.mySeat.next().ordinal();
                int right_ = 0;
                for (Map.Entry<Card, Integer> entry : where.entrySet()) {
                    double[] b = belief[BeliefEncoding.index(entry.getKey())];
                    int best = 0;
                    for (int place = 1; place < b.length; place++) if (b[place] > b[best]) best = place;
                    int guessed = best == BeliefEncoding.CLASS_SKAT ? 3
                            : best == BeliefEncoding.CLASS_LEFT ? left : 3 - me - left;
                    if (guessed == entry.getValue()) right_++;
                }
                argmax = (double) right_ / unseen;
                // The same belief, drawn through the confident-first sampler.
                List<WorldSampler.World> drawn = probe.withConfidentFirst()
                        .sample(evidence, Math.max(1, worlds.size()), random);
                double sum = 0;
                for (WorldSampler.World world : drawn) {
                    int hit = 0;
                    for (int seat = 0; seat < 3; seat++) {
                        if (seat == me) continue;
                        for (Card card : world.hands().get(seat)) {
                            Integer real = where.get(card);
                            if (real != null && real == seat) hit++;
                        }
                    }
                    if (!knowsTheSkat) {
                        for (Card card : world.skat()) {
                            Integer real = where.get(card);
                            if (real != null && real == 3) hit++;
                        }
                    }
                    sum += (double) hit / unseen;
                }
                confident = drawn.isEmpty() ? Double.NaN : sum / drawn.size();
            }
        }
        return new Reading(truth.completedTricks + 1, context.mySeat == context.game.declarer,
                worlds.size(), exact, worlds.isEmpty() ? 0 : placed / worlds.size(), unseen,
                argmax, confident);
    }

    static String report(Map<String, Map<Boolean, Map<Integer, Cell>>> table) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Map<Boolean, Map<Integer, Cell>>> p : table.entrySet()) {
            out.append(String.format(Locale.ROOT, "%n%s%n", p.getKey()));
            for (boolean declarer : new boolean[] {true, false}) {
                Map<Integer, Cell> byTrick = p.getValue().getOrDefault(declarer, Map.of());
                out.append(String.format(Locale.ROOT, "  as %s   trick:  exact%%  placed%%  (decisions, unseen cards)   net argmax%%  confident-first%%%n",
                        declarer ? "declarer" : "defender"));
                Cell early = new Cell();
                for (int trick = 1; trick <= 10; trick++) {
                    Cell c = byTrick.get(trick);
                    if (c == null) continue;
                    if (trick <= 3) { early.decisions += c.decisions; early.worlds += c.worlds; early.exact += c.exact; early.placed += c.placed; early.unseen += c.unseen; early.probed += c.probed; early.argmax += c.argmax; early.confident += c.confident; }
                    out.append(String.format(Locale.ROOT, "              %2d:   %6.2f   %6.1f   (%d, %.1f)   %s%n", trick,
                            c.exactShare(), c.placedShare(), c.decisions,
                            c.decisions == 0 ? 0 : (double) c.unseen / c.decisions, extra(c)));
                }
                out.append(String.format(Locale.ROOT, "        tricks 1-3:   %6.2f   %6.1f   (%d)   %s%n",
                        early.exactShare(), early.placedShare(), early.decisions, extra(early)));
            }
        }
        return out.toString();
    }

    private static String extra(Cell c) {
        if (c.probed == 0) return "";
        return String.format(Locale.ROOT, "%6.1f   %6.1f", c.argmaxShare(), c.confidentShare());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            int eq = arg.indexOf('=');
            if (eq < 0) options.put(arg.substring(2), "true");
            else options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return options;
    }
}
