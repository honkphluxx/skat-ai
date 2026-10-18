#!/usr/bin/env bash
# 450,000 more deals of best's cards, for the student (docs/training-plan.md 2.10, I5).
#
#   ./tools/export-more.sh                  boards 150,000-599,999 into teacher-data-best4/, then the corpus
#   ./tools/export-more.sh --threads=15     leave a core for the trainer beside it
#   ./tools/export-more.sh --assemble-only  the corpus from the shards on disk now: a point on the way
#   ./tools/export-more.sh --quick          ten boards and a corpus of them: the plumbing
#
# The student is short of data (2026-10-18): about 2.2 game points a game a
# doubling, no bend yet, 3.7 behind the teacher at 150,000 deals and 2.6
# with the suits permuted. This exports best (po:belief-64-shipped/
# rollout-32x4/lost-decl,lost-def,tie0-decl) as tools/data-scale.sh did --
# seed 101, the same seating draw, population and mix -- on the next 450,000
# boards, into teacher-data-best4/: about 2,000 deals an hour at 16 threads,
# so 9 to 10 days. Written a shard of 1,000 boards at a time, as .partial
# until complete, and a shard on disk is skipped: stop it with Ctrl-C (or
# touch STOP, read between runs of this script) and run the same command
# again to carry on.
#
# Then teacher-data-best-<N>/: teacher-data-best-150's shards and every
# complete shard of teacher-data-best4, copied, N the thousands of deals --
# teacher-data-best-600 when the export is through. --assemble-only builds
# it from what is on disk at the time (teacher-data-best-312, say), so a
# point of the curve can be trained while the export carries on; the split
# is by deal, so the held-out deals are the same in every corpus. About 1.9
# GB of new shards and 2.5 GB for the 600,000-deal corpus.
#
# Read arena-logs/summary-export-more.txt; the export's own log is in
# arena-logs/export-more/.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${EM_ORIGINAL:-}" ]; then
    EM_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/export-more.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    EM_ORIGINAL="$EM_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$EM_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; EXPORT=true; THREADS=16
for arg in "$@"; do
    case "$arg" in
        --quick)          QUICK=true ;;
        --assemble-only)  EXPORT=false ;;
        --threads=*)      THREADS="${arg#*=}" ;;
        -h|--help)        sed -n '2,31p' "$EM_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin for best's play-outs" >&2; exit 2; }
BASE=teacher-data-best-150
[ -f "$BASE/teacher-format.json" ] || { echo "no $BASE (tools/data-scale.sh builds it)" >&2; exit 2; }

T=belief-64-shipped
BEST="po:$T/rollout-32x4/lost-decl,lost-def,tie0-decl"
FIRST=150000; DEALS=450000; SHARD=1000; OUT=teacher-data-best4; PREFIX=teacher-data-best
if $QUICK; then DEALS=10; SHARD=10; OUT=teacher-data-best4-quick; PREFIX=teacher-data-best-quick; fi
LOG=arena-logs/export-more
mkdir -p "$LOG"
SUMMARY=arena-logs/summary-export-more.txt
$QUICK && SUMMARY=arena-logs/summary-export-more-quick.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "export more started $(date '+%Y-%m-%d %H:%M:%S')   export=$EXPORT (boards $FIRST-$((FIRST + DEALS - 1)) into $OUT) threads=$THREADS"

expected=$(( (DEALS + SHARD - 1) / SHARD ))
if $EXPORT; then
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    have=$(ls "$OUT"/teacher-v3-s101-*.bin 2>/dev/null | wc -l)
    say "  [$(date '+%H:%M:%S')] export: $BEST, $have of $expected shards on disk"
    start=$(date +%s)
    ./gradlew --console=plain -q --no-daemon :arena:exportTeacher \
        --args="--teacher=$BEST --boards=$DEALS --first=$FIRST --seed=101 --threads=$THREADS --shard=$SHARD --population=greedy,search-4,club,expert,jskat-new --mix=25,50,25 --passed-in=void --out=$ROOT/$OUT" \
        2>&1 | grep -v "^SkatKlar solver" | tee -a "$LOG/export-$OUT.log" | tail -12 | sed 's/^/      /' | tee -a "$SUMMARY"
    now=$(ls "$OUT"/teacher-v3-s101-*.bin 2>/dev/null | wc -l)
    hours=$(awk -v s="$(( $(date +%s) - start ))" 'BEGIN { printf "%.1f", s / 3600 }')
    say "  export ended after $hours hours: $now of $expected shards ($((now - have)) this run)"
fi

# The corpus: the 150,000 deals and every complete shard of the export.
shards=$(ls "$OUT"/teacher-v3-s101-*.bin 2>/dev/null | wc -l)
[ "$shards" -gt 0 ] || { say "  no shard of $OUT on disk yet -- nothing to assemble"; exit 1; }
if $QUICK; then N=quick; SOURCES="$OUT/teacher-v3-s101-*.bin"   # the plumbing, not 600 MB of copies
else
    N=$(( 150 + shards * SHARD / 1000 ))
    SOURCES="$BASE/teacher-v3-s101-*.bin $OUT/teacher-v3-s101-*.bin"
fi
CORPUS="$PREFIX-$N"; $QUICK && CORPUS=$PREFIX
mkdir -p "$CORPUS"
for g in "$OUT"/teacher-format.json "$OUT"/players.json "$OUT"/teacher.txt "$OUT"/encoding-v*.json; do
    [ -f "$g" ] && { [ -f "$CORPUS/$(basename "$g")" ] || cp "$g" "$CORPUS/"; }
done
# shellcheck disable=SC2086 -- $SOURCES is deliberately globbed
for shard in $SOURCES; do
    name=$(basename "$shard")
    [ -f "$CORPUS/$name" ] && continue
    cp "$shard" "$CORPUS/$name.partial" && mv "$CORPUS/$name.partial" "$CORPUS/$name"
done
say "  $CORPUS: $(ls "$CORPUS"/teacher-v3-s101-*.bin | wc -l) shards ($shards of $expected from the export)"
[ "$shards" -ge "$expected" ] || say "  the export is not through: run the same command again to carry on"
say "export more finished $(date '+%Y-%m-%d %H:%M:%S')"
