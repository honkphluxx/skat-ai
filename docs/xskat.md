# XSkat as an arena opponent — feasibility

Exploration record, 2026-09-08. **Implemented the same day** -- both engines are
arena contestants now; see [`external-bots.md`](external-bots.md) for the
adapter, the protocol and the honesty control, and section 9 below for what the
first matches said. Everything else here is the exploration as it was written,
and every number in it came out of a build made during it.

The short version: **XSkat is not GPL**, the licence question the exploration
started from does not exist, and no wrapper process is needed to answer it. A
separate process is still the right architecture, for reasons that are about C
globals rather than about copyright. XSkat already runs headless, plays all
three seats on deals we choose, is bit-for-bit reproducible, and does 17,000
games a second. What it costs to seat it at *our* table, one seat at a time, is
a stub layer of 46 symbols and a patch that forces a contract.

## 1. The licence

Every source file in XSkat 4.0 carries this, and there is no other licence text
in the tree:

```
    Copyright (C) 2004  Gunter Gerhardt

    This program is free software; you can redistribute it freely.
    Use it at your own risk; there is NO WARRANTY.

    Redistribution of modified versions is permitted
    provided that the following conditions are met:
    1. All copyright & permission notices are preserved.
    2.a) Only changes required for packaging or porting are made.
      or
    2.b) It is clearly stated who last changed the program.
         The program is renamed or
         the version number is of the form x.y.z,
         where x.y is the version of the original program
         and z is an arbitrary suffix.
```

That is a custom permissive licence. There is no copyleft, no source-disclosure
obligation, and no viral clause — nothing to link away from. Debian ships xskat
in `main` under exactly this text; the `debian/*` packaging is separately
GPL-2+, which is the most likely source of the "xskat is GPL" belief, along with
package indexes that guess a licence field. The GitHub mirror used for this
exploration is a live demonstration of clause 2.b working as intended: its
version string reads `4.0.mfrasca`, changed, as its commit message says, "as
requested by software license".

What the licence grants clearly: redistribution, and redistribution of modified
versions under a suffixed name. What it does not say anything about: whether the
code may be incorporated into a larger work and shipped under someone else's
terms. Rather than take a position on that silence, the conservative arrangement
avoids the question entirely, and it is one this repository already runs:

- XSkat is **fetched, never redistributed** — a pinned tarball or submodule
  under `third_party/xskat`, built locally, exactly like `third_party/jskat`.
- XSkat is a **measuring instrument, never a shipped component**. It is not
  strong enough to be worth shipping anyway (§5), so nothing is lost.
- A `THIRD_PARTY_NOTICES.md` row states the licence and that it is not
  redistributed here, as the JSkat row does.
- Any local patch (§7) lives as a patch file in this repository, applied at
  build time. If a patched build ever left this machine it would go out as
  `4.0.skatklar<n>` with a line saying who changed it — clause 2.b, satisfied.

Game records XSkat produces are its output, not a derivative of its source, and
carry no obligation. (This is a reading of the text, not legal advice; the point
of the arrangement above is that it does not depend on the reading.)

Take the pristine 4.0 tarball from Debian's pool —
`deb.debian.org/debian/pool/main/x/xskat/xskat_4.0.orig.tar.gz` — rather than
the GitHub mirror, which is a renamed modified version. **xskat.de itself is
gone as of 2026-09-08**, which is worth stating plainly: the licence's whole
premise is that the program may be passed on freely, and Debian passing it on
for twenty years is that premise working. See
[`external-bots.md`](external-bots.md) for the fetch and the Windows toolchain.

## 2. What XSkat is, and where it sits

Gunter Gerhardt's XSkat 4.0 (25 May 2004) is one of the oldest Skat programs for
Unix: 20,000 lines of K&R C, X11 UI, no dependencies beyond libX11. It is also
the field's standard weak-but-fast reference. Buro's group used it as the
baseline in the 2009 IJCAI comparison; Bernie (information-set UCT) was built on
it; and the SkatGame app that hosts kermit, zoot and theCount lists XSkat
alongside them as "a fast but weak program". That is precisely the rung this
arena is missing: `greedy` and `random` are ours, `jskat-*` is one other
codebase, and everything else on the ladder is a variant of our own search.

