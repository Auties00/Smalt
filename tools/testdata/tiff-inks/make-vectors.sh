#!/bin/bash
# Writes the multi-ink TIFF vectors of smalt-image's TiffInksTest and their Little CMS references.
# Usage: bash tools/testdata/tiff-inks/make-vectors.sh <lcms2 source directory> <checkout>
# From an MSYS2 UCRT64 shell on Windows (gcc and libtiff), or any shell with gcc and libtiff on Linux and macOS.
# Little CMS 2.16 or later (MIT; checked with the 2.17 release tarball) and libtiff 4.7.1. The output is
# deterministic for those versions; tiff/inks.sha256 next to them pins it.
set -euo pipefail
lcms="$1"
checkout="$2"
here="$(cd "$(dirname "$0")" && pwd)"
out="$checkout/image/src/test/resources/com/github/auties00/smalt/image/tiff/inks"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
mkdir -p "$out"
gcc -O2 -I"$lcms/include" -o "$build/smalt-inkvectors" "$here/smalt-inkvectors.c" "$lcms"/src/*.c -ltiff -lm
# libtiff warns that NumberOfInks differs from SamplesPerPixel when an alpha sample follows the inks
"$build/smalt-inkvectors" "$out" 2> >(grep -v -e NumberOfInks -e "is different from" -e _TIFFVSetField >&2)
(cd "$out" && sha256sum ink5.icc ink6.icc inks5-8.tif inks6-16a.tif inks5-8.ref.rgb inks6-16a.ref.rgba) \
    > "$out/../inks.sha256"
cat "$out/../inks.sha256"
