package dev.skatklar.demo.ai;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Implementation-independent API model shared by local, remote, stateful and stateless players. */
public final class SkatAi {
    private SkatAi() {}

    public enum Seat {
        HUMAN("You"), OPPONENT_ONE("Orange AI"), OPPONENT_TWO("Green AI");

        public final String label;

        Seat(String label) { this.label = label; }

        public Seat next() { return values()[(ordinal() + 1) % values().length]; }

        @Override public String toString() { return label; }
    }

    /** Stable table rotation. One set contains the three possible dealer positions. */
    public static final class RoundPosition {
        public final long roundNumber;
        public final long setNumber;
        public final int gameInSet;
        public final Seat dealer;
        public final Seat forehand;
        public final Seat middlehand;
        public final Seat rearhand;

        public RoundPosition(long roundNumber, Seat dealer) {
            if (roundNumber < 0) throw new IllegalArgumentException("Negative round number");
            this.roundNumber = roundNumber;
            this.setNumber = roundNumber / Seat.values().length;
            this.gameInSet = (int) (roundNumber % Seat.values().length);
            this.dealer = Objects.requireNonNull(dealer);
            this.forehand = dealer.next();
            this.middlehand = forehand.next();
            this.rearhand = middlehand.next();
        }

        public static RoundPosition at(long roundNumber) {
            // Start with the human in forehand, then rotate clockwise each round.
            Seat dealer = Seat.values()[(Seat.OPPONENT_TWO.ordinal()
                    + (int) (roundNumber % Seat.values().length)) % Seat.values().length];
            return new RoundPosition(roundNumber, dealer);
        }
    }

    /** Full announcement vocabulary; the demo currently plays only suit and Grand. */
    public enum ContractType {
        DIAMONDS, HEARTS, SPADES, CLUBS, GRAND, NULL, RAMSCH, PASSED_IN
    }

    /** A rules-level contract announcement, independent of any AI implementation. */
    public static final class ContractAnnouncement {
        public final ContractType type;
        public final boolean hand;
        public final boolean schneider;
        public final boolean schwarz;
        public final boolean ouvert;

        public ContractAnnouncement(ContractType type, boolean hand,
                                    boolean schneider, boolean schwarz, boolean ouvert) {
            this.type = Objects.requireNonNull(type);
            this.hand = hand;
            this.schneider = schneider;
            this.schwarz = schwarz;
            this.ouvert = ouvert;
        }

        public static ContractAnnouncement skatGame(ContractType type) {
            return new ContractAnnouncement(type, false, false, false, false);
        }
    }

    /** Announcement capabilities supplied by the game variant, not by an adapter. */
    public static final class ContractRules {
        public final Set<ContractType> allowedTypes;
        public final boolean handAllowed;
        public final boolean announcementsAllowed;
        public final boolean ouvertAllowed;

        public ContractRules(Set<ContractType> allowedTypes, boolean handAllowed,
                             boolean announcementsAllowed, boolean ouvertAllowed) {
            this.allowedTypes = immutableSet(allowedTypes);
            this.handAllowed = handAllowed;
            this.announcementsAllowed = announcementsAllowed;
            this.ouvertAllowed = ouvertAllowed;
        }

        public boolean permits(ContractAnnouncement contract) {
            return contract != null && allowedTypes.contains(contract.type)
                    && (handAllowed || !contract.hand)
                    && (announcementsAllowed || (!contract.schneider && !contract.schwarz))
                    && (ouvertAllowed || !contract.ouvert);
        }
    }

    public static final class GameDefinition {
        /**
         * The player alone against the other two — <b>null in a Ramsch</b>, which
         * has nobody in that role. Every read of this field is a place that has
         * to have decided what a Ramsch means to it; {@link #hasDeclarer()} is
         * the guard, and {@code contract.isRamsch()} says the same thing.
         */
        public final Seat declarer;
        public final Seat initialLeader;
        public final Contract contract;
        public final RoundPosition round;
        public final int bidValue;
        /**
         * Played without picking the skat up. The two cards still belong to the
         * declarer for matadors and for card points, which is exactly why a hand
         * game is not the certainty it is often assumed to be.
         */
        public final boolean hand;
        public final boolean schneiderAnnounced;
        public final boolean schwarzAnnounced;
        public final boolean ouvert;

