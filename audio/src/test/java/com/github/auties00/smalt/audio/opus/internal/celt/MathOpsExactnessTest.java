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
    private static void assertSin(double x) {
        assertEquals(Double.doubleToRawLongBits(StrictMath.sin(x)), Double.doubleToRawLongBits(MathOps.sin(x)), "x " + x);
    }

    @Test
    @DisplayName("sin matches StrictMath.sin bit for bit across the reduction ranges, near multiples of pi / 2 and on special values")
    void sin() {
        var random = new SplittableRandom(7);
        for (var i = 0; i < 2_000_000; i++) {
            assertSin(random.nextDouble(-12, 12));
        }
        for (var i = 0; i < 500_000; i++) {
            assertSin(random.nextDouble(-1e6, 1e6));
            assertSin(Math.scalb(random.nextDouble(-1, 1), random.nextInt(-40, 21)));
        }
        // Near n pi / 2 the medium reduction cancels and takes its second and third steps.
        for (var n = -40; n <= 40; n++) {
            var center = n * (Math.PI / 2);
            for (var ulps = -64; ulps <= 64; ulps++) {
                assertSin(center + ulps * Math.ulp(center));
            }
            for (var k = 0; k < 2000; k++) {
                assertSin(center + random.nextDouble(-1e-6, 1e-6));
            }
        }
        for (var high : new long[]{0x3fe921fbL, 0x3fe921fcL, 0x3ff921fbL, 0x3ff921fcL, 0x4002d97bL, 0x4002d97cL,
                0x413921fbL, 0x413921fcL, 0x3e400000L, 0x3d000000L}) {
            for (var low : new long[]{0, 1, 0x54442d18L, 0xffffffffL}) {
                assertSin(Double.longBitsToDouble(high << 32 | low));
                assertSin(-Double.longBitsToDouble(high << 32 | low));
            }
        }
        for (var x : new double[]{0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE, 1e300}) {
            assertSin(x);
        }
    }
}
