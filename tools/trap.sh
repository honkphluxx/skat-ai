#!/usr/bin/env bash
# The trap tally at flat zero, in two orders against the ladder.
#
#   ./tools/trap.sh                      the audit (seeds 14-16), then the pairing (11-13)
#   ./tools/trap.sh --quick
#   ./tools/trap.sh --audit-only
#   ./tools/trap.sh --pair-only --seeds="14 15 16"
#                                        three more seeds of the pairing; the
#                                        pooled line reads every seed on disk
#   ./tools/trap.sh --players=belief-32-shipped-trap-safe
#                                        the one-defender variants of the first
#                                        run; the default is the two-defender pair
#   ./tools/trap.sh --threads=8
#   ./tools/trap.sh --pair-only --players=belief-128-shipped --against=belief-64-shipped
#                                        pair against another player than the shipped one
#
# docs/training-plan.md section 2.9: SkatZero's whole declaring edge over the
# shipped player sits in the games our declarer sees at flat zero -- every
# card losing in every sampled world. There we win 17 of 238 and SkatZero 48,
# because its lines leave the defenders more ways to hand the game back.
# SearchAiProvider.TrapOrder scores each card there by the share of the next
# defender's replies that would do so, summed over the worlds:
#
#   belief-32-shipped-trap        the widest trap, the ladder's 31 as tiebreak
#   belief-32-shipped-trap-safe   the ladder's card, the widest trap as tiebreak
#
# Measured 2026-09-25 at one defender: nothing (-0.20 and -0.04 pooled), and
# the decision files said why -- three quarters of the games handed back are
# handed back by the second defender after the declarer's card, which a
# one-defender width cannot see. The default is now the same pair counting
# both defenders before the declarer moves again (TrapOrder.*_BOTH):
#
#   belief-32-shipped-trap2       the widest two-defender trap, then 31
#   belief-32-shipped-trap2-safe  31, then the widest two-defender trap
#
# 1. The declaring audit for both on seeds 14-16 -- the shipped player's audit
#    of 2026-09-24 on the same boards is the control -- and one table from the
#    three: games won, from declaring, games that reached flat zero and how
#    many of those were won, and games lost with Schneider. The first two
#    are the points; the last two are the trade the ladder was bought for.
# 2. Each variant against belief-32-shipped, fixed contracts, exact pairing,
#    seeds 11-13, pooled. The ship decision reads this row; the split and the
#    SkatZero row follow for a variant that resolves.
#
# CPU: the tally is one solver question per card per world on the decisions
# it is asked on (the declarer's flat-zero ones, about a third), and on a
# lead after the declarer wins a trick, one per lead. The pairing reports'
# "s, games/s" lines against the shipped self-match say what it costs.
#
# This file must keep LF line endings (.gitattributes pins *.sh).

if [ -z "${TRAP_ORIGINAL:-}" ]; then
    TRAP_ORIGINAL="$0"
    copy="$(mktemp "${TMPDIR:-/tmp}/trap.XXXXXX")" || exit 1
    cp "$0" "$copy" || exit 1
    TRAP_ORIGINAL="$TRAP_ORIGINAL" exec bash "$copy" "$@"
fi
cd "$(dirname "$TRAP_ORIGINAL")/.." || exit 1
ROOT=$(pwd -W 2>/dev/null || pwd)

QUICK=false; THREADS=16; AUDIT=true; PAIR=true; AGAINST=belief-32-shipped
PLAYERS="belief-32-shipped-trap2 belief-32-shipped-trap2-safe"
for arg in "$@"; do
    case "$arg" in
        --quick)       QUICK=true ;;
        --threads=*)   THREADS="${arg#*=}" ;;
        --seeds=*)     SEEDS_ARG="${arg#*=}" ;;
        --players=*)   PLAYERS="${arg#*=}"; PLAYERS="${PLAYERS//,/ }" ;;
        --audit-only)  PAIR=false ;;
        --pair-only)   AUDIT=false ;;
        --against=*)   AGAINST="${arg#*=}" ;;
        -h|--help)     sed -n '2,42p' "$TRAP_ORIGINAL" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

LOGROOT=arena-logs; AUDIT_SEEDS="14 15 16"; PAIR_SEEDS="11 12 13"; BOARDS=200; QUICKFLAG=""
if $QUICK; then LOGROOT=arena-logs/quick; AUDIT_SEEDS="11"; PAIR_SEEDS="11"; BOARDS=30; QUICKFLAG="--quick"; fi
[ -n "${SEEDS_ARG:-}" ] && PAIR_SEEDS="$SEEDS_ARG"
SUMMARY=$LOGROOT/summary-trap.txt
LOG=$LOGROOT/trap; mkdir -p "$LOG"
say() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
[ -f STOP ] && { rm -f STOP; echo "Removed a STOP file left over from an earlier run."; }
say "============================================================"
say "trap started $(date '+%Y-%m-%d %H:%M:%S')   players=$PLAYERS quick=$QUICK threads=$THREADS"

