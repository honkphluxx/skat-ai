"""
Route 1, step 2: the student learns from the all-cards labels (plan 2.10).

    python arena/python/train_outcomes.py --data allcards-data/r1-k8-s9001 \\
        --anchor teacher-data-best-600 --init policy-b600-pa40 --out policy-oc1-ev
    python arena/python/train_outcomes.py --self-test

Starts from a trained student (--init: its policy.pt, both heads) and trains
on the all-cards corpus (AllCardsExportMain): for every decision of the
student's self-play, every legal card's mean game value, win share and card
points over k play-outs on the table's real cards, from the decider's side.

Three losses, summed:

* the **policy**, by --objective:
  - ``expected`` (the chosen one): raise the expected score. With U a card's
    utility -- its mean game value a play-out, plus --win-weight times its win
    share and --points-weight times its mean card points (the ranking best
    uses: game value, then wins, then points) -- the loss is
    -sum_a pi(a) (U(a) - mean U) / --adv-scale over the legal cards. It is
    linear in the labels, so over many deals it climbs the expected utility
    itself: the labels were measured on the true cards, which the decider
    cannot see, and only a linear use of them is unbiased for its choice.
  - ``best`` (the comparison): cross entropy towards the card with the best U
    on this deal (ties shared). That teaches which card is most often best,
    not which is best on average.
* the **value** head: binary cross entropy towards each legal card's win share
  (--value-weight), the chance that the card keeps the decider's side winning
  as the student plays on;
* the **anchor**: the imitation loss on --anchor (the teacher corpus the init
  was trained on), the policy's cross entropy towards the card played there,
  a batch of it a step (--anchor-weight); against the drift the DMC pilot
  showed. Its value term is off by default (--anchor-value-weight): the value
  head now means the self-play outcome, not the teacher's vote.

Suits are permuted on both (--permute-suits all). Held out: a tenth of the
all-cards boards (split by board, --split-seed), and the anchor's own held-out
deals by the init's split -- the same deals the yardstick (eval_policy.py)
reads, so none of them is trained on. Printed per epoch, before training as
the epoch-0 line: on the held-out all-cards decisions, the policy's top card's
gain in U over the legal cards' mean (higher is better; what the objective
climbs), its shortfall from the labels' best (inflated by the labels' noise
early in the game, comparable between nets), how often it is the best or tied;
the value head's loss and calibration; and on the anchor's held-out deals the
regret in teacher worlds, to watch imitation drift. The epoch with the highest
held-out gain is kept (--select=gain) or the lowest teacher regret
(--select=teacher).

Writes --out as train_policy.py does -- policy.pt, model.json (the init's
descriptor, its split kept for the yardstick, plus what this run did),
fixtures.npz -- so export_policy.py, eval_policy.py and the arena take it.
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

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from allcards_data import AllCardsCorpus, split_by_board as allcards_split  # noqa: E402
from teacher_data import TeacherCorpus, split_by_board as teacher_split  # noqa: E402
from train_policy import CARDS, NEG, Batches, PolicyNet, losses, scores  # noqa: E402
import suit_permutation  # noqa: E402

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

OBJECTIVES = ("expected", "best")


# ------------------------------------------------------------------ the losses

def masked_logits(policy_logits, legal):
    return policy_logits.masked_fill(~legal, NEG)


def expected_loss(policy_logits, legal, utility, scale):
    """-E_pi[U - mean U] / scale over the legal cards: linear in the labels, so unbiased over deals."""
    pi = torch.softmax(masked_logits(policy_logits, legal), dim=1)
    count = legal.sum(dim=1, keepdim=True).clamp(min=1)
    mean = (utility * legal).sum(dim=1, keepdim=True) / count
    advantage = torch.where(legal, utility - mean, torch.zeros_like(utility)) / scale
    return -(pi * advantage).sum(dim=1).mean()


def best_targets(legal, utility):
    """The cards with the best U on the record (ties shared), as a distribution over the legal cards."""
    u = torch.where(legal, utility, torch.full_like(utility, -1e30))
    top = u.max(dim=1, keepdim=True).values
    best = legal & (u >= top - 1e-6)
    return best.float() / best.sum(dim=1, keepdim=True).float()


def best_loss(policy_logits, legal, utility):
    """Cross entropy towards the card with the best U on this deal: the biased comparison."""
    target = best_targets(legal, utility)
    return -(target * torch.log_softmax(masked_logits(policy_logits, legal), dim=1)).sum(dim=1).mean()


def value_loss(value_logits, legal, win_share):
    bce = nn.functional.binary_cross_entropy_with_logits(value_logits, win_share, reduction="none")
    return (bce * legal).sum() / legal.sum().clamp(min=1)


def utility_of(records, win_weight, points_weight):
    """U per card (n, 32): mean game value a play-out, plus the win share and card points as tiebreaks."""
    k = np.maximum(records.k, 1)[:, None].astype(np.float32)
    u = (records.value / k + win_weight * records.wins / k + points_weight * records.points / k)
    return np.where(records.legal, u, 0.0).astype(np.float32)


# ------------------------------------------------------------------ batches

class OutcomeBatches:
    """Tensors for all-cards rows; suits permuted on request, the labels moved with the cards."""

    def __init__(self, corpus, records, device, permute=None, seed=0, win_weight=1.0, points_weight=0.01):
        self.corpus = corpus
        self.r = records
        self.device = device
        self.utility = utility_of(records, win_weight, points_weight)
        self.win_share = records.win_share().astype(np.float32)
        self.table = None
        if permute:
            self.table = suit_permutation.SuitPermutation(corpus.fields, corpus.size)
            self.allowed = self.table.allowed(records.features, records.contract, permute)
            self.rng = np.random.default_rng(seed)

    def get(self, rows, augment=False):
        r = self.r
        features, legal = r.features[rows], r.legal[rows]
        utility, share = self.utility[rows], self.win_share[rows]
        if augment and self.table is not None:
            t, contract = self.table, r.contract[rows]
            k = t.draw(self.allowed[rows], self.rng)
            features = t.features(features, contract, k)
            legal, utility, share = (t.per_card(legal, contract, k), t.per_card(utility, contract, k),
                                     t.per_card(share, contract, k))
        d = self.device
        return (torch.from_numpy(self.corpus.unpack(features)).to(d),
                torch.from_numpy(np.ascontiguousarray(legal)).to(d),
                torch.from_numpy(np.ascontiguousarray(utility)).to(d),
                torch.from_numpy(np.ascontiguousarray(share)).to(d))


# ------------------------------------------------------------------ measures

def evaluate_outcomes(model, batches, rows, trick, batch=8192):
    """On held-out all-cards rows: the top card's gain over the mean, its shortfall, best-or-tied, value fit."""
    model.eval()
    gain, short, best = [], [], []
    vloss = 0.0
    pred_sum = np.zeros(10)
    true_sum = np.zeros(10)
    count = np.zeros(10)
    with torch.no_grad():
        for at in range(0, len(rows), batch):
            part = rows[at:at + batch]
            x, legal, utility, share = batches.get(part)
            p, v = model(x)
            top = masked_logits(p, legal).argmax(dim=1)
            u = torch.where(legal, utility, torch.full_like(utility, -1e30))
            umax = u.max(dim=1).values
            utop = utility.gather(1, top[:, None])[:, 0]
            mean = (utility * legal).sum(dim=1) / legal.sum(dim=1)
            gain.append((utop - mean).cpu().numpy())
            short.append((umax - utop).cpu().numpy())
            best.append((utop >= umax - 1e-6).cpu().numpy())
            vloss += value_loss(v, legal, share).item() * len(part)
            prob = torch.sigmoid(v)
            t = trick[part]
            per = ((prob * legal).sum(dim=1) / legal.sum(dim=1)).cpu().numpy()
            truth = ((share * legal).sum(dim=1) / legal.sum(dim=1)).cpu().numpy()
            np.add.at(pred_sum, t, per)
            np.add.at(true_sum, t, truth)
            np.add.at(count, t, 1)
    gain, short, best = np.concatenate(gain), np.concatenate(short), np.concatenate(best)
    groups = {"1-3": trick[rows] <= 3, "4-6": (trick[rows] >= 4) & (trick[rows] <= 6), "7-9": trick[rows] >= 7}
    out = {"gain": float(gain.mean()), "shortfall": float(short.mean()), "best": float(best.mean()),
           "value_loss": vloss / max(len(rows), 1)}
    for name, m in groups.items():
        out[f"gain {name}"] = float(gain[m].mean())
        out[f"best {name}"] = float(best[m].mean())
    out["calibration"] = {int(t): (pred_sum[t] / count[t], true_sum[t] / count[t])
                          for t in range(1, 10) if count[t]}
    return out


