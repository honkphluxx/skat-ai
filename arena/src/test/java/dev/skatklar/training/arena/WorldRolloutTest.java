package dev.skatklar.training.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.search.WorldSampler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * A world's board is the recorded game with the hidden cards moved, and nothing else.
 *
 * <p>{@link WorldRolloutMain} plays each legal card out in the worlds the
 * player sampled, on a board rebuilt from each world. Two properties hold that
 * up: handed the true deal as its world, the rebuilt board is the recorded
 * board and a rollout on it with the original seeds replays the recorded game
 * card for card; handed any sampled world, it is a deal in which every card
 * already played was held by the seat that played it, the declarer holds what
 * it was dealt, and the game plays out without a rule broken.
 */
public class WorldRolloutTest {

    @Test public void theTrueDealAsAWorldReplaysTheGame() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve("search-4");
        long seed = 14;
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        int checked = 0;
        for (int i = 0; i < 12 && checked < 5; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null) continue;
            RolloutAuditMain.Replay game = RolloutAuditMain.record(board, seed, fixed, player);
            if (game.decisions().isEmpty()) continue;
            RolloutAuditMain.Recorded d = game.decisions().get(game.decisions().size() / 2);
            WorldSampler.World truth = trueWorld(game, d.index());
            Board rebuilt = WorldRolloutMain.worldBoard(game, d.index(), truth);
            assertNotNull("board " + i + ": the true deal is a deal", rebuilt);
            SkatDeck.Deal a = board.deal(), b = rebuilt.deal();
            assertEquals("board " + i + " seat 0", new HashSet<>(a.human), new HashSet<>(b.human));
            assertEquals("board " + i + " seat 1", new HashSet<>(a.opponentOne), new HashSet<>(b.opponentOne));
            assertEquals("board " + i + " seat 2", new HashSet<>(a.opponentTwo), new HashSet<>(b.opponentTwo));
            assertEquals("board " + i + " skat", new HashSet<>(a.skat), new HashSet<>(b.skat));
            RolloutAuditMain.Outcome replayed = RolloutAuditMain.rollout(game, rebuilt, truth.skat(), d.index(),
                    d.played(), player, seed, -1, 0x3017L, true);
            assertNotNull("board " + i + " replayed to an end", replayed);
            assertEquals("board " + i + ": every scripted card is the one the player would choose",
                    0, replayed.scriptMismatches());
            assertEquals("board " + i + ": every card of the game", game.sequence(), replayed.sequence());
            assertEquals("board " + i + " scored alike", game.tournamentPoints(), replayed.tournamentPoints());
            checked++;
        }
        assertTrue("decisions checked: " + checked, checked >= 4);
    }

    @Test public void everySampledWorldIsADealTheHistoryFits() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve("search-4");
        long seed = 15;
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        int worlds = 0;
        for (int i = 0; i < 8; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null) continue;
            RolloutAuditMain.Replay game = RolloutAuditMain.record(board, seed, fixed, player);
            int declarer = fixed.declarer().ordinal();
            Map<Card, Integer> owner = owners(game);
            for (RolloutAuditMain.Recorded d : game.decisions()) {
                assertNotNull("board " + i + ": the worlds were kept", d.sampled());
                assertTrue(d.sampled().size() >= d.worlds());
                for (int w = 0; w < d.worlds(); w++) {
                    WorldSampler.World world = d.sampled().get(w);
                    Board rebuilt = WorldRolloutMain.worldBoard(game, d.index(), world);
                    assertNotNull("board " + i + " decision " + d.index() + " world " + w + " is a deal", rebuilt);
                    List<List<Card>> hands = List.of(rebuilt.deal().human, rebuilt.deal().opponentOne,
                            rebuilt.deal().opponentTwo);
                    List<List<Card>> dealt = List.of(board.deal().human, board.deal().opponentOne,
                            board.deal().opponentTwo);
                    assertEquals("the declarer keeps its ten", new HashSet<>(dealt.get(declarer)),
                            new HashSet<>(hands.get(declarer)));
                    for (Card played : game.sequence().subList(0, d.index())) {
                        int seat = owner.get(played);
                        if (seat == declarer) continue;
                        assertTrue("seat " + seat + " holds the " + played + " it played",
                                hands.get(seat).contains(played));
                    }
                    for (int seat = 0; seat < 3; seat++) {
                        if (seat == declarer) continue;
                        assertTrue("seat " + seat + " holds the world's hand",
                                hands.get(seat).containsAll(world.hands().get(seat)));
                    }
                    worlds++;
                }
                // One world a decision, played out: no rule broken, the exchange as recorded.
                WorldSampler.World world = d.sampled().get(0);
                RolloutAuditMain.Outcome o = RolloutAuditMain.rollout(game,
                        WorldRolloutMain.worldBoard(game, d.index(), world), world.skat(), d.index(), d.played(),
                        player, seed, 0, 0x3017L, false);
                assertNotNull("board " + i + " decision " + d.index() + " plays out in its first world", o);
            }
        }
        assertTrue("worlds checked: " + worlds, worlds >= 40);
    }

    /** Who held each card at the start of play: the dealt hands, the skat taken up by the declarer. */
    private static Map<Card, Integer> owners(RolloutAuditMain.Replay game) {
        SkatDeck.Deal deal = game.board().deal();
        List<List<Card>> dealt = List.of(deal.human, deal.opponentOne, deal.opponentTwo);
        Map<Card, Integer> owner = new HashMap<>();
        for (int seat = 0; seat < 3; seat++) for (Card c : dealt.get(seat)) owner.put(c, seat);
        for (Card c : deal.skat) owner.put(c, game.fixed().declarer().ordinal());
        return owner;
    }

    /** The true deal as the sampler writes a world: what each seat still holds, and the skat. */
    private static WorldSampler.World trueWorld(RolloutAuditMain.Replay game, int index) {
        Map<Card, Integer> owner = owners(game);
        Set<Card> gone = new HashSet<>(game.sequence().subList(0, index));
        gone.addAll(game.skat());
        List<List<Card>> hands = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) hands.add(new ArrayList<>());
        for (Map.Entry<Card, Integer> e : owner.entrySet()) {
            if (!gone.contains(e.getKey())) hands.get(e.getValue()).add(e.getKey());
        }
        return new WorldSampler.World(hands, game.skat());
    }
}
