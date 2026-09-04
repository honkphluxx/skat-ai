package dev.skatklar.training.arena;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.Map;
import java.util.function.Consumer;

/** Command line entry point for the headless duplicate arena. */
public final class ArenaMain {

    private static final String USAGE = """
            Duplicate-deal arena for SkatKlar AI implementations.

            Usage:
              arena --a=<player> --b=<player> [options]

            Options:
              --a=<player>      first contestant (required)
              --b=<player>      second contestant (required)
              --boards=<n>      boards to play; each is 6 games       (default 200)
              --seed=<n>        match seed; identical seeds replay exactly (default 1)
              --threads=<n>     parallel boards                        (default 1)
              --fixed-contract  compare card play only: replace the auction with a
                                predetermined declarer and contract, identical for
                                both sides. No board passes in.
              --contracts=<src> where the fixed contract comes from:
                                  auction  run the auction with --bidder (default)
                                  solver   the double-dummy oracle: the most
                                           valuable contract that survives perfect
                                           defence. Objective, and slower.
              --bidder=<player> the bidder for --contracts=auction (default
                                greedy). Use the strongest bidder you have; the
                                report prints the contract mix so you can judge
                                what you measured on.
              --csv=<path>      write the per-board differences
              --quiet           suppress progress output

            Players:
              a registered id, or class:<fully.qualified.ProviderClass>

            Threads default to 1 because the vendored JSkat player touches
            process-wide state. Validate a contestant before raising it.

            Default mode measures bidding and card play together, which is what a
            table does; --fixed-contract isolates card play by handing both sides
            the same declarer and contract.

            A match is decided on classic game points per game, the currency the
            canon settles in. Tournament points are still reported one row below
            because every measurement taken before 2026-08-17 was in them, and
            the two can disagree -- Seeger-Fabian pays a defender 40 whenever
            somebody else's game goes down, which flatters a player that neither
            declares well nor has to.
            """;

    private ArenaMain() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parse(args);
        if (options.containsKey("help") || options.containsKey("h")) {
            System.out.print(USAGE);
            return;
        }

        PlayerRegistry registry = PlayerRegistry.withDefaults();
        String a = options.get("a");
        String b = options.get("b");
        if (a == null || b == null) {
            System.out.print(USAGE);
            System.out.println("\nRegistered players: " + String.join(", ", registry.ids()));
            System.exit(2);
            return;
        }

        int boards = intOption(options, "boards", 200);
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        int threads = intOption(options, "threads", 1);
        boolean quiet = options.containsKey("quiet");
        boolean fixedContract = options.containsKey("fixed-contract");
        String bidder = options.getOrDefault("bidder", "greedy");
        String contractSource = options.getOrDefault("contracts", "auction");

        Contestant first = registry.resolve(a);
        Contestant second = registry.resolve(b);
        requireUsable(first);
        requireUsable(second);
        // There is one terminal and one person. Parallel boards would interleave
        // their prompts into something nobody can answer, so a match with a human
        // in it runs one board at a time whatever was asked for.
        if (threads != 1 && (dev.skatklar.training.play.ConsolePlayer.ID.equals(first.id())
                || dev.skatklar.training.play.ConsolePlayer.ID.equals(second.id()))) {
            System.out.println("A human is playing: running one board at a time.");
            threads = 1;
        }

        if (!quiet) {
            // Locale.ROOT throughout: a result line that reads "1.000 boards" on a
            // German machine and "1,000 boards" elsewhere is ambiguous in logs and
            // unusable in a diff between two runs.
            System.out.printf(Locale.ROOT,
                    "%s vs %s over %,d boards (%,d games), seed %d, %d thread(s)%n",
                    first.displayName(), second.displayName(),
                    boards, boards * DuplicateMatch.GAMES_PER_SIDE_AND_BOARD * 2, seed, threads);
        }

        long startedAt = System.nanoTime();
        Consumer<String> progress = quiet ? line -> {} : System.out::println;
        MatchResult result = fixedContract
                ? DuplicateMatch.runFixedContract(first, second, boards, seed, threads,
                        contracts(contractSource, registry, bidder, seed), progress)
                : DuplicateMatch.run(first, second, boards, seed, threads, progress);
        double seconds = (System.nanoTime() - startedAt) / 1e9;

        System.out.println();
        System.out.print(result.report());
        System.out.printf(Locale.ROOT, "%n%.1f s, %.0f games/s%n",
                seconds, result.games() / seconds);

        String csv = options.get("csv");
        if (csv != null) {
            // Absolute, and with the directory created. This task forks a JVM
            // whose working directory is the module rather than wherever the
            // command was typed, so a relative path lands somewhere the caller
            // did not mean -- and a missing directory used to take the whole run
            // down with an exception, after the match had already been played and
            // reported. Losing the CSV is a nuisance; losing the exit code of a
            // three-hour overnight run because of it is not.
            Path path = Path.of(csv).toAbsolutePath();
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, result.toCsv());
                System.out.println("Per-board differences written to " + path);
            } catch (IOException failure) {
                System.err.println("Could not write " + path + ": " + failure.getMessage());
            }
        }
    }

    private static ContractSource contracts(String source, PlayerRegistry registry,
                                            String bidder, long seed) {
        return switch (source) {
            case "auction" -> new AuctionContractSource(registry.resolve(bidder), seed);
            case "solver" -> new SolverContractSource();
            default -> throw new IllegalArgumentException(
                    "Unknown contract source '" + source + "'. Use auction or solver.");
        };
    }

    /**
     * Builds one session from a contestant before the match starts.
     *
     * <p>Constructing a session is what loads native libraries and model files, so
     * a contestant that cannot run fails here in a second with its own message,
     * rather than a thousand boards in, wrapped in "Board 0 failed".
     */
    private static void requireUsable(Contestant contestant) {
        try (SkatAiSession probe = contestant.newProvider(0L).createSession()) {
            if (probe == null) throw new IllegalStateException("no session");
        } catch (RuntimeException | LinkageError failure) {
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            System.err.println("Contestant '" + contestant.id() + "' cannot be used.");
            System.err.println("  " + failure.getMessage());
            if (root != failure) System.err.println("  root cause: " + root);
            System.exit(3);
        }
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String trimmed = arg.startsWith("--") ? arg.substring(2)
                    : arg.startsWith("-") ? arg.substring(1) : arg;
            int equals = trimmed.indexOf('=');
            if (equals < 0) options.put(trimmed, "");
            else options.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        return options;
    }
}
