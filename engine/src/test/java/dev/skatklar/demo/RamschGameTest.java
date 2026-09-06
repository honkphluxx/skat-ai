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

    /**
     * The Schieben's own price. Every leg that travelled unopened doubles the
     * Ramsch, and three legs is the whole table refusing to look.
     */
    @Test public void everyLegPushedOnUnopenedDoublesTheRamsch() {
        List<SkatAi.CompletedTrick> tricks = tricksWorth(24, 27, 55);

        assertEquals(-69, SkatRules.scoreRamsch(tricks, points(14), 0).value());
        assertEquals(-138, SkatRules.scoreRamsch(tricks, points(14), 1).value());
        assertEquals(-276, SkatRules.scoreRamsch(tricks, points(14), 2).value());
        assertEquals(-552, SkatRules.scoreRamsch(tricks, points(14), 3).value());
        assertEquals(3, SkatRules.scoreRamsch(tricks, points(14), 3).doublings());
    }

    /** One ladder, not two multiplications: a jungfrau is one more rung on it. */
    @Test public void aJungfrauIsOneMoreDoublingOnTheSameLadder() {
        SkatRules.RamschScore score =
                SkatRules.scoreRamsch(tricksWorth(0, 51, 55), points(14), 3);

        assertTrue(score.jungfrau());
        assertEquals(4, score.doublings());
        assertEquals(-1104, score.value());
    }

    /**
     * A durchmarsch doubles with the rest. It is what the Ramsch turned out to
     * be worth, and the table raised those stakes before anybody knew who would
     * be paying them.
     */
    @Test public void aDurchmarschDoublesWithTheSchieben() {
        List<SkatAi.CompletedTrick> tricks = new ArrayList<>();
        for (int number = 0; number < 10; number++) {
            tricks.add(trick(number, SkatAi.Seat.OPPONENT_ONE, number == 0 ? 106 : 0));
        }
        SkatRules.RamschScore score = SkatRules.scoreRamsch(tricks, points(14), 3);

        assertTrue(score.durchmarsch());
        assertFalse("a durchmarsch has no jungfrau to add", score.jungfrau());
        assertEquals(960, score.value());
        assertEquals(3, score.doublings());
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

    // ------------------------------------------------- pushed on unopened

    /**
     * The two cards stop in front of the seat, not in its hand. Everything about
     * the blind push follows from that: a hand of ten is what makes pushing them
     * on without looking possible at all.
     */
    @Test public void theTwoCardsAreOfferedBeforeTheyAreInTheHand() {
        GameEngine engine = offeredRamsch(3);

        assertTrue(engine.awaitingTakeUp());
        assertEquals(SkatAi.Seat.HUMAN, engine.pushingSeat());
        List<Card> offered = engine.receivedCards();
        assertEquals(2, offered.size());
        List<Card> hand = engine.snapshot().hands.get(GameEngine.HUMAN);
        assertEquals(10, hand.size());
        for (Card card : offered) {
            assertFalse("an offered card is not in the hand yet", hand.contains(card));
        }
    }

    /** Nothing may be pushed out of a hand that has not been handed anything. */
    @Test public void theHandIsNotAChooserWhileTheCardsAreOnOffer() {
        GameEngine engine = offeredRamsch(3);
        Card own = engine.snapshot().hands.get(GameEngine.HUMAN).stream()
                .filter(card -> card.rank != Card.Rank.JACK).findFirst().orElseThrow();

        assertFalse(engine.pushOne(own));
        assertTrue(engine.snapshot().pendingDiscards.isEmpty());
    }

    /**
     * Taking them up is the game as it has always been played: twelve cards, two
     * of the seat's own choosing go on, and the offer is over.
     */
    @Test public void takingThemUpLeavesTheOrdinarySchieben() {
        GameEngine engine = offeredRamsch(3);
        List<Card> offered = engine.receivedCards();

        assertTrue(engine.takeUpPush());
        assertFalse(engine.awaitingTakeUp());
        assertTrue(engine.awaitingPush());
        List<Card> twelve = engine.snapshot().hands.get(GameEngine.HUMAN);
        assertEquals(12, twelve.size());
        assertTrue(twelve.containsAll(offered));
        assertEquals(0, engine.blindPushes());

        List<Card> pushable = new ArrayList<>();
        for (Card card : twelve) {
            if (card.rank != Card.Rank.JACK && pushable.size() < 2) pushable.add(card);
        }
        assertTrue(engine.pushOne(pushable.get(0)));
        assertTrue(engine.pushOne(pushable.get(1)));
        assertEquals(GameEngine.Phase.PLAY, engine.confirmPush());
    }

    /**
     * Pushing on unopened: the same two cards travel, the ten stay exactly as
     * they were dealt, and the Schieben runs on to the end without this seat ever
     * having chosen anything.
     */
    @Test public void pushingOnUnopenedKeepsTheTenAndSendsWhatArrived() {
        GameEngine engine = offeredRamsch(3);
        List<Card> ten = List.copyOf(engine.snapshot().hands.get(GameEngine.HUMAN));

        assertEquals(GameEngine.Phase.PLAY, engine.passPushOn());
        assertEquals(1, engine.blindPushes());
        GameEngine.Snapshot snapshot = engine.snapshot();
        assertEquals(ten, snapshot.hands.get(GameEngine.HUMAN));

        LinkedHashSet<Card> seen = new LinkedHashSet<>();
        for (List<Card> hand : snapshot.hands) {
            assertEquals("ten cards after the Schieben", 10, hand.size());
            seen.addAll(hand);
        }
        seen.addAll(snapshot.skat);
        assertEquals("32 distinct cards", 32, seen.size());
        assertEquals(2, snapshot.skat.size());
    }

    /**
     * A seat that never opened the pair cannot have chosen it, so a player that
     * pushes blind is not asked which two cards it would rather have sent.
     */
    @Test public void aBlindSeatIsNeverAskedWhichTwoToPush() {
        Random random = new Random(5);
        NeverLooks provider = new NeverLooks(random);
        GameEngine engine = new GameEngine(random, provider);
        engine.restart();

        assertEquals(0, provider.pushesChosen);
        GameEngine.Snapshot snapshot = engine.snapshot();
        LinkedHashSet<Card> seen = new LinkedHashSet<>();
        for (List<Card> hand : snapshot.hands) {
            assertEquals(10, hand.size());
            seen.addAll(hand);
        }
        seen.addAll(snapshot.skat);
        assertEquals(32, seen.size());
        // Worth saying out loud: with nobody looking, the skat is the dealt one,
        // and a dealt skat may hold a jack. The rule is that a jack may not be
        // pushed, not that the skat cannot contain one.
        assertEquals(2, snapshot.skat.size());
    }

    /** Unasked, the deal is exactly the one this engine always played. */
    @Test public void withoutTheOfferTheCardsArriveInTheHandAsBefore() {
        GameEngine engine = interactiveRamsch(3, false);

        assertFalse(engine.awaitingTakeUp());
        assertTrue(engine.awaitingPush());
        assertEquals(12, engine.snapshot().hands.get(GameEngine.HUMAN).size());
    }

    /**
     * The engine carries the count from the Schieben to the settlement. Checked
     * against the arithmetic rather than a fixed number, because the seed
     * decides who loses and with how much — what must hold is that the value is
     * the card points doubled once per blind leg.
     */
    @Test public void aRamschPushedOnBlindIsSettledAtTheDoubledValue() {
        GameEngine engine = offeredRamsch(3);
        engine.passPushOn();
        assertEquals(1, engine.blindPushes());

        SkatAi.GameResult result = playOut(engine).snapshot().result;

        // One blind leg, plus a rung if the deal happened to end in a jungfrau.
        assertEquals(result.jungfrau ? 2 : 1, result.ramschDoublings);
        assertEquals(result.durchmarsch ? 240
                        : -(result.declarerPoints << result.ramschDoublings),
                result.gameValue);
    }

    // ---------------------------------------------------------------- helpers

    /** Passes everything and never opens what it is handed. */
    private static final class NeverLooks implements SkatAiProvider {
        private final Random random;
        private int pushesChosen;

        NeverLooks(Random random) { this.random = random; }

        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("blind", "Never looks", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public int bid(SkatAi.BidRequest request) { return 0; }

                @Override public boolean takeUpPush(SkatAi.RamschTakeUpContext context) {
                    return false;
                }

                @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) {
                    pushesChosen++;
                    return SkatRules.defaultRamschPush(context.hand);
                }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    List<Card> legal = new ArrayList<>(context.legalCards);
                    return legal.get(random.nextInt(legal.size()));
                }
            };
        }
    }

    /** An interactive Ramsch stopped at the player's own leg, with the offer on. */
    private static GameEngine offeredRamsch(long seed) {
        return interactiveRamsch(seed, true);
    }

    private static GameEngine interactiveRamsch(long seed, boolean offered) {
        Random random = new Random(seed);
        GameEngine engine = new GameEngine(random, new AlwaysPasses(random));
        engine.setBlindPushOffered(offered);
        Auction auction = engine.startInteractiveDeal(Set.of(SkatAi.Seat.HUMAN));
        int guard = 0;
        while (!auction.finished() && guard++ < 200) {
            if (auction.pending() != null) auction.answer(false);
            else auction.step();
        }
        assertTrue("everybody passes, so this is a Ramsch", auction.passedIn());
        engine.settleAuction();
        return engine;
    }


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
        return playOut(deal(seed));
    }

    /** The same, from wherever this engine has got to. */
    private static GameEngine playOut(GameEngine engine) {
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
