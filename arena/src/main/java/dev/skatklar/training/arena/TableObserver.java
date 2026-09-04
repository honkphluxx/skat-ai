package dev.skatklar.training.arena;

import dev.skatklar.demo.GameEngine;

/**
 * Implemented by providers that are allowed to see the whole table.
 *
 * <p>There is exactly one legitimate use: a <b>reference opponent</b> that cheats
 * on purpose, to establish the ceiling every honest player is measured against.
 * A player reached through this interface is not a candidate for shipping and its
 * score is not comparable to an honest one.
 *
 * <p>It deliberately lives in the arena rather than in {@code core}, and the
 * shipped {@link dev.skatklar.demo.ai.SkatAiProvider} boundary stays untouched: a
 * player that ships cannot acquire this by accident, because the engine never
 * offers it.
 */
public interface TableObserver {

    /** Called once per game, before the first card, with the engine being played. */
    void observe(GameEngine engine);
}
