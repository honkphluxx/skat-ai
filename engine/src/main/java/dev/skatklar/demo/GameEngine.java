package dev.skatklar.demo;

import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Trusted game state and rules authority for the auto-bidding demo variant. */
public final class GameEngine {
    public static final int HUMAN = SkatAi.Seat.HUMAN.ordinal();
    public static final int OPPONENT_ONE = SkatAi.Seat.OPPONENT_ONE.ordinal();
    public static final int OPPONENT_TWO = SkatAi.Seat.OPPONENT_TWO.ordinal();

    private final Random random;
    private final SeatedAiProviders aiProviders;
    @SuppressWarnings("unchecked")
    private final ArrayList<Card>[] hands = new ArrayList[] {
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>()
    };
    private final EnumMap<SkatAi.Seat, SkatAiSession> aiSessions =
            new EnumMap<>(SkatAi.Seat.class);
    private final EnumSet<SkatAi.Seat> humanSeats = EnumSet.of(SkatAi.Seat.HUMAN);
    private final ArrayList<SkatAi.PlayedCard> trick = new ArrayList<>(3);
    private final ArrayList<SkatAi.CompletedTrick> history = new ArrayList<>(10);
    private final EnumMap<SkatAi.Seat, Integer> tricksWon = zeroMap();
    private final EnumMap<SkatAi.Seat, Integer> capturedPoints = zeroMap();
    private final EnumMap<SkatAi.Seat, Set<SkatAi.FollowClass>> voidClasses =
            new EnumMap<>(SkatAi.Seat.class);
    private final LinkedHashSet<Card> playedCards = new LinkedHashSet<>();
    private static final SkatAi.ContractRules AUTO_BIDDING_CONTRACTS =
            new SkatAi.ContractRules(EnumSet.of(
                    SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS,
                    SkatAi.ContractType.SPADES, SkatAi.ContractType.CLUBS,
                    SkatAi.ContractType.GRAND), false, false, false);
    /**
     * The full game: every contract, hand play and every announcement. The
     * stripped-down auto-bidding variant deliberately keeps its narrower rules
     * above, so a beginner meets five games and no announcements at all.
     */
    public static final SkatAi.ContractRules FULL_CONTRACTS =
            new SkatAi.ContractRules(EnumSet.of(
                    SkatAi.ContractType.DIAMONDS, SkatAi.ContractType.HEARTS,
                    SkatAi.ContractType.SPADES, SkatAi.ContractType.CLUBS,
                    SkatAi.ContractType.GRAND, SkatAi.ContractType.NULL),
                    true, true, true);

    /**
     * Where a deal currently stands. Auto-bidding only ever reports
     * {@link Phase#PLAY}, because its auction happens inside one call; the
     * interactive game moves through the earlier phases with a person in them.
     */
    public enum Phase { AUCTION, SKAT_CHOICE, DISCARD, DECLARATION, PUSH, PLAY }

    private SkatAi.GameDefinition definition;
    private Phase phase = Phase.PLAY;
    private Auction auction;
    private final EnumMap<SkatAi.Seat, SkatAiSession> auctionSessions =
            new EnumMap<>(SkatAi.Seat.class);
    /**
     * Automated seats that have been dealt to but have not yet looked at their
     * cards, and the context each is waiting for.
     *
     * <p>Non-empty only between {@link #startInteractiveDeal(SkatDeck.Deal,
     * SkatAi.RoundPosition, Set, boolean)} with the deferral asked for and the
     * {@link #prepareDeferredSeats()} that answers it.
     */
    private final EnumMap<SkatAi.Seat, SkatAi.DealContext> deferredSeats =
            new EnumMap<>(SkatAi.Seat.class);
    /**
     * Seats that have looked at their cards but have not been let into the
     * auction yet.
     *
     * <p>Two states rather than one because preparing and admitting happen on
     * different threads. {@link #prepareDeferredSeats()} does the expensive half
     * anywhere; {@link #admitPreparedSeats()} must run on the thread that steps
     * the auction, because that is the only place a seat can be handed the bids
     * it missed without one slipping past in between.
     */
    private final EnumSet<SkatAi.Seat> preparedSeats = EnumSet.noneOf(SkatAi.Seat.class);
    /** Read from the thread that will open the auction; see {@link #aiPreparationPending}. */
    private volatile boolean aiPreparationPending;
    /**
     * Bumped by every deal, so evaluation still running for an abandoned one is
     * dropped rather than written into the deal that replaced it.
     */
    private volatile int dealGeneration;
    private List<Card> dealtSkat = Collections.emptyList();
    private final ArrayList<Card> pendingDiscards = new ArrayList<>(2);
    private SkatAi.RoundPosition auctionRound;
    private boolean declarerPlaysHand;
    private List<Card> skat = Collections.emptyList();
    private int leader;
    private int currentPlayer;
    private int completedTricks;
    private final ArrayList<RuleViolation> ruleViolations = new ArrayList<>();
    private SkatAi.Seat pendingTrickWinner;
    private SkatAi.GameResult result;
    private Snapshot cachedSnapshot;
    private long nextRoundNumber;

    /** Sessions taking part in a Schieben, which happens before play sessions exist. */
    private final EnumMap<SkatAi.Seat, SkatAiSession> schiebenSessions =
            new EnumMap<>(SkatAi.Seat.class);
    /** Whose leg of the Schieben is outstanding, or null when there is none. */
    private SkatAi.Seat pushSeat;
    private SkatAi.Seat pushFrom;
    private List<Card> pushReceived = Collections.emptyList();
    private int pushLeg;
    /** False in auto-bidding, where the person's seat is played for them anyway. */
    private boolean schiebenInteractive;
    private SkatAi.RoundPosition schiebenRound;

    private SkatAi.ContraLevel contra = SkatAi.ContraLevel.NONE;
    private SkatAi.Seat contraSeat;
    /** Seats that have had their one chance to double, whether or not they took it. */
    private final EnumSet<SkatAi.Seat> contraAsked = EnumSet.noneOf(SkatAi.Seat.class);
    /** A Kontra is on the table and a human declarer has not yet answered it. */
    private boolean awaitingRe;

    /**
     * The dealer's generator and the fallback player's are split rather than
     * shared. Sharing one stream made the next deal depend on how many decisions
     * the players happened to make in the previous one, so a change to card play
     * silently reshuffled every board after it — the exact failure a fixed seed
     * exists to prevent. {@link Rng#derive} gives the player a stream that
     * provably never meets the dealer's.
     */
    public GameEngine(Random random) {
        this(random, new LegalRandomAiProvider(Rng.derive(random)));
    }

    public GameEngine(Random random, Contract contract) {
        this(random, new LegalRandomAiProvider(Rng.derive(random)));
        configureAndRestart(new SkatAi.GameDefinition(
                SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN, contract));
    }

    public GameEngine(Random random, SkatAiProvider aiProvider) {
        this(random, SeatedAiProviders.uniform(aiProvider), true);
    }

    /**
     * Seats a possibly different implementation per seat and deals no cards yet.
     * Intended for headless tooling that supplies its own boards through
     * {@link #restartWithDeal}; interactive callers want the public constructors.
     */
    public static GameEngine headless(Random random, SeatedAiProviders aiProviders) {
        return new GameEngine(random, aiProviders, false);
    }

    private GameEngine(Random random, SeatedAiProviders aiProviders, boolean dealImmediately) {
        this.random = random;
        this.aiProviders = Objects.requireNonNull(aiProviders, "aiProviders");
        definition = new SkatAi.GameDefinition(
                SkatAi.Seat.HUMAN, SkatAi.Seat.HUMAN, Contract.GRAND);
        // Construction remains side-effect-light for embedders and legacy callers;
        // auto-bidding starts explicitly via restart().
        if (dealImmediately) restartWithDefinition();
    }

    public synchronized void configureAndRestart(SkatAi.GameDefinition newDefinition) {
        definition = newDefinition;
        restartWithDefinition();
    }

    /** Deals, auctions, exchanges the skat and announces under the demo variant. */
    public synchronized void restart() {
        closePlaySessions();
        clearInteractiveState();
        ruleViolations.clear();
        // Nobody bidding used to mean "deal again", which made passing free and
        // taught exactly the wrong thing. All three passing is a Schieberamsch
        // now, so a deal is always played and no deal is ever thrown away.
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(nextRoundNumber++);
        SkatDeck.Deal deal = SkatDeck.deal(random);
        loadDeal(deal);
        AuctionSetup setup = runAutoBidding(round, deal);
        definition = setup.ramsch()
                ? SkatAi.GameDefinition.ramsch(round)
                : new SkatAi.GameDefinition(setup.declarer, round.forehand,
                        setup.contract, round, setup.bidValue);
        initializeTrickPlay();
    }

    /** Starts a deal with AI sessions only for seats not occupied by people. */
    public synchronized void restart(Set<SkatAi.Seat> occupiedHumanSeats) {
        if (occupiedHumanSeats == null || occupiedHumanSeats.isEmpty()) {
            throw new IllegalArgumentException("At least one human seat is required");
        }
        humanSeats.clear();
        humanSeats.addAll(occupiedHumanSeats);
        restart();
    }

