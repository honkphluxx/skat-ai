package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game and the discard after the skat, chosen by a small net (after
 * route 1, docs/training-plan.md; trained by arena/python/train_declarer.py).
 *
 * <p>The options are the {@link ContractSearch} candidates without the search:
 * every trump game that covers the bid on the twelve, times the ten pairs
 * drawn from the five cards {@link Discards#ranked} wants least for that game.
 * The net scores each from what the declarer can see -- the ten it keeps and
 * the two it buries, laid out relative to the game (the four jacks by suit,
 * then the trump suit's seven other cards, then each side suit's seven, the
 * side suits in suit order), the game, whether the pair is the heuristic's,
 * the declarer's place, the bid, and each place's highest bid and whether it
 * passed -- and the highest score is played, the first of equals in game
 * order, then pair order. A few multiply-adds an option: microseconds where
 * {@link ContractSearch} spends seconds.
 *
 * <p>The features are spelled out once here and once in train_declarer.py,
 * and {@link #checkParity} holds the two together: the exporter writes, for
 * real boards, every option's inputs and the score Python gave it, and this
 * class rebuilds the features from the inputs and must give the same scores.
 */
public final class LearnedDeclarer {

    /** The games in the net's one-hot order. */
    static final Contract[] GAMES = {Contract.DIAMONDS, Contract.HEARTS, Contract.SPADES, Contract.CLUBS,
            Contract.GRAND};
    static final int FEATURES = 32 + 32 + GAMES.length + 1 + 3 + 1 + 2 * 3;
    private static final int MAGIC = 0x4E444B53;   // "SKDN", little-endian
    private static final int POOL = 5;

    private final int features;
    /** weights[layer][out][in], biases[layer][out]; ReLU between layers, one output. */
    private final float[][][] weights;
    private final float[][] biases;

    public LearnedDeclarer(float[][][] weights, float[][] biases) {
        if (weights.length == 0 || weights.length != biases.length) {
            throw new IllegalArgumentException("one bias a layer, at least one layer");
        }
        this.features = weights[0][0].length;
        if (features != FEATURES) throw new IllegalArgumentException("the net reads " + features + " features, not " + FEATURES);
        for (int l = 0; l < weights.length; l++) {
            int in = l == 0 ? features : weights[l - 1].length;
            for (float[] row : weights[l]) {
                if (row.length != in) throw new IllegalArgumentException("layer " + l + " is not " + in + " wide");
            }
            if (biases[l].length != weights[l].length) throw new IllegalArgumentException("layer " + l + "'s bias");
        }
        if (weights[weights.length - 1].length != 1) throw new IllegalArgumentException("one score, not more");
        this.weights = weights;
        this.biases = biases;
    }

    /** The exporter's declarer.bin: magic, version 1, features, then per layer out, in, weights, bias. */
    public static LearnedDeclarer load(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] header = data.readNBytes(16);
        if (header.length != 16) throw new IOException("not a declarer net: too short");
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt() != MAGIC) throw new IOException("not a declarer net (magic)");
        if (h.getInt() != 1) throw new IOException("a declarer net of another version");
        int features = h.getInt();
        int layers = h.getInt();
        if (features != FEATURES || layers < 1 || layers > 16) {
            throw new IOException("a declarer net of " + features + " features and " + layers + " layers");
        }
        float[][][] w = new float[layers][][];
        float[][] b = new float[layers][];
        for (int l = 0; l < layers; l++) {
            ByteBuffer dims = ByteBuffer.wrap(data.readNBytes(8)).order(ByteOrder.LITTLE_ENDIAN);
            int out = dims.getInt();
            int width = dims.getInt();
            if (out < 1 || out > 4096 || width < 1 || width > 4096) throw new IOException("layer " + l + " dims");
            ByteBuffer body = ByteBuffer.wrap(data.readNBytes(4 * (out * width + out))).order(ByteOrder.LITTLE_ENDIAN);
            if (body.remaining() != 4 * (out * width + out)) throw new IOException("layer " + l + " is cut short");
            w[l] = new float[out][width];
            for (int o = 0; o < out; o++) for (int i = 0; i < width; i++) w[l][o][i] = body.getFloat();
            b[l] = new float[out];
            for (int o = 0; o < out; o++) b[l][o] = body.getFloat();
        }
        if (data.read() != -1) throw new IOException("bytes after the last layer");
        return new LearnedDeclarer(w, b);
    }

    /** The net's score for one option's features. */
    public float score(float[] x) {
        if (x.length != features) throw new IllegalArgumentException(x.length + " features");
        float[] a = x;
        for (int l = 0; l < weights.length; l++) {
            float[] next = new float[weights[l].length];
            for (int o = 0; o < next.length; o++) {
                float s = biases[l][o];
                float[] row = weights[l][o];
                for (int i = 0; i < row.length; i++) s += row[i] * a[i];
                next[o] = l < weights.length - 1 ? Math.max(0f, s) : s;
            }
            a = next;
        }
        return a[0];
    }

    /** Card index -> slot, for {@code game}: the jacks, the trump suit, the side suits in suit order. */
    static int slot(Contract game, Card card) {
        if (card.rank == Card.Rank.JACK) return card.suit.ordinal();
        List<Card.Suit> suits = new ArrayList<>(List.of(Card.Suit.values()));
        if (game.trumpSuit != null) {
            suits.remove(game.trumpSuit);
            suits.add(0, game.trumpSuit);
        }
        int rank = card.rank.ordinal();
        int within = rank < Card.Rank.JACK.ordinal() ? rank : rank - 1;   // 7 8 9 10 Q K A -> 0..6
        return 4 + suits.indexOf(card.suit) * 7 + within;
    }

    /** The place of {@code seat} in {@code round}: 0 forehand, 1 middlehand, 2 rearhand. */
    static int place(SkatAi.RoundPosition round, SkatAi.Seat seat) {
        return seat == round.forehand ? 0 : seat == round.middlehand ? 1 : 2;
    }

    /**
     * One option's features.
     *
     * @param highest  each place's highest bid (0 for none), by {@link #place}
     * @param passed   whether each place passed, by {@link #place}
     */
    static float[] features(Collection<Card> kept, Collection<Card> buried, Contract game, boolean heuristic,
                            int place, int bid, int[] highest, boolean[] passed) {
        float[] x = new float[FEATURES];
        for (Card c : kept) x[slot(game, c)] = 1f;
        for (Card c : buried) x[32 + slot(game, c)] = 1f;
        int at = 64;
        for (int g = 0; g < GAMES.length; g++) if (GAMES[g] == game) x[at + g] = 1f;
        at += GAMES.length;
        x[at++] = heuristic ? 1f : 0f;
        x[at + place] = 1f;
        at += 3;
        x[at++] = bid / 100f;
        for (int p = 0; p < 3; p++) {
            x[at + 2 * p] = highest[p] / 100f;
            x[at + 2 * p + 1] = passed[p] ? 1f : 0f;
        }
        return x;
    }

    /** The pick: the game, its two buried cards, and the net's score. */
    public record Pick(Contract contract, List<Card> buried, float score) {}

    /**
     * The option this net scores highest, or null when no game in {@code games}
     * covers the bid.
     *
     * @param highestBids each seat's highest bid heard (a pass is not a bid)
     * @param passedSeats the seats heard to pass
     */
    public Pick choose(List<Card> twelve, int bid, SkatAi.Seat declarer, SkatAi.RoundPosition round,
                       Collection<Contract> games, Map<SkatAi.Seat, Integer> highestBids,
                       Set<SkatAi.Seat> passedSeats) {
        if (twelve.size() != 12) throw new IllegalArgumentException("twelve cards, not " + twelve.size());
        int[] highest = new int[3];
        boolean[] passed = new boolean[3];
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            int p = place(round, seat);
            highest[p] = highestBids.getOrDefault(seat, 0);
            passed[p] = passedSeats.contains(seat);
        }
        int myPlace = place(round, declarer);
        Pick best = null;
        for (Contract game : GAMES) {
            if (!games.contains(game) || SkatRules.guaranteedValue(game, twelve) < bid) continue;
            List<Card> ranked = Discards.ranked(game, twelve);
            List<Card> pool = ranked.subList(12 - POOL, 12);
            List<Card> heuristic = Discards.buried(game, twelve);
            for (int i = 0; i < POOL; i++) {
                for (int j = i + 1; j < POOL; j++) {
                    List<Card> buried = List.of(pool.get(i), pool.get(j));
                    List<Card> kept = new ArrayList<>(twelve);
                    kept.removeAll(buried);
                    boolean isHeuristic = heuristic.containsAll(buried);
                    float s = score(features(kept, buried, game, isHeuristic, myPlace, bid, highest, passed));
                    if (best == null || s > best.score()) best = new Pick(game, buried, s);
                }
            }
        }
        return best;
    }

    /**
     * Checks this net against the exporter's parity file: one line an option,
     * {@code ten(10) skat(2) place bid h0 p0 h1 p1 h2 p2 game d1 d2 heuristic score},
     * cards as BeliefEncoding indices, places 0-2. The features are rebuilt here
     * from the inputs and every score must agree within {@code tolerance}. The
     * heuristic flag is taken from the line, not recomputed: {@link Discards#ranked}
     * breaks ties by the order of the twelve, which the label files do not keep
     * (they sort the ten), while the engine hands the declarer -- here and when
     * the labels were made -- its dealt ten and then the skat.
     *
     * @return the number of options checked
     * @throws IOException on the first that does not agree
     */
    public int checkParity(BufferedReader parity, float tolerance) throws IOException {
        int checked = 0;
        String line;
        while ((line = parity.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length != 25) throw new IOException("a parity line of " + f.length + " fields: " + line);
            int at = 0;
            List<Card> twelve = new ArrayList<>(12);
            for (int i = 0; i < 12; i++) twelve.add(card(Integer.parseInt(f[at++])));
            int place = Integer.parseInt(f[at++]);
            int bid = Integer.parseInt(f[at++]);
            int[] highest = new int[3];
            boolean[] passed = new boolean[3];
            for (int p = 0; p < 3; p++) {
                highest[p] = Integer.parseInt(f[at++]);
                passed[p] = Integer.parseInt(f[at++]) != 0;
            }
            Contract game = Contract.valueOf(f[at++]);
            List<Card> buried = List.of(card(Integer.parseInt(f[at++])), card(Integer.parseInt(f[at++])));
            boolean heuristic = Integer.parseInt(f[at++]) != 0;
            float expected = Float.parseFloat(f[at]);
            List<Card> kept = new ArrayList<>(twelve);
            kept.removeAll(buried);
            float got = score(features(kept, buried, game, heuristic, place, bid, highest, passed));
            if (Math.abs(got - expected) > tolerance * Math.max(1f, Math.abs(expected))) {
                throw new IOException("score " + got + ", the exporter's " + expected + ": " + line);
            }
            checked++;
        }
        return checked;
    }

    private static Card card(int index) {
        return new Card(Card.Suit.values()[index / 8], Card.Rank.values()[index % 8]);
    }
}
