package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.BeliefModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The learned belief, wired into the search.
 *
 * <p>Phase 4's payoff, and the point where a log loss becomes tournament points
 * or does not. In core rather than in the training module because the app runs
 * this too: one world source, measured in the arena and shipped on the phone,
 * so that the number the arena prints is a number about the product. The search is untouched: it still draws worlds, still solves each
 * one double-dummy, still votes. Only <em>which</em> consistent worlds it draws
 * changes, and only through {@link WorldSampler.Weights}, so a comparison
 * against {@link WorldSource#UNIFORM} isolates the belief and nothing else.
 *
 * <p>Two properties are worth stating because they are what make the comparison
 * fair rather than flattering:
 *
 * <ul>
 *   <li>The features come from the seat's own {@link BeliefEncoding.Evidence} —
 *       the same object the exporter recorded and the trainer was fed, filtered
 *       by the same forgetting. A player with a poor memory hands the model the
 *       same partial picture it hands the search.</li>
 *   <li>Legality is not the model's business. Capacities, voids and the two skat
 *       slots are enforced by the sampler above the weights, so a confidently
 *       wrong model produces a player that guesses badly, never one that draws an
 *       impossible deal.</li>
 * </ul>
 *
 * <p>When the model refuses — a bad file, a shape mismatch, a runtime that will
 * not load — this falls back to the uniform draw for that decision rather than
 * ending the match. A belief that is unavailable should cost points, not a
 * night's measurement.
 */
public final class BeliefWorldSource implements WorldSource {

    private final BeliefModel model;
    /**
     * How sharply the model's opinion is taken, as an exponent on the weights.
     *
     * <p>One is the model as trained. Below one it is softened towards uniform,
     * above one it is sharpened towards its argmax. Present because the useful
     * setting is an empirical question and a cheap one to sweep: the belief sweep
     * showed the curve flattening early, since correct worlds vote as a bloc
     * while wrong ones scatter, and that is an argument for sharpening a model
     * that is right more often than not.
     */
    private final double sharpness;

    public BeliefWorldSource(BeliefModel model) {
        this(model, 1.0);
    }

    public BeliefWorldSource(BeliefModel model, double sharpness) {
        this.model = model;
        this.sharpness = sharpness;
    }

    @Override
    public List<WorldSampler.World> sample(BeliefEncoding.Evidence evidence,
                                           int count, Random random) {
        // Null is a contract the model has never seen. Counted over the shipped
        // training set: 0 Null decision points in 89 438, because no player this
        // project ships has ever announced one -- and a Null game also presents
        // every trump-derived feature as zero, so it is not merely a rare input
        // but an unvisited corner of the space. An unvisited corner does not
        // give a weak answer, it gives an arbitrary one, so the honest baseline
        // is asked instead. Remove this the run after a model is trained on
        // games that include them.
        if (evidence.context().game.contract.isNull()) {
            return WorldSource.UNIFORM.sample(evidence, count, random);
        }
        double[][] belief = beliefFor(evidence);
        if (belief == null) return WorldSource.UNIFORM.sample(evidence, count, random);

        // The observer comes from the position being decided, not from a field.
        // Every seat asks this source about its own decisions, and one instance
        // serves all three of them across several threads, so anything
        // seat-dependent that lives on the object is a race waiting for a long
        // run to find it.
        SkatAi.Seat observer = evidence.context().mySeat;
        WorldSampler sampler = WorldSampler.forDecision(evidence.context(),
                        WorldSource.knownSkat(evidence), evidence.rememberedPlays(),
                        evidence.rememberedVoids())
                .weightedBy((card, place) -> weight(belief, observer, card, place));

        List<WorldSampler.World> worlds = new ArrayList<>(count);
        for (int drawn = 0; drawn < count; drawn++) {
            WorldSampler.World world = sampler.draw(random);
            if (world != null) worlds.add(world);
        }
        return worlds;
    }

    /**
     * The model's answer as probabilities, or null when it could not give one.
     *
     * <p>Everything the model can do wrong is caught here rather than at the call
     * site: a wrong-sized output, a NaN, a runtime that throws. The player then
     * reasons uniformly for that decision, which is a measurable loss of strength
     * and not a crash.
     */
    private double[][] beliefFor(BeliefEncoding.Evidence evidence) {
        float[] features;
        float[] logits;
        try {
            features = BeliefEncoding.encode(evidence);
            if (model.inputs() > 0 && model.inputs() != features.length) return null;
            logits = model.logits(features);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (logits == null || logits.length != 32 * BeliefEncoding.CLASSES) return null;

        double[][] belief = new double[32][BeliefEncoding.CLASSES];
        for (int card = 0; card < 32; card++) {
            int at = card * BeliefEncoding.CLASSES;
            double highest = Double.NEGATIVE_INFINITY;
            for (int place = 0; place < BeliefEncoding.CLASSES; place++) {
                highest = Math.max(highest, logits[at + place]);
            }
            if (!Double.isFinite(highest)) return null;
            double total = 0;
            for (int place = 0; place < BeliefEncoding.CLASSES; place++) {
                double value = Math.exp(logits[at + place] - highest);
                belief[card][place] = value;
                total += value;
            }
            for (int place = 0; place < BeliefEncoding.CLASSES; place++) {
                belief[card][place] = Math.pow(belief[card][place] / total, sharpness);
            }
        }
        return belief;
    }

    /**
     * Translates a place the sampler understands into a class the model speaks.
     *
     * <p>The sampler numbers places by seat ordinal with 3 for the skat; the
     * model says left, right, skat <em>relative to the observer</em>, which is
     * how the encoding was trained and why the same net serves all three seats.
     * Left is the seat that plays after the observer. The observer's own seat
     * never arrives here — the sampler does not offer a place for cards that are
     * already in the hand it is dealing around.
     */
    private static double weight(double[][] belief, SkatAi.Seat observer, Card card, int place) {
        int index = BeliefEncoding.index(card);
        if (place == 3) return belief[index][BeliefEncoding.CLASS_SKAT];
        if (place == observer.next().ordinal()) return belief[index][BeliefEncoding.CLASS_LEFT];
        return belief[index][BeliefEncoding.CLASS_RIGHT];
    }
}