# One line per player from its audit CSVs: games, won, from declaring (tp per
# game, the gate's unit is tp/3), flat-zero games and how many were won, and
# lost games with Schneider.
audit_line() {
    local label="$1" dir="$2" seeds="$3" games="" decisions=""
    for s in $seeds; do
        [ -f "$dir/declaring-audit-games-s$s.csv" ] || { printf '  %-30s no audit for seed %s in %s\n' "$label" "$s" "$dir"; return; }
        games="$games $dir/declaring-audit-games-s$s.csv"; decisions="$decisions $dir/declaring-audit-decisions-s$s.csv"
    done
    # shellcheck disable=SC2086
    awk -F, -v label="$label" '
        FNR == 1 { next }
        FILENAME ~ /decisions/ { if ($4 == "us" && $17 == "FLAT_ZERO") flat[$1 " " $2] = 1; next }
        $5 == "us" { n++; won += $7; tp += $10; key[$1 " " $2] = $7; if ($7 == 0) { lost++; if ($8 < 31) schneidered++ } }
        END {
            for (k in flat) { fz++; if (key[k] == 1) fzwon++ }
            printf "  %-30s won %3d of %3d   from declaring %6.2f   flat zero %3d, won %2d   lost %3d, Schneidered %2d\n",
                label, won, n, tp / (3 * n), fz, fzwon, lost, schneidered
        }' $decisions $games
}

if $AUDIT; then
    say "--- 1. declaring audit, seeds $AUDIT_SEEDS (the control is the shipped player's audit on the same boards) ---"
    # The control, taken if it is not on disk (a --quick run, or a fresh checkout).
    have=true; for s in $AUDIT_SEEDS; do [ -f "$LOGROOT/par-audit/declaring-audit-games-s$s.csv" ] || have=false; done
    if ! $have; then
        say "  [$(date '+%H:%M:%S')] audit belief-32-shipped (the control)"
        ./tools/declaring-audit.sh --seeds="$AUDIT_SEEDS" --threads="$THREADS" $QUICKFLAG > /dev/null
    fi
    for P in $PLAYERS; do
        [ -f STOP ] && break
        dir="$LOGROOT/par-audit/$P"
        have=true; for s in $AUDIT_SEEDS; do [ -f "$dir/declaring-audit-games-s$s.csv" ] || have=false; done
        if $have; then say "  [skip] audit $P -- CSVs exist"; else
            say "  [$(date '+%H:%M:%S')] audit $P"
            ./tools/declaring-audit.sh --player="$P" --seeds="$AUDIT_SEEDS" --threads="$THREADS" $QUICKFLAG \
                | grep -E "wins .* of|throws|same result|cold |not cold|FLAT-ZERO|decisions\), in|we won" | sed 's/^/      /' | tee -a "$SUMMARY"
        fi
    done
    shipped_dir="$LOGROOT/par-audit"
    audit_line belief-32-shipped "$shipped_dir" "$AUDIT_SEEDS" | tee -a "$SUMMARY"
    for P in $PLAYERS; do audit_line "$P" "$LOGROOT/par-audit/$P" "$AUDIT_SEEDS" | tee -a "$SUMMARY"; done
    [ -f STOP ] && { say "STOP"; exit 0; }
fi

if $PAIR; then
    say "--- 2. each variant against $AGAINST, fixed contracts, seeds $PAIR_SEEDS ---"
    for P in $PLAYERS; do
        for SEED in $PAIR_SEEDS; do
            [ -f STOP ] && break
            tag="$P-vs-$AGAINST-cardplay-s$SEED"; $QUICK && tag="$tag-b$BOARDS"
            report="$LOG/$tag.txt"
            if [ ! -f "$report" ]; then
                say "  [$(date '+%H:%M:%S')] $tag ($BOARDS boards)"
                ./gradlew --console=plain -q --no-daemon :arena:arena \
                    --args="--a=$P --b=$AGAINST --boards=$BOARDS --seed=$SEED --threads=$THREADS --fixed-contract --quiet --csv=$ROOT/$LOG/$tag.csv" \
                    > "$report" 2>&1 || { mv "$report" "$LOG/$tag.failed.txt"; say "      FAILED -- see $tag.failed.txt"; continue; }
            fi
            grep -E "^  from declaring|^  from defending|^wins as declarer| = .*game pts/game|^Resolved|^Not resolved|games/s" "$report" | sed 's/^/      /' | tee -a "$SUMMARY"
        done
        diffs=""
        for report in "$LOG/$P-vs-$AGAINST"-cardplay-s*.txt; do
            [ -f "$report" ] || continue
            case "$report" in *-b[0-9]*.txt) $QUICK || continue ;; *) $QUICK && continue ;; esac
            d=$(grep -E " = .*game pts/game" "$report" | sed -E 's/.* = ([-+0-9.]+) game pts.*/\1/'); [ -n "$d" ] && diffs="$diffs $d"
        done
        echo "$diffs" | awk -v label="$P" -v against="$AGAINST" '{
            n = NF; if (n < 2) { print "  " label ": need two seeds to pool"; exit }
            for (i = 1; i <= n; i++) sum += $i; mean = sum / n
            for (i = 1; i <= n; i++) ss += ($i - mean) ^ 2
            se = sqrt(ss / (n - 1)) / sqrt(n)
            split("12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262", t, " "); q = (n - 1 <= 9) ? t[n - 1] : 1.96
            printf "  %s - %s, pooled over %d seeds: %+.2f game pts/game, 95%% [%+.2f, %+.2f]\n", label, against, n, mean, mean - q * se, mean + q * se
        }' | tee -a "$SUMMARY"
    done
fi
say "trap finished $(date '+%Y-%m-%d %H:%M:%S')"
