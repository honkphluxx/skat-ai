package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.training.arena.TableObserver;
import dev.skatklar.training.players.TrueDealLabeller;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The all-cards self-play exporter (docs/training-plan.md 2.10, route 1).
 *
 * <pre>
 *   gradlew :arena:allCardsExport --args="--model=policy-b600-pa40 --out=allcards-data/r1-k8 --boards=100000"
 * </pre>
 *
 * <p>The student plays all three seats at fixed suit and grand contracts
 * (greedy's auction and skat exchange). The game only finds the positions;
 * the labels come from the play-outs. At every decision with a choice the
 * main line takes the policy head's top card, or with probability
 * {@code epsilon} a card drawn from its softmax -- never anything computed
 * from the hidden cards, so every later position is one an honest table
 * reaches and the deal is a draw from the posterior the seats hold. Then
 * every legal card is played out {@code k} times on the table's real cards by
 * {@link TrueDealLabeller}, and the decision is written with each card's
 * wins, card points and game value from the decider's side.
 *
 * <p>To read the table, the seats are {@link TableObserver}s: the engine is
 * held by the labeller's side of the seat and read only after the main-line
 * card is chosen; the choice uses the seat's own context and its own random
 * stream (AllCardsExportTest holds the games with and without labels equal).
 *
 * <p>Every random stream is a function of the seed and the board, so a shard
 * is the same bytes at any thread count. Boards go into shards of
 * {@code shard-size}, written as {@code .partial} and renamed when complete; a
 * complete shard is skipped, and a {@code STOP} file in the working directory
 * stops before the next shard. Beside the shards: {@code allcards-format.json}
 * (the layout and every setting -- a resumed run must match it), the encoding
 * spec, and per shard a {@code .json} of label totals by card that
 * {@code arena/python/allcards_data.py} checks the bytes against.
 *
 * <p>Record layout ({@link #RECORD_BYTES} bytes, little-endian; slots are the
 * legal cards in card-index order):
 *
 * <pre>
 *     0  306  the belief encoding, a byte a feature
 *   306    4  legal cards, a bit per card index
 *   310    1  the main-line card
 *   311    1  flags: 1 = drawn (not the top card by rule), 2 = the decider is the declarer
 *   312    1  trick, 1-9
 *   313    1  contract ordinal
 *   314    1  k, play-outs a card
 *   315    1  the decider's place in the trick, 0-2
 *   316    1  main line: 1 if the decider's side won
 *   317    1  main line: the decider's side's card points
 *   318   10  wins per slot (u8, of k)
 *   328   20  card points per slot, summed over k (u16)
 *   348   40  game value per slot, summed over k, signed for the decider (i32)
 *   388    4  board index (u32)
 *   392    4  board seed (u32)
 * </pre>
 */
public final class AllCardsExportMain {

    static final int FORMAT = 1;
    static final int SLOTS = 10;
    static final int ENCODING = 0;
    static final int LEGAL = BeliefEncoding.SIZE;
    static final int CHOSEN = LEGAL + 4;
    static final int FLAGS = CHOSEN + 1;
    static final int TRICK = FLAGS + 1;
    static final int CONTRACT = TRICK + 1;
    static final int K = CONTRACT + 1;
    static final int PLACE = K + 1;
    static final int MAIN_WON = PLACE + 1;
    static final int MAIN_POINTS = MAIN_WON + 1;
    static final int WINS = MAIN_POINTS + 1;
    static final int POINTS = WINS + SLOTS;
    static final int VALUE = POINTS + 2 * SLOTS;
    static final int BOARD = VALUE + 4 * SLOTS;
    static final int SEED = BOARD + 4;
    static final int RECORD_BYTES = SEED + 4;

    static final int DRAWN = 1;
    static final int DECLARER = 2;

    private AllCardsExportMain() {}

    /** Everything that decides the bytes of a shard. */
    record Settings(Path model, String modelSha256, long seed, int k, double temperature, double epsilon,
                    int shardSize) {
        String json() {
            return "{\n"
                    + "  \"format\": " + FORMAT + ",\n"
                    + "  \"record_bytes\": " + RECORD_BYTES + ",\n"
                    + "  \"slots\": " + SLOTS + ",\n"
                    + "  \"encoding_version\": " + BeliefEncoding.VERSION + ",\n"
                    + "  \"encoding_size\": " + BeliefEncoding.SIZE + ",\n"
                    + "  \"scale\": 255,\n"
                    + "  \"fields\": {\"encoding\": [" + ENCODING + ", " + BeliefEncoding.SIZE + "], "
                    + "\"legal\": [" + LEGAL + ", 4], \"chosen\": [" + CHOSEN + ", 1], \"flags\": [" + FLAGS + ", 1], "
                    + "\"trick\": [" + TRICK + ", 1], \"contract\": [" + CONTRACT + ", 1], \"k\": [" + K + ", 1], "
                    + "\"place\": [" + PLACE + ", 1], \"main_won\": [" + MAIN_WON + ", 1], "
                    + "\"main_points\": [" + MAIN_POINTS + ", 1], \"wins\": [" + WINS + ", " + SLOTS + "], "
                    + "\"points\": [" + POINTS + ", " + (2 * SLOTS) + "], \"value\": [" + VALUE + ", " + (4 * SLOTS)
                    + "], \"board\": [" + BOARD + ", 4], \"seed\": [" + SEED + ", 4]},\n"
                    + "  \"flags\": {\"drawn\": " + DRAWN + ", \"declarer\": " + DECLARER + "},\n"
                    + "  \"model\": \"" + model.toString().replace('\\', '/') + "\",\n"
                    + "  \"model_sha256\": \"" + modelSha256 + "\",\n"
                    + "  \"seed\": " + seed + ",\n"
                    + "  \"k\": " + k + ",\n"
                    + String.format(Locale.ROOT, "  \"temperature\": %s,%n", temperature)
                    + String.format(Locale.ROOT, "  \"epsilon\": %s,%n", epsilon)
                    + "  \"shard_size\": " + shardSize + "\n"
                    + "}\n";
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        Path model = Path.of(options.getOrDefault("model", "policy-b600-pa40"));
        Path out = Path.of(require(options, "out"));
        long boards = Long.parseLong(options.getOrDefault("boards", "100000"));
        long first = Long.parseLong(options.getOrDefault("first-board", "0"));
        int shardSize = Integer.parseInt(options.getOrDefault("shard-size", "2000"));
        int k = Integer.parseInt(options.getOrDefault("k", "8"));
        double temperature = Double.parseDouble(options.getOrDefault("temperature", "1"));
        double epsilon = Double.parseDouble(options.getOrDefault("epsilon", "0.2"));
        long seed = Long.parseLong(options.getOrDefault("seed", "9001"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        checkSeed(seed);
        if (!(epsilon >= 0 && epsilon <= 1)) throw new IllegalArgumentException("epsilon is 0 to 1, not " + epsilon);
        if (first % shardSize != 0) throw new IllegalArgumentException("--first-board is a multiple of --shard-size");

        PolicyNet net = load(model);
        Settings settings = new Settings(model, sha256(model.resolve("policy.bin")), seed, k, temperature, epsilon,
                shardSize);
        Files.createDirectories(out);
        Path format = out.resolve("allcards-format.json");
        if (Files.exists(format)) {
            if (!Files.readString(format).equals(settings.json())) {
                throw new IllegalStateException(out + " holds shards made with other settings (" + format
                        + "); use another --out or the same settings");
            }
        } else {
            Files.writeString(format, settings.json());
        }
        Files.writeString(out.resolve("encoding-v" + BeliefEncoding.VERSION + ".json"),
                BeliefEncoding.specificationJson());

        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant greedy = registry.resolve("greedy");
        ContractSource contracts = new AuctionContractSource(greedy, seed);
        TrueDealLabeller labeller = new TrueDealLabeller(net, k, temperature);
        System.out.printf(Locale.ROOT, "all-cards export: %s, k %d, temperature %s, epsilon %s, seed %d, boards %,d-%,d "
                        + "in shards of %,d, %d threads -> %s%n", model, k, temperature, epsilon, seed, first,
                first + boards - 1, shardSize, threads, out);

        Stats total = new Stats();
        long started = System.nanoTime();
        for (long from = first; from < first + boards; from += shardSize) {
            long to = Math.min(first + boards, from + shardSize);
            Path shard = out.resolve(String.format(Locale.ROOT, "allcards-s%d-%07d.bin", seed, from));
            if (Files.exists(shard)) {
                System.out.printf(Locale.ROOT, "  %s exists, skipped%n", shard.getFileName());
                continue;
            }
            if (Files.exists(Path.of("STOP"))) {
                System.out.println("STOP file found -- stopping before " + shard.getFileName());
                break;
            }
            long shardStart = System.nanoTime();
            Shard result = playShard(net, labeller, greedy, contracts, settings, from, to, threads);
            Path partial = out.resolve(shard.getFileName() + ".partial");
            try (OutputStream sink = Files.newOutputStream(partial)) {
                for (byte[] record : result.records) sink.write(record);
            }
            Files.writeString(out.resolve(shard.getFileName().toString().replace(".bin", ".json")),
                    result.totals.json(from, to));
            Files.move(partial, shard, StandardCopyOption.ATOMIC_MOVE);
            total.add(result.stats);
            double seconds = (System.nanoTime() - shardStart) / 1e9;
            System.out.printf(Locale.ROOT, "  %s: %,d games, %,d records, %.0f s (%.1f games/s), %.0f s so far%n",
                    shard.getFileName(), result.stats.games, result.records.size(), seconds,
                    result.stats.games / seconds, (System.nanoTime() - started) / 1e9);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        total.print(seconds, threads, k);
    }

    static void checkSeed(long seed) {
        // 101 is the teacher corpora's (the yardstick's held-out deals); 11-16
        // and 21 are the arena's. Nothing below 1000 is ours to train on.
        if (seed < 1000) {
            throw new IllegalArgumentException("seed " + seed + " is below 1000: the teacher corpora (101) and the "
                    + "arena's seeds live there, and self-play must not play their boards");
        }
    }

    /** One shard: its records in board order, its label totals, its statistics. */
    record Shard(List<byte[]> records, Totals totals, Stats stats) {}

    static Shard playShard(PolicyNet net, TrueDealLabeller labeller, Contestant greedy, ContractSource contracts,
                           Settings settings, long from, long to, int threads) throws InterruptedException {
        int n = (int) (to - from);
        @SuppressWarnings("unchecked")
        List<byte[]>[] byBoard = new List[n];
        Stats[] stats = new Stats[Math.max(1, threads)];
        AtomicInteger next = new AtomicInteger();
        Throwable[] failure = new Throwable[1];
        Thread[] pool = new Thread[stats.length];
        for (int t = 0; t < pool.length; t++) {
            Stats own = stats[t] = new Stats();
            pool[t] = new Thread(() -> {
                for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) {
                    byBoard[i] = playBoard(net, labeller, greedy, contracts, settings, Board.of(settings.seed, from + i),
                            settings.epsilon, own, null);
                }
            }, "allcards-" + t);
            pool[t].setUncaughtExceptionHandler((th, e) -> failure[0] = e);
            pool[t].start();
        }
        for (Thread thread : pool) thread.join();
        if (failure[0] != null) throw new IllegalStateException("a worker failed", failure[0]);
        List<byte[]> records = new ArrayList<>();
        for (List<byte[]> board : byBoard) records.addAll(board);
        Totals totals = new Totals();
        for (byte[] record : records) totals.add(record);
        Stats merged = new Stats();
        for (Stats s : stats) merged.add(s);
        return new Shard(records, totals, merged);
    }

    /** What a test may watch of one game: every decision as labelled, with the table at that moment. */
    record Watched(SkatAi.DecisionContext context, List<List<Card>> hands, List<Card> skat, List<Card> discard,
                   Card played, Card top, boolean drawn, byte[] encoding, TrueDealLabeller.Labels labels,
                   int playsBefore) {}

    /** Tests only: one game's decisions, every card played in order, and the outcome. */
    static final class Watch {
        final List<Watched> decisions = new ArrayList<>();
        final List<SkatAi.PlayedCard> plays = new ArrayList<>();
        GameOutcome outcome;
    }

    /**
     * One board: the main line played, every decision with a choice labelled,
     * the records returned (none when the board has no suit or grand contract
     * the net was trained on).
     *
     * @param labeller null to play the main line alone (the honesty control)
     * @param watch    tests only: hears every decision; null otherwise
     */
    static List<byte[]> playBoard(PolicyNet net, TrueDealLabeller labeller, Contestant greedy,
                                  ContractSource contracts, Settings settings, Board board, double epsilon,
                                  Stats stats, Watch watch) {
        ContractSource.FixedContract contract = contracts.contractFor(board);
        if (contract == null || contract.contract().isNull() || contract.contract().isRamsch()
                || !net.covers(contract.contract())) {
            stats.skipped++;
            return List.of();
        }
        long seed = settings.seed;
        Game game = new Game(board.index(), seed, labeller, stats, watch);
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            seating.put(seat, new Actor(net, greedy.newProvider(Seeds.mix(seed, board.index(), seat.ordinal())),
                    epsilon, new SplittableRandom(Seeds.mix(seed, board.index(), seat.ordinal(), 0xAC7L)), game,
                    seat == SkatAi.Seat.HUMAN));
        }
        GameOutcome outcome = GameRunner.playFixed(board, seating, contract, Seeds.mix(seed, board.index(), 0xE1E1E1L));
        if (watch != null) watch.outcome = outcome;
        List<byte[]> records = new ArrayList<>(game.pending.size());
        for (Pending d : game.pending) {
            if (d.labels == null) continue;
            boolean declarer = d.seat == outcome.declarer();
            boolean won = declarer == outcome.declarerWon();
            int points = declarer ? outcome.declarerPoints() : 120 - outcome.declarerPoints();
            records.add(record(d, declarer, outcome.contract().ordinal(), labeller.k(), won, points, board.index(),
                    seed));
        }
        stats.games++;
        return records;
    }

    static byte[] record(Pending d, boolean declarer, int contract, int k, boolean won, int points, long board,
                         long seed) {
        ByteBuffer r = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        r.put(d.encoding);
        r.putInt(d.legal);
        r.put((byte) d.chosen);
        r.put((byte) ((d.drawn ? DRAWN : 0) | (declarer ? DECLARER : 0)));
        r.put((byte) d.trick);
        r.put((byte) contract);
        r.put((byte) k);
        r.put((byte) d.place);
        r.put((byte) (won ? 1 : 0));
        r.put((byte) Math.max(0, Math.min(120, points)));
        TrueDealLabeller.Labels l = d.labels;
        int slots = l.cards().size();
        if (slots > SLOTS) throw new IllegalStateException(slots + " legal cards");
        for (int s = 0; s < SLOTS; s++) r.put(s < slots ? (byte) l.wins()[s] : 0);
        for (int s = 0; s < SLOTS; s++) r.putShort(s < slots ? (short) l.points()[s] : 0);
        for (int s = 0; s < SLOTS; s++) r.putInt(s < slots ? Math.toIntExact(l.value()[s]) : 0);
        r.putInt((int) board);
        r.putInt((int) seed);
        return r.array();
    }

    /** One decision, kept until the game's outcome is known. */
    record Pending(SkatAi.Seat seat, byte[] encoding, int legal, int chosen, boolean drawn, int trick, int place,
                   TrueDealLabeller.Labels labels) {}

    /** What the three seats of one game share: the table, the declarer's discard, the decisions. */
    static final class Game {
        final long board;
        final long seed;
        final TrueDealLabeller labeller;
        final Stats stats;
        final Watch watch;
        final List<Pending> pending = new ArrayList<>(30);
        GameEngine engine;
        List<Card> discard;
        int decisions;

        Game(long board, long seed, TrueDealLabeller labeller, Stats stats, Watch watch) {
            this.board = board;
            this.seed = seed;
            this.labeller = labeller;
            this.stats = stats;
            this.watch = watch;
        }

        /** Labels a decision on the table as it is now; the main-line card is already chosen. */
        void label(SkatAi.DecisionContext context, List<Card> legal, Map<SkatAi.Seat, Integer> bids,
                   byte[] encoding, int mask, Card card, boolean drawn, Card top) {
            int number = decisions++;
            int trick = context.history.completedTricks.size() + 1;
            int place = context.currentTrick.plays.size();
            TrueDealLabeller.Labels labels = null;
            List<List<Card>> hands = null;
            List<Card> skat = null;
            if (labeller != null) {
                GameEngine.Snapshot table = engine.snapshot();
                hands = new ArrayList<>(3);
                for (List<Card> hand : table.hands) hands.add(List.copyOf(hand));
                skat = List.copyOf(table.skat);
                long started = System.nanoTime();
                labels = labeller.label(context, hands, skat, bids, discard, legal,
                        Seeds.mix(seed, board, number, 0x1ABE1L));
                stats.decided(trick, labels, legal.indexOf(top), System.nanoTime() - started);
            }
            pending.add(new Pending(context.mySeat, encoding, mask, BeliefEncoding.index(card), drawn, trick, place,
                    labels));
            if (watch != null) {
                watch.decisions.add(new Watched(context, hands, skat, discard, card, top, drawn, encoding, labels,
                        watch.plays.size()));
            }
        }
    }

    /**
     * The student in one seat. Everything before the first card is greedy's
     * (the auction it only hears, the skat exchange); this keeps what a perfect
     * memory keeps of it, as {@link SelfPlayExportMain}'s actor does.
     */
    static final class Actor implements SkatAiProvider, TableObserver {
        private static final SkatAi.AiDescriptor DESCRIPTOR =
                new SkatAi.AiDescriptor("allcards-actor", "All-cards actor", true);
        private final PolicyNet net;
        private final SkatAiProvider greedy;
        private final double epsilon;
        private final SplittableRandom random;
        private final Game game;
        /** The one seat that writes the game's cards down for a watching test. */
        private final boolean recorder;
        private final Map<SkatAi.Seat, Integer> highestBids = new EnumMap<>(SkatAi.Seat.class);
        private List<Card> buried;

        Actor(PolicyNet net, SkatAiProvider greedy, double epsilon, SplittableRandom random, Game game,
              boolean recorder) {
            this.recorder = recorder;
            this.net = net;
            this.greedy = greedy;
            this.epsilon = epsilon;
            this.random = random;
            this.game = game;
        }

        /** The table, for the labeller only; the card choice below never reads it. */
        @Override public void observe(GameEngine engine) { game.engine = engine; }

        @Override public SkatAi.AiDescriptor descriptor() { return DESCRIPTOR; }

        @Override public SkatAiSession createSession() {
            SkatAiSession delegate = greedy.createSession();
            return new SkatAiSession() {
                @Override public void prepareDeal(SkatAi.DealContext context) { delegate.prepareDeal(context); }
                @Override public int bid(SkatAi.BidRequest request) { return delegate.bid(request); }
                @Override public void bidObserved(SkatAi.BidEvent event) {
                    if (!event.passed) highestBids.merge(event.seat, event.value, Math::max);
                    delegate.bidObserved(event);
                }
                @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
                    return delegate.pickUpSkat(context);
                }
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    Set<Card> discarded = delegate.discardSkat(context);
                    buried = List.copyOf(discarded);
                    game.discard = buried;
                    return discarded;
                }
                @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
                    return delegate.announceContract(context);
                }
                @Override public void startGame(SkatAi.GameStartContext context) { delegate.startGame(context); }
                @Override public Card chooseCard(SkatAi.DecisionContext context) { return choose(context); }
                @Override public void cardPlayed(SkatAi.CardPlayedEvent event) {
                    if (recorder && game.watch != null) game.watch.plays.add(event.play);
                    delegate.cardPlayed(event);
                }
                @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
                    delegate.trickCompleted(event);
                }
                @Override public void endGame(SkatAi.GameResult result) { delegate.endGame(result); }
                @Override public void close() { delegate.close(); }
            };
        }

        private Card choose(SkatAi.DecisionContext context) {
            List<Card> legal = new ArrayList<>(context.legalCards);
            if (legal.size() == 1) return legal.get(0);
            legal.sort(Comparator.comparingInt(BeliefEncoding::index));
            byte[] encoding = BeliefEncoding.quantise(BeliefEncoding.encode(
                    SelfPlayExportMain.perfectMemory(context, highestBids, buried)));
            float[] scores = net.logits(BeliefEncoding.dequantise(encoding));
            Card top = TrueDealLabeller.topCard(scores, legal);
            boolean drawn = random.nextDouble() < epsilon;
            Card card = drawn ? TrueDealLabeller.drawCard(scores, legal, 1.0, random) : top;
            int mask = 0;
            for (Card c : legal) mask |= 1 << BeliefEncoding.index(c);
            game.label(context, legal, Map.copyOf(highestBids), encoding, mask, card, drawn, top);
            return card;
        }
    }

    /** Label totals by card index over a shard, for the Python reader to check the bytes against. */
    static final class Totals {
        long records;
        final long[] wins = new long[32];
        final long[] points = new long[32];
        final long[] value = new long[32];
        final long[] chosen = new long[32];

        void add(byte[] bytes) {
            ByteBuffer r = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int mask = r.getInt(LEGAL);
            int slot = 0;
            for (int card = 0; card < 32; card++) {
                if ((mask & (1 << card)) == 0) continue;
                wins[card] += r.get(WINS + slot) & 0xFF;
                points[card] += r.getShort(POINTS + 2 * slot) & 0xFFFF;
                value[card] += r.getInt(VALUE + 4 * slot);
                slot++;
            }
            chosen[r.get(CHOSEN) & 0xFF]++;
            records++;
        }

        String json(long from, long to) {
            return "{\"boards\": [" + from + ", " + to + "], \"records\": " + records
                    + ", \"wins\": " + list(wins) + ", \"points\": " + list(points) + ", \"value\": " + list(value)
                    + ", \"chosen\": " + list(chosen) + "}\n";
        }

        private static String list(long[] values) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < values.length; i++) b.append(i == 0 ? "" : ", ").append(values[i]);
            return b.append(']').toString();
        }
    }

    /** Cost and noise, by trick: what decides k. */
    static final class Stats {
        long games;
        long skipped;
        final long[] decisions = new long[10];
        final long[] positions = new long[10];
        final long[] nanos = new long[10];
        final long[] topIsBest = new long[10];
        final double[] regret = new double[10];
        final long[] halves = new long[10];
        final long[] halvesAgree = new long[10];
        final double[] within = new double[10];
        final long[] withinDf = new long[10];
        final double[] between = new double[10];
        final long[] betweenDf = new long[10];

        void decided(int trick, TrueDealLabeller.Labels l, int topSlot, long elapsed) {
            int t = trick - 1;
            int cards = l.cards().size();
            int k = l.valueByDraw()[0].length;
            decisions[t]++;
            positions[t] += l.positions();
            nanos[t] += elapsed;
            int best = TrueDealLabeller.best(l);
            if (best == topSlot) topIsBest[t]++;
            regret[t] += (l.value()[best] - l.value()[topSlot]) / (double) k;
            double[] mean = new double[cards];
            double all = 0;
            for (int c = 0; c < cards; c++) {
                mean[c] = l.value()[c] / (double) k;
                all += mean[c];
                for (int d = 0; d < k; d++) within[t] += Math.pow(l.valueByDraw()[c][d] - mean[c], 2);
                withinDf[t] += k - 1;
            }
            all /= cards;
            for (int c = 0; c < cards; c++) between[t] += Math.pow(mean[c] - all, 2);
            betweenDf[t] += cards - 1;
            if (k >= 2) {
                int h = k / 2;
                long[] a = new long[cards];
                long[] b = new long[cards];
                for (int c = 0; c < cards; c++) {
                    for (int d = 0; d < h; d++) a[c] += l.valueByDraw()[c][d];
                    for (int d = h; d < 2 * h; d++) b[c] += l.valueByDraw()[c][d];
                }
                halves[t]++;
                if (argmax(a) == argmax(b)) halvesAgree[t]++;
            }
        }

        private static int argmax(long[] v) {
            int best = 0;
            for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
            return best;
        }

        synchronized void add(Stats o) {
            games += o.games;
            skipped += o.skipped;
            for (int t = 0; t < 10; t++) {
                decisions[t] += o.decisions[t];
                positions[t] += o.positions[t];
                nanos[t] += o.nanos[t];
                topIsBest[t] += o.topIsBest[t];
                regret[t] += o.regret[t];
                halves[t] += o.halves[t];
                halvesAgree[t] += o.halvesAgree[t];
                within[t] += o.within[t];
                withinDf[t] += o.withinDf[t];
                between[t] += o.between[t];
                betweenDf[t] += o.betweenDf[t];
            }
        }

        void print(double seconds, int threads, int k) {
            long d = 0;
            long p = 0;
            for (int t = 0; t < 10; t++) {
                d += decisions[t];
                p += positions[t];
            }
            System.out.printf(Locale.ROOT, "%n%,d games (%,d boards without a suit or grand contract), %,d labelled "
                            + "decisions, %.0f s at %d threads: %.2f games/s, %.0f labelled decisions/s, %,d positions "
                            + "through the net (%.0f a game)%n", games, skipped, d, seconds, threads,
                    games / Math.max(seconds, 1e-9), d / Math.max(seconds, 1e-9), p, p / (double) Math.max(1, games));
            System.out.printf(Locale.ROOT, "k %d. By trick: cost, and the noise that decides k (game value a play-out, "
                    + "from the decider's side):%n", k);
            System.out.println("  trick  decisions  positions/dec  ms/dec(thread)  sd within card  sd between cards"
                    + "  halves agree  top is best  top's regret");
            for (int t = 0; t < 10; t++) {
                if (decisions[t] == 0) continue;
                System.out.printf(Locale.ROOT, "  %5d  %9d  %13.0f  %14.1f  %14.2f  %16.2f  %12s  %10.1f%%  %12.2f%n",
                        t + 1, decisions[t], positions[t] / (double) decisions[t], nanos[t] / 1e6 / decisions[t],
                        Math.sqrt(within[t] / Math.max(1, withinDf[t])),
                        Math.sqrt(between[t] / Math.max(1, betweenDf[t])),
                        halves[t] == 0 ? "--" : String.format(Locale.ROOT, "%.1f%%", 100.0 * halvesAgree[t] / halves[t]),
                        100.0 * topIsBest[t] / decisions[t], regret[t] / decisions[t]);
            }
            System.out.println("  (sd within card / sqrt(k) is the noise on a card's mean; \"halves agree\": the best card "
                    + "by the first k/2 play-outs is the best by the other k/2. Top is best and its regret are on this "
                    + "deal, so read them as an upper bound on what the labels can teach.)");
        }
    }

    static PolicyNet load(Path directory) throws IOException {
        try (InputStream weights = Files.newInputStream(directory.resolve("policy.bin"));
             InputStream parity = Files.newInputStream(directory.resolve("policy-parity.bin"))) {
            return PolicyNet.load(weights, parity);
        }
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + "=... is required");
        return value;
    }
}
