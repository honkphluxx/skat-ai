#include "solver.h"

#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
// Only for the clock below. Windows' POSIX clock lives in winpthreads, which is
// a whole threading runtime to link in for one call.
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <time.h>
#endif

namespace skat {
namespace {

inline int bitCount(uint32_t mask) { return __builtin_popcount(mask); }
inline int trailingZeros(uint32_t mask) { return __builtin_ctz(mask); }

/// A monotonic reading in nanoseconds.
///
/// clock_gettime rather than std::chrono::steady_clock, which is the same call
/// wrapped in a libstdc++ symbol versioned GLIBCXX_3.4.19 -- one more reason a
/// library that wants to load anywhere should link against libc and nothing
/// else. CLOCK_MONOTONIC is what steady_clock uses on Linux and on Android.
///
/// Windows has no clock_gettime of its own; mingw's comes from winpthreads, and
/// linking a threading runtime for one clock call is the same mistake in a
/// different library. QueryPerformanceCounter is what steady_clock uses there
/// anyway, and it costs nothing beyond kernel32.
inline int64_t nowTicks() {
#if defined(_WIN32)
    LARGE_INTEGER frequency;
    LARGE_INTEGER now;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&now);
    int64_t ticks = static_cast<int64_t>(now.QuadPart);
    int64_t perSecond = static_cast<int64_t>(frequency.QuadPart);
    if (perSecond <= 0) return 0;
    // Seconds and remainder separately. The counter runs from boot and the
    // frequency is megahertz, so ticks * 1000000000 overflows a signed 64-bit
    // integer within months of uptime -- and the failure would be a deadline in
    // the past, which reads as a solver that answers nothing.
    return (ticks / perSecond) * 1000000000ll
            + (ticks % perSecond) * 1000000000ll / perSecond;
#else
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000ll + now.tv_nsec;
#endif
}

inline int smaller(int a, int b) { return a < b ? a : b; }
inline int larger(int a, int b) { return a > b ? a : b; }

// The packed transposition entry, laid out exactly as the Java one so that the
// two implementations can be compared field by field when they disagree.
constexpr uint64_t kEntryPresent = 1ull << 63;
constexpr int kValueShift = 0;
constexpr int kMoveShift = 8;
constexpr int kExactShift = 14;
constexpr int kLowerBoundShift = 15;
constexpr int kLeaderShift = 16;
constexpr int kCardsLeftShift = 18;

inline int valueOf(uint64_t entry) { return static_cast<int>((entry >> kValueShift) & 0xFF); }
inline int moveOf(uint64_t entry) {
    return static_cast<int>((entry >> kMoveShift) & 0x3F) - 1;
}
inline bool isExact(uint64_t entry) { return ((entry >> kExactShift) & 1) != 0; }
inline bool isLowerBound(uint64_t entry) {
    return ((entry >> kLowerBoundShift) & 1) != 0;
}
inline int leaderOf(uint64_t entry) { return static_cast<int>((entry >> kLeaderShift) & 3); }
inline int cardsLeftOf(uint64_t entry) {
    return static_cast<int>((entry >> kCardsLeftShift) & 0xF);
}

/// Each of 32 bits into its own two-bit lane. Five shifts and no loop.
inline uint64_t spread(uint32_t mask) {
    uint64_t bits = mask;
    bits = (bits | (bits << 16)) & 0x0000FFFF0000FFFFull;
    bits = (bits | (bits << 8)) & 0x00FF00FF00FF00FFull;
    bits = (bits | (bits << 4)) & 0x0F0F0F0F0F0F0F0Full;
    bits = (bits | (bits << 2)) & 0x3333333333333333ull;
    bits = (bits | (bits << 1)) & 0x5555555555555555ull;
    return bits;
}

}  // namespace

Solver::Solver(const Tables* tables, int declarerSeat)
        : tables_(tables), declarerSeat_(declarerSeat) {
    memset(killers_, 0xFF, sizeof(killers_));
}

Solver::~Solver() { free(table_); }

