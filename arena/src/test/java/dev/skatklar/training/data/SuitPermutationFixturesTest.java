package dev.skatklar.training.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.belief.BeliefEncoding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * The committed pairs the trainer's suit permutation is checked against are
 * what the encoder writes today (arena/python/suit_permutation.py).
 */
public class SuitPermutationFixturesTest {

    private static Path committed() {
        for (Path p : List.of(Path.of("python", "fixtures", "suit-permutation.bin"),
                Path.of("arena", "python", "fixtures", "suit-permutation.bin"))) {
            if (Files.exists(p)) return p;
        }
        throw new AssertionError("no python/fixtures/suit-permutation.bin from " + Path.of("").toAbsolutePath());
    }

    /** Fails when the encoder changed: run ./gradlew :arena:suitPermutationFixtures and commit the file. */
    @Test public void theCommittedPairsAreWhatTheEncoderWritesToday() throws IOException {
        assertArrayEquals("the encoder writes other pairs than the committed ones: "
                        + "./gradlew :arena:suitPermutationFixtures",
                Files.readAllBytes(committed()), SuitPermutationFixtures.generate());
    }

    @Test public void everyContractHasEveryPermutationItAdmits() {
        List<int[]> perms = SuitPermutationFixtures.permutations();
        assertEquals(24, perms.size());
        assertArrayEquals(new int[] {0, 1, 2, 3}, perms.get(0));
        for (Contract contract : Contract.values()) {
            long admitted = perms.stream().filter(p -> SuitPermutationFixtures.admits(contract, p)).count();
            assertEquals(contract.toString(), contract.trumpSuit == null ? 24 : 6, admitted);
        }
        byte[] file = SuitPermutationFixtures.generate();
        int cases = (file.length - 16) / SuitPermutationFixtures.caseBytes();
        assertEquals(SuitPermutationFixtures.PER_PERMUTATION * (4 * 6 + 3 * 24), cases);
    }

    /** The jacks keep their places except in a Null; the rest of a card keeps its rank. */
    @Test public void aCardMovesWithItsSuit() {
        int[] perm = {3, 2, 1, 0};
        Card jack = new Card(Card.Suit.CLUBS, Card.Rank.JACK);
        Card ace = new Card(Card.Suit.CLUBS, Card.Rank.ACE);
        assertEquals(jack, SuitPermutationFixtures.move(jack, perm, Contract.GRAND));
        assertEquals(jack, SuitPermutationFixtures.move(jack, perm, Contract.HEARTS));
        assertEquals(new Card(Card.Suit.DIAMONDS, Card.Rank.JACK), SuitPermutationFixtures.move(jack, perm, Contract.NULL));
        assertEquals(new Card(Card.Suit.DIAMONDS, Card.Rank.ACE), SuitPermutationFixtures.move(ace, perm, Contract.GRAND));
    }

    /**
     * Moving the trump suit is not a symmetry, and the encoder says so: the
     * trump counts change. Why a suit game admits only the side suits' six.
     */
    @Test public void movingTheTrumpSuitChangesWhatTheEncoderWrites() {
        Random random = new Random(3);
        int[] swapHeartsSpades = {0, 2, 1, 3};
        int changed = 0;
        for (int i = 0; i < 50; i++) {
            SuitPermutationFixtures.Position p = SuitPermutationFixtures.Position.random(Contract.HEARTS, random);
            float[] before = BeliefEncoding.encode(p.evidence());
            float[] after = BeliefEncoding.encode(p.moved(swapHeartsSpades).evidence());
            int mine = BeliefEncoding.TRUMPS_MINE.offset();
            int out = BeliefEncoding.TRUMPS_OUT.offset();
            if (before[mine] != after[mine] || before[out] != after[out]) changed++;
        }
        assertTrue("moving hearts in a hearts game changed the trump counts in " + changed + " of 50", changed > 25);
        assertFalse(SuitPermutationFixtures.admits(Contract.HEARTS, swapHeartsSpades));
    }

    /** Two positions of one seed are the same position: the file does not depend on the run. */
    @Test public void theFileIsTheSameEveryRun() {
        assertTrue(Arrays.equals(SuitPermutationFixtures.generate(), SuitPermutationFixtures.generate()));
    }
}
