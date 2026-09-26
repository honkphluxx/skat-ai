package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Do our defenders hand the declarer games that another defence would not?
 *
 * <p>The declaring audit put two declarers in front of the same defenders. This
 * turns it round: the same declarer -- {@code --player}, the player that ships
 * -- on the same boards, at the same contracts, played once against itself in
 * both defender seats and once against {@code --defenders} in both. The
 * providers are seeded as {@link DeclaringAuditMain} seeds them, so the first
 * game of every board is the declaring audit's own game for that board, and
 * the report prints its declarer's wins to be held against that audit's.
 *
 * <p>Every card is solved with the hands face up, as there. A defender's card
 * that turns a position the declarer cannot win into one it can is a gift; a
 * declarer's card that turns a won position into a lost one is a throw. The
 * report compares the two defences on what they are paid for -- the games the
 * declarer wins, and what each side takes off the score sheet -- then on the
 * games that were not cold for the declarer (the defence had them), then on
 * the gifts, by what the defender was doing: leading, following suit, trumping
 * in, or discarding, and whose card held the trick. Rates are over the
 * chances -- defender decisions in a position the declarer could not yet win
 * -- because the two defences do not reach the same positions. Last, the
 * throws the declarer made against each defence: a defence that makes the
 * declarer go wrong earns those too.
 *
 * <pre>./gradlew :arena:defendingAudit --args="--seeds=14,15,16 --boards=200 --defenders=skatzero --threads=16 --out=arena-logs/defending-audit/skatzero"</pre>
 */
public final class DefendingAuditMain {

    private DefendingAuditMain() {}

    /** What a defender was doing with a card. */
    enum Kind {
        LEAD("leads the trick"), FOLLOW("follows suit"), TRUMP_IN("void, trumps in"),
        DISCARD("void, discards");
        final String label;
        Kind(String label) { this.label = label; }
    }

    /** Who held the trick when the card was played; nobody on a lead. */
    enum Holder { NOBODY, PARTNER, DECLARER }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String rules = Rules.apply(options);
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve(options.getOrDefault("player", "belief-32-shipped"));
        Contestant defenders = registry.resolve(options.getOrDefault("defenders", "skatzero"));
        int boards = Integer.parseInt(options.getOrDefault("boards", "200"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        List<Long> seeds = new ArrayList<>();
        for (String part : options.getOrDefault("seeds", "14,15,16").split("[ ,]+")) {
            if (!part.isBlank()) seeds.add(Long.parseLong(part.trim()));
        }
        String out = options.get("out");
        System.out.printf(Locale.ROOT, "Defending audit: %s declaring, against itself and against %s defending, "
                + "%d boards a seed, seeds %s, %s%n", player.id(), defenders.id(), boards, seeds, rules);

        List<DeclaringAuditMain.BoardAudit> pooled = new ArrayList<>();
        for (long seed : seeds) {
            ContractSource contracts = new AuctionContractSource(
                    registry.resolve(options.getOrDefault("bidder", "greedy")), seed);
            List<Callable<DeclaringAuditMain.BoardAudit>> work = new ArrayList<>();
            for (int i = 0; i < boards; i++) {
                final Board board = Board.of(seed, i);
                work.add(() -> {
                    ContractSource.FixedContract fixed = contracts.contractFor(board);
                    if (fixed == null) return null;
                    return new DeclaringAuditMain.BoardAudit(board.index(), fixed,
                            DeclaringAuditMain.play(board, seed, fixed, player, player, false, true),
                            DeclaringAuditMain.play(board, seed, fixed, player, defenders, false));
                });
            }
            List<DeclaringAuditMain.BoardAudit> audits = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
            try {
                for (Future<DeclaringAuditMain.BoardAudit> f : pool.invokeAll(work)) {
                    if (f.get() != null) audits.add(f.get());
                }
            } finally {
                pool.shutdownNow();
            }
            int ownWins = 0;
            for (DeclaringAuditMain.BoardAudit a : audits) if (a.ours().won()) ownWins++;
            // The same shape as the declaring audit's ZERO-CHECK, for the script to hold against it.
            System.out.printf(Locale.ROOT, "SAME-GAMES seed %d boards %d us-wins %d%n", seed, audits.size(), ownWins);
            if (out != null) {
                Path dir = Path.of(out).toAbsolutePath();
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("defending-audit-games-s" + seed + ".csv"),
                        DeclaringAuditMain.gamesCsv(audits, seed));
                Files.writeString(dir.resolve("defending-audit-decisions-s" + seed + ".csv"),
                        DeclaringAuditMain.decisionsCsv(audits, seed));
            }
            pooled.addAll(audits);
        }
        System.out.print(report(pooled, player.id(), defenders.id()));
        if (out != null) {
            System.out.println("Per-game and per-decision files written to " + Path.of(out).toAbsolutePath()
                    + " (side \"us\": our defenders; \"par\": " + defenders.id() + ")");
        }
    }

