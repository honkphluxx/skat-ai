package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The game chosen again after the skat ({@link SearchAiProvider#withPostSkatContract}).
 *
 * <p>One jack and six clubs priced at the deal intend a Clubs game; the skat
 * brings two more jacks, and on the twelve Grand is worth twice Clubs'
 * guaranteed value (96 against 48). The re-chooser announces Grand and buries what its
 * search chose; without it the player announces as before. A settled contract
 * and an exchange without a bid are left alone; the setting survives the
 * other builders, and the choice is {@link ContractSearch}'s.
 */
public class PostSkatContractTest {

    private static final SkatAi.ContractRules RULES = new SkatAi.ContractRules(EnumSet.of(
            SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS, SkatAi.ContractType.SPADES,
            SkatAi.ContractType.CLUBS, SkatAi.ContractType.GRAND), false, false, false);
    private static final SkatAi.RoundPosition ROUND = SkatAi.RoundPosition.at(0);

    static SkatAi.ContractRules rules() {
        return RULES;
    }

    private static Card card(Card.Suit suit, Card.Rank rank) {
        return new Card(suit, rank);
    }

    /** J♣, A 10 K Q 9 8 of clubs, A and 7 of hearts, 7 of spades. */
    static List<Card> ten() {
        List<Card> ten = new ArrayList<>(List.of(card(Card.Suit.CLUBS, Card.Rank.JACK)));
        for (Card.Rank rank : new Card.Rank[] {Card.Rank.ACE, Card.Rank.TEN, Card.Rank.KING, Card.Rank.QUEEN,
                Card.Rank.NINE, Card.Rank.EIGHT}) {
            ten.add(card(Card.Suit.CLUBS, rank));
        }
        ten.add(card(Card.Suit.HEARTS, Card.Rank.ACE));
        ten.add(card(Card.Suit.HEARTS, Card.Rank.SEVEN));
        ten.add(card(Card.Suit.SPADES, Card.Rank.SEVEN));
        return ten;
    }

    /** Two more jacks. */
    static List<Card> skat() {
        return List.of(card(Card.Suit.SPADES, Card.Rank.JACK), card(Card.Suit.HEARTS, Card.Rank.JACK));
    }

    static SearchAiProvider base() {
        return new SearchAiProvider(new GreedyAiProvider(), Personality.clubPlayer(), 11L, WorldSource.UNIFORM);
    }

    record Exchange(Set<Card> discarded, SkatAi.ContractAnnouncement announced) {}

    /** The engine's calls after an auction won at {@code bid}: deal, pick-up, discard, announcement. */
    static Exchange exchange(SearchAiProvider player, int bid, boolean pickUpTold, Contract settled) {
        List<Card> twelve = new ArrayList<>(ten());
        twelve.addAll(skat());
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(ROUND, SkatAi.Seat.HUMAN, new LinkedHashSet<>(ten()), RULES));
            if (pickUpTold) session.pickUpSkat(new SkatAi.SkatChoiceContext(ROUND, SkatAi.Seat.HUMAN, bid));
            Set<Card> discarded = new HashSet<>(session.discardSkat(new SkatAi.SkatExchangeContext(ROUND,
                    SkatAi.Seat.HUMAN, new LinkedHashSet<>(twelve), skat(), settled)));
            if (settled != null) return new Exchange(discarded, null);
            List<Card> kept = new ArrayList<>(twelve);
            kept.removeAll(discarded);
            return new Exchange(discarded, session.announceContract(new SkatAi.ContractContext(ROUND,
                    SkatAi.Seat.HUMAN, new LinkedHashSet<>(kept), bid, true, RULES)));
        }
    }

    @Test public void theHandIsWhatTheCommentSays() {
        List<Card> twelve = new ArrayList<>(ten());
        twelve.addAll(skat());
        assertEquals(96, SkatRules.guaranteedValue(Contract.GRAND, twelve));
        assertEquals(48, SkatRules.guaranteedValue(Contract.CLUBS, twelve));
    }

    @Test public void offItAnnouncesItsIntentionAndOnItAnnouncesTheGameTheSkatMade() {
        Exchange off = exchange(base(), 18, true, null);
        assertNotEquals("the ten-card price does not see the Grand (else this hand tests nothing)",
                SkatAi.ContractType.GRAND, off.announced().type);
        Exchange on = exchange(base().withPostSkatContract(4), 18, true, null);
        assertEquals(SkatAi.ContractType.GRAND, on.announced().type);
        // And what it buried is ContractSearch's pick, made from the same stream.
        assertEquals(2, on.discarded().size());
        List<Card> twelve = new ArrayList<>(ten());
        twelve.addAll(skat());
        assertTrue("buried from the twelve", twelve.containsAll(on.discarded()));
        assertTrue("and it covers the bid with the kept ten and the skat it buried",
                SkatRules.guaranteedValue(Contract.GRAND, twelve) >= 18);
    }

    @Test public void aSettledContractAndAnExchangeWithoutABidAreLeftAlone() {
        Exchange settledOff = exchange(base(), 18, true, Contract.CLUBS);
        Exchange settledOn = exchange(base().withPostSkatContract(4), 18, true, Contract.CLUBS);
        assertEquals("told the contract, it buries for it as before", settledOff.discarded(), settledOn.discarded());
        Exchange untoldOff = exchange(base(), 18, false, null);
        Exchange untoldOn = exchange(base().withPostSkatContract(4), 18, false, null);
        assertEquals("no bid told, no re-choice", untoldOff.announced().type, untoldOn.announced().type);
        assertEquals(untoldOff.discarded(), untoldOn.discarded());
    }

    @Test public void theChoiceIsContractSearchsAndCoversTheBid() {
        List<Card> twelve = new ArrayList<>(ten());
        twelve.addAll(skat());
        List<Contract> games = List.of(Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS,
                Contract.GRAND);
        ContractSearch.Pick pick = ContractSearch.choose(twelve, 18, SkatAi.Seat.HUMAN, ROUND.forehand, games, 4,
                ordinal -> new Random(ordinal));
        assertEquals(Contract.GRAND, pick.contract());
        assertTrue("a chance", pick.chance() > 0.5 && pick.chance() <= 1.0);
        assertTrue(twelve.containsAll(pick.buried()) && pick.buried().size() == 2);
        assertEquals("only Grand covers 49", Contract.GRAND, ContractSearch.choose(twelve, 49, SkatAi.Seat.HUMAN,
                ROUND.forehand, games, 4, ordinal -> new Random(ordinal)).contract());
        assertNull("nothing covers 97", ContractSearch.choose(twelve, 97, SkatAi.Seat.HUMAN, ROUND.forehand,
                games, 4, ordinal -> new Random(ordinal)));
        assertTrue("worth: a sure game is its value, a sure loss twice its value lost",
                ContractSearch.worth(24, 1.0) == 24 && ContractSearch.worth(24, 0.0) == -48);
    }

    @Test public void theSettingSurvivesTheOtherBuildersAndTheDiscardSearch() {
        SearchAiProvider player = base().withPostSkatContract(16).withAdaptiveBidding().withMarginTiebreak(15)
                .withRuleTiebreak().withLadder().withNullWorlds(24).withNullTiebreak(RuleTiebreak.NullOrder.LOW_RANK)
                .withBiddingBudget(1L).withWorldThreads(2).withCardBudget(5L).withCardPlayObserver(r -> {})
                .withDiscardSearch(8, 5, 0L).withTemperature(0.1);
        assertEquals(16, player.postSkatWorlds());
        assertEquals("off unless asked for", 0, base().withDiscardSearch(8, 5, 0L).postSkatWorlds());
    }
}
