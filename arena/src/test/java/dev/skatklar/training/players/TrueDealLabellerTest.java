package dev.skatklar.training.players;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.PolicyNet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The labeller compares the cards on the same luck, and draws the opponents.
 *
 * <p>On a net of random weights and decisions from real engine games: a card's
 * label does not depend on which other cards are labelled beside it or in what
 * order (draw d is the same stream for every candidate); and with drawn
 * opponents the labels are not the top-card labels, while the top-card form is
 * deterministic.
 */
public class TrueDealLabellerTest {

    @Test public void aCardsLabelIsTheSameAloneAndAmongOthers() throws IOException {
        PolicyNet net = RolloutRescueTest.randomNet(41);
        TrueDealLabeller labeller = new TrueDealLabeller(net, 4, 1.0);
        int checked = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(83, 3)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 3 || at.history.completedTricks.size() > 6) continue;
            List<Card> all = new ArrayList<>(at.legalCards);
            List<Card> discard = at.mySeat == at.game.declarer ? step.skat() : step.skat();
            TrueDealLabeller.Labels together = labeller.label(at, step.hands(), step.skat(), Map.of(), discard, all, 77);
            List<Card> reversed = new ArrayList<>(all);
            Collections.reverse(reversed);
            TrueDealLabeller.Labels backwards = labeller.label(at, step.hands(), step.skat(), Map.of(), discard,
                    reversed, 77);
            for (int c = 0; c < all.size(); c++) {
                TrueDealLabeller.Labels alone = labeller.label(at, step.hands(), step.skat(), Map.of(), discard,
                        List.of(all.get(c)), 77);
                int r = all.size() - 1 - c;
                assertEquals("wins alone and together", alone.wins()[0], together.wins()[c]);
                assertEquals("points alone and together", alone.points()[0], together.points()[c]);
                assertEquals("value alone and together", alone.value()[0], together.value()[c]);
                assertEquals("value in reverse order", together.value()[c], backwards.value()[r]);
            }
            checked++;
        }
        assertTrue("decisions checked: " + checked, checked > 10);
    }

    @Test public void theOpponentsAreDrawn() throws IOException {
        PolicyNet net = RolloutRescueTest.randomNet(42);
        int differ = 0;
        int checked = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(89, 3)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2 || at.history.completedTricks.size() > 5) continue;
            List<Card> all = new ArrayList<>(at.legalCards);
            TrueDealLabeller.Labels top = TrueDealLabeller.topOnly(net, 3).label(at, step.hands(), step.skat(),
                    Map.of(), step.skat(), all, 5);
            TrueDealLabeller.Labels again = TrueDealLabeller.topOnly(net, 3).label(at, step.hands(), step.skat(),
                    Map.of(), step.skat(), all, 6);
            for (int c = 0; c < all.size(); c++) {
                assertEquals("the top card form does not depend on the streams", top.value()[c], again.value()[c]);
                for (int d = 1; d < 3; d++) {
                    assertEquals("nor on the draw", top.valueByDraw()[c][0], top.valueByDraw()[c][d]);
                }
            }
            TrueDealLabeller.Labels drawn = new TrueDealLabeller(net, 3, 1.0).label(at, step.hands(), step.skat(),
                    Map.of(), step.skat(), all, 5);
            if (!java.util.Arrays.equals(drawn.value(), top.value())) differ++;
            checked++;
        }
        assertTrue("drawn opponents change the labels (" + differ + " of " + checked + ")", differ > checked / 3);
    }
}
