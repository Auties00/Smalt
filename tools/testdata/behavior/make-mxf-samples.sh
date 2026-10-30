#!/usr/bin/env bash
# Regenerates the MXF samples of the I/O behavior harness (core/src/test/resources/samples/mxf) with ffmpeg 7.1
# from its synthetic lavfi sources: OP1a files of every essence MxfDemuxer maps (VC-3, ProRes, AVC long GOP with
# B-frames, AVC-Intra class 50 with in-band parameter sets, MPEG-2 long GOP with B-frames, AES3 PCM) and OP-Atom
# files of one picture and one sound track. As for make-samples.sh, the golden dumps are tied to these bytes.
# When BBC bmx's raw2bmx (1.6, BSD-3-Clause, github.com/bbc/bmx releases) is on PATH, it also wraps ffmpeg's raw
# essence in the files of another writer: a headerless AVC-Intra class 50 720p50 OP1a file (the parameter sets
# stripped, as RP 2027 allows), DNxHD 1080p 36 Mb/s (CID 1253), ProRes 422 with its stereo PCM as two mono tracks,
# and a clip wrapped PCM file; --regtest makes its identifiers and dates fixed. It also writes two small bmx files
# to the MXF seeds of DemuxerFuzzTest (the others come from FuzzSeedWriter).
#
#   bash tools/testdata/behavior/make-mxf-samples.sh [output directory] [fuzz seed directory]
set -euo pipefail

out="${1:-core/src/test/resources/samples}/mxf"
seeds="${2:-core/src/test/resources/com/github/auties00/smalt/fuzz/DemuxerFuzzTestInputs}/mxf"
mkdir -p "$out"

ff() {
    ffmpeg -hide_banner -loglevel error -y -bitexact "$@"
}

tone="sine=frequency=440:sample_rate=48000"
x264=(-x264-params threads=1:sliced-threads=0)

ff -f lavfi -i "testsrc2=size=256x128:rate=25:duration=0.24" -f lavfi -i "$tone:duration=0.24" \
    -c:v dnxhd -profile:v dnxhr_lb -pix_fmt yuv422p -c:a pcm_s24le -ac 2 -f mxf "$out/op1a-dnxhr.mxf"
ff -f lavfi -i "testsrc2=size=64x48:rate=25:duration=0.24" -f lavfi -i "$tone:duration=0.24" \
    -c:v prores_ks -profile:v 2 -pix_fmt yuv422p10le -c:a pcm_s16le -ac 1 -f mxf "$out/op1a-prores.mxf"
ff -f lavfi -i "testsrc2=size=160x120:rate=25:duration=0.8" -f lavfi -i "$tone:duration=0.8" \
    -c:v libx264 -preset ultrafast -crf 34 -g 10 -bf 2 -pix_fmt yuv420p "${x264[@]}" -c:a pcm_s16le -ac 1 \
    -f mxf "$out/op1a-h264.mxf"
ff -f lavfi -i "testsrc2=size=160x128:rate=25:duration=0.8" -f lavfi -i "$tone:duration=0.8" \
    -c:v mpeg2video -q:v 10 -g 6 -bf 2 -c:a pcm_s16le -ac 1 -f mxf "$out/op1a-mpeg2.mxf"
ff -f lavfi -i "testsrc2=size=960x720:rate=50:duration=0.02" -f lavfi -i "$tone:duration=0.02" \
    -c:v libx264 -pix_fmt yuv420p10le -x264-params avcintra-class=50:threads=1:sliced-threads=0 \
    -c:a pcm_s24le -ac 1 -f mxf "$out/op1a-avci50.mxf"
ff -f lavfi -i "testsrc2=size=256x128:rate=25:duration=0.12" -c:v dnxhd -profile:v dnxhr_lb -pix_fmt yuv422p \
    -f mxf_opatom "$out/opatom-dnxhr.mxf"
ff -f lavfi -i "$tone:duration=0.24" -c:a pcm_s24le -ac 1 -f mxf_opatom -mxf_audio_edit_rate 25 \
    "$out/opatom-pcm.mxf"

if command -v raw2bmx > /dev/null; then
    raw="$(mktemp -d)"
    trap 'rm -rf "$raw"' EXIT
    ff -f lavfi -i "testsrc2=size=960x720:rate=50:duration=0.02" -c:v libx264 -pix_fmt yuv420p10le \
        -x264-params avcintra-class=50:threads=1:sliced-threads=0 -f h264 "$raw/avci50.h264"
    ff -f lavfi -i "testsrc2=size=1920x1080:rate=25:duration=0.04" -c:v dnxhd -b:v 36M -pix_fmt yuv422p \
        -f rawvideo "$raw/dnxhd.vc3"
    ff -f lavfi -i "testsrc2=size=64x48:rate=25:duration=0.2" -c:v prores_ks -profile:v 2 -pix_fmt yuv422p10le \
        -f rawvideo "$raw/prores.raw"
    ff -f lavfi -i "$tone:duration=0.02" -c:a pcm_s24le -ac 1 "$raw/tone24m.wav"
    ff -f lavfi -i "$tone:duration=0.04" -c:a pcm_s16le -ac 1 "$raw/tone16.wav"
    ff -f lavfi -i "$tone:duration=0.2" -c:a pcm_s24le -ac 2 "$raw/tone24.wav"
    bmx() {
        raw2bmx --regtest "$@" > /dev/null
    }
    bmx -t op1a -f 50 -o "$out/bmx-op1a-avci50-headerless.mxf" --force-no-avci-head --avci50_720p "$raw/avci50.h264" \
        --wave "$raw/tone24m.wav"
    bmx -t op1a -f 25 -o "$out/bmx-op1a-vc3.mxf" --vc3_1080p_1253 "$raw/dnxhd.vc3" --wave "$raw/tone16.wav"
    bmx -t op1a -f 25 -o "$out/bmx-op1a-prores.mxf" --rdd36_422 "$raw/prores.raw" --wave "$raw/tone24.wav"
    bmx -t op1a -f 25 --clip-wrap -o "$out/bmx-op1a-clip-pcm.mxf" --wave "$raw/tone24.wav"
    mkdir -p "$seeds"
    ff -f lavfi -i "testsrc2=size=32x32:rate=25:duration=0.12" -c:v prores_ks -profile:v 0 -pix_fmt yuv422p10le \
        -f rawvideo "$raw/seed.raw"
    ff -f lavfi -i "$tone:duration=0.12" -c:a pcm_s16le -ac 1 "$raw/seed.wav"
    bmx -t op1a -f 25 -o "$seeds/valid-bmx-op1a-prores.mxf" --rdd36_422_proxy "$raw/seed.raw" --wave "$raw/seed.wav"
    bmx -t op1a -f 25 --clip-wrap -o "$seeds/valid-bmx-clip-pcm.mxf" --wave "$raw/seed.wav"
fi
