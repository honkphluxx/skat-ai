package dev.skatklar.training.arena;

/**
 * Deterministic seed derivation. Every random draw in a match must be a pure
 * function of the match seed and the coordinates of the game, so that a result
 * can be reproduced exactly and a suspicious board can be replayed on its own.
 */
public final class Seeds {
    private Seeds() {}

    /** SplitMix64 finalizer, applied to a folded tuple. */
    public static long mix(long... parts) {
        long z = 0x9E3779B97F4A7C15L;
        for (long part : parts) {
            z += part * 0x9E3779B97F4A7C15L;
            z ^= z >>> 30;
            z *= 0xBF58476D1CE4E5B9L;
            z ^= z >>> 27;
            z *= 0x94D049BB133111EBL;
            z ^= z >>> 31;
        }
        return z;
    }
}
