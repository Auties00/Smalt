package com.github.auties00.smalt.audio.voice.internal.aec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delay estimator's decimator on silence after a burst of noise: without flushing, its
 * filters decay into subnormal numbers that never reach zero, as the reference's do; with
 * flushing, they settle to exact zeros.
 */
@DisplayName("AEC3 decimator")
class DecimatorTest {
    private static int subnormalOutputs(boolean flush) {
        var decimator = new Decimator(flush);
        var block = new float[Aec3.BLOCK_SIZE];
        var out = new float[Aec3.BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR];
        var random = new SplittableRandom(3);
        for (var b = 0; b < 50; b++) {
            for (var i = 0; i < block.length; i++) {
                block[i] = (float) (random.nextGaussian() * 3000);
            }
            decimator.decimate(block, 0, out);
        }
        Arrays.fill(block, 0.f);
        var subnormal = 0;
        for (var b = 0; b < 2000; b++) {
            decimator.decimate(block, 0, out);
            if (b >= 1000) {
                for (var v : out) {
                    if (v != 0 && Math.abs(v) < Float.MIN_NORMAL) {
                        subnormal++;
                    }
                }
            }
        }
        return subnormal;
    }

    @Test
    @DisplayName("without flushing, silence decimates to subnormal numbers as in the reference")
    void subnormalWithoutFlush() {
        assertTrue(subnormalOutputs(false) > 0);
    }

    @Test
    @DisplayName("with flushing, silence decimates to exact zeros")
    void zerosWithFlush() {
        assertEquals(0, subnormalOutputs(true));
    }
}