Where the AI lives:

| file | lines | what |
| --- | --- | --- |
| `skat.c` | 3158 | dealing, bidding, discard, suit/grand card play, the game loop |
| `ramsch.c` | 685 | Ramsch and Schieberamsch play |
| `null.c` | 436 | Null play, including revolution |
| `text.c` | 403 | string tables |
| `xio.c`, `xdial.c` | 6817 | X11 rendering and dialogs — the **only** files that include X11 |
| `cards.c` | 4788 | card images as GIF bytes, not AI |

The AI files include no X11 header and reference no X type. The whole player is
about 4,300 lines of decision code.

## 3. What was measured

Ubuntu container, gcc 13, `libx11-dev` present; upstream `Makefile` unchanged.

```
make                       # builds clean apart from warnings
unset DISPLAY
./xskat -auto 3000         # three computers play 3000 games, prints three scores
```

**Headless already.** `-auto n` sets `numsp=0`, so no window is ever created and
`hndl_events` iterates over nothing. No X server is needed at run time; libX11
is needed only to link. 3000 games ran with `DISPLAY` unset.

**Fast.** 5000 games in 0.29 s wall — about 17,000 complete games per second,
single-threaded, including bidding and discard. Against the cost of our search
player this is free.

**Reproducible.** `skat.c:295` does `setrnd(&seed[1],seed[0])` at every deal: the
AI's random stream is re-derived from the deal seed before each game, so play is
a pure function of the deal. Two runs 1.1 s apart produced byte-identical logs
(same md5) both for a bare distribution matrix and for one with an explicit
`random_seed` line. This is a better starting point than JSkat, whose
irreproducibility is documented in the AI notes.

**We can choose the boards.** `-game <file>` (or `-game -` for stdin) reads
predefined deals as a 4×8 matrix — rows Diamond, Heart, Spade, Club; columns
A 10 K Q J 9 8 7; each cell 1–3 for the seat holding that card, 0 for the skat:

```
  2  2  2  2  2  0  3  3
  2  2  2  2  2  3  3  3
  1  1  1  1  1  3  3  3
  1  1  1  1  1  0  3  3
```

A `random_seed <n> <skip> <dealer>` line sets the stream and the dealer instead.
Deals may be rotated with a trailing `L`/`R`, and `-start` picks who deals —
between them, any of our boards can be presented to XSkat at any seat rotation.

**The log is a complete transcript.** `-log -` writes to stdout: one block per
game giving the play trick by trick in seat columns, the deal, the skat before
and after the discard, the winning bid, the contract, and the result. In the
unformatted output an underscore marks the seat that led the trick and
UPPERCASE the seat that won it (`xdial.c:2592-2593` — `prot1.anspiel[i]` and
`prot1.gemacht[i]`). Everything needed to replay a game through our engine is
there.

**What kind of player it is.** 3000 auto games, default strength, default
variants:

| | |
| --- | --- |
| boards with a declarer | 2986 of 3000 (0.5% passed in) |
| Grand | 863 (28.9% of declared games) |
| Clubs / Spades / Hearts / Diamonds | 650 / 558 / 514 / 383 |
| Null | 18 (0.6%) |
| declarer lost | 1084 (36.3%) |

Read two things from that. It declares nearly every board, unlike JSkat's
algorithmic player at 6.2% — so its tournament points come from playing, not
from abstaining, and a match against it will not need the declaring/defending
split to be readable. And it almost never declares Null, so it exercises that
contract barely at all; a Null conclusion cannot be drawn from an XSkat match.

`-s1 -s2 -s3` set per-seat strength from -4 (weak) to 0 (default). Note what
that dial actually is: `strateg[]` is read in exactly one place, `calc_rw` at
`skat.c:485`, which is bidding. It makes XSkat bid more timidly. It does not
weaken its card play.

## 4. Three shapes for the integration

### Shape A — the unmodified binary, all three seats, our boards

