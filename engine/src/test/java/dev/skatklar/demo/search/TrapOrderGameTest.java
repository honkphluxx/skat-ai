package dev.skatklar.demo.search;

import static org.junit.Assert.assertTrue;

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
 * The trap orders reach the table: played out, the same declarer on the same
 * deals chooses differently with the trap on than off, and differently when
 * it counts both defenders than when it counts one. {@code TrapWidthTest}
 * checks the width itself; this checks the player asks for the width it was
 * configured with, which no test of the helper can.
 */
public class TrapOrderGameTest {

    /** Four worlds: enough for a flat zero to happen, and a deal in seconds. */
    private static final Personality QUICK = new Personality(4, 1.0, 0.0, 0.5);

    /** Every card the declarer played in one deal. */
    private static List<Card> declarerCards(SearchAiProvider.TrapOrder order, int deal) {
        List<Card> played = new ArrayList<>();
        {
            SkatAi.Seat declarer = SkatAi.Seat.values()[deal % 3];
            Map<SkatAi.Seat, SkatAiProvider> seats = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                if (seat != declarer) { seats.put(seat, new GreedyAiProvider()); continue; }
                SearchAiProvider player = new SearchAiProvider(new GreedyAiProvider(), QUICK,
                        1000L + deal, WorldSource.UNIFORM).withMarginTiebreak(15).withLadder();
                if (order != null) player = player.withTrap(order);
                seats.put(seat, player.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                    @Override public void decided(SearchAiProvider.CardPlayReport report) {}
                    @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                                Map<Card, Integer> cushion, int worlds,
                                                boolean cushionAsked, Card chosen) {
                        played.add(chosen);
                    }
                }));
            }
            GameEngine engine = GameEngine.headless(new Random(deal), SeatedAiProviders.of(seats));
            engine.restartWithContract(SkatDeck.deal(new Random(7_000L + deal)), SkatAi.RoundPosition.at(0),
                    declarer, Contract.values()[deal % 5], 18, Set.of());
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot now = engine.snapshot();
                if (now.gameComplete()) break;
                if (now.trickComplete()) engine.finishCompletedTrick(); else engine.playAiCard();
            }
            engine.close();
        }
        return played;
    }

    @Test public void theOrderAndTheDepthBothReachTheCardPlayed() {
        assertTrue("replayable: the same seed gives the same cards",
                declarerCards(null, 2).equals(declarerCards(null, 2)));
        // Deal by deal until each comparison has differed once; a player that
        // ignored the order, or the depth, would run out of deals instead.
        boolean trapMatters = false, depthMatters = false;
        for (int deal = 0; deal < 40 && !(trapMatters && depthMatters); deal++) {
            List<Card> one = declarerCards(SearchAiProvider.TrapOrder.TRAP_FIRST, deal);
            if (!trapMatters) trapMatters = !declarerCards(null, deal).equals(one);
            if (!depthMatters) {
                depthMatters = !one.equals(declarerCards(SearchAiProvider.TrapOrder.TRAP_FIRST_BOTH, deal));
            }
        }
        assertTrue("the one-defender trap changes some card", trapMatters);
        assertTrue("counting both defenders changes some card the one-defender trap chose", depthMatters);
    }
}
