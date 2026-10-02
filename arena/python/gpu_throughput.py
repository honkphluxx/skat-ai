"""
What the GPU can do for self-play (plan 2.10, R): the learner's half of the
throughput test, and the inference an actor could hand it.

    python arena/python/gpu_throughput.py [--seconds 1.5] [--hidden 512 --layers 3]

Self-play for a network-only player needs games by the hundred million. The
CPU half (SelfPlayThroughputMain) times the actors as they are today: one
forward pass per card, in Java, one input at a time. This half times the same
network, untrained -- speed does not depend on the weights -- three ways, for
the encoding alone (306 inputs) and with the play state (538):

  1. inference on the GPU, by batch size: what an actor would get if it
     gathered the decisions of many games and asked the GPU at once. Timed
     twice: the forward pass alone, and with the inputs crossing from the host
     as the corpus stores them, a byte a feature, and divided by 255 on the
     card -- the way an actor would have to send them;
  2. inference on the CPU with torch, by batch size: the same batching
     without a GPU, against which the Java forward pass's one-at-a-time
     number reads;
  3. training steps on the GPU, by batch size: decisions a second the learner
     can swallow -- the student's own losses, AdamW, fp32 and bf16.

Prints one table per part. Nothing is read or written.
"""

from __future__ import annotations

import argparse
import os
import platform
import sys
import time

import torch

from train_policy import CARDS, PolicyNet, losses

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

ENCODING = 306
WITH_PLAY_STATE = 538


def timed(step, sync, seconds, minimum=3):
    """Runs step until `seconds` have passed (and at least `minimum` times); seconds per call."""
    step()
    sync()  # warm-up: allocation, kernel choice
    calls = 0
    start = time.perf_counter()
    while True:
        step()
        calls += 1
        if calls >= minimum:
            sync()
            elapsed = time.perf_counter() - start
            if elapsed >= seconds:
                return elapsed / calls


def gpu_inference(model, inputs, batch, device, seconds, bf16, from_host):
    model.eval()
    if from_host:
        host = torch.randint(0, 256, (batch, inputs), dtype=torch.uint8).pin_memory()

        def step():
            with torch.inference_mode(), torch.autocast("cuda", dtype=torch.bfloat16, enabled=bf16):
                x = host.to(device, non_blocking=True).float().div_(255.0)
                p, v = model(x)
                p.argmax(dim=1).cpu()  # the answer has to come back to the actor
    else:
        x = torch.rand(batch, inputs, device=device)

        def step():
            with torch.inference_mode(), torch.autocast("cuda", dtype=torch.bfloat16, enabled=bf16):
                model(x)
    return batch / timed(step, torch.cuda.synchronize, seconds)


def cpu_inference(model, inputs, batch, seconds):
    model.eval()
    x = torch.rand(batch, inputs)

    def step():
        with torch.inference_mode():
            model(x)
    return batch / timed(step, lambda: None, seconds)


def gpu_training(model, inputs, batch, device, seconds, bf16):
    model.train()
    optimiser = torch.optim.AdamW(model.parameters(), lr=1e-4, weight_decay=1e-2)
    x = torch.rand(batch, inputs, device=device)
    legal = torch.rand(batch, CARDS, device=device) < 0.3
    legal[:, 0] = True
    chosen = torch.zeros(batch, dtype=torch.long, device=device)
    share = torch.rand(batch, CARDS, device=device)

    def step():
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=bf16):
            p, v = model(x)
            policy_loss, value_loss = losses(p.float(), v.float(), legal, chosen, share)
            loss = policy_loss + value_loss
        optimiser.zero_grad(set_to_none=True)
        loss.backward()
        optimiser.step()
    return batch / timed(step, torch.cuda.synchronize, seconds)


def row(label, cells):
    print(f"    {label:30s}" + "".join(cells))


def cell(rate):
    if rate is None:
        return f"{'-':>12s}"
    if rate >= 1e6:
        return f"{rate / 1e6:10.2f} M"
    if rate >= 1e3:
        return f"{rate / 1e3:10.1f} k"
    return f"{rate:12.0f}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seconds", type=float, default=1.5, help="time spent on each cell")
    parser.add_argument("--hidden", type=int, default=512)
    parser.add_argument("--layers", type=int, default=3)
    parser.add_argument("--cpu-only", action="store_true", help="skip the GPU parts (a smoke run)")
    args = parser.parse_args()

    cuda = torch.cuda.is_available() and not args.cpu_only
    print(f"torch {torch.__version__}, python {platform.python_version()}, "
          f"{os.cpu_count()} logical CPUs, torch CPU threads {torch.get_num_threads()}")
    if cuda:
        props = torch.cuda.get_device_properties(0)
        print(f"GPU: {props.name}, {props.total_memory / 2**30:.1f} GiB, {props.multi_processor_count} SMs, "
              f"bf16 {'yes' if torch.cuda.is_bf16_supported() else 'no'}")
    else:
        print("GPU: none" + (" (--cpu-only)" if args.cpu_only else " -- only the CPU table will be printed"))
    print(f"net: {args.layers} x {args.hidden}, LayerNorm, GELU; policy and value heads of {CARDS}; "
          f"rates in decisions a second")
    print()

    sizes = [(ENCODING, "encoding"), (WITH_PLAY_STATE, "with play state")]
    device = torch.device("cuda") if cuda else None
    bf16 = cuda and torch.cuda.is_bf16_supported()

    if cuda:
        batches = [1, 64, 512, 4096, 32768, 131072]
        print("1. inference on the GPU, by batch size")
        row("", [f"{b:>12,d}" for b in batches])
        for inputs, name in sizes:
            model = PolicyNet(inputs, args.hidden, args.layers).to(device)
            for precision in (["fp32", "bf16"] if bf16 else ["fp32"]):
                for from_host in (False, True):
                    label = f"{name}, {precision}" + (", from host" if from_host else "")
                    row(label, [cell(gpu_inference(model, inputs, b, device, args.seconds,
                                                   precision == "bf16", from_host)) for b in batches])
            del model
            torch.cuda.empty_cache()
        print("    from host: the inputs leave the host as bytes, are divided by 255 on the card, and the")
        print("    chosen card comes back -- a whole round trip, as an actor would see it.")
        print()

    batches = [1, 64, 512, 4096]
    print("2. inference on the CPU (torch), by batch size")
    row("", [f"{b:>12,d}" for b in batches])
    for inputs, name in sizes:
        model = PolicyNet(inputs, args.hidden, args.layers)
        row(name, [cell(cpu_inference(model, inputs, b, args.seconds)) for b in batches])
    print("    Against SelfPlayThroughputMain's Java pass, one input a call: the gain batching buys on the CPU.")
    print()

    if cuda:
        batches = [1024, 4096, 16384, 65536]
        print("3. training on the GPU (forward, both losses, backward, AdamW), by batch size")
        best = 0.0
        row("", [f"{b:>12,d}" for b in batches])
        for inputs, name in sizes:
            for precision in (["fp32", "bf16"] if bf16 else ["fp32"]):
                model = PolicyNet(inputs, args.hidden, args.layers).to(device)
                rates = [gpu_training(model, inputs, b, device, args.seconds, precision == "bf16")
                         for b in batches]
                best = max(best, max(rates))
                row(f"{name}, {precision}", [cell(r) for r in rates])
                del model
                torch.cuda.empty_cache()
        print(f"    At the best rate one epoch over the v2 corpus (1.2 M decisions) takes "
              f"{1_197_380 / best:.1f} s.")


if __name__ == "__main__":
    main()
