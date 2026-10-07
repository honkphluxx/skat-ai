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
    public record Scope(boolean lostDeclarer, boolean lostDefender, int tieDeclarer, int tieDefender,
                        boolean cushionFirstDeclarer, boolean cushionFirstDefender,
                        boolean heldOnlyDeclarer, boolean heldOnlyDefender) {
        /** The first form: the declarer's lost positions and nothing else. */
        public static final Scope LOST_DECLARER = new Scope(true, false, -1, -1);

        /** Ties on the vote alone, as first built. */
        public Scope(boolean lostDeclarer, boolean lostDefender, int tieDeclarer, int tieDefender) {
            this(lostDeclarer, lostDefender, tieDeclarer, tieDefender, false, false, false, false);
        }

        /**
         * {@code lost-decl,lost-def,tie0-decl,tie2-def}: any of them,
         * comma-separated. {@code tie<k>c-decl} / {@code tie<k>c-def}: the
         * tie that is left after the search's own cushion -- among the cards
         * tied on the vote, only those that also share the best cushion.
         * {@code tie<k>w-...} (or {@code tie<k>cw-...}): only the ties at the
         * full vote, where the best card holds the game for this side in
         * every world searched.
         */
        public static Scope parse(String text) {
            boolean lostDecl = false;
            boolean lostDef = false;
            int tieDecl = -1;
            int tieDef = -1;
            boolean cushionDecl = false;
            boolean cushionDef = false;
            boolean heldDecl = false;
            boolean heldDef = false;
            for (String part : text.split(",")) {
                String p = part.trim();
                java.util.regex.Matcher tie = java.util.regex.Pattern.compile("tie(\\d+)(c?)(w?)-(decl|def)").matcher(p);
                if (p.equals("lost-decl")) lostDecl = true;
                else if (p.equals("lost-def")) lostDef = true;
                else if (tie.matches()) {
                    int k = Integer.parseInt(tie.group(1));
                    boolean afterCushion = !tie.group(2).isEmpty();
                    boolean heldOnly = !tie.group(3).isEmpty();
                    if (tie.group(4).equals("decl")) {
                        tieDecl = k;
                        cushionDecl = afterCushion;
                        heldDecl = heldOnly;
                    } else {
                        tieDef = k;
                        cushionDef = afterCushion;
                        heldDef = heldOnly;
                    }
                }
                else throw new IllegalArgumentException("a scope is lost-decl, lost-def, tie<k>[c][w]-decl, tie<k>[c][w]-def; not " + p);
            }
            return new Scope(lostDecl, lostDef, tieDecl, tieDef, cushionDecl, cushionDef, heldDecl, heldDef);
        }

        boolean covers(boolean declares) {
            return declares ? lostDeclarer || tieDeclarer >= 0 : lostDefender || tieDefender >= 0;
        }
    }

    /** Per kind of hand-over: decisions looked at, handed over, card changed. Kinds: lost/tie x declarer/defender. */
    private static final String[] KINDS = {"lost, declarer", "lost, defender", "tied, declarer", "tied, defender"};
    /**
     * What the handed ties were, per role (declarer, defender): the top vote
     * at every world searched (the game held for this side everywhere) or
     * short of it; and whether the search's own cushion had already settled
     * the tie (one card with the best cushion among the candidates), and
     * whether the play-outs overruled that card.
     */
    private static final AtomicLong[] TIE_FULL = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] TIE_FULL_CHANGED = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] TIE_PART = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] TIE_PART_CHANGED = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] TIE_CUSHION_SETTLED = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] TIE_CUSHION_OVERRULED = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] SEEN = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] KIND_HANDED = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] KIND_CHANGED = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private static final AtomicLong DECLARER_DECISIONS = new AtomicLong();
    private static final AtomicLong HANDED = new AtomicLong();
    /** Handed decisions where the rescuer's card was not the search's own. */
    private static final AtomicLong CHANGED = new AtomicLong();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Any decision a scope looked at, either role: a defenders'-only
            // scope sees no declarer decisions and used to print nothing.
            if (SEEN[0].get() + SEEN[1].get() > 0) {
                long decisions = SEEN[0].get() + SEEN[1].get();
                System.out.printf("Flat handover: %,d of %,d %s decisions handed to the rescuer (%.1f%%)%n",
                        HANDED.get(), decisions, SEEN[1].get() == 0 ? "declarer" : SEEN[0].get() == 0 ? "defender" : "declarer and defender",
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
                String[] roles = {"declarer", "defender"};
                for (int r = 0; r < 2; r++) {
                    long ties = TIE_FULL[r].get() + TIE_PART[r].get();
                    if (ties == 0) continue;
                    System.out.printf("  ties, %s: at the full vote %,d (changed %,d), short of it %,d (changed %,d); "
                                    + "the search's cushion had settled %,d of them, the play-outs overruled it in %,d%n",
                            roles[r], TIE_FULL[r].get(), TIE_FULL_CHANGED[r].get(), TIE_PART[r].get(),
                            TIE_PART_CHANGED[r].get(), TIE_CUSHION_SETTLED[r].get(), TIE_CUSHION_OVERRULED[r].get());
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
    /** The search's cushion with the last vote (empty when it was not asked) and the worlds searched. */
    private Map<Card, Integer> lastCushion;
    private int lastSearched;
    /** The search's last evidence and worlds, delivered the same way. */
    private BeliefEncoding.Evidence lastEvidence;
    private List<WorldSampler.World> lastWorlds;
    private final BiConsumer<Map<Card, Integer>, Boolean> listener;
    private final Scope scope;
    /** The search as given, before this provider's observer: what {@link #withTap} rebuilds from. */
    private final SearchAiProvider plainSearch;
    /**
     * Another observer of the search's decisions, or null: told everything the
     * search tells, except that its vote arrives after the hand-over with the
     * card actually played -- the search's own or the play-outs'. What the
     * teacher exporter records a player with play-outs through.
     */
    private final SearchAiProvider.CardPlayObserver tap;
    /** The search's last vote, held for the tap until the card is settled. */
    private SkatAi.DecisionContext pendingContext;
    private Map<Card, Integer> pendingCushion;
    private int pendingWorlds;
    private boolean pendingCushionAsked;

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
        this(id, search, rescuer, rollout, listener, scope, null);
    }

    /** The same player, with {@code tap} told of every card decision (see {@link #tap}). */
    public FlatHandoverProvider withTap(SearchAiProvider.CardPlayObserver tap) {
        return new FlatHandoverProvider(id, plainSearch, rescuer, rollout, listener, scope, tap);
    }

    private FlatHandoverProvider(String id, SkatAiProvider search, SkatAiProvider rescuer, RolloutRescue rollout,
                                 BiConsumer<Map<Card, Integer>, Boolean> listener, Scope scope,
                                 SearchAiProvider.CardPlayObserver tap) {
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
        this.plainSearch = searching;
        this.tap = tap;
        this.search = searching.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
            @Override public void decided(SearchAiProvider.CardPlayReport report) {
                if (tap != null) tap.decided(report);
            }
            @Override public void sampled(SkatAi.DecisionContext context, BeliefEncoding.Evidence evidence,
                                          List<WorldSampler.World> worlds) {
                lastEvidence = evidence;
                lastWorlds = worlds;
                if (tap != null) tap.sampled(context, evidence, worlds);
            }
            @Override public boolean wantsWorldVerdicts() {
                return tap != null && tap.wantsWorldVerdicts();
            }
            @Override public void worldVerdicts(SkatAi.DecisionContext context, List<Card> legal, long[] holds,
                                                int worlds) {
                if (tap != null) tap.worldVerdicts(context, legal, holds, worlds);
            }
            @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                        Map<Card, Integer> cushion, int worldsSearched,
                                        boolean cushionAsked, Card chosen) {
                lastVotes = votes;
                lastCushion = cushionAsked ? cushion : Map.of();
                lastSearched = worldsSearched;
                pendingContext = context;
                pendingCushion = cushion;
                pendingWorlds = worldsSearched;
                pendingCushionAsked = cushionAsked;
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
        return candidates(votes, Map.of(), 0, legal, declares, scope);
    }

    /**
     * As above; where the scope asks for the tie left after the cushion, the
     * tied cards are narrowed to those sharing the best {@code cushion} among
     * them (no cushion asked: no narrowing); where it asks for held ties
     * only, a tie whose best vote is short of {@code searched} is not handed.
     */
    static List<Card> candidates(Map<Card, Integer> votes, Map<Card, Integer> cushion, int searched,
                                 Set<Card> legal, boolean declares, Scope scope) {
        if (legal.size() < 2 || !scope.covers(declares)) return List.of();
        if (flat(votes, legal)) {
            return (declares ? scope.lostDeclarer() : scope.lostDefender()) ? new java.util.ArrayList<>(legal) : List.of();
        }
        int margin = declares ? scope.tieDeclarer() : scope.tieDefender();
        if (margin < 0) return List.of();
        List<Card> tied = tied(votes, legal, margin);
        if (declares ? scope.heldOnlyDeclarer() : scope.heldOnlyDefender()) {
            int top = 0;
            for (Card card : tied) top = Math.max(top, votes.getOrDefault(card, 0));
            if (searched <= 0 || top < searched) return List.of();
        }
        if ((declares ? scope.cushionFirstDeclarer() : scope.cushionFirstDefender()) && cushion != null
                && !cushion.isEmpty()) {
            tied = bestCushion(tied, cushion);
        }
        return tied.size() < 2 ? List.of() : tied;
    }

    /** The cards among {@code cards} with the best cushion, in their order. */
    static List<Card> bestCushion(List<Card> cards, Map<Card, Integer> cushion) {
        int top = Integer.MIN_VALUE;
        for (Card card : cards) top = Math.max(top, cushion.getOrDefault(card, 0));
        List<Card> out = new java.util.ArrayList<>();
        for (Card card : cards) if (cushion.getOrDefault(card, 0) == top) out.add(card);
        return out;
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
                pendingContext = null;
                Card card = decide(context);
                if (tap != null && pendingContext == context && lastVotes != null) {
                    tap.voted(context, lastVotes, pendingCushion, pendingWorlds, pendingCushionAsked, card);
                }
                pendingContext = null;
                return card;
            }

            private Card decide(SkatAi.DecisionContext context) {
                lastVotes = null;
                lastCushion = Map.of();
                lastSearched = 0;
                lastEvidence = null;
                lastWorlds = null;
                Card searched = main.chooseCard(context);
                if (!context.game.hasDeclarer() || context.legalCards.size() < 2) return searched;
                boolean declares = context.mySeat == context.game.declarer;
                if (!scope.covers(declares)) return searched;
                Map<Card, Integer> votes = lastVotes;
                boolean lost = flat(votes, context.legalCards);
                boolean handLost = lost && (declares ? scope.lostDeclarer() : scope.lostDefender());
                Map<Card, Integer> cushion = lastCushion;
                List<Card> candidates = candidates(votes, cushion, lastSearched, context.legalCards, declares, scope);
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
                boolean changed = !searched.equals(rescued);
                if (changed) {
                    CHANGED.incrementAndGet();
                    KIND_CHANGED[kind].incrementAndGet();
                }
                if (!handLost) {
                    int top = 0;
                    for (Card card : candidates) top = Math.max(top, votes.getOrDefault(card, 0));
                    boolean full = lastSearched > 0 && top >= lastSearched;
                    (full ? TIE_FULL : TIE_PART)[role].incrementAndGet();
                    if (changed) (full ? TIE_FULL_CHANGED : TIE_PART_CHANGED)[role].incrementAndGet();
                    if (!cushion.isEmpty() && bestCushion(candidates, cushion).size() == 1) {
                        TIE_CUSHION_SETTLED[role].incrementAndGet();
                        if (!bestCushion(candidates, cushion).get(0).equals(rescued)) {
                            TIE_CUSHION_OVERRULED[role].incrementAndGet();
                        }
                    }
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
