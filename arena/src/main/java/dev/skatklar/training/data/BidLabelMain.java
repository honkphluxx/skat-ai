package dev.skatklar.training.data;

import dev.skatklar.demo.BidValues;
import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.LearnedDeclarer;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What winning the auction is worth, seat by seat and bid by bid, with the
 * learned declarer after the skat (docs/training-plan.md, the bidding).
 *
 * <pre>
 * ./gradlew :arena:bidLabels --args="--model=policy-oc3-ev --declarer=declarer-net-v1 --out=x.jsonl --boards=2000"
 * </pre>
 *
 * <p>For every board and every seat: if this seat won the auction at bid b,
 * it would pick up the board's skat and the learned declarer would choose a
 * game covering b and its discard from the twelve ({@link LearnedDeclarer},
 * the seat having bid b and the other two passed). Each distinct choice over
 * the ladder up to the most any game is worth on the twelve is played out k
 * times on the true deal by the student, as the declarer-choice labels are --
 * the declarer by its top card, the defenders drawn -- so the line gives the
 * seat's value of declaring at every bid. Beside it, what the shipped bidder
 * ({@code --shipped}, belief-64-shipped) does with the seat's ten: its
 * ceiling (the highest bid it says or holds once something has been bid)
 * and whether it opens at 18 when nothing has been, after the passes that
 * would have come before it in the seat's place (none in middlehand, middlehand's
 * in rearhand, both in forehand). {@code arena/python/bid_report.py} plays the
 * auction out of these numbers.
 *
 * <p>One JSON line a board, the seats in place order (forehand, middlehand,
 * rearhand). Written in board order as boards finish; the same bytes at any
 * thread count.
 */
public final class BidLabelMain {

    private BidLabelMain() {}

    static final String[] PLACES = {"forehand", "middlehand", "rearhand"};

    /** One seat's labels: the choice at each bid, each distinct choice played out. */
    record SeatLabels(SkatAi.Seat seat, int place, List<Card> ten, int ceiling, boolean open18,
                      List<int[]> levels, List<DeclarerChoiceMain.Scored> picks) {}

    record Result(long board, List<Card> skat, List<SeatLabels> seats) {}

    static SkatAi.Seat seatAt(SkatAi.RoundPosition round, int place) {
        return place == 0 ? round.forehand : place == 1 ? round.middlehand : round.rearhand;
    }

