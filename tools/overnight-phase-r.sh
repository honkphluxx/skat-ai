#!/usr/bin/env bash
#
# Phase R of docs/training-plan.md: re-baseline everything the Null fix
# invalidated, and place the two outside engines on the ladder. For Git Bash on
# Windows, from the skat-ai directory or anywhere else.
#
#   ./tools/overnight-phase-r.sh              the whole phase: two nights or so
#   ./tools/overnight-phase-r.sh --quick      one seed, small boards: a rehearsal
#   ./tools/overnight-phase-r.sh --threads=8
#   ./tools/overnight-phase-r.sh --seeds      also purge and regenerate the app's
#                                             challenge-seed pool (off by default:
#                                             it rewrites files under core/)
#
# Stop it any time: create a file called STOP in the skat-ai directory, or press
# Ctrl-C. The running match is killed within seconds and its half-written log
# moved aside; the next start repeats that one match and nothing else. So this
# can be run an hour tonight and the rest tomorrow, and the two halves are one
# run.
#
# What it does, in order, remembering across starts in arena-logs/phase-r/:
#
#   1. builds the outside-engine helpers if their sources are present
#      (tools/build-external-bots.sh -- a missing engine is reported, not fatal)
#   2. ONCE: moves every auction-mode log aside into arena-logs/superseded-*,
#      because the bidder changed in 09a81fa and those numbers are the "before"
#      half of a before-and-after. Fixed-contract and oracle logs are kept: they
#      bypass the auction and stand.
#   3. optionally (--seeds), ONCE: regenerates the challenge-seed pool
#   4. runs tools/overnight-arena.sh, which is itself resumable match by match
#
# Read arena-logs/summary.txt in the morning. Phase R's gate is that the
# ladder is republished with its contract mix, and that the Null column is no
# longer zero.

set -uo pipefail
cd "$(dirname "$0")/.."

STATE=arena-logs/phase-r
mkdir -p "$STATE"
ARENA_ARGS=""
DO_SEEDS=false
for arg in "$@"; do
    case "$arg" in
        --seeds)   DO_SEEDS=true ;;
        -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)         ARENA_ARGS="$ARENA_ARGS $arg" ;;
    esac
done

if [ -f STOP ]; then
    rm -f STOP
    echo "Removed a STOP file left over from an earlier run."
fi

echo "== Phase R  $(date '+%Y-%m-%d %H:%M:%S')"

# 1. The helpers. Cheap, idempotent, and the only step that can leave the
#    ladder shorter than intended -- so it says so out loud.
if [ -f tools/build-external-bots.sh ]; then
    sh tools/build-external-bots.sh || true
fi
for bot in third_party/xskat/skatklar-xskat third_party/go-skat/skatklar-goskat; do
    if [ -x "$bot" ] || [ -x "$bot.exe" ]; then
        echo "   $bot: present"
    else
        echo "   $bot: MISSING -- its matches will be skipped (see docs/external-bots.md)"
    fi
done

# 2. Move the invalidated logs aside, exactly once. overnight-arena.sh has
#    --redo for this, but passing it on every start would move the night's own
#    new logs aside as well and repeat them; the marker is what makes a
#    resumed run and a fresh run the same command.
if [ ! -f "$STATE/redo-done" ]; then
    OLD="arena-logs/superseded-$(date '+%Y%m%d-%H%M%S')-phase-r"
    moved=0
    for f in arena-logs/*-auction-*.txt arena-logs/*-auction-*.csv; do
        [ -e "$f" ] || continue
        mkdir -p "$OLD"
        mv "$f" "$OLD/"
        moved=$((moved + 1))
    done
    echo "   $moved auction-mode log(s) from before the Null fix moved to ${OLD#arena-logs/}"
    date > "$STATE/redo-done"
else
    echo "   auction-mode logs already moved aside on $(cat "$STATE/redo-done")"
fi

# 3. The challenge-seed pool, only when asked, only once. It lives in the app
#    repository one level up and rewrites core/.../ChallengeSeedData.java, which
#    is why it is not on by default: that is a product change to commit on its
#    own, not a side effect of a measurement night.
if $DO_SEEDS; then
    if [ -f "$STATE/seeds-done" ]; then
        echo "   challenge seeds already regenerated on $(cat "$STATE/seeds-done")"
    elif [ -x ../tools/challenge-seeds.sh ] || [ -f ../tools/challenge-seeds.sh ]; then
        echo "   regenerating the challenge-seed pool (purge, then a full audition)"
        ( cd .. && bash tools/challenge-seeds.sh --purge && bash tools/challenge-seeds.sh ) \
            && date > "$STATE/seeds-done" \
            || echo "   challenge seeds FAILED -- carrying on with the matches"
    else
        echo "   ../tools/challenge-seeds.sh not found; skipping the seed pool"
    fi
fi

# 4. The night itself. Everything after this line is overnight-arena.sh's:
#    resumable, stoppable, one log per match.
# shellcheck disable=SC2086
exec ./tools/overnight-arena.sh $ARENA_ARGS
