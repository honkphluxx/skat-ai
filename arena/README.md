# SkatKlar training ground

> **Written by a language model.** Every line of this file and of the code it
> describes was written by an LLM, under a person's direction. The numbers are
> not claims about that code -- they are records of running it, reproducible
> from these sources with the seeds given. The prose is fluent either way,
> which is exactly why this document leans on intervals and on the runs that
> came out flat. See the project README for the longer version.

The third build target. A plain JVM module that runs on the Windows workstation
and never ships. Phase 0 of [`docs/training-ground.md`](../docs/training-ground.md)
lives here: the arena that turns "is this AI better?" into a number with an
error bar.

## Running a match

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :arena:arena --args="--a=greedy --b=random --boards=1000"
```

```
--a=<player>    first contestant (required)
--b=<player>    second contestant (required)
--boards=<n>    boards to play; each is 6 games            (default 200)
--seed=<n>      match seed; identical seeds replay exactly (default 1)
--threads=<n>   parallel boards                            (default 1)
--contracts=<s> where a fixed contract comes from: auction (default) or solver
--csv=<path>    write the per-board differences
--quiet         suppress progress output
```

Players are registered ids or `class:<fully.qualified.ProviderClass>` for
anything implementing `SkatAiProvider` on the classpath. All JSkat entries are
resolved reflectively, so the arena still runs when one is unavailable — run the
arena with no arguments to see which resolved.

| id | what |
| --- | --- |
| `random` | random legal card, the floor |
| `greedy` | deterministic heuristic baseline (this module) |
| `solver` | double-dummy par -- sees every hand, cheats on purpose |
| `search` | determinized search, honest, 16 worlds per decision |
| `search-4` … `search-64` | the same at other sample counts |
| `beginner` `club` `expert` `analyst` | the same player at four personalities |
| `jskat` | whatever the app currently seats — now `AlgorithmAI` |
| `jskat-new` | `newalgorithm.AlgorithmAI`, explicitly |
| `jskat-algorithmic` | `algorithmic.AlgorithmicAIPlayer`, superseded |
| `jskat-ml` | `ml.MLPlayer`, dense ONNX nets trained on ISS |
| `jskat-ml-pro` | `ml.MLPlayerPro`, transformer ONNX nets |

`jskat` deliberately tracks the app's default so that "measure the shipped
player" keeps working when the default moves; it is currently the same player as
`jskat-new`. `jskat-algorithmic` is the superseded one, kept so the change stays
measurable. The two ML players need ONNX Runtime
(declared by this module, deliberately not by `:jskat-ai`, so it stays out of the
APK) and the model files: run `gradlew :jskat-base:downloadMlModels` inside
`third_party/jskat` once, or pass `-Djskat.models.dir=<path>`.

## Measured so far

All at fixed contracts (auction by `greedy`), 1301 scored boards, seed 1.

| player | declarer win rate |
| --- | --- |
| `jskat-ml-pro` / `jskat-ml` | **69.3-69.8%** |
| `jskat-new` (app default) | 58-60% |
| `greedy` | 34-50% |
| `jskat-algorithmic` (superseded) | 28-33% |

| match | TP/game | 95% CI |
| --- | --- | --- |
| `jskat-ml-pro` - `jskat-new` | **+28.4** | [+26.2, +30.7] |
| `jskat-ml-pro` - `greedy` | **+33.5** | [+31.0, +36.0] |
| `jskat-new` - `greedy` | **+7.0** | [+4.9, +9.2] |
| `jskat-new` - `jskat-algorithmic` | **+25.7 to +30.8** | varies, see below |
| `greedy` - `jskat-algorithmic` | **+16.7** | |
| `jskat-ml-pro` - `jskat-ml` | -0.7 | [-2.3, +1.0] **not resolved** |
| `jskat-ml-pro` - `jskat-ml` (auction, 800) | +0.1 | [-2.6, +2.8] **not resolved** |

The measurement of `jskat-new` against `jskat-algorithmic` moved between runs
because JSkat's players are not reproducible (see below). The ordering never did.

**Read only the pairwise numbers, never the absolute win rates across matches.**
A declarer's win rate moves a long way with the company it keeps: `greedy` wins
50% of its declared games against `jskat-algorithmic` and 34% against
`jskat-ml-pro`. The pairwise differences are roughly additive -- +28.4 plus +7.0
predicts +35.4 where +33.5 was measured -- but do not count on that either, since
each match fills the other two seats with the opponent.

### Why the two ML players tie

**82% of their boards scored alike**, and about 8,200 boards would be needed to
resolve what is left. That is not noise: `MLPlayer` and `MLPlayerPro` share the
same `card_play_transformer` for card play and differ only in bidding and game
evaluation -- exactly what `--fixed-contract` removes. The mode cannot separate
them by construction. The residual 18% comes from the discard, which uses the
game-evaluation model.

The obvious next step was to run them in default (auction) mode, where their
bidding and evaluation models *are* in play. **That does not separate them
either**: 800 boards gave +0.119 TP/game, 95% CI [-2.587, +2.826], with 68% of
boards scoring alike and an extrapolated 412,645 boards needed for the observed
edge. At 1 game/s that is not a measurement anyone will ever take.

Treat `jskat-ml` and `jskat-ml-pro` as **one opponent** at the strength listed
above. Which of the two you seat is not a decision worth spending sample on.

### What the auction-mode run did show

Two things, neither of them the thing it was run to measure.

**Their announcements are being overridden.** 800 boards produced 102 and 81
`ANNOUNCE` rule violations -- roughly one board in eight. The ML players announce
contracts the demo variant does not permit, `GameEngine.fallbackAnnouncement`
substitutes a legal one, and the game is then played at a contract the player did
not choose. Every ML number in auction mode is therefore a measurement of a
hobbled version of these players. The fixed-contract numbers are unaffected,
because there no player announces anything. Widening the permitted contract set
is a prerequisite for any future auction-mode comparison against them.

**They declare Grand in more than half of their declared games** -- 423 and 380
Grands over 800 boards, against roughly 24 in about 1700 for `greedy`. Two
readings, and they are not exclusive: models trained on ISS may simply have
learned that Grand is undervalued by club players, or the announcement override
above is pushing them into Grand. Which it is has to be settled before the number
means anything, and the way to settle it is to stop overriding them.

Both were found by the violation counters rather than by the score line, which is
the argument for keeping them in the report.

### The ladder, measured — in tournament points, superseded

Everything in this subsection is in Seeger-Fabian tournament points, which is
what the arena reported until 2026-08-17. It is kept because the reasoning in it
still holds; the numbers are not comparable with the ones in the next subsection.

Overnight run, seed 11, 178-300 scored boards per match, zero rule violations
except where noted.

| match | mode | TP/game | 95% CI | declarer win rate |
| --- | --- | --- | --- | --- |
| `solver` - `greedy` | fixed | +31.9 | [+27.2, +36.6] | - |
| `search` - `greedy` | **auction** | +21.8 | [+15.8, +27.7] | 74.0% vs 51.8% |
| `search` - `jskat-new` | **auction** | +2.4 | [-3.8, +8.6] | 65.3% vs 58.1% |
| `search` - `jskat-ml-pro` | oracle | -9.9 | [-15.8, -4.0] | 83.7% vs 91.6% |
| `expert` - `solver` | fixed | -24.5 | [-29.8, -19.3] | 37.6% vs 66.9% |
| `search` - `search-32` | fixed | -3.3 | [-8.1, +1.4] | **not resolved** |
| `search-32` - `search-64` | fixed | -2.6 | [-7.4, +2.2] | **not resolved** |
| `beginner` - `club` | fixed | -17.0 | [-23.2, -10.8] | 43.5% vs 61.6% |
| `club` - `expert` | fixed | -13.6 | [-18.6, -8.6] | 57.3% vs 73.6% |
| `expert` - `analyst` | fixed | -8.8 | [-14.0, -3.5] | 56.7% vs 67.4% |

Four things worth reading out of that.

**Sampling saturates early.** 16 to 32 worlds is +3.3 and 32 to 64 is +2.6, and
neither resolves — together they are worth less than the step from 4 to 16
(+14.1). Sixteen determinizations is a reasonable default and the Android time
budget is not going to be the binding constraint. Spend the compute on the
belief instead.

**With the auction live, the search player is level with the shipped default.**
`search` - `jskat-new` = +2.4, interval spanning zero: on current evidence they
are the same strength, and this player has not a single learned parameter in it.
It also declares 37.6% of its games against jskat's 22.9%, so it is not buying
that with abstention.

**The level spacing tightened at the top** — 17.0, 13.6, 8.8 — which followed
from the sampling curve above: `expert` and `analyst` differed mostly in world
count, and world count has stopped paying by then. Respacing them on **memory**,
with the world count merely following, measured much straighter:

| step | worlds | memory | TP/game | 95% CI | declarer win rate |
| --- | --- | --- | --- | --- | --- |
| `beginner` → `club` | 2 → 6 | 30% → 60% | **20.5** | [14.9, 26.0] | 46.0% vs 66.4% |
| `club` → `expert` | 6 → 16 | 60% → 85% | **19.3** | [14.6, 23.9] | 52.5% vs 74.3% |
| `expert` → `analyst` | 16 → 32 | 85% → 100% | **12.0** | [7.7, 16.3] | 54.3% vs 69.1% |

Two even steps and a smaller one, against 17.0 / 13.6 / 8.8 before. The top step
is still the shortest, and the reason is visible in the same row: 49% of its
boards scored alike for both sides, against 29% at the bottom of the ladder.
Two strong players simply agree on most boards, so there is less to separate
them — a ceiling effect rather than a badly chosen dial.

That is close enough to ship as four levels. Whoever wants them exactly even can
push `expert` down to 80% memory; the honest note is that perfect memory is a
different kind of player rather than a slightly better one, and the last step
buys the difference between counting well and counting exactly.

### The ladder, re-measured in game points

Overnight run of 2026-08-17/18, seeds 12 and 13 at 200-300 boards a match, in
**classic game points per game** — the currency the canon settles in. Seed 11 ran
at a fifth of the boards as a smoke test and is left out of the averages; where
it disagreed it was noise, which is worth knowing before reading a 40-board
result again. Each figure is the mean of the two full seeds; *ns* marks a step
that did not resolve in at least one of them.

| match | mode | game pts/game | resolved |
| --- | --- | --- | --- |
| `beginner` → `club` | cardplay | **10.9** | yes |
| `club` → `expert` | cardplay | **6.9** | yes |
| `expert` → `analyst` | cardplay | **3.8** | yes, all three |
| `beginner` → `club` | **auction** | **8.0** | yes |
| `club` → `expert` | **auction** | **6.5** | yes |
| `expert` → `analyst` | **auction** | **2.6** | one seed of three |
| `expert` - `solver` | cardplay | −15.0 | yes |
| `solver` - `greedy` | cardplay | +16.3 | yes |
| `search` - `search-32` | cardplay | −0.3 | **no, either seed** |
| `search` - `solver` | **auction** | +0.9 | **no, either seed** |
| `search` - `greedy` | **auction** | +18.8 | yes |
| `search` - `jskat-new` | **auction** | +10.5 | yes |
| `search` - `jskat-ml-pro` | oracle | −2.8 | one seed |

Ramsch rate 10.5-16.0% across every auction match, steady across seeds and
matchups, which is the q ≈ 0.49 calibration of the bidding rule holding up.

The card-play rows source their contracts from greedy's auction, which
over-declares: that is why every absolute figure in them is negative, the solver
included. Sourcing them from `search` instead was tried and dropped — it costs
5.3 s a board on top of the match, roughly doubling a seed, and it raises the
share of boards on which both sides score alike from 26% to 37%, because safer
contracts leave less to separate two players with. A more realistic distribution
at the price of a worse instrument, and the realistic distribution is what the
auction rows already measure.

Four things to read out of it.

**The ladder survives the change of currency and the arrival of the auction.**
10.9 / 6.9 / 3.8 at fixed contracts, 8.0 / 6.5 / 3.3 with the auction live. The
auction-mode ladder is the *more even* of the two, which was not the expectation:
the bidding rule differs between levels only through `lossWeight`, and it turns
out to separate them about as well as card play does.

**The top step survived halving its own compute**, which is the test of the
paragraph below rather than a separate finding. `analyst` dropped from 32 worlds
to 16 on 2026-08-18 and the step was re-measured on three seeds at 200 boards:

| mode | with 32 worlds | with 16 worlds |
| --- | --- | --- |
| cardplay | 3.8 (two full seeds) | **3.8** (three, all resolved) |
| auction | 3.3 (two full seeds) | **2.6** (three, one resolved) |

Card play came back at 3.82 against 3.81, which is a closer agreement than the
prediction deserved; the auction figure is lower but sits well inside intervals
that are ±2.5 wide. Half the thinking time, the same distance from `expert`.
The two rows in the table above are the new numbers.

**The world count is dead above sixteen.** `search` - `search-32` did not resolve
in any of three seeds, and the two full ones point in opposite directions
(+1.2 and −1.7). The step from `expert` to `analyst` is therefore carried almost
entirely by memory 0.85 → 1.00, and `analyst`'s 32 worlds were twice the compute
for nothing measurable. **`analyst` runs at 16 worlds since 2026-08-18.** The top
step is short because it has nothing left to spend: memory is already at 1.0
there, so lengthening it needs a dial that does not exist yet.

**Bidding is worth about as much as perfect card play.** `search` - `solver` is
+0.9 and does not resolve — our honest player is level with the double-dummy
cheat, in auction mode, on 500 boards. The cheat is perfect at card play and bids
with a greedy delegate that declares 41-43% of deals; `search` declares 15-17%
and plays them well. Under a ruleset that pays a defender nothing, choosing
which games to play is worth roughly the 15 points that perfect information buys.

**JSkat's ML player still plays cards slightly better.** −2.8 at objective
contracts, resolved in one seed of two. The gap has closed from −9.9 in the old
currency, but the sign has not changed, and it is card play rather than bidding —
oracle mode hands both sides the same contract. That is the gap a belief model
would have to close.

**JSkat's newalgorithm player committed rule violations** — 11 in 1500 games,
where our side committed none. Found and fixed: three separate crashes in its
Grand players, all upstream, all throwing rather than playing a wrong card, so
the engine substituted a legal card and the player silently played somebody
else's game. See `third_party/jskat/ANDROID_INTEGRATION.md` §6. The same match
now reports zero violations, so `jskat-new` numbers taken before this fix
understate it slightly.

### The belief sweep, and what it answered

`belief-0` … `belief-100` are the same search at the same effort, differing only
in what share of their sampled worlds is the deal actually on the table. They
cheat, they are instruments rather than players, and they exist to answer one
question before a line of a belief model is written: **how accurate does a belief
have to be before it pays?**

Measured against `belief-0`, 265 scored boards each, seed 1:

| belief | TP/game vs `belief-0` | 95% CI | declarer win rate |
| --- | --- | --- | --- |
| 25% right | **+25.2** | [+20.6, +29.7] | 67.2% vs 34.0% |
| 50% right | **+27.5** | [+22.8, +32.1] | 67.6% vs 30.9% |
| 75% right | **+24.9** | [+20.4, +29.3] | 64.5% vs 30.2% |
| 100% right | **+22.7** | [+18.3, +27.1] | 62.3% vs 30.6% |

**The curve is flat from 25% on.** Neither of the two shapes the experiment was
built to distinguish: the entire 23-to-27-point gap between a uniform guesser and
perfect information is already bought by getting one world in four right, and
everything above that buys nothing measurable.

That is not the surprise it first looks like. **The vote aggregates, and correct
worlds agree with each other while wrong ones scatter.** Fifteen wrong
hypotheses do not vote as a bloc — they spread across the legal cards — so a
coherent minority of four correct ones carries the decision. What a belief model
needs is not to be right most of the time; it needs to put real mass on the true
configuration *often enough*, which is a far weaker requirement and one that
self-play data can meet.

The gentle decline from 50% to 100% is the same effect the solver already
showed, now visible along a second axis: as the belief approaches the truth the
player approaches double-dummy play, which is maximin and does not exploit a
weak opponent. Reading down the declarer win rate — 67.6%, 64.5%, 62.3% — is
reading a player becoming steadily more correct and steadily less effective
against opponents who blunder. The intervals overlap, so treat the ordering as
suggestive rather than resolved, but it matches what `solver` does.

**Belief pays for the declarer and barely for the defence.** From declaring,
-23.5 becomes +0.8. From defending, 10.8 becomes 11.7. A declarer has to plan a
whole hand — which finesse, when to pull trumps — and that plan is what a wrong
world ruins; a defender's decisions are far more locally determined.

`belief-5` through `belief-15` are registered to find where the curve actually
takes off, since 25% is already past the interesting part:

```powershell
.\gradlew.bat :arena:arena --args="--a=belief-5 --b=belief-0 --boards=300 --fixed-contract --threads=4"
.\gradlew.bat :arena:arena --args="--a=belief-10 --b=belief-0 --boards=300 --fixed-contract --threads=4"
.\gradlew.bat :arena:arena --args="--a=belief-15 --b=belief-0 --boards=300 --fixed-contract --threads=4"
```

Mixing in the truth is a crude model of accuracy: a real belief is wrong in
structured ways, confident about jacks and vague about sevens, while this is
right or uniformly wrong with nothing between. In one direction that makes the
instrument optimistic — an exactly correct world is more than a good model
usually produces. In the other it is pessimistic, because a real model's near
misses still carry the right jacks and the right voids, and the vote does not
need a world to be perfect to be useful.

### Null

Null is not a harder trump game, it is a different one: the declarer wins by
taking **no trick at all**, and a search counted in card points recommends the
opposite of the right card there. `NullSolver` is therefore a second solver
rather than a flag on the first — a boolean minimax where the declarer needs one
surviving move, the defence needs one killing move, and the game ends the moment
the declarer wins a trick. That last property makes it cheap: a full ten-card
Null solves in about a millisecond, where a suit game costs hundreds.

Everything else in the player is unchanged. The sampler, the personality dials
and the vote all work exactly as before — only the question put to the solver
differs, and the discard inverts (bury the two highest cards, not the points of
a short suit). That the dials needed no attention at all is the useful signal:
they act on what the player knows and how hard it thinks, not on what it is
trying to achieve.

The contract oracle prices Null too, and finds it on about one board in sixteen
— which also lowered the share of boards with no game at all from 23% to 20%.

Two things follow for reading numbers:

- The search player now makes **its own discard** instead of borrowing the
  greedy heuristic's, because the right two cards depend on the contract and
  nothing else in it would get Null right otherwise. Earlier `search` numbers
  were taken with the old discard.
- Its vote is on **one threshold**, "can the declarer still reach 61". It does
  not separately defend Schneider once a game is decided, which the solver
  player does. That is a deliberate cost decision: the extra thresholds would
  double the searches per decision to chase a multiplier that Seeger-Fabian
  barely pays.

### The ceiling: what `solver` is, and what it is not

`solver` is the arena's par baseline. It reads every hand out of the engine
through `TableObserver` -- a seam that exists only in this module, so nothing
that ships can acquire it -- and plays the resulting perfect-information game
optimally. Bidding, pick-up and discard are delegated to `greedy`, so it cheats
in card play and nowhere else.

It searches for the **result**, not for the last card point: 61 wins, 90 is
Schneider, and two lines that both end at 75 are worth the same. Asking only
those questions costs about a tenth of what an exact value costs, and lands in
the same band every time -- `DoubleDummySolverTest` asserts exactly that. Once
the band is settled it does maximise points, because the game value still reads
them.

**It is a par, not an upper bound.** The distinction turned out to matter more
than expected:

- **As a defender it is close to a true ceiling.** It knows every card, and the
  two defenders act as one player -- a coordination no real pair has. A declarer
  facing it is behind by more than its own mistakes.
- **As a declarer it is deliberately pessimistic.** Double-dummy play assumes
  the defence is perfect, so on a contract that cannot be made against perfect
  defence it plays for the best it can *guarantee* -- and never for the line that
  a weak defender would misplay. A real player facing weak defenders will beat
  par as declarer, and should.

So "beat the solver" is not a coherent goal, but "beat the solver *from the
declarer's chair*" is an ordinary one, and a player that does it is exploiting
opponents rather than outplaying the ceiling. Read a match against `solver` as
two separate numbers -- the report already splits declaring from defending.

Measured, 265 scored boards at greedy's contracts, seed 1: `solver` - `greedy` =
**+32.8 TP/game** [+28.0, +37.6], declarer win rate **66.0% against 28.7%**. And
directly against the strongest player we have, 171 boards at oracle contracts:
`solver` - `jskat-ml-pro` = **+7.0 TP/game** [+1.1, +13.0], declarer win rate
**90.1% against 81.3%**.

That second number is the useful one, and it is smaller than expected. Par beats
the ONNX player by seven tournament points, not by thirty, and the whole gap is
in declaring (+28.2 against +20.3); defending is a wash (+2.9 against +3.7),
because at oracle contracts the declarer wins four games in five and there are
few defender bonuses to collect either way. **`jskat-ml-pro` plays close to par**
on contracts that are objectively there. Whatever we build has to clear that,
and the room above it is seven points wide.

### The ten percent the discard costs

At `--contracts=solver` every board is makeable by construction, so a player that
saw all the cards and drew perfectly would win 100% of its declared games. The
solver wins **90.1%**. It plays optimally, so the missing tenth is not card play:
it is the discard, which the solver delegates to `greedy`, plus the heuristic
discard the oracle assumed when it priced the board. Two heuristics disagreeing
about which two cards to bury costs one declared game in ten.

That is a large number for a decision that gets almost no attention next to card
play, and it is worth remembering when reading any fixed-contract result: the
instrument fixes the contract, not the exchange.

### The honest search player

`search` is the first player here that could ship. It sees only its own cards.
For each decision it draws deals consistent with everything it has observed,
asks the solver which cards still win in each of them, and plays the card that
wins in the most worlds -- sample, solve, aggregate, the standard construction
for trick games with hidden cards.

Two things it deliberately does not do:

- **The sampler is uniform** (`core/.../search/WorldSampler`). It filters on
  facts -- cards seen, how many each seat holds, void suits shown by a failure to
  follow, and the declarer's own discard -- and weights nothing. The bidding is
  the strongest soft signal available and is ignored on purpose: that is the
  belief model, and Phase 4 is measured as the tournament points it adds *over
  this player*. A half-hearted version of it here would make that comparison
  meaningless.
- **The vote is on results, not points.** A card scores a world if the declarer
  still reaches the target from there. Skat pays for 61 and 90, not for the
  difference between 74 and 75, and the threshold question is an order of
  magnitude cheaper than the value.

`WorldSamplerTest` checks the two properties that matter, at every decision of
real games rather than on a constructed position: every world it draws could be
the deal actually on the table, and the deal actually on the table is one it
could draw. A sampler that excludes the truth is wrong in a way no amount of
sampling repairs.

**Strategy fusion is not fixed here**, and no sample count fixes it. Solving each
world separately lets the player assume it will play differently in each of them,
so it overvalues lines whose success depends on knowing something it will not
know. That is the known weakness of this whole family.

Cost is linear in the world count, so `search-4` through `search-64` are all
registered: "how many worlds is enough" is a question to answer with a number.

```powershell
.\gradlew.bat :arena:arena --args="--a=search --b=search-4 --boards=200 --fixed-contract --threads=4"
.\gradlew.bat :arena:arena --args="--a=search --b=solver --boards=200 --fixed-contract --contracts=solver --threads=4"
.\gradlew.bat :arena:arena --args="--a=search --b=jskat-ml-pro --boards=200 --fixed-contract --contracts=solver --threads=4"
```

The three questions those answer, in order: how much a sample count buys, what
not knowing costs against a player that does know (`search` against `solver`),
and the one the project is actually about -- whether a search player built from
our own parts beats the best thing we could find off the shelf.

### Playing strength as four dials

Peak strength is not the product. A Skat app needs opponents a beginner can beat
and an expert cannot, and it needs them to feel like *players* rather than like a
strong engine with a random number generator bolted on. `Personality` is where
that lives, and the rule behind it is:

> **Weaken what the player knows, or how hard it thinks. Never the rule by which
> it decides.**

A player that sometimes throws away a good card at random is not a weaker
opponent, it is a broken one: its mistakes have no pattern, so a human learns
nothing from beating it. A player that has forgotten which trumps are gone makes
mistakes that are recognisable, predictable and punishable. That is what an
easier setting has to feel like.

| Dial | Mechanism | What it looks like at the table |
| --- | --- | --- |
| **Erinnerungsvermögen** | each played card and each shown void is dropped from the belief with probability `1 - memory`, decided once and kept | miscounts trumps, leads into a suit somebody showed out of, "forgets" the last ace |
| **Erfahrung** | worlds sampled per decision, 2 to 64 | few worlds is a player going on a hunch: sound in clear positions, erratic in close ones |
| **Risikofreude** | shifts the target the search plays for, up to 29 points | bold play chases Schneider and lets games slip; cautious play banks the win and never presses |
| **Aggressivität** | the make chance a contract needs before it is bid, and which card it prefers among equally good ones | declares far more or far fewer games; takes tricks with the high card instead of sneaking through |

The memory dial is the interesting one mechanically. A forgotten card does not
merely go unaccounted for — it returns to the pool of cards that might still be
in an opponent's hand, so the player reasons about worlds in which an ace it
already saw fall is still out there. `PersonalityTest` asserts exactly that: a
forgotten card reappears in sampled worlds, and a remembered one never does.

There is deliberately **no blunder rate**. If a level ever needs to be weaker
than `memory = 0, worlds = 2`, the honest lever is a shorter search horizon — a
player that stops seeing the endgame, which is a beginner's actual problem —
rather than corrupting choices that were correctly reasoned.

Bidding is measured rather than tabulated: the player deals the unseen cards at
random a few times, solves, and bids a contract when its make chance clears the
threshold aggression sets. That replaces the usual table of hand-evaluation rules
with something that needs no separate tuning per contract type — and it is the
same machinery the card play already uses.

It has to model the pickup to work at all. Judged as dealt, the median hand makes
*nothing* against perfect defence, so the first version asked for a make chance
that was simply unreachable and produced a player that never opened the bidding
once in 48 games. Playing the exchange out first — bury two cards by the classic
rule, count their points — moves the median hand from 0.00 to 0.33, and the
threshold is now calibrated against that measured distribution: neutral
aggression qualifies about half of hands, which leaves roughly one board in ten
passed in at a table of three, as at a real one.

An overnight measurement run is in `tools/overnight-arena.sh`, for Git Bash:

```bash
./tools/overnight-arena.sh --quick     # one seed, small boards: does it run at all
./tools/overnight-arena.sh             # three seeds, a few hours
```

It runs the test suite, then the level ladder both at fixed contracts and with
the auction live, the sampling curve and the matches against the field, three
times on different seeds, and writes one line per match into
`arena-logs/summary.txt`. It is resumable -- a match whose log exists is skipped
-- and it stops early if you create a file called `STOP` in the repository root.
`tools/overnight-arena.bat` is the older cmd.exe version and runs the same list
without the auction-mode ladder.

```powershell
.\gradlew.bat :arena:arena --args="--a=beginner --b=club --boards=200 --fixed-contract --threads=4"
.\gradlew.bat :arena:arena --args="--a=expert --b=solver --boards=200 --fixed-contract --threads=4"
```

**The labels are a promise until a match has been run.** Turning a dial setting
into a number of tournament points against a fixed reference is exactly what this
arena is for, and the four presets are starting points chosen by eye. A first
six-board smoke run had `beginner` winning 25% of its declared games against
`expert`'s 75%, which says the dials bite; it says nothing yet about whether the
spacing between the levels is even.

### The bar for our own search player

`jskat-ml-pro` is the only player measured that is **positive from declaring**
(+3.68 TP/game). Every other one is underwater on this contract distribution.
A search player has to be measured against 69%, not against the 50% that was the
bar before the ML players ran.

### Cost

ML matches are inference-bound even after pooling: about 3 games/s with one ML
side and 1 game/s with two, against roughly 4200 for the algorithmic players. A
1500-board ML match takes 45 minutes to two hours single-threaded. Use
`--threads=4`; start there rather than 8, because each pooled ML player holds
four ONNX sessions with their own weights.

Threads default to 1 because the vendored JSkat player reaches into
process-wide state. Raise it only for contestants you have validated;
`ArenaTest` asserts that parallel and serial runs agree for the built-ins.

## Generating belief-model training data

```powershell
.\gradlew.bat :arena:export --args="--boards=200000 --threads=4 --shard=20000 --out=../belief-data"
```

```
--boards=<n>    boards to play                        (default 1000)
--seed=<n>      the usual match seed                  (default 1)
--threads=<n>   parallel boards                       (default 1)
--out=<dir>     where the shards go                   (default belief-data)
--shard=<n>     boards per shard file                 (default 500)
--players=a,b   population to seat, comma separated
```

Each board yields roughly 26 examples — one per card decision per seat — at
**299 bytes** apiece: 232 feature bytes, 32 label bytes, 32 mask bytes and a
three-byte trailer. A hundred thousand boards is about 2.6 million examples and
780 MB. Read a shard with

```python
raw = numpy.fromfile(path, dtype=numpy.uint8).reshape(-1, 299)
features = raw[:, :232].astype(numpy.float32) / 255.0
target, mask = raw[:, 232:264], raw[:, 264:296]
who_sat_where = raw[:, 296:]      # diagnostics, never an input
```

The trailer names which player sat in each seat, relative to the observer, as an
index into the `population.json` written beside the shards. It is **not a
feature**: at play time the opponent is a human and no such label exists, so a
model trained on it would lean on something that is not there. It is recorded
because it answers one question cheaply that would otherwise cost a regenerated
corpus — *how much would knowing the opponent's style be worth?* Train once with
it as an extra input, measure the tournament points, and only then decide whether
adapting to the opponent is worth any machinery. Three bytes a record, and the
same trick the belief sweep used.

using the offsets from the `encoding-v1.json` the exporter writes beside the
shards rather than the literals above.

**Cost, measured.** The first probe ran 2,000 boards in 38 minutes on four
threads — 25 records a second, which is 94 hours for 300,000 boards. Almost none
of that was card play: the auction asks *every* seat to evaluate two contracts
before a card is dealt, and a fixed six-world hand evaluation therefore cost
three full evaluations a board whatever the level. Bidding effort now follows the
experience dial (`worlds / 3`, capped at six), which is also the coherent thing —
a player that samples two worlds a card has no business weighing its opening bid
over six.

That plus a cheaper population roughly doubles the rate. Weighting is free: an id
listed twice is drawn twice as often.

```powershell
.\gradlew.bat :arena:export --args="--boards=100000 --threads=4 --shard=10000 --out=../belief-data --players=greedy,greedy,jskat-new,jskat-new,search-4,club"
```

Expect eight to twelve hours and about 2.6 million examples. Shards are written
as they finish, so a run stopped early is still a usable corpus — the last shard
is simply shorter.

**Seat a population, not an opponent.** A belief model trained on games between
copies of one player learns where *that* player puts its cards and is confidently
wrong against anyone else. The default mix is `greedy,search-4,club,expert,jskat-new`.
Humans are what that mix is still missing, and that — not raw volume — is what
the ISS archive would add.

The labels are free and exact because the engine knows the deal, so there is no
archive to license and no human to imitate. What the exporter must never do is
let a hidden card into the *features*: `BeliefExporterTest` encodes two deals
that differ only in how the unseen cards are split between the opponents and
asserts the vectors come out identical.

### The aggression sweep: four points found, three points imagined

Two runs, and they say different things. The first, on 2026-08-21, entered
`belief-bold` (aggression 0.80) and `belief-timid` (0.20) against `belief` (0.50)
both with the auction and at fixed contracts, the second as a control.

**What it found, and this part holds.** The timid control came out at *exactly*
+0.000 on all three seeds: 0.2 and 0.5 both sit below the 0.6 threshold that
`prefersTheHighCard()` used, so those two players' card play was identical and
every board cancelled. Bold at 0.8 crossed the threshold and lost **4.08 game
points a game** [−5.72, −2.44]. The flag is gone; keeping your points off the
table is better Skat at every temperament. Re-measured after the removal, the
bold control is +0.000, +0.000, −0.111 — the four points were real and they are
back.

**What I concluded from it, and it was wrong.** Bold's auction result was −0.99
while carrying that −4.08 handicap, so I subtracted one from the other, called
the +3.09 difference "the bidding's share", and wrote that declaring more is
worth about three points. Measured properly once the handicap was gone:

| | pooled | 95% CI |
|---|---|---|
| bold (0.80) − belief, auction | **+0.08** | [−0.73, +0.88] |
| timid (0.20) − belief, auction | **−0.13** | [−0.84, +0.58] |

Nothing, in either direction, across a band of aggression from 0.2 to 0.8.

The subtraction was invalid for a reason worth remembering, and it is not the one
I flagged at the time. I warned that the two estimates were unpaired and gave the
difference an interval of about ±2. The real flaw is that **the control was
measured under a different declare rate**: with the auction live, bold declares
36.7% of boards against 25.6%, so it plays declarer far more often and the
card-play handicap applies with much greater weight than in the fixed-contract
run, where both sides declare exactly a third. A control only subtracts cleanly
when the thing being controlled for enters both runs the same way. Here it did
not, and no amount of widening the interval would have fixed that.

**The flat result is itself informative.** Bold declares eleven percentage points
more games than the reference and scores the same. A response that is flat in
both directions around a threshold is what a *correctly set* threshold looks
like — at an optimum the first derivative is zero, so small moves cost nothing.
The evidence now says the bidder is calibrated, not cautious. The original
suspicion, from an 82–87% win rate at under a third of boards declared, was
wrong; and the win rate never could have settled it, because a player declaring
exactly at break-even still wins far more than two thirds of what it takes.

**One confound found on the way, and it is not a bug.** After the tie-break was
removed, aggression should touch nothing at a fixed contract — yet seed 13 shows
−0.111 rather than zero. The path is `discardSkat`: the player buries for the
contract it *intended*, which is right in a real game, where the discard precedes
the declaration and nothing else is known. In `--fixed-contract` mode the arena
imposes a contract the player never chose, so its discard is made for a different
game — and `lossWeight` can reorder which contract it intended, so two players
differing only in aggression can bury different cards. Fixed-contract mode
therefore isolates card play from *bidding* but not from the discard. It is
symmetric, so paired differences survive; it is worth knowing before the next
control is designed on the assumption that it is airtight.

### The ladder, re-measured on the fixed instrument

Card play, fixed contracts, three seeds of 200 boards, game points:

| step | before | after | 95% CI |
|---|---|---|---|
| beginner → club | 10.25 | **5.87** | [3.97, 7.76] |
| club → expert | 7.46 | **7.46** | [5.48, 9.44] |
| expert → analyst | 2.43 | **5.19** | [3.40, 6.98] |
| expert → the double-dummy ceiling | 14.98 | 14.98 | [13.12, 16.84] |

"Before" is the same instrument, before removing `prefersTheHighCard()` and
before `analyst` went back to 32 worlds. The ladder is now roughly even instead
of top-heavy, which is what a four-level product wants.

**The bottom step halved, and that was predictable.** Of the four levels only
`beginner` had aggression above 0.6, so only `beginner` carried the high-card
tie-break — removing it made the *weakest* level about four points stronger. That
follows from the −4.08 measurement and I did not see it coming until the run
came back.

The consequence is a product question rather than an arena one: the entry level
is now four points harder, and `beginner` already sits at the floor of the dials
that exist — two worlds, 30% memory. `Personality`'s own javadoc anticipated
this: the honest way to build a weaker level is to shorten the search horizon so
the player stops seeing the endgame, which is a beginner's actual problem, rather
than to corrupt its choices. That is now an open piece of work — and since
2026-08-23 it is the *only* open way, because the dials below the beginner have
been measured and there is nothing there. See "The entry level: the dials are at
their stops".

`club → expert` is identical to the last digit across both runs, which is the
instrument confirming itself: neither level had the flag, and with common random
numbers identical players play identical games.

**Sixteen worlds against thirty-two, re-run: −1.84 [−3.47, −0.21], resolved.**
That reverses a decision made on 2026-08-18, when the same comparison failed to
resolve and pointed both ways (+1.2 and −1.7) — on the arena that gave the two
sides different random streams. Doubling the sampling is worth about two game
points; the old instrument simply could not see two points. `analyst()` samples 32
worlds again, which roughly doubles the top step, and the javadoc there records
both measurements.

### What the belief is worth, three seeds

Re-measured 2026-08-21 on the fixed instrument; the first numbers, taken before
the seeding bug was found, are in `arena-logs/superseded-*`. `belief` against
`search` — the same player, the same sixteen worlds, the same personality, only
the world source swapped — on three independent board sets, 300 boards each,
pooled by inverse variance:

| mode | seed 11 | seed 12 | seed 13 | pooled | 95% CI |
|---|---|---|---|---|---|
| fixed contracts | +0.71 | +1.87 | +1.98 | **+1.50** | [+0.23, +2.76] |
| with the auction | +2.44 | +1.69 | +2.34 | **+2.12** | [+1.45, +2.79] |

Both still resolved, both homogeneous across seeds (Q = 0.80 and 1.01 on two
degrees of freedom). **The belief model survives the instrument fix**, which was
the thing worth knowing: it is the one result that had independent support before,
and it still has it.

Two things moved. The pooled estimate at fixed contracts fell from +2.37 to
+1.50 — the old number was flattered by noise. And the auction interval halved,
from ±2.26 to ±1.16, which is the common random numbers paying for themselves.

**The auction mode is now the stronger of the two, and that is a finding rather
than an accident.** All three auction seeds resolve on their own; none of the
fixed-contract ones do. The difference between the modes is who picks the
contracts: greedy over-declares, so fixed-contract mode spends much of its
measurement on games that are lost whatever anyone does. When the players choose
their own games, the belief has something to be right about. Note also that the
two sides now declare at *identical* rates (29.89% against 29.89%, Ramsch 12.89%
against 12.89%): with common random numbers they bid the same, so the whole
difference is card play, priced on a sane distribution of contracts.

**The whole gain is as declarer.** All six matches, belief minus search:

| | s11 | s12 | s13 |
|---|---|---|---|
| from declaring, fixed contracts | +3.73 | +4.62 | +5.29 |
| from declaring, with the auction | +2.52 | +5.86 | +4.98 |
| from defending, fixed contracts | +0.30 | −0.15 | −0.20 |
| from defending, with the auction | +0.13 | −0.17 | −0.36 |

Defence averages −0.08 over six independent measurements and never leaves ±0.36.
The belief does not defend better. It does not defend worse either. It simply
does not touch defence at all.

### Why defence is not the place to look

The obvious reading — the defenders are held back by strategy fusion, since two
determinized searches cannot cooperate — is wrong, and the arena had already said
so before the belief existed. `expert` against `solver` at fixed contracts, the
honest player against a double-dummy player that sees all three hands:

| | s11 (32 boards) | s12 (171) | s13 (180) |
|---|---|---|---|
| from declaring, gap to the solver | 23.79 | 26.15 | 25.28 |
| from defending, gap to the solver | 1.25 | 0.08 | **−0.07** |

**Perfect information is worth nothing in defence.** On the two large seeds the
omniscient player defends no better than an honest one, and on seed 13 very
slightly worse. There is no headroom there to be unlocked — not by a belief, not
by alpha-mu, not by anything, because a player that already knows every card
cannot find it either.

That is not a quirk of the implementation, it is the shape of the game under
classic settlement. A defender scores only when the declarer goes down, and
whether that happens is mostly settled by the cards and by the *declarer's*
choices. The band in which defensive skill decides the game is narrow. The
declarer, by contrast, carries the whole game value, doubled when lost, and has
to find one line among many.

**So the remaining points are in declaring and in the auction**, and the
declaring column says how many are left: at fixed contracts the solver declares
at +2.91 (s12) where `expert` was −23.24, `search` −8.92 and `belief` −5.19. Two
thirds of that gap is already closed. About eight game points remain.

### The sharpness sweep, and why it stays at 1.0

An exponent on the model's probabilities: below one softens towards uniform,
above one sharpens towards the argmax. Measured at fixed contracts, 200 boards a
seed, each against `search`:

| exponent | pooled | 95% CI | Q (df 2) |
|---|---|---|---|
| 0.5 `belief-soft` | +1.99 | [+0.38, +3.61] | 1.35, p = 0.51 |
| **1.0 `belief`** | **+2.37** | [+1.03, +3.71] | 0.41, p = 0.82 |
| 2.0 `belief-sharp` | +2.42 | [+0.91, +3.92] | 7.48, **p = 0.024** |

No exponent beats the model as trained, and that is a real answer rather than an
absence of one: the network is calibrated about right for this use, so there is
nothing to correct.

The Q column is the more interesting half. Sharpening is the only setting whose
seeds disagree by more than their own intervals allow — +5.76, −0.02, +2.35 —
and the plain model is the most consistent thing measured here. That is what
sharpening should do: it does not make the belief more right, it makes the search
commit harder to whatever the belief said, so the good guesses and the bad ones
both get louder. The pooled mean survives; the variance does not.

Caveat on the method. Each variant was measured against `search`, so comparing
two variants throws away the pairing that makes this arena precise. If the
exponent is ever worth revisiting, measure `belief-sharp` against `belief`
directly on the same boards. It is not worth revisiting on this evidence.

### The arena was measuring a player against itself as three points weaker

The most important thing αµ produced, and it is not about αµ.

`alphamu-1` — the control, αµ with nothing expanded — is **provably the same
player** as `belief`. Proven twice: 25 of 25 headless deals played card for card
identically, and in the arena itself, 15 of 15 boards scored alike with a
difference of exactly zero. It changes no decision.

The arena reported it as **−3.08 game points a game, resolved, `belief` is
stronger**.

**Why.** `DuplicateMatch.playRotation` mixed the *half* — which of the two sides
was being played out — into the seed each provider was given. So the two sides
drew their worlds from different random streams. For a sampling player that is a
different player: `search-4` against a copy of itself came out **2.4 game points
apart on fourteen boards, with only 57% of boards scoring alike**.

That noise is inside every number this arena has ever printed. It is invisible
when two players differ by a lot — `search` against `greedy` is +18 and nobody
was ever going to mistake that — and it is the entire signal when they differ by
little. The αµ measurements are worth nothing and have been withdrawn.

**The fix is common random numbers**: the seed depends on the board and the seat
and not on which side is playing, so the two halves differ only in which
contestant sits where. A self-match is now exactly zero on every board, and every
measurement of a small effect gets sharper for free.

**Why the existing guard missed it.** `ArenaTest` has had a zero check since the
beginning — two identical players must score identically — and it used `greedy`,
which is deterministic and *ignores the seed it is handed*. The one player that
cannot detect a seeding bug was the one guarding against it. There is now a
second version of the check that uses a sampling player, which is the version
with teeth.

**What has to be re-measured.** Everything with an effect under about five game
points, which is most of what is written above. The belief's +2.4 is the one
result with independent support — three separate seeds, homogeneous at Q = 0.41 —
so it is likely to survive, but "likely to survive" is not a measurement.

### alpha-mu: correct, and worth nothing

`AlphaMu` is Cazenave and Ventos's search for incomplete-information games,
applied where they apply it: to the declarer. It removes **strategy fusion** —
the determinized vote solves every sampled world double-dummy, which assumes its
*own* future cards are face up, so it plans a different continuation in each
world and counts them all as won. αµ forbids that by construction: one card for
all worlds at every node of ours, while the opponents keep their perfect
information.

Used only from seven cards in hand down (cost: about 4 ms a decision at five
cards, 19 at six, 130 at seven — roughly six times per extra card, so the opening
lead would be half a minute) and only by the declarer.

**Measured on the fixed instrument: +0.006 game points a game**, 95% CI [−0.073,
+0.085] pooled over three seeds of 300 boards (−0.01, +0.34, +0.50 individually).
It is not worse. It is not better. It is nothing, and the interval is narrow
enough to say so — because with common random numbers most boards produce an
identical game and contribute no variance at all, which is a kind of precision
this arena could not reach before.

It stays registered and unused. The remaining declaring headroom is about eight
game points and this is not the way to it.

**What it cost to find that out**, recorded because the errors were the
instructive part:

- The **root cut** was written to stop as soon as the claim fell, which is
  precisely when deepening is working, so every search returned its depth-one
  answer and "depth 3" was depth 2 with extra bookkeeping. Caught by the test
  asserting that deeper must *sometimes* claim less.
- The **target** was one number for all worlds, when the skat is two unseen cards
  and each world buries different points in it. It is a vector now.
- The first version returned a **single chosen card**, silently replacing the
  caller's tie-break — among cards that win equally often, keep your points off
  the table — with "lowest card index". `AlphaMu.rank` returns a score per card.
- Three arena runs said −2.78, −3.09 and −3.08, and I read them as three
  confirmations. They were one measurement on one seed's boards, repeated. Then
  the depth-one *control* — a player provably identical to the one it was
  measured against — lost the same three points, which is what finally pointed at
  the arena rather than the algorithm.

### 2026-08-23: everything re-measured with the auction, and JSkat's transformer

Three seeds of the nightly script, pooled by inverse variance. The auction-mode
numbers are the ones to read: `--fixed-contract` hands both sides a contract
chosen by a greedy bidder, which wastes a good share of the boards on games
nobody would have played, and the auction has since turned out to be the tighter
instrument rather than the noisier one.

| Match | game pts/game | 95% CI | |
|---|---|---|---|
| beginner − club, auction | −3.44 | [−4.83, −2.04] | resolved |
| club − expert, auction | −5.86 | [−7.57, −4.16] | resolved |
| expert − analyst, auction | −3.19 | [−4.56, −1.81] | resolved |
| belief − search, auction | +2.12 | [+1.45, +2.79] | resolved |
| belief − jskat-new, auction | +12.58 | [+10.92, +14.24] | resolved |
| search − jskat-new, auction | +9.33 | [+7.44, +11.22] | resolved |
| search − greedy, auction | +18.96 | [+16.94, +20.98] | resolved |
| search − solver, auction | +2.65 | [+0.39, +4.91] | resolved |
| search − jskat-ml-pro, oracle | −2.60 | [−3.92, −1.29] | resolved |
| belief − jskat-ml-pro, oracle | −1.48 | [−2.64, −0.33] | resolved |
| belief-bold / belief-timid | +0.08 / −0.10 | flat | — |

**The ladder is real and monotone**: 3.4, 5.9 and 3.2 points a step, every step
resolving, 12.5 points from the entry level to the top one. That is the first
time all three steps have resolved in one run.

**We are still behind JSkat's transformer, by 1.48 points, and now that is a
number rather than a suspicion.** The belief model closed 1.1 of a 2.6-point
deficit and did not close it. Two things follow. The deficit is entirely in the
card play and the discard — the oracle mode forces the contract, so the auction
cannot contribute — and in the full game the same player is 12.6 points *ahead*
of JSkat's standard AI. So the reading is not "JSkat plays better Skat"; it is
"JSkat's transformer plays better cards than our sixteen-world search".

Which points at the cheapest possible explanation: `belief` samples sixteen
worlds, and doubling that was measured at +1.84 for the beliefless player
(`search` against `search-32`) — more than the whole deficit. If it carries over,
we were comparing our middle effort against JSkat's best. *(It carried over, and
then some: see the next section.)* `belief-32` is
registered for exactly that match, and it is worth knowing that at a fixed
contract it is not an analogy for the shipped top level but literally the same
player: `Opponents.Level.ANALYST` is (32, 1.00, −0.1, 0.35) against
`Personality.REFERENCE`'s (32, 1.00, 0.0, 0.5), aggression moves only the
auction, and a negative risk aims at the same target as a neutral one.

**The bidding rule is worth more than seeing all three hands.** `search` beats
`solver` — which plays every card knowing the whole deal — by 2.65 points once
the two of them have to bid for their contracts. The solver declares 42% of
boards and scores −7.2 game points doing it; we declare 15% and score +0.4. This
says more about the solver's uncalibrated bidding than about ours, but it is the
clearest statement yet of why the auction is the sharper instrument: at fixed
contracts this match is a 15-point loss, and in the game it is a win.

### 2026-08-24: level with JSkat's transformer, and the world count is not done paying

`belief-32` — the same player as `belief` at 32 sampled worlds instead of 16, and
at a fixed contract the same player as `Opponents.Level.ANALYST` card for card.
Three seeds of 300 boards each:

| Match | game pts/game | 95% CI | Q (2 df) |
|---|---|---|---|
| belief-32 − jskat-ml-pro, oracle | **+0.19** | [−0.95, +1.33] | 5.33 |
| belief-32 − belief, fixed contract | **+2.64** | [+1.40, +3.88] | 1.55 |

**The deficit is gone.** `belief` lost this match by 1.48 points with the interval
clear of zero; `belief-32` is level, and the point estimate is on the right side
of it. Not resolved, and it was never going to be — 300 boards a seed against
JSkat resolves about ±1.15, and an edge near zero needs thousands. The honest
claim is *parity*, not a win: **the strongest player we ship and the strongest
player JSkat ships are, at forced contracts, indistinguishable.**

One caveat kept in view rather than buried: Q = 5.33 on two degrees of freedom is
p ≈ 0.07, so the three seeds are marginally more spread than sampling noise
explains — −1.26, +1.84, +1.01. Seed 11's boards favour jskat-ml-pro in every
match on record (`belief` lost worst there too), so the likeliest reading is a
board effect the three-seed pooling does not fully average away. It is a reason
to say "about even" rather than "+0.19", not a reason to distrust the direction.

**Doubling the worlds is worth 2.64 points on top of the belief**, resolved, and
homogeneous across seeds. That is *more* than the 1.84 the same doubling was
worth to the beliefless player. Comparing two paired estimates is the arithmetic
this file warns against elsewhere, so the 0.8 difference is not itself a finding
— but the direction is worth stating as a hypothesis for the next person: better
hypotheses about the hidden cards and more of them look like complements rather
than substitutes. A sharper prior makes each additional sample worth more,
because the samples are drawn from a distribution that is closer to the truth.

**The two routes agree, which is the check worth having.** Chaining the paired
measurements predicts −1.48 + 2.64 = +1.16 for `belief-32` against jskat-ml-pro;
measured directly it is +0.19 [−0.95, +1.33], and the prediction sits inside the
interval. Two independent paths to the same place is what a working instrument
looks like — and this one has been wrong before, which is why it gets checked.

**Open, and cheap:** whether 64 worlds buys another two points, or whether this is
where the curve bends. One match answers it. The cost is linear and it is paid on
a phone, so at some point the answer stops being a measurement and starts being a
product decision about how long a person will wait for a card.

### The entry level: the dials are at their stops

The question was whether the app can honestly offer two beginner levels. Three
candidates, each the beginner personality with one or both knowledge dials pushed
to their floor, measured against the beginner itself on duplicate boards:

| Candidate | vs `beginner` | 95% CI | vs `club` |
|---|---|---|---|
| novice-floor (1 world, no memory) | **+0.56** | [−0.56, +1.67] | −6.35 |
| novice-0m (2 worlds, no memory) | **+0.44** | [−0.79, +1.66] | −6.15 |
| novice-1w (1 world, memory 0.30) | not measured | | −7.35 |

Neither floor is weaker than the level it was supposed to sit below. Both point
estimates are *positive*. So there is no second entry level to be had from
`worlds` and `memory`, and the earlier claim in this file that the beginner "sits
at the floor of the dials" was wrong in letter and right in effect: it does not
sit there, but the floor is no lower than where it sits.

**Why, and it is structural rather than a calibration problem.** A search with
one world solves that world exactly. Perfect play on a wrong guess is still
strong play — it is coherent, it never wastes a trump, it never fails to cash a
winner. Taking away information stops making a player weaker once the player has
so little left that its remaining decision is a clean double-dummy problem. The
dial that still has travel in it is not what the player knows but how far it
looks, and `Personality`'s own note predicted that: *if a level ever needs to be
weaker than memory = 0, worlds = 1 can make it, the honest answer is to shorten
the search horizon.*

**There is a lot of room down there.** `greedy` — a rule-following player with no
search at all — is 19 points below `search`, while the whole four-rung ladder
spans 12.5. Chaining the ladder's paired steps (which this file elsewhere warns
against doing, so treat it as indicative) puts the beginner about 9 points below
`search`, leaving something like ten points of unreachable space between the
gentlest opponent we can currently build and a player that merely follows rules.
A true beginner belongs in that space.


## The app was playing against JSkat

Found on 2026-08-22, and it reframes most of what is written above.
`MainActivity` constructed `new JSkatAiProvider()` and handed it to the game
view. **Everything this project has measured — the search player, the four
levels, the belief model, the αµ week — had never reached a phone.** A person
installing the app played against the vendored JSkat AI, and the four levels
were unreachable because nothing in the app's sources so much as mentioned
`Personality`.

That is also why there was no difficulty setting: there was nothing to set.

**What changed.** `dev.skatklar.demo.ai.Opponents` is the one seam between the
measured players and the app — the app does not know what a personality is, and
the arena does not know what a settings screen is. `AppOpponent` seats a level
and can swap it; because the engine asks for a fresh session per game, a change
takes effect at the next deal and never mid-trick, which is the only sane moment
for it. The settings dialog has an Opponent spinner above the game mode, in all
six languages.

`GreedyAiProvider` moved from the training module into `core` along the way. The
search player leans on it for the discard and for anything it cannot decide
itself, and the phone build cannot see the training module — which drags in the
vendored JSkat library and ONNX Runtime with it.

**JSkat is not gone**, and should not be. It remains the arena's baseline
(`jskat-new`, which our player beats by 10.25 game points a game) and one of the
five seats in the self-play population that produced the belief model's training
corpus: `greedy, search-4, club, expert, jskat-new`. Its style is therefore baked
into what the model learned about where cards lie — deliberately, because a model
trained only on copies of one player is confidently wrong against everyone else.

**Still open, and now the top of the list.** Removing `prefersTheHighCard()` made
`beginner` about four game points *stronger*, because it was the only level with
aggression above the threshold. The bottom step of the ladder halved as a result
and the entry level is harder than it was.

I said at the time that `beginner` sat at the floor of the dials that exist and
that a new one was needed. **That was wrong.** It sits at two worlds and 30%
memory; the floor is *one* world and *none*. Three settings are registered to
price the room that is actually left — `novice-1w`, `novice-0m`,
`novice-floor` — and they cost one measurement rather than a new search mode.

That measurement came back on 2026-08-23, and the room is empty: both floors are
*not weaker* than the beginner they were meant to sit below, +0.56 and +0.44 game
points with the intervals straddling zero. So the original claim was wrong about
where the beginner sits and right about what has to happen next. The search
horizon it is.

One dial was free and is already applied: `Opponents.Level.usesBelief()` gives
the learned belief to every level except the beginner. The belief is worth 1.5 to
2.1 game points, so withholding it lowers the entry level and widens the bottom
step at the same time, and it is exactly the right shape of weakness — a player
that guesses worse about where the cards lie, rather than one that throws away a
good card at random.

If the floor of the existing dials still is not gentle enough, *then* the search
horizon is the next thing to build, as `Personality`'s javadoc has always said:
stop the search early so the player cannot see the endgame, which is a
beginner's actual problem.

## Getting the belief onto a phone

The measured +2.1 game points lived only in the arena: the model ran through ONNX
Runtime, whose native libraries the Android build deliberately keeps out of the
APK. So the model now also ships as a plain array of numbers.

```powershell
py training\python\export_weights.py --model=belief-model
```

writes `belief.bin` beside `belief.pt` — 2.9 MB of big-endian float32 — and
`dev.skatklar.demo.belief.BeliefNet` in **core** reads it with a
`DataInputStream` and multiplies four matrices. No runtime, no native code, no
platform to be surprised by.

**The arena uses it too, and that is the point.** `BeliefPlayers` prefers
`belief.bin` over `belief.onnx` wherever both exist. One implementation, measured
and shipped, is one fewer way for the number this README quotes to be a number
about something else. ONNX Runtime stays as a fallback for older model
directories and as a second opinion when the two are suspected of disagreeing.

**Three details decide whether it agrees with PyTorch**, and all three fail
quietly:

- LayerNorm normalises with the *biased* variance and puts eps inside the root;
- GELU is the exact erf form, not the tanh approximation, which differs by about
  1e-3 near |x| = 2 — three layers of that misses the parity tolerance by an
  order of magnitude;
- Dropout is the identity in eval mode, so it does not appear at all.

Whether they are right is not settled by reading the code. `BeliefNetTest` checks
the forward pass against a NumPy reference written from the PyTorch definition
rather than from the Java, on a small net whose fixture fits in the repository;
and `ModelDirectory.checkParity` replays the trainer's own sixteen vectors
through whichever implementation is about to play, refusing it over 2e-3. That
second check is now shared between the two loaders, so it cannot rot on the one
that ships.

**The app now seats it**, which until 2026-08-22 it did not — see below.

To install the weights in a product: run the exporter and drop `belief.bin`
where that product looks for it -- for SkatKlar's app, its Android assets. It checks the encoding version and the input width
before using it and falls back to uniform sampling when the file is missing or
does not match, because the belief is worth about two game points and no part of
the rules: a build without it is weaker, not broken.

## Seating the trained belief

The model the trainer writes is three files in one directory: `belief.onnx` and
its `belief.onnx.data` sidecar, `model.json`, and `fixtures.npz`. Point a match
at it and three contestants appear:

```powershell
.\gradlew.bat :arena:arena --args="--a=belief --b=search --boards=300 --seed=11 --fixed-contract"
```

`belief`, `belief-sharp` and `belief-soft` are the same player as `search` —
`Personality.REFERENCE`, sixteen worlds, full memory — with the world source
swapped and nothing else. That is the whole point of the comparison: the paired
per-board difference against `search` prices the model, not the model plus a
personality. The two variants raise and lower an exponent on the model's
probabilities, because how literally the search wants the belief taken is a
question the validation loss cannot answer. A loss rewards calibration; a vote
over sampled worlds rewards being right about the jacks.

They are registered only when a model is on disk, so a fresh clone simply does
not list them. The directory is looked for as `belief-model` from the repository
root or the module, and `-Dbelief.model.dir=...` overrides that.

**Three things are checked before the model may play**, because each of them
otherwise fails silently — the player is merely weak, and nothing says why:

- the encoding version in `model.json` against `BeliefEncoding.VERSION`, since a
  v1 model fed v2 features is reading a different game;
- the input width;
- the **parity fixtures**. `fixtures.npz` holds sixteen real feature vectors and
  the logits Python produced for them; `OnnxBeliefModel` replays them through
  ONNX Runtime on load and refuses a model whose logits move by more than 2e-3.
  A runtime that disagrees with the trainer about the same input is exactly the
  failure that is invisible from the outside.

A model that fails any of these ends the match with a message rather than
quietly falling back to uniform sampling — a silent fallback would turn a
measurement of the belief into a measurement of `search` wearing its name. A
model that fails on *one decision*, in contrast, does fall back for that decision
only: an unavailable belief should cost points, not a night's run.

**What is enforced above the belief.** The model never places a card; it only
weights where the sampler is already willing to put one. Capacities, voids and
the two skat slots are enforced in `WorldSampler` above the weights, so the worst
a confidently wrong model can do is make the player believe the wrong thing. It
cannot make it believe an impossible thing, and a search fed impossible worlds
does not play badly — it crashes.

**Where the seams are tested.** `BeliefModel` is a one-method interface so that
everything interesting is testable without a runtime: `BeliefWorldSourceTest`
drives it with a stub that always says "left", one that says "skat", one that
returns nonsense and one that throws, and checks both that the belief steers the
draw for every seat — left and right are relative to whoever is deciding, which
is what lets one network serve all three — and that every failure mode lands on
the uniform draw instead of on an exception.

## Why duplicate scoring

Skat's variance is severe. Two reasonable players compared on independently
dealt hands need tens of thousands of games before the difference between them
rises out of the noise, because almost all of the variance is in the cards
rather than in the play.

So every board is played six times: three with contestant A in one seat and B
in the other two, then three with the roles exchanged. Both sides declare and
defend the same cards from the same positions, and the measurement is the
**paired per-board difference**. Deal luck cancels inside each difference
instead of having to average out across the sample, which is worth roughly an
order of magnitude in boards.

The reported interval is a paired-difference 95% confidence interval. When it
still contains zero the run prints how many boards the observed edge would need.

Scoring is the **classic game value** the canon settles in (`docs/rules.md`):
the declarer's column on a paper list, or in a Ramsch the loser's, doubled on a
loss. That is what the paired difference is taken in and what a match is decided
on.

Seeger-Fabian tournament points (declarer +50 on a win, -50 on a loss, 40 to each
defender when the declarer goes down) are still reported, one row below, because
every measurement recorded before 2026-08-17 was taken in them. They are no
longer the target, and the two disagree: over one 24-board match the greedy
baseline scores -12.8 tournament points and -16.5 game points a game -- the same
play, flattered by a system that pays a defender for somebody else's failure.

## Two modes, and why the second exists

The default mode runs the auction at every table and measures bidding and card
play together, which is what a real table does. It has one trap: because a
defender collects 40 every time the declarer goes down, **a player that refuses
to declare can post a good total without ever showing whether it plays well.**

The vendored JSkat baseline does exactly that. It declares 6% of its games — a
seat's fair share is 33% — and essentially its entire score is defender bonuses
collected from opponents who declared and failed. The report therefore splits the
total:

```
tournament pts/game          27.43       -19.26
  from declaring              9.91       -30.55
  from defending             17.52        11.28
