package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.belief.BeliefEncoding;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where a search player's hypotheses come from.
 *
 * <p>The seam between "what do I think the hidden cards are" and "what do I do
 * about it". Splitting it out is the whole point of the phase plan: the search
 * stays the same and the belief is replaced, so the difference between two
 * beliefs is measured in tournament points on identical boards rather than in
 * log-loss on a validation set.
 *
 * <p>Three implementations are foreseen, and the arena can seat all of them:
 *
 * <ul>
 *   <li>{@link #UNIFORM} — every arrangement consistent with the observations is
 *       equally likely. The honest baseline.</li>
 *   <li>A <b>belief oracle</b> that mixes in the true deal a fixed share of the
 *       time. It cheats, so it belongs in the training module, and it exists to
 *       answer one question before a model is built: how much is accuracy
 *       actually worth?</li>
 *   <li>A <b>learned model</b>, which is Phase 4.</li>
 * </ul>
 */
@FunctionalInterface
public interface WorldSource {

    /**
     * Up to {@code count} deals the player is willing to reason about.
     *
     * <p>Takes the seat's whole {@link BeliefEncoding.Evidence} rather than the
     * three fields the uniform sampler happens to need. A learned belief reads
     * the auction, the Schieben and the doubling as well, and threading those
     * through as extra parameters would have meant either a signature nobody can
     * read or a second channel for the model to receive them on. One object that
     * means "everything this seat knows" is also exactly what the exporter
     * records and what the trainer was fed, which is the property that keeps the
     * two halves honest.
     *
     * @return possibly fewer than {@code count} worlds, or an empty list when the
     *         position admits none; never null
     */
    List<WorldSampler.World> sample(BeliefEncoding.Evidence evidence, int count, Random random);

    /**
     * The two cards this seat knows are buried, or null.
     *
     * <p>Two seats can know: a declarer that discarded them, and in a Ramsch the
     * rearhand whose push became the skat. The second one is new -- before the
     * evidence was passed whole, the sampler had no way to hear about it and
     * spent every draw guessing at two cards the player was holding in memory.
     */
    static List<Card> knownSkat(BeliefEncoding.Evidence evidence) {
        if (evidence.knownDiscard() != null && evidence.knownDiscard().size() == 2) {
            return evidence.knownDiscard();
        }
        if (evidence.schieben() != null && evidence.schieben().toSkat()) {
            return evidence.schieben().pushedOn();
        }
        return null;
    }

    /** Uniform over everything not ruled out. */
    WorldSource UNIFORM = (evidence, count, random) -> {
        WorldSampler sampler = WorldSampler.forDecision(evidence.context(),
                knownSkat(evidence), evidence.rememberedPlays(), evidence.rememberedVoids());
        List<WorldSampler.World> worlds = new ArrayList<>(count);
        for (int drawn = 0; drawn < count; drawn++) {
            WorldSampler.World world = sampler.draw(random);
            if (world != null) worlds.add(world);
        }
        return worlds;
    };
}
