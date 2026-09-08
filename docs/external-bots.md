# Seating an outside engine

Two engines that are not ours can now sit at the arena's table: **XSkat 4.0**
(Gunter Gerhardt, C, 2004) and **go-skat** (Dimitris Dranidis, Go, MIT, 2022).
Both run as helper processes and speak the line protocol below.

Neither is redistributed here. Both are fetched into `third_party/` and built
in place by `tools/build-external-bots.sh`, with a driver of ours copied in;
**no upstream source file is modified by either build.** A checkout that has
fetched neither builds and runs exactly as before — the contestants are
registered from a hook that looks for the binaries, so a missing engine costs
the arena an opponent rather than a run. See [`xskat.md`](xskat.md) for the
licence question and why the arrangement is the one it is.

## Why a whole game and not a position

Neither engine has an entry point meaning "given this position, play a card".
What each has is a game loop that deals, bids, plays and scores, and whose
card-play strength is inseparable from the memory it builds up while that loop
runs — XSkat learns a seat is void inside `calc_poss`, which is called as a side
effect of working out whose turn it is; go-skat's `analysePlay` runs on every
card and is what its tactics read.

So the helper plays a real game of its own, and the arena supplies the other two
seats' cards where the user interface used to supply a human's. Every card, ours
and theirs, goes through the engine's own path. The alternative — reconstructing
the engine's derived state at each decision — was rejected: it fails as a
quietly weaker player, and a quietly weaker player is a fictitious measurement.

The cost is that a real game needs all three hands present before the first
card, which means handing the helper cards its seat should not see. That is
treated as a risk to be measured rather than an assumption to be argued, which
is what the next section is for.

## The honesty control

`-Dskat.probe=<n>` asks the helper for every card **n+1 times**: once for real,
and n more times with the cards that seat has not seen reshuffled among the
places it cannot distinguish. A player that reads only its own hand answers the
same card every time. The run prints the count at exit.

Two things had to be true before the number meant anything, and neither was true
at first:

- **The probe must not perturb the engine's own randomness.** Both engines draw
  on a generator while choosing; letting each world advance it made the control
  report its own noise. XSkat's `seed[1]` is saved and restored per world;
  go-skat's `r` is swapped aside for a fresh stream and put back after.
  This alone moved XSkat from 89 apparent mismatches to 4.
- **The probe must not change the game it runs inside.** `make_best` is not a
  pure function — it marks cards in `gespcd`, updates the high-card tables, and
  the Null and Ramsch players keep tallies. All of it is snapshotted and
  restored. The acceptance test is that a probed match and an unprobed match
  print the same result, and they do.

Measured, 40 boards (240 games) against `greedy`:

| | decisions probed | answers that changed |
| --- | --- | --- |
| XSkat | 2,960 | **4 (0.14%)**, all as declarer |
| go-skat | 1,480 | **2 (0.14%)** |

So both are honest to within a fifth of a percent of their decisions. XSkat's
four are real: it is told the skat (`gespcd` marks it, and `gewinnstich` sums the
points still in hands, which is 120 minus what is taken minus the skat), and as
declarer in a hand game it should not know it. That is XSkat's own behaviour in
its own program, where one process plays every seat — but it is not something
the arena may credit it with.

## Blind mode

`xskat-blind` and `go-skat-blind` are the same engines dealt a world drawn
uniformly from the ones their seat cannot tell apart: their own cards and the
play so far are real, everything else is a sample. The dependence stays; the
advantage does not. When a seat then plays a card the sample had put somewhere
else, the sample is repaired by swapping it with an unplayed card of that seat —
both are unseen, so the world stays consistent and stays uniform.

What the leak is worth, over 150 boards (900 games):

```
xskat - xskat-blind = +0.398 game pts/game   95% CI [-0.550, +1.346]
```

Nothing measurable. Use `xskat` for a faithful reproduction of the program and
`xskat-blind` when the honesty of the number matters more; on the evidence so
far it does not change the answer.

## The protocol

One request a line, one reply a line, on stdin and stdout. Every request carries
a sequence number and every reply repeats it: a pipe one reply out of step is
otherwise invisible — the helper keeps answering, the answers are just all one
command late, and the only symptom is a player that measures weaker than it is.
That happened during development, twice, both times because the engine printed
its own commentary to stdout. Both drivers now keep the protocol on a duplicate
of fd 1 and point the engine's stdout at the null device.

Cards are two characters: suit `C S H D`, rank `A T K Q J 9 8 7`. Seats are
`0 1 2`, matching `SkatAi.Seat` ordinals. Contracts are `C S H D` for the suit
games, `G` for Grand, `N` for Null, `R` for Ramsch.

| request | reply | when |
| --- | --- | --- |
| `HELLO` | `HELLO <driver-id>` | handshake |
| `SEED <n>` | `OK` | once a deal; also resets the control's counters |
| `PROBE <n>` | `OK` | worlds per decision; 0 or 1 turns the control off |
| `BLIND <0\|1>` | `OK` | sampled world instead of the true deal |
| `MAXBID <seat> <10 cards>` | `MAXBID <n>` | the highest this engine would go |
| `HANDGAME <seat> <bid> <leader> <10 cards>` | `HANDGAME <0\|1> GAME <c>` | play without the skat? |
| `DISCARD <seat> <bid> <leader> <10 cards> <2 skat>` | `DISCARD <c1> <c2> GAME <c>` | the discard, and the game it wants |
| `GAME <myseat> <declarer> <contract> <hand> <ouvert> <leader> <bid> <30 cards> <2 skat>` | `OK` | start of play |
| `PLAY` | `CARD <c>` | our seat's turn |
| `PLAYED <c>` | `OK` | somebody else's card |
| `STATS` | `STATS decisions n mismatches m divergences d declarer x defender y tricks t1..t10` | end of deal |
| `QUIT` | `BYE` | shutdown |

`divergences` counts cards our engine called legal that the helper's own rules
did not, or that were not in that seat's hand at all. It is a rules cross-check
between two implementations that have never seen each other, and it has stayed
at zero.

A Ramsch is delegated to `GreedyAiProvider` rather than passed on: XSkat's
Schieberamsch and the arena's canon are not the same game, and a contestant that
played a different Ramsch would poison the only measurement it exists to make.
That is what the `delegated games` count in the control's summary is.

## Adding a third engine

Nothing in the Java side is XSkat- or go-skat-specific. Write a driver that
speaks the table above, put the binary where `ExternalBots` looks, and it is a
contestant. The two existing drivers are about 500 lines of C and 400 of Go, and
most of that is the two controls rather than the protocol.
