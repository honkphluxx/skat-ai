package dev.skatklar.training.data;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.NullContractSource;
import dev.skatklar.training.arena.SolverContractSource;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Generates the belief model's training set by playing games and writing down
 * what each seat could see and where the cards actually were.
 *
 * <p>Played against a <b>population</b> rather than against one opponent, and
 * that is not a detail. A belief model trained only on games between copies of
 * one player learns where <em>that</em> player puts its cards; against anyone
 * else it is confidently wrong. The seats are filled from a mix of strengths and
 * styles, so the distribution of positions is at least not a single program's
 * habits. Humans are still missing from that mix, and that — not the raw volume —
 * is what the ISS archive would add.
 *
 * <pre>
 *   --boards=&lt;n&gt;    boards to play                          (default 1000)
 *   --seed=&lt;n&gt;      the usual reproducible match seed        (default 1)
 *   --threads=&lt;n&gt;   parallel boards                          (default 1)
 *   --out=&lt;dir&gt;     where the shards go                     (default belief-data)
 *   --shard=&lt;n&gt;     boards per shard file                    (default 500)
 *   --players=a,b   population to seat, comma separated
 *   --passed-in=&lt;x&gt; ramsch (default) or void; see DuplicateMatch.PassedIn
 *   --contracts=&lt;x&gt; auction (default), solver or null; see below
 * </pre>
 *
 * <p><b>Minting contracts.</b> By default the population bids and the contract
 * is whatever the auction produced, which is the corpus a belief model wants.
 * {@code --contracts=} replaces that with a {@link ContractSource}: the auction
 * is skipped and the board is played at a contract chosen for it. Note that
 * {@code auction} here means <em>the population's own auction</em>, not
 * {@link AuctionContractSource} — the exporter has a whole table of bidders
 * already and borrowing one of them to bid for everybody would throw away the
 * diversity the population is for.
 *
 * <p>This exists for one reason. Two corpora have been counted and both hold
 * exactly zero Null decision points, because no seat in any population ever
 * announces a Null: the player will bid 18, hold 23 and announce it, but
 * {@code guaranteedValue(NULL)} is a flat 23 and can never outbid anyone. That
 * is a bidding defect, and waiting for it to be fixed would block the belief
 * model on Null indefinitely. {@code --contracts=null} mints the games instead.
 *
 * <p>What a minted record is not: a game anybody bid for. Its bidding block is
 * empty, because there was no auction, so a model trained on it learns where the
 * cards lie in a Null <em>given no bidding evidence</em>. That is close to the
 * app's own case, where our seats only ever meet a Null a person declared, but it
 * is not the same thing — which is why minted boards belong in their own
 * {@code --out=} directory rather than stirred into a corpus that was bid for.
 *
 * <p>The corpus has to be exported under the rules the resulting player will be
 * measured under. A model trained on boards where every pass-out became a
 * Ramsch has learned what a pass implies at a table that plays Ramsch, and that
 * is not the table it will be graded at in void-board mode.
 */
public final class ExportMain {

