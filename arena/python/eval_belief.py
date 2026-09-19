"""
Scores a trained belief model on a corpus it was not necessarily trained on.

    python3 eval_belief.py --model ../../belief-model-mixed --data ../../belief-data-null
    python3 eval_belief.py --model ../../belief-model-mixed --data ../../belief-data-v2 --by-contract

train_belief.py already prints a held-out number, but it is the held-out number
of *its own* split of *its own* corpus, which answers "did this training run
converge" and nothing else. The question B2 step 3 asks is a different shape:
one model, several corpora, and the same model read on slices of one corpus.

    - does the mixed net know Null as well as a net trained on Null alone?
      -> the same --data, two --model
    - did adding Null cost the mixed net anything on the trump positions the
      player actually meets 97% of the time?
      -> the same --model, --by-contract, against today's model

Both comparisons are only meaningful if the held-out split is identical across
the runs being compared, so the split is recomputed here from the board ids with
the same seed and fraction the trainer used, rather than stored. Same corpus and
same seed therefore means the same held-out boards, whichever model is being
read -- and a model is never scored on a board it may have trained on unless
--all is passed, which says so in the output.

The number that matters is accuracy over the uniform baseline printed beside it,
not accuracy. Guessing in proportion to how many places are left in each hand is
already right about 47% of the time on trump deals, and a Null slice has its own
baseline, so the two are not comparable as raw percentages.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np
import torch

from belief_data import SPLIT_RULE, Corpus, score, uniform_baseline
from train_belief import CARDS, BeliefNet, evaluate

CONTRACTS = ("Diamonds", "Hearts", "Spades", "Clubs", "Grand", "Null", "Ramsch")

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")


def load(directory, inputs, device):
    """The saved net, rebuilt from the shape model.json recorded beside it."""
    path = pathlib.Path(directory)
    spec = json.loads((path / "model.json").read_text())
    if spec["inputs"] != inputs:
        raise ValueError(
            f"{path.name} wants {spec['inputs']} inputs and this corpus has "
            f"{inputs}: the model and the corpus are different encodings")
    model = BeliefNet(inputs, spec["hidden"], spec["layers"]).to(device)
    model.load_state_dict(torch.load(path / "belief.pt", map_location=device))
    model.eval()
    return model, spec


def audit(spec, name, data_name, seed, fraction, scoring_all):
    """
    Is this model entitled to be scored on the set it is about to be scored on?

    A held-out number means one thing only: the model never saw these deals.
    Score a model against a split it was not trained under and that guarantee is
    gone -- the "held-out" tenth is mostly its training data, and it reads as a
    model that is five points better than it is. This is not hypothetical. It
    happened on 2026-09-19, between a morning that changed the split rule and an
    afternoon that compared a model from three days earlier against one trained
    after the change, and the two splits overlapped by 6%.

    So the rule, the seed and the fraction go into every model.json, and a
    mismatch is said out loud here rather than quietly folded into a percentage.
    It is a warning and not a refusal, because scoring a model on data it has
    seen is sometimes exactly what is wanted -- that is what --all is for -- and
    the thing that does harm is doing it without knowing.
    """
    if scoring_all:
        return
    stored = spec.get("split_rule")
    if stored is None:
        print(f"  !! {name} does not say how it was split. It predates "
              f"split_rule ({SPLIT_RULE}), so the held-out tenth below is "
              f"probably its training data and the number is inflated. "
              f"Retrain it before comparing.")
    elif stored != SPLIT_RULE:
        print(f"  !! {name} was split by {stored} and this is {SPLIT_RULE}: "
              f"it was not held out of what follows. Retrain it.")
    elif spec.get("split_seed") != seed or spec.get("split_fraction") != fraction:
        print(f"  !! {name} was split at seed {spec.get('split_seed')} / "
              f"fraction {spec.get('split_fraction')} and this is {seed} / "
              f"{fraction}: a different tenth. Pass --seed and --val-fraction "
              f"to match, or the number is inflated.")
    elif spec.get("trained_on") and spec["trained_on"] != data_name:
        # Not a problem -- it is the whole point of this script -- but worth
        # saying, because a model scored on its own corpus and one scored on a
        # stranger's are different claims and the output looks identical.
        print(f"  (trained on {spec['trained_on']}, scored on {data_name})")


def report(name, model, x, target, mask, corpus, device):
    """One line: how often the model is right, and how often guessing is."""
    if len(x) == 0:
        print(f"  {name:12s}          no records")
        return
    accuracy, nll = evaluate(model, x, target, mask, device)
    baseline = np.repeat(uniform_baseline(corpus, x)[:, None, :], CARDS, axis=1)
    base_accuracy, _ = score(baseline, target, mask)
    print(f"  {name:12s} {len(x):9,d} records   {accuracy:6.1%} correct   "
          f"baseline {base_accuracy:6.1%}   {accuracy - base_accuracy:+6.1%}   "
          f"nll {nll:.4f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--data", required=True)
    parser.add_argument("--by-contract", action="store_true",
                        help="one line per contract as well as the total")
    parser.add_argument("--all", action="store_true",
                        help="score every record, not only the held-out boards")
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=1,
                        help="must match the seed the model was trained with, "
                             "or the held-out split is not the held-out split")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()

    corpus = Corpus(args.data)
    x, target, mask, board = corpus.load(packed=True)
    if args.limit:
        x, target, mask, board = (a[:args.limit] for a in (x, target, mask, board))

    if args.all:
        rows = np.arange(len(x))
        print(f"{pathlib.Path(args.model).name} on ALL of "
              f"{pathlib.Path(args.data).name} -- includes boards it may have trained on")
    else:
        _, rows = corpus.split_by_board(board, args.val_fraction, seed=args.seed)
        print(f"{pathlib.Path(args.model).name} on the held-out tenth of "
              f"{pathlib.Path(args.data).name} (seed {args.seed})")

    x = corpus.unpack(x[rows])
    target = target[rows].astype(np.int64)
    mask = mask[rows]

    device = torch.device(args.device)
    model, spec = load(args.model, corpus.size, device)
    print(f"encoding v{corpus.version}, {corpus.size} inputs, "
          f"{spec['hidden']}x{spec['layers']}")
    audit(spec, pathlib.Path(args.model).name, pathlib.Path(args.data).resolve().name,
          args.seed, args.val_fraction, args.all)
    print()

    report("everything", model, x, target, mask, corpus, device)
    if args.by_contract:
        print()
        contract = corpus.slice(x, "contract")
        for slot, label in enumerate(CONTRACTS):
            rows = contract[:, slot] > 0.5
            if not rows.any():
                continue
            report(label, model, x[rows], target[rows], mask[rows], corpus, device)


if __name__ == "__main__":
    main()
