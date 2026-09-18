package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * The position-aware order the search player uses for the ties it cannot
 * settle by search. Each case is one position and the card the rule says
 * comes out on top of a small candidate set.
 */
public class RuleTiebreakTest {

    private static final SkatAi.Seat ME = SkatAi.Seat.HUMAN;
    private static final SkatAi.Seat LEFT = SkatAi.Seat.OPPONENT_ONE;
    private static final SkatAi.Seat RIGHT = SkatAi.Seat.OPPONENT_TWO;

    private static Card card(Card.Suit suit, Card.Rank rank) { return new Card(suit, rank); }

    private static SkatAi.DecisionContext at(Contract contract, SkatAi.Seat declarer,
                                             List<SkatAi.PlayedCard> plays, List<Card> candidates) {
        SkatAi.GameDefinition game = new SkatAi.GameDefinition(declarer, LEFT, contract);
        SkatAi.Seat leader = plays.isEmpty() ? ME : plays.get(0).seat;
        return new SkatAi.DecisionContext(game, ME, new LinkedHashSet<>(candidates),
                new LinkedHashSet<>(candidates),
                new SkatAi.CurrentTrick(1, leader, plays),
                new SkatAi.GameHistory(List.of()),
                new SkatAi.DerivedGameKnowledge(Set.of(), Map.of(), Map.of(), Map.of()));
    }

    private static Card best(SkatAi.DecisionContext context) {
        Comparator<Card> order = RuleTiebreak.order(context);
        return context.legalCards.stream().max(order).orElseThrow();
    }

    private static Card bestByNullRank(SkatAi.DecisionContext context) {
        return context.legalCards.stream().max(RuleTiebreak.order(context, true)).orElseThrow();
    }

    @Test public void leadingPlaysTheFewestPointsThenTheLeastPower() {
        List<Card> touching = List.of(card(Card.Suit.HEARTS, Card.Rank.KING),
                card(Card.Suit.HEARTS, Card.Rank.QUEEN), card(Card.Suit.SPADES, Card.Rank.NINE));
        // Nine before queen before king: points first.
        assertEquals(card(Card.Suit.SPADES, Card.Rank.NINE),
                best(at(Contract.GRAND, ME, List.of(), touching)));
        // Equal points: the lower of the touching cards, the seven over the eight.
        List<Card> small = List.of(card(Card.Suit.HEARTS, Card.Rank.EIGHT), card(Card.Suit.HEARTS, Card.Rank.SEVEN));
        assertEquals(card(Card.Suit.HEARTS, Card.Rank.SEVEN),
                best(at(Contract.GRAND, ME, List.of(), small)));
    }

    @Test public void lastToPlayTakesTheTrickWithTheMostPointsAndLeastPower() {
        // Clubs led, the king is winning; I hold the ten and the ace of clubs
        // and a nine: both the ten and the ace take it, the ace brings one more.
        List<SkatAi.PlayedCard> plays = List.of(
                new SkatAi.PlayedCard(LEFT, card(Card.Suit.CLUBS, Card.Rank.KING)),
                new SkatAi.PlayedCard(RIGHT, card(Card.Suit.CLUBS, Card.Rank.SEVEN)));
        List<Card> hand = List.of(card(Card.Suit.CLUBS, Card.Rank.NINE),
                card(Card.Suit.CLUBS, Card.Rank.TEN), card(Card.Suit.CLUBS, Card.Rank.ACE));
        assertEquals(card(Card.Suit.CLUBS, Card.Rank.ACE), best(at(Contract.GRAND, ME, plays, hand)));

        // Both court cards take a nine-led trick; the king brings more home.
        List<SkatAi.PlayedCard> low = List.of(
                new SkatAi.PlayedCard(LEFT, card(Card.Suit.CLUBS, Card.Rank.NINE)),
                new SkatAi.PlayedCard(RIGHT, card(Card.Suit.CLUBS, Card.Rank.EIGHT)));
        List<Card> court = List.of(card(Card.Suit.CLUBS, Card.Rank.QUEEN), card(Card.Suit.CLUBS, Card.Rank.KING));
        assertEquals(card(Card.Suit.CLUBS, Card.Rank.KING), best(at(Contract.GRAND, ME, low, court)));
    }

    @Test public void lastToPlayAndLosingGivesTheFewestPoints() {
        // The ace of clubs is winning for the declarer; I cannot beat it.
        List<SkatAi.PlayedCard> plays = List.of(
                new SkatAi.PlayedCard(LEFT, card(Card.Suit.CLUBS, Card.Rank.ACE)),
                new SkatAi.PlayedCard(RIGHT, card(Card.Suit.CLUBS, Card.Rank.SEVEN)));
        List<Card> hand = List.of(card(Card.Suit.CLUBS, Card.Rank.TEN), card(Card.Suit.CLUBS, Card.Rank.NINE),
                card(Card.Suit.CLUBS, Card.Rank.KING));
        assertEquals(card(Card.Suit.CLUBS, Card.Rank.NINE), best(at(Contract.GRAND, LEFT, plays, hand)));
    }

