#!/usr/bin/env bash
# Route 1, step 2: the student learns from the all-cards labels (docs/training-plan.md 2.10).
#
#   ./tools/outcomes-train.sh --quick              one draw of the first arm, one epoch on a slice; 20 boards of seed 11
#   ./tools/outcomes-train.sh                      round 1: ev and bc, three draws each, the yardstick, the arena
#   ./tools/outcomes-train.sh --round=2            round 2: ev and evu from round 1's ev
#   ./tools/outcomes-train.sh --round=2 --no-arena training and the yardstick only
#   ./tools/outcomes-train.sh --round=3 --from=evu --arms="ev evu" --epochs=10 --threads=16 --python=...
#   ./tools/outcomes-train.sh --round=2 --arms=ev --anchors="1 0.25 0" --no-arena
#                                                  the anchor test: does the imitation anchor cap the gain?
#
# Round r trains on the all-cards corpus allcards-data/r<r>-k8-s<9000+r>
# (tools/allcards-export.sh, its labels made by round r-1's student) and starts
# each draw d (init seed d) from draw d of the round before -- round 1 from
# policy-b600-pa40 and its two re-draws, round r from policy-oc<r-1>-<from>
# (--from, default ev) -- and is paired with it. The arms
# (arena/python/train_outcomes.py):
#
#   ev   policy-oc<r>-ev[-i2|-i3]   --objective expected on round r's corpus:
#                                   raise the expected score (linear in the
#                                   labels, unbiased over deals)
#   evu  policy-oc<r>-evu[-i2|-i3]  the same on every round's corpus so far
#   bc   policy-oc<r>-bc[-i2|-i3]   --objective best on round r's corpus: the
#                                   per-deal best card (the biased comparison)
#
# --arms picks them; round 1 runs ev and bc, later rounds ev and evu.
# --anchors gives the imitation anchor's weights to train each arm at (default
# 1); a weight w other than 1 is its own student, -a<w> in the name with the
# point dropped (policy-oc2-ev-a025, policy-oc2-ev-a0). The training summary
# lists, per arm and weight, the draws' held-out gain of the top card over the
# legal mean at the start and at the kept epoch, and the teacher regret.
# both with the value head on each card's win share, the teacher-data-best-600
# imitation loss as the anchor, suits permuted. The trainer's self-test runs
# first (the objectives on made-up decisions; it stops the script if it fails).
# Then the yardstick (eval_policy.py on teacher-data-best's held-out deals:
# the referee is double-dummy, so a flat yardstick with a gain in games is
# possible), and student-64 with each new student against belief-64-shipped,
# fixed contracts, seeds 11-16 and 21, 200 boards. Reports already on disk
# are reused (pa40's from arena-logs/recipe-check/, earlier rounds' from
# arena-logs/outcomes/). Pooled, the draws averaged seed by seed: each arm,
# minus its start, minus pa40 (from round 2), and every later student set
# minus the first.
#
# Each step is skipped when its output exists (a student counts as trained by
# its model.json); arena reports are written as .partial and renamed; touch
# STOP to stop after the current step. ev runs before bc throughout, so a stop
# leaves ev whole. Training is minutes a student on the GPU; the arena about
# 4 hours an arm at 16 threads. Read arena-logs/summary-outcomes.txt (round 1)
# or arena-logs/summary-outcomes-r<r>.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${OT_ORIGINAL:-}" ]; then
    OT_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/outcomes-train.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    OT_ORIGINAL="$OT_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$OT_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; ARENA=true; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16 21"
ROUND=1; FROM=ev; ARMS=""; ANCHORS="1"; ANCHOR=teacher-data-best-600; EPOCHS=10; PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --no-arena)   ARENA=false ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --round=*)    ROUND="${arg#*=}" ;;
        --from=*)     FROM="${arg#*=}" ;;
        --arms=*)     ARMS="${arg#*=}" ;;
        --anchors=*)  ANCHORS="${arg#*=}" ;;
        --anchor=*)   ANCHOR="${arg#*=}" ;;
        --epochs=*)   EPOCHS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,51p' "$OT_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
case "$ROUND" in ''|*[!0-9]*|0) echo "a round is 1, 2, ..., not $ROUND" >&2; exit 2 ;; esac
data_of() { echo "allcards-data/r$1-k8-s$((9000 + $1))"; }
for ((r = 1; r <= ROUND; r++)); do
    [ -f "$(data_of $r)/allcards-format.json" ] || { echo "no all-cards corpus $(data_of $r) (tools/allcards-export.sh)" >&2; exit 2; }
