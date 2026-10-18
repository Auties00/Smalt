package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the APNG encoder: exact round trips through Smalt's APNG decoder and ffmpeg's (which
 * must decode every file without a warning), the color type chosen for the whole file, the
 * disposal and blend operations, exact delay fractions and their merging, the fallback image,
 * the single-frame collapse to a plain PNG, and the registry's animation writer with its
 * drift-free delay approximation.
 */
class ApngEncoderTest {
    record Chunk(String type, byte[] data) {
    }

    static List<Chunk> chunks(byte[] png) {
        var list = new ArrayList<Chunk>();
        var position = 8;
        while (position < png.length) {
            var length = ByteArrays.i32be(png, position);
            var type = new String(png, position + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            list.add(new Chunk(type, Arrays.copyOfRange(png, position + 8, position + 8 + length)));
            position += 12 + length;
        }
        return list;
    }

    static List<Chunk> chunks(byte[] png, String type) {
        return chunks(png).stream().filter(chunk -> chunk.type().equals(type)).toList();
    }

    static int colorType(byte[] png) {
        return png[25];
    }

    static RgbaPicture picture(int width, int height, int[] colors, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        for (var i = 0; i < colors.length; i++) {
            ByteArrays.put32le(picture.pixels(), (i / width) * picture.stride() + (i % width) * 4, colors[i]);
        }
        return picture;
    }

    /** Returns the tight RGBA a decoder shows: pixels of alpha 0 become transparent black. */
    static byte[] shown(RgbaPicture picture) {
        var tight = RasterTesting.tight(picture);
        for (var i = 0; i < tight.length; i += 4) {
            if (!picture.hasAlpha()) {
                tight[i + 3] = (byte) 0xFF;
            } else if (tight[i + 3] == 0) {
                Arrays.fill(tight, i, i + 4, (byte) 0);
            }
        }
        return tight;
    }

    static List<byte[]> decode(byte[] png) {
        try (var reader = PngDecoder.openAnimation(MediaSource.of(png), ImageDecoderOptions.defaults())) {
            var frames = new ArrayList<byte[]>();
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                frames.add(RasterTesting.tight(frame.picture()));
            }
            return frames;
        }
    }

    /** Frames of a sprite over a background, with translucent pixels, holes and repeated frames. */
    static List<RgbaPicture> sprites(int width, int height, int count, int colors, boolean translucent, long seed) {
        var random = new Random(seed);
        var palette = new int[colors];
        for (var i = 0; i < colors; i++) {
            palette[i] = random.nextInt(1 << 24) | (translucent && i % 4 == 0 ? (64 + i % 128) << 24 : 0xFF00_0000);
        }
        var background = new int[width * height];
        for (var i = 0; i < background.length; i++) {
            background[i] = palette[(i / 3 + random.nextInt(2)) % colors];
        }
        var frames = new ArrayList<RgbaPicture>();
        for (var f = 0; f < count; f++) {
            var pixels = background.clone();
            for (var y = 0; y < Math.min(5, height); y++) {
                for (var x = 0; x < Math.min(7, width); x++) {
                    pixels[((f * 2 + y) % height) * width + (f * 3 + x) % width] = palette[(f + x) % colors];
                }
            }
            if (translucent && f % 3 == 2) {
                Arrays.fill(pixels, 0, width * Math.max(1, height / 4), 0);
            }
            frames.add(picture(width, height, pixels, translucent));
        }
        return frames;
    }

