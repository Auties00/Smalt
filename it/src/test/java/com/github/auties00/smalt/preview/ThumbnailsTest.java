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
                    frames.add(frame.picture().pixels().clone());
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
                    assertArrayEquals(frames.get(expected), frame.picture().pixels(), name + " pixels at " + micros);
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
