#!/usr/bin/env bash
# Is the 150,000-deal student short of training or of size? (docs/training-plan.md 2.10, I5)
#
#   ./tools/capacity-check.sh                 two variants, three draws each, yardstick, arena
#   ./tools/capacity-check.sh --quick         two epochs, one draw, one seed of 20 boards
#   ./tools/capacity-check.sh --threads=20 --seeds="11 12 13"
#   ./tools/capacity-check.sh --python=/c/Python312/python.exe
#
# On teacher-data-best-150 (150,000 deals of best's cards, 2 million
# records) the student with policy-model's recipe -- 3 x 512, 20 epochs --
# plays 3.72 behind the teacher on its own. Two training-only variants:
#
#   e40      the same net, 40 epochs (the learning-rate cycle stretched to
#            fit: the best held-out epoch near the end of a one-cycle schedule
#            is expected, so this asks whether the longer cycle pays)
#   h1024    3 x 1024 (about four times the weights), 40 epochs -- against
#            e40, so the two changes are read apart
#
# Three training draws each (init seeds 1-3; a student is a draw), the
# yardstick on teacher-data-best's held-out deals beside the three 20-epoch
# students of the data-scale run, then student-64 with each against
# belief-64-shipped, fixed contracts, seeds 11-16 and 21; per variant the
# draws averaged seed by seed, e40 paired with the 20-epoch students and
# h1024 with e40. About 12 minutes a seed and student at 16 threads: 8.5
# hours, the training minutes on the GPU. For the phone: 3 x 1024 costs
# about four times the multiply-adds a card, still a few milliseconds.
#
# Each step is skipped when its output exists; arena reports are written as
# .partial first; touch STOP to stop after the current one. Read
# arena-logs/summary-capacity-check.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${CC_ORIGINAL:-}" ]; then
    CC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/capacity-check.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    CC_ORIGINAL="$CC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$CC_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; EPOCHS=40
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,32p' "$CC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
DATA=teacher-data-best-150
[ -f "$DATA/teacher-format.json" ] || { echo "no $DATA (tools/data-scale.sh builds it)" >&2; exit 2; }

T=belief-64-shipped
SUFFIX=""
if $QUICK; then EPOCHS=2; BOARDS=20; SEEDS="11"; SUFFIX="-quick"; fi
# variant|model|hidden|init seed
if $QUICK; then
    STUDENTS=("e40|policy-b150-e40$SUFFIX|512|1" "h1024|policy-b150-h1024$SUFFIX|1024|1")
else
    STUDENTS=(
        "e40|policy-b150-e40|512|1" "e40|policy-b150-e40-i2|512|2" "e40|policy-b150-e40-i3|512|3"
        "h1024|policy-b150-h1024|1024|1" "h1024|policy-b150-h1024-i2|1024|2" "h1024|policy-b150-h1024-i3|1024|3"
    )
fi
# The 20-epoch students of tools/data-scale.sh, on disk: the baseline.
BASE=(policy-b150 policy-b150-i2 policy-b150-i3)
LOG=arena-logs/capacity-check
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-capacity-check.txt
$QUICK && SUMMARY=arena-logs/summary-capacity-check-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "capacity check started $(date '+%Y-%m-%d %H:%M:%S')   epochs=$EPOCHS boards=$BOARDS threads=$THREADS seeds=$SEEDS"

for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r variant model hidden init <<< "$entry"
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    if [ ! -f "$model/model.json" ]; then
        say "  [$(date '+%H:%M:%S')] train $model: 3 x $hidden, $EPOCHS epochs, init seed $init"
        if ! "$PYTHON" arena/python/train_policy.py --data "$DATA" --out "$model" --epochs "$EPOCHS" \
                --hidden "$hidden" --init-seed "$init" > "$LOG/train-$model.txt" 2>&1; then
            tail -5 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
        fi
        grep -E "model:|best held-out" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
    if [ ! -f "$model/policy.bin" ]; then
        "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
            || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
    fi
done

