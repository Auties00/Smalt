package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the {@link Av1Decoder} contracts that do not depend on conformance streams: the color
 * description of its pictures and the handling of an exception thrown by the consumer.
 */
@DisplayName("Av1Decoder")
class Av1DecoderTest {
    private static final ContentLightLevel LIGHT_LEVEL = new ContentLightLevel(1000, 400);

    @Nested
    @DisplayName("color description")
    class Color {
        @Test
        @DisplayName("without a track description, the stream's high-dynamic-range metadata is reported")
        void signaled() {
            var color = decodeColor(Optional.empty());
            assertEquals(Optional.of(LIGHT_LEVEL), color.contentLightLevel());
            assertEquals(ColorInfo.BT601_LIMITED.matrix(), color.matrix());
        }

        @Test
        @DisplayName("a track description wins but keeps the stream's metadata it leaves unspecified")
        void trackDeclared() {
            var declared = ColorInfo.BT709_LIMITED;
            var color = decodeColor(Optional.of(declared));
            assertEquals(declared.matrix(), color.matrix());
            assertEquals(declared.primaries(), color.primaries());
            assertEquals(declared.transfer(), color.transfer());
            assertEquals(declared.range(), color.range());
            assertEquals(Optional.of(LIGHT_LEVEL), color.contentLightLevel());
        }

        @Test
        @DisplayName("metadata and siting the track declares are kept")
        void trackMetadataWins() {
            var light = new ContentLightLevel(4000, 1000);
            var declared = ColorInfo.BT709_LIMITED.withContentLightLevel(light).withChromaSiting(ChromaSiting.CENTER);
            var color = decodeColor(Optional.of(declared));
            assertEquals(Optional.of(light), color.contentLightLevel());
            assertEquals(ChromaSiting.CENTER, color.chromaSiting());
        }

        private static ColorInfo decodeColor(Optional<ColorInfo> declared) {
            var packet = withLightLevel(encode(1).getFirst());
            var colors = new ArrayList<ColorInfo>();
            try (var decoder = new Av1Decoder(track(declared))) {
                decoder.decode(packet, frame -> colors.add(((YuvPicture) frame.picture()).color()));
            }
            assertEquals(1, colors.size());
            return colors.getFirst();
        }

        /**
         * Returns a packet with a {@code METADATA_TYPE_HDR_CLL} metadata OBU after its temporal
         * delimiter.
         */
        private static Packet withLightLevel(Packet packet) {
            var data = packet.data();
            assertEquals(0x12, data[0]);
            assertEquals(0x00, data[1]);
            // OBU_METADATA with a size field, 6 payload bytes: type 1, max_cll 1000, max_fall 400, trailing bits.
            var metadata = new byte[]{0x2A, 6, 1, 0x03, (byte) 0xE8, 0x01, (byte) 0x90, (byte) 0x80};
            var result = new byte[data.length + metadata.length];
            System.arraycopy(data, 0, result, 0, 2);
            System.arraycopy(metadata, 0, result, 2, metadata.length);
            System.arraycopy(data, 2, result, 2 + metadata.length, data.length - 2);
            return packet.withData(result);
        }
    }

    @Nested
    @DisplayName("consumer failures")
    class ConsumerFailures {
        @Test
        @DisplayName("an exception thrown by the consumer of decode reaches the caller unchanged")
        void decode() {
            var failure = new IllegalStateException("consumer failure");
            try (var decoder = new Av1Decoder(track(Optional.empty()))) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> decoder.decode(encode(1).getFirst(), _ -> {
                            throw failure;
                        }));
                assertSame(failure, thrown);
            }
        }

        @Test
        @DisplayName("an exception thrown by the consumer of flush reaches the caller unchanged")
        void flush() {
            var failure = new IllegalStateException("consumer failure");
            var packets = encode(3);
            var options = Av1DecoderOptions.builder().maxFrameDelay(4).build();
            try (var decoder = new Av1Decoder(track(Optional.empty()), options)) {
                for (var packet : packets) {
                    decoder.decode(packet, _ -> {
                    });
                }
                var thrown = assertThrows(IllegalStateException.class, () -> decoder.flush(_ -> {
                    throw failure;
                }));
                assertSame(failure, thrown);
            }
        }
    }

    private static List<Packet> encode(int frames) {
        var packets = new ArrayList<Packet>();
        var pictures = Vp9TestVideo.synthesize(64, 48, frames);
        try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                .build())) {
            for (var i = 0; i < frames; i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
        }
        assertEquals(frames, packets.size());
        return packets;
    }

    private static VideoTrack track(Optional<ColorInfo> color) {
        var builder = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 90_000)).width(64)
                .height(48);
        color.ifPresent(builder::color);
        return builder.build();
    }
}
