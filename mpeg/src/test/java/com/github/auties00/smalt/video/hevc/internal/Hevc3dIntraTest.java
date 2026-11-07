package com.github.auties00.smalt.video.hevc.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the depth intra predictor of 3D-HEVC against the specification: the depth look-up table
 * mapping against a direct transcription of equations I-55 and I-56 on random tables, the wedgelet
 * tables against the pattern counts and the {@code wedge_full_tab_idx} lengths of the reference
 * decoder, and the segment prediction's unclipped values of equation I-93.
 */
@DisplayName("HEVC 3D depth intra prediction")
class Hevc3dIntraTest {
    /**
     * Derives {@code DltValToIdx} as equation I-56 writes it.
     */
    private static int[] literalValToIdx(int[] dltVal, int bitDepth) {
        var range = 1 << bitDepth;
        var numValDlt = dltVal.length;
        var idxToVal = new int[range];
        for (var i = 0; i < range; i++) {
            idxToVal[i] = i < numValDlt ? dltVal[i] : 0;
        }
        var valToIdx = new int[range];
        for (var d = 0; d < range; d++) {
            var idxLower = 0;
            var foundFlag = false;
            for (var iL = 1; iL < numValDlt; iL++) {
                if (!foundFlag && idxToVal[iL] > d) {
                    idxLower = iL - 1;
                    foundFlag = true;
                }
            }
            var idxUpper = foundFlag ? idxLower + 1 : numValDlt - 1;
            valToIdx[d] = Math.abs(d - idxToVal[idxLower]) < Math.abs(d - idxToVal[idxUpper]) ? idxLower : idxUpper;
        }
        return valToIdx;
    }

    private static int[] randomTable(Random random, int bitDepth, int count) {
        var range = 1 << bitDepth;
        var values = new java.util.TreeSet<Integer>();
        while (values.size() < count) {
            values.add(random.nextInt(range));
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    @ParameterizedTest(name = "{0}-bit depth")
    @ValueSource(ints = {8, 10})
    @DisplayName("the look-up table mapping equals equations I-55 and I-56")
    void lookUpTable(int bitDepth) {
        var random = new Random(bitDepth * 7919L);
        var range = 1 << bitDepth;
        var intra = new Hevc3dIntra();
        for (var trial = 0; trial < 64; trial++) {
            var count = trial < 4 ? trial + 1 : 1 + random.nextInt(trial % 3 == 0 ? range : 40);
            var table = randomTable(random, bitDepth, count);
            var expected = literalValToIdx(table, bitDepth);
            intra.setLookUpTable(table, bitDepth);
            for (var d = 0; d < range; d++) {
                for (var offset = -3; offset <= 3; offset++) {
                    var index = Math.clamp(expected[d] + offset, 0, range - 1);
                    var value = index < table.length ? table[index] : 0;
                    assertEquals(value, intra.offsetValue(d, offset), "table " + Arrays.toString(table) + " d " + d);
                }
            }
        }
    }

    @Test
    @DisplayName("a full 16-bit look-up table is derived in linear time")
    void largeLookUpTable() {
        var table = new int[1 << 16];
        for (var i = 0; i < table.length; i++) {
            table[i] = i;
        }
        var intra = new Hevc3dIntra();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> intra.setLookUpTable(table, 16));
        assertEquals(12345 + 7, intra.offsetValue(12345, 7));
        assertEquals(0, intra.offsetValue(3, -10));
    }

    @Test
    @DisplayName("without a look-up table the offset value is left unclipped")
    void noLookUpTable() {
        var intra = new Hevc3dIntra();
        intra.setLookUpTable(null, 8);
        assertEquals(260, intra.offsetValue(250, 10));
        assertEquals(-5, intra.offsetValue(3, -8));
    }

    @Test
    @DisplayName("the wedgelet tables have the reference decoder's pattern counts and index lengths")
    void wedgeTables() {
        // NumWedgePattern of 4x4 to 32x32 blocks, from a separate transcription of I.6.6, and the fixed lengths of
        // wedge_full_tab_idx the reference decoder reads
        assertArrayEquals(new int[]{86, 802, 510, 510}, new int[]{Hevc3dIntra.wedgeCount(2), Hevc3dIntra.wedgeCount(3),
                Hevc3dIntra.wedgeCount(4), Hevc3dIntra.wedgeCount(5)});
        assertArrayEquals(new int[]{7, 10, 9, 9}, new int[]{Hevc3dIntra.wedgeIndexBits(2), Hevc3dIntra.wedgeIndexBits(3),
                Hevc3dIntra.wedgeIndexBits(4), Hevc3dIntra.wedgeIndexBits(5)});
        for (var log2 = 2; log2 <= 5; log2++) {
            var size = 1 << log2;
            var seen = new HashSet<String>();
            for (var k = 0; k < Hevc3dIntra.wedgeCount(log2); k++) {
                var pattern = Hevc3dIntra.wedge(log2, k);
                assertEquals(size * size, pattern.length);
                var ones = 0;
                var inverse = new byte[pattern.length];
                for (var i = 0; i < pattern.length; i++) {
                    assertTrue(pattern[i] == 0 || pattern[i] == 1);
                    ones += pattern[i];
                    inverse[i] = (byte) (1 - pattern[i]);
                }
                assertTrue(ones > 0 && ones < pattern.length, "a pattern has two segments");
                assertTrue(seen.add(Arrays.toString(pattern)), "patterns are distinct");
                assertFalse(seen.contains(Arrays.toString(inverse)), "no pattern is the inverse of another");
                if (log2 == 5) {
                    var base = Hevc3dIntra.wedge(4, k);
                    for (var y = 0; y < 32; y++) {
                        for (var x = 0; x < 32; x++) {
                            assertEquals(base[(y >> 1) * 16 + (x >> 1)], pattern[y * 32 + x]);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("segment values outside the sample range are kept for the residual")
    void unclippedSegments() {
        var intra = new Hevc3dIntra();
        intra.setLookUpTable(null, 8);
        var size = 8;
        var boundary = new int[4 * size + 1];
        Arrays.fill(boundary, 250);
        var pattern = Hevc3dIntra.wedge(3, 0);
        var frame = new HevcFrame(16, 16, 0, true);
        var outside = intra.predictSegments(boundary, size, pattern, 20, -3, frame, 8, 8);
        // every neighboring sample is 250, so both segments predict 250 before their offsets
        assertTrue(outside);
        assertEquals(270, intra.raw0());
        assertEquals(247, intra.raw1());
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var raw = pattern[y * size + x] == 0 ? intra.raw0() : intra.raw1();
                assertEquals(Math.clamp(raw, 0, 255), frame.y8[(8 + y) * 16 + 8 + x] & 0xFF);
            }
        }
        assertFalse(intra.predictSegments(boundary, size, pattern, 1, -1, frame, 8, 8));
    }
}
