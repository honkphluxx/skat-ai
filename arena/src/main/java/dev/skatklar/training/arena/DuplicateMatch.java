package dev.skatklar.training.arena;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Head-to-head comparison of two AI implementations under duplicate scoring.
 *
 * <p>Every board is played six times. Three times with contestant A in one seat
 * and B in the other two, and three times with the roles exchanged. Both sides
 * therefore declare and defend the same cards from the same positions, which is
 * what makes the per-board difference a fair, low-variance measurement.
 *
 * <p>Skat's variance is severe enough that an unpaired comparison of two
 * reasonable players needs tens of thousands of games to resolve a difference
 * that duplicate scoring exposes in a few hundred boards.
 */
public final class DuplicateMatch {
    /** Games each side plays per board: one per seat. */
    public static final int GAMES_PER_SIDE_AND_BOARD = SkatAi.Seat.values().length;

    private DuplicateMatch() {}

    public static MatchResult run(Contestant a, Contestant b, int boards, long seed) {
        return run(a, b, boards, seed, 1, progress -> {});
    }

    /**
     * Compares card play only: the auction is replaced by {@code contracts}, and
     * both contestants play every board at the identical declarer and contract.
     *
     * <p>This is fair whatever the source produces -- a poor contract costs both
     * sides equally and cancels in the paired difference. What the source governs
     * is whether the exercised skills are the ones that matter; see
     * {@link ContractSource}.
     */
    public static MatchResult runFixedContract(Contestant a, Contestant b, int boards, long seed,
                                               int threads, ContractSource contracts,
                                               Consumer<String> progress) {
        return execute(a, b, boards, seed, threads, contracts, progress);
    }

    /**
     * @param threads 1 by default on purpose. The vendored JSkat player reaches
     *                into process-wide state, so parallel boards are opt-in and
     *                must be validated per contestant before being trusted.
     */
    public static MatchResult run(Contestant a, Contestant b, int boards, long seed,
                                  int threads, Consumer<String> progress) {
        return execute(a, b, boards, seed, threads, null, progress);
    }

    private static MatchResult execute(Contestant a, Contestant b, int boards, long seed,
                                       int threads, ContractSource contracts,
                                       Consumer<String> progress) {
        if (boards < 1) throw new IllegalArgumentException("A match needs at least one board");

        List<Callable<BoardResult>> work = new ArrayList<>(boards);
        for (int i = 0; i < boards; i++) {
            final int index = i;
            work.add(() -> playBoard(a, b, Board.of(seed, index), seed, contracts));
        }

        List<BoardResult> results = threads > 1
                ? runParallel(work, threads, progress)
                : runSerial(work, progress);

        Tally tallyA = new Tally(a.id());
        Tally tallyB = new Tally(b.id());
        List<Double> diffs = new ArrayList<>(boards);
        int skipped = 0;
        for (BoardResult result : results) {
            if (result == null) { skipped++; continue; }
            tallyA.add(result.a());
            tallyB.add(result.b());
            // Differenced in game points, not tournament points. The canon
            // settles classically (docs/rules.md 1), and a measurement taken in
            // a currency the game does not pay in optimises for the wrong thing:
            // Seeger-Fabian pays a defender 40 every time a declarer goes down,
            // which rewards abstaining, and it has no number at all for a Ramsch.
            diffs.add((double) (result.a().gamePoints() - result.b().gamePoints()));
        }
        double[] flat = new double[diffs.size()];
        for (int i = 0; i < flat.length; i++) flat[i] = diffs.get(i);
        return new MatchResult(tallyA, tallyB, flat, GAMES_PER_SIDE_AND_BOARD,
                contracts == null ? "auction at every table" : contracts.describe(), skipped);
    }

    private static List<BoardResult> runSerial(List<Callable<BoardResult>> work,
                                               Consumer<String> progress) {
        List<BoardResult> results = new ArrayList<>(work.size());
        for (int i = 0; i < work.size(); i++) {
            try {
                results.add(work.get(i).call());
            } catch (Exception failure) {
                throw new IllegalStateException("Board " + i + " failed", failure);
            }
            reportProgress(progress, i + 1, work.size());
        }
        return results;
    }

    private static List<BoardResult> runParallel(List<Callable<BoardResult>> work,
                                                 int threads, Consumer<String> progress) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<BoardResult>> futures = pool.invokeAll(work);
            List<BoardResult> results = new ArrayList<>(work.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("Board " + i + " failed", failure.getCause());
                }
                reportProgress(progress, i + 1, work.size());
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Match interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void reportProgress(Consumer<String> progress, int done, int total) {
        int step = Math.max(1, total / 20);
        if (done % step == 0 || done == total) {
            progress.accept(String.format("  %d/%d boards", done, total));
        }
    }

    private static BoardResult playBoard(Contestant a, Contestant b, Board board, long seed,
                                         ContractSource contracts) {
        ContractSource.FixedContract fixed = null;
        if (contracts != null) {
            fixed = contracts.contractFor(board);
            // A board the source cannot price is dropped rather than quietly
            // falling back to an auction, which would mix two instruments.
            if (fixed == null) return null;
        }
        Tally tallyA = new Tally(a.id());
        Tally tallyB = new Tally(b.id());
        for (SkatAi.Seat singleton : SkatAi.Seat.values()) {
            tallyA.record(playRotation(board, a, b, singleton, seed, fixed), singleton);
            tallyB.record(playRotation(board, b, a, singleton, seed, fixed), singleton);
        }
        return new BoardResult(tallyA, tallyB);
    }

    /**
     * One seating of one board, with <b>common random numbers</b>.
     *
     * <p>The seed a provider gets depends on the board and the seat and on
     * nothing else — in particular not on which of the two sides is being played
     * out. That is what makes the pairing exact: the two halves differ only in
     * which contestant sits where, so a player compared with itself plays the
     * identical game and the measured difference is exactly zero.
     *
     * <p>It was not always so. The half used to go into the seed, which meant
     * the two sides drew different worlds from different streams, and a player
     * scored against a copy of itself came out 2.4 game points apart on fourteen
     * boards. That noise is inside every number this arena has ever printed: it
     * is invisible when two players differ by a lot, and it is the whole signal
     * when they differ by little. It is what "resolved" a −3.08 between αµ at
     * depth one and the player it is provably identical to.
     *
     * <p>Common random numbers is the standard fix and costs nothing. Every
     * measurement of a small effect gets sharper, and a self-match becomes the
     * instrument's own zero check.
     */
    private static GameOutcome playRotation(Board board, Contestant singleton, Contestant others,
                                            SkatAi.Seat singletonSeat, long seed,
                                            ContractSource.FixedContract fixed) {
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            Contestant contestant = seat == singletonSeat ? singleton : others;
            seating.put(seat, contestant.newProvider(Seeds.mix(
                    seed, board.index(), singletonSeat.ordinal(), seat.ordinal())));
        }
        long engineSeed = Seeds.mix(seed, board.index(), singletonSeat.ordinal(), 0xE1E1E1L);
        return fixed == null
                ? GameRunner.play(board, seating, engineSeed)
                : GameRunner.playFixed(board, seating, fixed, engineSeed);
    }

    private record BoardResult(Tally a, Tally b) {}
}
