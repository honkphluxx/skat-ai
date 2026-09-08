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
its own commentary to stdout: go-skat its grand evaluation, and the mfrasca
fork of XSkat its game value. Pristine XSkat prints nothing on this path — its
one `printf` is the auto-mode score line, which the driver never reaches — so
for that source the separation is insurance. Both drivers keep the protocol on
a duplicate of fd 1 and point the engine's stdout at the null device anyway,
because "this build happens not to print" is not a property worth depending
on.

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

## Getting the engines, and building them on Windows

**xskat.de is gone** (checked 2026-09-08). The source survives in two places
that are not going anywhere:

- **Debian's pool**, which is the pristine 4.0 upstream tarball as Gerhardt
  released it: `http://deb.debian.org/debian/pool/main/x/xskat/xskat_4.0.orig.tar.gz`
  (Debian 13 still ships `xskat 4.0-9`; `snapshot.debian.org` has every older
  one). Unpack it straight into `third_party/xskat`.
- **`github.com/mfrasca/xskat`**, which is what this project was first
  developed against. It is a *renamed modified version* in the sense clause 2.b
  of the licence means, and it changes more than the version string: it adds a
  formatted, human-readable game value (`spwert_text`), three text entries for
  it, and a `printf` of it at the end of `calc_result`.

**The two are the same player**, and that is measured rather than assumed: the
same match against `greedy` — 40 boards, seed 1 — produces byte-identical
reports from both, down to the declarer win rate and the confidence interval.
The fork's arithmetic for `spwert` is a refactor of the same increments; only
the explanation string is new. So every number in these docs stands whichever
source you build, and the build script supports both — it asks `skat.h` whether
`spwert_text` is declared and defines `HAVE_SPWERT_TEXT` when it is, because
`calc_result` writes into that buffer unconditionally and the driver has to
allocate it before anything can reach a null pointer.

go-skat is `git clone https://github.com/dranidis/go-skat third_party/go-skat`.
Its build fetches two dependencies from the Go module proxy, so it wants a
working network the first time.

### The compiler

Only four of XSkat's files are compiled here — `skat.c`, `null.c`, `ramsch.c`,
`text.c` — and between them they include `stdio`, `stdlib`, `string`, `ctype`
and `time` and nothing else. **There is no X11 and no POSIX in the build**: the
driver replaces the two files that had them, and its own two platform calls
(`dup`, `/dev/null`) are `#ifdef`-ed to `_dup` and `NUL`. So any C compiler
that accepts K&R function definitions will do it.

**One flag is not optional on a modern compiler.** Through GCC 14, `int f();`
meant "takes unspecified arguments" and XSkat's empty-parenthesis declarations
matched their K&R definitions. GCC 15 defaults to `-std=gnu23`, where it means
"takes nothing", and every function in `defs.h` collides with its own
definition — forty copies of *number of arguments doesn't match prototype*.
w64devkit ships a GCC new enough to do this. `-std=gnu89` is the answer, and
the build script detects it rather than assuming it: it compiles the two-line
program that has the problem and keeps the first dialect that builds it, so a
compiler nobody here has tried gets the right answer rather than the one that
suited the compiler that was.

In order of least trouble on Windows:

1. **[w64devkit](https://github.com/skeeto/w64devkit/releases)** — one zip, no
   installer, no registry. Unpack it anywhere, run `w64devkit.exe` for a shell
   with `gcc` on PATH (or add its `bin\` to PATH and use Git Bash), then
   `./tools/build-external-bots.sh` as usual. This is the recommended route.
2. **MSYS2**: `pacman -S mingw-w64-ucrt-x86_64-gcc`, then build from the UCRT64
   shell.
3. **Cross-built from Linux**, which is how the first Windows binary was made:
   ```bash
   CC="x86_64-w64-mingw32-gcc -std=gnu89 -O2 -w -DDEFAULT_LANGUAGE=\"english\""
   $CC -Dmain=xskat_main_unused -c skat.c -o w_skat.o
   $CC -c null.c   -o w_null.o
   $CC -c ramsch.c -o w_ramsch.o
   $CC -c text.c   -o w_text.o
   $CC -c skatklar_driver.c -o w_driver.o
   x86_64-w64-mingw32-gcc w_*.o -static -o skatklar-xskat.exe
   ```
   `-static` matters: without it the binary wants `libwinpthread-1.dll` beside
   it. With it, the only imports are `KERNEL32` and `msvcrt`.

**Not MSVC.** `cl.exe` is the one toolchain worth warning about: XSkat is K&R C
throughout and calls a dozen functions that are never declared, which recent
MSVC treats as an error rather than a warning in its C modes. Fighting that
means editing Gerhardt's sources, which is the one thing this arrangement is
built to avoid.

The Go helper needs `-o skatklar-goskat.exe` on Windows — `go build -o <name>`
does not add the suffix the way MinGW's `gcc -o <name>` does, and a file
without it is not something `CreateProcess` will start. The build script
handles that from `uname -s`; it is written down here because a helper that
builds and cannot be launched looks exactly like a helper that did not build.

## Adding a third engine

Nothing in the Java side is XSkat- or go-skat-specific. Write a driver that
speaks the table above, put the binary where `ExternalBots` looks, and it is a
contestant. The two existing drivers are about 500 lines of C and 400 of Go, and
most of that is the two controls rather than the protocol.
