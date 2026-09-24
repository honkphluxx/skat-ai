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


# Which rule decides a board's side in split_by_board. Written into every
# model.json and checked by eval_belief.py, because a model can only be scored
# honestly on a held-out set it was actually held out of: score a model trained
# under one rule against a set drawn by another and you are grading it on its
# own training data. That happened once, on 2026-09-19, and cost an afternoon
# reading a five-point gap that was not there. Bump this string whenever the
# rule changes, so the mismatch is loud instead of invisible.
SPLIT_RULE = "splitmix64-v1"


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

    def load(self, limit=None, packed=False):
        """Every shard, as one block. Returns features, target, mask, board.

        With ``packed``, the features come back as the bytes on disk (uint8,
        unscaled: divide by ``scale`` to get the floats) and the labels as
        int8, a quarter of the memory. A five-million-record night is 6 GB of
        float32 features and 1.6 GB of bytes; a caller that unpacks one batch
        at a time (:func:`unpack`) never needs the floats all at once.

        With ``limit``, only as many whole shards as it takes to reach that
        many records, then truncated: a bounded sample from the front of the
        corpus, for a check that does not need all of it. The records of one
        shard are consecutive boards, so a sample of two shards is a sample of
        the same games the rest are, not a slice of one.
        """
        blocks = []
        loaded = 0
        for shard in self.shards:
            blocks.append(np.fromfile(shard, dtype=np.uint8).reshape(-1, self.record_bytes))
            loaded += len(blocks[-1])
            if limit is not None and loaded >= limit:
                break
        raw = np.concatenate(blocks)
        if limit is not None:
            raw = raw[:limit]
        size = self.size
        if packed:
            features = np.ascontiguousarray(raw[:, :size])
            target = raw[:, size:size + LABEL_BYTES].astype(np.int8)
        else:
            features = raw[:, :size].astype(np.float32) / self.scale
            target = raw[:, size:size + LABEL_BYTES].astype(np.int64)
        mask = raw[:, size + LABEL_BYTES:size + LABEL_BYTES + MASK_BYTES].astype(np.float32)
        board_at = size + LABEL_BYTES + MASK_BYTES + SEATING_BYTES
        board = raw[:, board_at:board_at + BOARD_BYTES].astype(np.uint32)
        board = (board[:, 0] | (board[:, 1] << 8)
                 | (board[:, 2] << 16) | (board[:, 3] << 24))
        return features, target, mask, board

    def unpack(self, features):
        """Packed bytes (or a batch of them) as the floats the model reads."""
        if features.dtype == np.float32:
            return features
        return features.astype(np.float32) / self.scale

    def split_by_board(self, board, fraction=0.1, seed=0):
        """
        Indices for training and validation, split on whole boards.

        A board yields about thirty records off the same thirty-two cards, so a
        split by record would put near-copies of training rows into validation
        and report a number that says nothing about an unseen deal. This is the
        one place that can go wrong without ever looking wrong.

        The side a board lands on is a function of its id and the seed alone,
        and of nothing else -- not of which other boards are in the corpus, not
        of how many there are, not of the order they were read in. That is
        worth more than it sounds. Drawing the held-out tenth from whichever
        boards happen to be present, which is what this did until 2026-09-19,
        makes the split a property of the *corpus* rather than of the deal: the
        same board is held out of one corpus and trained on in another, so a
        model trained on a mixed corpus and then scored on the held-out tenth of
        one of its parts is being scored on deals it has already seen. It reads
        as a model that generalises unusually well, and it is the bug this
        file's own docstring warns about, arriving through the back door.

        B2 step 3 is entirely made of such comparisons -- one model against
        several corpora, several models against one -- so the split has to mean
        the same thing everywhere. The cost of the change is that a
        val_accuracy stored in a model.json written before this date was
        measured on a different tenth and is not comparable with one measured
        after it; recompute it with eval_belief.py rather than quoting it.

        SplitMix64's finalising mix, which is cheap, vectorises, and spreads
        adjacent ids -- and the ids here are adjacent, since a corpus is one
        seed and consecutive board indices run through Seeds.mix.
        """
        # Board ids arrive as a signed 32-bit identity; take the low word so
        # the mixing below is defined rather than sign-dependent.
        z = (board.astype(np.int64) & 0xFFFFFFFF).astype(np.uint64)
        # The seed's contribution is folded in Python, where the product is
        # exact, then reduced -- numpy warns on a scalar uint64 multiply that
        # wraps, and a warning printed once a night is a warning nobody reads.
        z = z + np.uint64((seed * 0x9E3779B97F4A7C15) & 0xFFFFFFFFFFFFFFFF)
        z ^= z >> np.uint64(30)
        z = z * np.uint64(0xBF58476D1CE4E5B9)
        z ^= z >> np.uint64(27)
        z = z * np.uint64(0x94D049BB133111EB)
        z ^= z >> np.uint64(31)
        cut = np.uint64(round(fraction * (1 << 32)))
        is_val = (z >> np.uint64(32)) < cut
        # A corpus small enough to round down to nothing still needs something
        # held out, or the trainer evaluates on an empty set and reports a
        # perfect score. Take the board nearest the cut rather than the first,
        # which keeps the choice a function of the ids.
        if not is_val.any():
            only = np.unique(board)[0]
            is_val = board == only
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


