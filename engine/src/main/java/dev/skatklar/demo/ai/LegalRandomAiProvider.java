package dev.skatklar.demo.ai;

import dev.skatklar.demo.Card;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/** Small implementation useful for tests and as a resilient offline fallback. */
public final class LegalRandomAiProvider implements SkatAiProvider {
    private final Random random;

    public LegalRandomAiProvider(Random random) { this.random = random; }

    @Override public SkatAi.AiDescriptor descriptor() {
        return new SkatAi.AiDescriptor("legal-random", "Legal random player", false);
    }

    @Override public SkatAiSession createSession() {
        return new SkatAiSession() {
            @Override public int bid(SkatAi.BidRequest request) {
                return request.requestedBid <= 18 ? request.requestedBid : 0;
            }

            @Override public Set<Card> discardSkat(SkatAi.SkatExchangeContext context) {
                LinkedHashSet<Card> result = new LinkedHashSet<>();
                result.add(context.skat.get(0));
                result.add(context.skat.get(1));
                return result;
            }

            @Override public SkatAi.ContractAnnouncement announceContract(
                    SkatAi.ContractContext context) {
                ArrayList<SkatAi.ContractType> types =
                        new ArrayList<>(context.rules.allowedTypes);
                return SkatAi.ContractAnnouncement.skatGame(
                        types.get(random.nextInt(types.size())));
            }

            @Override public Card chooseCard(SkatAi.DecisionContext context) {
                ArrayList<Card> cards = new ArrayList<>(context.legalCards);
                return cards.get(random.nextInt(cards.size()));
            }
        };
    }
}
