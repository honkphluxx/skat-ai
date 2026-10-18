"""The suits are interchangeable: more records from the same boards (plan 2.10, I5).

    python3 suit_permutation.py --check-fixtures                 the Java encoder's own pairs
    python3 suit_permutation.py ../../teacher-data-best          the corpus checks on permuted records
    python3 suit_permutation.py ../../teacher-data-best --mode=bids

The belief encoding lays the cards out suit-major (BeliefEncoding.index:
suit * 8 + rank, clubs, spades, hearts, diamonds), so a net reading it learns
a hearts position and the same position with spades for hearts apart. Skat
does not tell them apart:

* in a suit game the three side suits can be exchanged (6 ways) -- the trump
  suit stays, because the contract and every bid are priced on it;
* in a grand and a Ramsch the four suits can, all but the jacks (24 ways) --
  the jacks are the trumps and their order is fixed;
* in a Null the four whole suits can, the jacks with them (24 ways).

A permutation moves every per-card input (the hand, the plays, the trick, the
discard, the Schieben), the follow classes a seat has shown out of, and in a
Null the unseen jacks; and with them the per-card targets: the legal cards,
the card played, the votes. Nothing else in the encoding names a suit, and
:class:`SuitPermutation` refuses an encoding with a field it has not been told
about, so a field added later cannot be left behind silently.

One input does name suits without saying so: a bid. 22 is spades times two,
and a defender who bid 22 in a grand is likely long in spades; exchange spades
for hearts and the bid still says 22. ``mode="all"`` permutes anyway and lets
the held-out yardstick say what that costs; ``mode="bids"`` draws only the
permutations that map, for each remembered bid and the winning one, the set of
suits whose base value divides it onto itself (9 diamonds, 10 hearts, 11
spades, 12 clubs) -- fewer of them, but none that contradicts an auction.

The teacher's votes and the solver behind them are the game's and permute with
it; its rule tiebreak, which decides between tied cards, may prefer one suit
by order, and that only moves which of equally voted cards was played.

Two checks before a trainer may use it. ``fixtures/suit-permutation.bin`` holds
pairs the Java encoder wrote (SuitPermutationFixtures): a random position, and
the same position with every card moved by a permutation, each encoded by
BeliefEncoding itself; the table here must turn the first into the second byte
for byte. And a block of the corpus, permuted, must pass every check
teacher_data.run_checks makes of a corpus -- the verdicts against the votes,
the play so far against the encoding, the legal cards in the hand.
"""

from __future__ import annotations

import itertools
import pathlib
import sys

import numpy as np

CARDS = 32
JACK = 4                        # Card.Rank ordinal: 7 8 9 10 J Q K A
SUITS = 4                       # Card.Suit ordinal: clubs spades hearts diamonds
#: The contract byte (Contract ordinal) -> its trump suit's ordinal; -1 for none.
TRUMP_SUIT = (3, 2, 1, 0, -1, -1, -1)   # diamonds hearts spades clubs grand null ramsch
NULL = 5
#: Every permutation of the four suits, the identity first: PERMS[k][s] is where suit s goes.
PERMS = tuple(itertools.permutations(range(SUITS)))
K = len(PERMS)
#: A suit's base value -> the suit (bids divisible by it point at it).
BASES = {9: 3, 10: 2, 11: 1, 12: 0}
MODES = ("all", "bids")

#: The encoding's fields by what a permutation does to them.
CARD_FIELDS = ("my_hand", "played_by_me", "played_by_left", "played_by_right", "current_trick",
               "my_discard", "pushed_on", "received")
VOID_FIELDS = ("void_left", "void_right")        # trump, then the four suits
JACK_FIELDS = ("jacks_out",)                     # one per suit, clubs to diamonds
SUIT_FREE = ("played_present", "trick_leader", "my_discard_present", "pushed_to_skat",
             "received_from_skat", "schieben_present", "contract", "declarer", "bid_value",
             "bids_by_seat", "bidding_present", "contra_level", "contra_seat", "trumps_out",
             "trumps_mine", "counts_present", "cards_left", "trick_number")

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures" / "suit-permutation.bin"
FIXTURE_MAGIC = b"SKSP"


