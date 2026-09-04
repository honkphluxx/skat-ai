package dev.skatklar.training.arena;

import java.util.Locale;
import java.util.Map;

/**
 * The outcome of a duplicate match, reported as a <em>paired</em> comparison.
 *
 * <p>Each board contributes one difference: the game points contestant A scored
 * playing all three seats, minus the points contestant B scored playing the same
 * three seats of the same board. Game points, because that is what this ruleset
 * pays -- the declarer's column on a paper list, or in a Ramsch the loser's. Because both contestants saw the
 * identical cards from the identical positions, deal luck cancels inside each
 * difference instead of having to average out across the sample. That is the
 * entire reason for duplicate scoring, and it is worth roughly an order of
 * magnitude in the number of boards needed to resolve a small edge.
 *
 * <p>The reported interval is therefore a paired-difference confidence interval,
 * not two independent means compared by eye.
 */
public final class MatchResult {
    /** 97.5th percentile of the normal distribution. */
    private static final double Z95 = 1.959964;

    private final Tally a;
    private final Tally b;
    private final double[] perBoardDiff;
    private final int gamesPerSideAndBoard;
    private final String contractSource;
    private final int skippedBoards;

    MatchResult(Tally a, Tally b, double[] perBoardDiff, int gamesPerSideAndBoard,
                String contractSource, int skippedBoards) {
        this.a = a;
        this.b = b;
        this.perBoardDiff = perBoardDiff.clone();
        this.gamesPerSideAndBoard = gamesPerSideAndBoard;
        this.contractSource = contractSource;
        this.skippedBoards = skippedBoards;
    }

    public String contractSource() { return contractSource; }
    public int skippedBoards() { return skippedBoards; }

    /**
     * Boards on which both contestants scored the same and which therefore
     * carried no information: a contract nobody could make, or one nobody could
     * lose. Duplicate pairing already neutralises them -- they contribute a zero
     * difference rather than noise -- but a high share means most of the sample
     * is being spent to learn nothing, which is a property of the contract
     * source worth watching.
     */
    public int boardsWithoutSignal() {
        int tied = 0;
        for (double diff : perBoardDiff) if (diff == 0.0) tied++;
        return tied;
    }

    public Tally a() { return a; }
    public Tally b() { return b; }
    public int boards() { return perBoardDiff.length; }
    public int games() { return perBoardDiff.length * gamesPerSideAndBoard * 2; }
    public double[] perBoardDiff() { return perBoardDiff.clone(); }

    /** Mean advantage of A over B, in game points per game. */
    public double meanDiffPerGame() {
        double sum = 0;
        for (double diff : perBoardDiff) sum += diff;
        return perBoardDiff.length == 0 ? 0 : sum / perBoardDiff.length / gamesPerSideAndBoard;
    }

    /** Standard error of {@link #meanDiffPerGame()}. */
    public double stdErrorPerGame() {
        int n = perBoardDiff.length;
        if (n < 2) return Double.NaN;
        double mean = 0;
        for (double diff : perBoardDiff) mean += diff;
        mean /= n;
        double sumSquares = 0;
        for (double diff : perBoardDiff) sumSquares += (diff - mean) * (diff - mean);
        double sd = Math.sqrt(sumSquares / (n - 1));
        return sd / Math.sqrt(n) / gamesPerSideAndBoard;
    }

    public double ciLowPerGame() { return meanDiffPerGame() - Z95 * stdErrorPerGame(); }
    public double ciHighPerGame() { return meanDiffPerGame() + Z95 * stdErrorPerGame(); }

    /** True when the 95% interval excludes zero, i.e. the difference is resolved. */
    public boolean significant() {
        double err = stdErrorPerGame();
        return !Double.isNaN(err) && err > 0 && (ciLowPerGame() > 0 || ciHighPerGame() < 0);
    }

    /** Rough number of boards needed to resolve the observed edge, for planning. */
    public long boardsForSignificance() {
        double mean = meanDiffPerGame();
        double err = stdErrorPerGame();
        if (Double.isNaN(err) || mean == 0) return -1;
        double sdPerBoard = err * Math.sqrt(boards());
        return (long) Math.ceil(Math.pow(Z95 * sdPerBoard / Math.abs(mean), 2));
    }

