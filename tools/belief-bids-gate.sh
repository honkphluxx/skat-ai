#!/usr/bin/env bash
# Phase B2: the net that reads the auction spelled out, in play.
#
#   ./tools/belief-bids-gate.sh                  weights, the probe, the pairing
#   ./tools/belief-bids-gate.sh --quick
#   ./tools/belief-bids-gate.sh --threads=8
#   ./tools/belief-bids-gate.sh --model=belief-model-bids-structure
#   ./tools/belief-bids-gate.sh --seeds="14 15 16" --pair-only
#                                             three more seeds of the pairing;
#                                             the pooled line reads every seed
#                                             on disk, so the first three count
#
# tools/belief-bids.sh (2026-09-24) trained the shipped corpus three ways and
# held the nets out by role: the inputs as they are place a declarer's unseen
# cards 62.4% right (a second seed 62.3), the same net with the auction spelled
# out -- belief_data.bid_structure, fifty-one derived inputs -- 63.3%. That
# cleared the noise floor by nine times and earned the Java counterpart
# (BeliefEncoding.bidStructure), which the arena checks against the trainer's
# fixtures on every load. This is the net at the table.
#
# Three steps:
#
#   1. export_weights.py writes belief.bin for the model; the loader refuses
#      it if this build spells the auction differently from the trainer.
#   2. belief-share.sh with the model in the candidate slot, three seeds: the
#      declarer's placed% at tricks 1-3 against the shipped net's 52.1
#      (uniform 50.5), and the defender's 49.7 (42.4) must not fall.
#   3. belief-32-shipped-candidate against belief-32-shipped, fixed contracts,
#      exact pairing, three seeds, pooled: the points.
#
# The shipped net is the control here: it was trained on this corpus with
# these settings and reads 62.4 held out, the same as tonight's control. The
# third gate of the plan (against belief-25) is a night of its own.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${GATE_ORIGINAL:-}" ]; then
    GATE_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-bids-gate.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    GATE_ORIGINAL="$GATE_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$GATE_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
MODEL=belief-model-bids-structure
PYTHON=${SKATKLAR_PYTHON:-}
PAIR_ONLY=false
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS_ARG="${arg#*=}" ;;
        --pair-only)  PAIR_ONLY=true ;;
        --model=*)    MODEL="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,30p' "$GATE_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

LOGROOT=arena-logs; PAIR_SEEDS="11 12 13"; BOARDS=200; QUICKFLAG=""
if $QUICK; then LOGROOT=arena-logs/quick; PAIR_SEEDS="11"; BOARDS=30; QUICKFLAG="--quick"; fi
[ -n "${SEEDS_ARG:-}" ] && PAIR_SEEDS="$SEEDS_ARG"
SUMMARY=$LOGROOT/summary-belief-bids-gate.txt
LOG=$LOGROOT/belief-bids-gate; mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "belief-bids-gate started $(date '+%Y-%m-%d %H:%M:%S')   model=$MODEL quick=$QUICK threads=$THREADS"
[ -f "$MODEL/belief.pt" ] || { say "no belief.pt in $MODEL -- run tools/belief-bids.sh first"; exit 1; }

# 1. The weights.
say "--- 1. belief.bin for $MODEL ---"
if [ -f "$MODEL/belief.bin" ] && [ "$MODEL/belief.bin" -nt "$MODEL/belief.pt" ]; then
    say "  [skip] belief.bin is newer than belief.pt"
elif "$PYTHON" arena/python/export_weights.py --model="$MODEL" > "$LOG/weights.txt" 2>&1; then
    say "  written"
else
    tail -3 "$LOG/weights.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  WEIGHTS FAILED"; exit 1
fi
[ -f STOP ] && { say "STOP"; exit 0; }

# 2. The probe.
if $PAIR_ONLY; then
    say "--- 2. belief-share.sh skipped (--pair-only) ---"
else
    say "--- 2. belief-share.sh, candidate against shipped (shipped: declarer 52.1 / 50.5, defender 49.7 / 42.4) ---"
    ./tools/belief-share.sh --candidate="$MODEL" --players=belief-32-shipped-candidate,belief-32-shipped --threads="$THREADS" $QUICKFLAG \
        | grep -E "^belief|as declarer|as defender|tricks 1-3|FAILED" | sed 's/^/      /' | tee -a "$SUMMARY"
    [ -f STOP ] && { say "STOP"; exit 0; }
fi

# 3. The pairing. Pooled over every seed on disk for this model, so a
# second run with --seeds adds to the first rather than replacing it.
say "--- 3. belief-32-shipped-candidate ($MODEL) vs belief-32-shipped, fixed contracts ---"
for SEED in $PAIR_SEEDS; do
    [ -f STOP ] && break
    tag="$(basename "$MODEL")-vs-belief-32-shipped-cardplay-s$SEED"; $QUICK && tag="$tag-b$BOARDS"
    report="$LOG/$tag.txt"
    if [ ! -f "$report" ]; then
        say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
        ./gradlew --console=plain -q --no-daemon "-Dbelief.model.candidate.dir=$MODEL" :arena:arena \
            --args="--a=belief-32-shipped-candidate --b=belief-32-shipped --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
    fi
    grep -E "^  from declaring|^  from defending|^wins as declarer| = .*game pts/game|^Resolved|^Not resolved" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
done
diffs=""
for report in "$LOG/$(basename "$MODEL")"-vs-belief-32-shipped-cardplay-s*.txt; do
    [ -f "$report" ] || continue
    case "$report" in *-b[0-9]*.txt) $QUICK || continue ;; *) $QUICK && continue ;; esac
    d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'); [ -n "$d" ] && diffs="$diffs $d"
done
echo "$diffs" | awk '{
    n = NF; if (n < 2) { print "  pooled: need two seeds"; exit }
    for (i = 1; i <= n; i++) sum += $i; mean = sum / n
    for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
    se = sqrt(ss / (n - 1)) / sqrt(n)
    split("12.706 4.303 3.182 2.776 2.571", t, " "); q = (n - 1 <= 5) ? t[n - 1] : 1.96
    printf "  candidate - shipped, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", n, mean, mean - q * se, mean + q * se
}' | tee -a "$SUMMARY"
say "belief-bids-gate finished $(date '+%Y-%m-%d %H:%M:%S')"
