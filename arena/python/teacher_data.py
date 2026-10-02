"""Reading the teacher corpus (tools/teacher-export.sh), and checking it.

    python3 teacher_data.py ../../teacher-data-v2              the checks, seconds
    python3 teacher_data.py ../../teacher-data-v2 --all        over every record

One record is one card decision of the teacher with more than one legal card:
the position as the deciding seat saw it (the belief encoding, the same 306
bytes the belief net reads), the legal cards, the card it played, and its vote
-- in how many of its worlds each card still reaches the target -- plus the
cushion, and the vote world by world. The layout is read from
``teacher-format.json`` and the features' from ``encoding-vN.json``, both
written beside the shards by the exporter; nothing here hard-codes an offset
that the files do not also state, and a disagreement is a loud failure.

Pure numpy, like belief_data.py, and for the same reason: the part of the
pipeline that can be silently wrong -- a field at the wrong offset, a split
that leaks whole deals -- is all here and none of it needs a GPU to be checked.
"""

from __future__ import annotations

import json
import pathlib
import sys

import numpy as np

from belief_data import SPLIT_RULE, Corpus

CARDS = 32
MAX_LEGAL = 10

#: The record layout this reader was written against. Checked field by field
#: against teacher-format.json, so an exporter that moves a field fails here
#: rather than training a net on the wrong bytes.
EXPECTED = {
    "features": (0, 306), "legal_mask": (306, 4), "chosen": (310, 1), "worlds": (311, 1),
    "verdict_worlds": (312, 1), "cushion_asked": (313, 1), "contract": (314, 1),
    "declarer": (315, 1), "trick": (316, 1), "votes": (317, 32), "cushion": (349, 32),
    "verdicts": (381, 80), "deal": (461, 4), "seating": (465, 3), "teacher_seats": (468, 1),
}
#: Format 3 appends the play so far (engine PlayHistory): points and tricks
#: won per relative seat, then nine tricks of leader, three cards, winner.
#: Every format-2 field keeps its offset, so one reader serves both.
PLAY_STATE = (469, 51)
RECORD_BYTES_BY_FORMAT = {2: 469, 3: 520}
FORMAT = 3
RECORD_BYTES = RECORD_BYTES_BY_FORMAT[FORMAT]

CONTRACTS = ("Diamonds", "Hearts", "Spades", "Clubs", "Grand", "Null", "Ramsch")


def split_by_board(board, fraction=0.1, seed=0):
    """The belief corpus's split rule, on the teacher's deal ids.

    The same function, not a copy: ``Corpus.split_by_board`` reads nothing of
    the corpus it is called on, and a second implementation is a second place
    for the rule to drift. A deal held out here is held out by its id alone.
    """
    return Corpus.split_by_board(None, board, fraction, seed)


class TeacherCorpus:
    """One directory of teacher shards, plus the two files that describe them."""

    def __init__(self, directory):
        self.directory = pathlib.Path(directory)
        layout_file = self.directory / "teacher-format.json"
        if not layout_file.exists():
            raise FileNotFoundError(f"no teacher-format.json in {self.directory}")
        self.layout = json.loads(layout_file.read_text())
        self.format = self.layout["version"]
        if self.format not in RECORD_BYTES_BY_FORMAT:
            raise ValueError(f"teacher format {self.format}, this reader reads {sorted(RECORD_BYTES_BY_FORMAT)}")
        self.record_bytes = RECORD_BYTES_BY_FORMAT[self.format]
        if self.layout["record_bytes"] != self.record_bytes:
            raise ValueError(f"{self.layout['record_bytes']} bytes a record, format {self.format} is "
                             f"{self.record_bytes}")
        expected = dict(EXPECTED)
        if self.format >= 3:
            expected["play_state"] = PLAY_STATE
        for name, (offset, width) in expected.items():
            got = tuple(self.layout["fields"][name][:2])
            if got != (offset, width):
                raise ValueError(f"field {name} at {got}, this reader expects {(offset, width)}")

        specs = sorted(self.directory.glob("encoding-v*.json"))
        if len(specs) != 1:
            raise ValueError(f"expected one encoding-v*.json in {self.directory}, found {len(specs)}")
        self.spec = json.loads(specs[0].read_text())
        self.version = self.spec["version"]
        self.size = self.spec["size"]
        self.scale = self.spec["scale"]
        self.fields = {f["name"]: (f["offset"], f["width"]) for f in self.spec["fields"]}
        if self.version != self.layout["encoding_version"] or self.size != EXPECTED["features"][1]:
            raise ValueError("the features in the teacher records are not the encoding beside them")

        players = self.directory / "players.json"
        self.players = json.loads(players.read_text()) if players.exists() else []
        self.shards = sorted(self.directory.glob(f"teacher-v{self.format}-*.bin"))
        if not self.shards:
            raise FileNotFoundError(f"no teacher-v{self.format}-*.bin shards in {self.directory}")

    def field(self, name):
        return self.fields[name]

    def slice(self, x, name):
        offset, width = self.field(name)
        return x[..., offset:offset + width]

    def load(self, limit=None):
        """Every shard, or the first ``limit`` records, as a :class:`Records`."""
        blocks = []
        loaded = 0
        for shard in self.shards:
            data = np.fromfile(shard, dtype=np.uint8)
            if len(data) % self.record_bytes:
                raise ValueError(f"{shard.name} is not a whole number of records")
            blocks.append(data.reshape(-1, self.record_bytes))
            loaded += len(blocks[-1])
            if limit is not None and loaded >= limit:
                break
        raw = np.concatenate(blocks)
        if limit is not None:
            raw = raw[:limit]
        return Records(raw)

    def unpack(self, features):
        """Packed feature bytes as the floats a net reads."""
        return features.astype(np.float32) / self.scale


