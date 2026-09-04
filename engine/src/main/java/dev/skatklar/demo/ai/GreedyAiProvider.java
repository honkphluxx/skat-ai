package dev.skatklar.demo.ai;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.ramsch.RamschPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A small, fully deterministic heuristic player.
 *
 * <p>It exists for two reasons. It is a sanity baseline that is clearly better
 * than random but clearly worse than anything worth shipping, so a new model
 * that cannot beat it is broken rather than merely weak. And because it is
 * deterministic, running it against itself must produce an exactly zero
 * duplicate difference — which is the arena's own correctness test.
 *
 * <p>It is intentionally simple and is not a target to optimise against.
 */
public final class GreedyAiProvider implements SkatAiProvider {

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor("greedy", "Greedy heuristic", true);
    }

    @Override public SkatAiSession createSession() { return new Session(); }

    private static final class Session implements SkatAiSession {
        private final List<Card> hand = new ArrayList<>();
        private int maxBid;
        private int winningBid;

        @Override public void prepareDeal(SkatAi.DealContext context) {
            hand.clear();
            hand.addAll(context.initialHand);
            maxBid = 0;
            for (SkatAi.ContractType type : context.contractRules.allowedTypes) {
                Contract contract = toContract(type);
                if (contract == null) continue;
                maxBid = Math.max(maxBid, estimate(hand, contract));
            }
        }

        @Override public int bid(SkatAi.BidRequest request) {
            return request.requestedBid <= maxBid ? request.requestedBid : 0;
        }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            winningBid = context.winningBid;
            return true;
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            List<Card> twelve = new ArrayList<>(context.hand);
            Contract contract = bestContract(twelve, allSkatContracts(), winningBid);
            twelve.sort(Comparator
                    .comparingInt((Card card) -> keepScore(card, contract, twelve))
                    .thenComparing(contract.preferredCardOrder));
            LinkedHashSet<Card> discarded = new LinkedHashSet<>();
            discarded.add(twelve.get(0));
            discarded.add(twelve.get(1));
            return discarded;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
            List<SkatAi.ContractType> allowed = new ArrayList<>(context.rules.allowedTypes);
            allowed.removeIf(type -> toContract(type) == null);
            Contract best = bestContract(
                    new ArrayList<>(context.hand), allowed, context.winningBid);
            return SkatAi.ContractAnnouncement.skatGame(SkatAi.ContractType.valueOf(best.name()));
        }

        @Override public void startGame(SkatAi.GameStartContext context) {
            hand.clear();
            hand.addAll(context.initialHand);
        }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            // Everything below is written as declarer-or-defender, and a Ramsch
            // is neither.
            if (context.game.isRamsch()) return RamschPolicy.chooseCard(context);
            Contract contract = context.game.contract;
            List<Card> legal = new ArrayList<>(context.legalCards);
            legal.sort(contract.preferredCardOrder);
            return context.currentTrick.plays.isEmpty()
                    ? lead(context, contract, legal)
                    : follow(context, contract, legal);
        }

        /** Declarers pull trumps; defenders cash aces and otherwise lead cheaply. */
        private Card lead(SkatAi.DecisionContext context, Contract contract, List<Card> legal) {
            boolean declarer = context.mySeat == context.game.declarer;
            if (declarer && opponentsHoldTrump(context, contract)) {
                for (Card card : legal) if (contract.isTrump(card)) return card;
            }
            for (Card card : legal) {
                if (!contract.isTrump(card) && card.rank == Card.Rank.ACE) return card;
            }
            return cheapest(legal, contract);
        }

        private Card follow(SkatAi.DecisionContext context, Contract contract, List<Card> legal) {
            List<SkatAi.PlayedCard> plays = context.currentTrick.plays;
            Card led = plays.get(0).card;
            SkatAi.PlayedCard winning = plays.get(0);
            for (int i = 1; i < plays.size(); i++) {
                if (SkatRules.beats(contract, led, winning.card, plays.get(i).card)) {
                    winning = plays.get(i);
                }
            }
            boolean last = plays.size() == 2;
            boolean partnerWinning = context.mySeat != context.game.declarer
                    && winning.seat != context.game.declarer;
            int trickPoints = SkatRules.cardPoints(led);
            for (int i = 1; i < plays.size(); i++) trickPoints += SkatRules.cardPoints(plays.get(i).card);

            if (partnerWinning) {
                // Feed points to a partner who has already secured the trick.
                return last ? richest(legal) : cheapest(legal, contract);
            }

            Card cheapestWinner = null;
            for (Card card : legal) {
                if (!SkatRules.beats(contract, led, winning.card, card)) continue;
                if (cheapestWinner == null || SkatRules.cardPoints(card) < SkatRules.cardPoints(cheapestWinner)) {
                    cheapestWinner = card;
                }
            }
            if (cheapestWinner != null && (last || trickPoints >= 4)) return cheapestWinner;
            return cheapest(legal, contract);
        }

        private static boolean opponentsHoldTrump(SkatAi.DecisionContext context, Contract contract) {
            int outstanding = SkatRules.trumpOrder(contract).size();
            for (Card card : context.derived.playedCards) if (contract.isTrump(card)) outstanding--;
            for (Card card : context.hand) if (contract.isTrump(card)) outstanding--;
            return outstanding > 0;
        }

        /** Lowest card points first, then the contract's own order for determinism. */
        private static Card cheapest(List<Card> legal, Contract contract) {
            Card best = legal.get(0);
            for (Card card : legal) {
                if (SkatRules.cardPoints(card) < SkatRules.cardPoints(best)) best = card;
            }
            return best;
        }

        private static Card richest(List<Card> legal) {
            Card best = legal.get(0);
            for (Card card : legal) {
                if (SkatRules.cardPoints(card) > SkatRules.cardPoints(best)) best = card;
            }
            return best;
        }
    }

    /** Higher means "more worth keeping" when discarding into the skat. */
    private static int keepScore(Card card, Contract contract, List<Card> holding) {
        if (contract.isTrump(card)) return 1000;
        boolean protectedByAce = holdsAce(holding, card.suit);
        return switch (card.rank) {
            case ACE -> 100;
            // A bare ten is the classic discard: it banks its ten points instead
            // of handing them over when the ace turns up.
            case TEN -> protectedByAce ? 60 : 5;
            case KING -> protectedByAce ? 30 : 15;
            case QUEEN -> 20;
            case NINE -> 12;
            case EIGHT -> 11;
            case SEVEN -> 10;
            case JACK -> 1000;
        };
    }

    private static boolean holdsAce(List<Card> holding, Card.Suit suit) {
        for (Card card : holding) {
            if (card.suit == suit && card.rank == Card.Rank.ACE) return true;
        }
        return false;
    }

    /**
     * Picks the best-looking contract that still covers {@code bidValue}.
     *
     * <p>The filter is not optional polish. The maximum bid this player is willing
     * to hold is derived from its <em>best</em> contract, but the announcement is
     * made from the hand after the skat exchange -- announcing a cheaper contract
     * than the bid loses the game outright under the overbid rule, whatever the
     * card points. The arena measured this at 9.6% of declared games before the
     * filter existed.
     *
     * <p>When nothing covers the bid the hand was simply overbid during the
     * auction; the strongest contract then minimises the charge.
     */
    private static Contract bestContract(List<Card> cards, List<SkatAi.ContractType> allowed,
                                         int bidValue) {
        Contract best = null;
        int bestScore = Integer.MIN_VALUE;
        Contract fallback = Contract.GRAND;
        int fallbackValue = Integer.MIN_VALUE;
        for (SkatAi.ContractType type : allowed) {
            Contract contract = toContract(type);
            if (contract == null) continue;
            int guaranteed = SkatRules.guaranteedValue(contract, cards);
            if (guaranteed >= bidValue) {
                int score = rank(cards, contract);
                if (score > bestScore) { bestScore = score; best = contract; }
            } else if (guaranteed > fallbackValue) {
                fallbackValue = guaranteed;
                fallback = contract;
            }
        }
        return best != null ? best : fallback;
    }

    private static List<SkatAi.ContractType> allSkatContracts() {
        return List.of(SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS,
                SkatAi.ContractType.SPADES, SkatAi.ContractType.CLUBS,
                SkatAi.ContractType.GRAND);
    }

    /** Playability score, used to pick a contract even when no bid is justified. */
    private static int rank(List<Card> cards, Contract contract) {
        int trumps = 0;
        int aces = 0;
        int tens = 0;
        for (Card card : cards) {
            if (contract.isTrump(card)) trumps++;
            if (card.rank == Card.Rank.ACE) aces++;
            if (card.rank == Card.Rank.TEN) tens++;
        }
        int matadors = SkatRules.matadorCount(contract, cards);
        int trumpWeight = contract == Contract.GRAND ? 6 : 3;
        return trumps * trumpWeight + aces * 3 + tens + matadors * 2;
    }

    /** Conservative maximum bid: the game value, but only for hands that look sound. */
    private static int estimate(List<Card> cards, Contract contract) {
        // This heuristic counts trumps, aces and matadors, so it can only speak
        // about games won on card points. Null is a different objective and gets
        // a flat pass until somebody teaches this player to recognise one.
        if (!SearchAiProvider.playsForCardPoints(contract)) return 0;
        int trumps = 0;
        for (Card card : cards) if (contract.isTrump(card)) trumps++;
        int minimumTrumps = contract == Contract.GRAND ? 3 : 4;
        if (trumps < minimumTrumps) return 0;
        int score = rank(cards, contract);
        // Calibrated in the arena to pass in on roughly one deal in ten, which is
        // realistic. Raising it further barely improves the declarer win rate
        // (~49%), because the binding constraint is this player's card play and
        // not its hand selection -- which is exactly the gap a search player closes.
        int threshold = contract == Contract.GRAND ? 33 : 24;
        if (score < threshold) return 0;
        return SkatRules.guaranteedValue(contract, cards);
    }

    private static Contract toContract(SkatAi.ContractType type) {
        return switch (type) {
            case DIAMONDS -> Contract.DIAMONDS;
            case HEARTS -> Contract.HEARTS;
            case SPADES -> Contract.SPADES;
            case CLUBS -> Contract.CLUBS;
            case GRAND -> Contract.GRAND;
            case NULL, RAMSCH, PASSED_IN -> null;
        };
    }
}
