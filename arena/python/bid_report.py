"""
The auction played out of the bid labels: what the bidding gives away (the bidding, after the declarer-choice work).

    python arena/python/bid_report.py arena-logs/bid-labels/<run>.jsonl
    python arena/python/bid_report.py --self-test

Reads BidLabelMain's lines: per board, each seat's value of declaring at every
bid with the learned declarer after the skat (each distinct choice played out k
times on the true deal by the student), and what the shipped bidder does with
the seat's ten (its ceiling once something is bid, and whether it opens 18 when
nothing is). A bidding policy is (ceiling, opens 18) a seat. The auction is the
engine's: middlehand says to forehand, the winner hears rearhand, a tie goes
to the one who hears; if nobody has bid, forehand may open 18; nobody, and the
board is passed in (0, as in the arena's void mode). The declarer scores the
mean game value of its choice at the price -- the arena's game points; the
defenders score nothing.

Two policies A and B are paired as the arena pairs them: every board from all
three seats by both sides (A in one seat and B in two, then the other way
round), A's game points a seat-game minus B's, with a 95% interval over
boards. The policies:

  shipped        the file's
  oracle         the highest bid whose value is positive, by these labels -- a
                 ceiling, it sees the deal
  shipped+r      the shipped ceiling r rungs higher (r = 1, 2, 4), its openings as they are
  by-ceiling     says 18 into an empty auction only where it would hold 18: the
                 shipped bidder says 18 there on seats whose ceiling is 0 (it
                 weighs a Ramsch that a bid takes away) -- this takes those out
  hold-18        the other way: where it would say 18, it also holds 18
  always-open    says 18 into an empty auction on every seat, ceilings as shipped
  always-18      says and holds 18 on every seat, above that the shipped ceilings

What this cannot see: the shipped bidder re-prices on what the table says
(adaptive bidding), here it keeps its deal-time ceiling; and the labels are
the student's play-outs, as everywhere in this family -- the table decides.
"""

from __future__ import annotations

import argparse
import json
import sys

import numpy as np


def ladder():
    """BidValues.LADDER: every value a game can be worth, 18 and up."""
    values = set()
    for base, top in ((9, 11), (10, 11), (11, 11), (12, 11), (24, 4)):
        for m in range(2, top + 1 + 6 + 1):
            values.add(base * m)
    values.update((23, 35, 46, 59))
    return sorted(v for v in values if v >= 18)


LADDER = ladder()


def next_bid(current):
    for v in LADDER:
        if v > current:
            return v
    return 0


class Seat:
    """One seat of one board: value(b) of declaring at b, and the shipped bidder's numbers."""

    def __init__(self, raw):
        self.place = raw["place"]
        self.ten = tuple(raw.get("ten", ()))
        self.ceiling = raw["shipped"]["ceiling"]
        self.open18 = bool(raw["shipped"]["open18"])
        means = [float(np.mean(p["value"])) for p in raw["picks"]]
        self.values = {b: means[i] for b, i in raw["levels"]}
        self.most = max(self.values) if self.values else 0

    def value(self, b):
        """Declaring at b; above what any game covers, an overbid lost at about twice the bid."""
        if b in self.values:
            return self.values[b]
        return -2.0 * b


def board_seats(line):
    seats = {s["place"]: Seat(s) for s in line["seats"]}
    return [seats["forehand"], seats["middlehand"], seats["rearhand"]]


def auction(policies):
    """policies: (ceiling, open18) for forehand, middlehand, rearhand -> (declarer place index or None, price)."""
    def says(p, current, nxt):
        ceiling, opens = policies[p]
        return opens if current == 0 else nxt <= ceiling

    def duel(announcer, hearer, start):
        current = start
        while True:
            nxt = next_bid(current)
            if nxt == 0 or not says(announcer, current, nxt):
                return hearer, current
            current = nxt
            if current > policies[hearer][0]:
                return announcer, current

    w1, p1 = duel(1, 0, 0)
    w2, p2 = duel(2, w1, p1)
    if p2 == 0:
        return (0, 18) if policies[0][1] else (None, 0)
    return w2, p2


