package dev.skatklar.demo;

import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/** Pure trusted rules for suit and Grand hand games. */
public final class SkatRules {
    private SkatRules() {}

    public static Set<Card> legalCards(Contract contract, List<Card> hand,
                                       List<SkatAi.PlayedCard> currentTrick) {
        if (hand.isEmpty()) return Collections.emptySet();
        if (currentTrick.isEmpty()) return immutableCards(hand);

        Follow led = followClass(contract, currentTrick.get(0).card);
        ArrayList<Card> following = new ArrayList<>();
        for (Card card : hand) {
            if (followClass(contract, card).equals(led)) following.add(card);
        }
        return immutableCards(following.isEmpty() ? hand : following);
    }

    public static SkatAi.Seat trickWinner(Contract contract, List<SkatAi.PlayedCard> plays) {
        if (plays.size() != 3) throw new IllegalArgumentException("A Skat trick needs exactly three cards");
        Card led = plays.get(0).card;
        SkatAi.PlayedCard winning = plays.get(0);
        for (int i = 1; i < plays.size(); i++) {
            SkatAi.PlayedCard challenger = plays.get(i);
            if (beats(contract, led, winning.card, challenger.card)) winning = challenger;
        }
        return winning.seat;
    }

    public static boolean beats(Contract contract, Card led, Card incumbent, Card challenger) {
        Follow ledClass = followClass(contract, led);
        Follow incumbentClass = followClass(contract, incumbent);
        Follow challengerClass = followClass(contract, challenger);

        if (!challengerClass.equals(incumbentClass)) {
            if (challengerClass.trump) return true;
            if (incumbentClass.trump) return false;
            return challengerClass.equals(ledClass) && !incumbentClass.equals(ledClass);
        }
        if (!challengerClass.equals(ledClass) && !challengerClass.trump) return false;
        return strength(contract, challenger) > strength(contract, incumbent);
    }

    public static int cardPoints(Card card) {
        return switch (card.rank) {
            case ACE -> 11;
            case TEN -> 10;
            case KING -> 4;
            case QUEEN -> 3;
            case JACK -> 2;
            case NINE, EIGHT, SEVEN -> 0;
        };
    }

    public static int cardPoints(Iterable<Card> cards) {
        int result = 0;
        for (Card card : cards) result += cardPoints(card);
        return result;
    }

    /**
     * The complete settlement of a finished game: who won, at what value, and
     * whether the declarer overbid.
     *
     * <p>This is the authority. Winning and the game value are decided together
     * because the overbid rule couples them: a declarer who bid more than the
     * contract turned out to be worth loses even with 61 or more card points.
     */
    public record GameScore(boolean declarerWon, boolean overbid, int gameValue) {}

    /**
     * Settles a finished game, including overbidding (ISkO 3.6). The declarer
     * wins by taking at least 61 card points, keeping every announcement that was
     * made, <em>and</em> playing a contract worth at least the winning bid.
     *
     * <p>An overbid game is charged at the lowest multiple of the announced
     * contract's base value that reaches the bid, and then doubled like any
     * other lost game with the skat picked up. Null has no multiples, so an
     * overbid Null is simply the lost game, doubled.
     */
    public static GameScore score(SkatAi.GameDefinition game, Iterable<Card> declarerCards,
                                  int declarerPoints, Map<SkatAi.Seat, Integer> tricksWon) {
        if (game.contract.isRamsch()) {
            throw new IllegalArgumentException("A Ramsch is settled by scoreRamsch");
        }
        int declarerTricks = tricksWon.getOrDefault(game.declarer, 0);
        if (game.contract.isNull()) {
            // The whole contract is "I take no trick". Card points never enter.
            boolean won = declarerTricks == 0;
            int value = nullValue(game.hand, game.ouvert);
            if (value >= game.bidValue) {
                return new GameScore(won, false, won ? value : -2 * value);
            }
            return new GameScore(false, true, -2 * value);
        }
        boolean madeCardPoints = declarerPoints >= 61
                && keptAnnouncements(game, declarerPoints, 10 - declarerTricks);
        int played = playedValue(game, declarerCards, madeCardPoints, declarerPoints, tricksWon);
        if (played >= game.bidValue) {
            return new GameScore(madeCardPoints, false, madeCardPoints ? played : -2 * played);
        }
        int charged = Math.max(played, multipleReaching(baseValue(game.contract), game.bidValue));
        return new GameScore(false, true, -2 * charged);
    }

