package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.BeliefModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The half of the belief that can be tested without a runtime.
 *
 * <p>{@link BeliefModel} is one method wide precisely so this file can exist: a
 * stub that always says "left", one that says "skat", one that returns nonsense
 * and one that throws are all a line each, and between them they cover every way
 * the wiring can be wrong. What is <em>not</em> covered here is whether the
 * trained network is any good — that is a question for the arena, in game points,
 * and no unit test can answer it.
 *
 * <p>The two properties under test are the ones a mistake would hide rather than
 * announce. First, that the model's classes reach the sampler's places: left,
 * right and skat are relative to the seat deciding, so an off-by-one seat is a
 * player that carefully believes the mirror image of what its model said and
 * still plays legal, plausible-looking Skat. Second, that a model which fails
 * costs strength and not the match.
 */
public class BeliefWorldSourceTest {

    /** A model certain that one named card sits in one named class. */
    private static BeliefModel certainAbout(Card card, int cardClass) {
        return features -> {
            float[] logits = new float[32 * BeliefEncoding.CLASSES];
            int watched = BeliefEncoding.index(card);
            for (int at = 0; at < 32; at++) {
                for (int place = 0; place < BeliefEncoding.CLASSES; place++) {
                    logits[at * BeliefEncoding.CLASSES + place] =
                            at == watched && place == cardClass ? 8f : 0f;
                }
            }
            return logits;
        };
    }

