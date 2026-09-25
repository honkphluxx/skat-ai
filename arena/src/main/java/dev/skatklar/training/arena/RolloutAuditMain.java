package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.solve.DoubleDummySolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Does the vote rank the declarer's cards the way the table does?
 *
 * <p>The vote counts, for each legal card, the sampled worlds in which the
 * declarer still wins against a defence that sees every card. The declaring
 * audit found it calibrated against that defence and twenty points too
 * pessimistic against the one at the table: a card the worlds give one chance
 * in eight goes on to win the game nearly one time in three
 * (docs/training-plan.md, 2026-09-25). Too pessimistic everywhere changes
 * nothing if the order of the cards is right. This asks for the order.
 *
 * <p>It replays the arena's declarer rotation exactly as the declaring audit
 * does -- same boards, same contracts from greedy's auction, same seeds, the
 * player in all three seats -- and records every declarer decision: the vote,
 * and the card that wins against perfect defence in the true deal. It then
 * picks decisions from four bands of the vote share of the card played (every
 * card at zero; 1-25%; 26-75%; 76-99%) and, from each, plays every legal card
 * out {@code --rollouts} times to the end of the game against the real
 * defenders -- fresh players, the history before the decision replayed into
 * them card for card, the card forced, then left to play. Every card of one
 * decision is rolled out with the same seeds, so the comparison between cards
 * shares its luck.
 *
 * <p>For each decision: did the vote's card come out best at the table, and
 * by how much did it fall short of the best -- in game points, the gate's
 * unit, with the best card picked on one half of the rollouts and both
 * measured on the other, so the shortfall is not the maximum of noise.
 *
 * <p>The prefix replay is checked, not assumed: on the first decisions of each
 * band, one extra rollout is run with the original seeds, the players asked
 * at every scripted card as well, and the card they choose held against the
 * card recorded, and every card of the game and its outcome against the original's. A mismatch is
 * counted and printed; any at all means the rollouts are not continuations of
 * the recorded games and nothing below the line should be read.
 *
 * <pre>./gradlew :arena:rolloutAudit --args="--seeds=14,15,16 --boards=200 --rollouts=8 --per-band=12 --threads=16 --out=arena-logs/rollout"</pre>
 */
public final class RolloutAuditMain {

    private RolloutAuditMain() {}

    /** The four places a declarer decision can sit, by the vote share of the card it played. */
    enum Band {
        FLAT_ZERO("every card at zero"), LOW("played 1-25%"), MIDDLE("played 26-75%"),
        HIGH("played 76-99%");
        final String label;
        Band(String label) { this.label = label; }
    }

    /** One declarer decision of the recorded game. */
    record Recorded(int index, int trick, Map<Card, Integer> votes, int worlds, Card played,
                    Map<Card, Boolean> truth) {}

    /** One board's recorded game: the cards in order, the skat, the decisions, the result. */
    record Replay(Board board, ContractSource.FixedContract fixed, List<Card> sequence,
                  List<Card> skat, List<Recorded> decisions, boolean won, int tournamentPoints) {}

    /** One legal card's rollouts at one decision. */
    record CardResult(Card card, int votes, boolean truthWins, int[] won, int[] tp) {
        double mean(int from, int to) {
            double sum = 0;
            for (int i = from; i < to; i++) sum += tp[i];
            return to > from ? sum / (to - from) : 0;
        }
        double winRate() {
            int sum = 0;
            for (int w : won) sum += w;
            return won.length == 0 ? 0 : sum / (double) won.length;
        }
    }