        public GameDefinition(Seat declarer, Seat initialLeader, Contract contract) {
            this(declarer, initialLeader, contract, new RoundPosition(0,
                    initialLeader == null ? Seat.OPPONENT_TWO : initialLeader.next().next()), 0);
        }

        public GameDefinition(Seat declarer, Seat initialLeader, Contract contract,
                              RoundPosition round, int bidValue) {
            this(declarer, initialLeader, contract, round, bidValue,
                    false, false, false, false);
        }

        public GameDefinition(Seat declarer, Seat initialLeader, Contract contract,
                              RoundPosition round, int bidValue, boolean hand,
                              boolean schneiderAnnounced, boolean schwarzAnnounced,
                              boolean ouvert) {
            this.contract = Objects.requireNonNull(contract);
            if (contract.isRamsch()) {
                if (declarer != null) {
                    throw new IllegalArgumentException("A Ramsch has no declarer");
                }
            } else {
                Objects.requireNonNull(declarer, "declarer");
            }
            this.declarer = declarer;
            this.initialLeader = Objects.requireNonNull(initialLeader);
            this.round = Objects.requireNonNull(round);
            this.bidValue = Math.max(0, bidValue);
            this.hand = hand;
            this.schneiderAnnounced = schneiderAnnounced;
            this.schwarzAnnounced = schwarzAnnounced;
            this.ouvert = ouvert;
        }

        /** True when nothing beyond the bare contract was announced. */
        public boolean plainSkatGame() {
            return !hand && !schneiderAnnounced && !schwarzAnnounced && !ouvert;
        }

        /** False only for a Ramsch, where {@link #declarer} is null. */
        public boolean hasDeclarer() { return declarer != null; }

        public boolean isRamsch() { return contract.isRamsch(); }

        /** The Ramsch nobody bid for. Forehand leads it, as they lead everything. */
        public static GameDefinition ramsch(RoundPosition round) {
            Objects.requireNonNull(round, "round");
            return new GameDefinition(null, round.forehand, Contract.RAMSCH, round, 0);
        }
    }

    /**
     * How far the doubling has been taken. Kontra doubles the game value, Re
     * doubles it again, and the canon stops there — no Sup, no Hirsch.
     *
     * <p>Not part of {@link GameDefinition}, because it is not settled when the
     * game is: a defender may watch two cards fall in the first trick and only
     * then say it.
     */
    public enum ContraLevel {
        NONE(1), KONTRA(2), RE(4);

        /** What the game value is multiplied by, won or lost alike. */
        public final int factor;

        ContraLevel(int factor) { this.factor = factor; }
    }

    /**
     * The one moment a seat may double: immediately before it plays its own
     * first card of the first trick. A defender in third position has therefore
     * seen two cards; forehand has seen none.
     *
     * <p>{@code answeringKontra} distinguishes the two questions asked through
     * this context. False asks a defender for Kontra; true asks the declarer for
     * Re, and then it is the only moment they get, because the answer must come
     * before the next card is played.
     */
    public static final class ContraContext {
        public final GameDefinition game;
        public final Seat mySeat;
        public final Set<Card> hand;
        public final CurrentTrick currentTrick;
        public final boolean answeringKontra;

        public ContraContext(GameDefinition game, Seat mySeat, Set<Card> hand,
                             CurrentTrick currentTrick, boolean answeringKontra) {
            this.game = Objects.requireNonNull(game);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.hand = immutableSet(hand);
            this.currentTrick = Objects.requireNonNull(currentTrick);
            this.answeringKontra = answeringKontra;
        }
    }

    /** Said out loud, so every seat hears it — including the one that said it. */
    public static final class ContraEvent {
        public final Seat seat;
        public final ContraLevel level;

        public ContraEvent(Seat seat, ContraLevel level) {
            this.seat = Objects.requireNonNull(seat);
            this.level = Objects.requireNonNull(level);
        }
    }

