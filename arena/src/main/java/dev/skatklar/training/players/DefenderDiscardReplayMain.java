package dev.skatklar.training.players;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.training.arena.AuctionContractSource;
import dev.skatklar.training.arena.Board;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.ContractSource;
import dev.skatklar.training.arena.GameOutcome;
import dev.skatklar.training.arena.GameRunner;
import dev.skatklar.training.arena.PlayerRegistry;
import dev.skatklar.training.arena.Seeds;
import dev.skatklar.training.arena.TableObserver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The play-outs' discard fix, proven narrow (docs/training-plan.md 2.10, R).
 *
 * <pre>
 *   gradlew :arena:defenderReplay --args="--seed=11 --boards=6 --threads=16"
 * </pre>
 *
 * <p>Plays every seating of a few boards exactly as the arena's duplicate match
 * does (fixed contracts by greedy's auction, the same seeds a seat and a
 * rotation), twice: once with the play-outs as they were before the fix
 * ({@link RolloutRescue.Replay#legacy}), once as they are, the second also
 * picking by the old form at every hand-over (the shadow, on the same decision
 * and the same random streams). Then, game by game:
 *
 * <ul>
 *   <li>every hand-over as declarer picks the same card in both forms -- the
 *       fix does not touch the declarer;</li>
 *   <li>a game with no hand-over whose two forms differ is the same game, card
 *       for card and score for score;</li>
 *   <li>otherwise the two games are the same up to the first such hand-over,
 *       which is a defender's, and part there, with the old game playing the
 *       shadow's card -- so the shadow is the old form exactly.</li>
 * </ul>
 *
 * <p>It counts the defenders' hand-overs and how many of them the fix moves.
 * Any break of the three rules is printed as a VIOLATION and the exit code is 1.
 */
public final class DefenderDiscardReplayMain {

    static final String BEST = "po:belief-64-shipped/rollout-32x4/lost-decl,lost-def,tie0-decl";
    static final String TEACHER = "belief-64-shipped";

    private DefenderDiscardReplayMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        String player = options.getOrDefault("player", BEST);
        String against = options.getOrDefault("against", TEACHER);
        long seed = Long.parseLong(options.getOrDefault("seed", "11"));
        int boards = Integer.parseInt(options.getOrDefault("boards", "6"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        System.out.printf(Locale.ROOT, "defender discard replay: %s against %s, seed %d, %d boards, %d threads%n",
                player, against, seed, boards, threads);
        long start = System.nanoTime();
        Report report = run(registry.resolve(player), registry.resolve(against),
                new AuctionContractSource(registry.resolve("greedy"), seed), seed, boards, threads);
        report.print();
        System.out.printf(Locale.ROOT, "took %.0f s%n", (System.nanoTime() - start) / 1e9);
        if (!report.violations.isEmpty()) System.exit(1);
    }

    /** What the replay found. */
    static final class Report {
        int games;
        int identical;
        int diverged;
        int declarerHandovers;
        int declarerMoved;
        int defenderHandovers;
        int defenderMoved;
        final List<String> violations = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        void print() {
            for (String line : lines) System.out.println(line);
            System.out.printf(Locale.ROOT, "%d games: %d the same card for card, %d parted at a defender's "
                    + "hand-over%n", games, identical, diverged);
            System.out.printf(Locale.ROOT, "declarer hand-overs: %d, the fix moved %d%n",
                    declarerHandovers, declarerMoved);
            System.out.printf(Locale.ROOT, "defender hand-overs: %d, the fix moved %d (%.1f%%)%n",
                    defenderHandovers, defenderMoved, 100.0 * defenderMoved / Math.max(1, defenderHandovers));
            if (violations.isEmpty()) {
                System.out.println("NARROW: only defenders' hand-overs moved, and every game parted exactly there");
            } else {
                for (String v : violations) System.out.println("VIOLATION " + v);
            }
        }
    }

    /** One game as played: every card in order, the score, and the hand-overs. */
    record Played(List<Card> cards, GameOutcome outcome, List<RolloutRescue.Decided> journal) {}

    static Report run(Contestant player, Contestant against, ContractSource contracts, long seed, int boards,
                      int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<String> labels = new ArrayList<>();
            List<Future<Played[]>> work = new ArrayList<>();
            for (int i = 0; i < boards; i++) {
                Board board = Board.of(seed, i);
                ContractSource.FixedContract fixed = contracts.contractFor(board);
                if (fixed == null || fixed.contract().isNull()) continue;
                for (SkatAi.Seat singleton : SkatAi.Seat.values()) {
                    for (boolean playerAlone : new boolean[] {true, false}) {
                        labels.add(String.format(Locale.ROOT, "board %d, %s %s alone (declarer %s, %s)", i,
                                playerAlone ? "player" : "opponent", singleton, fixed.declarer(), fixed.contract()));
                        Contestant alone = playerAlone ? player : against;
                        Contestant others = playerAlone ? against : player;
                        work.add(pool.submit(() -> new Played[] {
                                play(board, alone, others, singleton, seed, fixed, true, false),
                                play(board, alone, others, singleton, seed, fixed, false, true)}));
                    }
                }
            }
            Report report = new Report();
            for (int g = 0; g < work.size(); g++) {
                Played[] pair = work.get(g).get();
                compare(labels.get(g), pair[0], pair[1], report);
            }
            return report;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One rotation of one board, seeded as {@code DuplicateMatch.playRotation} seeds it. */
    static Played play(Board board, Contestant singleton, Contestant others, SkatAi.Seat singletonSeat, long seed,
                       ContractSource.FixedContract fixed, boolean legacy, boolean shadow) {
        RolloutRescue.Replay replay = new RolloutRescue.Replay(legacy, shadow);
        List<Card> cards = new ArrayList<>(30);
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            Contestant contestant = seat == singletonSeat ? singleton : others;
            seating.put(seat, recorded(contestant.newProvider(Seeds.mix(
                    seed, board.index(), singletonSeat.ordinal(), seat.ordinal())), cards));
        }
        long engineSeed = Seeds.mix(seed, board.index(), singletonSeat.ordinal(), 0xE1E1E1L);
        RolloutRescue.REPLAY.set(replay);
        try {
            GameOutcome outcome = GameRunner.playFixed(board, seating, fixed, engineSeed);
            return new Played(cards, outcome, List.copyOf(replay.journal));
        } finally {
            RolloutRescue.REPLAY.remove();
        }
    }

    /** The three rules of the class comment, on one rotation played in both forms. */
    static void compare(String label, Played old, Played fixed, Report report) {
        report.games++;
        int firstMoved = -1;
        for (RolloutRescue.Decided d : fixed.journal()) {
            boolean moved = d.other() == null || !d.other().equals(d.picked());
            if (d.declarer()) {
                report.declarerHandovers++;
                if (moved) {
                    report.declarerMoved++;
                    report.violations.add(label + ": a declarer's hand-over moved, after " + d.cardsBefore()
                            + " cards: " + d.other() + " -> " + d.picked());
                }
            } else {
                report.defenderHandovers++;
                if (moved) report.defenderMoved++;
            }
            if (moved && firstMoved < 0) firstMoved = d.cardsBefore();
        }
        int parted = firstDifference(old.cards(), fixed.cards());
        if (firstMoved < 0) {
            if (parted >= 0 || !old.outcome().equals(fixed.outcome())) {
                report.violations.add(label + ": no hand-over moved, yet the games differ"
                        + (parted >= 0 ? " at card " + parted : " in their score"));
            } else {
                report.identical++;
            }
            return;
        }
        report.diverged++;
        RolloutRescue.Decided first = null;
        for (RolloutRescue.Decided d : fixed.journal()) {
            if (d.cardsBefore() == firstMoved) { first = d; break; }
        }
        if (parted != firstMoved) {
            report.violations.add(label + ": the first moved hand-over is at card " + firstMoved
                    + ", the games part at card " + parted);
        } else if (!old.cards().get(parted).equals(first.other())) {
            report.violations.add(label + ": at card " + parted + " the old game played " + old.cards().get(parted)
                    + ", the shadow said " + first.other());
        } else if (!fixed.cards().get(parted).equals(first.picked())) {
            report.violations.add(label + ": at card " + parted + " the game played " + fixed.cards().get(parted)
                    + ", the play-outs picked " + first.picked());
        } else {
            report.lines.add(String.format(Locale.ROOT, "  %s: parted at card %d, %s defending: %s -> %s",
                    label, parted, first.seat(), first.other(), first.picked()));
        }
    }

    /** The index of the first card where two games differ, or -1 when one is the other. */
    static int firstDifference(List<Card> a, List<Card> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) if (!a.get(i).equals(b.get(i))) return i;
        return a.size() == b.size() ? -1 : n;
    }

    /** Both faces a seated provider may have. */
    interface Seated extends SkatAiProvider, TableObserver {}

    /** A seat that writes down every card it plays, in the order of the game, and is otherwise the provider. */
    static SkatAiProvider recorded(SkatAiProvider provider, List<Card> cards) {
        return new Seated() {
            @Override public SkatAi.AiDescriptor descriptor() { return provider.descriptor(); }
            @Override public void observe(GameEngine engine) {
                if (provider instanceof TableObserver o) o.observe(engine);
            }
            @Override public void observeFixedContract(Board board, ContractSource.FixedContract fixed) {
                if (provider instanceof TableObserver o) o.observeFixedContract(board, fixed);
            }
            @Override public SkatAiSession createSession() {
                SkatAiSession inner = provider.createSession();
                return (SkatAiSession) Proxy.newProxyInstance(DefenderDiscardReplayMain.class.getClassLoader(),
                        new Class<?>[] {SkatAiSession.class}, (proxy, method, args) -> {
                            Object result;
                            try {
                                result = method.invoke(inner, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                            if (method.getName().equals("chooseCard")) cards.add((Card) result);
                            return result;
                        });
            }
        };
    }
}
