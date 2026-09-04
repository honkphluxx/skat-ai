package dev.skatklar.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * What has to be true of the generator the cards come from.
 *
 * <p>These are not statistical tests of xoshiro256++ — that work is published,
 * and repeating BigCrush in a unit test would be theatre. They pin the things
 * this <em>implementation</em> could get wrong: the subclass trap in
 * {@link java.util.Random}'s constructor, the bias in a bounded draw, and the
 * claim that splitting gives independent streams.
 */
public final class RngTest {

    @Test public void theSameSeedDealsTheSameCards() {
        // The subclass trap: Random's constructor calls setSeed() before this
        // class's fields exist. If Rng ever grows a field initialiser, the state
        // is wiped after seeding and every seed produces the same stream.
        Rng first = Rng.withSeed(20260817L);
        Rng second = Rng.withSeed(20260817L);
        for (int draw = 0; draw < 500; draw++) {
            assertEquals(first.nextLong(), second.nextLong());
        }
        assertEquals(SkatDeck.deal(Rng.withSeed(7)).human,
                SkatDeck.deal(Rng.withSeed(7)).human);
    }

    @Test public void differentSeedsDealDifferentCards() {
        assertFalse(SkatDeck.deal(Rng.withSeed(7)).human
                .equals(SkatDeck.deal(Rng.withSeed(8)).human));
    }

    @Test public void aFreshGeneratorIsNotSeededFromAConstant() {
        Set<Long> firstDraws = new HashSet<>();
        for (int generator = 0; generator < 64; generator++) {
            firstDraws.add(Rng.seeded().nextLong());
        }
        assertEquals(64, firstDraws.size());
    }

    @Test public void boundedDrawsAreUniformAcrossAnAwkwardBound() {
        // 31 divides neither 2^32 nor 2^31, so a modulo-based bound would skew
        // measurably here. It is also the largest bound Collections.shuffle uses
        // on a Skat deck, which is why this bound and not another.
        int bound = 31;
        int draws = 620_000;
        int[] buckets = new int[bound];
        Rng rng = Rng.withSeed(4242);
        for (int draw = 0; draw < draws; draw++) buckets[rng.nextInt(bound)]++;
        double expected = (double) draws / bound;
        double chiSquared = 0;
        for (int count : buckets) {
            chiSquared += (count - expected) * (count - expected) / expected;
        }
        // 30 degrees of freedom: the 99.9th percentile is 59.7, so 90 is a
        // failure that means something rather than a flake waiting to happen.
        assertTrue("chi-squared " + chiSquared, chiSquared < 90);
    }

    @Test public void boundedDrawsStayInsideTheirBound() {
        Rng rng = Rng.withSeed(11);
        for (int bound = 1; bound <= 64; bound++) {
            for (int draw = 0; draw < 200; draw++) {
                int value = rng.nextInt(bound);
                assertTrue(value >= 0 && value < bound);
            }
        }
    }

    @Test public void doublesFillTheUnitInterval() {
        Rng rng = Rng.withSeed(3);
        double lowest = 1;
        double highest = 0;
        double sum = 0;
        int draws = 200_000;
        for (int draw = 0; draw < draws; draw++) {
            double value = rng.nextDouble();
            assertTrue(value >= 0 && value < 1);
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
            sum += value;
        }
        assertTrue(lowest < .001);
        assertTrue(highest > .999);
        assertEquals(.5, sum / draws, .01);
    }

    @Test public void splitStreamsDoNotMeet() {
        Rng parent = Rng.withSeed(99);
        Rng child = parent.split();
        Set<Long> fromParent = new HashSet<>();
        for (int draw = 0; draw < 5_000; draw++) fromParent.add(parent.nextLong());
        int shared = 0;
        for (int draw = 0; draw < 5_000; draw++) {
            if (fromParent.contains(child.nextLong())) shared++;
        }
        // Two 64-bit streams of 5000 draws each collide by chance with
        // probability about 1.4e-12, so anything at all here is overlap.
        assertEquals(0, shared);
    }

    @Test public void splittingIsReproducible() {
        assertEquals(Rng.withSeed(5).split().nextLong(),
                Rng.withSeed(5).split().nextLong());
    }

    @Test public void everyCardReachesEveryPositionOfAShuffledDeck() {
        // The point of the whole class: a 48-bit generator can reach only a
        // vanishing fraction of the 32! orderings. This cannot prove the deck is
        // uniform, but a generator that could not move a card to some position
        // at all would fail it.
        List<Card> ordered = new ArrayList<>(SkatDeck.ordered());
        int[][] seen = new int[SkatDeck.CARD_COUNT][SkatDeck.CARD_COUNT];
        Rng rng = Rng.withSeed(2026);
        int shuffles = 20_000;
        for (int round = 0; round < shuffles; round++) {
            List<Card> deck = new ArrayList<>(ordered);
            Collections.shuffle(deck, rng);
            for (int position = 0; position < deck.size(); position++) {
                seen[ordered.indexOf(deck.get(position))][position]++;
            }
        }
        int expected = shuffles / SkatDeck.CARD_COUNT;
        for (int card = 0; card < SkatDeck.CARD_COUNT; card++) {
            for (int position = 0; position < SkatDeck.CARD_COUNT; position++) {
                assertTrue("card " + card + " never reached " + position,
                        seen[card][position] > expected / 3);
            }
        }
    }

    @Test public void aDerivedGeneratorDoesNotShareTheDealersStream() {
        // The bug this exists to keep fixed: GameEngine used to hand the same
        // generator to the dealer and to the fallback player, so how many
        // decisions the players made decided what the next deal was.
        Rng source = Rng.withSeed(31);
        Rng derived = Rng.derive(source);
        Set<Long> fromSource = new HashSet<>();
        for (int draw = 0; draw < 2_000; draw++) fromSource.add(source.nextLong());
        for (int draw = 0; draw < 2_000; draw++) {
            assertFalse(fromSource.contains(derived.nextLong()));
        }
    }
}