void Solver::setHands(const uint32_t hands[3]) {
    hands_[0] = hands[0];
    hands_[1] = hands[1];
    hands_[2] = hands[2];
}

void Solver::setBudgetNanos(int64_t budgetNanos) {
    bounded_ = true;
    deadlineTicks_ = nowTicks() + budgetNanos;
}

bool Solver::pastDeadline() {
    // Subtraction rather than a comparison, for the same reason the Java gives:
    // a monotonic clock has no defined origin and the difference stays correct
    // across a wrap where a plain >= does not.
    if (nowTicks() - deadlineTicks_ < 0) return false;
    expired_ = true;
    return true;
}

int Solver::pointsInHands() const {
    // Twelve table lookups rather than a loop over every card still held. The
    // Java walks the bits, which is thirty iterations on a fresh deal, and this
    // is called at the top of every trick-start node -- the single most visited
    // piece of arithmetic in the search.
    return tables_->pointsOfHands(hands_);
}

uint32_t Solver::aliveCards(uint32_t trickCards, int trickSize) const {
    uint32_t alive = hands_[0] | hands_[1] | hands_[2];
    for (int slot = 0; slot < trickSize; slot++) {
        alive |= 1u << ((trickCards >> (8 * slot)) & 0xFF);
    }
    return alive;
}

int Solver::searchMoves(int seat, uint32_t trickCards, int trickSize, int ttMove,
                        uint8_t* out) {
    uint32_t hand = hands_[seat];
    uint32_t playable = hand;
    if (trickSize > 0) {
        int ledClass = tables_->followClass[trickCards & 0xFF];
        uint32_t following = hand & tables_->classMask[ledClass];
        if (following != 0) playable = following;
    }
    playable = tables_->dropDominated(playable, aliveCards(trickCards, trickSize));

    const uint8_t* killers = killers_[ply_];
    int32_t score[10];
    int count = 0;
    for (uint32_t rest = playable; rest != 0;) {
        uint32_t bit = rest & (~rest + 1);
        int card = trailingZeros(bit);
        rest ^= bit;
        int32_t value = tables_->strength[card];
        if (card == ttMove) value += 1 << 20;
        else if (card == killers[0]) value += 1 << 18;
        else if (card == killers[1]) value += 1 << 17;
        out[count] = static_cast<uint8_t>(card);
        score[count] = value;
        count++;
    }
    for (int i = 1; i < count; i++) {
        uint8_t card = out[i];
        int32_t value = score[i];
        int j = i - 1;
        while (j >= 0 && score[j] < value) {
            out[j + 1] = out[j];
            score[j + 1] = score[j];
            j--;
        }
        out[j + 1] = card;
        score[j + 1] = value;
    }
    return count;
}

int Solver::trickPoints(uint32_t trickCards, int trickSize) const {
    int total = 0;
    for (int slot = 0; slot < trickSize; slot++) {
        total += tables_->points[(trickCards >> (8 * slot)) & 0xFF];
    }
    return total;
}

int Solver::orderedMoves(int seat, uint32_t trickCards, int trickSize,
                         uint8_t* out) const {
    uint32_t hand = hands_[seat];
    uint32_t playable = hand;
    if (trickSize > 0) {
        int ledClass = tables_->followClass[trickCards & 0xFF];
        uint32_t following = hand & tables_->classMask[ledClass];
        if (following != 0) playable = following;
    }
    int count = 0;
    for (uint32_t rest = playable; rest != 0;) {
        uint32_t bit = rest & (~rest + 1);
        out[count++] = static_cast<uint8_t>(trailingZeros(bit));
        rest ^= bit;
    }
    // Insertion sort by descending strength; count is at most ten.
    for (int i = 1; i < count; i++) {
        uint8_t value = out[i];
        int j = i - 1;
        while (j >= 0 && tables_->strength[out[j]] < tables_->strength[value]) {
            out[j + 1] = out[j];
            j--;
        }
        out[j + 1] = value;
    }
    return count;
}

