# One CMake tree for every platform this solver ships on.
#
# The Android app builds it through externalNativeBuild for four ABIs; the
# server jar carries a Linux x86-64 build of the same sources. There is
# deliberately no second build system and no per-platform source: a solver that
# is fast on arm64 and subtly different on x86-64 is two solvers, and only one
# of them would ever be tested.
#
# Targets:
#   skatsolve         the shared library, JNI included, for the app and the jar
#   skatsolve_core    the same search as a static library, no JNI
#   skatsolve_test    a standalone checker and benchmark that needs no JVM
#
# The JNI half is compiled only when JNI headers are found, so the test binary
# still builds on a machine with no JDK at all.

cmake_minimum_required(VERSION 3.18)
project(skatsolve CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# The identity the library reports through skat_version, so a stale or
# mismatched .so can be recognised as one from the Java side rather than by its
# behaviour. Bump it when the search changes in a way callers should notice.
set(SKAT_SOLVE_VERSION "1" CACHE STRING "Build identity reported by skat_version")

add_library(skatsolve_core STATIC
        src/tables.cpp
        src/solver.cpp
        src/api.cpp)
target_include_directories(skatsolve_core PUBLIC include src)
target_compile_definitions(skatsolve_core PRIVATE
        SKAT_SOLVE_VERSION="${SKAT_SOLVE_VERSION}")

# -O2 rather than -O3: measured on this search, -O3 bought nothing outside the
# noise and grew the library.
#
# The library includes <stdint.h> and <string.h> rather than <cstdint> and
# <cstring>, and calls memset and calloc rather than std::memset and
# std::calloc. That is not a style choice. The Android build uses
# ANDROID_STL=none, which passes -nostdinc++ and takes the C++ headers away
# along with the runtime, and every one of the c-prefixed spellings is a C++
# header. The C ones come from the platform's libc and are there either way.
# `make check-freestanding` below compiles the same way on the host, so the
# next person to add an include finds out here rather than in a build log from
# a machine they do not have.
#
# No exceptions and no RTTI, and that is a portability decision rather than a
# size one. Both drag in libstdc++ and libgcc's unwinder, and a build that
# carried those statically demanded GLIBC_2.38 -- a glibc from 2023 -- so the
# library would have refused to load on a server two releases old, with an
# error naming a symbol version rather than anything a person could act on.
# Without them this compiles against libc alone and asks for nothing newer than
# GLIBC_2.4, which is 2006. The deadline that used to unwind with an exception
# is a flag instead; see Solver::expired.
if(NOT MSVC)
    target_compile_options(skatsolve_core PRIVATE
            -O2 -fno-rtti -fno-exceptions -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
endif()

# The checker and benchmark are a desktop tool and are not built for Android.
#
# Not only because nothing would run them there. The library itself is compiled
# against libc alone -- ANDROID_STL=none, which takes the C++ *headers* away as
# well as the runtime -- and this file uses <vector> and <string> quite happily
# because it is a program rather than a library. Building it for the NDK would
# fail for want of headers the library does not need and the test does.
if(NOT ANDROID)
    add_executable(skatsolve_test test/selftest.cpp)
    target_link_libraries(skatsolve_test PRIVATE skatsolve_core)

    enable_testing()
    add_test(NAME solver_matches_minimax COMMAND skatsolve_test check 400)
endif()

# ------------------------------------------------------------------------ JNI

# The Android NDK ships its own JNI headers and needs no find_package; a desktop
# or server build needs a JDK. Where neither is present the shared library is
# simply not built, and the static library and the test binary still are.
#
# SKAT_JNI_INCLUDE overrides the search, and exists for cross-compiling: a
# find_package on the build host would hand a Windows build the host's own
# headers, which is either wrong or accidentally right, and neither is a thing
# to leave to chance. See docs/native-solver.md for the mingw-w64 recipe.
set(SKAT_JNI_INCLUDE "" CACHE STRING
        "JNI include directories. Set this when cross-compiling; otherwise found.")
set(SKAT_BUILD_JNI OFF)
if(ANDROID)
    set(SKAT_BUILD_JNI ON)
    set(SKAT_JNI_INCLUDE_DIRS "")
elseif(SKAT_JNI_INCLUDE)
    set(SKAT_BUILD_JNI ON)
    set(SKAT_JNI_INCLUDE_DIRS ${SKAT_JNI_INCLUDE})
else()
    find_package(JNI QUIET)
    if(JNI_FOUND)
        set(SKAT_BUILD_JNI ON)
        set(SKAT_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
    endif()
endif()

if(SKAT_BUILD_JNI)
    add_library(skatsolve SHARED src/jni.cpp)
    target_link_libraries(skatsolve PRIVATE skatsolve_core)
    target_include_directories(skatsolve PRIVATE ${SKAT_JNI_INCLUDE_DIRS})
    if(MINGW)
        # Windows has no version script and no ELF visibility. What decides the
        # exports there is __declspec(dllexport) on the entry points, which is
        # what a Windows JDK's JNIEXPORT expands to -- and naming any symbol
        # that way switches off mingw's export-everything default, so the same
        # dozen entry points leave the library and nothing else does.
        #
        # Everything static. A DLL that needs libgcc_s_seh-1.dll beside it is a
        # DLL that will not load on a machine which has only a JRE, which is
        # every machine this is for.
        target_compile_options(skatsolve PRIVATE -O2 -fno-rtti -fno-exceptions -Wall -Wextra)
        if(CMAKE_CROSSCOMPILING)
            # Cross-building from Linux against the host JDK's headers. Its
            # jni_md.h is ABI-identical for x86-64 -- jint 32 bits, jlong 64,
            # jbyte one, and __stdcall is a no-op on x64 -- but it spells
            # JNIEXPORT as an ELF visibility attribute, which exports nothing
            # from a PE file. The header guards that define with #ifndef, so
            # this is the one thing that has to be said. jni.cpp static_asserts
            # the rest rather than trusting the paragraph above.
            target_compile_definitions(skatsolve PRIVATE
                    "JNIEXPORT=__declspec(dllexport)")
        endif()
        target_link_options(skatsolve PRIVATE
                -static -static-libgcc -static-libstdc++
                -Wl,--exclude-all-symbols
                -Wl,--no-undefined
                -Wl,--fatal-warnings)
    elseif(NOT MSVC AND NOT APPLE)
        target_compile_options(skatsolve PRIVATE -O2 -fno-rtti -fno-exceptions -Wall -Wextra)
        # Nothing but libc is linked, and nothing but the dozen JNI entry points
        # is exported. The second is not tidiness: a library loaded into a JVM
        # shares its symbol namespace, and every name it publishes is a name
        # that can collide with something else in the process.
        #
        # The three strictness flags are the NDK's own, applied here so that the
        # host build is a real proxy for the Android one. --no-undefined-version
        # is the reason the export map names JNI_On* rather than JNI_OnLoad: it
        # makes naming a symbol the library does not define an error, and
        # --fatal-warnings means that error is fatal. Finding that out here
        # costs a rebuild; finding it out on the NDK costs a round trip to
        # whoever has one.
        target_link_options(skatsolve PRIVATE
                -Wl,--exclude-libs,ALL
                -Wl,--no-undefined
                -Wl,--no-undefined-version
                -Wl,--fatal-warnings
                -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map)
        if(NOT ANDROID)
            # The last thing outside libc: one 64-bit popcount helper. The NDK
            # links its builtins statically already and would only warn that the
            # flag was unused.
            target_link_options(skatsolve PRIVATE -static-libgcc)
        endif()
    elseif(APPLE)
        # A macOS build would need its own export handling -- there is no
        # version script there -- and nothing ships for macOS, so it gets the
        # plain link rather than a half-configured one.
        target_compile_options(skatsolve PRIVATE -O2 -fno-rtti -fno-exceptions -Wall -Wextra)
    endif()
    set_target_properties(skatsolve PROPERTIES
            OUTPUT_NAME "skatsolve"
            CXX_VISIBILITY_PRESET hidden)
    if(WIN32)
        # skatsolve.dll, not libskatsolve.dll. CMake prefixes shared libraries
        # with "lib" under mingw and NativeSolver.fileName() asks for the name a
        # Windows toolchain would ordinarily produce -- which is also what a
        # System.loadLibrary("skatsolve") looks for.
        set_target_properties(skatsolve PROPERTIES PREFIX "")
    endif()
else()
    message(STATUS "No JNI headers found; building the core and the test only.")
endif()

# Compiles the library exactly as the NDK does with ANDROID_STL=none: no C++
# headers at all. It is the one thing about the Android build that can be
# checked without an NDK, and the failure it catches -- a c-prefixed include
# creeping back in -- is otherwise only visible to whoever has one.
if(NOT ANDROID AND NOT MSVC)
    add_library(skatsolve_freestanding OBJECT
            src/tables.cpp
            src/solver.cpp
            src/api.cpp)
    target_include_directories(skatsolve_freestanding PRIVATE include src)
    target_compile_definitions(skatsolve_freestanding PRIVATE SKAT_SOLVE_VERSION="check")
    target_compile_options(skatsolve_freestanding PRIVATE
            -nostdinc++ -O1 -fno-rtti -fno-exceptions -Wall -Wextra -Wno-unused-parameter)
endif()
