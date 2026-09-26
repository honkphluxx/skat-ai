#!/usr/bin/env bash
# Would a global endgame cache pay? Where the solver's work lies, and how often it repeats.
#
#   ./tools/endgame-probe.sh                 seed 14, 60 boards, the shipped player in all seats
#   ./tools/endgame-probe.sh --quick         6 boards
#   ./tools/endgame-probe.sh --boards=200 --seed=15
#   ./tools/endgame-probe.sh --threads=8
#
# docs/training-plan.md 2.10, T2. A position at the start of a trick -- the
# three remaining hands and who leads -- is worth the same in every world and
# every game that reaches it, so a cache of the last few tricks, shared by
# every game, would be sound. Whether it pays is two numbers, measured on the
# player's own searches in real games (EndgameProbeMain):
#
#   1. the share of all solver nodes by tricks left, and so the share that
#      lies at or below a cut of 2, 3 or 4 tricks -- the most a cache there
#      can ever save;
#   2. how often a position at the cut has been seen before -- in the same
#      decision (another world, or another question of the same world), in
#      the same game, or in another game -- and the nodes its subtree cost
#      again, which is what a cache would have saved.
#
# The native engine is not instrumented, so this runs the Java search, which
# is several times slower: the shares are what to read, not the times.
# A few minutes for the default at 16 threads (4 games took a minute on 2).
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${PROBE_ORIGINAL:-}" ]; then
    PROBE_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/endgame-probe.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    PROBE_ORIGINAL="$PROBE_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$PROBE_ORIGINAL")/.." || exit 1

THREADS=16; SEED=14; BOARDS=60; SAMPLE=8
for arg in "$@"; do
    case "$arg" in
        --quick)      BOARDS=6 ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seed=*)     SEED="${arg#*=}" ;;
        --boards=*)   BOARDS="${arg#*=}" ;;
        --sample=*)   SAMPLE="${arg#*=}" ;;
        -h|--help)    sed -n '2,27p' "$PROBE_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
LOG=arena-logs/endgame-probe; mkdir -p "$LOG"
stamp=$(date '+%Y%m%d-%H%M%S')
report="$LOG/endgame-probe-s$SEED-b$BOARDS-$stamp.txt"
echo "endgame-probe started $(date '+%Y-%m-%d %H:%M:%S')   seed=$SEED boards=$BOARDS threads=$THREADS"
echo "report: $report"
./gradlew --console=plain -q --no-daemon :arena:endgameProbe \
    --args="--seed=$SEED --boards=$BOARDS --threads=$THREADS --sample=$SAMPLE" \
    > "$report" 2>&1 || { mv "$report" "$LOG/endgame-probe-$stamp.failed.txt"; echo "FAILED -- see $LOG/endgame-probe-$stamp.failed.txt"; exit 1; }
grep -v "^SkatKlar solver" "$report"
echo "endgame-probe finished $(date '+%Y-%m-%d %H:%M:%S')"
