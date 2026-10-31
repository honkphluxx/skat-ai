#!/usr/bin/env bash
# Longer, and wider, now the suits are permuted? (docs/training-plan.md 2.10, I5)
#
#   ./tools/recipe-check.sh                  the variants on teacher-data-best-150 and the yardstick
#   ./tools/recipe-check.sh --quick           two epochs, one draw: the plumbing
#   ./tools/recipe-check.sh --arena=auto      then the arena for the lowest regret, against pa40
#   ./tools/recipe-check.sh --arena=h1024-pa80
#   ./tools/recipe-check.sh --data=teacher-data-best-600 --arena=auto
#   ./tools/recipe-check.sh --threads=15 --seeds="11 12 13" --python=/c/Python312/python.exe
#   ./tools/recipe-check.sh --data=teacher-data-best-600 --variants="h1024x5-pa80 h2048x4-pa80" --draws=1
#                                             the size test: deeper and wider, one draw each
#
# With the suits permuted (--permute-suits all) 40 epochs paid where it had
# not before, the best epochs at 37-40 of 40, and the student gained +1.23
# in games (2026-10-18). Unpermuted, 3 x 1024 overfitted by epoch 14. Both
# questions again, on the permuted records -- training only, so it can run
# on the GPU beside tools/export-more.sh, which has the cores:
#
#   pa40         3 x 512, 40 epochs: the recipe now (on disk for 150,000 deals)
#   pa60, pa80   3 x 512, 60 and 80 epochs
#   h1024-pa40   3 x 1024, 40 epochs
#   h1024-pa80   3 x 1024, 80 epochs
#
# all with the suits permuted, three init draws each (models policy-b150-pa60,
# -i2, -i3 and so on; on another corpus its number stands for 150). The
# yardstick on teacher-data-best's held-out deals beside the unpermuted
# students; per variant the draws' mean, and the epoch each draw was best at.
# The arena is off by default, as the export holds the cores: --arena=auto
# runs student-64 with the lowest-regret variant against belief-64-shipped,
# fixed contracts, seeds 11-16 and 21, paired seed by seed with pa40 (whose
# reports from tools/permute-check.sh are reused; on another corpus they are
# played too). About 2.5 hours of training at 150,000 deals, more beside the
# export; 4.2 hours of arena a variant at 16 threads.
#
# --variants names others: pa<epochs> for 3 x 512, h<width>[x<layers>]-pa<epochs>
# otherwise (h2048x4-pa80: four layers of 2048, 80 epochs); pa40, the
# yardstick's reference, is always among them. --draws=N trains N init draws
# of a variant not yet on disk; draws already on disk are always read. The
# yardstick is rerun whenever a student is missing from it.
#
# Each step is skipped when its output exists; arena reports are written as
# .partial first; touch STOP to stop after the current one. Read
# arena-logs/summary-recipe-check.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${RC_ORIGINAL:-}" ]; then
    RC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/recipe-check.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    RC_ORIGINAL="$RC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$RC_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; ARENA=none; DATA=teacher-data-best-150
VARIANTS=""; DRAWS=3
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --arena=*)    ARENA="${arg#*=}" ;;
        --data=*)     DATA="${arg#*=}" ;;
        --variants=*) VARIANTS="${arg#*=}" ;;
        --draws=*)    DRAWS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,45p' "$RC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
[ -f "$DATA/teacher-format.json" ] || { echo "no $DATA" >&2; exit 2; }
[ -f teacher-data-best/teacher-format.json ] || { echo "no teacher-data-best (the yardstick's deals)" >&2; exit 2; }
"$PYTHON" arena/python/suit_permutation.py --check-fixtures > /dev/null \
    || { "$PYTHON" arena/python/suit_permutation.py --check-fixtures; echo "the suit permutation fails its fixtures" >&2; exit 2; }

