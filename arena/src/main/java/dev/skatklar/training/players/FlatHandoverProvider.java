package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.TableObserver;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Our search, except where it is lost: the declarer's decisions in which every
 * legal card loses in every world go to another player (docs/training-plan.md
 * 2.10, R: where SkatZero's declaring is better).
 *
 * <p>Spelled {@code flat:<search>/<rescuer>} in the arena, e.g.
 * {@code flat:belief-64-shipped/skatzero}. The search plays everything as it
 * always does -- the auction, the skat, defence, and every declarer card where
 * at least one card holds the game in at least one world. Where its own vote
 * is zero for every legal card (a position lost against perfect defence in
 * every world it drew), the search has nothing to choose by and falls back to
 * its tiebreaks and the ladder; this hands those decisions to the rescuer
 * instead. The defending audit and the defence split put the difference
 * between SkatZero's declaring and ours there: the double-dummy question
 * cannot prefer the card that gives real defenders the most room to go
 * wrong, and a player trained on games as they end can.
 *
 * <p>The search is asked first, every time, and its vote is read from its own
 * session through {@link SearchAiProvider.CardPlayObserver#voted}; only then,
 * and only on a flat position, is the rescuer asked, so a rescuer that keeps a
 * game of its own (an outside engine) is asked exactly for the cards it plays
 * and told of every other one. Both hear every event, as in
 * {@link RoleSplitProvider}.
 */
public final class FlatHandoverProvider implements SkatAiProvider, TableObserver {

    private static final AtomicLong DECLARER_DECISIONS = new AtomicLong();
    private static final AtomicLong HANDED = new AtomicLong();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (DECLARER_DECISIONS.get() > 0) {
                System.out.printf("Flat handover: %,d of %,d declarer decisions handed to the rescuer (%.1f%%)%n",
                        HANDED.get(), DECLARER_DECISIONS.get(),
                        100.0 * HANDED.get() / DECLARER_DECISIONS.get());
            }
        }));
    }

    private final String id;
    private final SkatAiProvider search;
    private final SkatAiProvider rescuer;
    /** The search's last vote, delivered on the deciding thread inside its chooseCard. */
    private Map<Card, Integer> lastVotes;
    private final BiConsumer<Map<Card, Integer>, Boolean> listener;

    public FlatHandoverProvider(String id, SkatAiProvider search, SkatAiProvider rescuer) {
        this(id, search, rescuer, null);
    }

    /** @param listener told, at every declarer decision, the search's votes and whether it was handed over */
    FlatHandoverProvider(String id, SkatAiProvider search, SkatAiProvider rescuer,
                         BiConsumer<Map<Card, Integer>, Boolean> listener) {
        if (!(search instanceof SearchAiProvider searching)) {
            throw new IllegalArgumentException(id + ": the first player must be a search player, not "
                    + search.descriptor().id);
        }
        this.id = id;
        this.search = searching.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
            @Override public void decided(SearchAiProvider.CardPlayReport report) {}
            @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                        Map<Card, Integer> cushion, int worldsSearched,
                                        boolean cushionAsked, Card chosen) {
                lastVotes = votes;
            }
        });
        this.rescuer = rescuer;
        this.listener = listener;
    }

    static long handed() { return HANDED.get(); }
    static long declarerDecisions() { return DECLARER_DECISIONS.get(); }

    @Override public SkatAi.AiDescriptor descriptor() { return new SkatAi.AiDescriptor(id, id, true); }

    @Override public void observe(GameEngine engine) {
        if (search instanceof TableObserver o) o.observe(engine);
        if (rescuer instanceof TableObserver o) o.observe(engine);
    }

    @Override public void observeFixedContract(Board board, ContractSource.FixedContract fixed) {
        if (search instanceof TableObserver o) o.observeFixedContract(board, fixed);
        if (rescuer instanceof TableObserver o) o.observeFixedContract(board, fixed);
    }

    /** True when the search drew worlds and no legal card held the game in any of them. */
    static boolean flat(Map<Card, Integer> votes, Set<Card> legal) {
        if (votes == null || votes.isEmpty()) return false;
        for (Card card : legal) {
            if (votes.getOrDefault(card, 0) > 0) return false;
        }
        return true;
    }

    @Override public SkatAiSession createSession() {
        SkatAiSession main = search.createSession();
        SkatAiSession other = rescuer.createSession();
        return new SkatAiSession() {
            @Override public void prepareDeal(SkatAi.DealContext context) {
                main.prepareDeal(context);
                other.prepareDeal(context);
            }
            @Override public int bid(SkatAi.BidRequest request) { return main.bid(request); }
            @Override public void bidObserved(SkatAi.BidEvent event) {
                main.bidObserved(event);
                other.bidObserved(event);
            }
            @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) { return main.pickUpSkat(context); }
            @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                return main.discardSkat(context);
            }
            @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
                return main.announceContract(context);
            }
            @Override public boolean takeUpPush(SkatAi.RamschTakeUpContext context) { return main.takeUpPush(context); }
            @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) { return main.pushCards(context); }
            @Override public void ramschPushObserved(SkatAi.RamschPushEvent event) {
                main.ramschPushObserved(event);
                other.ramschPushObserved(event);
            }
            @Override public boolean announceContra(SkatAi.ContraContext context) {
                return main.announceContra(context);
            }
            @Override public void contraObserved(SkatAi.ContraEvent event) {
                main.contraObserved(event);
                other.contraObserved(event);
            }
            @Override public void startGame(SkatAi.GameStartContext context) {
                main.startGame(context);
                other.startGame(context);
            }
            @Override public Card chooseCard(SkatAi.DecisionContext context) {
                lastVotes = null;
                Card searched = main.chooseCard(context);
                boolean declares = context.game.hasDeclarer() && context.mySeat == context.game.declarer;
                if (!declares || context.legalCards.size() < 2) return searched;
                Map<Card, Integer> votes = lastVotes;
                boolean hand = flat(votes, context.legalCards);
                DECLARER_DECISIONS.incrementAndGet();
                if (listener != null) listener.accept(votes, hand);
                if (!hand) return searched;
                HANDED.incrementAndGet();
                Card rescued = other.chooseCard(context);
                return context.legalCards.contains(rescued) ? rescued : searched;
            }
            @Override public void cardPlayed(SkatAi.CardPlayedEvent event) {
                main.cardPlayed(event);
                other.cardPlayed(event);
            }
            @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
                main.trickCompleted(event);
                other.trickCompleted(event);
            }
            @Override public void endGame(SkatAi.GameResult result) {
                main.endGame(result);
                other.endGame(result);
            }
            @Override public void close() {
                try {
                    main.close();
                } finally {
                    other.close();
                }
            }
        };
    }
}
