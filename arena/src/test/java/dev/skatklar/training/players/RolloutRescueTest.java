package dev.skatklar.training.players;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The lockstep play-outs are the one-at-a-time play-outs, counted from the
 * deciding seat's side, and the scopes hand over what they name.
 *
 * <p>On a net of random weights (so every position gets its own answer) and
 * on decisions from real engine games, as declarer and as defender, with the
 * top card and with drawn opponents, over all legal cards and over two: the
 * batched tally must equal the reference tally exactly. A defender's tally
 * must be the declarer's result turned round. A choice among candidates must
 * be one of them. Then the scope's spelling and the tie rule.
 */
public class RolloutRescueTest {

    static PolicyNet randomNet(long seed) throws IOException {
        Random random = new Random(seed);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int inputs = BeliefEncoding.SIZE;
        int hidden = 24;
        int outputs = PolicyNet.OUTPUTS;
        for (int v : new int[] {PolicyNet.MAGIC, 2, BeliefEncoding.VERSION, inputs, hidden, 1, outputs, 0x1F}) {
            out.writeInt(v);
        }
        for (int at = 0; at < hidden * inputs; at++) out.writeFloat((float) (random.nextGaussian() * 0.3));
        for (int at = 0; at < hidden; at++) out.writeFloat((float) (random.nextGaussian() * 0.1));
        for (int at = 0; at < hidden; at++) out.writeFloat(1f);
        for (int at = 0; at < hidden; at++) out.writeFloat(0f);
        for (int at = 0; at < outputs * hidden; at++) out.writeFloat((float) (random.nextGaussian() * 0.5));
        for (int at = 0; at < outputs; at++) out.writeFloat(0f);
        return PolicyNet.load(new ByteArrayInputStream(bytes.toByteArray()), null);
    }

