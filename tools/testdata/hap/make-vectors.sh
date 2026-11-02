#!/usr/bin/env bash
# Regenerates the HAP test vectors of smalt-image (image/hap/ffmpeg/ and its .sha256) with ffmpeg 7.1 as a
# black-box encoder. ffmpeg's HAP encoder needs libsnappy, which the CI oracle build leaves out, so the files are
# kept with the tests; ffmpeg's HAP decoder has its own Snappy and decodes them on every runner. Each file holds
# three 132x76 frames (the encoder takes multiples of 4) of ffmpeg's testsrc2 pattern with the gradients pattern as
# alpha, coded in one HAP format with one second-stage compressor and chunk count.
# Usage: FFMPEG=<ffmpeg with --enable-libsnappy> make-vectors.sh <smalt checkout>
set -euo pipefail
ROOT="$1"
FFMPEG="${FFMPEG:-ffmpeg}"
OUT="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/hap/ffmpeg"
rm -rf "$OUT"
mkdir -p "$OUT"
make() {
    local format="$1" compressor="$2" chunks="$3" container="$4"
    "$FFMPEG" -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=136x76:rate=25" \
        -f lavfi -i "gradients=size=136x76:rate=25:seed=1" \
        -filter_complex "[0]format=rgba,crop=132:76:2:0[c];[1]format=gray,crop=132:76:1:0[a];[c][a]alphamerge" -frames:v 3 -c:v hap -format "$format" \
        -compressor "$compressor" -chunks "$chunks" -fflags +bitexact -flags:v +bitexact \
        "$OUT/$format-$compressor-$chunks.$container"
}
make hap snappy 1 mov
make hap none 1 mov
make hap snappy 4 mov
make hap none 3 avi
make hap_alpha snappy 1 mov
make hap_alpha snappy 8 mkv
make hap_q snappy 1 mov
make hap_q none 2 mov
make hap_q snappy 5 avi
(cd "$OUT" && sha256sum -- *) > "$OUT.sha256"
