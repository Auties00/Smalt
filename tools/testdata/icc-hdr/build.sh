#!/bin/bash
# Builds smalt-lcmsdump against a Little CMS source tree (2.16 or later; checked with 2.17).
# Usage: bash tools/testdata/icc-hdr/build.sh <lcms2 source directory> <output directory>
# From an MSYS2 UCRT64 shell on Windows, or any shell with gcc on Linux and macOS.
set -euo pipefail
lcms="$1"
out="$2"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out"
gcc -O2 -I"$lcms/include" -o "$out/smalt-lcmsdump" "$here/smalt-lcmsdump.c" "$lcms"/src/*.c -lm
