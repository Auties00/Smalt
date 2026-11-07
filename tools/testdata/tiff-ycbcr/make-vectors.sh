#!/bin/bash
# Writes the YCbCr data-unit vectors of smalt-image's TiffYCbCrTest with libtiff's own predictor and codecs.
# Usage: bash tools/testdata/tiff-ycbcr/make-vectors.sh <checkout>
# From an MSYS2 UCRT64 shell on Windows (gcc and libtiff), or any shell with gcc and libtiff on Linux and macOS;
# checked with libtiff 4.7.1, for which the output is deterministic; tiff/ycbcr.sha256 next to them pins it.
set -euo pipefail
checkout="$1"
here="$(cd "$(dirname "$0")" && pwd)"
out="$checkout/image/src/test/resources/com/github/auties00/smalt/image/tiff/ycbcr"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
mkdir -p "$out"
gcc -O2 -o "$build/smalt-ycbcrvectors" "$here/smalt-ycbcrvectors.c" -ltiff
"$build/smalt-ycbcrvectors" "$out"
(cd "$out" && sha256sum ./*.tif | sed 's#\./##') > "$out/../ycbcr.sha256"
cat "$out/../ycbcr.sha256"
