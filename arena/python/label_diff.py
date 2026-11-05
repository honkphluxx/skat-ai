"""
Two label sets on the same decisions: what does finishing the play-outs another way change? (route 1)

    python arena/python/label_diff.py allcards-data/quick-r4-nosearch allcards-data/quick-r4
    python arena/python/label_diff.py allcards-data/quick-r4-nosearch allcards-data/quick-r4-control
    python arena/python/label_diff.py --self-test

The second line is the control: B made with --reseed-from instead of
--search-from, the student still finishing the play-outs on fresh luck from
that trick, so every column shows what play-out noise alone gives.

A and B are two all-cards exports of the same student, the same seed and the
same boards that differ only in how the play-outs are finished (A: the student
plays on; B: --search-from, a small search plays on). The main line draws from
its own random stream, so the two corpora hold the very same decisions, record
for record; this is checked (position, legal cards, the card played, trick,
board) and the run stops if it does not hold. Within a decision the play-outs
of A and B share their random streams until the search takes over, so for a
decision before that trick much of the play-out noise is common to both.

Printed by trick and by the decider's role, on the main line's undrawn cards
(the student's top card), all in game value a play-out from the decider's side:

  agree     how often A's best card is B's best or tied with it
  top A, B  how often the student's top card is the best by A, by B
  regret    the best card's mean minus the top card's, by A and by B
  B-A       regret by B minus regret by A, with a 95% interval clustered by board
  A misleads  what A's best card gives up by B's verdict: B's best minus A's best,
            both by B; what a student taught perfectly by A would still miss by B
  signal    how much of a decision's label shape changes: the variance over the
            legal cards of (B - A), centred within the decision, over that of A

The labels carry noise (k play-outs a card), so "agree" stays below 100% and
"A misleads" above 0 even if nothing changed; read them against each other
and across tricks, not against zero. Pure numpy.
"""

from __future__ import annotations

import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from allcards_data import CARDS, DECLARER, DRAWN, AllCardsCorpus, Records  # noqa: E402


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
    return float(mean), float(1.96 * np.sqrt((resid ** 2).sum()) / n)


def aligned(a, b):
    """Raise unless A and B hold the same decisions in the same order."""
    if len(a) != len(b):
        raise ValueError(f"A has {len(a):,} records, B {len(b):,}: not the same decisions")
    for name in ("features", "legal", "chosen", "drawn", "declarer", "trick", "board", "contract"):
        same = np.all(getattr(a, name) == getattr(b, name), axis=tuple(range(1, getattr(a, name).ndim)))
        if not same.all():
            first = int(np.argmin(same))
            raise ValueError(f"record {first:,}: {name} differs between A and B: not the same decisions")


def compare(a, b):
    """Per-record numbers of the comparison (see the module text), over the undrawn records."""
    aligned(a, b)
    keep = ~a.drawn
    rows = np.arange(len(a))[keep]
    top = a.chosen[keep]
    ua, ub = a.mean_value()[keep], b.mean_value()[keep]
    best_a, best_b = a.best()[keep], b.best()[keep]
    legal = a.legal[keep]
    count = legal.sum(axis=1)
    centre_a = np.where(legal, ua - (ua * legal).sum(axis=1, keepdims=True) / count[:, None], 0.0)
    centre_b = np.where(legal, ub - (ub * legal).sum(axis=1, keepdims=True) / count[:, None], 0.0)
    r = np.arange(len(rows))
    return {
        "trick": a.trick[keep], "declarer": a.declarer[keep], "board": a.board[keep],
        "agree": b.is_best(a.best())[keep],
        "top_a": a.is_best(a.chosen)[keep], "top_b": b.is_best(b.chosen)[keep],
        "regret_a": ua[r, best_a] - ua[r, top], "regret_b": ub[r, best_b] - ub[r, top],
        "misleads": ub[r, best_b] - ub[r, best_a],
        "var_a": (centre_a ** 2).sum(axis=1), "var_diff": ((centre_b - centre_a) ** 2).sum(axis=1),
    }


