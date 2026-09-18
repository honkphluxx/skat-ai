# Training plan, September 2026

How to make the strongest player this ground can make, given what it has now:
a working arena, a solver, a search player at parity with the best outside
program, a belief model that is worth a fraction of what a belief could be
worth, two new outside opponents, and two findings that invalidate part of the
record. Every number here is from `arena/README.md` or `docs/xskat.md` unless
it says otherwise.

## 1. Where we stand, in numbers

| what | measured | where |
| --- | --- | --- |
| our best (`belief-32` = `ANALYST`) vs `jskat-ml-pro`, oracle contracts | **+0.19** [−0.95, +1.33] — parity | README, 2026-08-24 |
| the same player vs `jskat-new`, full game | **+12.6** | README |
| what the learned belief buys, 16 worlds, fixed contracts | **+2.37** [+1.03, +3.71] | sharpness sweep |
| what doubling the worlds buys on top of it | **+2.64** [+1.40, +3.88], "not done paying" | 2026-08-24 |
| what a belief that gets **one world in four right** buys | **+25.2** [+20.6, +29.7], and flat from there | belief sweep |
| room left in declaring vs par at fixed contracts | about **8 game points** (solver +2.91, `belief` −5.19) | "why defence is not the place" |
| the discard's share of that | about **a tenth of declared games** | "the ten percent" |
| `xskat` / `go-skat` vs `greedy` | +27.8 / +21.8; tied with each other | xskat.md §9 |
| αµ over plain voting | **nothing**, resolved | README |
| defence as a place to gain | **not**, measured | README |

Read the third and fifth rows together. They are the whole plan. A belief
sampled uniformly is 23 to 27 points below one that is right a quarter of the
time, and ours has closed about **two and a half** of those points. Nothing else
on this list is within an order of magnitude of that gap.

## 2. Two findings, and what each one invalidates

### 2.1 No player of ours ever declared Null (fixed in 09a81fa, 2026-08-28)

`HandEvaluator.promise` paid for matadors it did not hold and ranked Null on the
wrong scale, so `SearchAiProvider` never had Null among its candidates. Every
auction our players took part in before that commit was an auction in which
Null did not exist. What that reaches:

| artefact | status | why |
| --- | --- | --- |
| **the belief corpus, `belief-data/`** | **must be regenerated** | 0 Null decision points in 89,438 sampled. Worse than a gap: every *low* bid and every pass in the corpus was made by a bidder that could not say Null, so what the model learned about what "18" or a pass implies about jacks and aces is the wrong population. The guard that keeps the model off Null contracts kept it *usable*; it did not keep it *right*. |
| **the belief weights** | **retrain** | follows from the above |
| auction-mode ladders and the aggression sweep | **invalidated**, need a night | the bidder changed |
| `tools/challenge-seeds.tsv` | `--purge` and regenerate | old bidder's judgement |
| fixed-contract and oracle-contract results | **survive** | they bypass the auction |

**Softened, 2026-09-09, and this is worth two nights.** The argument above is
that a Null-*capable* bidder is a different bidder, so every low bid and every
pass in the corpus came from the wrong population. Measurement since says the
two bidders are very nearly the same one. The fix landed in 09a81fa; across 51
arena reports after it, the bidder declared Null **zero times**, and the audit
puts its modelled rate at 0.45% of boards -- itself an upper bound. A bidder
that *can* say Null and essentially never does produces almost exactly the
bidding distribution of one that cannot.

So the corpus is not the wrong population; it is the right population missing
a rounding error. **`belief-data/` does not need regenerating for the Null
reason** -- which removes a night of export and a night of training from Phase
B2. It still needs regenerating for anything else that changed the bidder, and
Phase V will change it again, so the export belongs after V rather than before.

What survives from the row is narrower and still true: there are no Null
decision points in the corpus, so the model has never seen Null play and cannot
learn it from this data. Given the contract is worth between -0.17 and +0.09
game points per game, that is a fact to record, not a reason to spend a week.

The last row is why the parity result stands: it was measured at oracle
contracts.

### 2.2 The Ramsch is not the official game, and nobody else plays ours

The canon (`rules.md` §3) plays a Schieberamsch when all three pass; the
Skatordnung passes the deal in. Every auction-mode match therefore has 10–16% of
its boards in a game that **no outside engine plays**: XSkat has a Ramsch of its
own with different options, go-skat has none, JSkat's is a different variant
again — and the arena already hands all three to `RamschPolicy` on those
boards. Three consequences:

