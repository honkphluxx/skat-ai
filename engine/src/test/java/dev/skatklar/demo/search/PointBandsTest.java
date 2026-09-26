package dev.skatklar.demo.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * {@link SearchAiProvider#withPointBands()}: the band score of a card in one
 * world, the choice made from the scores of all of them, and that the player
 * asks for it.
 *
 * <p>The band scores are held against a plain minimax over the rules on
 * three- and four-card endgames -- the declarer's exact final total after
 * each card, counted against {@link SearchAiProvider#BAND_POINTS} -- for the
 * declarer and for a defender, mid-trick and on lead. The choice is held to
 * its contract on hand-made score tables. The game test plays deals with the
 * bands on and off and requires them to change some card, which no test of
 * the helpers can see.
 */
public class PointBandsTest {

    private static final Contract[] CONTRACTS = {
            Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS, Contract.GRAND};

    @Test public void theBandScoreCountsTheBandsTheExactTotalReaches() {
        Random random = new Random(20260926L);
        int checked = 0, asDefender = 0, varied = 0;
        for (int position = 0; position < 300; position++) {
            Contract contract = CONTRACTS[random.nextInt(CONTRACTS.length)];
            SkatAi.Seat declarer = SkatAi.Seat.values()[random.nextInt(3)];
            SkatAi.Seat leader = SkatAi.Seat.values()[random.nextInt(3)];
            SkatAi.Seat me = SkatAi.Seat.values()[random.nextInt(3)];
            List<Card> deck = new ArrayList<>(SkatDeck.ordered());
            Collections.shuffle(deck, random);
            int size = 3 + random.nextInt(2);
            List<List<Card>> hands = new ArrayList<>();
            for (int seat = 0; seat < 3; seat++) hands.add(new ArrayList<>(deck.subList(seat * size, seat * size + size)));
            List<SkatAi.PlayedCard> trick = new ArrayList<>();
            for (SkatAi.Seat seat = leader; seat != me; seat = seat.next()) {
                List<Card> legal = new ArrayList<>(SkatRules.legalCards(contract, hands.get(seat.ordinal()), trick));
                Card card = legal.get(random.nextInt(legal.size()));
                hands.get(seat.ordinal()).remove(card);
                trick.add(new SkatAi.PlayedCard(seat, card));
            }
            List<Card> trickCards = new ArrayList<>();
            int remaining = 0;
            for (SkatAi.PlayedCard play : trick) { trickCards.add(play.card); remaining += SkatRules.cardPoints(play.card); }
            for (List<Card> hand : hands) remaining += SkatRules.cardPoints(hand);
            int banked = random.nextInt(95);
            List<Card> legal = new ArrayList<>(SkatRules.legalCards(contract, hands.get(me.ordinal()), trick));

            int[] got = SearchAiProvider.bandScores(contract, declarer, me, hands, leader, trickCards,
                    banked, remaining, legal);
            int[] expected = new int[legal.size()];
            for (int i = 0; i < legal.size(); i++) {
                int total = banked + afterMove(contract, declarer, hands, trick, me, legal.get(i));
                for (int points : SearchAiProvider.BAND_POINTS) {
                    if ((total >= points) == (me == declarer)) expected[i]++;
                }
            }
            assertArrayEquals(contract + " " + declarer + " to move " + me + " banked " + banked
                    + " trick " + trickCards + " hands " + hands, expected, got);
            checked++;
            if (me != declarer) asDefender++;
            for (int i = 1; i < expected.length; i++) if (expected[i] != expected[0]) { varied++; break; }
        }
        assertTrue("positions: " + checked, checked == 300);
        assertTrue("as defender: " + asDefender, asDefender > 100);
        assertTrue("positions where the cards' scores differ: " + varied, varied >= 50);
    }

    @Test public void aCloseCardReplacesTheVotesCardOnlyWithASignificantLead() {
        Card a = new Card(Card.Suit.HEARTS, Card.Rank.TEN), b = new Card(Card.Suit.HEARTS, Card.Rank.SEVEN),
                c = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        List<Card> cards = List.of(a, b, c);
        Map<Card, Integer> votes = new LinkedHashMap<>();
        votes.put(a, 20); votes.put(b, 18); votes.put(c, 5);
        // b leads a by one band in every world: zero variance, a lead -- replaced.
        int[][] steady = new int[8][];
        for (int w = 0; w < 8; w++) steady[w] = new int[] {2, 3, 5};
        assertEquals("c is far behind in the vote, b is close and always better", b,
                SearchAiProvider.bandChoice(cards, votes, a, steady, 3, 2.0));
        // b leads a by one band in half the worlds and trails by one in a quarter: t about 1.
        int[][] noisy = new int[8][];
        for (int w = 0; w < 8; w++) noisy[w] = new int[] {2, w < 4 ? 3 : w < 6 ? 1 : 2, 0};
        assertEquals("not significant: the vote's card stays", a,
                SearchAiProvider.bandChoice(cards, votes, a, noisy, 3, 2.0));
        // Out of the close range the best band score does not count.
        Map<Card, Integer> apart = new LinkedHashMap<>(votes);
        apart.put(b, 16);
        assertEquals("b is four worlds behind", a,
                SearchAiProvider.bandChoice(cards, apart, a, steady, 3, 2.0));
        // Equal sums keep the incumbent.
        int[][] level = new int[4][];
        for (int w = 0; w < 4; w++) level[w] = new int[] {3, 3, 0};
        assertEquals(a, SearchAiProvider.bandChoice(cards, votes, a, level, 3, 2.0));
        // Significant at a looser t, not at 2.
        int[][] lean = new int[10][];
        for (int w = 0; w < 10; w++) lean[w] = new int[] {2, w < 6 ? 3 : w < 8 ? 2 : 1, 0};
        assertEquals(a, SearchAiProvider.bandChoice(cards, votes, a, lean, 3, 2.0));
        assertEquals(b, SearchAiProvider.bandChoice(cards, votes, a, lean, 3, 1.0));
    }

    /**
     * Sixteen worlds. At four, a lead of one band in one world is all a close
     * call ever showed, and the paired test never fired in twelve deals --
     * which is the step doing its job, and no test of whether it is asked.
     */
    private static final Personality QUICK = new Personality(16, 1.0, 0.0, 0.5);

    private static List<Card> cardsOfSearchSeats(boolean bands, int deal) {
        List<Card> played = new ArrayList<>();
        SkatAi.Seat declarer = SkatAi.Seat.values()[deal % 3];
        Map<SkatAi.Seat, SkatAiProvider> seats = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            // The declarer greedy; both defenders the search player, so the bands are asked from their side.
            if (seat == declarer) { seats.put(seat, new GreedyAiProvider()); continue; }
            SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(), QUICK,
                    2000L + deal * 3L + seat.ordinal(), WorldSource.UNIFORM).withMarginTiebreak(15).withLadder();
            if (bands) player = player.withPointBands();
            seats.put(seat, player.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                @Override public void decided(SearchAiProvider.CardPlayReport report) {}
                @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                            Map<Card, Integer> cushion, int worlds,
                                            boolean cushionAsked, Card chosen) {
                    synchronized (played) { played.add(chosen); }
                }
            }));
        }
        GameEngine engine = GameEngine.headless(new Random(deal), SeatedAiProviders.of(seats));
        engine.restartWithContract(SkatDeck.deal(new Random(9_000L + deal)), SkatAi.RoundPosition.at(0),
                declarer, CONTRACTS[deal % 5], 18, Set.of());
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot now = engine.snapshot();
            if (now.gameComplete()) break;
            if (now.trickComplete()) engine.finishCompletedTrick(); else engine.playAiCard();
        }
        engine.close();
        return played;
    }

    @Test public void theBandsReachTheTable() {
        for (int deal = 0; deal < 12; deal++) {
            if (!cardsOfSearchSeats(false, deal).equals(cardsOfSearchSeats(true, deal))) return;
        }
        assertTrue("twelve deals and the bands never changed a defender's card", false);
    }

    /** The declarer's card points from the rest of the play after {@code mover} plays {@code card}, by minimax. */
    private static int afterMove(Contract contract, SkatAi.Seat declarer, List<List<Card>> hands,
                                 List<SkatAi.PlayedCard> trick, SkatAi.Seat mover, Card card) {
        List<List<Card>> h = copy(hands);
        h.get(mover.ordinal()).remove(card);
        List<SkatAi.PlayedCard> t = new ArrayList<>(trick);
        t.add(new SkatAi.PlayedCard(mover, card));
        if (t.size() == 3) {
            SkatAi.Seat winner = SkatRules.trickWinner(contract, t);
            int points = 0;
            for (SkatAi.PlayedCard play : t) points += SkatRules.cardPoints(play.card);
            return (winner == declarer ? points : 0) + value(contract, declarer, h, new ArrayList<>(), winner);
        }
        return value(contract, declarer, h, t, mover.next());
    }

    private static int value(Contract contract, SkatAi.Seat declarer, List<List<Card>> hands,
                             List<SkatAi.PlayedCard> trick, SkatAi.Seat mover) {
        if (hands.get(mover.ordinal()).isEmpty()) return 0;
        boolean maximise = mover == declarer;
        int best = maximise ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (Card card : SkatRules.legalCards(contract, hands.get(mover.ordinal()), trick)) {
            int v = afterMove(contract, declarer, hands, trick, mover, card);
            best = maximise ? Math.max(best, v) : Math.min(best, v);
        }
        return best;
    }

    private static List<List<Card>> copy(List<List<Card>> hands) {
        List<List<Card>> out = new ArrayList<>();
        for (List<Card> hand : hands) out.add(new ArrayList<>(hand));
        return out;
    }
}
