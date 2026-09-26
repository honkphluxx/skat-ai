#!/usr/bin/env bash
# The vote against a rollout's pick, on the same worlds.
#
#   ./tools/world-rollout.sh                 the audit's 144 decisions, all 32 worlds, one rollout a card a world
#   ./tools/world-rollout.sh --quick         seed 14, 2 decisions a band, 8 worlds
#   ./tools/world-rollout.sh --worlds=16     half the worlds, half the time
#   ./tools/world-rollout.sh --per-world=2
#   ./tools/world-rollout.sh --threads=8
#
# The rollout audit (docs/training-plan.md 2.9, 2026-09-26) found the vote's
# order right where it can be judged, and its card beaten mostly by the card
# that wins in the true deal -- a card the vote cannot tell apart without
# seeing the deal. What it could not say is how much the vote loses against a
# better use of the worlds the player already has. This asks that, with the
# information held equal.
#
# The audit's decisions are recorded again (same boards, seeds, players; the
# record check says whether the same card and vote came back) with the worlds
# the player sampled for each. In each of those worlds every legal card is
# forced and the game played on by the real players -- the hidden cards dealt
# as the world has them. The card with the best mean over the worlds is the
# rollout's pick; it and the vote's card are then scored on the audit's
# true-deal rollouts (arena-logs/rollout/rollout-audit-s<seed>.csv), which
# share no seeds with these.
#
# Read the "rollout - vote" column. Clearly above zero: the counting loses
# points the worlds already hold, and a rollout-style evaluation is a lever.
# Near zero: the gap to the cheat is the information, and the question goes
# back to the belief. The est.vote column against the vote column says whether
# the rollouts' level matches the table where the vote's did not.
#
# Needs the rollout audit's CSVs. Roughly four times the audit's rollouts
# (32 worlds against 8 true-deal rollouts), so about four hours at 16 threads
# for the default; --worlds=16 halves it.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${WORLDS_ORIGINAL:-}" ]; then
    WORLDS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/world-rollout.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    WORLDS_ORIGINAL="$WORLDS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$WORLDS_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; PLAYER=belief-32-shipped; WORLDS=0; PER_WORLD=1; PER_BAND=0; SEEDS="14 15 16"
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --worlds=*)    WORLDS="${arg#*=}" ;;
        --per-world=*) PER_WORLD="${arg#*=}" ;;
        --per-band=*)  PER_BAND="${arg#*=}" ;;
        --seeds=*)     SEEDS="${arg#*=}" ;;
        -h|--help)     sed -n '2,36p' "$WORLDS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
FROM=arena-logs/rollout; LOG=arena-logs/rollout/worlds
if $QUICK; then LOG=arena-logs/quick/rollout-worlds; SEEDS="14"; PER_BAND=2; WORLDS=8; fi
for s in $SEEDS; do
    [ -f "$FROM/rollout-audit-s$s.csv" ] || { echo "no $FROM/rollout-audit-s$s.csv -- run tools/rollout-audit.sh first" >&2; exit 2; }
done
mkdir -p "$LOG"
stamp=$(date '+%Y%m%d-%H%M%S')
report="$LOG/world-rollout-$stamp.txt"
echo "world-rollout started $(date '+%Y-%m-%d %H:%M:%S')   seeds=$SEEDS worlds=$WORLDS per-world=$PER_WORLD per-band=$PER_BAND threads=$THREADS"
echo "report: $report"
./gradlew --console=plain -q --no-daemon :arena:worldRollout \
    --args="--player=$PLAYER --seeds=${SEEDS// /,} --from=$ROOT/$FROM --worlds=$WORLDS --per-world=$PER_WORLD --per-band=$PER_BAND --threads=$THREADS --out=$ROOT/$LOG" \
    > "$report" 2>&1 || { mv "$report" "$LOG/world-rollout-$stamp.failed.txt"; echo "FAILED -- see $LOG/world-rollout-$stamp.failed.txt"; exit 1; }
grep -v "^SkatKlar solver" "$report"
echo "world-rollout finished $(date '+%Y-%m-%d %H:%M:%S')"
