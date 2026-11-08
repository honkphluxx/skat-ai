package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

/**
 * The contract and the discard chosen together, after the skat, from the
 * declarer's own twelve cards.
 *
 * <p>For every trump game that covers the bid on the twelve,
 * {@link DiscardSearch} over worlds dealt from the twenty unseen cards picks
 * the pair to bury and says in how many of them the contract then makes
 * against double-dummy defence; the game kept is the one worth most by the
 * bidder's own reckoning, value times p less twice the value times (1 - p),
 * the first of equals in the order given. Nothing here reads an opponent's
 * hand: the worlds are drawn from what this seat cannot see.
 *
 * <p>Why it exists: the search player prices its ten cards at the deal, for
 * the two likeliest trump games, and announces that intention whatever the
 * skat brings. Measured on the true-deal labels (2026-11-07, 1,747 declared
 * boards, docs/training-plan.md), choosing again after the skat is worth
 * about five points a declared game, four fifths of it a suit game turned
 * into Grand. One choice is shared by the arena's labels
 * (DeclarerChoiceMain) and by {@link SearchAiProvider#withPostSkatContract},
 * so the player measured at the table is the choice measured by the labels.
 */
public final class ContractSearch {

    private ContractSearch() {}

    /** The pick: the game, its two buried cards, and the make chance its search gave it. */
    public record Pick(Contract contract, List<Card> buried, double chance) {}

    /** The bidder's worth of declaring a game of {@code value} made with chance {@code p}. */
    static double worth(int value, double p) {
        return value * p - 2.0 * value * (1 - p);
    }

    /**
     * @param contracts the games that may be announced, in the order ties are broken; Null is skipped
     * @param randomFor the worlds' random source for each contract, by {@link Contract#ordinal()}
     * @return the pick, or null when no game in {@code contracts} covers the bid
     */
    public static Pick choose(List<Card> twelve, int bid, SkatAi.Seat declarer, SkatAi.Seat forehand,
                              Collection<Contract> contracts, int worlds, IntFunction<Random> randomFor) {
        if (twelve.size() != 12) throw new IllegalArgumentException("twelve cards, not " + twelve.size());
        List<Card> unseen = new ArrayList<>(SkatDeck.ordered());
        unseen.removeAll(twelve);
        Pick best = null;
        double bestWorth = Double.NEGATIVE_INFINITY;
        for (Contract c : contracts) {
            if (c.isNull() || c.isRamsch()) continue;
            int value = SkatRules.guaranteedValue(c, twelve);
            if (value < bid) continue;
            DiscardSearch.Choice found = DiscardSearch.choose(c, declarer, twelve, unseen, forehand, worlds, 5, 0L,
                    randomFor.apply(c.ordinal()), Discards.buried(c, twelve));
            if (found.worlds() == 0 || found.buried().size() != 2) continue;
            double p = (double) found.wins() / found.worlds();
            double w = worth(value, p);
            if (w > bestWorth) {
                bestWorth = w;
                best = new Pick(c, List.copyOf(found.buried()), p);
            }
        }
        return best;
    }
}