    /**
     * Plays one predetermined board: an explicit deal and an explicit rotation,
     * without the pass-in retry loop that {@link #restart()} uses. Tooling needs
     * both, because a duplicate comparison must replay the identical board for
     * every contestant, and a retry would silently substitute a different deal.
     *
     * <p>Always returns {@code true} now, and is kept boolean only so that the
     * tooling built around it did not all have to change on the same day: an
     * auction where everyone passes produces a Ramsch, not a discarded board.
     *
     * @param occupiedHumanSeats seats played by people; may be empty for an
     *                           all-AI table, unlike {@link #restart(Set)}
     */
    public synchronized boolean restartWithDeal(SkatDeck.Deal deal, SkatAi.RoundPosition round,
                                                Set<SkatAi.Seat> occupiedHumanSeats) {
        Objects.requireNonNull(deal, "deal");
        Objects.requireNonNull(round, "round");
        closePlaySessions();
        clearInteractiveState();
        ruleViolations.clear();
        humanSeats.clear();
        if (occupiedHumanSeats != null) humanSeats.addAll(occupiedHumanSeats);
        loadDeal(deal);
        AuctionSetup setup = runAutoBidding(round, deal);
        definition = setup.ramsch()
                ? SkatAi.GameDefinition.ramsch(round)
                : new SkatAi.GameDefinition(setup.declarer, round.forehand,
                        setup.contract, round, setup.bidValue);
        initializeTrickPlay();
        return true;
    }

    /**
     * Deals a fresh board and opens an auction a person takes part in.
     *
     * <p>Unlike {@link #restart()} this returns before anything has been bid.
     * The caller drives {@link Auction#step()} on its own clock, answers for the
     * seats it is playing, and then follows the auction with the skat, discard
     * and declaration calls below. Nothing is decided here that a player has not
     * yet decided.
     */
    public synchronized Auction startInteractiveDeal(Set<SkatAi.Seat> occupiedHumanSeats) {
        return startInteractiveDeal(occupiedHumanSeats, false);
    }

    /**
     * As above, on a fresh board, optionally leaving the automated seats'
     * evaluation to the caller.
     *
     * @param deferAiPreparation see
     *        {@link #startInteractiveDeal(SkatDeck.Deal, SkatAi.RoundPosition, Set, boolean)}
     */
    public synchronized Auction startInteractiveDeal(Set<SkatAi.Seat> occupiedHumanSeats,
                                                     boolean deferAiPreparation) {
        return startInteractiveDeal(SkatDeck.deal(random),
                SkatAi.RoundPosition.at(nextRoundNumber++), occupiedHumanSeats,
                deferAiPreparation);
    }

    /**
     * As {@link #startInteractiveDeal(Set)}, on a board somebody else chose.
     *
     * <p>For a caller that has already chosen the board — a replay, or a fixture
     * that needs two deals differing only in the hidden half. Nothing else
     * differs: the auction that follows is the real one. The contract challenges
     * do not come through here; they arrive already settled, through
     * {@link #startChallengeDeal}.
     */
    public synchronized Auction startInteractiveDeal(SkatDeck.Deal board,
                                                     SkatAi.RoundPosition position,
                                                     Set<SkatAi.Seat> occupiedHumanSeats) {
        return startInteractiveDeal(board, position, occupiedHumanSeats, false);
    }

    /**
     * As above, optionally handing the automated seats' evaluation back to the
     * caller.
     *
     * <p>Dealing is instant; looking at a hand is not. A searching player measures
     * what it holds by playing it out against perfect defence a few times, and
     * that is several double-dummy solves per seat whose cost is a distribution
     * with a long tail rather than a number. Run inline, on a phone, on the
     * thread that delivered the tap, that is the freeze the player sees — the app
     * is still on the title screen, still animating, and no longer listening.
     *
     * <p>So the deal splits in two. This method deals, seats the sessions and
     * returns the auction; {@link #prepareDeferredSeats()} does the expensive
     * half and may be called from anywhere. Between the two calls the auction
     * exists but must not be stepped: an automated seat asked to bid before it
     * has seen its cards is a session consulted out of order, and
     * {@link #aiPreparationPending()} is how a caller knows it is safe.
     *
     * @param deferAiPreparation true to leave {@code prepareDeal} to the caller.
     *                           Anything that has nowhere else to run the work —
     *                           the server, tooling, the tests — passes false and
     *                           gets the whole deal in one call, as before.
     */
    public synchronized Auction startInteractiveDeal(SkatDeck.Deal board,
                                                     SkatAi.RoundPosition position,
                                                     Set<SkatAi.Seat> occupiedHumanSeats,
                                                     boolean deferAiPreparation) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(position, "position");
        closePlaySessions();
        // The full reset, not a partial one. Every other entry point uses it,
        // and the fields it clears that a hand-rolled reset missed are the
        // Schieben's: a deal started while a Ramsch was half pushed would have
        // inherited its pushed cards and its closed sessions.
        clearInteractiveState();
        ruleViolations.clear();
        humanSeats.clear();
        if (occupiedHumanSeats != null) humanSeats.addAll(occupiedHumanSeats);
        if (humanSeats.isEmpty()) humanSeats.add(SkatAi.Seat.HUMAN);

