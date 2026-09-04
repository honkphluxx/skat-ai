package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.solve.ContractTables;
import dev.skatklar.demo.solve.DoubleDummySolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * αµ: a search that is not allowed to be two people at once.
 *
 * <p>What is wrong with the player this replaces. It samples sixteen deals,
 * solves each one double-dummy, and votes. Solving a deal double-dummy assumes
 * every card is face up — including, and this is the damage, <em>its own future
 * cards</em>. So in world A it plans a line that only works if it later finesses
 * left, in world B a line that only works if it later finesses right, and it
 * counts both as won. At the table it will have to pick one. That is <b>strategy
 * fusion</b>, and it is why a determinized search is optimistic in exactly the
 * positions where the guess matters.
 *
 * <p>αµ (Cazenave and Ventos, 2019) fixes it by construction: at every node where
 * <em>this</em> player moves, one card must be chosen for <em>all</em> worlds at
 * once. The opponents keep their perfect information and may answer differently
 * in each world — that half of the pessimism is deliberate and is what keeps the
 * search sound rather than hopeful. The parameter {@code depth} counts this
 * player's own moves before the position is handed to the double-dummy solver;
 * at {@code depth = 1} nothing is expanded before the hand-off and the algorithm
 * is exactly the old vote, which is the identity {@code AlphaMuTest} pins down.
 *
 * <h2>Why the values are vectors</h2>
 *
 * <p>A move is not worth a number here, it is worth a <em>vector</em>: won or
 * lost, once per sampled world. Two moves can be incomparable — this one wins
 * worlds 1–8, that one wins worlds 5–12 — and collapsing them to a count too
 * early is what loses the information the search is for. So each node returns
 * the set of vectors that no other vector beats outright, its <b>Pareto
 * front</b>, and only the root collapses to a number by counting worlds won.
 *
 * <p>With at most thirty-two worlds a vector is an {@code int}, one bit per
 * world, and the three operations the algorithm needs are one instruction each:
 *
 * <ul>
 *   <li>the opponents choosing per world is bitwise <b>and</b>;</li>
 *   <li>keeping every option open at our own nodes is <b>union of fronts</b>;</li>
 *   <li>"v1 is at least as good everywhere and better somewhere" is a subset
 *       test, {@code (v2 & ~v1) == 0 && v1 != v2}.</li>
 * </ul>
 *
 * <h2>What is modelled and what is not</h2>
 *
 * <p>The outcome per world is one bit: does the declarer reach its target. Skat
 * charges a lost game twice the game value whatever the margin, so the bit is
 * most of what the score sheet pays for — but not all of it. Schneider and
 * Schwarz are invisible here, and a line that wins by one point ranks with one
 * that wins by forty. {@link Personality#targetFor} still sets where the bit
 * falls, so a bold player still plays for a higher band; it simply cannot see
 * past it.
 *
 * <p>Only the declarer's side of this is used. The arena measured the defensive
 * ceiling at zero — a double-dummy player that sees all three hands defends no
 * better than an honest one — so a defender has nothing to gain from a better
 * search and keeps the cheap vote. The other reason is that the defending side
 * is two people with different information, and calling them one Max would
 * assume a partnership that does not exist.
 */
public final class AlphaMu {

    /**
     * How large a Pareto front may grow before the weakest members are dropped.
     *
     * <p>The combination at an opponent node is a cross product, so fronts can in
     * principle multiply. In practice they collapse — most vectors dominate most
     * others — and this cap has to be reached before it costs anything. It is
     * here so that a pathological position costs a bounded amount of time rather
     * than the match, and dropping by fewest worlds won is the least damaging
     * order: the vectors discarded are the ones the root would not have chosen.
     */
    private static final int MAX_FRONT = 16;

    private final Contract contract;
    private final ContractTables tables;
    private final int declarerSeat;
    private final int mySeat;
    private final int worldCount;
    private final int allWorlds;
    /** Per world, per seat: the cards still held, as a bit mask. */
    private final int[][] hands;
    /**
     * One solver per world, kept for the whole search.
     *
     * <p>The leaves of this search are the same endgame over and over with one
     * card moved, so a solver that forgets between questions re-derives all of
     * it every time. Sharing a table within a world was worth roughly an order
     * of magnitude when it was added; sharing across worlds would be worth
     * nothing, since two worlds have different hands and therefore different
     * keys.
     */
    private final DoubleDummySolver[] solvers;
    private long leaves;
    /**
     * How often committing to one card actually cost something.
     *
     * <p>The number this whole algorithm exists to make non-zero. At a node of
     * ours it compares what a per-world choice would have claimed -- which is
     * what the determinized vote claims -- against the best single card. When
     * the two agree, αµ has expanded a subtree and changed nothing.
     */
    private long fusionBites;

    /** Gives back every solver's native table. Idempotent. */
    private void releaseSolvers() {
        for (DoubleDummySolver solver : solvers) {
            if (solver != null) solver.close();
        }
    }

    /** Per world, the points the declarer needed when the search started. */
    private final int[] targets;

    private AlphaMu(Contract contract, int declarerSeat, int mySeat, int[][] hands,
                    int[] targets) {
        this.targets = targets;
        this.contract = contract;
        this.tables = ContractTables.of(contract);
        this.declarerSeat = declarerSeat;
        this.mySeat = mySeat;
        this.hands = hands;
        this.worldCount = hands.length;
        this.allWorlds = worldCount == 32 ? -1 : (1 << worldCount) - 1;
        this.solvers = new DoubleDummySolver[worldCount];
        for (int world = 0; world < worldCount; world++) {
            solvers[world] = DoubleDummySolver.reusableFor(contract, declarerSeat);
        }
    }

    /**
     * How many worlds each legal card still wins, and what the search cost.
     *
     * <p>A score per card rather than one chosen card, and the difference is not
     * cosmetic. Outcomes here are one bit per world, so with sixteen worlds
     * several cards tie constantly, and <em>who breaks the tie</em> decides most
     * of the hands. The caller already has a good rule for that — among cards
     * that win equally often, keep your points off the table — and it is a rule
     * about Skat that this search knows nothing about. Returning one card would
     * silently replace it with "lowest card index", which is not a rule at all.
     *
     * <p>That was not hypothetical. The first version returned a single card,
     * measured 2.8 game points a game <em>worse</em> than the vote it replaced,
     * and the whole loss was here rather than in the algorithm.
     *
     * @param fusionBites nodes at which one card for all worlds was worse than a
     *                    card per world. Zero means this search was an expensive
     *                    way to reproduce the vote.
     */
    public record Ranking(Map<Card, Integer> worldsWon, int worlds, long leaves,
                          long fusionBites) {

        /** The share of worlds the best card wins, which is what deepening lowers. */
        public double bestShare() {
            int best = 0;
            for (int won : worldsWon.values()) best = Math.max(best, won);
            return worlds == 0 ? 0 : (double) best / worlds;
        }
    }

    /**
     * The best card for the seat to move, over the worlds it is willing to
     * believe in.
     *
     * @param worlds     sampled deals; each holds the three hands by seat
     *                   ordinal with everything already played removed, so the
     *                   observer's own hand appears in its own seat
     * @param leader     the seat that led the current trick
     * @param trickSoFar cards already on the table this trick, in play order
     *                   from the leader
     * @param targets    per world, the card points the declarer still needs,
     *                   the caller having already subtracted what is banked.
     *                   One per world rather than one overall because the skat
     *                   is two unseen cards and each world puts different points
     *                   in it -- the same position is a different contract
     *                   depending on where those eleven points went
     * @param depth      this player's own moves to expand before the solver is
     *                   asked; 1 reproduces the plain determinized vote
     * @return one entry per legal card; empty when there is nothing to choose
     */
    public static Ranking rank(Contract contract, SeatIndex declarer, SeatIndex toPlay,
                                  List<WorldSampler.World> worlds, SeatIndex leader,
                                  List<Card> trickSoFar, int[] targets, int depth) {
        if (targets.length != worlds.size()) {
            throw new IllegalArgumentException("one target per world, not " + targets.length);
        }
        if (worlds.isEmpty() || worlds.size() > 32) {
            throw new IllegalArgumentException(
                    "alpha-mu carries one bit per world, so 1..32 of them: " + worlds.size());
        }
        int[][] hands = new int[worlds.size()][3];
        for (int world = 0; world < worlds.size(); world++) {
            for (int seat = 0; seat < 3; seat++) {
                int mask = 0;
                for (Card card : worlds.get(world).hands().get(seat)) {
                    mask |= 1 << ContractTables.index(card);
                }
                hands[world][seat] = mask;
            }
        }
        AlphaMu search = new AlphaMu(contract, declarer.index(), toPlay.index(), hands, targets);
        try {
            return search.atRoot(toPlay.index(), leader.index(), pack(trickSoFar),
                    trickSoFar.size(), 0, Math.max(1, depth));
        } finally {
            // Thirty-two solvers, each of which may be holding a native
            // transposition table the garbage collector cannot see the size of.
            // They are dead the moment this search returns, and saying so here
            // is the difference between a steady heap and one that grows by a
            // decision's worth of tables until something else forces a
            // collection.
            search.releaseSolvers();
        }
    }

    /**
     * A seat, as the plain 0..2 ordinal the solver and the tables speak.
     *
     * <p>Deliberately not {@code SkatAi.Seat}: this package is the one place
     * where the search, the tables and the solver meet, and all three of them
     * already index by ordinal. A one-method interface keeps the call sites
     * readable without dragging the AI's vocabulary down into the arithmetic.
     */
    @FunctionalInterface
    public interface SeatIndex {
        int index();
    }

    private static int pack(List<Card> trickSoFar) {
        int packed = 0;
        for (int slot = 0; slot < trickSoFar.size(); slot++) {
            packed |= ContractTables.index(trickSoFar.get(slot)) << (8 * slot);
        }
        return packed;
    }

    /**
     * The root, with iterative deepening so that the shallow answer is always
     * available and the deep one only has to beat it.
     *
     * <p>The <b>root cut</b> from the paper: when deepening does not raise the
     * best move's share of worlds, searching deeper cannot raise it either, so
     * it stops. That is what makes the cost of a high {@code depth} something
     * the position decides rather than the caller.
     */
    private Ranking atRoot(int toPlay, int leader, int trickCards, int trickSize,
                           int spent, int depth) {
        Map<Card, Integer> scores = new LinkedHashMap<>();
        double bestShare = -1;
        for (int level = 1; level <= depth; level++) {
            Map<Card, Integer> levelScores = new LinkedHashMap<>();
            int bestWon = -1;
            for (int move : legalMoves(hands[0][toPlay], trickCards, trickSize)) {
                // Legality is read off world zero. Every world shares the seat's
                // own hand -- that is what a sampled world is -- so at our own
                // node the move list cannot differ between them.
                int vector = bestOf(afterMove(toPlay, leader, trickCards, trickSize,
                        move, allWorlds, spent, level - 1));
                int won = Integer.bitCount(vector & allWorlds);
                levelScores.put(ContractTables.card(move), won);
                bestWon = Math.max(bestWon, won);
            }
            if (levelScores.isEmpty()) break;
            double share = (double) bestWon / worldCount;
            boolean unchanged = !scores.isEmpty() && share == bestShare;
            scores = levelScores;
            bestShare = share;
            // The root cut, and note which way round it goes. Deepening can only
            // ever *lower* the claim -- that is the whole point of the algorithm,
            // and the reason a deeper answer is the one to keep. So a level that
            // lowers it is a level that found something and the search goes on;
            // it stops when a level changes nothing, because no deeper one will
            // change anything either.
            //
            // Written the other way round first, breaking as soon as the share
            // fell, which meant every search stopped at the first level that did
            // any good and "depth 3" was depth 2 with extra bookkeeping.
            if (unchanged) break;
            if (bestWon == worldCount) break;
        }
        return new Ranking(scores, worldCount, leaves, fusionBites);
    }

    /** The best single vector of a front, by worlds won. */
    private int bestOf(List<Integer> front) {
        int best = 0;
        int won = -1;
        for (int vector : front) {
            int count = Integer.bitCount(vector & allWorlds);
            if (count > won) { won = count; best = vector; }
        }
        return best;
    }

    /**
     * Our own node: one card for every world, and every card kept as an option.
     *
     * <p>The union rather than the maximum is the whole point. Which of these
     * vectors is best depends on what the opponents do above, and that is not
     * known here; collapsing now would be the same mistake the vote makes.
     */
    private List<Integer> maxNode(int toPlay, int leader, int trickCards, int trickSize,
                                  int liveWorlds, int spent, int depth) {
        if (depth == 0) {
            return List.of(evaluate(toPlay, leader, trickCards, trickSize, liveWorlds, spent));
        }
        int anyWorld = Integer.numberOfTrailingZeros(liveWorlds);
        List<Integer> front = new ArrayList<>();
        for (int move : legalMoves(hands[anyWorld][toPlay], trickCards, trickSize)) {
            List<Integer> child = afterMove(toPlay, leader, trickCards, trickSize, move,
                    liveWorlds, spent, depth - 1);
            for (int vector : child) {
                // Cut on win: a card that wins every live world cannot be
                // improved on, so the remaining cards need not be searched.
                if ((vector & liveWorlds) == liveWorlds) return List.of(vector);
            }
            front.addAll(child);
        }
        // What a search that may change its mind per world would claim here: the
        // union, bit by bit. Comparing it with the best single card is the
        // measurement of strategy fusion itself.
        int perWorld = 0;
        for (int vector : front) perWorld |= vector;
        List<Integer> pruned = prune(front, liveWorlds);
        if (Integer.bitCount(bestOf(pruned) & liveWorlds)
                < Integer.bitCount(perWorld & liveWorlds)) {
            fusionBites++;
        }
        return pruned;
    }

    /**
     * An opponent's node: perfect information, so a different card per world.
     *
     * <p>Two things happen here that do not happen at our own nodes. A card the
     * opponent does not hold in some world is simply not available there, so the
     * recursion runs on the worlds where it is legal and the rest are left to the
     * other cards — that is the {@code reachable} mask below. And the outcomes
     * are combined with <b>and</b>: the opponent will pick, in each world
     * separately, whichever card hurts us there.
     *
     * <p>The cross product is what keeps the answer honest. Our own future
     * choices are still open inside each branch, and an opponent's card is only
     * good if it is good against <em>one</em> of our replies rather than against
     * a different one in every world.
     */
    private List<Integer> minNode(int toPlay, int leader, int trickCards, int trickSize,
                                  int liveWorlds, int spent, int depth) {
        // One legal-card mask per live world, computed once: the loop below asks
        // for it twice per candidate and there can be ten candidates.
        int[] legalByWorld = new int[worldCount];
        int candidates = 0;
        for (int rest = liveWorlds; rest != 0; rest &= rest - 1) {
            int world = Integer.numberOfTrailingZeros(rest);
            legalByWorld[world] = legalMask(hands[world][toPlay], trickCards, trickSize);
            candidates |= legalByWorld[world];
        }

        List<Integer> combined = null;
        for (int rest = candidates; rest != 0; rest &= rest - 1) {
            int move = Integer.numberOfTrailingZeros(rest);
            int reachable = 0;
            for (int live = liveWorlds; live != 0; live &= live - 1) {
                int world = Integer.numberOfTrailingZeros(live);
                if ((legalByWorld[world] & (1 << move)) != 0) reachable |= 1 << world;
            }
            List<Integer> front = afterMove(toPlay, leader, trickCards, trickSize, move,
                    reachable, spent, depth);
            combined = combined == null ? front : meet(combined, front, liveWorlds);
            // Every live world already lost: nothing below can matter.
            if (combined.size() == 1 && (combined.get(0) & liveWorlds) == 0) return combined;
        }
        return combined == null
                ? List.of(evaluate(toPlay, leader, trickCards, trickSize, liveWorlds, spent))
                : combined;
    }

    /** The cross product of two fronts under componentwise minimum. */
    private List<Integer> meet(List<Integer> left, List<Integer> right, int liveWorlds) {
        List<Integer> product = new ArrayList<>(Math.min(MAX_FRONT * 4, left.size() * right.size()));
        for (int a : left) {
            for (int b : right) product.add(a & b);
        }
        return prune(product, liveWorlds);
    }

    /**
     * Plays one card in every world it is live in, and continues.
     *
     * <p>Where a trick completes this is also where the score moves: the three
     * cards are the same in every world of this branch — our own card was chosen
     * once for all of them and each opponent branch fixes one card — so the
     * winner and the points are the same too, and {@code target} can stay a plain
     * number instead of becoming a vector.
     */
    private List<Integer> afterMove(int toPlay, int leader, int trickCards, int trickSize,
                                    int move, int liveWorlds, int spent, int depth) {
        for (int rest = liveWorlds; rest != 0; rest &= rest - 1) {
            hands[Integer.numberOfTrailingZeros(rest)][toPlay] &= ~(1 << move);
        }
        try {
            int played = trickCards | (move << (8 * trickSize));
            int nextSeat;
            int nextLeader = leader;
            int nextTrick = played;
            int nextSize = trickSize + 1;
            int nextSpent = spent;
            if (trickSize == 2) {
                int led = played & 0xFF;
                int second = (played >> 8) & 0xFF;
                int winningCard = tables.trickWinner(led, second, move);
                int slot = winningCard == led ? 0 : winningCard == second ? 1 : 2;
                nextSeat = (leader + slot) % 3;
                nextLeader = nextSeat;
                nextTrick = 0;
                nextSize = 0;
                if (nextSeat == declarerSeat) {
                    nextSpent = spent + tables.points(led) + tables.points(second)
                            + tables.points(move);
                }
            } else {
                nextSeat = (toPlay + 1) % 3;
            }
            // Depth is spent at our own nodes and nowhere else, so the hand-off
            // to the solver happens as soon as the budget is gone -- from the
            // position right after our own card, without expanding the
            // opponents' replies first. Expanding them would cost branching for
            // nothing: below the last of our decisions the game is perfect
            // information, and the solver already plays it exactly.
            if (depth == 0) {
                return List.of(evaluate(nextSeat, nextLeader, nextTrick, nextSize,
                        liveWorlds, nextSpent));
            }
            return nextSeat == mySeat
                    ? maxNode(nextSeat, nextLeader, nextTrick, nextSize, liveWorlds,
                            nextSpent, depth)
                    : minNode(nextSeat, nextLeader, nextTrick, nextSize, liveWorlds,
                            nextSpent, depth);
        } finally {
            for (int rest = liveWorlds; rest != 0; rest &= rest - 1) {
                hands[Integer.numberOfTrailingZeros(rest)][toPlay] |= 1 << move;
            }
        }
    }

    /**
     * The leaf: one double-dummy question per live world.
     *
     * <p>Everything above this line exists to make sure the same card is played
     * in every world; below it, perfect information is fine, because there are no
     * more decisions of ours left to fuse.
     */
    private int evaluate(int toPlay, int leader, int trickCards, int trickSize,
                         int liveWorlds, int spent) {
        int won = ~liveWorlds;
        for (int rest = liveWorlds; rest != 0; rest &= rest - 1) {
            int world = Integer.numberOfTrailingZeros(rest);
            leaves++;
            // What the declarer still needs in this world: what it needed when
            // the search started, less what the tricks played inside the search
            // have already brought in. The first term is per world because the
            // skat is, the second is not because a branch fixes every card in it.
            boolean declarerMakesIt = solvers[world].reaches(toPlay, hands[world], leader,
                    trickCards, trickSize, targets[world] - spent);
            // One bit, from the point of view of the seat doing the searching.
            // The declarer wants the target reached; a defender wants it missed,
            // which is the same search read the other way up.
            if (declarerMakesIt == (mySeat == declarerSeat)) won |= 1 << world;
        }
        return won;
    }

    /** Keeps only the vectors nothing else beats, and at most {@link #MAX_FRONT}. */
    private List<Integer> prune(List<Integer> front, int liveWorlds) {
        List<Integer> kept = new ArrayList<>(Math.min(front.size(), MAX_FRONT));
        outer:
        for (int candidate : front) {
            int masked = candidate & liveWorlds;
            for (int index = 0; index < kept.size(); index++) {
                int other = kept.get(index) & liveWorlds;
                if (other == masked) continue outer;
                if ((masked & ~other) == 0) continue outer;          // other dominates
                if ((other & ~masked) == 0) { kept.remove(index); index--; }
            }
            kept.add(candidate);
        }
        if (kept.size() > MAX_FRONT) {
            kept.sort((a, b) -> Integer.compare(
                    Integer.bitCount(b & liveWorlds), Integer.bitCount(a & liveWorlds)));
            return new ArrayList<>(kept.subList(0, MAX_FRONT));
        }
        return kept;
    }

    /** The cards this hand may play, as a bit mask. */
    private int legalMask(int hand, int trickCards, int trickSize) {
        if (trickSize == 0) return hand;
        int mustFollow = hand & tables.classMask(tables.followClass(trickCards & 0xFF));
        return mustFollow != 0 ? mustFollow : hand;
    }

    /** The same, as 0..31 indices. */
    private int[] legalMoves(int hand, int trickCards, int trickSize) {
        int playable = legalMask(hand, trickCards, trickSize);
        int[] moves = new int[Integer.bitCount(playable)];
        int at = 0;
        for (int rest = playable; rest != 0; rest &= rest - 1) {
            moves[at++] = Integer.numberOfTrailingZeros(rest);
        }
        return moves;
    }
}
