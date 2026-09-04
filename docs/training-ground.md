# SkatKlar AI training ground

Design and decision record for the third build target: a machine-learning
training ground that runs on a Windows workstation (RTX 4090) alongside the
Android app (Android Studio) and the Java server (Linux command line).

Status: proposal. Nothing here is implemented yet.

> **Currency note (2026-08-17).** Every measurement in this document is in
> Seeger-Fabian tournament points, because that is what the arena reported at the
> time. It no longer does: the canon in [`rules.md`](rules.md) settles classically
> and the arena's paired difference is now taken in game points. The numbers below
> stay as the record of what was measured; they are not comparable with anything
> measured after that date, and the ladder will need one re-run in the new
> currency before it means anything again.

## 1. Goal and constraint

Build a Skat AI strong enough to carry a **commercial, closed-source** product.
That constraint drives most of the decisions below, and it is worth stating the
consequence up front: the shipped AI must be free of copyleft obligations and
free of unlicensed third-party training data.

## 2. Where the field actually stands

Calibration matters before choosing a strategy, because "the existing AI is not
very good" is true for some players and false for others.

| Player | Approach | Strength |
| --- | --- | --- |
| `LegalRandomAiProvider` (ours) | random legal card | floor |
| JSkat `AlgorithmicAIPlayer` (ours, shipped) | hand-written heuristics | weak |
| JSkat `org.jskat.ai.ml` (in tree, unused) | supervised nets, ONNX | untested by us |
| kermit / theCount (Buro, U. Alberta) | determinized search + learned evaluation + inference | expert human level |

The JSkat algorithmic player is genuinely limited, and the repository documents
why: per `third_party/jskat/ANDROID_INTEGRATION.md`, its `BidEvaluator` bids and
announces suit games only and "does not suggest Grand at all". A player that
never finds Grand and never plays Null is not a serious opponent.

The top of the field is a different story. Kermit is described in the Alberta
literature as playing at expert human level, and the line of work did not stop
there: Rebstock's policy-based inference improved on kermit's own inference by
**5.1 tournament points per game in suit games**, 3.1 in Grand and 2.4 in Null.
Differences of that size are large in Skat.

Two things follow. First, "beat the existing AI" is a low bar and a bad target;
"approach theCount" is the real one. Second, kermit and theCount are not
available as source or as a library — they can only be played against on ISS —
so building on them was never an option regardless of licensing.

## 3. The architectural conclusion

**A neural network alone will not get there.** Every strong trick-taking-game
program of the last two decades is *search plus* a learned component, not a
learned policy on its own. Pure imitation of human game records has a hard
ceiling at roughly the average strength of the humans imitated.

The established recipe for imperfect-information trick games:

1. **Sample plausible worlds** consistent with everything observed so far —
   bidding, discards, void suits, cards played. This is the belief model.
2. **Solve each sampled world** with a fast perfect-information (double-dummy)
   search.
3. **Aggregate** the per-world results into one move choice.

This is why the proposed first sub-task — predicting where the hidden cards are
— is the right first component and not a detour: it is step 1, and steps 2 and 3
are useless without it. Sampling worlds uniformly at random is what weak
determinization players do, and it is exactly where the 5.1 TP/G above came from.

It also relocates the engineering problem. The hot loop is the **solver**, not
the network. A belief net is a sub-millisecond forward pass; a search that
solves hundreds of determinizations per decision is where the compute goes, and
it must also run inside the Android app.

## 4. Language and framework decisions

### 4.1 Does Android dictate the training stack?

No. There is no CUDA on Android — CUDA is NVIDIA-only, phones run Adreno, Mali
or Tensor. Mobile acceleration goes through Vulkan/OpenCL delegates or vendor
NPUs (NNAPI is deprecated as of Android 15 in favour of vendor delegates).

None of that matters here, because the models in question are small. A belief
model with a few hundred input dimensions and a 32x3 output is well under a
millisecond on CPU with XNNPACK. Android constrains the *interchange format*,
nothing else.

### 4.2 Decisions

