// Per-contract lookup tables over the 32-card pack.
//
// The mirror of core/src/main/java/dev/skatklar/demo/solve/ContractTables.java,
// and deliberately a literal one. Every number here -- the card index, the
// strength order, the point values, what counts as trump -- has to agree with
// the Java tables card for card, because a solved line and a played line must
// agree on who takes the trick. Where the Java file is the authority, this file
// is the copy, and SolverParityTest is what keeps the copy honest.
//
// Card index is suit-major, rank-minor, exactly as ContractTables.index:
//   suit  CLUBS=0 SPADES=1 HEARTS=2 DIAMONDS=3
//   rank  SEVEN=0 EIGHT=1 NINE=2 TEN=3 JACK=4 QUEEN=5 KING=6 ACE=7
//   index = suit * 8 + rank
//
// Contract ordinal follows the Java enum:
//   DIAMONDS=0 HEARTS=1 SPADES=2 CLUBS=3 GRAND=4 NULL=5 RAMSCH=6

#ifndef SKAT_TABLES_H
#define SKAT_TABLES_H

#include <stdint.h>

namespace skat {

inline constexpr int kTotalPoints = 120;
inline constexpr int kCards = 32;
/// Follow class of every trump card. The four suits are their ordinal plus one.
inline constexpr int kTrumpClass = 0;
inline constexpr int kContracts = 7;
inline constexpr int kNullContract = 5;

/// A run of cards that are worth the same and sit next to each other in strength.
///
/// The one structure the whole equivalence reduction rests on. In a trick game
/// where only tricks are counted -- bridge -- any two adjacent cards are
/// interchangeable, which is where the classic double-dummy saving comes from.
/// Skat counts card points, so two adjacent cards are interchangeable only when
/// they are also worth the same, and that is a much shorter list: the four
/// jacks (two points each, adjacent at the top of trump) and the nine-eight-
/// seven of every class (nothing each, adjacent at the bottom). Six groups per
/// contract at most, none longer than four.
///
/// It is still worth a great deal, because low cards are exactly what a hand
/// has several of and exactly what gets led when nothing better is available.
struct EquivGroup {
    uint32_t mask;
    /// The group's cards, strongest first.
    uint8_t order[4];
    uint8_t size;
};

struct Tables {
    /// 0 for trump, otherwise the card's suit ordinal plus one.
    uint8_t followClass[kCards];
    /// Same numbers SkatRules uses, so a solved trick and a played one agree.
    uint16_t strength[kCards];
    uint8_t points[kCards];
    /// Every card of one follow class, as a bit mask over card indices.
    uint32_t classMask[5];
    /// Point sums by suit byte, so counting what is left is twelve lookups.
    uint16_t pointsByByte[4][256];
    EquivGroup groups[8];
    uint8_t groupCount;
    /// Every card that belongs to some equivalence group; the early-out.
    uint32_t groupedCards;

    /// Drops every playable card that an equivalent higher one already covers.
    ///
    /// Two cards of the same class, worth the same, with no *live* card of that
    /// class between them are interchangeable: the two positions they lead to
    /// differ only by relabelling them, and nothing else can tell them apart. So
    /// only the strongest of such a run needs searching.
    ///
    /// `alive` must include the cards lying in the current trick as well as
    /// every card still in a hand. A card on the table is gone from the future
    /// but it can still be the card that wins *this* trick, so it separates the
    /// cards on either side of it and must not be skipped over.
    inline uint32_t dropDominated(uint32_t playable, uint32_t alive) const {
        if ((playable & groupedCards) == 0) return playable;
        uint32_t keep = playable;
        for (int g = 0; g < groupCount; g++) {
            const EquivGroup& group = groups[g];
            uint32_t inPlay = playable & group.mask;
            if ((inPlay & (inPlay - 1)) == 0) continue;  // nought or one card
            bool previousWasPlayable = false;
            for (int i = 0; i < group.size; i++) {
                uint32_t bit = 1u << group.order[i];
                if ((alive & bit) == 0) continue;  // already played; not a divider
                if ((playable & bit) != 0) {
                    if (previousWasPlayable) keep &= ~bit;
                    previousWasPlayable = true;
                } else {
                    previousWasPlayable = false;
                }
            }
        }
        return keep;
    }

    /// The card a dominated one defers to: the top of its run of live playable
    /// cards. Returns `card` itself when it was not dominated.
    inline int representativeOf(int card, uint32_t playable, uint32_t alive) const {
        for (int g = 0; g < groupCount; g++) {
            const EquivGroup& group = groups[g];
            if ((group.mask & (1u << card)) == 0) continue;
            // The same walk dropDominated makes, stopped at `card`: whatever the
            // current run of live playable cards started with is what `card`
            // deferred to.
            int runTop = -1;
            for (int i = 0; i < group.size; i++) {
                int other = group.order[i];
                uint32_t bit = 1u << other;
                if ((alive & bit) == 0) continue;
                if ((playable & bit) == 0) { runTop = -1; continue; }
                if (runTop < 0) runTop = other;
                if (other == card) return runTop;
            }
            return card;
        }
        return card;
    }

    /// Card points held across the three hands, by table rather than by loop.
    inline int pointsOfHands(const uint32_t hands[3]) const {
        int total = 0;
        for (int seat = 0; seat < 3; seat++) {
            uint32_t mask = hands[seat];
            total += pointsByByte[0][mask & 0xFF];
            total += pointsByByte[1][(mask >> 8) & 0xFF];
            total += pointsByByte[2][(mask >> 16) & 0xFF];
            total += pointsByByte[3][(mask >> 24) & 0xFF];
        }
        return total;
    }

    /// Which of a completed trick's three cards takes it. Mirrors SkatRules.beats.
    inline int trickWinner(int led, int second, int third) const {
        int best = led;
        if (beats(led, best, second)) best = second;
        if (beats(led, best, third)) best = third;
        return best;
    }

    inline bool beats(int led, int incumbent, int challenger) const {
        int ledClass = followClass[led];
        int incumbentClass = followClass[incumbent];
        int challengerClass = followClass[challenger];
        if (challengerClass != incumbentClass) {
            if (challengerClass == kTrumpClass) return true;
            if (incumbentClass == kTrumpClass) return false;
            return challengerClass == ledClass;
        }
        if (challengerClass != ledClass && challengerClass != kTrumpClass) return false;
        return strength[challenger] > strength[incumbent];
    }
};

/// The tables for a contract ordinal, or nullptr for one this solver refuses.
///
/// Built once on first use and never freed; there are seven of them and they
/// are read-only after construction, so handing the same pointer to every
/// thread is safe.
const Tables* tablesFor(int contractOrdinal);

}  // namespace skat

#endif  // SKAT_TABLES_H
