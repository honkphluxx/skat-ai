package dev.skatklar.demo.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import dev.skatklar.demo.Card;
import dev.skatklar.demo.Contract;
import dev.skatklar.demo.ai.SkatAi;
import dev.skatklar.demo.ai.SkatAiSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** withForcedDiscard (BidCheckMain's play-outs): it buries exactly the two it is given, and only when it holds them. */
public class ForcedDiscardTest {

    private static Set<Card> discard(SearchAiProvider player, List<Card> skat) {
        List<Card> twelve = new ArrayList<>(PostSkatContractTest.ten());
        twelve.addAll(skat);
        SkatAi.RoundPosition round = SkatAi.RoundPosition.at(0);
        try (SkatAiSession session = player.createSession()) {
            session.prepareDeal(new SkatAi.DealContext(round, SkatAi.Seat.HUMAN,
                    new LinkedHashSet<>(PostSkatContractTest.ten()), PostSkatContractTest.rules()));
            return new HashSet<>(session.discardSkat(new SkatAi.SkatExchangeContext(round, SkatAi.Seat.HUMAN,
                    new LinkedHashSet<>(twelve), skat, Contract.CLUBS)));
        }
    }

    @Test public void itBuriesTheTwoItIsGiven() {
        List<Card> skat = PostSkatContractTest.skat();
        Set<Card> own = discard(PostSkatContractTest.base(), skat);
        List<Card> forced = new ArrayList<>();
        for (Card c : PostSkatContractTest.ten()) if (!own.contains(c) && forced.size() < 2) forced.add(c);
        assertNotEquals("a pair it would not bury itself", own, new HashSet<>(forced));
        assertEquals(new HashSet<>(forced), discard(PostSkatContractTest.base().withForcedDiscard(forced), skat));
    }

    @Test public void cardsItDoesNotHoldAreIgnored() {
        List<Card> skat = PostSkatContractTest.skat();
        List<Card> elsewhere = List.of(new Card(Card.Suit.DIAMONDS, Card.Rank.ACE),
                new Card(Card.Suit.DIAMONDS, Card.Rank.KING));
        assertEquals(discard(PostSkatContractTest.base(), skat),
                discard(PostSkatContractTest.base().withForcedDiscard(elsewhere), skat));
        assertThrows(IllegalArgumentException.class,
                () -> PostSkatContractTest.base().withForcedDiscard(List.of(elsewhere.get(0))));
    }
}
