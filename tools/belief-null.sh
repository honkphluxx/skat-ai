#!/usr/bin/env bash
# B2 step 3: can one belief model hold Null as well as trump, or does Null
# need a net of its own?
#
#   ./tools/belief-null.sh            the night: 200k minted boards, 20 epochs, gates on three seeds
#   ./tools/belief-null.sh --quick    a rehearsal: 4k boards, 3 epochs, 30-board gates, one seed,
#                                     into its own directories -- an hour, and it answers
#                                     "does every step run" and nothing else
#   ./tools/belief-null.sh --threads=8
#   ./tools/belief-null.sh --python=/c/Python312/python.exe
#
# Why this exists. Two corpora were counted and both hold exactly zero Null
# decision points -- 0 of 89,438 in v1 and 0 of 511,816 in v2 -- so the belief
# model has never seen the contract our player is measurably worst at. That is
# a bidding fact and not a training one: guaranteedValue(NULL) is a flat 23, so
# no seat can ever outbid anyone to announce one, and waiting for the bidder to
# be fixed would block this indefinitely. The Nulls are minted instead, with
# ExportMain's --contracts=null, which prices about one board in eleven.
#
# What a minted record is not: a game anybody bid for. Its bidding block is
# empty, so the model learns where the cards lie in a Null *given no bidding
# evidence*. That is close to the app's own case -- our seats only ever meet a
# Null a person declared -- but it is not the same thing, which is why the
# minted boards live in their own corpus and are mixed in by name rather than
# stirred in and forgotten.
#
# Seven steps, each skipped when its output already exists, so the same command
# resumes after a stop (a STOP file in the repository root, or Ctrl-C, between
# steps or matches):
#
#   1. mint        belief-data-null/    :arena:export --contracts=null, seed 12
#   2. check       check_data.py        both corpora; a FAIL stops the night here
#   3. mix         belief-data-mixed/   the trump shards and the Null shards in one
#                                       directory, hard-linked, so the mix costs no disk
#   4. train       belief-model-trump/  one net over the trump corpus alone: the control
#                  belief-model-mixed/  the same, plus the minted Nulls: the treatment
#                  belief-model-null/   one net over Null alone
#   5. weights     belief-model-mixed/belief.bin   export_weights.py
#   6. read        eval_belief.py       the three numbers, below
#   7. gates       arena-logs/          the mixed model against the shipped one
#
# The three numbers, read in the morning from arena-logs/summary-belief-null.txt:
#
#   Null accuracy    mixed net vs Null-only net, on the held-out tenth of the
#                    Null corpus. If the Null-only net wins clearly, sharing
#                    costs something and a separate head is worth its weight.
#   trump accuracy   mixed net vs the trump-only control, on the held-out tenth
#                    of the trump corpus, sliced by contract. This is the number
#                    that says whether Null poisoned the 97% of positions the
#                    player actually meets, and it is the one that can veto the
#                    whole idea.
#
#                    Against a control trained tonight rather than against the
#                    shipped model, and the difference is not pedantry. A model
#                    can only be scored honestly on deals it was actually held
#                    out of, so a model trained before the split rule changed is
#                    graded on its own training data and reads about five points
#                    too well. That is exactly what happened on the first quick
#                    run of this script. eval_belief.py now says so out loud, but
#                    the real fix is a control trained the same night, on the
#                    same split, for the same epochs, differing in one thing:
#                    the Null records. One confound remains and is left standing
#                    rather than engineered away -- the mixed corpus is about 8%
#                    larger, so the mixed net takes about 8% more gradient steps
#                    at equal epochs. If the two land within a point of each
#                    other that is immaterial; if the mixed net wins by a lot,
#                    suspect the steps before crediting the Nulls.
#   the arena        the mixed model on --contracts=null boards, and on the
#                    ordinary gates. Accuracy is a proxy; this is the outcome.
#
# Read them against the arguments, which were written down before the run so
# the result cannot be read to suit. *For sharing:* most of the belief's work
# is contract-independent bookkeeping -- who followed, who is void, what is
# gone -- and a shared trunk sees twenty times the data. *For separating:* the
# two contracts want opposite things from the same evidence, and a trunk that
# has to serve both may learn neither sharply. The measurement decides it.
#
# Note on the held-out split: since 2026-09-19 a board's side is a function of
# its id and the seed alone, so the same deal is held out of every corpus that
# contains it. That is what makes the first two numbers honest -- before it,
# the mixed net would have been scored on Null deals it had trained on. A
# val_accuracy stored in a model.json written before that date is not
# comparable; this script recomputes every number it prints.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

