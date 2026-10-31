#!/usr/bin/env bash
# The play-outs' discard fix: what it moves (docs/training-plan.md 2.10, R).
#
#   ./tools/defender-fix.sh                 the replay check, then five arms, seeds 11-16 and 21
#   ./tools/defender-fix.sh --quick         replay on 2 boards; best and best-tie0c, seed 11, 20 boards
#   ./tools/defender-fix.sh --replay-only   the replay check alone
#   ./tools/defender-fix.sh --only="best best-tie0" --seeds="11 12" --threads=20
#
# The defect (fixed 2026-10-31): when a defender handed a decision to the
# play-outs, the declarer in those play-outs played without its own discard
# (taken from the defender's evidence, where it is absent, instead of the
# world's skat). Only the defenders' scopes ran that path -- lost-def and
# tie*-def; the declarer's scopes, student-64 and the yardstick never did. So
# best's +1.31 and every defender-scope verdict of the play-out study were
# measured with it.
#
# Step 0, the replay (:arena:defenderReplay): every rotation of a few boards of
# seed 11, best against the teacher, in the old form and the new; declarers'
# hand-overs must not move, games must part only at a defender's hand-over
# the fix moved. It counts the defenders' hand-overs and how many moved. A
# broken rule stops the script.
#
# Then each arm against belief-64-shipped, fixed contracts, 200 boards a seed:
#
#   best            lost-decl,lost-def,tie0-decl          paired with the old best
#   best-nolostdef  lost-decl,tie0-decl                   best minus this: lost-def's own share
#   best-tie0       lost-decl,lost-def,tie0-decl,tie0-def paired with best, and with the old `all'
#   best-tie0w      ... ,tie0w-def (held ties only)       paired with best
#   best-tie0c      ... ,tie0c-def (what the cushion leaves) paired with best
#
# all as po:belief-64-shipped/rollout-32x4/<scope>, play-outs by policy-model
# as before. `--only' also takes def-lost, def-tie, def-tie-w and def-tie-c:
# the old single-scope arms again, each paired with its own old report. The old
# reports are read from arena-logs/playout-study/ and never written; this
# writes arena-logs/defender-fix/. best runs first over every seed, then the
# other arms seed by seed, so a stop leaves each arm with the same seeds.
#
# Reports are written as .partial and renamed when complete, so a killed one
# is redone; touch STOP to stop after the current one. About 35-40 minutes a
# seed and arm at 16 threads, 4.5 hours an arm. Read
# arena-logs/summary-defender-fix.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DF_ORIGINAL:-}" ]; then
    DF_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/defender-fix.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DF_ORIGINAL="$DF_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DF_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; REPLAY_ONLY=false; THREADS=16; BOARDS=200; REPLAY_BOARDS=10
ARMS="best best-nolostdef best-tie0 best-tie0w best-tie0c"; SEEDS="11 12 13 14 15 16 21"
for arg in "$@"; do
    case "$arg" in
        --quick)        QUICK=true ;;
        --replay-only)  REPLAY_ONLY=true ;;
        --threads=*)    THREADS="${arg#*=}" ;;
        --only=*)       ARMS="${arg#*=}" ;;
        --seeds=*)      SEEDS="${arg#*=}" ;;
        -h|--help)      sed -n '2,43p' "$DF_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin for the play-outs' student" >&2; exit 2; }
if $QUICK; then BOARDS=20; REPLAY_BOARDS=2; ARMS="best best-tie0c"; SEEDS="11"; fi

T=belief-64-shipped
D="lost-decl,lost-def,tie0-decl"
scope_of() {
    case "$1" in
        best)            echo "$D" ;;
        best-nolostdef)  echo "lost-decl,tie0-decl" ;;
        best-tie0)       echo "$D,tie0-def" ;;
        best-tie0w)      echo "$D,tie0w-def" ;;
        best-tie0c)      echo "$D,tie0c-def" ;;
        def-lost)        echo "lost-def" ;;
        def-tie)         echo "tie0-def" ;;
        def-tie-w)       echo "tie0w-def" ;;
        def-tie-c)       echo "tie0c-def" ;;
        *)               return 1 ;;
    esac
}
# The old report each arm is paired with, by its tag in arena-logs/playout-study/.
old_of() {
    case "$1" in
        best)       echo best ;;
        best-tie0)  echo all ;;
        def-*)      echo "$1" ;;
        *)          echo "" ;;
    esac
}
for arm in $ARMS; do
    scope_of "$arm" > /dev/null || { echo "unknown arm: $arm" >&2; exit 2; }
done

LOG=arena-logs/defender-fix
OLD=arena-logs/playout-study
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-defender-fix.txt
$QUICK && SUMMARY=arena-logs/summary-defender-fix-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "defender fix started $(date '+%Y-%m-%d %H:%M:%S')   arms=$ARMS boards=$BOARDS threads=$THREADS seeds=$SEEDS"

