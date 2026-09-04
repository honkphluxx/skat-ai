package dev.skatklar.demo.search;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.ramsch.RamschEvaluator;
import dev.skatklar.demo.ramsch.RamschPolicy;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.solve.NullSolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * An honest search player: it never sees a card it was not shown.
 *
 * <p>Perfect-information Monte Carlo, the standard construction for trick games
 * with hidden cards. For each decision it draws deals consistent with what it has
 * observed ({@link WorldSampler}), asks the double-dummy solver which cards still
 * win in each of them, and plays the card that wins in the most worlds. Every
 * individual search is omniscient — about a hypothesis the player invented itself.
 *
 * <p>How strong it is, and how it feels to play against, is set by its
 * {@link Personality}: how much it remembers, how many worlds it can afford, how
 * boldly it plays and how readily it bids. Every one of those weakens what the
 * player knows or how hard it thinks, never the rule by which it decides — see
 * {@code Personality} for why that distinction is the whole design.
 *
 * <p>Two properties of the search itself are fixed rather than dialled:
 *
 * <ul>
 *   <li>The sampler is <b>uniform</b> over the arrangements it considers possible.
 *       It filters on facts and weights nothing, and in particular ignores the
 *       bidding. The learned belief model of Phase 4 is measured as the
 *       tournament points it adds over exactly this player.</li>
 *   <li>The vote is on <b>results, not points</b>: a card scores a world if the
 *       declarer still reaches the target from there. Skat pays for 61 and for
 *       90, not for the difference between 74 and 75.</li>
 * </ul>
 *
 * <p><b>Strategy fusion</b> is the known weakness of this whole family and is not
 * fixed here. Solving each world separately lets the player assume it will play
 * differently in each of them, so it overvalues lines whose success depends on
 * knowing something it will not know. More samples do not help; inference and
 * search over information sets do.
 */
public final class SearchAiProvider implements SkatAiProvider {

    /** Worlds per decision at the reference setting. */
    public static final int DEFAULT_WORLDS = Personality.REFERENCE.worlds();

    /**
     * How often a single opponent passes rather than take the game — the one
     * number in the bidding rule that is a property of the table rather than of
     * the cards, and the one thing in it that has to be measured.
     *
     * <p>It only matters before anybody has bid, which is where it decides
     * whether passing risks a Ramsch or merely hands the game to somebody else.
     * There is a fixed point here worth being honest about: a table that bids
     * more passes less, which makes passing safer, which makes it bid less. One
     * pass through the arena is enough to land near it, and the value below is
     * that measurement rather than a guess.
     */
    public static final double OPPONENT_PASSES = 0.5;

    /**
     * Worlds sampled per contract when evaluating a hand for the auction.
     *
     * <p>Tied to the experience dial rather than fixed, for two reasons. It is
     * incoherent for a player who samples two worlds a card to weigh its opening
     * bid over six -- a beginner does not think harder about bidding than about
     * playing. And it is where the time was going: the auction asks every seat
     * about two contracts before a card is dealt, so a fixed six worlds cost
     * three full hand evaluations a board whatever the level. Capped at six,
     * because bidding is a coarse decision and the extra samples were not
     * changing the answer.
     */
    private int biddingWorlds() {
        return Math.max(2, Math.min(6, personality.worlds() / 3));
    }

    private final SkatAiProvider delegate;
    private final Personality personality;
    private final long seed;
    private final WorldSource worlds;
    /**
     * Own moves αµ expands before the solver takes over; 0 keeps αµ out entirely.
     *
     * <p>Zero and one are different settings on purpose. Zero does not run
     * {@link AlphaMu} at all. One runs it with nothing expanded, which is
     * provably the same evaluation as the vote — so a player at one is the
     * <b>control</b>: it exercises every line of the plumbing (the per-world
     * targets, the mask conversion, the seat indices, the tie-break hand-off)
     * and must score exactly zero against the player it is built on. Any
     * difference at depth one is a wiring bug, and it separates that question
     * from "is the algorithm any good", which cannot be answered while the two
     * are confounded.
     */
    private final int alphaMuDepth;

    /**
     * Wall-clock a seat may spend measuring its hand before it bids, or zero for
     * no ceiling.
     *
     * <p>Zero everywhere it matters. A deadline makes the player's opinion depend
     * on the machine and on what else was running, and the arena, the server and
     * every test have to replay a match from its seed. Only the app sets one,
     * because only the app has somebody holding the phone.
     */
    private final long biddingBudgetNanos;

