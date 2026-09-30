"""
Trains the student: which card would the teacher play here? (plan 2.10, I3)

    python3 train_policy.py --data ../../teacher-data-v2 --out ../../policy-model

The corpus is the teacher's own card decisions (tools/teacher-export.sh): the
position as the deciding seat saw it, the legal cards, the card it played, and
its vote -- in how many of its 64 worlds each card still reaches the target.

Two heads on one trunk, and why two:

* the **policy** head is trained on the card the teacher played. Two decisions
  in five have every legal card tied at the top of the vote, and there the card
  is chosen by the tiebreaks (the cushion, the ladder, the rule order); only the
  played card carries them.
* the **value** head is trained on each legal card's vote as a share of the
  worlds. That is the soft target the plan names: it says how much worse the
  second card is, which the played card alone cannot, and it is the quantity a
  weaker level is made of later.

The student plays the policy head's card. What is printed for the held-out
deals, beside the plain agreement with the teacher's card:

* **best**: the student's card is one of the teacher's top-voted cards -- the
  fairer number, since a tie among top cards is a tiebreak and not a mistake;
* **regret**: how many of the teacher's 64 worlds the student's card holds
  fewer than the teacher's card, on average. Zero is the teacher; the number to
  watch across runs.

Each against what a card drawn at random would score, and broken down by role,
by trick and by how strong the table was.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time

import numpy as np
import torch
import torch.nn as nn

from teacher_data import CARDS, CONTRACTS, SPLIT_RULE, TeacherCorpus, run_checks, split_by_board

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

NEG = -1e9


class PolicyNet(nn.Module):
    """The belief net's trunk, with a policy head and a value head, each one per card."""

    def __init__(self, inputs, hidden=512, layers=3, dropout=0.1):
        super().__init__()
        blocks = []
        width = inputs
        for _ in range(layers):
            blocks += [nn.Linear(width, hidden), nn.LayerNorm(hidden), nn.GELU(), nn.Dropout(dropout)]
            width = hidden
        self.trunk = nn.Sequential(*blocks)
        self.policy = nn.Linear(width, CARDS)
        self.value = nn.Linear(width, CARDS)

    def forward(self, x):
        h = self.trunk(x)
        return self.policy(h), self.value(h)


def losses(policy_logits, value_logits, legal, chosen, share):
    """Cross entropy on the played card over the legal ones; BCE on each legal card's share."""
    masked = policy_logits.masked_fill(~legal, NEG)
    policy_loss = nn.functional.cross_entropy(masked, chosen)
    bce = nn.functional.binary_cross_entropy_with_logits(value_logits, share, reduction="none")
    value_loss = (bce * legal).sum() / legal.sum().clamp(min=1)
    return policy_loss, value_loss


class Batches:
    """Tensors for a set of rows, a batch at a time."""

    def __init__(self, corpus, records, device):
        self.corpus = corpus
        self.r = records
        self.device = device
        self.share = records.share().astype(np.float32)

    def get(self, rows):
        r = self.r
        d = self.device
        return (torch.from_numpy(self.corpus.unpack(r.features[rows])).to(d),
                torch.from_numpy(r.legal[rows]).to(d),
                torch.from_numpy(r.chosen[rows]).to(d),
                torch.from_numpy(self.share[rows]).to(d))


def predict(model, batches, rows, batch=8192):
    """The student's card by the policy head, and by the value head, for each row."""
    model.eval()
    by_policy = np.empty(len(rows), dtype=np.int64)
    by_value = np.empty(len(rows), dtype=np.int64)
    loss_p = loss_v = 0.0
    with torch.no_grad():
        for at in range(0, len(rows), batch):
            part = rows[at:at + batch]
            x, legal, chosen, share = batches.get(part)
            p, v = model(x)
            lp, lv = losses(p, v, legal, chosen, share)
            loss_p += lp.item() * len(part)
            loss_v += lv.item() * len(part)
            by_policy[at:at + len(part)] = p.masked_fill(~legal, NEG).argmax(dim=1).cpu().numpy()
            # The value head's card, its ties broken by the policy.
            vv = torch.sigmoid(v) + 1e-4 * torch.softmax(p.masked_fill(~legal, NEG), dim=1)
            by_value[at:at + len(part)] = vv.masked_fill(~legal, -1.0).argmax(dim=1).cpu().numpy()
    return by_policy, by_value, loss_p / max(len(rows), 1), loss_v / max(len(rows), 1)


def scores(records, rows, picks):
    """Agreement, best-set agreement, and regret in worlds, for picks on rows."""
    r = records
    idx = np.arange(len(rows))
    votes = r.votes[rows]
    top = np.where(r.legal[rows], votes, -1).max(axis=1)
    agree = (picks == r.chosen[rows])
    best = votes[idx, picks] == top
    regret = (top - votes[idx, picks]) * (64.0 / np.maximum(r.worlds[rows], 1))
    return agree, best, regret


