package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
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
 * A declarer told which contract it will play buries for that contract.
 *
 * <p>Which sounds like nothing and was worth twenty-six points. The engine asks
 * for the discard before the announcement, because in a real game that is the
 * order -- the discard is part of deciding the contract, so nobody knows it yet.
 * A fixed-contract match has no auction, and removed that reasoning along with
 * it: the declarer went on discarding for the contract {@code price()} liked on
 * its own ten cards, while about to play one the arena had chosen.
 *
 * <p>Measured on 400 boards priced as makeable Nulls: its own discard kept a
 * makeable ten 74% of the time, and told the contract, 100%. At solver
 * contracts, 87% against 100% -- which is the missing tenth {@code
 * arena/README.md} attributes to "two heuristics disagreeing about which two
 * cards to bury", with a cause rather than an attribution.
 *
 * <p>Not a gift of hidden information. A real declarer knows perfectly well what
 * it is about to announce while it is burying.
 */
public class SettledContractDiscardTest {

    private static final SkatAi.ContractRules RULES = new SkatAi.ContractRules(
            EnumSet.of(SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS,
                    SkatAi.ContractType.SPADES, SkatAi.ContractType.CLUBS,
                    SkatAi.ContractType.GRAND, SkatAi.ContractType.NULL),
            false, false, false);

    /** The twelve a declarer holds after the pick-up, from a seeded pack. */
    private static List<Card> twelve(long seed) {
        List<Card> pack = new ArrayList<>(SkatDeck.ordered());
        java.util.Collections.shuffle(pack, new Random(seed));
        return new ArrayList<>(pack.subList(0, 12));
    }

    private static Set<Card> discardFor(List<Card> twelve, Contract settled) {
        SkatAiProvider player = new SearchAiProvider(new GreedyAiProvider(),
                Personality.clubPlayer(), 11L, WorldSource.UNIFORM);
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(SkatAi.RoundPosition.at(0),
                    SkatAi.Seat.HUMAN, new LinkedHashSet<>(twelve.subList(0, 10)), RULES));
            return new HashSet<>(session.discardSkat(new SkatAi.SkatExchangeContext(
                    SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN,
                    new LinkedHashSet<>(twelve), twelve.subList(10, 12), settled)));
        }
    }

    /**
     * Told Null, it buries what a Null wants: the two highest.
     *
     * <p>The sharpest case, because a Null discard is the exact inverse of a
     * trump one -- keep trumps and aces and bury short-suit points, against bury
     * the two cards most likely to be forced to win a trick.
     */
    @Test public void toldNullItBuriesForNull() {
        for (long seed : new long[] {1L, 5L, 9L, 12L}) {
            List<Card> twelve = twelve(seed);
            assertEquals("told Null, it must bury the Null discard",
                    new HashSet<>(Discards.buried(Contract.NULL, twelve)),
                    discardFor(twelve, Contract.NULL));
        }
    }

    /** And told a trump game, the trump discard for that game. */
    @Test public void toldATrumpGameItBuriesForThatGame() {
        for (Contract contract : new Contract[] {Contract.CLUBS, Contract.HEARTS,
                Contract.GRAND}) {
            List<Card> twelve = twelve(3L);
            assertEquals("told " + contract + ", it must bury that game's discard",
                    new HashSet<>(Discards.buried(contract, twelve)),
                    discardFor(twelve, contract));
        }
    }

    /**
     * Being told changes the answer, or the test above proves nothing.
     *
     * <p>A seed where the seat's own opinion is a trump game and the settled
     * contract is Null, so the two discards must differ. Without this, a player
     * that ignored {@code settledContract} entirely could pass everything above
     * whenever its own preference happened to agree.
     */
    @Test public void beingToldActuallyChangesTheDiscard() {
        int differed = 0;
        for (long seed = 1; seed <= 12; seed++) {
            List<Card> twelve = twelve(seed);
            if (!discardFor(twelve, Contract.NULL).equals(discardFor(twelve, Contract.CLUBS))) {
                differed++;
            }
        }
        assertTrue("Null and Clubs must bury different cards on some hand of twelve,"
                + " or settledContract is being ignored", differed > 0);
    }

    /**
     * Not told, it is unchanged: the auction path must not shift.
     *
     * <p>The whole game outside the arena goes through here, and it had better
     * play exactly as it did. Asserted against a second session given the
     * four-argument context, which is the constructor every auction game uses.
     */
    @Test public void withoutASettledContractNothingChanges() {
        List<Card> twelve = twelve(7L);
        SkatAiProvider player = new SearchAiProvider(new GreedyAiProvider(),
                Personality.clubPlayer(), 11L, WorldSource.UNIFORM);
        Set<Card> viaOldConstructor;
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(SkatAi.RoundPosition.at(0),
                    SkatAi.Seat.HUMAN, new LinkedHashSet<>(twelve.subList(0, 10)), RULES));
            viaOldConstructor = new HashSet<>(session.discardSkat(
                    new SkatAi.SkatExchangeContext(SkatAi.RoundPosition.at(0),
                            SkatAi.Seat.HUMAN, new LinkedHashSet<>(twelve),
                            twelve.subList(10, 12))));
        }
        assertEquals(viaOldConstructor, discardFor(twelve, null));
        assertEquals("a discard is always two cards", 2, viaOldConstructor.size());
    }

    /** An auction game is handed no settled contract, and must not invent one. */
    @Test public void theAuctionPathStillPassesNothing() {
        SkatAi.SkatExchangeContext context = new SkatAi.SkatExchangeContext(
                SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN,
                new LinkedHashSet<>(twelve(2L)), twelve(2L).subList(10, 12));
        org.junit.Assert.assertNull("nobody knows the contract before the announcement",
                context.settledContract);
        assertNotEquals(0, context.hand.size());
    }
}
