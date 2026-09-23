#!/usr/bin/env bash
# Phase B2 step 1: how often are the worlds the belief samples the world on
# the table? The plan's "true-world share per trick", never printed until now.
#
#   ./tools/belief-share.sh              three seeds, 200 boards (about 40 min)
#   ./tools/belief-share.sh --quick      seed 11, 20 boards
#   ./tools/belief-share.sh --players=belief-32-shipped,search-32,belief-32-flat
#   ./tools/belief-share.sh --candidate=belief-model-declarer-x2 \
#                           --players=belief-32-shipped-candidate,belief-32-shipped
#                                        a model under test, seated as the shipped
#                                        player (-Dbelief.model.candidate.dir)
#
# What it prints, for the belief player and the uniform sampler on the same
# games (fixed contracts from greedy's auction, all three seats the player,
# the arena's own seeding): by trick and by role, the share of sampled worlds
# that are exactly the true deal, and the share of unseen cards the average
# world puts in the right hand. The plan's gate is stated on the first; the
# sweep's yardstick (belief-5 .. belief-25) mixes the truth in at that rate,
# so their exact share is the rate by construction and they are not probed.
#
# How to read it. Exact is essentially zero for any honest sampler in the
# first tricks -- millions of deals are consistent with one hand -- and rises
# only as the deal narrows; so the number that can move is "placed", and the
# question the run answers is by how much the belief beats uniform placement
# at tricks 1-3, where the votes are decided and the cost is spent. That
# margin is what Phase P would be trying to widen, and what a generation
# would have to be measured on before its night of gates.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${SHARE_ORIGINAL:-}" ]; then
    SHARE_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-share.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    SHARE_ORIGINAL="$SHARE_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$SHARE_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
PLAYERS=belief-32-shipped,search-32
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS_ARG="${arg#*=}" ;;
        --players=*)  PLAYERS="${arg#*=}" ;;
        --candidate=*) CANDIDATE="${arg#*=}" ;;
        -h|--help)    sed -n '2,24p' "$SHARE_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOG=arena-logs/belief-share
BOARDS=200
SEEDS="11 12 13"
if $QUICK; then LOG=arena-logs/quick/belief-share; BOARDS=20; SEEDS="11"; fi
[ -n "${SEEDS_ARG:-}" ] && SEEDS="$SEEDS_ARG"
mkdir -p "$LOG"
stamp=$(date '+%Y%m%d-%H%M%S')
report="$LOG/belief-share-$stamp.txt"
echo "belief-share started $(date '+%Y-%m-%d %H:%M:%S')   players=$PLAYERS seeds=$SEEDS boards=$BOARDS threads=$THREADS"
echo "report: $report"
PROPS=""
[ -n "${CANDIDATE:-}" ] && PROPS="-Dbelief.model.candidate.dir=$CANDIDATE" && LOG="$LOG/$(basename "$CANDIDATE")" && mkdir -p "$LOG" && report="$LOG/belief-share-$stamp.txt"
# shellcheck disable=SC2086 -- $PROPS is deliberately word-split
./gradlew --console=plain -q --no-daemon $PROPS :arena:beliefShare \
    --args="--players=$PLAYERS --seeds=${SEEDS// /,} --boards=$BOARDS --threads=$THREADS --out=$ROOT/$LOG/$stamp" \
    > "$report" 2>&1 || { mv "$report" "$LOG/belief-share-$stamp.failed.txt"; echo "FAILED -- see $LOG/belief-share-$stamp.failed.txt"; exit 1; }
grep -v 'seed [0-9]* done' "$report"
echo "belief-share finished $(date '+%Y-%m-%d %H:%M:%S')"
