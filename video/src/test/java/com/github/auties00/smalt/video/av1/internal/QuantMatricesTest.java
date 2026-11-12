package com.github.auties00.smalt.video.av1.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the quantizer matrix lookup over every level a frame header can select.
 */
class QuantMatricesTest {
    @Test
    @DisplayName("has a matrix for every level below 15 and none for level 15, which dequantizes flat")
    void levels() {
        for (var level = 0; level <= 15; level++) {
            for (var planeType = 0; planeType < 2; planeType++) {
                for (var txSize = 0; txSize < 19; txSize++) {
                    var matrix = QuantMatrices.get(level, planeType, txSize);
                    if (level == 15) {
                        assertNull(matrix);
                    } else {
                        assertNotNull(matrix, "level " + level + ", plane type " + planeType + ", size " + txSize);
                    }
                }
            }
        }
    }
}