    record Probe(long seed, Replay replay, Recorded decision, Band band, List<CardResult> cards,
                 int checkMismatches, boolean checked) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String rules = Rules.apply(options);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve(options.getOrDefault("player", "belief-32-shipped"));
        int boards = Integer.parseInt(options.getOrDefault("boards", "200"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        int rollouts = Integer.parseInt(options.getOrDefault("rollouts", "8"));
        int perBand = Integer.parseInt(options.getOrDefault("per-band", "12"));
        int checks = Integer.parseInt(options.getOrDefault("checks", "2"));
        List<Long> seeds = new ArrayList<>();
        for (String part : options.getOrDefault("seeds", options.getOrDefault("seed", "14")).split("[ ,]+")) {
            if (!part.isBlank()) seeds.add(Long.parseLong(part.trim()));
        }
        String out = options.get("out");
        System.out.printf(Locale.ROOT, "Rollout audit: %s, %d boards a seed, seeds %s, %d rollouts a card, "
                + "up to %d decisions a band a seed, %s%n", player.id(), boards, seeds, rollouts, perBand, rules);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<Probe> all = new ArrayList<>();
        try {
            for (long seed : seeds) {
                ContractSource contracts = new AuctionContractSource(
                        registry.resolve(options.getOrDefault("bidder", "greedy")), seed);
                List<Callable<Replay>> replays = new ArrayList<>();
                for (int i = 0; i < boards; i++) {
                    final Board board = Board.of(seed, i);
                    replays.add(() -> {
                        ContractSource.FixedContract fixed = contracts.contractFor(board);
                        return fixed == null ? null : record(board, seed, fixed, player);
                    });
                }
                List<Replay> games = new ArrayList<>();
                for (Future<Replay> f : pool.invokeAll(replays)) if (f.get() != null) games.add(f.get());

                // The decisions to probe: by band, in a seeded shuffle, the first perBand.
                Map<Band, List<Object[]>> candidates = new EnumMap<>(Band.class);
                for (Band band : Band.values()) candidates.put(band, new ArrayList<>());
                for (Replay game : games) {
                    for (Recorded d : game.decisions()) {
                        Band band = bandOf(d);
                        if (band != null) candidates.get(band).add(new Object[] {game, d});
                    }
                }
                List<Callable<Probe>> probes = new ArrayList<>();
                for (Band band : Band.values()) {
                    List<Object[]> list = candidates.get(band);
                    Collections.shuffle(list, new Random(Seeds.mix(seed, band.ordinal(), 0x7011L)));
                    for (int k = 0; k < Math.min(perBand, list.size()); k++) {
                        Replay game = (Replay) list.get(k)[0];
                        Recorded d = (Recorded) list.get(k)[1];
                        boolean check = k < checks;
                        probes.add(() -> probe(seed, game, d, band, player, rollouts, check));
                    }
                }
                List<Probe> done = new ArrayList<>();
                for (Future<Probe> f : pool.invokeAll(probes)) if (f.get() != null) done.add(f.get());
                System.out.printf(Locale.ROOT, "seed %d: %d games replayed, %d decisions probed%n",
                        seed, games.size(), done.size());
                if (out != null) {
                    Path dir = Path.of(out).toAbsolutePath();
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("rollout-audit-s" + seed + ".csv"), csv(done));
                }
                all.addAll(done);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.print(report(all, rollouts));
        if (out != null) System.out.println("Per-card rows written to " + Path.of(out).toAbsolutePath());
    }

    static Band bandOf(Recorded d) {
        if (d.votes() == null || d.worlds() == 0 || d.votes().size() < 2) return null;
        int max = 0, min = Integer.MAX_VALUE;
        for (int v : d.votes().values()) { max = Math.max(max, v); min = Math.min(min, v); }
        if (max == 0) return Band.FLAT_ZERO;
        int share = (int) Math.round(100.0 * d.votes().getOrDefault(d.played(), 0) / d.worlds());
        if (share >= 1 && share <= 25) return Band.LOW;
        if (share >= 26 && share <= 75) return Band.MIDDLE;
        if (share >= 76 && share <= 99) return Band.HIGH;
        return null;
    }

    // ------------------------------------------------------------------ recording

    /** The arena's game for this board, played as the declaring audit plays it, and written down. */
    static Replay record(Board board, long seed, ContractSource.FixedContract fixed, Contestant player) {
        SkatAi.Seat declarer = fixed.declarer();
        Map<Card, Integer>[] lastVotes = new Map[1];
        int[] lastWorlds = new int[1];
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            SkatAiProvider provider = player.newProvider(Seeds.mix(seed, board.index(), declarer.ordinal(), seat.ordinal()));
            if (seat == declarer && provider instanceof SearchAiProvider search) {
                provider = search.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                    @Override public void decided(SearchAiProvider.CardPlayReport report) {}
                    @Override public void voted(SkatAi.DecisionContext context, Map<Card, Integer> votes,
                                                Map<Card, Integer> cushion, int worlds, boolean asked, Card chosen) {
                        lastVotes[0] = votes;
                        lastWorlds[0] = worlds;
                    }
                });
            }
            seating.put(seat, provider);
        }
        GameEngine engine = GameEngine.headless(new Random(engineSeed(seed, board, declarer)),
                SeatedAiProviders.of(seating));
        observe(engine, seating, board, fixed);
        try {
            engine.restartWithContract(board.deal(), board.round(), declarer, fixed.contract(),
                    fixed.bidValue(), Collections.emptySet(), fixed.auction());
            List<Card> skat = new ArrayList<>(engine.snapshot().skat);
            List<Card> sequence = new ArrayList<>(30);
            List<Recorded> decisions = new ArrayList<>();
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot before = engine.snapshot();
                if (before.gameComplete()) {
                    GameOutcome outcome = GameOutcome.of(before.result, engine.ruleViolationsBySeat(),
                            engine.ruleViolations());
                    return new Replay(board, fixed, sequence, skat, decisions, outcome.declarerWon(),
                            Scoring.tournamentPoints(outcome, outcome.declarer()));
                }
                if (before.trickComplete()) { engine.finishCompletedTrick(); continue; }
                SkatAi.Seat mover = SkatAi.Seat.values()[before.currentPlayer];
                Map<Card, Boolean> truth = mover == declarer && !before.contract.isNull()
                        ? truth(before, declarer, mover) : null;
                lastVotes[0] = null;
                Card played = engine.playAiCard();
                if (mover == declarer && lastVotes[0] != null && truth != null) {
                    decisions.add(new Recorded(sequence.size(), before.completedTricks + 1,
                            lastVotes[0], lastWorlds[0], played, truth));
                }
                sequence.add(played);
            }
            throw new IllegalStateException("board " + board.index() + " did not finish");
        } finally {
            engine.close();
        }
    }

    static long engineSeed(long seed, Board board, SkatAi.Seat declarer) {
        return Seeds.mix(seed, board.index(), declarer.ordinal(), 0xE1E1E1L);
    }

    private static void observe(GameEngine engine, Map<SkatAi.Seat, SkatAiProvider> seating,
                                Board board, ContractSource.FixedContract fixed) {
        for (SkatAiProvider provider : seating.values()) {
            if (provider instanceof TableObserver observer) {
                observer.observe(engine);
                observer.observeFixedContract(board, fixed);
            }
        }
    }

    /** For every legal card of the mover: does the declarer still reach 61 with every hand face up? */
    static Map<Card, Boolean> truth(GameEngine.Snapshot snapshot, SkatAi.Seat declarer, SkatAi.Seat mover) {
        List<Card> trickSoFar = new ArrayList<>(3);
        for (SkatAi.PlayedCard play : snapshot.trick) trickSoFar.add(play.card);
        SkatAi.Seat leader = SkatAi.Seat.values()[snapshot.leader];
        int banked = snapshot.capturedPoints.getOrDefault(declarer, 0) + SkatRules.cardPoints(snapshot.skat);
        Map<Card, Boolean> truth = new LinkedHashMap<>();
        for (DoubleDummySolver.Verdict verdict : DoubleDummySolver.movesReaching(snapshot.contract,
                declarer, mover, snapshot.hands, leader, trickSoFar, 61 - banked)) {
            if (snapshot.legalCards.contains(verdict.card())) truth.put(verdict.card(), verdict.reachesTarget());
        }
        return truth;
    }

    // ------------------------------------------------------------------ rolling out

    static Probe probe(long seed, Replay game, Recorded d, Band band, Contestant player,
                       int rollouts, boolean check) {
        int mismatches = 0;
        if (check) {
            Outcome replayed = rollout(game, d.index(), d.played(), player, seed, -1, true);
            if (replayed == null || replayed.scriptMismatches() > 0 || replayed.won() != game.won()
                    || replayed.tournamentPoints() != game.tournamentPoints()
                    || !replayed.sequence().equals(game.sequence())) {
                mismatches = 1 + (replayed == null ? 0 : replayed.scriptMismatches());
                System.out.printf(Locale.ROOT, "  replay check failed: seed %d board %d decision at card %d: %s%n",
                        seed, game.board().index(), d.index(), replayed == null ? "no outcome (skat or rules)"
                                : "script cards the players would not have chosen " + replayed.scriptMismatches()
                                + ", same cards " + replayed.sequence().equals(game.sequence())
                                + ", won " + replayed.won() + " vs " + game.won()
                                + ", tp " + replayed.tournamentPoints() + " vs " + game.tournamentPoints());
            }
        }
        List<CardResult> cards = new ArrayList<>();
        for (Card card : d.votes().keySet()) {
            int[] won = new int[rollouts];
            int[] tp = new int[rollouts];
            for (int r = 0; r < rollouts; r++) {
                Outcome o = rollout(game, d.index(), card, player, seed, r, false);
                if (o == null) return null;
                won[r] = o.won() ? 1 : 0;
                tp[r] = o.tournamentPoints();
            }
            cards.add(new CardResult(card, d.votes().getOrDefault(card, 0),
                    Boolean.TRUE.equals(d.truth().get(card)), won, tp));
        }
        return new Probe(seed, game, d, band, cards, mismatches, check);
    }

    record Outcome(boolean won, int tournamentPoints, int scriptMismatches, List<Card> sequence) {}

    /**
     * The recorded game up to {@code index}, then {@code forced}, then the players.
     *
     * @param r the rollout number, which seeds the players; -1 for the original
     *          seeds, which with {@code askDuringScript} must reproduce the
     *          recorded game exactly when {@code forced} is the card played
     */
    static Outcome rollout(Replay game, int index, Card forced, Contestant player, long seed, int r,
                           boolean askDuringScript) {
        Board board = game.board();
        ContractSource.FixedContract fixed = game.fixed();
        SkatAi.Seat declarer = fixed.declarer();
        List<Card> script = new ArrayList<>(game.sequence().subList(0, index));
        script.add(forced);
        int[] mismatches = new int[1];
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            long providerSeed = r < 0
                    ? Seeds.mix(seed, board.index(), declarer.ordinal(), seat.ordinal())
                    : Seeds.mix(seed, board.index(), index, r, seat.ordinal(), 0x7011L);
            seating.put(seat, new Scripted(player.newProvider(providerSeed), script, askDuringScript, mismatches));
        }
        GameEngine engine = GameEngine.headless(new Random(engineSeed(seed, board, declarer)),
                SeatedAiProviders.of(seating));
        observe(engine, seating, board, fixed);
        try {
            engine.restartWithContract(board.deal(), board.round(), declarer, fixed.contract(),
                    fixed.bidValue(), Collections.emptySet(), fixed.auction());
            if (!new ArrayList<>(engine.snapshot().skat).equals(game.skat())) return null;
            List<Card> sequence = new ArrayList<>(30);
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot now = engine.snapshot();
                if (now.gameComplete()) {
                    if (!engine.ruleViolations().isEmpty()) return null;
                    GameOutcome outcome = GameOutcome.of(now.result, engine.ruleViolationsBySeat(),
                            engine.ruleViolations());
                    return new Outcome(outcome.declarerWon(),
                            Scoring.tournamentPoints(outcome, outcome.declarer()), mismatches[0], sequence);
                }
                if (now.trickComplete()) engine.finishCompletedTrick(); else sequence.add(engine.playAiCard());
            }
            return null;
        } finally {
            engine.close();
        }
    }

    /** A player that plays the script's cards while the script lasts, and hears everything. */
    static final class Scripted implements SkatAiProvider, TableObserver {
        private final SkatAiProvider inner;
        private final List<Card> script;
        private final boolean ask;
        private final int[] mismatches;

        Scripted(SkatAiProvider inner, List<Card> script, boolean ask, int[] mismatches) {
            this.inner = inner;
            this.script = script;
            this.ask = ask;
            this.mismatches = mismatches;
        }

        @Override public void observe(GameEngine engine) {
            if (inner instanceof TableObserver o) o.observe(engine);
        }

        @Override public void observeFixedContract(Board board, ContractSource.FixedContract fixed) {
            if (inner instanceof TableObserver o) o.observeFixedContract(board, fixed);
        }

        @Override public SkatAi.AiDescriptor descriptor() { return inner.descriptor(); }

        @Override public SkatAiSession createSession() {
            SkatAiSession s = inner.createSession();
            return new SkatAiSession() {
                @Override public void prepareDeal(SkatAi.DealContext c) { s.prepareDeal(c); }
                @Override public int bid(SkatAi.BidRequest c) { return s.bid(c); }
                @Override public void bidObserved(SkatAi.BidEvent e) { s.bidObserved(e); }
                @Override public boolean pickUpSkat(SkatAi.SkatChoiceContext c) { return s.pickUpSkat(c); }
                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext c) { return s.discardSkat(c); }
                @Override public SkatAi.ContractAnnouncement announceContract(SkatAi.ContractContext c) { return s.announceContract(c); }
                @Override public boolean takeUpPush(SkatAi.RamschTakeUpContext c) { return s.takeUpPush(c); }
                @Override public Set<Card> pushCards(SkatAi.RamschPushContext c) { return s.pushCards(c); }
                @Override public void ramschPushObserved(SkatAi.RamschPushEvent e) { s.ramschPushObserved(e); }
                @Override public boolean announceContra(SkatAi.ContraContext c) { return s.announceContra(c); }
                @Override public void contraObserved(SkatAi.ContraEvent e) { s.contraObserved(e); }
                @Override public void startGame(SkatAi.GameStartContext c) { s.startGame(c); }
                @Override public Card chooseCard(SkatAi.DecisionContext c) {
                    int at = 0;
                    for (SkatAi.CompletedTrick t : c.history.completedTricks) at += t.plays.size();
                    at += c.currentTrick.plays.size();
                    if (at >= script.size()) return s.chooseCard(c);
                    Card scripted = script.get(at);
                    // Asked at the forced card too: the check forces the card that
                    // was played, and a player not asked there would skip a draw
                    // from its random stream and play the rest of the game on
                    // different worlds from the recorded one.
                    if (ask) {
                        Card own = s.chooseCard(c);
                        if (!own.equals(scripted)) synchronized (mismatches) { mismatches[0]++; }
                    }
                    return scripted;
                }
                @Override public void cardPlayed(SkatAi.CardPlayedEvent e) { s.cardPlayed(e); }
                @Override public void trickCompleted(SkatAi.TrickCompletedEvent e) { s.trickCompleted(e); }
                @Override public void endGame(SkatAi.GameResult r) { s.endGame(r); }
                @Override public void close() { s.close(); }
            };
        }
    }

    // ------------------------------------------------------------------ reporting

    static String report(List<Probe> probes, int rollouts) {
        StringBuilder out = new StringBuilder();
        int checked = 0, failed = 0;
        for (Probe p : probes) if (p.checked()) { checked++; if (p.checkMismatches() > 0) failed++; }
        out.append(String.format(Locale.ROOT, "%nREPLAY CHECK: %d probes replayed with the original seeds, %d did not "
                + "reproduce the recorded game%s%n", checked, failed,
                failed == 0 ? "" : "  <-- the rollouts are not continuations of the recorded games; read nothing below"));
        int half = rollouts / 2;
        out.append(String.format(Locale.ROOT, "%nBy band of the vote share of the card played, %d rollouts a card "
                + "(game points are tournament points / 3, the gate's unit)%n", rollouts));
        out.append(String.format(Locale.ROOT, "  %-26s %5s %6s %12s %12s %12s %14s %14s%n", "band", "n", "cards",
                "vote=best", "truth=best", "vote wins", "best wins", "short, g.p."));
        for (Band band : Band.values()) {
            int n = 0, cards = 0, voteBest = 0, truthBest = 0;
            double voteWin = 0, bestWin = 0, shortfall = 0;
            for (Probe p : probes) {
                if (p.band() != band) continue;
                n++;
                cards += p.cards().size();
                double best = -1e9;
                for (CardResult c : p.cards()) best = Math.max(best, c.mean(0, rollouts));
                CardResult chosen = null;
                for (CardResult c : p.cards()) if (c.card().equals(p.decision().played())) chosen = c;
                if (chosen == null) continue;
                if (chosen.mean(0, rollouts) >= best - 1e-9) voteBest++;
                // The truth's pick: among the cards that win against perfect defence
                // in the true deal (or all, if none does), the one with the best rollout.
                double truthBestMean = -1e9;
                boolean anyTruth = false;
                for (CardResult c : p.cards()) if (c.truthWins()) anyTruth = true;
                for (CardResult c : p.cards()) if (!anyTruth || c.truthWins()) truthBestMean = Math.max(truthBestMean, c.mean(0, rollouts));
                if (truthBestMean >= best - 1e-9) truthBest++;
                voteWin += chosen.winRate();
                double bestRate = 0;
                for (CardResult c : p.cards()) bestRate = Math.max(bestRate, c.winRate());
                bestWin += bestRate;
                // Split halves: the best card picked on the first half, both measured on the second.
                CardResult pick = null;
                for (CardResult c : p.cards()) if (pick == null || c.mean(0, half) > pick.mean(0, half)) pick = c;
                shortfall += (pick.mean(half, rollouts) - chosen.mean(half, rollouts)) / 3.0;
            }
            if (n == 0) { out.append(String.format(Locale.ROOT, "  %-26s %5d%n", band.label, 0)); continue; }
            out.append(String.format(Locale.ROOT, "  %-26s %5d %6.1f %11.1f%% %11.1f%% %11.1f%% %13.1f%% %+14.2f%n",
                    band.label, n, cards / (double) n, 100.0 * voteBest / n, 100.0 * truthBest / n,
                    100.0 * voteWin / n, 100.0 * bestWin / n, shortfall / n));
        }
        out.append("  vote=best: the vote's card had the best mean at the table (ties count); truth=best: the same for\n"
                + "  the best of the cards that win against perfect defence in the true deal; vote wins / best wins: game\n"
                + "  win rate of the vote's card and of the best card; short: what the vote's card gives up per decision,\n"
                + "  the best picked on half the rollouts and both measured on the other half (can be negative).\n");
        return out.toString();
    }

    static String csv(List<Probe> probes) {
        StringBuilder out = new StringBuilder("seed,board,contract,trick,index,band,card,played,votes,worlds,truth_wins,rollouts,won,mean_tp\n");
        for (Probe p : probes) {
            for (CardResult c : p.cards()) {
                int won = 0;
                for (int w : c.won()) won += w;
                out.append(String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%.2f%n",
                        p.seed(), p.replay().board().index(), p.replay().fixed().contract(),
                        p.decision().trick(), p.decision().index(), p.band(), c.card(),
                        c.card().equals(p.decision().played()) ? 1 : 0, c.votes(), p.decision().worlds(),
                        c.truthWins() ? 1 : 0, c.won().length, won, c.mean(0, c.tp().length)));
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
