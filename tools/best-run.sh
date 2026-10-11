#!/usr/bin/env bash
# `best` against an outsider, then its decisions for the student (docs/training-plan.md 2.10, R).
#
#   ./tools/best-run.sh                  the XSkat check, then the export
#   ./tools/best-run.sh --quick          two boards of each, a shard of ten: the plumbing
#   ./tools/best-run.sh --check-only     the XSkat check alone
#   ./tools/best-run.sh --check-only --against=skatzero
#                                        the same check against SkatZero (card play
#                                        only: SkatZero never bids, so fixed contracts
#                                        are the only way it can sit at the table)
#   ./tools/best-run.sh --export-only    the export alone
#   ./tools/best-run.sh --threads=20 --seeds="11 12 13" --boards=30000
#
# best = po:belief-64-shipped/rollout-32x4/lost-decl,lost-def,tie0-decl: the
# teacher, with the student's drawn play-outs deciding the declarer's and the
# defenders' lost positions and the declarer's ties (+1.31 [+0.81, +1.81]
# against the teacher itself). No SkatZero anywhere.
#
# 1. The XSkat check. Both best and the teacher alone against XSkat, fixed
#    contracts (greedy's auction), duplicate, 200 boards a seed, seeds 11-16;
#    pooled each, and best minus the teacher paired by seed: does the edge
#    measured against our own teacher hold against a player that is not us?
#    --against=skatzero: the same against the strongest card play we know of,
#    which no corpus of ours has seen so far; seeds 11-16 by default as for
#    XSkat.
# 2. The export. best as the teacher of tools/teacher-export.sh: the first
#    30,000 boards of teacher-data-v3's deals (seed 101, the same seating
#    draw), into teacher-data-best/. Each record is the search's as in v3 --
#    evidence, vote, cushion, verdicts, play state -- with the card best
#    played. Shards are skipped when on disk, so it resumes; teacher-data-v3's
#    first 30 shards are the like-for-like control for the student trained
#    on it.
#
# Arena reports are written as .partial and renamed when the run ends, so a
# killed one is redone; touch STOP to stop after the current one. About six
# hours for the check at 16 threads and 17 for the export (teacher-data-v3
# took 1.3 s a board; best costs about half again). Read
# arena-logs/summary-best-run.txt; the export's log is in teacher-data-best/.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${BR_ORIGINAL:-}" ]; then
    BR_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/best-run.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BR_ORIGINAL="$BR_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BR_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

AGAINST=xskat; QUICK=false; CHECK=true; EXPORT=true; THREADS=16; BOARDS=200; SEEDS="11 12 13 14 15 16"
EXPORT_BOARDS=30000; SHARD_ARG=""; OUT=teacher-data-best
for arg in "$@"; do
    case "$arg" in
        --quick)        QUICK=true ;;
        --check-only)   EXPORT=false ;;
        --export-only)  CHECK=false ;;
        --against=*)    AGAINST="${arg#*=}" ;;
        --threads=*)    THREADS="${arg#*=}" ;;
        --seeds=*)      SEEDS="${arg#*=}" ;;
        --boards=*)     EXPORT_BOARDS="${arg#*=}" ;;
        -h|--help)      sed -n '2,40p' "$BR_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin for the play-outs' student" >&2; exit 2; }
if $QUICK; then BOARDS=2; SEEDS="11"; EXPORT_BOARDS=10; OUT=teacher-data-best-quick; fi

T=belief-64-shipped
BEST="po:$T/rollout-32x4/lost-decl,lost-def,tie0-decl"
LOG=arena-logs/best-run
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-best-run.txt
$QUICK && SUMMARY=arena-logs/summary-best-run-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "best run started $(date '+%Y-%m-%d %H:%M:%S')   threads=$THREADS check=$CHECK (against $AGAINST, $SEEDS, $BOARDS boards) export=$EXPORT ($EXPORT_BOARDS boards into $OUT)"

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

