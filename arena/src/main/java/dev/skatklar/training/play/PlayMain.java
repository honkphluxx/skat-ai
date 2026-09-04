package dev.skatklar.training.play;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Scoring;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Deals a hand and lets you play it, against any player the arena knows.
 *
 * <pre>
 *   ./gradlew :arena:play
 *   ./gradlew :arena:play --args="--opponent=belief --deals=10 --seed=7"
 * </pre>
 *
 * <p>The opponent is named by its <em>arena id</em>, and that is the point of
 * this entry point rather than a nicety. Every strength claim in this repository
 * is a claim about a contestant with a name -- {@code belief} is 2.1 game points
 * above {@code search}, {@code belief-32} is level with JSkat's transformer --
 * and the player you sit down against here is that same object, built by the
 * same factory from the same seed. There is no separate "app opponent" to drift
 * from the measured one, because there is no second implementation.
 *
 * <p>Everything else is the shipped engine: {@link GameRunner} is what the arena
 * runs its matches through, and it is used unchanged. Your bids, your discard
 * and your cards go through the same legality checks as any player's, and an
 * illegal card is refused here exactly as it would be in a match.
 *
 * <h2>What the running total is and is not</h2>
 *
 * <p>Game points, as the arena scores them, accumulated over the deals you play.
 * That makes it comparable in <em>units</em> to every table in the README, and
 * comparable in nothing else. The arena's numbers come from duplicate boards
 * with common random numbers, where both sides play the same deals from both
 * sides and almost all of the luck cancels; a run of ten single deals is mostly
 * luck. Thirty deals will tell you whether you are being beaten badly; it will
 * not resolve two game points, and neither would three hundred played this way.
 *
 * <p>That is a limitation of playing one deal at a time, not of the arena. If
 * you want a real anchor, {@code ConsolePlayer} is registered as {@code human}
 * and can be entered into a duplicate match -- with the caveat, written down in
 * that class, that you would see every board twice and the AI would not.
 */
public final class PlayMain {

    private PlayMain() {}

    public static void main(String[] args) {
        String opponentId = "belief";
        int deals = 5;
        long seed = System.nanoTime();

        for (String arg : args) {
            if (arg.startsWith("--opponent=")) opponentId = value(arg);
            else if (arg.startsWith("--deals=")) deals = Integer.parseInt(value(arg));
            else if (arg.startsWith("--seed=")) seed = Long.parseLong(value(arg));
            else if (arg.equals("--help") || arg.equals("-h")) { usage(null); return; }
            else { usage("unrecognised argument: " + arg); return; }
        }

        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant opponent;
        try {
            opponent = registry.resolve(opponentId);
        } catch (RuntimeException unknown) {
            // The belief players exist only when a model is on disk. One ships
            // in belief-model/, so the default normally resolves -- but a
            // checkout that removed it should be told what it does have rather
            // than handed a stack trace.
            usage(unknown.getMessage() + "\nAvailable: " + String.join(", ", registry.ids()));
            return;
        }

        Console console = new Console();
        console.say("SkatKlar -- you against " + opponent.displayName()
                + " (" + opponent.id() + ")");
        console.say("Cards are named suit-then-rank: CJ SA H10 D7. "
                + "Ctrl-D finishes the hand for you.");
        console.say("Seed " + seed + ", " + deals + " deal" + (deals == 1 ? "" : "s") + ".");

        ConsolePlayer human = new ConsolePlayer(console);
        int gameTotal = 0;
        int tournamentTotal = 0;
        int declared = 0;
        int played = 0;
        for (int index = 0; index < deals; index++) {
            Board board = Board.of(seed, index);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            seating.put(SkatAi.Seat.HUMAN, human);
            // A provider per seat with its own derived seed: two seats sharing
            // one sampling stream is a bug the arena has been bitten by, and the
            // fix belongs everywhere the engine is driven, not only in matches.
            seating.put(SkatAi.Seat.OPPONENT_ONE, opponent.newProvider(seed * 31L + index * 2L));
            seating.put(SkatAi.Seat.OPPONENT_TWO, opponent.newProvider(seed * 31L + index * 2L + 1));

            GameOutcome outcome = GameRunner.play(board, seating, seed ^ (index * 0x9E3779B9L));
            gameTotal += Scoring.gamePoints(outcome, SkatAi.Seat.HUMAN);
            tournamentTotal += Scoring.tournamentPoints(outcome, SkatAi.Seat.HUMAN);
            if (outcome.isDeclarer(SkatAi.Seat.HUMAN)) declared++;
            played++;
            // Both numbers, because either one alone misleads over a handful of
            // deals. A score sheet only ever writes in the declarer's column, so
            // a person who has not declared yet is on nought however well they
            // have defended; Seeger-Fabian pays a defender 40 for beating a
            // contract, which is feedback but is not what the canon settles in.
            console.say("  Running: " + signed(gameTotal) + " game points, "
                    + signed(tournamentTotal) + " tournament points, over "
                    + played + " deal" + (played == 1 ? "" : "s")
                    + " (" + declared + " declared).");
            if (console.closed()) {
                console.say("  (input ended -- stopping here)");
                break;
            }
        }

        console.rule("Done");
        console.say("  " + played + " deal" + (played == 1 ? "" : "s")
                + ", " + declared + " of them declared by you.");
        console.say("  Game points:       " + signed(gameTotal)
                + String.format(Locale.ROOT, "  (%+.2f a deal)",
                        played == 0 ? 0.0 : (double) gameTotal / played));
        console.say("  Tournament points: " + signed(tournamentTotal)
                + String.format(Locale.ROOT, "  (%+.2f a deal)",
                        played == 0 ? 0.0 : (double) tournamentTotal / played));
        console.say("  Mostly luck, at this length. See the class comment on "
                + "PlayMain for why that is not a measurement.");
    }

    private static String signed(int points) {
        return (points >= 0 ? "+" : "") + points;
    }

    private static String value(String argument) {
        return argument.substring(argument.indexOf('=') + 1);
    }

    private static void usage(String complaint) {
        if (complaint != null) System.out.println(complaint + "\n");
        System.out.println("""
                Play Skat against a measured opponent.

                  --opponent=ID   any arena contestant; default `belief`, the
                                  measured player with the trained model that
                                  ships in belief-model/. `club`, `expert` and
                                  `analyst` are the product levels; `greedy` and
                                  `random` are the baselines you should beat.
                  --deals=N       how many hands to deal (default 5)
                  --seed=N        fixes the deals, so a hand can be replayed

                  ./gradlew :arena:play --args="--opponent=club --deals=3"
                """);
    }
}
