#!/usr/bin/env bash
# Route 1: does a wider net learn more from the outcome labels? (docs/training-plan.md 2.10)
#
#   ./tools/outcomes-size.sh --quick       both nets, one draw, one epoch on a slice; 20 boards of seed 11
#   ./tools/outcomes-size.sh --no-arena    stage 1: training and the yardstick only (the gate)
#   ./tools/outcomes-size.sh               stage 2: the arena as well (training and yardstick reused)
#
# Two nets, one recipe: --objective expected, the teacher-data-best-600 anchor
# at weight 1, suits permuted, the value head on the win share, 10 epochs, the
# epoch kept by held-out gain -- on the union of the student-finished corpora
# of rounds 1-3 (allcards-data/r1-k8-s9001, r2-k8-s9002, r3-k8-s9003; round
# 4's search-finished labels left out), about 6 million decisions:
#
#   w512   policy-ocu3-w512[-i2|-i3]    3 x 512, from policy-b600-pa40[-i2|-i3]
#   w1024  policy-ocu3-w1024[-i2|-i3]   3 x 1024, from policy-b600-h1024-pa80[-i2|-i3]
#
# --nets picks them (default both). Under imitation the wider start was
# +0.15 [-0.53, +0.83] over pa40 in games; the question here is whether it
# gains more from the outcomes. The held-out decisions are the corpora's own
# board split, the same for both nets, so the training summary's gains compare
# directly: stage 1's gate is the wider net's gain clearly above the narrower's.
#
# The yardstick (eval_policy.py on teacher-data-best's held-out deals) beside
# the starts and policy-oc3-ev, route 1's best student; then student-64 with
# each new student against belief-64-shipped, fixed contracts, seeds 11-16 and
# 21, 200 boards. The starts' and oc3-ev's reports are reused from
# arena-logs/recipe-check/ and arena-logs/outcomes/. Pooled, the draws
# averaged seed by seed: each net, minus its start, w1024 minus w512, the
# difference of the two gains ((w1024 - its start) - (w512 - its start): what
# the outcomes add to the wider net beyond the narrower, the starts' gap taken
# out), and each net minus policy-oc3-ev.
#
# Each step is skipped when its output exists (a student counts as trained by
# its model.json); arena reports are written as .partial and renamed; touch
# STOP to stop after the current step. Read arena-logs/summary-outcomes-size.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${OS_ORIGINAL:-}" ]; then
    OS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/outcomes-size.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    OS_ORIGINAL="$OS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$OS_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; ARENA=true; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"; NETS="w512 w1024"
ANCHOR=teacher-data-best-600; EPOCHS=10; PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --no-arena)   ARENA=false ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --nets=*)     NETS="${arg#*=}" ;;
        --anchor=*)   ANCHOR="${arg#*=}" ;;
        --epochs=*)   EPOCHS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,36p' "$OS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python \
                     ../.venv/bin/python .venv/bin/python; do
        [ -x "$candidate" ] && PYTHON="$candidate" && break
    done
fi
if [ -z "$PYTHON" ] || ! "$PYTHON" -c "import torch" 2>/dev/null; then
    echo "no interpreter with torch found; pass --python=... or set SKATKLAR_PYTHON" >&2
    exit 2
fi
for n in $EPOCHS $THREADS; do case "$n" in ''|*[!0-9]*|0) echo "not a positive whole number: $n" >&2; exit 2 ;; esac; done
start_base() { case "$1" in w512) echo policy-b600-pa40 ;; w1024) echo policy-b600-h1024-pa80 ;; *) return 1 ;; esac; }
[ -n "$NETS" ] || { echo "--nets is empty" >&2; exit 2; }
for net in $NETS; do start_base "$net" > /dev/null || { echo "unknown net: $net (w512, w1024)" >&2; exit 2; }; done
DATA="allcards-data/r1-k8-s9001 allcards-data/r2-k8-s9002 allcards-data/r3-k8-s9003"
for c in $DATA; do
    [ -f "$c/allcards-format.json" ] || { echo "no all-cards corpus $c (tools/allcards-export.sh)" >&2; exit 2; }