done
[ -f "$ANCHOR/teacher-format.json" ] || { echo "no anchor corpus $ANCHOR" >&2; exit 2; }
[ -f teacher-data-best/teacher-format.json ] || { echo "no teacher-data-best for the yardstick" >&2; exit 2; }

T=belief-64-shipped
if [ -z "$ARMS" ]; then if [ "$ROUND" = 1 ]; then ARMS="ev bc"; else ARMS="ev evu"; fi; fi
DRAWS="1 2 3"; TRAIN_EXTRA=""; SUFFIX=""
if $QUICK; then
    ARMS="${ARMS%% *}"; DRAWS="1"; EPOCHS=1; BOARDS=20; SEEDS="11"; SUFFIX="-quick"
    TRAIN_EXTRA="--limit 200000 --anchor-limit 400000 --teacher-val 20000"
fi
objective_of() { case "$1" in ev|evu) echo expected ;; bc) echo best ;; *) return 1 ;; esac; }
for arm in $ARMS; do objective_of "$arm" > /dev/null || { echo "unknown arm: $arm (ev, evu, bc)" >&2; exit 2; }; done
for w in $ANCHORS; do
    case "$w" in ''|*[!0-9.]*|*.*.*) echo "an anchor weight is a number, not $w" >&2; exit 2 ;; esac
