"""
A learned bidder from the bid labels, tested on another seed (the bidding, after bid_report.py).

    python arena/python/train_bidder.py --train arena-logs/bid-labels/<s9202 run>.jsonl \
        --test arena-logs/bid-labels/<s9201 run>.jsonl --out bidder-net-v1
    python arena/python/train_bidder.py --self-test

What a seat can know when it bids: its ten, its place, and what the shipped
bidder says about that ten (its ceiling once something is bid, whether it
says 18 into an empty auction) -- the shipped player prices the ten anyway, so
its verdict is an input the table has. Not the skat, not the other hands.
The net (MLP 37 -> 256 -> 256 -> 63) predicts the value of declaring at every
rung of the ladder (BidLabelMain's labels: the learned declarer after the skat,
played out by the student; above every game the twelve covers, an overbid at
-2b), trained by squared error in units of 100 game points.

The bid, from the prediction:
  learned        ceiling: the highest rung predicted above t; says 18 if 18 is
  learned-first  ceiling: the last rung of the first run above t -- it stops
                 at the first rung it expects to lose
  union          the higher of learned-first's and the shipped ceiling; says
                 18 if either does
with t = 0 (declare when the expected game points are positive: a defender
scores nothing) and t = 5 as a sensitivity row, never chosen on the test file.

Each row is paired against the shipped bidder on the test file as bid_report.py
pairs (every board from all three seats by both sides), once per init seed and
pooled: a student is a draw. Then the oracle's row for scale. The labels are
the student's play-outs, and so is the test: the table decides.

Writes <out>/bidder.pt (the seeds' state dicts) and <out>/bidder.json.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bid_report as br  # noqa: E402

PLACES = {"forehand": 0, "middlehand": 1, "rearhand": 2}
FEATURES = 32 + 3 + 2
RUNGS = len(br.LADDER)
SCALE = 100.0


def features(seat):
    x = np.zeros(FEATURES, np.float32)
    for c in seat.ten:
        x[c] = 1.0
    x[32 + PLACES[seat.place]] = 1.0
    x[35] = seat.ceiling / SCALE
    x[36] = 1.0 if seat.open18 else 0.0
    return x


def targets(seat):
    return np.array([seat.value(b) for b in br.LADDER], np.float32) / SCALE


def load(path):
    with open(path) as f:
        return [json.loads(row) for row in f if row.strip()]


def table(lines):
    seats = [s for line in lines for s in br.board_seats(line)]
    if any(len(s.ten) != 10 for s in seats):
        raise SystemExit("a seat without its ten: not a BidLabelMain file")
    return np.stack([features(s) for s in seats]), np.stack([targets(s) for s in seats])


def make_net(torch, hidden):
    nn = torch.nn
    return nn.Sequential(nn.Linear(FEATURES, hidden), nn.ReLU(), nn.Linear(hidden, hidden), nn.ReLU(),
                         nn.Linear(hidden, RUNGS))


def train(torch, x, y, seed, epochs, hidden, batch=256, lr=1e-3, decay=1e-3):
    torch.manual_seed(seed)
    net = make_net(torch, hidden)
    opt = torch.optim.AdamW(net.parameters(), lr=lr, weight_decay=decay)
    xt, yt = torch.tensor(x), torch.tensor(y)
    gen = torch.Generator().manual_seed(seed)
    for _ in range(epochs):
        perm = torch.randperm(len(xt), generator=gen)
        for k in range(0, len(xt), batch):
            idx = perm[k:k + batch]
            loss = ((net(xt[idx]) - yt[idx]) ** 2).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
    return net


def predict(torch, net, x):
    with torch.no_grad():
        return net(torch.tensor(x)).numpy() * SCALE


def ceiling_of(pred, t, first):
    c = 0
    for b, v in zip(br.LADDER, pred):
        if v > t:
            c = b
        elif first:
            break
    return c


def policies(lookup, t):
    """lookup: (ten, place) -> predicted values at every rung."""
    def learned(seat):
        p = lookup[(seat.ten, seat.place)]
        return ceiling_of(p, t, False), bool(p[0] > t)

    def learned_first(seat):
        p = lookup[(seat.ten, seat.place)]
        return ceiling_of(p, t, True), bool(p[0] > t)

    def union(seat):
        c, o = learned_first(seat)
        return max(c, seat.ceiling), o or seat.open18

    return [(f"learned t={t}", learned), (f"learned-first t={t}", learned_first), (f"union t={t}", union)]


def lookup_of(lines, pred):
    seats = [s for line in lines for s in br.board_seats(line)]
    return {(s.ten, s.place): p for s, p in zip(seats, pred)}


def evaluate(lines, preds, thresholds=(0, 5)):
    """preds: one prediction matrix per seed. Rows: per seed, and pooled (the seeds' per-board differences averaged)."""
    rows = []
    for t in thresholds:
        per_seed = [dict(policies(lookup_of(lines, p), t)) for p in preds]
        for name in per_seed[0]:
            diffs, rates = [], []
            for pol in per_seed:
                d, rate = br.paired(lines, pol[name], br.shipped)
                diffs.append(d)
                rates.append(rate)
            seeds = [float(np.mean(d)) for d in diffs]
            m, h = br.interval(np.mean(diffs, axis=0))
            rows.append((name, m, h, seeds, float(np.mean(rates))))
    return rows


def run(args):
    import torch
    torch.set_num_threads(args.threads)
    start = time.time()
    if os.path.abspath(args.train) == os.path.abspath(args.test):
        raise SystemExit("the test file is the training file")
    train_lines, test_lines = load(args.train), load(args.test)
    x, y = table(train_lines)
    xt, yt = table(test_lines)
    print(f"train {len(train_lines):,} boards ({len(x):,} seats), test {len(test_lines):,} boards ({len(xt):,} seats)")
    base = float(((y.mean(0) - yt) ** 2).mean())
    nets, preds = [], []
    for seed in args.seeds:
        net = train(torch, x, y, seed, args.epochs, args.hidden)
        p = predict(torch, net, xt)
        mse = float(((p / SCALE - yt) ** 2).mean())
        print(f"  seed {seed}: test error {mse:.4f} (the training mean's: {base:.4f})")
        nets.append(net)
        preds.append(p)
    print(f"  trained in {time.time() - start:.0f} s")
    print("\npolicy minus shipped on the test file, game points a seat-game, every board from all three seats by both sides:")
    for name, m, h, seeds, rate in evaluate(test_lines, preds):
        per = ", ".join(f"{s:+.2f}" for s in seeds)
        print(f"  {name:20s} {m:+.3f} [{m - h:+.3f}, {m + h:+.3f}]   seeds {per}   boards declared {rate:.1%}")
    d, rate = br.paired(test_lines, br.oracle, br.shipped)
    m, h = br.interval(d)
    print(f"  {'oracle (a bound)':20s} {m:+.3f} [{m - h:+.3f}, {m + h:+.3f}]   boards declared {rate:.1%}")
    if args.out:
        os.makedirs(args.out, exist_ok=True)
        torch.save([n.state_dict() for n in nets], os.path.join(args.out, "bidder.pt"))
        with open(os.path.join(args.out, "bidder.json"), "w") as f:
            json.dump({"features": FEATURES, "hidden": args.hidden, "rungs": RUNGS, "ladder": br.LADDER,
                       "scale": SCALE, "seeds": args.seeds, "epochs": args.epochs, "train": args.train,
                       "test": args.test}, f, indent=1)
        print(f"\nwrote {args.out}/bidder.pt and bidder.json")


# ---------------------------------------------------------------- self-test


def synthetic(n, seed):
    """Boards where a seat's value is +40 at every bid up to 30 when it holds the jack of clubs (card 4), else -30;
    the shipped bidder passes everything. A learned bidder must find the jack."""
    rng = np.random.default_rng(seed)
    out = []
    for board in range(n):
        deck = rng.permutation(32)
        seats = []
        for i, place in enumerate(("forehand", "middlehand", "rearhand")):
            ten = sorted(int(c) for c in deck[10 * i:10 * i + 10])
            v = 40 if 4 in ten else -30
            levels = [[b, 0] for b in br.LADDER if b <= 30]
            seats.append({"place": place, "ten": ten, "shipped": {"ceiling": 0, "open18": False},
                          "levels": levels, "picks": [{"value": [v, v]}]})
        out.append({"board": board, "skat": [int(deck[30]), int(deck[31])], "seats": seats})
    return out


def self_test():
    import torch
    ok = True

    def check(name, passed):
        nonlocal ok
        ok &= bool(passed)
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")

    seat = br.Seat(synthetic(1, 0)[0]["seats"][0])
    x = features(seat)
    check("features: ten cards, one place, the shipped verdict", x[:32].sum() == 10 and x[32:35].sum() == 1
          and x.shape == (FEATURES,))
    check("targets: an overbid above the covered games", targets(seat)[br.LADDER.index(33)] == -0.66)
    p = np.full(RUNGS, -1.0)
    p[:3] = 10.0
    p[5] = 10.0
    check("learned takes the highest rung above t", ceiling_of(p, 0, False) == br.LADDER[5])
    check("learned-first stops at the first rung below t", ceiling_of(p, 0, True) == br.LADDER[2])
    check("nothing above t is a pass", ceiling_of(np.full(RUNGS, -1.0), 0, True) == 0)

    shy = br.Seat({"place": "middlehand", "ten": seat.ten, "shipped": {"ceiling": 30, "open18": True},
                   "levels": [], "picks": []})
    pols = dict(policies({(shy.ten, shy.place): np.full(RUNGS, -1.0)}, 0))
    check("union keeps the shipped bid where the net would pass",
          pols["union t=0"](shy) == (30, True) and pols["learned-first t=0"](shy) == (0, False))

    train_lines, test_lines = synthetic(600, 1), synthetic(200, 2)
    xs, ys = table(train_lines)
    net = train(torch, xs, ys, 1, 150, 64)
    xt, _ = table(test_lines)
    pred = predict(torch, net, xt)
    seats = [s for line in test_lines for s in br.board_seats(line)]
    right = np.mean([(p[0] > 0) == (4 in s.ten) for s, p in zip(seats, pred)])
    check(f"it finds the jack of clubs ({right:.1%} of test seats right)", right > 0.95)
    rows = {name: m for name, m, *_ in evaluate(test_lines, [pred], thresholds=(0,))}
    check(f"and beats a bidder that passes everything ({rows['learned-first t=0']:+.2f})",
          rows["learned-first t=0"] > 5)
    print("self-test " + ("passed" if ok else "FAILED"))
    return ok


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--train")
    parser.add_argument("--test")
    parser.add_argument("--out")
    parser.add_argument("--seeds", type=int, nargs="+", default=[1, 2, 3])
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--hidden", type=int, default=256)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return 0 if self_test() else 1
    if not args.train or not args.test:
        parser.error("--train and --test, or --self-test")
    run(args)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
