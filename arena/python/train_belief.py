"""
Trains the belief model: where are the twenty-two cards I cannot see?

    python3 train_belief.py --data ../../belief-data --out ../../belief-model

One decision worth stating before the code. The model predicts, for each of the
32 cards, one of three places -- my left-hand opponent, my right-hand opponent,
the skat -- and the loss applies only to the cards the observing seat cannot
see. It does **not** enforce that the answer is a legal deal, and it should not:
ten cards go left, ten go right, two are buried, and a per-card classifier has no
way to respect that. Consistency is imposed downstream, where the sampler draws
whole arrangements and can simply reject impossible ones. Trying to bake it in
here would trade a clean training signal for a constraint the sampler enforces
for free.

The number to watch is not accuracy but accuracy *over the uniform baseline*
printed beside it. Guessing in proportion to how many places are left in each
hand is already right about 47% of the time, and that is exactly what the player
this model has to improve on believes today.
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

from belief_data import Corpus, Forgetting, score, uniform_baseline

CLASSES = 3
CARDS = 32

# torch's ONNX exporter prints a tick mark when it succeeds, and a Windows
# console is cp1252, which cannot encode one. Without this the export dies on
# its own progress message after the model has already been converted -- the
# whole run lost to a character. Errors are replaced rather than raised, because
# no diagnostic is worth failing a night of training over.
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")


class BeliefNet(nn.Module):
    """
    A plain trunk and a per-card head.

    Deliberately small and deliberately boring. It has to run on a phone inside a
    search that calls it thousands of times a game, so the budget is well under a
    megabyte of weights; and the first question is whether the *evidence* carries
    signal, which a bigger model would only blur. Anything clever belongs after
    the first honest measurement in the arena, not before it.
    """

    def __init__(self, inputs, hidden=512, layers=3, dropout=0.1):
        super().__init__()
        blocks = []
        width = inputs
        for _ in range(layers):
            blocks += [nn.Linear(width, hidden), nn.LayerNorm(hidden),
                       nn.GELU(), nn.Dropout(dropout)]
            width = hidden
        self.trunk = nn.Sequential(*blocks)
        self.head = nn.Linear(width, CARDS * CLASSES)

    def forward(self, x):
        return self.head(self.trunk(x)).view(-1, CARDS, CLASSES)


def masked_loss(logits, target, mask):
    """Cross entropy over the cards this seat is actually guessing at."""
    flat = logits.reshape(-1, CLASSES)
    losses = nn.functional.cross_entropy(flat, target.reshape(-1), reduction="none")
    losses = losses.view_as(mask)
    return (losses * mask).sum() / mask.sum().clamp(min=1)


def evaluate(model, x, target, mask, device, batch=8192):
    """Accuracy and NLL on a held-out set, at full memory."""
    model.eval()
    hits = 0.0
    total = 0.0
    nll = 0.0
    with torch.no_grad():
        for at in range(0, len(x), batch):
            xb = torch.from_numpy(x[at:at + batch]).to(device)
            tb = torch.from_numpy(target[at:at + batch]).to(device)
            mb = torch.from_numpy(mask[at:at + batch]).to(device)
            probabilities = torch.softmax(model(xb), dim=-1)
            picked = probabilities.argmax(dim=-1)
            hits += ((picked == tb).float() * mb).sum().item()
            chosen = probabilities.gather(-1, tb.unsqueeze(-1)).squeeze(-1)
            nll += (-torch.log(chosen.clamp(min=1e-9)) * mb).sum().item()
            total += mb.sum().item()
    return hits / max(total, 1), nll / max(total, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", default="../../belief-data")
    parser.add_argument("--out", default="../../belief-model")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch", type=int, default=1024)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--hidden", type=int, default=512)
    parser.add_argument("--layers", type=int, default=3)
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--limit", type=int, default=0,
                        help="use only this many records; for a quick smoke run")
    parser.add_argument("--export-only", action="store_true",
                        help="skip training and export the ONNX from belief.pt")
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    corpus = Corpus(args.data)
    x, target, mask, board = corpus.load()
    if args.limit:
        x, target, mask, board = (a[:args.limit] for a in (x, target, mask, board))
    train, val = corpus.split_by_board(board, args.val_fraction, seed=args.seed)
    print(f"encoding v{corpus.version}, {corpus.size} inputs")
    print(f"{len(train):,} training records, {len(val):,} held out "
          f"({len(np.unique(board[val])):,} whole boards)")

    baseline = np.repeat(uniform_baseline(corpus, x[val])[:, None, :], CARDS, axis=1)
    base_accuracy, base_nll = score(baseline, target[val], mask[val])
    print(f"uniform sampler baseline: {base_accuracy:.1%} correct, nll {base_nll:.4f}")
    print()

    device = torch.device(args.device)
    model = BeliefNet(corpus.size, args.hidden, args.layers).to(device)
    parameters = sum(p.numel() for p in model.parameters())
    print(f"model: {parameters:,} parameters, {parameters * 4 / 1e6:.1f} MB as float32")

    # One number for both, or the schedule runs out before the epoch does and
    # OneCycleLR raises in the middle of a long run.
    steps_per_epoch = max(1, len(train) // args.batch)
    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)
    schedule = torch.optim.lr_scheduler.OneCycleLR(
        optimiser, max_lr=args.lr, total_steps=args.epochs * steps_per_epoch)
    forgetting = Forgetting(corpus, seed=args.seed)

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    best = float("inf")
    best_epoch = 0
    best_train = float("nan")
    best_scores = (0.0, float("inf"))
    rng = np.random.default_rng(args.seed)

    if args.export_only:
        model.load_state_dict(torch.load(out / "belief.pt"))
        accuracy, nll = evaluate(model, x[val], target[val], mask[val], device)
        export(model, corpus, out, args, accuracy, nll, base_accuracy, base_nll)
        print(f"exported from belief.pt: {accuracy:.1%} correct, nll {nll:.4f}")
        return

    for epoch in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(train)
        started = time.time()
        running = 0.0
        steps = 0
        for step in range(steps_per_epoch):
            rows = order[step * args.batch:(step + 1) * args.batch]
            if len(rows) == 0:
                rows = order[:args.batch]
            # Forgetting is applied here rather than in the corpus, so one
            # generated set of shards serves every personality and the dropout
            # distribution stays a knob rather than a property of the files.
            forgotten, _ = forgetting.apply(x[rows])
            xb = torch.from_numpy(forgotten).to(device)
            tb = torch.from_numpy(target[rows]).to(device)
            mb = torch.from_numpy(mask[rows]).to(device)

            loss = masked_loss(model(xb), tb, mb)
            optimiser.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            schedule.step()
            running += loss.item()
            steps += 1

        accuracy, nll = evaluate(model, x[val], target[val], mask[val], device)
        print(f"epoch {epoch:3d}  train {running / max(steps, 1):.4f}   "
              f"val {accuracy:.1%} / {nll:.4f}   "
              f"over baseline {accuracy - base_accuracy:+.1%}   "
              f"{time.time() - started:.0f}s")
        if nll < best:
            best = nll
            best_epoch = epoch
            best_train = running / max(steps, 1)
            best_scores = (accuracy, nll)
            torch.save(model.state_dict(), out / "belief.pt")

    # Exported once, from the best checkpoint, and only after the training has
    # finished. Doing it inside the loop cost an export per improvement and,
    # worse, put a step that can fail on its own dependencies in the middle of a
    # run that takes hours.
    model.load_state_dict(torch.load(out / "belief.pt"))
    try:
        export(model, corpus, out, args, *best_scores, base_accuracy, base_nll)
        print(f"\nbest held-out nll {best:.4f} at epoch {best_epoch} of {args.epochs}, "
              f"where training loss stood at {best_train:.4f}; "
              f"weights and ONNX in {out}")
        # Deliberately an observation rather than a diagnosis. A best epoch in
        # the first half says the epochs after it bought training loss and paid
        # for it in held-out loss; whether that means too little data or too much
        # model is not something this line can tell, and the gap at the optimum
        # is the number to read. Near zero, as it was at 900k records, means the
        # model was still generalising when it peaked.
        if best_epoch < args.epochs // 2:
            print(f"Everything after epoch {best_epoch} traded held-out loss for "
                  f"training loss. The gap at the best epoch was "
                  f"{best_train - best:+.4f}; the closer that is to zero, the more "
                  "the corpus rather than the model is the binding constraint.")
    except Exception as failure:  # noqa: BLE001 -- the weights are already safe
        print(f"\nbest held-out nll {best:.4f}; weights in {out}")
        print(f"ONNX export failed, and the training is not lost: {failure}")
        print("Fix the cause and re-run with --export-only; belief.pt is on disk "
              "and nothing has to be trained again. If the message mentions a "
              "missing module, requirements.txt is the answer.")


def export(model, corpus, out, args, accuracy, nll, base_accuracy, base_nll):
    """ONNX for the app, plus fixtures so the Java side can prove it agrees."""
    model.eval()
    dummy = torch.zeros(1, corpus.size, device=next(model.parameters()).device)
    torch.onnx.export(
        model, dummy, out / "belief.onnx",
        input_names=["features"], output_names=["logits"],
        dynamic_axes={"features": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=17)

    # A handful of real vectors and what this model says about them. The Java
    # loader replays these on startup: a model and a runtime that disagree about
    # the same input is the failure this whole file format exists to prevent, and
    # it is otherwise invisible until the player is mysteriously weak.
    x, _, _, _ = corpus.load()
    sample = torch.from_numpy(x[:16]).to(dummy.device)
    with torch.no_grad():
        logits = model(sample).cpu().numpy()
    np.savez(out / "fixtures.npz", features=x[:16], logits=logits)

    (out / "model.json").write_text(json.dumps({
        "encoding_version": corpus.version,
        "inputs": corpus.size,
        "cards": CARDS,
        "classes": ["left", "right", "skat"],
        "hidden": args.hidden,
        "layers": args.layers,
        "val_accuracy": accuracy,
        "val_nll": nll,
        "baseline_accuracy": base_accuracy,
        "baseline_nll": base_nll,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
