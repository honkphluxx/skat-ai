package dev.skatklar.training.data;

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
        int played = 0;
        for (int i = 0; i < 10; i++) {
            Board board = Board.of(seed, i);
            GameOutcome recorded = TeacherExportMain.playBoard(teacher, board, seed, sink, true);
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
        }
        assertTrue("verdicts recorded: " + withVerdicts, withVerdicts > 100);
    }
}
