package com.github.auties00.smalt.video.h263.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the per-layer and per-picture state of the annex engine: the picture sizes of the source formats of Table 1,
 * the timing of 8- and 10-bit temporal references of B-pictures, the pictures kept for Reference Picture Selection,
 * and the segment rectangles of Independent Segment Decoding.
 */
@DisplayName("H.263 picture state")
class PictureStateTest {
    @ParameterizedTest(name = "format {0}")
    @CsvSource({"0,0,0", "1,128,96", "2,176,144", "3,352,288", "4,704,576", "5,1408,1152", "6,200,120", "7,0,0"})
    @DisplayName("sizes the pictures of each source format, and none for a reserved one")
    void formats(int format, int width, int height) {
        var state = new LayerState();
        state.format = format;
        state.customWidth = 200;
        state.customHeight = 120;
        assertEquals(width, state.width());
        assertEquals(height, state.height());
    }

    @Test
    @DisplayName("times B-pictures before their anchor with 8- and 10-bit temporal references")
    void timing() {
        var stream = new SubBitstream(0, new PicturePool());
        assertEquals(0, stream.time(1020, true, 100, false));
        assertEquals(800, stream.time(4, true, 100, false), "10-bit references wrap at 1024");
        assertEquals(200, stream.time(1022, true, 100, true), "a B-picture six periods before its anchor");
        assertEquals(200, stream.time(254, false, 100, true),
                "an 8-bit reference after a 10-bit one wraps at 256");
        assertEquals(800 + 4 * 100, stream.time(8, false, 100, false));
        assertEquals(1200 + 4 * 100, stream.time(12, true, 100, false), "a 10-bit reference after an 8-bit one");
    }

    @Test
    @DisplayName("keeps the pictures Reference Picture Selection may name, by their 10-bit temporal reference")
    void stored() {
        var stream = new SubBitstream(0, new PicturePool());
        assertNull(stream.stored(1, 5));
        var picture = new PlusPicture(64, 48);
        picture.temporalReference = 1024 + 5;
        stream.addAnchor(picture, true);
        assertSame(picture, stream.stored(1, 5));
        assertNull(stream.stored(1, 6));
        assertSame(picture, stream.anchor(1));
        assertEquals(1, stream.heldPictures());
    }

    @Test
    @DisplayName("finds the segment rectangle of a sample only inside a recorded segment")
    void segmentRectangles() {
        var picture = new PlusPicture(64, 48);
        var out = new int[4];
        assertFalse(picture.segmentRect(5, 5, out), "no segment recorded");
        Arrays.fill(picture.segmentMap, -1);
        picture.segmentMap[0] = 0;
        picture.segmentRects = new int[]{0, 0, 15, 15};
        picture.segmentCount = 1;
        assertTrue(picture.segmentRect(5, 5, out));
        assertArrayEquals(new int[]{0, 0, 15, 15}, out);
        assertFalse(picture.segmentRect(20, 5, out), "a macroblock outside every segment");
        assertFalse(picture.segmentRect(-1, 5, out));
        assertFalse(picture.segmentRect(5, -1, out));
        assertFalse(picture.segmentRect(picture.gridWidth * 16, 5, out));
        assertFalse(picture.segmentRect(5, picture.gridHeight * 16, out));
    }
}
