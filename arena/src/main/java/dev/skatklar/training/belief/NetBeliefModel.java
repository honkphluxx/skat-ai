package dev.skatklar.training.belief;

import dev.skatklar.demo.belief.BeliefModel;
import dev.skatklar.demo.belief.BeliefNet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The belief as the app runs it: plain Java, no runtime, no native libraries.
 *
 * <p>Preferred over {@link OnnxBeliefModel} wherever both are available, and the
 * reason is not speed. The arena exists to say what the shipped player is worth,
 * and it can only say that if it measures the shipped player. Two
 * implementations of one model is two chances to be measuring the wrong one.
 *
 * <p>Everything interesting lives in {@link BeliefNet}, in {@code core}, because
 * that is the module the phone build can see. What is left here is finding the
 * file and refusing it when it does not match the encoding or the trainer.
 */
public final class NetBeliefModel implements BeliefModel {

    private final BeliefNet net;

    private NetBeliefModel(BeliefNet net) {
        this.net = net;
    }

    /** Loads {@code belief.bin}, and refuses to return one that fails its checks. */
    public static NetBeliefModel load(Path directory) throws IOException {
        Path weights = directory.resolve("belief.bin");
        if (!Files.isRegularFile(weights)) {
            throw new IOException("no belief.bin in " + directory.toAbsolutePath());
        }
        int inputs = ModelDirectory.checkDescriptor(directory.resolve("model.json"));
        BeliefNet net;
        try (InputStream in = Files.newInputStream(weights)) {
            net = BeliefNet.load(in);
        }
        if (net.inputs() != inputs) {
            throw new IOException("belief.bin takes " + net.inputs()
                    + " inputs, model.json says " + inputs);
        }
        NetBeliefModel model = new NetBeliefModel(net);
        ModelDirectory.checkParity(directory.resolve("fixtures.npz"), model);
        return model;
    }

    @Override public int inputs() { return net.inputs(); }

    @Override public float[] logits(float[] features) { return net.logits(features); }
}