if $CHECK; then
    available=true
    case "$AGAINST" in
        xskat)
            if [ ! -x third_party/xskat/skatklar-xskat ] && [ ! -x third_party/xskat/skatklar-xskat.exe ]; then
                say "no XSkat build under third_party/xskat (tools/build-external-bots.sh) -- skipping the check"
                available=false
            fi ;;
        skatzero)
            SZ=${SKATKLAR_SKATZERO_DIR:-third_party/skatzero}
            PY=${SKATKLAR_PYTHON:-python}
            if [ ! -f "$SZ/models/onnx/D_0.onnx" ]; then
                say "no SkatZero checkout with ONNX models at $SZ -- skipping the check"; available=false
            elif ! "$PY" -c "import numpy, onnxruntime" 2>/dev/null; then
                say "$PY lacks numpy or onnxruntime (the SkatZero bot runs under it); set SKATKLAR_PYTHON -- skipping the check"
                available=false
            fi ;;
        *) say "--against is xskat or skatzero, not $AGAINST"; exit 2 ;;
    esac
    if $available; then
        say "--- 1. against $AGAINST, fixed contracts, seeds $SEEDS ---"
        PAIRS=("teacher|$T" "best|$BEST")
        for SEED in $SEEDS; do
            for pair in "${PAIRS[@]}"; do
                IFS='|' read -r TAG P <<< "$pair"
                [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
                tag="$TAG-vs-$AGAINST-s$SEED-b$BOARDS"
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
                grep -E "^  from declaring|^  from defending| = .*game pts/game|rule violations|^  (lost|tied), |play-outs' cost|^  (Diamonds|Hearts|Spades|Clubs|Grand|Null) " "$report" \
                    | sed 's/^/      /' | tee -a "$SUMMARY"
            done
        done
        teacher=""; best=""; paired=""
        for SEED in $SEEDS; do
            a=$(diff_of "$LOG/teacher-vs-$AGAINST-s$SEED-b$BOARDS.txt"); b=$(diff_of "$LOG/best-vs-$AGAINST-s$SEED-b$BOARDS.txt")
            [ -n "$a" ] && teacher="$teacher $a"
            [ -n "$b" ] && best="$best $b"
            [ -n "$a" ] && [ -n "$b" ] && paired="$paired $(awk -v x="$b" -v y="$a" 'BEGIN { printf "%.4f", x - y }')"
        done
        echo "$teacher" | pool "$T - $AGAINST" | tee -a "$SUMMARY"
        echo "$best" | pool "best - $AGAINST" | tee -a "$SUMMARY"
        echo "$paired" | pool "    best minus $T, both against $AGAINST, same seeds" | tee -a "$SUMMARY"
    fi
fi

if $EXPORT; then
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "--- 2. the export: $BEST, boards 0-$((EXPORT_BOARDS - 1)) of seed 101, into $OUT ---"
    $QUICK && SHARD_ARG="--shard=10"
    # teacher-export.sh's own options; --quick there would pick its own folder,
    # so the shard size is passed straight through instead.
    if $QUICK; then
        ./gradlew --console=plain -q --no-daemon :arena:exportTeacher \
            --args="--teacher=$BEST --boards=$EXPORT_BOARDS --first=0 --seed=101 --threads=$THREADS $SHARD_ARG --population=greedy,search-4,club,expert,jskat-new --mix=25,50,25 --passed-in=void --out=$ROOT/$OUT" \
            2>&1 | grep -v "^SkatKlar solver" | tee "$LOG/export-quick.log" | tail -14 | sed 's/^/      /' | tee -a "$SUMMARY"
    else
        ./tools/teacher-export.sh --teacher="$BEST" --boards="$EXPORT_BOARDS" --first=0 --seed=101 \
            --threads="$THREADS" --out="$OUT" 2>&1 | tail -14 | sed 's/^/      /' | tee -a "$SUMMARY"
    fi
fi
say "best run finished $(date '+%Y-%m-%d %H:%M:%S')"