done
[ -f "$ANCHOR/teacher-format.json" ] || { echo "no anchor corpus $ANCHOR" >&2; exit 2; }
[ -f teacher-data-best/teacher-format.json ] || { echo "no teacher-data-best for the yardstick" >&2; exit 2; }

T=belief-64-shipped
BEST=policy-oc3-ev
DRAWS="1 2 3"; TRAIN_EXTRA=""; SUFFIX=""
if $QUICK; then
    DRAWS="1"; EPOCHS=1; BOARDS=20; SEEDS="11"; SUFFIX="-quick"
    TRAIN_EXTRA="--limit 200000 --anchor-limit 400000 --teacher-val 20000"
fi
draw_suffix() { [ "$1" = 1 ] && echo "" || echo "-i$1"; }
model_of() { echo "policy-ocu3-$1$(draw_suffix "$2")$SUFFIX"; }   # net draw
init_of() { echo "$(start_base "$1")$(draw_suffix "$2")"; }        # net draw
best_of() { echo "$BEST$(draw_suffix "$1")"; }
for net in $NETS; do
    for d in $DRAWS; do
        for f in model.json policy.pt policy.bin; do
            [ -f "$(init_of "$net" "$d")/$f" ] || { echo "$(init_of "$net" "$d")/$f is missing" >&2; exit 2; }
        done
    done
done
for d in $DRAWS; do [ -f "$(best_of "$d")/policy.bin" ] || { echo "$(best_of "$d")/policy.bin is missing" >&2; exit 2; }; done

LOG=arena-logs/outcomes
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-outcomes-size$SUFFIX.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "outcomes size test started $(date '+%Y-%m-%d %H:%M:%S')   nets=$NETS anchor=$ANCHOR epochs=$EPOCHS draws=$DRAWS boards=$BOARDS seeds=$SEEDS on ${DATA//allcards-data\//}"

# --- the trainer's self-test -------------------------------------------------
if ! "$PYTHON" arena/python/train_outcomes.py --self-test > "$LOG/self-test.txt" 2>&1; then
    grep FAIL "$LOG/self-test.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  SELF-TEST FAILED -- see $LOG/self-test.txt; nothing trained"; exit 1
fi
say "  self-test passed"

# --- train and export ----------------------------------------------------------
for net in $NETS; do
    for d in $DRAWS; do
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        model=$(model_of "$net" "$d")
        if [ ! -f "$model/model.json" ]; then
            say "  [$(date '+%H:%M:%S')] train $model from $(init_of "$net" "$d"), objective expected, anchor weight 1, init seed $d"
            start=$(date +%s)
            # shellcheck disable=SC2086 -- TRAIN_EXTRA and the corpora are deliberately word-split
            if ! "$PYTHON" arena/python/train_outcomes.py --data $DATA --anchor "$ANCHOR" --init "$(init_of "$net" "$d")" \
                    --out "$model" --objective expected --epochs "$EPOCHS" --init-seed "$d" --anchor-weight 1 \
                    $TRAIN_EXTRA > "$LOG/train-$model.txt" 2>&1; then
                tail -8 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
                say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
            fi
            say "      took $(( ($(date +%s) - start) / 60 )) min"
        fi
        grep -E "^epoch +0 |^kept epoch|held-out gain over" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        if [ ! -f "$model/policy.bin" ]; then
            "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
                || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
        fi
    done
done

