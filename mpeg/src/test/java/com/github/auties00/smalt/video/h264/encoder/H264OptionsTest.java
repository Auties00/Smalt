package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on the H.264 options types: presets, builders that leave the
 * original untouched and round-trip through {@code toBuilder()}, value equality, validation, and
 * how the encoder resolves the optional rate settings.
 */
@DisplayName("H.264 options")
class H264OptionsTest {
    private static final Rational FPS = new Rational(30, 1);

    @Nested
    @DisplayName("H264EncoderOptions")
    class Encoder {
        @Test
        @DisplayName("forRealtime() is Constrained Baseline CBR in Annex B with a half-second buffer")
        void realtime() {
            var options = H264EncoderOptions.forRealtime();
            assertEquals(H264Profile.CONSTRAINED_BASELINE, options.profile());
            assertEquals(H264RateControl.CONSTANT_BITRATE, options.rateControl());
            assertEquals(H264EncoderSpeed.FASTEST, options.speed());
            assertEquals(H264PacketFormat.ANNEX_B, options.packetFormat());
            assertEquals(Optional.of(Duration.ofMillis(500)), options.bufferSize());
            assertEquals(OptionalLong.empty(), options.maxBitrate());
            assertEquals(OptionalInt.empty(), options.keyframeInterval());
            assertEquals(Optional.empty(), options.level());
            assertEquals(new Rational(1, 90_000), options.timeBase());
            assertEquals(ColorInfo.BT601_LIMITED, options.color());
            assertEquals(12, options.minQuantizer());
            assertTrue(options.realtime());
        }

        @Test
        @DisplayName("defaults() is forFile(): Main profile at an average bitrate with a one-second buffer")
        void file() {
            var options = H264EncoderOptions.defaults();
            assertEquals(H264EncoderOptions.forFile(), options);
            assertEquals(options, H264EncoderOptions.builder().build());
            assertEquals(H264Profile.MAIN, options.profile());
            assertEquals(H264RateControl.AVERAGE_BITRATE, options.rateControl());
            assertEquals(1_000_000, options.bitrate());
            assertEquals(Optional.of(Duration.ofSeconds(1)), options.bufferSize());
            assertEquals(OptionalInt.of(60), options.keyframeInterval());
            assertEquals(H264PacketFormat.LENGTH_PREFIXED, options.packetFormat());
            assertEquals(MediaLimits.DEFAULT, options.limits());
            assertFalse(options.realtime());
        }

        @Test
        @DisplayName("the builder changes settings and leaves the original untouched")
        void changes() {
            var base = H264EncoderOptions.forFile();
            var changed = base.toBuilder().frameRate(new Rational(25, 1))
                    .timeBase(new Rational(1, 25))
                    .profile(H264Profile.HIGH)
                    .level(H264Level.LEVEL_3_1)
                    .rateControl(H264RateControl.CONSTANT_BITRATE)
                    .bitrate(700_000)
                    .maxBitrate(900_000)
                    .bufferSize(Duration.ofMillis(250))
                    .quality(30)
                    .keyframeInterval(12)
                    .speed(H264EncoderSpeed.SLOW)
                    .color(ColorInfo.BT601_LIMITED)
                    .threads(3)
                    .packetFormat(H264PacketFormat.ANNEX_B)
                    .quantizers(10, 40)
                    .realtime(true)
                    .limits(MediaLimits.builder().maxDimension(1024).build()).build();
            assertEquals(H264EncoderOptions.forFile(), base);
            assertEquals(Optional.of(H264Level.LEVEL_3_1), changed.level());
            assertEquals(OptionalLong.of(900_000), changed.maxBitrate());
            assertEquals(Optional.of(Duration.ofMillis(250)), changed.bufferSize());
            assertEquals(30, changed.quality());
            assertEquals(3, changed.threads());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base, changed);
            var reset = changed.toBuilder().automaticLevel().automaticMaxBitrate().automaticBufferSize().build();
            assertEquals(Optional.empty(), reset.level());
            assertEquals(OptionalLong.empty(), reset.maxBitrate());
            assertEquals(Optional.empty(), reset.bufferSize());
        }

