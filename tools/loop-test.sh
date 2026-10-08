#!/usr/bin/env bash
# Does a student taught by best make better play-outs? (docs/training-plan.md 2.10, R)
#
#   ./tools/loop-test.sh                 control corpus, two students, yardstick, arena
#   ./tools/loop-test.sh --quick         two epochs, one seed of 20 boards: the plumbing
#   ./tools/loop-test.sh --threads=20 --seeds="11 12 13"
#   ./tools/loop-test.sh --python=/c/Python312/python.exe
#
# The first turn of the loop the plan calls Phase R, like for like:
#
#   policy-v3-30   trained on teacher-data-v3's first 30 shards (boards 0-29,999
#                  of seed 101): the teacher's (belief-64-shipped) cards
#   policy-best    trained on teacher-data-best: the same deals and seating,
#                  best's cards (po:belief-64-shipped/rollout-32x4/lost-decl,
#                  lost-def,tie0-decl -- the play-outs' card where they decided)
#
# Same recipe for both, policy-model's (the encoding only, 3 x 512, random
# start, 20 epochs). Then the yardstick on the control corpus's held-out deals
# (the same deals are held out of both: the split is by deal), and the arena:
# best with each student doing the play-outs, each against belief-64-shipped,
# fixed contracts, seeds 11-16 and 21, paired by seed. The yardstick cannot
# see the difference -- it scores against the votes, and best only chooses
# among cards the votes rate equal -- so the arena decides. policy-model
# (90,000 deals of v2) did best's play-outs so far (+1.31): the reference, not
# the control.
#
# Each step is skipped when its output exists, so the same command resumes;
# reports are written as .partial first; touch STOP to stop after the current
# one. About seven to eight hours for the arena at 16 threads, minutes for the
# training on the GPU. Read arena-logs/summary-loop-test.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${LT_ORIGINAL:-}" ]; then
    LT_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/loop-test.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    LT_ORIGINAL="$LT_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$LT_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; EPOCHS=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --epochs=*)   EPOCHS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,32p' "$LT_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
for f in teacher-data-best/teacher-format.json teacher-data-v3/teacher-format.json; do
    [ -f "$f" ] || { echo "no $f" >&2; exit 2; }
done

CONTROL=teacher-data-v3-30
A=policy-v3-30; B=policy-best
if $QUICK; then EPOCHS=2; BOARDS=20; SEEDS="11"; A=policy-v3-30-quick; B=policy-best-quick; fi
LOG=arena-logs/loop-test
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-loop-test.txt
$QUICK && SUMMARY=arena-logs/summary-loop-test-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "loop test started $(date '+%Y-%m-%d %H:%M:%S')   epochs=$EPOCHS boards=$BOARDS threads=$THREADS seeds=$SEEDS"

# 1. The control corpus: v3's first 30 shards and the files that describe them.
mkdir -p "$CONTROL"
for g in teacher-data-v3/teacher-format.json teacher-data-v3/players.json teacher-data-v3/teacher.txt \
         teacher-data-v3/encoding-v*.json; do
    [ -f "$CONTROL/$(basename "$g")" ] || cp "$g" "$CONTROL/"
done
copied=0
for k in $(seq 0 29); do
    shard=$(printf 'teacher-v3-s101-%06d.bin' $((k * 1000)))
    [ -f "teacher-data-v3/$shard" ] || { say "teacher-data-v3/$shard is missing"; exit 2; }
    [ -f "teacher-data-best/$shard" ] || { say "teacher-data-best/$shard is missing -- the export is not complete"; exit 2; }
    [ -f "$CONTROL/$shard" ] || { cp "teacher-data-v3/$shard" "$CONTROL/$shard.partial" && mv "$CONTROL/$shard.partial" "$CONTROL/$shard"; copied=$((copied + 1)); }
done
say "  control corpus $CONTROL: 30 shards ($copied copied now)"

# 2. The two students, the same recipe.
for pair in "$A|$CONTROL" "$B|teacher-data-best"; do
    IFS='|' read -r model data <<< "$pair"
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    if [ ! -f "$model/policy.pt" ]; then
        say "  [$(date '+%H:%M:%S')] train $model on $data"
        if ! "$PYTHON" arena/python/train_policy.py --data "$data" --out "$model" --epochs "$EPOCHS" \
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

# 3. The yardstick, on the control corpus's held-out deals.
if [ ! -f "$LOG/yardstick-$A-$B.txt" ]; then
    say "  [$(date '+%H:%M:%S')] yardstick"
    "$PYTHON" arena/python/eval_policy.py --data "$CONTROL" --model policy-model --model "$A" --model "$B" \
        > "$LOG/yardstick-$A-$B.txt" 2>&1 || say "  YARDSTICK FAILED -- see $LOG/yardstick-$A-$B.txt"
fi
sed -n '/^by role/,$p' "$LOG/yardstick-$A-$B.txt" | head -12 | sed 's/^/      /' | tee -a "$SUMMARY"

# 4. The arena: best's play-outs by each student, against the teacher.
T=belief-64-shipped
SCOPE=lost-decl,lost-def,tie0-decl
diff_of() { [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
for SEED in $SEEDS; do
    for model in "$A" "$B"; do
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
        grep -E " = .*game pts/game|^  (lost|tied), " "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
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
a=""; b=""; paired=""; ref=""
for SEED in $SEEDS; do
    x=$(diff_of "$LOG/best-$A-s$SEED-b$BOARDS.txt"); y=$(diff_of "$LOG/best-$B-s$SEED-b$BOARDS.txt")
    r=$(diff_of "arena-logs/playout-study/best-s$SEED-b$BOARDS.txt")
    [ -n "$x" ] && a="$a $x"
    [ -n "$y" ] && b="$b $y"
    [ -n "$r" ] && ref="$ref $r"
    [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$y" -v q="$x" 'BEGIN { printf "%.4f", p - q }')"
done
say "--- pooled, each against $T ---"
echo "$a" | pool "best, play-outs by $A" | tee -a "$SUMMARY"
echo "$b" | pool "best, play-outs by $B" | tee -a "$SUMMARY"
echo "$paired" | pool "    $B minus $A, same seeds" | tee -a "$SUMMARY"
echo "$ref" | pool "best, play-outs by policy-model (the playout study; reference)" | tee -a "$SUMMARY"
say "loop test finished $(date '+%Y-%m-%d %H:%M:%S')"
