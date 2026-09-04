package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAi;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * The window between winning a trick and being allowed to lead the next one.
 *
 * <p>A trick the human has just won sits on the table for the settle, the hold
 * and the whole collection flight, and the surface hands the hand back for all
 * of it so a card can already be chosen and pulled out. What it must never do is
 * let that card be played, because the engine is still holding a complete trick.
 * {@link GameEngine.Snapshot#willLeadNextTrick} is the predicate both the touch
 * handling and the drawing key off, so the two can never disagree about whether
 * the hand is live.
 */
public class PreArmedLeadTest {

    /**
     * The promise the window makes: whenever it is open, finishing the trick
     * puts the human on turn with a real trick to lead and no card in it
     * illegal. Whenever it is shut for a trick the human won, there is a reason
     * -- the deal ended, or the next trick is the tenth, which plays itself.
     */
    @Test
    public void theWindowAlwaysDeliversTheTurnItPromises() {
        int windows = 0;
        for (Contract contract : new Contract[]{
                Contract.GRAND, Contract.CLUBS, Contract.HEARTS, Contract.NULL}) {
            for (int seed = 0; seed < 40; seed++) {
                windows += playDeal(contract, seed);
            }
        }
        assertTrue("the window was never observed, so this test proved nothing",
                windows > 40);
    }

    private int playDeal(Contract contract, int seed) {
        GameEngine engine = new GameEngine(new Random(seed), contract);
        int windows = 0;
        while (true) {
            GameEngine.Snapshot before = engine.snapshot();
            if (before.gameComplete()) return windows;
            if (before.trickComplete()) {
                boolean armed = before.willLeadNextTrick(SkatAi.Seat.HUMAN);
                if (armed) {
                    windows++;
                    assertEquals("armed for a seat that did not win the trick",
                            SkatAi.Seat.HUMAN, before.trickWinner);
                    // The window is emphatically not a turn. Everything that
                    // decides whether a move is accepted still says no.
                    assertFalse("the armed window posed as a turn",
                            before.waitingForHuman());
                    assertFalse("the engine took a card while a trick was complete",
                            engine.playHumanCard(before.hands.get(0).get(0)));
                }
                boolean ended = engine.finishCompletedTrick();
                GameEngine.Snapshot after = engine.snapshot();
                if (armed) {
                    assertFalse("armed, but the deal ended instead", ended);
                    assertTrue("armed, but the turn never came",
                            after.waitingForHuman());
                    assertTrue("armed, but the human is not leading",
                            after.trick.isEmpty());
                    assertTrue("armed into the tenth trick, which plays itself",
                            after.completedTricks <= 8);
                    // Leading is unconstrained, which is why the surface may
                    // treat the whole hand as playable inside the window.
                    assertEquals("a leading hand was not wholly legal",
                            after.hands.get(0).size(), after.legalCards.size());
                } else if (before.trickWinner == SkatAi.Seat.HUMAN) {
                    assertTrue("the window was withheld from an ordinary lead",
                            after.gameComplete() || after.completedTricks >= 9);
                }
                continue;
            }
            if (before.currentPlayer == 0) {
                Card pick = null;
                for (Card candidate : before.hands.get(0)) {
                    if (before.legalCards.contains(candidate)) {
                        pick = candidate;
                        break;
                    }
                }
                assertTrue("the human was on turn with no legal card", pick != null);
                assertTrue("a legal card was refused", engine.playHumanCard(pick));
            } else {
                engine.playAiCard();
            }
        }
    }

    /** Losing the trick never lends the hand back, however the deal is going. */
    @Test
    public void aSeatThatLostTheTrickIsNeverArmed() {
        int completed = 0;
        for (int seed = 0; seed < 60; seed++) {
            GameEngine engine = new GameEngine(new Random(seed), Contract.GRAND);
            while (!engine.snapshot().gameComplete()) {
                GameEngine.Snapshot snapshot = engine.snapshot();
                if (snapshot.trickComplete()) {
                    completed++;
                    for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                        if (seat == snapshot.trickWinner) continue;
                        assertFalse("a losing seat was armed to lead",
                                snapshot.willLeadNextTrick(seat));
                    }
                    engine.finishCompletedTrick();
                    continue;
                }
                if (snapshot.currentPlayer == 0) {
                    List<Card> hand = snapshot.hands.get(0);
                    for (Card candidate : hand) {
                        if (snapshot.legalCards.contains(candidate)) {
                            engine.playHumanCard(candidate);
                            break;
                        }
                    }
                } else {
                    engine.playAiCard();
                }
            }
        }
        assertTrue("no trick was ever completed", completed > 100);
    }

    /** Nothing is armed before the first card, or after the last. */
    @Test
    public void theWindowIsShutOutsideCardPlay() {
        GameEngine engine = new GameEngine(new Random(7), Contract.GRAND);
        assertFalse("armed before a card had been played",
                engine.snapshot().willLeadNextTrick(SkatAi.Seat.HUMAN));
        assertFalse("armed for a null seat",
                engine.snapshot().willLeadNextTrick(null));
        while (!engine.snapshot().gameComplete()) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.trickComplete()) {
                engine.finishCompletedTrick();
                continue;
            }
            if (snapshot.currentPlayer == 0) {
                for (Card candidate : snapshot.hands.get(0)) {
                    if (snapshot.legalCards.contains(candidate)) {
                        engine.playHumanCard(candidate);
                        break;
                    }
                }
            } else {
                engine.playAiCard();
            }
        }
        assertFalse("armed after the deal was over",
                engine.snapshot().willLeadNextTrick(SkatAi.Seat.HUMAN));
    }
}
