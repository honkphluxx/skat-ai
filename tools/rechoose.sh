#!/usr/bin/env bash
# After route 1: does choosing the game again after the skat pay at a table?
#
#   ./tools/rechoose.sh --quick       one seed, 20 boards: does it run, and how long
#   ./tools/rechoose.sh               seeds 11, 12, 13, 400 boards each
#   ./tools/rechoose.sh --worlds=8    the cheaper re-chooser
#
# The declarer-choice labels (tools/declarer-choice.sh, 2026-11-07) put an
# honest re-chooser after the skat at +5.26 [+3.49, +7.04] a declared game over
# the shipped player's choice, four fifths of it a suit game turned into
# Grand -- but by play-outs of the student on both sides against greedy's
# bids. This asks the table: belief-64-shipped-rechoose-<worlds> (the shipped
# player with 64 worlds, its game chosen again on the twelve by ContractSearch)
# against belief-64-shipped, in the AUCTION -- the only mode where the
# declarer chooses the game; at fixed contracts the variant is the same player
# -- with passed-in boards void, every board from all three seats by both
# sides. Both bid alike (the change is after the auction), so a board differs
# only where a declarer chose another game or buried other cards.
#
# Read the pooled line (every board of every seed, the per-board differences
# from the arena's CSVs), the declares and wins-as-declarer rows, and the
# contract table -- the labels expect many more Grands, made at about the
# same rate.
#
# Options: --worlds (16; or 8), --boards, --seeds ("11 12 13"), --threads (16).
# Logs in arena-logs/rechoose/, the summary in arena-logs/summary-rechoose.txt
# (-quick). A match whose report exists is skipped; touch STOP to stop after
# the current match.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${RC_ORIGINAL:-}" ]; then
    RC_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/rechoose.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    RC_ORIGINAL="$RC_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$RC_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=400; SEEDS="11 12 13"; WORLDS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --boards=*)   BOARDS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --worlds=*)   WORLDS="${arg#*=}" ;;
        -h|--help)    sed -n '2,29p' "$RC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
case "$WORLDS" in 8|16) ;; *) echo "--worlds is 8 or 16 (the registered re-choosers)" >&2; exit 2 ;; esac
for n in "$THREADS" "$BOARDS"; do case "$n" in ''|*[!0-9]*|0) echo "not a positive whole number: $n" >&2; exit 2 ;; esac; done
for s in $SEEDS; do case "$s" in ''|*[!0-9]*) echo "a seed is a whole number, not $s" >&2; exit 2 ;; esac; done

LOG=arena-logs/rechoose
SUMMARY=arena-logs/summary-rechoose.txt
if $QUICK; then
    LOG=arena-logs/quick/rechoose
    SUMMARY=arena-logs/quick/summary-rechoose.txt
    BOARDS=20
    SEEDS="11"
fi
mkdir -p "$LOG" "$(dirname "$SUMMARY")"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }

A="belief-64-shipped-rechoose-$WORLDS"
B=belief-64-shipped
say "============================================================"
say "rechoose started $(date '+%Y-%m-%d %H:%M:%S')   $A vs $B, auction, passed-in void, seeds $SEEDS, $BOARDS boards, $THREADS threads"

CSVS=""
for SEED in $SEEDS; do
    tag="$A-vs-$B-auction-void-s$SEED-b$BOARDS"
    report="$LOG/$tag.txt"
    CSVS="$CSVS $LOG/$tag.csv"
    if [ -f "$report" ]; then
        say "  [skip] $tag -- report exists"
    else
        [ -f STOP ] && { say "STOP file found -- stopping."; break; }
        say "  [$(date '+%H:%M:%S')] $tag"
        start=$(date +%s)
        ./gradlew --console=plain -q --no-daemon :arena:arena \
            --args="--a=$A --b=$B --boards=$BOARDS --seed=$SEED --threads=$THREADS --passed-in=void --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; continue; }
        mv "$report.partial" "$report"
        say "      took $(( ($(date +%s) - start) / 60 )) min"
    fi
    grep -E "carried no signal|^game pts/game|^  from declaring|^declares|^wins as declarer|^overbid|^rule violations|^  (Diamonds|Hearts|Spades|Clubs|Grand|Null) | = .*game pts/game|^Resolved|^Not resolved" \
        "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
done

# Every board of every seed, one difference each: the boards are independent.
files=""; for f in $CSVS; do [ -f "$f" ] && files="$files $f"; done
if [ -n "$files" ]; then
    # shellcheck disable=SC2086 -- the file list is deliberately word-split
    awk -F, -v label="$A minus $B" 'FNR > 1 && $2 != "" { n++; s += $2; ss += $2 * $2 }
        END { if (n < 2) { printf "  pooled: %d boards, nothing to pool\n", n; exit }
              m = s / n; sd = sqrt((ss - n * m * m) / (n - 1)); h = 1.96 * sd / sqrt(n)
              printf "  pooled over %d boards: %s %+.3f game pts/game, 95%% [%+.3f, %+.3f]\n", n, label, m, m - h, m + h }' \
        $files | tee -a "$SUMMARY"
fi
say "rechoose finished $(date '+%Y-%m-%d %H:%M:%S')"