    /**
     * One leg of the Schieben: twelve cards in hand, two of which are pushed on.
     *
     * <p>The two cards received are named separately from the rest of the hand
     * because they are the strongest piece of evidence anyone gets before the
     * first card falls — they say exactly what {@link #from} no longer holds.
     * Rearhand's push is not passed to anybody; it becomes the skat.
     */
    public static final class RamschPushContext {
        public final RoundPosition round;
        public final Seat mySeat;
        public final Set<Card> hand;
        public final List<Card> received;
        /** Who pushed {@link #received} here; null for forehand, who gets the skat. */
        public final Seat from;
        /** Who will receive the push; null for rearhand, whose push becomes the skat. */
        public final Seat to;

        public RamschPushContext(RoundPosition round, Seat mySeat, Set<Card> hand,
                                 List<Card> received, Seat from, Seat to) {
            this.round = Objects.requireNonNull(round);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.hand = immutableSet(hand);
            this.received = immutableList(received);
            this.from = from;
            this.to = to;
        }
    }

    /** Two cards moved from one seat to the next, as everyone at the table saw it. */
    public static final class RamschPushEvent {
        public final Seat from;
        /** Null when the push became the skat. */
        public final Seat to;
        /** Only the seats that held them ever learn which cards they were. */
        public final int count;

        public RamschPushEvent(Seat from, Seat to, int count) {
            this.from = Objects.requireNonNull(from);
            this.to = to;
            this.count = count;
        }
    }

    public static final class DealContext {
        public final RoundPosition round;
        public final Seat mySeat;
        public final Set<Card> initialHand;
        public final ContractRules contractRules;

        public DealContext(RoundPosition round, Seat mySeat, Set<Card> initialHand,
                           ContractRules contractRules) {
            this.round = Objects.requireNonNull(round);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.initialHand = immutableSet(initialHand);
            this.contractRules = Objects.requireNonNull(contractRules);
        }
    }

    public enum BidRole { ANNOUNCE, HOLD }

    public static final class BidRequest {
        public final RoundPosition round;
        public final Seat mySeat;
        public final BidRole role;
        public final int currentBid;
        public final int requestedBid;

        public BidRequest(RoundPosition round, Seat mySeat, BidRole role,
                          int currentBid, int requestedBid) {
            this.round = Objects.requireNonNull(round);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.role = Objects.requireNonNull(role);
            this.currentBid = Math.max(0, currentBid);
            this.requestedBid = requestedBid;
        }
    }

    public static final class BidEvent {
        public final Seat seat;
        public final int value;
        public final boolean passed;

        public BidEvent(Seat seat, int value, boolean passed) {
            this.seat = Objects.requireNonNull(seat);
            this.value = Math.max(0, value);
            this.passed = passed;
        }
    }

    public static final class SkatChoiceContext {
        public final RoundPosition round;
        public final Seat mySeat;
        public final int winningBid;

        public SkatChoiceContext(RoundPosition round, Seat mySeat, int winningBid) {
            this.round = Objects.requireNonNull(round);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.winningBid = winningBid;
        }
    }

    public static final class SkatExchangeContext {
        public final RoundPosition round;
        public final Seat mySeat;
        public final Set<Card> hand;
        public final List<Card> skat;

        public SkatExchangeContext(RoundPosition round, Seat mySeat,
                                   Set<Card> hand, List<Card> skat) {
            this.round = Objects.requireNonNull(round);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.hand = immutableSet(hand);
            this.skat = immutableList(skat);
        }
    }

    public static final class ContractContext {
        public final RoundPosition round;
        public final Seat mySeat;
        public final Set<Card> hand;
        public final int winningBid;
        public final boolean skatPickedUp;
        public final ContractRules rules;

        public ContractContext(RoundPosition round, Seat mySeat, Set<Card> hand,
                               int winningBid, boolean skatPickedUp, ContractRules rules) {
            this.round = Objects.requireNonNull(round);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.hand = immutableSet(hand);
            this.winningBid = winningBid;
            this.skatPickedUp = skatPickedUp;
            this.rules = Objects.requireNonNull(rules);
        }
    }

    public static final class AiDescriptor {
        public final String id;
        public final String displayName;
        public final boolean stateful;

        public AiDescriptor(String id, String displayName, boolean stateful) {
            this.id = Objects.requireNonNull(id);
            this.displayName = Objects.requireNonNull(displayName);
            this.stateful = stateful;
        }
    }

