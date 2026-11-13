#!/usr/bin/env bash
# After route 1: does choosing the game again after the skat pay at a table?
#
#   ./tools/rechoose.sh --quick       one seed, 20 boards: does it run, and how long
#   ./tools/rechoose.sh               seeds 11, 12, 13, 400 boards each
#   ./tools/rechoose.sh --worlds=8    the cheaper re-chooser
#   ./tools/rechoose.sh --vs=xskat    both players against an outside engine
#   ./tools/rechoose.sh --defence=skatzero   both players declaring, SkatZero defending
#   ./tools/rechoose.sh --player=belief-64-shipped-declarer-net --against=belief-64-shipped-rechoose-16
#                                     any two players in the same pairings
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
# --vs=<engine> (xskat, go-skat, jskat-new): instead, each of the two plays
# <engine> on the same boards, and the pooled lines are rechoose - engine,
# shipped - engine, and their difference board by board -- the re-choice's
# worth against a field that is not ourselves.
#
# --defence=<player> (skatzero): both sides seated as roles:<ours>/<player>
# -- ours bids, takes the skat, names the game and plays it as declarer;
# <player> defends -- so every declarer, ours with and without the re-choice,
# meets that defence. SkatZero cannot bid, which is why it can only sit at an
# auction this way; a measurement, never training.
#
# --player replaces the re-chooser (A), --against the shipped player (B): the
# learned declarer, belief-64-shipped-declarer-net (declarer-net-v1/, checked
# against its parity file on load), is measured this way.
#
# Options: --worlds (16; or 8), --player, --against, --boards, --seeds
# ("11 12 13"), --threads (16), --vs, --defence (not both).
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

QUICK=false; THREADS=16; BOARDS=400; SEEDS="11 12 13"; WORLDS=16; VS=""; DEFENCE=""; PLAYER=""; AGAINST=""
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --boards=*)   BOARDS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        --worlds=*)   WORLDS="${arg#*=}" ;;
        --vs=*)       VS="${arg#*=}" ;;
        --defence=*)  DEFENCE="${arg#*=}" ;;
        --player=*)   PLAYER="${arg#*=}" ;;
        --against=*)  AGAINST="${arg#*=}" ;;
        -h|--help)    sed -n '2,49p' "$RC_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
case "$WORLDS" in 8|16) ;; *) echo "--worlds is 8 or 16 (the registered re-choosers)" >&2; exit 2 ;; esac
[ -n "$VS" ] && [ -n "$DEFENCE" ] && { echo "--vs or --defence, not both" >&2; exit 2; }
case "$VS$DEFENCE$PLAYER$AGAINST" in *[!A-Za-z0-9._-]*) echo "a player id is letters, digits, '.', '_' and '-'" >&2; exit 2 ;; esac
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

A="${PLAYER:-belief-64-shipped-rechoose-$WORLDS}"
B="${AGAINST:-belief-64-shipped}"
[ "$A" = "$B" ] && { echo "--player and --against are the same player" >&2; exit 2; }
if [ -n "$DEFENCE" ]; then A="roles:$A/$DEFENCE"; B="roles:$B/$DEFENCE"; fi
# A file name of a player spec: roles:x/y -> roles_x_y.
name_of() { printf '%s' "$1" | tr ':/' '__'; }
say "============================================================"
say "rechoose started $(date '+%Y-%m-%d %H:%M:%S')   $A vs ${VS:-$B}${VS:+ (and $B vs $VS)}, auction, passed-in void, seeds $SEEDS, $BOARDS boards, $THREADS threads"