def random_scores(records, rows):
    """What a legal card drawn at random scores, in expectation, on the same rows."""
    r = records
    legal = r.legal[rows]
    n = legal.sum(axis=1)
    votes = np.where(legal, r.votes[rows], 0)
    top = np.where(legal, r.votes[rows], -1).max(axis=1)
    agree = 1.0 / n
    best = (legal & (r.votes[rows] == top[:, None])).sum(axis=1) / n
    regret = (top - votes.sum(axis=1) / n) * (64.0 / np.maximum(r.worlds[rows], 1))
    return agree, best, regret


def report(records, rows, picks, title):
    agree, best, regret = scores(records, rows, picks)
    ra, rb, rr = random_scores(records, rows)
    r = records
    print(f"{title}: {len(rows):,} held-out decisions")
    print(f"    {'':22s} {'teacher':>8s} {'best':>7s} {'regret':>7s}   {'random: teacher':>16s} {'best':>6s} {'regret':>7s}")

    def line(name, m):
        if m.sum() == 0:
            return
        print(f"    {name:22s} {agree[m].mean():8.1%} {best[m].mean():7.1%} {regret[m].mean():7.2f}   "
              f"{ra[m].mean():16.1%} {rb[m].mean():6.1%} {rr[m].mean():7.2f}   ({m.sum():,})")

    everything = np.ones(len(rows), dtype=bool)
    line("all", everything)
    legal = r.legal[rows]
    top = np.where(legal, r.votes[rows], -1).max(axis=1)
    tied = (legal & (r.votes[rows] == top[:, None])).sum(axis=1) == legal.sum(axis=1)
    line("vote decides", ~tied)
    line("all tied (tiebreaks)", tied)
    line("declarer", r.declarer[rows])
    line("defender", ~r.declarer[rows])
    trick = r.trick[rows]
    line("tricks 1-3", trick <= 3)
    line("tricks 4-6", (trick >= 4) & (trick <= 6))
    line("tricks 7-9", trick >= 7)
    for c in range(len(CONTRACTS)):
        line(CONTRACTS[c], r.contract[rows] == c)
    seats = r.teacher_seats[rows]
    for s in (1, 2, 3):
        line(f"teacher seats {s}", seats == s)
    return float(agree.mean()), float(best.mean()), float(regret.mean())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", default="../../teacher-data-v2")
    parser.add_argument("--out", default="../../policy-model")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch", type=int, default=1024)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--hidden", type=int, default=512)
    parser.add_argument("--layers", type=int, default=3)
    parser.add_argument("--value-weight", type=float, default=1.0,
                        help="the value head's loss beside the policy's; 0 trains the policy alone")
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=1, help="the held-out split")
    parser.add_argument("--init-seed", type=int, default=None,
                        help="the weights and batch order, when they should differ from --seed")
    parser.add_argument("--fraction", type=float, default=1.0,
                        help="train on this share of the training deals (whole deals, the held-out "
                             "set unchanged): 0.25 and 0.5 beside 1 say whether more data still pays")
    parser.add_argument("--tricks", default="",
                        help="train only on decisions in these tricks, e.g. 1-3: a net for the phase "
                             "it will play (the hybrid's opening, or 4-9 for an endgame net). The "
                             "held-out set stays whole; the best epoch is chosen on these tricks")
    parser.add_argument("--weight-tricks", default="",
                        help="draw decisions in these tricks more often, e.g. 1-2:4 -- each such "
                             "record four times an epoch, the rest once; the best epoch is chosen "
                             "on these tricks")
    parser.add_argument("--limit", type=int, default=0, help="read only this many records; a smoke run")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()

    init_seed = args.seed if args.init_seed is None else args.init_seed
    torch.manual_seed(init_seed)
    corpus = TeacherCorpus(args.data)
    records = corpus.load(limit=args.limit or None)
    print(f"{len(records):,} records from {len(corpus.shards)} shards, players {corpus.players}")
    if not run_checks(corpus, records, args.val_fraction, args.seed):
        print("the corpus failed its checks; not training on it")
        sys.exit(1)

    train, val = split_by_board(records.deal, args.val_fraction, seed=args.seed)
    if args.fraction < 1.0:
        # Whole deals, chosen by a second draw of the same rule, so a quarter is
        # a quarter of the deals and not of the records.
        keep, _ = split_by_board(records.deal[train], 1.0 - args.fraction, seed=args.seed + 1000)
        train = train[keep]
        print(f"training on {args.fraction:.0%} of the training deals: {len(train):,} records")
    select = np.ones(len(val), dtype=bool)
    if args.tricks:
        low, high = trick_range(args.tricks)
        train = train[(records.trick[train] >= low) & (records.trick[train] <= high)]
        select = (records.trick[val] >= low) & (records.trick[val] <= high)
        print(f"training on tricks {low}-{high} only: {len(train):,} records")
    if args.weight_tricks:
        span, _, times = args.weight_tricks.partition(":")
        low, high = trick_range(span)
        times = int(times or 1)
        inside = train[(records.trick[train] >= low) & (records.trick[train] <= high)]
        train = np.concatenate([train] + [inside] * (times - 1))
        if not args.tricks:
            select = (records.trick[val] >= low) & (records.trick[val] <= high)
        print(f"tricks {low}-{high} drawn {times} times: {len(train):,} records an epoch")
    print(f"{len(train):,} training records, {len(val):,} held out "
          f"({len(np.unique(records.deal[val])):,} whole deals)")

    device = torch.device(args.device)
    batches = Batches(corpus, records, device)
    model = PolicyNet(corpus.size, args.hidden, args.layers).to(device)
    parameters = sum(p.numel() for p in model.parameters())
    print(f"model: {parameters:,} parameters, {parameters * 4 / 1e6:.1f} MB as float32, on {device}")
    print()

    steps_per_epoch = max(1, len(train) // args.batch)
    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)
    schedule = torch.optim.lr_scheduler.OneCycleLR(optimiser, max_lr=args.lr,
                                                   total_steps=args.epochs * steps_per_epoch)
    rng = np.random.default_rng(init_seed)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    best_regret = float("inf")
    best_epoch = 0

    for epoch in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(train)
        started = time.time()
        running_p = running_v = 0.0
        for step in range(steps_per_epoch):
            rows = order[step * args.batch:(step + 1) * args.batch]
            x, legal, chosen, share = batches.get(rows)
            p, v = model(x)
            lp, lv = losses(p, v, legal, chosen, share)
            loss = lp + args.value_weight * lv
            optimiser.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            schedule.step()
            running_p += lp.item()
            running_v += lv.item()
        by_policy, by_value, val_p, val_v = predict(model, batches, val)
        agree, best, regret = scores(records, val, by_policy)
        _, best_v, regret_v = scores(records, val, by_value)
        chosen_on = regret[select].mean()
        print(f"epoch {epoch:3d}  train {running_p / steps_per_epoch:.4f} / {running_v / steps_per_epoch:.4f}"
              f"   val {val_p:.4f} / {val_v:.4f}   teacher's card {agree.mean():.1%}  best {best.mean():.1%}"
              f"  regret {regret.mean():.2f}   (value head: best {best_v.mean():.1%} regret {regret_v.mean():.2f})"
              + (f"   selected tricks {chosen_on:.2f}" if not select.all() else "")
              + f"   {time.time() - started:.0f}s")
        if chosen_on < best_regret:
            best_regret = chosen_on
            best_epoch = epoch
            torch.save(model.state_dict(), out / "policy.pt")

    model.load_state_dict(torch.load(out / "policy.pt"))
    by_policy, by_value, _, _ = predict(model, batches, val)
    print()
    print(f"best held-out regret at epoch {best_epoch} of {args.epochs}"
          + (" (on the selected tricks)" if not select.all() else ""))
    print()
    summary = report(records, val, by_policy, "the policy head")
    print()
    report(records, val, by_value, "the value head (for comparison)")
    contracts_trained = 0
    for c in np.unique(records.contract[train]):
        contracts_trained |= 1 << int(c)
    write_descriptor(out, corpus, args, summary, best_epoch, len(train), len(val), contracts_trained)
    x = corpus.unpack(records.features[val[:16]])
    with torch.no_grad():
        p, v = model(torch.from_numpy(x).to(device))
    np.savez(out / "fixtures.npz", features=x, policy=p.cpu().numpy(), value=v.cpu().numpy())
    print(f"\nweights, descriptor and fixtures in {out}")


