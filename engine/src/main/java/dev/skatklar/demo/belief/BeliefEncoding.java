package dev.skatklar.demo.belief;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Version 2 of the feature encoding the belief model reads.
 *
 * <p>This class is one half of a contract. The other half is the Python trainer,
 * and the single most common way a project like this fails is that the two drift
 * apart — a model that scores 97% in training and plays like a random bot in the
 * app. So the layout is declared once, in {@link #FIELDS}, both sides read it,
 * and a fixtures file pins actual vectors so a drift shows up as a failing test
 * rather than as a mysteriously weak player.
 *
 * <h2>Three properties that are decisions, not details</h2>
 *
 * <p><b>Everything is relative to the observer.</b> Seats are encoded as "me",
 * "the opponent on my left" and "the one on my right", never as absolute seats.
 * A model trained on absolute seats has to learn the same fact three times and
 * gets a third of the data for each.
 *
 * <p><b>Every evidence block carries a presence bit.</b> A player with an
 * imperfect memory does not merely have <em>fewer</em> observations, it has
 * <em>missing</em> ones — and the difference matters enormously. Zeroing the
 * bidding block without a mask tells the model that nobody bid, which is a false
 * fact rather than an absent one, and a model told that nobody bid will place
 * the jacks somewhere they are not. Confidently wrong is worse than uncertain.
 * The presence bit is what turns the first into the second, and the trainer must
 * drop blocks with the same distribution that play produces, or the model will
 * never have seen the state it is asked to handle.
 *
 * <p><b>Counts are encoded separately from identities.</b> A Skat player does
 * not remember thirty individual cards; they remember that two trumps are still
 * out and that the club jack has not appeared. Those are different memories with
 * different failure modes — the count survives long after the identities blur —
 * so they are separate inputs here and separately forgettable in
 * {@link dev.skatklar.demo.search.Personality}. Giving the model only the card
 * identities would force it to re-derive a count that the player it is modelling
 * knows directly, and would make "I lost track of which, but three are out"
 * inexpressible.
 */
public final class BeliefEncoding {

    /**
     * Bumped whenever the layout changes. The trainer refuses a mismatch.
     *
     * <p>v2 (2026-08-19) made room for the rules the canon added. A Ramsch has a
     * contract slot and a declarer slot that says "nobody"; the Schieben is
     * recorded, because it is the strongest evidence anybody gets before a card
     * falls; and Kontra/Re are inputs, because a defender who doubles has said
     * something about their hand.
     */
    public static final int VERSION = 2;

    /**
     * Features are stored as bytes, scaled by this.
     *
     * <p>Nearly every input is a bit, and the handful that are not are fractions
     * with at most a dozen distinct values -- a count over eleven, a bid over a
     * hundred. Four bytes a feature would make a night's data four times larger
     * for precision nothing uses. One byte gives a resolution of 1/255, which is
     * finer than any input here varies, and turns a 7 GB corpus into under 2 GB.
     */
    public static final int SCALE = 255;

    /**
     * Diagnostic bytes appended to each stored record: which player sat in each
     * seat, relative to the observer.
     *
     * <p>Part of the file format and never part of the input. The trainer reads
     * them to answer "how much would knowing the opponent's style be worth?" and
     * must not feed them to a shipped model: at play time the opponent is a human
     * and no such label exists.
     */
    public static final int TRAILER_BYTES = 3;

    /**
     * Four more diagnostic bytes: which board the record came from.
     *
     * <p>Also never an input, and there so that a validation split can be made by
     * board rather than by record. One board yields about thirty records off the
     * same thirty-two cards, so splitting by record puts near-copies of training
     * rows into validation and reports a score that says nothing about a deal the
     * model has not seen.
     */
    public static final int BOARD_BYTES = 4;

    /** Quantises a vector for storage; the trainer divides by {@link #SCALE}. */
    public static byte[] quantise(float[] features) {
        byte[] bytes = new byte[features.length];
        for (int at = 0; at < features.length; at++) {
            int value = Math.round(Math.max(0, Math.min(1, features[at])) * SCALE);
            bytes[at] = (byte) value;
        }
        return bytes;
    }

    /** The inverse, for tests and for anything reading a shard back in Java. */
    public static float[] dequantise(byte[] bytes) {
        float[] features = new float[bytes.length];
        for (int at = 0; at < bytes.length; at++) {
            features[at] = (bytes[at] & 0xFF) / (float) SCALE;
        }
        return features;
    }

    /** One field of the input vector: a name, an offset and a width. */
    public record Field(String name, int offset, int width, String meaning) {}

    private static final List<Field> LAYOUT = new ArrayList<>();

    private static int cursor;

    private static Field add(String name, int width, String meaning) {
        Field field = new Field(name, cursor, width, meaning);
        LAYOUT.add(field);
        cursor += width;
        return field;
    }

    // ------------------------------------------------------------------ layout

    public static final Field MY_HAND = add("my_hand", 32,
            "cards I hold, one bit per card in suit-major rank-minor order");
    public static final Field PLAYED_BY_ME = add("played_by_me", 32,
            "cards I have played and still remember playing");
    public static final Field PLAYED_BY_LEFT = add("played_by_left", 32,
            "cards my left-hand opponent played, as far as I remember them");
    public static final Field PLAYED_BY_RIGHT = add("played_by_right", 32,
            "cards my right-hand opponent played, as far as I remember them");
    public static final Field PLAYED_PRESENT = add("played_present", 1,
            "1 when the trick history is remembered at all");
    public static final Field CURRENT_TRICK = add("current_trick", 32,
            "cards face up in the trick being played; never forgotten");
    public static final Field TRICK_LEADER = add("trick_leader", 3,
            "who led the current trick, relative: me, left, right");
    public static final Field MY_DISCARD = add("my_discard", 32,
            "the two cards I buried, when I am the declarer and remember them");
    public static final Field MY_DISCARD_PRESENT = add("my_discard_present", 1,
            "1 when the discard above is mine and known");
    public static final Field PUSHED_ON = add("pushed_on", 32,
            "the two cards I pushed on in a Schieberamsch");
    public static final Field PUSHED_TO_SKAT = add("pushed_to_skat", 1,
            "1 when I was rearhand, so what I pushed became the skat and I know it");
    public static final Field RECEIVED = add("received", 32,
            "the two cards handed to me, which my right-hand neighbour no longer has");
    public static final Field RECEIVED_FROM_SKAT = add("received_from_skat", 1,
            "1 when I was forehand, so what I received was the dealt skat and says nothing");
    public static final Field SCHIEBEN_PRESENT = add("schieben_present", 1,
            "1 in a Ramsch whose Schieben this seat remembers");
    public static final Field CONTRACT = add("contract", 7,
            "diamonds, hearts, spades, clubs, grand, null, ramsch");
    public static final Field DECLARER = add("declarer", 4,
            "who is playing alone, relative: me, left, right, nobody -- the last is a Ramsch");
    public static final Field BID_VALUE = add("bid_value", 1,
            "the winning bid divided by 100");
    public static final Field BIDS_BY_SEAT = add("bids_by_seat", 3,
            "the highest bid each seat made, relative, divided by 100");
    public static final Field BIDDING_PRESENT = add("bidding_present", 1,
            "1 when the auction is remembered; the first thing a club player drops");
    public static final Field CONTRA_LEVEL = add("contra_level", 2,
            "Kontra said, and Re said -- a defender doubling has claimed something");
    public static final Field CONTRA_SEAT = add("contra_seat", 3,
            "who said Kontra, relative: me, left, right");
    public static final Field VOID_LEFT = add("void_left", 5,
            "classes my left-hand opponent has shown out of: trump, then four suits");
    public static final Field VOID_RIGHT = add("void_right", 5,
            "the same for my right-hand opponent");
    public static final Field TRUMPS_OUT = add("trumps_out", 1,
            "trumps still unaccounted for, divided by 11 -- the count a player keeps");
    public static final Field TRUMPS_MINE = add("trumps_mine", 1,
            "trumps in my own hand, divided by 11");
    public static final Field JACKS_OUT = add("jacks_out", 4,
            "one bit per jack still unseen, clubs to diamonds -- counted hardest of all");
    public static final Field COUNTS_PRESENT = add("counts_present", 1,
            "1 when the trump and jack counters are being kept");
    public static final Field CARDS_LEFT = add("cards_left", 3,
            "cards still held by me, left and right, each divided by 10");
    public static final Field TRICK_NUMBER = add("trick_number", 1,
            "tricks completed, divided by 10");

    /** The full input width. */
    public static final int SIZE = cursor;

    /** The layout, in order. Written to the spec file the trainer reads. */
    public static final List<Field> FIELDS = Collections.unmodifiableList(LAYOUT);

    /**
     * The label: for every card, where it actually is, from the observer's seat.
     *
     * <p>Only the cards the observer cannot see carry a loss; the mask says
     * which. Three classes rather than two, because the skat is a real place a
     * card can be and a model that cannot say "it is buried" will put buried
     * cards into hands instead.
     */
    public static final int CLASS_LEFT = 0;
    public static final int CLASS_RIGHT = 1;
    public static final int CLASS_SKAT = 2;
    public static final int CLASSES = 3;

    private BeliefEncoding() {}

    /**
     * One seat's leg of the Schieben, from its own side.
     *
     * <p>Worth its own record because the two halves mean different things.
     * {@code pushedOn} is a positive fact about somebody else's hand — and only a
     * <em>certain</em> one for rearhand, whose push becomes the skat and stays
     * there; forehand's and middlehand's may be pushed onward again.
     * {@code received} is a fact about the neighbour on the right: whatever those
     * cards are, that seat has them no longer.
     */
    public record Schieben(List<Card> pushedOn, boolean toSkat,
                           List<Card> received, boolean fromSkat) {}

    /** What the observer knows, and is willing to admit it knows. */
    public record Evidence(SkatAi.DecisionContext context,
                           Set<Card> rememberedPlays,
                           Map<SkatAi.Seat, Set<SkatAi.FollowClass>> rememberedVoids,
                           List<Card> knownDiscard,
                           Map<SkatAi.Seat, Integer> highestBids,
                           boolean biddingRemembered,
                           Integer trumpsUnaccounted,
                           Set<Card> jacksUnseen,
                           Schieben schieben,
                           SkatAi.ContraLevel contra,
                           SkatAi.Seat contraSeat) {

        /** A declared game with nothing doubled: no Schieben, no Kontra. */
        public Evidence(SkatAi.DecisionContext context, Set<Card> rememberedPlays,
                        Map<SkatAi.Seat, Set<SkatAi.FollowClass>> rememberedVoids,
                        List<Card> knownDiscard, Map<SkatAi.Seat, Integer> highestBids,
                        boolean biddingRemembered, Integer trumpsUnaccounted,
                        Set<Card> jacksUnseen) {
            this(context, rememberedPlays, rememberedVoids, knownDiscard, highestBids,
                    biddingRemembered, trumpsUnaccounted, jacksUnseen,
                    null, SkatAi.ContraLevel.NONE, null);
        }
    }

    /**
     * Encodes one decision point into {@link #SIZE} floats.
     *
     * <p>Nothing here reads a hidden card. The features are exactly what the seat
     * has been shown, filtered by what it still remembers, which is what makes a
     * model trained on them usable by an honest player.
     */
    public static float[] encode(Evidence evidence) {
        SkatAi.DecisionContext context = evidence.context();
        SkatAi.Seat me = context.mySeat;
        SkatAi.Seat left = me.next();
        SkatAi.Seat right = left.next();
        float[] out = new float[SIZE];

        for (Card card : context.hand) out[MY_HAND.offset() + index(card)] = 1;

        boolean anyHistory = false;
        for (SkatAi.CompletedTrick trick : context.history.completedTricks) {
            for (SkatAi.PlayedCard play : trick.plays) {
                if (!evidence.rememberedPlays().contains(play.card)) continue;
                anyHistory = true;
                Field field = play.seat == me ? PLAYED_BY_ME
                        : play.seat == left ? PLAYED_BY_LEFT : PLAYED_BY_RIGHT;
                out[field.offset() + index(play.card)] = 1;
            }
        }
        out[PLAYED_PRESENT.offset()] = anyHistory ? 1 : 0;

        for (SkatAi.PlayedCard play : context.currentTrick.plays) {
            out[CURRENT_TRICK.offset() + index(play.card)] = 1;
        }
        out[TRICK_LEADER.offset() + relative(me, context.currentTrick.leader)] = 1;

        if (evidence.knownDiscard() != null && !evidence.knownDiscard().isEmpty()) {
            for (Card card : evidence.knownDiscard()) out[MY_DISCARD.offset() + index(card)] = 1;
            out[MY_DISCARD_PRESENT.offset()] = 1;
        }

        if (evidence.schieben() != null) {
            for (Card card : evidence.schieben().pushedOn()) {
                out[PUSHED_ON.offset() + index(card)] = 1;
            }
            for (Card card : evidence.schieben().received()) {
                out[RECEIVED.offset() + index(card)] = 1;
            }
            out[PUSHED_TO_SKAT.offset()] = evidence.schieben().toSkat() ? 1 : 0;
            out[RECEIVED_FROM_SKAT.offset()] = evidence.schieben().fromSkat() ? 1 : 0;
            out[SCHIEBEN_PRESENT.offset()] = 1;
        }

        out[CONTRACT.offset() + context.game.contract.ordinal()] = 1;
        // A Ramsch has nobody in the declarer slot, which is a fourth position
        // rather than an all-zero block: an absent one-hot and a forgotten one
        // would otherwise look identical to the model.
        out[DECLARER.offset() + (context.game.hasDeclarer()
                ? relative(me, context.game.declarer) : NO_DECLARER)] = 1;
        if (evidence.contra() != null && evidence.contra() != SkatAi.ContraLevel.NONE) {
            out[CONTRA_LEVEL.offset()] = 1;
            if (evidence.contra() == SkatAi.ContraLevel.RE) out[CONTRA_LEVEL.offset() + 1] = 1;
            if (evidence.contraSeat() != null) {
                out[CONTRA_SEAT.offset() + relative(me, evidence.contraSeat())] = 1;
            }
        }
        out[BID_VALUE.offset()] = context.game.bidValue / 100f;
        if (evidence.biddingRemembered()) {
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                int bid = evidence.highestBids().getOrDefault(seat, 0);
                out[BIDS_BY_SEAT.offset() + relative(me, seat)] = bid / 100f;
            }
            out[BIDDING_PRESENT.offset()] = 1;
        }

        writeVoids(out, VOID_LEFT, evidence.rememberedVoids().get(left));
        writeVoids(out, VOID_RIGHT, evidence.rememberedVoids().get(right));

        if (evidence.trumpsUnaccounted() != null) {
            out[TRUMPS_OUT.offset()] = evidence.trumpsUnaccounted() / 11f;
            int mine = 0;
            for (Card card : context.hand) if (isTrump(context.game.contract, card)) mine++;
            out[TRUMPS_MINE.offset()] = mine / 11f;
            int at = 0;
            for (Card.Suit suit : new Card.Suit[] {Card.Suit.CLUBS, Card.Suit.SPADES,
                    Card.Suit.HEARTS, Card.Suit.DIAMONDS}) {
                Card jack = new Card(suit, Card.Rank.JACK);
                out[JACKS_OUT.offset() + at++] = evidence.jacksUnseen().contains(jack) ? 1 : 0;
            }
            out[COUNTS_PRESENT.offset()] = 1;
        }

        out[CARDS_LEFT.offset()] = context.hand.size() / 10f;
        out[CARDS_LEFT.offset() + 1] = remaining(context, left) / 10f;
        out[CARDS_LEFT.offset() + 2] = remaining(context, right) / 10f;
        out[TRICK_NUMBER.offset()] = context.history.completedTricks.size() / 10f;
        return out;
    }

    /**
     * Where every card actually is, and which of those the observer cannot see.
     *
     * <p>The labels come from the engine's own view of the deal, so they are
     * exact and free: every game the arena plays is a fully labelled example, and
     * no archive is needed to produce them.
     *
     * @param hands the true hands by seat ordinal
     * @param skat  the two buried cards
     * @return labels in {@link #CLASS_LEFT}/{@link #CLASS_RIGHT}/{@link #CLASS_SKAT},
     *         and a mask that is 1 exactly where the observer is guessing
     */
    public static Labels labels(SkatAi.DecisionContext context, List<List<Card>> hands,
                                List<Card> skat, Set<Card> knownToObserver) {
        SkatAi.Seat me = context.mySeat;
        SkatAi.Seat left = me.next();
        byte[] target = new byte[32];
        byte[] mask = new byte[32];
        for (Card card : SkatDeck.ordered()) {
            int at = index(card);
            if (knownToObserver.contains(card)) continue;
            if (hands.get(left.ordinal()).contains(card)) target[at] = CLASS_LEFT;
            else if (skat.contains(card)) target[at] = CLASS_SKAT;
            else if (hands.get(left.next().ordinal()).contains(card)) target[at] = CLASS_RIGHT;
            else continue;   // already played, or in my own hand: nothing to guess
            mask[at] = 1;
        }
        return new Labels(target, mask);
    }

    /** One training target: a class per card, and where the loss applies. */
    public record Labels(byte[] target, byte[] mask) {}

    /** Stable 0..31 index, the same one the solver uses. */
    public static int index(Card card) {
        return card.suit.ordinal() * 8 + card.rank.ordinal();
    }

    /** The Ramsch slot in {@link #DECLARER}: nobody is playing alone. */
    public static final int NO_DECLARER = 3;

    /** 0 for me, 1 for my left-hand opponent, 2 for my right-hand one. */
    public static int relative(SkatAi.Seat me, SkatAi.Seat other) {
        return (other.ordinal() - me.ordinal() + 3) % 3;
    }

    private static void writeVoids(float[] out, Field field, Set<SkatAi.FollowClass> voids) {
        if (voids == null) return;
        for (SkatAi.FollowClass followClass : voids) {
            out[field.offset() + (followClass.trump ? 0 : followClass.suit.ordinal() + 1)] = 1;
        }
    }

    private static boolean isTrump(Contract contract, Card card) {
        return !contract.isNull() && contract.isTrump(card);
    }

    private static int remaining(SkatAi.DecisionContext context, SkatAi.Seat seat) {
        int played = 0;
        for (SkatAi.CompletedTrick trick : context.history.completedTricks) {
            for (SkatAi.PlayedCard play : trick.plays) if (play.seat == seat) played++;
        }
        for (SkatAi.PlayedCard play : context.currentTrick.plays) if (play.seat == seat) played++;
        return 10 - played;
    }

    /** The trumps nobody has seen yet: not in my hand, not in the cards I remember. */
    public static int trumpsUnaccounted(Contract contract, Set<Card> myHand, Set<Card> seen) {
        int count = 0;
        for (Card card : SkatDeck.ordered()) {
            if (!isTrump(contract, card)) continue;
            if (myHand.contains(card) || seen.contains(card)) continue;
            count++;
        }
        return count;
    }

    /** The jacks nobody has seen yet, which is the count players keep hardest. */
    public static Set<Card> jacksUnseen(Set<Card> myHand, Set<Card> seen) {
        Set<Card> unseen = new LinkedHashSet<>();
        for (Card.Suit suit : Card.Suit.values()) {
            Card jack = new Card(suit, Card.Rank.JACK);
            if (!myHand.contains(jack) && !seen.contains(jack)) unseen.add(jack);
        }
        return unseen;
    }

    /** The layout as JSON, for the trainer to read and check against. */
    public static String specificationJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"version\": ").append(VERSION)
                .append(",\n  \"size\": ").append(SIZE)
                .append(",\n  \"classes\": ").append(CLASSES)
                .append(",\n  \"storage\": \"uint8\"")
                .append(",\n  \"scale\": ").append(SCALE)
                .append(",\n  \"record_bytes\": ")
                .append(SIZE + 64 + TRAILER_BYTES + BOARD_BYTES)
                .append(",\n  \"record_layout\": \"")
                .append(SIZE).append(" feature bytes, 32 label bytes, 32 mask bytes, ")
                .append(TRAILER_BYTES).append(" diagnostic bytes that are never inputs\"")
                .append(",\n  \"fields\": [\n");
        for (int at = 0; at < FIELDS.size(); at++) {
            Field field = FIELDS.get(at);
            json.append("    {\"name\": \"").append(field.name())
                    .append("\", \"offset\": ").append(field.offset())
                    .append(", \"width\": ").append(field.width())
                    .append(", \"meaning\": \"").append(field.meaning()).append("\"}")
                    .append(at + 1 < FIELDS.size() ? "," : "").append("\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }
}
