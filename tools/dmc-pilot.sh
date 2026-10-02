#!/usr/bin/env bash
# The Deep Monte-Carlo pilot (docs/training-plan.md 2.10, R): can self-play
# teach the student what imitation could not -- the endgame?
#
#   ./tools/dmc-pilot.sh                     ten iterations of 500,000 games
#   ./tools/dmc-pilot.sh --quick             two iterations of 20,000 games (the plumbing)
#   ./tools/dmc-pilot.sh --iterations=20     more; finished steps are skipped, so this resumes
#   ./tools/dmc-pilot.sh --games=1000000 --epsilon=0.05 --window=3 --epochs=2
#   ./tools/dmc-pilot.sh --python=/c/Python312/python.exe
#
# Each iteration k:
#   1. self-play  net-(k-1) in all three seats plays --games games at fixed
#                 suit and grand contracts (greedy's auction), every card by its
#                 value head, a share --epsilon at random; every decision is
#                 written with whether the decider's side won
#                 (SelfPlayExportMain -> dmc-pilot/data-k, ~3.7 GB a 500,000)
#   2. learn      the value head, at the card played, regressed on that outcome
#                 over the last --window iterations' games (train_dmc.py ->
#                 dmc-pilot/net-k); the held-out loss before and after
#   3. export     policy.bin for the next iteration's actors
#   4. yardstick  net-k against the student on the teacher's held-out deals and
#                 referee worlds (eval_policy.py on teacher-data-v2): regret by
#                 trick, value head rows. The question is tricks 7-9, where the
#                 student plays like a one-world search.
#
# net-00 is policy-model, copied. Self-play data older than the window is
# deleted as the pilot goes (--keep-data keeps it). Read
# arena-logs/summary-dmc.txt; touch STOP to stop after the current step.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DMC_ORIGINAL:-}" ]; then
    DMC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/dmc-pilot.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DMC_ORIGINAL="$DMC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DMC_ORIGINAL")/.." || exit 1

ITERATIONS=10; GAMES=500000; EPSILON=0.02; WINDOW=2; EPOCHS=1; THREADS=""
QUICK=false; KEEP=false; INIT=policy-model; YARDSTICK=teacher-data-v2; DIR=dmc-pilot
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)          QUICK=true ;;
        --iterations=*)   ITERATIONS="${arg#*=}" ;;
        --games=*)        GAMES="${arg#*=}" ;;
        --epsilon=*)      EPSILON="${arg#*=}" ;;
        --window=*)       WINDOW="${arg#*=}" ;;
        --epochs=*)       EPOCHS="${arg#*=}" ;;
        --threads=*)      THREADS="${arg#*=}" ;;
        --init=*)         INIT="${arg#*=}" ;;
        --yardstick=*)    YARDSTICK="${arg#*=}" ;;
        --keep-data)      KEEP=true ;;
        --python=*)       PYTHON="${arg#*=}" ;;
        -h|--help)        sed -n '2,32p' "$DMC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if $QUICK; then ITERATIONS=2; GAMES=20000; DIR=dmc-pilot-quick; fi
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python ../.venv/bin/python .venv/bin/python; do
        [ -x "$candidate" ] && PYTHON="$candidate" && break
    done
fi
if [ -z "$PYTHON" ] || ! "$PYTHON" -c "import torch" 2>/dev/null; then
    echo "no interpreter with torch found; pass --python=... or set SKATKLAR_PYTHON" >&2
    exit 2
fi
for f in policy.pt policy.bin policy-parity.bin model.json; do
    [ -f "$INIT/$f" ] || { echo "$INIT/$f is missing (train_policy.py, then export_policy.py)" >&2; exit 2; }
done
[ -f "$YARDSTICK/teacher-format.json" ] || { echo "no teacher corpus $YARDSTICK for the yardstick" >&2; exit 2; }

LOG=arena-logs
mkdir -p "$LOG" "$DIR"
SUMMARY=$LOG/summary-dmc.txt
$QUICK && SUMMARY=$LOG/summary-dmc-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
net() { printf '%s/net-%02d' "$DIR" "$1"; }
data() { printf '%s/data-%02d' "$DIR" "$1"; }
say "============================================================"
say "dmc pilot started $(date '+%Y-%m-%d %H:%M:%S')   iterations=$ITERATIONS games=$GAMES epsilon=$EPSILON window=$WINDOW epochs=$EPOCHS init=$INIT dir=$DIR"

