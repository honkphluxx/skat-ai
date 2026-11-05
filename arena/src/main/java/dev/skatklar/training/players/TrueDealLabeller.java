package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.training.data.SelfPlayExportMain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Every legal card of a real decision played out on the real cards
 * (docs/training-plan.md 2.10, route 1: the all-cards exporter).
 *
 * <p>Each candidate card is played for the deciding seat and the game finished
 * {@code k} times by the student at all three seats, every seat reading only
 * its own evidence -- a perfect memory, the bids heard, and for the declarer
 * its own discard -- exactly as {@link RolloutRescue}'s play-outs read it, but
 * in the one world that is the table's. The deciding seat's own side plays the
 * student's top card; the other side's cards are drawn from the student's
 * softmax at {@code temperature} (rollout-32x4's opponents). Draw {@code d} uses
 * the same random stream for every candidate card, so the cards are compared on
 * the same luck. Everything is counted from the deciding seat's side: its wins,
 * its card points, the game value signed for it.
 *
 * <p>With a seat net for every seat ({@link #label(SkatAi.DecisionContext, List,
 * List, Map, List, Collection, long, PolicyNet[])}), every card of a play-out is
 * played by the net of the seat that plays it -- the main line's seating, so
 * a card is valued against the opponents actually at the table. Without, the
 * labeller's own net plays every seat.
 *
 * <p>With a search ({@link #withSearch}), every position of a play-out from
 * trick {@code searchFrom} on is played by a small search instead of a net:
 * {@code searchWorlds} worlds sampled from that seat's own evidence (its hand,
 * the cards seen, the voids shown, the declarer its discard -- never the
 * table's hands), each legal card asked of the double-dummy solver whether the
 * declarer still reaches 61 in that world (the target less what the declarer
 * has banked, the world's skat counted), the card with the most votes for the
 * seat's side played, ties to the seat's net's own preference. The shipped
 * search's vote (Personality.targetFor at risk 0), without its belief, cushion
 * or ladder. It plays every seat from that trick on, so a label then says what
 * a card is worth with a competent, fixed finish rather than the student's own.
 *
 * <p>The play-outs of a decision run in lockstep, each step's waiting positions
 * through one batched forward pass a net ({@link PolicyNet#logits(float[][])},
 * bit for bit the single pass, so the grouping changes no answer).
 *
 * <p>Thread-safe if the net is: nothing is kept between calls.
 */
public final class TrueDealLabeller {

    private final PolicyNet net;
    private final int k;
    private final double temperature;
    /** False only in tests: every seat by the top card, the play-outs then a replay of a top-card game. */
    private final boolean drawOpponents;
    /** The first trick (1-10) the search plays; 11 for never. */
    private final int searchFrom;
    private final int searchWorlds;
    /** A diagnostic: from this trick (1-10) on a play-out's random stream is replaced by a fresh one; 11 for never. */
    private final int reseedFrom;

    public TrueDealLabeller(PolicyNet net, int k, double temperature) {
        this(net, k, temperature, true, 11, 0, 11);
    }

    TrueDealLabeller(PolicyNet net, int k, double temperature, boolean drawOpponents) {
        this(net, k, temperature, drawOpponents, 11, 0, 11);
    }

    private TrueDealLabeller(PolicyNet net, int k, double temperature, boolean drawOpponents, int searchFrom,
                             int searchWorlds, int reseedFrom) {
        if (k < 1 || k > 255) throw new IllegalArgumentException("k is 1 to 255, not " + k);
        if (!(temperature > 0) || Double.isInfinite(temperature)) {
            throw new IllegalArgumentException("the temperature is a positive number, not " + temperature);
        }
        if (searchFrom < 1 || searchFrom > 11) throw new IllegalArgumentException("the search's trick is 1-10, or 11 for none");
        if (searchFrom <= 10 && searchWorlds < 1) throw new IllegalArgumentException("a search needs worlds");
        if (reseedFrom < 1 || reseedFrom > 11) throw new IllegalArgumentException("the reseed's trick is 1-10, or 11 for none");
        if (searchFrom <= 10 && reseedFrom <= 10) {
            throw new IllegalArgumentException("the search or the reseed, not both: the reseed is the search's control");
        }
        this.net = net;
        this.k = k;
        this.temperature = temperature;
        this.drawOpponents = drawOpponents;
        this.searchFrom = searchFrom;
        this.searchWorlds = searchWorlds;
        this.reseedFrom = reseedFrom;
    }

    /** This labeller, the play-outs' positions from trick {@code fromTrick} on played by a {@code worlds}-world search. */
    public TrueDealLabeller withSearch(int fromTrick, int worlds) {
        TrueDealLabeller searched = new TrueDealLabeller(net, k, temperature, drawOpponents, fromTrick, worlds,
                reseedFrom);
        searched.tap = tap;
        return searched;
    }

    /**
     * A diagnostic, the search's control: this labeller, each play-out from
     * trick {@code fromTrick} on drawing from a fresh random stream (still the
     * same for every candidate of a draw), the student still playing. Against
     * the plain labeller it shares every play-out up to that trick and parts
     * from it after, as the search does, so the two show what play-out noise
     * alone does to the labels.
     */
    public TrueDealLabeller withReseed(int fromTrick) {
        TrueDealLabeller reseeded = new TrueDealLabeller(net, k, temperature, drawOpponents, searchFrom, searchWorlds,
                fromTrick);
        reseeded.tap = tap;
        return reseeded;
    }

    public int reseedFrom() { return reseedFrom; }

    public int searchFrom() { return searchFrom; }

    public int searchWorlds() { return searchWorlds; }

    public int k() { return k; }

    /**
     * Tests only: every seat plays the top card, the opponents too, so a play-out
     * of the card the main line played is a replay of a top-card main line.
     */
    public static TrueDealLabeller topOnly(PolicyNet net, int k) {
        return new TrueDealLabeller(net, k, 1.0, false);
    }

    /** Tests only: this labeller, telling {@code tap} every position it feeds the net. */
    public TrueDealLabeller withTap(Consumer<Fed> tap) {
        TrueDealLabeller tapped = new TrueDealLabeller(net, k, temperature, drawOpponents, searchFrom, searchWorlds,
                reseedFrom);
        tapped.tap = tap;
        return tapped;
    }

    /**
     * What the play-outs found, per candidate card in the order given, from
     * the deciding seat's side: summed over the {@code k} draws, and the game
     * value of each draw on its own (for the noise statistics).
     */
    public record Labels(List<Card> cards, int[] wins, int[] points, long[] value, int[][] valueByDraw,
                         long positions, long searches, long searchNanos) {}

    /** One position a play-out handed the net, for tests: whose card, which draw, the input. */
    public record Fed(int candidate, int draw, SkatAi.Seat seat, float[] input, PolicyNet net, boolean searched,
                      int trick) {}

    /** Tests only: hears every position fed to the net. */
    private Consumer<Fed> tap;

    /**
     * @param at               the decision, as the deciding seat sees it
     * @param hands            every seat's cards at this moment, by seat ordinal (the table's)
     * @param skat             the two cards that count for the declarer: its discard, or the
     *                         untouched skat of a hand game
     * @param bids             the highest bid each seat made, as every seat heard it
     * @param declarersDiscard what the declarer remembers having discarded; null in a hand game
     * @param candidates       the cards to label
     * @param streamSeed       the decision's random streams; draw d of every candidate uses
     *                         {@code mix(streamSeed, d)}
     */
    public Labels label(SkatAi.DecisionContext at, List<List<Card>> hands, List<Card> skat,
                        Map<SkatAi.Seat, Integer> bids, List<Card> declarersDiscard, Collection<Card> candidates,
                        long streamSeed) {
        return label(at, hands, skat, bids, declarersDiscard, candidates, streamSeed, new PolicyNet[] {net, net, net});
    }

    /** As above, each seat's cards played by {@code bySeat[seat.ordinal()]}. */
    public Labels label(SkatAi.DecisionContext at, List<List<Card>> hands, List<Card> skat,
                        Map<SkatAi.Seat, Integer> bids, List<Card> declarersDiscard, Collection<Card> candidates,
                        long streamSeed, PolicyNet[] bySeat) {
        if (bySeat.length != 3) throw new IllegalArgumentException("a net for each of the three seats");
        final class Flight {
            final int candidate;
            final int draw;
            final Playout playout;
            SplittableRandom random;
            boolean reseeded;

            Flight(int candidate, int draw, Playout playout, SplittableRandom random) {
                this.candidate = candidate;
                this.draw = draw;
                this.playout = playout;
                this.random = random;
            }
        }
        List<Card> cards = new ArrayList<>(candidates);
        List<Flight> flights = new ArrayList<>(cards.size() * k);
        for (int c = 0; c < cards.size(); c++) {
            for (int d = 0; d < k; d++) {
                Playout playout = new Playout(at, hands, skat);
                playout.start(cards.get(c));
                flights.add(new Flight(c, d, playout, stream(streamSeed, d)));
            }
        }
        boolean declares = at.mySeat == at.game.declarer;
        long positions = 0;
        long searches = 0;
        long searchNanos = 0;
        List<Flight> waiting = new ArrayList<>(flights.size());
        List<SkatAi.DecisionContext> contexts = new ArrayList<>(flights.size());
        while (true) {
            waiting.clear();
            contexts.clear();
            for (Flight f : flights) {
                SkatAi.DecisionContext next = f.playout.next();
                if (next == null) continue;
                waiting.add(f);
                contexts.add(next);
            }
            if (waiting.isEmpty()) break;
            float[][] inputs = new float[contexts.size()][];
            for (int i = 0; i < inputs.length; i++) {
                SkatAi.DecisionContext p = contexts.get(i);
                inputs[i] = PolicyNet.features(SelfPlayExportMain.perfectMemory(p, bids,
                        knownDiscard(p, declarersDiscard)));
                if (tap != null) {
                    tap.accept(new Fed(waiting.get(i).candidate, waiting.get(i).draw, p.mySeat, inputs[i],
                            bySeat[p.mySeat.ordinal()], searches(p), p.history.completedTricks.size() + 1));
                }
            }
            float[][] scores = byNet(inputs, contexts, bySeat);
            positions += inputs.length;
            for (int i = 0; i < inputs.length; i++) {
                SkatAi.DecisionContext p = contexts.get(i);
                List<Card> legal = new ArrayList<>(p.legalCards);
                Card card;
                Flight flight = waiting.get(i);
                if (!flight.reseeded && p.history.completedTricks.size() + 1 >= reseedFrom) {
                    // Drawn from the stream itself, so every candidate of a draw that got
                    // here on the same luck goes on with the same fresh luck.
                    flight.random = new SplittableRandom(Seeds.mix(flight.random.nextLong(), 0x5EED5EEDL));
                    flight.reseeded = true;
                }
                if (searches(p)) {
                    long started = System.nanoTime();
                    card = searchCard(p, knownDiscard(p, declarersDiscard), searchWorlds,
                            new Random(waiting.get(i).random.nextLong()), scores[i]);
                    searchNanos += System.nanoTime() - started;
                    searches++;
                } else {
                    boolean ownSide = (p.mySeat == p.game.declarer) == declares;
                    card = !drawOpponents || ownSide ? RolloutRescue.top(scores[i], legal)
                            : RolloutRescue.draw(scores[i], legal, temperature, waiting.get(i).random);
                }
                waiting.get(i).playout.play(card);
            }
        }
        int[] wins = new int[cards.size()];
        int[] points = new int[cards.size()];
        long[] value = new long[cards.size()];
        int[][] byDraw = new int[cards.size()][k];
        for (Flight f : flights) {
            Playout.Result r = f.playout.result();
            int signed = declares ? r.gameValue() : -r.gameValue();
            wins[f.candidate] += r.declarerWon() == declares ? 1 : 0;
            points[f.candidate] += declares ? r.declarerPoints() : 120 - r.declarerPoints();
            value[f.candidate] += signed;
            byDraw[f.candidate][f.draw] = signed;
        }
        return new Labels(List.copyOf(cards), wins, points, value, byDraw, positions, searches, searchNanos);
    }

    /** What the seat to move knows of the skat: the declarer its own discard, a defender nothing. */
    static List<Card> knownDiscard(SkatAi.DecisionContext p, List<Card> declarersDiscard) {
        return p.mySeat == p.game.declarer ? declarersDiscard : null;
    }

    /** Whether the search, not a net, plays this play-out position. */
    private boolean searches(SkatAi.DecisionContext p) {
        return p.history.completedTricks.size() + 1 >= searchFrom;
    }

    /**
     * The search's card for the seat to move, from its own evidence only: the
     * position as the seat sees it ({@code at} holds its hand and the public
     * play, nothing of the other hands) and, for the declarer, its discard.
     * {@code worlds} worlds are sampled from that; in each, every legal card
     * gets a vote for the seat's side when the solver says the declarer
     * reaches (as declarer) or misses (as defender) 61 less what it has banked
     * with that world's skat. Most votes wins; ties to the highest of
     * {@code scores} (the seat's net); a world the sampler cannot fill is left
     * out, and with no world at all the net's card is played.
     */
    static Card searchCard(SkatAi.DecisionContext at, List<Card> knownDiscard, int worlds, Random random,
                           float[] scores) {
        List<Card> legal = new ArrayList<>(at.legalCards);
        int[] votes = new int[legal.size()];
        boolean iDeclare = at.mySeat == at.game.declarer;
        WorldSampler sampler = WorldSampler.forDecision(at, knownDiscard);
        List<Card> trick = new ArrayList<>(3);
        for (SkatAi.PlayedCard play : at.currentTrick.plays) trick.add(play.card);
        int banked = at.derived.cardPoints.getOrDefault(at.game.declarer, 0);
        for (int w = 0; w < worlds; w++) {
            WorldSampler.World world = sampler.draw(random);
            if (world == null) continue;
            int target = 61 - banked - SkatRules.cardPoints(world.skat());
            // Reached already in this world: every card reaches, so the world adds the
            // same to every card (a vote each or none) and cannot move the choice.
            if (target <= 0) continue;
            for (DoubleDummySolver.Verdict v : DoubleDummySolver.movesReaching(at.game.contract, at.game.declarer,
                    at.mySeat, world.hands(), at.currentTrick.leader, trick, target)) {
                int c = legal.indexOf(v.card());
                if (c >= 0 && v.reachesTarget() == iDeclare) votes[c]++;
            }
        }
        int best = -1;
        for (int c = 0; c < legal.size(); c++) {
            if (best < 0 || votes[c] > votes[best]
                    || (votes[c] == votes[best]
                    && scores[dev.skatklar.demo.belief.BeliefEncoding.index(legal.get(c))]
                    > scores[dev.skatklar.demo.belief.BeliefEncoding.index(legal.get(best))])) {
                best = c;
            }
        }
        return legal.get(best);
    }

    /** Each input through the net of the seat to move: one batch a distinct net, the answers back in order. */
    private static float[][] byNet(float[][] inputs, List<SkatAi.DecisionContext> contexts, PolicyNet[] bySeat) {
        if (bySeat[0] == bySeat[1] && bySeat[1] == bySeat[2]) return bySeat[0].logits(inputs);
        float[][] out = new float[inputs.length][];
        for (int s = 0; s < 3; s++) {
            PolicyNet net = bySeat[s];
            boolean seen = false;
            for (int t = 0; t < s; t++) seen |= bySeat[t] == net;
            if (seen) continue;
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < inputs.length; i++) if (bySeat[contexts.get(i).mySeat.ordinal()] == net) rows.add(i);
            if (rows.isEmpty()) continue;
            float[][] batch = new float[rows.size()][];
            for (int i = 0; i < batch.length; i++) batch[i] = inputs[rows.get(i)];
            float[][] answers = net.logits(batch);
            for (int i = 0; i < batch.length; i++) out[rows.get(i)] = answers[i];
        }
        return out;
    }

    static SplittableRandom stream(long streamSeed, int draw) {
        // Depends on the decision and the draw only, never on the card: the
        // candidates are compared on the same luck.
        return new SplittableRandom(Seeds.mix(streamSeed, draw, 0xA11CA4D5L));
    }

    /** The card the labels rank first: the most game value, then wins, then points; the earlier card on a full tie. */
    public static int best(Labels labels) {
        return best(labels.value(), labels.wins(), labels.points());
    }

    static int best(long[] value, int[] wins, int[] points) {
        int best = 0;
        for (int c = 1; c < value.length; c++) {
            if (value[c] > value[best] || (value[c] == value[best] && (wins[c] > wins[best]
                    || (wins[c] == wins[best] && points[c] > points[best])))) {
                best = c;
            }
        }
        return best;
    }

    /** The policy's top legal card, as the play-outs pick it for the own side ({@link RolloutRescue#top}). */
    public static Card topCard(float[] scores, List<Card> legal) {
        return RolloutRescue.top(scores, legal);
    }

    /** A legal card drawn from the policy's softmax at a temperature, as the play-outs draw the opponents'. */
    public static Card drawCard(float[] scores, List<Card> legal, double temperature, SplittableRandom random) {
        return RolloutRescue.draw(scores, legal, temperature, random);
    }
}
