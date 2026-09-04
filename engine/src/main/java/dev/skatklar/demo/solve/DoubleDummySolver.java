package dev.skatklar.demo.solve;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Perfect-information ("double dummy") search over a Skat deal.
 *
 * <p>Given every card in every hand, it returns the card points the declarer can
 * still take with optimal play by all three. It is not a player: it cheats by
 * construction. Two things are built on it.
 *
 * <ul>
 *   <li>A <b>reference opponent and a par baseline</b> for the arena. Every other
 *       player's strength then reads as a measured gap below a known ceiling,
 *       instead of only as a comparison against other mediocre programs.</li>
 *   <li>The <b>engine inside an honest search player</b>. That player never sees
 *       the hidden cards: it samples deals consistent with what it legally knows,
 *       solves each sampled deal here, and aggregates. Every individual solve is
 *       omniscient about a hypothesis the player invented itself.</li>
 * </ul>
 *
 * <p>The two defenders are modelled as one minimising side, which is the standard
 * and correct treatment under perfect information -- with nothing hidden there is
 * nothing for them to signal, so their best joint play is a single strategy.
 */
public final class DoubleDummySolver implements AutoCloseable {

    /** Total card points in the pack; also the widest possible search window. */
    public static final int TOTAL_POINTS = 120;

    private final ContractTables tables;
    /**
     * The contract as its enum ordinal, which is what the native engine speaks.
     *
     * <p>Kept beside {@link #tables} rather than derived from it because the
     * tables are shared per contract and deliberately do not remember which one
     * they were built for.
     */
    private final int contractOrdinal;
    private final int declarerSeat;
    private final int[] hands = new int[3];
    private final int[] classMask = new int[5];
    /**
     * The transposition table, flat.
     *
     * <p>It was a {@code HashMap<Key, Entry>} of two records. That is 240,000
     * entries and 480,000 short-lived objects for one ten-card solve -- measured
     * at 5.94 MB allocated per solve -- and every probe was a hash, a node chase
     * and a record {@code equals}. A hanging phone sampled in the field landed
     * in exactly that: {@code HashMap.getNode} -> {@code Key.equals}.
     *
     * <p>Now two {@code long}s per slot in one array, four slots to a bucket so a
     * bucket is one 64-byte cache line, and nothing allocated per node at all.
     * Semantics are unchanged in the only way that matters: an entry that
     * matches still answers exactly what it answered before, and the table
     * remains a pure cache, so losing one costs a re-search and never an answer.
     * {@link #solveExhaustively} is what holds that promise honest.
     */
    private long[] table;
    private int bucketMask;
    private int tableBits;
    private int storedEntries;

    private long visitedNodes;
    private boolean useTranspositions = true;

    /**
     * The native solver this one delegates to, or zero.
     *
     * <p>Only {@link #reusableFor} ever sets it. Every other entry point here is
     * static and single-shot, and calls the library directly rather than through
     * an object that would have to be closed.
     */
    private long nativeHandle;

    /**
     * Whether the native engine answers the questions it can.
     *
     * <p>Decided once, when this class is initialised, and then left alone. It
     * is not final and not private for one reason: {@code SolverParityTest} has
     * to put the same position to both engines inside one JVM, and a decision
     * baked into a static final cannot be put back. Nothing in production writes
     * to it -- to run on the Java search, start the process with
     * {@code -Dskatklar.solver=java}.
     */
    static boolean nativeEnabled = NativeSolver.available();

    /**
     * When this search must give up, as a {@link System#nanoTime} reading.
     *
     * <p>Unset by default, and deliberately so. A deadline makes the answer
     * depend on the machine and on what else it happened to be doing, which is
     * exactly what the arena and the server must never have -- a match there has
     * to replay from its seed. It exists for the app, where a hand nobody can
     * evaluate in time is a frozen phone, and where a weaker answer that arrives
     * is worth more than a better one that does not.
     */
    private boolean bounded;
    private long deadlineNanos;

    /**
     * Nodes between two clock readings, minus one, as a mask.
     *
     * <p>{@code System.nanoTime} costs tens of nanoseconds and a node costs
     * around a hundred, so reading it at every node would be a double-digit tax.
     * Once per thousand puts the overhead below a tenth of a percent and still
     * bounds the overrun at well under a millisecond.
     */
    private static final long DEADLINE_CHECK_MASK = 1023L;

    /**
     * Thrown out of a search that ran out of time, and caught at the entry point.
     *
     * <p>Stackless and private: this is control flow across thirty frames of
     * recursion rather than a fault, and unwinding leaves {@link #hands} with the
     * move in progress still removed. That is why only the single-shot static
     * entry points may set a deadline -- each builds a solver, catches this and
     * throws the solver away. A solver that keeps its table across questions must
     * never be given one.
     */
    private static final class Expired extends RuntimeException {
        private static final Expired INSTANCE = new Expired();
        private Expired() { super(null, null, false, false); }
    }

    private DoubleDummySolver(Contract contract, int declarerSeat) {
        // Null is a different game, not a different trump: its objective is
        // binary ("the declarer takes no trick at all") rather than a point
        // count, so every window, cut-off and transposition value below means
        // nothing for it. A Null search is its own piece of work; until then,
        // refusing is the honest answer and callers fall back.
        if (contract.isNull()) {
            throw new IllegalArgumentException(
                    "The double-dummy solver does not model Null games yet");
        }
        this.tables = ContractTables.of(contract);
        this.contractOrdinal = contract.ordinal();
        this.declarerSeat = declarerSeat;
        for (int followClass = 0; followClass < 5; followClass++) {
            classMask[followClass] = tables.classMask(followClass);
        }
    }

