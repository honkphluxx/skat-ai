"""
Where does one student play a better card than another? (plan 2.10, route 1)

    python arena/python/where_gain.py --data allcards-data/r1-k8-s9001 \\
        --pair policy-b600-pa40:policy-oc2-ev --pair policy-b600-pa40-i2:policy-oc2-ev-i2 ...

On the held-out boards of all-cards corpora (the training split's tenth, never
trained on: `--split-seed`, `--val-fraction` as train_outcomes.py), every
student of every pair picks its top card; the labels say what each card is
worth -- U, the mean game value a play-out from the decider's side plus the
win share and card points as tiebreaks (train_outcomes.utility_of). For a pair
base:model the difference U(model's card) - U(base's card) is the label's
verdict on the change, decision by decision; the pairs (draws) are averaged
record by record. Printed by group -- the decider's role, the trick, the
contract, and in a format-2 corpus how many seats the student held:

  share     of the held-out decisions in the group
  differ    how often the two students' cards differ
  gain      the mean difference a decision, with a 95% interval clustered by
            board (the decisions of one board are not independent)
  of all    the group's share of the total difference

The labels are a corpus's own: made with that corpus's student playing on (r1:
pa40, r2: policy-oc1-ev, r3: policy-oc2-ev against a pool). A gain here is
"a better card if that student plays on", not a game result; what agrees
across corpora is the part to believe, and the arena is the judge.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np
import torch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from allcards_data import AllCardsCorpus, Records, split_by_board  # noqa: E402
from train_outcomes import utility_of  # noqa: E402
from train_policy import NEG, PolicyNet  # noqa: E402

CONTRACTS = ("Diamonds", "Hearts", "Spades", "Clubs", "Grand")


def load_model(directory, device):
    d = json.loads((pathlib.Path(directory) / "model.json").read_text())
    model = PolicyNet(d["inputs"], d["hidden"], d["layers"]).to(device)
    model.load_state_dict(torch.load(pathlib.Path(directory) / "policy.pt", map_location=device))
    model.eval()
    return model


def top_cards(model, features, legal, scale, device, batch=16384):
    out = np.empty(len(features), dtype=np.int64)
    with torch.no_grad():
        for at in range(0, len(features), batch):
            x = torch.from_numpy(features[at:at + batch].astype(np.float32) / scale).to(device)
            p, _ = model(x)
            mask = torch.from_numpy(legal[at:at + batch]).to(device)
            out[at:at + batch] = p.masked_fill(~mask, NEG).argmax(dim=1).cpu().numpy()
    return out


def clustered(values, clusters):
    """Mean and its 95% half-width, clustered by board."""
    n = len(values)
    if n == 0:
        return float("nan"), float("nan")
    mean = values.mean()
    _, inverse = np.unique(clusters, return_inverse=True)
    sums = np.bincount(inverse, weights=values)
    counts = np.bincount(inverse)
    resid = sums - counts * mean
    se = np.sqrt((resid ** 2).sum()) / n
    return float(mean), float(1.96 * se)


def groups(r):
    """(name, mask) for every group printed."""
    out = [("all", np.ones(len(r), dtype=bool)),
           ("declarer", r.declarer), ("defender", ~r.declarer)]
    for lo, hi in ((1, 3), (4, 6), (7, 9)):
        t = (r.trick >= lo) & (r.trick <= hi)
        out.append((f"tricks {lo}-{hi}", t))
    for role, m in (("declarer", r.declarer), ("defender", ~r.declarer)):
        for lo, hi in ((1, 3), (4, 6), (7, 9)):
            out.append((f"  {role} {lo}-{hi}", m & (r.trick >= lo) & (r.trick <= hi)))
    out.append(("suit games", r.contract < 4))
    out.append(("grand", r.contract == 4))
    out.append(("defender, leading", ~r.declarer & (r.place == 0)))
    out.append(("defender, following", ~r.declarer & (r.place > 0)))
    out.append(("declarer, leading", r.declarer & (r.place == 0)))
    out.append(("declarer, following", r.declarer & (r.place > 0)))
    if r.students.min() < 3:
        for c in (1, 2, 3):
            out.append((f"student at {c} seat(s)", r.students == c))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", nargs="+", required=True, help="all-cards corpora (or held-out cuts of them)")
    parser.add_argument("--pair", action="append", required=True, help="base:model, a draw each, repeated")
    parser.add_argument("--heldout-only", action="store_true",
                        help="the files hold the held-out records already (heldout.bin beside the format)")
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--split-seed", type=int, default=1)
    parser.add_argument("--win-weight", type=float, default=1.0)
    parser.add_argument("--points-weight", type=float, default=0.01)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    device = torch.device(args.device)
    pairs = [p.split(":") for p in args.pair]
    if any(len(p) != 2 for p in pairs):
        raise SystemExit("a pair is base:model")
    models = {}
    for base, model in pairs:
        for m in (base, model):
            if m not in models:
                models[m] = load_model(m, device)

    for data in args.data:
        directory = pathlib.Path(data)
        if args.heldout_only:
            layout = json.loads((directory / "allcards-format.json").read_text())
            spec = json.loads(next(directory.glob("encoding-v*.json")).read_text())
            raw = np.fromfile(directory / "heldout.bin", dtype=np.uint8).reshape(-1, layout["record_bytes"])
            r = Records(raw)
            scale = spec["scale"]
            label = layout.get("model", "?")
        else:
            corpus = AllCardsCorpus(directory)
            all_records = corpus.load()
            _, val = split_by_board(all_records.board, args.val_fraction, seed=args.split_seed)
            r = Records(all_records.raw[val])
            scale = corpus.scale
            label = corpus.layout.get("model", "?")
        u = utility_of(r, args.win_weight, args.points_weight)
        rows = np.arange(len(r))
        board = r.board * 1_000_003 + r.seed
        picks = {m: top_cards(net, r.features, r.legal, scale, device) for m, net in models.items()}
        print(f"\n=== {directory.name}: {len(r):,} held-out decisions, {len(np.unique(board)):,} boards; labels with "
              f"{label} playing on")
        gain = np.zeros(len(r))
        differ = np.zeros(len(r))
        for base, model in pairs:
            gain += u[rows, picks[model]] - u[rows, picks[base]]
            differ += picks[model] != picks[base]
        gain /= len(pairs)
        differ /= len(pairs)
        names = sorted({p[1].split("-i")[0] for p in pairs}), sorted({p[0].split("-i")[0] for p in pairs})
        print(f"    {' '.join(names[0])} against {' '.join(names[1])}, {len(pairs)} draw pair(s): U of the model's "
              f"card minus the base's (game value a play-out)")
        total = gain.sum()
        print(f"    {'group':26s} {'share':>6s} {'differ':>7s} {'gain':>8s} {'95%':>7s} {'of all':>7s}")
        for name, m in groups(r):
            if m.sum() == 0:
                continue
            mean, half = clustered(gain[m], board[m])
            print(f"    {name:26s} {m.mean():6.1%} {differ[m].mean():7.1%} {mean:+8.3f} {half:7.3f} "
                  f"{gain[m].sum() / total if total else float('nan'):7.1%}")


if __name__ == "__main__":
    sys.exit(main())
