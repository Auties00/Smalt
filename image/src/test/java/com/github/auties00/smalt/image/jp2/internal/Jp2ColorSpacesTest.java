package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.box;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.colr;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.concat;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.ftyp;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.ihdr;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the conversion of the enumerated color spaces of ISO/IEC 15444-2 M.11.7.3.1 to RGB: CIE L*a*b* against
 * published values and Little CMS, CIECAM97s J*a*b* against the worked examples of the CIE TC1-34 report and a
 * forward model written from it, and the YCbCr, YPbPr, PhotoYCC, CMY, YCCK and bi-level spaces against their
 * definitions.
 */
@DisplayName("JPEG 2000 enumerated color spaces")
class Jp2ColorSpacesTest {
    /** A JPX file of one row of pixels in a color space; each pixel lists its channel samples. */
    static byte[] file(int space, byte[] parameters, int precision, boolean signed, long[][] pixels) {
        var channels = pixels[0].length;
        var planes = new long[channels][pixels.length];
        for (var i = 0; i < pixels.length; i++) {
            for (var c = 0; c < channels; c++) {
                planes[c][i] = pixels[i][c];
            }
        }
        var writer = new Part2Writer(pixels.length, 1, channels, precision, signed);
        writer.rsiz = 0;
        var shifts = new long[channels];
        if (!signed) {
            java.util.Arrays.fill(shifts, 1L << (precision - 1));
        }
        var stream = writer.write(Jp2Part2Test.forward(writer, planes, shifts, Part2Forward.Tree.dyadic(0), null));
        return concat(signature(), ftyp(), box("jp2h", ihdr(pixels.length, 1, channels, precision),
                colr(space, 0, parameters)), box("jp2c", stream));
    }

    static int[][] rgb16(byte[] file) {
        var picture = (Rgba16Picture) Jp2Decoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
        var result = new int[picture.width()][3];
        for (var x = 0; x < picture.width(); x++) {
            for (var c = 0; c < 3; c++) {
                result[x][c] = picture.pixels()[4 * x + c] & 0xFFFF;
            }
        }
        return result;
    }

