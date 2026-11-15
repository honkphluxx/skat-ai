#!/usr/bin/env bash
# The bid labels checked at the table: is the student's estimate of the learned bidder's extra games what the table makes of them?
#
#   ./tools/bid-check.sh --quick      40 boards: the speed, and that it runs end to end
#   ./tools/bid-check.sh              the 2,000 boards of the seed-9201 labels, k 2
#   ./tools/bid-check.sh --defence=student --stride=2
#                                     the table's declarer against the labels' defence, every second task
#
# arena/python/bid_check.py select: the seats where the learned bidder's ceiling
# (--model, bidder-net-v1, as the table's player bids) is above the shipped one,
# at the first bid where the two part, with the game and discard the learned
# declarer chose there; and --control seats both bidders declare, at 18.
# BidCheckMain then plays each k times with --player (belief-64-shipped, the
# card play of every player at the bidding table test) in all three seats, the
# declarer burying the labels' two cards. bid_check.py report: table against
# student seat by seat, by predicted value, and what a higher threshold drops.
#
# Options: --labels (a bid-labels .jsonl; the seed is read from its name, or
# --seed), --model, --player, --declarer and --defence (a player, "student" --
# the labels' student, a defender drawn at temperature 1 -- or
# "student-greedy", a defender by its top card; both default to --player),
# --student (the student's directory, policy-oc3-ev), --stride (1: every
# task), --k (2), --control (300), --threads (24), --python. Writes
# arena-logs/bid-check/<labels-bidder-control>.tasks (shared by every
# seating), <run>.checked, .txt and the summary in
# arena-logs/summary-bid-check.txt (-quick). A finished step is reused; set
# runs side by side with bid_check.py compare.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${BC_ORIGINAL:-}" ]; then
    BC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/bid-check.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BC_ORIGINAL="$BC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BC_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; K=2; CONTROL=300; THREADS=24; MODEL=bidder-net-v1; PLAYER=belief-64-shipped; SEED=
DECLARER=; DEFENCE=; STRIDE=1; STUDENT=policy-oc3-ev
LABELS=arena-logs/bid-labels/policy-oc3-ev-declarer-net-v1-belief-64-shipped-s9201-b2000-k8.jsonl
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --labels=*)    LABELS="${arg#*=}" ;;
        --seed=*)      SEED="${arg#*=}" ;;
        --k=*)         K="${arg#*=}" ;;
        --control=*)   CONTROL="${arg#*=}" ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --model=*)     MODEL="${arg#*=}" ;;
        --player=*)    PLAYER="${arg#*=}" ;;
        --declarer=*)  DECLARER="${arg#*=}" ;;
        --defence=*)   DEFENCE="${arg#*=}" ;;
        --student=*)   STUDENT="${arg#*=}" ;;
        --stride=*)    STRIDE="${arg#*=}" ;;
        --python=*)    PYTHON="${arg#*=}" ;;
        -h|--help)     sed -n '2,32p' "$BC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f "$LABELS" ] || { echo "no labels: $LABELS (tools/bid-labels.sh)" >&2; exit 2; }
if [ -z "$SEED" ]; then
    SEED=$(basename "$LABELS" | sed -nE 's/.*-s([0-9]+)-b[0-9]+.*/\1/p')
    [ -n "$SEED" ] || { echo "no seed in the labels' name; pass --seed=" >&2; exit 2; }
fi
BOARDS=0
if $QUICK; then BOARDS=40; CONTROL=20; fi
DECLARER=${DECLARER:-$PLAYER}; DEFENCE=${DEFENCE:-$PLAYER}
for n in "$K" "$CONTROL" "$THREADS" "$SEED" "$STRIDE"; do
    case "$n" in ''|*[!0-9]*) echo "not a whole number: $n" >&2; exit 2 ;; esac
done
[ "$K" -ge 1 ] || { echo "--k is at least 1" >&2; exit 2; }
[ "$STRIDE" -ge 1 ] || { echo "--stride is at least 1" >&2; exit 2; }
case "$DECLARER $DEFENCE" in
    *student*) for f in model.json policy.bin; do
                   [ -f "$STUDENT/$f" ] || { echo "$STUDENT/$f is missing: the student seat needs it (--student=)" >&2; exit 2; }
               done ;;
