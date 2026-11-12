#!/usr/bin/env bash
# Regenerates the CineForm test vectors of smalt-video (video/cfhd/sdk/ and its .sha256) with GoPro's cineform-sdk
# (Apache License 2.0 or MIT, https://github.com/gopro/cineform-sdk, master) built from source, as a black-box
# encoder and reference decoder: make-vectors.cpp encodes four frames of a synthetic pattern per vector (4:2:2 intra,
# 4:2:2 groups of two frames, interlaced 4:2:2, RGB 4:4:4 and RGBA 4:4:4:4) and stores each sample with the SDK's
# decode of it. ffmpeg's CineForm encoder writes intra frames only, so the group transform is tested on these.
# The CineForm RAW (Bayer) vectors hold one or two frames of a synthetic mosaic in each Bayer phase, under each encode
# curve of the ECRV metadata and with a pre-applied curve (PCRV), each sample with the SDK's 16-bit Bayer (BYR4)
# and, for most, 16-bit RGB (RG48) decodes. The two bayer-canon vectors code crops of the CC0 raw.pixls.us sample
# "Adobe DNG Converter - Canon EOS 5D Mark III - 16bit 16bit uncompressed (3:2).DNG" (tools/testdata/vectors.tsv,
# SHA-256 95dc76a2f255e623acaa5149b320389f7da7c995da890c3e0d511c0ff681b2e1), its photosites shifted left by 2 bits:
# 256x128 from (3000, 600), RGGB, and 160x96 from (3001, 601), BGGR. Without that file they are skipped.
# Every sample carries the encoding date and time in its metadata, so a regeneration changes every vector.
# curve-tables.c writes the SDK's linearization table of each encode curve; only their digests are committed
# (video/cfhd/internal/curves.sha256), and the script refreshes them too.
# Usage: make-vectors.sh <cineform-sdk checkout> <build directory> <smalt checkout> [<Canon 5D Mark III DNG>]
set -euo pipefail
SDK="$1"
BUILD="$2"
ROOT="$3"
DNG="${4:-}"
HERE="$(dirname "$0")"
OUT="$ROOT/video/src/test/resources/com/github/auties00/smalt/video/cfhd/sdk"
cmake -G Ninja -S "$SDK" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-w -fpermissive" \
    -DCMAKE_CXX_FLAGS="-w -fpermissive"
ninja -C "$BUILD" CFHDCodecStatic
extra=()
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) extra=(-lole32 -luuid) ;;
    *) extra=(-luuid -lpthread) ;;
esac
g++ -O1 -w -fopenmp -I"$SDK/Common" -o "$BUILD/make-vectors" "$HERE/make-vectors.cpp" \
    "$BUILD/libCFHDCodecStatic.a" "${extra[@]}"
crops=()
if [ -n "$DNG" ]; then
    python -I "$HERE/dngcrop.py" "$DNG" 3000 600 256 128 "$BUILD/canon-rggb.u16" 2
    python -I "$HERE/dngcrop.py" "$DNG" 3001 601 160 96 "$BUILD/canon-bggr.u16" 2
    crops=("$BUILD/canon-rggb.u16" "$BUILD/canon-bggr.u16")
fi
rm -rf "$OUT"
mkdir -p "$OUT"
"$BUILD/make-vectors" "$OUT" ${crops[@]+"${crops[@]}"}
(cd "$OUT" && sha256sum -- *) > "$OUT.sha256"
gcc -O1 -w -I"$SDK/Common" -o "$BUILD/curve-tables" "$HERE/curve-tables.c" -lm
rm -rf "$BUILD/curves"
mkdir -p "$BUILD/curves"
"$BUILD/curve-tables" "$BUILD/curves"
PINS="$ROOT/video/src/test/resources/com/github/auties00/smalt/video/cfhd/internal/curves.sha256"
(cd "$BUILD/curves" && sha256sum -- *) > "$PINS"
