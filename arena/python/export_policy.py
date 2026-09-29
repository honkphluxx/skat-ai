"""Writes the trained student as a plain array of numbers (plan 2.10, I4).

    python3 arena/python/export_policy.py --model=policy-model

Writes `policy.bin` beside `policy.pt`, and `policy-parity.bin` beside that:
the trainer's own answers for sixteen held-out inputs, which
`dev.skatklar.demo.belief.PolicyNet` replays before it lets the weights play.

The layout is the belief weights' (export_weights.py), read by the same Java
loader, with its own magic so neither file can be loaded as the other:

    magic "SKPW", format 2, encoding version, inputs, hidden, layers, outputs = 64,
    contracts trained (a bit per Contract ordinal)
    per trunk block:  linear weight (out x in, row major), bias, gamma, beta
    head:             the policy head's 32 rows, then the value head's 32

One head of 64 rather than two of 32, because a head is only a Linear: the two
stacked are exactly one matrix, and the Java side needs nothing new to read it.
"""

import argparse
import json
import pathlib
import struct
import sys

import numpy as np
import torch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from export_weights import write  # noqa: E402  (the shape-checked tensor writer)

MAGIC = 0x534B5057
FORMAT = 2
CONTRACTS = ("Diamonds", "Hearts", "Spades", "Clubs", "Grand", "Null", "Ramsch")


def contracts_of(directory, descriptor, root):
    """Which contracts the student was trained on, from model.json or its corpus."""
    if "contracts_trained" in descriptor:
        return descriptor["contracts_trained"]
    # Models trained before train_policy.py recorded it: read the corpus it
    # names. Every contract that appears in it at all; the teacher corpus of
    # 2026-09-28 holds the four suits and Grand and no Null.
    from teacher_data import TeacherCorpus
    for base in (root, directory.parent, pathlib.Path.cwd()):
        data = base / descriptor["trained_on"]
        if (data / "teacher-format.json").exists():
            records = TeacherCorpus(data).load()
            mask = 0
            for c in np.unique(records.contract):
                mask |= 1 << int(c)
            print(f"contracts read from {data}")
            return mask
    raise SystemExit(f"model.json does not say which contracts it saw, and the corpus "
                     f"{descriptor['trained_on']} is not beside it")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="policy-model",
                        help="directory holding policy.pt, model.json and fixtures.npz")
    args = parser.parse_args()

    directory = pathlib.Path(args.model)
    root = pathlib.Path(__file__).resolve().parents[2]
    descriptor = json.loads((directory / "model.json").read_text())
    if descriptor.get("kind") != "policy":
        raise SystemExit(f"{directory} does not hold a policy model")
    inputs, hidden, layers = descriptor["inputs"], descriptor["hidden"], descriptor["layers"]
    cards = descriptor["cards"]
    outputs = 2 * cards
    contracts = contracts_of(directory, descriptor, root)
    state = torch.load(directory / "policy.pt", map_location="cpu")

    out = directory / "policy.bin"
    with open(out, "wb") as f:
        f.write(struct.pack(">8i", MAGIC, FORMAT, descriptor["encoding_version"],
                            inputs, hidden, layers, outputs, contracts))
        width = inputs
        for block in range(layers):
            linear, norm = f"trunk.{4 * block}", f"trunk.{4 * block + 1}"
            write(f, state[f"{linear}.weight"], (hidden, width))
            write(f, state[f"{linear}.bias"], (hidden,))
            write(f, state[f"{norm}.weight"], (hidden,))
            write(f, state[f"{norm}.bias"], (hidden,))
            width = hidden
        head_weight = torch.cat([state["policy.weight"], state["value.weight"]], dim=0)
        head_bias = torch.cat([state["policy.bias"], state["value.bias"]], dim=0)
        write(f, head_weight, (outputs, width))
        write(f, head_bias, (outputs,))

    fixtures = np.load(directory / "fixtures.npz")
    features = np.ascontiguousarray(fixtures["features"], dtype=np.float32)
    logits = np.concatenate([fixtures["policy"], fixtures["value"]], axis=1).astype(np.float32)
    if logits.shape[1] != outputs or features.shape[1] != inputs:
        raise SystemExit(f"fixtures are {features.shape} -> {logits.shape}, the model {inputs} -> {outputs}")
    parity = directory / "policy-parity.bin"
    with open(parity, "wb") as f:
        f.write(struct.pack(">3i", features.shape[0], features.shape[1], logits.shape[1]))
        f.write(features.astype(">f4").tobytes())
        f.write(logits.astype(">f4").tobytes())

    held = ", ".join(n for at, n in enumerate(CONTRACTS) if contracts >> at & 1)
    print(f"{out}: {out.stat().st_size / 1024 / 1024:.2f} MB, {inputs}x{hidden}x{layers} -> {outputs}")
    print(f"  trained on: {held}; every other contract is played by the search")
    print(f"{parity}: {features.shape[0]} of the trainer's own answers")


if __name__ == "__main__":
    sys.exit(main())