    private ExportMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }

        int boards = Integer.parseInt(options.getOrDefault("boards", "1000"));
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        int shardSize = Integer.parseInt(options.getOrDefault("shard", "500"));
        boolean voidPassedIn = switch (options.getOrDefault("passed-in", "ramsch")) {
            case "ramsch" -> false;
            case "void" -> true;
            default -> throw new IllegalArgumentException(
                    "Unknown --passed-in '" + options.get("passed-in") + "'. Use ramsch or void.");
        };
        Path out = Path.of(options.getOrDefault("out", "belief-data")).toAbsolutePath();
        String contractsOption = options.getOrDefault("contracts", "auction");
        ContractSource contracts = switch (contractsOption) {
            case "auction" -> null;   // the population bids, as it always has
            case "solver" -> new SolverContractSource();
            case "null" -> new NullContractSource();
            default -> throw new IllegalArgumentException(
                    "Unknown --contracts '" + contractsOption + "'. Use auction, solver or null.");
        };

        // The corpus is scored under the same rule the arena measures under;
        // see Rules. It prints, because a corpus and the model trained on it
        // must be read against the ladder they were built on.
        System.out.println(dev.skatklar.training.arena.Rules.apply(options));
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        List<Contestant> population = new ArrayList<>();
        for (String id : options.getOrDefault("players",
                "greedy,search-4,club,expert,jskat-new").split(",")) {
            if (id.isBlank()) continue;
            try {
                population.add(registry.resolve(id.trim()));
            } catch (IllegalArgumentException absent) {
                System.out.println("skipping unavailable player " + id.trim());
            }
        }
        if (population.isEmpty()) throw new IllegalArgumentException("No players to seat");

        Files.createDirectories(out);
        Files.writeString(out.resolve("encoding-v" + BeliefEncoding.VERSION + ".json"),
                BeliefEncoding.specificationJson());
        // The trailer's three bytes are indices into this list. Written beside the
        // shards so a corpus stays readable without the command line that made it.
        StringBuilder players = new StringBuilder("[");
        for (int at = 0; at < population.size(); at++) {
            players.append(at > 0 ? ", " : "").append('"').append(population.get(at).id())
                    .append('"');
        }
        Files.writeString(out.resolve("population.json"), players.append("]\n").toString());

        System.out.printf(Locale.ROOT,
                "%,d boards, seed %d, %d thread(s), %d bytes a record, into %s%n",
                boards, seed, threads, BeliefExporter.RECORD_BYTES, out);
        System.out.println("population: " + population);
        System.out.println("contracts: " + (contracts == null
                ? "whatever the population's own auction produced"
                : contracts.describe() + " (no auction: the bidding block stays empty)"));

        long startedAt = System.nanoTime();
        long total = 0;
        // Boards no source could price. Reported rather than swallowed: a source
        // that quietly skipped four boards in five would make a corpus look
        // merely small instead of unrepresentative.
        AtomicInteger skipped = new AtomicInteger();
        for (int first = 0; first < boards; first += shardSize) {
            int last = Math.min(boards, first + shardSize);
            Path shard = out.resolve(String.format(Locale.ROOT, "shard-%05d.bin", first));
            try (BeliefExporter.ShardWriter writer = new BeliefExporter.ShardWriter(shard)) {
                play(population, first, last, seed, threads, writer, voidPassedIn,
                        contracts, skipped);
                total += writer.records();
                System.out.printf(Locale.ROOT, "  %s: %,d records (%,d boards)%n",
                        shard.getFileName(), writer.records(), last - first);
            }
        }
        double seconds = (System.nanoTime() - startedAt) / 1e9;
        System.out.printf(Locale.ROOT, "%n%,d records in %.0f s (%,.0f a second)%n",
                total, seconds, total / seconds);
        if (contracts != null) {
            int played = boards - skipped.get();
            System.out.printf(Locale.ROOT,
                    "%,d of %,d boards could be priced (%.1f%%); %,d skipped%n",
                    played, boards, 100.0 * played / boards, skipped.get());
            if (played == 0) throw new IllegalStateException(
                    "No board could be priced: the corpus would be empty.");
        }
    }

    private static void play(List<Contestant> population, int firstBoard, int lastBoard,
                             long seed, int threads, BeliefExporter.Sink sink,
                             boolean voidPassedIn, ContractSource contracts,
                             AtomicInteger skipped) throws Exception {
        List<Callable<Void>> work = new ArrayList<>(lastBoard - firstBoard);
        for (int index = firstBoard; index < lastBoard; index++) {
            final int board = index;
            work.add(() -> {
                playBoard(population, Board.of(seed, board), seed, sink, voidPassedIn,
                        contracts, skipped);
                return null;
            });
        }
        if (threads > 1) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (Future<Void> future : pool.invokeAll(work)) future.get();
            } finally {
                pool.shutdownNow();
            }
        } else {
            for (Callable<Void> task : work) task.call();
        }
    }

    /**
     * One board, played once with a randomly drawn seating.
     *
     * <p>No duplicate rotation here: this is not a measurement, and playing the
     * same deal six times would only teach the model that these particular deals
     * are common. Fresh deals are cheap.
     */
    /**
     * A number that identifies the <em>deal</em>, not its position in one run.
     *
     * <p>The board index restarts at zero for every export, so two runs at
     * different seeds would hand the trainer the same identifiers for entirely
     * different cards -- and the validation split, which exists precisely to keep
     * one deal out of both halves, would quietly stop working the first time a
     * second export was added to a directory. Mixing the seed in makes the id
     * mean what the split needs it to mean: same number, same thirty-two cards.
     */
    private static int dealIdentity(long seed, Board board) {
        return (int) Seeds.mix(seed, board.index(), 0xDEA1);
    }

    private static void playBoard(List<Contestant> population, Board board, long seed,
                                  BeliefExporter.Sink sink, boolean voidPassedIn,
                                  ContractSource contracts, AtomicInteger skipped) {
        Random random = new Random(Seeds.mix(seed, board.index(), 0xDA7AL));
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        Map<SkatAi.Seat, Integer> whoSatWhere = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            whoSatWhere.put(seat, random.nextInt(population.size()));
        }
        List<BeliefExporter> recorders = new ArrayList<>(3);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            Contestant contestant = population.get(whoSatWhere.get(seat));
            BeliefExporter recorder = new BeliefExporter(
                    contestant.newProvider(Seeds.mix(seed, board.index(), seat.ordinal())),
                    sink, whoSatWhere, dealIdentity(seed, board));
            recorders.add(recorder);
            seating.put(seat, recorder);
        }
        long engineSeed = Seeds.mix(seed, board.index(), 0xE1E1E1L);
        if (contracts == null) {
            GameRunner.play(board, seating, engineSeed, voidPassedIn);
        } else {
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            // A board the source cannot price is not played at all. Falling back
            // to the auction would be worse than skipping: the corpus would be
            // part minted and part bid for, with nothing in a record to say
            // which, and the empty bidding block would stop meaning anything.
            if (fixed == null) {
                skipped.incrementAndGet();
                recorders.clear();
                return;
            }
            GameRunner.playFixed(board, seating, fixed, engineSeed);
        }
        recorders.clear();
    }
}
