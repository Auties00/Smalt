package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
 * Covers the options convention on the VP8 options types: presets, withers that leave the
 * original untouched, builders that round-trip through {@code toBuilder()}, value equality,
 * validation, the typed speed, and the decoder threading.
 */
@DisplayName("VP8 options")
class Vp8OptionsTest {
    @Nested
    @DisplayName("Vp8EncoderOptions")
    class Encoder {
        @Test
        @DisplayName("forRealtime() is error resilient speed 6 with WebRTC's buffer and tolerances")
        void realtime() {
            var options = Vp8EncoderOptions.forRealtime();
            assertTrue(options.errorResilient());
            assertTrue(options.dropFrames());
            assertEquals(Vp8EncoderSpeed.SPEED_6, options.speed());
            assertEquals(OptionalInt.empty(), options.keyframeInterval());
            assertEquals(Duration.ofMillis(1000), options.bufferSize());
            assertEquals(Duration.ofMillis(500), options.initialBufferLevel());
            assertEquals(Duration.ofMillis(600), options.optimalBufferLevel());
            assertEquals(100, options.undershootPercent());
            assertEquals(15, options.overshootPercent());
            assertEquals(OptionalInt.of(900), options.maxKeyframeBitratePercent());
            assertEquals(new Rational(1, 90_000), options.timeBase());
        }

        @Test
        @DisplayName("defaults() keeps every frame with libvpx's buffer and tolerances")
        void defaults() {
            var options = Vp8EncoderOptions.defaults();
            assertEquals(options, Vp8EncoderOptions.builder().build());
            assertFalse(options.errorResilient());
            assertFalse(options.dropFrames());
            assertEquals(OptionalInt.of(128), options.keyframeInterval());
            assertEquals(Duration.ofMillis(6000), options.bufferSize());
            assertEquals(100, options.overshootPercent());
            assertEquals(OptionalInt.empty(), options.maxKeyframeBitratePercent());
            assertEquals(MediaLimits.DEFAULT, options.limits());
        }

        @Test
        @DisplayName("withers change one setting and leave the original untouched")
        void withers() {
            var base = Vp8EncoderOptions.forRealtime();
            var changed = base.toBuilder().frameRate(new Rational(15, 1))
                    .timeBase(Rational.MICROSECONDS)
                    .bitrate(250_000)
                    .quantizers(10, 50)
                    .keyframeInterval(90)
                    .speed(Vp8EncoderSpeed.SPEED_10)
                    .errorResilient(false)
                    .temporalLayers(2)
                    .dropFrames(false)
                    .bufferSize(Duration.ofMillis(2000))
                    .initialBufferLevel(Duration.ofMillis(1000))
                    .optimalBufferLevel(Duration.ofMillis(1500))
                    .tokenPartitions(4)
                    .threads(2)
                    .encodeBreakout(1)
                    .rateTolerance(50, 50)
                    .noMaxKeyframeBitrate()
                    .limits(MediaLimits.builder().maxDimension(1024).build()).build();
            assertEquals(Vp8EncoderOptions.forRealtime(), base);
            assertEquals(Vp8EncoderSpeed.SPEED_10, changed.speed());
            assertEquals(4, changed.tokenPartitions());
            assertEquals(1, changed.encodeBreakout());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base, changed);
        }

