package dev.skatklar.training.arena;

import dev.skatklar.demo.SkatRules;
import java.util.Map;

/**
 * Which matador rule a measurement is taken under, and the one place the
 * arena decides it.
 *
 * <p>The engine defaults to the canon -- jacks only -- because that is what
 * the app and the server play. The arena defaults to the ISkO instead, for
 * two reasons that both have dates on them: every number in
 * {@code arena/README.md} before 2026-09-13 was taken under the official rule,
 * and the outside engines it measures against bid by it (XSkat's ceiling
 * happens to stop at the jacks anyway; go-skat's does not). A run that wanted
 * the app's rule says {@code --matadors=jacks} and gets it printed in its
 * header, so no log is ever ambiguous about which ladder it was scored on.
 *
 * <p>Switching the arena's default to the canon later -- clamping the training
 * ladder to what the app plays -- is one word in {@link #DEFAULT}.
 */
public final class Matadors {
    public static final String OPTION = "matadors";
    public static final String DEFAULT = "official";

    private Matadors() {}

    /** Reads the option, applies it process-wide, and returns a label for the header. */
    public static String apply(Map<String, String> options) {
        String value = options.getOrDefault(OPTION, DEFAULT);
        SkatRules.MatadorRule rule = switch (value) {
            case "official", "isko" -> SkatRules.MatadorRule.OFFICIAL;
            case "jacks", "canon", "app" -> SkatRules.MatadorRule.JACKS_ONLY;
            default -> throw new IllegalArgumentException(
                    "Unknown --" + OPTION + " '" + value + "'. Use official or jacks.");
        };
        SkatRules.setMatadorRule(rule);
        return rule == SkatRules.MatadorRule.OFFICIAL
                ? "matadors: official (ISkO)" : "matadors: jacks only (the app's rule)";
    }
}
