#!/usr/bin/env bash
#
# Overnight measurement run for the SkatKlar arena, for Git Bash on Windows.
#
#   ./tools/overnight-arena.sh              the full run: three seeds, a few hours
#   ./tools/overnight-arena.sh --quick      one seed, small boards: a sanity check
#   ./tools/overnight-arena.sh --seeds="21 22"
#   ./tools/overnight-arena.sh --scale=0.5  half the boards everywhere
#   ./tools/overnight-arena.sh --threads=8  fewer, to keep the machine usable
#   ./tools/overnight-arena.sh --redo=expert-vs-analyst
#                                           re-measure just the matches whose
#                                           name contains that, keeping the rest
#
# Everything lands in arena-logs/: one full report and one per-board CSV per
# match, and one readable line per match in arena-logs/summary.txt, which is the
# file to read in the morning.
#
# Three properties worth knowing before you leave it running:
#
#   * It is resumable. A match whose log already exists is skipped, so if the
#     machine reboots at three in the morning you re-run the same command and it
#     carries on. Delete a log to force that one match to run again.
#   * It stops on request, within seconds. Create a file called STOP in the
#     repository root (or press Ctrl-C) and the match that is running is killed,
#     its half-written log is moved aside as .interrupted.txt, and the script
#     exits. Re-run the same command later and that match is the first thing it
#     does again; nothing else is repeated. A match is never worth more than a
#     night's sleep, and a kill costs at most the minutes it had run.
#   * A failed match does not kill the run. It is recorded as FAILED in the
#     summary and the next match starts. Only a failing test suite stops a seed,
#     because measuring on a broken tree is worse than not measuring.
#
# Seeds differ and that is the point: the JSkat players are not reproducible,
# and a difference that survives three seeds is real in a way one long run
# cannot show.
#
# This file must keep LF line endings. Git Bash feeds a script with CRLF to
# bash, which then reads the carriage return as part of every command and fails
# with "command not found" on the first line. .gitattributes pins it.

set -uo pipefail
# One level up from tools/ is the skat-ai project root, which is where the arena
# reads and writes: arena-logs/, belief-model/, the wrapper. Anchored rather than
# taken from the caller, because the run resumes by checking whether a match's
# log already exists -- started from elsewhere it would quietly redo the night.
cd "$(dirname "$0")/.."
# A Windows path, not a MINGW one. Git Bash reports /c/Users/... and the JVM
# reads that as a relative path off the drive root, so the CSVs would land in
# C:\c\Users\... -- which is exactly the shape of bug that eats a night's run.
ROOT=$(pwd -W 2>/dev/null || pwd)

SEEDS="11 12 13"
SCALE=1
# How many boards run at once. Four was the original guess and it was badly
# wrong: on a 13700KF -- eight P-cores, eight E-cores, twenty-four logical --
# four threads left five sixths of the machine idle, and going to sixteen made
# the same match twice as fast (13.5 s a board down to 6.5). Not more than
# sixteen: the remaining eight logical processors are hyperthreads sharing a
# P-core with a solver that is already using it, and the E-cores in the mix are
# why the speedup is two-fold rather than four.
THREADS=$(nproc 2>/dev/null || echo 4)
[ "$THREADS" -gt 16 ] && THREADS=16
# Substring of the match names to re-measure. Their logs are moved aside before
# the run so the skip logic lets them through again, and everything else is left
# alone.
#
# This is the normal shape of a re-run, not a special case. Changing one player
# invalidates the matches that player is in and nothing else, and re-measuring
# the other eleven would cost a night to confirm what has not changed. Moved
# rather than deleted: the superseded numbers are the before half of a
# before-and-after.
REDO=""
# Extra JVM system properties for the arena's own process, forwarded through
# arena/build.gradle.kts. -Dskat.probe=<n> is the honesty control on the
# outside engines (docs/external-bots.md); it is expensive and is only ever
# asked for by the probe block below, never for a whole night.
PROPS=""
# Who picks the contracts in --fixed-contract mode. Empty means the arena's
# default, which is greedy.
#
# Switching this to --bidder=search was tried and dropped, and the measurement is
# worth keeping so nobody tries it again on the same reasoning. The complaint was
# real: greedy over-declares, so card play was being measured on a pile of
# hopeless games -- in the run of 2026-08-17 even the double-dummy solver came
# out at -0.11 game points a game. But sourcing the contracts from search costs
# 5.3 s a board on top of the match (greedy: 1 s for forty boards, search: 211),
# which roughly doubles a seed, and it raises the share of boards on which both
# sides score alike from 26% to 37% -- safer contracts, so less to separate two
# players with. It buys a more realistic distribution at the price of a worse
# instrument, and the realistic distribution is already measured: that is what
# the auction-mode block below is.
BIDDER=""
QUICK=false
for arg in "$@"; do
    case "$arg" in
        # A rehearsal writes into its own directory. It used to write beside
        # the real logs under the same names, and since the name carries the
        # seed but not the board count, a 20%-scale rehearsal of seed 11
        # permanently shadowed the full-scale seed 11: every one of those
        # matches was skipped that night as "log already exists", and the
        # numbers that survived were 40-board ones with intervals four times
        # too wide to say anything. A rehearsal is not a measurement and must
        # not be able to stand in for one.
        --quick)      SEEDS="11"; SCALE=0.2; QUICK=true ;;
        --props=*)    PROPS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --scale=*)    SCALE="${arg#*=}" ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --redo=*)     REDO="${arg#*=}" ;;
        -h|--help)    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOG=arena-logs
