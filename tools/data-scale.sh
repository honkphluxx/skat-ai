#!/usr/bin/env bash
# Does a student on its own get stronger with more of best's data? (docs/training-plan.md 2.10, I5)
#
#   ./tools/data-scale.sh                 export, corpora, students, yardstick, arena
#   ./tools/data-scale.sh --quick         ten boards, two epochs, one draw, one seed of 20 boards
#   ./tools/data-scale.sh --skip-export   the rest, on what is on disk
#   ./tools/data-scale.sh --threads=20 --seeds="11 12 13"
#   ./tools/data-scale.sh --python=/c/Python312/python.exe
#
# The student imitating the teacher was data-limited in September: a quarter,
# a half and all of 90,000 deals gave a held-out regret of 0.74, 0.62 and
# 0.51 (about 0.12 a doubling, no flattening), and twice the epochs nothing.
# This continues the curve on best's cards -- the strongest player of ours,
# whose card differs from the teacher's in the lost positions and ties -- and
# asks the question that matters for a phone player: does the student playing
# on its own get stronger in games, not only on the yardstick?
#
#   1. export   best (po:belief-64-shipped/rollout-32x4/lost-decl,lost-def,
#               tie0-decl) on boards 60,000-149,999 of seed 101, the same
#               seating draw, into teacher-data-best3/ (about 41 hours;
#               resumes shard by shard). With teacher-data-best (0-29,999)
#               and teacher-data-best2 (30,000-59,999), 150,000 deals.
#   2. corpora  teacher-data-best-150/: the three together (copies).
#               60,000 deals is teacher-data-best2-u, from the loop turn.
#   3. students three training draws at each size (a student is a draw,
#               2026-10-10): 30,000 -- policy-best, -i2, -i3, on disk;
#               60,000 -- policy-best2-u on disk, policy-b60-i2, -i3 new;
#               150,000 -- policy-b150, -i2, -i3 new. policy-model's recipe.
#   4. yardstick on teacher-data-best's held-out deals, which every student
#               here has not seen (the split is by deal), with policy-model
#               (the teacher's cards, 90,000 deals) beside them.
#   5. arena    student-64 (the student for every card of a suit or grand
#               game) with each student, against belief-64-shipped, fixed
#               contracts, seeds 11-16 and 21; per size the three draws
#               averaged seed by seed, then pooled, and 150,000 and 60,000
#               paired with 30,000. About 12 minutes a seed and student at
#               16 threads -- the student's seats cost no solver: 13 hours.
#
# Each step is skipped when its output exists, so the same command resumes;
# arena reports are written as .partial first; touch STOP to stop after the
# current one. Read arena-logs/summary-data-scale.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DS2_ORIGINAL:-}" ]; then
    DS2_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/data-scale.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DS2_ORIGINAL="$DS2_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DS2_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; EXPORT=true; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; EPOCHS=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)        QUICK=true ;;
        --skip-export)  EXPORT=false ;;
        --threads=*)    THREADS="${arg#*=}" ;;
        --seeds=*)      SEEDS="${arg#*=}" ;;
        --epochs=*)     EPOCHS="${arg#*=}" ;;
        --python=*)     PYTHON="${arg#*=}" ;;
        -h|--help)      sed -n '2,43p' "$DS2_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              echo "unknown option: $arg" >&2; exit 2 ;;
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
for d in teacher-data-best teacher-data-best2 teacher-data-best2-u; do
    [ -f "$d/teacher-format.json" ] || { echo "no $d" >&2; exit 2; }
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin" >&2; exit 2; }

T=belief-64-shipped
BEST="po:$T/rollout-32x4/lost-decl,lost-def,tie0-decl"
FIRST=60000; DEALS=90000; SHARD=1000
DATA3=teacher-data-best3; DATA150=teacher-data-best-150
SUFFIX=""
if $QUICK; then
    DEALS=10; SHARD=10; EPOCHS=2; BOARDS=20; SEEDS="11"; SUFFIX="-quick"
    DATA3=teacher-data-best3-quick; DATA150=teacher-data-best-150-quick
fi
LOG=arena-logs/data-scale
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-data-scale.txt
$QUICK && SUMMARY=arena-logs/summary-data-scale-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "data scale started $(date '+%Y-%m-%d %H:%M:%S')   export=$EXPORT ($DEALS deals from $FIRST into $DATA3) epochs=$EPOCHS boards=$BOARDS threads=$THREADS seeds=$SEEDS"

# 1. The export.
expected=$(( (DEALS + SHARD - 1) / SHARD ))
if $EXPORT; then
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] export: $BEST, boards $FIRST-$((FIRST + DEALS - 1)) into $DATA3"
    ./gradlew --console=plain -q --no-daemon :arena:exportTeacher \
        --args="--teacher=$BEST --boards=$DEALS --first=$FIRST --seed=101 --threads=$THREADS --shard=$SHARD --population=greedy,search-4,club,expert,jskat-new --mix=25,50,25 --passed-in=void --out=$ROOT/$DATA3" \
        2>&1 | grep -v "^SkatKlar solver" | tee -a "$LOG/export-$DATA3.log" | tail -12 | sed 's/^/      /' | tee -a "$SUMMARY"
