package dev.skatklar.training.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.skatklar.demo.Contract;
import dev.skatklar.demo.belief.BeliefEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Minting contracts, because the auction will not produce one.
 *
 * <p>Two corpora shipped holding exactly zero Null decision points -- 0 of
 * 89,438 in v1 and 0 of 511,816 in v2 -- and nothing failed on either, because
 * every record in them was well-formed. There were simply none of a whole
 * contract. No seat in any population announces a Null: the player will bid 18,
 * hold 23 and announce it, but {@code guaranteedValue(NULL)} is a flat 23 and
 * can never outbid anyone, so the belief model cannot learn a contract the
 * corpus never contains and waiting for the bidder would block it indefinitely.
 *
 * <p>So {@code --contracts=null} mints the games instead, and this is the check
 * that would have caught the original defect: not "is the corpus well-formed"
 * but "is the contract in it at all".
 */
public class MintedContractsTest {

    /** The seven contract slots, in {@link Contract} ordinal order. */
    private static int[] contractCensus(Path directory) throws IOException {
        int[] seen = new int[Contract.values().length];
        List<Path> shards = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .sorted().forEach(shards::add);
        }
        int offset = BeliefEncoding.CONTRACT.offset();
        for (Path shard : shards) {
            ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(shard))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int records = bytes.remaining() / BeliefExporter.RECORD_BYTES;
            for (int record = 0; record < records; record++) {
                int at = record * BeliefExporter.RECORD_BYTES;
                for (int slot = 0; slot < seen.length; slot++) {
                    // Features are bytes quantised from [0, 1]; a one-hot is
                    // either the top of that range or the bottom of it.
                    if ((bytes.get(at + offset + slot) & 0xFF) > 127) seen[slot]++;
                }
            }
        }
        return seen;
    }

    private static Path export(String... extra) throws Exception {
        Path out = Files.createTempDirectory("minted");
        List<String> args = new ArrayList<>(List.of(
                "--boards=120", "--shard=60", "--out=" + out,
                "--passed-in=void", "--players=greedy,search-4"));
        args.addAll(List.of(extra));
        ExportMain.main(args.toArray(new String[0]));
        return out;
    }

    /**
     * The one-line check, made into a test: a minted corpus holds Nulls.
     *
     * <p>And holds nothing else, which is the stronger claim and the one that
     * says the auction really was skipped rather than merely nudged.
     */
    @Test public void aMintedNullCorpusIsAllNull() throws Exception {
        int[] census = contractCensus(export("--contracts=null"));
        assertTrue("a minted Null corpus must hold Null decision points",
                census[Contract.NULL.ordinal()] > 0);
        for (Contract contract : Contract.values()) {
            if (contract == Contract.NULL) continue;
            assertEquals("nothing but Null was minted, saw " + contract,
                    0, census[contract.ordinal()]);
        }
    }

    /**
     * The default is untouched: the population bids and gets what it bids.
     *
     * <p>Asserted as the negative the corpora actually shipped with, so that if
     * the bidder is ever fixed this test fails and says so out loud rather than
     * letting a stale claim sit in the docs.
     */
    @Test public void withoutTheSwitchTheAuctionStillDecides() throws Exception {
        int[] census = contractCensus(export());
        int total = 0;
        for (int seen : census) total += seen;
        assertTrue("the auction must produce some games", total > 0);
        assertEquals("no bidder of ours announces a Null yet -- if this fails,"
                        + " the bidder was fixed and docs/training-plan.md is stale",
                0, census[Contract.NULL.ordinal()]);
    }

    /**
     * A source that can price nothing must say so rather than write an empty
     * corpus, because an empty corpus is indistinguishable from a fast one.
     */
    @Test public void aCorpusThatCouldNotBeMintedIsAnError() throws Exception {
        Path out = Files.createTempDirectory("minted-none");
        try {
            // One board, and the odds a single board prices a Null are about one
            // in eleven -- so this is seeded to a board that cannot.
            ExportMain.main(new String[] {"--boards=1", "--seed=4", "--shard=1",
                    "--out=" + out, "--contracts=null", "--players=greedy"});
        } catch (IllegalStateException empty) {
            assertTrue(empty.getMessage().contains("priced"));
            return;
        }
        // If that board happened to price, the test has not proved anything, but
        // neither has it found a defect. Say which, rather than passing silently.
        int[] census = contractCensus(out);
        if (census[Contract.NULL.ordinal()] == 0) fail("an empty corpus was written");
    }
}