class Records:
    """The fields of a block of raw records, as arrays. Features stay packed."""

    def __init__(self, raw):
        self.raw = raw
        n = len(raw)
        self.features = np.ascontiguousarray(raw[:, 0:306])
        mask = np.ascontiguousarray(raw[:, 306:310]).view("<u4")[:, 0]
        self.legal = ((mask[:, None] >> np.arange(CARDS, dtype=np.uint32)) & 1).astype(bool)
        self.chosen = raw[:, 310].astype(np.int64)
        self.worlds = raw[:, 311].astype(np.int64)
        self.verdict_worlds = raw[:, 312].astype(np.int64)
        self.cushion_asked = raw[:, 313].astype(bool)
        self.contract = raw[:, 314].astype(np.int64)
        self.declarer = raw[:, 315].astype(bool)
        self.trick = raw[:, 316].astype(np.int64)
        self.votes = raw[:, 317:349].astype(np.int16)
        self.cushion = raw[:, 349:381].astype(np.int16)
        self.verdicts = np.ascontiguousarray(raw[:, 381:461]).view("<u8")
        self.deal = np.ascontiguousarray(raw[:, 461:465]).view("<i4")[:, 0].astype(np.int64)
        self.seating = raw[:, 465:468].astype(np.int64)
        self.teacher_seats = raw[:, 468].astype(np.int64)
        # Format 3 only: the play so far, raw (see PLAY_STATE); None before it.
        offset, width = PLAY_STATE
        self.play_state = np.ascontiguousarray(raw[:, offset:offset + width]) \
            if raw.shape[1] >= offset + width else None
        self.n = n

    def __len__(self):
        return self.n

    def best(self):
        """The cards with the top vote, per record: the teacher's own best set."""
        top = np.where(self.legal, self.votes, -1).max(axis=1)
        return self.legal & (self.votes == top[:, None])

    def share(self):
        """Each card's vote as a share of the worlds, the value target."""
        return self.votes / np.maximum(self.worlds, 1)[:, None]

    def verdict_bits_by_card(self):
        """The verdict words laid out by card index (zero off the legal cards)."""
        out = np.zeros((self.n, CARDS), dtype=np.uint64)
        rank = np.cumsum(self.legal, axis=1) - 1
        rows, cards = np.nonzero(self.legal)
        k = rank[rows, cards]
        keep = k < MAX_LEGAL
        out[rows[keep], cards[keep]] = self.verdicts[rows[keep], k[keep]]
        return out


#: Inputs the play state adds when a student reads it (format 3): points won
#: by me, left, right (/120); tricks won by each (/10); the declarer's side's
#: points and the defenders' (/120, both zero in a game without a declarer);
#: and for every card, if it has been played in a completed trick: which
#: trick ((t+1)/9), its place in that trick (one of three) and who won that
#: trick (one of three, relative). The per-card block is the part the belief
#: encoding cannot say -- it knows who played a card, not when or who took it.
PLAY_STATE_FEATURES = 3 + 3 + 2 + CARDS * 7


