package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the time-range operations: frame- and sample-exact trims of encoded streams, edit-list
 * exact trims of copied streams, identical output whether the demuxer seeks or decodes from the
 * start (for every seekable audio container and codec), and the byte-exact remux path.
 */
class TranscodeTrimTest {
    /**
     * Returns a 5-second 320x240 30 fps H.264 + AAC MP4 whose only key frame is the first one.
     */
    static Path h264() {
        return Fixtures.testVideo("mp4", 320, 240, 5, List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "2",
                "-g", "300", "-c:a", "aac", "-shortest"));
    }

    /**
     * Returns a 5-second 320x240 30 fps VP9 + Opus WebM with a key frame every second.
     */
    static Path vp9() {
        return Fixtures.testVideo("webm", 320, 240, 5, List.of("-c:v", "libvpx-vp9", "-b:v", "300k", "-deadline", "realtime",
                "-cpu-used", "8", "-g", "30", "-c:a", "libopus", "-shortest"));
    }

    static TranscodeResult transcode(Path input, Path output, TranscodeOptions options) {
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            return Transcoder.transcode(in, out, options);
        }
    }

    @Nested
    @DisplayName("encoded streams")
    class Encoded {
        @ParameterizedTest(name = "{0}..{1} us into {2}")
        @CsvSource({
                "1000000, 2500000, MP4",
                "400000, 1900000, MP4",
                "1050000, 3000000, WEBM",
                "0, 1700000, WEBM",
                "2500000, 5000000, MATROSKA"})
        @DisplayName("keep exactly the frames and samples of the range, starting with the frame shown at the start")
        void exact(long start, long end, OutputContainer container, @TempDir Path directory) {
            var input = container == OutputContainer.MP4 ? h264() : vp9();
            var output = directory.resolve("out." + container.extension());
            var options = TranscodeOptions.of(container)
                    .toBuilder().range(start, end)
                    .video(VideoTranscodeOptions.builder().mode(StreamMode.ENCODE).build())
                    .audio(AudioTranscodeOptions.builder().mode(StreamMode.ENCODE).build()).build();
            var result = transcode(input, output, options);
            Oracle.assertDecodesCleanly(output);
            var probe = MediaProbe.of(output);
            var firstFrame = (int) Math.floor(start * 30 / 1e6 + 1e-6);
            var lastFrame = (int) Math.ceil(end * 30 / 1e6 - 1e-6);
            assertEquals(lastFrame - firstFrame, probe.video().decoded(), "decoded frames");
            var seconds = (end - start) / 1e6;
            // AAC: ffmpeg's raw decoding keeps the padding of the final frame that the edit list trims
            var tolerance = probe.audio().codec().equals("aac") ? 1024.0 / 48_000 : 1.0 / 48_000;
            assertEquals(seconds, probe.audio().audioSeconds(), tolerance, "decoded audio seconds");
            assertEquals(probe.video().start(), probe.audio().start(), 0.001, "audio and video start together");
            assertEquals(seconds, result.durationMicros() / 1e6, 1.0 / 30, "reported duration");
            assertEquals(firstFrame, matchingSourceFrame(input, output, firstFrame), "first output frame");
        }

        @Test
        @DisplayName("limits the duration and starts mid-stream")
        void maxDuration(@TempDir Path directory) {
            var output = directory.resolve("out.webm");
            var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder().start(3_500_000).maxDuration(10_000_000)
                    .video(VideoTranscodeOptions.builder().mode(StreamMode.ENCODE).build())
                    .audio(AudioTranscodeOptions.builder().mode(StreamMode.ENCODE).build()).build();
            transcode(vp9(), output, options);
            var probe = MediaProbe.of(output);
            assertEquals(45, probe.video().decoded());
            assertEquals(1.5, probe.audio().audioSeconds(), 1.0 / 48_000);
        }
    }

    @Nested
    @DisplayName("copied streams")
    class Copied {
        @ParameterizedTest(name = "{0}..{1} us")
        @CsvSource({"1500000, 3200000", "400000, 1000000", "0, 2000000", "4000000, 5000000"})
        @DisplayName("start at the preceding key frame and present exactly the range through the MP4 edit lists")
        void editLists(long start, long end, @TempDir Path directory) {
            var output = directory.resolve("out.mp4");
            var result = transcode(h264(), output, TranscodeOptions.remux(OutputContainer.MP4).toBuilder()
                    .range(start, end).build());
            assertTrue(result.remuxed());
            Oracle.assertDecodesCleanly(output);
            var probe = MediaProbe.of(output);
            var frames = Math.round((end - start) * 30 / 1e6);
            assertEquals(frames, probe.video().decoded(), "presented frames");
            assertEquals((end - start) / 1e6, probe.audio().duration(), 1.0 / 48_000, "declared audio seconds");
            assertEquals((end - start) / 1e6, probe.video().duration(), 1.0 / 30, "declared video seconds");
            // ffmpeg's raw decoding keeps the rest of the final AAC frame that the edit list trims
            assertEquals((end - start) / 1e6, probe.audio().audioSeconds(), 1024.0 / 48_000, "presented audio seconds");
            assertEquals(0, probe.video().start(), 0.001);
            assertEquals(0, probe.audio().start(), 0.001);
        }

        @Test
        @DisplayName("keep audio and video in sync in containers without edit lists")
        void matroska(@TempDir Path directory) {
            var output = directory.resolve("out.webm");
            transcode(vp9(), output, TranscodeOptions.remux(OutputContainer.WEBM).toBuilder()
                    .range(1_500_000, 3_000_000).build());
            Oracle.assertDecodesCleanly(output);
            var probe = MediaProbe.of(output);
            // the copy starts at the key frame of second 1, so half a second of lead is presented
            assertEquals(60, probe.video().decoded(), "frames from the key frame to the end");
            assertEquals(probe.video().start() + 0.5, probe.audio().start(), 0.03, "audio starts at the requested start");
        }

        @Test
        @DisplayName("hand whole-file copies into MP4 to the remuxer, byte for byte")
        void remuxPath(@TempDir Path directory) throws Exception {
            for (var input : List.of(h264(), Fixtures.testVideo("mkv", 320, 240, 2, List.of("-c:v", "libx264", "-c:a", "aac",
                    "-shortest")))) {
                var output = directory.resolve("transcoded.mp4");
                var result = transcode(input, output, TranscodeOptions.of(OutputContainer.MP4));
                assertTrue(result.remuxed());
                var reference = MediaSink.memory();
                try (var in = MediaSource.of(input)) {
                    Mp4Remuxer.remux(in, reference, RemuxOptions.defaults());
                }
                assertArrayEquals(reference.toByteArray(), Files.readAllBytes(output), input.getFileName().toString());
            }
        }
    }

    @Nested
    @DisplayName("seeking")
    class Seeking {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"WAV", "MATROSKA"})
        @DisplayName("produces the same output as decoding from the start and dropping")
        void sameOutput(OutputContainer container, @TempDir Path directory) throws Exception {
            var input = Fixtures.tone("wav", 440, 44_100, 2, 4.0, List.of("-c:a", "pcm_s16le"));
            var options = TranscodeOptions.of(container).toBuilder().range(1_234_567, 3_210_987)
                    .audio(AudioTranscodeOptions.builder().gainDb(-3).codec(Codec.PCM_S16LE).build()).build();
            var seeked = directory.resolve("seeked." + container.extension());
            var decoded = directory.resolve("decoded." + container.extension());
            transcode(input, seeked, options.toBuilder().seeking(true).build());
            transcode(input, decoded, options.toBuilder().seeking(false).build());
            assertArrayEquals(Files.readAllBytes(decoded), Files.readAllBytes(seeked));
            assertEquals(Math.round((3_210_987 - 1_234_567) * 44_100L / 1e6), MediaProbe.of(seeked).audio().decoded(), 1);
        }

        @Test
        @DisplayName("keeps every subtitle cue shown at the start, also two that appear together and one stored late")
        void subtitlesStoredLate(@TempDir Path directory) throws Exception {
            var script = directory.resolve("cues.ass");
            Files.writeString(script, """
                    [Script Info]
                    ScriptType: v4.00+
                    PlayResX: 384
                    PlayResY: 288

                    [V4+ Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                    Style: Default,Arial,16,&Hffffff,&Hffffff,&H0,&H0,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,0

                    [Events]
                    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    Dialogue: 0,0:00:05.00,0:01:05.00,Default,,0,0,0,,Long A
                    Dialogue: 1,0:00:05.00,0:01:05.00,Default,,0,0,0,,Long B
                    Dialogue: 0,0:00:29.50,0:00:31.00,Default,,0,0,0,,Edge A
                    Dialogue: 1,0:00:29.50,0:00:32.00,Default,,0,0,0,,Edge B
                    Dialogue: 0,0:00:40.00,0:00:41.00,Default,,0,0,0,,Late
                    """);
            var input = Fixtures.read(Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=64x48:rate=10:duration=70", "-i", script.toString()), List.of("-c:v", "libx264", "-g", "50",
                    "-c:s", "copy")));
            // ffmpeg stores the second cue of 5 s after the video of 40 s, past the reach of the
            // subtitle scan, so only the seeked demuxer delivers it
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(MediaSource.of(input))) {
                var video = Long.MIN_VALUE;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == 1) {
                        video = Math.max(video, packet.pts());
                    } else if (new String(packet.data(), java.nio.charset.StandardCharsets.UTF_8).endsWith("Long B")) {
                        assertTrue(video >= 40_000, "the cue is stored after " + video + " ms of video");
                    }
                }
            }
            var expected = List.of("Long A", "Long B", "Edge A", "Edge B", "Late");
            for (var container : List.of(OutputContainer.MATROSKA, OutputContainer.SRT)) {
                var options = TranscodeOptions.of(container).toBuilder().start(30_000_000).build();
                var seeked = MediaSink.memory();
                Transcoder.transcode(MediaSource.of(input), seeked, options);
                var decoded = MediaSink.memory();
                Transcoder.transcode(MediaSource.of(input), decoded, options.toBuilder().seeking(false).build());
                var cues = com.github.auties00.smalt.subtitle.Subtitles.read(MediaSource.of(seeked.toByteArray()),
                        com.github.auties00.smalt.subtitle.SubtitleReaderOptions.defaults());
                assertEquals(com.github.auties00.smalt.subtitle.Subtitles.read(MediaSource.of(decoded.toByteArray()),
                        com.github.auties00.smalt.subtitle.SubtitleReaderOptions.defaults()), cues, container.name());
                assertEquals(expected, cues.stream().map(cue -> cue.text()).toList(), container.name());
                assertEquals(35_000_000, cues.get(1).endMicros(), container.name());
            }
        }

        @ParameterizedTest(name = "{0} from {2} us for at most {3} us at {4} Hz")
        @MethodSource("com.github.auties00.smalt.transcode.TranscodeTrimTest#seekableAudio")
        @DisplayName("decodes encoded audio after a seek to exactly the samples decoding from the start and dropping gives")
        void encodedAudio(String name, Path input, long start, long maxDuration, int sampleRate, @TempDir Path directory) {
            var audio = AudioTranscodeOptions.builder().mode(StreamMode.ENCODE).codec(Codec.PCM_F32LE).build();
            var options = TranscodeOptions.of(OutputContainer.WAV).toBuilder().start(start).maxDuration(maxDuration)
                    .audio(sampleRate > 0 ? audio.toBuilder().sampleRate(sampleRate).build() : audio).build();
            var seeked = directory.resolve("seeked.wav");
            var decoded = directory.resolve("decoded.wav");
            transcode(input, seeked, options.toBuilder().seeking(true).build());
            transcode(input, decoded, options.toBuilder().seeking(false).build());
            var expected = decodeAll(decoded);
            var actual = decodeAll(seeked);
            int rate;
            long total;
            var readerOptions = sampleRate > 0 ? AudioReaderOptions.builder().sampleRate(sampleRate).build()
                    : AudioReaderOptions.defaults();
            try (var reader = AudioReader.open(MediaSource.of(input), readerOptions)) {
                rate = reader.sampleRate();
                total = decodeAll(reader)[0].length;
            }
            var length = Math.min(Math.round(maxDuration * (double) rate / 1e6), total - Math.round(start * (double) rate / 1e6));
            assertEquals(length, expected[0].length, name + " samples decoding from the start");
            assertEquals(expected[0].length, actual[0].length, name + " samples after a seek");
            for (var channel = 0; channel < expected.length; channel++) {
                var mismatch = Arrays.mismatch(expected[channel], actual[channel]);
                assertEquals(-1, mismatch, name + " channel " + channel + " differs from sample " + mismatch);
            }
        }
    }

    /**
     * Returns six seconds of a two-channel sweep encoded by ffmpeg.
     */
    static Path sweep(String extension, int sampleRate, List<String> options) {
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "aevalsrc=0.3*sin(2*PI*(200+150*t)*t)|0.25*sin(2*PI*330*t+3*sin(5*t)):s="
                + sampleRate + ":d=6"), options);
    }

    static Stream<Arguments> seekableAudio() {
        var files = List.of(
                Arguments.of("ogg opus", sweep("opus", 48_000, List.of("-c:a", "libopus", "-f", "ogg"))),
                Arguments.of("webm opus", sweep("webm", 48_000, List.of("-c:a", "libopus"))),
                Arguments.of("mp4 opus", sweep("mp4", 48_000, List.of("-c:a", "libopus"))),
                Arguments.of("ogg vorbis", sweep("ogg", 44_100, List.of("-c:a", "libvorbis"))),
                // millisecond Matroska timestamps do not fall on 44.1 kHz sample boundaries
                Arguments.of("webm vorbis", sweep("webm", 44_100, List.of("-c:a", "libvorbis"))),
                Arguments.of("mka aac", sweep("mka", 44_100, List.of("-c:a", "aac", "-aac_pns", "0"))),
                // without noise substitution, whose generator runs from the start of the stream
                Arguments.of("m4a aac", sweep("m4a", 44_100, List.of("-c:a", "aac", "-aac_pns", "0"))),
                Arguments.of("adts aac", sweep("aac", 44_100, List.of("-c:a", "aac", "-aac_pns", "0", "-f", "adts"))),
                Arguments.of("mp3", sweep("mp3", 44_100, List.of("-c:a", "libmp3lame", "-b:a", "128k"))),
                Arguments.of("flac", sweep("flac", 44_100, List.of("-c:a", "flac"))));
        var ranges = new long[][]{{2_345_678, 1_865_309}, {3_500_000, 10_000_000}, {400_000, 1_100_000}};
        // 0 keeps the codec's rate; the others make the decoder resample, which must restart in phase
        var rates = new int[]{0, 44_100, 48_000, 16_000};
        return files.stream().flatMap(file -> Arrays.stream(ranges).flatMap(range -> Arrays.stream(rates)
                .mapToObj(rate -> Arguments.of(file.get()[0], file.get()[1], range[0], range[1], rate))));
    }

    /**
     * Returns every sample of the first audio track of a file, one array per channel.
     */
    static float[][] decodeAll(Path file) {
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            return decodeAll(reader);
        }
    }

    /**
     * Returns every remaining sample of a reader, one array per channel.
     */
    static float[][] decodeAll(AudioReader reader) {
        var planes = new float[reader.layout().channels()][1 << 16];
        var count = 0;
        for (var block = reader.read(); block != null; block = reader.read()) {
            if (count + block.frames() > planes[0].length) {
                for (var channel = 0; channel < planes.length; channel++) {
                    planes[channel] = Arrays.copyOf(planes[channel], Math.max(planes[channel].length * 2, count + block.frames()));
                }
            }
            for (var channel = 0; channel < planes.length; channel++) {
                System.arraycopy(block.plane(channel), 0, planes[channel], count, block.frames());
            }
            count += block.frames();
        }
        for (var channel = 0; channel < planes.length; channel++) {
            planes[channel] = Arrays.copyOf(planes[channel], count);
        }
        return planes;
    }

    /**
     * Returns the index of the source frame closest to the first frame of an output.
     */
    static int matchingSourceFrame(Path input, Path output, int expected) {
        var first = Oracle.decodeVideo(output, "gray", 1);
        var source = Oracle.decodeVideo(input, "gray", expected + 4);
        var frameSize = first.length;
        var best = -1;
        var bestError = Double.MAX_VALUE;
        for (var index = Math.max(0, expected - 3); (index + 1) * frameSize <= source.length; index++) {
            var error = 0.0;
            for (var pixel = 0; pixel < frameSize; pixel++) {
                error += Math.abs((first[pixel] & 0xFF) - (source[index * frameSize + pixel] & 0xFF));
            }
            if (error < bestError) {
                bestError = error;
                best = index;
            }
        }
        return best;
    }
}
