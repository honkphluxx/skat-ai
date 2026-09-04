package dev.skatklar.training.arena;

import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The engine-reported result of one arena game, flattened for scoring and CSV. */
public record GameOutcome(SkatAi.RoundPosition round,
                          boolean ramsch,
                          SkatAi.Seat declarer,
                          Contract contract,
                          int bidValue,
                          boolean declarerWon,
                          int declarerPoints,
                          int gameValue,
                          boolean overbid,
                          Map<SkatAi.Seat, Integer> ruleViolations,
                          List<GameEngine.RuleViolation> violationDetail,
                          SkatAi.Seat scoredSeat,
                          boolean jungfrau,
                          boolean durchmarsch) {

    /**
     * A deal nobody took is no longer a deal nobody plays: everybody passing is
     * a Ramsch, so this replaces the old pass-in outcome rather than joining it.
     * {@code declarer} is null there and {@code scoredSeat} carries the loser, or
     * whoever marched through.
     */
    public static GameOutcome of(SkatAi.GameResult result,
                                 Map<SkatAi.Seat, Integer> ruleViolations,
                                 List<GameEngine.RuleViolation> detail) {
        return new GameOutcome(result.game.round, result.game.isRamsch(),
                result.game.declarer,
                result.game.contract, result.game.bidValue, result.declarerWon,
                result.declarerPoints, result.gameValue, result.overbid,
                seatMap(ruleViolations), List.copyOf(detail),
                result.scoredSeat, result.jungfrau, result.durchmarsch);
    }

    /** {@code new EnumMap<>(map)} rejects an empty non-enum map, hence the copy. */
    private static Map<SkatAi.Seat, Integer> seatMap(Map<SkatAi.Seat, Integer> source) {
        EnumMap<SkatAi.Seat, Integer> copy = new EnumMap<>(SkatAi.Seat.class);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    /** Violations caused by {@code seat}, not by whoever else sat at the table. */
    public int ruleViolationsOf(SkatAi.Seat seat) {
        return ruleViolations.getOrDefault(seat, 0);
    }

    public boolean isDeclarer(SkatAi.Seat seat) {
        return !ramsch && declarer == seat;
    }

    /** The seat a Ramsch was charged to, or won by. */
    public boolean isScored(SkatAi.Seat seat) { return scoredSeat == seat; }

    /**
     * The unsigned value the game was charged at. {@code gameValue} arrives
     * signed and doubled on a loss, which is the score-sheet convention. For an
     * overbid game this is the multiple of the base value that reaches the bid,
     * not the value the contract actually achieved.
     */
    public int chargedValue() {
        if (ramsch) return Math.abs(gameValue);
        return declarerWon ? gameValue : -gameValue / 2;
    }

    /**
     * True when the declarer took at least 61 card points and still lost, purely
     * because the bid exceeded the contract's value. Worth separating from an
     * ordinary loss: it is a bidding error, not a play error, and a contestant
     * that shows a high rate here has a hand-evaluation problem.
     */
    public boolean overbidDespiteCardPoints() {
        return overbid && declarerPoints >= 61;
    }
}
