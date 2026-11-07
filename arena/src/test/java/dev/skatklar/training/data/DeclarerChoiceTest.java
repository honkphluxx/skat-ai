package dev.skatklar.training.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.Discards;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.players.RolloutRescueTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The declarer-choice labels: the options are the contracts that cover the bid
 * with the ten discards from each one's five least-wanted cards, the
 * references among them; a play-out buries the option's two cards and plays
 * the option's contract, the declarer knowing its discard and the defenders
 * drawn; a board is the same bytes run twice.
 */
public class DeclarerChoiceTest {

    static final long SEED = 9101;
    static final PlayerRegistry REGISTRY = PlayerRegistry.withDefaults();
    static final Contestant GREEDY = REGISTRY.resolve("greedy");
    static final Contestant SEARCH = REGISTRY.resolve("search-4");

    /** Declared boards of greedy's auction, with their fixed contract. */
    static List<Object[]> declared(int wanted) {
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED);
        List<Object[]> out = new ArrayList<>();
        for (long i = 0; out.size() < wanted && i < 200; i++) {
            Board board = Board.of(SEED, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            out.add(new Object[] {board, fixed});
        }
        return out;
    }

    static List<Card> twelve(Board board, ContractSource.FixedContract fixed) {
        List<Card> twelve = new ArrayList<>(DeclarerChoiceMain.handOf(board, fixed.declarer()));
        twelve.addAll(board.deal().skat);
        return twelve;
    }

