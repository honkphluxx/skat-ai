package dev.skatklar.training.arena;

import dev.skatklar.demo.GameEngine;
import dev.skatklar.demo.ai.SeatedAiProviders;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiProvider;
import java.util.Collections;
import java.util.Random;

/**
 * Determines the contract by seating one bidder at all three seats and running
 * the ordinary auction, then discarding everything except the declarer, the
 * announced contract, and the winning bid.
 *
 * <p>Seating a single implementation at every seat is deliberate: an auction
 * between two <em>different</em> bidders would let the more aggressive one pick
 * which boards it declares, and that asymmetry is exactly what fixing the
 * contract is meant to remove.
 *
 * <p>The quality of the resulting contract distribution is bounded by the bidder.
 * Pass the strongest bidder available, and read the contract mix in the report
 * before trusting a card-play conclusion drawn on it. See {@link ContractSource}
 * for the two better sources this seam exists for.
 */
public final class AuctionContractSource implements ContractSource {
    private final Contestant bidder;
    private final long seed;

    public AuctionContractSource(Contestant bidder, long seed) {
        this.bidder = bidder;
        this.seed = seed;
    }

    @Override public String describe() {
        return "auction by " + bidder.id() + " at all three seats";
    }

    @Override public FixedContract contractFor(Board board) {
        SkatAiProvider provider = bidder.newProvider(Seeds.mix(seed, board.index(), 0xB1DL));
        GameEngine engine = GameEngine.headless(
                new Random(Seeds.mix(seed, board.index(), 0xB1DE)),
                SeatedAiProviders.uniform(provider));
        try {
            engine.restartWithDeal(board.deal(), board.round(), Collections.emptySet());
            SkatAi.GameDefinition definition = engine.snapshot().definition;
            // A Ramsch is not a contract anybody can be handed: there is no
            // declarer to seat and no game to replay at six rotations. The board
            // drops out of a fixed-contract comparison, exactly as a pass-in used
            // to -- which is also why this source undercounts how often the
            // bidder passes.
            if (definition.isRamsch()) return null;
            return new FixedContract(definition.declarer, definition.contract, definition.bidValue);
        } finally {
            engine.close();
        }
    }
}
