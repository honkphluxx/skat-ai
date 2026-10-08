#!/usr/bin/env bash
# One more turn of the loop: best teaches the student that does best's play-outs (docs/training-plan.md 2.10, R).
#
#   ./tools/loop-turn.sh                 turn 2: export, train, arena
#   ./tools/loop-turn.sh --turn=3        the turn after (needs turn 2's student)
#   ./tools/loop-turn.sh --quick         ten boards, two epochs, one seed of 20 boards: the plumbing
#   ./tools/loop-turn.sh --no-union      skip the student trained on two turns' data
#   ./tools/loop-turn.sh --threads=20 --seeds="11 12 13"
#   ./tools/loop-turn.sh --python=/c/Python312/python.exe
#
# Turn 1 (tools/loop-test.sh): best, its play-outs by policy-model, played
# boards 0-29,999 of seed 101 (teacher-data-best); policy-best learnt its
# cards, and best with policy-best's play-outs beat best with policy-model's
# by +0.70 [+0.15, +1.25]. Turn N here:
#
#   1. export   po:belief-64-shipped/rollout-32x4@<turn N-1's student>/
#               lost-decl,lost-def,tie0-decl as the teacher, on the next
#               30,000 deals (boards 30,000(N-1) to 30,000N-1, seed 101, the
#               same seating draw) into teacher-data-bestN/: fresh deals, so
#               no student sees a deal twice
#   2. train    policy-bestN on that alone -- the like-for-like successor of
#               turn N-1's student (same recipe, same amount) -- and, unless
#               --no-union, policy-bestN-u on turn N-1's data and this turn's
#               together (twice the deals: does more data add to the turn?)
#   3. arena    best with each new student doing the play-outs, against
#               belief-64-shipped, fixed contracts, seeds 11-16 and 21; paired
#               by seed with turn N-1's student, whose reports are on disk
#               (the arena is deterministic, so they are not played again)
#
# Each step is skipped when its output exists, so the same command resumes:
# the export a shard at a time, arena reports as .partial until complete;
# touch STOP to stop after the current one. About 14 hours for the export and
# 7.5 for the arena (3.7 with --no-union) at 16 threads; the training takes
# minutes. No SkatZero anywhere. Read arena-logs/summary-loop-turn.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${LN_ORIGINAL:-}" ]; then
    LN_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/loop-turn.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    LN_ORIGINAL="$LN_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$LN_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

TURN=2; QUICK=false; UNION=true; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; EPOCHS=20
DEALS=30000
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --turn=*)     TURN="${arg#*=}" ;;
        --quick)      QUICK=true ;;
        --no-union)   UNION=false ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --epochs=*)   EPOCHS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,36p' "$LN_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ "$TURN" -ge 2 ] 2>/dev/null || { echo "--turn is 2 or more (turn 1 is tools/loop-test.sh)" >&2; exit 2; }
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python ../.venv/bin/python .venv/bin/python; do
        [ -x "$candidate" ] && PYTHON="$candidate" && break
    done
fi
if [ -z "$PYTHON" ] || ! "$PYTHON" -c "import torch" 2>/dev/null; then
    echo "no interpreter with torch found; pass --python=... or set SKATKLAR_PYTHON" >&2
    exit 2
fi

# Turn 1's names are policy-best and teacher-data-best; turn N's add N.
name_of() { [ "$1" -eq 1 ] && echo "$2" || echo "$2$1"; }
PREV=$(name_of $((TURN - 1)) policy-best)
PREV_DATA=$(name_of $((TURN - 1)) teacher-data-best)
NEXT=$(name_of "$TURN" policy-best)
DATA=$(name_of "$TURN" teacher-data-best)
UNION_MODEL="$NEXT-u"; UNION_DATA="$DATA-u"
FIRST=$(( (TURN - 1) * DEALS ))
SHARD=1000
if $QUICK; then
    DEALS=10; SHARD=10; EPOCHS=2; BOARDS=20; SEEDS="11"
    NEXT="$NEXT-quick"; DATA="$DATA-quick"; UNION_MODEL="$UNION_MODEL-quick"; UNION_DATA="$UNION_DATA-quick"
fi
[ -f "$PREV/policy.bin" ] || { echo "no $PREV/policy.bin: turn $((TURN - 1))'s student" >&2; exit 2; }

