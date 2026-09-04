package dev.skatklar.training.arena;

import dev.skatklar.demo.ai.SkatAiProvider;

/**
 * A named AI implementation entered into a match.
 *
 * <p>A contestant is a factory rather than a single provider on purpose: a
 * provider that carries a {@link java.util.Random} (like
 * {@code LegalRandomAiProvider}) would otherwise share one stream across seats
 * and across games, which destroys both reproducibility and thread safety. Each
 * seat in each game gets its own provider with a derived seed.
 */
public interface Contestant {

    /** Stable identifier used on the command line and in CSV output. */
    String id();

    default String displayName() { return id(); }

    /** Creates an independent provider seeded from {@code seed}. */
    SkatAiProvider newProvider(long seed);
}
