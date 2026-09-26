package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.search.WorldSampler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * With the information held equal, does a better use of the worlds pick a better card than the vote?
 *
 * <p>The rollout audit (docs/training-plan.md 2.9, 2026-09-26) played every
 * legal card of 144 recorded declarer decisions out on the true deal. The card
 * that did best there was chosen by someone who knows where the cards are, so
 * its lead over the vote's card mixes the value of that knowledge with what
 * the vote does wrong with the knowledge it has. This separates the two.
 *
 * <p>For each decision of the audit it records the game again -- the same
 * boards, seeds and players, so the same decision comes back with the same
 * vote, which is checked against the audit's CSV -- and keeps the worlds the
 * player sampled for it. In each of those worlds (the ones the vote was
 * tallied over, nothing more) every legal card is forced and the game played
 * on by the real players: the hidden cards dealt as the world has them, the
 * history replayed card for card, then left to play. Unlike the vote, which
 * asks in each world whether a defence that sees every card can be beaten,
 * this asks what the game is worth against the defenders at the table.
 *
 * <p>The rollout's card is the one with the best mean over the worlds. Both it
 * and the vote's card are then scored on the audit's true-deal rollouts,
 * which were drawn with other seeds on another deal, so the choice and its
 * score share no noise. Per band: how often the two agree, the true-deal game
 * points of each, and the paired difference with its interval. A clear
 * positive difference means the counting loses points the worlds already
 * hold; a difference near zero means the gap to the cheat is the information.
 *
 * <pre>./gradlew :arena:worldRollout --args="--seeds=14,15,16 --from=arena-logs/rollout --threads=16 --out=arena-logs/rollout/worlds"</pre>
 */
public final class WorldRolloutMain {

    private WorldRolloutMain() {}

    /** One decision of the audit's CSV. */
    record AuditRow(long seed, int board, int index, RolloutAuditMain.Band band, String played,
                    Map<String, Integer> votes, Map<String, Double> trueMeanTp, Set<String> truthWins) {}

    /** One legal card's rollouts over the worlds. */
    static final class CardWorlds {
        final Card card;
        int rollouts, won;
        long tp;
        CardWorlds(Card card) { this.card = card; }
        double mean() { return rollouts == 0 ? Double.NaN : tp / (double) rollouts; }
    }

