package dev.skatklar.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a hand is worth, and what may legally be said about it.
 *
 * <p>Two things live here, and both are the parts of Skat that beginners get
 * wrong and that most apps either hide or compute incorrectly.
 *
 * <p><b>The bid ladder is generated, not typed out.</b> It is exactly the set of
 * values a game can have: every multiple of a base value that a real
 * multiplier can reach, plus the four fixed Null values. A hand-written list is
 * a list of numbers somebody once believed; this one cannot disagree with the
 * scoring code, because it is derived from it.
 *
 * <p><b>The guaranteed value is a minimum over all possible skats.</b> The two
 * cards in the skat belong to the declarer for the matador count whether or not
 * they are ever picked up, so during the auction a holding does not have a
 * value, it has a range:
 *
 * <ul>
 *   <li>on a <i>with</i> hand the skat can only add matadors, so the face value
 *       is also the floor;</li>
 *   <li>on a <i>without</i> hand it can break the run and take multipliers away.
 *       Holding the jacks of hearts and diamonds and playing clubs is
 *       <i>without 2</i>, game 3, 36 — but with the jack of clubs in the skat it
 *       is <i>with 1</i>, game 2, 24. Bid 30 on that hand and you are overbid
 *       before you have played a card.</li>
 * </ul>
 *
 * <p>Both ends are brute-forced over all 231 two-card skats. It is a few hundred
 * cheap evaluations and it is exact, which is worth more here than being clever.
 *
 * <p>Note that a hand game is <em>not</em> exempt: the skat still counts, so a
 * hand game bid also has a range. What removes the range is knowing all twelve
 * cards — that is, having picked the skat up and discarded.
 */
public final class BidValues {

    /** Every value a game can be worth, ascending. The legal bids are exactly these. */
    public static final List<Integer> LADDER = buildLadder();

    private BidValues() {}

    /**
     * What a declaration is worth for a holding, as a range over the unseen skat.
     *
     * @param matadors    matador count for the face-value case
     * @param with        whether that count is "with" or "without"
     * @param multiplier  the face-value multiplier, matadors and announcements included
     * @param base        base value of the game, or 0 for Null
     * @param exact       true when nothing unseen can change the value: either
     *                    all twelve of the declarer's cards are known, or the
     *                    game is Null
     */
    public record Range(int guaranteed, int expected, int best, boolean exact,
                        boolean with, int matadors, int multiplier, int base) {

        /** True when this game covers {@code bid} no matter what the skat holds. */
        public boolean covers(int bid) {
            return guaranteed >= bid;
        }

        /** True when only a friendly skat covers {@code bid}. */
        public boolean risky(int bid) {
            return guaranteed < bid && best >= bid;
        }

        /** True when no skat makes this game reach {@code bid}. */
        public boolean unreachable(int bid) {
            return best < bid;
        }
    }

    /**
     * Prices {@code declaration} for {@code known} cards.
     *
     * @param known the cards the player holds: ten during the auction, or all
     *              twelve once the skat has been taken and the discard chosen —
     *              in which case the answer is exact
     */
    public static Range evaluate(Declaration declaration, Collection<Card> known) {
        Contract contract = declaration.contract();
        int announced = announcementExtras(declaration);
        if (contract.isNull()) {
            int value = SkatRules.nullValue(declaration.hand(), declaration.ouvert());
            return new Range(value, value, value, true, false, 0, 1, 0);
        }
        int base = SkatRules.baseValue(contract);
        Set<Card> cards = new LinkedHashSet<>(known);
        int faceMatadors = SkatRules.matadorCount(contract, cards);
        // "With" or "without" is decided by one card: the jack of clubs starts
        // the chain in every trump game.
        boolean with = cards.contains(new Card(Card.Suit.CLUBS, Card.Rank.JACK));
        int expected = base * (faceMatadors + 1 + announced);

        if (cards.size() >= 12) {
            return new Range(expected, expected, expected, true, with, faceMatadors,
                    faceMatadors + 1 + announced, base);
        }

        List<Card> unseen = unseen(cards);
        int min = Integer.MAX_VALUE;
        int max = 0;
        ArrayList<Card> twelve = new ArrayList<>(cards.size() + 2);
        for (int i = 0; i < unseen.size(); i++) {
            for (int j = i + 1; j < unseen.size(); j++) {
                twelve.clear();
                twelve.addAll(cards);
                twelve.add(unseen.get(i));
                twelve.add(unseen.get(j));
                int multiplier = SkatRules.matadorCount(contract, twelve) + 1 + announced;
                min = Math.min(min, multiplier);
                max = Math.max(max, multiplier);
            }
        }
        if (min == Integer.MAX_VALUE) {
            min = faceMatadors + 1 + announced;
            max = min;
        }
        return new Range(base * min, expected, base * max, false, with, faceMatadors,
                faceMatadors + 1 + announced, base);
    }

