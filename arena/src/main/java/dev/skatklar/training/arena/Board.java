package dev.skatklar.training.arena;

import dev.skatklar.demo.SkatDeck;
import dev.skatklar.demo.ai.SkatAi;
import java.util.Random;

/**
 * One reproducible deal plus its rotation. A board is derived purely from the
 * match seed and its index, so the identical 32 cards and the identical dealer
 * can be replayed for every contestant and in every later run.
 */
public record Board(long index, SkatDeck.Deal deal, SkatAi.RoundPosition round) {

    public static Board of(long matchSeed, long index) {
        return new Board(index,
                SkatDeck.deal(new Random(Seeds.mix(matchSeed, index))),
                SkatAi.RoundPosition.at(index));
    }
}