    // ------------------------------------------------------------------ reading a game

    /** One defender card, with what it was doing and whether it was a gift. */
    record DefenderCard(Kind kind, Holder holder, boolean chance, boolean gift, int cardPoints,
                        boolean trump, DeclaringAuditMain.Decision decision) {
        /** Onto the partner's card with the declarer still to play: the partner led, this is second. */
        boolean beforeTheDeclarer() { return holder == Holder.PARTNER && decision.positionInTrick() == 1; }
    }

    /**
     * How the defender's own tally stood at a card, in the declaring audit's
     * classes read from the defence's side: the cards that keep the declarer
     * from winning (the legal cards not among the solver's winning cards)
     * against the card played. At a gift: every card at zero (it thought the
     * game lost, the tiebreak chose), a safe card tied with it (the tiebreak
     * chose), every safe card outvoted by at most a tenth of the worlds
     * (narrow), or by more (its worlds were confidently wrong).
     */
    static DeclaringAuditMain.VoteClass classify(DeclaringAuditMain.Decision d) {
        if (d.votes() == null || d.worldsSearched() == 0) return DeclaringAuditMain.VoteClass.NO_VOTE;
        int max = 0;
        for (int v : d.votes().values()) max = Math.max(max, v);
        if (max == 0) return DeclaringAuditMain.VoteClass.FLAT_ZERO;
        int chosen = d.votes().getOrDefault(d.played(), 0);
        int bestSafe = -1;
        for (Map.Entry<Card, Integer> vote : d.votes().entrySet()) {
            if (!d.winningCards().contains(vote.getKey())) bestSafe = Math.max(bestSafe, vote.getValue());
        }
        if (bestSafe >= chosen) return DeclaringAuditMain.VoteClass.TIED;
        return (chosen - bestSafe) * 10 <= d.worldsSearched()
                ? DeclaringAuditMain.VoteClass.NARROW : DeclaringAuditMain.VoteClass.WIDE;
    }

    /** The defenders' cards of a game, each placed in its trick. Null games are not classified. */
    static List<DefenderCard> defenderCards(Contract contract, DeclaringAuditMain.Game game) {
        List<DefenderCard> cards = new ArrayList<>();
        List<Card> trick = new ArrayList<>(3);
        List<Boolean> byDeclarer = new ArrayList<>(3);
        for (DeclaringAuditMain.Decision d : game.decisions()) {
            if (d.positionInTrick() == 0) { trick.clear(); byDeclarer.clear(); }
            if (trick.size() != d.positionInTrick()) {
                throw new IllegalStateException("decisions out of order at trick " + d.trick());
            }
            if (!d.byDeclarer()) {
                Kind kind;
                Holder holder = Holder.NOBODY;
                if (trick.isEmpty()) {
                    kind = Kind.LEAD;
                } else {
                    Card led = trick.get(0);
                    int best = 0;
                    for (int i = 1; i < trick.size(); i++) {
                        if (SkatRules.beats(contract, led, trick.get(best), trick.get(i))) best = i;
                    }
                    holder = byDeclarer.get(best) ? Holder.DECLARER : Holder.PARTNER;
                    SkatAi.FollowClass ledClass = SkatRules.publicFollowClass(contract, led);
                    SkatAi.FollowClass mine = SkatRules.publicFollowClass(contract, d.played());
                    if (mine.equals(ledClass)) kind = Kind.FOLLOW;
                    else if (mine.equals(SkatAi.FollowClass.trump())) kind = Kind.TRUMP_IN;
                    else kind = Kind.DISCARD;
                }
                boolean chance = !d.wonBefore() && d.legal() > 1;
                cards.add(new DefenderCard(kind, holder, chance, d.gift(), SkatRules.cardPoints(d.played()),
                        SkatRules.publicFollowClass(contract, d.played()).equals(SkatAi.FollowClass.trump()), d));
            }
            trick.add(d.played());
            byDeclarer.add(d.byDeclarer());
        }
        return cards;
    }

