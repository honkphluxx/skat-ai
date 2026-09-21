#!/usr/bin/env bash
# How far is the shipped player's declaring from par, with both declarers
# facing the same defence?
#
#   ./tools/declaring-par.sh             three seeds, 200 boards each
#   ./tools/declaring-par.sh --quick     one seed, 30 boards
#   ./tools/declaring-par.sh --seeds="14 15 16"   more seeds; matches already on
#                                        disk are skipped, and the closing line
#                                        pools every seed found in the log folder
#
# "From declaring" is a number about a declarer AND the two players defending
# against it, and it only compares like with like when the defenders are the
# same. The first version of this script got that wrong: it ran "us vs solver"
# and read both declaring columns, which puts OUR declarer against the solver's
# double-dummy defence and the SOLVER's declarer against ours. It reported a
# gap of 17.8 against the plan's 8, and five different players of ours all read
# -21.9x with exactly 38.01% wins -- because against perfect defence a declarer
# wins roughly when the ten it kept are cold, and every one of them buries the
# same heuristic pair. The August reference numbers had the same issue in the
# other direction: belief's -5.19 was against search defenders, the solver's
# +2.91 against expert's.
#
# So par is measured as two declarers against one defence, ours:
#
#   solver declaring, we defend   <- "us vs solver", the solver's column
#   we declare,       we defend   <- "us vs us", a self-match
#
# Same seed, same boards, same fixed contracts from greedy's auction, same
# defenders. The difference is declaring skill and nothing else. Phase D's gate
# is 4.
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
        --seeds=*)    SEEDS_ARG="${arg#*=}" ;;
        -h|--help)    sed -n '2,29p' "$PAR_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

US=belief-32-shipped
LOG=arena-logs/par
SUMMARY=arena-logs/summary-par.txt
BOARDS=200
SEEDS="11 12 13"
if $QUICK; then LOG=arena-logs/quick/par; SUMMARY=arena-logs/quick/summary-par.txt; BOARDS=30; SEEDS="11"; fi
[ -n "${SEEDS_ARG:-}" ] && SEEDS="$SEEDS_ARG"
mkdir -p "$LOG" "$(dirname "$SUMMARY")"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }

# Runs a fixed-contract match unless its log exists; prints nothing.
run() {
    local a="$1" b="$2" tag="$3"
    local report="$LOG/$tag.txt"
    [ -f "$report" ] && return 0
    [ -f STOP ] && return 1
    say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
    ./gradlew --console=plain -q --no-daemon :arena:arena \
        --args="--a=$a --b=$b --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; return 1; }
}
# The "from declaring" value in column 1 or 2 of a report.
declaring() { grep -E "^  from declaring" "$1" | awk -v c="$2" '{print $(2 + c)}'; }

say "============================================================"
say "declaring-par started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"
for SEED in $SEEDS; do
    suffix="s$SEED"; $QUICK && suffix="$suffix-b$BOARDS"
    vs_solver="$US-vs-solver-cardplay-$suffix"
    self="$US-vs-$US-cardplay-$suffix"
    run "$US" solver "$vs_solver" || continue
    run "$US" "$US" "$self" || continue
    solver_decl=$(declaring "$LOG/$vs_solver.txt" 2)
    our_decl=$(declaring "$LOG/$self.txt" 1)
    gap=$(awk -v s="$solver_decl" -v u="$our_decl" 'BEGIN { printf "%.2f", s - u }')
    say "  seed $SEED: against our defence, solver declares $solver_decl, we declare $our_decl -> gap $gap"
done
# Every seed with both logs on disk, not only this run's: the question is the
# gap, and seeds added later belong in the same interval. The per-board files
# carry only each match's total, so the interval comes from the spread between
# seeds -- a t interval, since with a handful of seeds the normal one is
# optimistic by a factor that matters.
gaps=""
for self_log in "$LOG"/$US-vs-$US-cardplay-s*.txt; do
    [ -f "$self_log" ] || continue
    tag=$(basename "$self_log" .txt); tag=${tag#$US-vs-$US-cardplay-}
    vs_log="$LOG/$US-vs-solver-cardplay-$tag.txt"
    [ -f "$vs_log" ] || continue
    s=$(declaring "$vs_log" 2); u=$(declaring "$self_log" 1)
    [ -n "$s" ] && [ -n "$u" ] && gaps="$gaps $(awk -v s="$s" -v u="$u" 'BEGIN { print s - u }')"
done
echo "$gaps" | awk '{
    n = NF; if (n < 2) { print "  pooled: need two seeds"; exit }
    for (i = 1; i <= n; i++) sum += $i; mean = sum / n
    for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
    se = sqrt(ss / (n - 1)) / sqrt(n)
    split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " ")
    q = (n - 1 <= 9) ? t[n - 1] : 1.96
    printf "  pooled over %d seeds: gap %.2f, 95%% [%.1f, %.1f]  (the gate is 4)\n", n, mean, mean - q * se, mean + q * se
}' | tee -a "$SUMMARY"
say "declaring-par finished $(date '+%Y-%m-%d %H:%M:%S')"
