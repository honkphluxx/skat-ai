package dev.skatklar.training.belief;

import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefModel;
import dev.skatklar.demo.search.BeliefWorldSource;
import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.demo.ai.GreedyAiProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Seats the learned belief in the arena, when there is one to seat.
 *
 * <p>A holder class for the same reason {@code JSkatMlPlayers} is one: the
 * registry must not import ONNX Runtime, so that a build or a machine without it
 * still resolves every other player. It is found reflectively, and its absence
 * costs the arena a contestant rather than a run.
 *
 * <p>What it registers is deliberately one player and two variations of the same
 * player. {@code belief} is {@link Personality#REFERENCE} — the exact settings
 * {@code search} runs at, with the world source swapped and nothing else — so
 * that {@code belief} against {@code search} on duplicate boards prices the model
 * and only the model. {@code belief-sharp} and {@code belief-soft} answer the one
 * question that cannot be settled from the validation loss: whether the search
 * wants the model's opinion taken more or less literally than it was trained.
 *
 * <p>{@code belief-32} is the same player again at twice the sampling budget,
 * which at a fixed contract is precisely the level the app ships as its
 * strongest. See the comment on it in {@link #register}.
 */
public final class BeliefPlayers {

    /** Overrides where the model is looked for; otherwise the candidates below. */
    public static final String DIRECTORY_PROPERTY = "belief.model.dir";

    private BeliefPlayers() {}

    /**
     * Adds the belief players, or none if no model is on disk.
     *
     * <p>Called reflectively by {@link PlayerRegistry#withDefaults()}. Registering
     * nothing is the normal case on a machine that has not trained a model, and
     * it must stay quiet: the arena prints its contestants, and a warning about a
     * model nobody asked for would be noise in every run.
     */
    public static void register(PlayerRegistry registry) {
        Path directory = locate();
        if (directory == null) return;
        // One model, one session, shared by every seat and every thread. ONNX
        // Runtime sessions are safe to call concurrently, and loading three
        // megabytes of weights per seat per match is not.
        Loader loader = new Loader(directory);
        registry.register(player("belief", "Search with the learned belief",
                loader, 1.0));
        registry.register(player("belief-sharp",
                "Search with the learned belief, sharpened", loader, 2.0));
        registry.register(player("belief-soft",
                "Search with the learned belief, softened", loader, 0.5));
        // The belief plus alpha-mu, which is the only pairing worth measuring:
        // both change the declarer's card play and nothing else, so `alphamu`
        // against `belief` on the same boards prices the search on top of the
        // belief rather than the two of them together against a player that has
        // neither.
        // The control first, and it is not optional. `alphamu-1` runs the same
        // code with nothing expanded, which is provably the same evaluation as
        // the vote, so against `belief` it must score exactly zero on every
        // board. If it does not, the plumbing is wrong and any number `alphamu`
        // produces is measuring that instead of the algorithm.
        // The auction sweep. Same player, same belief, same card-play effort --
        // only how heavily a lost game weighs when it decides whether to declare.
        // `Personality.lossWeight()` is 1.5 - aggression, so bold discounts the
        // downside by a fifth and timid adds a third to it.
        //
        // A confound to measure rather than argue about: aggression also decides
        // `prefersTheHighCard()` above 0.6, which is a card-play tie-break and
        // nothing to do with bidding. So each variant is entered twice -- with
        // the auction, where both effects act, and at fixed contracts, where the
        // bidding is bypassed and only the tie-break can move. The second is the
        // control, and the difference between the two is the auction's share.
        // Thirty-two worlds instead of sixteen, and nothing else. Two questions
        // in one contestant.
        //
        // The first is a gap: `belief` sits 1.48 game points behind
        // jskat-ml-pro at forced contracts, 95% CI [-2.64, -0.33], pooled over
        // three seeds -- resolved, so JSkat's transformer still plays the better
        // cards. The cheapest candidate explanation is simply that it is being
        // compared against our *middle* effort: doubling the world count was
        // measured at +1.84 for the beliefless player, which is more than the
        // deficit. If that carries over, the deficit is a sampling budget rather
        // than a weakness, and this is the contestant that says so.
        //
        // The second is what the app already ships. At a fixed contract this
        // player and `Opponents.Level.ANALYST` are the same player, exactly:
        // analyst is (32, 1.00, -0.1, 0.35) against REFERENCE's (32, 1.00, 0.0,
        // 0.5) here, aggression moves only the auction, and a negative risk
        // reaches the same target as a neutral one -- see
        // Personality.targetFor(). So an oracle match against jskat-ml-pro is not
        // an analogy for the top level, it is a measurement of it.
        //
        // Worth knowing before reading the result: against jskat the boards are
        // not paired as tightly as our own players are against each other -- the
        // three seeds of `belief` resolved to about +-1.15, so an edge near zero
        // will not resolve. The sharp measurement of the world count is
        // `belief-32` against `belief` at fixed contracts, where duplicate deals
        // and common random numbers remove nearly all of the board variance.
        registry.register(worlds("belief-32", 32, loader));
        registry.register(sweep("belief-bold", 0.80, loader));
        registry.register(sweep("belief-timid", 0.20, loader));
        registry.register(alphaMu(registry, "alphamu-1", 1, loader));
        registry.register(alphaMu(registry, "alphamu", 2, loader));
        registry.register(alphaMu(registry, "alphamu-3", 3, loader));
    }

    /**
     * The belief player at a different sampling budget, and nothing else changed.
     *
     * <p>Linear in the world count, so this is also the honest price list for
     * thinking time on a phone.
     */
    private static Contestant worlds(String id, int worlds, Loader loader) {
        Personality personality = new Personality(worlds, Personality.REFERENCE.memory(),
                Personality.REFERENCE.risk(), Personality.REFERENCE.aggression());
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "Belief, " + worlds + " worlds a decision";
            }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                        new BeliefWorldSource(loader.get()));
            }
            @Override public String toString() { return id; }
        };
    }

    /** The belief player at a different aggression, for the auction sweep. */
    private static Contestant sweep(String id, double aggression, Loader loader) {
        Personality personality = new Personality(Personality.REFERENCE.worlds(),
                Personality.REFERENCE.memory(), Personality.REFERENCE.risk(), aggression);
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "Belief, aggression " + Math.round(aggression * 100) + "%";
            }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                        new BeliefWorldSource(loader.get()));
            }
            @Override public String toString() { return id; }
        };
    }

    /** The belief player, with αµ instead of the vote once the hand is short. */
    private static Contestant alphaMu(PlayerRegistry registry, String id, int depth,
                                      Loader loader) {
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return depth <= 1 ? "Belief, through the alpha-mu path (control)"
                        : "Belief plus alpha-mu, depth " + depth;
            }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), Personality.REFERENCE, seed,
                        new BeliefWorldSource(loader.get()), depth);
            }
            @Override public String toString() { return id; }
        };
    }

    private static Contestant player(String id, String displayName, Loader loader,
                                     double sharpness) {
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() { return displayName; }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), Personality.REFERENCE, seed,
                        new BeliefWorldSource(loader.get(), sharpness));
            }
            @Override public String toString() { return id; }
        };
    }

    /**
     * Where the model lives.
     *
     * <p>Searched rather than fixed, because the arena and the exporter run with
     * different working directories — one is the module, the other the repository
     * root — and a player that silently does not exist depending on which Gradle
     * task launched it is the kind of thing that costs an evening.
     */
    private static Path locate() {
        String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path named = Path.of(override);
            return holdsAModel(named) ? named : null;
        }
        for (Path candidate : List.of(Path.of("belief-model"), Path.of("..", "belief-model"),
                Path.of("..", "..", "belief-model"))) {
            if (holdsAModel(candidate)) return candidate;
        }
        return null;
    }

    private static boolean holdsAModel(Path directory) {
        return Files.isRegularFile(directory.resolve("belief.bin"))
                || Files.isRegularFile(directory.resolve("belief.onnx"));
    }

    /**
     * The pure-Java weights if they are there, ONNX Runtime if they are not.
     *
     * <p>The order matters more than it looks. {@code belief.bin} is what the
     * app ships, so it is what the arena should be measuring; the runtime is
     * kept as a fallback for a model directory that predates the exporter, and
     * as a second opinion when the two are suspected of disagreeing. Whichever
     * is used replays the same fixtures before it is allowed to play.
     */
    private static BeliefModel read(Path directory) throws IOException {
        if (Files.isRegularFile(directory.resolve("belief.bin"))) {
            return NetBeliefModel.load(directory);
        }
        return OnnxBeliefModel.load(directory);
    }

    /**
     * Loads once, on the first decision that needs it, and remembers the failure.
     *
     * <p>Lazy so that listing the players does not load a model, and loud so that
     * a model which fails its own parity check ends the match instead of quietly
     * becoming a uniform player. A silent fallback here would turn a measurement
     * of the belief into a measurement of {@code search} wearing its name — which
     * is the one outcome worse than no measurement.
     */
    private static final class Loader {
        private final Path directory;
        private BeliefModel model;
        private IOException failure;

        Loader(Path directory) { this.directory = directory; }

        synchronized BeliefModel get() {
            if (failure != null) throw new UncheckedIOException(failure);
            if (model == null) {
                try {
                    model = read(directory);
                } catch (IOException problem) {
                    failure = problem;
                    throw new UncheckedIOException(problem);
                }
            }
            return model;
        }
    }
}
