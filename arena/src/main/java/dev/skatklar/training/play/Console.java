package dev.skatklar.training.play;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A terminal, and the small amount of taste required to make one playable.
 *
 * <p>Kept apart from {@link ConsolePlayer} so that the player is about Skat and
 * this is about typing. Two decisions in here are worth stating, because both
 * are the kind that make the difference between a demo and something a person
 * will actually sit through:
 *
 * <ul>
 *   <li><b>Cards are chosen by their name, not by an index.</b> A menu of
 *       numbered options is easier to write and worse to use: the numbers move
 *       every trick, so the eye has to re-read the whole list before every card.
 *       {@code CJ} and {@code H10} do not move. Indices still work as a
 *       fallback, because someone will try them.</li>
 *   <li><b>The hand is sorted the way the contract sorts it.</b> In a Grand the
 *       jacks stand apart; in a suit game they belong to the trump suit; in a
 *       Null there is no trump at all and the ten sits between nine and jack.
 *       Showing a person a hand in a different order from the one the game is
 *       played in is a way of asking them to make the mistake.</li>
 * </ul>
 *
 * <p>End of input is not an error. A pipe that runs dry, or a Ctrl-D, means the
 * person has stopped playing, and the reply is the same as it would be for an
 * absent player: {@link #closed()} goes true and every prompt returns its
 * default, so the hand finishes rather than throwing on a half-played trick.
 */
final class Console {

    private final BufferedReader in;
    private final PrintStream out;
    private boolean closed;

    Console() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    Console(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    /** True once the input has ended; every prompt then answers with its default. */
    boolean closed() { return closed; }

    void say(String line) { out.println(line); }

    void blank() { out.println(); }

    /**
     * A section heading, in ASCII.
     *
     * <p>Box-drawing characters were the first draft and they came out as
     * {@code ??} on the first terminal this was run on: a JVM whose default
     * charset is not UTF-8 mangles them on the way out, which on Windows is the
     * common case rather than the exotic one. A rule made of hyphens is worth
     * less than one made of line characters and is worth a great deal more than
     * one made of question marks. The same reasoning applies to every string in
     * this file: it is all ASCII on purpose.
     */
    void rule(String title) {
        out.println();
        out.println("-- " + title + " " + "-".repeat(Math.max(0, 58 - title.length())));
    }

    /**
     * Asks until the answer names one of {@code choices}, or the input ends.
     *
     * @param render how a choice appears and what the person may type for it
     * @return the chosen element, or the first one if the input has ended
     */
    <T> T choose(String prompt, List<T> choices, java.util.function.Function<T, String> render) {
        if (choices.isEmpty()) throw new IllegalArgumentException("nothing to choose from");
        if (choices.size() == 1) {
            out.println(prompt + "  -> " + render.apply(choices.get(0)) + " (the only one)");
            return choices.get(0);
        }
        List<String> names = new ArrayList<>();
        for (T choice : choices) names.add(render.apply(choice));
        while (true) {
            out.println(prompt);
            out.println("   " + String.join("   ", numbered(names)));
            String answer = readLine("> ");
            if (answer == null) return choices.get(0);
            answer = answer.trim();
            if (answer.isEmpty()) continue;
            for (int at = 0; at < names.size(); at++) {
                if (names.get(at).equalsIgnoreCase(answer)) return choices.get(at);
            }
            try {
                int index = Integer.parseInt(answer);
                if (index >= 1 && index <= choices.size()) return choices.get(index - 1);
            } catch (NumberFormatException notANumber) {
                // fall through to the complaint below
            }
            out.println("   Not one of those. Type the name, or its number.");
        }
    }

    /** A yes/no question. The default is what an absent player is taken to mean. */
    boolean confirm(String prompt, boolean fallback) {
        while (true) {
            String answer = readLine(prompt + " [y/n] ");
            if (answer == null) return fallback;
            answer = answer.trim().toLowerCase(Locale.ROOT);
            if (answer.startsWith("y") || answer.startsWith("j")) return true;
            if (answer.startsWith("n")) return false;
            if (answer.isEmpty()) return fallback;
        }
    }

    /** A whole number in range, or the fallback once the input has ended. */
    int number(String prompt, int low, int high, int fallback) {
        while (true) {
            String answer = readLine(prompt + " [" + low + ".." + high + "] ");
            if (answer == null) return fallback;
            answer = answer.trim();
            if (answer.isEmpty()) return fallback;
            try {
                int value = Integer.parseInt(answer);
                if (value >= low && value <= high) return value;
            } catch (NumberFormatException notANumber) {
                // fall through
            }
            out.println("   A number between " + low + " and " + high + ", please.");
        }
    }

    /** Null once the input has ended, which is not an error. */
    String readLine(String prompt) {
        if (closed) return null;
        out.print(prompt);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) {
                closed = true;
                out.println();
                out.println("(input ended -- playing the rest of the hand for you)");
            }
            return line;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    private static List<String> numbered(List<String> names) {
        List<String> out = new ArrayList<>(names.size());
        for (int at = 0; at < names.size(); at++) out.add((at + 1) + ") " + names.get(at));
        return out;
    }

    // ------------------------------------------------------------- rendering

    /** {@code CJ}, {@code H10}, {@code SA} -- suit letter then rank, no spaces. */
    static String name(Card card) {
        return switch (card.suit) {
            case CLUBS -> "C";
            case SPADES -> "S";
            case HEARTS -> "H";
            case DIAMONDS -> "D";
        } + card.rank.label;
    }

    /** The hand in the order the contract puts it in; trumps first. */
    static List<Card> sorted(Iterable<Card> cards, Contract contract) {
        List<Card> ordered = new ArrayList<>();
        for (Card card : cards) ordered.add(card);
        Comparator<Card> order = contract == null
                ? Contract.GRAND.preferredCardOrder : contract.preferredCardOrder;
        ordered.sort(order);
        return ordered;
    }

    static String hand(Iterable<Card> cards, Contract contract) {
        List<String> names = new ArrayList<>();
        for (Card card : sorted(cards, contract)) names.add(name(card));
        return String.join(" ", names);
    }

    static String trick(SkatAi.CurrentTrick trick) {
        if (trick == null || trick.plays.isEmpty()) return "(you lead)";
        List<String> parts = new ArrayList<>();
        for (SkatAi.PlayedCard play : trick.plays) {
            parts.add(seat(play.seat) + " " + name(play.card));
        }
        return String.join("   ", parts);
    }

    static String seat(SkatAi.Seat seat) {
        return switch (seat) {
            case HUMAN -> "You";
            case OPPONENT_ONE -> "West";
            case OPPONENT_TWO -> "East";
        };
    }
}
