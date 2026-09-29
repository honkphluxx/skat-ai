#!/usr/bin/env bash
# I3: the student, trained on the teacher's decisions (docs/training-plan.md 2.10).
#
#   ./tools/policy-train.sh              the checks, then three runs on teacher-data-v2/:
#                                        a quarter, a half and all of the training deals,
#                                        20 epochs each -- whether more data still pays
#   ./tools/policy-train.sh --quick      the checks and one 3-epoch run on 5% of the deals,
#                                        into policy-model-quick/: does every step run
#   ./tools/policy-train.sh --data=teacher-data-v2 --epochs=30
#   ./tools/policy-train.sh --python=/c/Python312/python.exe
#
# Each run is skipped when its policy.pt exists, so the same command resumes.
# Read the table at the end of each log (arena-logs/policy-*.txt), summarised
# into arena-logs/summary-policy.txt:
#
#   teacher   the student plays the teacher's card
#   best      the student's card is one of the teacher's top-voted cards; a
#             different card among tied ones is a tiebreak, not a mistake
#   regret    worlds of 64 the student's card holds fewer than the teacher's;
#             0 is the teacher, and a card at random is printed beside it
#
# Nothing here plays a game. The student in the arena is I4.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${POLICY_ORIGINAL:-}" ]; then
    POLICY_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/policy-train.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    POLICY_ORIGINAL="$POLICY_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$POLICY_ORIGINAL")/.." || exit 1

QUICK=false
DATA=teacher-data-v2
EPOCHS=20
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)     QUICK=true ;;
        --data=*)    DATA="${arg#*=}" ;;
        --epochs=*)  EPOCHS="${arg#*=}" ;;
        --python=*)  PYTHON="${arg#*=}" ;;
        -h|--help)   sed -n '2,24p' "$POLICY_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           echo "unknown option: $arg" >&2; exit 2 ;;
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

LOG=arena-logs
SUMMARY=$LOG/summary-policy.txt
RUNS="0.25:policy-model-q25 0.5:policy-model-q50 1.0:policy-model"
if $QUICK; then
    LOG=arena-logs/quick
    SUMMARY=$LOG/summary-policy.txt
    RUNS="0.05:policy-model-quick"
    EPOCHS=3
fi
mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }

say "============================================================"
say "policy training started $(date '+%Y-%m-%d %H:%M:%S')   data=$DATA epochs=$EPOCHS quick=$QUICK python=$PYTHON"
cuda=$("$PYTHON" -c "import torch; print(torch.cuda.is_available())")
say "  CUDA available: $cuda"

say "  [$(date '+%H:%M:%S')] check $DATA"
if ! (cd arena/python && "$PYTHON" teacher_data.py "../../$DATA") > "$LOG/policy-check.txt" 2>&1; then
    grep -E "FAIL|Error" "$LOG/policy-check.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    say "  CHECK FAILED -- see $LOG/policy-check.txt"; exit 1
fi
grep -E "records from|all checks|no Null|tied at the top|at random" "$LOG/policy-check.txt" | sed 's/^/      /' | tee -a "$SUMMARY"

for run in $RUNS; do
    fraction=${run%%:*}
    model=${run#*:}
    name=$(basename "$model")
    if [ -f "$model/policy.pt" ]; then
        say "  [skip] $name -- $model/policy.pt exists"
        continue
    fi
    [ -f STOP ] && { say "STOP file found -- stopping."; exit 0; }
    say "  [$(date '+%H:%M:%S')] train $name: $fraction of the training deals, $EPOCHS epochs"
    if ! (cd arena/python && "$PYTHON" train_policy.py --data "../../$DATA" --out "../../$model" \
            --epochs "$EPOCHS" --fraction "$fraction") > "$LOG/policy-$name.txt" 2>&1; then
        tail -5 "$LOG/policy-$name.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  TRAINING FAILED -- see $LOG/policy-$name.txt"; exit 1
    fi
    # The policy head's table only; the value head's follows it in the log.
    sed '/the value head/q' "$LOG/policy-$name.txt" \
        | grep -E "training records|best held-out|random: teacher|^    (all|vote decides|all tied|declarer|defender|tricks) " \
        | sed 's/^/      /' | tee -a "$SUMMARY"
done
say "policy training finished $(date '+%Y-%m-%d %H:%M:%S')"
