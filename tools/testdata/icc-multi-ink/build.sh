#!/bin/bash
# Builds smalt-lcmsinks against a Little CMS source tree (checked with the 2.17 release tarball, MIT) and runs it.
# Usage: bash tools/testdata/icc-multi-ink/build.sh <lcms2 source directory> <output directory>
# From an MSYS2 UCRT64 shell on Windows, or any shell with gcc on Linux and macOS. The outputs go to
# <output directory>/data; copy them to core/src/test/resources/com/github/auties00/smalt/image/color/inks and
# check them against tools/testdata/icc-multi-ink/data.sha256.
set -euo pipefail
lcms="$1"
out="$2"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out/data"
gcc -O2 -ffp-contract=off -I"$lcms/include" -o "$out/smalt-lcmsinks" "$here/smalt-lcmsinks.c" "$lcms"/src/*.c -lm
"$out/smalt-lcmsinks" "$out/data"