| Layer | Decision | Rationale |
| --- | --- | --- |
| Training | **Python + PyTorch (CUDA)** | ecosystem, first-class ONNX export, BSD-3 licensed |
| Interchange | **ONNX** | one artifact for every target |
| Inference (server, JVM) | `com.microsoft.onnxruntime:onnxruntime` | MIT; already a `jskat-base` dependency |
| Inference (Android) | `com.microsoft.onnxruntime:onnxruntime-android` | same Java API as the server; minSdk 21 covers our 26 |
| Game rules, solver, search | **Java, in `core`** | single source of truth; already shared by app and server |
| Data preparation, arena | **Java (JVM tool module)** | reuses `core`; no second rules implementation |

Rejected alternatives: **LiteRT** (best Android GPU/NPU story, but a second
toolchain and a different API from the server, for a speedup we do not need) and
**ExecuTorch** (PyTorch-native, but younger and with a weaker JVM story). The
decisive criterion is that the *same* model must run on Linux JVM and Android;
ONNX Runtime is the only option that does that with one artifact and one API.

On Windows specifically: PyTorch CUDA wheels run natively, but `torch.compile`
requires the `triton-windows` fork. Irrelevant at this model size. If it ever
becomes a nuisance, WSL2 gives full CUDA passthrough to the same GPU.

If the Java solver later proves too slow on the training machine, the escape
hatch is a native solver behind JNI (C++ or Rust) — but only after measurement,
and only if the Android path can use the same code via the NDK. Do not start
there.

### 4.3 The encoding contract

The feature encoding will exist twice: once in Python for training, once in Java
for inference. This is the single most common source of silent, catastrophic
bugs in this kind of project — a model that scores 97% in training and plays
like a random bot in the app.

Mitigation, from day one:

- A versioned encoding specification (`encoding/vN.json`) that both sides read.
- A fixtures file of game states with their expected encoded tensors.
- A parity test on both sides asserting bit-identical output on those fixtures,
  wired into CI. JSkat solves this informally via `MLConstants`; we make it a
  hard test.

## 5. Licensing position

Verified for this project's commercial, closed-source goal:

| Component | Licence | Verdict |
| --- | --- | --- |
| JSkat (vendored) | Apache-2.0 | **Safe.** Permissive; requires attribution and a statement of changes, both already satisfied by `THIRD_PARTY_NOTICES.md` and `ANDROID_INTEGRATION.md`. |
| `avaskys/skat-ml-models` | MIT | **Safe.** Maximally permissive. Weights are not separately licensed — confirm before shipping any of them. |
| ONNX Runtime | MIT | Safe. |
| PyTorch | BSD-3 | Safe. |
| **ISS game database** | **none stated** | **Open risk. See below.** |

### 5.1 The ISS data question

The ISS site states only that "all games are stored and made available for free
to everyone". There is no licence, no terms of use, and no citation requirement
published alongside the downloads. Free to download is not the same as free to
use commercially.

The relevant considerations, in order:

1. Individual game records are facts and are unlikely to attract copyright.
2. The *collection* plausibly enjoys the EU sui generis database right
   (§§ 87a ff. UrhG) given the investment in running the server for two decades.
   Extracting substantial parts is then restricted.
3. Germany's text-and-data-mining exception (§ 44b UrhG, implementing the DSM
   directive) permits TDM on lawfully accessible works **including for
   commercial purposes**, unless the rightsholder has reserved the right in
   machine-readable form; § 87c extends this to databases. No such reservation
   is published on the ISS pages.

That is a reasonable position, not a certain one, and this document is not legal
advice. **The cheap fix is one email to Michael Buro asking for written
permission to train a commercial model on the published game data.** A written
yes removes the entire question for the cost of one message, and should be sent
before the data becomes load-bearing.

Note also that redistribution is a separate question from training: even under a
TDM exception, we may not republish the dataset.

### 5.2 Self-play as the exit

The strategic point: **the ISS data is a bootstrap, not a dependency.** Once a
working solver and a first belief model exist, the system can generate unlimited
training data by self-play, with no licensing question at all. The known caveat
is distribution shift — a belief model is a model of *opponents*, and self-play
opponents are not human — so human data likely remains valuable for the belief
component specifically. But the product does not have to remain tied to it.

## 6. Build-on versus from-scratch

Recommendation: **own encoding, own models, own solver — with JSkat and
skat-ml-models retained as opponents and as development-time reference only.**

The reasoning is engineering, not legal. Apache-2.0 and MIT pose no obstacle to
a closed-source commercial product; we could build on both. But:

- `skat-ml-models` is a pure imitation policy. Adopting its encoding means
  inheriting a state representation designed for imitation, when we need one
  designed to feed a search.
