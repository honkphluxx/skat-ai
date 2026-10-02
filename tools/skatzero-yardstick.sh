#!/usr/bin/env bash
# SkatZero on the yardstick (docs/training-plan.md 2.10, R): does a network
# trained on 1.5 billion self-play games play the endgame -- where our student
# plays like a one-world search -- or not?
#
#   ./tools/skatzero-yardstick.sh
#   ./tools/skatzero-yardstick.sh --every=4          a quarter of the held-out decisions (faster)
#   ./tools/skatzero-yardstick.sh --workers=12
#   ./tools/skatzero-yardstick.sh --python=/c/Python312/python.exe
#
# eval_policy.py on teacher-data-v3's held-out deals (the student's split),
# with SkatZero scored beside the student, the student with the play state,
# and the k-world searches, on the same decisions and the same referee
# worlds. SkatZero's state is rebuilt from each record (skatzero_eval.py); its
# card is what tools/skatzero-bot.py plays at the arena, including its
# one-step look-ahead. Read "regret by trick" and "by role".
#
# Needs a SkatZero checkout with its ONNX models (third_party/skatzero, or
# $SKATKLAR_SKATZERO_DIR) and onnxruntime in the interpreter that has torch
# (pip install onnxruntime). About 15-30 minutes. Writes
# arena-logs/skatzero-yardstick.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${SZY_ORIGINAL:-}" ]; then
    SZY_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/skatzero-yardstick.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    SZY_ORIGINAL="$SZY_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$SZY_ORIGINAL")/.." || exit 1

DATA=teacher-data-v3
EVERY=1
WORKERS=""
PYTHON=${SKATKLAR_PYTHON:-}
SZ=${SKATKLAR_SKATZERO_DIR:-third_party/skatzero}
for arg in "$@"; do
    case "$arg" in
        --every=*)    EVERY="${arg#*=}" ;;
        --workers=*)  WORKERS="${arg#*=}" ;;
        --data=*)     DATA="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,22p' "$SZY_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
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
if ! "$PYTHON" -c "import onnxruntime" 2>/dev/null; then
    echo "onnxruntime is missing: $PYTHON -m pip install onnxruntime" >&2
    exit 2
fi
[ -f "$SZ/models/onnx/D_0.onnx" ] || { echo "no SkatZero checkout with ONNX models at $SZ" >&2; exit 2; }
[ -f "$DATA/teacher-format.json" ] || { echo "no corpus $DATA" >&2; exit 2; }

models="--model policy-model"
[ -f policy-v3-state-warm/policy.pt ] && models="$models --model policy-v3-state-warm"
extra="--skatzero-every $EVERY"
[ -n "$WORKERS" ] && extra="$extra --skatzero-workers $WORKERS"
mkdir -p arena-logs
OUT=arena-logs/skatzero-yardstick.txt
echo "skatzero yardstick $(date '+%Y-%m-%d %H:%M:%S')  data=$DATA every=$EVERY  $models" | tee "$OUT"
# shellcheck disable=SC2086
"$PYTHON" arena/python/eval_policy.py --data "$DATA" $models --skatzero "$SZ" $extra 2>&1 | tee -a "$OUT"