# Run from a copy, for the reason overnight-arena.sh gives: bash reads a script
# as it goes, and a commit mid-run would land it on a byte offset.
if [ -z "${BELIEF_NULL_ORIGINAL:-}" ]; then
    BELIEF_NULL_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/belief-null.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    BELIEF_NULL_ORIGINAL="$BELIEF_NULL_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$BELIEF_NULL_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false
THREADS=16
PYTHON=${SKATKLAR_PYTHON:-}
for arg in "$@"; do
    case "$arg" in
        --quick)      QUICK=true ;;
        --threads=*)  THREADS="${arg#*=}" ;;
        --python=*)   PYTHON="${arg#*=}" ;;
        -h|--help)    sed -n '2,85p' "$BELIEF_NULL_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

TRUMP=belief-data-v2
NULLDATA=belief-data-null
MIXED=belief-data-mixed
TRUMP_MODEL=belief-model-trump
MIXED_MODEL=belief-model-mixed
NULL_MODEL=belief-model-null
LOG=arena-logs
SUMMARY=arena-logs/summary-belief-null.txt
BOARDS=200000
SHARD=20000
EPOCHS=20
SEEDS="11 12 13"
GATE=300
GATE_SMALL=200
NULLGATE=6000
if $QUICK; then
    TRUMP=belief-data-v2-quick
    NULLDATA=belief-data-null-quick
    MIXED=belief-data-mixed-quick
    TRUMP_MODEL=belief-model-trump-quick
    MIXED_MODEL=belief-model-mixed-quick
    NULL_MODEL=belief-model-null-quick
    LOG=arena-logs/quick
    SUMMARY=arena-logs/quick/summary-belief-null.txt
    BOARDS=4000
    SHARD=2000
    EPOCHS=3
    SEEDS="11"
    GATE=30
    GATE_SMALL=30
    NULLGATE=300
fi
mkdir -p "$LOG"
POPULATION=greedy,search-4,club,expert,jskat-new,xskat-blind,go-skat

say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
stopped() { [ -f STOP ] && { say "STOP file found -- stopping."; return 0; }; return 1; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }

say "============================================================"
say "belief-null started $(date '+%Y-%m-%d %H:%M:%S')   quick=$QUICK threads=$THREADS python=$PYTHON"

# The trump corpus is this script's input, not its output: belief-v2.sh makes
# it. Said here rather than three steps in, because the mint is an hour and
# finding out afterwards that there is nothing to mix it with is an hour lost.
if [ ! -f "$TRUMP/population.json" ]; then
    say "  $TRUMP is missing -- run ./tools/belief-v2.sh${QUICK:+ --quick} first."
    exit 1
fi

# 1. Mint. Seed 12 rather than 11, so the deal ids do not collide with the
# trump corpus's and a board cannot be in both halves of the mix.
if [ -f "$NULLDATA/population.json" ]; then
    say "  [skip] mint -- $NULLDATA/population.json exists"
else
    say "  [$(date '+%H:%M:%S')] mint $BOARDS boards into $NULLDATA (about one in eleven prices)"
    rm -rf "$NULLDATA"
    ./gradlew --console=plain -q --no-daemon :arena:export \
        --args="--boards=$BOARDS --seed=12 --threads=$THREADS --shard=$SHARD --out=$NULLDATA --passed-in=void --contracts=null --players=$POPULATION" \
        > "$LOG/belief-null-mint.txt" 2>&1
    rc=$?
    grep -E "population|contracts|skipping unavailable|records|could be priced" "$LOG/belief-null-mint.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
    if [ $rc -ne 0 ] || [ ! -f "$NULLDATA/population.json" ]; then
        say "  MINT FAILED -- see $LOG/belief-null-mint.txt"; exit 1
    fi
fi
stopped && exit 0

