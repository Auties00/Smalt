#!/bin/bash
# Builds smalt-lcmsadvanced against a Little CMS source tree (checked with the 2.17 release tarball, MIT) and runs it.
# Usage: bash tools/testdata/icc-advanced/build.sh <lcms2 source directory> <output directory>
# From an MSYS2 UCRT64 shell on Windows, or any shell with gcc on Linux and macOS. The outputs go to
# <output directory>/data; copy them to core/src/test/resources/com/github/auties00/smalt/image/color/advanced and
# check them against tools/testdata/icc-advanced/profiles.sha256.
set -euo pipefail
lcms="$1"
out="$2"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out/data"
gcc -O2 -ffp-contract=off -I"$lcms/include" -o "$out/smalt-lcmsadvanced" "$here/smalt-lcmsadvanced.c" "$lcms"/src/*.c -lm
"$out/smalt-lcmsadvanced" "$out/data"
