#!/usr/bin/env bash
# Phase B2: can the net read the auction better if it is spelled out?
#
#   ./tools/belief-bids.sh                    three nets, held out by role
#   ./tools/belief-bids.sh --quick            3 epochs on the quick corpus: does it run
#   ./tools/belief-bids.sh --python=/c/Python312/python.exe
#
# What is being asked. The belief's declarer column is thin -- its marginals
# place a declarer's unseen cards 2 points over uniform against 8 for a
# defender (docs/training-plan.md, B2, 2026-09-24) -- and the sampler was
# measured to deliver what the marginals promise, so what is left is the net.
# The paper this belief descends from (Solinas, Rebstock, Buro, AAAI 2019)
# hands its net each opponent's highest bid as a game *type* and a magnitude
# bucketed to preserve the multiplier, "because the bid multiplier is a
# strong predictor for the locations of jacks in particular". Ours arrives as
# the value over a hundred, in one byte. Whether three GELU layers recover
# "divisible by eleven, so spades, so a multiplier of three, so two jacks"
# from 0.33 on their own is what this run asks, before anything is ported.
#
# Three nets from the shipped corpus (belief-data-mixed), the same split, the
# same epochs:
#
#   belief-model-bids-control      the inputs as they are, init seed 1
#   belief-model-bids-control2     the same, init seed 2: the noise floor
#   belief-model-bids-structure    --bid-structure: 51 derived inputs appended
#                                  (belief_data.bid_structure), init seed 1
#
# Read the held-out-by-role lines (eval_belief.py --by-role). The declarer
# margin is the whole answer, and control2 - control is what a difference has
# to clear:
#
#   structure clears the noise floor    -> port bid_structure to BeliefEncoding
#                                          (Java), retrain, and it goes to the
#                                          arena as belief-32-bids
#   within the noise floor              -> the scalar was enough; the net is
#                                          not short of the auction's structure,
#                                          and the remaining levers are the
#                                          defenders' choices and human data
#   defender margin falls               -> the price, not a reason to ship
#
# The structure net cannot be exported for the player (export_weights.py
# refuses it) until the Java encoder spells the auction out the same way;
# this run is the reason to write that or not to.
#
# Roughly 7 minutes a net on the 4090, and the evaluations are seconds.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${BIDS_ORIGINAL:-}" ]; then
    BIDS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-bids.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BIDS_ORIGINAL="$BIDS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BIDS_ORIGINAL")/.." || exit 1

QUICK=false
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,46p' "$BIDS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

DATA=belief-data-mixed
LOG=arena-logs/belief-bids
SUMMARY=arena-logs/summary-belief-bids.txt
EPOCHS=20
MODELS=belief-model-bids
if $QUICK; then
    DATA=belief-data-mixed-quick; LOG=arena-logs/quick/belief-bids
    SUMMARY=arena-logs/quick/summary-belief-bids.txt; EPOCHS=3
    MODELS=belief-model-bids-quick
fi
mkdir -p "$LOG" "$(dirname "$SUMMARY")"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "belief-bids started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK python=$PYTHON"
[ -d "$DATA" ] || { say "no corpus at $DATA"; exit 1; }

train() {
    local model="$1" tag="$2"; shift 2
    if [ -f "$model/belief.pt" ] && grep -q bid_structure "$model/model.json" 2>/dev/null; then
        say "  [skip] train $tag -- $model/belief.pt exists"; return 0
    fi
    [ -f STOP ] && return 1
    say "  [$(date '+%H:%M:%S')] train $tag, $EPOCHS epochs, $*"
    if ! "$PYTHON" arena/python/train_belief.py --data "$DATA" --out "$model" --epochs "$EPOCHS" "$@" \
            > "$LOG/train-$tag.txt" 2>&1; then
        tail -5 "$LOG/train-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  TRAINING FAILED -- see $LOG/train-$tag.txt"; return 1
    fi
    grep -E "inputs|uniform sampler baseline|^epoch +$EPOCHS |best held-out" "$LOG/train-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
}
say "--- 1. three nets from $DATA ---"
train "$MODELS-control"   control   --init-seed 1 || exit 1
train "$MODELS-control2"  control2  --init-seed 2 || exit 1
train "$MODELS-structure" structure --init-seed 1 --bid-structure || exit 1

say "--- 2. held out by role (the declarer line is the answer; control2 - control is the noise) ---"
for tag in control control2 structure; do
    model="$MODELS-$tag"
    if "$PYTHON" arena/python/eval_belief.py --model "$model" --data "$DATA" --by-role > "$LOG/eval-$tag.txt" 2>&1; then
        say "  $tag:"; grep -E "records|^  !!" "$LOG/eval-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    else
        tail -3 "$LOG/eval-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
done
say "  shipped, for reference (2026-09-23, belief-data-mixed held out): declarer 62.4 / 51.1 (+11.3); defender 67.5 / 44.7 (+22.8)"
say "belief-bids finished $(date '+%Y-%m-%d %H:%M:%S')"