- Its models set a fixed capability ceiling we would then be working under.
- The AI is the product here. Owning the representation is worth the extra
  weeks.

What we *do* take from them, at zero cost:

- **Baseline opponents.** Both JSkat players and the ONNX ML player drop into
  the arena behind `SkatAiProvider`, giving us a measuring stick from day one.
- **Reference for SGF ingestion.** `org.jskat.control.iss.IssGameExtractor` and
  the `MessageParser` already understand the ISS format.
- **Proof that the deployment path works.** `jskat-base` already loads ONNX
  models through ONNX Runtime on the JVM.

Nothing from either project should end up in the shipped AI path. JSkat may
remain in the app as a fallback opponent until our own player supersedes it.

## 7. Module layout

```
SkatKlar/
  core/            existing - rules, AI API; gains the solver and belief interfaces
  app/             existing - Android
  server/          existing - Linux JVM
  jskat-ai/        existing - JSkat adapter (baseline opponent)
  training/        NEW - JVM tool module, runs on Windows
    sgf/             ISS SGF ingest -> replayed game states
    encoding/        Java side of the encoding contract + parity test
    export/          tensor shard writer (.npz)
    arena/           headless tournament runner
  ml/              NEW - Python project (uv), PyTorch + CUDA
    encoding/        Python side of the contract + parity test
    models/          belief model, later: evaluation and policy
    train/
    export_onnx.py
  encoding/        NEW - shared, versioned spec + fixtures
```

`training` is a JVM module so that it links against `core` and never
reimplements Skat rules. `ml` does tensors only; it has no notion of the game
beyond the encoded arrays.

## 8. The arena

The existing `SkatAiProvider` / `SkatAiSession` boundary in
`engine/src/main/java/dev/skatklar/demo/ai` is already the right interface — it
models bidding, skat exchange, announcement and trick play, and it explicitly
supports stateless and remote implementations. The arena is a headless runner
over that interface.

Requirements:

- **Duplicate-deal scoring.** Each deal is played three times with rotated
  seats, and both contestants see identical shuffles. Skat's variance is severe;
  without this, thousands of games are needed to resolve differences that
  duplicate scoring exposes in hundreds.
- **Reported confidence intervals.** A result without an interval is noise.
- **Seeded, reproducible deal generation.**
- **Parallel execution** across cores, with per-decision time budgets.

ISS as an opponent source: `org.jskat.control.iss` (already vendored, including
`WebSocketConnector`) can connect our player to the live server and play kermit,
zoot and theCount. Two caveats — it is slow, and ISS is a shared public server
where thousands of automated games would be rude without asking first. **Treat
ISS bots as an occasional calibration benchmark, never as the inner loop.**

## 9. Phase plan

**Phase 0 — playground. Implemented**, see [`arena/README.md`](../arena/README.md).
Duplicate-deal arena over the shipped `SkatAiProvider` boundary, Seeger-Fabian
scoring, paired confidence intervals, reproducible from a single seed. Opponents:
`random`, a new deterministic `greedy` baseline, and `jskat` (resolved
reflectively so a missing adapter cannot block work). Everything afterwards is
measured against this.

`core` gained three additive pieces to make it possible, with no behaviour change
on the app or server paths — their 16 existing engine and rules tests pass
unchanged:

- `SeatedAiProviders` — one implementation per seat, so two contestants can sit
  at the same table. The single-provider constructor became the uniform case.
- `GameEngine.headless(...)` plus `restartWithDeal(deal, round, humanSeats)` — an
  explicit board with no pass-in retry loop, because a retry would silently
  substitute a different deal and destroy the duplicate pairing. Also permits an
  empty human-seat set, for an all-AI table.
- `SkatRules.matadorCount` and `SkatRules.trumpOrder` made public. Hand
  evaluation needs exactly the count the score sheet uses, and the Phase 3 solver
  will too. Extracted from `gameValue`, which now calls them.

It has two modes. The default runs the auction at every table. `--fixed-contract`
replaces the auction with a `ContractSource`, so both contestants play every board
at the identical declarer and contract and only card play is compared — the
instrument Phase 4 needs, since a search player is judged on play rather than on
bidding. Contract sources are pluggable precisely because "where does a *good*
contract come from" is a real question: an auction by the best available bidder
today, ISS replay of real human contracts once Phase 1 lands, and a double-dummy
oracle once Phase 3 does.

