"""
SkatZero's card at a teacher record (plan 2.10, R: SkatZero on the yardstick).

eval_policy.py scores players on the teacher's held-out decisions; this gives
it SkatZero (github.com/Jimboom7/SkatZero, MIT, run through its ONNX models
like tools/skatzero-bot.py) as one more player. A format-3 record holds what
SkatZero's state is built from: my hand, the contract and who declares, my
discard as declarer, every completed trick in play order, and the points
taken. The one thing it does not hold is the order of the current trick when
two cards lie in it: the encoding has them as a set (``current_trick``) with
the leader beside it. When the two cards are of different classes (trump or a
suit), the voids the encoding records settle which one was led -- the second
seat must have shown out of the led class. When they are of the same class
both orders are tried, and where SkatZero's card differs between them the
decision is ambiguous and left out for every player (eval_policy drops those
rows from all its tables, so the comparison stays like with like).

Every decision is checked: SkatZero's own legal cards, worked out from the
state rebuilt here, must be exactly the record's legal cards. A mismatch is a
reconstruction error; such rows are counted and left out too, and more than a
handful of them stops the run.

What SkatZero plays here is what tools/skatzero-bot.py plays at the arena:
the same state, the same nine models, and the same one-step look-ahead when
its card completes a trick it wins. Its bidding never comes into it -- the
record's contract is given -- and neither do the bids (the bot hands SkatZero
empty bids too).
"""

from __future__ import annotations

import os
import sys

import numpy as np

SUITS = "CSHD"                       # Card.Suit order: clubs, spades, hearts, diamonds
RANKS = "789TJQKA"                   # Card.Rank order
CONTRACT_LETTER = "DHSCGN"           # Contract ordinal: diamonds, hearts, spades, clubs, grand, null
RANK_POINTS = {'A': 11, 'T': 10, 'K': 4, 'Q': 3, 'J': 2, '9': 0, '8': 0, '7': 0}
SUIT_ORDER = {'C': 3, 'S': 2, 'H': 1, 'D': 0}
RANK_ORDER = {'A': 7, 'T': 6, 'K': 5, 'Q': 4, '9': 3, '8': 2, '7': 1}
NULL_ORDER = {'A': 8, 'K': 7, 'Q': 6, 'J': 5, 'T': 4, '9': 3, '8': 2, '7': 1}
EMPTY_BIDS = {'D': 0, 'H': 0, 'S': 0, 'C': 0, 'N': 0}
FOLLOW_CLASSES = 5                   # the void fields: trump, then the four suits in Card.Suit order

AMBIGUOUS = -1
MISMATCH = -2


