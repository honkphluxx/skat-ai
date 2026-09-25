#!/usr/bin/env bash
# Where does SkatZero's card play beat ours -- today, and card by card?
#
#   ./tools/skatzero-gap.sh                  the pairing (three seeds), then the audit
#   ./tools/skatzero-gap.sh --quick
#   ./tools/skatzero-gap.sh --threads=8
#
# SkatZero is the strongest card play on the ladder: 2.57 [1.28, 3.85] a game
# above the player that shipped on 2026-09-17, at oracle contracts, and the
# count of lost games that day put the whole of it at Null (39% of makeable
# Nulls won against 78%) with the trump games level. Since then the Null
# levers shipped (+1.73 a Null), the ladder shipped (+1.30 on the split), and
# the belief was retrained; nobody has measured the row for the player that
# ships now. Two things, in order:
#
#   1. belief-32-shipped against skatzero, oracle contracts, three seeds --
#      the same row the overnight script keeps, under the same name, so the
#      night skips it afterwards. The report's per-contract table says how
#      much of what is left is Null.
#   2. tools/declaring-audit.sh --par=skatzero: SkatZero in par's chair. The
#      same boards, greedy's contracts, our defenders both times, the solver
#      judging every card of both -- so the two declarers' throws, the boards
#      only one of them wins, and the value lost when both win or both lose
#      are read side by side. That is what "level in trump games" looks like
#      card by card, if it is still true.
#
# Roughly ten minutes a seed for the pairing (SkatZero is a Python helper,
# about two games a second) and fifteen for the audit.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${GAP_ORIGINAL:-}" ]; then
    GAP_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/skatzero-gap.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    GAP_ORIGINAL="$GAP_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$GAP_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        -h|--help)    sed -n '2,30p' "$GAP_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f "${SKATKLAR_SKATZERO_DIR:-third_party/skatzero}/models/onnx/D_0.onnx" ] || { echo "no SkatZero models under third_party/skatzero" >&2; exit 2; }

LOG=arena-logs; SEEDS="11 12 13"; BOARDS=200; QUICKFLAG=""; SUFFIX=""
if $QUICK; then LOG=arena-logs/quick; SEEDS="11"; BOARDS=30; QUICKFLAG="--quick"; SUFFIX="-b30"; fi
SUMMARY=$LOG/summary-skatzero-gap.txt
mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "skatzero-gap started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"

# 1. The row for the player that ships.
say "--- 1. belief-32-shipped vs skatzero, oracle contracts (2026-09-17 shipped player: -2.57 [-3.85, -1.28]) ---"
diffs=""
for SEED in $SEEDS; do
    [ -f STOP ] && break
    tag="belief-32-shipped-vs-skatzero-oracle-s$SEED$SUFFIX"
    report="$LOG/$tag.txt"
    if [ ! -f "$report" ]; then
        say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
        ./gradlew --console=plain -q --no-daemon :arena:arena \
            --args="--a=belief-32-shipped --b=skatzero --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --contracts=solver --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
    fi
    grep -E "^  from declaring|^  from defending|^wins as declarer| = .*game pts/game|^Resolved|^Not resolved|^  (Diamonds|Hearts|Spades|Clubs|Grand|Null) " "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'); [ -n "$d" ] && diffs="$diffs $d"
done
echo "$diffs" | awk '{
    n = NF; if (n < 2) { print "  pooled: need two seeds"; exit }
    for (i = 1; i <= n; i++) sum += $i; mean = sum / n
    for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
    se = sqrt(ss / (n - 1)) / sqrt(n)
    split("12.706 4.303 3.182 2.776 2.571", t, " "); q = (n - 1 <= 5) ? t[n - 1] : 1.96
    printf "  shipped - skatzero, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", n, mean, mean - q * se, mean + q * se
}' | tee -a "$SUMMARY"
[ -f STOP ] && { say "STOP"; exit 0; }

# 2. SkatZero in par's chair.
say "--- 2. declaring-audit.sh --par=skatzero, seeds $SEEDS ---"
./tools/declaring-audit.sh --par=skatzero --seeds="$SEEDS" --threads="$THREADS" $QUICKFLAG \
    | grep -vE "^\s*$|ZERO CHECK|no log|Per-game|^report:" | sed 's/^/      /' | tee -a "$SUMMARY"
say "skatzero-gap finished $(date '+%Y-%m-%d %H:%M:%S')"