    // ------------------------------------------------------------------ reporting

    static String report(List<DeclaringAuditMain.BoardAudit> audits, String us, String them) {
        StringBuilder out = new StringBuilder();
        String[] names = {us + " (itself)", them};
        int n = audits.size();
        out.append(String.format(Locale.ROOT, "%n1. RESULT   %d games, the declarer %s every time%n", n, us));
        out.append(String.format(Locale.ROOT, "   %-30s %12s %16s %16s%n", "defenders", "decl. won",
                "decl. g.p./game", "defence g.p./game"));
        for (int side = 0; side < 2; side++) {
            int won = 0;
            double declTp = 0, defTp = 0;
            for (DeclaringAuditMain.BoardAudit a : audits) {
                DeclaringAuditMain.Game g = side == 0 ? a.ours() : a.par();
                if (g.won()) won++;
                declTp += g.declarerTournamentPoints();
                for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                    if (seat != a.fixed().declarer()) defTp += Scoring.tournamentPoints(g.outcome(), seat);
                }
            }
            out.append(String.format(Locale.ROOT, "   %-30s %5d %5.1f%% %+16.2f %+16.2f%n", names[side], won,
                    pct(won, n), declTp / (3.0 * n), defTp / (3.0 * n)));
        }
        int onlyOurs = 0, onlyTheirs = 0;
        for (DeclaringAuditMain.BoardAudit a : audits) {
            if (a.ours().won() && !a.par().won()) onlyTheirs++;
            if (!a.ours().won() && a.par().won()) onlyOurs++;
        }
        out.append(String.format(Locale.ROOT, "   boards the declarer won only against us: %d; only against %s: %d%n",
                onlyTheirs, them, onlyOurs));
        out.append("   (defence g.p.: what the two defender seats take off the sheet together, the 40 a seat for a\n"
                + "    beaten declarer; the pairing's own number would be this minus the declarer's column)\n");

        out.append(String.format(Locale.ROOT, "%n2. NOT COLD   games the declarer could not win at the first card, "
                + "with every hand face up%n"));
        for (int side = 0; side < 2; side++) {
            int games = 0, declarerWon = 0, gifted = 0, decisiveGift = 0;
            for (DeclaringAuditMain.BoardAudit a : audits) {
                DeclaringAuditMain.Game g = side == 0 ? a.ours() : a.par();
                if (g.coldAtStart()) continue;
                games++;
                if (g.won()) {
                    declarerWon++;
                    DeclaringAuditMain.Decision last = g.decisive();
                    if (last != null && last.gift()) decisiveGift++;
                }
                if (g.count(false) > 0) gifted++;
            }
            out.append(String.format(Locale.ROOT, "   %-30s %d games; a gift in %d (%.1f%%); the declarer won %d "
                    + "(%.1f%%), %d settled by a gift%n", names[side], games, gifted, pct(gifted, games), declarerWon,
                    pct(declarerWon, games), decisiveGift));
        }

