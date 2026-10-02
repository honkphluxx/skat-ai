"""
Deep Monte-Carlo on self-play (plan 2.10, R: the pilot).

    python arena/python/train_dmc.py --data dmc-pilot/data-01 [--data ...] \\
        --init dmc-pilot/net-00 --out dmc-pilot/net-01

The self-play exporter (SelfPlayExportMain) plays whole games with one net in
all three seats, each card the one its value head rates highest (a few at
random), and writes every decision with what came of it: whether the
decider's side won. This regresses the value head, at the card that was
played, on that outcome -- binary cross entropy, the same loss and the same
meaning the head was trained with on the teacher's votes ("the chance this
card keeps my side winning"), only now the answer is the game as it was
actually played out rather than the teacher's 64 solved worlds. That is
DouZero's Deep Monte-Carlo with a win/loss target, started from the student.

The policy head is not trained here; the actors play by the value head, and
the yardstick (eval_policy.py) prints both. The model directory written is a
policy model like train_policy.py's -- policy.pt, model.json (the init's
descriptor, with the split it was held out by, plus what this step did),
fixtures.npz -- so export_policy.py and eval_policy.py take it unchanged.

Held out: a share of the self-play games, by game id, to watch the loss and
the calibration (the mean estimate against the mean outcome, by trick). The
loss of the init net on the same held-out games is printed first: if
training does not move it, nothing was learnt.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import struct
import sys
import time

import numpy as np
import torch
from torch import nn

from train_policy import CARDS, PolicyNet

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

FORMAT = 1


class SelfPlay:
    """The decisions of one or more self-play directories, as arrays."""

    def __init__(self, directories, limit=None):
        self.layouts = []
        shards = []
        for directory in directories:
            directory = pathlib.Path(directory)
            layout = json.loads((directory / "selfplay-format.json").read_text())
            if layout["format"] != FORMAT:
                raise SystemExit(f"{directory}: self-play format {layout['format']}, this reads {FORMAT}")
            self.layouts.append(layout)
            shards += sorted(directory.glob("selfplay-*.bin"))
        if not shards:
            raise SystemExit(f"no selfplay-*.bin in {directories}")
        sizes = {l["record_bytes"] for l in self.layouts}
        encodings = {(l["encoding_version"], l["encoding_size"]) for l in self.layouts}
        if len(sizes) > 1 or len(encodings) > 1:
            raise SystemExit(f"the directories disagree on the layout: {sizes} {encodings}")
        size = sizes.pop()
        f = {name: tuple(at) for name, at in self.layouts[0]["fields"].items()}
        flag = self.layouts[0]["flags"]
        self.encoding_version, self.size = encodings.pop()
        self.scale = self.layouts[0]["scale"]

        # A shard at a time, so the raw records of only one are ever in memory
        # beside the columns: a pilot iteration is gigabytes.
        columns = {k: [] for k in ("features", "legal_bits", "chosen", "flags", "trick", "contract", "won",
                                   "points", "estimate", "game")}
        left = limit
        for shard in shards:
            raw = np.fromfile(shard, dtype=np.uint8)
            if raw.size % size:
                raise SystemExit(f"{shard}: {raw.size} bytes is not a whole number of {size}-byte records")
            raw = raw.reshape(-1, size)
            if left is not None:
                raw = raw[:left]
                left -= len(raw)

            def column(name, dtype):
                at, width = f[name]
                return np.ascontiguousarray(raw[:, at:at + width]).view(dtype).reshape(-1)

            at, width = f["encoding"]
            columns["features"].append(np.ascontiguousarray(raw[:, at:at + width]))
            columns["legal_bits"].append(column("legal", "<u4"))
            columns["chosen"].append(column("chosen", np.uint8))
            columns["flags"].append(column("flags", np.uint8))
            columns["trick"].append(column("trick", np.uint8))
            columns["contract"].append(column("contract", np.uint8))
            columns["won"].append(column("won", np.uint8))
            columns["points"].append(column("points", np.uint8))
            columns["estimate"].append(column("estimate", np.uint8))
            columns["game"].append(column("game", "<i8"))
            del raw
            if left is not None and left <= 0:
                break
        joined = {k: np.concatenate(v) for k, v in columns.items()}
        columns.clear()
        self.features = joined["features"]
        self.legal_bits = joined["legal_bits"]
        self.chosen = joined["chosen"].astype(np.int64)
        flags = joined["flags"]
        self.explored = (flags & flag["explored"]) != 0
        self.declarer = (flags & flag["declarer"]) != 0
        self.trick = joined["trick"].astype(np.int64)
        self.contract = joined["contract"]
        self.won = joined["won"].astype(np.float32)
        self.points = joined["points"]
        self.estimate = joined["estimate"].astype(np.float32) / 255.0
        self.game = joined["game"]
        self.check()

    def __len__(self):
        return len(self.chosen)

    def check(self):
        """What must hold of any self-play corpus; refuses to train on one where it does not."""
        problems = []
        if not ((self.legal_bits >> self.chosen.astype(np.uint32)) & 1).all():
            problems.append("a chosen card is not legal")
        if (popcount32(self.legal_bits) < 2).any():
            problems.append("a record with fewer than two legal cards")
        if not ((self.trick >= 1) & (self.trick <= 10)).all():
            problems.append("a trick outside 1-10")
        if not np.isin(self.won, (0.0, 1.0)).all():
            problems.append("an outcome that is not 0 or 1")
        if (self.points > 120).any():
            problems.append("more than 120 points")
        # One outcome per side and game.
        key = self.game * 2 + self.declarer
        order = np.argsort(key, kind="stable")
        k, w = key[order], self.won[order]
        same = k[1:] == k[:-1]
        if (w[1:][same] != w[:-1][same]).any():
            problems.append("two outcomes for one side of one game")
        if problems:
            raise SystemExit("SELF-PLAY CHECK FAILED: " + "; ".join(problems))

    def held_out(self, fraction):
        """A mask of the rows whose game is held out (a hash of the game id)."""
        mixed = (self.game.astype(np.uint64) * np.uint64(0x9E3779B97F4A7C15)) >> np.uint64(40)
        return (mixed % np.uint64(10_000)) < np.uint64(round(fraction * 10_000))


def popcount32(words):
    words = words.astype(np.uint32)
    count = np.zeros(words.shape, dtype=np.int64)
    for bit in range(32):
        count += (words >> np.uint32(bit)) & 1
    return count


def bce_at_chosen(value_logits, chosen, won):
    picked = value_logits.gather(1, chosen[:, None]).squeeze(1)
    return nn.functional.binary_cross_entropy_with_logits(picked, won, reduction="none"), picked


class Feeder:
    def __init__(self, data, device):
        self.data = data
        self.device = device
        self.features = torch.from_numpy(data.features)
        self.chosen = torch.from_numpy(data.chosen)
        self.won = torch.from_numpy(data.won)

    def get(self, rows):
        rows_t = torch.from_numpy(rows)
        d = self.device
        x = self.features[rows_t].to(d, non_blocking=True).float().div_(self.data.scale)
        return x, self.chosen[rows_t].to(d, non_blocking=True), self.won[rows_t].to(d, non_blocking=True)


def evaluate(model, feeder, rows, batch):
    """Per-row loss and estimate on these rows, eval mode."""
    model.eval()
    losses = np.empty(len(rows), dtype=np.float64)
    estimates = np.empty(len(rows), dtype=np.float64)
    with torch.no_grad():
        for at in range(0, len(rows), batch):
            part = rows[at:at + batch]
            x, chosen, won = feeder.get(part)
            _, v = model(x)
            loss, picked = bce_at_chosen(v, chosen, won)
            losses[at:at + len(part)] = loss.cpu().numpy()
            estimates[at:at + len(part)] = torch.sigmoid(picked).cpu().numpy()
    return losses, estimates


def report(data, rows, losses, estimates, label):
    print(f"  {label}: loss {losses.mean():.4f}   estimate {estimates.mean():.3f} against outcome "
          f"{data.won[rows].mean():.3f}")
    line_l, line_e = [], []
    for t in range(1, 11):
        m = data.trick[rows] == t
        if not m.any():
            line_l.append(f"{'-':>7s}")
            line_e.append(f"{'-':>7s}")
            continue
        line_l.append(f"{losses[m].mean():7.3f}")
        line_e.append(f"{estimates[m].mean() - data.won[rows][m].mean():+7.3f}")
    print("    by trick        " + "".join(f"{t:>7d}" for t in range(1, 11)))
    print("    loss            " + "".join(line_l))
    print("    estimate - won  " + "".join(line_e))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", action="append", required=True, help="a self-play directory; repeatable")
    parser.add_argument("--init", required=True, help="the policy model to start from")
    parser.add_argument("--out", required=True)
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--batch", type=int, default=8192)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--held-out", type=float, default=0.02, help="share of the games held out")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--limit", type=int, default=0, help="read only this many records; a smoke run")
    args = parser.parse_args()

    init = pathlib.Path(args.init)
    descriptor = json.loads((init / "model.json").read_text())
    if descriptor.get("kind") != "policy" or descriptor.get("play_state"):
        raise SystemExit(f"{init}: a policy model on the encoding alone is needed (self-play writes no play state)")
    started = time.time()
    data = SelfPlay(args.data, limit=args.limit or None)
    if (data.encoding_version, data.size) != (descriptor["encoding_version"], descriptor["inputs"]):
        raise SystemExit(f"the self-play encoding v{data.encoding_version} x {data.size} is not the model's "
                         f"v{descriptor['encoding_version']} x {descriptor['inputs']}")
    held = data.held_out(args.held_out)
    val = np.flatnonzero(held)
    train = np.flatnonzero(~held)
    games = len(np.unique(data.game))
    print(f"{len(data):,} decisions of {games:,} games from {', '.join(args.data)} "
          f"({time.time() - started:.0f} s to read); held out {len(val):,}")
    print(f"  declarer's decisions {data.declarer.mean():.1%}, explored {data.explored.mean():.1%}, "
          f"won {data.won.mean():.3f}; the actors' own estimate {data.estimate.mean():.3f}")

    device = torch.device(args.device)
    torch.manual_seed(args.seed)
    model = PolicyNet(descriptor["inputs"], descriptor["hidden"], descriptor["layers"]).to(device)
    model.load_state_dict(torch.load(init / "policy.pt", map_location=device))
    feeder = Feeder(data, device)
    eval_batch = 32768

    before_l, before_e = evaluate(model, feeder, val, eval_batch)
    print()
    report(data, val, before_l, before_e, f"held out, {init.name} as it came")

    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)
    rng = np.random.default_rng(args.seed)
    steps = max(1, len(train) // args.batch)
    for epoch in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(train)
        t0 = time.time()
        running = 0.0
        for step in range(steps):
            rows = order[step * args.batch:(step + 1) * args.batch]
            x, chosen, won = feeder.get(rows)
            _, v = model(x)
            loss, _ = bce_at_chosen(v, chosen, won)
            loss = loss.mean()
            optimiser.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            running += loss.item()
        l, e = evaluate(model, feeder, val, eval_batch)
        print(f"\nepoch {epoch}: train loss {running / steps:.4f}, {time.time() - t0:.0f} s")
        report(data, val, l, e, "held out")
    after_l = l

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), out / "policy.pt")
    updated = dict(descriptor)
    if "contracts_trained" not in updated:
        # Students trained before train_policy.py recorded it: the exported
        # weights' header carries it (export_policy.py read it from the corpus).
        weights = init / "policy.bin"
        if not weights.exists():
            raise SystemExit(f"{init}: model.json does not say which contracts it saw and there is no policy.bin")
        with open(weights, "rb") as f:
            updated["contracts_trained"] = struct.unpack(">8i", f.read(32))[7]
    updated.update({
        "init_from": init.resolve().name,
        "trained_on": ",".join(pathlib.Path(d).resolve().name for d in args.data),
        "dmc": {
            "target": "won (the decider's side), BCE on the value head at the card played",
            "records": int(len(train)),
            "held_out_records": int(len(val)),
            "games": int(games),
            "epochs": args.epochs,
            "batch": args.batch,
            "lr": args.lr,
            "held_out_loss_before": float(before_l.mean()),
            "held_out_loss_after": float(after_l.mean()),
            "selfplay": data.layouts,
            "lineage": (descriptor.get("dmc") or {}).get("lineage", []) + [init.resolve().name],
        },
    })
    (out / "model.json").write_text(json.dumps(updated, indent=2) + "\n")
    model.eval()
    x = feeder.get(val[:16])[0]
    with torch.no_grad():
        p, v = model(x)
    np.savez(out / "fixtures.npz", features=x.cpu().numpy(), policy=p.cpu().numpy(), value=v.cpu().numpy())
    print(f"\nheld-out loss {before_l.mean():.4f} -> {after_l.mean():.4f}; "
          f"weights, descriptor and fixtures in {out} ({time.time() - started:.0f} s)")


if __name__ == "__main__":
    main()
