#!/usr/bin/env bash
# How far is the shipped player's declaring from par, now that it knows the
# contract it is declaring?
#
#   ./tools/declaring-par.sh             three seeds, 200 boards each
#   ./tools/declaring-par.sh --quick     one seed, 30 boards
#
# "About eight game points remain" (arena/README.md) was `belief` against
# `solver` at fixed contracts, read off the "from declaring" line. Two things
# have changed underneath that number.
#
# The solver is a TableObserver: observeFixedContract tells it the contract
# before it discards. Our player was not told, and buried for whatever it would
# have bid -- a different pair on 25% of solver-priced boards, a makeable ten
# 87% of the time against 100%. That was fixed on 2026-09-20
# (SkatExchangeContext.settledContract), so some of the eight may be gone.
#
# And the player is not the one that was measured: since then it gained 128
# Null worlds, the LOW_RANK tiebreak, the v2 belief and the Null-aware one.
#
# Same instrument as the original so the numbers compare: fixed contracts from
# the default auction (greedy's), both sides at the same contract. Read the two
# "from declaring" values; their difference is the gap. Phase D's gate is 4.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${PAR_ORIGINAL:-}" ]; then
    PAR_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/declaring-par.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    PAR_ORIGINAL="$PAR_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$PAR_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        -h|--help)    sed -n '2,25p' "$PAR_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOG=arena-logs/par
SUMMARY=arena-logs/summary-par.txt
BOARDS=200
SEEDS="11 12 13"
if $QUICK; then LOG=arena-logs/quick/par; SUMMARY=arena-logs/quick/summary-par.txt; BOARDS=30; SEEDS="11"; fi
mkdir -p "$LOG" "$(dirname "$SUMMARY")"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }

say "============================================================"
say "declaring-par started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"
for SEED in $SEEDS; do
    [ -f STOP ] && { say "STOP file found -- stopping."; break; }
    tag="belief-32-shipped-vs-solver-cardplay-s$SEED"
    $QUICK && tag="$tag-b$BOARDS"
    report="$LOG/$tag.txt"
    if [ -f "$report" ]; then say "  [skip] $tag -- log already exists"; continue; fi
    say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
    ./gradlew --console=plain -q --no-daemon :arena:arena \
        --args="--a=belief-32-shipped --b=solver --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED"; continue; }
    grep -E "^game pts/game|^  from declaring|^  from defending|^wins as declarer| = .*game pts/game" \
        "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
done
say "declaring-par finished $(date '+%Y-%m-%d %H:%M:%S')"
