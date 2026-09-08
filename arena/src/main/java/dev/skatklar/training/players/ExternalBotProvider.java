package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.training.arena.TableObserver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seats an outside engine that runs as a helper process -- XSkat or go-skat --
 * at one seat of an arena game.
 *
 * <h2>Why this implements {@link TableObserver}</h2>
 *
 * <p>Both engines are complete programs. Neither has an entry point that means
 * "given this position, play a card"; what they have is a game loop that deals,
 * bids, plays and scores, and whose card-play strength is inseparable from the
 * memory it builds up while that loop runs. So the helper plays a real game of
 * its own, with the arena supplying the other two seats' cards where its user
 * interface used to supply a human's -- and a real game needs all three hands
 * present in its own data structures before the first card.
 *
 * <p>That is a genuine risk and it is treated as one. Handing an engine the
 * cards it should not see would flatter it, and no amount of reading the source
 * settles whether it looks. So the helper carries an honesty control: with
 * {@code --probe=<n>}, every card it plays is asked for again under {@code n}
 * random reshuffles of exactly the cards that seat has not seen, and the run
 * reports how often the answer changed. Zero mismatches over a match is the
 * evidence that the score is honest; anything else invalidates it and is
 * printed rather than swallowed. See {@code docs/external-bots.md}.
 *
 * <p>A Ramsch is delegated to {@link GreedyAiProvider}: the arena's canon and
 * XSkat's Schieberamsch are not the same game, and a contestant that plays a
 * different Ramsch would poison the only measurement it exists to make.
 */
public final class ExternalBotProvider implements SkatAiProvider, TableObserver {

    private static final AtomicLong PROBE_DECISIONS = new AtomicLong();
    private static final AtomicLong PROBE_MISMATCHES = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong DIVERGENCES = new AtomicLong();
    private static final AtomicLong MISMATCH_DECLARER = new AtomicLong();
    private static final AtomicLong MISMATCH_DEFENDER = new AtomicLong();
    private static final AtomicLong[] MISMATCH_BY_TRICK = new AtomicLong[10];
    static {
        for (int i = 0; i < 10; i++) MISMATCH_BY_TRICK[i] = new AtomicLong();
    }

    private final String id;
    private final String displayName;
    private final java.util.List<String> command;
    private final long seed;
    private final int probeWorlds;
    private final boolean sampledWorld;
    private final SkatAiProvider delegate = new GreedyAiProvider();
    private GameEngine engine;