    /**
     * Card points the declarer takes from the remaining hands, with optimal play.
     *
     * <p>Excludes anything already won and anything in the skat: this is the value
     * of what is left to play. Callers add the banked points themselves.
     *
     * @param hands   the three hands as card collections, indexed by seat ordinal;
     *                all three must hold the same number of cards
     * @param leader  the seat to lead the next trick
     */
    public static Result solve(Contract contract, SkatAi.Seat declarer,
                               List<? extends Collection<Card>> hands, SkatAi.Seat leader) {
        if (nativeEnabled) {
            int[] masks = masks(hands);
            long[] out = new long[3];
            if (NativeSolver.solve(contract.ordinal(), declarer.ordinal(),
                    masks[0], masks[1], masks[2], leader.ordinal(), out) == NativeSolver.OK) {
                return new Result((int) out[0], out[1], (int) out[2]);
            }
        }
        return solve(contract, declarer, hands, leader, true);
    }

    /**
     * The three hands as bit masks, which is the only shape the native engine
     * takes and the shape the Java search uses internally anyway.
     */
    private static int[] masks(List<? extends Collection<Card>> hands) {
        int[] masks = new int[3];
        for (int seat = 0; seat < 3; seat++) {
            int mask = 0;
            for (Card card : hands.get(seat)) mask |= 1 << ContractTables.index(card);
            masks[seat] = mask;
        }
        return masks;
    }