match() {  # a b -> plays the match unless its report exists; prints the tag
    local a="$1" b="$2"
    local tag="$(name_of "$a")-vs-$(name_of "$b")-auction-void-s$SEED-b$BOARDS"
    local report="$LOG/$tag.txt"
    if [ -f "$report" ]; then
        say "  [skip] $tag -- report exists" >&2
    elif [ -f STOP ]; then
        say "STOP file found -- stopping." >&2; return 1
    else
        say "  [$(date '+%H:%M:%S')] $tag" >&2
        local start=$(date +%s)
        if ./gradlew --console=plain -q --no-daemon :arena:arena \
            --args="--a=$a --b=$b --boards=$BOARDS --seed=$SEED --threads=$THREADS --passed-in=void --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report.partial" 2>&1; then
            mv "$report.partial" "$report"
            say "      took $(( ($(date +%s) - start) / 60 )) min" >&2
        else
            mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt" >&2; return 1
        fi
    fi
    grep -E "carried no signal|^game pts/game|^  from declaring|^declares|^wins as declarer|^overbid|^rule violations|^  (Diamonds|Hearts|Spades|Clubs|Grand|Null) | = .*game pts/game|^Resolved|^Not resolved" \
        "$report" | sed 's/^/      /' | tee -a "$SUMMARY" >&2
    echo "$LOG/$tag.csv"
}

# Every board of every seed, one difference each: the boards are independent.
pool() {  # label files...
    local label="$1"; shift
    [ $# -gt 0 ] || return 0
    awk -F, -v label="$label" 'FNR > 1 && $2 != "" { n++; s += $2; ss += $2 * $2 }
        END { if (n < 2) { printf "  pooled: %d boards, nothing to pool\n", n; exit }
              m = s / n; sd = sqrt((ss - n * m * m) / (n - 1)); h = 1.96 * sd / sqrt(n)
              printf "  pooled over %d boards: %s %+.3f game pts/game, 95%% [%+.3f, %+.3f]\n", n, label, m, m - h, m + h }' \
        "$@" | tee -a "$SUMMARY"
    # Seeger-Fabian beside it, where every CSV kept the column (the arena
    # writes it from 2026-11-13): reported, never decided on.
    awk -F, -v label="$label" 'FNR == 1 { if ($3 != "diff_tournament_points") bad = 1; next }
        $3 != "" { n++; s += $3; ss += $3 * $3 }
        END { if (bad || n < 2) exit
              m = s / n; sd = sqrt((ss - n * m * m) / (n - 1)); h = 1.96 * sd / sqrt(n)
              printf "  pooled over %d boards: %s %+.3f tournament pts/game, 95%% [%+.3f, %+.3f]\n", n, label, m, m - h, m + h }' \
        "$@" | tee -a "$SUMMARY"
}

FIRST=(); SECOND=(); PAIRS=()
for SEED in $SEEDS; do
    if [ -z "$VS" ]; then
        csv=$(match "$A" "$B") || break
        FIRST+=("$csv")
    else
        one=$(match "$A" "$VS") || break
        two=$(match "$B" "$VS") || break
        FIRST+=("$one"); SECOND+=("$two")
        # The same seed, the same boards: (A - engine) - (B - engine), board by board.
        paired="$LOG/$(name_of "$A")-minus-$(name_of "$B")-vs-$VS-s$SEED-b$BOARDS.csv"
        awk -F, 'NR == FNR { if (FNR == 1) ta = ($3 == "diff_tournament_points"); else { a[$1] = $2; t[$1] = $3 }; next }
                 FNR == 1 { tp = ta && ($3 == "diff_tournament_points")
                            print tp ? "board,diff_game_points,diff_tournament_points" : "board,diff_game_points"; next }
                 ($1 in a) { if (tp) printf "%s,%.4f,%.4f\n", $1, a[$1] - $2, t[$1] - $3
                             else printf "%s,%.4f\n", $1, a[$1] - $2 }' "$one" "$two" > "$paired"
        PAIRS+=("$paired")
    fi
done
existing() { local f; for f in "$@"; do [ -f "$f" ] && printf '%s\n' "$f"; done; }
if [ -z "$VS" ]; then
    mapfile -t files < <(existing "${FIRST[@]}"); pool "$A minus $B" "${files[@]}"
else
    mapfile -t files < <(existing "${FIRST[@]}"); pool "$A minus $VS" "${files[@]}"
    mapfile -t files < <(existing "${SECOND[@]}"); pool "$B minus $VS" "${files[@]}"
    mapfile -t files < <(existing "${PAIRS[@]}"); pool "the re-choice against $VS: ($A - $VS) - ($B - $VS)" "${files[@]}"
fi
say "rechoose finished $(date '+%Y-%m-%d %H:%M:%S')"