fi
have=$(ls "$DATA3"/teacher-v3-s101-*.bin 2>/dev/null | wc -l)
[ "$have" -ge "$expected" ] || { say "  $DATA3 has $have of $expected shards -- run the same command again"; exit 1; }

# 2. The 150,000-deal corpus.
mkdir -p "$DATA150"
for g in "$DATA3"/teacher-format.json "$DATA3"/players.json "$DATA3"/teacher.txt "$DATA3"/encoding-v*.json; do
    [ -f "$DATA150/$(basename "$g")" ] || cp "$g" "$DATA150/"
done
SOURCES="teacher-data-best/teacher-v3-s101-*.bin teacher-data-best2/teacher-v3-s101-*.bin $DATA3/teacher-v3-s101-*.bin"
$QUICK && SOURCES="$DATA3/teacher-v3-s101-*.bin"   # the plumbing, not 400 MB of copies
# shellcheck disable=SC2086 -- $SOURCES is deliberately globbed
for shard in $SOURCES; do
    name=$(basename "$shard")
    [ -f "$DATA150/$name" ] && continue
    cp "$shard" "$DATA150/$name.partial" && mv "$DATA150/$name.partial" "$DATA150/$name"
done
say "  $DATA150: $(ls "$DATA150"/teacher-v3-s101-*.bin | wc -l) shards"

# 3. The students: size|model|corpus|init seed (models on disk are skipped).
if $QUICK; then
    STUDENTS=(
        "30|policy-best|teacher-data-best|1"
        "150|policy-b150$SUFFIX|$DATA150|1"
    )
else
    STUDENTS=(
        "30|policy-best|teacher-data-best|1"
        "30|policy-best-i2|teacher-data-best|2"
        "30|policy-best-i3|teacher-data-best|3"
        "60|policy-best2-u|teacher-data-best2-u|1"
        "60|policy-b60-i2|teacher-data-best2-u|2"
        "60|policy-b60-i3|teacher-data-best2-u|3"
        "150|policy-b150|$DATA150|1"
        "150|policy-b150-i2|$DATA150|2"
        "150|policy-b150-i3|$DATA150|3"
    )
fi
for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r size model data init <<< "$entry"
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    if [ ! -f "$model/policy.pt" ]; then
        say "  [$(date '+%H:%M:%S')] train $model on $data, init seed $init"
        if ! "$PYTHON" arena/python/train_policy.py --data "$data" --out "$model" --epochs "$EPOCHS" --init-seed "$init" \
                > "$LOG/train-$model.txt" 2>&1; then
            tail -5 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
        fi
        grep -E "records from|best held-out" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
    if [ ! -f "$model/policy.bin" ]; then
        "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
            || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
    fi
done

# 4. The yardstick.
YARD="$LOG/yardstick$SUFFIX.txt"
if [ ! -f "$YARD" ]; then
    models="--model policy-model"
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r size model data init <<< "$entry"; models="$models --model $model"; done
    say "  [$(date '+%H:%M:%S')] yardstick on teacher-data-best's held-out deals"
    # shellcheck disable=SC2086 -- $models is deliberately word-split
    "$PYTHON" arena/python/eval_policy.py --data teacher-data-best $models > "$YARD.partial" 2>&1 \
        && mv "$YARD.partial" "$YARD" || say "  YARDSTICK FAILED -- see $YARD.partial"
fi
[ -f "$YARD" ] && sed -n '/^by role/,$p' "$YARD" | head -32 | sed 's/^/      /' | tee -a "$SUMMARY"

# 5. The arena: each student alone, against the teacher.
for SEED in $SEEDS; do
    for entry in "${STUDENTS[@]}"; do
        IFS='|' read -r size model data init <<< "$entry"
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
# One number per size and seed: the mean of that size's draws on that seed.
size_seed() {
    local want=$1 seed=$2 sum=0 n=0 x
    for entry in "${STUDENTS[@]}"; do
        IFS='|' read -r size model data init <<< "$entry"
        [ "$size" = "$want" ] || continue
        x=$(diff_of "$LOG/student-$model-s$seed-b$BOARDS.txt"); [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
say "--- pooled: student-64 against $T, the draws of a size averaged seed by seed ---"
for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r size model data init <<< "$entry"
    own=""; for SEED in $SEEDS; do x=$(diff_of "$LOG/student-$model-s$SEED-b$BOARDS.txt"); [ -n "$x" ] && own="$own $x"; done
    echo "$own" | pool "    one draw: $model ($size,000 deals)" | tee -a "$SUMMARY"
done
for size in 30 60 150; do
    vals=""; for SEED in $SEEDS; do v=$(size_seed "$size" "$SEED"); [ -n "$v" ] && vals="$vals $v"; done
    echo "$vals" | pool "$size,000 deals of best's cards" | tee -a "$SUMMARY"
done
for size in 60 150; do
    paired=""
    for SEED in $SEEDS; do
        x=$(size_seed "$size" "$SEED"); y=$(size_seed 30 "$SEED")
        [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$paired" | pool "    $size,000 minus 30,000, same seeds" | tee -a "$SUMMARY"
done
say "data scale finished $(date '+%Y-%m-%d %H:%M:%S')"
