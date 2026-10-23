package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the codec-neutral key frame settings of the video encoders and the transcoder, and the
 * elementary-stream and Ogg FLAC output targets, checked with ffmpeg and ffprobe.
 */
@DisplayName("Key frames and output targets")
class TranscodeTargetsTest {
    private static final Rational RATE = new Rational(30, 1);

    /**
     * Renders frame {@code index} of a pattern that pans one pixel a frame, so that no frame looks
     * like a scene cut.
     */
    private static RgbaPicture frame(int index) {
        var picture = RgbaPicture.allocate(96, 64, false);
        var pixels = picture.pixels();
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 96; x++) {
                var offset = (y * 96 + x) * 4;
                var u = x + index;
                pixels[offset] = (byte) (u * 3 + y);
                pixels[offset + 1] = (byte) (y * 4 + (u >> 2));
                pixels[offset + 2] = (byte) (((u >> 3) ^ (y >> 3)) * 24);
                pixels[offset + 3] = (byte) 255;
            }
        }
        return picture;
    }

    /**
     * Encodes frames of the panning pattern and returns the display indices of the key frames.
     */
    private static TreeSet<Integer> keyframes(Codec codec, VideoEncoderOptions options, int frames) {
        var packets = new ArrayList<Packet>();
        Rational timeBase;
        try (var encoder = VideoEncoders.create(codec, options)) {
            timeBase = encoder.timeBase();
            var step = RATE.invert().rescale(1, timeBase);
            for (var index = 0; index < frames; index++) {
                encoder.encode(new VideoFrame(frame(index), RATE.invert().rescale(index, timeBase), step, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        assertEquals(frames, packets.size(), codec + " packets");
        var result = new TreeSet<Integer>();
        for (var packet : packets) {
            if (packet.keyframe()) {
                result.add((int) Math.round(timeBase.toMicros(packet.pts()) * 30 / 1e6));
            }
        }
        return result;
    }

    private static TreeSet<Integer> multiples(int interval, int frames) {
        var result = new TreeSet<Integer>();
        for (var index = 0; index < frames; index += interval) {
            result.add(index);
        }
        return result;
    }

    @Nested
    @DisplayName("key frames")
    class Keyframes {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"H264", "VP8", "VP9", "AV1"})
        @DisplayName("places a key frame every interval frames, and none after the first without periodic key frames")
        void interval(Codec codec) {
            var base = VideoEncoderOptions.of(96, 64, RATE, 300_000);
            assertEquals(multiples(7, 29), keyframes(codec, base.toBuilder().keyframeInterval(7).build(), 29));
            assertEquals(multiples(12, 40), keyframes(codec, base.toBuilder().keyframeInterval(12).build(), 40));
            assertEquals(multiples(7, 29), keyframes(codec, VideoEncoderOptions.forRealtime(96, 64, RATE, 300_000)
                    .toBuilder().keyframeInterval(7).build(), 29));
            assertEquals(new TreeSet<>(List.of(0)), keyframes(codec, base.toBuilder().noPeriodicKeyframes().build(), 140));
        }

        @Test
        @DisplayName("keeps each codec's own distance when the interval is automatic")
        void automatic() {
            var base = VideoEncoderOptions.of(96, 64, RATE, 300_000);
            // H.264 files get an IDR picture every two seconds, VP8 and VP9 every 128 frames
            assertEquals(multiples(60, 140), keyframes(Codec.H264, base, 140));
            assertEquals(multiples(128, 140), keyframes(Codec.VP8, base, 140));
            assertEquals(multiples(128, 140), keyframes(Codec.VP9, base, 140));
            assertEquals(multiples(60, 140), keyframes(Codec.H264, base.toBuilder().keyframeInterval(9)
                    .automaticKeyframeInterval().build(), 140));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"H264, MATROSKA", "VP9, WEBM", "VP8, WEBM", "AV1, MP4"})
        @DisplayName("gives a transcoded stream the requested key frames, as ffprobe reads them")
        void transcoded(Codec codec, OutputContainer container, @TempDir Path directory) throws IOException {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var source = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=2"),
                    List.of("-c:v", "libx264", "-g", "250", "-pix_fmt", "yuv420p"));
            var output = directory.resolve("out." + container.extension());
            var options = TranscodeOptions.of(container).toBuilder()
                    .video(VideoTranscodeOptions.encode(codec).toBuilder().keyframeInterval(10).build()).build();
            try (var in = MediaSource.of(source); var out = MediaSink.of(output)) {
                Transcoder.transcode(in, out, options);
            }
            assertEquals(multiples(10, 60), probeKeyframes(output));
            var none = directory.resolve("none." + container.extension());
            try (var in = MediaSource.of(source); var out = MediaSink.of(none)) {
                Transcoder.transcode(in, out, options.toBuilder().video(options.video().toBuilder().noPeriodicKeyframes()
                        .build()).build());
            }
            assertEquals(new TreeSet<>(List.of(0)), probeKeyframes(none));
        }

        @Test
        @DisplayName("rejects a key frame interval that is not positive")
        void validation() {
            var base = VideoEncoderOptions.of(96, 64, RATE, 300_000);
            assertThrows(IllegalArgumentException.class,
                    () -> base.toBuilder().keyframeInterval(0).build());
            assertThrows(IllegalArgumentException.class,
                    () -> VideoTranscodeOptions.builder().keyframeInterval(-3).build());
            var set = base.toBuilder().keyframeInterval(5).build();
            assertEquals(5, set.keyframeInterval().getAsInt());
            assertTrue(set.periodicKeyframes());
            var off = set.toBuilder().noPeriodicKeyframes().build();
            assertTrue(off.keyframeInterval().isEmpty());
            assertFalse(off.periodicKeyframes());
            assertEquals(base, off.toBuilder().automaticKeyframeInterval().build());
            assertFalse(base.equals(off));
            var transcode = VideoTranscodeOptions.builder().keyframeInterval(4).build();
            assertEquals(transcode, transcode.toBuilder().build());
            assertEquals(VideoTranscodeOptions.defaults(), transcode.toBuilder().automaticKeyframeInterval().build());
            assertFalse(transcode.toBuilder().noPeriodicKeyframes().build().periodicKeyframes());
        }
    }

    /**
     * Returns the display indices, at 30 frames per second, of the key frames ffprobe reports in the
     * first video stream.
     */
    static TreeSet<Integer> probeKeyframes(Path file) {
        var csv = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error", "-select_streams",
                "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", file.toString())), StandardCharsets.UTF_8);
        var result = new TreeSet<Integer>();
        var first = Double.NaN;
        var times = new ArrayList<double[]>();
        for (var line : csv.split("\\R")) {
            var fields = line.trim().split(",");
            if (fields.length < 2 || fields[0].isEmpty() || fields[0].equals("N/A")) {
                continue;
            }
            var time = Double.parseDouble(fields[0]);
            first = Double.isNaN(first) ? time : Math.min(first, time);
            times.add(new double[]{time, fields[1].startsWith("K") ? 1 : 0});
        }
        for (var entry : times) {
            if (entry[1] == 1) {
                result.add((int) Math.round((entry[0] - first) * 30));
            }
        }
        return result;
    }

    static Path transcode(Path input, Path output, TranscodeOptions options) {
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            Transcoder.transcode(in, out, options);
        }
        return output;
    }

    @Nested
    @DisplayName("elementary video streams")
    class ElementaryVideo {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {
                "H264;mp4;-c:v libx264 -g 10 -bf 2 -pix_fmt yuv420p;h264",
                "HEVC;mp4;-c:v libx265 -x265-params keyint=10:log-level=none -pix_fmt yuv420p;hevc",
                "AV1;mkv;-c:v libaom-av1 -g 10 -cpu-used 8 -pix_fmt yuv420p;obu"})
        @DisplayName("copies a stream into an elementary stream that ffmpeg decodes to the same frames")
        void copy(OutputContainer container, String extension, String encoder, String ffmpegFormat, @TempDir Path directory) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=1",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1"), List.of(encoder.split(" ")));
            var output = directory.resolve("out." + container.extension());
            TranscodeResult result;
            try (var in = MediaSource.of(source); var out = MediaSink.of(output)) {
                result = Transcoder.transcode(in, out, TranscodeOptions.of(container));
            }
            assertEquals(1, result.streams().size(), "the audio is left out");
            assertTrue(result.streams().getFirst().copied());
            assertEquals(container.videoCodecs().getFirst(), result.streams().getFirst().track().codec());
            Oracle.assertDecodesCleanly(output);
            var expected = Oracle.decodeVideo(source, "yuv420p", 0);
            assertEquals(30 * 160 * 120 * 3 / 2, expected.length);
            assertArrayEquals(expected, Oracle.decodeVideo(output, List.of("-f", ffmpegFormat), "yuv420p", 0));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {"H264;h264", "AV1;obu"})
        @DisplayName("encodes into an elementary stream that ffmpeg decodes close to the source")
        void encode(OutputContainer container, String ffmpegFormat, @TempDir Path directory) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = Fixtures.ffmpeg("webm", List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=1"),
                    List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "2M"));
            var output = transcode(source, directory.resolve("out." + container.extension()), TranscodeOptions.of(container)
                    .toBuilder().video(VideoTranscodeOptions.builder().bitrate(1_500_000).keyframeInterval(15).build())
                    .build());
            Oracle.assertDecodesCleanly(output);
            var expected = Oracle.decodeVideo(source, "gray", 0);
            var actual = Oracle.decodeVideo(output, List.of("-f", ffmpegFormat), "gray", 0);
            assertEquals(expected.length, actual.length, "frame count");
            assertTrue(TranscodeBehaviorTest.meanAbsoluteDifference(expected, actual) < 6);
        }

        @Test
        @DisplayName("maps file names and rejects other codecs")
        void profile() {
            assertEquals(OutputContainer.H264, OutputContainer.forFileName("clip.264").orElseThrow());
            assertEquals(OutputContainer.HEVC, OutputContainer.forFileName("clip.HEVC").orElseThrow());
            assertEquals(OutputContainer.AV1, OutputContainer.forFileName("clip.obu").orElseThrow());
            assertFalse(OutputContainer.H264.supports(Codec.HEVC));
            assertFalse(OutputContainer.AV1.supports(Codec.OPUS));
            var tone = Fixtures.read(Fixtures.tone("wav", 440, 48_000, 1, 0.2, List.of()));
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(
                    MediaSource.of(tone), MediaSink.memory(),
                    TranscodeOptions.of(OutputContainer.H264)));
            // no HEVC encoder exists, so only an HEVC stream can be written to an .h265 file
            var vp9 = Fixtures.ffmpeg("webm", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=0.3"),
                    List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8"));
            var sink = MediaSink.memory();
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class, () -> Transcoder.transcode(
                    MediaSource.of(Fixtures.read(vp9)), sink, TranscodeOptions.of(OutputContainer.HEVC)));
            assertEquals(0, sink.size(), "nothing is written");
        }
    }

    @Nested
    @DisplayName("Dolby elementary streams and Ogg FLAC")
    class Audio {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {"AC3;ac3;ac3", "EAC3;eac3;eac3"})
        @DisplayName("copies an AC-3 or E-AC-3 stream byte for byte and encodes other audio to it")
        void dolby(OutputContainer container, String encoder, String probeName, @TempDir Path directory)
                throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var elementary = Fixtures.tone(container.extension(), 440, 48_000, 2, 1, List.of("-c:a", encoder, "-b:a", "192k"));
            var copied = transcode(elementary, directory.resolve("copy." + container.extension()), TranscodeOptions.of(container));
            assertArrayEquals(Files.readAllBytes(elementary), Files.readAllBytes(copied));
            var mkv = transcode(elementary, directory.resolve("in.mkv"), TranscodeOptions.of(OutputContainer.MATROSKA));
            var back = transcode(mkv, directory.resolve("back." + container.extension()), TranscodeOptions.of(container));
            assertArrayEquals(Files.readAllBytes(elementary), Files.readAllBytes(back));
            var wav = Fixtures.tone("wav", 440, 48_000, 2, 1, List.of());
            var encoded = transcode(wav, directory.resolve("enc." + container.extension()), TranscodeOptions.of(container));
            assertTrue(Oracle.probe(encoded).contains("\"codec_name\": \"" + probeName + "\""), Oracle.probe(encoded));
            Oracle.assertDecodesCleanly(encoded);
            var expected = Oracle.decodeAudio(wav);
            var decoded = Oracle.decodeAudio(encoded);
            assertTrue(decoded.length >= expected.length, "duration kept");
            try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(encoded)))) {
                assertEquals(container.audioCodecs().getFirst(), demuxer.tracks().getFirst().codec());
            }
        }

        @Test
        @DisplayName("re-encodes E-AC-3 for an AC-3 file and AC-3 for an E-AC-3 file")
        void dolbyProfiles(@TempDir Path directory) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var eac3 = Fixtures.tone("eac3", 440, 48_000, 2, 0.5, List.of("-c:a", "eac3"));
            var result = Transcoder.transcode(MediaSource.of(Files.readAllBytes(eac3)), MediaSink.memory(),
                    TranscodeOptions.of(OutputContainer.AC3));
            assertFalse(result.streams().getFirst().copied());
            assertEquals(Codec.AC3, result.streams().getFirst().track().codec());
            assertEquals(OutputContainer.EAC3, OutputContainer.forFileName("x.ec3").orElseThrow());
            assertEquals(OutputContainer.AC3, OutputContainer.forFileName("x.AC3").orElseThrow());
            assertFalse(OutputContainer.EAC3.supports(Codec.AC3));
        }

        @Test
        @DisplayName("writes Ogg FLAC that ffmpeg decodes to the source samples, and copies FLAC into it")
        void oggFlac(@TempDir Path directory) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var wav = Fixtures.tone("wav", 440, 44_100, 2, 1, List.of("-c:a", "pcm_s16le"));
            var output = transcode(wav, directory.resolve("out.oga"), TranscodeOptions.of(OutputContainer.OGG_FLAC));
            var probe = Oracle.probe(output);
            assertTrue(probe.contains("\"codec_name\": \"flac\"") && probe.contains("\"format_name\": \"ogg\""), probe);
            Oracle.assertDecodesCleanly(output);
            assertArrayEquals(Oracle.decodeAudio(wav), Oracle.decodeAudio(output));
            var flac = Fixtures.tone("flac", 440, 44_100, 2, 1, List.of());
            var result = Transcoder.transcode(MediaSource.of(Files.readAllBytes(flac)), MediaSink.of(directory.resolve("copy.oga")),
                    TranscodeOptions.of(OutputContainer.OGG_FLAC));
            assertTrue(result.streams().getFirst().copied());
            assertArrayEquals(Oracle.decodeAudio(flac), Oracle.decodeAudio(directory.resolve("copy.oga")));
            var opus = Fixtures.tone("opus", 440, 48_000, 2, 0.5, List.of("-c:a", "libopus"));
            var encoded = Transcoder.transcode(MediaSource.of(Files.readAllBytes(opus)), MediaSink.memory(),
                    TranscodeOptions.of(OutputContainer.OGG_FLAC));
            assertEquals(Codec.FLAC, encoded.streams().getFirst().track().codec());
            assertEquals("oga", OutputContainer.OGG_FLAC.extension());
            assertEquals(OutputContainer.OGG, OutputContainer.forFileName("x.oga").orElseThrow());
            assertThrows(IllegalArgumentException.class, () ->
                    Transcoder.transcode(MediaSource.of(Files.readAllBytes(opus)), MediaSink.memory(),
                            TranscodeOptions.of(OutputContainer.OGG_FLAC).toBuilder()
                                    .audio(AudioTranscodeOptions.encode(Codec.OPUS)).build()));
        }
    }
}
