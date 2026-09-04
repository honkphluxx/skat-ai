package dev.skatklar.training.arena;

import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import java.util.EnumMap;
import java.util.Map;

/** Aggregates one contestant's results over the games in which it is the singleton. */
public final class Tally {
    private final String contestant;
    private int games;
    private int ramschGames;
    private int declared;
    private int declarerWins;
    private int overbids;
    private int overbidsDespitePoints;
    private long tournamentPoints;
    private long declarerTournamentPoints;
    private long defenderTournamentPoints;
    private int defended;
    private long gamePoints;
    private int ruleViolations;
    private final Map<GameEngine.ViolationPhase, Integer> violationPhases =
            new EnumMap<>(GameEngine.ViolationPhase.class);
    private final Map<Contract, Integer> declaredContracts = new EnumMap<>(Contract.class);

    public Tally(String contestant) { this.contestant = contestant; }

    void record(GameOutcome outcome, SkatAi.Seat seat) {
        games++;
        ruleViolations += outcome.ruleViolationsOf(seat);
        for (GameEngine.RuleViolation violation : outcome.violationDetail()) {
            if (violation.seat() == seat) violationPhases.merge(violation.phase(), 1, Integer::sum);
        }
        int points = Scoring.tournamentPoints(outcome, seat);
        tournamentPoints += points;
        gamePoints += Scoring.gamePoints(outcome, seat);
        if (outcome.ramsch()) { ramschGames++; return; }
        if (outcome.isDeclarer(seat)) declarerTournamentPoints += points;
        else { defenderTournamentPoints += points; defended++; }
        if (outcome.isDeclarer(seat)) {
            declared++;
            if (outcome.declarerWon()) declarerWins++;
            if (outcome.overbid()) overbids++;
            if (outcome.overbidDespiteCardPoints()) overbidsDespitePoints++;
            declaredContracts.merge(outcome.contract(), 1, Integer::sum);
        }
    }

    void add(Tally other) {
        games += other.games;
        ramschGames += other.ramschGames;
        declared += other.declared;
        declarerWins += other.declarerWins;
        overbids += other.overbids;
        overbidsDespitePoints += other.overbidsDespitePoints;
        tournamentPoints += other.tournamentPoints;
        declarerTournamentPoints += other.declarerTournamentPoints;
        defenderTournamentPoints += other.defenderTournamentPoints;
        defended += other.defended;
        gamePoints += other.gamePoints;
        ruleViolations += other.ruleViolations;
        other.violationPhases.forEach((phase, count) ->
                violationPhases.merge(phase, count, Integer::sum));
        other.declaredContracts.forEach((contract, count) ->
                declaredContracts.merge(contract, count, Integer::sum));
    }

    public String contestant() { return contestant; }
    public int games() { return games; }
    public int ramschGames() { return ramschGames; }
    public int declared() { return declared; }
    public int declarerWins() { return declarerWins; }
    public int overbids() { return overbids; }
    public int overbidsDespitePoints() { return overbidsDespitePoints; }
    public long tournamentPoints() { return tournamentPoints; }
    public long declarerTournamentPoints() { return declarerTournamentPoints; }
    public long defenderTournamentPoints() { return defenderTournamentPoints; }
    public int defended() { return defended; }
    public long gamePoints() { return gamePoints; }
    public int ruleViolations() { return ruleViolations; }
    /** Which decision the violations happened in; empty when there were none. */
    public Map<GameEngine.ViolationPhase, Integer> violationPhases() { return violationPhases; }
    public Map<Contract, Integer> declaredContracts() { return declaredContracts; }

    public double tournamentPointsPerGame() { return games == 0 ? 0 : (double) tournamentPoints / games; }
    /**
     * Share of the total that came from declaring, and from defending.
     *
     * <p>Both are divided by <em>all</em> games so that they add up to
     * {@link #tournamentPointsPerGame()}. Seeger-Fabian pays a defender 40 every
     * time the declarer goes down, so a player that simply refuses to declare can
     * post a healthy total while never showing whether it plays well. Splitting
     * the number is the only way to see that from the report.
     */
    public double declarerPointsPerGame() { return games == 0 ? 0 : (double) declarerTournamentPoints / games; }
    public double defenderPointsPerGame() { return games == 0 ? 0 : (double) defenderTournamentPoints / games; }
    public double gamePointsPerGame() { return games == 0 ? 0 : (double) gamePoints / games; }
    public double declarerRate() { return games == 0 ? 0 : (double) declared / games; }
    public double declarerWinRate() { return declared == 0 ? 0 : (double) declarerWins / declared; }
    /** Share of declared games where the bid exceeded the value the contract reached. */
    public double overbidRate() { return declared == 0 ? 0 : (double) overbids / declared; }
    /**
     * How often this seat ended up in a Ramsch. The old pass rate under another
     * name, and a more interesting number than it used to be: passing now costs
     * something, so a population's Ramsch rate says how well its bidding is
     * calibrated rather than how shy it is.
     */
    public double ramschRate() { return games == 0 ? 0 : (double) ramschGames / games; }
}
