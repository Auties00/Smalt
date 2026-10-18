package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on the VP9 options types: presets, withers that leave the
 * original untouched, builders that round-trip through {@code toBuilder()}, value equality,
 * validation, the typed speed, and the decoder threading.
 */
@DisplayName("VP9 options")
class Vp9OptionsTest {
    @Nested
    @DisplayName("Vp9EncoderOptions")
    class Encoder {
        @Test
        @DisplayName("forRealtime() is error resilient speed 6 with cyclic refresh and WebRTC's quantizers")
        void realtime() {
            var options = Vp9EncoderOptions.forRealtime();
            assertTrue(options.errorResilient());
            assertTrue(options.adaptiveQuantization());
            assertTrue(options.dropFrames());
            assertEquals(Vp9EncoderSpeed.SPEED_6, options.speed());
            assertEquals(2, options.minQuantizer());
            assertEquals(52, options.maxQuantizer());
            assertEquals(OptionalInt.empty(), options.keyframeInterval());
            assertEquals(Duration.ofMillis(600), options.optimalBufferLevel());
            assertEquals(6, options.tileColumnsLog2());
            assertEquals(new Rational(1, 90_000), options.timeBase());
        }

        @Test
        @DisplayName("defaults() keeps every frame with libvpx's buffer and periodic key frames")
        void defaults() {
            var options = Vp9EncoderOptions.defaults();
            assertEquals(options, Vp9EncoderOptions.builder().build());
            assertFalse(options.errorResilient());
            assertFalse(options.adaptiveQuantization());
            assertFalse(options.dropFrames());
            assertEquals(OptionalInt.of(128), options.keyframeInterval());
            assertEquals(Duration.ofMillis(6000), options.bufferSize());
            assertEquals(MediaLimits.DEFAULT, options.limits());
        }

        @Test
        @DisplayName("toBuilder changes settings and leaves the original untouched")
        void toBuilder() {
            var base = Vp9EncoderOptions.forRealtime();
            var changed = base.toBuilder().frameRate(new Rational(15, 1))
                    .timeBase(Rational.MICROSECONDS)
                    .bitrate(250_000)
                    .quantizers(10, 50)
                    .keyframeInterval(90)
                    .speed(Vp9EncoderSpeed.SPEED_8)
                    .errorResilient(false)
                    .adaptiveQuantization(false)
                    .temporalLayers(3)
                    .dropFrames(false)
                    .bufferSize(Duration.ofMillis(2000)).initialBufferLevel(Duration.ofMillis(1000))
                            .optimalBufferLevel(Duration.ofMillis(1500))
                    .tileColumnsLog2(1)
                    .threads(4)
                    .limits(MediaLimits.builder().maxDimension(1024).build()).build();
            assertEquals(Vp9EncoderOptions.forRealtime(), base);
            assertEquals(Vp9EncoderSpeed.SPEED_8, changed.speed());
            assertEquals(3, changed.temporalLayers());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base, changed);
        }

        @Test
        @DisplayName("rejects values outside their ranges")
        void validation() {
            var options = Vp9EncoderOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(-1).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().quantizers(0, 64).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().keyframeInterval(-5).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().temporalLayers(4).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().tileColumnsLog2(7).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().threads(-1).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bufferSize(Duration.ofMillis(1000))
                    .initialBufferLevel(Duration.ofMillis(500)).optimalBufferLevel(Duration.ofMillis(1001)).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().frameRate(null).build());
            for (var level = 5; level <= 9; level++) {
                assertEquals(level, Vp9EncoderSpeed.of(level).level());
            }
            assertThrows(IllegalArgumentException.class, () -> Vp9EncoderSpeed.of(4));
            assertThrows(IllegalArgumentException.class, () -> Vp9EncoderSpeed.of(10));
        }

        @Test
        @DisplayName("setTargetBitrate retargets a running encoder like reconfigure")
        void targetBitrate() {
            var frames = Vp9TestVideo.synthesize(64, 48, 20);
            var viaSetter = new ArrayList<Packet>();
            var viaReconfigure = new ArrayList<Packet>();
            try (var a = new Vp9Encoder(64, 48, Vp9EncoderOptions.forRealtime().toBuilder().bitrate(200_000).build());
                 var b = new Vp9Encoder(64, 48, Vp9EncoderOptions.forRealtime().toBuilder().bitrate(200_000).build())) {
                for (var i = 0; i < frames.size(); i++) {
                    if (i == 10) {
                        a.setTargetBitrate(80_000);
                        b.reconfigure(b.options().toBuilder().bitrate(80_000).build());
                    }
                    var frame = new VideoFrame(frames.get(i), i * 3000L, 3000, false);
                    a.encode(frame, viaSetter::add);
                    b.encode(frame, viaReconfigure::add);
                }
            }
            assertEquals(viaReconfigure.size(), viaSetter.size());
            for (var i = 0; i < viaSetter.size(); i++) {
                assertArrayEquals(viaReconfigure.get(i).data(), viaSetter.get(i).data());
            }
        }
    }

    @Nested
    @DisplayName("Vp9Decoder options")
    class Decoder {
        @Test
        @DisplayName("decoding on the calling thread gives the same pictures as the shared pool")
        void threading() {
            var frames = Vp9TestVideo.synthesize(640, 360, 4);
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp9Encoder(640, 360, Vp9EncoderOptions.forRealtime().toBuilder().bitrate(800_000)
                    .build())) {
                for (var i = 0; i < frames.size(); i++) {
                    encoder.encode(new VideoFrame(frames.get(i), i * 3000L, 3000, false), packets::add);
                }
            }
            var pooled = decode(packets, VideoDecoderOptions.defaults());
            var calling = decode(packets, VideoDecoderOptions.builder().threads(1).build());
            assertEquals(pooled.size(), calling.size());
            for (var i = 0; i < pooled.size(); i++) {
                assertArrayEquals(pooled.get(i), calling.get(i), "frame " + i);
            }
        }

        private static List<byte[]> decode(List<Packet> packets, VideoDecoderOptions options) {
            var pictures = new ArrayList<byte[]>();
            try (var decoder = new Vp9Decoder(Vp9VideoEncoderTest.track(640, 360), options)) {
                assertEquals(options.threads(), decoder.options().threads());
                for (var packet : packets) {
                    decoder.decode(packet, frame -> {
                        var picture = (YuvPicture) frame.picture();
                        pictures.add(Arrays.copyOf(picture.y(), picture.y().length));
                    });
                }
            }
            return pictures;
        }
    }
}
