package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import java.util.ArrayList;
import java.util.List;

/**
 * The classic discard, as a plain heuristic.
 *
 * <p>In a trump game: keep every trump and every ace; bury the points of the
 * shortest side suit, where the defence cannot take them. In Null the same
 * question has the opposite answer -- bury the two highest cards, because the
 * contract is not to win a trick. Both are what a club player does without
 * thinking, and good enough to stand in for the real decision wherever the real
 * decision is not what is being measured.
 *
 * <p>Two callers need exactly this and would otherwise each grow their own copy:
 * the hand evaluator, which has to know what a hand looks like <em>after</em> the
 * exchange before it can say whether the contract makes, and the arena's contract
 * oracle, which has to price a board the same way. Solving all 66 discards would
 * answer a hand-evaluation question that neither of them is asking.
 */
public final class Discards {

    private Discards() {}

    /** The ten cards to keep out of twelve, best first. */
    public static List<Card> keepBestTen(Contract contract, List<Card> twelve) {
        List<Card> sorted = new ArrayList<>(twelve);
        sorted.sort((left, right) -> Integer.compare(
                keepScore(contract, right, twelve), keepScore(contract, left, twelve)));
        return new ArrayList<>(sorted.subList(0, 10));
    }

    /** The two cards this heuristic buries, and therefore the points it banks. */
    public static List<Card> buried(Contract contract, List<Card> twelve) {
        List<Card> sorted = new ArrayList<>(twelve);
        sorted.sort((left, right) -> Integer.compare(
                keepScore(contract, right, twelve), keepScore(contract, left, twelve)));
        return new ArrayList<>(sorted.subList(10, 12));
    }

    private static int keepScore(Contract contract, Card card, List<Card> holding) {
        // Null inverts everything. There is no trump, points are irrelevant, and
        // the cards worth keeping are the ones that cannot be forced to win a
        // trick -- so the two highest go, and a long suit is safer to hold than a
        // short one because there is more below to duck with.
        if (contract.isNull()) {
            int suitLength = 0;
            for (Card other : holding) if (other.suit == card.suit) suitLength++;
            return 1_000 - SkatRules.nullStrength(card.rank) * 100 + suitLength;
        }
        if (contract.isTrump(card)) return 10_000 + SkatRules.cardPoints(card);
        if (card.rank == Card.Rank.ACE) return 9_000;
        int suitLength = 0;
        for (Card other : holding) {
            if (!contract.isTrump(other) && other.suit == card.suit) suitLength++;
        }
        return suitLength * 100 - SkatRules.cardPoints(card) * 3 - card.rank.ordinal();
    }
}
