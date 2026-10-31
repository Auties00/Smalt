#!/usr/bin/env bash
# Makes the encrypted seeds of core's DemuxerFuzzTest.mp4Encrypted and matroskaEncrypted targets (which
# DemuxerBehaviorTest.encryptedSeeds also runs through the I/O behavior harness) from small synthetic clips:
# ffmpeg's cenc encryption of a progressive MP4, and Shaka Packager's cenc, cbc1, cens and cbcs encryption of
# fragmented MP4 and WebM encryption, all with key ID 30313233343536373839303132333435 and key
# ebdd62f16814d27b68ef122afce4ae3c. ffmpeg and Shaka Packager (BSD 3-Clause; the seeds were made with the v3.9.3
# release binary, packager-win-x64.exe) run as black boxes.
#   bash tools/testdata/cenc/make-seeds.sh <Shaka Packager executable> <output directory>
set -euo pipefail
PACKAGER="$1"
OUT="$2"
mkdir -p "$OUT"
cd "$OUT"
KEY_ID=30313233343536373839303132333435
KEY=ebdd62f16814d27b68ef122afce4ae3c
ffmpeg -v error -y -f lavfi -i testsrc2=size=64x48:rate=15:duration=0.6 \
  -f lavfi -i sine=frequency=440:sample_rate=16000:duration=0.6 \
  -c:v libx264 -g 4 -bf 1 -c:a aac -b:a 24k -ac 1 clear.mp4
ffmpeg -v error -y -i clear.mp4 -c copy -encryption_scheme cenc-aes-ctr -encryption_key "$KEY" \
  -encryption_kid "$KEY_ID" valid-cenc-progressive.mp4
KEYS="label=:key_id=$KEY_ID:key=$KEY"
for scheme in cenc cbc1 cens cbcs; do
  "$PACKAGER" "in=clear.mp4,stream=video,output=valid-$scheme-video.mp4" --enable_raw_key_encryption \
    --keys "$KEYS" --protection_scheme "$scheme" --clear_lead 0 --segment_duration 0.3
done
"$PACKAGER" in=clear.mp4,stream=audio,output=valid-cbcs-audio.mp4 --enable_raw_key_encryption --keys "$KEYS" \
  --protection_scheme cbcs --clear_lead 0
ffmpeg -v error -y -f lavfi -i testsrc2=size=64x48:rate=15:duration=0.6 -c:v libvpx-vp9 -b:v 50k -an \
  -f webm -dash 1 clearv.webm
ffmpeg -v error -y -f lavfi -i sine=frequency=440:sample_rate=48000:duration=0.6 -c:a libopus -b:a 16k -ac 1 -vn \
  -f webm -dash 1 cleara.webm
"$PACKAGER" in=clearv.webm,stream=video,output=valid-webm-video.webm \
  in=cleara.webm,stream=audio,output=valid-webm-audio.webm --enable_raw_key_encryption --keys "$KEYS" --clear_lead 0
sha256sum valid-*
