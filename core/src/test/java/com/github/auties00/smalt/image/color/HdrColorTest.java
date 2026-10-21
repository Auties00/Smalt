package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the high-dynamic-range side of color management: the BT.2100 transfer functions against values
 * computed from their definitions, transforms into PQ and HLG targets (BT.2408 placement of standard-dynamic-range
 * white, PQ and HLG conversion through display light, gamut conversion and tone mapping), and the ICC form of
 * high-dynamic-range profiles with its {@code cicp} tag, checked against profiles written by Skia and Little CMS
 * 2.17 and, when {@code smalt-lcmsdump} ({@code tools/testdata/icc-hdr}) is on {@code PATH}, read back by Little CMS.
 */
class HdrColorTest {
    static final String LCMS = "smalt-lcmsdump";

    static ColorProfile hdr(ColorPrimaries primaries, TransferCharacteristics transfer) {
        return ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, primaries, transfer));
    }

    static ColorProfile pq2020() {
        return hdr(ColorPrimaries.BT2020, TransferCharacteristics.PQ);
    }

    static ColorProfile hlg2020() {
        return hdr(ColorPrimaries.BT2020, TransferCharacteristics.HLG);
    }

    static ColorProfile pqMastered(double peak) {
        var display = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), peak, 0.005);
        return ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ).withMasteringDisplay(display));
    }

    static int[] convert16(ColorTransform transform, int... samples) {
        var pixels = samples.length / transform.inputChannels();
        var src = new short[samples.length];
        for (var i = 0; i < samples.length; i++) {
            src[i] = (short) samples[i];
        }
        var dst = new short[pixels * transform.outputChannels()];
        transform.transform(src, 0, transform.inputChannels(), dst, 0, transform.outputChannels(), pixels);
        var out = new int[dst.length];
        for (var i = 0; i < dst.length; i++) {
            out[i] = dst[i] & 0xFFFF;
        }
        return out;
    }

    static int[] convert8(ColorTransform transform, int... samples) {
        var pixels = samples.length / transform.inputChannels();
        var src = new byte[samples.length];
        for (var i = 0; i < samples.length; i++) {
            src[i] = (byte) samples[i];
        }
        var dst = new byte[pixels * transform.outputChannels()];
        transform.transform(src, 0, transform.inputChannels(), dst, 0, transform.outputChannels(), pixels);
        var out = new int[dst.length];
        for (var i = 0; i < dst.length; i++) {
            out[i] = dst[i] & 0xFF;
        }
        return out;
    }

    static int word(double signal) {
        return (int) Math.round(signal * 65535);
    }

    static void assertNear(int expected, int actual, int tolerance, String message) {
        assertTrue(Math.abs(expected - actual) <= tolerance, message + ": expected " + expected + " +- " + tolerance
                + " but was " + actual);
    }

    /** The HLG signal of neutral display light in candelas per square meter on the 1000-nit reference display. */
    static double hlgOfNits(double nits) {
        return Hdr.hlgOetf(Math.pow(nits / 1000.0, 1 / 1.2));
    }

    static double srgbToLinear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Returns the normalized primary matrix of SMPTE RP 177 for primaries with a D65 white. */
    static double[] npm(double rx, double ry, double gx, double gy, double bx, double by) {
        double[] p = {rx / ry, gx / gy, bx / by, 1, 1, 1, (1 - rx - ry) / ry, (1 - gx - gy) / gy, (1 - bx - by) / by};
        double[] white = {0.3127 / 0.3290, 1, (1 - 0.3127 - 0.3290) / 0.3290};
        var inverse = invert(p);
        var out = new double[9];
        for (var c = 0; c < 3; c++) {
            var s = inverse[c * 3] * white[0] + inverse[c * 3 + 1] * white[1] + inverse[c * 3 + 2] * white[2];
            for (var r = 0; r < 3; r++) {
                out[r * 3 + c] = p[r * 3 + c] * s;
            }
        }
        return out;
    }

    static double[] invert(double[] m) {
        var det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        return new double[]{
                (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det};
    }

    static double[] multiply(double[] a, double[] b) {
        var out = new double[9];
        for (var r = 0; r < 3; r++) {
            for (var c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    static byte[] resource(String name) {
        try (InputStream in = HdrColorTest.class.getResourceAsStream("hdr/" + name)) {
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("transfer functions")
    class TransferFunctions {
        // values computed in double precision from the ITU-R BT.2100 definitions
        @ParameterizedTest(name = "{0} cd/m2")
        @CsvSource({"0, 0", "0.005, 0.015076399042367938", "1, 0.14994573210018022", "100, 0.508078421517399",
                "203, 0.5806888810416109", "1000, 0.751827096247041", "4000, 0.9025723933109373", "10000, 1.0"})
        @DisplayName("PQ maps luminance to the BT.2100 signal and back")
        void pq(double nits, double signal) {
            assertEquals(signal, Hdr.nitsToPq(nits), 1e-12);
            assertEquals(nits, Hdr.pqToNits(signal), Math.max(1e-9, nits * 1e-11));
        }

        @ParameterizedTest(name = "signal {0}")
        @CsvSource({"0, 0, 0", "0.25, 0.020833333333333332, 9.605290744601332",
                "0.5, 0.08333333333333333, 50.69702849110048", "0.75, 0.26496256042100724, 203.15214593754538",
                "1.0, 1.0000000269348075, 1000.000032321769"})
        @DisplayName("HLG maps the signal to scene light, display light and back")
        void hlg(double signal, double scene, double display) {
            assertEquals(scene, Hdr.hlgToScene(signal), 1e-12);
            assertEquals(display, 1000 * Math.pow(Hdr.hlgToScene(signal), Hdr.hlgGamma(1000)), 1e-9);
            assertEquals(Math.min(signal, 1.0), Hdr.hlgOetf(scene), 1e-8);
        }

        @Test
        @DisplayName("places the BT.2408 reference white at PQ 58% and HLG 75%")
        void bt2408() {
            assertEquals(0.58, Hdr.nitsToPq(203), 0.001);
            assertEquals(0.75, hlgOfNits(203), 0.0005);
            assertEquals(203.0, ColorTransformOptions.defaults().referenceWhite());
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {10, 12})
        @DisplayName("round-trips every PQ and HLG code value through light")
        void codes(int bits) {
            var max = (1 << bits) - 1;
            for (var code = 0; code <= max; code++) {
                var signal = (double) code / max;
                assertEquals(code, Math.round(Hdr.nitsToPq(Hdr.pqToNits(signal)) * max), "PQ " + code);
                assertEquals(code, Math.round(Hdr.hlgOetf(Hdr.hlgToScene(signal)) * max), "HLG " + code);
            }
        }

        @Test
        @DisplayName("encoding tables stay within a fraction of a 16-bit code of the functions")
        void tables() {
            var pq = Hdr.Tables.pqEncode();
            var hlg = Hdr.Tables.hlgEncode();
            for (var i = 0; i <= 200_000; i++) {
                var x = Math.pow(10, -10 + 10.0 * i / 200_000);
                assertEquals(Hdr.nitsToPq(x * 10000), Hdr.lookupFourthRoot(pq, (float) x), 0.1 / 65535, "PQ " + x);
                assertEquals(Hdr.hlgOetf(x), Hdr.lookupSqrt(hlg, (float) x), 0.1 / 65535, "HLG " + x);
            }
        }
    }

    @Nested
    @DisplayName("standard-dynamic-range sources")
    class StandardSources {
        @Test
        @DisplayName("place sRGB white at 203 cd/m2 in PQ and HLG targets, at both depths")
        void white() {
            for (var primaries : List.of(ColorPrimaries.BT709, ColorPrimaries.BT2020, ColorPrimaries.SMPTE432)) {
                var pq = ColorTransform.create(ColorProfile.srgb(), hdr(primaries, TransferCharacteristics.PQ));
                var hlg = ColorTransform.create(ColorProfile.srgb(), hdr(primaries, TransferCharacteristics.HLG));
                for (var c : convert16(pq, 65535, 65535, 65535)) {
                    assertNear(word(Hdr.nitsToPq(203)), c, 1, "PQ white " + primaries);
                }
                for (var c : convert16(hlg, 65535, 65535, 65535)) {
                    assertNear(word(hlgOfNits(203)), c, 1, "HLG white " + primaries);
                }
                for (var c : convert8(pq, 255, 255, 255)) {
                    assertEquals((int) Math.round(Hdr.nitsToPq(203) * 255), c, "8-bit PQ white " + primaries);
                }
                for (var c : convert8(hlg, 255, 255, 255)) {
                    assertEquals((int) Math.round(hlgOfNits(203) * 255), c, "8-bit HLG white " + primaries);
                }
                assertArrayEquals(new int[]{0, 0, 0}, convert16(pq, 0, 0, 0));
                assertArrayEquals(new int[]{0, 0, 0}, convert16(hlg, 0, 0, 0));
            }
        }

        @Test
        @DisplayName("follow the reference white option and tone-map a white above an HLG target's limit")
        void referenceWhite() {
            var at100 = ColorTransformOptions.builder().referenceWhite(100).build();
            var pq = ColorTransform.create(ColorProfile.srgb(), pq2020(), at100);
            assertNear(word(Hdr.nitsToPq(100)), convert16(pq, 65535, 65535, 65535)[0], 1, "PQ at 100");
            var at1000 = ColorTransformOptions.builder().referenceWhite(1000).build();
            var hlg = ColorTransform.create(ColorProfile.srgb(), hlg2020(), at1000);
            assertNear(65535, convert16(hlg, 65535, 65535, 65535)[0], 1, "HLG at 1000");
            var at2000 = ColorTransformOptions.builder().referenceWhite(2000).build();
            var mapped = ColorTransform.create(ColorProfile.srgb(), hlg2020(), at2000);
            var clipped = ColorTransform.create(ColorProfile.srgb(), hlg2020(), at2000.toBuilder()
                    .toneMapping(ToneMapping.CLIP).build());
            assertNear(65535, convert16(mapped, 65535, 65535, 65535)[0], 1, "BT.2390 peak");
            // a mid gray of sRGB 0.2 is 0.0331 of white, 66 cd/m2: below the knee it keeps its luminance
            var gray = word(0.2);
            var expected = word(hlgOfNits(srgbToLinear(gray / 65535.0) * 2000));
            assertNear(expected, convert16(mapped, gray, gray, gray)[0], 2, "below the knee");
            assertNear(expected, convert16(clipped, gray, gray, gray)[0], 2, "clipped");
            var bright = word(0.8);
            assertTrue(convert16(mapped, bright, bright, bright)[0] < convert16(clipped, bright, bright, bright)[0]);
        }

        @Test
        @DisplayName("convert every 16-bit sRGB gray to the PQ signal of its luminance")
        void ramp() {
            var transform = ColorTransform.create(ColorProfile.srgb(), pq2020());
            var previous = -1;
            for (var v = 0; v < 65536; v += 13) {
                var out = convert16(transform, v, v, v);
                var expected = word(Hdr.nitsToPq(srgbToLinear(v / 65535.0) * 203));
                assertNear(expected, out[0], 3, "gray " + v);
                // the s15Fixed16 colorants of the ICC link leave neutrals within two codes of neutral
                assertNear(out[0], out[1], 2, "green of gray " + v);
                assertNear(out[0], out[2], 2, "blue of gray " + v);
                assertTrue(out[0] >= previous, "monotonic at " + v);
                previous = out[0];
            }
        }

        @Test
        @DisplayName("convert the BT.709 primaries into BT.2020 as the BT.2087 matrix does")
        void gamut() {
            var transform = ColorTransform.create(ColorProfile.srgb(), pq2020());
            // ITU-R BT.2087 table 2, linear BT.709 to BT.2020
            double[][] matrix = {{0.6274, 0.3293, 0.0433}, {0.0691, 0.9195, 0.0114}, {0.0164, 0.0880, 0.8956}};
            for (var primary = 0; primary < 3; primary++) {
                var rgb = new int[3];
                rgb[primary] = 65535;
                var out = convert16(transform, rgb);
                for (var c = 0; c < 3; c++) {
                    var expected = Hdr.nitsToPq(matrix[c][primary] * 203);
                    assertEquals(expected, out[c] / 65535.0, 0.002, "primary " + primary + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("convert gray and CMYK sources, and 8-bit input as its 16-bit widening")
        void otherSources() {
            var gray = ColorTransform.create(ColorProfile.grayGamma22(), pq2020());
            assertEquals(1, gray.inputChannels());
            assertEquals(3, gray.outputChannels());
            for (var c : convert16(gray, 65535)) {
                assertNear(word(Hdr.nitsToPq(203)), c, 1, "gray white");
            }
            var mid = convert16(gray, 32768)[0];
            assertNear(word(Hdr.nitsToPq(Math.pow(32768 / 65535.0, 2.2) * 203)), mid, 4, "gray 0.5");
            var cmyk = ColorTransform.create(TestProfiles.profile("skia-icc-misc-Coated_FOGRA39_CMYK.icc"), hlg2020());
            assertEquals(4, cmyk.inputChannels());
            var paper = convert8(cmyk, 0, 0, 0, 0);
            var ink = convert8(cmyk, 255, 255, 255, 255);
            for (var c = 0; c < 3; c++) {
                assertEquals(Math.round(hlgOfNits(203) * 255), paper[c], 3, "paper " + c);
                assertTrue(ink[c] < 40, "full ink " + c + " = " + ink[c]);
            }
            var transform = ColorTransform.create(ColorProfile.displayP3(), hlg2020());
            var random = new Random(7);
            for (var i = 0; i < 2000; i++) {
                int r = random.nextInt(256), g = random.nextInt(256), b = random.nextInt(256);
                var narrow = convert8(transform, r, g, b);
                var wide = convert16(transform, r * 257, g * 257, b * 257);
                for (var c = 0; c < 3; c++) {
                    assertNear((int) Math.round(wide[c] / 257.0), narrow[c], 1, "pixel " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("report a damaged standard-dynamic-range profile when the transform is created, either way")
    void damagedStandardProfile() {
        var bytes = ColorProfile.srgb().toByteArray();
        var count = ByteBuffer.wrap(bytes).getInt(128);
        for (var i = 0; i < count; i++) {
            var at = 132 + i * 12;
            if (new String(bytes, at, 4, StandardCharsets.US_ASCII).equals("rXYZ")) {
                System.arraycopy("zzzz".getBytes(StandardCharsets.US_ASCII), 0, bytes, at, 4);
            }
        }
        var damaged = ColorProfile.parse(bytes);
        assertThrows(MediaException.Malformed.class, () -> ColorTransform.create(damaged, ColorProfile.displayP3()));
        assertThrows(MediaException.Malformed.class, () -> ColorTransform.create(damaged, pq2020()));
        assertThrows(MediaException.Malformed.class, () -> ColorTransform.create(damaged, hlg2020()));
        assertThrows(MediaException.Malformed.class, () -> ColorTransform.create(pq2020(), damaged));
    }

    @Nested
    @DisplayName("high-dynamic-range sources")
    class HighSources {
        @Test
        @DisplayName("leave values unchanged between equal descriptions")
        void identity() {
            var random = new Random(3);
            for (var profile : List.of(pq2020(), hlg2020(), hdr(ColorPrimaries.BT709, TransferCharacteristics.PQ))) {
                var transform = ColorTransform.create(profile, ColorProfile.parse(profile.toByteArray()));
                var samples = new int[300];
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = random.nextInt(65536);
                }
                assertArrayEquals(samples, convert16(transform, samples));
            }
            // a source peak within a PQ target's limit is not compressed
            var mastered = ColorTransform.create(pqMastered(4000), pq2020());
            assertArrayEquals(new int[]{60000, 1000, 30000}, convert16(mastered, 60000, 1000, 30000));
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {10, 12})
        @DisplayName("convert PQ to HLG and back within one code value for neutral colors up to 1000 cd/m2")
        void pqHlgRoundTrip(int bits) {
            var there = ColorTransform.create(pq2020(), hlg2020());
            var back = ColorTransform.create(hlg2020(), pq2020());
            var max = (1 << bits) - 1;
            var top = (int) Math.floor(Hdr.nitsToPq(1000) * max);
            for (var code = 0; code <= top; code++) {
                var sample = (int) Math.round(code * 65535.0 / max);
                var hlg = convert16(there, sample, sample, sample);
                var pq = convert16(back, hlg);
                assertNear(code, (int) Math.round(pq[0] * (double) max / 65535), 1, "code " + code);
                assertNear(word(hlgOfNits(Hdr.pqToNits(sample / 65535.0))), hlg[0], 1, "HLG of code " + code);
            }
        }

        @ParameterizedTest(name = "HLG {0}")
        @CsvSource({"0.25, 0.29653314006622733", "0.5, 0.44159845897314537", "0.75, 0.5807671908638553",
                "1.0, 0.7518270997704155"})
        @DisplayName("convert HLG neutrals to the PQ signal of their BT.2100 display light")
        void hlgToPq(double hlg, double pq) {
            var transform = ColorTransform.create(hlg2020(), pq2020());
            for (var c : convert16(transform, word(hlg), word(hlg), word(hlg))) {
                assertNear(word(pq), c, 1, "HLG " + hlg);
            }
        }

        @Test
        @DisplayName("round-trip saturated HLG colors through PQ")
        void hlgColors() {
            var there = ColorTransform.create(hlg2020(), pq2020());
            var back = ColorTransform.create(pq2020(), hlg2020());
            var random = new Random(11);
            for (var i = 0; i < 5000; i++) {
                var rgb = new int[]{random.nextInt(65536), random.nextInt(65536), random.nextInt(65536)};
                var again = convert16(back, convert16(there, rgb));
                for (var c = 0; c < 3; c++) {
                    assertNear(rgb[c], again[c], 3, "pixel " + i + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("apply the BT.2100 HLG OOTF and its inverse with the BT.2020 luminance weights to colors")
        void hlgColorFormulas() {
            // luminance weights from the SMPTE RP 177 normalized primary matrix of BT.2020
            var weights = java.util.Arrays.copyOfRange(npm(0.708, 0.292, 0.170, 0.797, 0.131, 0.046), 3, 6);
            var toPq = ColorTransform.create(hlg2020(), pq2020());
            var toHlg = ColorTransform.create(pq2020(), hlg2020());
            var random = new Random(17);
            for (var i = 0; i < 3000; i++) {
                var rgb = new int[]{random.nextInt(65536), random.nextInt(65536), random.nextInt(65536)};
                var scene = new double[3];
                for (var c = 0; c < 3; c++) {
                    scene[c] = Hdr.hlgToScene(rgb[c] / 65535.0);
                }
                var ys = weights[0] * scene[0] + weights[1] * scene[1] + weights[2] * scene[2];
                var pq = convert16(toPq, rgb);
                for (var c = 0; c < 3; c++) {
                    var nits = ys > 0 ? 1000 * Math.pow(ys, 0.2) * scene[c] : 0;
                    assertNear(word(Hdr.nitsToPq(nits)), pq[c], 2, "HLG color " + i + " channel " + c);
                }
                var light = new double[3];
                for (var c = 0; c < 3; c++) {
                    light[c] = Math.min(Hdr.pqToNits(rgb[c] / 65535.0), 1000) / 1000;
                }
                var yd = weights[0] * light[0] + weights[1] * light[1] + weights[2] * light[2];
                var hlg = convert16(toHlg, rgb);
                for (var c = 0; c < 3; c++) {
                    var e = yd > 0 ? light[c] * Math.pow(yd, -1 / 6.0) : 0;
                    assertNear(word(Hdr.hlgOetf(Math.min(e, 1))), hlg[c], 2, "PQ color " + i + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("convert 8-bit high-dynamic-range pixels as their 16-bit widening")
        void eightBit() {
            for (var pair : List.of(List.of(pq2020(), hlg2020()), List.of(hlg2020(), pq2020()),
                    List.of(pq2020(), hdr(ColorPrimaries.BT709, TransferCharacteristics.HLG)),
                    List.of(pqMastered(4000), hlg2020()))) {
                var transform = ColorTransform.create(pair.get(0), pair.get(1));
                var random = new Random(19);
                for (var i = 0; i < 2000; i++) {
                    int r = random.nextInt(256), g = random.nextInt(256), b = random.nextInt(256);
                    var narrow = convert8(transform, r, g, b);
                    var wide = convert16(transform, r * 257, g * 257, b * 257);
                    for (var c = 0; c < 3; c++) {
                        assertNear((int) Math.round(wide[c] / 257.0), narrow[c], 1, pair + " pixel " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("convert between primaries, clipping colors outside the target gamut")
        void gamut() {
            var to709 = ColorTransform.create(pq2020(), hdr(ColorPrimaries.BT709, TransferCharacteristics.PQ));
            var to2020 = ColorTransform.create(hdr(ColorPrimaries.BT709, TransferCharacteristics.PQ), pq2020());
            for (var code = 0; code < 1024; code++) {
                var sample = (int) Math.round(code * 65535.0 / 1023);
                var out = convert16(to709, sample, sample, sample);
                for (var c : out) {
                    assertNear(sample, c, 1, "neutral " + code);
                }
            }
            var matrix = multiply(invert(npm(0.708, 0.292, 0.170, 0.797, 0.131, 0.046)),
                    npm(0.64, 0.33, 0.30, 0.60, 0.15, 0.06));
            var inverse = invert(matrix);
            var random = new Random(5);
            for (var i = 0; i < 2000; i++) {
                var rgb = new int[]{random.nextInt(65536), random.nextInt(65536), random.nextInt(65536)};
                var wide = convert16(to2020, rgb);
                for (var c = 0; c < 3; c++) {
                    var nits = 0.0;
                    for (var k = 0; k < 3; k++) {
                        nits += matrix[c * 3 + k] * Hdr.pqToNits(rgb[k] / 65535.0);
                    }
                    assertNear(word(Hdr.nitsToPq(nits)), wide[c], 1, "BT.709 color " + i + " channel " + c);
                }
                var narrow = convert16(to709, rgb);
                for (var c = 0; c < 3; c++) {
                    var nits = 0.0;
                    for (var k = 0; k < 3; k++) {
                        nits += inverse[c * 3 + k] * Hdr.pqToNits(rgb[k] / 65535.0);
                    }
                    var expected = word(Hdr.nitsToPq(Math.min(Math.max(nits, 0), 10000)));
                    assertNear(expected, narrow[c], 1, "BT.2020 color " + i + " channel " + c);
                }
            }
            var green = convert16(to709, 0, word(Hdr.nitsToPq(500)), 0);
            assertEquals(0, green[0]);
            assertEquals(0, green[2]);
            // the BT.709 red in BT.2020 coordinates (ITU-R BT.2087) comes back as pure red
            var red = convert16(to709, word(Hdr.nitsToPq(0.6274 * 100)), word(Hdr.nitsToPq(0.0691 * 100)),
                    word(Hdr.nitsToPq(0.0164 * 100)));
            assertNear(word(Hdr.nitsToPq(100)), red[0], 40, "red");
            assertTrue(red[1] < word(Hdr.nitsToPq(0.1)) && red[2] < word(Hdr.nitsToPq(0.1)), "red purity");
        }

        @Test
        @DisplayName("adapt the DCI white to D65 with the Bradford transform")
        void whiteAdaptation() {
            var transform = ColorTransform.create(hdr(ColorPrimaries.SMPTE431, TransferCharacteristics.PQ), pq2020());
            var white = word(Hdr.nitsToPq(203));
            var out = convert16(transform, white, white, white);
            assertNear(out[0], out[1], 2, "green");
            assertNear(out[0], out[2], 2, "blue");
            assertNear(white, out[1], 40, "luminance");
        }

        @Test
        @DisplayName("tone-map a PQ source brighter than the target limit with BT.2390, or clip it")
        void toneMapping() {
            var source = pqMastered(4000);
            var mapped = ColorTransform.create(source, hlg2020());
            var clipped = ColorTransform.create(source, hlg2020(),
                    ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
            var peak = word(Hdr.nitsToPq(4000));
            assertNear(65535, convert16(mapped, peak, peak, peak)[0], 1, "peak");
            assertNear(65535, convert16(clipped, peak, peak, peak)[0], 1, "clipped peak");
            var dark = word(Hdr.nitsToPq(50));
            var expectedDark = word(hlgOfNits(Hdr.pqToNits(dark / 65535.0)));
            assertNear(expectedDark, convert16(mapped, dark, dark, dark)[0], 1, "below the knee");
            var bright = word(Hdr.nitsToPq(2000));
            var nits = Hdr.eetf(Hdr.pqToNits(bright / 65535.0), 4000, 1000);
            assertNear(word(hlgOfNits(nits)), convert16(mapped, bright, bright, bright)[0], 2, "2000 cd/m2");
            assertNear(65535, convert16(clipped, bright, bright, bright)[0], 1, "clipped 2000 cd/m2");
            var limited = ColorTransform.create(source, pqMastered(1000));
            var max = 0;
            for (var v = 0; v < 65536; v += 97) {
                max = Math.max(max, convert16(limited, v, v / 2, v / 3)[0]);
            }
            assertTrue(max <= word(Hdr.nitsToPq(1000)) + 1, "limited to 1000 cd/m2: " + max);
            // hue is kept: the ratio of the components in light stays the same
            var color = convert16(limited, peak, word(Hdr.nitsToPq(1000)), 0);
            var ratio = Hdr.pqToNits(color[1] / 65535.0) / Hdr.pqToNits(color[0] / 65535.0);
            assertEquals(0.25, ratio, 0.01);
        }

        @Test
        @DisplayName("convert in place and through strides as into a separate array")
        void inPlace() {
            var random = new Random(9);
            for (var pair : List.of(List.of(pq2020(), hlg2020()), List.of(ColorProfile.srgb(), pq2020()),
                    List.of(hlg2020(), ColorProfile.srgb()))) {
                var transform = ColorTransform.create(pair.get(0), pair.get(1));
                var wide = new short[4 * 500];
                var narrow = new byte[4 * 500];
                for (var i = 0; i < wide.length; i++) {
                    wide[i] = (short) random.nextInt(65536);
                    narrow[i] = (byte) random.nextInt(256);
                }
                var wideOut = wide.clone();
                var narrowOut = narrow.clone();
                transform.transform(wide, 0, 4, wideOut, 0, 4, 500);
                transform.transform(narrow, 0, 4, narrowOut, 0, 4, 500);
                var wideIn = wide.clone();
                var narrowIn = narrow.clone();
                transform.transform(wideIn, 0, 4, wideIn, 0, 4, 500);
                transform.transform(narrowIn, 0, 4, narrowIn, 0, 4, 500);
                assertArrayEquals(wideOut, wideIn);
                assertArrayEquals(narrowOut, narrowIn);
                for (var i = 3; i < wide.length; i += 4) {
                    assertEquals(wide[i], wideOut[i], "alpha kept");
                    assertEquals(narrow[i], narrowOut[i], "alpha kept");
                }
            }
        }
    }

    @Nested
    @DisplayName("ICC form")
    class IccForm {
        static int tag(byte[] icc, String signature) {
            var buffer = ByteBuffer.wrap(icc);
            var count = buffer.getInt(128);
            for (var i = 0; i < count; i++) {
                var at = 132 + i * 12;
                if (new String(icc, at, 4, StandardCharsets.US_ASCII).equals(signature)) {
                    return buffer.getInt(at + 4);
                }
            }
            return -1;
        }

        @Test
        @DisplayName("is a version 4.4 matrix/TRC profile with a cicp tag and an MD5 profile identifier")
        void structure() throws Exception {
            for (var profile : List.of(pq2020(), hlg2020(), hdr(ColorPrimaries.SMPTE432, TransferCharacteristics.PQ))) {
                var icc = profile.toByteArray();
                var buffer = ByteBuffer.wrap(icc);
                assertEquals(icc.length, buffer.getInt(0));
                assertEquals(0x04400000, buffer.getInt(8));
                assertEquals("mntrRGB XYZ ", new String(icc, 12, 12, StandardCharsets.US_ASCII));
                var cicp = tag(icc, "cicp");
                assertEquals("cicp", new String(icc, cicp, 4, StandardCharsets.US_ASCII));
                var color = profile.colorInfo().orElseThrow();
                assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) color.primaries().code(), (byte) color.transfer().code(),
                        0, 1}, java.util.Arrays.copyOfRange(icc, cicp + 4, cicp + 12));
                var trc = tag(icc, "rTRC");
                assertEquals(trc, tag(icc, "gTRC"));
                assertEquals(trc, tag(icc, "bTRC"));
                assertEquals("curv", new String(icc, trc, 4, StandardCharsets.US_ASCII));
                assertEquals(4096, buffer.getInt(trc + 8));
                var copy = icc.clone();
                java.util.Arrays.fill(copy, 84, 100, (byte) 0);
                assertArrayEquals(MessageDigest.getInstance("MD5").digest(copy), java.util.Arrays.copyOfRange(icc, 84, 100));
                assertEquals("CICP " + color.primaries() + " / " + color.transfer(), profile.description().orElseThrow());
            }
        }

        @Test
        @DisplayName("tone curves render neutrals as the default HDR to SDR tone mapping does")
        void curves() {
            for (var profile : List.of(pq2020(), hlg2020())) {
                var icc = profile.toByteArray();
                var trc = tag(icc, "rTRC");
                var buffer = ByteBuffer.wrap(icc);
                var previous = -1;
                for (var i = 0; i < 4096; i++) {
                    var value = buffer.getShort(trc + 12 + 2 * i) & 0xFFFF;
                    assertTrue(value >= previous, "monotonic at " + i);
                    previous = value;
                }
                assertEquals(0, buffer.getShort(trc + 12) & 0xFFFF);
                assertEquals(65535, buffer.getShort(trc + 12 + 2 * 4095) & 0xFFFF);
                var transfer = profile.colorInfo().orElseThrow().transfer();
                // 50 cd/m2 lies below the knee of the BT.2390 curve from 1000 to 203 cd/m2
                var signal = transfer == TransferCharacteristics.PQ ? Hdr.nitsToPq(50) : hlgOfNits(50);
                assertEquals(50 / 203.0, Hdr.iccCurve(transfer, signal), 1e-9);
                var index = (int) Math.round(signal * 4095);
                var tabulated = (buffer.getShort(trc + 12 + 2 * index) & 0xFFFF) / 65535.0;
                assertEquals(Hdr.iccCurve(transfer, index / 4095.0), tabulated, 0.6 / 65535);
                assertEquals(1.0, Hdr.iccCurve(transfer, 1.0));
                assertEquals(Hdr.eetf(203, 1000, 203) / 203, Hdr.iccCurve(transfer,
                        transfer == TransferCharacteristics.PQ ? Hdr.nitsToPq(203) : hlgOfNits(203)), 1e-6);
            }
        }

        @Test
        @DisplayName("parses back to an equal profile; the mastering metadata stays outside the ICC form")
        void roundTrip() {
            var mastered = pqMastered(4000);
            var parsed = ColorProfile.parse(mastered.toByteArray());
            assertTrue(parsed.isHighDynamicRange());
            assertArrayEquals(pq2020().toByteArray(), mastered.toByteArray());
            assertNotEquals(mastered, parsed);
            assertEquals(pq2020(), parsed);
            assertEquals(pq2020().hashCode(), parsed.hashCode());
            assertEquals(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ), parsed.colorInfo().orElseThrow());
            var light = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                    ColorPrimaries.BT2020, TransferCharacteristics.PQ).withContentLightLevel(new ContentLightLevel(800, 200)));
            assertNotEquals(pq2020(), light);
            assertEquals(pq2020(), ColorProfile.parse(light.toByteArray()));
        }

        @Test
        @DisplayName("reads the cicp tags of Skia and Little CMS 2.17 profiles")
        void foreign() throws Exception {
            var skia = TestProfiles.profile("skia-icc-misc-Rec2020_PQ_cicp.icc");
            assertTrue(skia.isHighDynamicRange());
            assertEquals(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ), skia.colorInfo().orElseThrow());
            var lattice = new int[3 * 9 * 9 * 9];
            var n = 0;
            for (var r = 0; r < 9; r++) {
                for (var g = 0; g < 9; g++) {
                    for (var b = 0; b < 9; b++) {
                        lattice[n++] = r * 8191;
                        lattice[n++] = g * 8191;
                        lattice[n++] = b * 8191;
                    }
                }
            }
            assertArrayEquals(convert16(ColorTransform.create(pq2020(), ColorProfile.srgb()), lattice),
                    convert16(ColorTransform.create(skia, ColorProfile.srgb()), lattice));
            var digest = MessageDigest.getInstance("SHA-256");
            var pinned = List.of(
                    List.of("lcms-pq.icc", "3e205ea515d718c5aa31c99dd443b0aa16354ca650b8e15e4c26ca37fea4b1b0", "PQ"),
                    List.of("lcms-hlg.icc", "2cf08cd3b79a86551309c9032e840cb619f954a756c434ef788a90a8f6365d92", "HLG"));
            for (var row : pinned) {
                var bytes = resource(row.get(0));
                assertEquals(row.get(1), HexFormat.of().formatHex(digest.digest(bytes)), row.get(0));
                var profile = ColorProfile.parse(bytes);
                assertTrue(profile.isHighDynamicRange(), row.get(0));
                assertEquals(TransferCharacteristics.valueOf(row.get(2)), profile.colorInfo().orElseThrow().transfer());
                assertEquals(ColorPrimaries.BT2020, profile.colorInfo().orElseThrow().primaries());
                assertEquals("4.4.0", profile.version());
            }
        }

        @Test
        @DisplayName("a HEIF or AVIF colr ICC profile with a PQ or HLG cicp tag describes high dynamic range")
        void heif() {
            var picture = com.github.auties00.smalt.image.RgbaPicture.allocate(2, 2, false);
            for (var bytes : List.of(resource("lcms-hlg.icc"), pq2020().toByteArray())) {
                var profile = com.github.auties00.smalt.internal.heif.HeifPictures.colorProfile(bytes, picture)
                        .orElseThrow();
                assertTrue(profile.isHighDynamicRange());
            }
        }

        @Test
        @DisplayName("ignores a cicp tag that is not a PQ or HLG RGB description")
        void ignored() {
            var base = pq2020().toByteArray();
            var at = tag(base, "cicp");
            // primaries, transfer, matrix, range overrides
            int[][] variants = {{2, 16, 0, 1}, {0, 16, 0, 1}, {99, 16, 0, 1}, {9, 13, 0, 1}, {9, 1, 0, 1},
                    {9, 16, 9, 1}, {9, 16, 0, 0}, {9, 99, 0, 1}, {9, 17, 0, 1}};
            for (var variant : variants) {
                var bytes = base.clone();
                for (var i = 0; i < 4; i++) {
                    bytes[at + 8 + i] = (byte) variant[i];
                }
                var profile = ColorProfile.parse(bytes);
                assertFalse(profile.isHighDynamicRange(), java.util.Arrays.toString(variant));
                assertTrue(profile.colorInfo().isEmpty());
                assertEquals(3, ColorTransform.create(profile, ColorProfile.srgb()).outputChannels());
            }
            var wrongType = base.clone();
            wrongType[at] = 'x';
            assertFalse(ColorProfile.parse(wrongType).isHighDynamicRange());
            // a cicp tag shorter than the 12 bytes of a cicpType is not read
            var count = ByteBuffer.wrap(base).getInt(128);
            for (var i = 0; i < count; i++) {
                var entry = 132 + i * 12;
                if (new String(base, entry, 4, StandardCharsets.US_ASCII).equals("cicp")) {
                    var shortTag = base.clone();
                    ByteBuffer.wrap(shortTag).putInt(entry + 8, 11);
                    assertFalse(ColorProfile.parse(shortTag).isHighDynamicRange());
                }
            }
            // a device link, abstract or named color profile stays one, and transforms still reject it
            for (var deviceClass : List.of("link", "abst", "nmcl")) {
                var bytes = base.clone();
                System.arraycopy(deviceClass.getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
                var profile = ColorProfile.parse(bytes);
                assertFalse(profile.isHighDynamicRange(), deviceClass);
                assertThrows(MediaException.Unsupported.class, () -> ColorTransform.create(profile, ColorProfile.srgb()));
            }
            var gray = ColorProfile.grayGamma22().toByteArray();
            assertFalse(ColorProfile.parse(gray).isHighDynamicRange());
        }

        @Test
        @DisplayName("survives damaged and truncated high-dynamic-range profiles")
        void robustness() {
            var base = hlg2020().toByteArray();
            var random = new Random(13);
            for (var i = 0; i < 3000; i++) {
                var bytes = base.clone();
                var flips = 1 + random.nextInt(8);
                for (var f = 0; f < flips; f++) {
                    var position = random.nextInt(i % 2 == 0 ? 600 : bytes.length);
                    bytes[position] = (byte) random.nextInt(256);
                }
                if (i % 7 == 0) {
                    bytes = java.util.Arrays.copyOf(bytes, random.nextInt(bytes.length));
                }
                try {
                    var profile = ColorProfile.parse(bytes);
                    var transform = ColorTransform.create(profile, ColorProfile.srgb());
                    transform.transform(new short[]{1000, 30000, (short) 65535}, 0, transform.inputChannels(), new short[3],
                            0, 3, 1);
                } catch (MediaException expected) {
                    // damaged profiles are reported as media exceptions
                }
            }
        }

        @Test
        @DisplayName("Little CMS reads the version 4.4 form and renders its neutrals as Smalt's default tone mapping")
        void littleCms() throws IOException {
            Assumptions.assumeTrue(Oracle.find(LCMS).isPresent(), LCMS + " is not on PATH");
            var directory = Files.createDirectories(Path.of("target", "hdr-color-tests"));
            var own = directory.resolve("lcms-pq.icc");
            Files.write(own, resource("lcms-pq.icc"));
            var ownInfo = lcms("info", own);
            // the tool reads the cicp tag of the 16-byte layout Little CMS 2.17 writes
            assertTrue(ownInfo.contains("cicp 9 16 0 1"), ownInfo.toString());
            for (var profile : List.of(pq2020(), hlg2020())) {
                var path = directory.resolve(profile.colorInfo().orElseThrow().transfer() + ".icc");
                Files.write(path, profile.toByteArray());
                var info = lcms("info", path);
                assertTrue(info.contains("version 04400000"), info.toString());
                assertTrue(info.contains("space RGB "), info.toString());
                var transform = ColorTransform.create(profile, ColorProfile.srgb());
                for (var line : lcms("gray", path)) {
                    var fields = line.trim().split(" ");
                    var k = Integer.parseInt(fields[0]);
                    var smalt = convert16(transform, k * 257, k * 257, k * 257);
                    for (var c = 0; c < 3; c++) {
                        assertNear(smalt[c], Integer.parseInt(fields[1 + c]), 2 * 257, profile + " gray " + k);
                    }
                }
            }
        }

        static List<String> lcms(String command, Path profile) {
            var out = Oracle.run(LCMS, Duration.ofMinutes(1), null, List.of(command, profile.toAbsolutePath().toString()));
            return new String(out, StandardCharsets.US_ASCII).lines().toList();
        }
    }
}
