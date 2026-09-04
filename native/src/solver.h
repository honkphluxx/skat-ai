// Perfect-information ("double dummy") search over a Skat deal.
//
// A port of core/src/main/java/dev/skatklar/demo/solve/DoubleDummySolver.java.
// The Java file carries the reasoning behind the shape of the search -- why the
// transposition key is a bijection rather than a hash, why the root brackets the
// value with null windows instead of searching the full range, why the table
// grows on demand -- and none of that is repeated here. What this file
// documents is where the C++ deliberately does something the Java does not.
//
// The contract with the Java side is: the same position must yield the same
// declarer point value. It need not yield the same *card* among cards that
// defend the same value, and the optimisations below take that latitude --
// equivalent-card merging in particular can return the seven where Java
// returned the eight when the two are interchangeable. SolverParityTest asserts
// the value exactly and the card up to equivalence.

#ifndef SKAT_SOLVER_H
#define SKAT_SOLVER_H

#include <stddef.h>
#include <stdint.h>

#include "tables.h"

namespace skat {

class Solver {
public:
    Solver(const Tables* tables, int declarerSeat);
    ~Solver();
    Solver(const Solver&) = delete;
    Solver& operator=(const Solver&) = delete;

    void setHands(const uint32_t hands[3]);
    void setHand(int seat, uint32_t mask) { hands_[seat] = mask; }
    uint32_t hand(int seat) const { return hands_[seat]; }

    /// Turns the transposition table off. Only the parity tests do this: it is
    /// how a disagreement is pinned on the pruning or on the table.
    void setUseTranspositions(bool use) { useTranspositions_ = use; }

    /// Gives this search a budget in nanoseconds, measured from the call.
    ///
    /// A budget rather than an absolute deadline because the two sides do not
    /// share a clock origin: Java hands over what is left of its own deadline
    /// and this side starts its own steady clock. Assuming System.nanoTime and
    /// clock_gettime(CLOCK_MONOTONIC) share an epoch happens to hold on Linux
    /// and Android today and is not something to build on.
    void setBudgetNanos(int64_t budgetNanos);
    void clearBudget() { bounded_ = false; expired_ = false; }

    /// Whether the last bounded search gave up before it could answer.
    ///
    /// A flag rather than an exception, and not out of taste: exceptions drag
    /// in the unwinder and the ABI's type information, and those pull the whole
    /// C++ runtime into a library that otherwise needs nothing but libc. It
    /// mattered here -- a build carrying the runtime statically demanded a
    /// glibc from 2023 and would not have loaded on a server two releases old.
    /// The flag costs one predictable branch after each child, which measured
    /// as nothing at all.
    bool expired() const { return expired_; }

    /// Sizes the table before the search rather than growing into it.
    void reserve(int bits);
    /// Caps how far the table may grow. Memory, not correctness: the table is a
    /// cache and a smaller one costs re-searches, never answers.
    void setMaxTableBits(int bits) { maxTableBits_ = bits; }

    uint64_t visitedNodes() const { return visitedNodes_; }
    int storedEntries() const { return storedEntries_; }
    void resetNodes() { visitedNodes_ = 0; }

    /// Negamax-shaped alpha-beta over declarer points still to be won.
    int search(int toPlay, int leader, uint32_t trickCards, int trickSize,
               int alpha, int beta);

    /// One ply that keeps the move as well as its value.
    struct Choice {
        int card;
        int declarerPoints;
    };
    Choice rootChoice(int toPlay, int leader, uint32_t trickCards, int trickSize,
                      int alpha, int beta);
    /// The exact value at the root, bracketed by null windows. See the Java.
    Choice chooseAtRoot(int toPlay, int leader, uint32_t trickCards, int trickSize);

    /// The value of playing one card from the current position.
    int childValue(int toPlay, int leader, uint32_t trickCards, int trickSize,
                   int card, int alpha, int beta);

    /// Every legal card, strongest first. Returns how many were written.
    ///
    /// The plain generator: no equivalence reduction, no killers. What the
    /// exhaustive minimax and the per-card verdict list need, because both have
    /// to see every card a player could actually put down.
    int orderedMoves(int seat, uint32_t trickCards, int trickSize, uint8_t* out) const;

    /// The cards the search actually tries, ordered for cutting.
    ///
    /// Two things the plain generator does not do. Equivalent cards are
    /// collapsed to one representative, which is where most of the saving over
    /// the Java search comes from. And the order puts the transposition table's
    /// move first, then the two killers for this depth, then strength -- the
    /// first two because a move that refuted a sibling usually refutes here,
    /// and strength last because in a trick game it is the honest default.
    int searchMoves(int seat, uint32_t trickCards, int trickSize, int ttMove,
                    uint8_t* out);

    /// Everything still capable of taking part in the current trick: the three
    /// hands plus whatever is lying on the table.
    uint32_t aliveCards(uint32_t trickCards, int trickSize) const;

    /// Card points still held, and therefore still winnable.
    int pointsInHands() const;
    int trickPoints(uint32_t trickCards, int trickSize) const;

    /// Plain minimax: no pruning, no table, no ordering. The ground truth the
    /// fast search is checked against, on both sides of the language boundary.
    int brute(int toPlay, int leader, uint32_t trickCards, int trickSize);

    const Tables* tables() const { return tables_; }
    int declarerSeat() const { return declarerSeat_; }

private:
    static constexpr int kWays = 4;
    static constexpr int kMinTableBits = 8;
    static constexpr int kMaxTableBits = 18;
    static constexpr uint64_t kDeadlineCheckMask = 1023;

    void ensureTable();
    void allocate(int bits);
    void grow();
    int slots() const { return (1 << tableBits_) * kWays; }
    uint64_t position() const;
    int bucketAt(uint64_t position, int leader) const;
    int probe(uint64_t position, int leader) const;
    void store(uint64_t position, int leader, int value, bool exact, bool lowerBound,
               int move, int cardsLeft);
    /// True once the budget has run out; sets `expired_` as it goes.
    bool pastDeadline();

    const Tables* tables_;
    int declarerSeat_;
    uint32_t hands_[3] = {0, 0, 0};

    /// Position and entry word interleaved; four slots to a bucket, so a bucket
    /// is one cache line. Raw rather than a vector because calloc hands back
    /// zeroed pages the kernel has not touched yet, which a vector's assign
    /// would then immediately write over.
    uint64_t* table_ = nullptr;
    size_t tableLength_ = 0;
    uint32_t bucketMask_ = 0;
    int tableBits_ = 0;
    int maxTableBits_ = kMaxTableBits;
    int storedEntries_ = 0;

    uint64_t visitedNodes_ = 0;
    bool useTranspositions_ = true;
    bool bounded_ = false;
    bool expired_ = false;
    int64_t deadlineTicks_ = 0;

    /// Killer moves: the cards that last caused a cut-off at this depth.
    ///
    /// Not in the Java. A refutation at one node very often refutes its
    /// siblings too, and a killer is the cheapest way to try it first -- no
    /// table probe, no hash, one comparison while the moves are being scored.
    /// Two slots because the best and second-best refutation alternate. Depth
    /// is counted in cards played, so at most thirty.
    uint8_t killers_[32][2];
    int ply_ = 0;

    void rememberKiller(int card) {
        uint8_t* slot = killers_[ply_];
        if (slot[0] != card) {
            slot[1] = slot[0];
            slot[0] = static_cast<uint8_t>(card);
        }
    }
};

}  // namespace skat

#endif  // SKAT_SOLVER_H