        // The rotation counter is deliberately not advanced from a supplied
        // position. A challenge board's position comes from which audition
        // attempt happened to fit, and letting that number into the counter
        // would jump the dealer rotation of the next ordinary game.
        auctionRound = position;
        SkatDeck.Deal deal = board;
        loadDeal(deal);
        dealtSkat = skat;
        // A neutral, readable order before any game is previewed. Grand groups
        // the jacks, which is the least misleading default: it claims a trump
        // suit for nobody.
        for (ArrayList<Card> hand : hands) hand.sort(CardOrder.GRAND);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (humanSeats.contains(seat)) continue;
            SkatAiSession session = aiProviders.providerFor(seat).createSession();
            auctionSessions.put(seat, session);
            SkatAi.DealContext context = new SkatAi.DealContext(auctionRound, seat,
                    new LinkedHashSet<>(hands[seat.ordinal()]), FULL_CONTRACTS);
            if (deferAiPreparation) {
                deferredSeats.put(seat, context);
                continue;
            }
            try {
                session.prepareDeal(context);
            } catch (RuntimeException invalid) {
                recordViolation(seat, ViolationPhase.BID);
            }
        }
        preparedSeats.clear();
        aiPreparationPending = !deferredSeats.isEmpty();
        definition = new SkatAi.GameDefinition(auctionRound.forehand, auctionRound.forehand,
                Contract.GRAND, auctionRound, 0);
        trick.clear();
        history.clear();
        playedCards.clear();
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            tricksWon.put(seat, 0);
            capturedPoints.put(seat, 0);
            voidClasses.put(seat, new LinkedHashSet<>());
        }
        completedTricks = 0;
        pendingTrickWinner = null;
        result = null;
        leader = auctionRound.forehand.ordinal();
        currentPlayer = leader;
        phase = Phase.AUCTION;
        auction = new Auction(auctionRound, humanSeats, auctionSessions);
        // A seat that has not looked at its cards is neither asked anything nor
        // told anything until admitPreparedSeats hands it the log. Wired here
        // rather than only when the deal is deferred, because the predicate is
        // simply false when nothing was deferred.
        auction.withholdFrom(this::aiPreparationPending);
        cachedSnapshot = null;
        return auction;
    }

    /** True while any automated seat is still owed a look at the cards it was dealt. */
    public boolean aiPreparationPending() {
        return aiPreparationPending;
    }

    /**
     * True while <em>this</em> seat is still owed a look at its cards.
     *
     * <p>The per-seat question is what lets the auction open before every seat
     * has finished thinking: a person can say 18 while a seat that speaks later
     * is still measuring its hand. Only a seat about to be asked something has
     * to be ready.
     *
     * <p>A seat that has been prepared but not yet admitted still answers true.
     * It has looked at its cards but has not been told what was said while it
     * was looking, so an opinion from it now would be one formed in ignorance.
     */
    public synchronized boolean aiPreparationPending(SkatAi.Seat seat) {
        return seat != null && (deferredSeats.containsKey(seat) || preparedSeats.contains(seat));
    }

    /**
     * Lets every prepared seat into the auction, replaying the bids it missed.
     *
     * <p><b>Must be called on the thread that steps the auction</b>, and is the
     * only place a seat stops being withheld. That is what makes the replay
     * exact rather than approximately right: while a seat is withheld,
     * {@link Auction} tells it nothing, so what it missed is precisely the log
     * — and because nothing can be spoken between the replay and the seat
     * ceasing to be withheld, it hears every bid exactly once.
     *
     * <p>Cheap and idempotent; the ordinary call finds nothing to do.
     *
     * @return true when at least one seat was admitted
     */
    public boolean admitPreparedSeats() {
        List<SkatAi.Seat> admitting;
        Auction current;
        synchronized (this) {
            if (preparedSeats.isEmpty()) return false;
            admitting = new ArrayList<>(preparedSeats);
            current = auction;
        }
        List<SkatAi.Seat> broken = new ArrayList<>();
        for (SkatAi.Seat seat : admitting) {
            if (current != null) broken.addAll(current.replayBidsTo(seat));
        }
        synchronized (this) {
            for (SkatAi.Seat seat : broken) recordViolation(seat, ViolationPhase.BID);
            admitting.forEach(preparedSeats::remove);
            aiPreparationPending = !deferredSeats.isEmpty() || !preparedSeats.isEmpty();
        }
        return true;
    }

    /**
     * Lets the automated seats look at their hands. The expensive half of a
     * deferred deal, and the whole reason the split exists.
     *
     * <p><b>Deliberately not {@code synchronized}.</b> Holding the engine's
     * monitor for the seconds this can take would block every {@code snapshot()}
     * the renderer makes, which is the same freeze in a different place — the
     * work would be off the caller's thread and the UI would still be stuck on
     * the lock. So the sessions and their contexts are copied out under the
     * monitor, the solving happens outside it, and only the bookkeeping goes
     * back in.
     *
     * <p>That is sound because these sessions belong to nobody in between: the
     * auction does not touch them until it is stepped, and it may not be stepped
     * while {@link #aiPreparationPending()} is true. If the deal is abandoned
     * while this is running the generation moves, the work is dropped, and
     * nothing it computed is written anywhere.
     *
     * <p>Safe to call from any thread and safe to call twice; the second call
     * finds nothing to do. The volatile write it ends with is what publishes the
     * prepared sessions to the thread that opens the auction.
     */
    public void prepareDeferredSeats() {
        int generation;
        Map<SkatAi.Seat, SkatAi.DealContext> pending;
        Map<SkatAi.Seat, SkatAiSession> sessions;
        synchronized (this) {
            if (deferredSeats.isEmpty()) return;
            generation = dealGeneration;
            pending = new EnumMap<>(deferredSeats);
            sessions = new EnumMap<>(auctionSessions);
        }
        List<SkatAi.Seat> broken = new ArrayList<>();
        for (Map.Entry<SkatAi.Seat, SkatAi.DealContext> entry : pending.entrySet()) {
            // Between seats rather than only at the end: the first seat's
            // evaluation is long enough for a player to have left the table.
            if (generation != dealGeneration) return;
            SkatAiSession session = sessions.get(entry.getKey());
            if (session == null) continue;
            try {
                session.prepareDeal(entry.getValue());
            } catch (RuntimeException invalid) {
                broken.add(entry.getKey());
            }
        }
        synchronized (this) {
            if (generation != dealGeneration) return;
            for (SkatAi.Seat seat : broken) recordViolation(seat, ViolationPhase.BID);
            // Prepared, not admitted. The seat has seen its cards; it has not
            // heard what was said while it was looking, and only the auction's
            // own thread can hand it that. See admitPreparedSeats.
            for (SkatAi.Seat seat : pending.keySet()) {
                if (!broken.contains(seat)) preparedSeats.add(seat);
            }
            deferredSeats.clear();
            aiPreparationPending = !preparedSeats.isEmpty();
        }
    }

    /**
     * Starts a contract challenge: an audited board, already settled, straight
     * into the play.
     *
     * <p>Nothing before the first trick is left to do. The bidding happened
     * during the caller's audition of the board, against these very cards; the
     * skat was exchanged there too and the board arrives with the declarer's ten
     * already chosen. A challenge is a card-play exercise, and the discard is a
     * separate skill that would otherwise have to be got right before the
     * exercise could begin.
     *
     * <p>The auditioning itself is not in this project -- it belongs to whatever
     * builds the exercises, and in SkatKlar that is {@code ContractChallenge} on
     * the closed side. This method is the seam: it takes a board somebody else
     * decided was worth playing and starts it.
     *
     * <p>A Ramsch is the one that still has something to do, because the
     * Schieben cannot be pre-computed into a deal without losing the order it
     * happened in. It is run here, automatically for every seat including the
     * person's, and reported through {@link SchiebenObserver} so the app can show
     * the cards travelling before the first trick.
     *
     * @param declarer    who plays it alone; ignored, and must be null, in a Ramsch
     * @param declaration the game as the audition winner announced it
     * @param bidValue    what the board was won for; the declarer is held to it,
     *                    so a challenge can still be an overbid
     * @return always {@link Phase#PLAY}: there is nothing left to ask anybody
     */
    public synchronized Phase startChallengeDeal(SkatDeck.Deal board,
                                                 SkatAi.RoundPosition position,
                                                 SkatAi.Seat declarer,
                                                 Declaration declaration, int bidValue,
                                                 Set<SkatAi.Seat> occupiedHumanSeats) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(declaration, "declaration");
        closePlaySessions();
        clearInteractiveState();
        ruleViolations.clear();
        humanSeats.clear();
        if (occupiedHumanSeats != null) humanSeats.addAll(occupiedHumanSeats);
        if (humanSeats.isEmpty()) humanSeats.add(SkatAi.Seat.HUMAN);
        // The rotation counter is deliberately not advanced from a supplied
        // position. A challenge board's position comes from which audition
        // attempt happened to fit, and letting that number into the counter
        // would jump the dealer rotation of the next ordinary game.
        auctionRound = position;
        loadDeal(board);
        dealtSkat = skat;

        if (declaration.contract().isRamsch()) {
            definition = SkatAi.GameDefinition.ramsch(position);
            runAutomaticSchieben(position);
        } else {
            definition = new SkatAi.GameDefinition(
                    Objects.requireNonNull(declarer, "declarer"), position.forehand,
                    declaration.contract(), position, bidValue, declaration.hand(),
                    declaration.schneiderAnnounced(), declaration.schwarzAnnounced(),
                    declaration.ouvert());
        }
        initializeTrickPlay();
        return phase;
    }

    /**
     * Pushes the skat round with nobody being asked, including the seat a person
     * is sitting in.
     *
     * <p>The sessions are made here and closed here. They exist for three
     * decisions and are not the sessions that will play the hand — a Schieben
     * happens before anybody knows what game they are in, and the play sessions
     * are told the game when they start.
     */
    private void runAutomaticSchieben(SkatAi.RoundPosition position) {
        EnumMap<SkatAi.Seat, SkatAiSession> pushers = new EnumMap<>(SkatAi.Seat.class);
        try {
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                SkatAiSession session = aiProviders.providerFor(seat).createSession();
                pushers.put(seat, session);
                try {
                    session.prepareDeal(new SkatAi.DealContext(position, seat,
                            new LinkedHashSet<>(hands[seat.ordinal()]), FULL_CONTRACTS));
                } catch (RuntimeException invalid) {
                    recordViolation(seat, ViolationPhase.BID);
                }
            }
            beginSchieben(position, pushers, false);
        } finally {
            for (SkatAiSession session : pushers.values()) {
                try { session.close(); } catch (RuntimeException ignored) {}
            }
        }
    }

    /** The auction in progress, or null outside an interactive deal. */
    public synchronized Auction auction() { return auction; }

    public synchronized Phase phase() { return phase; }

    /** The cards dealt to the skat, before any discard replaced them. */
    public synchronized List<Card> dealtSkat() { return dealtSkat; }

    /**
     * Moves an interactive deal on once the auction is over.
     *
     * <p>For an automated declarer this completes the whole remaining setup —
     * pick-up or hand, discard, announcement — and starts trick play. For a seat
     * a person is playing it stops at {@link Phase#SKAT_CHOICE} and waits.
     *
     * @return the phase now waiting for input, or {@link Phase#PLAY} when trick
     *         play has begun
     */
    public synchronized Phase settleAuction() {
        if (auction == null || !auction.finished()) {
            throw new IllegalStateException("The auction is not over");
        }
        for (SkatAi.Seat seat : auction.violations()) {
            recordViolation(seat, ViolationPhase.BID);
        }
        if (auction.passedIn()) {
            // Nobody wanted it, so everybody plays it. The auction sessions carry
            // the Schieben; a person at the table stops it at Phase.PUSH.
            definition = SkatAi.GameDefinition.ramsch(auctionRound);
            beginSchieben(auctionRound, auctionSessions, true);
            if (pushSeat == null) {
                closeAuctionSessions();
                initializeTrickPlay();
            }
            return phase;
        }
        SkatAi.Seat declarer = auction.declarer();
        if (humanSeats.contains(declarer)) {
            phase = Phase.SKAT_CHOICE;
            cachedSnapshot = null;
            return phase;
        }
        settleAutomaticDeclarer(declarer, auction.winningBid());
        return phase;
    }

    private void settleAutomaticDeclarer(SkatAi.Seat declarer, int bidValue) {
        SkatAiSession session = auctionSessions.get(declarer);
        boolean pickUp = true;
        if (session != null) {
            try {
                pickUp = session.pickUpSkat(
                        new SkatAi.SkatChoiceContext(auctionRound, declarer, bidValue));
            } catch (RuntimeException invalid) {
                recordViolation(declarer, ViolationPhase.DISCARD);
                pickUp = true;
            }
        }
        List<Card> declarerCards;
        if (pickUp && session != null) {
            declarerCards = exchangeSkat(session, auctionRound, declarer, bidValue, dealtSkat);
        } else {
            pickUp = session != null && pickUp;
            declarerCards = new ArrayList<>(hands[declarer.ordinal()]);
        }
        SkatAi.ContractAnnouncement announced = null;
        if (session != null) {
            try {
                announced = session.announceContract(new SkatAi.ContractContext(
                        auctionRound, declarer, new LinkedHashSet<>(declarerCards),
                        bidValue, pickUp, FULL_CONTRACTS));
            } catch (RuntimeException invalid) {
                announced = null;
            }
        }
        Declaration declaration = usableDeclaration(announced, pickUp, declarerCards, bidValue);
        if (declaration == null) {
            recordViolation(declarer, ViolationPhase.ANNOUNCE);
            declaration = new Declaration(
                    Contract.valueOf(fallbackAnnouncement(declarerCards, bidValue).type.name()),
                    !pickUp, false, false, false);
        }
        closeAuctionSessions();
        definition = new SkatAi.GameDefinition(declarer, auctionRound.forehand,
                declaration.contract(), auctionRound, bidValue, declaration.hand(),
                declaration.schneiderAnnounced(), declaration.schwarzAnnounced(),
                declaration.ouvert());
        phase = Phase.PLAY;
        initializeTrickPlay();
    }

    /**
     * Reconciles what an automated declarer said with what actually happened.
     * The pick-up decision is a fact by the time the announcement is made, so an
     * announcement that contradicts it, or that stacks announcements the rules
     * do not allow, is refused rather than quietly rewritten.
     */
    private Declaration usableDeclaration(SkatAi.ContractAnnouncement announced, boolean pickUp,
                                          List<Card> declarerCards, int bidValue) {
        if (announced == null || !FULL_CONTRACTS.permits(announced)) return null;
        Contract contract;
        try {
            contract = Contract.valueOf(announced.type.name());
        } catch (RuntimeException notPlayable) {
            return null;
        }
        Declaration declaration = new Declaration(contract, !pickUp,
                announced.schneider, announced.schwarz, announced.ouvert);
        return declaration.legal() ? declaration : null;
    }

    /**
     * The declarer takes the skat. Returns the twelve cards now in that hand,
     * two of which must come back through {@link #discard}.
     */
    public synchronized List<Card> pickUpSkat() {
        SkatAi.Seat declarer = requireHumanDeclarer(Phase.SKAT_CHOICE);
        hands[declarer.ordinal()].addAll(dealtSkat);
        hands[declarer.ordinal()].sort(CardOrder.GRAND);
        declarerPlaysHand = false;
        phase = Phase.DISCARD;
        cachedSnapshot = null;
        return Collections.unmodifiableList(new ArrayList<>(hands[declarer.ordinal()]));
    }

    /** The declarer leaves the skat alone and goes straight to the announcement. */
    public synchronized void playHand() {
        requireHumanDeclarer(Phase.SKAT_CHOICE);
        declarerPlaysHand = true;
        phase = Phase.DECLARATION;
        cachedSnapshot = null;
    }

    /** True once the declarer has chosen to leave the skat untouched. */
    public synchronized boolean declarerPlaysHand() { return declarerPlaysHand; }

    /**
     * Puts one card back into the skat.
     *
     * <p>One card at a time, because that is what the player is doing: a card
     * chosen for the skat leaves the hand there and then. Keeping it in the hand
     * until the pair was complete meant that choosing a card had no visible
     * consequence, and a player who cannot see that a choice registered will
     * make it again — undoing it.
     *
     * <p>The choice stays reversible through {@link #takeBackDiscard} right up
     * to the announcement, which is exactly how long a real player has.
     *
     * @return false when the card is not in the declarer's hand, or two are
     *         already down
     */
    public synchronized boolean discardOne(Card card) {
        SkatAi.Seat declarer = requireDeclaringHuman();
        if (card == null || pendingDiscards.size() >= 2) return false;
        ArrayList<Card> hand = hands[declarer.ordinal()];
        if (!hand.remove(card)) return false;
        pendingDiscards.add(card);
        if (pendingDiscards.size() == 2) {
            skat = Collections.unmodifiableList(new ArrayList<>(pendingDiscards));
            phase = Phase.DECLARATION;
        } else {
            phase = Phase.DISCARD;
        }
        cachedSnapshot = null;
        return true;
    }

    /** Takes a card back out of the skat, up to the moment the game is announced. */
    public synchronized boolean takeBackDiscard(Card card) {
        SkatAi.Seat declarer = requireDeclaringHuman();
        if (card == null || !pendingDiscards.remove(card)) return false;
        hands[declarer.ordinal()].add(card);
        hands[declarer.ordinal()].sort(CardOrder.GRAND);
        skat = dealtSkat;
        phase = Phase.DISCARD;
        cachedSnapshot = null;
        return true;
    }

    /** The cards put back so far, in the order they were chosen. */
    public synchronized List<Card> pendingDiscards() {
        return List.copyOf(pendingDiscards);
    }

    /** Puts two cards back in one call. Convenience for tooling and tests. */
    public synchronized boolean discard(Card first, Card second) {
        if (first == null || second == null || first.equals(second)) return false;
        if (!discardOne(first)) return false;
        if (!discardOne(second)) {
            takeBackDiscard(first);
            return false;
        }
        return true;
    }

    /**
     * Announces the game and starts trick play.
     *
     * @return false when the declaration is not one the rules allow here; the
     *         phase is unchanged and the caller can ask again
     */
    public synchronized boolean declare(Declaration declaration) {
        SkatAi.Seat declarer = requireHumanDeclarer(Phase.DECLARATION);
        if (declaration == null || !declaration.legal()) return false;
        if (!FULL_CONTRACTS.permits(declaration.toAnnouncement())) return false;
        // Hand is not something the player announces twice. It was decided when
        // the skat was either taken or left, and that has already happened.
        if (declaration.hand() != declarerPlaysHand) return false;
        definition = new SkatAi.GameDefinition(declarer, auctionRound.forehand,
                declaration.contract(), auctionRound, auction.winningBid(),
                declaration.hand(), declaration.schneiderAnnounced(),
                declaration.schwarzAnnounced(), declaration.ouvert());
        closeAuctionSessions();
        phase = Phase.PLAY;
        initializeTrickPlay();
        return true;
    }

    /**
     * The person who won this auction, while they are still choosing what to
     * put back or what to announce. Both steps belong to the same decision, so
     * both accept the same calls.
     */
    private SkatAi.Seat requireDeclaringHuman() {
        if ((phase != Phase.DISCARD && phase != Phase.DECLARATION) || auction == null
                || auction.declarer() == null || !humanSeats.contains(auction.declarer())) {
            throw new IllegalStateException("Not choosing a skat");
        }
        return auction.declarer();
    }

    private SkatAi.Seat requireHumanDeclarer(Phase expected) {
        if (phase != expected || auction == null || auction.declarer() == null
                || !humanSeats.contains(auction.declarer())) {
            throw new IllegalStateException("Not in phase " + expected);
        }
        return auction.declarer();
    }

    private void closeAuctionSessions() {
        for (SkatAiSession session : auctionSessions.values()) {
            try { session.close(); } catch (RuntimeException ignored) {}
        }
        auctionSessions.clear();
    }

    private void restartWithDefinition() {
        closePlaySessions();
        clearInteractiveState();
        ruleViolations.clear();
        SkatDeck.Deal deal = SkatDeck.deal(random);
        loadDeal(deal);
        initializeTrickPlay();
    }

    private void loadDeal(SkatDeck.Deal deal) {
        hands[HUMAN].clear();
        hands[OPPONENT_ONE].clear();
        hands[OPPONENT_TWO].clear();
        hands[HUMAN].addAll(deal.human);
        hands[OPPONENT_ONE].addAll(deal.opponentOne);
        hands[OPPONENT_TWO].addAll(deal.opponentTwo);
        skat = Collections.unmodifiableList(new ArrayList<>(deal.skat));
    }

    /** Forgets an interactive auction, so a fresh deal cannot inherit half of one. */
    private void clearInteractiveState() {
        // Before the sessions go, so anything measuring a hand on another thread
        // sees a number it no longer matches and drops its work on the floor.
        dealGeneration++;
        deferredSeats.clear();
        preparedSeats.clear();
        aiPreparationPending = false;
        closeAuctionSessions();
        auction = null;
        auctionRound = null;
        declarerPlaysHand = false;
        pendingDiscards.clear();
        dealtSkat = Collections.emptyList();
        schiebenSessions.clear();
        schiebenInteractive = false;
        schiebenRound = null;
        pushSeat = null;
        pushFrom = null;
        pushReceived = Collections.emptyList();
        pushLeg = 0;
    }

    private void initializeTrickPlay() {
        phase = Phase.PLAY;
        for (ArrayList<Card> hand : hands) hand.sort(definition.contract.preferredCardOrder);
        trick.clear();
        history.clear();
        playedCards.clear();
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            tricksWon.put(seat, 0);
            capturedPoints.put(seat, 0);
            voidClasses.put(seat, new LinkedHashSet<>());
        }
        completedTricks = 0;
        pendingTrickWinner = null;
        result = null;
        contra = SkatAi.ContraLevel.NONE;
        contraSeat = null;
        contraAsked.clear();
        awaitingRe = false;
        leader = definition.initialLeader.ordinal();
        currentPlayer = leader;
        createAiSessions();
        cachedSnapshot = null;
    }

    /** Ends stateful AI lifecycles when a remote client disconnects. */
    public synchronized void close() {
        closePlaySessions();
    }

    private void closePlaySessions() {
        for (SkatAiSession session : aiSessions.values()) {
            try { session.endGame(result); } finally { session.close(); }
        }
        aiSessions.clear();
    }

    private AuctionSetup runAutoBidding(SkatAi.RoundPosition round, SkatDeck.Deal deal) {
        EnumMap<SkatAi.Seat, SkatAiSession> bidders = new EnumMap<>(SkatAi.Seat.class);
        try {
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                SkatAiSession session = aiProviders.providerFor(seat).createSession();
                bidders.put(seat, session);
                session.prepareDeal(new SkatAi.DealContext(round, seat,
                        new LinkedHashSet<>(hands[seat.ordinal()]), AUTO_BIDDING_CONTRACTS));
            }
            AuctionResult auction = auction(round, bidders);
            if (auction.declarer == null) {
                // Everyone passed. The same seats that just refused the game now
                // push the skat around it, so the Schieben runs on the bidding
                // sessions -- they are the only ones that exist at this point.
                beginSchieben(round, bidders, false);
                return AuctionSetup.forRamsch();
            }

            for (Map.Entry<SkatAi.Seat, SkatAiSession> entry : bidders.entrySet()) {
                if (entry.getKey() != auction.declarer) entry.getValue().close();
            }
            SkatAiSession winner = bidders.get(auction.declarer);
            List<Card> twelveCards = exchangeSkat(
                    winner, round, auction.declarer, auction.bidValue, deal.skat);

            SkatAi.ContractAnnouncement announced;
            try {
                announced = winner.announceContract(new SkatAi.ContractContext(
                        round, auction.declarer, new LinkedHashSet<>(twelveCards),
                        auction.bidValue, true, AUTO_BIDDING_CONTRACTS));
            } catch (RuntimeException invalid) {
                announced = null;
            }
            if (!AUTO_BIDDING_CONTRACTS.permits(announced)) {
                recordViolation(auction.declarer, ViolationPhase.ANNOUNCE);
                announced = fallbackAnnouncement(twelveCards, auction.bidValue);
            }
            winner.close();
            return new AuctionSetup(auction.declarer, auction.bidValue,
                    Contract.valueOf(announced.type.name()));
        } finally {
            for (SkatAiSession bidder : bidders.values()) {
                try { bidder.close(); } catch (RuntimeException ignored) {}
            }
        }
    }

    /**
     * Runs the declarer's pick-up and discard, leaving the hand and the skat in
     * their post-exchange state. Shared by the auction and by a predetermined
     * contract, so both take exactly the same path through the legality guard.
     */
    private List<Card> exchangeSkat(SkatAiSession declarerSession, SkatAi.RoundPosition round,
                                    SkatAi.Seat declarer, int bidValue, List<Card> dealtSkat) {
        // The demo variant excludes hand games. The callback stays useful to a
        // full-game coordinator, while this variant always picks up.
        declarerSession.pickUpSkat(new SkatAi.SkatChoiceContext(round, declarer, bidValue));
        ArrayList<Card> twelveCards = new ArrayList<>(hands[declarer.ordinal()]);
        twelveCards.addAll(dealtSkat);
        Set<Card> discarded;
        try {
            discarded = declarerSession.discardSkat(new SkatAi.SkatExchangeContext(
                    round, declarer, new LinkedHashSet<>(twelveCards), dealtSkat));
        } catch (RuntimeException invalid) {
            discarded = Collections.emptySet();
        }
        if (!validDiscard(discarded, twelveCards)) {
            recordViolation(declarer, ViolationPhase.DISCARD);
            discarded = new LinkedHashSet<>(dealtSkat);
        }
        twelveCards.removeAll(discarded);
        hands[declarer.ordinal()].clear();
        hands[declarer.ordinal()].addAll(twelveCards);
        skat = Collections.unmodifiableList(new ArrayList<>(discarded));
        return twelveCards;
    }

    /**
     * Plays a predetermined board with a predetermined declarer and contract,
     * skipping the auction entirely. The declarer still picks up and discards,
     * because discarding is a play decision rather than a bidding one.
     *
     * <p>Tooling uses this to compare card play without the comparison being
     * swamped by differences in bidding aggressiveness. {@code bidValue} is kept
     * because the overbid rule needs it; supply the value the contract was
     * actually bid to, or zero for a contract under no bid pressure.
     */
    public synchronized void restartWithContract(SkatDeck.Deal deal, SkatAi.RoundPosition round,
                                                 SkatAi.Seat declarer, Contract contract,
                                                 int bidValue,
                                                 Set<SkatAi.Seat> occupiedHumanSeats) {
        Objects.requireNonNull(deal, "deal");
        Objects.requireNonNull(round, "round");
        Objects.requireNonNull(declarer, "declarer");
        Objects.requireNonNull(contract, "contract");
        closePlaySessions();
        clearInteractiveState();
        ruleViolations.clear();
        humanSeats.clear();
        if (occupiedHumanSeats != null) humanSeats.addAll(occupiedHumanSeats);
        loadDeal(deal);

        SkatAiSession exchange = aiProviders.providerFor(declarer).createSession();
        try {
            exchange.prepareDeal(new SkatAi.DealContext(round, declarer,
                    new LinkedHashSet<>(hands[declarer.ordinal()]), AUTO_BIDDING_CONTRACTS));
            exchangeSkat(exchange, round, declarer, bidValue, deal.skat);
        } finally {
            try { exchange.close(); } catch (RuntimeException ignored) {}
        }

        definition = new SkatAi.GameDefinition(declarer, round.forehand, contract, round, bidValue);
        initializeTrickPlay();
    }

    private AuctionResult auction(SkatAi.RoundPosition round,
                                  EnumMap<SkatAi.Seat, SkatAiSession> bidders) {
        DuelResult first = duel(round, bidders, round.middlehand, round.forehand, 0);
        DuelResult second = duel(round, bidders, round.rearhand, first.winner, first.bidValue);
        if (second.bidValue == 0 && second.winner == round.forehand) {
            int accepted = askBid(round, bidders, round.forehand,
                    SkatAi.BidRole.ANNOUNCE, 0, 18);
            if (accepted != 18) {
                observe(bidders, new SkatAi.BidEvent(round.forehand, 0, true));
                return new AuctionResult(null, 0);
            }
            observe(bidders, new SkatAi.BidEvent(round.forehand, 18, false));
            return new AuctionResult(round.forehand, 18);
        }
        return new AuctionResult(second.winner, second.bidValue);
    }

    private DuelResult duel(SkatAi.RoundPosition round,
                            EnumMap<SkatAi.Seat, SkatAiSession> bidders,
                            SkatAi.Seat announcer, SkatAi.Seat hearer, int startBid) {
        int current = startBid;
        while (true) {
            int next = nextBid(current);
            if (next == 0) return new DuelResult(hearer, current);
            int announced = askBid(round, bidders, announcer,
                    SkatAi.BidRole.ANNOUNCE, current, next);
            if (announced != next) {
                observe(bidders, new SkatAi.BidEvent(announcer, current, true));
                return new DuelResult(hearer, current);
            }
            current = announced;
            observe(bidders, new SkatAi.BidEvent(announcer, current, false));
            int held = askBid(round, bidders, hearer,
                    SkatAi.BidRole.HOLD, current, current);
            if (held != current) {
                observe(bidders, new SkatAi.BidEvent(hearer, current, true));
                return new DuelResult(announcer, current);
            }
            observe(bidders, new SkatAi.BidEvent(hearer, current, false));
        }
    }

    private int askBid(SkatAi.RoundPosition round,
                       EnumMap<SkatAi.Seat, SkatAiSession> bidders, SkatAi.Seat seat,
                       SkatAi.BidRole role, int current, int requested) {
        try {
            return bidders.get(seat).bid(new SkatAi.BidRequest(
                    round, seat, role, current, requested));
        } catch (RuntimeException invalid) {
            recordViolation(seat, ViolationPhase.BID);
            return 0;
        }
    }

    private void observe(EnumMap<SkatAi.Seat, SkatAiSession> bidders,
                         SkatAi.BidEvent event) {
        for (Map.Entry<SkatAi.Seat, SkatAiSession> bidder : bidders.entrySet()) {
            try { bidder.getValue().bidObserved(event); }
            catch (RuntimeException invalid) {
                recordViolation(bidder.getKey(), ViolationPhase.BID_OBSERVED);
            }
        }
    }

    private static int nextBid(int current) {
        return BidValues.next(current);
    }

    private static boolean validDiscard(Set<Card> discarded, List<Card> cards) {
        return discarded != null && discarded.size() == 2 && cards.containsAll(discarded);
    }

    /**
     * Substituted when a player fails to announce a permitted contract.
     *
     * <p>Prefers the strongest-looking contract among those that actually cover
     * {@code bidValue}. The engine must not hand a seat a contract that is
     * already overbid when a sound one exists -- that would be a loss caused by
     * the substitution rather than by play. When nothing covers the bid, the
     * contract with the highest guaranteed value is chosen, which at least
     * minimises the overbid charge.
     */
    private static SkatAi.ContractAnnouncement fallbackAnnouncement(List<Card> cards, int bidValue) {
        int best = -1;
        Contract selected = Contract.GRAND;
        int bestUncovered = -1;
        Contract uncovered = Contract.GRAND;
        // Deliberately only the trump games. A substituted contract has to be a
        // safe one, and Null is never a safe guess: it is a hand shape, not a
        // stronger or weaker version of the game the player meant to play.
        for (Contract contract : Contract.TRUMP_GAMES) {
            int score = 0;
            for (Card card : cards) {
                if (contract.isTrump(card)) score += 4;
                if (card.rank == Card.Rank.ACE) score += 2;
                if (card.rank == Card.Rank.TEN) score++;
            }
            int guaranteed = SkatRules.guaranteedValue(contract, cards);
            if (guaranteed >= bidValue) {
                if (score > best) { best = score; selected = contract; }
            } else if (guaranteed > bestUncovered) {
                bestUncovered = guaranteed;
                uncovered = contract;
            }
        }
        return SkatAi.ContractAnnouncement.skatGame(
                SkatAi.ContractType.valueOf(best >= 0 ? selected.name() : uncovered.name()));
    }

    private record DuelResult(SkatAi.Seat winner, int bidValue) {}
    private record AuctionResult(SkatAi.Seat declarer, int bidValue) {}

    /** What an auction produced. A null declarer is the Ramsch nobody bid for. */
    private record AuctionSetup(SkatAi.Seat declarer, int bidValue, Contract contract) {
        static AuctionSetup forRamsch() { return new AuctionSetup(null, 0, Contract.RAMSCH); }
        boolean ramsch() { return declarer == null; }
    }

    // ------------------------------------------------------------- Schieben

    /**
     * Told each time cards arrive somewhere during a Schieben, so a screen can
     * show them travelling.
     *
     * <p>Needed because the engine runs every automated leg inside one call: by
     * the time {@code confirmPush} returns, two seats may already have pushed and
     * the hands say nothing about the order it happened in. The observer sees the
     * legs in the order they occurred and can replay them at whatever pace reads.
     *
     * <p>Only the count travels, never the cards. Which two were pushed is
     * private to the seats that held them — that is the whole reason the
     * Schieben is worth anything as evidence.
     */
    public interface SchiebenObserver {
        /**
         * @param from the seat that pushed, or null for the skat itself arriving
         *             at forehand, which starts the Schieben off
         * @param to   the seat the cards landed in, or null for the table: the
         *             last push is nobody's, it is the skat the game is played
         *             with
         */
        void cardsPushed(SkatAi.Seat from, SkatAi.Seat to, int count);
    }

    private SchiebenObserver schiebenObserver;

    /** Watches the Schieben. One at a time; null clears it. */
    public synchronized void setSchiebenObserver(SchiebenObserver observer) {
        this.schiebenObserver = observer;
    }

    private void notifySchieben(SkatAi.Seat from, SkatAi.Seat to, int count) {
        SchiebenObserver observer = schiebenObserver;
        if (observer == null || count <= 0 || (from == null && to == null)) return;
        try {
            observer.cardsPushed(from, to, count);
        } catch (RuntimeException ignored) {
            // A screen that throws must not be able to stop the game.
        }
    }

    /**
     * Starts the Schieben and carries it as far as it will go without a person.
     *
     * <p>Resumable on purpose. Three legs, each one a hand of twelve and two
     * cards pushed on, and any of the three may belong to somebody who has to be
     * shown the cards and waited for. The state that survives between legs is
     * whose turn it is and what they were handed; everything else is the hands.
     */
    private void beginSchieben(SkatAi.RoundPosition round,
                               Map<SkatAi.Seat, SkatAiSession> sessions,
                               boolean interactive) {
        schiebenSessions.clear();
        schiebenSessions.putAll(sessions);
        schiebenInteractive = interactive;
        schiebenRound = round;
        pushLeg = 0;
        pushSeat = round.forehand;
        pushFrom = null;
        pushReceived = List.copyOf(dealtSkat.isEmpty() ? skat : dealtSkat);
        pendingDiscards.clear();
        advanceSchieben();
    }

    /**
     * Runs the outstanding legs until a person's turn comes or the skat is
     * settled. Leaves {@link Phase#PUSH} behind in the first case.
     */
    private void advanceSchieben() {
        while (pushSeat != null) {
            notifySchieben(pushFrom, pushSeat, pushReceived.size());
            hands[pushSeat.ordinal()].addAll(pushReceived);
            hands[pushSeat.ordinal()].sort(CardOrder.GRAND);
            if (schiebenInteractive && humanSeats.contains(pushSeat)) {
                phase = Phase.PUSH;
                cachedSnapshot = null;
                return;
            }
            SkatAiSession session = schiebenSessions.get(pushSeat);
            Set<Card> pushed = null;
            if (session != null) {
                try {
                    pushed = session.pushCards(new SkatAi.RamschPushContext(
                            schiebenRound(), pushSeat,
                            new LinkedHashSet<>(hands[pushSeat.ordinal()]),
                            pushReceived, pushFrom, receiverOf(pushLeg)));
                } catch (RuntimeException invalid) {
                    pushed = null;
                }
            }
            if (!SkatRules.legalRamschPush(pushed, hands[pushSeat.ordinal()])) {
                if (session != null) recordViolation(pushSeat, ViolationPhase.PUSH);
                pushed = SkatRules.defaultRamschPush(hands[pushSeat.ordinal()]);
            }
            completeLeg(pushed);
        }
    }

    /** Applies one finished leg and moves the Schieben on, or ends it. */
    private void completeLeg(Set<Card> pushed) {
        SkatAi.Seat pusher = pushSeat;
        SkatAi.Seat receiver = receiverOf(pushLeg);
        hands[pusher.ordinal()].removeAll(pushed);
        List<Card> carried = List.copyOf(pushed);
        SkatAi.RamschPushEvent event = new SkatAi.RamschPushEvent(pusher, receiver, carried.size());
        for (SkatAiSession listener : schiebenSessions.values()) {
            try { listener.ramschPushObserved(event); } catch (RuntimeException ignored) {}
        }
        pendingDiscards.clear();
        if (receiver == null) {
            // The last push is not to anybody: those two cards are the skat the
            // Ramsch is played with, and they go face down on the table.
            notifySchieben(pusher, null, carried.size());
            skat = Collections.unmodifiableList(new ArrayList<>(carried));
            pushSeat = null;
            pushFrom = null;
            pushReceived = Collections.emptyList();
            schiebenSessions.clear();
            return;
        }
        pushFrom = pusher;
        pushSeat = receiver;
        pushReceived = carried;
        pushLeg++;
    }

    /** Who this leg pushes to; null on the last one, whose push becomes the skat. */
    private SkatAi.Seat receiverOf(int leg) {
        return leg < 2 ? pushSeat.next() : null;
    }

    private SkatAi.RoundPosition schiebenRound() {
        return auctionRound != null ? auctionRound : schiebenRound;
    }

    /** True while a person owes the table two pushed cards. */
    public synchronized boolean awaitingPush() { return phase == Phase.PUSH; }

    /** The seat being asked to push, or null when nobody is. */
    public synchronized SkatAi.Seat pushingSeat() {
        return phase == Phase.PUSH ? pushSeat : null;
    }

    /** The two cards this seat was just handed, so they can be shown as new. */
    public synchronized List<Card> receivedCards() { return List.copyOf(pushReceived); }

    /** Who handed them over, or null when they are the skat off the table. */
    public synchronized SkatAi.Seat pushFrom() { return pushFrom; }

    /**
     * Who the outstanding push goes to, or null when it becomes the skat — which
     * is the case on the last leg, and is the difference the screen has to say.
     */
    public synchronized SkatAi.Seat pushReceiver() {
        return phase == Phase.PUSH ? receiverOf(pushLeg) : null;
    }

    /**
     * Chooses one card to push on. Mirrors {@link #discardOne} deliberately: it
     * is the same gesture, and it is reversible for exactly as long.
     *
     * @return false for a jack, a card not in the hand, or a third card
     */
    public synchronized boolean pushOne(Card card) {
        if (phase != Phase.PUSH || card == null || pendingDiscards.size() >= 2) return false;
        // Only the seat that is being asked may push, which matters the moment
        // more than one person is at the table: without it any client could
        // empty another seat's hand once the Schieben reached them.
        if (!humanSeats.contains(pushSeat)) return false;
        if (card.rank == Card.Rank.JACK) return false;
        ArrayList<Card> hand = hands[pushSeat.ordinal()];
        if (!hand.remove(card)) return false;
        pendingDiscards.add(card);
        cachedSnapshot = null;
        return true;
    }

    /** Takes a card back out of the push, up to the moment it is confirmed. */
    public synchronized boolean takeBackPush(Card card) {
        if (phase != Phase.PUSH || card == null || !humanSeats.contains(pushSeat)) return false;
        if (!pendingDiscards.remove(card)) return false;
        hands[pushSeat.ordinal()].add(card);
        hands[pushSeat.ordinal()].sort(CardOrder.GRAND);
        cachedSnapshot = null;
        return true;
    }

    /**
     * Hands the two chosen cards on, and carries the Schieben as far as it goes.
     *
     * @return the phase now waiting: {@link Phase#PUSH} for the next person, or
     *         {@link Phase#PLAY} once the skat is settled
     */
    public synchronized Phase confirmPush() {
        if (phase != Phase.PUSH || pendingDiscards.size() != 2
                || !humanSeats.contains(pushSeat)) {
            throw new IllegalStateException("Two cards must be chosen first");
        }
        completeLeg(new LinkedHashSet<>(pendingDiscards));
        advanceSchieben();
        if (pushSeat == null) {
            closeAuctionSessions();
            initializeTrickPlay();
        }
        return phase;
    }

    private void createAiSessions() {
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            if (humanSeats.contains(seat)) continue;
            SkatAiSession session = aiProviders.providerFor(seat).createSession();
            aiSessions.put(seat, session);
            session.startGame(new SkatAi.GameStartContext(
                    definition, seat, new LinkedHashSet<>(hands[seat.ordinal()])));
        }
    }

    public synchronized Card playAiCard() {
        return playAiCard(false);
    }

    /**
     * Like {@link #playAiCard()}, except that it stops short of the card when
     * its Kontra offer has just opened a human declarer's Re window.
     *
     * <p>Ruling 8 in {@code docs/rules.md}: Re follows immediately, before the
     * next card. An automated seat that says Kontra and plays in the same breath
     * buries that window in the one call that opened it — the declarer never saw
     * it. This variant returns {@code null} instead and leaves the deal waiting
     * on {@link #announceRe()} or {@link #declineRe()}; the next call plays the
     * withheld card, and cannot re-ask the Kontra question because the seat is
     * already in {@code contraAsked}.
     *
     * <p>A separate method rather than the behaviour, because the app's local
     * loop does not yet know how to wait here: it has no Re control to offer,
     * and an engine that pauses on a question nobody can answer is a game that
     * hangs. The server's protocol does have the control, so the server calls
     * this one. When the app grows the control it should switch too, and this
     * comment should go with it.
     */
    public synchronized Card playAiCardOrAwaitRe() {
        return playAiCard(true);
    }

    private Card playAiCard(boolean holdForRe) {
        SkatAi.Seat seat = SkatAi.Seat.values()[currentPlayer];
        if (humanSeats.contains(seat) || trick.size() == 3 || result != null) {
            throw new IllegalStateException("It is not an AI turn");
        }
        offerContra(seat);
        if (holdForRe && awaitingRe) return null;
        Set<Card> legal = currentLegalCards();
        SkatAi.DecisionContext context = decisionContext(seat, legal);
        Card card = null;
        try {
            card = aiSessions.get(seat).chooseCard(context);
        } catch (RuntimeException ignored) {
            recordViolation(seat, ViolationPhase.PLAY);
        }
        if (card == null || !legal.contains(card) || !hands[currentPlayer].contains(card)) {
            recordViolation(seat, ViolationPhase.PLAY);
            card = legal.iterator().next();
        }
        hands[currentPlayer].remove(card);
        play(seat, card);
        return card;
    }

    /** Backward-compatible name retained for existing callers and tests. */
    public synchronized Card playRandomAiCard() { return playAiCard(); }

    public synchronized boolean playHumanCard(Card card) {
        return playHumanCard(SkatAi.Seat.HUMAN, card);
    }

    /**
     * Positional play hook for deterministic UI and engine tests. The position
     * is evaluated against the current, already sorted human hand.
     */
    public synchronized boolean playHumanCardAt(int position) {
        if (position < 0 || position >= hands[HUMAN].size()) return false;
        return playHumanCard(hands[HUMAN].get(position));
    }

    /** Applies a card submitted by the person occupying {@code seat}. */
    public synchronized boolean playHumanCard(SkatAi.Seat seat, Card card) {
        if (seat == null || !humanSeats.contains(seat) || currentPlayer != seat.ordinal()
                || trick.size() == 3 || result != null) return false;
        if (!currentLegalCards().contains(card)) return false;
        if (!hands[seat.ordinal()].remove(card)) return false;
        play(seat, card);
        return true;
    }

    /**
     * Whether {@code seat} may still double. The window is one moment wide: the
     * seat has not yet played into the first trick, and it closes for good the
     * instant they do. Defenders only, one Kontra to a game, never in a Ramsch.
     */
    public synchronized boolean mayAnnounceContra(SkatAi.Seat seat) {
        return seat != null && result == null && phase == Phase.PLAY
                && definition.hasDeclarer() && seat != definition.declarer
                && contra == SkatAi.ContraLevel.NONE && completedTricks == 0
                && !contraAsked.contains(seat);
    }

    /** Whether the declarer still owes the table an answer to a Kontra. */
    public synchronized boolean mayAnnounceRe() {
        return awaitingRe && result == null;
    }

    /**
     * A person says Kontra, or the declarer answers Re.
     *
     * @return false when the moment for it has passed, which is not an error:
     *         a player who taps a fraction too late has simply missed it
     */
    public synchronized boolean announceContra(SkatAi.Seat seat) {
        if (!mayAnnounceContra(seat)) return false;
        registerContra(seat, SkatAi.ContraLevel.KONTRA);
        return true;
    }

    public synchronized boolean announceRe() {
        if (!mayAnnounceRe()) return false;
        registerContra(definition.declarer, SkatAi.ContraLevel.RE);
        awaitingRe = false;
        return true;
    }

    /** The declarer heard the Kontra and lets it stand. */
    public synchronized void declineRe() { awaitingRe = false; }

    public synchronized SkatAi.ContraLevel contraLevel() { return contra; }

    /**
     * Gives one AI seat its single chance to double, immediately before it plays.
     *
     * <p>Asked here rather than at the start of the game because that is when the
     * rules allow it, and the difference is the point: a defender playing third
     * has watched two cards fall and knows more than the one who leads.
     */
    private void offerContra(SkatAi.Seat seat) {
        if (!mayAnnounceContra(seat)) return;
        contraAsked.add(seat);
        SkatAiSession session = aiSessions.get(seat);
        if (session == null) return;
        boolean said;
        try {
            said = session.announceContra(new SkatAi.ContraContext(definition, seat,
                    new LinkedHashSet<>(hands[seat.ordinal()]),
                    new SkatAi.CurrentTrick(completedTricks, SkatAi.Seat.values()[leader], trick),
                    false));
        } catch (RuntimeException invalid) {
            recordViolation(seat, ViolationPhase.CONTRA);
            return;
        }
        if (said) registerContra(seat, SkatAi.ContraLevel.KONTRA);
    }

    /**
     * Puts a Kontra or a Re on the table and tells everyone, then asks the
     * declarer to answer a Kontra while they still can — the answer has to come
     * before the next card, and for an automated declarer this is that moment.
     */
    private void registerContra(SkatAi.Seat seat, SkatAi.ContraLevel level) {
        contra = level;
        contraSeat = seat;
        SkatAi.ContraEvent event = new SkatAi.ContraEvent(seat, level);
        for (SkatAiSession session : aiSessions.values()) {
            try { session.contraObserved(event); } catch (RuntimeException ignored) {}
        }
        cachedSnapshot = null;
        if (level != SkatAi.ContraLevel.KONTRA) return;
        SkatAi.Seat declarer = definition.declarer;
        SkatAiSession session = aiSessions.get(declarer);
        if (session == null) {
            awaitingRe = humanSeats.contains(declarer);
            return;
        }
        boolean said;
        try {
            said = session.announceContra(new SkatAi.ContraContext(definition, declarer,
                    new LinkedHashSet<>(hands[declarer.ordinal()]),
                    new SkatAi.CurrentTrick(completedTricks, SkatAi.Seat.values()[leader], trick),
                    true));
        } catch (RuntimeException invalid) {
            recordViolation(declarer, ViolationPhase.CONTRA);
            return;
        }
        if (said) registerContra(declarer, SkatAi.ContraLevel.RE);
    }

    private void play(SkatAi.Seat seat, Card card) {
        awaitingRe = false;
        contraAsked.add(seat);
        if (!trick.isEmpty()) {
            SkatAi.FollowClass ledClass = SkatRules.publicFollowClass(
                    definition.contract, trick.get(0).card);
            if (!SkatRules.publicFollowClass(definition.contract, card).equals(ledClass)) {
                voidClasses.get(seat).add(ledClass);
            }
        }
        SkatAi.PlayedCard play = new SkatAi.PlayedCard(seat, card);
        trick.add(play);
        playedCards.add(card);
        SkatAi.CardPlayedEvent event = new SkatAi.CardPlayedEvent(completedTricks, play);
        for (SkatAiSession session : aiSessions.values()) session.cardPlayed(event);
        if (trick.size() < 3) {
            currentPlayer = (currentPlayer + 1) % 3;
        } else {
            pendingTrickWinner = SkatRules.trickWinner(definition.contract, trick);
            currentPlayer = pendingTrickWinner.ordinal();
        }
        cachedSnapshot = null;
    }

    public synchronized boolean finishCompletedTrick() {
        if (trick.size() != 3 || pendingTrickWinner == null) return false;
        int points = 0;
        for (SkatAi.PlayedCard play : trick) points += SkatRules.cardPoints(play.card);
        SkatAi.CompletedTrick completed = new SkatAi.CompletedTrick(
                completedTricks, SkatAi.Seat.values()[leader], trick,
                pendingTrickWinner, points);
        history.add(completed);
        tricksWon.put(pendingTrickWinner, tricksWon.get(pendingTrickWinner) + 1);
        capturedPoints.put(pendingTrickWinner, capturedPoints.get(pendingTrickWinner) + points);
        SkatAi.TrickCompletedEvent event = new SkatAi.TrickCompletedEvent(completed);
        for (SkatAiSession session : aiSessions.values()) session.trickCompleted(event);
        trick.clear();
        completedTricks++;
        leader = pendingTrickWinner.ordinal();
        currentPlayer = leader;
        pendingTrickWinner = null;
        // A Null game is over the moment the declarer takes a trick: the
        // contract was "not one", and playing the remaining tricks out would
        // decide nothing. Every other game runs its full ten.
        boolean nullDecided = definition.contract.isNull()
                && definition.hasDeclarer() && tricksWon.get(definition.declarer) > 0;
        if (completedTricks == 10 || nullDecided) finishGame();
        cachedSnapshot = null;
        return result != null;
    }

    private void finishGame() {
        if (definition.isRamsch()) {
            finishRamsch();
            return;
        }
        int skatPoints = SkatRules.cardPoints(skat);
        int declarerCaptured = capturedPoints.get(definition.declarer);
        int declarerPoints = declarerCaptured + skatPoints;
        int defenderPoints = 120 - declarerPoints;
        ArrayList<Card> declarerCards = new ArrayList<>(skat);
        for (SkatAi.CompletedTrick completed : history) {
            for (SkatAi.PlayedCard play : completed.plays) {
                if (play.seat == definition.declarer) declarerCards.add(play.card);
            }
        }
        // Winning and the value are settled together: an overbid declarer loses
        // even with 61 or more card points.
        SkatRules.GameScore score = SkatRules.score(
                definition, declarerCards, declarerPoints, tricksWon);
        // Kontra and Re are a multiplication on the value, and a multiplication
        // commutes with the doubling of a loss -- so it lands here, once, on the
        // signed value, rather than being threaded through the scoring rules.
        result = new SkatAi.GameResult(definition, score.declarerWon(), declarerPoints,
                defenderPoints, skatPoints, score.gameValue() * contra.factor, score.overbid(),
                capturedPoints, tricksWon, definition.declarer, contra, false, false);
        for (SkatAiSession session : aiSessions.values()) session.endGame(result);
    }

    /**
     * Settles a Ramsch, which scores one seat and leaves the other two blank.
     *
     * <p>{@code declarerWon} is only true for a durchmarsch, and the point totals
     * are read as the scored seat's and everyone else's. There is no declarer to
     * be them.
     */
    private void finishRamsch() {
        int skatPoints = SkatRules.cardPoints(skat);
        SkatRules.RamschScore score = SkatRules.scoreRamsch(history, skat);
        result = new SkatAi.GameResult(definition, score.durchmarsch(), score.cardPoints(),
                120 - score.cardPoints(), skatPoints, score.value(), false,
                capturedPoints, tricksWon, score.scoredSeat(), SkatAi.ContraLevel.NONE,
                score.jungfrau(), score.durchmarsch());
        for (SkatAiSession session : aiSessions.values()) session.endGame(result);
    }

    private Set<Card> currentLegalCards() {
        return SkatRules.legalCards(definition.contract, hands[currentPlayer], trick);
    }

    private SkatAi.DecisionContext decisionContext(SkatAi.Seat seat, Set<Card> legal) {
        return new SkatAi.DecisionContext(
                definition,
                seat,
                new LinkedHashSet<>(hands[seat.ordinal()]),
                legal,
                new SkatAi.CurrentTrick(completedTricks, SkatAi.Seat.values()[leader], trick),
                new SkatAi.GameHistory(history),
                new SkatAi.DerivedGameKnowledge(playedCards, tricksWon, capturedPoints, voidClasses));
    }

    public synchronized Snapshot snapshot() {
        if (cachedSnapshot != null) return cachedSnapshot;
        ArrayList<List<Card>> copies = new ArrayList<>(3);
        for (ArrayList<Card> hand : hands) {
            copies.add(Collections.unmodifiableList(new ArrayList<>(hand)));
        }
        Set<Card> legal = result == null && trick.size() < 3
                ? currentLegalCards() : Collections.emptySet();
        cachedSnapshot = new Snapshot(
                Collections.unmodifiableList(copies),
                Collections.unmodifiableList(new ArrayList<>(trick)),
                Collections.unmodifiableList(new ArrayList<>(history)),
                skat,
                definition,
                leader,
                currentPlayer,
                completedTricks,
                legal,
                 pendingTrickWinner,
                 result,
                 ruleViolations.size(),
                 Collections.unmodifiableMap(new EnumMap<>(capturedPoints)),
                 defaultSeatNames(), humanSeatFlags(), phase,
                 Collections.unmodifiableList(new ArrayList<>(pendingDiscards)));
        return cachedSnapshot;
    }

    /** The decision a player was being asked for when it broke the API contract. */
    public enum ViolationPhase { BID, BID_OBSERVED, DISCARD, ANNOUNCE, PUSH, CONTRA, PLAY }

    /** One recorded violation: which seat, in which decision. */
    public record RuleViolation(SkatAi.Seat seat, ViolationPhase phase) {}

    /**
     * Records one API-contract violation against the seat and decision that
     * caused it.
     *
     * <p>An aggregate counter is not attributable: at a table seating two
     * different implementations it says a violation happened but not by whom,
     * which makes it worse than useless for comparing them. The phase matters
     * too -- a player that throws while bidding is being driven outside the
     * lifecycle it expects, while one that plays an illegal card is simply wrong.
     */
    private void recordViolation(SkatAi.Seat seat, ViolationPhase phase) {
        ruleViolations.add(new RuleViolation(seat, phase));
    }

    /** Violations in the current game, in the order they happened. */
    public synchronized List<RuleViolation> ruleViolations() {
        return List.copyOf(ruleViolations);
    }

    /** Violations in the current game, attributed to the seat that caused each. */
    public synchronized Map<SkatAi.Seat, Integer> ruleViolationsBySeat() {
        EnumMap<SkatAi.Seat, Integer> counts = zeroMap();
        for (RuleViolation violation : ruleViolations) {
            counts.merge(violation.seat(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    private static EnumMap<SkatAi.Seat, Integer> zeroMap() {
        EnumMap<SkatAi.Seat, Integer> result = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) result.put(seat, 0);
        return result;
    }

    public static final class Snapshot {
        public final List<List<Card>> hands;
        public final List<SkatAi.PlayedCard> trick;
        public final List<SkatAi.CompletedTrick> history;
        public final List<Card> skat;
        public final Contract contract;
        public final SkatAi.GameDefinition definition;
        public final int leader;
        public final int currentPlayer;
        public final int completedTricks;
        public final Set<Card> legalCards;
        public final SkatAi.Seat trickWinner;
        public final SkatAi.GameResult result;
        public final int aiRuleViolations;
        public final Map<SkatAi.Seat, Integer> capturedPoints;
        public final String[] seatNames;
        public final boolean[] humanSeats;
        /**
         * How far this deal has got. Everything before {@link Phase#PLAY} means
         * the cards are dealt but no card may be played yet, which is why
         * {@link #waitingForHuman()} answers no there however it may look.
         */
        public final Phase phase;
        /**
         * Cards the declarer has put back but not yet committed to. They are out
         * of the hand and on their way to the skat, and can still come back.
         */
        public final List<Card> pendingDiscards;

        public Snapshot(List<List<Card>> hands, List<SkatAi.PlayedCard> trick,
                 List<SkatAi.CompletedTrick> history, List<Card> skat,
                 SkatAi.GameDefinition definition, int leader, int currentPlayer,
                 int completedTricks, Set<Card> legalCards, SkatAi.Seat trickWinner,
                 SkatAi.GameResult result, int aiRuleViolations,
                 Map<SkatAi.Seat, Integer> capturedPoints) {
            this(hands, trick, history, skat, definition, leader, currentPlayer,
                    completedTricks, legalCards, trickWinner, result, aiRuleViolations,
                    capturedPoints, defaultSeatNames(), new boolean[]{true, false, false});
        }

        public Snapshot(List<List<Card>> hands, List<SkatAi.PlayedCard> trick,
                 List<SkatAi.CompletedTrick> history, List<Card> skat,
                 SkatAi.GameDefinition definition, int leader, int currentPlayer,
                 int completedTricks, Set<Card> legalCards, SkatAi.Seat trickWinner,
                 SkatAi.GameResult result, int aiRuleViolations,
                 Map<SkatAi.Seat, Integer> capturedPoints, String[] seatNames,
                 boolean[] humanSeats) {
            this(hands, trick, history, skat, definition, leader, currentPlayer,
                    completedTricks, legalCards, trickWinner, result, aiRuleViolations,
                    capturedPoints, seatNames, humanSeats, Phase.PLAY);
        }

        public Snapshot(List<List<Card>> hands, List<SkatAi.PlayedCard> trick,
                 List<SkatAi.CompletedTrick> history, List<Card> skat,
                 SkatAi.GameDefinition definition, int leader, int currentPlayer,
                 int completedTricks, Set<Card> legalCards, SkatAi.Seat trickWinner,
                 SkatAi.GameResult result, int aiRuleViolations,
                 Map<SkatAi.Seat, Integer> capturedPoints, String[] seatNames,
                 boolean[] humanSeats, Phase phase) {
            this(hands, trick, history, skat, definition, leader, currentPlayer,
                    completedTricks, legalCards, trickWinner, result, aiRuleViolations,
                    capturedPoints, seatNames, humanSeats, phase, Collections.emptyList());
        }

        public Snapshot(List<List<Card>> hands, List<SkatAi.PlayedCard> trick,
                 List<SkatAi.CompletedTrick> history, List<Card> skat,
                 SkatAi.GameDefinition definition, int leader, int currentPlayer,
                 int completedTricks, Set<Card> legalCards, SkatAi.Seat trickWinner,
                 SkatAi.GameResult result, int aiRuleViolations,
                 Map<SkatAi.Seat, Integer> capturedPoints, String[] seatNames,
                 boolean[] humanSeats, Phase phase, List<Card> pendingDiscards) {
            this.hands = hands;
            this.trick = trick;
            this.history = history;
            this.skat = skat;
            this.contract = definition.contract;
            this.definition = definition;
            this.leader = leader;
            this.currentPlayer = currentPlayer;
            this.completedTricks = completedTricks;
            this.legalCards = legalCards;
            this.trickWinner = trickWinner;
            this.result = result;
            this.aiRuleViolations = aiRuleViolations;
            this.capturedPoints = capturedPoints;
            this.seatNames = seatNames == null ? defaultSeatNames() : seatNames.clone();
            this.humanSeats = humanSeats == null
                    ? new boolean[]{true, false, false} : humanSeats.clone();
            this.phase = phase == null ? Phase.PLAY : phase;
            this.pendingDiscards = pendingDiscards == null
                    ? Collections.emptyList() : pendingDiscards;
        }

        public boolean waitingForHuman() {
            return waitingForHuman(SkatAi.Seat.HUMAN);
        }

        public boolean waitingForHuman(SkatAi.Seat seat) {
            return seat != null && phase == Phase.PLAY && result == null
                    && currentPlayer == seat.ordinal() && trick.size() < 3;
        }

        /**
         * True while a finished trick still sits on the table and this seat has
         * already been shown to have won it, with a further trick left for it to
         * lead.
         *
         * <p>Deliberately not a softer {@link #waitingForHuman()}. The engine
         * accepts no card in this state and will not until the trick is
         * finished; the question answered here is about the deal rather than
         * about input -- who is visibly about to be on turn -- so that a surface
         * can hand the hand back before the trick collection has finished
         * playing out. Anything acting on it must still gate the actual move on
         * {@link #waitingForHuman()}.
         */
        public boolean willLeadNextTrick(SkatAi.Seat seat) {
            if (seat == null || phase != Phase.PLAY || result != null) return false;
            if (!trickComplete() || trickWinner != seat) return false;
            // completedTricks still counts the tricks *before* this one. Eight
            // means the next trick is the tenth, which has no choice left in it
            // and is played out by the app itself; nine means this trick is the
            // tenth and nothing follows it at all.
            if (completedTricks >= 8) return false;
            // A Null is over the moment the declarer takes a trick, so a
            // declarer who has just won one never leads another.
            return !(contract.isNull() && definition.hasDeclarer()
                    && definition.declarer == seat);
        }

        /**
         * True when the completed trick still lying on the table is the last
         * trick of a Null.
         *
         * <p>The mirror of the {@code nullDecided} test in
         * {@link GameEngine#finishTrick()}, asked one moment earlier: there the
         * trick has been counted and the game is already over, here it is still
         * fanned out on the table and the app has yet to decide whether to
         * collect it. It must not. Null counts nothing, so there is no pile for
         * the cards to be worth anything in, and this particular trick is the
         * whole story of the deal -- flying it to a winner would take it off the
         * table the player is about to read it on.
         *
         * <p>Only that trick. The tenth trick of a Null the declarer got
         * through is collected like any other: a defender won it, the flight
         * to him is what says so, and the result screen that follows shows an
         * empty table rather than a trick nobody needs to re-read.
         */
        public boolean nullEndsHere() {
            if (!trickComplete() || trickWinner == null) return false;
            if (contract == null || !contract.isNull()) return false;
            if (!definition.hasDeclarer()) return false;
            return trickWinner == definition.declarer;
        }

        /** True while the deal is dealt but not yet being played. */
        public boolean beforePlay() { return phase != Phase.PLAY; }

        public boolean trickComplete() { return trick.size() == 3; }
        public boolean gameComplete() { return result != null; }
    }

    private static String[] defaultSeatNames() {
        return new String[]{SkatAi.Seat.HUMAN.label, SkatAi.Seat.OPPONENT_ONE.label,
                SkatAi.Seat.OPPONENT_TWO.label};
    }

    private boolean[] humanSeatFlags() {
        boolean[] result = new boolean[SkatAi.Seat.values().length];
        for (SkatAi.Seat seat : humanSeats) result[seat.ordinal()] = true;
        return result;
    }
}