def card_map(perm, jacks_move):
    """Where each card index goes under a suit permutation."""
    out = np.empty(CARDS, dtype=np.int64)
    for suit in range(SUITS):
        for rank in range(8):
            card = suit * 8 + rank
            out[card] = card if rank == JACK and not jacks_move else perm[suit] * 8 + rank
    return out


class SuitPermutation:
    """The tables for one encoding: built from its field list, refused if it has fields not named above."""

    def __init__(self, fields, size):
        named = set(CARD_FIELDS) | set(VOID_FIELDS) | set(JACK_FIELDS) | set(SUIT_FREE)
        unknown = sorted(set(fields) - named)
        if unknown:
            raise ValueError(f"the encoding has fields this permutation does not know: {unknown}; "
                             "say in suit_permutation.py what a suit permutation does to them")
        absent = sorted(named - set(fields))
        if absent:
            raise ValueError(f"the encoding lacks fields this permutation expects: {absent}")
        widths = {**{f: CARDS for f in CARD_FIELDS}, **{f: 1 + SUITS for f in VOID_FIELDS},
                  **{f: SUITS for f in JACK_FIELDS}}
        for name, width in widths.items():
            if fields[name][1] != width:
                raise ValueError(f"field {name} is {fields[name][1]} wide, expected {width}")
        self.fields = fields
        self.size = size
        # [jacks move][k] -> card index map (forward), its inverse, and the
        # feature gather: x_new = x[feature_source].
        self.forward = np.empty((2, K, CARDS), dtype=np.int64)
        self.card_source = np.empty((2, K, CARDS), dtype=np.int64)
        self.feature_source = np.empty((2, K, size), dtype=np.int64)
        for move in (0, 1):
            for k, perm in enumerate(PERMS):
                pi = card_map(perm, bool(move))
                self.forward[move, k] = pi
                self.card_source[move, k, pi] = np.arange(CARDS)
                src = np.arange(size)
                for name in CARD_FIELDS:
                    offset = fields[name][0]
                    src[offset + pi] = offset + np.arange(CARDS)
                for name in VOID_FIELDS:
                    offset = fields[name][0]
                    for suit in range(SUITS):
                        src[offset + 1 + perm[suit]] = offset + 1 + suit
                if move:
                    for name in JACK_FIELDS:
                        offset = fields[name][0]
                        for suit in range(SUITS):
                            src[offset + perm[suit]] = offset + suit
                self.feature_source[move, k] = src

    @classmethod
    def for_corpus(cls, corpus):
        return cls(corpus.fields, corpus.size)

    # ------------------------------------------------------------ which ones

    def allowed(self, features, contract, mode="all"):
        """(n, K) bool: the permutations each record may take. The identity always."""
        if mode not in MODES:
            raise ValueError(f"mode is one of {MODES}, not {mode}")
        n = len(contract)
        perms = np.array(PERMS)                                    # (K, 4)
        trump = np.array(TRUMP_SUIT)[contract]                     # (n,)
        fixes = perms[:, np.maximum(trump, 0)].T == np.maximum(trump, 0)[:, None]   # (n, K)
        ok = np.where((trump >= 0)[:, None], fixes, True)
        if mode == "bids":
            for bid in self.bids(features):
                hinted = np.zeros((n, SUITS), dtype=bool)
                for base, suit in BASES.items():
                    hinted[:, suit] = (bid > 0) & (bid % base == 0)
                # sigma maps the hinted set onto itself: hinted[perm[s]] == hinted[s] for every s.
                for k, perm in enumerate(PERMS):
                    ok[:, k] &= (hinted[:, list(perm)] == hinted).all(axis=1)
        ok[:, 0] = True
        return ok

    def bids(self, features):
        """The winning bid and each remembered seat's highest, as whole numbers (0 when none)."""
        f = features.astype(np.int64)
        value = lambda b: np.minimum(100, np.rint(b * 100 / 255).astype(np.int64))
        offset, _ = self.fields["bid_value"]
        out = [value(f[:, offset])]
        offset, width = self.fields["bids_by_seat"]
        present = f[:, self.fields["bidding_present"][0]] > 127
        for seat in range(width):
            out.append(np.where(present, value(f[:, offset + seat]), 0))
        return out

    @staticmethod
    def draw(allowed, rng):
        """One permitted permutation per row, uniformly among those permitted."""
        scores = rng.random(allowed.shape)
        return np.where(allowed, scores, -1.0).argmax(axis=1)

    # ------------------------------------------------------------ applying

    def moves(self, contract):
        return (np.asarray(contract) == NULL).astype(np.int64)

    def features(self, features, contract, k):
        """Packed (or float) feature rows, permuted row by row."""
        src = self.feature_source[self.moves(contract), k]          # (n, size)
        return np.take_along_axis(features, src, axis=1)

    def per_card(self, values, contract, k):
        """A per-card array (n, 32) -- legal, votes, shares -- moved with the cards."""
        src = self.card_source[self.moves(contract), k]
        return np.take_along_axis(values, src, axis=1)

    def card(self, cards, contract, k):
        """Card indices (n,) -- the card played -- moved."""
        return self.forward[self.moves(contract), k, cards]

    def play_state(self, state, contract, k):
        """Format 3's play so far: the cards in its nine tricks moved, the rest as it is."""
        out = state.copy()
        fwd = self.forward[self.moves(contract), k]                 # (n, 32)
        rows = np.arange(len(state))
        for t in range(9):
            for place in range(3):
                col = 6 + 5 * t + 1 + place
                card = state[:, col].astype(np.int64)
                on = state[:, 6 + 5 * t] != 255
                out[on, col] = fwd[rows[on], card[on]]
        return out

    def raw(self, raw, k):
        """Whole teacher records (format 2 or 3) permuted -- for the checks; training permutes batches."""
        from teacher_data import CARDS as _C, MAX_LEGAL, PLAY_STATE, Records
        assert _C == CARDS
        r = Records(raw)
        out = raw.copy()
        contract = r.contract
        out[:, 0:self.size] = self.features(raw[:, 0:self.size], contract, k)
        legal = self.per_card(r.legal, contract, k)
        bits = (legal.astype(np.uint64) << np.arange(CARDS, dtype=np.uint64)).sum(axis=1).astype("<u4")
        out[:, 306:310] = bits.view(np.uint8).reshape(-1, 4)
        out[:, 310] = self.card(r.chosen, contract, k)
        out[:, 317:349] = self.per_card(raw[:, 317:349], contract, k)
        out[:, 349:381] = self.per_card(raw[:, 349:381], contract, k)
        # The verdicts are kept per legal card in index order: re-laid out by the new order.
        by_card = self.per_card(r.verdict_bits_by_card(), contract, k)
        verdicts = np.zeros((len(raw), MAX_LEGAL), dtype=np.uint64)
        rank = np.cumsum(legal, axis=1) - 1
        rows, cards = np.nonzero(legal)
        keep = rank[rows, cards] < MAX_LEGAL
        verdicts[rows[keep], rank[rows[keep], cards[keep]]] = by_card[rows[keep], cards[keep]]
        out[:, 381:461] = verdicts.astype("<u8").view(np.uint8).reshape(-1, 8 * MAX_LEGAL)
        offset, width = PLAY_STATE
        if raw.shape[1] >= offset + width:
            out[:, offset:offset + width] = self.play_state(raw[:, offset:offset + width], contract, k)
        return out


