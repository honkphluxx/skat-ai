// Checks the solver against itself, and times it, without needing a JVM.
//
// Two jobs, and they are different. `check` asserts that alpha-beta with the
// transposition table and all the ordering returns exactly what plain minimax
// returns on the same position -- the one invariant the whole optimisation
// effort has to preserve, and the one that catches a bad bound or a key
// collision immediately. `bench` reports what the search costs on ten-card
// deals, which is the number every optimisation is judged on.
//
// `null-check` and `null-bench` do the same two jobs for the Null search, which
// is a separate engine answering a separate question: a bit rather than a point
// count. Its check has one extra invariant to assert, because its speed comes
// from two switchable things -- the transposition table and the equivalence
// reduction -- and a bit that changes when either is turned off is a bug that a
// comparison against minimax alone can hide behind luck.
//
// Agreement with the *Java* solver is a different question and is not asked
// here; SolverParityTest asks it, across the JNI boundary, where both engines
// can be handed the identical position.

#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <string>
#include <vector>

#include "null_solver.h"
#include "skatsolve.h"

namespace {

/// splitmix64: a deal generator that repeats exactly, run to run and box to box.
struct Random {
    uint64_t state;
    explicit Random(uint64_t seed) : state(seed) {}
    uint64_t next() {
        uint64_t z = (state += 0x9E3779B97F4A7C15ull);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    int below(int bound) { return static_cast<int>(next() % static_cast<uint64_t>(bound)); }
};

/// Deals `cardsEach` to every seat out of a shuffled pack.
void deal(Random& random, int cardsEach, uint32_t hands[3]) {
    int pack[32];
    for (int i = 0; i < 32; i++) pack[i] = i;
    for (int i = 31; i > 0; i--) {
        int j = random.below(i + 1);
        int swap = pack[i];
        pack[i] = pack[j];
        pack[j] = swap;
    }
    hands[0] = hands[1] = hands[2] = 0;
    int at = 0;
    for (int seat = 0; seat < 3; seat++) {
        for (int card = 0; card < cardsEach; card++) {
            hands[seat] |= 1u << pack[at++];
        }
    }
}

const int kContracts[] = {0, 1, 2, 3, 4, 6};  // every contract but Null
const int kContractCount = 6;

int check(int deals) {
    Random random(20260825ull);
    int failures = 0;
    int checked = 0;
    for (int i = 0; i < deals; i++) {
        // Five cards each is where minimax is still affordable and the search
        // already has to get trumping, following and the trick winner right.
        int cardsEach = 2 + random.below(4);
        int contract = kContracts[random.below(kContractCount)];
        int declarer = random.below(3);
        int leader = random.below(3);
        uint32_t hands[3];
        deal(random, cardsEach, hands);

        int32_t exhaustive = 0;
        if (skat_brute(contract, declarer, hands, leader, &exhaustive) != SKAT_OK) continue;

        SkatResult solved{};
        if (skat_solve(contract, declarer, hands, leader, &solved) != SKAT_OK) continue;
        checked++;
        if (solved.declarerPoints != exhaustive) {
            failures++;
            std::printf("MISMATCH deal %d: contract %d declarer %d leader %d cards %d"
                        " -- minimax %d, search %d (hands %08x %08x %08x)\n",
                        i, contract, declarer, leader, cardsEach, exhaustive,
                        solved.declarerPoints, hands[0], hands[1], hands[2]);
            if (failures > 5) return failures;
        }

        // The yes/no question must agree with the value it is asking about, at
        // both edges: the exact value must be reachable and one more must not.
        int reaches = skat_reaches(contract, declarer, hands, leader, leader, 0, 0,
                                   exhaustive);
        int overshoots = skat_reaches(contract, declarer, hands, leader, leader, 0, 0,
                                      exhaustive + 1);
        if (reaches != 1 || overshoots != 0) {
            failures++;
            std::printf("WINDOW deal %d: value %d but reaches=%d reaches+1=%d\n",
                        i, exhaustive, reaches, overshoots);
            if (failures > 5) return failures;
        }

        // And the best card must defend exactly that value.
        SkatResult best{};
        if (skat_best_card(contract, declarer, leader, hands, leader, 0, 0, &best)
                == SKAT_OK) {
            if (best.declarerPoints != exhaustive) {
                failures++;
                std::printf("BESTCARD deal %d: minimax %d, best card defends %d\n",
                            i, exhaustive, best.declarerPoints);
                if (failures > 5) return failures;
            }
        }
    }
    std::printf("checked %d positions against plain minimax, %d failures\n",
                checked, failures);
    return failures;
}

// ---------------------------------------------------------------------- Null

double seconds(std::chrono::steady_clock::time_point from);

/// A hand a Null would actually be declared on.
///
/// A uniformly random ten-card hand loses a Null in the first trick or two, so
/// a benchmark built on one measures a search that never starts. This is what
/// the game does instead: the declarer is dealt twelve and keeps the ten
/// lowest, exactly as Discards.keepBestTen(Contract.NULL, twelve) does, and the
/// other twenty are split between the defenders.
void dealNull(Random& random, uint32_t hands[3], int declarer) {
    int pack[32];
    for (int i = 0; i < 32; i++) pack[i] = i;
    for (int i = 31; i > 0; i--) {
        int j = random.below(i + 1);
        int swap = pack[i];
        pack[i] = pack[j];
        pack[j] = swap;
    }
    int twelve[12];
    for (int i = 0; i < 12; i++) twelve[i] = pack[i];
    // By rank, which in a Null is the strength; the two highest are discarded.
    for (int i = 1; i < 12; i++) {
        int card = twelve[i];
        int rank = card % 8;
        int j = i - 1;
        while (j >= 0 && (twelve[j] % 8) > rank) { twelve[j + 1] = twelve[j]; j--; }
        twelve[j + 1] = card;
    }
    hands[0] = hands[1] = hands[2] = 0;
    for (int i = 0; i < 10; i++) hands[declarer] |= 1u << twelve[i];
    int others[2];
    int at = 0;
    for (int seat = 0; seat < 3; seat++) if (seat != declarer) others[at++] = seat;
    for (int i = 12; i < 22; i++) hands[others[0]] |= 1u << pack[i];
    for (int i = 22; i < 32; i++) hands[others[1]] |= 1u << pack[i];
}

int nullCheck(int deals) {
    Random random(20260918ull);
    int failures = 0;
    int checked = 0;
    int survived = 0;
    for (int i = 0; i < deals; i++) {
        int cardsEach = 2 + random.below(4);
        int declarer = random.below(3);
        int leader = random.below(3);
        uint32_t hands[3];
        deal(random, cardsEach, hands);

        int32_t exhaustive = skat_null_brute(declarer, hands, leader);
        if (exhaustive < 0) continue;
        int32_t fast = skat_null_survives(declarer, hands, leader, leader, 0, 0, nullptr);
        checked++;
        survived += fast;
        if (fast != exhaustive) {
            failures++;
            std::printf("NULL MISMATCH deal %d: declarer %d leader %d cards %d"
                        " -- minimax %d, search %d (hands %08x %08x %08x)\n",
                        i, declarer, leader, cardsEach, exhaustive, fast,
                        hands[0], hands[1], hands[2]);
            if (failures > 5) return failures;
        }

        // The per-card list must be the same question asked one ply down: the
        // declarer survives exactly when some legal card does, the defence
        // holds it exactly when every legal card does.
        int32_t cards[10];
        int32_t verdicts[10];
        int count = skat_null_moves_surviving(declarer, leader, hands, leader, 0, 0,
                                              cards, verdicts);
        if (count <= 0) {
            failures++;
            std::printf("NULL MOVES deal %d: no legal move from a full hand\n", i);
            if (failures > 5) return failures;
            continue;
        }
        bool any = false;
        bool all = true;
        for (int at = 0; at < count; at++) {
            any |= verdicts[at] != 0;
            all &= verdicts[at] != 0;
        }
        bool expected = leader == declarer ? any : all;
        if (expected != (exhaustive != 0)) {
            failures++;
            std::printf("NULL MOVES deal %d: verdicts say %d, minimax says %d\n",
                        i, expected ? 1 : 0, exhaustive);
            if (failures > 5) return failures;
        }

        // The two things the speed comes from, each switched off in turn. A
        // wrong equivalence rule and a colliding key both show up as a bit that
        // moves, and both can hide from a minimax comparison on easy deals.
        for (int ablation = 0; ablation < 2; ablation++) {
            skat::NullSolver plain(declarer, hands);
            if (ablation == 0) plain.setUseEquivalence(false);
            else plain.setUseTranspositions(false);
            if (plain.survives(leader, leader, 0, 0) != (fast != 0)) {
                failures++;
                std::printf("NULL ABLATION deal %d: %s changes the verdict\n",
                            i, ablation == 0 ? "equivalence" : "transpositions");
                if (failures > 5) return failures;
            }
        }
    }
    std::printf("checked %d Null positions against plain minimax, %d failures"
                " (%d declarers survived)\n", checked, failures, survived);
    return failures;
}

int nullBench(int deals) {
    Random random(4711ull);
    std::vector<uint32_t> handsAll;
    std::vector<int> declarers;
    std::vector<int> leaders;

    // Only Nulls the declarer makes. About one hand in seventy of the ones
    // above survives, and those are the ones worth timing: a Null that cannot
    // be made is refuted in a trick or two and never opens the tree, so a
    // benchmark that let them in would be reporting the rejection rate rather
    // than the cost of a decision. Finding them is not counted in what follows.
    int tried = 0;
    while (static_cast<int>(declarers.size()) < deals) {
        int declarer = random.below(3);
        int leader = random.below(3);
        uint32_t hands[3];
        dealNull(random, hands, declarer);
        tried++;
        skat::NullSolver solver(declarer, hands);
        if (!solver.survives(leader, leader, 0, 0)) continue;
        handsAll.push_back(hands[0]);
        handsAll.push_back(hands[1]);
        handsAll.push_back(hands[2]);
        declarers.push_back(declarer);
        leaders.push_back(leader);
    }
    std::printf("kept %d made Nulls out of %d dealt\n", deals, tried);

    auto started = std::chrono::steady_clock::now();
    int64_t nodes = 0;
    for (int i = 0; i < deals; i++) {
        const uint32_t* hands = &handsAll[static_cast<size_t>(i) * 3];
        SkatResult out{};
        skat_null_survives(declarers[i], hands, leaders[i], leaders[i], 0, 0, &out);
        nodes += out.visitedNodes;
    }
    double solveSeconds = seconds(started);

    // The question a determinized player actually asks: a verdict per card, one
    // world at a time. It is the cost that decides whether 128 worlds fit in a
    // turn on a phone.
    started = std::chrono::steady_clock::now();
    int64_t moves = 0;
    for (int i = 0; i < deals; i++) {
        const uint32_t* hands = &handsAll[static_cast<size_t>(i) * 3];
        int32_t cards[10];
        int32_t verdicts[10];
        int count = skat_null_moves_surviving(declarers[i], leaders[i], hands, leaders[i],
                                              0, 0, cards, verdicts);
        moves += count > 0 ? count : 0;
    }
    double movesSeconds = seconds(started);

    std::printf("null survives: %d deals in %.3f s -- %.2f ms/deal, %" PRId64 " nodes\n",
                deals, solveSeconds, solveSeconds * 1000 / deals, nodes);
    std::printf("null per-card: %d deals in %.3f s -- %.2f ms/deal, %" PRId64 " verdicts\n",
                deals, movesSeconds, movesSeconds * 1000 / deals, moves);

    // What the table size is worth. Printed rather than asserted: this is the
    // sweep the default in null_solver.h cites, and a number in a comment that
    // nothing prints is a number nobody checks again. The two ablations are not
    // here -- without the table the same deals take some seconds each, which is
    // a measurement to take deliberately rather than every time.
    for (int bits = 12; bits <= 18; bits += 2) {
        auto from = std::chrono::steady_clock::now();
        int64_t visited = 0;
        for (int i = 0; i < deals; i++) {
            skat::NullSolver solver(declarers[i], &handsAll[static_cast<size_t>(i) * 3]);
            solver.setTableBits(bits);
            solver.survives(leaders[i], leaders[i], 0, 0);
            visited += static_cast<int64_t>(solver.visitedNodes());
        }
        double took = seconds(from);
        std::printf("  %2d-bit table  %8.2f ms/deal  %12" PRId64 " nodes\n",
                    bits, took * 1000 / deals, visited);
    }

    // And what the 64 KB equivalence table buys over computing the same answer.
    // The node counts must match exactly -- they are the same reduction -- so a
    // difference here is a bug rather than a measurement.
    for (int useTable = 1; useTable >= 0; useTable--) {
        auto from = std::chrono::steady_clock::now();
        int64_t visited = 0;
        for (int i = 0; i < deals; i++) {
            skat::NullSolver solver(declarers[i], &handsAll[static_cast<size_t>(i) * 3]);
            solver.setUseEquivTable(useTable != 0);
            solver.survives(leaders[i], leaders[i], 0, 0);
            visited += static_cast<int64_t>(solver.visitedNodes());
        }
        double took = seconds(from);
        std::printf("  equivalence by %-6s %8.2f ms/deal  %12" PRId64 " nodes\n",
                    useTable ? "table" : "loop", took * 1000 / deals, visited);
    }
    return 0;
}

double seconds(std::chrono::steady_clock::time_point from) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - from).count();
}

