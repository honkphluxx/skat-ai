#!/bin/sh
# Builds the helper binaries that let the arena seat XSkat and go-skat.
#
# Neither engine is redistributed here. Both are fetched into third_party/ and
# built in place with a driver of ours copied in; nothing upstream is modified.
# A missing engine, a missing compiler or a failed build costs the arena a
# contestant, never a run -- so this script reports and carries on.
set -u

root=$(cd "$(dirname "$0")/.." && pwd)
xskat="$root/third_party/xskat"
goskat="$root/third_party/go-skat"
status=0

# Windows needs the suffix spelled out for Go. MinGW's gcc adds .exe to a -o
# without an extension all by itself; `go build -o name` does not, and a file
# with no extension is not something CreateProcess will start -- so the arena
# would build a helper it could never launch.
EXE=""
case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac

# Reports a built helper whichever name it ended up with.
built() {
    [ -x "$1" ] || [ -x "$1.exe" ]
}

say() { printf '%s\n' "$*"; }

if [ ! -f "$xskat/skat.c" ]; then
    say "third_party/xskat is empty -- fetch XSkat 4.0 from http://www.xskat.de/"
    say "  and unpack it there to measure against it. Building without it."
elif ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1; then
    say "No C compiler on PATH; skipping the XSkat helper."
    say "  On Windows, w64devkit is the least trouble: unpack it and run its"
    say "  w64devkit.exe shell, or put its bin/ on PATH. See docs/external-bots.md."
else
    CC=${CC:-$(command -v cc || command -v gcc)}
    say "Building the XSkat helper with $CC"
    # Which C dialect this compiler has to be told to speak.
    #
    # XSkat is K&R C throughout and declares its functions with empty
    # parentheses. Through GCC 14 that meant "takes unspecified arguments" and
    # everything matched; GCC 15 defaults to -std=gnu23, where it means "takes
    # nothing", and every definition in the file collides with its own
    # prototype -- "number of arguments doesn't match prototype", forty times.
    # w64devkit ships a GCC new enough to do this, which is how it was found.
    #
    # Detected rather than hard-coded: compile the smallest program that has
    # the problem and keep the first dialect that builds it. That way a
    # compiler nobody here has tried gets the right answer instead of the
    # answer that suited the one that was.
    probe=$(mktemp -d 2>/dev/null || echo "${TMPDIR:-/tmp}/skatklar-probe.$$")
    mkdir -p "$probe"
    printf 'int f();\nint f(a) int a; { return a; }\n' > "$probe/knr.c"
    CSTD=""
    for candidate in "-std=gnu89" "-std=gnu89 -fpermissive" "-std=gnu17" ""; do
        # shellcheck disable=SC2086 -- $candidate is deliberately word-split
        if $CC $candidate -w -c "$probe/knr.c" -o "$probe/knr.o" 2>/dev/null; then
            CSTD="$candidate"
            break
        fi
    done
    rm -rf "$probe"
    # Not "${CSTD:-...}" with an apostrophe in the default: inside double
    # quotes bash reads that apostrophe as opening a quote, and the script
    # dies at end of file complaining about a line forty lines further down.
    if [ -n "$CSTD" ]; then
        say "  K&R dialect: $CSTD"
    else
        say "  K&R dialect: whatever this compiler does by default"
    fi
    # Which XSkat this is. The mfrasca fork adds a formatted game value and a
    # buffer for it that calc_result writes into unconditionally; pristine 4.0
    # has neither. Asking the header is better than asking the version string,
    # because it is the declaration the driver actually needs.
    CDEFS=""
    if grep -q "spwert_text" "$xskat/skat.h" 2>/dev/null; then
        CDEFS="-DHAVE_SPWERT_TEXT"
        say "  source: the mfrasca fork (has spwert_text)"
    else
        say "  source: pristine 4.0"
    fi
    # skat.c is compiled unmodified; -Dmain= only moves its main() out of the
    # way so the driver can provide one. No X11: the driver replaces xio.c and
    # xdial.c, which are the only files that ever included it.
    ( cd "$xskat" \
      && cp "$root/external/xskat/skatklar_driver.c" . \
      && $CC $CSTD -O2 -w -DDEFAULT_LANGUAGE='"english"' -Dmain=xskat_main_unused \
             -c skat.c -o skatklar_skat.o \
      && $CC $CSTD -O2 -w -DDEFAULT_LANGUAGE='"english"' -c null.c -o skatklar_null.o \
      && $CC $CSTD -O2 -w -DDEFAULT_LANGUAGE='"english"' -c ramsch.c -o skatklar_ramsch.o \
      && $CC $CSTD -O2 -w -DDEFAULT_LANGUAGE='"english"' -c text.c -o skatklar_text.o \
      && $CC $CSTD $CDEFS -O2 -w -DDEFAULT_LANGUAGE='"english"' -c skatklar_driver.c -o skatklar_driver.o \
      && $CC skatklar_skat.o skatklar_null.o skatklar_ramsch.o skatklar_text.o \
             skatklar_driver.o -o skatklar-xskat ) \
      || { say "  XSkat helper failed to build."; status=1; }
    built "$xskat/skatklar-xskat" && say "  -> third_party/xskat/skatklar-xskat$EXE"
fi

if [ ! -f "$goskat/go.mod" ]; then
    say "third_party/go-skat is empty -- clone https://github.com/dranidis/go-skat"
    say "  there to measure against it. Building without it."
elif ! command -v go >/dev/null 2>&1; then
    say "No Go toolchain on PATH; skipping the go-skat helper."
else
    say "Building the go-skat helper"
    ( cd "$goskat" \
      && cp "$root/external/go-skat/skatklar_driver.go" . \
      && go build -o "skatklar-goskat$EXE" . ) \
      || { say "  go-skat helper failed to build."; status=1; }
    built "$goskat/skatklar-goskat" && say "  -> third_party/go-skat/skatklar-goskat$EXE"
fi

exit $status
