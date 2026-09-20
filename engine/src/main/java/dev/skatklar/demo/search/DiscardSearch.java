package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.solve.NullSolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Which two cards to bury, decided by playing the hand out instead of by rule.
 *
 * <p>The same construction the card play uses -- sample deals consistent with
 * what this seat can see, solve each one, keep what wins in the most -- applied
 * one decision earlier. There are only 66 ways to bury two of twelve, so the
 * candidates can be enumerated rather than invented.
 *
 * <p><b>Why this is worth doing at all.</b> Measured 2026-09-20 over 352
 * declared boards with contracts from the auction and all 66 discards
 * enumerated against double-dummy defence: some discard makes the contract on
 * 47.2% of them and the heuristic's discard on 40.9%. One declared game in
 * eight that could be won is lost before a card is played. The heuristic is not
 * bad -- keep trumps and aces, bury the points of a short suit is what a club
 * player does and it is right most of the time -- it is simply answering a
 * question about the shape of a hand when the question is about this deal.
 *
 * <p><b>Not all 66, and that is what makes it work.</b> The first version
 * scored every pair and lost to the heuristic by two points: maximising over 66
 * candidates on four Bernoulli samples each picks the luckiest rather than the
 * best, and that winner's curse was larger than the effect being chased. So the
 * candidates are pruned to pairs drawn from the {@code candidateCards} the
 * heuristic ranks lowest -- five of them, ten pairs -- which over 264 declared
 * boards contains a making discard on 100% of the boards where one exists. The
 * heuristic's ordering is worth considerably more than the choice it makes with
 * it. What the prune saves is spent on worlds, where it does some good.
 *
 * <p><b>The worlds are drawn once and reused for every candidate.</b> That is not an
 * optimisation, or not only one. The twenty cards this seat cannot see are the
 * same whichever two it buries -- the buried pair comes out of its own twelve --
 * so one set of worlds is legitimate for every candidate, and using a fresh set
 * per candidate would score the discards against different opponents and call
 * the difference skill. It is a paired comparison for the same reason the arena
 * is one.
 *
 * <p><b>Uniform, for now.</b> {@code BeliefWorldSource} cannot be asked here:
 * its evidence is built from a {@link SkatAi.DecisionContext}, which is a
 * card-play position, and at the exchange there is no trick, no history and no
 * void to encode. What there is -- the auction -- the belief has barely seen,
 * since a minted Null carries no bidding at all. So the twenty unseen cards are
 * split evenly at random, which is what the sampler did everywhere before the
 * belief existed and is most of the value here.
 */
public final class DiscardSearch {

    private DiscardSearch() {}

    /**
     * @param buried the two cards to put down
     * @param wins how many of the sampled worlds that choice makes the contract in
     * @param worlds how many worlds were drawn
     * @param solves how many positions were actually solved before the budget bit
     */
    public record Choice(List<Card> buried, int wins, int worlds, int solves) {}

    /**
     * The best of the 66, or the fallback if nothing could be searched in time.
     *
     * @param unseen the twenty cards this seat cannot see: everything but its
     *        own twelve
     * @param deadlineNanos a {@link System#nanoTime} value to stop at, or zero
     *        for no limit. Checked between candidates rather than inside one,
     *        so a spent budget yields the best of what was scored rather than
     *        an unscored guess
     * @param candidateCards how many of the heuristic's least-wanted cards the
     *        buried pair may be drawn from; five covers the whole ceiling
     * @param fallback what to bury if the budget was gone before anything was
     *        scored -- the heuristic, which is never worse than arbitrary
     */
    public static Choice choose(Contract contract, SkatAi.Seat declarer, List<Card> twelve,
                                List<Card> unseen, SkatAi.Seat forehand, int worlds,
                                int candidateCards, long deadlineNanos, Random random,
                                List<Card> fallback) {
        if (twelve.size() != 12 || unseen.size() != 20 || worlds < 1) {
            return new Choice(fallback, 0, 0, 0);
        }
        int pool = Math.max(2, Math.min(12, candidateCards));
        // Least wanted last, so the candidates are the tail of the ordering.
        List<Card> candidates = new ArrayList<>(
                Discards.ranked(contract, twelve).subList(12 - pool, 12));
        // Drawn up front, before any solving, and reused for every candidate.
        List<List<List<Card>>> sampled = new ArrayList<>(worlds);
        for (int world = 0; world < worlds; world++) {
            List<Card> shuffled = new ArrayList<>(unseen);
            Collections.shuffle(shuffled, random);
            sampled.add(List.of(new ArrayList<>(shuffled.subList(0, 10)),
                    new ArrayList<>(shuffled.subList(10, 20))));
        }

        List<Card> best = null;
        int bestWins = -1;
        int bestBanked = -1;
        int solves = 0;
        for (int first = 0; first < candidates.size(); first++) {
            for (int second = first + 1; second < candidates.size(); second++) {
                if (deadlineNanos != 0 && best != null
                        && System.nanoTime() - deadlineNanos >= 0) {
                    return new Choice(best, bestWins, worlds, solves);
                }
                Card one = candidates.get(first);
                Card two = candidates.get(second);
                List<Card> kept = new ArrayList<>(twelve);
                kept.remove(one);
                kept.remove(two);
                int banked = SkatRules.cardPoints(one) + SkatRules.cardPoints(two);
                int wins = 0;
                for (List<List<Card>> split : sampled) {
                    if (makes(contract, declarer, kept, split, forehand, banked)) wins++;
                    solves++;
                }
                // More banked points breaks a tie, and only a tie. Two discards
                // that win the same worlds are not equally safe: the points
                // already in the skat cannot be taken back, so they are the
                // cheapest insurance against a world nobody sampled. In a Null
                // there are no points to bank and this is always zero, which
                // leaves the enumeration order to decide -- and that order is
                // the heuristic's own, least wanted first, so a tie falls to the
                // card it liked least. That is the right default: a tie means the
                // sampled worlds could not tell them apart.
                if (wins > bestWins || (wins == bestWins && banked > bestBanked)) {
                    bestWins = wins;
                    bestBanked = banked;
                    best = List.of(one, two);
                }
            }
        }
        return best == null ? new Choice(fallback, 0, worlds, solves)
                : new Choice(best, bestWins, worlds, solves);
    }

    /** Does the declarer hold this contract in this world, having banked those points? */
    private static boolean makes(Contract contract, SkatAi.Seat declarer, List<Card> kept,
                                 List<List<Card>> split, SkatAi.Seat forehand, int banked) {
        List<List<Card>> hands = new ArrayList<>(3);
        int taken = 0;
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            hands.add(seat == declarer ? kept : split.get(taken++));
        }
        if (contract.isNull()) return NullSolver.declarerSurvives(declarer, hands, forehand);
        // The two buried cards count to the declarer, so the target on the table
        // is lower by exactly what went into the skat.
        return DoubleDummySolver.declarerReaches(contract, declarer, hands, forehand,
                Math.max(1, 61 - banked));
    }
}
