#!/usr/bin/env bash
# B2, the cheap half: retrain the belief on a void-mode corpus from the wider
# population, and put the result against the model it would replace.
#
#   ./tools/belief-v2.sh            the night: 200k boards, 20 epochs, gates on three seeds
#   ./tools/belief-v2.sh --quick    a rehearsal: 2k boards, 3 epochs, 30-board gates, one
#                                   seed, into its own directories -- an hour, and it
#                                   answers "does every step run" and nothing else
#   ./tools/belief-v2.sh --threads=8
#   ./tools/belief-v2.sh --python=/c/Python312/python.exe
#
# Five steps, each skipped when its output already exists, so the same command
# resumes after a stop (a STOP file in the repository root, or Ctrl-C, between
# steps or matches):
#
#   1. export      belief-data-v2/     :arena:export, --passed-in=void, population
#                                      greedy, search-4, club, expert, jskat-new,
#                                      xskat-blind, go-skat (whichever are built)
#   2. check       check_data.py       seconds; a FAIL stops the night here
#   3. train       belief-model-v2/    train_belief.py, prints the held-out accuracy
#                                      against the uniform-sampler baseline
#   4. weights     belief-model-v2/belief.bin   export_weights.py
#   5. gates       arena-logs/         the candidate against the shipped model
#
# The gates, void mode, three seeds, read in the morning from
# arena-logs/summary-belief-v2.txt:
#
#   belief-32-candidate  vs belief-32       fixed contracts: the model alone, card play
#   belief-32-candidate  vs belief-32       full game: the model alone, with the auction
#   ...-ties-candidate   vs ...-ties        the shipped combination, only the belief swapped;
#                                          this is the row that decides
#   ...-ties-candidate   vs xskat, jskat-new, go-skat   the population gates
#
# Why not skatzero in the population: as seated it never bids (the driver
# answers MAXBID 0), so a seventh of the corpus's passes would say nothing
# about the hand behind them, and the model would learn that a pass means
# nothing. It joins once it has a delegate bidder.
#
# Why "cheap half": the model, the encoding and the Null guard are unchanged;
# only the corpus is new. If the candidate does not move, the population was
# not the limit and the rest of B2 (Null in the model, the true-world-share
# diagnostic) is where the week goes. If it does, ship it and then do the rest.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

# Run from a copy, for the reason overnight-arena.sh gives: bash reads a script
# as it goes, and a commit mid-run would land it on a byte offset.
if [ -z "${BELIEF_V2_ORIGINAL:-}" ]; then
    BELIEF_V2_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-v2.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BELIEF_V2_ORIGINAL="$BELIEF_V2_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BELIEF_V2_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
# The interpreter with a CUDA torch (arena/python/requirements.txt). python3 is
# not on PATH in Git Bash on this machine; the venv beside the repository is.
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,48p' "$BELIEF_V2_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

DATA=belief-data-v2
MODEL=belief-model-v2
LOG=arena-logs
SUMMARY=arena-logs/summary-belief-v2.txt
BOARDS=200000
SHARD=20000
EPOCHS=20
SEEDS="11 12 13"
GATE=300
GATE_SMALL=200
if $QUICK; then
    DATA=belief-data-v2-quick
    MODEL=belief-model-v2-quick
    LOG=arena-logs/quick
    SUMMARY=arena-logs/quick/summary-belief-v2.txt
    BOARDS=2000
    SHARD=1000
    EPOCHS=3
    SEEDS="11"
    GATE=30
    GATE_SMALL=30
fi
mkdir -p "$LOG"
POPULATION=greedy,search-4,club,expert,jskat-new,xskat-blind,go-skat

say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
stopped() { [ -f STOP ] && { say "STOP file found -- stopping."; return 0; }; return 1; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }

say "============================================================"
say "belief v2 started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS python=$PYTHON"

# 1. The corpus. population.json is the last file the exporter writes.
if [ -f "$DATA/population.json" ]; then
    say "  [skip] export -- $DATA/population.json exists"
else
    say "  [$(date '+%H:%M:%S')] export $BOARDS boards into $DATA"
    rm -rf "$DATA"
    ./gradlew --console=plain -q --no-daemon :arena:export \
        --args="--boards=$BOARDS --seed=11 --threads=$THREADS --shard=$SHARD --out=$DATA --passed-in=void --players=$POPULATION" \
        > "$LOG/belief-v2-export.txt" 2>&1
    rc=$?
    grep -E "population|skipping unavailable|boards, seed|records|wrote" "$LOG/belief-v2-export.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    if [ $rc -ne 0 ] || [ ! -f "$DATA/population.json" ]; then
        say "  EXPORT FAILED -- see $LOG/belief-v2-export.txt"; exit 1
    fi
fi
stopped && exit 0

