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

/**
 * A solver that keeps its transposition table must answer what a fresh one does.
 *
 * <p>{@link DoubleDummySolver#reusableFor} is the one caller that lets a table
 * outlive the question that filled it, and it is the only path through the table
 * that nothing else here covers. The risk it carries is specific: an entry
 * written while answering one question is still sitting there when the next
 * arrives, and if the key or the stored bound did not fully describe the position
 * it was taken from, the second answer is the first one leaking through. That
 * failure is silent — the search returns a plausible number, no exception, no
 * illegal move — and it would show up only as a player that is mysteriously
 * wrong in the endgame.
 *
 * <p>Three things have to hold, and each of them is a different way the table
 * could be unsound:
 *
 * <ul>
 *   <li><b>Across deals.</b> The same solver is asked about unrelated hands. The
 *       key packs all three hands, so entries from one deal must never match in
 *       another; if they did, they would answer for cards that are not there.</li>
 *   <li><b>Across targets.</b> The same position is asked at many thresholds.
 *       Values are points still to be won and bounds are stored with their kind,
 *       so an entry written under one window must stay valid under a different
 *       one. This is the property that makes a shared table legitimate at all.</li>
 *   <li><b>Across orders.</b> The same set of questions, shuffled. A table that
 *       is sound cannot care what order it was filled in.</li>
 * </ul>
 *
 * <p>The reference is a fresh solver per question, which is the same search with
 * an empty table — and that path is pinned to plain minimax by
 * {@link DoubleDummySolverTest#prunedSearchAgreesWithExhaustiveSearch}. So this
 * test inherits that anchor rather than trusting the fast path twice.
 *
 * <p><b>What it cannot see.</b> Deleting the leader check at the slot leaves all
 * three tests passing. That is not a hole in the tests so much as a fact about
 * the table: the leader is mixed into the bucket hash, so two positions that
 * differ only in who leads almost never share a bucket, and the check at the slot
 * is a second line of defence that fires only on a hash collision. Removing it
 * would be wrong — the hands alone do not determine the leader, and
 * {@code theValueIsIndependentOfWhoLeadsOnlyWhenItShouldBe} shows the answer
 * depends on it — but the damage would be one wrong entry in hundreds of
 * thousands, which is beneath what a test of this shape can resolve. Worth
 * knowing before someone deletes it as dead code.
 */
public class ReusedSolverTest {

    /** Five cards a hand: the regime the reusable solver is actually used in. */
    private static final int CARDS_EACH = 5;

    private record Question(int[] hands, int toPlay, int leader, int trickCards,
                            int trickSize, int target) {}

    @Test public void aReusedTableAnswersWhatAFreshOneDoes() {
        for (Contract contract : Contract.TRUMP_GAMES) {
            for (int declarer = 0; declarer < 3; declarer++) {
                List<Question> questions = questions(contract, declarer, new Random(contract.ordinal() * 31L + declarer));
                DoubleDummySolver reused = DoubleDummySolver.reusableFor(contract, declarer);
                int asked = 0;
                int reachable = 0;
                for (Question question : questions) {
                    boolean fresh = DoubleDummySolver.declarerReaches(contract, declarer,
                            question.toPlay, question.hands, question.leader,
                            question.trickCards, question.trickSize, question.target);
                    boolean warm = reused.reaches(question.toPlay, question.hands,
                            question.leader, question.trickCards, question.trickSize,
                            question.target);
                    assertEquals("a warm table disagreed about " + contract
                            + " at target " + question.target, fresh, warm);
                    asked++;
                    if (fresh) reachable++;
                }
                assertTrue("no questions were asked", asked >= 40);
                // Agreement is worthless if every answer is the same answer. The
                // first draft asked for 61 points out of a fifteen-card deal and
                // got "no" forty times, and would have agreed just as happily
                // with a solver that always says no.
                assertTrue("the questions were all trivial: " + reachable + " of "
                                + asked + " reachable",
                        reachable > asked / 5 && reachable < asked - asked / 5);
            }
        }
    }

