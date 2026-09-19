package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;

/**
 * A ceiling on one card decision, and the stopwatch that reports it.
 *
 * <p>Bidding has had a ceiling since it reached a phone; card play never did,
 * which was defensible only while a decision was cheap. A Null now samples four
 * times the worlds of any other contract, so the one decision with no ceiling
 * became the one that needed it.
 *
 * <p>What a budget may cost is votes. What it may never cost is a legal card,
 * or the arena's ability to replay a match from its seed — which is why the
 * unbounded player must be bit-for-bit what it was before any of this existed.
 */
public class CardBudgetTest {

    private static Map<SkatAi.Seat, SkatAiProvider> seating(long budgetNanos,
                                                            SearchAiProvider.CardPlayObserver watcher,
                                                            int threads) {
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(),
                    Personality.clubPlayer(), 5L * 31L + seat.ordinal(), WorldSource.UNIFORM)
                    .withMarginTiebreak(15).withRuleTiebreak().withWorldThreads(threads);
            if (budgetNanos > 0) player = player.withCardBudget(budgetNanos);
            if (watcher != null) player = player.withCardPlayObserver(watcher);
            seating.put(seat, player);
        }
        return seating;
    }

    private static List<Card> playOut(long budgetNanos, SearchAiProvider.CardPlayObserver watcher,
                                      int threads, Contract contract) {
        GameEngine engine = GameEngine.headless(new Random(7),
                SeatedAiProviders.of(seating(budgetNanos, watcher, threads)));
        engine.restartWithContract(SkatDeck.deal(new Random(7)),
                SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN, contract, 0, Set.of());
        List<Card> played = new ArrayList<>();
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) break;
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else played.add(engine.playAiCard());
        }
        assertNotNull("the deal must finish", engine.snapshot().result);
        assertEquals("no seat may be given a card by the engine",
                0, engine.ruleViolations().size());
        return played;
    }

    /**
     * The unbounded player is untouched, which is the claim the arena rests on.
     *
     * <p>Asked of a player that has an observer attached as well, because
     * watching must not change what is watched: the report is taken after the
     * decision and from numbers the decision already had.
     */
    @Test public void noBudgetAndNoObserverPlayTheSameGameAsAnObserver() {
        List<Card> plain = playOut(0L, null, 1, Contract.CLUBS);
        List<Card> watched = playOut(0L, report -> { }, 1, Contract.CLUBS);
        assertEquals("a deal is thirty cards", 30, plain.size());
        assertEquals(plain, watched);
    }

    /** A budget far above any decision here is the same as no budget at all. */
    @Test public void aBudgetNobodyReachesChangesNothing() {
        assertEquals(playOut(0L, null, 1, Contract.CLUBS),
                playOut(600_000_000_000L, null, 1, Contract.CLUBS));
    }

    /**
     * A budget of one nanosecond still plays a whole legal game.
     *
     * <p>The cards are not asserted, because under a spent budget they are not
     * a function of the seed any more — that is what a budget is. What is
     * asserted is everything else: the deal finishes, the engine never has to
     * substitute a card, and every seat searched at least one world. A player
     * that answered from no worlds at all would still be legal and would be
     * guessing, which is worse than hasty.
     */
    @Test public void aBudgetAlreadyGoneIsHastyRatherThanMute() {
        List<SearchAiProvider.CardPlayReport> reports = new CopyOnWriteArrayList<>();
        List<Card> played = playOut(1L, reports::add, 1, Contract.CLUBS);
        assertEquals(30, played.size());
        assertFalse("some decision must have been searched", reports.isEmpty());
        for (SearchAiProvider.CardPlayReport report : reports) {
            assertTrue("at least one world, always", report.worldsSearched() >= 1);
            assertTrue(report.worldsSearched() <= report.worldsAsked());
        }
    }

    /** The same, with the worlds spread over threads: every chunk owes a world. */
    @Test public void everyChunkSearchesAtLeastOneWorldUnderASpentBudget() {
        List<SearchAiProvider.CardPlayReport> reports = new CopyOnWriteArrayList<>();
        playOut(1L, reports::add, 4, Contract.CLUBS);
        assertFalse(reports.isEmpty());
        for (SearchAiProvider.CardPlayReport report : reports) {
            assertTrue("one per chunk, and there are four chunks where there are four worlds",
                    report.worldsSearched() >= 1);
        }
    }

    /** What the observer is for: the numbers are the ones a log line needs. */
    @Test public void theReportSaysWhatTheDecisionCost() {
        List<SearchAiProvider.CardPlayReport> reports = new CopyOnWriteArrayList<>();
        playOut(0L, reports::add, 1, Contract.NULL);
        assertFalse(reports.isEmpty());
        for (SearchAiProvider.CardPlayReport report : reports) {
            assertEquals(Contract.NULL, report.contract());
            assertTrue("a decision is taken with cards in hand", report.cardsInHand() >= 1);
            assertTrue(report.worldsAsked() >= 1);
            assertEquals("unbounded means every world", report.worldsAsked(), report.worldsSearched());
            assertFalse("and so the budget did not bite", report.budgetBit());
            assertTrue("a decision takes some time", report.nanos() > 0);
        }
    }

    /**
     * An observer that throws is a logger with a bug in it.
     *
     * <p>And a logger with a bug in it does not get to lose the game. This is
     * the one thing the instrumentation could plausibly break, since it is the
     * only new code that runs inside a decision.
     */
    @Test public void anObserverThatThrowsDoesNotReachTheTable() {
        List<Card> broken = playOut(0L, report -> { throw new IllegalStateException("bad log"); },
                1, Contract.CLUBS);
        assertEquals(playOut(0L, null, 1, Contract.CLUBS), broken);
    }
}
