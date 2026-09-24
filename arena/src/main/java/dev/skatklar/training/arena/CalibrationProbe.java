package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.belief.BeliefEncoding;
import dev.skatklar.demo.search.BeliefWorldSource;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.search.WorldSampler;
import java.util.*;

/**
 * Two nets read on the same decisions of the same fixed-contract games (the
 * shipped player's), by role and by what the card is: a jack, another trump,
 * or plain. For each: the probability the net gives the true place, the
 * argmax hit rate, the mean confidence (max p), and a calibration table of
 * confidence against hit rate. A net that is right more often on average but
 * wrong when sure is what a vote over sampled worlds punishes.
 */
public final class CalibrationProbe {
    static final String[] NAMES = {"shipped", "bids-structure"};
    static final String[] DIRS = {"belief-model", "belief-model-bids-structure"};
    static final String[] KINDS = {"jack", "trump", "plain"};
    static final double[] EDGES = {0.5, 0.65, 0.8, 0.95, 1.01};

    public static void main(String[] a) throws Exception {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        Contestant player = registry.resolve("belief-32-shipped");
        int boards = Integer.parseInt(a[0]);
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 12;
        int maxTrick = a.length > 2 ? Integer.parseInt(a[2]) : 3;
        ContractSource contracts = new AuctionContractSource(registry.resolve("greedy"), seed);
        BeliefWorldSource[] nets = new BeliefWorldSource[2];
        for (int m = 0; m < 2; m++) {
            nets[m] = new BeliefWorldSource(dev.skatklar.training.belief.NetBeliefModel.load(java.nio.file.Path.of(DIRS[m])));
        }
        // [model][role][kind]: count, sum p(true), hits, sum maxp ; calibration [model][role][kind][bin]: n, hits, sum conf
        double[][][][] agg = new double[2][2][3][4];
        double[][][][][] cal = new double[2][2][3][EDGES.length][3];
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
                            if (trick > maxTrick) return;
                            SkatAi.Seat me = c.mySeat;
                            boolean decl = me == c.game.declarer;
                            boolean knowsSkat = decl && !c.game.hand;
                            int role = decl ? 0 : 1;
                            Map<Card, Integer> where = new HashMap<>();
                            for (int seat = 0; seat < 3; seat++) {
                                if (seat == me.ordinal()) continue;
                                for (Card card : truth.hands.get(seat)) where.put(card, seat);
                            }
                            if (!knowsSkat) for (Card card : truth.skat) where.put(card, 3);
                            int left = me.next().ordinal(), right = 3 - me.ordinal() - left;
                            for (int m = 0; m < 2; m++) {
                                double[][] belief = nets[m].belief(e);
                                if (belief == null) return;
                                synchronized (agg) {
                                    for (Map.Entry<Card, Integer> en : where.entrySet()) {
                                        Card card = en.getKey();
                                        int kind = card.rank == Card.Rank.JACK ? 0
                                                : SkatRules.publicFollowClass(c.game.contract, card).equals(SkatAi.FollowClass.trump()) ? 1 : 2;
                                        double[] b = belief[BeliefEncoding.index(card)];
                                        double pl = b[BeliefEncoding.CLASS_LEFT], pr = b[BeliefEncoding.CLASS_RIGHT], ps = knowsSkat ? 0 : b[BeliefEncoding.CLASS_SKAT];
                                        double tot = pl + pr + ps; if (tot <= 0) continue;
                                        pl /= tot; pr /= tot; ps /= tot;
                                        int truthPlace = en.getValue();
                                        double ptrue = truthPlace == left ? pl : truthPlace == right ? pr : ps;
                                        double max = Math.max(pl, Math.max(pr, ps));
                                        int guess = max == pl ? left : max == pr ? right : 3;
                                        double[] g = agg[m][role][kind];
                                        g[0]++; g[1] += ptrue; g[2] += guess == truthPlace ? 1 : 0; g[3] += max;
                                        int bin = 0; while (bin < EDGES.length - 1 && max >= EDGES[bin]) bin++;
                                        double[] cb = cal[m][role][kind][bin];
                                        cb[0]++; cb[1] += guess == truthPlace ? 1 : 0; cb[2] += max;
                                    }
                                }
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
        System.out.println("FIXED mode, seed " + seed + ", " + games[0] + " games, tricks 1-" + maxTrick + ": both nets on the shipped player's decisions");
        for (int role = 0; role < 2; role++) {
            System.out.println(role == 0 ? "as declarer" : "as defender");
            System.out.println(String.format(Locale.ROOT, "  %-8s %-16s %8s %9s %9s %9s", "card", "net", "n", "p(true)", "argmax%", "conf"));
            for (int kind = 0; kind < 3; kind++) for (int m = 0; m < 2; m++) {
                double[] g = agg[m][role][kind];
                System.out.println(String.format(Locale.ROOT, "  %-8s %-16s %8.0f %8.1f%% %8.1f%% %8.1f%%", KINDS[kind], NAMES[m], g[0], 100 * g[1] / g[0], 100 * g[2] / g[0], 100 * g[3] / g[0]));
            }
            System.out.println("  calibration, jacks (confidence bin: n, mean confidence -> hit rate):");
            for (int m = 0; m < 2; m++) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "    %-16s", NAMES[m]));
                double lo = 0;
                for (int bin = 0; bin < EDGES.length; bin++) {
                    double[] cb = cal[m][role][0][bin];
                    line.append(String.format(Locale.ROOT, "  [%.2f,%.2f): %4.0f %5.1f->%5.1f", lo, Math.min(EDGES[bin], 1.0), cb[0], 100 * cb[2] / Math.max(1, cb[0]), 100 * cb[1] / Math.max(1, cb[0])));
                    lo = EDGES[bin];
                }
                System.out.println(line);
            }
            System.out.println("  calibration, all unseen cards:");
            for (int m = 0; m < 2; m++) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "    %-16s", NAMES[m]));
                double lo = 0;
                for (int bin = 0; bin < EDGES.length; bin++) {
                    double n = 0, h = 0, cf = 0;
                    for (int kind = 0; kind < 3; kind++) { n += cal[m][role][kind][bin][0]; h += cal[m][role][kind][bin][1]; cf += cal[m][role][kind][bin][2]; }
                    line.append(String.format(Locale.ROOT, "  [%.2f,%.2f): %5.0f %5.1f->%5.1f", lo, Math.min(EDGES[bin], 1.0), n, 100 * cf / Math.max(1, n), 100 * h / Math.max(1, n)));
                    lo = EDGES[bin];
                }
                System.out.println(line);
            }
        }
    }
}
