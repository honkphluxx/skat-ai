package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Draws complete deals consistent with everything one seat has legally observed.
 *
 * <p>This is step one of the three-step recipe every strong trick-game program
 * uses: sample worlds, solve each, aggregate. It is deliberately the <em>uniform</em>
 * sampler — every arrangement of the unseen cards that does not contradict an
 * observation is equally likely — because that is the honest baseline the learned
 * belief model of Phase 4 has to improve on, in tournament points rather than in
 * log-loss.
 *
 * <p>What it does respect, because these are facts rather than inferences:
 *
 * <ul>
 *   <li>the observer's own hand and every card already played;</li>
 *   <li>how many cards each seat still holds;</li>
 *   <li><b>void classes</b> — a seat that failed to follow a suit cannot hold a
 *       card of it. This is by far the strongest hard constraint available, and
 *       it costs nothing to enforce;</li>
 *   <li>the skat, which is two unseen cards for anyone who did not discard them,
 *       and known exactly to the declarer that did.</li>
 * </ul>
 *
 * <p>What it deliberately ignores: the bidding. Who bid how high is the single
 * strongest <em>soft</em> signal about where the jacks are, and using it means
 * weighting arrangements rather than filtering them. That is the belief model,
 * and mixing a half-hearted version of it into the baseline would make the two
 * impossible to compare.
 */
public final class WorldSampler {

    /** How many times to retry a draw that paints itself into a corner. */
    private static final int MAX_ATTEMPTS = 40;

    private final Contract contract;
    private final SkatAi.Seat observer;
    private final List<Card> myCards;
    private final List<Card> unknown;
    private final int[] needed = new int[3];
    private final int skatSlots;
    private final List<Card> knownSkat;
    private final Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids;
    /** Null for the uniform sampler, which is what every caller gets by default. */
    private Weights weights;

    private WorldSampler(Contract contract, SkatAi.Seat observer, List<Card> myCards,
                         List<Card> unknown, int[] needed, int skatSlots, List<Card> knownSkat,
                         Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids) {
        this.contract = contract;
        this.observer = observer;
        this.myCards = myCards;
        this.unknown = unknown;
        System.arraycopy(needed, 0, this.needed, 0, 3);
        this.skatSlots = skatSlots;
        this.knownSkat = knownSkat;
        this.voids = voids;
    }

    /**
     * Builds a sampler for the position described by {@code context}.
     *
     * @param knownSkat the two cards the declarer buried, when the observer is the
     *                  declarer and remembers them; {@code null} otherwise, in
     *                  which case the skat is sampled like any other unseen card
     */
    public static WorldSampler forDecision(SkatAi.DecisionContext context, List<Card> knownSkat) {
        return forDecision(context, knownSkat, context.derived.playedCards,
                context.derived.voidClasses);
    }

    /**
     * The same, for a player that does not remember everything it saw.
     *
     * <p>A forgotten card is not merely unaccounted for: it goes back into the
     * pool of cards that might be in an opponent's hand, so the player will
     * happily reason about a world in which an ace it has already seen fall is
     * still out there. That is the point. It produces the mistakes a human makes
     * when the count slips -- leading into a suit somebody showed out of, holding
     * a trump for a card that is long gone -- rather than the arbitrary ones a
     * random blunder produces.
     *
     * <p>Because more cards then compete for the same number of places, some
     * genuinely unseen cards are left out of each world. The world stays a legal
     * deal; it is simply not this one.
     *
     * @param remembered     the played cards this player still knows about; cards
     *                       lying in the current trick must always be included,
     *                       since they are face up on the table
     * @param enforcedVoids  the voids it still knows about
     */
    public static WorldSampler forDecision(SkatAi.DecisionContext context, List<Card> knownSkat,
                                           Set<Card> remembered,
                                           Map<SkatAi.Seat, Set<SkatAi.FollowClass>> enforcedVoids) {
        Contract contract = context.game.contract;
        SkatAi.Seat observer = context.mySeat;

        List<Card> mine = new ArrayList<>(context.hand);
        Set<Card> accountedFor = new LinkedHashSet<>(mine);
        accountedFor.addAll(remembered);
        List<Card> known = null;
        if (knownSkat != null && knownSkat.size() == 2
                && Collections.disjoint(knownSkat, accountedFor)) {
            known = List.copyOf(knownSkat);
            accountedFor.addAll(known);
        }

        List<Card> unknown = new ArrayList<>();
        for (Card card : SkatDeck.ordered()) {
            if (!accountedFor.contains(card)) unknown.add(card);
        }

        // Ten each at the start; every card a seat has played is one fewer. The
        // observer's own count comes from its hand rather than from arithmetic,
        // so a mid-trick position needs no special case.
        int[] needed = new int[3];
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            needed[seat.ordinal()] = seat == observer ? 0 : 10 - playedBy(context, seat);
        }
        int skatSlots = known == null ? 2 : 0;