def trick_range(text):
    """'1-3' as (1, 3); '2' as (2, 2)."""
    low, _, high = text.partition("-")
    low, high = int(low), int(high or low)
    if not 1 <= low <= high <= 10:
        raise SystemExit(f"a trick range is 1-10, not {text}")
    return low, high


def write_descriptor(out, corpus, args, summary, best_epoch, train_rows, val_rows, contracts_trained):
    agree, best, regret = summary
    (out / "model.json").write_text(json.dumps({
        "kind": "policy",
        "encoding_version": corpus.version,
        "inputs": corpus.size,
        "cards": CARDS,
        "heads": ["policy", "value"],
        "hidden": args.hidden,
        "layers": args.layers,
        "value_weight": args.value_weight,
        "val_teacher_card": agree,
        "val_best": best,
        "val_regret_worlds": regret,
        "best_epoch": best_epoch,
        "epochs": args.epochs,
        "train_records": train_rows,
        "val_records": val_rows,
        "fraction": args.fraction,
        "tricks": args.tricks or "1-9",
        "weight_tricks": args.weight_tricks or None,
        "split_rule": SPLIT_RULE,
        "split_seed": args.seed,
        "split_fraction": args.val_fraction,
        "init_seed": args.seed if args.init_seed is None else args.init_seed,
        "trained_on": pathlib.Path(args.data).resolve().name,
        "contracts_trained": contracts_trained,
        "teacher": (pathlib.Path(args.data) / "teacher.txt").read_text().strip()
        if (pathlib.Path(args.data) / "teacher.txt").exists() else None,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
