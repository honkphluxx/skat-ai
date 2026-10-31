#!/usr/bin/env bash
# Is a permutation of the suits worth more deals? (docs/training-plan.md 2.10, I5)
#
#   ./tools/permute-check.sh                 train, yardstick, then the arena for the best 150,000 variant
#   ./tools/permute-check.sh --quick          two epochs, one draw, one seed of 20 boards: the plumbing
#   ./tools/permute-check.sh --arena=none     training and yardstick only
#   ./tools/permute-check.sh --arena=pa40     the arena for that variant, whatever the yardstick says
#   ./tools/permute-check.sh --threads=20 --seeds="11 12 13" --python=/c/Python312/python.exe
#
# The student is short of data (2026-10-18), and the encoding tells the suits
# apart where Skat does not. train_policy.py --permute-suits draws, for every
# training record and every epoch, a permutation the contract admits -- the
# side suits of a suit game, all four (the jacks kept) in a grand or Ramsch,
# whole suits in a Null -- and moves the inputs and the targets with it
# (arena/python/suit_permutation.py, checked against the Java encoder's own
# pairs and the corpus checks before it trains). Two modes: 'all' every such
# permutation, 'bids' only those that leave each bid's suits where they were
# (a bid of 22 says spades); 'bids' leaves about three a record, 'all' eight.
#
#   30,000 deals (teacher-data-best)      pa20 pa40 pb20 pb40   (a = all, b = bids; epochs)
#   150,000 deals (teacher-data-best-150) b150-pa20 b150-pa40 b150-pb20
#
# Three training draws each (init seeds 1-3). The yardstick on teacher-data-best's
# held-out deals beside the unpermuted students of the data-scale and capacity
# runs (30,000, 60,000 and 150,000 deals, 20 epochs; 150,000 at 40), and each
# variant's regret read off that curve as the deals it is worth unpermuted.
# Then the arena for one 150,000-deal variant -- by default the one with the
# lowest regret, and none if no variant beats the unpermuted students --
# student-64 against belief-64-shipped, fixed contracts, seeds 11-16 and 21,
# paired with the unpermuted students of the same epochs. About 1.2 hours of
# training on the GPU and 4.2 of arena at 16 threads.
#
# Each step is skipped when its output exists; arena reports are written as
# .partial first; touch STOP to stop after the current one. Read
# arena-logs/summary-permute-check.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${PC_ORIGINAL:-}" ]; then
    PC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/permute-check.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    PC_ORIGINAL="$PC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$PC_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; ARENA=auto
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --arena=*)    ARENA="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,37p' "$PC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
for d in teacher-data-best teacher-data-best-150; do
    [ -f "$d/teacher-format.json" ] || { echo "no $d (tools/best-run.sh and tools/data-scale.sh build them)" >&2; exit 2; }
done
"$PYTHON" arena/python/suit_permutation.py --check-fixtures > /dev/null \
    || { "$PYTHON" arena/python/suit_permutation.py --check-fixtures; echo "the suit permutation fails its fixtures" >&2; exit 2; }

T=belief-64-shipped
D30=teacher-data-best; D150=teacher-data-best-150
SUFFIX=""
$QUICK && { BOARDS=20; SEEDS="11"; SUFFIX="-quick"; }
# variant|model|data|epochs|mode|init seed
STUDENTS=()
add() {   # $1 variant, $2 model stem, $3 data, $4 epochs, $5 mode
    local draws="1 2 3"; $QUICK && draws="1"
    local e=$4; $QUICK && e=2
    for i in $draws; do
        local m="$2"; [ "$i" -gt 1 ] && m="$m-i$i"
        STUDENTS+=("$1|$m$SUFFIX|$3|$e|$5|$i")
    done
}
if $QUICK; then
    add pa20 policy-best-pa20 $D30 20 all; add pb20 policy-best-pb20 $D30 20 bids
    add b150-pa20 policy-b150-pa20 $D150 20 all