# An if rather than `$QUICK && ...`, which evaluates to false here and would
# abort the script the day somebody adds `set -e` to the line above.
if $QUICK; then LOG=arena-logs/quick; fi
mkdir -p "$LOG"
SUMMARY="$LOG/summary.txt"

if [ -n "$REDO" ]; then
    OLD="$LOG/superseded-$(date '+%Y%m%d-%H%M%S')"
    moved=0
    for f in "$LOG"/*"$REDO"*.txt "$LOG"/*"$REDO"*.csv; do
        [ -e "$f" ] || continue
        mkdir -p "$OLD"
        mv "$f" "$OLD/"
        moved=$((moved + 1))
    done
    if [ "$moved" -eq 0 ]; then
        echo "--redo=$REDO matched no existing log; nothing to re-measure." >&2
        exit 2
    fi
    echo "$moved log(s) matching '$REDO' moved to $OLD"
fi

# Everything the summary learns, the terminal learns too. A run this long that
# says nothing for twenty minutes reads as a hang.
say() {
    printf '%s\n' "$*" | tee -a "$SUMMARY"
}

boards() {
    # awk rather than $(( )), because --scale=0.5 is not an integer.
    awk -v n="$1" -v s="$SCALE" 'BEGIN { v = int(n * s); print (v < 10 ? 10 : v) }'
}

stopped() {
    if [ -f STOP ]; then
        say ""
        say "STOP file found -- stopping after $(date '+%H:%M:%S')."
        return 0
    fi
    return 1
}

# One match. Writes its own log and appends the lines worth reading to the
# summary; never returns non-zero, because one bad match must not end the night.
match() {
    local a="$1" b="$2" count="$3" extra="${4:-}"
    # The mode belongs in the name. The same pair is measured twice, once with
    # the auction and once at fixed contracts, and a shared filename would make
    # the second run look already done and silently halve the night.
    local mode=auction
    case "$extra" in
        *contracts=solver*) mode=oracle ;;
        *fixed-contract*)   mode=cardplay ;;
    esac
    # Void-board mode is a different measurement of the same pair, so it needs a
    # different name for exactly the reason the mode does: a shared filename
    # would make the second run read the first one as already done.
    case "$extra" in *--passed-in=void*) mode="$mode-void" ;; esac
    # The bidder belongs in the name too, and for the same reason: the same pair
    # at fixed contracts scores differently depending on who chose the contracts,
    # so a run that changes the bidder must not read the old logs as its own.
    case "$extra" in
        *--bidder=*) mode="$mode-$(printf '%s' "${extra##*--bidder=}" | cut -d' ' -f1)" ;;
    esac
    local tag="$a-vs-$b-$mode-s$SEED"
    # A probed match is not the same match: the control adds a summary to the
    # report and costs several times as much, so it gets its own name and never
    # shadows an unprobed log.
    case "$PROPS" in *skat.probe=*) tag="$tag-probe" ;; esac
    local report="$LOG/$tag.txt"

    if [ -f "$report" ]; then
        say "  [skip] $tag -- log already exists"
        return 0
    fi
    [ -f STOP ] && return 0

    say "  [$(date '+%H:%M:%S')] $tag ($count boards)"
    # In the background, so the STOP file is noticed while the match runs and
    # not only between matches. --no-daemon is what makes the kill reach the
    # arena: with a daemon the arena's JVM is the daemon's child and outlives
    # the client, and a match that keeps running after "stop" is the one thing
    # this script must never do. It costs a few seconds of JVM start per match.
    # shellcheck disable=SC2086 -- $extra and $PROPS are deliberately word-split
    ./gradlew --console=plain -q --no-daemon $PROPS :arena:arena \
            --args="--a=$a --b=$b --boards=$count --seed=$SEED --threads=$THREADS $extra --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report" 2>&1 &
    RUNNING=$!
    RUNNING_REPORT="$report"
    RUNNING_TAG="$tag"
    while kill -0 "$RUNNING" 2>/dev/null; do
        if [ -f STOP ]; then
            interrupt_running
            return 0
        fi
        sleep 10
    done
    wait "$RUNNING"
    local rc=$?
    RUNNING=""
    if [ "$rc" -ne 0 ]; then
        # Moved aside rather than left in place, so that re-running the script
        # retries this match instead of skipping it as already done.
        mv "$report" "$LOG/$tag.failed.txt"
        say "      FAILED -- see $tag.failed.txt"
        return 0
    fi
    grep -E "^game pts/game|^tournament pts|^declares|^ramsch|^rule violations" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    # "  in: {PLAY=1}" only appears when somebody broke the API contract, and it
    # is the half of that report worth reading -- the count alone says a
    # violation happened but not in which decision.
    grep -E "^  in: | = .*game pts/game|^Resolved|^Not resolved" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    # The honesty control's verdict on an outside engine, when it was asked for.
    grep -E "^Honesty control|^  as declarer|^  rule divergences|^  A non-zero" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    return 0
}

# Kills the match that is running and moves its half-written log aside, so the
# next run does it again from the start. Called for the STOP file and for
# Ctrl-C alike.
RUNNING=""
RUNNING_REPORT=""
RUNNING_TAG=""
interrupt_running() {
    [ -n "$RUNNING" ] || return 0
    local pid="$RUNNING"
    RUNNING=""
    # On Windows the bash pid is not the Windows pid, and only a Windows kill of
    # the whole tree reaches the JVM that gradlew started. ps -W maps one to the
    # other; elsewhere a plain kill of the group is enough.
    if command -v taskkill >/dev/null 2>&1; then
        local winpid
        winpid=$(ps -W -p "$pid" 2>/dev/null | awk 'NR==2 { print $4 }')
        [ -n "$winpid" ] && taskkill //T //F //PID "$winpid" >/dev/null 2>&1
    fi
    pkill -TERM -P "$pid" 2>/dev/null
    kill -TERM "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    # A match that finished in the moment between the last check and the kill
    # has its result line already; keep it rather than pay for it twice.
    if [ -f "$RUNNING_REPORT" ] && grep -q " = .*game pts/game" "$RUNNING_REPORT"; then
        say "      (finished just before the stop; kept)"
        return 0
    fi
    if [ -f "$RUNNING_REPORT" ]; then
        mv "$RUNNING_REPORT" "$LOG/$RUNNING_TAG.interrupted.txt"
    fi
    rm -f "$LOG/$RUNNING_TAG.csv"
    say "      INTERRUPTED at $(date '+%H:%M:%S') -- $RUNNING_TAG will run again next time"
}

# Ctrl-C is the STOP file without the file.
on_signal() {
    say ""
    say "Interrupted -- stopping."
    interrupt_running
    say "Stopped $(date '+%Y-%m-%d %H:%M:%S'). Re-run the same command to carry on."
    exit 130
}
trap on_signal INT TERM

# A STOP file left over from last time would end this run before it starts.
# Starting the script is the clearest possible statement that it should run.
if [ -f STOP ]; then
    rm -f STOP
    echo "Removed a STOP file left over from an earlier run."
fi

say "============================================================"
say "Started $(date '+%Y-%m-%d %H:%M:%S')   seeds: $SEEDS   scale: $SCALE   threads: $THREADS"

for SEED in $SEEDS; do
    stopped && break
    say ""
    say "--- seed $SEED ---------------------------------------------"

    if ! ./gradlew --console=plain -q :engine:test :arena:test \
            > "$LOG/tests-seed$SEED.txt" 2>&1; then
        say "  TESTS FAILED -- skipping seed $SEED, see tests-seed$SEED.txt"
        continue
    fi
    say "  core and training tests pass"

    # The ladder, card play only. This is the number the product needs: four
    # levels that are actually a level apart, with bidding held out of it.
    match beginner club    "$(boards 200)" "--fixed-contract $BIDDER"
    match club     expert  "$(boards 200)" "--fixed-contract $BIDDER"
    match expert   analyst "$(boards 200)" "--fixed-contract $BIDDER"

    # The same ladder with the auction live, which is where the new bidding rule
    # shows. Declaring or not is now an expectation compared against what a
    # Ramsch would cost this hand, so these two blocks can disagree -- and if
    # they do, the disagreement is the finding.
    match beginner club    "$(boards 200)" ""
    match club     expert  "$(boards 200)" ""
    match expert   analyst "$(boards 200)" ""

    # What the ceiling costs, and what more sampling buys.
    match expert    solver    "$(boards 200)" "--fixed-contract $BIDDER"
    match search    search-32 "$(boards 200)" "--fixed-contract $BIDDER"

    # Against the field. The last one prices card play at objective contracts,
    # so neither side is measured on its own taste in games.
    match search solver       "$(boards 250)" ""
    match search greedy       "$(boards 300)" ""
    match search jskat-new    "$(boards 250)" "--passed-in=void"
    match solver greedy       "$(boards 300)" "--fixed-contract $BIDDER"
    match search jskat-ml-pro "$(boards 200)" "--fixed-contract --contracts=solver"

    # The learned belief, priced. Same search, same personality, same number of
    # worlds -- only where those worlds come from changes, so what the paired
    # difference measures is the model and nothing else. That is also why it is
    # measured against `search` rather than against the ladder: a win over a
    # weaker level would not say whether the belief or the memory did it.
    #
    # Skipped entirely when no model has been trained, rather than left to fail
    # thirteen times: a missing model is the normal state of a fresh clone.
    # Either file is a model. The exporter writes belief.bin and that is what the
    # app and the arena both prefer, so guarding on the ONNX alone would silently
    # skip every belief match on a machine that has only the shipped weights.
    if [ -f belief-model/belief.bin ] || [ -f belief-model/belief.onnx ]; then
        match belief search "$(boards 300)" "--fixed-contract $BIDDER"
        match belief search "$(boards 300)" ""
        # alpha-mu against the belief player it is built on, so the paired
        # difference is the search and not the search plus the model. Fixed
        # contracts only: it changes the declarer's card play and nothing about
        # the auction, so an auction run would spend an hour measuring noise.
        match alphamu belief "$(boards 300)" "--fixed-contract $BIDDER"

        # Our shipping player against the field, which `search` used to stand in
        # for and no longer should: the app seats the belief player, so the belief
        # player is the one whose distance to JSkat means anything.
        match belief jskat-ml-pro "$(boards 300)" "--fixed-contract --contracts=solver"
        match belief jskat-new    "$(boards 300)" "--passed-in=void"

        # The same match at the effort the app's top level actually spends. At a
        # fixed contract `belief-32` and Opponents.Level.ANALYST are the same
        # player card for card, so this is the only line in this file that
        # measures the strongest thing we ship against the strongest thing JSkat
        # ships. `belief` lost that comparison by 1.48 points; doubling the
        # worlds was worth 1.84 to the beliefless player, and whether that
        # carries over is the question.
        #
        # The control below is the sharper of the two and is not optional. Our
        # own players meet on duplicate boards with common random numbers, so the
        # paired difference is tight enough to resolve a point; against JSkat it
        # is not, and an edge near zero will come back unresolved. If that
        # happens, this line is what says whether the worlds did anything -- the
        # jskat line alone could not tell "we improved and it is still close"
        # from "nothing happened".
        match belief-32 jskat-ml-pro "$(boards 300)" "--fixed-contract --contracts=solver"
        match belief-32 belief       "$(boards 300)" "--fixed-contract $BIDDER"

        # How weak the entry level can be made with the dials that already exist.
        # Measured against `club`, which is the step a beginner actually feels.
        match novice-1w    club "$(boards 200)" "--fixed-contract $BIDDER"
        match novice-0m    club "$(boards 200)" "--fixed-contract $BIDDER"
        match novice-floor club "$(boards 200)" "--fixed-contract $BIDDER"
        # And the candidates against each other, which is the measurement that
        # decides whether the app can honestly offer two entry levels. Their
        # distances to `club` would give that as a difference of two unpaired
        # estimates, and this arena has already been burnt once by exactly that
        # arithmetic. A step a person is meant to feel gets measured directly.
        match novice-floor beginner  "$(boards 200)" "--fixed-contract $BIDDER"
        match novice-0m    beginner  "$(boards 200)" "--fixed-contract $BIDDER"

        # Does this player reize too cautiously? It wins 82-87% of the games it
        # declares while declaring under a third of the boards, and the classic
        # break-even is a two-thirds make chance -- but that comparison proves
        # nothing on its own, because a player declaring exactly at the threshold
        # still wins far more than two thirds on average. The games it takes are
        # the good ones. So the question is settled by moving the threshold and
        # measuring, not by reading the win rate.
        #
        # Each variant twice. Aggression also flips prefersTheHighCard() above
        # 0.6, which is a card-play tie-break; the fixed-contract run bypasses the
        # auction entirely, so whatever it shows is that confound and nothing
        # else. Auction minus fixed contracts is the bidding's own share.
        match belief-bold  belief "$(boards 250)" ""
        match belief-bold  belief "$(boards 200)" "--fixed-contract $BIDDER"
        match belief-timid belief "$(boards 250)" ""
        match belief-timid belief "$(boards 200)" "--fixed-contract $BIDDER"
        # The sharpness sweep is not here, and the measurement is why. An
        # exponent on the model's probabilities was swept over three seeds on
        # 2026-08-20: 0.5 pooled to +1.99, 1.0 to +2.37, 2.0 to +2.42, all
        # overlapping. No setting beats the model as trained -- and sharpening
        # was the one variant whose seeds disagreed by more than their own
        # intervals allowed (+5.76, -0.02, +2.35; Q = 7.5, p = 0.02), which is
        # exactly what sharpening does: it makes the search commit harder to
        # whatever the belief said, so the wrong guesses get louder too.
        # `belief-sharp` and `belief-soft` stay registered; they just do not
        # deserve an hour a night. If the question is reopened, measure them
        # against `belief` on the same boards rather than each against `search`.
    fi

    # The outside engines, when this checkout has built them
    # (tools/build-external-bots.sh; docs/external-bots.md). Each is placed
    # against the field in both modes, and our best is placed against each at
    # oracle contracts, which is the only line where "stronger than XSkat" means
    # card play rather than taste in games. Nothing here runs on a checkout
    # without the binaries, and nothing fails because of it.
    XSKAT=false; GOSKAT=false
    [ -x third_party/xskat/skatklar-xskat ] || [ -x third_party/xskat/skatklar-xskat.exe ] && XSKAT=true
    [ -x third_party/go-skat/skatklar-goskat ] || [ -x third_party/go-skat/skatklar-goskat.exe ] && GOSKAT=true
    if $XSKAT; then
        match xskat greedy    "$(boards 300)" "--passed-in=void"
        match xskat greedy    "$(boards 300)" "--fixed-contract $BIDDER"
        match xskat jskat-new "$(boards 250)" "--passed-in=void"
        # The leak, priced: xskat is told the skat, xskat-blind is dealt a
        # sampled one. Measured in the container at +0.40 [-0.55, +1.35] over
        # 150 boards; three seeds of this is what settles whether it is zero.
        match xskat xskat-blind "$(boards 300)" "--passed-in=void"
        if [ -f belief-model/belief.bin ] || [ -f belief-model/belief.onnx ]; then
            match belief-32 xskat "$(boards 300)" "--fixed-contract --contracts=solver"
            match belief-32 xskat "$(boards 300)" "--passed-in=void"
        fi
    fi
    if $GOSKAT; then
        match go-skat greedy "$(boards 200)" "--passed-in=void"
        match go-skat greedy "$(boards 200)" "--fixed-contract $BIDDER"
        if [ -f belief-model/belief.bin ] || [ -f belief-model/belief.onnx ]; then
            match belief-32 go-skat "$(boards 200)" "--fixed-contract --contracts=solver"
            match belief-32 go-skat "$(boards 200)" "--passed-in=void"
        fi
    fi
    if $XSKAT && $GOSKAT; then
        match xskat go-skat "$(boards 300)" "--passed-in=void"
    fi
    # The honesty control, once a seed and small: every card re-asked under
    # eight reshuffles of what the seat cannot see. Zero is the expected answer
    # for go-skat and a handful as declarer for xskat; anything else means the
    # engine's score above is not an honest player's. Small because it costs
    # nine searches a card, and its verdict does not sharpen with boards.
    if $XSKAT || $GOSKAT; then
        SAVED_PROPS="$PROPS"
        PROPS="$PROPS -Dskat.probe=8"
        $XSKAT  && match xskat   greedy "$(boards 60)" "--passed-in=void"
        $GOSKAT && match go-skat greedy "$(boards 60)" "--passed-in=void"
        PROPS="$SAVED_PROPS"
    fi
done

say ""
if [ -f STOP ]; then
    say "Stopped $(date '+%Y-%m-%d %H:%M:%S') -- re-run the same command to carry on."
else
    say "Finished $(date '+%Y-%m-%d %H:%M:%S')"
fi
say ""
say "Read arena-logs/summary.txt. The line that decides a match is the one with"
say "'game pts/game' and a confidence interval; 'ramsch' is how often that side"
say "ended up in one, which is the bidding rule's calibration showing."
