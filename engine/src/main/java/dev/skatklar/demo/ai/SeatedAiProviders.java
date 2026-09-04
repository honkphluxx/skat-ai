package dev.skatklar.demo.ai;

import java.util.Map;
import java.util.Objects;

/**
 * Supplies a possibly different AI implementation per seat.
 *
 * <p>The app and the server seat one implementation at every AI seat and use
 * {@link #uniform(SkatAiProvider)}. Tooling that compares two implementations
 * against each other needs them at the same table, which is the only reason this
 * indirection exists.
 */
@FunctionalInterface
public interface SeatedAiProviders {

    /** Never returns {@code null} for a seat the engine will actually ask. */
    SkatAiProvider providerFor(SkatAi.Seat seat);

    /** The ordinary case: one implementation occupies every AI seat. */
    static SeatedAiProviders uniform(SkatAiProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return seat -> provider;
    }

    /** Seats a fixed assignment; the map must cover every seat the engine asks. */
    static SeatedAiProviders of(Map<SkatAi.Seat, SkatAiProvider> bySeat) {
        Objects.requireNonNull(bySeat, "bySeat");
        return seat -> {
            SkatAiProvider provider = bySeat.get(seat);
            if (provider == null) {
                throw new IllegalStateException("No AI provider seated at " + seat);
            }
            return provider;
        };
    }
}
