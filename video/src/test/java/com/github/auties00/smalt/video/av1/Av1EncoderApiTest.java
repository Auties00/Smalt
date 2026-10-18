package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the AV1 presets, builder setters and their validation, the Optional-returning
 * {@code encodeFrame}, the statistics, and the encoder's state, timestamp, color and key frame
 * request contracts.
 */
@DisplayName("AV1 encoder API")
class Av1EncoderApiTest {
    @Nested
    @DisplayName("Av1EncoderOptions presets")
    class Presets {
        @ParameterizedTest
        @ValueSource(ints = {0, 60, 75, 100})
        @DisplayName("ofQuality(q) is good-quality coding at the slow speed and quality q")
        void ofQuality(int quality) {
            var options = Av1EncoderOptions.ofQuality(quality);
            assertEquals(Av1EncoderUsage.GOOD_QUALITY, options.usage());
            assertEquals(Av1EncoderSpeed.SLOW, options.speed());
            assertEquals(OptionalInt.of(quality), options.quality());
        }

        @ParameterizedTest
        @ValueSource(longs = {1, 250_000, 4_000_000})
        @DisplayName("ofBitrate(b) is good-quality coding at the slow speed and bitrate b")
        void ofBitrate(long bitrate) {
            var options = Av1EncoderOptions.ofBitrate(bitrate);
            assertEquals(Av1EncoderUsage.GOOD_QUALITY, options.usage());
            assertEquals(Av1EncoderSpeed.SLOW, options.speed());
            assertEquals(bitrate, options.bitrate());
            assertEquals(OptionalInt.empty(), options.quality());
        }