def game_points(seats, policies):
    declarer, price = auction(policies)
    out = [0.0, 0.0, 0.0]
    if declarer is not None:
        out[declarer] = seats[declarer].value(price)
    return out, declarer, price


def paired(lines, policy_a, policy_b):
    """Per board: A's game points a seat-game minus B's, every board from all three seats by both sides."""
    diffs, declared = [], []
    for line in lines:
        seats = board_seats(line)
        pa = [policy_a(s) for s in seats]
        pb = [policy_b(s) for s in seats]
        a_total = b_total = 0.0
        for s in range(3):
            # A at seat s, B at the other two; then B at s, A at the other two.
            one = [pa[i] if i == s else pb[i] for i in range(3)]
            pts, d, _ = game_points(seats, one)
            a_total += pts[s]
            b_total += sum(pts[i] for i in range(3) if i != s)
            declared.append(d is not None)
            two = [pb[i] if i == s else pa[i] for i in range(3)]
            pts, d, _ = game_points(seats, two)
            b_total += pts[s]
            a_total += sum(pts[i] for i in range(3) if i != s)
            declared.append(d is not None)
        diffs.append((a_total - b_total) / 9.0)
    return np.asarray(diffs), float(np.mean(declared))


def interval(values):
    values = np.asarray(values, dtype=np.float64)
    if len(values) < 2:
        return float(values.mean()) if len(values) else float("nan"), float("nan")
    return float(values.mean()), float(1.96 * values.std(ddof=1) / np.sqrt(len(values)))


def shipped(seat):
    return seat.ceiling, seat.open18


def oracle(seat):
    good = [b for b, v in seat.values.items() if v > 0]
    ceiling = max(good) if good else 0
    return ceiling, ceiling >= 18 and seat.value(18) > 0


def raised(r):
    def policy(seat):
        if seat.ceiling == 0:
            return 0, seat.open18
        c = seat.ceiling
        for _ in range(r):
            c = next_bid(c) or c
        return c, seat.open18
    return policy


def by_ceiling(seat):
    return seat.ceiling, seat.ceiling >= 18


def hold_18(seat):
    return (max(seat.ceiling, 18) if seat.open18 else seat.ceiling), seat.open18


def always_open(seat):
    return seat.ceiling, True


def always_18(seat):
    return max(seat.ceiling, 18), True


POLICIES = [("oracle", oracle), ("shipped+1", raised(1)), ("shipped+2", raised(2)), ("shipped+4", raised(4)),
            ("by-ceiling", by_ceiling), ("hold-18", hold_18), ("always-open", always_open), ("always-18", always_18)]


def report(lines):
    seats = [s for line in lines for s in board_seats(line)]
    n = len(lines)
    print(f"{n:,} boards, {len(seats):,} seats")
    ceilings = np.array([s.ceiling for s in seats])
    opens = np.array([s.open18 for s in seats])
    v18 = np.array([s.value(18) if s.most >= 18 else np.nan for s in seats])
    print(f"  the shipped bidder: bids at all on {np.mean(ceilings > 0):.1%} of seats, opens 18 on {np.mean(opens):.1%};"
          f" median ceiling of those it bids {np.median(ceilings[ceilings > 0]) if (ceilings > 0).any() else 0:.0f}")
    print(f"  declaring at 18 (the learned declarer): positive on {np.nanmean(v18 > 0):.1%} of seats; "
          f"of the seats the shipped bidder passes, positive on {np.nanmean(v18[ceilings == 0] > 0):.1%}, "
          f"mean {np.nanmean(v18[ceilings == 0]):+.1f}; of those it bids, mean {np.nanmean(v18[ceilings > 0]):+.1f}")
    above = [s for s in seats if s.ceiling > 0]
    lost = [max((v for b, v in s.values.items() if b > s.ceiling), default=np.nan) for s in above]
    print(f"  above its ceiling a positive value is left on {np.nanmean(np.array(lost) > 0):.1%} of the seats it bids")
    unheld = (ceilings == 0) & opens
    if unheld.any():
        print(f"  says 18 into an empty auction but would not hold it: {np.mean(unheld):.1%} of seats, "
              f"positive at 18 on {np.nanmean(v18[unheld] > 0):.1%}, mean {np.nanmean(v18[unheld]):+.1f}")
    silent = (ceilings == 0) & ~opens
    if silent.any():
        print(f"  passes outright: {np.mean(silent):.1%} of seats, "
              f"positive at 18 on {np.nanmean(v18[silent] > 0):.1%}, mean {np.nanmean(v18[silent]):+.1f}")
    base, rate = paired(lines, shipped, shipped)
    print(f"\n  shipped against itself: {base.mean():+.3f} (must be 0); boards declared {rate:.1%}")
    print("  policy minus shipped, game points a seat-game, every board from all three seats by both sides:")
    for name, policy in POLICIES:
        d, rate = paired(lines, policy, shipped)
        m, h = interval(d)
        print(f"    {name:12s} {m:+.3f} [{m - h:+.3f}, {m + h:+.3f}]   boards declared {rate:.1%}")