    @Test public void lastToPlayBehindThePartnerGivesTheMostPoints() {
        // My partner (RIGHT) holds the trick over the declarer (LEFT): schmieren.
        List<SkatAi.PlayedCard> plays = List.of(
                new SkatAi.PlayedCard(LEFT, card(Card.Suit.CLUBS, Card.Rank.KING)),
                new SkatAi.PlayedCard(RIGHT, card(Card.Suit.CLUBS, Card.Rank.ACE)));
        List<Card> hand = List.of(card(Card.Suit.HEARTS, Card.Rank.NINE), card(Card.Suit.HEARTS, Card.Rank.TEN),
                card(Card.Suit.HEARTS, Card.Rank.KING));
        assertEquals(card(Card.Suit.HEARTS, Card.Rank.TEN), best(at(Contract.GRAND, LEFT, plays, hand)));
    }

    @Test public void aTakerOutranksAGiverWhateverThePoints() {
        // Spades led at a Hearts game and I am void: the seven of hearts
        // trumps the declarer's ten, the ace of diamonds merely gives eleven.
        List<SkatAi.PlayedCard> plays = List.of(
                new SkatAi.PlayedCard(LEFT, card(Card.Suit.SPADES, Card.Rank.TEN)),
                new SkatAi.PlayedCard(RIGHT, card(Card.Suit.SPADES, Card.Rank.EIGHT)));
        List<Card> hand = List.of(card(Card.Suit.DIAMONDS, Card.Rank.ACE), card(Card.Suit.HEARTS, Card.Rank.SEVEN));
        assertEquals(card(Card.Suit.HEARTS, Card.Rank.SEVEN), best(at(Contract.HEARTS, LEFT, plays, hand)));
    }

    /**
     * A Null is scored on tricks and not on card points, so the shipped order
     * -- fewest points first -- is sorting by a quantity the contract does not
     * use, and it inverts Null's own rank wherever a ten meets a court card.
     * This is the measurement that named the defect: the queen came out ahead
     * of the ten.
     */
    @Test public void theShippedOrderSortsANullByAQuantityItDoesNotScore() {
        List<Card> tie = List.of(card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.QUEEN), card(Card.Suit.CLUBS, Card.Rank.SEVEN));
        // Null rank, low to high: 7 8 9 10 J Q K A -- the ten is below the queen.
        assertEquals("the shipped order prefers the seven, which costs nothing",
                card(Card.Suit.CLUBS, Card.Rank.SEVEN), best(at(Contract.NULL, ME, List.of(), tie)));
        List<Card> pair = List.of(card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.QUEEN));
        assertEquals("and between a ten and a queen it takes the queen, by points",
                card(Card.Suit.CLUBS, Card.Rank.QUEEN), best(at(Contract.NULL, ME, List.of(), pair)));
    }

    /** The declarer sheds its highest safe card: high cards are what lose a Null. */
    @Test public void aNullDeclarerShedsTheHighestSafeCard() {
        List<Card> tie = List.of(card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.QUEEN), card(Card.Suit.CLUBS, Card.Rank.SEVEN));
        assertEquals(card(Card.Suit.CLUBS, Card.Rank.QUEEN),
                bestByNullRank(at(Contract.NULL, ME, List.of(), tie)));
        // Following, behind a declarer that has already played, it still sheds.
        List<SkatAi.PlayedCard> played = List.of(
                new SkatAi.PlayedCard(ME, card(Card.Suit.HEARTS, Card.Rank.SEVEN)));
        assertEquals(card(Card.Suit.CLUBS, Card.Rank.QUEEN),
                bestByNullRank(at(Contract.NULL, ME, played, tie)));
    }

    /**
     * A defender in front of the declarer plays its lowest card, because a low
     * card is what forces the declarer over; behind a declarer that has already
     * committed a card, the low card would be spent for nothing, so it sheds.
     */
    @Test public void aNullDefenderLeadsLowAndDiscardsHigh() {
        List<Card> tie = List.of(card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.QUEEN), card(Card.Suit.CLUBS, Card.Rank.SEVEN));
        assertEquals("on lead, the ammunition",
                card(Card.Suit.CLUBS, Card.Rank.SEVEN),
                bestByNullRank(at(Contract.NULL, LEFT, List.of(), tie)));

        List<SkatAi.PlayedCard> partnerOnly = List.of(
                new SkatAi.PlayedCard(RIGHT, card(Card.Suit.HEARTS, Card.Rank.SEVEN)));
        assertEquals("behind a partner, with the declarer still to play, still low",
                card(Card.Suit.CLUBS, Card.Rank.SEVEN),
                bestByNullRank(at(Contract.NULL, LEFT, partnerOnly, tie)));

        List<SkatAi.PlayedCard> declarerPlayed = List.of(
                new SkatAi.PlayedCard(LEFT, card(Card.Suit.HEARTS, Card.Rank.SEVEN)));
        assertEquals("behind the declarer, the low card is spent for nothing",
                card(Card.Suit.CLUBS, Card.Rank.QUEEN),
                bestByNullRank(at(Contract.NULL, LEFT, declarerPlayed, tie)));
    }

    /** Nothing about a trump game changes when the Null branch is switched on. */
    @Test public void theNullBranchTouchesNoTrumpGame() {
        List<Card> hand = List.of(card(Card.Suit.CLUBS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.QUEEN), card(Card.Suit.CLUBS, Card.Rank.SEVEN));
        for (Contract contract : List.of(Contract.GRAND, Contract.CLUBS, Contract.HEARTS)) {
            SkatAi.DecisionContext context = at(contract, ME, List.of(), hand);
            assertEquals(contract.toString(), best(context), bestByNullRank(context));
        }
    }
}
