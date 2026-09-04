package dev.skatklar.training.arena;

import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.training.players.BeliefOracleProvider;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.players.SolverAiProvider;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Resolves command-line names to contestants.
 *
 * <p>Optional opponents are looked up reflectively so that the arena keeps
 * building and running when an adapter is absent — the JSkat baselines live in
 * a module that pulls the whole vendored library, and a broken or unavailable
 * baseline must not block work on our own player.
 */
public final class PlayerRegistry {
    private static final String CLASS_PREFIX = "class:";

    private final Map<String, Contestant> byId = new LinkedHashMap<>();

    public static PlayerRegistry withDefaults() {
        PlayerRegistry registry = new PlayerRegistry();
        // A person at a terminal, entered under the same interface as everything
        // else -- which is the only reason it is possible at all. Costs nothing
        // when unused: constructing it does not touch the terminal.
        //
        // Read ConsolePlayer before believing a score it produces. A duplicate
        // match shows every board twice with the sides swapped, and a human
        // remembers the first showing where the AI does not.
        registry.register(stateless(dev.skatklar.training.play.ConsolePlayer.ID,
                "A person at a terminal", seed -> new dev.skatklar.training.play.ConsolePlayer()));
        registry.register(stateless("random", "Legal random",
                seed -> new LegalRandomAiProvider(new Random(seed))));
        registry.register(stateless("greedy", "Greedy heuristic",
                seed -> new GreedyAiProvider()));
        // The ceiling. It sees every hand, so its score is not a target and not
        // comparable to an honest player's -- it is the distance every honest
        // player is measured by. See SolverAiProvider for what it does and does
        // not cheat at.
        registry.register(stateless("solver", "Double-dummy par (cheats)",
                seed -> new SolverAiProvider()));
        // The honest counterpart of the solver: same search, but it only ever
        // sees its own cards and guesses the rest. Registered at several sample
        // counts because "how many worlds is enough" is a question the arena is
        // supposed to answer with a number rather than a preference.
        for (int worlds : new int[] {4, 8, 16, 32, 64}) {
            registry.register(stateless("search-" + worlds,
                    "Determinized search, " + worlds + " worlds",
                    seed -> new SearchAiProvider(new GreedyAiProvider(), worlds, seed)));
        }
        registry.register(stateless("search", "Determinized search, 16 worlds",
                seed -> new SearchAiProvider(new GreedyAiProvider(),
                        SearchAiProvider.DEFAULT_WORLDS, seed)));
        // The shipping personalities. Their labels are a promise until a match
        // has been run against them; converting a dial setting into tournament
        // points is exactly what this arena is for.
        // The belief sweep. Same search, same effort, same personality -- only the
        // share of sampled worlds that are the true one changes. `belief-0` is
        // `search` and `belief-100` lands on `solver`. The first run showed the
        // whole gain is already there at 25%, so the low settings exist to find
        // where it actually takes off -- which is the number a model has to hit.
        for (int percent : new int[] {0, 5, 10, 15, 25, 50, 75, 100}) {
            double accuracy = percent / 100.0;
            registry.register(stateless("belief-" + percent,
                    "Search with a belief " + percent + "% right",
                    seed -> new BeliefOracleProvider(accuracy, Personality.REFERENCE, seed)));
        }
        // How weak the existing dials can actually go, which is a question that
        // was never asked. `beginner` sits at two worlds and 30% memory, and the
        // floor is one world and none -- so the claim that the entry level had
        // run out of room was simply wrong. These three price the room that is
        // left, before anyone builds a new dial to replace it.
        registry.register(personality("novice-1w", "Beginner, one world",
                new Personality(1, 0.30, 0.55, 0.75)));
        registry.register(personality("novice-0m", "Beginner, no memory",
                new Personality(2, 0.00, 0.55, 0.75)));
        registry.register(personality("novice-floor", "Beginner, both at the floor",
                new Personality(1, 0.00, 0.55, 0.75)));
        registry.register(personality("beginner", "Beginner", Personality.beginner()));
        registry.register(personality("club", "Club player", Personality.clubPlayer()));
        registry.register(personality("expert", "Expert", Personality.expert()));
        registry.register(personality("analyst", "Analyst", Personality.analyst()));
        // JSkat's players, discovered reflectively. "jskat" deliberately tracks
        // whatever the app currently seats, so "measure the shipped player" keeps
        // working when the default moves; the others name one implementation each.
        registry.registerIfPresent("jskat", "JSkat (app default)",
                "dev.skatklar.demo.ai.JSkatAiProvider", null);
        registry.registerIfPresent("jskat-new", "JSkat AlgorithmAI",
                "dev.skatklar.demo.ai.JSkatAiProvider", "newAlgorithm");
        registry.registerIfPresent("jskat-algorithmic", "JSkat AlgorithmicAIPlayer (superseded)",
                "dev.skatklar.demo.ai.JSkatAiProvider", "algorithmic");
        registry.registerIfPresent("jskat-ml", "JSkat MLPlayer (dense)",
                "dev.skatklar.training.players.JSkatMlPlayers", "dense");
        registry.registerIfPresent("jskat-ml-pro", "JSkat MLPlayerPro (transformer)",
                "dev.skatklar.training.players.JSkatMlPlayers", "transformer");
        // The learned belief, if one has been trained. Reached through a hook
        // rather than named here for two reasons: this file must not import ONNX
        // Runtime, and whether there is a model on disk is a question only that
        // class can answer. No model, no contestant, no noise.
        registry.registerHook("dev.skatklar.training.belief.BeliefPlayers");
        return registry;
    }