def evaluate_teacher(model, batches, records, rows, batch=8192):
    """Regret in teacher worlds on the anchor's held-out rows (train_policy's measure)."""
    model.eval()
    picks = np.empty(len(rows), dtype=np.int64)
    with torch.no_grad():
        for at in range(0, len(rows), batch):
            part = rows[at:at + batch]
            x, legal, _, _ = batches.get(part)
            p, _ = model(x)
            picks[at:at + len(part)] = masked_logits(p, legal).argmax(dim=1).cpu().numpy()
    agree, best, regret = scores(records, rows, picks)
    return float(regret.mean()), float(agree.mean())


def line(epoch, o, teacher, elapsed=None, train=None):
    head = f"epoch {epoch:3d}"
    if train is not None:
        head += f"  train policy {train[0]:.4f} value {train[1]:.4f} anchor {train[2]:.4f}"
    return (f"{head}   held out: gain {o['gain']:+.3f} (1-3 {o['gain 1-3']:+.3f}, 4-6 {o['gain 4-6']:+.3f}, "
            f"7-9 {o['gain 7-9']:+.3f})  shortfall {o['shortfall']:.3f}  best {o['best']:.1%} "
            f"(1-3 {o['best 1-3']:.1%}, 7-9 {o['best 7-9']:.1%})  value {o['value_loss']:.4f}   "
            f"teacher regret {teacher[0]:.3f} (teacher's card {teacher[1]:.1%})"
            + (f"   {elapsed:.0f}s" if elapsed is not None else ""))


