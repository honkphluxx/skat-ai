"""SkatZero (github.com/Jimboom7/SkatZero, MIT) as a skat-ai helper process.

Speaks the external-bot protocol of docs/external-bots.md on stdin/stdout, so
the arena seats it through ExternalBotProvider like XSkat and go-skat. Needs a
checkout of SkatZero with its ONNX models (models/onnx/{D,G,N}_{0,1,2}.onnx),
Python 3, numpy and onnxruntime -- not torch: the models are run through
onnxruntime and SkatZero's own torch-loading code is never imported.

    git clone https://github.com/Jimboom7/SkatZero third_party/skatzero
    python -m pip install numpy onnxruntime

Where the checkout is: $SKATKLAR_SKATZERO_DIR, else third_party/skatzero next
to this script's parent directory.

What it is good for, and what it is not. SkatZero is a self-play reinforcement
learner (DouZero-style deep Monte Carlo) and its card play is the reference
it is seated for: measured in a container at fixed oracle contracts it beat
`search` by +9.8 game points a game over 102 boards, where belief-32 beats
`search` by about +2.4. Its bidding is a bolted-on heuristic that runs the net
over every pickup and takes up to a minute a hand, so this driver does not bid:
MAXBID answers 0 and the seat never declares in auction mode. Measure it with
--fixed-contract.

Honesty by construction rather than by probe. The GAME line carries all three
hands and the skat because XSkat and go-skat need them; this driver keeps its
own seat's ten cards (and the skat when it is the declarer and picked up) and
drops the rest before storing anything. The net's features are its own hand,
the set of cards it has not seen, the trick, the history and the voids each
seat has shown -- the same things a person at the table knows. The STATS
reply therefore reports zero mismatches without running the reshuffle probe;
there is nothing for a reshuffle to change.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SZ = os.environ.get('SKATKLAR_SKATZERO_DIR') or os.path.join(os.path.dirname(HERE), 'third_party', 'skatzero')
sys.path.insert(0, SZ)

# The protocol goes out on a duplicate of fd 1; anything the library prints
# goes to stderr, which the arena discards. See docs/external-bots.md on why.
protocol_out = os.fdopen(os.dup(1), 'w', buffering=1)
sys.stdout = sys.stderr

import numpy as np  # noqa: E402
import onnxruntime as ort  # noqa: E402
from skatzero.env.skat import SkatEnv  # noqa: E402
from skatzero.env.feature_transformations import extract_state  # noqa: E402
from skatzero.evaluation.utils import swap_colors  # noqa: E402
from skatzero.game.utils import init_32_deck  # noqa: E402
from skatzero.test.utils import available_actions, construct_state_from_history  # noqa: E402

RANK_POINTS = {'A': 11, 'T': 10, 'K': 4, 'Q': 3, 'J': 2, '9': 0, '8': 0, '7': 0}
SUIT_ORDER = {'C': 3, 'S': 2, 'H': 1, 'D': 0}
RANK_ORDER = {'A': 7, 'T': 6, 'K': 5, 'Q': 4, '9': 3, '8': 2, '7': 1}
NULL_ORDER = {'A': 8, 'K': 7, 'Q': 6, 'J': 5, 'T': 4, '9': 3, '8': 2, '7': 1}
EMPTY_BIDS = {'D': 0, 'H': 0, 'S': 0, 'C': 0, 'N': 0}


class OnnxAgent:
    """SkatZero's DMCAgent interface on top of one exported ONNX model."""

    def __init__(self, path):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        self.session = ort.InferenceSession(path, options, providers=['CPUExecutionProvider'])

    def predict(self, state, raw=False):
        legal = state['legal_actions']
        keys = np.array(list(legal.keys()))
        if len(keys) == 1 and not raw:
            return keys, np.array([100.0])
        actions = list(legal.values())
        for i, action in enumerate(actions):
            if action is None:
                actions[i] = np.zeros(32)
                actions[i][keys[i]] = 1
        actions = np.array(actions, dtype=np.float32)
        obs = np.repeat(state['obs'].astype(np.float32)[None, :], len(keys), axis=0)
        history = np.repeat(state['history'].astype(np.float32)[None, :, :], len(keys), axis=0)
        values = self.session.run(['output'], {'obs': obs, 'history': history, 'actions': actions})[0]
        return keys, values

    def eval_step(self, state, raw=False):
        keys, values = self.predict(state, raw)
        best = int(np.argmax(values))
        info = {'values': {state['raw_legal_actions'][i]: float(values[i]) for i in range(len(keys))}}
        return keys[best], info

    # The interface SkatEnv.set_agents expects, unused here.
    def step(self, state):
        return self.eval_step(state)[0]

    def eval(self):
        pass

    def set_device(self, device):
        pass


