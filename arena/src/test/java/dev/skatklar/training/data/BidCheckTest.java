package dev.skatklar.training.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

/** BidCheckMain: a task reads back, its board is the labels' or it refuses, and a play-out scores consistently. */
public class BidCheckTest {

    private static String join(List<Card> cards) {
        return cards.stream().map(c -> Integer.toString(BeliefEncoding.index(c))).collect(Collectors.joining(","));
    }

    /** Clubs at 18 for the forehand of board 3 of seed 9201, burying the first two of its ten. */
    private static String task() {
        Board board = Board.of(9201, 3);
        SkatAi.Seat fh = BidLabelMain.seatAt(board.round(), 0);
        List<Card> ten = DeclarerChoiceMain.handOf(board, fh);
        return "disputed 3 0 " + join(ten) + " " + join(board.deal().skat) + " 18 CLUBS " + join(ten.subList(0, 2))
                + " 12.500 20,-40 1,0";
    }

    @Test public void aTaskReadsBack() {
        BidCheckMain.Task t = BidCheckMain.parse(task());
        assertEquals("disputed", t.kind());
        assertEquals(3, t.board());
        assertEquals(0, t.place());
        assertEquals(10, t.ten().size());
        assertEquals(2, t.skat().size());
        assertEquals(18, t.bid());
        assertEquals(Contract.CLUBS, t.contract());
        assertEquals(t.ten().subList(0, 2), t.discard());
        assertTrue("the rest passes through", BidCheckMain.line(t, new int[][] {{1}, {1}, {2}, {3}}).startsWith(task()));
        assertTrue(BidCheckMain.line(t, new int[][] {{24, -48}, {1, 0}, {70, 40}, {74, -98}})
                .endsWith("\t24,-48 1,0 70,40 74,-98"));
    }

    @Test public void aBoardThatIsNotTheLabelsIsRefused() {
        Contestant player = PlayerRegistry.withDefaults().resolve("search-4");
        String[] f = task().split(" ");
        Board board = Board.of(9201, 3);
        // Another skat, the ten and its discard as they are: only the board check can tell.
        List<Card> other = DeclarerChoiceMain.handOf(board, BidLabelMain.seatAt(board.round(), 1)).subList(0, 2);
        f[4] = join(other);
        String wrong = String.join(" ", f);
        assertThrows(IllegalStateException.class, () -> BidCheckMain.play(player, BidCheckMain.parse(wrong), 9201, 1));
    }

    @Test public void aPlayOutScoresConsistentlyAndRepeats() {
        Contestant player = PlayerRegistry.withDefaults().resolve("search-4");
        int[][] r = BidCheckMain.play(player, BidCheckMain.parse(task()), 9201, 2);
        for (int d = 0; d < 2; d++) {
            assertTrue("won is a positive value, lost a negative one", (r[1][d] == 1) == (r[0][d] > 0));
            assertEquals("tournament points: the value and the bonus", r[0][d] + (r[1][d] == 1 ? 50 : -50), r[3][d]);
            assertTrue(r[2][d] >= 0 && r[2][d] <= 120);
        }
        int[][] again = BidCheckMain.play(player, BidCheckMain.parse(task()), 9201, 2);
        for (int i = 0; i < 4; i++) assertArrayEquals(r[i], again[i]);
    }
}
