# The rules we play

The canon. Everything in the engine, the arena and the training data is settled
against this document; where code and this file disagree, the code is wrong.
Set by Philipp on 2026-08-17 — see the appendix for the wording it was given in.
Some of it may later become configurable, but the defaults are these and the
defaults are what the AI is trained and measured on.

## 1. Foundation and settlement

- The **Internationale Skatordnung (ISkO)** applies, with the exceptions below.
- **No Bock rounds.** No round ever counts double.
- **Classic settlement by game value.** No Seeger-Fabian, so no flat ±50 per
  game and no bonus for the defenders. Only the declarer's column moves; the
  single exception is Ramsch, which has no declarer.
- **A lost game counts double**, as on any paper Skatliste: won `+value`, lost
  `−2 × value`. This is already what `SkatRules.score` does.
- **No Spitze.** Winning the last trick with the jack of clubs is worth nothing
  extra.

## 2. Kontra and Re

- **Kontra** doubles the game value; **Re** doubles it again. Nothing after
  that — no Sup, no Hirsch, no Bock.
- **When it may be said (the late variant):** a player must announce before
  playing *their own* first card into the first trick. A defender sitting third
  may therefore watch two cards fall and still say Kontra; forehand must speak
  before leading.
- **Never in a Ramsch.**

## 3. Ramsch (Schieberamsch)

A Ramsch is played whenever all three players pass. There is no re-deal — ever.

- **Schieben.** The skat is picked up in turn and two cards are pushed on.
  Forehand takes the two skat cards, pushes two to middlehand; middlehand takes
  those two and pushes two to rearhand; rearhand takes those two and pushes two,
  and *those* two are the skat the game is played with. Every player therefore
  holds twelve cards once and ten cards when play starts.
- **Jacks may never be pushed** — not into the skat and not to the next player.
- **Whose skat is it?** Nobody's, until the end: the skat is added, unseen, to
  the **loser** — the player with the most card points. The loser's total is
  written as minus points (65 card points = −65).
- **Jungfrau.** A player who takes no trick at all doubles the loser's minus
  points.
- **Durchmarsch.** A player who takes all ten tricks has broken the Ramsch and
  *wins* it: **+120**, and nobody else scores. Two jungfrauen are by definition
  a durchmarsch of the third player.

## 4. Rulings the canon does not state

These are the edges the wording above leaves open. They are decided here so
that engine, solver, tests and score sheet cannot quietly disagree.

1. **Ramsch is played in Grand order.** The four jacks are the only trumps;
   every other suit ranks A 10 K Q 9 8 7. Following suit is the usual duty.
2. **Forehand leads the first trick** of a Ramsch, as in any other game.
3. **The loser is found on tricks alone.** The skat is added *after* the loser
   is known, which is what "unbesehen" means: the two cards cannot change who
   loses, only how much.
4. **Ties.** Among the players tied for most card points, the one who won the
   later trick loses. This keeps exactly one loser, which the skat needs.
5. **Doubling covers the skat.** A jungfrau doubles the loser's whole total,
   trick points plus the skat, not the tricks alone.
6. **A durchmarsch is worth 120 flat.** The skat is not added to it and the
   other two players score zero.
7. **One Kontra per game.** Either defender may say it; the second defender
   cannot say it again. Only the declarer may answer Re.
8. **Re follows immediately.** It must be said before the next card is played.
   When the Kontra came from a defender playing after the declarer, that means
   Re can arrive after the declarer has already played a card — that is allowed,
   because the declarer had no earlier moment to answer.
9. **Order of the multipliers.** The Kontra/Re factor multiplies the value of
   the game *as played*, announcements included, and the doubling of a loss is
   applied afterwards. A lost Kontra game is `−2 × 2 × value`.
10. **Kontra applies to Null and to overbid games** exactly the same way: on the
    fixed Null value, and on the charged multiple an overbid declarer pays.
11. **A Ramsch is a played deal.** It occupies a row on the list and the deal
    rotates after it.

## 5. Worked examples

Check the arithmetic against these; the tests do.

| Situation | Value |
|---|---|
| Clubs, skat picked up, without two, game 3 → 12 × 3 | won **+36**, lost **−72** |
| the same with Kontra | won **+72**, lost **−144** |
| the same with Kontra and Re | won **+144**, lost **−288** |
| bid 33, plays Diamonds with one (game 2 = 18), overbid → charged 36 | **−72** |
| Ramsch, tricks 0 / 51 / 55, skat 14 | third player **−69** |
| the same, with the first player jungfrau | third player **−138** |
| Ramsch, tricks 40 / 40 / 26, skat 14, second player won the later trick | second player **−54** |
| durchmarsch | **+120** |

