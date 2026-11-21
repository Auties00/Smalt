#!/usr/bin/env bash
# Regenerates the lossless Motion-JPEG test vectors of smalt-image (jpeg/mjpeg/ and jpeg/mjpeg-md5.csv) with
# ffmpeg 7.1 as a black-box encoder. Each file holds two frames of ffmpeg's testsrc2 pattern (the bgra one with
# an alpha ramp) coded by ffmpeg's lossless JPEG encoder (-c:v ljpeg) with one predictor; mjpeg-md5.csv holds the
# MD5 of the same frames converted to the file's pixel format by ffmpeg and written as raw video, which is what a
# lossless decode must reproduce.
# Usage: make-vectors.sh <smalt checkout>
set -euo pipefail
ROOT="$1"
OUT="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/mjpeg"
CSV="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/mjpeg-md5.csv"
rm -rf "$OUT"
mkdir -p "$OUT"
echo "# file;pixel format;width;height;frames;md5 of the source frames as ffmpeg rawvideo in that pixel format" > "$CSV"
echo "# made by tools/testdata/mjpeg/make-vectors.sh with ffmpeg 7.1 (-c:v ljpeg)" >> "$CSV"
make() {
    local file="$1" format="$2" predictor="$3" size="$4" filter="$5"
    # testsrc2 renders even sizes only, so odd sizes are cropped from a larger picture
    local input=(-f lavfi -i "testsrc2=size=64x48:rate=5" -frames:v 2 -vf "format=rgb24,crop=${size%x*}:${size#*x}:3:5,$filter")
    ffmpeg -hide_banner -loglevel error -y "${input[@]}" -pix_fmt "$format" -c:v ljpeg -pred "$predictor" \
        -fflags +bitexact -flags:v +bitexact "$OUT/$file"
    local md5
    md5=$(ffmpeg -hide_banner -loglevel error "${input[@]}" -pix_fmt "$format" -f rawvideo - | md5sum | cut -d' ' -f1)
    echo "$file;$format;${size%x*};${size#*x};2;$md5" >> "$CSV"
}
make ljpeg-yuvj420p-pred1.avi yuvj420p 1 41x29 null
make ljpeg-yuvj422p-pred2.mkv yuvj422p 2 40x30 null
make ljpeg-yuvj444p-pred3.avi yuvj444p 3 33x17 null
make ljpeg-bgr24-pred1.avi bgr24 1 40x30 null
make ljpeg-bgra-pred2.mkv bgra 2 40x30 "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='X*6+Y'"
(cd "$OUT" && sha256sum -- *) > "$OUT.sha256"
