#include "tables.h"

// The tables are computed by the compiler, not at load.
//
// It started as a lazily built cache behind std::call_once, which is the
// obvious thing and cost more than it looks: call_once is pthread_once, and
// pthread_once is versioned GLIBC_2.34, so one lazy initialiser made a library
// that is otherwise happy on a decade-old distribution refuse to load on
// anything before Ubuntu 22.04. Everything here is a pure function of a
// contract ordinal, so the compiler can do all of it and put the result in
// .rodata: no initialiser, no guard, no race, and nothing to link against.
//
// See tables.h for what the numbers mean and where they come from.

namespace skat {
namespace {

constexpr int kSuitClubs = 0;
constexpr int kSuitSpades = 1;
constexpr int kSuitHearts = 2;
constexpr int kSuitDiamonds = 3;

constexpr int kRankJack = 4;
constexpr int kRankTen = 3;
constexpr int kRankQueen = 5;
constexpr int kRankKing = 6;
constexpr int kRankAce = 7;
constexpr int kRankNine = 2;
constexpr int kRankEight = 1;

/// The trump suit of a contract, or -1 where the jacks are the only trumps.
constexpr int trumpSuitOf(int contract) {
    return contract == 0   ? kSuitDiamonds
           : contract == 1 ? kSuitHearts
           : contract == 2 ? kSuitSpades
           : contract == 3 ? kSuitClubs
                           : -1;  // GRAND, NULL and RAMSCH
}

constexpr bool isTrump(int contract, int suit, int rank) {
    return contract == kNullContract ? false
                                     : (rank == kRankJack || suit == trumpSuitOf(contract));
}

/// ContractTables.strengthOf, number for number.
constexpr int strengthOf(int contract, int suit, int rank) {
    if (rank == kRankJack) {
        return suit == kSuitClubs    ? 104
               : suit == kSuitSpades ? 103
               : suit == kSuitHearts ? 102
                                     : 101;
    }
    int value = rank == kRankAce     ? 7
                : rank == kRankTen   ? 6
                : rank == kRankKing  ? 5
                : rank == kRankQueen ? 4
                : rank == kRankNine  ? 3
                : rank == kRankEight ? 2
                                     : 1;
    return isTrump(contract, suit, rank) ? 50 + value : value;
}

constexpr int pointsOf(int rank) {
    return rank == kRankAce     ? 11
           : rank == kRankTen   ? 10
           : rank == kRankKing  ? 4
           : rank == kRankQueen ? 3
           : rank == kRankJack  ? 2
                                : 0;
}

constexpr Tables build(int contract) {
    Tables t{};
    for (int suit = 0; suit < 4; suit++) {
        for (int rank = 0; rank < 8; rank++) {
            int index = suit * 8 + rank;
            bool trump = isTrump(contract, suit, rank);
            t.followClass[index] = static_cast<uint8_t>(trump ? kTrumpClass : suit + 1);
            t.strength[index] = static_cast<uint16_t>(strengthOf(contract, suit, rank));
            t.points[index] = static_cast<uint8_t>(pointsOf(rank));
        }
    }
    for (int followClass = 0; followClass < 5; followClass++) {
        uint32_t mask = 0;
        for (int index = 0; index < kCards; index++) {
            if (t.followClass[index] == followClass) mask |= 1u << index;
        }
        t.classMask[followClass] = mask;
    }

    // Point sums by suit byte. Card index is suit-major, so byte n of a hand
    // mask is exactly suit n and the four tables are genuinely different.
    for (int suit = 0; suit < 4; suit++) {
        for (int byte = 0; byte < 256; byte++) {
            int total = 0;
            for (int bit = 0; bit < 8; bit++) {
                if (byte & (1 << bit)) total += t.points[suit * 8 + bit];
            }
            t.pointsByByte[suit][byte] = static_cast<uint16_t>(total);
        }
    }

    // The equivalence groups, derived rather than written down: inside each
    // follow class, sort by strength and cut at every change of point value. A
    // run of two or more is a group. For a suit game that finds the four jacks
    // and the nine-eight-seven of each class; for Grand the jacks and four
    // times nine-eight-seven. Nothing else in Skat qualifies, because no other
    // two adjacent cards are worth the same.
    t.groupCount = 0;
    t.groupedCards = 0;
    for (int followClass = 0; followClass < 5; followClass++) {
        uint8_t ordered[kCards] = {};
        int count = 0;
        for (int index = 0; index < kCards; index++) {
            if (t.followClass[index] == followClass) {
                ordered[count++] = static_cast<uint8_t>(index);
            }
        }
        for (int i = 1; i < count; i++) {
            uint8_t value = ordered[i];
            int j = i - 1;
            while (j >= 0 && t.strength[ordered[j]] < t.strength[value]) {
                ordered[j + 1] = ordered[j];
                j--;
            }
            ordered[j + 1] = value;
        }
        int start = 0;
        while (start < count) {
            int end = start + 1;
            while (end < count && t.points[ordered[end]] == t.points[ordered[start]]) end++;
            int size = end - start;
            if (size >= 2 && t.groupCount < 8) {
                EquivGroup& group = t.groups[t.groupCount++];
                group.size = static_cast<uint8_t>(size < 4 ? size : 4);
                group.mask = 0;
                for (int i = 0; i < group.size; i++) {
                    group.order[i] = ordered[start + i];
                    group.mask |= 1u << group.order[i];
                }
                t.groupedCards |= group.mask;
            }
            start = end;
        }
    }
    return t;
}

constexpr Tables kTables[kContracts] = {
        build(0), build(1), build(2), build(3), build(4), build(5), build(6),
};

}  // namespace

const Tables* tablesFor(int contractOrdinal) {
    if (contractOrdinal < 0 || contractOrdinal >= kContracts) return nullptr;
    // Null is a different game rather than a different trump: its objective is
    // "the declarer takes no trick at all" rather than a point count, so every
    // window and cut-off in the search means nothing for it. Refusing is the
    // honest answer, and the caller falls back exactly as the Java solver's
    // constructor makes it.
    if (contractOrdinal == kNullContract) return nullptr;
    return &kTables[contractOrdinal];
}

}  // namespace skat