    @Test public void theModelSteersTheDrawTowardsTheSeatItNames() {
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            Card watched = unseenFor(seat).get(0);
            int left = count(seat, certainAbout(watched, BeliefEncoding.CLASS_LEFT), watched,
                    world -> world.hands().get(seat.next().ordinal()).contains(watched));
            int right = count(seat, certainAbout(watched, BeliefEncoding.CLASS_RIGHT), watched,
                    world -> world.hands().get(seat.next().next().ordinal()).contains(watched));
            int uniform = count(seat, null, watched,
                    world -> world.hands().get(seat.next().ordinal()).contains(watched));

            // Left is the seat that plays after the observer, whichever seat that
            // is. Getting this relative is what lets one network serve all three.
            assertTrue("seated at " + seat + ", a belief in left placed it left "
                    + left + " times of 200, against " + uniform + " uniform", left > 150);
            assertTrue("seated at " + seat + ", a belief in right placed it right "
                    + right + " times of 200", right > 150);
        }
    }

    /**
     * The skat is a place a card can be, and the model can say so.
     *
     * <p>Stated as a belief about <em>every</em> card rather than about one,
     * because two slots against twenty-two candidates is a queue: a model that
     * nominates one card for the skat while shrugging about the rest watches the
     * slots fill with shrugs before its candidate is reached. That is not a bug
     * in the wiring — it is what sequential assignment does, and a trained model
     * does not behave that way, since it spends its skat mass on a few cards and
     * denies it to the rest. So the stub does the same.
     */
    @Test public void itCanAlsoBelieveACardIsBuried() {
        SkatAi.Seat seat = SkatAi.Seat.OPPONENT_ONE;
        Card watched = unseenFor(seat).get(0);
        int buried = count(seat, believesOnlyThisIsBuried(watched), watched,
                world -> world.skat().contains(watched));
        int uniform = count(seat, null, watched, world -> world.skat().contains(watched));

        // Two of twenty-two places are the skat, so uniform is about 9%. A class
        // the sampler could not reach would leave this pinned there, which is the
        // failure this test exists for -- the skat is place 3, not a seat.
        assertTrue("belief in the skat put it there " + buried + " times of 200, uniform "
                + uniform, buried > 150);
    }

    /** "This card is buried and none of the others are." */
    private static BeliefModel believesOnlyThisIsBuried(Card card) {
        return features -> {
            float[] logits = new float[32 * BeliefEncoding.CLASSES];
            int watched = BeliefEncoding.index(card);
            for (int at = 0; at < 32; at++) {
                logits[at * BeliefEncoding.CLASSES + BeliefEncoding.CLASS_SKAT] =
                        at == watched ? 8f : -8f;
            }
            return logits;
        };
    }

    /**
     * Every way a model can fail, and the same answer to all of them: reason
     * uniformly for that decision. A belief that is unavailable should cost
     * points, not a night's measurement.
     */
    @Test public void abrokenModelFallsBackInsteadOfFailing() {
        List<BeliefModel> broken = List.of(
                features -> { throw new IllegalStateException("the runtime died"); },
                features -> new float[7],
                features -> null,
                features -> {
                    float[] logits = new float[32 * BeliefEncoding.CLASSES];
                    java.util.Arrays.fill(logits, Float.NaN);
                    return logits;
                },
                new BeliefModel() {
                    @Override public float[] logits(float[] features) {
                        throw new AssertionError("a width mismatch must be caught first");
                    }
                    @Override public int inputs() { return BeliefEncoding.SIZE + 1; }
                });

        for (BeliefModel model : broken) {
            List<WorldSampler.World> worlds = new BeliefWorldSource(model)
                    .sample(evidence(SkatAi.Seat.HUMAN), 20, new Random(3));
            assertEquals("a failed model still yields a full sample", 20, worlds.size());
            for (WorldSampler.World world : worlds) assertTrue(legal(world));
        }
    }

    @Test public void everySampledWorldIsStillALegalDeal() {
        Card watched = unseenFor(SkatAi.Seat.HUMAN).get(0);
        List<WorldSampler.World> worlds =
                new BeliefWorldSource(certainAbout(watched, BeliefEncoding.CLASS_SKAT), 4.0)
                        .sample(evidence(SkatAi.Seat.HUMAN), 50, new Random(8));
        assertEquals(50, worlds.size());
        for (WorldSampler.World world : worlds) {
            assertTrue("a sharpened belief may not bend the deal", legal(world));
        }
    }

    private interface Landed {
        boolean in(WorldSampler.World world);
    }

    /** How often {@code watched} landed where {@code landed} looks, over 200 draws. */
    private static int count(SkatAi.Seat seat, BeliefModel model, Card watched, Landed landed) {
        WorldSource source = model == null ? WorldSource.UNIFORM : new BeliefWorldSource(model);
        List<WorldSampler.World> worlds = source.sample(evidence(seat), 200, new Random(5));
        assertEquals(200, worlds.size());
        int found = 0;
        for (WorldSampler.World world : worlds) {
            assertTrue(legal(world));
            if (landed.in(world)) found++;
        }
        return found;
    }

    private static boolean legal(WorldSampler.World world) {
        if (world == null) return false;
        Set<Card> seen = new LinkedHashSet<>();
        for (List<Card> hand : world.hands()) {
            if (hand.size() != 10) return false;
            seen.addAll(hand);
        }
        seen.addAll(world.skat());
        return world.skat().size() == 2 && seen.size() == 32;
    }

    /** Before a card is played: ten in hand, twenty-two unseen, nothing ruled out. */
    private static BeliefEncoding.Evidence evidence(SkatAi.Seat seat) {
        Set<Card> hand = new LinkedHashSet<>(handFor(seat));
        SkatAi.GameDefinition game = new SkatAi.GameDefinition(seat, seat, Contract.GRAND);
        SkatAi.DecisionContext context = new SkatAi.DecisionContext(
                game, seat, hand, hand,
                new SkatAi.CurrentTrick(0, seat, List.of()),
                new SkatAi.GameHistory(List.of()),
                new SkatAi.DerivedGameKnowledge(Set.of(), Map.of(), Map.of(), Map.of()));
        return new BeliefEncoding.Evidence(context, Set.of(), Map.of(), null, Map.of(),
                false, null, Set.of());
    }

    /** A different ten per seat, so no test accidentally passes on one hand. */
    private static List<Card> handFor(SkatAi.Seat seat) {
        List<Card> deck = SkatDeck.ordered();
        return new ArrayList<>(deck.subList(seat.ordinal() * 10, seat.ordinal() * 10 + 10));
    }

    private static List<Card> unseenFor(SkatAi.Seat seat) {
        List<Card> unseen = new ArrayList<>(SkatDeck.ordered());
        unseen.removeAll(handFor(seat));
        return unseen;
    }
}
