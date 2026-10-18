package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the key frame accessors and builder setters of the animation options, and the
 * duration-based frame API and animation writer factory of the WebP animation encoder, by
 * comparing their files with those of the native millisecond API.
 */
@DisplayName("WebpAnimationEncoder")
class WebpAnimationEncoderTest {
    static RgbaPicture solid(int rgb) {
        var picture = RgbaPicture.allocate(8, 8, false);
        var pixels = picture.pixels();
        for (var i = 0; i < 64; i++) {
            pixels[4 * i] = (byte) (rgb >> 16);
            pixels[4 * i + 1] = (byte) (rgb >> 8);
            pixels[4 * i + 2] = (byte) rgb;
            pixels[4 * i + 3] = (byte) 0xFF;
        }
        return picture;
    }

    static byte[] nativeFile(int... durations) {
        var encoder = new WebpAnimationEncoder(8, 8);
        var colors = new int[]{0xFF0000, 0x00FF00, 0x0000FF};
        for (var i = 0; i < durations.length; i++) {
            encoder.addFrame(solid(colors[i]), durations[i]);
        }
        return encoder.finish();
    }

    @Nested
    @DisplayName("key frame options")
    class Keyframes {
        @Test
        @DisplayName("keyframes(min, max) sets the distances, which then ignore the coding mode")
        void explicit() {
            var options = WebpAnimationEncoderOptions.builder().keyframes(4, 12).build();
            assertEquals(4, options.minKeyframeDistance());
            assertEquals(12, options.maxKeyframeDistance());
            var lossy = options.toBuilder().lossless(false).build();
            assertEquals(4, lossy.minKeyframeDistance());
            assertEquals(12, lossy.maxKeyframeDistance());
        }

        @Test
        @DisplayName("noKeyframes() sets both distances to 0")
        void none() {
            var options = WebpAnimationEncoderOptions.lossy(50).toBuilder().noKeyframes().build();
            assertEquals(0, options.minKeyframeDistance());
            assertEquals(0, options.maxKeyframeDistance());
            assertEquals(WebpAnimationEncoderOptions.lossy(50).toBuilder().keyframes(0, 0).build(), options);
        }

        @Test
        @DisplayName("unset distances follow the coding mode, also through toBuilder()")
        void followMode() {
            assertEquals(9, WebpAnimationEncoderOptions.defaults().minKeyframeDistance());
            assertEquals(17, WebpAnimationEncoderOptions.defaults().maxKeyframeDistance());
            assertEquals(3, WebpAnimationEncoderOptions.lossy(75).minKeyframeDistance());
            assertEquals(5, WebpAnimationEncoderOptions.lossy(75).maxKeyframeDistance());
            var lossy = WebpAnimationEncoderOptions.defaults().toBuilder().lossless(false).build();
            assertEquals(WebpAnimationEncoderOptions.lossy(75), lossy);
            assertEquals(3, lossy.minKeyframeDistance());
            assertEquals(5, lossy.maxKeyframeDistance());
            var back = WebpAnimationEncoderOptions.lossy(75).toBuilder().keyframes(2, 4).automaticKeyframes()
                    .lossless(true).build();
            assertEquals(WebpAnimationEncoderOptions.lossless(), back);
        }
    }

    @Nested
    @DisplayName("addFrame(Picture, Duration) and openAnimationWriter")
    class DurationFrames {
        @Test
        @DisplayName("rounds the running end time to milliseconds, so 1.4, 1.4 and 1.4 ms become 1, 2 and 1")
        void cumulativeRounding() {
            var encoder = new WebpAnimationEncoder(8, 8);
            encoder.addFrame(solid(0xFF0000), Duration.ofNanos(1_400_000));
            encoder.addFrame(solid(0x00FF00), Duration.ofNanos(1_400_000));
            encoder.addFrame(solid(0x0000FF), Duration.ofNanos(1_400_000));
            assertArrayEquals(nativeFile(1, 2, 1), encoder.finish());
        }

        @Test
        @DisplayName("openAnimationWriter writes the file of the native calls on finish")
        void writer() {
            var sink = MediaSink.memory();
            try (var writer = WebpAnimationEncoder.openAnimationWriter(8, 8, WebpAnimationEncoderOptions.defaults(),
                    sink)) {
                writer.write(solid(0xFF0000), 100_000);
                writer.write(solid(0x00FF00), 250_000);
                writer.finish();
            }
            assertArrayEquals(nativeFile(100, 250), sink.toByteArray());
        }

        @Test
        @DisplayName("rejects a negative duration")
        void rejects() {
            var encoder = new WebpAnimationEncoder(8, 8);
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(solid(0), Duration.ofMillis(-5)));
        }
    }
}
