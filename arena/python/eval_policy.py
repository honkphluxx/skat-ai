"""
How many worlds is the student worth? (plan 2.10, I3)

    python3 eval_policy.py --data ../../teacher-data-v2 --model ../../policy-model [--model ...]

A yardstick in the teacher's own units, before any game is played. Every
held-out record carries the teacher's verdict world by world for 64 worlds.
Split them: worlds 0-31 are what a smaller search would have seen, worlds
32-63 are the referee. A k-world search is simulated by voting over the first
k worlds alone (1, 2, 4 ... 32), its ties shared out evenly; the student plays
its policy head's card. Both are then scored on worlds 32-63, which neither
the smaller search nor the student has seen -- the student never saw any
world of a held-out deal, and its training deals are other deals.

What is printed, per player, on the referee's 32 worlds:

  regret   worlds the card holds fewer than the referee's best card
  best     the card is one of the referee's top-voted

With --skatzero (a SkatZero checkout; a format-3 corpus), SkatZero is scored
as one more player on the same decisions: skatzero_eval.py rebuilds its
state from the record. Where the record leaves the current trick's order
open and the order changes SkatZero's card, the decision is left out for
every player, so the tables stay like with like.

So "the student sits between the 8- and the 16-world search" is a statement
about the same positions, the same worlds and the same question. What it is
not is a game result: the search's tiebreaks (the cushion, the ladder) are
left out here -- ties are shared evenly, which slightly flatters nobody and
slightly undersells the search -- and the teacher's own card is shown only
for orientation, since the teacher saw the referee's worlds.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import time

import numpy as np
import torch

from teacher_data import TeacherCorpus, popcount64, split_by_board
from train_policy import Batches, PolicyNet, predict

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

LOW = np.uint64(0xFFFFFFFF)


def referee(records, rows):
    """Each card's vote over worlds 32-63, and the legal mask, for the rows."""
    bits = records.verdict_bits_by_card()[rows]
    votes = popcount64(bits >> np.uint64(32))
    legal = records.legal[rows]
    top = np.where(legal, votes, -1).max(axis=1)
    return bits, votes, legal, top


def score_pick(votes, top, picks):
    idx = np.arange(len(picks))
    got = votes[idx, picks]
    return (top - got).astype(np.float64), (got == top).astype(np.float64)


def score_search(bits, votes, legal, top, k):
    """Expected regret and best-rate of a k-world search, ties shared evenly."""
    mask = np.uint64((1 << k) - 1)
    small = popcount64(bits & mask)
    small = np.where(legal, small, -1)
    chosen = small == small.max(axis=1, keepdims=True)
    share = chosen / chosen.sum(axis=1, keepdims=True)
    regret = ((top[:, None] - votes) * share).sum(axis=1)
    best = ((votes == top[:, None]) * share).sum(axis=1)
    return regret, best