# 2. Check both. Seconds, and the reason the night is not spent on a silent bug.
for corpus in "$NULLDATA" "$TRUMP"; do
    say "  [$(date '+%H:%M:%S')] check_data $corpus"
    if ! "$PYTHON" arena/python/check_data.py "$corpus" > "$LOG/belief-null-check-$(basename "$corpus").txt" 2>&1; then
        grep -E "FAIL|Error|error" "$LOG/belief-null-check-$(basename "$corpus").txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  CHECK FAILED -- see $LOG/belief-null-check-$(basename "$corpus").txt"; exit 1
    fi
    sed -n '/what is in it/,/^$/p' "$LOG/belief-null-check-$(basename "$corpus").txt" | sed 's/^/      /' | tee -a "$SUMMARY"
done
stopped && exit 0

# 3. The mix: both sets of shards in one directory. Hard links, so a corpus
# that is the size of both costs the disk of neither; the Null shards are
# renamed rather than renumbered, because a name is visible in a listing and a
# number is not, and somebody will want to know what is in here.
if [ -f "$MIXED/population.json" ]; then
    say "  [skip] mix -- $MIXED/population.json exists"
else
    say "  [$(date '+%H:%M:%S')] mix $TRUMP and $NULLDATA into $MIXED"
    rm -rf "$MIXED"; mkdir -p "$MIXED"
    cp "$TRUMP/encoding-v"*.json "$TRUMP/population.json" "$MIXED/" || exit 1
    for shard in "$TRUMP"/shard-*.bin; do
        ln "$shard" "$MIXED/$(basename "$shard")" 2>/dev/null \
            || cp "$shard" "$MIXED/$(basename "$shard")" || exit 1
    done
    for shard in "$NULLDATA"/shard-*.bin; do
        target="$MIXED/shard-null-$(basename "$shard" | sed 's/^shard-//')"
        ln "$shard" "$target" 2>/dev/null || cp "$shard" "$target" || exit 1
    done
    if ! "$PYTHON" arena/python/check_data.py "$MIXED" > "$LOG/belief-null-check-mixed.txt" 2>&1; then
        grep -E "FAIL|Error|error" "$LOG/belief-null-check-mixed.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  MIXED CHECK FAILED -- see $LOG/belief-null-check-mixed.txt"; exit 1
    fi
    sed -n '/what is in it/,/^$/p' "$LOG/belief-null-check-mixed.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
fi
stopped && exit 0

# 4. Two nets from the same shards, which is why this step is minutes and the
# corpus was the expensive part.
train() {
    local data="$1" model="$2" tag="$3"
    if [ -f "$model/belief.pt" ]; then say "  [skip] train $tag -- $model/belief.pt exists"; return 0; fi
    say "  [$(date '+%H:%M:%S')] train $tag, $EPOCHS epochs, $data -> $model"
    if ! "$PYTHON" arena/python/train_belief.py --data "$data" --out "$model" --epochs "$EPOCHS" \
            > "$LOG/belief-null-train-$tag.txt" 2>&1; then
        tail -5 "$LOG/belief-null-train-$tag.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  TRAINING FAILED -- see $LOG/belief-null-train-$tag.txt"; exit 1
    fi
    grep -E "uniform sampler baseline|best held-out" "$LOG/belief-null-train-$tag.txt" | tail -3 | sed 's/^/      /' | tee -a "$SUMMARY"
}
train "$TRUMP" "$TRUMP_MODEL" trump
stopped && exit 0
train "$MIXED" "$MIXED_MODEL" mixed
stopped && exit 0
train "$NULLDATA" "$NULL_MODEL" nullonly
stopped && exit 0

# 5. The weights the arena and the app read. Only the mixed model gets them:
# the Null-only net exists to answer a question, not to be played.
if [ -f "$MIXED_MODEL/belief.bin" ]; then
    say "  [skip] weights -- $MIXED_MODEL/belief.bin exists"
else
    say "  [$(date '+%H:%M:%S')] export_weights $MIXED_MODEL"
    if ! "$PYTHON" arena/python/export_weights.py --model="$MIXED_MODEL" > "$LOG/belief-null-weights.txt" 2>&1; then
        tail -5 "$LOG/belief-null-weights.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
        say "  WEIGHTS FAILED -- see $LOG/belief-null-weights.txt"; exit 1
    fi
    grep -E "belief.bin|parity" "$LOG/belief-null-weights.txt" | sed 's/^/      /' | tee -a "$SUMMARY"
fi
stopped && exit 0

