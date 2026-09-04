package dev.skatklar.training.arena;

import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.Collections;
import java.util.Map;
import java.util.Random;

/**
 * Drives one headless game to completion through the shipped {@link GameEngine}.
 *
 * <p>Using the production engine rather than an arena-local game loop is the
 * whole point: a measurement taken under different rules than the app plays is
 * worthless. Rules, auction, legality checks and game value all come from
 * {@code core}.
 */
public final class GameRunner {
    /** 30 card plays plus 10 trick settlements, with slack for a hung engine. */
    private static final int MAX_STEPS = 128;

    private GameRunner() {}

    public static GameOutcome play(Board board, Map<SkatAi.Seat, SkatAiProvider> seating,
                                   long engineSeed) {
        GameEngine engine = GameEngine.headless(new Random(engineSeed),
                SeatedAiProviders.of(seating));
        showTableTo(seating, engine);
        try {
            // Always starts something now: an auction where everybody passes
            // produces a Ramsch, so no board is thrown away and none is scored
            // as a non-event.
            engine.restartWithDeal(board.deal(), board.round(), Collections.emptySet());
            return finish(engine, board);
        } finally {
            engine.close();
        }
    }

    /**
     * Plays the board at a predetermined contract, skipping the auction, so that
     * a comparison of card play is not diluted by differences in bidding.
     */
    public static GameOutcome playFixed(Board board, Map<SkatAi.Seat, SkatAiProvider> seating,
                                        ContractSource.FixedContract fixed, long engineSeed) {
        GameEngine engine = GameEngine.headless(new Random(engineSeed),
                SeatedAiProviders.of(seating));
        showTableTo(seating, engine);
        try {
            engine.restartWithContract(board.deal(), board.round(), fixed.declarer(),
                    fixed.contract(), fixed.bidValue(), Collections.emptySet());
            return finish(engine, board);
        } finally {
            engine.close();
        }
    }

    /**
     * Hands the engine to any provider that is entitled to see every hand.
     *
     * <p>Only a deliberate reference opponent implements {@link TableObserver},
     * and only the arena ever calls this. The shipped provider boundary carries
     * no such capability, so a player that ships cannot acquire it by mistake.
     */
    private static void showTableTo(Map<SkatAi.Seat, SkatAiProvider> seating, GameEngine engine) {
        for (SkatAiProvider provider : seating.values()) {
            if (provider instanceof TableObserver observer) observer.observe(engine);
        }
    }

    private static GameOutcome finish(GameEngine engine, Board board) {
        for (int step = 0; step < MAX_STEPS; step++) {
            GameEngine.Snapshot snapshot = engine.snapshot();
            if (snapshot.gameComplete()) {
                return GameOutcome.of(snapshot.result,
                        engine.ruleViolationsBySeat(), engine.ruleViolations());
            }
            if (snapshot.trickComplete()) {
                engine.finishCompletedTrick();
            } else {
                engine.playAiCard();
            }
        }
        throw new IllegalStateException(
                "Game on board " + board.index() + " did not finish in " + MAX_STEPS + " steps");
    }
}
