#!/usr/bin/env bash
# Phase D, the line choice: does asking for the Schneider rung on a lost board
# close any of the card-play gap to par?
#
#   ./tools/ladder.sh              six seeds; three instruments (about 3 h)
#   ./tools/ladder.sh --quick      seed 11, 30 boards: does it run
#   ./tools/ladder.sh --threads=8
#
# What is being asked. The declaring audit (docs/training-plan.md, Phase D)
# put six sevenths of the card-play gap on boards that are lost against
# perfect defence: there a third of the declarer's decisions have every card
# at zero votes and fall to a tiebreak with no objective, the game is
# Schneidered twice as often as par's, and fewer of the defenders' mistakes
# are drawn. belief-32-shipped-ladder is the shipped player with one thing
# added: when no card reaches 61 in any world, the declarer asks the same
# worlds which card keeps it out of Schneider, and among those tied, which
# holds the rung plus the margin. On every decision where some card still
# wins the two players are identical.
#
# Three instruments, in the order they should be read:
#
#   1. ladder vs shipped, fixed contracts, exact pairing. The sharpest number:
#      common random numbers make every board where the ladder never fired
#      contribute exactly zero. Six seeds pooled by t interval.
#   2. declaring-par.sh --split with the ladder as the player: the gate. The
#      card-play share was 4.06 [1.6, 6.5] for the shipped player.
#   3. declaring-audit.sh on the ladder: why the number moved. The not-cold
#      rows are what the change is aimed at -- our win rate there was 23.8%
#      against par's 30.4%, and the Schneider row 0.69 -- and the cold rows
#      are what it must not touch (415 of 424 won).
#
# Expectation, written before running: the Schneider row (about 0.7) should
# show; of the gift row (up to 3.5) an honest ladder takes some fraction.
# Under about +1 on the split will not resolve in six seeds, which is why
# instrument 1 is read first.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${LADDER_ORIGINAL:-}" ]; then
    LADDER_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/ladder.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    LADDER_ORIGINAL="$LADDER_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$LADDER_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        -h|--help)    sed -n '2,36p' "$LADDER_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LADDER=belief-32-shipped-ladder
SHIPPED=belief-32-shipped
LOG=arena-logs/ladder
SUMMARY=arena-logs/summary-ladder.txt
BOARDS=200
SEEDS="11 12 13 14 15 16"
QUICKFLAG=""
if $QUICK; then
    LOG=arena-logs/quick/ladder; SUMMARY=arena-logs/quick/summary-ladder.txt
    BOARDS=30; SEEDS="11"; QUICKFLAG="--quick"
fi
mkdir -p "$LOG" "$(dirname "$SUMMARY")"

say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }

say "============================================================"
say "ladder started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS"

# --- 1. exact pairing --------------------------------------------------------
say "--- 1. $LADDER vs $SHIPPED, fixed contracts, exact pairing ---"
diffs=""
for SEED in $SEEDS; do
    [ -f STOP ] && break
    tag="$LADDER-vs-$SHIPPED-cardplay-s$SEED"; $QUICK && tag="$tag-b$BOARDS"
    report="$LOG/$tag.txt"
    if [ ! -f "$report" ]; then
        say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
        ./gradlew --console=plain -q --no-daemon :arena:arena \
            --args="--a=$LADDER --b=$SHIPPED --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
    fi
    grep -E "^  from declaring|^wins as declarer|^rule violations| = .*game pts/game|^Resolved|^Not resolved" \
        "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
    d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/')
    [ -n "$d" ] && diffs="$diffs $d"
done
echo "$diffs" | awk '{
    n = NF; if (n < 2) { print "  pooled: need two seeds"; exit }
    for (i = 1; i <= n; i++) sum += $i; mean = sum / n
    for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
    se = sqrt(ss / (n - 1)) / sqrt(n)
    split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " ")
    q = (n - 1 <= 9) ? t[n - 1] : 1.96
    printf "  ladder - shipped, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", n, mean, mean - q * se, mean + q * se
}' | tee -a "$SUMMARY"

# --- 2. the gate ---------------------------------------------------------------
[ -f STOP ] && { say "STOP -- skipping the gate and the audit."; exit 0; }
say "--- 2. declaring-par.sh --split --player=$LADDER (the gate; shipped read 4.06 [1.6, 6.5]) ---"
./tools/declaring-par.sh --split --player=$LADDER --seeds="$SEEDS" --threads=$THREADS $QUICKFLAG \
    | grep -E "seed|share|pooled" | sed 's/^/      /' | tee -a "$SUMMARY"

# --- 3. the audit ----------------------------------------------------------------
[ -f STOP ] && { say "STOP -- skipping the audit."; exit 0; }
say "--- 3. declaring-audit.sh --player=$LADDER (why; shipped: cold 415/424, not-cold wins 23.8% vs par 30.4%) ---"
./tools/declaring-audit.sh --player=$LADDER --seeds="$SEEDS" --threads=$THREADS $QUICKFLAG \
    | sed -n '/ZERO CHECK/,/^3\. FLIPS/p;/gifts in games that were NOT cold/,/^4\./p' | sed 's/^/      /' | tee -a "$SUMMARY"
say "ladder finished $(date '+%Y-%m-%d %H:%M:%S')"