    /**
     * An announcement is a promise: missing it loses the game outright, however
     * many card points were taken. Ouvert in a trump game implies schwarz, so it
     * needs no separate test here.
     */
    private static boolean keptAnnouncements(SkatAi.GameDefinition game, int declarerPoints,
                                             int defenderTricks) {
        if (game.schneiderAnnounced && 120 - declarerPoints > 30) return false;
        return !game.schwarzAnnounced || defenderTricks == 0;
    }

    /** The four fixed Null values. Matadors and card points do not apply. */
    public static int nullValue(boolean hand, boolean ouvert) {
        if (ouvert) return hand ? 59 : 46;
        return hand ? 35 : 23;
    }

    /**
     * Which announcements a contract permits. Schneider, schwarz and ouvert are
     * hand-game announcements in a trump game; Null has no schneider or schwarz
     * at all, and may be played open with or without the skat.
     */
    public static boolean legalAnnouncement(Contract contract, boolean hand,
                                            boolean schneiderAnnounced,
                                            boolean schwarzAnnounced, boolean ouvert) {
        if (contract == null) return false;
        if (contract.isNull()) return !schneiderAnnounced && !schwarzAnnounced;
        if (!hand && (schneiderAnnounced || schwarzAnnounced || ouvert)) return false;
        if (schwarzAnnounced && !schneiderAnnounced) return false;
        return !ouvert || schwarzAnnounced;
    }

    /**
     * Returns the signed Skat game value for the supported suit and Grand games.
     * A lost game is doubled, as it is when entered on a Skat score sheet.
     *
     * <p>This is the value of the game <em>as played</em> and deliberately knows
     * nothing about the bid. Callers settling a real game want {@link #score}.
     */
    public static int gameValue(SkatAi.GameDefinition game, Iterable<Card> declarerCards,
                                boolean declarerWon, int declarerPoints,
                                Map<SkatAi.Seat, Integer> tricksWon) {
        int value = playedValue(game, declarerCards, declarerWon, declarerPoints, tricksWon);
        return declarerWon ? value : -2 * value;
    }

    /** Unsigned value of the game as played, before the doubling of a loss. */
    private static int playedValue(SkatAi.GameDefinition game, Iterable<Card> declarerCards,
                                   boolean declarerWon, int declarerPoints,
                                   Map<SkatAi.Seat, Integer> tricksWon) {
        if (game.contract.isNull()) return nullValue(game.hand, game.ouvert);
        int declarerTricks = tricksWon.getOrDefault(game.declarer, 0);
        int defenderTricks = 10 - declarerTricks;
        int multiplier = matadorCount(game.contract, declarerCards) + 1; // with/without + game
        if (declarerWon) {
            if (120 - declarerPoints <= 30) multiplier++;
            if (defenderTricks == 0) multiplier++;
        } else {
            if (declarerPoints <= 30) multiplier++;
            if (declarerTricks == 0) multiplier++;
        }
        return baseValue(game.contract) * (multiplier + announcementMultipliers(game));
    }

    /**
     * Multipliers bought by announcing rather than by the cards. Each is charged
     * whether the game is won or lost, which is what makes an announced schwarz
     * expensive to miss.
     */
    public static int announcementMultipliers(SkatAi.GameDefinition game) {
        int extra = 0;
        if (game.hand) extra++;
        if (game.schneiderAnnounced) extra++;
        if (game.schwarzAnnounced) extra++;
        if (game.ouvert) extra++;
        return extra;
    }

    /**
     * The value the declarer is guaranteed at announcement time, from the
     * matadors alone. A bid above this cannot be covered by the contract and is
     * what a declarer must avoid announcing.
     *
     * <p>Deliberately counts only the cards passed in. During the auction those
     * are ten, and the two unseen skat cards can still move the matador count in
     * either direction — {@link BidValues} is what quantifies that.
     */
    public static int guaranteedValue(Contract contract, Iterable<Card> declarerCards) {
        if (contract.isNull()) return nullValue(false, false);
        return baseValue(contract) * (matadorCount(contract, declarerCards) + 1);
    }

    /** Base value of a trump game. Null is fixed and has none; see {@link #nullValue}. */
    public static int baseValue(Contract contract) {
        return switch (contract) {
            case DIAMONDS -> 9;
            case HEARTS -> 10;
            case SPADES -> 11;
            case CLUBS -> 12;
            case GRAND -> 24;
            case NULL -> throw new IllegalArgumentException("Null has no base value");
            case RAMSCH -> throw new IllegalArgumentException("A Ramsch is scored in card points");
        };
    }

    // ---------------------------------------------------------------- Ramsch

