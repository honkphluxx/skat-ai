#!/usr/bin/env bash
# The teacher's decisions, for the student to imitate (docs/training-plan.md 2.10, I1).
#
#   ./tools/teacher-export.sh                  90,000 boards, seed 101, into teacher-data-v2/
#   ./tools/teacher-export.sh --quick          200 boards into teacher-data-v2-quick/, to time it
#   ./tools/teacher-export.sh --boards=10000 --first=90000
#                                              more boards after the first 90,000
#   ./tools/teacher-export.sh --population=greedy,expert --mix=0,1,0
#                                              other opponents, always two teacher seats
#   ./tools/teacher-export.sh --population=    the teacher in all three seats (format 1's games)
#   ./tools/teacher-export.sh --teacher=belief-32-shipped --out=teacher-data-32
#   ./tools/teacher-export.sh --threads=12     leave cores free for other work
#
# The teacher (belief-64-shipped, picked in T1) plays whole games, auction
# included, passed-in boards void as in the arena's gates. Each board gets one,
# two or three teacher seats (--mix weights, default 25,50,25 -- two as at the
# app's table, one human and two AIs) and the rest from --population (default
# greedy,search-4,club,expert,jskat-new; a player not built here is skipped), so
# the student sees the positions weaker and different players lead it into. Only
# the teacher's seats are recorded. At every card decision with a choice it
# records the position as the seat saw it (the belief encoding of the player's
# own evidence), the legal cards, the card played, the vote per card, the
# cushion, the vote world by world for the first 64 worlds -- from which a 4-,
# 8-, 16- or 32-world player's vote can be read off later, for the weaker
# levels -- and who sat where (players.json; never an input to the student).
# Format 2: teacher-format.json, written beside the shards.
#
# About 11 records a board at the default mix, so the default is about a
# million. Written a shard (1,000 boards) at a time, as .partial until complete,
# and a shard already on disk is skipped: stop it with Ctrl-C and run the same
# command again to carry on. --quick prints the real rate first.
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

THREADS=16; BOARDS=90000; FIRST=0; SEED=101; TEACHER=belief-64-shipped; OUT=teacher-data-v2; SHARD=1000
POPULATION=greedy,search-4,club,expert,jskat-new; MIX=25,50,25
for arg in "$@"; do
    case "$arg" in
        --quick)       BOARDS=200; SHARD=100; OUT=teacher-data-v2-quick ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --boards=*)    BOARDS="${arg#*=}" ;;
        --first=*)     FIRST="${arg#*=}" ;;
        --seed=*)      SEED="${arg#*=}" ;;
        --teacher=*)   TEACHER="${arg#*=}" ;;
        --out=*)       OUT="${arg#*=}" ;;
        --population=*) POPULATION="${arg#*=}" ;;
        --mix=*)       MIX="${arg#*=}" ;;
        -h|--help)     sed -n '2,32p' "$TEACHER_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
mkdir -p "$OUT"
stamp=$(date '+%Y%m%d-%H%M%S')
log="$OUT/teacher-export-$stamp.log"
echo "teacher-export started $(date '+%Y-%m-%d %H:%M:%S')   teacher=$TEACHER population=${POPULATION:-none} mix=$MIX boards=$FIRST..$((FIRST + BOARDS - 1)) seed=$SEED threads=$THREADS out=$OUT"
echo "log: $log"
./gradlew --console=plain -q --no-daemon :arena:exportTeacher \
    --args="--teacher=$TEACHER --boards=$BOARDS --first=$FIRST --seed=$SEED --threads=$THREADS --shard=$SHARD --population=$POPULATION --mix=$MIX --passed-in=void --out=$ROOT/$OUT" \
    2>&1 | grep -v "^SkatKlar solver" | tee "$log"
echo "teacher-export finished $(date '+%Y-%m-%d %H:%M:%S')"
