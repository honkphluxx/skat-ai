#include "skatsolve.h"

#include <stdlib.h>

#include "null_solver.h"
#include "solver.h"
#include "tables.h"

using skat::NullSolver;
using skat::Solver;
using skat::Tables;

namespace {
inline int32_t larger(int32_t a, int32_t b) { return a > b ? a : b; }
}  // namespace

namespace {

/// Checks the arguments a position must satisfy before any of them are used.
///
/// Cheap, and the alternative is a segfault inside the JVM's address space
/// rather than an exception a caller can see. A trick of more than two cards,
/// a seat that is not the one to play after them, or a hand mask with a bit
/// outside the pack are all caller bugs, and all of them would otherwise
/// corrupt the search quietly.
bool positionIsSane(int32_t declarerSeat, int32_t toPlay, int32_t leader,
                    int32_t trickSize) {
    if (declarerSeat < 0 || declarerSeat > 2) return false;
    if (toPlay < 0 || toPlay > 2) return false;
    if (leader < 0 || leader > 2) return false;
    if (trickSize < 0 || trickSize > 2) return false;
    return (leader + trickSize) % 3 == toPlay;
}

struct Prepared {
    const Tables* tables;
    bool ok;
};

Prepared prepare(int32_t contract) {
    const Tables* tables = skat::tablesFor(contract);
    return Prepared{tables, tables != nullptr};
}

}  // namespace

struct SkatSolver {
    Solver solver;
    SkatSolver(const Tables* tables, int declarerSeat) : solver(tables, declarerSeat) {}
};

/// Placement new, declared here rather than included from <new>.
///
/// One line, and it saves the library a dependency on libstdc++ for a function
/// that does nothing but return its argument.
inline void* operator new(size_t, void* where) noexcept { return where; }

namespace {

/// How large a table to build before the search rather than growing into it.
///
/// Measured, and re-measured, because the first measurement was taken on the
/// wrong deals. `skatsolve_test bench` used to deal ten uniformly random cards
/// and pick a contract at random, and on those the declarer reaches 61 about
/// once in sixty: the null-window question is then answered by a hopeless hand
/// in microseconds, and the sizes below were tuned against that. The bench now
/// deals twelve to the declarer, lets the hand pick the contract, buries the
/// two worst cards and keeps the deal only if the declarer can actually make
/// it -- which is what a declaration is. Everything costs more on those, and
/// the tuning moves.
///
/// Over forty made declarations, `reaches(61)` from the root:
///
///     12 bits   0.2 MB   217 ms/deal   208e6 nodes
///     14 bits   1.0 MB    98 ms/deal    87e6 nodes     <- what used to ship
///     16 bits   4.0 MB    65 ms/deal    55e6 nodes
///     18 bits    16 MB    66 ms/deal    52e6 nodes
///     20 bits    64 MB   108 ms/deal    52e6 nodes
///     22 bits   256 MB   175 ms/deal    52e6 nodes
///
/// and the exact-value question, which searches several times as much:
///
///     13 bits   0.5 MB   836 ms/deal
///     15 bits   2.0 MB   364 ms/deal                   <- what used to ship
///     17 bits   8.0 MB   205 ms/deal
///     19 bits    32 MB   221 ms/deal
///
/// Both curves have the same shape and it is worth reading carefully, because
/// it answers a question people keep asking of a machine with a lot of memory.
/// The node count stops falling at 18 bits and is flat after it: the table has
/// stopped missing, and there is nothing left for a bigger one to remember. Yet
/// the 256 MB table is the second slowest thing on the list, three times the
/// cost of the 4 MB one while searching the same tree. Past the point where the
/// table fits in cache, every extra megabyte buys nothing and costs a miss.
/// This is not a structure that wants more RAM; it wants to fit in L2.
///
/// The allocation itself is not what decides any of this: building and throwing
/// away a 4 MB table costs 0.145 ms against a 145 ms decision, and 8 MB costs
/// 0.29 against 205. An epoch-stamped table kept alive per thread would save
/// that tenth of a per cent and nothing else, which is why there is not one.
///
/// One more turn of the same handle, and the reason a single number is wrong.
/// The sizes above are right for a decision at the top of a hand, which is the
/// expensive one and the one that decides a deal's total. They are wrong for
/// everything after it. The same sweep at three positions:
///
///     cards a hand      12 bits   14 bits   16 bits   18 bits
///     ten (the root)     98.3      56.7      42.7      45.7   ms
///     eight               3.20      3.05      3.05      3.92  ms
///     six                 0.113     0.125     0.259     0.794 ms
///
/// Read the six-card row against its node count, which is 89945 at every size:
/// the search is identical and the big table is seven times slower, because at
/// that size the whole cost is building a table the search will touch a
/// thousandth of. A hand is one decision at ten cards and nine smaller ones, so
/// a fixed size is either too small where it matters or too large nine times
/// over.
///
/// The three measured optima -- 16 bits at thirty cards, 14 at twenty-four, 12
/// at eighteen -- are a straight line in the cards remaining, and that is what
/// tableBitsFor is. It is not a fit to three points so much as the obvious
/// shape: the tree grows with the cards left, and so should the table.
///
/// Deliberately not applied to the reusable handles AlphaMu holds: there are
/// thirty-two of those alive at once and four megabytes each is not a trade a
/// phone should make. They keep the Java's grow-on-demand behaviour.
constexpr int kWindowedBits = 16;
constexpr int kExactBits = 17;
constexpr int kSmallestBits = 8;

/// How many bits of table a position of this size deserves.
///
/// @param extra one more doubling for the exact-value question, which searches
///              several times the tree a null window does.
int tableBitsFor(const uint32_t hands[3], int extra) {
    int cardsLeft = __builtin_popcount(hands[0]) + __builtin_popcount(hands[1])
            + __builtin_popcount(hands[2]);
    int bits = cardsLeft / 3 + 6 + extra;
    if (bits < kSmallestBits) return kSmallestBits;
    int ceiling = kWindowedBits + extra;
    return bits > ceiling ? ceiling : bits;
}

}  // namespace

