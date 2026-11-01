"""Reading the all-cards self-play corpus (tools/allcards-export.sh), and checking it.

    python3 allcards_data.py ../../allcards-data/r1-k8          the checks
    python3 allcards_data.py ../../allcards-data/r1-k8 --limit=200000

One record is one decision of the student in self-play with more than one
legal card (AllCardsExportMain): the position as the deciding seat saw it
(the belief encoding, the same 306 bytes the student reads), the legal cards,
the card the main line played, and for every legal card what k play-outs of
it on the table's real cards gave -- wins, card points and game value from
the decider's side, summed over the k. On disk the labels sit in slots, the
legal cards in card-index order; :class:`Records` lays them out by card index
(n, 32), zero off the legal cards, which is what the trainer and
suit_permutation.per_card take.

The layout is read from ``allcards-format.json`` and checked field by field
against what this reader was written for; the features' from
``encoding-vN.json``. Beside each shard the exporter writes its label totals by
card, computed in Java from the bytes it wrote; the first check here is that
this reader, laying the slots out its own way, adds up to the same numbers.
Pure numpy: the part that can be silently wrong is all here.
"""

from __future__ import annotations

import json
import pathlib
import sys

import numpy as np

from belief_data import Corpus

CARDS = 32
SLOTS = 10
FORMAT = 1
RECORD_BYTES = 396
#: The record layout this reader was written against.
EXPECTED = {
    "encoding": (0, 306), "legal": (306, 4), "chosen": (310, 1), "flags": (311, 1), "trick": (312, 1),
    "contract": (313, 1), "k": (314, 1), "place": (315, 1), "main_won": (316, 1), "main_points": (317, 1),
    "wins": (318, 10), "points": (328, 20), "value": (348, 40), "board": (388, 4), "seed": (392, 4),
}
DRAWN, DECLARER = 1, 2
#: Suit and grand games only (Contract ordinals 0-4).
TRUMP_GAMES = 5


def split_by_board(board, fraction=0.1, seed=0):
    """The belief corpus's split rule on the board ids: a board is held out whole, by its id alone."""
    return Corpus.split_by_board(None, board, fraction, seed)


class AllCardsCorpus:
    """One directory of all-cards shards and the files that describe them."""

    def __init__(self, directory):
        self.directory = pathlib.Path(directory)
        layout_file = self.directory / "allcards-format.json"
        if not layout_file.exists():
            raise FileNotFoundError(f"no allcards-format.json in {self.directory}")
        self.layout = json.loads(layout_file.read_text())
        if self.layout["format"] != FORMAT or self.layout["record_bytes"] != RECORD_BYTES:
            raise ValueError(f"format {self.layout['format']} of {self.layout['record_bytes']} bytes, this reader "
                             f"reads format {FORMAT} of {RECORD_BYTES}")
        for name, (offset, width) in EXPECTED.items():
            got = tuple(self.layout["fields"][name][:2])
            if got != (offset, width):
                raise ValueError(f"field {name} at {got}, this reader expects {(offset, width)}")
        self.k = int(self.layout["k"])
        self.seed = int(self.layout["seed"])

        specs = sorted(self.directory.glob("encoding-v*.json"))
        if len(specs) != 1:
            raise ValueError(f"expected one encoding-v*.json in {self.directory}, found {len(specs)}")
        self.spec = json.loads(specs[0].read_text())
        self.version = self.spec["version"]
        self.size = self.spec["size"]
        self.scale = self.spec["scale"]
        self.fields = {f["name"]: (f["offset"], f["width"]) for f in self.spec["fields"]}
        if self.version != self.layout["encoding_version"] or self.size != EXPECTED["encoding"][1]:
            raise ValueError("the features in the records are not the encoding beside them")
        self.shards = sorted(self.directory.glob("allcards-s*-*.bin"))
        if not self.shards:
            raise FileNotFoundError(f"no allcards-s*-*.bin shards in {self.directory}")

    def totals(self, shard):
        """The exporter's own label totals for a shard."""
        return json.loads(shard.with_suffix(".json").read_text())

    def raw(self, shard):
        data = np.fromfile(shard, dtype=np.uint8)
        if len(data) % RECORD_BYTES:
            raise ValueError(f"{shard.name} is not a whole number of records")
        return data.reshape(-1, RECORD_BYTES)

    def load(self, limit=None):
        """Every shard, or the first ``limit`` records, as :class:`Records`."""
        blocks, loaded = [], 0
        for shard in self.shards:
            blocks.append(self.raw(shard))
            loaded += len(blocks[-1])
            if limit is not None and loaded >= limit:
                break
        raw = np.concatenate(blocks)
        return Records(raw[:limit] if limit is not None else raw)

    def unpack(self, features):
        """Packed feature bytes as the floats a net reads."""
        return features.astype(np.float32) / self.scale


