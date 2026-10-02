package dev.skatklar.training.players;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * A role split asks the declaring player as declarer and the defending player
 * as defender, at every card, and both hear every card.
 *
 * <p>Two scripted halves sit in every seat of fixed-contract games. Each
 * records, at every card it is asked for, whether its seat was declaring;
 * the declaring half must only ever be asked as declarer and the defending
 * half only as defender, and both must be asked. Each half must have been told
 * of all thirty cards, and the declarer's skat exchange must have gone to the
 * declaring half and never to the defending one.
 */
public class RoleSplitProviderTest {

    /** Plays its first legal card; counts what it was told and asked, and in which role. */
    static final class Scripted implements SkatAiProvider {
        int cardsHeard;
        int exchanges;
        int askedAsDeclarer;
        int askedAsDefender;

        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("scripted", "scripted", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    if (context.mySeat == context.game.declarer) askedAsDeclarer++;
                    else askedAsDefender++;
                    return context.legalCards.iterator().next();
                }
                @Override public void cardPlayed(SkatAi.CardPlayedEvent event) { cardsHeard++; }
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    exchanges++;
                    List<Card> all = new ArrayList<>(context.hand);
                    all.addAll(context.skat);
                    return Set.of(all.get(0), all.get(1));
                }
            };
        }
    }

    @Test public void eachRoleIsPlayedByItsOwnHalf() {
        long seed = 21;
        ContractSource contracts = new AuctionContractSource(PlayerRegistry.withDefaults().resolve("greedy"), seed);
        int games = 0;
        int asDeclarer = 0;
        int asDefender = 0;
        for (int i = 0; games < 6 && i < 40; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null) continue;
            Map<SkatAi.Seat, Scripted[]> halves = new EnumMap<>(SkatAi.Seat.class);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                Scripted declaring = new Scripted();
                Scripted defending = new Scripted();
                halves.put(seat, new Scripted[] {declaring, defending});
                seating.put(seat, new RoleSplitProvider("roles:test", declaring, defending));
            }
            GameRunner.playFixed(board, seating, fixed, i);
            games++;
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                Scripted declaring = halves.get(seat)[0];
                Scripted defending = halves.get(seat)[1];
                assertEquals("the declaring half heard every card", 30, declaring.cardsHeard);
                assertEquals("the defending half heard every card", 30, defending.cardsHeard);
                assertEquals("the declaring half is asked only as declarer", 0, declaring.askedAsDefender);
                assertEquals("the defending half is asked only as defender", 0, defending.askedAsDeclarer);
                assertEquals("the defending half never exchanges", 0, defending.exchanges);
                if (seat != fixed.declarer()) {
                    assertEquals("a defender's declaring half is never asked", 0, declaring.askedAsDeclarer);
                    assertEquals("a defender exchanges nothing", 0, declaring.exchanges);
                } else {
                    assertEquals("the declarer's defending half is never asked", 0, defending.askedAsDefender);
                }
                asDeclarer += declaring.askedAsDeclarer;
                asDefender += defending.askedAsDefender;
            }
            Scripted declarersHalf = halves.get(fixed.declarer())[0];
            assertTrue("the declarer played its cards with the declaring half", declarersHalf.askedAsDeclarer >= 9);
        }
        assertTrue("games: " + games, games >= 5);
        assertTrue("declarer decisions: " + asDeclarer, asDeclarer > 40);
        assertTrue("defender decisions: " + asDefender, asDefender > 80);
    }

    @Test public void theRegistrySpellsItRoles() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant split = registry.resolve("roles:greedy/random");
        assertEquals("roles:greedy/random", split.id());
        assertTrue(split.newProvider(1) instanceof RoleSplitProvider);
        for (String bad : new String[] {"roles:greedy", "roles:/random", "roles:greedy/", "roles:greedy/nobody"}) {
            try {
                registry.resolve(bad);
                fail(bad + " should not resolve");
            } catch (IllegalArgumentException expected) {
                // as it should
            }
        }
    }
}
