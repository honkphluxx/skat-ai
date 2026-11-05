package dev.skatklar.training.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.players.RolloutRescueTest;
import dev.skatklar.training.players.TrueDealLabeller;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The all-cards exporter labels the table as it is, plays the main line
 * honestly, and writes what it labelled.
 *
 * <p>On a net of random weights (every position its own answer) and greedy's
 * fixed contracts, as the exporter runs:
 *
 * <ul>
 *   <li>the table handed to the play-outs at every decision is the real one:
 *       each seat's cards are exactly the cards it goes on to play, the skat
 *       the declarer's discard;</li>
 *   <li>with the main line at the top card and the play-outs at the top card
 *       for every seat, the play-out of the card the main line played is the
 *       main line: every position it feeds the net is, float for float, the
 *       main line's next decision, and it ends on the main line's result from
 *       the decider's side -- the evidence, the side and the sign all right;</li>
 *   <li>labelling does not move the main line;</li>
 *   <li>a shard is the same bytes at one thread and at three, and another seed
 *       gives other bytes;</li>
 *   <li>a record's label slots are the labels of the legal cards in card-index
 *       order.</li>
 * </ul>
 */
public class AllCardsExportTest {

    static final long SEED = 9101;
    static final Contestant GREEDY = PlayerRegistry.withDefaults().resolve("greedy");

    static AllCardsExportMain.Settings settings(long seed, int k, double epsilon) {
        return new AllCardsExportMain.Settings(Path.of("random-net"), "-", seed, k, 1.0, epsilon, 4);
    }

    /** Up to {@code games} watched games of seed {@code seed}, boards from 0. */
    static List<AllCardsExportMain.Watch> watched(PolicyNet net, TrueDealLabeller labeller, long seed, int games,
                                                  double epsilon) {
        ContractSource contracts = new AuctionContractSource(GREEDY, seed);
        List<AllCardsExportMain.Watch> out = new ArrayList<>();
        for (int i = 0; out.size() < games && i < 40; i++) {
            AllCardsExportMain.Watch watch = new AllCardsExportMain.Watch();
            List<byte[]> records = AllCardsExportMain.playBoard(net, labeller, GREEDY, contracts,
                    settings(seed, labeller == null ? 1 : labeller.k(), epsilon), Board.of(seed, i), epsilon,
                    new AllCardsExportMain.Stats(), watch);
            if (watch.outcome == null) continue;
            if (labeller != null) assertEquals("a record a decision", watch.decisions.size(), records.size());
            out.add(watch);
        }
        assertEquals(games, out.size());
        return out;
    }