- An auction-mode match against an outside engine is, on one board in eight, a
  match against our own greedy Ramsch heuristic wearing its name. The
  `delegated games` count in the external-bots summary is that number.
- The Ramsch policy can only ever be trained by self-play and measured against
  ourselves. That is fine — it is a product feature, not a strength claim — but
  it must not leak into strength claims.
- The belief encoding carries Schieben fields that no outside population will
  ever produce, and a corpus mixing Ramsch and non-Ramsch boards trains one
  model on two games.

**Decision: the measurement canon is the official rule; the product canon is
the Schieberamsch.** The arena grows `--passed-in=void` — a board on which all
three pass scores zero, counts as no game, and carries no signal in the paired
difference, exactly as the pairing already treats an equal-scored board. Every
cross-engine measurement, every calibration, and the belief corpus are taken in
that mode. Ramsch stays in the app, gets its own self-play loop (§4, phase M)
and its own arena mode, and its numbers are reported as its own column.

## 2.3 What Phase R measured, 2026-09-09 — and why the auction moves up the list

Phase R ran and its gate **failed, informatively**. Full account in
[`../arena/README.md`](../arena/README.md); the two numbers that change this
plan:

- **Zero Nulls in ~1,800 declared games**, three seeds, while the oracle prices
  Null as makeable on about 3% of the same boards. The player announces Null
  correctly when it wins an auction; it cannot win one, because
  `guaranteedValue(NULL)` is a flat 23 and the bidder never offers Null Hand
  (35), Null Ouvert (46) or Null Ouvert Hand (59). A hand shaped like a Null
  sits opposite two hands holding every jack, one of which bids 24.
- **`belief-32` beats `xskat` at oracle contracts on all three seeds (+2.6,
  +4.3, +3.2) and is level with it in the full game** (+1.6, −2.7, −0.2). We
  declare 24–26% of boards and win 85% of them; XSkat declares 33% and wins
  77%. The nine boards a hundred we decline and it takes are the entire
  difference.

So the auction is not the fourth lever, it is the second, and it is cheaper
than the belief: the card play is already ahead of a program we are level with
overall. **Phase A moves ahead of Phase D**, and the Null variants are the first
thing in it — a Null that can only ever be bid to 23 is a contract we own on
paper and never play.

## 2.7 Where we stand after the belief retrain, 2026-09-17

The shipped player is `belief-32-adaptive-margin-ties` on the **v2 belief**:
the same network retrained on a void-mode corpus from `greedy, search-4,
club, expert, jskat-new, xskat-blind, go-skat`. Three seeds, void mode;
details in [`../arena/README.md`](../arena/README.md), 2026-09-17.

| v2 belief minus v1, same player | full game |
| --- | --- |
| exact pairing | **+0.69** [+0.04, +1.33], seeds +0.65 / +0.66 / +0.75 |
| paired by board against `xskat`, `jskat-new`, `go-skat` | +0.04, +0.70, +0.17, all non-negative |

- **The population was a limit**, worth about as much as each of the three
  rules. Held-out placement went from 64.6% to 66.3% against the same
  46.7% baseline, and the gain shows against every outsider, not only the
  two whose style entered the corpus.
- **B2's cheap half is done in one night**; the expensive half (Null in
  the model, the true-world-share diagnostic, a longer or wider training
  run -- validation loss was still falling at epoch 20) is what remains
  of B2 and is now worth doing, since the belief has been shown to move.
- **Standing against the field** is the 2.6 table plus about two thirds
  of a point: level with XSkat, three ahead of go-skat, twelve ahead of
  JSkat; card play 3.6 behind SkatZero at oracle contracts and 5.4 behind
  the honest ceiling, both taken with the v1 belief.

- **The ladder is on record** (2026-09-17, second entry): −3.76 / −5.91 /
  −2.91, every step resolved on every seed; the rules are worth +2.85 at
  beginner and +1.69 at club, both resolved now.

- **Card play is level with SkatZero at trump contracts** (2026-09-17,
  third entry): of 507 oracle trump games, `belief-32` loses 56 to the
  cheat and `skatzero` 61; of 18 Nulls, we lose 11 and it loses 4. The
  head-to-head deficit is a Null deficit. The shipped player is
  −2.57 [−3.85, −1.28] against SkatZero, a point closer than the
  2026-09-15 player was.
