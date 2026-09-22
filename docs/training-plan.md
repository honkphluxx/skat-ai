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

## 2.8 B2 step 3 answered, 2026-09-20: one net, and it pays at Null

**Share the network.** The mixed net -- the v2 trump corpus plus 323,524
minted Null decision points -- is better at Null than a net trained on Null
alone, and costs the trump positions nothing. Held out, against the same
46.7% baseline:

| | mixed net | the alternative |
| --- | --- | --- |
| the Null corpus | **63.6%**, nll 0.6895 | Null-only net 62.4%, nll 0.7114 |
| the trump corpus | 66.2%, nll 0.6537 | trump-only control 66.2%, nll 0.6529 |

Trump is a dead heat contract by contract, 66.0 to 66.4 with the two
alternating for the lead by a tenth. There is no poisoning to weigh against
the Null gain.

And it reaches the table. Three seeds, `--fixed-contract --contracts=null`,
1,689 played boards, inverse-variance pooled:

| | |
| --- | --- |
| Null, fixed contract | **+0.74** [+0.32, +1.17], resolved; seeds +0.81 / +0.35 / +1.12 |
| Nulls made | 63.5% against 60.2% |
| trump card play | +0.32 [-0.85, +1.48], not resolved |
| full game against the shipped player | -0.01 [-0.73, +0.71], not resolved |

So the mixed belief is a strict improvement: it wins where it was given new
data and is indistinguishable everywhere else. The trump rows are the
result -- adding a contract the auction never reaches should not move games
that never contain it, and it does not.

**Three things worth keeping about how this was measured**, because the first
attempt at the same night produced +0.000 [+0.000, +0.000] and every one of
them had to be fixed before the number above existed.

- The sampler refused to consult any model on a Null, so both sides played
  identically on 539 of 539 boards. That refusal was correct when written and
  its own comment said to remove it the run after a model was trained on
  Nulls. A fact about a model now lives in the model: `belief.bin` format 2
  carries which contracts its corpus held, and a format 1 file is read as the
  old rule rather than as a guess about it.
- `check_data.py`'s census read a bounded sample and a mixed corpus sorts its
  minted shards last, so it reported "Null: none at all" about a corpus with
  323,524 Nulls while the trainer trained on them happily.
- A model trained before the split rule changed cannot be scored on the new
  split at all -- the new held-out tenth is its training data. The trump
  comparison is therefore against a control trained the same night, not
  against the shipped model, which read five points too well.

**The caveat that remains, and its size.** A minted Null has no auction behind
it, so the model learned Null given no bidding evidence, while the app's own
case is a Null a person declared after a real auction. Stress-tested by
pasting real bidding blocks from other deals onto held-out Null records:
64.5% to 63.9%, and merely setting the presence bit costs 0.1. Those pasted
bids contradict the cards in a way real ones never would, so that is an upper
bound on the cost and the corner is not a cliff. Worth minting Nulls behind a
real auction before this is leaned on harder, not before shipping it.

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

- **Null, first round** (2026-09-18 second): 128 worlds is worth **+1.17**
  [+0.37, +1.98] at Null, resolved, and lifts the win rate on makeable
  Nulls from 59% to 64%. The tiebreak idea was refuted at **−1.33**
  [−1.95, −0.72]: a card reaching the tiebreak already survives every
  sampled world, so what is left to choose is robustness to the worlds
  nobody sampled, and there the low card is the wider margin.

