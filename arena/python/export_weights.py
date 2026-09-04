"""Writes the trained belief as a plain array of numbers.

The app should not pull ONNX Runtime's native libraries into the APK to multiply
four matrices, and the arena should not measure a different implementation from
the one that ships. So the model leaves here as `belief.bin`, which
`dev.skatklar.demo.belief.BeliefNet` reads with a DataInputStream and nothing else.

    py arena/python/export_weights.py --model=belief-model

Writes `belief.bin` beside `belief.pt`, and `belief-parity.bin` beside that:
the same cases `fixtures.npz` holds, in a format a phone can read without a
NumPy. The app and the server replay them before they let the weights play --
see `dev.skatklar.demo.belief.BeliefParity` for why a header check is not enough.
`model.json` carries the shape and the encoding version and is written by
train_belief.py.

Format, all big-endian because that is what Java reads without ceremony:

    magic "SKBW", format, encoding version, inputs, hidden, layers, outputs
    per trunk block:  linear weight (out x in, row major), bias, gamma, beta
    head:             linear weight (out x in), bias

Torch stores a Linear's weight as (out_features, in_features) and computes
x @ W.T, so row-major (out, in) is exactly one dot product per output. Nothing is
transposed on either side of this file, which is the sort of thing that is easier
to keep true than to debug.
"""

import argparse
import json
import pathlib
import struct
import sys

import numpy as np
import torch

MAGIC = 0x534B4257
FORMAT = 1


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="belief-model",
                        help="directory holding belief.pt and model.json")
    args = parser.parse_args()

    directory = pathlib.Path(args.model)
    descriptor = json.loads((directory / "model.json").read_text())
    inputs = descriptor["inputs"]
    hidden = descriptor["hidden"]
    layers = descriptor["layers"]
    outputs = descriptor["cards"] * len(descriptor["classes"])

    state = torch.load(directory / "belief.pt", map_location="cpu")

    out = directory / "belief.bin"
    with open(out, "wb") as f:
        f.write(struct.pack(">7i", MAGIC, FORMAT, descriptor["encoding_version"],
                            inputs, hidden, layers, outputs))
        width = inputs
        for block in range(layers):
            # Each block is Linear, LayerNorm, GELU, Dropout -- four modules, so
            # the linear of block n sits at 4n and its norm at 4n + 1.
            linear, norm = f"trunk.{4 * block}", f"trunk.{4 * block + 1}"
            write(f, state[f"{linear}.weight"], (hidden, width))
            write(f, state[f"{linear}.bias"], (hidden,))
            write(f, state[f"{norm}.weight"], (hidden,))
            write(f, state[f"{norm}.bias"], (hidden,))
            width = hidden
        write(f, state["head.weight"], (outputs, width))
        write(f, state["head.bias"], (outputs,))

    cases = write_parity(directory, outputs)

    print(f"{out}: {out.stat().st_size / 1024 / 1024:.2f} MB, "
          f"{inputs}x{hidden}x{layers} -> {outputs}")
    if cases:
        print(f"{cases}: {cases.stat().st_size / 1024:.0f} KB of the trainer's own answers")
    print("Now run the arena; BeliefPlayers prefers belief.bin and replays "
          "fixtures.npz through it before letting it play.")
    print("To ship it: copy belief.bin and belief-parity.bin into "
          "app/src/main/assets/ and beside the server's belief-model/.")


def write_parity(directory, outputs):
    """The fixtures again, in the format the shipped loaders read.

    `fixtures.npz` is a zip of .npy files, which is a fine thing for the trainer
    and the arena and a poor thing for an Android asset: reading it needs a
    parser nobody wants on a phone to check sixteen rows. So the same numbers go
    out again, flat and big-endian, as three ints and two blocks of float32.

    Absent fixtures are tolerated -- an older model directory is still a model --
    but then nothing downstream can vouch for the weights, and the loaders say so.
    """
    source = directory / "fixtures.npz"
    if not source.is_file():
        print(f"no {source}; the app and server will load the weights unverified")
        return None
    arrays = np.load(source)
    features = np.ascontiguousarray(arrays["features"], dtype=np.float32)
    logits = np.ascontiguousarray(arrays["logits"], dtype=np.float32)
    logits = logits.reshape(features.shape[0], -1)
    if logits.shape[1] != outputs:
        raise SystemExit(f"fixtures hold {logits.shape[1]} outputs, the model has {outputs}")

    out = directory / "belief-parity.bin"
    with open(out, "wb") as f:
        f.write(struct.pack(">3i", features.shape[0], features.shape[1], logits.shape[1]))
        f.write(features.astype(">f4").tobytes())
        f.write(logits.astype(">f4").tobytes())
    return out


def write(handle, tensor, shape):
    """One tensor, shape checked, big-endian float32.

    The shape assertion is the whole reason this is a function. A transposed
    weight matrix still has the right number of elements, still loads, and still
    produces confident nonsense -- and the parity fixtures would catch it, but
    only after someone has spent an evening wondering why the player got worse.
    """
    values = tensor.detach().cpu().numpy()
    if tuple(values.shape) != shape:
        raise SystemExit(f"expected {shape}, found {tuple(values.shape)}")
    handle.write(np.ascontiguousarray(values, dtype=np.float32).astype(">f4").tobytes())


if __name__ == "__main__":
    sys.exit(main())