def by_card(slots, legal):
    """Slot values (n, SLOTS) laid out by card index (n, 32): slot j is the j-th legal card counting up."""
    rank = np.cumsum(legal, axis=1) - 1                                # the slot of each legal card
    picked = np.take_along_axis(slots, np.clip(rank, 0, SLOTS - 1), axis=1)
    return np.where(legal, picked, 0)


class Records:
    """The fields of a block of raw records, as arrays; labels by card index."""

    def __init__(self, raw):
        self.raw = raw
        self.n = len(raw)
        self.features = np.ascontiguousarray(raw[:, 0:306])
        mask = np.ascontiguousarray(raw[:, 306:310]).view("<u4")[:, 0]
        self.legal = ((mask[:, None] >> np.arange(CARDS, dtype=np.uint32)) & 1).astype(bool)
        self.chosen = raw[:, 310].astype(np.int64)
        self.drawn = (raw[:, 311] & DRAWN).astype(bool)
        self.declarer = (raw[:, 311] & DECLARER).astype(bool)
        self.trick = raw[:, 312].astype(np.int64)
        self.contract = raw[:, 313].astype(np.int64)
        self.k = raw[:, 314].astype(np.int64)
        self.place = raw[:, 315].astype(np.int64)
        self.main_won = raw[:, 316].astype(bool)
        self.main_points = raw[:, 317].astype(np.int64)
        self.wins_slots = raw[:, 318:328].astype(np.int64)
        self.points_slots = np.ascontiguousarray(raw[:, 328:348]).view("<u2").astype(np.int64)
        self.value_slots = np.ascontiguousarray(raw[:, 348:388]).view("<i4").astype(np.int64)
        self.board = np.ascontiguousarray(raw[:, 388:392]).view("<u4")[:, 0].astype(np.int64)
        self.seed = np.ascontiguousarray(raw[:, 392:396]).view("<u4")[:, 0].astype(np.int64)
        self.wins = by_card(self.wins_slots, self.legal)
        self.points = by_card(self.points_slots, self.legal)
        self.value = by_card(self.value_slots, self.legal)

    def __len__(self):
        return self.n

    def mean_value(self):
        """Each legal card's game value a play-out, from the decider's side (0 off the legal cards)."""
        return self.value / np.maximum(self.k, 1)[:, None]

    def win_share(self):
        """Each legal card's share of play-outs won: the value head's target."""
        return self.wins / np.maximum(self.k, 1)[:, None]

    def rank_key(self):
        """One number a card that orders as the exporter ranks: game value, then wins, then points (-inf off legal)."""
        key = (self.value * 1_000_000 + self.wins * 1_000 + self.points).astype(np.float64)
        return np.where(self.legal, key, -np.inf)

    def best(self):
        """The card the labels rank first; the earliest of a full tie."""
        return self.rank_key().argmax(axis=1)

    def is_best(self, cards):
        """Whether each record's card is the labels' best or tied with it in full."""
        key = self.rank_key()
        rows = np.arange(self.n)
        return key[rows, cards] == key.max(axis=1)


