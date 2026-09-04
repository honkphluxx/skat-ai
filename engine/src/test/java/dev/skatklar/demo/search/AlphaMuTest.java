package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.solve.DoubleDummySolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * Two properties, and between them they say whether αµ is what it claims.
 *
 * <p>The first is an <b>identity</b>: at depth one the algorithm expands nothing
 * of its own before handing the position to the solver, so it must reproduce the
 * determinized vote exactly — same worlds, same verdicts, and the same count
 * <em>for every card</em>, not merely for the best one. The weaker version of
 * this test, which compared only the best card's score, passed happily while the
 * search was throwing away the caller's tie-break and costing 2.8 game points a
 * game. Ties are the common case with one bit per world; a test that ignores
 * them ignores most of the decisions.
 *
 * <p>The second is a <b>direction</b>: deeper must never claim more. αµ's whole
 * purpose is to take away the optimism a determinized search gets by planning a
 * different continuation in every world, so its estimate of how many worlds it
 * wins can only fall as the depth rises. A depth-two search that claimed more
 * worlds than depth one would be a search that had found extra optimism, which
 * is the bug this algorithm exists to remove. The paper leans on the same fact
 * for its root cut.
 *
 * <p>Deliberately not tested here: whether it plays better. That is not a unit
 * test's business — it is a duplicate-deal match, and it is the reason the arena
 * exists.
 */
public class AlphaMuTest {

    private static final int TARGET = 30;

    /**
     * Five cards a hand, not ten.
     *
     * <p>Two reasons, and the second is the one worth writing down. Ten cards a
     * hand is unaffordable — the search costs about six times as much per extra
     * card — so it is not the regime the player uses this in anyway. And at the
     * opening lead the sampled worlds disagree about almost every card, so each
     * opponent reply splits them into near-singletons and committing to one card
     * across a singleton constrains nothing. Fusion needs several worlds to
     * survive the same opponent card, which is an endgame condition. A test
     * written at trick one measures the algorithm in the one place it provably
     * cannot act.
     */
    private static final int CARDS_EACH = 5;

    @Test public void atDepthOneItIsExactlyTheOldVote() {
        int checked = 0;
        for (int seed = 0; seed < 12; seed++) {
            Position position = position(seed, 8);
            AlphaMu.Ranking ranking = AlphaMu.rank(Contract.GRAND, seat(0), seat(0),
                    position.worlds, seat(0), List.of(), targets(8), 1);
            assertEquals("one score per card in hand",
                    position.myHand.size(), ranking.worldsWon().size());

            // The old vote, computed the long way round: one solver call per
            // world, counting the cards that still reach the target.
            for (Card card : position.myHand) {
                int votes = 0;
                for (WorldSampler.World world : position.worlds) {
                    for (DoubleDummySolver.Verdict verdict : DoubleDummySolver.movesReaching(
                            Contract.GRAND, SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN,
                            world.hands(), SkatAi.Seat.HUMAN, List.of(), TARGET)) {
                        if (verdict.card().equals(card) && verdict.reachesTarget()) votes++;
                    }
                }
                assertEquals("depth one must agree with the vote about " + card
                        + " at seed " + seed, votes, (int) ranking.worldsWon().get(card));
                checked++;
            }
        }
        assertTrue("the loop must actually have run: " + checked, checked >= 12 * 5);
    }

    @Test public void deeperNeverClaimsMoreThanShallower() {
        int strictlyLower = 0;
        for (int seed = 0; seed < 60; seed++) {
            Position position = position(seed, 16);
            double shallow = share(position, 1);
            if (shallow <= 0.001 || shallow >= 0.999) continue;   // nothing left to decide
            double deep = share(position, 2);
            assertTrue("depth two claimed more than depth one at seed " + seed
                    + ": " + deep + " > " + shallow, deep <= shallow + 1e-9);
            if (deep < shallow - 1e-9) strictlyLower++;
        }
        // If it were never lower, the search would be expanding nodes and
        // changing nothing, which is the other way this can be broken -- and is
        // how it was broken once: the root cut was written to stop as soon as
        // the claim *fell*, which is precisely when deepening is working, so
        // every search returned the depth-one answer.
        assertTrue("depth two never removed any optimism, so it is doing nothing",
                strictlyLower > 0);
    }

    @Test public void everyCardItNamesIsOneItHolds() {
        for (int seed = 0; seed < 8; seed++) {
            Position position = position(seed, 6);
            for (int depth = 1; depth <= 3; depth++) {
                AlphaMu.Ranking ranking = AlphaMu.rank(Contract.CLUBS, seat(0), seat(0),
                        position.worlds, seat(0), List.of(), targets(6), depth);
                assertEquals(position.myHand.size(), ranking.worldsWon().size());
                for (Card card : ranking.worldsWon().keySet()) {
                    assertTrue("scored a card it does not hold: " + card,
                            position.myHand.contains(card));
                }
                assertTrue(ranking.bestShare() >= 0 && ranking.bestShare() <= 1);
            }
        }
    }