# --- what the training did, per net -----------------------------------------------
say "--- training: held-out gain of the top card over the legal mean (game value a play-out), the draws' mean ---"
for net in $NETS; do
    files=""; for d in $DRAWS; do files="$files $LOG/train-$(model_of "$net" "$d").txt"; done
    # shellcheck disable=SC2086 -- the file list is deliberately word-split
    awk -v name="$net (from $(start_base "$net"))" '
        /^kept epoch/ { epochs = epochs " " $3 }
        /held-out gain over the legal mean/ {
            line = $0
            sub(/.*legal mean: /, "", line); split(line, a, " ")
            g0 += a[1]; g1 += a[6]
            sub(/.*teacher regret /, "", line); split(line, t, " ")
            r0 += t[1]; r1 += t[3]; n++
        }
        END { if (n) printf "  %-36s gain %+.3f -> %+.3f (%+.3f)   teacher regret %.3f -> %.3f   kept epochs%s\n", name, g0 / n, g1 / n, (g1 - g0) / n, r0 / n, r1 / n, epochs }
    ' $files | tee -a "$SUMMARY"
done

# --- the yardstick ---------------------------------------------------------------
YARD="$LOG/yardstick-size$SUFFIX.txt"
STUDENTS=""
for net in $NETS; do for d in $DRAWS; do STUDENTS="$STUDENTS $(init_of "$net" "$d")"; done; done
for d in $DRAWS; do STUDENTS="$STUDENTS $(best_of "$d")"; done
for net in $NETS; do for d in $DRAWS; do STUDENTS="$STUDENTS $(model_of "$net" "$d")"; done; done
if [ -f "$YARD" ]; then
    for m in $STUDENTS; do
        grep -qE "^ +student $m +[0-9]" "$YARD" || { say "  the yardstick lacks $m -- rerunning it"; mv "$YARD" "$YARD.old"; break; }
    done
fi
if [ ! -f "$YARD" ]; then
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    models=""; for m in $STUDENTS; do models="$models --model $m"; done
    say "  [$(date '+%H:%M:%S')] yardstick on teacher-data-best's held-out deals"
    # shellcheck disable=SC2086 -- $models is deliberately word-split
    "$PYTHON" arena/python/eval_policy.py --data teacher-data-best $models > "$YARD.partial" 2>&1 \
        && mv "$YARD.partial" "$YARD" || { say "  YARDSTICK FAILED -- see $YARD.partial"; exit 1; }
fi
say "--- the yardstick: held-out regret in worlds of 32 (all, declarer, defender, tricks 1-3, 4-6, 7-9) ---"
awk '/all    declarer/{n++} n==1 && $1 == "student"' "$YARD" | sed 's/^/  /' | tee -a "$SUMMARY"
$ARENA || { say "arena: off (--no-arena)"; say "outcomes size test finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0; }

# --- the arena -------------------------------------------------------------------
report_of() {  # model seed -> an existing report, here or from the recipe check
    local tag="student-$1-s$2-b$BOARDS"
    for dir in "$LOG" arena-logs/recipe-check; do
        [ -f "$dir/$tag.txt" ] && { echo "$dir/$tag.txt"; return; }
    done
}
play() {  # model seed
    local model=$1 SEED=$2 tag="student-$1-s$2-b$BOARDS"
    [ -n "$(report_of "$model" "$SEED")" ] && return
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] $tag"
    local start=$(date +%s) report="$LOG/$tag.txt"
    ./gradlew --console=plain -q --no-daemon -Dpolicy.model.dir="$ROOT/$model" :arena:arena \
        --args="--a=student-64 --b=$T --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; return; }
    mv "$report.partial" "$report"
    say "      took $(( ($(date +%s) - start) / 60 )) min"
    grep -E " = .*game pts/game" "$report" | sed "s/^/      $model: /" | tee -a "$SUMMARY"
}
for net in $NETS; do for SEED in $SEEDS; do for d in $DRAWS; do play "$(init_of "$net" "$d")" "$SEED"; done; done; done
for SEED in $SEEDS; do for d in $DRAWS; do play "$(best_of "$d")" "$SEED"; done; done
for net in $NETS; do for SEED in $SEEDS; do for d in $DRAWS; do play "$(model_of "$net" "$d")" "$SEED"; done; done; done