namespace {
/// Which slot of a completed trick holds a given card.
inline int slotOf(uint32_t trickCards, int card) {
    for (int slot = 0; slot < 3; slot++) {
        if (static_cast<int>((trickCards >> (8 * slot)) & 0xFF) == card) return slot;
    }
    return 0;  // unreachable: the winner was one of the three
}
}  // namespace

int Solver::childValue(int toPlay, int leader, uint32_t trickCards, int trickSize,
                       int card, int alpha, int beta) {
    uint32_t nextTrick = trickCards | (static_cast<uint32_t>(card) << (8 * trickSize));
    // One card deeper, whichever branch is taken. The killer table is indexed by
    // this, and it is the only place the search recurses, so it is the only
    // place the depth has to be kept.
    ply_++;
    int value;
    if (trickSize < 2) {
        value = search((toPlay + 1) % 3, leader, nextTrick, trickSize + 1, alpha, beta);
    } else {
        int led = nextTrick & 0xFF;
        int second = (nextTrick >> 8) & 0xFF;
        int winnerCard = tables_->trickWinner(led, second, card);
        int winnerSeat = (leader + slotOf(nextTrick, winnerCard)) % 3;
        int trickTotal =
                tables_->points[led] + tables_->points[second] + tables_->points[card];
        int gained = winnerSeat == declarerSeat_ ? trickTotal : 0;
        value = gained
                + search(winnerSeat, winnerSeat, 0, 0, alpha - gained, beta - gained);
    }
    ply_--;
    return value;
}

int Solver::search(int toPlay, int leader, uint32_t trickCards, int trickSize,
                   int alpha, int beta) {
    visitedNodes_++;
    if (bounded_ && (visitedNodes_ & kDeadlineCheckMask) == 0 && pastDeadline()) return 0;
    if (hands_[0] == 0 && hands_[1] == 0 && hands_[2] == 0) return 0;

    if (trickSize == 0) {
        // Nothing left to play for: the declarer can never take more than the
        // points still in the hands and never fewer than none, so a window that
        // already excludes that range is decided without searching.
        int remaining = pointsInHands();
        if (remaining <= alpha) return remaining;
        if (beta <= 0) return 0;
    }

    // The last trick is forced and one node deep, so hashing it, probing for it
    // and storing it is pure overhead -- and it is the most numerous node in the
    // tree by a wide margin.
    int cardsLeft = trickSize == 0 ? bitCount(hands_[toPlay]) : 0;
    bool atTrickStart = trickSize == 0 && useTranspositions_ && cardsLeft > 1;
    uint64_t key = 0;
    int preferredMove = -1;
    if (atTrickStart) {
        ensureTable();
        key = position();
        int at = probe(key, leader);
        if (at >= 0) {
            uint64_t entry = table_[at + 1];
            int cached = valueOf(entry);
            if (isExact(entry)) return cached;
            if (isLowerBound(entry) && cached >= beta) return cached;
            if (!isLowerBound(entry) && cached <= alpha) return cached;
            preferredMove = moveOf(entry);
        }
    }

    int originalAlpha = alpha;
    int originalBeta = beta;
    bool declarerToPlay = toPlay == declarerSeat_;
    int best = declarerToPlay ? -1000000 : 1000000;
    int bestMove = -1;

    uint8_t moves[10];
    int count = searchMoves(toPlay, trickCards, trickSize, preferredMove, moves);

    for (int i = 0; i < count; i++) {
        int card = moves[i];
        hands_[toPlay] &= ~(1u << card);
        int value = childValue(toPlay, leader, trickCards, trickSize, card, alpha, beta);
        hands_[toPlay] |= 1u << card;
        // The budget ran out somewhere below. Unwind without storing anything:
        // `best` is the maximum over the children that finished, which is not
        // the value of this position and must not be cached as one.
        if (expired_) return 0;

        if (declarerToPlay) {
            if (value > best) { best = value; bestMove = card; }
            if (best > alpha) alpha = best;
        } else {
            if (value < best) { best = value; bestMove = card; }
            if (best < beta) beta = best;
        }
        if (alpha >= beta) {
            rememberKiller(card);
            break;
        }
    }

    if (atTrickStart) {
        bool exact = best > originalAlpha && best < originalBeta;
        store(key, leader, best, exact, best >= originalBeta, bestMove, cardsLeft);
    }
    return best;
}