    static int[][] rgb8(byte[] file) {
        var picture = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(file)).picture();
        var result = new int[picture.width()][3];
        for (var x = 0; x < picture.width(); x++) {
            for (var c = 0; c < 3; c++) {
                result[x][c] = picture.pixels()[4 * x + c] & 0xFF;
            }
        }
        return result;
    }

    static byte[] parameters(long... values) {
        var out = new ByteArrayOutputStream();
        for (var value : values) {
            Part2Forward.u32(out, value);
        }
        return out.toByteArray();
    }

    static double srgbToLinear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    @Nested
    @DisplayName("CIE L*a*b*")
    class Lab {
        // L*a*b* (D50, Bradford) of the sRGB primaries, secondaries and white, as color management modules give them
        private static final double[][] LAB = {
                {54.2905, 80.8049, 69.8910}, {87.8185, -79.2711, 80.9946}, {29.5683, 68.2986, -112.0294},
                {100, 0, 0}, {97.6071, -15.7529, 93.3885}, {60.1697, 93.5500, -60.4986}};
        private static final int[][] RGB = {
                {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}, {255, 255, 0}, {255, 0, 255}};

        @Test
        @DisplayName("enumerated parameters give the ranges and offsets; the sRGB colors come back within one level")
        void parameters() {
            var pixels = new long[LAB.length][3];
            for (var i = 0; i < LAB.length; i++) {
                pixels[i][0] = Math.round(LAB[i][0] * 65535 / 100);
                pixels[i][1] = Math.round(LAB[i][1] * 65535 / 256 + 32768);
                pixels[i][2] = Math.round(LAB[i][2] * 65535 / 256 + 32768);
            }
            var d50 = 0x00443530L;
            var rgb = rgb8(file(14, Jp2ColorSpacesTest.parameters(100, 0, 256, 32768, 256, 32768, d50), 16, false,
                    pixels));
            for (var i = 0; i < LAB.length; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(RGB[i][c], rgb[i][c], 1, "color " + i + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("the default encoding of 8-bit samples maps L* 0-100, a* -85-85 and b* -75-125")
        void defaults() {
            // L* = 54.29, a* = 80.80, b* = 69.89 in the default 8-bit encoding
            var red = new long[]{Math.round(54.2905 * 255 / 100), Math.round(80.8049 * 255 / 170 + 128),
                    Math.round(69.8910 * 255 / 200 + 96)};
            var rgb = rgb8(file(14, null, 8, false, new long[][]{red, {255, 128, 96}, {0, 128, 96}}));
            assertTrue(rgb[0][0] >= 250 && rgb[0][1] <= 12 && rgb[0][2] <= 12, java.util.Arrays.toString(rgb[0]));
            assertEquals(255, rgb[1][0]);
            assertEquals(255, rgb[1][2]);
            assertEquals(0, rgb[2][1]);
        }

        @Test
        @DisplayName("a D65 illuminant reads L*a*b* relative to D65")
        void illuminant() {
            // sRGB green, L*a*b* relative to D65 (no adaptation)
            var lab = new double[]{87.7347, -86.1827, 83.1793};
            var pixel = new long[]{Math.round(lab[0] * 65535 / 100), Math.round(lab[1] * 65535 / 256 + 32768),
                    Math.round(lab[2] * 65535 / 256 + 32768)};
            var rgb = rgb8(file(14, Jp2ColorSpacesTest.parameters(100, 0, 256, 32768, 256, 32768, 0x00443635L), 16,
                    false, new long[][]{pixel}));
            assertEquals(0, rgb[0][0], 1);
            assertEquals(255, rgb[0][1], 1);
            assertEquals(0, rgb[0][2], 1);
        }

        @Test
        @DisplayName("decodes like Little CMS's L*a*b* to sRGB transform")
        void littleCms() throws IOException, InterruptedException {
            Oracle.assume("smalt-lcmsdump");
            var values = new ArrayList<double[]>();
            var pixels = new ArrayList<long[]>();
            for (var l = 5; l <= 95; l += 15) {
                for (var a = -60; a <= 60; a += 20) {
                    for (var b = -60; b <= 60; b += 20) {
                        var pixel = new long[]{Math.round(l * 65535 / 100.0), Math.round(a * 65535 / 256.0 + 32768),
                                Math.round(b * 65535 / 256.0 + 32768)};
                        pixels.add(pixel);
                        values.add(new double[]{pixel[0] * 100 / 65535.0, (pixel[1] - 32768) * 256 / 65535.0,
                                (pixel[2] - 32768) * 256 / 65535.0});
                    }
                }
            }
            var actual = rgb16(file(14, Jp2ColorSpacesTest.parameters(100, 0, 256, 32768, 256, 32768, 0x00443530L),
                    16, false, pixels.toArray(long[][]::new)));
            var process = new ProcessBuilder(Oracle.find("smalt-lcmsdump").orElseThrow().toString(), "lab")
                    .redirectErrorStream(true).start();
            try (var input = process.getOutputStream()) {
                var text = new StringBuilder();
                for (var value : values) {
                    text.append(value[0]).append(' ').append(value[1]).append(' ').append(value[2]).append('\n');
                }
                input.write(text.toString().getBytes(StandardCharsets.US_ASCII));
            }
            var lines = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip()
                    .split("\\R");
            assertTrue(process.waitFor(1, TimeUnit.MINUTES));
            assertEquals(values.size(), lines.length);
            var worst = 0;
            for (var i = 0; i < lines.length; i++) {
                var expected = lines[i].trim().split(" ");
                for (var c = 0; c < 3; c++) {
                    worst = Math.max(worst, Math.abs(Integer.parseInt(expected[c]) - actual[i][c]));
                }
            }
            // Little CMS evaluates the sRGB tone curves through tables: allow 1/256 of the range
            assertTrue(worst <= 256, "largest difference from Little CMS " + worst);
        }
    }

    @Nested
    @DisplayName("CIECAM97s J*a*b*")
    class Jab {
        /** The forward CIECAM97s model of the CIE TC1-34 report, sections 2.1 to 2.3, giving J, C and h. */
        static double[] forward(double[] xyz, double[] white, double la, double yb) {
            double[][] mb = {{0.8951, 0.2664, -0.1614}, {-0.7502, 1.7135, 0.0367}, {0.0389, -0.0685, 1.0296}};
            double[][] mbInverse = {{0.9869929, -0.1470543, 0.1599627}, {0.4323053, 0.5183603, 0.0492912},
                    {-0.0085287, 0.0400428, 0.9684867}};
            double[][] mh = {{0.38971, 0.68898, -0.07868}, {-0.22981, 1.18340, 0.04641}, {0, 0, 1}};
            var c = 0.69;
            var nc = 1.0;
            var fll = 1.0;
            var f = 1.0;
            var rgb = Jp2Colors.apply(mb, new double[]{xyz[0] / xyz[1], 1, xyz[2] / xyz[1]});
            var w = Jp2Colors.apply(mb, new double[]{white[0] / white[1], 1, white[2] / white[1]});
            var p = Math.pow(w[2], 0.0834);
            var d = f - f / (1 + 2 * Math.pow(la, 0.25) + la * la / 300);
            var rc = (d / w[0] + 1 - d) * rgb[0];
            var gc = (d / w[1] + 1 - d) * rgb[1];
            var bc = (d / Math.pow(w[2], p) + 1 - d) * Math.pow(Math.abs(rgb[2]), p) * Math.signum(rgb[2]);
            var rcw = (d / w[0] + 1 - d) * w[0];
            var gcw = (d / w[1] + 1 - d) * w[1];
            var bcw = (d / Math.pow(w[2], p) + 1 - d) * Math.pow(w[2], p);
            var k = 1 / (5 * la + 1);
            var fl = 0.2 * Math.pow(k, 4) * 5 * la + 0.1 * Math.pow(1 - Math.pow(k, 4), 2) * Math.cbrt(5 * la);
            var n = yb / white[1];
            var nbb = 0.725 * Math.pow(1 / n, 0.2);
            var z = 1 + fll * Math.sqrt(n);
            var hpe = multiply(mh, mbInverse);
            var cones = Jp2Colors.apply(hpe, new double[]{rc * xyz[1], gc * xyz[1], bc * xyz[1]});
            var conesW = Jp2Colors.apply(hpe, new double[]{rcw * white[1], gcw * white[1], bcw * white[1]});
            var ra = compress(cones[0], fl);
            var ga = compress(cones[1], fl);
            var ba = compress(cones[2], fl);
            var a = ra - 12 * ga / 11 + ba / 11;
            var b = (ra + ga - 2 * ba) / 9;
            var h = Math.toDegrees(Math.atan2(b, a));
            if (h < 0) {
                h += 360;
            }
            var hh = h < 20.14 ? h + 360 : h;
            double[][] hues = {{20.14, 0.8}, {90, 0.7}, {164.25, 1.0}, {237.53, 1.2}, {380.14, 0.8}};
            var e = 0.0;
            for (var i = 0; i < 4; i++) {
                if (hh >= hues[i][0] && hh < hues[i + 1][0]) {
                    e = hues[i][1] + (hues[i + 1][1] - hues[i][1]) * (hh - hues[i][0]) / (hues[i + 1][0] - hues[i][0]);
                }
            }
            var achromatic = (2 * ra + ga + ba / 20 - 2.05) * nbb;
            var achromaticW = (2 * compress(conesW[0], fl) + compress(conesW[1], fl) + compress(conesW[2], fl) / 20
                    - 2.05) * nbb;
            var j = 100 * Math.pow(achromatic / achromaticW, c * z);
            var s = 50 * Math.hypot(a, b) * 100 * e * (10.0 / 13) * nc * nbb / (ra + ga + 21.0 / 20 * ba);
            var chroma = 2.44 * Math.pow(s, 0.69) * Math.pow(j / 100, 0.67 * n) * (1.64 - Math.pow(0.29, n));
            return new double[]{j, chroma, h};
        }

        private static double compress(double value, double fl) {
            var x = Math.pow(fl * Math.abs(value) / 100, 0.73);
            return Math.signum(value) * 40 * x / (x + 2) + 1;
        }

        private static double[][] multiply(double[][] a, double[][] b) {
            var result = new double[3][3];
            for (var r = 0; r < 3; r++) {
                for (var c = 0; c < 3; c++) {
                    result[r][c] = a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c];
                }
            }
            return result;
        }

        // Table 4.1 of the report: XYZ, white, L_A, then J, C, h
        private static final double[][] CASES = {
                {19.01, 20.00, 21.78, 95.05, 100.00, 108.88, 318.31, 42.44, 0.47, 219.4},
                {57.06, 43.06, 31.96, 95.05, 100.00, 108.88, 31.83, 65.27, 61.97, 19.35},
                {3.53, 6.56, 2.14, 109.85, 100.00, 35.58, 318.31, 21.04, 72.99, 175.4},
                {19.01, 20.00, 21.78, 109.85, 100.00, 35.58, 31.83, 39.88, 66.85, 250.8}};

        @Test
        @DisplayName("the test's forward model reproduces the worked examples of the report")
        void examples() {
            for (var i = 0; i < CASES.length; i++) {
                var row = CASES[i];
                var jch = forward(new double[]{row[0], row[1], row[2]}, new double[]{row[3], row[4], row[5]}, row[6],
                        20);
                assertEquals(row[7], jch[0], 0.02, "J of case " + (i + 1));
                assertEquals(row[8], jch[1], 0.02, "C of case " + (i + 1));
                if (i > 0) {
                    assertEquals(row[9], jch[2], 0.1, "h of case " + (i + 1));
                }
            }
        }

        @Test
        @DisplayName("the inverse model gives back the tristimulus values of the worked examples")
        void inverse() {
            for (var i = 1; i < CASES.length; i++) {
                var row = CASES[i];
                var model = new Jp2Colors.Ciecam97s(new double[]{row[3], row[4], row[5]}, row[6], 20, 1.0, 0.69, 1.0,
                        1.0);
                var jch = forward(new double[]{row[0], row[1], row[2]}, new double[]{row[3], row[4], row[5]}, row[6],
                        20);
                var xyz = new double[3];
                model.inverse(jch[0], jch[1], jch[2], xyz);
                for (var c = 0; c < 3; c++) {
                    assertEquals(row[c], xyz[c], 0.05, "case " + (i + 1) + " component " + c);
                }
            }
        }

        @Test
        @DisplayName("sRGB colors encoded as J*a*b* decode within one level")
        void roundTrip() {
            int[][] colors = {{255, 0, 0}, {0, 200, 0}, {30, 60, 220}, {128, 128, 128}, {250, 240, 200},
                    {90, 20, 70}, {10, 10, 10}, {200, 120, 40}};
            var white = new double[]{0.3127 / 0.3290 * 100, 100, (1 - 0.3127 - 0.3290) / 0.3290 * 100};
            double[][] toXyz = {{0.4123908, 0.3575843, 0.1804808}, {0.2126390, 0.7151687, 0.0721923},
                    {0.0193308, 0.1191948, 0.9505322}};
            var pixels = new long[colors.length][3];
            for (var i = 0; i < colors.length; i++) {
                var linear = new double[3];
                for (var c = 0; c < 3; c++) {
                    linear[c] = srgbToLinear(colors[i][c] / 255.0);
                }
                var xyz = Jp2Colors.apply(toXyz, linear);
                for (var c = 0; c < 3; c++) {
                    xyz[c] *= 100;
                }
                var jch = forward(xyz, white, 318.31, 20);
                var a = jch[1] * Math.cos(Math.toRadians(jch[2]));
                var b = jch[1] * Math.sin(Math.toRadians(jch[2]));
                pixels[i][0] = Math.round(jch[0] * 65535 / 100);
                pixels[i][1] = Math.round(a * 65535 / 255 + 32768);
                pixels[i][2] = Math.round(b * 65535 / 255 + 32768);
            }
            var rgb = rgb8(file(19, null, 16, false, pixels));
            for (var i = 0; i < colors.length; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(colors[i][c], rgb[i][c], 1, "color " + i + " channel " + c);
                }
            }
        }
    }

    @Nested
    @DisplayName("YCbCr, YPbPr and PhotoYCC")
    class Luma {
        @ParameterizedTest(name = "EnumCS {0}")
        @ValueSource(ints = {1, 3, 4, 22, 23})
        @DisplayName("luma and color differences convert back to the R'G'B' they were made from")
        void ycc(int space) {
            var bt709 = space == 1 || space == 22;
            var full = space == 3;
            var kr = bt709 ? 0.2126 : 0.299;
            var kb = bt709 ? 0.0722 : 0.114;
            int[][] colors = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {200, 150, 50}, {16, 32, 64}, {128, 128, 128}};
            var pixels = new long[colors.length][3];
            for (var i = 0; i < colors.length; i++) {
                var r = colors[i][0] / 255.0;
                var g = colors[i][1] / 255.0;
                var b = colors[i][2] / 255.0;
                var y = kr * r + (1 - kr - kb) * g + kb * b;
                var cb = (b - y) / (2 * (1 - kb));
                var cr = (r - y) / (2 * (1 - kr));
                if (full) {
                    pixels[i] = new long[]{Math.round(y * 1023), Math.round(cb * 1023 + 512),
                            Math.round(cr * 1023 + 512)};
                } else {
                    pixels[i] = new long[]{Math.round((16 + 219 * y) * 4), Math.round((128 + 224 * cb) * 4),
                            Math.round((128 + 224 * cr) * 4)};
                }
            }
            var rgb = rgb8(file(space, null, 10, false, pixels));
            for (var i = 0; i < colors.length; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(colors[i][c], rgb[i][c], 1, "color " + i + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("PhotoYCC follows the Photo CD equations")
        void photoYcc() {
            long[][] pixels = {{100, 156, 137}, {180, 120, 200}, {30, 190, 90}};
            var rgb = rgb8(file(9, null, 8, false, pixels));
            for (var i = 0; i < pixels.length; i++) {
                var y = 1.3584 * pixels[i][0];
                var c1 = 2.2179 * (pixels[i][1] - 156);
                var c2 = 1.8215 * (pixels[i][2] - 137);
                var expected = new double[]{y + c2, y - 0.194 * c1 - 0.509 * c2, y + c1};
                for (var c = 0; c < 3; c++) {
                    assertEquals(Math.clamp(Math.round(expected[c]), 0, 255), rgb[i][c], 1, "pixel " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("inks and bi-level")
    class Inks {
        @Test
        @DisplayName("CMY samples are the complements of RGB")
        void cmy() {
            var rgb = rgb8(file(11, null, 8, false, new long[][]{{0, 255, 100}, {255, 255, 255}}));
            assertEquals(List.of(255, 0, 155), List.of(rgb[0][0], rgb[0][1], rgb[0][2]));
            assertEquals(List.of(0, 0, 0), List.of(rgb[1][0], rgb[1][1], rgb[1][2]));
        }

        @Test
        @DisplayName("YCCK goes to RGB through YCbCr(2), complemented inks and the black ink")
        void ycck() {
            // white (no ink) at full luma and neutral chroma, with half black
            var rgb = rgb8(file(13, null, 8, false, new long[][]{{255, 128, 128, 0}, {255, 128, 128, 255},
                    {76, 85, 255, 0}}));
            assertEquals(255, rgb[0][0]);
            assertEquals(255, rgb[0][2]);
            assertEquals(0, rgb[1][1]);
            // YCbCr(2) of pure red is about (76, 85, 255)
            assertTrue(rgb[2][0] >= 250 && rgb[2][1] <= 5 && rgb[2][2] <= 5, java.util.Arrays.toString(rgb[2]));
        }

        @Test
        @DisplayName("bi-level(0) samples of 1 are black, bi-level(2) samples of 1 white")
        void bilevel() {
            var black = rgb8(file(0, null, 1, false, new long[][]{{1}, {0}}));
            assertEquals(0, black[0][0]);
            assertEquals(255, black[1][0]);
            var white = rgb8(file(15, null, 1, false, new long[][]{{1}, {0}}));
            assertEquals(255, white[0][0]);
            assertEquals(0, white[1][0]);
        }
    }

    @Nested
    @DisplayName("e-sRGB")
    class ExtendedSrgb {
        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {10, 12, 16})
        @DisplayName("codes of 255 * 2^(n-9) * R' + 3 * 2^(n-3) decode to R' clipped to sRGB")
        void encoding(int precision) {
            // R' of each channel: black, white, mid-gray, a quarter, below black and above white
            double[][] values = {{0, 1, 0.5}, {0.25, 0.75, 1}, {-0.4, 1.2, 0.1}};
            var pixels = new long[values.length][3];
            for (var i = 0; i < values.length; i++) {
                for (var c = 0; c < 3; c++) {
                    pixels[i][c] = Math.round(255 * Math.scalb(1.0, precision - 9) * values[i][c]
                            + 3 * Math.scalb(1.0, precision - 3));
                }
            }
            var rgb = rgb16(file(20, null, precision, false, pixels));
            for (var i = 0; i < values.length; i++) {
                for (var c = 0; c < 3; c++) {
                    var expected = Math.clamp(values[i][c], 0, 1) * 65535;
                    // one code of the n-bit encoding, rescaled to 16 bits
                    assertEquals(expected, rgb[i][c], 65535 / (255 * Math.scalb(1.0, precision - 9)) + 1,
                            "pixel " + i + " channel " + c);
                }
            }
        }
    }
}
