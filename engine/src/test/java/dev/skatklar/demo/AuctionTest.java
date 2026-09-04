package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The auction as a state machine: who is asked what, in which order, and what
 * ends up in the log. The log is load-bearing — a seat that dropped out at 20
 * told the table something, and no input convenience may swallow it.
 */
public class AuctionTest {

    /** A bidder that accepts everything up to a ceiling and then stops. */
    private static SkatAiSession ceiling(int limit) {
        return new SkatAiSession() {
            @Override public int bid(SkatAi.BidRequest request) {
                return request.requestedBid <= limit ? request.requestedBid : 0;
            }
            @Override public Card chooseCard(SkatAi.DecisionContext context) {
                return context.legalCards.iterator().next();
            }
        };
    }

    private static Map<SkatAi.Seat, SkatAiSession> table(int limit, SkatAi.Seat... humanSeats) {
        Map<SkatAi.Seat, SkatAiSession> sessions = new EnumMap<>(SkatAi.Seat.class);
        Set<SkatAi.Seat> people = Set.of(humanSeats);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (!people.contains(seat)) sessions.put(seat, ceiling(limit));
        }
        return sessions;
    }

    private static Auction run(Auction auction, boolean answer) {
        int guard = 0;
        while (!auction.finished() && guard++ < 200) {
            if (auction.pending() != null) auction.answer(answer);
            else auction.step();
        }
        assertTrue("the auction must terminate", auction.finished());
        return auction;
    }

    @Test
    public void middlehandSpeaksFirstAndForehandIsAskedToHold() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        Auction auction = new Auction(round, Set.of(), table(0));
        Auction.Decision first = auction.currentDecision();
        assertEquals(round.middlehand, first.seat());
        assertEquals(Auction.Role.ANNOUNCE, first.role());
        assertEquals(18, first.value());
    }

    @Test
    public void aPersonWhoNeverPassesWinsTheAuction() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        Auction auction = run(new Auction(round, Set.of(SkatAi.Seat.HUMAN),
                table(22, SkatAi.Seat.HUMAN)), true);
        assertEquals(SkatAi.Seat.HUMAN, auction.declarer());
        assertTrue(auction.winningBid() >= 18);
        assertFalse(auction.passedIn());
    }

    @Test
    public void everySpokenStepIsRecorded() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        Auction auction = run(new Auction(round, Set.of(SkatAi.Seat.HUMAN),
                table(22, SkatAi.Seat.HUMAN)), true);
        List<Auction.Step> log = auction.log();
        assertFalse(log.isEmpty());
        boolean sawPass = false;
        int highest = 0;
        for (Auction.Step step : log) {
            if (step.passed()) sawPass = true;
            else {
                // Values only ever climb, and every rung is a rung of the ladder.
                assertTrue(step.value() >= highest);
                assertTrue(BidValues.rung(step.value()) >= 0);
                highest = step.value();
            }
        }
        assertTrue("passes belong in the log too", sawPass);
        assertEquals(highest, auction.winningBid());
    }

    @Test
    public void aTableOfPassesPassesIn() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        Auction auction = new Auction(round, Set.of(), table(0));
        auction.advanceAutomatic();
        assertTrue(auction.finished());
        assertTrue(auction.passedIn());
        assertNull(auction.declarer());
        assertEquals(0, auction.winningBid());
    }

    @Test
    public void forehandIsOfferedEighteenWhenNobodySpoke() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        // Forehand is the person here, and both automated seats pass at once.
        Auction auction = new Auction(round, Set.of(round.forehand),
                table(0, round.forehand));
        auction.advanceAutomatic();
        Auction.Decision decision = auction.pending();
        assertEquals(round.forehand, decision.seat());
        assertEquals(Auction.Role.ANNOUNCE, decision.role());
        assertEquals(18, decision.value());
        auction.answer(true);
        assertTrue(auction.finished());
        assertEquals(round.forehand, auction.declarer());
        assertEquals(18, auction.winningBid());
    }

    @Test
    public void aPersonWhoHoldsToTwentyRecordsTwenty() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        Auction auction = new Auction(round, Set.of(SkatAi.Seat.HUMAN),
                table(30, SkatAi.Seat.HUMAN));
        int guard = 0;
        while (!auction.finished() && guard++ < 200) {
            if (auction.pending() != null) auction.answer(auction.pending().value() <= 20);
            else auction.step();
        }
        assertTrue(auction.finished());
        assertFalse(SkatAi.Seat.HUMAN == auction.declarer());
        assertEquals(20, auction.highestSaid(SkatAi.Seat.HUMAN));
    }

    /** A jump bid says one value. Only what was said goes into the log. */
    @Test
    public void aJumpBidRecordsOnlyTheValueSpoken() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        Auction auction = new Auction(round, Set.of(round.middlehand),
                table(0, round.middlehand));
        auction.step();
        Auction.Decision decision = auction.pending();
        assertEquals(round.middlehand, decision.seat());
        auction.announce(36);
        boolean saw36 = false;
        for (Auction.Step step : auction.log()) {
            if (step.seat() == round.middlehand && !step.passed()) {
                assertEquals(36, step.value());
                saw36 = true;
            }
        }
        assertTrue(saw36);
        assertEquals(36, auction.currentBid());
    }

    /** The whole interactive deal: auction, skat, discard, declaration, play. */
    @Test
    public void anInteractiveDealReachesTrickPlay() {
        GameEngine engine = new GameEngine(new Random(11),
                new LegalRandomAiProvider(new Random(11)));
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        assertEquals(GameEngine.Phase.AUCTION, engine.phase());
        assertFalse("no card may be played during the auction",
                engine.snapshot().waitingForHuman());
        run(auction, true);
        assertEquals(SkatAi.Seat.HUMAN, auction.declarer());
        assertEquals(GameEngine.Phase.SKAT_CHOICE, engine.settleAuction());

        List<Card> twelve = engine.pickUpSkat();
        assertEquals(12, twelve.size());
        assertEquals(GameEngine.Phase.DISCARD, engine.phase());
        assertFalse(engine.snapshot().waitingForHuman());
        assertTrue(engine.discard(twelve.get(0), twelve.get(1)));
        assertEquals(GameEngine.Phase.DECLARATION, engine.phase());

        // Hand cannot be announced after the skat was taken.
        assertFalse(engine.declare(Declaration.hand(Contract.CLUBS)));
        assertTrue(engine.declare(Declaration.of(Contract.CLUBS)));
        assertEquals(GameEngine.Phase.PLAY, engine.phase());
        GameEngine.Snapshot snapshot = engine.snapshot();
        assertEquals(10, snapshot.hands.get(GameEngine.HUMAN).size());
        assertEquals(Contract.CLUBS, snapshot.contract);
        assertEquals(auction.winningBid(), snapshot.definition.bidValue);
    }

    /**
     * A card chosen for the skat leaves the hand at once, and can be taken back
     * until the game is announced.
     */
    @Test
    public void theSkatFillsOneCardAtATimeAndStaysReversible() {
        GameEngine engine = new GameEngine(new Random(23),
                new LegalRandomAiProvider(new Random(23)));
        run(engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN)), true);
        assertEquals(GameEngine.Phase.SKAT_CHOICE, engine.settleAuction());
        List<Card> twelve = engine.pickUpSkat();

        Card first = twelve.get(3);
        assertTrue(engine.discardOne(first));
        assertEquals(11, engine.snapshot().hands.get(GameEngine.HUMAN).size());
        assertEquals(List.of(first), engine.snapshot().pendingDiscards);
        assertEquals(GameEngine.Phase.DISCARD, engine.phase());
        assertFalse("the same card cannot go twice", engine.discardOne(first));

        assertTrue(engine.takeBackDiscard(first));
        assertEquals(12, engine.snapshot().hands.get(GameEngine.HUMAN).size());
        assertTrue(engine.snapshot().pendingDiscards.isEmpty());
        assertEquals(GameEngine.Phase.DISCARD, engine.phase());

        Card second = twelve.get(7);
        assertTrue(engine.discardOne(first));
        assertTrue(engine.discardOne(second));
        assertEquals(10, engine.snapshot().hands.get(GameEngine.HUMAN).size());
        assertEquals(GameEngine.Phase.DECLARATION, engine.phase());
        assertEquals(List.of(first, second), engine.snapshot().skat);
        assertFalse("a third card cannot go back", engine.discardOne(twelve.get(1)));

        // Still reversible while the game is being chosen.
        assertTrue(engine.takeBackDiscard(second));
        assertEquals(GameEngine.Phase.DISCARD, engine.phase());
        assertEquals(11, engine.snapshot().hands.get(GameEngine.HUMAN).size());
        assertTrue(engine.discardOne(second));
        assertEquals(GameEngine.Phase.DECLARATION, engine.phase());
        assertTrue(engine.declare(Declaration.of(Contract.HEARTS)));
        assertEquals(GameEngine.Phase.PLAY, engine.phase());
        assertEquals(2, engine.snapshot().skat.size());
    }

    @Test
    public void aHandGameKeepsTheSkatAndSkipsTheDiscard() {
        GameEngine engine = new GameEngine(new Random(3),
                new LegalRandomAiProvider(new Random(3)));
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        run(auction, true);
        assertEquals(GameEngine.Phase.SKAT_CHOICE, engine.settleAuction());
        engine.playHand();
        assertEquals(GameEngine.Phase.DECLARATION, engine.phase());
        assertTrue(engine.declarerPlaysHand());
        assertFalse("a skat game cannot be announced after choosing hand",
                engine.declare(Declaration.of(Contract.GRAND)));
        assertTrue(engine.declare(Declaration.hand(Contract.GRAND)));
        GameEngine.Snapshot snapshot = engine.snapshot();
        assertEquals(10, snapshot.hands.get(GameEngine.HUMAN).size());
        assertEquals(2, snapshot.skat.size());
        assertTrue(snapshot.definition.hand);
    }
}
