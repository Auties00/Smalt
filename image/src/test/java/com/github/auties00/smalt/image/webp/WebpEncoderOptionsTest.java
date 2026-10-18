package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on {@link WebpEncoderOptions} and
 * {@link WebpAnimationEncoderOptions}, the encoder entry points that take no options, and the
 * animation writer that {@link Images} opens for WebP.
 */
@DisplayName("WebP encoder options")
class WebpEncoderOptionsTest {
    private static RgbaPicture frame(int seed) {
        var picture = RgbaPicture.allocate(24, 16, true);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            var pixel = i / 4;
            pixels[i] = (byte) (pixel * 5 + seed * 40);
            pixels[i + 1] = (byte) (pixel >> 1);
            pixels[i + 2] = (byte) (seed * 70);
            pixels[i + 3] = (byte) (pixel % 24 < 4 ? 0 : 255);
        }
        return picture;
    }

    @Nested
    @DisplayName("WebpEncoderOptions")
    class Still {
        @Test
        @DisplayName("defaults are cwebp's: lossy quality 75, method 4, exact alpha, fast alpha filter")
        void defaults() {
            var defaults = WebpEncoderOptions.defaults();
            assertFalse(defaults.isLossless());
            assertEquals(75, defaults.quality());
            assertEquals(4, defaults.method());
            assertEquals(100, defaults.alphaQuality());
            assertEquals(WebpEncoderOptions.AlphaFilter.FAST, defaults.alphaFilter());
            assertFalse(defaults.exact());
            assertEquals(OptionalInt.empty(), defaults.targetSize());
            assertFalse(defaults.extendedFormat());
            assertEquals(defaults, WebpEncoderOptions.lossy(75));
            assertEquals(defaults, WebpEncoderOptions.builder().build());
        }

        @Test
        @DisplayName("the lossless preset is cwebp -lossless: effort 75, method 4")
        void lossless() {
            var lossless = WebpEncoderOptions.lossless();
            assertTrue(lossless.isLossless());
            assertEquals(75, lossless.quality());
            assertEquals(WebpEncoderOptions.builder().lossless(true).build(), lossless);
        }

        @Test
        @DisplayName("withers, builders and value equality")
        void convention() {
            var base = WebpEncoderOptions.lossy(80);
            var changed = base.toBuilder().method(6).alphaQuality(50).alphaFilter(WebpEncoderOptions.AlphaFilter.BEST)
                    .exact(true).targetSize(4096).extendedFormat(true).build();
            assertEquals(4, base.method());
            assertEquals(OptionalInt.of(4096), changed.targetSize());
            assertEquals(OptionalInt.empty(), changed.toBuilder().noTargetSize().build().targetSize());
            var built = WebpEncoderOptions.builder().quality(80).method(6).alphaQuality(50)
                    .alphaFilter(WebpEncoderOptions.AlphaFilter.BEST).exact(true).targetSize(4096).extendedFormat(true)
                    .build();
            assertEquals(changed, built);
            assertEquals(changed.hashCode(), built.hashCode());
            assertEquals(built, built.toBuilder().build());
            assertEquals(base, built.toBuilder().method(4).alphaQuality(100).alphaFilter(WebpEncoderOptions.AlphaFilter.FAST)
                    .exact(false).noTargetSize().extendedFormat(false).build());
            assertNotEquals(base, changed);
        }

        @Test
        @DisplayName("rejects values out of range")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossy(-1));
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossy(101));
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.builder().method(7).build());
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.builder().alphaQuality(101).build());
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.builder().targetSize(0).build());
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.builder().targetSize(-5).build());
            assertThrows(NullPointerException.class, () -> WebpEncoderOptions.builder().alphaFilter(null).build());
        }

        @Test
        @DisplayName("encode without options and encode to a sink write the bytes of the defaults")
        void entryPoints() {
            var picture = frame(1);
            var expected = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.defaults()));
            assertArrayEquals(expected, WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture)));
            var sink = MediaSink.memory();
            WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.defaults(), sink));
            assertArrayEquals(expected, sink.toByteArray());
        }
    }

    @Nested
    @DisplayName("WebpAnimationEncoderOptions")
    class Animation {
        @Test
        @DisplayName("defaults are gif2webp's lossless settings; the lossy preset keys every 3 to 5 frames")
        void presets() {
            var defaults = WebpAnimationEncoderOptions.defaults();
            assertEquals(WebpAnimationEncoderOptions.lossless(), defaults);
            assertTrue(defaults.isLossless());
            assertEquals(0, defaults.loopCount());
            assertEquals(75, defaults.quality());
            assertEquals(4, defaults.method());
            assertEquals(9, defaults.minKeyframeDistance());
            assertEquals(17, defaults.maxKeyframeDistance());
            assertFalse(defaults.allowMixed());
            assertFalse(defaults.minimize());
            var lossy = WebpAnimationEncoderOptions.lossy(60);
            assertFalse(lossy.isLossless());
            assertEquals(60, lossy.quality());
            assertEquals(3, lossy.minKeyframeDistance());
            assertEquals(5, lossy.maxKeyframeDistance());
        }

        @Test
        @DisplayName("withers, builders and value equality")
        void convention() {
            var changed = WebpAnimationEncoderOptions.lossy(60).toBuilder().loopCount(3).method(6).keyframes(2, 8)
                    .allowMixed(true).minimize(true).build();
            var built = WebpAnimationEncoderOptions.builder().lossless(false).quality(60).loopCount(3).method(6)
                    .keyframes(2, 8).allowMixed(true).minimize(true).build();
            assertEquals(changed, built);
            assertEquals(changed.hashCode(), built.hashCode());
            assertEquals(built, built.toBuilder().build());
            assertEquals(0, changed.toBuilder().noKeyframes().build().maxKeyframeDistance());
            assertTrue(changed.toBuilder().lossless(true).build().isLossless());
            assertNotEquals(WebpAnimationEncoderOptions.lossy(60), changed);
        }

        @ParameterizedTest(name = "loop count {0}")
        @ValueSource(ints = {-1, 65536})
        @DisplayName("rejects a loop count outside 0..65535")
        void rejectsLoopCount(int loopCount) {
            assertThrows(IllegalArgumentException.class, () -> WebpAnimationEncoderOptions.builder()
                    .loopCount(loopCount).build());
        }

        @Test
        @DisplayName("rejects other values out of range")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> WebpAnimationEncoderOptions.lossy(101));
            assertThrows(IllegalArgumentException.class, () -> WebpAnimationEncoderOptions.builder().method(-1)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> WebpAnimationEncoderOptions.builder().keyframes(-1, 3)
                    .build());
        }

        @Test
        @DisplayName("an encoder without options uses the defaults")
        void defaultEncoder() {
            var expected = new WebpAnimationEncoder(24, 16, WebpAnimationEncoderOptions.defaults());
            var implicit = new WebpAnimationEncoder(24, 16);
            for (var i = 0; i < 3; i++) {
                expected.addFrame(frame(i), 50);
                implicit.addFrame(frame(i), 50);
            }
            assertArrayEquals(expected.finish(), implicit.finish());
        }
    }

    @Nested
    @DisplayName("Images.openAnimationWriter")
    class Writer {
        private byte[] direct(WebpAnimationEncoderOptions options, int... durations) {
            var encoder = new WebpAnimationEncoder(24, 16, options);
            for (var i = 0; i < durations.length; i++) {
                encoder.addFrame(frame(i), durations[i]);
            }
            return encoder.finish();
        }

        @Test
        @DisplayName("writes the bytes of the WebP encoder at the mapped settings")
        void sameBytes() {
            assertTrue(Images.isAnimationEncodable(ImageFormat.WEBP));
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, 24, 16, sink)) {
                assertEquals(24, writer.width());
                assertEquals(16, writer.height());
                for (var i = 0; i < 3; i++) {
                    writer.write(frame(i), 40_000);
                }
                writer.finish();
            }
            var expected = WebpTestSupport.withVp8(() -> direct(WebpAnimationEncoderOptions.lossy(75), 40, 40, 40));
            assertArrayEquals(expected, sink.toByteArray());
            var lossless = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, 24, 16,
                    ImageEncoderOptions.lossless().toBuilder().loopCount(2).build(), lossless)) {
                writer.write(new AnimationFrame(frame(0), 0, 30_000));
                writer.write(new AnimationFrame(frame(1), 30_000, 30_000));
                writer.finish();
            }
            assertArrayEquals(direct(WebpAnimationEncoderOptions.lossless().toBuilder().loopCount(2).build(), 30, 30),
                    lossless.toByteArray());
        }

        @Test
        @DisplayName("rounds the running end time, so durations never drift")
        void rounding() {
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, 24, 16, ImageEncoderOptions.lossless(), sink)) {
                for (var i = 0; i < 4; i++) {
                    writer.write(frame(i), 33_333);
                }
                writer.finish();
            }
            assertArrayEquals(direct(WebpAnimationEncoderOptions.lossless(), 33, 34, 33, 33), sink.toByteArray());
            var durations = new ArrayList<Integer>();
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(sink.toByteArray()))) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    durations.add(frame.durationMillis());
                }
            }
            assertEquals(List.of(33, 34, 33, 33), durations);
        }

        @Test
        @DisplayName("rejects formats without an animation encoder, bad sizes and misuse")
        void rejects() {
            assertFalse(Images.isAnimationEncodable(ImageFormat.JPEG));
            var unsupported = assertThrows(MediaException.Unsupported.class,
                    () -> Images.openAnimationWriter(ImageFormat.JPEG, 24, 16, MediaSink.memory()));
            assertEquals("jpeg", unsupported.format());
            assertThrows(IllegalArgumentException.class,
                    () -> Images.openAnimationWriter(ImageFormat.WEBP, 0, 16, MediaSink.memory()));
            assertThrows(IllegalArgumentException.class, () -> Images.openAnimationWriter(ImageFormat.WEBP, 24, 16,
                    ImageEncoderOptions.builder().loopCount(70_000).build(), MediaSink.memory()));
            try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, 24, 16, ImageEncoderOptions.lossless(),
                    MediaSink.memory())) {
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalArgumentException.class, () -> writer.write(RgbaPicture.allocate(8, 8, false),
                        1000));
                assertThrows(IllegalArgumentException.class, () -> writer.write(frame(0), -1));
                writer.write(frame(0), 1000);
                writer.finish();
                assertThrows(IllegalStateException.class, () -> writer.write(frame(1), 1000));
            }
        }
    }
}
