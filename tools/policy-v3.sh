#!/usr/bin/env bash
# The student with the play so far (docs/training-plan.md 2.10, format 3).
#
#   ./tools/policy-v3.sh                 four students on teacher-data-v3, then the yardstick
#   ./tools/policy-v3.sh --epochs=30
#   ./tools/policy-v3.sh --python=/c/Python312/python.exe
#
# teacher-data-v3 is the same 90,000 boards as v2 (seed 101; 94% of the
# records byte for byte the same, the rest games that went another way),
# each record with the play so far: points and tricks won per seat, and for
# every card played, in which trick, where in it, and who took it. Does the
# student play better for seeing it -- in the endgame most of all, where it
# plays like a one-world search today?
#
#   policy-v3-base        the encoding only, random start: the control on this corpus
#   policy-v3-warm        the encoding only, from the belief net's trunk
#   policy-v3-state       the encoding and the play state, random start
#   policy-v3-state-warm  the same from the belief's trunk (the play state's
#                         columns of the first layer start at zero)
#
# Beside policy-model and policy-model-warm (v2, the same deals held out).
# Each is skipped when its policy.pt exists. Read "regret by trick" for the
# endgame and "by role" for tricks 1-2, in arena-logs/policy-v3.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${V3_ORIGINAL:-}" ]; then
    V3_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/policy-v3.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    V3_ORIGINAL="$V3_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$V3_ORIGINAL")/.." || exit 1

DATA=teacher-data-v3
EPOCHS=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --epochs=*)  EPOCHS="${arg#*=}" ;;
        --data=*)    DATA="${arg#*=}" ;;
        --python=*)  PYTHON="${arg#*=}" ;;
        -h|--help)   sed -n '2,25p' "$V3_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
SUMMARY=$LOG/policy-v3.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "policy v3 started $(date '+%Y-%m-%d %H:%M:%S')   data=$DATA epochs=$EPOCHS"

RUNS=(
    "policy-v3-base|"
    "policy-v3-warm|--init-from belief-model"
    "policy-v3-state|--play-state"
    "policy-v3-state-warm|--play-state --init-from belief-model"
)
[ -f belief-model/belief.pt ] || { echo "no belief-model/belief.pt to start from" >&2; exit 2; }
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
    grep -E "trunk from|best held-out" "$LOG/policy-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
done

models="--model policy-model"
[ -f policy-model-warm/policy.pt ] && models="$models --model policy-model-warm"
for run in "${RUNS[@]}"; do models="$models --model ${run%%|*}"; done
say "  [$(date '+%H:%M:%S')] yardstick"
# shellcheck disable=SC2086
if "$PYTHON" arena/python/eval_policy.py --data "$DATA" $models > "$LOG/policy-v3-yardstick.txt" 2>&1; then
    sed -n '/^by role/,$p' "$LOG/policy-v3-yardstick.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
else
    tail -5 "$LOG/policy-v3-yardstick.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  YARDSTICK FAILED -- see $LOG/policy-v3-yardstick.txt"
fi
say "policy v3 finished $(date '+%Y-%m-%d %H:%M:%S')"
