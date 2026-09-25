package dev.skatklar.training.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A rollout from a recorded position is a continuation of the recorded game.
 *
 * <p>With the original seeds and the card that was played forced, the scripted
 * players must choose every scripted card themselves and the game must end
 * exactly as recorded -- the property {@link RolloutAuditMain}'s replay check
 * relies on for every number it prints. On a cheap player, several decisions.
 */
public class RolloutReplayTest {

    @Test public void forcingThePlayedCardWithTheOriginalSeedsReplaysTheGame() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve("search-4");
        long seed = 14;
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        int checked = 0;
        for (int i = 0; i < 12 && checked < 6; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null) continue;
            RolloutAuditMain.Replay game = RolloutAuditMain.record(board, seed, fixed, player);
            if (game.decisions().isEmpty()) continue;
            RolloutAuditMain.Recorded d = game.decisions().get(game.decisions().size() / 2);
            RolloutAuditMain.Outcome replayed =
                    RolloutAuditMain.rollout(game, d.index(), d.played(), player, seed, -1, true);
            assertNotNull("board " + i + " replayed to an end", replayed);
            assertEquals("board " + i + ": every scripted card is the one the player would choose",
                    0, replayed.scriptMismatches());
            assertEquals("board " + i + ": every card of the game, the continuation included",
                    game.sequence(), replayed.sequence());
            assertEquals("board " + i + " won alike", game.won(), replayed.won());
            assertEquals("board " + i + " scored alike", game.tournamentPoints(), replayed.tournamentPoints());
            checked++;
        }
        assertTrue("decisions checked: " + checked, checked >= 4);
    }

    @Test public void aRolloutWithItsOwnSeedsIsAGameOfItsOwn() {
        // Not a property the audit needs, a guard that the seeds reach the players:
        // over a few rollouts of one decision, the players see different worlds.
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve("search-4");
        long seed = 14;
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        for (int i = 0; i < 12; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null) continue;
            RolloutAuditMain.Replay game = RolloutAuditMain.record(board, seed, fixed, player);
            if (game.decisions().isEmpty()) continue;
            RolloutAuditMain.Recorded d = game.decisions().get(0);
            java.util.Set<Integer> points = new java.util.HashSet<>();
            for (int r = 0; r < 6; r++) {
                RolloutAuditMain.Outcome o = RolloutAuditMain.rollout(game, d.index(), d.played(), player, seed, r, false);
                assertNotNull(o);
                points.add(o.tournamentPoints());
            }
            if (points.size() > 1) return;
        }
        assertFalse("no decision in twelve boards gave two different rollout results", true);
    }
}
