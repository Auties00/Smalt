#!/usr/bin/env bash
# Regenerates the I/O behavior samples of the intermediate and image video codecs (PNG, TIFF and GIF frames,
# QuickTime Animation, CineForm and HAP in QuickTime, AVI and Matroska) under core/src/test/resources/samples with
# ffmpeg 7.1 from its synthetic lavfi sources. ffmpeg writes HAP only when built with libsnappy (the gyan.dev full
# build is), so FFMPEG may name such a build. The committed files are the reference: their golden dumps are tied to
# these exact bytes, so rerunning this script with another ffmpeg build requires regenerating the dumps
# (-Dsmalt.test.updateDumps=true) and the pins (pin-samples.py).
#
#   FFMPEG=/path/to/ffmpeg bash tools/testdata/behavior/make-intermediate-samples.sh [output directory]
set -euo pipefail

out="${1:-core/src/test/resources/samples}"
mkdir -p "$out"/{mp4,avi,matroska}

ff() {
    "${FFMPEG:-ffmpeg}" -hide_banner -loglevel error -y -bitexact "$@"
}

video="testsrc2=size=64x48:rate=10:duration=0.5"
# a moving gradient with alpha, so that alpha-capable codecs code a varying alpha plane
alpha="$video,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='clip(4*X+30*sin(N+Y/6),0,255)'"

ff -f lavfi -i "$alpha" -c:v png -pix_fmt rgba "$out/mp4/png-rgba.mov"
ff -f lavfi -i "$video" -c:v tiff -pix_fmt rgb24 "$out/mp4/tiff.mov"
ff -f lavfi -i "$video" -c:v gif -pix_fmt rgb8 "$out/mp4/gif.mov"
ff -f lavfi -i "$alpha" -c:v qtrle -pix_fmt argb -g 3 "$out/mp4/qtrle-argb.mov"
ff -f lavfi -i "$video" -c:v cfhd -pix_fmt yuv422p10le -quality medium "$out/mp4/cfhd.mov"
ff -f lavfi -i "$alpha" -c:v hap -format hap_alpha -chunks 2 "$out/mp4/hap-alpha.mov"

ff -f lavfi -i "$video" -c:v png -pix_fmt rgb24 "$out/avi/png.avi"
ff -f lavfi -i "$video" -c:v cfhd -pix_fmt gbrp12le -quality high "$out/avi/cfhd.avi"
ff -f lavfi -i "$video" -c:v hap -format hap_q "$out/avi/hap-q.avi"

ff -f lavfi -i "$video" -c:v png -pix_fmt rgb24 "$out/matroska/png.mkv"
ff -f lavfi -i "$video" -c:v cfhd -pix_fmt yuv422p10le -quality film1 "$out/matroska/cfhd.mkv"
ff -f lavfi -i "$video" -c:v hap -format hap "$out/matroska/hap.mkv"