def play_state_features(corpus, state, features):
    """The play state of a batch as inputs; ``features`` the same rows' packed encoding."""
    n = len(state)
    s = state.astype(np.int64)
    out = np.zeros((n, PLAY_STATE_FEATURES), dtype=np.float32)
    out[:, 0:3] = s[:, 0:3] / 120.0
    out[:, 3:6] = s[:, 3:6] / 10.0
    declarer = corpus.slice(features, "declarer") > corpus.scale / 2       # me, left, right, nobody
    total = s[:, 0:3].sum(axis=1)
    rows = np.arange(n)
    has = declarer[:, :3].any(axis=1)
    seat = declarer[:, :3].argmax(axis=1)
    mine = np.where(has, s[rows, seat], 0)
    out[:, 6] = mine / 120.0
    out[:, 7] = np.where(has, total - mine, 0) / 120.0
    cards = out[:, 8:].reshape(n, CARDS, 7)
    tricks = s[:, 6:51].reshape(n, 9, 5)
    for t in range(9):
        on = tricks[:, t, 0] != 255
        if not on.any():
            continue
        r = rows[on]
        winner = tricks[on, t, 4]
        for k in range(3):
            card = tricks[on, t, 1 + k]
            cards[r, card, 0] = (t + 1) / 9.0
            cards[r, card, 1 + k] = 1.0
            cards[r, card, 4 + winner] = 1.0
    return out


def popcount64(words):
    """Bits set in each uint64, vectorised."""
    w = np.ascontiguousarray(words, dtype=np.uint64)
    if hasattr(np, "bitwise_count"):  # numpy 2
        return np.bitwise_count(w).astype(np.int64)
    as_bytes = w.view(np.uint8).reshape(w.shape + (8,))
    return np.unpackbits(as_bytes, axis=-1).sum(axis=-1).astype(np.int64)


def check(name, condition, detail=""):
    status = "ok  " if condition else "FAIL"
    print(f"  [{status}] {name}{'  -- ' + detail if detail and not condition else ''}")
    return bool(condition)


def check_play_state(corpus, r):
    """Format 3: the play so far against the encoding written beside it."""
    state = r.play_state.astype(np.int64)
    tricks = state[:, 6:51].reshape(-1, 9, 5)
    played = tricks[:, :, 0] != 255
    good = True
    good &= check("one completed trick for every trick before this one", (played.sum(axis=1) == r.trick - 1).all())
    good &= check("the completed tricks come first", (np.diff(played.astype(int), axis=1) <= 0).all())
    good &= check("tricks won add up to the tricks played", (state[:, 3:6].sum(axis=1) == r.trick - 1).all())
    good &= check("points won are at most 120", (state[:, 0:3].sum(axis=1) <= 120).all())
    no_tricks = state[:, 3:6] == 0
    good &= check("no points without a trick", (state[:, 0:3][no_tricks] == 0).all())
    # Every card in the history was played by the seat the encoding says, and
    # every card the encoding marks as played is in the history.
    blocks = [corpus.slice(r.features, name) > corpus.scale / 2
              for name in ("played_by_me", "played_by_left", "played_by_right")]
    agree = True
    covered = np.zeros((len(r), CARDS), dtype=bool)
    rows = np.arange(len(r))
    for t in range(9):
        on = played[:, t]
        for k in range(3):
            card = np.where(on, tricks[:, t, 1 + k], 0)
            seat = (tricks[:, t, 0] + k) % 3
            for s in range(3):
                m = on & (seat == s)
                agree &= bool(blocks[s][rows[m], card[m]].all())
            covered[rows[on], card[on]] = True
    marked = blocks[0] | blocks[1] | blocks[2]
    good &= check("the history's cards were played by the seats the encoding names", agree)
    good &= check("every card the encoding marks as played is in the history", (covered == marked).all())
    winners = tricks[:, :8, 4]
    leaders = tricks[:, 1:, 0]
    both = played[:, 1:]
    good &= check("each trick's winner leads the next", (winners[both] == leaders[both]).all())
    return good


