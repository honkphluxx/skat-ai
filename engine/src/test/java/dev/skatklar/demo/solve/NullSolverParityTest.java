package dev.skatklar.demo.solve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.ai.SkatAi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The two Null searches, asked the same questions, inside one JVM.
 *
 * <p>{@link SolverParityTest} does this for the points search and explains why;
 * this is the same argument for the other engine. What differs is how much
 * latitude there is, and the answer is none. The points solver may name a
 * different card among cards that defend the same value, because its caller
 * wants a card to play. This one's caller wants a verdict <em>per card</em> —
 * it is counting votes across sampled worlds — so a list that is right about the
 * position and wrong about which card is which would poison every vote taken
 * from it. Both the order and the pairing are asserted.
 *
 * <p>The positions are small on purpose. Five cards a seat is where plain
 * minimax is still affordable, and by then the search has already had to get
 * following, the trick winner, the equivalence reduction and the transposition
 * table right — a bug in any of them shows up here rather than in an arena
 * result three days later.
 *
 * <p>Skips when the library is not loadable, which on a developer's workstation
 * it usually is not. A skipped run proved nothing, which is the honest report.
 */
public final class NullSolverParityTest {

    private boolean originalSetting;

    @Before public void requireNative() {
        Assume.assumeTrue("no native solver on this platform; nothing to compare",
                NativeSolver.available());
        originalSetting = NullSolver.nativeEnabled;
    }

    @After public void restoreSetting() {
        NullSolver.nativeEnabled = originalSetting;
    }

    private record Position(SkatAi.Seat declarer, SkatAi.Seat leader, List<List<Card>> hands) {}

    private static Position position(Random random, int cardsEach) {
        List<Card> pack = new ArrayList<>(32);
        for (int index = 0; index < 32; index++) pack.add(ContractTables.card(index));
        Collections.shuffle(pack, random);
        List<List<Card>> hands = new ArrayList<>(3);
        int at = 0;
        for (int seat = 0; seat < 3; seat++) {
            hands.add(new ArrayList<>(pack.subList(at, at + cardsEach)));
            at += cardsEach;
        }
        return new Position(SkatAi.Seat.values()[random.nextInt(3)],
                SkatAi.Seat.values()[random.nextInt(3)], hands);
    }

    @Test public void survivalAgrees() {
        Random random = new Random(20260918L);
        for (int deal = 0; deal < 300; deal++) {
            Position position = position(random, 2 + random.nextInt(4));

            NullSolver.nativeEnabled = true;
            boolean nativeAnswer =
                    NullSolver.declarerSurvives(position.declarer, position.hands, position.leader);
            NullSolver.nativeEnabled = false;
            boolean javaAnswer =
                    NullSolver.declarerSurvives(position.declarer, position.hands, position.leader);

            assertEquals("deal " + deal + " " + position, javaAnswer, nativeAnswer);
            // And both against the reference, so that two engines agreeing on
            // the same mistake is not mistaken for the two engines agreeing.
            assertEquals("deal " + deal + " " + position,
                    NullSolver.survivesExhaustively(position.declarer, position.hands,
                            position.leader),
                    nativeAnswer);
        }
    }

    @Test public void everyCardGetsTheSameVerdict() {
        Random random = new Random(4711L);
        for (int deal = 0; deal < 300; deal++) {
            Position position = position(random, 2 + random.nextInt(4));
            SkatAi.Seat toPlay = position.leader;

            NullSolver.nativeEnabled = true;
            List<NullSolver.Verdict> nativeVerdicts = NullSolver.movesSurviving(
                    position.declarer, toPlay, position.hands, position.leader, List.of());
            NullSolver.nativeEnabled = false;
            List<NullSolver.Verdict> javaVerdicts = NullSolver.movesSurviving(
                    position.declarer, toPlay, position.hands, position.leader, List.of());

            assertEquals("deal " + deal + ": move counts differ",
                    javaVerdicts.size(), nativeVerdicts.size());
            assertFalse("deal " + deal + ": no legal move", javaVerdicts.isEmpty());
            for (int at = 0; at < javaVerdicts.size(); at++) {
                assertEquals("deal " + deal + " slot " + at + ": different card",
                        javaVerdicts.get(at).card(), nativeVerdicts.get(at).card());
                assertEquals("deal " + deal + " card " + javaVerdicts.get(at).card(),
                        javaVerdicts.get(at).declarerSurvives(),
                        nativeVerdicts.get(at).declarerSurvives());
            }
        }
    }

    /**
     * The verdict list and the position agree with each other.
     *
     * <p>Not a restatement of the two tests above: they pin the native list to
     * the Java list, and this pins the list to the <em>question</em>. The
     * declarer survives exactly when some legal card does; the defence holds the
     * declarer exactly when every legal card does. A pair of engines that had
     * both drifted one ply would pass the others and fail this.
     */
    @Test public void theListAnswersTheSameQuestionAsThePosition() {
        Random random = new Random(1848L);
        for (int deal = 0; deal < 300; deal++) {
            Position position = position(random, 2 + random.nextInt(4));
            List<NullSolver.Verdict> verdicts = NullSolver.movesSurviving(
                    position.declarer, position.leader, position.hands, position.leader,
                    List.of());
            boolean any = false;
            boolean all = true;
            for (NullSolver.Verdict verdict : verdicts) {
                any |= verdict.declarerSurvives();
                all &= verdict.declarerSurvives();
            }
            boolean fromTheList = position.leader == position.declarer ? any : all;
            assertEquals("deal " + deal + " " + position,
                    NullSolver.survivesExhaustively(position.declarer, position.hands,
                            position.leader),
                    fromTheList);
        }
    }
}
