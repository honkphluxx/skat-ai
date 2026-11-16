# Training plan, September 2026

How to make the strongest player this ground can make, given what it has now:
a working arena, a solver, a search player at parity with the best outside
program, a belief model that is worth a fraction of what a belief could be
worth, two new outside opponents, and two findings that invalidate part of the
record. Every number here is from `arena/README.md` or `docs/xskat.md` unless
it says otherwise.

## 1. Where we stand, in numbers

| what | measured | where |
| --- | --- | --- |
| our best (`belief-32` = `ANALYST`) vs `jskat-ml-pro`, oracle contracts | **+0.19** [−0.95, +1.33] — parity | README, 2026-08-24 |
| the same player vs `jskat-new`, full game | **+12.6** | README |
| what the learned belief buys, 16 worlds, fixed contracts | **+2.37** [+1.03, +3.71] | sharpness sweep |
| what doubling the worlds buys on top of it | **+2.64** [+1.40, +3.88], "not done paying" | 2026-08-24 |
| what a belief that gets **one world in four right** buys | **+25.2** [+20.6, +29.7], and flat from there | belief sweep |
| room left in declaring vs par at fixed contracts | about **8 game points** (solver +2.91, `belief` −5.19) | "why defence is not the place" |
| the discard's share of that | about **a tenth of declared games** | "the ten percent" |
| `xskat` / `go-skat` vs `greedy` | +27.8 / +21.8; tied with each other | xskat.md §9 |
| αµ over plain voting | **nothing**, resolved | README |
| defence as a place to gain | **not**, measured | README |

Read the third and fifth rows together. They are the whole plan. A belief
sampled uniformly is 23 to 27 points below one that is right a quarter of the
time, and ours has closed about **two and a half** of those points. Nothing else
on this list is within an order of magnitude of that gap.

## 2. Two findings, and what each one invalidates

### 2.1 No player of ours ever declared Null (fixed in 09a81fa, 2026-08-28)

`HandEvaluator.promise` paid for matadors it did not hold and ranked Null on the
wrong scale, so `SearchAiProvider` never had Null among its candidates. Every
auction our players took part in before that commit was an auction in which
Null did not exist. What that reaches:

| artefact | status | why |
| --- | --- | --- |
| **the belief corpus, `belief-data/`** | **must be regenerated** | 0 Null decision points in 89,438 sampled. Worse than a gap: every *low* bid and every pass in the corpus was made by a bidder that could not say Null, so what the model learned about what "18" or a pass implies about jacks and aces is the wrong population. The guard that keeps the model off Null contracts kept it *usable*; it did not keep it *right*. |
| **the belief weights** | **retrain** | follows from the above |
| auction-mode ladders and the aggression sweep | **invalidated**, need a night | the bidder changed |
| `tools/challenge-seeds.tsv` | `--purge` and regenerate | old bidder's judgement |
| fixed-contract and oracle-contract results | **survive** | they bypass the auction |

**Softened, 2026-09-09, and this is worth two nights.** The argument above is
that a Null-*capable* bidder is a different bidder, so every low bid and every
pass in the corpus came from the wrong population. Measurement since says the
two bidders are very nearly the same one. The fix landed in 09a81fa; across 51
arena reports after it, the bidder declared Null **zero times**, and the audit
puts its modelled rate at 0.45% of boards -- itself an upper bound. A bidder
that *can* say Null and essentially never does produces almost exactly the
bidding distribution of one that cannot.

So the corpus is not the wrong population; it is the right population missing
a rounding error. **`belief-data/` does not need regenerating for the Null
reason** -- which removes a night of export and a night of training from Phase
B2. It still needs regenerating for anything else that changed the bidder, and
Phase V will change it again, so the export belongs after V rather than before.

What survives from the row is narrower and still true: there are no Null
decision points in the corpus, so the model has never seen Null play and cannot
learn it from this data. Given the contract is worth between -0.17 and +0.09
game points per game, that is a fact to record, not a reason to spend a week.

The last row is why the parity result stands: it was measured at oracle
contracts.

### 2.2 The Ramsch is not the official game, and nobody else plays ours

The canon (`rules.md` §3) plays a Schieberamsch when all three pass; the
Skatordnung passes the deal in. Every auction-mode match therefore has 10–16% of
its boards in a game that **no outside engine plays**: XSkat has a Ramsch of its
own with different options, go-skat has none, JSkat's is a different variant
again — and the arena already hands all three to `RamschPolicy` on those
boards. Three consequences:

- An auction-mode match against an outside engine is, on one board in eight, a
  match against our own greedy Ramsch heuristic wearing its name. The
  `delegated games` count in the external-bots summary is that number.
- The Ramsch policy can only ever be trained by self-play and measured against
  ourselves. That is fine — it is a product feature, not a strength claim — but
  it must not leak into strength claims.
- The belief encoding carries Schieben fields that no outside population will
  ever produce, and a corpus mixing Ramsch and non-Ramsch boards trains one
  model on two games.

**Decision: the measurement canon is the official rule; the product canon is
the Schieberamsch.** The arena grows `--passed-in=void` — a board on which all
three pass scores zero, counts as no game, and carries no signal in the paired
difference, exactly as the pairing already treats an equal-scored board. Every
cross-engine measurement, every calibration, and the belief corpus are taken in
that mode. Ramsch stays in the app, gets its own self-play loop (§4, phase M)
and its own arena mode, and its numbers are reported as its own column.

## 2.3 What Phase R measured, 2026-09-09 — and why the auction moves up the list

Phase R ran and its gate **failed, informatively**. Full account in
[`../arena/README.md`](../arena/README.md); the two numbers that change this
plan:

- **Zero Nulls in ~1,800 declared games**, three seeds, while the oracle prices
  Null as makeable on about 3% of the same boards. The player announces Null
  correctly when it wins an auction; it cannot win one, because
  `guaranteedValue(NULL)` is a flat 23 and the bidder never offers Null Hand
  (35), Null Ouvert (46) or Null Ouvert Hand (59). A hand shaped like a Null
  sits opposite two hands holding every jack, one of which bids 24.
- **`belief-32` beats `xskat` at oracle contracts on all three seeds (+2.6,
  +4.3, +3.2) and is level with it in the full game** (+1.6, −2.7, −0.2). We
  declare 24–26% of boards and win 85% of them; XSkat declares 33% and wins
  77%. The nine boards a hundred we decline and it takes are the entire
  difference.

So the auction is not the fourth lever, it is the second, and it is cheaper
than the belief: the card play is already ahead of a program we are level with
overall. **Phase A moves ahead of Phase D**, and the Null variants are the first
thing in it — a Null that can only ever be bid to 23 is a contract we own on
paper and never play.

## 2.10 The next phase, planned 2026-09-26: a teacher on the host, a student on the phone

Where 2.9 ends: the vote is not the lever, more worlds are (64 worlds
close about half of the defence's gap to SkatZero), and the phone's
thinking time is already at its limit on an S25 Ultra. Every lever that
worked costs search time. So the strong player moves to the host and
the phone gets a network that imitates it. Decided with Philipp, in
this order; nothing below is started.

**Phase T -- the teacher.**

- **T1, pick it.** `belief-64-shipped` (705f2b3) against the shipped
  player, fixed contracts, seeds 11-13 (`tools/trap.sh --pair-only
  --players=belief-64-shipped` runs it); and a variant with 64 worlds
  for the defenders only, since that is where the audit saw the gain.
  The teacher is picked for strength alone -- phone time does not count
  here. Gate: a resolved positive pairing.
- **T2, make it cheaper to run on the host.** Games per second measured
  first (the shipped player plays about one game a second at 16
  threads, 1,068 games in about 1,000 s; the 64-world teacher about
  half). Candidates, each proven exact by a test against the plain
  solve: one solve for cards that are equivalent (touching, nothing
  between them left in play); one transposition table shared by every
  question a decision asks of a world; and a table shared across worlds
  and decisions (a solved position is worth the same in every world
  that reaches it -- see the caching note below). Target about one game
  a second for the teacher.

*T1, first three seeds (2026-09-26).* `belief-64-shipped` against the
shipped player, fixed contracts, exact pairing: seed 11 +1.42 [-0.35,
+3.19], seed 12 **+4.30 [+1.36, +7.24]**, seed 13 +1.85 [-0.96, +4.67];
pooled **+2.53 [-1.33, +6.38]** -- every seed positive, one resolved
alone, the pooled interval wide on three seeds (t with two degrees of
freedom). The "from declaring" column moves most (-2.69 against -5.43,
-3.31 against -10.15, -7.14 against -9.92), but in a pairing that column
is our declarer against their defence and theirs against ours, so it
does not split declaring from defending; the defending audit already
showed the defence's share. Price: the three matches took 1,610, 1,739
and 1,328 s against about 1,150 s for a match of two 32-world players,
so the 64-world player costs about 1.7 times as much a game, not two --
Null decisions and everything outside the tally stay the same. Next:
seeds 14-16 (`tools/trap.sh --pair-only --players=belief-64-shipped
--seeds="14 15 16"`), which the pooled line then reads with the first
three.

*T1 passed (2026-09-26, seeds 14-16 added).* Seed 14 +0.84 [-0.87,
+2.56], 15 +0.78 [-1.03, +2.59], 16 +0.85 [-1.71, +3.41]; pooled over
six seeds **+1.67 [+0.25, +3.10]**, resolved, and all six seeds
positive. The later three are smaller than the first (seed 12's +4.30
carries the early mean), so the honest size is between one and two game
points a game. The defending column now moves too (12.95 / 12.41, 14.59
/ 13.33, 12.58 / 11.83). Price confirmed at about 1.7 times the
shipped player's time a game (1,453, 1,370 and 1,341 s a match).
**The teacher is `belief-64-shipped`.** The defenders-only variant was
wanted only to save phone time, which the teacher no longer needs, and
is dropped. At about 0.6 games a second on the host, the first 50,000
games take about a day, so T2's speed-ups wait: I1 is next. Open and
cheap to settle beside it: whether 128 worlds is better still (the
exporter does not care which teacher it records).

*T1, one step further (2026-09-27): 128 worlds is not better.*
`belief-128-shipped` (939d954) against the 64-world teacher: seed 11
+1.00 [-0.65, +2.65], seed 12 -0.43 [-2.53, +1.67], seed 13 **-2.52
[-4.64, -0.39]**; pooled -0.65 [-5.05, +3.75], at 2,473-2,755 s a match
against about 1,450 for 64 against 32. Nothing to buy for another 1.7
times the time; the teacher stays at 64 worlds.

*I1 built (2026-09-27).* `tools/teacher-export.sh` (`TeacherExportMain`,
Gradle `exportTeacher`): the teacher in all three seats, whole games,
auction included, passed-in boards void; at every card decision with a
choice, a 465-byte record -- the belief encoding of the seat's own
evidence, the legal cards, the card played, the vote and cushion per
card, and the vote world by world for the first 64 worlds (one bit a
world a legal card, through a new observer hook,
`CardPlayObserver.worldVerdicts`, asked for only by the exporter) so a
4- to 32-world player's vote can be read off the same games for the
weaker levels. Shards of 1,000 boards, resumable. `TeacherExportTest`:
recording changes no game, the chosen card is legal, votes sit on legal
cards only, and the world-by-world verdicts add up to the votes; three
mutants each fail.

*I1 format 2 (2026-09-27), before the corpus was run.* The teacher
only against itself would teach the student the positions the teacher
leads itself into and nothing else, and in the app it sits beside a
human and against weaker AIs. So each board now draws one, two or three
teacher seats (weights 25:50:25, `--mix`) and fills the rest from the
belief corpus's population (`greedy, search-4, club, expert,
jskat-new`, `--population`), seeded by the board so a shard written
again is the same shard. Only the teacher's seats are recorded; each
record gains four bytes (469): who sat at the deciding seat, its left
and its right (indices into `players.json`, 0 the teacher) and how many
seats the teacher held -- a diagnostic for slicing the student's
accuracy by opponent, never an input. About 11 records a board, so
I2's corpus is 90,000 boards for about a million records.
`--population=` gives format 1's all-teacher games. `TeacherExportTest`
adds a mixed-seating test (the draw is by seed alone, every seat count
occurs, only the teacher is recorded, the seating bytes agree with the
count); four more mutants each fail. One test run failed once on the
older check that the world-by-world verdicts add up to the votes (two
votes, one bit) and never again: 0 in 8,000 records of a 400-board
stress run, 0 in 19 reruns, and the native solver's answers under eight
threads agreed with its serial ones on 9,600 calls. Not found, so the
exporter now checks every decision and leaves out, and counts, any whose
verdicts do not add up; the count is printed with each shard.

*I2 done (2026-09-28), I3 built (2026-09-29).* The corpus: 90,000 boards,
1,197,380 teacher decisions in 123,149 s (34 h, 9.7 a second at 16
threads; 13.3 a board, not the 11 guessed above), no decision left out.
What it holds, from the checker (`arena/python/teacher_data.py`): the
five suit and grand contracts, a third of the decisions the declarer's,
and **no Null at all** -- nobody in the population announces one, the
same gap the belief corpora had -- so the student hands Null back to the
search. 4.6 legal cards on average; in 38% of the decisions every legal
card is tied at the top of the vote and the tiebreaks decide, in 35%
one card is best. `train_policy.py` (`tools/policy-train.sh`): the
belief net's trunk (306 inputs, 3 x 512) with two heads, a policy head
on the card the teacher played (the only target that carries the
tiebreaks) and a value head on each legal card's vote share (the soft
target, and what a weaker level is made of); split by deal with the
belief corpus's rule. Held out it prints the student's agreement with
the teacher's card, whether its card is one of the teacher's top-voted
("best"), and its regret in worlds of 64 against the teacher's card --
each beside what a random legal card scores -- by role, trick, contract
and table strength. The script trains on a quarter, a half and all of
the training deals: I2's question whether more data still pays.

One gap in the inputs, known before the first run: the belief encoding
says which cards each seat played but not who won which trick, so the
student cannot see the card points already banked -- and the teacher's
target is "61 minus what the declarer has". The value it cannot see it
has to guess from the cards played. If the late tricks lag, the next
corpus adds a small score block (points banked by each side, tricks
won) to the teacher record; the Java player knows those numbers, so the
student can be given them. Measured first, then decided.

*I3, first results (2026-09-29).* Twenty epochs take about a minute on
the host's GPU. Held out (119,968 decisions of 7,750 deals), regret in
worlds of 64 against the teacher's card, random legal card 3.48:

    training deals   teacher's card   top-voted   regret   best epoch
    a quarter             66.5%          78.8%      1.41       14
    a half                69.9%          80.9%      1.15       16
    all                   73.0%          82.9%      0.94       20 of 20

Every doubling of the data takes a fifth off the regret, and the full
run was still improving at its last epoch: neither data nor training
time is exhausted. By trick, against a random card's regret, the early
tricks are the best learned (0.64 of 3.86) and the late ones the worst
(1.04 of 2.57) -- what the missing points banked would predict, and
also what a hard, exact endgame would; the table cannot tell the two
apart. Agreement with the teacher's card is not the number to steer by
either: a third of the decisions are ties any card wins. So before any
game, a yardstick in the teacher's own units (`eval_policy.py`, run by
`policy-train.sh`): of each held-out record's 64 worlds, 0-31 stand in
for a smaller search (1, 2 ... 32 worlds, ties shared evenly) and 32-63
are the referee none of the players has seen; the student and the
searches are scored on the same positions by the same referee.

*The yardstick's answer (2026-09-29).* Regret on worlds 32-63, in
worlds of 32 (lower is better):

                         all   declarer defender  tricks 1-3  4-6    7-9
    student (all data)  0.505   0.448    0.532      0.389    0.602  0.527
    search,  8 worlds   0.504   0.350    0.575      0.804    0.566  0.143
    search, 16 worlds   0.338   0.214    0.396      0.569    0.366  0.081
    search, 32 worlds   0.228   0.137    0.270      0.397    0.241  0.046
    random legal card   1.781   2.361    1.511      1.999    2.058  1.290

Overall the student is worth an 8-world search -- but that average hides
the finding. In the first three tricks it plays like the 32-world search
(0.389 against 0.397); in the last three like a 1-world search (0.527
against 0.544). The net is good exactly where search is expensive and
bad exactly where search is nearly exact and cheap (97% of a search
player's card-play time was the first two tricks when last measured).
So the student to build first is not the net alone but a hand-over: the
net for the opening tricks, the search from trick N on. With the
32-world search after trick 3, the three groups average 0.225, the
32-world search's own number, at a fraction of its time. A table by
single trick (eval_policy.py, since this note) picks N.

Forty epochs changed nothing (0.505 either way; the quarter and half
runs slightly worse): training time is not the limit, data is -- each
doubling still takes a sixth off the regret. The value head is better
than the policy in the last tricks (0.401 against 0.527) and worse in
the first; with the search taking the endgame that matters less. The
missing points banked stay a suspect for the endgame, where the
hand-over makes them moot.

*I4 built (2026-09-29): the student plays the opening.* After the
per-trick table (student 0.314 / 0.380 / 0.472 worlds of 32 in tricks
1-3 against the 32-world search's 0.427 / 0.404 / 0.361, and nothing
like the search after that), Philipp's call: the hybrid for the phone
first, then the question of GPU self-play (R), which the hybrid does
not answer -- it is CPU, a player, not a data engine.

`SearchAiProvider.withOpening(PolicyNet, tricks)`: for the first
`tricks` tricks of a suit or grand game the student names the card in
one forward pass and no world is sampled; a Null, a Ramsch or a
contract the student never saw goes to the search, and from trick
`tricks + 1` the player is exactly what it was. The student reads
the evidence the teacher's records were made of (`evidence(context)`
after `rememberWhatIsStillThere`, quantised as the corpus is).
`PolicyNet` (engine, belief package) is the belief net's own loader
and forward pass with a 64-wide head (policy, then value) under its own
magic, parity-checked against the trainer's answers at load;
`export_policy.py` writes `policy.bin` and `policy-parity.bin`. Java
and torch pick the same card on 3,000 of 3,000 real records; a
corrupted weight is refused. `StudentOpeningTest` plays whole games
with a student of known scores (the card is the student's in the
opening, no world sampled; the search's after; Null, an unseen contract
and zero tricks never reach it; the setting survives every builder),
five mutants each fail.

Arena players (BeliefPlayers, when `policy-model/policy.bin` exists):
`hybrid-64-t1/t2/t3` (the student for 1-3 tricks, then the teacher),
`hybrid-32-t2` (then the shipped 32-world player) and `student-64`
(the student wherever it can play). `CardTimeMain` (`:arena:cardTime`)
times every card decision by trick for several players on the same
boards. First look, three boards in the container (two cores), card
play of all three seats per game: the shipped player 18.6 s, 96% of it
in tricks 1-2; `hybrid-64-t2` 1.75 s -- the 64-world search after trick
2 costs a tenth of what the 32-world one costs over a whole game;
`hybrid-32-t2` 0.94 s; the student alone 0.03 s. `tools/hybrid.sh`
runs the timing and the pairings: `hybrid-64-t2` against the teacher
and against the shipped player (six seeds each), t1 and t3 against the
teacher, the student alone against the shipped player.

*I4 measured (2026-09-29/30, tools/hybrid.sh, host, 24 cores).* Card
play of all three seats, seconds a game, 30 boards:

    belief-32-shipped 8.33   belief-64-shipped 14.15   student-64 0.01
    hybrid-64-t1 3.13        hybrid-64-t2 0.72         hybrid-64-t3 0.13
    hybrid-32-t2 0.38        (96% of a search player's time is tricks 1-2)

Fixed contracts, exact pairing, pooled over seeds:

    hybrid-64-t2 - belief-64-shipped  -0.54 [-0.88, -0.20]  6 seeds, five negative
    hybrid-64-t2 - belief-32-shipped  +0.50 [-0.70, +1.70]  6 seeds, not resolved
    hybrid-64-t1 - belief-64-shipped  -0.43 [-1.58, +0.72]  3 seeds
    hybrid-64-t3 - belief-64-shipped  -0.65 [-6.17, +4.87]  3 seeds
    student-64   - belief-32-shipped  -6.23 [-10.17, -2.29] 3 seeds

The student in the opening costs about half a game point against the
teacher -- small, but resolved -- and the hybrid at two tricks plays
the shipped player's strength or a little better at a twelfth of its
time. The student alone loses six points, nearly all as declarer
(from declaring -7.1/-11.0/-13.8 against +3.2/-1.9/+0.5): the search
in the endgame is not optional. Against the teacher the hybrid's
declaring column is the lower one in five seeds of six, by more than
its defending column -- consistent with the yardstick, where the
student as declarer is worth fewer worlds (0.448 against the 8-world
search's 0.350) than as defender (0.532 against 0.575).

*The student by role, held out (2026-09-30).* Tricks 1-2, regret in
worlds of 32: as declarer the student 0.293, the 32-world search 0.318;
as defender 0.371 against 0.456 -- better than the search it replaces
in both roles. Against the teacher, which cannot be put on its own
referee, extrapolated from 16 to 32 worlds (about 0.68 and 0.72 per
doubling): about 0.22 and 0.33, so a third more regret as declarer and
an eighth more as defender; in the cushion the teacher asked for on
tied votes the student's card is 0.33 worlds of 64 short as declarer,
0.19 as defender. Many small losses, a little more as declarer: enough
for the -0.54, no declaring audit needed. The value head is worse in
tricks 1-2 in both roles (0.373, 0.440): as declarer only from trick 3,
where the hybrid searches -- dropped.

Next, cheapest first: nets for a phase (`train_policy.py --tricks`,
`--weight-tricks`; `tools/policy-phases.sh`): tricks 1-2 only, 1-3
only, 1-2 drawn four times; and for a player that is networks all the
way through -- kept beside the hybrid, Philipp's point -- tricks 4-9
only at the same size and at 1024 x 4.

*Twelve seeds, and the phase nets (2026-09-30).* Six more seeds (17-22):
`hybrid-64-t2` - `belief-64-shipped` -0.49 [-0.81, -0.17] over all
twelve; - `belief-32-shipped` +0.33 [-0.19, +0.84] over twelve, still
not resolved. The hybrid is the shipped player's strength or a shade
better, at a twelfth of its card-play time.

`tools/policy-phases.sh`: every net trained on a phase is worse than the
all-trick student, in its own phase too. Tricks 1-2, declarer /
defender, regret in worlds of 32: all tricks 0.293 / 0.371; tricks 1-2
only 0.399 / 0.448; 1-3 only 0.369 / 0.412; 1-2 drawn four times 0.358
/ 0.401 (best epoch 8 of 30: the repeats are learnt by heart). Tricks
4-9, trick by trick: all tricks 0.538 0.612 0.655 0.666 0.568 0.348;
4-9 only 0.653 0.709 0.708 0.695 0.584 0.375; the same at 1024 x 4
0.632 0.717 0.716 0.685 0.572 0.328. What the student learns in one
phase serves it in the others, and a net four times the size gains
nothing: neither the phase nor the capacity is the limit, the data
(and what the input can see) is. One net for all tricks; a phase net
only again if the corpus grows by a lot.

*Format 3 (2026-09-30): the play so far in the teacher record.* Philipp's
call: the hybrid into the app, and the next corpus in format 3 at once.
`PlayHistory` (engine, belief package) writes 51 bytes from the decision
context: card points and tricks won by me, left and right, then nine
completed tricks of leader, three cards in the order they fell, winner
(seats relative, cards by index, 255 for a trick not yet played). Raw on
purpose: how a net reads it -- a vector per card, a small sequence
encoder -- is the trainer's choice on the same bytes, and the player that
feeds a format-3 student will call the same encoder. Appended after
`teacher_seats`, so every format-2 offset stands and `teacher_data.py`
reads both. The exporter's test holds the block to the encoding beside it
(every history card played by the seat the encoding names, every played
card in the history, each winner leads the next, points and tricks add
up); five mutants of the encoder each fail it, and the corpus checker
does the same checks in numpy (four mutants caught). It is the whole
history: the teacher forgets nothing, and a forgetful player will have to
mask it as the trainer's forgetting masks the encoding. 520 bytes a
record; `teacher-export.sh` writes `teacher-data-v3/`, 90,000 boards.

*S, the app (2026-09-30).* Philipp's call on the twelve seeds: the hybrid
goes into the app. `Opponents.seat` takes the student as a last
argument; the top level (`Level.usesStudent`, ANALYST only) then plays
the first `STUDENT_TRICKS` = 2 tricks of a suit or grand game with it
and samples `STUDENT_WORLDS` = 64 worlds afterwards -- the arena's
`hybrid-64-t2` -- while a Null keeps the level's own 128 worlds from the
first card. Lower levels ignore it: they forget, and the student learnt
from a player that does not; and they search fewer worlds, so the
student would make them stronger and close the ladder's gaps. The app
loads `policy.bin` and `policy-parity.bin` from its assets beside the
belief, requires the parity replay, and falls back to the old top level
(the same strength, twelve times the thinking) if either is missing or
wrong. `OpponentsTest` plays whole games through the app's seam with the
app's observer: the student's cards at 10 and 9 cards in hand with no
world sampled, 64 worlds after, 128 on a Null, a lower level and no
student unchanged; four mutants of the wiring each fail. On a phone the
student's forward pass is a few hundred thousand multiplications a card;
what is left to measure is the S25's time a card, in the SkatKlarPerf
log the dev builds already write (worlds=0/0 marks the student's cards).

*Warm start (2026-09-30).* Philipp's two more ideas, weighed before any
code: preprocessed facts -- the trump count and the observed voids are in
the encoding already (`trumps_out`, `trumps_mine`, `jacks_out`,
`void_left`, `void_right`), and what the student could not see was the
points and the trick order, which format 3 adds -- and the belief net's
output as an input, which needs no export (it reads the same 306 bytes)
but ties two networks together at play time: dropped, as more ways to
fail. Instead his third: start the student from the belief net's
weights. The two have the same trunk, 306 x 512 x 3, the same tensor
names, so the whole trunk carries over and only the heads start random;
the result is one ordinary student. `train_policy.py --init-from
belief-model [--freeze-epochs N]`; `tools/policy-warm.sh` trains it plain,
at a third of the learning rate, and with the first block held for five
epochs, beside a second random seed as the noise floor.

*Format 3 corpus done (2026-10-01).* `teacher-data-v3/`: 1,197,472
decisions in 119,297 s, none left out, every check passed over all of
them. The same 90,000 boards as v2 (seed 101) -- and about 94% of the
records are byte for byte v2's, the rest from games that went another
way: the teacher is not perfectly repeatable across two runs, which is
worth knowing and harmless here. The split is by deal, so v2 students are
scored on the same held-out deals. The student reads the play state as
232 more inputs (`teacher_data.play_state_features`, `train_policy.py
--play-state`): points and tricks won per seat, the declarer's side's and
the defenders' points, and per card whether it has fallen, in which
trick, in which place and who took that trick -- checked on records
against the encoding beside them (cards played by the seat whose turn it
was, winners and counts adding up; three mutants caught). A warm start
fills the encoding's columns of the wider first layer from the belief net
and starts the new ones at zero. `tools/policy-v3.sh` trains the control
and the warm start on the encoding alone, and both again with the play
state.

*What the play state is worth (2026-10-02, `tools/policy-v3.sh`).* Regret
in worlds of 32 on the held-out deals, declarer / defender, tricks 1-2
and 3-9 (the seed-to-seed spread of one recipe is about 0.004 on these):

    v2, random start             0.293 0.371   0.482 0.579
    v2, belief's trunk           0.293 0.364   0.465 0.567
    v3, random start             0.308 0.372   0.473 0.577
    v3, belief's trunk           0.289 0.370   0.473 0.571
    v3 + play state, random      0.293 0.381   0.479 0.589
    v3 + play state, belief's    0.293 0.366   0.475 0.547

Tricks 7-9 for the last against the best of the others: 0.625 / 0.536 /
0.310 against 0.647 / 0.558 / 0.340. The play state is worth something
only from the belief's trunk, only late, mostly to the defender: about
5-9% off the last three tricks, where the student still plays like a
one-world search (0.536 at trick 8 against that search's 0.521). Nothing
in tricks 1-2, so nothing for the hybrid. So the endgame is not short of
information but of calculation -- the result the yardstick was set up to
tell apart, and the one SkatZero's own declaring (more won positions
thrown than ours) also points to. A network-only player would need far
more data than imitation can buy, which is R's question; the search
keeps the endgame. Note: v3 is the same games as v2, so it is not more
data; a corpus that adds data needs another seed.

*The self-play throughput test (2026-10-02, `tools/selfplay-throughput.sh`,
host: 24 logical CPUs, RTX 4090).* Network-only games, the student in all
three seats at fixed suit and grand contracts through the shipped engine
(a bare actor: evidence, encoding, one forward pass, nothing at the deal):

    threads   games/s   decisions/s   forward pass alone, us
       1        113        2,593          378
       8        738       16,950          394
      24      1,702       39,114          583

99% of an actor's time is the Java forward pass, one input a call; the
engine and the encoding are the last 1%. At 24 threads that is 147 million
games a day, the v2 teacher corpus's 1.2 million decisions every 31 s and
ten thousand times it in 3.5 days -- on the CPU alone, as the code stands.
(The arena's student-64 manages 2.3 games/s on a thread: it prices every
deal for the auction with the solver, 98% of its time. Self-play must not
carry that.) The same net batched, decisions a second: torch on the CPU
450 k at 4,096 a batch (eleven times the Java actors on all cores); the
4090 11-14 M (9-12 M with the inputs' round trip from the host), fp32 as
fast as bf16, the play state's 538 inputs costing nothing. Training on the
4090: 4-5 M decisions a second, an epoch of v2 in 0.2 s.

So volume is not what stands between us and a network-only player: the
four orders of magnitude are days of CPU, and batching onto the GPU would
buy another one or two once the actors' per-card overhead is all that is
left (at about 15 us a decision against today's 380, the 24 threads would
not keep the card busy). What decides R is the training signal. Teacher
labels cost 14 s a game and do not scale; self-play games carry only their
outcome. The candidate is Deep Monte-Carlo as in DouZero (a three-player
card game with hidden hands, learnt from outcomes alone): a Q per card
regressed on the game's result, actors exploring with the current net, the
learner on the GPU. Two things in hand make a pilot cheap: the student's
value head already estimates, per card, the share of worlds that hold --
a Q to start from -- and the yardstick scores any net against the teacher's
referee worlds on held-out deals, so whether self-play moves the endgame is
measured, not argued. The pilot needs no GPU inference: generate a few
million games with the Java actors, train, export, repeat.

*The Deep Monte-Carlo pilot, built (2026-10-02, `tools/dmc-pilot.sh`).*
`SelfPlayExportMain` (`:arena:selfPlayExport`) plays network-only games, one
net in all three seats at fixed suit and grand contracts (greedy's auction,
greedy's skat exchange, board seed 7000 + iteration, never the teacher's
101), each card the legal one the value head rates highest, a share epsilon
(0.02) at random. Each seat reads the teacher's own evidence -- a perfect
memory, the bids heard, the declarer's discard; `SelfPlayExportTest` holds
it byte for byte to the search player's at memory 1 -- and every decision
is written (326 bytes: the encoding, legal cards, the card, explored and
declarer flags, trick, contract, whether the decider's side won, its card
points, the net's own estimate, the game id). `train_dmc.py` regresses the
value head at the card played on whether the side won -- the head's own
loss and meaning, the outcome of the game as played in place of 64 solved
worlds -- over the last two iterations' games, one epoch, AdamW 3e-4; it
prints the held-out loss and calibration by trick before and after, and
writes a policy model that `export_policy.py` and the yardstick take
unchanged. The loop: self-play 500,000 games, learn, export, yardstick
(value-head rows against net-00 = the student, on the teacher's held-out
deals and referee worlds). The policy head is left alone; the actors play
by the value head, which was already the better of the two late (0.40
against 0.53 in tricks 7-9).

What it answers: whether the value head's regret in tricks 7-9 falls
below the student's, towards the searches', iteration by iteration. What
it cannot: the referee is double-dummy; a net that learns the game as
actually played may part from it where the referee's perfect play is the
wrong model, so a flat yardstick with a falling self-play loss would send
us to a game match before any verdict.

*What the pilot answered (2026-10-02, ten iterations, 5 million games).*
Self-play itself worked as built: 1,700 games/s, the held-out self-play loss
0.497 -> 0.328, flat from iteration 5, and calibrated to within a point at
every trick (estimate minus outcome +-0.005). The yardstick went the other
way. Value head, regret in worlds of 32:

                     trick 1    3      5      7      8      9
    net-00 (student)  0.389  0.528  0.630  0.574  0.419  0.213
    net-01            0.458  0.618  0.709  0.626  0.406  0.234
    net-05            0.659  0.792  0.919  0.799  0.546  0.281
    net-10            0.700  0.849  0.947  0.806  0.523  0.259

Worse everywhere from net-03 on, early tricks most; the policy head, left
untrained, drifted with the trunk to twice the student's regret. Not the
referee's double-dummy bias: at trick 9 almost nothing is hidden and the
referee is close to the truth, and it got worse there too. The reading: the
outcome of a game is decided far more by the deal than by any one card, so
a net learning to predict it learns who holds the game (the loss falls,
the calibration is excellent) and next to nothing about which card is
better -- that difference is a sliver of the target's variance, seen only
for the card played, and for the others only through 2% exploration while
the trunk under their outputs moves. DouZero spent billions of games (with
the action as an input, not an output per card); five million of ours did
not separate the cards.

What this points to: compare the cards on the same deal. Play every legal
card in turn on the actual cards, the rest of the game played out honestly
by the net, and label each card with its result -- the deal's variance
cancels between the alternatives, which is exactly the noise that drowned
this run. Late in the game the play-out is a few cards long and the label
close to exact whatever the net's strength. Not built.

*SkatZero on the yardstick, built (2026-10-02, `tools/skatzero-yardstick.sh`).*
SkatZero is itself Deep Monte-Carlo (its README: DouZero's method, the card
as an input beside the state and an LSTM over the history, nine models,
about 1.5 billion self-play games each for suit and grand, 500 hours on an
RTX 4070 Ti and 16 cores) -- the method the pilot ran at 5 million games.
So the question for R: does learning from outcomes at that scale learn the
endgame? `skatzero_eval.py` rebuilds SkatZero's state from a format-3
record (hand, contract, declarer, own discard, every completed trick in
order, points) and plays its card as `tools/skatzero-bot.py` does, look-
ahead included; `eval_policy.py --skatzero` scores it as one more row. The
record holds the current trick as a set: with two cards of different
classes the recorded voids settle the order, with two of one class both
orders are tried, and where SkatZero's card depends on it the decision is
left out for every player (8% of a 415-decision sample). Checked: its legal
cards equal the record's on every rebuilt position; against the bot's own
Driver fed the same game card by card, the same card on 338 of 338 and the
same points. A future exporter should write the current trick in order.

*SkatZero on the yardstick: the answer (2026-10-02, teacher-data-v3, 107,929
held-out decisions; 11,980 left out for every player where the current
trick's order was open and decided SkatZero's card).* Regret in worlds of 32:

                       declarer 1-2  defender 1-2  declarer 3-9  defender 3-9
    student (v2)           0.297         0.339         0.482         0.516
    student, play state    0.300         0.336         0.476         0.489
    SkatZero               0.433         0.552         0.236         0.492
    search, 8 worlds       0.732         0.908         0.257         0.491
    search, 32 worlds      0.326         0.506         0.091         0.219

    by trick       1      2      3      4      5      6      7      8      9
    student      0.301  0.349  0.431  0.486  0.541  0.587  0.612  0.554  0.324
    SkatZero     0.513  0.513  0.549  0.555  0.531  0.502  0.375  0.251  0.112
    search, 4    1.127  1.101  1.104  0.999  0.855  0.642  0.424  0.223  0.079

Three readings. (1) Learning from outcomes at 1.5 billion games does learn
the declarer's endgame: declarer 3-9 0.236, an 8-world search, against the
student's 0.48; tricks 7-9 between a 2- and a 4-world search. Not the
32-world search, which the hybrid keeps. (2) SkatZero's opening is worse
than the student's (trick 1 0.51 against 0.30): "SkatZero's opening, our
endgame" has nothing to take on this yardstick. (3) The decisive one: on
defence the yardstick puts SkatZero level with the student and short of the
32-world search, yet in games SkatZero's defence gives away about 3 g.p. a
game less than our search's (the defending audit, 2026-09-26). The referee
is double-dummy: it lets the declarer play every world with every card
seen, so a defence that gives a real declarer chances to go wrong -- or
denies him the information to get it right -- scores nothing. The teacher's
labels share that blindness, so no amount of imitation fixes our defence;
only a signal from games as played can see it (outcomes at scale, as
SkatZero, or comparing the cards on the same deal with an honest declarer
playing on). And the yardstick must not be the judge of defence: that
needs games.

*The defence split, built (2026-10-02, `tools/defence-split.sh`).* A role
split is one seat played by two players, `roles:<declaring>/<defending>`
(`RoleSplitProvider`; everything before the first card is the declaring
player's, each card goes to the player the seat's role names, both hear
every event; `RoleSplitProviderTest`, four mutants fail). Three pairings at
fixed contracts: our teacher declaring with SkatZero defending against the
teacher whole (six seeds), the same for the shipped 32-world player, and
SkatZero declaring with the teacher defending. The first is the number
that says what the defence gap the yardstick cannot see is worth to our
best player. Container smoke, `roles:search-4/skatzero` vs `search-4`, 8
boards: no delegated game, no divergence, honesty control 0 of 480.

*The defence split: the answer, and a correction (2026-10-02, 200 boards a
seed, fixed contracts).*

    roles:teacher-declares/SkatZero-defends - teacher   6 seeds  +0.05 [-1.10, +1.20]
    roles:shipped-declares/SkatZero-defends - shipped   3 seeds  +1.26 [-0.62, +3.15]
    roles:SkatZero-declares/teacher-defends - teacher   3 seeds  +1.42 [-1.42, +4.27]

The "from declaring" line separates the roles here: in the first two
pairings both sides declare with the same player, so A's minus B's is what
SkatZero's defence costs that declarer; in the third both defend with the
teacher, so it is SkatZero's declaring minus the teacher's. Per seed, in the
arena's tournament points a game: SkatZero defending against our teacher
+0.1 -1.2 -1.7 -1.4 +2.8 +1.3 (nothing); against the shipped 32-world
player +1.2 +3.1 +1.6; SkatZero declaring against the teacher's defence
+0.3 +3.3 +4.1.

The correction: the note before this one called defence our biggest gap
(about 3 g.p. a game). That was the shipped 32-world player's defence (the
audit of 2026-09-26). The 64-world teacher defends as well as SkatZero in
games; the gap the audit found is the 32 worlds', which T1's 64 already
closed most of. What remains, and is not yet resolved, is the other side:
SkatZero may declare better than our teacher in games -- the declaring
audit's par run said the same (SkatZero won 319 of 529 boards to our 302,
48 of 238 flat-zero boards to our 17) -- although on the yardstick it opens
worse and plays the endgame below the 32-world search. Both are true if
the difference sits where the yardstick is blind: a position the declarer
has lost against perfect defence scores every card zero, so the referee
cannot prefer the card that gives real defenders the most room to go
wrong. That is where SkatZero, trained on games as they end, has something
to teach, and where the teacher's labels have nothing. Next: three more
seeds of the third pairing (`--only=sz-decl-teacher-def --seeds="14 15
16"`) to resolve it.

*SkatZero declares better than our teacher: resolved (2026-10-03).* Seeds
14-16 added to the third pairing: +3.01, +0.94, +1.03; pooled over six
seeds `roles:skatzero/belief-64-shipped - belief-64-shipped` **+1.54
[+0.45, +2.64]** game points a game, the declaring line +0.3 +3.3 +4.1
+4.8 +1.5 +1.4. With our teacher defending on both sides, SkatZero's
declaring is worth about a game point and a half a game over the
teacher's -- the first thing measured in games that SkatZero does better
than our best player, and the one place the teacher's labels and the
yardstick cannot point at.

*Where, built (`FlatHandoverProvider`, `flat:<search>/<rescuer>`).* Our
teacher plays everything, except the declarer's decisions in which its own
vote is zero for every legal card -- lost against perfect defence in every
world it drew, where it has nothing to choose by but tiebreaks and the
ladder; those go to SkatZero. The search is asked first and its vote read
from its own session; the rescuer is asked only for the cards it plays and
told of every other one. `FlatHandoverProviderTest`: handed over exactly
when every legal card is at zero, only as declarer, the rescuer hearing
every card (five mutants fail); 31% of a 4-world search's declarer
decisions are flat. If `flat:belief-64-shipped/skatzero` takes most of the
+1.54, the edge sits in lost positions -- the declarer's play for real
defenders' mistakes -- and that is the target for new labels; if not, it
sits in positions the search still thinks winnable.

*The flat handover: the answer (2026-10-03, six seeds).*
`flat:belief-64-shipped/skatzero - belief-64-shipped` **+1.00 [+0.11,
+1.90]** game points a game; per seed -0.47 +1.60 +0.52 +1.17 +1.32 +1.88;
29-36% of the declarer's decisions handed over (1,176-1,416 of about
4,000 a seed), honesty control 0 of every handed decision. SkatZero
declaring everything was +1.54 [+0.45, +2.64] on the same seeds: about
two thirds of its edge comes from the positions our search calls lost --
every legal card at zero votes, where the double-dummy question has no
answer and the search falls back on tiebreaks and the ladder -- and the
rest, within the noise, from everywhere else. The teacher with SkatZero
in its lost declarer positions is the strongest player measured here.

