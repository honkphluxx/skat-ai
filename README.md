# A Skat engine, a Skat AI, and the instrument that measures it

This is the playing and measuring half of [SkatKlar](https://skatklar.dev): a
complete rules engine for German Skat, a family of AI players built on
determinized search and a learned card-location model, and — the part that is
actually unusual — a duplicate-deal arena that can tell whether a change to any
of it was an improvement.

Published under the BSD 3-Clause licence. The Android app and the multiplayer
server that use this engine are separate and closed; nothing here depends on
them, and nothing here has ever seen them.

## Written by a language model

**Every line of this project — the code, the comments, the tests, this README and
the lab notebook behind it — was written by a large language model.** The
direction, the rules canon, the decisions about what to build and what to throw
away, and the judgement about when a result was believable came from a person;
the typing did not.

That is worth stating plainly rather than in a footnote, because it should change
how you read the rest. Two things follow.

The measurements are not claims about the code, they are records of running it.
Every interval in this repository came out of the arena on the machine described
in the lab notebook, and every one of them is reproducible from these sources by
anyone who runs the same command with the same seed. Where a claim was not
measured it says so. Several sections exist specifically to record where the
project believed something for a week that turned out to be an artefact of a
broken instrument.

The prose is fluent, and fluency is not evidence. A language model writes
confident explanations of its own mistakes as readily as of its successes, which
is precisely why this project leans as hard as it does on paired measurements and
on tests that fail: they are the parts that cannot be talked into agreeing. Read
the code the way you would read any code of unknown provenance — and if you find
something wrong, the arena is the fastest way to show it.

```
skat-ai/
  engine/     the rules, the double-dummy solver, the players     no dependencies
  arena/      duplicate matches, the belief trainer's Java side, the players
  jskat-ai/   an adapter that seats JSkat's players at this table
  external/   the drivers that seat XSkat and go-skat, as helper processes
  tools/      the overnight measurement run, the engine builds, the SkatZero driver
  native/     the same solver again in C++, for when Java is too slow
  docs/       the rules canon, the design record, the native solver, the outside engines
```

## The short version of why this exists

Card-game AI is unusually good at fooling the person writing it. A change makes
the player *look* smarter — it holds the ace back, it counts trumps — and the
only honest question is whether it wins more, which in a game this noisy takes
thousands of hands to answer. Every strength claim in this repository therefore
comes with a confidence interval, and several of them are records of a claim that
did not survive one.

The arena is what makes that affordable, and the two ideas in it are worth more
than any of the players:

**Duplicate deals.** Both sides play the same boards from the same seats, as in
duplicate bridge. What is compared is the *paired* difference, so the deal
itself — by far the largest term in a Skat score — cancels.

**Common random numbers.** Both sides are handed identical random streams, keyed
on the board and the seat and never on which side is which. Before that was true,
this arena measured a player against a bit-for-bit identical copy of itself as
2.4 game points weaker, and produced three consistent, reproducible, entirely
fictitious results before the control caught it. That story is in
[`arena/README.md`](arena/README.md), because a measuring instrument that has
never been caught lying is one nobody has checked.

## Getting started

```bash
git clone --recurse-submodules <this repository>
cd skat-ai
./gradlew test          # engine and arena, about 250 tests
./gradlew :arena:play   # deal a hand and play it yourself
```

Java 17 or newer. Nothing else is required: the engine has no third-party
dependency at all, and the arena needs only ONNX Runtime and JUnit, which Gradle
fetches. A trained belief model is in the repository, so the strongest players
work out of the box. Without `--recurse-submodules` everything still builds — you
simply have no JSkat opponents to measure against; the other outside engines are
fetched separately, see below.

### Play a hand

```bash
./gradlew :arena:play --args="--opponent=club --deals=3"
```

You are dealt ten cards, you bid, you pick up the skat and you play, on the
terminal. The opponent is named by its *arena id*, which is the same object the
measurements are about: `club` is the shipped middle level, `analyst` the top
one (the same player the arena calls `belief-32-adaptive-margin-ties`), `greedy`
the heuristic baseline you should be able to beat. A person is seated through the same `SkatAiProvider`
interface as every AI, so your cards are checked by the same legality rules and
your hand is scored by the same code.

### Run a match

```bash
./gradlew :arena:arena --args="--a=belief --b=search --boards=300 --seed=11"
```

```
belief - search = +2.121 game pts/game   95% CI [+1.454, +2.788]
Resolved: belief is stronger.
```

`--fixed-contract` skips the auction and compares card play alone; `--threads=N`
uses more cores. `tools/overnight-arena.sh` runs the whole ladder across three
seeds and pools the results, which takes a night and is how every table below was
produced.

## What is in the engine

Everything the rules need and nothing else — it compiles with an empty classpath,
which is checked, because this is also what goes into an Android APK.

| Package | What lives there |
| --- | --- |
| `dev.skatklar.demo` | cards, deck, contracts, the auction, the game engine, scoring |
| `dev.skatklar.demo.solve` | the double-dummy solver: alpha-beta over card points, a flat transposition table, an optional native backend |
| `dev.skatklar.demo.search` | determinized search: sample worlds consistent with what has been seen, solve each, vote |
| `dev.skatklar.demo.belief` | the learned card-location model, as three matrix multiplications and nothing else |
| `dev.skatklar.demo.ai` | the player levels a product would ship, and the provider interface everything is seated through |
| `dev.skatklar.demo.ramsch` | Ramsch and Schieberamsch, which are not a footnote in this canon |

The rules the engine implements are written down in
[`docs/rules.md`](docs/rules.md), and that document is the authority: where the
code and it disagree, the code is wrong.

## What is in the player

Three layers, each measured against the one below.

**A double-dummy solver.** Given all three hands, alpha-beta over card points
with a perfect-hash transposition table keyed on the three hands. It is the
ceiling: no honest player can beat it, and the distance to it is how far there is
left to go. There is a C++ implementation as well; the Java one is the
specification, and a parity test holds them to the same answers.

**Determinized search (PIMC).** The player does not see the other hands, so it
samples worlds consistent with everything it has observed — cards played, voids
shown, the bidding — solves each one, and votes. Sixteen worlds by default,
thirty-two at the top level, where doubling is worth about 2.6 game points.

**A learned belief.** Uniform sampling assumes the unseen cards are anywhere.
They are not: what a seat bid, and what it discarded, says a great deal about
what it holds. A small network (306 → 512×3 → 96) predicts, for every unseen
card, whether it lies left, right or in the skat, and the sampler draws from that
instead. Worth **+2.12 game points a game, 95% CI [+1.45, +2.79]** over the same
player sampling uniformly.

The network ships as `belief.bin`, a plain array of numbers read by
`BeliefNet` — a hundred lines of arithmetic, no runtime, no native libraries.
The arena measures that same implementation rather than the trainer's, because a
measurement of code you do not ship is a measurement of nothing.

**Three rules from the score sheet**, on top. The auction re-prices the hand
against only the worlds consistent with what the other seats said (an
opponent who held 44 holds the jacks in every world that survives); among
cards that win in equally many worlds, the one that wins by fifteen points
of cushion in the most worlds is played, because the worlds are guesses; and
the ties that leaves are broken by the position in the trick rather than by
the cheapest card. None of them learns anything about any opponent, which is
what lets them be measured against the whole field, and together they are
worth about two game points a game over the same player without them.

## Where it stands

The shipped player, `belief-32-adaptive-margin-ties` on the v2 belief,
against the field. Three seeds pooled by inverse variance, 300 boards a seed
(200 against go-skat), the auction played, passed-in boards voided;
2026-09-17.

| Match | game pts/game | 95% CI |
| --- | --- | --- |
| shipped − XSkat | +0.83 | [−0.49, +2.15] |
| shipped − go-skat | **+3.07** | [+1.72, +4.43] |
| shipped − JSkat AlgorithmAI | **+12.52** | [+11.30, +13.74] |
| the first two rules − the reference player without them | **+1.07** | [+0.32, +1.83] |
| the third rule, on top of the two | +0.23 | [−0.19, +0.66] |
| the v2 belief − the v1 belief, same player | **+0.69** | [+0.04, +1.33] |

Card play alone, at the objectively best makeable contract on each board
(`--fixed-contract --contracts=solver`), which is the only line where "stronger
than X" means card play rather than taste in games:

| Match | game pts/game | 95% CI |
| --- | --- | --- |
| belief-32 − SkatZero | **−3.58** | [−4.85, −2.31] |
| belief-32 − double-dummy solver (which sees everything) | **−5.38** | [−6.61, −4.14] |
| SkatZero − double-dummy solver | **−5.20** | [−6.50, −3.90] |

The first table says the player is level with XSkat and ahead of the rest.
The second says where the remaining points are: SkatZero, a self-play
reinforcement learner, is 3.6 points better at card play, yet paired board by
board through the common opponent the two are the *same* distance from
perfect play (+0.23 [−1.34, +1.80] between them). Both make about the same
amount of what perfect play punishes; SkatZero's edge is made against an
imperfect opponent, which is the part a determinized search cannot see
(it assumes an opponent who knows what it knows) and the part a policy trained
under the fog can. That is the open problem this repository is working on.

The ladder the app ships, every step resolved, full game (2026-09-15, taken
before the rules went on at the two lower levels too; that narrows the two
lower steps by about half a point and a point, and the next ladder run records
the exact spacing):

| Step | game pts/game |
| --- | --- |
| beginner → club | 4.2 |
| club → expert | 6.6 |
| expert → analyst | 2.6 |

[`arena/README.md`](arena/README.md) is the lab notebook behind all of it,
including the measurements that came out flat (an alpha-mu search: correct,
+0.006, kept and unused), the ones that were wrong the first time, and the
ceiling that was not one until the cheating player learned to discard.

## The trained model, and training your own

**A trained belief ships with this repository**, under `belief-model/`: the
weights, the shape descriptor, and the trainer's own recorded answers for
sixteen inputs. The current one (2026-09-17) was trained on 200,000 boards
played by seven programs -- five of ours and two outside engines -- because a
belief trained only on games between copies of one player learns where *that*
player puts its cards; widening the population was worth +0.69 [+0.04, +1.33]
a game with everything else held fixed. `tools/belief-v2.sh` is that run. So `belief` and `belief-32` are contestants from a fresh clone,
and the numbers in the table above can be reproduced rather than taken on trust.
The PyTorch checkpoint and the ONNX export are not included; neither is needed to
run or to verify anything.

Training your own is the interesting part anyway:

```bash
./gradlew :arena:export --args="--boards=200000 --threads=8"   # labelled positions
python arena/python/train_belief.py --data=belief-data --out=belief-model
python arena/python/export_weights.py --model=belief-model     # belief.bin
```

The exporter also writes `belief-parity.bin`: sixteen inputs and the outputs the
trainer produced for them. Every loader replays those before it lets the weights
play, because a model that is merely *wrong* — a truncated file, a changed
activation, a reordered block — loads and answers and looks fine, and shows up
only as a player that is mysteriously weak. The shipped model carries its parity
file for the same reason: you should not have to take these weights on trust
either.

## The outside opponents

Four programs that are not ours can sit at the arena's table, and every
mechanism that ships has had to be non-negative against all of them: a rule
that beats one bot and loses to another has learned that bot, not Skat. None of
them is redistributed here and nothing derived from any of them is part of the
engine. [`docs/external-bots.md`](docs/external-bots.md) is the full account;
[`docs/xskat.md`](docs/xskat.md) the licence question and the survey of what
else exists.

| id | what it is | licence | seated for |
| --- | --- | --- | --- |
| `jskat-new`, `jskat-ml-pro` | [JSkat](https://github.com/b0n541/jskat-multimodule): a rule-based player and a transformer trained on ISS games | Apache 2.0 | the full game; card play |
| `xskat`, `xskat-blind` | [XSkat 4.0](https://github.com/mfrasca/xskat) (Gunter Gerhardt, C, 2004): heuristic tables, the classic X11 program | custom permissive, not GPL | the full game |
| `go-skat` | [go-skat](https://github.com/dranidis/go-skat) (Dimitris Dranidis, Go, 2022): heuristics plus a sampled alpha-beta | MIT | the full game |
| `skatzero` | [SkatZero](https://github.com/Jimboom7/SkatZero): deep Monte Carlo self-play, nine networks, first on the ISS leaderboard by its author's account | MIT | card play only |

**JSkat** is a Gradle submodule under `third_party/jskat`, adapted by
`jskat-ai/`. It points at a **modified fork**,
[honkphluxx/jskat](https://github.com/honkphluxx/jskat), branch `skatklar`; the
five changes are listed in that repository's `CHANGES.md`, where the Apache
licence asks for them. Two are the reason a fork exists: the arena cannot run
duplicate deals against players whose shared random generators cannot be
seeded, and upstream's `getSuitMultiplier` loops forever on a holding with no
trumps — it froze a 1500-board run for 31 minutes at 100% of a core. The
learned players need models that are **not in JSkat's repository**, about
113 MB from [skat-ml-models](https://github.com/avaskys/skat-ml-models):

```bash
git submodule update --init third_party/jskat
cd third_party/jskat && ./gradlew :jskat-base:downloadMlModels
```

**XSkat and go-skat** are C and Go programs with no "given this position, play
a card" entry point, so each runs as a helper process that plays a whole game
of its own, with the arena supplying the other two seats' cards where a human
used to; the protocol is a dozen lines a game (`HELLO`, `GAME`, `PLAY`,
`PLAYED`, …) and is documented in `docs/external-bots.md`. That means handing
the helper cards its seat should not see, which is treated as a risk to be
measured: `-Dskat.probe=<n>` asks the helper for every card again with the
unseen cards reshuffled, and a player reading only its own hand answers the
same card every time (XSkat reads the skat; `xskat-blind` is dealt a sampled
one instead, and the two are within 0.01 of each other). Both are fetched into
`third_party/` and built in place, no upstream file modified:

```bash
# XSkat 4.0: xskat.de is gone; Debian's pool has the pristine tarball
mkdir -p third_party/xskat && curl -L http://deb.debian.org/debian/pool/main/x/xskat/xskat_4.0.orig.tar.gz | tar xz -C third_party/xskat --strip-components=1
git clone https://github.com/dranidis/go-skat third_party/go-skat
./tools/build-external-bots.sh          # needs a C compiler and Go
```

**SkatZero** is a different kind of opponent: no search, no rules knowledge,
a six-layer network plus an LSTM over the history, trained by self-play over
about 1.5 billion games per model. Its bidding is a heuristic bolted on
afterwards, so `tools/skatzero-bot.py` does not bid and it is measured at
fixed contracts only. It is honest by construction — the driver keeps only
the seat's own cards, so the probe has nothing to find — and it is the
strongest card play on the ladder, the reference the training plan's
population constraint wanted: a player whose style owes nothing to ours.

```bash
git clone https://github.com/Jimboom7/SkatZero third_party/skatzero   # models included, 52 MB
python -m pip install numpy onnxruntime
```

A checkout that has fetched none of them builds and runs exactly as before:
the contestants are registered from hooks that look for the binaries, and a
missing engine costs the arena an opponent rather than a run.

## Contributing, and the one house rule

Claims about strength need a number and an interval. "This should be better" is
not a reason to merge, and neither is "it plays more sensibly"; the arena is
cheap to run and it has repeatedly disagreed with both. A change that cannot be
measured is fine — most changes cannot be — but then say so rather than implying
it helps.

## Licence

BSD 3-Clause; see [`LICENSE`](LICENSE). Third-party components and their licences
are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
