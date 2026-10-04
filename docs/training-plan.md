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
