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
#   * It stops on request. Create a file called STOP in the repository root and
#     the current match finishes, then it exits.
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
for arg in "$@"; do
    case "$arg" in
        --quick)      SEEDS="11"; SCALE=0.2 ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --scale=*)    SCALE="${arg#*=}" ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --redo=*)     REDO="${arg#*=}" ;;
        -h|--help)    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOG=arena-logs
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
    # The bidder belongs in the name too, and for the same reason: the same pair
    # at fixed contracts scores differently depending on who chose the contracts,
    # so a run that changes the bidder must not read the old logs as its own.
    case "$extra" in
        *--bidder=*) mode="$mode-$(printf '%s' "${extra##*--bidder=}" | cut -d' ' -f1)" ;;
    esac
    local tag="$a-vs-$b-$mode-s$SEED"
    local report="$LOG/$tag.txt"

    if [ -f "$report" ]; then
        say "  [skip] $tag -- log already exists"
        return 0
    fi
    [ -f STOP ] && return 0

    say "  [$(date '+%H:%M:%S')] $tag ($count boards)"
    # shellcheck disable=SC2086 -- $extra is deliberately word-split into flags
    if ! ./gradlew --console=plain -q :arena:arena \
            --args="--a=$a --b=$b --boards=$count --seed=$SEED --threads=$THREADS $extra --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report" 2>&1; then
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
    return 0
}

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
    match search jskat-new    "$(boards 250)" ""
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
        match belief jskat-new    "$(boards 300)" ""

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
done

say ""
say "Finished $(date '+%Y-%m-%d %H:%M:%S')"
say ""
say "Read arena-logs/summary.txt. The line that decides a match is the one with"
say "'game pts/game' and a confidence interval; 'ramsch' is how often that side"
say "ended up in one, which is the bidding rule's calibration showing."
