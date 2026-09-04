package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class PersonalityTest {

    /** The sliders a settings screen would show have to reach both ends. */
    @Test public void slidersSpanTheIntendedRange() {
        assertEquals(2, Personality.ofSliders(0, 0, 50, 50).worlds());
        assertEquals(64, Personality.ofSliders(100, 100, 50, 50).worlds());
        assertEquals(1.0, Personality.ofSliders(50, 100, 50, 50).memory(), 1e-9);
        assertEquals(0.0, Personality.ofSliders(50, 0, 50, 50).memory(), 1e-9);
        assertEquals(-1.0, Personality.ofSliders(50, 50, 0, 50).risk(), 1e-9);
        assertEquals(1.0, Personality.ofSliders(50, 50, 100, 50).risk(), 1e-9);
    }

    /**
     * Risk is a target, not a coin. A bold player aims past the win and a
     * cautious one aims at it — and neither ever aims below it, because playing
     * for fewer than 61 points is not a style, it is a mistake.
     */
    @Test public void boldPlayAimsPastTheWinAndCautiousPlayAimsAtIt() {
        Personality bold = new Personality(8, 1.0, 1.0, 0.5);
        Personality careful = new Personality(8, 1.0, -1.0, 0.5);
        assertEquals(61, careful.targetFor(0));
        assertEquals(90, bold.targetFor(0));
        assertTrue("the target moves with what is already banked",
                careful.targetFor(20) < careful.targetFor(0));
    }

    /**
     * Aggression is how heavily a lost game weighs, and a lighter loss is a
     * lower bar: the bar itself is now an expectation rather than a threshold,
     * so this is where the dial has to bite.
     */
    @Test public void aggressionLowersTheBarForBidding() {
        assertTrue(new Personality(8, 1.0, 0.0, 1.0).lossWeight()
                < new Personality(8, 1.0, 0.0, 0.0).lossWeight());
        assertEquals("neutral weighs a loss exactly as the score sheet charges it",
                1.0, new Personality(8, 1.0, 0.0, 0.5).lossWeight(), 1e-9);
    }

    /**
     * The mechanism behind the memory dial, checked where it actually bites: a
     * player that has forgotten a card reasons about worlds in which that card is
     * still out there. That is the whole trick — the misplays it causes are the
     * ones a human makes when the count slips, not arbitrary ones.
     */
    @Test public void aForgottenCardComesBackIntoPlay() {
        Capture capture = new Capture();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, capture);
        GameEngine engine = GameEngine.headless(new Random(3), SeatedAiProviders.of(seating));
        engine.restartWithContract(SkatDeck.deal(new Random(3)), SkatAi.RoundPosition.at(0),
                SkatAi.Seat.HUMAN, Contract.CLUBS, 0, Set.of());
        for (int step = 0; step < 128 && capture.late == null; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) break;
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else engine.playAiCard();
        }
        engine.close();

        SkatAi.DecisionContext context = capture.late;
        assertTrue("the harness must reach a position with cards already played",
                context != null && context.derived.playedCards.size() >= 6);

        Card forgotten = context.derived.playedCards.iterator().next();
        Set<Card> patchy = new LinkedHashSet<>(context.derived.playedCards);
        patchy.remove(forgotten);

        WorldSampler forgetful = WorldSampler.forDecision(context, null, patchy,
                context.derived.voidClasses);
        boolean resurfaced = false;
        Random random = new Random(5);
        for (int draw = 0; draw < 30 && !resurfaced; draw++) {
            WorldSampler.World world = forgetful.draw(random);
            if (world == null) continue;
            for (List<Card> hand : world.hands()) {
                if (hand.contains(forgotten)) resurfaced = true;
            }
            if (world.skat().contains(forgotten)) resurfaced = true;
        }
        assertTrue("a forgotten card must be able to reappear", resurfaced);

        WorldSampler exact = WorldSampler.forDecision(context, null,
                context.derived.playedCards, context.derived.voidClasses);
        for (int draw = 0; draw < 30; draw++) {
            WorldSampler.World world = exact.draw(random);
            if (world == null) continue;
            for (List<Card> hand : world.hands()) {
                assertNotEquals("a player that remembers must not deal a played card back",
                        true, hand.contains(forgotten));
            }
        }
    }

    /** Grabs a decision context from the middle of a game. */
    private static final class Capture implements SkatAiProvider {
        private final SkatAiProvider blind = new LegalRandomAiProvider(new Random(4));
        private SkatAi.DecisionContext late;

        @Override public SkatAi.AiDescriptor descriptor() { return blind.descriptor(); }

        @Override public SkatAiSession createSession() {
            SkatAiSession inner = blind.createSession();
            return new SkatAiSession() {
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    return new LinkedHashSet<>(new ArrayList<>(context.hand).subList(0, 2));
                }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    if (context.derived.playedCards.size() >= 6 && late == null) late = context;
                    return inner.chooseCard(context);
                }
            };
        }
    }
}
