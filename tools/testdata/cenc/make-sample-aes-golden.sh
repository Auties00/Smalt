#!/usr/bin/env bash
# Makes the input and golden file of core's SampleAesGoldenTest: a 9-frame lossless H.264 clip cut into small
# slices (lossless coding puts emulation prevention bytes in the slices, and the slice sizes reach the pattern's
# corner cases: slices of at most 48 bytes, a last block of exactly 16 bytes, a partial last block) with AAC, AC-3
# and E-AC-3 tracks, encrypted to HLS SAMPLE-AES transport streams by Shaka Packager (BSD 3-Clause; the golden
# file was made with the v3.9.3 release binary, packager-win-x64.exe) run as a black box, and listed by
# sample-aes-units.py. ffmpeg's encoders are not reproducible across versions, so the input is kept with the
# golden file rather than remade.
#   bash tools/testdata/cenc/make-sample-aes-golden.sh <Shaka Packager executable> <output directory>
set -euo pipefail
PACKAGER="$1"
OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
cd "$OUT"
ffmpeg -v error -y -f lavfi -i testsrc2=size=128x72:rate=30:duration=0.3 \
  -f lavfi -i sine=frequency=440:sample_rate=48000:duration=0.3 -map 0 -map 1 -map 1 -map 1 \
  -c:v libx264 -g 6 -bf 2 -qp 0 -x264-params slice-max-size=120:log-level=error \
  -c:a:0 aac -b:a:0 64k -c:a:1 ac3 -b:a:1 96k -c:a:2 eac3 -b:a:2 96k -ac 2 sample-aes-input.mp4
"$PACKAGER" in=sample-aes-input.mp4,stream=0,output=video.ts in=sample-aes-input.mp4,stream=1,output=aac.ts \
  in=sample-aes-input.mp4,stream=2,output=ac3.ts in=sample-aes-input.mp4,stream=3,output=eac3.ts \
  --protection_scheme cbcs --enable_raw_key_encryption \
  --keys label=:key_id=0c1d2e3f405162738495a6b7c8d9eafb:key=8899aabbccddeeff0011223344556677 \
  --iv f0e1d2c3b4a5968778695a4b3c2d1e0f --clear_lead 0
python -I "$HERE/sample-aes-units.py" video.ts aac.ts ac3.ts eac3.ts > sample-aes-shaka.txt
sha256sum sample-aes-input.mp4 sample-aes-shaka.txt
