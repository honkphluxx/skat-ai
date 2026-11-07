"""
What the declarer's choice after the auction gives up, by the true-deal labels (after route 1).

    python arena/python/declarer_choice.py arena-logs/declarer-choice/s9101-b2000.jsonl --boards=2000
    python arena/python/declarer_choice.py --self-test

Reads DeclarerChoiceMain's lines: per declared board, every option (a contract
covering the bid times the ten discards from its five least-wanted cards)
played out k times on the true deal by the student. U per play-out is route
1's: the game value from the declarer's side plus the win (1) and a hundredth
of the card points. The references per board:

  shipped   what --shipped (belief-64-shipped) buries and announces in that seat
  fixed     the auction's contract with the heuristic's discard for it (route 1's games)
  searched  an honest re-chooser after the skat: every covering contract's discard
            searched over worlds from the unseen cards, the contract worth most kept
            (older files have no searched reference)

The honest comparisons come first: one reference minus another by the labels
on all k play-outs -- both choices made without seeing the deal, so nothing
selects on the labels' noise or hindsight.

For each reference, what choosing otherwise is worth, three ways:

  contract and discard   any option
  discard only           the options in the reference's own contract
  contract only          each contract with the heuristic's discard for it

Each is "cross-fitted": the option is chosen on half the play-outs and scored
on the other half (and the other way round, averaged), so play-out noise does
not make the gain look larger -- it is what choosing by k/2 play-outs on the
true deal is worth, an upper bound on what an honest declarer could learn,
since the labels see the deal. "in-sample" is the naive best minus the
reference on all k, inflated by noise, for scale. Intervals are 95%, boards
independent. With --boards (the boards the run asked for, passed in or not),
every gain also per game, comparable with the arena's game points a game.
"""

from __future__ import annotations

import argparse
import json
import sys

import numpy as np

CONTRACTS = ("DIAMONDS", "HEARTS", "SPADES", "CLUBS", "GRAND")
WAYS = ("contract and discard", "discard only", "contract only")
REFERENCES = ("shipped", "fixed", "searched")
HONEST = (("shipped", "fixed"), ("searched", "shipped"), ("searched", "fixed"))


def utility(option):
    v = np.asarray(option["value"], dtype=np.float64)
    w = np.asarray(option["wins"], dtype=np.float64)
    p = np.asarray(option["points"], dtype=np.float64)
    return v + w + 0.01 * p


def find(options, contract, discard=None, heuristic=None):
    for i, o in enumerate(options):
        if o["contract"] != contract:
            continue
        if discard is not None and sorted(o["discard"]) != sorted(discard):
            continue
        if heuristic is not None and o["heuristic"] != heuristic:
            continue
        return i
    return -1


