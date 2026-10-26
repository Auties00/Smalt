package com.github.auties00.smalt.video.vp8.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks libvpx's regular VP8 quantizer against its arithmetic definition: the level of a
 * coefficient is the exact quotient of its rounded magnitude by the step once it reaches the zero
 * bin, and the zero bin widens with the run of zero levels before it and with the macroblock's
 * extra.
 */
@DisplayName("VP8 regular quantizer")
class RegularQuantizerTest {
    private static final int[] ZBIN_BOOST = {0, 0, 8, 10, 12, 14, 16, 20, 24, 28, 32, 36, 40, 44, 44, 44};

    /** The level a coefficient gets from the definition of the quantizer. */
    private static int expected(int z, int step, int qindex, int run, int ac, int extra) {
        var zbin = ((qindex < 48 ? 84 : 80) * step + 64) >> 7;
        var widening = (ac * ZBIN_BOOST[run]) >> 7;
        var x = Math.abs(z);
        if (x < zbin + widening + extra) {
            return 0;
        }
        var y = Math.min((x + ((48 * step) >> 7)) / step, RtDsp.MAX_LEVEL);
        return z < 0 ? -y : y;
    }

    @ParameterizedTest(name = "qindex {0}")
    @ValueSource(ints = {0, 10, 47, 48, 90, 127})
    @DisplayName("levels follow the zero bin, its zero-run widening and the exact quotient")
    void definition(int qindex) {
        var dc = Vp8Tables.DC_QLOOKUP[qindex];
        var ac = Vp8Tables.AC_QLOOKUP[qindex];
        var quantizer = new RegularQuantizer();
        quantizer.set(dc, ac, qindex);
        var random = new Random(qindex);
        var coef = new int[16];
        var levels = new short[16];
        for (var trial = 0; trial < 4000; trial++) {
            var extra = trial % 3 == 0 ? 0 : quantizer.zbinExtra(random.nextInt(40));
            var first = trial % 5 == 0 ? 1 : 0;
            var scale = 1 + random.nextInt(trial % 7 == 0 ? 32767 : 4 * ac);
            for (var k = 0; k < 16; k++) {
                coef[k] = random.nextInt(2 * scale + 1) - scale;
            }
            var original = coef.clone();
            var eob = quantizer.quantize(coef, 0, 1, first, extra, levels, 0, 1);
            var run = 0;
            var last = 0;
            for (var n = 0; n < 16; n++) {
                var rc = RtDsp.ZIGZAG[n];
                var level = expected(original[rc], rc == 0 ? dc : ac, qindex, run, ac, extra);
                run = level != 0 ? 0 : run + 1;
                if (n < first) {
                    level = 0;
                }
                assertEquals(level, levels[n], "level at " + n);
                assertEquals(level * (rc == 0 ? dc : ac), coef[rc], "dequantized at " + n);
                if (level != 0) {
                    last = n + 1;
                }
            }
            assertEquals(last, eob, "end of block");
        }
    }

    @Test
    @DisplayName("the 16-bit and 32-bit variants agree")
    void variants() {
        var quantizer = new RegularQuantizer();
        quantizer.set(Vp8Tables.DC_QLOOKUP[60] * 2, 70, 60);
        var random = new Random(7);
        var ints = new int[48];
        var shorts = new short[16];
        var a = new short[16];
        var b = new short[16];
        for (var trial = 0; trial < 1000; trial++) {
            for (var k = 0; k < 16; k++) {
                shorts[k] = (short) (random.nextInt(4001) - 2000);
                ints[3 * k + 1] = shorts[k];
            }
            var e1 = quantizer.quantize(ints, 1, 3, 0, 9, a, 0, 1);
            var e2 = quantizer.quantize(shorts, 0, 1, 0, 9, b, 0, 1);
            assertEquals(e1, e2);
            for (var k = 0; k < 16; k++) {
                assertEquals(a[k], b[k]);
                assertEquals(ints[3 * k + 1], shorts[k]);
            }
        }
    }

    @Test
    @DisplayName("the zero-bin extra scales the AC step in 1/128")
    void extra() {
        var quantizer = new RegularQuantizer();
        quantizer.set(20, 100, 30);
        assertEquals(0, quantizer.zbinExtra(0));
        assertEquals(100 * 12 >> 7, quantizer.zbinExtra(12));
        assertEquals(100 * 196 >> 7, quantizer.zbinExtra(196));
    }
}