    /** The bids heard by a seat that won at {@code bid}: its own, and the other two passing. */
    static List<SkatAi.BidEvent> wonAt(SkatAi.RoundPosition round, SkatAi.Seat seat, int bid) {
        List<SkatAi.BidEvent> events = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            SkatAi.Seat other = seatAt(round, p);
            if (other != seat) events.add(new SkatAi.BidEvent(other, 0, true));
        }
        events.add(new SkatAi.BidEvent(seat, bid, false));
        return events;
    }

    /**
     * The shipped bidder on a seat's ten: the highest bid it says or holds with
     * something already bid, and whether it opens 18 with nothing bid after the
     * passes its place implies.
     */
    static int[] shippedBidder(SkatAiProvider player, Board board, SkatAi.Seat seat, int place, List<Card> ten) {
        SkatAi.RoundPosition round = board.round();
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(round, seat, new LinkedHashSet<>(ten),
                    DeclarerChoiceMain.RULES));
            int ceiling = 0;
            int previous = 18;
            for (int b : BidValues.LADDER) {
                int current = b == 18 ? 18 : previous;
                if (session.bid(new SkatAi.BidRequest(round, seat, SkatAi.BidRole.HOLD, current, b)) != b) break;
                ceiling = b;
                previous = b;
            }
            // The passes before this seat's first word when nothing is bid.
            int before = place == 1 ? 0 : place == 2 ? 1 : 2;
            if (before >= 1) session.bidObserved(new SkatAi.BidEvent(seatAt(round, 1), 0, true));
            if (before >= 2) session.bidObserved(new SkatAi.BidEvent(seatAt(round, 2), 0, true));
            boolean open = session.bid(new SkatAi.BidRequest(round, seat, SkatAi.BidRole.ANNOUNCE, 0, 18)) == 18;
            return new int[] {ceiling, open ? 1 : 0};
        }
    }

    static Result board(PolicyNet net, LearnedDeclarer declarer, Contestant shipped, Board board, int k, long seed,
                        DeclarerChoiceMain.Stats stats) {
        SkatAi.RoundPosition round = board.round();
        List<Card> skat = board.deal().skat;
        List<SeatLabels> seats = new ArrayList<>(3);
        List<Contract> games = List.of(DeclarerChoiceMain.TRUMP_GAMES);
        for (int place = 0; place < 3; place++) {
            SkatAi.Seat seat = seatAt(round, place);
            List<Card> ten = DeclarerChoiceMain.handOf(board, seat);
            List<Card> twelve = new ArrayList<>(ten);
            twelve.addAll(skat);
            int[] bidder = shippedBidder(shipped.newProvider(Seeds.mix(seed, board.index(), place, 0xB1DDL)), board,
                    seat, place, ten);
            int most = 0;
            for (Contract g : games) if (net.covers(g)) most = Math.max(most, SkatRules.guaranteedValue(g, twelve));
            List<int[]> levels = new ArrayList<>();
            List<DeclarerChoiceMain.Scored> picks = new ArrayList<>();
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (int b : BidValues.LADDER) {
                if (b > most) break;
                Map<SkatAi.Seat, Integer> highest = new EnumMap<>(SkatAi.Seat.class);
                highest.put(seat, b);
                Set<SkatAi.Seat> passed = new LinkedHashSet<>();
                for (int p = 0; p < 3; p++) if (p != place) passed.add(seatAt(round, p));
                List<Contract> covered = new ArrayList<>();
                for (Contract g : games) if (net.covers(g)) covered.add(g);
                LearnedDeclarer.Pick pick = declarer.choose(twelve, b, seat, round, covered, highest, passed);
                if (pick == null) break;
                DeclarerChoiceMain.Option option = new DeclarerChoiceMain.Option(pick.contract(), pick.buried(),
                        DeclarerChoiceMain.pair(dev.skatklar.demo.search.Discards.buried(pick.contract(), twelve))
                                .equals(DeclarerChoiceMain.pair(pick.buried())));
                String key = option.contract() + " " + option.discard();
                Integer index = seen.get(key);
                if (index == null) {
                    index = picks.size();
                    seen.put(key, index);
                    ContractSource.FixedContract fixed = new ContractSource.FixedContract(seat, option.contract(), b,
                            wonAt(round, seat, b));
                    picks.add(DeclarerChoiceMain.playOut(net, board, fixed, option, k,
                            Seeds.mix(seed, place, 0xB1DL)));
                }
                levels.add(new int[] {b, index});
            }
            stats.options += picks.size();
            seats.add(new SeatLabels(seat, place, List.copyOf(ten), bidder[0], bidder[1] == 1, levels, picks));
        }
        stats.boards++;
        return new Result(board.index(), List.copyOf(skat), seats);
    }

    static String json(Result r) {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"board\": ").append(r.board()).append(", \"skat\": ").append(indices(r.skat()))
                .append(", \"seats\": [");
        for (int s = 0; s < r.seats().size(); s++) {
            SeatLabels seat = r.seats().get(s);
            if (s > 0) b.append(", ");
            b.append("{\"place\": \"").append(PLACES[seat.place()]).append("\", \"ten\": ").append(indices(seat.ten()))
                    .append(", \"shipped\": {\"ceiling\": ").append(seat.ceiling()).append(", \"open18\": ")
                    .append(seat.open18()).append("}, \"levels\": [");
            for (int i = 0; i < seat.levels().size(); i++) {
                int[] l = seat.levels().get(i);
                b.append(i == 0 ? "" : ", ").append('[').append(l[0]).append(", ").append(l[1]).append(']');
            }
            b.append("], \"picks\": [");
            for (int i = 0; i < seat.picks().size(); i++) {
                DeclarerChoiceMain.Scored p = seat.picks().get(i);
                if (i > 0) b.append(", ");
                b.append("{\"contract\": \"").append(p.option().contract().name()).append("\", \"discard\": ")
                        .append(indices(p.option().discard())).append(", \"heuristic\": ").append(p.option().heuristic())
                        .append(", \"value\": ").append(ints(p.value())).append(", \"wins\": ").append(ints(p.wins()))
                        .append(", \"points\": ").append(ints(p.points())).append('}');
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    private static String indices(List<Card> cards) {
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparingInt(BeliefEncoding::index));
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) b.append(i == 0 ? "" : ", ").append(BeliefEncoding.index(sorted.get(i)));
        return b.append(']').toString();
    }

    private static String ints(int[] values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) b.append(i == 0 ? "" : ", ").append(values[i]);
        return b.append(']').toString();
    }

    /** The net in {@code directory}, its parity file checked when there is one. */
    static LearnedDeclarer loadDeclarer(Path directory) throws IOException {
        LearnedDeclarer net;
        try (InputStream in = Files.newInputStream(directory.resolve("declarer.bin"))) {
            net = LearnedDeclarer.load(in);
        }
        Path parity = directory.resolve("declarer-parity.txt");
        if (Files.isRegularFile(parity)) {
            try (BufferedReader r = Files.newBufferedReader(parity)) {
                net.checkParity(r, 1e-4f);
            }
        }
        return net;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        Path model = Path.of(options.getOrDefault("model", "policy-oc3-ev"));
        Path declarerDir = Path.of(options.getOrDefault("declarer", "declarer-net-v1"));
        Path out = Path.of(options.getOrDefault("out", "arena-logs/bid-labels/bid-labels.jsonl"));
        long boards = Long.parseLong(options.getOrDefault("boards", "2000"));
        long first = Long.parseLong(options.getOrDefault("first-board", "0"));
        int k = Integer.parseInt(options.getOrDefault("k", "8"));
        long seed = Long.parseLong(options.getOrDefault("seed", "9201"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        String shippedId = options.getOrDefault("shipped", "belief-64-shipped");
        if (k < 2 || k % 2 != 0) throw new IllegalArgumentException("k is even and at least 2");
        if (seed < 1000) throw new IllegalArgumentException("seed " + seed + " is below 1000: those boards are the arena's");

        PolicyNet net = AllCardsExportMain.load(model);
        LearnedDeclarer declarer = loadDeclarer(declarerDir);
        Contestant shipped = PlayerRegistry.withDefaults().resolve(shippedId);
        System.out.printf(Locale.ROOT, "bid labels: student %s, declarer %s, shipped bidder %s, k %d, seed %d, boards "
                + "%,d-%,d, %d threads -> %s%n", model, declarerDir, shippedId, k, seed, first, first + boards - 1,
                threads, out);
        long started = System.nanoTime();
        DeclarerChoiceMain.Stats total = new DeclarerChoiceMain.Stats();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (BufferedWriter w = Files.newBufferedWriter(partial)) {
            List<Future<Object[]>> futures = new ArrayList<>();
            for (long i = first; i < first + boards; i++) {
                Board board = Board.of(seed, i);
                futures.add(pool.submit(() -> {
                    DeclarerChoiceMain.Stats stats = new DeclarerChoiceMain.Stats();
                    return new Object[] {board(net, declarer, shipped, board, k, seed, stats), stats};
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                Object[] result = futures.get(i).get();
                futures.set(i, null);
                w.write(json((Result) result[0]));
                w.write('\n');
                total.add((DeclarerChoiceMain.Stats) result[1]);
                if ((i + 1) % 500 == 0) {
                    w.flush();
                    System.out.printf(Locale.ROOT, "  %,d of %,d boards, %.0f s%n", i + 1, boards,
                            (System.nanoTime() - started) / 1e9);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Files.move(partial, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(Locale.ROOT, "  %,d boards, 3 seats each; %.1f distinct choices a seat, k %d: %,d play-outs "
                        + "in %.0f s%n", total.boards, total.boards == 0 ? 0.0 : total.options / (3.0 * total.boards), k,
                total.options * k, (System.nanoTime() - started) / 1e9);
    }
}
