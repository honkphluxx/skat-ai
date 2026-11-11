"""
The learned bidder for the Java side: bidder.bin and bidder-parity.txt (after train_bidder.py).

    python arena/python/export_bidder.py --model bidder-net-v1
    python arena/python/export_bidder.py --model bidder-net-v1 --boards 30 --lines <a bid-labels .jsonl>
    python arena/python/export_bidder.py --tiny-fixture engine/src/test/resources/learned-bidder \
        --lines <a bid-labels .jsonl> --boards 12

bidder.bin (little-endian): the magic "SKBN", version 1, the feature count, the
rung count, the net count, the layer count, the scale (float), the ladder, then
per net per layer its output and input widths, the weights row by row and the
bias -- what dev.skatklar.demo.search.LearnedBidder.load reads. Every init seed
the model was trained with goes in; Java predicts their mean.

bidder-parity.txt: for --boards boards of a bid-labels file (by default the
model's own test file, which it never trained on), one line a seat -- the ten,
the place, the shipped ceiling and opening, the value at every rung and the
ceiling (learned-first at 0) this ensemble gives. LearnedBidder.checkParity
rebuilds the features in Java and must agree.

With --model, also the ensemble's row on the whole test file (the table plays
the ensemble, not the seeds one by one): union t=0 against the shipped bidder.

--tiny-fixture writes a small random ensemble (two nets of 2 x 8) and its
parity over --boards boards of --lines, for the engine's own test.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bid_report as br  # noqa: E402
from train_bidder import FEATURES, PLACES, RUNGS, SCALE, ceiling_of, evaluate, features, load, make_net  # noqa: E402

MAGIC = 0x4E424B53


def linears(net):
    import torch.nn as nn
    return [m for m in net if isinstance(m, nn.Linear)]


def write_bin(nets, path):
    layers = len(linears(nets[0]))
    with open(path, "wb") as f:
        f.write(struct.pack("<iiiiiif", MAGIC, 1, FEATURES, RUNGS, len(nets), layers, SCALE))
        f.write(struct.pack(f"<{RUNGS}i", *br.LADDER))
        for net in nets:
            for layer in linears(net):
                w = layer.weight.detach().cpu().numpy().astype("<f4")
                b = layer.bias.detach().cpu().numpy().astype("<f4")
                f.write(struct.pack("<ii", w.shape[0], w.shape[1]))
                f.write(np.ascontiguousarray(w).tobytes())
                f.write(np.ascontiguousarray(b).tobytes())


def ensemble(nets, x):
    import torch
    with torch.no_grad():
        return np.mean([n(torch.from_numpy(x)).numpy() for n in nets], axis=0) * SCALE


def parity_lines(nets, lines):
    seats = [s for line in lines for s in br.board_seats(line)]
    x = np.stack([features(s) for s in seats])
    pred = ensemble(nets, x)
    out = []
    for s, p in zip(seats, pred):
        fields = [*s.ten, PLACES[s.place], s.ceiling, int(s.open18)]
        fields += [f"{float(v):.9g}" for v in p]
        fields.append(ceiling_of(p, 0, True))
        out.append(" ".join(str(v) for v in fields))
    return out


def write_parity(nets, lines, path, note):
    rows = parity_lines(nets, lines)
    with open(path, "w") as f:
        f.write(f"# {note}\n")
        f.write("# ten place shipped-ceiling shipped-opens value-per-rung... ceiling\n")
        f.write("\n".join(rows) + "\n")
    return len(rows)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model")
    parser.add_argument("--tiny-fixture")
    parser.add_argument("--lines")
    parser.add_argument("--boards", type=int, default=30)
    args = parser.parse_args(argv)
    import torch
    if args.tiny_fixture:
        if not args.lines:
            parser.error("--tiny-fixture needs --lines")
        nets = []
        for seed in (7, 8):
            torch.manual_seed(seed)
            nets.append(make_net(torch, 8))
        os.makedirs(args.tiny_fixture, exist_ok=True)
        write_bin(nets, os.path.join(args.tiny_fixture, "bidder.bin"))
        n = write_parity(nets, load(args.lines)[:args.boards], os.path.join(args.tiny_fixture, "bidder-parity.txt"),
                         f"a tiny random ensemble (2 nets, 2 x 8) over {args.boards} boards of {os.path.basename(args.lines)}")
        print(f"wrote {args.tiny_fixture}/bidder.bin and bidder-parity.txt ({n} seats)")
        return 0
    if not args.model:
        parser.error("--model, or --tiny-fixture")
    with open(os.path.join(args.model, "bidder.json")) as f:
        meta = json.load(f)
    if meta["features"] != FEATURES or meta["rungs"] != RUNGS or meta["ladder"] != br.LADDER:
        raise SystemExit("the model's features or ladder are not this exporter's")
    nets = []
    for state in torch.load(os.path.join(args.model, "bidder.pt")):
        net = make_net(torch, meta["hidden"])
        net.load_state_dict(state)
        net.eval()
        nets.append(net)
    lines_path = args.lines or meta["test"]
    test_lines = load(lines_path)
    write_bin(nets, os.path.join(args.model, "bidder.bin"))
    n = write_parity(nets, test_lines[:args.boards], os.path.join(args.model, "bidder-parity.txt"),
                     f"{len(nets)} nets over {args.boards} boards of {os.path.basename(lines_path)}")
    print(f"wrote {args.model}/bidder.bin ({len(nets)} nets) and bidder-parity.txt ({n} seats)")
    x = np.stack([features(s) for line in test_lines for s in br.board_seats(line)])
    rows = evaluate(test_lines, [ensemble(nets, x)], thresholds=(0,))
    for name, m, h, _, rate in rows:
        print(f"  the ensemble, {name:18s} {m:+.3f} [{m - h:+.3f}, {m + h:+.3f}]   boards declared {rate:.1%}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
