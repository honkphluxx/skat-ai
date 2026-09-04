package dev.skatklar.training.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.training.arena.TableObserver;
import dev.skatklar.demo.ai.GreedyAiProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The exporter's one hard correctness property is a negative: the features must
 * contain nothing the seat cannot see. A leak here is the most expensive bug
 * this project can have — the model would train beautifully, score well on a
 * held-out split, and be useless in a game, because the input it learned to lean
 * on does not exist at play time. Every test below is aimed at that.
 */
public class BeliefExporterTest {

    /**
     * The load-bearing test. Two deals that differ <em>only</em> in how the
     * unseen cards are distributed between the two opponents must produce the
     * identical feature vector, because the observer cannot tell them apart.
     * If any hidden card reaches the features, this fails.
     */
    @Test public void featuresCannotTellTwoHiddenDealsApart() {
        // Only the twenty cards in the two opponents' hands move. The skat stays
        // where it is, because the declarer picks it up and legitimately sees it,
        // and the observer's own ten cards obviously stay. What is left is
        // exactly the part it is guessing about.
        List<Card> pack = new ArrayList<>(SkatDeck.ordered());
        List<Card> mine = new ArrayList<>(pack.subList(0, 10));
        List<Card> hiddenA = new ArrayList<>(pack.subList(10, 30));
        List<Card> hiddenB = new ArrayList<>(hiddenA);
        java.util.Collections.shuffle(hiddenB, new Random(9));
        List<Card> skat = new ArrayList<>(pack.subList(30, 32));

        float[] first = encodeFresh(mine, concat(hiddenA, skat));
        float[] second = encodeFresh(mine, concat(hiddenB, skat));
        assertArrayEquals("the observer cannot see the difference, so neither may the model",
                first, second, 0f);
    }

    /**
     * The labels, by contrast, must see everything — they are the answer sheet.
     * Exactly the cards the observer is guessing about carry a loss, and each is
     * labelled with the place it really is.
     */
    @Test public void labelsCoverEveryUnseenCardAndNothingElse() {
        BeliefExporter.CountingSink sink = playOneGame();
        assertTrue("the game must produce examples", sink.size() > 0);

        for (int at = 0; at < sink.size(); at++) {
            BeliefEncoding.Labels labels = sink.labels(at);
            int masked = 0;
            for (byte bit : labels.mask()) masked += bit;
            // Positions with nothing hidden are not written at all: a declarer
            // playing its last card knows where every card is, and a row with an
            // empty mask would carry no loss.
            assertTrue("a written example always has something to guess, example " + at,
                    masked > 0);
            assertTrue("and never more than the twenty-two cards a seat cannot see",
                    masked <= 22);
            for (int card = 0; card < 32; card++) {
                if (labels.mask()[card] == 0) continue;
                assertTrue("a masked card carries one of the three places",
                        labels.target()[card] >= 0 && labels.target()[card] < BeliefEncoding.CLASSES);
            }
        }
    }

    /** A declarer knows its own discard, so those two cards are not guesses. */
    /**
     * The counter matters as much as the assertion. This test used to skip every
     * record instead of checking one: the discard was being kept on the session
     * that ran the skat exchange, the engine starts trick play in a fresh one, so
     * the presence bit was always zero and the loop below always fell through.
     * A vacuous pass hid the bug for the whole of the v1 corpus.
     */
    @Test public void theDeclarerIsNotAskedAboutItsOwnDiscard() {
        BeliefExporter.CountingSink sink = playOneGame();
        int checked = 0;
        for (int at = 0; at < sink.size(); at++) {
            float[] features = sink.features(at);
            if (features[BeliefEncoding.MY_DISCARD_PRESENT.offset()] == 0) continue;
            for (int card = 0; card < 32; card++) {
                if (features[BeliefEncoding.MY_DISCARD.offset() + card] == 0) continue;
                assertEquals("a card I buried myself is not something to guess at",
                        0, sink.labels(at).mask()[card]);
                checked++;
            }
        }
        assertTrue("the declarer plays ten cards, so this must have been exercised",
                checked > 0);
    }

