#!/usr/bin/env bash
# The student started from the belief net's trunk (docs/training-plan.md 2.10, I4).
#
#   ./tools/policy-warm.sh               four students on teacher-data-v2, then the yardstick
#   ./tools/policy-warm.sh --epochs=30
#   ./tools/policy-warm.sh --python=/c/Python312/python.exe
#
# The belief net and the student have the same body -- 306 inputs, three
# layers of 512 -- and the belief net's was trained on its own, larger corpus
# to say where the unseen cards are. Philipp's idea: start the student from it
# instead of from random weights, and train on as usual. The heads start
# random; the result is one ordinary student, nothing of the belief needed to
# run it, so policy.bin could simply be replaced.
#
#   policy-model-seed2        random start, another seed: the noise floor any
#                             difference below has to clear
#   policy-model-warm         the belief's trunk, trained as usual
#   policy-model-warm-lr3     the same at a third of the learning rate, so the
#                             first epochs do not train the head start away
#   policy-model-warm-freeze  the belief's first block held for 5 epochs
#
# Beside policy-model (random start, the one in the app). Each is skipped when
# its policy.pt exists. Read "by role" for tricks 1-2 -- the student's in the
# hybrid -- and "regret by trick", in arena-logs/policy-warm.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${WARM_ORIGINAL:-}" ]; then
    WARM_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/policy-warm.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    WARM_ORIGINAL="$WARM_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$WARM_ORIGINAL")/.." || exit 1

DATA=teacher-data-v2
EPOCHS=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --epochs=*)  EPOCHS="${arg#*=}" ;;
        --data=*)    DATA="${arg#*=}" ;;
        --python=*)  PYTHON="${arg#*=}" ;;
        -h|--help)   sed -n '2,26p' "$WARM_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
SUMMARY=$LOG/policy-warm.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "policy warm start started $(date '+%Y-%m-%d %H:%M:%S')   data=$DATA epochs=$EPOCHS"

RUNS=(
    "policy-model-seed2|--init-seed 2"
    "policy-model-warm|--init-from belief-model"
    "policy-model-warm-lr3|--init-from belief-model --lr 3e-4"
    "policy-model-warm-freeze|--init-from belief-model --freeze-epochs 5"
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
for run in "${RUNS[@]}"; do models="$models --model ${run%%|*}"; done
say "  [$(date '+%H:%M:%S')] yardstick"
# shellcheck disable=SC2086
if "$PYTHON" arena/python/eval_policy.py --data "$DATA" $models > "$LOG/policy-warm-yardstick.txt" 2>&1; then
    sed -n '/^by role/,$p' "$LOG/policy-warm-yardstick.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
else
    tail -5 "$LOG/policy-warm-yardstick.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  YARDSTICK FAILED -- see $LOG/policy-warm-yardstick.txt"
fi
say "policy warm start finished $(date '+%Y-%m-%d %H:%M:%S')"