T=belief-64-shipped
SCOPE=lost-decl,lost-def,tie0-decl
LOG=arena-logs/loop-turn
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-loop-turn.txt
$QUICK && SUMMARY=arena-logs/summary-loop-turn-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "loop turn $TURN started $(date '+%Y-%m-%d %H:%M:%S')   teacher's student $PREV, deals $FIRST-$((FIRST + DEALS - 1)) into $DATA, union=$UNION, epochs=$EPOCHS, boards=$BOARDS, threads=$THREADS, seeds=$SEEDS"

# 1. The export: best with last turn's student, on fresh deals.
TEACHER="po:$T/rollout-32x4@$PREV/$SCOPE"
[ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
say "  [$(date '+%H:%M:%S')] export: $TEACHER"
./gradlew --console=plain -q --no-daemon :arena:exportTeacher \
    --args="--teacher=$TEACHER --boards=$DEALS --first=$FIRST --seed=101 --threads=$THREADS --shard=$SHARD --population=greedy,search-4,club,expert,jskat-new --mix=25,50,25 --passed-in=void --out=$ROOT/$DATA" \
    2>&1 | grep -v "^SkatKlar solver" | tee -a "$LOG/export-$DATA.log" | tail -12 | sed 's/^/      /' | tee -a "$SUMMARY"
expected=$(( (DEALS + SHARD - 1) / SHARD ))
have=$(ls "$DATA"/teacher-v3-s101-*.bin 2>/dev/null | wc -l)
[ "$have" -ge "$expected" ] || { say "  the export has $have of $expected shards -- run the same command again"; exit 1; }

# 2. The students.
if $UNION && ! $QUICK; then
    mkdir -p "$UNION_DATA"
    for g in "$DATA"/teacher-format.json "$DATA"/players.json "$DATA"/teacher.txt "$DATA"/encoding-v*.json; do
        [ -f "$UNION_DATA/$(basename "$g")" ] || cp "$g" "$UNION_DATA/"
    done
    for shard in "$PREV_DATA"/teacher-v3-s101-*.bin "$DATA"/teacher-v3-s101-*.bin; do
        [ -f "$UNION_DATA/$(basename "$shard")" ] && continue
        cp "$shard" "$UNION_DATA/$(basename "$shard").partial" && mv "$UNION_DATA/$(basename "$shard").partial" "$UNION_DATA/$(basename "$shard")"
    done
fi
MODELS=("$NEXT|$DATA")
$UNION && ! $QUICK && MODELS+=("$UNION_MODEL|$UNION_DATA")
for pair in "${MODELS[@]}"; do
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

# 3. The arena: best with each new student, against the teacher.
report_of() {   # the report of best with student $1 on seed $2, wherever a turn wrote it
    for dir in "$LOG" arena-logs/loop-test; do
        [ -f "$dir/best-$1-s$2-b$BOARDS.txt" ] && { echo "$dir/best-$1-s$2-b$BOARDS.txt"; return; }
    done
}
diff_of() { [ -n "$1" ] && [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
for SEED in $SEEDS; do
    for pair in "${MODELS[@]}"; do
        IFS='|' read -r model data <<< "$pair"
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
say "--- pooled, each against $T ---"
base=""
for SEED in $SEEDS; do b=$(diff_of "$(report_of "$PREV" "$SEED")"); [ -n "$b" ] && base="$base $b"; done
echo "$base" | pool "best, play-outs by $PREV (turn $((TURN - 1)))" | tee -a "$SUMMARY"
for pair in "${MODELS[@]}"; do
    IFS='|' read -r model data <<< "$pair"
    own=""; paired=""
    for SEED in $SEEDS; do
        x=$(diff_of "$(report_of "$model" "$SEED")"); y=$(diff_of "$(report_of "$PREV" "$SEED")")
        [ -n "$x" ] && own="$own $x"
        [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$own" | pool "best, play-outs by $model (trained on $data)" | tee -a "$SUMMARY"
    echo "$paired" | pool "    $model minus $PREV, same seeds" | tee -a "$SUMMARY"
done
say "loop turn $TURN finished $(date '+%Y-%m-%d %H:%M:%S')"
