package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the VP9 builder setters and their validation, the Optional-returning {@code encodeFrame},
 * the statistics and the decoder constructors.
 */
@DisplayName("VP9 API")
class Vp9EncoderApiTest {
    private static List<Packet> encode(int frames) {
        var pictures = Vp9TestVideo.synthesize(64, 48, frames);
        var packets = new ArrayList<Packet>();
        try (var encoder = new Vp9Encoder(64, 48, Vp9EncoderOptions.forRealtime())) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encodeFrame(new VideoFrame(pictures.get(i), i * 3000L, 3000, false)).ifPresent(packets::add);
            }
        }
        return packets;
    }

    private static List<YuvPicture> decode(Vp9Decoder decoder, List<Packet> packets) {
        var out = new ArrayList<YuvPicture>();
        try (decoder) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> out.add((YuvPicture) frame.picture()));
            }
            decoder.flush(frame -> out.add((YuvPicture) frame.picture()));
        }
        return out;
    }

    private static VideoTrack track() {
        return VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 90_000)).width(64).height(48)
                .build();
    }

    @Nested
    @DisplayName("Vp9EncoderOptions.Builder")
    class BuilderSetters {
        @Test
        @DisplayName("keyframeInterval(int) sets the interval and noPeriodicKeyframes() clears it")
        void keyframeInterval() {
            var base = Vp9EncoderOptions.defaults();
            assertEquals(OptionalInt.of(60), base.toBuilder().keyframeInterval(60).build().keyframeInterval());
            assertEquals(OptionalInt.empty(), base.toBuilder().noPeriodicKeyframes().build().keyframeInterval());
            assertEquals(Vp9EncoderOptions.forRealtime(),
                    Vp9EncoderOptions.forRealtime().toBuilder().keyframeInterval(5).noPeriodicKeyframes().build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().keyframeInterval(0).build());
        }

        @Test
        @DisplayName("the three buffer setters set the buffer model")
        void buffer() {
            var size = Duration.ofMillis(3000);
            var initial = Duration.ofMillis(1000);
            var optimal = Duration.ofMillis(2000);
            var actual = Vp9EncoderOptions.forRealtime().toBuilder().bufferSize(size).initialBufferLevel(initial)
                    .optimalBufferLevel(optimal).build();
            assertEquals(size, actual.bufferSize());
            assertEquals(initial, actual.initialBufferLevel());
            assertEquals(optimal, actual.optimalBufferLevel());
        }

        @Test
        @DisplayName("the buffer setters reject null and build() rejects levels above the size")
        void bufferValidation() {
            var builder = Vp9EncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.bufferSize(null));
            assertThrows(NullPointerException.class, () -> builder.initialBufferLevel(null));
            assertThrows(NullPointerException.class, () -> builder.optimalBufferLevel(null));
            assertThrows(IllegalArgumentException.class,
                    () -> Vp9EncoderOptions.builder().bufferSize(Duration.ofMillis(100)).build());
        }

        @Test
        @DisplayName("threads(0) is automatic, a count above 64 is lowered to 64, and a negative one is rejected")
        void threads() {
            assertEquals(0, Vp9EncoderOptions.builder().threads(0).build().threads());
            assertEquals(64, Vp9EncoderOptions.builder().threads(65).build().threads());
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> Vp9EncoderOptions.builder().threads(-1).build());
            assertEquals("thread count -1 is negative", failure.getMessage());
        }

        @Test
        @DisplayName("setters reject null and leave range checks to build()")
        void setters() {
            var builder = Vp9EncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.frameRate(null));
            assertThrows(NullPointerException.class, () -> builder.speed(null));
            assertThrows(NullPointerException.class, () -> builder.limits(null));
            builder.quantizers(10, 64);
            var failure = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("largest quantizer 64 is not in [10, 63]", failure.getMessage());
        }
    }

    @Nested
    @DisplayName("Vp9Encoder")
    class Encoder {
        @Test
        @DisplayName("the size-only constructor uses defaults()")
        void defaultConstructor() {
            try (var encoder = new Vp9Encoder(64, 48)) {
                assertEquals(Vp9EncoderOptions.defaults(), encoder.options());
            }
            assertThrows(IllegalArgumentException.class, () -> new Vp9Encoder(64, 16_385));
        }

        @Test
        @DisplayName("encodeFrame(frame) returns the packets of encode(frame, output), and requestKeyframe() forces a key frame")
        void optionalEncodeFrame() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 6);
            var options = Vp9EncoderOptions.forRealtime();
            try (var expected = new Vp9Encoder(64, 48, options); var actual = new Vp9Encoder(64, 48, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    var frame = new VideoFrame(pictures.get(i), i * 3000L, 3000, false);
                    var key = i == 3;
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
                        assertEquals(i == 0 || key, packet.orElseThrow().keyframe());
                    }
                }
            }
        }

        @Test
        @DisplayName("encodeFrame(frame) rejects a closed encoder")
        void closed() {
            var picture = Vp9TestVideo.synthesize(64, 48, 1).getFirst();
            var encoder = new Vp9Encoder(64, 48);
            encoder.close();
            assertThrows(IllegalStateException.class,
                    () -> encoder.encodeFrame(new VideoFrame(picture, 0, 3000, false)));
        }

        @Test
        @DisplayName("the statistics report the temporal layer of every frame and count the key frames")
        void stats() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 4);
            var options = Vp9EncoderOptions.forRealtime().toBuilder().temporalLayers(2).build();
            try (var encoder = new Vp9Encoder(64, 48, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encodeFrame(new VideoFrame(pictures.get(i), i * 3000L, 3000, false));
                    var stats = encoder.stats();
                    assertTrue(stats.lastQuantizerIndex() >= 0 && stats.lastQuantizerIndex() <= 255);
                    assertEquals(i & 1, stats.lastTemporalLayer());
                }
                assertTrue(encoder.stats().keyframesEncoded() >= 1);
            }
        }
    }

    @Nested
    @DisplayName("Vp9Decoder")
    class Decoder {
        @Test
        @DisplayName("the decoder keeps the options it was created with")
        void mapsOptions() {
            var limits = MediaLimits.DEFAULT;
            var generic = VideoDecoderOptions.builder().limits(limits).threads(1).highBitDepth(true).build();
            try (var decoder = new Vp9Decoder(track(), generic)) {
                assertEquals(generic, decoder.options());
            }
            assertThrows(IllegalArgumentException.class, () -> new Vp9Decoder(
                    VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).build(),
                    VideoDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("the one-argument constructor decodes the same pictures as the default options")
        void decodesSame() {
            var packets = encode(5);
            var expected = decode(new Vp9Decoder(track()), packets);
            var actual = decode(new Vp9Decoder(track(), VideoDecoderOptions.defaults()), packets);
            assertEquals(expected.size(), actual.size());
            assertEquals(packets.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i).y(), actual.get(i).y());
                assertArrayEquals(expected.get(i).u(), actual.get(i).u());
                assertArrayEquals(expected.get(i).v(), actual.get(i).v());
            }
        }
    }
}