YARD="$LOG/yardstick$SUFFIX.txt"
if [ ! -f "$YARD" ]; then
    models=""
    for m in "${BASE[@]}"; do [ -f "$m/model.json" ] && models="$models --model $m"; done
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant model hidden init <<< "$entry"; models="$models --model $model"; done
    say "  [$(date '+%H:%M:%S')] yardstick on teacher-data-best's held-out deals"
    # shellcheck disable=SC2086 -- $models is deliberately word-split
    "$PYTHON" arena/python/eval_policy.py --data teacher-data-best $models > "$YARD.partial" 2>&1 \
        && mv "$YARD.partial" "$YARD" || say "  YARDSTICK FAILED -- see $YARD.partial"
fi
[ -f "$YARD" ] && awk '/all    declarer/{n++} n==1' "$YARD" | grep -vE "value|its" | head -12 | sed 's/^/      /' | tee -a "$SUMMARY"

for SEED in $SEEDS; do
    for entry in "${STUDENTS[@]}"; do
        IFS='|' read -r variant model hidden init <<< "$entry"
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        tag="student-$model-s$SEED-b$BOARDS"
        report="$LOG/$tag.txt"
        if [ ! -f "$report" ]; then
            say "  [$(date '+%H:%M:%S')] $tag"
            start=$(date +%s)
            ./gradlew --console=plain -q --no-daemon -Dpolicy.model.dir="$ROOT/$model" :arena:arena \
                --args="--a=student-64 --b=$T --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; continue; }
            mv "$report.partial" "$report"
            say "      took $(( ($(date +%s) - start) / 60 )) min"
        fi
        grep -E " = .*game pts/game" "$report" | sed "s/^/      $model: /" | tee -a "$SUMMARY"
    done
done

diff_of() { [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
pool() {
    awk -v label="$1" '{
        n = NF; if (n < 2) { printf "  %s: %d seed(s), nothing to pool\n", label, n; exit }
        for (i = 1; i <= n; i++) sum += $i; mean = sum / n
        for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
        se = sqrt(ss / (n - 1)) / sqrt(n)
        split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
        printf "  %s, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", label, n, mean, mean - q * se, mean + q * se
    }'
}
# The mean over a list of models of their result on one seed ($1 the seed, then the reports' paths' model names).
mean_of() {
    local seed=$1; shift; local sum=0 n=0 x m
    for m in "$@"; do
        x=$(diff_of "$LOG/student-$m-s$seed-b$BOARDS.txt"); [ -n "$x" ] || x=$(diff_of "arena-logs/data-scale/student-$m-s$seed-b$BOARDS.txt")
        [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
models_of() {
    local want=$1 out=""
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant model hidden init <<< "$entry"; [ "$variant" = "$want" ] && out="$out $model"; done
    echo "$out"
}
say "--- pooled: student-64 against $T, the draws of a variant averaged seed by seed ---"
# shellcheck disable=SC2046 -- the model lists are deliberately word-split
for v in base e40 h1024; do
    if [ "$v" = base ]; then list="${BASE[*]}"; label="3 x 512, 20 epochs (tools/data-scale.sh)"; else list=$(models_of "$v"); label="$v"; fi
    vals=""; for SEED in $SEEDS; do x=$(mean_of "$SEED" $list); [ -n "$x" ] && vals="$vals $x"; done
    echo "$vals" | pool "$label" | tee -a "$SUMMARY"
done
pair() {   # $1 label, $2 the list, $3 the list it is paired with
    local paired="" x y
    for SEED in $SEEDS; do
        x=$(mean_of "$SEED" $2); y=$(mean_of "$SEED" $3)
        [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$paired" | pool "    $1, same seeds" | tee -a "$SUMMARY"
}
pair "e40 minus 20 epochs" "$(models_of e40)" "${BASE[*]}"
pair "h1024 minus e40" "$(models_of h1024)" "$(models_of e40)"
say "capacity check finished $(date '+%Y-%m-%d %H:%M:%S')"