- **Null is the one unvisited corner left.** The v2 corpus has 0 Null
  decision points in 511,816 records, for the same reason the v1 corpus
  did: nothing in the population announces Null. The belief guard is
  therefore still correct, and Null is played on uniform worlds.

**Next, in order:** Null card play, which is now the largest measured
weakness and the cheapest to attack. Built 2026-09-18 and waiting on one
run (`tools/null-card-play.sh`): a Null-only contract source, because at
3% of the oracle mix there is nothing to measure on; a tiebreak that
stops ordering a Null by card points, which the contract does not score
and which inverts Null's own rank wherever a ten meets a court card; and
more worlds for Null, whose solves are the cheapest this player makes.
Then the part no lever reaches: a Null-declaring member of the export
population, so the belief model stops being blind there. Then the rest
of B2 (the longer or wider training run, the true-world-share
diagnostic); then, if it still looks worth it, the opponent-modelling
cheat as a second ceiling instrument.

## 2.6 Where we stand after the tiebreak run, 2026-09-16

The shipped player is `belief-32-adaptive-margin-ties`: the reference
player with the auction allowed to re-price the hand, card-play ties
broken by a fifteen-point cushion, and the ties that leaves broken by the
position in the trick. Three seeds, void mode; details in
[`../arena/README.md`](../arena/README.md), 2026-09-16.

| shipped minus | full game | change vs 2.5, paired by board |
| --- | --- | --- |
| `belief-32-adaptive-margin` (exact pairing) | +0.23 [−0.19, +0.66] | — |
| `xskat` | +0.72 [−0.64, +2.07] | **+0.80** [+0.26, +1.34] |
| `jskat-new` | **+11.72** [+10.48, +12.95] | +0.38 [−0.48, +1.24] |
| `go-skat` | **+3.01** [+1.61, +4.41] | +0.45 [−0.31, +1.22] |
| `solver` (oracle, `belief-32`) | **−5.38** [−6.61, −4.14] | the ceiling, now honest |

- **The rule tiebreak is small and non-negative everywhere**, resolved
  against XSkat when paired by board. Same standard as the adaptive bidder;
  shipped at every level. The ladder has not been re-measured with it.
- **The ceiling is honest now** (the cheat discards for its contract and
  plays Null with the Null solver) and it says something new: `belief-32`
  and `skatzero` are the same distance from perfect play (+0.23 [−1.34,
  +1.80] between them through the solver), while SkatZero beats belief-32
  by 3.6 head to head. The gap between the two honest players is made
  against imperfect opponents, which is the part determinized search
  cannot see and a policy trained under the fog can. The 5.4 points to
  omniscience are nobody's to teach yet.
- **The deterministic levers are used up.** Jack floor, cushion, position:
  each was structural, each passed the gates, together they are worth
  about two points over the reference. What is left in card play is the
  fog, and that is a learned player (§B2 for the belief, the pilot's
  imitation-then-anchored-PPO for the policy), not another rule.

**Next, in order:** B2's cheap half first -- a void-mode corpus from the
wider population (`greedy, search-4, club, expert, jskat-new, xskat-blind,
go-skat`; `skatzero` only once it has a delegate bidder, since as seated it
never bids and would teach the model that a pass means nothing), the same
model retrained, Null still guarded -- to learn whether the population
matters before the Null work is paid for; then the ladder re-run with the
three switches; then, if the population moved the belief, the rest of B2.

## 2.5 Where we stand after the ship run, 2026-09-15

The shipped player is `belief-32-adaptive-margin`: the reference player with
the auction allowed to re-price the hand and card-play ties broken by a
fifteen-point cushion. Three seeds, 300 boards, void mode; details in
[`../arena/README.md`](../arena/README.md), 2026-09-14 and 2026-09-15.

| shipped minus | full game | change vs 2.4 |
| --- | --- | --- |
| `belief-32` (exact pairing) | **+1.07** [+0.32, +1.83] | — |
| `xskat` | **−0.05** [−1.35, +1.26] | +1.18 [+0.20, +2.16] paired |
| `go-skat` | **+2.57** [+1.18, +3.96] | +0.32 paired |
| `jskat-new` | **+11.35** [+10.14, +12.55] | unchanged |
| `skatzero` (card play, oracle) | **−3.58** [−4.85, −2.31] | new reference |