        @Test
        @DisplayName("the presets reject out-of-range values")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.ofQuality(101));
            assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.ofQuality(-1));
            assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.ofBitrate(0));
        }
    }

    @Nested
    @DisplayName("Av1EncoderOptions.Builder")
    class BuilderSetters {
        @Test
        @DisplayName("keyframeInterval(int) sets the interval and noPeriodicKeyframes() clears it")
        void keyframeInterval() {
            var base = Av1EncoderOptions.forRealtime();
            assertEquals(OptionalInt.of(90), base.toBuilder().keyframeInterval(90).build().keyframeInterval());
            var periodic = base.toBuilder().keyframeInterval(90).build();
            assertEquals(OptionalInt.empty(), periodic.toBuilder().noPeriodicKeyframes().build().keyframeInterval());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().keyframeInterval(0).build());
        }

        @Test
        @DisplayName("the three buffer setters set the buffer model")
        void buffer() {
            var size = Duration.ofMillis(2000);
            var initial = Duration.ofMillis(800);
            var optimal = Duration.ofMillis(1200);
            var actual = Av1EncoderOptions.builder().bufferSize(size).initialBufferLevel(initial)
                    .optimalBufferLevel(optimal).build();
            assertEquals(size, actual.bufferSize());
            assertEquals(initial, actual.initialBufferLevel());
            assertEquals(optimal, actual.optimalBufferLevel());
        }

        @Test
        @DisplayName("the buffer setters reject null and build() rejects levels above the size")
        void bufferValidation() {
            var builder = Av1EncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.bufferSize(null));
            assertThrows(NullPointerException.class, () -> builder.initialBufferLevel(null));
            assertThrows(NullPointerException.class, () -> builder.optimalBufferLevel(null));
            assertThrows(IllegalArgumentException.class,
                    () -> Av1EncoderOptions.builder().bufferSize(Duration.ofMillis(100)).build());
        }

        @Test
        @DisplayName("maxKeyframeBitratePercent(int) sets the limit and noMaxKeyframeBitrate() clears it")
        void maxKeyframeBitrate() {
            var base = Av1EncoderOptions.defaults();
            assertEquals(OptionalInt.of(450),
                    base.toBuilder().maxKeyframeBitratePercent(450).build().maxKeyframeBitratePercent());
            assertEquals(OptionalInt.empty(), Av1EncoderOptions.forRealtime().toBuilder().noMaxKeyframeBitrate()
                    .build().maxKeyframeBitratePercent());
            assertThrows(IllegalArgumentException.class,
                    () -> base.toBuilder().maxKeyframeBitratePercent(99).build());
        }

        @Test
        @DisplayName("quality(int) sets the quality and automaticQuality() clears it")
        void quality() {
            var good = Av1EncoderOptions.builder().usage(Av1EncoderUsage.GOOD_QUALITY);
            assertEquals(OptionalInt.of(42), good.quality(42).build().quality());
            var bitrate = Av1EncoderOptions.forArchive().toBuilder().automaticQuality().build();
            assertEquals(OptionalInt.empty(), bitrate.quality());
        }

        @Test
        @DisplayName("threads(0) is automatic, a count above 64 is lowered to 64, and a negative one is rejected")
        void threads() {
            assertEquals(0, Av1EncoderOptions.builder().threads(0).build().threads());
            assertEquals(64, Av1EncoderOptions.builder().threads(1000).build().threads());
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> Av1EncoderOptions.builder().threads(-1).build());
            assertEquals("thread count -1 is negative", failure.getMessage());
        }

        @Test
        @DisplayName("setters reject null and leave range checks to build()")
        void setters() {
            var builder = Av1EncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.usage(null));
            assertThrows(NullPointerException.class, () -> builder.speed(null));
            assertThrows(NullPointerException.class, () -> builder.pictureFormat(null, 8));
            builder.lookahead(65);
            var failure = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("lookahead 65 is not in [0, 64]", failure.getMessage());
        }

        @Test
        @DisplayName("build() keeps the usage rules for the plain quality setters")
        void qualityRules() {
            assertThrows(IllegalArgumentException.class,
                    () -> Av1EncoderOptions.forIntraOnly(50).toBuilder().automaticQuality().build());
            assertThrows(IllegalArgumentException.class,
                    () -> Av1EncoderOptions.forRealtime().toBuilder().quality(50).build());
            assertThrows(IllegalArgumentException.class,
                    () -> Av1EncoderOptions.ofQuality(50).toBuilder().quality(101).build());
        }
    }

    @Nested
    @DisplayName("Av1Encoder")
    class Encoder {
        @Test
        @DisplayName("the size-only constructor uses defaults()")
        void defaultConstructor() {
            try (var encoder = new Av1Encoder(64, 48)) {
                assertEquals(Av1EncoderOptions.defaults(), encoder.options());
                assertEquals(64, encoder.width());
                assertEquals(48, encoder.height());
            }
            assertThrows(IllegalArgumentException.class, () -> new Av1Encoder(0, 48));
        }

        @Test
        @DisplayName("encodeFrame(frame) returns the packets of encode(frame, output), and requestKeyframe() forces a key frame")
        void optionalEncodeFrame() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 6);
            var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(200_000).build();
            try (var expected = new Av1Encoder(64, 48, options); var actual = new Av1Encoder(64, 48, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    var frame = new VideoFrame(pictures.get(i), i * 3000L, 3000, false);
                    var key = i == 4;
                    if (key) {
                        expected.requestKeyframe();
                        actual.requestKeyframe();
                    }
                    var references = new ArrayList<Packet>();
                    expected.encode(frame, references::add);
                    var reference = references.isEmpty() ? null : references.getFirst();
                    var packet = actual.encodeFrame(frame);
                    assertEquals(reference == null, packet.isEmpty());
                    if (reference != null) {
                        assertArrayEquals(reference.data(), packet.orElseThrow().data());
                        assertEquals(reference.keyframe(), packet.orElseThrow().keyframe());
                        assertEquals(i == 0 || key, packet.orElseThrow().keyframe());
                    }
                }
            }
        }

        @Test
        @DisplayName("encodeFrame(frame) is unavailable in the good-quality usage")
        void goodQualityRejects() {
            var picture = Vp9TestVideo.synthesize(64, 48, 1).getFirst();
            try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.ofQuality(60))) {
                assertThrows(IllegalStateException.class,
                        () -> encoder.encodeFrame(new VideoFrame(picture, 0, 3000, false)));
            }
        }

        @Test
        @DisplayName("the statistics count the frames and key frames and report the last quantizer index")
        void stats() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 3);
            try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.forRealtime())) {
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encodeFrame(new VideoFrame(pictures.get(i), i * 3000L, 3000, false));
                }
                var stats = encoder.stats();
                assertTrue(stats.keyframesEncoded() >= 1);
                assertEquals(3, stats.framesEncoded() + stats.framesDropped());
                assertFalse(stats.lastQuantizerIndex() < 0 || stats.lastQuantizerIndex() > 255);
            }
        }

        @ParameterizedTest
        @EnumSource(Av1EncoderUsage.class)
        @DisplayName("a closed encoder rejects every coding and configuration call")
        void closed(Av1EncoderUsage usage) {
            var options = switch (usage) {
                case REALTIME -> Av1EncoderOptions.forRealtime();
                case GOOD_QUALITY -> Av1EncoderOptions.ofBitrate(200_000);
                case ALL_INTRA -> Av1EncoderOptions.forIntraOnly(60);
            };
            var frame = new VideoFrame(Vp9TestVideo.synthesize(64, 48, 1).getFirst(), 0, 3000, false);
            var encoder = new Av1Encoder(64, 48, options);
            encoder.close();
            assertThrows(IllegalStateException.class, () -> encoder.encode(frame, _ -> {
            }));
            assertThrows(IllegalStateException.class, () -> encoder.flush(_ -> {
            }));
            assertThrows(IllegalStateException.class, () -> encoder.reconfigure(options));
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(300_000));
            encoder.close();
        }

        @Test
        @DisplayName("setTargetBitrate(b) is rejected at a constant quality and applies a bitrate otherwise")
        void targetBitrate() {
            try (var intra = new Av1Encoder(64, 48, Av1EncoderOptions.forIntraOnly(60));
                 var quality = new Av1Encoder(64, 48, Av1EncoderOptions.ofQuality(60));
                 var bitrate = new Av1Encoder(64, 48, Av1EncoderOptions.ofBitrate(200_000));
                 var realtime = new Av1Encoder(64, 48, Av1EncoderOptions.forRealtime())) {
                assertThrows(IllegalStateException.class, () -> intra.setTargetBitrate(300_000));
                assertThrows(IllegalStateException.class, () -> quality.setTargetBitrate(300_000));
                bitrate.setTargetBitrate(300_000);
                realtime.setTargetBitrate(300_000);
                assertEquals(300_000, bitrate.options().bitrate());
                assertEquals(300_000, realtime.options().bitrate());
                assertThrows(IllegalArgumentException.class, () -> realtime.setTargetBitrate(0));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"matrix", "range"})
        @DisplayName("a 4:2:0 picture of another matrix or range is converted to the options' color before coding")
        void convertsColor(String mismatch) {
            var source = Vp9TestVideo.synthesize(64, 48, 1).getFirst();
            var color = mismatch.equals("matrix") ? ColorInfo.BT709_LIMITED
                    : ColorInfo.BT601_LIMITED.withRange(ColorRange.FULL);
            var tagged = retag(source, color);
            var options = Av1EncoderOptions.forRealtime();
            var converted = ImageOps.toYuv(tagged, ChromaFormat.YUV420, options.color());
            try (var actual = new Av1Encoder(64, 48, options); var expected = new Av1Encoder(64, 48, options)) {
                var packet = actual.encodeFrame(new VideoFrame(tagged, 0, 3000, false)).orElseThrow();
                var reference = expected.encodeFrame(new VideoFrame(converted, 0, 3000, false)).orElseThrow();
                assertArrayEquals(reference.data(), packet.data());
            }
        }

        @Test
        @DisplayName("a frame whose timestamp does not follow the last one is rejected and leaves the clock")
        void timestamps() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 2);
            try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.forRealtime())) {
                encoder.encodeFrame(new VideoFrame(pictures.get(0), 3000, 3000, false));
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.encodeFrame(new VideoFrame(pictures.get(1), 3000, 3000, false)));
                var packet = encoder.encodeFrame(new VideoFrame(pictures.get(1), Packet.NO_TIMESTAMP, 0, false));
                assertEquals(6000, packet.orElseThrow().pts());
            }
        }

        @Test
        @DisplayName("a key frame requested from another thread while frames are coded is never lost")
        void concurrentKeyframeRequests() throws InterruptedException {
            var pictures = Vp9TestVideo.synthesize(64, 48, 4);
            var options = Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false).noPeriodicKeyframes()
                    .threads(1).build();
            try (var encoder = new Av1Encoder(64, 48, options)) {
                var coded = new AtomicLong();
                var keyFrames = new ConcurrentLinkedQueue<Long>();
                var stop = new AtomicBoolean();
                var coder = Thread.ofPlatform().start(() -> {
                    for (var i = 0L; !stop.get(); i++) {
                        var frame = new VideoFrame(pictures.get((int) (i % pictures.size())), i * 3000, 3000, false);
                        if (encoder.encodeFrame(frame).orElseThrow().keyframe()) {
                            keyFrames.add(i);
                        }
                        coded.set(i + 1);
                    }
                });
                try {
                    for (var request = 0; request < 40; request++) {
                        var before = coded.get();
                        encoder.requestKeyframe();
                        while (coded.get() < before + 2 && coder.isAlive()) {
                            Thread.onSpinWait();
                        }
                        assertTrue(keyFrames.stream().anyMatch(index -> index >= before),
                                "the request made after frame " + before + " was lost");
                        keyFrames.clear();
                    }
                } finally {
                    stop.set(true);
                    coder.join();
                }
            }
        }

        private static YuvPicture retag(YuvPicture source, ColorInfo color) {
            var picture = YuvPicture.allocate(source.width(), source.height(), source.chromaFormat(), false, color);
            System.arraycopy(source.y(), 0, picture.y(), 0, source.y().length);
            System.arraycopy(source.u(), 0, picture.u(), 0, source.u().length);
            System.arraycopy(source.v(), 0, picture.v(), 0, source.v().length);
            return picture;
        }
    }
}