# 2. The check. Seconds, and the reason the night is not spent on a silent bug.
say "  [$(date '+%H:%M:%S')] check_data $DATA"
if ! "$PYTHON" arena/python/check_data.py "$DATA" > "$LOG/belief-v2-check.txt" 2>&1; then
    grep -E "FAIL|Error|error" "$LOG/belief-v2-check.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  CHECK FAILED -- see $LOG/belief-v2-check.txt"; exit 1
fi
grep -E "records from|FAIL|all checks|PASS" "$LOG/belief-v2-check.txt" | tail -4 | sed 's/^/      /' | tee -a "$SUMMARY"
stopped && exit 0

# 3. Train.
if [ -f "$MODEL/belief.pt" ]; then
    say "  [skip] train -- $MODEL/belief.pt exists"
else
    say "  [$(date '+%H:%M:%S')] train $EPOCHS epochs into $MODEL"
    if ! "$PYTHON" arena/python/train_belief.py --data "$DATA" --out "$MODEL" --epochs "$EPOCHS" \
            > "$LOG/belief-v2-train.txt" 2>&1; then
        tail -5 "$LOG/belief-v2-train.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  TRAINING FAILED -- see $LOG/belief-v2-train.txt"; exit 1
    fi
    grep -E "uniform sampler baseline|best held-out|correct" "$LOG/belief-v2-train.txt" | tail -4 | sed 's/^/      /' | tee -a "$SUMMARY"
fi
stopped && exit 0

# 4. The weights the arena and the app read.
if [ -f "$MODEL/belief.bin" ]; then
    say "  [skip] weights -- $MODEL/belief.bin exists"
else
    say "  [$(date '+%H:%M:%S')] export_weights $MODEL"
    if ! "$PYTHON" arena/python/export_weights.py --model="$MODEL" > "$LOG/belief-v2-weights.txt" 2>&1; then
        tail -5 "$LOG/belief-v2-weights.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  WEIGHTS FAILED -- see $LOG/belief-v2-weights.txt"; exit 1
    fi
    grep -E "belief.bin|parity" "$LOG/belief-v2-weights.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
fi
stopped && exit 0

# 5. The gates. Same naming as overnight-arena.sh, so ~/pool.py and the paired
# CSVs read these like any other night; the candidate contestants exist only
# when the property names a model, so no earlier log can shadow them.
PROPS="-Dbelief.model.candidate.dir=$MODEL"
match() {
    local a="$1" b="$2" count="$3" extra="${4:-}"
    local mode=auction
    case "$extra" in *fixed-contract*) mode=cardplay ;; esac
    case "$extra" in *--passed-in=void*) mode="$mode-void" ;; esac
    local tag="$a-vs-$b-$mode-s$SEED"
    $QUICK && tag="$tag-b$count"
    local report="$LOG/$tag.txt"
    if [ -f "$report" ]; then say "  [skip] $tag -- log already exists"; return 0; fi
    [ -f STOP ] && return 0
    say "  [$(date '+%H:%M:%S')] $tag ($count boards)"
    # shellcheck disable=SC2086 -- $extra and $PROPS are deliberately word-split
    ./gradlew --console=plain -q --no-daemon $PROPS :arena:arena \
        --args="--a=$a --b=$b --boards=$count --seed=$SEED --threads=$THREADS $extra --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1
    if [ $? -ne 0 ]; then
        mv "$report" "$LOG/$tag.failed.txt"
        say "      FAILED -- see $tag.failed.txt"
        return 0
    fi
    grep -E "^game pts/game|^declares|^rule violations| = .*game pts/game|^Resolved|^Not resolved" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
}

XSKAT=false; GOSKAT=false
{ [ -x third_party/xskat/skatklar-xskat ] || [ -x third_party/xskat/skatklar-xskat.exe ]; } && XSKAT=true
{ [ -x third_party/go-skat/skatklar-goskat ] || [ -x third_party/go-skat/skatklar-goskat.exe ]; } && GOSKAT=true
SHIPPED=belief-32-adaptive-margin-ties
CANDIDATE=belief-32-adaptive-margin-ties-candidate
for SEED in $SEEDS; do
    stopped && break
    say "--- seed $SEED ---"
    match belief-32-candidate belief-32 "$GATE" "--fixed-contract"
    match belief-32-candidate belief-32 "$GATE" "--passed-in=void"
    match "$CANDIDATE" "$SHIPPED" "$GATE" "--passed-in=void"
    $XSKAT  && match "$CANDIDATE" xskat     "$GATE" "--passed-in=void"
    match "$CANDIDATE" jskat-new "$GATE" "--passed-in=void"
    $GOSKAT && match "$CANDIDATE" go-skat   "$GATE_SMALL" "--passed-in=void"
done
say "belief v2 finished $(date '+%Y-%m-%d %H:%M:%S')"
