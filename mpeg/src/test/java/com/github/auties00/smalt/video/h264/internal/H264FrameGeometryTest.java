package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the plane geometry of decoder frames for every supported {@code chroma_format_idc}, and
 * that the allocation estimate the decoder checks against the memory limits covers the planes
 * it allocates.
 */
@DisplayName("H.264 decoder frame geometry")
class H264FrameGeometryTest {
    @ParameterizedTest(name = "chroma_format_idc {0}, {1} bits")
    @CsvSource({"0, 8", "1, 8", "2, 8", "0, 10", "1, 10", "2, 14"})
    @DisplayName("sizes the chroma planes by MbHeightC and estimates exactly the planes it allocates")
    void planes(int chromaFormat, int depth) {
        var mbWidth = 5;
        var mbHeight = 3;
        var frame = new DecoderFrame(mbWidth, mbHeight, chromaFormat, depth, depth);
        var mbHeightC = chromaFormat == 2 ? 16 : 8;
        assertEquals(chromaFormat != 0, frame.hasChroma);
        assertEquals(mbHeightC, frame.chromaMbHeight);
        assertEquals(mbHeight * mbHeightC, frame.chromaHeight);
        var lumaSamples = (long) frame.lumaStride * (mbHeight * 16 + 2 * DecoderFrame.LUMA_PAD);
        var chromaSamples = chromaFormat == 0 ? 0
                : (long) frame.chromaStride * (mbHeight * mbHeightC + 2 * DecoderFrame.CHROMA_PAD);
        var wide = depth > 8;
        if (wide) {
            assertEquals(lumaSamples, frame.luma16.length);
            if (chromaFormat == 0) {
                assertNull(frame.cb16);
            } else {
                assertEquals(chromaSamples, frame.cb16.length);
                assertEquals(chromaSamples, frame.cr16.length);
            }
        } else {
            assertEquals(lumaSamples, frame.luma.length);
            if (chromaFormat == 0) {
                assertNull(frame.cb);
            } else {
                assertEquals(chromaSamples, frame.cb.length);
                assertEquals(chromaSamples, frame.cr.length);
            }
        }
        var motion = (long) mbWidth * mbHeight * (2 * 16 * 4 + 2 * 4 + 2 * 4 * 4 + 4);
        assertEquals((lumaSamples + 2 * chromaSamples) * (wide ? 2 : 1) + motion,
                DecoderFrame.allocationSize(mbWidth, mbHeight, chromaFormat, wide));
    }
}
