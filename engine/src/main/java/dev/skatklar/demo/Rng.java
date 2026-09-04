package dev.skatklar.demo;

import java.security.SecureRandom;
import java.util.Random;

/**
 * The generator every card distribution comes from.
 *
 * <p><b>Why this exists.</b> {@link java.util.Random} is a 48-bit linear
 * congruential generator. That is not a quality judgement about its output
 * bits — it is a counting argument about the deck. A shuffle of thirty-two
 * cards has {@code 32! ≈ 2.6 × 10^35 ≈ 2^117.6} possible outcomes, and a
 * generator with 48 bits of state can be in at most {@code 2^48} distinct
 * conditions. Seeded from any one state it can therefore produce at most
 * {@code 2^48} of those shuffles — roughly one deal in every 10^20 that exists
 * is reachable at all. Every other deal is not merely unlikely; it can never be
 * dealt. On top of that, an LCG's low bits are famously weak, and
 * {@code Collections.shuffle} leans on exactly those through
 * {@code nextInt(bound)}.
 *
 * <p><b>What this is.</b> xoshiro256++ by Blackman and Vigna: four 64-bit words
 * of state, period {@code 2^256 − 1}, and it passes the whole of BigCrush and
 * PractRand. {@code 2^256} states against {@code 2^117.6} shuffles means the
 * state space is no longer the constraint — the seed is, and a fresh generator
 * takes all 256 bits of it from {@link SecureRandom}. The bounded draw below is
 * Lemire's multiply-shift with the rejection step included, so it is exactly
 * uniform rather than nearly uniform, which is the other half of an honest
 * shuffle.
 *
 * <p>This is deliberately <em>not</em> a cryptographic generator: watch enough
 * output and the state can be recovered. Nothing in the offline game needs
 * secrecy from the person holding the phone. The server, which deals to remote
 * strangers who could profit from predicting it, keeps {@link SecureRandom} and
 * should.
 *
 * <p><b>Reproducibility.</b> {@link #withSeed(long)} expands a single long
 * through SplitMix64 exactly as the reference implementation prescribes, so a
 * seed replays a match card for card. {@link #split()} and {@link #jump()}
 * produce provably non-overlapping streams, which is what a fixture that needs
 * several independent generators from one seed should use instead of seeding
 * each one with {@code seed + 1}.
 *
 * <p>Draws are synchronized, matching {@link java.util.Random}'s own
 * thread-safety contract, because a generator handed to a background search and
 * to the dealer must not tear its state between them.
 */
public final class Rng extends Random {

    private static final long serialVersionUID = 1L;

    /** The golden-ratio increment SplitMix64 is defined with. */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /**
     * Advances the state by {@code 2^128} draws — Blackman and Vigna's published
     * jump polynomial for xoshiro256++.
     */
    private static final long[] JUMP = {
            0x180EC6D33CFD0ABAL, 0xD5A61266F0C9392CL,
            0xA9582618E03FC9AAL, 0x39ABDC4529B1661CL
    };

    /** As {@link #JUMP}, but {@code 2^192} draws. */
    private static final long[] LONG_JUMP = {
            0x76E15D3EFEFDCBBFL, 0xC5004E441C522FB3L,
            0x77710069854EE241L, 0x39109BB02ACBE635L
    };

    // No field initialisers. Random's constructor calls setSeed() on a subclass
    // before this class's initialisers would run, so anything assigned here
    // would silently overwrite the state that call had just installed.
    private long s0;
    private long s1;
    private long s2;
    private long s3;

    /**
     * A generator seeded from {@link SecureRandom}: 256 bits of real entropy, so
     * two runs of the app share no part of their deal sequence.
     */
    public static Rng seeded() {
        // nextBytes rather than generateSeed: the latter goes to the platform's
        // seed generator, which on a headless JVM can block on an empty entropy
        // pool. The arena and the training tools run there.
        byte[] entropy = new byte[32];
        Entropy.SOURCE.nextBytes(entropy);
        long[] state = new long[4];
        for (int word = 0; word < 4; word++) {
            long value = 0L;
            for (int b = 0; b < 8; b++) {
                value = (value << 8) | (entropy[word * 8 + b] & 0xFFL);
            }
            state[word] = value;
        }
        return withState(state);
    }

    /** A reproducible generator: the same seed always deals the same cards. */
    public static Rng withSeed(long seed) {
        return new Rng(seed);
    }

    /**
     * A generator on an explicit state. All four words zero is the one state
     * xoshiro cannot leave, so it is replaced rather than accepted.
     */
    public static Rng withState(long[] state) {
        if (state == null || state.length != 4) {
            throw new IllegalArgumentException("xoshiro256++ takes four state words");
        }
        Rng rng = new Rng(0L);
        rng.install(state[0], state[1], state[2], state[3]);
        return rng;
    }

    /**
     * An independent generator drawn from an existing one.
     *
     * <p>For any {@link Rng} this is a real stream split: the child starts
     * {@code 2^128} draws further along, so the two cannot overlap however long
     * either runs. For any other {@link Random} it falls back to seeding from
     * that generator's own output, which is only as good as the source but is
     * still better than sharing one stream between the dealer and the players.
     */
    public static Rng derive(Random source) {
        if (source instanceof Rng rng) return rng.split();
        if (source == null) return seeded();
        long[] state = new long[4];
        for (int word = 0; word < 4; word++) state[word] = source.nextLong();
        if ((state[0] | state[1] | state[2] | state[3]) == 0L) return seeded();
        return withState(state);
    }

