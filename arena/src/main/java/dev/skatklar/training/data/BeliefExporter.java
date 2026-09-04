package dev.skatklar.training.data;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.training.arena.TableObserver;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns played games into labelled training examples for the belief model.
 *
 * <p><b>Nothing a seat learns before the first card may be kept on the session.</b>
 * The engine runs the auction, the skat exchange and the Schieben in one session
 * and starts trick play in a fresh one, so a field on {@link Session} is gone by
 * the time a card is chosen -- silently, because an empty field and an honest
 * absence look identical here by design. Three separate pieces of evidence were
 * lost to it before it was noticed: the declarer's own discard, the Schieben, and
 * the whole auction. They live on the provider now, cleared when the next deal is
 * prepared, and anything added later belongs there too.
 *
 * <p>The point of generating our own data is that the label is free and exact.
 * Every game the arena plays knows where every card was, so each decision point
 * yields a complete answer to the question the model is being asked — no archive
 * to license, no human to imitate, and no chance of a mislabelled example.
 *
 * <p>It records through the same seam a cheating player would use, which is why
 * it lives in this module and not in {@code core}: {@link TableObserver} hands it
 * the engine, it reads the truth for the labels, and the <em>features</em> come
 * from the observing seat's own evidence and nothing else. That split is the
 * whole correctness property here — a feature accidentally computed from the
 * truth produces a model that scores beautifully in training and is useless in a
 * game, because at play time that input simply does not exist.
 *
 * <p>Written as flat binary: one record is {@code SIZE} feature bytes, then 32
 * label bytes, 32 mask bytes and a three-byte diagnostic trailer. No framing, no compression, no dependency —
 * {@code numpy.fromfile(path, dtype=numpy.uint8).reshape(-1, RECORD_BYTES)} reads
 * a shard in one line, and the features come back as floats by dividing by
 * {@link BeliefEncoding#SCALE}.
 */
public final class BeliefExporter implements SkatAiProvider, TableObserver {

    /**
     * Which player sat in each seat, relative to the observer, as an index into
     * the population -- {@link BeliefEncoding#TRAILER_BYTES} bytes of diagnostics.
     *
     * <p><b>Never an input.</b> At play time the opponent is a human and there is
     * no such label, so a model trained on it would lean on something that does
     * not exist -- the exact failure the feature-leak test exists to prevent. It
     * is recorded because it answers a question cheaply that would otherwise cost
     * a regenerated corpus: <em>how much would knowing the opponent's style be
     * worth?</em> Train once with the label as an extra input, measure the
     * tournament points it adds, and only then decide whether adapting to the
     * opponent deserves the machinery. That is the trick the belief sweep used,
     * and here it costs three bytes a record.
     */
    public static final int TRAILER_BYTES = BeliefEncoding.TRAILER_BYTES;

    /**
     * The board this record came from, little-endian, appended after the seating.
     *
     * <p>Also never an input, and there for one reason: a validation split has to
     * be made by <b>board</b> and not by record. One board yields about thirty
     * records off the same thirty-two cards, so a random split by record puts
     * near-copies of training rows into validation and reports a score that has
     * nothing to do with a deal the model has not seen. Without this the corpus
     * cannot be split honestly at all -- the shards are written from four threads
     * and carry no other clue which board a row belongs to.
     */
    public static final int BOARD_BYTES = BeliefEncoding.BOARD_BYTES;

    /** Bytes per record: the input vector, the label, its mask, and the trailer. */
    public static final int RECORD_BYTES =
            BeliefEncoding.SIZE + 32 + 32 + TRAILER_BYTES + BOARD_BYTES;

    private final SkatAiProvider delegate;
    private final Sink sink;
    private final Map<SkatAi.Seat, Integer> populationIndex;
    private final int board;
    private volatile GameEngine engine;
    /**
     * This seat's leg of the Schieben, held on the provider rather than on the
     * session.
     *
     * <p>The engine runs the Schieben in the bidding session and starts trick
     * play in a fresh one, so a field on the session is gone by the time a card
     * is chosen — the same trap the search player's own discard fell into. It is
     * cleared when the next deal is prepared.
     */
    private volatile BeliefEncoding.Schieben schieben;
    /**
     * The declarer's own two buried cards, held here for the same reason and
     * after the same bug: they were recorded on the session that ran the skat
     * exchange, and every feature vector in the v1 corpus therefore said the
     * declarer had no idea what it had buried -- while the label asked it to
     * guess those exact two cards. The test that was supposed to catch that
     * skipped every record instead of failing on one.
     */
    private volatile List<Card> myDiscard;
    /**
     * The highest bid each seat made, and the third thing to be lost to the same
     * mistake.
     *
     * <p>Bids are observed in the bidding session and every record is written
     * from the play session, so this map was empty at every decision point and
     * the whole auction block -- the evidence this project has repeatedly called
     * the strongest single signal about where the cards are -- was absent from
     * the corpus while its presence bit correctly said so. Nothing was wrong with
     * the encoding; the data simply never arrived.
     */
    private final Map<SkatAi.Seat, Integer> highestBids = new EnumMap<>(SkatAi.Seat.class);

    /**
     * @param delegate the player actually making the decisions; its strength
     *                 shapes the positions that get recorded, which is why the
     *                 exporter is run against a population rather than one
     *                 opponent
     */
    public BeliefExporter(SkatAiProvider delegate, Sink sink,
                          Map<SkatAi.Seat, Integer> populationIndex, int board) {
        this.delegate = delegate;
        this.sink = sink;
        this.populationIndex = populationIndex;
        this.board = board;
    }

    public BeliefExporter(SkatAiProvider delegate, Sink sink,
                          Map<SkatAi.Seat, Integer> populationIndex) {
        this(delegate, sink, populationIndex, 0);
    }

    public BeliefExporter(SkatAiProvider delegate, Sink sink) {
        this(delegate, sink, Map.of(), 0);
    }

    @Override public void observe(GameEngine engine) { this.engine = engine; }

    @Override public SkatAi.AiDescriptor descriptor() { return delegate.descriptor(); }

    @Override public SkatAiSession createSession() {
        return new Session(delegate.createSession());
    }

    private final class Session implements SkatAiSession {
        private final SkatAiSession inner;
        private SkatAi.ContraLevel contra = SkatAi.ContraLevel.NONE;
        private SkatAi.Seat contraSeat;

        Session(SkatAiSession inner) { this.inner = inner; }

        @Override public void prepareDeal(SkatAi.DealContext context) {
            highestBids.clear();
            myDiscard = null;
            schieben = null;
            contra = SkatAi.ContraLevel.NONE;
            contraSeat = null;
            inner.prepareDeal(context);
        }

        @Override public int bid(SkatAi.BidRequest request) { return inner.bid(request); }

        @Override public void bidObserved(SkatAi.BidEvent event) {
            if (!event.passed) {
                highestBids.merge(event.seat, event.value, Math::max);
            }
            inner.bidObserved(event);
        }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            return inner.pickUpSkat(context);
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            Set<Card> discarded = inner.discardSkat(context);
            myDiscard = List.copyOf(discarded);
            return discarded;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
            return inner.announceContract(context);
        }

        /**
         * The seat's own leg of the Schieben, kept as evidence.
         *
         * <p>Four of the twenty hidden cards are located exactly by this exchange,
         * for every seat, before a card is played -- a stronger prior than the
         * auction ever gives, and the reason a Ramsch is worth recording at all.
         */
        @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) {
            Set<Card> pushed = inner.pushCards(context);
            schieben = new BeliefEncoding.Schieben(List.copyOf(pushed), context.to == null,
                    List.copyOf(context.received), context.from == null);
            return pushed;
        }

        @Override public void contraObserved(SkatAi.ContraEvent event) {
            contra = event.level;
            if (event.level == SkatAi.ContraLevel.KONTRA) contraSeat = event.seat;
            inner.contraObserved(event);
        }

        @Override public void startGame(SkatAi.GameStartContext context) { inner.startGame(context); }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            record(context);
            return inner.chooseCard(context);
        }

        /**
         * One example: the seat's evidence in, the truth out.
         *
         * <p>Recorded with a <em>perfect</em> memory. Forgetting is applied by the
         * trainer, by clearing a block and its presence bit, so one generated
         * corpus serves every personality — and so the dropout distribution is a
         * knob to tune rather than a property baked into terabytes of files.
         */
        private void record(SkatAi.DecisionContext context) {
            GameEngine table = engine;
            if (table == null) return;
            GameEngine.Snapshot truth = table.snapshot();
            if (truth.hands == null || truth.hands.size() != 3) return;

            Set<Card> known = new LinkedHashSet<>(context.hand);
            known.addAll(context.derived.playedCards);
            boolean iAmDeclarer = context.game.hasDeclarer()
                    && context.mySeat == context.game.declarer;
            if (iAmDeclarer && myDiscard != null) known.addAll(myDiscard);
            // Rearhand's push is the skat and stays there, so that seat knows two
            // of the buried cards for certain. Asking a model to predict what the
            // player already holds in memory would inflate the score with free
            // answers; forehand's and middlehand's pushes may be passed on again,
            // so those stay something to guess at.
            if (schieben != null && schieben.toSkat()) known.addAll(schieben.pushedOn());

            Set<Card> seen = new LinkedHashSet<>(context.derived.playedCards);
            BeliefEncoding.Evidence evidence = new BeliefEncoding.Evidence(
                    context,
                    context.derived.playedCards,
                    context.derived.voidClasses,
                    iAmDeclarer ? myDiscard : null,
                    highestBids,
                    !highestBids.isEmpty(),
                    BeliefEncoding.trumpsUnaccounted(context.game.contract,
                            new LinkedHashSet<>(context.hand), seen),
                    BeliefEncoding.jacksUnseen(new LinkedHashSet<>(context.hand), seen),
                    schieben, contra, contraSeat);

            float[] features = BeliefEncoding.encode(evidence);
            BeliefEncoding.Labels labels = BeliefEncoding.labels(context,
                    truth.hands, truth.skat, known);

            // A position with nothing left to guess carries no loss and no
            // signal: by its last card a declarer that remembers its own discard
            // knows where all thirty-two are. Writing those rows would pad the
            // corpus with examples the model cannot learn anything from.
            boolean anythingHidden = false;
            for (byte bit : labels.mask()) anythingHidden |= bit != 0;
            if (!anythingHidden) return;

            byte[] trailer = new byte[TRAILER_BYTES + BOARD_BYTES];
            SkatAi.Seat seat = context.mySeat;
            for (int at = 0; at < TRAILER_BYTES; at++) {
                trailer[at] = (byte) (int) populationIndex.getOrDefault(seat, 255);
                seat = seat.next();
            }
            for (int at = 0; at < BOARD_BYTES; at++) {
                trailer[TRAILER_BYTES + at] = (byte) (board >>> (8 * at));
            }
            sink.accept(features, labels, trailer);
        }

        @Override public void cardPlayed(SkatAi.CardPlayedEvent event) { inner.cardPlayed(event); }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            inner.trickCompleted(event);
        }

        @Override public void endGame(SkatAi.GameResult result) { inner.endGame(result); }
        @Override public void close() { inner.close(); }
    }

    /** Where records go. Separate so a test can count them without writing a file. */
    public interface Sink {
        void accept(float[] features, BeliefEncoding.Labels labels, byte[] trailer);
    }

    /** A shard on disk. Thread-safe, because boards are played in parallel. */
    public static final class ShardWriter implements Sink, AutoCloseable {
        private final OutputStream out;
        private final ByteBuffer buffer =
                ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        private long written;

        public ShardWriter(Path path) throws IOException {
            Files.createDirectories(path.toAbsolutePath().getParent());
            this.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
        }

        @Override public synchronized void accept(float[] features,
                                                  BeliefEncoding.Labels labels,
                                                  byte[] trailer) {
            buffer.clear();
            buffer.put(BeliefEncoding.quantise(features));
            buffer.put(labels.target());
            buffer.put(labels.mask());
            buffer.put(trailer);
            try {
                out.write(buffer.array());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot write shard", failure);
            }
            written++;
        }

        public long records() { return written; }

        @Override public void close() throws IOException { out.close(); }
    }

    /** Counts records without keeping them; used by the tests. */
    public static final class CountingSink implements Sink {
        private final List<float[]> features = new ArrayList<>();
        private final List<BeliefEncoding.Labels> labels = new ArrayList<>();

        @Override public synchronized void accept(float[] row, BeliefEncoding.Labels label,
                                                  byte[] trailer) {
            features.add(row);
            labels.add(label);
        }

        public int size() { return features.size(); }
        public float[] features(int at) { return features.get(at); }
        public BeliefEncoding.Labels labels(int at) { return labels.get(at); }
    }
}