def table(c, title, select):
    print(f"\n  {title}")
    print("  trick  records   agree   top A   top B  regret A  regret B   B-A [95%]            A misleads [95%]       signal")
    for t in list(range(1, 10)) + [None]:
        s = select & ((c["trick"] == t) if t is not None else True)
        if not s.any():
            continue
        diff, half = clustered(c["regret_b"][s] - c["regret_a"][s], c["board"][s])
        mis, mhalf = clustered(c["misleads"][s], c["board"][s])
        signal = c["var_diff"][s].sum() / max(c["var_a"][s].sum(), 1e-12)
        label = f"{t:5d}" if t is not None else "  all"
        print(f"  {label}  {s.sum():7d}  {c['agree'][s].mean():6.1%}  {c['top_a'][s].mean():6.1%}  "
              f"{c['top_b'][s].mean():6.1%}  {c['regret_a'][s].mean():8.2f}  {c['regret_b'][s].mean():8.2f}  "
              f"{diff:+6.2f} [{diff - half:+.2f}, {diff + half:+.2f}]  {mis:6.2f} [{mis - mhalf:.2f}, {mis + mhalf:.2f}]  "
              f"{signal:6.2f}")


def report(c, names):
    print(f"A = {names[0]}\nB = {names[1]}")
    print(f"{len(c['trick']):,} undrawn decisions on {len(np.unique(c['board'])):,} boards")
    everyone = np.ones(len(c["trick"]), dtype=bool)
    table(c, "every decision", everyone)
    table(c, "the declarer deciding", c["declarer"])
    table(c, "a defender deciding", ~c["declarer"])


# ---------------------------------------------------------------- self-test


