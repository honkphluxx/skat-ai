#!/usr/bin/env bash
# One net per phase of the game? (docs/training-plan.md 2.10, I4)
#
#   ./tools/policy-phases.sh             five students on teacher-data-v2, then the yardstick
#   ./tools/policy-phases.sh --epochs=40
#   ./tools/policy-phases.sh --python=/c/Python312/python.exe
#
# The hybrid gives the student tricks 1-2 and the search the rest, and on the
# held-out deals the student there beats a 32-world search but not the 64-world
# teacher (a third more regret as declarer, an eighth as defender). The student
# was trained on all nine tricks. Does a net that spends itself on the tricks
# it plays do better there?
#
#   policy-model-t12      trained on tricks 1-2 only
#   policy-model-t13      trained on tricks 1-3 only
#   policy-model-t12w4    all tricks, tricks 1-2 drawn four times as often
#
# And the other end, for a player that is networks all the way through (the
# student alone lost 6.2 points to the shipped player, nearly all of it as
# declarer, and nearly all of that in the endgame):
#
#   policy-model-t49      trained on tricks 4-9 only, the same size
#   policy-model-t49-big  the same, 1024 wide and 4 deep
#
# Each is skipped when its policy.pt exists. The yardstick then scores all of
# them beside policy-model (all tricks): read "by role" for tricks 1-2 and
# "regret by trick" for 4-9, in arena-logs/policy-phases.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${PHASES_ORIGINAL:-}" ]; then
    PHASES_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/policy-phases.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    PHASES_ORIGINAL="$PHASES_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$PHASES_ORIGINAL")/.." || exit 1

DATA=teacher-data-v2
EPOCHS=30
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --epochs=*)  EPOCHS="${arg#*=}" ;;
        --data=*)    DATA="${arg#*=}" ;;
        --python=*)  PYTHON="${arg#*=}" ;;
        -h|--help)   sed -n '2,30p' "$PHASES_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python ../.venv/bin/python .venv/bin/python; do
        [ -x "$candidate" ] && PYTHON="$candidate" && break
    done
fi
if [ -z "$PYTHON" ] || ! "$PYTHON" -c "import torch" 2>/dev/null; then
    echo "no interpreter with torch found; pass --python=... or set SKATKLAR_PYTHON" >&2
    exit 2
fi

LOG=arena-logs
mkdir -p "$LOG"
SUMMARY=$LOG/policy-phases.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "policy phases started $(date '+%Y-%m-%d %H:%M:%S')   data=$DATA epochs=$EPOCHS"

RUNS=(
    "policy-model-t12|--tricks 1-2"
    "policy-model-t13|--tricks 1-3"
    "policy-model-t12w4|--weight-tricks 1-2:4"
    "policy-model-t49|--tricks 4-9"
    "policy-model-t49-big|--tricks 4-9 --hidden 1024 --layers 4"
)
for run in "${RUNS[@]}"; do
    model=${run%%|*}
    extra=${run#*|}
    if [ -f "$model/policy.pt" ]; then say "  [skip] $model -- policy.pt exists"; continue; fi
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] train $model: $extra"
    # shellcheck disable=SC2086 -- $extra is deliberately word-split
    if ! "$PYTHON" arena/python/train_policy.py --data "$DATA" --out "$model" --epochs "$EPOCHS" $extra \
            > "$LOG/policy-$model.txt" 2>&1; then
        tail -5 "$LOG/policy-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  TRAINING FAILED -- see $LOG/policy-$model.txt"; exit 1
    fi
    grep -E "training on|drawn|best held-out" "$LOG/policy-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
done

models="--model policy-model"
for run in "${RUNS[@]}"; do models="$models --model ${run%%|*}"; done
say "  [$(date '+%H:%M:%S')] yardstick"
# shellcheck disable=SC2086
if "$PYTHON" arena/python/eval_policy.py --data "$DATA" $models > "$LOG/policy-phases-yardstick.txt" 2>&1; then
    sed -n '/^by role/,$p' "$LOG/policy-phases-yardstick.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
else
    tail -5 "$LOG/policy-phases-yardstick.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  YARDSTICK FAILED -- see $LOG/policy-phases-yardstick.txt"
fi
say "policy phases finished $(date '+%Y-%m-%d %H:%M:%S')"
