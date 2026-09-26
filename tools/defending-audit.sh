#!/usr/bin/env bash
# Do our defenders hand the declarer games that another defence would not?
#
#   ./tools/defending-audit.sh                         SkatZero's defenders, seeds 14-16
#   ./tools/defending-audit.sh --quick
#   ./tools/defending-audit.sh --defenders="skatzero xskat"
#   ./tools/defending-audit.sh --seeds="11 12 13"
#   ./tools/defending-audit.sh --threads=8
#
# The declaring audits put two declarers in front of our defenders; this puts
# our declarer in front of two defences. The player that ships declares every
# board, at greedy's contracts, once against itself in both defender seats and
# once against the other bot in both, with every card solved face up
# (DefendingAuditMain). The first game of every board is the declaring audit's
# own game for that board: the SAME-GAMES line prints our declarer's wins, to
# be held against that audit's on the same seeds -- if they differ, nothing
# below them is about the same games.
#
# docs/training-plan.md 2.9: two thirds of the gifts SkatZero's declarer
# draws from our defenders are a defender's lead, and wrong discards are the
# next. Both are judgements about the declarer's hand. We defend two games in
# three, so if our defenders make those judgements worse than SkatZero's, that
# is where points are. The report, per defence:
#
#   1. the declarer's wins and each side's points
#   2. games the defence had (not cold for the declarer): how many it gave away
#   3. gifts by what the defender was doing -- leading, following, trumping in,
#      discarding, and whose card held the trick -- as a rate over the chances
#   4. the Nulls
#   5. the declarer's own throws against each defence
#   6. our defenders' own tally at each of their gifts -- a tie the tiebreak
#      broke, or worlds that said the gift was the better card -- and how
#      the share of worlds a card holds in compares with how often it gifts
#
# Roughly fifteen minutes a defence at 16 threads (SkatZero is a Python
# helper, about two games a second).
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DEFENDING_ORIGINAL:-}" ]; then
    DEFENDING_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/defending-audit.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DEFENDING_ORIGINAL="$DEFENDING_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DEFENDING_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; PLAYER=belief-32-shipped; DEFENDERS="skatzero"; SEEDS="14 15 16"; BOARDS=200
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --defenders=*) DEFENDERS="${arg#*=}"; DEFENDERS="${DEFENDERS//,/ }" ;;
        --seeds=*)     SEEDS_ARG="${arg#*=}" ;;
        -h|--help)     sed -n '2,39p' "$DEFENDING_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
LOGROOT=arena-logs
if $QUICK; then LOGROOT=arena-logs/quick; SEEDS="14"; BOARDS=30; fi
[ -n "${SEEDS_ARG:-}" ] && SEEDS="$SEEDS_ARG"
if [[ " $DEFENDERS " == *" skatzero "* ]] && [ ! -f "${SKATKLAR_SKATZERO_DIR:-third_party/skatzero}/models/onnx/D_0.onnx" ]; then
    echo "no SkatZero models under third_party/skatzero" >&2; exit 2
fi

for D in $DEFENDERS; do
    LOG=$LOGROOT/defending-audit/$D
    mkdir -p "$LOG"
    stamp=$(date '+%Y%m%d-%H%M%S')
    report="$LOG/defending-audit-$stamp.txt"
    echo "defending-audit $D started $(date '+%Y-%m-%d %H:%M:%S')   seeds=$SEEDS boards=$BOARDS threads=$THREADS"
    echo "report: $report"
    ./gradlew --console=plain -q --no-daemon :arena:defendingAudit \
        --args="--player=$PLAYER --defenders=$D --seeds=${SEEDS// /,} --boards=$BOARDS --threads=$THREADS --out=$ROOT/$LOG" \
        > "$report" 2>&1 || { mv "$report" "$LOG/defending-audit-$stamp.failed.txt"; echo "FAILED -- see $LOG/defending-audit-$stamp.failed.txt"; continue; }
    grep -v "^SkatKlar solver" "$report"
    # The same games as the declaring audit's, or not.
    for s in $SEEDS; do
        mine=$(grep -E "^SAME-GAMES seed $s " "$report" | sed -E 's/.* us-wins ([0-9]+).*/\1/')
        csv=$LOGROOT/par-audit/declaring-audit-games-s$s.csv
        if [ -f "$csv" ]; then
            theirs=$(awk -F, 'NR > 1 && $5 == "us" { w += $7 } END { print w + 0 }' "$csv")
            verdict=OK; [ "$mine" = "$theirs" ] || verdict="DIFFERENT -- not the declaring audit's games"
            echo "  same-games check seed $s: our declarer won $mine here, $theirs in the declaring audit   $verdict"
        else
            echo "  same-games check seed $s: no declaring audit on disk ($csv)"
        fi
    done
done
echo "defending-audit finished $(date '+%Y-%m-%d %H:%M:%S')"
