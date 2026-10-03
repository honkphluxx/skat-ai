package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rest of a game played out in one world, every seat by the same policy
 * (docs/training-plan.md 2.10, R: play-outs in the declarer's lost positions).
 *
 * <p>Starts from a real decision -- the position as the deciding seat saw it,
 * with the hidden hands filled in from one sampled world -- plays a given card
 * for the deciding seat, and then lets {@link Policy} play every remaining
 * card for all three seats, each seat seeing only what it would see at the
 * table: the {@link SkatAi.DecisionContext} it is handed is built exactly as
 * {@code GameEngine} builds one (its own hand, the legal cards, the trick,
 * the completed tricks, the cards played, the voids shown, points and tricks
 * taken). The world is the only thing it does not know, and it is never shown
 * it. Rules are the engine's own ({@link SkatRules#legalCards},
 * {@link SkatRules#trickWinner}); suit and grand games only.
 *
 * <p>Not thread-safe: one instance per play-out.
 */
final class Playout {

    /** Chooses a card for the seat in a context, from its legal cards. */
    interface Policy {
        Card choose(SkatAi.DecisionContext context);
    }

    /** What came of one play-out. */
    record Result(boolean declarerWon, int declarerPoints) {}

    private final SkatAi.GameDefinition game;
    private final List<List<Card>> hands = new ArrayList<>(3);
    private final List<SkatAi.CompletedTrick> history;
    private final List<SkatAi.PlayedCard> trick;
    private final Set<Card> played;
    private final Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids = new EnumMap<>(SkatAi.Seat.class);
    private final Map<SkatAi.Seat, Integer> tricksWon = new EnumMap<>(SkatAi.Seat.class);
    private final Map<SkatAi.Seat, Integer> points = new EnumMap<>(SkatAi.Seat.class);
    private final int skatPoints;
    private SkatAi.Seat leader;
    private SkatAi.Seat toPlay;

    /**
     * @param at    the real decision, for the seat to move
     * @param world every seat's remaining cards by seat ordinal (the deciding
     *              seat's own must be its real hand), and the skat as this
     *              world has it -- the declarer's to count at the end
     */
    Playout(SkatAi.DecisionContext at, List<List<Card>> world, List<Card> skat) {
        if (at.game.contract.isNull() || !at.game.hasDeclarer()) {
            throw new IllegalArgumentException("play-outs are for suit and grand games");
        }
        this.game = at.game;
        for (int s = 0; s < 3; s++) hands.add(new ArrayList<>(world.get(s)));
        if (!new HashSet<>(hands.get(at.mySeat.ordinal())).equals(at.hand)) {
            throw new IllegalArgumentException("the world must hold the deciding seat's own hand");
        }
        this.history = new ArrayList<>(at.history.completedTricks);
        this.trick = new ArrayList<>(at.currentTrick.plays);
        this.played = new LinkedHashSet<>(at.derived.playedCards);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            voids.put(seat, new HashSet<>(at.derived.voidClasses.getOrDefault(seat, Set.of())));
            tricksWon.put(seat, at.derived.tricksWon.getOrDefault(seat, 0));
            points.put(seat, at.derived.cardPoints.getOrDefault(seat, 0));
        }
        this.skatPoints = SkatRules.cardPoints(skat);
        this.leader = at.currentTrick.leader;
        this.toPlay = at.mySeat;
    }

    /** Plays {@code first} for the deciding seat, then the rest by {@code policy}. */
    Result run(Card first, Policy policy) {
        play(toPlay, first);
        while (history.size() < 10) {
            List<Card> hand = hands.get(toPlay.ordinal());
            Set<Card> legal = SkatRules.legalCards(game.contract, hand, trick);
            Card card = legal.size() == 1 ? legal.iterator().next() : policy.choose(context(toPlay, legal));
            if (!legal.contains(card)) throw new IllegalStateException("the policy played an illegal card: " + card);
            play(toPlay, card);
        }
        int declarer = points.get(game.declarer) + skatPoints;
        return new Result(declarer >= 61, declarer);
    }

    private SkatAi.DecisionContext context(SkatAi.Seat seat, Set<Card> legal) {
        return new SkatAi.DecisionContext(game, seat, new LinkedHashSet<>(hands.get(seat.ordinal())), legal,
                new SkatAi.CurrentTrick(history.size(), leader, trick),
                new SkatAi.GameHistory(history),
                new SkatAi.DerivedGameKnowledge(played, tricksWon, points, voids));
    }

    private void play(SkatAi.Seat seat, Card card) {
        List<Card> hand = hands.get(seat.ordinal());
        if (!hand.remove(card)) throw new IllegalStateException(seat + " does not hold " + card);
        if (!trick.isEmpty()) {
            SkatAi.FollowClass led = SkatRules.publicFollowClass(game.contract, trick.get(0).card);
            if (!SkatRules.publicFollowClass(game.contract, card).equals(led)) voids.get(seat).add(led);
        }
        trick.add(new SkatAi.PlayedCard(seat, card));
        played.add(card);
        if (trick.size() < 3) {
            toPlay = seat.next();
            return;
        }
        SkatAi.Seat winner = SkatRules.trickWinner(game.contract, trick);
        int won = 0;
        for (SkatAi.PlayedCard p : trick) won += SkatRules.cardPoints(p.card);
        history.add(new SkatAi.CompletedTrick(history.size(), leader, new ArrayList<>(trick), winner, won));
        tricksWon.merge(winner, 1, Integer::sum);
        points.merge(winner, won, Integer::sum);
        trick.clear();
        leader = winner;
        toPlay = winner;
    }
}
