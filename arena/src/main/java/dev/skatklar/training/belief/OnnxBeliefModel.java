package dev.skatklar.training.belief;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.BeliefModel;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * The trained network, as the search sees it.
 *
 * <p>The only class in the project that touches ONNX Runtime for our own model,
 * which is the point of {@link BeliefModel} being one method wide: everything
 * that decides how a probability becomes a sampled world lives in
 * {@link BeliefWorldSource} and is tested without a runtime, a GPU or a trained
 * file. What is left here is loading, shape checking and a tensor round trip.
 *
 * <p>Three things are verified before the model is allowed to play, because each
 * of them fails <em>silently</em> otherwise — the player is simply weak, and no
 * log line says why:
 *
 * <ul>
 *   <li>the encoding version and the input width in {@code model.json};</li>
 *   <li>the <b>parity fixtures</b> — real feature vectors and the logits Python
 *       produced for them.</li>
 * </ul>
 *
 * <p>Both live in {@link ModelDirectory}, shared with the pure-Java loader that
 * actually ships, so the two cannot drift apart.
 *
 * <p>Loading the model by path rather than by bytes is deliberate: torch writes
 * the weights to a {@code belief.onnx.data} sidecar, which the runtime resolves
 * relative to the model file. Handing it a byte array loads a graph with no
 * weights in it, and — this is the trap — that does not throw. It answers.
 */
public final class OnnxBeliefModel implements BeliefModel, AutoCloseable {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final int inputs;

    private OnnxBeliefModel(OrtEnvironment environment, OrtSession session,
                            String inputName, int inputs) {
        this.environment = environment;
        this.session = session;
        this.inputName = inputName;
        this.inputs = inputs;
    }

    /**
     * Loads {@code belief.onnx} from a directory the trainer wrote.
     *
     * @param directory holds {@code belief.onnx}, {@code model.json} and,
     *                  optionally, {@code fixtures.npz}
     * @throws IOException when the directory is not a usable model, including
     *                     when it is usable but disagrees with Python
     */
    public static OnnxBeliefModel load(Path directory) throws IOException {
        Path graph = directory.resolve("belief.onnx");
        if (!Files.isRegularFile(graph)) {
            throw new IOException("no belief.onnx in " + directory.toAbsolutePath());
        }
        int inputs = ModelDirectory.checkDescriptor(directory.resolve("model.json"));

        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession session;
        String inputName;
        try {
            session = environment.createSession(graph.toAbsolutePath().toString(),
                    singleThreaded());
            inputName = session.getInputNames().iterator().next();
        } catch (OrtException failure) {
            throw new IOException("ONNX Runtime refused " + graph + ": " + failure.getMessage(),
                    failure);
        }
        OnnxBeliefModel model = new OnnxBeliefModel(environment, session, inputName, inputs);
        try {
            ModelDirectory.checkParity(directory.resolve("fixtures.npz"), model);
        } catch (IOException | RuntimeException failure) {
            model.close();
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
        return model;
    }

    /**
     * One thread per inference, which is the opposite of the default and the
     * right way round here.
     *
     * <p>ONNX Runtime sizes its intra-op pool to the machine and forks into it
     * for every call. That is correct for a server answering one large batch at a
     * time; it is wrong for this, where the caller is already the parallel one.
     * The arena runs a board per thread, each seat asks for a single-sample
     * forward pass of a 735k-parameter MLP, and the fork-join around a
     * microsecond of arithmetic costs more than the arithmetic — while the extra
     * threads spin against the solver threads that are doing the actual work.
     *
     * <p>The visible symptom is a machine that looks half busy and finishes
     * slowly: more threads than cores, most of them waiting.
     */
    private static OrtSession.SessionOptions singleThreaded() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        return options;
    }

    @Override public int inputs() { return inputs; }

    @Override
    public float[] logits(float[] features) {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment,
                FloatBuffer.wrap(features), new long[] {1, features.length});
             OrtSession.Result result = session.run(
                     Collections.singletonMap(inputName, tensor))) {
            return flatten(result.get(0).getValue());
        } catch (OrtException failure) {
            // Swallowed into the contract rather than propagated: the caller
            // treats "no answer" as "reason uniformly this once", and a model
            // that has started to fail should cost points, not the match.
            throw new IllegalStateException("belief model failed on one decision", failure);
        }
    }

    /**
     * The output as one row, whatever rank the exporter gave it.
     *
     * <p>{@code train_belief.py} reshapes to {@code (batch, 32, 3)}, so the value
     * arrives as {@code float[1][32][3]}; an exporter that kept it flat gives
     * {@code float[1][96]}. Both mean the same thing in the same order.
     */
    private static float[] flatten(Object value) {
        if (value instanceof float[][][] cube) {
            float[][] rows = cube[0];
            float[] flat = new float[rows.length * rows[0].length];
            int at = 0;
            for (float[] row : rows) for (float score : row) flat[at++] = score;
            return flat;
        }
        if (value instanceof float[][] rows) return rows[0].clone();
        throw new IllegalStateException("unexpected model output: " + value.getClass());
    }

    /** Closes the session. The environment is process-wide and is left alone. */
    @Override public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // Nothing useful to do about a session that will not close.
        }
    }
}
