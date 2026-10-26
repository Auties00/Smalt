#!/usr/bin/env bash
# Regenerates the container samples of the I/O behavior harness (core/src/test/resources/samples) with
# ffmpeg 7.1 from its synthetic lavfi sources. The committed files are the reference: their golden dumps
# (core/src/test/resources/dumps) are tied to these exact bytes, so rerunning this script with another
# ffmpeg build produces other bytes and requires regenerating the dumps (-Dsmalt.test.updateDumps=true).
#
#   bash tools/testdata/behavior/make-samples.sh [output directory]
set -euo pipefail

out="${1:-core/src/test/resources/samples}"
mkdir -p "$out"/{mp4,matroska,ogg,ts,avi,wav,aiff,mp3,adts,amr,ac3,ivf,flac,subtitle,elementary,y4m,rawvideo}

ff() {
    ffmpeg -hide_banner -loglevel error -y -bitexact "$@"
}

video="testsrc2=size=160x120:rate=15:duration=2"
tone="sine=frequency=440:sample_rate=48000:duration=2"
noise="anoisesrc=color=pink:amplitude=0.3:sample_rate=44100:seed=7:duration=2"
short="anoisesrc=color=pink:amplitude=0.3:sample_rate=22050:seed=7:duration=0.6"
x264=(-c:v libx264 -preset ultrafast -crf 32 -g 10 -bf 2 -pix_fmt yuv420p -x264-params threads=1:sliced-threads=0)

ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a aac -b:a 48k -ac 1 -movflags +faststart -shortest \
    "$out/mp4/faststart.mp4"
ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a aac -b:a 48k -ac 1 -shortest "$out/mp4/moov-last.mp4"
ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a aac -b:a 48k -ac 1 \
    -movflags frag_keyframe+empty_moov+default_base_moof -shortest "$out/mp4/fragmented.mp4"
ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a pcm_s16le -ac 1 -ar 8000 -f mov -shortest \
    "$out/mp4/quicktime.mov"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libx264 -preset fast -crf 30 -g 10 -bf 2 \
    -pix_fmt yuv422p10le -x264-params threads=1:sliced-threads=0 "$out/mp4/h264-422-10.mov"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libx264 -preset fast -crf 30 -g 10 -bf 2 \
    -pix_fmt yuv444p -x264-params threads=1:sliced-threads=0 "$out/mp4/h264-444.mp4"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libx265 -preset ultrafast -crf 34 -g 5 -bf 0 \
    -pix_fmt yuv420p -tag:v hvc1 -x265-params pools=1:frame-threads=1:log-level=error "$out/mp4/hevc.mp4"
ff -f lavfi -i "$short" -c:a alac -ac 2 "$out/mp4/alac.m4a"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -c:v prores_ks -profile:v 2 -pix_fmt yuv422p10le \
    "$out/mp4/prores.mov"
ff -f lavfi -i "testsrc2=size=256x128:rate=15:duration=0.2" -c:v dnxhd -profile:v dnxhr_lb -pix_fmt yuv422p \
    "$out/mp4/dnxhr.mov"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -c:v jpeg2000 -pix_fmt yuv422p "$out/mp4/jpeg2000.mov"
python -I "$(dirname "$0")/make-mj2-sample.py" "$out/mp4/motion.mj2"
ff -f lavfi -i "testsrc2=size=128x96:rate=15:duration=1" -f lavfi -i "sine=frequency=440:sample_rate=8000:duration=1" \
    -c:v h263 -q:v 8 -g 5 -c:a libopencore_amrnb -b:a 12.2k -ac 1 -shortest "$out/mp4/h263-amr.3gp"

ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a libopus -b:a 32k -ac 1 -shortest \
    "$out/matroska/h264-opus.mkv"
ff -f lavfi -i "$video" -f lavfi -i "$noise" -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 100k -g 10 \
    -threads 1 -c:a libvorbis -q:a 0 -ac 1 -shortest "$out/matroska/vp9-vorbis.webm"
ff -f lavfi -i "$short" -c:a flac -ac 2 "$out/matroska/flac.mka"
# transparent WebM: AlphaMode 1, the alpha frames in BlockAdditional 1
alpha="testsrc2=size=64x48:rate=10:duration=1,format=yuva420p,geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='clip(4*X+40*sin(N+Y/8),0,255)'"
ff -f lavfi -i "$alpha" -c:v libvpx -pix_fmt yuva420p -deadline realtime -cpu-used 8 -b:v 200k -g 5 -auto-alt-ref 0 \
    -threads 1 "$out/matroska/vp8-alpha.webm"
ff -f lavfi -i "$alpha" -c:v libvpx-vp9 -pix_fmt yuva420p -deadline realtime -cpu-used 8 -b:v 200k -g 5 -threads 1 \
    "$out/matroska/vp9-alpha.webm"

