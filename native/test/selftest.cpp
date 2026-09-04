// Checks the solver against itself, and times it, without needing a JVM.
//
// Two jobs, and they are different. `check` asserts that alpha-beta with the
// transposition table and all the ordering returns exactly what plain minimax
// returns on the same position -- the one invariant the whole optimisation
// effort has to preserve, and the one that catches a bad bound or a key
// collision immediately. `bench` reports what the search costs on ten-card
// deals, which is the number every optimisation is judged on.
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
    return check(count) == 0 ? 0 : 1;
}
