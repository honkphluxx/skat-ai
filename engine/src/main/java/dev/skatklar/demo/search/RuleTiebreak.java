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
final class RuleTiebreak {

    private RuleTiebreak() {}

    /** Greater is better, as the search's own comparators read. The shipped order. */
    static Comparator<Card> order(SkatAi.DecisionContext context) {
        return order(context, false);
    }

    /**
     * @param nullByRank order a Null by Null rank rather than by card points;
     *                   a contestant until the arena says otherwise, which is
     *                   why it is a flag and not simply the behaviour
     */
    static Comparator<Card> order(SkatAi.DecisionContext context, boolean nullByRank) {
        Contract contract = context.game.contract;
        List<SkatAi.PlayedCard> plays = context.currentTrick.plays;
        if (nullByRank && contract.isNull()) return nullOrder(context, contract, plays);
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
     * <p>Measured before it was written: asked to break a tie between the ten,
     * the queen and the seven of a suit at a Null, the order above answers
     * seven, queen, ten -- the queen ahead of the ten, because a queen is worth
     * three card points and a ten is worth ten, while in Null's own order
     * (7 8 9 10 J Q K A) the ten is the lower card. Nothing is scored for card
     * points in a Null, so every such inversion is free damage.
     *
     * <p>What replaces it is one rule with two directions, and only the
     * direction is a claim worth arguing about. <b>Shed the highest card</b>
     * when a high card is what threatens you: the declarer must take no trick,
     * so its high cards are the danger and one that is safe now need not stay
     * safe; a defender's high cards are worth nothing at all, since a defender
     * taking a trick costs its side nothing. <b>Play the lowest card</b> when
     * you are a defender and the declarer has not yet played in this trick,
     * lead included: a low card is the ammunition that forces the declarer
     * over, and spending it in front of a declarer that has already committed
     * a card wastes it.
     *
     * <p>Only reached between cards the vote could not separate, so it never
     * overrules the search about which cards survive -- a card that hands the
     * declarer a trick has already lost the vote in every sampled world.
     */
    private static Comparator<Card> nullOrder(SkatAi.DecisionContext context, Contract contract,
                                              List<SkatAi.PlayedCard> plays) {
        Comparator<Card> highest = Comparator.comparingInt(card -> SkatRules.power(contract, card));
        boolean declaring = context.mySeat == context.game.declarer;
        boolean declarerHasPlayed = false;
        for (SkatAi.PlayedCard play : plays) {
            if (play.seat == context.game.declarer) declarerHasPlayed = true;
        }
        return declaring || declarerHasPlayed ? highest : highest.reversed();
    }
}
