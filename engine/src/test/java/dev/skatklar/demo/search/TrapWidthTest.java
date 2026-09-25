package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * {@link SearchAiProvider#trapWidth} against a brute-force game tree.
 *
 * <p>Three-card endgames, dealt at random, with the declarer to move after
 * zero, one or two cards of the current trick -- so every path the width
 * takes is exercised: the next defender replying inside the trick, the
 * trick completing with a defender on lead, and the trick completing with
 * the declarer on lead again. The tree below shares the definition of the
 * width (whose replies count, and the widest lead when the declarer leads
 * again) and nothing else: its values come from a plain minimax over the
 * rules, not from the solver, and its trick bookkeeping is its own.
 */
public class TrapWidthTest {

    private static final Contract[] CONTRACTS = {
            Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS, Contract.GRAND};

    @Test public void theWidthIsTheShareOfRepliesThatHandTheGameBack() {
        Random random = new Random(20260925L);
        int inTrick = 0, defenderLeads = 0, declarerLeadsAgain = 0, flatZero = 0, compared = 0;
        for (int position = 0; position < 400; position++) {
            Contract contract = CONTRACTS[random.nextInt(CONTRACTS.length)];
            SkatAi.Seat declarer = SkatAi.Seat.values()[random.nextInt(3)];
            SkatAi.Seat leader = SkatAi.Seat.values()[random.nextInt(3)];
            List<Card> deck = new ArrayList<>(SkatDeck.ordered());
            Collections.shuffle(deck, random);
            List<List<Card>> hands = new ArrayList<>();
            for (int seat = 0; seat < 3; seat++) hands.add(new ArrayList<>(deck.subList(seat * 3, seat * 3 + 3)));
            // The trick so far: from the leader up to the declarer.
            List<SkatAi.PlayedCard> trick = new ArrayList<>();
            for (SkatAi.Seat seat = leader; seat != declarer; seat = seat.next()) {
                List<Card> legal = new ArrayList<>(SkatRules.legalCards(contract, hands.get(seat.ordinal()), trick));
                Card card = legal.get(random.nextInt(legal.size()));
                hands.get(seat.ordinal()).remove(card);
                trick.add(new SkatAi.PlayedCard(seat, card));
            }
            List<Card> trickCards = new ArrayList<>();
            for (SkatAi.PlayedCard play : trick) trickCards.add(play.card);

            List<Card> legal = new ArrayList<>(SkatRules.legalCards(contract, hands.get(declarer.ordinal()), trick));
            int best = 0;
            for (Card card : legal) best = Math.max(best, afterMove(contract, declarer, hands, trick, declarer, card));
            for (int needed : new int[] {best + 1, Math.max(1, best), 1 + random.nextInt(best + 1)}) {
                if (needed == best + 1) flatZero++;
                for (Card card : legal) {
                    double expected = bruteWidth(contract, declarer, hands, trick, needed, card);
                    double got = SearchAiProvider.trapWidth(contract, declarer, declarer, hands,
                            leader, trickCards, needed, card);
                    assertEquals(contract + " " + declarer + " led by " + leader + " " + trickCards
                            + " hands " + hands + " needed " + needed + " card " + card,
                            expected, got, 1e-9);
                    compared++;
                    if (trick.size() < 2) inTrick++;
                    else if (winnerAfter(contract, trick, declarer, card) == declarer) declarerLeadsAgain++;
                    else defenderLeads++;
                }
            }
        }
        assertTrue("positions at flat zero: " + flatZero, flatZero >= 300);
        assertTrue("replies inside the trick: " + inTrick, inTrick > 200);
        assertTrue("tricks completed, a defender on lead: " + defenderLeads, defenderLeads > 50);
        assertTrue("tricks completed, the declarer on lead again: " + declarerLeadsAgain, declarerLeadsAgain > 50);
        assertTrue(compared > 1000);
    }

    // ------------------------------------------------------------ the brute force

    private static SkatAi.Seat winnerAfter(Contract contract, List<SkatAi.PlayedCard> trick,
                                          SkatAi.Seat seat, Card card) {
        List<SkatAi.PlayedCard> full = new ArrayList<>(trick);
        full.add(new SkatAi.PlayedCard(seat, card));
        return SkatRules.trickWinner(contract, full);
    }

    private static double bruteWidth(Contract contract, SkatAi.Seat declarer, List<List<Card>> hands,
                                     List<SkatAi.PlayedCard> trick, int needed, Card card) {
        List<List<Card>> after = copy(hands);
        after.get(declarer.ordinal()).remove(card);
        List<SkatAi.PlayedCard> played = new ArrayList<>(trick);
        played.add(new SkatAi.PlayedCard(declarer, card));
        if (played.size() < 3) return giftShare(contract, declarer, after, played, declarer.next(), needed);
        SkatAi.Seat winner = SkatRules.trickWinner(contract, played);
        int points = 0;
        for (SkatAi.PlayedCard play : played) points += SkatRules.cardPoints(play.card);
        int still = winner == declarer ? needed - points : needed;
        if (still < 1) return 1.0;
        if (winner != declarer) return giftShare(contract, declarer, after, List.of(), winner, still);
        double widest = 0;
        for (Card lead : after.get(declarer.ordinal())) {
            widest = Math.max(widest, bruteWidth(contract, declarer, after, List.of(), still, lead));
        }
        return widest;
    }

    private static double giftShare(Contract contract, SkatAi.Seat declarer, List<List<Card>> hands,
                                    List<SkatAi.PlayedCard> trick, SkatAi.Seat defender, int needed) {
        List<Card> replies = new ArrayList<>(SkatRules.legalCards(contract, hands.get(defender.ordinal()), trick));
        if (replies.isEmpty()) return 0;
        int gifts = 0;
        for (Card reply : replies) {
            if (afterMove(contract, declarer, hands, trick, defender, reply) >= needed) gifts++;
        }
        return gifts / (double) replies.size();
    }

    /** The declarer's card points from the current trick on, after {@code seat} plays {@code card}. */
    private static int afterMove(Contract contract, SkatAi.Seat declarer, List<List<Card>> hands,
                                 List<SkatAi.PlayedCard> trick, SkatAi.Seat seat, Card card) {
        List<List<Card>> after = copy(hands);
        after.get(seat.ordinal()).remove(card);
        List<SkatAi.PlayedCard> played = new ArrayList<>(trick);
        played.add(new SkatAi.PlayedCard(seat, card));
        return value(contract, declarer, after, played, seat.next());
    }

    /** Minimax: the declarer's card points from the current trick on. */
    private static int value(Contract contract, SkatAi.Seat declarer, List<List<Card>> hands,
                             List<SkatAi.PlayedCard> trick, SkatAi.Seat toPlay) {
        if (trick.size() == 3) {
            SkatAi.Seat winner = SkatRules.trickWinner(contract, trick);
            int points = 0;
            for (SkatAi.PlayedCard play : trick) points += SkatRules.cardPoints(play.card);
            int here = winner == declarer ? points : 0;
            if (hands.get(winner.ordinal()).isEmpty()) return here;
            return here + value(contract, declarer, hands, List.of(), winner);
        }
        List<Card> legal = new ArrayList<>(SkatRules.legalCards(contract, hands.get(toPlay.ordinal()), trick));
        boolean maximise = toPlay == declarer;
        int best = maximise ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (Card card : legal) {
            int v = afterMove(contract, declarer, hands, trick, toPlay, card);
            best = maximise ? Math.max(best, v) : Math.min(best, v);
        }
        return best;
    }

    private static List<List<Card>> copy(List<List<Card>> hands) {
        List<List<Card>> copy = new ArrayList<>(3);
        for (List<Card> hand : hands) copy.add(new ArrayList<>(hand));
        return copy;
    }
}
