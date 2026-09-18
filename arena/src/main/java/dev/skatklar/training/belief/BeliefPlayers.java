package dev.skatklar.training.belief;

import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefModel;
import dev.skatklar.demo.search.BeliefWorldSource;
import dev.skatklar.demo.search.HandEvaluator.AuctionEvidence.PassRule;
import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.RuleTiebreak.NullOrder;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.Opponents;
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

    /**
     * A second model, seated as {@code belief-32-candidate} and
     * {@code belief-32-adaptive-margin-ties-candidate}: the same players as
     * the shipped ones with only the belief swapped, so that a retrained model
     * meets the model it would replace on the same boards in the same process.
     * Looked for in {@code belief-model-v2} when the property is not set;
     * nothing is registered when no such directory holds a model.
     */
    public static final String CANDIDATE_PROPERTY = "belief.model.candidate.dir";

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
        // The aggression ladder at 32 worlds, so that a bolder bidder is
        // compared with belief-32 and nothing else moves. `belief-bold` above
        // is at 16 worlds, and against belief-32 it confounds the dial with
        // the sampling budget.
        //
        // Why the ladder exists after the calibration tool said the threshold
        // was right: that tool scores a passed hand as zero. In the duplicate
        // arena a passed hand is not zero -- on the other side of the pairing
        // XSkat holds the same cards in the same seat, declares them, and
        // scores. And being bolder with one hand can take the board away from
        // XSkat holding another at the same table. Neither effect is visible
        // one hand at a time; both are visible to the auction, and the arena is
        // what runs the auction against the real opponent. The break-even each
        // dial declares above: 0.630, 0.583, 0.524, against 0.667 at reference.
        registry.register(sweep("belief-32-a65", 32, 0.65, loader));
        registry.register(sweep("belief-32-a80", 32, 0.80, loader));
        registry.register(sweep("belief-32-a95", 32, 0.95, loader));
        // belief-32 with the auction allowed to change the price of the hand,
        // and nothing else different. The dial above was +0.8 against XSkat
        // and -0.67 against belief-32; this is the player that is meant to
        // earn the first without paying the second, by being bold only when
        // the table is quiet. See SearchAiProvider.withAdaptiveBidding.
        registry.register(adaptive("belief-32-adaptive", 32, PassRule.DEFAULT, loader));
        // The adaptive bidder passed its four gates (+0.14 self-play, +0.46
        // over reference against xskat, +0.43 against go-skat) while declaring
        // less than belief-32 does: the gain came from the hard jack floor,
        // and the pass rule -- doubt a pass only on two jacks over what it
        // implies, and then keep the world with weight 0.35 -- barely fired.
        // One dimension of tuning, through the same four gates: doubt a pass
        // on one jack over. Everything else is the reference reading.
        registry.register(adaptive("belief-32-adaptive-p1", 32, new PassRule(1, PassRule.DEFAULT.weight()), loader));
        // belief-32 with one thing changed in card play: among cards that keep
        // the game winnable in equally many worlds, the one that wins with
        // fifteen points to spare in the most worlds, instead of the cheapest.
        // The vote itself is untouched. A robustness term against the worlds
        // being wrong, not an information term; see
        // SearchAiProvider.withMarginTiebreak. Fifteen is a round number below
        // the Schneider step, chosen once and not swept.
        registry.register(margin("belief-32-margin", 32, 15, loader));
        // Both switches on: the adaptive bidder at its reference reading and
        // the fifteen-point cushion. Each passed its four gates alone (the
        // bidder +0.14 in self-play, the cushion +1.10, both non-negative
        // against every outsider); one is bidding and the other card play, so
        // the effects should add, and "should" is what this row measures.
        // The candidate for Opponents.seat if it lands where the sum predicts.
        registry.register(combined("belief-32-adaptive-margin", 32, PassRule.DEFAULT, 15, loader));
        // The shipped player with its last ties -- the cards the vote and the
        // cushion both leave level -- broken by the position in the trick
        // instead of by the cheapest card: last to play, take the trick if it
        // can be taken, give the least if not and the most to the partner;
        // otherwise the fewest points and the least power. Deterministic rules
        // from the score sheet, nothing learned; see SearchAiProvider.withRuleTiebreak.
        // Expected small: it only decides what the search called a tie.
        registry.register(combined("belief-32-adaptive-margin-ties", 32, PassRule.DEFAULT, 15, true, loader));
        // The app's ladder, built by Opponents.seat itself so that what the
        // arena measures is what the phone seats: belief where the level has
        // it, the two switches where Level.playsAdaptively says. The "-on" and
        // "-off" variants state the switches instead, for the two lower levels
        // whose strength with them nobody has measured: with two or six worlds
        // the top vote is tied on most decisions, so the cushion fires on most
        // of them, and the effect could go either way.
        for (Opponents.Level level : Opponents.Level.values()) {
            String name = level.name().toLowerCase();
            registry.register(app("app-" + name, level, null, loader));
            registry.register(app("app-" + name + "-on", level, true, loader));
            registry.register(app("app-" + name + "-off", level, false, loader));
        }
        registry.register(alphaMu(registry, "alphamu-1", 1, loader));
        registry.register(alphaMu(registry, "alphamu", 2, loader));
        registry.register(alphaMu(registry, "alphamu-3", 3, loader));

        // The Null variants, for the instrument the Null contract source
        // provides (`--contracts=null`). All three are the shipped player with
        // one thing changed, so a match between any of them and the shipped
        // player is exactly paired: the tiebreak that stops sorting Null by
        // card points, twice the worlds a Null decision samples, and both.
        // Null is the one contract where our card play is measurably behind
        // (arena/README.md, 2026-09-17 third), where the belief is blind, and
        // where a solve is cheap enough that more worlds cost little.
        registry.register(nullVariant("belief-32-null-rank", 32, NullOrder.SHED_HIGH, 0, loader));
        registry.register(nullVariant("belief-32-null-128", 32, NullOrder.POINTS, 128, loader));
        registry.register(nullVariant("belief-32-null-rank-128", 32, NullOrder.SHED_HIGH, 128, loader));
        registry.register(nullVariant("belief-32-null-rank-256", 32, NullOrder.SHED_HIGH, 256, loader));
        // The second round, after the first refuted "shed the highest safe
        // card" (-1.33, resolved) and cleared "more worlds" (+1.17, resolved).
        // What is left to separate is how low to play and how far the world
        // count keeps paying: LOW_RANK is what the shipped points order was
        // accidentally approximating, said properly, and 256 worlds has never
        // been measured without the refuted tiebreak attached to it.
        registry.register(nullVariant("belief-32-null-256", 32, NullOrder.POINTS, 256, loader));
        registry.register(nullVariant("belief-32-null-512", 32, NullOrder.POINTS, 512, loader));
        registry.register(nullVariant("belief-32-null-low-128", 32, NullOrder.LOW_RANK, 128, loader));
        registry.register(nullVariant("belief-32-null-low-256", 32, NullOrder.LOW_RANK, 256, loader));

        Path candidate = locateCandidate();
        if (candidate != null) {
            Loader other = new Loader(candidate);
            registry.register(renamed(worlds("belief-32-candidate", 32, other),
                    "Belief, 32 worlds a decision, candidate model " + candidate));
            registry.register(renamed(combined("belief-32-adaptive-margin-ties-candidate", 32,
                    PassRule.DEFAULT, 15, true, other),
                    "The shipped player with the candidate model " + candidate));
        }
    }

    /**
     * The shipped player with the Null knobs moved.
     *
     * @param rankTies  break Null ties by Null rank instead of by card points
     * @param nullWorlds worlds a Null decision samples; zero for the usual count
     */
    private static Contestant nullVariant(String id, int worlds, NullOrder nullTies, int nullWorlds,
                                          Loader loader) {
        Personality personality = new Personality(worlds, Personality.REFERENCE.memory(),
                Personality.REFERENCE.risk(), Personality.REFERENCE.aggression());
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "The shipped player, Null ties " + nullTies
                        + (nullWorlds > 0 ? ", " + nullWorlds + " worlds" : "");
            }
            @Override public SkatAiProvider newProvider(long seed) {
                SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                        new BeliefWorldSource(loader.get()))
                        .withAdaptiveBidding(PassRule.DEFAULT).withMarginTiebreak(15);
                // The position tiebreak carries the Null branch, so every
                // variant has it; POINTS means that branch still orders a Null
                // by card points, which is the shipped control.
                player = player.withRuleTiebreak();
                if (nullWorlds > 0) player = player.withNullWorlds(nullWorlds);
                return nullTies == NullOrder.POINTS ? player : player.withNullTiebreak(nullTies);
            }
            @Override public String toString() { return id; }
        };
    }

    /** The same contestant under a display name that says which model it carries. */
    private static Contestant renamed(Contestant inner, String displayName) {
        return new Contestant() {
            @Override public String id() { return inner.id(); }
            @Override public String displayName() { return displayName; }
            @Override public SkatAiProvider newProvider(long seed) { return inner.newProvider(seed); }
            @Override public String toString() { return inner.id(); }
        };
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

    /**
     * A level of the app, exactly as {@link Opponents#seat} builds it.
     *
     * @param adaptively null for what the level ships with; true or false to
     *                   state the two switches
     */
    private static Contestant app(String id, Opponents.Level level, Boolean adaptively, Loader loader) {
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "App level " + level + (adaptively == null ? " as shipped"
                        : adaptively ? ", switches on" : ", switches off");
            }
            @Override public SkatAiProvider newProvider(long seed) {
                BeliefWorldSource worlds = new BeliefWorldSource(loader.get());
                boolean switches = adaptively != null ? adaptively : level.playsAdaptively();
                return Opponents.seat(level, worlds, seed, Opponents.UNBOUNDED, switches);
            }
            @Override public String toString() { return id; }
        };
    }

    /** The belief player listening to the auction and breaking card-play ties by a cushion. */
    private static Contestant combined(String id, int worlds, PassRule rule, int points, Loader loader) {
        return combined(id, worlds, rule, points, false, loader);
    }

    /** As above, and with {@code rules} the ties the cushion leaves are broken by position. */
    private static Contestant combined(String id, int worlds, PassRule rule, int points, boolean rules,
                                       Loader loader) {
        Personality personality = new Personality(worlds, Personality.REFERENCE.memory(),
                Personality.REFERENCE.risk(), Personality.REFERENCE.aggression());
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "Belief, " + worlds + " worlds, adaptive bidding, ties broken by "
                        + points + " points of cushion" + (rules ? ", then by position" : "");
            }
            @Override public SkatAiProvider newProvider(long seed) {
                SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                        new BeliefWorldSource(loader.get()))
                        .withAdaptiveBidding(rule).withMarginTiebreak(points);
                return rules ? player.withRuleTiebreak() : player;
            }
            @Override public String toString() { return id; }
        };
    }

    /** The belief player, breaking card-play ties by a cushion of {@code points}. */
    private static Contestant margin(String id, int worlds, int points, Loader loader) {
        Personality personality = new Personality(worlds, Personality.REFERENCE.memory(),
                Personality.REFERENCE.risk(), Personality.REFERENCE.aggression());
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "Belief, " + worlds + " worlds, ties broken by " + points + " points of cushion";
            }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                        new BeliefWorldSource(loader.get())).withMarginTiebreak(points);
            }
            @Override public String toString() { return id; }
        };
    }

    /** The belief player at the reference aggression, listening to the auction and reading a pass by {@code rule}. */
    private static Contestant adaptive(String id, int worlds, PassRule rule, Loader loader) {
        Personality personality = new Personality(worlds, Personality.REFERENCE.memory(),
                Personality.REFERENCE.risk(), Personality.REFERENCE.aggression());
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "Belief, " + worlds + " worlds, adaptive bidding"
                        + (rule.equals(PassRule.DEFAULT) ? ""
                        : ", pass doubted at +" + rule.margin() + " jacks, weight " + rule.weight());
            }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), personality, seed,
                        new BeliefWorldSource(loader.get())).withAdaptiveBidding(rule);
            }
            @Override public String toString() { return id; }
        };
    }

    /** The belief player at a different aggression, for the auction sweep. */
    private static Contestant sweep(String id, double aggression, Loader loader) {
        return sweep(id, Personality.REFERENCE.worlds(), aggression, loader);
    }

    /** As above, at a stated world count rather than the reference one. */
    private static Contestant sweep(String id, int worlds, double aggression, Loader loader) {
        Personality personality = new Personality(worlds,
                Personality.REFERENCE.memory(), Personality.REFERENCE.risk(), aggression);
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() {
                return "Belief, " + worlds + " worlds, aggression "
                        + Math.round(aggression * 100) + "%";
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

    private static Path locateCandidate() {
        String override = System.getProperty(CANDIDATE_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path named = Path.of(override);
            return holdsAModel(named) ? named : null;
        }
        for (Path candidate : List.of(Path.of("belief-model-v2"), Path.of("..", "belief-model-v2"),
                Path.of("..", "..", "belief-model-v2"))) {
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