        @Test
        @DisplayName("rejects values outside their ranges")
        void validation() {
            var options = H264EncoderOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(1L << 31).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().maxBitrate(500_000).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().maxBitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bufferSize(Duration.ZERO).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().quality(52).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().keyframeInterval(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().threads(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().threads(65).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().quantizers(30, 20).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().quantizers(0, 52).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().frameRate(new Rational(2000, 1)).build());
            for (var matrix : MatrixCoefficients.values()) {
                var color = ColorInfo.BT709_LIMITED.withMatrix(matrix);
                if (matrix == MatrixCoefficients.IDENTITY) {
                    // the identity matrix needs 4:4:4, which the encoder does not code
                    assertThrows(IllegalArgumentException.class, () -> options.toBuilder().color(color).build());
                } else {
                    assertEquals(color, options.toBuilder().color(color).build().color());
                }
            }
            assertThrows(NullPointerException.class, () -> options.toBuilder().level(null).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().bufferSize(null).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().speed(null).build());
            assertEquals(H264RateControl.CONSTANT_QUALITY,
                    options.toBuilder().rateControl(H264RateControl.CONSTANT_QUALITY).maxBitrate(1).build().rateControl());
        }

        @Test
        @DisplayName("resolves the default maximum bitrate and buffer of each rate control mode")
        void resolution() {
            var abr = new H264Setup(320, 240, H264EncoderOptions.forFile().toBuilder().bitrate(400_000).automaticBufferSize().build());
            assertEquals(800_000, abr.effectiveMaxBitrate());
            assertEquals(1_600_000, abr.effectiveBufferSize());
            var file = new H264Setup(320, 240, H264EncoderOptions.forFile().toBuilder().bitrate(400_000).build());
            assertEquals(800_000, file.effectiveBufferSize());
            var cbr = new H264Setup(320, 240, H264EncoderOptions.forRealtime().toBuilder().bitrate(350_001).build());
            assertEquals(350_001, cbr.effectiveMaxBitrate());
            assertEquals(175_000, cbr.effectiveBufferSize());
            var cq = new H264Setup(320, 240, H264EncoderOptions.defaults()
                    .toBuilder().rateControl(H264RateControl.CONSTANT_QUALITY).build());
            assertEquals(0, cq.resolved().bitrate());
            assertEquals(0, cq.effectiveBufferSize());
            var explicit = H264Setup.of(320, 240, FPS, FPS.invert(), H264Profile.MAIN, 0,
                    H264RateControl.AVERAGE_BITRATE, 300_000, 300_000, 60_001, 23, 30, H264EncoderSpeed.FAST,
                    ColorInfo.BT709_LIMITED, 1);
            assertEquals(60_001, explicit.effectiveBufferSize());
        }

        @ParameterizedTest
        @EnumSource(H264Level.class)
        @DisplayName("every level is one the encoder knows")
        void levels(H264Level level) {
            var options = H264EncoderOptions.builder().rateControl(H264RateControl.CONSTANT_QUALITY)
                    .frameRate(new Rational(1, 1)).level(level).build();
            try (var encoder = new H264Encoder(16, 16, options)) {
                assertEquals(16, encoder.width());
            }
        }

        @Test
        @DisplayName("the encoder checks the picture size against the level and the limits")
        void pictureSize() {
            assertThrows(IllegalArgumentException.class, () -> new H264Encoder(0, 16, H264EncoderOptions.defaults()));
            assertThrows(IllegalArgumentException.class,
                    () -> new H264Encoder(1920, 1080, H264EncoderOptions.builder().level(H264Level.LEVEL_2_1).build()));
            assertThrows(MediaException.LimitExceeded.class, () -> new H264Encoder(640, 480,
                    H264EncoderOptions.builder().limits(MediaLimits.builder().maxDimension(320).build()).build()));
            try (var encoder = new H264Encoder(33, 17, H264EncoderOptions.forRealtime())) {
                assertEquals(H264EncoderOptions.forRealtime(), encoder.options());
                assertEquals(Codec.H264, encoder.codec());
            }
        }

        @Test
        @DisplayName("setTargetBitrate accepts the int range and rejects constant quality")
        void targetBitrate() {
            try (var encoder = new H264Encoder(32, 32, H264EncoderOptions.forRealtime())) {
                encoder.setTargetBitrate(300_000L);
                assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
                assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(1L << 31));
            }
            try (var encoder = new H264Encoder(32, 32,
                    H264EncoderOptions.builder().rateControl(H264RateControl.CONSTANT_QUALITY).build())) {
                assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(300_000));
            }
        }
    }

    @Nested
    @DisplayName("H264DecoderOptions")
    class Decoder {
        @Test
        @DisplayName("defaults, presets, builders and equality follow the options convention")
        void convention() {
            var defaults = H264DecoderOptions.defaults();
            assertEquals(MediaLimits.DEFAULT, defaults.limits());
            assertEquals(0, defaults.threads());
            assertFalse(defaults.realtime());
            var realtime = H264DecoderOptions.forRealtime();
            assertTrue(realtime.realtime());
            assertEquals(1, realtime.threads());
            assertEquals(realtime, defaults.toBuilder().realtime(true).threads(1).build());
            var small = MediaLimits.builder().maxDimension(64).build();
            var changed = defaults.toBuilder().limits(small).threads(4).build();
            assertEquals(changed, H264DecoderOptions.builder().limits(small).threads(4).build());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(defaults, changed);
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().threads(-1).build());
            assertThrows(IllegalArgumentException.class, () -> realtime.toBuilder().threads(2).build());
            assertThrows(IllegalArgumentException.class, () -> changed.toBuilder().realtime(true).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().limits(null).build());
        }

        @Test
        @DisplayName("a real-time decoder outputs each access unit's picture before decode returns")
        void lowDelay() {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).width(32)
                    .height(32).build();
            try (var encoder = new H264Encoder(32, 32, H264EncoderOptions.forRealtime());
                 var decoder = new H264Decoder(track, H264DecoderOptions.forRealtime())) {
                assertEquals(H264DecoderOptions.forRealtime(), decoder.options());
                var frames = new int[1];
                for (var i = 0; i < 3; i++) {
                    var frame = new VideoFrame(EncoderHarness.synthetic(32, 32, i, 7, 1.0), i * 3000L, 3000, false);
                    encoder.encode(frame, packet -> decoder.decode(packet, (picture, damaged) -> frames[0]++));
                    assertEquals(i + 1, frames[0]);
                }
            }
        }
    }
}