# --- step 0: the replay -------------------------------------------------------
replay="$LOG/replay-s11-b$REPLAY_BOARDS.txt"
if [ ! -f "$replay" ]; then
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] replay, seed 11, $REPLAY_BOARDS boards"
    start=$(date +%s)
    if ./gradlew --console=plain -q --no-daemon :arena:defenderReplay \
            --args="--seed=11 --boards=$REPLAY_BOARDS --threads=$THREADS" > "$replay.partial" 2>&1 \
            && grep -q '^NARROW' "$replay.partial"; then
        mv "$replay.partial" "$replay"
        say "      took $(( ($(date +%s) - start) / 60 )) min"
    else
        mv "$replay.partial" "$LOG/replay-s11-b$REPLAY_BOARDS.failed.txt"
        grep -E '^VIOLATION' "$LOG/replay-s11-b$REPLAY_BOARDS.failed.txt" | head -5 | sed 's/^/      /' | tee -a "$SUMMARY"
        say "      REPLAY FAILED -- see $LOG/replay-s11-b$REPLAY_BOARDS.failed.txt; no arm is run"
        exit 1
    fi
fi
grep -E "^[0-9]+ games:|hand-overs:|^NARROW" "$replay" | sed 's/^/      /' | tee -a "$SUMMARY"
$REPLAY_ONLY && { say "replay only: done"; exit 0; }

# --- the arms -----------------------------------------------------------------
run_one() {  # arm seed
    local arm=$1 SEED=$2
    local P="po:$T/rollout-32x4/$(scope_of "$arm")"
    local tag="$arm-s$SEED-b$BOARDS"
    local report="$LOG/$tag.txt"
    if [ ! -f "$report" ]; then
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        say "  [$(date '+%H:%M:%S')] $tag   $P"
        local start=$(date +%s)
        ./gradlew --console=plain -q --no-daemon :arena:arena \
            --args="--a=$P --b=$T --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
            > "$report.partial" 2>&1 || { mv "$report.partial" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; return; }
        mv "$report.partial" "$report"
        say "      took $(( ($(date +%s) - start) / 60 )) min"
    fi
    grep -E " = .*game pts/game|^  lost, defender|^  tied, defender|^  ties, defender" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
}
if [[ " $ARMS " == *" best "* ]]; then
    for SEED in $SEEDS; do run_one best "$SEED"; done
fi
for SEED in $SEEDS; do
    for arm in $ARMS; do
        [ "$arm" = best ] && continue
        run_one "$arm" "$SEED"
    done
done

# --- pooled -------------------------------------------------------------------
diff_of() { [ -n "$1" ] && [ -f "$1" ] && grep -E " = .*game pts/game" "$1" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'; }
pool() {
    awk -v label="$1" '{
        n = NF; if (n < 2) { printf "  %s: %d seed(s), nothing to pool\n", label, n; exit }
        for (i = 1; i <= n; i++) sum += $i; mean = sum / n
        for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
        se = sqrt(ss / (n - 1)) / sqrt(n)
        split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
        printf "  %s, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]   per seed%s\n", label, n, mean, mean - q * se, mean + q * se, $0
    }'
}
own() {  # report-path-pattern (with SEED placeholder @)
    local out=""
    for SEED in $SEEDS; do x=$(diff_of "${1//@/$SEED}"); [ -n "$x" ] && out="$out $x"; done
    echo "$out"
}
paired() {  # pattern-a pattern-b: a minus b, seeds where both exist
    local out=""
    for SEED in $SEEDS; do
        x=$(diff_of "${1//@/$SEED}"); y=$(diff_of "${2//@/$SEED}")
        [ -n "$x" ] && [ -n "$y" ] && out="$out $(awk -v p="$x" -v q="$y" 'BEGIN { printf "%.4f", p - q }')"
    done
    echo "$out"
}
say "--- pooled, each against $T (old = before the fix, arena-logs/playout-study/) ---"
NEW_BEST="$LOG/best-s@-b$BOARDS.txt"
for arm in $ARMS; do
    say "$arm   po:$T/rollout-32x4/$(scope_of "$arm")"
    own "$LOG/$arm-s@-b$BOARDS.txt" | pool "$arm, fixed" | tee -a "$SUMMARY"
    old=$(old_of "$arm")
    if [ -n "$old" ]; then
        own "$OLD/$old-s@-b$BOARDS.txt" | pool "    the old $old" | tee -a "$SUMMARY"
        paired "$LOG/$arm-s@-b$BOARDS.txt" "$OLD/$old-s@-b$BOARDS.txt" | pool "    fixed minus the old $old, same seeds" | tee -a "$SUMMARY"
    fi
    case "$arm" in
        best-nolostdef)
            paired "$NEW_BEST" "$LOG/$arm-s@-b$BOARDS.txt" | pool "    best minus best-nolostdef: lost-def's own share" | tee -a "$SUMMARY" ;;
        best-tie0|best-tie0w|best-tie0c)
            paired "$LOG/$arm-s@-b$BOARDS.txt" "$NEW_BEST" | pool "    $arm minus best, both fixed" | tee -a "$SUMMARY" ;;
    esac
done
say "defender fix finished $(date '+%Y-%m-%d %H:%M:%S')"
