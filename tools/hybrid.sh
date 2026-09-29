#!/usr/bin/env bash
# I4: the student plays the opening, the search the rest (docs/training-plan.md 2.10).
#
#   ./tools/hybrid.sh                    the night: weights, timing, then the pairings below
#   ./tools/hybrid.sh --quick            one seed of 30 boards per pairing, 4 timing boards
#   ./tools/hybrid.sh --time-only        the timing table and nothing else (minutes)
#   ./tools/hybrid.sh --pairs="hybrid-64-t2:belief-64-shipped" --seeds="17 18 19"
#                                        other pairings or more seeds; the pooled
#                                        line reads every seed on disk
#   ./tools/hybrid.sh --threads=12
#   ./tools/hybrid.sh --python=/c/Python312/python.exe
#
# The players (arena/src/.../BeliefPlayers.java, registered when policy-model/
# holds policy.bin):
#
#   hybrid-64-t1/t2/t3   the student for the first 1, 2 or 3 tricks of a suit or
#                        grand game, then the 64-world teacher (belief-64-shipped)
#   hybrid-32-t2         the student for 2 tricks, then the 32-world shipped player
#   student-64           the student wherever it can play (Null stays with search)
#
# 1. weights   policy-model/policy.bin from policy.pt (export_policy.py), once
# 2. timing    ms a card decision by trick, and card-play seconds a game, for the
#              shipped player, the teacher and the hybrids, on the same boards
#              (CardTimeMain; the ratio is what carries over to the phone)
# 3. pairings  fixed contracts, exact pairing, pooled over seeds:
#
#   hybrid-64-t2 vs belief-64-shipped   does handing the opening to the student
#                                       lose anything against the teacher? (6 seeds)
#   hybrid-64-t2 vs belief-32-shipped   the ship question: teacher strength at
#                                       phone cost -- T1 measured +1.67 for the
#                                       teacher itself on seeds 11-16 (6 seeds)
#   hybrid-64-t1, -t3 vs belief-64-shipped   where to hand over (3 seeds each)
#   student-64 vs belief-32-shipped     the student alone: what the search adds
#
# About six hours at 16 threads; each report is skipped when it exists, so the
# same command resumes. Read arena-logs/summary-hybrid.txt in the morning.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${HYBRID_ORIGINAL:-}" ]; then
    HYBRID_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/hybrid.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    HYBRID_ORIGINAL="$HYBRID_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$HYBRID_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; TIME_ONLY=false; PAIRS_ARG=""; SEEDS_ARG=""
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --time-only)   TIME_ONLY=true ;;
        --pairs=*)     PAIRS_ARG="${arg#*=}" ;;
        --seeds=*)     SEEDS_ARG="${arg#*=}" ;;
        --python=*)    PYTHON="${arg#*=}" ;;
        -h|--help)     sed -n '2,38p' "$HYBRID_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOGROOT=arena-logs; BOARDS=200; TIME_BOARDS=30
LONG="11 12 13 14 15 16"; SHORT="11 12 13"
if $QUICK; then LOGROOT=arena-logs/quick; BOARDS=30; TIME_BOARDS=4; LONG="11"; SHORT="11"; fi
SUMMARY=$LOGROOT/summary-hybrid.txt
LOG=$LOGROOT/hybrid; mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "hybrid started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"

# 1. The weights the Java player reads, written once from the trained student.
if [ ! -f policy-model/policy.bin ] || [ ! -f policy-model/policy-parity.bin ]; then
    if [ -z "$PYTHON" ]; then
        for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python ../.venv/bin/python .venv/bin/python; do
            [ -x "$candidate" ] && PYTHON="$candidate" && break
        done
    fi
    if [ -z "$PYTHON" ] || ! "$PYTHON" -c "import torch" 2>/dev/null; then
        say "  no policy-model/policy.bin and no interpreter with torch to write it; pass --python=..."; exit 2
    fi
    say "  [$(date '+%H:%M:%S')] export_policy policy-model"
    if ! "$PYTHON" arena/python/export_policy.py --model=policy-model > "$LOG/export-policy.txt" 2>&1; then
        tail -5 "$LOG/export-policy.txt" | sed 's/^/      /' | tee -a "$SUMMARY"; say "  EXPORT FAILED"; exit 1
    fi
    sed 's/^/      /' "$LOG/export-policy.txt" | tee -a "$SUMMARY"