So the target is precise: a third of the declarer's decisions, in which
the right question is not "which card wins against perfect defence"
(none does) but "which card wins most often against the defenders at the
table". The teacher's labels are silent there by construction. Three ways
to an answer of our own: (a) distil SkatZero's choices in exactly these
positions into our net (cheap; capped at SkatZero); (b) in a flat
position, play every legal card out in the sampled worlds with realistic
defenders -- the student for all seats -- instead of the solver's perfect
ones, and keep the card that wins most often (a change to the search,
no training; the play-out idea, aimed where it matters); (c) labels from
(b) for a net, later.

*A rule (Philipp, 2026-10-03).* SkatZero is a measuring stick and a source
of concepts, never of data: no training in which SkatZero is the teacher,
no labels taken from its choices, no cloning. Hybrids with SkatZero in
them are for analysis only (the role split, the flat handover). Option (a)
above is struck.

*Play-outs in the lost positions, built (`RolloutRescue`, `Playout`;
`flat:<search>/rollout-<worlds>`).* Where the search's vote is zero for
every legal card, each legal card is played in each of the search's own
sampled worlds and the game finished by the student at all three seats,
every seat reading only its own evidence (perfect memory, the bids heard,
the declarer's discard); the card that wins in the most worlds is played,
ties to the most declarer points, then to the search's card. `Playout`
uses the engine's own rules and builds each position as the engine does:
`PlayoutTest` replays the engine's fixed-contract games from every decision
on the true hands with the engine's policy, and the play-out must end on
the engine's declarer points with every position it hands the policy
encoding byte for byte as the engine's own (six games, over 500 positions;
six mutants fail). Container smoke: `flat:search-4/rollout-16` played ten
boards at the search's own speed. The pairing:
`./tools/defence-split.sh --only=teacher-flat-rollout`, against the
teacher, six seeds, beside the SkatZero handover's +1.00. The student is a
weaker defender than the teacher the arena seats against it; cards that
win only against its mistakes will gain less there.

*Play-outs in the lost positions: the answer (2026-10-03, six seeds).*
`flat:belief-64-shipped/rollout-64 - belief-64-shipped` **+0.09 [-0.30,
+0.48]**: nothing, on the same seeds where SkatZero in the same positions
gave +1.00 [+0.11, +1.90]; 29-37% of declarer decisions handed over, as
before. Not yet known: how often the play-outs' card was not the search's
own, and how often any card won a play-out at all. Instrumented since
(the arena's report prints both, and for any rescuer how often its card
differed from the search's); a container smoke against a 4-world search
shows the play-outs moving the card in 63-86% of handed decisions and the
best card winning 30-38% of its worlds against the student's defence. If
the teacher's run looks alike, the play-outs do choose differently and
believe they win -- against the student -- and the cards they choose win
nothing against the teacher's defence: the student is the wrong model of
the defenders at the table, the risk named when the play-outs were built.
One seed of each rescuer with the counters (seed 21) says which.

*The counters (seed 21, 2026-10-04).* Same seed, same handed positions
(1,384 and 1,392 of about 4,130 declarer decisions):

                          result   card differs from the search's
    play-outs (rollout-64)  +1.00 [-0.54, +2.54]   46%
    SkatZero                +0.98 [-0.61, +2.57]   58%

Play-outs: in 52% of the handed decisions some card won some play-out; the
cards' counts differed in 51%; the best card won 10.9% of its worlds. So
the student is not the soft defender feared -- against it a lost position
stays lost nine times in ten -- and the play-outs' signal is thin: a best
card at about seven wins of 64, its rivals a win or two behind, and half
the decisions with no win at all, where the points decide. Pooled over
seven seeds: play-outs +0.22 [-0.23, +0.67], SkatZero +1.00 [+0.28,
+1.72]. On seed 21 the two are level, so the play-outs are not wrong in
kind; they decide on too few events. Next: thicken the signal rather than
change the defender -- several play-outs per world with the defenders'
cards drawn from the student's policy instead of its top card (the
chance of a defender's mistake, not the certainty of none), and the
win-or-lose count scored as the game's value, so a lost Schneider and a
lost game are not the same zero.

*Drawn defences, built (2026-10-04; `flat:<search>/rollout-<worlds>x<draws>`).*
The defenders' cards in a play-out are drawn from the student's own policy
(softmax over the legal cards) rather than taken from its top, `draws`
times per world, on one random stream per world and draw shared by every
candidate card; the declarer's later cards stay the student's top card.
The cards are ranked by the game's value as the engine settles it
(`SkatRules.score` on the play-out: a loss doubled, Schneider counted, an
overbid charged), summed, then wins, then points. `PlayoutTest` now also
holds every play-out to the engine's settled game value, the draw to the
softmax (1:3:6 within a point or so over 20,000 draws, an illegal card
never), the value ranking's order, and drawn play-outs to be reproducible
and to differ from the top-card ones; four new mutants fail. Container
smoke against a 4-world search: twice the play-outs' time, the card
changed in two thirds of the handed decisions, the best card winning a
quarter of its play-outs (one in nine with the top card). The pairing:
`./tools/defence-split.sh --only=teacher-flat-drawn`.

*Drawn defences: the answer (2026-10-04, seeds 11-16 and 21).*
`flat:belief-64-shipped/rollout-32x4 - belief-64-shipped` **+0.93 [+0.47,
+1.40]** game points a game, per seed +0.20 +1.22 +0.32 +0.94 +0.98 +1.47
+1.41; the SkatZero handover on the same seven seeds +1.00 [+0.28, +1.72],
the first play-outs +0.22 [-0.23, +0.67]. Our own play-outs now take as
much from the declarer's lost positions as SkatZero does -- no SkatZero
in them, nothing learnt from it. Counters: 29-37% of the declarer's
decisions handed over, the card changed in 47-53% of them, some card won
some play-out in 53-62%, the best card 8-13% of its play-outs. Which of the
two changes carried it -- the drawn defences or the ranking by game value
-- is not separated; a value-ranked top-card run would say. Cost: about
70 minutes a seed at 14 threads against the plain teacher's 22, all of it
in the handed decisions. The teacher with drawn-defence play-outs in its
lost declarer positions is the strongest player of our own measured here.

*Batched play-outs and the scopes, built (2026-10-04; `po:<search>/<rollout>/<scope>`).*
The play-outs of a decision now run in lockstep: every candidate card in
every world and draw is a `Playout` stepped one position at a time, and
each step's waiting positions go through the student as one batch
(`PolicyNet.logits(float[][])`, a blocked kernel that answers bit for bit
what the single pass answers -- `PolicyNetBatchTest`, batches of 1 to 130
on an odd width). Same random streams, same answers: the batched tally
equals the one-at-a-time tally exactly (`RolloutRescueTest`, on real engine
decisions, both roles, with and without draws), and on the real student
in the container it is 3.1 times faster (3.0 s against 0.96 s a decision,
one thread, 8 worlds by 4 draws). The play-outs now serve both roles and
more than the lost positions: `po:` takes a scope, `lost-decl`,
`lost-def`, `tie<k>-decl`, `tie<k>-def` in any comma list. A tie is a
position that is not lost and has two or more cards within k votes of
the search's best; the play-outs then choose among those cards only, so
the search's vote stays the safety net (`FlatHandoverProvider.candidates`,
its own test and mutants). Everything is counted from the deciding seat's
side: a defender's win is the declarer's loss, its value the declarer's
turned round. `po:X/rollout-32x4/lost-decl` is `flat:X/rollout-32x4` and
must replay its games exactly. Container smoke, four boards, all four
scopes against the teacher: the teacher's votes tie at the top in about
three quarters of the declarer's decisions (the ladder's won positions:
every winning card at full votes) and a third of the defenders', the card
changed in 40-45% of those; about 1.3 s a handed decision on the
container's two cores.

*The play-out study, prepared (2026-10-04; `./tools/playout-study.sh`).*
Every pairing against belief-64-shipped, fixed contracts, seeds 11-16 and
21 (the seeds of teacher-flat-drawn), seeds outermost so any stop leaves
every question with the same seeds: `decl-lost` (seed 11 only: the
batched replay must give +0.200 and 1,242 of 4,221 handed, 622 changed,
exactly, in less time); `decl-lost-v` (64 worlds, top card, value-ranked:
which of the two changes made +0.93 out of +0.22); `def-lost`;
`decl-tie` (tie0: among the cards that hold the game, the one with the
best score -- Schneider, more points); `def-tie`; and `all` (the four
scopes together; they do not overlap, so it should come near the sum of
its parts). Each scope is also pooled paired with teacher-flat-drawn on
the same seeds. A rough estimate from seed 11's counters: 30-40 minutes a
seed for each single scope, 80-90 for `all`, about four hours a seed at 16
threads, a day for all seven; the first seed's `took` lines will say. A
difference of 0.3 game points between scopes is below what seven seeds
resolve (a pairing's interval is about +-0.5); the study is for the
large effects and for whether the parts add.

*The play-out study: the answer (2026-10-06, seeds 11-16 and 21).*
Against belief-64-shipped, game points a game, pooled over seeds:

| scope | pooled | per seed 11 12 13 14 15 16 21 |
|---|---|---|
| lost-decl (teacher-flat-drawn) | +0.93 [+0.47, +1.40] | +0.20 +1.22 +0.32 +0.94 +0.98 +1.47 +1.41 |
| lost-decl, rollout-64v | +0.28 [-0.30, +0.87] (6 seeds) | -0.31 -- +0.12 -0.05 +0.44 +0.21 +1.29 |
| lost-def | **+0.07 [+0.02, +0.12]** | +0.18 +0.02 +0.05 +0.09 +0.02 +0.08 +0.06 |
| tie0-decl | **+0.42 [+0.05, +0.78]** | -0.13 +0.40 +1.11 +0.29 +0.10 +0.51 +0.64 |
| tie0-def | -0.43 [-1.74, +0.87] | +1.62 -1.21 -1.37 -1.09 +0.94 -2.26 +0.33 |
| all four | +0.86 [-0.20, +1.92] | +2.56 +0.61 +0.20 -0.57 +1.61 -0.19 +1.78 |

The replay: `po:.../lost-decl` on seed 11 gave +0.200 and 1,242 of 4,221
handed, 622 changed -- the old run exactly. Seed 11 ran under load (every
pairing two to three times slower that night); from seed 12 on: the plain
teacher about 22 minutes a seed at 16 threads, one scope 27-31, all four
36-38 with 37 million positions through the student (the old drawn run
moved 9 million in about 48 extra minutes; now about 14 extra minutes for
four times as many). The rollout-64v seed 12 was killed mid-run and its
partial report taken for done -- the script now writes `.partial` first.

What it says. (1) The drawing carries the +0.93, not the value ranking:
64 worlds, top card, value-ranked is +0.28, paired with the drawn run
-0.60 [-1.07, -0.14]; the first win-ranked top-card run was +0.22. Not
cleanly separated from the play-outs' count (64 against 128), but more
worlds did not buy it. (2) The defenders' lost positions are real but
small (+0.07, 10% of defender decisions handed, the card changed in a
quarter). (3) The declarer's ties pay: about half its decisions tie at
the top -- the ladder's won positions, every winning card at full votes,
the best card winning 92% of its play-outs -- and choosing among those by
the play-outs' game value is +0.42 (Schneider and points). (4) The
defenders' ties do not: half the defender decisions handed, the card
changed in half of those, seeds from -2.3 to +1.6 and the mean below
zero; the teacher's own declaring gains up to four points a game against
it. Why is open. (5) The parts add, seed by seed: the four single-scope
results summed per seed track `all` closely (mean 0.99 against 0.86), and
`all` minus lost-decl is -0.08 [-1.35, +1.20] -- the defenders' ties eat
what the other two add. Summed the same way, lost-decl + lost-def +
tie0-decl predicts about +1.4 [+0.8, +2.0] (a prediction, not a
measurement). Next: measure that combination (`best`, `--only=best`), and
find out what the defenders' ties are before trying them again.

*What the defenders' ties are (2026-10-06, container count).*
The teacher already breaks its ties: a fifteen-point cushion asked
whenever the top vote is shared (+1.10 when it shipped), then the rule
tiebreak. The tie scopes overrule both. A container count, 12 boards of
seed 16, `po:belief-64-shipped/rollout-32x4/tie0-decl,tie0-def` against
the teacher (the counters now print this split): of 97 handed declarer
ties, 60 at the full vote (the game held in every world) and 37 short of
it; the cushion had settled 19 and the play-outs overruled it in 15. Of
205 defender ties only 62 at the full vote and 143 short of it -- the
defenders' ties are mostly open positions, not won ones, and 74 of the 95
changed defender cards fell there; the cushion had settled 71 and the
play-outs overruled it in 27. Small, one seed; it says where to look, not
what it costs. Two new scope suffixes, for either role: `tie<k>c-...`
hands over only what the cushion leaves tied (the play-outs never
overrule it), `tie<k>w-...` only ties at the full vote; `tie<k>cw-...`
both (`FlatHandoverProvider.candidates`, tested, five mutants fail).
Three new pairings in `./tools/playout-study.sh`, each paired with the
scope it narrows: `def-tie-w` (tie0w-def: are the open ties the loss?),
`def-tie-c` (tie0c-def: is overruling the cushion the loss?), and
`decl-tie-c` (tie0c-decl: does decl-tie gain more where it leaves the
cushion alone?).

*Best, and the ties narrowed: the answer (2026-10-07, seeds 11-16 and 21).*
Against belief-64-shipped, game points a game:

| player | pooled | per seed 11 12 13 14 15 16 21 |
|---|---|---|
| **best** (lost-decl, lost-def, tie0-decl) | **+1.31 [+0.81, +1.81]** | +0.88 +1.77 +1.59 +0.57 +0.90 +2.04 +1.44 |
| tie0w-def (held defender ties only) | -0.18 [-0.64, +0.28] | -0.27 +0.42 -0.77 +0.05 -0.91 +0.03 +0.20 |
| tie0c-def (what the cushion leaves) | +0.31 [-0.75, +1.37] | +1.53 -0.15 +1.17 -0.68 +1.14 -1.59 +0.74 |
| tie0c-decl | +0.36 [-0.17, +0.90] | -0.01 +0.38 +1.42 +0.31 -0.47 +0.30 +0.61 |
| lost-decl, rollout-64v (seed 12 redone) | +0.37 [-0.15, +0.88] | -0.31 +0.88 +0.12 -0.05 +0.44 +0.21 +1.29 |

`best` came in where the per-seed sum of its parts put it (+1.42
[+0.84, +2.01] predicted), every seed positive, +0.38 [-0.14, +0.89] over
the declarer's lost positions alone. It is the strongest player of our own
measured here, with no SkatZero in it; about 32 minutes a seed at 16
threads against the plain teacher's 22. The value-ranked top-card
play-outs, now on seven seeds, stay -0.57 [-0.95, -0.18] below the drawn
ones: the drawing carries the gain. The defenders' ties stay with the
search. Held ties only are nothing (-0.18); leaving the cushion alone
takes back most of the loss (+0.74 [-0.06, +1.55] over tie0-def) but what
is left swings -1.6 to +1.5 from seed to seed -- a defender's card changes
the rest of the game more than a declarer's, and the student's judgement
there is no better than the teacher's tiebreak. For the declarer the
cushion does not matter: tie0c-decl against tie0-decl -0.06 [-0.31,
+0.20], so `best` keeps tie0-decl. The defenders'-only reports printed no
counters (the shutdown hook asked for declarer decisions); fixed.

*best outside, and best as the teacher: prepared (2026-10-07; `./tools/best-run.sh`).*
Two questions in one run. First, does the edge hold against a player that
is not us: best and the teacher alone, each against XSkat at fixed
contracts, seeds 11-16, pooled each and best minus the teacher paired by
seed. Second, the data for the loop the plan calls Phase R: best as the
teacher of `TeacherExportMain`, on the first 30,000 boards of
teacher-data-v3's deals and seating (seed 101), into teacher-data-best/.
A record is the search's as in v3 -- evidence, vote, cushion, verdicts,
play state -- with the card best played: the play-outs' where they
decided. `FlatHandoverProvider.withTap` carries the exporter's observer
through the hand-over and tells it the vote only once the card is
settled; a test holds the tapped card to the card each session returned
and the tapped games to the untapped ones (three mutants fail). Container
smoke on a 4-world search: 248 records from 20 boards, every decision
recorded, the corpus checks passing, the chosen card always at the top
vote (a play-out card is a tied or a lost one). The student trained on it
against the one trained on v3's first 30 shards, and then the play-outs
with each, is the next step; the yardstick will not see the difference --
it scores against the votes, and best only chooses among cards the votes
rate equal -- so the arena decides. About six hours for the check and
seventeen for the export at 16 threads.

*best outside, and best as the teacher: the answer (2026-10-08).*
Against XSkat at fixed contracts, seeds 11-16, 200 boards each:
belief-64-shipped +5.30 [+4.27, +6.33], best +6.92 [+5.08, +8.76]; best
minus the teacher paired by seed **+1.62 [+0.39, +2.85]**, every seed
positive (per seed, teacher / best: +5.67 / +7.33, +5.56 / +9.45, +6.81 /
+8.02, +5.06 / +6.39, +3.86 / +4.39, +4.85 / +5.94). The edge measured
against our own teacher holds against a player that is not us, and is no
smaller there. The export: 399,846 records from 30,000 boards in 13.5
hours at 16 threads (8.2 a second; v3 took 10.0). In the auction's own
games the declarer's lost positions are fewer than at greedy's fixed
contracts (7% of the declarer's decisions against about 30%), the
defenders' 17%, the declarer's ties 74%; best's card differs from the
search's in 60,217 records, 15% of the corpus.

*The first turn of the loop, prepared (2026-10-08; `./tools/loop-test.sh`).*
Two students, policy-model's recipe (encoding only, 3 x 512, random start,
20 epochs): policy-v3-30 on teacher-data-v3's first 30 shards (the control,
the same deals and seating) and policy-best on teacher-data-best. The
yardstick on the control's held-out deals, then best's play-outs by each
student against belief-64-shipped, seeds 11-16 and 21, paired by seed;
best with policy-model (90,000 deals of v2) is the reference, not the
control. The play-outs' student is now named in the spec:
`rollout-32x4@policy-best` loads policy-best/policy.bin (a folder in the
working directory, no slash), each folder once.

*The first turn of the loop: the answer (2026-10-08, seeds 11-16 and 21).*
best's play-outs (lost-decl, lost-def, tie0-decl) against belief-64-shipped,
by student:

| play-outs by | trained on | pooled | per seed 11 12 13 14 15 16 21 |
|---|---|---|---|
| policy-best | best's cards, 30,000 deals | **+2.01 [+1.40, +2.63]** | +1.92 +2.03 +3.29 +1.43 +1.70 +2.38 +1.34 |
| policy-v3-30 | the teacher's, the same deals | +1.23 [+0.06, +2.41] | +0.13 +2.97 +2.16 +2.11 +0.10 -0.33 +1.49 |
| policy-model | the teacher's, 90,000 deals (v2) | +1.31 [+0.81, +1.81] | +0.88 +1.77 +1.59 +0.57 +0.90 +2.04 +1.44 |

Paired by seed: policy-best minus policy-v3-30 +0.78 [-0.50, +2.06] (the
control swings from -0.3 to +3.0 and leaves it unresolved); policy-best
minus policy-model +0.70 [+0.15, +1.25], six seeds of seven positive -- a
student taught by best on a third of the deals makes better play-outs than
one taught by the teacher on all of them. The yardstick says the opposite
(regret as declarer 3-9: policy-model 0.45, policy-v3-30 0.73, policy-best
0.72; the 30,000-deal students are both far behind on it): it scores
agreement with the teacher's votes, and what makes a play-out good is
evidently not that. Each student trained in about 30 seconds on the GPU.
The loop turns once and the player improves: best with policy-best is the
strongest player of our own, +2.01 against the teacher, and no SkatZero in
any of it.

*The second turn, prepared (2026-10-08; `./tools/loop-turn.sh`).*
best with policy-best's play-outs is the teacher now, on the next 30,000
deals (boards 30,000-59,999 of seed 101, the same seating draw; no student
sees a deal twice), into teacher-data-best2/. policy-best2 learns that
alone -- like for like with policy-best, the same recipe and amount -- and
policy-best2-u learns turn 1's and turn 2's data together (twice the
deals). best with each doing the play-outs against belief-64-shipped,
seeds 11-16 and 21, paired with policy-best's reports from the loop test
(the arena is deterministic; they are not played again). The question:
does the loop keep climbing, or stop after one turn? `--turn=3` runs the
next one from policy-best2.

*The second turn: the answer (2026-10-09, seeds 11-16 and 21).* The loop
did not climb. best with policy-best's play-outs exported deals
30,000-59,999 (398,985 records, 13.8 hours; 42% of the handed cards
changed, as in turn 1). best's play-outs against belief-64-shipped:
policy-best2 (turn 2's data alone) **+1.07 [-0.03, +2.17]**, per seed
-0.28 +2.95 +0.85 +1.20 -0.21 +0.75 +2.23; policy-best2-u (both turns'
data, 798,831 records) **+1.10 [+0.12, +2.08]**, per seed -0.23 +1.73
+2.06 +0.11 +0.57 +2.63 +0.86. Paired with policy-best (+2.01): -0.94
[-2.28, +0.40] and -0.91 [-1.64, -0.17]. Twice the data bought nothing
(the union is level with the single turn), so the pace of the export is
not what limits the loop. Two readings. Either turn 1's data is better
than turn 2's -- the second teacher's handed cards came from a student's
play-outs that had been taught by play-outs, and whatever drifted in that
step hurt -- or policy-best is a lucky draw of its training: every
student here is one initialisation and one batch order, and the five
students measured so far stand at +1.07, +1.10, +1.23, +1.31 and +2.01,
which a student-to-student spread of about +-0.5 would produce from a
single level near +1.3 with one outlier. The two readings call for
opposite next steps (turn the loop from policy-best again, or stop
turning it), so the draw is measured first: `./tools/student-seeds.sh`
re-trains policy-best twice (init seeds 2 and 3) and policy-best2 once
on their own data, and runs best's play-outs with each, paired with the
student it re-draws; about 11 hours. Until then, turn 1's +0.70 over
policy-model stands as measured but is read as one draw, not as the
loop's gain.

*The draw: the answer (2026-10-10, seeds 11-16 and 21).* The +2.01 was the
draw. The same data again with other initialisations and batch orders:
policy-best-i2 **+1.21 [+0.25, +2.17]**, policy-best-i3 **+1.05 [+0.11,
+1.98]** (teacher-data-best, as policy-best); policy-best2-i2 **+0.99
[+0.16, +1.83]** (teacher-data-best2, as policy-best2's +1.07). Eight
students have now done best's play-outs against belief-64-shipped on the
same seven seeds -- trained on the teacher's cards (policy-model 90,000
deals +1.31, policy-v3-30 +1.23), on best's (policy-best +2.01, its
re-draws +1.21 and +1.05), on turn 2's (policy-best2 +1.07, +0.99) and on
both turns' (policy-best2-u +1.10): mean +1.25, spread 0.33, and without
the one outlier mean +1.14, spread 0.11. Nothing about the training data
-- whose cards, how many, which turn -- moves the result; one draw of the
training moves it by as much as anything we changed. So: (1) the
play-outs' gain is robust, about +1.1 to +1.3 against the teacher for any
student of this family, the worst draw still +0.99 [+0.16, +1.83]; it
comes from the mechanism -- drawn opponents over the search's worlds,
the game's value -- not from the student's precision; (2) the loop does
not climb: a student taught by best is no better at best's play-outs
than one taught by the teacher, so Phase R's "the teacher produces new
data, the student is retrained" adds nothing here, and turn 1's +0.70 is
withdrawn; (3) a student is a draw: comparing two students needs several
draws of each (three draws, pooled, about 11 hours a candidate), and
every single-student comparison before this note is one sample of two
noise sources. The knobs that remain are the mechanism's, with the
student held fixed: the draws' temperature (the top card was +0.28, the
drawn +0.93 -- the one setting already measured), the play-outs' count,
the tie margin, and the opponent-model question (2026-10-09): whether
the best temperature depends on who is across the table.

*The opponents' temperature, prepared (2026-10-10; `./tools/temperature-study.sh`).*
The play-outs' opponents are now drawn at a temperature
(`rollout-32x4t<T>`: the student's scores divided by T before the
softmax; 1 is the draw as it was, bit for bit; `RolloutRescue`'s
constructor takes it, `PlayoutTest` holds the odds at 2 and 0.1 and
`RolloutRescueTest` that it reaches the play-outs; two mutants fail). The
opponent model's strength as one number with the student unchanged, so
the draw of its training stays out. The study: best at temperatures 0.5,
2 and 4 against belief-64-shipped (seeds 11-16 and 21) and against XSkat
(11-16), each pooled and paired by seed with temperature 1, which is on
disk from the play-out study and the best run. It separates the two
readings of top card +0.28 against drawn +0.93: if the best temperature
against XSkat is above the best against the teacher, the opponent model
wants to match the opponent (2026-10-09), and the app's play-outs would
want tuning to the level across the table; if one temperature wins
against both, the drawing is about the signal. About 16-17 hours at 16
threads.

*The opponents' temperature: the answer (2026-10-11).* Flat. best's play-outs
with the opponents drawn at temperature T, paired by seed with T = 1:

| T | against belief-64-shipped (7 seeds) | paired with 1 | against XSkat (6 seeds) | paired with 1 |
|---|---|---|---|---|
| 0.5 | +1.34 [+0.39, +2.29] | +0.03 [-0.92, +0.98] | +6.62 [+4.21, +9.02] | -0.30 [-1.02, +0.41] |
| 1 | +1.31 [+0.81, +1.81] | -- | +6.92 [+5.08, +8.76] | -- |
| 2 | +1.55 [+0.66, +2.43] | +0.23 [-0.65, +1.11] | +6.41 [+4.59, +8.23] | -0.51 [-1.05, +0.03] |
| 4 | +1.15 [+0.16, +2.13] | -0.16 [-1.20, +0.87] | +6.80 [+5.11, +8.49] | -0.12 [-1.14, +0.91] |

