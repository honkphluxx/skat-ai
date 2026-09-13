package dev.skatklar.training.arena;

import dev.skatklar.demo.SkatRules;
import java.util.Map;

/**
 * Which rule set a measurement is taken under, and the one place the arena
 * decides it.
 *
 * <p>The engine defaults to the canon -- {@code docs/rules.md}: only jacks are
 * matadors, and a hand game is valued on what was known when it was declared
 * -- because that is what the app and the server play. The arena defaults to
 * the ISkO instead, for two reasons that both have dates on them: every number
 * in {@code arena/README.md} before 2026-09-13 was taken under it, and the
 * outside engines it measures against bid by it (XSkat's own ceiling happens
 * to stop at the jacks anyway; go-skat's does not). A run that wants the app's
 * rules says {@code --rules=canon} and gets it printed in its header, so no
 * log is ever ambiguous about which ladder it was scored on.
 *
 * <p>Switching the arena to the canon later -- clamping the training ladder to
 * what the app plays -- is one word in {@link #DEFAULT}.
 */
public final class Rules {
    public static final String OPTION = "rules";
    public static final String DEFAULT = "official";

    private Rules() {}

    /** Reads the option, applies it process-wide, and returns a label for the header. */
    public static String apply(Map<String, String> options) {
        String value = options.getOrDefault(OPTION, DEFAULT);
        switch (value) {
            case "official", "isko" -> {
                SkatRules.setMatadorRule(SkatRules.MatadorRule.OFFICIAL);
                SkatRules.setHandValueRule(SkatRules.HandValueRule.AS_PLAYED);
                return "rules: ISkO (matadors run into the suit, hand games valued with the skat)";
            }
            case "canon", "app", "jacks" -> {
                SkatRules.setMatadorRule(SkatRules.MatadorRule.JACKS_ONLY);
                SkatRules.setHandValueRule(SkatRules.HandValueRule.AS_DECLARED);
                return "rules: canon (jacks-only matadors, hand games valued as declared)";
            }
            default -> throw new IllegalArgumentException(
                    "Unknown --" + OPTION + " '" + value + "'. Use official or canon.");
        }
    }
}
