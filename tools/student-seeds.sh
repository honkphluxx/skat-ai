#!/usr/bin/env bash
# How much of a play-out student is its training draw? (docs/training-plan.md 2.10, R)
#
#   ./tools/student-seeds.sh                 three more students, the arena for each
#   ./tools/student-seeds.sh --quick         two epochs, one seed of 20 boards: the plumbing
#   ./tools/student-seeds.sh --only=policy-best-i2
#   ./tools/student-seeds.sh --threads=20 --seeds="11 12 13"
#   ./tools/student-seeds.sh --python=/c/Python312/python.exe
#
# Turn 1's student (policy-best, +2.01 against the teacher doing best's
# play-outs) and turn 2's (policy-best2 +1.07, policy-best2-u +1.10) differ
# by more than their arena intervals -- but each is one draw of the
# training: one initialisation, one batch order. Before reading the loop as
# climbing or sinking, the same data again with other draws:
#
#   policy-best-i2, policy-best-i3    teacher-data-best, --init-seed 2 and 3
#   policy-best2-i2                   teacher-data-best2, --init-seed 2
#
# Same held-out split (--seed 1), same recipe otherwise. Then best with each
# doing the play-outs against belief-64-shipped, seeds 11-16 and 21, paired
# by seed with the student it re-draws. If policy-best's re-draws land near
# +1.1, the +2.01 was the draw and every student so far is about +1.1-1.3;
# if they land near +2.0, turn 1's data is better than turn 2's and the loop
# drifted. About 3.7 hours a student at 16 threads; the training takes
# seconds. Each step is skipped when its output exists; touch STOP to stop.
# Read arena-logs/summary-student-seeds.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${SS_ORIGINAL:-}" ]; then
    SS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/student-seeds.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    SS_ORIGINAL="$SS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$SS_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; ONLY=""; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; EPOCHS=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --only=*)     ONLY="${arg#*=}" ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --epochs=*)   EPOCHS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,28p' "$SS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
SUFFIX=""
if $QUICK; then EPOCHS=2; BOARDS=20; SEEDS="11"; SUFFIX="-quick"; fi

# model|data|init seed|the student it re-draws
STUDENTS=(
    "policy-best-i2|teacher-data-best|2|policy-best"
    "policy-best-i3|teacher-data-best|3|policy-best"
    "policy-best2-i2|teacher-data-best2|2|policy-best2"
)
T=belief-64-shipped
SCOPE=lost-decl,lost-def,tie0-decl
LOG=arena-logs/student-seeds
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-student-seeds.txt
$QUICK && SUMMARY=arena-logs/summary-student-seeds-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "student seeds started $(date '+%Y-%m-%d %H:%M:%S')   epochs=$EPOCHS boards=$BOARDS threads=$THREADS seeds=$SEEDS"

for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r model data init base <<< "$entry"
    [ -n "$ONLY" ] && [ "$ONLY" != "$model" ] && continue
    model="$model$SUFFIX"
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    [ -f "$data/teacher-format.json" ] || { say "no $data"; exit 2; }
    if [ ! -f "$model/policy.pt" ]; then
        say "  [$(date '+%H:%M:%S')] train $model on $data, init seed $init"
        if ! "$PYTHON" arena/python/train_policy.py --data "$data" --out "$model" --epochs "$EPOCHS" --init-seed "$init" \
                > "$LOG/train-$model.txt" 2>&1; then
            tail -5 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
        fi
    fi
    grep -E "records from|best held-out" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    if [ ! -f "$model/policy.bin" ]; then
        "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
            || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
    fi
done

report_of() {   # best with student $1 on seed $2, wherever a run wrote it
    for dir in "$LOG" arena-logs/loop-turn arena-logs/loop-test; do
        [ -f "$dir/best-$1-s$2-b$BOARDS.txt" ] && { echo "$dir/best-$1-s$2-b$BOARDS.txt"; return; }
    done
}
diff_of() { [ -n "$1" ] && [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
for SEED in $SEEDS; do
    for entry in "${STUDENTS[@]}"; do
        IFS='|' read -r model data init base <<< "$entry"
        [ -n "$ONLY" ] && [ "$ONLY" != "$model" ] && continue
        model="$model$SUFFIX"
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        tag="best-$model-s$SEED-b$BOARDS"
        report="$LOG/$tag.txt"
        if [ ! -f "$report" ]; then
            say "  [$(date '+%H:%M:%S')] $tag"
            start=$(date +%s)
            ./gradlew --console=plain -q --no-daemon :arena:arena \
                --args="--a=po:$T/rollout-32x4@$model/$SCOPE --b=$T --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; continue; }
            mv "$report.partial" "$report"
            say "      took $(( ($(date +%s) - start) / 60 )) min"
        fi
        grep -E " = .*game pts/game" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    done
done

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
say "--- pooled, each against $T ---"
for student in policy-best policy-best2 policy-best2-u policy-model policy-v3-30; do
    own=""; for SEED in $SEEDS; do x=$(diff_of "$(report_of "$student" "$SEED")"); [ -n "$x" ] && own="$own $x"; done
    echo "$own" | pool "best, play-outs by $student (as measured)" | tee -a "$SUMMARY"
done
for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r model data init base <<< "$entry"
    [ -n "$ONLY" ] && [ "$ONLY" != "$model" ] && continue
    model="$model$SUFFIX"
    own=""; paired=""
    for SEED in $SEEDS; do
        x=$(diff_of "$(report_of "$model" "$SEED")"); y=$(diff_of "$(report_of "$base" "$SEED")")
        [ -n "$x" ] && own="$own $x"
        [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$own" | pool "best, play-outs by $model ($data, init seed $init)" | tee -a "$SUMMARY"
    echo "$paired" | pool "    $model minus $base, same seeds" | tee -a "$SUMMARY"
done
say "student seeds finished $(date '+%Y-%m-%d %H:%M:%S')"
