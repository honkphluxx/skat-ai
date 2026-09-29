package dev.skatklar.training.arena;

import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.search.SearchAiProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * What a card costs, trick by trick (plan 2.10, I4): the phone's question.
 *
 * <pre>
 *   gradlew :arena:cardTime --args="--players=belief-32-shipped,hybrid-64-t2 --boards=40"
 * </pre>
 *
 * <p>Each player takes all three seats of the same boards at the same
 * contracts (the greedy auction's, as the arena's --fixed-contract), one board
 * at a time so a decision has the machine to itself, and every card decision
 * with a choice is timed by the player's own stopwatch
 * ({@link SearchAiProvider.CardPlayObserver#decided}): the wall time from the
 * decision's start to its card, the student's forward pass included. Printed
 * per trick, per game, and the share of a game's card-play time spent in the
 * first two tricks.
 *
 * <p>A desktop's numbers, not a phone's: what carries over is the ratio
 * between players, which is what decides whether a hybrid fits in the time
 * the phone's 32-world player takes today.
 */
public final class CardTimeMain {

    private CardTimeMain() {}

    public static void main(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        String[] ids = options.getOrDefault("players", "belief-32-shipped,belief-64-shipped,hybrid-64-t2")
                .split(",");
        int boards = Integer.parseInt(options.getOrDefault("boards", "20"));
        long seed = Long.parseLong(options.getOrDefault("seed", "11"));
        System.out.println(Rules.apply(options));
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);

        List<Board> played = new ArrayList<>();
        List<ContractSource.FixedContract> fixed = new ArrayList<>();
        for (int index = 0; played.size() < boards && index < boards * 4; index++) {
            Board board = Board.of(seed, index);
            ContractSource.FixedContract contract = contracts.contractFor(board);
            if (contract == null) continue;
            played.add(board);
            fixed.add(contract);
        }
        System.out.printf(Locale.ROOT, "%d boards, seed %d, each player in all three seats, %d cores%n%n",
                played.size(), seed, Runtime.getRuntime().availableProcessors());

        System.out.printf(Locale.ROOT, "%-20s", "ms a decision");
        for (int trick = 1; trick <= 9; trick++) System.out.printf(Locale.ROOT, "%8s", "trick " + trick);
        System.out.printf(Locale.ROOT, "%12s%12s%n", "s a game", "tricks 1-2");
        for (String id : ids) {
            Contestant player = registry.resolve(id.trim());
            AtomicLongArray nanos = new AtomicLongArray(11);
            AtomicLongArray count = new AtomicLongArray(11);
            SearchAiProvider.CardPlayObserver clock = report -> {
                int trick = Math.max(1, Math.min(10, 11 - report.cardsInHand()));
                nanos.addAndGet(trick, report.nanos());
                count.incrementAndGet(trick);
            };
            for (int at = 0; at < played.size(); at++) {
                Board board = played.get(at);
                Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
                for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                    SkatAiProvider provider = player.newProvider(Seeds.mix(seed, board.index(), seat.ordinal()));
                    if (provider instanceof SearchAiProvider search) provider = search.withCardPlayObserver(clock);
                    seating.put(seat, provider);
                }
                GameRunner.playFixed(board, seating, fixed.get(at), Seeds.mix(seed, board.index(), 0xE1E1E1L));
            }
            long total = 0;
            for (int trick = 1; trick <= 10; trick++) total += nanos.get(trick);
            System.out.printf(Locale.ROOT, "%-20s", id.trim());
            for (int trick = 1; trick <= 9; trick++) {
                System.out.printf(Locale.ROOT, "%8.1f", count.get(trick) == 0 ? 0.0
                        : nanos.get(trick) / 1e6 / count.get(trick));
            }
            System.out.printf(Locale.ROOT, "%12.2f%11.0f%%%n", total / 1e9 / played.size(),
                    total == 0 ? 0.0 : 100.0 * (nanos.get(1) + nanos.get(2)) / total);
        }
        System.out.println();
        System.out.println("s a game: the card play of all three seats, auction excluded. Desktop time;");
        System.out.println("the ratio between players is what carries over to the phone.");
    }
}