    public String report() {
        StringBuilder out = new StringBuilder();
        out.append("Duplicate match: ").append(a.contestant())
                .append("  vs  ").append(b.contestant()).append('\n');
        out.append(String.format(Locale.ROOT,
                "%d boards, %d games (each board played from all three seats by both sides)%n",
                boards(), games()));
        out.append("Contracts from: ").append(contractSource).append('\n');
        if (skippedBoards > 0) {
            out.append(String.format(Locale.ROOT,
                    "  %d board(s) skipped: the source produced no contract%n", skippedBoards));
        }
        if (boards() > 0) {
            out.append(String.format(Locale.ROOT,
                    "  %d of %d boards scored alike for both sides and carried no signal (%.0f%%)%n",
                    boardsWithoutSignal(), boards(),
                    100.0 * boardsWithoutSignal() / boards()));
        }
        out.append('\n');

        out.append(String.format(Locale.ROOT, "%-22s %12s %12s%n", "", a.contestant(), b.contestant()));
        row(out, "game pts/game", a.gamePointsPerGame(), b.gamePointsPerGame());
        // Kept below the line it used to be above. Every measurement in
        // docs/ai-training-ground.md was taken in tournament points, and
        // dropping the column would make the record unreadable rather than
        // superseded -- but it is no longer what the match is decided on.
        row(out, "tournament pts/game", a.tournamentPointsPerGame(), b.tournamentPointsPerGame());
        row(out, "  from declaring", a.declarerPointsPerGame(), b.declarerPointsPerGame());
        row(out, "  from defending", a.defenderPointsPerGame(), b.defenderPointsPerGame());
        row(out, "declares", a.declarerRate() * 100, b.declarerRate() * 100, "%");
        row(out, "wins as declarer", a.declarerWinRate() * 100, b.declarerWinRate() * 100, "%");
        row(out, "overbid (lost)", a.overbidRate() * 100, b.overbidRate() * 100, "%");
        row(out, "ramsch", a.ramschRate() * 100, b.ramschRate() * 100, "%");
        out.append(String.format(Locale.ROOT, "%-22s %12d %12d%n",
                "rule violations", a.ruleViolations(), b.ruleViolations()));
        if (!a.violationPhases().isEmpty() || !b.violationPhases().isEmpty()) {
            out.append(String.format(Locale.ROOT, "  in: %-16s %-16s%n",
                    a.violationPhases(), b.violationPhases()));
        }
        if (a.declarerRate() < 0.15 || b.declarerRate() < 0.15) {
            out.append("  ! a contestant below 15% declares is winning by abstaining, not by playing;\n")
                    .append("    its total is defender bonuses and says little about card play.\n");
        }
        if (a.overbidsDespitePoints() > 0 || b.overbidsDespitePoints() > 0) {
            out.append(String.format(Locale.ROOT,
                    "  of which lost with 61+ card points: %d / %d -- a bidding error, not a play error%n",
                    a.overbidsDespitePoints(), b.overbidsDespitePoints()));
        }

        out.append('\n').append("Contracts declared\n");
        out.append(String.format(Locale.ROOT, "  %-20s %12s %12s%n", "", a.contestant(), b.contestant()));
        for (dev.skatklar.demo.Contract contract : dev.skatklar.demo.Contract.DECLARED_GAMES) {
            int left = a.declaredContracts().getOrDefault(contract, 0);
            int right = b.declaredContracts().getOrDefault(contract, 0);
            if (left == 0 && right == 0) continue;
            out.append(String.format(Locale.ROOT, "  %-20s %12d %12d%n", contract.label, left, right));
        }

        double mean = meanDiffPerGame();
        out.append('\n');
        out.append(String.format(Locale.ROOT,
                "%s - %s = %+.3f game pts/game   95%% CI [%+.3f, %+.3f]%n",
                a.contestant(), b.contestant(), mean, ciLowPerGame(), ciHighPerGame()));
        if (significant()) {
            out.append(String.format(Locale.ROOT, "Resolved: %s is stronger.%n",
                    mean > 0 ? a.contestant() : b.contestant()));
        } else {
            long needed = boardsForSignificance();
            out.append("Not resolved: the interval still contains zero.");
            if (needed > 0) {
                out.append(String.format(Locale.ROOT,
                        " About %,d boards would be needed for this edge.", needed));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String label, double left, double right) {
        row(out, label, left, right, "");
    }

    private static void row(StringBuilder out, String label, double left, double right, String unit) {
        out.append(String.format(Locale.ROOT, "%-22s %11.2f%s %11.2f%s%n",
                label, left, unit.isEmpty() ? " " : unit, right, unit.isEmpty() ? " " : unit));
    }

    /** One row per board, for offline analysis of where the difference comes from. */
    public String toCsv() {
        StringBuilder out = new StringBuilder("board,diff_game_points\n");
        for (int i = 0; i < perBoardDiff.length; i++) {
            out.append(i).append(',')
                    .append(String.format(Locale.ROOT, "%.4f",
                            perBoardDiff[i] / gamesPerSideAndBoard))
                    .append('\n');
        }
        return out.toString();
    }

    /** Machine-readable summary, so a training loop can gate on it. */
    public Map<String, Object> summary() {
        return Map.of(
                "a", a.contestant(),
                "b", b.contestant(),
                "boards", boards(),
                "games", games(),
                "diff_game_points_per_game", meanDiffPerGame(),
                "std_error", stdErrorPerGame(),
                "ci_low", ciLowPerGame(),
                "ci_high", ciHighPerGame(),
                "significant", significant());
    }
}
