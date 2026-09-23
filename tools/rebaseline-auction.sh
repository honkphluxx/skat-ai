#!/usr/bin/env bash
# Re-baseline after 33a5ac9: fixed-contract games now hear the auction.
#
#   ./tools/rebaseline-auction.sh              the split (six seeds), the belief-share
#                                              probe, and the confident-first pairing
#   ./tools/rebaseline-auction.sh --quick
#   ./tools/rebaseline-auction.sh --threads=8
#
# Every fixed-contract log on disk was taken with the bidding block of the
# belief's evidence empty (docs/training-plan.md, B2, 2026-09-24). This moves
# the ones the plan reads aside -- arena-logs/par and arena-logs/belief-share
# into superseded-<stamp>-no-auction -- and takes them again. The old numbers
# stay readable as the before half; nothing is deleted.
#
# Three things, in the order to read them:
#
#   1. declaring-par.sh --split, six seeds. The card-play share was 2.27
#      [-0.1, 4.6] with the ladder and the auction stripped. Par (the solver)
#      does not read the auction, so its column should not move; ours should,
#      and the gap should close by whatever the declarer's belief is worth.
#   2. belief-share.sh. The declarer's placed% at tricks 1-3 was 51.3 against
#      uniform 50.5; the net's argmax there should now read near the auction
#      figure (53), and placed% follow.
#   3. belief-32-shipped-confident against belief-32-shipped, fixed contracts,
#      exact pairing, three seeds: the sampler's first step, measured on the
#      corrected instrument.
#
# The field gates (overnight-arena.sh) are auction-mode except the solver row
# at oracle contracts, which is fixed-contract and moves too; re-run that one
# with --redo=solver-oracle when the machine is free. It is not in here
# because a night of it costs more than it tells.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${REBASE_ORIGINAL:-}" ]; then
    REBASE_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/rebaseline-auction.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    REBASE_ORIGINAL="$REBASE_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$REBASE_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        -h|--help)    sed -n '2,32p' "$REBASE_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOGROOT=arena-logs; SEEDS="11 12 13 14 15 16"; PAIR_SEEDS="11 12 13"; BOARDS=200; QUICKFLAG=""
if $QUICK; then LOGROOT=arena-logs/quick; SEEDS="11"; PAIR_SEEDS="11"; BOARDS=30; QUICKFLAG="--quick"; fi
SUMMARY=$LOGROOT/summary-rebaseline-auction.txt
mkdir -p "$LOGROOT"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "rebaseline-auction started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"

# 0. The old logs aside, once. A second run finds them already moved and resumes.
stamp=$(date '+%Y%m%d-%H%M%S')
for d in par belief-share; do
    if [ -d "$LOGROOT/$d" ] && [ ! -f "$LOGROOT/$d/.hears-the-auction" ]; then
        mv "$LOGROOT/$d" "$LOGROOT/superseded-$stamp-no-auction-$d"
        say "  $LOGROOT/$d moved to $LOGROOT/superseded-$stamp-no-auction-$d"
    fi
    mkdir -p "$LOGROOT/$d" && touch "$LOGROOT/$d/.hears-the-auction"
done

# 1. The split.
say "--- 1. declaring-par.sh --split (before: card play 2.27 [-0.1, 4.6], discard 0.93) ---"
./tools/declaring-par.sh --split --seeds="$SEEDS" --threads="$THREADS" $QUICKFLAG \
    | grep -E "seed|share|pooled" | sed 's/^/      /' | tee -a "$SUMMARY"
[ -f STOP ] && { say "STOP"; exit 0; }

# 2. The probe.
say "--- 2. belief-share.sh (before: declarer 51.3 / 50.5, argmax 49.2; defender 49.3 / 42.4) ---"
./tools/belief-share.sh --seeds="$PAIR_SEEDS" --threads="$THREADS" $QUICKFLAG \
    | grep -E "^belief|^search|as declarer|as defender|tricks 1-3|FAILED" | sed 's/^/      /' | tee -a "$SUMMARY"
[ -f STOP ] && { say "STOP"; exit 0; }

# 3. Confident-first, exact pairing.
say "--- 3. belief-32-shipped-confident vs belief-32-shipped, fixed contracts ---"
LOG=$LOGROOT/confident; mkdir -p "$LOG"; diffs=""
for SEED in $PAIR_SEEDS; do
    [ -f STOP ] && break
    tag="belief-32-shipped-confident-vs-belief-32-shipped-cardplay-s$SEED"; $QUICK && tag="$tag-b$BOARDS"
    report="$LOG/$tag.txt"
    if [ ! -f "$report" ]; then
        say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
        ./gradlew --console=plain -q --no-daemon :arena:arena \
            --args="--a=belief-32-shipped-confident --b=belief-32-shipped --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
    fi
    grep -E "^  from declaring|^  from defending|^wins as declarer| = .*game pts/game|^Resolved|^Not resolved" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'); [ -n "$d" ] && diffs="$diffs $d"
done
echo "$diffs" | awk '{
    n = NF; if (n < 2) { print "  pooled: need two seeds"; exit }
    for (i = 1; i <= n; i++) sum += $i; mean = sum / n
    for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
    se = sqrt(ss / (n - 1)) / sqrt(n)
    split("12.706 4.303 3.182 2.776 2.571", t, " "); q = (n - 1 <= 5) ? t[n - 1] : 1.96
    printf "  confident - shipped, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", n, mean, mean - q * se, mean + q * se
}' | tee -a "$SUMMARY"
say "rebaseline-auction finished $(date '+%Y-%m-%d %H:%M:%S')"