def fake(n, seed, legal_count=4, k=8):
    """Raw format-2 records: random positions, the first legal_count cards of a random set legal."""
    rng = np.random.default_rng(seed)
    raw = np.zeros((n, 400), dtype=np.uint8)
    raw[:, 0:306] = rng.integers(0, 256, (n, 306), dtype=np.uint8)
    masks = np.zeros(n, dtype=np.uint32)
    for i in range(n):
        for card in rng.choice(CARDS, legal_count, replace=False):
            masks[i] |= np.uint32(1) << np.uint32(card)
    raw[:, 306:310] = masks.view(np.uint8).reshape(n, 4)
    legal = ((masks[:, None] >> np.arange(CARDS, dtype=np.uint32)) & 1).astype(bool)
    raw[:, 310] = [rng.choice(np.flatnonzero(row)) for row in legal]
    raw[:, 311] = (rng.random(n) < 0.2) * DRAWN | (rng.random(n) < 0.33) * DECLARER
    raw[:, 312] = rng.integers(1, 10, n)
    raw[:, 314] = k
    raw[:, 388:392] = (np.arange(n, dtype=np.uint32) // 5).view(np.uint8).reshape(n, 4)
    values = np.zeros((n, 10), dtype=np.int32)
    values[:, :legal_count] = rng.integers(-60, 120, (n, legal_count)) * k
    raw[:, 348:388] = values.view(np.uint8).reshape(n, 40)
    return raw


def set_values(raw, values):
    out = raw.copy()
    out[:, 348:388] = np.ascontiguousarray(values.astype(np.int32)).view(np.uint8).reshape(len(raw), 40)
    return out


def self_test():
    ok = True

    def check(name, passed):
        nonlocal ok
        ok &= bool(passed)
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")

    raw = fake(400, 1)
    a = Records(raw)
    c = compare(a, Records(raw.copy()))
    check("the same labels agree everywhere", c["agree"].all() and (c["misleads"] == 0).all()
          and (c["regret_a"] == c["regret_b"]).all() and (c["var_diff"] == 0).all())
    check("only undrawn records", len(c["trick"]) == (~a.drawn).sum() and 0 < len(c["trick"]) < len(a))
    check("a regret is never negative", (c["regret_a"] >= 0).all())

    # B: on the first 100 records, A's best card loses 40 a play-out and the
    # first other legal card gains 10 above A's best.
    k = 8
    values = np.ascontiguousarray(raw[:, 348:388]).view("<i4").astype(np.int64).copy()
    rank = np.cumsum(a.legal, axis=1) - 1
    best = a.best()
    slot_best = rank[np.arange(len(a)), best]
    changed = np.zeros(len(a), dtype=bool)
    changed[:100] = True
    expect = np.zeros(len(a))
    for i in np.flatnonzero(changed):
        other = 0 if slot_best[i] != 0 else 1
        top = values[i, slot_best[i]] // k
        values[i, other] = (top + 10) * k
        values[i, slot_best[i]] = (top - 40) * k
        expect[i] = 50
    b = Records(set_values(raw, values))
    c = compare(a, b)
    keep = ~a.drawn
    check("A misleads by what B says A's best gives up", np.allclose(c["misleads"], expect[keep]))
    check("agree fails exactly where B moved the best", (c["agree"] == ~changed[keep]).all())
    check("the signal is zero where nothing moved", (c["var_diff"][~changed[keep]] == 0).all()
          and (c["var_diff"][changed[keep]] > 0).all())

    # A uniform shift of every card is no change of shape.
    original = np.ascontiguousarray(raw[:, 348:388]).view("<i4").astype(np.int64)
    shifted = Records(set_values(raw, np.where(np.arange(10) < 4, original + 7 * k, 0)))
    c = compare(a, shifted)
    check("a shift of every card changes no shape", np.allclose(c["var_diff"], 0) and c["agree"].all()
          and np.allclose(c["misleads"], 0))

    # A ties two cards at the top (its best is the earlier); B lifts the later
    # one: A's best is no longer B's best, though B's best is tied best by A.
    tied = original.copy()
    tied[0, 0], tied[0, 1], tied[0, 2], tied[0, 3] = 50 * k, 50 * k, 0, 0
    lifted = tied.copy()
    lifted[0, 1] = 51 * k
    raw_tied = set_values(raw, tied)
    raw_tied[0, 311] &= ~DRAWN & 0xFF
    c = compare(Records(raw_tied), Records(set_values(raw_tied, lifted)))
    check("agree asks whether A's best is B's best", not c["agree"][0] and np.isclose(c["misleads"][0], 1.0))

    for name, mutate in (("chosen", lambda r: r.__setitem__((5, 310), (r[5, 310] + 1) % 32)),
                         ("features", lambda r: r.__setitem__((7, 100), r[7, 100] ^ 1)),
                         ("records", None)):
        other = raw.copy()
        if mutate is None:
            other = other[:-1]
        else:
            mutate(other)
        try:
            compare(a, Records(other))
            check(f"refuses different decisions ({name})", False)
        except ValueError:
            check(f"refuses different decisions ({name})", True)

    clustered_mean, half = clustered(np.array([1.0, 1.0, 3.0, 3.0]), np.array([0, 0, 1, 1]))
    check("the clustered interval treats a board as one", clustered_mean == 2.0 and abs(half - 1.96 * np.sqrt(8) / 4) < 1e-12)
    print("self-test " + ("passed" if ok else "FAILED"))
    return ok


def main(argv):
    if argv == ["--self-test"]:
        return 0 if self_test() else 1
    if len(argv) != 2 or argv[0].startswith("-"):
        print(__doc__)
        return 2
    corpora = [AllCardsCorpus(d) for d in argv]
    if corpora[0].seed != corpora[1].seed or corpora[0].k != corpora[1].k:
        raise ValueError("A and B must share the seed and k")
    report(compare(corpora[0].load(), corpora[1].load()), argv)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