        int required = needed[0] + needed[1] + needed[2] + skatSlots;
        if (required > unknown.size()) {
            throw new IllegalStateException("Inconsistent position: " + unknown.size()
                    + " candidate cards for " + required + " places");
        }
        // Copied field by field: new EnumMap<>(map) rejects an empty map that is
        // not itself an EnumMap, and this one arrives wrapped as unmodifiable.
        EnumMap<SkatAi.Seat, Set<SkatAi.FollowClass>> voids = new EnumMap<>(SkatAi.Seat.class);
        voids.putAll(enforcedVoids);
        return new WorldSampler(contract, observer, mine, unknown, needed, skatSlots, known, voids);
    }

    /** Cards {@code seat} has already played, across finished tricks and this one. */
    private static int playedBy(SkatAi.DecisionContext context, SkatAi.Seat seat) {
        int count = 0;
        for (SkatAi.CompletedTrick trick : context.history.completedTricks) {
            for (SkatAi.PlayedCard play : trick.plays) if (play.seat == seat) count++;
        }
        for (SkatAi.PlayedCard play : context.currentTrick.plays) if (play.seat == seat) count++;
        return count;
    }

    /** The observer's own hand, which every sampled world shares. */
    public List<Card> observerCards() { return myCards; }

    /**
     * One consistent deal: the three hands by seat ordinal, with the observer's
     * own cards in its own seat.
     *
     * <p>Returns {@code null} only if no arrangement could be found within the
     * retry budget, which means the void constraints are so tight that almost
     * nothing fits. Callers treat that as "sample fewer worlds", never as an
     * error: a position that hard is one where the guess barely matters.
     */
    public World draw(Random random) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            World world = attemptDraw(random);
            if (world != null) return world;
        }
        return null;
    }

    private World attemptDraw(Random random) {
        // Most constrained first. Dealing a card that only one seat may hold
        // after that seat has filled up is the only way this fails, and handling
        // those cards while every seat is still open makes it rare.
        List<Card> order = new ArrayList<>(unknown);
        Collections.shuffle(order, random);
        order.sort((left, right) -> Integer.compare(
                placesFor(left).size(), placesFor(right).size()));

        int[] remaining = needed.clone();
        int skatLeft = skatSlots;
        List<List<Card>> hands = new ArrayList<>(3);
        for (int seat = 0; seat < 3; seat++) {
            hands.add(seat == observer.ordinal() ? new ArrayList<>(myCards) : new ArrayList<>());
        }
        List<Card> skat = knownSkat == null ? new ArrayList<>(2) : new ArrayList<>(knownSkat);

        for (Card card : order) {
            List<Integer> places = new ArrayList<>(4);
            for (int place : placesFor(card)) {
                boolean room = place < 3 ? remaining[place] > 0 : skatLeft > 0;
                if (room) places.add(place);
            }
            // A card with nowhere left to go is simply not in this world. With a
            // perfect memory there is exactly one card per place and skipping one
            // means the draw failed, which the check below catches; with a
            // forgetful one there are more cards than places by construction.
            if (places.isEmpty()) continue;
            int place = choosePlace(card, places, random);
            if (place < 3) {
                hands.get(place).add(card);
                remaining[place]--;
            } else {
                skat.add(card);
                skatLeft--;
            }
        }
        if (skatLeft > 0) return null;
        for (int seat = 0; seat < 3; seat++) if (remaining[seat] > 0) return null;
        return new World(hands, skat);
    }

    /**
     * Which of the open places this card goes to.
     *
     * <p>Uniform unless a belief says otherwise, and this one line is the entire
     * seam between the two. Everything that makes a sampled deal <em>legal</em> --
     * the capacities, the voids, the retry -- is above and stays untouched, so a
     * learned belief can only change which consistent world is drawn, never
     * whether it is consistent. That is the property worth having: a bad model
     * makes the player wrong, it cannot make it cheat or crash.
     *
     * <p>Sequential rather than exact. Drawing from the product of per-card
     * probabilities subject to the capacities is a matching problem; assigning
     * one card at a time in proportion to the weights, over the places still
     * open, is the cheap standard approximation and it moves the draws where the
     * belief points, which is all the search needs.
     */
    private int choosePlace(Card card, List<Integer> places, Random random) {
        if (weights == null) return places.get(random.nextInt(places.size()));
        double total = 0;
        double[] share = new double[places.size()];
        for (int at = 0; at < places.size(); at++) {
            share[at] = Math.max(0, weights.weight(card, places.get(at)));
            total += share[at];
        }
        // A belief that rules out every open place says nothing useful about this
        // card, so the uniform draw takes over rather than the position failing.
        if (total <= 0) return places.get(random.nextInt(places.size()));
        double point = random.nextDouble() * total;
        for (int at = 0; at < places.size(); at++) {
            point -= share[at];
            if (point <= 0) return places.get(at);
        }
        return places.get(places.size() - 1);
    }

    /**
     * How likely a belief thinks each card is to be in each place.
     *
     * <p>Places are the seat ordinals, with 3 for the skat -- the same numbering
     * {@code placesFor} uses. Weights need not sum to anything; only their ratios
     * within one card matter.
     */
    @FunctionalInterface
    public interface Weights {
        double weight(Card card, int place);
    }

    /** The same sampler, drawing where a belief points instead of uniformly. */
    public WorldSampler weightedBy(Weights weights) {
        WorldSampler weighted = new WorldSampler(contract, observer, myCards, unknown,
                needed, skatSlots, knownSkat, voids);
        weighted.weights = weights;
        return weighted;
    }

    /** Seats (0..2) that may hold this card, plus 3 for the skat. */
    private List<Integer> placesFor(Card card) {
        List<Integer> places = new ArrayList<>(4);
        SkatAi.FollowClass followClass = SkatRules.publicFollowClass(contract, card);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (seat == observer || needed[seat.ordinal()] == 0) continue;
            Set<SkatAi.FollowClass> seatVoids = voids.get(seat);
            if (seatVoids != null && seatVoids.contains(followClass)) continue;
            places.add(seat.ordinal());
        }
        if (skatSlots > 0) places.add(3);
        return places;
    }

    /** One sampled deal: three hands by seat ordinal, plus the two buried cards. */
    public record World(List<List<Card>> hands, List<Card> skat) {}
}