    public static final class GameStartContext {
        public final GameDefinition game;
        public final Seat mySeat;
        public final Set<Card> initialHand;
        public final RoundPosition round;

        public GameStartContext(GameDefinition game, Seat mySeat, Set<Card> initialHand) {
            this.game = Objects.requireNonNull(game);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.initialHand = immutableSet(initialHand);
            this.round = game.round;
        }
    }

    public static final class DecisionContext {
        public final GameDefinition game;
        public final Seat mySeat;
        public final Set<Card> hand;
        public final Set<Card> legalCards;
        public final CurrentTrick currentTrick;
        public final GameHistory history;
        public final DerivedGameKnowledge derived;

        public DecisionContext(GameDefinition game, Seat mySeat, Set<Card> hand,
                               Set<Card> legalCards, CurrentTrick currentTrick,
                               GameHistory history, DerivedGameKnowledge derived) {
            this.game = Objects.requireNonNull(game);
            this.mySeat = Objects.requireNonNull(mySeat);
            this.hand = immutableSet(hand);
            this.legalCards = immutableSet(legalCards);
            this.currentTrick = Objects.requireNonNull(currentTrick);
            this.history = Objects.requireNonNull(history);
            this.derived = Objects.requireNonNull(derived);
        }
    }

    public static final class CurrentTrick {
        public final int trickNumber;
        public final Seat leader;
        public final List<PlayedCard> plays;

        public CurrentTrick(int trickNumber, Seat leader, List<PlayedCard> plays) {
            this.trickNumber = trickNumber;
            this.leader = Objects.requireNonNull(leader);
            this.plays = immutableList(plays);
        }
    }

    public static final class PlayedCard {
        public final Seat seat;
        public final Card card;

        public PlayedCard(Seat seat, Card card) {
            this.seat = Objects.requireNonNull(seat);
            this.card = Objects.requireNonNull(card);
        }
    }

    public static final class CompletedTrick {
        public final int trickNumber;
        public final Seat leader;
        public final List<PlayedCard> plays;
        public final Seat winner;
        public final int cardPoints;

        public CompletedTrick(int trickNumber, Seat leader, List<PlayedCard> plays,
                              Seat winner, int cardPoints) {
            this.trickNumber = trickNumber;
            this.leader = Objects.requireNonNull(leader);
            this.plays = immutableList(plays);
            this.winner = Objects.requireNonNull(winner);
            this.cardPoints = cardPoints;
        }
    }

    public static final class GameHistory {
        public final List<CompletedTrick> completedTricks;

        public GameHistory(List<CompletedTrick> completedTricks) {
            this.completedTricks = immutableList(completedTricks);
        }
    }

    /** A following category is Trump or one of the four non-trump suits. */
    public static final class FollowClass {
        public final boolean trump;
        public final Card.Suit suit;

        private FollowClass(boolean trump, Card.Suit suit) {
            this.trump = trump;
            this.suit = suit;
        }