Solver::Choice Solver::rootChoice(int toPlay, int leader, uint32_t trickCards,
                                  int trickSize, int alpha, int beta) {
    bool declarerToPlay = toPlay == declarerSeat_;
    int best = declarerToPlay ? -1000000 : 1000000;
    int bestCard = -1;
    uint8_t moves[10];
    // The root may collapse equivalents too: a representative is a legal card
    // and defends the same value, which is exactly what the caller plays.
    int count = searchMoves(toPlay, trickCards, trickSize, -1, moves);
    for (int i = 0; i < count; i++) {
        int card = moves[i];
        hands_[toPlay] &= ~(1u << card);
        int value = childValue(toPlay, leader, trickCards, trickSize, card, alpha, beta);
        hands_[toPlay] |= 1u << card;
        if (expired_) return Choice{bestCard, best};
        bool better = bestCard < 0 || (declarerToPlay ? value > best : value < best);
        if (better) {
            best = value;
            bestCard = card;
        }
        if (declarerToPlay) alpha = larger(alpha, best);
        else beta = smaller(beta, best);
        if (alpha >= beta) break;
    }
    return Choice{bestCard, best};
}

Solver::Choice Solver::chooseAtRoot(int toPlay, int leader, uint32_t trickCards,
                                    int trickSize) {
    int ceiling = pointsInHands() + trickPoints(trickCards, trickSize);
    int low = 0;
    int high = ceiling;
    while (low < high) {
        int mid = (low + high + 1) / 2;
        Choice probe = rootChoice(toPlay, leader, trickCards, trickSize, mid - 1, mid);
        int value = probe.declarerPoints;
        if (expired_) break;
        if (value >= mid) low = larger(mid, value);
        else high = smaller(mid - 1, value);
    }
    return rootChoice(toPlay, leader, trickCards, trickSize, low - 1, low + 1);
}

// ----------------------------------------------------- the transposition table

void Solver::reserve(int bits) {
    if (bits < kMinTableBits) bits = kMinTableBits;
    if (bits > maxTableBits_) bits = maxTableBits_;
    if (table_ == nullptr || tableBits_ < bits) allocate(bits);
}

void Solver::ensureTable() {
    if (table_ == nullptr) {
        allocate(kMinTableBits);
        return;
    }
    if (tableBits_ < maxTableBits_ && storedEntries_ > slots() - (slots() >> 2)) {
        grow();
    }
}

void Solver::allocate(int bits) {
    size_t length = static_cast<size_t>(1) << bits;
    length *= kWays * 2;
    uint64_t* fresh = static_cast<uint64_t*>(calloc(length, sizeof(uint64_t)));
    if (fresh == nullptr) {
        // Out of memory is not a reason to answer wrongly. Keep whatever table
        // there is, or none: the search works without one, slowly, and slowly
        // is a great deal better than a wrong number.
        return;
    }
    free(table_);
    table_ = fresh;
    tableLength_ = length;
    tableBits_ = bits;
    bucketMask_ = (1u << bits) - 1;
    storedEntries_ = 0;
}

void Solver::grow() {
    uint64_t* old = table_;
    size_t oldLength = tableLength_;
    table_ = nullptr;
    tableLength_ = 0;
    allocate(tableBits_ + 1);
    if (table_ == nullptr) {
        // The bigger table could not be had; keep the smaller one rather than
        // losing the table altogether.
        table_ = old;
        tableLength_ = oldLength;
        return;
    }
    for (size_t at = 0; at < oldLength; at += 2) {
        uint64_t entry = old[at + 1];
        if (entry == 0) continue;
        store(old[at], leaderOf(entry), valueOf(entry), isExact(entry),
              isLowerBound(entry), moveOf(entry), cardsLeftOf(entry));
    }
    free(old);
}