- **Level with XSkat**, from −0.73 at 900 boards and −1.31 at 300. Both
  mechanisms are structural -- a hard jack floor from the score sheet, a
  robustness term on the vote -- and learn nothing about any opponent, which
  is what the population constraint (§4) requires and why they passed every
  gate at once.
- **The pass side of the adaptive bidder is not a lever**: a harder reading
  sat on top of the reference on every gate. The reference reading stays.
- **SkatZero is the new card-play ceiling on the ladder**, 3.6 points above
  our best at oracle contracts, honest by construction. Self-play deep Monte
  Carlo, no inference, no search. The double-dummy cheat is not a ceiling in
  its current form: it discards with `greedy` and wins only 85% of oracle
  declarations, so both honest players measure level with it.
- **In the app, every level plays with both switches** (from the ladder
  re-run of the same day: beginner +1.73 and club +1.23 over themselves
  without, positive on every seed; the ladder stays four levels a level
  apart). The worry about the cushion at few worlds did not materialise.

**Next, in order:** the deterministic tiebreak rules below
the cushion (position-aware: last to play and winning, take the trick;
losing, give the least; otherwise the lowest of touching cards), one row,
expected small -- built 2026-09-15 as `belief-32-adaptive-margin-ties`,
queued in the overnight script, not yet measured; the cheat's discard fixed
so the distance-to-omniscience row means what it says -- built the same
day (`SolverAiProvider.solvedDiscard`), the two oracle rows to be
re-measured with `--redo=vs-solver-oracle`; then B2, the belief model
retrained on a void-mode corpus that includes `xskat`, `go-skat` and
`skatzero`.

## 2.4 Where we stand after the 900-board run, 2026-09-12

Three seeds at 900 boards each, void mode, every outsider blind-checked. In
[`../arena/README.md`](../arena/README.md), 2026-09-12.

| belief-32 minus | full game | card play (oracle) |
| --- | --- | --- |
| `xskat` | **−0.73** [−1.47, +0.00] | **+3.22** [+2.42, +4.02] |
| `go-skat` | **+1.95** [+1.20, +2.70] | **+8.22** [+7.07, +9.36] |

- **Information leakage is closed.** Paired board by board, blinding either
  engine changes the result by under 0.01 game points a game. The auction was
  already blind from source. Nothing about either opponent is dubious.
- **Card play is ahead of both, resolved.** Belief v2 (B2) remains worth doing
  but it is improving the part that is already winning.
- **The full game is where the points go**, and by an amount that differs by
  opponent -- 3.95 against XSkat, 6.27 against go-skat -- which means "full
  game minus oracle" is not measuring the bidding alone. The oracle's contract
  mix amplifies a card-play edge; a real auction's does not. **That confound is
  inside every auction-cost figure so far**, and the next run removes it.

**Immediate next (queued in the overnight script):** card play at fixed
contracts drawn from a real bidder -- `--contracts=auction --bidder=xskat` and
`--bidder=belief-32` -- so that bidding and contract mix separate by
subtraction. What comes after depends on the answer:

- edge at realistic contracts still near +3 → the bidding costs ~4, build the
  ceiling instrument (does the bid cap, priced on ten cards before the skat,
  lose auctions it should win?)
- edge drops to ~+1 → most of the "auction cost" was contract mix, the bidding
  is only ~1–2 behind, and B2 moves back up the list

## 3. The levers, ranked

1. **The belief model.** +2.4 delivered against +25 available. Before training
   anything, find out *why*: the design named the diagnostic — the share of
   sampled worlds that are the true world, by trick — and never printed it. That
   one number places us on the oracle's curve and tells us whether the ceiling
   is the corpus, the encoding, the network, or how the sampler uses it.
2. **Effort.** Worlds are still paying (+2.6 for the last doubling), and the
   native solver makes them cheap on the workstation. The reference player is
   not bound by the phone's budget, and that separation should be explicit.
3. **Declaring.** Eight points to par, a tenth of it in the discard. A
   belief-weighted discard search is the obvious first bite; the line choice is
   the rest, and it is where a better belief pays twice (the 2026-08-24 note that
   sharper priors and more samples look like complements).
4. **The auction** — *promoted above declaring by Phase R, see §2.3*. The Null
   variants first (35/46/59, so a Null hand can outbid a mediocre suit game),
   then the declining threshold: nine boards a hundred that XSkat takes and
   makes, we pass. A learned bidder is a later question.
