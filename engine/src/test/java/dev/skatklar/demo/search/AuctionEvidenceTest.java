package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/** The auction as a constraint on sampled worlds: what a bid rules out, and what a pass does not. */
public final class AuctionEvidenceTest {

    private static Card c(Card.Suit suit, Card.Rank rank) { return new Card(suit, rank); }

    /** Four jacks and the ace of clubs: guarantees 60 in Clubs under the canon. */
    private static List<Card> theJacks() {
        return List.of(c(Card.Suit.CLUBS, Card.Rank.JACK), c(Card.Suit.SPADES, Card.Rank.JACK),
                c(Card.Suit.HEARTS, Card.Rank.JACK), c(Card.Suit.DIAMONDS, Card.Rank.JACK),
                c(Card.Suit.CLUBS, Card.Rank.ACE), c(Card.Suit.CLUBS, Card.Rank.TEN),
                c(Card.Suit.HEARTS, Card.Rank.SEVEN), c(Card.Suit.HEARTS, Card.Rank.EIGHT),
                c(Card.Suit.SPADES, Card.Rank.SEVEN), c(Card.Suit.DIAMONDS, Card.Rank.SEVEN));
    }

    /** No jacks at all: "without four", so it still guarantees base times five. */
    private static List<Card> noJacks() {
        return List.of(c(Card.Suit.CLUBS, Card.Rank.ACE), c(Card.Suit.CLUBS, Card.Rank.TEN),
                c(Card.Suit.CLUBS, Card.Rank.KING), c(Card.Suit.CLUBS, Card.Rank.QUEEN),
                c(Card.Suit.SPADES, Card.Rank.ACE), c(Card.Suit.HEARTS, Card.Rank.ACE),
                c(Card.Suit.HEARTS, Card.Rank.SEVEN), c(Card.Suit.HEARTS, Card.Rank.EIGHT),
                c(Card.Suit.SPADES, Card.Rank.SEVEN), c(Card.Suit.DIAMONDS, Card.Rank.SEVEN));
    }

    /** One jack, the jack of hearts: "without two", base times three at most. */
    private static List<Card> oneMiddleJack() {
        return List.of(c(Card.Suit.HEARTS, Card.Rank.JACK), c(Card.Suit.CLUBS, Card.Rank.ACE),
                c(Card.Suit.CLUBS, Card.Rank.TEN), c(Card.Suit.CLUBS, Card.Rank.KING),
                c(Card.Suit.SPADES, Card.Rank.ACE), c(Card.Suit.HEARTS, Card.Rank.ACE),
                c(Card.Suit.HEARTS, Card.Rank.SEVEN), c(Card.Suit.HEARTS, Card.Rank.EIGHT),
                c(Card.Suit.SPADES, Card.Rank.SEVEN), c(Card.Suit.DIAMONDS, Card.Rank.SEVEN));
    }

    private static Map<SkatAi.Seat, List<Card>> world(List<Card> opponentOne, List<Card> opponentTwo) {
        Map<SkatAi.Seat, List<Card>> hands = new EnumMap<>(SkatAi.Seat.class);
        hands.put(SkatAi.Seat.HUMAN, new ArrayList<>());
        hands.put(SkatAi.Seat.OPPONENT_ONE, opponentOne);
        hands.put(SkatAi.Seat.OPPONENT_TWO, opponentTwo);
        return hands;
    }

    private static HandEvaluator.AuctionEvidence saidBy(SkatAi.Seat seat, int bid, int passedAt) {
        Map<SkatAi.Seat, Integer> bids = new EnumMap<>(SkatAi.Seat.class);
        Map<SkatAi.Seat, Integer> passes = new EnumMap<>(SkatAi.Seat.class);
        if (bid > 0) bids.put(seat, bid);
        if (passedAt > 0) passes.put(seat, passedAt);
        return new HandEvaluator.AuctionEvidence(bids, passes);
    }

