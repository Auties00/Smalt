package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraMutations;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the ProRes decoder corrupt variants of valid frames (byte flips, truncations, damaged frame
 * and picture headers, slice tables and slice data) and requires every decode to succeed or raise a
 * {@link MediaException}, in bounded time, and the decoder to decode the clean frame identically
 * afterwards.
 */
@DisplayName("ProRes decoder robustness")
class ProResRobustnessTest {
    static byte[] frame(ProResProfile profile, String format, FieldOrder order) {
        var picture = IntraTesting.sourceFrames(96, 50, format, 1).getFirst();
        var encoder = new ProResEncoder(96, 50, ProResEncoderOptions.of(profile).toBuilder().fieldOrder(order)
                .sliceSize(2).build());
        return encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).data();
    }

    static Stream<Arguments> frames() {
        return Stream.of(
                Arguments.of("422 HQ", frame(ProResProfile.HQ, "yuv422p10le", FieldOrder.PROGRESSIVE)),
                Arguments.of("422 Proxy interlaced", frame(ProResProfile.PROXY, "yuv422p10le", FieldOrder.TOP_FIRST)),
                Arguments.of("4444 with alpha", frame(ProResProfile.PRORES_4444, "yuva444p12le", FieldOrder.PROGRESSIVE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frames")
    @DisplayName("raises only MediaException on corrupt frames")
    void mutations(String label, byte[] frame) {
        var decoder = new ProResDecoder(ProResDecoderOptions.builder().threads(1).build());
        var clean = IntraTesting.raw(decoder.decodeFrame(frame, 0, frame.length), true);
        var variants = IntraMutations.mutations(frame, 200, 400, label.hashCode());
        var rejected = IntraMutations.check(variants, variant -> decoder.decodeFrame(variant, 0, variant.length));
        assertTrue(rejected > 0, label + ": no variant was rejected");
        assertArrayEquals(clean, IntraTesting.raw(decoder.decodeFrame(frame, 0, frame.length), true));
    }

    @Test
    @DisplayName("checks the frame size against the limits before allocating")
    void limits() {
        var frame = frame(ProResProfile.HQ, "yuv422p10le", FieldOrder.PROGRESSIVE);
        frame[16] = (byte) 0xFF;
        frame[17] = (byte) 0xFF;
        frame[18] = (byte) 0xFF;
        frame[19] = (byte) 0xFF;
        var decoder = new ProResDecoder(ProResDecoderOptions.builder().limits(MediaLimits.DEFAULT).build());
        assertThrows(MediaException.class, () -> decoder.decodeFrame(frame, 0, frame.length));
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(new byte[40], 0, 40));
        var versioned = frame(ProResProfile.HQ, "yuv422p10le", FieldOrder.PROGRESSIVE);
        versioned[11] = 2;
        assertThrows(MediaException.Unsupported.class, () -> decoder.decodeFrame(versioned, 0, versioned.length));
    }

    @Test
    @DisplayName("decodes on several threads exactly as on one")
    void threads() {
        var picture = IntraTesting.sourceFrames(640, 480, "yuv422p10le", 1).getFirst();
        var frame = new ProResEncoder(640, 480).encodeFrame(new VideoFrame(picture, 0, 0, true)).data();
        var single = IntraTesting.raw(new ProResDecoder(ProResDecoderOptions.builder().threads(1).build())
                .decodeFrame(frame, 0, frame.length), false);
        var parallel = IntraTesting.raw(new ProResDecoder(ProResDecoderOptions.builder().threads(0).build())
                .decodeFrame(frame, 0, frame.length), false);
        assertArrayEquals(single, parallel);
        var serial = new ProResEncoder(640, 480, ProResEncoderOptions.builder().threads(1).build())
                .encodeFrame(new VideoFrame(picture, 0, 0, true)).data();
        assertArrayEquals(serial, frame, "the encoder output does not depend on the threads");
    }
}