Three findings already came out of it, all recorded in section 10.

**Phase 1 — solver. Implemented and wired into the arena.**, in `engine/src/main/java/dev/skatklar/demo/solve`:
`DoubleDummySolver` (alpha-beta with a transposition table over a 10-trick deal)
and `ContractTables` (the rules flattened into per-contract arrays, with strength
numbers deliberately identical to `SkatRules` so a solved line and a played line
agree on who wins a trick).

The arena uses it twice, which were the two things it was for:

- **`solver`, the par baseline.** A player that reads every hand out of the
  engine and plays the perfect-information game optimally. It cheats only in card
  play; bidding and the discard are delegated. See `training/README.md` for what
  its score does and does not bound.
- **`--contracts=solver`, the objective contract.** For each seat and contract,
  one yes/no question -- does this make against perfect defence? -- and the most
  valuable contract that passes is the one played. No bidder's opinion in it.

Speed came from asking the right question rather than from micro-optimisation. An
exact value for a ten-card deal costs about 400 ms; asking whether the declarer
reaches a given number costs about 20, because a null window lets the bound cut
decide most branches without looking at them. Both the oracle and the player ask
only such questions -- the player about 61 and 90, which is all a score sheet
pays for.

The load-bearing test is `prunedSearchAgreesWithExhaustiveSearch`: the same class
also contains plain minimax with no pruning, no ordering and no table, and the
test asserts that alpha-beta alone and alpha-beta plus the table each reproduce
its answer exactly over 60 positions. Pruning and caching are optimisations and
must not change the value; the two assertions are separate so a disagreement
names which of the two is wrong. That is not hypothetical — see section 10.

This was Phase 3 until the question came up of whether the ISS data is worth its
risk. It moved to the front because it is the only large piece that needs **no
data, no permission and no waiting**, and because it pays three ways at once:

- It is a prerequisite for the search player regardless.
- It gives a *double-dummy reference opponent* — one that sees all the cards.
  That sets the ceiling, and turns every other player's strength into a measured
  gap below it. It is the most informative number currently missing.
- It gives the arena's `ContractSource` an objective oracle, better than any
  human or program source.

**Phase 2 — search player with a uniform belief. Implemented**, in
`engine/src/main/java/dev/skatklar/demo/search`: `WorldSampler` draws deals
consistent with what one seat has observed, `SearchAiProvider` solves each of
them and plays the card that wins in the most. Registered in the arena as
`search`, plus `search-4` to `search-64` so the sample count can be measured
rather than chosen.

It lives in `core` rather than in the training module because it is the first
player here that could ship: the same class has to run on Android and on the
server. Bidding and the discard are still delegated to a heuristic.

The sampler filters on facts only -- cards seen, hand sizes, void classes shown
by a failure to follow, and the declarer's own discard, which has to be carried
across sessions because the engine runs the exchange in a session of its own. It
weights nothing, and in particular ignores the bidding, which is the strongest
soft signal there is. That omission is the point: Phase 4 is measured as what it
adds over this player.

The bar it has to clear is now known and it is not low: JSkat's ONNX players win
**69% of the games they declare** at fixed contracts, and are the only players
measured that come out positive from declaring at all. See
[`arena/README.md`](../arena/README.md) for the full ladder. Until it exists, "how much is a belief model worth?" has no
answer; after it, the arena reports that answer in TP/game.

**Phase 3 — data pipeline.** ISS SGF download, parse, replay to state stream,
encode, export shards, plus the Java/Python encoding parity test. Now behind the
solver, and gated on Michael Buro's answer rather than blocking on it.

**Phase 2b — playing strength as a product feature. Implemented**, in
`core/.../search/Personality`. Peak strength is not what a Skat app sells: it
needs opponents a beginner can beat and an expert cannot, and they have to feel
like players rather than like a strong engine with noise added.

Four dials — memory, experience, risk appetite, aggression — each mapped onto a
nameable human failure mode rather than onto a random deviation. The design rule
is that a dial may weaken **what the player knows or how hard it thinks, never
the rule by which it decides**: a player that discards a good card at random has
mistakes with no pattern and teaches a human nothing, while a player that has
forgotten which trumps are gone makes mistakes that can be recognised and
punished. There is deliberately no blunder rate; if a level ever needs to be
weaker than the dials reach, the honest lever is a shorter search horizon.

