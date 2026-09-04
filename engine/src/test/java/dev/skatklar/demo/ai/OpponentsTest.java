package dev.skatklar.demo.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.WorldSource;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The seam the app reaches the measured players through.
 *
 * <p>Small, because most of what could go wrong is caught elsewhere: the
 * personalities have their own test and the search has the arena. What is left
 * is the part that exists only for the app — that the four levels really are
 * four different players, that they are ordered the way the settings screen
 * presents them, and that one of them can be seated and will play a legal game.
 */
public class OpponentsTest {

    @Test public void everyLevelIsItsOwnPlayer() {
        Set<Personality> distinct = new LinkedHashSet<>();
        for (Opponents.Level level : Opponents.Level.values()) distinct.add(level.personality());
        assertEquals("every level must be its own player",
                Opponents.Level.values().length, distinct.size());
    }

    /**
     * The settings screen lists them in enum order and calls that easier to
     * harder. Nothing else enforces it, and a level list that is not monotone is
     * a settings screen that lies.
     */
    @Test public void theyGetHarderInTheOrderTheyAreListed() {
        Personality previous = null;
        for (Opponents.Level level : Opponents.Level.values()) {
            Personality personality = level.personality();
            if (previous != null) {
                assertTrue(level + " remembers no more than the level below it",
                        personality.memory() >= previous.memory());
                assertTrue(level + " thinks no harder than the level below it",
                        personality.worlds() >= previous.worlds());
            }
            previous = personality;
        }
        assertTrue("the top level must be meaningfully above the bottom",
                Opponents.Level.ANALYST.personality().worlds()
                        > Opponents.Level.BEGINNER.personality().worlds() * 4);
    }

    /**
     * The entry level does not get the learned belief, and the others do.
     *
     * <p>Worth a test rather than a comment because it is invisible from the
     * outside: a beginner with a good belief is a beginner that guesses where
     * the cards are as well as the analyst does, which is not what the level is
     * for. Passing a belief in and getting uniform sampling out is the whole
     * behaviour.
     */
    @Test public void theBeginnerIsNotGivenTheBelief() {
        assertTrue(Opponents.Level.BEGINNER.personality().worlds() > 0);
        assertEquals(false, Opponents.Level.BEGINNER.usesBelief());
        for (Opponents.Level level : Opponents.Level.values()) {
            if (level != Opponents.Level.BEGINNER) {
                assertTrue(level + " should reason with the belief", level.usesBelief());
            }
        }
        // And a level that does not use it must still be seatable when one is
        // handed in, rather than refusing or quietly using it anyway.
        WorldSource refuses = (evidence, count, random) -> {
            throw new AssertionError("the beginner must not consult the belief");
        };
        assertNotNull(Opponents.seat(Opponents.Level.BEGINNER, refuses, 3L));
    }

    @Test public void theDefaultIsOneOfThem() {
        assertNotNull(Opponents.DEFAULT);
        assertEquals(Opponents.Level.CLUB, Opponents.DEFAULT);
    }

    /**
     * A seated level plays a whole game without the engine having to substitute
     * a card for it. That is the one failure the app cannot recover from: an
     * opponent that names an illegal card is a game that cannot continue.
     */
    @Test public void aSeatedLevelPlaysALegalGame() {
        for (Opponents.Level level : Opponents.Level.values()) {
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, Opponents.seat(level, WorldSource.UNIFORM, 5L));
            }
            GameEngine engine = GameEngine.headless(new Random(7), SeatedAiProviders.of(seating));
            engine.restartWithContract(SkatDeck.deal(new Random(7)),
                    SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN, Contract.CLUBS, 0, Set.of());
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot snapshot = engine.snapshot();
                if (snapshot.gameComplete()) break;
                if (snapshot.trickComplete()) engine.finishCompletedTrick();
                else engine.playAiCard();
            }
            assertTrue(level + " must finish the game", engine.snapshot().gameComplete());
            assertEquals(level + " named an illegal card", 0, engine.ruleViolations().size());
            engine.close();
        }
    }
}