    /**
     * The seat that is not the declarer reads the same search upside down, and
     * must still name a legal card. Not used by the player — the arena priced
     * the defensive ceiling at zero — but a search that threw here would be a
     * search with a latent sign error.
     */
    @Test public void itAlsoWorksFromADefendersSeat() {
        Position position = position(3, 6);
        AlphaMu.Ranking ranking = AlphaMu.rank(Contract.GRAND, seat(1), seat(0),
                position.worlds, seat(0), List.of(), targets(6), 2);
        assertEquals(position.myHand.size(), ranking.worldsWon().size());
    }

    /**
     * Where fusion is impossible, αµ must be a no-op — at every depth, for every
     * card.
     *
     * <p>One world cannot be fused with anything, and neither can sixteen copies
     * of one world: there is no pair of worlds for a committed card to be wrong
     * about. So the deeper search has to return the vote's numbers exactly. This
     * is the test that separates "correctly pessimistic" from "buggy": a mistake
     * in the Pareto bookkeeping — the cross product at an opponent node, the
     * worlds a card is unavailable in, the front pruning — would show up as
     * pessimism here, where none is possible.
     */
    @Test public void whereFusionIsImpossibleItChangesNothing() {
        int compared = 0;
        for (int seed = 0; seed < 25; seed++) {
            for (int copies : new int[] {1, 4, 16}) {
                List<WorldSampler.World> worlds = new ArrayList<>();
                WorldSampler.World one = position(seed, 1).worlds.get(0);
                for (int copy = 0; copy < copies; copy++) worlds.add(one);
                Position position = new Position(one.hands().get(0), worlds);

                Map<Card, Integer> vote = AlphaMu.rank(Contract.GRAND, seat(0), seat(0),
                        worlds, seat(0), List.of(), targets(copies), 1).worldsWon();
                for (int depth : new int[] {2, 3}) {
                    Map<Card, Integer> deep = AlphaMu.rank(Contract.GRAND, seat(0), seat(0),
                            worlds, seat(0), List.of(), targets(copies), depth).worldsWon();
                    for (Card card : vote.keySet()) {
                        assertEquals("depth " + depth + " with " + copies
                                        + " identical worlds moved " + card,
                                vote.get(card), deep.get(card));
                        compared++;
                    }
                }
                assertTrue(position.myHand.size() > 0);
            }
        }
        assertTrue("nothing was compared", compared > 500);
    }

    @Test public void aSingleWorldIsJustTheSolver() {
        Position position = position(5, 1);
        AlphaMu.Ranking ranking = AlphaMu.rank(Contract.GRAND, seat(0), seat(0),
                position.worlds, seat(0), List.of(), targets(1), 2);
        // One world cannot be fused with anything, so the claim is the truth:
        // either the declarer reaches the target against best defence or not.
        boolean reachable = DoubleDummySolver.declarerReaches(Contract.GRAND,
                SkatAi.Seat.HUMAN, position.worlds.get(0).hands(), SkatAi.Seat.HUMAN, TARGET);
        assertEquals(reachable ? 1.0 : 0.0, ranking.bestShare(), 1e-9);
    }

    private static double share(Position position, int depth) {
        return AlphaMu.rank(Contract.GRAND, seat(0), seat(0), position.worlds,
                seat(0), List.of(), targets(position.worlds.size()), depth).bestShare();
    }

    /** The same target in every world, which keeps these tests about the search. */
    private static int[] targets(int worlds) {
        int[] targets = new int[worlds];
        java.util.Arrays.fill(targets, TARGET);
        return targets;
    }

    private static AlphaMu.SeatIndex seat(int ordinal) {
        return () -> ordinal;
    }

    /** Ten cards in seat 0, and {@code count} guesses at how the rest lies. */
    private record Position(List<Card> myHand, List<WorldSampler.World> worlds) {}

    private static Position position(int seed, int count) {
        Random random = new Random(seed * 7919L + 13);
        List<Card> deck = new ArrayList<>(SkatDeck.ordered());
        Collections.shuffle(deck, random);
        List<Card> live = new ArrayList<>(deck.subList(0, CARDS_EACH * 3 + 2));
        List<Card> mine = List.copyOf(live.subList(0, CARDS_EACH));

        List<Card> rest = new ArrayList<>(live.subList(CARDS_EACH, live.size()));
        List<WorldSampler.World> worlds = new ArrayList<>(count);
        for (int world = 0; world < count; world++) {
            Collections.shuffle(rest, random);
            worlds.add(new WorldSampler.World(
                    List.of(new ArrayList<>(mine),
                            new ArrayList<>(rest.subList(0, CARDS_EACH)),
                            new ArrayList<>(rest.subList(CARDS_EACH, CARDS_EACH * 2))),
                    new ArrayList<>(rest.subList(CARDS_EACH * 2, CARDS_EACH * 2 + 2))));
        }
        return new Position(mine, worlds);
    }
}
