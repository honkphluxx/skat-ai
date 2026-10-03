package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.training.data.SelfPlayExportMain;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A lost declarer position, decided by playing it out against realistic
 * defenders (docs/training-plan.md 2.10, R).
 *
 * <p>The search's question -- in how many worlds does this card hold the game
 * against perfect play -- has the answer zero for every card here. This asks
 * the one that matters at a table: in how many of the same worlds does the
 * card win when the rest of the game is played by players who can go wrong.
 * Each legal card is played in each of the search's own sampled worlds (up to
 * {@code worlds} of them), and the game finished by the student for all three
 * seats, each seat reading only its own evidence (perfect memory, the bids it
 * heard, the declarer's own discard). The card that wins in the most worlds
 * is played; ties go to the most declarer points over the same worlds (a
 * Schneider avoided is worth something), then to the search's own card.
 *
 * <p>What the student stands for here is the defence at the table, and it is a
 * weaker defender than the players it will meet in the arena. Cards that only
 * win against its mistakes win less there; the pairing is the judge.
 */
public final class RolloutRescue {

    private final PolicyNet student;
    private final int worlds;

    public RolloutRescue(PolicyNet student, int worlds) {
        if (worlds < 1) throw new IllegalArgumentException("at least one world");
        this.student = student;
        this.worlds = worlds;
    }

    /** What the play-outs found for each legal card: worlds won, declarer points summed. */
    record Tally(Card card, int wins, int points) {}

    /**
     * @param seen    the search's own evidence for this decision (its bids and discard)
     * @param sampled the search's own worlds for this decision
     * @param fallback the search's card, kept on a full tie
     */
    Card choose(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                List<WorldSampler.World> sampled, Card fallback) {
        return pick(tally(context, seen, sampled), fallback);
    }

    /** The most worlds won, then the most declarer points; the search's own card on a full tie. */
    static Card pick(List<Tally> tallies, Card fallback) {
        Tally best = null;
        for (Tally t : tallies) {
            if (best == null || t.wins > best.wins || (t.wins == best.wins && t.points > best.points)) best = t;
        }
        if (best == null) return fallback;
        for (Tally t : tallies) {
            if (t.card.equals(fallback) && t.wins == best.wins && t.points == best.points) return fallback;
        }
        return best.card;
    }

    List<Tally> tally(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                      List<WorldSampler.World> sampled) {
        Map<SkatAi.Seat, Integer> bids = seen == null ? Map.of() : seen.highestBids();
        List<Card> buried = seen == null ? null : seen.knownDiscard();
        Playout.Policy policy = at -> student.choose(SelfPlayExportMain.perfectMemory(at, bids, buried),
                new ArrayList<>(at.legalCards));
        List<Tally> out = new ArrayList<>();
        int n = Math.min(worlds, sampled.size());
        for (Card card : context.legalCards) {
            int wins = 0;
            int points = 0;
            for (int w = 0; w < n; w++) {
                WorldSampler.World world = sampled.get(w);
                Playout.Result r = new Playout(context, world.hands(), world.skat()).run(card, policy);
                if (r.declarerWon()) wins++;
                points += r.declarerPoints();
            }
            out.add(new Tally(card, wins, points));
        }
        return out;
    }

    private static PolicyNet cached;

    /** The student from -Dpolicy.model.dir, else policy-model/ here or above; loaded once. */
    public static synchronized PolicyNet student() {
        if (cached != null) return cached;
        String named = System.getProperty("policy.model.dir");
        List<Path> candidates = named != null && !named.isBlank() ? List.of(Path.of(named))
                : List.of(Path.of("policy-model"), Path.of("..", "policy-model"), Path.of("..", "..", "policy-model"));
        for (Path dir : candidates) {
            if (!Files.isRegularFile(dir.resolve("policy.bin"))) continue;
            try (InputStream weights = Files.newInputStream(dir.resolve("policy.bin"));
                 InputStream parity = Files.newInputStream(dir.resolve("policy-parity.bin"))) {
                cached = PolicyNet.load(weights, parity);
                return cached;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("no policy-model/policy.bin for the play-outs' student");
    }
}
