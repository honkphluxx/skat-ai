package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.ai.SkatAi;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class ScoreSheetTest {

    private static SkatAi.GameDefinition game(SkatAi.Seat declarer, Contract contract,
                                              boolean hand, int bid) {
        return new SkatAi.GameDefinition(declarer, SkatAi.Seat.HUMAN, contract,
                new SkatAi.RoundPosition(0, SkatAi.Seat.OPPONENT_TWO), bid,
                hand, false, false, false);
    }

    private static ScoreSheet.Entry entry(SkatAi.Seat declarer, Contract contract, int value) {
        return new ScoreSheet.Entry(declarer, contract, false, false, false, false,
                value, value > 0, false);
    }

    @Test
    public void theDeclarerAloneIsCreditedOrCharged() {
        ScoreSheet sheet = new ScoreSheet();
        sheet.add(entry(SkatAi.Seat.HUMAN, Contract.CLUBS, 60));
        sheet.add(entry(SkatAi.Seat.OPPONENT_ONE, Contract.GRAND, 96));
        sheet.add(entry(SkatAi.Seat.HUMAN, Contract.HEARTS, -40));

        assertEquals(20, sheet.total(SkatAi.Seat.HUMAN));
        assertEquals(96, sheet.total(SkatAi.Seat.OPPONENT_ONE));
        // Defending is worth nothing on a classic Skatliste, even three times over.
        assertEquals(0, sheet.total(SkatAi.Seat.OPPONENT_TWO));
    }

    @Test
    public void aLostGameIsEnteredAsTheEngineSettledIt() {
        // The doubling of a lost game belongs to SkatRules; the sheet must not
        // apply it a second time.
        SkatAi.GameResult lost = new SkatAi.GameResult(
                game(SkatAi.Seat.HUMAN, Contract.CLUBS, false, 24), false, 45, 75, 6,
                -48, false, Map.of(), Map.of());
        ScoreSheet sheet = new ScoreSheet();
        sheet.add(lost);

        assertEquals(-48, sheet.total(SkatAi.Seat.HUMAN));
        assertEquals(1, sheet.size());
        assertFalse(sheet.entries().get(0).won());
    }

    @Test
    public void anOverbidGameKeepsItsMarkThroughAStorageRoundTrip() {
        SkatAi.GameResult overbid = new SkatAi.GameResult(
                game(SkatAi.Seat.OPPONENT_TWO, Contract.DIAMONDS, true, 60), false, 95, 25, 4,
                -72, true, Map.of(), Map.of());
        ScoreSheet sheet = new ScoreSheet();
        sheet.add(overbid);

        ScoreSheet restored = ScoreSheet.deserialize(sheet.serialize());
        ScoreSheet.Entry entry = restored.entries().get(0);
        assertTrue(entry.overbid());
        assertTrue(entry.hand());
        assertEquals(Contract.DIAMONDS, entry.contract());
        assertEquals(SkatAi.Seat.OPPONENT_TWO, entry.declarer());
        assertEquals(-72, restored.total(SkatAi.Seat.OPPONENT_TWO));
    }

    @Test
    public void everyAnnouncementSurvivesStorage() {
        ScoreSheet sheet = new ScoreSheet();
        sheet.add(new ScoreSheet.Entry(SkatAi.Seat.HUMAN, Contract.GRAND,
                true, true, true, true, 216, true, false));

        ScoreSheet.Entry entry = ScoreSheet.deserialize(sheet.serialize()).entries().get(0);
        assertTrue(entry.hand());
        assertTrue(entry.schneiderAnnounced());
        assertTrue(entry.schwarzAnnounced());
        assertTrue(entry.ouvert());
        assertTrue(entry.won());
    }

    @Test
    public void anEmptySheetRoundTripsToAnEmptySheet() {
        ScoreSheet restored = ScoreSheet.deserialize(new ScoreSheet().serialize());
        assertTrue(restored.isEmpty());
        assertEquals(0, restored.playedCount());
    }

    @Test
    public void unreadableStorageIsReadAsNoScoreRatherThanThrowing() {
        // Preferences outlive the code that wrote them. Losing an evening's
        // score is survivable; refusing to open the app is not.
        for (String broken : List.of("", "   ", "2|0:0:0:1", "1", "1:0:0:0:0|nonsense",
                "1:0:0:0:0|9:0:0:1", "1:0:0:0:0|0:99:0:1", "1:a:0:0:0")) {
            ScoreSheet sheet = ScoreSheet.deserialize(broken);
            assertTrue("should be empty for: " + broken, sheet.isEmpty());
        }
    }

    @Test
    public void theOldestRowsAreDroppedButTheirValuesAreNot() {
        ScoreSheet sheet = new ScoreSheet();
        for (int index = 0; index < ScoreSheet.MAX_ENTRIES + 10; index++) {
            sheet.add(entry(SkatAi.Seat.HUMAN, Contract.CLUBS, 10));
        }
        assertEquals(ScoreSheet.MAX_ENTRIES, sheet.size());
        assertEquals(ScoreSheet.MAX_ENTRIES + 10, sheet.playedCount());
        assertEquals((ScoreSheet.MAX_ENTRIES + 10) * 10, sheet.total(SkatAi.Seat.HUMAN));

        // ...and a dropped row still counts after a trip through storage.
        ScoreSheet restored = ScoreSheet.deserialize(sheet.serialize());
        assertEquals((ScoreSheet.MAX_ENTRIES + 10) * 10, restored.total(SkatAi.Seat.HUMAN));
        assertEquals(ScoreSheet.MAX_ENTRIES + 10, restored.playedCount());
        assertFalse(restored.isEmpty());
    }

    @Test
    public void theCarryLineHoldsExactlyWhatTheHiddenRowsHeld() {
        ScoreSheet sheet = new ScoreSheet();
        sheet.add(entry(SkatAi.Seat.HUMAN, Contract.CLUBS, 24));
        sheet.add(entry(SkatAi.Seat.OPPONENT_ONE, Contract.GRAND, 48));
        sheet.add(entry(SkatAi.Seat.HUMAN, Contract.SPADES, -22));
        sheet.add(entry(SkatAi.Seat.OPPONENT_TWO, Contract.NULL, 23));

        assertEquals(24, sheet.carriedTotal(SkatAi.Seat.HUMAN, 2));
        assertEquals(48, sheet.carriedTotal(SkatAi.Seat.OPPONENT_ONE, 2));
        assertEquals(0, sheet.carriedTotal(SkatAi.Seat.OPPONENT_TWO, 2));
        // The carry plus the rows still shown must be the standing total, or the
        // page would silently disagree with itself.
        assertEquals(sheet.total(SkatAi.Seat.HUMAN),
                sheet.carriedTotal(SkatAi.Seat.HUMAN, 2) - 22);
        assertEquals(sheet.total(SkatAi.Seat.OPPONENT_TWO),
                sheet.carriedTotal(SkatAi.Seat.OPPONENT_TWO, sheet.size()));
    }

    @Test
    public void clearingLosesEverythingIncludingWhatHadScrolledOff() {
        ScoreSheet sheet = new ScoreSheet();
        for (int index = 0; index < ScoreSheet.MAX_ENTRIES + 5; index++) {
            sheet.add(entry(SkatAi.Seat.OPPONENT_ONE, Contract.CLUBS, 9));
        }
        sheet.clear();

        assertTrue(sheet.isEmpty());
        assertEquals(0, sheet.size());
        assertEquals(0, sheet.playedCount());
        assertEquals(0, sheet.total(SkatAi.Seat.OPPONENT_ONE));
    }

    @Test
    public void aCopyIsDetachedFromTheListItCameFrom() {
        ScoreSheet sheet = new ScoreSheet();
        sheet.add(entry(SkatAi.Seat.HUMAN, Contract.CLUBS, 60));
        ScoreSheet copy = sheet.copy();
        sheet.add(entry(SkatAi.Seat.HUMAN, Contract.GRAND, 96));

        assertEquals(60, copy.total(SkatAi.Seat.HUMAN));
        assertEquals(156, sheet.total(SkatAi.Seat.HUMAN));
    }

    @Test
    public void aFinishedGameIsReadStraightOffTheResult() {
        SkatAi.GameResult result = new SkatAi.GameResult(
                game(SkatAi.Seat.OPPONENT_ONE, Contract.NULL, true, 35), true, 0, 120, 0,
                35, false, Map.of(), Map.of());
        ScoreSheet.Entry entry = ScoreSheet.entryFor(result);

        assertEquals(SkatAi.Seat.OPPONENT_ONE, entry.declarer());
        assertEquals(Contract.NULL, entry.contract());
        assertTrue(entry.hand());
        assertTrue(entry.won());
        assertEquals(35, entry.value());
    }
}
