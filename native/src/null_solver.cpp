#include "null_solver.h"

#include <stdlib.h>
#include <string.h>

namespace skat {
namespace {

inline int trailingZeros(uint32_t mask) { return __builtin_ctz(mask); }

/// Each of 32 bits into its own two-bit lane. Five shifts and no loop.
///
/// The same helper the points solver uses, and for the same reason: three hands
/// over one pack is three bits per card of which at most one is set, so two
/// bits a card is enough and 32 cards then fill a word exactly.
inline uint64_t spread(uint32_t mask) {
    uint64_t bits = mask;
    bits = (bits | (bits << 16)) & 0x0000FFFF0000FFFFull;
    bits = (bits | (bits << 8)) & 0x00FF00FF00FF00FFull;
    bits = (bits | (bits << 4)) & 0x0F0F0F0F0F0F0F0Full;
    bits = (bits | (bits << 2)) & 0x3333333333333333ull;
    bits = (bits | (bits << 1)) & 0x5555555555555555ull;
    return bits;
}

/// The whole of the trick rules in a Null, and the reason this file has no
/// tables. There is no trump, so only the led suit can win, and inside a suit
/// the card index *is* the strength -- SkatRules.nullStrength is the rank
/// ordinal plus one, and the index is the rank ordinal plus eight times the
/// suit. Two cards of the same suit therefore compare correctly as integers.
inline int winnerSlot(int led, int second, int third) {
    int suit = led >> 3;
    int best = led;
    int bestSlot = 0;
    if ((second >> 3) == suit && second > best) { best = second; bestSlot = 1; }
    if ((third >> 3) == suit && third > best) { best = third; bestSlot = 2; }
    (void) best;
    return bestSlot;
}

constexpr EquivTable buildEquivTable() {
    EquivTable table{};
    for (int alive = 0; alive < 256; alive++) {
        for (int playable = 0; playable < 256; playable++) {
            int keep = playable;
            bool previousWasPlayable = false;
            // Strongest first, because a run defers upwards: of several
            // interchangeable cards it is the highest that stands for the rest.
            for (int rank = 7; rank >= 0; rank--) {
                int bit = 1 << rank;
                if ((alive & bit) == 0) continue;  // played; not a divider
                if ((playable & bit) != 0) {
                    if (previousWasPlayable) keep &= ~bit;
                    previousWasPlayable = true;
                } else {
                    previousWasPlayable = false;
                }
            }
            table.keep[alive][playable] = static_cast<uint8_t>(keep);
        }
    }
    return table;
}

constexpr EquivTable kEquiv = buildEquivTable();

/// The packed transposition entry. One bit of payload, which is the point of
/// this solver; the rest is what the bucket needs to answer a probe.
constexpr uint64_t kEntryPresent = 1ull << 63;
constexpr int kValueShift = 0;
constexpr int kLeaderShift = 1;
constexpr int kCardsLeftShift = 3;

inline bool valueOf(uint64_t entry) { return ((entry >> kValueShift) & 1) != 0; }
inline int leaderOf(uint64_t entry) { return static_cast<int>((entry >> kLeaderShift) & 3); }
inline int cardsLeftOf(uint64_t entry) {
    return static_cast<int>((entry >> kCardsLeftShift) & 0x1F);
}

}  // namespace

const EquivTable& equivTable() { return kEquiv; }

NullSolver::NullSolver(int declarerSeat, const uint32_t hands[3])
        : declarerSeat_(declarerSeat) {
    hands_[0] = hands[0];
    hands_[1] = hands[1];
    hands_[2] = hands[2];
    memset(killers_, 0xFF, sizeof(killers_));
}

NullSolver::~NullSolver() { free(table_); }

// ------------------------------------------------------------------- moves

uint32_t NullSolver::alive(uint32_t trickCards, int trickSize) const {
    uint32_t live = hands_[0] | hands_[1] | hands_[2];
    for (int slot = 0; slot < trickSize; slot++) {
        live |= 1u << ((trickCards >> (8 * slot)) & 0xFF);
    }
    return live;
}

uint32_t NullSolver::playableCards(int seat, uint32_t trickCards, int trickSize) const {
    uint32_t hand = hands_[seat];
    if (trickSize == 0) return hand;
    uint32_t following = hand & (0xFFu << (8 * ((trickCards & 0xFF) >> 3)));
    return following != 0 ? following : hand;
}

/// Ascending rank across the suits, which is the Java order card for card.
///
/// The Java sorts the bit order -- ascending index, so suit-major -- by
/// strength with a stable insertion sort, and in a Null the strength is the
/// rank alone. Cards of the same rank in different suits therefore keep their
/// suit order, and emitting rank by rank and suit by suit reproduces that
/// without sorting anything.
int NullSolver::writeMoves(uint32_t playable, uint8_t* out) {
    int count = 0;
    for (int rank = 0; rank < 8; rank++) {
        for (int suit = 0; suit < 4; suit++) {
            int card = suit * 8 + rank;
            if ((playable >> card) & 1u) out[count++] = static_cast<uint8_t>(card);
        }
    }
    return count;
}

int NullSolver::legalMoves(int seat, uint32_t trickCards, int trickSize,
                           uint8_t* out) const {
    return writeMoves(playableCards(seat, trickCards, trickSize), out);
}

/// The same answer without the table: eight branches a suit instead of a load.
///
/// Kept because the table is 64 KB of .rodata in a library that is otherwise
/// 43 KB, and on Android it is 64 KB four times over. Whether that is worth
/// paying is a measurement, not an opinion, and this is the other side of it.
uint32_t NullSolver::reduceByLoop(uint32_t playable, uint32_t live) {
    uint32_t keep = playable;
    for (int suit = 0; suit < 4; suit++) {
        int shift = 8 * suit;
        uint32_t byte = (playable >> shift) & 0xFF;
        if ((byte & (byte - 1)) == 0) continue;  // nought or one card
        uint32_t liveByte = (live >> shift) & 0xFF;
        bool previousWasPlayable = false;
        for (int rank = 7; rank >= 0; rank--) {
            uint32_t bit = 1u << rank;
            if ((liveByte & bit) == 0) continue;
            if ((byte & bit) != 0) {
                if (previousWasPlayable) keep &= ~(bit << shift);
                previousWasPlayable = true;
            } else {
                previousWasPlayable = false;
            }
        }
    }
    return keep;
}

uint32_t NullSolver::reduce(uint32_t playable, uint32_t live) {
    uint32_t keep = 0;
    for (int suit = 0; suit < 4; suit++) {
        int shift = 8 * suit;
        uint32_t byte = (playable >> shift) & 0xFF;
        if (byte == 0) continue;
        keep |= static_cast<uint32_t>(kEquiv.keep[(live >> shift) & 0xFF][byte]) << shift;
    }
    return keep;
}

int NullSolver::searchMoves(int seat, uint32_t trickCards, int trickSize,
                            uint8_t* out) const {
    uint32_t playable = playableCards(seat, trickCards, trickSize);
    if (useEquivalence_) {
        uint32_t live = alive(trickCards, trickSize);
        playable = useEquivTable_ ? reduce(playable, live) : reduceByLoop(playable, live);
    }
    int count = writeMoves(playable, out);

    // The killers go to the front of the weakest-first order rather than into a
    // score, because there is nothing else to weigh them against: the order is
    // one number and putting a card first is a memmove of at most nine bytes.
    // Second killer first, so the better one ends up ahead of it.
    const uint8_t* killers = killers_[ply_];
    for (int which = 1; which >= 0; which--) {
        uint8_t card = killers[which];
        for (int at = 1; at < count; at++) {
            if (out[at] != card) continue;
            memmove(out + 1, out, static_cast<size_t>(at));
            out[0] = card;
            break;
        }
    }
    return count;
}

int NullSolver::representativeOf(int card, uint32_t playable, uint32_t live) {
    int shift = 8 * (card >> 3);
    int playableByte = static_cast<int>((playable >> shift) & 0xFF);
    int liveByte = static_cast<int>((live >> shift) & 0xFF);
    int wanted = card & 7;
    // The same walk the table was built with, stopped at `card`: whatever the
    // current run of live playable cards started with is what `card` defers to.
    int runTop = -1;
    for (int rank = 7; rank >= 0; rank--) {
        int bit = 1 << rank;
        if ((liveByte & bit) == 0) continue;
        if ((playableByte & bit) == 0) { runTop = -1; continue; }
        if (runTop < 0) runTop = rank;
        if (rank == wanted) return (card & ~7) | runTop;
    }
    return card;
}

// ------------------------------------------------------------------ search

bool NullSolver::survives(int toPlay, int leader, uint32_t trickCards, int trickSize) {
    visitedNodes_++;
    if ((hands_[0] | hands_[1] | hands_[2]) == 0) return true;

    bool atTrickStart = trickSize == 0 && useTranspositions_;
    uint64_t key = 0;
    if (atTrickStart) {
        ensureTable();
        if (table_ != nullptr) {
            key = position();
            int at = probe(key, leader);
            if (at >= 0) return valueOf(table_[at + 1]);
        } else {
            atTrickStart = false;
        }
    }

    bool declarerToPlay = toPlay == declarerSeat_;
    bool result = !declarerToPlay;  // declarer: false until a move survives
    uint8_t moves[10];
    int count = searchMoves(toPlay, trickCards, trickSize, moves);
    for (int i = 0; i < count; i++) {
        int card = moves[i];
        hands_[toPlay] &= ~(1u << card);
        bool value = childSurvives(toPlay, leader, trickCards, trickSize, card);
        hands_[toPlay] |= 1u << card;
        // Short-circuit: the declarer needs one move that survives, the defence
        // one that kills. The card that did it is this depth's killer, because
        // the card that refutes one sibling usually refutes the next.
        if (declarerToPlay == value) {
            result = value;
            rememberKiller(card);
            break;
        }
    }

    if (atTrickStart) store(key, leader, result);
    return result;
}

bool NullSolver::childSurvives(int toPlay, int leader, uint32_t trickCards, int trickSize,
                               int card) {
    uint32_t nextTrick = trickCards | (static_cast<uint32_t>(card) << (8 * trickSize));
    // One card deeper, whichever branch is taken. The killer table is indexed
    // by this, and this is the only place the search recurses.
    ply_++;
    bool value;
    if (trickSize < 2) {
        value = survives((toPlay + 1) % 3, leader, nextTrick, trickSize + 1);
    } else {
        int led = static_cast<int>(nextTrick & 0xFF);
        int second = static_cast<int>((nextTrick >> 8) & 0xFF);
        int winner = (leader + winnerSlot(led, second, card)) % 3;
        // One trick is the whole game. Nothing after it can matter, which is
        // why this search is cheap where the points search is not.
        value = winner != declarerSeat_ && survives(winner, winner, 0, 0);
    }
    ply_--;
    return value;
}

// -------------------------------------------------- the transposition table

uint64_t NullSolver::position() const {
    uint64_t first = spread(hands_[0]);
    uint64_t second = spread(hands_[1]);
    uint64_t third = spread(hands_[2]);
    return first | (second << 1) | third | (third << 1);
}

void NullSolver::ensureTable() {
    if (table_ != nullptr) return;
    size_t length = (static_cast<size_t>(1) << tableBits_) * kWays * 2;
    // calloc rather than malloc-and-memset: the kernel hands back zeroed pages
    // it has not touched, and a Null search that finishes in a millisecond
    // never touches most of them.
    table_ = static_cast<uint64_t*>(calloc(length, sizeof(uint64_t)));
    // A failed allocation is not a reason to answer wrongly. The search works
    // without a table, slowly, and slowly beats a wrong bit.
    bucketMask_ = (1u << tableBits_) - 1;
    storedEntries_ = 0;
}

int NullSolver::bucketAt(uint64_t key, int leader) const {
    uint64_t mixed = key ^ (static_cast<uint64_t>(leader) * 0x9E3779B97F4A7C15ull);
    mixed = (mixed ^ (mixed >> 30)) * 0xBF58476D1CE4E5B9ull;
    mixed = (mixed ^ (mixed >> 27)) * 0x94D049BB133111EBull;
    mixed ^= mixed >> 31;
    return static_cast<int>(mixed & bucketMask_) * (kWays * 2);
}

int NullSolver::probe(uint64_t key, int leader) const {
    int base = bucketAt(key, leader);
    for (int way = 0; way < kWays; way++) {
        int at = base + way * 2;
        uint64_t entry = table_[at + 1];
        if (entry == 0) continue;
        if (table_[at] == key && leaderOf(entry) == leader) return at;
    }
    return -1;
}

void NullSolver::store(uint64_t key, int leader, bool value) {
    if (table_ == nullptr) return;
    int cardsLeft = __builtin_popcount(hands_[0] | hands_[1] | hands_[2]);
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
        // Keep the position that cost most to derive. Depth in cards left,
        // exactly as in the points solver.
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
            | (static_cast<uint64_t>(value ? 1 : 0) << kValueShift)
            | (static_cast<uint64_t>(leader & 3) << kLeaderShift)
            | (static_cast<uint64_t>(cardsLeft & 0x1F) << kCardsLeftShift);
}

// ----------------------------------------------------------------- testing

bool NullSolver::brute(int toPlay, int leader, uint32_t trickCards, int trickSize) {
    visitedNodes_++;
    if ((hands_[0] | hands_[1] | hands_[2]) == 0) return true;
    bool declarerToPlay = toPlay == declarerSeat_;
    bool result = !declarerToPlay;
    uint8_t moves[10];
    int count = legalMoves(toPlay, trickCards, trickSize, moves);
    for (int i = 0; i < count; i++) {
        int card = moves[i];
        hands_[toPlay] &= ~(1u << card);
        uint32_t nextTrick = trickCards | (static_cast<uint32_t>(card) << (8 * trickSize));
        bool value;
        if (trickSize < 2) {
            value = brute((toPlay + 1) % 3, leader, nextTrick, trickSize + 1);
        } else {
            int led = static_cast<int>(nextTrick & 0xFF);
            int second = static_cast<int>((nextTrick >> 8) & 0xFF);
            int winner = (leader + winnerSlot(led, second, card)) % 3;
            value = winner != declarerSeat_ && brute(winner, winner, 0, 0);
        }
        hands_[toPlay] |= 1u << card;
        if (declarerToPlay) result |= value;
        else result &= value;
    }
    return result;
}

}  // namespace skat