else
    add pa20 policy-best-pa20 $D30 20 all; add pa40 policy-best-pa40 $D30 40 all
    add pb20 policy-best-pb20 $D30 20 bids; add pb40 policy-best-pb40 $D30 40 bids
    add b150-pa20 policy-b150-pa20 $D150 20 all; add b150-pa40 policy-b150-pa40 $D150 40 all
    add b150-pb20 policy-b150-pb20 $D150 20 bids
fi
# The unpermuted students on disk (tools/data-scale.sh, tools/capacity-check.sh): the curve.
B30=(policy-best policy-best-i2 policy-best-i3)
B60=(policy-best2-u policy-b60-i2 policy-b60-i3)
B150=(policy-b150 policy-b150-i2 policy-b150-i3)
B150E40=(policy-b150-e40 policy-b150-e40-i2 policy-b150-e40-i3)
LOG=arena-logs/permute-check
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-permute-check.txt
$QUICK && SUMMARY=arena-logs/summary-permute-check-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "permute check started $(date '+%Y-%m-%d %H:%M:%S')   boards=$BOARDS threads=$THREADS seeds=$SEEDS arena=$ARENA"

for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r variant model data epochs mode init <<< "$entry"
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    if [ ! -f "$model/model.json" ]; then
        say "  [$(date '+%H:%M:%S')] train $model: $data, $epochs epochs, suits permuted ($mode), init seed $init"
        if ! "$PYTHON" arena/python/train_policy.py --data "$data" --out "$model" --epochs "$epochs" \
                --permute-suits "$mode" --init-seed "$init" > "$LOG/train-$model.txt" 2>&1; then
            tail -8 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
        fi
        grep -E "permutations a record may take|best held-out" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
    if [ ! -f "$model/policy.bin" ]; then
        "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
            || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
    fi
done

YARD="$LOG/yardstick$SUFFIX.txt"
if [ ! -f "$YARD" ]; then
    models=""
    for m in "${B30[@]}" "${B60[@]}" "${B150[@]}" "${B150E40[@]}"; do [ -f "$m/model.json" ] && models="$models --model $m"; done
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant model data epochs mode init <<< "$entry"; models="$models --model $model"; done
    say "  [$(date '+%H:%M:%S')] yardstick on teacher-data-best's held-out deals"
    # shellcheck disable=SC2086 -- $models is deliberately word-split
    "$PYTHON" arena/python/eval_policy.py --data teacher-data-best $models > "$YARD.partial" 2>&1 \
        && mv "$YARD.partial" "$YARD" || say "  YARDSTICK FAILED -- see $YARD.partial"
fi
[ -f "$YARD" ] || exit 1

# A model's held-out regret, all decisions: the first table of the yardstick.
regret_of() { awk -v m="$1" '/all    declarer/{n++} n==1 && $1 == "student" && $2 == m { print $3; exit }' "$YARD"; }
mean_regret() {   # the models' mean regret, over those in the yardstick
    local sum=0 n=0 x m
    for m in "$@"; do
        x=$(regret_of "$m"); [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
models_of() {
    local want=$1 out=""
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant model data epochs mode init <<< "$entry"; [ "$variant" = "$want" ] && out="$out $model"; done
    echo "$out"
}
variants() {
    local seen="" entry variant
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant _ <<< "$entry"; case " $seen " in *" $variant "*) ;; *) seen="$seen $variant" ;; esac; done
    echo "$seen"
}
r30=$(mean_regret "${B30[@]}"); r60=$(mean_regret "${B60[@]}"); r150=$(mean_regret "${B150[@]}"); r150e40=$(mean_regret "${B150E40[@]}")
# Regret read off the unpermuted curve as deals: piecewise linear in log2(deals),
# the last segment extended beyond 150,000.
worth() {
    awk -v r="$1" -v a="$r30" -v b="$r60" -v c="$r150" 'BEGIN {
        if (a == "" || b == "" || c == "") { print "?"; exit }
        x1 = log(30000) / log(2); x2 = log(60000) / log(2); x3 = log(150000) / log(2)
        if (r >= b) x = x1 + (a - r) / (a - b) * (x2 - x1)
        else        x = x2 + (b - r) / (b - c) * (x3 - x2)
        printf "%.0f,000", 2 ^ x / 1000
    }'
}
say "--- the yardstick: held-out regret, all decisions, three draws averaged ---"
say "  unpermuted, 20 epochs: 30,000 deals $r30   60,000 $r60   150,000 $r150   (150,000 at 40 epochs: $r150e40)"
best_variant=""; best_regret=""
for v in $(variants); do
    r=$(mean_regret $(models_of "$v")); [ -n "$r" ] || continue
    say "  $v: $r   -- worth about $(worth "$r") deals unpermuted"
    case "$v" in b150-*)
        if [ -z "$best_regret" ] || awk -v x="$r" -v y="$best_regret" 'BEGIN { exit !(x < y) }'; then best_variant=$v; best_regret=$r; fi ;;
    esac
