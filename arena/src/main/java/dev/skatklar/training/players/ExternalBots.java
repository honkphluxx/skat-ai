package dev.skatklar.training.players;

import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import java.util.List;

/**
 * Registers the outside engines that run as helper processes, and only those a
 * checkout has actually built.
 *
 * <p>Availability is a question about the filesystem rather than the classpath,
 * which is why this is a hook rather than a {@code registerIfPresent} entry: a
 * clone that never fetched {@code third_party/xskat} has no binary, no
 * contestant and no failure, exactly as an unfetched JSkat submodule has none.
 *
 * <p>{@code -Dskat.probe=<n>} turns on the honesty control described in
 * {@link ExternalBotProvider}; it costs one extra search per card per world and
 * is meant to be run over a few hundred boards, not over every match.
 */
public final class ExternalBots {

    private ExternalBots() {}

    public static void register(PlayerRegistry registry) {
        int probe = Integer.getInteger("skat.probe", 0);

        String xskat = ExternalBot.locate("SKATKLAR_XSKAT_BOT",
                "third_party/xskat/skatklar-xskat",
                "skat-ai/third_party/xskat/skatklar-xskat",
                "../third_party/xskat/skatklar-xskat");
        if (xskat != null) {
            List<String> command = List.of(xskat);
            registry.register(contestant("xskat", "XSkat 4.0 (Gerhardt)", command, probe, false));
            registry.register(contestant("xskat-blind", "XSkat 4.0, blind", command, probe, true));
        }

        String goskat = ExternalBot.locate("SKATKLAR_GOSKAT_BOT",
                "third_party/go-skat/skatklar-goskat",
                "skat-ai/third_party/go-skat/skatklar-goskat",
                "../third_party/go-skat/skatklar-goskat");
        if (goskat != null) {
            // -skatklar is the flag the additive driver file watches for; without
            // it the binary is ordinary go-skat and would deal its own game.
            List<String> command = List.of(goskat, "-skatklar");
            registry.register(contestant("go-skat", "go-skat (Dranidis)", command, probe, false));
            registry.register(contestant("go-skat-blind", "go-skat, blind", command, probe, true));
        }

        // SkatZero (Jimboom7, MIT): a self-play reinforcement learner, seated
        // through tools/skatzero-bot.py, which runs its ONNX models under
        // Python. Card play only -- the driver never bids -- so it is a
        // --fixed-contract reference, and the strongest one we have. Present
        // when the models are: the checkout is not built, only cloned.
        String skatzero = skatZeroModels();
        String script = scriptFor("tools/skatzero-bot.py",
                "skat-ai/tools/skatzero-bot.py", "../tools/skatzero-bot.py");
        if (skatzero != null && script != null) {
            List<String> command = List.of(python(), script);
            registry.register(contestant("skatzero", "SkatZero (Jimboom7), card play only",
                    command, probe, false));
        }
    }

    /** The SkatZero checkout's ONNX models, or null. $SKATKLAR_SKATZERO_DIR names the checkout. */
    private static String skatZeroModels() {
        String named = System.getenv("SKATKLAR_SKATZERO_DIR");
        List<String> roots = named != null ? List.of(named)
                : List.of("third_party/skatzero", "skat-ai/third_party/skatzero", "../third_party/skatzero");
        for (String root : roots) {
            java.nio.file.Path model = java.nio.file.Path.of(root, "models", "onnx", "D_0.onnx");
            if (java.nio.file.Files.isRegularFile(model)) return model.toAbsolutePath().normalize().toString();
        }
        return null;
    }

    private static String scriptFor(String... relativePaths) {
        for (String relative : relativePaths) {
            java.nio.file.Path candidate = java.nio.file.Path.of(relative).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate.toString();
        }
        return null;
    }

    /** $SKATKLAR_PYTHON, else the interpreter the platform usually calls Python 3. */
    private static String python() {
        String named = System.getenv("SKATKLAR_PYTHON");
        if (named != null && !named.isBlank()) return named;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        return windows ? "python" : "python3";
    }

    private static Contestant contestant(String id, String displayName,
                                         List<String> command, int probe, boolean blind) {
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() { return displayName; }
            @Override public SkatAiProvider newProvider(long seed) {
                return new ExternalBotProvider(id, displayName, command, seed, probe, blind);
            }
            @Override public String toString() { return id; }
        };
    }
}
