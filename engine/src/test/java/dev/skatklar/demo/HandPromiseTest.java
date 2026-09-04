package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.search.HandEvaluator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * What the cheap pre-filter is allowed to say about a hand.
 *
 * <p>It decides which contracts get a solve spent on them, so anything it will
 * not rank is a contract this player cannot declare — which is how the app went
 * 3053 auditioned deals without a single Null. These are the two properties that
 * failure came from.
 */
public final class HandPromiseTest {

    /** Four sevens, four eights, two nines: it cannot be forced to take a trick. */
    private static List<Card> perfectNull() {
        List<Card> hand = new ArrayList<>();
        for (Card.Suit suit : Card.Suit.values()) {
            hand.add(new Card(suit, Card.Rank.SEVEN));
            hand.add(new Card(suit, Card.Rank.EIGHT));
        }
        hand.add(new Card(Card.Suit.CLUBS, Card.Rank.NINE));
        hand.add(new Card(Card.Suit.SPADES, Card.Rank.NINE));
        return hand;
    }

    @Test
    public void matadorsCountOnlyWhenTheyAreHeld() {
        List<Card> withoutThem = perfectNull();
        assertFalse("no club jack, so this is a hand without matadors",
                SkatRules.withMatadors(Contract.CLUBS, withoutThem));
        // The value is unchanged -- "without nine, game ten" really is worth
        // ninety. Only the guess about how it will play must not be.
        assertTrue(SkatRules.guaranteedValue(Contract.CLUBS, withoutThem) > 90);

        List<Card> withThem = new ArrayList<>(withoutThem.subList(0, 9));
        withThem.add(new Card(Card.Suit.CLUBS, Card.Rank.JACK));
        assertTrue(SkatRules.withMatadors(Contract.CLUBS, withThem));
    }

    @Test
    public void aHandThatCannotWinATrickIsNotAPromisingTrumpGame() {
        List<Card> hand = perfectNull();
        List<Contract> trumpGames = HandEvaluator.plausibleTrumpGames(hand, 5);
        List<Contract> everything = HandEvaluator.plausible(hand, 6);
        assertFalse("Null must not be offered as a trump game",
                trumpGames.contains(Contract.NULL));
        assertEquals("the best holding for a Null is ranked as one",
                Contract.NULL, everything.get(0));
    }

    /**
     * The tie-break. Trump length, aces and matadors are exchangeable across the
     * four suits, so any preference the ranking shows between them is its own
     * arithmetic and not the cards. It used to be the order the enum happens to
     * declare them in, which made the app declare Diamonds half again as often
     * as Clubs.
     */
    @Test
    public void aTieBetweenSuitGamesGoesToTheDearerOne() {
        // Two suits, two cards each, nothing else to separate them.
        List<Card> hand = new ArrayList<>();
        for (Card.Rank rank : new Card.Rank[] {Card.Rank.SEVEN, Card.Rank.EIGHT}) {
            hand.add(new Card(Card.Suit.CLUBS, rank));
            hand.add(new Card(Card.Suit.DIAMONDS, rank));
        }
        List<Contract> ranked = HandEvaluator.plausibleTrumpGames(hand, 4);
        assertEquals("Clubs and Diamonds are equal here, so the dearer wins",
                Contract.CLUBS, ranked.get(0));
        assertTrue(ranked.indexOf(Contract.CLUBS) < ranked.indexOf(Contract.DIAMONDS));
    }

    /**
     * Which suit is favoured, and why it is allowed to be.
     *
     * <p>Trump length, aces and matadors are exchangeable across the four suits,
     * so a preference between them is the ranking's own arithmetic. The old one
     * preferred whichever came first in {@code DECLARED_GAMES} and produced
     * Diamonds 738 against Clubs 547 in the seed pool — value thrown away by an
     * accident of enum order.
     *
     * <p>The fix does not flatten that. It <em>points it the other way</em>, and
     * that is the point: on cards the scale cannot tell apart, the dearer game
     * is the better one to spend a solve on. So the property to hold is not
     * "no suit is favoured" but "no cheaper suit is favoured over a dearer one".
     */
    @Test
    public void aFavouredSuitIsAlwaysTheDearerOne() {
        Rng rng = Rng.withSeed(4242L);
        Map<Contract, Integer> firsts = new EnumMap<>(Contract.class);
        for (int deal = 0; deal < 4000; deal++) {
            SkatDeck.Deal dealt = SkatDeck.deal(rng);
            for (List<Card> hand : List.of(dealt.human, dealt.opponentOne, dealt.opponentTwo)) {
                Contract best = HandEvaluator.plausibleTrumpGames(hand, 1).get(0);
                if (best != Contract.GRAND) firsts.merge(best, 1, Integer::sum);
            }
        }
        List<Contract> dearestFirst = List.of(Contract.CLUBS, Contract.SPADES,
                Contract.HEARTS, Contract.DIAMONDS);
        for (int i = 1; i < dearestFirst.size(); i++) {
            Contract dearer = dearestFirst.get(i - 1);
            Contract cheaper = dearestFirst.get(i);
            assertTrue(dearer + " must not be chosen less often than " + cheaper
                            + ": " + firsts,
                    firsts.getOrDefault(dearer, 0) >= firsts.getOrDefault(cheaper, 0));
        }
    }
}
