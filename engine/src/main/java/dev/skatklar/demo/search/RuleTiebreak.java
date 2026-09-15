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
 */
final class RuleTiebreak {

    private RuleTiebreak() {}

    /** Greater is better, as the search's own comparators read. */
    static Comparator<Card> order(SkatAi.DecisionContext context) {
        Contract contract = context.game.contract;
        List<SkatAi.PlayedCard> plays = context.currentTrick.plays;
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
}
