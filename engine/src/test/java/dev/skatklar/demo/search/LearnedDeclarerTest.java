package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * The learned declarer: the exporter's file loads and scores as Python scored
 * (a tiny random net over a dozen real boards, every option's features rebuilt
 * here from its inputs); the layout is the one train_declarer.py documents; it
 * chooses among the games covering the bid and the ten pairs of each; and the
 * search player announces what it picks.
 */
public class LearnedDeclarerTest {

    static LearnedDeclarer tiny() throws IOException {
        try (InputStream in = LearnedDeclarerTest.class.getResourceAsStream("/learned-declarer/declarer.bin")) {
            assertNotNull("the tiny fixture", in);
            return LearnedDeclarer.load(in);
        }
    }

    static BufferedReader parity() {
        InputStream in = LearnedDeclarerTest.class.getResourceAsStream("/learned-declarer/declarer-parity.txt");
        assertNotNull("the tiny fixture's parity", in);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test public void itScoresEveryOptionAsPythonDid() throws IOException {
        try (BufferedReader r = parity()) {
            assertEquals("every option of the twelve boards", 570, tiny().checkParity(r, 1e-5f));
        }
    }

    @Test public void aScoreThatDiffersIsRefused() throws IOException {
        List<String> lines;
        try (BufferedReader r = parity()) {
            lines = r.lines().collect(Collectors.toList());
        }
        String last = lines.get(lines.size() - 1);
        String[] f = last.split(" ");
        f[f.length - 1] = String.valueOf(Float.parseFloat(f[f.length - 1]) + 0.01f);
        String changed = String.join(" ", f);
        LearnedDeclarer net = tiny();
        assertThrows(IOException.class, () -> net.checkParity(new BufferedReader(new StringReader(changed)), 1e-5f));
        String[] g = last.split(" ");
        g[23] = g[23].equals("0") ? "1" : "0";                  // the heuristic flag
        assertThrows(IOException.class, () -> net.checkParity(
                new BufferedReader(new StringReader(String.join(" ", g))), 1e-5f));
    }

    @Test public void aFileThatIsNotOneIsRefused() {
        assertThrows(IOException.class, () -> LearnedDeclarer.load(new java.io.ByteArrayInputStream(new byte[8])));
        assertThrows(IOException.class, () -> LearnedDeclarer.load(new java.io.ByteArrayInputStream(
                new byte[] {0x53, 0x4B, 0x44, 0x4E, 2, 0, 0, 0, 80, 0, 0, 0, 1, 0, 0, 0})));
    }

    @Test public void theLayoutIsTheJacksThenTheTrumpSuitThenTheSideSuits() {
        for (Contract game : LearnedDeclarer.GAMES) {
            Set<Integer> slots = new HashSet<>();
            for (Card.Suit suit : Card.Suit.values()) {
                for (Card.Rank rank : Card.Rank.values()) slots.add(LearnedDeclarer.slot(game, new Card(suit, rank)));
            }
            assertEquals(game + ": a permutation of the 32 slots", 32, slots.size());
        }
        assertEquals(2, LearnedDeclarer.slot(Contract.HEARTS, new Card(Card.Suit.HEARTS, Card.Rank.JACK)));
        assertEquals("hearts' seven first after the jacks", 4,
                LearnedDeclarer.slot(Contract.HEARTS, new Card(Card.Suit.HEARTS, Card.Rank.SEVEN)));
        assertEquals("hearts' ace", 10, LearnedDeclarer.slot(Contract.HEARTS, new Card(Card.Suit.HEARTS, Card.Rank.ACE)));
        assertEquals("then clubs", 11, LearnedDeclarer.slot(Contract.HEARTS, new Card(Card.Suit.CLUBS, Card.Rank.SEVEN)));
        assertEquals("grand: clubs first", 4, LearnedDeclarer.slot(Contract.GRAND, new Card(Card.Suit.CLUBS, Card.Rank.SEVEN)));
    }

    /** A net that scores an option by its game's one-hot alone: {@code favour} 10, the rest 0. */
    static LearnedDeclarer favouring(Contract favour) {
        float[][] first = new float[1][LearnedDeclarer.FEATURES];
        for (int g = 0; g < LearnedDeclarer.GAMES.length; g++) {
            if (LearnedDeclarer.GAMES[g] == favour) first[0][64 + g] = 10f;
        }
        return new LearnedDeclarer(new float[][][] {first, {{1f}}}, new float[][] {{0f}, {0f}});
    }

    @Test public void itChoosesAmongTheGamesThatCoverTheBidAndTheirTenPairs() {
        List<Card> twelve = new ArrayList<>(PostSkatContractTest.ten());
        twelve.addAll(PostSkatContractTest.skat());
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        List<Contract> games = List.of(LearnedDeclarer.GAMES);
        Map<SkatAi.Seat, Integer> bids = new EnumMap<>(SkatAi.Seat.class);
        LearnedDeclarer.Pick grand = favouring(Contract.GRAND).choose(twelve, 18, SkatAi.Seat.HUMAN, round, games,
                bids, Set.of());
        assertEquals(Contract.GRAND, grand.contract());
        List<Card> pool = Discards.ranked(Contract.GRAND, twelve).subList(7, 12);
        assertTrue("two of the five least wanted", pool.containsAll(grand.buried()) && grand.buried().size() == 2);
        LearnedDeclarer.Pick hearts = favouring(Contract.HEARTS).choose(twelve, 18, SkatAi.Seat.HUMAN, round, games,
                bids, Set.of());
        assertEquals("hearts covers 18", Contract.HEARTS, hearts.contract());
        assertTrue(SkatRules.guaranteedValue(Contract.HEARTS, twelve) < 49);
        LearnedDeclarer.Pick high = favouring(Contract.HEARTS).choose(twelve, 49, SkatAi.Seat.HUMAN, round, games,
                bids, Set.of());
        assertEquals("above hearts' value only Grand covers", Contract.GRAND, high.contract());
        assertEquals("only the games allowed", Contract.CLUBS, favouring(Contract.GRAND).choose(twelve, 18,
                SkatAi.Seat.HUMAN, round, List.of(Contract.CLUBS), bids, Set.of()).contract());
        assertNull("nothing covers 97", favouring(Contract.GRAND).choose(twelve, 97, SkatAi.Seat.HUMAN, round, games,
                bids, Set.of()));
    }

    /** Grand scores 5 only when middlehand passed (feature 75 + 2): an AND of two features, through a ReLU. */
    static LearnedDeclarer grandIfMiddlehandPassed() {
        float[][] first = new float[1][LearnedDeclarer.FEATURES];
        first[0][64 + 4] = 10f;
        first[0][74 + 2 * 1 + 1] = 10f;
        return new LearnedDeclarer(new float[][][] {first, {{1f}}}, new float[][] {{-15f}, {0f}});
    }

    @Test public void thePlacesAndTheAuctionReachTheNet() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        assertEquals(0, LearnedDeclarer.place(round, round.forehand));
        assertEquals(1, LearnedDeclarer.place(round, round.middlehand));
        assertEquals(2, LearnedDeclarer.place(round, round.rearhand));
        List<Card> twelve = new ArrayList<>(PostSkatContractTest.ten());
        twelve.addAll(PostSkatContractTest.skat());
        List<Contract> games = List.of(LearnedDeclarer.GAMES);
        LearnedDeclarer net = grandIfMiddlehandPassed();
        Map<SkatAi.Seat, Integer> none = new EnumMap<>(SkatAi.Seat.class);
        assertEquals("middlehand passed", Contract.GRAND, net.choose(twelve, 18, round.forehand, round, games, none,
                Set.of(round.middlehand)).contract());
        assertEquals("rearhand passed: all equal, the first game", Contract.DIAMONDS, net.choose(twelve, 18,
                round.forehand, round, games, none, Set.of(round.rearhand)).contract());
        // And through the search player: it hears the pass and hands it on.
        SearchAiProvider player = PostSkatContractTest.base().withLearnedDeclarer(net);
        List<Card> all = new ArrayList<>(twelve);
        try (dev.skatklar.demo.ai.SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(round, round.forehand,
                    new java.util.LinkedHashSet<>(PostSkatContractTest.ten()), PostSkatContractTest.rules()));
            session.bidObserved(new SkatAi.BidEvent(round.middlehand, 0, true));
            session.pickUpSkat(new SkatAi.SkatChoiceContext(round, round.forehand, 18));
            java.util.Set<Card> buried = session.discardSkat(new SkatAi.SkatExchangeContext(round, round.forehand,
                    new java.util.LinkedHashSet<>(all), PostSkatContractTest.skat(), null));
            List<Card> kept = new ArrayList<>(all);
            kept.removeAll(buried);
            assertEquals(SkatAi.ContractType.GRAND, session.announceContract(new SkatAi.ContractContext(round,
                    round.forehand, new java.util.LinkedHashSet<>(kept), 18, true, PostSkatContractTest.rules())).type);
        }
    }

    @Test public void theSearchPlayerAnnouncesWhatItPicksAndLeavesASettledContractAlone() {
        SearchAiProvider learned = PostSkatContractTest.base().withLearnedDeclarer(favouring(Contract.HEARTS));
        PostSkatContractTest.Exchange on = PostSkatContractTest.exchange(learned, 18, true, null);
        assertEquals(SkatAi.ContractType.HEARTS, on.announced().type);
        PostSkatContractTest.Exchange settledOff = PostSkatContractTest.exchange(PostSkatContractTest.base(), 18, true,
                Contract.CLUBS);
        PostSkatContractTest.Exchange settledOn = PostSkatContractTest.exchange(learned, 18, true, Contract.CLUBS);
        assertEquals(settledOff.discarded(), settledOn.discarded());
        PostSkatContractTest.Exchange untold = PostSkatContractTest.exchange(learned, 18, false, null);
        assertEquals("no bid told, the intention as before",
                PostSkatContractTest.exchange(PostSkatContractTest.base(), 18, false, null).announced().type,
                untold.announced().type);
        LearnedDeclarer net = favouring(Contract.GRAND);
        SearchAiProvider chained = PostSkatContractTest.base().withLearnedDeclarer(net).withPostSkatContract(4)
                .withDiscardSearch(8, 5, 0L).withAdaptiveBidding().withLadder().withNullWorlds(24);
        assertTrue("the net survives the other builders", chained.learnedDeclarer() == net);
        assertEquals("off unless asked for", null, PostSkatContractTest.base().learnedDeclarer());
    }
}