    @Test public void everyTableIsTheRealOne() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(31);
        int checked = 0;
        for (AllCardsExportMain.Watch game : watched(net, new TrueDealLabeller(net, 2, 1.0), SEED, 4, 0.2)) {
            for (AllCardsExportMain.Watched w : game.decisions) {
                for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                    Set<Card> toCome = new HashSet<>();
                    for (int i = w.playsBefore(); i < game.plays.size(); i++) {
                        if (game.plays.get(i).seat == seat) toCome.add(game.plays.get(i).card);
                    }
                    assertEquals(seat + "'s cards on the table are the cards it goes on to play", toCome,
                            new HashSet<>(w.hands().get(seat.ordinal())));
                }
                assertEquals("the play-outs' skat is the declarer's discard", new HashSet<>(w.discard()),
                        new HashSet<>(w.skat()));
                checked++;
            }
        }
        assertTrue("decisions checked: " + checked, checked > 60);
    }

    @Test public void theMainLinesCardPlayedOutIsTheMainLine() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(32);
        List<TrueDealLabeller.Fed> fed = new ArrayList<>();
        TrueDealLabeller labeller = TrueDealLabeller.topOnly(net, 1).withTap(fed::add);
        int decisions = 0;
        int defenders = 0;
        List<AllCardsExportMain.Watch> games = watched(net, labeller, SEED + 1, 4, 0.0);
        List<AllCardsExportMain.Watched> everyDecision = new ArrayList<>();
        for (AllCardsExportMain.Watch game : games) everyDecision.addAll(game.decisions);
        // The fed positions came decision after decision, game after game: cut them by each decision's count.
        List<List<TrueDealLabeller.Fed>> feeds = split(fed, everyDecision);
        int offset = 0;
        for (AllCardsExportMain.Watch game : games) {
            List<AllCardsExportMain.Watched> ds = game.decisions;
            List<List<TrueDealLabeller.Fed>> perDecision = feeds.subList(offset, offset + ds.size());
            offset += ds.size();
            for (int j = 0; j < ds.size(); j++) {
                AllCardsExportMain.Watched w = ds.get(j);
                assertEquals("the main line played its top card", w.top(), w.played());
                int slot = w.labels().cards().indexOf(w.played());
                List<float[]> inputs = new ArrayList<>();
                for (TrueDealLabeller.Fed f : perDecision.get(j)) if (f.candidate() == slot) inputs.add(f.input());
                assertEquals("decision " + j + ": one position for every later decision of the main line",
                        ds.size() - j - 1, inputs.size());
                for (int i = 0; i < inputs.size(); i++) {
                    assertArrayEquals("decision " + j + ", the play-out's position " + i + " is the main line's",
                            BeliefEncoding.dequantise(ds.get(j + 1 + i).encoding()), inputs.get(i), 0f);
                }
                boolean declares = w.context().mySeat == game.outcome.declarer();
                boolean won = declares == game.outcome.declarerWon();
                int points = declares ? game.outcome.declarerPoints() : 120 - game.outcome.declarerPoints();
                int value = declares ? game.outcome.gameValue() : -game.outcome.gameValue();
                assertEquals("decision " + j + ": the decider's side's win", won ? 1 : 0, w.labels().wins()[slot]);
                assertEquals("decision " + j + ": the decider's side's points", points, w.labels().points()[slot]);
                assertEquals("decision " + j + ": the game value for the decider", value, w.labels().value()[slot]);
                decisions++;
                if (!declares) defenders++;
            }
        }
        assertTrue("decisions: " + decisions, decisions > 60);
        assertTrue("defenders' decisions: " + defenders, defenders > 30);
    }

    /** The fed positions of one game, cut into the runs that belong to each decision, in order. */
    private static List<List<TrueDealLabeller.Fed>> split(List<TrueDealLabeller.Fed> fed,
                                                          List<AllCardsExportMain.Watched> ds) {
        // Each decision's play-outs feed sum over candidates of the positions with a choice after it;
        // the labels count them, so the runs are cut by each decision's own count.
        List<List<TrueDealLabeller.Fed>> out = new ArrayList<>();
        int at = 0;
        for (AllCardsExportMain.Watched w : ds) {
            int n = (int) w.labels().positions();
            out.add(new ArrayList<>(fed.subList(at, at + n)));
            at += n;
        }
        assertEquals("every fed position belongs to a decision", fed.size(), at);
        return out;
    }

    @Test public void labellingDoesNotMoveTheMainLine() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(33);
        List<AllCardsExportMain.Watch> labelled = watched(net, new TrueDealLabeller(net, 2, 1.0), SEED + 2, 5, 0.3);
        List<AllCardsExportMain.Watch> plain = watched(net, null, SEED + 2, 5, 0.3);
        int drawn = 0;
        for (int g = 0; g < 5; g++) {
            assertEquals("game " + g + ": the same cards", plays(plain.get(g)), plays(labelled.get(g)));
            assertEquals("game " + g + ": the same result", result(plain.get(g)), result(labelled.get(g)));
            for (AllCardsExportMain.Watched w : labelled.get(g).decisions) if (w.drawn()) drawn++;
        }
        assertTrue("some main-line cards were drawn: " + drawn, drawn > 5);
    }

    private static String result(AllCardsExportMain.Watch game) {
        dev.skatklar.training.arena.GameOutcome o = game.outcome;
        return o.declarer() + " " + o.contract() + " " + o.declarerWon() + " " + o.declarerPoints() + " "
                + o.gameValue();
    }

    private static List<String> plays(AllCardsExportMain.Watch game) {
        List<String> out = new ArrayList<>();
        for (SkatAi.PlayedCard p : game.plays) out.add(p.seat + ":" + p.card);
        return out;
    }

    @Test public void aShardIsTheSameBytesAtAnyThreadCount() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(34);
        TrueDealLabeller labeller = new TrueDealLabeller(net, 2, 1.0);
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED + 3);
        AllCardsExportMain.Shard one = AllCardsExportMain.playShard(net, labeller, GREEDY, contracts,
                settings(SEED + 3, 2, 0.2), 0, 6, 1);
        AllCardsExportMain.Shard three = AllCardsExportMain.playShard(net, labeller, GREEDY, contracts,
                settings(SEED + 3, 2, 0.2), 0, 6, 3);
        assertTrue("records: " + one.records().size(), one.records().size() > 40);
        assertEquals(one.records().size(), three.records().size());
        for (int i = 0; i < one.records().size(); i++) {
            assertArrayEquals("record " + i, one.records().get(i), three.records().get(i));
        }
        ContractSource others = new AuctionContractSource(GREEDY, SEED + 4);
        AllCardsExportMain.Shard other = AllCardsExportMain.playShard(net, labeller, GREEDY, others,
                settings(SEED + 4, 2, 0.2), 0, 6, 1);
        assertNotEquals("another seed, other boards", one.totals().json(0, 6), other.totals().json(0, 6));
    }

    @Test public void withTheSearchAShardIsStillTheSameBytesAtAnyThreadCount() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(36);
        TrueDealLabeller labeller = new TrueDealLabeller(net, 2, 1.0).withSearch(7, 3);
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED + 8);
        AllCardsExportMain.Shard one = AllCardsExportMain.playShard(net, labeller, GREEDY, contracts,
                settings(SEED + 8, 2, 0.2), 0, 4, 1);
        AllCardsExportMain.Shard three = AllCardsExportMain.playShard(net, labeller, GREEDY, contracts,
                settings(SEED + 8, 2, 0.2), 0, 4, 3);
        AllCardsExportMain.Shard plain = AllCardsExportMain.playShard(net, new TrueDealLabeller(net, 2, 1.0), GREEDY,
                contracts, settings(SEED + 8, 2, 0.2), 0, 4, 1);
        assertTrue("records: " + one.records().size(), one.records().size() > 20);
        for (int i = 0; i < one.records().size(); i++) {
            assertArrayEquals("record " + i, one.records().get(i), three.records().get(i));
        }
        assertNotEquals("the search changes the labels", plain.totals().json(0, 4), one.totals().json(0, 4));
        long searches = 0;
        for (long n : one.stats().searches) searches += n;
        assertTrue("searches counted: " + searches, searches > 0);
    }

    @Test public void theReseedKeepsTheDecisionsAndChangesOnlyTheLabels() throws Exception {
        // The control's corpus pairs with the plain one record for record
        // (label_diff.py relies on it): the same position, card, trick and
        // board; only the label slots differ.
        PolicyNet net = RolloutRescueTest.randomNet(37);
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED + 9);
        AllCardsExportMain.Shard plain = AllCardsExportMain.playShard(net, new TrueDealLabeller(net, 2, 1.0), GREEDY,
                contracts, settings(SEED + 9, 2, 0.2), 0, 4, 1);
        AllCardsExportMain.Shard control = AllCardsExportMain.playShard(net,
                new TrueDealLabeller(net, 2, 1.0).withReseed(7), GREEDY, contracts, settings(SEED + 9, 2, 0.2), 0, 4, 2);
        assertTrue("records: " + plain.records().size(), plain.records().size() > 20);
        assertEquals(plain.records().size(), control.records().size());
        int labelsDiffer = 0;
        for (int i = 0; i < plain.records().size(); i++) {
            byte[] a = plain.records().get(i);
            byte[] b = control.records().get(i);
            assertArrayEquals("record " + i + " before its labels", java.util.Arrays.copyOfRange(a, 0, AllCardsExportMain.WINS),
                    java.util.Arrays.copyOfRange(b, 0, AllCardsExportMain.WINS));
            assertArrayEquals("record " + i + " after its labels", java.util.Arrays.copyOfRange(a, AllCardsExportMain.BOARD, a.length),
                    java.util.Arrays.copyOfRange(b, AllCardsExportMain.BOARD, b.length));
            if (!java.util.Arrays.equals(a, b)) labelsDiffer++;
        }
        assertTrue("labels changed: " + labelsDiffer, labelsDiffer > plain.records().size() / 3);
    }

    @Test public void theSettingsNameTheReseedOnlyWhenOn() {
        AllCardsExportMain.Settings off = new AllCardsExportMain.Settings(Path.of("m"), "-", SEED, 8, 1.0, 0.2, 4,
                List.of(), List.of(), new int[] {0, 0, 1}, 11, 0);
        AllCardsExportMain.Settings on = new AllCardsExportMain.Settings(Path.of("m"), "-", SEED, 8, 1.0, 0.2, 4,
                List.of(), List.of(), new int[] {0, 0, 1}, 11, 0, 7);
        assertTrue("an earlier corpus's settings unchanged", !off.json().contains("reseed"));
        assertTrue(off.json().endsWith("\"search_worlds\": 0\n}\n"));
        assertTrue(on.json().endsWith("\"search_worlds\": 0,\n  \"reseed_from\": 7\n}\n"));
    }

    @Test public void aRecordsSlotsAreItsLegalCardsInIndexOrder() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(35);
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED + 5);
        int records = 0;
        for (int i = 0; i < 12; i++) {
            AllCardsExportMain.Watch watch = new AllCardsExportMain.Watch();
            List<byte[]> bytes = AllCardsExportMain.playBoard(net, new TrueDealLabeller(net, 3, 1.0), GREEDY,
                    contracts, settings(SEED + 5, 3, 0.2), Board.of(SEED + 5, i), 0.2,
                    new AllCardsExportMain.Stats(), watch);
            for (int r = 0; r < bytes.size(); r++) {
                AllCardsExportMain.Watched w = watch.decisions.get(r);
                AllCardsExportMain.Totals fromBytes = new AllCardsExportMain.Totals();
                fromBytes.add(bytes.get(r));
                AllCardsExportMain.Totals fromLabels = new AllCardsExportMain.Totals();
                TrueDealLabeller.Labels l = w.labels();
                for (int c = 0; c < l.cards().size(); c++) {
                    int index = BeliefEncoding.index(l.cards().get(c));
                    fromLabels.wins[index] += l.wins()[c];
                    fromLabels.points[index] += l.points()[c];
                    fromLabels.value[index] += l.value()[c];
                }
                fromLabels.chosen[BeliefEncoding.index(w.played())]++;
                fromLabels.records = 1;
                assertEquals("record " + r + " of board " + i, fromLabels.json(0, 1), fromBytes.json(0, 1));
                assertEquals("k", 3, bytes.get(r)[AllCardsExportMain.K]);
                assertEquals("trick", w.context().history.completedTricks.size() + 1,
                        bytes.get(r)[AllCardsExportMain.TRICK]);
                records++;
            }
        }
        assertTrue("records: " + records, records > 80);
    }

    // ------------------------------------------------------------------ with a pool

    static AllCardsExportMain.Settings pooled(long seed, int k, double epsilon) {
        return new AllCardsExportMain.Settings(Path.of("random-net"), "-", seed, k, 1.0, epsilon, 4,
                List.of(Path.of("a"), Path.of("b")), List.of("-", "-"), new int[] {40, 40, 20});
    }

    /** Watched games with a pool of two nets beside the student, boards with a pool seat only. */
    static List<AllCardsExportMain.Watch> watchedWithPool(PolicyNet net, List<PolicyNet> pool,
                                                          TrueDealLabeller labeller, long seed, int games,
                                                          double epsilon) {
        ContractSource contracts = new AuctionContractSource(GREEDY, seed);
        List<AllCardsExportMain.Watch> out = new ArrayList<>();
        for (int i = 0; out.size() < games && i < 80; i++) {
            AllCardsExportMain.Watch watch = new AllCardsExportMain.Watch();
            List<byte[]> records = AllCardsExportMain.playBoard(net, pool, labeller, GREEDY, contracts,
                    pooled(seed, labeller.k(), epsilon), Board.of(seed, i), epsilon, new AllCardsExportMain.Stats(),
                    watch);
            if (watch.outcome == null) continue;
            assertEquals("a record a labelled decision", watch.decisions.size(), records.size());
            int students = 0;
            for (int role : watch.roles) if (role == 0) students++;
            if (students == 3) continue;
            out.add(watch);
        }
        assertEquals(games, out.size());
        return out;
    }

    @Test public void withAPoolEverySeatIsPlayedByItsOwnNet() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(50);
        List<PolicyNet> pool = List.of(RolloutRescueTest.randomNet(51), RolloutRescueTest.randomNet(52));
        List<TrueDealLabeller.Fed> fed = new ArrayList<>();
        TrueDealLabeller labeller = TrueDealLabeller.topOnly(net, 1).withTap(fed::add);
        List<AllCardsExportMain.Watch> games = watchedWithPool(net, pool, labeller, SEED + 6, 6, 0.0);
        List<AllCardsExportMain.Watched> everyDecision = new ArrayList<>();
        for (AllCardsExportMain.Watch game : games) everyDecision.addAll(game.decisions);
        List<List<TrueDealLabeller.Fed>> feeds = split(fed, everyDecision);
        int offset = 0;
        int decisions = 0;
        int poolPositions = 0;
        Set<PolicyNet> poolSeen = new HashSet<>();
        for (AllCardsExportMain.Watch game : games) {
            List<AllCardsExportMain.Watched> ds = game.decisions;
            List<List<TrueDealLabeller.Fed>> perDecision = feeds.subList(offset, offset + ds.size());
            offset += ds.size();
            for (int j = 0; j < ds.size(); j++) {
                AllCardsExportMain.Watched w = ds.get(j);
                assertEquals("only the student's decisions are labelled", 0, game.roles[w.context().mySeat.ordinal()]);
                int slot = w.labels().cards().indexOf(w.played());
                List<float[]> studentInputs = new ArrayList<>();
                for (TrueDealLabeller.Fed f : perDecision.get(j)) {
                    assertTrue("every play-out position goes to its seat's net",
                            f.net() == game.bySeat[f.seat().ordinal()]);
                    if (game.roles[f.seat().ordinal()] != 0) {
                        poolPositions++;
                        poolSeen.add(f.net());
                    }
                    if (f.candidate() == slot && game.roles[f.seat().ordinal()] == 0) studentInputs.add(f.input());
                }
                assertEquals("decision " + j + ": one student position for every later labelled decision",
                        ds.size() - j - 1, studentInputs.size());
                for (int i = 0; i < studentInputs.size(); i++) {
                    assertArrayEquals("the play-out's student position is the main line's",
                            BeliefEncoding.dequantise(ds.get(j + 1 + i).encoding()), studentInputs.get(i), 0f);
                }
                boolean declares = w.context().mySeat == game.outcome.declarer();
                int value = declares ? game.outcome.gameValue() : -game.outcome.gameValue();
                int points = declares ? game.outcome.declarerPoints() : 120 - game.outcome.declarerPoints();
                assertEquals("decision " + j + ": the main line's points from the decider's side", points,
                        w.labels().points()[slot]);
                assertEquals("decision " + j + ": the main line's game value", value, w.labels().value()[slot]);
                decisions++;
            }
        }
        assertTrue("decisions: " + decisions, decisions > 30);
        assertTrue("pool seats played in the play-outs: " + poolPositions, poolPositions > 100);
        assertEquals("both pool nets sat at a table", 2, poolSeen.size());
    }

    @Test public void aRecordSaysWhoSatBesideTheDecider() throws Exception {
        PolicyNet net = RolloutRescueTest.randomNet(53);
        List<PolicyNet> pool = List.of(RolloutRescueTest.randomNet(54), RolloutRescueTest.randomNet(55));
        ContractSource contracts = new AuctionContractSource(GREEDY, SEED + 7);
        int checked = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            AllCardsExportMain.Watch watch = new AllCardsExportMain.Watch();
            List<byte[]> bytes = AllCardsExportMain.playBoard(net, pool, new TrueDealLabeller(net, 1, 1.0), GREEDY,
                    contracts, pooled(SEED + 7, 1, 0.2), Board.of(SEED + 7, i), 0.2, new AllCardsExportMain.Stats(),
                    watch);
            for (int r = 0; r < bytes.size(); r++) {
                SkatAi.Seat me = watch.decisions.get(r).context().mySeat;
                int students = 0;
                for (int role : watch.roles) if (role == 0) students++;
                byte[] b = bytes.get(r);
                assertEquals("the seat after the decider", watch.roles[me.next().ordinal()], b[AllCardsExportMain.ROLES]);
                assertEquals("the seat before it", watch.roles[me.next().next().ordinal()],
                        b[AllCardsExportMain.ROLES + 1]);
                assertEquals("the student's seats", students, b[AllCardsExportMain.ROLES + 2]);
                seen.add(b[AllCardsExportMain.ROLES] + "/" + b[AllCardsExportMain.ROLES + 1]);
                checked++;
            }
        }
        assertTrue("records: " + checked, checked > 100);
        assertTrue("several seatings: " + seen, seen.size() >= 4);
    }

    @Test public void theSeatingFollowsTheMix() {
        int[] mix = {40, 40, 20};
        int[] count = new int[4];
        int[] byNet = new int[3];
        for (int b = 0; b < 6000; b++) {
            int[] roles = AllCardsExportMain.seating(9003, b, 2, mix);
            assertArrayEquals("the same board, the same seating", roles, AllCardsExportMain.seating(9003, b, 2, mix));
            int students = 0;
            for (int role : roles) {
                if (role == 0) students++;
                byNet[role]++;
            }
            count[students]++;
        }
        assertEquals("the student always sits", 0, count[0]);
        assertEquals("one seat", 0.40, count[1] / 6000.0, 0.02);
        assertEquals("two seats", 0.40, count[2] / 6000.0, 0.02);
        assertEquals("three seats", 0.20, count[3] / 6000.0, 0.02);
        assertEquals("the pool nets alike", 1.0, byNet[1] / (double) byNet[2], 0.08);
        int[] firstSeat = new int[3];
        for (int b = 0; b < 3000; b++) if (AllCardsExportMain.seating(9003, b, 2, mix)[0] == 0) firstSeat[0]++;
        assertEquals("each seat is the student's about as often", (0.4 + 0.8 + 0.6) / 3, firstSeat[0] / 3000.0, 0.03);
        assertArrayEquals("no pool, the student everywhere", new int[3], AllCardsExportMain.seating(9003, 7, 0, mix));
    }

    @Test(expected = IllegalArgumentException.class)
    public void theTeachersAndTheArenasSeedsAreRefused() {
        AllCardsExportMain.checkSeed(101);
    }
}
