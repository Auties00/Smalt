#!/usr/bin/env bash
# Makes the golden file of core's CencVideoGoldenTest: VP9 and AV1 conformance vectors (pinned in
# tools/testdata/vectors.tsv; libvpx and libaom test vectors, BSD 2-Clause and BSD 3-Clause) remuxed to MP4 by
# ffmpeg (-c copy) and encrypted with Common Encryption by Shaka Packager (BSD 3-Clause; the golden file was made
# with the v3.9.3 release binary, packager-win-x64.exe) run as a black box, once clear, once with cenc and once
# with cbcs, then listed by cenc-samples.py. Shaka Packager and Smalt disagree on purpose where Shaka Packager
# reads a frame header against the specification (VP9 frames that take their size from a reference of another
# size, AV1 superres); the test names those vectors.
#   bash tools/testdata/cenc/make-video-golden.sh <Shaka Packager executable> <vector directory> <output directory>
set -euo pipefail
PACKAGER="$1"
VECTORS="$2"
OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
cd "$OUT"
KEYS=label=:key_id=0c1d2e3f405162738495a6b7c8d9eafb:key=8899aabbccddeeff0011223344556677
: > cenc-video-shaka.txt
for name in vp90-2-16-intra-only.webm vp90-2-17-show-existing-frame.webm vp90-2-15-segkey.webm \
    vp90-2-14-resize-fp-tiles-1-2-4-8-16.webm vp90-2-20-big_superframe-01.webm vp90-2-22-svc_1280x720_3.ivf \
    vp90-2-21-resize_inter_320x180_5_1-2.webm vp91-2-04-yuv444.webm \
    av1-av1-1-b8-01-size-16x18.ivf av1-av1-1-b8-01-size-66x66.ivf av1-av1-1-b8-02-allintra.ivf \
    av1-av1-1-b8-03-sizeup.mkv av1-av1-1-b8-03-sizedown.mkv av1-av1-1-b8-04-cdfupdate.ivf av1-av1-1-b8-06-mfmv.ivf \
    av1-av1-1-b8-16-intra_only-intrabc-extreme-dv.ivf av1-av1-1-b8-22-svc-L1T2.ivf av1-av1-1-b8-22-svc-L2T2-2.ivf \
    av1-av1-1-b8-23-film_grain-50.ivf av1-av1-1-b8-24-monochrome.ivf av1-av1-1-b10-00-quantizer-00.ivf \
    av1-av1-1-b10-24-monochrome.ivf; do
  ffmpeg -v error -y -i "$VECTORS/$name" -map 0:v:0 -c copy "$name.mp4"
  if ! "$PACKAGER" "in=$name.mp4,stream=video,output=$name.clear.mp4" --segment_duration 1 > /dev/null 2>&1; then
    echo "# $name: Shaka Packager cannot read it" >> cenc-video-shaka.txt
    continue
  fi
  "$PACKAGER" "in=$name.mp4,stream=video,output=$name.cenc.mp4" --enable_raw_key_encryption --keys "$KEYS" \
    --protection_scheme cenc --iv f0e1d2c3b4a59687 --clear_lead 0 --segment_duration 1 > /dev/null 2>&1
  "$PACKAGER" "in=$name.mp4,stream=video,output=$name.cbcs.mp4" --enable_raw_key_encryption --keys "$KEYS" \
    --protection_scheme cbcs --iv f0e1d2c3b4a5968778695a4b3c2d1e0f --clear_lead 0 --segment_duration 1 \
    > /dev/null 2>&1
  for kind in clear cenc cbcs; do
    python -I "$HERE/cenc-samples.py" "$name:$kind" "$name.$kind.mp4" >> cenc-video-shaka.txt
  done
done
sha256sum cenc-video-shaka.txt
