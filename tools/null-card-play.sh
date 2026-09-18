#!/usr/bin/env bash
# Null card play: the one contract where this player is measurably behind.
#
#   ./tools/null-card-play.sh              three seeds, ~135 Null boards each
#   ./tools/null-card-play.sh --quick      one seed, ~18 boards: does it run
#   ./tools/null-card-play.sh --boards=3000 --threads=8
#
# Why this exists. At contracts the double-dummy cheat holds, our player wins
# 39% of Nulls and SkatZero wins 78%, while at trump contracts the two are
# level (arena/README.md, 2026-09-17 third). Null is 3% of the oracle's
# contract mix, so the overnight run cannot measure a change there: six games
# a seed is enough to notice a gap and nowhere near enough to close one.
# `--contracts=null` (NullContractSource) prices Null on every board a seat can
# hold one on, which is about one board in eleven, so 1500 attempted boards buy
# roughly 135 played ones. That is a small board count by this arena's
# standards and enough here for one reason: a Null swings 69 game points
# between won and lost, so a ten-point shift in the declarer's win rate is
# worth about two game points a game, where the mechanisms measured on trump
# contracts were worth one. Null is also slow -- NullSolver has no native
# backend, so every world is a Java search -- and a night is finite.
#
# The two levers, each its own contestant against the shipped player, exactly
# paired because only the one thing differs:
#
#   belief-32-null-rank       break a Null's ties by Null's own rank instead of
#                             by card points. The shipped order sorts a Null by
#                             a quantity the contract does not score -- asked to
#                             separate a ten from a queen it takes the queen,
#                             three card points against ten, while in Null the
#                             ten is the lower card. The replacement is one rule
#                             with two directions: shed the highest safe card
#                             (the declarer's high cards are the danger, and a
#                             defender's are worth nothing), except as a defender
#                             in front of a declarer that has not yet played,
#                             where the low card is the ammunition that forces it
#                             over. See RuleTiebreak.nullOrder.
#   belief-32-null-128        sample 128 worlds at a Null instead of 32. A Null
#                             solve is a yes/no question with no points to count
#                             and is about an order of magnitude cheaper than a
#                             trump game's, and the belief model is blind to Null
#                             (0 decision points in 5.1 million records), so Null
#                             worlds are drawn uniformly and more of them is the
#                             one lever that costs only time.
#   belief-32-null-rank-128   both, which is the candidate if both hold up.
#   belief-32-null-rank-256   and the same with the world count doubled again,
#                             to see whether more worlds keep paying.
#
# Read in the morning from arena-logs/summary-null.txt. The row that decides is
# each variant against `belief-32-adaptive-margin-ties`, the shipped player, in
# exact pairing on Null boards. A variant that wins there still has to be
# non-negative on the ordinary overnight gates before it ships: none of this
# touches a trump game by construction (RuleTiebreakTest pins that), but "by
# construction" is a claim the arena has disagreed with before.
#
# The order is variant-major: all three seeds of the rank tiebreak first, then
# the world counts. An interrupted run therefore leaves a complete answer about
# the cheapest lever rather than a third of an answer about four.
#
# Resumable: a match whose log exists is skipped, a STOP file in the repository
# root or Ctrl-C stops it between matches.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${NULL_ARENA_ORIGINAL:-}" ]; then
    NULL_ARENA_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/null-card-play.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    NULL_ARENA_ORIGINAL="$NULL_ARENA_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$NULL_ARENA_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

LOG=arena-logs
SUMMARY=arena-logs/summary-null.txt
THREADS=16
# About one board in eleven can hold a Null, so this is ~135 played boards.
BOARDS=1500
SEEDS="11 12 13"
QUICK=false
for arg in "$@"; do
    case "$arg" in
        --quick)     QUICK=true ;;
        --boards=*)  BOARDS="${arg#*=}" ;;
        --threads=*) THREADS="${arg#*=}" ;;
        --seeds=*)   SEEDS="${arg#*=}" ;;
        -h|--help)   sed -n '2,52p' "$NULL_ARENA_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if $QUICK; then LOG=arena-logs/quick; SUMMARY=$LOG/summary-null.txt; BOARDS=200; SEEDS="11"; fi
mkdir -p "$LOG"

say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }

SHIPPED=belief-32-adaptive-margin-ties
VARIANTS="belief-32-null-rank belief-32-null-128 belief-32-null-rank-128 belief-32-null-rank-256"

match() {
    local a="$1" b="$2"
    local tag="$a-vs-$b-nullplay-s$SEED"
    $QUICK && tag="$tag-b$BOARDS"
    local report="$LOG/$tag.txt"
    if [ -f "$report" ]; then say "  [skip] $tag -- log already exists"; return 0; fi
    [ -f STOP ] && return 0
    say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards attempted)"
    ./gradlew --console=plain -q --no-daemon :arena:arena \
        --args="--a=$a --b=$b --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --contracts=null --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1
    if [ $? -ne 0 ]; then
        mv "$report" "$LOG/$tag.failed.txt"
        say "      FAILED -- see $tag.failed.txt"
        return 0
    fi
    # The skipped count belongs in the summary: it is how many boards could not
    # hold a Null, and a run whose yield collapsed would otherwise look like a
    # run with narrow intervals for no stated reason.
    grep -E "board\(s\) scored alike|board\(s\) skipped|^  Null| = .*game pts/game|^Resolved|^Not resolved|rule violations" \
        "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
}

say "============================================================"
say "Null card play started $(date '+%Y-%m-%d %H:%M:%S')   boards=$BOARDS threads=$THREADS seeds=$SEEDS"
for variant in $VARIANTS; do
    [ -f STOP ] && { say "STOP file found -- stopping."; break; }
    say "--- $variant ---"
    for SEED in $SEEDS; do
        match "$variant" "$SHIPPED"
        [ -f STOP ] && break
    done
done
say "Null card play finished $(date '+%Y-%m-%d %H:%M:%S')"
