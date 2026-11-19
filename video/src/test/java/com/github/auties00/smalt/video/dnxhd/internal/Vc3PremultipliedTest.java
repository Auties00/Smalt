package com.github.auties00.smalt.video.dnxhd.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the premultiplication arithmetic of VC-3 coding units with {@code PMA} set against a
 * floating-point reference of the definition, exhaustively at 8 bits and on random samples at 10
 * and 12 bits.
 */
@DisplayName("VC-3 premultiplied alpha")
class Vc3PremultipliedTest {
    /** The reference premultiplication: base + (v - base) * a / max, rounded half away from zero. */
    static int premultiply(int value, double alpha, int max, int base) {
        var scaled = (value - base) * alpha / max;
        return base + (int) Math.signum(scaled) * (int) Math.floor(Math.abs(scaled) + 0.5);
    }

    /** The reference division: base + (p - base) * max / a, rounded half away from zero, clipped. */
    static int straighten(int value, double alpha, int max, int base) {
        if (alpha == 0) {
            return base;
        }
        var scaled = (value - base) * max / alpha;
        var rounded = base + (int) Math.signum(scaled) * (long) Math.floor(Math.abs(scaled) + 0.5);
        return (int) Math.clamp(rounded, 0, max);
    }

    @ParameterizedTest(name = "base {0}")
    @ValueSource(ints = {16, 128, 0, 235})
    @DisplayName("matches the definition for every 8-bit sample and alpha, and inverts it within the rounding")
    void exhaustiveEightBit(int base) {
        var plane = new short[256 * 256];
        var alpha = new short[256 * 256];
        var bytes = new byte[256 * 256];
        var alphaBytes = new byte[256 * 256];
        for (var a = 0; a < 256; a++) {
            for (var v = 0; v < 256; v++) {
                plane[a * 256 + v] = (short) v;
                alpha[a * 256 + v] = (short) a;
                alphaBytes[a * 256 + v] = (byte) a;
            }
        }
        Vc3Premultiplied.apply(plane, 256, 256, 0, 256, 0, 0, alpha, 256, 255, base);
        for (var a = 0; a < 256; a++) {
            for (var v = 0; v < 256; v++) {
                var premultiplied = plane[a * 256 + v];
                assertEquals(premultiply(v, a, 255, base), premultiplied, "premultiplied " + v + " at alpha " + a);
                bytes[a * 256 + v] = (byte) premultiplied;
            }
        }
        Vc3Premultiplied.remove(plane, 256, 256, 0, 256, 0, 0, alpha, 256, 255, base);
        Vc3Premultiplied.remove(bytes, 256, 256, 0, 256, 0, 0, alphaBytes, 256, base);
        for (var a = 0; a < 256; a++) {
            for (var v = 0; v < 256; v++) {
                var at = a * 256 + v;
                var expected = straighten(premultiply(v, a, 255, base), a, 255, base);
                assertEquals(expected, plane[at], "straightened " + v + " at alpha " + a);
                assertEquals(expected, bytes[at] & 0xFF, "8-bit straightened " + v + " at alpha " + a);
                if (a == 0) {
                    assertEquals(base, expected);
                } else {
                    // the premultiplied code is off by at most half a code, which the division scales by 255 / a
                    assertTrue(Math.abs(expected - v) <= Math.ceil(127.5 / a) + 1,
                            v + " at alpha " + a + " came back as " + expected);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} bits")
    @ValueSource(ints = {10, 12})
    @DisplayName("matches the definition for random deep samples, opaque samples unchanged")
    void deep(int depth) {
        var max = (1 << depth) - 1;
        var random = new Random(depth);
        var count = 4096;
        var plane = new short[count];
        var alpha = new short[count];
        var original = new int[count];
        for (var i = 0; i < count; i++) {
            original[i] = random.nextInt(max + 1);
            plane[i] = (short) original[i];
            alpha[i] = (short) (i < 64 ? max : random.nextInt(max + 1));
        }
        var black = 16 << (depth - 8);
        Vc3Premultiplied.apply(plane, count, count, 0, 1, 0, 0, alpha, count, max, black);
        for (var i = 0; i < count; i++) {
            assertEquals(premultiply(original[i], alpha[i], max, black), plane[i], "sample " + i);
        }
        var premultiplied = plane.clone();
        Vc3Premultiplied.remove(plane, count, count, 0, 1, 0, 0, alpha, count, max, black);
        for (var i = 0; i < count; i++) {
            assertEquals(straighten(premultiplied[i], alpha[i], max, black), plane[i], "sample " + i);
            if (i < 64) {
                assertEquals(original[i], plane[i], "opaque sample " + i);
            }
        }
    }

    @Test
    @DisplayName("scales a subsampled chroma sample by the mean alpha of the luma samples it covers")
    void subsampledChroma() {
        // a 4x2 alpha plane under one row of two 4:2:0 chroma samples
        var alpha = new short[]{1023, 511, 0, 0, 1023, 1, 0, 0};
        var chroma = new short[]{1000, 100};
        Vc3Premultiplied.apply(chroma, 2, 2, 0, 1, 1, 1, alpha, 4, 1023, 512);
        var mean = (1023 + 511 + 1023 + 1) / 4.0;
        assertEquals(premultiply(1000, mean, 1023, 512), chroma[0]);
        assertEquals(512, chroma[1], "transparent chroma sits at the middle code");
        chroma[1] = 77;
        Vc3Premultiplied.remove(chroma, 2, 2, 0, 1, 1, 1, alpha, 4, 1023, 512);
        assertEquals(straighten(premultiply(1000, mean, 1023, 512), mean, 1023, 512), chroma[0]);
        assertEquals(512, chroma[1], "a fully transparent sample straightens to the base");
        // 4:2:2 averages two horizontal neighbors only
        var row = new short[]{900};
        Vc3Premultiplied.apply(row, 1, 1, 0, 1, 1, 0, new short[]{1023, 0}, 2, 1023, 512);
        assertEquals(premultiply(900, 511.5, 1023, 512), row[0]);
    }

    @Test
    @DisplayName("divides full-range components by full-range alpha, clipping to the range")
    void fullRange() {
        assertEquals(0, Vc3Premultiplied.remove(1234, 0, 65535));
        assertEquals(65535, Vc3Premultiplied.remove(40000, 30000, 65535), "clipped");
        assertEquals(Math.round(20000 * 65535.0 / 32768), Vc3Premultiplied.remove(20000, 32768, 65535));
        assertEquals(500, Vc3Premultiplied.remove(500, 65535, 65535), "opaque");
    }
}
