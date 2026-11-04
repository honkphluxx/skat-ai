package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.training.data.SelfPlayExportMain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    public TrueDealLabeller(PolicyNet net, int k, double temperature) {
        this(net, k, temperature, true);
    }

    TrueDealLabeller(PolicyNet net, int k, double temperature, boolean drawOpponents) {
        if (k < 1 || k > 255) throw new IllegalArgumentException("k is 1 to 255, not " + k);
        if (!(temperature > 0) || Double.isInfinite(temperature)) {
            throw new IllegalArgumentException("the temperature is a positive number, not " + temperature);
        }
        this.net = net;
        this.k = k;
        this.temperature = temperature;
        this.drawOpponents = drawOpponents;
    }

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
        TrueDealLabeller tapped = new TrueDealLabeller(net, k, temperature, drawOpponents);
        tapped.tap = tap;
        return tapped;
    }

    /**
     * What the play-outs found, per candidate card in the order given, from
     * the deciding seat's side: summed over the {@code k} draws, and the game
     * value of each draw on its own (for the noise statistics).
     */
    public record Labels(List<Card> cards, int[] wins, int[] points, long[] value, int[][] valueByDraw,
                         long positions) {}

    /** One position a play-out handed the net, for tests: whose card, which draw, the input. */
    public record Fed(int candidate, int draw, SkatAi.Seat seat, float[] input, PolicyNet net) {}

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
        record Flight(int candidate, int draw, Playout playout, SplittableRandom random) {}
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
                        p.mySeat == p.game.declarer ? declarersDiscard : null));
                if (tap != null) {
                    tap.accept(new Fed(waiting.get(i).candidate, waiting.get(i).draw, p.mySeat, inputs[i],
                            bySeat[p.mySeat.ordinal()]));
                }
            }
            float[][] scores = byNet(inputs, contexts, bySeat);
            positions += inputs.length;
            for (int i = 0; i < inputs.length; i++) {
                SkatAi.DecisionContext p = contexts.get(i);
                List<Card> legal = new ArrayList<>(p.legalCards);
                boolean ownSide = (p.mySeat == p.game.declarer) == declares;
                Card card = !drawOpponents || ownSide ? RolloutRescue.top(scores[i], legal)
                        : RolloutRescue.draw(scores[i], legal, temperature, waiting.get(i).random);
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
        return new Labels(List.copyOf(cards), wins, points, value, byDraw, positions);
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