int bench(int deals) {
    Random random(4711ull);
    std::vector<uint32_t> handsAll;
    std::vector<int> contracts;
    std::vector<int> declarers;
    std::vector<int> leaders;
    for (int i = 0; i < deals; i++) {
        uint32_t hands[3];
        deal(random, 10, hands);
        handsAll.push_back(hands[0]);
        handsAll.push_back(hands[1]);
        handsAll.push_back(hands[2]);
        contracts.push_back(kContracts[random.below(kContractCount)]);
        declarers.push_back(random.below(3));
        leaders.push_back(random.below(3));
    }

    // The question the app and the server actually ask: can the declarer reach
    // 61 from a fresh ten-card deal. Everything else here is a variation on it.
    auto started = std::chrono::steady_clock::now();
    int64_t made = 0;
    for (int i = 0; i < deals; i++) {
        const uint32_t* hands = &handsAll[static_cast<size_t>(i) * 3];
        made += skat_reaches(contracts[i], declarers[i], hands, leaders[i], leaders[i],
                             0, 0, 61);
    }
    double reachSeconds = seconds(started);

    started = std::chrono::steady_clock::now();
    int64_t nodes = 0;
    int64_t points = 0;
    for (int i = 0; i < deals; i++) {
        const uint32_t* hands = &handsAll[static_cast<size_t>(i) * 3];
        SkatResult out{};
        skat_solve(contracts[i], declarers[i], hands, leaders[i], &out);
        nodes += out.visitedNodes;
        points += out.declarerPoints;
    }
    double solveSeconds = seconds(started);

    std::printf("reaches(61): %d deals in %.3f s -- %.2f ms/deal, %" PRId64 " made\n",
                deals, reachSeconds, reachSeconds * 1000 / deals, made);
    std::printf("solve exact: %d deals in %.3f s -- %.2f ms/deal, %" PRId64 " nodes,"
                " mean %.1f points\n",
                deals, solveSeconds, solveSeconds * 1000 / deals, nodes,
                static_cast<double>(points) / deals);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    std::string mode = argc > 1 ? argv[1] : "check";
    int count = argc > 2 ? std::atoi(argv[2]) : (mode == "check" ? 400 : 50);
    std::printf("skatsolve %s\n", skat_version());
    if (mode == "bench") return bench(count);
    if (mode == "null-bench") return nullBench(count);
    if (mode == "null-check") return nullCheck(count) == 0 ? 0 : 1;
    return check(count) == 0 ? 0 : 1;
}