**Next, in order:** Null card play, which is now the largest measured
weakness and the cheapest to attack. Built 2026-09-18 and part measured
(`tools/null-card-play.sh`): a Null-only contract source, because at
3% of the oracle mix there is nothing to measure on; a tiebreak that
stops ordering a Null by card points, which the contract does not score
and which inverts Null's own rank wherever a ten meets a court card; and
more worlds for Null, whose solves are the cheapest this player makes.
Then the part no lever reaches: Null in the belief model, which needs a
corpus that contains a Null at all (§B2 step 3 -- minted with
`--contracts=null`, since no bidder announces one), and a measurement of
whether Null belongs in the shared network or in one of its own. Then the rest
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
3. **Null in the model. Answered 2026-09-20: share the network** -- see
   [&sect;2.8](#28-b2-step-3-answered-2026-09-20-one-net-and-it-pays-at-null)
   for the numbers and for the three measurement defects that had to be fixed
   before they meant anything. What follows is the reasoning as it stood
   before the run, kept because the result should be readable against the
   arguments rather than in place of them.

   Two things have to happen here and they are
   usually confused with each other: *getting Null data at all*, and
   *deciding which network learns from it*.

   **The data.** Two corpora have now been counted and both hold exactly
   zero Null decision points — 0 of 89,438 in v1 and 0 of 511,816 in v2 —
   because no seat in any population announces a Null. That is a bidding
   fact, not a training one (`NullAuditMain`: the player will bid 18, hold
   23 and announce Null, but `guaranteedValue(NULL)` is a flat 23 and it
   can never outbid anyone), and waiting for the bidder to be fixed would
   block this indefinitely. So the Nulls are **minted** instead:
   `ExportMain` learns `--contracts=<src>` and exports with
   `NullContractSource`, which prices a Null on about one board in eleven,
   so 200k boards give roughly 18,000 Null games and half a million Null
   decision points. Verify the count is non-zero this time; it is the
   one-line check that would have caught 2.1.

   The caveat, recorded before the corpus is built rather than after: a
   minted Null has no auction behind it, so its bidding block is empty.
   The model would learn where the cards lie in a Null *given no bidding
   evidence*. That is close to the app's own case — our seats only ever
   meet a Null that a person declared — but it is not the same thing, and
   it argues for keeping the minted records distinguishable rather than
   pretending they came from a normal game.

   **Which network — measure it, do not argue it.** This step used to say
   "shared network, not a separate model, because Null decision points will
   be about one in twenty and a separate head would starve". That reason
   expired with the paragraph above: minted data can be as plentiful as we
   like, so scarcity no longer decides anything. Train **both** from the
   same corpus, which costs minutes because the corpus is the expensive
   part, and read three numbers:

   - held-out Null accuracy, mixed net against Null-only net;
   - held-out **trump** accuracy of the mixed net against today's model —
     the number that says whether Null poisoned the 97% of positions the
     player actually meets;
   - the arena, as always, on `--contracts=null` boards and on the ordinary
     gates.

   **The tooling for all of this is `./tools/belief-null.sh`** (2026-09-19):
   mint, check, mix, train both nets, print the three numbers, run the gates,
   resumable step by step like `belief-v2.sh`. It takes `belief-data-v2` as
   its input and refuses to start without it. Two things were fixed to make
   its numbers readable and both are worth knowing about. `ExportMain` gained
   `--contracts=`, measured at 11.0% of boards pricing a Null, so 200k boards
   give roughly 22k games and 430k decision points. And `split_by_board` was
   drawing the held-out tenth from whichever boards were present, which made
   the split a property of the corpus rather than of the deal — so the mixed
   net would have been scored on Null deals it had trained on, and would have
   looked like a net that generalises unusually well. It is now a hash of the
   board id and the seed. A `val_accuracy` stored in a `model.json` written
   before that date was measured on a different tenth; recompute it with
   `eval_belief.py` rather than quoting it.

   That last point has a bigger half, learned the hard way on the first quick
   run (2026-09-19): it is not only the stored *number* that goes stale, it is
   the *model*. A model trained before the rule changed was held out of a
   different tenth, so scoring it on the new one grades it on its own training
   data — the two splits overlapped by 6%, and the shipped model read about
   five points too well, which looked exactly like "Null poisoned the trump
   positions". It had not: on the records genuinely held out of both, the two
   nets were level (62.7% against 62.2%). So the trump comparison is against a
   **control trained the same night**, on the same split, for the same epochs,
   differing only in the Null records — not against the shipped model. Every
   `model.json` now records `split_rule`, `split_seed` and `split_fraction`,
   and `eval_belief.py` warns when they do not match what it is scoring
   against.

   What the arguments are, so the result can be read against them. *For
   sharing:* most of the belief's work is contract-independent bookkeeping
   (voids, cards gone, the 10/10/2 split), which a shared net learns once
   from every record rather than twice from a slice, and the net is already
   shared across contracts that differ a great deal — a Grand has four
   trumps, a suit game eleven — and handles that well; one file also means
   one parity check and one version to get wrong, which matters in a
   pipeline whose failure mode is a model that is silently wrong.
   *Against:* several inputs change meaning on the contract bit.
   `trumps_out`, `trumps_mine` and `jacks_out` are meaningless in a Null and
   are still encoded, so a Null record asserts "no trumps are out", which is
   a false fact rather than an absent one — the same shape as the
   presence-bit defect of 2.1. The void classes move too, since a jack is
   trump in one game and a plain card in the other, and the discard is
   inverted. A shared net must spend capacity learning to ignore inputs
   rather than to use them.

   If the mixed net matches on trump and wins on Null, ship one file. If it
   costs anything on trump, ship two; the Null net can be smaller, since
   Null inference looks like the simpler problem.
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

**Started 2026-09-20. Two things found before building anything.**

*The obvious instrument is circular and cannot be used.* `SolverContractSource`
prices a board by asking whether it is makeable after `Discards.keepBestTen`,
and the player buries that same heuristic's complement — so at
`--contracts=solver` the heuristic keeps a makeable ten **100%** of the time by
construction. Measuring a discard there measures the assumption. The contract
has to come from somewhere that chose it without reference to a discard, which
means the auction.

*And the declarer was burying for the wrong contract.* The engine asks for the
discard before the announcement, rightly, since in a real game the discard is
part of deciding the contract. A fixed-contract match has no auction and lost
that knowledge with it: the declarer buried for whatever `price()` liked about
its own ten cards. `SkatExchangeContext.settledContract` fixes it, and it was
worth **+26.0 points of makeability at Null contracts and +13.0 at solver
contracts** — told the contract, the declarer keeps a makeable ten every time.
The 13 is the missing tenth `arena/README.md` calls "the ten percent the
discard costs". Every fixed-contract Null number here was measured through the
74%.

**The prize, measured honestly.** Contracts from the auction, 352 declared
boards, every one of the 66 discards enumerated against double-dummy defence:

| | |
| --- | --- |
| some discard makes it | 47.2% |
| the heuristic's discard makes it | 40.9% |
| cold either way | 52.8% |

So **+6.3 points, or 13.3% of the winnable boards** — one declared game in
eight that could be won is lost at the discard. That is the ceiling for this
item and it is a generous one: enumerating 66 discards with every hand face up
is not something a player can do, and the double-dummy defence it is scored
against is not the defence it will meet.

- Belief-weighted discard: for each of the 66 discards, the vote over sampled
  worlds, native solver, budgeted. Measured at **oracle contracts against
  par's 90.1%** — the tenth that is the discard is the target.

  **One obstacle, found 2026-09-20 and not yet settled.** "Belief-weighted"
  assumes the belief can be asked at the discard, and it cannot as things
  stand: `BeliefEncoding.Evidence` is built from a `DecisionContext`, which is
  a card-play position, and at the exchange there is no trick, no play history
  and no void. The first version should therefore sample **uniformly** over the
  twenty unseen cards, which is most of the value and needs nothing new; asking
  the belief at the discard is its own piece of work and should be justified by
  the uniform version paying first.

  **Measured 2026-09-21: does not resolve, and not worth its cost.** Three
  seeds, 400 auction boards each, pooled, against the shipped player:

  | worlds a candidate | game pts/game | wins as declarer |
  | --- | --- | --- |
  | 8 | -0.25 [-0.75, +0.24] | +0.30 pp |
  | 16 | +0.19 [-0.27, +0.64] | +1.56 pp |
  | 32 | +0.14 [-0.32, +0.60] | +1.09 pp |

  Read it as a small effect this instrument cannot see rather than as nothing.
  The declarer-win lift at 16 worlds, +1.6 points, is almost exactly what the
  pilot predicted -- a quarter of the 6.3-point ceiling -- and on 28% of games
  declared it is worth something like +0.3 game points, which is inside a
  half-width of 0.45. Resolving it would take about five nights. And it costs
  about ten seconds a discard on the workstation (ten candidates, sixteen
  worlds, 160 solves), so it would not ship to a phone even resolved.
  **Closed at the heuristic.** The world-count axis also refuses to rise: 16
  and 32 are level, so this is not a budget problem either.

  What the item did buy is the settled-contract fix below it, which was the
  larger defect and cost nothing to run.
- Then the line choice, measured as declaring-column distance to par at fixed
  contracts, currently 8.

  **Re-measure that 8 before working on it.** It was `belief` against `solver`
  at fixed contracts, where `solver` is a `TableObserver`, is told the contract
  by `observeFixedContract`, and discards for it -- while our player discarded
  for whatever it would have bid, on 25% of solver-priced boards a different
  pair, and kept a makeable ten 87% of the time against 100%. Part of the 8 was
  that defect, fixed 2026-09-20. It was also the `belief` of mid-August, not the
  player that ships now. `./tools/declaring-par.sh` asks the question again.

  **Measured 2026-09-21: about 5, and the 8 was never a clean number.**
  "From declaring" scores a declarer together with the two players defending
  against it, so two declarers only compare against the same defence. The 8
  subtracted belief's -5.19 (against search's defence, from belief vs search)
  from the solver's +2.91 (against expert's, from expert vs solver). A first
  re-measure made the opposite mistake -- our declarer against the solver's
  double-dummy defence, the solver's against ours -- and read 17.8; five
  different players of ours all scored exactly 38.01% there, because against
  perfect defence a declarer wins about when the ten it kept are cold, and they
  all bury the same pair. Nothing had regressed.

  Measured properly -- solver declaring and ours, both against our defence, on
  the same boards and fixed contracts from greedy's auction:

  | seed | solver declares | we declare | gap |
  | --- | --- | --- | --- |
  | 11 (178 boards) | -2.61 | -5.29 | 2.68 |
  | 12 (171) | -0.45 | -10.29 | 9.84 |
  | 13 (180) | -4.41 | -7.74 | 3.33 |

  **Mean 5.3, roughly [0.8, 9.8]** from the spread over three seeds (the
  per-board files hold only each match's total, so no paired interval). The
  gate of 4 is inside that interval: Phase D may be nearly done or may have
  most of it left, and three seeds cannot say which. Par here is an omniscient
  declarer -- it discards and plays seeing every hand -- so not all of the gap
  is reachable by an honest one.

  **Six seeds, 2026-09-21: 4.99 [2.0, 8.0]** (t interval). Seeds 14-16 added
  6.93, 2.99 and 4.16 on 173, 170 and 177 boards. The gap is real -- the
  interval stays clear of zero -- and the gate of 4 is still inside it, with
  the point estimate a point above. What this does not say is how much of the
  five an honest declarer could ever take: the solver's share includes seeing
  every hand at the discard and at every card, and the one piece of that
  already chased, the discard, came back at about +0.3 and unresolvable.

  **Split, 2026-09-21.** `solver-heuristic-discard` cheats at every card but
  buries our pair (identical on 53 of 53 boards checked), so the solver minus
  it is the discard's share and it minus us is card play's. Six seeds:

  | | share | 95% |
  | --- | --- | --- |
  | the discard | 0.93 | [0.1, 1.8] |
  | card play | **4.06** | [1.6, 6.5] |

  Card play is four fifths of the gap, on every seed, and its own interval
  clears zero. The discard is a point at most, which is why the search came
  back at +0.3: it was fishing a one-point pond. The line choice is where
  Phase D's remaining points are, and this is the instrument that measures it
  -- `--split`, the card-play share, its gate now 4.06 - 4 &asymp; nothing
  for the honest player to lose and up to 4 to find.

  **Where the card play loses it, 2026-09-22: not in won games. In lost
  ones.** Before proposing a fix, an instrument that could name the failure
  mode: `./tools/declaring-audit.sh` (`DeclaringAuditMain`). The per-board
  files in `arena-logs/par/` cannot say -- each row is one side's six-game
  total minus the other's, and the self-match that carries our declarer's
  column is zero on every row by construction -- so the audit replays the
  arena's own declarer-rotation games (same `Seeds.mix`, same fixed contract,
  same defenders; its ZERO CHECK reproduces every seed's "from declaring" to
  the cent), solves the true position before every card, and reads the
  player's own tally through its own session
  (`CardPlayObserver.voted`, a default no-op). Two units worth fixing first:
  "from declaring" is Seeger-Fabian points per game at the table, so the
  card-play share of 4.06 is about **five points of declarer win rate**,
  53.7% against 58.5%, roughly fifty games in 1,049.

  Six seeds, 1,049 declared boards (`arena-logs/par-audit/`; seeds 11-13
  were also run in the Cowork container and the files are byte-identical):

  | | s11 | s12 | s13 | s14 | s15 | s16 | six seeds |
  | --- | --- | --- | --- | --- | --- | --- | --- |
  | boards cold after the discard (won with every hand face up) | 81 | 66 | 70 | 75 | 58 | 74 | 424 |
  | of those, we won | 81 | 66 | 69 | 72 | 56 | 71 | **415 = 97.9%** |
  | card-play share on cold boards | -0.04 | +0.07 | +0.43 | +1.10 | +0.86 | +1.01 | **0.57 [0.1, 1.1]** |
  | boards not cold | 97 | 105 | 110 | 98 | 112 | 103 | 625 |
  | par wins one (always by a defender's mistake; par never throws) | 31 | 40 | 35 | 24 | 27 | 33 | **190 = 30.4%** |
  | we win one | 24 | 24 | 31 | 17 | 26 | 27 | **149 = 23.8%** |
  | card-play share on not-cold boards | +2.62 | +8.33 | +1.89 | +3.63 | +1.35 | +3.11 | **3.49 [0.9, 6.1]** |
  | of it, both lost but charged differently (we are Schneidered about twice as often) | +0.54 | +0.55 | +0.51 | +0.54 | +1.31 | +0.66 | 0.69 [0.4, 1.0] |
  | total, which is the gate's number | 2.58 | 8.40 | 2.33 | 4.72 | 2.21 | 4.11 | **4.06 [1.6, 6.5]** |
  | our throws (a won position played to a lost one) | 5 | 6 | 10 | 9 | 7 | 17 | 54 in 50 games |
  | our declarer decisions with **every card at zero votes** | 32% | 35% | 29% | 36% | 42% | 36% | **2,860 of 8,189 = 35%** |
  | of those, the position was in truth won | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
  | games flat from the opening lead on | 50/75 | 43/86 | -- | 54/85 | 52/90 | 53/78 | about six in ten |

  The total row reproduces `--split`'s 4.06 [1.6, 6.5] exactly, which is the
  zero check at the level of the whole instrument. Of it, the cold boards
  carry **0.57** -- nine games in 424, one in seven of the share -- and the
  not-cold boards **3.49**, six in seven. Seeds 14-16 lose two or three cold
  games each where 11-13 lost one in all, so "never" is wrong; "one board in
  fifty" is right, and those nine are the only places a *better answer to
  the present question* would help.

  So the honest declarer converts 98% of cold boards: the belief is doing
  its job, and the calibration table says so directly -- cards the vote put
  at 12% / 39% / 65% / 89% won in the true world 10% / 34% / 62% / 90% of the
  time. **Six sevenths of the gap is on boards that are lost against perfect
  defence, where the only way to win is a defender's mistake, and par draws
  one in 30% of those games against our 24%.** The mechanism is in the
  zero-votes row: on a third of its decisions the search answers "no card
  reaches 61 in any of the 32 worlds", and the card then falls to the
  tiebreak -- keep the points off the table, then the rule order -- which has
  no opinion about which line keeps a mistake possible, and none about
  Schneider either (28 of our 73 lost not-cold games on seed 11 were
  Schneidered, 14 of par's 66). Par, which asks for the most it can guarantee
  and only then for the result, keeps its tricks and its pressure; ours, told
  it has lost, plays the rest of the hand with no objective at all. Six games
  in ten of those are flat from the first card.

  **What that rules out.** More worlds (the tally is flat, not close), a
  better belief (calibrated), alpha-mu (strategy fusion is about overvaluing
  won lines; these are lost ones), and anything aimed at the discard. What it
  points at is one place in `chooseCard`: **what the declarer asks when the
  answer to "does this reach 61" is no everywhere.** The natural first
  experiment is a ladder -- when the top vote is zero, tally the same worlds
  at the next rung down (61 → 31, the Schneider line, then the best guaranteed
  points), and pick from that. It costs one extra tally on a third of the
  declarer's decisions and nothing on the rest. Its ceiling is the not-cold
  boards par wins and we lose, 70 in 1,049 against 29 the other way -- about
  3.5 points -- and an honest player will not draw every mistake par draws,
  so expect less; the Schneider row, 0.69 [0.4, 1.0], is the part it can
  claim almost by construction. Gate unchanged:
  `./tools/declaring-par.sh --split`, the card-play share, from 4.06.

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
