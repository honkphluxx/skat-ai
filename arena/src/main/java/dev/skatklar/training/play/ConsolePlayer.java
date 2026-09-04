package dev.skatklar.training.play;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A player that asks a person.
 *
 * <p>It implements {@link SkatAiProvider}, which is the whole idea: the engine
 * has no notion of who or what is behind a seat, so a human is seated the same
 * way the search player is, gets asked the same questions in the same order, and
 * is bound by the same legality checks. Nothing in the engine, the arena or the
 * scoring knows the difference -- which means a hand played here is played under
 * exactly the rules the measurements were taken under, and a person can be
 * entered into a duplicate match against a measured opponent.
 *
 * <p><b>On measuring yourself against the AI.</b> That is a real use and the
 * reason this is a registered contestant rather than only a toy: every number in
 * this repository is AI against AI, and nobody knows where a human sits on that
 * scale. But read the duplicate scoring first. A duplicate match plays each
 * board twice with the sides swapped, so a person would see the same thirty-two
 * cards a second time knowing where they lie. The AI has no such memory between
 * the halves. Any human score taken that way flatters the human, and by an
 * amount nobody has measured. {@link PlayMain} deals each board once for exactly
 * that reason, and is the honest way to get a rough anchor.
 *
 * <p>Thread-safety: none, deliberately. There is one terminal and one person.
 * The arena forces a single thread when this player is entered.
 */
public final class ConsolePlayer implements SkatAiProvider {

    /** Registered under this id, so `--a=human` works. */
    public static final String ID = "human";

    private final Console console;

    public ConsolePlayer() { this(new Console()); }

