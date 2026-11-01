#!/usr/bin/env bash
# The all-cards self-play exporter, route 1's training data (docs/training-plan.md 2.10).
#
#   ./tools/allcards-export.sh --quick        one shard of 2,000 boards at k 16: the throughput, and whether 8 is enough
#   ./tools/allcards-export.sh                100,000 boards at k 8 (50 shards), then the checks
#   ./tools/allcards-export.sh --boards=300000 --k=8 --seed=9001 --threads=24 --model=policy-b600-pa40
#   ./tools/allcards-export.sh --python=/c/Python312/python.exe
#
# The student (--model) plays all three seats at greedy's fixed suit and grand
# contracts, boards of --seed (never below 1000: 101 is the teacher corpora's,
# 11-16 and 21 the arena's). The main line takes the policy head's top card,
# a fifth of the time a card drawn from its softmax (--epsilon=0.2); the game
# only finds the positions. At every decision with a choice each legal card
# is played out --k times on the table's real cards, every seat by the
# student from its own evidence, the decider's side by the top card, the
# other side drawn (temperature 1); the decision is written with each card's
# wins, card points and game value from the decider's side
# (AllCardsExportMain). Then arena/python/allcards_data.py checks every shard
# against the exporter's own totals and the records as a whole.
#
# The Java side prints, by trick, the cost and the noise that decide k: the
# spread of a card's play-outs, the spread between cards, and how often the
# first k/2 play-outs pick the same best card as the other k/2. In --quick
# (k 16) that last column is what k 8 would give: if it is high where the
# cards' values are close, 8 is enough.
#
# Writes allcards-data/<run>/ (quick: allcards-data/quick-k16/), the logs in
# arena-logs/allcards/, the summary in arena-logs/summary-allcards.txt
# (-quick). Shards are complete or absent (.partial until renamed), a rerun
# carries on where it stopped, and a STOP file stops before the next shard.
# Refuses to start when the disk would keep less than 10 GB free. About 390
# bytes a decision, 23 decisions a game; the quick run measures the speed.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${AC_ORIGINAL:-}" ]; then
    AC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/allcards-export.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    AC_ORIGINAL="$AC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$AC_ORIGINAL")/.." || exit 1

QUICK=false; BOARDS=100000; K=8; SEED=9001; EPSILON=0.2; THREADS=24; MODEL=policy-b600-pa40; OUT=""
SHARD=2000; PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --boards=*)    BOARDS="${arg#*=}" ;;
        --k=*)         K="${arg#*=}" ;;
        --seed=*)      SEED="${arg#*=}" ;;
        --epsilon=*)   EPSILON="${arg#*=}" ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --model=*)     MODEL="${arg#*=}" ;;
        --out=*)       OUT="${arg#*=}" ;;
        --python=*)    PYTHON="${arg#*=}" ;;
        -h|--help)     sed -n '2,34p' "$AC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if $QUICK; then BOARDS=$SHARD; K=16; fi
for n in "$BOARDS" "$K" "$SEED" "$THREADS"; do
    case "$n" in ''|*[!0-9]*) echo "not a whole number: $n" >&2; exit 2 ;; esac
done
[ "$SEED" -ge 1000 ] || { echo "seed $SEED is below 1000: those boards are the teacher's and the arena's" >&2; exit 2; }
for f in model.json policy.bin policy-parity.bin; do
    [ -f "$MODEL/$f" ] || { echo "$MODEL/$f is missing: not a trained, exported student" >&2; exit 2; }
done
if [ -z "$OUT" ]; then
    if $QUICK; then OUT=allcards-data/quick-k$K; else OUT=allcards-data/r1-k$K-s$SEED; fi
fi
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python \
                     ../.venv/bin/python .venv/bin/python python3 python; do
        command -v "$candidate" > /dev/null 2>&1 && PYTHON="$candidate" && break
    done
