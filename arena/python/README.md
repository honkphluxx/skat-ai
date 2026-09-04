# The belief trainer

Where are the twenty-two cards I cannot see? The model answers that for each of
the 32 cards with one of three places — my left-hand opponent, my right-hand
opponent, the skat — and the search player samples its worlds from the answer
instead of drawing them uniformly.

## The order to do things in

```bash
# 1. Generate a corpus (Java side, in the repository root)
./gradlew :arena:export --args="--boards=100000 --threads=8 --out=belief-data"

# 2. Check it before spending a night on it. Seconds, and no GPU needed.
cd arena/python && python3 check_data.py ../../belief-data

# 3. Train, from an interpreter that has a CUDA torch (see requirements.txt)
.venv/Scripts/python arena/python/train_belief.py \
    --data belief-data --out belief-model
```

**The interpreter is not a detail.** Python 3.14 has no CUDA wheels for torch —
only CPU builds are published, and the tracking issue is closed as not planned —
so a 3.14 on PATH gives you either an install error or, worse, a CPU build that
runs and is a hundred times too slow. `requirements.txt` has the commands for
putting a 3.12 in a venv beside the repository. The check that matters is
`torch.cuda.is_available()` printing True before anything is timed.

Step 2 is not a formality. Every bug this pipeline has had so far was silent: a
feature that was never populated, a split that leaked whole deals, a presence bit
left standing over a cleared block. None of them raise an exception and all of
them produce a model that trains beautifully and plays badly. `check_data.py`
exists to turn that class of failure into a line that says FAIL.

## What the numbers mean

The trainer prints accuracy against a **uniform sampler baseline** on the same
held-out boards, and that comparison is the only figure worth reading. Guessing
each unseen card in proportion to how many places are left in each hand is
already right about **47%** of the time — and that is exactly what the player
this model has to improve on believes today. An 80% model sounds excellent and
would be worth about thirty points of nothing if the baseline were 79%.

The final measurement is not here at all. It is tournament points in the arena,
with everything else about the search player held fixed and only its
`WorldSource` swapped. The belief sweep was built for precisely that comparison.

## Three decisions, so they are not re-litigated by accident

**Forgetting happens in the trainer, not in the corpus.** The exporter records
every position once, at perfect memory. `Forgetting` then drops observations with
the distribution a given personality produces — independently at `1 - memory`,
with the counting blocks surviving at `sqrt(memory)`, matching `Personality`. So
one generated corpus serves every level, and the dropout stays a knob to tune
rather than a property baked into a terabyte of files.

**Every drop clears a presence bit.** Zeroing the bidding block without clearing
its bit does not say "I forgot the auction", it says "nobody bid" — a false fact
rather than an absent one, and a model told that nobody bid will place the jacks
somewhere they are not. Confidently wrong is worse than uncertain.

**The model does not enforce a legal deal.** Ten cards left, ten right, two
buried is a constraint a per-card classifier cannot respect, and the sampler
imposes it downstream for free by drawing whole arrangements and rejecting
impossible ones. Baking it in here would trade a clean training signal for
something already handled.

## The layout is read, never assumed

`encoding-vN.json` is written beside the shards by the exporter, from the same
`BeliefEncoding.FIELDS` the Java side encodes with. Both halves read that file;
nothing here knows an offset by number. A version mismatch is a loud failure,
which is the cheapest possible version of the most expensive bug in a project
like this — the trainer and the app quietly disagreeing about what input 137 is.