done
anchor_suffix() { [ "$1" = 1 ] && echo "" || echo "-a${1//./}"; }
VARIANTS=""; for arm in $ARMS; do for w in $ANCHORS; do VARIANTS="$VARIANTS $arm:$w"; done; done
VARIANTS="${VARIANTS# }"
if $QUICK; then VARIANTS="${VARIANTS%% *}"; fi
vname() { echo "${1%%:*}$(anchor_suffix "${1#*:}")"; }   # ev:0.25 -> ev-a025
data_for() {  # arm -> the corpora it trains on
    if [ "$1" = evu ]; then local out="" r; for ((r = 1; r <= ROUND; r++)); do out="$out $(data_of $r)"; done; echo "${out# }"
    else data_of "$ROUND"; fi
}
draw_suffix() { [ "$1" = 1 ] && echo "" || echo "-i$1"; }
model_of() { echo "policy-oc$ROUND-$1$(draw_suffix "$2")$SUFFIX"; }
pa40_of() { echo "policy-b600-pa40$(draw_suffix "$1")"; }
init_of() { if [ "$ROUND" = 1 ]; then pa40_of "$1"; else echo "policy-oc$((ROUND - 1))-$FROM$(draw_suffix "$1")"; fi; }
for d in $DRAWS; do
    for f in model.json policy.pt; do
        [ -f "$(init_of "$d")/$f" ] || { echo "$(init_of "$d")/$f is missing" >&2; exit 2; }
    done
done

LOG=arena-logs/outcomes
mkdir -p "$LOG"
if [ "$ROUND" = 1 ]; then SUMMARY=arena-logs/summary-outcomes; else SUMMARY=arena-logs/summary-outcomes-r$ROUND; fi
$QUICK && SUMMARY="$SUMMARY-quick"
SUMMARY="$SUMMARY.txt"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "outcomes round $ROUND started $(date '+%Y-%m-%d %H:%M:%S')   from=$(init_of 1) anchor=$ANCHOR epochs=$EPOCHS students=$VARIANTS draws=$DRAWS boards=$BOARDS seeds=$SEEDS"

# --- the trainer's self-test -------------------------------------------------
if ! "$PYTHON" arena/python/train_outcomes.py --self-test > "$LOG/self-test.txt" 2>&1; then
    grep FAIL "$LOG/self-test.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  SELF-TEST FAILED -- see $LOG/self-test.txt; nothing trained"; exit 1
fi
say "  self-test passed"

# --- train and export ----------------------------------------------------------
for v in $VARIANTS; do
    arm=${v%%:*}; w=${v#*:}
    for d in $DRAWS; do
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        model=$(model_of "$(vname "$v")" "$d")
        if [ ! -f "$model/model.json" ]; then
            say "  [$(date '+%H:%M:%S')] train $model from $(init_of "$d"), objective $(objective_of "$arm"), anchor weight $w, init seed $d, on $(data_for "$arm")"
            # shellcheck disable=SC2086 -- TRAIN_EXTRA and the corpora are deliberately word-split
            if ! "$PYTHON" arena/python/train_outcomes.py --data $(data_for "$arm") --anchor "$ANCHOR" --init "$(init_of "$d")" \
                    --out "$model" --objective "$(objective_of "$arm")" --epochs "$EPOCHS" --init-seed "$d" --anchor-weight "$w" \
                    $TRAIN_EXTRA > "$LOG/train-$model.txt" 2>&1; then
                tail -8 "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
                say "  TRAINING FAILED -- see $LOG/train-$model.txt"; exit 1
            fi
        fi
        grep -E "^epoch +0 |^kept epoch|held-out gain over" "$LOG/train-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        if [ ! -f "$model/policy.bin" ]; then
            "$PYTHON" arena/python/export_policy.py --model "$model" > "$LOG/export-$model.txt" 2>&1 \
                || { tail -5 "$LOG/export-$model.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1; }
        fi
    done
done

# --- what the training did, per student set ---------------------------------------
say "--- training: held-out gain of the top card over the legal mean (game value a play-out), the draws' mean ---"
for v in $VARIANTS; do
    files=""; for d in $DRAWS; do files="$files $LOG/train-$(model_of "$(vname "$v")" "$d").txt"; done
    # shellcheck disable=SC2086 -- the file list is deliberately word-split
    awk -v name="$(vname "$v") (anchor ${v#*:})" '
        /^kept epoch/ { epochs = epochs " " $3 }
        /held-out gain over the legal mean/ {
            line = $0
            sub(/.*legal mean: /, "", line); split(line, a, " ")
            g0 += a[1]; g1 += a[6]
            sub(/.*teacher regret /, "", line); split(line, t, " ")
            r0 += t[1]; r1 += t[3]; n++
        }
        END { if (n) printf "  %-24s gain %+.3f -> %+.3f (%+.3f)   teacher regret %.3f -> %.3f   kept epochs%s\n", name, g0 / n, g1 / n, (g1 - g0) / n, r0 / n, r1 / n, epochs }
    ' $files | tee -a "$SUMMARY"
done

# --- the yardstick ---------------------------------------------------------------
YARD="$LOG/yardstick-oc$ROUND$SUFFIX.txt"
STUDENTS=""
for d in $DRAWS; do STUDENTS="$STUDENTS $(pa40_of "$d")"; done
[ "$ROUND" = 1 ] || for d in $DRAWS; do STUDENTS="$STUDENTS $(init_of "$d")"; done
for v in $VARIANTS; do for d in $DRAWS; do STUDENTS="$STUDENTS $(model_of "$(vname "$v")" "$d")"; done; done
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
$ARENA || { say "arena: off (--no-arena)"; say "outcomes finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0; }

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
for SEED in $SEEDS; do for d in $DRAWS; do play "$(pa40_of "$d")" "$SEED"; done; done
[ "$ROUND" = 1 ] || for SEED in $SEEDS; do for d in $DRAWS; do play "$(init_of "$d")" "$SEED"; done; done
for v in $VARIANTS; do
    for SEED in $SEEDS; do for d in $DRAWS; do play "$(model_of "$(vname "$v")" "$d")" "$SEED"; done; done
done

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
START=""; PA40=""; for d in $DRAWS; do START="$START $(init_of "$d")"; PA40="$PA40 $(pa40_of "$d")"; done
start_name=$(init_of 1)
say "--- pooled: student-64 against $T, the draws averaged seed by seed ---"
# shellcheck disable=SC2086
[ "$ROUND" = 1 ] || series $PA40 | pool "pa40" | tee -a "$SUMMARY"
# shellcheck disable=SC2086
series $START | pool "$start_name (the start)" | tee -a "$SUMMARY"
lists=(); names=()
for v in $VARIANTS; do
    arm=${v%%:*}; name=$(vname "$v")
    list=""; for d in $DRAWS; do list="$list $(model_of "$name" "$d")"; done
    lists+=("$list"); names+=("$name")
    # shellcheck disable=SC2086
    series $list | pool "$name ($(objective_of "$arm"), anchor ${v#*:}, on $(data_for "$arm" | sed 's|allcards-data/||g'))" | tee -a "$SUMMARY"
    if [ "$ROUND" = 1 ]; then
        paired "$list" "$START" | pool "    $name minus pa40, same seeds and draws" | tee -a "$SUMMARY"
    else
        paired "$list" "$START" | pool "    $name minus its start, same seeds and draws" | tee -a "$SUMMARY"
        paired "$list" "$PA40" | pool "    $name minus pa40, same seeds and draws" | tee -a "$SUMMARY"
    fi
done
for ((i = 1; i < ${#lists[@]}; i++)); do
    paired "${lists[$i]}" "${lists[0]}" | pool "${names[$i]} minus ${names[0]}, same seeds and draws" | tee -a "$SUMMARY"
done
say "outcomes finished $(date '+%Y-%m-%d %H:%M:%S')"
