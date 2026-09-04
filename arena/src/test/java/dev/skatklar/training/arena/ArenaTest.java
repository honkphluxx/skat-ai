package dev.skatklar.training.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.search.Personality;
import dev.skatklar.demo.search.SearchAiProvider;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.ai.LegalRandomAiProvider;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.demo.ai.SkatAiSession;
import dev.skatklar.training.players.BeliefOracleProvider;
import dev.skatklar.demo.ai.GreedyAiProvider;
import dev.skatklar.training.players.SolverAiProvider;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class ArenaTest {

    private static final Contestant GREEDY = new Contestant() {
        @Override public String id() { return "greedy"; }
        @Override public SkatAiProvider newProvider(long seed) { return new GreedyAiProvider(); }
    };

    private static final Contestant SOLVER = new Contestant() {
        @Override public String id() { return "solver"; }
        @Override public SkatAiProvider newProvider(long seed) { return new SolverAiProvider(); }
    };

    private static final Contestant RANDOM = new Contestant() {
        @Override public String id() { return "random"; }
        @Override public SkatAiProvider newProvider(long seed) {
            return new LegalRandomAiProvider(new Random(seed));
        }
    };

    /**
     * The arena's own correctness test. Two identical deterministic players must
     * score identically on every board, because duplicate rotation gives each of
     * them the same cards in the same seats. Any non-zero difference means the
     * pairing is broken and every measurement taken with it is meaningless.
     */
    @Test public void identicalDeterministicPlayersScoreExactlyEqual() {
        MatchResult result = DuplicateMatch.run(GREEDY, GREEDY, 40, 12345L);
        for (double diff : result.perBoardDiff()) {
            assertEquals("duplicate rotation must be perfectly symmetric", 0.0, diff, 0.0);
        }
        assertEquals(0.0, result.meanDiffPerGame(), 0.0);
        assertEquals(result.a().tournamentPoints(), result.b().tournamentPoints());
        assertFalse("a zero difference can never be significant", result.significant());
    }

    /**
     * The same check, with a player that <em>uses</em> its seed.
     *
     * <p>The test above has guarded this arena from the start and could not
     * catch the bug that got past it, because {@code greedy} is deterministic
     * and ignores the seed it is handed. A sampling player does not, and the two
     * halves of a board used to be seeded differently — so a search player
     * scored against a copy of itself came out 2.4 game points apart, and on a
     * long match that noise was reported as a resolved difference. It "resolved"
     * αµ at depth one, which is provably the same player as the one it was being
     * compared with, as three points weaker.
     *
     * <p>So this is the version of the zero check that has teeth: a seeded,
     * sampling player against itself, which is only exactly zero when both sides
     * draw from the same stream.
     */
    @Test public void aSamplingPlayerScoresExactlyZeroAgainstItself() {
        Contestant search = PlayerRegistry.withDefaults().resolve("search-4");
        MatchResult result = DuplicateMatch.runFixedContract(search, search, 12, 11L, 1,
                new AuctionContractSource(GREEDY, 11L), line -> {});
        for (double diff : result.perBoardDiff()) {
            assertEquals("a player must score exactly like itself", 0.0, diff, 0.0);
        }
        assertEquals(0.0, result.meanDiffPerGame(), 0.0);
        assertFalse(result.significant());
    }

    @Test public void matchesAreReproducibleFromTheSeed() {
        MatchResult first = DuplicateMatch.run(GREEDY, RANDOM, 25, 777L);
        MatchResult second = DuplicateMatch.run(GREEDY, RANDOM, 25, 777L);
        assertEquals(first.meanDiffPerGame(), second.meanDiffPerGame(), 0.0);
        assertEquals(first.a().tournamentPoints(), second.a().tournamentPoints());
        assertEquals(first.b().tournamentPoints(), second.b().tournamentPoints());
    }

    @Test public void parallelBoardsMatchSerialBoards() {
        MatchResult serial = DuplicateMatch.run(GREEDY, RANDOM, 30, 99L, 1, line -> {});
        MatchResult parallel = DuplicateMatch.run(GREEDY, RANDOM, 30, 99L, 4, line -> {});
        assertEquals(serial.meanDiffPerGame(), parallel.meanDiffPerGame(), 0.0);
    }

    @Test public void greedyBeatsRandomDecisively() {
        MatchResult result = DuplicateMatch.run(GREEDY, RANDOM, 120, 2024L);
        assertTrue("a heuristic player must beat random play", result.meanDiffPerGame() > 0);
        assertTrue("and the gap must resolve on this few boards", result.significant());
    }

    @Test public void engineReportsNoRuleViolations() {
        MatchResult result = DuplicateMatch.run(GREEDY, RANDOM, 40, 5L);
        assertEquals("a contestant that trips the engine's legality guard is broken",
                0, result.a().ruleViolations());
        assertEquals(0, result.b().ruleViolations());
    }

    @Test public void everyGameProducesAConsistentResult() {
        int played = 0;
        for (int index = 0; index < 60; index++) {
            Board board = Board.of(31337L, index);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, new GreedyAiProvider());
            GameOutcome outcome = GameRunner.play(board, seating, index);
            if (outcome.ramsch()) {
                // A Ramsch is a played deal with no declarer and no contract
                // value, so none of the assertions below apply to it.
                assertNull(outcome.declarer());
                assertNotNull(outcome.scoredSeat());
                continue;
            }
            played++;
            assertNotNull(outcome.declarer());
            assertNotNull(outcome.contract());
            assertTrue("card points stay inside the 120-point pack",
                    outcome.declarerPoints() >= 0 && outcome.declarerPoints() <= 120);
            assertEquals("winning is 61 card points and a contract that covers the bid",
                    outcome.declarerPoints() >= 61 && !outcome.overbid(), outcome.declarerWon());
            assertEquals("a lost game is entered doubled and negative",
                    outcome.declarerWon(), outcome.gameValue() > 0);
            assertTrue("the charged value is unsigned and positive",
                    outcome.chargedValue() > 0);
            assertTrue("a settled game always covers the bid",
                    outcome.chargedValue() >= outcome.bidValue());
        }
        assertTrue("this seed must produce played games, not only pass-ins", played > 0);
    }

    /**
     * Fixing the contract must make the two sides structurally identical: each
     * declares exactly one third of the games, at exactly the same contracts,
     * and nothing passes in. If the contract were not truly fixed, the mix would
     * differ and every card-play conclusion drawn from the mode would be void.
     */
    /**
     * The hook that makes the par baseline possible at all. A reference opponent
     * only sees the table because {@code GameRunner} hands it the engine; forget
     * that call and the solver silently degrades to its blind delegate, and every
     * ceiling measured with it is wrong by exactly the amount that matters.
     */
    @Test public void theRunnerShowsTheTableToObservers() {
        RecordingObserver observer = new RecordingObserver();
        Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, observer);
        Board board = Board.of(2L, 0);
        GameRunner.playFixed(board, seating,
                new ContractSource.FixedContract(SkatAi.Seat.HUMAN, Contract.CLUBS, 0), 1L);
        assertNotNull("a TableObserver must be given the engine before play", observer.seen);
    }

    /**
     * What the par baseline claims to be: with the solver in all three seats, the
     * declarer must end in the same scoring band the double-dummy value of the
     * position predicted before a card was played.
     *
     * <p>Bands rather than exact points, because the player searches for the
     * result and not for the last point -- 64 and 74 are the same won game, and
     * the search is an order of magnitude cheaper for not distinguishing them.
     * What would be a bug is landing in a different band from the one the deal
     * was worth.
     */
    @Test public void solverPlayLandsInTheDoubleDummyBand() {
        for (int index = 0; index < 4; index++) {
            Board board = Board.of(11L, index);
            ContractSource.FixedContract fixed =
                    new AuctionContractSource(GREEDY, 11L).contractFor(board);
            if (fixed == null) continue;

            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) seating.put(seat, new SolverAiProvider());
            GameEngine engine = GameEngine.headless(new Random(1), SeatedAiProviders.of(seating));
            for (SkatAiProvider provider : seating.values()) {
                ((TableObserver) provider).observe(engine);
            }
            engine.restartWithContract(board.deal(), board.round(), fixed.declarer(),
                    fixed.contract(), fixed.bidValue(), Set.of());

            GameEngine.Snapshot start = engine.snapshot();
            // The solver values the play alone; the score sheet counts the skat
            // with it, and 61 is a threshold on the total. Comparing bands on the
            // play-only number asks a question nobody is scored on -- and gets a
            // different answer, since a 22-point skat moves a game across the line.
            int predicted = DoubleDummySolver.solve(fixed.contract(), fixed.declarer(),
                    start.hands, SkatAi.Seat.values()[start.leader]).declarerPoints()
                    + SkatRules.cardPoints(start.skat);

            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot snapshot = engine.snapshot();
                if (snapshot.gameComplete()) break;
                if (snapshot.trickComplete()) engine.finishCompletedTrick();
                else engine.playAiCard();
            }
            int played = engine.snapshot().result.declarerPoints;
            engine.close();

            assertEquals("board " + index + ": double-dummy " + predicted + ", played " + played,
                    band(predicted), band(played));
        }
    }

    /** Lost, won, Schneider: what a Skat game is actually scored on. */
    private static int band(int declarerPoints) {
        if (declarerPoints >= 90) return 3;
        if (declarerPoints >= 61) return 2;
        if (declarerPoints >= 31) return 1;
        return 0;
    }

    private static final class RecordingObserver implements SkatAiProvider, TableObserver {
        private final SkatAiProvider blind = new GreedyAiProvider();
        private GameEngine seen;

        @Override public SkatAi.AiDescriptor descriptor() { return blind.descriptor(); }
        @Override public SkatAiSession createSession() { return blind.createSession(); }
        @Override public void observe(GameEngine engine) { this.seen = engine; }
    }

    /**
     * The honest search player, end to end through the engine. It has far more
     * ways to go wrong than the solver -- it reconstructs the position from its
     * own observations rather than reading it out of the engine -- and the engine
     * is the only judge of whether the card it names was legal at all.
     */
    @Test public void theSearchPlayerPlaysLegalCardsAndBeatsTheHeuristic() {
        Contestant search = new Contestant() {
            @Override public String id() { return "search-4"; }
            @Override public SkatAiProvider newProvider(long seed) {
                return new SearchAiProvider(new GreedyAiProvider(), 4, seed);
            }
        };
        MatchResult result = DuplicateMatch.runFixedContract(search, GREEDY, 3, 8L, 1,
                new AuctionContractSource(GREEDY, 8L), line -> {});
        assertEquals("a search player that names an illegal card is broken",
                0, result.a().ruleViolations());
        assertEquals(0, result.b().ruleViolations());
        assertTrue("every board must produce scored games", result.boards() > 0);
        // Deliberately no assertion about who won. Three boards cannot resolve a
        // difference between two competent players, and a strength test that
        // fails on noise teaches everyone to ignore the suite. Strength belongs
        // in a match, and the README carries the measured numbers.
    }

    /**
     * The belief sweep's right-hand end, which is what makes the middle of the
     * curve readable. With every sampled world replaced by the true one, the
     * search is no longer guessing, so it must land in the same scoring band the
     * double-dummy value predicted -- exactly as the cheating solver does. If the
     * truth is not reaching the sampler, this is where it shows.
     */
    @Test public void aPerfectBeliefPlaysLikePerfectInformation() {
        for (int index = 0; index < 3; index++) {
            Board board = Board.of(11L, index);
            ContractSource.FixedContract fixed =
                    new AuctionContractSource(GREEDY, 11L).contractFor(board);
            if (fixed == null) continue;

            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, new BeliefOracleProvider(1.0, Personality.REFERENCE, 4L));
            }
            GameEngine engine = GameEngine.headless(new Random(1), SeatedAiProviders.of(seating));
            for (SkatAiProvider provider : seating.values()) {
                ((TableObserver) provider).observe(engine);
            }
            engine.restartWithContract(board.deal(), board.round(), fixed.declarer(),
                    fixed.contract(), fixed.bidValue(), Set.of());

            GameEngine.Snapshot start = engine.snapshot();
            // The solver values the play alone; the score sheet counts the skat
            // with it, and 61 is a threshold on the total. Comparing bands on the
            // play-only number asks a question nobody is scored on -- and gets a
            // different answer, since a 22-point skat moves a game across the line.
            int predicted = DoubleDummySolver.solve(fixed.contract(), fixed.declarer(),
                    start.hands, SkatAi.Seat.values()[start.leader]).declarerPoints()
                    + SkatRules.cardPoints(start.skat);

            for (int step = 0; step < 128; step++) {
                GameEngine.Snapshot snapshot = engine.snapshot();
                if (snapshot.gameComplete()) break;
                if (snapshot.trickComplete()) engine.finishCompletedTrick();
                else engine.playAiCard();
            }
            int played = engine.snapshot().result.declarerPoints;
            engine.close();

            // Won or lost, not the finer bands. This player votes on one
            // threshold -- can the declarer still reach 61 -- so that is the only
            // thing a perfect belief pins. Once the game is decided its defenders
            // stop paying for points, and the declarer can end above the
            // double-dummy value because the defence has nothing left to defend.
            // The solver player walks 90 and 31 as well and is held to all three.
            assertEquals("board " + index + ": double-dummy " + predicted + ", played " + played,
                    predicted >= 61, played >= 61);
        }
    }

    /**
     * The oracle must price boards, and price them identically every time: a
     * contract source that wanders makes two runs of the same seed incomparable.
     */
    @Test public void theOracleIsDeterministicAndPricesMostBoards() {
        SolverContractSource oracle = new SolverContractSource();
        int priced = 0;
        for (int index = 0; index < 8; index++) {
            Board board = Board.of(3L, index);
            ContractSource.FixedContract first = oracle.contractFor(board);
            ContractSource.FixedContract second = oracle.contractFor(board);
            assertEquals("the same deal must yield the same contract", first, second);
            if (first != null) {
                priced++;
                assertEquals("an assigned contract carries no bid", 0, first.bidValue());
            }
        }
        assertTrue("most deals contain a game somewhere, was " + priced + " of 8", priced >= 4);
    }

    @Test public void fixedContractGivesBothSidesTheSameContracts() {
        MatchResult result = DuplicateMatch.runFixedContract(
                GREEDY, RANDOM, 80, 4711L, 1, new AuctionContractSource(GREEDY, 4711L), l -> {});

        assertTrue("the source must price most boards", result.boards() > 40);
        assertEquals("each side declares exactly one seat per board",
                result.a().declared(), result.b().declared());
        assertEquals(result.boards(), result.a().declared());
        assertEquals("a fixed contract is never a Ramsch", 0, result.a().ramschGames());
        assertEquals(0, result.b().ramschGames());
        assertEquals("the contract mix must be identical, not merely similar",
                result.a().declaredContracts(), result.b().declaredContracts());
    }

    @Test public void fixedContractKeepsTheDuplicateSymmetry() {
        MatchResult result = DuplicateMatch.runFixedContract(
                GREEDY, GREEDY, 40, 31L, 1, new AuctionContractSource(GREEDY, 31L), l -> {});
        for (double diff : result.perBoardDiff()) {
            assertEquals("fixing the contract must not break the pairing", 0.0, diff, 0.0);
        }
        assertEquals(result.boards(), result.boardsWithoutSignal());
    }

    @Test public void fixedContractIsReproducible() {
        MatchResult first = DuplicateMatch.runFixedContract(
                GREEDY, RANDOM, 30, 8L, 1, new AuctionContractSource(GREEDY, 8L), l -> {});
        MatchResult second = DuplicateMatch.runFixedContract(
                GREEDY, RANDOM, 30, 8L, 1, new AuctionContractSource(GREEDY, 8L), l -> {});
        assertEquals(first.meanDiffPerGame(), second.meanDiffPerGame(), 0.0);
        assertEquals(first.boards(), second.boards());
        assertEquals(first.skippedBoards(), second.skippedBoards());
    }

    /**
     * The overbid rule, end to end through the engine. A declarer who bid beyond
     * the value of the contract it announced must lose even with 61 or more card
     * points, and must be charged at a value that reaches the bid.
     *
     * <p>Driven by a deliberately reckless test player, because the shipped
     * contestants now avoid overbidding and would exercise nothing.
     */
    @Test public void overbidDeclarersLoseDespiteCardPoints() {
        int overbid = 0;
        int overbidWithCardPoints = 0;
        for (int index = 0; index < 200; index++) {
            Board board = Board.of(4242L, index);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, seat == SkatAi.Seat.HUMAN
                        ? new RecklessBidderProvider()
                        : new LegalRandomAiProvider(new Random(index * 3L + seat.ordinal())));
            }
            GameOutcome outcome = GameRunner.play(board, seating, index);
            if (outcome.ramsch() || !outcome.overbid()) continue;
            overbid++;
            assertFalse("an overbid declarer never wins", outcome.declarerWon());
            assertTrue("the charge must reach the bid",
                    outcome.chargedValue() >= outcome.bidValue());
            assertEquals("the charge is a multiple of the announced contract's base value",
                    0, outcome.chargedValue() % SkatRules.baseValue(outcome.contract()));
            assertTrue("and is doubled like any lost game", outcome.gameValue() < 0);
            if (outcome.declarerPoints() >= 61) overbidWithCardPoints++;
        }
        assertTrue("the reckless bidder must actually produce overbid games", overbid > 0);
        assertTrue("including games lost with 61 or more card points", overbidWithCardPoints > 0);
    }

    /**
     * Violations must land on the seat that caused them. A table-wide counter
     * would blame a clean contestant for whatever its opponents did, which is
     * exactly backwards when the point is to compare two implementations.
     */
    @Test public void ruleViolationsAreAttributedToTheCausingSeat() {
        int blamed = 0;
        Map<dev.skatklar.demo.GameEngine.ViolationPhase, Integer> phases = new EnumMap<>(
                dev.skatklar.demo.GameEngine.ViolationPhase.class);
        for (int index = 0; index < 20; index++) {
            Board board = Board.of(9001L, index);
            Map<SkatAi.Seat, SkatAiProvider> seating = new EnumMap<>(SkatAi.Seat.class);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                seating.put(seat, seat == SkatAi.Seat.OPPONENT_ONE
                        ? new HostileProvider() : new GreedyAiProvider());
            }
            GameOutcome outcome = GameRunner.play(board, seating, index);
            blamed += outcome.ruleViolationsOf(SkatAi.Seat.OPPONENT_ONE);
            for (dev.skatklar.demo.GameEngine.RuleViolation violation : outcome.violationDetail()) {
                phases.merge(violation.phase(), 1, Integer::sum);
            }
            assertEquals("a clean seat must stay clean",
                    0, outcome.ruleViolationsOf(SkatAi.Seat.HUMAN));
            assertEquals(0, outcome.ruleViolationsOf(SkatAi.Seat.OPPONENT_TWO));
        }
        assertTrue("the hostile seat must be blamed", blamed > 0);
        assertTrue("and the decision must be named, not just counted",
                phases.containsKey(dev.skatklar.demo.GameEngine.ViolationPhase.BID)
                        || phases.containsKey(dev.skatklar.demo.GameEngine.ViolationPhase.PLAY));
    }

    /** Throws from every decision the engine guards. */
    private static final class HostileProvider implements SkatAiProvider {
        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("hostile", "Hostile", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public int bid(SkatAi.BidRequest request) {
                    throw new IllegalStateException("no");
                }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    throw new IllegalStateException("no");
                }
            };
        }
    }

    /** Bids far beyond its hand and always announces the cheapest contract. */
    private static final class RecklessBidderProvider implements SkatAiProvider {
        @Override public SkatAi.AiDescriptor descriptor() {
            return new SkatAi.AiDescriptor("reckless", "Reckless bidder", false);
        }

        @Override public SkatAiSession createSession() {
            return new SkatAiSession() {
                @Override public int bid(SkatAi.BidRequest request) {
                    return request.requestedBid <= 60 ? request.requestedBid : 0;
                }

                @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                    return new LinkedHashSet<>(context.skat);
                }

                @Override public SkatAi.ContractAnnouncement announceContract(
                        SkatAi.ContractContext context) {
                    return SkatAi.ContractAnnouncement.skatGame(SkatAi.ContractType.DIAMONDS);
                }

                @Override public Card chooseCard(SkatAi.DecisionContext context) {
                    return context.legalCards.iterator().next();
                }
            };
        }
    }

    @Test public void seegerFabianScoresDeclarerAndDefenders() {
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        SkatAi.Seat declarer = round.forehand;
        SkatAi.Seat defender = round.middlehand;

        GameOutcome won = new GameOutcome(round, false, declarer, Contract.CLUBS,
                24, true, 72, 24, false, java.util.Map.of(), java.util.List.of(),
                declarer, false, false);
        assertEquals(24 + 50, Scoring.tournamentPoints(won, declarer));
        assertEquals(0, Scoring.tournamentPoints(won, defender));

        // A lost game already arrives doubled and negative from SkatRules.
        GameOutcome lost = new GameOutcome(round, false, declarer, Contract.CLUBS,
                24, false, 45, -48, false, java.util.Map.of(), java.util.List.of(),
                declarer, false, false);
        assertEquals(-48 - 50, Scoring.tournamentPoints(lost, declarer));
        assertEquals(Scoring.DEFENDER_BONUS, Scoring.tournamentPoints(lost, defender));

        // A Ramsch scores one seat its card points and leaves the others blank.
        GameOutcome ramsch = new GameOutcome(round, true, null, Contract.RAMSCH,
                0, false, 69, -69, false, java.util.Map.of(), java.util.List.of(),
                declarer, false, false);
        assertEquals(-69, Scoring.tournamentPoints(ramsch, declarer));
        assertEquals(0, Scoring.tournamentPoints(ramsch, defender));
    }

    @Test public void registryResolvesBuiltInsAndClassSpecs() {
        PlayerRegistry registry = PlayerRegistry.withDefaults();
        assertTrue(registry.ids().contains("random"));
        assertTrue(registry.ids().contains("greedy"));
        assertNotNull(registry.resolve("greedy").newProvider(1L));
        assertNotNull(registry.resolve(
                "class:dev.skatklar.demo.ai.GreedyAiProvider").newProvider(1L));
    }
}
