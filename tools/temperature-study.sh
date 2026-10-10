#!/usr/bin/env bash
# How strong should the play-outs' opponents be? (docs/training-plan.md 2.10, R)
#
#   ./tools/temperature-study.sh                 temperatures 0.5, 2 and 4, against the teacher and XSkat
#   ./tools/temperature-study.sh --quick         one temperature, one seed of 20 boards: the plumbing
#   ./tools/temperature-study.sh --temperatures="0.5 2" --opponents=teacher
#   ./tools/temperature-study.sh --threads=20 --seeds="11 12 13"
#
# best's play-outs draw the opponents' cards from the student's softmax
# (rollout-32x4). A temperature on that draw is the opponent model's
# strength as one number, the student unchanged: below 1 sharper (towards
# the top card), above 1 flatter (a weaker opponent). The one setting
# measured so far is the one knob we know matters -- the top card +0.28, the
# drawn cards +0.93 in the declarer's lost positions -- and two readings fit
# it: a noisier opponent thickens a thin signal, or a noisier opponent
# resembles the real one better (Philipp, 2026-10-09: the play-out model
# and the actual opponent should be of similar strength). The two part
# across opponents. The student is held fixed (policy-model), so the draw
# of its training stays out of it (2026-10-10).
#
#   best at t   po:belief-64-shipped/rollout-32x4t<t>/lost-decl,lost-def,tie0-decl
#   against     belief-64-shipped (seeds 11-16 and 21) and xskat (11-16),
#               fixed contracts, 200 boards a seed
#
# Temperature 1 is on disk already (arena-logs/playout-study/best-s*, and
# arena-logs/best-run/best-vs-xskat-s*); each other temperature is pooled
# and paired with it by seed, per opponent. If the best temperature against
# XSkat is higher than against the teacher, the opponent model wants
# matching; if the same temperature wins against both, it is the signal.
#
# Reports are written as .partial and renamed when complete, so a killed one
# is redone; touch STOP to stop after the current one. About 3.7 hours a
# temperature against the teacher and 1.8 against XSkat at 16 threads: 16-17
# hours for the three. Read arena-logs/summary-temperature-study.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${TS_ORIGINAL:-}" ]; then
    TS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/temperature-study.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    TS_ORIGINAL="$TS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$TS_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; TEMPS="0.5 2 4"; OPPONENTS="teacher xskat"; SEEDS_ARG=""
for arg in "$@"; do
    case "$arg" in
        --quick)           QUICK=true ;;
        --threads=*)       THREADS="${arg#*=}" ;;
        --temperatures=*)  TEMPS="${arg#*=}" ;;
        --opponents=*)     OPPONENTS="${arg#*=}" ;;
        --seeds=*)         SEEDS_ARG="${arg#*=}" ;;
        -h|--help)         sed -n '2,36p' "$TS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                 echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin for the play-outs' student" >&2; exit 2; }
if $QUICK; then BOARDS=20; TEMPS="2"; SEEDS_ARG="11"; fi

T=belief-64-shipped
SCOPE=lost-decl,lost-def,tie0-decl
LOG=arena-logs/temperature-study
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-temperature-study.txt
$QUICK && SUMMARY=arena-logs/summary-temperature-study-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "temperature study started $(date '+%Y-%m-%d %H:%M:%S')   temperatures=$TEMPS opponents=$OPPONENTS boards=$BOARDS threads=$THREADS"

# opponent tag -> its player id, its seeds, where temperature 1's reports are
opponent_id() { case "$1" in teacher) echo "$T" ;; xskat) echo xskat ;; *) echo "$1" ;; esac; }
opponent_seeds() { case "$1" in teacher) echo "11 12 13 14 15 16 21" ;; *) echo "11 12 13 14 15 16" ;; esac; }
base_report() {   # temperature 1 against opponent $1 on seed $2
    case "$1" in
        teacher) echo "arena-logs/playout-study/best-s$2-b$BOARDS.txt" ;;
        xskat)   echo "arena-logs/best-run/best-vs-xskat-s$2-b$BOARDS.txt" ;;
        *)       echo "$LOG/t1-vs-$1-s$2-b$BOARDS.txt" ;;
    esac
}
for opp in $OPPONENTS; do
    if [ "$opp" = xskat ] && [ ! -x third_party/xskat/skatklar-xskat ] && [ ! -x third_party/xskat/skatklar-xskat.exe ]; then
        say "no XSkat build under third_party/xskat -- skipping it"; continue
    fi
    AGAINST=$(opponent_id "$opp")
    SEEDS=$(opponent_seeds "$opp"); [ -n "$SEEDS_ARG" ] && SEEDS="$SEEDS_ARG"
    say "--- against $AGAINST, fixed contracts, seeds $SEEDS ---"
    for SEED in $SEEDS; do
        for temp in $TEMPS; do
            [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
            P="po:$T/rollout-32x4t$temp/$SCOPE"
            tag="t$temp-vs-$opp-s$SEED-b$BOARDS"
            report="$LOG/$tag.txt"
            if [ ! -f "$report" ]; then
                say "  [$(date '+%H:%M:%S')] $tag   $P"
                start=$(date +%s)
                ./gradlew --console=plain -q --no-daemon :arena:arena \
                    --args="--a=$P --b=$AGAINST --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                    > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; continue; }
                mv "$report.partial" "$report"
                say "      took $(( ($(date +%s) - start) / 60 )) min"
            fi
            grep -E " = .*game pts/game|rule violations|^  (lost|tied), " "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
        done
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
say "--- pooled, by opponent ---"
for opp in $OPPONENTS; do
    AGAINST=$(opponent_id "$opp")
    SEEDS=$(opponent_seeds "$opp"); [ -n "$SEEDS_ARG" ] && SEEDS="$SEEDS_ARG"
    base=""; for SEED in $SEEDS; do b=$(diff_of "$(base_report "$opp" "$SEED")"); [ -n "$b" ] && base="$base $b"; done
    echo "$base" | pool "best at temperature 1 - $AGAINST (as measured)" | tee -a "$SUMMARY"
    for temp in $TEMPS; do
        own=""; paired=""
        for SEED in $SEEDS; do
            x=$(diff_of "$LOG/t$temp-vs-$opp-s$SEED-b$BOARDS.txt"); y=$(diff_of "$(base_report "$opp" "$SEED")")
            [ -n "$x" ] && own="$own $x"
            [ -n "$x" ] && [ -n "$y" ] && paired="$paired $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
        done
        echo "$own" | pool "best at temperature $temp - $AGAINST" | tee -a "$SUMMARY"
        echo "$paired" | pool "    temperature $temp minus 1, same seeds" | tee -a "$SUMMARY"
    done
done
say "temperature study finished $(date '+%Y-%m-%d %H:%M:%S')"