    /** Multipliers bought by the announcement rather than by the cards. */
    public static int announcementExtras(Declaration declaration) {
        if (declaration.contract().isNull()) return 0;
        int extra = 0;
        if (declaration.hand()) extra++;
        if (declaration.schneiderAnnounced()) extra++;
        if (declaration.schwarzAnnounced()) extra++;
        if (declaration.ouvert()) extra++;
        return extra;
    }

    /** The next legal bid above {@code current}, or 0 when the ladder is exhausted. */
    public static int next(int current) {
        for (int value : LADDER) if (value > current) return value;
        return 0;
    }

    /** The bid one step below {@code current}, or 0 below the first rung. */
    public static int previous(int current) {
        int result = 0;
        for (int value : LADDER) {
            if (value >= current) return result;
            result = value;
        }
        return result;
    }

    /** The lowest rung that reaches {@code target}; the first rung when below it. */
    public static int lowestReaching(int target) {
        for (int value : LADDER) if (value >= target) return value;
        return LADDER.get(LADDER.size() - 1);
    }

    /** Ladder position of {@code value}, or -1 when it is not a legal bid. */
    public static int rung(int value) {
        return LADDER.indexOf(value);
    }

    /**
     * The rungs from just above {@code currentBid} up to and including the
     * highest value this declaration can reach. What a ceiling may be set to.
     */
    public static List<Integer> reachable(Range range, int currentBid) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int value : LADDER) {
            if (value <= currentBid) continue;
            if (value > range.best()) break;
            result.add(value);
        }
        return result;
    }

    private static List<Card> unseen(Set<Card> known) {
        ArrayList<Card> result = new ArrayList<>(SkatDeck.CARD_COUNT);
        for (Card card : SkatDeck.ordered()) if (!known.contains(card)) result.add(card);
        return result;
    }

    /**
     * Every value any game can take. A suit game runs to eighteen multipliers —
     * eleven matadors, the game itself, hand, schneider made, schneider
     * announced, schwarz made, schwarz announced and ouvert — while Grand counts
     * only its four jacks and so stops at eleven.
     */
    private static List<Integer> buildLadder() {
        TreeSet<Integer> values = new TreeSet<>();
        for (Contract contract : Contract.TRUMP_GAMES) {
            int base = SkatRules.baseValue(contract);
            int topMatadors = contract == Contract.GRAND ? 4 : 11;
            // + game + hand + schneider (made, announced) + schwarz (made,
            // announced) + ouvert
            int topMultiplier = topMatadors + 1 + 6;
            for (int multiplier = 2; multiplier <= topMultiplier; multiplier++) {
                values.add(base * multiplier);
            }
        }
        for (boolean hand : new boolean[]{false, true}) {
            for (boolean ouvert : new boolean[]{false, true}) {
                values.add(SkatRules.nullValue(hand, ouvert));
            }
        }
        values.headSet(18).clear();
        return List.copyOf(values);
    }
}
