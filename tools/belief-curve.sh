#!/usr/bin/env bash
# Where does knowing the cards start to pay -- and where does our net sit on that curve?
#
#   ./tools/belief-curve.sh                   five players against belief-0, seeds 11-13
#   ./tools/belief-curve.sh --quick
#   ./tools/belief-curve.sh --seeds="14 15 16"   more seeds; the pooled lines read every seed on disk
#   ./tools/belief-curve.sh --players=belief-5,belief-10
#   ./tools/belief-curve.sh --threads=8
#
# The belief sweep of August (arena/README.md, "The belief sweep") mixed the
# true deal into the sampled worlds at a fixed share and measured the same
# search against itself with a uniform belief. At 25% true worlds it was
# already +25.2 [+20.6, +29.7] a game, as much as omniscience, and flat above.
# belief-5, -10 and -15 were registered then to find where the curve takes
# off and were never run. That is the number that says whether a better
# belief can still close the gap to the cheat: our learned net samples the
# exact deal about 0.02% of the time and is worth +2.37 at the same effort
# (the sharpness sweep), and a better net's extra placement was worth nothing
# at the table (docs/training-plan.md, B2 step 5).
#
# Five players, each against belief-0 -- the same search, the same
# personality (REFERENCE, 16 worlds), a uniform belief -- at fixed contracts,
# exact pairing, so the only difference in every match is the belief:
#
#   belief-5 / belief-10 / belief-15   the true deal as that share of the worlds
#   belief-25                          the August anchor, on today's engine and seeds
#   belief                             the learned net, same search and effort
#
# Read the first three against the last two. If belief-5 is already worth
# several points, the curve rises early and a belief that gets the critical
# cards jointly right is a live lever; if it stays near the net's +2.4 until
# 15%, no realistic net reaches the steep part and the gap to the cheat is
# mostly the price of hidden cards.
#
# The players are instruments: belief-N cheats. Nothing here ships.
# Roughly ten minutes a match at 16 threads, fifteen matches for the default.
# Run it when the machine is free -- two scripts at once share the cores and
# both take twice as long.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${CURVE_ORIGINAL:-}" ]; then
    CURVE_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-curve.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    CURVE_ORIGINAL="$CURVE_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$CURVE_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16
PLAYERS="belief-5 belief-10 belief-15 belief-25 belief"
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --seeds=*)     SEEDS_ARG="${arg#*=}" ;;
        --players=*)   PLAYERS="${arg#*=}"; PLAYERS="${PLAYERS//,/ }" ;;
        -h|--help)     sed -n '2,42p' "$CURVE_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOGROOT=arena-logs; SEEDS="11 12 13"; BOARDS=200
if $QUICK; then LOGROOT=arena-logs/quick; SEEDS="11"; BOARDS=30; fi
[ -n "${SEEDS_ARG:-}" ] && SEEDS="$SEEDS_ARG"
SUMMARY=$LOGROOT/summary-belief-curve.txt
LOG=$LOGROOT/belief-curve; mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "belief-curve started $(date '+%Y-%m-%d %H:%M:%S')   players=$PLAYERS seeds=$SEEDS quick=$QUICK threads=$THREADS"
if [[ " $PLAYERS " == *" belief "* ]] && [ ! -f belief-model/belief.bin ] && [ ! -f belief-model/belief.onnx ]; then
    say "  no belief-model on disk: the learned player 'belief' is not registered and is skipped"
    kept=""; for P in $PLAYERS; do [ "$P" = belief ] || kept="$kept $P"; done; PLAYERS="${kept# }"
fi

pooled=""
for P in $PLAYERS; do
    say "--- $P vs belief-0, fixed contracts ---"
    for SEED in $SEEDS; do
        [ -f STOP ] && break
        tag="$P-vs-belief-0-cardplay-s$SEED"; $QUICK && tag="$tag-b$BOARDS"
        report="$LOG/$tag.txt"
        if [ ! -f "$report" ]; then
            say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
            ./gradlew --console=plain -q --no-daemon :arena:arena \
                --args="--a=$P --b=belief-0 --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
        fi
        grep -E "^  from declaring|^  from defending|^wins as declarer| = .*game pts/game" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    done
    diffs=""
    for report in "$LOG/$P"-vs-belief-0-cardplay-s*.txt; do
        [ -f "$report" ] || continue
        case "$report" in *-b[0-9]*.txt) $QUICK || continue ;; *) $QUICK && continue ;; esac
        d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'); [ -n "$d" ] && diffs="$diffs $d"
    done
    line=$(echo "$diffs" | awk -v label="$P" '{
        n = NF; if (n < 1) { printf "  %-10s no seeds\n", label; exit }
        for (i = 1; i <= n; i++) sum += $i; mean = sum / n
        if (n < 2) { printf "  %-10s %+6.2f game pts/game over 1 seed\n", label, mean; exit }
        for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
        se = sqrt(ss / (n - 1)) / sqrt(n)
        split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
        printf "  %-10s %+6.2f game pts/game over belief-0, pooled over %d seeds, 95%% [%+.2f, %+.2f]\n", label, mean, n, mean - q * se, mean + q * se
    }')
    say "$line"
    pooled="$pooled
$line"
    [ -f STOP ] && break
done
say "--- the curve (August, seed 1: belief-25 +25.2; the net at 16 worlds +2.37 in the sharpness sweep) ---"
say "$pooled"
say "belief-curve finished $(date '+%Y-%m-%d %H:%M:%S')"