if [ ! -f "$(net 0)/policy.bin" ]; then
    mkdir -p "$(net 0)"
    cp "$INIT"/policy.pt "$INIT"/policy.bin "$INIT"/policy-parity.bin "$INIT"/model.json "$(net 0)/" || exit 1
    [ -f "$INIT/fixtures.npz" ] && cp "$INIT/fixtures.npz" "$(net 0)/"
    say "  net-00 is $INIT"
fi

for ((k = 1; k <= ITERATIONS; k++)); do
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    prev=$(net $((k - 1))); this=$(net "$k"); out=$(data "$k")
    tag=$(printf '%02d' "$k")

    if [ -f "$out/selfplay-format.json" ]; then
        say "  [skip] self-play $tag -- $out exists"
    elif [ -f "$this/policy.bin" ]; then
        say "  [skip] self-play $tag -- net-$tag exists"
    else
        say "  [$(date '+%H:%M:%S')] self-play $tag: $prev, $GAMES games"
        args="--model=$prev --out=$out --games=$GAMES --iteration=$k --epsilon=$EPSILON"
        [ -n "$THREADS" ] && args="$args --threads=$THREADS"
        if ! ./gradlew --console=plain -q --no-daemon :arena:selfPlayExport --args="$args" \
                > "$LOG/dmc-selfplay-$tag.txt" 2>&1; then
            tail -8 "$LOG/dmc-selfplay-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  SELF-PLAY FAILED -- see $LOG/dmc-selfplay-$tag.txt"; exit 1
        fi
        grep -E "games \(|declarer won" "$LOG/dmc-selfplay-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi

    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    if [ -f "$this/policy.bin" ]; then
        say "  [skip] learn $tag -- $this exists"
    else
        sources=""
        for ((j = k; j > k - WINDOW && j >= 1; j--)); do
            [ -f "$(data "$j")/selfplay-format.json" ] && sources="$sources --data $(data "$j")"
        done
        say "  [$(date '+%H:%M:%S')] learn $tag from$sources"
        # shellcheck disable=SC2086 -- $sources is deliberately word-split
        if ! "$PYTHON" arena/python/train_dmc.py $sources --init "$prev" --out "$this" --epochs "$EPOCHS" \
                > "$LOG/dmc-learn-$tag.txt" 2>&1; then
            tail -8 "$LOG/dmc-learn-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  LEARNING FAILED -- see $LOG/dmc-learn-$tag.txt"; exit 1
        fi
        grep -E "decisions of|held-out loss" "$LOG/dmc-learn-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        sed -n '/^epoch/,$p' "$LOG/dmc-learn-$tag.txt" | grep -E "by trick|loss  |estimate - won" | tail -3 \
            | sed 's/^/    /' | tee -a "$SUMMARY"
        if ! "$PYTHON" arena/python/export_policy.py --model "$this" > "$LOG/dmc-export-$tag.txt" 2>&1; then
            tail -5 "$LOG/dmc-export-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  EXPORT FAILED -- see $LOG/dmc-export-$tag.txt"; exit 1
        fi
    fi

    if [ -f "$LOG/dmc-yardstick-$tag.txt" ] && grep -q "^regret by trick" "$LOG/dmc-yardstick-$tag.txt"; then
        say "  [skip] yardstick $tag -- done"
    else
        say "  [$(date '+%H:%M:%S')] yardstick $tag"
        if ! "$PYTHON" arena/python/eval_policy.py --data "$YARDSTICK" --model "$(net 0)" --model "$this" \
                > "$LOG/dmc-yardstick-$tag.txt" 2>&1; then
            tail -5 "$LOG/dmc-yardstick-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
            say "  YARDSTICK FAILED -- see $LOG/dmc-yardstick-$tag.txt"; exit 1
        fi
    fi
    sed -n '/^regret by trick/,/^    (decisions)/p' "$LOG/dmc-yardstick-$tag.txt" \
        | grep -E "regret by trick|trick 1|student|its value head|search, (1 world|8 worlds|32 worlds)" \
        | sed 's/^/    /' | tee -a "$SUMMARY"

    if ! $KEEP; then
        old=$((k - WINDOW + 1))
        if [ "$old" -ge 1 ] && [ -d "$(data "$old")" ]; then
            rm -rf "$(data "$old")" && say "  removed $(data "$old") (outside the window)"
        fi
    fi
done
say "dmc pilot finished $(date '+%Y-%m-%d %H:%M:%S')"
