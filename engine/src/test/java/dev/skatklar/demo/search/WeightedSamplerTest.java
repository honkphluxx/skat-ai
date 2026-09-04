package dev.skatklar.demo.search;

import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * A belief may steer the draw. It may not bend the deal.
 *
 * <p>That split is the whole reason the weighting lives inside the sampler
 * rather than replacing it. Everything that makes a world <em>legal</em> — ten
 * cards a seat, two in the skat, nothing in a hand that showed void — is
 * enforced above the weights, so the worst a wrong model can do is make the
 * player believe the wrong thing. It cannot make it believe an impossible thing,
 * and a search fed impossible worlds does not play badly, it crashes.
 */
public class WeightedSamplerTest {

    @Test public void weightsMoveTheDrawWithoutBreakingIt() {
        WorldSampler uniform = samplerForOpeningLead();
        List<Card> unseen = unseenCards();
        Card watched = unseen.get(0);

        // A belief certain that this one card sits on the left.
        WorldSampler weighted = uniform.weightedBy(
                (card, place) -> card.equals(watched) ? (place == leftOf() ? 1 : 0) : 1);

        int onTheLeft = 0;
        Random random = new Random(5);
        for (int draw = 0; draw < 200; draw++) {
            WorldSampler.World world = weighted.draw(random);
            assertTrue("a weighted draw is still a legal deal", legal(world));
            if (world.hands().get(leftOf()).contains(watched)) onTheLeft++;
        }
        // Not all two hundred, and the gap is the point: cards are placed most
        // constrained first, so by the time this one is reached the left hand is
        // occasionally already full. Capacity beats belief, which is the right
        // way round -- a legal deal that ignores the model is recoverable, a
        // model-shaped deal that is illegal is not.
        assertTrue("the belief moves nearly every draw: " + onTheLeft, onTheLeft > 180);
    }

    @Test public void theUniformSamplerIsUnchanged() {
        WorldSampler sampler = samplerForOpeningLead();
        Card watched = unseenCards().get(0);

        int onTheLeft = 0;
        Random random = new Random(5);
        for (int draw = 0; draw < 400; draw++) {
            WorldSampler.World world = sampler.draw(random);
            assertTrue(legal(world));
            if (world.hands().get(leftOf()).contains(watched)) onTheLeft++;
        }
        // Ten of the twenty-two unseen places are the left hand, so about 45%.
        assertTrue("without weights the draw stays uniform: " + onTheLeft,
                onTheLeft > 140 && onTheLeft < 220);
    }

    /**
     * A belief that rules out every open place is not a constraint, it is a
     * failure of the model, and the sampler falls back rather than giving up on
     * a position it can perfectly well fill.
     */
    @Test public void anImpossibleBeliefFallsBackInsteadOfFailing() {
        WorldSampler weighted = samplerForOpeningLead().weightedBy((card, place) -> 0);

        Random random = new Random(9);
        for (int draw = 0; draw < 50; draw++) {
            assertTrue(legal(weighted.draw(random)));
        }
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

    private static int leftOf() { return SkatAi.Seat.HUMAN.next().ordinal(); }

    private static List<Card> unseenCards() {
        List<Card> unseen = new ArrayList<>(SkatDeck.ordered());
        unseen.removeAll(myTen());
        return unseen;
    }

    private static List<Card> myTen() {
        return SkatDeck.ordered().subList(0, 10);
    }

    /** Before a card is played: ten in hand, twenty-two unseen, nothing ruled out. */
    private static WorldSampler samplerForOpeningLead() {
        SkatAi.GameDefinition game = new SkatAi.GameDefinition(
                SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN, Contract.GRAND);
        SkatAi.DecisionContext context = new SkatAi.DecisionContext(
                game, SkatAi.Seat.HUMAN, new LinkedHashSet<>(myTen()),
                new LinkedHashSet<>(myTen()),
                new SkatAi.CurrentTrick(0, SkatAi.Seat.HUMAN, List.of()),
                new SkatAi.GameHistory(List.of()),
                new SkatAi.DerivedGameKnowledge(Set.of(), Map.of(), Map.of(), Map.of()));
        return WorldSampler.forDecision(context, null, Set.of(), Map.of());
    }
}