    record WorldProbe(AuditRow row, List<CardWorlds> cards, int worlds, int failed) {
        CardWorlds pick() {
            CardWorlds best = null;
            for (CardWorlds c : cards) {
                if (best == null || c.mean() > best.mean() + 1e-9) best = c;
                else if (Math.abs(c.mean() - best.mean()) <= 1e-9 && prefer(c, best)) best = c;
            }
            return best;
        }
        /** Among ties: the vote's own card, then the higher vote. */
        private boolean prefer(CardWorlds c, CardWorlds over) {
            if (c.card.toString().equals(row.played())) return true;
            if (over.card.toString().equals(row.played())) return false;
            return row.votes().getOrDefault(c.card.toString(), 0) > row.votes().getOrDefault(over.card.toString(), 0);
        }
        CardWorlds played() {
            for (CardWorlds c : cards) if (c.card.toString().equals(row.played())) return c;
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String rules = Rules.apply(options);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve(options.getOrDefault("player", "belief-32-shipped"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        int perWorld = Integer.parseInt(options.getOrDefault("per-world", "1"));
        int maxWorlds = Integer.parseInt(options.getOrDefault("worlds", "0"));
        int perBand = Integer.parseInt(options.getOrDefault("per-band", "0"));
        Path from = Path.of(options.getOrDefault("from", "arena-logs/rollout")).toAbsolutePath();
        List<Long> seeds = new ArrayList<>();
        for (String part : options.getOrDefault("seeds", "14,15,16").split("[ ,]+")) {
            if (!part.isBlank()) seeds.add(Long.parseLong(part.trim()));
        }
        String out = options.get("out");
        System.out.printf(Locale.ROOT, "World rollouts: %s, seeds %s, decisions from %s, %s rollout(s) a card a world, "
                        + "worlds %s, %s%n", player.id(), seeds, from, perWorld,
                maxWorlds > 0 ? "the first " + maxWorlds + " tallied" : "all tallied", rules);

        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<WorldProbe> all = new ArrayList<>();
        int recordMismatches = 0, rows = 0;
        try {
            for (long seed : seeds) {
                List<AuditRow> audit = readAudit(from.resolve("rollout-audit-s" + seed + ".csv"), seed);
                if (perBand > 0) {
                    Map<RolloutAuditMain.Band, Integer> taken = new HashMap<>();
                    List<AuditRow> kept = new ArrayList<>();
                    for (AuditRow row : audit) if (taken.merge(row.band(), 1, Integer::sum) <= perBand) kept.add(row);
                    audit = kept;
                }
                rows += audit.size();
                ContractSource contracts = new AuctionContractSource(
                        registry.resolve(options.getOrDefault("bidder", "greedy")), seed);
                Map<Integer, Future<RolloutAuditMain.Replay>> games = new TreeMap<>();
                for (AuditRow row : audit) {
                    games.computeIfAbsent(row.board(), b -> pool.submit(() -> {
                        Board board = Board.of(seed, b);
                        ContractSource.FixedContract fixed = contracts.contractFor(board);
                        return fixed == null ? null : RolloutAuditMain.record(board, seed, fixed, player);
                    }));
                }
                List<Callable<WorldProbe>> tasks = new ArrayList<>();
                for (AuditRow row : audit) {
                    RolloutAuditMain.Replay game = games.get(row.board()).get();
                    RolloutAuditMain.Recorded d = null;
                    if (game != null) for (RolloutAuditMain.Recorded r : game.decisions()) if (r.index() == row.index()) d = r;
                    String why = d == null ? "decision not recorded again" : sameDecision(row, d);
                    if (why != null) {
                        recordMismatches++;
                        System.out.printf(Locale.ROOT, "  record check failed: seed %d board %d decision at card %d: %s%n",
                                seed, row.board(), row.index(), why);
                        continue;
                    }
                    final RolloutAuditMain.Recorded decision = d;
                    tasks.add(() -> probe(seed, game, decision, row, player, perWorld, maxWorlds));
                }
                int before = all.size();
                for (Future<WorldProbe> f : pool.invokeAll(tasks)) if (f.get() != null) all.add(f.get());
                System.out.printf(Locale.ROOT, "seed %d: %d decisions from the audit, %d rolled out over their worlds%n",
                        seed, audit.size(), all.size() - before);
                if (out != null) {
                    Path dir = Path.of(out).toAbsolutePath();
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("world-rollout-s" + seed + ".csv"), csv(all.subList(before, all.size())));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.print(report(all, rows, recordMismatches));
        System.out.printf(Locale.ROOT, "%.0f s%n", (System.nanoTime() - started) / 1e9);
        if (out != null) System.out.println("Per-card rows written to " + Path.of(out).toAbsolutePath());
    }

    /** Null when the decision recorded again is the audit's: same card played, same vote. */
    static String sameDecision(AuditRow row, RolloutAuditMain.Recorded d) {
        if (!d.played().toString().equals(row.played())) return "played " + d.played() + ", the audit has " + row.played();
        Map<String, Integer> votes = new HashMap<>();
        for (Map.Entry<Card, Integer> e : d.votes().entrySet()) votes.put(e.getKey().toString(), e.getValue());
        if (!votes.equals(row.votes())) return "votes " + votes + ", the audit has " + row.votes();
        if (d.sampled() == null || d.sampled().size() < d.worlds()) return "the sampled worlds were not seen";
        return null;
    }

    // ------------------------------------------------------------------ rolling out in the worlds

    static WorldProbe probe(long seed, RolloutAuditMain.Replay game, RolloutAuditMain.Recorded d, AuditRow row,
                            Contestant player, int perWorld, int maxWorlds) {
        int use = d.worlds();
        if (maxWorlds > 0) use = Math.min(use, maxWorlds);
        List<CardWorlds> cards = new ArrayList<>();
        for (Card card : d.votes().keySet()) cards.add(new CardWorlds(card));
        int failed = 0;
        for (int w = 0; w < use; w++) {
            WorldSampler.World world = d.sampled().get(w);
            Board board = worldBoard(game, d.index(), world);
            if (board == null) { failed += cards.size() * perWorld; continue; }
            for (CardWorlds c : cards) {
                for (int k = 0; k < perWorld; k++) {
                    RolloutAuditMain.Outcome o = RolloutAuditMain.rollout(game, board, world.skat(), d.index(), c.card,
                            player, seed, w * perWorld + k, 0x3017L, false);
                    if (o == null) { failed++; continue; }
                    c.rollouts++;
                    c.won += o.won() ? 1 : 0;
                    c.tp += o.tournamentPoints();
                }
            }
        }
        for (CardWorlds c : cards) if (c.rollouts == 0) return null;
        return new WorldProbe(row, cards, use, failed);
    }

    /**
     * The recorded board with its hidden cards dealt as {@code world} has them:
     * the declarer keeps the ten it was dealt, each defender holds the cards it
     * has played before {@code index} and the world's hand, and the skat is the
     * two left over -- the dealt skat when the declarer took it up (the world
     * then gives the defenders exactly the other twenty), the world's skat in a
     * hand game. Null if the world does not add up to a deal.
     */
    static Board worldBoard(RolloutAuditMain.Replay game, int index, WorldSampler.World world) {
        Board truth = game.board();
        SkatDeck.Deal deal = truth.deal();
        SkatAi.Seat declarer = game.fixed().declarer();
        List<List<Card>> dealt = List.of(deal.human, deal.opponentOne, deal.opponentTwo);
        Map<Card, Integer> owner = new HashMap<>();
        for (int seat = 0; seat < 3; seat++) for (Card c : dealt.get(seat)) owner.put(c, seat);
        for (Card c : deal.skat) owner.put(c, declarer.ordinal());
        List<List<Card>> hands = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) hands.add(new ArrayList<>());
        for (Card played : game.sequence().subList(0, index)) hands.get(owner.get(played)).add(played);
        for (int seat = 0; seat < 3; seat++) {
            if (seat == declarer.ordinal()) {
                hands.set(seat, new ArrayList<>(dealt.get(seat)));
            } else {
                hands.get(seat).addAll(world.hands().get(seat));
            }
        }
        Set<Card> used = new HashSet<>();
        List<Card> ordered = new ArrayList<>(32);
        for (List<Card> hand : hands) {
            if (hand.size() != 10) return null;
            ordered.addAll(hand);
            used.addAll(hand);
        }
        List<Card> skat = new ArrayList<>();
        for (Card c : SkatDeck.ordered()) if (!used.contains(c)) skat.add(c);
        if (skat.size() != 2) return null;
        ordered.addAll(skat);
        try {
            return new Board(truth.index(), SkatDeck.dealFrom(ordered), truth.round());
        } catch (IllegalArgumentException notADeal) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reading the audit

    static List<AuditRow> readAudit(Path csv, long seed) throws java.io.IOException {
        if (!Files.exists(csv)) throw new IllegalArgumentException("No rollout audit for seed " + seed + ": " + csv
                + " -- run tools/rollout-audit.sh first");
        Map<String, List<String[]>> byDecision = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv);
        String header = lines.get(0);
        if (!header.equals("seed,board,contract,trick,index,band,card,played,votes,worlds,truth_wins,rollouts,won,mean_tp")) {
            throw new IllegalArgumentException("Unexpected header in " + csv + ": " + header);
        }
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] f = line.split(",", -1);
            byDecision.computeIfAbsent(f[1] + "/" + f[4], k -> new ArrayList<>()).add(f);
        }
        List<AuditRow> rows = new ArrayList<>();
        for (List<String[]> cards : byDecision.values()) {
            String[] first = cards.get(0);
            String played = null;
            Map<String, Integer> votes = new HashMap<>();
            Map<String, Double> tp = new HashMap<>();
            Set<String> truth = new HashSet<>();
            for (String[] f : cards) {
                if (f[7].equals("1")) played = f[6];
                votes.put(f[6], Integer.parseInt(f[8]));
                tp.put(f[6], Double.parseDouble(f[13]));
                if (f[10].equals("1")) truth.add(f[6]);
            }
            rows.add(new AuditRow(seed, Integer.parseInt(first[1]), Integer.parseInt(first[4]),
                    RolloutAuditMain.Band.valueOf(first[5]), played, votes, tp, truth));
        }
        return rows;
    }

    // ------------------------------------------------------------------ reporting

    static String report(List<WorldProbe> probes, int rows, int recordMismatches) {
        StringBuilder out = new StringBuilder();
        int failed = 0, rollouts = 0;
        for (WorldProbe p : probes) {
            failed += p.failed();
            for (CardWorlds c : p.cards()) rollouts += c.rollouts;
        }
        out.append(String.format(Locale.ROOT, "%nRECORD CHECK: %d of %d audit decisions recorded again with a different "
                        + "card or vote%s%n", recordMismatches, rows,
                recordMismatches == 0 ? "" : "  <-- those are left out; many would mean the worlds are not the audit's"));
        out.append(String.format(Locale.ROOT, "ROLLOUTS: %d played, %d could not be (world not a deal, or the exchange "
                + "or the rules came out differently)%n", rollouts, failed));
        out.append(String.format(Locale.ROOT, "%nOn the audit's true-deal rollouts, game points a decision "
                + "(tournament points / 3), the rollout's card against the vote's%n"));
        out.append(String.format(Locale.ROOT, "  %-18s %4s %7s %9s %9s %9s %24s %11s %11s %9s%n", "band", "n", "agree",
                "vote", "rollout", "best", "rollout - vote, 95%", "better/wrs", "wins truth", "est.vote"));
        List<WorldProbe> pooled = new ArrayList<>();
        for (RolloutAuditMain.Band band : RolloutAuditMain.Band.values()) {
            List<WorldProbe> in = new ArrayList<>();
            for (WorldProbe p : probes) if (p.row().band() == band) in.add(p);
            pooled.addAll(in);
            out.append(line(band.label, in));
        }
        out.append(line("all", pooled));
        out.append("  agree: the rollout picks the vote's card; vote / rollout: true-deal game points of each card;\n"
                + "  best: of the card best on the true deal (knows the deal; the ceiling, not a target); better/wrs:\n"
                + "  decisions where the rollout's card scored higher / lower on the true deal; wins truth: share of\n"
                + "  decisions where each card (vote/rollout) wins against perfect defence in the true deal; est.vote:\n"
                + "  what the worlds' rollouts expected of the vote's card, to read against the vote column.\n");
        return out.toString();
    }

    private static String line(String label, List<WorldProbe> in) {
        int n = in.size();
        if (n == 0) return String.format(Locale.ROOT, "  %-18s %4d%n", label, 0);
        int agree = 0, better = 0, worse = 0, voteTruth = 0, pickTruth = 0;
        double vote = 0, pick = 0, best = 0, est = 0;
        double[] diffs = new double[n];
        for (int i = 0; i < n; i++) {
            WorldProbe p = in.get(i);
            AuditRow row = p.row();
            CardWorlds mine = p.pick();
            String pickName = mine.card.toString();
            if (pickName.equals(row.played())) agree++;
            double v = row.trueMeanTp().get(row.played()) / 3.0;
            double r = row.trueMeanTp().get(pickName) / 3.0;
            double b = -1e9;
            for (double t : row.trueMeanTp().values()) b = Math.max(b, t / 3.0);
            vote += v; pick += r; best += b;
            diffs[i] = r - v;
            if (r > v + 1e-9) better++; else if (r < v - 1e-9) worse++;
            if (row.truthWins().contains(row.played())) voteTruth++;
            if (row.truthWins().contains(pickName)) pickTruth++;
            CardWorlds played = p.played();
            if (played != null) est += played.mean() / 3.0;
        }
        double mean = 0;
        for (double x : diffs) mean += x;
        mean /= n;
        double ss = 0;
        for (double x : diffs) ss += (x - mean) * (x - mean);
        double half = n > 1 ? tQuantile(n - 1) * Math.sqrt(ss / (n - 1)) / Math.sqrt(n) : Double.NaN;
        return String.format(Locale.ROOT, "  %-18s %4d %6.0f%% %+9.2f %+9.2f %+9.2f %+7.2f [%+6.2f, %+6.2f] %5d/%-5d %4.0f/%-4.0f%% %+9.2f%n",
                label, n, 100.0 * agree / n, vote / n, pick / n, best / n, mean, mean - half, mean + half,
                better, worse, 100.0 * voteTruth / n, 100.0 * pickTruth / n, est / n);
    }

    /** Two-sided 95% quantile of Student's t, close enough for an interval's width. */
    static double tQuantile(int df) {
        double[] small = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228};
        return df <= small.length ? small[df - 1] : 1.96 + 2.4 / df;
    }

    static String csv(List<WorldProbe> probes) {
        StringBuilder out = new StringBuilder("seed,board,index,band,card,played,votes,truth_wins,true_mean_tp,"
                + "worlds,world_rollouts,world_won,world_mean_tp,rollout_pick\n");
        for (WorldProbe p : probes) {
            AuditRow row = p.row();
            String pick = p.pick().card.toString();
            for (CardWorlds c : p.cards()) {
                String name = c.card.toString();
                out.append(String.format(Locale.ROOT, "%d,%d,%d,%s,%s,%d,%d,%d,%.2f,%d,%d,%d,%.2f,%d%n",
                        row.seed(), row.board(), row.index(), row.band(), name, name.equals(row.played()) ? 1 : 0,
                        row.votes().getOrDefault(name, 0), row.truthWins().contains(name) ? 1 : 0,
                        row.trueMeanTp().getOrDefault(name, Double.NaN), p.worlds(), c.rollouts, c.won, c.mean(),
                        name.equals(pick) ? 1 : 0));
            }
        }
        return out.toString();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) options.put(body, "true"); else options.put(body.substring(0, eq), body.substring(eq + 1));
        }
        return options;
    }
}
