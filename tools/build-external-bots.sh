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

say() { printf '%s\n' "$*"; }

if [ ! -f "$xskat/skat.c" ]; then
    say "third_party/xskat is empty -- fetch XSkat 4.0 from http://www.xskat.de/"
    say "  and unpack it there to measure against it. Building without it."
elif ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1; then
    say "No C compiler on PATH; skipping the XSkat helper."
else
    CC=${CC:-$(command -v cc || command -v gcc)}
    say "Building the XSkat helper with $CC"
    # skat.c is compiled unmodified; -Dmain= only moves its main() out of the
    # way so the driver can provide one. No X11: the driver replaces xio.c and
    # xdial.c, which are the only files that ever included it.
    ( cd "$xskat" \
      && cp "$root/external/xskat/skatklar_driver.c" . \
      && $CC -O2 -w -DDEFAULT_LANGUAGE='"english"' -Dmain=xskat_main_unused \
             -c skat.c -o skatklar_skat.o \
      && $CC -O2 -w -DDEFAULT_LANGUAGE='"english"' -c null.c -o skatklar_null.o \
      && $CC -O2 -w -DDEFAULT_LANGUAGE='"english"' -c ramsch.c -o skatklar_ramsch.o \
      && $CC -O2 -w -DDEFAULT_LANGUAGE='"english"' -c text.c -o skatklar_text.o \
      && $CC -O2 -w -DDEFAULT_LANGUAGE='"english"' -c skatklar_driver.c -o skatklar_driver.o \
      && $CC skatklar_skat.o skatklar_null.o skatklar_ramsch.o skatklar_text.o \
             skatklar_driver.o -o skatklar-xskat ) \
      || { say "  XSkat helper failed to build."; status=1; }
    [ -x "$xskat/skatklar-xskat" ] && say "  -> third_party/xskat/skatklar-xskat"
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
      && go build -o skatklar-goskat . ) \
      || { say "  go-skat helper failed to build."; status=1; }
    [ -x "$goskat/skatklar-goskat" ] && say "  -> third_party/go-skat/skatklar-goskat"
fi

exit $status
