"""Reading the belief corpus, and forgetting on the way in.

Pure numpy on purpose. The half of the pipeline that can be wrong in a silent,
expensive way -- a field read at the wrong offset, a validation split that leaks,
a memory simulation that does not match the player it is meant to model -- is all
here, and none of it needs a GPU or a deep learning framework to be checked. The
torch side is a few dozen conventional lines on top.

The layout is never hard-coded. It is read from the ``encoding-vN.json`` the
exporter writes beside the shards, which is the same file the Java side declares
it from; a mismatch is a loud failure rather than a quietly shifted feature.
"""

from __future__ import annotations

import json
import pathlib

import numpy as np

LABEL_BYTES = 32
MASK_BYTES = 32
SEATING_BYTES = 3
BOARD_BYTES = 4


class Corpus:
    """One directory of shards, plus the specification that describes them."""

    def __init__(self, directory):
        self.directory = pathlib.Path(directory)
        specs = sorted(self.directory.glob("encoding-v*.json"))
        if not specs:
            raise FileNotFoundError(
                f"no encoding-v*.json in {self.directory}: that file is written "
                "beside the shards and is what says how to read them")
        if len(specs) > 1:
            raise ValueError(f"two encodings in one corpus: {[s.name for s in specs]}")
        self.spec = json.loads(specs[0].read_text())
        self.version = self.spec["version"]
        self.size = self.spec["size"]
        self.scale = self.spec["scale"]
        self.fields = {f["name"]: (f["offset"], f["width"]) for f in self.spec["fields"]}
        # The spec is the authority; the arithmetic below is the cross-check.
        # If the two ever disagree, the file was written by a different encoder
        # than this reader believes in, and reading it would misalign every row.
        derived = self.size + LABEL_BYTES + MASK_BYTES + SEATING_BYTES + BOARD_BYTES
        self.record_bytes = self.spec.get("record_bytes", derived)
        if self.record_bytes != derived:
            raise ValueError(
                f"the corpus says {self.record_bytes} bytes a record, this reader "
                f"makes it {derived}: encoding v{self.version} is not what this "
                "trainer was written against")

        widths = sum(w for _, w in self.fields.values())
        if widths != self.size:
            raise ValueError(f"fields cover {widths} of {self.size} inputs")

        self.shards = sorted(self.directory.glob("shard-*.bin"))
        if not self.shards:
            raise FileNotFoundError(f"no shards in {self.directory}")

    def field(self, name):
        """Offset and width of a named field, by name rather than by number."""
        if name not in self.fields:
            raise KeyError(f"{name} is not in encoding v{self.version}")
        return self.fields[name]

    def slice(self, x, name):
        offset, width = self.field(name)
        return x[..., offset:offset + width]

    def load(self):
        """Every shard, as one block. Returns features, target, mask, board."""
        raw = np.concatenate([
            np.fromfile(shard, dtype=np.uint8).reshape(-1, self.record_bytes)
            for shard in self.shards])
        size = self.size
        features = raw[:, :size].astype(np.float32) / self.scale
        target = raw[:, size:size + LABEL_BYTES].astype(np.int64)
        mask = raw[:, size + LABEL_BYTES:size + LABEL_BYTES + MASK_BYTES].astype(np.float32)
        board_at = size + LABEL_BYTES + MASK_BYTES + SEATING_BYTES
        board = raw[:, board_at:board_at + BOARD_BYTES].astype(np.uint32)
        board = (board[:, 0] | (board[:, 1] << 8)
                 | (board[:, 2] << 16) | (board[:, 3] << 24))
        return features, target, mask, board

    def split_by_board(self, board, fraction=0.1, seed=0):
        """
        Indices for training and validation, split on whole boards.

        A board yields about thirty records off the same thirty-two cards, so a
        split by record would put near-copies of training rows into validation
        and report a number that says nothing about an unseen deal. This is the
        one place that can go wrong without ever looking wrong.
        """
        boards = np.unique(board)
        rng = np.random.default_rng(seed)
        held = set(rng.choice(boards, size=max(1, int(len(boards) * fraction)),
                              replace=False).tolist())
        is_val = np.array([b in held for b in board])
        return np.where(~is_val)[0], np.where(is_val)[0]


