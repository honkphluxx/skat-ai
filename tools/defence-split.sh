#!/usr/bin/env bash
# What is better defence worth to our player? (docs/training-plan.md 2.10, R)
#
#   ./tools/defence-split.sh                 the three pairings below, 200 boards a seed
#   ./tools/defence-split.sh --quick         one seed of 30 boards each (the plumbing)
#   ./tools/defence-split.sh --threads=12 --boards=300
#
# A role split is one seat played by two players: roles:<declaring>/<defending>
# (RoleSplitProvider). Fixed contracts (greedy's auction), duplicate scoring,
# pooled over seeds:
#
#   roles:belief-64-shipped/skatzero  vs belief-64-shipped    seeds 11-16
#       our teacher declaring, SkatZero defending, against the teacher whole:
#       what SkatZero's defence adds to our best player -- the gap the
#       yardstick cannot see (the defending audit put it near 3 g.p. a game)
#   roles:belief-32-shipped/skatzero  vs belief-32-shipped    seeds 11-13
#       the same for the shipped 32-world player
#   roles:skatzero/belief-64-shipped  vs belief-64-shipped    seeds 11-13
#       the other half: SkatZero declaring, our teacher defending -- whether
#       its declaring is worth anything to us in games (the yardstick says
#       it opens worse and plays the endgame below a 32-world search)
#
# Each report is skipped when it exists, so the same command resumes; touch
# STOP to stop after the current one. Needs the SkatZero checkout
# (third_party/skatzero or $SKATKLAR_SKATZERO_DIR) and a Python with numpy and
# onnxruntime ($SKATKLAR_PYTHON, else python). About three to four hours at
# 16 threads. Read arena-logs/summary-defence-split.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${DS_ORIGINAL:-}" ]; then
    DS_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/defence-split.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    DS_ORIGINAL="$DS_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$DS_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; BOARDS=200
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --boards=*)   BOARDS="${arg#*=}" ;;
        -h|--help)    sed -n '2,30p' "$DS_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
SZ=${SKATKLAR_SKATZERO_DIR:-third_party/skatzero}
[ -f "$SZ/models/onnx/D_0.onnx" ] || { echo "no SkatZero checkout with ONNX models at $SZ" >&2; exit 2; }
PY=${SKATKLAR_PYTHON:-python}
"$PY" -c "import numpy, onnxruntime" 2>/dev/null || {
    echo "$PY lacks numpy or onnxruntime (the SkatZero bot runs under it); set SKATKLAR_PYTHON" >&2; exit 2; }

LONG="11 12 13 14 15 16"; SHORT="11 12 13"
if $QUICK; then BOARDS=30; LONG="11"; SHORT="11"; fi
LOG=arena-logs/defence-split
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-defence-split.txt
$QUICK && SUMMARY=arena-logs/summary-defence-split-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "defence split started $(date '+%Y-%m-%d %H:%M:%S')   boards=$BOARDS threads=$THREADS"

# player|against|seeds|tag (the tag names the files: no ':' or '/' in them)
PAIRS=(
    "roles:belief-64-shipped/skatzero|belief-64-shipped|$LONG|teacher-decl-sz-def"
    "roles:belief-32-shipped/skatzero|belief-32-shipped|$SHORT|shipped-decl-sz-def"
    "roles:skatzero/belief-64-shipped|belief-64-shipped|$SHORT|sz-decl-teacher-def"
)
for pair in "${PAIRS[@]}"; do
    IFS='|' read -r P AGAINST SEEDS TAG <<< "$pair"
    say "--- $P against $AGAINST, fixed contracts, seeds $SEEDS ---"
    diffs=""
    for SEED in $SEEDS; do
        [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
        tag="$TAG-s$SEED-b$BOARDS"
        report="$LOG/$tag.txt"
        if [ ! -f "$report" ]; then
            say "  [$(date '+%H:%M:%S')] $tag"
            ./gradlew --console=plain -q --no-daemon :arena:arena \
                --args="--a=$P --b=$AGAINST --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $LOG/$tag.failed.txt"; continue; }
        fi
        grep -E "^  from declaring|^  from defending| = .*game pts/game|delegated|Honesty" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
        d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/')
        [ -n "$d" ] && diffs="$diffs $d"
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
say "defence split finished $(date '+%Y-%m-%d %H:%M:%S')"
