package dev.skatklar.demo.search;

/**
 * The dials that make one search player play differently from another.
 *
 * <p>The design rule behind every one of them: <b>weaken what the player knows or
 * how hard it thinks, never the rule by which it decides.</b> A player that
 * sometimes throws away a good card at random is not a weaker opponent, it is a
 * broken one — it teaches a human nothing, because its mistakes have no pattern
 * to exploit. A player that has forgotten which trumps are gone makes mistakes a
 * human recognises, can predict, and can punish. That is what an opponent at an
 * easier setting has to feel like.
 *
 * <p>Each dial therefore maps onto a specific, nameable human failure mode:
 *
 * <table border="1">
 *   <caption>What the dials do</caption>
 *   <tr><th>Dial</th><th>Mechanism</th><th>How it shows at the table</th></tr>
 *   <tr><td>Erinnerungsvermögen<br>{@code memory}</td>
 *       <td>played cards and shown voids are dropped from the belief with
 *           probability {@code 1 - memory}, once and for the rest of the game</td>
 *       <td>miscounts trumps, leads into a suit an opponent showed out of,
 *           "forgets" the last ace</td></tr>
 *   <tr><td>Erfahrung<br>{@code worlds}</td>
 *       <td>how many deals are sampled and solved per decision</td>
 *       <td>few worlds is a player going on a hunch: right in clear positions,
 *           erratic in close ones — exactly where a beginner is erratic</td></tr>
 *   <tr><td>Risikofreude<br>{@code risk}</td>
 *       <td>shifts the target the search plays for, up to 29 points either way</td>
 *       <td>bold play chases Schneider and lets games slip; cautious play banks
 *           the win and never presses an advantage</td></tr>
 *   <tr><td>Aggressivität<br>{@code aggression}</td>
 *       <td>how heavily a lost game weighs when it compares declaring against
 *           taking the Ramsch</td>
 *       <td>declares far more or far fewer games — and nothing else. It used to
 *           pick the high card among equally good ones too; that was measured and
 *           it cost four game points a game, so it is gone</td></tr>
 * </table>
 *
 * <p>Deliberately absent: a blunder rate. It is the obvious knob and the wrong
 * one, for the reason above. If a level ever needs to be weaker than
 * {@code memory = 0, worlds = 1} can make it, the honest answer is to shorten the
 * search horizon so the player stops seeing the endgame — a beginner's actual
 * problem — rather than to corrupt its choices.
 *
 * @param worlds     deals sampled per decision, 1 or more; cost is linear in it
 * @param memory     0..1, the chance of retaining any single observation
 * @param risk       -1..1, cautious to bold
 * @param aggression 0..1, timid to reckless in the auction
 */
public record Personality(int worlds, double memory, double risk, double aggression) {

    public Personality {
        if (worlds < 1) throw new IllegalArgumentException("A search needs at least one world");
        memory = clamp(memory, 0, 1);
        risk = clamp(risk, -1, 1);
        aggression = clamp(aggression, 0, 1);
    }

    /** Full memory, no bias, 16 worlds: the player the arena measures as `search`. */
    public static final Personality REFERENCE = new Personality(16, 1.0, 0.0, 0.5);

    /**
     * Four presets that span the range a product would ship.
     *
     * <p>Spaced mostly on <b>memory</b>, which is the dial that moves a level
     * without making it think longer. Measured in game points on 2026-08-21, on
     * the fixed instrument and at fixed contracts, the steps are 10.25, 7.46 and
     * 2.43 — so the ladder is real but closes at the top, which is why
     * {@link #analyst()} also samples twice as many worlds as {@link #expert()}.
     * For scale, the whole distance from {@code expert} to a double-dummy player
     * that sees all three hands is 14.98.
     */
    public static Personality beginner() { return new Personality(2, 0.30, 0.55, 0.75); }
    public static Personality clubPlayer() { return new Personality(6, 0.60, 0.15, 0.55); }
    public static Personality expert() { return new Personality(16, 0.85, 0.0, 0.45); }
    /**
     * Thirty-two worlds again, since 2026-08-21.
     *
     * <p>This level was cut from 32 to 16 on 2026-08-18 because {@code search}
     * against {@code search-32} — the same player at both counts — failed to
     * resolve in three seeds and pointed in opposite directions (+1.2 and −1.7
     * game points a game). That measurement was taken on an arena that gave the
     * two sides different random streams, and it has since been re-run on one
     * that does not: <b>−1.84 game points a game, 95% CI [−3.47, −0.21],
     * resolved in favour of 32</b>.
     *
     * <p>So doubling the sampling is worth about two game points, and the reason
     * it looked worthless was that the instrument could not see two points. It is
     * also exactly what this level needed: the top step of the ladder measures
     * 2.43 points against {@link #expert()} and two of its three seeds do not
     * resolve, which is a level that is barely there. Sixteen more worlds roughly
     * doubles it.
     *
     * <p>Re-measured on 2026-08-24 with the learned belief in place, where it is
     * worth <b>+2.64 game points a game, 95% CI [+1.40, +3.88]</b> — more than
     * the 1.84 it was worth without the belief, and the difference between 16
     * and 32 worlds is what carried this player from 1.48 points behind JSkat's
     * transformer to level with it. Better hypotheses and more of them appear to
     * be complements: a sharper prior makes each extra sample worth more.
     *
     * <p>What it costs is thinking time on a phone, linearly. If that ever
     * becomes the binding constraint, this is the line to change back — but then
     * knowingly, and against a number. The number is now two and a half game
     * points, which is most of a rung on the ladder.
     */
    public static Personality analyst() { return new Personality(32, 1.00, -0.1, 0.35); }

