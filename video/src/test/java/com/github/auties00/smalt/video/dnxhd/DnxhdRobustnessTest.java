package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;
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
 * Feeds the DNxHD decoder corrupt variants of valid frames (byte flips, truncations, damaged
 * headers, scan indices and macroblock data) and requires every decode to succeed or raise a
 * {@link MediaException}, in bounded time, and the decoder to decode the clean frame identically
 * afterwards.
 */
@DisplayName("DNxHD decoder robustness")
class DnxhdRobustnessTest {
    static byte[] frame(DnxhdEncoderOptions options, String format) {
        var picture = IntraTesting.sourceFrames(130, 66, format, 1).getFirst();
        return new DnxhdEncoder(130, 66, options).encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).data();
    }

    static Stream<Arguments> frames() {
        return Stream.of(
                Arguments.of("DNxHR LB", frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB), "yuv422p")),
                Arguments.of("DNxHR HQX 12-bit", frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX)
                        .toBuilder()
                        .bitDepth(12)
                        .build(), "yuv422p12le")),
                Arguments.of("DNxHR 444", frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444), "yuv444p10le")),
                Arguments.of("DNxHR HQ 4:2:0 with alpha", frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)
                        .toBuilder()
                        .chromaFormat(com.github.auties00.smalt.image.ChromaFormat.YUV420)
                        .alpha(true)
                        .build(), "yuva444p10le")),
                Arguments.of("DNxHR 444 lossless alpha", frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444)
                        .toBuilder().alpha(true)
                        .losslessAlpha(true).build(), "yuva444p10le")),
                Arguments.of("DNxHR SQ with Avid alpha", avidAlpha(frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ),
                        "yuv422p"))));
    }

    /** Appends Avid's out-of-band alpha to a frame: 8-bit samples, then the size of the coding unit. */
    static byte[] avidAlpha(byte[] frame) {
        var out = java.util.Arrays.copyOf(frame, frame.length + 130 * 66 + 4);
        for (var i = 0; i < 130 * 66; i++) {
            out[frame.length + i] = (byte) (i * 7);
        }
        out[out.length - 4] = (byte) (frame.length >>> 24);
        out[out.length - 3] = (byte) (frame.length >>> 16);
        out[out.length - 2] = (byte) (frame.length >>> 8);
        out[out.length - 1] = (byte) frame.length;
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frames")
    @DisplayName("raises only MediaException on corrupt frames")
    void mutations(String label, byte[] frame) {
        var decoder = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(1).build());
        var clean = IntraTesting.raw(decoder.decodeFrame(frame, 0, frame.length), true);
        var variants = IntraMutations.mutations(frame, 0x180, 400, label.hashCode());
        var rejected = IntraMutations.check(variants, variant -> decoder.decodeFrame(variant, 0, variant.length));
        assertTrue(rejected > 0, label + ": no variant was rejected");
        assertArrayEquals(clean, IntraTesting.raw(decoder.decodeFrame(frame, 0, frame.length), true));
    }

    @Test
    @DisplayName("rejects unknown compression IDs with Unsupported and forbidden alpha and 4:2:0 as Malformed")
    void rejected() {
        var decoder = new DnxhdDecoder();
        var frame = frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ), "yuv422p");
        var unknown = frame.clone();
        unknown[0x2B] = 0x01;
        assertThrows(MediaException.Unsupported.class, () -> decoder.decodeFrame(unknown, 0, unknown.length));
        var lowBandwidth = frame(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB), "yuv422p");
        var alpha = lowBandwidth.clone();
        alpha[7] |= 1;
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(alpha, 0, alpha.length));
        var lossless = frame.clone();
        lossless[7] |= 3;
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(lossless, 0, lossless.length));
        var hd = new DnxhdEncoder(1280, 720, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)).encodeFrame(new VideoFrame(
                IntraTesting.sourceFrames(1280, 720, "yuv422p", 1).getFirst(), Packet.NO_TIMESTAMP, 0, true)).data();
        hd[0x2C] = (byte) (hd[0x2C] & ~0x60 | 0x20);
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(hd, 0, hd.length));
    }

    @Test
    @DisplayName("decodes and encodes on several threads exactly as on one")
    void threads() {
        var picture = IntraTesting.sourceFrames(1280, 720, "yuv422p", 1).getFirst();
        var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHD);
        var frame = new DnxhdEncoder(1280, 720, options).encodeFrame(new VideoFrame(picture, 0, 0, true)).data();
        var serial = new DnxhdEncoder(1280, 720, options.toBuilder().threads(1).build())
                .encodeFrame(new VideoFrame(picture, 0, 0, true)).data();
        assertArrayEquals(serial, frame);
        var single = IntraTesting.raw(new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(1).build())
                .decodeFrame(frame, 0, frame.length), false);
        var parallel = IntraTesting.raw(new DnxhdDecoder().decodeFrame(frame, 0, frame.length), false);
        assertArrayEquals(single, parallel);
    }
}