def score_random(votes, legal, top):
    share = legal / legal.sum(axis=1, keepdims=True)
    return ((top[:, None] - votes) * share).sum(axis=1), ((votes == top[:, None]) * share).sum(axis=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", default="../../teacher-data-v2")
    parser.add_argument("--model", action="append", default=[],
                        help="a policy model directory; may be given more than once")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--skatzero", default=None,
                        help="a SkatZero checkout with its ONNX models: score SkatZero too (format-3 corpus)")
    parser.add_argument("--skatzero-workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    parser.add_argument("--skatzero-every", type=int, default=1,
                        help="score every k-th held-out decision only, for every player alike (SkatZero is slow)")
    args = parser.parse_args()

    corpus = TeacherCorpus(args.data)
    records = corpus.load(limit=args.limit or None)
    splits = set()
    players = []
    for directory in args.model:
        descriptor = json.loads((pathlib.Path(directory) / "model.json").read_text())
        splits.add((descriptor["split_rule"], descriptor["split_seed"], descriptor["split_fraction"]))
        players.append((pathlib.Path(directory).name, directory, descriptor))
    if len(splits) > 1:
        sys.exit(f"the models were held out by different splits: {splits}")
    rule, seed, fraction = splits.pop() if splits else ("splitmix64-v1", 1, 0.1)
    if (records.worlds < 64).any() or (records.verdict_worlds < 64).any():
        sys.exit("this yardstick needs 64 verdict worlds on every record")
    _, val = split_by_board(records.deal, fraction, seed=seed)
    by_skatzero = None
    if args.skatzero:
        # SkatZero cannot be scored where the record leaves the current
        # trick's order open and the order changes its card (see
        # skatzero_eval.py); those rows go for every player, so every row of
        # every table is the same decisions for all of them.
        from skatzero_eval import AMBIGUOUS, MISMATCH, skatzero_picks
        val = val[::args.skatzero_every]
        started = time.time()
        picks = skatzero_picks(corpus, records, val, args.skatzero, workers=args.skatzero_workers)
        ambiguous = int((picks == AMBIGUOUS).sum())
        mismatched = int((picks == MISMATCH).sum())
        print(f"SkatZero: {len(val):,} decisions in {time.time() - started:.0f} s; left out for every player: "
              f"{ambiguous:,} where the current trick's order is open and decides its card, "
              f"{mismatched:,} where its legal cards are not the record's")
        if mismatched > 0.005 * len(val):
            sys.exit("more than 0.5% of the rebuilt positions disagree with the record's legal cards: "
                     "the reconstruction is wrong somewhere")
        keep = picks >= 0
        val = val[keep]
        by_skatzero = picks[keep]
    bits, votes, legal, top = referee(records, val)
    print(f"{len(val):,} held-out decisions ({rule}, seed {seed}); scored on worlds 32-63")
    print()

    rows = []  # (name, regret, best)
    picks = []  # (name, cards) for the cushion table
    device = torch.device(args.device)
    for name, directory, d in players:
        batches = Batches(corpus, records, device, d.get("play_state", False))
        model = PolicyNet(d["inputs"], d["hidden"], d["layers"]).to(device)
        model.load_state_dict(torch.load(pathlib.Path(directory) / "policy.pt", map_location=device))
        by_policy, by_value, _, _ = predict(model, batches, val)
        rows.append((f"student {name}", *score_pick(votes, top, by_policy)))
        rows.append((f"  its value head", *score_pick(votes, top, by_value)))
        # The hand-over's question (plan 2.10, I4): the student lost the
        # teacher half a point, mostly as declarer, and the value head is the
        # better of the two there on the whole game. Both heads come from the
        # same forward pass, so this costs nothing at the table.
        mixed = np.where(records.declarer[val], by_value, by_policy)
        rows.append((f"  value as declarer", *score_pick(votes, top, mixed)))
        picks.append((f"student {name}", by_policy))
        picks.append(("  its value head", by_value))
        picks.append(("  value as declarer", mixed))
    if by_skatzero is not None:
        rows.append(("SkatZero", *score_pick(votes, top, by_skatzero)))
        picks.append(("SkatZero", by_skatzero))
    for k in (1, 2, 4, 8, 16, 32):
        rows.append((f"search, {k} world{'s' if k > 1 else ''}", *score_search(bits, votes, legal, top, k)))
    rows.append(("a random legal card", *score_random(votes, legal, top)))
    rows.append(("(the teacher's card)", *score_pick(votes, top, records.chosen[val])))

    trick = records.trick[val]
    declarer = records.declarer[val]
    groups = [("all", np.ones(len(val), dtype=bool)), ("declarer", declarer), ("defender", ~declarer),
              ("tricks 1-3", trick <= 3), ("tricks 4-6", (trick >= 4) & (trick <= 6)), ("tricks 7-9", trick >= 7)]
    print("regret, in worlds of 32")
    print(f"    {'':26s}" + "".join(f"{g:>12s}" for g, _ in groups))
    for name, regret, _ in rows:
        print(f"    {name:26s}" + "".join(f"{regret[m].mean():12.3f}" for _, m in groups))
    print()
    print("one of the referee's top-voted cards")
    print(f"    {'':26s}" + "".join(f"{g:>12s}" for g, _ in groups))
    for name, _, best in rows:
        print(f"    {name:26s}" + "".join(f"{best[m].mean():12.1%}" for _, m in groups))
    print()
    opening = trick <= 2
    roles = [("declarer 1-2", declarer & opening), ("defender 1-2", ~declarer & opening),
             ("declarer 3-9", declarer & ~opening), ("defender 3-9", ~declarer & ~opening)]
    print("by role, in the two tricks the hybrid gives the student and after them")
    print(f"    regret, in worlds of 32{'':3s}" + "".join(f"{g:>14s}" for g, _ in roles))
    for name, regret, _ in rows:
        print(f"    {name:26s}" + "".join(f"{regret[m].mean():14.3f}" for _, m in roles))
    print(f"    {'(decisions)':26s}" + "".join(f"{int(m.sum()):14,d}" for _, m in roles))
    print(f"    one of the top-voted{'':6s}" + "".join(f"{g:>14s}" for g, _ in roles))
    for name, _, best in rows:
        print(f"    {name:26s}" + "".join(f"{best[m].mean():14.1%}" for _, m in roles))
    print()
    # The tiebreak the yardstick above cannot see. Where the teacher's vote
    # tied at the top it asked a second question -- in how many worlds does
    # the card hold with fifteen points to spare -- and that decides points
    # (Schneider, the margin) rather than the game. Recorded over all 64
    # worlds, so there is no referee half: this is the student against the
    # teacher's own answer on deals the student never trained on, and the
    # searches have no row.
    asked = records.cushion_asked[val]
    cushion = records.cushion[val].astype(np.int64)
    in_top = (votes_all := records.votes[val].astype(np.int64)) == \
        np.where(records.legal[val], votes_all, -1).max(axis=1, keepdims=True)
    best_cushion = np.where(in_top & records.legal[val], cushion, -1).max(axis=1)
    print("the tiebreak: where the teacher's vote tied at the top and it asked for the cushion")
    print(f"    cushion short, worlds of 64 " + "".join(f"{g:>14s}" for g, _ in roles))
    idx = np.arange(len(val))
    for name, cards in picks + [("(the teacher's card)", records.chosen[val])]:
        on_top = in_top[idx, cards]
        short = np.where(asked & on_top, best_cushion - cushion[idx, cards], 0)
        cells = []
        for _, m in roles:
            sel = m & asked & on_top
            cells.append(f"{short[sel].mean():14.2f}" if sel.any() else f"{'-':>14s}")
        print(f"    {name:28s}" + "".join(cells))
    print(f"    {'(decisions asked)':28s}" + "".join(f"{int((m & asked).sum()):14,d}" for _, m in roles))
    print("    Only where the card was one of the top-voted; a card below the top is regret above.")
    print()
    print("regret by trick, in worlds of 32 -- where a student could hand over to the search")
    tricks = [(f"trick {t}", trick == t) for t in range(1, 10)]
    print(f"    {'':26s}" + "".join(f"{g:>9s}" for g, _ in tricks))
    for name, regret, _ in rows:
        print(f"    {name:26s}" + "".join(f"{regret[m].mean():9.3f}" for _, m in tricks))
    print(f"    {'(decisions)':26s}" + "".join(f"{int(m.sum()):9,d}" for _, m in tricks))
    print()
    print("The teacher's card saw the referee's worlds; it is there for orientation, not as a rung.")


if __name__ == "__main__":
    main()