    /**
     * Cards in hand at or below which αµ is used instead of the vote.
     *
     * <p>A cost limit, not a taste. The search branches on our own card, then on
     * both opponents' answers, then on our own card again, and every leaf is a
     * double-dummy solve; measured on this machine one decision costs about 4 ms
     * with five cards left, 19 ms with six and 130 ms with seven, which is
     * roughly a factor of six per extra card. At ten -- the opening lead -- that
     * extrapolates to half a minute for one card, so the first tricks are not
     * negotiable and are played by the vote.
     *
     * <p>Seven is where the two curves cross for us: still affordable at three or
     * four decisions a game, and by then the sampled worlds have grown alike
     * enough that committing to one card actually costs something, which is the
     * only condition under which αµ differs from the vote at all.
     */
    private static final int ALPHA_MU_FROM = 7;

    /**
     * Everything this seat learned before the first card, kept on the provider.
     *
     * <p>All of it for the same reason as {@link #buried}: the engine runs the
     * auction, the exchange and the Schieben in one session and starts trick play
     * in a fresh one, so a field on the session is empty by the time a card is
     * chosen. The belief exporter lost three separate pieces of evidence to that
     * before it was noticed, and this player has to feed a model trained on all
     * of them.
     */
    private volatile List<Card> buried;
    private volatile Map<SkatAi.Seat, Integer> highestBids = new EnumMap<>(SkatAi.Seat.class);
    private volatile BeliefEncoding.Schieben schieben;
    private volatile SkatAi.ContraLevel contra = SkatAi.ContraLevel.NONE;
    private volatile SkatAi.Seat contraSeat;

    /**
     * @param delegate falls back for the discard, and for any decision this
     *                 player cannot make itself
     * @param seed     fixes the sampling, so a match replays exactly
     */
    public SearchAiProvider(SkatAiProvider delegate, Personality personality, long seed) {
        this(delegate, personality, seed, WorldSource.UNIFORM);
    }

    /**
     * @param worlds where the hypotheses come from. Everything else about the
     *               player is held fixed when this is replaced, which is what
     *               makes the difference between two beliefs measurable.
     */
    public SearchAiProvider(SkatAiProvider delegate, Personality personality, long seed,
                            WorldSource worlds) {
        this(delegate, personality, seed, worlds, 0);
    }

    /**
     * @param alphaMuDepth own moves {@link AlphaMu} expands before handing the
     *                     position to the solver. Zero, the default, does not
     *                     use it; one is the control described above; two is the
     *                     first setting that removes any strategy fusion.
     */
    public SearchAiProvider(SkatAiProvider delegate, Personality personality, long seed,
                            WorldSource worlds, int alphaMuDepth) {
        this(delegate, personality, seed, worlds, alphaMuDepth, 0L);
    }

    private SearchAiProvider(SkatAiProvider delegate, Personality personality, long seed,
                             WorldSource worlds, int alphaMuDepth, long biddingBudgetNanos) {
        this.delegate = delegate;
        this.personality = personality;
        this.seed = seed;
        this.worlds = worlds;
        this.alphaMuDepth = Math.max(0, alphaMuDepth);
        this.biddingBudgetNanos = Math.max(0L, biddingBudgetNanos);
    }

    /**
     * The same player, with a ceiling on what one hand's evaluation may cost.
     *
     * <p>A copy rather than a setting, so that the bounded player and the
     * unbounded one are different objects and nothing can acquire a deadline by
     * accident. Everything else is held fixed, which is what makes the cost of
     * the ceiling measurable in the arena.
     *
     * <p>What the ceiling buys, and what it costs: a hand whose two plausible
     * contracts cannot be sampled inside the budget is bid on the delegate's
     * opinion instead of on a measured one, and a hand that is sampled only
     * partly is bid on a noisier number. Both are weaker than waiting. Neither
     * is a frozen screen.
     *
     * @param nanos how long one seat may take; zero or less removes the ceiling
     */
    public SearchAiProvider withBiddingBudget(long nanos) {
        return new SearchAiProvider(delegate, personality, seed, worlds, alphaMuDepth, nanos);
    }

