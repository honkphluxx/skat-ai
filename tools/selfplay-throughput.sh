#!/usr/bin/env bash
# Can self-play reach the data a network-only player needs? (docs/training-plan.md
# 2.10, R: the throughput test.) Nothing is trained and nothing is written but
# the log; about ten minutes.
#
#   ./tools/selfplay-throughput.sh
#   ./tools/selfplay-throughput.sh --threads=1,8,16,24 --seconds=20
#   ./tools/selfplay-throughput.sh --python=/c/Python312/python.exe
#
# 1. the actors (CPU, Java, SelfPlayThroughputMain): the student's forward
#    pass alone, one input a call as the players call it today, and whole
#    network-only games through the shipped engine with a bare student actor
#    in all three seats -- at several thread counts; plus one line of the
#    arena's student-64 for comparison. Needs policy-model/policy.bin.
# 2. the GPU (gpu_throughput.py): the same network's inference by batch size
#    on the GPU (alone, and with the inputs' round trip from the host) and on
#    the CPU with torch, and training steps by batch size -- for the encoding
#    alone and with the play state.
#
# Read arena-logs/summary-throughput.txt.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${SPT_ORIGINAL:-}" ]; then
    SPT_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/selfplay-throughput.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    SPT_ORIGINAL="$SPT_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$SPT_ORIGINAL")/.." || exit 1

THREADS=""
SECONDS_EACH=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --threads=*)  THREADS="${arg#*=}" ;;
        --seconds=*)  SECONDS_EACH="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,20p' "$SPT_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
if [ -z "$PYTHON" ]; then
    for candidate in ../.venv/Scripts/python.exe ../.venv/Scripts/python .venv/Scripts/python.exe .venv/Scripts/python ../.venv/bin/python .venv/bin/python; do
        [ -x "$candidate" ] && PYTHON="$candidate" && break
    done
fi
if [ -z "$PYTHON" ] || ! "$PYTHON" -c "import torch" 2>/dev/null; then
    echo "no interpreter with torch found; pass --python=... or set SKATKLAR_PYTHON" >&2
    exit 2
fi
[ -f policy-model/policy.bin ] || { echo "no policy-model/policy.bin; run arena/python/export_policy.py" >&2; exit 2; }

LOG=arena-logs
mkdir -p "$LOG"
SUMMARY=$LOG/summary-throughput.txt
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
say "============================================================"
say "self-play throughput started $(date '+%Y-%m-%d %H:%M:%S')"

args="--seconds=$SECONDS_EACH"
[ -n "$THREADS" ] && args="$args --threads=$THREADS"
say "  [$(date '+%H:%M:%S')] 1. the actors (CPU, Java)"
if ! ./gradlew --console=plain -q --no-daemon :arena:selfPlayThroughput --args="$args" \
        > "$LOG/throughput-cpu.txt" 2>&1; then
    tail -8 "$LOG/throughput-cpu.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  ACTOR TEST FAILED -- see $LOG/throughput-cpu.txt"; exit 1
fi
grep -v "^Picked up\|native solver" "$LOG/throughput-cpu.txt" | sed 's/^/      /' | tee -a "$SUMMARY"

say "  [$(date '+%H:%M:%S')] 2. the GPU (torch)"
if ! "$PYTHON" arena/python/gpu_throughput.py > "$LOG/throughput-gpu.txt" 2>&1; then
    tail -8 "$LOG/throughput-gpu.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  GPU TEST FAILED -- see $LOG/throughput-gpu.txt"; exit 1
fi
sed 's/^/      /' "$LOG/throughput-gpu.txt" | tee -a "$SUMMARY"
say "self-play throughput finished $(date '+%Y-%m-%d %H:%M:%S')"
