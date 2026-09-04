# The native double-dummy solver

`native/`, `engine/.../solve/NativeSolver.java`, `engine/.../solve/SolverParityTest.java`

The double-dummy search is where this project spends its CPU. It is what a
bidder asks forty times before it says a number, what the search player asks
once per sampled world per card, and — since the server seats the same player
the app ships — what a server full of tables is mostly doing. It is now written
twice: once in Java, which is the specification, and once in C++, which is what
actually runs.

**Java is not deleted and is not dead code.** Every entry point tries the
library and falls back to the Java search when there is no library for this
platform, when it will not load, when it is the wrong version, or when the
contract is one it refuses. That fallback is exercised on every build by
`-Dskatklar.solver=java`, and it is what makes shipping a native binary a
performance decision rather than a portability risk.

## What it bought

Measured on 150 identical ten-card deals, and on the bidding evaluation a
player actually waits for. Same machine, same deals, same answers.

| | Java | C++ | |
|---|---|---|---|
| `declarerReaches(61)`, per deal | 10.2 ms | 3.7 ms | **2.8x** |
| exact value, per deal | 370 ms | 241 ms | **1.5x** |
| nodes for the exact value | 1.16 bn | 0.70 bn | 1.66x fewer |
| `HandEvaluator`, 5 contracts x 8 worlds, per hand | 2243 ms | 936 ms | **2.4x** |

The first row is the one that matters: the yes/no question is what the app and
the server ask, and the exact value is the arena's.

**A straight translation was worth 1.4x.** That is the honest number for
"C++ is faster than Java", and it is why the brief said to optimise the search
rather than only port it. The JIT is good at this code. Everything above 1.4x
came from doing something different:

- **Equivalent cards are collapsed.** In a trick game that counts only tricks —
  bridge — any two adjacent cards are interchangeable, which is the classic
  double-dummy saving. Skat counts card points, so two adjacent cards are
  interchangeable only when they are also *worth* the same, and that is a much
  shorter list: the four jacks (two points each, adjacent at the top of trump)
  and the nine-eight-seven of every class (nothing each, adjacent at the
  bottom). It is still worth a great deal, because low cards are exactly what a
  hand holds several of and exactly what gets led when nothing better is
  available. Two cards count as interchangeable only when no *live* card of
  their class lies between them — and a card lying in the current trick is
  live, because it can still be the card that wins it.
- **Killer moves.** The card that caused a cut-off at this depth is tried first
  at its siblings, before the transposition table's move and before strength.
  Two slots per depth, no hashing, one comparison while the moves are scored.
- **The table is sized for the question.** The Java starts every table at
  sixteen kilobytes and doubles when three quarters full, which is right when
  the size is unknowable. At the entry point it *is* knowable, because the
  question is known: a null-window question keeps getting cheaper up to a
  megabyte and then turns round as the table stops fitting in cache; the exact
  question searches thirty times as much. Building it at that size costs one
  memset of about thirty microseconds and saves ten doublings that each
  re-insert everything.
- **Points by table, not by loop.** Counting what is left is twelve lookups
  rather than thirty iterations, at the top of every trick-start node.
- **No table at the last trick.** It is forced and one node deep, and it is the
  most numerous node in the tree by a wide margin.

Two things were tried and measured *worse*, and are not in the code: ordering
the defenders' moves weakest-first (nearly twice the nodes — strength
descending is right for both sides), and keeping one solver per thread between
questions (the callers reshuffle the other two hands every world, so a kept
table is pollution rather than reuse).

## What the two engines must agree about

The **declarer point value** of a position, exactly. Every **yes/no verdict**,
exactly — a null window's answer does not depend on how tightly it was
searched.

The **card** need not match. Collapsing equivalents means the C++ will
sometimes name the eight where Java named the nine, and those are the same card
as far as the game is concerned. `SolverParityTest` asserts what the difference
is allowed to be: whenever the two name different cards, playing either must
lead to the same value.

That test puts both engines the same questions inside one JVM, which is what
`DoubleDummySolver.nativeEnabled` exists for. It skips where there is no
library — which is most Windows checkouts — and a skipped run has proved
nothing, so the machine that builds a release is where it has to pass.

`native/test/selftest.cpp` asks a different question, without a JVM: does
alpha-beta with all its pruning and its table return exactly what plain minimax
returns? That is the invariant the optimisation work rests on, and
`./gradlew -PserverOnly=true :server:checkNativeSolver` is how to ask it.

## Shipping it

