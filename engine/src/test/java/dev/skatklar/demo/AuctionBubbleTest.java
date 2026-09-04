package dev.skatklar.demo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * What the table is told a seat said, against what it did.
 *
 * <p>Written after "says 18" was read on a hand that ended in a Ramsch. The
 * engine turned out to be right — a passed-in auction cannot contain a spoken
 * bid — so the mismatch was in the words, and this is the invariant the words
 * have to respect.
 */
public final class AuctionBubbleTest {

    /** Says every value up to a ceiling and passes above it. */
    private static SkatAiProvider upTo(int ceiling) {
        return new SkatAiProvider() {
            @Override public SkatAi.AiDescriptor descriptor() {
                return new SkatAi.AiDescriptor("scripted", "Scripted", false);
            }

            @Override public SkatAiSession createSession() {
                return new SkatAiSession() {
                    @Override public int bid(SkatAi.BidRequest request) {
                        return request.requestedBid <= ceiling ? request.requestedBid : 0;
                    }

                    @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                        List<Card> twelve = new ArrayList<>(context.hand);
                        return new LinkedHashSet<>(twelve.subList(0, 2));
                    }

                    @Override public SkatAi.ContractAnnouncement announceContract(
                            SkatAi.ContractContext context) {
                        return SkatAi.ContractAnnouncement.skatGame(SkatAi.ContractType.CLUBS);
                    }

                    @Override public Card chooseCard(SkatAi.DecisionContext context) {
                        return context.legalCards.iterator().next();
                    }
                };
            }
        };
    }

    private static Auction auction(SkatAi.RoundPosition round, int forehand,
                                   int middlehand, int rearhand) {
        EnumMap<SkatAi.Seat, SkatAiSession> sessions = new EnumMap<>(SkatAi.Seat.class);
        sessions.put(round.forehand, upTo(forehand).createSession());
        sessions.put(round.middlehand, upTo(middlehand).createSession());
        sessions.put(round.rearhand, upTo(rearhand).createSession());
        Auction auction = new Auction(round, Collections.emptySet(), sessions);
        auction.advanceAutomatic();
        return auction;
    }

    /**
     * The one that matters: nobody can be shown saying a number on a hand
     * everybody passed. A bubble reading "says 18" over a Ramsch would be the
     * app describing a game that was never bid for.
     */
    @Test
    public void aPassedInAuctionHasNoSpokenBid() {
        for (int round = 0; round < 3; round++) {
            Auction auction = auction(SkatAi.RoundPosition.at(round), 0, 0, 0);
            assertTrue("a table where nobody bids passes in", auction.passedIn());
            for (Auction.Step step : auction.log()) {
                assertTrue("a passed-in auction must contain only passes: " + auction.log(),
                        step.passed());
            }
        }
    }

    /**
     * A pass before anything has been said is not a pass "at" anything. The
     * value in the log is the rung that was offered, not a number anybody put on
     * the table, and the wording has to be able to tell the two apart.
     */
    @Test
    public void aPassBeforeAnyBidCarriesNoValueAnybodySaid() {
        Auction auction = auction(SkatAi.RoundPosition.at(0), 0, 0, 0);
        boolean anythingSaid = false;
        for (Auction.Step step : auction.log()) {
            assertFalse("nothing was ever said, so no step may claim a value was",
                    anythingSaid);
            if (!step.passed()) anythingSaid = true;
        }
    }

    /** And when somebody really was pushed, the value is the one they declined. */
    @Test
    public void aPassAfterABidKeepsTheValueItDeclined() {
        Auction auction = auction(SkatAi.RoundPosition.at(0), 18, 24, 0);
        assertFalse(auction.passedIn());
        boolean sawSpokenBid = false;
        boolean sawPassAfterIt = false;
        for (Auction.Step step : auction.log()) {
            if (!step.passed()) sawSpokenBid = true;
            else if (sawSpokenBid) sawPassAfterIt = true;
        }
        assertTrue("this table has a real duel in it", sawSpokenBid);
        assertTrue("and somebody dropped out of it", sawPassAfterIt);
    }
}
