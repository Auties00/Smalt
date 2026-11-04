#!/usr/bin/env bash
# Regenerates the I/O behavior samples of the simple raster formats (image/src/test/resources/samples/{pnm,tga,qoi,
# hdr,exr}) with ffmpeg 7.1 from its synthetic testsrc2 source. As for make-image-samples.sh, the committed files are
# the reference their golden dumps are tied to.
#
#   bash tools/testdata/behavior/make-raster-samples.sh [output directory]
set -euo pipefail

out="${1:-image/src/test/resources/samples}"
mkdir -p "$out"/{pnm,tga,qoi,hdr,exr}

ff() {
    ffmpeg -hide_banner -loglevel error -y -bitexact "$@"
}

still="testsrc2=size=48x32:rate=1:duration=1"

ff -f lavfi -i "$still" -frames:v 1 -c:v ppm -pix_fmt rgb24 -f image2 "$out/pnm/rgb.ppm"
ff -f lavfi -i "$still" -frames:v 1 -c:v pgm -pix_fmt gray16be -f image2 "$out/pnm/gray16.pgm"
ff -f lavfi -i "$still" -frames:v 1 -c:v pam -pix_fmt rgba -f image2 "$out/pnm/rgba.pam"
ff -f lavfi -i "$still" -frames:v 1 -c:v pbm -pix_fmt monob -f image2 "$out/pnm/mono.pbm"
ff -f lavfi -i "$still" -frames:v 1 -c:v pfm -pix_fmt gbrpf32le -f image2 "$out/pnm/float.pfm"

ff -f lavfi -i "$still" -frames:v 1 -c:v targa -rle 1 -pix_fmt bgra -f image2 "$out/tga/rle-bgra.tga"
ff -f lavfi -i "$still" -frames:v 1 -c:v targa -rle 0 -pix_fmt bgr24 -f image2 "$out/tga/raw-bgr.tga"
ff -f lavfi -i "$still" -frames:v 1 -c:v targa -rle 1 -pix_fmt pal8 -f image2 "$out/tga/rle-pal8.tga"

ff -f lavfi -i "$still" -frames:v 1 -c:v qoi -pix_fmt rgba -f image2 "$out/qoi/rgba.qoi"

ff -f lavfi -i "$still" -frames:v 1 -c:v hdr -pix_fmt gbrpf32le -f image2 "$out/hdr/rle.hdr"

ff -f lavfi -i "$still" -frames:v 1 -c:v exr -compression zip1 -format half -pix_fmt gbrpf32le -f image2 \
    "$out/exr/zip-half.exr"
ff -f lavfi -i "$still" -frames:v 1 -c:v exr -compression rle -format float -pix_fmt gbrapf32le -f image2 \
    "$out/exr/rle-float.exr"
