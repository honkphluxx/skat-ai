package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.Opponents;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.search.WorldSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The three-call turn plays the same game as the one-call turn.
 *
 * <p>{@link GameEngine#playAiCard()} decides and plays under one lock.
 * {@link GameEngine#beginAiTurn()}, {@link GameEngine#decideAiCard} and
 * {@link GameEngine#commitAiCard} do the same work in three pieces so that the
 * middle one — the search, which is the whole of the cost — can run on a worker
 * while the app keeps drawing. The app uses the second form and everything else
 * uses the first, so the two have to be the same game or the player people meet
 * on a phone is not the player the arena measured.
 *
 * <p>Same cards, in the same order, is a stronger claim than same result and is
 * the one asserted: a split that consumed the session's randomness differently
 * would still reach a plausible result while being a different player.
 */
public class AiTurnSplitTest {

    /** Every seat automated, so the loop below is the whole deal. */
    private static GameEngine deal() {
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            seating.put(seat, Opponents.seat(Opponents.Level.CLUB, WorldSource.UNIFORM, 5L));
        }
        GameEngine engine = GameEngine.headless(new Random(7), SeatedAiProviders.of(seating));
        engine.restartWithContract(SkatDeck.deal(new Random(7)),
                SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN, Contract.CLUBS, 0, Set.of());
        return engine;
    }

    private static List<Card> playInOneCall(GameEngine engine) {
        List<Card> played = new ArrayList<>();
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) break;
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else played.add(engine.playAiCard());
        }
        return played;
    }

    private static List<Card> playInThreeCalls(GameEngine engine) {
        List<Card> played = new ArrayList<>();
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) break;
            if (snapshot.trickComplete()) {
                engine.finishCompletedTrick();
                continue;
            }
            GameEngine.AiTurn turn = engine.beginAiTurn();
            assertNotNull("a seat that owes a card must offer a turn", turn);
            // Where the worker would be. Nothing between these two calls holds
            // the engine's lock, which is the entire point of the split.
            engine.decideAiCard(turn);
            played.add(engine.commitAiCard(turn));
        }
        return played;
    }

    @Test public void bothFormsPlayTheSameCards() {
        List<Card> oneCall = playInOneCall(deal());
        List<Card> threeCalls = playInThreeCalls(deal());
        assertEquals("a deal is thirty cards", 30, oneCall.size());
        assertEquals(oneCall, threeCalls);
    }

    @Test public void bothFormsReachTheSameResult() {
        GameEngine one = deal();
        playInOneCall(one);
        GameEngine three = deal();
        playInThreeCalls(three);
        GameEngine.Snapshot first = one.snapshot();
        GameEngine.Snapshot second = three.snapshot();
        assertNotNull(first.result);
        assertEquals(first.result.declarerPoints, second.result.declarerPoints);
        assertEquals(first.result.declarerWon, second.result.declarerWon);
        assertEquals(first.result.gameValue, second.result.gameValue);
        assertEquals("neither form may substitute a card for a seat",
                0, one.ruleViolations().size());
        assertEquals(0, three.ruleViolations().size());
    }

    /**
     * A card decided for a deal that has since been replaced is dropped.
     *
     * <p>The failure this prevents is specific and would be very hard to read
     * from a bug report: the player starts a new game while a seat is still
     * thinking about the old one, the answer arrives, and a card from the
     * previous deal is played into the new one — legal, in all likelihood, and
     * nonsense.
     */
    @Test public void aTurnDecidedForAReplacedDealIsRefused() {
        GameEngine engine = deal();
        GameEngine.AiTurn turn = engine.beginAiTurn();
        assertNotNull(turn);
        engine.decideAiCard(turn);
        // The worker is still holding the answer when the table is cleared.
        engine.restartWithContract(SkatDeck.deal(new Random(99)),
                SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN, Contract.HEARTS, 0, Set.of());
        assertNull("a card decided for the deal before this one must be dropped",
                engine.commitAiCard(turn));
        // And the fresh deal is untouched: nobody has played to it yet.
        assertTrue(engine.snapshot().trick.isEmpty());
    }

    /**
     * A card decided for a trick that has since been collected is dropped too.
     *
     * <p>The same staleness one notch finer, and the one that can happen without
     * anybody touching the screen: the guard is on the trick and the cards in
     * it, not only on the deal.
     */
    @Test public void aTurnDecidedForACollectedTrickIsRefused() {
        GameEngine engine = deal();
        GameEngine.AiTurn turn = engine.beginAiTurn();
        assertNotNull(turn);
        engine.decideAiCard(turn);
        // Three cards land while the answer is in flight, and the trick goes.
        assertNotNull(engine.commitAiCard(engine.beginAiTurn()));
        assertNotNull(engine.commitAiCard(engine.beginAiTurn()));
        assertNotNull(engine.commitAiCard(engine.beginAiTurn()));
        engine.finishCompletedTrick();
        assertNull("a card decided for the trick before this one must be dropped",
                engine.commitAiCard(turn));
    }
}
