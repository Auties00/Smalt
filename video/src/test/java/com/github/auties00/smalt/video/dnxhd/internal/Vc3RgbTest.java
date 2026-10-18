package com.github.auties00.smalt.video.dnxhd.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the RGB sample handling of SMPTE ST 2019-1 against hand-computed values: the BT.709
 * Y'CbCr to RGB conversion of alternate color space macroblocks (clause 8.1.1), the encoder's
 * conversion into them, and the RGB levels of Table 2.
 */
@DisplayName("VC-3 RGB samples")
class Vc3RgbTest {
    static short[][] macroblock(int y, int cb, int cr) {
        var planes = new short[3][256];
        java.util.Arrays.fill(planes[0], (short) y);
        java.util.Arrays.fill(planes[1], (short) cb);
        java.util.Arrays.fill(planes[2], (short) cr);
        return planes;
    }

    static int[] convert(int depth, int y, int cb, int cr) {
        var planes = macroblock(y, cb, cr);
        Vc3Rgb.toRgb(new Vc3Target(true, planes, null, null, null, new int[]{16, 16, 16}, 0, 1, 1, 0, 0, 0), 0, 0, depth);
        for (var plane : planes) {
            for (var sample : plane) {
                assertEquals(plane[0], sample, "uniform macroblock");
            }
        }
        return new int[]{planes[0][0], planes[1][0], planes[2][0]};
    }

    @ParameterizedTest(name = "{0}-bit Y={1} Cb={2} Cr={3}")
    @CsvSource({
            // white, black and mid gray keep R = G = B = Y
            "10, 940, 512, 512, 940, 940, 940",
            "10, 64, 512, 512, 64, 64, 64",
            "10, 502, 512, 512, 502, 502, 502",
            "12, 3760, 2048, 2048, 3760, 3760, 3760",
            // BT.709 primaries quantized to 10 bits: Y = 64 + 876 Ey, Cb/Cr = 512 + 896 E
            "10, 250, 409, 960, 940, 64, 64",
            "10, 691, 167, 105, 64, 940, 64",
            "10, 127, 960, 471, 64, 64, 940"})
    @DisplayName("converts Y'CbCr macroblocks with the BT.709 matrix at the levels of Y'")
    void conversion(int depth, int y, int cb, int cr, int r, int g, int b) {
        var rgb = convert(depth, y, cb, cr);
        assertTrue(Math.abs(rgb[0] - r) <= 1, "R " + rgb[0]);
        assertTrue(Math.abs(rgb[1] - g) <= 1, "G " + rgb[1]);
        assertTrue(Math.abs(rgb[2] - b) <= 1, "B " + rgb[2]);
    }

    @ParameterizedTest(name = "{0}-bit R={1} G={2} B={3}")
    @CsvSource({
            "10, 65535, 65535, 65535, 940, 512, 512",
            "10, 0, 0, 0, 64, 512, 512",
            "12, 65535, 65535, 65535, 3760, 2048, 2048",
            // BT.709 primaries quantized to 10 bits: Y = 64 + 876 Ey, Cb/Cr = 512 + 896 E
            "10, 65535, 0, 0, 250, 409, 960",
            "10, 0, 65535, 0, 691, 167, 105",
            "10, 0, 0, 65535, 127, 960, 471"})
    @DisplayName("converts full-range RGB into BT.709 Y'CbCr codes at the levels of Table 2")
    void forward(int depth, int r, int g, int b, int y, int cb, int cr) {
        var first = new short[]{(short) r};
        var second = new short[]{(short) g};
        var third = new short[]{(short) b};
        Vc3Rgb.toYcbcr(first, second, third, depth);
        assertEquals(y, first[0], "Y'");
        assertEquals(cb, second[0], "Cb");
        assertEquals(cr, third[0], "Cr");
    }

    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {10, 12})
    @DisplayName("round-trips RGB through Y'CbCr macroblocks within two codes of the RGB levels")
    void roundTrip(int depth) {
        var random = new java.util.Random(depth);
        var planes = new short[3][256];
        var expected = new short[3][256];
        for (var i = 0; i < 256; i++) {
            for (var c = 0; c < 3; c++) {
                planes[c][i] = (short) random.nextInt(65536);
                expected[c][i] = planes[c][i];
            }
        }
        for (var c = 0; c < 3; c++) {
            Vc3Rgb.compress(expected[c], depth);
        }
        Vc3Rgb.toYcbcr(planes[0], planes[1], planes[2], depth);
        Vc3Rgb.toRgb(new Vc3Target(true, planes, null, null, null, new int[]{16, 16, 16}, 0, 1, 1, 0, 0, 0), 0, 0, depth);
        // rounding Y' and Cr to codes moves R by up to 0.5 + 1.54 * 0.5 codes before R itself is rounded
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < 256; i++) {
                assertTrue(Math.abs(planes[c][i] - expected[c][i]) <= 2,
                        "channel " + c + " sample " + i + ": " + planes[c][i] + " vs " + expected[c][i]);
            }
        }
    }

    @Test
    @DisplayName("clips converted samples to the code range")
    void clipping() {
        assertEquals(1023, convert(10, 1019, 512, 1019)[0]);
        assertEquals(0, convert(10, 4, 512, 4)[0]);
        assertEquals(0, convert(12, 16, 16, 2048)[2]);
    }

    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {10, 12})
    @DisplayName("maps the in-band levels of Table 2 to full range and back")
    void levels(int depth) {
        var black = 16 << (depth - 8);
        var white = 235 << (depth - 8);
        assertEquals(0, Vc3Rgb.expand(black, depth, false, 65535));
        assertEquals(65535, Vc3Rgb.expand(white, depth, false, 65535));
        assertEquals(0, Vc3Rgb.expand(black - 4, depth, false, 65535));
        assertEquals(65535, Vc3Rgb.expand(white + 4, depth, false, 65535));
        assertEquals(255, Vc3Rgb.expand(white, depth, false, 255));
        var plane = new short[]{0, (short) 65535, (short) 32768};
        Vc3Rgb.compress(plane, depth);
        assertEquals(black, plane[0]);
        assertEquals(white, plane[1]);
        assertEquals((black + white + 1) / 2, plane[2]);
        for (var code = black; code <= white; code++) {
            var round = new short[]{(short) Vc3Rgb.expand(code, depth, false, 65535)};
            Vc3Rgb.compress(round, depth);
            assertEquals(code, round[0], "code " + code + " survives the round trip");
        }
    }

    @Test
    @DisplayName("keeps the full code range for an out-of-band color volume")
    void outOfBand() {
        assertEquals(0, Vc3Rgb.expand(0, 10, true, 65535));
        assertEquals(65535, Vc3Rgb.expand(1023, 10, true, 65535));
        assertEquals(4100, Vc3Rgb.expand(64, 10, true, 65535));
    }
}
