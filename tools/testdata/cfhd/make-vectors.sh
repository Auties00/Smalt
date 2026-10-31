#!/usr/bin/env bash
# Regenerates the CineForm test vectors of smalt-video (video/cfhd/sdk/ and its .sha256) with GoPro's cineform-sdk
# (Apache License 2.0 or MIT, https://github.com/gopro/cineform-sdk, master) built from source, as a black-box
# encoder and reference decoder: make-vectors.cpp encodes four frames of a synthetic pattern per vector (4:2:2 intra,
# 4:2:2 groups of two frames, interlaced 4:2:2, RGB 4:4:4 and RGBA 4:4:4:4) and stores each sample with the SDK's
# decode of it. ffmpeg's CineForm encoder writes intra frames only, so the group transform is tested on these.
# Usage: make-vectors.sh <cineform-sdk checkout> <build directory> <smalt checkout>
set -euo pipefail
SDK="$1"
BUILD="$2"
ROOT="$3"
OUT="$ROOT/video/src/test/resources/com/github/auties00/smalt/video/cfhd/sdk"
cmake -G Ninja -S "$SDK" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-w -fpermissive" \
    -DCMAKE_CXX_FLAGS="-w -fpermissive"
ninja -C "$BUILD" CFHDCodecStatic
extra=()
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) extra=(-lole32 -luuid) ;;
    *) extra=(-luuid -lpthread) ;;
esac
g++ -O1 -w -fopenmp -I"$SDK/Common" -o "$BUILD/make-vectors" "$(dirname "$0")/make-vectors.cpp" \
    "$BUILD/libCFHDCodecStatic.a" "${extra[@]}"
rm -rf "$OUT"
mkdir -p "$OUT"
"$BUILD/make-vectors" "$OUT"
(cd "$OUT" && sha256sum -- *) > "$OUT.sha256"
