package dev.skatklar.training.players;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.PolicyNet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

    @Test public void theSearchTakesOverFromItsTrick() throws IOException {
        PolicyNet net = RolloutRescueTest.randomNet(43);
        List<TrueDealLabeller.Fed> fed = new ArrayList<>();
        TrueDealLabeller labeller = new TrueDealLabeller(net, 2, 1.0).withSearch(6, 3).withTap(fed::add);
        int searched = 0;
        int byNet = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(97, 2)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2 || at.history.completedTricks.size() > 6) continue;
            fed.clear();
            TrueDealLabeller.Labels l = labeller.label(at, step.hands(), step.skat(), Map.of(), step.skat(),
                    new ArrayList<>(at.legalCards), 9);
            long counted = 0;
            for (TrueDealLabeller.Fed f : fed) {
                assertEquals("searched exactly from trick 6", f.trick() >= 6, f.searched());
                if (f.searched()) { searched++; counted++; } else { byNet++; }
            }
            assertEquals("the labels count the searches", counted, l.searches());
        }
        assertTrue("positions searched: " + searched + ", by the net: " + byNet, searched > 100 && byNet > 100);
    }

    @Test public void theSearchsCardIsTheTallyOfTheSolversVerdicts() throws IOException {
        // An independent tally over the same sampled worlds: every legal card a
        // vote for the seat's side where the declarer reaches (or, defending,
        // misses) 61 less its banked points and the world's skat; most votes,
        // ties to the net's highest score.
        PolicyNet net = RolloutRescueTest.randomNet(44);
        int checked = 0;
        int decidedByVotes = 0;
        int asDefender = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(101, 10)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2 || at.history.completedTricks.size() < 3) continue;
            boolean declares = at.mySeat == at.game.declarer;
            List<Card> discard = TrueDealLabeller.knownDiscard(at, step.skat());
            float[] scores = net.logits(PolicyNet.features(dev.skatklar.training.data.SelfPlayExportMain.perfectMemory(
                    at, Map.of(), discard)));
            Card card = TrueDealLabeller.searchCard(at, discard, 6, new java.util.Random(5), scores);

            List<Card> legal = new ArrayList<>(at.legalCards);
            int[] votes = new int[legal.size()];
            dev.skatklar.demo.search.WorldSampler sampler = dev.skatklar.demo.search.WorldSampler.forDecision(at, discard);
            java.util.Random random = new java.util.Random(5);
            List<Card> trick = new ArrayList<>();
            for (SkatAi.PlayedCard p : at.currentTrick.plays) trick.add(p.card);
            for (int w = 0; w < 6; w++) {
                dev.skatklar.demo.search.WorldSampler.World world = sampler.draw(random);
                if (world == null) continue;
                int need = 61 - at.derived.cardPoints.getOrDefault(at.game.declarer, 0)
                        - dev.skatklar.demo.SkatRules.cardPoints(world.skat());
                for (int c = 0; c < legal.size(); c++) {
                    boolean reaches = need <= 0;
                    if (!reaches) {
                        for (dev.skatklar.demo.solve.DoubleDummySolver.Verdict v : dev.skatklar.demo.solve.DoubleDummySolver
                                .movesReaching(at.game.contract, at.game.declarer, at.mySeat, world.hands(),
                                        at.currentTrick.leader, trick, need)) {
                            if (v.card().equals(legal.get(c))) reaches = v.reachesTarget();
                        }
                    }
                    if (reaches == declares) votes[c]++;
                }
            }
            int top = 0;
            for (int c = 1; c < legal.size(); c++) if (votes[c] > votes[top]) top = c;
            Card expected = null;
            float high = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < legal.size(); c++) {
                float sc = scores[dev.skatklar.demo.belief.BeliefEncoding.index(legal.get(c))];
                if (votes[c] == votes[top] && sc > high) { expected = legal.get(c); high = sc; }
            }
            assertEquals("the search's card is the tally's", expected, card);
            boolean unanimous = true;
            for (int v : votes) unanimous &= v == votes[0];
            if (!unanimous && !expected.equals(RolloutRescue.top(scores, legal))) decidedByVotes++;
            if (!declares) asDefender++;
            checked++;
        }
        assertTrue("positions: " + checked, checked > 30);
        assertTrue("the votes overrule the net somewhere: " + decidedByVotes, decidedByVotes >= 10);
        assertTrue("defenders among them: " + asDefender, asDefender > 10);
    }

    @Test public void aSeatKnowsItsOwnDiscardAndNothingElse() {
        int seen = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(103, 1)) {
            SkatAi.DecisionContext at = step.context();
            List<Card> discard = step.skat();
            if (at.mySeat == at.game.declarer) {
                assertEquals("the declarer its discard", discard, TrueDealLabeller.knownDiscard(at, discard));
            } else {
                assertEquals("a defender nothing of the skat", null, TrueDealLabeller.knownDiscard(at, discard));
            }
            seen++;
        }
        assertTrue(seen > 10);
    }

    @Test public void aReachedGameLeavesTheChoiceToTheNet() throws IOException {
        PolicyNet net = RolloutRescueTest.randomNet(45);
        int checked = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(107, 6)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2) continue;
            int banked = at.derived.cardPoints.getOrDefault(at.game.declarer, 0)
                    + dev.skatklar.demo.SkatRules.cardPoints(step.skat());
            if (banked < 61) continue;
            List<Card> discard = TrueDealLabeller.knownDiscard(at, step.skat());
            float[] scores = net.logits(PolicyNet.features(dev.skatklar.training.data.SelfPlayExportMain.perfectMemory(
                    at, Map.of(), discard)));
            // Every world agrees on the skat only for the declarer; a defender's worlds may not have reached.
            if (at.mySeat != at.game.declarer) continue;
            assertEquals("the game is won whatever is played: the net's card",
                    RolloutRescue.top(scores, new ArrayList<>(at.legalCards)),
                    TrueDealLabeller.searchCard(at, discard, 4, new java.util.Random(3), scores));
            checked++;
        }
        assertTrue("reached positions: " + checked, checked > 3);
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

    /** Each play-out's positions before trick {@code before}, by candidate and draw, in order. */
    private static Map<String, List<float[]>> before(List<TrueDealLabeller.Fed> fed, int before) {
        Map<String, List<float[]>> out = new HashMap<>();
        for (TrueDealLabeller.Fed f : fed) {
            if (f.trick() < before) out.computeIfAbsent(f.candidate() + "/" + f.draw(), x -> new ArrayList<>()).add(f.input());
        }
        return out;
    }

    /** Each play-out's first position of trick {@code trick}, by candidate and draw. */
    private static Map<String, float[]> first(List<TrueDealLabeller.Fed> fed, int trick) {
        Map<String, float[]> out = new HashMap<>();
        for (TrueDealLabeller.Fed f : fed) if (f.trick() == trick) out.putIfAbsent(f.candidate() + "/" + f.draw(), f.input());
        return out;
    }

    @Test public void theReseedSharesThePlayOutsUpToItsTrickAndPartsAfter() throws IOException {
        // The search's control: the same play-outs as the plain labeller up to
        // trick 6, other luck from there, so other labels.
        PolicyNet net = RolloutRescueTest.randomNet(47);
        List<TrueDealLabeller.Fed> plainFed = new ArrayList<>();
        List<TrueDealLabeller.Fed> reseedFed = new ArrayList<>();
        TrueDealLabeller plain = new TrueDealLabeller(net, 3, 1.0).withTap(plainFed::add);
        TrueDealLabeller reseeded = new TrueDealLabeller(net, 3, 1.0).withTap(reseedFed::add).withReseed(6);
        int checked = 0;
        int labelsDiffer = 0;
        int partedAfter = 0;
        int reachedSeventh = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(103, 3)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 2 || at.history.completedTricks.size() > 3) continue;
            plainFed.clear();
            reseedFed.clear();
            List<Card> cards = new ArrayList<>(at.legalCards);
            TrueDealLabeller.Labels a = plain.label(at, step.hands(), step.skat(), Map.of(), step.skat(), cards, 13);
            TrueDealLabeller.Labels b = reseeded.label(at, step.hands(), step.skat(), Map.of(), step.skat(), cards, 13);
            Map<String, List<float[]>> early = before(plainFed, 6);
            Map<String, List<float[]>> earlyReseeded = before(reseedFed, 6);
            assertEquals("the same play-outs reach trick 6", early.keySet(), earlyReseeded.keySet());
            for (String key : early.keySet()) {
                List<float[]> x = early.get(key);
                List<float[]> y = earlyReseeded.get(key);
                assertEquals("positions before trick 6, " + key, x.size(), y.size());
                for (int i = 0; i < x.size(); i++) assertArrayEquals("position " + i + " of " + key, x.get(i), y.get(i), 0f);
            }
            // Trick 6 itself is played on the fresh luck: the first position of
            // trick 7 already differs in many play-outs.
            Map<String, float[]> seventh = first(plainFed, 7);
            Map<String, float[]> seventhReseeded = first(reseedFed, 7);
            for (String key : seventh.keySet()) {
                reachedSeventh++;
                if (!Arrays.equals(seventh.get(key), seventhReseeded.get(key))) partedAfter++;
            }
            if (!Arrays.equals(a.value(), b.value()) || !Arrays.equals(a.wins(), b.wins())) labelsDiffer++;
            checked++;
        }
        assertTrue("decisions checked: " + checked, checked > 10);
        assertTrue("trick 7 opens differently: " + partedAfter + " of " + reachedSeventh + " play-outs",
                reachedSeventh > 100 && partedAfter > reachedSeventh / 5);
        assertTrue("other labels: " + labelsDiffer + " of " + checked, labelsDiffer > checked / 2);
    }

    @Test public void withTheReseedACardsLabelIsStillTheSameAloneAndAmongOthers() throws IOException {
        // The fresh luck is drawn from the draw's own stream, so the candidates
        // still share it.
        PolicyNet net = RolloutRescueTest.randomNet(48);
        TrueDealLabeller labeller = new TrueDealLabeller(net, 3, 1.0).withReseed(5);
        int checked = 0;
        for (PlayoutTest.Step step : RolloutRescueTest.steps(107, 3)) {
            SkatAi.DecisionContext at = step.context();
            if (at.legalCards.size() < 3 || at.history.completedTricks.size() > 5) continue;
            List<Card> all = new ArrayList<>(at.legalCards);
            TrueDealLabeller.Labels together = labeller.label(at, step.hands(), step.skat(), Map.of(), step.skat(), all, 21);
            for (int c = 0; c < all.size(); c++) {
                TrueDealLabeller.Labels alone = labeller.label(at, step.hands(), step.skat(), Map.of(), step.skat(),
                        List.of(all.get(c)), 21);
                assertEquals("value alone and together", alone.value()[0], together.value()[c]);
                assertEquals("wins alone and together", alone.wins()[0], together.wins()[c]);
            }
            checked++;
        }
        assertTrue("decisions checked: " + checked, checked > 10);
    }

    @Test public void theReseedIsTheSearchsControlNotItsCompanion() throws IOException {
        PolicyNet net = RolloutRescueTest.randomNet(49);
        TrueDealLabeller labeller = new TrueDealLabeller(net, 2, 1.0);
        assertThrows(IllegalArgumentException.class, () -> labeller.withSearch(7, 3).withReseed(7));
        assertThrows(IllegalArgumentException.class, () -> labeller.withReseed(7).withSearch(7, 3));
        assertThrows(IllegalArgumentException.class, () -> labeller.withReseed(0));
        assertEquals(7, labeller.withReseed(7).withTap(f -> { }).reseedFrom());
        assertEquals(11, labeller.reseedFrom());
    }
}