diff_of() { [ -n "$1" ] && [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
mean_of() {  # seed models... -> the draws' mean on that seed
    local seed=$1; shift; local sum=0 n=0 x m
    for m in "$@"; do
        x=$(diff_of "$(report_of "$m" "$seed")"); [ -n "$x" ] || continue
        sum=$(awk -v a="$sum" -v b="$x" 'BEGIN { printf "%.6f", a + b }'); n=$((n + 1))
    done
    [ "$n" -gt 0 ] && awk -v a="$sum" -v n="$n" 'BEGIN { printf "%.4f", a / n }'
}
pool() {
    awk -v label="$1" '{
        n = NF; if (n < 2) { printf "  %s: %d seed(s), nothing to pool\n", label, n; exit }
        for (i = 1; i <= n; i++) sum += $i; mean = sum / n
        for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
        se = sqrt(ss / (n - 1)) / sqrt(n)
        split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
        printf "  %s, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]   per seed%s\n", label, n, mean, mean - q * se, mean + q * se, $0
    }'
}
series() {  # models... -> the draws' mean seed by seed
    local out="" SEED x
    for SEED in $SEEDS; do x=$(mean_of "$SEED" "$@"); [ -n "$x" ] && out="$out $x"; done
    echo "$out"
}
paired() {  # "models a" "models b" -> a minus b seed by seed, both draw-averaged
    local out="" SEED x y
    for SEED in $SEEDS; do
        # shellcheck disable=SC2086 -- the model lists are deliberately word-split
        x=$(mean_of "$SEED" $1); y=$(mean_of "$SEED" $2)
        [ -n "$x" ] && [ -n "$y" ] && out="$out $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$out"
}
gains() {  # "a" "a's start" "b" "b's start" -> (a - its start) - (b - its start) seed by seed
    local out="" SEED w x y z
    for SEED in $SEEDS; do
        # shellcheck disable=SC2086 -- the model lists are deliberately word-split
        w=$(mean_of "$SEED" $1); x=$(mean_of "$SEED" $2); y=$(mean_of "$SEED" $3); z=$(mean_of "$SEED" $4)
        [ -n "$w" ] && [ -n "$x" ] && [ -n "$y" ] && [ -n "$z" ] \
            && out="$out $(awk -v a="$w" -v b="$x" -v c="$y" -v d="$z" 'BEGIN { printf "%.4f", (a - b) - (c - d) }')"
    done
    echo "$out"
}
list_of() { local out="" d; for d in $DRAWS; do out="$out $("$@" "$d")"; done; echo "${out# }"; }
BESTS=$(list_of best_of)
say "--- pooled: student-64 against $T, the draws averaged seed by seed ---"
for net in $NETS; do
    # shellcheck disable=SC2086
    series $(list_of init_of "$net") | pool "$(start_base "$net") (the $net start)" | tee -a "$SUMMARY"
done
# shellcheck disable=SC2086
series $BESTS | pool "$BEST (route 1's best so far)" | tee -a "$SUMMARY"
for net in $NETS; do
    trained=$(list_of model_of "$net"); started=$(list_of init_of "$net")
    # shellcheck disable=SC2086
    series $trained | pool "$net (expected, anchor 1, on r1-r3)" | tee -a "$SUMMARY"
    paired "$trained" "$started" | pool "    $net minus its start, same seeds and draws" | tee -a "$SUMMARY"
    paired "$trained" "$BESTS" | pool "    $net minus $BEST, same seeds and draws" | tee -a "$SUMMARY"
done
case " $NETS " in *" w512 "*) case " $NETS " in *" w1024 "*)
    paired "$(list_of model_of w1024)" "$(list_of model_of w512)" | pool "w1024 minus w512, same seeds and draws" | tee -a "$SUMMARY"
    gains "$(list_of model_of w1024)" "$(list_of init_of w1024)" "$(list_of model_of w512)" "$(list_of init_of w512)" \
        | pool "the gains: (w1024 - its start) - (w512 - its start)" | tee -a "$SUMMARY"
;; esac ;; esac
say "outcomes size test finished $(date '+%Y-%m-%d %H:%M:%S')"
