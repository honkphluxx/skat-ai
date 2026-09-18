// Perfect-information search for Null, where the objective is a single bit.
//
// A port of engine/src/main/java/dev/skatklar/demo/solve/NullSolver.java. The
// Java file carries the reasoning about the game -- why one trick ends the
// search, why the two defenders are one side, why the move order is weakest
// first -- and none of that is repeated here. What this file documents is
// where the C++ does something the Java does not, and why a Null search can
// afford things a points search cannot.
//
// The contract with the Java side is the verdict: the same position must yield
// the same bit, and the per-card verdict list must agree card for card. There
// is no latitude here of the kind the points solver takes with equivalent
// cards, because the caller reads the list by card. NullSolverParityTest is
// what holds this file to that.
//
// What it is worth: about twelve times the Java search, measured over
// twenty-five Nulls the declarer can actually make, at the root, where a
// decision costs 660 ms in Java and 55 ms here. That is more than the points
// port's three and a half, and for the same reason it was three and a half
// there -- most of it is not the language.
//
// Why Null gets its own solver rather than a contract ordinal in the other one:
// the value of a position is not a number to bound but a bit to prove, so
// every window, every bound and every alpha-beta cut-off in Solver means
// nothing here. What replaces them is short-circuit evaluation -- the declarer
// needs one move that survives, the defence needs one that kills -- which is
// cheaper than the machinery it replaces.
//
// Three things make this search fast. Measured on six Nulls the declarer
// actually makes, from the root, on this container -- and made Nulls are the
// expensive ones by a wide margin, because a Null the declarer cannot make is
// refuted in a trick or two and never opens the tree at all:
//
//   1. The transposition table, worth about 820x in nodes. Keyed on a bijection
//      of the position and probed only at the start of a trick, where the three
//      hands and the leader say everything there is to know. Off: 1.50e9 nodes
//      and 12.9 s a deal, against 1.8e6 and 19 ms with everything on. Its size
//      matters as much as its presence; see kDefaultTableBits.
//   2. Equivalence, worth about 99x on top of it. Null has no card points, so
//      within a suit *any* two cards with no live card between them are
//      interchangeable -- the bridge case, not the much narrower one the points
//      solver has to settle for (where two cards must also be worth the same,
//      which leaves only the jacks and the nine-eight-seven). A ten-card hand
//      routinely offers three or four legal moves that are all the same move.
//      Off: 1.81e8 nodes and 1.73 s a deal.
//   3. No trump, so a trick is decided inside the led suit and the winner is
//      found by comparing rank bits rather than by a table. Not separable as a
//      number; it is why this file has no tables to look anything up in.
//
// The order is worth noticing, because it is the reverse of the points solver's.
// There the table is a cache over a search that alpha-beta has already pruned
// hard; here there is no pruning to do -- a bit has no window -- so the table is
// not an optimisation over the search, it *is* the search's memory, and without
// it the same endgames are re-derived down every transposed order of the same
// tricks.
//
// Card indices are the project's own: suit-major, rank-minor, index = suit * 8
// + rank, CLUBS=0 SPADES=1 HEARTS=2 DIAMONDS=3, SEVEN=0 .. ACE=7. That layout
// is what makes this file mostly bit arithmetic: **in a Null the strength order
// inside a suit is exactly the rank order**, so a card's bit position within
// its suit byte *is* its strength, and a suit of a hand is one byte of the
// mask. The points solver cannot do this because its jacks leave their suits.

#ifndef SKAT_NULL_SOLVER_H
#define SKAT_NULL_SOLVER_H

#include <stddef.h>
#include <stdint.h>

