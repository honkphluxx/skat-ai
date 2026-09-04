package dev.skatklar.demo;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One auction, advanced one spoken bid at a time.
 *
 * <p>The auto-bidding variant runs its auction inside a single call, because
 * nobody is watching. As soon as a person sits at the table the auction becomes
 * something they take part in and watch happen, so it has to be a state machine:
 * the caller drives it with {@link #step()} on whatever clock makes the drama
 * read, and stops at {@link #pending()} whenever it is a person's turn to speak.
 *
 * <p>The procedure is the standard one. Middlehand bids to forehand until one of
 * them gives way; rearhand then bids to the survivor; if nobody has said
 * anything at all, forehand is asked whether they will take the game at 18. The
 * seat still standing is the declarer.
 *
 * <p>Every individual step is recorded in {@link #log()}, including steps an
 * automation answered on a player's behalf. A player who drops out at 20 has
 * told the table something, and no input convenience is allowed to swallow it.
 */
public final class Auction {

    /** What the seat on turn is being asked to do. */
    public enum Role {
        /** Say the next value, or pass. */
        ANNOUNCE,
        /** Hold the value just said, or pass. */
        HOLD
    }

    /** The question in front of one seat right now. */
    public record Decision(SkatAi.Seat seat, Role role, int value, int currentBid,
                           SkatAi.Seat opponent) {}

    /** One spoken step: a value said or held, or a pass at that value. */
    public record Step(SkatAi.Seat seat, int value, boolean passed, Role role) {}

    private final SkatAi.RoundPosition round;
    private final Set<SkatAi.Seat> humanSeats;
    private final Map<SkatAi.Seat, SkatAiSession> sessions;
    private final List<Step> log = new ArrayList<>();
    private final List<SkatAi.Seat> violations = new ArrayList<>();

    private SkatAi.Seat announcer;
    private SkatAi.Seat hearer;
    private int currentBid;
    private int stage;
    private boolean awaitingHold;
    private Decision pending;
    private boolean finished;
    private boolean passedIn;
    private SkatAi.Seat declarer;
    private int winningBid;
    /**
     * Seats that have been dealt to but have not yet looked at their cards.
     *
     * <p>They are neither asked anything nor told anything: a session consulted
     * before {@code prepareDeal} is consulted out of order, and one that hears a
     * bid before it has seen its hand loses the bid, because {@code prepareDeal}
     * is also where a player forgets the previous deal. So the auction skips
     * them in both directions, and whoever admits them replays what they missed
     * — see {@code GameEngine.admitPreparedSeats}.
     *
     * <p>Only ever consulted, and only ever cleared, on the thread that steps
     * the auction. That is what makes the replay exact: nothing can be spoken
     * between "this seat is deaf" and "this seat has heard everything".
     */
    private Predicate<SkatAi.Seat> unheard = seat -> false;

    /**
     * @param humanSeats seats a person answers for; every other seat must have a
     *                   session in {@code sessions}
     */
    public Auction(SkatAi.RoundPosition round, Set<SkatAi.Seat> humanSeats,
                   Map<SkatAi.Seat, SkatAiSession> sessions) {
        this.round = Objects.requireNonNull(round, "round");
        this.humanSeats = EnumSet.noneOf(SkatAi.Seat.class);
        if (humanSeats != null) this.humanSeats.addAll(humanSeats);
        this.sessions = new EnumMap<>(SkatAi.Seat.class);
        if (sessions != null) this.sessions.putAll(sessions);
        this.announcer = round.middlehand;
        this.hearer = round.forehand;
        this.currentBid = 0;
        this.stage = 0;
    }

    /**
     * Withholds the auction from seats that have not seen their cards yet.
     *
     * <p>Default: nobody. Only a deferred deal needs this, and only the engine
     * that deferred it knows who is still thinking.
     */
    public void withholdFrom(Predicate<SkatAi.Seat> unheard) {
        this.unheard = unheard == null ? seat -> false : unheard;
    }

    /** True when this seat is still owed a look at its cards. */
    public boolean withheldFrom(SkatAi.Seat seat) {
        return seat != null && !humanSeats.contains(seat) && unheard.test(seat);
    }

    public SkatAi.RoundPosition round() { return round; }
    public boolean finished() { return finished; }
    public boolean passedIn() { return passedIn; }
    public SkatAi.Seat declarer() { return declarer; }
    public int winningBid() { return winningBid; }
    public int currentBid() { return currentBid; }
    public List<Step> log() { return Collections.unmodifiableList(log); }
    /** Seats that broke the API contract while bidding, in the order they did. */
    public List<SkatAi.Seat> violations() { return List.copyOf(violations); }

    /** The question a person has to answer, or null when nobody is being asked. */
    public Decision pending() { return pending; }

    /** The question in front of whoever is on turn, person or not. */
    public Decision currentDecision() {
        if (finished) return null;
        if (stage == 2) {
            return new Decision(round.forehand, Role.ANNOUNCE, 18, 0, null);
        }
        if (awaitingHold) {
            return new Decision(hearer, Role.HOLD, currentBid, currentBid, announcer);
        }
        int next = BidValues.next(currentBid);
        // The ladder cannot run out in a real auction, but a hostile bid value
        // must not turn into an endless loop.
        if (next == 0) return null;
        return new Decision(announcer, Role.ANNOUNCE, next, currentBid, hearer);
    }

    /** True when the seat on turn is a person, so the caller must collect an answer. */
    public boolean waitingForPerson() {
        return pending != null;
    }

    /**
     * Advances the auction by exactly one spoken step.
     *
     * @return true when something was said. False means the auction has either
     *         finished or is waiting for a person; check {@link #pending()}.
     */
    public boolean step() {
        if (finished || pending != null) return false;
        Decision decision = currentDecision();
        if (decision == null) {
            finish(hearer, currentBid);
            return false;
        }
        if (humanSeats.contains(decision.seat())) {
            pending = decision;
            return false;
        }
        // Still thinking about its hand. Nothing is said and nothing is
        // recorded; the caller is expected to notice and come back.
        if (withheldFrom(decision.seat())) return false;
        apply(decision, askSession(decision));
        return true;
    }

    /**
     * Runs every step an automated seat can take right now.
     *
     * <p>Stops on a person's turn, on the end of the auction, and — for a
     * deferred deal — on a seat that has not seen its cards yet.
     */
    public void advanceAutomatic() {
        while (step()) {
            // Each iteration is one spoken bid.
        }
    }

    /**
     * Answers the pending question on behalf of the person sitting there.
     *
     * @param accept true to say or hold the value, false to pass
     */
    public void answer(boolean accept) {
        Decision decision = pending;
        if (decision == null) throw new IllegalStateException("Nobody is being asked");
        pending = null;
        apply(decision, accept);
    }

    /**
     * Answers a pending announcement by naming a value above the next rung — a
     * jump bid, which is legal and which some players want.
     *
     * <p>Only the value actually spoken is recorded, because only it was
     * spoken. That is the opposite of the automation case, where every rung the
     * app climbs on the player's behalf is a real bid and goes into the log.
     */
    public void announce(int value) {
        Decision decision = pending;
        if (decision == null || decision.role() != Role.ANNOUNCE) {
            throw new IllegalStateException("Nobody is being asked to announce");
        }
        if (stage == 2 || value == decision.value()) {
            answer(true);
            return;
        }
        if (value < decision.value() || BidValues.rung(value) < 0) {
            throw new IllegalArgumentException("Not a legal bid: " + value);
        }
        pending = null;
        currentBid = value;
        record(decision.seat(), value, false, Role.ANNOUNCE);
        awaitingHold = true;
    }

    private boolean askSession(Decision decision) {
        SkatAiSession session = sessions.get(decision.seat());
        if (session == null) return false;
        SkatAi.BidRole role = decision.role() == Role.ANNOUNCE
                ? SkatAi.BidRole.ANNOUNCE : SkatAi.BidRole.HOLD;
        try {
            return session.bid(new SkatAi.BidRequest(round, decision.seat(), role,
                    decision.currentBid(), decision.value())) == decision.value();
        } catch (RuntimeException invalid) {
            violations.add(decision.seat());
            return false;
        }
    }

    private void apply(Decision decision, boolean accepted) {
        if (stage == 2) {
            record(decision.seat(), 18, !accepted, Role.ANNOUNCE);
            if (accepted) finish(round.forehand, 18);
            else finishPassedIn();
            return;
        }
        if (decision.role() == Role.ANNOUNCE) {
            if (accepted) {
                currentBid = decision.value();
                record(decision.seat(), currentBid, false, Role.ANNOUNCE);
                awaitingHold = true;
            } else {
                // The value recorded is the one that was declined. That is the
                // informative number: "passed at 24" says how far a seat was
                // willing to be pushed, which is what the table reads.
                record(decision.seat(), decision.value(), true, Role.ANNOUNCE);
                endDuel(hearer);
            }
            return;
        }
        if (accepted) {
            record(decision.seat(), currentBid, false, Role.HOLD);
            awaitingHold = false;
        } else {
            record(decision.seat(), currentBid, true, Role.HOLD);
            endDuel(announcer);
        }
    }

    private void endDuel(SkatAi.Seat winner) {
        awaitingHold = false;
        if (stage == 0) {
            stage = 1;
            announcer = round.rearhand;
            hearer = winner;
            return;
        }
        // Nobody said a number: forehand is offered the game at 18.
        if (currentBid == 0 && winner == round.forehand) {
            stage = 2;
            return;
        }
        finish(winner, currentBid);
    }

    private void record(SkatAi.Seat seat, int value, boolean passed, Role role) {
        log.add(new Step(seat, value, passed, role));
        SkatAi.BidEvent event = new SkatAi.BidEvent(seat, value, passed);
        for (Map.Entry<SkatAi.Seat, SkatAiSession> entry : sessions.entrySet()) {
            // A seat that has not looked at its cards is told nothing, because
            // prepareDeal is where a player forgets the last deal and would
            // wipe this. It is replayed the whole log when it is admitted.
            if (withheldFrom(entry.getKey())) continue;
            try {
                entry.getValue().bidObserved(event);
            } catch (RuntimeException invalid) {
                violations.add(entry.getKey());
            }
        }
    }

    private void finish(SkatAi.Seat winner, int bid) {
        finished = true;
        pending = null;
        declarer = winner;
        winningBid = Math.max(18, bid);
    }

    private void finishPassedIn() {
        finished = true;
        passedIn = true;
        pending = null;
        declarer = null;
        winningBid = 0;
    }

    /**
     * Tells one seat every bid spoken so far, in order.
     *
     * <p>For a seat that was withheld while it thought: what it missed is
     * exactly the log, because nothing reached it while {@link #withheldFrom}
     * was true. Called by whoever admits the seat, on the thread that steps the
     * auction, so no bid can slip between the last replayed one and the first
     * live one.
     *
     * @return the seats that threw while being told, so the caller can record it
     */
    public List<SkatAi.Seat> replayBidsTo(SkatAi.Seat seat) {
        SkatAiSession session = sessions.get(seat);
        if (session == null) return List.of();
        for (Step step : log) {
            try {
                session.bidObserved(new SkatAi.BidEvent(step.seat(), step.value(),
                        step.passed()));
            } catch (RuntimeException invalid) {
                violations.add(seat);
                return List.of(seat);
            }
        }
        return List.of();
    }

    /** The highest value {@code seat} said or held, or 0 if it never bid. */
    public int highestSaid(SkatAi.Seat seat) {
        int highest = 0;
        for (Step step : log) {
            if (step.seat() == seat && !step.passed()) highest = Math.max(highest, step.value());
        }
        return highest;
    }
}