class Forgetting:
    """
    Turns a perfectly remembered position into one a given player would hold.

    The exporter records everything, once, at full memory. Forgetting belongs
    here rather than in the corpus for two reasons: one generated corpus then
    serves every personality, and the dropout distribution stays a knob to tune
    instead of a property baked into a terabyte of files.

    What it must match is {@code Personality}: observations are dropped
    independently with probability ``1 - memory``, and the blocks that carry
    counts survive at ``sqrt(memory)``, because counting is the one piece of
    bookkeeping the game trains and it decays far more slowly than recall of
    individual cards.

    Every drop clears a presence bit as well as the block. Zeroing the bidding
    block without clearing its bit does not say "I forgot the auction", it says
    "nobody bid" -- a false fact rather than an absent one, and a model told that
    nobody bid will place the jacks somewhere they are not.
    """

    #: The four shipped levels, and what each remembers.
    LADDER = (0.30, 0.60, 0.85, 1.00)

    def __init__(self, corpus, levels=LADDER, seed=0):
        self.corpus = corpus
        self.levels = np.asarray(levels, dtype=np.float32)
        self.rng = np.random.default_rng(seed)

    def apply(self, x):
        """A forgotten copy of a batch, one memory level drawn per example."""
        out = x.copy()
        memory = self.rng.choice(self.levels, size=len(out))[:, None]

        for block in ("played_by_me", "played_by_left", "played_by_right"):
            offset, width = self.corpus.field(block)
            kept = self.rng.random((len(out), width)) < memory
            out[:, offset:offset + width] *= kept

        # The presence bit follows the block: nothing remembered, nothing claimed.
        remembered = np.zeros(len(out), dtype=bool)
        for block in ("played_by_me", "played_by_left", "played_by_right"):
            offset, width = self.corpus.field(block)
            remembered |= out[:, offset:offset + width].any(axis=1)
        present, _ = self.corpus.field("played_present")
        out[:, present] = np.where(remembered, out[:, present], 0)

        self._drop_block(out, "bids_by_seat", "bidding_present", memory[:, 0])
        # The auction is the first thing to go, and the count the last.
        self._drop_block(out, "trumps_out", "counts_present", np.sqrt(memory[:, 0]),
                         also=("trumps_mine", "jacks_out"))
        return out, memory[:, 0]

    def _drop_block(self, out, block, presence, survival, also=()):
        keep = self.rng.random(len(out)) < survival
        for name in (block,) + tuple(also):
            offset, width = self.corpus.field(name)
            out[:, offset:offset + width] *= keep[:, None]
        present, _ = self.corpus.field(presence)
        out[:, present] *= keep


def uniform_baseline(corpus, x):
    """
    What the current sampler already believes, as probabilities.

    :class:`WorldSampler` draws uniformly over the arrangements it considers
    possible, which for a single card means: proportional to how many places are
    left in each hand and in the skat. That is not a strawman, it is the player
    the belief model has to beat, and it is already right far more often than
    chance -- so it is the only baseline worth quoting.

    Returns an array of shape ``(records, 3)`` in the label's class order:
    left, right, skat.
    """
    left = corpus.slice(x, "cards_left")[:, 1] * 10
    right = corpus.slice(x, "cards_left")[:, 2] * 10
    # Two cards are buried, unless this seat is one of the two that knows them:
    # the declarer who discarded them, or rearhand whose push became the skat.
    knows_skat = ((corpus.slice(x, "my_discard_present")[:, 0] > 0.5)
                  | (corpus.slice(x, "pushed_to_skat")[:, 0] > 0.5))
    skat = np.where(knows_skat, 0.0, 2.0)
    slots = np.stack([left, right, skat], axis=1)
    total = slots.sum(axis=1, keepdims=True)
    return slots / np.maximum(total, 1e-6)


def score(probabilities, target, mask):
    """Accuracy and mean negative log likelihood over the masked cards only."""
    picked = probabilities.argmax(axis=-1)
    hit = (picked == target) & (mask > 0.5)
    accuracy = hit.sum() / max(mask.sum(), 1)
    chosen = np.take_along_axis(probabilities, target[..., None], axis=-1)[..., 0]
    loss = -np.log(np.maximum(chosen, 1e-9))
    return float(accuracy), float((loss * mask).sum() / max(mask.sum(), 1))
