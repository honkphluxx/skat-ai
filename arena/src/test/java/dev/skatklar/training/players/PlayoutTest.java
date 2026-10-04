package dev.skatklar.training.players;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.TableObserver;
import dev.skatklar.training.data.SelfPlayExportMain;
import dev.skatklar.training.data.SelfPlayExportTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * A play-out is the engine's game, and the play-outs rescue only what is lost.
 *
 * <p>The engine plays fixed-contract games with a fixed policy at every seat
 * (the lowest card by index), and the test keeps every decision with the
 * hands as they were at that moment. From each such decision a play-out is
 * started on the true hands with the same policy: it must end on exactly the
 * engine's declarer points, and every position it hands the policy on the
 * way must encode, byte for byte, as the engine's own position at the same
 * step -- which holds the play-out's history, voids, played cards, points and
 * trick to the engine's. Then the rescue's ranking, and a short arena game
 * with the play-outs as the rescuer.
 */
public class PlayoutTest {

    static Card lowest(SkatAi.DecisionContext context) {
        return context.legalCards.stream().min(Comparator.comparingInt(BeliefEncoding::index)).orElseThrow();
    }

    static byte[] encode(SkatAi.DecisionContext context) {
        return BeliefEncoding.quantise(BeliefEncoding.encode(
                SelfPlayExportMain.perfectMemory(context, Map.of(), null)));
    }

    /** A decision as the engine made it, with every hand at that moment. */
    record Step(SkatAi.DecisionContext context, List<List<Card>> hands, List<Card> skat, Card played) {}

    /** Plays the lowest card and writes every decision down, hands included. */
    static final class Recorder implements SkatAiProvider, TableObserver {
        final List<Step> steps;
        GameEngine engine;

        Recorder(List<Step> steps) { this.steps = steps; }