    public Rng(long seed) {
        super(seed);
    }

    /**
     * Expands one long into the four state words, through SplitMix64.
     *
     * <p>Called by {@link Random}'s constructor before this class's own
     * constructor body runs. That is the reason the state fields have no
     * initialisers, and the reason this method must not depend on anything else
     * being set up yet.
     */
    @Override
    public synchronized void setSeed(long seed) {
        long x = seed;
        long[] state = new long[4];
        for (int word = 0; word < 4; word++) {
            x += GOLDEN_GAMMA;
            long z = x;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            state[word] = z ^ (z >>> 31);
        }
        install(state[0], state[1], state[2], state[3]);
    }

    private synchronized void install(long w0, long w1, long w2, long w3) {
        if ((w0 | w1 | w2 | w3) == 0L) {
            // The all-zero state is a fixed point: it would emit zeros forever.
            w0 = GOLDEN_GAMMA;
            w1 = 0xBF58476D1CE4E5B9L;
            w2 = 0x94D049BB133111EBL;
            w3 = 0x2545F4914F6CDD1DL;
        }
        s0 = w0;
        s1 = w1;
        s2 = w2;
        s3 = w3;
    }

    /** The raw generator: one 64-bit word, uniform over the whole range. */
    @Override
    public synchronized long nextLong() {
        long result = Long.rotateLeft(s0 + s3, 23) + s0;
        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);
        return result;
    }

    /**
     * The hook every inherited method of {@link Random} is built on, so anything
     * this class does not override still draws from xoshiro rather than from the
     * congruential generator underneath.
     */
    @Override
    protected int next(int bits) {
        return (int) (nextLong() >>> (64 - bits));
    }

    @Override
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /**
     * A uniform value in {@code [0, bound)} — Lemire's multiply-shift, including
     * the rejection step.
     *
     * <p>{@code Collections.shuffle} is nothing but a loop of these, so a
     * shuffle is only as unbiased as this method is. The cheap alternatives are
     * not: {@code nextInt() % bound} skews towards the low values whenever the
     * bound does not divide {@code 2^32}, and for a 32-card deck it does not
     * divide it for 27 of the 31 draws.
     */
    @Override
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        long product = (nextLong() >>> 32) * bound;
        long low = product & 0xFFFFFFFFL;
        if (low < bound) {
            // 2^32 mod bound, computed without needing 2^32 itself.
            long threshold = Integer.toUnsignedLong(-bound) % bound;
            while (low < threshold) {
                product = (nextLong() >>> 32) * bound;
                low = product & 0xFFFFFFFFL;
            }
        }
        return (int) (product >>> 32);
    }

    @Override
    public boolean nextBoolean() {
        return nextLong() < 0L;
    }

    /** The 53 significant bits of a double, which is every value it can hold. */
    @Override
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    @Override
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    @Override
    public void nextBytes(byte[] bytes) {
        if (bytes == null) return;
        int index = 0;
        while (index < bytes.length) {
            long word = nextLong();
            int take = Math.min(8, bytes.length - index);
            for (int b = 0; b < take; b++) {
                bytes[index++] = (byte) word;
                word >>>= 8;
            }
        }
    }

    /**
     * A generator whose stream provably never meets this one's.
     *
     * <p>The child continues from here and this generator jumps {@code 2^128}
     * draws ahead, so the child owns everything before that point and this one
     * owns everything after it. Neither can reach the other's region: no run of
     * a card game is {@code 2^128} draws long. Two generators seeded {@code seed}
     * and {@code seed + 1} carry no such guarantee, which is why a fixture that
     * wants several streams from one seed should split rather than increment.
     */
    public synchronized Rng split() {
        Rng child = withState(new long[]{s0, s1, s2, s3});
        jump();
        return child;
    }

    /** Advances by {@code 2^128} draws. */
    public synchronized void jump() {
        applyJump(JUMP);
    }

    /** Advances by {@code 2^192} draws, for splitting a split. */
    public synchronized void longJump() {
        applyJump(LONG_JUMP);
    }

    private void applyJump(long[] polynomial) {
        long w0 = 0L;
        long w1 = 0L;
        long w2 = 0L;
        long w3 = 0L;
        for (long word : polynomial) {
            for (int bit = 0; bit < 64; bit++) {
                if ((word & (1L << bit)) != 0L) {
                    w0 ^= s0;
                    w1 ^= s1;
                    w2 ^= s2;
                    w3 ^= s3;
                }
                nextLong();
            }
        }
        install(w0, w1, w2, w3);
    }

    /** The four state words, for a fixture that wants to resume exactly here. */
    public synchronized long[] state() {
        return new long[]{s0, s1, s2, s3};
    }

    /** Held apart so that constructing a seeded generator is the only cost. */
    private static final class Entropy {
        static final SecureRandom SOURCE = new SecureRandom();

        private Entropy() {}
    }
}
