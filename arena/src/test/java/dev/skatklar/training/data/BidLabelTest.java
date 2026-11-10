package dev.skatklar.training.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.BidValues;
import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.LearnedDeclarer;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.players.RolloutRescueTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The bid labels: every seat of a board in place order with its own ten; the
 * ladder from 18 up to the most a game is worth on the twelve, each level's
 * choice a game covering that bid; each distinct choice played out once; the
 * shipped bidder's ceiling the highest bid it holds, and its opening read in
 * its place; a board the same bytes run twice.
 */
public class BidLabelTest {

    static final long SEED = 9201;
    static final Contestant SEARCH = PlayerRegistry.withDefaults().resolve("search-4");

    /** A net that likes Grand best, then Clubs, Spades, Hearts, Diamonds: the game one-hots weighted 5..1. */
    static LearnedDeclarer ordered() {
        float[][] first = new float[1][80];
        for (int g = 0; g < 5; g++) first[0][64 + g] = g + 1;
        return new LearnedDeclarer(new float[][][] {first, {{1f}}}, new float[][] {{0f}, {0f}});
    }

    @Test public void everySeatGetsTheLadderAndEachLevelsChoiceCoversItsBid() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(71);
        LearnedDeclarer declarer = ordered();
        int checked = 0;
        for (long i = 0; i < 4; i++) {
            Board board = Board.of(SEED, i);
            BidLabelMain.Result r = BidLabelMain.board(net, declarer, SEARCH, board, 2, SEED,
                    new DeclarerChoiceMain.Stats());
            assertEquals(3, r.seats().size());
            for (int place = 0; place < 3; place++) {
                BidLabelMain.SeatLabels s = r.seats().get(place);
                assertEquals("place order", place, s.place());
                assertEquals("its own ten", DeclarerChoiceMain.handOf(board, BidLabelMain.seatAt(board.round(), place)),
                        s.ten());
                List<Card> twelve = new ArrayList<>(s.ten());
                twelve.addAll(board.deal().skat);
                int previous = 0;
                Set<Integer> used = new HashSet<>();
                for (int[] level : s.levels()) {
                    assertTrue("the ladder in order", level[0] > previous && BidValues.LADDER.contains(level[0]));
                    if (previous == 0) assertEquals("from 18", 18, level[0]);
                    previous = level[0];
                    DeclarerChoiceMain.Scored pick = s.picks().get(level[1]);
                    assertTrue("the choice covers the bid",
                            SkatRules.guaranteedValue(pick.option().contract(), twelve) >= level[0]);
                    used.add(level[1]);
                }
                int most = 0;
                for (Contract g : DeclarerChoiceMain.TRUMP_GAMES) {
                    if (net.covers(g)) most = Math.max(most, SkatRules.guaranteedValue(g, twelve));
                }
                assertEquals("up to the most a game is worth", most, previous);
                assertEquals("every pick is used", s.picks().size(), used.size());
                Set<String> distinct = new HashSet<>();
                for (DeclarerChoiceMain.Scored p : s.picks()) distinct.add(p.option().contract() + " " + p.option().discard());
                assertEquals("no choice played out twice", s.picks().size(), distinct.size());
                assertEquals("Grand while it covers", Contract.GRAND, s.picks().get(s.levels().get(0)[1]).option().contract());
                checked++;
            }
        }
        assertEquals(12, checked);
    }

    @Test public void theShippedBiddersCeilingIsTheHighestBidItHolds() throws Exception {
        for (long i = 0; i < 6; i++) {
            Board board = Board.of(SEED, i);
            for (int place = 0; place < 3; place++) {
                SkatAi.Seat seat = BidLabelMain.seatAt(board.round(), place);
                List<Card> ten = DeclarerChoiceMain.handOf(board, seat);
                int[] got = BidLabelMain.shippedBidder(SEARCH.newProvider(3), board, seat, place, ten);
                int ceiling = got[0];
                try (SkatAiSession s = SEARCH.newProvider(3).createSession()) {
                    s.prepareDeal(new SkatAi.DealContext(board.round(), seat, new LinkedHashSet<>(ten),
                            DeclarerChoiceMain.RULES));
                    if (ceiling > 0) {
                        assertEquals("it holds its ceiling", ceiling, s.bid(new SkatAi.BidRequest(board.round(), seat,
                                SkatAi.BidRole.HOLD, BidValues.previous(ceiling) == 0 ? 18 : BidValues.previous(ceiling),
                                ceiling)));
                    }
                    int above = BidValues.next(Math.max(ceiling, 17));
                    if (ceiling > 0 && above != 0) {
                        assertEquals("and not the rung above", 0, s.bid(new SkatAi.BidRequest(board.round(), seat,
                                SkatAi.BidRole.HOLD, ceiling, above)));
                    }
                }
            }
        }
    }

    @Test public void aBoardIsTheSameRunTwice() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(72);
        Board board = Board.of(SEED, 7);
        String one = BidLabelMain.json(BidLabelMain.board(net, ordered(), SEARCH, board, 2, SEED,
                new DeclarerChoiceMain.Stats()));
        String two = BidLabelMain.json(BidLabelMain.board(net, ordered(), SEARCH, board, 2, SEED,
                new DeclarerChoiceMain.Stats()));
        assertEquals(one, two);
        assertTrue(one.contains("\"place\": \"forehand\"") && one.contains("\"place\": \"rearhand\"")
                && one.contains("\"ceiling\": ") && one.contains("\"open18\": "));
    }

    @Test public void theWinnersAuctionIsItsBidAndTwoPasses() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(1);
        List<SkatAi.BidEvent> events = BidLabelMain.wonAt(round, round.middlehand, 27);
        assertEquals(3, events.size());
        assertEquals(round.middlehand, events.get(2).seat);
        assertEquals(27, events.get(2).value);
        assertTrue(events.get(0).passed && events.get(1).passed && !events.get(2).passed);
        assertTrue(new LinkedHashSet<>(List.of(events.get(0).seat, events.get(1).seat))
                .equals(new LinkedHashSet<>(List.of(round.forehand, round.rearhand))));
    }
}
