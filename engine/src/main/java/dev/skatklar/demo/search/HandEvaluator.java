package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.solve.NullSolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * How likely a hand is to make a contract, measured rather than scored.
 *
 * <p>Hand evaluation is normally a table of rules — count the jacks, add a point
 * for each ace, bid if the total clears a threshold. That table is where a
 * heuristic player's ceiling comes from, and it is the part that has to be
 * rewritten for every contract type. This does the same job by playing the hand
 * out: deal the other twenty-two cards at random, solve, repeat, and report the
 * share of deals that made it.
 *
 * <p>The number is a probability against <em>perfect</em> defence, so it is
 * systematically pessimistic — real defenders let contracts through that a solver
 * would beat. That is fine for its purpose, which is comparing contracts and
 * hands against each other, but it means the threshold a player bids at is not
 * the win rate it will actually see. The arena is what converts one into the
 * other.
 *
 * <p>Cost is one solve per sampled deal, so this is the expensive part of a
 * bidding decision. Callers should ask about the two or three contracts a hand
 * could plausibly play and not about all five.
 */
public final class HandEvaluator {

    private final int worlds;
    private final Random random;

    public HandEvaluator(int worlds, Random random) {
        this.worlds = Math.max(1, worlds);
        this.random = random;
    }

    /**
     * The share of sampled deals the contract holds up in — 61 card points for a
     * trump game, not a single trick for Null.
     *
     * @param myCards  the ten cards as dealt; the pickup and discard are modelled
     * @param mySeat   where they are sitting; the leader follows from the round
     * @param leader   the seat that will lead the first trick
     */
    public double makeChance(Contract contract, List<Card> myCards, SkatAi.Seat mySeat,
                             SkatAi.Seat leader) {
        return sample(contract, myCards, mySeat, leader, false, 0L);
    }

    /**
     * As {@link #makeChance}, but stopping when the clock runs out.
     *
     * <p>The estimate is a mean over independent worlds, so a short sample is a
     * noisier answer rather than a wrong one, and the honest thing to do when
     * time runs out is to divide by the worlds that were actually solved. That
     * is the whole trick: the caller loses precision, not the ability to answer.
     *
     * <p>The first world is always started, so a hand comes back unmeasured only
     * when a single solve could not finish inside the budget — the pathological
     * tail this exists for.
     *
     * @param deadlineNanos a {@link System#nanoTime} reading to stop at
     * @return the share over the worlds that finished, or {@link Double#NaN} if
     *         none of them did
     */
    public double makeChanceBefore(Contract contract, List<Card> myCards, SkatAi.Seat mySeat,
                                   SkatAi.Seat leader, long deadlineNanos) {
        return sample(contract, myCards, mySeat, leader, true, deadlineNanos);
    }