extern "C" {

int32_t skat_solve(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                   int32_t leader, SkatResult* out) {
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, leader, leader, 0)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(tableBitsFor(hands, kExactBits - kWindowedBits));
    solver.setHands(hands);
    int value = solver.search(leader, leader, 0, 0, 0, skat::kTotalPoints);
    if (out != nullptr) {
        out->declarerPoints = value;
        out->card = -1;
        out->visitedNodes = static_cast<int64_t>(solver.visitedNodes());
        out->transpositions = solver.storedEntries();
    }
    return SKAT_OK;
}

int32_t skat_reaches(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                     int32_t toPlay, int32_t leader, uint32_t trickCards,
                     int32_t trickSize, int32_t target) {
    if (target <= 0) return 1;
    if (target > skat::kTotalPoints) return 0;
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(tableBitsFor(hands, 0));
    solver.setHands(hands);
    int value = solver.search(toPlay, leader, trickCards, trickSize, target - 1, target);
    return value >= target ? 1 : 0;
}

int32_t skat_reaches_within(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                            int32_t toPlay, int32_t leader, uint32_t trickCards,
                            int32_t trickSize, int32_t target, int64_t budgetNanos) {
    if (target <= 0) return 1;
    if (target > skat::kTotalPoints) return 0;
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(tableBitsFor(hands, 0));
    solver.setHands(hands);
    solver.setBudgetNanos(budgetNanos);
    int value = solver.search(toPlay, leader, trickCards, trickSize, target - 1, target);
    if (solver.expired()) return SKAT_EXPIRED;
    return value >= target ? 1 : 0;
}

int32_t skat_best_card(int32_t contract, int32_t declarerSeat, int32_t toPlay,
                       const uint32_t hands[3], int32_t leader, uint32_t trickCards,
                       int32_t trickSize, SkatResult* out) {
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(tableBitsFor(hands, kExactBits - kWindowedBits));
    solver.setHands(hands);
    Solver::Choice choice = solver.chooseAtRoot(toPlay, leader, trickCards, trickSize);
    if (choice.card < 0) return SKAT_INVALID;
    if (out != nullptr) {
        out->declarerPoints = choice.declarerPoints;
        out->card = choice.card;
        out->visitedNodes = static_cast<int64_t>(solver.visitedNodes());
        out->transpositions = solver.storedEntries();
    }
    return SKAT_OK;
}

