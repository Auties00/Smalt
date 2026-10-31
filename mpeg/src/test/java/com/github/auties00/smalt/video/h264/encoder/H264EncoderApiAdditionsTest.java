package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the H.264 encoder options builder's key frame interval setter and named resets, the
 * quantizer bound constants and the default-options constructor.
 */
@DisplayName("H.264 encoder API additions")
class H264EncoderApiAdditionsTest {
    @Test
    @DisplayName("keyframeInterval(int) sets the interval")
    void keyframeInterval() {
        var plain = H264EncoderOptions.forRealtime().toBuilder().keyframeInterval(90).build();
        assertEquals(OptionalInt.of(90), plain.keyframeInterval());
        assertEquals(H264EncoderOptions.forRealtime(), plain.toBuilder().noPeriodicKeyframes().build());
    }

    @Test
    @DisplayName("keyframeInterval(int) is validated by build()")
    void keyframeIntervalValidated() {
        var builder = H264EncoderOptions.builder().keyframeInterval(0);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    @DisplayName("noPeriodicKeyframes() clears the interval")
    void noPeriodicKeyframes() {
        var options = H264EncoderOptions.forFile().toBuilder().noPeriodicKeyframes().build();
        assertEquals(OptionalInt.empty(), options.keyframeInterval());
        assertEquals(H264EncoderOptions.forFile(), options.toBuilder().keyframeInterval(60).build());
    }

    @Test
    @DisplayName("automaticMaxBitrate() and automaticBufferSize() restore the defaults of the mode")
    void automaticResets() {
        var configured = H264EncoderOptions.forFile().toBuilder().maxBitrate(3_000_000)
                .bufferSize(Duration.ofSeconds(3)).build();
        var reset = configured.toBuilder().automaticMaxBitrate().automaticBufferSize().build();
        assertEquals(OptionalLong.empty(), reset.maxBitrate());
        assertEquals(Optional.empty(), reset.bufferSize());
    }

    @Test
    @DisplayName("the quantizer constants bound quality and the quantizer window")
    void quantizerBounds() {
        assertEquals(0, H264EncoderOptions.MIN_QUANTIZER);
        assertEquals(51, H264EncoderOptions.MAX_QUANTIZER);
        var widest = H264EncoderOptions.builder()
                .quantizers(H264EncoderOptions.MIN_QUANTIZER, H264EncoderOptions.MAX_QUANTIZER)
                .quality(H264EncoderOptions.MAX_QUANTIZER).build();
        assertEquals(H264EncoderOptions.MIN_QUANTIZER, widest.minQuantizer());
        assertEquals(H264EncoderOptions.MAX_QUANTIZER, widest.maxQuantizer());
        assertThrows(IllegalArgumentException.class,
                () -> H264EncoderOptions.builder().quality(H264EncoderOptions.MAX_QUANTIZER + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> H264EncoderOptions.builder().quantizers(H264EncoderOptions.MIN_QUANTIZER - 1, 30).build());
    }

    @Test
    @DisplayName("H264Encoder(width, height) encodes as H264Encoder(width, height, defaults())")
    void defaultConstructor() {
        try (var plain = new H264Encoder(32, 32);
             var explicit = new H264Encoder(32, 32, H264EncoderOptions.defaults())) {
            assertEquals(H264EncoderOptions.defaults(), plain.options());
            var first = new ArrayList<Packet>();
            var second = new ArrayList<Packet>();
            for (var i = 0; i < 3; i++) {
                var picture = EncoderHarness.synthetic(32, 32, i, 3, 1.0);
                plain.encode(new VideoFrame(picture, i * 3000L, 3000, false), first::add);
                explicit.encode(new VideoFrame(picture, i * 3000L, 3000, false), second::add);
            }
            plain.flush(first::add);
            explicit.flush(second::add);
            assertEquals(second.size(), first.size());
            for (var i = 0; i < first.size(); i++) {
                assertArrayEquals(second.get(i).data(), first.get(i).data());
            }
            assertArrayEquals(explicit.codecConfig().orElseThrow(), plain.codecConfig().orElseThrow());
        }
    }

    @Test
    @DisplayName("a 4:4:0 picture codes as its 4:2:0 reduction by ImageOps")
    void yuv440Input() {
        try (var direct = new H264Encoder(34, 30); var reduced = new H264Encoder(34, 30)) {
            var first = new ArrayList<Packet>();
            var second = new ArrayList<Packet>();
            for (var i = 0; i < 3; i++) {
                var source = EncoderHarness.synthetic(34, 30, i, 5, 1.0);
                var full = com.github.auties00.smalt.image.ops.ImageOps.toYuv(source,
                        com.github.auties00.smalt.image.ChromaFormat.YUV444, source.color());
                var picture = com.github.auties00.smalt.image.ops.ImageOps.toYuv(full,
                        com.github.auties00.smalt.image.ChromaFormat.YUV440, source.color());
                assertEquals(com.github.auties00.smalt.image.ChromaFormat.YUV440, picture.chromaFormat());
                var halved = com.github.auties00.smalt.image.ops.ImageOps.toYuv(picture,
                        com.github.auties00.smalt.image.ChromaFormat.YUV420, source.color());
                direct.encode(new VideoFrame(picture, i * 3000L, 3000, false), first::add);
                reduced.encode(new VideoFrame(halved, i * 3000L, 3000, false), second::add);
            }
            direct.flush(first::add);
            reduced.flush(second::add);
            assertEquals(second.size(), first.size());
            for (var i = 0; i < first.size(); i++) {
                assertArrayEquals(second.get(i).data(), first.get(i).data(), "packet " + i);
            }
        }
    }

    @Test
    @DisplayName("H264Encoder(width, height) rejects sizes outside 1 to 16384")
    void defaultConstructorRejectsSize() {
        assertThrows(IllegalArgumentException.class, () -> new H264Encoder(0, 32));
    }
}