    public void register(Contestant contestant) {
        byId.put(contestant.id(), contestant);
    }

    /**
     * Registers a provider only when it is actually on the classpath.
     *
     * @param staticFactory name of a no-argument static method returning a
     *                      {@link SkatAiProvider}, or {@code null} to use the
     *                      class's no-argument constructor. The indirection
     *                      exists because the ML players are wired from a holder
     *                      class in this module, so that ONNX Runtime stays out
     *                      of the Android build.
     */
    public boolean registerIfPresent(String id, String displayName, String className,
                                     String staticFactory) {
        try {
            Class<?> type = Class.forName(className);
            if (staticFactory == null) {
                if (!SkatAiProvider.class.isAssignableFrom(type)) return false;
                type.getDeclaredConstructor();
                register(stateless(id, displayName, seed -> {
                    seedJSkat(seed);
                    return instantiate(type);
                }));
            } else {
                Method method = type.getDeclaredMethod(staticFactory);
                if (!SkatAiProvider.class.isAssignableFrom(method.getReturnType())) return false;
                register(stateless(id, displayName, seed -> {
                    seedJSkat(seed);
                    return invoke(method);
                }));
            }
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Lets a class that owns an optional dependency register its own players.
     *
     * <p>Wider than {@link #registerIfPresent}, which can only name one provider
     * with a fixed constructor. A hook decides for itself how many contestants it
     * contributes and whether it contributes any — which is what a player backed
     * by a file on disk needs, since its availability is not a classpath
     * question.
     *
     * @param className a class with a {@code static void register(PlayerRegistry)}
     */
    public boolean registerHook(String className) {
        try {
            Class.forName(className)
                    .getDeclaredMethod("register", PlayerRegistry.class)
                    .invoke(null, this);
            return true;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    public Set<String> ids() { return byId.keySet(); }

    /**
     * @param spec a registered id, or {@code class:<fully.qualified.Name>} for a
     *             provider with a no-argument constructor anywhere on the classpath
     */
    public Contestant resolve(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty player specification");
        }
        if (spec.startsWith(CLASS_PREFIX)) {
            String className = spec.substring(CLASS_PREFIX.length());
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException missing) {
                throw new IllegalArgumentException("No such provider class: " + className, missing);
            }
            if (!SkatAiProvider.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException(className + " is not a SkatAiProvider");
            }
            return stateless(className.substring(className.lastIndexOf('.') + 1),
                    className, seed -> instantiate(type));
        }
        Contestant contestant = byId.get(spec);
        if (contestant == null) {
            throw new IllegalArgumentException(
                    "Unknown player '" + spec + "'. Available: " + String.join(", ", ids())
                            + " (or class:<fqcn>)");
        }
        return contestant;
    }

    /**
     * Seeds the generator JSkat's players share, so a match involving them
     * replays exactly like every other match does.
     *
     * <p>It is one generator for the whole process, not one per seat, so this
     * buys run-to-run reproducibility and not seat independence. Best effort:
     * an adapter without the hook simply stays unseeded.
     */
    private static void seedJSkat(long seed) {
        try {
            Class.forName("dev.skatklar.demo.ai.JSkatAiProvider")
                    .getMethod("seedSharedRandom", long.class).invoke(null, seed);
        } catch (ReflectiveOperationException | LinkageError absent) {
            // No JSkat adapter on the classpath, or an older one. Nothing to seed.
        }
    }

    private static SkatAiProvider invoke(Method factory) {
        try {
            return (SkatAiProvider) factory.invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot call " + factory, failure);
        }
    }

    private static SkatAiProvider instantiate(Class<?> type) {
        try {
            return (SkatAiProvider) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), failure);
        }
    }

    private static Contestant personality(String id, String displayName, Personality profile) {
        return stateless(id, displayName + " (" + profile.worlds() + " worlds, memory "
                        + Math.round(profile.memory() * 100) + "%)",
                seed -> new SearchAiProvider(new GreedyAiProvider(), profile, seed));
    }

    private static Contestant stateless(String id, String displayName, SeededProvider factory) {
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() { return displayName; }
            @Override public SkatAiProvider newProvider(long seed) { return factory.create(seed); }
            @Override public String toString() { return id; }
        };
    }

    @FunctionalInterface
    private interface SeededProvider {
        SkatAiProvider create(long seed);
    }
}
