package dev.skatklar.training.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * What encoding v2 added: a Ramsch is a recordable position now.
 *
 * <p>v1 could not say it. There was a contract slot for the six declared games
 * and a declarer field that was always somebody, so the exporter skipped those
 * decisions rather than mislabel them — which threw away one deal in eight, and
 * the one deal in eight where four of the twenty hidden cards are already
 * located by the Schieben.
 */
public class RamschRecordingTest {

    /** Passes everything, so the auction always produces a Ramsch. */
    private static final class AlwaysPasses implements SkatAiProvider {
        private final Random random;

        AlwaysPasses(Random random) { this.random = random; }

        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("passes", "Passes everything", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public int bid(SkatAi.BidRequest request) { return 0; }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    List<Card> legal = new ArrayList<>(context.legalCards);
                    return legal.get(random.nextInt(legal.size()));
                }
            };
        }
    }

    @Test public void aRamschIsRecordedRatherThanSkipped() {
        BeliefExporter.CountingSink sink = playRamsch(9);
        assertTrue("thirty decisions a deal, three seats", sink.size() > 20);
    }

    @Test public void everyRamschRecordSaysItIsOne() {
        BeliefExporter.CountingSink sink = playRamsch(9);
        int contractSlot = BeliefEncoding.CONTRACT.offset()
                + dev.skatklar.demo.Contract.RAMSCH.ordinal();
        int nobody = BeliefEncoding.DECLARER.offset() + BeliefEncoding.NO_DECLARER;

        for (int at = 0; at < sink.size(); at++) {
            float[] row = sink.features(at);
            assertEquals("contract says Ramsch", 1f, row[contractSlot], 1e-6);
            assertEquals("and nobody is playing alone", 1f, row[nobody], 1e-6);
        }
    }

    /**
     * The Schieben is in every record, and it is exactly two cards each way. Two
     * pushed and two received is the whole rule; a block that filled up over the
     * three legs would mean the seat was remembering somebody else's exchange.
     */
    @Test public void theSchiebenIsRecordedAsTwoCardsEachWay() {
        BeliefExporter.CountingSink sink = playRamsch(9);

        for (int at = 0; at < sink.size(); at++) {
            float[] row = sink.features(at);
            assertEquals(1f, row[BeliefEncoding.SCHIEBEN_PRESENT.offset()], 1e-6);
            assertEquals("two cards pushed on", 2, countBits(row, BeliefEncoding.PUSHED_ON));
            assertEquals("two cards received", 2, countBits(row, BeliefEncoding.RECEIVED));
        }
    }

    /**
     * Rearhand's push becomes the skat and stays there, so that seat is not asked
     * to guess where those two cards went. Every other seat still is: their push
     * can be pushed on again.
     */
    @Test public void rearhandIsNotAskedAboutTheCardsItBuried() {
        BeliefExporter.CountingSink sink = playRamsch(9);
        int checked = 0;

        for (int at = 0; at < sink.size(); at++) {
            float[] row = sink.features(at);
            if (row[BeliefEncoding.PUSHED_TO_SKAT.offset()] < 0.5f) continue;
            byte[] mask = sink.labels(at).mask();
            for (int card = 0; card < 32; card++) {
                if (row[BeliefEncoding.PUSHED_ON.offset() + card] < 0.5f) continue;
                assertEquals("a card this seat put in the skat itself",
                        0, mask[card]);
                checked++;
            }
        }
        assertTrue("rearhand plays too, so this must have been exercised", checked > 0);
    }

    private static int countBits(float[] row, BeliefEncoding.Field field) {
        int count = 0;
        for (int at = 0; at < field.width(); at++) {
            if (row[field.offset() + at] > 0.5f) count++;
        }
        return count;
    }

    private static BeliefExporter.CountingSink playRamsch(long seed) {
        BeliefExporter.CountingSink sink = new BeliefExporter.CountingSink();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        List<BeliefExporter> recorders = new ArrayList<>();
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            BeliefExporter recorder = new BeliefExporter(
                    new AlwaysPasses(new Random(seed + seat.ordinal())), sink);
            recorders.add(recorder);
            seating.put(seat, recorder);
        }
        GameEngine engine = GameEngine.headless(new Random(seed), SeatedAiProviders.of(seating));
        for (BeliefExporter recorder : recorders) recorder.observe(engine);
        engine.restartWithDeal(dev.skatklar.demo.SkatDeck.deal(new Random(seed)),
                SkatAi.RoundPosition.at(0), java.util.Set.of());
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