    static void assertFfmpegAgrees(byte[] png, List<byte[]> smalt, int width, String name) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = RasterTesting.write(name + ".apng", png);
        Oracle.assertDecodesCleanly(file);
        var raw = RasterTesting.ffmpegFrames(file, "rgba");
        var size = smalt.getFirst().length;
        assertEquals(smalt.size() * size, raw.length, name + ": ffmpeg frame count");
        for (var i = 0; i < smalt.size(); i++) {
            RasterTesting.assertSameVisible(smalt.get(i), Arrays.copyOfRange(raw, i * size, (i + 1) * size), width,
                    name + " frame " + i + " (ffmpeg vs Smalt)");
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0}x{1}, {2} colors, translucent {3}, color type {4}")
        @CsvSource({"1, 1, 2, false, 3", "17, 9, 12, true, 3", "48, 32, 200, true, 3", "64, 40, 3000, false, 2",
                "64, 40, 3000, true, 6", "3, 70, 255, false, 3"})
        @DisplayName("animations decode exactly, in Smalt and in ffmpeg, with the expected color type")
        void exact(int width, int height, int colors, boolean translucent, int type) {
            var source = sprites(width, height, 10, colors, translucent, width * 31L + colors);
            var encoder = new ApngEncoder(width, height, ApngEncoderOptions.builder().loopCount(4).build());
            for (var frame : source) {
                encoder.addFrame(frame, 1, 25);
            }
            var png = encoder.finish();
            assertEquals(type, colorType(png));
            var frames = decode(png);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                RasterTesting.assertSameVisible(shown(source.get(i)), frames.get(i), width, "frame " + i);
            }
            var actl = chunks(png, "acTL").getFirst().data();
            assertEquals(10, ByteArrays.i32be(actl, 0));
            assertEquals(4, ByteArrays.i32be(actl, 4));
            try (var reader = PngDecoder.openAnimation(MediaSource.of(png))) {
                assertEquals(4, reader.loopCount());
            }
            assertFfmpegAgrees(png, frames, width, "apng-" + width + "x" + height + "-" + colors + "-" + translucent);
        }

        @Test
        @DisplayName("frames are coded as changed rectangles with both blend operations where they pay")
        void rectangles() {
            var source = sprites(96, 64, 8, 40, false, 9);
            var encoder = new ApngEncoder(96, 64);
            for (var frame : source) {
                encoder.addFrame(frame, 1, 10);
            }
            var png = encoder.finish();
            var controls = chunks(png, "fcTL").stream().map(chunk -> ApngFrameControl.parse(chunk.data())).toList();
            assertEquals(96, controls.getFirst().width());
            assertTrue(controls.stream().skip(1).allMatch(control -> control.width() * control.height() < 96 * 64 / 4),
                    controls.toString());
            assertTrue(controls.stream().anyMatch(control -> control.blend() == ApngFrameControl.BLEND_OVER),
                    controls.toString());
        }

        @Test
        @DisplayName("frames that clear their area choose the background disposal")
        void disposal() {
            var blank = new int[20 * 20];
            var a = blank.clone();
            Arrays.fill(a, 0, 200, 0xFF00_00FF);
            var b = blank.clone();
            Arrays.fill(b, 200, 400, 0xFFFF_0000);
            var encoder = new ApngEncoder(20, 20);
            encoder.addFrame(picture(20, 20, a, true), 1, 10);
            encoder.addFrame(picture(20, 20, b, true), 1, 10);
            encoder.addFrame(picture(20, 20, a, true), 1, 10);
            var png = encoder.finish();
            var frames = decode(png);
            RasterTesting.assertSameVisible(shown(picture(20, 20, b, true)), frames.get(1), 20, "frame 1");
            RasterTesting.assertSameVisible(shown(picture(20, 20, a, true)), frames.get(2), 20, "frame 2");
            assertFfmpegAgrees(png, frames, 20, "apng-dispose");
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {
        @Test
        @DisplayName("delays are stored as exact fractions and identical frames merge them")
        void delays() {
            var source = sprites(8, 8, 3, 5, false, 3);
            var encoder = new ApngEncoder(8, 8);
            encoder.addFrame(source.get(0), 1, 30);
            encoder.addFrame(source.get(0), 1, 20);
            encoder.addFrame(source.get(1), 1001, 30000);
            encoder.addFrame(source.get(1), 65_535, 1);
            encoder.addFrame(source.get(2), 0, 1);
            var controls = chunks(encoder.finish(), "fcTL").stream().map(chunk -> ApngFrameControl.parse(chunk.data())).toList();
            assertEquals(List.of("1/12", "1001/30000", "65535/1", "0/1"),
                    controls.stream().map(control -> control.delayNumerator() + "/" + control.delayDenominator()).toList());
        }

        @Test
        @DisplayName("a fallback image is stored as a default image outside the animation")
        void fallback() {
            var still = picture(6, 4, new int[24], false);
            var source = sprites(6, 4, 3, 7, false, 5);
            var encoder = new ApngEncoder(6, 4);
            encoder.setFallbackImage(still);
            for (var frame : source) {
                encoder.addFrame(frame, 1, 10);
            }
            var png = encoder.finish();
            var order = chunks(png).stream().map(Chunk::type).toList();
            assertTrue(order.indexOf("IDAT") < order.indexOf("fcTL"), order.toString());
            assertEquals(3, decode(png).size());
            RasterTesting.assertSameRgba(shown(still), RasterTesting.tight(
                    (RgbaPicture) PngDecoder.decode(MediaSource.of(png)).picture()), 6, "default image");
            assertFfmpegAgrees(png, decode(png), 6, "apng-fallback");
        }

        @Test
        @DisplayName("a single frame without a fallback image is a plain PNG")
        void single() {
            var frame = sprites(5, 5, 1, 4, true, 2).getFirst();
            var encoder = new ApngEncoder(5, 5);
            encoder.addFrame(frame, 1, 10);
            encoder.addFrame(frame, 1, 10);
            var png = encoder.finish();
            assertTrue(chunks(png, "acTL").isEmpty());
            RasterTesting.assertSameVisible(shown(frame), decode(png).getFirst(), 5, "still");
        }

        @Test
        @DisplayName("misuse is rejected")
        void misuse() {
            var encoder = new ApngEncoder(2, 2);
            assertThrows(IllegalStateException.class, encoder::finish);
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(2, 3, false), 1,
                    1));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(2, 2, false), 1,
                    0));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(2, 2, false),
                    65_536, 1));
            encoder.addFrame(RgbaPicture.allocate(2, 2, false), 1, 1);
            assertThrows(IllegalStateException.class, () -> encoder.setFallbackImage(RgbaPicture.allocate(2, 2,
                    false)));
            encoder.finish();
            assertThrows(IllegalStateException.class, encoder::finish);
            assertThrows(IllegalArgumentException.class, () -> new ApngEncoder(0, 2));
            assertThrows(IllegalArgumentException.class, () -> ApngEncoderOptions.builder().loopCount(-1).build());
            assertThrows(IllegalArgumentException.class, () -> ApngEncoderOptions.builder().compressionLevel(10)
                    .build());
        }

        @Test
        @DisplayName("options follow the immutable options convention")
        void options() {
            var options = ApngEncoderOptions.builder().compressionLevel(9).filter(PngEncoderOptions.Filter.PAETH)
                    .loopCount(3).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(ApngEncoderOptions.builder().compressionLevel(9).filter(PngEncoderOptions.Filter.PAETH)
                    .loopCount(3).build(), options);
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertFalse(options.equals(ApngEncoderOptions.defaults()));
        }
    }

    @Nested
    @DisplayName("registry animation writer")
    class Writer {
        @Test
        @DisplayName("writes APNG with exact millisecond delays")
        void milliseconds() {
            var source = sprites(16, 12, 4, 30, true, 8);
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.PNG, 16, 12, ImageEncoderOptions.defaults()
                    .toBuilder().loopCount(2).build(), sink)) {
                for (var frame : source) {
                    writer.write(frame, 40_000);
                }
                writer.finish();
            }
            var png = sink.toByteArray();
            var controls = chunks(png, "fcTL").stream().map(chunk -> ApngFrameControl.parse(chunk.data())).toList();
            assertTrue(controls.stream().allMatch(control -> control.delayNumerator() == 1 && control.delayDenominator() == 25),
                    controls.toString());
            assertEquals(4, decode(png).size());
        }

        @ParameterizedTest(name = "{0} us per frame")
        @ValueSource(longs = {33_333, 41_708, 1, 123_456_789})
        @DisplayName("approximates other durations without drift")
        void drift(long micros) {
            var sink = MediaSink.memory();
            var frames = 50;
            try (var writer = Images.openAnimationWriter(ImageFormat.PNG, 2, 1, sink)) {
                for (var i = 0; i < frames; i++) {
                    writer.write(picture(2, 1, new int[]{0xFF00_0000 | i, 0xFFFF_FFFF}, false), micros);
                }
                writer.finish();
            }
            var total = 0.0;
            var controls = chunks(sink.toByteArray(), "fcTL").stream().map(chunk -> ApngFrameControl.parse(chunk.data())).toList();
            assertEquals(frames, controls.size());
            for (var i = 0; i < controls.size(); i++) {
                total += (double) controls.get(i).delayNumerator() / controls.get(i).delayDenominator();
                assertEquals((i + 1) * micros / 1e6, total, 1.0 / 65_535 + (i + 1) * micros * 1e-15,
                        "end time of frame " + i);
            }
        }
    }

    @Nested
    @DisplayName("duration frames and openAnimationWriter")
    class DurationFrames {
        static byte[] nativeFile(int[][] delays) {
            var encoder = new ApngEncoder(2, 1);
            for (var i = 0; i < delays.length; i++) {
                encoder.addFrame(picture(2, 1, new int[]{0xFF00_0000 | i * 40, 0xFFFF_FFFF}, false), delays[i][0],
                        delays[i][1]);
            }
            return encoder.finish();
        }

        @Test
        @DisplayName("addFrame(Picture, Duration) stores an exact fraction as the native call would")
        void exact() {
            var encoder = new ApngEncoder(2, 1);
            encoder.addFrame(picture(2, 1, new int[]{0xFF00_0000, 0xFFFF_FFFF}, false), Duration.ofMillis(40));
            encoder.addFrame(picture(2, 1, new int[]{0xFF00_0028, 0xFFFF_FFFF}, false), Duration.ofMillis(250));
            assertArrayEquals(nativeFile(new int[][]{{1, 25}, {1, 4}}), encoder.finish());
        }

        @Test
        @DisplayName("openAnimationWriter writes the file of the native calls on finish")
        void writer() {
            var sink = MediaSink.memory();
            try (var writer = ApngEncoder.openAnimationWriter(2, 1, ApngEncoderOptions.defaults(), sink)) {
                writer.write(picture(2, 1, new int[]{0xFF00_0000, 0xFFFF_FFFF}, false), 40_000);
                writer.write(picture(2, 1, new int[]{0xFF00_0028, 0xFFFF_FFFF}, false), 250_000);
                writer.finish();
            }
            assertArrayEquals(nativeFile(new int[][]{{1, 25}, {1, 4}}), sink.toByteArray());
        }

        @Test
        @DisplayName("addFrame(Picture, Duration) rejects a negative duration and one over 65535 seconds")
        void rejects() {
            var encoder = new ApngEncoder(2, 1);
            var frame = picture(2, 1, new int[]{0xFF00_0000, 0xFFFF_FFFF}, false);
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(frame, Duration.ofNanos(-1)));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(frame, Duration.ofSeconds(65_536)));
        }
    }
}