No temperature from 0.5 to 4 is distinguishable from 1, against either
opponent. The matching hypothesis (2026-10-09) predicts a flatter model
paying more against the weaker XSkat; the nearest thing to a signal points
the other way (T = 2: +0.23 against the teacher, -0.51 against XSkat), and
neither is resolved. So the step that matters is from the top card (+0.28
in the declarer's lost positions) to any draw at all (+0.93): what the
play-outs need is variety in the opponents' replies, not a model of how
strong they are, and once there is variety its exact sharpness over an
eightfold range makes no difference we can see. The student's own
distribution (T = 1) stays; the real-time opponent model has no measured
basis here and is shelved.

*best against SkatZero, prepared (2026-10-11; `./tools/best-run.sh --check-only --against=skatzero`).*
What `best` gains over the teacher, +1.1 to +1.3, was inferred to put it
about level with SkatZero's card play (SkatZero declaring for the teacher
+1.54, its hand-over in the lost positions +1.00, ours there +0.93); it has
never been measured. The same check as against XSkat: best and the teacher
alone, each against SkatZero, fixed contracts (greedy's auction; SkatZero
never bids), seeds 11-16, 200 boards; pooled each and best minus the
teacher paired by seed. The per-contract lines go into the summary, since
the last oracle-contract pairings (belief-32-shipped, -2.6 over three
seeds) put most of SkatZero's edge in Null, which the play-outs never
touch. SkatZero is an opponent here, not a source: nothing it plays is
recorded.