# 6. The three numbers. Every one recomputed here rather than quoted from a
# model.json, because a stored val_accuracy was measured on that model's own
# corpus and these comparisons cross corpora.
read_off() {
    local model="$1" data="$2" extra="${3:-}"
    local out="$LOG/belief-null-eval-$(basename "$model")-on-$(basename "$data").txt"
    # shellcheck disable=SC2086 -- $extra is deliberately word-split
    if ! "$PYTHON" arena/python/eval_belief.py --model "$model" --data "$data" $extra > "$out" 2>&1; then
        tail -3 "$out" | sed 's/^/      /' | tee -a "$SUMMARY"; return 0
    fi
    grep -E "records|^  !!" "$out" | sed 's/^/      /' | tee -a "$SUMMARY"
}
say ""
say "--- does sharing cost Null? (the held-out tenth of $NULLDATA) ---"
say "  mixed net:"
read_off "$MIXED_MODEL" "$NULLDATA"
say "  Null-only net:"
read_off "$NULL_MODEL" "$NULLDATA"
say ""
say "--- did Null poison the trump positions? (the held-out tenth of $TRUMP) ---"
say "  mixed net:"
read_off "$MIXED_MODEL" "$TRUMP" --by-contract
say "  trump-only control:"
read_off "$TRUMP_MODEL" "$TRUMP" --by-contract
say ""
stopped && exit 0

# 7. The gates. Same naming as overnight-arena.sh, so ~/pool.py and the paired
# CSVs read these like any other night. The Null row is its own match on
# --contracts=null boards, because Null is 3% of the oracle's contract mix and
# an ordinary gate cannot see it (tools/null-card-play.sh).
PROPS="-Dbelief.model.candidate.dir=$MIXED_MODEL"
match() {
    local a="$1" b="$2" count="$3" extra="${4:-}"
    local mode=auction
    case "$extra" in *contracts=null*) mode=null ;; *fixed-contract*) mode=cardplay ;; esac
    case "$extra" in *--passed-in=void*) mode="$mode-void" ;; esac
    local tag="$a-vs-$b-$mode-s$SEED"
    $QUICK && tag="$tag-b$count"
    local report="$LOG/$tag.txt"
    if [ -f "$report" ]; then say "  [skip] $tag -- log already exists"; return 0; fi
    [ -f STOP ] && return 0
    say "  [$(date '+%H:%M:%S')] $tag ($count boards)"
    # shellcheck disable=SC2086 -- $extra and $PROPS are deliberately word-split
    ./gradlew --console=plain -q --no-daemon $PROPS :arena:arena \
        --args="--a=$a --b=$b --boards=$count --seed=$SEED --threads=$THREADS $extra --quiet --csv=$ROOT/$LOG/$tag.csv" \
        > "$report" 2>&1
    if [ $? -ne 0 ]; then
        mv "$report" "$LOG/$tag.failed.txt"
        say "      FAILED -- see $tag.failed.txt"
        return 0
    fi
    grep -E "^game pts/game|^declares|^rule violations| = .*game pts/game|^Resolved|^Not resolved" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
}

XSKAT=false; GOSKAT=false
{ [ -x third_party/xskat/skatklar-xskat ] || [ -x third_party/xskat/skatklar-xskat.exe ]; } && XSKAT=true
{ [ -x third_party/go-skat/skatklar-goskat ] || [ -x third_party/go-skat/skatklar-goskat.exe ]; } && GOSKAT=true
SHIPPED=belief-32-shipped
CANDIDATE=belief-32-shipped-candidate
for SEED in $SEEDS; do
    stopped && break
    say "--- seed $SEED ---"
    # The row this is for: Null card play with the new belief behind it.
    match "$CANDIDATE" "$SHIPPED" "$NULLGATE" "--fixed-contract --contracts=null"
    # And the rows that say it cost nothing anywhere else.
    match belief-32-candidate belief-32 "$GATE" "--fixed-contract"
    match "$CANDIDATE" "$SHIPPED" "$GATE" "--passed-in=void"
    $XSKAT  && match "$CANDIDATE" xskat     "$GATE" "--passed-in=void"
    match "$CANDIDATE" jskat-new "$GATE" "--passed-in=void"
    $GOSKAT && match "$CANDIDATE" go-skat   "$GATE_SMALL" "--passed-in=void"
done
say "belief-null finished $(date '+%Y-%m-%d %H:%M:%S')"
