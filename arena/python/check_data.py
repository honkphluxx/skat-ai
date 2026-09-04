"""
Checks on the half of the pipeline that does not need a GPU.

Run it against any corpus directory; it takes seconds and it is the thing to run
before starting a night of training:

    python3 check_data.py ../../belief-data

What it is really guarding is the class of bug that does not announce itself. A
feature read at the wrong offset, a validation split that leaks whole deals, a
memory simulation that forgets a block but leaves its presence bit standing --
each of those produces a model that trains beautifully and plays badly, and none
of them raises an exception.
"""

from __future__ import annotations

import sys

import numpy as np

from belief_data import Corpus, Forgetting


def check(name, condition, detail=""):
    status = "ok  " if condition else "FAIL"
    print(f"  [{status}] {name}{'  -- ' + detail if detail and not condition else ''}")
    return condition


def main(directory):
    corpus = Corpus(directory)
    x, target, mask, board = corpus.load()
    print(f"encoding v{corpus.version}: {corpus.size} inputs, "
          f"{corpus.record_bytes} bytes a record")
    print(f"{len(x):,} records from {len(np.unique(board)):,} boards")
    print()

    good = True
    print("the record itself")
    good &= check("features are in [0, 1]", x.min() >= 0 and x.max() <= 1)
    good &= check("every record has something to guess", mask.sum(axis=1).min() >= 1)
    good &= check("and never more than the 22 a seat cannot see",
                  mask.sum(axis=1).max() <= 22)
    good &= check("labels are one of the three places",
                  set(np.unique(target[mask == 1]).tolist()) <= {0, 1, 2})
    good &= check("a card in my own hand is never a label",
                  not (corpus.slice(x, "my_hand")[mask == 1] > 0).any())

    print()
    print("one-hots")
    for block in ("contract", "declarer", "trick_leader"):
        sums = corpus.slice(x, block).sum(axis=1)
        good &= check(f"{block} sums to one", np.allclose(sums, 1),
                      f"saw {sorted(set(np.round(sums, 3).tolist()))[:4]}")

    print()
    print("the Schieben")
    schieben = corpus.slice(x, "schieben_present")[:, 0] > 0.5
    ramsch = corpus.slice(x, "contract")[:, 6] > 0.5
    good &= check("recorded exactly on the Ramsch deals",
                  bool((schieben == ramsch).all()))
    if schieben.any():
        pushed = corpus.slice(x[schieben], "pushed_on").sum(axis=1)
        received = corpus.slice(x[schieben], "received").sum(axis=1)
        good &= check("two cards pushed on", np.allclose(pushed, 2))
        good &= check("two cards received", np.allclose(received, 2))
        buried = corpus.slice(x[schieben], "pushed_to_skat")[:, 0] > 0.5
        if buried.any():
            asked = (corpus.slice(x[schieben][buried], "pushed_on")
                     * mask[schieben][buried]).sum()
            good &= check("rearhand is not asked about what it buried", asked == 0)
        print(f"         {100.0 * ramsch.mean():.1f}% of records are a Ramsch")

    print()
    print("the split")
    train, val = corpus.split_by_board(board, fraction=0.1, seed=1)
    shared = set(board[train].tolist()) & set(board[val].tolist())
    good &= check("no board is in both halves", not shared, f"{len(shared)} shared")
    good &= check("every record lands somewhere", len(train) + len(val) == len(x))

    print()
    print("forgetting")
    forgetting = Forgetting(corpus, seed=2)
    perfect, _ = Forgetting(corpus, levels=(1.0,), seed=2).apply(x)
    good &= check("perfect memory changes nothing", np.array_equal(perfect, x))

    poor, _ = Forgetting(corpus, levels=(0.3,), seed=2).apply(x)
    played = ("played_by_me", "played_by_left", "played_by_right")
    before = sum(corpus.slice(x, b).sum() for b in played)
    after = sum(corpus.slice(poor, b).sum() for b in played)
    good &= check("a poor memory drops most of the history",
                  0.2 < after / max(before, 1) < 0.45,
                  f"kept {after / max(before, 1):.0%}")

    # A cleared block must be *complete*: the presence bit down and every value
    # in it zero. The reverse implication does not hold and must not be checked
    # -- trumps_out is a number, and a genuine zero ("no trumps are out") looks
    # exactly like a cleared one. That asymmetry is the whole reason the presence
    # bit exists.
    for block, presence, extra in (("bids_by_seat", "bidding_present", ()),
                                   ("trumps_out", "counts_present",
                                    ("trumps_mine", "jacks_out"))):
        pres, _ = corpus.field(presence)
        cleared = poor[:, pres] < 0.5
        leftover = np.zeros(len(poor), dtype=bool)
        for name in (block,) + extra:
            offset, width = corpus.field(name)
            leftover |= poor[:, offset:offset + width].any(axis=1)
        good &= check(f"a cleared {presence} leaves nothing behind",
                      not (cleared & leftover).any())

    for presence, level in (("bidding_present", 0.3), ("counts_present", 0.3)):
        pres, _ = corpus.field(presence)
        weak, _ = Forgetting(corpus, levels=(level,), seed=3).apply(x)
        strong, _ = Forgetting(corpus, levels=(1.0,), seed=3).apply(x)
        good &= check(f"{presence} survives less often at memory {level}",
                      (weak[:, pres] > 0.5).mean() < (strong[:, pres] > 0.5).mean(),
                      f"{(weak[:, pres] > 0.5).mean():.0%} vs "
                      f"{(strong[:, pres] > 0.5).mean():.0%}")

    _, memory = forgetting.apply(x[:2000])
    good &= check("memory is drawn per example, across the ladder",
                  len(set(np.round(memory, 2).tolist())) == len(Forgetting.LADDER))

    print()
    print("PASS" if good else "FAILED")
    return 0 if good else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "../../belief-data"))
