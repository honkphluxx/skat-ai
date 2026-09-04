package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.solve.NullSolver;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Null through the whole player, not just the solver. The search, the sampler and
 * the vote are shared with the trump games; only the objective is different, and
 * this is where a wrong objective would show as the declarer cheerfully winning
 * tricks it was contracted to avoid.
 */
public class NullPlayTest {

    /**
     * With the true deal in every sampled world, a Null the declarer can survive
     * must actually be survived. The solver says what is possible before a card
     * is played, and the game then has to deliver it.
     *
     * <p>The belief is made perfect on purpose. An honest player guessing at the
     * hidden cards will sometimes fail a survivable Null, and that is not a
     * defect -- it is the price of not knowing, and it is what the belief sweep
     * measures. What this pins is the objective: with nothing hidden, the search
     * must play Null as Null and not as a points game.
     */
    @Test public void aSurvivableNullIsActuallySurvived() {
        // A random hand almost never makes Null against perfect defence -- real
        // Null hands are chosen, not dealt -- so the deals are scanned rather
        // than assumed, and only the ones that can be survived are asserted on.
        int survivable = 0;
        for (int deal = 0; deal < 400 && survivable < 3; deal++) {
            SkatDeck.Deal cards = SkatDeck.deal(new Random(deal));
            // Filter on the dealt cards before building anything: solving a Null
            // costs a millisecond, while starting a game runs the declarer's
            // whole hand evaluation. The exchange can only improve the hand, so
            // a deal that survives as dealt is a candidate worth playing.
            if (!NullSolver.declarerSurvives(SkatAi.Seat.HUMAN,
                    List.of(new ArrayList<>(cards.human), new ArrayList<>(cards.opponentOne),
                            new ArrayList<>(cards.opponentTwo)),
                    SkatAi.RoundPosition.at(deal).forehand)) {
                continue;
            }

            GameEngine[] table = new GameEngine[1];
            GameEngine engine = seatSearchPlayers(table);
            table[0] = engine;
            engine.restartWithContract(cards, SkatAi.RoundPosition.at(deal),
                    SkatAi.Seat.HUMAN, Contract.NULL, 0, Set.of());

            GameEngine.Snapshot start = engine.snapshot();
            boolean canSurvive = NullSolver.declarerSurvives(SkatAi.Seat.HUMAN, start.hands,
                    SkatAi.Seat.values()[start.leader]);
            if (!canSurvive) {
                // Not worth playing: the deal is lost whatever anyone does, and
                // playing it out costs more than solving the other 199.
                engine.close();
                continue;
            }
            survivable++;
            playOut(engine);
            SkatAi.GameResult result = engine.snapshot().result;
            engine.close();
            assertEquals("deal " + deal + ": the declarer could take no trick and took "
                            + result.tricksWon.get(SkatAi.Seat.HUMAN),
                    0, (int) result.tricksWon.getOrDefault(SkatAi.Seat.HUMAN, 0));
            assertTrue("a Null with no trick taken is a won game", result.declarerWon);
        }
        assertTrue("the sample must contain survivable deals, was " + survivable,
                survivable > 0);
    }

    /** A Null hand full of low cards must evaluate far above one full of aces. */
    @Test public void theEvaluatorTellsALowHandFromAHighOne() {
        HandEvaluator evaluator = new HandEvaluator(8, new Random(3));
        List<Card> low = tenCards(Card.Rank.SEVEN, Card.Rank.EIGHT, Card.Rank.NINE);
        List<Card> high = tenCards(Card.Rank.ACE, Card.Rank.KING, Card.Rank.QUEEN);
        double lowChance = evaluator.makeChance(Contract.NULL, low, SkatAi.Seat.HUMAN,
                SkatAi.Seat.HUMAN);
        double highChance = evaluator.makeChance(Contract.NULL, high, SkatAi.Seat.HUMAN,
                SkatAi.Seat.HUMAN);
        assertTrue("sevens and eights should nearly always survive, was " + lowChance,
                lowChance > 0.5);
        assertTrue("a hand of aces and kings should not, was " + highChance,
                highChance < lowChance);
    }

    /** The discard has to invert for Null: the highest cards go, not the lowest. */
    @Test public void theNullDiscardBuriesTheHighestCards() {
        List<Card> twelve = new ArrayList<>();
        for (Card.Rank rank : new Card.Rank[] {Card.Rank.SEVEN, Card.Rank.EIGHT,
                Card.Rank.NINE, Card.Rank.TEN, Card.Rank.JACK, Card.Rank.QUEEN}) {
            twelve.add(new Card(Card.Suit.SPADES, rank));
            twelve.add(new Card(Card.Suit.HEARTS, rank));
        }
        twelve.set(0, new Card(Card.Suit.SPADES, Card.Rank.ACE));
        twelve.set(1, new Card(Card.Suit.HEARTS, Card.Rank.KING));

        List<Card> buried = Discards.buried(Contract.NULL, twelve);
        assertTrue("the ace must go, buried " + buried,
                buried.contains(new Card(Card.Suit.SPADES, Card.Rank.ACE)));
        assertTrue("the king must go, buried " + buried,
                buried.contains(new Card(Card.Suit.HEARTS, Card.Rank.KING)));
    }

    /** Ten cards drawn from the given ranks, spread across the suits. */
    private static List<Card> tenCards(Card.Rank... ranks) {
        List<Card> cards = new ArrayList<>();
        for (Card.Rank rank : ranks) {
            for (Card.Suit suit : Card.Suit.values()) {
                if (cards.size() < 10) cards.add(new Card(suit, rank));
            }
        }
        return cards;
    }

    private static GameEngine seatSearchPlayers(GameEngine[] table) {
        WorldSource truth = (evidence, count, random) -> {
            GameEngine.Snapshot snapshot = table[0].snapshot();
            List<List<Card>> hands = new ArrayList<>(3);
            for (List<Card> hand : snapshot.hands) hands.add(new ArrayList<>(hand));
            return List.of(new WorldSampler.World(hands, new ArrayList<>(snapshot.skat)));
        };
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            seating.put(seat, new SearchAiProvider(new LegalRandomAiProvider(new Random(1)),
                    Personality.REFERENCE, 2L, truth));
        }
        return GameEngine.headless(new Random(1), SeatedAiProviders.of(seating));
    }

    /**
     * The honest player has to be legal at Null even where it cannot be perfect.
     * Guessing wrong loses the game; naming a card that is not in the hand is a
     * different kind of wrong, and the engine counts it.
     */
    @Test public void theHonestPlayerCommitsNoViolationsAtNull() {
        for (int deal = 0; deal < 3; deal++) {
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, new SearchAiProvider(new LegalRandomAiProvider(new Random(2)),
                        new Personality(4, 1.0, 0.0, 0.5), 3L));
            }
            GameEngine engine = GameEngine.headless(new Random(deal),
                    SeatedAiProviders.of(seating));
            engine.restartWithContract(SkatDeck.deal(new Random(deal)),
                    SkatAi.RoundPosition.at(deal), SkatAi.Seat.HUMAN, Contract.NULL, 0, Set.of());
            playOut(engine);
            assertEquals("a Null game must not need the engine to substitute cards",
                    0, engine.ruleViolations().size());
            engine.close();
        }
    }

    private static void playOut(GameEngine engine) {
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) return;
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else engine.playAiCard();
        }
        throw new IllegalStateException("game did not finish");
    }
}
