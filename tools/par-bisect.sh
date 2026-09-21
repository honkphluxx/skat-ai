#!/usr/bin/env bash
# Where did the declaring go? A bisect over players, one seed, one instrument.
#
#   ./tools/par-bisect.sh               seed 12, 200 boards a step (~45 minutes)
#
# declaring-par.sh (2026-09-21) put the shipped player at -21.96 from
# declaring on seed 12, where August's `belief` read -5.19 on the same 171
# boards -- the level `expert` sat at (-23.24). But the solver's own number
# moved as well, +2.91 to -0.45, and the solver has not changed in principle,
# so the ruler may have moved too: different contracts out of greedy's auction,
# or different scoring. Two readings, and they want opposite responses:
#
#   the instrument drifted  -> August's `belief` also reads about -22 today,
#                              nothing is broken, and the gap is simply larger
#                              than the plan thought
#   a player regressed      -> `belief` still reads about -5, and somewhere
#                              between it and the shipped player the declaring
#                              collapsed
#
# So each step adds what was added to the shipped player, in the order it was
# added, all against `solver` on the same boards:
#
#   1  belief                          August's player: calibrates the ruler
#   2  belief-32                       32 worlds, the reference personality
#   3  belief-32-adaptive-margin-ties  the previous shipped player
#   4  belief-32-shipped               today's, on today's model (mixed)
#   5  belief-32-shipped-candidate     today's, on the v2 model it replaced
#
# Read "from declaring" for each. Where it jumps is where to look. Step 5 against
# step 4 separates the model swap from everything else.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${BISECT_ORIGINAL:-}" ]; then
    BISECT_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/par-bisect.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BISECT_ORIGINAL="$BISECT_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BISECT_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

THREADS=16
for arg in "$@"; do
    case "$arg" in
        --threads=*) THREADS="${arg#*=}" ;;
        -h|--help)   sed -n '2,32p' "$BISECT_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
SEED=12
BOARDS=200
LOG=arena-logs/par-bisect
SUMMARY=arena-logs/summary-par-bisect.txt
mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }

step() {
    local n="$1" a="$2" props="${3:-}"
    local tag="$n-$a-vs-solver-cardplay-s$SEED"
    local report="$LOG/$tag.txt"
    if [ -f "$report" ]; then say "  [skip] $tag"; return 0; fi
    [ -f STOP ] && return 0
    say "  [$(date '+%H:%M:%S')] step $n: $a"
    # shellcheck disable=SC2086 -- $props is deliberately word-split
    ./gradlew --console=plain -q --no-daemon $props :arena:arena \
        --args="--a=$a --b=solver --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED"; return 0; }
    grep -E "^[0-9]+ boards|^  from declaring|^wins as declarer" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
}

say "============================================================"
say "par-bisect started $(date '+%Y-%m-%d %H:%M:%S')   seed $SEED, $BOARDS boards a step"
step 1 belief
step 2 belief-32
step 3 belief-32-adaptive-margin-ties
step 4 belief-32-shipped
step 5 belief-32-shipped-candidate "-Dbelief.model.candidate.dir=belief-model-v2"
say "par-bisect finished $(date '+%Y-%m-%d %H:%M:%S')"
