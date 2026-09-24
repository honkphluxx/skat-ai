package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.BeliefWorldSource;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import dev.skatklar.demo.search.WorldSource;
import java.util.*;

/**
 * B2 step 4, measured before it is built: at every decision of fixed-contract
 * play, how well do worlds place the unseen cards under four samplers --
 * the player's own sequential draw, confident-first, uniform, and the
 * paper's estimator (K uniform consistent worlds, each weighted by the
 * product of the net's per-card marginals, 32 resampled from them)?
 *
 * <p>placed% = share of the unseen cards a world puts in the right hand,
 * averaged over the 32 worlds. ESS = effective sample size of the K weights,
 * (sum w)^2 / sum w^2: how many of the K candidates the resample really
 * draws from.
 */
public final class ReweightProbe {
    static final int N = 32;
    static final int[] KS = {64, 256, 1024};

    public static void main(String[] a) throws Exception {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve("belief-32-shipped");
        int boards = Integer.parseInt(a[0]);
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 12;
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        BeliefWorldSource probe = new BeliefWorldSource(
                dev.skatklar.training.belief.NetBeliefModel.load(java.nio.file.Path.of("belief-model")));
        // rows: 0 sequential (player's), 1 confident, 2 uniform, 3.. reweight K, then SIR K, then argmax
        int rows = 3 + 2 * KS.length + 2;
        double[][][] sum = new double[2][rows][11]; double[][] cnt = new double[2][11];
        double[][][] ess = new double[2][2 * KS.length][11];
        int[] games = {0};
        for (int i = 0; i < boards; i++) {
            Board board = Board.of(seed, i);
            ContractSource.FixedContract fixed = contracts.contractFor(board);
            if (fixed == null) continue;
            SkatAi.Seat declarer = fixed.declarer();
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            GameEngine[] eng = new GameEngine[1];
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                SkatAiProvider p = player.newProvider(Seeds.mix(seed, board.index(), declarer.ordinal(), seat.ordinal()));
                if (p instanceof SearchAiProvider s) {
                    p = s.withCardPlayObserver(new SearchAiProvider.CardPlayObserver() {
                        @Override public void decided(SearchAiProvider.CardPlayReport r) {}
                        @Override public void sampled(SkatAi.DecisionContext c, BeliefEncoding.Evidence e, List<WorldSampler.World> drawn) {
                            if (!c.game.hasDeclarer()) return;
                            GameEngine.Snapshot truth = eng[0].snapshot();
                            int trick = truth.completedTricks + 1;
                            double[][] belief = probe.belief(e);
                            if (belief == null) return;
                            SkatAi.Seat me = c.mySeat;
                            boolean decl = me == c.game.declarer;
                            boolean knowsSkat = decl && !c.game.hand;
                            int role = decl ? 0 : 1;
                            // truth: where each unseen card is (seat ordinal, 3 = skat)
                            Map<Card, Integer> where = new HashMap<>();
                            for (int seat = 0; seat < 3; seat++) {
                                if (seat == me.ordinal()) continue;
                                for (Card card : truth.hands.get(seat)) where.put(card, seat);
                            }
                            if (!knowsSkat) for (Card card : truth.skat) where.put(card, 3);
                            int unseen = where.size();
                            if (unseen == 0) return;
                            WorldSampler.Weights w = (card, place) -> {
                                int idx = BeliefEncoding.index(card);
                                if (place == 3) return belief[idx][BeliefEncoding.CLASS_SKAT];
                                if (place == me.next().ordinal()) return belief[idx][BeliefEncoding.CLASS_LEFT];
                                return belief[idx][BeliefEncoding.CLASS_RIGHT];
                            };
                            Random random = new Random(Seeds.mix(seed, board.index(), trick, me.ordinal()));
                            WorldSampler uniform = WorldSampler.forDecision(c, WorldSource.knownSkat(e), e.rememberedPlays(), e.rememberedVoids());
                            WorldSampler confident = uniform.weightedBy(w).confidentFirst();

                            double[] row = new double[rows];
                            row[0] = placed(drawn, where, me);
                            row[1] = placed(draw(confident, N, random), where, me);
                            // K uniform candidates, once at the largest K; smaller K use a prefix.
                            int kmax = KS[KS.length - 1];
                            List<WorldSampler.World> cand = draw(uniform, kmax, random);
                            row[2] = placed(cand.subList(0, Math.min(N, cand.size())), where, me);
                            double[] logw = new double[cand.size()];
                            for (int k = 0; k < cand.size(); k++) logw[k] = logWeight(cand.get(k), w, me, knowsSkat);
                            double[] essHere = new double[2 * KS.length];
                            for (int ki = 0; ki < KS.length; ki++) {
                                int k = Math.min(KS[ki], cand.size());
                                double max = Double.NEGATIVE_INFINITY;
                                for (int j = 0; j < k; j++) max = Math.max(max, logw[j]);
                                double[] wt = new double[k]; double tot = 0, sq = 0;
                                for (int j = 0; j < k; j++) { wt[j] = Math.exp(logw[j] - max); tot += wt[j]; sq += wt[j] * wt[j]; }
                                essHere[ki] = tot * tot / sq;
                                List<WorldSampler.World> re = new ArrayList<>(N);
                                for (int n = 0; n < N; n++) {
                                    double point = random.nextDouble() * tot; int j = 0;
                                    while (j < k - 1 && (point -= wt[j]) > 0) j++;
                                    re.add(cand.get(j));
                                }
                                row[3 + ki] = placed(re, where, me);
                            }
                            // SIR: sequential proposal, corrected to the product of marginals.
                            Seq seq = new Seq(c, e, w, me);
                            List<WorldSampler.World> sw = new ArrayList<>(kmax); double[] slog = new double[kmax];
                            int got = 0;
                            for (int k = 0; k < kmax; k++) {
                                double[] lq = new double[1];
                                WorldSampler.World world = seq.draw(random, lq);
                                if (world == null) continue;
                                sw.add(world); slog[got++] = logWeight(world, w, me, knowsSkat) - lq[0];
                            }
                            for (int ki = 0; ki < KS.length; ki++) {
                                int k = Math.min(KS[ki], got);
                                double max = Double.NEGATIVE_INFINITY;
                                for (int j = 0; j < k; j++) max = Math.max(max, slog[j]);
                                double[] wt = new double[k]; double tot = 0, sq = 0;
                                for (int j = 0; j < k; j++) { wt[j] = Math.exp(slog[j] - max); tot += wt[j]; sq += wt[j] * wt[j]; }
                                essHere[KS.length + ki] = k == 0 ? 0 : tot * tot / sq;
                                List<WorldSampler.World> re = new ArrayList<>(N);
                                for (int n = 0; n < N && k > 0; n++) {
                                    double point = random.nextDouble() * tot; int j = 0;
                                    while (j < k - 1 && (point -= wt[j]) > 0) j++;
                                    re.add(sw.get(j));
                                }
                                row[3 + KS.length + ki] = placed(re, where, me);
                            }
                            // argmax
                            int hit = 0;
                            for (Map.Entry<Card, Integer> en : where.entrySet()) {
                                int best = -1; double bw = -1;
                                for (int place : new int[] {me.next().ordinal(), 3 - me.ordinal() - me.next().ordinal(), 3}) {
                                    if (place == 3 && knowsSkat) continue;
                                    double v = w.weight(en.getKey(), place);
                                    if (v > bw) { bw = v; best = place; }
                                }
                                if (best == en.getValue()) hit++;
                            }
                            row[rows - 1] = (double) hit / unseen;
                            // expected placed% of an exact independent draw from the marginals:
                            // the normalised probability the net gives the true place, per card.
                            double ptrue = 0;
                            for (Map.Entry<Card, Integer> en : where.entrySet()) {
                                double tot = 0, mine2 = 0;
                                for (int place : new int[] {me.next().ordinal(), 3 - me.ordinal() - me.next().ordinal(), 3}) {
                                    if (place == 3 && knowsSkat) continue;
                                    double v = Math.max(0, w.weight(en.getKey(), place)); tot += v;
                                    if (place == en.getValue()) mine2 = v;
                                }
                                ptrue += tot > 0 ? mine2 / tot : 1.0 / (knowsSkat ? 2 : 3);
                            }
                            row[rows - 2] = ptrue / unseen;
                            synchronized (sum) {
                                cnt[role][trick]++;
                                for (int r = 0; r < rows; r++) sum[role][r][trick] += row[r];
                                for (int ki = 0; ki < 2 * KS.length; ki++) ess[role][ki][trick] += essHere[ki];
                            }
                        }
                    });
                }
                seating.put(seat, p);
            }
            GameEngine engine = GameEngine.headless(new Random(Seeds.mix(seed, board.index(), declarer.ordinal(), 0xE1E1E1L)), SeatedAiProviders.of(seating));
            eng[0] = engine;
            engine.restartWithContract(board.deal(), board.round(), fixed.declarer(), fixed.contract(), fixed.bidValue(), Collections.emptySet(), fixed.auction());
            if (engine.snapshot().definition.isRamsch()) { engine.close(); continue; }
            games[0]++;
            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot s = engine.snapshot();
                if (s.gameComplete()) break;
                if (s.trickComplete()) engine.finishCompletedTrick(); else engine.playAiCard();
            }
            engine.close();
        }
        String[] names = new String[rows];
        names[0] = "sequential (player)"; names[1] = "confident-first"; names[2] = "uniform";
        for (int ki = 0; ki < KS.length; ki++) names[3 + ki] = "reweight K=" + KS[ki];
        for (int ki = 0; ki < KS.length; ki++) names[3 + KS.length + ki] = "SIR K=" + KS[ki];
        names[rows - 1] = "net argmax";
        names[rows - 2] = "net p(true place)";
        System.out.println("FIXED mode, seed " + seed + ", " + games[0] + " games: placed% of unseen cards, by role and trick");
        for (int role = 0; role < 2; role++) {
            System.out.println(role == 0 ? "as declarer" : "as defender");
            for (int r = 0; r < rows; r++) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-20s", names[r]));
                double h = 0, n = 0;
                for (int t = 1; t <= 9; t++) {
                    line.append(String.format(Locale.ROOT, " t%d=%5.1f", t, 100 * sum[role][r][t] / Math.max(1, cnt[role][t])));
                    if (t <= 3) { h += sum[role][r][t]; n += cnt[role][t]; }
                }
                line.append(String.format(Locale.ROOT, "   tricks1-3=%5.1f", 100 * h / Math.max(1, n)));
                System.out.println(line);
            }
            for (int ki = 0; ki < 2 * KS.length; ki++) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-20s", (ki < KS.length ? "ESS uniform K=" : "ESS SIR K=") + KS[ki % KS.length]));
                for (int t = 1; t <= 9; t++) line.append(String.format(Locale.ROOT, " t%d=%5.1f", t, ess[role][ki][t] / Math.max(1, cnt[role][t])));
                System.out.println(line);
            }
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-20s", "decisions"));
            for (int t = 1; t <= 9; t++) line.append(String.format(Locale.ROOT, " t%d=%5.0f", t, cnt[role][t]));
            System.out.println(line);
        }
    }

    /** The sampler's sequential draw, re-implemented so its proposal probability can be read. */
    static final class Seq {
        final dev.skatklar.demo.Contract contract; final SkatAi.Seat me; final WorldSampler.Weights w;
        final List<Card> mine, unknown, knownSkat; final int[] needed = new int[3]; final int skatSlots;
        final Map<SkatAi.Seat, Set<SkatAi.FollowClass>> voids;
        Seq(SkatAi.DecisionContext c, BeliefEncoding.Evidence e, WorldSampler.Weights w, SkatAi.Seat me) {
            this.contract = c.game.contract; this.me = me; this.w = w;
            mine = new ArrayList<>(c.hand);
            Set<Card> accounted = new LinkedHashSet<>(mine); accounted.addAll(e.rememberedPlays());
            List<Card> ks = WorldSource.knownSkat(e);
            if (ks != null && ks.size() == 2 && Collections.disjoint(ks, accounted)) { knownSkat = List.copyOf(ks); accounted.addAll(knownSkat); } else knownSkat = null;
            unknown = new ArrayList<>();
            for (Card card : dev.skatklar.demo.SkatDeck.ordered()) if (!accounted.contains(card)) unknown.add(card);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                int played = 0;
                for (SkatAi.CompletedTrick t : c.history.completedTricks) for (SkatAi.PlayedCard p : t.plays) if (p.seat == seat) played++;
                for (SkatAi.PlayedCard p : c.currentTrick.plays) if (p.seat == seat) played++;
                needed[seat.ordinal()] = seat == me ? 0 : 10 - played;
            }
            skatSlots = knownSkat == null ? 2 : 0;
            voids = e.rememberedVoids();
        }
        List<Integer> placesFor(Card card) {
            List<Integer> places = new ArrayList<>(4);
            SkatAi.FollowClass fc = dev.skatklar.demo.SkatRules.publicFollowClass(contract, card);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                if (seat == me || needed[seat.ordinal()] == 0) continue;
                Set<SkatAi.FollowClass> v = voids.get(seat);
                if (v != null && v.contains(fc)) continue;
                places.add(seat.ordinal());
            }
            if (skatSlots > 0) places.add(3);
            return places;
        }
        WorldSampler.World draw(Random random, double[] logq) {
            for (int attempt = 0; attempt < 40; attempt++) {
                List<Card> order = new ArrayList<>(unknown);
                Collections.shuffle(order, random);
                order.sort((l, r) -> Integer.compare(placesFor(l).size(), placesFor(r).size()));
                int[] remaining = needed.clone(); int skatLeft = skatSlots; double lq = 0;
                List<List<Card>> hands = new ArrayList<>(3);
                for (int seat = 0; seat < 3; seat++) hands.add(seat == me.ordinal() ? new ArrayList<>(mine) : new ArrayList<>());
                List<Card> skat = knownSkat == null ? new ArrayList<>(2) : new ArrayList<>(knownSkat);
                boolean ok = true;
                for (Card card : order) {
                    List<Integer> places = new ArrayList<>(4);
                    for (int place : placesFor(card)) if (place < 3 ? remaining[place] > 0 : skatLeft > 0) places.add(place);
                    if (places.isEmpty()) continue;
                    double total = 0; double[] share = new double[places.size()];
                    for (int at = 0; at < places.size(); at++) { share[at] = Math.max(0, w.weight(card, places.get(at))); total += share[at]; }
                    int chosen; double prob;
                    if (total <= 0) { chosen = random.nextInt(places.size()); prob = 1.0 / places.size(); }
                    else {
                        double point = random.nextDouble() * total; chosen = places.size() - 1;
                        for (int at = 0; at < places.size(); at++) { point -= share[at]; if (point <= 0) { chosen = at; break; } }
                        prob = share[chosen] / total;
                    }
                    lq += Math.log(Math.max(1e-12, prob));
                    int place = places.get(chosen);
                    if (place < 3) { hands.get(place).add(card); remaining[place]--; } else { skat.add(card); skatLeft--; }
                }
                if (skatLeft > 0) ok = false;
                for (int seat = 0; seat < 3; seat++) if (remaining[seat] > 0) ok = false;
                if (ok) { logq[0] = lq; return new WorldSampler.World(hands, skat); }
            }
            return null;
        }
    }

    static List<WorldSampler.World> draw(WorldSampler sampler, int n, Random random) {
        List<WorldSampler.World> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { WorldSampler.World w = sampler.draw(random); if (w != null) out.add(w); }
        return out;
    }

    static double placed(List<WorldSampler.World> worlds, Map<Card, Integer> where, SkatAi.Seat me) {
        if (worlds.isEmpty()) return Double.NaN;
        double total = 0;
        for (WorldSampler.World world : worlds) {
            int hit = 0;
            for (int seat = 0; seat < 3; seat++) {
                if (seat == me.ordinal()) continue;
                for (Card card : world.hands().get(seat)) { Integer real = where.get(card); if (real != null && real == seat) hit++; }
            }
            for (Card card : world.skat()) { Integer real = where.get(card); if (real != null && real == 3) hit++; }
            total += (double) hit / where.size();
        }
        return total / worlds.size();
    }

    static double logWeight(WorldSampler.World world, WorldSampler.Weights w, SkatAi.Seat me, boolean knowsSkat) {
        double log = 0;
        for (int seat = 0; seat < 3; seat++) {
            if (seat == me.ordinal()) continue;
            for (Card card : world.hands().get(seat)) log += Math.log(Math.max(1e-12, w.weight(card, seat)));
        }
        if (!knowsSkat) for (Card card : world.skat()) log += Math.log(Math.max(1e-12, w.weight(card, 3)));
        return log;
    }
}
