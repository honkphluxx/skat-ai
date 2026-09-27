package dev.skatklar.training.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The teacher export records what the teacher decided, and recording changes nothing.
 *
 * <p>On a cheap search player: the games come out the same with and without
 * the recorder; every record's chosen card is legal and its votes sit on
 * legal cards only; and the world-by-world verdicts add up to the votes --
 * which also holds the verdicts to the right cards, since a verdict filed
 * under the wrong card index counts into the wrong vote.
 */
public class TeacherExportTest {

    static final class Collecting implements TeacherExportMain.Sink {
        final List<byte[]> records = new ArrayList<>();
        @Override public synchronized void accept(ByteBuffer record) {
            records.add(record.array().clone());
        }
    }

    @Test public void recordingChangesNoGameAndTheRecordsAddUp() {
        Contestant teacher = PlayerRegistry.withDefaults().resolve("search-4");
        long seed = 7;
        Collecting sink = new Collecting();
        long leftOutBefore = TeacherExportMain.INCONSISTENT.get();
        int played = 0;
        for (int i = 0; i < 10; i++) {
            Board board = Board.of(seed, i);
            GameOutcome recorded = TeacherExportMain.playBoard(teacher, List.of(), new int[] {25, 50, 25}, board, seed, sink, true);
            // The same board without a recorder, the same seeds.
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, teacher.newProvider(Seeds.mix(seed, board.index(), seat.ordinal())));
            }
            GameOutcome plain = GameRunner.play(board, seating, Seeds.mix(seed, board.index(), 0xE1E1E1L), true);
            if (recorded == null) continue;
            assertEquals("board " + i + ": passed in alike", plain.passedIn(), recorded.passedIn());
            if (!plain.passedIn()) {
                assertEquals("board " + i + ": the declarer's points", plain.declarerPoints(), recorded.declarerPoints());
                assertEquals("board " + i + ": the result", plain.declarerWon(), recorded.declarerWon());
                played++;
            }
        }
        assertTrue("games compared: " + played, played >= 5);
        assertTrue("records: " + sink.records.size(), sink.records.size() > 100);

        int withVerdicts = 0;
        for (byte[] bytes : sink.records) {
            ByteBuffer r = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(TeacherExportMain.RECORD_BYTES, bytes.length);
            int mask = r.getInt(TeacherExportMain.LEGAL_MASK);
            int chosen = r.get(TeacherExportMain.CHOSEN) & 0xFF;
            int worlds = r.get(TeacherExportMain.WORLDS) & 0xFF;
            int verdictWorlds = r.get(TeacherExportMain.VERDICT_WORLDS) & 0xFF;
            assertTrue("more than one legal card", Integer.bitCount(mask) > 1);
            assertTrue("the chosen card is legal", (mask & (1 << chosen)) != 0);
            assertEquals(Math.min(worlds, 64), verdictWorlds);
            int k = 0;
            for (int card = 0; card < 32; card++) {
                int votes = r.get(TeacherExportMain.VOTES + card) & 0xFF;
                if ((mask & (1 << card)) == 0) {
                    assertEquals("no votes on an illegal card", 0, votes);
                    continue;
                }
                assertTrue("votes within the worlds", votes <= worlds);
                long bits = r.getLong(TeacherExportMain.VERDICTS + 8 * k);
                if (verdictWorlds < 64) assertEquals("no verdict beyond the worlds", 0, bits >>> verdictWorlds);
                if (worlds <= 64) {
                    assertEquals("the verdicts add up to the vote, card " + card, votes, Long.bitCount(bits));
                } else {
                    assertTrue(Long.bitCount(bits) <= votes);
                }
                if (bits != 0) withVerdicts++;
                k++;
            }
            int trick = r.get(TeacherExportMain.TRICK) & 0xFF;
            assertTrue("trick 1..10", trick >= 1 && trick <= 10);
            for (int k2 = 0; k2 < 3; k2++) assertEquals("all teacher", 0, r.get(TeacherExportMain.SEATING + k2));
            assertEquals("three teacher seats", 3, r.get(TeacherExportMain.TEACHER_SEATS));
        }
        assertTrue("verdicts recorded: " + withVerdicts, withVerdicts > 100);
        assertEquals("decisions left out for verdicts that did not add up",
                leftOutBefore, TeacherExportMain.INCONSISTENT.get());
    }

    @Test public void aDecisionWhoseVerdictsDoNotAddUpIsCaught() {
        dev.skatklar.demo.Card a = new dev.skatklar.demo.Card(dev.skatklar.demo.Card.Suit.HEARTS, dev.skatklar.demo.Card.Rank.TEN);
        dev.skatklar.demo.Card b = new dev.skatklar.demo.Card(dev.skatklar.demo.Card.Suit.CLUBS, dev.skatklar.demo.Card.Rank.ACE);
        List<dev.skatklar.demo.Card> legal = List.of(a, b);
        assertTrue(TeacherExportMain.consistent(legal, new long[] {0b101, 0b1}, Map.of(a, 2, b, 1), 4));
        assertTrue("no bit, no vote", TeacherExportMain.consistent(legal, new long[] {0, 0b1}, Map.of(b, 1), 4));
        assertTrue("two votes, one bit", !TeacherExportMain.consistent(legal, new long[] {0b1, 0b1}, Map.of(a, 2, b, 1), 4));
        assertTrue("a bit without a vote", !TeacherExportMain.consistent(legal, new long[] {0b1, 0b11}, Map.of(a, 1, b, 1), 4));
        assertTrue("beyond 64 worlds the bits hold only the first 64",
                TeacherExportMain.consistent(legal, new long[] {0b1, 0b1}, Map.of(a, 70, b, 1), 100));
    }

    /**
     * With a population the teacher shares the table: only its seats are
     * recorded, each record names who sat where, and the seat counts follow
     * the mix. The seating written into a record is the seating drawn for
     * the board, read from the deciding seat.
     */
    @Test public void mixedSeatingRecordsOnlyTheTeacherAndSaysWhoSatWhere() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant teacher = registry.resolve("search-4");
        List<Contestant> population = List.of(registry.resolve("greedy"));
        int[] mix = {1, 1, 1};
        long seed = 11;
        int[] seatCounts = new int[4];
        for (int i = 0; i < 300; i++) {
            int[] players = TeacherExportMain.seatPlayers(Board.of(seed, i), seed, 1, mix);
            int teacherSeats = 0;
            for (int p : players) {
                assertTrue("a player index in range", p == 0 || p == 1);
                if (p == 0) teacherSeats++;
            }
            assertTrue("the teacher always sits", teacherSeats >= 1);
            seatCounts[teacherSeats]++;
            assertArrayEquals("drawn from the board and seed alone", players,
                    TeacherExportMain.seatPlayers(Board.of(seed, i), seed, 1, mix));
        }
        for (int t = 1; t <= 3; t++) assertTrue(t + " teacher seats: " + seatCounts[t], seatCounts[t] > 60);
        int[] onlyOne = TeacherExportMain.seatPlayers(Board.of(seed, 0), seed, 1, new int[] {1, 0, 0});
        assertEquals("mix 1,0,0 seats the teacher once", 2, onlyOne[0] + onlyOne[1] + onlyOne[2]);

        Collecting sink = new Collecting();
        int withStranger = 0;
        for (int i = 0; i < 12; i++) {
            Board board = Board.of(seed, i);
            int[] players = TeacherExportMain.seatPlayers(board, seed, 1, mix);
            int before = sink.records.size();
            TeacherExportMain.playBoard(teacher, population, mix, board, seed, sink, true);
            int teacherSeats = 0;
            for (int p : players) if (p == 0) teacherSeats++;
            for (int n = before; n < sink.records.size(); n++) {
                ByteBuffer r = ByteBuffer.wrap(sink.records.get(n)).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(TeacherExportMain.RECORD_BYTES, sink.records.get(n).length);
                assertEquals("only the teacher is recorded", 0, r.get(TeacherExportMain.SEATING));
                int left = r.get(TeacherExportMain.SEATING + 1), right = r.get(TeacherExportMain.SEATING + 2);
                assertEquals("the board's teacher seats", teacherSeats, r.get(TeacherExportMain.TEACHER_SEATS));
                assertEquals("the record's seating agrees with the count",
                        teacherSeats, 1 + (left == 0 ? 1 : 0) + (right == 0 ? 1 : 0));
                if (left != 0 || right != 0) withStranger++;
            }
        }
        assertTrue("records: " + sink.records.size(), sink.records.size() > 50);
        assertTrue("records beside a stranger: " + withStranger, withStranger > 20);
    }
}
