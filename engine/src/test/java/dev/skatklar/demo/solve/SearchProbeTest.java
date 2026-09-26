package dev.skatklar.demo.solve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link DoubleDummySolver.SearchProbe} sees the Java search as it is and changes nothing.
 *
 * <p>On random five- and six-card deals: the probe's node count equals the
 * solver's own, the root's subtree is the whole search, the subtrees of the
 * positions one trick down add up to no more than the whole, every
 * trick-start position it reports carries the tricks it has left, and the
 * value is the value without a probe. A probe cannot be installed while the
 * native engine answers, because it would see nothing.
 */
public class SearchProbeTest {

    private boolean original;

    @Before public void javaSearch() {
        original = DoubleDummySolver.nativeEnabled;
        DoubleDummySolver.nativeEnabled = false;
    }

    @After public void restore() {
        DoubleDummySolver.setSearchProbe(null);
        DoubleDummySolver.nativeEnabled = original;
    }

    static final class Recorder implements DoubleDummySolver.SearchProbe {
        long nodes;
        final long[] byTricks = new long[11];
        final List<long[]> starts = new ArrayList<>();
        @Override public void node(int tricksLeft) { nodes++; byTricks[tricksLeft]++; }
        final java.util.Set<Integer> solvers = new java.util.HashSet<>();
        final long[] lookups = new long[4];
        @Override public void trickStart(int solver, long position, int leader, int contract, int declarer,
                                         int tricksLeft, long subtreeNodes, int lookup) {
            starts.add(new long[] {tricksLeft, subtreeNodes, contract, declarer, leader, position, lookup});
            solvers.add(solver);
            lookups[lookup]++;
        }
    }

    @Test public void theProbeCountsTheSearchAndChangesNothing() {
        Random random = new Random(20260926L);
        Contract[] contracts = {Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS, Contract.GRAND};
        long hits = 0, bounds = 0, decided = 0, absent = 0;
        for (int deal = 0; deal < 40; deal++) {
            Contract contract = contracts[deal % contracts.length];
            int size = 5 + deal % 2;
            List<Card> deck = new ArrayList<>(SkatDeck.ordered());
            Collections.shuffle(deck, random);
            List<List<Card>> hands = new ArrayList<>();
            for (int seat = 0; seat < 3; seat++) hands.add(new ArrayList<>(deck.subList(seat * size, seat * size + size)));
            SkatAi.Seat declarer = SkatAi.Seat.values()[deal % 3];
            SkatAi.Seat leader = SkatAi.Seat.values()[(deal / 3) % 3];

            DoubleDummySolver.setSearchProbe(null);
            DoubleDummySolver.Result plain = DoubleDummySolver.solve(contract, declarer, hands, leader);
            Recorder recorder = new Recorder();
            DoubleDummySolver.setSearchProbe(recorder);
            DoubleDummySolver.Result probed = DoubleDummySolver.solve(contract, declarer, hands, leader);
            DoubleDummySolver.setSearchProbe(null);

            assertEquals("deal " + deal + ": the value", plain.declarerPoints(), probed.declarerPoints());
            assertEquals("deal " + deal + ": the nodes", plain.visitedNodes(), probed.visitedNodes());
            assertEquals("deal " + deal + ": the probe saw every node", probed.visitedNodes(), recorder.nodes);
            long[] root = recorder.starts.get(recorder.starts.size() - 1);
            assertEquals("the root is reported last, with all its tricks", size, root[0]);
            assertEquals("the root's subtree is the whole search", probed.visitedNodes(), root[1]);
            assertEquals(contract.ordinal(), root[2]);
            assertEquals(declarer.ordinal(), root[3]);
            assertEquals(leader.ordinal(), root[4]);
            long oneDown = 0;
            for (long[] start : recorder.starts) {
                assertTrue("tricks left in range", start[0] >= 1 && start[0] <= size);
                assertTrue("a subtree holds at least its own node", start[1] >= 1);
                if (start[0] == size - 1) oneDown += start[1];
            }
            assertTrue("the subtrees one trick down fit inside the whole", oneDown < probed.visitedNodes());
            assertEquals("one solve, one solver", 1, recorder.solvers.size());
            for (long[] start : recorder.starts) {
                // What the table said decides what the node costs: settled by the
                // points, or answered by the table, it is one node and no more.
                if (start[6] == DoubleDummySolver.SearchProbe.LOOKUP_DECIDED
                        || start[6] == DoubleDummySolver.SearchProbe.LOOKUP_HIT) {
                    assertEquals("a settled or answered position costs one node", 1, start[1]);
                } else {
                    assertTrue("a position the table could not answer is searched below", start[1] > 1);
                }
            }
            hits += recorder.lookups[DoubleDummySolver.SearchProbe.LOOKUP_HIT];
            bounds += recorder.lookups[DoubleDummySolver.SearchProbe.LOOKUP_BOUND];
            decided += recorder.lookups[DoubleDummySolver.SearchProbe.LOOKUP_DECIDED];
            absent += recorder.lookups[DoubleDummySolver.SearchProbe.LOOKUP_ABSENT];
            assertEquals("nothing is counted above the deal's size", 0, recorder.byTricks[Math.min(10, size + 1)]);
        }
        // Every kind of arrival happens on these deals, so none of the checks above is vacuous.
        assertTrue("hits " + hits, hits > 0);
        assertTrue("bounds " + bounds, bounds > 0);
        assertTrue("decided " + decided, decided > 0);
        assertTrue("absent " + absent, absent > 0);
    }

    @Test public void aProbeNeedsTheJavaSearch() {
        DoubleDummySolver.nativeEnabled = true;
        try {
            DoubleDummySolver.setSearchProbe(new Recorder());
            fail("a probe was installed while the native engine answers");
        } catch (IllegalStateException expected) {
            // what it should do
        }
    }
}
