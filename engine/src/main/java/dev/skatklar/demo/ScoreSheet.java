package dev.skatklar.demo;

import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The running Skat score list: one row per finished game, and a total per seat.
 *
 * <p>The scoring is the classic Skatliste and nothing more: the declarer's game
 * value is entered in the declarer's column, and the defenders get nothing.
 * {@link SkatRules#score} has already signed the value and doubled a loss, so
 * this class does no arithmetic on it — it only adds up columns. Keeping the
 * settlement in one place matters: overbidding, announcements kept or missed,
 * and the doubling of a lost game all interact, and a second implementation
 * here would eventually disagree with the one that decided the game.
 *
 * <p>Deliberately not Seeger-Fabian. Tournament scoring pays defenders 40 and
 * adds 50 to every settled game, which is the right answer for a scored series
 * and the wrong one for a kitchen table. Should it be wanted, it belongs here
 * as a second {@code total} implementation over the same rows, not as a
 * different way of recording them.
 */
public final class ScoreSheet {
    /**
     * How many rows survive. A row is about forty bytes and the list is written
     * to preferences after every game, so this is a cap on that write, not a
     * judgement about how long a series may run. Older rows are dropped from
     * the front and their values stay in the totals.
     */
    public static final int MAX_ENTRIES = 120;

    /**
     * One settled game, as it would be written on a line of the paper list.
     *
     * <p>{@code declarer} is the seat the row is charged to. In every declared
     * game that is the declarer; in a Ramsch, which has none, it is the seat that
     * lost it or the one that marched through. The name is kept because that is
     * what the column is called and what every reader of this class already
     * expects, but the meaning is "whose row this is".
     */
    public record Entry(SkatAi.Seat declarer, Contract contract, boolean hand,
                        boolean schneiderAnnounced, boolean schwarzAnnounced,
                        boolean ouvert, int value, boolean won, boolean overbid,
                        boolean jungfrau, boolean durchmarsch) {
        public Entry {
            Objects.requireNonNull(declarer, "declarer");
            Objects.requireNonNull(contract, "contract");
        }

        /** A declared game, which has neither of the two Ramsch marks. */
        public Entry(SkatAi.Seat declarer, Contract contract, boolean hand,
                     boolean schneiderAnnounced, boolean schwarzAnnounced,
                     boolean ouvert, int value, boolean won, boolean overbid) {
            this(declarer, contract, hand, schneiderAnnounced, schwarzAnnounced,
                    ouvert, value, won, overbid, false, false);
        }

        public boolean ramsch() { return contract.isRamsch(); }
    }

    /**
     * A list that opens partway through, carrying totals forward from rows it
     * does not itself hold.
     *
     * <p>Written for the online card, whose rows scroll off the front exactly as
     * this one's do; without it the same list would read differently on the
     * server and on the paper. The carry is the "Übertrag" a page gets when the
     * previous one filled up.
     */
    public static ScoreSheet continuing(int[] carriedBySeat, int carriedCount) {
        ScoreSheet sheet = new ScoreSheet();
        if (carriedBySeat != null) {
            for (int seat = 0; seat < Math.min(carriedBySeat.length,
                    sheet.dropped.length); seat++) {
                sheet.dropped[seat] = carriedBySeat[seat];
            }
        }
        sheet.droppedCount = Math.max(0, carriedCount);
        return sheet;
    }

    private final List<Entry> entries = new ArrayList<>();
    /** Values of rows that have scrolled off the front, kept per seat. */
    private final int[] dropped = new int[SkatAi.Seat.values().length];
    /** How many rows have scrolled off, so row numbering keeps counting up. */
    private int droppedCount;

    /** Reads a finished game as the line it would occupy on the list. */
    public static Entry entryFor(SkatAi.GameResult result) {
        Objects.requireNonNull(result, "result");
        SkatAi.GameDefinition game = result.game;
        return new Entry(result.scoredSeat, game.contract, game.hand,
                game.schneiderAnnounced, game.schwarzAnnounced, game.ouvert,
                result.gameValue, result.declarerWon, result.overbid,
                result.jungfrau, result.durchmarsch);
    }

    public void add(Entry entry) {
        entries.add(Objects.requireNonNull(entry, "entry"));
        while (entries.size() > MAX_ENTRIES) {
            Entry gone = entries.remove(0);
            dropped[gone.declarer().ordinal()] += gone.value();
            droppedCount++;
        }
    }

    public void add(SkatAi.GameResult result) {
        add(entryFor(result));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty() && !hasDropped();
    }

    /** The seat's score over every game recorded, including rows since dropped. */
    public int total(SkatAi.Seat seat) {
        int sum = dropped[seat.ordinal()];
        for (Entry entry : entries) {
            if (entry.declarer() == seat) sum += entry.value();
        }
        return sum;
    }

    /**
     * The seat's score over the first {@code count} retained rows, plus the
     * rows already dropped. This is the carry line ("Übertrag") a paper list
     * gets when the page fills and the next page opens with the running total.
     */
    public int carriedTotal(SkatAi.Seat seat, int count) {
        int sum = dropped[seat.ordinal()];
        int limit = Math.max(0, Math.min(count, entries.size()));
        for (int index = 0; index < limit; index++) {
            Entry entry = entries.get(index);
            if (entry.declarer() == seat) sum += entry.value();
        }
        return sum;
    }

    /**
     * How many games have been played in total, counting rows that have been
     * dropped. Row numbering on the sheet has to keep counting up even after
     * the oldest rows are gone, or the list starts lying about how long the
     * evening has been.
     */
    public int playedCount() {
        return entries.size() + droppedCount;
    }

    /**
     * A detached copy. The renderer runs on the GL thread and reads the list
     * while the game thread is already dealing the next hand; handing it a copy
     * is cheaper and harder to get wrong than sharing one behind a lock.
     */
    public ScoreSheet copy() {
        return deserialize(serialize());
    }

    public void clear() {
        entries.clear();
        java.util.Arrays.fill(dropped, 0);
        droppedCount = 0;
    }

    private boolean hasDropped() {
        for (int value : dropped) {
            if (value != 0) return true;
        }
        return droppedCount > 0;
    }

    // ------------------------------------------------------------ persistence

    private static final String VERSION = "1";
    private static final String ROW_SEPARATOR = "|";
    private static final String FIELD_SEPARATOR = ":";

    /**
     * A flat string, because that is what {@code SharedPreferences} stores well
     * and because the alternative — a JSON document — would drag a parser into
     * the module that owns the rules.
     */
    public String serialize() {
        StringBuilder text = new StringBuilder(VERSION);
        text.append(FIELD_SEPARATOR).append(droppedCount);
        for (int value : dropped) text.append(FIELD_SEPARATOR).append(value);
        for (Entry entry : entries) {
            text.append(ROW_SEPARATOR)
                    .append(entry.declarer().ordinal()).append(FIELD_SEPARATOR)
                    .append(entry.contract().ordinal()).append(FIELD_SEPARATOR)
                    .append(flags(entry)).append(FIELD_SEPARATOR)
                    .append(entry.value());
        }
        return text.toString();
    }

    /**
     * Never throws. Stored preferences outlive the code that wrote them, and a
     * score list is not worth crashing over: anything unreadable is read as an
     * empty list, which loses an evening's score and nothing else.
     */
    public static ScoreSheet deserialize(String text) {
        ScoreSheet sheet = new ScoreSheet();
        if (text == null || text.isBlank()) return sheet;
        String[] rows = text.split("\\" + ROW_SEPARATOR, -1);
        String[] head = rows[0].split(FIELD_SEPARATOR, -1);
        if (head.length < 2 + SkatAi.Seat.values().length
                || !VERSION.equals(head[0])) {
            return sheet;
        }
        try {
            sheet.droppedCount = Math.max(0, Integer.parseInt(head[1]));
            for (int seat = 0; seat < sheet.dropped.length; seat++) {
                sheet.dropped[seat] = Integer.parseInt(head[2 + seat]);
            }
            SkatAi.Seat[] seats = SkatAi.Seat.values();
            Contract[] contracts = Contract.values();
            for (int index = 1; index < rows.length; index++) {
                String[] fields = rows[index].split(FIELD_SEPARATOR, -1);
                if (fields.length < 4) return new ScoreSheet();
                int declarer = Integer.parseInt(fields[0]);
                int contract = Integer.parseInt(fields[1]);
                int flags = Integer.parseInt(fields[2]);
                int value = Integer.parseInt(fields[3]);
                if (declarer < 0 || declarer >= seats.length
                        || contract < 0 || contract >= contracts.length) {
                    return new ScoreSheet();
                }
                sheet.entries.add(new Entry(seats[declarer], contracts[contract],
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                        (flags & 8) != 0, value, (flags & 16) != 0, (flags & 32) != 0,
                        (flags & 64) != 0, (flags & 128) != 0));
            }
        } catch (RuntimeException unreadable) {
            return new ScoreSheet();
        }
        return sheet;
    }

    private static int flags(Entry entry) {
        return (entry.hand() ? 1 : 0)
                | (entry.schneiderAnnounced() ? 2 : 0)
                | (entry.schwarzAnnounced() ? 4 : 0)
                | (entry.ouvert() ? 8 : 0)
                | (entry.won() ? 16 : 0)
                | (entry.overbid() ? 32 : 0)
                | (entry.jungfrau() ? 64 : 0)
                | (entry.durchmarsch() ? 128 : 0);
    }
}