        @Override public void observe(GameEngine engine) { this.engine = engine; }
        @Override public SkatAi.AiDescriptor descriptor() { return new SkatAi.AiDescriptor("lowest", "lowest", false); }
        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    Card card = lowest(context);
                    GameEngine.Snapshot snapshot = engine.snapshot();
                    List<List<Card>> hands = new ArrayList<>();
                    for (List<Card> hand : snapshot.hands) hands.add(new ArrayList<>(hand));
                    steps.add(new Step(context, hands, new ArrayList<>(snapshot.skat), card));
                    return card;
                }
                @Override public java.util.Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    List<Card> all = new ArrayList<>(context.hand);
                    all.addAll(context.skat);
                    all.sort(Comparator.comparingInt(BeliefEncoding::index));
                    return java.util.Set.of(all.get(0), all.get(1));
                }
            };
        }
    }

    @Test public void aPlayoutIsTheEnginesGame() {
        long seed = 17;
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        int games = 0;
        int compared = 0;
        for (int i = 0; games < 6 && i < 40; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            List<Step> steps = new ArrayList<>();
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, new Recorder(steps));
            GameOutcome outcome = GameRunner.playFixed(board, seating, fixed, i);
            games++;
            for (int k = 0; k < steps.size(); k++) {
                Step from = steps.get(k);
                List<byte[]> seen = new ArrayList<>();
                Playout.Result result = new Playout(from.context(), from.hands(), from.skat()).run(from.played(), at -> {
                    seen.add(encode(at));
                    return lowest(at);
                });
                assertEquals("board " + i + " from decision " + k + ": the declarer's points",
                        outcome.declarerPoints(), result.declarerPoints());
                assertEquals("board " + i + " from decision " + k + ": the result",
                        outcome.declarerPoints() >= 61, result.declarerWon());
                assertEquals("board " + i + " from decision " + k + ": the game value as settled",
                        outcome.gameValue(), result.gameValue());
                List<byte[]> engine = new ArrayList<>();
                for (Step later : steps.subList(k + 1, steps.size())) {
                    if (later.context().legalCards.size() > 1) engine.add(encode(later.context()));
                }
                assertEquals("board " + i + " from decision " + k + ": positions handed to the policy",
                        engine.size(), seen.size());
                for (int j = 0; j < seen.size(); j++) {
                    assertArrayEquals("board " + i + " from decision " + k + ", step " + j, engine.get(j), seen.get(j));
                }
                compared += seen.size();
            }
        }
        assertTrue("games: " + games, games >= 5);
        assertTrue("positions compared: " + compared, compared > 500);
    }

    @Test public void theRescueTakesTheMostWinsThenThePoints() {
        Card a = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        Card b = new Card(Card.Suit.CLUBS, Card.Rank.TEN);
        Card c = new Card(Card.Suit.HEARTS, Card.Rank.SEVEN);
        assertEquals(b, RolloutRescue.pick(List.of(new RolloutRescue.Tally(a, 3, 900),
                new RolloutRescue.Tally(b, 5, 100), new RolloutRescue.Tally(c, 4, 999)), a));
        assertEquals("points break a tie in wins", c, RolloutRescue.pick(List.of(new RolloutRescue.Tally(a, 2, 500),
                new RolloutRescue.Tally(c, 2, 600)), a));
        assertEquals("the search's card on a full tie", a, RolloutRescue.pick(List.of(
                new RolloutRescue.Tally(c, 0, 400), new RolloutRescue.Tally(a, 0, 400)), a));
    }

    @Test public void theValueRankingTakesTheMostValueThenWinsThenPoints() {
        Card a = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        Card b = new Card(Card.Suit.CLUBS, Card.Rank.TEN);
        Card c = new Card(Card.Suit.HEARTS, Card.Rank.SEVEN);
        assertEquals("value first", c, RolloutRescue.pickByValue(List.of(new RolloutRescue.Tally(a, 9, 900, -40),
                new RolloutRescue.Tally(c, 1, 100, -10)), a));
        assertEquals("then wins", b, RolloutRescue.pickByValue(List.of(new RolloutRescue.Tally(a, 2, 900, -10),
                new RolloutRescue.Tally(b, 3, 100, -10)), a));
        assertEquals("then points", c, RolloutRescue.pickByValue(List.of(new RolloutRescue.Tally(a, 2, 500, -10),
                new RolloutRescue.Tally(c, 2, 600, -10)), a));
        assertEquals("the search's card on a full tie", a, RolloutRescue.pickByValue(List.of(
                new RolloutRescue.Tally(c, 0, 400, -50), new RolloutRescue.Tally(a, 0, 400, -50)), a));
    }

    @Test public void aDrawnCardFollowsThePolicy() {
        Card low = new Card(Card.Suit.CLUBS, Card.Rank.SEVEN);
        Card mid = new Card(Card.Suit.CLUBS, Card.Rank.EIGHT);
        Card high = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        float[] logits = new float[64];
        logits[BeliefEncoding.index(low)] = 0f;
        logits[BeliefEncoding.index(mid)] = (float) Math.log(3);
        logits[BeliefEncoding.index(high)] = (float) Math.log(6);
        logits[BeliefEncoding.index(new Card(Card.Suit.SPADES, Card.Rank.ACE))] = 50f; // not legal: never drawn
        java.util.SplittableRandom random = new java.util.SplittableRandom(3);
        Map<Card, Integer> counts = new java.util.HashMap<>();
        int n = 20_000;
        for (int i = 0; i < n; i++) counts.merge(RolloutRescue.draw(logits, List.of(low, mid, high), random), 1, Integer::sum);
        assertEquals(3, counts.size());
        assertEquals("1 in 10", 0.1, counts.get(low) / (double) n, 0.01);
        assertEquals("3 in 10", 0.3, counts.get(mid) / (double) n, 0.015);
        assertEquals("6 in 10", 0.6, counts.get(high) / (double) n, 0.015);
    }

    @Test public void theDrawnPlayoutsAreReproducibleAndDiffer() throws Exception {
        float[] bias = new float[PolicyNet.OUTPUTS];
        for (int at = 0; at < 32; at++) bias[at] = (at % 5) * 0.3f;
        PolicyNet net = SelfPlayExportTest.constantNet(bias);
        long seed = 17;
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        for (int i = 0; i < 20; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            List<Step> steps = new ArrayList<>();
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, new Recorder(steps));
            GameRunner.playFixed(board, seating, fixed, i);
            // The declarer's second card: a position with the rest of the game to play.
            Step at = steps.stream().filter(s -> s.context().mySeat == fixed.declarer()
                    && s.context().history.completedTricks.size() == 1 && s.context().legalCards.size() > 1)
                    .findFirst().orElse(null);
            if (at == null) continue;
            List<dev.skatklar.demo.search.WorldSampler.World> world = List.of(
                    new dev.skatklar.demo.search.WorldSampler.World(at.hands(), at.skat()));
            List<RolloutRescue.Tally> one = new RolloutRescue(net, 1, 16, 99).tally(at.context(), null, world);
            List<RolloutRescue.Tally> again = new RolloutRescue(net, 1, 16, 99).tally(at.context(), null, world);
            assertEquals("the same seed, the same play-outs", one, again);
            List<RolloutRescue.Tally> top = new RolloutRescue(net, 1).tally(at.context(), null, world);
            boolean spread = false;
            for (RolloutRescue.Tally t : one) {
                // 16 drawn play-outs of one world: at least one card's points must differ from 16 x its top-card play-out.
                RolloutRescue.Tally same = top.stream().filter(x -> x.card().equals(t.card())).findFirst().orElseThrow();
                if (t.points() != 16 * same.points()) spread = true;
            }
            assertTrue("drawn defences play differently from the top card", spread);
            return;
        }
        throw new AssertionError("no position found");
    }

    @Test public void theRescuedPlayerPlaysWholeGames() throws Exception {
        float[] bias = new float[PolicyNet.OUTPUTS];
        for (int at = 0; at < 32; at++) bias[at] = at / 4f;
        PolicyNet net = SelfPlayExportTest.constantNet(bias);
        long seed = 5;
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        long handedBefore = FlatHandoverProvider.handed();
        int games = 0;
        for (int i = 0; games < 4 && i < 30; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, new FlatHandoverProvider("flat:test/rollout", new SearchAiProvider(
                        new GreedyAiProvider(), 4, seed + i * 3 + seat.ordinal()), new RolloutRescue(net, 4)));
            }
            GameOutcome outcome = GameRunner.playFixed(board, seating, fixed, i);
            assertEquals("no rule broken", 0, outcome.ruleViolationsOf(fixed.declarer()));
            games++;
        }
        assertTrue("games: " + games, games >= 3);
        assertTrue("lost positions played out: " + (FlatHandoverProvider.handed() - handedBefore),
                FlatHandoverProvider.handed() > handedBefore);
    }
}
