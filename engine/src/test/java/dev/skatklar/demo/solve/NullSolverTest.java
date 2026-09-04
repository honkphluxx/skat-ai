package dev.skatklar.demo.solve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class NullSolverTest {

    /**
     * The load-bearing test, as for the points solver: the transposition table
     * and the move ordering are optimisations and must not change the answer.
     * Plain boolean minimax is the reference.
     */
    @Test public void theFastSearchAgreesWithExhaustiveSearch() {
        for (int trial = 0; trial < 40; trial++) {
            List<List<Card>> hands = randomHands(trial, 5);
            SkatAi.Seat declarer = SkatAi.Seat.values()[trial % 3];
            SkatAi.Seat leader = SkatAi.Seat.values()[(trial + 1) % 3];
            assertEquals("seed " + trial,
                    NullSolver.survivesExhaustively(declarer, hands, leader),
                    NullSolver.declarerSurvives(declarer, hands, leader));
        }
    }

    /** The lowest cards of three suits cannot be forced to take a trick. */
    @Test public void theLowestCardsInEverySuitAlwaysSurvive() {
        List<Card> declarerCards = cards("S7", "S8", "H7", "H8", "D7", "D8");
        List<Card> left = cards("SA", "SK", "HA", "HK", "DA", "DK");
        List<Card> right = cards("SQ", "SJ", "HQ", "HJ", "DQ", "DJ");
        for (SkatAi.Seat leader : SkatAi.Seat.values()) {
            assertTrue("a hand of sevens and eights takes no trick, led by " + leader,
                    NullSolver.declarerSurvives(SkatAi.Seat.HUMAN,
                            List.of(declarerCards, left, right), leader));
        }
    }

    /**
     * A bare ace is the classic Null death: the defence leads the suit, the
     * declarer has to follow, and the ace takes the trick it cannot refuse.
     */
    @Test public void aBareAceCannotBeDucked() {
        List<Card> declarerCards = cards("SA", "H7", "H8");
        List<Card> left = cards("S7", "HA", "HK");
        List<Card> right = cards("S8", "HQ", "HJ");
        assertFalse("an unprotected ace loses the game",
                NullSolver.declarerSurvives(SkatAi.Seat.HUMAN,
                        List.of(declarerCards, left, right), SkatAi.Seat.OPPONENT_ONE));
    }

    /** Being void is safety in Null: a card you never have to follow with. */
    @Test public void aVoidSuitIsNotADanger() {
        List<Card> declarerCards = cards("S7", "S8", "S9");
        List<Card> left = cards("HA", "HK", "HQ");
        List<Card> right = cards("DA", "DK", "DQ");
        assertTrue("nothing can be led that the declarer must win",
                NullSolver.declarerSurvives(SkatAi.Seat.HUMAN,
                        List.of(declarerCards, left, right), SkatAi.Seat.OPPONENT_ONE));
    }

    /** A full deal has to solve, and fast enough to sample many of them. */
    @Test public void aFullDealSolvesQuickly() {
        SkatDeck.Deal deal = SkatDeck.deal(new Random(5));
        List<List<Card>> hands = List.of(
                new ArrayList<>(deal.human),
                new ArrayList<>(deal.opponentOne),
                new ArrayList<>(deal.opponentTwo));
        long startedAt = System.nanoTime();
        NullSolver.declarerSurvives(SkatAi.Seat.HUMAN, hands, SkatAi.Seat.HUMAN);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue("a boolean game should be far cheaper than a points game, was "
                + millis + " ms", millis < 200);
    }

    /** Every verdict must match a search of the position after that card. */
    @Test public void perMoveVerdictsAgreeWithASearchOfTheChild() {
        for (int trial = 0; trial < 12; trial++) {
            List<List<Card>> hands = randomHands(100 + trial, 4);
            SkatAi.Seat declarer = SkatAi.Seat.values()[trial % 3];
            SkatAi.Seat leader = SkatAi.Seat.values()[(trial + 2) % 3];
            for (NullSolver.Verdict verdict
                    : NullSolver.movesSurviving(declarer, leader, hands, leader, List.of())) {
                List<List<Card>> after = new ArrayList<>();
                for (List<Card> hand : hands) after.add(new ArrayList<>(hand));
                after.get(leader.ordinal()).remove(verdict.card());
                List<NullSolver.Verdict> next = NullSolver.movesSurviving(declarer,
                        leader.next(), after, leader, List.of(verdict.card()));
                boolean expected = leader.next() == declarer
                        ? next.stream().anyMatch(NullSolver.Verdict::declarerSurvives)
                        : next.stream().allMatch(NullSolver.Verdict::declarerSurvives);
                assertEquals(verdict.card() + " on seed " + trial,
                        expected, verdict.declarerSurvives());
            }
        }
    }

    private static List<List<Card>> randomHands(long seed, int perHand) {
        List<Card> pack = new ArrayList<>(SkatDeck.ordered());
        Collections.shuffle(pack, new Random(seed));
        List<List<Card>> hands = new ArrayList<>(3);
        for (int seat = 0; seat < 3; seat++) {
            hands.add(new ArrayList<>(pack.subList(seat * perHand, (seat + 1) * perHand)));
        }
        return hands;
    }

    private static List<Card> cards(String... shorthand) {
        List<Card> result = new ArrayList<>();
        for (String text : shorthand) {
            Card.Suit suit = switch (text.charAt(0)) {
                case 'C' -> Card.Suit.CLUBS;
                case 'S' -> Card.Suit.SPADES;
                case 'H' -> Card.Suit.HEARTS;
                default -> Card.Suit.DIAMONDS;
            };
            Card.Rank rank = switch (text.charAt(1)) {
                case 'A' -> Card.Rank.ACE;
                case 'T' -> Card.Rank.TEN;
                case 'K' -> Card.Rank.KING;
                case 'Q' -> Card.Rank.QUEEN;
                case 'J' -> Card.Rank.JACK;
                case '9' -> Card.Rank.NINE;
                case '8' -> Card.Rank.EIGHT;
                default -> Card.Rank.SEVEN;
            };
            result.add(new Card(suit, rank));
        }
        return result;
    }
}
