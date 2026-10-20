package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares {@link Thumbnails} with ffmpeg's accurate seek ({@code -ss} before the input, which
 * decodes from the preceding key frame and drops the frames before the time) and covers
 * {@link com.github.auties00.smalt.image.AnimationReader#seek(long)} on GIF, APNG and WebP.
 * Pictures are compared by PSNR, since the color conversion and the scaler differ.
 */
@DisplayName("Thumbnails")
class ThumbnailsTest {
    private static final String VIDEO = "testsrc2=size=320x240:rate=25:duration=4";

    static Path video(String extension, List<String> options) {
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", VIDEO), options);
    }

    static Stream<Arguments> videos() {
        return Stream.of(
                Arguments.of("mp4 h264 b-frames", video("mp4", List.of("-c:v", "libx264", "-g", "25", "-bf", "2",
                        "-pix_fmt", "yuv420p"))),
                Arguments.of("webm vp9", video("webm", List.of("-c:v", "libvpx-vp9", "-g", "25", "-deadline", "realtime",
                        "-cpu-used", "8"))),
                Arguments.of("mkv av1", video("mkv", List.of("-c:v", "libaom-av1", "-g", "25", "-cpu-used", "8"))),
                Arguments.of("webm vp8 without cues", video("webm", List.of("-c:v", "libvpx", "-g", "25", "-live", "1"))));
    }

    /**
     * Decodes ffmpeg's frame at a time, scaled, to packed RGBA; full chroma interpolation keeps
     * swscale's 4:2:0 shortcut from dominating the comparison.
     */
    static byte[] reference(Path file, long micros, int width, int height) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var arguments = new ArrayList<>(List.of("-v", "error"));
        if (micros >= 0) {
            arguments.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.6f", micros / 1e6)));
        }
        arguments.addAll(List.of("-i", file.toString(), "-frames:v", "1", "-vf", "scale=" + width + ":" + height
                + ":flags=bicubic+full_chroma_int+accurate_rnd", "-f", "rawvideo", "-pix_fmt", "rgba", "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments);
    }

    static double psnr(RgbaPicture picture, byte[] reference) {
        var error = 0.0;
        var count = 0;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var channel = 0; channel < 3; channel++) {
                    var ours = picture.pixels()[y * picture.stride() + x * 4 + channel] & 0xFF;
                    var theirs = reference[(y * picture.width() + x) * 4 + channel] & 0xFF;
                    error += (ours - theirs) * (ours - theirs);
                    count++;
                }
            }
        }
        return error == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / (error / count));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("videos")
    @DisplayName("takes the frame ffmpeg's accurate seek shows, full size and scaled")
    void videoFrames(String name, Path file) {
        var bytes = Fixtures.read(file);
        for (var micros : new long[]{0, 1_000_000, 2_360_000, 3_960_000}) {
            var full = Thumbnails.at(MediaSource.of(bytes), micros, ThumbnailOptions.fullSize());
            assertEquals(micros, full.timestampMicros(), 1000, name + " timestamp at " + micros);
            assertEquals(320, full.picture().width(), name);
            var fullPsnr = psnr(full.picture(), reference(file, micros, 320, 240));
            assertTrue(fullPsnr > 33, name + " full-size PSNR " + fullPsnr + " at " + micros);
            // the neighboring frames of the moving test pattern score some 20 dB, far below the right one
            var neighbor = psnr(full.picture(), reference(file, micros < 3_900_000 ? micros + 40_000 : micros - 40_000, 320, 240));
            assertTrue(fullPsnr > neighbor + 8, name + " PSNR " + fullPsnr + " against the next frame's " + neighbor);
            var small = Thumbnails.at(MediaSource.of(bytes), micros, ThumbnailOptions.fitting(160, 160));
            assertEquals(160, small.picture().width(), name);
            assertEquals(120, small.picture().height(), name);
            var smallPsnr = psnr(small.picture(), reference(file, micros, 160, 120));
            assertTrue(smallPsnr > 28, name + " scaled PSNR " + smallPsnr + " at " + micros);
        }
    }

    @Test
    @DisplayName("turns a rotated video upright and gives the last frame past the end")
    void rotatedAndPastEnd() {
        var source = video("mp4", List.of("-c:v", "libx264", "-g", "25"));
        var rotated = Fixtures.ffmpeg("mp4", List.of("-display_rotation", "90", "-i", source.toString()), List.of("-c", "copy"));
        var thumbnail = Thumbnails.at(MediaSource.of(Fixtures.read(rotated)), 500_000, ThumbnailOptions.fitting(120, 200));
        assertEquals(120, thumbnail.picture().width());
        assertEquals(160, thumbnail.picture().height());
        var last = Thumbnails.at(MediaSource.of(Fixtures.read(source)), 60_000_000, ThumbnailOptions.defaults());
        assertEquals(3_960_000, last.timestampMicros(), 1000);
    }

    @Test
    @DisplayName("spreads pictures evenly over the video")
    void evenly() {
        var thumbnails = Thumbnails.evenly(MediaSource.of(Fixtures.read(video("mp4", List.of("-c:v", "libx264", "-g", "25")))),
                4, ThumbnailOptions.fitting(80, 80));
        assertEquals(4, thumbnails.size());
        var times = thumbnails.stream().mapToLong(Thumbnail::timestampMicros).toArray();
        assertArrayEquals(new long[]{480_000, 1_480_000, 2_480_000, 3_480_000}, times);
    }

    static Stream<Arguments> matroskaVideos() {
        return Stream.of(
                Arguments.of("mkv h264", video("mkv", List.of("-c:v", "libx264", "-g", "25", "-bf", "2", "-pix_fmt", "yuv420p"))),
                Arguments.of("webm vp9", video("webm", List.of("-c:v", "libvpx-vp9", "-g", "25", "-deadline", "realtime",
                        "-cpu-used", "8"))),
                Arguments.of("webm vp8 without cues", video("webm", List.of("-c:v", "libvpx", "-g", "25", "-live", "1"))));
    }

    /**
     * Replaces the segment's {@code Duration} element (ID {@code 0x4489}) with a {@code Void}
     * element of the same size, as a recording whose header was never finalized lacks it.
     */
    static byte[] withoutDuration(byte[] matroska) {
        var info = indexOf(matroska, new byte[]{0x15, 0x49, (byte) 0xA9, 0x66}, 0);
        assertTrue(info >= 0, "no Info element");
        var duration = indexOf(matroska, new byte[]{0x44, (byte) 0x89}, info);
        assertTrue(duration >= 0, "no Duration element");
        var sizeByte = matroska[duration + 2] & 0xFF;
        assertTrue(sizeByte == 0x84 || sizeByte == 0x88, "one-byte size of a float");
        var length = 3 + (sizeByte & 0x0F);
        var result = matroska.clone();
        result[duration] = (byte) 0xEC;
        result[duration + 1] = 0x40;
        result[duration + 2] = (byte) (length - 3);
        java.util.Arrays.fill(result, duration + 3, duration + length, (byte) 0);
        return result;
    }

    static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (var index = from; index <= data.length - pattern.length; index++) {
            for (var offset = 0; offset < pattern.length; offset++) {
                if (data[index + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matroskaVideos")
    @DisplayName("spreads pictures over a video without a declared duration as over the same video with it")
    void evenlyWithoutDuration(String name, Path file) {
        // ffmpeg also writes per-track DURATION tags; a copy through Smalt's muxer declares only the
        // segment Duration, which the test then strips
        var source = Fixtures.read(file);
        var sink = com.github.auties00.smalt.io.MediaSink.memory();
        com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(source), sink,
                com.github.auties00.smalt.transcode.TranscodeOptions.of(
                        com.github.auties00.smalt.transcode.OutputContainer.MATROSKA));
        var declared = sink.toByteArray();
        var stripped = withoutDuration(declared);
        try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(declared))) {
            assertTrue(reader.durationMicros() > 0, name + " declares a duration");
            assertEquals(reader.durationMicros(), Thumbnails.measureDuration(MediaSource.of(declared), reader.track().id(),
                    ThumbnailOptions.defaults()), 1000, name + " measured like declared");
        }
        try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(stripped))) {
            assertEquals(-1, reader.durationMicros(), name + " declares no duration");
        }
        var options = ThumbnailOptions.fitting(80, 80);
        var expected = Thumbnails.evenly(MediaSource.of(declared), 5, options);
        var inputs = new ArrayList<>(List.of(stripped));
        try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(source))) {
            if (reader.durationMicros() < 0) {
                inputs.add(source);
            }
        }
        for (var input : inputs) {
            var actual = Thumbnails.evenly(MediaSource.of(input), 5, options);
            assertEquals(5, actual.size());
            for (var index = 0; index < 5; index++) {
                assertEquals(expected.get(index).timestampMicros(), actual.get(index).timestampMicros(),
                        name + " picture " + index);
                assertArrayEquals(expected.get(index).picture().pixels(), actual.get(index).picture().pixels(),
                        name + " picture " + index);
            }
        }
        assertEquals(3_960_000, Thumbnails.at(MediaSource.of(stripped), 60_000_000).timestampMicros(), 1000,
                name + " past the end");
    }

    @Test
    @DisplayName("measures a video whose packets carry no duration from its frame rate")
    void evenlyWithoutPacketDurations() {
        // IVF packets have no duration; zeroing the header's frame count leaves no declared duration
        var declared = Fixtures.read(video("ivf", List.of("-c:v", "libvpx", "-g", "25")));
        var counted = new byte[4];
        System.arraycopy(declared, 24, counted, 0, 4);
        assertArrayEquals(new byte[]{100, 0, 0, 0}, counted, "IVF frame count");
        var stripped = declared.clone();
        java.util.Arrays.fill(stripped, 24, 28, (byte) 0);
        try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(stripped))) {
            assertEquals(-1, reader.durationMicros());
            assertEquals(4_000_000, Thumbnails.measureDuration(MediaSource.of(stripped), reader.track().id(),
                    ThumbnailOptions.defaults()));
        }
        var options = ThumbnailOptions.fitting(80, 80);
        var expected = Thumbnails.evenly(MediaSource.of(declared), 5, options);
        var actual = Thumbnails.evenly(MediaSource.of(stripped), 5, options);
        for (var index = 0; index < 5; index++) {
            assertEquals(expected.get(index).timestampMicros(), actual.get(index).timestampMicros(), "picture " + index);
            assertArrayEquals(expected.get(index).picture().pixels(), actual.get(index).picture().pixels());
        }
        assertEquals(3_960_000, Thumbnails.at(MediaSource.of(stripped), 60_000_000).timestampMicros());
    }

    @Test
    @DisplayName("measures the video track of a file whose audio outlasts it")
    void evenlyWithLongerAudio() {
        var file = Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", VIDEO, "-f", "lavfi", "-i", "sine=duration=9"),
                List.of("-c:v", "libx264", "-g", "25", "-pix_fmt", "yuv420p", "-c:a", "flac"));
        var sink = com.github.auties00.smalt.io.MediaSink.memory();
        com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(Fixtures.read(file)), sink,
                com.github.auties00.smalt.transcode.TranscodeOptions.of(
                        com.github.auties00.smalt.transcode.OutputContainer.MATROSKA));
        var stripped = withoutDuration(sink.toByteArray());
        try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(stripped))) {
            assertEquals(-1, reader.durationMicros());
            assertEquals(4_000_000, Thumbnails.measureDuration(MediaSource.of(stripped), reader.track().id(),
                    ThumbnailOptions.defaults()), 1000);
        }
        var times = Thumbnails.evenly(MediaSource.of(stripped), 5, ThumbnailOptions.fitting(80, 80)).stream()
                .mapToLong(Thumbnail::timestampMicros).toArray();
        assertArrayEquals(new long[]{400_000, 1_200_000, 2_000_000, 2_800_000, 3_600_000}, times);
        assertEquals(3_960_000, Thumbnails.at(MediaSource.of(stripped), 60_000_000).timestampMicros(), 1000);
    }

    @Test
    @DisplayName("refuses to measure a source read once")
    void evenlyWithoutDurationOfStream() {
        var file = video("mkv", List.of("-c:v", "libx264", "-g", "25", "-pix_fmt", "yuv420p"));
        var sink = com.github.auties00.smalt.io.MediaSink.memory();
        com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(Fixtures.read(file)), sink,
                com.github.auties00.smalt.transcode.TranscodeOptions.of(
                        com.github.auties00.smalt.transcode.OutputContainer.MATROSKA));
        var stripped = withoutDuration(sink.toByteArray());
        var failure = org.junit.jupiter.api.Assertions.assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class,
                () -> Thumbnails.evenly(MediaSource.of(new java.io.ByteArrayInputStream(stripped), stripped.length), 3));
        assertTrue(failure.getMessage().contains("declares no duration"), failure.getMessage());
        var declared = sink.toByteArray();
        assertEquals(3, Thumbnails.evenly(MediaSource.of(new java.io.ByteArrayInputStream(declared), declared.length), 3)
                .size());
    }

    @Test
    @DisplayName("takes the same pictures from a file on disk as from a source")
    void pathOverloads() {
        var file = video("mp4", List.of("-c:v", "libx264", "-g", "25"));
        var bytes = Fixtures.read(file);
        var options = ThumbnailOptions.fitting(80, 80);
        var fromSource = Thumbnails.at(MediaSource.of(bytes), 1_000_000, options);
        var fromPath = Thumbnails.at(file, 1_000_000, options);
        assertEquals(fromSource.timestampMicros(), fromPath.timestampMicros());
        assertArrayEquals(fromSource.picture().pixels(), fromPath.picture().pixels());
        assertEquals(Thumbnails.at(MediaSource.of(bytes), 0).timestampMicros(), Thumbnails.at(file, 0).timestampMicros());
        var evenlyFromSource = Thumbnails.evenly(MediaSource.of(bytes), 3);
        var evenlyFromPath = Thumbnails.evenly(file, 3);
        assertEquals(evenlyFromSource.size(), evenlyFromPath.size());
        for (var index = 0; index < evenlyFromSource.size(); index++) {
            assertEquals(evenlyFromSource.get(index).timestampMicros(), evenlyFromPath.get(index).timestampMicros());
            assertArrayEquals(evenlyFromSource.get(index).picture().pixels(), evenlyFromPath.get(index).picture().pixels());
        }
        assertEquals(3, Thumbnails.evenly(file, 3, options).size());
    }

    @Test
    @DisplayName("skips a black opening when choosing a representative frame")
    void bestFrame() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "color=c=black:s=160x120:r=25:d=1", "-f", "lavfi", "-i",
                "testsrc2=s=160x120:r=25:d=2"), List.of("-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0", "-c:v", "libx264"));
        var plain = Thumbnails.at(MediaSource.of(Fixtures.read(file)), 0, ThumbnailOptions.defaults());
        assertEquals(0, plain.timestampMicros());
        var best = Thumbnails.at(MediaSource.of(Fixtures.read(file)), 0, ThumbnailOptions.builder().bestFrame(true)
                .build());
        assertEquals(1_000_000, best.timestampMicros());
    }

    @Test
    @DisplayName("scales a still image as ffmpeg does")
    void stillImage() {
        var file = Fixtures.testImage("jpg", 640, 480, List.of("-q:v", "2"));
        var thumbnail = Thumbnails.at(MediaSource.of(Fixtures.read(file)), 0, ThumbnailOptions.fitting(160, 160));
        assertEquals(160, thumbnail.picture().width());
        assertEquals(120, thumbnail.picture().height());
        var quality = psnr(thumbnail.picture(), reference(file, -1, 160, 120));
        assertTrue(quality > 25, "PSNR " + quality);
    }

    static Stream<Arguments> animations() {
        var input = List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=1");
        return Stream.of(
                Arguments.of("gif", Fixtures.ffmpeg("gif", input, List.of())),
                Arguments.of("apng", Fixtures.ffmpeg("apng", input, List.of("-plays", "0", "-f", "apng"))),
                Arguments.of("webp", Fixtures.ffmpeg("webp", input, List.of("-c:v", "libwebp_anim", "-lossless", "1", "-loop", "0"))));
    }

    @Nested
    @DisplayName("an animation reader")
    class Animations {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.preview.ThumbnailsTest#animations")
        @DisplayName("seeks forwards and backwards to the frame displayed at a time")
        void seeks(String name, Path file) {
            var bytes = Fixtures.read(file);
            var frames = new ArrayList<byte[]>();
            var times = new ArrayList<Long>();
            try (var reader = Images.openAnimation(MediaSource.of(bytes))) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    frames.add(((RgbaPicture) frame.picture()).pixels().clone());
                    times.add(frame.timestampMicros());
                }
            }
            assertEquals(10, frames.size(), name);
            try (var reader = Images.openAnimation(MediaSource.of(bytes))) {
                assertTrue(reader.isSeekable(), name);
                for (var micros : new long[]{550_000, 120_000, 999_999, -5, 0, 300_000, 300_000, 950_000}) {
                    reader.seek(micros);
                    var frame = reader.read();
                    var expected = (int) Math.min(9, Math.max(0, micros) / 100_000);
                    assertEquals(times.get(expected), frame.timestampMicros(), name + " at " + micros);
                    assertArrayEquals(frames.get(expected), ((RgbaPicture) frame.picture()).pixels(), name + " pixels at " + micros);
                    if (expected < 9) {
                        assertEquals(times.get(expected + 1), reader.read().timestampMicros(), name + " continues");
                    }
                }
                reader.seek(1_000_000);
                assertNull(reader.read(), name + " past the end");
            }
        }
    }
}
