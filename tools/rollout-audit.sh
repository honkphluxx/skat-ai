#!/usr/bin/env bash
# Does the vote rank the declarer's cards the way the table does?
#
#   ./tools/rollout-audit.sh                  seeds 14-16, 12 decisions a band a seed, 8 rollouts a card
#   ./tools/rollout-audit.sh --quick          seed 14, 30 boards, 3 a band, 4 rollouts
#   ./tools/rollout-audit.sh --rollouts=16 --per-band=20
#   ./tools/rollout-audit.sh --player=belief-32-shipped-trap2
#   ./tools/rollout-audit.sh --threads=8
#
# The declaring audit found the vote calibrated against the defence it models
# -- one that sees every card, in every sampled world -- and twenty points too
# pessimistic against the defence at the table: of the cards the worlds give
# one chance in eight, the game is won nearly one time in three
# (docs/training-plan.md, 2026-09-25). That only costs points if it puts the
# cards in the wrong order. This asks for the order: from recorded declarer
# positions of the arena's own games, every legal card is played out to the
# end of the game against the real defenders, several times, with shared
# seeds, and the table's order is held against the vote's.
#
# Four bands, by the vote share of the card the player chose: every card at
# zero, 1-25%, 26-75%, 76-99%. Per band: how often the vote's card was the
# best at the table, how often the best card of those that win against perfect
# defence in the true deal was, the game win rate of the vote's card and of the
# best card, and what the vote's card gives up per decision in game points
# (best picked on half the rollouts, both measured on the other half).
#
# REPLAY CHECK first: on the first decisions of each band the recorded game is
# rolled out with its own seeds and its own card, and must come out card for
# card identical. If it says a probe did not reproduce, nothing below it holds.
#
# Roughly an hour at 16 threads for the default. The per-card rows go to
# arena-logs/rollout/rollout-audit-s<seed>.csv.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${ROLLOUT_ORIGINAL:-}" ]; then
    ROLLOUT_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/rollout-audit.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    ROLLOUT_ORIGINAL="$ROLLOUT_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$ROLLOUT_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; PLAYER=belief-32-shipped; ROLLOUTS=8; PER_BAND=12; SEEDS="14 15 16"; BOARDS=200
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --player=*)    PLAYER="${arg#*=}" ;;
        --rollouts=*)  ROLLOUTS="${arg#*=}" ;;
        --per-band=*)  PER_BAND="${arg#*=}" ;;
        --seeds=*)     SEEDS="${arg#*=}" ;;
        -h|--help)     sed -n '2,34p' "$ROLLOUT_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
LOG=arena-logs/rollout
if $QUICK; then LOG=arena-logs/quick/rollout; SEEDS="14"; BOARDS=30; PER_BAND=3; ROLLOUTS=4; fi
[ "$PLAYER" != belief-32-shipped ] && LOG="$LOG/$PLAYER"
mkdir -p "$LOG"
stamp=$(date '+%Y%m%d-%H%M%S')
report="$LOG/rollout-audit-$stamp.txt"
echo "rollout-audit started $(date '+%Y-%m-%d %H:%M:%S')   player=$PLAYER seeds=$SEEDS boards=$BOARDS per-band=$PER_BAND rollouts=$ROLLOUTS threads=$THREADS"
echo "report: $report"
./gradlew --console=plain -q --no-daemon :arena:rolloutAudit \
    --args="--player=$PLAYER --seeds=${SEEDS// /,} --boards=$BOARDS --rollouts=$ROLLOUTS --per-band=$PER_BAND --threads=$THREADS --out=$ROOT/$LOG" \
    > "$report" 2>&1 || { mv "$report" "$LOG/rollout-audit-$stamp.failed.txt"; echo "FAILED -- see $LOG/rollout-audit-$stamp.failed.txt"; exit 1; }
grep -v "^SkatKlar solver" "$report"
echo "rollout-audit finished $(date '+%Y-%m-%d %H:%M:%S')"