5. **Defence.** Measured as not the place. Leave it.

## 4. The phases, each with the measurement that decides it

Every phase ends in a number the arena prints. A phase whose gate does not
resolve is not passed by argument.

### Phase R — re-baseline (two nights, no code)

One command: `./tools/overnight-phase-r.sh`. It builds the helpers, moves
every auction-mode log from before the fix aside (once), and runs
`overnight-arena.sh`, which now carries the outside engines and the honesty
probe as part of the standard night. Stop it with a `STOP` file or Ctrl-C —
the running match is killed within seconds and repeated next time — and
re-run the same command to carry on; two evenings are one run.

- `--seeds` also purges and regenerates `tools/challenge-seeds.tsv` (off by
  default: it rewrites files under `core/` and is a product change to commit
  on its own).
- Every auction-mode ladder entry and the aggression sweep, with the
  Null-capable bidder.
- `xskat`, `xskat-blind`, `go-skat` on the ladder in both modes, our best
  against each at oracle contracts, the leak priced over three seeds.

**Gate:** ladder v3 published in the README, and the contract mix per player
printed beside it — the Null column is the check that the fix reached the
auction.

### Phase V — the void-board mode (a day)

- `--passed-in=void` in `DuplicateMatch`/`GameRunner`; the report keeps the
  Ramsch column and adds a passed-in column.
- The exporter takes the same flag.

**Gate:** in that mode, `delegated games` from the external-bots summary is 0
and the Ramsch rate is 0; the fixed-contract numbers are byte-identical to
before (the flag must not touch them).

**Built 2026-09-09 (d09b943), and the delegation was larger than this section
assumed.** The Phase R logs put it at 6.7-14.9% of games and 36-79 delegated
games in 900, every seed -- about one auction-mode game in ten against an
outside engine played by `greedy` rather than by the engine. The JSkat adapter
does the same thing with `RamschPolicy`, so `jskat-new` was affected too and
its auction-mode matches now run void as well.

Unit-verified at six seeds: every Ramsch becomes exactly one passed-in board,
the ramsch count goes to zero and the game count is unchanged.

**GATE PASSED 2026-09-09**, xskat - greedy, 100 boards, seed 11:
```
ramsch 0.00% / 0.00%   passed in 12.67% / 14.33%
External bots: 776 games, 0 delegated (0.00%), 0 rule divergences
```
Two bugs were found on the way and are worth reading before Phase A, both in
[`../arena/README.md`](../arena/README.md): a passed-in board was paying every
seat a 40-point defender bonus, and the delegation counter was counting the
decision to delegate rather than the stand-in ever playing.

**Phase A is blocked on one decision, not on code.** The bidder weighs
declaring against the Ramsch that passing buys -- but in void mode passing buys
nothing, so under `--passed-in=void` it discounts against a penalty the mode
has removed. Three ways out are set out at the end of the README; the choice
changes what the sweep measures.

### Phase B2 — belief v2 (the week that matters)

1. **Diagnose before training.** Print the true-world share per trick for the
   current model against the uniform sampler, in `train_belief.py`'s eval and
   as an arena-side probe on real games. Expected: low single digits at trick
   one, rising. That number is the baseline everything below is measured
   against, and it decides where to look: a corpus problem shows as a model that
   fits its held-out set and still samples the truth rarely in play; an
   encoding problem shows in `check_data.py` (is the bidding block populated at
   the rate the auction should populate it? after the fix, a Null bid is a
   thing the block must be able to say).
2. **A new corpus, in void-board mode**, from a population that can say Null
   and does not all play alike: `greedy, search-4, club, expert, jskat-new,
   xskat-blind, go-skat`. The two outside engines are there for style
   diversity and because they are licence-clean; `xskat-blind` rather than
   `xskat` so that no decision in the corpus was made with a card the seat
   could not see. 200k boards, `--threads=8`; the exporter already takes
   `--players=`. `check_data.py` before the night, as its README says.
3. **Null in the model**: shared network with the game-type input that already
   exists at offset 264, not a separate model — Null decision points will be
   about one in twenty and a separate head would starve. Verify the count is
   non-zero this time; it is the one-line check that would have caught 2.1.
4. Train. Same recipe, same interpreter warning.

