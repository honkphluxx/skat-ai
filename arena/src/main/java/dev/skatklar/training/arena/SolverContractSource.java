package dev.skatklar.training.arena;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.SkatRules;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.search.Discards;
import dev.skatklar.demo.solve.DoubleDummySolver;
import dev.skatklar.demo.solve.NullSolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The objective contract: the most valuable game that actually survives perfect
 * defence on this deal.
 *
 * <p>Every other source is an opinion. An auction reflects one bidder's hand
 * evaluation, and an ISS replay reflects one human's — both of them carrying
 * whatever systematic error that player has, straight into the contract mix the
 * card-play comparison is then measured on. This one asks the deal.
 *
 * <p>For each of the three seats and each of the five contracts the demo plays,
 * it asks the solver a single yes/no question: can this declarer still take 61
 * card points against best defence? Among the contracts that pass it announces
 * the one with the highest game value, which is what a perfect bidder would
 * reach for. Grand therefore appears whenever it holds up, and the report's
 * contract mix is worth reading — a skew towards Grand here is a property of the
 * truth rather than a bug in the source.
 *
 * <p>Three limits, stated because they bound what the resulting mix means:
 *
 * <ul>
 *   <li>It prices the <b>hand game</b>: the declarer's dealt ten cards, with the
 *       skat's points credited as they are at settlement. The engine then lets
 *       the declarer pick up and discard, which ordinarily improves the hand. So
 *       this is a conservative filter — a contract it rejects may still be
 *       makeable after a good exchange.</li>
 *   <li>Perfect defence is stronger than any real defence, and both defenders
 *       act as one. Fewer contracts pass than a table would sustain, and the mix
 *       is biased towards hands that are sound rather than merely promising.</li>
 *   <li>The bid is set to zero, so the overbid rule cannot decide a board. There
 *       was no auction; inventing a bid would price the declarer's discard, which
 *       is a hand-evaluation skill this instrument deliberately does not
 *       measure.</li>
 * </ul>
 *
 * <p>Cost is about fifteen null-window solves per board, which is roughly a
 * quarter of a second — several times what the auction source costs, and nothing
 * against a match with any ONNX player in it.
 */
public final class SolverContractSource implements ContractSource {

    /** Card points the declarer must reach, counting the skat. */
    private static final int TARGET = 61;

    @Override public String describe() {
        return "double-dummy oracle: best contract that makes against perfect defence";
    }

    @Override public FixedContract contractFor(Board board) {
        List<List<Card>> dealt = List.of(
                new ArrayList<>(board.deal().human),
                new ArrayList<>(board.deal().opponentOne),
                new ArrayList<>(board.deal().opponentTwo));
        SkatAi.Seat leader = board.round().forehand;

        // Ask the most valuable candidate first and stop at the first one that
        // makes: it is by construction the answer, and every solve saved is
        // roughly a tenth of a second. Matadors are counted from all twelve
        // cards, which is what the score sheet does whether or not the skat was
        // picked up.
        List<Candidate> candidates = new ArrayList<>(15);
        for (SkatAi.Seat declarer : SkatAi.Seat.values()) {
            List<Card> twelve = new ArrayList<>(dealt.get(declarer.ordinal()));
            twelve.addAll(board.deal().skat);
            for (Contract contract : Contract.DECLARED_GAMES) {
                candidates.add(new Candidate(declarer, contract,
                        SkatRules.guaranteedValue(contract, twelve), twelve));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::value).reversed()
                .thenComparingInt(candidate -> candidate.declarer().ordinal())
                .thenComparingInt(candidate -> candidate.contract().ordinal()));

        for (Candidate candidate : candidates) {
            if (makes(candidate.contract(), candidate.declarer(), dealt,
                    candidate.twelve(), leader)) {
                return new FixedContract(candidate.declarer(), candidate.contract(), 0);
            }
        }
        // No seat has a game against perfect defence. That is a genuine Ramsch
        // board rather than a failure of the source, and since a Ramsch cannot
        // be handed to a declarer the match reports it as skipped.
        return null;
    }

    private record Candidate(SkatAi.Seat declarer, Contract contract, int value,
                             List<Card> twelve) {}

    /** Whether the declarer reaches 61 after the plausible discard, against best defence. */
    private static boolean makes(Contract contract, SkatAi.Seat declarer,
                                 List<List<Card>> dealt, List<Card> twelve,
                                 SkatAi.Seat leader) {
        // The same heuristic the hand evaluator uses, so the oracle prices a
        // board the way a player will actually hold it -- and so Null gets the
        // inverted discard it needs rather than a trump game's.
        List<Card> keep = Discards.keepBestTen(contract, twelve);
        int discarded = SkatRules.cardPoints(Discards.buried(contract, twelve));

        List<List<Card>> hands = new ArrayList<>(3);
        for (SkatAi.Seat seat : SkatAi.Seat.values()) {
            hands.add(seat == declarer ? keep : dealt.get(seat.ordinal()));
        }
        // Null is a different contract, not a cheaper one: no points are counted
        // and the declarer must take no trick at all.
        if (contract.isNull()) {
            return NullSolver.declarerSurvives(declarer, hands, leader);
        }
        return DoubleDummySolver.declarerReaches(contract, declarer, hands, leader,
                TARGET - discarded);
    }

}
