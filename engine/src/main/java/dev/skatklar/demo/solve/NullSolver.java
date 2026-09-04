package dev.skatklar.demo.solve;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Perfect-information search for Null, where the objective is not points.
 *
 * <p>A Null declarer wins by taking <em>no trick at all</em>. That is a different
 * game from the one {@link DoubleDummySolver} solves, not a harder version of it:
 * the value of a position is a single bit, the declarer is the side trying to
 * <em>lose</em> every trick, and the moment it wins one the game is over. A
 * points-based search asked about Null does not merely answer badly, it answers
 * the opposite question — the card that takes the most points is usually the
 * worst card there is.
 *
 * <p>Being a boolean game makes it far cheaper than a suit game. There is no
 * window to widen and no value to bracket: every branch either survives or does
 * not, the declarer needs one surviving move and the defence needs one killing
 * move, and both cut immediately when they find it. A full ten-card deal solves
 * in about a millisecond.
 *
 * <p>The two defenders are modelled as one side, as everywhere else under perfect
 * information: with nothing hidden there is nothing to signal, so their best
 * joint play is a single strategy.
 */
public final class NullSolver {

    private final ContractTables tables = ContractTables.of(Contract.NULL);
    private final int declarerSeat;
    private final int[] hands = new int[3];
    private final int[] classMask = new int[5];
    private final Map<Key, Boolean> transpositions = new HashMap<>();

    private long visitedNodes;

    private NullSolver(int declarerSeat) {
        this.declarerSeat = declarerSeat;
        for (int followClass = 0; followClass < 5; followClass++) {
            classMask[followClass] = tables.classMask(followClass);
        }
    }

    /** Whether the declarer can still avoid every remaining trick. */
    public static boolean declarerSurvives(SkatAi.Seat declarer,
                                           List<? extends Collection<Card>> hands,
                                           SkatAi.Seat leader) {
        NullSolver solver = prepare(declarer, hands);
        return solver.survives(leader.ordinal(), leader.ordinal(), 0, 0);
    }

    /**
     * For every legal card, whether the declarer still survives after playing it.
     *
     * <p>The shape a determinized search needs: a verdict per move, so the votes
     * of many sampled worlds can be added up. Mirrors
     * {@link DoubleDummySolver#movesReaching}, with survival in place of a
     * points threshold.
     */
    public static List<Verdict> movesSurviving(SkatAi.Seat declarer, SkatAi.Seat toPlay,
                                               List<? extends Collection<Card>> remaining,
                                               SkatAi.Seat leader, List<Card> trickSoFar) {
        if (trickSoFar.size() > 2) {
            throw new IllegalArgumentException("A trick holds at most three cards");
        }
        if ((leader.ordinal() + trickSoFar.size()) % 3 != toPlay.ordinal()) {
            throw new IllegalArgumentException(
                    "It is not " + toPlay + "'s turn after " + trickSoFar.size() + " cards");
        }
        NullSolver solver = prepare(declarer, remaining);
        int trickCards = 0;
        for (int slot = 0; slot < trickSoFar.size(); slot++) {
            trickCards |= ContractTables.index(trickSoFar.get(slot)) << (8 * slot);
        }
        List<Verdict> verdicts = new ArrayList<>();
        for (int card : solver.orderedMoves(toPlay.ordinal(), trickCards, trickSoFar.size())) {
            solver.hands[toPlay.ordinal()] &= ~(1 << card);
            boolean survives = solver.childSurvives(toPlay.ordinal(), leader.ordinal(),
                    trickCards, trickSoFar.size(), card);
            solver.hands[toPlay.ordinal()] |= 1 << card;
            verdicts.add(new Verdict(ContractTables.card(card), survives));
        }
        return verdicts;
    }

    /** One card, and whether the Null declarer still gets through after it. */
    public record Verdict(Card card, boolean declarerSurvives) {}

    private static NullSolver prepare(SkatAi.Seat declarer,
                                      List<? extends Collection<Card>> hands) {
        NullSolver solver = new NullSolver(declarer.ordinal());
        for (int seat = 0; seat < 3; seat++) {
            int mask = 0;
            for (Card card : hands.get(seat)) mask |= 1 << ContractTables.index(card);
            solver.hands[seat] = mask;
        }
        return solver;
    }

