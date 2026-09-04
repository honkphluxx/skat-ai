package dev.skatklar.training.arena;

import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;

/**
 * Decides which contract a board is played at when the auction is taken out of
 * the comparison.
 *
 * <p>Fixing the contract makes a match <em>fair</em> regardless of where the
 * contract came from: both contestants play the identical deal, at the identical
 * contract, from all three seats, so a poor contract costs them equally and
 * cancels in the paired difference. What the source affects is <em>external
 * validity</em> — whether the card-play skills being exercised are the ones that
 * matter at contracts a strong player would actually reach.
 *
 * <p>Three sources are worth having, in increasing order of quality:
 *
 * <ol>
 *   <li>{@link AuctionContractSource} — run the normal auction with the best
 *       bidder available. Cheap, offline, and only as good as that bidder. This
 *       is what exists today.</li>
 *   <li><b>ISS replay</b> — take the declarer and contract a real, often strong,
 *       human chose on that exact deal. The published ISS archive contains 9.1
 *       million of them, free and offline. This is the honest answer to "where
 *       does a good contract come from", and it arrives with the Phase 1
 *       pipeline rather than needing anything new.</li>
 *   <li><b>Double-dummy oracle</b> — once the Phase 3 solver exists, compute the
 *       objectively best makeable contract for the deal. Better than any human
 *       source, because it is ground truth rather than opinion.</li>
 * </ol>
 */
public interface ContractSource {

    /** Human-readable description, printed in the match report. */
    String describe();

    /**
     * @return the contract this board should be played at, or {@code null} when
     *         the source cannot supply one (an auction that passed in, a deal
     *         missing from an archive). The board is then skipped and reported.
     */
    FixedContract contractFor(Board board);

    /** A declarer, a contract, and the bid it must cover under the overbid rule. */
    record FixedContract(SkatAi.Seat declarer, Contract contract, int bidValue) {}
}