esac
for f in bidder.pt bidder.json; do
    [ -f "$MODEL/$f" ] || { echo "$MODEL/$f is missing (arena/python/train_bidder.py)" >&2; exit 2; }
done
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python \
                     ../.venv/bin/python .venv/bin/python python3 python; do
        command -v "$candidate" > /dev/null 2>&1 && PYTHON="$candidate" && break
    done
fi
"$PYTHON" -c "import numpy, torch" 2>/dev/null || { echo "no python with numpy and torch ($PYTHON); pass --python=..." >&2; exit 2; }

LOG=arena-logs/bid-check
mkdir -p "$LOG"
BASE="$(basename "$LABELS" .jsonl)-$(basename "$MODEL")-c$CONTROL"
$QUICK && BASE="$BASE-quick"
if [ "$DECLARER" = "$PLAYER" ] && [ "$DEFENCE" = "$PLAYER" ] && [ "$STRIDE" = 1 ]; then
    # The first runs' name, so that they are found again.
    NAME="$(basename "$LABELS" .jsonl)-$(basename "$MODEL")-$PLAYER-k$K-c$CONTROL"
    $QUICK && NAME="$NAME-quick"
else
    NAME="$BASE-decl-$DECLARER-def-$DEFENCE-k$K-every$STRIDE"
fi
TASKS="$LOG/$BASE.tasks"
[ -f "$TASKS" ] || { [ -f "$LOG/$NAME.tasks" ] && TASKS="$LOG/$NAME.tasks"; }
CHECKED="$LOG/$NAME.checked"
REPORT="$LOG/$NAME.txt"
SUMMARY=arena-logs/summary-bid-check.txt
$QUICK && SUMMARY=arena-logs/summary-bid-check-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "bid check started $(date '+%Y-%m-%d %H:%M:%S')   labels=$LABELS seed=$SEED bidder=$MODEL declarer=$DECLARER defence=$DEFENCE k=$K every=$STRIDE control=$CONTROL threads=$THREADS"

if [ -f "$TASKS" ]; then
    say "  [skip] tasks -- $TASKS exists"
else
    "$PYTHON" arena/python/bid_check.py select --labels "$LABELS" --model "$MODEL" --out "$TASKS.partial" \
        --boards "$BOARDS" --control "$CONTROL" 2>&1 | tee -a "$SUMMARY"
    [ "${PIPESTATUS[0]}" -eq 0 ] || { say "      SELECT FAILED"; exit 1; }
    mv "$TASKS.partial" "$TASKS"
fi

if [ -f "$CHECKED" ]; then
    say "  [skip] play-outs -- $CHECKED exists"
else
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] play-outs"
    start=$(date +%s)
    run_log="$LOG/$NAME-run.txt"
    ./gradlew --console=plain -q --no-daemon :arena:bidCheck \
        --args="--tasks=$ROOT/$TASKS --out=$ROOT/$CHECKED --seed=$SEED --k=$K --threads=$THREADS --declarer=$DECLARER --defence=$DEFENCE --stride=$STRIDE --model=$STUDENT" \
        > "$run_log.partial" 2>&1 || { mv "$run_log.partial" "$LOG/$NAME-run.failed.txt"; say "      FAILED -- see $LOG/$NAME-run.failed.txt"; exit 1; }
    mv "$run_log.partial" "$run_log"
    grep -E "tasks, .* play-outs in" "$run_log" | sed 's/^/    /' | tee -a "$SUMMARY"
    say "      took $(( ($(date +%s) - start) / 60 )) min"
fi

if [ -f "$REPORT" ]; then
    say "  [skip] report -- $REPORT exists"
else
    if "$PYTHON" arena/python/bid_check.py report "$CHECKED" > "$REPORT.partial" 2>&1; then
        mv "$REPORT.partial" "$REPORT"
    else
        mv "$REPORT.partial" "$LOG/$NAME-report.failed.txt"
        tail -5 "$LOG/$NAME-report.failed.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "      REPORT FAILED -- see $LOG/$NAME-report.failed.txt"; exit 1
    fi
fi
sed 's/^/  /' "$REPORT" | tee -a "$SUMMARY"
say "bid check finished $(date '+%Y-%m-%d %H:%M:%S')"
