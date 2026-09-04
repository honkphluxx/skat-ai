package dev.skatklar.demo.ramsch;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * How to play a Ramsch when nothing cleverer is available.
 *
 * <p>A deliberate placeholder, and a package of its own so that it is obvious
 * what has not been built yet. Every strong player in this project searches, and
 * none of that machinery applies here: the double-dummy solver, the world
 * sampler and the search player all ask "does the declarer reach 61", a question
 * a Ramsch does not have. Three players with three separate objectives need a
 * maxn search, and until that exists this is what the seats play.
 *
 * <p>The heuristic is the one every beginner is taught, which is worth saying
 * because it is also most of the game:
 *
 * <ul>
 *   <li>take no trick that has anything in it;
 *   <li>when a trick is going to somebody else, put your dearest card on it —
 *       points you are not going to collect are points somebody else must;
 *   <li>when you cannot get out of winning, win as cheaply as you can;
 *   <li>lead your rubbish, and keep the cards that cannot be forced on you.
 * </ul>
 *
 * <p>What it does not do: play for a durchmarsch, count what the others have
 * shed, or notice that it is one trick away from a jungfrau worth protecting.
 * Those are the reasons it will be replaced rather than tuned.
 */
public final class RamschPolicy {

    private RamschPolicy() {}

    /** The card this policy plays, from the seat's own view of the table. */
    public static Card chooseCard(SkatAi.DecisionContext context) {
        List<Card> trickSoFar = new ArrayList<>(3);
        for (SkatAi.PlayedCard play : context.currentTrick.plays) trickSoFar.add(play.card);
        return chooseCard(context.legalCards, trickSoFar);
    }

    /**
     * The same decision from the bare position.
     *
     * <p>Split out because a simulator has no {@link SkatAi.DecisionContext} and
     * building one to ask a question this simple would be most of the work —
     * {@link RamschEvaluator} plays thousands of these out to price a hand.
     */
    public static Card chooseCard(Collection<Card> legalCards, List<Card> trickSoFar) {
        List<Card> legal = new ArrayList<>(legalCards);
        if (legal.isEmpty()) throw new IllegalStateException("No legal card");
        if (legal.size() == 1) return legal.get(0);

        if (trickSoFar.isEmpty()) return lead(legal);

        Card led = trickSoFar.get(0);
        Card winning = led;
        for (Card played : trickSoFar) {
            if (SkatRules.beats(Contract.RAMSCH, led, winning, played)) winning = played;
        }

        List<Card> safe = new ArrayList<>();
        for (Card card : legal) {
            if (!SkatRules.beats(Contract.RAMSCH, led, winning, card)) safe.add(card);
        }
        // Nothing safe means the trick is ours whatever we do, so it costs as
        // little as we can make it cost.
        if (safe.isEmpty()) return cheapest(legal);
        return dearest(safe);
    }

    /**
     * Leading. The cheapest card there is, because the lead is the one card
     * nobody can make us play: whatever we put down we may have to win with.
     */
    private static Card lead(List<Card> legal) {
        return cheapest(legal);
    }

    /** Fewest card points, and among equals the one with least power left. */
    private static Card cheapest(List<Card> cards) {
        return cards.stream().min(byCost).orElse(cards.get(0));
    }

    /**
     * Most card points, and among equals the most powerful — the card most
     * likely to have won a trick we did not want later on.
     */
    private static Card dearest(List<Card> cards) {
        return cards.stream().max(byCost).orElse(cards.get(0));
    }

    /**
     * Card points first, then anything deterministic. The tie-break has no
     * opinion on purpose: among cards worth the same it makes no difference
     * here, and a comparator that pretended otherwise would be the beginning of
     * exactly the hand-tuning this class is meant to be replaced instead of.
     */
    private static final Comparator<Card> byCost =
            Comparator.<Card>comparingInt(SkatRules::cardPoints)
                    .thenComparing(Card::toString);
}
