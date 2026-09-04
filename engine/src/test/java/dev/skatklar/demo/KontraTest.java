package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Kontra and Re: a multiplication on the value, and one moment to say it.
 *
 * <p>The timing is the part worth testing rather than the arithmetic. The canon
 * takes the late variant — a seat may speak until it plays its own first card
 * into the first trick — which means a defender in third position gets to watch
 * two cards fall first, and that everybody's window closes at a different time.
 */
public class KontraTest {

    /** Plays the first legal card every time, so two runs differ only in the doubling. */
    private static class Predictable implements SkatAiProvider {
        private final boolean saysContra;

        Predictable(boolean saysContra) { this.saysContra = saysContra; }

        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("predictable", "Plays the first legal card", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    List<Card> hand = new ArrayList<>(context.hand);
                    return Set.of(hand.get(0), hand.get(1));
                }

                @Override public boolean announceContra(SkatAi.ContraContext context) {
                    return saysContra;
                }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    return context.legalCards.iterator().next();
                }
            };
        }
    }

    @Test public void kontraDoublesTheValueAndReDoublesItAgain() {
        int plain = valueWith(false, false);
        int kontra = valueWith(true, false);
        int re = valueWith(true, true);

        assertEquals(2 * plain, kontra);
        assertEquals(4 * plain, re);
    }

    @Test public void theResultRecordsHowFarTheDoublingWent() {
        assertEquals(SkatAi.ContraLevel.NONE, resultWith(false, false).contra);
        assertEquals(SkatAi.ContraLevel.KONTRA, resultWith(true, false).contra);
        assertEquals(SkatAi.ContraLevel.RE, resultWith(true, true).contra);
    }

    /**
     * The declarer cannot Kontra their own game, and the window is only open
     * while the first trick is still being played to.
     */
    @Test public void onlyADefenderMayDoubleAndOnlyBeforeItPlays() {
        GameEngine engine = table(false, false);
        SkatAi.GameDefinition game = engine.snapshot().definition;
        SkatAi.Seat declarer = game.declarer;

        assertFalse("the declarer cannot Kontra itself", engine.mayAnnounceContra(declarer));
        SkatAi.Seat defender = declarer.next();
        assertTrue(engine.mayAnnounceContra(defender));

        // Play the whole first trick, which closes everybody's window.
        for (int card = 0; card < 3; card++) engine.playAiCard();
        assertFalse(engine.mayAnnounceContra(defender));
        assertFalse(engine.announceContra(defender));
    }

    // ---------------------------------------------------------------- helpers

    private static int valueWith(boolean kontra, boolean re) {
        return Math.abs(resultWith(kontra, re).gameValue);
    }

    private static SkatAi.GameResult resultWith(boolean kontra, boolean re) {
        GameEngine engine = table(kontra, re);
        for (int step = 0; step < 128 && engine.snapshot().result == null; step++) {
            if (engine.snapshot().trickComplete()) engine.finishCompletedTrick();
            else engine.playAiCard();
        }
        return engine.snapshot().result;
    }

    /**
     * One fixed board at a fixed contract, so the only thing that varies between
     * runs is who says what before the first card.
     */
    private static GameEngine table(boolean defendersDouble, boolean declarerAnswers) {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        SkatAi.Seat declarer = round.forehand;
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            seating.put(seat, new Predictable(
                    seat == declarer ? declarerAnswers : defendersDouble));
        }
        GameEngine engine = GameEngine.headless(new Random(4), SeatedAiProviders.of(seating));
        engine.restartWithContract(SkatDeck.deal(new Random(11)), round, declarer,
                Contract.CLUBS, 18, Set.of());
        return engine;
    }
}
