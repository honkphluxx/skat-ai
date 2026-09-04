package dev.skatklar.demo.ai;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.EnumMap;
import java.util.Map;
import org.jskat.ai.algorithmic.AlgorithmicAIPlayer;
import org.jskat.ai.newalgorithm.AlgorithmAI;
import org.jskat.data.GameContract;
import org.jskat.data.SkatGameData;
import org.jskat.data.Trick;
import org.jskat.player.AbstractJSkatPlayer;
import org.jskat.util.CardList;
import org.jskat.util.GameType;
import org.jskat.util.Player;

/**
 * Shared adapter around any stateful JSkat player.
 *
 * <p>JSkat ships several. {@code JSkatPlayerResolver.getAllAIPlayerImplementations()}
 * currently offers {@code newalgorithm.AlgorithmAI}, {@code ml.MLPlayer} and
 * {@code ml.MLPlayerPro} -- notably <em>not</em> the {@code algorithmic} player
 * this adapter defaulted to. The ML players are wired from the training module
 * rather than here, so that ONNX Runtime never reaches the Android build.
 */
public final class JSkatAiProvider implements SkatAiProvider {

    /**
     * Where a session gets its player, and where it gives it back.
     *
     * <p>Most implementations are cheap to construct and the default source just
     * builds a new one every time. The ONNX players are not: each construction
     * loads four models totalling hundreds of megabytes, and a 300-board arena
     * match asks for a session per seat per game -- 5400 of them. Those are
     * pooled instead, which is safe because a JSkat player is designed to be
     * reused: {@code newGame()} is its own reset hook and this adapter calls it
     * at the start of every deal.
     */
    public interface PlayerSource {
        AbstractJSkatPlayer borrow();

        /** Called once per session, after {@code finalizeGame()}. */
        default void release(AbstractJSkatPlayer player) {}
    }

    private final String id;
    private final String displayName;
    private final PlayerSource source;

    /**
     * The player the app and the server seat by default.
     *
     * <p>This was {@code algorithmic.AlgorithmicAIPlayer} until the arena measured
     * the two against each other at fixed contracts: {@code AlgorithmAI} wins
     * 60.3% of the games it declares against 27.1%, a difference of +30.8
     * tournament points per game (95% CI [+28.3, +33.2] over 1301 boards).
     * Upstream's {@code JSkatPlayerResolver} does not offer the older player at
     * all any more.
     */
    public JSkatAiProvider() {
        this("jskat-newalgorithm", "JSkat AlgorithmAI", AlgorithmAI::new);
    }

    public JSkatAiProvider(String id, String displayName, PlayerSource source) {
        this.id = id;
        this.displayName = displayName;
        this.source = source;
    }

    /** Explicit name for the default, for tooling that wants to be unambiguous. */
    public static JSkatAiProvider newAlgorithm() {
        return new JSkatAiProvider();
    }

    /** The superseded player, kept so the arena can still measure against it. */
    public static JSkatAiProvider algorithmic() {
        return new JSkatAiProvider("jskat-algorithmic", "JSkat Algorithmic",
                AlgorithmicAIPlayer::new);
    }