def board_numbers(line):
    """Per board: the references and, per way, (cross-fitted gain, in-sample gain, contract changed)."""
    options = line["options"]
    u = np.stack([utility(o) for o in options])                    # (options, k)
    k = u.shape[1]
    if k < 2 or k % 2:
        raise ValueError(f"board {line['board']}: k = {k}, not even")
    half_a, half_b = u[:, : k // 2].mean(axis=1), u[:, k // 2:].mean(axis=1)
    mean = u.mean(axis=1)
    contracts = [o["contract"] for o in options]
    refs = {
        "shipped": find(options, line["shipped"]["contract"], line["shipped"]["discard"]),
        "fixed": find(options, line["auction_contract"], heuristic=True),
    }
    if "searched" in line:
        refs["searched"] = find(options, line["searched"]["contract"], line["searched"]["discard"])
    out = {"refs": refs, "mean": mean, "contracts": contracts}
    for name, ref in refs.items():
        if ref < 0:
            raise ValueError(f"board {line['board']}: the {name} reference is not among the options")
        ways = {
            "contract and discard": np.ones(len(options), dtype=bool),
            "discard only": np.array([c == contracts[ref] for c in contracts]),
            "contract only": np.array([o["heuristic"] for o in options]) | (np.arange(len(options)) == ref),
        }
        for way, allowed in ways.items():
            pick_a = np.flatnonzero(allowed)[np.argmax(half_a[allowed])]
            pick_b = np.flatnonzero(allowed)[np.argmax(half_b[allowed])]
            cross = 0.5 * ((half_b[pick_a] - half_b[ref]) + (half_a[pick_b] - half_a[ref]))
            best = np.flatnonzero(allowed)[np.argmax(mean[allowed])]
            out[(name, way)] = (cross, mean[best] - mean[ref], contracts[best] != contracts[ref])
    out["shipped_minus_fixed"] = mean[refs["shipped"]] - mean[refs["fixed"]]
    for a, b in HONEST:
        if a in refs and b in refs:
            out[(a, "minus", b)] = mean[refs[a]] - mean[refs[b]]
    return out


def interval(values):
    values = np.asarray(values, dtype=np.float64)
    n = len(values)
    if n < 2:
        return float(values.mean()) if n else float("nan"), float("nan")
    return float(values.mean()), float(1.96 * values.std(ddof=1) / np.sqrt(n))


def report(lines, boards=None):
    numbers = [board_numbers(line) for line in lines]
    n = len(numbers)
    k = len(lines[0]["options"][0]["value"]) if n else 0
    per_game = (n / boards) if boards else None
    print(f"{n:,} declared boards" + (f" of {boards:,} ({n / boards:.1%})" if boards else "")
          + f", k {k}, {np.mean([len(l['options']) for l in lines]):.1f} options a board")
    differ = np.mean([l["shipped"]["contract"] != l["auction_contract"] for l in lines])
    print(f"  the shipped player announces another contract than the auction's on {differ:.1%} of them")
    print("\n  honest: one reference minus another by the labels (all k), U a declared game")
    for a, b in HONEST:
        rows = [x[(a, "minus", b)] for x in numbers if (a, "minus", b) in x]
        if not rows:
            continue
        m, h = interval(rows)
        game = f"   per game {m * per_game:+.2f} [{(m - h) * per_game:+.2f}, {(m + h) * per_game:+.2f}]" if per_game else ""
        changed = np.mean([l[a]["contract"] != (l["auction_contract"] if b == "fixed" else l[b]["contract"])
                           for l in lines if a in l and (b == "fixed" or b in l)])
        print(f"    {a} minus {b}: {m:+.2f} [{m - h:+.2f}, {m + h:+.2f}]{game}   another contract on {changed:.1%}")
    print("\n  what choosing otherwise is worth, U a declared game (cross-fitted on halves; in-sample for scale)")
    print(f"  {'reference':9s} {'way':22s} {'cross-fitted [95%]':>24s}  {'per game':>8s}  {'in-sample':>9s}  "
          f"{'other contract':>14s}")
    for name in REFERENCES:
        if (name, WAYS[0]) not in numbers[0]:
            continue
        for way in WAYS:
            cross = [x[(name, way)][0] for x in numbers]
            naive = [x[(name, way)][1] for x in numbers]
            moved = [x[(name, way)][2] for x in numbers]
            m, h = interval(cross)
            game = f"{m * per_game:+8.2f}" if per_game else f"{'':8s}"
            print(f"  {name:9s} {way:22s} {m:+8.2f} [{m - h:+.2f}, {m + h:+.2f}]  {game}  {np.mean(naive):9.2f}  "
                  f"{np.mean(moved):14.1%}")
    print("\n  by the shipped contract: boards, cross-fitted gain of any option, how often the labels' best is another contract")
    for c in CONTRACTS:
        rows = [x for x, l in zip(numbers, lines) if l["shipped"]["contract"] == c]
        if not rows:
            continue
        m, h = interval([x[("shipped", "contract and discard")][0] for x in rows])
        moved = np.mean([x[("shipped", "contract and discard")][2] for x in rows])
        print(f"    {c:9s} {len(rows):6,d}   {m:+7.2f} [{m - h:+.2f}, {m + h:+.2f}]   {moved:6.1%}")
    return numbers


# ---------------------------------------------------------------- self-test


def fake_line(board, rng, k=8, noise=0.0, better=None, gain=0.0, contracts=("CLUBS", "GRAND"),
              shipped=("CLUBS", [0, 1]), auction="CLUBS", searched=None):
    """Ten pairs per contract, the fourth the heuristic's; option ``better`` (contract, discard) gains ``gain``."""
    options = []
    for c in contracts:
        for j in range(10):
            discard = [j, j + 1]
            base = np.full(k, 20.0)
            if better is not None and c == better[0] and discard == better[1]:
                base = base + gain
            v = base + noise * rng.standard_normal(k)
            options.append({"contract": c, "discard": discard, "heuristic": j == 3,
                            "value": [float(x) for x in v], "wins": [0] * k, "points": [0] * k})
    line = {"board": board, "declarer": "HUMAN", "bid": 18, "auction_contract": auction,
            "shipped": {"contract": shipped[0], "discard": shipped[1]}, "options": options}
    if searched is not None:
        line["searched"] = {"contract": searched[0], "discard": searched[1], "chance": 0.5}
    return line


def self_test():
    ok = True

    def check(name, passed):
        nonlocal ok
        ok &= bool(passed)
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")

    rng = np.random.default_rng(1)
    flat = board_numbers(fake_line(0, rng))
    check("all options equal: no gain any way", all(abs(flat[(r, w)][0]) < 1e-12 and abs(flat[(r, w)][1]) < 1e-12
                                                      for r in ("shipped", "fixed") for w in WAYS))

    other = board_numbers(fake_line(1, rng, better=("GRAND", [5, 6]), gain=10.0))
    check("a better option in another contract: the gain any way, and the contract moved",
          abs(other[("shipped", "contract and discard")][0] - 10) < 1e-9 and other[("shipped", "contract and discard")][2])
    check("... and nothing for the discard alone", abs(other[("shipped", "discard only")][0]) < 1e-12)
    check("... nor for the contract with the heuristic's discard (the better pair is not the heuristic's)",
          abs(other[("shipped", "contract only")][0]) < 1e-12)

    heur = board_numbers(fake_line(2, rng, better=("GRAND", [3, 4]), gain=6.0))
    check("the other contract's heuristic pair better: 'contract only' sees it",
          abs(heur[("shipped", "contract only")][0] - 6) < 1e-9)

    own = board_numbers(fake_line(3, rng, better=("CLUBS", [5, 6]), gain=4.0, shipped=("CLUBS", [2, 3])))
    check("a better discard in the shipped contract: 'discard only' sees it, the contract does not move",
          abs(own[("shipped", "discard only")][0] - 4) < 1e-9 and not own[("shipped", "discard only")][2])
    check("the references found: shipped by its pair, fixed by the auction's heuristic pair",
          own["refs"]["shipped"] == 2 and own["refs"]["fixed"] == 3)
    check("U is the game value, plus the win and a hundredth of the card points",
          float(utility({"value": [2], "wins": [1], "points": [50]})[0]) == 3.5)
    shipped_better = board_numbers(fake_line(4, rng, better=("CLUBS", [2, 3]), gain=5.0, shipped=("CLUBS", [2, 3])))
    check("shipped minus fixed by the labels", abs(shipped_better["shipped_minus_fixed"] - 5) < 1e-9)

    honest = board_numbers(fake_line(6, rng, better=("GRAND", [5, 6]), gain=7.0, searched=("GRAND", [5, 6])))
    check("searched minus shipped and minus fixed, by the labels",
          abs(honest[("searched", "minus", "shipped")] - 7) < 1e-9 and abs(honest[("searched", "minus", "fixed")] - 7) < 1e-9
          and abs(honest[("shipped", "minus", "fixed")]) < 1e-9)
    check("the searched reference found by its pair", honest["refs"]["searched"] == 15)
    check("an older file without it: no searched rows", ("searched", "minus", "shipped") not in other
          and ("searched", WAYS[0]) not in other)

    noisy = [board_numbers(fake_line(b, rng, noise=10.0)) for b in range(3000)]
    cross, half = interval([x[("shipped", "contract and discard")][0] for x in noisy])
    naive = np.mean([x[("shipped", "contract and discard")][1] for x in noisy])
    check(f"pure noise: cross-fitted within its interval of zero ({cross:+.3f} +- {half:.3f}), "
          f"in-sample inflated ({naive:+.2f})", abs(cross) < 1.5 * half and naive > 20 * half)

    try:
        board_numbers(fake_line(5, rng, shipped=("SPADES", [0, 1])))
        check("a reference missing from the options is refused", False)
    except ValueError:
        check("a reference missing from the options is refused", True)
    print("self-test " + ("passed" if ok else "FAILED"))
    return ok


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lines", nargs="?")
    parser.add_argument("--boards", type=int, default=0, help="boards the run asked for, for the per-game column")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return 0 if self_test() else 1
    if not args.lines:
        parser.error("a .jsonl file, or --self-test")
    with open(args.lines) as f:
        lines = [json.loads(row) for row in f if row.strip()]
    if not lines:
        print("no declared boards")
        return 1
    report(lines, args.boards or None)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
