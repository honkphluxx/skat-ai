package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * A game fixed to an auction's outcome hears the auction.
 *
 * <p>The arena's fixed-contract mode runs a bidder's auction to pick the
 * declarer and contract, then restarts the board at that contract. Until
 * 2026-09-24 nobody at the second table was told a bid had been made, so
 * every fixed-contract measurement ran with the belief's bidding evidence
 * empty. This pins the two halves: the headless engine logs its auction, and
 * {@code restartWithContract} given that log delivers it to every seat --
 * the declarer's after its {@code prepareDeal}, the defenders' through a
 * session of their own, none of it as a violation.
 */
public class FixedContractAuctionTest {

    /** Records what a seat hears, and whether prepareDeal came first. */
    private static final class Listening implements SkatAiProvider {
        final SkatAi.Seat seat;
        final List<SkatAi.BidEvent> heard = new ArrayList<>();
        int prepared;
        boolean heardBeforePrepare;
        final SkatAiProvider inner = new GreedyAiProvider();

        Listening(SkatAi.Seat seat) { this.seat = seat; }

        @Override public SkatAi.AiDescriptor descriptor() { return inner.descriptor(); }
        @Override public SkatAiSession createSession() {
            SkatAiSession delegate = inner.createSession();
            return new SkatAiSession() {
                boolean preparedHere;
                @Override public void prepareDeal(SkatAi.DealContext context) {
                    prepared++; preparedHere = true; delegate.prepareDeal(context);
                }
                @Override public void bidObserved(SkatAi.BidEvent event) {
                    heard.add(event);
                    if (!preparedHere) heardBeforePrepare = true;
                    delegate.bidObserved(event);
                }
                @Override public int bid(SkatAi.BidRequest request) { return delegate.bid(request); }
                @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext c) { return delegate.pickUpSkat(c); }
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext c) { return delegate.discardSkat(c); }
                @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext c) { return delegate.announceContract(c); }
                @Override public void startGame(SkatAi.GameStartContext c) { delegate.startGame(c); }
                @Override public Card chooseCard(SkatAi.DecisionContext c) { return delegate.chooseCard(c); }
                @Override public void cardPlayed(SkatAi.CardPlayedEvent e) { delegate.cardPlayed(e); }
                @Override public void trickCompleted(SkatAi.TrickCompletedEvent e) { delegate.trickCompleted(e); }
                @Override public void endGame(SkatAi.GameResult r) { delegate.endGame(r); }
                @Override public void close() { delegate.close(); }
            };
        }
    }

    private static Map<SkatAi.Seat, Listening> table() {
        Map<SkatAi.Seat, Listening> seats = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) seats.put(seat, new Listening(seat));
        return seats;
    }

    private static SeatedAiProviders seating(Map<SkatAi.Seat, Listening> seats) {
        Map<SkatAi.Seat, SkatAiProvider> providers = new EnumMap<>(SkatAi.Seat.class);
        seats.forEach(providers::put);
        return SeatedAiProviders.of(providers);
    }

    /** A deal greedy bids on, found by trying seeds; the first one is enough. */
    private static long biddingSeed() {
        for (long seed = 1; seed < 200; seed++) {
            Map<SkatAi.Seat, Listening> seats = table();
            GameEngine engine = GameEngine.headless(new Random(seed), seating(seats));
            engine.restartWithDeal(SkatDeck.deal(new Random(seed)), SkatAi.RoundPosition.at(0), Set.of());
            boolean declared = !engine.snapshot().definition.isRamsch();
            engine.close();
            if (declared) return seed;
        }
        throw new AssertionError("greedy never declared in 200 deals");
    }

    @Test public void theHeadlessAuctionIsLoggedAndReplayed() {
        long seed = biddingSeed();

        Map<SkatAi.Seat, Listening> first = table();
        GameEngine auctioned = GameEngine.headless(new Random(seed), seating(first));
        auctioned.restartWithDeal(SkatDeck.deal(new Random(seed)), SkatAi.RoundPosition.at(0), Set.of());
        SkatAi.GameDefinition definition = auctioned.snapshot().definition;
        List<SkatAi.BidEvent> log = auctioned.auctionLog();
        auctioned.close();

        assertFalse("the log holds the auction", log.isEmpty());
        // What the log says is exactly what every seat heard, in order.
        for (Listening seat : first.values()) {
            assertEquals(log.size(), seat.heard.size());
            for (int at = 0; at < log.size(); at++) {
                assertEquals(log.get(at).seat, seat.heard.get(at).seat);
                assertEquals(log.get(at).value, seat.heard.get(at).value);
                assertEquals(log.get(at).passed, seat.heard.get(at).passed);
            }
        }

        // The same board at that outcome, with the log: every seat hears it again.
        Map<SkatAi.Seat, Listening> second = table();
        GameEngine fixed = GameEngine.headless(new Random(seed), seating(second));
        fixed.restartWithContract(SkatDeck.deal(new Random(seed)), SkatAi.RoundPosition.at(0),
                definition.declarer, definition.contract, definition.bidValue, Set.of(), log);
        for (Listening seat : second.values()) {
            assertEquals("seat " + seat.seat + " heard the whole auction", log.size(), seat.heard.size());
        }
        Listening declarer = second.get(definition.declarer);
        assertEquals("the declarer was prepared once, for the exchange", 1, declarer.prepared);
        assertFalse("and heard the auction after it", declarer.heardBeforePrepare);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (seat == definition.declarer) continue;
            assertEquals("a defender is not priced for a game it will not bid on", 0, second.get(seat).prepared);
        }
        assertEquals("no violation for listening", 0, fixed.ruleViolations().size());
        assertTrue("the fixed game left the log empty", fixed.auctionLog().isEmpty());
        fixed.close();

        // And without the log, nobody hears anything -- the old behaviour, kept.
        Map<SkatAi.Seat, Listening> third = table();
        GameEngine silent = GameEngine.headless(new Random(seed), seating(third));
        silent.restartWithContract(SkatDeck.deal(new Random(seed)), SkatAi.RoundPosition.at(0),
                definition.declarer, definition.contract, definition.bidValue, Set.of());
        for (Listening seat : third.values()) assertTrue(seat.heard.isEmpty());
        silent.close();
    }
}