def name(index):
    return SUITS[index // 8] + RANKS[index % 8]


def index_of(card):
    return SUITS.index(card[0]) * 8 + RANKS.index(card[1])


def is_trump(card, contract):
    if contract == 'N':
        return False
    return card[1] == 'J' or (contract != 'G' and card[0] == contract)


def follow_class(card, contract):
    """0 = trump, 1 + suit index otherwise: the void fields' order."""
    return 0 if is_trump(card, contract) else 1 + SUITS.index(card[0])


def trick_winner(trick, contract):
    """trick: [(seat, card)]; the seat that takes it. As tools/skatzero-bot.py."""
    lead = trick[0][1]
    if contract == 'N':
        suit = lead[0]
        best = trick[0]
        for p, c in trick[1:]:
            if c[0] == suit and NULL_ORDER[c[1]] > NULL_ORDER[best[1][1]]:
                best = (p, c)
        return best[0]

    def strength(c):
        if c[1] == 'J':
            return 100 + SUIT_ORDER[c[0]]
        if is_trump(c, contract):
            return 50 + RANK_ORDER[c[1]]
        return RANK_ORDER[c[1]]

    lead_suit = contract if is_trump(lead, contract) else lead[0]
    best = trick[0]
    for p, c in trick[1:]:
        if is_trump(c, contract):
            if not is_trump(best[1], contract) or strength(c) > strength(best[1]):
                best = (p, c)
        elif not is_trump(best[1], contract) and c[0] == lead_suit and strength(c) > strength(best[1]):
            best = (p, c)
    return best[0]


class Position:
    """One decision as SkatZero needs it; seats relative to the deciding seat (0 me, 1 left, 2 right)."""

    def __init__(self, contract, declarer, hand, skat, hand_game, completed, current, leader, points):
        self.contract = contract        # 'D' 'H' 'S' 'C' 'G' 'N'
        self.declarer = declarer        # 0, 1, 2
        self.hand = hand                # card names
        self.skat = skat                # my discard, as declarer, else []
        self.hand_game = hand_game
        self.completed = completed      # [(seat, card)] in play order, completed tricks
        self.current = current          # [(seat, card)] the trick on the table, in order
        self.leader = leader            # who led the current trick
        self.points = points            # [declarer's side, defenders] from completed tricks


def positions(corpus, records, rows):
    """The positions of these rows: a list of candidates (more than one when the order is open) per row."""
    f = corpus.slice
    feats = records.features[rows]
    state = records.play_state[rows].astype(np.int64)
    hand = f(feats, "my_hand") > corpus.scale / 2
    current = f(feats, "current_trick") > corpus.scale / 2
    leader = f(feats, "trick_leader").argmax(axis=1)
    declarer = f(feats, "declarer").argmax(axis=1)
    discard = f(feats, "my_discard") > corpus.scale / 2
    discard_known = f(feats, "my_discard_present")[:, 0] > corpus.scale / 2
    void = [None, f(feats, "void_left") > corpus.scale / 2, f(feats, "void_right") > corpus.scale / 2]
    out = []
    for i, row in enumerate(rows):
        contract = CONTRACT_LETTER[records.contract[row]]
        d = int(declarer[i])
        if d == 3:
            raise SystemExit("a record without a declarer: SkatZero plays no Ramsch")
        my_hand = [name(c) for c in np.flatnonzero(hand[i])]
        i_declare = d == 0
        skat = [name(c) for c in np.flatnonzero(discard[i])] if (i_declare and discard_known[i]) else []
        hand_game = i_declare and not discard_known[i]
        completed = []
        pts = state[i, 0:3]
        for t in range(9):
            at = 6 + 5 * t
            if state[i, at] == 255:
                break
            lead = int(state[i, at])
            for k in range(3):
                completed.append(((lead + k) % 3, name(int(state[i, at + 1 + k]))))
        points = [int(pts[d]), int(pts.sum() - pts[d])]
        if i_declare and skat:
            points[0] += sum(RANK_POINTS[c[1]] for c in skat)
        lead = int(leader[i])
        on_table = [name(c) for c in np.flatnonzero(current[i])]
        orders = []
        if len(on_table) <= 1:
            orders.append([(lead, c) for c in on_table])
        else:
            a, b = on_table
            second = (lead + 1) % 3
            for first, then in ((a, b), (b, a)):
                led = follow_class(first, contract)
                # The second seat followed, or has shown out of the led class.
                if follow_class(then, contract) == led or (second != 0 and void[second][i, led]):
                    orders.append([(lead, first), (second, then)])
            if not orders:
                orders.append([(lead, a), (second, b)])   # the check against the legal cards will say
        out.append([Position(contract, d, my_hand, skat, hand_game, completed, order, lead, points)
                    for order in orders])
    return out


class Evaluator:
    """SkatZero's nine models and its state code, in one process."""

    def __init__(self, checkout):
        sys.path.insert(0, checkout)
        import onnxruntime as ort
        from skatzero.env.skat import SkatEnv
        from skatzero.env.feature_transformations import extract_state  # noqa: F401  (imported for SkatEnv)
        from skatzero.evaluation.utils import swap_colors
        from skatzero.test.utils import construct_state_from_history
        self.swap_colors = swap_colors
        self.construct = construct_state_from_history
        self.sessions = []
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        for gametype in "DGN":
            for i in range(3):
                path = os.path.join(checkout, "models", "onnx", f"{gametype}_{i}.onnx")
                self.sessions.append(ort.InferenceSession(path, options, providers=["CPUExecutionProvider"]))
        self.env = SkatEnv()
        self.state, _ = self.env.game.init_game()

    def set_gametype(self, gametype):
        trump = 'J' if gametype == 'G' else (None if gametype == 'N' else 'D')
        self.state['trump'] = trump
        self.env.game.round.trump = trump
        self.env.game.gametype = 'D' if gametype in 'CSHD' else gametype
        self.env.game.round.gametype = self.env.game.gametype

    def values(self, p, hand, trace, points, leader):
        """SkatZero's value for each legal card, and its legal cards, in our card names."""
        gt = p.contract
        me = (0 - p.declarer) % 3
        rs = self.state
        rs['self'] = me
        rs['soloplayer'] = 0
        rs['points'] = list(points)
        rs['pos'] = (leader - p.declarer) % 3
        rs['bids'] = [dict(EMPTY_BIDS) for _ in range(3)]
        rs['bid_jacks'] = [0, 0, 0]
        rs['blind_hand'] = p.hand_game
        rs['open_hand'] = False
        rs['soloplayer_open_cards'] = []
        rs['drueck'] = False
        hand = list(hand)
        skat = list(p.skat) if me == 0 else []
        trace = [((s - p.declarer) % 3, c) for s, c in trace]
        if gt in 'CSH':
            hand = self.swap_colors(hand, 'D', gt)
            skat = self.swap_colors(skat, 'D', gt)
            trace = [(s, self.swap_colors([c], 'D', gt)[0]) for s, c in trace]
        rs['current_hand'] = hand
        rs['skat'] = skat
        rs['trace'] = trace
        self.set_gametype(gt)
        played, others, trick, actions = self.construct(hand, trace, skat, trump=rs['trump'])
        rs['played_cards'] = played
        rs['others_hand'] = others
        rs['actions'] = actions
        rs['trick'] = trick
        state = self.env.extract_state(rs)
        legal = state['legal_actions']
        keys = list(legal.keys())
        acts = np.array([legal[k] for k in keys], dtype=np.float32)
        obs = np.repeat(state['obs'].astype(np.float32)[None, :], len(keys), axis=0)
        history = np.repeat(state['history'].astype(np.float32)[None, :, :], len(keys), axis=0)
        mode = {'G': 3, 'N': 6}.get(gt, 0)
        out = self.sessions[mode + me].run(['output'], {'obs': obs, 'history': history, 'actions': acts})[0]
        cards = state['raw_legal_actions']
        result = {}
        for card, value in zip(cards, np.asarray(out).reshape(-1)):
            ours = self.swap_colors([card], 'D', gt)[0] if gt in 'CSH' else card
            result[ours] = float(value)
        return result

    def choose(self, p):
        """SkatZero's card, as tools/skatzero-bot.py's Driver.choose plays it; and its legal cards."""
        trace = p.completed + p.current
        values = self.values(p, p.hand, trace, p.points, p.leader)
        legal = set(values)
        if p.contract != 'N' and len(p.current) == 2 and len(p.hand) > 1:
            looked = {}
            for card, value in values.items():
                full = p.current + [(0, card)]
                if trick_winner(full, p.contract) != 0:
                    looked[card] = value
                    continue
                points = list(p.points)
                points[0 if p.declarer == 0 else 1] += sum(RANK_POINTS[c[1]] for _, c in full)
                after = self.values(p, [c for c in p.hand if c != card], trace + [(0, card)], points, 0)
                looked[card] = max(after.values())
            values = looked
        return max(values, key=values.get), legal


_worker = None


def _init(checkout):
    global _worker
    _worker = Evaluator(checkout)


def _decide(batch):
    out = []
    for candidates, legal_mask in batch:
        picks = set()
        for p in candidates:
            card, legal = _worker.choose(p)
            if {index_of(c) for c in legal} != set(np.flatnonzero(legal_mask).tolist()):
                continue
            picks.add(index_of(card))
        if not picks:
            out.append(MISMATCH)
        elif len(picks) > 1:
            out.append(AMBIGUOUS)
        else:
            out.append(picks.pop())
    return out


def skatzero_picks(corpus, records, rows, checkout, workers=1, chunk=256):
    """SkatZero's card per row (a card index), AMBIGUOUS or MISMATCH where it cannot be scored."""
    if records.play_state is None:
        raise SystemExit("SkatZero on the yardstick needs a format-3 corpus (the play so far)")
    if not os.path.exists(os.path.join(checkout, "models", "onnx", "D_0.onnx")):
        raise SystemExit(f"no SkatZero checkout with ONNX models at {checkout}")
    cands = positions(corpus, records, rows)
    work = list(zip(cands, records.legal[rows]))
    batches = [work[i:i + chunk] for i in range(0, len(work), chunk)]
    if workers <= 1:
        _init(checkout)
        results = [_decide(b) for b in batches]
    else:
        import multiprocessing
        with multiprocessing.get_context("spawn").Pool(workers, initializer=_init, initargs=(checkout,)) as pool:
            results = pool.map(_decide, batches)
    return np.array([x for r in results for x in r], dtype=np.int64)
