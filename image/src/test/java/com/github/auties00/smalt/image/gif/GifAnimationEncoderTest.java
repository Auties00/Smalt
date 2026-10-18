package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the duration-based frame API of the GIF animation encoder and its animation writer
 * factory, by comparing their files with those of the native centisecond API.
 */
@DisplayName("GifAnimationEncoder")
class GifAnimationEncoderTest {
    static RgbaPicture solid(int width, int height, int rgb) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var i = 0; i < width * height; i++) {
            pixels[4 * i] = (byte) (rgb >> 16);
            pixels[4 * i + 1] = (byte) (rgb >> 8);
            pixels[4 * i + 2] = (byte) rgb;
            pixels[4 * i + 3] = (byte) 0xFF;
        }
        return picture;
    }

    static byte[] native3(int... delays) {
        var encoder = new GifAnimationEncoder(8, 8);
        var colors = new int[]{0xFF0000, 0x00FF00, 0x0000FF};
        for (var i = 0; i < delays.length; i++) {
            encoder.addFrame(solid(8, 8, colors[i]), delays[i]);
        }
        return encoder.finish();
    }

    @Nested
    @DisplayName("addFrame(Picture, Duration)")
    class DurationFrames {
        @Test
        @DisplayName("rounds the running end time to hundredths, so 33, 33 and 34 ms become 3, 4 and 3")
        void cumulativeRounding() {
            var encoder = new GifAnimationEncoder(8, 8);
            encoder.addFrame(solid(8, 8, 0xFF0000), Duration.ofMillis(33));
            encoder.addFrame(solid(8, 8, 0x00FF00), Duration.ofMillis(33));
            encoder.addFrame(solid(8, 8, 0x0000FF), Duration.ofMillis(34));
            assertArrayEquals(native3(3, 4, 3), encoder.finish());
        }

        @Test
        @DisplayName("converts a non-RGBA picture to RGBA")
        void convertsYuv() {
            var yuv = YuvPicture.allocate(8, 8, ChromaFormat.YUV420, false, ColorInfo.JPEG);
            var expected = new GifAnimationEncoder(8, 8);
            expected.addFrame(ImageOps.toRgba(yuv), 10);
            var actual = new GifAnimationEncoder(8, 8);
            actual.addFrame(yuv, Duration.ofMillis(100));
            assertArrayEquals(expected.finish(), actual.finish());
        }

        @Test
        @DisplayName("rejects a negative duration, a delay over 65535 hundredths and a finished encoder")
        void rejects() {
            var encoder = new GifAnimationEncoder(8, 8);
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.addFrame(solid(8, 8, 0), Duration.ofMillis(-1)));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.addFrame(solid(8, 8, 0), Duration.ofSeconds(656)));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.addFrame(solid(4, 4, 0), Duration.ofMillis(10)));
            encoder.addFrame(solid(8, 8, 0), Duration.ofMillis(10));
            encoder.finish();
            assertThrows(IllegalStateException.class,
                    () -> encoder.addFrame(solid(8, 8, 0), Duration.ofMillis(10)));
        }
    }

    @Nested
    @DisplayName("openAnimationWriter")
    class Writer {
        @Test
        @DisplayName("writes the file of the native API with the rounded delays on finish")
        void matchesNative() {
            var sink = MediaSink.memory();
            try (var writer = GifAnimationEncoder.openAnimationWriter(8, 8, GifEncoderOptions.defaults(), sink)) {
                writer.write(solid(8, 8, 0xFF0000), 33_000);
                writer.write(solid(8, 8, 0x00FF00), 33_000);
                writer.write(solid(8, 8, 0x0000FF), 34_000);
                writer.finish();
            }
            assertArrayEquals(native3(3, 4, 3), sink.toByteArray());
        }

        @Test
        @DisplayName("rejects a canvas that is not positive")
        void rejectsCanvas() {
            assertThrows(IllegalArgumentException.class,
                    () -> GifAnimationEncoder.openAnimationWriter(0, 8, GifEncoderOptions.defaults(), MediaSink.memory()));
        }
    }
}
