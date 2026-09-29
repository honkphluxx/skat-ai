package dev.skatklar.demo.belief;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The student: which card would the teacher play here? (docs/training-plan.md 2.10, I3-I4)
 *
 * <p>A network trained on 1.2 million card decisions of the 64-world search
 * player, reading the same input the belief net reads -- the seat's own
 * evidence, {@link BeliefEncoding} -- and answering with a score per card. One
 * forward pass where the teacher solves 64 worlds, which is the point: on the
 * held-out deals it plays the first two tricks about as well as the 32-world
 * search and the last three about as badly as a one-world one, so it is used
 * for the opening and the search does the rest (see
 * {@code SearchAiProvider#withOpening}).
 *
 * <p>The trunk is the belief net's, read by {@link BeliefNet}'s own loader; the
 * head has 64 outputs, the policy's 32 scores then the value head's 32, both
 * by card index. Only the policy is used to play. The file is
 * {@code policy.bin}, written by {@code arena/python/export_policy.py}, with the
 * trainer's own answers beside it in {@code policy-parity.bin}, which
 * {@link #load} replays before the weights may play.
 *
 * <p>Thread-safe, like the belief net: nothing is kept between calls.
 */
public final class PolicyNet {

    /** File magic: "SKPW", SkatKlar policy weights. */
    public static final int MAGIC = 0x534B5057;
    /** The policy's 32 card scores, then the value head's 32. */
    public static final int OUTPUTS = 64;

    private final BeliefNet net;

    private PolicyNet(BeliefNet net) {
        this.net = net;
    }

    /**
     * Reads the weights and, when given, replays the trainer's cases through
     * them; throws if either the shape or the answers are wrong. Neither stream
     * is closed.
     *
     * @param parity {@code policy-parity.bin}, or null to load unverified
     */
    public static PolicyNet load(InputStream weights, InputStream parity) throws IOException {
        BeliefNet net = BeliefNet.load(weights, MAGIC, "policy");
        if (net.outputs() != OUTPUTS) {
            throw new IOException("a policy has " + OUTPUTS + " outputs, this file " + net.outputs());
        }
        if (net.encodingVersion() != BeliefEncoding.VERSION) {
            throw new IOException("policy trained on encoding v" + net.encodingVersion()
                    + ", this build speaks v" + BeliefEncoding.VERSION);
        }
        if (net.inputs() != BeliefEncoding.SIZE) {
            throw new IOException("policy wants " + net.inputs() + " inputs, the encoding produces "
                    + BeliefEncoding.SIZE);
        }
        if (parity != null) BeliefParity.check(net, parity);
        return new PolicyNet(net);
    }

    /** Whether the corpus held this contract; a policy asked about one it never saw guesses. */
    public boolean covers(Contract contract) {
        return (net.trainedOnContracts() & BeliefModel.contractBit(contract)) != 0;
    }

    /** Which contracts the corpus held, a bit per {@link Contract} ordinal. */
    public int trainedOnContracts() { return net.trainedOnContracts(); }

    /**
     * The input exactly as the trainer saw it: the teacher's records store the
     * encoding a byte a feature, and the trainer divides by 255. A bid of 23 is
     * 0.23 here and 59/255 there; small, but the parity is about the same input.
     */
    public static float[] features(BeliefEncoding.Evidence evidence) {
        return BeliefEncoding.dequantise(BeliefEncoding.quantise(BeliefEncoding.encode(evidence)));
    }

    /** All 64 outputs for one input. */
    public float[] logits(float[] features) {
        return net.logits(features);
    }

    /**
     * The legal card the policy scores highest; null when nothing is legal.
     * Ties go to the earlier card in {@code legal}, which is never reached in
     * practice -- two float scores are equal only for a card the net never
     * learned to tell apart, and then either is the policy's answer.
     */
    public Card choose(BeliefEncoding.Evidence evidence, List<Card> legal) {
        return choose(features(evidence), legal);
    }

    /** As above, on an input already encoded. */
    public Card choose(float[] features, List<Card> legal) {
        float[] scores = logits(features);
        Card best = null;
        float top = Float.NEGATIVE_INFINITY;
        for (Card card : legal) {
            float score = scores[BeliefEncoding.index(card)];
            if (best == null || score > top) {
                best = card;
                top = score;
            }
        }
        return best;
    }
}
