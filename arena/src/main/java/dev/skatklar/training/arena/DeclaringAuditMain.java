package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.solve.NullSolver;
import java.io.IOException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Where, card by card, an honest declarer loses the games par wins with the
 * same ten cards.
 *
 * <p>{@code declaring-par.sh --split} says card play is 4.06 of the gap to par
 * and cannot say more: the arena's per-board file holds one number a board, the
 * difference of the two sides' totals, and for the self-match that carries our
 * declarer's column it is zero on every row by construction. Nothing on disk
 * pairs our declarer with par's on a board. This does.
 *
 * <p>Each board is played twice at the contract the arena would fix for it:
 * once with {@code --player} in all three seats, once with {@code --par}
 * declaring against the same two defenders. The providers are seeded exactly
 * as {@link DuplicateMatch} seeds the rotation in which the declarer's seat is
 * the singleton, so these are <b>the same two games</b> the self-match and the
 * match against par already played -- and the first thing the report prints is
 * both declarer win counts, to be held against "wins as declarer" in those
 * logs. If they differ, nothing below them means anything.
 *
 * <p>Before every card, the position is solved with every hand face up. That
 * gives each game a history of <b>flips</b>: a declarer's card after which a
 * won position is lost (a throw), a defender's after which a lost one is won
 * (a gift). Par never throws, by construction. So the gap has exactly three
 * places to be, and the report prices each in the gate's own unit:
 *
 * <ul>
 *   <li>games cold after the discard that our declarer threw away;</li>
 *   <li>games not cold, where the defence gave par a gift it did not give us
 *       -- different lines draw different mistakes;</li>
 *   <li>gifts we received and handed back.</li>
 * </ul>
 *
 * <p>And at each throw it reads the player's own tally, through the player's
 * own session ({@link SearchAiProvider.CardPlayObserver#voted}): was the
 * winning card tied with the one played, narrowly outvoted, widely outvoted, or
 * was every card at zero, which is a declarer that believes the game is lost
 * and is choosing by tiebreak. The last table is the vote's calibration: of the
 * cards that won in a share p of the sampled worlds, how many win in the world
 * that is actually on the table. A vote is a double-dummy statement about a
 * sampled world, and the truth is one more world, so an honest belief makes
 * those two numbers equal and strategy fusion cannot touch them -- which is
 * what separates "the belief is wrong" from "the vote is the wrong question".
 *
 * <p>It is written to print any of those diagnoses as readily as another.
 *
 * <pre>./gradlew :arena:declaringAudit --args="--seeds=11,12,13 --boards=200 --threads=16 --out=arena-logs/par-audit"</pre>
 */
public final class DeclaringAuditMain {

    private DeclaringAuditMain() {}

    /** One card decision, solved with every hand face up. */
    record Decision(int trick, int positionInTrick, boolean byDeclarer, int cardsInHand,
                    int legal, int legalWinning, boolean wonBefore, boolean wonAfter,
                    Card played, List<Card> winningCards,
                    Map<Card, Integer> votes, int worldsSearched, boolean cushionAsked) {
        boolean thrown() { return byDeclarer && wonBefore && !wonAfter; }
        boolean gift() { return !byDeclarer && !wonBefore && wonAfter; }
        boolean flip() { return wonBefore != wonAfter; }
    }

    record Game(boolean coldAtStart, GameOutcome outcome, List<Decision> decisions) {
        boolean won() { return outcome.declarerWon(); }
        int declarerTournamentPoints() {
            return Scoring.tournamentPoints(outcome, outcome.declarer());
        }
        int count(boolean throwsNotGifts) {
            int n = 0;
            for (Decision d : decisions) if (throwsNotGifts ? d.thrown() : d.gift()) n++;
            return n;
        }
        /** The last flip: the card that settled the game, or null if nothing ever flipped. */
        Decision decisive() {
            Decision last = null;
            for (Decision d : decisions) if (d.flip()) last = d;
            return last;
        }
    }

    record BoardAudit(long board, ContractSource.FixedContract fixed, Game ours, Game par) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String rules = Rules.apply(options);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve(options.getOrDefault("player", "belief-32-shipped"));
        Contestant par = registry.resolve(options.getOrDefault("par", "solver-heuristic-discard"));
        int boards = Integer.parseInt(options.getOrDefault("boards", "200"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        // --seeds="11 12 13" or --seeds=11,12,13; --seed=11 for one.
        List<Long> seeds = new ArrayList<>();
        for (String part : options.getOrDefault("seeds", options.getOrDefault("seed", "11"))
                .split("[ ,]+")) {
            if (!part.isBlank()) seeds.add(Long.parseLong(part.trim()));
        }
        String out = options.get("out");

        System.out.printf(Locale.ROOT, "Declaring audit: %s against par %s, %d boards a seed, seeds %s, %s%n",
                player.id(), par.id(), boards, seeds, rules);

        List<BoardAudit> pooled = new ArrayList<>();
        int asked = 0;
        for (long seed : seeds) {
            ContractSource contracts = new AuctionContractSource(
                    registry.resolve(options.getOrDefault("bidder", "greedy")), seed);
            System.out.println("seed " + seed + ", contracts from: " + contracts.describe());
            List<Callable<BoardAudit>> work = new ArrayList<>();
            for (int i = 0; i < boards; i++) {
                final int index = i;
                work.add(() -> audit(Board.of(seed, index), seed, player, par, contracts));
            }
            List<BoardAudit> audits = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
            try {
                int done = 0;
                for (Future<BoardAudit> future : pool.invokeAll(work)) {
                    BoardAudit audit = future.get();
                    if (audit != null) audits.add(audit);
                    if (++done % Math.max(1, boards / 10) == 0 && !options.containsKey("quiet")) {
                        System.out.printf(Locale.ROOT, "  %d/%d boards%n", done, boards);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            System.out.println(zeroCheck(audits, seed, player.id(), par.id()));
            // Written a seed at a time, so a stopped run keeps what it finished.
            if (out != null) {
                Path dir = Path.of(out).toAbsolutePath();
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("declaring-audit-games-s" + seed + ".csv"),
                        gamesCsv(audits, seed));
                Files.writeString(dir.resolve("declaring-audit-decisions-s" + seed + ".csv"),
                        decisionsCsv(audits, seed));
            }
            pooled.addAll(audits);
            asked += boards;
        }
        System.out.print(report(pooled, asked, player.id(), par.id()));
        if (out != null) System.out.println("Per-game and per-decision files written to " + Path.of(out).toAbsolutePath());
    }

    /**
     * One line a seed, in a fixed shape a script can grep: the two declarers'
     * wins and "from declaring", to be held against the arena's own logs of the
     * same seed. These are the same games or the audit is about something else.
     */
    static String zeroCheck(List<BoardAudit> audits, long seed, String us, String parId) {
        int n = audits.size(), oursWon = 0, parWon = 0;
        long oursTp = 0, parTp = 0;
        for (BoardAudit a : audits) {
            if (a.ours().won()) oursWon++;
            if (a.par().won()) parWon++;
            oursTp += a.ours().declarerTournamentPoints();
            parTp += a.par().declarerTournamentPoints();
        }
        return String.format(Locale.ROOT,
                "ZERO-CHECK seed %d boards %d us-wins %d us-declaring %.2f par-wins %d par-declaring %.2f",
                seed, n, oursWon, n == 0 ? 0 : oursTp / (3.0 * n), parWon, n == 0 ? 0 : parTp / (3.0 * n));
    }

    // ------------------------------------------------------------------ playing

    private static BoardAudit audit(Board board, long seed, Contestant player, Contestant par,
                                    ContractSource contracts) {
        ContractSource.FixedContract fixed = contracts.contractFor(board);
        if (fixed == null) return null;
        return new BoardAudit(board.index(), fixed,
                play(board, seed, fixed, player, player, true),
                play(board, seed, fixed, par, player, false));
    }

    /**
     * The rotation of {@link DuplicateMatch} in which the declarer's seat is the
     * singleton, seeded as it seeds it, and stopped before every card to ask
     * the solver what the position is worth.
     */
    static Game play(Board board, long seed, ContractSource.FixedContract fixed,
                             Contestant declaring, Contestant defending, boolean watchTheVote) {
        SkatAi.Seat declarer = fixed.declarer();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        Vote[] lastVote = new Vote[1];
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            Contestant contestant = seat == declarer ? declaring : defending;
            SkatAiProvider provider = contestant.newProvider(Seeds.mix(
                    seed, board.index(), declarer.ordinal(), seat.ordinal()));
            if (watchTheVote && seat == declarer && provider instanceof SearchAiProvider search) {
                provider = search.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                    @Override public void decided(SearchAiProvider.CardPlayReport report) {}
                    @Override public void voted(SkatAi.DecisionContext context,
                                                Map<Card, Integer> votes, Map<Card, Integer> cushion,
                                                int worldsSearched, boolean cushionAsked, Card chosen) {
                        lastVote[0] = new Vote(votes, worldsSearched, cushionAsked, chosen);
                    }
                });
            }
            seating.put(seat, provider);
        }
        long engineSeed = Seeds.mix(seed, board.index(), declarer.ordinal(), 0xE1E1E1L);
        GameEngine engine = GameEngine.headless(new Random(engineSeed), SeatedAiProviders.of(seating));
        for (SkatAiProvider provider : seating.values()) {
            if (provider instanceof TableObserver observer) {
                observer.observe(engine);
                observer.observeFixedContract(board, fixed);
            }
        }
        try {
            engine.restartWithContract(board.deal(), board.round(), fixed.declarer(),
                    fixed.contract(), fixed.bidValue(), Collections.emptySet(), fixed.auction());
            List<Decision> decisions = new ArrayList<>(30);
            Boolean cold = null;
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot before = engine.snapshot();
                if (before.gameComplete()) {
                    return new Game(cold != null && cold,
                            GameOutcome.of(before.result, engine.ruleViolationsBySeat(),
                                    engine.ruleViolations()), decisions);
                }
                if (before.trickComplete()) { engine.finishCompletedTrick(); continue; }

                SkatAi.Seat mover = SkatAi.Seat.values()[before.currentPlayer];
                boolean byDeclarer = mover == declarer;
                Map<Card, Boolean> truth = truth(before, declarer, mover);
                int winning = 0;
                List<Card> winningCards = new ArrayList<>();
                for (Map.Entry<Card, Boolean> entry : truth.entrySet()) {
                    if (entry.getValue()) { winning++; winningCards.add(entry.getKey()); }
                }
                // The declarer needs one card that wins; the defence needs only
                // one that does not.
                boolean wonBefore = byDeclarer ? winning > 0 : winning == truth.size();
                if (cold == null) cold = wonBefore;

                lastVote[0] = null;
                Card played = engine.playAiCard();
                Boolean wonAfter = truth.get(played);
                if (wonAfter == null) {
                    throw new IllegalStateException("board " + board.index() + ": " + played
                            + " was played and the solver did not consider it legal");
                }
                Vote vote = byDeclarer ? lastVote[0] : null;
                if (vote != null && !played.equals(vote.chosen())) vote = null;
                decisions.add(new Decision(before.completedTricks + 1, before.trick.size(),
                        byDeclarer, before.hands.get(mover.ordinal()).size(), truth.size(), winning,
                        wonBefore, wonAfter, played, winningCards,
                        vote == null ? null : vote.votes(),
                        vote == null ? 0 : vote.worldsSearched(),
                        vote != null && vote.cushionAsked()));
            }
            throw new IllegalStateException("board " + board.index() + " did not finish");
        } finally {
            engine.close();
        }
    }

    private record Vote(Map<Card, Integer> votes, int worldsSearched, boolean cushionAsked,
                        Card chosen) {}

    /** For every legal card: does the declarer still win, with every hand face up? */
    private static Map<Card, Boolean> truth(GameEngine.Snapshot snapshot, SkatAi.Seat declarer,
                                            SkatAi.Seat mover) {
        List<Card> trickSoFar = new ArrayList<>(3);
        for (SkatAi.PlayedCard play : snapshot.trick) trickSoFar.add(play.card);
        SkatAi.Seat leader = SkatAi.Seat.values()[snapshot.leader];
        Map<Card, Boolean> truth = new LinkedHashMap<>();
        if (snapshot.contract.isNull()) {
            for (NullSolver.Verdict verdict : NullSolver.movesSurviving(declarer, mover,
                    snapshot.hands, leader, trickSoFar)) {
                if (snapshot.legalCards.contains(verdict.card())) {
                    truth.put(verdict.card(), verdict.declarerSurvives());
                }
            }
        } else {
            // The same threshold SolverAiProvider defends and SearchAiProvider
            // votes on at the reference risk: 61, skat included.
            int banked = snapshot.capturedPoints.getOrDefault(declarer, 0)
                    + SkatRules.cardPoints(snapshot.skat);
            for (DoubleDummySolver.Verdict verdict : DoubleDummySolver.movesReaching(
                    snapshot.contract, declarer, mover, snapshot.hands, leader, trickSoFar,
                    61 - banked)) {
                if (snapshot.legalCards.contains(verdict.card())) {
                    truth.put(verdict.card(), verdict.reachesTarget());
                }
            }
        }
        return truth;
    }

    // ---------------------------------------------------------------- reporting

    /** How the player's own tally stood at a card that threw the game. */
    enum VoteClass {
        NO_VOTE("no tally seen (one legal card, or the player fell back)"),
        FLAT_ZERO("every card at zero: it thought the game lost, tiebreak chose"),
        TIED("a winning card tied with the one played: tiebreak chose"),
        NARROW("a winning card outvoted by at most a tenth of the worlds"),
        WIDE("every winning card outvoted by more than a tenth");
        final String meaning;
        VoteClass(String meaning) { this.meaning = meaning; }
    }

    static VoteClass classify(Decision d) {
        if (d.votes() == null || d.worldsSearched() == 0) return VoteClass.NO_VOTE;
        int max = 0;
        for (int v : d.votes().values()) max = Math.max(max, v);
        if (max == 0) return VoteClass.FLAT_ZERO;
        int chosen = d.votes().getOrDefault(d.played(), 0);
        int bestWinner = -1;
        for (Card card : d.winningCards()) bestWinner = Math.max(bestWinner, d.votes().getOrDefault(card, 0));
        if (bestWinner >= chosen) return VoteClass.TIED;
        return (chosen - bestWinner) * 10 <= d.worldsSearched() ? VoteClass.NARROW : VoteClass.WIDE;
    }

    static String report(List<BoardAudit> audits, int asked, String us, String parId) {
        StringBuilder out = new StringBuilder();
        int n = audits.size();
        int oursWon = 0, parWon = 0;
        long oursTp = 0, parTp = 0;
        for (BoardAudit a : audits) {
            if (a.ours().won()) oursWon++;
            if (a.par().won()) parWon++;
            oursTp += a.ours().declarerTournamentPoints();
            parTp += a.par().declarerTournamentPoints();
        }
        out.append(String.format(Locale.ROOT, "%n%d boards declared (%d skipped: no contract)%n", n, asked - n));
        out.append("\n1. POOLED -- the ZERO-CHECK lines above are the ones to hold against the arena logs, seed by seed\n");
        out.append(String.format(Locale.ROOT, "   %-28s wins %d of %d = %.2f%%   from declaring %.2f%n",
                us, oursWon, n, pct(oursWon, n), oursTp / (3.0 * n)));
        out.append(String.format(Locale.ROOT, "   %-28s wins %d of %d = %.2f%%   from declaring %.2f%n",
                parId, parWon, n, pct(parWon, n), parTp / (3.0 * n)));
        out.append(String.format(Locale.ROOT, "   card-play share on these boards: %.2f%n",
                (parTp - oursTp) / (3.0 * n)));

        // 2. Paired outcomes by whether the ten cards were cold.
        out.append("\n2. THE SAME BOARD, BOTH DECLARERS   (cold = won with every hand face up, after the discard)\n");
        out.append("                 boards  both win  only par  only us  neither   gap, in the gate's unit  (of it: same result, different value)\n");
        for (int pass = 0; pass < 2; pass++) {
            boolean cold = pass == 0;
            int boards = 0, both = 0, onlyPar = 0, onlyUs = 0, neither = 0;
            long tp = 0, sameResultTp = 0;
            for (BoardAudit a : audits) {
                // The two games share the ten cards only when the discards
                // agree; par's own verdict is the one that names the bucket.
                if (a.par().coldAtStart() != cold) continue;
                boards++;
                boolean u = a.ours().won(), p = a.par().won();
                if (u && p) both++; else if (p) onlyPar++; else if (u) onlyUs++; else neither++;
                int diff = a.par().declarerTournamentPoints() - a.ours().declarerTournamentPoints();
                tp += diff;
                // Schneider either way: a game both declarers won or both lost
                // can still be charged at two different values.
                if (u == p) sameResultTp += diff;
            }
            out.append(String.format(Locale.ROOT, "   %-12s %6d  %8d  %8d  %7d  %7d   %+.2f  (%+.2f)%n",
                    cold ? "cold" : "not cold", boards, both, onlyPar, onlyUs, neither,
                    tp / (3.0 * n), sameResultTp / (3.0 * n)));
        }
        int disagree = 0;
        for (BoardAudit a : audits) if (a.ours().coldAtStart() != a.par().coldAtStart()) disagree++;
        out.append(String.format(Locale.ROOT,
                "   boards where the two games disagree about 'cold' (different discard or lead): %d%n", disagree));

        // 3. Flips.
        out.append("\n3. FLIPS   (throw = declarer's card turns won into lost; gift = defender's card turns lost into won)\n");
        for (int pass = 0; pass < 2; pass++) {
            boolean ours = pass == 0;
            int throwsN = 0, gifts = 0, gamesThrown = 0, gamesGifted = 0, gavenBack = 0;
            for (BoardAudit a : audits) {
                Game g = ours ? a.ours() : a.par();
                int t = g.count(true), f = g.count(false);
                throwsN += t; gifts += f;
                if (t > 0) gamesThrown++;
                if (f > 0) gamesGifted++;
                Decision last = g.decisive();
                if (f > 0 && last != null && last.thrown()) gavenBack++;
            }
            out.append(String.format(Locale.ROOT,
                    "   %-28s throws %d in %d games; gifts received %d in %d games; gifted and then thrown back for good: %d%n",
                    ours ? us : parId, throwsN, gamesThrown, gifts, gamesGifted, gavenBack));
        }
        out.append("   gifts in games that were NOT cold, per such game -- different lines draw different mistakes:\n");
        for (int pass = 0; pass < 2; pass++) {
            boolean ours = pass == 0;
            int games = 0, withGift = 0, won = 0;
            for (BoardAudit a : audits) {
                Game g = ours ? a.ours() : a.par();
                if (g.coldAtStart()) continue;
                games++;
                if (g.count(false) > 0) withGift++;
                if (g.won()) won++;
            }
            out.append(String.format(Locale.ROOT, "   %-28s %d games, a gift in %d (%.1f%%), won %d (%.1f%%)%n",
                    ours ? us : parId, games, withGift, pct(withGift, games), won, pct(won, games)));
        }

        // 4. The decisive throw, where our declarer lost a game it had held.
        out.append("\n4. OUR LOST GAMES THAT WERE WON AT SOME POINT: the last throw, by cards in hand and by what the tally said\n");
        int[] byCards = new int[11];
        int[][] byCardsAndClass = new int[11][VoteClass.values().length];
        int[] byClass = new int[VoteClass.values().length];
        int[] leadByClass = new int[VoteClass.values().length];
        int held = 0, parWonThose = 0;
        for (BoardAudit a : audits) {
            Game g = a.ours();
            if (g.won()) continue;
            Decision last = g.decisive();
            if (last == null || !last.thrown()) continue;
            held++;
            if (a.par().won()) parWonThose++;
            VoteClass c = classify(last);
            byCards[last.cardsInHand()]++;
            byCardsAndClass[last.cardsInHand()][c.ordinal()]++;
            byClass[c.ordinal()]++;
            if (last.positionInTrick() == 0) leadByClass[c.ordinal()]++;
        }
        out.append(String.format(Locale.ROOT, "   %d such games; par won %d of those boards%n", held, parWonThose));
        out.append("   cards in hand:");
        for (int cards = 10; cards >= 1; cards--) out.append(String.format(Locale.ROOT, "  %d:%d", cards, byCards[cards]));
        out.append('\n');
        for (VoteClass c : VoteClass.values()) {
            out.append(String.format(Locale.ROOT, "   %-10s %3d  (on lead %d)  %s%n", c, byClass[c.ordinal()],
                    leadByClass[c.ordinal()], c.meaning));
            if (byClass[c.ordinal()] > 0) {
                out.append("              by cards in hand:");
                for (int cards = 10; cards >= 1; cards--) {
                    if (byCardsAndClass[cards][c.ordinal()] > 0) {
                        out.append(String.format(Locale.ROOT, "  %d:%d", cards, byCardsAndClass[cards][c.ordinal()]));
                    }
                }
                out.append('\n');
            }
        }

        // 5. A declarer that believes it has lost.
        out.append("\n5. FLAT-ZERO DECISIONS of our declarer (two or more legal cards, every one at zero votes)\n");
        int flat = 0, flatButWon = 0, flatGames = 0, flatGamesParWon = 0, flatGamesWeWon = 0, voted = 0;
        for (BoardAudit a : audits) {
            boolean any = false;
            for (Decision d : a.ours().decisions()) {
                if (!d.byDeclarer() || d.votes() == null) continue;
                voted++;
                if (classify(d) != VoteClass.FLAT_ZERO) continue;
                flat++; any = true;
                if (d.wonBefore()) flatButWon++;
            }
            if (any) {
                flatGames++;
                if (a.par().won()) flatGamesParWon++;
                if (a.ours().won()) flatGamesWeWon++;
            }
        }
        out.append(String.format(Locale.ROOT,
                "   %d of %d voted decisions (%.1f%%), in %d games; the position was in truth WON at %d of them%n",
                flat, voted, pct(flat, voted), flatGames, flatButWon));
        out.append(String.format(Locale.ROOT, "   of those %d games we won %d and par won %d%n",
                flatGames, flatGamesWeWon, flatGamesParWon));

        // 6. Calibration of the vote as a double-dummy statement.
        out.append("\n6. IS THE VOTE CALIBRATED?  every legal card at every voted decision of our declarer\n");
        out.append("   share of sampled worlds in which the card wins -> share of those cards that win in the true world\n");
        String[] labels = {"0%", "1-25%", "26-50%", "51-75%", "76-99%", "100%"};
        int[] cards = new int[6], wins = new int[6];
        double[] claimed = new double[6];
        int[] chosenCards = new int[6], chosenWins = new int[6];
        for (BoardAudit a : audits) {
            for (Decision d : a.ours().decisions()) {
                if (!d.byDeclarer() || d.votes() == null || d.worldsSearched() == 0) continue;
                for (Map.Entry<Card, Integer> entry : d.votes().entrySet()) {
                    double share = entry.getValue() / (double) d.worldsSearched();
                    int bucket = share <= 0 ? 0 : share >= 1 ? 5 : share <= 0.25 ? 1 : share <= 0.5 ? 2 : share <= 0.75 ? 3 : 4;
                    boolean wins_ = d.winningCards().contains(entry.getKey());
                    cards[bucket]++; claimed[bucket] += share;
                    if (wins_) wins[bucket]++;
                    if (entry.getKey().equals(d.played())) {
                        chosenCards[bucket]++;
                        if (wins_) chosenWins[bucket]++;
                    }
                }
            }
        }
        out.append("   vote share    cards   mean claim   true   |  the card played: n   true\n");
        for (int b = 0; b < 6; b++) {
            out.append(String.format(Locale.ROOT, "   %-10s %8d   %8.1f%%  %5.1f%%  |  %18d  %5.1f%%%n", labels[b],
                    cards[b], cards[b] == 0 ? 0 : 100 * claimed[b] / cards[b], pct(wins[b], cards[b]),
                    chosenCards[b], pct(chosenWins[b], chosenCards[b])));
        }
        return out.toString();
    }

    private static double pct(int part, int whole) { return whole == 0 ? 0 : 100.0 * part / whole; }

    // ---------------------------------------------------------------------- CSV

    static String gamesCsv(List<BoardAudit> audits, long seed) {
        StringBuilder csv = new StringBuilder("seed,board,contract,declarer,side,cold_at_start,won,"
                + "declarer_points,game_value,declarer_tp,throws,gifts,decisive_trick,decisive_by,"
                + "decisive_cards_in_hand,decisive_vote_class\n");
        for (BoardAudit a : audits) {
            for (int pass = 0; pass < 2; pass++) {
                Game g = pass == 0 ? a.ours() : a.par();
                Decision last = g.decisive();
                csv.append(seed).append(',').append(a.board()).append(',')
                        .append(a.fixed().contract()).append(',').append(a.fixed().declarer()).append(',')
                        .append(pass == 0 ? "us" : "par").append(',')
                        .append(g.coldAtStart() ? 1 : 0).append(',').append(g.won() ? 1 : 0).append(',')
                        .append(g.outcome().declarerPoints()).append(',').append(g.outcome().gameValue()).append(',')
                        .append(g.declarerTournamentPoints()).append(',')
                        .append(g.count(true)).append(',').append(g.count(false)).append(',')
                        .append(last == null ? "" : last.trick()).append(',')
                        .append(last == null ? "" : last.byDeclarer() ? "declarer" : "defender").append(',')
                        .append(last == null ? "" : last.cardsInHand()).append(',')
                        .append(last == null || !last.thrown() || pass == 1 ? "" : classify(last)).append('\n');
            }
        }
        return csv.toString();
    }

    static String decisionsCsv(List<BoardAudit> audits, long seed) {
        StringBuilder csv = new StringBuilder("seed,board,contract,side,trick,position_in_trick,by,"
                + "cards_in_hand,legal,legal_winning,won_before,won_after,played,winning_cards,"
                + "worlds,cushion_asked,vote_class,votes\n");
        for (BoardAudit a : audits) {
            for (int pass = 0; pass < 2; pass++) {
                Game g = pass == 0 ? a.ours() : a.par();
                for (Decision d : g.decisions()) {
                    csv.append(seed).append(',').append(a.board()).append(',')
                            .append(a.fixed().contract()).append(',').append(pass == 0 ? "us" : "par").append(',')
                            .append(d.trick()).append(',').append(d.positionInTrick()).append(',')
                            .append(d.byDeclarer() ? "declarer" : "defender").append(',')
                            .append(d.cardsInHand()).append(',').append(d.legal()).append(',')
                            .append(d.legalWinning()).append(',')
                            .append(d.wonBefore() ? 1 : 0).append(',').append(d.wonAfter() ? 1 : 0).append(',')
                            .append(code(d.played())).append(',');
                    for (Card card : d.winningCards()) csv.append(code(card)).append(' ');
                    csv.append(',').append(d.worldsSearched()).append(',')
                            .append(d.cushionAsked() ? 1 : 0).append(',')
                            .append(d.votes() == null ? "" : classifyForCsv(d)).append(',');
                    if (d.votes() != null) {
                        for (Map.Entry<Card, Integer> vote : d.votes().entrySet()) {
                            csv.append(code(vote.getKey())).append(':').append(vote.getValue()).append(' ');
                        }
                    }
                    csv.append('\n');
                }
            }
        }
        return csv.toString();
    }

    /** The class only means something at a throw; elsewhere the column says how flat the tally was. */
    private static String classifyForCsv(Decision d) {
        if (d.thrown()) return classify(d).name();
        return classify(d) == VoteClass.FLAT_ZERO ? "FLAT_ZERO" : "";
    }

    /** "CJ", "H10", "S7": suit initial and rank, no commas and no spaces. */
    static String code(Card card) {
        return card.suit.name().charAt(0) + card.rank.label;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            int eq = arg.indexOf('=');
            if (eq < 0) options.put(arg.substring(2), "true");
            else options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return options;
    }
}
