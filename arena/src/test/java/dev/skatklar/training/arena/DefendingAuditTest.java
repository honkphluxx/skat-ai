package dev.skatklar.training.arena;

import static org.junit.Assert.assertEquals;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The defending audit's reading of a defender card: what it was doing and whose card held the trick.
 *
 * <p>Three tricks of a Hearts game, laid out by hand, in which every case the
 * report counts appears once: a lead, following suit onto the partner's and
 * the declarer's card, a jack played to a spade lead (a trump, not a spade), a
 * heart trumping in, a void discard. And the two flags the rates rest on: a
 * card with no alternative is no chance, and a gift is a card that turns a
 * lost position won.
 */
public class DefendingAuditTest {

    private static Card c(Card.Suit suit, Card.Rank rank) { return new Card(suit, rank); }

    private static DeclaringAuditMain.Decision d(int trick, int pos, boolean declarer, Card played,
                                                 int legal, boolean before, boolean after) {
        return new DeclaringAuditMain.Decision(trick, pos, declarer, 10 - trick, legal, 0, before, after, played,
                List.of(), null, 0, false);
    }

    @Test public void everyDefenderCardIsPlacedInItsTrick() {
        List<DeclaringAuditMain.Decision> ds = new ArrayList<>();
        // Trick 1: a defender leads the ace of spades, the declarer follows low, the partner follows.
        ds.add(d(1, 0, false, c(Card.Suit.SPADES, Card.Rank.ACE), 10, false, false));
        ds.add(d(1, 1, true, c(Card.Suit.SPADES, Card.Rank.SEVEN), 3, false, false));
        ds.add(d(1, 2, false, c(Card.Suit.SPADES, Card.Rank.TEN), 1, false, false));
        // Trick 2: the declarer leads a club; a void defender trumps in with a heart,
        // the other, void too, discards a ten onto it.
        ds.add(d(2, 0, true, c(Card.Suit.CLUBS, Card.Rank.SEVEN), 9, false, false));
        ds.add(d(2, 1, false, c(Card.Suit.HEARTS, Card.Rank.SEVEN), 4, false, false));
        ds.add(d(2, 2, false, c(Card.Suit.DIAMONDS, Card.Rank.TEN), 5, false, true));
        // Trick 3: the declarer leads a spade; the jack of spades is a trump, not a spade.
        ds.add(d(3, 0, true, c(Card.Suit.SPADES, Card.Rank.QUEEN), 8, false, false));
        ds.add(d(3, 1, false, c(Card.Suit.SPADES, Card.Rank.JACK), 3, false, false));
        ds.add(d(3, 2, false, c(Card.Suit.SPADES, Card.Rank.EIGHT), 2, true, true));
        DeclaringAuditMain.Game game = new DeclaringAuditMain.Game(false, null, ds);

        List<DefendingAuditMain.DefenderCard> cards = DefendingAuditMain.defenderCards(Contract.HEARTS, game);
        assertEquals(6, cards.size());
        expect(cards.get(0), DefendingAuditMain.Kind.LEAD, DefendingAuditMain.Holder.NOBODY, true, false, 11);
        expect(cards.get(1), DefendingAuditMain.Kind.FOLLOW, DefendingAuditMain.Holder.PARTNER, false, false, 10);
        expect(cards.get(2), DefendingAuditMain.Kind.TRUMP_IN, DefendingAuditMain.Holder.DECLARER, true, false, 0);
        expect(cards.get(3), DefendingAuditMain.Kind.DISCARD, DefendingAuditMain.Holder.PARTNER, true, true, 10);
        expect(cards.get(4), DefendingAuditMain.Kind.TRUMP_IN, DefendingAuditMain.Holder.DECLARER, true, false, 2);
        expect(cards.get(5), DefendingAuditMain.Kind.FOLLOW, DefendingAuditMain.Holder.PARTNER, false, false, 0);
    }

    @Test public void theDeclarersCardHoldsUntilItIsBeaten() {
        // The declarer leads the ace of clubs; a defender follows low; the declarer
        // still holds when the second defender plays.
        List<DeclaringAuditMain.Decision> ds = new ArrayList<>();
        ds.add(d(1, 0, true, c(Card.Suit.CLUBS, Card.Rank.ACE), 10, false, false));
        ds.add(d(1, 1, false, c(Card.Suit.CLUBS, Card.Rank.KING), 3, false, false));
        ds.add(d(1, 2, false, c(Card.Suit.CLUBS, Card.Rank.SEVEN), 2, false, false));
        List<DefendingAuditMain.DefenderCard> cards =
                DefendingAuditMain.defenderCards(Contract.GRAND, new DeclaringAuditMain.Game(false, null, ds));
        assertEquals(DefendingAuditMain.Holder.DECLARER, cards.get(0).holder());
        assertEquals(DefendingAuditMain.Holder.DECLARER, cards.get(1).holder());
    }

    private static void expect(DefendingAuditMain.DefenderCard card, DefendingAuditMain.Kind kind,
                               DefendingAuditMain.Holder holder, boolean chance, boolean gift, int points) {
        assertEquals("kind", kind, card.kind());
        assertEquals("holder", holder, card.holder());
        assertEquals("chance", chance, card.chance());
        assertEquals("gift", gift, card.gift());
        assertEquals("points", points, card.cardPoints());
    }
}