*best against SkatZero: the answer (2026-10-11, seeds 11-16).* Not level
yet. Fixed contracts (greedy's auction), 200 boards a seed, SkatZero
playing all its cards:

| player | against SkatZero | per seed 11 12 13 14 15 16 |
|---|---|---|
| belief-64-shipped | **-2.00 [-3.23, -0.78]** | -0.03 -1.81 -3.42 -1.66 -2.23 -2.87 |
| best | **-0.96 [-2.27, +0.35]** | +0.44 +0.15 -2.58 -0.36 -1.22 -2.20 |
| best minus the teacher, paired | **+1.04 [+0.48, +1.60]** | +0.47 +1.96 +0.84 +1.31 +1.01 +0.67 |

The play-outs' gain holds a third time, now against the strongest
opponent we have: +1.31 against the teacher, +1.62 against XSkat, +1.04
against SkatZero, every seed positive in all three. But the parity
inferred from the split pairings was too hopeful -- SkatZero declaring for
the teacher was +1.54, best's gain about +1.0 to +1.3, so the arithmetic
always left half a point or more -- and best is still about a point behind
SkatZero's card play, not resolved. Greedy's auction gave no Null here
(the per-contract table holds suit and grand games only), so what is left
is not the Null edge the oracle-contract pairings of September found: it
is in the trump games. The declaring rows carry it -- on five seeds of six
SkatZero's declarer play against our defenders out-scores ours against
its defenders by more than the defending rows differ -- which fits the
split pairings: SkatZero declares better, defends about as we do. Where,
card by card, is what `tools/declaring-audit.sh --par=skatzero` reads
(SkatZero in par's chair, the solver judging both declarers' cards on the
same boards). Cost: about 17 minutes a seed for the teacher, 26 for best.

*Where SkatZero declares better: the audit (2026-10-12; `tools/declaring-audit.sh --par=skatzero`).*
Caution first: the audit's declarer is its default, belief-32-shipped
(32 worlds, no play-outs), not the teacher and not best -- it says where
SkatZero's declaring beats our search family, not what is left against
best. Same boards (seeds 11-16, 1,049 declared at greedy's contracts), our
defenders both times, the solver judging every card. SkatZero wins 57.2%
against our 55.0%, +1.42 from declaring. The gap, by kind:

- Lost-looking positions. 34.7% of our declarer's voted decisions were
  flat zero (every card loses in every sampled world), in 490 games; we
  won 32 of those games, SkatZero 87 of the same boards. Not cold: only
  SkatZero won 73 boards, only we 54. SkatZero is no more accurate double
  dummy -- it throws more (92 throws against our 71) -- but its lines draw
  more mistakes from the defenders: a gift in 31.3% of its not-cold games
  against 28.0% of ours (257 gifts received against 224). This is what
  best's lost-decl play-outs already chase (+0.93 there).
- Won games' value. On cold boards the gap is +0.50, of it +0.28 the same
  result at a different value -- Schneider and points -- what tie0-decl
  chases.
- Our own throws are small: 22 games won at some point and lost (6 by a
  tie, 8 narrow, 8 wide); SkatZero won 18 of those boards.
- The vote is calibrated, a little over-confident in the middle (cards at
  26-50% of the worlds win 33% of the time, not 39%).

So SkatZero's declaring edge over the search family is mostly in the two
places best's play-outs already work on, and very little in plain
accuracy. What is still left against best is the next question: the same
audit with best and with the teacher in our declarer's chair
(`--player=... --tag=best`, the hand-over now reports its vote with the
card actually played, `DeclaringAuditMain` through
`FlatHandoverProvider.withTap`).

*Where SkatZero declares better than the teacher, and than best (2026-10-13).*
The same audit with the teacher and with best in our declarer's chair
(same 1,049 boards, our player defending both games, the solver judging):

| | teacher | best |
|---|---|---|
| SkatZero's card-play share over ours | **3.20** | **1.56** |
| cold boards: gap (of it the same result at another value) | +0.94 (+0.26) | +0.78 (+0.11) |
| cold boards won only by SkatZero / only by us | 17 / 6 | 17 / 6 |
| not cold: gap | +2.26 | +0.77 |
| not cold boards won only by SkatZero / only by us | 75 / 47 | 71 / 57 |
| flat-zero games won by us / by SkatZero on the same boards | 19 / 87 | 34 / 81 |
| a gift in our not-cold games (SkatZero 30.0%) | 24.6% | 27.2% |
| our games won at some point and lost | 18 | 20 |

(The "from declaring" figures are not comparable with the 32-world audit
of 2026-10-12: the defenders are our player in both games, so they change
with it. The gaps are.)

best closes half of SkatZero's declaring edge over the teacher, and where
it was meant to: the not-cold boards (2.26 to 0.77 -- more swindles, the
flat-zero games won 34 times instead of 19) and the value of won games
(0.26 to 0.11). What it leaves is the other half of the cold-board gap,
and it is not value: SkatZero loses 6 cold boards we win, we lose 17 it
wins, the same 17 and 6 with best as with the teacher -- the play-outs
never see these, because when the game is thrown it is neither lost nor
tied. They are our throws of won games: 20 such games with best, the
throwing card chosen over a winning one that was outvoted narrowly (9,
within a tenth of the worlds), widely (7) or tied (4). The calibration
says why the narrow ones happen: the card our declarer plays at a vote of
51-75% wins 49% of the time (the claim is 64%), at 26-50% 26% (claim 38%)
-- the card picked for its vote is picked partly for the vote's luck, and
the sampled worlds are a little too kind. Two levers follow, both ours:
widen the declarer's tie margin so that a near-tie goes to the play-outs
(tie<k>-decl, k of 3 to 6 worlds out of 64 -- the play-outs then choose
among the cards within k of the best by playing them out, which a single
vote's luck cannot fool), and, for the wide ones, the belief that feeds
the worlds. The first is a knob on what exists.

*The declarer's tie margin, prepared (2026-10-13; `./tools/tie-margin.sh`).*
best with the declarer's near-ties handed to the play-outs as well:
tie<k>-decl for k = 2, 4 and 8 worlds of 64, the rest of best unchanged,
against belief-64-shipped, seeds 11-16 and 21, each paired by seed with
best at k = 0 from the play-out study. The audit's narrow outvotes (within
a tenth of the worlds, about 6) fall inside k = 8; the vote's flattery of
the card it picks is a few worlds at the margins that matter. No code
change: the scope already takes a margin. A container smoke on an 8-world
search handed 37% of the declarer's decisions at k = 4 against 30% at 0.
About 12-14 hours at 16 threads.

*The declarer's tie margin: the answer (2026-10-14, seeds 11-16 and 21).*
It costs. best with near-ties handed over too, paired by seed with best at
margin 0 (+1.31):

| margin k (worlds of 64) | against the teacher | paired with k = 0 | per seed 11 12 13 14 15 16 21 |
|---|---|---|---|
| 2 | +0.72 [-0.05, +1.48] | -0.60 [-1.24, +0.05] | -0.43 +0.98 +0.56 +1.26 -0.10 +2.05 +0.70 |
| 4 | +0.50 [-0.29, +1.30] | **-0.81 [-1.61, -0.01]** | -0.66 +0.03 +0.47 +1.38 -0.21 +1.74 +0.79 |
| 8 | +0.83 [+0.10, +1.56] | -0.49 [-1.26, +0.29] | +0.21 +0.36 +0.66 +1.67 +0.09 +2.18 +0.63 |

Every margin is below 0, k = 4 resolved. Few decisions move -- on seed 11
the declarer's handed share goes from 55% at k = 0 to 61%, 64% and 65%,
the added ones mostly ties short of the full vote, in open games -- and
they cost half a point to most of a point: per decision the dearest
change measured. The reading: where two cards' votes differ by even two
worlds of 64, the solver over the sampled worlds knows something the
play-outs do not, and the play-outs' ranking (the game's value first,
under the student's play) overrides it at a loss. The play-outs win where
the vote is silent -- every card lost, or exactly tied -- and lose where
it speaks, however softly. So the thrown won games of the audit are not
the play-outs' to fix; margin 0 stays. What remains for them is the
belief and the worlds (the audit's wide outvotes), and the vote's
flattery of its pick, which is the search's own statistics, not a
hand-over question. About 39 minutes a seed and margin.

*The student and more of best's data, prepared (2026-10-14; `./tools/data-scale.sh`).*
The September curve (the teacher's cards: a quarter, a half and all of
90,000 deals at 0.74, 0.62 and 0.51 held-out regret, about 0.12 a
doubling, twice the epochs nothing) said the student is data-limited. This
continues it on best's cards and asks what matters for a phone player:
does the student on its own get stronger in games? best exports boards
60,000-149,999 of seed 101 (teacher-data-best3, about 41 hours; with
teacher-data-best and -best2, 150,000 deals of best's cards). Three
training draws at 30,000 deals (policy-best, -i2, -i3, on disk), 60,000
(policy-best2-u on disk, policy-b60-i2, -i3) and 150,000 (policy-b150,
-i2, -i3), policy-model's recipe; the yardstick on teacher-data-best's
held-out deals, which no student here has seen; then student-64 -- the
student for every card of a suit or grand game -- with each, against
belief-64-shipped, fixed contracts, seeds 11-16 and 21, the draws of a
size averaged seed by seed and 60,000 and 150,000 paired with 30,000.
About 13 hours of arena on top of the export.

*The student and more of best's data: the answer (2026-10-17).* The curve
is still straight, on the yardstick and in games. Three training draws a
size, policy-model's recipe:

| deals of best's cards | held-out regret, all (three draws) | student-64 against the teacher (draws averaged by seed, 7 seeds) | paired with 30,000 |
|---|---|---|---|
| 30,000 | 0.646 0.638 0.649 | -8.79 [-10.33, -7.26] | -- |
| 60,000 | 0.520 0.515 0.516 | -6.54 [-7.95, -5.14] | **+2.25 [+1.47, +3.03]** |
| 150,000 | 0.398 0.398 0.398 | -3.72 [-5.15, -2.29] | **+5.07 [+4.43, +5.72]** |

The yardstick falls 0.13 from 30,000 to 60,000 and 0.12 more to 150,000
(1.3 doublings): about 0.09-0.13 a doubling, a slight bend at most. The
150,000-deal student regrets 0.40, between an 8-world (0.50) and a
16-world search (0.33), and beats policy-model (the teacher's cards,
90,000 deals: 0.50) as the curve says it should. In games the student
playing every card of a suit or grand game on its own gains about 2.2
game points a game a doubling (+2.25 for the first, +2.8 over the next
1.3), with no bend yet; it is 3.7 behind the teacher at 150,000 deals. The
draws of a size agree within 0.6 to 1.1, so the sizes are not the draw.
This is the other side of 2026-10-10: the student's quality does not
matter to best's play-outs, and it matters a great deal to the student
playing alone, the phone's player. Extrapolated -- these curves bend
eventually, and the ceiling is the player imitated -- level with the
teacher wants about two more doublings, some 500,000-600,000 deals.

Two cheaper questions come first. The 150,000-deal students were at their
best held-out regret in epochs 18-20 of 20, so they may be
under-trained, and 2 million records may want more than 3 x 512: both
are training-only (minutes on the GPU) plus 4 hours of arena a variant.
Then the data: 1,195,777 records took 44.8 hours (7.4 a second), so
another 450,000 deals is about 9 days on this machine, or one to two on
rented cores.

*Training or size? The capacity check (2026-10-17, planned).*
`tools/capacity-check.sh` asks both cheaper questions on
teacher-data-best-150 alone, no new export. Two training-only variants,
three init draws each (seeds 1-3):

- **e40** -- 3 x 512, 40 epochs, the one-cycle learning rate stretched to
  fit (policy-b150-e40, -i2, -i3). A best epoch at the end of a one-cycle
  schedule is what the schedule does, not proof of under-training; this
  asks whether the longer cycle pays.
- **h1024** -- 3 x 1024, about four times the weights and the
  multiply-adds a card (still a few milliseconds on a phone), also 40
  epochs (policy-b150-h1024, -i2, -i3), so it is read against e40 and
  the two changes stay apart.

The yardstick on teacher-data-best's held-out deals beside the three
20-epoch students of the data-scale run, then student-64 with each
against belief-64-shipped, fixed contracts, seeds 11-16 and 21; the draws
of a variant averaged seed by seed, e40 paired with the 20-epoch students
(their reports in arena-logs/data-scale/) and h1024 with e40. About 8.5
hours of arena. If neither moves the student, the 3.7 left are the data's
to close, and the next 450,000 deals are the step.

*Training or size: the answer (2026-10-18).* Neither. The 150,000-deal
student is short of data, not of epochs or width.

| variant (three draws) | best epoch | held-out regret, all | student-64 against the teacher (7 seeds) | paired |
|---|---|---|---|---|
| 3 x 512, 20 epochs (data-scale) | 18-20 of 20 | 0.398 0.398 0.398 | -3.72 [-5.15, -2.29] | -- |
| e40: 3 x 512, 40 epochs | 39, 24, 39 of 40 | 0.397 0.396 0.398 | -3.84 [-5.16, -2.52] | minus 20 epochs: -0.12 [-1.05, +0.80] |
| h1024: 3 x 1024, 40 epochs | 14, 15, 16 of 40 | 0.419 0.421 0.421 | -4.30 [-6.07, -2.52] | minus e40: -0.45 [-1.29, +0.38] |

The longer cycle changes nothing: on the training run's own held-out
tricks the regret is flat at 0.75 from epoch 25 to 40 while the held-out
loss turns up after epoch 25, so 3 x 512 is already at the edge of what
2 million records support. The wider net overfits outright: its
held-out loss is lowest near epoch 13 and climbs from 0.85 to 1.10 by
epoch 40 while its training loss halves, and even at its kept (best)
epoch it is worse on the yardstick (0.42 against 0.40) and, within the
noise, in games. Best epochs at the end of a one-cycle schedule were the
schedule, not under-training.

So the lever is the data. Before 9 days of export, a cheaper way to more
of it: the encoding is absolute in the suits (cards suit-major,
BeliefEncoding.index), so the net learns every suit arrangement apart.
The game is symmetric under permuting suits -- the side suits of a suit
game (6 ways), the non-jack cards of a grand (24), whole suits in a Null
(24) -- and the teacher's votes and the solver are with it, up to
the order of the rule tiebreak. Permuting the
features and the per-card targets in the trainer multiplies the records
without a new board; whether it is worth a doubling is a training-only
question.

*The suits permuted (2026-10-18, planned).* `train_policy.py
--permute-suits` draws, for every training record and every epoch, a
permutation of the suits the contract admits and moves the inputs and the
targets with it (`arena/python/suit_permutation.py`): the three side suits
of a suit game (the trump suit stays: the contract and the bids are priced
on it, and the encoder's trump counts change when it moves), the four suits
of a grand or a Ramsch with the jacks kept, the whole suits of a Null. It
moves the eight per-card fields, the follow classes shown out of, and in a
Null the unseen jacks; the legal cards, the card played, the votes and the
play so far with them; the held-out set is never permuted. A field the
module has not been told about stops it. Two checks before it trains: 192
pairs the Java encoder wrote itself (`SuitPermutationFixtures`: a random
position and the same position with every card moved, both through
`BeliefEncoding`; committed as `arena/python/fixtures/suit-permutation.bin`,
and `SuitPermutationFixturesTest` fails when the encoder writes other
bytes), which the table must reproduce byte for byte -- mutants that move
the jacks outside a Null, keep them in one, or leave the voids, the unseen
jacks or a card field behind all fail it -- and the corpus checks of
`teacher_data.run_checks` on 100,000 permuted records, which catch a
verdict or a play state left unmoved.

A bid names suits without saying so (22 is spades), and a permutation
leaves it saying the same number. Mode `all` permutes anyway, 8.2
permutations a record on average (6 in a suit game, 24 in a grand); mode
`bids` only those mapping each remembered bid's suits (and the winning
bid's) onto themselves, 3.2 on average -- the declarer's 18 alone pins the
diamonds in most hearts, spades and clubs games. Without the flag the
trainer is unchanged, epoch for epoch.

A first reading on six shards of teacher-data-best (5,400 training
deals), one draw, 3 x 512, 12 epochs on the CPU, the run's own held-out
deals: regret 1.87 unpermuted (best at epoch 9; training loss 0.59 against
1.28 held out by epoch 12), 1.57 with `all`, 1.62 with `bids`. That is a
small-data reading and one draw; `tools/permute-check.sh` makes the real
one: `all` and `bids` at 20 and 40 epochs on 30,000 deals, `all` at 20 and
40 and `bids` at 20 on 150,000, three draws each; the yardstick beside the
unpermuted 30,000-, 60,000- and 150,000-deal students, each variant's
regret read off that curve as the deals it is worth; then student-64 with
the best 150,000-deal variant against belief-64-shipped, seeds 11-16 and
21, paired with the unpermuted students of the same epochs. About 1.2
hours of training and 4.2 of arena. Nothing changes at play time: the
student reads the same encoding.

*The suits permuted: the answer (2026-10-18).* It pays, on the yardstick
and in games. Three draws a variant; the yardstick on teacher-data-best's
held-out deals, read off the unpermuted curve (30,000 0.644, 60,000 0.517,
150,000 0.398) as the deals it is worth:

| deals | variant | held-out regret | worth, unpermuted |
|---|---|---|---|
| 30,000 | all, 20 epochs | 0.526 | about 57,000 |
| 30,000 | all, 40 epochs | **0.482** | about 79,000 |
| 30,000 | bids, 20 / 40 epochs | 0.557 / 0.553 | about 48,000 |
| 150,000 | all, 20 epochs | 0.366 | about 192,000 |
| 150,000 | all, 40 epochs | **0.339** | about 236,000 |
| 150,000 | bids, 20 epochs | 0.377 | about 176,000 |

(Beyond 150,000 the worth extends the curve's last segment: an
extrapolation.) `all` beats `bids` everywhere: the more permutations are
worth more than the bids they leave contradicted. And 40 epochs now pays
where unpermuted it did not (2026-10-18: -0.12), the best epochs at 37-40
of 40: the permuted records hold more than 20 epochs take in. The gain
shrinks with the data, 2.6 times the deals at 30,000 and 1.6 at 150,000,
as an augmentation should.

In games, student-64 with the three `all`/40 students against
belief-64-shipped, seeds 11-16 and 21: -2.61 [-3.99, -1.23], against
-3.84 for the unpermuted 40-epoch students; **paired +1.23 [+0.35,
+2.11]**. The data-scale curve's 2.2 game points a doubling over the
0.65 doublings the yardstick reads predicts +1.4: the yardstick and the
games agree again. `--permute-suits all` at 40 epochs is the student's
recipe from here; nothing changes at play time.

*Next: the data, and the recipe beside it (2026-10-18, planned).* Two runs
at once, one on the cores and one on the GPU. `tools/export-more.sh`
exports best on boards 150,000-599,999 of seed 101 as data-scale did (the
same seating draw, population and mix) into teacher-data-best4/, about
2,000 deals an hour, 9 to 10 days; it resumes shard by shard, and
`--assemble-only` builds teacher-data-best-<N> (the 150,000 and every
complete shard so far) for a point on the curve before it is through --
teacher-data-best-600 at the end. `tools/recipe-check.sh` asks, on the
permuted records, what the unpermuted ones answered no to: 60 and 80
epochs (the permuted 40 were best at epochs 37-40), and 3 x 1024 at 40 and
80 (it overfitted by epoch 14 unpermuted); three draws each, the yardstick
beside pa40 and the unpermuted students. Training only by default, so it
does not take the export's cores; `--arena=auto` later plays the
lowest-regret variant against pa40, and `--data=teacher-data-best-600`
repeats the whole question on the larger corpus.

*The recipe and 600,000 deals: the answer (2026-10-31).* The export
finished: boards 150,000-599,999 in 258.7 hours (450 shards, 5.96 million
records), teacher-data-best-600 assembled. Both recipe checks, three draws
a variant, suits permuted throughout; the yardstick on teacher-data-best's
held-out deals:

| variant | 150,000 deals | 600,000 deals |
|---|---|---|
| 3 x 512, 40 epochs (pa40) | 0.339 | 0.297 |
| 3 x 512, 60 epochs | 0.330 | 0.293 |
| 3 x 512, 80 epochs | 0.325 | 0.288 |
| 3 x 1024, 40 epochs | **0.324** | 0.278 |
| 3 x 1024, 80 epochs | 0.332 (overfits after ~50) | **0.274** |

In games, student-64 against belief-64-shipped, seeds 11-16 and 21, the
draws averaged seed by seed:

- at 150,000, 3 x 1024/40 -2.39 against pa40's -2.61: **+0.22 [-0.26,
  +0.71]**;
- at 600,000, 3 x 1024/80 -1.26 against pa40's -1.41: **+0.15 [-0.53,
  +0.83]**;
- pa40 at 600,000 against pa40 at 150,000: **+1.20 [+0.41, +1.98]**; against
  the unpermuted 20-epoch students at 150,000, +2.31 [+0.80, +3.81].

Two readings. Width and epochs buy yardstick (0.02 at 600,000) and next
to nothing measurable in games; a 3 x 1024 student costs the phone four
times the multiply-adds for at most a few tenths, so 3 x 512 with the
suits permuted at 40 epochs stays the recipe. And the curve bends: four
times the deals gave +1.2 game points, 0.6 a doubling against the 2.2 a
doubling from 30,000 to 150,000, and the yardstick fell 0.04 over the two
doublings against 0.12-0.13 a doubling before. The student playing on its
own is now 1.4 behind the teacher (it was 8.8 at 30,000 unpermuted) and
regrets 0.30 worlds of 32, between a 16- and a 32-world search; best,
whose cards it imitates, is +1.31 above the teacher. More of the same data
is no longer the cheap lever it was.

*The size test (2026-10-31, planned).* Is the student short of size, now
that more data has stopped paying? SkatZero's nets are no bigger than ours
(five layers of 512 and an LSTM of 128 over the history, about 1.5
million weights each, nine of them by game type and seat); what it does
better on our yardstick -- the declarer's endgame -- it learned from
outcomes. Still, 3 x 1024 at 600,000 deals was best at epochs 78-80 and
improved the yardstick from 0.297 to 0.274, mostly in tricks 4-6, so the
question is open. `tools/recipe-check.sh` takes `--variants` and `--draws`:
`--data=teacher-data-best-600 --variants="h1024x5-pa80 h2048x4-pa80"
--draws=1` trains five layers of 1,024 (4.5 million weights) and four of
2,048 (13 million), 80 epochs, suits permuted, one draw each, and runs the
yardstick beside pa40 and 3 x 1024/80 (their three draws on disk). Training
only; games for whichever clearly beats 0.274.

*The size test: the answer (2026-10-31).* One draw each, 600,000 deals,
suits permuted, 80 epochs; the yardstick (all / tricks 4-6 / 7-9):

| network | weights | held-out regret | tricks 4-6 | tricks 7-9 | best epoch |
|---|---|---|---|---|---|
| 3 x 512, 40 epochs (pa40, three draws) | 0.7 M | 0.297 | 0.38 | 0.22 | 37-40 |
| 3 x 1024 (three draws) | 2.5 M | 0.274 | 0.34 | 0.21 | 78-80 |
| 5 x 1024 | 4.6 M | **0.265** | 0.32 | 0.20 | 79 |
| 4 x 2048 | 13.4 M | 0.279 | 0.34 | 0.21 | 52 |

Depth buys a little more (0.009 on 3 x 1024, against a draw-to-draw spread
of about 0.002); width to 2,048 overfits: held-out loss lowest near epoch
40 (0.709), 0.754 by 80 while training loss falls to 0.556; the
teacher's card agreed with about 70% of the time at every size. Each
doubling of the weights buys less (0.023, then 0.009, then nothing), and
the 0.023 of 3 x 1024 was +0.15 [-0.53, +0.83] in games. Size is not what
holds the student back: a few hundredths of yardstick, a few tenths of a
game point at most, for many times the phone's work. The endgame, where
the 32-world search regrets 0.04, stays at 0.20 whatever the size. Next:
learning from outcomes, starting from 3 x 512 (or 5 x 1024 if the phone's
budget allows).

*Route 1: learning from outcomes, the plan (2026-10-31).* The student is
now limited by what imitation can teach, not by data or size: 1.41 behind
the teacher on its own (policy-b600-pa40), its endgame at 0.20 worlds of 32
whatever its width, while SkatZero -- a net of the same size, 1.5 billion
self-play games -- learned the declarer's endgame from outcomes. The DMC
pilot (2026-10-02) showed how not to: one outcome per decision is decided
by the deal, not the card, and drowned the difference between cards (value
head worse every iteration, policy drifting). The plan follows that
note's own conclusion -- compare the cards on the same deal:

1. *The all-cards self-play exporter.* Self-play with the student in all
   three seats, fixed suit and grand contracts (greedy's auction), board
   seeds away from 101. At a decision, every legal card is played out to
   the end of the game K times on the true cards, each seat played by the
   net from its own evidence only (no seat sees a hidden card), the others'
   cards drawn from the softmax as in rollout-32x4; each card labelled with
   its mean win, card points and game value. The deal is the game's own,
   a draw from the true posterior given the decider's knowledge, so the
   labels estimate each card's value with no belief model in them, and
   the deal's variance cancels between the cards of one decision.
   RolloutRescue's lockstep batched play-outs do this in sampled worlds
   already; here the world is the real one. Throughput first.
2. *Training,* from policy-b600-pa40: the value head on every legal card's
   label, the policy head towards the best card by the labels (game value,
   then wins, then points, as best ranks), the teacher-data-best-600
   imitation loss mixed in as an anchor against the pilot's drift, suits
   permuted on both.
3. *Measures:* the yardstick by trick (the referee is double-dummy, so a
   gain in games with a flat yardstick is possible), then student-64
   against belief-64-shipped, seeds 11-16 and 21, three draws, paired
   with policy-b600-pa40.
4. *Iterate:* the new net plays and labels the next round; opponents
   partly from earlier nets and fixed outsiders (Phase P's rule); two
   failed gates end it.

Out of scope for now: the auction, Null, Ramsch. No SkatZero in any
training data; it stays a held-out yardstick, measured at the end.

*The play-outs' discard defect, fixed (2026-10-31; `tools/defender-fix.sh`).*
Found while reading `RolloutRescue` for the route-1 exporter. In a play-out
every seat reads its own evidence, and the declarer's includes its discard.
`tally` took that discard from the deciding seat's evidence. When the
declarer decided, that was right; when a defender decided, the defender's
evidence has no discard, so the declarer in every one of those play-outs
played as though it had none (its discard field empty, as in a hand
game). Only the defenders' scopes ran that path: `lost-def` and every
`tie<k>-def`. The declarer's scopes (`lost-decl`, `tie<k>-decl`), student-64
and the yardstick never did. So `best` (+1.31 [+0.81, +1.81], which holds
`lost-def`), the defenders' own verdicts of the play-out study and its
narrowing (lost-def +0.07, tie0-def -0.43, tie0w-def -0.18, tie0c-def +0.31,
all four +0.86), the best-run pairings against XSkat and SkatZero, and the
data best exported were all measured or made with it. The +0.07 is the
play-out study's `def-lost` arm (2026-10-06): `lost-def` alone against the
teacher, seven seeds, about 10% of the defenders' decisions handed over and
the card changed in a quarter of those. Its share inside best was never
measured on its own; best matched the per-seed sum of its parts (+1.42
predicted), which counted it at +0.07.

The fix (`RolloutRescue.declarersDiscard`): the play-out's declarer remembers
the world's skat when a defender decides -- in a sampled world, the
declarer's discard as that world has it -- and nothing in a hand game, where
it never saw the skat; when the declarer decides, its own evidence as
before. Tests: a defender's tally equals a reference that gives every seat
its table evidence, and differs from a forgetting declarer on enough
decisions to have power (`aDefendersPlayOutsGiveTheDeclarerItsDiscard`); the
rule itself, hand games and a declarer whose world's skat is not its discard
included (`theDiscardRuleNamesTheDeclarersOwn`); the defender-turned-round
test's reference now gives the declarer its discard. The replay
(`DefenderDiscardReplayMain`, `:arena:defenderReplay`) plays every rotation
of a few boards as the duplicate match seeds it, in the old form and the new,
the new one also picking by the old form at every hand-over on the same
decision and streams: declarers' hand-overs must not move, a game must be
the same card for card unless a hand-over moved, and then part exactly at
the first one, a defender's, with the old game playing the shadow's card.
`DefenderDiscardReplayTest` runs it on a 4-world search with random play-out
weights (18 games: 75 declarer hand-overs, none moved; 127 defenders', 42
moved; 15 games parted, each at a defender's hand-over) and checks the
comparison on made-up games. Six mutants fail: the old rule, the fix spilt
onto the declarer, hand games ignored, the one-by-one reference left
unfixed, a shadow on other streams, the old form not honoured in the
replay.

The study, prepared: step 0 the replay with best on ten boards of seed 11
(stops the script if a rule breaks); then against belief-64-shipped, fixed
contracts, seeds 11-16 and 21, 200 boards, into arena-logs/defender-fix/:
`best` fixed, paired with the old best reports; `best-nolostdef`
(lost-decl,tie0-decl, untouched by the fix), so best minus it is lost-def's
own share; `best-tie0`, `best-tie0w`, `best-tie0c` (best plus each
defender-tie scope), each paired with fixed best, and best-tie0 also with
the old `all` (the same spec). The old single-scope arms can be rerun with
`--only=def-lost` etc., each paired with its own old report. About 4.5 hours
an arm at 16 threads. Not now: re-exporting best's 600,000 deals; the
temperature, tie-margin and declaring-audit verdicts stand (the first
compared arms under the same defect, the other two are declarer-only).

*The discard fix: the answer (2026-11-01, seeds 11-16 and 21).* The defect
was nearly inert, and best stands. The replay with best against the teacher
(ten boards of seed 11, 60 games): 179 declarer hand-overs, none moved; 45
defender hand-overs, 5 moved (11%); 55 games the same card for card, the 5
others parting exactly at the moved hand-over -- the fix is as narrow as
built. Against belief-64-shipped, paired by seed:

| arm | against the teacher | paired | per seed 11 12 13 14 15 16 21 |
|---|---|---|---|
| best, fixed | +1.30 [+0.78, +1.83] | minus the old best -0.01 [-0.06, +0.04] | -0.12 +0.05 -0.02 0 0 +0.02 0 |
| best without lost-def | +1.24 [+0.72, +1.76] | lost-def's own share: best minus it **+0.06 [+0.04, +0.09]** | +0.06 +0.07 +0.03 +0.09 +0.02 +0.10 +0.06 |
| best + tie0-def | +1.40 [+0.18, +2.62] | minus best +0.10 [-1.23, +1.42]; minus the old all +0.54 [-0.13, +1.21] | +2.04 -0.19 -1.56 -1.24 +1.93 -0.64 +0.34 |
| best + tie0w-def | +1.31 [+0.74, +1.88] | minus best +0.01 [-0.31, +0.32] | -0.23 +0.21 -0.63 +0.42 +0.05 +0.08 +0.17 |
| best + tie0c-def | +1.56 [+1.03, +2.09] | minus best +0.26 [-0.28, +0.80] | +0.89 -0.29 +0.57 -0.11 +0.63 -0.62 +0.74 |

(per seed: the paired difference.) Three readings. (1) best is unchanged: on
three seeds of seven the fixed and the old games score identically, on the
other four by at most an eighth of a point; whatever the declarer's discard
does to the student's play-outs, it rarely turns the card the play-outs pick
in a defender's lost position, and turns the game less. Nothing downstream of
best -- the XSkat and SkatZero pairings, best's exported data, the students
taught on it -- needs redoing. (2) lost-def is worth +0.06, now measured
inside best and resolved on every seed: the old single-scope +0.07 was right.
(3) The defenders' ties are not a loss after all, and not a gain either. With
the fix, adding tie0-def to best is +0.10 with seeds from -1.6 to +2.0 -- the
old "the defenders' ties eat what the other two add" (all minus lost-decl
-0.08) is withdrawn; `all` itself moved +0.54 [-0.13, +1.21], since its
tie0-def hands over half the defenders' decisions and the defect sat in every
one of them. Held ties only are exactly nothing (+0.01 [-0.31, +0.32]); the
ties the cushion leaves give the best pooled player measured, +1.56 against
the teacher, but only +0.26 [-0.28, +0.80] over best, four seeds of seven
positive -- not clear of zero, so best stays best. At its paired spread
(about 0.58 a seed) resolving +0.26 would take about twenty seeds. No step 3
(best did not move), no re-export.

*The all-cards exporter, built (2026-11-01; `tools/allcards-export.sh`,
`AllCardsExportMain`, `TrueDealLabeller`, `arena/python/allcards_data.py`).*
Route 1's step 1, with three settings chosen before it was built (Philipp,
2026-11-01):

- *The main line* takes the policy head's top card, a fifth of the time a
  card drawn from its softmax (epsilon 0.2). The game only finds the
  positions; the labels teach. It must never take a card computed from the
  hidden cards (the labels' best, say): every later position would then
  come from a player that peeks, and the deal would no longer be a draw from
  the posterior the honest seats hold -- the property the labels' meaning
  rests on. The student plays it because its own positions are the ones to
  label: those it will meet, mistakes included.
- *k = 8* play-outs a card, to be confirmed by the quick run.
- *The policy target* (for the trainer, next): "raise the expected score",
  not "the best card on this deal". The labels are measured on the true
  cards, which the decider cannot see; the per-deal best card teaches which
  card is most often best, not which is best on average (a card that wins a
  little on 60% of deals beats one that wins a lot on 40% by count and loses
  on expectation). The trainer moves probability towards cards whose game
  value is above the legal cards' mean, in proportion; that is linear in the
  labels, so over deals it aims at the expected game value itself. The
  per-deal best card is trained beside it for comparison.

What it does. The student (policy-b600-pa40) in all three seats at greedy's
fixed suit and grand contracts, boards of seed 9001 (anything below 1000 is
refused: 101 is the teacher corpora's, 11-16 and 21 the arena's). At every
decision with a choice, after the main-line card is chosen from the seat's
own context and stream, every legal card is played out k times on the
table's real cards (read through `TableObserver`): every seat by the student
from its own evidence (perfect memory, the bids heard, the declarer its
discard), the decider's side by the top card, the other side drawn at
temperature 1; draw d the same random stream for every card. Each card's
wins, card points and game value from the decider's side go into a 396-byte
record (layout in AllCardsExportMain's comment and `allcards-format.json`),
slots in card-index order; `allcards_data.py` lays them out by card. Shards
of 2,000 boards, the same bytes at any thread count; resumable; a STOP file
stops between shards.

Tests (`AllCardsExportTest`, `TrueDealLabellerTest`), on a net of random
weights: the table handed to the play-outs is the real one (each seat holds
exactly the cards it goes on to play, the skat is the declarer's discard);
with the top card everywhere, the play-out of the main line's own card is
the main line -- every position it feeds the net equals the next main-line
decision float for float, and it ends on the main line's win, points and
game value from the decider's side; labelling does not move the main line; a
shard is the same bytes at one thread and three; a record's slots are its
legal cards' labels in index order; a card's label is the same alone, among
others and in reverse order; the opponents are drawn. Thirteen mutants fail
(two wrong tables, the discard or the bids dropped, the value's sign, the
points' or the wins' side, the actor's stream shared with the labeller, a
stream that depends on the thread or on the card, slots reversed, opponents
not drawn, the main line never drawn). The reader checks every shard against
the exporter's own totals by card, then the records (legal counts, empty
slots, bounds, signs, the legal cards in the hand, a whole-board split, labels
that move with a suit permutation); five reader mutants and a shard with one
byte changed fail it.

A container smoke on the real student (24 boards, k 8, 2 cores): 0.33 games
a second at two threads; at k 16 21,500 positions through the net a game,
thirteen times more a decision in trick 1 than in trick 8. At
k 16 (20 boards): a card's play-outs spread 27 game points in trick 1, the
cards' means 15; by trick 8 1.2 against 3.4. The first eight play-outs picked
the same best card as the other eight in 49% of trick-1 decisions, 68% in
trick 4, 88% in tricks 5-6, 98% in trick 8: early labels are noisy, late ones
nearly exact. The student's own top card was the labels' best in 30-45% of
decisions in tricks 1-7, and 55-60% in tricks 8-9 -- on the deal and with the
noise in, so an upper bound on what is there to learn. The quick run on
bigbox (2,000 boards at k 16) gives the real speed and the k check.

*The all-cards quick run: k 8 stays (2026-11-01, bigbox, 24 threads).* One
shard, 2,000 boards of seed 9001 at k 16: 1,749 games (251 boards without a
suit or grand contract), 40,153 labelled decisions in 15 minutes -- 1.94
games and 45 decisions a second, 21,400 positions through the net a game,
2,000 a decision in trick 1 and 27 in trick 9. Every check of the reader
passes. By trick, in game value a play-out from the decider's side:

| trick | sd within a card | sd between cards | first 8 and last 8 pick the same card |
|---|---|---|---|
| 1 | 36.3 | 19.8 | 55.6% |
| 3 | 32.7 | 20.7 | 64.3% |
| 5 | 26.8 | 22.6 | 76.4% |
| 7 | 16.8 | 22.4 | 91.8% |
| 8 | 10.6 | 20.3 | 97.4% |
| 9 | 4.5 | 19.6 | 99.6% |

The last column is exactly what two independent labels at k 8 do: in the
endgame k 8 settles the card; in the opening one k-8 label is a coin flip
weighted towards the right card. That decides less than it looks. The
policy target chosen ("raise the expected score") is linear in the labels,
so their noise is unbiased and averages out over decisions; the play-outs'
noise on a card's mean falls as 1/k per decision, and the spread of the
deals as 1/decisions, so for a fixed budget more decisions at a smaller k
are at least as good. k 8 stays (twice the decisions of 16; the per-deal
best-card comparison arm, which noise does bias, keeps some precision).
Two cautions on the other columns. The between-card spread holds the noise
too: without it about 18 game points in trick 1 (19.8 squared minus 36.3
squared over 16), so the cards really differ. And the student's "regret"
on the deal (8.6 game points a play-out in trick 1, 0.75 in trick 9) is
mostly the noise's own maximum early on (a card's mean is 9 points uncertain
at k 16 in trick 1), and only late a measure of what is there to learn. The
"top is best" column counted a full tie as a miss (the ranking takes the
earlier of equal cards), which is why trick 9 read 67% where the labels are
exact; fixed to count ties as best, the reader's version of it (the main
line's card, drawn ones included) reads 36% in trick 1 rising to 64% in
trick 7, 80% in trick 8 and 92% in trick 9. The script's checks step failed
on bigbox -- it changed directory before calling a relatively named python;
it now runs the reader from the repository root, and the dry run uses a
relative python too. At k 8 the full run should take about 6 hours for
100,000 boards.

*The first all-cards corpus (2026-11-02, bigbox, 24 threads).*
`allcards-data/r1-k8-s9001`: boards 0-99,999 of seed 9001 at k 8, policy-b600-pa40
in all three seats -- 87,512 games (12,488 boards without a suit or grand
contract), 2,010,969 labelled decisions in 6.2 hours (3.93 games and 90
decisions a second, 10,740 positions through the net a game); every check
passes. The per-trick table repeats the quick run's at k 8: the first four
and last four play-outs pick the same card in 56% of trick-1 decisions, 76%
in trick 5, 92% in trick 7, 97% in trick 8 and 99.5% in trick 9; the
student's top card is the labels' best or tied in 38% of trick-1 decisions
rising to 67% in trick 7, 81% in trick 8 and 93% in trick 9.

*The trainer, built (2026-11-02; `arena/python/train_outcomes.py`,
`tools/outcomes-train.sh`).* From a student's full weights (both heads),
three losses a step: the policy by --objective -- `expected`, minus the
policy's expected advantage in U over the legal cards' mean (U = mean game
value a play-out + 1 x win share + 0.01 x mean card points; the advantage
divided by 20), or `best`, cross entropy towards the per-deal best card by U,
ties shared; the value head on each legal card's win share; and the anchor, a
batch of teacher-data-best-600 a step with the imitation loss on the card
played (its value term off: the value head now means the self-play outcome).
Suits permuted on both. AdamW 3e-4, 200 steps' warm-up, cosine to zero, 10
epochs by default. Held out: a tenth of the all-cards boards, and on the
anchor the init's own split, the yardstick's deals. Kept: the epoch with the
highest held-out gain of the top card over the legal mean. The self-test runs
the objectives on made-up decisions where the answer is known -- a card that
gains 2 on 60% of deals and loses 1 on the rest against one that gains 6 on
40%: `expected` must take the second (mean 2.4 against 0.8), `best` the first
-- plus the masks (an illegal card's logit changes neither loss; when every
legal card is negative the per-deal target stays on the least bad legal one),
the gradient's sign, a constant shift, ties, the value mask; six mutants fail
it. A CPU smoke in the container (the quick shard and three teacher shards,
two epochs) trained, exported, ran the yardstick and loaded in Java with its
parity check.

The study (`./tools/outcomes-train.sh`): ev = `expected` and bc = `best`, each
from policy-b600-pa40 and its two re-draws (init seeds 1-3, draw d paired
with pa40's draw d); the self-test first; the yardstick on teacher-data-best;
then student-64 with each against belief-64-shipped, fixed contracts, seeds
11-16 and 21, 200 boards, pa40's reports reused from the recipe check;
pooled each minus pa40 and ev minus bc, the draws averaged seed by seed.

*Route 1, round 1: the answer (2026-11-02).* Six students, each from its
pa40 draw, 10 epochs on allcards-data/r1-k8-s9001 with the anchor; about 4
minutes each on the GPU. student-64 against belief-64-shipped, fixed
contracts, seeds 11-16 and 21, 200 boards, the three draws averaged seed by
seed and paired with pa40's draws:

| student | against the teacher | minus pa40, paired | per seed 11 12 13 14 15 16 21 |
|---|---|---|---|
| pa40 (the start) | -1.41 [-2.73, -0.10] | -- | |
| ev (raise the expected score) | -0.37 [-2.08, +1.35] | +1.05 [-0.16, +2.26] | +0.06 -1.38 +1.49 +1.15 +2.13 +1.46 +2.43 |
| bc (the per-deal best card) | -0.68 [-1.99, +0.62] | **+0.73 [+0.15, +1.31]** | -0.36 +0.80 +0.32 +1.06 +1.67 +0.76 +0.86 |
| ev minus bc | | +0.32 [-0.80, +1.44] | +0.43 -2.18 +1.16 +0.09 +0.46 +0.70 +1.57 |

Learning from the outcome labels pays in games: both objectives gain six
seeds of seven, the per-deal best card resolved at +0.73, raise-the-expected-
score larger at +1.05 but not resolved (seed 12 at -1.4 against a run of
+1.1 to +2.4); between the two nothing is resolved. The student on its own
is now level with the teacher within the noise (ev -0.37 [-2.08, +1.35]),
where imitation had left it 1.41 behind at 600,000 deals and bending.

On the training's own held-out boards the gain of the top card over the
legal mean rose from +3.85-3.91 to +4.49-4.52 for ev (tricks 1-3 +0.8, 4-6
+0.7, 7-9 +0.4) and to +4.45-4.47 for bc, which instead puts its card on the
deal's best more often (58.4% against ev's 57.2%, from 55.6%) -- each
objective winning on its own measure, as it should. Both flatten by epoch 5
(ev +4.46 at 5, +4.49 at 10): the data, not the epochs, is what the next
gain needs. ev kept the imitation where it was (teacher regret 0.563 ->
0.561, 0.563 -> 0.562, 0.559 -> 0.557); bc moved away from it (0.563 -> 0.593,
0.597, 0.585). The value head is calibrated within a point at every trick
(trick 1 0.487 predicted against 0.491 won). The yardstick barely moves for ev
(0.305 against pa40's 0.297, tricks 7-9 0.229 against 0.221) and worsens for
bc (0.321): its referee is double-dummy, and these labels come from honest
play -- a gain in games with a flat yardstick, as the plan allowed. The hoped
for endgame repair does not show on it; where in the game the +1.05 sits is
not yet measured.

Round 1 passes its gate (a gain in games, resolved for one objective, the
larger and unresolved for the other). Next: round 2 -- policy-oc1-ev (draw 1,
not chosen by its games, which would select on the arena's seeds) exports
the next 100,000 boards (seed 9002), and the round-2 students train on them.

*Round 2's corpus, and the trainer by round (2026-11-03).*
`allcards-data/r2-k8-s9002`: boards 0-99,999 of seed 9002 at k 8, labelled by
policy-oc1-ev (draw 1) in all three seats and its play-outs -- 87,649 games,
2,004,211 decisions in 6.1 hours, every check passed. Against round 1's
table (the measured and the measuring student both changed, so this is the
new student judged by its own play, not a like-for-like):

| trick | sd within a card r1 / r2 | halves agree r1 / r2 | top is best r1 / r2 | top's regret r1 / r2 |
|---|---|---|---|---|
| 1 | 35.9 / 30.4 | 56.0% / 62.9% | 38.1% / 39.0% | 10.06 / 9.26 |
| 4 | 30.0 / 24.7 | 68.7% / 75.9% | 43.0% / 44.6% | 8.73 / 7.93 |
| 7 | 17.5 / 13.3 | 91.5% / 94.0% | 66.8% / 70.2% | 4.36 / 3.61 |
| 8 | 11.4 / 8.4 | 96.8% / 97.9% | 80.5% / 83.6% | 2.57 / 1.91 |
| 9 | 5.1 / 3.9 | 99.5% / 99.6% | 92.6% / 94.6% | 0.93 / 0.58 |

The student finds its own labels' best card more often and gives up less
against it at every trick, most in the endgame (trick 8 -26%, trick 9 -38%
regret). One thing to watch: the spread of a card's play-outs fell by a
sixth to a quarter at every trick. The play-outs' opponents are drawn from
the student's softmax, and a sharper student draws less varied replies --
the variety that made the drawn defences pay (+0.93 against the top card's
+0.28) could thin out round by round.

`train_outcomes.py --data` takes several corpora; a board is held out by its
index alone (self-test: whole boards, and a corpus's held-out boards are
round 1's alone and among others; two mutants fail it), two corpora of the
same seed are refused. `tools/outcomes-train.sh --round=r` trains round r
from round r-1's `--from` arm (default ev), each draw from the same draw: `ev`
on round r's corpus, `evu` on every round's so far, `bc` as before; pooled
each minus its start, minus pa40, and the first arm minus the second. Round 1
keeps its names and files.

*Route 1, round 2: the answer (2026-11-03).* Six students from the three
policy-oc1-ev draws, 10 epochs; ev on round 2's corpus, evu on rounds 1 and 2.
student-64 against belief-64-shipped, seeds 11-16 and 21, 200 boards, the
draws averaged seed by seed:

| student | against the teacher | minus its start (oc1-ev) | minus pa40 |
|---|---|---|---|
| policy-oc1-ev (the start) | -0.37 [-2.08, +1.35] | -- | +1.05 [-0.16, +2.26] |
| ev (round 2's labels) | **+0.07 [-1.04, +1.18]** | +0.43 [-0.41, +1.28] | **+1.48 [+0.93, +2.03]** |
| evu (rounds 1 and 2) | -0.17 [-1.47, +1.13] | +0.20 [-0.45, +0.84] | +1.25 [+0.36, +2.13] |
| ev minus evu | | +0.24 [-0.16, +0.63] | |

Per seed, ev minus its start: +0.96 +1.91 +0.64 +0.53 +0.07 -0.06 -1.03; minus
pa40: +1.03 +0.53 +2.13 +1.68 +2.21 +1.40 +1.41. Three readings. (1) Two
rounds of outcome labels put the student, on its own, level with the
64-world teacher (+0.07, interval -1.0 to +1.2) and +1.48 above where
imitation left it, every seed positive -- the first network-only player of
ours at the teacher's strength, a hundredth of a second a game against 14 s.
(2) Round 2's own step is smaller and not resolved: +0.43, five seeds of seven
positive, after round 1's +1.05. On its training's held-out boards the gain of
the top card over the legal mean rose only +0.09 (+4.48 -> +4.57 for ev, and
alike for evu), against round 1's +0.64: the second round's labels taught a
seventh of what the first round's did. (3) Fresh labels alone beat the union
(+0.24 [-0.16, +0.63], five seeds of seven): round 1's labels, made with the
older student playing on, add nothing once round 2's are there. The
yardstick stays flat (0.301-0.305; pa40 0.297); teacher regret where it was
(0.557-0.565); the value head's held-out loss is lower on round 2's labels
(0.377 against 0.49), as the narrower play-outs predicted.

Why the step shrank is open, and two causes are confounded. Round 1's gain
flattened by epoch 5, read then as the data's limit; it is equally what a
policy loss balanced against a fixed imitation anchor does -- an equilibrium
the anchor sets, whatever the data (teacher regret did not move in either
round). And round 2's play-outs are narrower (a card's spread down a sixth to
a quarter): sharper drawn opponents, less to learn from. The anchor question
is training-only: ev on round 2's corpus at anchor weight 0.25 and 0, from the
same starts, read on the same held-out gain; the arena only if the gain moves.
The gate: round 2 does not pass on its own step; one more unresolved round
ends the loop by the plan's rule, so the cause is looked at before round 3.

*The anchor test, prepared (2026-11-03; `tools/outcomes-train.sh --anchors`).*
Each weight in `--anchors` trains its own students (a weight other than 1
named `-a<w>` without the point: policy-oc2-ev-a025, policy-oc2-ev-a0); the
summary gains a training table -- per student set, the draws' held-out gain
of the top card over the legal mean at the start and at the kept epoch, the
teacher regret, the kept epochs -- and every later set is paired with the
first in the arena. `./tools/outcomes-train.sh --round=2 --arms=ev
--anchors="1 0.25 0" --no-arena` reuses round 2's ev (weight 1) and trains
ev at 0.25 and 0 from the same policy-oc1-ev draws on round 2's corpus. What
it decides: if the gain at the kept epoch climbs well past round 2's +0.09
as the weight falls (and the teacher regret rises with it), the anchor was
setting an equilibrium, and the arena follows (the same command without
--no-arena); if it stays near +0.09 at weight 0, the anchor was not the cap
and the labels themselves have run thin.

*The anchor test: the answer (2026-11-04, training only).* Round 2's ev from
the same policy-oc1-ev draws, three draws a weight, the draws' means:

| anchor weight | held-out gain, start -> kept | teacher regret (worlds of 64) | yardstick all / tricks 1-3 / 7-9 |
|---|---|---|---|
| 1 (round 2's ev) | +4.482 -> +4.575 (+0.09) | 0.560 -> 0.564 | 0.303 / 0.301 / 0.224 |
| 0.25 | +4.482 -> +4.721 (+0.24) | 0.560 -> 0.585 | 0.321 / 0.318 / 0.237 |
| 0 | +4.482 -> +4.871 (+0.39) | 0.560 -> 0.789 | 0.430 / 0.499 / 0.264 |

The anchor was setting an equilibrium: the gain on the held-out labels
grows as its weight falls, two and a half times at 0.25 and four times at
0. Even with no anchor at all it levels off (+0.39 by epoch 6-9), so the
labels' own reach is modest too. What the anchor holds back is mostly the
opening: without it the yardstick's tricks 1-3 go from 0.30 to 0.50 (what
an 8-world search scores over a whole game; a 16-world one 0.33) and the
teacher's card is followed far less, while tricks 7-9 move only 0.22 -> 0.26.
Neither measure is the judge: the yardstick's referee is double-dummy, and
the labels are this student's play against itself, which a net freed from
the teacher can learn to exploit without getting better against anyone
else. The arena decides: ev at 0.25 and 0 against belief-64-shipped, seeds
11-16 and 21, paired with ev at 1 (on disk), about 11 hours.

*The anchor test in games: the answer (2026-11-04).* student-64 against
belief-64-shipped, seeds 11-16 and 21, three draws a weight, paired with ev at
weight 1:

| anchor weight | against the teacher | minus its start | minus ev at 1 | per seed, minus ev at 1 |
|---|---|---|---|---|
| 1 | +0.07 [-1.04, +1.18] | +0.43 [-0.41, +1.28] | -- | |
| 0.25 | +0.11 [-0.62, +0.84] | +0.48 [-0.89, +1.85] | +0.05 [-0.73, +0.82] | +0.19 +0.11 -0.44 +1.54 +0.46 -0.42 -1.12 |
| 0 | -0.09 [-1.18, +1.00] | +0.27 [-0.57, +1.12] | -0.16 [-0.66, +0.34] | -0.89 -0.67 -0.03 +0.42 +0.37 +0.26 -0.56 |

Two and a half to four times the label gain bought nothing in games: 0.25
level with 1, 0 a little behind, neither resolved. So the anchor was capping
the gain on the labels, not the student's strength. What the freed net
learned is what its own self-play rewards -- the labels are the student
against itself, and the extra fits that opponent and the labels' noise, not
the teacher across the table -- and what it gave up (the opening, by the
yardstick) cost about as much as it gained. Weight 1 stays.

Where that leaves the loop. Round 2's own step is +0.43 (unresolved) at
every weight, after round 1's +1.05; the second round's labels hold less, and
taking them more literally does not help. The plan's rule ends the loop
after one more unresolved round, so round 3 should change what the labels
are made of rather than repeat round 2: the student playing against copies of
itself is the likely limit, as round 2's narrower play-outs already showed.
Phase P's rule is the lever -- opponents partly from earlier nets and fixed
outsiders, in the main line and in the play-outs, so a card is valued against
more than one way of defending and declaring.

*Opponents from earlier nets, built (2026-11-04; `--pool`, `--mix`).* Phase P's
rule in the all-cards exporter: `--pool` names other students; per board the
student takes one, two or three seats in the proportion `--mix` (default 40 :
40 : 20; seats at random, each other seat a pool net drawn uniformly, one
stream a board of its own). Pool seats play the main line by the same rule
(top card, a fifth drawn); only the student's decisions are labelled; and in
the play-outs every card is played by the net of the seat that plays it --
the decider's own side at the top card, the other side drawn -- so a card is
valued against the opponents actually at the table (TrueDealLabeller, one
batched pass a distinct net, bit for bit the single pass). Records are format
2: four bytes more, who sat after and before the decider (0 the student, i
the pool's i-th net) and the student's seat count; the reader reads both
formats and the trainer mixes them. Without a pool the exporter writes format
1's records byte for byte plus `0 0 3 0` (209 of 209 records compared against
the code before the change). Tests: with a pool every play-out position goes
to its seat's net, only the student's decisions are labelled, and the play-out
of the main line's card is the main line at the student's positions and in
its result; the role bytes; the seating follows the mix (6,000 boards within
two points) and is the same for the same board; seven mutants fail. The
reader checks the roles against the seat count; two mutants fail it. A pool
holding the student itself is refused.

Round 3, prepared: policy-oc2-ev in the student's seats, policy-b600-pa40
(imitation of best) and policy-oc1-ev (round 1) in the pool, seed 9003 --
labels for the student against the net it was a round ago and against the
imitation net it started from, beside its own copies a fifth of the boards.

*Route 1, round 3, and the end of the loop (2026-11-05).* The corpus,
`allcards-data/r3-k8-s9003`: policy-oc2-ev in the student's seats,
policy-b600-pa40 and policy-oc1-ev in the pool, 170,000 boards of seed 9003 --
148,508 games, 2,038,750 labelled decisions in 5.9 hours (7.0 games a second:
the pool seats only play), every check passed; the student held one, two and
three seats in 22 / 45 / 33% of the records, as the mix gives. Against its own
labels it plays as in round 2 (top is best 39% in trick 1, 71% in trick 7,
95% in trick 9; regret 9.1, 3.5, 0.5).

The students (from the three policy-oc2-ev draws, anchor 1): held-out gain
+4.51 -> +4.55 (+0.04, after round 2's +0.09 and round 1's +0.64); teacher
regret unchanged; yardstick 0.301 (pa40 0.297). student-64 against
belief-64-shipped, seeds 11-16 and 21:

| student | against the teacher | minus its start | minus pa40 |
|---|---|---|---|
| policy-oc2-ev (the start) | +0.07 [-1.04, +1.18] | -- | +1.48 [+0.93, +2.03] |
| policy-oc3-ev | +0.09 [-1.02, +1.20] | **+0.03 [-0.34, +0.39]** | +1.51 [+0.72, +2.29] |

Round 3's own step is nothing (per seed -0.65 -0.13 -0.06 +0.38 +0.31 -0.17
+0.49). With round 2's unresolved +0.43 that is the second failed gate, and
the loop ends by the plan's rule. Opponents from earlier nets did not revive
it: the labels against them taught less still.

What route 1 leaves. Outcome labels on the true deal, every card compared on
the same deal, moved the network-only student +1.5 game points over where
imitation stopped (resolved; +1.05 in the first round, +0.43 in the second,
nothing in the third), from 1.41 behind the 64-world teacher to level with it
-- a hundredth of a second a game. The yardstick never moved: the gain is of
the kind only games see. What stops it is the labels' own reach: the play-outs
are finished by the student itself, so a label says how good a card is if
this student plays on, and once the student plays its own labels' best card
about as well as a 3 x 512 net can, the next round has little to say -- the
held-out gain fell 0.64, 0.09, 0.04, and neither loosening the anchor nor
changing the opponents moved games. A stronger label needs a stronger
continuation than the student (the search in the play-outs' later tricks, say),
or a different signal.

*Where the gain sits (2026-11-05; `arena/python/where_gain.py`).* On the
held-out boards of the three label corpora (about 200,000 decisions each,
never trained on), every student picks its top card and the corpus's labels
say what it is worth: U, game value a play-out from the decider's side with
the tiebreaks, as trained. policy-oc2-ev against policy-b600-pa40, the three
draw pairs averaged record by record, the interval clustered by board:

| group (share of decisions) | cards differ | gain a decision, r1 / r2 / r3 labels | share of the total (r1) |
|---|---|---|---|
| all | 14% | +0.57 / +0.58 / +0.53 (+-0.05) | 100% |
| declarer, tricks 1-3 (10%) | 31-33% | **+1.25 / +1.08 / +0.91** | 23% |
| declarer, tricks 4-6 (11%) | 21-24% | +0.61 / +0.37 / +0.52 | 12% |
| declarer, tricks 7-9 (12%) | 9% | **+0.12 / +0.25 / +0.27** | 2% |
| defender, tricks 1-3 (23%) | 10-11% | +0.51 / +0.58 / +0.54 | 21% |
| defender, tricks 4-6 (22%) | 12% | +0.57 / +0.55 / +0.54 | 22% |
| defender, tricks 7-9 (22%) | 10% | +0.50 / +0.65 / +0.48 | 19% |
| grand (8%) | 19-20% | +1.17 / +1.24 / +1.17 | 16% |

The three label sets agree, though r1's labels are pa40's own play and r3's
the new student's against a pool -- so the change is a better card, not one
fitted to its own continuation. Two thirds of it is defence, spread evenly
over the whole game (+0.5 a decision in every third, leading and following
alike); a fifth to a quarter is the declarer's first three tricks, where a third of the
cards changed (the lead most: +0.82 a decision leading, +0.46 following); the
declarer's endgame, the target route 1 was planned for, gained almost
nothing (+0.1 to +0.3, 9% of cards changed). Grand gains twice the rate of a
suit game.

By round (pairs of consecutive students, r1 / r3 labels): round 1, pa40 ->
oc1-ev, +0.62 / +0.47 -- the whole robust change; round 2, oc1-ev -> oc2-ev,
**-0.06 / +0.06**, worse by the old student's play and better by the new
one's: round 2 learned cards that pay when the newer student plays on --
co-adaptation to its own continuation, the self-reference the plateau note
suspected; round 3, oc2-ev -> oc3-ev, +0.03 / +0.04. So route 1's lasting
gain is round 1's, and a label finished by the student it trains stops being
informative after one turn. A next signal needs a continuation the student
does not shape: a fixed one, and stronger than the student where the student
is weakest (the declarer's endgame) -- the search in the play-outs' later
tricks is the candidate.

*A search finishes the play-outs, built (2026-11-05; `--search-from`,
`--search-worlds`).* Option 2 after the loop: the labels' continuation stops
being the student it trains. With `--search-from=T` every play-out position
from trick T on, every seat alike, is played by a small search instead of a
net (TrueDealLabeller.searchCard): W worlds sampled from that seat's own
evidence -- its hand, the cards seen, the voids shown, the declarer its
discard, never the table's hands -- each legal card asked of the double-dummy
solver whether the declarer reaches 61 less what it has banked with that
world's skat, the most votes for the seat's side played, ties to the seat's
net's preference: the shipped search's vote at risk 0, without its belief,
cushion or ladder. Each draw samples its own worlds, so the play-outs keep
their variety. The choice of trick 7 and 8 worlds: on the yardstick (October)
even a 4-world search loses less than the student in tricks 7, 8 and 9 (0.42 /
0.22 / 0.08 against 0.61 / 0.55 / 0.32) and more in trick 6 (0.64 against
0.59). The student still plays the play-outs' tricks before T (round-on-round
co-adaptation is left there; it was +-0.06).

Tests: the search takes over at exactly its trick and the labels count its
calls; its card is an independent tally of the solver's verdicts over the
same sampled worlds, the net's preference breaking ties (from trick 4 on in
ten recorded games, the votes overruling the net in ten positions or more,
defenders among them); a seat knows the skat only as its own discard; a
declarer whose game is won whatever it plays gets the net's card; a shard with
the search is the same bytes at one thread and three and differs from one
without. Five mutants fail (the world's skat left out of the target, the
defender's side of the vote, the tiebreak, the trick threshold, a defender
told the discard); a sixth -- dropping the declarer's vote in a world already
won -- is equivalent, since such a world adds the same to every card, and the
branch now just skips it. Without the search the records are byte for byte
as before. A container smoke (12 boards, k 8, Java solver): a search cost
0.01-0.11 ms (four or fewer cards a hand by trick 7), about 400 searches a
trick-1 decision; within a card the play-outs' spread from trick 7 on is
nearly nil -- the search's finish is close to deterministic, the draws' variety
is in the tricks before.

*The search's labels against the student's, on the same decisions
(2026-11-05; `arena/python/label_diff.py`, `--reseed-from`).* Quick exports
(2,000 boards, k 16, seed 9004) of policy-oc3-ev, once with the search from
trick 7 and once without. On bigbox the search costs about 6% of a trick-1
decision (719 searches at 0.09 ms of 1,155 ms): 1.94 games/s against 2.08.
Read alone, its summary showed nothing new -- the student's top card the
labels' best 73.4% / 86.4% in tricks 7 / 8 against round 3's 66.9% / 82.5% --
but that compared another student, seed and noise level (the search's
play-outs vary less: sd within a card 12.0 against 13.1 in trick 7), so it is
not read. The main line draws from its own stream, so the two corpora hold the
same decisions record for record (checked), and a decision's play-outs share
their luck until trick 7. label_diff on the 31,649 undrawn decisions: the best
card moves in 22% of decisions in tricks 1-6 (agree 77-78%), and by the
search's labels the student-finished best gives up 1.3-1.7 a decision there,
1.10 [0.99, 1.21] over all tricks; the label shape changes by 0.18-0.34 of its
variance. The student's own top card does not look worse by the search: its
regret by the search minus by itself -0.22 [-0.40, -0.04] (declarer +0.01,
defender -0.34). So the search reshuffles the other cards more than it
convicts the student's. How much of the reshuffle is the luck parting after
trick 7 is open: a rough count (sd about 13 a play-out from trick 7, 16
play-outs) puts a noise-only "misleads" near 1. The control that answers it,
built: `--reseed-from=T` -- the student still finishes, every play-out on
fresh luck from trick T (drawn from the draw's own stream, so the candidates
still share it), the labels sharing everything with the plain ones before T
and nothing after, as the search's do. Tests: the play-outs before trick 6
the same position for position, trick 7 opening otherwise in more than a
fifth of them; a card's label the same alone and among others; the exporter's
records the same bytes but for the label slots; not together with the search;
the settings name it only when on. Seven mutants fail.

*The control: the search's change is real (2026-11-05).* The same quick
export with `--reseed-from=7` (the student finishing on fresh luck from trick
7) against the plain one gives what noise alone does, column by column.
Tricks 1-6, noise against the search: agree 83-87% against 77-78%; "A
misleads" 0.33-0.60 against 1.26-1.70; signal 0.04-0.05 against 0.18-0.34.
By role, the declarer's labels are the noisy ones (misleads 0.6-1.2 from noise
alone in tricks 1-6 against the search's 1.25-1.90), the defender's clean
(0.20-0.40 against 1.0-1.7). Over all tricks: misleads 0.38 [0.35, 0.41]
against 1.10 [0.99, 1.21], signal 0.04 against 0.23. The control's B-A is
-0.00 [-0.05, +0.05], as it must be; the search's -0.22 [-0.40, -0.04] stays:
by the search's labels the student's own top card is no worse, slightly
better, than by its own. So the search changes which of the other cards
ranks first, and the shape of the labels four to six times beyond noise, most
of all for the defender, without convicting the student's choices. Whether
that makes a stronger student is the arena's to say -- with the caveat that
the labels value a card as followed by the search's endgame, while in a game
the student plays its own. Round 4 goes ahead: the full export (100,000
boards, k 8, seed 9004, no pool) and ev from the three policy-oc3-ev draws.

*Route 1, round 4 -- the search's labels: no gain (2026-11-06).* The export:
100,000 boards, 87,256 games, 1,994,734 decisions in 6.0 h (4.06 games/s;
the search 359 calls at 0.08 ms in a trick-1 decision of 548 ms); all checks
passed. Training ev from the three policy-oc3-ev draws moved little: held-out
gain on the new labels +4.402 -> +4.441 (+0.039), best or tied 59.7% -> 59.8%,
teacher regret 0.563 -> 0.565; the yardstick unchanged within its noise
(0.302-0.305 against 0.300-0.303). The arena, student-64 against
belief-64-shipped, 7 seeds of 200 boards, draws averaged: ev -0.18 [-1.33,
+0.97]; **minus its start -0.28 [-0.76, +0.21]** (five seeds of seven below
zero); minus pa40 +1.23 [+0.56, +1.91] (round 3's student: +1.51). So the
search's labels, though they differ from the student's beyond noise, do not
make a stronger student: the gate fails a third time. Consistent with the
control: the search reorders the cards the student does not play (its top
card no worse by the search, -0.22), and the expected objective moves a card
in proportion to its probability, so a reshuffle among unlikely cards teaches
next to nothing; what little moved may follow the caveat (a card valued as
followed by the search's endgame, played before the student's own). Option 2
is closed; policy-oc3-ev stays route 1's best student, +1.51 over pa40 and
level with belief-64-shipped (+0.09 [-1.02, +1.20]).

*Route 1, the size test under outcomes (2026-11-06, planned;
`tools/outcomes-size.sh`).* Under imitation width did not pay in games (3 x
1024 at 600,000 deals +0.15 [-0.53, +0.83] over pa40, the size test of
2026-10-31); whether a wider net learns more from the outcome labels -- a
label on every legal card, about 6 million decisions in rounds 1-3 -- is a
separate question. Two nets on one recipe (expected, anchor 1, 10 epochs, the
epoch kept by held-out gain) on the union of the student-finished corpora
r1-r3 (round 4's search labels left out): w512 from policy-b600-pa40 and
w1024 (3 x 1024, 2.5 million weights) from policy-b600-h1024-pa80, three
draws each (policy-ocu3-w512, policy-ocu3-w1024). The held-out decisions are
the corpora's board split, the same for both, so the gains compare directly.
Stage 1, `--no-arena`: training and the yardstick, the gate being w1024's
held-out gain clearly above w512's. Stage 2: student-64 against
belief-64-shipped, seven seeds; the starts' and oc3-ev's reports reused.
Read: each minus its start, w1024 minus w512, and the gains' difference
((w1024 - its start) - (w512 - its start)), which takes the starts' gap out.
Dry-run with a fake python and gradlew (quick, stage 1, stage 2 reusing both;
the pooled lines match the fake's known differences); three mutants (the
gains' sign, a net's start, the pairing) caught.
The first run ran out of memory before training: the full Records of the
three corpora (6,053,930 decisions; raw bytes, the features again, wins,
points and value by card as int64) peak near twelve gigabytes, and the
anchor's 7,968,234 records did not fit beside them. train_outcomes.py now
loads the corpora shard by shard into LeanRecords -- only what training
reads, U and the win share computed per shard in the same elementwise
arithmetic -- about 3.7 GB. Its self-test checks the lean load against the
full Records on fake corpora (one corpus, two of both formats, a limit inside
a shard and across corpora): the same bytes in every field; six mutants fail.

*The size test under outcomes: stage 1, the gate fails (2026-11-06).* Both
nets trained in about 10 minutes a draw on the 6,053,930 decisions (605,650
held out, 32,382 boards), kept at epoch 9-10. Held-out gain of the top card
over the legal mean, the draws' mean: w512 +3.953 -> +4.568 (+0.616), w1024
+4.014 -> +4.652 (+0.638); the draws agree within about 0.01. The wider net
ends 0.084 higher, but 0.061 of that is its start; what the outcomes add to
it beyond the narrower is +0.022. The yardstick keeps imitation's order: w1024
0.276-0.279 against w512's 0.303-0.304 (each a few thousandths above its
start, as outcome training always cost). For scale, round 1's 0.64 of label
gain was +1.05 in games, so +0.02 is a few hundredths of a game point, far
inside what seven seeds resolve (+-0.4 paired); the arena (stage 2, ten
hours) is not run. Width is closed for route 1 as for imitation: the wider net
learns from the outcomes what the narrower does, and keeps the head start
imitation gave it (+0.15 [-0.53, +0.83] in games). On one pass over the union,
w512 gains as much (+0.62) as round 1 did on r1 alone (+0.64).

*After route 1: the auction and the discard, read again (2026-11-07,
diagnosis only).* Every route-1 game was played at fixed contracts from
greedy's auction, so none of it measured bidding, the contract or (beyond the
heuristic both sides share) the discard; in student-64 all three are still the
search player's. What the code does (SearchAiProvider):

- *Bidding*: the ten dealt cards are priced once at the deal -- the two
  likeliest trump games and Null, each a make chance over `biddingWorlds()`
  = clamp(worlds / 3, 2, 6) = 6 sampled worlds for every shipped level,
  belief-64 included -- and re-priced on what the table says (adaptive).
  The ceiling is the intended contract's guaranteed value on the ten; the
  bid is declared-worth against the priced Ramsch. Phase A (2026-09-09)
  closed how often to declare (more costs points, monotonically) and Null
  (arithmetic); what it left open is which contract and how high, with
  overbid 0.00% in every report.
- *The contract*: chosen before the skat and never asked again. The discard
  buries for `intended` (the ten-card choice), and `announceContract` takes
  `intended` whenever it covers the bid; the twelve cards are never priced.
  Unmeasured. A skat with a jack, or one that fills a suit, does not change
  the game announced.
- *The discard*: the `Discards.buried` heuristic for that contract. The
  searched discard (Phase D, 2026-09-21) was +0.19 / +0.14 [-0.3, +0.6] at
  16 / 32 worlds, closed at the heuristic; the omniscient ceiling was 6.3
  points of makeability on declared boards, and the par split put the discard
  at 1.14 [0.3, 1.9] of the 4.99.
- *Hand games*: the engine's variant excludes them (GameEngine always picks
  up), so they are outside this game.

The instrument proposed: route 1's labels at the declarer's choice. After
the auction (greedy's, as the arena's contracts), every contract that covers
the bid (the four suits and Grand; Null left out, the student does not play
it) times the ten discards from that contract's five least-wanted cards,
each played out k = 8 times on the true deal by the student, common random
numbers across the options; the shipped player's own choice beside them
(its ten-card price, its discard, its announcement). Read: how often the
labels' best contract differs from the announced one, and what the shipped
choice gives up, split into the contract and the discard within it --
measured by choosing on half the play-outs and scoring on the other half, so
the noise does not inflate it. About 50 options x 8 play-outs a declared
game, a few seconds a game a thread.

*The declarer-choice labels, built (2026-11-07; DeclarerChoiceMain,
`:arena:declarerChoice`, `arena/python/declarer_choice.py`,
`tools/declarer-choice.sh`).* As proposed, with two references: *shipped*
(belief-64-shipped in the declarer's seat, the engine's own calls in order --
priced at the deal, the auction heard, the skat up, its discard and
announcement; the price is the deal's, since a replayed auction asks it for
no bids to re-price on) and *fixed* (the auction's contract with the
heuristic's discard, what route 1 played). The student plays each option out
as the all-cards labels do: the declarer by its top card, the defenders
drawn, the auction heard, the declarer knowing its discard; draw d's streams
depend on the board and d only. Three ways, cross-fitted on halves of the k
play-outs: contract and discard, the discard alone, the contract alone (each
with the heuristic's pair). Tests (DeclarerChoiceTest): the options are the
contracts covering the bid with the ten pairs of their five least-wanted
cards, one heuristic pair each, the references among them, no Null, none
twice; a play-out plays the option's contract with the declarer holding the
twelve less its discard; a discard not in the hand is refused; the defenders
are drawn; a board is the same bytes run twice, an option the same alone.
Six mutants fail. The report's self-test (known gains by way, the
references found, U's weights, pure noise cross-fitted to zero while the
in-sample maximum is inflated): six mutants fail. A container smoke (pa40,
search-4 as shipped, 12 boards): 47 options a board, about 45 play-outs a
second a thread.

*The declarer-choice labels: the first run (2026-11-07; seed 9101, 2,000
boards, k 8, policy-oc3-ev playing out).* 1,747 declared boards (253 passed
in), 45.2 options a board, 631,304 play-outs in 9.6 minutes on bigbox. The
honest comparison -- two choices made without the deal, scored on the same
labels: **shipped minus fixed -0.53 [-1.96, +0.90] a declared game**; the
shipped player announces another contract than greedy's auction on 34.1% of
boards and gains nothing by it. What choosing by the labels is worth,
cross-fitted on halves (an upper bound: the labels see the deal), against
shipped: contract and discard +19.2 [+17.3, +21.1] a declared game (+16.8 a
game), the contract alone with the heuristic's discard +13.4 [+11.8, +15.0],
the discard alone +6.2 [+5.0, +7.5] (Phase D's omniscient discard ceiling was
the same six points); against fixed nearly the same. The labels' best is
another contract on 61-68% of suit boards and 3.6% of Grand boards. The halves
remove the noise of the defenders' draws, not the hindsight of the layout --
which trump suit splits well is exactly what the labels know and a declarer
does not -- so these are ceilings, and what an honest declarer can take of
them is the open question. (A first attempt at this run reused the quick
run's 40 boards: the script named the label file by LINES, which bash resets
to the terminal height after every external command; fixed, 2c44623.)

*Two additions, built (2026-11-07).* A third reference, **searched**: an
honest re-chooser after the skat (DeclarerChoiceMain.searchedChoice) -- for
every contract covering the bid, DiscardSearch over 16 worlds dealt from the
twenty unseen cards picks the pair and gives the make chance against
double-dummy defence; the contract kept maximises value x p - 2 value (1 - p),
the bidder's own reckoning. It reads only the declarer's twelve. The report
now leads with the honest differences (shipped - fixed, searched - shipped,
searched - fixed). And what the declarer could see is written beside each
board -- its ten, the skat, its place, the auction by place -- so a learned
declarer can be trained on these labels and scored on boards it never saw.
The search costs about 160 full-game solves a contract (Phase D's ten seconds
a discard), so `--search-worlds=0` leaves it out for a large corpus. Lines
are written as boards finish (board order), so a long run that dies keeps
its .partial. Tests: the re-chooser covers the bid, is an option, gives the
same pick for the same twelve, re-chooses somewhere; on four jacks and seven
clubs it keeps Grand (guaranteed 120 against Clubs' 60), the only game above
a bid of 60, nothing above 120; the JSON carries the searched choice, the ten,
the skat, the place and every auction event. Three more mutants fail (the
worth's order, the bid filter, the chance). The report's self-test: the
honest differences, the searched reference found by its pair, older files
without it; three more mutants fail.

*The searched re-chooser: the answer (2026-11-07; seed 9101, 2,000 boards,
16 worlds, 41 minutes on bigbox, the search 1,925 of the 2,500 s).* Honest,
by the labels: **searched minus shipped +5.26 [+3.49, +7.04] a declared game,
+4.60 [+3.05, +6.15] a game**; searched minus fixed +4.73 [+2.87, +6.59];
shipped minus fixed -0.53. The re-chooser announces another contract than the
shipped player on 47.0% of the declared boards. Where it comes from (the
per-board differences, grouped by what changed):

| change | boards | searched - shipped | share of the total |
| --- | --- | --- | --- |
| the same contract (another discard at most) | 926 | +0.07 (se 0.79) | 1% |
| suit to another suit | 562 | +1.98 (se 1.94) | 12% |
| **a suit to Grand** | **224** | **+33.07 (se 1.98)** | **81%** |
| Grand to a suit | 35 | +17.3 (se 17.6) | 7% |

Grand goes from 250 declared to 439. The labels' win share is the same for
both choices (0.637), the game value is not (-7.23 against -1.95 a play-out):
the gain is games won at Grand's value instead of a suit's. The shipped player
prices only the two likeliest trump games on its ten at the deal
(HandEvaluator.plausibleTrumpGames(hand, 2)) and never looks again, so a
Grand the skat makes -- or one it never priced -- is not announced. (The
re-chooser's formula also prefers the cheapest game when its chance is below
two thirds, which is why it picks Diamonds 602 times; that part moves little,
+2 a board, unresolved.) Caveats: the play-outs are the student on both sides
with drawn defenders, not the shipped search, and the bid is greedy's; a
Grand that only the student's defence lets through would show here and not at
a table. The table is the next measurement.

*The re-chooser at a table, built (2026-11-08; ContractSearch,
`SearchAiProvider.withPostSkatContract`, `tools/rechoose.sh`).* The choice
moved into the engine as ContractSearch, shared by the labels
(DeclarerChoiceMain.searchedChoice now calls it, the same worlds from the same
streams) and by the player: with `withPostSkatContract(worlds)`, after a real
auction (never at a settled contract, never on a Null intention) the search
player re-chooses its game and discard on the twelve and announces it, when
it covers the winning bid on the twelve; off by default, and off ships. The
bid comes from `pickUpSkat`, which until now ignored its context. Registered
as `belief-64-shipped-rechoose-16` and `-8`. `tools/rechoose.sh` plays it
against belief-64-shipped in the auction with passed-in boards void, seeds
11-13 at 400 boards, and pools the per-board differences of every seed. Tests
(PostSkatContractTest): one jack and six clubs intend Clubs at the deal; the
skat's two jacks make Grand worth 96 against Clubs' 48, and the re-chooser
announces Grand where the player without it announces Clubs; a settled
contract and an exchange without a bid are left alone; the setting survives
every builder, the discard search included; ContractSearch on that twelve
keeps Grand, the only game above 48, none above 96. Six mutants fail. The
engine suite passes but for ten belief tests whose binary resources the
container copy lacks; the declarer-choice tests pass with the shared choice.

*The re-chooser at a table: the answer (2026-11-08; `tools/rechoose.sh`,
seeds 11-13, 400 boards each, auction, passed-in void, 5.9 hours).*
belief-64-shipped-rechoose-16 against belief-64-shipped:

| seed | rechoose - shipped | wins as declarer | Grand declared (won) |
| --- | --- | --- | --- |
| 11 | +2.81 [+1.58, +4.03] | 84.1% / 81.4% | 98 (96%) / 34 (88%) |
| 12 | +3.26 [+2.06, +4.46] | 84.6% / 77.8% | 104 (96%) / 41 (93%) |
| 13 | +3.10 [+1.81, +4.39] | 83.8% / 78.6% | 123 (96%) / 51 (98%) |

**Pooled over the 1,200 boards: +3.06 [+2.34, +3.77] game points a game**,
resolved on every seed -- the largest single change this plan has measured
(the ladder was +1.30, route 1's whole card-play training +1.51). Both declare
on the same boards (27.8-29.9%: the auction is unchanged), the re-chooser
wins more of them (+2.7 to +6.8 points) and three times as many are Grand,
made 96% of the time. The labels predicted about +1.6 a game (+5.3 on the
roughly 30% of games declared); the table gives about twice that, so the
student's play-outs undersold it rather than flattered it. The cheapest-game
preference below two thirds shows: the re-chooser's Diamonds are won 62-70%
against the shipped player's 70-78%, a part a better rule might keep.
What it is not yet: measured against anyone but our own shipped player, and
affordable -- about 160 full-game solves a game at 16 worlds, seconds on the
workstation, far beyond a phone. Both are the next questions: the same pairing
against XSkat, go-skat and JSkat, and a cheap choice that keeps the gain (the
8-world re-chooser, Grand priced on the twelve, or a learned declarer trained
on these labels and choices).

*Against others (2026-11-08, `tools/rechoose.sh --vs`, `--defence`).* Two
more pairings, measurement only. `--vs=<engine>` plays the re-chooser and the
shipped player each against XSkat (or go-skat, JSkat) on the same boards and
pools rechoose - engine, shipped - engine and their per-board difference.
`--defence=skatzero` seats both sides as `roles:<ours>/skatzero`: ours bids,
takes the skat, names and declares the game, SkatZero defends, so every
declarer meets the strongest defence we have. SkatZero cannot bid, so this is
the only way it can sit at an auction; it is a yardstick here, never a
teacher. It also tests the labels' weak point -- the student's defence -- by
replacing it with SkatZero's.

*Against others: the answer (2026-11-08/09; seeds 11-13, 400 boards each,
auction, passed-in void).*

| pairing (pooled over 1,200 boards) | game points a game |
| --- | --- |
| rechoose - XSkat | **+3.31 [+2.20, +4.42]**, resolved on every seed (+3.93, +3.82, +2.17) |
| shipped - XSkat | +0.84 [-0.32, +2.00], none resolved (+1.47, +0.81, +0.25) |
| the re-choice against XSkat, board by board | **+2.47 [+1.87, +3.06]** |
| rechoose - shipped, SkatZero defending both | **+2.56 [+1.84, +3.27]**, resolved on every seed (+2.37, +3.33, +1.97) |

The first time a player of ours is resolved ahead of XSkat in the full game
(the shipped player has been level with it since September: -0.05, +0.72,
+0.84 now). Against XSkat we declare 23-25% of boards to its 35% and win
89-94% of them; the re-chooser's Grands are won 94-99%. With SkatZero
defending, the gain is 0.5 smaller than against our own defence (+3.06) and
just as resolved, and the re-chooser's Grands are still made 93-97% -- so the
labels' doubt, the student's defence letting Grands through, is answered. The
weak spot recurs in all three runs: the re-chooser's Diamonds are won 57-81%
against the shipped player's 72-90%, where the formula takes the cheapest game
because no game looks likely to make. What is left is cost: a learned
declarer, trained on the labels, to make the same choice in milliseconds.

*A learned declarer, built (2026-11-09; `arena/python/train_declarer.py`).*
An MLP (3 x 256) scores one option from what the declarer can see -- the ten
kept and the two buried, laid out relative to the option's game (the jacks,
the trump suit, the side suits in a fixed order), the game, whether the pair
is the heuristic's, its place, the bid and the auction's highest bids by
place -- trained to predict the option's U centred within its board; the
declarer takes the option scored highest. The test is honest: boards of
another seed, the net's choice and the references scored on the same labels.
Route 1's softmax objective was tried and fails here as in round 4: it learns
"Grand loses" from the many boards where it does and then gives Grand no
weight to learn from the few where it wins. Self-test: the layout, the
features (no label reaches them), a made-up corpus where Grand pays exactly
with three kept jacks (learned minus shipped +3.2 against the truth's +3.5,
never a losing Grand), the choice and the heuristic-only choice; four mutants
fail (the heuristic flag is not needed by the made-up corpus, so its mutant
lives). A smoke on the seed-9101 file alone (1,300 boards to train, 447
others to test, 12 seconds on the container's CPU): each game with its
heuristic pair, **learned minus shipped +6.54 [+3.58, +9.49] a declared game,
learned minus searched +0.85 [-2.48, +4.18]**; choosing the discard too it
does worse (+1.91 over shipped, -3.78 under searched) -- ten discards a game
are too many to learn from 1,300 boards.

*The learned declarer: the first real training (2026-11-09; declarer-net-v1,
17,414 boards of seed 9102 to train, the 1,747 of seed 9101 to test, a few
minutes on the GPU, kept at epoch 25 of 30).* Learned minus shipped **+12.28
[+10.62, +13.94] a declared game**, learned minus searched **+7.02 [+5.57,
+8.48]**; each game with its heuristic pair +9.63 and +4.37. The discard now
pays (+2.65 a declared game over the heuristic's), and the net names Grand on
562 of the 1,747 boards (searched about 440, shipped 250). Read with care:
train and test labels both come from the student's play-outs, so the net may
have learned what that student's play rewards -- contracts its drawn defence
lets through -- which the search, scoring against double-dummy defence, does
not chase. New boards rule out overfitting to boards, not to the play-outs;
the table decides.

*To the table (2026-11-09; LearnedDeclarer, `export_declarer.py`,
`SearchAiProvider.withLearnedDeclarer`).* The net in Java (engine): the
options are ContractSearch's without the search -- every trump game covering
the bid, times the ten pairs of its five least-wanted cards -- each scored
from the same features as in Python, the best buried and announced; about
0.1 ms an option on the container's JVM, a few milliseconds a declared game.
`export_declarer.py --model declarer-net-v1` writes declarer.bin and a parity
file: for thirty test boards every option's inputs and Python's score, which
Java rebuilds and must match (checked on load in the arena; a mismatch
refuses the net). Registered as `belief-64-shipped-declarer-net`
(declarer-net-v1/, or -Dskat.declarer.dir); `tools/rechoose.sh` takes
`--player` and `--against`. One subtlety found by the parity check: the
heuristic's ranking breaks ties by the order of the twelve, which the label
files do not keep, so the parity file carries the flag rather than
recomputing it; the engine hands the declarer its dealt ten and then the
skat, as when the labels were made. Tests (LearnedDeclarerTest, PostSkatContractTest):
a tiny random net's export scores all 570 options of twelve real boards as
Python did, a changed score or flag is refused, a malformed file too; the
layout; the choice among covering games and their pairs, none above every
value; the places and the heard passes reach the net (an AND of two features
through a ReLU), through the search player too; it announces what it picks,
leaves a settled contract and an untold bid alone, survives the builders. The
real net's parity holds on 930 options. Nine mutants fail.

*The learned declarer at the table: the answer (2026-11-09/10; seeds 11-13,
400 boards each, auction, passed-in void).*

| pairing (pooled over 1,200 boards) | game points a game |
| --- | --- |
| declarer-net - shipped | **+3.14 [+2.23, +4.04]**, resolved on every seed (+2.75, +3.68, +2.98) |
| declarer-net - rechoose-16 | **+0.08 [-0.70, +0.86]**, none resolved (-0.06, +0.42, -0.11) |
| declarer-net - XSkat | **+3.86 [+2.66, +5.05]**, resolved on every seed (+3.96, +5.43, +2.18) |
| the choice against XSkat, board by board | +3.01 [+2.31, +3.72] (the search's: +2.47) |

The net is level with the 16-world search it was never shown -- trained only
on outcome labels -- at about a thousandth of its cost (milliseconds against
seconds a declared game). The labels' +7 over the search did not reach the
table, as feared: what the student's play-outs reward beyond the search is
not worth points against real defence; what both find is. The net declares
more Grands than the search (154-173 a seed against 98-123) and wins fewer
of them (86-91% against 94-96%), for the same points. A phone-sized player
with the whole gain: the shipped player plus declarer-net-v1 is +3.1 over the
shipped player and +3.9 over XSkat.

*The bidding: the instrument (2026-11-10).* With the declaring settled (the
shipped player plus declarer-net-v1), the auction is the next place points
can leak. `BidLabelMain` (`:arena:bidLabels`, `tools/bid-labels.sh`) labels,
for every board and every seat, what winning the auction is worth at every
bid: the seat picks up the board's skat, the learned declarer chooses a game
covering the bid and its discard (on the twelve, given that seat's bid and
the other two passing), and each distinct choice is played out k times on the
true deal by the student -- the declarer-choice labels' method, one level up.
Beside that, what the shipped bidder does with the same ten: its ceiling (the
LADDER scanned with hold requests until it refuses) and whether it opens 18
when nobody has bid, each after the passes its place would have heard.
`arena/python/bid_report.py` then plays the engine's auction out of those
numbers (middlehand to forehand, the winner hears rearhand, a tie to the one
who hears, forehand may open 18, else passed in at 0 as in void mode), every
board from all three seats by both sides, and pairs a policy against the
shipped bidder: an oracle (the highest bid whose label is positive -- a bound,
it sees the deal), the shipped ceilings one, two and four rungs higher, and
the openings: the shipped bidder says 18 into an empty auction on seats whose
ceiling is 0 (it weighs a Ramsch that a bid takes away), so by-ceiling takes
those openings out, hold-18 holds where it opens, always-open opens on every
seat and always-18 also holds 18 everywhere. In the 48-board quick run taking
the openings out cost -4.4 [-7.3, -1.4]: they are worth points. It cannot see the shipped
bidder's re-pricing on what the table says, and the labels are the student's
play-outs: the table decides. Shipped against itself gives exactly 0; four
Java tests (levels cover, distinct picks, the ceiling holds and the next rung
is refused, determinism) and the report's self-test, mutation-checked.

*The bidding: the first answer (2026-11-10/11; seed 9201, 2,000 boards, k 8).*

| policy minus shipped (game points a seat-game) | |
| --- | --- |
| oracle (sees the deal: a bound) | +9.86 [+9.37, +10.35] |
| shipped ceiling +1 / +2 / +4 rungs | +0.05 / +0.04 / -0.12, none resolved |
| by-ceiling (drop the openings it would not hold) | **-3.13 [-3.54, -2.71]** |
| hold-18 (hold 18 where it opens) | +0.33 [+0.18, +0.49] |
| always-open / always-18 | -0.05 / -0.23, not resolved |

The shipped bidder bids on 18% of seats; declaring at 18 is positive on 53%.
Bidding higher is worth nothing (few boards have two bidders); bidding more
often is where the points are, and no blanket rule finds them: the seats it
passes outright (62%) are positive at 18 on 37% but -20.7 on average. The
openings it would not hold (20% of seats, its Ramsch term) are good, +18.3
on average and positive on 72% -- which is why dropping them costs 3. So the
question is discrimination, seat by seat, from what a seat can know.
`arena/python/train_bidder.py`: an MLP on the ten, the place and the shipped
bidder's verdict predicting the value at every rung, the bid the highest rung
predicted positive. Trained on the even boards of this file and tested on the
odd (three init seeds): +1.38 [+0.85, +1.91]; the union with the shipped
ceiling +1.91 [+1.39, +2.42], every seed resolved -- from 1,000 boards of
training. The 20,000-board corpus on seed 9202 trains the real one.

*The learned bidder: the corpus, and to the table (2026-11-11).* Trained on
seed 9202 (20,000 boards, 60,000 seats, three init seeds, 33 s), tested on
the 2,000 boards of seed 9201:

| policy minus shipped (game points a seat-game, labels) | | seeds |
| --- | --- | --- |
| learned t=0 | +2.90 [+2.49, +3.30] | +2.54, +3.22, +2.93 |
| learned-first t=0 | +2.90 [+2.50, +3.30] | +2.56, +3.15, +2.99 |
| union t=0 (the higher of it and the shipped ceiling) | **+3.06 [+2.66, +3.46]** | +2.82, +3.20, +3.16 |
| union t=5 | +2.68 [+2.32, +3.05] | |
| oracle (a bound) | +9.86 | |

Twenty times the data doubled the gain (+1.4 from 1,000 boards); the union
adds less now, the net having learned most of what the shipped price knew.
On the 1,000-board split the three seeds' mean prediction (what the table
plays) gave more than the seeds one by one (+2.15 against +1.91), so the
exporter prints the ensemble's row too.

To the table: `LearnedBidder` (engine; "SKBN" -- the ladder, every seed's
net, their mean predicted; `checkParity` against the exporter's file),
`arena/python/export_bidder.py`, `SearchAiProvider.withLearnedBidder` (union
t=0: says or holds a bid when the shipped price would or when it is within
the net's learned-first ceiling; the features priced once at the deal -- the
shipped ceiling and its opening with the passes its place would have heard,
less the re-price on them that BidLabelMain's opening saw; won past the
shipped ceiling the learned declarer chooses after the skat even on a Null
intention), and `belief-64-shipped-declarer-bidder-net` (-Dskat.bidder.dir or
bidder-net-v1/). Six tests, six mutants killed. Asked of the table: against
belief-64-shipped-declarer-net (the bidder alone differs), and both against
XSkat.

*The learned bidder at the table (2026-11-11/12; seeds 11-13, 400 boards
each, auction, passed-in void).*

| pairing (pooled over 1,200 boards) | game points a game |
| --- | --- |
| declarer-bidder-net - declarer-net (the bidder alone differs) | **+1.48 [+0.50, +2.47]**, no seed resolved alone (+1.34, +1.62, +1.49) |
| declarer-bidder-net - XSkat | **+7.05 [+5.82, +8.27]**, resolved on every seed (+7.10, +8.52, +5.53) |
| declarer-net - XSkat | +3.86 [+2.66, +5.05] |
| the bidder against XSkat, board by board | **+3.19 [+2.12, +4.27]** |

Head to head the bidder keeps about half of the labels' +3.06 (the labels'
shipped side is this very player: the shipped bidder, the learned declarer);
against XSkat all of it. The net declares on 45-48% of seats against 23-25%
(16% head to head, where it is outbid), wins 73-86% of them against 87-94%,
overbids never; XSkat, outbid, declares on 18% of seats instead of 35% and
wins more of those it keeps. The labels' defenders are the student, and the
student defends worse than our search does -- head to head the bolder
bidder meets that search and loses more of its new games (73-75% won) than
against XSkat (82-86%). In arena points (only the declarer scores): a bolder
bidder is worth more there than under a list that pays the defenders for a
lost game.

*The scoring, and the labels checked at the table (2026-11-13).* Tournament
points from the same reports (the paired point estimate: both sides play the
same boards; no interval): head to head the bidder is +2.37, +1.48, +1.02 by
seed, +1.62 a game against +1.48 in game points; against XSkat it adds +5.82
(game points +3.19). Each extra game won earns the 50 bonus, which outweighs
the 40 a defender collects on the ones lost even at 73% won. The scoring does
not change the picture; no tournament-points bidder. The arena's CSV now keeps
the tournament difference as a third column (reported, never decided on), and
tools/rechoose.sh pools it beside the game points when every CSV has it.

Head to head the bidder kept half of the labels' +3.06. Before relabelling
(a straight swap to search defenders would cost days), how far the student's
play-outs are from the table's, where it matters: `arena/python/bid_check.py
select` takes the seats where the learned ceiling is above the shipped one,
at the first bid where the two part, with the learned declarer's game and
discard there, plus 300 control seats both bidders declare; `BidCheckMain`
(`:arena:bidCheck`) plays each k times with belief-64-shipped in all three
seats at that contract (`SearchAiProvider.withForcedDiscard`, measurement
only), the auction the labels assumed; `bid_check.py report` pairs table
against student seat by seat, by predicted value, and shows what a higher
threshold would drop. `tools/bid-check.sh` runs the three. Declarer and
defence differ together: it measures the labels' whole bias.

*The bid check (2026-11-13; seed 9201, the 2,000 boards' 6,000 seats; k 2
with belief-64-shipped in all three seats, 137 min).*

| seats | n | student | table | table - student | won (student / table) |
| --- | --- | --- | --- | --- | --- |
| the learned bidder's extra games, at the bid where it parts from the shipped | 2,580 (43%) | +22.8 | +9.4 | **-13.40 [-14.78, -12.03]** | 79% / 67% |
| control: games both declare, at 18 | 300 | +50.9 | +40.0 | **-10.89 [-14.66, -7.13]** | 89% / 83% |

| predicted at the bid | seats | predicted | student | table | won |
| --- | --- | --- | --- | --- | --- |
| below 10 | 729 | +5.0 | +6.8 | -6.7 | 55% |
| 10 to 25 | 874 | +16.8 | +15.5 | +2.5 | 63% |
| 25 to 50 | 746 | +35.0 | +34.2 | +21.2 | 77% |
| 50 and over | 231 | +67.4 | +63.9 | +47.6 | 87% |

The net is calibrated to its labels (student = predicted in every band); the
labels are not to the table: the student's play-outs sit 11-13 game points a
game above the table on every game declared, the control's as much as the
bidder's own -- a shift, a little steeper at the top, not a defect of the
marginal games. On average the extra games still pay at the table (+9.4,
+26.2 tournament points), which is the head-to-head +1.5; those predicted at
10 or less do not: dropping them (t=10, 28% of the disputed seats) drops a
table mean of -6.7 [-10.0, -3.5]; the band from 10 to 15 is +2.3 on its own,
15 to 20 +2.0 -- 10 is where the bar goes. `LearnedBidder.withThreshold`
(learned-first above t on every rung, the ceiling too), and
`belief-64-shipped-declarer-bidder-net-t10`. Chosen on seed 9201's boards;
the table test is on 11-13.

*The threshold at the table: refuted (2026-11-14; seeds 11-13, 400 boards
each).* t10 - t0 head to head **-1.01 [-1.70, -0.31]** game points
(-1.01, -1.81, -0.20 by seed), -1.26 [-2.66, +0.13] tournament points. It
declares on 28% of seats against 36%, wins a little more of them, and loses
the points. My reading of the bid check was wrong: "the seats predicted at
10 or less lose -6.7 at the table" priced dropping them at 0, as if nobody
declared instead, and ignored the rungs above, where t10 also gives way in a
contested auction. The labels' own auction simulation (bid_report, which
plays both) had said so before the check: union t=5 below t=0 on the labels.
Re-run with the real ensemble on seed 9201 (the test seed):

| union t, minus t=0 | labels as they are | labels shifted -13 (the check's table ~ 0.99 x student - 12.9) |
| --- | --- | --- |
| t=-10 | +0.31 [+0.03, +0.58] | -0.79 |
| t=-5 | +0.25 [+0.06, +0.45] | -0.29 |
| t=5 | -0.47 [-0.65, -0.29] | +0.07 |
| t=10 | **-0.84 [-1.09, -0.59]** | +0.23 [-0.01, +0.46] |
| union t=0 minus shipped | +3.11 | +0.51 |

The table (t10 - t0 -1.01; t0 - shipped +1.48 head to head) sits nearer the
labels as they are for the threshold and between the two for the level: the
check's -13 is real for the games it replayed, but a uniform shift is not
how the bias reaches the auction. The bidder stays at t=0
(belief-64-shipped-declarer-bidder-net); -t10 stays registered as the
refuted variant. Lesson: a decision's counterfactual is the auction, not 0 --
ask the simulator that plays it, then the table.

**Phase P -- the play-outs at the source (2026-11-15).** Every learned
choice so far (the declarer's, the bidder's) was labelled by the student
playing all three seats -- the declarer by its top card, the defenders drawn
at temperature 1 -- and the bid check puts those play-outs 11-13 game points
a game above the table's. The declarer's +7 on the labels came to nothing
beyond the search at the table; the bidder kept half. Before choosing a
cheaper proxy for the table, which side of the student's play the optimism
comes from. P1, the decomposition, on the bid check's own 2,880 tasks
(seed 9201): BidCheckMain seats the declarer and the defence separately
(--declarer, --defence: a table player, "student" as the labels play, or
"student-greedy", a defender by its top card), and `bid_check.py compare`
sets the runs against the all-search one task by task.

- student / student (T=1): must reproduce the labels (the path's own check)
- student-greedy everywhere: is it the temperature-1 defence alone?
- search declarer / student defence, and student declarer / search defence
- search declarer / student-greedy defence

Then P2: the cheapest seating within a point or two of the table's, by
cost and bias -- the label generator's new play-out.

*P1, the decomposition (2026-11-15; the 1,440 tasks every seating played,
every second of the bid check's, k 2).*

| seating (declarer / defence) | all | minus labels | minus the table (search / search) |
| --- | --- | --- | --- |
| search / search (the table) | +13.6 | -12.69 [-14.53, -10.85] | |
| student / student T=1 (the labels' own) | +25.9 | -0.35 [-1.74, +1.04] | +12.34 |
| student / student greedy | +19.7 | -6.57 | +6.12 [+3.65, +8.59] |
| search / student T=1 | +24.6 | -1.65 | +11.04 [+8.96, +13.13] |
| search / student greedy | +19.3 | -6.94 | +5.75 [+3.64, +7.86] |
| student / search | +12.3 | -13.91 | **-1.22 [-3.32, +0.88]** |

The student/student path reproduces the labels (-0.35), so the instrument
is sound. The optimism is the student's defence, all of it: the student
declaring against the search's defence is the table's game (-1.2, not
resolved), the search declaring against the student's defence is the
labels' (+11.0 above the table). Half of it is the temperature-1 draw (a
greedy defence +6 above the table instead of +12); half is the student's
defence itself. Its declaring is good enough -- the cheap seat can stay.

P2: a cheaper defence that lands near the table, by cost and bias, with the
student declaring: belief-4/8/16-shipped (new: the shipped player with fewer
worlds), belief-32-shipped, search-8 (uniform worlds), hybrid-8/16-t2/t4
(new: the student's opening, a small search after trick 2 or 4).

*P2, the cheaper defences (2026-11-15; every fourth task, 720, k 2; the
student declaring; thread-seconds a play-out on bigbox's 24 threads).*

| defence | minus the table (search / search) | cost |
| --- | --- | --- |
| belief-64-shipped (stdecl-sdef) | -0.83 [-3.75, +2.08] | 17.8 |
| belief-32-shipped | **+1.06 [-2.00, +4.11]** | 10.3 |
| belief-16-shipped | **+2.80 [+0.02, +5.58]** | 5.5 |
| belief-8-shipped | +10.44 [+7.49, +13.38] | 2.9 |
| belief-4-shipped | +14.25 | 1.6 |
| search-8 (uniform worlds) | +19.84 [+16.55, +23.13] | 0.5 |
| hybrid-8-t2 / -t4 | +9.00 / +10.67 | 0.1 / 0.03 |
| hybrid-16-t2 / -t4 | +7.87 / +8.09 | 0.17 / 0.03 |
| (student greedy, from P1) | +6.12 | ~0.1 |
| (student T=1, the labels) | +12.34 | ~0.1 |

The defence needs the belief and 16 worlds or more; below that it is worse
than the student playing its top card, and uniform worlds are worse than the
labels. The hybrids are as cheap as the student and as optimistic as it:
the student's first two tricks of defence alone cost about five points
(hybrid-16-t2 against belief-16), so the opening leads are where its defence
is weakest. The saving over the table's own defence is 1.7x (belief-32) to
3.2x (belief-16), not the 5-10x hoped for: relabelling the bidder's 20,000
boards at k 8 with either would take days.

*P3, the bidder on better labels (2026-11-16).* The label play-outs keep the
student declaring and seat a 16-world belief search in the two defenders'
seats (BidLabelMain and tools/bid-labels.sh --defence; the P2 bias +2.8
above the table instead of the student's +12, 5.5 thread-seconds a play-out)
at k 2. Seed 9201's 2,000 boards relabelled as the test set, 8,000 of seed
9202 as the training set. train_bidder.py --init starts every seed from a
trained bidder (fine-tuning; --epochs 0 evaluates it as it is). The three
candidates on the new test labels, in the auction simulation against the
shipped bidder: bidder-net-v1 as it is, bidder-net-v1 fine-tuned on the
8,000, a bidder trained on the 8,000 alone; the best of them to the table.

**Phase I -- imitation.**

- **I1, the exporter.** Every card decision, every seat and contract:
  the position (the belief net's encoding), the legal cards, the
  teacher's vote share and cushion per card, and the card it chose; in
  the belief data's shard format. Bidding and the discard stay out at
  first.
- **I2, the first corpus.** 90,000 boards (about a million teacher
  decisions at the mixed seating), one to two days on the host;
  training on a quarter, a half and all of it shows whether more data
  still pays.
- **I3, the policy net.** Trained on the teacher's vote distribution,
  not only its card. Whether it takes the belief net's output as an
  input is measured, not assumed.
- **I4, the student players.** The net alone, and the net naming a
  short list for a small search. Gates in order: agreement with the
  teacher on held-out games; the pairing against the shipped player and
  against the teacher; time per card on the S25 Ultra.
- **I5, scale** to 200,000-500,000 games if the curve is still rising.

**Phase S -- ship the student.** Gated against the outside bots (XSkat,
SkatZero), then size, load time and time per card in the app. Bidding
keeps its own search; its phone time is a separate question.

**Phase R -- self-improvement.** The student guides the teacher's
search, the teacher produces new data, the student is retrained; the
only way past the teacher. Long-term, priority open, may be left out.

**Not taken: the GPU.** Solving worlds in a shader was considered. The
solver is alpha-beta with a transposition table and tree sizes that
differ a hundredfold between worlds of one decision -- divergent,
memory-bound work that a GPU does badly -- and a decision's 32-64 worlds
are far too small a batch; across thousands of games the batches would
be big enough, but that work already splits perfectly over the CPU
threads. After Phase I the search runs only on the host, where
throughput is the question and a week of CPU is an acceptable answer.

**Caching (Philipp's question, to be measured in T2).** A solved
position -- the remaining hands, whose lead, the cards in the trick --
has one value whatever world it was reached from, for a fixed contract
and declarer. So a cache keyed on the position is valid across worlds,
across the questions a decision asks, and across the decisions of one
game. Today every `movesReaching` call builds a fresh solver and table,
so none of that is reused. The proposal was a cache of the last three
or four tricks only; the more useful form is probably the solver's own
transposition table kept alive with a bounded size and a replacement
rule (the "fading" the proposal asked for), because it already stores
bounds valid for any target. Two unknowns decide it, and both are
measurable before any build: how often a position solved in one world
recurs in another world of the same decision (hidden cards differ, so
recurrences come late in the tree), and how much of a solve's time is
spent where those recurrences are. A last-trick endgame is a few hundred
nodes, so caching only those saves little per hit; the payoff, if there
is one, is in the middle of the tree.

*Measured first (2026-09-26, T2's opening question).* `tools/endgame-probe.sh`
(`EndgameProbeMain`; a probe hook in the Java search, `DoubleDummySolver.setSearchProbe`,
null in play; the native engine is not instrumented, so this runs the Java
search). The shipped player in all seats, four games in the container --
a first look, to be confirmed on 60 boards on the host:

- **Where the work is -- Philipp's intuition holds.** 63% of all solver
  nodes lie below a cut of three tricks to go, 87% below four; the last
  three tricks alone hold 63% (34%, 23%, 5% at three, two and one).
- **A global endgame table would save almost nothing.** Of the positions
  at the three-trick cut, 1.8% had been reached before by another search
  of the same decision, 0.2% by an earlier decision of the game, none by
  another game. A cache that outlives one search -- for the decision, the
  game or for good -- saves 3.7%, 4.1% and 4.1% of all nodes (4.9-5.1% at
  a four-trick cut). Worlds differ in the hidden cards, so their endgames
  differ too; and different deals almost never meet.
- **The repeats are inside each search.** 90% of three-trick positions had
  been reached before *in the same search*, and searching them again cost
  21% of all nodes (25% at four tricks). The solver's own table holds only
  bounds from null windows shifted by the points already taken, and evicts
  the entries with the fewest cards first -- exactly these. An endgame
  store that keeps exact values for positions with three or fewer tricks
  to go, and is never the first thing evicted, is the speed-up this
  points at: about a fifth of the nodes, within every search, on the phone
  as well as the host. To be built and measured against the native engine
  in T2; the global table is not built.

*Confirmed on the host (2026-09-26, 3a7e44b; seed 14, 60 boards, 56
games, 1,284 decisions, 27 billion nodes).* The same picture: the last
three tricks hold 60% of the nodes (34%, 22%, 5%), the last four 85%. A
cache that outlives one search saves 3.5% at the three-trick cut, 3.9%
kept for the game, 3.9% kept for good (another game's repeats 0.0%); at
four tricks 5.6/5.8/5.8%. Repeats inside one search cost 21.9% of all
nodes at three tricks and 26.7% at four. The global endgame table is
closed; the in-search endgame store is the T2 build, after one more
reading: whether those in-search repeats miss the table because the
entry was evicted or because it held a bound the new window could not
use -- the two call for different repairs (the replacement rule, or
exact values for the last tricks).

*Read (0e233a1, same 60 boards).* Of the 21.9% of nodes repeated inside
one search at the three-trick cut: 13.2% a bound the new window could
not use, 7.2% evicted, 1.5% never stored (every earlier visit settled by
the points left); at four tricks 19.8 / 5.0 / 1.9%.

*Why this is parked, not built.* Every figure above is nodes a second
visit cost, not time saved net of what the repair costs:

- The bound share is not free to recover. Exact values for the last
  tricks mean the first visit searches with a full window instead of a
  null one -- the very thing that makes our questions an order of
  magnitude cheaper -- so the first visits get dearer by an unknown
  amount. A table entry keeping both an upper and a lower bound is the
  cheap version and recovers only part of it.
- The eviction share trades against itself: keep the endgames and the
  entries evicted instead are deeper ones, dearer to search again. A
  bigger table costs memory misses on every node.
- Nodes are not time, and this is the Java search; the native engine,
  which the host and the phone run, would need the same change and its
  own measurement.

So the realistic gain is somewhere between 5% and 20% of solver time,
for a change inside the solver both engines share. Against it: after
Phase I the phone runs a network, not the search, and on the host a 20%
faster teacher shortens a five-day data run by one day. Parked behind T1;
reopened only if the teacher's data runs turn out to be the bottleneck,
and then as a Java experiment first (both-bound entries, and a
replacement rule that spares the last tricks) that has to show 15% wall
time on fixed positions before anything is ported to C++.

## 2.9 Where the SkatZero gap is, 2026-09-25: the games our declarer gives up on

`tools/skatzero-gap.sh`, two instruments on the player that ships.

**The row.** `belief-32-shipped` − `skatzero`, oracle contracts, three
seeds, 200 boards: −1.29, −1.93, −4.54, **pooled −2.59 [−6.87, +1.69]** --
the 2026-09-17 shipped player read −2.57. Unchanged, but not in the same
place: that day the whole gap was Null (39% of makeable Nulls won against
78%); today Null is **18 of 18 for us against 16 of 18**, the Null levers
did what they were bought for, and the suits are level (223 of 247 won
against 222). What is left is Grand -- 245 of 260 against 253 -- and seed
13, where SkatZero made five more Grands and three more Spades and the row
resolved on its own at −4.54. Eight Grands in 260 is a difference at the
edge of what three seeds can see, so the row alone does not say where the
gap is. The audit does.

**The audit.** `declaring-audit.sh --par=skatzero`: the same 529 boards
at greedy's contracts, our defenders both times, the solver judging every
card of both declarers. SkatZero wins 319 to our 302, from declaring −3.76
against −5.75; on the not-cold boards it alone wins 44 and we alone win
28. And the card play that does it is *worse* by the solver's lights:
SkatZero throws 52 won positions in 44 games where we throw 33 in 29. It
wins because our defenders hand it more games -- 142 gifts in 117 games
against 118 in 102 -- and the place that happens is exact:

| boards where our declarer saw every card at zero (238 of 529) | ours | SkatZero |
| --- | --- | --- |
| won | **17** (7%) | **48** (20%) |
| defender decisions while the game was lost | 3,487 | 3,304 |
| of them, positions with a card that gives the game away | 413 (11.8%) | 515 (15.6%) |
| such positions three quarters or more of whose cards give it away | 19 | 40 |
| gifts actually taken | 25 (6.1% of chances) | 58 (11.3%) |

Everywhere else the two draw errors at the same rate (16.9% against 16.7%
of chances on the not-cold boards as a whole). The difference is confined
to the games our declarer has decided are lost -- 45% of the boards -- and
there it is mechanical. At flat zero the ladder aims at 31: the Schneider
defence that shipped for +1.30. That line cashes what it can and hands the
rest over, and the defenders, offered nothing to get wrong, get nothing
wrong: our trap count falls off after trick five (t6-t9: 160 chances to
SkatZero's 218) and the traps we do leave are narrow. SkatZero, which has
no notion of a lost game, keeps playing for the win, leaves twice as many
wide traps, and the same defenders walk into them at twice the rate. The
take rate rises with the width of the trap on both sides (under a quarter
of the defender's cards losing: 3-4% taken; three quarters or more: 21%
for ours, 40% for SkatZero's), so width is a proxy the solver can compute,
and it is not the whole of it -- at equal width SkatZero's traps are still
taken more often, which is the part no double-dummy criterion sees.

**The lever, named.** In a position the vote calls lost in every world,
a card's worth is not its floor but the share of the defender's replies
that hand the game back -- and that share is one solver question per
card per world, `movesReaching` asked from the defender's seat at the
winning rung, the same cost as the rung tally the ladder already pays.
The variant: at flat zero, tally that width over the sampled worlds and
play the widest trap, with the 31-point rung as the tiebreak rather than
the goal -- and its mirror, 31 first and the trap as the tiebreak, because
the ladder's +1.30 was real and a trap that concedes Schneider in the
worlds where it is not sprung may cost more than it wins. Both are
measured before either ships: the audit against the solver (flat-zero
games won, 17 today, and the Schneider count beside it), then the split,
then the pairing against the shipped player, then this row. SkatZero's
48 of 238 is the ceiling a trap criterion can aim at; the gap's whole
2.0 a game from declaring sits in those 238 games.

*Built (2026-09-25):* `SearchAiProvider.TrapOrder` and `trapWidth`,
registered as `belief-32-shipped-trap` (widest trap, 31 as tiebreak) and
`belief-32-shipped-trap-safe` (31, then the widest trap), both the shipped
player plus the tally and nothing else. `TrapWidthTest` checks the width
against a brute-force minimax on 400 random three-card endgames through
all three paths (reply inside the trick, a defender leading next, the
declarer leading again), and fails on each of four seeded mistakes in it.
A container smoke run, seed 14, 38 boards -- a hint, not a result: games
won 16 / 20 / 17 (shipped / trap / trap-safe), flat-zero games won 1 / 5
/ 2, Schneidered 3 / 3 / 3, audit wall time +3% / +2%. The measurement
is `tools/trap.sh`.

*Measured (2026-09-25, `tools/trap.sh`): nothing, and the reason is
visible.* The audit on seeds 14-16 against the shipped player on the same
boards:

| seeds 14-16, 520 boards | won | from declaring | flat-zero games won | Schneidered |
| --- | --- | --- | --- | --- |
| belief-32-shipped | 275 | -9.25 | 15 of 252 | 34 of 245 |
| belief-32-shipped-trap | 276 | -9.34 | 16 of 252 | 37 of 244 |
| belief-32-shipped-trap-safe | 277 | -9.02 | 17 of 252 | 33 of 243 |

The pairing against the shipped player, seeds 11-13: trap-first +0.47,
-0.81, -0.27, **pooled -0.20 [-1.80, +1.39]**; safety-first +0.56, -0.49,
-0.18, **pooled -0.04 [-1.38, +1.31]**. Neither ships; the 38-board smoke
was noise. The cost was invisible (games/s unchanged).

Why, from the decision files: the variants played the shipped player's
card at 1,050 of the first 1,150 flat-zero decisions (trap-first; 1,168 for
safety-first) and diverged in 98 and 76 games of 520, always first at a
flat-zero declarer decision -- so the gating is exact and the tally is
simply flat most of the time. It is flat because it looks at the wrong
defender. Split the gift chances by who is to play:

| defender decisions while the game is lost, with a card that gives it back | right after the declarer | after the other defender |
| --- | --- | --- |
| SkatZero, s11-13 | 299 chances, 34 taken (11.4%) | 526 chances, **108 taken (20.5%)** |
| shipped, s14-16 | 267, 21 (7.9%) | 466, 85 (18.2%) |
| trap-first, s14-16 | 281, 21 (7.5%) | 471, 87 (18.5%) |

Three quarters of the games handed back are handed back by the *second*
defender to play after the declarer's card -- the partner of the one the
width counts -- and those errors are taken at twice the rate. The one-ply
width raised the chances it can see (267 to 281) and not the rate, and it
cannot see the others at all. The lever is the same tally carried one
defender further: for each card, the chance that a defender choosing at
random among its legal cards hands the game back before the declarer is
next to move -- the first defender's gifting share, plus, over its other
replies, the second defender's. One more solver question per safe reply,
a few times today's (unmeasurable) cost. That is the next variant; the
two built today stay registered as its controls.

*Built:* `TrapOrder.TRAP_FIRST_BOTH` and `SAFETY_FIRST_BOTH`, registered as
`belief-32-shipped-trap2` and `-trap2-safe`; `trapWidth` takes the number
of defender decisions to look through. A safe reply hands on to the next
defender's chance, uniform over its legal cards, until the declarer is
to move again -- the count stops there without asking, since after a safe
reply every declarer card would answer no. `TrapWidthTest` checks the
two-defender width against the brute force on three- and four-card
endgames (it adds to the one-defender width in over 200 of them), and
`TrapOrderGameTest` plays deals out and requires the trap, and the depth,
each to change some card the declarer plays -- the part no test of the
helper could see; it fails with the depth not passed, or the trap never
asked. `tools/trap.sh` now runs this pair by default.

*Measured (2026-09-25, evening): nothing either -- the trap line closes.*
Audit, seeds 14-16: won 273 / 270 against 275, flat-zero games won 13 /
10 against 15, Schneidered 34 / 32 against 34. Pairing, seeds 11-13:
two-defender trap-first **-0.18 [-1.06, +0.70]**, safety-first **+0.04
[-0.66, +0.75]**. Four trap variants, one and two defenders, both
orders: none moves the flat-zero games or the points. What SkatZero does
in those games is not a width a double-dummy tally can see; the
registrations stay as controls and nothing ships.

**Where that leaves the question: how the worlds add up.** The shipped
declarer's own decisions on those 520 boards, by the vote share of the
card it played, against two outcomes -- whether the card wins in the
true deal against perfect defence (what each world's vote assumes), and
whether the game was in fact won at this table:

| vote share of the card played | decisions | wins vs perfect defence | game actually won |
| --- | --- | --- | --- |
| 0 | 1,497 | 0.1% | 2.3% |
| 1-25% | 274 | 8.0% | **31.0%** |
| 26-50% | 204 | 25.0% | **46.1%** |
| 51-75% | 215 | 57.2% | 70.2% |
| 76-99% | 382 | 88.5% | 93.2% |
| 100% | 1,497 | 99.7% | 100.0% |

The vote is calibrated -- against the opponent it models, a defence that
sees every card. Against the defence actually at the table it is
pessimistic by twenty points in the middle band: a card the worlds give
one chance in eight wins the game nearly one time in three. Every world
is solved as if the defenders were omniscient, so the tally prices
exactly the thing real defenders are not. Whether that mis-*ranks*
cards, rather than only mis-levels them, is the question the next
instrument has to answer: from recorded positions, play each legal card
out against the real defenders several times and compare that ranking
with the vote's.

*Measured (2026-09-26): the rollout audit.* `tools/rollout-audit.sh`
(`RolloutAuditMain`): the shipped declarer's games on seeds 14-16 are
recorded; from 36 decisions in each band of the played card's vote share,
every legal card is forced and the game played on eight times by the same
four players at the table, the random streams shared across cards. The
replay check reproduced 24 of 24 recorded games card for card.

| band | vote's card best at the table | vote's card wins | best card wins | split-half shortfall, g.p. a decision |
| --- | --- | --- | --- | --- |
| flat zero | 83% | 3.5% | 5.6% | +0.50 |
| 1-25% | 61% | 22.6% | 31.9% | +2.25 |
| 26-75% | 67% | 60.1% | 73.6% | +6.67 |
| 76-99% | 61% | 89.6% | 96.9% | +3.56 |

The shortfall is not the price of the aggregation alone. The rollouts are
played on the true deal, so "best at the table" is chosen by someone who
knows where the cards are; the shortfall is the value of that knowledge
plus whatever the vote gets wrong with the knowledge it has. The CSVs
separate the two roughly -- game points a decision, averaged per probe:

| band | vote's card | cards tied with it at the top | cards the vote ranks lower | cards that win in the true deal (probes) |
| --- | --- | --- | --- | --- |
| flat zero | -51.4 | -51.8 | -- | -- |
| 1-25% | -24.9 | -25.2 | -30.0 | +18.0 (3) |
| 26-75% | -1.0 | -0.1 | -8.7 | +23.7 (18) |
| 76-99% | +20.5 | +20.4 | +9.1 | +22.9 (33) |

Where the vote ranks one card above another, the table agrees: the
cards it ranks lower are 5 to 11 points worse against the real
defenders, and among the cards it ties at the top the table has no
preference. What beats its card is the card that wins in the true deal,
by 20 to 40 points -- a card the vote cannot tell from the others
without seeing the deal. So the pessimism of the table above mis-levels
the vote and, on this evidence, does not mis-rank it: most of the
shortfall is information. At flat zero no single card recovers the game
against these defenders (the best 5.6%, all cards within half a point),
so SkatZero's flat-zero wins are not one better card at that moment --
they are made earlier, or over several cards, which forcing one card
cannot show. Two limits: the per-decision shortfalls do not add up per
game, and eight rollouts a card are noisy (the split-half estimate keeps
the selection bias out, not the noise). What this audit cannot say is how
much the vote loses against a better use of the same worlds; the next
instrument asks that with the information held equal -- rollouts inside
the player's own sampled worlds, a card chosen by their mean, scored on
the true-deal rollouts recorded here. Built the same day as
`tools/world-rollout.sh` (`WorldRolloutMain`; `WorldRolloutTest` holds
the rebuilt world board to the recorded game when the world is the true
deal, and to the history in every sampled world).

*Measured (2026-09-26): the same worlds, used better, buy little.*
`tools/world-rollout.sh`, the audit's 144 decisions, all 32 tallied
worlds, one rollout a card a world -- 26,688 rollouts, none failed; the
record check found all 144 decisions again with the same card and vote.
Scored on the audit's true-deal rollouts, game points a decision:

| band | agree | vote's card | rollout's pick | best (knows the deal) | rollout - vote, 95% | est. of vote's card |
| --- | --- | --- | --- | --- | --- | --- |
| flat zero | 58% | -51.38 | -50.72 | -49.75 | +0.65 [-0.44, +1.74] | -52.54 |
| 1-25% | 58% | -24.90 | -28.02 | -18.84 | **-3.12 [-6.20, -0.03]** | -20.46 |
| 26-75% | 53% | -1.04 | +3.66 | +9.07 | +4.70 [-1.02, +10.42] | +2.09 |
| 76-99% | 50% | +20.51 | +22.46 | +24.81 | +1.95 [-0.45, +4.36] | +21.37 |
| all | 55% | -14.20 | -13.16 | -8.68 | **+1.05 [-0.71, +2.80]** | -12.38 |

Three readings. The rollouts get the *level* right where the vote did not:
their estimate of the vote's card is within two to four points of what
the table paid, band by band. They do not get a clearly better *card*:
+1.05 a decision, unresolved, worse in the low band, and where the two
disagree the true deal scores both cards the same in three decisions of
four (the disagreement is mostly between equivalent cards). The middle
band's +4.70 is the only hint, on 36 decisions. Post hoc, the rollout
used only as a tiebreak among the vote's top cards reads +0.81 [-0.67,
+2.30] -- a look chosen after the data, so not evidence. Against the
distance to the card that is best in the true deal (5.5 points a
decision here, before its noise), a better use of the same worlds
recovers perhaps one; the rest is information. The rollout defenders do
not see the declarer's hand, so this also bounds, for one card, what
playing on the defenders' ignorance of that hand is worth against these
defenders. At about ten CPU-minutes a decision it is an instrument, not
a player, and nothing here is built into one.

Where this leaves 2.9: the vote is not the lever -- neither its
counting nor its omniscient defenders cost a resolved point. What the
gifts SkatZero draws are made of (worked out from the audit's decision
files the same day): two thirds of all gifts, for both declarers, are a
defender's *lead*, not a reply inside the trick -- SkatZero 97 of 142
against our 78 of 118 on the same boards, and 16 against 6 wrong
discards, mostly low cards. Both are defender judgements about the
declarer's hand.

*Measured (2026-09-26): our defence gives away what SkatZero's keeps.*
`tools/defending-audit.sh` (`DefendingAuditMain`): the shipped player
declares the 520 boards of seeds 14-16 at greedy's contracts, once
against itself in both defender seats, once against SkatZero in both,
every card solved face up; the same-games check matched the declaring
audit on all three seeds. Against our defence the declarer wins 275,
against SkatZero's 255; the boards only one defence loses split 42 to
22 against us (sign test p about 0.02). The declarer's column reads
-9.25 against -12.34 game points a game -- three points a game, on the
defence alone. Of the 313 games the defence had at the first card, ours
gave 76 away and SkatZero's 58, every one by a gift; the declarer's own
throws are level (38 and 38).

Where our defenders' extra gifts are, as rates over the chances (a
defender decision with a choice while the declarer cannot yet win):

| situation | ours | SkatZero |
| --- | --- | --- |
| following suit onto the partner's card, declarer still to play | 15 of 351 (4.3%) | 5 of 326 (1.5%) |
| -- of which an ace or ten, or a king or queen under or over the partner | 11 | 4 |
| following suit onto the partner's card, declarer already played | 7 of 295 | 4 of 312 |
| leading an ace or ten of a side suit, tricks 2-4 | 18 of 164 (11.0%) | 10 of 159 (6.3%) |
| leading a low side card, tricks 2-4 | 10 of 166 | 5 of 173 |
| leading a jack, tricks 5-10 | 4 of 203 | 0 of 201 |
| leading a king or queen of a side suit, tricks 2-4 | 4 of 140 | 11 of 170 |
| all, trump games | 106 of 4,237 (2.5%) | 86 of 4,365 (2.0%) |

Two patterns carry most of the difference: points put on the partner's
card before the declarer has played -- feeding a trick the declarer can
still take -- and an ace or ten led into the declarer in the early
tricks. Both are about the declarer's hand (can it still beat or trump
this suit?). Each cell is a handful of gifts; the pattern, not the
cell, is the finding. Next: the defender's own vote at each of these
gifts -- tied or flat (a tiebreak's choice, a rule can fix it) or
confidently wrong (its worlds misplaced the declarer's cards, a belief
question for defenders).

*Measured (2026-09-26): the gifts are close calls, and no simple rule
separates them.* The same audit with our defenders' tallies kept
(b2f8684; the games unchanged, same-games OK). Of our 106 gifts in
trump games, 38 were tied with a safe card at the top and 55 outvoted
every safe card by at most a tenth of the worlds; 13 by more. In worlds:
the gift led the best safe card by 0 in 38, 1 in 21, 2 in 16, 3 in 18,
4 or more in 13 -- 93 of 106 within three worlds of 32. The two patterns
look the same (points onto the partner's card: 5 tied, 6 narrow, 2 wide;
an ace or ten led in tricks 2-4: 7, 10, 1). The worlds did not tell the
gift from the safe card; they were not confidently wrong.

Rules tried offline on the 4,237 chances, each scored by the solver's
verdict on the card it would have played instead (the game's result
against perfect play from there, so points fed are inside it):
"fewest card points among the cards within M worlds of the top" makes
things worse at every M (106 gifts to 107, 131, 135, 140 for M = 0-3);
"no ace or ten onto the partner's card before the declarer, nor led from
a side suit in tricks 2-4" at best 106 to 100 (ties only), and to 102 at
M = 3, changing 86 to 248 cards for it. Small margins carry signal: the
vote share of the card played orders the gifts (11.1%, 6.1%, 4.7%, 2.2%,
0.2% from the lowest share band to 100%). The levels in that table are
not a calibration -- the chances are selected by the true deal being
lost for the declarer, which makes every claim look pessimistic; the
report's label overstates it.

So the defence's gap is spread thin over close calls, not concentrated
in a pattern a rule can name. Two readings remain: sampling noise (a
margin of one to three worlds in 32 is inside it -- more worlds would
move those cards) or a belief that cannot separate the cases (more
worlds would not). A defender with 64 worlds in the same audit decides
between them.

*Measured (2026-09-26): twice the worlds close about half the defence's
gap.* `belief-64-shipped` (705f2b3, the shipped player with 64 worlds a
trump card) in SkatZero's place, same audit, same-games OK:

| defence | declarer won | declarer g.p./game | not-cold games given away | gifts (trump games) | onto the partner's card |
| --- | --- | --- | --- | --- | --- |
| ours, 32 worlds | 275 | -9.25 | 76 | 106 | 22 |
| ours, 64 worlds | 264 | -11.05 | 61 | 90 | 13 |
| SkatZero | 255 | -12.34 | 58 | 86 | 9 |

Every column moves the same way, most of the way to SkatZero on the
gifts and about sixty per cent of it on wins and points. Alone it is
not resolved: the boards only one defence loses split 31 to 20 (sign
test p about 0.16). So the close calls are in good part sampling noise,
and the belief is not the whole story.

What it would cost. The top two cards' tally, over our defenders' 7,881
decisions: tied 56%, within 1-3 worlds 25%, 4-8 6%, 9 or more 3%, all
at zero 10%; the declarer's 4,069: tied 51%, 1-3 8%, 9+ 3%, all at zero
37%. Two consequences. Stopping early when the leader cannot be caught
-- the exact, free version -- would almost never fire: ties dominate,
and a tie sends the tiebreak to the full tally. And adding worlds only
where the top is below every world and the next card within three
covers 60% of the defenders' decisions (68% of their card solves) and 96
of the 106 gifts -- so a close-call-only 64 costs about two thirds of a
flat 64. Whether any of it is worth the phone's time is the pairing's
question: `belief-64-shipped` against the shipped player, fixed
contracts, seeds 11-13, with the games-per-second line as the price.

*Measured (2026-09-26): card points on close calls change nothing.*
`belief-32-shipped-bands` (4462a5a; `SearchAiProvider.withPointBands`:
when another card is within three worlds of the top vote, every world is
asked where the declarer's total lands in the bands 31/46/61/76/90, and
the best mean band score replaces the vote's card only with a paired t
of 2) in the defence: declarer won 278 against our 275, gifts 110
against 106, the boards only one defence loses 6 to 3 -- level, at up
to five extra questions a world on six decisions in ten. The reading
matters more than the number: finer scoring of the same 32 worlds buys
nothing, twice the worlds buys half the gap. The noise that costs our
defenders is in which worlds are drawn, not in the yes-or-no answer each
world gives. Closed; the registration stays as a control.

Two smaller readings from the same run. SkatZero's discard leaves the
game cold on 19 boards where ours does not, against 6 the other way;
that is a discard finding for Phase D's list, not for this lever. And
the honesty control on the outside bot: 0 of 5,290 decisions changed
under a reshuffle of the cards it cannot see.

## 2.8 B2 step 3 answered, 2026-09-20: one net, and it pays at Null

**Share the network.** The mixed net -- the v2 trump corpus plus 323,524
minted Null decision points -- is better at Null than a net trained on Null
alone, and costs the trump positions nothing. Held out, against the same
46.7% baseline:

| | mixed net | the alternative |
| --- | --- | --- |
| the Null corpus | **63.6%**, nll 0.6895 | Null-only net 62.4%, nll 0.7114 |
| the trump corpus | 66.2%, nll 0.6537 | trump-only control 66.2%, nll 0.6529 |

Trump is a dead heat contract by contract, 66.0 to 66.4 with the two
alternating for the lead by a tenth. There is no poisoning to weigh against
the Null gain.

And it reaches the table. Three seeds, `--fixed-contract --contracts=null`,
1,689 played boards, inverse-variance pooled:

| | |
| --- | --- |
| Null, fixed contract | **+0.74** [+0.32, +1.17], resolved; seeds +0.81 / +0.35 / +1.12 |
| Nulls made | 63.5% against 60.2% |
| trump card play | +0.32 [-0.85, +1.48], not resolved |
| full game against the shipped player | -0.01 [-0.73, +0.71], not resolved |

So the mixed belief is a strict improvement: it wins where it was given new
data and is indistinguishable everywhere else. The trump rows are the
result -- adding a contract the auction never reaches should not move games
that never contain it, and it does not.

**Three things worth keeping about how this was measured**, because the first
attempt at the same night produced +0.000 [+0.000, +0.000] and every one of
them had to be fixed before the number above existed.

- The sampler refused to consult any model on a Null, so both sides played
  identically on 539 of 539 boards. That refusal was correct when written and
  its own comment said to remove it the run after a model was trained on
  Nulls. A fact about a model now lives in the model: `belief.bin` format 2
  carries which contracts its corpus held, and a format 1 file is read as the
  old rule rather than as a guess about it.
- `check_data.py`'s census read a bounded sample and a mixed corpus sorts its
  minted shards last, so it reported "Null: none at all" about a corpus with
  323,524 Nulls while the trainer trained on them happily.
- A model trained before the split rule changed cannot be scored on the new
  split at all -- the new held-out tenth is its training data. The trump
  comparison is therefore against a control trained the same night, not
  against the shipped model, which read five points too well.

**The caveat that remains, and its size.** A minted Null has no auction behind
it, so the model learned Null given no bidding evidence, while the app's own
case is a Null a person declared after a real auction. Stress-tested by
pasting real bidding blocks from other deals onto held-out Null records:
64.5% to 63.9%, and merely setting the presence bit costs 0.1. Those pasted
bids contradict the cards in a way real ones never would, so that is an upper
bound on the cost and the corner is not a cliff. Worth minting Nulls behind a
real auction before this is leaned on harder, not before shipping it.

## 2.7 Where we stand after the belief retrain, 2026-09-17

The shipped player is `belief-32-adaptive-margin-ties` on the **v2 belief**:
the same network retrained on a void-mode corpus from `greedy, search-4,
club, expert, jskat-new, xskat-blind, go-skat`. Three seeds, void mode;
details in [`../arena/README.md`](../arena/README.md), 2026-09-17.

| v2 belief minus v1, same player | full game |
| --- | --- |
| exact pairing | **+0.69** [+0.04, +1.33], seeds +0.65 / +0.66 / +0.75 |
| paired by board against `xskat`, `jskat-new`, `go-skat` | +0.04, +0.70, +0.17, all non-negative |

- **The population was a limit**, worth about as much as each of the three
  rules. Held-out placement went from 64.6% to 66.3% against the same
  46.7% baseline, and the gain shows against every outsider, not only the
  two whose style entered the corpus.
- **B2's cheap half is done in one night**; the expensive half (Null in
  the model, the true-world-share diagnostic, a longer or wider training
  run -- validation loss was still falling at epoch 20) is what remains
  of B2 and is now worth doing, since the belief has been shown to move.
- **Standing against the field** is the 2.6 table plus about two thirds
  of a point: level with XSkat, three ahead of go-skat, twelve ahead of
  JSkat; card play 3.6 behind SkatZero at oracle contracts and 5.4 behind
  the honest ceiling, both taken with the v1 belief.

- **The ladder is on record** (2026-09-17, second entry): −3.76 / −5.91 /
  −2.91, every step resolved on every seed; the rules are worth +2.85 at
  beginner and +1.69 at club, both resolved now.

- **Card play is level with SkatZero at trump contracts** (2026-09-17,
  third entry): of 507 oracle trump games, `belief-32` loses 56 to the
  cheat and `skatzero` 61; of 18 Nulls, we lose 11 and it loses 4. The
  head-to-head deficit is a Null deficit. The shipped player is
  −2.57 [−3.85, −1.28] against SkatZero, a point closer than the
  2026-09-15 player was.
- **Null is the one unvisited corner left.** The v2 corpus has 0 Null
  decision points in 511,816 records, for the same reason the v1 corpus
  did: nothing in the population announces Null. The belief guard is
  therefore still correct, and Null is played on uniform worlds.

- **Null, first round** (2026-09-18 second): 128 worlds is worth **+1.17**
  [+0.37, +1.98] at Null, resolved, and lifts the win rate on makeable
  Nulls from 59% to 64%. The tiebreak idea was refuted at **−1.33**
  [−1.95, −0.72]: a card reaching the tiebreak already survives every
  sampled world, so what is left to choose is robustness to the worlds
  nobody sampled, and there the low card is the wider margin.

**Next, in order:** Null card play, which is now the largest measured
weakness and the cheapest to attack. Built 2026-09-18 and part measured
(`tools/null-card-play.sh`): a Null-only contract source, because at
3% of the oracle mix there is nothing to measure on; a tiebreak that
stops ordering a Null by card points, which the contract does not score
and which inverts Null's own rank wherever a ten meets a court card; and
more worlds for Null, whose solves are the cheapest this player makes.
Then the part no lever reaches: Null in the belief model, which needs a
corpus that contains a Null at all (§B2 step 3 -- minted with
`--contracts=null`, since no bidder announces one), and a measurement of
whether Null belongs in the shared network or in one of its own. Then the rest
of B2 (the longer or wider training run, the true-world-share
diagnostic); then, if it still looks worth it, the opponent-modelling
cheat as a second ceiling instrument.

## 2.6 Where we stand after the tiebreak run, 2026-09-16

The shipped player is `belief-32-adaptive-margin-ties`: the reference
player with the auction allowed to re-price the hand, card-play ties
broken by a fifteen-point cushion, and the ties that leaves broken by the
position in the trick. Three seeds, void mode; details in
[`../arena/README.md`](../arena/README.md), 2026-09-16.

| shipped minus | full game | change vs 2.5, paired by board |
| --- | --- | --- |
| `belief-32-adaptive-margin` (exact pairing) | +0.23 [−0.19, +0.66] | — |
| `xskat` | +0.72 [−0.64, +2.07] | **+0.80** [+0.26, +1.34] |
| `jskat-new` | **+11.72** [+10.48, +12.95] | +0.38 [−0.48, +1.24] |
| `go-skat` | **+3.01** [+1.61, +4.41] | +0.45 [−0.31, +1.22] |
| `solver` (oracle, `belief-32`) | **−5.38** [−6.61, −4.14] | the ceiling, now honest |

- **The rule tiebreak is small and non-negative everywhere**, resolved
  against XSkat when paired by board. Same standard as the adaptive bidder;
  shipped at every level. The ladder has not been re-measured with it.
- **The ceiling is honest now** (the cheat discards for its contract and
  plays Null with the Null solver) and it says something new: `belief-32`
  and `skatzero` are the same distance from perfect play (+0.23 [−1.34,
  +1.80] between them through the solver), while SkatZero beats belief-32
  by 3.6 head to head. The gap between the two honest players is made
  against imperfect opponents, which is the part determinized search
  cannot see and a policy trained under the fog can. The 5.4 points to
  omniscience are nobody's to teach yet.
- **The deterministic levers are used up.** Jack floor, cushion, position:
  each was structural, each passed the gates, together they are worth
  about two points over the reference. What is left in card play is the
  fog, and that is a learned player (§B2 for the belief, the pilot's
  imitation-then-anchored-PPO for the policy), not another rule.

**Next, in order:** B2's cheap half first -- a void-mode corpus from the
wider population (`greedy, search-4, club, expert, jskat-new, xskat-blind,
go-skat`; `skatzero` only once it has a delegate bidder, since as seated it
never bids and would teach the model that a pass means nothing), the same
model retrained, Null still guarded -- to learn whether the population
matters before the Null work is paid for; then the ladder re-run with the
three switches; then, if the population moved the belief, the rest of B2.

## 2.5 Where we stand after the ship run, 2026-09-15

The shipped player is `belief-32-adaptive-margin`: the reference player with
the auction allowed to re-price the hand and card-play ties broken by a
fifteen-point cushion. Three seeds, 300 boards, void mode; details in
[`../arena/README.md`](../arena/README.md), 2026-09-14 and 2026-09-15.

| shipped minus | full game | change vs 2.4 |
| --- | --- | --- |
| `belief-32` (exact pairing) | **+1.07** [+0.32, +1.83] | — |
| `xskat` | **−0.05** [−1.35, +1.26] | +1.18 [+0.20, +2.16] paired |
| `go-skat` | **+2.57** [+1.18, +3.96] | +0.32 paired |
| `jskat-new` | **+11.35** [+10.14, +12.55] | unchanged |
| `skatzero` (card play, oracle) | **−3.58** [−4.85, −2.31] | new reference |

- **Level with XSkat**, from −0.73 at 900 boards and −1.31 at 300. Both
  mechanisms are structural -- a hard jack floor from the score sheet, a
  robustness term on the vote -- and learn nothing about any opponent, which
  is what the population constraint (§4) requires and why they passed every
  gate at once.
- **The pass side of the adaptive bidder is not a lever**: a harder reading
  sat on top of the reference on every gate. The reference reading stays.
- **SkatZero is the new card-play ceiling on the ladder**, 3.6 points above
  our best at oracle contracts, honest by construction. Self-play deep Monte
  Carlo, no inference, no search. The double-dummy cheat is not a ceiling in
  its current form: it discards with `greedy` and wins only 85% of oracle
  declarations, so both honest players measure level with it.
- **In the app, every level plays with both switches** (from the ladder
  re-run of the same day: beginner +1.73 and club +1.23 over themselves
  without, positive on every seed; the ladder stays four levels a level
  apart). The worry about the cushion at few worlds did not materialise.

**Next, in order:** the deterministic tiebreak rules below
the cushion (position-aware: last to play and winning, take the trick;
losing, give the least; otherwise the lowest of touching cards), one row,
expected small -- built 2026-09-15 as `belief-32-adaptive-margin-ties`,
queued in the overnight script, not yet measured; the cheat's discard fixed
so the distance-to-omniscience row means what it says -- built the same
day (`SolverAiProvider.solvedDiscard`), the two oracle rows to be
re-measured with `--redo=vs-solver-oracle`; then B2, the belief model
retrained on a void-mode corpus that includes `xskat`, `go-skat` and
`skatzero`.

## 2.4 Where we stand after the 900-board run, 2026-09-12

Three seeds at 900 boards each, void mode, every outsider blind-checked. In
[`../arena/README.md`](../arena/README.md), 2026-09-12.

| belief-32 minus | full game | card play (oracle) |
| --- | --- | --- |
| `xskat` | **−0.73** [−1.47, +0.00] | **+3.22** [+2.42, +4.02] |
| `go-skat` | **+1.95** [+1.20, +2.70] | **+8.22** [+7.07, +9.36] |

- **Information leakage is closed.** Paired board by board, blinding either
  engine changes the result by under 0.01 game points a game. The auction was
  already blind from source. Nothing about either opponent is dubious.
- **Card play is ahead of both, resolved.** Belief v2 (B2) remains worth doing
  but it is improving the part that is already winning.
- **The full game is where the points go**, and by an amount that differs by
  opponent -- 3.95 against XSkat, 6.27 against go-skat -- which means "full
  game minus oracle" is not measuring the bidding alone. The oracle's contract
  mix amplifies a card-play edge; a real auction's does not. **That confound is
  inside every auction-cost figure so far**, and the next run removes it.

**Immediate next (queued in the overnight script):** card play at fixed
contracts drawn from a real bidder -- `--contracts=auction --bidder=xskat` and
`--bidder=belief-32` -- so that bidding and contract mix separate by
subtraction. What comes after depends on the answer:

- edge at realistic contracts still near +3 → the bidding costs ~4, build the
  ceiling instrument (does the bid cap, priced on ten cards before the skat,
  lose auctions it should win?)
- edge drops to ~+1 → most of the "auction cost" was contract mix, the bidding
  is only ~1–2 behind, and B2 moves back up the list

## 3. The levers, ranked

1. **The belief model.** +2.4 delivered against +25 available. Before training
   anything, find out *why*: the design named the diagnostic — the share of
   sampled worlds that are the true world, by trick — and never printed it. That
   one number places us on the oracle's curve and tells us whether the ceiling
   is the corpus, the encoding, the network, or how the sampler uses it.
2. **Effort.** Worlds are still paying (+2.6 for the last doubling), and the
   native solver makes them cheap on the workstation. The reference player is
   not bound by the phone's budget, and that separation should be explicit.
3. **Declaring.** Eight points to par, a tenth of it in the discard. A
   belief-weighted discard search is the obvious first bite; the line choice is
   the rest, and it is where a better belief pays twice (the 2026-08-24 note that
   sharper priors and more samples look like complements).
4. **The auction** — *promoted above declaring by Phase R, see §2.3*. The Null
   variants first (35/46/59, so a Null hand can outbid a mediocre suit game),
   then the declining threshold: nine boards a hundred that XSkat takes and
   makes, we pass. A learned bidder is a later question.
5. **Defence.** Measured as not the place. Leave it.

## 4. The phases, each with the measurement that decides it

Every phase ends in a number the arena prints. A phase whose gate does not
resolve is not passed by argument.

### Phase R — re-baseline (two nights, no code)

One command: `./tools/overnight-phase-r.sh`. It builds the helpers, moves
every auction-mode log from before the fix aside (once), and runs
`overnight-arena.sh`, which now carries the outside engines and the honesty
probe as part of the standard night. Stop it with a `STOP` file or Ctrl-C —
the running match is killed within seconds and repeated next time — and
re-run the same command to carry on; two evenings are one run.

- `--seeds` also purges and regenerates `tools/challenge-seeds.tsv` (off by
  default: it rewrites files under `core/` and is a product change to commit
  on its own).
- Every auction-mode ladder entry and the aggression sweep, with the
  Null-capable bidder.
- `xskat`, `xskat-blind`, `go-skat` on the ladder in both modes, our best
  against each at oracle contracts, the leak priced over three seeds.

**Gate:** ladder v3 published in the README, and the contract mix per player
printed beside it — the Null column is the check that the fix reached the
auction.

### Phase V — the void-board mode (a day)

- `--passed-in=void` in `DuplicateMatch`/`GameRunner`; the report keeps the
  Ramsch column and adds a passed-in column.
- The exporter takes the same flag.

**Gate:** in that mode, `delegated games` from the external-bots summary is 0
and the Ramsch rate is 0; the fixed-contract numbers are byte-identical to
before (the flag must not touch them).

**Built 2026-09-09 (d09b943), and the delegation was larger than this section
assumed.** The Phase R logs put it at 6.7-14.9% of games and 36-79 delegated
games in 900, every seed -- about one auction-mode game in ten against an
outside engine played by `greedy` rather than by the engine. The JSkat adapter
does the same thing with `RamschPolicy`, so `jskat-new` was affected too and
its auction-mode matches now run void as well.

Unit-verified at six seeds: every Ramsch becomes exactly one passed-in board,
the ramsch count goes to zero and the game count is unchanged.

**GATE PASSED 2026-09-09**, xskat - greedy, 100 boards, seed 11:
```
ramsch 0.00% / 0.00%   passed in 12.67% / 14.33%
External bots: 776 games, 0 delegated (0.00%), 0 rule divergences
```
Two bugs were found on the way and are worth reading before Phase A, both in
[`../arena/README.md`](../arena/README.md): a passed-in board was paying every
seat a 40-point defender bonus, and the delegation counter was counting the
decision to delegate rather than the stand-in ever playing.

**Phase A is blocked on one decision, not on code.** The bidder weighs
declaring against the Ramsch that passing buys -- but in void mode passing buys
nothing, so under `--passed-in=void` it discounts against a penalty the mode
has removed. Three ways out are set out at the end of the README; the choice
changes what the sweep measures. *(Overtaken, 2026-09-13 to 15: the sweep
ran in void mode with the discount left in -- the third way -- and the dial
lost to its own guards; the adaptive bidder with the cushion shipped instead.
The Ramsch discount still prices against the canon in the app, which is
where it belongs. Nothing here is waiting on a decision any more; the
paragraph stays as the record of why the void-mode auction numbers carry a
constant offset.)*

### Phase B2 — belief v2 (the week that matters)

1. **Diagnose before training.** Print the true-world share per trick for the
   current model against the uniform sampler, in `train_belief.py`'s eval and
   as an arena-side probe on real games. Expected: low single digits at trick
   one, rising. That number is the baseline everything below is measured
   against, and it decides where to look: a corpus problem shows as a model that
   fits its held-out set and still samples the truth rarely in play; an
   encoding problem shows in `check_data.py` (is the bidding block populated at
   the rate the auction should populate it? after the fix, a Null bid is a
   thing the block must be able to say).
2. **A new corpus, in void-board mode**, from a population that can say Null
   and does not all play alike: `greedy, search-4, club, expert, jskat-new,
   xskat-blind, go-skat`. The two outside engines are there for style
   diversity and because they are licence-clean; `xskat-blind` rather than
   `xskat` so that no decision in the corpus was made with a card the seat
   could not see. 200k boards, `--threads=8`; the exporter already takes
   `--players=`. `check_data.py` before the night, as its README says.
3. **Null in the model. Answered 2026-09-20: share the network** -- see
   [&sect;2.8](#28-b2-step-3-answered-2026-09-20-one-net-and-it-pays-at-null)
   for the numbers and for the three measurement defects that had to be fixed
   before they meant anything. What follows is the reasoning as it stood
   before the run, kept because the result should be readable against the
   arguments rather than in place of them.

   Two things have to happen here and they are
   usually confused with each other: *getting Null data at all*, and
   *deciding which network learns from it*.

   **The data.** Two corpora have now been counted and both hold exactly
   zero Null decision points — 0 of 89,438 in v1 and 0 of 511,816 in v2 —
   because no seat in any population announces a Null. That is a bidding
   fact, not a training one (`NullAuditMain`: the player will bid 18, hold
   23 and announce Null, but `guaranteedValue(NULL)` is a flat 23 and it
   can never outbid anyone), and waiting for the bidder to be fixed would
   block this indefinitely. So the Nulls are **minted** instead:
   `ExportMain` learns `--contracts=<src>` and exports with
   `NullContractSource`, which prices a Null on about one board in eleven,
   so 200k boards give roughly 18,000 Null games and half a million Null
   decision points. Verify the count is non-zero this time; it is the
   one-line check that would have caught 2.1.

   The caveat, recorded before the corpus is built rather than after: a
   minted Null has no auction behind it, so its bidding block is empty.
   The model would learn where the cards lie in a Null *given no bidding
   evidence*. That is close to the app's own case — our seats only ever
   meet a Null that a person declared — but it is not the same thing, and
   it argues for keeping the minted records distinguishable rather than
   pretending they came from a normal game.

   **Which network — measure it, do not argue it.** This step used to say
   "shared network, not a separate model, because Null decision points will
   be about one in twenty and a separate head would starve". That reason
   expired with the paragraph above: minted data can be as plentiful as we
   like, so scarcity no longer decides anything. Train **both** from the
   same corpus, which costs minutes because the corpus is the expensive
   part, and read three numbers:

   - held-out Null accuracy, mixed net against Null-only net;
   - held-out **trump** accuracy of the mixed net against today's model —
     the number that says whether Null poisoned the 97% of positions the
     player actually meets;
   - the arena, as always, on `--contracts=null` boards and on the ordinary
     gates.

   **The tooling for all of this is `./tools/belief-null.sh`** (2026-09-19):
   mint, check, mix, train both nets, print the three numbers, run the gates,
   resumable step by step like `belief-v2.sh`. It takes `belief-data-v2` as
   its input and refuses to start without it. Two things were fixed to make
   its numbers readable and both are worth knowing about. `ExportMain` gained
   `--contracts=`, measured at 11.0% of boards pricing a Null, so 200k boards
   give roughly 22k games and 430k decision points. And `split_by_board` was
   drawing the held-out tenth from whichever boards were present, which made
   the split a property of the corpus rather than of the deal — so the mixed
   net would have been scored on Null deals it had trained on, and would have
   looked like a net that generalises unusually well. It is now a hash of the
   board id and the seed. A `val_accuracy` stored in a `model.json` written
   before that date was measured on a different tenth; recompute it with
   `eval_belief.py` rather than quoting it.

   That last point has a bigger half, learned the hard way on the first quick
   run (2026-09-19): it is not only the stored *number* that goes stale, it is
   the *model*. A model trained before the rule changed was held out of a
   different tenth, so scoring it on the new one grades it on its own training
   data — the two splits overlapped by 6%, and the shipped model read about
   five points too well, which looked exactly like "Null poisoned the trump
   positions". It had not: on the records genuinely held out of both, the two
   nets were level (62.7% against 62.2%). So the trump comparison is against a
   **control trained the same night**, on the same split, for the same epochs,
   differing only in the Null records — not against the shipped model. Every
   `model.json` now records `split_rule`, `split_seed` and `split_fraction`,
   and `eval_belief.py` warns when they do not match what it is scoring
   against.

   What the arguments are, so the result can be read against them. *For
   sharing:* most of the belief's work is contract-independent bookkeeping
   (voids, cards gone, the 10/10/2 split), which a shared net learns once
   from every record rather than twice from a slice, and the net is already
   shared across contracts that differ a great deal — a Grand has four
   trumps, a suit game eleven — and handles that well; one file also means
   one parity check and one version to get wrong, which matters in a
   pipeline whose failure mode is a model that is silently wrong.
   *Against:* several inputs change meaning on the contract bit.
   `trumps_out`, `trumps_mine` and `jacks_out` are meaningless in a Null and
   are still encoded, so a Null record asserts "no trumps are out", which is
   a false fact rather than an absent one — the same shape as the
   presence-bit defect of 2.1. The void classes move too, since a jack is
   trump in one game and a plain card in the other, and the discard is
   inverted. A shared net must spend capacity learning to ignore inputs
   rather than to use them.

   If the mixed net matches on trump and wins on Null, ship one file. If it
   costs anything on trump, ship two; the Null net can be smaller, since
   Null inference looks like the simpler problem.
4. Train. Same recipe, same interpreter warning.

**Step 1 measured, 2026-09-23** (`tools/belief-share.sh`, three seeds, 200
boards, the shipped belief and the uniform sampler on the same games, the
sampled worlds read off each player's own draw):

| tricks 1-3 | exact | placed, belief | placed, uniform |
| --- | --- | --- | --- |
| as declarer | 0.01% | 51.3% | 50.5% |
| as defender | 0.01% | **49.3%** | 42.4% |

Seeds agree to a tenth. Two things the plan had wrong, and one it did not
know.

*The gate below cannot be met by any honest sampler.* "Exact" -- all twenty
unseen cards in their true places -- is 0.01% at tricks 1-3 for the belief
and for uniform alike, and one decision in twenty-two sees even one such
world by trick five. The oracle sweep's lowest rung mixes the truth in at
5%, five hundred times that. So `belief-5` is not a yardstick the belief
can be measured against; it is a different object. The number that moves is
*placement*: the share of unseen cards a sampled world puts in the right
hand. The gate is restated on it.

*The belief is a defender's belief.* On defence it places 49.3% against
uniform's 42.4% -- seven points, on every seed and every contract (Grand is
the one it helps least, 46.8). As declarer it places **51.3% against
50.5%**: less than a point. The auction is the evidence the net reads, and
the declarer's auction is two opponents who mostly passed; the defenders'
auction is a declarer who bid. This is consistent with everything measured
before it -- the +25 at `belief-25` is a declaring number in an experiment
where the truth was mixed in for every seat, Phase D found the declarer's
worlds calibrated but the declarer's line the problem, and defence has
never been where the points were -- but it was never stated, and it sets
Phase P's ceiling: a generation that trains on more of the same evidence
will move the defender's column and not the declarer's.

*What would move the declarer's column* is not more of the same corpus.
The encoding already carries the declarer's own discard, every seat's
highest bid and every card played by every seat, so the thin margin is not
a missing field. Read by trick, the belief's declarer placement climbs
50.3 to 52.1 over tricks 1-3 and uniform's 50.0 to 51.0: the same slope,
one point apart. That is the filter doing the work in both -- a card that
did not follow suit is a void in both samplers -- and the net adding about
a point from the auction and nothing from the play. There are two possible
reasons and the run cannot separate them: the net does not learn what a
defender's chosen card says, or there is nothing to learn because the
corpus's defenders are search players, whose choice among equal cards is a
tiebreak and not a signal. Human defenders signal; that is the one thing
the ISS archive would settle and self-play cannot, and it is why that
permission is still worth asking for. Until then, a generation of Phase P
should be measured on this table before its night of gates, and expected
to move the right-hand column.

**2026-09-23/24: two hypotheses, one experiment, and the answer was
neither.** Philipp's first thought was the two-to-one ratio -- two records in
three are a defender's -- and `tools/belief-declarer.sh` was written to test
it (three nets from the shipped corpus at declarer weight 1, parity and
declarer-only; `train_belief.py --declarer-weight`, `eval_belief.py
--by-role`). Its first step runs before any training and answered the
question on its own: **the shipped model, held out by role, places the
declarer's unseen cards at 62.4% against a 51.1% baseline (+11.3)** and the
defenders' at 67.5 against 44.7 (+22.8). In play the same model read +0.8 and
+6.9. So the net knows more about the declarer's hands than ever reaches
the table, the ratio is not the cause, and the three-net run is moot. (The
run did not train anyway: `--quick` had written three-epoch nets into the
night's model directories and the resume check kept them. Fixed; the quick
nets now live under `-quick`.)

Where it goes, measured in the container with the net's own argmax read
at every decision beside the drawn worlds (`BeliefShareMain` now prints
both):

| tricks 1-3, net argmax on the unseen cards | as declarer | as defender |
| --- | --- | --- |
| corpus records, full memory (20k of shard 0) | 58.7 | 63.5 |
| the same with the bidding block zeroed | 56.8 | 63.2 |
| **in play, fixed contracts** (36 games) | **49.2** | 60.3 |
| in play, the real auction (32 games) | 53.3 | 62.4 |
| uniform | 50.7 | 45.7 |

*First: the fixed-contract instrument strips the auction.* A game started
by `restartWithContract` has no bids, so `bidding_present` is 0 at every
decision of every fixed-contract match, and the declarer's belief -- whose
evidence is mostly the auction -- reads at uniform there while the
defender's, fed by the contract and the declarer's identity, barely
notices. With the real auction the declarer's argmax is back at 53 and the
defender's at the corpus figure. Every fixed-contract number that seats a
belief player, `--split` included, was taken with the declarer's belief
switched off by the instrument; the pairings between two belief players
are still fair (both blind alike), but the belief's worth to the declarer
was never measured, and the app, which has an auction, is better than the
arena said. The fix is in the engine: record the auction's `BidEvent`s,
carry them in `FixedContract`, and have `restartWithContract` replay them
to every seat before the exchange. It changes every fixed-contract
baseline, so it is a re-baseline night after it lands.

*Second: the sequential sampler loses most of what the net knows.* As a
defender the net's argmax is right on 60% of the unseen cards and the
worlds it draws place 48.5% (uniform 42.4): two thirds of the margin gone
between the net and the deal. The mechanism is in `WorldSampler`: cards are
dealt one at a time in a *random* order, each to a place in proportion to
the belief, and once a hand is full every later card is forced -- so what
gets forced is whatever came last, not what the net was unsure of.
`WorldSampler.confidentFirst()` deals the surest cards first: +1.3 for the
defender and +0.5 for the declarer at tricks 1-3 in the same games,
registered as `belief-32-shipped-confident` for the gate. It is a first
step, not the answer; a sampler that draws from the per-card marginals
*subject to* the capacities (a Sinkhorn pass over the belief, or swap
moves after the draw) is where the other five points are, and it is the
B2 work that pays on both seats.

The residual after both -- corpus 56.8 without bids against 53.3 in the
auction -- is small enough to be the population (our own defenders play
the corpus's card, not always the corpus's choice) and is the part human
data would speak to. Not the first thing to chase.

**2026-09-24: the auction replayed (33a5ac9), the re-baseline
(`tools/rebaseline-auction.sh`), and what the paper says.** Every
fixed-contract log the plan reads was taken again on the corrected
instrument; the old ones are under `arena-logs/superseded-20260923-225652-
no-auction-*`. Three readings, six / three / three seeds, 200 boards:

| tricks 1-3, belief-32-shipped | placed% | uniform | net argmax% | confident-first% |
| --- | --- | --- | --- | --- |
| as declarer, auction stripped (09-23) | 51.3 | 50.5 | 49.2 | -- |
| **as declarer, auction replayed** | **52.1** | 50.5 | **55.7** | 52.4 |
| as defender, auction stripped (09-23) | 49.3 | 42.4 | 60.3 | -- |
| as defender, auction replayed | 49.7 | 42.4 | 61.1 | 50.6 |

The instrument is fixed: the net's argmax for the declarer rose from 49
to 56, the auction-mode figure. What reached the table is +0.8 placed
(51.3 to 52.1), and the split did not move -- **card play's share 2.24
[1.6, 2.8]** against the ladder's 2.27 [-0.1, 4.6] on the same six seeds,
discard 1.14 [0.3, 1.9], gap 3.37 [2.2, 4.6]; the self-match "we declare"
column moved +0.18 a seed, noise. The par column moved +0.35, also noise
(our defenders hear the auction now too). So finding A was real and worth
nothing in points *until finding B is fixed*: the declarer's belief now
knows six points more than uniform and the sampler delivers one and a
half. `belief-32-shipped-confident` against `belief-32-shipped`, fixed
contracts, exact pairing: +1.08 [-1.83, +3.98] over three seeds, two of
three better from declaring; not resolved, not shipped, and not the fix.

The fix is in the paper this belief descends from -- Solinas, Rebstock,
Buro, *Improving Search with Supervised Learning in Trick-Based Card
Games*, AAAI 2019 (arXiv 1903.09604) -- which never deals cards one at a
time. It generates *legal* states first (the whole information set when
it is small, otherwise a uniform sample of it without replacement) and
weights each by the product of the net's per-card marginals, p(s|h) ∝
∏ L(h)[c, loc(c, s)], then samples from that. Hand sizes are never
violated because every candidate is a real deal, so there is nothing to
force; the paper adds no further constraints and says suit-length ones
"could be added". Our `WorldSampler` is a faster approximation of that
estimator, and the approximation is where two thirds of the margin goes.
Three more things from the same paper: its true-state sampling ratio is
"uniformly larger for defender compared to soloist", for the two reasons
we found (the declarer knows the skat; the declarer's choice of game
leaks its hand), so a defender-tilted belief is structural; its bidding
features are each opponent's highest bid as a *type* (which game the bid
implies) and a *magnitude* bucketed to preserve the multiplier (18-24,
27-36, 40-48, 50-72, >72), because the multiplier predicts the jacks,
where ours are raw bids by seat; and it was trained on 20 million human
games, which is the ISS question from the other end.

**Next, B2 step 4: the paper's sampler.** Draw K uniform consistent
worlds, weight by the product of marginals, resample the N the search
uses; `WorldSampler` already draws uniform consistent worlds when every
weight is 1. Measure before building it into a player: the same
`BeliefShareMain` reading with K at 64, 256, 1024 beside sequential and
confident-first, in the container, then register the winner as
`belief-32-shipped-reweighted` and run the three gates. The cost is K
uniform draws a decision instead of N belief draws, which is cheaper per
draw; the budget is the same 32 worlds.

**2026-09-24, later: step 4 measured before it was built, and finding B
is withdrawn.** The probe (`ReweightProbe`, container, 72 fixed-contract
games over seeds 12 and 13, the shipped player's own decisions) reads
every sampler against the same truth at tricks 1-3, and adds the yardstick
that was missing on the 23rd: *the probability the net itself gives the
true place of each unseen card*, which is exactly what an ideal draw from
its marginals would place.

| tricks 1-3, placed% | as declarer | as defender |
| --- | --- | --- |
| uniform | 50.4 | 42.4 |
| sequential draw (the shipped sampler) | 52.5 | 49.6 |
| confident-first | 52.3 | 50.7 |
| paper's estimator: 1024 uniform worlds reweighted by ∏ marginals | 52.7 | 51.1 |
| the same target, sequential proposal corrected (SIR, K=1024) | 52.3 | 51.2 |
| **ideal draw from the net's marginals: mean p(true place)** | **52.6** | **50.8** |
| net argmax | 56.1 | 61.2 |

The sampler loses nothing for the declarer and about a point for the
defender; the paper's estimator, sampled properly (effective sample size
300-500 of 1024 with the corrected sequential proposal, 25 of 1024 from
uniform candidates for a defender, whose product is far more peaked),
lands within a point of the ideal too. The "two thirds of the margin
lost between the net and the deal" of the 23rd compared two different
quantities: argmax accuracy is the share of cards whose *most likely*
place is right, and a draw from a 60/40 belief lands right 52% of the
time, not 60%. The gap between 61 and 51 for the defender is the net's
uncertainty, not the sampler's loss, and no sampler recovers it --
sharpening the marginals would, at the price of the calibration the vote
depends on, and the sharpness exponent was swept for the 16-world player
and left at 1 (§5). Confident-first stays a registered variant
(+1 for the defender here, +1.08 unresolved in the pairing) and is not
shipped. Step 4 is closed without code, which is what the probe was for.

What is left for the declarer's belief is the net: its marginals place
+2.2 over uniform for the declarer and +8.4 for a defender, and that is
what reaches the table. The levers are the ones the paper and the
23rd's residual point at -- the bid as type and multiplier-bucketed
magnitude rather than a raw value, the defenders' choices as evidence,
and human games -- and each is a training-side change measured first
held out by role (`eval_belief.py --by-role`), then with
`tools/belief-share.sh`, whose declarer column now reads 52.1 with the
auction present and whose gate is unchanged.

**2026-09-24, step 5: the auction spelled out. Held out by role first,
and it pays.** `belief_data.bid_structure` derives, per relative seat,
whether the seat bid at all, the paper's five magnitude buckets, which
base values divide the bid (diamonds to grand, and the Null prices) and
the smallest suit multiplier it admits: fifty-one inputs, from the
`bids_by_seat` the corpus already holds (a bid over a hundred survives the
byte exactly; above 100 both sides read 100), computed after forgetting so
a forgotten auction stays forgotten. `tools/belief-bids.sh` trained the
shipped corpus three ways at the same split and twenty epochs:

| held out by role, argmax over uniform | as declarer | as defender | nll |
| --- | --- | --- | --- |
| control, the inputs as they are (init seed 1) | 62.4 (+11.3) | 67.5 (+22.8) | 0.6562 |
| control2, the same at init seed 2 -- the noise floor | 62.3 (+11.2) | 67.5 (+22.8) | 0.6564 |
| **the auction spelled out** | **63.3 (+12.2)** | 67.7 (+23.0) | 0.6524 |

Nine times the floor, on the seat that needed it. So the net could not
recover the divisibility from a scalar on its own, and the paper's feature
was worth what the paper said it was. Ported: `BeliefEncoding.bidStructure`
mirrors the Python bit for bit (checked on all 50,000 records of a shard
head, and by `ModelDirectory.checkParity` against the trainer's fixtures on
every load); a model that reads the block says so by its width
(`BID_STRUCTURE_SIZE`, 357), `BeliefWorldSource` derives it for such a
model and hands the plain vector to any other, the corpus stays at 306 and
every shard already written serves both. What is not yet known is the
points: `tools/belief-bids-gate.sh` seats the net as the candidate against
the shipped one -- belief-share for the placed% (the declarer's 52.1 is
the number to beat), then the fixed-contract pairing over three seeds.

*At the table (2026-09-24, `tools/belief-bids-gate.sh`):* the belief
moved, the points did not resolve.

| tricks 1-3, placed% | as declarer | as defender |
| --- | --- | --- |
| belief-32-shipped | 52.1 | 49.7 |
| **the auction spelled out, as belief-32-shipped-candidate** | **53.4** | **50.5** |
| uniform | 50.5 | 42.4 |

Plus 1.3 for the declarer and 0.8 for a defender, in the same games --
the largest move the declarer's column has made, and the first gate
passed with the defender's column rising rather than holding. The
pairing, fixed contracts, seeds 11-13, 200 boards: -0.26, +1.59, -0.98,
**pooled +0.12 [-3.17, +3.40]**; from declaring +0.7 a game on average,
from defending -0.2. Not resolved, so not shipped: the second gate asks
for resolved and positive, and a belief worth a point of placement is
worth a fraction of a game point, under this instrument's noise at 600
boards. The pairing continues on seeds 14-16 (`--seeds="14 15 16"
--pair-only`; the pooled line reads every seed on disk), and the ship
decision is made on six. CPU: the first layer is 512x357 instead of
512x306, a few percent of the net and nothing of the search.

*Six seeds (2026-09-24, evening):* seeds 14-16 came in at -2.08, -2.37
and -2.36, each resolved on its own, all of it from declaring (-3.2,
-3.0, -3.9 a game) with defending flat. **Pooled over six: -1.08 [-2.71,
+0.55]** by the seed-level t, which is wide because seed 12 sits at
+1.59; at the board level it is not ambiguous -- of the 312 boards in
1,049 that scored differently, the candidate lost 190 and won 122, a tilt
of nearly four standard deviations, and it declares fewer winners in
five seeds of six. **Not shipped.** A net that places the declarer's
unseen cards better, by the belief's own instrument, costs the declarer
about a point a game at the table.

Before naming a cause, one was ruled out. `CalibrationProbe` (container,
72 games, seeds 12-13) reads both nets on the same decisions of the same
games, by role and by what the card is, with a calibration table of
confidence against hit rate:

| declarer, tricks 1-3 | p(true) shipped | p(true) spelled out | argmax shipped | argmax spelled out |
| --- | --- | --- | --- | --- |
| jacks | 55.2 | 57.1 | 58.6 | 63.0 |
| other trumps | 50.3 | 51.0 | 52.7 | 55.1 |
| plain cards | 52.3 | 53.7 | 56.1 | 58.4 |

Better on every class, the jacks most, and the calibration is the same
shape for both (both a little sure of themselves in the 0.80-0.95 bin,
neither more than the other). So the loss is not in what the belief
knows and not in how sure it is; it is in what the search does with a
belief of that shape, and that is the declaring audit's question, not
the probe's. Next: `tools/declaring-audit.sh` (which now takes
`--candidate=`) on seeds 14-16 for the shipped player and for the
candidate, same boards, same contracts -- throws and gifts by vote class
and by trick, side by side. Whatever it says, the encoding stays: the
derivation is a width the model asks for, and a model that does not ask
gets the vector it always got.

*The audit named it (2026-09-24, late).* Both audits on seeds 14-16, the
same 520 boards: throws 38 in 31 games against 32 in 24, flat-zero
decisions 36.8% against 36.2%, the vote calibrated alike, the lost games
that were once won 13 against 12 -- nothing in the card play differed,
except one line: the gap "of it: same result, different value" went from
-0.09 to +0.70. The per-game files say what that is. **When the shipped
declarer loses it is Schneidered 34 times in 245; the candidate, 78 times
in 249** (par, on the same boards, 33 and 37). Mean declarer points in a
lost game 41.6 against 36.8. Forty-four extra Schneiders at fifty-odd
tournament points each is the whole 4.7 a game the candidate was short.
That is the ladder's signature exactly -- the ladder is the Schneider
defence, shipped 2026-09-22 for +1.30 -- and `BeliefPlayers` says why:
when `belief-32-shipped` became `ladderVariant`, `belief-32-shipped-
candidate` stayed `nullVariant`. Every net measured through the candidate
slot since then played the shipped card play of the 21st against the
shipped card play of the 22nd, and a net worth +1.3 of placement read
-1.08 [-2.71, +0.55] for it: the ladder's +1.3 taken away, and the net's
own worth, whatever it is, hidden inside the noise.

Fixed: the slot is built from `ladderVariant` like the player it stands
for, and `BeliefPlayersTwinTest` reads the ladder flag off both (and off
`belief-32-shipped-flat`, which must differ, so the reading is a
reading); it fails on the old line. The rule it enforces is the one
written above it in the file and broken anyway: a gate that swaps the
belief has to swap it underneath the player people actually get. The
six seeds are re-run on the corrected slot before any of the numbers
above are read again; the calibration probe and the belief-share
figures stand, since neither depends on the card play.

*On the corrected slot (2026-09-25):* belief-share reads as before,
declarer 53.6 against 52.1, defender 50.4 against 49.7. The pairing,
fixed contracts, six seeds: +0.01, +2.67, -0.38, -0.27, -0.70, -0.58,
**pooled +0.13 [-1.21, +1.46]**; from declaring +0.25 a game on average,
from defending -0.02. The ladder's +1.3 came back to the cent (-1.08
became +0.13) and what is left is the net's own worth, which is nothing
this instrument can see: a point and a half of placement for the
declarer and three quarters for a defender buys at most a quarter of a
game point, and resolving a quarter of a point takes twenty thousand
boards. **Step 5 closes here, not shipped.** Gate one passed, gate two
did not, and gate three was not run. The encoding stays in the tree
for the day a net is worth enough to need it; the model directory stays
on disk. What the week says, put plainly: the belief's marginal accuracy
is no longer the binding constraint on points at this table. The shipped
belief is worth +2.4 over uniform; the next point of accuracy is worth
a fraction of that, and the search converts it at the rate a point of
placement deserves. The plan's own ranking has said since the 9th where
the next lever is -- the auction -- and that thread ran its course
between the 13th and the 15th: the aggression dial lost to its own guards
and the adaptive bidder with the cushion shipped (arena/README.md). What
is left of the auction is the ceiling and the contract choice, which is
the SkatZero gap's other half; the "blocked on one decision" line in §4
was stale when this was written and is marked so now.

**Gates, all three, restated:**
- placement at tricks 1–3, as declarer, clears uniform by more than the
  two points the belief has today -- `tools/belief-share.sh`, and the
  defender's column must not fall; measured with the auction replayed
  into the fixed-contract game (33a5ac9): 52.1 against 50.5 today, with
  the net's argmax at 55.7 -- the gap between the two is the net's
  uncertainty, not the sampler's (2026-09-24, later);
- `belief-v2` − `belief` at fixed contracts, resolved and positive;
- `belief-v2` − `belief-25` closes rather than holds.

If gate one fails while held-out accuracy is fine, the fault is in how the
sampler uses the model, not in the model — look at `BeliefWorldSource` before
touching the network.

### Phase D — declaring (after B2, because it pays twice with a better belief)

**Started 2026-09-20. Two things found before building anything.**

*The obvious instrument is circular and cannot be used.* `SolverContractSource`
prices a board by asking whether it is makeable after `Discards.keepBestTen`,
and the player buries that same heuristic's complement — so at
`--contracts=solver` the heuristic keeps a makeable ten **100%** of the time by
construction. Measuring a discard there measures the assumption. The contract
has to come from somewhere that chose it without reference to a discard, which
means the auction.

*And the declarer was burying for the wrong contract.* The engine asks for the
discard before the announcement, rightly, since in a real game the discard is
part of deciding the contract. A fixed-contract match has no auction and lost
that knowledge with it: the declarer buried for whatever `price()` liked about
its own ten cards. `SkatExchangeContext.settledContract` fixes it, and it was
worth **+26.0 points of makeability at Null contracts and +13.0 at solver
contracts** — told the contract, the declarer keeps a makeable ten every time.
The 13 is the missing tenth `arena/README.md` calls "the ten percent the
discard costs". Every fixed-contract Null number here was measured through the
74%.

**The prize, measured honestly.** Contracts from the auction, 352 declared
boards, every one of the 66 discards enumerated against double-dummy defence:

| | |
| --- | --- |
| some discard makes it | 47.2% |
| the heuristic's discard makes it | 40.9% |
| cold either way | 52.8% |

So **+6.3 points, or 13.3% of the winnable boards** — one declared game in
eight that could be won is lost at the discard. That is the ceiling for this
item and it is a generous one: enumerating 66 discards with every hand face up
is not something a player can do, and the double-dummy defence it is scored
against is not the defence it will meet.

- Belief-weighted discard: for each of the 66 discards, the vote over sampled
  worlds, native solver, budgeted. Measured at **oracle contracts against
  par's 90.1%** — the tenth that is the discard is the target.

  **One obstacle, found 2026-09-20 and not yet settled.** "Belief-weighted"
  assumes the belief can be asked at the discard, and it cannot as things
  stand: `BeliefEncoding.Evidence` is built from a `DecisionContext`, which is
  a card-play position, and at the exchange there is no trick, no play history
  and no void. The first version should therefore sample **uniformly** over the
  twenty unseen cards, which is most of the value and needs nothing new; asking
  the belief at the discard is its own piece of work and should be justified by
  the uniform version paying first.

  **Measured 2026-09-21: does not resolve, and not worth its cost.** Three
  seeds, 400 auction boards each, pooled, against the shipped player:

  | worlds a candidate | game pts/game | wins as declarer |
  | --- | --- | --- |
  | 8 | -0.25 [-0.75, +0.24] | +0.30 pp |
  | 16 | +0.19 [-0.27, +0.64] | +1.56 pp |
  | 32 | +0.14 [-0.32, +0.60] | +1.09 pp |

  Read it as a small effect this instrument cannot see rather than as nothing.
  The declarer-win lift at 16 worlds, +1.6 points, is almost exactly what the
  pilot predicted -- a quarter of the 6.3-point ceiling -- and on 28% of games
  declared it is worth something like +0.3 game points, which is inside a
  half-width of 0.45. Resolving it would take about five nights. And it costs
  about ten seconds a discard on the workstation (ten candidates, sixteen
  worlds, 160 solves), so it would not ship to a phone even resolved.
  **Closed at the heuristic.** The world-count axis also refuses to rise: 16
  and 32 are level, so this is not a budget problem either.

  What the item did buy is the settled-contract fix below it, which was the
  larger defect and cost nothing to run.
- Then the line choice, measured as declaring-column distance to par at fixed
  contracts, currently 8.

  **Re-measure that 8 before working on it.** It was `belief` against `solver`
  at fixed contracts, where `solver` is a `TableObserver`, is told the contract
  by `observeFixedContract`, and discards for it -- while our player discarded
  for whatever it would have bid, on 25% of solver-priced boards a different
  pair, and kept a makeable ten 87% of the time against 100%. Part of the 8 was
  that defect, fixed 2026-09-20. It was also the `belief` of mid-August, not the
  player that ships now. `./tools/declaring-par.sh` asks the question again.

  **Measured 2026-09-21: about 5, and the 8 was never a clean number.**
  "From declaring" scores a declarer together with the two players defending
  against it, so two declarers only compare against the same defence. The 8
  subtracted belief's -5.19 (against search's defence, from belief vs search)
  from the solver's +2.91 (against expert's, from expert vs solver). A first
  re-measure made the opposite mistake -- our declarer against the solver's
  double-dummy defence, the solver's against ours -- and read 17.8; five
  different players of ours all scored exactly 38.01% there, because against
  perfect defence a declarer wins about when the ten it kept are cold, and they
  all bury the same pair. Nothing had regressed.

  Measured properly -- solver declaring and ours, both against our defence, on
  the same boards and fixed contracts from greedy's auction:

  | seed | solver declares | we declare | gap |
  | --- | --- | --- | --- |
  | 11 (178 boards) | -2.61 | -5.29 | 2.68 |
  | 12 (171) | -0.45 | -10.29 | 9.84 |
  | 13 (180) | -4.41 | -7.74 | 3.33 |

  **Mean 5.3, roughly [0.8, 9.8]** from the spread over three seeds (the
  per-board files hold only each match's total, so no paired interval). The
  gate of 4 is inside that interval: Phase D may be nearly done or may have
  most of it left, and three seeds cannot say which. Par here is an omniscient
  declarer -- it discards and plays seeing every hand -- so not all of the gap
  is reachable by an honest one.

  **Six seeds, 2026-09-21: 4.99 [2.0, 8.0]** (t interval). Seeds 14-16 added
  6.93, 2.99 and 4.16 on 173, 170 and 177 boards. The gap is real -- the
  interval stays clear of zero -- and the gate of 4 is still inside it, with
  the point estimate a point above. What this does not say is how much of the
  five an honest declarer could ever take: the solver's share includes seeing
  every hand at the discard and at every card, and the one piece of that
  already chased, the discard, came back at about +0.3 and unresolvable.

  **Split, 2026-09-21.** `solver-heuristic-discard` cheats at every card but
  buries our pair (identical on 53 of 53 boards checked), so the solver minus
  it is the discard's share and it minus us is card play's. Six seeds:

  | | share | 95% |
  | --- | --- | --- |
  | the discard | 0.93 | [0.1, 1.8] |
  | card play | **4.06** | [1.6, 6.5] |

  Card play is four fifths of the gap, on every seed, and its own interval
  clears zero. The discard is a point at most, which is why the search came
  back at +0.3: it was fishing a one-point pond. The line choice is where
  Phase D's remaining points are, and this is the instrument that measures it
  -- `--split`, the card-play share, its gate now 4.06 - 4 &asymp; nothing
  for the honest player to lose and up to 4 to find.

  **Where the card play loses it, 2026-09-22: not in won games. In lost
  ones.** Before proposing a fix, an instrument that could name the failure
  mode: `./tools/declaring-audit.sh` (`DeclaringAuditMain`). The per-board
  files in `arena-logs/par/` cannot say -- each row is one side's six-game
  total minus the other's, and the self-match that carries our declarer's
  column is zero on every row by construction -- so the audit replays the
  arena's own declarer-rotation games (same `Seeds.mix`, same fixed contract,
  same defenders; its ZERO CHECK reproduces every seed's "from declaring" to
  the cent), solves the true position before every card, and reads the
  player's own tally through its own session
  (`CardPlayObserver.voted`, a default no-op). Two units worth fixing first:
  "from declaring" is Seeger-Fabian points per game at the table, so the
  card-play share of 4.06 is about **five points of declarer win rate**,
  53.7% against 58.5%, roughly fifty games in 1,049.

  Six seeds, 1,049 declared boards (`arena-logs/par-audit/`; seeds 11-13
  were also run in the Cowork container and the files are byte-identical):

  | | s11 | s12 | s13 | s14 | s15 | s16 | six seeds |
  | --- | --- | --- | --- | --- | --- | --- | --- |
  | boards cold after the discard (won with every hand face up) | 81 | 66 | 70 | 75 | 58 | 74 | 424 |
  | of those, we won | 81 | 66 | 69 | 72 | 56 | 71 | **415 = 97.9%** |
  | card-play share on cold boards | -0.04 | +0.07 | +0.43 | +1.10 | +0.86 | +1.01 | **0.57 [0.1, 1.1]** |
  | boards not cold | 97 | 105 | 110 | 98 | 112 | 103 | 625 |
  | par wins one (always by a defender's mistake; par never throws) | 31 | 40 | 35 | 24 | 27 | 33 | **190 = 30.4%** |
  | we win one | 24 | 24 | 31 | 17 | 26 | 27 | **149 = 23.8%** |
  | card-play share on not-cold boards | +2.62 | +8.33 | +1.89 | +3.63 | +1.35 | +3.11 | **3.49 [0.9, 6.1]** |
  | of it, both lost but charged differently (we are Schneidered about twice as often) | +0.54 | +0.55 | +0.51 | +0.54 | +1.31 | +0.66 | 0.69 [0.4, 1.0] |
  | total, which is the gate's number | 2.58 | 8.40 | 2.33 | 4.72 | 2.21 | 4.11 | **4.06 [1.6, 6.5]** |
  | our throws (a won position played to a lost one) | 5 | 6 | 10 | 9 | 7 | 17 | 54 in 50 games |
  | our declarer decisions with **every card at zero votes** | 32% | 35% | 29% | 36% | 42% | 36% | **2,860 of 8,189 = 35%** |
  | of those, the position was in truth won | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
  | games flat from the opening lead on | 50/75 | 43/86 | -- | 54/85 | 52/90 | 53/78 | about six in ten |

  The total row reproduces `--split`'s 4.06 [1.6, 6.5] exactly, which is the
  zero check at the level of the whole instrument. Of it, the cold boards
  carry **0.57** -- nine games in 424, one in seven of the share -- and the
  not-cold boards **3.49**, six in seven. Seeds 14-16 lose two or three cold
  games each where 11-13 lost one in all, so "never" is wrong; "one board in
  fifty" is right, and those nine are the only places a *better answer to
  the present question* would help.

  So the honest declarer converts 98% of cold boards: the belief is doing
  its job, and the calibration table says so directly -- cards the vote put
  at 12% / 39% / 65% / 89% won in the true world 10% / 34% / 62% / 90% of the
  time. **Six sevenths of the gap is on boards that are lost against perfect
  defence, where the only way to win is a defender's mistake, and par draws
  one in 30% of those games against our 24%.** The mechanism is in the
  zero-votes row: on a third of its decisions the search answers "no card
  reaches 61 in any of the 32 worlds", and the card then falls to the
  tiebreak -- keep the points off the table, then the rule order -- which has
  no opinion about which line keeps a mistake possible, and none about
  Schneider either (28 of our 73 lost not-cold games on seed 11 were
  Schneidered, 14 of par's 66). Par, which asks for the most it can guarantee
  and only then for the result, keeps its tricks and its pressure; ours, told
  it has lost, plays the rest of the hand with no objective at all. Six games
  in ten of those are flat from the first card.

  **What that rules out.** More worlds (the tally is flat, not close), a
  better belief (calibrated), alpha-mu (strategy fusion is about overvaluing
  won lines; these are lost ones), and anything aimed at the discard. What it
  points at is one place in `chooseCard`: **what the declarer asks when the
  answer to "does this reach 61" is no everywhere.** The natural first
  experiment is a ladder -- when the top vote is zero, tally the same worlds
  at the next rung down (61 → 31, the Schneider line, then the best guaranteed
  points), and pick from that. It costs one extra tally on a third of the
  declarer's decisions and nothing on the rest. Its ceiling is the not-cold
  boards par wins and we lose, 70 in 1,049 against 29 the other way -- about
  3.5 points -- and an honest player will not draw every mistake par draws,
  so expect less; the Schneider row, 0.69 [0.4, 1.0], is the part it can
  claim almost by construction. Gate unchanged:
  `./tools/declaring-par.sh --split`, the card-play share, from 4.06.

  **The ladder, measured 2026-09-22: +1.30 [+0.47, +2.12], resolved, and it
  came out where the audit said it would.** `belief-32-shipped-ladder` is the
  shipped player with one addition (`SearchAiProvider.withLadder`): a
  declarer whose every card has zero votes on a trump contract asks the same
  worlds which card keeps it at 31 -- out of Schneider -- and among those
  tied, which holds 31 plus the usual margin; identical to the shipped player
  on every decision where some card still wins. `./tools/ladder.sh`, six
  seeds, 200 boards, three instruments:

  | | shipped | ladder |
  | --- | --- | --- |
  | ladder - shipped, fixed contracts, exact pairing, six seeds | | **+1.30** [+0.47, +2.12] game pts/game; +0.41 / +1.67 / +0.84 / +1.82 / +2.41 / +0.63, four resolved alone, none negative; win rate up on four seeds, level on two; 0 rule violations |
  | `--split`, card play's share of the gap to par | 4.06 [1.6, 6.5] | **2.27** [-0.1, 4.6] |
  | `--split`, the gap to par | 4.99 [2.0, 8.0] | 3.21 [0.7, 5.8] |
  | audit: cold boards won | 415 of 424, share 0.57 | 415 of 424, share 0.57 -- untouched, as the construction says |
  | audit: not-cold boards, share | 3.47 | **1.71** |
  | of it, both lost but charged differently (the Schneider row) | 0.68 | **0.00** |
  | audit: not-cold boards won | 149 = 23.8% (par 30.4%) | **164 = 26.2%** |
  | only par wins / only we win | 70 / 29 | 66 / 40 |
  | gifts drawn in not-cold games | 25.4% | 27.8% |
  | our throws | 54 in 50 games | 54 in 50 games -- none added |
  | flat-zero games won | 25 of 492 | 40 of 492 |

  Read against the expectation written into the script before it ran: the
  Schneider row was worth about 0.7 and it went to zero exactly; the gift
  row was worth up to 3.5 and the ladder took about a third of it, fifteen
  more games won on the boards where only a mistake can win. The ceiling
  did not move -- the nine cold games are still lost and par still never
  throws -- and nothing regressed: the cold rows are the same numbers to
  the game, and the throw count is the same 54. The zero check passed on
  all twelve lines, so the three instruments were reading the same games.

  What is left on this item is 1.71 on the not-cold boards, and it is a
  different question from the one the ladder answered: the ladder gives a
  lost declarer an objective, but the objective is still a double-dummy
  threshold, and a defender's mistake is drawn by keeping *chances* alive
  rather than by holding a guaranteed 31. Par's line -- the most it can
  guarantee, then points -- does that as a by-product. A second rung at 46,
  or "the card that keeps 61 reachable in the most worlds *if one defender
  card were misplayed*", are the two shapes; neither is cheap to gate, and
  the item ships as it stands first.

  **Gate passed.** Resolved positive at fixed contracts, and the declaring
  column moved by 1.78 [0.4, 3.2] on the split. What ships is the switch
  turned on in `Opponents` for every level -- the ladder cannot fire on a
  defender or in the auction, so the field gates
  (`tools/overnight-arena.sh`) are a regression check, not the decision.

  **Shipped 4ae37c9, and the field gates caught that it had not shipped.**
  `--redo=belief-32-shipped` moved every shipped row the right way (vs
  adaptive-margin +0.44 to +0.82, vs xskat +0.61 to +0.97, vs jskat-new
  +11.8 to +12.8, go-skat level, and vs `solver` at oracle contracts **-6.56
  to -3.50 on all three seeds** -- predicted not to move, and wrong: a
  makeable board is lost from our seat against perfect defence all the time,
  and not being Schneidered there is worth three points). `--redo=app-`
  then read `app-beginner-on` against `-off` **byte for byte identical**
  to the pre-ladder night. Beginner samples uniform worlds, so the belief
  retrain of 2026-09-20 cannot touch it, and a switch that was really on
  would have to. It was not: `withBiddingBudget` and `withWorldThreads`
  built the new player from a constructor that resets the card-play
  settings, and `Opponents.seat` applies both after `.withLadder()`. The
  club and expert rows had moved -- because of the retrained belief. Fixed
  in c29d1c2 with `SettingsChainTest`, which builds the app's own chain and
  reads it back and failed on both counts before the fix; and while there,
  a rung at 61 for a personality that aims above it (beginner 77, club
  65), whose vote is flat at its target and says nothing about whether the
  game can still be won. The reference player aims at 61 and is unchanged
  to the board.

  The app rows, re-measured with the ladder in them (2026-09-23):
  beginner on/off +2.93 to **+3.28** [+0.6, +6.0], the row that had to
  move; club on/off +2.39 to +2.70; beginner-club -5.18 to -3.82, club-expert
  -4.45 to -4.27, expert-analyst -3.15 to -3.21 -- the levels stay three to
  four apart and nothing went down. The `belief-32-shipped` rows of 09-22
  were valid all along: that contestant applies the ladder last.

**Gate:** each change resolved positive at fixed contracts; the declaring column
moves.

### Phase A — the auction *(now before Phase D)*

1. **Count first, fix second.** `./gradlew :arena:nullAudit
   --args="--player=belief-32 --boards=2000 --threads=8"` asks every seat how
   high it will go and what it would announce, settles the auction between the
   three, and reports how often Null is intended, how often it survives, what
   beat it, and — as an explicit upper bound — how many would have survived at
   35, 46 or 59. Add `--oracle` on a few hundred boards for the rate a bidder
   could approach.

   **It answers two different questions and they lead different ways.** If Null
   is intended at roughly the oracle's 3% and never survives, the 23-point cap
   is the whole story and step 2 is arithmetic. If almost nothing intends Null
   at all, the ceiling is innocent and the fault is upstream — in
   `HandEvaluator.makeChance` or in which contracts `SearchAiProvider.candidates`
   offers — and step 2 would have been the wrong fix.
2. ~~**The Null variants.**~~ **Answered and dropped, 2026-09-09.** The audit
   ran: Null is intended on 13 of 6,000 seats and **9 of those already win the
   auction**. Lifting the ceiling to 59 would rescue the four that did not —
   plus 0.2 percentage points against a gap of 3.9. The ceiling was innocent.
   Full numbers in [`../arena/README.md`](../arena/README.md).

   **The gap is that Null is almost never intended**: 0.45% of boards declared
   against the oracle's 4.34%. Every hand is offered Null by `candidates()`, so
   the loss is in `declaringIsWorth(23, makeChance(NULL))` losing to the same
   for two trump games. Two candidate causes with different fixes, and the
   decomposition that separates them is the next measurement: on the boards
   where the oracle says Null, what did that seat's ceiling, intent and measured
   Null chance look like? Some gap is legitimate — the oracle has the actual
   layout and the bidder samples, and Null safety is unusually sensitive to
   which low cards sit where — but not a factor of ten.

   Second thread, one call further down the same path: the model declares Null
   on 0.45% of boards and Phase R's real matches on none of ~1,800. Something
   after winning the auction loses the rest, and the skat pick-up is the
   suspect.

   **Both are now instrumented.** `--explain=null` dumps the boards the oracle
   calls Null, one line per seat, with each seat's Null chance beside the chance
   and guaranteed value of the trump game it preferred — a high Null chance
   beside a preferred rival means the value comparison loses, a low one means
   `makeChance` is the harsh part. And the audit now follows the skat for any
   Null intent that wins, reporting what it announced once it had seen the two
   cards. Run it over the same 400 boards:

   ```
   ./gradlew :arena:nullAudit --args="--player=belief-32 --boards=400 --threads=8 --explain=null"
   ```

   **Both ran, 2026-09-09, and the thread has moved.** More worlds changes
   nothing (mean P(null) 0.04 → 0.06, largest 0.33 → 0.34) and the routing is
   correct — `NullSolver`, not the points solver. And the "ten times too rare"
   framing was partly wrong: those boards are *selected* by the oracle for
   having a favourable layout, so a low mean sampled chance on them is what
   selection produces. The oracle's 4.34% needs hindsight and is not a target.

   What is left is sharper. Losing a Null costs 46 and winning gains 23, so the
   threshold is about **0.67**, and the largest Null chance on any seat on any
   of those boards is **0.34** — half of it. Null is unreachable by arithmetic.
   On the same boards trump games score up to 0.81, because **double-dummy
   defence punishes Null far harder than a trump game**: beating a Null needs
   one forcing line and a defender who sees everything always finds it.

   **One correction to the numbers above, 2026-09-09.** The audit's 0.45% is an
   **upper bound**, not an estimate. Its auction model asks every seat as though
   it opened, and an opening seat carries a Ramsch discount (`ramschRisk` is
   zero once `currentBid > 0`) that a holding seat does not — correctly, since a
   Ramsch needs all three to pass. So marginal hands look biddable to the model
   that would not be to the real auction, which is why the arena declared no
   Null at all across 51 reports while the model said 0.45%. The bidder is
   right; the model of it was wrong, and the report now prints the caveat. None
   of the conclusions move: the ceiling was cleared against these same inflated
   ceilings, and the arithmetic finding is per seat and never touches the
   auction model.

   **The question is now "are we right to decline", and the arena answers it.**
   The contract table prints declared *and won*, so one oracle-contract run says
   whether we make the Nulls we are handed. Make most of them and the repair is
   a contract-specific correction — Null judged against the defence it will
   actually meet, or a threshold that knows double-dummy treats the two
   contracts differently. Lose most of them and the bidder is right and this
   thread closes.

   **Read the older advice beside a second run at `--bidding-worlds=32`.** The bidder decides
   Null on `clamp(worlds/3, 2, 6)` sampled worlds — six of them for `belief-32`
   — so its Null chance is quantised in sixths, and a Null has to survive nearly
   every world to look sound. If the chance rises sharply with more worlds, the
   harshness is sampling noise and the fix is worlds rather than judgement; if
   it does not, the evaluator genuinely dislikes those hands and that is a
   different repair.
   **CLOSED, 2026-09-09, on the oracle-contract run.** `belief-32 - xskat`,
   300 boards, seed 11, contracts from the double-dummy oracle. Pooling both
   players, because the question is about the contract and not about us:
   non-Null contracts are made **463 of 510 = 90.8%**, Null **13 of 20 = 65.0%**.
   Break-even is 46/69 = **0.667**, so declining is right by a hair and the
   interval ([0.43, 0.82]) says nothing.

   More boards do not help: at p = 0.65 the interval still straddles 0.667 at
   n = 90 and at n = 160, and reaching n = 90 costs about 2,400 oracle boards.
   And the stakes are below the noise floor -- Null is the oracle's best contract
   on 3.8% of boards, so capturing every one perfectly is worth between **-0.17
   and +0.09 game points per game**, against a match margin of +2.565 whose own
   interval is +/-2.10. **Two orders of magnitude under our measurement noise.**

   The mechanism, corrected: nobody plays double-dummy defence in that match, so
   the Null gap is about the *declarer* -- a cold Null has one line and must be
   found blind, a cold trump game survives imprecision. Full entry in
   [`../arena/README.md`](../arena/README.md).

   **What the same run says instead:** at identical contracts we make 93.96% to
   XSkat's 85.66% and win by +2.565, resolved. Our card play is ahead; our
   auction is not. That makes item 3 below the top of the list, not a follow-up.

   **PHASE A ANSWERED, AND IN THE NEGATIVE, 2026-09-09.** Item 3 below assumed
   a boldness dividend. There is none. Measured over 900 hands at 24 bidding
   worlds, each seat's intended contract played out whether or not it would
   have been declared, declaring more costs points monotonically: 4.65 points
   a hand at 21.6% of hands declared, 4.22 at 24.8%, 4.00 at 26.7%, 3.80 at
   30.9%, 3.11 at 42.6%. Today's cut pays 4.55. Reaching XSkat's share would
   cost the better part of a point a hand.

   Three things follow, all recorded in [`../arena/README.md`](../arena/README.md):

   - The estimator **is** systematically pessimistic, as `HandEvaluator` always
     claimed -- but only visibly at 24 worlds. At 6 the top of the range is
     saturated (41% of hands pinned to 0.000 or 1.000) and the bias hides.
   - Correcting it still would not move the auction: the error is +0.18 to
     +0.22 around predicted 0.35-0.45 and about zero above 0.85, and the cut is
     at the top. **The estimator is wrong where the decision is not.**
   - Raising the bidding world cap is not worth it. 4x the worlds for a cut
     difference worth 0.11 points a hand decided by nine held-out hands.

   **So the 2.5 points are elsewhere.** Our card play beats XSkat's at identical
   contracts by +2.565 and the full auction is level. It is now measured not to
   be in how often we declare, which leaves *which contract we choose* and *how
   high we bid*. `overbid (lost)` is 0.00% in every report we have; for a bidder
   that never overbids that is a symptom rather than a virtue, and it is the
   next thing to look at.

3. **The declining threshold.** We declare 24–26% against XSkat's 33% and win
   85% against its 77%. Sweep the threshold and measure; the aggression dials
   already exist and their sweep is redone on the Null-capable bidder.

**Gate:** auction-mode result against `jskat-new`, `xskat` and `go-skat` in
void-board mode, all resolved positive; and `belief-32` − `xskat` in the full
game resolved positive, which it is not today.

~~Null declared at roughly the oracle's rate.~~ **Dropped 2026-09-09**, and it
was never a gate worth passing: the oracle's rate needs hindsight, and the
oracle-contract run showed the whole contract is worth between -0.17 and +0.09
game points per game. A gate is a thing a build can fail on, and this one could
only ever have been failed on noise.

### Phase P — population self-play, the loop (repeat until it stops paying)

Generation *n*: population = the fixed outsiders (`greedy, jskat-new,
xskat-blind, go-skat`) + the best of generations *n−1* and *n−2* → corpus →
train → `belief-n`.

**Gate per generation:** beats generation *n−1* at fixed contracts, resolved;
non-negative against every outsider in void-board auction mode. Two consecutive
failures end the loop. The outsiders never leave the population — a model
trained only on its own lineage is confidently wrong about everyone else, and
the humans who buy the app are everyone else.

### Phase M — the Ramsch, separately

Self-play only, its own arena mode (`--ramsch-only`), its own policy under
`RamschPolicy`, measured against `greedy`'s Ramsch and its own previous version.
Never in a strength claim; always its own column. The Schieben fields in the
encoding are masked in every non-Ramsch corpus.

### Phase C — calibration, once

When Phase P has produced something worth calibrating: one session on ISS
against the Muppets through go-skat's `client_iss.go`, and the ISS archive as a
contract oracle offline if permission ever arrives. Days of wall clock for one
interval, no pairing, no common random numbers — a number to write down, not a
loop to run.

### Phase S — shipping the effort

The reference player runs at whatever worlds the workstation affords (64, 128 —
measure where it stops paying, the curve is not flat yet). The phone runs at 32.
The belief already ships as plain arrays; the gap between the two is a stated
number in the README, not a surprise.

## 5. What not to do

- Do not retrain on `belief-data/` as it is. Every record in it was made by a
  bidder that could not say Null.
- Do not quote any auction-mode number from before 09a81fa.
- Do not put ISS on the critical path. Nothing here needs it.
- Do not revisit αµ, the sharpness exponent, or defence — all three are
  measured and closed.
- Do not compare against kermit locally; there is no such thing.
- Do not let a Ramsch board into a cross-engine or calibration measurement.

## 6. Order and cost

R (2 nights) → V (1 day) → **A (2 nights)** → B2 (1 week: a day of diagnosis, a
night of export, a night of training, two nights of gates) → D (1 week) → P
(a generation a week, as long as it pays) → M in parallel with P → C once →
S last. **A moved ahead of B2 as well as D, 2026-09-09**: the oracle-contract
run put our card play clearly ahead of XSkat's (93.96% to 85.66% at identical
contracts) and left the auction as the only place we are behind, so two nights
of threshold sweep now outrank a week of belief work. V stays in front of A
because A is measured in auction mode, and auction mode is not yet honest
against outsiders -- see Phase V. Nights are the arena's: `--threads=4` with ML players, 8 without; XSkat
costs nothing, go-skat about 8 games a second.

## 7. Done means

- `belief-vN` − `jskat-ml-pro` at oracle contracts resolved **positive** (from
  +0.19, not resolved).
- Declaring column within **4 game points of par** at fixed contracts (from 8).
- Non-negative, resolved, against `xskat`, `go-skat` and `jskat-new` in
  void-board auction mode.
- Null decision points present in the corpus at all, which they were not before
  09a81fa. ~~Null declared at roughly the oracle's rate.~~ Dropped 2026-09-09:
  measured at break-even and two orders of magnitude below the noise floor. A
  bidder that never announces Null may still be a product question; it is not a
  strength question.
- The true-world share printed in every training log, so 2.1 cannot happen
  again without being seen.
