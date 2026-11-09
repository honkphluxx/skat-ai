"""
A learned declarer: the game and the discard after the skat, from the declarer-choice labels (after route 1).

    python arena/python/train_declarer.py \\
        --train arena-logs/declarer-choice/policy-oc3-ev-belief-64-shipped-s9102-b20000-k8-w0.jsonl \\
        --test  arena-logs/declarer-choice/policy-oc3-ev-belief-64-shipped-s9101-b2000-k8-w16.jsonl \\
        --out declarer-net-v1
    python arena/python/train_declarer.py --self-test

Each line of a DeclarerChoiceMain file is one declared board: every option (a
trump game covering the bid, times a discard) played out k times on the true
deal. The net scores an option from what the declarer can see -- the ten it
keeps and the two it buries, laid out relative to the option's game (the
jacks, then the trump suit, then the side suits in a fixed order), the game,
whether the pair is the heuristic's, its place, the bid and the auction's
highest bids by place -- and never from the labels' deal. It is trained to
predict each option's U (route 1's: the game value a play-out plus the win and
a hundredth of the card points), centred within its board, so only the
differences between a board's options are learned. (Route 1's softmax
objective was tried and fails here as it failed in round 4: it learns "Grand
loses" on the many boards where it does, the softmax leaves Grand no weight,
and the few boards where Grand wins move it no more.) The declarer then takes
the option scored highest.

The test is honest: on boards of another seed (--test, never trained on), the
net's choice and each reference -- shipped, fixed, searched where the file has
it -- are scored by the same labels on all k play-outs; nothing chooses on the
labels. Printed: learned minus each reference with a 95% interval, a declared
game and a game, how often the net picks another game than the reference, and
the game mix. --contract-only restricts the net to each game's heuristic pair.

Writes --out/declarer.pt and declarer.json (the layout, the widths, the
results), for an export to the player later.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np

CONTRACTS = ("DIAMONDS", "HEARTS", "SPADES", "CLUBS", "GRAND")
TRUMP_SUIT = {"CLUBS": 0, "SPADES": 1, "HEARTS": 2, "DIAMONDS": 3}     # Card.Suit ordinals
PLACES = ("forehand", "middlehand", "rearhand")
JACK = 4                                                                # Card.Rank ordinal
NON_JACK_RANKS = (0, 1, 2, 3, 5, 6, 7)                                 # 7 8 9 10 Q K A
FEATURES = 32 + 32 + len(CONTRACTS) + 1 + len(PLACES) + 1 + 2 * len(PLACES)


def layout(contract):
    """Card index (suit * 8 + rank) -> slot 0-31: the jacks, the trump suit, the side suits in order."""
    slots = np.zeros(32, dtype=np.int64)
    for suit in range(4):
        slots[suit * 8 + JACK] = suit
    suits = list(range(4))
    if contract in TRUMP_SUIT:
        suits.remove(TRUMP_SUIT[contract])
        suits.insert(0, TRUMP_SUIT[contract])
    at = 4
    for suit in suits:
        for rank in NON_JACK_RANKS:
            slots[suit * 8 + rank] = at
            at += 1
    assert at == 32 and sorted(slots) == list(range(32))
    return slots


LAYOUTS = {c: layout(c) for c in CONTRACTS}


def utility(option):
    v = np.asarray(option["value"], dtype=np.float64)
    w = np.asarray(option["wins"], dtype=np.float64)
    p = np.asarray(option["points"], dtype=np.float64)
    return float((v + w + 0.01 * p).mean())


def option_features(line, option):
    """What the declarer can see of one option, as FEATURES floats."""
    c = option["contract"]
    slots = LAYOUTS[c]
    twelve = set(line["ten"]) | set(line["skat"])
    buried = set(option["discard"])
    kept = twelve - buried
    x = np.zeros(FEATURES, dtype=np.float32)
    for card in kept:
        x[slots[card]] = 1.0
    for card in buried:
        x[32 + slots[card]] = 1.0
    at = 64
    x[at + CONTRACTS.index(c)] = 1.0
    at += len(CONTRACTS)
    x[at] = 1.0 if option["heuristic"] else 0.0
    at += 1
    x[at + PLACES.index(line["position"])] = 1.0
    at += len(PLACES)
    x[at] = line["bid"] / 100.0
    at += 1
    highest = {p: 0 for p in PLACES}
    passed = {p: 0 for p in PLACES}
    for place, value, did_pass in line.get("auction", []):
        if did_pass:
            passed[place] = 1
        else:
            highest[place] = max(highest[place], value)
    for i, p in enumerate(PLACES):
        x[at + 2 * i] = highest[p] / 100.0
        x[at + 2 * i + 1] = passed[p]
    return x


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


class Corpus:
    """Options of many boards as flat arrays: features, U, the board each belongs to."""

    def __init__(self, lines):
        self.lines = lines
        xs, us, boards, heur = [], [], [], []
        self.start = []
        for b, line in enumerate(lines):
            self.start.append(len(us))
            for o in line["options"]:
                xs.append(option_features(line, o))
                us.append(utility(o))
                boards.append(b)
                heur.append(o["heuristic"])
        self.start.append(len(us))
        self.x = np.stack(xs) if xs else np.zeros((0, FEATURES), dtype=np.float32)
        self.u = np.asarray(us, dtype=np.float32)
        self.board = np.asarray(boards, dtype=np.int64)
        self.heuristic = np.asarray(heur, dtype=bool)
        self.n_boards = len(lines)
        counts = np.bincount(self.board, minlength=self.n_boards)
        means = np.bincount(self.board, weights=self.u, minlength=self.n_boards) / np.maximum(counts, 1)
        self.centred = (self.u - means[self.board]).astype(np.float32)

    def options_of(self, b):
        return slice(self.start[b], self.start[b + 1])


def load(path):
    with open(path) as f:
        return [json.loads(row) for row in f if row.strip()]


def split_boards(n, fraction, seed):
    rng = np.random.default_rng(seed)
    order = rng.permutation(n)
    cut = int(round(n * fraction))
    return np.sort(order[cut:]), np.sort(order[:cut])


# ---------------------------------------------------------------- the net


def make_net(hidden, layers):
    import torch.nn as nn
    parts, width = [], FEATURES
    for _ in range(layers):
        parts += [nn.Linear(width, hidden), nn.ReLU()]
        width = hidden
    parts.append(nn.Linear(width, 1))
    return nn.Sequential(*parts)


def scores_of(net, x, device, batch=65536):
    import torch
    out = np.empty(len(x), dtype=np.float32)
    net.eval()
    with torch.no_grad():
        for at in range(0, len(x), batch):
            out[at:at + batch] = net(torch.from_numpy(x[at:at + batch]).to(device)).squeeze(1).cpu().numpy()
    return out


def train(corpus, boards, val_boards, args, device, log=print):
    """Trains on the options of ``boards``; keeps the epoch whose choice scores best on ``val_boards``."""
    import torch
    torch.manual_seed(args.seed)
    net = make_net(args.hidden, args.layers).to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    rows = np.concatenate([np.arange(corpus.start[b], corpus.start[b + 1]) for b in boards])
    rng = np.random.default_rng(args.seed)
    best, best_state = -np.inf, None
    x_all = torch.from_numpy(corpus.x).to(device)
    centred = torch.from_numpy(corpus.centred).to(device)
    board_all = torch.from_numpy(corpus.board).to(device)
    for epoch in range(1, args.epochs + 1):
        net.train()
        order = rng.permutation(boards)
        total, steps = 0.0, 0
        for at in range(0, len(order), args.boards_per_batch):
            chunk = order[at:at + args.boards_per_batch]
            idx = np.concatenate([np.arange(corpus.start[b], corpus.start[b + 1]) for b in chunk])
            idx_t = torch.from_numpy(idx).to(device)
            pred = net(x_all[idx_t]).squeeze(1)
            group = board_all[idx_t]
            _, inverse = torch.unique(group, return_inverse=True)
            groups = int(inverse.max()) + 1
            counts = torch.zeros(groups, device=device).index_add_(0, inverse, torch.ones_like(pred))
            mean_pred = torch.zeros(groups, device=device).index_add_(0, inverse, pred) / counts
            loss = ((pred - mean_pred[inverse] - centred[idx_t]) ** 2).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += float(loss.detach())
            steps += 1
        val = evaluate(corpus, scores_of(net, corpus.x, device), val_boards)["gain_vs_mean"]
        log(f"  epoch {epoch:3d}  loss {total / max(steps, 1):9.4f}   held-out boards: the choice's U over the "
            f"options' mean {val:+.3f}")
        if val > best:
            best = val
            best_state = {k: v.detach().clone() for k, v in net.state_dict().items()}
            kept = epoch
    net.load_state_dict(best_state)
    log(f"  kept epoch {kept} (by the held-out boards)")
    return net, kept


# ---------------------------------------------------------------- the honest test


def choose(corpus, scores, b, contract_only=False):
    s = corpus.options_of(b)
    allowed = corpus.heuristic[s] if contract_only else np.ones(s.stop - s.start, dtype=bool)
    sc = np.where(allowed, scores[s], -np.inf)
    return s.start + int(np.argmax(sc))


def references(corpus, b):
    line = corpus.lines[b]
    opts = line["options"]
    base = corpus.start[b]
    refs = {"shipped": find(opts, line["shipped"]["contract"], line["shipped"]["discard"]),
            "fixed": find(opts, line["auction_contract"], heuristic=True)}
    if line.get("searched"):
        refs["searched"] = find(opts, line["searched"]["contract"], line["searched"]["discard"])
    return {k: base + v for k, v in refs.items() if v >= 0}


def interval(values):
    values = np.asarray(values, dtype=np.float64)
    if len(values) < 2:
        return float(values.mean()) if len(values) else float("nan"), float("nan")
    return float(values.mean()), float(1.96 * values.std(ddof=1) / np.sqrt(len(values)))


def evaluate(corpus, scores, boards, contract_only=False):
    """The net's choice on ``boards`` against the references and the options' mean, by the labels."""
    out = {"diff": {}, "other_game": {}, "mix": {c: 0 for c in CONTRACTS}}
    gains_mean = []
    for b in boards:
        pick = choose(corpus, scores, b, contract_only)
        s = corpus.options_of(b)
        gains_mean.append(corpus.u[pick] - corpus.u[s].mean())
        game = corpus.lines[b]["options"][pick - s.start]["contract"]
        out["mix"][game] += 1
        for name, r in references(corpus, b).items():
            out["diff"].setdefault(name, []).append(corpus.u[pick] - corpus.u[r])
            ref_game = corpus.lines[b]["options"][r - s.start]["contract"]
            out["other_game"].setdefault(name, []).append(game != ref_game)
    out["gain_vs_mean"] = float(np.mean(gains_mean)) if gains_mean else float("nan")
    return out


def report(corpus, scores, boards, title, per_game=None, log=print):
    for contract_only in (False, True):
        r = evaluate(corpus, scores, boards, contract_only)
        log(f"\n  {title}{' -- each game with its heuristic pair' if contract_only else ''}: "
            f"{len(boards):,} boards; the choice over the options' mean {r['gain_vs_mean']:+.2f}")
        for name in ("shipped", "fixed", "searched"):
            if name not in r["diff"]:
                continue
            m, h = interval(r["diff"][name])
            game = f"   a game {m * per_game:+.2f} [{(m - h) * per_game:+.2f}, {(m + h) * per_game:+.2f}]" if per_game else ""
            log(f"    learned minus {name:8s} {m:+6.2f} [{m - h:+.2f}, {m + h:+.2f}] a declared game{game}"
                f"   another game on {np.mean(r['other_game'][name]):.1%}")
        log("    games chosen: " + "  ".join(f"{c} {n}" for c, n in r["mix"].items()))
    return evaluate(corpus, scores, boards)


# ---------------------------------------------------------------- self-test


def fake_lines(n, rng, k=8):
    """Boards where Grand pays exactly when the declarer keeps three jacks or more; other games are flat."""
    lines = []
    for b in range(n):
        cards = rng.permutation(32)[:12]
        ten, skat = [int(c) for c in cards[:10]], [int(c) for c in cards[10:]]
        jacks = sum(1 for c in cards if c % 8 == JACK)
        options = []
        for c in ("CLUBS", "GRAND"):
            for j in range(3):
                discard = sorted(int(x) for x in cards[[j, j + 1]] if True)
                kept_jacks = jacks - sum(1 for d in discard if d % 8 == JACK)
                base = 30.0 if (c == "GRAND" and kept_jacks >= 3) else (-30.0 if c == "GRAND" else 5.0)
                value = base + 20.0 * rng.standard_normal(k)
                options.append({"contract": c, "discard": discard, "heuristic": j == 0,
                                "value": [float(v) for v in value], "wins": [0] * k, "points": [0] * k})
        line = {"board": b, "bid": 18, "position": PLACES[b % 3], "auction": [[PLACES[(b + 1) % 3], 18, False]],
                "ten": ten, "skat": skat, "auction_contract": "CLUBS",
                "shipped": {"contract": "CLUBS", "discard": options[0]["discard"]}, "options": options}
        lines.append(line)
    return lines


def self_test():
    ok = True

    def check(name, passed):
        nonlocal ok
        ok &= bool(passed)
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")

    check("each game's layout is a permutation of the 32 slots", all(sorted(LAYOUTS[c]) == list(range(32))
                                                                      for c in CONTRACTS))
    check("the jacks first, the trump suit next", LAYOUTS["HEARTS"][2 * 8 + JACK] == 2
          and LAYOUTS["HEARTS"][2 * 8 + 7] == 10 and LAYOUTS["HEARTS"][0 * 8 + 0] == 11)
    line = fake_lines(1, np.random.default_rng(0))[0]
    x = option_features(line, line["options"][0])
    check("ten kept, two buried, one game, one place", x[:32].sum() == 10 and x[32:64].sum() == 2
          and x[64:69].sum() == 1 and x[70:73].sum() == 1)
    check("nothing of the labels in the features",
          np.array_equal(option_features(line, line["options"][0]),
                         option_features({**line, "options": []}, {**line["options"][0], "value": [999] * 8})))

    rng = np.random.default_rng(1)
    train_lines, test_lines = fake_lines(3000, rng), fake_lines(600, rng)
    corpus, test = Corpus(train_lines), Corpus(test_lines)
    tr, va = split_boards(corpus.n_boards, 0.1, 0)

    class Args:
        hidden, layers, lr, epochs, boards_per_batch, seed = 64, 2, 3e-3, 12, 64, 0

    import torch
    net, _ = train(corpus, tr, va, Args, torch.device("cpu"), log=lambda *_: None)
    scores = scores_of(net, test.x, torch.device("cpu"))
    r = evaluate(test, scores, range(test.n_boards))
    truth = []
    for b in range(test.n_boards):
        s = test.options_of(b)
        line = test_lines[b]
        best = [o for o in line["options"] if o["contract"] == "GRAND" and
                sum(1 for c in set(line["ten"]) | set(line["skat"]) if c % 8 == JACK and c not in o["discard"]) >= 3]
        truth.append(30.0 - 5.0 if best else 0.0)
    m, _ = interval(r["diff"]["shipped"])
    check(f"it learns when Grand pays: learned minus shipped {m:+.1f} against the truth {np.mean(truth):+.1f}",
          abs(m - np.mean(truth)) < 0.25 * np.mean(truth) + 1.0)
    check("the choice is never Grand where Grand loses", all(
        test_lines[b]["options"][choose(test, scores, b) - test.start[b]]["contract"] == "CLUBS"
        or sum(1 for c in set(test_lines[b]["ten"]) | set(test_lines[b]["skat"]) if c % 8 == JACK) >= 3
        for b in range(test.n_boards)))

    flat = Corpus(fake_lines(1, np.random.default_rng(3)))
    s = flat.options_of(0)
    pick = choose(flat, np.arange(s.stop - s.start, dtype=np.float32)[::-1].copy(), 0)
    check("choose takes the highest score", pick == 0)
    only = choose(flat, np.arange(s.stop - s.start, dtype=np.float32), 0, contract_only=True)
    check("contract only: the highest score among heuristic pairs", flat.heuristic[only] and only == 3)
    print("self-test " + ("passed" if ok else "FAILED"))
    return ok


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--train", nargs="+")
    parser.add_argument("--test")
    parser.add_argument("--out")
    parser.add_argument("--hidden", type=int, default=256)
    parser.add_argument("--layers", type=int, default=3)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--boards-per-batch", type=int, default=256)
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--declared-share", type=float, default=0.874,
                        help="declared boards a board, for the per-game column (the s9101 run's 87.4%%)")
    parser.add_argument("--device", default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return 0 if self_test() else 1
    if not (args.train and args.test and args.out):
        parser.error("--train, --test and --out, or --self-test")
    import torch
    device = torch.device(args.device or ("cuda" if torch.cuda.is_available() else "cpu"))
    train_lines = [line for path in args.train for line in load(path)]
    test_lines = load(args.test)
    train_seeds = {pathlib.Path(p).name for p in args.train}
    if pathlib.Path(args.test).name in train_seeds:
        raise SystemExit("the test file is among the training files")
    corpus, test = Corpus(train_lines), Corpus(test_lines)
    tr, va = split_boards(corpus.n_boards, args.val_fraction, args.seed)
    print(f"train: {corpus.n_boards:,} boards ({len(tr):,} + {len(va):,} held out for the epoch), "
          f"{len(corpus.u):,} options; test: {test.n_boards:,} boards of another seed; {FEATURES} features, "
          f"{args.layers} x {args.hidden}, {device}")
    net, kept = train(corpus, tr, va, args, device)
    scores = scores_of(net, test.x, device)
    r = report(test, scores, range(test.n_boards), "test", per_game=args.declared_share)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.save(net.state_dict(), out / "declarer.pt")
    summary = {"features": FEATURES, "hidden": args.hidden, "layers": args.layers, "objective": "centred U",
               "contracts": CONTRACTS, "places": PLACES, "kept_epoch": kept, "seed": args.seed,
               "train": [str(p) for p in args.train], "test": str(args.test),
               "test_boards": test.n_boards,
               "test_minus": {k: interval(v)[0] for k, v in r["diff"].items()}}
    (out / "declarer.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"\nwrote {out}/declarer.pt and declarer.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
