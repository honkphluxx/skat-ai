package dev.skatklar.training.players;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The rescuer plays the declarer's lost positions and nothing else.
 *
 * <p>A 4-world search in every seat, a scripted rescuer beside it. At every
 * declarer decision the provider reports the search's own votes and whether
 * it handed over; the test works the rule out from those votes itself -- every
 * legal card at zero -- and holds the two equal. The rescuer must be asked
 * exactly as often as it was handed a decision, only as declarer, and must
 * have heard every card; and in the games played some decisions must be
 * handed over and some not, or the comparison says nothing.
 */
public class FlatHandoverProviderTest {

    static final class Rescuer implements SkatAiProvider {
        int asked;
        int askedAsDefender;
        int cardsHeard;

        @Override public SkatAi.AiDescriptor descriptor() { return new SkatAi.AiDescriptor("rescuer", "rescuer", false); }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    asked++;
                    if (context.mySeat != context.game.declarer) askedAsDefender++;
                    return context.legalCards.iterator().next();
                }
                @Override public void cardPlayed(SkatAi.CardPlayedEvent event) { cardsHeard++; }
            };
        }
    }

    @Test public void onlyTheLostDeclarerPositionsAreHandedOver() {
        long seed = 9;
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        List<boolean[]> seen = new ArrayList<>(); // {handed, flat by the test's own reading}
        int handed = 0;
        int games = 0;
        for (int i = 0; games < 12 && i < 60; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            Map<SkatAi.Seat, Rescuer> rescuers = new EnumMap<>(SkatAi.Seat.class);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                Rescuer rescuer = new Rescuer();
                rescuers.put(seat, rescuer);
                SkatAiProvider search = new SearchAiProvider(new GreedyAiProvider(), 4, seed * 31 + i * 3 + seat.ordinal());
                seating.put(seat, new FlatHandoverProvider("flat:test", search, rescuer, (votes, hand) -> {
                    boolean flat = votes != null && !votes.isEmpty()
                            && votes.values().stream().allMatch(v -> v == 0);
                    seen.add(new boolean[] {hand, flat});
                }));
            }
            int before = seen.size();
            GameRunner.playFixed(board, seating, fixed, i);
            games++;
            int handedHere = 0;
            for (boolean[] s : seen.subList(before, seen.size())) {
                assertEquals("handed over exactly when every legal card is at zero", s[1], s[0]);
                if (s[0]) handedHere++;
            }
            handed += handedHere;
            int asked = 0;
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                Rescuer r = rescuers.get(seat);
                assertEquals("the rescuer is never asked to defend", 0, r.askedAsDefender);
                assertEquals("the rescuer heard every card", 30, r.cardsHeard);
                if (seat != fixed.declarer()) assertEquals("a defender's rescuer is never asked", 0, r.asked);
                asked += r.asked;
            }
            assertEquals("the rescuer is asked exactly for the decisions handed to it", handedHere, asked);
        }
        assertTrue("games: " + games, games >= 10);
        assertTrue("decisions handed over: " + handed, handed > 0);
        assertTrue("decisions kept: " + (seen.size() - handed), seen.size() - handed > 20);
    }

    @Test public void aFlatPositionNeedsEveryLegalCardAtZero() {
        Card a = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        Card b = new Card(Card.Suit.CLUBS, Card.Rank.TEN);
        assertTrue(FlatHandoverProvider.flat(Map.of(a, 0, b, 0), java.util.Set.of(a, b)));
        assertTrue(!FlatHandoverProvider.flat(Map.of(a, 0, b, 1), java.util.Set.of(a, b)));
        assertTrue("no vote at all is not a flat position", !FlatHandoverProvider.flat(Map.of(), java.util.Set.of(a, b)));
        assertTrue(!FlatHandoverProvider.flat(null, java.util.Set.of(a, b)));
    }

    @Test public void theRegistrySpellsItFlat() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        assertTrue(registry.resolve("flat:search-4/random").newProvider(1) instanceof FlatHandoverProvider);
        try {
            registry.resolve("flat:greedy/random").newProvider(1);
            fail("a non-search first player should be refused");
        } catch (IllegalArgumentException expected) {
            // as it should
        }
        for (String bad : new String[] {"flat:search-4", "flat:/random", "flat:search-4/"}) {
            try {
                registry.resolve(bad);
                fail(bad + " should not resolve");
            } catch (IllegalArgumentException expected) {
                // as it should
            }
        }
    }
}