fi

# 2. The timing table.
tag=card-time-b$TIME_BOARDS
if [ -f "$LOG/$tag.txt" ]; then
    say "  [skip] timing -- $LOG/$tag.txt exists"
else
    say "  [$(date '+%H:%M:%S')] timing, $TIME_BOARDS boards"
    ./gradlew --console=plain -q --no-daemon :arena:cardTime \
        --args="--players=belief-32-shipped,belief-64-shipped,hybrid-64-t1,hybrid-64-t2,hybrid-64-t3,hybrid-32-t2,student-64 --boards=$TIME_BOARDS --seed=11" \
        > "$LOG/$tag.txt" 2>&1 || { mv "$LOG/$tag.txt" "$LOG/$tag.failed.txt"; say "      TIMING FAILED -- see $tag.failed.txt"; exit 1; }
fi
grep -E "^ms a decision|^belief|^hybrid|^student|cores" "$LOG/$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
$TIME_ONLY && { say "hybrid finished $(date '+%Y-%m-%d %H:%M:%S')"; exit 0; }

# 3. The pairings: "player:against:seeds" with the seeds spelled out.
if [ -n "$PAIRS_ARG" ]; then
    PAIRS=""
    for p in ${PAIRS_ARG//,/ }; do PAIRS="$PAIRS;$p:${SEEDS_ARG:-$SHORT}"; done
else
    PAIRS="hybrid-64-t2:belief-64-shipped:$LONG;hybrid-64-t2:belief-32-shipped:$LONG;hybrid-64-t1:belief-64-shipped:$SHORT;hybrid-64-t3:belief-64-shipped:$SHORT;student-64:belief-32-shipped:$SHORT"
fi
IFS=';' read -r -a PAIR_LIST <<< "$PAIRS"
for pair in "${PAIR_LIST[@]}"; do
    [ -z "$pair" ] && continue
    P=${pair%%:*}; rest=${pair#*:}; AGAINST=${rest%%:*}; SEEDS=${rest#*:}
    say "--- $P against $AGAINST, fixed contracts, seeds $SEEDS ---"
    for SEED in $SEEDS; do
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        tag="$P-vs-$AGAINST-cardplay-s$SEED"; $QUICK && tag="$tag-b$BOARDS"
        report="$LOG/$tag.txt"
        if [ ! -f "$report" ]; then
            say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
            ./gradlew --console=plain -q --no-daemon :arena:arena \
                --args="--a=$P --b=$AGAINST --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
        fi
        grep -E "^  from declaring|^  from defending| = .*game pts/game|games/s" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    done
    diffs=""
    for report in "$LOG/$P-vs-$AGAINST"-cardplay-s*.txt; do
        [ -f "$report" ] || continue
        case "$report" in *-b[0-9]*.txt) $QUICK || continue ;; *) $QUICK && continue ;; esac
        d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'); [ -n "$d" ] && diffs="$diffs $d"
    done
    echo "$diffs" | awk -v label="$P" -v against="$AGAINST" '{
        n = NF; if (n < 2) { print "  " label " - " against ": one seed, nothing to pool"; exit }
        for (i = 1; i <= n; i++) sum += $i; mean = sum / n
        for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
        se = sqrt(ss / (n - 1)) / sqrt(n)
        split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
        printf "  %s - %s, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", label, against, n, mean, mean - q * se, mean + q * se
    }' | tee -a "$SUMMARY"
done
say "hybrid finished $(date '+%Y-%m-%d %H:%M:%S')"
