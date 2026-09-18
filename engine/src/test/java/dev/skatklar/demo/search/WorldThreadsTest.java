package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Spreading a decision's worlds over threads does not change the decision.
 *
 * <p>This is the property the whole arena rests on, so it is asserted rather
 * than argued: a match has to replay from its seed, and a player whose cards
 * depend on how many cores the machine had is not a player anything can
 * measure. It holds for a structural reason — {@code chooseCard} samples every
 * world before it searches any of them, so the session's random stream is
 * already spent by the time the first solve starts, and a tally of counts per
 * card sums the same in any order — but "holds for a reason" and "holds" are
 * different claims and only one of them survives somebody editing the file.
 *
 * <p>Deliberately run at Null as well as at a trump game. The two contracts go
 * down different paths inside {@code castVotes}, and Null is the one the thread
 * count exists for.
 */
public class WorldThreadsTest {

    private static SeatedAiProviders seating(int threads, long seed) {
        return SeatedAiProviders.of(providersFor(threads, seed));
    }

    private static Map<SkatAi.Seat, SkatAiProvider> providersFor(int threads, long seed) {
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            seating.put(seat, new SearchAiProvider(new GreedyAiProvider(), Personality.clubPlayer(),
                    seed * 31L + seat.ordinal(), WorldSource.UNIFORM)
                    .withMarginTiebreak(15)
                    .withRuleTiebreak()
                    .withWorldThreads(threads));
        }
        return seating;
    }

    /** Every card of one deal, played by seats that all use {@code threads}. */
    private static List<Card> playOut(int threads, Contract contract, long seed) {
        GameEngine engine = GameEngine.headless(new Random(7), seating(threads, seed));
        engine.restartWithContract(SkatDeck.deal(new Random(7)),
                SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN, contract, 0, Set.of());
        List<Card> played = new ArrayList<>();
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) break;
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else played.add(engine.playAiCard());
        }
        assertNotNull(engine.snapshot().result);
        return played;
    }

    @Test public void aTrumpGameIsTheSameOnFourThreadsAsOnOne() {
        assertEquals(playOut(1, Contract.CLUBS, 5L), playOut(4, Contract.CLUBS, 5L));
    }

    @Test public void grandIsTheSameOnFourThreadsAsOnOne() {
        assertEquals(playOut(1, Contract.GRAND, 11L), playOut(4, Contract.GRAND, 11L));
    }

    /**
     * Null, which is what the thread count is for: its worlds are the expensive
     * ones and there are meant to be many more of them.
     */
    @Test public void nullIsTheSameOnFourThreadsAsOnOne() {
        assertEquals(playOut(1, Contract.NULL, 3L), playOut(4, Contract.NULL, 3L));
    }

    /**
     * More threads than worlds is a legal thing to ask for.
     *
     * <p>The chunking divides the worlds among the threads rather than handing
     * one to each, so a request for more threads than there are worlds has to
     * degrade to fewer chunks instead of producing empty ones. A thread count
     * far above any machine's core count is the honest way to reach that edge.
     */
    @Test public void moreThreadsThanWorldsStillPlaysTheSameGame() {
        assertEquals(playOut(1, Contract.CLUBS, 5L), playOut(512, Contract.CLUBS, 5L));
    }

    /** Nonsense counts mean "off" rather than an exception three tricks in. */
    @Test public void aThreadCountBelowOneMeansOne() {
        assertEquals(playOut(1, Contract.CLUBS, 5L), playOut(0, Contract.CLUBS, 5L));
        assertEquals(playOut(1, Contract.CLUBS, 5L), playOut(-4, Contract.CLUBS, 5L));
    }
}
