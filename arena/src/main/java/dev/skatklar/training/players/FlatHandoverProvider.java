package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.TableObserver;
import java.util.List;
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
 *
 * <p>The rescuer can also be our own: {@code flat:<search>/rollout} (or
 * {@code rollout-<worlds>}) plays the lost position out in the search's own
 * sampled worlds against realistic defenders ({@link RolloutRescue}) instead
 * of asking another player.
 *
 * <p>With the play-outs the hand-over can reach further ({@link Scope}, spelled
 * {@code po:<search>/<rollout>/<scope>}): the defenders' lost positions too
 * (every card lets the declarer through against perfect play), and positions
 * that are not lost but tied -- the cards whose vote is within {@code k}
 * worlds of the best, when there are two or more of them. There the search's
 * vote stays the safety net: the play-outs only choose among the cards it
 * already rates (near) equally safe, by game value against realistic
 * opponents -- a Schneider made or avoided, which the vote cannot see.
 */
public final class FlatHandoverProvider implements SkatAiProvider, TableObserver {

    /**
     * Which decisions are handed to the play-outs: lost positions as declarer
     * and as defender, and ties within {@code k} worlds of the top vote as
     * declarer and as defender ({@code -1}: never).
     */
    public record Scope(boolean lostDeclarer, boolean lostDefender, int tieDeclarer, int tieDefender) {
        /** The first form: the declarer's lost positions and nothing else. */
        public static final Scope LOST_DECLARER = new Scope(true, false, -1, -1);

        /** {@code lost-decl,lost-def,tie0-decl,tie2-def}: any of them, comma-separated. */
        public static Scope parse(String text) {
            boolean lostDecl = false;
            boolean lostDef = false;
            int tieDecl = -1;
            int tieDef = -1;
            for (String part : text.split(",")) {
                String p = part.trim();
                if (p.equals("lost-decl")) lostDecl = true;
                else if (p.equals("lost-def")) lostDef = true;
                else if (p.matches("tie\\d+-decl")) tieDecl = Integer.parseInt(p.substring(3, p.indexOf('-')));
                else if (p.matches("tie\\d+-def")) tieDef = Integer.parseInt(p.substring(3, p.indexOf('-')));
                else throw new IllegalArgumentException("a scope is lost-decl, lost-def, tie<k>-decl, tie<k>-def; not " + p);
            }
            return new Scope(lostDecl, lostDef, tieDecl, tieDef);
        }

        boolean covers(boolean declares) {
            return declares ? lostDeclarer || tieDeclarer >= 0 : lostDefender || tieDefender >= 0;
        }
    }