It also gave the player its own bidding: rather than a table of hand-evaluation
rules, it deals the unseen cards at random a few times, solves, and bids when the
measured make chance clears the threshold aggression sets. Nothing to tune per
contract type, and it reuses the machinery that already exists.

**Phase 2c — Null.** The engine grew Null games; the search player now plays
them. `NullSolver` is a separate solver on purpose: the objective is a single bit
rather than a point count, the declarer is the side trying to *lose* every trick,
and the search ends the moment it wins one. That makes it about a thousand times
cheaper than a suit game, which is worth knowing for the Android budget.

What did **not** change is the interesting part. The world sampler, the four
personality dials and the vote are untouched, because they are defined over what
a player knows and how hard it thinks rather than over what it is trying to
achieve. A whole new contract type dropped in behind them without a single
adjustment.

**Forgetting has to reach the belief model's inputs, and it has to be masked.**
The memory dial currently drops played cards and shown voids. Once a learned
belief model consumes the bidding, the discard and the trick history, forgetting
should apply to all of it uniformly — a player at 80% memory has lost some of
what it saw, and the bidding is the first thing a club player stops tracking.
That keeps the dial defined over evidence rather than over any one feature, so it
survives every later addition to the input.

One requirement makes or breaks it. A forgotten input must be encoded as
**unknown, not as absent**. Zeroing the bidding block tells the network that
nobody bid, which is a false fact rather than a missing one, and a network told
that nobody bid will confidently place the jacks somewhere they are not — worse
than forgetting, because it is hallucinating. So every evidence block carries a
presence bit, and training data is generated with the same dropout distribution
that play time will produce. Then "I forgot the bidding" is a state the model has
seen and falls back to the prior on. This belongs in encoding v1: retrofitting a
mask into a trained model means retraining it.

Two refinements worth building in at the same time. Forgetting should be
**age-shaped** rather than uniform — trick one goes before trick eight, which is
how human memory actually fails — and it must be **stable within a game**, rolled
once per observation and then kept, because a memory that flickers from trick to
trick is not forgetting but noise.

**Phase 4 — belief model. Encoding and data generation implemented.**
`core/.../belief/BeliefEncoding` is version 1 of the feature contract, and
`training/.../data/BeliefExporter` turns played games into labelled examples.
Every game already knows where every card was, so a decision point is a complete,
exactly labelled answer to the question the model is asked — free, unlicensed and
unmislabelled. `./gradlew :arena:export` runs it.

Four decisions in the encoding are worth stating, because each is a place this
kind of project usually goes wrong:

- **Relative seats.** Everything is "me", "left" and "right", never absolute
  seats. Absolute seats make the model learn the same fact three times on a third
  of the data each.
- **Presence bits on every evidence block**, so a forgotten input is *unknown*
  rather than *absent* — see the memory note above for why the difference decides
  whether forgetting produces uncertainty or confident invention.
- **Counts beside identities.** Trumps still out, jacks still unseen. A player
  remembers the count long after the identities blur, and a model given only the
  identities could not express "three are out, but I have lost track of which".
- **Perfect memory at export, forgetting at training.** One corpus then serves
  every personality, and the dropout distribution stays a knob rather than
  something baked into gigabytes of files.

The exporter's one hard property is a negative: no hidden card may reach the
features. `BeliefExporterTest` pins it by encoding two deals that differ only in
how the unseen cards are split between the opponents and asserting the vectors
are identical. A leak there is the most expensive bug available here — the model
would train beautifully, validate beautifully, and be useless in a game, because
the input it leaned on does not exist at play time.

Still to come: the PyTorch trainer, the ONNX export, the Java-Python parity test
on fixtures, and the `WorldSource` that samples from the model instead of
uniformly.

**Phase 4 — belief model.** Supervised, labels free and exact from the full deal
in each record. Output: per-card distribution over {opponent A, opponent B,
skat}, normalised with Sinkhorn/IPF so it respects known remaining hand sizes — a
plain per-card softmax will violate the counting constraints and be useless to a
sampler. Key inputs: the bidding history (who bid how high is the strongest
single signal about jacks and aces), the declarer's discard, and void suits
inferred from failures to follow. Evaluated by log-loss, calibration, true-state
sampling ratio — and, decisively, by the TP/game it adds over Phase 2's uniform
sampler.

