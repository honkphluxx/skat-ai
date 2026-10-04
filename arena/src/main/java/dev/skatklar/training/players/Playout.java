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

    /**
     * What came of one play-out: 61 card points reached, the declarer's card
     * points (the skat included), and the game as the engine would settle it
     * ({@link SkatRules#score}: the signed value, a loss doubled, an overbid
     * charged) -- the score sheet's number for the declarer.
     */
    record Result(boolean declarerWon, int declarerPoints, int gameValue) {}

    private final SkatAi.GameDefinition game;
    private final List<List<Card>> hands = new ArrayList<>(3);
    private final List<SkatAi.CompletedTrick> history;
    private final List<SkatAi.PlayedCard> trick;
    private final Set<Card> played;
    private final Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids = new EnumMap<>(SkatAi.Seat.class);
    private final Map<SkatAi.Seat, Integer> tricksWon = new EnumMap<>(SkatAi.Seat.class);
    private final Map<SkatAi.Seat, Integer> points = new EnumMap<>(SkatAi.Seat.class);
    private final int skatPoints;
    private final List<Card> skat;
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
        this.skat = List.copyOf(skat);
        this.leader = at.currentTrick.leader;
        this.toPlay = at.mySeat;
    }

    /** Plays {@code first} for the deciding seat, then the rest by {@code policy}. */
    Result run(Card first, Policy policy) {
        start(first);
        for (SkatAi.DecisionContext at = next(); at != null; at = next()) play(policy.choose(at));
        return result();
    }

    /**
     * The same play-out a step at a time, so many of them can wait on one
     * batched forward pass: {@link #start} the deciding seat's card, then
     * {@link #next} (which plays forced cards itself and returns the next
     * position with a choice, or null at the end) and {@link #play} the answer,
     * until {@link #result}.
     */
    void start(Card first) {
        play(toPlay, first);
    }

    /** The next position with more than one legal card, or null when the game is over. */
    SkatAi.DecisionContext next() {
        while (history.size() < 10) {
            List<Card> hand = hands.get(toPlay.ordinal());
            Set<Card> legal = SkatRules.legalCards(game.contract, hand, trick);
            if (legal.size() > 1) {
                pending = legal;
                return context(toPlay, legal);
            }
            play(toPlay, legal.iterator().next());
        }
        return null;
    }

    /** Answers the position {@link #next} returned. */
    void play(Card card) {
        if (pending == null) throw new IllegalStateException("no position is waiting for a card");
        if (!pending.contains(card)) throw new IllegalStateException("the policy played an illegal card: " + card);
        pending = null;
        play(toPlay, card);
    }

    /** The settled game; only once {@link #next} has returned null. */
    Result result() {
        if (history.size() < 10) throw new IllegalStateException("the play-out is not over");
        int declarer = points.get(game.declarer) + skatPoints;
        List<Card> declarerPlayed = new ArrayList<>(10);
        for (SkatAi.CompletedTrick done : history) {
            for (SkatAi.PlayedCard p : done.plays) if (p.seat == game.declarer) declarerPlayed.add(p.card);
        }
        SkatRules.GameScore score = SkatRules.score(game, SkatRules.matadorCards(game, declarerPlayed, skat),
                declarer, tricksWon);
        return new Result(declarer >= 61, declarer, score.gameValue());
    }

    /** The legal cards of the position {@link #next} handed out, until it is answered. */
    private Set<Card> pending;

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
