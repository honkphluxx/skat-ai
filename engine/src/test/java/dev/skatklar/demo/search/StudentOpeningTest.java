package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.belief.PolicyNetTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * {@link SearchAiProvider#withOpening}: the student plays the first tricks, the search the rest.
 *
 * <p>Whole games, all three seats the search player with a student whose
 * scores are fixed -- card i scores i/4 whatever the position -- so the card
 * it should name is the legal card with the highest index, and anything else
 * the seat plays in the opening came from somewhere else. Checked per decision:
 * in the first {@code tricks} tricks of a suit or grand game the card is the
 * student's and no world is sampled; from then on worlds are sampled. A Null
 * never reaches the student, nor does a contract it was not trained on, and
 * with zero tricks nothing does.
 */
public class StudentOpeningTest {

    private static final Personality QUICK = new Personality(4, 1.0, 0.0, 0.5);

    /** One decision the seat made: tricks completed, the legal cards, the card, worlds asked. */
    private record Decision(int completed, List<Card> legal, Card played, int worldsAsked) {}

    private static PolicyNet student(int contracts) throws IOException {
        return PolicyNet.load(new ByteArrayInputStream(
                PolicyNetTest.constant(PolicyNetTest.rising(), contracts, PolicyNet.MAGIC, 64)), null);
    }

    /** The student's card: the legal card with the highest index. */
    private static Card highest(List<Card> legal) {
        Card best = null;
        for (Card card : legal) {
            if (best == null || BeliefEncoding.index(card) > BeliefEncoding.index(best)) best = card;
        }
        return best;
    }

    private static List<Decision> play(Contract contract, int deal, PolicyNet student, int tricks) {
        List<Decision> decisions = new ArrayList<>();
        Map<SkatAi.Seat, SkatAiProvider> seats = new EnumMap<>(SkatAi.Seat.class);
        SkatAi.Seat declarer = SkatAi.Seat.values()[deal % 3];
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            int[] lastWorlds = {-1};
            SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(), QUICK,
                    500L + deal * 3L + seat.ordinal(), WorldSource.UNIFORM)
                    .withMarginTiebreak(15).withLadder()
                    .withCardPlayObserver(report -> lastWorlds[0] = report.worldsAsked())
                    .withOpening(student, tricks);
            seats.put(seat, recording(player, decisions, lastWorlds));
        }
        GameEngine engine = GameEngine.headless(new Random(deal), SeatedAiProviders.of(seats));
        engine.restartWithContract(SkatDeck.deal(new Random(7_000L + deal)), SkatAi.RoundPosition.at(0),
                declarer, contract, 18, Set.of());
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot now = engine.snapshot();
            if (now.gameComplete()) break;
            if (now.trickComplete()) engine.finishCompletedTrick(); else engine.playAiCard();
        }
        engine.close();
        return decisions;
    }

    @Test public void theStudentPlaysTheOpeningAndTheSearchTheRest() throws IOException {
        PolicyNet student = student(0x1F);
        int opening = 0, later = 0, differs = 0;
        Contract[] contracts = {Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS, Contract.GRAND};
        for (int deal = 0; deal < 5; deal++) {
            for (Decision d : play(contracts[deal], deal, student, 2)) {
                if (d.legal.size() < 2) continue;
                if (d.completed < 2) {
                    assertEquals("trick " + (d.completed + 1) + ": the student's card", highest(d.legal), d.played);
                    assertEquals("no world sampled for the student's card", 0, d.worldsAsked);
                    opening++;
                } else {
                    assertTrue("trick " + (d.completed + 1) + ": the search, worlds " + d.worldsAsked,
                            d.worldsAsked > 0);
                    if (!d.played.equals(highest(d.legal))) differs++;
                    later++;
                }
            }
        }
        assertTrue("opening decisions " + opening, opening >= 15);
        assertTrue("later decisions " + later, later >= 30);
        assertTrue("the search plays its own cards later: " + differs + " of " + later, differs >= 10);
    }

    @Test public void nullAndUnseenContractsAndZeroTricksGoToTheSearch() throws IOException {
        // A student that claims Null as well: the rule, not the corpus, keeps it off a Null.
        for (Decision d : play(Contract.NULL, 1, student(0x7F), 3)) {
            if (d.legal.size() > 1) assertTrue("a Null is the search's", d.worldsAsked > 0);
        }
        // Trained without Diamonds: a Diamonds game is the search's from the first card.
        for (Decision d : play(Contract.DIAMONDS, 2, student(0x1E), 3)) {
            if (d.legal.size() > 1) assertTrue("a contract it never saw", d.worldsAsked > 0);
        }
        for (Decision d : play(Contract.CLUBS, 3, student(0x1F), 0)) {
            if (d.legal.size() > 1) assertTrue("zero tricks is no student", d.worldsAsked > 0);
        }
    }

    @Test public void theOpeningSurvivesTheOtherBuilders() throws IOException {
        PolicyNet student = student(0x1F);
        SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(), QUICK, 1L, WorldSource.UNIFORM)
                .withOpening(student, 2).withAdaptiveBidding().withMarginTiebreak(15).withRuleTiebreak()
                .withLadder().withTrap(SearchAiProvider.TrapOrder.SAFETY_FIRST).withPointBands()
                .withNullWorlds(24).withNullTiebreak(RuleTiebreak.NullOrder.LOW_RANK)
                .withBiddingBudget(1_000L).withWorldThreads(2).withCardBudget(5L)
                .withCardPlayObserver(report -> {}).withDiscardSearch(8, 5, 0L).withTemperature(0.1);
        assertEquals(student, player.cardPlaySettings().opening);
        assertEquals(2, player.cardPlaySettings().openingTricks);
        assertTrue(player.cardPlaySettings().ladder && player.cardPlaySettings().bands);
        assertEquals(null, new SearchAiProvider(new GreedyAiProvider(), QUICK, 1L, WorldSource.UNIFORM)
                .withOpening(student, 0).cardPlaySettings().opening);
    }

    /** The seat as it is, with every card decision written down. */
    private static SkatAiProvider recording(SearchAiProvider player, List<Decision> into, int[] lastWorlds) {
        return new SkatAiProvider() {
            @Override public SkatAi.AiDescriptor descriptor() { return player.descriptor(); }
            @Override public SkatAiSession createSession() {
                SkatAiSession inner = player.createSession();
                return new SkatAiSession() {
                    @Override public void prepareDeal(SkatAi.DealContext c) { inner.prepareDeal(c); }
                    @Override public int bid(SkatAi.BidRequest r) { return inner.bid(r); }
                    @Override public void bidObserved(SkatAi.BidEvent e) { inner.bidObserved(e); }
                    @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext c) { return inner.pickUpSkat(c); }
                    @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext c) { return inner.discardSkat(c); }
                    @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext c) {
                        return inner.announceContract(c);
                    }
                    @Override public boolean takeUpPush(SkatAi.RamschTakeUpContext c) { return inner.takeUpPush(c); }
                    @Override public Set<Card> pushCards(SkatAi.RamschPushContext c) { return inner.pushCards(c); }
                    @Override public void ramschPushObserved(SkatAi.RamschPushEvent e) { inner.ramschPushObserved(e); }
                    @Override public boolean announceContra(SkatAi.ContraContext c) { return inner.announceContra(c); }
                    @Override public void contraObserved(SkatAi.ContraEvent e) { inner.contraObserved(e); }
                    @Override public void startGame(SkatAi.GameStartContext c) { inner.startGame(c); }
                    @Override public Card chooseCard(SkatAi.DecisionContext c) {
                        lastWorlds[0] = -1;
                        Card card = inner.chooseCard(c);
                        synchronized (into) {
                            into.add(new Decision(c.history.completedTricks.size(),
                                    new ArrayList<>(c.legalCards), card, lastWorlds[0]));
                        }
                        return card;
                    }
                    @Override public void cardPlayed(SkatAi.CardPlayedEvent e) { inner.cardPlayed(e); }
                    @Override public void trickCompleted(SkatAi.TrickCompletedEvent e) { inner.trickCompleted(e); }
                    @Override public void endGame(SkatAi.GameResult r) { inner.endGame(r); }
                    @Override public void close() { inner.close(); }
                };
            }
        };
    }
}
