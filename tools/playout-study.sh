#!/usr/bin/env bash
# Where do play-outs against our own student pay? (docs/training-plan.md 2.10, R)
#
#   ./tools/playout-study.sh                 every pairing below, seeds 11-16 and 21
#   ./tools/playout-study.sh --quick         one seed of 20 boards each (the plumbing)
#   ./tools/playout-study.sh --threads=20 --boards=200
#   ./tools/playout-study.sh --only=def-tie --seeds="11 12"
#                                            one pairing (by tag), other seeds; the
#                                            pooled lines read every seed on disk
#
# Our teacher (belief-64-shipped) everywhere, except the positions a scope
# names, which the student decides by playing every candidate card out in the
# search's own worlds (RolloutRescue, FlatHandoverProvider). Always against
# the teacher whole, fixed contracts (greedy's auction), duplicate scoring.
# No SkatZero anywhere.
#
#   po:<search>/<rollout>/<scope>, the scope a comma list of
#     lost-decl   the declarer's lost positions (no card wins in any world)
#     lost-def    the defenders' lost positions (no card beats the contract)
#     tie<k>-decl the declarer's other positions where two or more cards are
#                 within k votes of the best: the play-outs choose among those
#     tie<k>-def  the same for a defender
#   rollout-32x4: 32 of the search's worlds, four play-outs each, the
#     opponents' cards drawn from the student's policy, cards ranked by the
#     game's value (the score sheet's number, schneider and a loss's doubling
#     included), then wins, then card points; rollout-64v: 64 worlds, the top
#     card at every seat, ranked the same way.
#
#   tag          player                                             question
#   decl-lost    po:.../rollout-32x4/lost-decl  (seed 11 only)      the batched play-outs give the
#                                                                   same games as flat:.../rollout-32x4
#                                                                   (teacher-flat-drawn-s11), faster
#   decl-lost-v  po:.../rollout-64v/lost-decl                       drawn defences or value ranking:
#                                                                   which made +0.93 out of +0.22?
#   def-lost     po:.../rollout-32x4/lost-def                       the defenders' lost positions
#   decl-tie     po:.../rollout-32x4/tie0-decl                      the declarer's tied cards: more
#                                                                   points, schneider, a safer win
#   def-tie      po:.../rollout-32x4/tie0-def                       the defenders' tied cards
#   all          po:.../rollout-32x4/lost-decl,lost-def,tie0-decl,tie0-def
#                                                                   everything at once: do the parts add?
#   best         po:.../rollout-32x4/lost-decl,lost-def,tie0-decl   all but the defenders' ties, the one
#                                                                   scope that lost (added 2026-10-06)
#
# The scopes do not overlap (a lost position is never a tie), so `all` should
# come near the sum of its parts, lost-decl's being teacher-flat-drawn
# (+0.93 [+0.47, +1.40]). Each scope's line in the summary is also given
# paired with teacher-flat-drawn on the same seeds where both exist, for
# decl-lost-v, all and best (for a single other scope that difference would
# only rank two scopes, not say what one adds). A report is written as
# .partial and renamed when the run ends, so a killed run is redone.
#
# Seeds run outermost, so after any number of seeds every question has that
# many. Each report is skipped when it exists, so the same command resumes;
# touch STOP to stop after the current one. Needs policy-model/policy.bin
# (the student) and the teacher's models. Read
# arena-logs/summary-playout-study.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${PS_ORIGINAL:-}" ]; then
    PS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/playout-study.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    PS_ORIGINAL="$PS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$PS_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200; ONLY=""; SEEDS="11 12 13 14 15 16 21"
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --boards=*)   BOARDS="${arg#*=}" ;;
        --only=*)     ONLY="${arg#*=}" ;;
        --seeds=*)    SEEDS="${arg#*=}" ;;
        -h|--help)    sed -n '2,58p' "$PS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin for the play-outs' student" >&2; exit 2; }

if $QUICK; then BOARDS=20; SEEDS="11"; fi
LOG=arena-logs/playout-study
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-playout-study.txt
$QUICK && SUMMARY=arena-logs/summary-playout-study-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "play-out study started $(date '+%Y-%m-%d %H:%M:%S')   boards=$BOARDS threads=$THREADS seeds=$SEEDS"

T=belief-64-shipped
# tag|player|only on these seeds (empty: every seed)
PAIRS=(
    "decl-lost|po:$T/rollout-32x4/lost-decl|11"
    "decl-lost-v|po:$T/rollout-64v/lost-decl|"
    "def-lost|po:$T/rollout-32x4/lost-def|"
    "decl-tie|po:$T/rollout-32x4/tie0-decl|"
    "def-tie|po:$T/rollout-32x4/tie0-def|"
    "all|po:$T/rollout-32x4/lost-decl,lost-def,tie0-decl,tie0-def|"
    "best|po:$T/rollout-32x4/lost-decl,lost-def,tie0-decl|"
)

for SEED in $SEEDS; do
    for pair in "${PAIRS[@]}"; do
        IFS='|' read -r TAG P RESTRICT <<< "$pair"
        [ -n "$ONLY" ] && [ "$ONLY" != "$TAG" ] && continue
        [ -n "$RESTRICT" ] && [ -z "$ONLY" ] && ! $QUICK && [[ " $RESTRICT " != *" $SEED "* ]] && continue
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        tag="$TAG-s$SEED-b$BOARDS"
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
        grep -E "^  from declaring|^  from defending| = .*game pts/game|^  (lost|tied), (declarer|defender):|play-outs" "$report" \
            | sed 's/^/      /' | tee -a "$SUMMARY"
    done
done

# The pooled lines: every seed on disk, and paired with teacher-flat-drawn
# (the declarer's lost positions alone, from tools/defence-split.sh).
diff_of() { [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
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
BASE=arena-logs/defence-split/teacher-flat-drawn
for pair in "${PAIRS[@]}"; do
    IFS='|' read -r TAG P RESTRICT <<< "$pair"
    [ -n "$ONLY" ] && [ "$ONLY" != "$TAG" ] && continue
    diffs=""; paired=""
    for report in "$LOG/$TAG"-s*-b"$BOARDS".txt; do
        [ -f "$report" ] || continue
        d=$(diff_of "$report"); [ -n "$d" ] || continue
        diffs="$diffs $d"
        seed=$(basename "$report" | sed -E "s/^$TAG-s([0-9]+)-b.*/\1/")
        b=$(diff_of "$BASE-s$seed-b$BOARDS.txt")
        [ -n "$b" ] && paired="$paired $(awk -v x="$d" -v y="$b" 'BEGIN { printf "%.4f", x - y }')"
    done
    echo "$diffs" | pool "$TAG ($P)" | tee -a "$SUMMARY"
    # Only where the comparison means something: the same positions decided
    # another way (decl-lost-v), or what the other scopes add on top (all, best).
    case "$TAG" in
        decl-lost-v|all|best) echo "$paired" | pool "    $TAG minus teacher-flat-drawn, same seeds" | tee -a "$SUMMARY" ;;
    esac
done
say "play-out study finished $(date '+%Y-%m-%d %H:%M:%S')"
