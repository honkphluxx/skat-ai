package dev.skatklar.demo.ai;

import dev.skatklar.demo.Card;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One provider per seat, behind the single-provider interface the engine and
 * the app already speak.
 *
 * <p>Why this has to exist: {@code SearchAiProvider} keeps evidence <em>on the
 * provider</em> — the bids it heard, the cards it pushed in a Schieberamsch, its
 * own discard — because the engine runs the auction and the trick play in
 * separate sessions and a session-level memory does not survive the hand-over.
 * The engine, in turn, seats one {@code SkatAiProvider} at every AI seat when it
 * is given only one. Put together, two AI seats at the same table shared one
 * memory: in a Schieberamsch, each seat's record of its own private push was
 * overwritten by the other's, so each ended up reasoning from cards it had no
 * right to know. Not a crash — a quiet little cheat.
 *
 * <p>The fix keeps both existing contracts. This is one {@code SkatAiProvider},
 * so nothing about the app or the engine changes; inside, each seat gets its own
 * delegate, created on first use. A session finds out which seat it is from the
 * first context the engine hands it — every session lifecycle starts with either
 * {@code prepareDeal} or {@code startGame}, and both name the seat — and from
 * then on it belongs to that seat's delegate. A callback arriving before the
 * seat is known would mean the engine broke that lifecycle, and throws rather
 * than guesses.
 */
public final class PerSeatAiProvider implements SkatAiProvider {

    private final Map<SkatAi.Seat, SkatAiProvider> bySeat = new EnumMap<>(SkatAi.Seat.class);

    /** Builds the delegate for each seat, called once per seat on first use. */
    public PerSeatAiProvider(Function<SkatAi.Seat, SkatAiProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            bySeat.put(seat, Objects.requireNonNull(providers.apply(seat), seat.name()));
        }
    }

    /** The delegate occupying {@code seat}, for tests and for curious tooling. */
    public SkatAiProvider forSeat(SkatAi.Seat seat) {
        return bySeat.get(seat);
    }

    @Override public SkatAi.AiDescriptor descriptor() {
        return bySeat.get(SkatAi.Seat.HUMAN).descriptor();
    }

    @Override public SkatAiSession createSession() {
        return new SeatBindingSession();
    }

    /** Delegates every call to the session of whichever seat announced itself first. */
    private final class SeatBindingSession implements SkatAiSession {

        private SkatAiSession inner;

        private SkatAiSession bind(SkatAi.Seat seat) {
            if (inner == null) inner = bySeat.get(seat).createSession();
            return inner;
        }

        private SkatAiSession bound() {
            if (inner == null) {
                throw new IllegalStateException(
                        "A session was consulted before any context named its seat");
            }
            return inner;
        }

        @Override public void prepareDeal(SkatAi.DealContext context) {
            bind(context.mySeat).prepareDeal(context);
        }

        @Override public void startGame(SkatAi.GameStartContext context) {
            bind(context.mySeat).startGame(context);
        }

        @Override public int bid(SkatAi.BidRequest request) { return bound().bid(request); }

        @Override public void bidObserved(SkatAi.BidEvent event) { bound().bidObserved(event); }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            return bound().pickUpSkat(context);
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            return bound().discardSkat(context);
        }

        @Override public SkatAi.ContractAnnouncement announceContract(
                SkatAi.ContractContext context) {
            return bound().announceContract(context);
        }

        @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) {
            return bound().pushCards(context);
        }

        @Override public void ramschPushObserved(SkatAi.RamschPushEvent event) {
            bound().ramschPushObserved(event);
        }

        @Override public boolean announceContra(SkatAi.ContraContext context) {
            return bound().announceContra(context);
        }

        @Override public void contraObserved(SkatAi.ContraEvent event) {
            bound().contraObserved(event);
        }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            return bound().chooseCard(context);
        }

        @Override public void cardPlayed(SkatAi.CardPlayedEvent event) {
            bound().cardPlayed(event);
        }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            bound().trickCompleted(event);
        }

        @Override public void endGame(SkatAi.GameResult result) { bound().endGame(result); }

        @Override public void close() {
            if (inner != null) inner.close();
        }
    }
}
