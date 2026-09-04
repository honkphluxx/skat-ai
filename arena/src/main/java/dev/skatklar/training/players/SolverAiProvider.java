package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.ramsch.RamschPolicy;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.training.arena.TableObserver;
import java.util.ArrayList;
import java.util.List;

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
 *   <li>It cheats <b>only in card play</b>. Bidding, the skat pickup and the
 *       discard are delegated to {@link GreedyAiProvider}, so those decisions are
 *       made blind like anyone else's. A perfect-information discard would mean
 *       66 full-deal solves per game, and it would confound the number: the point
 *       of a card-play ceiling is that only card play is optimal.</li>
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

        @Override public java.util.Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            return blind.discardSkat(context);
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