    /**
     * What the auction has said so far, as a constraint on the worlds a hand is
     * priced against.
     *
     * <p>The plain sampler deals the unseen cards uniformly, which prices a
     * hand as if nobody else had spoken. But an opponent who has held 44 has
     * told you something about where the jacks are, and one who passed at 20
     * has told you something weaker in the other direction. This carries
     * both, per seat, and {@link #consistent} turns them into a rule for
     * keeping or rejecting a sampled world.
     *
     * <p><b>What a bid means here is the jacks it implies, and nothing
     * learned.</b> The first version of this took "a bid of V needs a hand
     * that guarantees V" from the score sheet and rejected the rest -- and
     * excluded almost nothing, because a hand with no jacks at all guarantees
     * 120: Grand, without four, game five. Guaranteed value is what a game is
     * worth if made, not whether it can be, and "without N" sends the two in
     * opposite directions. So the floor is taken from the one thing every
     * bidder bids on, held jacks: a bid of V claims a game worth V, the most
     * lenient contract is Grand at base 24, and reaching V <em>with</em> N
     * matadors needs N held jacks, while any suit game needs more. That gives
     * {@link #jacksImplied}: a bid up to 24 says nothing, 25 to 48 says one
     * jack, 49 to 72 two, 73 to 96 three. It is wrong only for a bid of 49 or
     * more on at most one jack -- a "without" game in a monster suit, rare and
     * weak -- and wrong in the direction of trusting the bid slightly too much,
     * which is the small error to make. It learns nothing from any opponent:
     * the alternative, a model of what a bid means fitted to the arena's
     * population, would learn that XSkat never bluffs and then trust a human
     * who does.
     *
     * <p>A pass is weaker evidence and is treated as such. A player who passed
     * at L holding more jacks than L implies -- {@link PassRule#margin} more --
     * could plainly have bid; the world is kept with a reduced weight rather
     * than rejected, because cautious bidders exist. The {@link PassRule} is
     * the one tunable in this, and it is not to be fitted against any
     * particular opponent: a setting of it ships only when it is non-negative
     * against the whole field, itself included.
     *
     * @param bids   the highest value each opponent has bid or held, by seat;
     *               absent or zero for a seat that has not bid
     * @param passes the value each opponent declined, by seat; absent or zero
     *               for a seat that has not passed
     * @param rule   how hard a pass is read
     */
    public record AuctionEvidence(Map<SkatAi.Seat, Integer> bids,
                                  Map<SkatAi.Seat, Integer> passes,
                                  PassRule rule) {
        public static final AuctionEvidence NONE = new AuctionEvidence(Map.of(), Map.of());

        /** Jacks over the implied count before a pass is doubted, at the reference reading. */
        public static final int PASS_MARGIN = 2;
        /** How much of a world survives an opponent's pass at a level their jacks could have bid. */
        public static final double PASS_WEIGHT = 0.35;

        /**
         * How a pass is read: a world in which a passer holds at least
         * {@code margin} jacks more than their pass implies survives with
         * probability {@code weight}. Larger margins and weights read a pass
         * more softly; {@link #DEFAULT} is the reference reading.
         */
        public record PassRule(int margin, double weight) {
            public static final PassRule DEFAULT = new PassRule(PASS_MARGIN, PASS_WEIGHT);

            public PassRule {
                if (margin < 1) throw new IllegalArgumentException("margin must be at least one jack");
                if (weight < 0 || weight > 1) throw new IllegalArgumentException("weight is a probability");
            }
        }

        /** Evidence read under the reference {@link PassRule#DEFAULT}. */
        public AuctionEvidence(Map<SkatAi.Seat, Integer> bids, Map<SkatAi.Seat, Integer> passes) {
            this(bids, passes, PassRule.DEFAULT);
        }

        public boolean isEmpty() {
            return bids.values().stream().allMatch(v -> v <= 0)
                    && passes.values().stream().allMatch(v -> v <= 0);
        }

        /** The fewest held jacks a bid of {@code value} can plausibly rest on. */
        public static int jacksImplied(int value) {
            if (value <= 0) return 0;
            return Math.max(0, Math.min(4, (value + 23) / 24 - 1));
        }

        static int jacksHeld(List<Card> hand) {
            int jacks = 0;
            for (Card card : hand) if (card.rank == Card.Rank.JACK) jacks++;
            return jacks;
        }

        /**
         * Whether a sampled world is consistent with what its seats have said,
         * drawing on {@code random} for the soft cases.
         */
        boolean consistent(Map<SkatAi.Seat, List<Card>> hands, SkatAi.Seat mySeat, Random random) {
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                if (seat == mySeat) continue;
                int bid = bids.getOrDefault(seat, 0);
                int passedAt = passes.getOrDefault(seat, 0);
                if (bid <= 0 && passedAt <= 0) continue;
                int jacks = jacksHeld(hands.get(seat));
                if (bid > 0 && jacks < jacksImplied(bid)) return false;
                if (bid <= 0 && passedAt > 0 && jacks >= jacksImplied(passedAt) + rule.margin()
                        && random.nextDouble() >= rule.weight()) return false;
            }
            return true;
        }
    }

    /**
     * As {@link #makeChance}, priced against worlds consistent with the auction
     * so far rather than against all of them.
     */
    public double makeChance(Contract contract, List<Card> myCards, SkatAi.Seat mySeat,
                             SkatAi.Seat leader, AuctionEvidence evidence) {
        return sample(contract, myCards, mySeat, leader, false, 0L, evidence);
    }

    /** As {@link #makeChanceBefore}, with the auction as a constraint. */
    public double makeChanceBefore(Contract contract, List<Card> myCards, SkatAi.Seat mySeat,
                                   SkatAi.Seat leader, long deadlineNanos,
                                   AuctionEvidence evidence) {
        return sample(contract, myCards, mySeat, leader, true, deadlineNanos, evidence);
    }

    /**
     * How many deals may be drawn and rejected in search of one the auction
     * allows, before the last one is taken anyway. A bound so that evidence
     * the sampler cannot satisfy -- three opponents all claiming the jacks --
     * degrades to the unconstrained answer rather than to a hang.
     */
    private static final int REJECTION_ATTEMPTS = 40;

    private double sample(Contract contract, List<Card> myCards, SkatAi.Seat mySeat,
                          SkatAi.Seat leader, boolean bounded, long deadlineNanos) {
        return sample(contract, myCards, mySeat, leader, bounded, deadlineNanos,
                AuctionEvidence.NONE);
    }

    private double sample(Contract contract, List<Card> myCards, SkatAi.Seat mySeat,
                          SkatAi.Seat leader, boolean bounded, long deadlineNanos,
                          AuctionEvidence evidence) {
        List<Card> rest = new ArrayList<>();
        Set<Card> mine = new LinkedHashSet<>(myCards);
        for (Card card : SkatDeck.ordered()) if (!mine.contains(card)) rest.add(card);
        boolean constrained = evidence != null && !evidence.isEmpty();

        int made = 0;
        int solved = 0;
        for (int world = 0; world < worlds; world++) {
            if (bounded && world > 0 && System.nanoTime() - deadlineNanos >= 0) break;
            Map<SkatAi.Seat, List<Card>> hands = new EnumMap<>(SkatAi.Seat.class);
            int at = 0;
            // Deal until the auction allows it, or give up and take the last one.
            // Dealing is cheap and solving is not, so the rejections happen here
            // and never cost a solve.
            for (int attempt = 0; ; attempt++) {
                Collections.shuffle(rest, random);
                hands.clear();
                at = 0;
                for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                    if (seat == mySeat) {
                        hands.put(seat, new ArrayList<>(myCards));
                    } else {
                        hands.put(seat, new ArrayList<>(rest.subList(at, at + 10)));
                        at += 10;
                    }
                }
                if (!constrained || attempt >= REJECTION_ATTEMPTS
                        || evidence.consistent(hands, mySeat, random)) break;
            }
            // The declarer picks the skat up and buries two cards, so a hand has
            // to be judged as it will be played rather than as it was dealt.
            // Skipping that step is not a small pessimism: it is the difference
            // between a bidder that opens on a third of its hands and one that
            // never opens at all.
            List<Card> twelve = new ArrayList<>(myCards);
            twelve.addAll(rest.subList(at, at + 2));
            List<Card> keep = Discards.keepBestTen(contract, twelve);
            int bankedInTheSkat = SkatRules.cardPoints(Discards.buried(contract, twelve));

            List<List<Card>> bySeat = new ArrayList<>(3);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                bySeat.add(seat == mySeat ? keep : hands.get(seat));
            }
            // Spelled out rather than nested in a conditional expression. Mixing
            // a `boolean` arm with a `Boolean` one makes the whole expression a
            // boolean conditional (JLS 15.25), which unboxes the reference arm --
            // so the timeout's null would have become an NPE here.
            Boolean holds;
            if (contract.isNull()) {
                // Null is left unbounded on purpose: it is decided on tricks
                // rather than on points, prunes far harder, and has no tail worth
                // guarding against. The between-worlds check above contains it.
                holds = NullSolver.declarerSurvives(mySeat, bySeat, leader);
            } else if (bounded) {
                holds = DoubleDummySolver.declarerReachesBefore(contract, mySeat, bySeat,
                        leader, 61 - bankedInTheSkat, deadlineNanos);
            } else {
                holds = DoubleDummySolver.declarerReaches(contract, mySeat, bySeat, leader,
                        61 - bankedInTheSkat);
            }
            if (holds == null) break;
            solved++;
            if (holds) made++;
        }
        if (solved == 0) return Double.NaN;
        return (double) made / solved;
    }

    /**
     * The contracts worth asking about, best-looking first.
     *
     * <p>A cheap pre-filter, not an evaluation: it exists so the expensive solves
     * are spent on the contracts a hand might actually play rather than on all
     * six. Trump length plus the matadors it really holds is enough to sort
     * them.
     *
     * <p><b>Do not use this to decide whether a hand can play a Null.</b> Null
     * is scored on tricks and the trump games on points, and no arithmetic makes
     * two such numbers comparable — trying anyway is what kept Null out of the
     * top two on 99.5% of hands and out of the app's auctions entirely. Ask
     * {@link #plausibleTrumpGames} for the trump games and measure Null on its
     * own; that is what {@code SearchAiProvider} does. This full ranking is
     * still the right thing for choosing <em>between</em> contracts a hand has
     * already been judged on, which is what the announcement and the discard
     * use it for.
     */
    public static List<Contract> plausible(List<Card> cards, int howMany) {
        return ranked(cards, List.of(Contract.DECLARED_GAMES), howMany);
    }

    /** As {@link #plausible}, over the trump games alone. */
    public static List<Contract> plausibleTrumpGames(List<Card> cards, int howMany) {
        List<Contract> trumpGames = new ArrayList<>();
        for (Contract contract : Contract.DECLARED_GAMES) {
            if (!contract.isNull()) trumpGames.add(contract);
        }
        return ranked(cards, trumpGames, howMany);
    }

    private static List<Contract> ranked(List<Card> cards, List<Contract> of, int howMany) {
        List<Contract> all = new ArrayList<>(of);
        all.sort((left, right) -> {
            int byPromise = Integer.compare(promise(cards, right), promise(cards, left));
            if (byPromise != 0) return byPromise;
            // A tie is the scale admitting it cannot tell these two apart, and
            // 17.5% of hands produce one among the suit games. Left to a stable
            // sort it went to whichever came first in DECLARED_GAMES, which made
            // the app declare Diamonds half again as often as Clubs — a
            // measurable amount of game value given away by an accident of enum
            // order. Where the model is indifferent, take the game that is worth
            // more: what declaring is worth is linear in the game's value, so if
            // it is worth declaring at all it is worth declaring the dearer one.
            return Integer.compare(baseValue(right), baseValue(left));
        });
        return all.subList(0, Math.max(0, Math.min(howMany, all.size())));
    }

    private static int baseValue(Contract contract) {
        return contract.isNull() ? 0 : SkatRules.baseValue(contract);
    }

    private static int promise(List<Card> cards, Contract contract) {
        // Null is ranked on the opposite scale: what matters is how little the
        // hand can be forced to win. Not comparable with a trump game's number
        // and never compared with one — see plausible().
        if (contract.isNull()) {
            int danger = 0;
            for (Card card : cards) danger += (SkatRules.nullStrength(card.rank) - 1) * 2;
            return 60 - danger;
        }
        int trumps = 0;
        int aces = 0;
        for (Card card : cards) {
            if (contract.isTrump(card)) trumps++;
            if (card.rank == Card.Rank.ACE && !contract.isTrump(card)) aces++;
        }
        // Matadors count only when they are held. SkatRules.matadorCount returns
        // a magnitude, because the game's *value* is the same either way, and
        // paying for it unsigned here rated a hand holding none of the top
        // trumps exactly like one holding all of them: the best Null holding in
        // the pack — four sevens, four eights, two nines, which cannot be forced
        // to take a trick — came out of this as a promising Spades game at 62.
        // Grand lives on jacks and aces and dies on trump length, so the same
        // count would rank it last on every hand; the base value corrects for it.
        int matadors = SkatRules.withMatadors(contract, cards)
                ? SkatRules.matadorCount(contract, cards) : 0;
        return trumps * 10 + aces * 6 + matadors * 4
                + (contract == Contract.GRAND ? trumps * 8 : 0);
    }
}
