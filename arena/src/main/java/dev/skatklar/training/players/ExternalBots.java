package dev.skatklar.training.players;

import dev.skatklar.demo.ai.SkatAiProvider;
import dev.skatklar.training.arena.Contestant;
import dev.skatklar.training.arena.PlayerRegistry;
import java.util.List;

/**
 * Registers the outside engines that run as helper processes, and only those a
 * checkout has actually built.
 *
 * <p>Availability is a question about the filesystem rather than the classpath,
 * which is why this is a hook rather than a {@code registerIfPresent} entry: a
 * clone that never fetched {@code third_party/xskat} has no binary, no
 * contestant and no failure, exactly as an unfetched JSkat submodule has none.
 *
 * <p>{@code -Dskat.probe=<n>} turns on the honesty control described in
 * {@link ExternalBotProvider}; it costs one extra search per card per world and
 * is meant to be run over a few hundred boards, not over every match.
 */
public final class ExternalBots {

    private ExternalBots() {}

    public static void register(PlayerRegistry registry) {
        int probe = Integer.getInteger("skat.probe", 0);

        String xskat = ExternalBot.locate("SKATKLAR_XSKAT_BOT",
                "third_party/xskat/skatklar-xskat",
                "skat-ai/third_party/xskat/skatklar-xskat",
                "../third_party/xskat/skatklar-xskat");
        if (xskat != null) {
            List<String> command = List.of(xskat);
            registry.register(contestant("xskat", "XSkat 4.0 (Gerhardt)", command, probe, false));
            registry.register(contestant("xskat-blind", "XSkat 4.0, blind", command, probe, true));
        }

        String goskat = ExternalBot.locate("SKATKLAR_GOSKAT_BOT",
                "third_party/go-skat/skatklar-goskat",
                "skat-ai/third_party/go-skat/skatklar-goskat",
                "../third_party/go-skat/skatklar-goskat");
        if (goskat != null) {
            // -skatklar is the flag the additive driver file watches for; without
            // it the binary is ordinary go-skat and would deal its own game.
            List<String> command = List.of(goskat, "-skatklar");
            registry.register(contestant("go-skat", "go-skat (Dranidis)", command, probe, false));
            registry.register(contestant("go-skat-blind", "go-skat, blind", command, probe, true));
        }
    }

    private static Contestant contestant(String id, String displayName,
                                         List<String> command, int probe, boolean blind) {
        return new Contestant() {
            @Override public String id() { return id; }
            @Override public String displayName() { return displayName; }
            @Override public SkatAiProvider newProvider(long seed) {
                return new ExternalBotProvider(id, displayName, command, seed, probe, blind);
            }
            @Override public String toString() { return id; }
        };
    }
}