# ---------------------------------------------------------------- the checks

def read_fixtures(path=FIXTURES):
    """(contract, perm, before, after) arrays from the Java encoder's pairs."""
    data = pathlib.Path(path).read_bytes()
    if data[:4] != FIXTURE_MAGIC:
        raise ValueError(f"{path} is not a suit-permutation fixture file")
    version, count, size = np.frombuffer(data[4:16], dtype="<i4")
    if version != 1:
        raise ValueError(f"fixture version {version}, this reads 1")
    case = 1 + SUITS + 2 * size
    body = np.frombuffer(data[16:], dtype=np.uint8)
    if len(body) != count * case:
        raise ValueError(f"{path}: {len(body)} bytes for {count} cases of {case}")
    body = body.reshape(count, case)
    return (body[:, 0].astype(np.int64), body[:, 1:1 + SUITS].astype(np.int64),
            body[:, 1 + SUITS:1 + SUITS + size], body[:, 1 + SUITS + size:], int(size))


def check_fixtures(fields, size, path=FIXTURES, verbose=True):
    """True when the table turns every Java 'before' into its 'after'."""
    contract, perm, before, after, fixture_size = read_fixtures(path)
    if fixture_size != size:
        raise ValueError(f"fixtures are {fixture_size} wide, the encoding {size}")
    table = SuitPermutation(fields, size)
    index = {p: k for k, p in enumerate(PERMS)}
    k = np.array([index[tuple(p)] for p in perm])
    got = table.features(before, contract, k)
    wrong = (got != after).any(axis=1)
    identity = (before == after).all(axis=1)
    if verbose:
        names = ("Diamonds", "Hearts", "Spades", "Clubs", "Grand", "Null", "Ramsch")
        seen = ", ".join(f"{names[c]} {int((contract == c).sum())}" for c in range(7))
        print(f"  suit permutation: {len(contract)} pairs from the Java encoder ({seen}); "
              f"{int(identity.sum())} unchanged by their permutation; {int(wrong.sum())} disagree")
        for row in np.nonzero(wrong)[0][:5]:
            cols = np.nonzero(got[row] != after[row])[0]
            at = [next(n for n, (o, w) in fields.items() if o <= c < o + w) for c in cols]
            print(f"    pair {row}: contract {contract[row]}, permutation {tuple(int(s) for s in perm[row])}, "
                  f"fields {sorted(set(at))}")
    return not wrong.any()


