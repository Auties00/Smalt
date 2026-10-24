package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a transcode handles anamorphic video ({@link SampleAspectMode}): copies keep the
 * sample aspect ratio, encodes keep it (scaled by any uneven resize) or resample to square
 * samples, checked through ffprobe's {@code sample_aspect_ratio} and display size and against
 * ffmpeg's own {@code scale=iw*sar:ih} rendering.
 */
@DisplayName("Transcoding anamorphic video")
class SampleAspectTranscodeTest {
    /**
     * A 176x144 H.264 clip whose MP4 declares 16:11 samples, displayed 256x144.
     */
    static Path anamorphic() {
        var square = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1"),
                List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
        return Fixtures.ffmpeg("mp4", List.of("-i", square.toString()), List.of("-c", "copy", "-aspect", "16:9"));
    }

    static void transcode(Path input, Path output, TranscodeOptions options) {
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            Transcoder.transcode(in, out, options);
        }
    }

    record Probe(int width, int height, String sampleAspect) {
    }

    static Probe probe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var text = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio", "-of",
                "default=noprint_wrappers=1", file.toString())), StandardCharsets.UTF_8);
        int width = 0;
        int height = 0;
        String sar = null;
        for (var line : text.split("\\R")) {
            var parts = line.split("=", 2);
            if (parts.length < 2) {
                continue;
            }
            switch (parts[0]) {
                case "width" -> width = Integer.parseInt(parts[1].trim());
                case "height" -> height = Integer.parseInt(parts[1].trim());
                case "sample_aspect_ratio" -> sar = parts[1].trim();
                default -> {
                }
            }
        }
        return new Probe(width, height, sar);
    }

    static Optional<Rational> declared(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            return demuxer.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast).findFirst()
                    .orElseThrow().sampleAspectRatio();
        }
    }

    static double meanAbsoluteDifference(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        var sum = 0L;
        for (var index = 0; index < expected.length; index++) {
            sum += Math.abs((expected[index] & 0xFF) - (actual[index] & 0xFF));
        }
        return (double) sum / expected.length;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"MP4", "MATROSKA"})
    @DisplayName("copies a stream with its sample aspect ratio")
    void copy(OutputContainer container, @TempDir Path directory) {
        var output = directory.resolve("out." + container.extension());
        transcode(anamorphic(), output, TranscodeOptions.of(container).toBuilder().video(VideoTranscodeOptions.copy())
                .build());
        assertEquals(Optional.of(new Rational(16, 11)), declared(output));
        assertEquals("16:11", probe(output).sampleAspect());
    }

    @ParameterizedTest(name = "{0} {1}x{2} {3}")
    @CsvSource({"PRESERVE, 0, 0, FIT, 176, 144, 16:11", "AUTO, 0, 0, FIT, 176, 144, 16:11",
            "PRESERVE, 88, 144, EXACT, 88, 144, 32:11", "PRESERVE, 88, 72, FIT, 88, 72, 16:11",
            "APPLY, 0, 0, FIT, 256, 144, -", "APPLY, 128, 128, FIT, 128, 72, -"})
    @DisplayName("encodes keeping the sample aspect ratio or resampling to square samples")
    void encode(SampleAspectMode mode, int boxWidth, int boxHeight, ScaleMode scale, int width, int height, String sar,
                @TempDir Path directory) {
        var output = directory.resolve("out.mp4");
        var video = VideoTranscodeOptions.encode(com.github.auties00.smalt.track.Codec.H264).toBuilder().sampleAspect(mode);
        if (boxWidth > 0) {
            video.size(boxWidth, boxHeight, scale);
        }
        transcode(anamorphic(), output, TranscodeOptions.of(OutputContainer.MP4).toBuilder().video(video.build()).build());
        var probed = probe(output);
        assertEquals(width, probed.width());
        assertEquals(height, probed.height());
        if (sar.equals("-")) {
            assertEquals(Optional.empty(), declared(output));
        } else {
            assertEquals(sar, probed.sampleAspect());
        }
    }

    @Test
    @DisplayName("resamples to the display shape ffmpeg's scale=iw*sar:ih renders")
    void appliedPixels(@TempDir Path directory) {
        var output = directory.resolve("out.mp4");
        transcode(anamorphic(), output, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                .video(VideoTranscodeOptions.encode(com.github.auties00.smalt.track.Codec.H264).toBuilder()
                        .sampleAspect(SampleAspectMode.APPLY).build()).build());
        var expected = Oracle.decodeVideo(Fixtures.ffmpeg("mp4", List.of("-i", anamorphic().toString()),
                List.of("-vf", "scale=iw*sar:ih,setsar=1", "-c:v", "libx264", "-qp", "0", "-an")), "gray", 1);
        assertTrue(meanAbsoluteDifference(expected, Oracle.decodeVideo(output, "gray", 1)) < 6, "stretched picture");
    }

    @Test
    @DisplayName("resamples to square samples by default for a container that stores no ratio")
    void autoWithoutStorage(@TempDir Path directory) {
        var output = directory.resolve("out.ts");
        transcode(anamorphic(), output, TranscodeOptions.of(OutputContainer.MPEG_TS).toBuilder()
                .video(VideoTranscodeOptions.encode(com.github.auties00.smalt.track.Codec.H264)).build());
        var probed = probe(output);
        assertEquals(256, probed.width());
        assertEquals(144, probed.height());
    }

    @Test
    @DisplayName("refuses to copy a stream whose samples must be made square")
    void copyApply(@TempDir Path directory) {
        var output = directory.resolve("out.mp4");
        assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class, () -> transcode(anamorphic(), output,
                TranscodeOptions.of(OutputContainer.MP4).toBuilder().video(VideoTranscodeOptions.copy().toBuilder()
                        .sampleAspect(SampleAspectMode.APPLY).build()).build()));
        transcode(anamorphic(), output, TranscodeOptions.of(OutputContainer.MP4).toBuilder().video(VideoTranscodeOptions
                .builder().sampleAspect(SampleAspectMode.APPLY).build()).build());
        assertEquals(256, probe(output).width());
    }

    @Test
    @DisplayName("rejects squaring samples so wide that the picture would exceed the limits")
    void limits(@TempDir Path directory) {
        var square = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1"),
                List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
        var wide = Fixtures.ffmpeg("mp4", List.of("-i", square.toString()), List.of("-c", "copy", "-aspect", "1000:1"));
        var output = directory.resolve("out.mp4");
        assertThrows(com.github.auties00.smalt.MediaException.LimitExceeded.class, () -> transcode(wide, output,
                TranscodeOptions.of(OutputContainer.MP4).toBuilder().video(VideoTranscodeOptions.encode(
                        com.github.auties00.smalt.track.Codec.H264).toBuilder().sampleAspect(SampleAspectMode.APPLY).build())
                        .build()));
    }
}
