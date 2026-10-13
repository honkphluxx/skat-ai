#!/usr/bin/env bash
# Should the declarer's near-ties go to the play-outs too? (docs/training-plan.md 2.10, R)
#
#   ./tools/tie-margin.sh                    margins 2, 4 and 8, seeds 11-16 and 21
#   ./tools/tie-margin.sh --quick             margin 4, one seed of 20 boards: the plumbing
#   ./tools/tie-margin.sh --margins="3 6" --seeds="11 12 13" --threads=20
#
# best hands the play-outs the declarer's exact ties (tie0-decl): every card
# at the top vote. The declaring audit against SkatZero (2026-10-13) found
# what best leaves: won games thrown, the throwing card chosen over a winning
# one outvoted narrowly (9 of 20, within a tenth of the 64 worlds), widely
# (7) or tied (4) -- and a vote that flatters the card it picks (played at
# 51-75% of the worlds it wins 49% of the time). A margin k hands every card
# within k worlds of the best vote to the play-outs, which choose among them
# by playing them out, where one lucky vote cannot decide.
#
#   best at k   po:belief-64-shipped/rollout-32x4/lost-decl,lost-def,tie<k>-decl
#   against     belief-64-shipped, fixed contracts, 200 boards a seed
#
# k = 0 is best as measured in the play-out study (arena-logs/playout-study/
# best-s*), so each margin is pooled and paired with it by seed. The tie
# counters split each run's handed ties into those at the full vote and those
# short of it. Wider margins hand the play-outs more of the search's own
# decisions, where they judge by the student's play rather than by the
# solver; the curve over k says where that stops paying.
#
# Reports are written as .partial and renamed when complete, so a killed one
# is redone; touch STOP to stop after the current one. About 35-40 minutes a
# seed and margin at 16 threads: 12-14 hours for three margins. Read
# arena-logs/summary-tie-margin.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${TM_ORIGINAL:-}" ]; then
    TM_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/tie-margin.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    TM_ORIGINAL="$TM_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$TM_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; MARGINS="2 4 8"; SEEDS="11 12 13 14 15 16 21"
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --margins=*)  MARGINS="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        -h|--help)    sed -n '2,32p' "$TM_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin for the play-outs' student" >&2; exit 2; }
if $QUICK; then BOARDS=20; MARGINS="4"; SEEDS="11"; fi
for k in $MARGINS; do
    case "$k" in ''|*[!0-9]*) echo "a margin is a whole number of worlds, not $k" >&2; exit 2 ;; esac
done

T=belief-64-shipped
LOG=arena-logs/tie-margin
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-tie-margin.txt
$QUICK && SUMMARY=arena-logs/summary-tie-margin-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "tie margin started $(date '+%Y-%m-%d %H:%M:%S')   margins=$MARGINS boards=$BOARDS threads=$THREADS seeds=$SEEDS"

for SEED in $SEEDS; do
    for k in $MARGINS; do
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        P="po:$T/rollout-32x4/lost-decl,lost-def,tie$k-decl"
        tag="k$k-s$SEED-b$BOARDS"
        report="$LOG/$tag.txt"
        if [ ! -f "$report" ]; then
            say "  [$(date '+%H:%M:%S')] $tag   $P"
            start=$(date +%s)
            ./gradlew --console=plain -q --no-daemon :arena:arena \
                --args="--a=$P --b=$T --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; continue; }
            mv "$report.partial" "$report"
            say "      took $(( ($(date +%s) - start) / 60 )) min"
        fi
        grep -E " = .*game pts/game|^  tied, declarer|^  ties, declarer|play-outs' cost" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    done
done

diff_of() { [ -n "$1" ] && [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
pool() {
    awk -v label="$1" '{
        n = NF; if (n < 2) { printf "  %s: %d seed(s), nothing to pool\n", label, n; exit }
        for (i = 1; i <= n; i++) sum += $i; mean = sum / n
        for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
        se = sqrt(ss / (n - 1)) / sqrt(n)
        split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
        printf "  %s, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", label, n, mean, mean - q * se, mean + q * se
    }'
}
say "--- pooled, each against $T ---"
base=""; for SEED in $SEEDS; do b=$(diff_of "arena-logs/playout-study/best-s$SEED-b$BOARDS.txt"); [ -n "$b" ] && base="$base $b"; done
echo "$base" | pool "best at margin 0 (the play-out study)" | tee -a "$SUMMARY"
for k in $MARGINS; do
    own=""; paired=""
    for SEED in $SEEDS; do
        x=$(diff_of "$LOG/k$k-s$SEED-b$BOARDS.txt"); y=$(diff_of "arena-logs/playout-study/best-s$SEED-b$BOARDS.txt")
        [ -n "$x" ] && own="$own $x"
        [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$own" | pool "best at margin $k" | tee -a "$SUMMARY"
    echo "$paired" | pool "    margin $k minus 0, same seeds" | tee -a "$SUMMARY"
done
say "tie margin finished $(date '+%Y-%m-%d %H:%M:%S')"