# ------------------------------------------------------------------ the self-test

def self_test():
    """The objectives on made-up decisions, where the right answer is known. Exit code 1 on a failure."""
    torch.manual_seed(0)
    failures = []

    def check(name, ok):
        print(f"  {'ok  ' if ok else 'FAIL'}  {name}")
        if not ok:
            failures.append(name)

    # One position, three legal cards and one illegal. Card 0 gains a little on
    # 60% of deals and loses a little on the rest; card 1 gains a lot on 40%;
    # card 2 is always mediocre; card 5 is illegal but would be best.
    rng = np.random.default_rng(1)
    n = 4000
    lucky = rng.random(n) < 0.6
    u = np.zeros((n, CARDS), dtype=np.float32)
    u[:, 0] = np.where(lucky, 2.0, -1.0)          # mean 0.8, best on 60% of deals
    u[:, 1] = np.where(lucky, 0.0, 6.0)           # mean 2.4, best on 40%
    u[:, 2] = 0.5
    u[:, 5] = 50.0
    legal = np.zeros((n, CARDS), dtype=bool)
    legal[:, [0, 1, 2]] = True
    u = np.where(legal, u, 0.0).astype(np.float32)
    U, L = torch.from_numpy(u), torch.from_numpy(legal)

    def train(objective):
        logits = torch.zeros(1, CARDS, requires_grad=True)
        opt = torch.optim.SGD([logits], lr=0.5)
        for _ in range(400):
            idx = torch.from_numpy(rng.integers(0, n, 256))
            p = logits.expand(len(idx), CARDS)
            loss = expected_loss(p, L[idx], U[idx], 2.0) if objective == "expected" else best_loss(p, L[idx], U[idx])
            opt.zero_grad()
            loss.backward()
            opt.step()
        return torch.softmax(masked_logits(logits.detach(), L[:1]), dim=1)[0]

    pe = train("expected")
    pb = train("best")
    check("raise the expected score picks the card best on average (1), not the one most often best (0)",
          int(pe.argmax()) == 1 and pe[1] > 0.9)
    check("the per-deal best card picks the card most often best (0): the bias it is kept beside for",
          int(pb.argmax()) == 0)
    check("no probability on an illegal card, however good its label", float(pe[5]) < 1e-6 and float(pb[5]) < 1e-6)

    # The gradient's sign and scale: at uniform logits the gradient of the
    # expected loss on a card is -(A - E_pi A)/n_legal/scale.
    p = torch.zeros(1, CARDS, requires_grad=True)
    one_u = torch.zeros(1, CARDS)
    one_u[0, 0], one_u[0, 1], one_u[0, 2] = 3.0, 0.0, 0.0
    expected_loss(p, L[:1], one_u, 1.0).backward()
    g = p.grad[0]
    check("the gradient lowers the loss by raising the better card's logit", float(g[0]) < 0 < float(g[1]))
    check("and leaves an illegal card's logit alone", float(g[5]) == 0.0)
    check("a constant added to every legal card's utility changes nothing",
          torch.allclose(expected_loss(p, L[:1], one_u, 1.0), expected_loss(p, L[:1], one_u + 7.0 * L[:1], 1.0)))

    # Illegal cards are out of both losses: their logits change nothing, and a
    # record whose legal cards are all negative (a lost game) does not send
    # the per-deal target to an illegal card's zero.
    quiet = torch.zeros(1, CARDS)
    quiet[0, 0] = 1.0
    loud = quiet.clone()
    loud[0, 5] = 10.0
    neg = torch.zeros(1, CARDS)
    neg[0, 0], neg[0, 1], neg[0, 2] = -3.0, -1.0, -2.0
    same_e = torch.allclose(expected_loss(quiet, L[:1], neg, 1.0), expected_loss(loud, L[:1], neg, 1.0))
    same_b = torch.allclose(best_loss(quiet, L[:1], neg), best_loss(loud, L[:1], neg))
    check("an illegal card's logit changes neither loss", same_e and same_b)
    t = best_targets(L[:1], neg)[0]
    check("all legal cards negative: the per-deal target is the least bad legal card",
          abs(float(t[1]) - 1.0) < 1e-6 and float(t[5]) == 0.0 and float(t[3]) == 0.0)

    # Ties share the per-deal target.
    tie = torch.zeros(1, CARDS)
    tie[0, 0], tie[0, 1] = 1.0, 1.0
    t = best_targets(L[:1], tie)[0]
    check("a tie at the top shares the per-deal target", abs(float(t[0]) - 0.5) < 1e-6 and abs(float(t[1]) - 0.5) < 1e-6)

    # The value head only on the legal cards.
    v = torch.zeros(1, CARDS, requires_grad=True)
    share = torch.full((1, CARDS), 0.25)
    value_loss(v, L[:1], share).backward()
    check("the value loss touches the legal cards only", float(v.grad[0, 5]) == 0.0 and float(v.grad[0, 0]) != 0.0)

    print("self-test passed" if not failures else f"SELF-TEST FAILED: {failures}")
    return 0 if not failures else 1


