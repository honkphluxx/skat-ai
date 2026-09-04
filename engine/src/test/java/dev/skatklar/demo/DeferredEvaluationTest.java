package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.Opponents;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.search.HandEvaluator;
import dev.skatklar.demo.search.WorldSource;
import dev.skatklar.demo.solve.DoubleDummySolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Dealing and thinking, split apart.
 *
 * <p>A searching player measures the hand it has been dealt by playing it out
 * against perfect defence, which is several double-dummy solves a seat. On a
 * phone that ran on the thread that delivered the tap, and the app stopped
 * answering. It now runs on a worker while the cards fly, and a solve that will
 * not finish in time is abandoned rather than waited for.
 *
 * <p>Both halves are the kind of change that is only safe if it changes nothing
 * else, so that is what these assert: the deferred auction is word for word the
 * inline one, and a deadline that never expires answers exactly what no deadline
 * answers.
 */
public class DeferredEvaluationTest {

    /** Everything the seats said, as one comparable string. */
    private static String auctionOf(GameEngine engine, boolean defer) {
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN), defer);
        assertEquals("only a deferred deal leaves anything to prepare",
                defer, engine.aiPreparationPending());
        engine.prepareDeferredSeats();
        engine.admitPreparedSeats();
        assertFalse("preparing and admitting must clear the flag the auction reads",
                engine.aiPreparationPending());

        int guard = 0;
        while (!auction.finished() && guard++ < 100) {
            if (auction.pending() != null) auction.answer(false);
            else auction.step();
        }
        StringBuilder said = new StringBuilder();
        for (Auction.Step step : auction.log()) {
            said.append(step.seat()).append(step.passed() ? " passes " : " says ")
                    .append(step.value()).append(' ').append(step.role()).append('\n');
        }
        return said.append("declarer=").append(auction.declarer())
                .append(" bid=").append(auction.winningBid()).toString();
    }

    private static List<List<Card>> handsOf(SkatDeck.Deal deal) {
        List<List<Card>> bySeat = new ArrayList<>(3);
        bySeat.add(new ArrayList<>(deal.human));
        bySeat.add(new ArrayList<>(deal.opponentOne));
        bySeat.add(new ArrayList<>(deal.opponentTwo));
        return bySeat;
    }

    /**
     * The mechanism, on a player cheap enough to run twenty times: whatever the
     * seats would have said inline, they say after the deferral too.
     */
    @Test
    public void aDeferredAuctionIsWordForWordTheInlineOne() {
        for (int seed = 1; seed <= 20; seed++) {
            String inline = auctionOf(new GameEngine(Rng.withSeed(seed),
                    new LegalRandomAiProvider(new Random(seed))), false);
            String deferred = auctionOf(new GameEngine(Rng.withSeed(seed),
                    new LegalRandomAiProvider(new Random(seed))), true);
            assertEquals("seed " + seed, inline, deferred);
        }
    }

    /**
     * And again on the player the app actually seats, which is the one whose
     * evaluation is expensive enough to have been worth moving.
     */
    @Test
    public void aDeferredAuctionIsUnchangedForTheSearchingPlayer() {
        for (int seed = 1; seed <= 2; seed++) {
            SkatAiProvider inlinePlayer =
                    Opponents.seat(Opponents.Level.BEGINNER, WorldSource.UNIFORM, seed);
            SkatAiProvider deferredPlayer =
                    Opponents.seat(Opponents.Level.BEGINNER, WorldSource.UNIFORM, seed);
            assertEquals("seed " + seed,
                    auctionOf(new GameEngine(Rng.withSeed(seed), inlinePlayer), false),
                    auctionOf(new GameEngine(Rng.withSeed(seed), deferredPlayer), true));
        }
    }

    /** Preparing twice is what a resume does, and it must not double-count. */
    @Test
    public void preparingTwiceIsHarmless() {
        GameEngine engine = new GameEngine(Rng.withSeed(3),
                new LegalRandomAiProvider(new Random(3)));
        engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN), true);
        engine.prepareDeferredSeats();
        engine.prepareDeferredSeats();
        engine.admitPreparedSeats();
        engine.admitPreparedSeats();
        assertFalse(engine.aiPreparationPending());
        assertTrue(engine.ruleViolations().isEmpty());
    }

    /** A deal that never deferred has nothing to prepare and says so. */
    @Test
    public void anInlineDealLeavesNothingPending() {
        GameEngine engine = new GameEngine(Rng.withSeed(4),
                new LegalRandomAiProvider(new Random(4)));
        engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        assertFalse(engine.aiPreparationPending());
        engine.prepareDeferredSeats();
        assertFalse(engine.admitPreparedSeats());
        assertFalse(engine.aiPreparationPending());
    }

    /**
     * The auction opens over a seat that is still thinking, and that seat is
     * neither asked anything nor told anything until it is admitted.
     *
     * <p>This is what lets a person say 18 the moment the cards land. The
     * danger it has to avoid is subtle: prepareDeal is also where a player
     * forgets the previous deal, so a bid heard before it would be wiped. The
     * seat therefore hears nothing at all until admitPreparedSeats replays the
     * log to it — exactly once, because nothing can be spoken in between.
     */
    @Test
    public void aSeatStillThinkingIsNeitherAskedNorTold() {
        GameEngine engine = new GameEngine(Rng.withSeed(9),
                new LegalRandomAiProvider(new Random(9)));
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN), true);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            assertEquals("only the automated seats are owed a look",
                    seat != SkatAi.Seat.HUMAN, engine.aiPreparationPending(seat));
            assertEquals(seat != SkatAi.Seat.HUMAN, auction.withheldFrom(seat));
        }
        // Nothing an automated seat could say gets said while they are thinking.
        int guard = 0;
        while (!auction.finished() && auction.pending() == null && guard++ < 10) {
            if (!auction.step()) break;
        }
        for (Auction.Step step : auction.log()) {
            assertEquals("a withheld seat must not have spoken",
                    SkatAi.Seat.HUMAN, step.seat());
        }
        engine.prepareDeferredSeats();
        assertTrue("prepared is not admitted", engine.aiPreparationPending());
        assertTrue(engine.admitPreparedSeats());
        assertFalse(engine.aiPreparationPending());
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            assertFalse(seat.toString(), auction.withheldFrom(seat));
        }
    }

    /** A person can answer while an automated seat is still measuring its hand. */
    @Test
    public void thePersonCanSpeakWhileTheOthersThink() {
        GameEngine engine = new GameEngine(Rng.withSeed(10),
                new LegalRandomAiProvider(new Random(10)));
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN), true);
        // Drive to the person's question without preparing anybody.
        int guard = 0;
        while (auction.pending() == null && !auction.finished() && guard++ < 10) {
            if (!auction.step()) break;
        }
        if (auction.pending() != null) {
            auction.answer(true);
            assertFalse("the person's own word is in the log",
                    auction.log().isEmpty());
        }
        engine.prepareDeferredSeats();
        engine.admitPreparedSeats();
        assertFalse(engine.aiPreparationPending());
    }

    /**
     * Starting a second deal drops the first one's outstanding work. On the
     * phone this is a player leaving the table mid-evaluation; the flag has to
     * describe the deal now on the table, not the one that was abandoned.
     */
    @Test
    public void abandoningADealClearsWhatItLeftToDo() {
        GameEngine engine = new GameEngine(Rng.withSeed(5),
                new LegalRandomAiProvider(new Random(5)));
        engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN), true);
        assertTrue(engine.aiPreparationPending());
        engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        assertFalse("the inline deal that replaced it owes nobody anything",
                engine.aiPreparationPending());
    }

    /** A deadline that cannot expire must not change a single answer. */
    @Test
    public void aDeadlineThatNeverExpiresChangesNothing() {
        Random random = Rng.withSeed(2026);
        for (int deal = 0; deal < 8; deal++) {
            SkatDeck.Deal board = SkatDeck.deal(random);
            for (Contract contract : new Contract[] {Contract.GRAND, Contract.CLUBS}) {
                boolean plain = DoubleDummySolver.declarerReaches(
                        contract, SkatAi.Seat.HUMAN, handsOf(board), SkatAi.Seat.HUMAN, 61);
                Boolean bounded = DoubleDummySolver.declarerReachesBefore(
                        contract, SkatAi.Seat.HUMAN, handsOf(board), SkatAi.Seat.HUMAN, 61,
                        System.nanoTime() + 600_000_000_000L);
                assertNotNull("ten minutes was not enough for one solve", bounded);
                assertEquals(contract + " on deal " + deal, plain, (boolean) bounded);
            }
        }
    }

    /** A deadline already in the past gives up instead of answering. */
    @Test
    public void anExpiredDeadlineGivesUp() {
        SkatDeck.Deal board = SkatDeck.deal(Rng.withSeed(7));
        assertNull(DoubleDummySolver.declarerReachesBefore(Contract.GRAND, SkatAi.Seat.HUMAN,
                handsOf(board), SkatAi.Seat.HUMAN, 61, System.nanoTime() - 1L));
    }

    /**
     * The two shortcuts are answers, not searches, so they survive an expired
     * deadline: nothing to reach, and more than the pack holds.
     */
    @Test
    public void anExpiredDeadlineStillAnswersTheTrivialQuestions() {
        SkatDeck.Deal board = SkatDeck.deal(Rng.withSeed(8));
        long past = System.nanoTime() - 1L;
        assertEquals(Boolean.TRUE, DoubleDummySolver.declarerReachesBefore(Contract.GRAND,
                SkatAi.Seat.HUMAN, handsOf(board), SkatAi.Seat.HUMAN, 0, past));
        assertEquals(Boolean.FALSE, DoubleDummySolver.declarerReachesBefore(Contract.GRAND,
                SkatAi.Seat.HUMAN, handsOf(board), SkatAi.Seat.HUMAN,
                DoubleDummySolver.TOTAL_POINTS + 1, past));
    }

    /**
     * An unmeasurable hand comes back as silence rather than as a low chance.
     * Scoring it zero would read an answer out of a question never asked, and
     * would make every slow deal a pass.
     */
    @Test
    public void anExpiredBudgetLeavesTheHandUnmeasured() {
        SkatDeck.Deal board = SkatDeck.deal(Rng.withSeed(9));
        List<Card> hand = new ArrayList<>(board.human);
        double unmeasured = new HandEvaluator(4, Rng.withSeed(1)).makeChanceBefore(
                Contract.GRAND, hand, SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN,
                System.nanoTime() - 1L);
        assertTrue("an expired budget must not produce a number", Double.isNaN(unmeasured));

        double waited = new HandEvaluator(4, Rng.withSeed(1)).makeChance(
                Contract.GRAND, hand, SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN);
        double budgeted = new HandEvaluator(4, Rng.withSeed(1)).makeChanceBefore(
                Contract.GRAND, hand, SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN,
                System.nanoTime() + 600_000_000_000L);
        assertEquals("a budget that never bites must sample identically",
                waited, budgeted, 0.0);
    }
}
