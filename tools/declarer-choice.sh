#!/usr/bin/env bash
# After route 1: what the declarer's choice after the auction gives up, by the true-deal labels.
#
#   ./tools/declarer-choice.sh --quick                48 boards: the speed, and that it runs end to end
#   ./tools/declarer-choice.sh                        2,000 boards, k 8, seed 9101
#   ./tools/declarer-choice.sh --boards=4000 --k=16   more boards, more play-outs an option
#
# On every board greedy's auction declares (the arena's fixed contracts),
# DeclarerChoiceMain plays every option out --model's student in every seat
# (the declarer by its top card, the defenders drawn): each suit and grand
# contract whose guaranteed value covers the bid, times the ten discards
# from its five least-wanted cards, k times on the true deal, common random
# numbers across the options. Beside them three references: what --shipped
# (default belief-64-shipped) buries and announces in that seat, the
# auction's own contract with the heuristic's discard (what route 1 played),
# and an honest re-chooser after the skat (every covering contract's discard
# searched over 16 worlds from the unseen cards, the contract worth most
# kept); and what the declarer could see (its ten, the skat, its place, the
# auction), for a learned declarer later. Then arena/python/declarer_choice.py:
# the honest comparisons (one reference minus another), and what choosing by
# the labels is worth -- contract and discard, the discard alone, the
# contract alone -- chosen on half the play-outs and scored on the other
# half, per declared game and per game. The re-chooser's search is most of
# the cost (DiscardSearch: about 160 full-game solves a contract at 16
# worlds); --search-worlds=0 leaves it out, as for --boards=20000, the corpus
# a learned declarer would need (about 1.5 hours that way).
#
# Options: --model (policy-oc3-ev), --shipped, --boards, --k (even), --seed
# (9101; 1000 or more), --search-worlds (16; 0 off), --threads (24), --python. Writes
# arena-logs/declarer-choice/<run>.jsonl and .txt, the summary in
# arena-logs/summary-declarer-choice.txt (-quick). A finished run is reused:
# the labels when the .jsonl exists, the report when the .txt does.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DC_ORIGINAL:-}" ]; then
    DC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/declarer-choice.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DC_ORIGINAL="$DC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DC_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; BOARDS=2000; K=8; SEED=9101; THREADS=24; MODEL=policy-oc3-ev; SHIPPED=belief-64-shipped; SW=16
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --boards=*)    BOARDS="${arg#*=}" ;;
        --k=*)         K="${arg#*=}" ;;
        --seed=*)      SEED="${arg#*=}" ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --model=*)     MODEL="${arg#*=}" ;;
        --shipped=*)   SHIPPED="${arg#*=}" ;;
        --search-worlds=*) SW="${arg#*=}" ;;
        --python=*)    PYTHON="${arg#*=}" ;;
        -h|--help)     sed -n '2,33p' "$DC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
$QUICK && BOARDS=48
for n in "$BOARDS" "$K" "$SEED" "$THREADS" "$SW"; do
    case "$n" in ''|*[!0-9]*) echo "not a whole number: $n" >&2; exit 2 ;; esac
done
[ "$BOARDS" -ge 1 ] || { echo "--boards is at least 1" >&2; exit 2; }
{ [ "$K" -ge 2 ] && [ $((K % 2)) -eq 0 ]; } || { echo "--k is even and at least 2 (it is split in halves)" >&2; exit 2; }
[ "$SEED" -ge 1000 ] || { echo "seed $SEED is below 1000: those boards are the teacher's and the arena's" >&2; exit 2; }
for f in model.json policy.bin policy-parity.bin; do
    [ -f "$MODEL/$f" ] || { echo "$MODEL/$f is missing: not a trained, exported student" >&2; exit 2; }
done
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python \
                     ../.venv/bin/python .venv/bin/python python3 python; do
        command -v "$candidate" > /dev/null 2>&1 && PYTHON="$candidate" && break
    done
fi
"$PYTHON" -c "import numpy" 2>/dev/null || { echo "no python with numpy ($PYTHON); pass --python=..." >&2; exit 2; }

LOG=arena-logs/declarer-choice
mkdir -p "$LOG"
NAME="$(basename "$MODEL")-$SHIPPED-s$SEED-b$BOARDS-k$K-w$SW"
$QUICK && NAME="$NAME-quick"
# Not LINES: bash keeps LINES (and COLUMNS) as the terminal's size and resets
# them after every external command (checkwinsize), so a label file named by
# LINES was written to "49" in the repository root.
LABELS="$LOG/$NAME.jsonl"
REPORT="$LOG/$NAME.txt"
SUMMARY=arena-logs/summary-declarer-choice.txt
$QUICK && SUMMARY=arena-logs/summary-declarer-choice-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "declarer choice started $(date '+%Y-%m-%d %H:%M:%S')   student=$MODEL shipped=$SHIPPED boards=$BOARDS k=$K seed=$SEED search-worlds=$SW threads=$THREADS"

if [ -f "$LABELS" ]; then
    say "  [skip] labels -- $LABELS exists"
else
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] labels"
    start=$(date +%s)
    run_log="$LOG/$NAME-run.txt"
    ./gradlew --console=plain -q --no-daemon :arena:declarerChoice \
        --args="--model=$MODEL --shipped=$SHIPPED --out=$ROOT/$LABELS --boards=$BOARDS --k=$K --seed=$SEED --threads=$THREADS --search-worlds=$SW" \
        > "$run_log.partial" 2>&1 || { mv "$run_log.partial" "$LOG/$NAME-run.failed.txt"; say "      FAILED -- see $LOG/$NAME-run.failed.txt"; exit 1; }
    mv "$run_log.partial" "$run_log"
    grep -E "declared boards labelled" "$run_log" | sed 's/^/    /' | tee -a "$SUMMARY"
    say "      took $(( ($(date +%s) - start) / 60 )) min"
fi

if [ -f "$REPORT" ]; then
    say "  [skip] report -- $REPORT exists"
else
    # From the repository root, where a relative --python resolves.
    if "$PYTHON" arena/python/declarer_choice.py "$LABELS" --boards="$BOARDS" > "$REPORT.partial" 2>&1; then
        mv "$REPORT.partial" "$REPORT"
    else
        mv "$REPORT.partial" "$LOG/$NAME-report.failed.txt"
        tail -5 "$LOG/$NAME-report.failed.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "      REPORT FAILED -- see $LOG/$NAME-report.failed.txt"; exit 1
    fi
fi
sed 's/^/  /' "$REPORT" | tee -a "$SUMMARY"
say "declarer choice finished $(date '+%Y-%m-%d %H:%M:%S')"