    /**
     * Printed at exit rather than folded into the match report, so that the
     * arena's own output format is untouched and the control's numbers cannot
     * be mistaken for part of the score.
     */
    private static void reportOnExit() {
        if (!SUMMARY_ARMED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            long decisions = PROBE_DECISIONS.get();
            if (decisions == 0) return;
            long mismatches = PROBE_MISMATCHES.get();
            System.out.printf("%nHonesty control: %d of %d decisions changed under "
                    + "reshuffles of the cards the seat cannot see (%.2f%%)"
                    + "%n  as declarer %d, as defender %d, by trick %s"
                    + "%n  rule divergences %d, delegated games %d%n",
                    mismatches, decisions, 100.0 * mismatches / decisions,
                    MISMATCH_DECLARER.get(), MISMATCH_DEFENDER.get(),
                    java.util.Arrays.toString(mismatchesByTrick()),
                    DIVERGENCES.get(), FALLBACKS.get());
            if (mismatches > 0) {
                System.out.println("  A non-zero count means the score above is not "
                        + "an honest player's. Run the same match against the -blind "
                        + "variant to price it.");
            }
        }));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean SUMMARY_ARMED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ExternalBotProvider(String id, String displayName, java.util.List<String> command,
                               long seed, int probeWorlds, boolean sampledWorld) {
        this.id = id;
        this.displayName = displayName;
        this.command = command;
        this.seed = seed;
        this.probeWorlds = probeWorlds;
        this.sampledWorld = sampledWorld;
        if (probeWorlds > 1) reportOnExit();
    }

    /** Decisions probed and answers that changed, across every helper this run. */
    public static long probedDecisions() { return PROBE_DECISIONS.get(); }
    public static long probeMismatches() { return PROBE_MISMATCHES.get(); }
    /** Games this provider had to hand to the delegate. Should be Ramsch only. */
    public static long fallbacks() { return FALLBACKS.get(); }
    /** Cards our engine called legal that the helper's own rules did not, or vice versa. */
    public static long legalityDivergences() { return DIVERGENCES.get(); }

    /** Where the probe's mismatches fell: as declarer, as defender, and by trick. */
    public static long mismatchesAsDeclarer() { return MISMATCH_DECLARER.get(); }
    public static long mismatchesAsDefender() { return MISMATCH_DEFENDER.get(); }
    public static long[] mismatchesByTrick() {
        long[] copy = new long[10];
        for (int i = 0; i < 10; i++) copy[i] = MISMATCH_BY_TRICK[i].get();
        return copy;
    }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor(id, displayName, true);
    }

    @Override public void observe(GameEngine engine) { this.engine = engine; }

    @Override public SkatAiSession createSession() { return new Session(); }

    // ---- notation -------------------------------------------------------

    private static final String SUITS = "CSHD";      // Card.Suit ordinal order
    private static final char[] SUIT_LETTER = {'C', 'S', 'H', 'D'};
    private static final char[] RANK_LETTER = {'7', '8', '9', 'T', 'J', 'Q', 'K', 'A'};

    static String encode(Card card) {
        return "" + SUIT_LETTER[card.suit.ordinal()] + RANK_LETTER[card.rank.ordinal()];
    }

    static Card decode(String text) {
        if (text == null || text.length() != 2) return null;
        int suit = SUITS.indexOf(text.charAt(0));
        int rank = new String(RANK_LETTER).indexOf(text.charAt(1));
        if (suit < 0 || rank < 0) return null;
        return new Card(Card.Suit.values()[suit], Card.Rank.values()[rank]);
    }

    private static char contractLetter(SkatAi.ContractType type) {
        switch (type) {
        case DIAMONDS: return 'D';
        case HEARTS:   return 'H';
        case SPADES:   return 'S';
        case CLUBS:    return 'C';
        case GRAND:    return 'G';
        case NULL:     return 'N';
        default:       return 'R';
        }
    }

    private static SkatAi.ContractType contractType(char letter) {
        switch (letter) {
        case 'D': return SkatAi.ContractType.DIAMONDS;
        case 'H': return SkatAi.ContractType.HEARTS;
        case 'S': return SkatAi.ContractType.SPADES;
        case 'C': return SkatAi.ContractType.CLUBS;
        case 'G': return SkatAi.ContractType.GRAND;
        case 'N': return SkatAi.ContractType.NULL;
        default:  return null;
        }
    }

    private static char letterFor(dev.skatklar.demo.Contract contract) {
        switch (contract) {
        case DIAMONDS: return 'D';
        case HEARTS:   return 'H';
        case SPADES:   return 'S';
        case CLUBS:    return 'C';
        case GRAND:    return 'G';
        case NULL:     return 'N';
        default:       return 'R';
        }
    }

    // ---- the session ----------------------------------------------------

    private final class Session implements SkatAiSession {
        private final SkatAiSession blind = delegate.createSession();
        private ExternalBot bot;
        private SkatAi.Seat mySeat;
        private List<Card> myHand = List.of();
        private int forehand;
        private int maxBid = -1;
        private char chosenGame;
        private boolean chosenHand;
        /** True once this deal is the delegate's: a Ramsch, or a helper we could not use. */
        private boolean delegated;

        private ExternalBot bot() {
            if (bot == null) bot = ExternalBot.borrow(command);
            return bot;
        }

        /**
         * The engine builds a fresh session for trick play rather than carrying
         * the bidding one over, so both entry points have to stand on their own.
         * Neither may assume the other ran.
         */
        private void begin(SkatAi.Seat seat, SkatAi.RoundPosition round) {
            mySeat = seat;
            forehand = round.forehand.ordinal();
            maxBid = -1;
            delegated = false;
            long dealSeed = seed * 1_000_003L + round.roundNumber * 31L;
            bot().ask("SEED " + Math.floorMod(dealSeed, 2_000_000_000L));
            if (probeWorlds > 1) bot().ask("PROBE " + probeWorlds);
            bot().ask("BLIND " + (sampledWorld ? 1 : 0));
        }

        @Override public void prepareDeal(SkatAi.DealContext context) {
            blind.prepareDeal(context);
            myHand = new ArrayList<>(context.initialHand);
            chosenGame = 0;
            chosenHand = false;
            begin(context.mySeat, context.round);
        }

        @Override public int bid(SkatAi.BidRequest request) {
            if (maxBid < 0) {
                String reply = bot().ask("MAXBID " + mySeat.ordinal() + " " + cards(myHand));
                maxBid = parseTrailingInt(reply, 0);
            }
            return maxBid >= request.requestedBid ? request.requestedBid : 0;
        }

        @Override public void bidObserved(SkatAi.BidEvent event) { blind.bidObserved(event); }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            String reply = bot().ask("HANDGAME " + mySeat.ordinal() + " " + context.winningBid
                    + " " + forehand + " " + cards(myHand));
            String[] parts = reply.split("\\s+");
            chosenHand = parts.length > 1 && "1".equals(parts[1]);
            if (parts.length > 3) chosenGame = parts[3].charAt(0);
            return !chosenHand;
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            List<Card> hand = new ArrayList<>(context.hand);
            String reply = bot().ask("DISCARD " + mySeat.ordinal() + " 0 " + forehand
                    + " " + cards(hand) + " " + cards(context.skat));
            String[] parts = reply.split("\\s+");
            Set<Card> discards = new LinkedHashSet<>();
            if (parts.length > 2) {
                Card first = decode(parts[1]);
                Card second = decode(parts[2]);
                if (first != null) discards.add(first);
                if (second != null) discards.add(second);
            }
            if (parts.length > 4) chosenGame = parts[4].charAt(0);
            Set<Card> available = new LinkedHashSet<>(hand);
            available.addAll(context.skat);
            if (discards.size() != 2 || !available.containsAll(discards)) {
                FALLBACKS.incrementAndGet();
                return blind.discardSkat(context);
            }
            return discards;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
            SkatAi.ContractType type = contractType(chosenGame);
            if (type == null || !context.rules.allowedTypes.contains(type)) {
                return blind.announceContract(context);
            }
            boolean hand = chosenHand && context.rules.handAllowed;
            SkatAi.ContractAnnouncement announcement =
                    new SkatAi.ContractAnnouncement(type, hand, false, false, false);
            return context.rules.permits(announcement) ? announcement
                    : blind.announceContract(context);
        }

        @Override public void startGame(SkatAi.GameStartContext context) {
            blind.startGame(context);
            if (mySeat == null) begin(context.mySeat, context.round);
            SkatAi.GameDefinition game = context.game;
            if (game.isRamsch() || engine == null) {
                delegated = true;
                FALLBACKS.incrementAndGet();
                return;
            }
            GameEngine.Snapshot snapshot = engine.snapshot();
            StringBuilder line = new StringBuilder("GAME ")
                    .append(mySeat.ordinal()).append(' ')
                    .append(game.declarer.ordinal()).append(' ')
                    .append(letterFor(game.contract)).append(' ')
                    .append(game.hand ? 1 : 0).append(' ')
                    .append(game.ouvert ? 1 : 0).append(' ')
                    .append(game.initialLeader.ordinal()).append(' ')
                    .append(Math.max(18, game.bidValue));
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                List<Card> hand = snapshot.hands.get(seat.ordinal());
                if (hand.size() != 10) { delegated = true; break; }
                line.append(' ').append(cards(hand));
            }
            if (delegated || snapshot.skat.size() != 2) {
                delegated = true;
                FALLBACKS.incrementAndGet();
                return;
            }
            line.append(' ').append(cards(snapshot.skat));
            bot().ask(line.toString());
        }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            if (delegated) return blind.chooseCard(context);
            String reply = bot().ask("PLAY");
            Card card = reply.startsWith("CARD ") ? decode(reply.substring(5).trim()) : null;
            if (card == null || !context.legalCards.contains(card)) {
                FALLBACKS.incrementAndGet();
                delegated = true;          // its game state and ours have parted company
                return blind.chooseCard(context);
            }
            return card;
        }

        @Override public void cardPlayed(SkatAi.CardPlayedEvent event) {
            blind.cardPlayed(event);
            if (delegated || event.play.seat == mySeat) return;
            bot().ask("PLAYED " + encode(event.play.card));
        }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            blind.trickCompleted(event);
        }

        @Override public void endGame(SkatAi.GameResult result) {
            blind.endGame(result);
            collectProbe();
            releaseBot();
        }

        @Override public void close() {
            blind.close();
            collectProbe();
            releaseBot();
        }

        /**
         * The helper resets its counters on every SEED, so these are this deal's
         * numbers and simply add up. Read once per deal -- endGame and close both
         * arrive for the same session.
         */
        private boolean collected;

        private void collectProbe() {
            if (bot == null || collected) return;
            collected = true;
            String[] parts = bot.ask("STATS").split("\\s+");
            // STATS decisions n mismatches m divergences d declarer x defender y
            //       tricks t1..t10
            if (parts.length >= 7) {
                PROBE_DECISIONS.addAndGet(Long.parseLong(parts[2]));
                PROBE_MISMATCHES.addAndGet(Long.parseLong(parts[4]));
                DIVERGENCES.addAndGet(Long.parseLong(parts[6]));
            }
            if (parts.length >= 21) {
                MISMATCH_DECLARER.addAndGet(Long.parseLong(parts[8]));
                MISMATCH_DEFENDER.addAndGet(Long.parseLong(parts[10]));
                for (int trick = 0; trick < 10; trick++) {
                    MISMATCH_BY_TRICK[trick].addAndGet(Long.parseLong(parts[12 + trick]));
                }
            }
        }

        private void releaseBot() {
            if (bot == null) return;
            bot.release();
            bot = null;
        }

        private String cards(List<Card> hand) {
            StringBuilder text = new StringBuilder();
            for (Card card : hand) {
                if (text.length() > 0) text.append(' ');
                text.append(encode(card));
            }
            return text.toString();
        }

        private String cards(Set<Card> hand) { return cards(new ArrayList<>(hand)); }

        private int parseTrailingInt(String reply, int fallback) {
            String[] parts = reply.split("\\s+");
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (RuntimeException malformed) {
                return fallback;
            }
        }
    }
}