**Phase 5 — population self-play.** Close the data loop. Train the belief model
against a *population* of opponents — greedy, the JSkat players, earlier
checkpoints, random — rather than only against itself. A belief model trained
purely on self-play models an opponent that plays like us, which is the wrong
model for the humans who will buy the app; a population is the standard
mitigation, and `PlayerRegistry` already is one.

### What the ISS data is actually worth

Only one component genuinely wants it: the belief model, because that is a model
of *opponents*. Everything else is independent — the solver needs no data, the
search needs the solver, and the contract oracle is better without it.

Even for the belief model the dependency is softer than it looks. The strongest
signals are semi-objective: a bid of 30 constrains a hand through the game value
it implies, whatever the bidder's style, and a failure to follow suit is a fact.

Against that stand real costs, and they are the reason for the reordering:

- Imitation is capped at the strength of those imitated, and ISS is mixed. Without
  rating-weighting it teaches mediocrity.
- **ISS hosts bots.** kermit, zoot and theCount play there, so a "model of human
  opponents" built from that archive is partly a model of Buro's programs.
- The licence question is unresolved. Nothing on the critical path should depend
  on an answer that has not arrived.

So ISS keeps two roles it is genuinely irreplaceable in — **calibration against
human strength**, and a **bootstrap** for the belief model if permission lands —
and neither is on the critical path. Both need thousands of games rather than
millions.

## 10. Open questions and findings

**The overbid rule is now enforced (was: not implemented).** `GameEngine` used to
decide purely on the 61-point threshold, so a declarer who bid beyond the value
its contract reached still scored a win — which meant the arena *rewarded*
reckless bidding, and any AI selected against that signal would have learned to
overbid.

`SkatRules.score` now settles winning and value together, per ISkO 3.6: the
declarer wins only by taking 61 or more card points **and** playing a contract
worth at least the winning bid. An overbid game is charged at the lowest multiple
of the announced contract's base value that reaches the bid, then doubled like
any other lost game with the skat picked up. `SkatRules.gameValue` remains as the
value of the game *as played*, deliberately ignorant of the bid.

Two supporting changes came with it, because a rule is only fair if the engine
does not create the violation itself:

- `GameEngine.fallbackAnnouncement` — substituted when a player fails to announce
  a permitted contract — was picking by a crude score and ignoring the bid
  entirely. It now prefers contracts that cover the bid.
- `SkatAi.GameResult.overbid` and `Protocol.Result.overbid` carry the fact to the
  UI and across the wire. The result screen names it, because a player who took
  80 card points and still lost otherwise has no way to understand the result.
  Old clients simply see the field absent, which decodes to `false`.

The first arena run under the new rule immediately found a bidding bug in the
greedy baseline (9.6% of declared games overbid, down to 0.38% after the fix) —
which is the arena doing its job on day one.

**Card play, not bidding, is the binding constraint on a heuristic player.**
Tightening the greedy baseline's bidding threshold moves its pass-in rate a long
way (0% to 50% across the swept range) but barely moves its declarer win rate,
which sits near 48%. Hand selection is cheap to improve and quickly stops paying;
the win rate is limited by what the player does with the cards afterwards. That
is the gap Phases 3 and 4 exist to close, and it is worth remembering when a
future model shows a good bidding accuracy number in isolation.

**Seeger-Fabian rewards abstaining, and the shipped baseline exploits it.** The
vendored JSkat player declares 6.2% of its games where a seat's fair share is
33%, and posts +12.97 TP/game almost entirely from defender bonuses collected
when its opponents declared and failed. The arithmetic closes: 40 x 0.41 (greedy
declarer loss rate) x 0.70 (games it defends) is about 11.6 of the 12.97. Its
"win" over the greedy baseline is therefore a statement about bidding
aggressiveness, not about play.

The sharper reading points the other way: JSkat announces only its best 6% of
hands and wins 55.4% of them, while greedy announces 42% of hands — including a
great deal of mediocrity — and wins 59.3%. Far better hand selection and a worse
conversion rate suggests JSkat's card play is weaker than a 200-line heuristic's.
That is an inference, not a controlled measurement, and it is what `--fixed-contract`
exists to settle.