        public static FollowClass trump() { return new FollowClass(true, null); }
        public static FollowClass suit(Card.Suit suit) {
            return new FollowClass(false, Objects.requireNonNull(suit));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof FollowClass)) return false;
            FollowClass that = (FollowClass) other;
            return trump == that.trump && suit == that.suit;
        }

        @Override public int hashCode() { return 31 * Boolean.hashCode(trump) + Objects.hashCode(suit); }
        @Override public String toString() { return trump ? "TRUMP" : suit.name(); }
    }

    public static final class DerivedGameKnowledge {
        public final Set<Card> playedCards;
        public final Map<Seat, Integer> tricksWon;
        public final Map<Seat, Integer> cardPoints;
        public final Map<Seat, Set<FollowClass>> voidClasses;

        public DerivedGameKnowledge(Set<Card> playedCards, Map<Seat, Integer> tricksWon,
                                    Map<Seat, Integer> cardPoints,
                                    Map<Seat, Set<FollowClass>> voidClasses) {
            this.playedCards = immutableSet(playedCards);
            this.tricksWon = immutableSeatMap(tricksWon);
            this.cardPoints = immutableSeatMap(cardPoints);
            EnumMap<Seat, Set<FollowClass>> voidCopy = new EnumMap<>(Seat.class);
            for (Seat seat : Seat.values()) {
                voidCopy.put(seat, immutableSet(voidClasses.getOrDefault(seat, Collections.emptySet())));
            }
            this.voidClasses = Collections.unmodifiableMap(voidCopy);
        }
    }

    public static final class CardPlayedEvent {
        public final int trickNumber;
        public final PlayedCard play;

        public CardPlayedEvent(int trickNumber, PlayedCard play) {
            this.trickNumber = trickNumber;
            this.play = Objects.requireNonNull(play);
        }
    }

    public static final class TrickCompletedEvent {
        public final CompletedTrick trick;
        public TrickCompletedEvent(CompletedTrick trick) { this.trick = Objects.requireNonNull(trick); }
    }

    public static final class GameResult {
        public final GameDefinition game;
        public final boolean declarerWon;
        public final int declarerPoints;
        public final int defenderPoints;
        public final int skatPoints;
        public final int gameValue;
        /**
         * The declarer bid more than the announced contract turned out to be
         * worth, so the game is lost regardless of card points. Worth surfacing
         * separately: without it a player who took 80 points and still lost has
         * no way to understand the result.
         */
        public final boolean overbid;
        public final Map<Seat, Integer> capturedPoints;
        public final Map<Seat, Integer> tricksWon;
        /**
         * Whose column {@link #gameValue} is written in. The declarer in every
         * declared game; in a Ramsch the seat that lost it, or the one that
         * marched through. Never null — a Ramsch always scores somebody.
         */
        public final Seat scoredSeat;
        /** How far the doubling went. {@link #gameValue} already includes it. */
        public final ContraLevel contra;
        /** Ramsch only: exactly one seat took no trick, doubling the loser. */
        public final boolean jungfrau;
        /** Ramsch only: one seat took all ten tricks and won it outright. */
        public final boolean durchmarsch;

        public GameResult(GameDefinition game, boolean declarerWon, int declarerPoints,
                          int defenderPoints, int skatPoints, int gameValue,
                          Map<Seat, Integer> capturedPoints, Map<Seat, Integer> tricksWon) {
            this(game, declarerWon, declarerPoints, defenderPoints, skatPoints, gameValue,
                    false, capturedPoints, tricksWon);
        }

        public GameResult(GameDefinition game, boolean declarerWon, int declarerPoints,
                          int defenderPoints, int skatPoints, int gameValue, boolean overbid,
                          Map<Seat, Integer> capturedPoints, Map<Seat, Integer> tricksWon) {
            this(game, declarerWon, declarerPoints, defenderPoints, skatPoints, gameValue,
                    overbid, capturedPoints, tricksWon, game.declarer, ContraLevel.NONE,
                    false, false);
        }

        public GameResult(GameDefinition game, boolean declarerWon, int declarerPoints,
                          int defenderPoints, int skatPoints, int gameValue, boolean overbid,
                          Map<Seat, Integer> capturedPoints, Map<Seat, Integer> tricksWon,
                          Seat scoredSeat, ContraLevel contra,
                          boolean jungfrau, boolean durchmarsch) {
            this.game = Objects.requireNonNull(game);
            this.declarerWon = declarerWon;
            this.declarerPoints = declarerPoints;
            this.defenderPoints = defenderPoints;
            this.skatPoints = skatPoints;
            this.gameValue = gameValue;
            this.overbid = overbid;
            this.capturedPoints = immutableSeatMap(capturedPoints);
            this.tricksWon = immutableSeatMap(tricksWon);
            this.scoredSeat = Objects.requireNonNull(scoredSeat, "scoredSeat");
            this.contra = contra == null ? ContraLevel.NONE : contra;
            this.jungfrau = jungfrau;
            this.durchmarsch = durchmarsch;
        }
    }

    private static <T> List<T> immutableList(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static <T> Set<T> immutableSet(Iterable<T> source) {
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T item : source) result.add(item);
        return Collections.unmodifiableSet(result);
    }

    private static Map<Seat, Integer> immutableSeatMap(Map<Seat, Integer> source) {
        EnumMap<Seat, Integer> result = new EnumMap<>(Seat.class);
        for (Seat seat : Seat.values()) result.put(seat, source.getOrDefault(seat, 0));
        return Collections.unmodifiableMap(result);
    }
}
