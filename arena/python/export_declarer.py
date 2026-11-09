"""
The learned declarer for the Java side: declarer.bin and declarer-parity.txt (after route 1).

    python arena/python/export_declarer.py --model declarer-net-v1
    python arena/python/export_declarer.py --model declarer-net-v1 --boards 30 --lines <a declarer-choice .jsonl>
    python arena/python/export_declarer.py --tiny-fixture engine/src/test/resources/learned-declarer \
        --lines <a declarer-choice .jsonl> --boards 12

declarer.bin (little-endian): the magic "SKDN", version 1, the feature count,
the layer count, then per layer its output and input widths, the weights row by
row and the bias -- what dev.skatklar.demo.search.LearnedDeclarer.load reads.

declarer-parity.txt: for --boards boards of a declarer-choice file (by default
the model's own test file, which it never trained on), one line an option --
the twelve, the place, the bid, each place's highest bid and pass, the game,
the two buried cards, the heuristic flag and the score this net gives the
option. LearnedDeclarer.checkParity rebuilds the features from those inputs in
Java and must give the same scores, so a feature spelled differently on the two
sides cannot pass unnoticed.

--tiny-fixture writes a small random net (2 x 8) and its parity over --boards
boards of --lines, for the engine's own test.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import struct
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from train_declarer import CONTRACTS, FEATURES, PLACES, make_net, option_features  # noqa: E402

MAGIC = 0x4E444B53


def write_bin(net, path):
    import torch.nn as nn
    linears = [m for m in net if isinstance(m, nn.Linear)]
    with open(path, "wb") as f:
        f.write(struct.pack("<iiii", MAGIC, 1, FEATURES, len(linears)))
        for layer in linears:
            w = layer.weight.detach().cpu().numpy().astype("<f4")
            b = layer.bias.detach().cpu().numpy().astype("<f4")
            f.write(struct.pack("<ii", w.shape[0], w.shape[1]))
            f.write(np.ascontiguousarray(w).tobytes())
            f.write(np.ascontiguousarray(b).tobytes())


def auction_by_place(line):
    highest = {p: 0 for p in PLACES}
    passed = {p: 0 for p in PLACES}
    for place, value, did_pass in line.get("auction", []):
        if did_pass:
            passed[place] = 1
        else:
            highest[place] = max(highest[place], value)
    return highest, passed


def parity_lines(net, lines):
    import torch
    out = []
    net.eval()
    for line in lines:
        highest, passed = auction_by_place(line)
        twelve = list(line["ten"]) + list(line["skat"])
        xs = np.stack([option_features(line, o) for o in line["options"]])
        with torch.no_grad():
            scores = net(torch.from_numpy(xs)).squeeze(1).numpy()
        for o, s in zip(line["options"], scores):
            fields = [*twelve, PLACES.index(line["position"]), line["bid"]]
            for p in PLACES:
                fields += [highest[p], passed[p]]
            fields += [o["contract"], *sorted(o["discard"]), int(bool(o["heuristic"])), f"{float(s):.9g}"]
            out.append(" ".join(str(x) for x in fields))
    return out


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model")
    parser.add_argument("--lines", help="a declarer-choice .jsonl for the parity lines (default: the model's test)")
    parser.add_argument("--boards", type=int, default=30)
    parser.add_argument("--tiny-fixture", help="write a tiny random net and its parity here, for the engine test")
    args = parser.parse_args(argv)
    import torch
    if args.tiny_fixture:
        torch.manual_seed(7)
        net = make_net(8, 2)
        out = pathlib.Path(args.tiny_fixture)
        out.mkdir(parents=True, exist_ok=True)
        write_bin(net, out / "declarer.bin")
        if not args.lines:
            parser.error("--tiny-fixture needs --lines")
        with open(args.lines) as f:
            lines = [json.loads(row) for _, row in zip(range(args.boards), f)]
        rows = parity_lines(net, lines)
        (out / "declarer-parity.txt").write_text(f"# a tiny random net (2 x 8, seed 7), {len(lines)} boards of "
                                                f"{pathlib.Path(args.lines).name}\n" + "\n".join(rows) + "\n")
        print(f"wrote {out}/declarer.bin and declarer-parity.txt ({len(rows)} options)")
        return 0
    if not args.model:
        parser.error("--model, or --tiny-fixture")
    model = pathlib.Path(args.model)
    meta = json.loads((model / "declarer.json").read_text())
    net = make_net(meta["hidden"], meta["layers"])
    net.load_state_dict(torch.load(model / "declarer.pt", map_location="cpu"))
    write_bin(net, model / "declarer.bin")
    source = args.lines or meta["test"]
    with open(source) as f:
        lines = [json.loads(row) for _, row in zip(range(args.boards), f)]
    rows = parity_lines(net, lines)
    (model / "declarer-parity.txt").write_text(f"# {model.name}: {len(lines)} boards of {source}\n"
                                               + "\n".join(rows) + "\n")
    print(f"wrote {model}/declarer.bin and declarer-parity.txt ({len(lines)} boards, {len(rows)} options)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
