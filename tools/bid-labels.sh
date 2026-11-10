#!/usr/bin/env bash
# The bidding: what winning the auction is worth, seat by seat and bid by bid, with the learned declarer.
#
#   ./tools/bid-labels.sh --quick              48 boards: the speed, and that it runs end to end
#   ./tools/bid-labels.sh                      2,000 boards, k 8, seed 9201
#   ./tools/bid-labels.sh --boards=20000 --seed=9202   a corpus for a learned bidder
#
# BidLabelMain: for every board and every seat, the value of declaring at
# every bid -- the seat picks up the board's skat and the learned declarer
# (--declarer, declarer-net-v1) chooses a game covering the bid and its
# discard -- each distinct choice played out k times on the true deal by the
# student (--model, policy-oc3-ev); and what the shipped bidder (--shipped,
# belief-64-shipped) does with the seat's ten: its ceiling and its opening.
# Then arena/python/bid_report.py plays the auction out of those numbers:
# the shipped bidder against itself (0 by construction), and against an
# oracle ceiling (a bound: it sees the deal), raised ceilings and openings
# without the Ramsch the void mode never plays -- every board from all three
# seats by both sides, as the arena pairs them.
#
# Options: --model, --declarer, --shipped, --boards, --k (even), --seed (9201;
# 1000 or more), --threads (24), --python. Writes arena-logs/bid-labels/<run>.jsonl
# and .txt, the summary in arena-logs/summary-bid-labels.txt (-quick). A
# finished run is reused: the labels when the .jsonl exists, the report when
# the .txt does.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${BL_ORIGINAL:-}" ]; then
    BL_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/bid-labels.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BL_ORIGINAL="$BL_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BL_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; BOARDS=2000; K=8; SEED=9201; THREADS=24; MODEL=policy-oc3-ev; DECLARER=declarer-net-v1
SHIPPED=belief-64-shipped; PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --boards=*)    BOARDS="${arg#*=}" ;;
        --k=*)         K="${arg#*=}" ;;
        --seed=*)      SEED="${arg#*=}" ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --model=*)     MODEL="${arg#*=}" ;;
        --declarer=*)  DECLARER="${arg#*=}" ;;
        --shipped=*)   SHIPPED="${arg#*=}" ;;
        --python=*)    PYTHON="${arg#*=}" ;;
        -h|--help)     sed -n '2,25p' "$BL_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
$QUICK && BOARDS=48
for n in "$BOARDS" "$K" "$SEED" "$THREADS"; do
    case "$n" in ''|*[!0-9]*) echo "not a whole number: $n" >&2; exit 2 ;; esac
done
[ "$BOARDS" -ge 1 ] || { echo "--boards is at least 1" >&2; exit 2; }
{ [ "$K" -ge 2 ] && [ $((K % 2)) -eq 0 ]; } || { echo "--k is even and at least 2" >&2; exit 2; }
[ "$SEED" -ge 1000 ] || { echo "seed $SEED is below 1000: those boards are the arena's" >&2; exit 2; }
for f in model.json policy.bin policy-parity.bin; do
    [ -f "$MODEL/$f" ] || { echo "$MODEL/$f is missing: not a trained, exported student" >&2; exit 2; }
done
[ -f "$DECLARER/declarer.bin" ] || { echo "$DECLARER/declarer.bin is missing (arena/python/export_declarer.py)" >&2; exit 2; }
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python \
                     ../.venv/bin/python .venv/bin/python python3 python; do
        command -v "$candidate" > /dev/null 2>&1 && PYTHON="$candidate" && break
    done
fi
"$PYTHON" -c "import numpy" 2>/dev/null || { echo "no python with numpy ($PYTHON); pass --python=..." >&2; exit 2; }

LOG=arena-logs/bid-labels
mkdir -p "$LOG"
NAME="$(basename "$MODEL")-$(basename "$DECLARER")-$SHIPPED-s$SEED-b$BOARDS-k$K"
$QUICK && NAME="$NAME-quick"
# Not LINES: bash resets LINES and COLUMNS to the terminal's size (checkwinsize).
LABELS="$LOG/$NAME.jsonl"
REPORT="$LOG/$NAME.txt"
SUMMARY=arena-logs/summary-bid-labels.txt
$QUICK && SUMMARY=arena-logs/summary-bid-labels-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "bid labels started $(date '+%Y-%m-%d %H:%M:%S')   student=$MODEL declarer=$DECLARER shipped=$SHIPPED boards=$BOARDS k=$K seed=$SEED threads=$THREADS"

if [ -f "$LABELS" ]; then
    say "  [skip] labels -- $LABELS exists"
else
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] labels"
    start=$(date +%s)
    run_log="$LOG/$NAME-run.txt"
    ./gradlew --console=plain -q --no-daemon :arena:bidLabels \
        --args="--model=$MODEL --declarer=$DECLARER --shipped=$SHIPPED --out=$ROOT/$LABELS --boards=$BOARDS --k=$K --seed=$SEED --threads=$THREADS" \
        > "$run_log.partial" 2>&1 || { mv "$run_log.partial" "$LOG/$NAME-run.failed.txt"; say "      FAILED -- see $LOG/$NAME-run.failed.txt"; exit 1; }
    mv "$run_log.partial" "$run_log"
    grep -E "boards, 3 seats each" "$run_log" | sed 's/^/    /' | tee -a "$SUMMARY"
    say "      took $(( ($(date +%s) - start) / 60 )) min"
fi

if [ -f "$REPORT" ]; then
    say "  [skip] report -- $REPORT exists"
else
    if "$PYTHON" arena/python/bid_report.py "$LABELS" > "$REPORT.partial" 2>&1; then
        mv "$REPORT.partial" "$REPORT"
    else
        mv "$REPORT.partial" "$LOG/$NAME-report.failed.txt"
        tail -5 "$LOG/$NAME-report.failed.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "      REPORT FAILED -- see $LOG/$NAME-report.failed.txt"; exit 1
    fi
fi
sed 's/^/  /' "$REPORT" | tee -a "$SUMMARY"
say "bid labels finished $(date '+%Y-%m-%d %H:%M:%S')"
