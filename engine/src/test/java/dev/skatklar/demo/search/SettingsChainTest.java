package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.GreedyAiProvider;
import org.junit.Test;

/**
 * A {@code with} builder keeps every setting it was not asked to change.
 *
 * <p>Each builder constructs a new player from all the fields, and one that
 * forgets a field silently reverts it: {@code withWorldThreads} reset the
 * card-play settings, so the app's ladder -- applied before it in
 * {@code Opponents.seat} -- never reached the table. The order below is the
 * app's own.
 */
public class SettingsChainTest {

    private static SearchAiProvider base() {
        return new SearchAiProvider(new GreedyAiProvider(), Personality.clubPlayer(), 1L,
                WorldSource.UNIFORM);
    }

    @Test public void worldThreadsKeepTheLadderAndTheBudget() {
        SearchAiProvider player = base().withAdaptiveBidding().withMarginTiebreak(15)
                .withRuleTiebreak().withLadder().withNullWorlds(24)
                .withNullTiebreak(RuleTiebreak.NullOrder.LOW_RANK)
                .withBiddingBudget(1_000L).withWorldThreads(2);
        assertTrue("the ladder survives withWorldThreads", player.cardPlaySettings().ladder);
        player = player.withCardBudget(5_000L);
        assertTrue("and withCardBudget", player.cardPlaySettings().ladder);
        assertEquals(5_000L, player.cardPlaySettings().budgetNanos);
        player = player.withCardPlayObserver(report -> {});
        assertTrue("and withCardPlayObserver", player.cardPlaySettings().ladder);
        assertEquals(5_000L, player.cardPlaySettings().budgetNanos);
    }

    @Test public void theBudgetSurvivesTheOtherBuilders() {
        SearchAiProvider player = base().withCardBudget(7_000L).withLadder()
                .withNullWorlds(24).withNullTiebreak(RuleTiebreak.NullOrder.LOW_RANK)
                .withRuleTiebreak().withMarginTiebreak(15).withAdaptiveBidding()
                .withBiddingBudget(1L).withWorldThreads(3).withDiscardSearch(8, 5, 0L)
                .withTemperature(0.1);
        assertEquals(7_000L, player.cardPlaySettings().budgetNanos);
        assertTrue(player.cardPlaySettings().ladder);
    }

    @Test public void theTrapSurvivesEveryBuilderAfterIt() {
        SearchAiProvider player = base().withAdaptiveBidding().withMarginTiebreak(15)
                .withRuleTiebreak().withLadder().withTrap(SearchAiProvider.TrapOrder.SAFETY_FIRST)
                .withNullWorlds(24).withNullTiebreak(RuleTiebreak.NullOrder.LOW_RANK)
                .withBiddingBudget(1_000L).withWorldThreads(2).withCardBudget(5L)
                .withCardPlayObserver(report -> {}).withDiscardSearch(8, 5, 0L).withTemperature(0.1);
        assertEquals(SearchAiProvider.TrapOrder.SAFETY_FIRST, player.cardPlaySettings().trap);
        assertTrue(player.cardPlaySettings().ladder);
        // And the ladder, applied after the trap, keeps it too.
        player = base().withTrap(SearchAiProvider.TrapOrder.TRAP_FIRST).withLadder();
        assertEquals(SearchAiProvider.TrapOrder.TRAP_FIRST, player.cardPlaySettings().trap);
        assertEquals("off unless asked for", SearchAiProvider.TrapOrder.OFF,
                base().withLadder().cardPlaySettings().trap);
    }

    @Test public void theBiddingBudgetBuilderKeepsTheThreads() {
        // withBiddingBudget used to reset the world threads too; the order is
        // the app's, where the budget comes first.
        SearchAiProvider player = base().withLadder().withBiddingBudget(9L).withWorldThreads(4)
                .withCardBudget(1L);
        assertTrue(player.cardPlaySettings().ladder);
        player = base().withWorldThreads(4).withLadder().withBiddingBudget(9L);
        assertTrue(player.cardPlaySettings().ladder);
    }
}
