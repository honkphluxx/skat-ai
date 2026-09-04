package dev.skatklar.demo.solve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class DoubleDummySolverTest {

    // The solver models the trump games only: Null asks a different question
    // ("does the declarer take any trick at all?") and rejects the solver
    // outright, so the sweeps below iterate Contract.TRUMP_GAMES.


    /**
     * The load-bearing test. Alpha-beta, move ordering and the transposition
     * table are optimisations: they must not change the answer. Plain minimax is
     * the reference, and any disagreement is a bug in the fast path rather than a
     * judgement call.
     */
    @Test public void prunedSearchAgreesWithExhaustiveSearch() {
        for (Contract contract : Contract.TRUMP_GAMES) {
            for (int trial = 0; trial < 12; trial++) {
                long seed = contract.ordinal() * 1000L + trial;
                List<List<Card>> hands = randomHands(seed, 5);
                SkatAi.Seat declarer = SkatAi.Seat.values()[trial % 3];
                SkatAi.Seat leader = SkatAi.Seat.values()[(trial + 1) % 3];

                int slow = DoubleDummySolver.solveExhaustively(contract, declarer, hands, leader);
                int pruned = DoubleDummySolver.solve(contract, declarer, hands, leader, false)
                        .declarerPoints();
                int cached = DoubleDummySolver.solve(contract, declarer, hands, leader, true)
                        .declarerPoints();

                // Separated on purpose. The first version of the transposition
                // table keyed on two of the three hands, which does not identify a
                // position -- which cards seat 2 has already played is independent
                // of what the other two hold. It disagreed with minimax on a fifth
                // of these deals while alpha-beta alone was already correct, and
                // one assertion covering both would not have said which was wrong.
                assertEquals("alpha-beta, " + contract + " seed " + seed, slow, pruned);
                assertEquals("transpositions, " + contract + " seed " + seed, slow, cached);
            }
        }
    }

    /**
     * The null-window query is the whole reason the solver is fast enough to use
     * -- both the oracle and the player ask questions rather than for a value.
     * It must answer exactly what a full search would say about the same
     * threshold, on both sides of it, or every speed gain is a silent lie.
     */
    @Test public void nullWindowQueriesAgreeWithTheExactValue() {
        for (Contract contract : Contract.TRUMP_GAMES) {
            for (int trial = 0; trial < 8; trial++) {
                long seed = contract.ordinal() * 100L + trial;
                List<List<Card>> hands = randomHands(seed, 5);
                SkatAi.Seat declarer = SkatAi.Seat.values()[trial % 3];
                SkatAi.Seat leader = SkatAi.Seat.values()[(trial + 2) % 3];
                int exact = DoubleDummySolver.solve(contract, declarer, hands, leader)
                        .declarerPoints();
                for (int target : new int[] {1, 11, 21, 31, 45, 61, 90, 120}) {
                    assertEquals(contract + " seed " + seed + " target " + target,
                            exact >= target,
                            DoubleDummySolver.declarerReaches(contract, declarer, hands,
                                    leader, target));
                }
            }
        }
    }

    /** Choosing a card must not change what the position is worth. */
    @Test public void theChosenCardDefendsTheValueOfThePosition() {
        for (Contract contract : Contract.TRUMP_GAMES) {
            for (int trial = 0; trial < 8; trial++) {
                long seed = 500 + contract.ordinal() * 100L + trial;
                List<List<Card>> hands = randomHands(seed, 5);
                SkatAi.Seat declarer = SkatAi.Seat.values()[trial % 3];
                SkatAi.Seat leader = SkatAi.Seat.values()[(trial + 1) % 3];
                int exact = DoubleDummySolver.solve(contract, declarer, hands, leader)
                        .declarerPoints();
                DoubleDummySolver.Choice choice = DoubleDummySolver.bestCard(contract, declarer,
                        leader, hands, leader, List.of());
                assertEquals(contract + " seed " + seed, exact, choice.declarerPoints());
                assertTrue("the chosen card is one the player holds",
                        hands.get(leader.ordinal()).contains(choice.card()));
            }
        }
    }

    /**
     * The player's search reports bands rather than points, so it may return a
     * bound instead of the value. What it must never do is land in the wrong
     * band, because the band is what the score sheet pays.
     */
    @Test public void theResultSearchLandsInTheRightBand() {
        for (Contract contract : Contract.TRUMP_GAMES) {
            for (int trial = 0; trial < 8; trial++) {
                long seed = 900 + contract.ordinal() * 100L + trial;
                List<List<Card>> hands = randomHands(seed, 5);
                SkatAi.Seat declarer = SkatAi.Seat.values()[trial % 3];
                SkatAi.Seat leader = SkatAi.Seat.values()[(trial + 1) % 3];
                int banked = 17;   // an arbitrary but non-zero running score
                int exact = DoubleDummySolver.solve(contract, declarer, hands, leader)
                        .declarerPoints();
                DoubleDummySolver.Choice choice = DoubleDummySolver.bestCardForResult(
                        contract, declarer, leader, hands, leader, List.of(), banked);
                assertEquals(contract + " seed " + seed,
                        band(exact + banked), band(choice.declarerPoints() + banked));
            }
        }
    }

    /** Lost, won, Schneider: the three thresholds a Skat game is scored on. */
    private static int band(int declarerPoints) {
        if (declarerPoints >= 90) return 3;
        if (declarerPoints >= 61) return 2;
        if (declarerPoints >= 31) return 1;
        return 0;
    }

    @Test public void allTrumpsInOneHandTakesEverything() {
        // Clubs: the declarer holds all four jacks and the top three trumps, so
        // it wins every trick and therefore every point in play.
        List<Card> declarerCards = cards("CJ", "SJ", "HJ", "DJ", "CA", "CT", "CK");
        List<Card> left = cards("SA", "ST", "SK", "SQ", "S9", "S8", "S7");
        List<Card> right = cards("HA", "HT", "HK", "HQ", "H9", "H8", "H7");

        int inPlay = points(declarerCards) + points(left) + points(right);
        DoubleDummySolver.Result result = DoubleDummySolver.solve(Contract.CLUBS,
                SkatAi.Seat.HUMAN, List.of(declarerCards, left, right), SkatAi.Seat.HUMAN);

        assertEquals(inPlay, result.declarerPoints());
    }

    @Test public void aHandWithoutATrickTakesNothing() {
        // The declarer holds the three lowest cards of a suit nobody has to
        // follow, against two hands full of trumps. It cannot win a trick.
        List<Card> declarerCards = cards("H9", "H8", "H7");
        List<Card> left = cards("CJ", "SJ", "HJ");
        List<Card> right = cards("DJ", "CA", "CT");

        DoubleDummySolver.Result result = DoubleDummySolver.solve(Contract.CLUBS,
                SkatAi.Seat.HUMAN, List.of(declarerCards, left, right), SkatAi.Seat.HUMAN);

        assertEquals(0, result.declarerPoints());
    }

    @Test public void theValueIsIndependentOfWhoLeadsOnlyWhenItShouldBe() {
        // A sanity bound rather than an equality: whoever leads, the declarer can
        // never take more than the points actually in play, nor fewer than none.
        List<List<Card>> hands = randomHands(4242L, 6);
        int inPlay = points(hands.get(0)) + points(hands.get(1)) + points(hands.get(2));
        for (SkatAi.Seat leader : SkatAi.Seat.values()) {
            int value = DoubleDummySolver.solve(Contract.GRAND, SkatAi.Seat.OPPONENT_ONE,
                    hands, leader).declarerPoints();
            assertTrue("declarer points stay inside what is in play",
                    value >= 0 && value <= inPlay);
        }
    }

    /** A full ten-card deal has to be solvable, and quickly enough to be usable. */
    @Test public void solvesAFullDeal() {
        SkatDeck.Deal deal = SkatDeck.deal(new Random(7));
        List<List<Card>> hands = List.of(
                new ArrayList<>(deal.human),
                new ArrayList<>(deal.opponentOne),
                new ArrayList<>(deal.opponentTwo));

        long startedAt = System.nanoTime();
        DoubleDummySolver.Result result = DoubleDummySolver.solve(Contract.GRAND,
                SkatAi.Seat.HUMAN, hands, SkatAi.Seat.HUMAN);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue("a solved deal yields a sane point count",
                result.declarerPoints() >= 0 && result.declarerPoints() <= 120);
        assertTrue("ten tricks must solve in well under a second, was " + millis + " ms",
                millis < 1000);
    }

    @Test public void searchIsDeterministic() {
        List<List<Card>> hands = randomHands(99L, 7);
        int first = DoubleDummySolver.solve(Contract.SPADES, SkatAi.Seat.HUMAN,
                hands, SkatAi.Seat.OPPONENT_TWO).declarerPoints();
        int second = DoubleDummySolver.solve(Contract.SPADES, SkatAi.Seat.HUMAN,
                hands, SkatAi.Seat.OPPONENT_TWO).declarerPoints();
        assertEquals(first, second);
    }

    @Test public void pruningVisitsFarFewerNodesThanExhaustiveSearch() {
        List<List<Card>> hands = randomHands(2024L, 6);
        DoubleDummySolver.Result fast = DoubleDummySolver.solve(Contract.HEARTS,
                SkatAi.Seat.HUMAN, hands, SkatAi.Seat.HUMAN);
        assertTrue("a six-card deal should cost far less than brute force, was "
                        + fast.visitedNodes(),
                fast.visitedNodes() < 200_000);
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

    private static int points(List<Card> cards) {
        int total = 0;
        for (Card card : cards) {
            total += switch (card.rank) {
                case ACE -> 11;
                case TEN -> 10;
                case KING -> 4;
                case QUEEN -> 3;
                case JACK -> 2;
                default -> 0;
            };
        }
        return total;
    }
}
