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
  native/     the same solver again in C++, for when Java is too slow
  docs/       the rules canon, the design record, the native solver
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
simply have no JSkat opponents to measure against.

### Play a hand

```bash
./gradlew :arena:play --args="--opponent=club --deals=3"
```

You are dealt ten cards, you bid, you pick up the skat and you play, on the
terminal. The opponent is named by its *arena id*, which is the same object the
measurements are about: `club` is the shipped middle level, `belief` is the one
that is level with JSkat's transformer, `greedy` is the heuristic baseline you
should be able to beat. A person is seated through the same `SkatAiProvider`
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

## Where it stands

Pooled over three seeds by inverse variance, with the auction played:

| Match | game pts/game | 95% CI |
| --- | --- | --- |
| belief − search | **+2.12** | [+1.45, +2.79] |
| belief − JSkat AlgorithmAI | **+12.58** | [+10.92, +14.24] |
| belief-32 − JSkat MLPlayerPro (forced contracts) | **+0.19** | [−0.95, +1.33] |
| search − double-dummy solver (which sees everything) | +2.65 | [+0.39, +4.91] |

The third line is the one to read carefully: at 32 sampled worlds this player is
**level with JSkat's transformer**, not ahead of it — the interval contains zero
and was never going to resolve at this sample size. The fourth is a joke at the
solver's expense and a real result: perfect card sight loses the full game to a
calibrated bidding rule, because the solver declares 42% of boards and this
player declares 15%.

The ladder a product would ship, each step resolved:

| Step | game pts/game |
| --- | --- |
| beginner → club | 3.4 |
| club → expert | 5.9 |
| expert → analyst | 3.2 |

[`arena/README.md`](arena/README.md) is the lab notebook behind all of it,
including the measurements that came out flat (an alpha-mu search: correct,
+0.006, kept and unused) and the ones that were wrong the first time.

## The trained model, and training your own

**A trained belief ships with this repository**, under `belief-model/`: the
weights, the shape descriptor, and the trainer's own recorded answers for
sixteen inputs. So `belief` and `belief-32` are contestants from a fresh clone,
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

## JSkat

[JSkat](https://github.com/jskat/jskat) is a submodule under
`third_party/jskat`, and it is the only outside opponent this project can measure
itself against. It is used for exactly that: `jskat-ai/` adapts its players to
this engine's table so the arena can seat them. Nothing derived from it is part
of the engine or of anything that ships.

If you cloned without submodules:

```bash
git submodule update --init third_party/jskat
```

## Contributing, and the one house rule

Claims about strength need a number and an interval. "This should be better" is
not a reason to merge, and neither is "it plays more sensibly"; the arena is
cheap to run and it has repeatedly disagreed with both. A change that cannot be
measured is fine — most changes cannot be — but then say so rather than implying
it helps.

## Licence

BSD 3-Clause; see [`LICENSE`](LICENSE). Third-party components and their licences
are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