namespace skat {

/// Keeps only the strongest card of each run of adjacent live playable cards.
///
/// One byte of a hand mask is one suit, and this resolves such a byte in two
/// lookups -- a nibble each, the stronger four cards first, the second reading
/// the state the first left behind. `alive` must carry every card still in a
/// hand *and* the cards lying in the current trick: a played card is gone from
/// the future but can still win this trick, so it separates the cards on either
/// side of it and must not be skipped over.
///
/// **Why nibbles rather than the whole byte.** The obvious table is
/// `keep[alive][playable]`, one lookup instead of two, and it was written that
/// way first. It does not build: 64 KB of entries with an eight-step walk each
/// is about half a million loop bodies, and the NDK's clang gives up at its
/// constexpr step limit with "possible infinite loop?" -- where GCC, whose limit
/// is thirty-two times higher, had compiled it without a word. Raising the limit
/// with a compiler flag would have been the wrong repair twice over: it is a
/// flag per toolchain, and it buys the right to spend 64 KB of every ABI's
/// .rodata, which on Android the APK then carries four times.
///
/// Splitting the walk at the nibble costs one more dependent lookup and shrinks
/// the table by a factor of 128 -- 512 bytes, which is in L1 and stays there.
/// The build is 2048 loop bodies, comfortably inside every compiler's budget.
/// Over forty made Nulls, against NullSolver::reduceByLoop -- the same answer
/// computed rather than looked up, and necessarily the same node count:
///
///     512-byte table  42.9 ms/deal        loop  47.2 ms/deal
///
/// The 64 KB version measured 42.1 on the same deals, which is the same number
/// through the noise. So the small table gave up nothing, and the library went
/// from 123 KB back to 57 KB -- against 43 KB before this solver existed. There
/// was never a speed-for-memory trade here to make; there was a first draft that
/// spent 64 KB for no return and a compiler that refused to let it pass.
/// `skatsolve_test null-bench` prints both of the two that are left.
///
/// The carry is the one subtle part, and it is what makes the split exact: the
/// walk's whole state between cards is "the last live card I saw was playable",
/// one bit, so the high nibble's answer plus that bit is everything the low
/// nibble needs. Nothing is approximated here, and `null-check` proves it by
/// comparing the two paths over all 65536 byte pairs rather than by argument.
struct EquivTable {
    /// Indexed by [carry in][alive nibble][playable nibble]. The low four bits
    /// are the playable cards to keep; bit four is the carry out.
    uint8_t step[2][16][16];
};
const EquivTable& equivTable();

/// The carry bit inside an EquivTable entry.
inline constexpr uint8_t kCarry = 0x10;

class NullSolver {
public:
    NullSolver(int declarerSeat, const uint32_t hands[3]);
    ~NullSolver();
    NullSolver(const NullSolver&) = delete;
    NullSolver& operator=(const NullSolver&) = delete;

    /// Whether the declarer can still avoid every remaining trick.
    bool survives(int toPlay, int leader, uint32_t trickCards, int trickSize);

    /// The verdict for one card, with the hand no longer holding it.
    bool childSurvives(int toPlay, int leader, uint32_t trickCards, int trickSize, int card);

    /// Legal cards, weakest first, with no equivalence reduction.
    ///
    /// What the per-card verdict list needs: the caller reads it by card, so a
    /// card that a stronger equivalent one stands for still has to appear.
    int legalMoves(int seat, uint32_t trickCards, int trickSize, uint8_t* out) const;

    /// Every card this seat may legally put down, as a mask.
    uint32_t playableCards(int seat, uint32_t trickCards, int trickSize) const;

    /// Every card that can still take part in this trick: the three hands plus
    /// whatever is already lying on the table.
    uint32_t alive(uint32_t trickCards, int trickSize) const;

    /// Drops every playable card that an equivalent higher one already covers.
    static uint32_t reduce(uint32_t playable, uint32_t alive);
    static uint32_t reduceByLoop(uint32_t playable, uint32_t alive);
    void setUseEquivTable(bool use) { useEquivTable_ = use; }