# teacher-data-best-150 -> b150; any other corpus by its own name.
case "$DATA" in teacher-data-best-*) TAG="b${DATA#teacher-data-best-}" ;; *) TAG="${DATA#teacher-data-}" ;; esac
T=belief-64-shipped
SUFFIX=""
$QUICK && { BOARDS=20; SEEDS="11"; SUFFIX="-quick"; }
# variant|model|hidden|layers|epochs|init seed
STUDENTS=()
add() {   # $1 variant: pa<epochs>, or h<width>[x<layers>]-pa<epochs>
    local v=$1 hidden=512 layers=3 e i m
    if [[ $v =~ ^pa([0-9]+)$ ]]; then e=${BASH_REMATCH[1]}
    elif [[ $v =~ ^h([0-9]+)(x([0-9]+))?-pa([0-9]+)$ ]]; then
        hidden=${BASH_REMATCH[1]}; layers=${BASH_REMATCH[3]:-3}; e=${BASH_REMATCH[4]}
    else echo "a variant is pa<epochs> or h<width>[x<layers>]-pa<epochs>, not $v" >&2; exit 2; fi
    $QUICK && e=2
    for i in 1 2 3; do
        m="policy-$TAG-$v"; [ "$i" -gt 1 ] && m="$m-i$i"; m="$m$SUFFIX"
        # Draws beyond --draws only when they are on disk already.
        [ "$i" -gt "$DRAWS" ] && [ ! -f "$m/model.json" ] && continue
        STUDENTS+=("$v|$m|$hidden|$layers|$e|$i")
    done
}
if [ -z "$VARIANTS" ]; then
    VARIANTS="pa40 pa60 pa80 h1024-pa40 h1024-pa80"; $QUICK && VARIANTS="pa40 h1024-pa40"
fi
$QUICK && DRAWS=1
case "$DRAWS" in [123]) ;; *) echo "--draws is 1, 2 or 3, not $DRAWS" >&2; exit 2 ;; esac
case " $VARIANTS " in *" pa40 "*) ;; *) VARIANTS="pa40 $VARIANTS" ;; esac
for v in $VARIANTS; do add "$v"; done
# The unpermuted students on disk (tools/data-scale.sh, tools/capacity-check.sh), for the yardstick.
UNPERMUTED=(policy-best policy-best-i2 policy-best-i3 policy-b150 policy-b150-i2 policy-b150-i3
            policy-b150-e40 policy-b150-e40-i2 policy-b150-e40-i3)
LOG=arena-logs/recipe-check
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-recipe-check.txt
$QUICK && SUMMARY=arena-logs/summary-recipe-check-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "recipe check started $(date '+%Y-%m-%d %H:%M:%S')   data=$DATA boards=$BOARDS threads=$THREADS seeds=$SEEDS arena=$ARENA"

for entry in "${STUDENTS[@]}"; do
    IFS='|' read -r variant model hidden layers epochs init <<< "$entry"
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    if [ ! -f "$model/model.json" ]; then
        say "  [$(date '+%H:%M:%S')] train $model: $layers x $hidden, $epochs epochs, suits permuted, init seed $init"
        if ! "$PYTHON" arena/python/train_policy.py --data "$DATA" --out "$model" --epochs "$epochs" \
                --hidden "$hidden" --layers "$layers" --permute-suits all --init-seed "$init" > "$LOG/train-$model.txt" 2>&1; then
            tail -8 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
        fi
        grep -E "best held-out" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
    if [ ! -f "$model/policy.bin" ]; then
        "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
            || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
    fi
done

YARD="$LOG/yardstick-$TAG$SUFFIX.txt"
# Rerun when a student is missing from it (a later --variants).
if [ -f "$YARD" ]; then
    for entry in "${STUDENTS[@]}"; do
        IFS='|' read -r variant model hidden layers epochs init <<< "$entry"
        grep -qE "^ +student $model +[0-9]" "$YARD" || { say "  the yardstick lacks $model -- rerunning it"; mv "$YARD" "$YARD.old"; break; }
    done
fi
if [ ! -f "$YARD" ]; then
    models=""
    for m in "${UNPERMUTED[@]}"; do [ -f "$m/model.json" ] && models="$models --model $m"; done
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant model hidden layers epochs init <<< "$entry"; models="$models --model $model"; done
    say "  [$(date '+%H:%M:%S')] yardstick on teacher-data-best's held-out deals"
    # shellcheck disable=SC2086 -- $models is deliberately word-split
    "$PYTHON" arena/python/eval_policy.py --data teacher-data-best $models > "$YARD.partial" 2>&1 \
        && mv "$YARD.partial" "$YARD" || say "  YARDSTICK FAILED -- see $YARD.partial"
fi
[ -f "$YARD" ] || exit 1