ff -f lavfi -i "$noise" -c:a libvorbis -q:a 0 -ac 2 "$out/ogg/vorbis.ogg"
ff -f lavfi -i "$tone" -c:a libopus -b:a 32k -ac 2 "$out/ogg/opus.opus"
ff -f lavfi -i "$short" -c:a flac -ac 1 -f ogg "$out/ogg/flac.oga"
link1="$(mktemp -d)"
ff -f lavfi -i "sine=frequency=330:sample_rate=44100:duration=1" -c:a libvorbis -q:a 0 -ac 1 -serial_offset 11 \
    "$link1/a.ogg"
ff -f lavfi -i "sine=frequency=660:sample_rate=44100:duration=1" -c:a libvorbis -q:a 0 -ac 1 -serial_offset 22 \
    "$link1/b.ogg"
cat "$link1/a.ogg" "$link1/b.ogg" > "$out/ogg/chained.ogg"
rm -rf "$link1"

ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a aac -b:a 48k -ac 1 -shortest "$out/ts/h264-aac.ts"
ff -f lavfi -i "$video" -f lavfi -i "$tone" "${x264[@]}" -c:a ac3 -b:a 96k -ac 1 -shortest -f mpegts \
    -mpegts_m2ts_mode 1 "$out/ts/h264-ac3.m2ts"
ff -f lavfi -i "$video" -f lavfi -i "$tone" -t 0.6 -c:v mpeg4 -q:v 8 -g 10 -bf 2 -c:a pcm_bluray -ac 1 -f mpegts \
    -mpegts_m2ts_mode 1 "$out/ts/mpeg4-lpcm.m2ts"
ff -f lavfi -i "$tone" -t 0.3 -c:a s302m -strict -2 -ac 2 "$out/ts/s302m.ts"
# two recordings concatenated: the second continues the first one's timeline, moves the streams to other PIDs, adds
# an MP2 stream and has PMT version 1
pmt="$(mktemp -d)"
ff -f lavfi -i "$video" -f lavfi -i "$tone" -t 1 "${x264[@]}" -c:a aac -b:a 48k -ac 1 "$pmt/a.ts"
ff -f lavfi -i "$video" -f lavfi -i "$tone" -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=1" -t 1 \
    -map 0 -map 1 -map 2 "${x264[@]}" -c:a:0 aac -b:a:0 48k -ac:a:0 1 -c:a:1 mp2 -b:a:1 64k -ac:a:1 1 \
    -mpegts_start_pid 0x200 -tables_version 1 -output_ts_offset 1 "$pmt/b.ts"
cat "$pmt/a.ts" "$pmt/b.ts" > "$out/ts/pmt-change.ts"
rm -rf "$pmt"
obu="$(mktemp -d)"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libaom-av1 -cpu-used 8 -usage realtime -b:v 100k \
    -g 5 -threads 1 -row-mt 0 -f obu "$obu/av1.obu"
python -I "$(dirname "$0")/make-ts-samples.py" av1-scte35 "$obu/av1.obu" "$out/ts/av1-scte35.ts"
rm -rf "$obu"

ff -f lavfi -i "$video" -f lavfi -i "$tone" -c:v mpeg4 -q:v 8 -g 10 -bf 0 -c:a libmp3lame -b:a 32k -ac 1 -shortest \
    "$out/avi/mpeg4-mp3.avi"
ff -f lavfi -i "$video" -f lavfi -i "$tone" -c:v mjpeg -q:v 10 -c:a pcm_s16le -ac 1 -ar 8000 -shortest \
    "$out/avi/mjpeg-pcm.avi"

ff -f lavfi -i "$short" -c:a pcm_s16le -ac 2 "$out/wav/s16.wav"
ff -f lavfi -i "$tone" -c:a pcm_f32le -ac 1 -ar 8000 "$out/wav/f32.wav"
ff -f lavfi -i "$short" -c:a pcm_s24be -ac 2 "$out/aiff/s24.aiff"

ff -f lavfi -i "$noise" -c:a libmp3lame -q:a 6 -ac 2 -metadata title=Behavior -id3v2_version 3 "$out/mp3/vbr-xing.mp3"
ff -f lavfi -i "$tone" -c:a libmp3lame -b:a 64k -ac 1 -write_xing 0 -id3v2_version 0 "$out/mp3/cbr.mp3"
# MP2 followed by an ID3v1 tag (title, artist, album, year, comment, genre 12), which the mp2 muxer does not write
ff -f lavfi -i "$tone" -c:a mp2 -b:a 64k -ac 1 -f mp2 "$out/mp3/mp2-id3v1.mp2"
python -I -c 'import sys; f = lambda t, n: t.encode("ascii").ljust(n, bytes(1)); open(sys.argv[1], "ab").write(b"TAG" + f("Behavior", 30) + f("Smalt", 30) + f("Samples", 30) + b"2026" + f("mp2", 30) + bytes([12]))' \
    "$out/mp3/mp2-id3v1.mp2"

ff -f lavfi -i "$noise" -c:a aac -b:a 64k -ac 2 -f adts "$out/adts/aac.aac"