def main(argv):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("data", nargs="?", help="a teacher corpus: its checks, run on permuted records")
    parser.add_argument("--check-fixtures", action="store_true")
    parser.add_argument("--mode", default="all", choices=MODES)
    parser.add_argument("--limit", type=int, default=200_000)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args(argv)
    if not args.data and not args.check_fixtures:
        parser.print_help()
        return 2
    good = True
    if args.data:
        from teacher_data import Records, TeacherCorpus
        corpus = TeacherCorpus(args.data)
        if args.check_fixtures:
            good &= check_fixtures(corpus.fields, corpus.size)
        good &= check_corpus(corpus, corpus.load(limit=args.limit), args.mode, args.seed)
    else:
        import json
        spec = json.loads(next(FIXTURES.parent.glob("encoding-v*.json")).read_text())
        good &= check_fixtures({f["name"]: (f["offset"], f["width"]) for f in spec["fields"]}, spec["size"])
    print("suit permutation: all checks passed" if good else "suit permutation: SOME CHECKS FAILED")
    return 0 if good else 1


def check_corpus(corpus, records, mode, seed=7, verbose=True):
    """The corpus checks on a permuted copy of ``records``, and what the mode leaves to draw from."""
    from teacher_data import Records, run_checks
    table = SuitPermutation.for_corpus(corpus)
    allowed = table.allowed(records.features, records.contract, mode)
    if verbose:
        report_allowed(allowed, records.contract, mode)
    k = table.draw(allowed, np.random.default_rng(seed))
    permuted = Records(table.raw(records.raw, k))
    print(f"  the corpus checks on {len(records):,} permuted records "
          f"({int((k != 0).sum()):,} moved):")
    if verbose:
        return run_checks(corpus, permuted)
    import contextlib
    import io
    printed = io.StringIO()
    with contextlib.redirect_stdout(printed):
        good = run_checks(corpus, permuted)
    for line in printed.getvalue().splitlines():
        if "FAIL" in line:
            print(line)
    print("    all passed" if good else "    SOME FAILED")
    return good


def report_allowed(allowed, contract, mode):
    names = ("Diamonds", "Hearts", "Spades", "Clubs", "Grand", "Null", "Ramsch")
    parts = []
    for c in range(7):
        m = contract == c
        if m.any():
            parts.append(f"{names[c]} {allowed[m].sum(axis=1).mean():.1f}")
    print(f"  permutations a record may take, mode {mode}, on average: {', '.join(parts)} "
          f"(overall {allowed.sum(axis=1).mean():.1f})")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