**Gates, all three:**
- true-world share at tricks 1–3 clears the point where the oracle curve
  starts paying — the sweep's lowest registered rungs, `belief-5` to
  `belief-15`, are exactly the yardstick, and they cost one match each;
- `belief-v2` − `belief` at fixed contracts, resolved and positive;
- `belief-v2` − `belief-25` closes rather than holds.

If gate one fails while held-out accuracy is fine, the fault is in how the
sampler uses the model, not in the model — look at `BeliefWorldSource` before
touching the network.

### Phase D — declaring (after B2, because it pays twice with a better belief)

- Belief-weighted discard: for each of the 66 discards, the vote over sampled
  worlds, native solver, budgeted. Measured at **oracle contracts against
  par's 90.1%** — the tenth that is the discard is the target.
- Then the line choice, measured as declaring-column distance to par at fixed
  contracts, currently 8.

**Gate:** each change resolved positive at fixed contracts; the declaring column
moves.

### Phase A — the auction *(now before Phase D)*

1. **Count first, fix second.** `./gradlew :arena:nullAudit
   --args="--player=belief-32 --boards=2000 --threads=8"` asks every seat how
   high it will go and what it would announce, settles the auction between the
   three, and reports how often Null is intended, how often it survives, what
   beat it, and — as an explicit upper bound — how many would have survived at
   35, 46 or 59. Add `--oracle` on a few hundred boards for the rate a bidder
   could approach.

   **It answers two different questions and they lead different ways.** If Null
   is intended at roughly the oracle's 3% and never survives, the 23-point cap
   is the whole story and step 2 is arithmetic. If almost nothing intends Null
   at all, the ceiling is innocent and the fault is upstream — in
   `HandEvaluator.makeChance` or in which contracts `SearchAiProvider.candidates`
   offers — and step 2 would have been the wrong fix.