    /// The card a dominated one defers to: the top of its run of live playable
    /// cards. Returns `card` itself when it was not dominated. What the
    /// per-card verdict list uses to search one card of a run and copy the
    /// answer to the rest.
    static int representativeOf(int card, uint32_t playable, uint32_t alive);

    /// Plain boolean minimax: no table, no equivalence, no killers. The ground
    /// truth the fast search is checked against on both sides of the boundary.
    bool brute(int toPlay, int leader, uint32_t trickCards, int trickSize);

    void setUseTranspositions(bool use) { useTranspositions_ = use; }
    void setUseEquivalence(bool use) { useEquivalence_ = use; }
    /// Sizes the table, before it is built. One bucket is 64 bytes, so `bits`
    /// costs 2^bits * 64 bytes -- the one knob the benchmark sweeps.
    void setTableBits(int bits) { tableBits_ = bits; }
    uint64_t visitedNodes() const { return visitedNodes_; }
    void resetNodes() { visitedNodes_ = 0; }
    int storedEntries() const { return storedEntries_; }

    uint32_t hand(int seat) const { return hands_[seat]; }
    void setHand(int seat, uint32_t mask) { hands_[seat] = mask; }
    int declarerSeat() const { return declarerSeat_; }

private:
    /// Four slots to a bucket, two words to a slot: one bucket is a cache line.
    static constexpr int kWays = 4;
    /// 16 bits is 65536 buckets and four megabytes, measured rather than
    /// chosen. Over forty made Nulls (`skatsolve_test null-bench 40`):
    ///
    ///     14 bits    1 MB   105 ms/deal   55.1e6 nodes
    ///     16 bits    4 MB    47 ms/deal   24.7e6 nodes
    ///     18 bits   16 MB    49 ms/deal   21.4e6 nodes
    ///     20 bits   64 MB    81 ms/deal   21.4e6 nodes
    ///     22 bits  256 MB   120 ms/deal   21.4e6 nodes
    ///
    /// The node count stops falling at 18 bits -- the table has stopped
    /// missing -- and from there each doubling buys nothing but cache misses,
    /// which is why the biggest table is the second slowest thing on the list.
    /// 16 bits is as fast as 18 at a quarter of the memory, so it is the one to
    /// take. Four megabytes is a lot for a phone to hold, and it holds it for
    /// one solve at a time: the table is calloc'd per solver and freed with it,
    /// so the pages the search never touches are never faulted in.
    static constexpr int kDefaultTableBits = 16;

    /// The moves actually searched: equivalence-reduced, weakest first, with
    /// this depth's killers brought to the front.
    int searchMoves(int seat, uint32_t trickCards, int trickSize, uint8_t* out) const;

    /// A playable mask as a move list, ascending rank across the suits.
    static int writeMoves(uint32_t playable, uint8_t* out);

    /// The bijection: two bits a card, so 32 cards fill a word exactly.
    uint64_t position() const;
    void ensureTable();
    int bucketAt(uint64_t key, int leader) const;
    int probe(uint64_t key, int leader) const;   ///< the slot, or -1 for a miss
    void store(uint64_t key, int leader, bool value);

    void rememberKiller(int card) {
        uint8_t* slot = killers_[ply_];
        if (slot[0] != card) {
            slot[1] = slot[0];
            slot[0] = static_cast<uint8_t>(card);
        }
    }

    int declarerSeat_;
    uint32_t hands_[3];

    uint64_t* table_ = nullptr;
    uint32_t bucketMask_ = 0;
    int tableBits_ = kDefaultTableBits;
    int storedEntries_ = 0;
    uint64_t visitedNodes_ = 0;
    bool useTranspositions_ = true;
    bool useEquivalence_ = true;
    bool useEquivTable_ = true;

    /// The cards that last refuted at this depth. Depth is counted in cards
    /// played, so thirty is the most there can be.
    uint8_t killers_[32][2];
    int ply_ = 0;
};

}  // namespace skat

#endif  // SKAT_NULL_SOLVER_H