# The auction, spelled out. Solinas, Rebstock and Buro (AAAI 2019), whose
# card-location net this belief descends from, do not hand their net a bid as a
# number: each opponent's highest bid arrives as a *type* (which game the bid
# implies) and a *magnitude* bucketed so that bids sharing a multiplier fall
# together (18-24, 27-36, 40-48, 50-72, over 72), "because the bid multiplier
# is a strong predictor for the locations of jacks in particular". Ours arrives
# as the value over a hundred, quantised to a byte, and whether three layers of
# GELU can recover "divisible by eleven, so spades, so a multiplier of three,
# so two jacks" from 0.13 is the question this block lets a training run ask.
#
# Derived, not exported: the corpus already holds the value, so the same shards
# serve both the control and the candidate, and it is computed on the batch
# *after* forgetting, so a forgotten auction stays forgotten here too. Per
# relative seat (me, left, right), in this order:
#
#   1  no bid from this seat (passed before saying a value)
#   5  magnitude: 18-24, 27-36, 40-48, 50-72, >72
#   6  a base value that divides the bid: diamonds 9, hearts 10, spades 11,
#      clubs 12, grand 24, or a Null price (23, 35, 46, 59) -- several may be
#      set, which is the ambiguity the paper notes, left to the net
#   5  the smallest suit multiplier the bid admits: 2, 3, 4, 5, 6 and more
#
# Seventeen a seat, fifty-one in all, appended after the encoded inputs. The
# Java encoder has no counterpart yet, on purpose: this is measured held out
# by role first, and only a net that pays there earns the port.

BID_BUCKETS = ((18, 24), (27, 36), (40, 48), (50, 72), (73, 10 ** 9))
BID_BASES = (9, 10, 11, 12, 24)
NULL_PRICES = (23, 35, 46, 59)
BID_STRUCTURE_PER_SEAT = 1 + len(BID_BUCKETS) + len(BID_BASES) + 1 + 5
BID_STRUCTURE_WIDTH = 3 * BID_STRUCTURE_PER_SEAT


def bid_structure(corpus, x):
    """The fifty-one derived auction inputs for a batch of unpacked features."""
    bids = np.rint(corpus.slice(x, "bids_by_seat") * 100).astype(np.int64)
    present = corpus.slice(x, "bidding_present")[:, 0] > 0.5
    out = np.zeros((len(x), BID_STRUCTURE_WIDTH), dtype=np.float32)
    for seat in range(3):
        b = bids[:, seat]
        at = seat * BID_STRUCTURE_PER_SEAT
        out[:, at] = (b == 0)
        at += 1
        for lo, hi in BID_BUCKETS:
            out[:, at] = (b >= lo) & (b <= hi)
            at += 1
        for base in BID_BASES:
            out[:, at] = (b > 0) & (b % base == 0)
            at += 1
        out[:, at] = np.isin(b, NULL_PRICES)
        at += 1
        smallest = np.full(len(x), 0, dtype=np.int64)
        for base in (12, 11, 10, 9):
            divides = (b > 0) & (b % base == 0)
            smallest = np.where(divides, b // base, smallest)
        for slot, multiplier in enumerate((2, 3, 4, 5)):
            out[:, at + slot] = smallest == multiplier
        out[:, at + 4] = smallest >= 6
    out *= present[:, None]
    return out


def with_bid_structure(corpus, x):
    """The batch with the derived auction inputs appended."""
    return np.concatenate([x, bid_structure(corpus, x)], axis=1)


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


def is_declarer(corpus, x):
    """
    Which records were observed from the declarer's chair.

    The ``declarer`` field is one-hot over me / left / right / nobody, relative
    to the observing seat, so its first slot is the answer. Works on packed
    bytes and on unpacked floats alike: a one-hot is the top or the bottom of
    the range either way, and the threshold is half of it.
    """
    declarer = corpus.slice(x, "declarer")
    top = corpus.scale if x.dtype != np.float32 else 1.0
    return declarer[:, 0] > top / 2


def score(probabilities, target, mask):
    """Accuracy and mean negative log likelihood over the masked cards only."""
    picked = probabilities.argmax(axis=-1)
    hit = (picked == target) & (mask > 0.5)
    accuracy = hit.sum() / max(mask.sum(), 1)
    chosen = np.take_along_axis(probabilities, target[..., None], axis=-1)[..., 0]
    loss = -np.log(np.maximum(chosen, 1e-9))
    return float(accuracy), float((loss * mask).sum() / max(mask.sum(), 1))
