#!/usr/bin/env bash
# Where does our declarer lose the games par wins with the same ten cards?
#
#   ./tools/declaring-audit.sh             six seeds, 200 boards each (~1 h)
#   ./tools/declaring-audit.sh --quick     seed 11, 30 boards
#   ./tools/declaring-audit.sh --seeds="11 12 13"
#   ./tools/declaring-audit.sh --player=belief-32-shipped-ladder
#                                          a variant; its files carry its id
#
# declaring-par.sh --split prices the card-play share of the gap to par (4.06)
# and cannot say where it is: the arena's per-board CSV is one number a board,
# and for the self-match that carries our declarer's column it is zero on every
# row. This replays the same games -- same boards, same fixed contracts from
# greedy's auction, same provider seeds as the arena's declarer rotation --
# solves the true position before every card, and reads the player's own vote
# at the card that threw the game. See DeclaringAuditMain.
#
# It is a diagnosis, not a gate. The gate for anything that changes how the
# declarer chooses a card stays  ./tools/declaring-par.sh --split .
#
# The first thing it does with the result is a ZERO CHECK against the par logs
# already on disk: for every seed, our declarer's "from declaring" must equal
# the self-match's and par's must equal solver-heuristic-discard's, to the
# cent. If a seed says MISMATCH, the audit replayed different games from the
# ones the gate measured and nothing it prints about that seed should be read.
# (A seed with no par log on disk says "no log" and is not an error.)
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${AUDIT_ORIGINAL:-}" ]; then
    AUDIT_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/declaring-audit.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    AUDIT_ORIGINAL="$AUDIT_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$AUDIT_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --seeds=*)    SEEDS_ARG="${arg#*=}" ;;
        --player=*)   PLAYER_ARG="${arg#*=}" ;;
        -h|--help)    sed -n '2,24p' "$AUDIT_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

US=${PLAYER_ARG:-belief-32-shipped}
PAR=solver-heuristic-discard
LOG=arena-logs/par-audit
PARLOG=arena-logs/par
BOARDS=200
SEEDS="11 12 13 14 15 16"
SUFFIX=""
if $QUICK; then LOG=arena-logs/quick/par-audit; PARLOG=arena-logs/quick/par; BOARDS=30; SEEDS="11"; SUFFIX="-b30"; fi
[ -n "${SEEDS_ARG:-}" ] && SEEDS="$SEEDS_ARG"
mkdir -p "$LOG"

stamp=$(date '+%Y%m%d-%H%M%S')
# A variant's files go in a folder of their own, so they never overwrite the
# shipped player's -- and its report names the player.
[ "$US" != belief-32-shipped ] && LOG="$LOG/$US" && mkdir -p "$LOG"
report="$LOG/declaring-audit-$stamp.txt"
echo "declaring-audit started $(date '+%Y-%m-%d %H:%M:%S')   seeds=$SEEDS boards=$BOARDS threads=$THREADS"
echo "report: $report"

./gradlew --console=plain -q --no-daemon :arena:declaringAudit \
    --args="--player=$US --par=$PAR --seeds=${SEEDS// /,} --boards=$BOARDS --threads=$THREADS --quiet --out=$ROOT/$LOG" \
    > "$report" 2>&1 || { mv "$report" "$LOG/declaring-audit-$stamp.failed.txt"; echo "FAILED -- see $LOG/declaring-audit-$stamp.failed.txt"; exit 1; }

# The "from declaring" value in column 1 or 2 of an arena report.
declaring() { grep -E "^  from declaring" "$1" | awk -v c="$2" '{print $(2 + c)}'; }

{
echo
echo "ZERO CHECK against $PARLOG  (from declaring; the audit's number, then the arena's)"
bad=0
while read -r _ _ seed _ n _ uswins _ usdecl _ parwins _ pardecl; do
    self_log="$PARLOG/$US-vs-$US-cardplay-s$seed$SUFFIX.txt"
    hd_log="$PARLOG/$US-vs-$PAR-cardplay-s$seed$SUFFIX.txt"
    for pair in "us:$usdecl:$self_log:1" "par:$pardecl:$hd_log:2"; do
        IFS=: read -r who mine log col <<< "$pair"
        if [ ! -f "$log" ]; then echo "  seed $seed $who: no log ($log)"; continue; fi
        theirs=$(declaring "$log" "$col")
        if awk -v a="$mine" -v b="$theirs" 'BEGIN { d = a - b; if (d < 0) d = -d; exit !(d < 0.006) }'; then
            echo "  seed $seed $who: $mine = $theirs  ok"
        else
            echo "  seed $seed $who: $mine vs $theirs  MISMATCH"; bad=1
        fi
    done
done < <(grep '^ZERO-CHECK ' "$report")
[ "$bad" = 1 ] && echo "  AT LEAST ONE MISMATCH: the audit did not replay the gate's games. Do not read the tables for that seed."
} | tee -a "$report"

echo
awk '/^ZERO CHECK against/ { exit } /^[0-9]+ boards declared/ { on = 1 } on' "$report"
echo "declaring-audit finished $(date '+%Y-%m-%d %H:%M:%S')   full report: $report"
