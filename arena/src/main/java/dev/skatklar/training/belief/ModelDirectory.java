package dev.skatklar.training.belief;

import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.BeliefModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two checks any trained model has to pass before it is allowed to play.
 *
 * <p>Shared because there are now two ways to run the same weights — the pure
 * Java {@link dev.skatklar.demo.belief.BeliefNet} that ships in the app, and ONNX
 * Runtime — and the checks must not drift apart between them. That would be a
 * particularly unkind bug: the implementation that ships would be the one whose
 * verification had rotted.
 *
 * <p>Both failures this guards against are silent. A model trained on a
 * different encoding still loads, still answers, and is simply reading a
 * different game; an implementation that disagrees with the trainer about the
 * same input is invisible from the outside. Neither shows up as anything but a
 * player that is mysteriously weak.
 */
final class ModelDirectory {

    /** How far a logit may sit from the trainer's before the model is refused. */
    static final float PARITY_TOLERANCE = 2e-3f;

    private ModelDirectory() {}

    /**
     * Reads the trainer's own description and refuses a mismatch.
     *
     * <p>Parsed with a regex rather than a JSON library because this module has
     * no JSON dependency and two integers do not justify one. The file is
     * written by {@code train_belief.py} and by nothing else.
     *
     * @return the input width the model claims
     */
    static int checkDescriptor(Path descriptor) throws IOException {
        if (!Files.isRegularFile(descriptor)) {
            throw new IOException("no model.json beside the model: " + descriptor);
        }
        String json = Files.readString(descriptor, StandardCharsets.UTF_8);
        int version = number(json, "encoding_version");
        int inputs = number(json, "inputs");
        if (version != BeliefEncoding.VERSION) {
            throw new IOException("model was trained on encoding v" + version
                    + ", this build speaks v" + BeliefEncoding.VERSION);
        }
        if (inputs != BeliefEncoding.SIZE) {
            throw new IOException("model wants " + inputs + " inputs, the encoding produces "
                    + BeliefEncoding.SIZE);
        }
        return inputs;
    }

    private static int number(String json, String field) throws IOException {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) throw new IOException("model.json has no " + field);
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Replays the trainer's fixtures and refuses an implementation that
     * disagrees with it.
     *
     * <p>Absent fixtures are tolerated — an older model directory is still a
     * model — but a fixture that fails is fatal. The whole value of the check is
     * that it is not advisory.
     */
    static void checkParity(Path fixtures, BeliefModel model) throws IOException {
        if (!Files.isRegularFile(fixtures)) return;
        Map<String, Npz.Array> arrays = Npz.read(fixtures);
        Npz.Array features = arrays.get("features");
        Npz.Array expected = arrays.get("logits");
        if (features == null || expected == null) {
            throw new IOException("fixtures.npz has no features/logits pair");
        }
        int outputs = 32 * BeliefEncoding.CLASSES;
        float worst = 0;
        for (int row = 0; row < features.rows(); row++) {
            float[] one = new float[features.columns()];
            for (int at = 0; at < one.length; at++) one[at] = features.at(row, at);
            float[] got = model.logits(one);
            if (got == null || got.length != outputs) {
                throw new IOException("the model answered with "
                        + (got == null ? "nothing" : got.length + " numbers")
                        + " where " + outputs + " were due");
            }
            for (int at = 0; at < outputs; at++) {
                // The fixture array is (16, 32, 3) or (16, 96); either way the
                // flat order is the same.
                worst = Math.max(worst, Math.abs(got[at] - expected.values()[row * outputs + at]));
            }
        }
        if (worst > PARITY_TOLERANCE) {
            throw new IOException("this implementation and the trainer disagree about the "
                    + "same input, by up to " + worst + " in the logits. The model is not "
                    + "usable until that is explained.");
        }
    }
}