    /**
     * Seeds the generator JSkat's algorithmic players share.
     *
     * <p>They draw from one process-wide, unseeded {@code Random}, so a match
     * involving them is not reproducible unless a caller seeds it. Tooling that
     * promises "identical seeds replay exactly" has to call this; the app does
     * not care and does not.
     */
    public static void seedSharedRandom(long seed) {
        // Two of them: one in the newalgorithm players, one in CardList.
        org.jskat.ai.newalgorithm.AbstractAlgorithmAI.seedSharedRandom(seed);
        org.jskat.util.CardList.seedSharedRandom(seed);
    }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor(id, displayName, true);
    }

    @Override public SkatAiSession createSession() { return new Session(source); }

    private static final class Session implements SkatAiSession {
        private final PlayerSource source;
        private final AbstractJSkatPlayer delegate;

        Session(PlayerSource source) {
            this.source = source;
            this.delegate = source.borrow();
        }
        private final Map<SkatAi.Seat, Player> positions = new EnumMap<>(SkatAi.Seat.class);
        private SkatAi.GameStartContext start;
        private boolean ended;
        /**
         * A Ramsch, which this adapter does not hand to JSkat at all.
         *
         * <p>JSkat has a RAMSCH game type, but its players are built around a
         * declarer and {@code startGame} wants one; there is none. Rather than
         * feed a null position into a vendored component and find out what it
         * does, the session plays the same heuristic every other seat plays in a
         * Ramsch. The consequence is worth stating: a Ramsch cannot distinguish
         * two contestants, so any comparison that includes one is diluted by it.
         */
        private boolean ramsch;

        @Override public void prepareDeal(SkatAi.DealContext context) {
            mapPositions(context.round.forehand);
            delegate.setPlayerName("JSkat " + context.mySeat.label);
            delegate.newGame(positions.get(context.mySeat));
            delegate.takeCards(toJSkatCards(context.initialHand));
            delegate.setUpBidding();
        }

        @Override public int bid(SkatAi.BidRequest request) {
            if (ended) throw new IllegalStateException("JSkat session is closed");
            return request.role == SkatAi.BidRole.ANNOUNCE
                    ? delegate.bidMore(request.requestedBid)
                    : (delegate.holdBid(request.requestedBid) ? request.requestedBid : 0);
        }

        @Override public void bidObserved(SkatAi.BidEvent event) {
            if (!ended && !event.passed) {
                delegate.bidByPlayer(positions.get(event.seat), event.value);
            }
        }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            return !ended && delegate.pickUpSkat();
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            if (ended) throw new IllegalStateException("JSkat session is closed");
            delegate.takeSkat(toJSkatCards(context.skat));
            // In the embedded library the discard strategy otherwise falls back
            // to its Ramsch path (and global desktop options) before announceGame.
            // Select the normal declarer strategy without announcing early.
            prepareDeclarerStrategy(delegate, null);
            CardList discarded = delegate.discardSkat();
            LinkedHashSet<Card> result = new LinkedHashSet<>();
            for (org.jskat.util.Card card : discarded) result.add(fromJSkatCard(card));
            return result;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(
                SkatAi.ContractContext context) {
            if (ended) throw new IllegalStateException("JSkat session is closed");
            GameContract announced = delegate.announceGame();
            // JSkat 0.22's algorithmic BidEvaluator only ever suggests suit games.
            // Keep this compatibility policy inside the adapter: after JSkat has
            // performed its native bidding and discard, conservatively promote a
            // strong all-jack/high-card hand to Grand when the variant permits it.
            // Remove this branch when upstream exposes variant-aware announcements.
            if (context.rules.allowedTypes.contains(SkatAi.ContractType.GRAND)
                    && shouldPromoteToGrand(context.hand)) {
                return SkatAi.ContractAnnouncement.skatGame(SkatAi.ContractType.GRAND);
            }
            return new SkatAi.ContractAnnouncement(
                    SkatAi.ContractType.valueOf(announced.gameType().name()),
                    announced.hand(), announced.schneider(), announced.schwarz(),
                    announced.ouvert());
        }

        private static boolean shouldPromoteToGrand(Set<Card> hand) {
            int jacks = 0;
            int aces = 0;
            int tens = 0;
            for (Card card : hand) {
                if (card.rank == Card.Rank.JACK) jacks++;
                if (card.rank == Card.Rank.ACE) aces++;
                if (card.rank == Card.Rank.TEN) tens++;
            }
            return (jacks == 4 && aces >= 2)
                    || (jacks >= 3 && aces >= 3 && tens >= 1);
        }

        @Override public void startGame(SkatAi.GameStartContext context) {
            start = context;
            ramsch = context.game.isRamsch();
            if (ramsch) return;
            mapPositions(context.round.forehand);
            Player myPosition = positions.get(context.mySeat);
            delegate.setPlayerName("JSkat " + context.mySeat.label);
            delegate.newGame(myPosition);
            delegate.takeCards(toJSkatCards(context.initialHand));
            // Trick-play instances are deliberately separate from the auction instances.
            // JSkat normally creates this strategy during announceGame(); recreate only
            // that internal state for a freshly connected/pure trick-play component.
            if (context.mySeat == context.game.declarer) {
                prepareDeclarerStrategy(delegate, toGameType(context.game.contract));
            }
            delegate.startGame(positions.get(context.game.declarer),
                    new GameContract(toGameType(context.game.contract), false));
            delegate.setGameState(SkatGameData.GameState.TRICK_PLAYING);
            delegate.newTrick(0, positions.get(context.game.initialLeader));
        }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            if (ended || start == null) throw new IllegalStateException("JSkat session is not active");
            if (ramsch) return dev.skatklar.demo.ramsch.RamschPolicy.chooseCard(context);
            return fromJSkatCard(delegate.playCard());
        }

        @Override public void cardPlayed(SkatAi.CardPlayedEvent event) {
            if (ramsch) return;
            if (!ended) delegate.cardPlayed(positions.get(event.play.seat), toJSkatCard(event.play.card));
        }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            if (ended || ramsch) return;
            SkatAi.CompletedTrick source = event.trick;
            Trick trick = new Trick(source.trickNumber, positions.get(source.leader));
            for (SkatAi.PlayedCard play : source.plays) trick.addCard(toJSkatCard(play.card));
            trick.setTrickWinner(positions.get(source.winner));
            delegate.showTrick(trick);
            if (source.trickNumber < 9) {
                delegate.newTrick(source.trickNumber + 1, positions.get(source.winner));
            }
        }

        @Override public void endGame(SkatAi.GameResult result) {
            close();
        }

        @Override public void close() {
            if (ended) return;
            ended = true;
            try {
                // A Ramsch session never started the delegate, so there is
                // nothing for it to finalize -- but it still has to go back.
                if (!ramsch) delegate.finalizeGame();
            } finally {
                // Exactly once per session: the ended guard above makes close()
                // idempotent, and GameEngine calls endGame() and close() both.
                source.release(delegate);
            }
        }

        private void mapPositions(SkatAi.Seat firstLeader) {
            SkatAi.Seat seat = firstLeader;
            for (Player player : Player.values()) {
                positions.put(seat, player);
                seat = seat.next();
            }
        }
    }

    /**
     * Recreates the declarer's playing strategy for a component that reaches a
     * decision without having gone through the auction that normally builds it.
     *
     * <p>A per-implementation table rather than a shared interface, because this
     * is a compatibility quirk of two vendored classes and not a concept:
     *
     * <ul>
     *   <li>{@code AlgorithmicAIPlayer} builds its declarer strategy only in
     *       {@code announceGame()} and otherwise falls back to an opponent or
     *       Ramsch strategy, so it needs the fix-up before discarding and again
     *       at trick play.</li>
     *   <li>{@code AlgorithmAI} already builds one in {@code getCardsToDiscard()},
     *       so discarding is fine -- but its {@code startGame()} creates
     *       <em>only</em> opponent strategies, so a freshly connected declarer
     *       would defend against its own game. It needs the game type, which is
     *       why the discard call site passes null and trick play does not.</li>
     *   <li>The ML players read their knowledge at decision time and hold no
     *       per-game strategy object, so they need nothing here.</li>
     * </ul>
     */
    private static void prepareDeclarerStrategy(AbstractJSkatPlayer delegate, GameType gameType) {
        if (delegate instanceof AlgorithmicAIPlayer algorithmic) {
            algorithmic.prepareForPredeterminedDeclarerGame();
        } else if (delegate instanceof AlgorithmAI newAlgorithm && gameType != null) {
            newAlgorithm.prepareForPredeterminedDeclarerGame(gameType);
        }
    }

    private static GameType toGameType(Contract contract) {
        return GameType.valueOf(contract.name());
    }

    private static org.jskat.util.Card toJSkatCard(Card card) {
        return org.jskat.util.Card.getCard(
                org.jskat.util.Suit.valueOf(card.suit.name()),
                org.jskat.util.Rank.valueOf(card.rank.name()));
    }

    private static Card fromJSkatCard(org.jskat.util.Card card) {
        return new Card(Card.Suit.valueOf(card.getSuit().name()),
                Card.Rank.valueOf(card.getRank().name()));
    }

    private static CardList toJSkatCards(Iterable<Card> cards) {
        CardList result = new CardList();
        for (Card card : cards) result.add(toJSkatCard(card));
        return result;
    }
}
