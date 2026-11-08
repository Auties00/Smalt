#!/bin/bash
# Builds smalt-lcmsproof against a Little CMS source tree (checked with the 2.17 release tarball, MIT) and runs it.
# Usage: bash tools/testdata/icc-proofing/build.sh <lcms2 source directory> <smalt profiles directory> <output directory>
# From an MSYS2 UCRT64 shell on Windows, or any shell with gcc on Linux and macOS. The profiles of core's
# lcms/profiles.tsv must be in the conformance cache (~/.smalt/cache/conformance, or $SMALT_CONFORMANCE_CACHE), which
# any run of the color tests fills. The Smalt profiles come from the test class ProofingFixtures, whose main method
# writes them to the directory it is given; their SHA-256 digests go to smalt-profiles.tsv, which the test checks so
# that the Little CMS outputs always describe the profiles Smalt writes. Copy <output directory>/data, without the
# lcms-*.icc profiles, to core/src/test/resources/com/github/auties00/smalt/image/color/proofing and check it against
# tools/testdata/icc-proofing/data.sha256.
set -euo pipefail
lcms="$1"
smalt="$2"
out="$3"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cache="${SMALT_CONFORMANCE_CACHE:-$HOME/.smalt/cache/conformance}"
advanced="$root/core/src/test/resources/com/github/auties00/smalt/image/color/advanced"
mkdir -p "$out/data"
gcc -O2 -ffp-contract=off -I"$lcms/include" -I"$lcms/src" -o "$out/smalt-lcmsproof" "$here/smalt-lcmsproof.c" \
    "$lcms"/src/*.c -lm
"$out/smalt-lcmsproof" "$cache" "$advanced" "$smalt" "$out/data"
(cd "$smalt" && sha256sum smalt-*.icc | sed 's/ \*\?/\t/' | awk -F'\t' '{print $2 "\t" $1}') > "$out/data/smalt-profiles.tsv"
