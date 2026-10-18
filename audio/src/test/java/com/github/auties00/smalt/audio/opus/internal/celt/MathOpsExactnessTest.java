package com.github.auties00.smalt.audio.opus.internal.celt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the closed forms that replace libopus loops against a transcription of the loops themselves.
 */
class MathOpsExactnessTest {
    // libopus mathops.c isqrt32: bit-by-bit square root of an unsigned 32-bit value
    private static int referenceIsqrt32(int val) {
        var g = 0;
        var bshift = (EntropyCoder.ilog(val) - 1) >> 1;
        var b = 1 << bshift;
        do {
            var t = ((g << 1) + b) << bshift;
            if (Integer.compareUnsigned(t, val) <= 0) {
                g += b;
                val -= t;
            }
            b >>>= 1;
            bshift--;
        } while (bshift >= 0);
        return g;
    }

    @Test
    @DisplayName("isqrt32 matches the reference loop on every perfect square, its neighbors and random values")
    void isqrt32() {
        for (long k = 1; k <= 65536; k++) {
            for (var delta = -1; delta <= 1; delta++) {
                var v = k * k + delta;
                if (v >= 1 && v <= 0xFFFFFFFFL) {
                    assertEquals(referenceIsqrt32((int) v), MathOps.isqrt32((int) v), "value " + v);
                }
            }
        }
        var random = new SplittableRandom(3);
        for (var i = 0; i < 2_000_000; i++) {
            var v = random.nextInt();
            if (v != 0) {
                assertEquals(referenceIsqrt32(v), MathOps.isqrt32(v), "value " + Integer.toUnsignedString(v));
            }
        }
        assertEquals(referenceIsqrt32(-1), MathOps.isqrt32(-1));
    }

    @Test
    @DisplayName("cos matches StrictMath.cos bit for bit on celt_cos_norm angles, boundaries and random arguments")
    void cos() {
        var random = new SplittableRandom(5);
        for (var i = 0; i < 3_000_000; i++) {
            var theta = random.nextFloat();
            var x = (.5f * MathOps.PI) * theta;
            assertEquals(Double.doubleToRawLongBits(StrictMath.cos(x)), Double.doubleToRawLongBits(MathOps.cos(x)), "theta " + theta);
        }
        for (var i = 0; i < 1_000_000; i++) {
            var x = random.nextDouble(-8, 8);
            assertEquals(Double.doubleToRawLongBits(StrictMath.cos(x)), Double.doubleToRawLongBits(MathOps.cos(x)), "x " + x);
        }
        for (var high : new long[]{0x3fe921fbL, 0x3fe921fcL, 0x3ff921fbL, 0x3ff921fcL, 0x4002d97bL, 0x4002d97cL, 0x3e400000L, 0x3d000000L}) {
            for (var low : new long[]{0, 1, 0x54442d18L, 0xffffffffL}) {
                var x = Double.longBitsToDouble(high << 32 | low);
                assertEquals(Double.doubleToRawLongBits(StrictMath.cos(x)), Double.doubleToRawLongBits(MathOps.cos(x)), "x " + x);
            }
        }
        for (var x : new double[]{0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertEquals(Double.doubleToRawLongBits(StrictMath.cos(x)), Double.doubleToRawLongBits(MathOps.cos(x)), "x " + x);
        }
    }
}