    /** Package-private so the tests can pin a disagreement on pruning or on the table. */
    static Result solve(Contract contract, SkatAi.Seat declarer,
                        List<? extends Collection<Card>> hands, SkatAi.Seat leader,
                        boolean useTranspositions) {
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarer.ordinal());
        solver.useTranspositions = useTranspositions;
        int size = -1;
        for (int seat = 0; seat < 3; seat++) {
            int mask = 0;
            for (Card card : hands.get(seat)) mask |= 1 << ContractTables.index(card);
            solver.hands[seat] = mask;
            int count = Integer.bitCount(mask);
            if (size < 0) size = count;
            else if (size != count) {
                throw new IllegalArgumentException("All three hands must be the same size");
            }
        }
        int value = solver.search(leader.ordinal(), leader.ordinal(), 0, 0, 0, TOTAL_POINTS);
        return new Result(value, solver.visitedNodes, solver.storedEntries);
    }

    /**
     * What a solve found, plus what it cost.
     *
     * @param transpositions entries live in the table at the end. The table
     *                       replaces rather than chains, so this is what
     *                       survived, not everything that was ever stored.
     */
    public record Result(int declarerPoints, long visitedNodes, int transpositions) {}

    /**
     * The best card to play from a position, with the value it defends.
     *
     * <p>Unlike {@link #solve}, this accepts a position in the middle of a trick,
     * so the hands need not be the same size: a seat that has already played to
     * the current trick simply holds one card fewer.
     *
     * @param remaining   the three hands with the cards already played removed
     * @param leader      the seat that led the current trick
     * @param trickSoFar  cards played to the current trick, in play order from
     *                    the leader; empty when {@code toPlay} is on lead
     */
    public static Choice bestCard(Contract contract, SkatAi.Seat declarer, SkatAi.Seat toPlay,
                                  List<? extends Collection<Card>> remaining,
                                  SkatAi.Seat leader, List<Card> trickSoFar) {
        if (nativeEnabled) {
            Choice choice = nativeChoice(contract, declarer, toPlay, remaining, leader,
                    trickSoFar, Integer.MIN_VALUE);
            if (choice != null) return choice;
        }
        DoubleDummySolver solver = prepare(contract, declarer, toPlay, remaining, leader, trickSoFar);
        return solver.chooseAtRoot(toPlay.ordinal(), leader.ordinal(),
                pack(trickSoFar), trickSoFar.size());
    }

    private static DoubleDummySolver prepare(Contract contract, SkatAi.Seat declarer,
                                             SkatAi.Seat toPlay,
                                             List<? extends Collection<Card>> remaining,
                                             SkatAi.Seat leader, List<Card> trickSoFar) {
        if (trickSoFar.size() > 2) {
            throw new IllegalArgumentException("A trick holds at most three cards");
        }
        if ((leader.ordinal() + trickSoFar.size()) % 3 != toPlay.ordinal()) {
            throw new IllegalArgumentException(
                    "It is not " + toPlay + "'s turn after " + trickSoFar.size() + " cards");
        }
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarer.ordinal());
        solver.load(remaining);
        return solver;
    }

    private static int pack(List<Card> trickSoFar) {
        int packed = 0;
        for (int slot = 0; slot < trickSoFar.size(); slot++) {
            packed |= ContractTables.index(trickSoFar.get(slot)) << (8 * slot);
        }
        return packed;
    }

    /**
     * The best card for the <em>result</em>, which is what a Skat score sheet
     * pays for, rather than for the last card point.
     *
     * <p>Skat scores bands, not points: 61 wins, 90 is Schneider, 31 avoids being
     * Schneidered. Two lines that both end at 75 are worth exactly the same, and
     * a search that computes the difference between them has spent most of its
     * time on nothing. This method asks only the questions that pay -- at most
     * three null-window searches sharing one transposition table -- and is
     * roughly an order of magnitude cheaper than {@link #bestCard} on a full
     * deal.
     *
     * <p>It does not distinguish inside a band, so it will not go out of its way
     * for Schwarz. Anything that matters to the scoresheet other than that is
     * covered.
     *
     * @param declarerPointsSoFar card points the declarer has already banked,
     *                            including the skat
     */
    public static Choice bestCardForResult(Contract contract, SkatAi.Seat declarer,
                                           SkatAi.Seat toPlay,
                                           List<? extends Collection<Card>> remaining,
                                           SkatAi.Seat leader, List<Card> trickSoFar,
                                           int declarerPointsSoFar) {
        if (nativeEnabled) {
            Choice choice = nativeChoice(contract, declarer, toPlay, remaining, leader,
                    trickSoFar, declarerPointsSoFar);
            if (choice != null) return choice;
        }
        DoubleDummySolver solver = prepare(contract, declarer, toPlay, remaining, leader, trickSoFar);
        int trickCards = pack(trickSoFar);
        boolean declarerToPlay = toPlay == declarer;
        // The declarer tries for the best band it can still hold; the defenders
        // for the worst it cannot escape. Both walk the same thresholds, from
        // opposite ends, and stop at the first one the position confirms.
        //
        // The declarer does not walk down to 31. Seeger-Fabian charges a lost
        // game at a flat 50 whether or not it was Schneider, so playing safe for
        // 31 buys nothing on the score sheet, while taking every point available
        // leaves a fallible defence room to hand the game back.
        int[] thresholds = declarerToPlay
                ? new int[] {90, 61}
                : new int[] {31, 61, 90};
        Choice choice = null;
        for (int threshold : thresholds) {
            // A band already banked cannot be lost, so there is nothing to ask
            // about it: the question degenerates to "is there another point in
            // it", which is one node deep and keeps a legal move coming back.
            int needed = Math.max(1, threshold - declarerPointsSoFar);
            choice = solver.rootChoice(toPlay.ordinal(), leader.ordinal(), trickCards,
                    trickSoFar.size(), needed - 1, needed);
            boolean confirmed = declarerToPlay
                    ? choice.declarerPoints() >= needed
                    : choice.declarerPoints() < needed;
            if (confirmed) return choice;
        }
        // Every band failed: the declarer cannot reach even 31, or the defence
        // cannot hold 90. The band is settled either way, so fall back to the
        // exact optimum inside it -- points still decide the game value, and the
        // transposition table is warm from the questions just asked, so this
        // costs a fraction of what the same search would cost cold.
        return solver.chooseAtRoot(toPlay.ordinal(), leader.ordinal(),
                trickCards, trickSoFar.size());
    }

    /**
     * For every legal card, whether the declarer still reaches {@code target}
     * after playing it.
     *
     * <p>This is the shape a determinized search needs: not one best move, but a
     * verdict per move, so that the votes of many sampled worlds can be added up.
     * Every question is a null window, and all of them share one transposition
     * table, which is what makes evaluating ten cards cost noticeably less than
     * ten separate searches.
     *
     * @return the cards in the order they were searched, each with its verdict
     */
    public static List<Verdict> movesReaching(Contract contract, SkatAi.Seat declarer,
                                              SkatAi.Seat toPlay,
                                              List<? extends Collection<Card>> remaining,
                                              SkatAi.Seat leader, List<Card> trickSoFar,
                                              int target) {
        if (nativeEnabled) {
            List<Verdict> native_ = nativeMovesReaching(contract, declarer, toPlay,
                    remaining, leader, trickSoFar, target);
            if (native_ != null) return native_;
        }
        DoubleDummySolver solver = prepare(contract, declarer, toPlay, remaining, leader, trickSoFar);
        int trickCards = pack(trickSoFar);
        int alpha = Math.max(0, target - 1);
        int beta = Math.max(1, target);
        List<Verdict> verdicts = new ArrayList<>();
        for (int card : solver.orderedMoves(toPlay.ordinal(), trickCards, trickSoFar.size())) {
            solver.hands[toPlay.ordinal()] &= ~(1 << card);
            int value = solver.childValue(toPlay.ordinal(), leader.ordinal(), trickCards,
                    trickSoFar.size(), card, alpha, beta);
            solver.hands[toPlay.ordinal()] |= 1 << card;
            verdicts.add(new Verdict(ContractTables.card(card), value >= target, value));
        }
        return verdicts;
    }

    /** One card, and whether playing it still gets the declarer to the target. */
    public record Verdict(Card card, boolean reachesTarget, int bound) {}

    /** A card, the declarer points it leads to, and what finding it cost. */
    public record Choice(Card card, int declarerPoints, long visitedNodes) {}

    /**
     * Whether the declarer can still take at least {@code target} card points.
     *
     * <p>A null-window search: it answers the yes/no question the bidder actually
     * has and refuses to compute the exact value, which is a large saving. Use it
     * wherever the margin does not matter -- "is this contract makeable" is the
     * common case, and asking {@link #solve} for a number and then comparing it
     * costs several times as much.
     */
    public static boolean declarerReaches(Contract contract, SkatAi.Seat declarer,
                                          List<? extends Collection<Card>> hands,
                                          SkatAi.Seat leader, int target) {
        if (target <= 0) return true;
        if (target > TOTAL_POINTS) return false;
        if (nativeEnabled) {
            int[] masks = masks(hands);
            int answer = NativeSolver.reaches(contract.ordinal(), declarer.ordinal(),
                    masks[0], masks[1], masks[2], leader.ordinal(), leader.ordinal(),
                    0, 0, target);
            if (answer >= 0) return answer != 0;
        }
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarer.ordinal());
        solver.load(hands);
        return solver.search(leader.ordinal(), leader.ordinal(), 0, 0,
                target - 1, target) >= target;
    }

    /**
     * The same question, abandoned rather than answered if it takes too long.
     *
     * <p>The cost of a ten-card solve is not a number, it is a distribution. A
     * hand that is clearly making or clearly failing is cut off by the null
     * window almost at once; a hand sitting on the line searches most of the
     * tree. Two orders of magnitude separate them on the same code and the same
     * card count, and there is no way to tell which one has been handed over
     * without starting it. So a caller who cannot afford the tail says how long
     * it will wait and takes a smaller sample instead of a later answer.
     *
     * <p>Only for callers with a person waiting. Everything that has to be
     * reproducible -- the arena, the server, every test -- asks
     * {@link #declarerReaches} and waits.
     *
     * @param deadlineNanos a {@link System#nanoTime} reading to stop at
     * @return whether the declarer reaches {@code target}, or {@code null} if
     *         the deadline passed before the search could say
     */
    public static Boolean declarerReachesBefore(Contract contract, SkatAi.Seat declarer,
                                                List<? extends Collection<Card>> hands,
                                                SkatAi.Seat leader, int target,
                                                long deadlineNanos) {
        if (target <= 0) return Boolean.TRUE;
        if (target > TOTAL_POINTS) return Boolean.FALSE;
        if (nativeEnabled) {
            // A budget rather than the deadline itself. The two engines do not
            // share a clock origin and are not going to be made to: Java hands
            // over what is left of its own deadline and the library starts its
            // own steady clock from there.
            long budget = deadlineNanos - System.nanoTime();
            if (budget <= 0) return null;
            int[] masks = masks(hands);
            int answer = NativeSolver.reachesWithin(contract.ordinal(), declarer.ordinal(),
                    masks[0], masks[1], masks[2], leader.ordinal(), leader.ordinal(),
                    0, 0, target, budget);
            if (answer == NativeSolver.EXPIRED) return null;
            if (answer >= 0) return answer != 0;
        }
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarer.ordinal());
        solver.load(hands);
        solver.bounded = true;
        solver.deadlineNanos = deadlineNanos;
        try {
            return solver.search(leader.ordinal(), leader.ordinal(), 0, 0,
                    target - 1, target) >= target;
        } catch (Expired gaveUp) {
            return null;
        }
    }

    /**
     * The same yes/no question, from a position in the middle of a trick and
     * with the hands already as bit masks.
     *
     * <p>Exists for {@link dev.skatklar.demo.search.AlphaMu}, which reaches its
     * leaves after a fixed number of its own moves rather than at a trick
     * boundary, and which holds thousands of positions per decision as masks
     * already. Going through {@code List<List<Card>>} would allocate three lists
     * and up to thirty {@code Card} objects per leaf, and the leaves are the
     * whole cost of that search.
     *
     * <p>Masks are the same 0..31 indices {@link ContractTables#index} produces,
     * one entry per seat ordinal, with everything already played removed.
     *
     * @param trickCards cards played to the current trick, packed one per byte
     *                   in play order from the leader; see the private search
     * @param target     points the declarer still needs, so the caller has
     *                   already subtracted whatever is banked
     */
    public static boolean declarerReaches(Contract contract, int declarerSeat, int toPlay,
                                          int[] handMasks, int leader, int trickCards,
                                          int trickSize, int target) {
        if (target <= 0) return true;
        if (target > TOTAL_POINTS) return false;
        if (nativeEnabled) {
            int answer = NativeSolver.reaches(contract.ordinal(), declarerSeat, handMasks[0],
                    handMasks[1], handMasks[2], toPlay, leader, trickCards, trickSize, target);
            if (answer >= 0) return answer != 0;
        }
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarerSeat);
        System.arraycopy(handMasks, 0, solver.hands, 0, 3);
        return solver.search(toPlay, leader, trickCards, trickSize, target - 1, target) >= target;
    }

    /**
     * A solver that keeps its transposition table between questions.
     *
     * <p>Every other entry point here is single-shot: it builds a solver, asks
     * one thing and throws the table away. That is right for a caller that asks
     * once, and wrong for {@link dev.skatklar.demo.search.AlphaMu}, which asks
     * hundreds of questions about positions that differ by a card or two and
     * would otherwise re-derive the same endgames from scratch every time. The
     * table is keyed on the three hands and the leader and its values are points
     * <em>still</em> to be won, so an entry stays true however the position was
     * reached — which is what makes it safe to keep.
     *
     * <p>One solver per world. Two different worlds are two different sets of
     * hands and share nothing worth caching, while one world's leaves overlap
     * heavily.
     *
     * <p>Not thread-safe, and not meant to be: it is a scratchpad for one search
     * on one thread.
     */
    public static DoubleDummySolver reusableFor(Contract contract, int declarerSeat) {
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarerSeat);
        if (nativeEnabled) {
            long handle = NativeSolver.createSolver(contract.ordinal(), declarerSeat);
            if (handle != 0L) solver.nativeHandle = NativeHandles.register(solver, handle);
        }
        return solver;
    }

    /**
     * Releases the native table this solver kept, if it had one.
     *
     * <p>Optional in the sense that forgetting it leaks nothing permanently --
     * {@link NativeHandles} frees a handle whose solver has been collected --
     * and worth calling anyway, because a garbage collector has no idea that
     * the sixteen kilobytes it can see are holding a megabyte it cannot.
     */
    @Override public void close() {
        long handle = nativeHandle;
        nativeHandle = 0L;
        if (handle != 0L) NativeHandles.release(handle);
    }

    /**
     * Whether the declarer still reaches {@code target}, reusing this solver's
     * table. See {@link #reusableFor}.
     */
    public boolean reaches(int toPlay, int[] handMasks, int leader, int trickCards,
                           int trickSize, int target) {
        if (target <= 0) return true;
        if (target > TOTAL_POINTS) return false;
        if (nativeHandle != 0L) {
            int answer = NativeSolver.solverReaches(nativeHandle, handMasks[0], handMasks[1],
                    handMasks[2], toPlay, leader, trickCards, trickSize, target);
            if (answer >= 0) return answer != 0;
        }
        System.arraycopy(handMasks, 0, hands, 0, 3);
        return search(toPlay, leader, trickCards, trickSize, target - 1, target) >= target;
    }

    private void load(List<? extends Collection<Card>> source) {
        for (int seat = 0; seat < 3; seat++) {
            int mask = 0;
            for (Card card : source.get(seat)) mask |= 1 << ContractTables.index(card);
            hands[seat] = mask;
        }
    }

    /**
     * Picks the move at the root, exactly, without ever paying for a full-window
     * search.
     *
     * <p>A plain alpha-beta over the whole 0..120 range costs about half a second
     * on a ten-card deal, which no player can afford thirty times a game. Asking
     * instead whether the declarer reaches a given number is roughly twenty times
     * cheaper, because the bound cut in {@link #search} decides most branches
     * without looking at them. So the value is bracketed by binary search over
     * such questions -- seven of them cover the whole range -- and every one warms
     * the same transposition table for the next.
     *
     * <p>The answer is identical to the full-window search. Only the cost differs.
     */
    private Choice chooseAtRoot(int toPlay, int leader, int trickCards, int trickSize) {
        int ceiling = pointsInHands() + trickPoints(trickCards, trickSize);
        int low = 0;
        int high = ceiling;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            int value = rootValue(toPlay, leader, trickCards, trickSize, mid - 1, mid);
            // Fail-soft: the null window returns a bound that is often tighter
            // than the question asked, which skips whole steps of the bracket.
            if (value >= mid) low = Math.max(mid, value);
            else high = Math.min(mid - 1, value);
        }
        return rootChoice(toPlay, leader, trickCards, trickSize, low - 1, low + 1);
    }

    private int rootValue(int toPlay, int leader, int trickCards, int trickSize,
                          int alpha, int beta) {
        return rootChoice(toPlay, leader, trickCards, trickSize, alpha, beta).declarerPoints();
    }

    /**
     * One ply that keeps the move instead of only its value.
     *
     * <p>Deliberately a separate loop from {@link #search} rather than a flag
     * inside it: the root is visited a handful of times and the inner nodes
     * millions, and the two do not need to pay for each other.
     */
    private Choice rootChoice(int toPlay, int leader, int trickCards, int trickSize,
                              int alpha, int beta) {
        boolean declarerToPlay = toPlay == declarerSeat;
        int best = declarerToPlay ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        int bestCard = -1;
        for (int card : orderedMoves(toPlay, trickCards, trickSize)) {
            hands[toPlay] &= ~(1 << card);
            int value = childValue(toPlay, leader, trickCards, trickSize, card, alpha, beta);
            hands[toPlay] |= 1 << card;
            boolean better = bestCard < 0 || (declarerToPlay ? value > best : value < best);
            if (better) {
                best = value;
                bestCard = card;
            }
            if (declarerToPlay) alpha = Math.max(alpha, best);
            else beta = Math.min(beta, best);
            if (alpha >= beta) break;
        }
        if (bestCard < 0) throw new IllegalStateException("No legal card to choose");
        return new Choice(ContractTables.card(bestCard), best, visitedNodes);
    }

    /** Points lying on the table in the current, unfinished trick. */
    private int trickPoints(int trickCards, int trickSize) {
        int total = 0;
        for (int slot = 0; slot < trickSize; slot++) {
            total += tables.points((trickCards >>> (8 * slot)) & 0xFF);
        }
        return total;
    }

    /** The value of playing {@code card}, settling the trick when it completes it. */
    private int childValue(int toPlay, int leader, int trickCards, int trickSize,
                           int card, int alpha, int beta) {
        int nextTrick = trickCards | (card << (8 * trickSize));
        if (trickSize < 2) {
            return search((toPlay + 1) % 3, leader, nextTrick, trickSize + 1, alpha, beta);
        }
        int led = nextTrick & 0xFF;
        int second = (nextTrick >>> 8) & 0xFF;
        int winnerCard = tables.trickWinner(led, second, card);
        int winnerSeat = (leader + slotOf(nextTrick, winnerCard)) % 3;
        int trickPoints = tables.points(led) + tables.points(second) + tables.points(card);
        int gained = winnerSeat == declarerSeat ? trickPoints : 0;
        return gained + search(winnerSeat, winnerSeat, 0, 0, alpha - gained, beta - gained);
    }

    /**
     * Negamax-shaped alpha-beta over declarer points.
     *
     * <p>Both bounds and the returned value are <em>points still to be won</em>,
     * so a position's value does not depend on the running score. That is what
     * makes the transposition table sound: the same distribution of remaining
     * cards is worth the same regardless of how the game reached it.
     *
     * @param trickCards the cards played so far in the current trick, packed one
     *                   per byte in play order starting at the leader
     */
    private int search(int toPlay, int leader, int trickCards, int trickSize,
                       int alpha, int beta) {
        visitedNodes++;
        // Subtraction rather than a comparison: nanoTime has no defined origin
        // and is documented to wrap, and the difference stays correct across a
        // wrap where `>=` does not.
        if (bounded && (visitedNodes & DEADLINE_CHECK_MASK) == 0
                && System.nanoTime() - deadlineNanos >= 0) {
            throw Expired.INSTANCE;
        }
        if (hands[0] == 0 && hands[1] == 0 && hands[2] == 0) return 0;

        if (trickSize == 0) {
            // Nothing left to play for. The declarer can never take more than the
            // points still in the hands, and never fewer than none, so a window
            // that already excludes that range is decided without searching. This
            // is what makes a null-window question ("does the declarer reach 61?")
            // an order of magnitude cheaper than asking for the exact value.
            int remaining = pointsInHands();
            if (remaining <= alpha) return remaining;
            if (beta <= 0) return 0;
        }

        boolean atTrickStart = trickSize == 0 && useTranspositions;
        long position = 0;
        int cardsLeft = 0;
        int preferredMove = -1;
        if (atTrickStart) {
            cardsLeft = Integer.bitCount(hands[toPlay]);
            ensureTable();
            position = position();
            int at = probe(position, leader);
            if (at >= 0) {
                long entry = table[at + 1];
                int cached = valueOf(entry);
                if (isExact(entry)) return cached;
                if (isLowerBound(entry) && cached >= beta) return cached;
                if (!isLowerBound(entry) && cached <= alpha) return cached;
                // Not usable as an answer, but the move that produced it is still
                // the best guess at what refutes here, and trying it first is
                // where most of the pruning comes from.
                preferredMove = moveOf(entry);
            }
        }

        int originalAlpha = alpha;
        int originalBeta = beta;
        boolean declarerToPlay = toPlay == declarerSeat;
        int best = declarerToPlay ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        int bestMove = -1;

        for (int card : preferFirst(orderedMoves(toPlay, trickCards, trickSize), preferredMove)) {
            hands[toPlay] &= ~(1 << card);
            // childValue shifts the window by whatever a settled trick already
            // scored, so the child still reasons purely about points that remain.
            int value = childValue(toPlay, leader, trickCards, trickSize, card, alpha, beta);
            hands[toPlay] |= 1 << card;

            if (declarerToPlay) {
                if (value > best) { best = value; bestMove = card; }
                if (best > alpha) alpha = best;
            } else {
                if (value < best) { best = value; bestMove = card; }
                if (best < beta) beta = best;
            }
            if (alpha >= beta) break;
        }

        if (atTrickStart) {
            boolean exact = best > originalAlpha && best < originalBeta;
            store(position, leader, best, exact, best >= originalBeta, bestMove, cardsLeft);
        }
        return best;
    }

    /** Total card points still held, and therefore still winnable. */
    private int pointsInHands() {
        int total = 0;
        for (int seat = 0; seat < 3; seat++) {
            for (int rest = hands[seat]; rest != 0; ) {
                int bit = rest & -rest;
                total += tables.points(Integer.numberOfTrailingZeros(bit));
                rest ^= bit;
            }
        }
        return total;
    }

    /** Moves with {@code first} moved to the front, if it is among them. */
    private static int[] preferFirst(int[] moves, int first) {
        if (first < 0 || moves.length < 2 || moves[0] == first) return moves;
        for (int i = 1; i < moves.length; i++) {
            if (moves[i] == first) {
                System.arraycopy(moves, 0, moves, 1, i);
                moves[0] = first;
                return moves;
            }
        }
        return moves;
    }

    /**
     * Legal cards, strongest first.
     *
     * <p>Ordering matters more than it looks: alpha-beta prunes on the first move
     * that refutes, and in a trick game the strongest card is usually it. On a
     * ten-card deal this is the difference between seconds and minutes.
     */
    private int[] orderedMoves(int seat, int trickCards, int trickSize) {
        int hand = hands[seat];
        int playable = hand;
        if (trickSize > 0) {
            int ledClass = tables.followClass(trickCards & 0xFF);
            int following = hand & classMask[ledClass];
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
        // Insertion sort by descending strength; count is at most ten.
        for (int i = 1; i < count; i++) {
            int value = moves[i];
            int j = i - 1;
            while (j >= 0 && tables.strength(moves[j]) < tables.strength(value)) {
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

    // ------------------------------------------------- the transposition table

    /** Slots per bucket. Four slots of two longs is exactly one cache line. */
    private static final int WAYS = 4;
    /** 256 buckets, 16 KB. Every table starts here and grows into what it needs. */
    private static final int MIN_TABLE_BITS = 8;
    /** 262,144 buckets, 16.8 MB. Only the exact-value question ever gets near it. */
    private static final int MAX_TABLE_BITS = 18;

    // The packed entry. Value is declarer points still to be won, so 0..120;
    // move is a card index 0..31 held as move+1 so that -1 packs as zero; and
    // cardsLeft is what makes a sensible victim choice possible below. Bit 63 is
    // set on every stored entry, which is what makes a zero word mean "empty"
    // without reserving a value anybody might legitimately store.
    private static final long ENTRY_PRESENT = 1L << 63;
    private static final int VALUE_SHIFT = 0;
    private static final int MOVE_SHIFT = 8;
    private static final int EXACT_SHIFT = 14;
    private static final int LOWER_BOUND_SHIFT = 15;
    private static final int LEADER_SHIFT = 16;
    private static final int CARDS_LEFT_SHIFT = 18;

    private static int valueOf(long entry) { return (int) ((entry >>> VALUE_SHIFT) & 0xFF); }
    private static int moveOf(long entry) { return (int) ((entry >>> MOVE_SHIFT) & 0x3F) - 1; }
    private static boolean isExact(long entry) { return (entry >>> EXACT_SHIFT & 1) != 0; }
    private static boolean isLowerBound(long entry) {
        return (entry >>> LOWER_BOUND_SHIFT & 1) != 0;
    }
    private static int leaderOf(long entry) { return (int) ((entry >>> LEADER_SHIFT) & 3); }
    private static int cardsLeftOf(long entry) {
        return (int) ((entry >>> CARDS_LEFT_SHIFT) & 0xF);
    }

    /**
     * The three hands as one long, which is the whole reason this fits.
     *
     * <p>Every one of the 32 card indices is in exactly one of four states --
     * held by a seat, or gone -- so two bits each is 64 bits and not one more.
     * That makes the key a <em>bijection</em> with the three masks rather than a
     * hash of them, so a match needs no verification and cannot be a collision.
     *
     * <p>All three hands really are needed, and packing all three is not
     * redundancy: two disjoint masks do not determine the third, because which
     * cards seat 2 has already played is independent of what the other two still
     * hold. Keying on two of them silently merges unrelated positions -- it made
     * the solver disagree with plain minimax on roughly a fifth of five-card
     * deals.
     *
     * <p>The leader does not fit in the remaining nought bits and lives in the
     * entry word instead, where the probe checks it.
     */
    private long position() {
        long first = spread(hands[0]);
        long second = spread(hands[1]);
        long third = spread(hands[2]);
        return first | (second << 1) | third | (third << 1);
    }

    /** Each of 32 bits into its own 2-bit lane, five shifts and no loop. */
    private static long spread(int mask) {
        long bits = mask & 0xFFFFFFFFL;
        bits = (bits | (bits << 16)) & 0x0000FFFF0000FFFFL;
        bits = (bits | (bits << 8)) & 0x00FF00FF00FF00FFL;
        bits = (bits | (bits << 4)) & 0x0F0F0F0F0F0F0F0FL;
        bits = (bits | (bits << 2)) & 0x3333333333333333L;
        bits = (bits | (bits << 1)) & 0x5555555555555555L;
        return bits;
    }

    /**
     * Grows on demand rather than guessing, because guessing was measurably
     * worse.
     *
     * <p>The right size is not knowable in advance and is not what the card
     * count suggests. The same ten-card deal costs 42,000 nodes or 8.7 million
     * depending on how near the answer is to the window; the null-window
     * question that the app and the server actually ask stores about 11,000
     * entries, while the exact-value one stores thirty times that. Sizing from
     * the cards was tried first and cost 11.2 MB a solve to hold 11,000 entries
     * -- and would have handed AlphaMu's thirty-two live solvers one of those
     * each.
     *
     * <p>So every table starts at 16 KB and doubles when three quarters full.
     * Doubling carries the old entries over, so the cost is a handful of
     * re-inserts across a search, and it lands within a factor of two of what
     * the question needed rather than within a factor of a thousand.
     */
    private void ensureTable() {
        if (table == null) {
            allocate(MIN_TABLE_BITS);
            return;
        }
        if (tableBits < MAX_TABLE_BITS && storedEntries > slots() - (slots() >> 2)) {
            grow();
        }
    }

    private void allocate(int bits) {
        tableBits = bits;
        bucketMask = (1 << bits) - 1;
        table = new long[(1 << bits) * WAYS * 2];
        storedEntries = 0;
    }

    private int slots() {
        return (1 << tableBits) * WAYS;
    }

    /** Doubles the table and carries the old entries over. */
    private void grow() {
        long[] old = table;
        allocate(tableBits + 1);
        for (int at = 0; at < old.length; at += 2) {
            long entry = old[at + 1];
            if (entry == 0) continue;
            store(old[at], leaderOf(entry), valueOf(entry), isExact(entry),
                    isLowerBound(entry), moveOf(entry), cardsLeftOf(entry));
        }
    }

    /**
     * Where this position's bucket starts.
     *
     * <p>The key is a perfect encoding but its bits are anything but uniform:
     * positions inside one search differ in two or three lanes out of
     * thirty-two, and the lanes that move are neighbours. It therefore has to be
     * mixed hard before it is truncated, and this is the one place where being
     * approximately right is not good enough. A single multiply plus one
     * xor-shift was tried and it clustered so badly that <b>68 % of stores
     * evicted a live entry</b> in a table that was 98 % empty, which cost 2.2x
     * the nodes. The full splitmix64 finaliser below leaves 384 evictions in
     * 649,000 stores and reproduces the HashMap's node count to four figures.
     *
     * <p>The leader goes into the mix rather than into the key -- there is no
     * room for it in sixty-four bits -- and is checked again at the slot.
     */
    private int bucketAt(long position, int leader) {
        long mixed = position ^ (leader * 0x9E3779B97F4A7C15L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (int) (mixed & bucketMask) * (WAYS * 2);
    }

    /** The slot holding this position, or -1. Whole bucket, no chaining out of it. */
    private int probe(long position, int leader) {
        int base = bucketAt(position, leader);
        for (int way = 0; way < WAYS; way++) {
            int at = base + way * 2;
            long entry = table[at + 1];
            if (entry == 0) continue;
            if (table[at] == position && leaderOf(entry) == leader) return at;
        }
        return -1;
    }

    /**
     * Writes an entry, replacing rather than chaining.
     *
     * <p>An empty slot first, then this very position if it is already here, and
     * only then a victim -- the shallowest entry in the bucket, because the one
     * with the fewest cards left is the one cheapest to search again if it is
     * ever wanted. Nothing here can be wrong, only wasteful: the table is a
     * cache, and a lost entry costs a re-search.
     */
    private void store(long position, int leader, int value, boolean exact,
                       boolean lowerBound, int move, int cardsLeft) {
        int base = bucketAt(position, leader);
        int empty = -1;
        int victim = base;
        int shallowest = Integer.MAX_VALUE;
        for (int way = 0; way < WAYS; way++) {
            int at = base + way * 2;
            long entry = table[at + 1];
            if (entry == 0) {
                if (empty < 0) empty = at;
                continue;
            }
            if (table[at] == position && leaderOf(entry) == leader) {
                empty = -1;
                victim = at;
                shallowest = Integer.MIN_VALUE;
                break;
            }
            int depth = cardsLeftOf(entry);
            if (depth < shallowest) {
                shallowest = depth;
                victim = at;
            }
        }
        int at = empty >= 0 ? empty : victim;
        if (empty >= 0) storedEntries++;
        table[at] = position;
        table[at + 1] = ENTRY_PRESENT
                | ((long) (value & 0xFF) << VALUE_SHIFT)
                | ((long) ((move + 1) & 0x3F) << MOVE_SHIFT)
                | ((exact ? 1L : 0L) << EXACT_SHIFT)
                | ((lowerBound ? 1L : 0L) << LOWER_BOUND_SHIFT)
                | ((long) (leader & 3) << LEADER_SHIFT)
                | ((long) (cardsLeft & 0xF) << CARDS_LEFT_SHIFT);
    }

    // ------------------------------------------------------- the native engine

    /**
     * Asks the library for a card, or returns null so the caller uses Java.
     *
     * @param declarerPointsSoFar the banked points for the banded question, or
     *                            {@link Integer#MIN_VALUE} for the exact one
     */
    private static Choice nativeChoice(Contract contract, SkatAi.Seat declarer,
                                       SkatAi.Seat toPlay,
                                       List<? extends Collection<Card>> remaining,
                                       SkatAi.Seat leader, List<Card> trickSoFar,
                                       int declarerPointsSoFar) {
        // Validated here rather than only inside the library, because these are
        // the checks prepare() makes and a caller that gets them wrong deserves
        // the same exception whichever engine answers.
        if (trickSoFar.size() > 2) {
            throw new IllegalArgumentException("A trick holds at most three cards");
        }
        if ((leader.ordinal() + trickSoFar.size()) % 3 != toPlay.ordinal()) {
            throw new IllegalArgumentException(
                    "It is not " + toPlay + "'s turn after " + trickSoFar.size() + " cards");
        }
        int[] masks = masks(remaining);
        long[] out = new long[3];
        int status = declarerPointsSoFar == Integer.MIN_VALUE
                ? NativeSolver.bestCard(contract.ordinal(), declarer.ordinal(),
                        toPlay.ordinal(), masks[0], masks[1], masks[2], leader.ordinal(),
                        pack(trickSoFar), trickSoFar.size(), out)
                : NativeSolver.bestCardForResult(contract.ordinal(), declarer.ordinal(),
                        toPlay.ordinal(), masks[0], masks[1], masks[2], leader.ordinal(),
                        pack(trickSoFar), trickSoFar.size(), declarerPointsSoFar, out);
        if (status != NativeSolver.OK) return null;
        return new Choice(ContractTables.card((int) out[0]), (int) out[1], out[2]);
    }

    /** The per-card verdicts from the library, or null so the caller uses Java. */
    private static List<Verdict> nativeMovesReaching(Contract contract, SkatAi.Seat declarer,
                                                     SkatAi.Seat toPlay,
                                                     List<? extends Collection<Card>> remaining,
                                                     SkatAi.Seat leader,
                                                     List<Card> trickSoFar, int target) {
        if (trickSoFar.size() > 2) {
            throw new IllegalArgumentException("A trick holds at most three cards");
        }
        if ((leader.ordinal() + trickSoFar.size()) % 3 != toPlay.ordinal()) {
            throw new IllegalArgumentException(
                    "It is not " + toPlay + "'s turn after " + trickSoFar.size() + " cards");
        }
        int[] masks = masks(remaining);
        int[] cards = new int[10];
        int[] bounds = new int[10];
        int count = NativeSolver.movesReaching(contract.ordinal(), declarer.ordinal(),
                toPlay.ordinal(), masks[0], masks[1], masks[2], leader.ordinal(),
                pack(trickSoFar), trickSoFar.size(), target, cards, bounds);
        if (count < 0) return null;
        List<Verdict> verdicts = new ArrayList<>(count);
        for (int at = 0; at < count; at++) {
            verdicts.add(new Verdict(ContractTables.card(cards[at]),
                    bounds[at] >= target, bounds[at]));
        }
        return verdicts;
    }

    // ---------------------------------------------------------------- testing

    /**
     * Plain minimax: no pruning, no transpositions, no ordering.
     *
     * <p>Exists so the fast search can be checked against it. If alpha-beta and
     * the table are correct they must return the identical value for every
     * position, and a disagreement is a bug in the optimisation rather than a
     * matter of taste.
     */
    public static int solveExhaustively(Contract contract, SkatAi.Seat declarer,
                                        List<? extends Collection<Card>> hands,
                                        SkatAi.Seat leader) {
        DoubleDummySolver solver = new DoubleDummySolver(contract, declarer.ordinal());
        for (int seat = 0; seat < 3; seat++) {
            int mask = 0;
            for (Card card : hands.get(seat)) mask |= 1 << ContractTables.index(card);
            solver.hands[seat] = mask;
        }
        return solver.brute(leader.ordinal(), leader.ordinal(), 0, 0);
    }

    private int brute(int toPlay, int leader, int trickCards, int trickSize) {
        visitedNodes++;
        if (hands[0] == 0 && hands[1] == 0 && hands[2] == 0) return 0;
        boolean declarerToPlay = toPlay == declarerSeat;
        int best = declarerToPlay ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        List<Integer> moves = new ArrayList<>();
        for (int move : orderedMoves(toPlay, trickCards, trickSize)) moves.add(move);
        for (int card : moves) {
            hands[toPlay] &= ~(1 << card);
            int nextTrick = trickCards | (card << (8 * trickSize));
            int value;
            if (trickSize == 2) {
                int led = nextTrick & 0xFF;
                int second = (nextTrick >>> 8) & 0xFF;
                int winnerCard = tables.trickWinner(led, second, card);
                int winnerSeat = (leader + slotOf(nextTrick, winnerCard)) % 3;
                int trickPoints = tables.points(led) + tables.points(second) + tables.points(card);
                value = (winnerSeat == declarerSeat ? trickPoints : 0)
                        + brute(winnerSeat, winnerSeat, 0, 0);
            } else {
                value = brute((toPlay + 1) % 3, leader, nextTrick, trickSize + 1);
            }
            hands[toPlay] |= 1 << card;
            best = declarerToPlay ? Math.max(best, value) : Math.min(best, value);
        }
        return best;
    }
}
