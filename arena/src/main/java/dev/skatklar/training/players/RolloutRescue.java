package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.training.data.SelfPlayExportMain;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A position decided by playing it out against realistic opponents
 * (docs/training-plan.md 2.10, R).
 *
 * <p>Each candidate card is played in each of the search's own sampled worlds
 * (up to {@code worlds} of them) and the game finished by the student for all
 * three seats, each seat reading only its own evidence (perfect memory, the
 * bids it heard, the declarer's own discard). Everything is counted from the
 * deciding seat's side: its side's wins, card points and game value (the
 * declarer's score-sheet value, or minus it for the defenders).
 *
 * <p>Two forms. Top card ({@code draws == 0}): every seat plays the student's
 * top card; cards are ranked by wins, then points -- measured in the
 * declarer's lost positions, it decided on too few events (+0.22). Drawn
 * ({@code draws > 0}): the opponents' cards -- the other side's, never the
 * deciding seat's own side -- are drawn from the student's policy (softmax over
 * the legal cards), {@code draws} play-outs per world on one random stream per
 * world and draw shared by every candidate card; the own side keeps the top
 * card, since the search will play those cards and errs less. Cards are
 * ranked by the summed game value, then wins, then points (+0.93 in the
 * declarer's lost positions, level with SkatZero's play there). A top-card
 * form can be value-ranked too ({@code valueRanked}), which separates the two
 * changes.
 *
 * <p>The play-outs of one decision run in lockstep: at every step the positions
 * of all of them that wait for a card go through one batched forward pass
 * ({@link PolicyNet#logits(float[][])}, bitwise the single pass's answers), so
 * the result is exactly the one-at-a-time result ({@link #tallyOneByOne}, kept
 * as the reference) at a fraction of the time.
 */
public final class RolloutRescue {

    private final PolicyNet student;
    private final int worlds;
    /** Play-outs per world with the opponents' cards drawn from the policy; 0 for the top card. */
    private final int draws;
    private final boolean valueRanked;
    private final long seed;

    public RolloutRescue(PolicyNet student, int worlds) {
        this(student, worlds, 0, 0L);
    }

    /** @param draws play-outs per world with the opponents' cards drawn from the student's policy */
    public RolloutRescue(PolicyNet student, int worlds, int draws, long seed) {
        this(student, worlds, draws, draws > 0, seed);
    }

    public RolloutRescue(PolicyNet student, int worlds, int draws, boolean valueRanked, long seed) {
        if (worlds < 1) throw new IllegalArgumentException("at least one world");
        if (draws < 0) throw new IllegalArgumentException("draws cannot be negative");
        this.student = student;
        this.worlds = worlds;
        this.draws = draws;
        this.valueRanked = valueRanked;
        this.seed = seed;
    }

    /** What the play-outs found for one card, from the deciding seat's side: wins, card points, game value. */
    record Tally(Card card, int wins, int points, long value) {
        Tally(Card card, int wins, int points) { this(card, wins, points, 0L); }
    }

    /** Every legal card a candidate: the lost-position use. */
    Card choose(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                List<WorldSampler.World> sampled, Card fallback) {
        return choose(context, seen, sampled, context.legalCards, fallback);
    }

    /**
     * @param seen       the search's own evidence for this decision (its bids and discard)
     * @param sampled    the search's own worlds for this decision
     * @param candidates the cards to choose among (all legal, or the search's top-voted)
     * @param fallback   the search's card, kept on a full tie
     */
    Card choose(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                List<WorldSampler.World> sampled, Collection<Card> candidates, Card fallback) {
        List<Tally> tallies = tally(context, seen, sampled, candidates);
        int most = 0;
        int least = Integer.MAX_VALUE;
        for (Tally t : tallies) {
            most = Math.max(most, t.wins);
            least = Math.min(least, t.wins);
        }
        DECIDED.incrementAndGet();
        PLAYOUTS.addAndGet((long) Math.min(worlds, sampled.size()) * Math.max(1, draws));
        if (most > 0) ANY_WIN.incrementAndGet();
        if (most > least) SPREAD.incrementAndGet();
        BEST_WINS.addAndGet(most);
        return valueRanked ? pickByValue(tallies, fallback) : pick(tallies, fallback);
    }

    private static final AtomicLong DECIDED = new AtomicLong();
    private static final AtomicLong PLAYOUTS = new AtomicLong();
    private static final AtomicLong ANY_WIN = new AtomicLong();
    private static final AtomicLong SPREAD = new AtomicLong();
    private static final AtomicLong BEST_WINS = new AtomicLong();
    private static final AtomicLong FORWARDS = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();

    /** What the play-outs saw, for the arena's report; nothing when they never ran. */
    static void report() {
        long n = DECIDED.get();
        if (n == 0) return;
        System.out.printf("  play-outs: some card won in some play-out in %.1f%% of decisions; the cards' win counts "
                        + "differed in %.1f%%; the best card won %.1f%% of its play-outs on average%n",
                100.0 * ANY_WIN.get() / n, 100.0 * SPREAD.get() / n,
                100.0 * BEST_WINS.get() / Math.max(1, PLAYOUTS.get()));
        System.out.printf("  play-outs' cost: %,d positions through the net, %.1f ms a decision%n",
                FORWARDS.get(), NANOS.get() / 1e6 / n);
    }

    /** The most wins, then the most points; the search's own card on a full tie. */
    static Card pick(List<Tally> tallies, Card fallback) {
        Tally best = null;
        for (Tally t : tallies) {
            if (best == null || t.wins > best.wins || (t.wins == best.wins && t.points > best.points)) best = t;
        }
        if (best == null) return fallback;
        for (Tally t : tallies) {
            if (t.card.equals(fallback) && t.wins == best.wins && t.points == best.points) return fallback;
        }
        return best.card;
    }

    /** The most game value over the play-outs, then wins, then points; the search's own card on a full tie. */
    static Card pickByValue(List<Tally> tallies, Card fallback) {
        Tally best = null;
        for (Tally t : tallies) {
            if (best == null || t.value > best.value
                    || (t.value == best.value && (t.wins > best.wins
                    || (t.wins == best.wins && t.points > best.points)))) {
                best = t;
            }
        }
        if (best == null) return fallback;
        for (Tally t : tallies) {
            if (t.card.equals(fallback) && t.value == best.value && t.wins == best.wins && t.points == best.points) {
                return fallback;
            }
        }
        return best.card;
    }

    /** The decisions this instance has made, mixed into each decision's random streams. */
    private long decisions;

    /** One play-out in flight: which candidate it counts for, and its own random stream. */
    private record Flight(int candidate, Playout playout, SplittableRandom random) {}

    List<Tally> tally(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                      List<WorldSampler.World> sampled) {
        return tally(context, seen, sampled, context.legalCards);
    }

    /** All play-outs of the decision in lockstep, a batched forward pass a step. */
    List<Tally> tally(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                      List<WorldSampler.World> sampled, Collection<Card> candidates) {
        long started = System.nanoTime();
        Map<SkatAi.Seat, Integer> bids = seen == null ? Map.of() : seen.highestBids();
        List<Card> buried = seen == null ? null : seen.knownDiscard();
        long decision = decisions++;
        List<Card> cards = new ArrayList<>(candidates);
        int n = Math.min(worlds, sampled.size());
        int perWorld = Math.max(1, draws);
        List<Flight> flights = new ArrayList<>(cards.size() * n * perWorld);
        for (int c = 0; c < cards.size(); c++) {
            for (int w = 0; w < n; w++) {
                WorldSampler.World world = sampled.get(w);
                for (int d = 0; d < perWorld; d++) {
                    Playout playout = new Playout(context, world.hands(), world.skat());
                    playout.start(cards.get(c));
                    flights.add(new Flight(c, playout, stream(decision, w, d)));
                }
            }
        }
        List<Flight> waiting = new ArrayList<>(flights.size());
        List<SkatAi.DecisionContext> positions = new ArrayList<>(flights.size());
        while (true) {
            waiting.clear();
            positions.clear();
            for (Flight f : flights) {
                SkatAi.DecisionContext at = f.playout.next();
                if (at == null) continue;
                waiting.add(f);
                positions.add(at);
            }
            if (waiting.isEmpty()) break;
            float[][] inputs = new float[positions.size()][];
            for (int i = 0; i < inputs.length; i++) {
                inputs[i] = PolicyNet.features(SelfPlayExportMain.perfectMemory(positions.get(i), bids, buried));
            }
            float[][] scores = student.logits(inputs);
            FORWARDS.addAndGet(inputs.length);
            for (int i = 0; i < inputs.length; i++) {
                SkatAi.DecisionContext at = positions.get(i);
                waiting.get(i).playout.play(answer(context, at, scores[i], waiting.get(i).random));
            }
        }
        int[] wins = new int[cards.size()];
        int[] points = new int[cards.size()];
        long[] value = new long[cards.size()];
        boolean declares = declares(context);
        for (Flight f : flights) {
            Playout.Result r = f.playout.result();
            wins[f.candidate] += r.declarerWon() == declares ? 1 : 0;
            points[f.candidate] += declares ? r.declarerPoints() : 120 - r.declarerPoints();
            value[f.candidate] += declares ? r.gameValue() : -r.gameValue();
        }
        List<Tally> out = new ArrayList<>(cards.size());
        for (int c = 0; c < cards.size(); c++) out.add(new Tally(cards.get(c), wins[c], points[c], value[c]));
        NANOS.addAndGet(System.nanoTime() - started);
        return out;
    }

    /** The one-at-a-time form of {@link #tally}: the reference the lockstep form must equal. */
    List<Tally> tallyOneByOne(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                              List<WorldSampler.World> sampled, Collection<Card> candidates) {
        Map<SkatAi.Seat, Integer> bids = seen == null ? Map.of() : seen.highestBids();
        List<Card> buried = seen == null ? null : seen.knownDiscard();
        long decision = decisions++;
        boolean declares = declares(context);
        List<Tally> out = new ArrayList<>();
        int n = Math.min(worlds, sampled.size());
        int perWorld = Math.max(1, draws);
        for (Card card : candidates) {
            int wins = 0;
            int points = 0;
            long value = 0;
            for (int w = 0; w < n; w++) {
                WorldSampler.World world = sampled.get(w);
                for (int d = 0; d < perWorld; d++) {
                    SplittableRandom random = stream(decision, w, d);
                    Playout.Result r = new Playout(context, world.hands(), world.skat()).run(card, at -> answer(
                            context, at, student.logits(PolicyNet.features(
                                    SelfPlayExportMain.perfectMemory(at, bids, buried))), random));
                    wins += r.declarerWon() == declares ? 1 : 0;
                    points += declares ? r.declarerPoints() : 120 - r.declarerPoints();
                    value += declares ? r.gameValue() : -r.gameValue();
                }
            }
            out.add(new Tally(card, wins, points, value));
        }
        return out;
    }

    private SplittableRandom stream(long decision, int world, int draw) {
        // The same stream for every candidate card: compared on the same luck.
        return new SplittableRandom(Seeds.mix(seed, decision, (long) world * 1009 + draw));
    }

    private static boolean declares(SkatAi.DecisionContext context) {
        return context.mySeat == context.game.declarer;
    }

    /** The card a seat plays in a play-out: drawn for the deciding seat's opponents, the top card otherwise. */
    private Card answer(SkatAi.DecisionContext decision, SkatAi.DecisionContext at, float[] scores,
                        SplittableRandom random) {
        List<Card> legal = new ArrayList<>(at.legalCards);
        boolean ownSide = (at.mySeat == at.game.declarer) == declares(decision);
        if (draws == 0 || ownSide) return top(scores, legal);
        return draw(scores, legal, random);
    }

    /** The policy's highest-scoring legal card, ties to the earlier card: {@link PolicyNet#choose}'s rule. */
    static Card top(float[] scores, List<Card> legal) {
        Card best = null;
        float high = Float.NEGATIVE_INFINITY;
        for (Card card : legal) {
            float score = scores[BeliefEncoding.index(card)];
            if (best == null || score > high) {
                best = card;
                high = score;
            }
        }
        return best;
    }

    /** A legal card drawn from the softmax of the policy's scores over the legal cards. */
    static Card draw(float[] logits, List<Card> legal, SplittableRandom random) {
        double top = Double.NEGATIVE_INFINITY;
        for (Card card : legal) top = Math.max(top, logits[BeliefEncoding.index(card)]);
        double[] weight = new double[legal.size()];
        double total = 0;
        for (int i = 0; i < legal.size(); i++) {
            weight[i] = Math.exp(logits[BeliefEncoding.index(legal.get(i))] - top);
            total += weight[i];
        }
        double u = random.nextDouble() * total;
        for (int i = 0; i < legal.size(); i++) {
            u -= weight[i];
            if (u < 0) return legal.get(i);
        }
        return legal.get(legal.size() - 1);
    }

    private static PolicyNet cached;

    /** The student from -Dpolicy.model.dir, else policy-model/ here or above; loaded once. */
    public static synchronized PolicyNet student() {
        if (cached != null) return cached;
        String named = System.getProperty("policy.model.dir");
        List<Path> candidates = named != null && !named.isBlank() ? List.of(Path.of(named))
                : List.of(Path.of("policy-model"), Path.of("..", "policy-model"), Path.of("..", "..", "policy-model"));
        for (Path dir : candidates) {
            if (!Files.isRegularFile(dir.resolve("policy.bin"))) continue;
            try (InputStream weights = Files.newInputStream(dir.resolve("policy.bin"));
                 InputStream parity = Files.newInputStream(dir.resolve("policy-parity.bin"))) {
                cached = PolicyNet.load(weights, parity);
                return cached;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("no policy-model/policy.bin for the play-outs' student");
    }
}