2. ~~**The Null variants.**~~ **Answered and dropped, 2026-09-09.** The audit
   ran: Null is intended on 13 of 6,000 seats and **9 of those already win the
   auction**. Lifting the ceiling to 59 would rescue the four that did not —
   plus 0.2 percentage points against a gap of 3.9. The ceiling was innocent.
   Full numbers in [`../arena/README.md`](../arena/README.md).

   **The gap is that Null is almost never intended**: 0.45% of boards declared
   against the oracle's 4.34%. Every hand is offered Null by `candidates()`, so
   the loss is in `declaringIsWorth(23, makeChance(NULL))` losing to the same
   for two trump games. Two candidate causes with different fixes, and the
   decomposition that separates them is the next measurement: on the boards
   where the oracle says Null, what did that seat's ceiling, intent and measured
   Null chance look like? Some gap is legitimate — the oracle has the actual
   layout and the bidder samples, and Null safety is unusually sensitive to
   which low cards sit where — but not a factor of ten.

   Second thread, one call further down the same path: the model declares Null
   on 0.45% of boards and Phase R's real matches on none of ~1,800. Something
   after winning the auction loses the rest, and the skat pick-up is the
   suspect.

   **Both are now instrumented.** `--explain=null` dumps the boards the oracle
   calls Null, one line per seat, with each seat's Null chance beside the chance
   and guaranteed value of the trump game it preferred — a high Null chance
   beside a preferred rival means the value comparison loses, a low one means
   `makeChance` is the harsh part. And the audit now follows the skat for any
   Null intent that wins, reporting what it announced once it had seen the two
   cards. Run it over the same 400 boards:

   ```
   ./gradlew :arena:nullAudit --args="--player=belief-32 --boards=400 --threads=8 --explain=null"
   ```

   **Both ran, 2026-09-09, and the thread has moved.** More worlds changes
   nothing (mean P(null) 0.04 → 0.06, largest 0.33 → 0.34) and the routing is
   correct — `NullSolver`, not the points solver. And the "ten times too rare"
   framing was partly wrong: those boards are *selected* by the oracle for
   having a favourable layout, so a low mean sampled chance on them is what
   selection produces. The oracle's 4.34% needs hindsight and is not a target.

   What is left is sharper. Losing a Null costs 46 and winning gains 23, so the
   threshold is about **0.67**, and the largest Null chance on any seat on any
   of those boards is **0.34** — half of it. Null is unreachable by arithmetic.
   On the same boards trump games score up to 0.81, because **double-dummy
   defence punishes Null far harder than a trump game**: beating a Null needs
   one forcing line and a defender who sees everything always finds it.

   **One correction to the numbers above, 2026-09-09.** The audit's 0.45% is an
   **upper bound**, not an estimate. Its auction model asks every seat as though
   it opened, and an opening seat carries a Ramsch discount (`ramschRisk` is
   zero once `currentBid > 0`) that a holding seat does not — correctly, since a
   Ramsch needs all three to pass. So marginal hands look biddable to the model
   that would not be to the real auction, which is why the arena declared no
   Null at all across 51 reports while the model said 0.45%. The bidder is
   right; the model of it was wrong, and the report now prints the caveat. None
   of the conclusions move: the ceiling was cleared against these same inflated
   ceilings, and the arithmetic finding is per seat and never touches the
   auction model.

   **The question is now "are we right to decline", and the arena answers it.**
   The contract table prints declared *and won*, so one oracle-contract run says
   whether we make the Nulls we are handed. Make most of them and the repair is
   a contract-specific correction — Null judged against the defence it will
   actually meet, or a threshold that knows double-dummy treats the two
   contracts differently. Lose most of them and the bidder is right and this
   thread closes.

   **Read the older advice beside a second run at `--bidding-worlds=32`.** The bidder decides
   Null on `clamp(worlds/3, 2, 6)` sampled worlds — six of them for `belief-32`
   — so its Null chance is quantised in sixths, and a Null has to survive nearly
   every world to look sound. If the chance rises sharply with more worlds, the
   harshness is sampling noise and the fix is worlds rather than judgement; if
   it does not, the evaluator genuinely dislikes those hands and that is a
   different repair.
   **CLOSED, 2026-09-09, on the oracle-contract run.** `belief-32 - xskat`,
   300 boards, seed 11, contracts from the double-dummy oracle. Pooling both
   players, because the question is about the contract and not about us:
   non-Null contracts are made **463 of 510 = 90.8%**, Null **13 of 20 = 65.0%**.
   Break-even is 46/69 = **0.667**, so declining is right by a hair and the
   interval ([0.43, 0.82]) says nothing.

   More boards do not help: at p = 0.65 the interval still straddles 0.667 at
   n = 90 and at n = 160, and reaching n = 90 costs about 2,400 oracle boards.
   And the stakes are below the noise floor -- Null is the oracle's best contract
   on 3.8% of boards, so capturing every one perfectly is worth between **-0.17
   and +0.09 game points per game**, against a match margin of +2.565 whose own
   interval is +/-2.10. **Two orders of magnitude under our measurement noise.**

   The mechanism, corrected: nobody plays double-dummy defence in that match, so
   the Null gap is about the *declarer* -- a cold Null has one line and must be
   found blind, a cold trump game survives imprecision. Full entry in
   [`../arena/README.md`](../arena/README.md).

   **What the same run says instead:** at identical contracts we make 93.96% to
   XSkat's 85.66% and win by +2.565, resolved. Our card play is ahead; our
   auction is not. That makes item 3 below the top of the list, not a follow-up.

   **PHASE A ANSWERED, AND IN THE NEGATIVE, 2026-09-09.** Item 3 below assumed
   a boldness dividend. There is none. Measured over 900 hands at 24 bidding
   worlds, each seat's intended contract played out whether or not it would
   have been declared, declaring more costs points monotonically: 4.65 points
   a hand at 21.6% of hands declared, 4.22 at 24.8%, 4.00 at 26.7%, 3.80 at
   30.9%, 3.11 at 42.6%. Today's cut pays 4.55. Reaching XSkat's share would
   cost the better part of a point a hand.

   Three things follow, all recorded in [`../arena/README.md`](../arena/README.md):

   - The estimator **is** systematically pessimistic, as `HandEvaluator` always
     claimed -- but only visibly at 24 worlds. At 6 the top of the range is
     saturated (41% of hands pinned to 0.000 or 1.000) and the bias hides.
   - Correcting it still would not move the auction: the error is +0.18 to
     +0.22 around predicted 0.35-0.45 and about zero above 0.85, and the cut is
     at the top. **The estimator is wrong where the decision is not.**
   - Raising the bidding world cap is not worth it. 4x the worlds for a cut
     difference worth 0.11 points a hand decided by nine held-out hands.

   **So the 2.5 points are elsewhere.** Our card play beats XSkat's at identical
   contracts by +2.565 and the full auction is level. It is now measured not to
   be in how often we declare, which leaves *which contract we choose* and *how
   high we bid*. `overbid (lost)` is 0.00% in every report we have; for a bidder
   that never overbids that is a symptom rather than a virtue, and it is the
   next thing to look at.