    /** Decisions of real fixed-contract games, with every hand at that moment. */
    static List<PlayoutTest.Step> steps(long seed, int games) {
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        List<PlayoutTest.Step> all = new ArrayList<>();
        int played = 0;
        for (int i = 0; played < games && i < 40; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, new PlayoutTest.Recorder(all));
            GameRunner.playFixed(board, seating, fixed, i);
            played++;
        }
        return all;
    }

    @Test public void lockstepIsOneByOne() throws IOException {
        PolicyNet net = randomNet(3);
        int compared = 0;
        int asDefender = 0;
        for (PlayoutTest.Step step : steps(23, 3)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2 || at.history.completedTricks.size() > 6) continue;
            List<WorldSampler.World> world = List.of(new WorldSampler.World(step.hands(), step.skat()));
            List<Card> two = new ArrayList<>(at.legalCards).subList(0, 2);
            for (int draws : new int[] {0, 3}) {
                for (List<Card> candidates : List.of(new ArrayList<>(at.legalCards), two)) {
                    List<RolloutRescue.Tally> lockstep = new RolloutRescue(net, 1, draws, draws > 0, 7)
                            .tally(at, null, world, candidates);
                    List<RolloutRescue.Tally> reference = new RolloutRescue(net, 1, draws, draws > 0, 7)
                            .tallyOneByOne(at, null, world, candidates);
                    assertEquals("lockstep against one by one", reference, lockstep);
                    compared++;
                }
            }
            if (at.mySeat != at.game.declarer) asDefender++;
        }
        assertTrue("tallies compared: " + compared, compared > 40);
        assertTrue("defender decisions among them: " + asDefender, asDefender > 5);
    }

    @Test public void aDefendersTallyIsTheDeclarersResultTurnedRound() throws IOException {
        PolicyNet net = randomNet(4);
        int checked = 0;
        for (PlayoutTest.Step step : steps(29, 2)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2 || at.mySeat == at.game.declarer) continue;
            Card card = at.legalCards.iterator().next();
            RolloutRescue.Tally t = new RolloutRescue(net, 1).tally(at, null,
                    List.of(new WorldSampler.World(step.hands(), step.skat())), List.of(card)).get(0);
            Playout.Result r = new Playout(at, step.hands(), step.skat()).run(card, x -> RolloutRescue.top(
                    net.logits(PolicyNet.features(dev.skatklar.training.data.SelfPlayExportMain.perfectMemory(
                            x, Map.of(), null))), new ArrayList<>(x.legalCards)));
            assertEquals("a defender wins when the declarer loses", r.declarerWon() ? 0 : 1, t.wins());
            assertEquals("the defenders' card points", 120 - r.declarerPoints(), t.points());
            assertEquals("the defenders' value is the declarer's turned round", -r.gameValue(), t.value());
            checked++;
        }
        assertTrue("defender decisions checked: " + checked, checked > 10);
    }

    @Test public void aChoiceAmongCandidatesIsOneOfThem() throws IOException {
        PolicyNet net = randomNet(5);
        int checked = 0;
        for (PlayoutTest.Step step : steps(31, 2)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 3) continue;
            List<Card> legal = new ArrayList<>(at.legalCards);
            List<Card> candidates = List.of(legal.get(1), legal.get(2));
            Card chosen = new RolloutRescue(net, 1, 2, 11).choose(at, null,
                    List.of(new WorldSampler.World(step.hands(), step.skat())), candidates, legal.get(0));
            assertTrue("chosen among the candidates", candidates.contains(chosen));
            checked++;
        }
        assertTrue("checked: " + checked, checked > 5);
    }

    @Test public void theScopeIsSpelledOut() {
        FlatHandoverProvider.Scope all = FlatHandoverProvider.Scope.parse("lost-decl,lost-def,tie0-decl,tie2-def");
        assertEquals(new FlatHandoverProvider.Scope(true, true, 0, 2), all);
        assertEquals(FlatHandoverProvider.Scope.LOST_DECLARER, FlatHandoverProvider.Scope.parse("lost-decl"));
        assertTrue(all.covers(true));
        assertFalse(FlatHandoverProvider.Scope.parse("tie1-decl").covers(false));
        for (String bad : new String[] {"lost", "tie-decl", "tiex-def", "lost-decl,tie"}) {
            try {
                FlatHandoverProvider.Scope.parse(bad);
                fail(bad + " should not parse");
            } catch (IllegalArgumentException expected) {
                // as it should
            }
        }
    }

    @Test public void theTieRuleTakesTheCardsNearTheTop() {
        Card a = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        Card b = new Card(Card.Suit.CLUBS, Card.Rank.TEN);
        Card c = new Card(Card.Suit.HEARTS, Card.Rank.SEVEN);
        Set<Card> legal = new java.util.LinkedHashSet<>(List.of(a, b, c));
        Map<Card, Integer> votes = Map.of(a, 50, b, 50, c, 47);
        assertEquals(List.of(a, b), FlatHandoverProvider.tied(votes, legal, 0));
        assertEquals(List.of(a, b), FlatHandoverProvider.tied(votes, legal, 2));
        assertEquals(List.of(a, b, c), FlatHandoverProvider.tied(votes, legal, 3));
        assertEquals("a single best card is no tie", List.of(a), FlatHandoverProvider.tied(Map.of(a, 9, b, 3, c, 0), legal, 0));
    }

    @Test public void theScopeNamesTheCandidates() {
        Card a = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        Card b = new Card(Card.Suit.CLUBS, Card.Rank.TEN);
        Card c = new Card(Card.Suit.HEARTS, Card.Rank.SEVEN);
        Set<Card> legal = new java.util.LinkedHashSet<>(List.of(a, b, c));
        Map<Card, Integer> lost = Map.of(a, 0, b, 0, c, 0);
        Map<Card, Integer> tie = Map.of(a, 5, b, 5, c, 1);
        Map<Card, Integer> clear = Map.of(a, 9, b, 3, c, 0);
        FlatHandoverProvider.Scope lostOnly = FlatHandoverProvider.Scope.LOST_DECLARER;
        FlatHandoverProvider.Scope ties = FlatHandoverProvider.Scope.parse("tie0-decl,tie0-def");
        FlatHandoverProvider.Scope all = FlatHandoverProvider.Scope.parse("lost-decl,lost-def,tie0-decl,tie0-def");
        assertEquals(List.of(a, b, c), FlatHandoverProvider.candidates(lost, legal, true, lostOnly));
        assertEquals("the declarer's scope leaves defenders alone", List.of(), FlatHandoverProvider.candidates(lost, legal, false, lostOnly));
        assertEquals("a tie is not handed without a tie scope", List.of(), FlatHandoverProvider.candidates(tie, legal, true, lostOnly));
        assertEquals(List.of(a, b), FlatHandoverProvider.candidates(tie, legal, true, ties));
        assertEquals(List.of(a, b), FlatHandoverProvider.candidates(tie, legal, false, ties));
        assertEquals("a lost position is not a tie", List.of(), FlatHandoverProvider.candidates(lost, legal, false, ties));
        assertEquals(List.of(a, b, c), FlatHandoverProvider.candidates(lost, legal, false, all));
        assertEquals("a clear best card stays the search's", List.of(), FlatHandoverProvider.candidates(clear, legal, true, all));
        assertEquals("no votes, no hand-over", List.of(), FlatHandoverProvider.candidates(null, legal, true, all));
    }

    @Test public void theTieAfterTheCushionIsWhatTheCushionLeaves() {
        Card a = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        Card b = new Card(Card.Suit.CLUBS, Card.Rank.TEN);
        Card c = new Card(Card.Suit.HEARTS, Card.Rank.SEVEN);
        Card d = new Card(Card.Suit.HEARTS, Card.Rank.EIGHT);
        Set<Card> legal = new java.util.LinkedHashSet<>(List.of(a, b, c, d));
        Map<Card, Integer> tie = Map.of(a, 5, b, 5, c, 5, d, 1);
        FlatHandoverProvider.Scope after = FlatHandoverProvider.Scope.parse("tie0c-decl,tie0c-def");
        FlatHandoverProvider.Scope before = FlatHandoverProvider.Scope.parse("tie0-decl,tie0-def");
        assertEquals(new FlatHandoverProvider.Scope(false, false, 0, 0, true, true, false, false), after);
        assertEquals(new FlatHandoverProvider.Scope(false, false, 0, -1), FlatHandoverProvider.Scope.parse("tie0-decl"));
        assertEquals(new FlatHandoverProvider.Scope(false, false, -1, 2, false, true, false, true),
                FlatHandoverProvider.Scope.parse("tie2cw-def"));
        Map<Card, Integer> settled = Map.of(a, 3, b, 4, c, 2, d, 9);
        Map<Card, Integer> open = Map.of(a, 4, b, 2, c, 4, d, 9);
        assertEquals("the cushion settled it", List.of(), FlatHandoverProvider.candidates(tie, settled, 9, legal, false, after));
        assertEquals("what the cushion leaves tied", List.of(a, c), FlatHandoverProvider.candidates(tie, open, 9, legal, true, after));
        assertEquals("a vote-only tie ignores the cushion", List.of(a, b, c), FlatHandoverProvider.candidates(tie, settled, 9, legal, false, before));
        assertEquals("no cushion asked, no narrowing", List.of(a, b, c), FlatHandoverProvider.candidates(tie, Map.of(), 9, legal, true, after));
        assertEquals("only the role named is narrowed", List.of(a, b, c),
                FlatHandoverProvider.candidates(tie, settled, 9, legal, false, FlatHandoverProvider.Scope.parse("tie0c-decl,tie0-def")));
        // Held ties only: the best vote at every world searched.
        Map<Card, Integer> held = Map.of(a, 9, b, 9, c, 9, d, 1);
        FlatHandoverProvider.Scope heldOnly = FlatHandoverProvider.Scope.parse("tie0w-decl,tie0w-def");
        assertEquals(List.of(a, b, c), FlatHandoverProvider.candidates(held, Map.of(), 9, legal, false, heldOnly));
        assertEquals("a tie short of the full vote is not handed", List.of(),
                FlatHandoverProvider.candidates(tie, Map.of(), 9, legal, true, heldOnly));
        assertEquals("nothing searched, nothing held", List.of(),
                FlatHandoverProvider.candidates(held, Map.of(), 0, legal, true, heldOnly));
        Map<Card, Integer> nearly = Map.of(a, 8, b, 8, c, 8, d, 1);
        assertEquals("one world short is short", List.of(),
                FlatHandoverProvider.candidates(nearly, Map.of(), 9, legal, false, heldOnly));
        assertEquals("the declarer's w alone", List.of(),
                FlatHandoverProvider.candidates(nearly, Map.of(), 9, legal, true, FlatHandoverProvider.Scope.parse("tie0w-decl,tie0-def")));
        assertEquals("the declarer's w leaves the defender's ties", List.of(a, b, c),
                FlatHandoverProvider.candidates(nearly, Map.of(), 9, legal, false, FlatHandoverProvider.Scope.parse("tie0w-decl,tie0-def")));
        assertEquals("the defender's w leaves the declarer's ties", List.of(a, b, c),
                FlatHandoverProvider.candidates(nearly, Map.of(), 9, legal, true, FlatHandoverProvider.Scope.parse("tie0-decl,tie0w-def")));
        assertEquals("a tie short of the full vote is handed without w", List.of(a, b, c),
                FlatHandoverProvider.candidates(tie, Map.of(), 9, legal, true, before));
        try {
            FlatHandoverProvider.Scope.parse("tiewc-def");
            fail("tiewc-def should not parse");
        } catch (IllegalArgumentException expected) {
            // as it should
        }
        try {
            FlatHandoverProvider.Scope.parse("tiec-def");
            fail("tiec-def should not parse");
        } catch (IllegalArgumentException expected) {
            // as it should
        }
    }

    @Test public void theScopedPlayerPlaysWholeGamesAsBothRoles() throws IOException {
        PolicyNet net = randomNet(6);
        long seed = 37;
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        long before = FlatHandoverProvider.handed();
        int games = 0;
        FlatHandoverProvider.Scope scope = FlatHandoverProvider.Scope.parse("lost-decl,lost-def,tie0-decl,tie0-def");
        for (int i = 0; games < 3 && i < 30; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, new FlatHandoverProvider("po:test", new SearchAiProvider(new GreedyAiProvider(), 4,
                        seed + i * 3 + seat.ordinal()), new RolloutRescue(net, 4, 2, 13), scope));
            }
            GameOutcome outcome = GameRunner.playFixed(board, seating, fixed, i);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) assertEquals(0, outcome.ruleViolationsOf(seat));
            games++;
        }
        assertTrue("games: " + games, games == 3);
        assertTrue("decisions handed over", FlatHandoverProvider.handed() > before);
    }
}