regret_of() { awk -v m="$1" '/all    declarer/{n++} n==1 && $1 == "student" && $2 == m { print $3; exit }' "$YARD"; }
mean_regret() {
    local sum=0 n=0 x m
    for m in "$@"; do
        x=$(regret_of "$m"); [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
best_epoch_of() { [ -f "$1/model.json" ] && sed -nE 's/.*"best_epoch": ([0-9]+).*/\1/p' "$1/model.json"; }
models_of() {
    local want=$1 out=""
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant model hidden layers epochs init <<< "$entry"; [ "$variant" = "$want" ] && out="$out $model"; done
    echo "$out"
}
variants() {
    local seen="" entry variant
    for entry in "${STUDENTS[@]}"; do IFS='|' read -r variant _ <<< "$entry"; case " $seen " in *" $variant "*) ;; *) seen="$seen $variant" ;; esac; done
    echo "$seen"
}
say "--- the yardstick: held-out regret, all decisions, the draws averaged ---"
say "  unpermuted: 30,000 deals $(mean_regret policy-best policy-best-i2 policy-best-i3)   150,000 at 20 epochs $(mean_regret policy-b150 policy-b150-i2 policy-b150-i3), at 40 $(mean_regret policy-b150-e40 policy-b150-e40-i2 policy-b150-e40-i3)"
best_variant=""; best_regret=""
for v in $(variants); do
    list=$(models_of "$v")
    # shellcheck disable=SC2086
    r=$(mean_regret $list); [ -n "$r" ] || continue
    epochs=""; for m in $list; do epochs="$epochs $(best_epoch_of "$m")"; done
    say "  $v: $r   (best epochs:$epochs)"
    if [ "$v" != pa40 ] && { [ -z "$best_regret" ] || awk -v x="$r" -v y="$best_regret" 'BEGIN { exit !(x < y) }'; }; then
        best_variant=$v; best_regret=$r
    fi
done

# shellcheck disable=SC2086
base_regret=$(mean_regret $(models_of pa40))
case "$ARENA" in
    none) say "arena: none asked for (--arena=auto when the cores are free)"
          say "recipe check finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0 ;;
    auto)
        if [ -z "$best_variant" ] || { ! $QUICK && ! awk -v x="$best_regret" -v y="$base_regret" 'BEGIN { exit !(x < y) }'; }; then
            say "arena: no variant beats pa40 ($base_regret) on the yardstick -- none run"
            say "recipe check finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0
        fi
        ARENA=$best_variant
        say "arena: $ARENA, the lowest regret ($best_regret against pa40's $base_regret)" ;;
    *)
        [ -n "$(models_of "$ARENA")" ] || { say "no variant $ARENA"; exit 2; }
        say "arena: $ARENA, as asked" ;;
esac
ARENA_MODELS=$(models_of "$ARENA")
BASE_MODELS=$(models_of pa40)

for SEED in $SEEDS; do
    for model in $ARENA_MODELS $BASE_MODELS; do
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        tag="student-$model-s$SEED-b$BOARDS"
        [ -f "arena-logs/permute-check/$tag.txt" ] && continue   # pa40 at 150,000, played there
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
mean_of() {
    local seed=$1; shift; local sum=0 n=0 x m d
    for m in "$@"; do
        x=""
        for d in "$LOG" arena-logs/permute-check; do
            x=$(diff_of "$d/student-$m-s$seed-b$BOARDS.txt"); [ -n "$x" ] && break
        done
        [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
say "--- pooled: student-64 against $T, the draws averaged seed by seed ---"
own=""; base=""; paired=""
for SEED in $SEEDS; do
    # shellcheck disable=SC2086 -- the model lists are deliberately word-split
    x=$(mean_of "$SEED" $ARENA_MODELS); y=$(mean_of "$SEED" $BASE_MODELS)
    [ -n "$x" ] && own="$own $x"
    [ -n "$y" ] && base="$base $y"
    [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
done
echo "$base" | pool "pa40" | tee -a "$SUMMARY"
echo "$own" | pool "$ARENA" | tee -a "$SUMMARY"
echo "$paired" | pool "    $ARENA minus pa40, same seeds" | tee -a "$SUMMARY"
say "recipe check finished $(date '+%Y-%m-%d %H:%M:%S')"
