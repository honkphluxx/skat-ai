package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The sampler is where an imperfect-information player can cheat by accident.
 * Every one of these tests is about a way a world could be impossible, or about
 * the player seeing something it was never shown -- both of which would silently
 * inflate every measurement taken with it.
 */
public class WorldSamplerTest {

    /**
     * The strongest property available: whatever the sampler draws must be a deal
     * that could actually be the one on the table. Checked at every decision of
     * real games rather than on a constructed position, because the interesting
     * failures are mid-game -- after a discard, after a seat has shown a void.
     */
    @Test public void everyDrawnWorldCouldBeTheRealOne() {
        playGames((context, engine, sampler) -> {
            GameEngine.Snapshot truth = engine.snapshot();
            Random random = new Random(context.hand.size() * 31L + truth.completedTricks);
            for (int draw = 0; draw < 8; draw++) {
                WorldSampler.World world = sampler.draw(random);
                assertNotNull("a legal position must yield a world", world);

                // Same cards, same places, nothing invented and nothing lost.
                Set<Card> seen = new LinkedHashSet<>();
                for (int seat = 0; seat < 3; seat++) {
                    assertEquals("hand sizes must match the real ones",
                            truth.hands.get(seat).size(), world.hands().get(seat).size());
                    for (Card card : world.hands().get(seat)) {
                        assertTrue("no card may appear twice", seen.add(card));
                        assertFalse("a played card cannot be back in a hand",
                                context.derived.playedCards.contains(card));
                    }
                }
                for (Card card : world.skat()) {
                    assertTrue("the skat is part of the same pack", seen.add(card));
                }
                assertEquals("a world is a whole pack minus what has been played",
                        32 - context.derived.playedCards.size(), seen.size());

                assertEquals("the observer's own hand is not a guess",
                        new LinkedHashSet<>(context.hand),
                        new LinkedHashSet<>(world.hands().get(context.mySeat.ordinal())));

                // A seat that failed to follow cannot be holding that class.
                for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                    Set<SkatAi.FollowClass> voids = context.derived.voidClasses.get(seat);
                    if (voids == null || voids.isEmpty()) continue;
                    for (Card card : world.hands().get(seat.ordinal())) {
                        assertFalse(seat + " showed a void in "
                                        + SkatRules.publicFollowClass(context.game.contract, card),
                                voids.contains(SkatRules.publicFollowClass(
                                        context.game.contract, card)));
                    }
                }
            }
        });
    }

    /**
     * The other direction, and the one that catches a sampler which is merely
     * self-consistent: the deal that is actually on the table has to be drawable.
     * A constraint that excludes the truth makes every search wrong in a way no
     * amount of sampling repairs.
     */
    @Test public void theRealDealIsItselfAValidWorld() {
        playGames((context, engine, sampler) -> {
            GameEngine.Snapshot truth = engine.snapshot();
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                Set<SkatAi.FollowClass> voids = context.derived.voidClasses.get(seat);
                if (voids == null) continue;
                for (Card card : truth.hands.get(seat.ordinal())) {
                    assertFalse("the real deal contradicts an inferred void: " + seat + " " + card,
                            voids.contains(SkatRules.publicFollowClass(
                                    context.game.contract, card)));
                }
            }
        });
    }

    /** A declarer that remembers its discard must never sample it back into play. */
    @Test public void aKnownSkatIsNeverDealtOut() {
        playGames((context, engine, sampler) -> {
            if (context.mySeat != context.game.declarer) return;
            List<Card> buried = engine.snapshot().skat;
            WorldSampler knowing = WorldSampler.forDecision(context, buried);
            Random random = new Random(7);
            for (int draw = 0; draw < 8; draw++) {
                WorldSampler.World world = knowing.draw(random);
                assertNotNull(world);
                assertEquals("a known skat is fixed, not sampled",
                        new LinkedHashSet<>(buried), new LinkedHashSet<>(world.skat()));
                for (List<Card> hand : world.hands()) {
                    assertTrue("a buried card cannot be in anybody's hand",
                            Collections.disjoint(hand, buried));
                }
            }
        });
    }

    // ------------------------------------------------------------------ harness

    private interface Check {
        void run(SkatAi.DecisionContext context, GameEngine engine, WorldSampler sampler);
    }

    /**
     * Plays a few complete games with random legal play, running {@code check} at
     * every decision. Random play is the point: it produces void suits and odd
     * distributions far more often than a heuristic player would.
     */
    private void playGames(Check check) {
        int decisions = 0;
        for (int game = 0; game < 4; game++) {
            SkatDeck.Deal deal = SkatDeck.deal(new Random(game));
            Probe probe = new Probe(check);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, probe);
            GameEngine engine = GameEngine.headless(new Random(game),
                    SeatedAiProviders.of(seating));
            probe.engine = engine;
            engine.restartWithContract(deal, SkatAi.RoundPosition.at(game), SkatAi.Seat.HUMAN,
                    Contract.CLUBS, 0, Set.of());
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot snapshot = engine.snapshot();
                if (snapshot.gameComplete()) break;
                if (snapshot.trickComplete()) engine.finishCompletedTrick();
                else engine.playAiCard();
            }
            decisions += probe.decisions;
            engine.close();
        }
        assertTrue("the harness must actually reach decisions, was " + decisions, decisions > 50);
    }

    /** Plays at random, and checks the sampler on the way past. */
    private static final class Probe implements SkatAiProvider {
        private final Check check;
        private final SkatAiProvider blind = new LegalRandomAiProvider(new Random(99));
        private GameEngine engine;
        private int decisions;

        Probe(Check check) { this.check = check; }

        @Override public SkatAi.AiDescriptor descriptor() { return blind.descriptor(); }

        @Override public SkatAiSession createSession() {
            SkatAiSession inner = blind.createSession();
            return new SkatAiSession() {
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    List<Card> twelve = new ArrayList<>(context.hand);
                    return new LinkedHashSet<>(twelve.subList(0, 2));
                }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    decisions++;
                    check.run(context, engine, WorldSampler.forDecision(context, null));
                    return inner.chooseCard(context);
                }
            };
        }
    }
}