No change to XSkat at all. Write our board set as distribution matrices, run
`xskat -auto n -game deals.txt -log -`, parse the transcripts. About a day.

It does not give a head-to-head match: XSkat fills all three seats, so what
comes back is XSkat's score against XSkat, which in Skat says little about how
it would do against us. What it does give is worth more than that:

1. **An independent implementation to check our rules against.** Feed the same
   boards to XSkat and to our `GameEngine`, replay XSkat's transcript through
   our engine, and compare legality, trick winners, matadors, game values and
   won/lost verdicts on tens of thousands of deals. Two codebases that have
   never seen each other agreeing on the game value of 30,000 boards is a much
   stronger statement about the canon than any test we can write against
   ourselves — and cheap, because both sides are fast and deterministic.
2. **A licence-clean corpus, immediately.** 17,000 games a second of complete
   records — bids, discards, every card, results — reproducible from a seed and
   under no data licence at all. The belief model is the one part of the plan
   that genuinely wants a corpus, and it has been waiting on an unanswered
   permission mail since August. This will not replace ISS: a card-location
   model trained on XSkat self-play learns XSkat's habits, and its ceiling is
   XSkat. It is a warm start and, more usefully, a way to build and validate the
   whole encoding-training-serving pipeline now instead of when the mail is
   answered.
3. A first, rough reading of where it sits.

### Shape B — one seat, driven by the arena

The real contestant: a `SkatAiProvider` whose session owns an XSkat subprocess
and answers `chooseCard` from it, alongside our players in an ordinary duplicate
match.

`iscomp(s)` is `s >= numsp` — seat roles are positional, seats below `numsp` are
"human". So with `numsp=2`, seat 2 is XSkat's and seats 0 and 1 are ours, and the
deal matrix plus `-start`/rotation puts our cards wherever they need to be. XSkat
then plays a real game of its own: it deals, it infers, it maintains its void
tracking and its card-location state across the whole hand. We supply the other
two seats' cards where the UI would have supplied a human's, and read its card
where the UI would have drawn it. Nothing about its state is faked, which is what
makes this safe — a snapshot-injection design would have to reconstruct
`hatnfb`, `inhand`, `high`, `gespcd` and more at every decision, and would be
subtly wrong forever.

What it needs:

- **A stub UI.** The three AI objects reference 46 symbols defined in
  `xio.c`/`xdial.c`: `b_text calc_desk clear_info clr_desk conames di_ansage
  di_copyr di_delliste di_dicht di_grandhand di_hand di_info di_options di_proto
  di_result di_schieben di_spiel di_verdoppelt di_weiter di_wiederweiter
  do_msaho draw_skat drop_card exitus givecard hndl_events home_skat info_reiz
  info_spiel info_stich initscr inv_box nimm_stich put_box put_fbox putmark
  rem_box remmark revolutionscr setcurs show_hint stdwait textarr usrname waitt
  xinit`. Most are drawing calls that become no-ops; the `di_*` dialogs already
  carry a `numsp == 0` path for auto mode that answers them, which is the
  behaviour to copy. `hndl_events` becomes "read one line from stdin and apply
  it as the human seat's move". `drop_card` becomes "write the card to stdout".
  A few hundred lines, and it drops the X11 link entirely.
- **A way to force the contract.** The arena fixes the contract so that card
  play is compared without bidding differences, and XSkat has no option for
  that: `-game` sets the cards, not the game. Bypassing `REIZEN` means setting
  `spieler`, `trumpf`, `handsp`, `gedr` and the discard, then entering
  `spielphase()` — and getting every derived global that bidding and discarding
  would have set (`inhand`, `sptruempfe`, `maxrw`, `karobube`, the `hatnfb`
  initialisation) right along the way. This is the one genuinely risky piece:
  the failure mode is not a crash but a quietly worse player, and a quietly
  worse player is a fictitious measurement. It needs a control — the same boards
  played through the forced path and through XSkat's own auction, checked for
  the same score where the contract came out the same.
  The alternative is to run these matches in full-auction mode and let XSkat bid
  for itself, which costs nothing and measures bidding and play together.