        @Test
        @DisplayName("rejects values outside their ranges")
        void validation() {
            var options = Vp8EncoderOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().quantizers(5, 4).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().keyframeInterval(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().temporalLayers(0).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().tokenPartitions(3).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().threads(-1).build());
            assertEquals(0, options.toBuilder().threads(0).build().threads());
            assertEquals(64, options.toBuilder().threads(1000).build().threads());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bufferSize(Duration.ofMillis(1000))
                    .initialBufferLevel(Duration.ofMillis(1001)).optimalBufferLevel(Duration.ofMillis(500)).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bufferSize(Duration.ofNanos(1))
                    .initialBufferLevel(Duration.ZERO).optimalBufferLevel(Duration.ofNanos(1)).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().rateTolerance(101, 0).build());
            assertThrows(IllegalArgumentException.class,
                    () -> options.toBuilder().maxKeyframeBitratePercent(99).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().speed(null).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().limits(null).build());
        }

        @Test
        @DisplayName("speeds map to libvpx's cpu-used numbers 4 to 16")
        void speeds() {
            for (var level = 4; level <= 16; level++) {
                assertEquals(level, Vp8EncoderSpeed.of(level).level());
            }
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderSpeed.of(3));
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderSpeed.of(17));
        }

        @Test
        @DisplayName("setTargetBitrate retargets a running encoder like reconfigure")
        void targetBitrate() {
            var frames = RtTestVideo.synthesize(64, 48, 20);
            var viaSetter = new ArrayList<Packet>();
            var viaReconfigure = new ArrayList<Packet>();
            try (var a = new Vp8Encoder(64, 48, Vp8EncoderOptions.forRealtime().toBuilder().bitrate(200_000).build());
                 var b = new Vp8Encoder(64, 48, Vp8EncoderOptions.forRealtime().toBuilder().bitrate(200_000).build())) {
                for (var i = 0; i < frames.size(); i++) {
                    if (i == 10) {
                        a.setTargetBitrate(80_000);
                        b.reconfigure(b.options().toBuilder().bitrate(80_000).build());
                    }
                    var frame = new VideoFrame(frames.get(i), i * 3000L, 3000, false);
                    a.encode(frame, viaSetter::add);
                    b.encode(frame, viaReconfigure::add);
                }
                assertEquals(80_000, a.options().bitrate());
            }
            assertEquals(viaReconfigure.size(), viaSetter.size());
            for (var i = 0; i < viaSetter.size(); i++) {
                assertArrayEquals(viaReconfigure.get(i).data(), viaSetter.get(i).data());
            }
        }
    }

    @Nested
    @DisplayName("Vp8Decoder options")
    class Decoder {
        @Test
        @DisplayName("decoding on the calling thread gives the same pictures as the shared pool")
        void threading() {
            var frames = RtTestVideo.synthesize(320, 240, 6);
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp8Encoder(320, 240, Vp8EncoderOptions.forRealtime().toBuilder().bitrate(600_000)
                    .build())) {
                for (var i = 0; i < frames.size(); i++) {
                    encoder.encode(new VideoFrame(frames.get(i), i * 3000L, 3000, false), packets::add);
                }
            }
            assertEquals(decode(packets, VideoDecoderOptions.defaults()),
                    decode(packets, VideoDecoderOptions.builder().threads(1).build()));
        }

        private static List<String> decode(List<Packet> packets, VideoDecoderOptions options) {
            var digests = new ArrayList<String>();
            try (var decoder = new Vp8Decoder(Vp8VideoEncoderTest.track(320, 240), options)) {
                assertEquals(options.limits(), decoder.options().limits());
                assertEquals(options.threads(), decoder.options().threads());
                for (var packet : packets) {
                    decoder.decode(packet, frame -> digests.add(java.util.HexFormat.of()
                            .formatHex(Ivf.i420((com.github.auties00.smalt.image.YuvPicture) frame.picture()))));
                }
            }
            return digests;
        }
    }

    @Nested
    @DisplayName("Vp8EncoderOptions plain setters, resets and forRealtime(frameRate)")
    class PlainSetters {
        @Test
        @DisplayName("the int setters set the accessors and the resets empty them")
        void optionalSetters() {
            assertEquals(OptionalInt.of(60), Vp8EncoderOptions.builder().keyframeInterval(60).build()
                    .keyframeInterval());
            assertEquals(OptionalInt.empty(), Vp8EncoderOptions.builder().noPeriodicKeyframes().build()
                    .keyframeInterval());
            assertEquals(OptionalInt.of(300), Vp8EncoderOptions.builder().maxKeyframeBitratePercent(300).build()
                    .maxKeyframeBitratePercent());
            assertEquals(Vp8EncoderOptions.defaults(),
                    Vp8EncoderOptions.builder().maxKeyframeBitratePercent(300).noMaxKeyframeBitrate().build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.builder().keyframeInterval(0).build());
            assertThrows(IllegalArgumentException.class,
                    () -> Vp8EncoderOptions.builder().maxKeyframeBitratePercent(99).build());
        }

        @Test
        @DisplayName("the three buffer setters are validated together in build()")
        void bufferSetters() {
            var separate = Vp8EncoderOptions.builder()
                    .bufferSize(Duration.ofMillis(2000))
                    .initialBufferLevel(Duration.ofMillis(1000))
                    .optimalBufferLevel(Duration.ofMillis(1500))
                    .build();
            assertEquals(Duration.ofMillis(2000), separate.bufferSize());
            assertEquals(Duration.ofMillis(1000), separate.initialBufferLevel());
            assertEquals(Duration.ofMillis(1500), separate.optimalBufferLevel());
            var builder = Vp8EncoderOptions.builder().initialBufferLevel(Duration.ofMillis(9000));
            assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals(Duration.ofMillis(9000), builder.bufferSize(Duration.ofMillis(10_000)).build()
                    .initialBufferLevel());
            assertThrows(IllegalArgumentException.class,
                    () -> Vp8EncoderOptions.builder().initialBufferLevel(Duration.ofMillis(9000)).build());
        }

        @Test
        @DisplayName("forRealtime(30) equals forRealtime() and the key frame limit follows the frame rate")
        void realtimeFrameRate() {
            assertEquals(Vp8EncoderOptions.forRealtime(), Vp8EncoderOptions.forRealtime(new Rational(30, 1)));
            var fifteen = Vp8EncoderOptions.forRealtime(new Rational(15, 1));
            assertEquals(new Rational(15, 1), fifteen.frameRate());
            assertEquals(OptionalInt.of(450), fifteen.maxKeyframeBitratePercent());
            assertEquals(OptionalInt.of(899), Vp8EncoderOptions.forRealtime(new Rational(30_000, 1001))
                    .maxKeyframeBitratePercent());
            assertEquals(OptionalInt.of(300), Vp8EncoderOptions.forRealtime(new Rational(5, 1))
                    .maxKeyframeBitratePercent());
            assertEquals(Vp8EncoderOptions.forRealtime().toBuilder().frameRate(new Rational(15, 1))
                    .maxKeyframeBitratePercent(450).build(), fifteen);
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.forRealtime(new Rational(2000, 1)));
        }
    }

    @Nested
    @DisplayName("Vp8Encoder construction, Optional encodeFrame and stats names")
    class EncoderAdditions {
        @Test
        @DisplayName("the two-argument constructor uses defaults()")
        void defaultsConstructor() {
            try (var encoder = new Vp8Encoder(64, 48)) {
                assertEquals(Vp8EncoderOptions.defaults(), encoder.options());
            }
        }

        @Test
        @DisplayName("encodeFrame(frame) codes a requested key frame and the stats count it")
        void optionalEncode() {
            var frames = RtTestVideo.synthesize(64, 48, 6);
            try (var a = new Vp8Encoder(64, 48, Vp8EncoderOptions.forRealtime())) {
                for (var i = 0; i < frames.size(); i++) {
                    var frame = new VideoFrame(frames.get(i), i * 3000L, 3000, false);
                    if (i == 4) {
                        a.requestKeyframe();
                    }
                    var actual = a.encodeFrame(frame);
                    if (i == 0 || i == 4) {
                        assertTrue(actual.orElseThrow().keyframe());
                    }
                }
                var stats = a.stats();
                assertEquals(2, stats.keyframesEncoded());
                assertEquals(0, stats.lastTemporalLayer());
                assertTrue(stats.lastQuantizerIndex() >= 0 && stats.lastQuantizerIndex() <= 127);
            }
        }
    }

    @Nested
    @DisplayName("Vp8Scale and the decoder scaling accessors")
    class Scaling {
        @Test
        @DisplayName("of(code) and code() round-trip, with the RFC 6386 factors")
        void codes() {
            for (var scale : Vp8Scale.values()) {
                assertEquals(scale, Vp8Scale.of(scale.code()));
            }
            assertEquals(5, Vp8Scale.FIVE_THIRDS.numerator());
            assertEquals(3, Vp8Scale.FIVE_THIRDS.denominator());
            assertThrows(IllegalArgumentException.class, () -> Vp8Scale.of(4));
            assertThrows(IllegalArgumentException.class, () -> Vp8Scale.of(-1));
        }

        @Test
        @DisplayName("the decoder reports the scale fields of the last key frame")
        void decoder() {
            var frames = RtTestVideo.synthesize(64, 48, 1);
            Packet packet;
            try (var encoder = new Vp8Encoder(64, 48)) {
                packet = encoder.encodeFrame(new VideoFrame(frames.getFirst(), 0, 3000, false)).orElseThrow();
            }
            var data = packet.data().clone();
            data[7] |= (byte) 0x40;
            data[9] |= (byte) 0xC0;
            var scaled = new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true);
            try (var decoder = new Vp8Decoder(Vp8VideoEncoderTest.track(64, 48))) {
                assertEquals(Vp8Scale.NONE, decoder.horizontalScaling());
                decoder.decode(scaled, frame -> { });
                assertEquals(Vp8Scale.FIVE_FOURTHS, decoder.horizontalScaling());
                assertEquals(Vp8Scale.TWO, decoder.verticalScaling());
            }
        }
    }
}