## 6. Where the code stands

The rules above are implemented in `core` and honoured by the arena. What is
listed as missing is missing on purpose, not by oversight.

**Done.**

- `Contract.RAMSCH`, in Grand order, last in the enum so stored score sheets
  still read. `Contract.DECLARED_GAMES` is the list of games somebody can
  actually announce, and is what the old `Contract.values()` loops now use.
- `GameDefinition.declarer` is **null in a Ramsch** and non-null everywhere
  else, enforced in the constructor. `hasDeclarer()` is the guard.
- `GameEngine` runs the Schieberamsch when all three pass — three legs,
  forehand to middlehand to rearhand, no jack ever pushed, rearhand's push
  becoming the skat. Resumable, so a person's leg can stop it at `Phase.PUSH`.
  The old 64-deal retry loop and its exception are gone: no deal is ever thrown
  away now.
- `SkatRules.scoreRamsch` settles it, including the tie-break, the jungfrau and
  the durchmarsch. `SkatRules.score` refuses a Ramsch rather than guessing.
- Kontra and Re: asked of each seat in the one moment the canon allows, applied
  as a factor on the value, announced to every seat as an event, and carried on
  the result and the wire.
- `ScoreSheet` rows carry the scored seat, the jungfrau and the durchmarsch.
- Every player that can be seated copes: `SearchAiProvider`, the solver
  reference and the JSkat adapter all hand a Ramsch to `RamschPolicy`.
- The arena counts a Ramsch as the played deal it is; the old "passed in"
  column is now the **Ramsch rate**.

- **The Ramsch UI.** The Schieben is on screen: the two cards fly from seat to
  seat exactly as the deal flies, one leg at a time, and the person is asked for
  their own two in the same panel and with the same gesture as a discard — jacks
  refused, tap a card in the panel to take it back. The result screen no longer
  says "declarer": the two counters become the seat the Ramsch fell on and
  everybody else, the skat lands on the loser rather than on the winner, and
  Jungfrau and Durchmarsch are named. `GameEngine.SchiebenObserver` is what makes
  the pacing possible — the engine runs every automated leg inside one call, and
  the observer is how the app gets them back as a sequence it can play out.
- **Ramsch as a chosen game.** Contract Challenges can ask for one, which is the
  only way to play a Ramsch on purpose — the Schieben is run automatically there
  and only watched. See [`contract-challenges.md`](contract-challenges.md).

**Not done, deliberately.**

- **No Kontra UI.** The engine offers `mayAnnounceContra` / `announceContra` and
  the AI seats use them; nothing on screen does yet.
- **No Ramsch search.** `RamschPolicy` is the beginner's heuristic — shed the
  dear cards, win as cheaply as you must — and it is a placeholder. Every seat
  plays the same one, which means a Ramsch cannot currently tell two contestants
  apart and dilutes any match that contains one.
- **No Ramsch in the belief corpus.** Encoding v1 has no way to say "no
  declarer", so `BeliefExporter` skips those decisions rather than mislabel them.

## 7. What this changes for the AI

**The bid threshold is no longer a constant** — and the first version of this
section got the size of that wrong, which is worth leaving in rather than
quietly editing. It said the break-even make chance falls from two thirds to
about a fifth. Implemented and measured, it does not. What it does is stop being
one number at all.

Under classic settlement declaring is worth `V·p − 2V·(1−p)`, and passing is
worth `P(Ramsch) · R` where `R` is what a Ramsch costs *this hand*. The claim of
a fifth came from treating `P(Ramsch)` as one. It is not: passing usually means
somebody else takes the game, and a defender under classic settlement scores
**zero**. So the alternative to declaring is only expensive when a Ramsch is
actually close, and the rule now says so explicitly — nothing bid yet and both
opponents still in: `q²`; one opponent already out: `q`; both out: certainty; and
the moment any value stands on the table, zero, because then a pass makes this
seat a defender.

`q`, how often a single seat passes, is the one number in the rule that is a
property of the table rather than of the cards. It has a fixed point — a table
that bids more passes less, which makes passing safer, which makes it bid less —
and self-play lands on **q ≈ 0.49**, an 11.7% Ramsch rate over 60 boards, so the
0.5 the code carries is a measurement and not a guess.

`R` is measured per hand by playing the Ramsch out (`RamschEvaluator`), and the
spread is the whole reason a constant would not do: over 400 random hands the
mean is **−26.6**, the median **−14.7**, the tenth percentile **−67.7**, the
ninetieth **0.0**, and the worst hand seen **−206.7**. A tenth of hands cannot
lose a Ramsch at all; a tenth would rather do almost anything else.

