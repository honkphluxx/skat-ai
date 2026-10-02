package dev.skatklar.training.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.belief.PolicyNet;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * Self-play writes what was played and what came of it, and its seats read
 * the teacher's own evidence.
 *
 * <p>The evidence first: at every decision of a perfect-memory search player
 * the encoding the actor would build from the same context is byte for byte
 * the encoding the player built -- the bids and the discard taken from the
 * player, since those reach the two through different sessions. Then the
 * records, on a net of known scores: the card the value head (or the policy
 * head) rates highest among the legal ones, every one when epsilon is zero and
 * none of them when it is one; the outcome the same for the two defenders and
 * the opposite for the declarer; the points adding up to 120; the declarer's
 * discard and the auction's bids in its encoding.
 */
public class SelfPlayExportTest {

    @Test public void theActorReadsTheTeachersEvidence() {
        long seed = 31;
        Contestant greedy = PlayerRegistry.withDefaults().resolve("greedy");
        ContractSource contracts = new AuctionContractSource(greedy, seed);
        List<byte[][]> pairs = new ArrayList<>();
        SearchAiProvider.CardPlayObserver observer = new SearchAiProvider.CardPlayObserver() {
            @Override public void decided(SearchAiProvider.CardPlayReport report) {}
            @Override public void sampled(SkatAi.DecisionContext context, BeliefEncoding.Evidence seen,
                                          List<WorldSampler.World> worlds) {
                byte[] own = BeliefEncoding.quantise(BeliefEncoding.encode(seen));
                byte[] actor = BeliefEncoding.quantise(BeliefEncoding.encode(SelfPlayExportMain.perfectMemory(
                        context, seen.highestBids(), seen.knownDiscard())));
                synchronized (pairs) { pairs.add(new byte[][] {own, actor}); }
            }
        };
        int games = 0;
        for (int i = 0; games < 4 && i < 40; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null || fixed.contract().isNull()) continue;
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, new SearchAiProvider(new GreedyAiProvider(), 2,
                        Seeds.mix(seed, i, seat.ordinal())).withCardPlayObserver(observer));
            }
            GameRunner.playFixed(board, seating, fixed, Seeds.mix(seed, i, 0xE1E1E1L));
            games++;
        }
        assertTrue("decisions compared: " + pairs.size(), pairs.size() > 40);
        int later = 0;
        int voids = 0;
        for (byte[][] pair : pairs) {
            assertArrayEquals(pair[0], pair[1]);
            // Not a vacuous comparison: most decisions come after a trick, and
            // some after a void was shown.
            if (any(pair[0], BeliefEncoding.PLAYED_PRESENT)) later++;
            if (any(pair[0], BeliefEncoding.VOID_LEFT) || any(pair[0], BeliefEncoding.VOID_RIGHT)) voids++;
        }
        assertTrue("decisions with a history: " + later, later > pairs.size() / 2);
        assertTrue("decisions with a void shown: " + voids, voids > 5);
    }

    private static boolean any(byte[] encoding, BeliefEncoding.Field field) {
        for (int at = field.offset(); at < field.offset() + field.width(); at++) if (encoding[at] != 0) return true;
        return false;
    }

    @Test public void theRecordsSayWhatWasPlayedAndWhatCameOfIt() throws IOException {
        for (boolean byValue : new boolean[] {true, false}) {
            List<ByteBuffer> records = play(byValue, 0.0, 8);
            assertTrue("records: " + records.size(), records.size() > 80);
            Map<Long, int[]> games = new HashMap<>(); // won declarer, won defender, points declarer, points defender
            int declarerRecords = 0;
            for (ByteBuffer r : records) {
                int legal = r.getInt(SelfPlayExportMain.LEGAL);
                int chosen = r.get(SelfPlayExportMain.CHOSEN) & 0xFF;
                int flags = r.get(SelfPlayExportMain.FLAGS) & 0xFF;
                assertTrue("more than one legal card", Integer.bitCount(legal) > 1);
                assertTrue("the chosen card is legal", (legal & (1 << chosen)) != 0);
                assertEquals("nothing explored at epsilon 0", 0, flags & SelfPlayExportMain.EXPLORED);
                // The scores rise with the index in the policy half and fall in the value half.
                int expected = byValue ? Integer.numberOfTrailingZeros(legal) : 31 - Integer.numberOfLeadingZeros(legal);
                assertEquals("the head's best legal card", expected, chosen);
                int estimate = r.get(SelfPlayExportMain.ESTIMATE) & 0xFF;
                assertEquals("the value head's own estimate", (int) Math.round(255 * SelfPlayExportMain.sigmoid(
                        RISING[32 + chosen])), estimate);
                int trick = r.get(SelfPlayExportMain.TRICK) & 0xFF;
                assertTrue("trick " + trick, trick >= 1 && trick <= 10);
                boolean declarer = (flags & SelfPlayExportMain.DECLARER) != 0;
                int won = r.get(SelfPlayExportMain.WON);
                int points = r.get(SelfPlayExportMain.POINTS) & 0xFF;
                int[] game = games.computeIfAbsent(r.getLong(SelfPlayExportMain.GAME),
                        k -> new int[] {-1, -1, -1, -1});
                int side = declarer ? 0 : 1;
                if (game[side] >= 0) {
                    assertEquals("one outcome for a side", game[side], won);
                    assertEquals("one score for a side", game[side + 2], points);
                }
                game[side] = won;
                game[side + 2] = points;
                if (declarer) {
                    declarerRecords++;
                    assertTrue("the declarer remembers its discard", any(r, BeliefEncoding.MY_DISCARD_PRESENT));
                }
                assertTrue("the bids were heard", any(r, BeliefEncoding.BIDDING_PRESENT));
            }
            int both = 0;
            for (int[] game : games.values()) {
                if (game[0] < 0 || game[1] < 0) continue;
                both++;
                assertEquals("declarer and defenders cannot both win", 1, game[0] + game[1]);
                assertEquals("the points add up", 120, game[2] + game[3]);
            }
            assertTrue("games with both sides recorded: " + both, both >= 5);
            assertTrue("declarer records: " + declarerRecords, declarerRecords > 10);
        }
        for (ByteBuffer r : play(true, 1.0, 3)) {
            assertEquals("everything explored at epsilon 1", SelfPlayExportMain.EXPLORED,
                    r.get(SelfPlayExportMain.FLAGS) & SelfPlayExportMain.EXPLORED);
        }
    }

    private static boolean any(ByteBuffer r, BeliefEncoding.Field field) {
        for (int at = field.offset(); at < field.offset() + field.width(); at++) if (r.get(at) != 0) return true;
        return false;
    }

    static final float[] RISING = new float[PolicyNet.OUTPUTS];
    static {
        for (int at = 0; at < 32; at++) RISING[at] = at / 4f;
        for (int at = 32; at < 64; at++) RISING[at] = -at / 8f;
    }

    private static List<ByteBuffer> play(boolean byValue, double epsilon, int games) throws IOException {
        PolicyNet net = constantNet(RISING);
        long seed = 43;
        Contestant greedy = PlayerRegistry.withDefaults().resolve("greedy");
        ContractSource contracts = new AuctionContractSource(greedy, seed);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SelfPlayExportMain.Stats stats = new SelfPlayExportMain.Stats();
        AtomicLong boards = new AtomicLong();
        SplittableRandom random = new SplittableRandom(5);
        for (long game = 0; game < games; game++) {
            SelfPlayExportMain.playOne(net, greedy, contracts, boards, seed, 3, game, byValue, epsilon, random,
                    sink, stats);
        }
        byte[] bytes = sink.toByteArray();
        assertEquals(0, bytes.length % SelfPlayExportMain.RECORD_BYTES);
        assertEquals(stats.decisions, bytes.length / SelfPlayExportMain.RECORD_BYTES);
        List<ByteBuffer> out = new ArrayList<>();
        for (int at = 0; at < bytes.length; at += SelfPlayExportMain.RECORD_BYTES) {
            out.add(ByteBuffer.wrap(bytes, at, SelfPlayExportMain.RECORD_BYTES).slice().order(ByteOrder.LITTLE_ENDIAN));
        }
        return out;
    }

    /** A net that ignores its input: one hidden layer of zeros, the head's bias the answer. */
    static PolicyNet constantNet(float[] bias) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int inputs = BeliefEncoding.SIZE;
        int hidden = 4;
        int outputs = PolicyNet.OUTPUTS;
        for (int v : new int[] {PolicyNet.MAGIC, 2, BeliefEncoding.VERSION, inputs, hidden, 1, outputs, 0x1F}) {
            out.writeInt(v);
        }
        for (int at = 0; at < hidden * inputs; at++) out.writeFloat(0f);
        for (int at = 0; at < hidden; at++) out.writeFloat(0f);
        for (int at = 0; at < hidden; at++) out.writeFloat(1f);
        for (int at = 0; at < hidden; at++) out.writeFloat(0f);
        for (int at = 0; at < outputs * hidden; at++) out.writeFloat(0f);
        for (int at = 0; at < outputs; at++) out.writeFloat(bias[at]);
        ByteArrayOutputStream parity = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(parity);
        p.writeInt(1);
        p.writeInt(inputs);
        p.writeInt(outputs);
        for (int at = 0; at < inputs; at++) p.writeFloat(at % 7 == 0 ? 1f : 0f);
        for (float v : bias) p.writeFloat(v);
        return PolicyNet.load(new ByteArrayInputStream(bytes.toByteArray()),
                new ByteArrayInputStream(parity.toByteArray()));
    }
}
