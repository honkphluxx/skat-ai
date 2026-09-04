package dev.skatklar.demo.belief;

/**
 * The learned belief, reduced to the one thing the search needs from it.
 *
 * <p>An interface with a single method, and that is deliberate: on the other
 * side of it sits whatever runs the arithmetic -- {@link BeliefNet} in the app,
 * ONNX Runtime in the training module -- and on this side sits everything that
 * decides how a probability turns into a sampled world. It lives in core rather
 * than beside the loaders because the phone build has to be able to see it. Splitting them means the interesting half can be tested
 * against a stub — a model that always says "left", a model that says nothing —
 * without a runtime, a GPU or a trained file, which is exactly the half where a
 * mistake would be silent.
 */
@FunctionalInterface
public interface BeliefModel {

    /**
     * Class scores for every card, as {@code 32 x 3} in row-major order.
     *
     * <p>The three are left, right, skat, in the order {@link BeliefEncoding}
     * labels them. Raw logits are fine; the caller applies the softmax, because
     * whether a model exports one is a property of how it was traced.
     */
    float[] logits(float[] features);

    /** How wide an input this model expects, so a mismatch fails loudly. */
    default int inputs() { return -1; }
}
