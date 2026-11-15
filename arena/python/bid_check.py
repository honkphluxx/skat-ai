"""
The bid labels checked at the table: how far the student's play-outs are from the table's, on the seats where the
learned bidder bids and the shipped one does not (tools/bid-check.sh runs it).

    python arena/python/bid_check.py select --labels <bid-labels .jsonl> --model bidder-net-v1 --out tasks.txt
    (./gradlew :arena:bidCheck --args="--tasks=tasks.txt --out=checked.txt --seed=9201")
    python arena/python/bid_check.py report checked.txt
    python arena/python/bid_check.py --self-test

select: every seat whose learned ceiling (the ensemble's learned-first at 0, as the table's player bids) is above
the shipped ceiling -- the seats where the bidder at the table declares a game the shipped bidder would not -- at
the first bid where the two part: the rung above the shipped ceiling, 18 when it has none. The task is the game and
discard the learned declarer chose there (the labels' pick), with the student's play-outs of it. Then a control:
--control seats (default 300, drawn with --sample-seed) where the shipped bidder holds 18, at 18 -- games both
bidders declare.

report: per task the table's mean (k play-outs, the table player in all three seats) against the student's (the
labels' 8), paired seat by seat; the disputed seats by the predicted value at the bid; and what a higher threshold
would drop -- the mean table value of the disputed seats predicted at or below t (negative: dropping them pays).
The declarer differs too (the search player at the table, the student in the labels): this is the labels' whole
bias, defence and declarer play together.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bid_report as br  # noqa: E402

BINS = [(-1e9, 10), (10, 25), (25, 50), (50, 1e9)]
THRESHOLDS = (0, 5, 10, 15, 20, 30)


def next_rung(ceiling):
    return br.LADDER[0] if ceiling == 0 else br.LADDER[br.LADDER.index(ceiling) + 1]


def select(lines, predict, control=300, sample_seed=1):
    """predict: a list of Seats -> predicted values (seats x rungs). Returns (task lines, counts)."""
    from train_bidder import ceiling_of
    raw_seats, seats, boards = [], [], []
    for line in lines:
        by_place = {s["place"]: s for s in line["seats"]}
        for place in ("forehand", "middlehand", "rearhand"):
            raw_seats.append(by_place[place])
            seats.append(br.Seat(by_place[place]))
            boards.append(line)
    pred = predict(seats)
    tasks, counts = [], {"seats": len(seats), "disputed": 0, "uncovered": 0, "control": 0}
    controls = []
    for raw, seat, line, p in zip(raw_seats, seats, boards, pred):
        learned = ceiling_of(p, 0, True)
        levels = {b: i for b, i in raw["levels"]}
        if learned > seat.ceiling:
            b = next_rung(seat.ceiling)
            if b not in levels:
                counts["uncovered"] += 1
                continue
            counts["disputed"] += 1
            tasks.append(task_line("disputed", line, raw, seat, b, levels, p))
        elif seat.ceiling >= 18 and 18 in levels:
            controls.append((line, raw, seat, p))
    rng = np.random.default_rng(sample_seed)
    for i in sorted(rng.choice(len(controls), size=min(control, len(controls)), replace=False)):
        line, raw, seat, p = controls[i]
        levels = {b: j for b, j in raw["levels"]}
        tasks.append(task_line("control", line, raw, seat, 18, levels, p))
        counts["control"] += 1
    return tasks, counts


def task_line(kind, line, raw, seat, b, levels, p):
    pick = raw["picks"][levels[b]]
    place = {"forehand": 0, "middlehand": 1, "rearhand": 2}[seat.place]
    predicted = float(p[br.LADDER.index(b)])
    join = lambda xs: ",".join(str(int(x)) for x in xs)  # noqa: E731
    return " ".join([kind, str(line["board"]), str(place), join(seat.ten), join(line["skat"]), str(b),
                     pick["contract"], join(pick["discard"]), f"{predicted:.3f}", join(pick["value"]),
                     join(pick["wins"])])


def parse(row):
    task, result = row.rstrip("\n").split("\t")
    f = task.split(" ")
    values, wins, points, tp = ([int(x) for x in part.split(",")] for part in result.split(" "))
    return {"kind": f[0], "board": int(f[1]), "place": int(f[2]), "bid": int(f[5]), "contract": f[6],
            "predicted": float(f[8]), "student": [int(x) for x in f[9].split(",")],
            "student_wins": [int(x) for x in f[10].split(",")],
            "table": values, "wins": wins, "points": points, "tp": tp}


def summary(rows):
    s = np.array([np.mean(r["student"]) for r in rows])
    t = np.array([np.mean(r["table"]) for r in rows])
    m, h = br.interval(t - s)
    return {"n": len(rows), "student": float(s.mean()), "table": float(t.mean()), "diff": m, "half": h,
            "student_wins": float(np.mean([np.mean(r["student_wins"]) for r in rows])),
            "table_wins": float(np.mean([np.mean(r["wins"]) for r in rows])),
            "tp": float(np.mean([np.mean(r["tp"]) for r in rows]))}


def report(rows, out=print):
    out(f"  {len(rows):,} tasks, {sum(len(r['table']) for r in rows):,} play-outs at the table")
    for kind, title in (("disputed", "the learned bidder's extra games (its ceiling above the shipped one)"),
                        ("control", "control: games both bidders declare, at 18")):
        rows_k = [r for r in rows if r["kind"] == kind]
        if not rows_k:
            continue
        s = summary(rows_k)
        out(f"\n  {title}: {s['n']:,} seats")
        out(f"    game points a game: student {s['student']:+.1f}, table {s['table']:+.1f}; table - student "
            f"{s['diff']:+.2f} [{s['diff'] - s['half']:+.2f}, {s['diff'] + s['half']:+.2f}]")
        out(f"    won: student {s['student_wins']:.1%}, table {s['table_wins']:.1%}; the declarer's tournament "
            f"points at the table {s['tp']:+.1f}")
        if kind != "disputed":
            continue
        out("    by the predicted value at the bid:")
        for lo, hi in BINS:
            b = [r for r in rows_k if lo <= r["predicted"] < hi]
            if not b:
                continue
            bs = summary(b)
            label = f"below {hi:g}" if lo < -1e8 else (f"{lo:g} and over" if hi > 1e8 else f"{lo:g} to {hi:g}")
            out(f"      {label:12s} {bs['n']:5,} seats  predicted {np.mean([r['predicted'] for r in b]):+6.1f}  "
                f"student {bs['student']:+6.1f}  table {bs['table']:+6.1f}  won {bs['table_wins']:.0%}")
        out("    what a higher threshold drops (the seats predicted at or below t; a negative table mean: "
            "dropping them pays):")
        for t in THRESHOLDS:
            dropped = [r for r in rows_k if r["predicted"] <= t]
            if not dropped:
                out(f"      t={t:<3} none")
                continue
            v = np.array([np.mean(r["table"]) for r in dropped])
            m, h = br.interval(v) if len(v) > 1 else (float(v.mean()), float("nan"))
            out(f"      t={t:<3} {len(dropped):5,} seats ({len(dropped) / len(rows_k):.0%}), table mean {m:+.1f} "
                f"[{m - h:+.1f}, {m + h:+.1f}]")


def key(r):
    return (r["kind"], r["board"], r["place"], r["bid"])


def compare(named, out=print):
    """named: [(name, rows)], the first the reference. Each run on the tasks all of them played."""
    common = set.intersection(*({key(r) for r in rows} for _, rows in named))
    if not common:
        raise SystemExit("the runs share no task")
    by = [(name, {key(r): r for r in rows if key(r) in common}) for name, rows in named]
    ref_name, ref = by[0]
    keys = sorted(common)
    out(f"  {len(keys):,} tasks every run played ({sum(k[0] == 'disputed' for k in keys):,} disputed, "
        f"{sum(k[0] == 'control' for k in keys):,} control)")
    for kind in ("disputed", "control", None):
        ks = [k for k in keys if kind is None or k[0] == kind]
        if not ks:
            continue
        out(f"\n  {kind or 'all'}: {len(ks):,} tasks; labels (the student, T=1) "
            f"{np.mean([np.mean(ref[k]['student']) for k in ks]):+.1f}")
        for name, rows in by:
            t = np.array([np.mean(rows[k]["table"]) for k in ks])
            st = np.array([np.mean(rows[k]["student"]) for k in ks])
            m, h = br.interval(t - st)
            line = (f"    {name:44s} {t.mean():+6.1f}  won {np.mean([np.mean(rows[k]['wins']) for k in ks]):.0%}  "
                    f"minus labels {m:+6.2f} [{m - h:+.2f}, {m + h:+.2f}]")
            if name != ref_name:
                r = np.array([np.mean(ref[k]["table"]) for k in ks])
                m2, h2 = br.interval(t - r)
                line += f"  minus {ref_name} {m2:+6.2f} [{m2 - h2:+.2f}, {m2 + h2:+.2f}]"
            out(line)


def ensemble_predictor(model_dir):
    import torch
    from train_bidder import features, make_net
    with open(os.path.join(model_dir, "bidder.json")) as f:
        meta = json.load(f)
    nets = []
    for state in torch.load(os.path.join(model_dir, "bidder.pt")):
        net = make_net(torch, meta["hidden"])
        net.load_state_dict(state)
        net.eval()
        nets.append(net)

    def predict(seats):
        x = torch.from_numpy(np.stack([features(s) for s in seats]))
        with torch.no_grad():
            return np.mean([n(x).numpy() for n in nets], axis=0) * meta["scale"]
    return predict


# ---------------------------------------------------------------- self-test


def self_test():
    ok = True

    def check(name, passed):
        nonlocal ok
        ok &= bool(passed)
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")

    check("the rung above the shipped ceiling", next_rung(0) == 18 and next_rung(18) == 20 and next_rung(23) == 24)

    def seat(place, ten, ceiling, levels, value):
        return {"place": place, "ten": ten, "shipped": {"ceiling": ceiling, "open18": False},
                "levels": [[b, 0] for b in levels],
                "picks": [{"contract": "CLUBS", "discard": ten[:2], "value": [value, value], "wins": [1, 1]}]}

    lines = [{"board": 7, "skat": [30, 31], "seats": [
        seat("forehand", list(range(0, 10)), 0, [18, 20, 22], 10),        # learned 20 > 0: disputed at 18
        seat("middlehand", list(range(10, 20)), 18, [18, 20], 30),       # learned 20 > 18: disputed at 20
        seat("rearhand", list(range(20, 30)), 20, [18, 20], 5)]}]        # learned 20 = 20: control
    p = np.full((3, len(br.LADDER)), -1.0)
    p[:, :2] = 7.0                                                      # learned-first ceiling 20 everywhere
    tasks, counts = select(lines, lambda seats: p, control=5)
    check("two disputed, one control", counts["disputed"] == 2 and counts["control"] == 1)
    check("forehand at 18, middlehand at 20", tasks[0].split(" ")[5] == "18" and tasks[1].split(" ")[5] == "20")
    check("the task carries ten, skat, game, discard, prediction, the student's",
          tasks[0].split(" ")[3] == "0,1,2,3,4,5,6,7,8,9" and tasks[0].split(" ")[4] == "30,31"
          and tasks[0].split(" ")[6] == "CLUBS" and tasks[0].split(" ")[7] == "0,1"
          and tasks[0].split(" ")[8] == "7.000" and tasks[0].split(" ")[9] == "10,10")
    lines[0]["seats"][0]["levels"] = [[20, 0]]
    _, counts = select(lines, lambda seats: p, control=0)
    check("a bid no game covers is counted, not checked", counts["uncovered"] == 1 and counts["disputed"] == 1)

    rows = [parse(t + "\t" + "-20,-40 0,0 50,40 -70,-90") for t in tasks[:2]]
    rows.append(parse(tasks[2] + "\t" + "20,20 1,1 70,80 70,70"))
    check("parsed back", rows[0]["table"] == [-20, -40] and rows[0]["student"] == [10, 10] and rows[0]["bid"] == 18
          and rows[2]["kind"] == "control" and rows[2]["tp"] == [70, 70])
    s = summary(rows[:2])
    check("table - student, seat by seat", abs(s["diff"] - (((-30 - 10) + (-30 - 30)) / 2)) < 1e-9
          and s["table_wins"] == 0.0 and s["student_wins"] == 1.0)
    lines_out = []
    report(rows, out=lines_out.append)
    text = "\n".join(lines_out)
    check("the report names both groups and the thresholds",
          "2 seats" in text and "control" in text and "t=5 " in text and "t=0 " in text)
    other = [dict(r, table=[v + 10 for v in r["table"]]) for r in rows]
    other.append(dict(rows[0], board=99))
    cmp_lines = []
    compare([("ref", rows), ("plus10", other)], out=cmp_lines.append)
    text = "\n".join(cmp_lines)
    check("compare: the shared tasks only, and the paired difference", "3 tasks every run played" in text
          and "minus ref +10.00" in text)
    print("self-test " + ("passed" if ok else "FAILED"))
    return ok


def main(argv):
    if argv[:1] == ["--self-test"]:
        return 0 if self_test() else 1
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sel = sub.add_parser("select")
    sel.add_argument("--labels", required=True)
    sel.add_argument("--model", default="bidder-net-v1")
    sel.add_argument("--out", required=True)
    sel.add_argument("--boards", type=int, default=0, help="the first N boards only (0: all)")
    sel.add_argument("--control", type=int, default=300)
    sel.add_argument("--sample-seed", type=int, default=1)
    rep = sub.add_parser("report")
    rep.add_argument("checked")
    cmp_ = sub.add_parser("compare", help="name=checked-file ..., the first the reference")
    cmp_.add_argument("runs", nargs="+")
    args = parser.parse_args(argv)
    if args.command == "select":
        with open(args.labels) as f:
            lines = [json.loads(row) for row in f if row.strip()]
        if args.boards:
            lines = lines[:args.boards]
        tasks, counts = select(lines, ensemble_predictor(args.model), args.control, args.sample_seed)
        with open(args.out, "w") as f:
            f.write("\n".join(tasks) + "\n")
        print(f"  {counts['seats']:,} seats: {counts['disputed']:,} disputed ({counts['disputed'] / counts['seats']:.1%}), "
              f"{counts['uncovered']:,} at a bid no game covers, {counts['control']:,} control -> {args.out}")
        return 0
    if args.command == "compare":
        named = []
        for run in args.runs:
            name, _, path = run.partition("=")
            with open(path) as f:
                named.append((name, [parse(row) for row in f if row.strip()]))
        compare(named)
        return 0
    with open(args.checked) as f:
        rows = [parse(row) for row in f if row.strip()]
    report(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
