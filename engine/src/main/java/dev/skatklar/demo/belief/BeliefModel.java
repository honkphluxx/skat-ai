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

    /**
     * What a source that cannot say was taken to mean before it could.
     *
     * <p>Every contract except Null, because that is precisely the rule this
     * field replaced: the sampler consulted the model on everything and refused
     * on a Null. Reading an old model this way is therefore not a guess, it is
     * the old behaviour written down, and nothing that already works changes.
     * The alternative — reading silence as "saw nothing" — would have disabled
     * the belief for every contract on every model shipped to date, which is a
     * far larger change than the one intended and would have arrived silently.
     */
    int LEGACY_CONTRACTS = ~(1 << 5) & 0x7F;   // all seven contracts but NULL

    /**
     * Which contracts this model was actually trained on, as a bit per
     * {@link dev.skatklar.demo.Contract} ordinal.
     *
     * <p>This exists because the alternative was a hardcoded rule. Null was
     * absent from every corpus, so the sampler simply refused to consult the
     * model on a Null — correct at the time, and invisible once it stopped
     * being correct. A night spent measuring a Null-trained model against the
     * shipped one came back at exactly +0.000 on 539 of 539 boards, because
     * both sides were being sent to the uniform sampler and the thing under
     * test was never consulted. A fact about a model belongs in the model.
     *
     * <p>What the caller does with it: a model asked about a contract it has
     * never met does not give a weak answer, it gives an arbitrary one, so
     * {@link BeliefWorldSource} asks the honest baseline instead.
     */
    default int trainedOnContracts() { return LEGACY_CONTRACTS; }

    /** The bit {@link #trainedOnContracts} uses for one contract. */
    static int contractBit(dev.skatklar.demo.Contract contract) {
        return 1 << contract.ordinal();
    }

    /** Whether this model has ever been shown the given contract. */
    default boolean sawContract(dev.skatklar.demo.Contract contract) {
        return (trainedOnContracts() & contractBit(contract)) != 0;
    }
}
