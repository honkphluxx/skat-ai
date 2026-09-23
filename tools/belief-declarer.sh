#!/usr/bin/env bash
# Phase B2: is the declarer's thin belief the two-to-one ratio, or the evidence?
#
#   ./tools/belief-declarer.sh                    control + parity + declarer-only, then the probe
#   ./tools/belief-declarer.sh --quick            3 epochs, 20 boards: does it run
#   ./tools/belief-declarer.sh --python=/c/Python312/python.exe
#   ./tools/belief-declarer.sh --threads=8
#
# What is being asked. tools/belief-share.sh (2026-09-23) read the shipped
# belief off its own draw in play: as a defender it places 49.3% of the
# unseen cards in the right hand against uniform's 42.4%; as the declarer,
# 51.3% against 50.5%. Two records in three in the corpus are a defender's.
# Is the declarer's margin thin because the net saw fewer of its positions,
# or because two passes say little about twenty cards?
#
# Three nets from the same shards (belief-data-mixed, the shipped corpus),
# the same seed, the same epochs, one knob:
#
#   belief-model-declarer-control    --declarer-weight 1   the corpus as it is
#   belief-model-declarer-x2         --declarer-weight 0   declarer records to parity
#   belief-model-declarer-only       --declarer-only       the declarer's records alone
#
# The control is not the shipped model: it is trained tonight, so the only
# thing that differs between the three is the weighting, and the shipped
# model's own numbers can be read against it for drift.
#
# Two readings, in order. First the held-out tenth by role
# (eval_belief.py --by-role), the cheap one: if the control already beats the
# declarer's uniform baseline by a wide margin held out and by less than a
# point in play, the fault is in how the sampler uses the model, not in the
# model -- look at BeliefWorldSource before training anything. Then the probe
# in play (belief-share.sh, each net seated as the shipped player through the
# candidate slot), where the declarer column is the whole answer:
#
#   moves with the weight      -> the ratio mattered; Phase P oversamples
#   does not move              -> the evidence is thin; more of this corpus
#                                 will not help the declarer, and human data
#                                 (ISS) is the open question
#   defender column falls      -> the parity net costs what it does not gain;
#                                 that is the price, not a reason to ship it
#
# Roughly: 7 minutes a net on the 4090, 40 minutes a probe at 16 threads.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DECLARER_ORIGINAL:-}" ]; then
    DECLARER_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-declarer.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DECLARER_ORIGINAL="$DECLARER_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DECLARER_ORIGINAL")/.." || exit 1

QUICK=false
THREADS=16
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,42p' "$DECLARER_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

DATA=belief-data-mixed
LOG=arena-logs/belief-declarer
SUMMARY=arena-logs/summary-belief-declarer.txt
EPOCHS=20
SHARE_ARGS=""
MODELS=belief-model-declarer
if $QUICK; then
    DATA=belief-data-mixed-quick; LOG=arena-logs/quick/belief-declarer
    SUMMARY=arena-logs/quick/summary-belief-declarer.txt; EPOCHS=3; SHARE_ARGS="--quick"
    # Its own model directories, or a rehearsal's three-epoch nets stand in for
    # the night's -- which is exactly what happened on 2026-09-23, the full
    # run skipping all three trainings as "belief.pt exists".
    MODELS=belief-model-declarer-quick
fi
mkdir -p "$LOG" "$(dirname "$SUMMARY")"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "belief-declarer started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS python=$PYTHON"
[ -d "$DATA" ] || { say "no corpus at $DATA"; exit 1; }

# 1. The shipped model, held out by role: the cheap reading, before training.
say "--- 1. the shipped model, held out by role ($DATA) ---"
if "$PYTHON" arena/python/eval_belief.py --model belief-model --data "$DATA" --by-role > "$LOG/eval-shipped.txt" 2>&1; then
    grep -E "records|^  !!" "$LOG/eval-shipped.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
else
    tail -3 "$LOG/eval-shipped.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
fi

# 2. Three nets.
train() {
    local model="$1" tag="$2"; shift 2
    if [ -f "$model/belief.pt" ] && grep -q declarer_weight "$model/model.json" 2>/dev/null; then
        say "  [skip] train $tag -- $model/belief.pt exists"; return 0
    fi
    [ -f STOP ] && return 1
    say "  [$(date '+%H:%M:%S')] train $tag, $EPOCHS epochs, $*"
    if ! "$PYTHON" arena/python/train_belief.py --data "$DATA" --out "$model" --epochs "$EPOCHS" "$@" \
            > "$LOG/train-$tag.txt" 2>&1; then
        tail -5 "$LOG/train-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  TRAINING FAILED -- see $LOG/train-$tag.txt"; return 1
    fi
    grep -E "declarer records|uniform sampler baseline|^epoch +$EPOCHS |best held-out" "$LOG/train-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    if ! "$PYTHON" arena/python/export_weights.py --model="$model" > "$LOG/weights-$tag.txt" 2>&1; then
        tail -3 "$LOG/weights-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  WEIGHTS FAILED -- see $LOG/weights-$tag.txt"; return 1
    fi
}
say "--- 2. three nets from $DATA ---"
train "$MODELS-control" control --declarer-weight 1 || exit 1
train "$MODELS-x2"      x2      --declarer-weight 0 || exit 1
train "$MODELS-only"    only    --declarer-only     || exit 1

# 3. Held out by role, each net.
say "--- 3. held out by role ---"
for tag in control x2 only; do
    model="$MODELS-$tag"
    if "$PYTHON" arena/python/eval_belief.py --model "$model" --data "$DATA" --by-role > "$LOG/eval-$tag.txt" 2>&1; then
        say "  $tag:"; grep -E "records" "$LOG/eval-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    else
        tail -3 "$LOG/eval-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
done
[ -f STOP ] && exit 0

# 4. In play: each net seated as the shipped player, the probe's declarer column.
say "--- 4. in play (belief-share.sh, tricks 1-3 rows) ---"
for tag in control x2 only; do
    [ -f STOP ] && break
    model="$MODELS-$tag"
    say "  $tag:"
    ./tools/belief-share.sh --candidate="$model" --players=belief-32-shipped-candidate --threads="$THREADS" $SHARE_ARGS \
        | grep -E "as declarer|as defender|tricks 1-3|FAILED" | sed 's/^/      /' | tee -a "$SUMMARY"
done
say "  shipped, for reference (2026-09-23): declarer 51.3 / uniform 50.5; defender 49.3 / uniform 42.4"
say "belief-declarer finished $(date '+%Y-%m-%d %H:%M:%S')"
