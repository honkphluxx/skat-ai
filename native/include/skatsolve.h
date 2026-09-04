// The C surface of the Skat double-dummy solver.
//
// Plain C on purpose. It is what the JNI layer calls, what the standalone test
// and benchmark binaries call, and what anything else that ever wants this
// engine would call; keeping C++ types out of it means none of those have to
// agree on a standard library or an ABI.
//
// Hands are bit masks over the 32 card indices used throughout the project:
// suit-major, rank-minor, index = suit * 8 + rank, with CLUBS=0 SPADES=1
// HEARTS=2 DIAMONDS=3 and SEVEN=0 .. ACE=7. Contracts are the Java enum
// ordinals: DIAMONDS=0 HEARTS=1 SPADES=2 CLUBS=3 GRAND=4 NULL=5 RAMSCH=6.
//
// A trick in progress travels as `trickCards`, one card index per byte in play
// order starting at the leader, with `trickSize` saying how many of them are
// real. That is the same encoding the Java search uses internally, so a caller
// that already has it does not have to unpack it.
//
// Every entry point returns SKAT_UNSUPPORTED for a contract this engine refuses
// -- Null, whose objective is binary rather than a point count and whose search
// is a different piece of work. The caller falls back, exactly as it already
// does when the Java solver's constructor refuses.

#ifndef SKATSOLVE_H
#define SKATSOLVE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SKAT_OK 0
/// The contract has no double-dummy point search. Fall back.
#define SKAT_UNSUPPORTED (-1)
/// A bounded search gave up before it could answer.
#define SKAT_EXPIRED (-2)
/// The arguments do not describe a position.
#define SKAT_INVALID (-3)

/// What a solve found, plus what it cost.
typedef struct {
    int32_t declarerPoints;
    int32_t card;          /**< the chosen card index, or -1 where none was asked for */
    int64_t visitedNodes;
    int32_t transpositions;
} SkatResult;

/// Card points the declarer takes from the remaining hands with optimal play.
int32_t skat_solve(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                   int32_t leader, SkatResult* out);

/// Whether the declarer still reaches `target`. Returns 1, 0, or a negative code.
int32_t skat_reaches(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                     int32_t toPlay, int32_t leader, uint32_t trickCards,
                     int32_t trickSize, int32_t target);

/// The same question with a time budget in nanoseconds from now.
///
/// Returns 1, 0, SKAT_EXPIRED when the budget ran out first, or SKAT_UNSUPPORTED.
int32_t skat_reaches_within(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                            int32_t toPlay, int32_t leader, uint32_t trickCards,
                            int32_t trickSize, int32_t target, int64_t budgetNanos);

/// The best card from a position, with the exact value it defends.
int32_t skat_best_card(int32_t contract, int32_t declarerSeat, int32_t toPlay,
                       const uint32_t hands[3], int32_t leader, uint32_t trickCards,
                       int32_t trickSize, SkatResult* out);

/// The best card for the score sheet's bands rather than for the last point.
int32_t skat_best_card_for_result(int32_t contract, int32_t declarerSeat, int32_t toPlay,
                                  const uint32_t hands[3], int32_t leader,
                                  uint32_t trickCards, int32_t trickSize,
                                  int32_t declarerPointsSoFar, SkatResult* out);

/// For every legal card, whether the declarer still reaches `target` after it.
///
/// Writes one entry per legal move into `outCards` and `outBounds`, which must
/// have room for ten, in the order the moves were searched. Returns the number
/// written, or a negative code.
int32_t skat_moves_reaching(int32_t contract, int32_t declarerSeat, int32_t toPlay,
                            const uint32_t hands[3], int32_t leader, uint32_t trickCards,
                            int32_t trickSize, int32_t target, int32_t* outCards,
                            int32_t* outBounds);

/// Plain minimax, for the tests: no pruning, no table, no ordering.
int32_t skat_brute(int32_t contract, int32_t declarerSeat, const uint32_t hands[3],
                   int32_t leader, int32_t* out);

/// A solver that keeps its transposition table between questions.
///
/// For a caller that asks hundreds of questions about positions differing by a
/// card or two -- AlphaMu -- and would otherwise re-derive the same endgames
/// every time. One handle per world; two worlds share nothing worth caching.
/// Not thread-safe: it is a scratchpad for one search on one thread.
typedef struct SkatSolver SkatSolver;

SkatSolver* skat_solver_create(int32_t contract, int32_t declarerSeat);
void skat_solver_destroy(SkatSolver* solver);
int32_t skat_solver_reaches(SkatSolver* solver, const uint32_t hands[3], int32_t toPlay,
                            int32_t leader, uint32_t trickCards, int32_t trickSize,
                            int32_t target);
int64_t skat_solver_visited_nodes(const SkatSolver* solver);

/// A build identity, so a mismatched library can be recognised as one.
const char* skat_version(void);

/// Turns the transposition table off on a handle. Tests only: it is how a
/// disagreement is pinned on the pruning rather than on the table.
void skat_solver_set_transpositions(SkatSolver* solver, int32_t enabled);

#ifdef __cplusplus
}
#endif

#endif  // SKATSOLVE_H