def run_checks(corpus, limit=None, verbose=True):
    """Every check; True when all pass. Each shard against its own totals, then the records as a whole."""
    ok = True

    def check(name, passed, detail=""):
        nonlocal ok
        ok &= bool(passed)
        if verbose or not passed:
            print(f"  {'ok  ' if passed else 'FAIL'}  {name}{('  ' + detail) if detail else ''}")

    blocks = []
    for shard in corpus.shards:
        r = Records(corpus.raw(shard))
        t = corpus.totals(shard)
        same = (t["records"] == len(r)
                and t["wins"] == r.wins.sum(axis=0).tolist()
                and t["points"] == r.points.sum(axis=0).tolist()
                and t["value"] == r.value.sum(axis=0).tolist()
                and t["chosen"] == np.bincount(r.chosen, minlength=CARDS).tolist())
        check(f"{shard.name}: the labels by card add up to the exporter's own totals", same,
              f"{len(r):,} records")
        inside = (r.board >= t["boards"][0]).all() and (r.board < t["boards"][1]).all()
        check(f"{shard.name}: every board inside the shard's range {t['boards']}", inside)
        blocks.append(r.raw)
        if limit is not None and sum(len(b) for b in blocks) >= limit:
            break
    r = Records(np.concatenate(blocks)[:limit] if limit else np.concatenate(blocks))
    count = r.legal.sum(axis=1)
    check("2 to 10 legal cards a record", ((count >= 2) & (count <= SLOTS)).all())
    check("the main-line card is legal", r.legal[np.arange(len(r)), r.chosen].all())
    slot = np.arange(SLOTS)[None, :]
    unused = slot >= count[:, None]
    check("slots past the legal cards are empty",
          ((r.wins_slots[unused] == 0).all() and (r.points_slots[unused] == 0).all()
           and (r.value_slots[unused] == 0).all()))
    check("k as the format says", (r.k == corpus.k).all(), f"k {corpus.k}")
    check("wins at most k", (r.wins <= r.k[:, None]).all())
    check("card points at most 120 a play-out", (r.points <= 120 * r.k[:, None]).all())
    check("every legal card's game value is a game's (not zero)", (r.value[r.legal] != 0).mean() > 0.95,
          f"{(r.value[r.legal] != 0).mean():.1%} non-zero")
    check("a card that won every play-out has a positive value, one that lost every play-out a negative one",
          ((r.value[r.legal & (r.wins == r.k[:, None])] > 0).mean() > 0.98)
          and ((r.value[r.legal & (r.wins == 0)] < 0).mean() > 0.98))
    check("tricks 1-9, places 0-2, suit and grand games",
          ((r.trick >= 1) & (r.trick <= 9)).all() and ((r.place >= 0) & (r.place <= 2)).all()
          and (r.contract < TRUMP_GAMES).all())
    check("the board seed is the format's", (r.seed == corpus.seed).all(), f"seed {corpus.seed}")
    hand_offset, _ = corpus.fields["my_hand"]
    hand = r.features[:, hand_offset:hand_offset + CARDS] > 0
    check("the legal cards are in the decider's hand", (hand | ~r.legal).all())
    train, val = split_by_board(r.board, 0.1)
    overlap = np.intersect1d(np.unique(r.board[train]), np.unique(r.board[val]))
    check("the held-out split keeps boards whole", len(overlap) == 0,
          f"{len(np.unique(r.board[val])):,} of {len(np.unique(r.board)):,} boards held out")
    try:
        import suit_permutation
        table = suit_permutation.SuitPermutation(corpus.fields, corpus.size)
        rng = np.random.default_rng(7)
        sample = np.arange(min(len(r), 50_000))
        allowed = table.allowed(r.features[sample], r.contract[sample], "all")
        k = table.draw(allowed, rng)
        features = table.features(r.features[sample], r.contract[sample], k)
        legal = table.per_card(r.legal[sample], r.contract[sample], k)
        value = table.per_card(r.value[sample], r.contract[sample], k)
        chosen = table.card(r.chosen[sample], r.contract[sample], k)
        moved_hand = features[:, hand_offset:hand_offset + CARDS] > 0
        check("permuted by suits, the labels stay on the legal cards and the legal cards in the hand",
              (moved_hand | ~legal).all() and (value[~legal] == 0).all()
              and legal[np.arange(len(sample)), chosen].all()
              and (np.sort(value, axis=1) == np.sort(r.value[sample], axis=1)).all())
    except (ImportError, ValueError) as missing:
        check("suit permutation", False, str(missing))
    if verbose:
        report(r)
    return ok


def report(r):
    """What the labels say, by trick: how often the main line's top card is the labels' best."""
    best = r.best()
    played_best = r.is_best(r.chosen)
    print(f"\n  {len(r):,} records, {len(np.unique(r.board)):,} boards; declarer {r.declarer.mean():.1%}, "
          f"main-line card drawn {r.drawn.mean():.1%}")
    print("  trick  records  legal  mean value  played is best  played's shortfall (game value a play-out; a tie counts as best)")
    mean = r.mean_value()
    rows = np.arange(len(r))
    for t in range(1, 10):
        s = r.trick == t
        if not s.any():
            continue
        shortfall = mean[rows[s], best[s]] - mean[rows[s], r.chosen[s]]
        print(f"  {t:5d}  {s.sum():7d}  {r.legal[s].sum(axis=1).mean():5.2f}  "
              f"{(mean[s] * r.legal[s]).sum() / r.legal[s].sum():10.2f}  "
              f"{played_best[s].mean():14.1%}  {shortfall.mean():8.2f}")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    limit = None
    for arg in argv[1:]:
        if arg.startswith("--limit="):
            limit = int(arg.split("=", 1)[1])
    corpus = AllCardsCorpus(argv[0])
    print(f"{corpus.directory}: {len(corpus.shards)} shards, k {corpus.k}, seed {corpus.seed}, "
          f"model {corpus.layout['model']}")
    passed = run_checks(corpus, limit)
    print("\nall checks passed" if passed else "\nSOME CHECKS FAILED")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
