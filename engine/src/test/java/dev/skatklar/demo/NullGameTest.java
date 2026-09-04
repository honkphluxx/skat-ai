package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Null is a different game rather than a different trump: no trumps at all, the
 * jacks back in their own suits, the ten back between the nine and the jack, and
 * a contract that is decided by tricks rather than by card points.
 */
public class NullGameTest {

    private static Card card(Card.Suit suit, Card.Rank rank) {
        return new Card(suit, rank);
    }

    private static SkatAi.PlayedCard play(SkatAi.Seat seat, Card card) {
        return new SkatAi.PlayedCard(seat, card);
    }

    @Test
    public void jacksAreOrdinaryCardsOfTheirSuit() {
        assertFalse(Contract.NULL.isTrump(card(Card.Suit.CLUBS, Card.Rank.JACK)));
        List<SkatAi.PlayedCard> trick = List.of(
                play(SkatAi.Seat.HUMAN, card(Card.Suit.HEARTS, Card.Rank.TEN)),
                play(SkatAi.Seat.OPPONENT_ONE, card(Card.Suit.HEARTS, Card.Rank.JACK)),
                play(SkatAi.Seat.OPPONENT_TWO, card(Card.Suit.CLUBS, Card.Rank.JACK)));
        assertEquals(SkatAi.Seat.OPPONENT_ONE, SkatRules.trickWinner(Contract.NULL, trick));
        assertEquals(SkatAi.Seat.OPPONENT_TWO, SkatRules.trickWinner(Contract.GRAND, trick));
    }

    @Test
    public void aJackMustFollowItsOwnSuit() {
        Set<Card> legal = SkatRules.legalCards(Contract.NULL,
                List.of(card(Card.Suit.HEARTS, Card.Rank.JACK),
                        card(Card.Suit.CLUBS, Card.Rank.ACE)),
                List.of(play(SkatAi.Seat.HUMAN, card(Card.Suit.HEARTS, Card.Rank.SEVEN))));
        assertEquals(Set.of(card(Card.Suit.HEARTS, Card.Rank.JACK)), legal);
    }