```

and warns when a contestant drops below 15% declares.

`--fixed-contract` removes the auction. A `ContractSource` picks the declarer and
the contract, and **both contestants play every board at that identical
contract**, each declaring once and defending twice across the three rotations.
The structural check is in `ArenaTest`: both sides must end with an identical
contract mix, not merely a similar one. Nothing passes in, so all six games per
board score.

```powershell
.\gradlew.bat :arena:arena --args="--a=jskat --b=greedy --boards=2000 --fixed-contract"
```

## Where a *good* fixed contract comes from

Fixing the contract is **fair** whatever the source produces: a hopeless contract
is lost by both sides, a laydown is won by both, and either way it cancels in the
paired difference. Duplicate pairing does that work for free — the report counts
the boards where both sides scored alike and prints the share, because those
boards cost sample without carrying signal (typically around 17% with the greedy
bidder).

What the source governs is **external validity** — whether the card-play skills
being exercised are the ones that matter at contracts a strong player would
reach. `ContractSource` exists so that question has three answers, in increasing
order of quality:

1. **`AuctionContractSource`** (today) — run the ordinary auction with one bidder
   seated everywhere. Cheap and offline, and only as good as that bidder. Pass
   the strongest one you have via `--bidder=`, and read the contract mix in the
   report before trusting a conclusion drawn on it.
2. **ISS replay** — take the declarer and contract a real, usually strong, human
   chose on that exact deal. The published ISS archive holds 9.1 million of them,
   free and offline, and it arrives with the Phase 1 pipeline we need anyway.
   This is the honest answer.
3. **Double-dummy oracle** (`--contracts=solver`) — the most valuable contract
   that actually makes against perfect defence, computed per board by
   `SolverContractSource`. Ground truth rather than opinion.

```powershell
.\gradlew.bat :arena:arena --args="--a=solver --b=greedy --boards=400 --fixed-contract --contracts=solver --threads=4"
```

Measured over 40 boards: **23% of boards contain no game at all** and are
skipped, and **58% of the rest are Grand**. Both follow from the definition.
A real table sees far fewer Grands, because a real declarer is not choosing the
best of three seats and a real defence is not perfect. The oracle answers "what
is objectively there", not "what a club player would announce" -- for a
human-like mix, use the auction or, later, an ISS replay. It costs about a
second a board against milliseconds for the auction, so use `--threads`.

A live online AI is *not* on that list, and deliberately so. The ISS bots are
reachable only as a game server: you cannot ask kermit "what would you announce
on this hand" without playing an entire game through the protocol, on a shared
public server, at a rate that would be rude at any useful volume. Everything it
would tell us is already sitting in the free archive, 9.1 million times over and
several orders of magnitude faster. ISS bots earn their keep as an occasional
calibration opponent, not as a contract oracle.

## Reproducibility, and where it stops

**JSkat contestants are not reproducible.** Their players draw from two
process-wide unseeded `Random` instances (`AbstractAlgorithmAI`, `CardList`). The
arena seeds both through `JSkatAiProvider.seedSharedRandom`, which helps but is
not sufficient -- consecutive runs of the same match on the same seed still
differ by roughly +-1.5 TP/game over 400 boards. Read any JSkat result as
carrying that spread on top of its confidence interval, and prefer larger board
counts for them. Everything below applies fully to `random`, `greedy` and
anything we write ourselves.

Every random draw is a pure function of the match seed and the game's
coordinates (board index, singleton seat, half, seat). The same seed replays
bit-identically, and a suspicious board can be replayed on its own. Contestants
are factories rather than single providers so each seat in each game gets its
own seeded provider; sharing one `Random` across seats would break both
reproducibility and thread safety.

## The arena's own correctness test

`ArenaTest.identicalDeterministicPlayersScoreExactlyEqual` runs the
deterministic `greedy` baseline against itself and asserts the per-board
difference is **exactly** zero on every board. If the duplicate rotation is
wrong in any way, that test fails, and it fails before any real measurement is
taken with a broken instrument.

## The greedy baseline

`dev.skatklar.demo.ai.GreedyAiProvider` is a deliberately small
deterministic heuristic. It exists to be a floor, not a target: a model that
cannot beat it is broken rather than merely weak.

Current behaviour at a table of three greedy players: it declares about 30% of
deals, passes in about 11%, and **wins only about 45% of the games it declares**.
Raising the bidding threshold barely moves that win rate, which says the binding
constraint is its card play rather than its hand selection. That is exactly the
gap a search player is meant to close, and it is the first thing Phase 4 should
move.

## What the arena caught on its first real run

Enforcing the overbid rule in `GameEngine` immediately exposed a bug in this
baseline: it derived its maximum bid from its *best* contract but announced the
*best-looking* one after the skat exchange, which is often cheaper. That is an
automatic loss whatever the card points. The arena measured it at **9.6% of
declared games**; filtering announcements to contracts that cover the bid took
it to **0.38%**.

The residual is genuine Skat rather than a bug. A hand playing "without two" that
picks up a lower jack from the skat drops to "without one", so the contract can
be worth less after the exchange than the auction assumed. The `overbid (lost)`
row exists to keep that visible: a contestant showing a high rate there has a
hand-evaluation problem, not a card-play problem, and the report separates the
games lost with 61 or more card points for exactly that reason.