    /**
     * How a Ramsch ended: one seat scores and the other two write nothing.
     *
     * @param scoredSeat   the loser, or the seat that marched through
     * @param value        signed, and already doubled by a jungfrau
     * @param cardPoints   what that seat took, the skat included
     */
    public record RamschScore(SkatAi.Seat scoredSeat, int value, int cardPoints,
                              boolean jungfrau, boolean durchmarsch) {}

    /**
     * Settles a Ramsch from the tricks as they fell.
     *
     * <p>Takes the history rather than a points map because the tie-break needs
     * it: two seats can finish on the same number of card points, and the canon
     * ({@code docs/rules.md}, ruling 4) gives it to whoever won the later trick.
     * Without an order there is no answer, and an arbitrary one would put the
     * skat somewhere different depending on how the map happened to iterate.
     *
     * <p>The loser is found on the tricks <em>alone</em>. Only afterwards is the
     * skat added, which is what "unbesehen" means: those two cards change how
     * much is lost, never by whom.
     */
    public static RamschScore scoreRamsch(List<SkatAi.CompletedTrick> tricks,
                                          Iterable<Card> skat) {
        Map<SkatAi.Seat, Integer> points = new java.util.EnumMap<>(SkatAi.Seat.class);
        Map<SkatAi.Seat, Integer> won = new java.util.EnumMap<>(SkatAi.Seat.class);
        Map<SkatAi.Seat, Integer> lastTrick = new java.util.EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            points.put(seat, 0);
            won.put(seat, 0);
            lastTrick.put(seat, -1);
        }
        for (SkatAi.CompletedTrick trick : tricks) {
            points.merge(trick.winner, trick.cardPoints, Integer::sum);
            won.merge(trick.winner, 1, Integer::sum);
            lastTrick.put(trick.winner, trick.trickNumber);
        }

        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (tricks.size() == 10 && won.get(seat) == 10) {
                // A durchmarsch breaks the Ramsch: it is won, at a flat 120, and
                // the skat is not added to it because nobody lost it.
                return new RamschScore(seat, 120, points.get(seat), false, true);
            }
        }

        SkatAi.Seat loser = null;
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (loser == null
                    || points.get(seat) > points.get(loser)
                    || (points.get(seat).equals(points.get(loser))
                        && lastTrick.get(seat) > lastTrick.get(loser))) {
                loser = seat;
            }
        }
        int jungfrauen = 0;
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (won.get(seat) == 0) jungfrauen++;
        }
        boolean jungfrau = jungfrauen == 1;
        int total = points.get(loser) + cardPoints(skat);
        return new RamschScore(loser, -(jungfrau ? 2 * total : total), total, jungfrau, false);
    }

    /**
     * Whether these two cards may be pushed on. Jacks may not — the one rule the
     * Schieben adds, and the reason a Ramsch cannot be quietly emptied of trump.
     */
    public static boolean legalRamschPush(Set<Card> pushed, Collection<Card> hand) {
        if (pushed == null || pushed.size() != 2 || !hand.containsAll(pushed)) return false;
        for (Card card : pushed) {
            if (card.rank == Card.Rank.JACK) return false;
        }
        return true;
    }

    /**
     * The two cards a player pushes on when nothing cleverer is asked for: the
     * dearest non-jacks in the hand. Cards you do not hold cannot be forced into
     * a trick you win, so shedding the expensive ones is the whole idea.
     *
     * <p>Twelve cards hold at most four jacks, so there are always at least eight
     * candidates and this can never fail to find a pair.
     */
    public static Set<Card> defaultRamschPush(Collection<Card> hand) {
        List<Card> candidates = new ArrayList<>();
        for (Card card : hand) {
            if (card.rank != Card.Rank.JACK) candidates.add(card);
        }
        candidates.sort((left, right) -> {
            int byPoints = Integer.compare(cardPoints(right), cardPoints(left));
            if (byPoints != 0) return byPoints;
            return Integer.compare(strength(Contract.RAMSCH, right), strength(Contract.RAMSCH, left));
        });
        LinkedHashSet<Card> pushed = new LinkedHashSet<>();
        for (Card card : candidates) {
            if (pushed.size() == 2) break;
            pushed.add(card);
        }
        return Collections.unmodifiableSet(pushed);
    }

    /** Smallest multiple of {@code base} that is at least {@code target}. */
    private static int multipleReaching(int base, int target) {
        if (target <= base) return base;
        return base * ((target + base - 1) / base);
    }

    /**
     * "With" or "without" N matadors: the unbroken run of top trumps a holding
     * either has or lacks, counting down from the jack of clubs. Public because
     * hand evaluation needs exactly the same count the score sheet uses.
     */
    public static int matadorCount(Contract contract, Iterable<Card> heldCards) {
        if (contract.isNull()) return 0;
        LinkedHashSet<Card> cards = new LinkedHashSet<>();
        for (Card card : heldCards) cards.add(card);
        List<Card> trumpOrder = trumpOrder(contract);
        boolean with = cards.contains(trumpOrder.get(0));
        int matadors = 0;
        for (Card trump : trumpOrder) {
            if (cards.contains(trump) != with) break;
            matadors++;
        }
        return matadors;
    }

    /**
     * Whether the matadors counted by {@link #matadorCount} are held or missing
     * — "with" rather than "without".
     *
     * <p>{@code matadorCount} returns a magnitude, because the game's
     * <em>value</em> is the same either way: Diamonds without nine really is
     * game ten, ninety. Anything using that count to guess how a hand will
     * <em>play</em> needs this as well, or a holding with none of the top trumps
     * scores exactly like one holding all of them. That mistake is what kept
     * {@code HandEvaluator.promise} from ever ranking a Null hand.
     */
    public static boolean withMatadors(Contract contract, Iterable<Card> heldCards) {
        if (contract.isNull()) return false;
        List<Card> trumpOrder = trumpOrder(contract);
        if (trumpOrder.isEmpty()) return false;
        for (Card card : heldCards) {
            if (card.equals(trumpOrder.get(0))) return true;
        }
        return false;
    }

    /** Trumps from strongest to weakest: four jacks, then the trump suit if any. */
    public static List<Card> trumpOrder(Contract contract) {
        ArrayList<Card> result = new ArrayList<>();
        if (contract.isNull()) return result;
        result.add(new Card(Card.Suit.CLUBS, Card.Rank.JACK));
        result.add(new Card(Card.Suit.SPADES, Card.Rank.JACK));
        result.add(new Card(Card.Suit.HEARTS, Card.Rank.JACK));
        result.add(new Card(Card.Suit.DIAMONDS, Card.Rank.JACK));
        if (contract.trumpSuit != null) {
            Card.Rank[] ranks = { Card.Rank.ACE, Card.Rank.TEN, Card.Rank.KING,
                    Card.Rank.QUEEN, Card.Rank.NINE, Card.Rank.EIGHT, Card.Rank.SEVEN };
            for (Card.Rank rank : ranks) result.add(new Card(contract.trumpSuit, rank));
        }
        return result;
    }

    public static SkatAi.FollowClass publicFollowClass(Contract contract, Card card) {
        Follow follow = followClass(contract, card);
        return follow.trump ? SkatAi.FollowClass.trump() : SkatAi.FollowClass.suit(follow.suit);
    }

    private static int strength(Contract contract, Card card) {
        // Null keeps the jack in its own suit and puts the ten back where its
        // rank says it belongs, between the nine and the jack.
        if (contract.isNull()) return nullStrength(card.rank);
        if (card.rank == Card.Rank.JACK) {
            return 100 + switch (card.suit) {
                case CLUBS -> 4;
                case SPADES -> 3;
                case HEARTS -> 2;
                case DIAMONDS -> 1;
            };
        }
        int rank = switch (card.rank) {
            case ACE -> 7;
            case TEN -> 6;
            case KING -> 5;
            case QUEEN -> 4;
            case NINE -> 3;
            case EIGHT -> 2;
            case SEVEN -> 1;
            case JACK -> throw new AssertionError();
        };
        return contract.isTrump(card) ? 50 + rank : rank;
    }

    /** Null rank order, weakest first: 7 8 9 10 J Q K A. */
    public static int nullStrength(Card.Rank rank) {
        return switch (rank) {
            case SEVEN -> 1;
            case EIGHT -> 2;
            case NINE -> 3;
            case TEN -> 4;
            case JACK -> 5;
            case QUEEN -> 6;
            case KING -> 7;
            case ACE -> 8;
        };
    }

    private static Follow followClass(Contract contract, Card card) {
        return contract.isTrump(card) ? Follow.TRUMP : new Follow(false, card.suit);
    }

    private static Set<Card> immutableCards(List<Card> cards) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(cards));
    }

    private static final class Follow {
        static final Follow TRUMP = new Follow(true, null);
        final boolean trump;
        final Card.Suit suit;

        Follow(boolean trump, Card.Suit suit) {
            this.trump = trump;
            this.suit = suit;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Follow)) return false;
            Follow that = (Follow) other;
            return trump == that.trump && suit == that.suit;
        }

        @Override public int hashCode() { return 31 * Boolean.hashCode(trump) + (suit == null ? 0 : suit.hashCode()); }
    }
}