**Android**: `app/build.gradle.kts` builds `native/CMakeLists.txt` through
`externalNativeBuild` for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`, and
the APK carries them as ordinary JNI libraries. `System.loadLibrary` finds
them; nothing is unpacked.

**Everywhere else — the server, the arena, the seed generator, `:engine:test`**:
the library travels as a resource under
`engine/src/main/resources/dev/skatklar/native/<family>-<cpu>/`, and
`NativeSolver` writes it into the temporary directory on first use and loads it
from there.

**It lives in the engine for a reason, and it did not always.** Until 2026-08-28
it sat in the server's resources, which meant only the server could ever find
it: the arena and the engine's own tests depend on the engine and not on the
server, so the
arena, the seed generator and the whole core test suite had been running on the
Java search on every platform since the port landed — including
`SolverParityTest`, which is the one test whose entire job is to compare the two
engines and which was therefore comparing the Java engine with itself. Core is
the only module every desktop consumer has on its classpath.

The app is the one consumer that must *not* have it: it builds its own per-ABI
libraries above, and a desktop `.so` or `.dll` inside the APK is dead weight
nothing can load. `app/build.gradle.kts` excludes `dev/skatklar/native/**` from
the packaging.

A server is installed by copying one jar, so its library still has to travel
inside that jar — it now arrives there through core rather than from the server
module's own resources. The unit's `PrivateTmp=true` gives each core its own
temporary directory, which is where this lands and where it goes when the core
exits; `-Dskatklar.solver.dir=<path>` overrides it if that is ever not writable.

Rebuild for this machine's own platform:

```bash
./gradlew -PserverOnly=true :server:buildNativeSolver   # writes into core's resources
./gradlew -PserverOnly=true :server:checkNativeSolver
./gradlew :engine:test                          # SolverParityTest runs here
```

### Cross-building the Windows library from Linux

Nobody has to, but somebody did, and the recipe is short enough to keep:

```bash
JH=$(dirname $(dirname $(readlink -f $(which javac))))
cmake -S native -B build/win \
    -DCMAKE_SYSTEM_NAME=Windows \
    -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
    -DCMAKE_BUILD_TYPE=Release \
    -DSKAT_JNI_INCLUDE="$JH/include;$JH/include/linux"
cmake --build build/win --target skatsolve
```

`SKAT_JNI_INCLUDE` exists for exactly this: a `find_package(JNI)` on the build
host hands a Windows build the host's own headers, which is either wrong or
accidentally right, and neither is a thing to leave to chance. Pointing it at
the host JDK is deliberate and safe **for x86-64 only**: `jint` is 32 bits
either way (Windows spells it `long`, Linux `int`), `jlong` is 64, `jbyte` is
one byte, and `__stdcall` is a no-op in the x64 calling convention. `jni.cpp`
static-asserts the three sizes rather than trusting that paragraph. The one
thing that genuinely differs is `JNIEXPORT`, which has to be
`__declspec(dllexport)` or the PE file exports nothing; the JDK's header guards
that define with `#ifndef`, so CMake supplies it when cross-compiling.

Two Windows-specific things in the build, both for the same reason the Linux
library links against libc and nothing else:

- **`QueryPerformanceCounter` instead of `clock_gettime`.** Windows has no
  `clock_gettime`; mingw's comes from winpthreads, and linking a threading
  runtime for one clock call is the same mistake in a different library. The
  reading is assembled from seconds and remainder because the counter runs from
  boot at megahertz and `ticks * 1000000000` overflows a signed 64-bit integer
  within months of uptime — and that failure would be a deadline in the past,
  which reads as a solver that answers nothing.
- **`-static -static-libgcc -static-libstdc++`.** A DLL that needs
  `libgcc_s_seh-1.dll` beside it will not load on a machine that has only a
  JRE, which is every machine this is for.

Check any Windows build the way the Linux one is checked:

```bash
x86_64-w64-mingw32-objdump -p skatsolve.dll | grep "DLL Name"   # KERNEL32, msvcrt
x86_64-w64-mingw32-objdump -p skatsolve.dll | sed -n '/Name Pointer/,/^$/p'
```

Twelve exported names, all `Java_dev_skatklar_demo_solve_NativeSolver_*`, and no
`libgcc`, `libstdc++` or `libwinpthread` among the imports.

The binaries are checked in because the machine that cuts a release is not
always one that can build for the target, and a jar that quietly lost its
library would simply be slower with nothing in the log to say why.

**The Windows library has been built and inspected, not run.** No Windows
machine was available to this work. `SolverParityTest` is the acceptance test —
it puts the same ten positions to both engines in one JVM — and
`NativeSolver.verify()` falls back to the Java search with one logged line if
the library will not load or answers the wrong version, so the failure mode is
slowness rather than a wrong answer.

## The library depends on almost nothing, on purpose

It is 43 KB, exports twelve symbols, and links against `libc` and nothing else.
The highest glibc symbol version it asks for is `GLIBC_2.17` — 2012.

That took deliberate work and it was worth it. The first build used
`std::call_once`, `std::chrono`, `std::vector` and C++ exceptions, and linked
the C++ runtime statically so as not to depend on the host's: the result was
1.5 MB, exported 759 symbols, and required `GLIBC_2.38`. It would have loaded
on the machine that built it and refused, on a server two releases older, with
an error naming a symbol version rather than anything a person could act on.

So: the tables are computed by the compiler into `.rodata` rather than built
behind a `once_flag` (which is `pthread_once`, which is where `GLIBC_2.34` came
from); `clock_gettime` replaces `std::chrono`; `calloc` replaces `std::vector`;
and the deadline, which used to unwind thirty frames with an exception, is a
flag checked after each child — one predictable branch that measured as
nothing. A version script keeps everything but the JNI entry points inside,
which matters more than tidiness: a library loaded into a JVM shares its symbol
namespace with everything else in the process.

**The library includes `<stdint.h>`, not `<cstdint>`**, and calls `memset` and
`calloc` rather than `std::memset` and `std::calloc`. That is not a style
preference and it is the one rule here that is easy to break by accident. The
Android build sets `ANDROID_STL=none`, which passes `-nostdinc++`: it takes the
C++ *headers* away along with the runtime, and every c-prefixed spelling is a
C++ header. The C ones come from the platform's libc and are there either way.
One `#include <cstdint>` is enough to break all four Android ABIs while every
desktop build stays green, which is exactly what happened the first time.

The `skatsolve_freestanding` target exists to stop that happening again: it
compiles the same three files with `-nostdinc++` on the host, so a c-prefixed
include fails on whatever machine added it rather than in a build log from a
machine its author does not have. The checker and benchmark binary is
deliberately *not* built for Android — it is a program rather than a library
and uses `<vector>` and `<string>` quite happily.

The host link carries the NDK's three strictness flags for the same reason:
`--no-undefined`, `--no-undefined-version` and `--fatal-warnings`. The second
is why `src/exports.map` says `JNI_On*` rather than `JNI_OnLoad` — naming a
symbol the library does not define is an error under it, and this library
defines no `JNI_OnLoad`, because its entry points are found by their names in
the classic way and there is nothing for one to do. A wildcard that matches
nothing is not an error, so the pattern can sit there ready for the day one is
added.

## Where the boundary is

The JNI layer reads no Java object, allocates none, and looks nothing up by
name. Hands cross as three ints, positions as ints, and the two calls with more
than one number to return write into an array the caller already owns. That is
partly speed — a search leaf that had to build a `Card` would be a different
program — and partly that every one of those operations can throw, and a JNI
function that throws through a search is a crash rather than an exception.

`AlphaMu` is the one caller that holds a native solver rather than asking a
question and forgetting: it keeps thirty-two of them, one per world, so that
the same endgame is not re-derived for every leaf. Those are handles, and
`DoubleDummySolver` is `AutoCloseable` because of it —
`AlphaMu.rank` closes them in a `finally`. `NativeHandles` is the net
underneath: a phantom reference per handle, so a forgotten solver is freed the
next time one is created rather than never. Not `java.lang.ref.Cleaner`, which
is the same thing with a thread attached and needs API 33; this app supports
26.

## Not done

- **Null games.** The native engine refuses them and says so, and every caller
  already falls back — `NullSolver` is a different search with a binary
  objective, and none of the windows, cut-offs or table values here mean
  anything for it. It is also far less hot: Null prunes hard on its own.
- **Parallelism.** Deliberately: the server's problem is throughput across many
  tables, not latency on one, and threads inside one solve would buy the app a
  little and the server nothing.
- **The four Android ABIs have not been run.** There is no NDK on the machine
  this was written on. The first attempt at building them failed on
  `#include <cstdint>` under `ANDROID_STL=none`; that is fixed, and the fix is
  now checked by `skatsolve_freestanding` and by compiling with clang's
  `-nostdinc++`, which is the same condition. But compiling is not running:
  `SolverParityTest` on a device is what would say the ABIs are *right*, and
  nothing here can say that.

## Where the C++ stops

This port is not a beachhead. The rule it establishes — C++ at a leaf, behind a
narrow C API, holding no game state and no wire contract, with the Java kept as the
specification and as a fallback — is what made it safe, and it is also what says the
server should not follow. See [`server-language.md`](server-language.md).