# ------------------------------------------------------------------ main

def load_init(directory, device):
    directory = pathlib.Path(directory)
    descriptor = json.loads((directory / "model.json").read_text())
    if descriptor.get("kind") != "policy" or descriptor.get("play_state"):
        raise SystemExit(f"{directory} is not an encoding-only policy model")
    model = PolicyNet(descriptor["inputs"], descriptor["hidden"], descriptor["layers"]).to(device)
    model.load_state_dict(torch.load(directory / "policy.pt", map_location=device))
    return model, descriptor


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="the objectives on made-up decisions, then exit")
    parser.add_argument("--data", help="the all-cards corpus")
    parser.add_argument("--anchor", help="the teacher corpus for the imitation anchor")
    parser.add_argument("--init", help="the student to start from (policy.pt, model.json)")
    parser.add_argument("--out")
    parser.add_argument("--objective", default="expected", choices=OBJECTIVES)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch", type=int, default=1024)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--warmup", type=int, default=200, help="steps of linear warm-up, then cosine to zero")
    parser.add_argument("--adv-scale", type=float, default=20.0, help="game value that counts as one unit of advantage")
    parser.add_argument("--win-weight", type=float, default=1.0)
    parser.add_argument("--points-weight", type=float, default=0.01)
    parser.add_argument("--value-weight", type=float, default=1.0)
    parser.add_argument("--anchor-weight", type=float, default=1.0)
    parser.add_argument("--anchor-value-weight", type=float, default=0.0)
    parser.add_argument("--permute-suits", default="all", choices=("off",) + suit_permutation.MODES)
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--split-seed", type=int, default=1, help="the all-cards held-out boards")
    parser.add_argument("--init-seed", type=int, default=1)
    parser.add_argument("--select", default="gain", choices=("gain", "teacher"))
    parser.add_argument("--teacher-val", type=int, default=200_000, help="anchor held-out rows to score each epoch")
    parser.add_argument("--limit", type=int, default=0, help="read only this many all-cards records")
    parser.add_argument("--anchor-limit", type=int, default=0, help="read only this many anchor records")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    for name in ("data", "anchor", "init", "out"):
        if not getattr(args, name):
            raise SystemExit(f"--{name} is required")

    torch.manual_seed(args.init_seed)
    device = torch.device(args.device)
    permute = None if args.permute_suits == "off" else args.permute_suits
    model, descriptor = load_init(args.init, device)
    print(f"init {args.init}: {descriptor['layers']} x {descriptor['hidden']}, trained on {descriptor.get('trained_on')}")

    corpus = AllCardsCorpus(args.data)
    records = corpus.load(limit=args.limit or None)
    train, val = allcards_split(records.board, args.val_fraction, seed=args.split_seed)
    print(f"{args.data}: {len(records):,} records, k {corpus.k}; {len(train):,} training, {len(val):,} held out "
          f"({len(np.unique(records.board[val])):,} whole boards)")

    anchor = TeacherCorpus(args.anchor)
    anchor_records = anchor.load(limit=args.anchor_limit or None)
    a_train, a_val = teacher_split(anchor_records.deal, descriptor["split_fraction"], seed=descriptor["split_seed"])
    if len(a_val) > args.teacher_val:
        a_val = np.sort(np.random.default_rng(0).choice(a_val, args.teacher_val, replace=False))
    print(f"anchor {args.anchor}: {len(anchor_records):,} records, {len(a_train):,} training; "
          f"{len(a_val):,} held out by the init's split (seed {descriptor['split_seed']})")
    if permute:
        if not suit_permutation.check_fixtures(anchor.fields, anchor.size, verbose=False):
            raise SystemExit("the permutation does not reproduce the Java encoder's pairs; not training with it")
        if anchor.fields != corpus.fields:
            raise SystemExit("the anchor and the all-cards corpus speak different encodings")

    batches = OutcomeBatches(corpus, records, device, permute, args.init_seed + 1, args.win_weight,
                             args.points_weight)
    anchor_batches = Batches(anchor, anchor_records, device, False, permute, seed=args.init_seed + 2)

    steps_per_epoch = max(1, len(train) // args.batch)
    total = args.epochs * steps_per_epoch
    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)

    def lr_at(step):
        if step < args.warmup:
            return (step + 1) / args.warmup
        return 0.5 * (1 + np.cos(np.pi * (step - args.warmup) / max(1, total - args.warmup)))

    schedule = torch.optim.lr_scheduler.LambdaLR(optimiser, lr_at)
    rng = np.random.default_rng(args.init_seed)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    o = evaluate_outcomes(model, batches, val, records.trick)
    t = evaluate_teacher(model, anchor_batches, anchor_records, a_val)
    print(line(0, o, t))
    start_measures = (o, t)
    best_score = o["gain"] if args.select == "gain" else -t[0]
    best_epoch = 0
    best_measures = (o, t)
    torch.save(model.state_dict(), out / "policy.pt")

    anchor_order = rng.permutation(a_train)
    anchor_at = 0
    for epoch in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(train)
        started = time.time()
        sums = np.zeros(3)
        for step in range(steps_per_epoch):
            rows = order[step * args.batch:(step + 1) * args.batch]
            x, legal, utility, share = batches.get(rows, augment=True)
            p, v = model(x)
            lp = (expected_loss(p, legal, utility, args.adv_scale) if args.objective == "expected"
                  else best_loss(p, legal, utility))
            lv = value_loss(v, legal, share)
            if anchor_at + args.batch > len(anchor_order):
                anchor_order, anchor_at = rng.permutation(a_train), 0
            arows = np.sort(anchor_order[anchor_at:anchor_at + args.batch])
            anchor_at += args.batch
            ax, alegal, achosen, ashare = anchor_batches.get(arows, augment=True)
            ap, av = model(ax)
            la_p, la_v = losses(ap, av, alegal, achosen, ashare)
            la = la_p + args.anchor_value_weight * la_v
            loss = lp + args.value_weight * lv + args.anchor_weight * la
            optimiser.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            schedule.step()
            sums += (lp.item(), lv.item(), la.item())
        o = evaluate_outcomes(model, batches, val, records.trick)
        t = evaluate_teacher(model, anchor_batches, anchor_records, a_val)
        print(line(epoch, o, t, time.time() - started, sums / steps_per_epoch))
        score = o["gain"] if args.select == "gain" else -t[0]
        if score > best_score:
            best_score, best_epoch, best_measures = score, epoch, (o, t)
            torch.save(model.state_dict(), out / "policy.pt")

    model.load_state_dict(torch.load(out / "policy.pt", map_location=device))
    o, t = best_measures
    print(f"\nkept epoch {best_epoch} of {args.epochs} (by {args.select})")
    print(f"  held-out gain over the legal mean: {start_measures[0]['gain']:+.3f} at the init -> {o['gain']:+.3f}; "
          f"best or tied {start_measures[0]['best']:.1%} -> {o['best']:.1%}; "
          f"teacher regret {start_measures[1][0]:.3f} -> {t[0]:.3f}")
    print("  the value head's calibration on the held-out decisions, by trick (predicted / actual win share):")
    print("   " + "  ".join(f"{k}: {a:.3f}/{b:.3f}" for k, (a, b) in o["calibration"].items()))

    d = dict(descriptor)
    d.update({
        "best_epoch": best_epoch,
        "epochs": args.epochs,
        "lr": args.lr,
        "init_from": pathlib.Path(args.init).resolve().name,
        "init_seed": args.init_seed,
        "trained_on": pathlib.Path(args.data).resolve().name,
        "anchor": pathlib.Path(args.anchor).resolve().name,
        "objective": args.objective,
        "adv_scale": args.adv_scale,
        "win_weight": args.win_weight,
        "points_weight": args.points_weight,
        "value_weight": args.value_weight,
        "anchor_weight": args.anchor_weight,
        "anchor_value_weight": args.anchor_value_weight,
        "permute_suits": permute,
        "outcome_split_seed": args.split_seed,
        "outcome_val_fraction": args.val_fraction,
        "train_records": int(len(train)),
        "val_records": int(len(val)),
        "val_gain": o["gain"],
        "val_best_or_tied": o["best"],
        "val_regret_worlds": t[0],
        "select": args.select,
        "value_meaning": "win share of play-outs, the student playing on (all-cards labels)",
    })
    (out / "model.json").write_text(json.dumps(d, indent=2) + "\n")
    x, _, _, _ = anchor_batches.get(a_val[:16])
    model.eval()
    with torch.no_grad():
        p, v = model(x)
    np.savez(out / "fixtures.npz", features=x.cpu().numpy(), policy=p.cpu().numpy(), value=v.cpu().numpy())
    print(f"\nweights, descriptor and fixtures in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