ff -f lavfi -i "sine=frequency=440:sample_rate=8000:duration=2" -c:a libopencore_amrnb -b:a 12.2k -ac 1 "$out/amr/nb.amr"
ff -f lavfi -i "sine=frequency=440:sample_rate=16000:duration=2" -c:a libvo_amrwbenc -b:a 23.85k -ac 1 \
    -f amr "$out/amr/wb.awb"

ff -f lavfi -i "$noise" -c:a ac3 -b:a 128k -ac 2 "$out/ac3/ac3.ac3"
ff -f lavfi -i "$noise" -c:a eac3 -b:a 96k -ac 2 -f eac3 "$out/ac3/eac3.eac3"

ff -f lavfi -i "$video" -c:v libvpx -deadline realtime -cpu-used 8 -b:v 100k -g 10 -threads 1 "$out/ivf/vp8.ivf"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libaom-av1 -cpu-used 8 -usage realtime -b:v 100k \
    -g 5 -threads 1 -row-mt 0 "$out/ivf/av1.ivf"

ff -f lavfi -i "$short" -c:a flac -ac 2 -compression_level 5 "$out/flac/flac.flac"

# elementary video streams and uncompressed video
ff -f lavfi -i "$video" "${x264[@]}" -f h264 "$out/elementary/h264.h264"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libx265 -preset ultrafast -crf 34 -g 5 -bf 2     -pix_fmt yuv420p -x265-params pools=1:frame-threads=1:log-level=error -f hevc "$out/elementary/hevc.h265"
ff -f lavfi -i "testsrc2=size=160x120:rate=15:duration=1" -c:v libaom-av1 -cpu-used 8 -usage realtime -b:v 100k     -g 5 -threads 1 -row-mt 0 -f obu "$out/elementary/av1.obu"
python -I "$(dirname "$0")/make-obu-annexb.py" "$out/elementary/av1.obu" "$out/elementary/av1-annexb.obu"
ff -f lavfi -i "$video" -c:v mpeg4 -q:v 8 -g 10 -bf 2 -f m4v "$out/elementary/mpeg4.m4v"
ff -f lavfi -i "testsrc2=size=176x144:rate=15:duration=1" -c:v h263 -q:v 8 -g 5 -f h263 "$out/elementary/h263.263"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -pix_fmt yuv420p "$out/y4m/yuv420.y4m"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -pix_fmt yuv422p10le -strict -1 "$out/y4m/yuv422p10.y4m"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -vf scale=33:17 -c:v rawvideo -pix_fmt rgb24     "$out/rawvideo/rgb24.mov"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -c:v rawvideo -pix_fmt uyvy422 "$out/rawvideo/2vuy.mov"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -c:v v210 "$out/rawvideo/v210.mov"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -vf scale=33:17 -c:v rawvideo -pix_fmt pal8     "$out/rawvideo/pal8.avi"
ff -f lavfi -i "testsrc2=size=64x48:rate=15:duration=0.4" -c:v rawvideo -pix_fmt yuv420p "$out/rawvideo/i420.mkv"

printf '1\n00:00:00,000 --> 00:00:00,400\nHello\n\n2\n00:00:00,500 --> 00:00:00,900\n<i>World</i>\n\n3\n00:00:01,250 --> 00:00:02,000\nTwo\nlines\n' \
    > "$out/subtitle/cues.srt"
printf 'WEBVTT\n\nNOTE a comment block\n\n00:00.000 --> 00:00.400 align:start\nHello\n\nsecond\n00:00.500 --> 00:00.900\n<b>World</b>\n\n00:01.250 --> 00:02.000\nTwo\nlines\n' \
    > "$out/subtitle/cues.vtt"

python -I "$(dirname "$0")/make-wav-samples.py" "$out"

bitmap="$(dirname "$0")/make-bitmap-caption-samples.py"
python -I "$bitmap" sup "$out/subtitle/pgs.sup"
python -I "$bitmap" dvb "$out/ts/dvb-subtitles.ts"
python -I "$bitmap" teletext "$out/ts/teletext.ts"
captions="$(mktemp -d)"
python -I "$bitmap" vobsub "$out/subtitle/vobsub.sub" "$captions/vobsub.idx"
cp "$out/subtitle/vobsub.sub" "$captions/vobsub.sub"
ff -i "$out/subtitle/pgs.sup" -map 0 -c copy "$out/matroska/pgs.mkv"
ff -i "$captions/vobsub.idx" -map 0 -c copy "$out/matroska/vobsub.mkv"
ff -i "$captions/vobsub.idx" -map 0 -c copy "$out/mp4/vobsub.mp4"
ff -f lavfi -i "testsrc=s=64x48:r=30000/1001:d=3" -c:v libx264 -pix_fmt yuv420p -bf 0 -g 30 -x264-params aud=1     "$captions/raw.h264"
python -I "$bitmap" captions "$captions/raw.h264" "$captions/captions.h264"
ff -fflags +genpts -r 30000/1001 -f h264 -i "$captions/captions.h264" -c copy "$out/ts/h264-captions.ts"
rm -rf "$captions"
