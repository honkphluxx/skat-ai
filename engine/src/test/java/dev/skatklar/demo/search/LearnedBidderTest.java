package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.BidValues;
import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * The learned bidder: the exporter's file loads and predicts as Python did (a
 * tiny random ensemble over a dozen real boards); the features are the layout
 * train_bidder.py documents; the ceiling stops at the first rung it expects to
 * lose; and the search player bids the higher of its own price and the net's,
 * and chooses its game after the skat when the net took the bid past a Null
 * intention.
 */
public class LearnedBidderTest {

    private static final int[] LADDER = BidValues.LADDER.stream().mapToInt(Integer::intValue).toArray();

    static LearnedBidder tiny() throws IOException {
        try (InputStream in = LearnedBidderTest.class.getResourceAsStream("/learned-bidder/bidder.bin")) {
            assertNotNull("the tiny fixture", in);
            return LearnedBidder.load(in);
        }
    }

    static BufferedReader parity() {
        InputStream in = LearnedBidderTest.class.getResourceAsStream("/learned-bidder/bidder-parity.txt");
        assertNotNull("the tiny fixture's parity", in);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** A net that predicts {@code value} at every rung up to {@code top} and -100 above, whatever the hand. */
    static LearnedBidder constant(int top, float value) {
        float[][] w = new float[LADDER.length][LearnedBidder.FEATURES];
        float[] b = new float[LADDER.length];
        for (int r = 0; r < LADDER.length; r++) b[r] = LADDER[r] <= top ? value : -100f;
        return new LearnedBidder(LADDER, 1f, new float[][][][] {{w}}, new float[][][] {{b}});
    }

    @Test public void itPredictsEverySeatAsPythonDid() throws IOException {
        try (BufferedReader r = parity()) {
            assertEquals("every seat of the twelve boards", 36, tiny().checkParity(r, 1e-5f));
        }
        assertEquals("the exporter's ladder is the engine's", BidValues.LADDER.size(), tiny().ladder().length);
        for (int i = 0; i < LADDER.length; i++) assertEquals(LADDER[i], tiny().ladder()[i]);
    }

    @Test public void aValueThatDiffersIsRefused() throws IOException {
        List<String> lines;
        try (BufferedReader r = parity()) {
            lines = r.lines().filter(l -> !l.startsWith("#")).collect(Collectors.toList());
        }
        String[] f = lines.get(0).split(" ");
        f[13 + 5] = Float.toString(Float.parseFloat(f[13 + 5]) + 0.5f);
        String changed = String.join(" ", f);
        assertThrows(IOException.class, () -> tiny().checkParity(new BufferedReader(new StringReader(changed)), 1e-5f));
        String[] g = lines.get(0).split(" ");
        g[11] = Integer.toString(Integer.parseInt(g[11]) + 30);
        String moved = String.join(" ", g);
        assertThrows("the shipped ceiling is a feature", IOException.class,
                () -> tiny().checkParity(new BufferedReader(new StringReader(moved)), 1e-5f));
    }

    @Test public void theFeaturesAreTheDocumentedLayout() {
        List<Card> ten = List.of(new Card(Card.Suit.CLUBS, Card.Rank.SEVEN), new Card(Card.Suit.CLUBS, Card.Rank.JACK),
                new Card(Card.Suit.SPADES, Card.Rank.ACE), new Card(Card.Suit.HEARTS, Card.Rank.TEN),
                new Card(Card.Suit.DIAMONDS, Card.Rank.ACE), new Card(Card.Suit.DIAMONDS, Card.Rank.SEVEN),
                new Card(Card.Suit.HEARTS, Card.Rank.SEVEN), new Card(Card.Suit.SPADES, Card.Rank.SEVEN),
                new Card(Card.Suit.SPADES, Card.Rank.EIGHT), new Card(Card.Suit.HEARTS, Card.Rank.EIGHT));
        float[] x = LearnedBidder.features(ten, 2, 30, true);
        assertEquals(37, x.length);
        for (int i : new int[] {0, 4, 15, 19, 31, 24, 16, 8, 9, 17}) assertEquals("card " + i, 1f, x[i], 0f);
        float cards = 0;
        for (int i = 0; i < 32; i++) cards += x[i];
        assertEquals(10f, cards, 0f);
        assertEquals("rearhand", 1f, x[34], 0f);
        assertEquals(0f, x[32] + x[33], 0f);
        assertEquals(0.30f, x[35], 1e-6f);
        assertEquals(1f, x[36], 0f);
        assertEquals(0f, LearnedBidder.features(ten, 0, 0, false)[36], 0f);
    }

    @Test public void theCeilingStopsAtTheFirstRungItExpectsToLose() {
        LearnedBidder net = constant(30, 5f);
        float[] v = net.values(new float[LearnedBidder.FEATURES]);
        assertEquals(5f, v[0], 1e-6f);
        assertEquals(30, net.ceiling(v));
        v[2] = -1f;   // 22 expected to lose: stop at 20, though 23 to 30 would pay
        assertEquals(20, net.ceiling(v));
        v[0] = 0f;    // zero is not worth it
        assertEquals(0, net.ceiling(v));
        assertEquals("nothing worth it is a pass", 0,
                constant(30, -5f).ceiling(constant(30, -5f).values(new float[LearnedBidder.FEATURES])));
    }

    // ------------------------------------------------------------ at the table

    private static final SkatAi.ContractRules WITH_NULL = new SkatAi.ContractRules(EnumSet.of(
            SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS, SkatAi.ContractType.SPADES,
            SkatAi.ContractType.CLUBS, SkatAi.ContractType.GRAND, SkatAi.ContractType.NULL), false, false, false);
    private static final SkatAi.RoundPosition ROUND = SkatAi.RoundPosition.at(0);   // the human in forehand

    /** 7 8 9 of clubs, spades and hearts and the 7 of diamonds: a Null, and no trump game. */
    static List<Card> nullTen() {
        List<Card> ten = new ArrayList<>();
        for (Card.Suit suit : new Card.Suit[] {Card.Suit.CLUBS, Card.Suit.SPADES, Card.Suit.HEARTS}) {
            for (Card.Rank rank : new Card.Rank[] {Card.Rank.SEVEN, Card.Rank.EIGHT, Card.Rank.NINE}) {
                ten.add(new Card(suit, rank));
            }
        }
        ten.add(new Card(Card.Suit.DIAMONDS, Card.Rank.SEVEN));
        return ten;
    }

    static List<Card> nullSkat() {
        return List.of(new Card(Card.Suit.DIAMONDS, Card.Rank.ACE), new Card(Card.Suit.DIAMONDS, Card.Rank.TEN));
    }

    /** The answers to holding every rung from 18 to 60 against a bid, the deal priced first. */
    static List<Integer> holds(SearchAiProvider player, List<Card> ten) {
        List<Integer> answers = new ArrayList<>();
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(ROUND, SkatAi.Seat.HUMAN, new LinkedHashSet<>(ten),
                    PostSkatContractTest.rules()));
            int previous = 18;
            for (int b : LADDER) {
                if (b > 60) break;
                answers.add(session.bid(new SkatAi.BidRequest(ROUND, SkatAi.Seat.HUMAN, SkatAi.BidRole.HOLD,
                        b == 18 ? 18 : previous, b)));
                previous = b;
            }
        }
        return answers;
    }

    @Test public void itBidsTheHigherOfItsOwnPriceAndTheNets() {
        SearchAiProvider shipped = PostSkatContractTest.base();
        List<Integer> own = holds(shipped, PostSkatContractTest.ten());
        assertTrue("the shipped price holds something on this hand (else this tests nothing)", own.get(0) > 0);
        assertEquals("a net that expects to lose everywhere changes nothing",
                own, holds(shipped.withLearnedBidder(constant(264, -5f)), PostSkatContractTest.ten()));
        List<Integer> bolder = holds(shipped.withLearnedBidder(constant(40, 5f)), PostSkatContractTest.ten());
        for (int i = 0; i < bolder.size(); i++) {
            int b = LADDER[i];
            int expected = own.get(i) > 0 || b <= 40 ? b : 0;
            assertEquals("holding " + b, expected, (int) bolder.get(i));
        }
        assertEquals("off unless asked for", null, shipped.learnedBidder());
        assertTrue(shipped.withLearnedBidder(constant(40, 5f)).withLearnedDeclarer(
                LearnedDeclarerTest.favouring(Contract.HEARTS)).withPostSkatContract(4).learnedBidder() != null);
    }

    private static SkatAi.ContractAnnouncement wonAt(SearchAiProvider player, int bid) {
        List<Card> twelve = new ArrayList<>(nullTen());
        twelve.addAll(nullSkat());
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(ROUND, SkatAi.Seat.HUMAN, new LinkedHashSet<>(nullTen()),
                    WITH_NULL));
            session.pickUpSkat(new SkatAi.SkatChoiceContext(ROUND, SkatAi.Seat.HUMAN, bid));
            Set<Card> discarded = new HashSet<>(session.discardSkat(new SkatAi.SkatExchangeContext(ROUND,
                    SkatAi.Seat.HUMAN, new LinkedHashSet<>(twelve), nullSkat(), null)));
            List<Card> kept = new ArrayList<>(twelve);
            kept.removeAll(discarded);
            return session.announceContract(new SkatAi.ContractContext(ROUND, SkatAi.Seat.HUMAN,
                    new LinkedHashSet<>(kept), bid, true, WITH_NULL));
        }
    }

    @Test public void wonPastANullIntentionTheGameIsChosenAfterTheSkat() {
        SearchAiProvider declarer = PostSkatContractTest.base().withLearnedDeclarer(
                LearnedDeclarerTest.favouring(Contract.HEARTS));
        assertEquals("without the net a Null intention is left alone (and this hand intends Null)",
                SkatAi.ContractType.NULL, wonAt(declarer, 18).type);
        SearchAiProvider bidding = declarer.withLearnedBidder(constant(40, 5f));
        assertEquals("within the Null's value, still the Null", SkatAi.ContractType.NULL, wonAt(bidding, 23).type);
        assertEquals("past it, the learned declarer's game", SkatAi.ContractType.HEARTS, wonAt(bidding, 30).type);
    }
}
