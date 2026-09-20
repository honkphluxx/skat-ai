#!/usr/bin/env bash
# Phase D: is a searched discard worth more than the heuristic one?
#
#   ./tools/discard-search.sh              three seeds, 400 auction boards each
#   ./tools/discard-search.sh --quick      one seed, 40 boards: does it run
#   ./tools/discard-search.sh --threads=8
#
# What is being asked. The declarer buries two of twelve by rule -- keep trumps
# and aces, bury the points of a short suit, and in a Null the two highest.
# That rule is about the shape of a hand when the question is about this deal.
# Measured over 352 declared boards with all 66 discards enumerated against
# double-dummy defence: some discard makes the contract on 47.2%, the
# heuristic's on 40.9%. One declared game in eight that could be won is lost
# before a card is played, and that 6.3 points is the ceiling for this run.
#
# Why the gate is in AUCTION mode and not at --contracts=solver, which is where
# every other card-play question here is asked. SolverContractSource prices a
# board by asking whether it is makeable after Discards.keepBestTen, and the
# player buries that same heuristic's complement -- so at solver contracts the
# heuristic keeps a makeable ten 100% of the time by construction and the
# measurement is circular. It measures the assumption. The contract has to come
# from somewhere that chose it without reference to any discard, and the auction
# is the only such source we have. The cost is that auction mode is noisier,
# which is what the board count is for.
#
# The variants differ in one thing: worlds per candidate. Candidates are always
# the ten pairs drawn from the five cards the heuristic wants least, which gives
# up none of the ceiling -- see DiscardSearch, and the coverage measurement in
# docs/training-plan.md.
#
#   belief-32-shipped-discard-8     80 solves a discard
#   belief-32-shipped-discard-16   160
#   belief-32-shipped-discard-32   320
#
# Read the world count as the axis. If 8 already pays and 32 pays no more, the
# decision is cheap and the discard was simply being made wrong. If nothing
# pays at any count, the heuristic is right about this hand shape and Phase D's
# first item closes -- which is a result, and the line choice is the rest of the
# phase either way.
#
# One thing the pilot cannot tell us and this run can: whether making the
# contract more often survives contact with defenders who are not double-dummy.
# Every number above is against perfect defence, and that is not the defence
# this player meets.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DISCARD_ORIGINAL:-}" ]; then
    DISCARD_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/discard-search.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DISCARD_ORIGINAL="$DISCARD_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DISCARD_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        -h|--help)    sed -n '2,45p' "$DISCARD_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOG=arena-logs/discard
SUMMARY=arena-logs/summary-discard.txt
BOARDS=400
SEEDS="11 12 13"
if $QUICK; then
    LOG=arena-logs/quick/discard
    SUMMARY=arena-logs/quick/summary-discard.txt
    BOARDS=40
    SEEDS="11"
fi
mkdir -p "$LOG" "$(dirname "$SUMMARY")"

say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
stopped() { [ -f STOP ] && { say "STOP file found -- stopping."; return 0; }; return 1; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }

say "============================================================"
say "discard-search started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"

SHIPPED=belief-32-shipped
match() {
    local a="$1" b="$2" count="$3"
    local tag="$a-vs-$b-auction-void-s$SEED"
    $QUICK && tag="$tag-b$count"
    local report="$LOG/$tag.txt"
    if [ -f "$report" ]; then say "  [skip] $tag -- log already exists"; return 0; fi
    [ -f STOP ] && return 0
    say "  [$(date '+%H:%M:%S')] $tag ($count boards)"
    ./gradlew --console=plain -q --no-daemon :arena:arena \
        --args="--a=$a --b=$b --boards=$count --seed=$SEED --threads=$THREADS --passed-in=void --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1
    if [ $? -ne 0 ]; then
        mv "$report" "$LOG/$tag.failed.txt"
        say "      FAILED -- see $tag.failed.txt"
        return 0
    fi
    # declares and wins-as-declarer matter more than usual here: the discard can
    # only change a game this seat declared, so the whole effect lives in that
    # column and a match-wide average dilutes it by the games it cannot touch.
    grep -E "^game pts/game|^declares|^wins as declarer|^rule violations| = .*game pts/game|^Resolved|^Not resolved" \
        "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
}

for SEED in $SEEDS; do
    stopped && break
    say "--- seed $SEED ---"
    for W in 8 16 32; do
        stopped && break
        match "belief-32-shipped-discard-$W" "$SHIPPED" "$BOARDS"
    done
done
say "discard-search finished $(date '+%Y-%m-%d %H:%M:%S')"