uint64_t Solver::position() const {
    uint64_t first = spread(hands_[0]);
    uint64_t second = spread(hands_[1]);
    uint64_t third = spread(hands_[2]);
    return first | (second << 1) | third | (third << 1);
}

int Solver::bucketAt(uint64_t key, int leader) const {
    uint64_t mixed = key ^ (static_cast<uint64_t>(leader) * 0x9E3779B97F4A7C15ull);
    mixed = (mixed ^ (mixed >> 30)) * 0xBF58476D1CE4E5B9ull;
    mixed = (mixed ^ (mixed >> 27)) * 0x94D049BB133111EBull;
    mixed ^= mixed >> 31;
    return static_cast<int>(mixed & bucketMask_) * (kWays * 2);
}

int Solver::probe(uint64_t key, int leader) const {
    int base = bucketAt(key, leader);
    for (int way = 0; way < kWays; way++) {
        int at = base + way * 2;
        uint64_t entry = table_[at + 1];
        if (entry == 0) continue;
        if (table_[at] == key && leaderOf(entry) == leader) return at;
    }
    return -1;
}

void Solver::store(uint64_t key, int leader, int value, bool exact, bool lowerBound,
                   int move, int cardsLeft) {
    int base = bucketAt(key, leader);
    int empty = -1;
    int victim = base;
    int shallowest = 1 << 30;
    for (int way = 0; way < kWays; way++) {
        int at = base + way * 2;
        uint64_t entry = table_[at + 1];
        if (entry == 0) {
            if (empty < 0) empty = at;
            continue;
        }
        if (table_[at] == key && leaderOf(entry) == leader) {
            empty = -1;
            victim = at;
            break;
        }
        int depth = cardsLeftOf(entry);
        if (depth < shallowest) {
            shallowest = depth;
            victim = at;
        }
    }
    int at = empty >= 0 ? empty : victim;
    if (empty >= 0) storedEntries_++;
    table_[at] = key;
    table_[at + 1] = kEntryPresent
            | (static_cast<uint64_t>(value & 0xFF) << kValueShift)
            | (static_cast<uint64_t>((move + 1) & 0x3F) << kMoveShift)
            | (static_cast<uint64_t>(exact ? 1 : 0) << kExactShift)
            | (static_cast<uint64_t>(lowerBound ? 1 : 0) << kLowerBoundShift)
            | (static_cast<uint64_t>(leader & 3) << kLeaderShift)
            | (static_cast<uint64_t>(cardsLeft & 0xF) << kCardsLeftShift);
}

// ------------------------------------------------------------------- testing

int Solver::brute(int toPlay, int leader, uint32_t trickCards, int trickSize) {
    visitedNodes_++;
    if (hands_[0] == 0 && hands_[1] == 0 && hands_[2] == 0) return 0;
    bool declarerToPlay = toPlay == declarerSeat_;
    int best = declarerToPlay ? -1000000 : 1000000;
    uint8_t moves[10];
    int count = orderedMoves(toPlay, trickCards, trickSize, moves);
    for (int i = 0; i < count; i++) {
        int card = moves[i];
        hands_[toPlay] &= ~(1u << card);
        uint32_t nextTrick = trickCards | (static_cast<uint32_t>(card) << (8 * trickSize));
        int value;
        if (trickSize == 2) {
            int led = nextTrick & 0xFF;
            int second = (nextTrick >> 8) & 0xFF;
            int winnerCard = tables_->trickWinner(led, second, card);
            int winnerSeat = (leader + slotOf(nextTrick, winnerCard)) % 3;
            int trickTotal =
                    tables_->points[led] + tables_->points[second] + tables_->points[card];
            value = (winnerSeat == declarerSeat_ ? trickTotal : 0)
                    + brute(winnerSeat, winnerSeat, 0, 0);
        } else {
            value = brute((toPlay + 1) % 3, leader, nextTrick, trickSize + 1);
        }
        hands_[toPlay] |= 1u << card;
        best = declarerToPlay ? larger(best, value) : smaller(best, value);
    }
    return best;
}

}  // namespace skat