done

case "$ARENA" in
    none) say "arena: none asked for"; say "permute check finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0 ;;
    auto)
        base=$r150; case "$best_variant" in *40) base=$r150e40 ;; esac
        # --quick: two epochs never beat the real students; run its arena anyway, it is the plumbing.
        $QUICK && [ -n "$best_variant" ] && base=9
        if [ -z "$best_variant" ] || [ -z "$base" ] || ! awk -v x="$best_regret" -v y="$base" 'BEGIN { exit !(x < y) }'; then
            say "arena: no 150,000-deal variant beats the unpermuted students on the yardstick -- none run"
            say "permute check finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0
        fi
        ARENA=$best_variant
        if $QUICK; then say "arena: $ARENA (--quick: the plumbing)"
        else say "arena: $ARENA, the lowest regret ($best_regret against $base unpermuted)"; fi ;;
    *)
        case "$ARENA" in b150-*) ;; *) ARENA="b150-$ARENA" ;; esac
        [ -n "$(models_of "$ARENA")" ] || { say "no 150,000-deal variant $ARENA"; exit 2; }
        say "arena: $ARENA, as asked" ;;
esac
ARENA_MODELS=$(models_of "$ARENA")
case "$ARENA" in *40) PAIRED=("${B150E40[@]}"); PAIRED_LABEL="the unpermuted 40-epoch students" ;;
                 *)   PAIRED=("${B150[@]}"); PAIRED_LABEL="the unpermuted 20-epoch students" ;; esac

for SEED in $SEEDS; do
    for model in $ARENA_MODELS; do
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
# One seed's result averaged over a list of models, their reports wherever the runs put them.
mean_of() {
    local seed=$1; shift; local sum=0 n=0 x m d
    for m in "$@"; do
        x=""
        for d in "$LOG" arena-logs/capacity-check arena-logs/data-scale; do
            x=$(diff_of "$d/student-$m-s$seed-b$BOARDS.txt"); [ -n "$x" ] && break
        done
        [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
say "--- pooled: student-64 against $T, the draws averaged seed by seed ---"
# shellcheck disable=SC2086 -- the model lists are deliberately word-split
own=""; base=""; paired=""
for SEED in $SEEDS; do
    x=$(mean_of "$SEED" $ARENA_MODELS); y=$(mean_of "$SEED" "${PAIRED[@]}")
    [ -n "$x" ] && own="$own $x"
    [ -n "$y" ] && base="$base $y"
    [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
done
echo "$base" | pool "$PAIRED_LABEL" | tee -a "$SUMMARY"
echo "$own" | pool "$ARENA" | tee -a "$SUMMARY"
echo "$paired" | pool "    $ARENA minus $PAIRED_LABEL, same seeds" | tee -a "$SUMMARY"
say "permute check finished $(date '+%Y-%m-%d %H:%M:%S')"
