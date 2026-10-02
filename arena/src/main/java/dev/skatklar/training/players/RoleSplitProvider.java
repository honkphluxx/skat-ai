package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.TableObserver;
import java.util.Set;

/**
 * One seat played by two players: one when it declares, the other when it
 * defends (docs/training-plan.md 2.10, R: what better defence is worth).
 *
 * <p>Spelled {@code roles:<declaring>/<defending>} in the arena, e.g.
 * {@code roles:belief-64-shipped/skatzero}: our 64-world search declares,
 * SkatZero defends. Everything before the first card -- bidding, the skat,
 * naming the game, Schieben, Kontra -- belongs to the declaring player, since
 * that is the side of the game which decides it. At each card the seat asks
 * whichever of the two its role in this game names; a Ramsch, which has no
 * declarer, goes to the declaring player.
 *
 * <p>Both players hear everything: every event of every session goes to both,
 * so the one not playing this game keeps a correct picture and the one that
 * plays never misses a card. The engine-watching hooks ({@link TableObserver})
 * are passed on to whichever of the two wants them -- an outside engine needs
 * the hands to set up its own game, and nothing else here reads them.
 */
public final class RoleSplitProvider implements SkatAiProvider, TableObserver {

    private final String id;
    private final SkatAiProvider declaring;
    private final SkatAiProvider defending;

    public RoleSplitProvider(String id, SkatAiProvider declaring, SkatAiProvider defending) {
        this.id = id;
        this.declaring = declaring;
        this.defending = defending;
    }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor(id, id, true);
    }

    @Override public void observe(GameEngine engine) {
        if (declaring instanceof TableObserver o) o.observe(engine);
        if (defending instanceof TableObserver o) o.observe(engine);
    }

    @Override public void observeFixedContract(Board board, ContractSource.FixedContract fixed) {
        if (declaring instanceof TableObserver o) o.observeFixedContract(board, fixed);
        if (defending instanceof TableObserver o) o.observeFixedContract(board, fixed);
    }

    @Override public SkatAiSession createSession() {
        SkatAiSession decl = declaring.createSession();
        SkatAiSession def = defending.createSession();
        return new SkatAiSession() {
            @Override public void prepareDeal(SkatAi.DealContext context) {
                decl.prepareDeal(context);
                def.prepareDeal(context);
            }
            @Override public int bid(SkatAi.BidRequest request) { return decl.bid(request); }
            @Override public void bidObserved(SkatAi.BidEvent event) {
                decl.bidObserved(event);
                def.bidObserved(event);
            }
            @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) { return decl.pickUpSkat(context); }
            @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                return decl.discardSkat(context);
            }
            @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
                return decl.announceContract(context);
            }
            @Override public boolean takeUpPush(SkatAi.RamschTakeUpContext context) { return decl.takeUpPush(context); }
            @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) { return decl.pushCards(context); }
            @Override public void ramschPushObserved(SkatAi.RamschPushEvent event) {
                decl.ramschPushObserved(event);
                def.ramschPushObserved(event);
            }
            @Override public boolean announceContra(SkatAi.ContraContext context) {
                return decl.announceContra(context);
            }
            @Override public void contraObserved(SkatAi.ContraEvent event) {
                decl.contraObserved(event);
                def.contraObserved(event);
            }
            @Override public void startGame(SkatAi.GameStartContext context) {
                decl.startGame(context);
                def.startGame(context);
            }
            @Override public Card chooseCard(SkatAi.DecisionContext context) {
                return defends(context) ? def.chooseCard(context) : decl.chooseCard(context);
            }
            @Override public void cardPlayed(SkatAi.CardPlayedEvent event) {
                decl.cardPlayed(event);
                def.cardPlayed(event);
            }
            @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
                decl.trickCompleted(event);
                def.trickCompleted(event);
            }
            @Override public void endGame(SkatAi.GameResult result) {
                decl.endGame(result);
                def.endGame(result);
            }
            @Override public void close() {
                try {
                    decl.close();
                } finally {
                    def.close();
                }
            }
        };
    }

    /** True when this seat is a defender of a declared game. */
    static boolean defends(SkatAi.DecisionContext context) {
        return context.game.hasDeclarer() && context.mySeat != context.game.declarer;
    }
}
