package dev.skatklar.demo.ramsch;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What passing is worth, in points, on this particular hand.
 *
 * <p>The other half of every bidding decision. Under classic settlement a game
 * declared and won pays its value and a game lost costs twice it, so with a
 * re-deal as the alternative the break-even make chance is a flat two thirds.
 * There is no re-deal any more: the alternative to declaring is a Ramsch, and a
 * Ramsch is not worth zero. It has to be priced, and it cannot be priced by a
 * constant, because the hands that make a Ramsch dangerous are the same hands
 * that make it a marginal game — jacks, aces and tens.
 *
 * <p>So it is played out. Deal the twenty-two cards this seat cannot see at
 * random, push as everybody pushes, play the ten tricks with
 * {@link RamschPolicy}, settle with {@link SkatRules#scoreRamsch}, and average
 * what this seat paid. Cheap enough to do inside an auction: a playout is thirty
 * heuristic decisions and no search at all, next to nothing beside the
 * double-dummy solves the make chance already costs.
 *
 * <p>What it is not: an estimate of how a <em>good</em> Ramsch would go. It
 * prices the Ramsch this table would actually play, which is the one that would
 * actually happen — and when the heuristic is replaced by a real three-player
 * search, this number moves with it, on its own, which is the property worth
 * having.
 */
public final class RamschEvaluator {

    private RamschEvaluator() {}

    /**
     * Mean signed score for {@code mySeat} over sampled Ramsch playouts.
     *
     * <p>Negative almost always — a Ramsch is something one of three people
     * loses — and near zero only for a hand that can duck out of every trick.
     * Positive is possible and rare: a hand that can march through.
     *
     * @param myHand   the ten cards this seat holds
     * @param forehand who leads, and who starts the Schieben
     * @param worlds   playouts to average over; the estimate is noisy below four
     */
    public static double expectedValue(SkatAi.Seat mySeat, Collection<Card> myHand,
                                       SkatAi.Seat forehand, int worlds, Random random) {
        List<Card> unseen = new ArrayList<>(SkatDeck.ordered());
        unseen.removeAll(new LinkedHashSet<>(myHand));
        if (unseen.size() != 22 || myHand.size() != 10) {
            throw new IllegalArgumentException(
                    "A Ramsch is priced before the skat is touched: ten cards in hand");
        }

        double total = 0;
        for (int world = 0; world < Math.max(1, worlds); world++) {
            Collections.shuffle(unseen, random);
            Map<SkatAi.Seat, List<Card>> hands = new EnumMap<>(SkatAi.Seat.class);
            hands.put(mySeat, new ArrayList<>(myHand));
            SkatAi.Seat left = mySeat.next();
            SkatAi.Seat right = left.next();
            hands.put(left, new ArrayList<>(unseen.subList(0, 10)));
            hands.put(right, new ArrayList<>(unseen.subList(10, 20)));
            List<Card> skat = new ArrayList<>(unseen.subList(20, 22));

            total += playOut(hands, skat, forehand, mySeat);
        }
        return total / Math.max(1, worlds);
    }

    /** One Ramsch from the Schieben to the settlement; returns what {@code me} scored. */
    private static double playOut(Map<SkatAi.Seat, List<Card>> hands, List<Card> skat,
                                  SkatAi.Seat forehand, SkatAi.Seat me) {
        schieben(hands, skat, forehand);

        List<SkatAi.CompletedTrick> tricks = new ArrayList<>(10);
        SkatAi.Seat leader = forehand;
        for (int number = 0; number < 10; number++) {
            List<SkatAi.PlayedCard> plays = new ArrayList<>(3);
            List<Card> trickSoFar = new ArrayList<>(3);
            SkatAi.Seat seat = leader;
            for (int turn = 0; turn < 3; turn++) {
                List<Card> hand = hands.get(seat);
                Set<Card> legal = SkatRules.legalCards(Contract.RAMSCH, hand, plays);
                Card card = RamschPolicy.chooseCard(legal, trickSoFar);
                hand.remove(card);
                plays.add(new SkatAi.PlayedCard(seat, card));
                trickSoFar.add(card);
                seat = seat.next();
            }
            SkatAi.Seat winner = SkatRules.trickWinner(Contract.RAMSCH, plays);
            int points = 0;
            for (SkatAi.PlayedCard play : plays) points += SkatRules.cardPoints(play.card);
            tricks.add(new SkatAi.CompletedTrick(number, leader, plays, winner, points));
            leader = winner;
        }

        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricks, skat);
        return score.scoredSeat() == me ? score.value() : 0;
    }

    /**
     * The three legs, in place: the skat list ends up holding rearhand's push,
     * which is what the game is settled against.
     */
    private static void schieben(Map<SkatAi.Seat, List<Card>> hands, List<Card> skat,
                                 SkatAi.Seat forehand) {
        SkatAi.Seat seat = forehand;
        List<Card> carried = new ArrayList<>(skat);
        for (int leg = 0; leg < 3; leg++) {
            List<Card> hand = hands.get(seat);
            hand.addAll(carried);
            Set<Card> pushed = SkatRules.defaultRamschPush(hand);
            hand.removeAll(pushed);
            carried = new ArrayList<>(pushed);
            seat = seat.next();
        }
        skat.clear();
        skat.addAll(carried);
    }
}