    ConsolePlayer(Console console) { this.console = console; }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor(ID, "A person at a terminal", true);
    }

    @Override public SkatAiSession createSession() { return new Session(); }

    private final class Session implements SkatAiSession {

        private Contract contract;
        private SkatAi.Seat mySeat;
        private SkatAi.GameDefinition game;

        @Override public void prepareDeal(SkatAi.DealContext context) {
            mySeat = context.mySeat;
            contract = null;
            console.rule("Round " + context.round.roundNumber
                    + " -- you are " + position(context.round, context.mySeat));
            console.say("Your hand:  " + Console.hand(context.initialHand, null));
        }

        @Override public int bid(SkatAi.BidRequest request) {
            String role = switch (request.role) {
                case ANNOUNCE -> "You may bid";
                case HOLD -> "They bid -- do you hold";
            };
            boolean yes = console.confirm("  " + role + " " + request.requestedBid + "?", false);
            return yes ? request.requestedBid : 0;
        }

        @Override public void bidObserved(SkatAi.BidEvent event) {
            if (event.seat == mySeat) return;
            console.say(event.passed
                    ? "  " + Console.seat(event.seat) + " passes."
                    : "  " + Console.seat(event.seat) + " bids " + event.value + ".");
        }

        @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext context) {
            console.say("  You won the auction at " + context.winningBid + ".");
            return console.confirm("  Pick up the skat?", true);
        }

        @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
            console.say("  The skat held:  " + Console.hand(context.skat, null));
            console.say("  Twelve cards:   " + Console.hand(context.hand, null));
            List<Card> twelve = Console.sorted(context.hand, null);
            LinkedHashSet<Card> discarded = new LinkedHashSet<>();
            while (discarded.size() < 2) {
                List<Card> left = new ArrayList<>(twelve);
                left.removeAll(discarded);
                discarded.add(console.choose("  Lay away card " + (discarded.size() + 1) + " of 2:",
                        left, Console::name));
            }
            return discarded;
        }

        @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext context) {
            List<SkatAi.ContractType> allowed = new ArrayList<>(context.rules.allowedTypes);
            allowed.remove(SkatAi.ContractType.RAMSCH);
            allowed.remove(SkatAi.ContractType.PASSED_IN);
            console.say("  Your ten:  " + Console.hand(context.hand, null));
            SkatAi.ContractType type = console.choose("  What are you playing?", allowed,
                    SkatAi.ContractType::name);
            boolean hand = context.rules.handAllowed && !context.skatPickedUp
                    && console.confirm("  Hand game?", false);
            return new SkatAi.ContractAnnouncement(type, hand, false, false, false);
        }

        @Override public Set<Card> pushCards(SkatAi.RamschPushContext context) {
            console.say("  Schieberamsch: push two cards to "
                    + Console.seat(context.to) + ". Jacks may not go.");
            if (!context.received.isEmpty()) {
                console.say("  " + Console.seat(context.from) + " pushed you:  "
                        + Console.hand(context.received, null));
            }
            List<Card> pushable = new ArrayList<>();
            for (Card card : Console.sorted(context.hand, Contract.RAMSCH)) {
                if (card.rank != Card.Rank.JACK) pushable.add(card);
            }
            if (pushable.size() < 2) return SkatRules.defaultRamschPush(context.hand);
            LinkedHashSet<Card> pushed = new LinkedHashSet<>();
            while (pushed.size() < 2) {
                List<Card> left = new ArrayList<>(pushable);
                left.removeAll(pushed);
                pushed.add(console.choose("  Push card " + (pushed.size() + 1) + " of 2:",
                        left, Console::name));
            }
            return pushed;
        }

        @Override public boolean announceContra(SkatAi.ContraContext context) {
            String question = context.answeringKontra ? "  Re?" : "  Kontra?";
            return console.confirm(question, false);
        }

        @Override public void contraObserved(SkatAi.ContraEvent event) {
            console.say("  " + Console.seat(event.seat) + ": " + event.level + ".");
        }

        @Override public void startGame(SkatAi.GameStartContext context) {
            game = context.game;
            contract = context.game.contract;
            console.blank();
            console.say("  Contract: " + describe(context.game));
            console.say("  You are " + (context.game.hasDeclarer()
                    && context.game.declarer == context.mySeat
                    ? "the declarer." : "defending."));
        }

        @Override public Card chooseCard(SkatAi.DecisionContext context) {
            console.blank();
            console.say("  Trick " + (context.currentTrick.trickNumber + 1) + ":  "
                    + Console.trick(context.currentTrick));
            console.say("  Your hand:  " + Console.hand(context.hand, contract));
            List<Card> legal = Console.sorted(context.legalCards, contract);
            if (legal.size() < context.hand.size()) {
                console.say("  You must follow suit.");
            }
            return console.choose("  Your card:", legal, Console::name);
        }

        @Override public void trickCompleted(SkatAi.TrickCompletedEvent event) {
            SkatAi.CompletedTrick trick = event.trick;
            List<String> parts = new ArrayList<>();
            for (SkatAi.PlayedCard play : trick.plays) {
                parts.add(Console.seat(play.seat) + " " + Console.name(play.card));
            }
            console.say("  Trick " + (trick.trickNumber + 1) + ": " + String.join("  ", parts)
                    + "  ->  " + Console.seat(trick.winner)
                    + " (" + trick.cardPoints + ")");
        }

        @Override public void endGame(SkatAi.GameResult result) {
            // The engine keeps a pre-game session and a play session, and tells
            // both that the game ended -- so without this the result is printed
            // twice. `game` is set by startGame, which only the playing session
            // ever receives.
            if (game == null) return;
            console.rule("Result");
            if (result.game.isRamsch()) {
                console.say("  Ramsch. " + Console.seat(result.scoredSeat)
                        + " took the most, " + result.gameValue + " points"
                        + (result.durchmarsch ? " (Durchmarsch)" : "")
                        + (result.jungfrau ? " (Jungfrau)" : "") + ".");
            } else {
                console.say("  " + describe(result.game) + ": declarer took "
                        + result.declarerPoints + ", defence " + result.defenderPoints + ".");
                // gameValue already carries its sign: a lost game is negative,
                // and doubled, the way a score sheet records it. scoredSeat is
                // the declarer, won or lost, so naming them twice adds nothing.
                console.say("  " + Console.seat(result.scoredSeat)
                        + (result.gameValue < 0 ? " loses " : " scores ")
                        + Math.abs(result.gameValue)
                        + (result.overbid ? " (overbid)" : "") + ".");
            }
            game = null;
        }

        private String position(SkatAi.RoundPosition round, SkatAi.Seat seat) {
            if (seat == round.forehand) return "forehand";
            if (seat == round.middlehand) return "middlehand";
            return "rearhand";
        }
    }

    private static String describe(SkatAi.GameDefinition game) {
        StringBuilder said = new StringBuilder(game.contract.label);
        if (game.hand) said.append(", hand");
        if (game.schneiderAnnounced) said.append(", Schneider announced");
        if (game.schwarzAnnounced) said.append(", Schwarz announced");
        if (game.ouvert) said.append(", ouvert");
        if (game.bidValue > 0) said.append(" (bid ").append(game.bidValue).append(")");
        return said.toString();
    }
}