int32_t skat_best_card_for_result(int32_t contract, int32_t declarerSeat, int32_t toPlay,
                                  const uint32_t hands[3], int32_t leader,
                                  uint32_t trickCards, int32_t trickSize,
                                  int32_t declarerPointsSoFar, SkatResult* out) {
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(tableBitsFor(hands, 0));
    solver.setHands(hands);

    // Skat scores bands, not points: 61 wins, 90 is Schneider, 31 avoids being
    // Schneidered. The declarer walks down from the best band it might still
    // hold, the defence up from the worst it might not escape, and both stop at
    // the first threshold the position confirms. The declarer does not walk down
    // to 31: Seeger-Fabian charges a lost game a flat 50 whether or not it was
    // Schneider, so playing safe for 31 buys nothing.
    bool declarerToPlay = toPlay == declarerSeat;
    const int32_t declarerBands[] = {90, 61};
    const int32_t defenderBands[] = {31, 61, 90};
    const int32_t* bands = declarerToPlay ? declarerBands : defenderBands;
    int bandCount = declarerToPlay ? 2 : 3;

    Solver::Choice choice{-1, 0};
    for (int i = 0; i < bandCount; i++) {
        // A band already banked cannot be lost, so there is nothing to ask about
        // it; the question degenerates to "is there another point in it", which
        // is one node deep and keeps a legal move coming back.
        int needed = larger(1, bands[i] - declarerPointsSoFar);
        choice = solver.rootChoice(toPlay, leader, trickCards, trickSize, needed - 1, needed);
        bool confirmed = declarerToPlay ? choice.declarerPoints >= needed
                                        : choice.declarerPoints < needed;
        if (confirmed) {
            if (choice.card < 0) return SKAT_INVALID;
            if (out != nullptr) {
                out->declarerPoints = choice.declarerPoints;
                out->card = choice.card;
                out->visitedNodes = static_cast<int64_t>(solver.visitedNodes());
                out->transpositions = solver.storedEntries();
            }
            return SKAT_OK;
        }
    }
    // Every band failed: the declarer cannot reach even 31, or the defence
    // cannot hold 90. The band is settled either way, so fall back to the exact
    // optimum inside it -- points still decide the game value, and the table is
    // warm from the questions just asked.
    choice = solver.chooseAtRoot(toPlay, leader, trickCards, trickSize);
    if (choice.card < 0) return SKAT_INVALID;
    if (out != nullptr) {
        out->declarerPoints = choice.declarerPoints;
        out->card = choice.card;
        out->visitedNodes = static_cast<int64_t>(solver.visitedNodes());
        out->transpositions = solver.storedEntries();
    }
    return SKAT_OK;
}

int32_t skat_moves_reaching(int32_t contract, int32_t declarerSeat, int32_t toPlay,
                            const uint32_t hands[3], int32_t leader, uint32_t trickCards,
                            int32_t trickSize, int32_t target, int32_t* outCards,
                            int32_t* outBounds) {
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(tableBitsFor(hands, 0));
    solver.setHands(hands);
    int alpha = larger(0, target - 1);
    int beta = larger(1, target);
    // Every legal card gets a verdict, because the caller is counting votes
    // per card and a card missing from the list is a card that loses its vote.
    // But cards that are interchangeable have the same verdict by definition,
    // so only one of each run is searched and the rest copy its answer -- which
    // is the same saving the search makes internally, applied to the one place
    // that cannot simply drop the duplicates.
    uint8_t moves[10];
    int count = solver.orderedMoves(toPlay, trickCards, trickSize, moves);
    uint32_t playable = 0;
    for (int i = 0; i < count; i++) playable |= 1u << moves[i];
    uint32_t alive = solver.aliveCards(trickCards, trickSize);
    int32_t valueOfCard[32];
    bool haveValue[32] = {};
    for (int i = 0; i < count; i++) {
        int card = moves[i];
        int representative = prepared.tables->representativeOf(card, playable, alive);
        if (!haveValue[representative]) {
            solver.setHand(toPlay, solver.hand(toPlay) & ~(1u << representative));
            valueOfCard[representative] = solver.childValue(toPlay, leader, trickCards,
                                                            trickSize, representative,
                                                            alpha, beta);
            solver.setHand(toPlay, solver.hand(toPlay) | (1u << representative));
            haveValue[representative] = true;
        }
        outCards[i] = card;
        outBounds[i] = valueOfCard[representative];
    }
    return count;
}

int32_t skat_brute(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                   int32_t leader, int32_t* out) {
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    Solver solver(prepared.tables, declarerSeat);
    solver.setHands(hands);
    int value = solver.brute(leader, leader, 0, 0);
    if (out != nullptr) *out = value;
    return SKAT_OK;
}