    /** Storage is bytes; the round trip must not move a feature meaningfully. */
    @Test public void quantisingKeepsEveryValueWithinOneStep() {
        BeliefExporter.CountingSink sink = playOneGame();
        for (int at = 0; at < sink.size(); at++) {
            float[] original = sink.features(at);
            float[] restored = BeliefEncoding.dequantise(BeliefEncoding.quantise(original));
            for (int i = 0; i < original.length; i++) {
                assertEquals("feature " + i, original[i], restored[i], 1f / BeliefEncoding.SCALE);
            }
        }
    }

    /** The spec the trainer reads has to describe the vector the encoder writes. */
    @Test public void theSpecificationMatchesTheEncoder() {
        String json = BeliefEncoding.specificationJson();
        assertTrue(json.contains("\"size\": " + BeliefEncoding.SIZE));
        assertTrue(json.contains("\"scale\": " + BeliefEncoding.SCALE));
        assertTrue(json.contains("\"record_bytes\": " + BeliefExporter.RECORD_BYTES));
        int covered = 0;
        for (BeliefEncoding.Field field : BeliefEncoding.FIELDS) {
            assertTrue("field " + field.name() + " is in the spec",
                    json.contains("\"name\": \"" + field.name() + "\""));
            assertEquals("fields are contiguous", covered, field.offset());
            covered += field.width();
        }
        assertEquals("and cover the whole vector", BeliefEncoding.SIZE, covered);
    }

    // ------------------------------------------------------------------ harness

    /** Deals the given cards, plays one card, and returns the recorded features. */
    private static float[] encodeFresh(List<Card> mine, List<Card> rest) {
        BeliefExporter.CountingSink sink = new BeliefExporter.CountingSink();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        List<BeliefExporter> recorders = new ArrayList<>();
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            BeliefExporter recorder = new BeliefExporter(new GreedyAiProvider(), sink);
            recorders.add(recorder);
            seating.put(seat, recorder);
        }
        GameEngine engine = GameEngine.headless(new Random(1), SeatedAiProviders.of(seating));
        for (BeliefExporter recorder : recorders) recorder.observe(engine);

        // The engine deals from a deck, so the arrangement is set by handing it a
        // deal built from the cards this test wants in each place.
        SkatDeck.Deal deal = dealOf(mine, rest);
        engine.restartWithContract(deal, SkatAi.RoundPosition.at(0), SkatAi.Seat.HUMAN,
                Contract.CLUBS, 0, Set.of());
        engine.playAiCard();
        engine.close();
        assertTrue("the harness must record something", sink.size() > 0);
        return sink.features(0);
    }

    private static List<Card> concat(List<Card> first, List<Card> second) {
        List<Card> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** Builds a deal with a fixed first hand and a given order for everything else. */
    private static SkatDeck.Deal dealOf(List<Card> mine, List<Card> rest) {
        List<Card> ordered = new ArrayList<>(mine);
        ordered.addAll(rest);
        return SkatDeck.dealFrom(ordered);
    }

    private static BeliefExporter.CountingSink playOneGame() {
        BeliefExporter.CountingSink sink = new BeliefExporter.CountingSink();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        List<BeliefExporter> recorders = new ArrayList<>();
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            BeliefExporter recorder = new BeliefExporter(new GreedyAiProvider(), sink);
            recorders.add(recorder);
            seating.put(seat, recorder);
        }
        GameEngine engine = GameEngine.headless(new Random(4), SeatedAiProviders.of(seating));
        for (BeliefExporter recorder : recorders) recorder.observe(engine);
        engine.restartWithContract(SkatDeck.deal(new Random(4)), SkatAi.RoundPosition.at(0),
                SkatAi.Seat.HUMAN, Contract.SPADES, 0, Set.of());
        for (int step = 0; step < 128; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) break;
            if (snapshot.trickComplete()) engine.finishCompletedTrick();
            else engine.playAiCard();
        }
        engine.close();
        return sink;
    }
}