# ---------------------------------------------------------------- self-test


def fake_seat(place, ceiling, open18, values):
    return {"place": place, "shipped": {"ceiling": ceiling, "open18": open18},
            "levels": [[b, i] for i, b in enumerate(sorted(values))],
            "picks": [{"value": [values[b]] * 2} for b in sorted(values)]}


def self_test():
    ok = True

    def check(name, passed):
        nonlocal ok
        ok &= bool(passed)
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")

    check("the ladder starts 18 20 22 23 24 27", LADDER[:6] == [18, 20, 22, 23, 24, 27] and 264 in LADDER)
    # Forehand holds to 30, middlehand says to 24: forehand wins at 24, rearhand passes.
    check("forehand holds and wins at middlehand's last word", auction([(30, True), (24, True), (0, False)]) == (0, 24))
    check("middlehand outbids forehand at the next rung", auction([(23, True), (40, True), (0, False)]) == (1, 24))
    check("rearhand outbids the winner", auction([(23, True), (20, True), (36, True)]) == (2, 24))
    check("a tie goes to the one who hears", auction([(24, True), (24, True), (0, False)]) == (0, 24))
    check("nobody bids, forehand opens 18", auction([(30, True), (0, False), (0, False)]) == (0, 18))
    check("nobody at all: passed in", auction([(30, False), (0, False), (0, False)]) == (None, 0))
    check("middlehand will not open: rearhand opens against forehand",
          auction([(20, True), (40, False), (22, True)]) == (2, 22))

    seats = [fake_seat("forehand", 0, False, {18: -10.0}), fake_seat("middlehand", 18, True, {18: 30.0, 20: 30.0}),
             fake_seat("rearhand", 0, False, {18: 50.0, 20: 50.0, 22: 50.0})]
    lines = [{"board": 0, "seats": seats}]
    d, _ = paired(lines, shipped, shipped)
    check("a policy against itself is zero", abs(d[0]) < 1e-12)
    # The oracle sees rearhand's 50 and middlehand's 30; shipped passes rearhand.
    d, _ = paired(lines, oracle, shipped)
    check(f"the oracle takes what shipped leaves ({d[0]:+.2f} > 0)", d[0] > 0)
    s = Seat(seats[2])
    check("value above every game covered is an overbid", s.value(24) == -48.0 and s.value(20) == 50.0)
    check("raising keeps a pass a pass", raised(2)(Seat(seats[0])) == (0, False) and raised(2)(Seat(seats[1]))[0] == 22)
    fh = Seat(fake_seat("forehand", 0, True, {18: 5.0}))
    check("by-ceiling drops an opening it would not hold", by_ceiling(fh) == (0, False))
    check("hold-18 holds where it opens, and only there",
          hold_18(fh) == (18, True) and hold_18(Seat(seats[0])) == (0, False))
    check("always-open opens, always-18 also holds",
          always_open(Seat(seats[0])) == (0, True) and always_18(Seat(seats[0])) == (18, True)
          and always_18(Seat(seats[1])) == (18, True))
    print("self-test " + ("passed" if ok else "FAILED"))
    return ok


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lines", nargs="?")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return 0 if self_test() else 1
    if not args.lines:
        parser.error("a .jsonl file, or --self-test")
    with open(args.lines) as f:
        lines = [json.loads(row) for row in f if row.strip()]
    report(lines)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
