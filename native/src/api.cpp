#include "skatsolve.h"

#include <stdlib.h>

#include "solver.h"
#include "tables.h"

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
/// Measured on ten-card deals, and the single largest tuning win here. The Java
/// starts every table at sixteen kilobytes and doubles when three quarters
/// full, which is right when the size is unknowable -- but at this entry point
/// it is knowable, because the question is known. A null-window question
/// ("does the declarer reach 61?") keeps getting cheaper up to fourteen bits, a
/// megabyte, and then turns round as the table stops fitting in cache; the
/// exact-value question searches thirty times as much and is still improving at
/// fifteen. Building it at that size costs one memset of about thirty
/// microseconds against a search of milliseconds, and saves ten doublings that
/// each re-insert everything.
///
/// Deliberately not applied to the reusable handles AlphaMu holds: there are
/// thirty-two of those alive at once and a megabyte each is not a trade a phone
/// should make. They keep the Java's grow-on-demand behaviour.
constexpr int kWindowedBits = 14;
constexpr int kExactBits = 15;

}  // namespace

extern "C" {

int32_t skat_solve(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                   int32_t leader, SkatResult* out) {
    Prepared prepared = prepare(contract);
    if (!prepared.ok) return SKAT_UNSUPPORTED;
    if (!positionIsSane(declarerSeat, leader, leader, 0)) return SKAT_INVALID;
    Solver solver(prepared.tables, declarerSeat);
    solver.reserve(kExactBits);
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
    solver.reserve(kWindowedBits);
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
    solver.reserve(kWindowedBits);
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
    solver.reserve(kExactBits);
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
    solver.reserve(kWindowedBits);
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
    solver.reserve(kWindowedBits);
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

const char* skat_version(void) { return SKAT_SOLVE_VERSION; }

}  // extern "C"