**A transposition key must name all three hands.** The solver's first table keyed
on two hand masks and the leader, on the reasoning that the third hand follows
from the other two. It does not: which cards a seat has already *played* is
independent of what the other two still hold, so two genuinely different
positions collapsed onto one key. The solver then disagreed with plain minimax on
about a fifth of five-card deals — silently, and in a direction that looked like a
plausible score.

It was caught only because the exhaustive reference exists, and localised only
because the test asserts pruning and caching separately: alpha-beta alone was
correct everywhere, which pointed straight at the table. Worth remembering before
the same trick gets played with a hash instead of a record — a collision there
fails the same way, without an exception.

**The ML players are being overridden in auction mode, and the auction cannot
separate them.** Running `jskat-ml-pro` against `jskat-ml` with the auction live
gave +0.119 TP/game, CI [-2.587, +2.826], needing an extrapolated 412,645 boards —
the same non-result as at fixed contracts, for a different reason. What it did
surface: about one board in eight ends in an `ANNOUNCE` violation, because these
players announce contracts the demo variant does not permit and `GameEngine`
substitutes a legal one. They also declare Grand in over half their declared
games, against roughly 1.4% for the greedy baseline. Whether that is a real ISS-learned
preference or an artefact of the substitution cannot be told apart until the
permitted contract set is widened. Until then, auction-mode numbers involving them
measure a hobbled player, and only the fixed-contract ladder should be quoted.

**Par is not an upper bound, and the difference is the interesting part.** The
solver was built to put a ceiling under every other measurement. It does that
from the defenders' chairs: it sees all 30 cards and the two defenders act as one
player, which is stronger than any real pair. From the declarer's chair it does
not, and cannot. Double-dummy play maximises what can be *guaranteed* against
perfect defence, so on a contract that perfect defence beats it never tries the
line a weak defender would misplay -- it plays for the best guaranteed result and
takes the loss. Against the greedy baseline it wins 66% of its declared games.

That is not a defect in the solver, and the fix is not to make it play worse. It
means the arena now reports two different quantities, and the report already
splits them: distance from par when defending is a skill measurement, while
beating par when declaring is a measurement of how exploitable the opponent is.

The first direct measurement came out closer than expected. Over 171 boards at
oracle contracts, par beats `jskat-ml-pro` by **+7.0 TP/game** [+1.1, +13.0],
with declarer win rates of 90.1% against 81.3% — and the entire margin is in
declaring, since at objectively makeable contracts there are few defender bonuses
to collect. The strongest player available to us therefore plays within seven
tournament points of perfect information. That is the room a search player has,
and it is narrower than the ladder between the existing programs suggested.

**The discard is worth about a tenth of the declared games.** At oracle contracts
every board is makeable by construction, so a declarer that saw every card and
drew perfectly would win all of them. Par wins 90.1%, and since its card play is
optimal by construction the missing tenth is the exchange — the solver delegates
its discard to the greedy heuristic, and the oracle assumed a different heuristic
when it priced the board. Fixing the contract does not fix the exchange, and the
exchange turns out to cost more than most card-play improvements would gain.
Phase 2's search player will show both, and the second number is the one that
will not transfer to a strong human.

Still open:

- Written permission from Michael Buro for commercial training on ISS data.
- Whether DOSKV data (used by Rebstock) is obtainable and on what terms.
- Android time budget per decision — determines the solver's determinization count.
- Whether Null games get a separate model or a shared one with a game-type input.
- Whether the arena should also report against ISS bots periodically, and how to
  ask for permission to do that at any volume. Note this is a calibration
  question only: ISS bots are a game server, not a contract oracle, and the
  archive answers the oracle question offline and far faster.
- Whether `--fixed-contract` should also fix the declarer's discard. It currently
  does not, because discarding is a play decision that a search player should
  improve. The cost is that a different discard can change the matadors and turn
  a sound bid into an overbid, which adds a little noise.

## References

- [International Skat Server](https://skatgame.net/iss/) — 9.1M games, SGF, free download
- [Rebstock 2019, *Improving AI in Skat through Human Imitation and Policy Based Inference*](https://skatgame.net/mburo/ps/thesis_rebstock_2019.pdf)
- [Long 2011, *Search, Inference and Opponent Modelling in an Expert-Level Skat Player*](https://skatgame.net/mburo/ps/thesis_long_2011.pdf)
- [avaskys/skat-ml-models](https://github.com/avaskys/skat-ml-models) — MIT, PyTorch, ISS-trained, ONNX export
