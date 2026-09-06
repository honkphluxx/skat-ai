package dev.skatklar.demo.ai;

import dev.skatklar.demo.Card;
import java.util.Set;

/**
 * One player-component lifecycle. Pre-game and trick-play sessions may intentionally
 * be separate, which also supports remote and stateless implementations.
 */
public interface SkatAiSession extends AutoCloseable {
    default void prepareDeal(SkatAi.DealContext context) {}
    /** Returns the accepted bid value, or zero to pass. */
    default int bid(SkatAi.BidRequest request) { return 0; }
    default void bidObserved(SkatAi.BidEvent event) {}
    default boolean pickUpSkat(SkatAi.SkatChoiceContext context) { return true; }
    default Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
        throw new UnsupportedOperationException("Skat exchange is not supported");
    }
    default SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
        throw new UnsupportedOperationException("Contract announcement is not supported");
    }
    /**
     * Look at the two cards, or push them on unopened.
     *
     * <p>Asked once per leg, before {@link #pushCards}, and the two cards are
     * not shown while it is being asked — see {@link SkatAi.RamschTakeUpContext}
     * for why. Answering false ends this seat's leg then and there: the pair it
     * was handed travels on untouched and {@link #pushCards} is never asked.
     *
     * <p>TODO: every player in this project answers true. Pushing on unopened is
     * a live option in the Schieberamsch and there is no reasoning behind it
     * here yet, in the players or in the belief model; the default keeps the
     * game as it has always been played until there is.
     */
    default boolean takeUpPush(SkatAi.RamschTakeUpContext context) { return true; }

    /**
     * One leg of the Schieberamsch: the two cards to push on. Jacks may never be
     * pushed, and the engine substitutes a legal pair for anything else.
     *
     * <p>Defaulted rather than abstract, and defaulted to something defensible
     * rather than to an exception, because a Ramsch is now unavoidable — every
     * player that ever passes will be asked this. The default sheds the two most
     * expensive non-jacks, which is the whole idea of pushing in a Ramsch.
     */
    default Set<Card> pushCards(SkatAi.RamschPushContext context) {
        return dev.skatklar.demo.SkatRules.defaultRamschPush(context.hand);
    }

    /** Two cards changed hands. Which two is private to the seats that held them. */
    default void ramschPushObserved(SkatAi.RamschPushEvent event) {}

    /**
     * Kontra, or Re when {@code context.answeringKontra} is set. Asked once, in
     * the one moment the rules allow it: before this seat plays its first card.
     */
    default boolean announceContra(SkatAi.ContraContext context) { return false; }

    default void contraObserved(SkatAi.ContraEvent event) {}

    default void startGame(SkatAi.GameStartContext context) {}
    Card chooseCard(SkatAi.DecisionContext context);
    default void cardPlayed(SkatAi.CardPlayedEvent event) {}
    default void trickCompleted(SkatAi.TrickCompletedEvent event) {}
    default void endGame(SkatAi.GameResult result) {}
    @Override default void close() {}
}