    /**
     * Boolean minimax: the declarer needs one move that survives, the defence
     * needs one that does not.
     *
     * <p>No alpha-beta window, because there is nothing to bound — short-circuit
     * evaluation is already the whole of it.
     */
    private boolean survives(int toPlay, int leader, int trickCards, int trickSize) {
        visitedNodes++;
        if (hands[0] == 0 && hands[1] == 0 && hands[2] == 0) return true;

        boolean atTrickStart = trickSize == 0;
        Key key = null;
        if (atTrickStart) {
            key = new Key(hands[0], hands[1], hands[2], leader);
            Boolean cached = transpositions.get(key);
            if (cached != null) return cached;
        }

        boolean declarerToPlay = toPlay == declarerSeat;
        boolean result = !declarerToPlay;   // declarer: false until a move survives
        for (int card : orderedMoves(toPlay, trickCards, trickSize)) {
            hands[toPlay] &= ~(1 << card);
            boolean value = childSurvives(toPlay, leader, trickCards, trickSize, card);
            hands[toPlay] |= 1 << card;
            if (declarerToPlay == value) {
                result = value;
                break;
            }
        }
        if (atTrickStart) transpositions.put(key, result);
        return result;
    }

    /** The value of playing {@code card}, settling the trick when it completes it. */
    private boolean childSurvives(int toPlay, int leader, int trickCards, int trickSize,
                                  int card) {
        int nextTrick = trickCards | (card << (8 * trickSize));
        if (trickSize < 2) {
            return survives((toPlay + 1) % 3, leader, nextTrick, trickSize + 1);
        }
        int led = nextTrick & 0xFF;
        int second = (nextTrick >>> 8) & 0xFF;
        int winnerCard = tables.trickWinner(led, second, card);
        int winnerSeat = (leader + slotOf(nextTrick, winnerCard)) % 3;
        // One trick is the whole game. Nothing after it can matter, which is why
        // this search is cheap where the points search is not.
        if (winnerSeat == declarerSeat) return false;
        return survives(winnerSeat, winnerSeat, 0, 0);
    }

    /**
     * Legal cards, weakest first.
     *
     * <p>The reverse of the points solver's ordering, and for the same reason it
     * had one: the move most likely to decide the node goes first. Here both
     * sides want low cards — the declarer to duck, the defence to leave the
     * declarer holding the trick — so the cheapest card refutes soonest.
     */
    private int[] orderedMoves(int seat, int trickCards, int trickSize) {
        int playable = hands[seat];
        if (trickSize > 0) {
            int following = playable & classMask[tables.followClass(trickCards & 0xFF)];
            if (following != 0) playable = following;
        }
        int count = Integer.bitCount(playable);
        int[] moves = new int[count];
        int at = 0;
        for (int rest = playable; rest != 0; ) {
            int bit = rest & -rest;
            moves[at++] = Integer.numberOfTrailingZeros(bit);
            rest ^= bit;
        }
        for (int i = 1; i < count; i++) {
            int value = moves[i];
            int j = i - 1;
            while (j >= 0 && tables.strength(moves[j]) > tables.strength(value)) {
                moves[j + 1] = moves[j];
                j--;
            }
            moves[j + 1] = value;
        }
        return moves;
    }

    private static int slotOf(int trickCards, int card) {
        for (int slot = 0; slot < 3; slot++) {
            if (((trickCards >>> (8 * slot)) & 0xFF) == card) return slot;
        }
        throw new IllegalStateException("Winning card is not in the trick");
    }

    /**
     * All three hands plus the leader — the same reasoning as in the points
     * solver, where keying on two of them silently merged unrelated positions.
     * Nothing else is needed: a position's value here does not depend on how it
     * was reached, because any earlier trick taken by the declarer would have
     * ended the search.
     */
    private record Key(int first, int second, int third, int leader) {}

    // ---------------------------------------------------------------- testing

    /** Plain boolean minimax, no table and no ordering. The reference. */
    public static boolean survivesExhaustively(SkatAi.Seat declarer,
                                               List<? extends Collection<Card>> hands,
                                               SkatAi.Seat leader) {
        return prepare(declarer, hands).brute(leader.ordinal(), leader.ordinal(), 0, 0);
    }

    private boolean brute(int toPlay, int leader, int trickCards, int trickSize) {
        visitedNodes++;
        if (hands[0] == 0 && hands[1] == 0 && hands[2] == 0) return true;
        boolean declarerToPlay = toPlay == declarerSeat;
        boolean result = !declarerToPlay;
        for (int card : orderedMoves(toPlay, trickCards, trickSize)) {
            hands[toPlay] &= ~(1 << card);
            int nextTrick = trickCards | (card << (8 * trickSize));
            boolean value;
            if (trickSize < 2) {
                value = brute((toPlay + 1) % 3, leader, nextTrick, trickSize + 1);
            } else {
                int led = nextTrick & 0xFF;
                int second = (nextTrick >>> 8) & 0xFF;
                int winnerSeat = (leader
                        + slotOf(nextTrick, tables.trickWinner(led, second, card))) % 3;
                value = winnerSeat != declarerSeat && brute(winnerSeat, winnerSeat, 0, 0);
            }
            hands[toPlay] |= 1 << card;
            if (declarerToPlay) result |= value;
            else result &= value;
        }
        return result;
    }

    public long visitedNodes() { return visitedNodes; }
}
