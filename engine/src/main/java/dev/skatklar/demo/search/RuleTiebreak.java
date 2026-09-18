package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.Comparator;
import java.util.List;

/**
 * The order the search player breaks its last ties in when it is asked to
 * use the table's rules rather than the cheapest card
 * ({@link SearchAiProvider#withRuleTiebreak}).
 *
 * <p>Cards that reach here are, in every sampled world, worth the same game:
 * the vote could not separate them and neither could the cushion. What is
 * left is the position in the trick, which the worlds cannot disagree about.
 * Last to play, a card that takes the trick banks its points now instead of
 * in the worlds, so it is preferred, and among takers the one that brings the
 * most points home, then the one that spends the least power; a trick that
 * cannot be taken gets the fewest points, or the most when the partner holds
 * it. Earlier in the trick, the fewest points and then the least power --
 * which among touching cards, the usual reason for a tie, is the lowest of
 * them. Nothing here is learned or priced; it is what the score sheet says a
 * trick is worth, and it is a contestant until the arena says otherwise.
 *
 * <p>A Null is a different game and gets a different order; see
 * {@link #nullOrder}.
 */
public final class RuleTiebreak {

    private RuleTiebreak() {}

    /**
     * How a Null's last ties are settled. Only {@link #POINTS} ships; the
     * others are contestants, and one of them has already been measured and
     * refuted -- see {@link #SHED_HIGH}.
     */
    public enum NullOrder {
        /**
         * The order every other contract uses: fewest card points, then least
         * power. A Null scores no card points, so this is sorting by a
         * quantity the contract does not use, and it inverts Null's own rank
         * wherever a ten meets a court card (it prefers a queen, worth three,
         * to a ten, worth ten, though the ten is the lower card). What it
         * approximates, by accident, is "play a low card": the zero-point
         * sevens, eights and nines come first. Measured on 414 Null boards it
         * beat the principled replacement below, which is how the accident
         * turned into an explanation.
         */
        POINTS,
        /**
         * Shed the highest safe card, except as a defender in front of a
         * declarer that has not yet played. The reasoning was that a Null
         * declarer's high cards are the danger and a card safe now need not
         * stay safe, while a defender's high cards are worth nothing.
         *
         * <p><b>Measured worse, resolved: −1.33 [−1.95, −0.72] against the
         * shipped player over three seeds</b> (arena/README.md, 2026-09-18
         * second). The reasoning ignored what the vote already does. A card
         * reaching this comparator survives in <em>every sampled world</em>,
         * and the Null solver searches to the end of the hand rather than one
         * trick ahead, so "safe now, dangerous later" has already been priced.
         * What is left to choose between is robustness to the worlds that were
         * <em>not</em> sampled, and there a low card is the wider margin --
         * the same logic as the fifteen-point cushion in a trump game. Kept
         * registered as the record of a refuted idea.
         */
        SHED_HIGH,
        /**
         * The lowest card by Null's own rank, for both sides, everywhere.
         * What {@link #POINTS} was accidentally approximating, said properly:
         * the only difference between them is a ten against a court card,
         * where points prefer the queen and this prefers the ten, which is
         * lower and therefore the wider margin.
         */
        LOW_RANK
    }

    /** Greater is better, as the search's own comparators read. The shipped order. */
    public static Comparator<Card> order(SkatAi.DecisionContext context) {
        return order(context, NullOrder.POINTS);
    }

    /** @param nullOrder how to settle a Null's ties; see {@link NullOrder} */
    public static Comparator<Card> order(SkatAi.DecisionContext context, NullOrder nullOrder) {
        Contract contract = context.game.contract;
        List<SkatAi.PlayedCard> plays = context.currentTrick.plays;
        if (nullOrder != NullOrder.POINTS && contract.isNull()) {
            return nullOrder(context, contract, plays, nullOrder);
        }
        Comparator<Card> mostPoints = Comparator.comparingInt((Card card) -> SkatRules.cardPoints(card));
        Comparator<Card> fewestPoints = mostPoints.reversed();
        Comparator<Card> leastPower =
                Comparator.<Card>comparingInt(card -> SkatRules.power(contract, card)).reversed();
        if (plays.size() < 2) return fewestPoints.thenComparing(leastPower);

        Card led = plays.get(0).card;
        SkatAi.PlayedCard winning = SkatRules.beats(contract, led, plays.get(0).card, plays.get(1).card)
                ? plays.get(1) : plays.get(0);
        boolean iAmTheDeclarer = context.mySeat == context.game.declarer;
        boolean partnerHasIt = !iAmTheDeclarer && winning.seat != context.game.declarer;
        if (partnerHasIt) return mostPoints.thenComparing(leastPower);
        return (left, right) -> {
            boolean leftTakes = SkatRules.beats(contract, led, winning.card, left);
            boolean rightTakes = SkatRules.beats(contract, led, winning.card, right);
            if (leftTakes != rightTakes) return leftTakes ? 1 : -1;
            Comparator<Card> points = leftTakes ? mostPoints : fewestPoints;
            return points.thenComparing(leastPower).compare(left, right);
        };
    }

    /**
     * The same question in a Null game, where card points are not part of the
     * objective and the order above is therefore sorting by noise.
     *
     * <p>Only reached between cards the vote could not separate, so it never
     * overrules the search about which cards survive -- a card that hands the
     * declarer a trick has already lost the vote in every sampled world, and
     * the Null solver searches to the end of the hand. That is the fact the
     * first attempt here got wrong; see {@link NullOrder} for what each order
     * claims and which of them the arena has already refuted.
     */
    private static Comparator<Card> nullOrder(SkatAi.DecisionContext context, Contract contract,
                                              List<SkatAi.PlayedCard> plays, NullOrder order) {
        Comparator<Card> highest = Comparator.comparingInt(card -> SkatRules.power(contract, card));
        if (order == NullOrder.LOW_RANK) return highest.reversed();
        boolean declaring = context.mySeat == context.game.declarer;
        boolean declarerHasPlayed = false;
        for (SkatAi.PlayedCard play : plays) {
            if (play.seat == context.game.declarer) declarerHasPlayed = true;
        }
        return declaring || declarerHasPlayed ? highest : highest.reversed();
    }
}
