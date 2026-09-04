package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Schieberamsch: what is played when nobody wants the game.
 *
 * <p>Two things are being tested here and they are worth separating. The
 * arithmetic — who loses, what it costs, when it doubles — is the canon written
 * out as numbers, and it is checked against {@link SkatRules} directly. The rest
 * is a property the engine has to keep whatever the players do: three passes
 * produce a played deal, the Schieben moves cards without inventing or losing
 * any, and no jack ever reaches the skat.
 */
public class RamschGameTest {

    private static final Card CLUB_JACK = new Card(Card.Suit.CLUBS, Card.Rank.JACK);

    /** Passes everything, then plays legally at random. Everyone's Ramsch player. */
    private static final class AlwaysPasses implements SkatAiProvider {
        private final Random random;

        AlwaysPasses(Random random) { this.random = random; }

        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("passes", "Passes everything", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public int bid(SkatAi.BidRequest request) { return 0; }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    List<Card> legal = new ArrayList<>(context.legalCards);
                    return legal.get(random.nextInt(legal.size()));
                }
            };
        }
    }

    // ------------------------------------------------------------ arithmetic

    @Test public void theLoserIsTheOneWithTheMostAndTheSkatComesAfterwards() {
        // 51 and 55 in tricks, 14 in the skat. The skat cannot change who loses,
        // only what it costs -- which is what "unbesehen" means.
        List<SkatAi.CompletedTrick> tricks = tricksWorth(0, 51, 55);
        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricks, points(14));

        assertEquals(SkatAi.Seat.OPPONENT_TWO, score.scoredSeat());
        assertEquals(69, score.cardPoints());
    }

    @Test public void aJungfrauDoublesTheWholeTotalIncludingTheSkat() {
        // The same deal: the first seat took no trick at all.
        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricksWorth(0, 51, 55), points(14));

        assertTrue(score.jungfrau());
        assertEquals(-138, score.value());
    }

    @Test public void withoutAJungfrauTheLoserPaysOnce() {
        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricksWorth(24, 27, 55), points(14));

        assertFalse(score.jungfrau());
        assertEquals(-69, score.value());
    }

    @Test public void aTieGoesToWhoeverWonTheLaterTrick() {
        List<SkatAi.CompletedTrick> tricks = new ArrayList<>();
        tricks.add(trick(0, SkatAi.Seat.HUMAN, 40));
        tricks.add(trick(1, SkatAi.Seat.OPPONENT_ONE, 40));
        tricks.add(trick(2, SkatAi.Seat.OPPONENT_TWO, 26));
        for (int number = 3; number < 10; number++) {
            tricks.add(trick(number, SkatAi.Seat.OPPONENT_TWO, 0));
        }
        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricks, points(14));

        assertEquals(SkatAi.Seat.OPPONENT_ONE, score.scoredSeat());
        assertEquals(-54, score.value());
    }

    @Test public void aDurchmarschWinsAtAFlatHundredAndTwenty() {
        List<SkatAi.CompletedTrick> tricks = new ArrayList<>();
        for (int number = 0; number < 10; number++) {
            tricks.add(trick(number, SkatAi.Seat.OPPONENT_ONE, number == 0 ? 106 : 0));
        }
        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricks, points(14));

        assertTrue(score.durchmarsch());
        assertFalse("two jungfrauen are a durchmarsch, not a doubling", score.jungfrau());
        assertEquals(120, score.value());
        assertEquals(SkatAi.Seat.OPPONENT_ONE, score.scoredSeat());
    }

    @Test public void jacksMayNotBePushed() {
        List<Card> hand = List.of(CLUB_JACK,
                new Card(Card.Suit.HEARTS, Card.Rank.ACE),
                new Card(Card.Suit.HEARTS, Card.Rank.SEVEN));

        assertFalse(SkatRules.legalRamschPush(
                Set.of(CLUB_JACK, hand.get(1)), hand));
        assertTrue(SkatRules.legalRamschPush(
                Set.of(hand.get(1), hand.get(2)), hand));
    }

    @Test public void theDefaultPushShedsTheDearestNonJacks() {
        List<Card> hand = List.of(CLUB_JACK,
                new Card(Card.Suit.HEARTS, Card.Rank.ACE),
                new Card(Card.Suit.SPADES, Card.Rank.TEN),
                new Card(Card.Suit.HEARTS, Card.Rank.SEVEN));

        Set<Card> pushed = SkatRules.defaultRamschPush(hand);

        assertEquals(2, pushed.size());
        assertTrue(pushed.contains(hand.get(1)));
        assertTrue(pushed.contains(hand.get(2)));
    }

    // ---------------------------------------------------------------- engine

    @Test public void threePassesProduceAPlayedRamschRatherThanANewDeal() {
        GameEngine engine = playOut(7);
        GameEngine.Snapshot snapshot = engine.snapshot();

        assertEquals(Contract.RAMSCH, snapshot.definition.contract);
        assertNull("a Ramsch has no declarer", snapshot.definition.declarer);
        assertNotNull(snapshot.result);
        assertEquals(10, snapshot.history.size());
        assertNotNull(snapshot.result.scoredSeat);
    }

    /**
     * The Schieben is three exchanges and no card may be created or lost in them.
     * Checked at the end rather than between legs, because that is where it would
     * actually hurt: a duplicated card only shows up as an impossible trick.
     */
    @Test public void theSchiebenConservesTheDeck() {
        for (long seed = 0; seed < 12; seed++) {
            GameEngine engine = deal(seed);
            GameEngine.Snapshot snapshot = engine.snapshot();

            LinkedHashSet<Card> seen = new LinkedHashSet<>();
            for (List<Card> hand : snapshot.hands) {
                assertEquals("ten cards after the Schieben", 10, hand.size());
                seen.addAll(hand);
            }
            seen.addAll(snapshot.skat);
            assertEquals("32 distinct cards", 32, seen.size());
            assertEquals(2, snapshot.skat.size());
        }
    }

    /**
     * No jack can reach the skat, because the skat <em>is</em> rearhand's push
     * and a push may not contain one. This is the rule that keeps the trump out
     * of a Ramsch's blind spot.
     */
    @Test public void noJackEverReachesTheSkat() {
        for (long seed = 0; seed < 12; seed++) {
            for (Card card : deal(seed).snapshot().skat) {
                assertFalse("a jack in the skat: " + card, card.rank == Card.Rank.JACK);
            }
        }
    }

    @Test public void everyRamschScoresExactlyOneSeat() {
        for (long seed = 0; seed < 8; seed++) {
            SkatAi.GameResult result = playOut(seed).snapshot().result;
            if (!result.game.isRamsch()) continue;

            assertNotNull(result.scoredSeat);
            if (result.durchmarsch) {
                assertEquals(120, result.gameValue);
            } else {
                assertTrue("a Ramsch is lost, not won: " + result.gameValue,
                        result.gameValue < 0);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static GameEngine deal(long seed) {
        Random random = new Random(seed);
        GameEngine engine = new GameEngine(random, new AlwaysPasses(random));
        engine.restart();
        return engine;
    }

    /**
     * Plays the deal out. The human seat is played too, because auto-bidding
     * seats a person at the table and a Ramsch does not excuse them from it.
     */
    private static GameEngine playOut(long seed) {
        GameEngine engine = deal(seed);
        for (int step = 0; step < 128 && engine.snapshot().result == null; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else if (snapshot.waitingForHuman()) {
                engine.playHumanCard(snapshot.legalCards.iterator().next());
            } else {
                engine.playAiCard();
            }
        }
        return engine;
    }

    /** Ten tricks whose points land on the three seats in the given amounts. */
    private static List<SkatAi.CompletedTrick> tricksWorth(int human, int one, int two) {
        List<SkatAi.CompletedTrick> tricks = new ArrayList<>();
        int number = 0;
        if (human > 0) tricks.add(trick(number++, SkatAi.Seat.HUMAN, human));
        tricks.add(trick(number++, SkatAi.Seat.OPPONENT_ONE, one));
        tricks.add(trick(number++, SkatAi.Seat.OPPONENT_TWO, two));
        while (number < 10) {
            tricks.add(trick(number, SkatAi.Seat.OPPONENT_ONE, 0));
            number++;
        }
        return tricks;
    }

    /**
     * A trick that is only its winner and its worth. The plays are filler: every
     * caller here is testing the settlement, which reads nothing else.
     */
    private static SkatAi.CompletedTrick trick(int number, SkatAi.Seat winner, int cardPoints) {
        List<SkatAi.PlayedCard> plays = List.of(new SkatAi.PlayedCard(winner, CLUB_JACK));
        return new SkatAi.CompletedTrick(number, winner, plays, winner, cardPoints);
    }

    /** Any set of cards worth exactly {@code wanted}, for the skat. */
    private static List<Card> points(int wanted) {
        if (wanted == 14) {
            return List.of(new Card(Card.Suit.DIAMONDS, Card.Rank.ACE),
                    new Card(Card.Suit.DIAMONDS, Card.Rank.QUEEN));
        }
        throw new IllegalArgumentException("No skat worth " + wanted);
    }
}
