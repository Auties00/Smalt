package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks the plane geometry of decoder frames for every supported {@code chroma_format_idc}, the
 * frames of separately coded color planes, and that the allocation estimate the decoder checks
 * against the memory limits covers the planes it allocates.
 */
@DisplayName("H.264 decoder frame geometry")
class H264FrameGeometryTest {
    private static final long MOTION_PER_MB = 2 * 16 * 4 + 2 * 4 + 2 * 4 * 4 + 4;

    @ParameterizedTest(name = "chroma_format_idc {0}, {1} bits")
    @CsvSource({"0, 8", "1, 8", "2, 8", "3, 8", "0, 10", "1, 10", "2, 14", "3, 12"})
    @DisplayName("sizes the chroma planes by MbWidthC and MbHeightC and estimates exactly the planes it allocates")
    void planes(int chromaFormat, int depth) {
        var mbWidth = 5;
        var mbHeight = 3;
        var frame = new DecoderFrame(mbWidth, mbHeight, chromaFormat, depth, depth);
        var mbHeightC = chromaFormat >= 2 ? 16 : 8;
        var mbWidthC = chromaFormat == 3 ? 16 : 8;
        var pad = chromaFormat == 3 ? DecoderFrame.LUMA_PAD : DecoderFrame.CHROMA_PAD;
        assertEquals(chromaFormat != 0, frame.hasChroma);
        assertEquals(mbHeightC, frame.chromaMbHeight);
        assertEquals(mbWidthC, frame.chromaMbWidth);
        assertEquals(mbHeight * mbHeightC, frame.chromaHeight);
        assertEquals(mbWidth * mbWidthC, frame.chromaWidth);
        assertEquals(pad, frame.chromaPad);
        assertEquals(mbWidth * mbWidthC + 2 * pad, frame.chromaStride);
        assertEquals(pad * frame.chromaStride + pad, frame.chromaOrigin);
        var lumaSamples = (long) frame.lumaStride * (mbHeight * 16 + 2 * DecoderFrame.LUMA_PAD);
        var chromaSamples = chromaFormat == 0 ? 0 : (long) frame.chromaStride * (mbHeight * mbHeightC + 2 * pad);
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
        assertEquals((lumaSamples + 2 * chromaSamples) * (wide ? 2 : 1) + mbWidth * mbHeight * MOTION_PER_MB,
                DecoderFrame.allocationSize(mbWidth, mbHeight, chromaFormat, false, wide));
    }

    @ParameterizedTest(name = "{0} bits")
    @ValueSource(ints = {8, 14})
    @DisplayName("presents separately coded color planes as one 4:4:4 frame over three monochrome frames")
    void separatePlanes(int depth) {
        var mbWidth = 4;
        var mbHeight = 2;
        var frame = DecoderFrame.separatePlanes(mbWidth, mbHeight, depth);
        assertEquals(3, frame.chromaFormat);
        assertEquals(depth, frame.bitDepthChroma);
        assertEquals(frame.lumaStride, frame.chromaStride);
        assertEquals(frame.lumaOrigin, frame.chromaOrigin);
        for (var p = 0; p < 3; p++) {
            var plane = frame.plane(p);
            assertEquals(0, plane.chromaFormat);
            assertSame(frame, plane.owner());
            assertNotSame(frame, plane);
        }
        assertSame(frame, frame.owner());
        if (depth > 8) {
            assertSame(frame.plane(0).luma16, frame.luma16);
            assertSame(frame.plane(1).luma16, frame.cb16);
            assertSame(frame.plane(2).luma16, frame.cr16);
        } else {
            assertSame(frame.plane(0).luma, frame.luma);
            assertSame(frame.plane(1).luma, frame.cb);
            assertSame(frame.plane(2).luma, frame.cr);
        }
        assertSame(frame.plane(0).mv, frame.mv);
        var lumaSamples = (long) frame.lumaStride * (mbHeight * 16 + 2 * DecoderFrame.LUMA_PAD);
        assertEquals(3 * lumaSamples * (depth > 8 ? 2 : 1) + 3L * mbWidth * mbHeight * MOTION_PER_MB,
                DecoderFrame.allocationSize(mbWidth, mbHeight, 3, true, depth > 8));
        frame.id = 7;
        frame.fields = DecoderFrame.TOP;
        frame.codingStruct = DecoderFrame.CODED_FIELDS;
        frame.beginDecoding(DecoderFrame.TOP, true);
        for (var p = 0; p < 3; p++) {
            assertEquals(7, frame.plane(p).id);
            assertEquals(DecoderFrame.TOP, frame.plane(p).fields);
            assertEquals(DecoderFrame.CODED_FIELDS, frame.plane(p).codingStruct);
        }
        frame.publishDone(DecoderFrame.TOP);
        for (var p = 0; p < 3; p++) {
            assertEquals(true, frame.plane(p).isDone(DecoderFrame.TOP));
        }
    }
}
