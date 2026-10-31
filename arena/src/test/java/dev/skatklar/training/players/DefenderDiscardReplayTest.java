package dev.skatklar.training.players;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import java.io.IOException;
import org.junit.Test;

/**
 * The discard fix moves defenders' hand-overs and nothing else.
 *
 * <p>{@link DefenderDiscardReplayMain} on a cheap stand-in for best (a 4-world
 * search, play-outs by a net of random weights over every scope best uses plus
 * the defenders' ties, so the defenders hand over often) against a plain 4-world
 * search: every rotation of a few boards in the old form and in the new. No
 * rule of the replay may break; declarers hand over and none moves; defenders
 * hand over and some move -- the fix is not a no-op.
 */
public class DefenderDiscardReplayTest {

    static Contestant playOuts(PolicyNet net) {
        FlatHandoverProvider.Scope scope = FlatHandoverProvider.Scope.parse("lost-decl,lost-def,tie0-decl,tie0-def");
        return new Contestant() {
            @Override public String id() { return "po:search-4/random/all"; }
            @Override public SkatAiProvider newProvider(long seed) {
                return new FlatHandoverProvider("po:test", new SearchAiProvider(new GreedyAiProvider(), 4, seed),
                        new RolloutRescue(net, 4, 2, seed ^ 0x5011L), scope);
            }
        };
    }

    static Contestant search() {
        return new Contestant() {
            @Override public String id() { return "search-4"; }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), 4, seed);
            }
        };
    }

    @Test public void onlyDefendersHandOversMove() throws Exception {
        long seed = 71;
        DefenderDiscardReplayMain.Report report = DefenderDiscardReplayMain.run(
                playOuts(RolloutRescueTest.randomNet(21)), search(),
                new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed), seed, 3, 2);
        report.print();
        assertEquals("violations: " + report.violations, 0, report.violations.size());
        assertTrue("games: " + report.games, report.games >= 12);
        assertTrue("declarers handed over: " + report.declarerHandovers, report.declarerHandovers > 10);
        assertEquals("no declarer's hand-over moved", 0, report.declarerMoved);
        assertTrue("defenders handed over: " + report.defenderHandovers, report.defenderHandovers > 10);
        assertTrue("the fix moved some of them: " + report.defenderMoved, report.defenderMoved > 0);
        assertTrue("and some games parted: " + report.diverged, report.diverged > 0);
    }

    @Test public void theInstrumentSeesAGameThatPartsElsewhere() throws IOException {
        // The comparison itself, on made-up games: two games that differ where
        // no hand-over moved are a violation, and so is a shadow that is not
        // what the old game played.
        dev.skatklar.demo.Card a = new dev.skatklar.demo.Card(dev.skatklar.demo.Card.Suit.CLUBS,
                dev.skatklar.demo.Card.Rank.ACE);
        dev.skatklar.demo.Card b = new dev.skatklar.demo.Card(dev.skatklar.demo.Card.Suit.CLUBS,
                dev.skatklar.demo.Card.Rank.TEN);
        dev.skatklar.demo.Card c = new dev.skatklar.demo.Card(dev.skatklar.demo.Card.Suit.HEARTS,
                dev.skatklar.demo.Card.Rank.SEVEN);
        java.util.List<RolloutRescue.Decided> none = java.util.List.of(new RolloutRescue.Decided(
                dev.skatklar.demo.ai.SkatAi.Seat.OPPONENT_ONE, false, 1, b, b));
        DefenderDiscardReplayMain.Report r1 = new DefenderDiscardReplayMain.Report();
        DefenderDiscardReplayMain.compare("unmoved", new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, b, c), null, java.util.List.of()), new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, c, b), null, none), r1);
        assertEquals(1, r1.violations.size());

        java.util.List<RolloutRescue.Decided> moved = java.util.List.of(new RolloutRescue.Decided(
                dev.skatklar.demo.ai.SkatAi.Seat.OPPONENT_ONE, false, 1, c, b));
        DefenderDiscardReplayMain.Report r2 = new DefenderDiscardReplayMain.Report();
        DefenderDiscardReplayMain.compare("faithful", new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, b, c), null, java.util.List.of()), new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, c, b), null, moved), r2);
        assertEquals(r2.violations.toString(), 0, r2.violations.size());
        assertEquals(1, r2.diverged);

        java.util.List<RolloutRescue.Decided> lying = java.util.List.of(new RolloutRescue.Decided(
                dev.skatklar.demo.ai.SkatAi.Seat.OPPONENT_ONE, false, 1, c, a));
        DefenderDiscardReplayMain.Report r3 = new DefenderDiscardReplayMain.Report();
        DefenderDiscardReplayMain.compare("unfaithful shadow", new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, b, c), null, java.util.List.of()), new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, c, b), null, lying), r3);
        assertEquals(1, r3.violations.size());

        java.util.List<RolloutRescue.Decided> declarer = java.util.List.of(new RolloutRescue.Decided(
                dev.skatklar.demo.ai.SkatAi.Seat.HUMAN, true, 1, c, b));
        DefenderDiscardReplayMain.Report r4 = new DefenderDiscardReplayMain.Report();
        DefenderDiscardReplayMain.compare("declarer moved", new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, b, c), null, java.util.List.of()), new DefenderDiscardReplayMain.Played(
                java.util.List.of(a, c, b), null, declarer), r4);
        assertTrue(r4.violations.toString(), r4.violations.size() >= 1);
    }
}
