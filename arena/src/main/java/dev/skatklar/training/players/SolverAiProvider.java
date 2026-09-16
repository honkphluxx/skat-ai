package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.ramsch.RamschPolicy;
import dev.skatklar.demo.search.Discards;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.solve.NullSolver;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.TableObserver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The par baseline: a player that sees every hand and plays perfectly.
 *
 * <p>It is not a candidate for shipping and never will be. It exists to put a
 * ceiling on the measurement. Until now the arena could only say that one
 * mediocre program beats another by so many tournament points; with this seated
 * on the other side, every honest player's score reads as a measured distance
 * below optimal play on the same boards.
 *
 * <p>Two properties are worth being precise about, because they make its score
 * an upper bound rather than a target:
 *
 * <ul>
 *   <li>It cheats in card play and, when the contract is known before the
 *       discard, in the discard. Bidding and the auction-game discard are
 *       delegated to {@link GreedyAiProvider}, so those decisions are made blind
 *       like anyone else's. In a fixed-contract match the arena tells it the
 *       contract first ({@link TableObserver#observeFixedContract}) and it then
 *       discards by the solver: the first of the 66 pairs that makes Schneider
 *       against perfect defence, else the first that makes the game, else the
 *       heuristic pair. Until 2026-09-15 the discard was always the heuristic's,
 *       and the "distance to omniscience" it produced was not one: at oracle
 *       contracts the cheat won only 85% of its declarations and both honest
 *       players measured level with it.</li>
 *   <li>As a defender it plays as if <b>both defenders were one player</b> with a
 *       shared view of all 30 cards. That is the standard double-dummy treatment,
 *       and it flatters the defence beyond what any real pair can coordinate. A
 *       human or a program facing it is behind by more than its own mistakes.</li>
 * </ul>
 *
 * <p>Consequently: a gap to this player is a valid ceiling distance, but closing
 * it completely is not a goal, and "beat the solver" is not a coherent one.
 */
public final class SolverAiProvider implements SkatAiProvider, TableObserver {

    private final SkatAiProvider delegate;
    private GameEngine engine;
    private Board board;
    private ContractSource.FixedContract fixed;

    /**
     * Discards already solved, by deal, declarer and contract. A duplicate
     * match plays every board from all three seats, so the same twelve cards
     * at the same contract come round three times per side; the solve is the
     * expensive part of the game and there is no reason to repeat it.
     */
    private static final Map<String, Set<Card>> DISCARDS = new ConcurrentHashMap<>();
    private static final int DISCARD_CACHE_LIMIT = 20_000;
    /** Card points the declarer must reach, skat included; and to hold the defence to Schneider. */
    private static final int WINNING_POINTS = 61;
    private static final int SCHNEIDER_POINTS = 90;

    public SolverAiProvider() {
        this(new GreedyAiProvider());
    }

    /**
     * @param delegate makes every decision that is not a card play. Its bidding
     *                 quality therefore sets which contracts this player defends
     *                 or declares -- in {@code --fixed-contract} mode, where the
     *                 contract comes from outside, it only matters for the discard.
     */
    public SolverAiProvider(SkatAiProvider delegate) {
        this.delegate = delegate;
    }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor("solver", "Double-dummy par (cheats)", true);
    }

    @Override public void observe(GameEngine engine) {
        this.engine = engine;
    }

    @Override public void observeFixedContract(Board board, ContractSource.FixedContract fixed) {
        this.board = board;
        this.fixed = fixed;
    }

    /**
     * The perfect-information discard for a known contract, or {@code null}
     * when this player does not hold the contract (or was never told it).
     *
     * <p>Two null-window questions per candidate pair at most, and usually far
     * fewer: the first pass asks every pair whether it makes Schneider, which is
     * refuted almost at once on most of them, and only if none does is the
     * second pass, "does it make the game", asked. The heuristic's own pair is
     * asked first in each pass, so where several pairs are equally good the
     * cheat keeps the honest choice, and a board it cannot win at all is played
     * from the same ten cards an honest player would hold. Schwarz is not asked
     * for; it is rare enough at a fixed contract not to move the ceiling.
     */
    private Set<Card> solvedDiscard(SkatAi.SkatExchangeContext context) {
        ContractSource.FixedContract known = fixed;
        Board table = board;
        if (known == null || table == null || known.declarer() != context.mySeat) return null;
        Contract contract = known.contract();
        SkatAi.Seat declarer = context.mySeat;
        SkatAi.Seat leader = table.round().forehand;
        List<Card> twelve = new ArrayList<>(context.hand);
        String key = twelve + "|" + table.deal().human + table.deal().opponentOne
                + table.deal().opponentTwo + "|" + contract + "|" + declarer + "|" + leader;
        Set<Card> cached = DISCARDS.get(key);
        if (cached != null) return cached;

        List<List<Card>> dealt = List.of(
                new ArrayList<>(table.deal().human),
                new ArrayList<>(table.deal().opponentOne),
                new ArrayList<>(table.deal().opponentTwo));
        List<Set<Card>> pairs = candidatePairs(contract, twelve);
        Set<Card> answer = null;
        if (contract.isNull()) {
            for (Set<Card> pair : pairs) {
                if (NullSolver.declarerSurvives(declarer, handsAfter(dealt, declarer, twelve, pair), leader)) {
                    answer = pair;
                    break;
                }
            }
        } else {
            for (int target : new int[] {SCHNEIDER_POINTS, WINNING_POINTS}) {
                for (Set<Card> pair : pairs) {
                    int buried = SkatRules.cardPoints(pair);
                    if (DoubleDummySolver.declarerReaches(contract, declarer,
                            handsAfter(dealt, declarer, twelve, pair), leader, target - buried)) {
                        answer = pair;
                        break;
                    }
                }
                if (answer != null) break;
            }
        }
        if (answer == null) answer = pairs.get(0);
        if (DISCARDS.size() >= DISCARD_CACHE_LIMIT) DISCARDS.clear();
        DISCARDS.put(key, answer);
        return answer;
    }

    /** All 66 pairs of the twelve cards, the heuristic's pair first, the rest in hand order. */
    private static List<Set<Card>> candidatePairs(Contract contract, List<Card> twelve) {
        List<Set<Card>> pairs = new ArrayList<>(66);
        Set<Card> heuristic = new LinkedHashSet<>(Discards.buried(contract, twelve));
        pairs.add(heuristic);
        for (int i = 0; i < twelve.size(); i++) {
            for (int j = i + 1; j < twelve.size(); j++) {
                Set<Card> pair = new LinkedHashSet<>(List.of(twelve.get(i), twelve.get(j)));
                if (!pair.equals(heuristic)) pairs.add(pair);
            }
        }
        return pairs;
    }

    private static List<List<Card>> handsAfter(List<List<Card>> dealt, SkatAi.Seat declarer,
                                               List<Card> twelve, Set<Card> discard) {
        List<Card> keep = new ArrayList<>(twelve);
        keep.removeAll(discard);
        List<List<Card>> hands = new ArrayList<>(3);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            hands.add(seat == declarer ? keep : dealt.get(seat.ordinal()));
        }
        return hands;
    }

    @Override public SkatAiSession createSession() {
        return new Session(delegate.createSession());
    }

    private final class Session implements SkatAiSession {
        private final SkatAiSession blind;

        Session(SkatAiSession blind) {
            this.blind = blind;
        }

        @Override public void prepareDeal(SkatAi.DealContext context) { blind.prepareDeal(context); }
        @Override public int bid(SkatAi.BidRequest request) { return blind.bid(request); }
        @Override public void bidObserved(SkatAi.BidEvent event) { blind.bidObserved(event); }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            return blind.pickUpSkat(context);
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            Set<Card> solved = solvedDiscard(context);
            if (solved == null) return blind.discardSkat(context);
            // The delegate still sees the exchange, so its own bookkeeping of
            // the hand it will not be asked to play stays consistent.
            try { blind.discardSkat(context); } catch (RuntimeException ignored) {}
            return solved;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
            return blind.announceContract(context);
        }

        @Override public void startGame(SkatAi.GameStartContext context) { blind.startGame(context); }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            if (context.legalCards.size() == 1) {
                return context.legalCards.iterator().next();
            }
            // The double-dummy solver answers "does the declarer reach 61". A
            // Ramsch has neither, so even this cheating reference has nothing to
            // compute and plays the same heuristic as everyone else.
            if (context.game.isRamsch()) return RamschPolicy.chooseCard(context);
            GameEngine table = engine;
            // Without a table this is simply the delegate. That keeps the class
            // usable outside the arena and makes a missing observe() call show up
            // as a weaker player rather than as a crash halfway through a match.
            if (table == null) return blind.chooseCard(context);

            GameEngine.Snapshot snapshot = table.snapshot();
            List<Card> trickSoFar = new ArrayList<>(3);
            for (SkatAi.PlayedCard play : snapshot.trick) trickSoFar.add(play.card);

            // Null is a different game: the double-dummy solver refuses it
            // (and the native one, asked anyway, answered with a points
            // search that means nothing there -- the cheat won a fifth of
            // its oracle Nulls before this branch existed). The Null solver
            // says, per card, whether the declarer still survives; the
            // declarer plays one that does and a defender one after which
            // it does not, and where no card changes the verdict the choice
            // does not matter and the delegate's will do.
            if (context.game.contract.isNull()) {
                boolean declaring = context.mySeat == context.game.declarer;
                for (NullSolver.Verdict verdict : NullSolver.movesSurviving(context.game.declarer,
                        context.mySeat, snapshot.hands, SkatAi.Seat.values()[snapshot.leader], trickSoFar)) {
                    if (verdict.declarerSurvives() == declaring && context.legalCards.contains(verdict.card())) {
                        return verdict.card();
                    }
                }
                return blind.chooseCard(context);
            }

            // What the declarer has already banked, skat included -- the engine
            // credits the skat to the declarer at settlement, so a solver that
            // ignored it would defend the wrong threshold by up to 22 points.
            int banked = snapshot.capturedPoints.getOrDefault(context.game.declarer, 0)
                    + SkatRules.cardPoints(snapshot.skat);

            DoubleDummySolver.Choice choice = DoubleDummySolver.bestCardForResult(
                    context.game.contract,
                    context.game.declarer,
                    context.mySeat,
                    snapshot.hands,
                    SkatAi.Seat.values()[snapshot.leader],
                    trickSoFar,
                    banked);

            // The solver knows the rules of following suit, so its answer is
            // legal by construction. Checking anyway costs nothing per trick and
            // turns a future rules divergence between ContractTables and
            // SkatRules into a visible fallback instead of a rule violation.
            return context.legalCards.contains(choice.card())
                    ? choice.card() : blind.chooseCard(context);
        }

        @Override public void cardPlayed(SkatAi.CardPlayedEvent event) { blind.cardPlayed(event); }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            blind.trickCompleted(event);
        }

        @Override public void endGame(SkatAi.GameResult result) { blind.endGame(result); }
        @Override public void close() { blind.close(); }
    }
}