def run_checks(corpus, records, fraction=0.1, seed=1):
    """The checks a night of training should not start without. True when all pass."""
    r = records
    good = True
    rows = np.arange(len(r))
    print("what is in it")
    for c in range(len(CONTRACTS)):
        count = int((r.contract == c).sum())
        if count:
            print(f"    {CONTRACTS[c]:9s} {count:>10,}  {count / len(r):6.1%}")
    missing = [CONTRACTS[c] for c in range(5) if not (r.contract == c).any()]
    print(f"    declarer's decisions {r.declarer.mean():.1%}; "
          f"teacher seats 1/2/3: " + " / ".join(f"{(r.teacher_seats == s).mean():.1%}" for s in (1, 2, 3)))
    if not (r.contract == 5).any():
        print("    no Null decision at all: the student has nothing to learn Null from, and "
              "must hand Null games back to the search")
    if missing:
        print(f"    missing suit or grand contracts: {missing}")
    print()

    print("records")
    good &= check("every record has more than one legal card", (r.legal.sum(axis=1) > 1).all())
    good &= check("the chosen card is legal", r.legal[rows, r.chosen].all())
    good &= check("no vote on an illegal card", (r.votes[~r.legal] == 0).all())
    good &= check("votes within the worlds", (r.votes <= r.worlds[:, None]).all())
    good &= check("worlds recorded", (r.worlds > 0).all())
    best = r.best()
    good &= check("the chosen card is one of the top-voted", best[rows, r.chosen].all(),
                  f"{(~best[rows, r.chosen]).sum()} records choose below the top vote")
    within = r.worlds <= 64
    bits = popcount64(r.verdict_bits_by_card())
    good &= check("the world-by-world verdicts add up to the votes",
                  (bits[within] == r.votes[within]).all(),
                  f"{(bits[within] != r.votes[within]).any(axis=1).sum()} records disagree")
    good &= check("trick 1..9", ((r.trick >= 1) & (r.trick <= 9)).all(),
                  f"tricks seen {np.unique(r.trick)}")
    good &= check("the deciding seat is the teacher", (r.seating[:, 0] == 0).all())
    seats = 1 + (r.seating[:, 1] == 0) + (r.seating[:, 2] == 0)
    good &= check("the seating agrees with the teacher's seat count", (seats == r.teacher_seats).all())
    decl_feature = corpus.slice(r.features, "declarer")[:, 0] > corpus.scale / 2
    good &= check("the declarer byte agrees with the encoding", (decl_feature == r.declarer).all())
    contract_feature = corpus.slice(r.features, "contract").argmax(axis=1)
    good &= check("the contract byte agrees with the encoding", (contract_feature == r.contract).all())
    hand = corpus.slice(r.features, "my_hand") > corpus.scale / 2
    good &= check("every legal card is in the hand", (hand | ~r.legal).all())
    if r.play_state is not None:
        good &= check_play_state(corpus, r)
    print()

    print("the split")
    train, val = split_by_board(r.deal, fraction, seed)
    shared = np.intersect1d(np.unique(r.deal[train]), np.unique(r.deal[val]))
    good &= check("no deal on both sides", len(shared) == 0, f"{len(shared)} deals on both sides")
    # By deal, so a small corpus lands further from the fraction: four
    # binomial standard errors over the deals, and never tighter than 3 points.
    deals = len(np.unique(r.deal))
    slack = max(0.03, 4 * np.sqrt(fraction * (1 - fraction) / max(deals, 1)))
    held = len(np.unique(r.deal[val])) / max(deals, 1)
    good &= check(f"about {fraction:.0%} of the deals held out", abs(held - fraction) < slack,
                  f"{held:.1%} of {deals:,} deals")
    print(f"    {len(train):,} training, {len(val):,} held out ({SPLIT_RULE}, seed {seed})")
    print()

    ties = best.sum(axis=1)
    legal = r.legal.sum(axis=1)
    print("how hard the choice is")
    print(f"    legal cards {legal.mean():.2f} on average; the teacher's best set holds "
          f"{(ties / legal).mean():.0%} of them")
    print(f"    every legal card tied at the top {(ties == legal).mean():.1%} "
          f"(the vote says nothing; the tiebreaks decide) -- one best card {(ties == 1).mean():.1%}")
    print(f"    a card at random: the teacher's card {(1 / legal).mean():.1%}, "
          f"a best card {(ties / legal).mean():.1%}")
    print()
    print("all checks passed" if good else "SOME CHECKS FAILED")
    return good


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    corpus = TeacherCorpus(argv[0])
    limit = None if "--all" in argv else 300_000
    records = corpus.load(limit=limit)
    print(f"teacher format {corpus.format}, encoding v{corpus.version}, {len(corpus.shards)} shards, "
          f"players {corpus.players}")
    print(f"{len(records):,} records from {len(np.unique(records.deal)):,} deals"
          + (f" (the first {limit:,})" if limit else ""))
    print()
    return 0 if run_checks(corpus, records) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