    @Test public void theOptionsCoverTheBidAndHoldTheHeuristicAndTheReferences() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(61);
        int checked = 0;
        int contractsSeen = 0;
        for (Object[] b : declared(25)) {
            Board board = (Board) b[0];
            ContractSource.FixedContract fixed = (ContractSource.FixedContract) b[1];
            List<Card> twelve = twelve(board, fixed);
            DeclarerChoiceMain.Choice shipped = DeclarerChoiceMain.shippedChoice(SEARCH.newProvider(5), board, fixed,
                    DeclarerChoiceMain.handOf(board, fixed.declarer()), board.deal().skat);
            DeclarerChoiceMain.Choice asFixed = new DeclarerChoiceMain.Choice(fixed.contract(),
                    Discards.buried(fixed.contract(), twelve));
            List<DeclarerChoiceMain.Option> options = DeclarerChoiceMain.options(twelve, fixed.bidValue(), net,
                    List.of(shipped, asFixed));
            assertTrue("the shipped choice is an option", DeclarerChoiceMain.indexOf(options, shipped) >= 0);
            assertTrue("the fixed reference is an option", DeclarerChoiceMain.indexOf(options, asFixed) >= 0);
            Set<String> spelled = new HashSet<>();
            for (DeclarerChoiceMain.Option o : options) {
                assertTrue("a discard of two cards of the twelve", twelve.containsAll(o.discard())
                        && !o.discard().get(0).equals(o.discard().get(1)));
                assertTrue("no option twice", spelled.add(o.contract() + " " + o.discard()));
                assertFalse("no Null", o.contract().isNull());
            }
            for (Contract c : DeclarerChoiceMain.TRUMP_GAMES) {
                List<DeclarerChoiceMain.Option> of = options.stream().filter(o -> o.contract() == c).toList();
                boolean covers = SkatRules.guaranteedValue(c, twelve) >= fixed.bidValue();
                boolean reference = c == shipped.contract() || c == fixed.contract();
                if (!covers && !reference) {
                    assertTrue(c + " does not cover the bid " + fixed.bidValue() + ", so it is no option", of.isEmpty());
                    continue;
                }
                if (!covers) continue;
                contractsSeen++;
                List<Card> pool = Discards.ranked(c, twelve).subList(7, 12);
                long fromPool = of.stream().filter(o -> pool.containsAll(o.discard())).count();
                assertEquals(c + ": the ten pairs of its five least-wanted cards", 10, fromPool);
                List<Card> heuristic = DeclarerChoiceMain.pair(Discards.buried(c, twelve));
                long marked = of.stream().filter(DeclarerChoiceMain.Option::heuristic).count();
                assertEquals(c + ": one heuristic pair", 1, marked);
                assertTrue(c + ": the heuristic pair is marked",
                        of.stream().anyMatch(o -> o.heuristic() && o.discard().equals(heuristic)));
            }
            checked++;
        }
        assertTrue("boards checked: " + checked, checked >= 20);
        assertTrue("contracts with options: " + contractsSeen, contractsSeen > checked);
    }

    @Test public void aPlayOutBuriesTheOptionsCardsAndPlaysItsContract() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(62);
        int checked = 0;
        for (Object[] b : declared(8)) {
            Board board = (Board) b[0];
            ContractSource.FixedContract fixed = (ContractSource.FixedContract) b[1];
            List<Card> twelve = twelve(board, fixed);
            List<DeclarerChoiceMain.Option> options = DeclarerChoiceMain.options(twelve, fixed.bidValue(), net,
                    List.of());
            DeclarerChoiceMain.Option option = options.get(options.size() - 1);
            List<Card> kept = new ArrayList<>(twelve);
            kept.removeAll(option.discard());
            List<String> problems = new ArrayList<>();
            int[] decisions = new int[2];
            DeclarerChoiceMain.WATCH.set((context, declares) -> {
                if (context.game.contract != option.contract()) problems.add("contract " + context.game.contract);
                if (declares != (context.mySeat == fixed.declarer())) problems.add("the declarer's flag");
                if (declares) {
                    if (!kept.containsAll(context.hand)) problems.add("the declarer holds a buried card " + context.hand);
                    if (context.history.completedTricks.isEmpty() && context.currentTrick.plays.isEmpty()
                            && !new HashSet<>(kept).equals(context.hand)) {
                        problems.add("the declarer's ten " + context.hand + " are not the twelve less the discard");
                    }
                    decisions[0]++;
                } else {
                    decisions[1]++;
                }
            });
            try {
                DeclarerChoiceMain.playOut(net, board, fixed, option, 2, SEED);
            } finally {
                DeclarerChoiceMain.WATCH.remove();
            }
            assertTrue(String.join("; ", problems), problems.isEmpty());
            assertTrue("both sides decided", decisions[0] > 0 && decisions[1] > 0);
            checked++;
        }
        assertTrue("boards checked: " + checked, checked >= 8);
    }

    @Test public void aDiscardNotInTheHandIsRefused() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(63);
        Object[] b = declared(1).get(0);
        Board board = (Board) b[0];
        ContractSource.FixedContract fixed = (ContractSource.FixedContract) b[1];
        List<Card> twelve = twelve(board, fixed);
        List<Card> others = new ArrayList<>(dev.skatklar.demo.SkatDeck.ordered());
        others.removeAll(twelve);
        DeclarerChoiceMain.Option foreign = new DeclarerChoiceMain.Option(fixed.contract(), others.subList(0, 2), false);
        assertThrows(IllegalStateException.class, () -> DeclarerChoiceMain.playOut(net, board, fixed, foreign, 1, SEED));
    }

    @Test public void theDefendersAreDrawnAndTheDeclarerIsNot() throws Exception {
        // On one option the declarer's cards repeat draw by draw only when the
        // defenders' do; with the defenders drawn, the draws differ somewhere.
        PolicyNet net = RolloutRescueTest.randomNet(64);
        int varied = 0;
        int checked = 0;
        for (Object[] b : declared(10)) {
            Board board = (Board) b[0];
            ContractSource.FixedContract fixed = (ContractSource.FixedContract) b[1];
            List<DeclarerChoiceMain.Option> options = DeclarerChoiceMain.options(twelve(board, fixed),
                    fixed.bidValue(), net, List.of());
            DeclarerChoiceMain.Scored s = DeclarerChoiceMain.playOut(net, board, fixed, options.get(0), 6, SEED);
            if (Arrays.stream(s.points()).distinct().count() > 1) varied++;
            checked++;
        }
        assertTrue("draws that differ: " + varied + " of " + checked, varied >= checked / 2);
    }

    @Test public void aBoardIsTheSameRunTwiceAndAnOptionTheSameAloneAndAmongOthers() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(65);
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED);
        int checked = 0;
        for (long i = 0; checked < 3 && i < 40; i++) {
            Board board = Board.of(SEED, i);
            DeclarerChoiceMain.Result one = DeclarerChoiceMain.board(net, SEARCH, contracts, board, 2, SEED,
                    new DeclarerChoiceMain.Stats());
            if (one == null) continue;
            DeclarerChoiceMain.Result two = DeclarerChoiceMain.board(net, SEARCH, contracts, board, 2, SEED,
                    new DeclarerChoiceMain.Stats());
            assertNotNull(two);
            assertEquals("board " + i + " run twice", DeclarerChoiceMain.json(one), DeclarerChoiceMain.json(two));
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            DeclarerChoiceMain.Scored last = one.scored().get(one.scored().size() - 1);
            DeclarerChoiceMain.Scored alone = DeclarerChoiceMain.playOut(net, board, fixed, last.option(), 2, SEED);
            assertTrue("an option alone", Arrays.equals(alone.value(), last.value())
                    && Arrays.equals(alone.points(), last.points()) && Arrays.equals(alone.wins(), last.wins()));
            String line = DeclarerChoiceMain.json(one);
            assertEquals("one JSON object per option", one.scored().size(), line.split("\"heuristic\"", -1).length - 1);
            assertTrue("the shipped choice is written", line.contains("\"shipped\": {\"contract\": \""
                    + one.shipped().contract().name()));
            for (DeclarerChoiceMain.Scored s : one.scored()) {
                for (int d = 0; d < 2; d++) {
                    assertTrue("a win is a positive value", (s.wins()[d] == 1) == (s.value()[d] > 0));
                }
            }
            checked++;
        }
        assertTrue("boards checked: " + checked, checked == 3);
    }
}