    /**
     * The same question twice, with other questions in between.
     *
     * <p>A table that has been written to since the first answer must still
     * produce it. This is the cheapest possible check and it is the one that
     * would catch an entry being overwritten by a position it does not describe.
     */
    @Test public void askingTwiceGivesTheSameAnswer() {
        Contract contract = Contract.GRAND;
        DoubleDummySolver reused = DoubleDummySolver.reusableFor(contract, 0);
        List<Question> questions = questions(Contract.GRAND, 0, new Random(7));
        boolean[] first = new boolean[questions.size()];
        for (int at = 0; at < questions.size(); at++) {
            Question question = questions.get(at);
            first[at] = reused.reaches(question.toPlay(), question.hands(), question.leader(),
                    question.trickCards(), question.trickSize(), question.target());
        }
        for (int at = questions.size() - 1; at >= 0; at--) {
            Question question = questions.get(at);
            boolean again = reused.reaches(question.toPlay(), question.hands(),
                    question.leader(), question.trickCards(), question.trickSize(),
                    question.target());
            assertEquals("the answer moved when the table filled up", first[at], again);
        }
    }

    /** Shuffling the questions must not move a single answer. */
    @Test public void theOrderOfTheQuestionsDoesNotMatter() {
        Contract contract = Contract.CLUBS;
        List<Question> questions = questions(Contract.CLUBS, 1, new Random(11));
        boolean[] inOrder = new boolean[questions.size()];
        DoubleDummySolver forwards = DoubleDummySolver.reusableFor(contract, 1);
        for (int at = 0; at < questions.size(); at++) {
            Question question = questions.get(at);
            inOrder[at] = forwards.reaches(question.toPlay(), question.hands(),
                    question.leader(), question.trickCards(), question.trickSize(),
                    question.target());
        }

        List<Integer> order = new ArrayList<>();
        for (int at = 0; at < questions.size(); at++) order.add(at);
        Collections.shuffle(order, new Random(13));
        DoubleDummySolver shuffled = DoubleDummySolver.reusableFor(contract, 1);
        for (int at : order) {
            Question question = questions.get(at);
            assertEquals("the answer depended on when it was asked",
                    inOrder[at], shuffled.reaches(question.toPlay(), question.hands(),
                            question.leader(), question.trickCards(),
                            question.trickSize(), question.target()));
        }
    }

    /**
     * A mixed batch: several deals, each asked at several thresholds, some of
     * them from the middle of a trick.
     */
    private static List<Question> questions(Contract contract, int declarer, Random random) {
        List<Question> questions = new ArrayList<>();
        int deals = 0;
        for (int attempt = 0; deals < 4 && attempt < 200; attempt++) {
            int deal = deals;
            List<Card> deck = new ArrayList<>(SkatDeck.ordered());
            Collections.shuffle(deck, random);
            List<List<Card>> dealt = new ArrayList<>();
            int[] hands = new int[3];
            for (int seat = 0; seat < 3; seat++) {
                List<Card> hand = new ArrayList<>(
                        deck.subList(seat * CARDS_EACH, seat * CARDS_EACH + CARDS_EACH));
                dealt.add(hand);
                for (Card card : hand) hands[seat] |= 1 << ContractTables.index(card);
            }
            int leader = deal % 3;

            // Targets aimed at the answer rather than at round numbers. A
            // threshold far from what the declarer can actually take is a
            // question with a trivial answer, and a batch of those would agree
            // just as happily with a solver that always says no -- which is what
            // the first draft of this test did, forty times in a row. Straddling
            // the true value also puts every question near the decision
            // boundary, which is exactly where a wrong table entry shows.
            int exact = DoubleDummySolver.solveExhaustively(contract,
                    SkatAi.Seat.values()[declarer], dealt, SkatAi.Seat.values()[leader]);
            // Five random cards a hand hands the declarer a hopeless deal often
            // enough to matter: when the true value is zero, every threshold
            // clamps to one and every answer is "no". Those deals are dropped
            // rather than counted, because a batch of them is a batch that
            // cannot fail.
            if (exact < 12) continue;
            deals++;
            for (int offset : new int[] {-9, -4, -1, 0, 1, 5}) {
                int target = Math.max(1, exact + offset);
                questions.add(new Question(hands.clone(), leader, leader, 0, 0, target));

                // The same deal one card into the trick: the leader's card is out
                // of its hand and on the table, so this is a position the caller
                // really does ask about and the table really does have to keep
                // apart from the one above.
                int led = Integer.numberOfTrailingZeros(Integer.highestOneBit(hands[leader]));
                int[] midTrick = hands.clone();
                midTrick[leader] &= ~(1 << led);
                questions.add(new Question(midTrick, (leader + 1) % 3, leader,
                        led, 1, target));
            }
        }
        assertEquals("could not draw four deals worth asking about", 4, deals);
        return questions;
    }

}