    /**
     * Builds a personality from four 0..100 sliders, which is the shape a settings
     * screen wants. {@code experience} moves the world count geometrically,
     * because the difference between 2 and 4 worlds is felt and the difference
     * between 40 and 42 is not.
     */
    public static Personality ofSliders(int experience, int memory, int risk, int aggression) {
        double steps = clamp(experience, 0, 100) / 100.0;
        int worlds = (int) Math.round(2 * Math.pow(32, steps));   // 2 .. 64
        return new Personality(worlds, clamp(memory, 0, 100) / 100.0,
                clamp(risk, 0, 100) / 50.0 - 1.0, clamp(aggression, 0, 100) / 100.0);
    }

    /**
     * How many card points the search plays for, given what the declarer already
     * has. 61 wins the game; a bold player aims past it and a cautious one aims
     * at it and no further.
     *
     * <p>The asymmetry is on purpose. Aiming <em>higher</em> than 61 is the risky
     * choice for both sides: for the declarer it means chasing Schneider with
     * cards it might need, and for the defence it means playing to schneider the
     * declarer instead of merely beating it. Aiming lower is never useful, so a
     * cautious player simply plays for the plain win.
     */
    public int targetFor(int declarerPointsSoFar) {
        int reach = risk > 0 ? (int) Math.round(29 * risk) : 0;
        return 61 + reach - declarerPointsSoFar;
    }

    /**
     * How heavily this player feels a lost game, as a multiplier on the doubled
     * loss. Neutral is 1.0 — the loss exactly as the score sheet charges it.
     *
     * <p>This replaced a required make chance, and the replacement is the point.
     * A threshold on the make chance is a rule that only works while the
     * alternative to declaring is worth zero, which it was while a passed-in deal
     * was re-dealt. It no longer is: passing means a Ramsch, and what a Ramsch
     * costs depends on the hand. So the player compares two expectations instead,
     * and aggression moves the one thing a person's temperament actually moves —
     * how much the downside weighs. A reckless player discounts it by a quarter;
     * a timid one adds a sixth to it and passes hands it would probably make.
     *
     * <p>Deliberately not a knob on the make chance itself. That would be a
     * player lying to itself about the cards; this is a player valuing the same
     * facts differently, which is what the difference between two people at a
     * table really is.
     */
    public double lossWeight() {
        return 1.5 - aggression;
    }

    /**
     * How reliably the <em>counts</em> survive, as opposed to the identities.
     *
     * <p>A Skat player does not remember thirty cards. They remember that two
     * trumps are still out and that the club jack has not shown, and they
     * remember it long after they have lost track of which spade fell in trick
     * three. Counting is the one bookkeeping task the game trains, so it decays
     * far more slowly than recall of individual cards.
     *
     * <p>The square root is the shape rather than a measurement: it keeps the two
     * ends pinned — a player who remembers everything counts perfectly, one who
     * remembers nothing counts nothing — and lifts everything between, so a
     * player at 50% recall still has its trump count right about 70% of the time.
     * If the arena ever says the levels want a different curve, this is the line
     * to change.
     */
    public double countMemory() {
        return Math.sqrt(memory);
    }

    // Deliberately absent: prefersTheHighCard(), which said an aggressive player
    // would rather take a trick with the big card. It was measured on 2026-08-21
    // and it cost 4.08 game points a game, 95% CI [-5.72, -2.44].
    //
    // The measurement was an accident and is worth keeping for its shape. The
    // aggression sweep entered two variants at 0.8 and 0.2 against the reference
    // at 0.5, each twice, and the fixed-contract runs were only meant to be a
    // control for the auction runs. The 0.2 variant scored *exactly* +0.000
    // against the reference on all three seeds -- both are below the 0.6
    // threshold, so their card play was identical byte for byte. The 0.8 variant
    // crossed it and lost four points. Since aggression touches nothing else
    // once the contract is fixed, that four points was the tie-break and nothing
    // but the tie-break.
    //
    // Keeping your points off the table is simply better Skat, at every
    // temperament. Aggression now moves the auction only, which is also the
    // honest reading of what a bold player is.

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
