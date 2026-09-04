package dev.skatklar.training.players;

import dev.skatklar.demo.ai.JSkatAiProvider;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import org.jskat.ai.ml.MLPlayer;
import org.jskat.ai.ml.MLPlayerPro;
import org.jskat.player.AbstractJSkatPlayer;

/**
 * Wires JSkat's ONNX-based players into the arena.
 *
 * <p>They live here rather than in {@code :jskat-ai} on purpose: that module is a
 * dependency of the Android app, and ONNX Runtime would drag its native
 * libraries into the APK for a player the app does not use. Only the training
 * module declares the dependency.
 *
 * <p>Both need two things the ordinary build does not provide, and the arena task
 * arranges both: ONNX Runtime on the classpath, and {@code jskat.models.dir}
 * pointing at the files {@code :jskat-base:downloadMlModels} fetches into the
 * nested JSkat build.
 */
public final class JSkatMlPlayers {

    /**
     * One pool per player type, shared across every provider the registry hands
     * out. Static because the registry creates a provider per seat per game, so
     * pooling inside a provider would pool nothing.
     */
    private static final Pool DENSE = new Pool(MLPlayer::new, "JSkat MLPlayer (dense)");
    private static final Pool TRANSFORMER =
            new Pool(MLPlayerPro::new, "JSkat MLPlayerPro (transformer)");

    private JSkatMlPlayers() {}

    /** Dense (multi-layer perceptron) models. */
    public static SkatAiProvider dense() {
        return new JSkatAiProvider("jskat-ml", "JSkat MLPlayer (dense)", DENSE);
    }

    /** Attention-based transformer models. */
    public static SkatAiProvider transformer() {
        return new JSkatAiProvider("jskat-ml-pro", "JSkat MLPlayerPro (transformer)", TRANSFORMER);
    }

    /**
     * Hands out ML players and takes them back, so that constructing one is rare.
     *
     * <p>Constructing an ML player loads four ONNX models from disk, tens to
     * hundreds of megabytes. Measured before pooling: a 15-board match took 56
     * seconds for 72 games, about one game per second, because the arena asks for
     * a session per seat per game -- 5400 constructions for a 300-board match.
     *
     * <p>A thread-local single instance would not do: a contestant occupies two
     * of the three seats in half the rotations, and both would then share one
     * player object. The pool grows to however many sessions are alive at once,
     * which is three or four, and stops there.
     */
    private static final class Pool implements JSkatAiProvider.PlayerSource {
        private final Supplier<AbstractJSkatPlayer> factory;
        private final String displayName;
        private final Deque<AbstractJSkatPlayer> idle = new ArrayDeque<>();

        Pool(Supplier<AbstractJSkatPlayer> factory, String displayName) {
            this.factory = factory;
            this.displayName = displayName;
        }

        @Override public synchronized AbstractJSkatPlayer borrow() {
            return idle.isEmpty() ? create() : idle.pop();
        }

        @Override public synchronized void release(AbstractJSkatPlayer player) {
            idle.push(player);
        }

        private AbstractJSkatPlayer create() {
            try {
                return factory.get();
            } catch (LinkageError nativeFailure) {
                // Separated from the model case on purpose: pointing at the model
                // files when the real problem is a native library wastes an hour.
                throw new IllegalStateException(displayName + " could not load ONNX Runtime's"
                        + " native library. Windows ships its own"
                        + " C:\\Windows\\System32\\onnxruntime.dll, which conflicts with some"
                        + " releases -- 1.28.0 fails where 1.19.2 and 1.17.3 load. Try"
                        + " -PonnxVersion=<version>; jskat-base's models need 1.17 or newer.",
                        nativeFailure);
            } catch (RuntimeException failure) {
                throw new IllegalStateException(displayName + " could not be created; the model"
                        + " files are the likely cause. Run gradlew :jskat-base:downloadMlModels"
                        + " inside third_party/jskat, or pass -Djskat.models.dir=<path>.",
                        failure);
            }
        }
    }
}