- **One process per seat per thread.** Every piece of XSkat's state is a
  file-scope global. Two games cannot share a process, and the arena runs boards
  in parallel. A pooled subprocess per arena thread, reset between deals, is the
  shape; it is also why in-process JNI would be the wrong call even though this
  repository already has a native module. At 60 µs of thinking per whole game,
  the pipe is the only cost that matters and it is still nothing.

### Shape C — link the AI in-process

Compile `skat.c`, `null.c`, `ramsch.c`, `text.c` and the stubs into a shared
library and call it through JNI or FFM. Removes a pipe we do not need to remove
and reintroduces the global-state problem inside our own JVM. Not recommended.

## 5. Rule variants to pin down first

XSkat plays a superset of our canon and its defaults are not ours. Before any
number means anything, fix: `-noramsch -nosramsch -nokontra -nobock -nospitze
-norevolution -noklopfen -noschenken`, and settle `-oldrules` vs `-newrules`,
which changes game values (`skat.c:1427`, `:1500`, `:1520`) — the Grand ouvert
and hand-game multipliers. `-opt` should point at a scratch file so the run does
not read or write `~/.xskat.opt`, and `-nolist` keeps it away from the score
list. Everything else our canon settles in `docs/rules.md` needs the same
treatment: a differential run under Shape A is how the mismatches get found.

## 6. Recommendation

Do Shape A. It is a day's work, it needs no patch to XSkat, and the differential
rules check alone justifies it — an independent second implementation agreeing
with our engine on 30,000 boards is a stronger statement than anything else
available, and we can have it this week. The corpus is a second dividend and
unblocks belief-pipeline work that is currently waiting on an email.

Do Shape B when the arena actually wants XSkat as a rung on the ladder, and
budget the contract-forcing patch honestly — it is the part that can produce a
believable wrong answer. Consider running those matches in full-auction mode
first, which needs no patch at all.

Do not ship it. It is a benchmark.

## 7. Kermit, zoot and theCount

Asked alongside XSkat, and the answer is the opposite in every respect.

The three are Michael Buro's group's players — the "Muppets" — and they are the
top of the field. Kermit is PIMC search over determinized worlds with a learned
state evaluation, inference from void suits and learned card-location
histograms, and precomputed 6- and 7-trick endgame tables; it reached expert
human strength in 2008 and the 2013 recursive-Monte-Carlo paper measures its
solver at about 68 exact world evaluations per second on one i7 core. theCount is
the same line with better inference. Zoot is named on Buro's page and in the
SkatGame app as the third of the three, but no algorithm description for it
appears in the papers checked here (the 2013 recursive-MC paper and Rebstock's
2019 thesis do not mention it by name) — treat its internals as unknown.

None of them is obtainable. There is no source, no library, no binary: Rebstock's
thesis says plainly that "due to the commercial natures of these AI systems,
their implementation details are not readily available". The only two places
they exist are the International Skat Server and the SkatGame app, both run by
the same group. So they cannot be an arena contestant in any form. What they can
be is a calibration rung: ISS states that its message protocol is open and that
free client software allows connecting your own program, and it hosts all three
bots — writing an ISS client and playing our player against kermit would give an
absolute reading that nothing else can.

The cost is the point. Games on ISS are played in real time, so a match large
enough to have an error bar is days of wall clock, not the seconds a local
opponent costs; there are no duplicate deals, no common random numbers and
therefore no paired difference, so the noise that the arena was built to cancel
comes straight back. That makes it a calibration exercise to run once, when
there is something worth calibrating — not part of the development loop.

Two things worth noting while we are here. ISS's own page now states that "All
games are stored and made available for free to everyone", and offers 9.1M ISS
games plus 2.24M games from the SkatGame app, updated monthly. That is still not
a licence, which is exactly what the unanswered August mail was asking for, but
it is the site's own wording about the archive and it belongs in the record. And
the SkatGame app itself seats XSkat next to the Muppets as its weak opponent —
the field's own judgement about what XSkat is for, and it agrees with §6.

## 8. Can kermit be run locally, and what else can we actually run?