    /** Per kind of hand-over: decisions looked at, handed over, card changed. Kinds: lost/tie x declarer/defender. */
    private static final String[] KINDS = {"lost, declarer", "lost, defender", "tied, declarer", "tied, defender"};
    private static final AtomicLong[] SEEN = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] KIND_HANDED = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] KIND_CHANGED = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private static final AtomicLong DECLARER_DECISIONS = new AtomicLong();
    private static final AtomicLong HANDED = new AtomicLong();
    /** Handed decisions where the rescuer's card was not the search's own. */
    private static final AtomicLong CHANGED = new AtomicLong();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (DECLARER_DECISIONS.get() > 0) {
                long decisions = SEEN[0].get() + SEEN[1].get();
                System.out.printf("Flat handover: %,d of %,d %s decisions handed to the rescuer (%.1f%%)%n",
                        HANDED.get(), decisions, SEEN[1].get() == 0 ? "declarer" : "declarer and defender",
                        100.0 * HANDED.get() / Math.max(1, decisions));
                System.out.printf("  the rescuer's card differed from the search's in %,d of them (%.1f%%)%n",
                        CHANGED.get(), 100.0 * CHANGED.get() / Math.max(1, HANDED.get()));
                for (int k = 0; k < KINDS.length; k++) {
                    if (KIND_HANDED[k].get() == 0) continue;
                    System.out.printf("  %s: %,d of %,d decisions of that role handed over (%.1f%%), card changed in %,d (%.1f%%)%n",
                            KINDS[k], KIND_HANDED[k].get(), SEEN[k].get(),
                            100.0 * KIND_HANDED[k].get() / Math.max(1, SEEN[k].get()), KIND_CHANGED[k].get(),
                            100.0 * KIND_CHANGED[k].get() / Math.max(1, KIND_HANDED[k].get()));
                }
                RolloutRescue.report();
            }
        }));
    }

    private final String id;
    private final SkatAiProvider search;
    /** Another player as the rescuer, or null when the play-outs are. */
    private final SkatAiProvider rescuer;
    private final RolloutRescue rollout;
    /** The search's last vote, delivered on the deciding thread inside its chooseCard. */
    private Map<Card, Integer> lastVotes;
    /** The search's last evidence and worlds, delivered the same way. */
    private BeliefEncoding.Evidence lastEvidence;
    private List<WorldSampler.World> lastWorlds;
    private final BiConsumer<Map<Card, Integer>, Boolean> listener;
    private final Scope scope;

    public FlatHandoverProvider(String id, SkatAiProvider search, SkatAiProvider rescuer) {
        this(id, search, rescuer, null, null);
    }

    /** The lost positions played out in the search's own worlds instead of handed to another player. */
    public FlatHandoverProvider(String id, SkatAiProvider search, RolloutRescue rollout) {
        this(id, search, null, rollout, null);
    }

    /** The play-outs, for the decisions {@code scope} names. */
    public FlatHandoverProvider(String id, SkatAiProvider search, RolloutRescue rollout, Scope scope) {
        this(id, search, null, rollout, null, scope);
    }

    FlatHandoverProvider(String id, SkatAiProvider search, SkatAiProvider rescuer, RolloutRescue rollout,
                         BiConsumer<Map<Card, Integer>, Boolean> listener) {
        this(id, search, rescuer, rollout, listener, Scope.LOST_DECLARER);
    }

    /** @param listener told, at every declarer decision, the search's votes and whether it was handed over as lost */
    FlatHandoverProvider(String id, SkatAiProvider search, SkatAiProvider rescuer, RolloutRescue rollout,
                         BiConsumer<Map<Card, Integer>, Boolean> listener, Scope scope) {
        if ((rescuer == null) == (rollout == null)) {
            throw new IllegalArgumentException(id + ": a rescuer or the play-outs, exactly one");
        }
        if (rescuer != null && !scope.equals(Scope.LOST_DECLARER)) {
            throw new IllegalArgumentException(id + ": another player rescues the declarer's lost positions only");
        }
        this.scope = scope;
        if (!(search instanceof SearchAiProvider searching)) {
            throw new IllegalArgumentException(id + ": the first player must be a search player, not "
                    + search.descriptor().id);
        }
        this.id = id;
        this.search = searching.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
            @Override public void decided(SearchAiProvider.CardPlayReport report) {}
            @Override public void sampled(SkatAi.DecisionContext context, BeliefEncoding.Evidence evidence,
                                          List<WorldSampler.World> worlds) {
                lastEvidence = evidence;
                lastWorlds = worlds;
            }
            @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                        Map<Card, Integer> cushion, int worldsSearched,
                                        boolean cushionAsked, Card chosen) {
                lastVotes = votes;
            }
        });
        this.rescuer = rescuer;
        this.rollout = rollout;
        this.listener = listener;
    }

    static long handed() { return HANDED.get(); }
    static long declarerDecisions() { return DECLARER_DECISIONS.get(); }

    @Override public SkatAi.AiDescriptor descriptor() { return new SkatAi.AiDescriptor(id, id, true); }

    @Override public void observe(GameEngine engine) {
        if (search instanceof TableObserver o) o.observe(engine);
        if (rescuer instanceof TableObserver o) o.observe(engine);
    }

    /** A rescuer that never plays and hears everything: the session's partner when the play-outs rescue. */
    private static final SkatAiSession SILENT = new SkatAiSession() {
        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            throw new IllegalStateException("the play-outs choose, not this session");
        }
    };

    @Override public void observeFixedContract(Board board, ContractSource.FixedContract fixed) {
        if (search instanceof TableObserver o) o.observeFixedContract(board, fixed);
        if (rescuer instanceof TableObserver o) o.observeFixedContract(board, fixed);
    }

    /** The legal cards whose vote is within {@code margin} worlds of the best, in the legal cards' order. */
    static List<Card> tied(Map<Card, Integer> votes, Set<Card> legal, int margin) {
        if (votes == null || votes.isEmpty()) return List.of();
        int top = 0;
        for (Card card : legal) top = Math.max(top, votes.getOrDefault(card, 0));
        List<Card> out = new java.util.ArrayList<>();
        for (Card card : legal) if (votes.getOrDefault(card, 0) >= top - margin) out.add(card);
        return out;
    }

    /**
     * The cards the play-outs choose among, or none when the search keeps the
     * decision: every legal card in a lost position the scope hands over for
     * this role; otherwise, when the scope breaks this role's ties, the cards
     * within its margin of the best vote, if there are two or more. A lost
     * position is never a tie.
     */
    static List<Card> candidates(Map<Card, Integer> votes, Set<Card> legal, boolean declares, Scope scope) {
        if (legal.size() < 2 || !scope.covers(declares)) return List.of();
        if (flat(votes, legal)) {
            return (declares ? scope.lostDeclarer() : scope.lostDefender()) ? new java.util.ArrayList<>(legal) : List.of();
        }
        int margin = declares ? scope.tieDeclarer() : scope.tieDefender();
        if (margin < 0) return List.of();
        List<Card> tied = tied(votes, legal, margin);
        return tied.size() < 2 ? List.of() : tied;
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
        SkatAiSession other = rescuer != null ? rescuer.createSession() : SILENT;
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
                lastEvidence = null;
                lastWorlds = null;
                Card searched = main.chooseCard(context);
                if (!context.game.hasDeclarer() || context.legalCards.size() < 2) return searched;
                boolean declares = context.mySeat == context.game.declarer;
                if (!scope.covers(declares)) return searched;
                Map<Card, Integer> votes = lastVotes;
                boolean lost = flat(votes, context.legalCards);
                boolean handLost = lost && (declares ? scope.lostDeclarer() : scope.lostDefender());
                List<Card> candidates = candidates(votes, context.legalCards, declares, scope);
                if (declares) DECLARER_DECISIONS.incrementAndGet();
                int role = declares ? 0 : 1;
                SEEN[role].incrementAndGet();
                SEEN[role + 2].incrementAndGet();
                if (listener != null && declares) listener.accept(votes, handLost);
                if (candidates.isEmpty()) return searched;
                int kind = handLost ? role : role + 2;
                HANDED.incrementAndGet();
                KIND_HANDED[kind].incrementAndGet();
                Card rescued;
                if (rollout != null) {
                    rescued = lastWorlds == null ? searched : rollout.choose(context, lastEvidence, lastWorlds,
                            candidates, searched);
                } else {
                    rescued = other.chooseCard(context);
                }
                if (!searched.equals(rescued)) {
                    CHANGED.incrementAndGet();
                    KIND_CHANGED[kind].incrementAndGet();
                }
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
