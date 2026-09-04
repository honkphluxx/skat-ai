package dev.skatklar.demo;

import dev.skatklar.demo.ai.SkatAi;

/**
 * A contract as it would be announced: the game plus everything said on top of
 * it. Used before a game exists — by the bidding panel to price a game the
 * player is only previewing, and by the declaration step to state one.
 */
public record Declaration(Contract contract, boolean hand, boolean schneiderAnnounced,
                          boolean schwarzAnnounced, boolean ouvert) {

    public Declaration {
        if (contract == null) throw new IllegalArgumentException("A declaration needs a contract");
    }

    /** The bare game, skat picked up, nothing announced. */
    public static Declaration of(Contract contract) {
        return new Declaration(contract, false, false, false, false);
    }

    public static Declaration hand(Contract contract) {
        return new Declaration(contract, true, false, false, false);
    }

    /** Whether this combination of announcements is legal for this contract. */
    public boolean legal() {
        return SkatRules.legalAnnouncement(contract, hand, schneiderAnnounced,
                schwarzAnnounced, ouvert);
    }

    /**
     * The same declaration with one announcement toggled, and the implications
     * repaired: schwarz needs schneider and ouvert needs schwarz, so switching
     * one on switches on what it rests upon and switching one off releases what
     * rested on it. A player toggling chips can therefore never build an illegal
     * announcement, without ever being told "no".
     */
    public Declaration with(boolean nextHand, boolean nextSchneider,
                            boolean nextSchwarz, boolean nextOuvert) {
        if (contract.isNull()) {
            return new Declaration(contract, nextHand, false, false, nextOuvert);
        }
        boolean ouvertOn = nextOuvert;
        boolean schwarzOn = nextSchwarz || ouvertOn;
        boolean schneiderOn = nextSchneider || schwarzOn;
        boolean handOn = nextHand || schneiderOn;
        return new Declaration(contract, handOn, schneiderOn, schwarzOn, ouvertOn);
    }

    /**
     * The same pick-up decision applied to a different game, with every
     * announcement dropped.
     *
     * <p>Carrying them across would be worse than useless: Null Ouvert moved on
     * to Clubs would have to grow a schwarz and a schneider to stay legal, and a
     * player who tapped a neighbouring chip would find themselves holding a
     * contract several times the size of the one they were looking at.
     */
    public Declaration withContract(Contract next) {
        return new Declaration(next, hand, false, false, false);
    }

    public SkatAi.ContractAnnouncement toAnnouncement() {
        return new SkatAi.ContractAnnouncement(
                SkatAi.ContractType.valueOf(contract.name()),
                hand, schneiderAnnounced, schwarzAnnounced, ouvert);
    }

    public static Declaration fromAnnouncement(SkatAi.ContractAnnouncement announcement) {
        return new Declaration(Contract.valueOf(announcement.type.name()),
                announcement.hand, announcement.schneider, announcement.schwarz,
                announcement.ouvert);
    }
}