        out.append(String.format(Locale.ROOT, "%n3. GIFTS BY WHAT THE DEFENDER WAS DOING   chances (defender decisions, "
                + "more than one legal card, declarer not yet winning) and gifts among them; trump games%n"));
        out.append(String.format(Locale.ROOT, "   %-34s", ""));
        for (String name : names) out.append(String.format(Locale.ROOT, " %30s", name));
        out.append('\n');
        Map<String, int[][]> rows = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            for (Holder holder : Holder.values()) {
                if ((kind == Kind.LEAD) != (holder == Holder.NOBODY)) continue;
                rows.put(kind.label + (holder == Holder.NOBODY ? "" : ", " + holder.name().toLowerCase(Locale.ROOT)
                        + " holds"), new int[2][2]);
            }
        }
        int[][] total = new int[2][2];
        int[][] highGifts = new int[2][1];
        for (DeclaringAuditMain.BoardAudit a : audits) {
            if (a.fixed().contract().isNull()) continue;
            for (int side = 0; side < 2; side++) {
                DeclaringAuditMain.Game g = side == 0 ? a.ours() : a.par();
                for (DefenderCard c : defenderCards(a.fixed().contract(), g)) {
                    if (!c.chance()) continue;
                    String key = c.kind().label + (c.holder() == Holder.NOBODY ? ""
                            : ", " + c.holder().name().toLowerCase(Locale.ROOT) + " holds");
                    int[][] row = rows.get(key);
                    row[side][0]++;
                    total[side][0]++;
                    if (c.gift()) {
                        row[side][1]++;
                        total[side][1]++;
                        if (c.cardPoints() >= 10) highGifts[side][0]++;
                    }
                }
            }
        }
        for (Map.Entry<String, int[][]> row : rows.entrySet()) {
            out.append(String.format(Locale.ROOT, "   %-34s", row.getKey()));
            for (int side = 0; side < 2; side++) out.append(cell(row.getValue()[side]));
            out.append('\n');
        }
        out.append(String.format(Locale.ROOT, "   %-34s", "all"));
        for (int side = 0; side < 2; side++) out.append(cell(total[side]));
        out.append('\n');
        out.append(String.format(Locale.ROOT, "   gifts that were an ace or a ten: %d against %d%n",
                highGifts[0][0], highGifts[1][0]));

        out.append(String.format(Locale.ROOT, "%n4. NULL   the defence's side of the declarer's Nulls%n"));
        for (int side = 0; side < 2; side++) {
            int games = 0, won = 0, gifts = 0;
            for (DeclaringAuditMain.BoardAudit a : audits) {
                if (!a.fixed().contract().isNull()) continue;
                DeclaringAuditMain.Game g = side == 0 ? a.ours() : a.par();
                games++;
                if (g.won()) won++;
                gifts += g.count(false);
            }
            out.append(String.format(Locale.ROOT, "   %-30s %d Nulls, the declarer won %d, gifts %d%n",
                    names[side], games, won, gifts));
        }

        out.append(String.format(Locale.ROOT, "%n5. THROWS   the declarer's own cards that turned a won game lost, "
                + "against each defence%n"));
        for (int side = 0; side < 2; side++) {
            int throwsN = 0, games = 0, cold = 0, coldLost = 0;
            for (DeclaringAuditMain.BoardAudit a : audits) {
                DeclaringAuditMain.Game g = side == 0 ? a.ours() : a.par();
                int t = g.count(true);
                throwsN += t;
                if (t > 0) games++;
                if (g.coldAtStart()) { cold++; if (!g.won()) coldLost++; }
            }
            out.append(String.format(Locale.ROOT, "   %-30s throws %d in %d games; cold at the first card %d, "
                    + "of those lost %d%n", names[side], throwsN, games, cold, coldLost));
        }
        out.append(voteSection(audits, us));
        return out.toString();
    }

    /** Our defenders' own tallies at their gifts, and their calibration over every chance. */
    static String voteSection(List<DeclaringAuditMain.BoardAudit> audits, String us) {
        StringBuilder out = new StringBuilder();
        String[] groups = {"points onto the partner's card, declarer to play", "ace or ten led, tricks 2-4",
                "every other gift", "all gifts"};
        DeclaringAuditMain.VoteClass[] classes = DeclaringAuditMain.VoteClass.values();
        int[][] counts = new int[groups.length][classes.length];
        // Calibration: the vote share of the card played, against how often it was a gift.
        String[] bins = {"0", "1-25%", "26-50%", "51-75%", "76-99%", "100%"};
        int[] binChances = new int[bins.length], binGifts = new int[bins.length];
        double[] binClaim = new double[bins.length];
        for (DeclaringAuditMain.BoardAudit a : audits) {
            if (a.fixed().contract().isNull()) continue;
            for (DefenderCard c : defenderCards(a.fixed().contract(), a.ours())) {
                if (!c.chance()) continue;
                DeclaringAuditMain.Decision d = c.decision();
                if (d.votes() != null && d.worldsSearched() > 0) {
                    double share = d.votes().getOrDefault(d.played(), 0) / (double) d.worldsSearched();
                    int b = share == 0 ? 0 : share >= 1 ? 5 : share <= 0.25 ? 1 : share <= 0.5 ? 2 : share <= 0.75 ? 3 : 4;
                    binChances[b]++;
                    binClaim[b] += share;
                    if (c.gift()) binGifts[b]++;
                }
                if (!c.gift()) continue;
                int group;
                if (c.beforeTheDeclarer() && c.cardPoints() >= 3) group = 0;
                else if (c.kind() == Kind.LEAD && !c.trump() && c.cardPoints() >= 10
                        && d.trick() >= 2 && d.trick() <= 4) group = 1;
                else group = 2;
                int k = classify(d).ordinal();
                counts[group][k]++;
                counts[3][k]++;
            }
        }
        out.append(String.format(Locale.ROOT, "%n6. OUR DEFENDERS' OWN TALLY AT THEIR GIFTS (%s, trump games)%n", us));
        out.append(String.format(Locale.ROOT, "   %-50s", ""));
        for (DeclaringAuditMain.VoteClass vc : classes) out.append(String.format(Locale.ROOT, " %9s", vc.name()));
        out.append('\n');
        for (int g = 0; g < groups.length; g++) {
            out.append(String.format(Locale.ROOT, "   %-50s", groups[g]));
            for (int k = 0; k < classes.length; k++) out.append(String.format(Locale.ROOT, " %9d", counts[g][k]));
            out.append('\n');
        }
        out.append("   FLAT_ZERO: every card at zero, it thought the game lost and the tiebreak chose; TIED: a safe\n"
                + "   card had as many worlds, the tiebreak chose; NARROW / WIDE: every safe card outvoted by at most /\n"
                + "   more than a tenth of the worlds -- its worlds said the gift was the better card.\n");
        out.append("   Calibration over every chance: the share of its worlds in which the card played holds the\n"
                + "   declarer, against how often it was in fact a gift\n");
        out.append(String.format(Locale.ROOT, "   %-10s %9s %12s %9s%n", "share", "chances", "mean claim", "gifts"));
        for (int b = 0; b < bins.length; b++) {
            out.append(String.format(Locale.ROOT, "   %-10s %9d %11.1f%% %4d = %4.1f%%%n", bins[b], binChances[b],
                    binChances[b] == 0 ? 0 : 100 * binClaim[b] / binChances[b], binGifts[b],
                    pct(binGifts[b], binChances[b])));
        }
        return out.toString();
    }

    private static String cell(int[] chancesAndGifts) {
        return String.format(Locale.ROOT, " %11d, %4d = %5.1f%%   ", chancesAndGifts[0], chancesAndGifts[1],
                pct(chancesAndGifts[1], chancesAndGifts[0]));
    }

    private static double pct(int part, int whole) { return whole == 0 ? 0 : 100.0 * part / whole; }

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