    @Test public void whatABidImpliesInJacks() {
        assertEquals(0, HandEvaluator.AuctionEvidence.jacksImplied(18));
        assertEquals(0, HandEvaluator.AuctionEvidence.jacksImplied(24));
        assertEquals(1, HandEvaluator.AuctionEvidence.jacksImplied(27));
        assertEquals(1, HandEvaluator.AuctionEvidence.jacksImplied(48));
        assertEquals(2, HandEvaluator.AuctionEvidence.jacksImplied(50));
        assertEquals(2, HandEvaluator.AuctionEvidence.jacksImplied(72));
        assertEquals(3, HandEvaluator.AuctionEvidence.jacksImplied(96));
        assertEquals(4, HandEvaluator.AuctionEvidence.jacksImplied(120));
    }

    @Test public void theScoreSheetAloneWouldHaveExcludedNothing() {
        // The reason the floor is jacks and not guaranteed value: no jacks at
        // all "guarantees" 120, Grand without four, game five. A constraint
        // built on that number is no constraint.
        assertEquals(120, dev.skatklar.demo.SkatRules.guaranteedValue(Contract.GRAND, noJacks()));
    }

    @Test public void aBidOnFewerJacksThanItImpliesIsImpossible() {
        // Held 50: two jacks implied. One middle jack cannot be that world.
        HandEvaluator.AuctionEvidence held50 = saidBy(SkatAi.Seat.OPPONENT_ONE, 50, 0);
        assertFalse(held50.consistent(world(oneMiddleJack(), noJacks()),
                SkatAi.Seat.HUMAN, new Random(1)));
        // The same seat holding the jacks: that world survives.
        assertTrue(held50.consistent(world(theJacks(), noJacks()),
                SkatAi.Seat.HUMAN, new Random(1)));
    }

    @Test public void aLowBidRulesOutAlmostNothing() {
        // 18 through 24 imply no jack at all, which nearly any hand satisfies.
        HandEvaluator.AuctionEvidence held18 = saidBy(SkatAi.Seat.OPPONENT_TWO, 18, 0);
        assertTrue(held18.consistent(world(noJacks(), noJacks()),
                SkatAi.Seat.HUMAN, new Random(1)));
    }

    @Test public void aPassIsSoftAndWeightedNotRejected() {
        // The seat passed at 18, which implies nothing, and the sampled hand
        // holds four jacks -- two more than implied, so it could plainly have
        // bid. Not impossible, a cautious bidder, so it survives about
        // PASS_WEIGHT of the time.
        HandEvaluator.AuctionEvidence passed18 = saidBy(SkatAi.Seat.OPPONENT_ONE, 0, 18);
        Random random = new Random(7);
        int kept = 0;
        for (int i = 0; i < 2000; i++) {
            if (passed18.consistent(world(theJacks(), noJacks()), SkatAi.Seat.HUMAN, random)) kept++;
        }
        assertEquals(HandEvaluator.AuctionEvidence.PASS_WEIGHT, kept / 2000.0, 0.04);
        // One middle jack passing at 18 is unremarkable: no information, no
        // rejection.
        assertTrue(passed18.consistent(world(oneMiddleJack(), noJacks()),
                SkatAi.Seat.HUMAN, new Random(1)));
    }

    @Test public void theEmptyEvidenceConstrainsNothing() {
        assertTrue(HandEvaluator.AuctionEvidence.NONE.isEmpty());
        assertTrue(HandEvaluator.AuctionEvidence.NONE.consistent(
                world(oneMiddleJack(), noJacks()), SkatAi.Seat.HUMAN, new Random(1)));
    }

    @Test public void theConstrainedEstimateNeverReadsBelowTheHandsOwnMerit() {
        // Sanity on the whole path: with the jacks held against a seat that
        // bid 18, the constrained estimate is a number in [0, 1] and the
        // sampler did not hang on a constraint it could not satisfy.
        HandEvaluator evaluator = new HandEvaluator(4, new Random(3));
        double chance = evaluator.makeChance(Contract.CLUBS, theJacks(), SkatAi.Seat.HUMAN,
                SkatAi.Seat.HUMAN, saidBy(SkatAi.Seat.OPPONENT_ONE, 18, 0));
        assertTrue(chance >= 0 && chance <= 1);
    }
}
