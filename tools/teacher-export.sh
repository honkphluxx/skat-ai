#!/usr/bin/env bash
# The teacher's decisions, for the student to imitate (docs/training-plan.md 2.10, I1).
#
#   ./tools/teacher-export.sh                  50,000 boards, seed 101, into teacher-data/
#   ./tools/teacher-export.sh --quick          200 boards into teacher-data-quick/, to time it
#   ./tools/teacher-export.sh --boards=10000 --first=50000
#                                              more boards after the first 50,000
#   ./tools/teacher-export.sh --teacher=belief-32-shipped --out=teacher-data-32
#   ./tools/teacher-export.sh --threads=12     leave cores free for other work
#
# The teacher (belief-64-shipped, picked in T1) plays whole games in all three
# seats, auction included, passed-in boards void as in the arena's gates. At
# every card decision with a choice it records the position as the seat saw it
# (the belief encoding of the player's own evidence), the legal cards, the card
# played, the vote per card and the cushion, and the vote world by world for
# the first 64 worlds -- from which a 4-, 8-, 16- or 32-world player's vote can
# be read off later, for the weaker levels. Format: teacher-format.json, written
# beside the shards.
#
# Written a shard (1,000 boards) at a time, as .partial until complete, and a
# shard already on disk is skipped: stop it with Ctrl-C and run the same command
# again to carry on. About 0.6 boards a second at 16 threads, so the default
# is roughly a day; --quick prints the real rate first.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${TEACHER_ORIGINAL:-}" ]; then
    TEACHER_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/teacher-export.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    TEACHER_ORIGINAL="$TEACHER_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$TEACHER_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

THREADS=16; BOARDS=50000; FIRST=0; SEED=101; TEACHER=belief-64-shipped; OUT=teacher-data; SHARD=1000
for arg in "$@"; do
    case "$arg" in
        --quick)       BOARDS=200; SHARD=100; OUT=teacher-data-quick ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --boards=*)    BOARDS="${arg#*=}" ;;
        --first=*)     FIRST="${arg#*=}" ;;
        --seed=*)      SEED="${arg#*=}" ;;
        --teacher=*)   TEACHER="${arg#*=}" ;;
        --out=*)       OUT="${arg#*=}" ;;
        -h|--help)     sed -n '2,24p' "$TEACHER_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
mkdir -p "$OUT"
stamp=$(date '+%Y%m%d-%H%M%S')
log="$OUT/teacher-export-$stamp.log"
echo "teacher-export started $(date '+%Y-%m-%d %H:%M:%S')   teacher=$TEACHER boards=$FIRST..$((FIRST + BOARDS - 1)) seed=$SEED threads=$THREADS out=$OUT"
echo "log: $log"
./gradlew --console=plain -q --no-daemon :arena:exportTeacher \
    --args="--teacher=$TEACHER --boards=$BOARDS --first=$FIRST --seed=$SEED --threads=$THREADS --shard=$SHARD --passed-in=void --out=$ROOT/$OUT" \
    2>&1 | grep -v "^SkatKlar solver" | tee "$log"
echo "teacher-export finished $(date '+%Y-%m-%d %H:%M:%S')"