    /** The reference player at a given world count, with everything else neutral. */
    public SearchAiProvider(SkatAiProvider delegate, int worlds, long seed) {
        this(delegate, new Personality(worlds, 1.0, 0.0, 0.5), seed);
    }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor("search-" + personality.worlds(),
                "Determinized search, " + personality.worlds() + " worlds", true);
    }

    @Override public SkatAiSession createSession() {
        return new Session(delegate.createSession());
    }

    /**
     * Whether this contract is one the point-based search understands.
     *
     * <p>Null is scored on tricks rather than on points, so the vote, the target
     * and the make chance all have to come from {@link NullSolver} instead. This
     * is what selects between the two, and it is checked by name rather than
     * through {@code Contract.isNull} so the class still compiles against a core
     * that has no Null at all.
     */
    public static boolean playsForCardPoints(Contract contract) {
        return !"NULL".equals(contract.name());
    }

    /**
     * The chance that passing here ends in a Ramsch rather than in somebody
     * else's game — the factor that turns what a Ramsch costs into what passing
     * costs.
     *
     * <p>Three cases, and only the last one is estimated. Once a value stands on
     * the table somebody wants the game, so a pass makes this seat a defender,
     * and a defender scores nothing at all under classic settlement: passing is
     * then worth exactly zero and the old two-thirds threshold is back. With both
     * opponents already out, a pass hands this seat the Ramsch with certainty.
     * In between, the others still have to pass, once or twice, and
     * {@link #OPPONENT_PASSES} is how often one of them does.
     *
     * <p>Static and public because this staircase is the whole rule, and a rule
     * worth a test of its own.
     * It is also the correction to a claim made in {@code docs/rules.md} before
     * any of it was built: the break-even make chance does not simply collapse,
     * because most passes lead to somebody else's game rather than to a Ramsch.
     */
    public static double ramschRisk(int currentBid, int opponentsOut) {
        if (currentBid > 0) return 0;
        if (opponentsOut >= 2) return 1;
        return opponentsOut == 1 ? OPPONENT_PASSES : OPPONENT_PASSES * OPPONENT_PASSES;
    }

    private final class Session implements SkatAiSession {
        private final SkatAiSession blind;
        private final Random random = new Random(seed);

        /** Observations this player has dropped, decided once and then kept. */
        private final Set<Card> forgottenCards = new LinkedHashSet<>();
        private final Set<Card> rolledAlready = new LinkedHashSet<>();
        private final Map<SkatAi.Seat, Set<SkatAi.FollowClass>> knownVoids =
                new EnumMap<>(SkatAi.Seat.class);
        private final Map<SkatAi.Seat, Set<SkatAi.FollowClass>> judgedVoids =
                new EnumMap<>(SkatAi.Seat.class);

        private SkatAi.Seat mySeat;
        /**
         * Whether this player is keeping the trump and jack counters this deal.
         *
         * <p>Decided once, at the start, exactly as {@link Personality#countMemory}
         * describes: counting is the bookkeeping the game trains, so it survives
         * far better than recall of individual cards -- but a player that has lost
         * the count has lost it for the rest of the hand rather than for one
         * trick. Rolled per deal so the model sees the same mixture of present and
         * absent counters that the trainer dropped into it.
         */
        private boolean countsKept = true;
        /** The highest value the intended contract is certain to cover. */
        private int maxBid;
        private Contract intended;
        /** Make chance of {@link #intended}, measured on the dealt ten. */
        private double intendedChance;
        /** What a Ramsch on this hand is worth, in points. Negative, usually. */
        private double ramschValue;
        /**
         * The budget ran out before a single contract could be measured.
         *
         * <p>The difference between this and {@code intended == null} matters.
         * A player that measured its hand and did not like any of it should
         * pass, and does. A player that never got to look at its hand has no
         * opinion at all, and passing would be an opinion -- a false one, and
         * one that would turn every slow deal into a Ramsch. It bids the
         * delegate's way instead.
         */
        private boolean unevaluated;
        /** Opponents who have passed for good, which is how close a Ramsch is. */
        private int opponentsOut;

        Session(SkatAiSession blind) {
            this.blind = blind;
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                knownVoids.put(seat, new LinkedHashSet<>());
                judgedVoids.put(seat, new LinkedHashSet<>());
            }
        }

        // ---------------------------------------------------------------- auction

        @Override public void prepareDeal(SkatAi.DealContext context) {
            blind.prepareDeal(context);
            mySeat = context.mySeat;
            forgottenCards.clear();
            rolledAlready.clear();
            maxBid = 0;
            intended = null;
            intendedChance = 0;
            unevaluated = false;
            opponentsOut = 0;
            countsKept = random.nextDouble() < personality.countMemory();
            highestBids = new EnumMap<>(SkatAi.Seat.class);
            schieben = null;
            contra = SkatAi.ContraLevel.NONE;
            contraSeat = null;
            // Also the discard, which was missing from this list for a while.
            // The arena never noticed, because it hands every seat a fresh
            // provider per board; the app and the server keep one provider
            // across many deals, and there a declarer's discard from game N
            // survived into game N+1 as phantom "known buried cards" -- wrong
            // silently, in the way this player is always wrong silently: it
            // just guessed a little worse.
            buried = null;

            // Bid from a measured make chance rather than from a table of rules:
            // play the hand out against perfect defence a few times and see how
            // often it holds. Only the two most promising contracts are asked
            // about, because each answer costs several solves.
            List<Card> hand = new ArrayList<>(context.initialHand);
            HandEvaluator evaluator = new HandEvaluator(biddingWorlds(), random);
            // One budget for the seat, not one per contract: the tail this
            // guards against is a single solve, and splitting the allowance
            // would let a cheap first contract subsidise nothing while a
            // pathological one still ran to its own full share.
            boolean bounded = biddingBudgetNanos > 0;
            long deadline = bounded ? System.nanoTime() + biddingBudgetNanos : 0L;
            boolean measuredAnything = false;
            double best = Double.NEGATIVE_INFINITY;
            for (Contract contract : candidates(hand)) {
                if (!context.contractRules.allowedTypes.contains(
                        SkatAi.ContractType.valueOf(contract.name()))) {
                    continue;
                }
                double chance = bounded
                        ? evaluator.makeChanceBefore(contract, hand, context.mySeat,
                                context.round.forehand, deadline)
                        : evaluator.makeChance(contract, hand, context.mySeat,
                                context.round.forehand);
                // Not a number means not a single world was solved in time, which
                // is silence rather than a low chance. Scoring it as zero would
                // be reading an answer out of a question that was never asked.
                if (Double.isNaN(chance)) continue;
                measuredAnything = true;
                int value = SkatRules.guaranteedValue(contract, hand);
                double expected = declaringIsWorth(value, chance);
                if (expected > best) {
                    best = expected;
                    maxBid = value;
                    intended = contract;
                    intendedChance = chance;
                }
            }
            unevaluated = !measuredAnything;
            if (unevaluated) {
                // Nothing to price the alternative against, and the Ramsch
                // playouts would only spend more of a budget already gone.
                ramschValue = 0;
                return;
            }

            // And price the alternative on the same hand. A Ramsch is what
            // passing buys now, and pricing it with a constant would get exactly
            // the marginal hands wrong -- the ones with a jack and an ace and no
            // game, which are both the borderline bid and the likely loser.
            ramschValue = RamschEvaluator.expectedValue(context.mySeat,
                    context.initialHand, context.round.forehand, biddingWorlds(), random);
        }

        /**
         * The contracts this hand is measured on, in the order they are measured.
         *
         * <p>The two best-looking trump games, and then Null — always, and
         * always last.
         *
         * <p><b>Always</b>, because Null cannot be pre-filtered. It is scored on
         * tricks where the others are scored on points, and the cheap ranking
         * that picks the trump games has no way to compare the two; asking it to
         * anyway put Null last on 99.5% of hands and meant this player had
         * never once announced a Null — measured over 3053 auditioned deals, and
         * on the best Null holding in the pack it chose Spades. A Null solve is
         * also the cheapest of the three (measured: 291 ms against 529 for Grand
         * and 770 for Clubs, eight worlds, no native solver), so the contract
         * that could not be judged cheaply is the one that is cheap to judge.
         *
         * <p><b>Last</b>, because the budget is one deadline for the seat rather
         * than one per contract. Whatever runs out of time is dropped, so the
         * order decides what gets dropped first — and dropping Null lands
         * exactly on the behaviour this player had before, which is the right
         * thing to degrade to. Measuring it first could starve a trump game the
         * player already handles well.
         */
        private List<Contract> candidates(List<Card> hand) {
            List<Contract> order = new ArrayList<>(HandEvaluator.plausibleTrumpGames(hand, 2));
            order.add(Contract.NULL);
            return order;
        }

        /**
         * The decision, and the whole point of this rewrite: declare, or take
         * what passing leads to.
         *
         * <p>Classic settlement pays {@code +V} for a game made and charges
         * {@code −2V} for one lost, so declaring is worth
         * {@code V·p − 2V·(1−p)} with the loss weighted by temperament. Passing
         * used to be worth zero and the comparison collapsed to a threshold on
         * {@code p} — two thirds at neutral. It is not zero any more.
         */
        @Override public int bid(SkatAi.BidRequest request) {
            // A bid the contract cannot cover is a lost game whatever the cards,
            // and no expectation makes that worth saying.
            if (unevaluated) return blind.bid(request);
            if (intended == null || request.requestedBid > maxBid) return 0;
            double declaring = declaringIsWorth(maxBid, intendedChance);
            double passing = ramschRisk(request.currentBid, opponentsOut) * ramschValue;
            return declaring > passing ? request.requestedBid : 0;
        }

        private double declaringIsWorth(int value, double chance) {
            return value * chance - 2.0 * value * (1 - chance) * personality.lossWeight();
        }

        @Override public void bidObserved(SkatAi.BidEvent event) {
            if (event.passed && event.seat != mySeat) opponentsOut++;
            if (!event.passed) highestBids.merge(event.seat, event.value, Math::max);
            blind.bidObserved(event);
        }

        @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) {
            Set<Card> pushed = SkatRules.defaultRamschPush(context.hand);
            schieben = new BeliefEncoding.Schieben(List.copyOf(pushed), context.to == null,
                    List.copyOf(context.received), context.from == null);
            return pushed;
        }

        @Override public void contraObserved(SkatAi.ContraEvent event) {
            contra = event.level;
            if (event.level == SkatAi.ContraLevel.KONTRA) contraSeat = event.seat;
            blind.contraObserved(event);
        }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) { return true; }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            // Its own discard rather than the delegate's, because the right two
            // cards depend entirely on the contract: a trump game buries points
            // in a short suit, a Null buries the two highest cards. Nothing else
            // in this player would get Null right if this did not.
            List<Card> twelve = new ArrayList<>(context.hand);
            Contract forContract = intended != null
                    ? intended : HandEvaluator.plausible(twelve, 1).get(0);
            Set<Card> discarded = new LinkedHashSet<>(Discards.buried(forContract, twelve));
            if (discarded.size() != 2) discarded = blind.discardSkat(context);
            // The engine runs the exchange in a session of its own and starts
            // trick play in a fresh one, so this has to survive at the provider.
            // A declarer that forgot its own discard would sample its own buried
            // cards into an opponent's hand.
            buried = List.copyOf(discarded);
            return discarded;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
            // Announcing below the bid is an automatic loss whatever the cards, so
            // the filter comes before the preference.
            List<Card> hand = new ArrayList<>(context.hand);
            List<Contract> order = new ArrayList<>();
            if (intended != null) order.add(intended);
            order.addAll(HandEvaluator.plausible(hand, Contract.DECLARED_GAMES.length));

            Contract best = null;
            for (Contract contract : order) {
                if (!context.rules.allowedTypes.contains(
                        SkatAi.ContractType.valueOf(contract.name()))) {
                    continue;
                }
                // A contract worth less than the bid is an automatic loss whatever
                // the cards, so covering the bid comes before liking the hand.
                if (SkatRules.guaranteedValue(contract, hand) < context.winningBid) continue;
                best = contract;
                break;
            }
            if (best == null) return blind.announceContract(context);
            return SkatAi.ContractAnnouncement.skatGame(
                    SkatAi.ContractType.valueOf(best.name()));
        }

        @Override public void startGame(SkatAi.GameStartContext context) {
            blind.startGame(context);
            mySeat = context.mySeat;
        }

        // ------------------------------------------------------------- card play

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            List<Card> legal = new ArrayList<>(context.legalCards);
            if (legal.size() == 1) return legal.get(0);

            // A Ramsch has no declarer, and every question this player knows how
            // to ask is about one. Nothing here degrades gracefully to three
            // objectives, so it hands over rather than pretending.
            if (context.game.isRamsch()) return RamschPolicy.chooseCard(context);

            rememberWhatIsStillThere(context);

            List<WorldSampler.World> sampled;
            try {
                sampled = worlds.sample(evidence(context), personality.worlds(), random);
            } catch (IllegalStateException inconsistent) {
                // The position does not add up, which means this player is being
                // driven through a lifecycle it does not model. Play on rather
                // than take the table down; the delegate is always legal.
                return blind.chooseCard(context);
            }
            if (sampled.isEmpty()) return blind.chooseCard(context);

            Map<Card, Integer> votes = alphaMuScores(context, sampled);
            if (votes == null) {
                votes = new LinkedHashMap<>();
                for (Card card : legal) votes.put(card, 0);
                for (WorldSampler.World sample : sampled) castVotes(context, sample, votes);
            }

            Map<Card, Integer> scores = votes;
            Comparator<Card> byVotes = Comparator.comparingInt(card -> scores.getOrDefault(card, 0));
            Comparator<Card> byCost = Comparator.comparingInt(SkatRules::cardPoints);
            return legal.stream()
                    // Among cards that win equally often, keep the points off the
                    // table. This used to depend on the personality -- a bold
                    // player took the trick with the big card -- until the
                    // aggression sweep priced that at 4.08 game points a game
                    // against it. See Personality, where the dial used to live.
                    .max(byVotes
                            .thenComparing(byCost.reversed())
                            .thenComparing(Comparator.comparing(Card::toString).reversed()))
                    .orElse(legal.get(0));
        }

        /**
         * What this seat knows, in the shape both the model and the sampler read.
         *
         * <p>Assembled from what the player remembers rather than from what
         * happened -- the forgotten cards are already gone from
         * {@code remembered}, and the counts are recomputed from what is left, so
         * a player with a poor memory hands the model the same partial picture it
         * hands the search.
         */
        private BeliefEncoding.Evidence evidence(SkatAi.DecisionContext context) {
            Set<Card> remembered = remembered(context);
            Set<Card> hand = new LinkedHashSet<>(context.hand);
            boolean iAmDeclarer = context.game.hasDeclarer()
                    && context.mySeat == context.game.declarer;
            return new BeliefEncoding.Evidence(context, remembered, enforcedVoids(context),
                    iAmDeclarer ? buried : null, highestBids, !highestBids.isEmpty(),
                    countsKept ? BeliefEncoding.trumpsUnaccounted(context.game.contract,
                            hand, remembered) : null,
                    BeliefEncoding.jacksUnseen(hand, remembered),
                    schieben, contra, contraSeat);
        }

        /** One world's opinion: every card that still holds the contract scores. */
        /**
         * The αµ answer, or null when this decision is not one it takes.
         *
         * <p>Four conditions, and each of them is a place where the algorithm
         * either does not apply or does not pay. Null is a different game with a
         * different solver. A Ramsch has no declarer. A defender gains nothing —
         * the arena priced the ceiling on defence at zero, so there is no
         * fusion-shaped hole there worth the cost. And the first tricks are
         * unaffordable; see {@link #ALPHA_MU_FROM}.
         */
        private Map<Card, Integer> alphaMuScores(SkatAi.DecisionContext context,
                                                 List<WorldSampler.World> sampled) {
            if (alphaMuDepth < 1) return null;
            if (context.game.contract.isNull() || !context.game.hasDeclarer()) return null;
            SkatAi.Seat declarer = context.game.declarer;
            if (context.mySeat != declarer) return null;
            if (context.hand.size() > ALPHA_MU_FROM) return null;
            if (sampled.size() > 32) return null;

            // The declarer needs a different number of points in each world,
            // because each world buries different cards in the skat.
            int banked = context.derived.cardPoints.getOrDefault(declarer, 0);
            int[] targets = new int[sampled.size()];
            for (int world = 0; world < sampled.size(); world++) {
                targets[world] = personality.targetFor(
                        banked + SkatRules.cardPoints(sampled.get(world).skat()));
            }
            List<Card> trickSoFar = new ArrayList<>();
            for (SkatAi.PlayedCard play : context.currentTrick.plays) trickSoFar.add(play.card);

            AlphaMu.Ranking ranking;
            try {
                ranking = AlphaMu.rank(context.game.contract, declarer::ordinal,
                        context.mySeat::ordinal, sampled, context.currentTrick.leader::ordinal,
                        trickSoFar, targets, alphaMuDepth);
            } catch (RuntimeException unexpected) {
                // The vote is always available and always legal. A search that
                // cannot run should cost this player its edge for one card, not
                // the game.
                return null;
            }
            // Two different pieces of code decide what is legal here -- the
            // engine and the solver's own tables -- and the engine's answer is
            // the one that counts. A score for a card the engine will not accept
            // is dropped rather than trusted, and a ranking that lost cards that
            // way is abandoned entirely.
            Map<Card, Integer> scores = new LinkedHashMap<>();
            for (Map.Entry<Card, Integer> scored : ranking.worldsWon().entrySet()) {
                if (!context.legalCards.contains(scored.getKey())) return null;
                scores.put(scored.getKey(), scored.getValue());
            }
            return scores.size() == context.legalCards.size() ? scores : null;
        }

        private void castVotes(SkatAi.DecisionContext context, WorldSampler.World sample,
                               Map<Card, Integer> votes) {
            SkatAi.Seat declarer = context.game.declarer;
            boolean iAmTheDeclarer = context.mySeat == declarer;
            List<Card> played = new ArrayList<>(3);
            for (SkatAi.PlayedCard play : context.currentTrick.plays) played.add(play.card);

            // Null asks a different question, so it gets a different solver. The
            // declarer wants no trick at all, and a vote counted in card points
            // would recommend the opposite of the right card.
            if (!playsForCardPoints(context.game.contract)) {
                for (NullSolver.Verdict verdict : NullSolver.movesSurviving(declarer,
                        context.mySeat, sample.hands(), context.currentTrick.leader, played)) {
                    if (verdict.declarerSurvives() == iAmTheDeclarer
                            && votes.containsKey(verdict.card())) {
                        votes.merge(verdict.card(), 1, Integer::sum);
                    }
                }
                return;
            }

            int banked = context.derived.cardPoints.getOrDefault(declarer, 0)
                    + SkatRules.cardPoints(sample.skat());
            int target = personality.targetFor(banked);

            List<DoubleDummySolver.Verdict> verdicts = DoubleDummySolver.movesReaching(
                    context.game.contract, declarer, context.mySeat, sample.hands(),
                    context.currentTrick.leader, played, target);
            for (DoubleDummySolver.Verdict verdict : verdicts) {
                if (verdict.reachesTarget() == iAmTheDeclarer
                        && votes.containsKey(verdict.card())) {
                    votes.merge(verdict.card(), 1, Integer::sum);
                }
            }
        }

        // ----------------------------------------------------------------- memory

        /**
         * Decides once per observation whether it sticks.
         *
         * <p>Rolled when the fact first appears and then kept, because a memory
         * that flickers from trick to trick is not what forgetting looks like.
         * Cards lying in the current trick are never forgotten: they are face up
         * on the table.
         */
        private void rememberWhatIsStillThere(SkatAi.DecisionContext context) {
            if (personality.memory() >= 1.0) return;
            for (Card card : context.derived.playedCards) {
                if (forgottenCards.contains(card)) continue;
                if (rolledAlready.contains(card)) continue;
                rolledAlready.add(card);
                if (random.nextDouble() > personality.memory()) forgottenCards.add(card);
            }
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                Set<SkatAi.FollowClass> shown = context.derived.voidClasses.get(seat);
                if (shown == null) continue;
                for (SkatAi.FollowClass followClass : shown) {
                    if (!judgedVoids.get(seat).add(followClass)) continue;
                    if (random.nextDouble() <= personality.memory()) {
                        knownVoids.get(seat).add(followClass);
                    }
                }
            }
        }

        /**
         * The voids this player still knows about. A perfect memory keeps all of
         * them -- which has to be said explicitly, because the forgetting roll
         * below never runs at all in that case.
         */
        private Map<SkatAi.Seat, Set<SkatAi.FollowClass>> enforcedVoids(
                SkatAi.DecisionContext context) {
            return personality.memory() >= 1.0 ? context.derived.voidClasses : knownVoids;
        }

        /** Played cards this player still knows about, plus everything face up. */
        private Set<Card> remembered(SkatAi.DecisionContext context) {
            if (personality.memory() >= 1.0) return context.derived.playedCards;
            Set<Card> kept = new LinkedHashSet<>(context.derived.playedCards);
            kept.removeAll(forgottenCards);
            for (SkatAi.PlayedCard play : context.currentTrick.plays) kept.add(play.card);
            return kept;
        }

        @Override public void cardPlayed(SkatAi.CardPlayedEvent event) { blind.cardPlayed(event); }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            blind.trickCompleted(event);
        }

        @Override public void endGame(SkatAi.GameResult result) { blind.endGame(result); }
        @Override public void close() { blind.close(); }
    }
}
