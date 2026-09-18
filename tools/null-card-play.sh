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
# hold one on, which is about one board in eleven, so 6000 attempted boards buy
# roughly 540 played ones.
#
# That board count was 1500 for round one, and the reason it was is worth
# recording because it has stopped being true. The comment here used to end
# "Null is also slow -- NullSolver has no native backend, so every world is a
# Java search -- and a night is finite." There is a native backend now and it
# is about twelve times the Java (docs/native-solver.md), so the night that
# bought 414 Null boards a side buys four times as many, and four times the
# boards is half the interval.
#
# Halving it is the point rather than a luxury. Round one asked whether 128
# worlds beat 32 and answered +1.17 [+0.37, +1.98] -- resolved, but only just,
# on an interval 1.6 points wide. Round two asks whether 256 beats 128, which
# is a diminishing return and therefore a smaller effect measured against the
# same noise. At round one's precision that question would most likely have
# come back unresolved, and an unresolved night is a night spent learning
# nothing.
#
# Each variant is its own contestant against the shipped player, exactly
# paired because only the one thing differs.
#
# What the first round (2026-09-18) settled, over 414 Null boards a side:
#
#   belief-32-null-128        +1.17 [+0.37, +1.98], resolved. More worlds pay,
#                             and this now ships -- Opponents.NULL_WORLD_MULTIPLE
#                             gives every level four times its own world count
#                             at a Null.
#   belief-32-null-rank       -1.33 [-1.95, -0.72], resolved WORSE. "Shed the
#                             highest safe card" was wrong, and wrong for a
#                             reason worth keeping: a card that reaches the
#                             tiebreak already survives in every sampled world,
#                             and the Null solver searches to the end of the
#                             hand, so "safe now, dangerous later" is priced
#                             already. What is left is robustness to worlds
#                             nobody sampled, and there the low card is the
#                             wider margin -- the cushion argument again.
#   belief-32-null-rank-128   +0.08: the two cancel, as they should if the
#                             tiebreak costs about what the worlds buy.
#
# So the control is no longer the old shipped player: it is
# belief-32-null-128, which is what ships now. Every variant below differs
# from it by exactly one thing, which round one did not manage -- rank-128
# moved two levers at once and its +0.08 could not say which of them did what.
#
#   belief-32-null-256        does the world count keep paying past 128?
#   belief-32-null-512        and past 256? Worth asking in the same night
#                             now that a Null world is cheap; if 256 pays and
#                             512 does not, the curve has a top and we have
#                             found it in one run instead of three.
#   belief-32-null-low-128    the shipped points order sorts a Null by card
#                             points, which the contract does not score, and
#                             that accident approximates "play low" -- except
#                             between a ten and a court card, where points take
#                             the queen and Null rank takes the ten. LOW_RANK
#                             says it properly. One disagreement, and at the
#                             same 128 worlds as the control it is the only
#                             thing this variant changes.
#
# low-256 is deliberately not here. It moves the tiebreak and the world count
# together, which is the mistake round one made.
#
# VARIANTS=... overrides the list, to re-run a single one.
#
# Read in the morning from arena-logs/summary-null.txt. The row that decides is
# each variant against `belief-32-null-128`, which is the shipped player, in
# exact pairing on Null boards. A variant that wins there still has to be
# non-negative on the ordinary overnight gates before it ships: none of this
# touches a trump game by construction (RuleTiebreakTest pins that), but "by
# construction" is a claim the arena has disagreed with before.
#
# The order is variant-major: all three seeds of the first variant, then the
# next. An interrupted run therefore leaves a complete answer about
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
BOARDS=6000
SEEDS="11 12 13"
QUICK=false
for arg in "$@"; do
    case "$arg" in
        --quick)     QUICK=true ;;
        --boards=*)  BOARDS="${arg#*=}" ;;
        --threads=*) THREADS="${arg#*=}" ;;
        --seeds=*)   SEEDS="${arg#*=}" ;;
        -h|--help)   sed -n '2,76p' "$NULL_ARENA_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if $QUICK; then LOG=arena-logs/quick; SUMMARY=$LOG/summary-null.txt; BOARDS=200; SEEDS="11"; fi
mkdir -p "$LOG"

say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }

SHIPPED=belief-32-null-128
VARIANTS="${VARIANTS:-belief-32-null-256 belief-32-null-512 belief-32-null-low-128}"

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
