package dev.skatklar.demo.belief;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import dev.skatklar.demo.BidValues;
import org.junit.Test;

/**
 * The auction spelled out: the Java derivation against the Python one.
 *
 * <p>The expected rows are the ones {@code belief_data.bid_structure} was
 * checked against by hand on 2026-09-24, written as the seventeen bits of one
 * seat, so a change on either side that moves a single input fails here
 * before it fails in a night's training.
 */
public class BidStructureTest {

    /** One seat's seventeen inputs, as bits: none, 5 buckets, 5 bases, null, 5 multipliers. */
    private static String seat(float[] out, int seat) {
        StringBuilder bits = new StringBuilder();
        for (int at = 0; at < BeliefEncoding.BID_STRUCTURE_PER_SEAT; at++) {
            bits.append(out[seat * BeliefEncoding.BID_STRUCTURE_PER_SEAT + at] > 0.5f ? '1' : '0');
        }
        return bits.toString();
    }

    private static float[] vector(int me, int left, int right, boolean remembered) {
        float[] features = new float[BeliefEncoding.SIZE];
        features[BeliefEncoding.BIDS_BY_SEAT.offset()] = me / 100f;
        features[BeliefEncoding.BIDS_BY_SEAT.offset() + 1] = left / 100f;
        features[BeliefEncoding.BIDS_BY_SEAT.offset() + 2] = right / 100f;
        features[BeliefEncoding.BIDDING_PRESENT.offset()] = remembered ? 1 : 0;
        return features;
    }

    @Test public void theHandCheckedRows() {
        float[] out = BeliefEncoding.bidStructure(vector(18, 33, 0, true));
        assertEquals("18: bucket 18-24, diamonds, twice", "01000010000010000", seat(out, 0));
        assertEquals("33: bucket 27-36, spades, three times", "00100000100001000", seat(out, 1));
        assertEquals("no bid", "10000000000000000", seat(out, 2));

        out = BeliefEncoding.bidStructure(vector(24, 0, 46, true));
        assertEquals("24: clubs twice or a grand", "01000000011010000", seat(out, 0));
        assertEquals("46: bucket 40-48, a Null price, no suit reads it", "00010000000100000", seat(out, 2));

        out = BeliefEncoding.bidStructure(vector(72, 20, 36, true));
        assertEquals("72: diamonds, clubs, grand; clubs six times", "00001010011000001", seat(out, 0));
        assertEquals("20: hearts twice", "01000001000010000", seat(out, 1));
        assertEquals("36: diamonds or clubs; clubs three times", "00100010010000100", seat(out, 2));
    }

    @Test public void aForgottenAuctionSpellsNothing() {
        float[] out = BeliefEncoding.bidStructure(vector(24, 33, 46, false));
        assertArrayEquals(new float[BeliefEncoding.BID_STRUCTURE_WIDTH], out, 0f);
    }

    /**
     * The trainer reads the bid back out of a byte. Every legal bid must survive
     * that round trip, or the player and the trainer spell different auctions
     * -- and the byte tops out at a hundred, so above that both must read 100.
     */
    @Test public void everyLegalBidSurvivesTheByte() {
        for (int bid : BidValues.LADDER) {
            float[] exact = vector(bid, bid, bid, true);
            float[] fromByte = BeliefEncoding.dequantise(BeliefEncoding.quantise(exact));
            assertEquals("bid " + bid + " read back from a byte",
                    Math.min(bid, 100), Math.round(fromByte[BeliefEncoding.BIDS_BY_SEAT.offset()] * 100f));
            assertArrayEquals("bid " + bid + " spelled the same from a byte",
                    BeliefEncoding.bidStructure(exact), BeliefEncoding.bidStructure(fromByte), 0f);
        }
    }

    @Test public void theWiderVectorKeepsTheEncodedPrefix() {
        float[] features = vector(27, 18, 0, true);
        features[3] = 0.7f;
        float[] wide = BeliefEncoding.withBidStructure(features);
        assertEquals(BeliefEncoding.BID_STRUCTURE_SIZE, wide.length);
        for (int at = 0; at < BeliefEncoding.SIZE; at++) assertEquals(features[at], wide[at], 0f);
        assertArrayEquals(BeliefEncoding.bidStructure(features),
                java.util.Arrays.copyOfRange(wide, BeliefEncoding.SIZE, wide.length), 0f);
    }
}