3. **The declining threshold.** We declare 24–26% against XSkat's 33% and win
   85% against its 77%. Sweep the threshold and measure; the aggression dials
   already exist and their sweep is redone on the Null-capable bidder.

**Gate:** auction-mode result against `jskat-new`, `xskat` and `go-skat` in
void-board mode, all resolved positive; and `belief-32` − `xskat` in the full
game resolved positive, which it is not today.

~~Null declared at roughly the oracle's rate.~~ **Dropped 2026-09-09**, and it
was never a gate worth passing: the oracle's rate needs hindsight, and the
oracle-contract run showed the whole contract is worth between -0.17 and +0.09
game points per game. A gate is a thing a build can fail on, and this one could
only ever have been failed on noise.

### Phase P — population self-play, the loop (repeat until it stops paying)

Generation *n*: population = the fixed outsiders (`greedy, jskat-new,
xskat-blind, go-skat`) + the best of generations *n−1* and *n−2* → corpus →
train → `belief-n`.

**Gate per generation:** beats generation *n−1* at fixed contracts, resolved;
non-negative against every outsider in void-board auction mode. Two consecutive
failures end the loop. The outsiders never leave the population — a model
trained only on its own lineage is confidently wrong about everyone else, and
the humans who buy the app are everyone else.

### Phase M — the Ramsch, separately

Self-play only, its own arena mode (`--ramsch-only`), its own policy under
`RamschPolicy`, measured against `greedy`'s Ramsch and its own previous version.
Never in a strength claim; always its own column. The Schieben fields in the
encoding are masked in every non-Ramsch corpus.

### Phase C — calibration, once

When Phase P has produced something worth calibrating: one session on ISS
against the Muppets through go-skat's `client_iss.go`, and the ISS archive as a
contract oracle offline if permission ever arrives. Days of wall clock for one
interval, no pairing, no common random numbers — a number to write down, not a
loop to run.

### Phase S — shipping the effort

The reference player runs at whatever worlds the workstation affords (64, 128 —
measure where it stops paying, the curve is not flat yet). The phone runs at 32.
The belief already ships as plain arrays; the gap between the two is a stated
number in the README, not a surprise.

## 5. What not to do

- Do not retrain on `belief-data/` as it is. Every record in it was made by a
  bidder that could not say Null.
- Do not quote any auction-mode number from before 09a81fa.
- Do not put ISS on the critical path. Nothing here needs it.
- Do not revisit αµ, the sharpness exponent, or defence — all three are
  measured and closed.
- Do not compare against kermit locally; there is no such thing.
- Do not let a Ramsch board into a cross-engine or calibration measurement.

## 6. Order and cost

R (2 nights) → V (1 day) → **A (2 nights)** → B2 (1 week: a day of diagnosis, a
night of export, a night of training, two nights of gates) → D (1 week) → P
(a generation a week, as long as it pays) → M in parallel with P → C once →
S last. **A moved ahead of B2 as well as D, 2026-09-09**: the oracle-contract
run put our card play clearly ahead of XSkat's (93.96% to 85.66% at identical
contracts) and left the auction as the only place we are behind, so two nights
of threshold sweep now outrank a week of belief work. V stays in front of A
because A is measured in auction mode, and auction mode is not yet honest
against outsiders -- see Phase V. Nights are the arena's: `--threads=4` with ML players, 8 without; XSkat
costs nothing, go-skat about 8 games a second.

## 7. Done means

- `belief-vN` − `jskat-ml-pro` at oracle contracts resolved **positive** (from
  +0.19, not resolved).
- Declaring column within **4 game points of par** at fixed contracts (from 8).
- Non-negative, resolved, against `xskat`, `go-skat` and `jskat-new` in
  void-board auction mode.
- Null decision points present in the corpus at all, which they were not before
  09a81fa. ~~Null declared at roughly the oracle's rate.~~ Dropped 2026-09-09:
  measured at break-even and two orders of magnitude below the noise floor. A
  bidder that never announces Null may still be a product question; it is not a
  strength question.
- The true-world share printed in every training log, so 2.1 cannot happen
  again without being seen.
