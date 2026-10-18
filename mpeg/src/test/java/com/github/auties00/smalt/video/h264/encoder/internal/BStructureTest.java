package com.github.auties00.smalt.video.h264.encoder.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the coding order, reference roles and reordering depth of the B pictures between two
 * anchors, with and without a pyramid.
 */
class BStructureTest {
    @Test
    @DisplayName("without a pyramid the B pictures follow in display order and none is referenced")
    void flat() {
        var structure = new BStructure(3, false);
        assertArrayEquals(new int[]{1, 2, 3}, structure.order);
        assertArrayEquals(new boolean[]{false, false, false}, structure.reference);
        assertEquals(0, structure.referenced);
        assertEquals(1, structure.reorder);
    }

    @Test
    @DisplayName("three B pictures code the middle one first as a reference")
    void threeB() {
        var structure = new BStructure(3, true);
        assertArrayEquals(new int[]{2, 1, 3}, structure.order);
        assertArrayEquals(new boolean[]{true, false, false}, structure.reference);
        assertEquals(1, structure.referenced);
        assertEquals(2, structure.reorder);
    }

    @Test
    @DisplayName("four B pictures reference the second and the third")
    void fourB() {
        var structure = new BStructure(4, true);
        assertArrayEquals(new int[]{2, 1, 3, 4}, structure.order);
        assertArrayEquals(new boolean[]{true, false, true, false}, structure.reference);
        assertEquals(2, structure.referenced);
        assertEquals(2, structure.reorder);
    }

    @Test
    @DisplayName("a single B picture is never referenced")
    void oneB() {
        var structure = new BStructure(1, true);
        assertArrayEquals(new int[]{1}, structure.order);
        assertEquals(0, structure.referenced);
        assertEquals(1, structure.reorder);
    }

    @ParameterizedTest(name = "{0} B pictures")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("every display offset is coded once and a referenced picture is coded before the pictures between it and the anchors")
    void permutation(int count) {
        var structure = new BStructure(count, true);
        var sorted = structure.order.clone();
        Arrays.sort(sorted);
        for (var i = 0; i < count; i++) {
            assertEquals(i + 1, sorted[i]);
        }
        for (var i = 0; i < count; i++) {
            if (!structure.reference[i]) {
                continue;
            }
            for (var j = 0; j < i; j++) {
                assertTrue(Math.abs(structure.order[j] - structure.order[i]) >= 1);
            }
        }
        assertTrue(structure.reorder <= count || count == 0);
    }
}