No. Kermit, zoot and theCount exist as processes on machines Buro's group
operates, and nowhere else: no source, no binary, no library, no offline mode.
The SkatGame app reaches them over the network in its "BotSkat" room rather than
shipping them. ISS's own download page offers exactly two things — `skatgui.jar`
and `clients.tgz`, "client source code ... if you like to connect a skat program
to ISS" — and no AI source at all. So the only way our player ever meets kermit
is as a network opponent, in real time, one game at a time.

That route is cheaper than it looked, though, because someone has already walked
it (§8.2).

### 8.1 The survey

Everything found that is both open and actually runnable:

| | licence | what it is | use to us |
| --- | --- | --- | --- |
| **JSkat** | Apache-2.0 | already vendored | on the ladder |
| **XSkat** | custom permissive | §1–§6 | the weak reference, free and reproducible |
| **[go-skat](https://github.com/dranidis/go-skat)** | **MIT** | 17k lines of Go: heuristic player, sampled alpha-beta with a time budget, card inference, an ISS client | **the one real find** — see below |
| `skat-buddy` | MIT | 3.1k lines of Python | rules and a state machine only; no player |
| `gskat` | GPL-3 | GTK game; its own README says not to expect much AI | no |
| `svenpruefer/skat` | GPL-3 | Scala library, README says "Features: None so far" | no |
| `SkatBot` (CS229) | none stated | student project, needs MATLAB | no |

And everything strong that is *not* runnable: kermit, zoot, theCount (no source,
commercial); Edelkamp's player from *Challenging Human Supremacy in Skat* and the
2025 outer-learning work (no availability statement in either paper, and it
benchmarks against the commercial Fox 1.2); Bernie, the UCT player built on
XSkat, which survives only as a description in the literature.

That is the whole field. There is no third open implementation of a strong Skat
player waiting to be found — if there were, the papers would be benchmarking
against it instead of against XSkat.

### 8.2 go-skat

Dimitris Dranidis's `go-skat`, MIT, 2022. Built and run here: Go 1.24 with the
two dependencies vendored from tagged GitHub checkouts (the module proxy is not
on this container's egress allowlist), then `go-skat -auto -n 12 -r 42` played
twelve three-CPU games in 10.5 s — about 0.9 s per game, which is a search
player's cost, not a heuristic's. It declared all twelve and won 8.

Its shape is close to ours: `cpuplayer.go` is a 2000-line heuristic, `inference.go`
tracks what each opponent can still hold, and `minmaxplayer.go` + `skatminmax.go`
run alpha-beta over sampled worlds under a timeout — its own logs show "20 Worlds"
searches ending in "AB: TIMEOUT" after five seconds. Its 4400-line test file for
the heuristic alone says the author took it seriously. Strength is unknown and
nobody has published a number for it; our arena is the instrument that would
settle that, which is the point.

Two reasons to care:

1. **A third independent codebase for the ladder**, permissively licensed, at
   roughly the right strength band to be interesting — probably above XSkat and
   somewhere near our own search player. Integration cost is a subprocess and a
   line protocol, the same shape as XSkat Shape B, and easier: it is modern Go
   with no global-state or X11 problem, and adding a "play one card from this
   position" mode to it is a small patch to a codebase that already has an
   `issplayer` and an `htmlplayer` seam.
2. **It contains a working ISS client** — `client_iss.go`, 535 lines, dialling
   `skatgame.net:7000` — and a comment in that file preserves a complete recorded
   game from 2017 whose header reads `P0[zoot] P1[goskat] P2[bernie]`. Someone
   sat this program down at a table with zoot and it played the hand out. That
   turns "write an ISS client" from a research project into reading 535 lines of
   MIT-licensed Go that is known to have worked, and it hands us the wire format
   for free.

So the honest answer to "can we play kermit" is: not locally, ever, but the
client that would let us play it over the wire already exists and is readable.
The objection in §7 stands unchanged — real-time play, no duplicate deals, no
common random numbers, days of wall clock for one interval — so this remains a
calibration to run once against a finished player, not part of the loop.

### 8.3 What this changes about the plan

The ladder we can actually build locally is `random` → `greedy` → `XSkat` →
`JSkat` → `go-skat` → ours → `solver`, with every rung reproducible, duplicated
and paired. Above that there is nothing open at all, and the ceiling of the whole
exercise stays what it already was: the double-dummy solver for the absolute
bound, and — once there is something worth calibrating — one slow ISS session
against the Muppets for the human-expert reading.

XSkat is still the first thing to do, for the reasons in §6. go-skat is the
better second opponent, and worth measuring before any more effort goes into
JSkat.

## 9. What happened when they were seated

Both engines went in as helper processes, XSkat with no upstream file modified
at all (the driver supplies the 46 X11 symbols and its own `main`; `skat.c` is
compiled with `-Dmain=` and nothing else). Shape B, not Shape A -- once the
driver was replicating `do_spielen`'s body anyway, forcing the contract turned
out to be the small part.

Measured in the container, duplicate deals, contracts from the auction:

```
xskat   - greedy      = +27.800 game pts/game   95% CI [+15.991, +39.609]   40 boards
go-skat - greedy      = +21.817 game pts/game   95% CI [ +9.993, +33.641]   20 boards
xskat   - go-skat     =  +1.906 game pts/game   95% CI [ -3.115,  +6.926]   60 boards
```

So the two outside engines are a rung above `greedy` and, on the evidence so
far, indistinguishable from each other -- 417 boards would be needed to separate
them. Rule violations: zero on both sides, and zero disagreements between our
engine's legality and theirs across every card played.

Section 4 worried that a real game needs all three hands in the helper's own
data structures, and that handing an engine cards it should not see would
flatter it. The control built for that found the leak was real and then found it
was almost entirely the control's own noise: pinning each engine's random stream
across probe worlds moved XSkat from 89 apparent mismatches to **4 in 2,960
decisions (0.14%)**, and go-skat sits at 2 in 1,480. The four are genuine --
XSkat is told the skat, which as a hand-game declarer it should not know -- and
`xskat-blind` prices them at **+0.398 game pts/game, CI [-0.550, +1.346]** over
150 boards. Nothing measurable.

Two things about the instrument are worth keeping. It has to leave the game it
runs inside untouched, and `make_best` writes to six globals while choosing, so
"probed match and unprobed match print the same number" is the acceptance test
rather than a nicety. And both engines print commentary to stdout in the middle
of a reply -- XSkat the game value, go-skat its grand evaluation -- which put
the pipe one reply out of step and shows up as nothing but a weaker player.
Sequence numbers on every reply are what caught it; the fix is a duplicate of
fd 1 for the protocol and the null device for the engine.

## Sources

- XSkat: `deb.debian.org/debian/pool/main/x/xskat/` — the pristine 4.0 source
  and the licence text quoted above. (http://www.xskat.de/ was the home and is
  dead as of 2026-09-08.)
- Debian copyright for `xskat` 4.0-8, which ships it in `main` under that text.
- Mirror used for this exploration: https://github.com/mfrasca/xskat (4.0.mfrasca).
- Buro's Skat page and the SkatGame app page: https://skatgame.net/mburo/ ,
  https://skatgame.net/app/index-en.html
- ISS: https://skatgame.net/iss/ — bots, open protocol, game archive.
- Furtak & Buro, *Recursive Monte Carlo Search for Imperfect Information Games*,
  2013: https://skatgame.net/mburo/ps/recmc13.pdf
- Rebstock, *Improving AI in Skat through Human Imitation and Policy Based
  Inference*, 2019: https://skatgame.net/mburo/ps/thesis_rebstock_2019.pdf
- ISS downloads (`skatgui.jar`, `clients.tgz`): https://skatgame.net/iss/download.html
- go-skat, MIT: https://github.com/dranidis/go-skat
- Edelkamp, *Challenging Human Supremacy in Skat*, SOCS 2019:
  https://ai.dmi.unibas.ch/research/reading_group/edelkamp-socs2019.pdf
- *Outer-Learning Framework for Playing Multi-Player Trick-Taking Card Games*,
  2025: https://arxiv.org/html/2512.15435v1