    @Test
    public void theTenSinksBackBetweenTheNineAndTheJack() {
        List<Card> hand = new ArrayList<>(List.of(
                card(Card.Suit.CLUBS, Card.Rank.NINE),
                card(Card.Suit.CLUBS, Card.Rank.ACE),
                card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.JACK)));
        hand.sort(CardOrder.NULL);
        assertEquals(List.of(
                card(Card.Suit.CLUBS, Card.Rank.ACE),
                card(Card.Suit.CLUBS, Card.Rank.JACK),
                card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.NINE)), hand);
    }

    @Test
    public void oneTrickLosesTheGameHoweverFewPointsItHeld() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        SkatAi.GameDefinition game = new SkatAi.GameDefinition(SkatAi.Seat.HUMAN,
                round.forehand, Contract.NULL, round, 23);
        List<Card> declarerCards = List.of(card(Card.Suit.CLUBS, Card.Rank.SEVEN));

        SkatRules.GameScore clean = SkatRules.score(game, declarerCards, 0,
                Map.of(SkatAi.Seat.HUMAN, 0, SkatAi.Seat.OPPONENT_ONE, 5,
                        SkatAi.Seat.OPPONENT_TWO, 5));
        assertTrue(clean.declarerWon());
        assertEquals(23, clean.gameValue());

        SkatRules.GameScore lost = SkatRules.score(game, declarerCards, 0,
                Map.of(SkatAi.Seat.HUMAN, 1, SkatAi.Seat.OPPONENT_ONE, 5,
                        SkatAi.Seat.OPPONENT_TWO, 4));
        assertFalse(lost.declarerWon());
        assertEquals(-46, lost.gameValue());
    }

    /** A Null bid above its fixed value is simply an overbid; there are no multiples. */
    @Test
    public void anOverbidNullIsLostAtItsOwnValue() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        SkatAi.GameDefinition game = new SkatAi.GameDefinition(SkatAi.Seat.HUMAN,
                round.forehand, Contract.NULL, round, 24);
        SkatRules.GameScore score = SkatRules.score(game, List.of(), 0,
                Map.of(SkatAi.Seat.HUMAN, 0, SkatAi.Seat.OPPONENT_ONE, 5,
                        SkatAi.Seat.OPPONENT_TWO, 5));
        assertFalse(score.declarerWon());
        assertTrue(score.overbid());
        assertEquals(-46, score.gameValue());
    }

    /**
     * A Null game stops being playable the moment it is decided, which is the
     * only game in Skat where that is true.
     */
    @Test
    public void aNullGameEndsAsSoonAsTheDeclarerTakesATrick() {
        GameEngine engine = new GameEngine(new java.util.Random(5),
                new dev.skatklar.demo.ai.LegalRandomAiProvider(new java.util.Random(5)));
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        int guard = 0;
        while (!auction.finished() && guard++ < 200) {
            if (auction.pending() != null) auction.answer(true);
            else auction.step();
        }
        assertEquals(SkatAi.Seat.HUMAN, auction.declarer());
        engine.settleAuction();
        List<Card> twelve = engine.pickUpSkat();
        assertTrue(engine.discard(twelve.get(0), twelve.get(1)));
        assertTrue(engine.declare(Declaration.of(Contract.NULL)));

        int plays = 0;
        while (!engine.snapshot().gameComplete() && plays++ < 60) {
            GameEngine.Snapshot state = engine.snapshot();
            if (state.trickComplete()) engine.finishCompletedTrick();
            else if (state.waitingForHuman()) {
                assertTrue(engine.playHumanCard(state.legalCards.iterator().next()));
            } else engine.playAiCard();
        }
        GameEngine.Snapshot finished = engine.snapshot();
        assertTrue(finished.gameComplete());
        int declarerTricks = finished.result.tricksWon.get(SkatAi.Seat.HUMAN);
        if (declarerTricks > 0) {
            assertFalse(finished.result.declarerWon);
            assertEquals(1, declarerTricks);
            assertTrue("play stops at the deciding trick", finished.history.size() < 10);
        } else {
            assertTrue(finished.result.declarerWon);
            assertEquals(10, finished.history.size());
        }
    }

    /**
     * The view asks "may I collect this trick?" one moment before the engine
     * would say the deal is over, and it must say no to exactly one trick per
     * Null: the one the declarer takes. Miss it and the app sweeps away the
     * very trick the result screen is about to show; claim any other and a
     * trick is left lying on the table in a game that is still running.
     *
     * <p>Two seeds, and the second is the point. Seed 5 is a Null the declarer
     * loses, so it never reaches a tenth trick at all; a predicate that also
     * claimed the tenth would pass on it alone and take the collection flight
     * away from every Null the declarer wins. Seed 24 goes the distance.
     */
    @Test
    public void nullEndsHereClaimsOnlyTheTrickTheDeclarerTakes() {
        assertEquals("lost null", 0, disagreements(Contract.NULL, 5));
        assertEquals("won null", 0, disagreements(Contract.NULL, 24));
    }

    /**
     * The tenth trick of a Null the declarer got through belongs to a defender,
     * and the flight to him is what says so. Spelled out separately from the
     * sweep above because it is the case that was got wrong once.
     */
    @Test
    public void theTenthTrickOfAWonNullIsCollectedLikeAnyOther() {
        GameEngine engine = declaredDeal(Contract.NULL, 24);
        int completed = 0;
        int plays = 0;
        while (!engine.snapshot().gameComplete() && plays++ < 60) {
            GameEngine.Snapshot state = engine.snapshot();
            if (state.trickComplete()) {
                completed++;
                assertFalse("trick " + completed + " still flies", state.nullEndsHere());
                assertEquals("only the tenth ends it", completed == 10,
                        engine.finishCompletedTrick());
            } else if (state.waitingForHuman()) {
                assertTrue(engine.playHumanCard(state.legalCards.iterator().next()));
            } else {
                engine.playAiCard();
            }
        }
        assertEquals("seed 24 has to be a Null that goes the distance", 10, completed);
        assertTrue(engine.snapshot().result.declarerWon);
    }

    /**
     * And it must stay Null's business. A true here in any other contract would
     * take the collection flight away from every game in the app.
     */
    @Test
    public void noOtherContractEverEndsHere() {
        for (Contract contract : new Contract[]{Contract.GRAND, Contract.CLUBS}) {
            assertEquals(contract.name(), 0, disagreements(contract, 5));
        }
    }

    /**
     * Plays a whole deal out and returns how many completed tricks
     * {@code nullEndsHere()} got wrong -- either by disagreeing with "this is a
     * Null and the declarer took it", or by claiming a trick that did not in
     * fact end the deal.
     */
    private static int disagreements(Contract contract, int seed) {
        GameEngine engine = declaredDeal(contract, seed);
        int wrong = 0;
        int tricks = 0;
        int plays = 0;
        while (!engine.snapshot().gameComplete() && plays++ < 60) {
            GameEngine.Snapshot state = engine.snapshot();
            if (state.trickComplete()) {
                boolean predicted = state.nullEndsHere();
                boolean declarerTookIt = state.trickWinner == state.definition.declarer;
                boolean ended = engine.finishCompletedTrick();
                // Only the trick the declarer takes, and only in a Null. Every
                // deal also ends on its tenth trick, and that one is collected
                // like any other: the flight to the winner is wanted there.
                boolean expected = contract.isNull() && declarerTookIt;
                if (predicted != expected) wrong++;
                // Whenever it does claim a trick, the deal really is over.
                if (predicted && !ended) wrong++;
                tricks++;
            } else if (state.waitingForHuman()) {
                assertTrue(engine.playHumanCard(state.legalCards.iterator().next()));
            } else {
                engine.playAiCard();
            }
        }
        assertTrue("the deal has to finish", engine.snapshot().gameComplete());
        assertTrue("and play some tricks", tricks > 0);
        return wrong;
    }

    /** A deal from {@code seed}, bid up to the human seat and declared. */
    private static GameEngine declaredDeal(Contract contract, int seed) {
        GameEngine engine = new GameEngine(new java.util.Random(seed),
                new dev.skatklar.demo.ai.LegalRandomAiProvider(new java.util.Random(seed)));
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        int guard = 0;
        while (!auction.finished() && guard++ < 200) {
            if (auction.pending() != null) auction.answer(true);
            else auction.step();
        }
        assertEquals(SkatAi.Seat.HUMAN, auction.declarer());
        engine.settleAuction();
        List<Card> twelve = engine.pickUpSkat();
        assertTrue(engine.discard(twelve.get(0), twelve.get(1)));
        assertTrue(engine.declare(Declaration.of(contract)));
        return engine;
    }

    @Test
    public void announcementRulesFollowTheContract() {
        assertTrue(SkatRules.legalAnnouncement(Contract.NULL, false, false, false, true));
        assertTrue(SkatRules.legalAnnouncement(Contract.NULL, true, false, false, true));
        assertFalse(SkatRules.legalAnnouncement(Contract.NULL, true, true, false, false));
        assertTrue(SkatRules.legalAnnouncement(Contract.GRAND, true, false, false, false));
        assertFalse(SkatRules.legalAnnouncement(Contract.GRAND, false, true, false, false));
        assertFalse(SkatRules.legalAnnouncement(Contract.GRAND, true, false, true, false));
        assertFalse(SkatRules.legalAnnouncement(Contract.GRAND, true, true, false, true));
        assertTrue(SkatRules.legalAnnouncement(Contract.GRAND, true, true, true, true));
    }

    /** Announcing something and then missing it loses the game outright. */
    @Test
    public void aMissedAnnouncementLosesAWonGame() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        List<Card> declarerCards = List.of(
                card(Card.Suit.CLUBS, Card.Rank.JACK), card(Card.Suit.SPADES, Card.Rank.JACK));
        Map<SkatAi.Seat, Integer> tricks = Map.of(SkatAi.Seat.HUMAN, 8,
                SkatAi.Seat.OPPONENT_ONE, 1, SkatAi.Seat.OPPONENT_TWO, 1);
        SkatAi.GameDefinition announced = new SkatAi.GameDefinition(SkatAi.Seat.HUMAN,
                round.forehand, Contract.CLUBS, round, 24, true, true, false, false);

        SkatRules.GameScore missed = SkatRules.score(announced, declarerCards, 75, tricks);
        assertFalse(missed.declarerWon());
        assertEquals(-120, missed.gameValue());

        SkatRules.GameScore kept = SkatRules.score(announced, declarerCards, 95, tricks);
        assertTrue(kept.declarerWon());
        // with 2, game, hand, schneider made, schneider announced: 12 x 6.
        assertEquals(72, kept.gameValue());
    }
}