SkatSolver* skat_solver_create(int32_t contract, int32_t declarerSeat) {
    const Tables* tables = skat::tablesFor(contract);
    if (tables == nullptr || declarerSeat < 0 || declarerSeat > 2) return nullptr;
    // malloc and placement construction rather than `new`, so this library does
    // not need libstdc++'s operator new. Every allocation here is one solver or
    // one table; there is nothing for a general-purpose allocator to do that
    // malloc does not.
    void* memory = malloc(sizeof(SkatSolver));
    if (memory == nullptr) return nullptr;
    return new (memory) SkatSolver(tables, declarerSeat);
}

void skat_solver_destroy(SkatSolver* solver) {
    if (solver == nullptr) return;
    solver->~SkatSolver();
    free(solver);
}

int32_t skat_solver_reaches(SkatSolver* solver, const uint32_t hands[3], int32_t toPlay,
                            int32_t leader, uint32_t trickCards, int32_t trickSize,
                            int32_t target) {
    if (solver == nullptr) return SKAT_INVALID;
    if (target <= 0) return 1;
    if (target > skat::kTotalPoints) return 0;
    if (!positionIsSane(solver->solver.declarerSeat(), toPlay, leader, trickSize)) {
        return SKAT_INVALID;
    }
    solver->solver.setHands(hands);
    int value = solver->solver.search(toPlay, leader, trickCards, trickSize,
                                      target - 1, target);
    return value >= target ? 1 : 0;
}

int64_t skat_solver_visited_nodes(const SkatSolver* solver) {
    return solver == nullptr ? 0 : static_cast<int64_t>(solver->solver.visitedNodes());
}

void skat_solver_set_transpositions(SkatSolver* solver, int32_t enabled) {
    if (solver != nullptr) solver->solver.setUseTranspositions(enabled != 0);
}

// ---------------------------------------------------------------------- Null

int32_t skat_null_survives(int32_t declarerSeat, const uint32_t hands[3], int32_t toPlay,
                           int32_t leader, uint32_t trickCards, int32_t trickSize,
                           SkatResult* out) {
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    NullSolver solver(declarerSeat, hands);
    bool survives = solver.survives(toPlay, leader, trickCards, trickSize);
    if (out != nullptr) {
        out->declarerPoints = survives ? 1 : 0;
        out->card = -1;
        out->visitedNodes = static_cast<int64_t>(solver.visitedNodes());
        out->transpositions = solver.storedEntries();
    }
    return survives ? 1 : 0;
}

int32_t skat_null_moves_surviving(int32_t declarerSeat, int32_t toPlay,
                                  const uint32_t hands[3], int32_t leader,
                                  uint32_t trickCards, int32_t trickSize,
                                  int32_t* outCards, int32_t* outVerdicts) {
    if (!positionIsSane(declarerSeat, toPlay, leader, trickSize)) return SKAT_INVALID;
    NullSolver solver(declarerSeat, hands);
    uint8_t moves[10];
    int count = solver.legalMoves(toPlay, trickCards, trickSize, moves);
    // Every legal card gets a verdict, because the caller counts votes per card
    // and a card missing from the list is a card that loses its vote. But
    // interchangeable cards have the same verdict by definition, so only one of
    // each run is searched and the rest copy its answer -- the same saving the
    // search makes internally, applied to the one place that cannot simply drop
    // the duplicates.
    uint32_t playable = solver.playableCards(toPlay, trickCards, trickSize);
    uint32_t alive = solver.alive(trickCards, trickSize);
    int32_t verdictOf[32];
    bool haveVerdict[32] = {};
    for (int i = 0; i < count; i++) {
        int card = moves[i];
        int representative = NullSolver::representativeOf(card, playable, alive);
        if (!haveVerdict[representative]) {
            solver.setHand(toPlay, solver.hand(toPlay) & ~(1u << representative));
            bool survives = solver.childSurvives(toPlay, leader, trickCards, trickSize,
                                                 representative);
            solver.setHand(toPlay, solver.hand(toPlay) | (1u << representative));
            verdictOf[representative] = survives ? 1 : 0;
            haveVerdict[representative] = true;
        }
        outCards[i] = card;
        outVerdicts[i] = verdictOf[representative];
    }
    return count;
}

int32_t skat_null_brute(int32_t declarerSeat, const uint32_t hands[3], int32_t leader) {
    if (!positionIsSane(declarerSeat, leader, leader, 0)) return SKAT_INVALID;
    NullSolver solver(declarerSeat, hands);
    return solver.brute(leader, leader, 0, 0) ? 1 : 0;
}

const char* skat_version(void) { return SKAT_SOLVE_VERSION; }

}  // extern "C"