def trick_winner(trick, contract):
    """trick: [(relative player, card)], contract D/H/S/C/G/N. The relative winner."""
    lead = trick[0][1]
    if contract == 'N':
        suit = lead[0]
        best = trick[0]
        for p, c in trick[1:]:
            if c[0] == suit and NULL_ORDER[c[1]] > NULL_ORDER[best[1][1]]:
                best = (p, c)
        return best[0]
    trump = None if contract == 'G' else contract

    def is_trump(c):
        return c[1] == 'J' or (trump is not None and c[0] == trump)

    def strength(c):
        if c[1] == 'J':
            return 100 + SUIT_ORDER[c[0]]
        if is_trump(c):
            return 50 + RANK_ORDER[c[1]]
        return RANK_ORDER[c[1]]

    lead_suit = trump if is_trump(lead) else lead[0]
    best = trick[0]
    for p, c in trick[1:]:
        if is_trump(c):
            if not is_trump(best[1]) or strength(c) > strength(best[1]):
                best = (p, c)
        elif not is_trump(best[1]) and c[0] == lead_suit and strength(c) > strength(best[1]):
            best = (p, c)
    return best[0]


class Driver:
    """One seat. SkatZero numbers players relative to the declarer: 0 is the
    declarer, 1 and 2 follow in play order; the arena's seats are absolute, so
    rel(seat) = (seat - declarer) mod 3 throughout."""

    def __init__(self):
        self.agents = []
        for gametype in ['D', 'G', 'N']:
            for i in range(3):
                self.agents.append(OnnxAgent(os.path.join(SZ, 'models', 'onnx', f'{gametype}_{i}.onnx')))
        self.env = SkatEnv()
        self.env.set_agents(self.agents)
        self.state, _ = self.env.game.init_game()
        assert self.state is self.env.game.state  # extract_state reads legal actions from it
        self.decisions = 0
        self.divergences = 0
        self.reset()

    def reset(self):
        self.my_seat = None
        self.declarer = None
        self.contract = None
        self.hand_game = False
        self.ouvert = False
        self.my_cards = []
        self.skat = None
        self.declarer_cards = None
        self.trace = []
        self.trick = []
        self.points = [0, 0]      # [declarer, defenders], as SkatZero keeps them
        self.to_play = None
        self.leader = None

    def rel(self, seat):
        return (seat - self.declarer) % 3

    def set_gametype(self, gametype):
        """SkatZero plays every suit game as Diamonds; the caller swaps colours."""
        trump = 'J' if gametype == 'G' else (None if gametype == 'N' else 'D')
        self.state['trump'] = trump
        self.env.game.round.trump = trump
        self.env.game.gametype = 'D' if gametype in 'CSHD' else gametype
        self.env.game.round.gametype = self.env.game.gametype

    # ---------------------------------------------------------------- discard
    def discard(self, hand12):
        """The best discard by SkatZero's own value across the game types; the
        arena does not say which contract will be played, so neither side
        knows, and this seat discards for the game it would have chosen."""
        best = None
        for game_mode in ['C', 'S', 'H', 'D', 'G', 'N']:
            cards = list(hand12)
            if game_mode in 'CSH':
                cards = swap_colors(cards, 'D', game_mode)
            rs = self.state
            rs.update({'self': 0, 'soloplayer': 0, 'current_hand': cards,
                       'others_hand': [c for c in init_32_deck() if c not in cards],
                       'skat': [], 'trace': [], 'played_cards': [[], [], []], 'trick': [],
                       'points': [0, 0], 'bids': [dict(EMPTY_BIDS) for _ in range(3)],
                       'bid_jacks': [0, 0, 0], 'blind_hand': False, 'open_hand': False,
                       'soloplayer_open_cards': [], 'pos': 0, 'drueck': True})
            self.set_gametype(game_mode)
            rs['actions'] = available_actions(cards)
            state = extract_state(rs, self.env.get_legal_actions())
            agent = self.agents[{'D': 0, 'G': 3, 'N': 6}[self.env.game.gametype]]
            _, values = agent.predict(state, raw=True)
            i = int(np.argmax(values))
            pair = rs['actions'][i]
            if game_mode in 'CSH':
                pair = swap_colors(pair, 'D', game_mode)
            if best is None or values[i] > best[0]:
                best = (float(values[i]), pair, game_mode)
        self.state['drueck'] = False
        return best[1][0], best[1][1], best[2]

    # ------------------------------------------------------------------- play
    def evaluate(self, hand, trace, points):
        """SkatZero's value for every legal card of the seat to move, in the
        arena's card names. A port of api.prepare_state_for_cardplay, without
        the torch import that module carries."""
        gt = self.contract
        me = self.rel(self.my_seat)
        rs = self.state
        rs['self'] = me
        rs['soloplayer'] = 0
        rs['points'] = list(points)
        rs['pos'] = self.rel(self.leader)
        rs['bids'] = [dict(EMPTY_BIDS) for _ in range(3)]
        rs['bid_jacks'] = [0, 0, 0]
        rs['blind_hand'] = self.hand_game
        rs['open_hand'] = False
        rs['soloplayer_open_cards'] = []
        rs['drueck'] = False
        hand = list(hand)
        skat = list(self.skat) if (me == 0 and not self.hand_game and self.skat) else []
        trace = list(trace)
        if gt in 'CSH':
            hand = swap_colors(hand, 'D', gt)
            skat = swap_colors(skat, 'D', gt)
            trace = [(p, swap_colors([c], 'D', gt)[0]) for p, c in trace]
        if self.ouvert and gt == 'N' and self.declarer_cards is not None:
            rs['open_hand'] = True
            rs['soloplayer_open_cards'] = list(self.declarer_cards)
        rs['current_hand'] = hand
        rs['skat'] = skat
        rs['trace'] = trace
        self.set_gametype(gt)
        played, others, trick, actions = construct_state_from_history(hand, trace, skat, trump=rs['trump'])
        rs['played_cards'] = played
        rs['others_hand'] = others
        rs['actions'] = actions
        rs['trick'] = trick
        state = self.env.extract_state(rs)
        mode = {'G': 3, 'N': 6}.get(gt, 0)
        _, info = self.agents[mode + me].eval_step(state, True)
        values = {}
        for card, value in info['values'].items():
            values[swap_colors([card], 'D', gt)[0] if gt in 'CSH' else card] = value
        return values

    def choose(self):
        me = self.rel(self.my_seat)
        values = self.evaluate(self.my_cards, self.trace, self.points)
        # SkatZero's api looks one step ahead when its card completes the trick
        # and wins it: the card is then valued by the best lead it leaves.
        if self.contract != 'N' and len(self.trick) == 2 and len(self.my_cards) > 1:
            looked = {}
            for card, value in values.items():
                full = self.trick + [(me, card)]
                if trick_winner(full, self.contract) != me:
                    looked[card] = value
                    continue
                points = list(self.points)
                points[0 if me == 0 else 1] += sum(RANK_POINTS[c[1]] for _, c in full)
                saved = self.leader
                self.leader = self.my_seat
                after = self.evaluate([c for c in self.my_cards if c != card], self.trace + [(me, card)], points)
                self.leader = saved
                looked[card] = max(after.values())
            values = looked
        return max(values, key=values.get)

    def card_played(self, seat, card):
        rel = self.rel(seat)
        self.trace.append((rel, card))
        self.trick.append((rel, card))
        if seat == self.my_seat and card in self.my_cards:
            self.my_cards.remove(card)
        if len(self.trick) == 3:
            winner = trick_winner(self.trick, self.contract)
            self.points[0 if winner == 0 else 1] += sum(RANK_POINTS[c[1]] for _, c in self.trick)
            self.trick = []
            self.leader = (self.declarer + winner) % 3
            self.to_play = self.leader
        else:
            self.to_play = (seat + 1) % 3

    # --------------------------------------------------------------- protocol
    def handle(self, cmd, args):
        if cmd == 'HELLO':
            return 'HELLO skatzero'
        if cmd == 'SEED':
            self.reset()
            self.decisions = 0
            self.divergences = 0
            return 'OK'
        if cmd in ('PROBE', 'BLIND'):
            return 'OK'
        if cmd == 'MAXBID':
            return 'MAXBID 0'
        if cmd == 'HANDGAME':
            return 'HANDGAME 0 GAME G'
        if cmd == 'DISCARD':
            first, second, game = self.discard(args[3:15])
            self.skat = [first, second]
            return f'DISCARD {first} {second} GAME {game}'
        if cmd == 'GAME':
            self.my_seat = int(args[0])
            self.declarer = int(args[1])
            self.contract = args[2]
            self.hand_game = args[3] == '1'
            self.ouvert = args[4] == '1'
            self.leader = int(args[5])
            self.to_play = self.leader
            hands = [args[7 + 10 * s: 17 + 10 * s] for s in range(3)]
            self.my_cards = list(hands[self.my_seat])
            self.declarer_cards = list(hands[self.declarer]) if (self.ouvert and self.contract == 'N') else None
            # The other two hands end here. They are not stored.
            if self.my_seat == self.declarer and not self.hand_game:
                self.skat = list(args[37:39])
                self.points = [sum(RANK_POINTS[c[1]] for c in self.skat), 0]
            else:
                self.skat = None
                self.points = [0, 0]
            self.trace = []
            self.trick = []
            return 'OK'
        if cmd == 'PLAY':
            if self.to_play != self.my_seat:
                self.divergences += 1
            self.decisions += 1
            card = self.choose()
            self.card_played(self.my_seat, card)
            return f'CARD {card}'
        if cmd == 'PLAYED':
            self.card_played(self.to_play, args[0])
            return 'OK'
        if cmd == 'STATS':
            return (f'STATS decisions {self.decisions} mismatches 0 divergences {self.divergences}'
                    ' declarer 0 defender 0 tricks 0 0 0 0 0 0 0 0 0 0')
        if cmd == 'QUIT':
            return 'BYE'
        return 'ERROR unknown ' + cmd


def main():
    driver = Driver()
    for line in sys.stdin:
        parts = line.split()
        if not parts:
            continue
        seq, cmd, args = parts[0], parts[1], parts[2:]
        try:
            reply = driver.handle(cmd, args)
        except Exception as failure:  # noqa: BLE001 -- one bad deal must not end the match
            import traceback
            traceback.print_exc(file=sys.stderr)
            reply = 'ERROR ' + str(failure).replace('\n', ' ')
        protocol_out.write(f'{seq} {reply}\n')
        protocol_out.flush()
        if cmd == 'QUIT':
            break


if __name__ == '__main__':
    main()
