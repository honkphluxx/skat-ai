package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.search.Discards;
import dev.skatklar.demo.solve.NullSolver;
import java.util.ArrayList;
import java.util.List;

/**
 * Null on every board that can support one: the instrument for Null card play.
 *
 * <p>{@link SolverContractSource} prices the most valuable makeable contract,
 * and on the deals it prices that is Null about three times in a hundred. A
 * 200-board oracle match therefore carries six Null games a side, which is
 * enough to notice a difference and nowhere near enough to measure one -- and
 * Null card play is where the measured gap to SkatZero sits (arena/README.md,
 * 2026-09-17 third: of eighteen oracle Nulls our player wins seven and
 * SkatZero fourteen, while at trump contracts the two are level). This source
 * exists so that question can be asked on a board set that is all Null.
 *
 * <p>For each seat in turn it asks the Null solver whether that seat, after the
 * heuristic discard, can avoid every trick against perfect defence, and hands
 * the contract to the first seat that can. A board no seat can hold is skipped
 * and reported, as with any source. The order of seats is fixed rather than by
 * any measure of how <em>comfortably</em> the Null makes: a Null is makeable or
 * it is not, there is no margin to rank by.
 *
 * <p><b>What this measures and what it does not.</b> It measures play at
 * contracts a perfect declarer holds, which is the same standard the oracle
 * rows use, and it is not a sample of Nulls anybody would meet in a real
 * auction -- our bidders never announce Null at all (docs/training-plan.md,
 * and {@link NullAuditMain} for why). It is an instrument for a weakness, not
 * a picture of the game. The share of boards it can price is printed, because
 * a source that quietly skipped four boards in five would make its matches
 * look shorter than they are rather than wrong.
 */
public final class NullContractSource implements ContractSource {

    @Override public String describe() {
        return "Null wherever a seat can hold one against perfect defence";
    }

    @Override public FixedContract contractFor(Board board) {
        List<List<Card>> dealt = List.of(
                new ArrayList<>(board.deal().human),
                new ArrayList<>(board.deal().opponentOne),
                new ArrayList<>(board.deal().opponentTwo));
        SkatAi.Seat leader = board.round().forehand;
        for (SkatAi.Seat declarer : SkatAi.Seat.values()) {
            List<Card> twelve = new ArrayList<>(dealt.get(declarer.ordinal()));
            twelve.addAll(board.deal().skat);
            // The same heuristic discard SolverContractSource prices with, so
            // that "makeable" means the same thing in both sources -- and so
            // that a declarer holding this contract starts from the ten cards
            // an honest player would keep.
            List<Card> keep = Discards.keepBestTen(Contract.NULL, twelve);
            List<List<Card>> hands = new ArrayList<>(3);
            for (SkatAi.Seat seat : SkatAi.Seat.values()) {
                hands.add(seat == declarer ? keep : dealt.get(seat.ordinal()));
            }
            if (NullSolver.declarerSurvives(declarer, hands, leader)) {
                // No bid: there was no auction, and a bid would price the
                // discard, which is a hand-evaluation skill this does not
                // measure. The same choice SolverContractSource makes.
                return new FixedContract(declarer, Contract.NULL, 0);
            }
        }
        return null;
    }
}
