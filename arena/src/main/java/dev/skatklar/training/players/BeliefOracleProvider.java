package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.demo.search.WorldSource;
import dev.skatklar.training.arena.TableObserver;
import java.util.ArrayList;
import java.util.List;

/**
 * The search player with a belief of adjustable accuracy — an instrument, not a
 * player.
 *
 * <p>It answers the question that decides how much a learned belief model is
 * worth <em>before</em> anyone builds one: sample worlds as usual, but replace a
 * share {@code accuracy} of them with the deal actually on the table. Sweeping
 * that share from 0 to 1 and measuring each setting in the arena produces a curve
 * from belief accuracy to tournament points.
 *
 * <p>The two ends are already known and serve as the sanity check on the middle.
 * At 0 this is exactly {@code search}, the uniform baseline. At 1 every world is
 * the true one, so the vote reduces to a double-dummy decision and the player
 * should land on {@code solver}. What nobody knows is the shape in between, and
 * the shape is the decision:
 *
 * <ul>
 *   <li>If it rises steeply from the left, a rough model bought cheaply from
 *       self-play already pays, and Phase 4 can start on data we generate
 *       ourselves.</li>
 *   <li>If it stays flat until the right, only a genuinely accurate model moves
 *       anything — which makes the ISS archive worth waiting for, and worth
 *       asking again for.</li>
 * </ul>
 *
 * <p>Mixing in the truth is a deliberately crude model of accuracy: a real belief
 * model is wrong in structured ways, being confident about jacks and vague about
 * sevens, while this is right or uniformly wrong with nothing in between. It
 * bounds the answer rather than predicting it, which is all a go/no-go needs.
 */
public final class BeliefOracleProvider implements SkatAiProvider, TableObserver {

    private final SearchAiProvider search;
    private volatile GameEngine engine;

    public BeliefOracleProvider(double accuracy, Personality personality, long seed) {
        this.search = new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                mixingSource(accuracy));
    }

    @Override public void observe(GameEngine engine) {
        this.engine = engine;
    }

    @Override public SkatAi.AiDescriptor descriptor() { return search.descriptor(); }

    @Override public SkatAiSession createSession() { return search.createSession(); }

    /**
     * Uniform sampling, with the truth substituted {@code accuracy} of the time.
     *
     * <p>Substituted rather than added: the world count stays what the
     * personality says, so a run at accuracy 0.5 is not also a run with twice the
     * search effort. Otherwise the curve would measure two things at once.
     */
    private WorldSource mixingSource(double accuracy) {
        double share = Math.max(0, Math.min(1, accuracy));
        return (evidence, count, random) -> {
            List<WorldSampler.World> worlds =
                    WorldSource.UNIFORM.sample(evidence, count, random);
            GameEngine table = engine;
            if (table == null || share == 0) return worlds;

            WorldSampler.World truth = truthFrom(table);
            if (truth == null) return worlds;
            if (worlds.isEmpty()) worlds = new ArrayList<>(List.of(truth));
            for (int at = 0; at < worlds.size(); at++) {
                if (random.nextDouble() < share) worlds.set(at, truth);
            }
            return worlds;
        };
    }

    /** The deal as it actually is, read straight out of the engine. */
    private static WorldSampler.World truthFrom(GameEngine engine) {
        GameEngine.Snapshot snapshot = engine.snapshot();
        if (snapshot.hands == null || snapshot.hands.size() != 3) return null;
        List<List<Card>> hands = new ArrayList<>(3);
        for (List<Card> hand : snapshot.hands) hands.add(new ArrayList<>(hand));
        return new WorldSampler.World(hands, new ArrayList<>(snapshot.skat));
    }

    /** Package-visible for the test that pins the two ends of the sweep. */
    WorldSource sourceForTesting(double accuracy) { return mixingSource(accuracy); }
}