Put together, at neutral temperament and 18 at stake:

| Situation | hand | break-even make chance |
|---|---|---|
| nobody has bid | safe in a Ramsch (`R = 0`) | **0.67** — the old two thirds, unchanged |
| nobody has bid | median (`R = −15`) | **0.60** |
| nobody has bid | dangerous (`R = −68`) | **0.35** |
| both opponents out | median | **0.39** |
| both opponents out | dangerous | **declare regardless** |

Which is how the game is actually played: the threshold sits near two thirds
early in the auction and collapses late, and it collapses hardest for exactly
the hands that would lose the Ramsch. `Personality.requiredMakeChance` is gone;
aggression now moves `lossWeight`, how heavily the doubled loss weighs, which is
the one thing a person's temperament really moves.

**Ramsch is a three-player game.** The double-dummy solver, the PIMC search and
`GameDefinition` all assume two against one; none of them apply. A plain
heuristic is fine to start with — a Ramsch is mostly "take no trick that has
points in it" — but it must be a first-class player in the arena from the
beginning, because the auction's value now depends on how well the Ramsch is
played.

**Belief encoding v2** needs: a Ramsch slot in the contract field, a declarer
field that can say "none", the Kontra/Re flags, and — this one is new evidence
rather than a new field — the **schieben record**. In a Ramsch every player
knows two cards they pushed to a named opponent and two cards they received from
a named opponent. Four of the twenty hidden cards are located exactly, for every
seat, before the first card falls. That is a much stronger prior than anything
the auction provides, and it is the reason a Ramsch belief head is worth having
rather than a Ramsch heuristic that guesses.

**The arena metric.** Done: the paired per-board difference is now taken in
classic game points, and tournament points survive as a second row so that the
measurements in `ai-training-ground.md` stay readable rather than orphaned.

It changes verdicts, which is the point. In one 24-board match the greedy
baseline scores **−12.8 tournament points** and **−16.5 game points** per game —
the same play, but Seeger-Fabian pays it 40 every time somebody else's game goes
down, so it flattered a player that declares 43% of deals and wins 55% of them.
Every number in `ai-training-ground.md` was taken in the old currency and is now
a record of a different question.

## Appendix: the canon as given

> 1. Grundlage & Abrechnung
>
> * Es gilt die Internationale Skatordnung (ISkO), mit den unten stehenden Ausnahmen.
> * Keine Bockrunden.
> * Abrechnung: Klassisch nach Spielwert (ohne Seeger-Fabian-System; es gibt also keine pauschalen +50/-50 Punkte). Verlorene Spiele zählen (wie klassisch üblich) die doppelten Minuspunkte.
> * Keine "Spitze": Ein gewonnener letzter Stich mit dem Kreuz-Buben bringt keine Extrapunkte.
>
> 2. Kontra und Re
>
> * Erlaubt sind Kontra und Re. Danach ist Schluss (kein Sup/Hirsch).
> * Zeitpunkt der Ansage (die späte Variante): Die Ansage muss erfolgen, bevor der Spieler, der sie tätigt, seine eigene erste Karte in den ersten Stich legt. (Man darf sich also anschauen, was der Spieler vor einem anspielt, solange man selbst noch keine Karte gespielt hat).
> * Ausnahme: Bei einem Ramsch-Spiel darf kein Kontra/Re angesagt werden.
>
> 3. Ramsch (Schieberamsch)
>
> * Ein Ramsch wird gespielt, wenn alle drei Spieler beim Reizen passen.
> * Schieben: Der Skat wird der Reihe nach aufgenommen und es werden zwei Karten weitergedrückt.
> * Buben-Regel: Buben dürfen nicht in den Skat gedrückt/geschoben werden!
> * Wem gehört der Skat? Der Skat wird am Ende unbesehen dem Verlierer (dem Spieler mit den meisten Augen) zugerechnet. Die Augen des Verlierers werden als Minuspunkte notiert (z. B. 65 Augen = 65 Minuspunkte).
> * Jungfrau: Macht ein Spieler im Ramsch keinen einzigen Stich, verdoppeln sich die Minuspunkte des Verlierers.
> * Durchmarsch: Macht ein Spieler alle 10 Stiche, ist das normale Ramsch-Spiel gebrochen. Der Spieler gewinnt das Spiel als "Durchmarsch" (Wertung: 120 Pluspunkte). (Hinweis: Wenn zwei Spieler keinen Stich machen = 2 Jungfrauen, ist das automatisch ein Durchmarsch des dritten Spielers).