fi
"$PYTHON" -c "import numpy" 2>/dev/null || { echo "no python with numpy ($PYTHON); pass --python=..." >&2; exit 2; }

LOG=arena-logs/allcards
mkdir -p "$LOG" "$OUT"
NAME=$(basename "$OUT")
SUMMARY=arena-logs/summary-allcards.txt
$QUICK && SUMMARY=arena-logs/summary-allcards-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "all-cards export started $(date '+%Y-%m-%d %H:%M:%S')   $OUT: model=$MODEL boards=$BOARDS k=$K seed=$SEED epsilon=$EPSILON threads=$THREADS"

SHARDS=$(( (BOARDS + SHARD - 1) / SHARD ))
have=$(ls "$OUT"/allcards-s"$SEED"-*.bin 2>/dev/null | wc -l)
export_log="$LOG/$NAME-export.txt"
if [ "$have" -ge "$SHARDS" ] && [ -f "$export_log" ]; then
    say "  [skip] export -- $have of $SHARDS shards in $OUT"
else
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    # About 390 bytes a decision and 23 decisions a game, for the shards still to come.
    need_kb=$(( (SHARDS - have) * SHARD * 23 * 390 / 1024 ))
    free_kb=$(df -Pk . | awk 'NR == 2 { print $4 }')
    if [ -n "$free_kb" ] && [ $(( free_kb - need_kb )) -lt $(( 10 * 1024 * 1024 )) ]; then
        say "  REFUSED: about $(( need_kb / 1024 / 1024 )) GB to write, $(( free_kb / 1024 / 1024 )) GB free; 10 GB must stay free"
        exit 1
    fi
    say "  [$(date '+%H:%M:%S')] export: $(( SHARDS - have )) shard(s) to go, about $(( need_kb / 1024 )) MB"
    start=$(date +%s)
    ./gradlew --console=plain -q --no-daemon :arena:allCardsExport \
        --args="--model=$MODEL --out=$OUT --boards=$BOARDS --shard-size=$SHARD --k=$K --seed=$SEED --epsilon=$EPSILON --threads=$THREADS" \
        > "$export_log.partial" 2>&1 || { mv "$export_log.partial" "$LOG/$NAME-export.failed.txt"; say "      FAILED -- see $LOG/$NAME-export.failed.txt"; exit 1; }
    mv "$export_log.partial" "$export_log"
    say "      took $(( ($(date +%s) - start) / 60 )) min"
    have=$(ls "$OUT"/allcards-s"$SEED"-*.bin 2>/dev/null | wc -l)
    if [ "$have" -lt "$SHARDS" ]; then
        grep -E "^STOP" "$export_log" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  $have of $SHARDS shards so far; run again to carry on"
        exit 0
    fi
fi
grep -E "games/s|^k [0-9]+\.|^  trick|^  +[0-9]+  +[0-9]+ |^  \(sd" "$export_log" | sed 's/^/    /' | tee -a "$SUMMARY"

checks="$LOG/$NAME-checks.txt"
if [ -f "$checks" ]; then
    say "  [skip] checks -- $checks exists"
else
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] checks"
    if (cd arena/python && "$PYTHON" allcards_data.py "../../$OUT") > "$checks.partial" 2>&1; then
        mv "$checks.partial" "$checks"
    else
        mv "$checks.partial" "$LOG/$NAME-checks.failed.txt"
        grep -E "FAIL|Error" "$LOG/$NAME-checks.failed.txt" | head -5 | sed 's/^/      /' | tee -a "$SUMMARY"
        say "      CHECKS FAILED -- see $LOG/$NAME-checks.failed.txt"
        exit 1
    fi
fi
grep -E "all checks passed|records, .* boards|^  trick|^  +[0-9]+  +[0-9]+  " "$checks" | sed 's/^/    /' | tee -a "$SUMMARY"
say "all-cards export finished $(date '+%Y-%m-%d %H:%M:%S')"
