#!/bin/bash
# Builds smalt-pngdump against libpng (1.6.46 or later, for cICP, mDCV and cLLI) and zlib.
# Usage: tools/testdata/png-oracle/build.sh <output directory>
# On Windows, MSYS2's UCRT64 environment provides gcc, libpng and zlib (pacman -S mingw-w64-ucrt-x86_64-libpng).
set -e
out="${1:?usage: build.sh <output directory>}"
mkdir -p "$out"
here="$(cd "$(dirname "$0")" && pwd)"
gcc -O2 -Wall -o "$out/smalt-pngdump" "$here/smalt-pngdump.c" -static $(pkg-config --cflags --static --libs libpng16)
"$out/smalt-pngdump" 2>/dev/null || test $? -eq 2
echo "built $out/smalt-pngdump"
