package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers 16-bit accuracy, high-dynamic-range tone mapping, rendering intents and the transform
 * API contract. 8-bit accuracy is {@link LcmsOracleTest}'s.
 */
class ColorTransformTest {
    static short[] ramp16(int channels, int pixels, long seed) {
        var random = new SplittableRandom(seed);
        var out = new short[channels * pixels];
        for (var i = 0; i < out.length; i++) {
            out[i] = (short) (i < channels * 256 ? (i / channels) * 257 : random.nextInt(65536));
        }
        return out;
    }

    static ColorProfile hdr(TransferCharacteristics transfer, int maxCll) {
        var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020, transfer,
                com.github.auties00.smalt.image.ChromaSiting.UNSPECIFIED, Optional.empty(),
                maxCll > 0 ? Optional.of(new ContentLightLevel(maxCll, maxCll / 4)) : Optional.empty());
        return ColorProfile.of(color);
    }

    static int[] toSrgb8(ColorTransform transform, double r, double g, double b) {
        var in = new short[]{(short) Math.round(r * 65535), (short) Math.round(g * 65535), (short) Math.round(b * 65535)};
        var out = new short[3];
        transform.transform(in, 0, 3, out, 0, 3, 1);
        return new int[]{ColorMath.wordToByte(out[0] & 0xFFFF), ColorMath.wordToByte(out[1] & 0xFFFF),
                ColorMath.wordToByte(out[2] & 0xFFFF)};
    }

    @Nested
    @DisplayName("16-bit transforms")
    class SixteenBit {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "compact-icc-DisplayP3-v4.icc, compact-icc-sRGB-v4.icc",
                "compact-icc-sRGB-v4.icc, compact-icc-DisplayP3-v4.icc",
                "compact-icc-AdobeCompat-v2.icc, compact-icc-sRGB-v2-micro.icc",
                "compact-icc-ProPhoto-v4.icc, compact-icc-sRGB-v4.icc",
                "skia-icc-misc-Gray_Gamma_22.icc, compact-icc-sRGB-v4.icc",
                "compact-icc-sRGB-v4.icc, compact-icc-sGrey-v4.icc"
        })
        @DisplayName("matrix/TRC transforms match the floating-point pipeline within 16 units")
        void matrixShaperAccuracy(String source, String target) {
            var from = TestProfiles.profile(source);
            var to = TestProfiles.profile(target);
            var transform = ColorTransform.create(from, to);
            var inputs = from.channels();
            var outputs = to.channels();
            var pixels = 4096;
            var in = ramp16(inputs, pixels, 7);
            var out = new short[outputs * pixels];
            transform.transform(in, 0, inputs, out, 0, outputs, pixels);
            var pipeline = Linker.link(List.of(Linker.Node.of(from.icc(), 0, false), Linker.Node.of(to.icc(), 0, false)));
            var fin = new float[inputs];
            var fout = new float[outputs];
            var max = 0;
            for (var p = 0; p < pixels; p++) {
                for (var c = 0; c < inputs; c++) {
                    fin[c] = (in[p * inputs + c] & 0xFFFF) / 65535f;
                }
                pipeline.evaluate(fin, fout);
                for (var c = 0; c < outputs; c++) {
                    var expected = Math.round(Math.min(Math.max(fout[c], 0f), 1f) * 65535f);
                    max = Math.max(max, Math.abs(expected - (out[p * outputs + c] & 0xFFFF)));
                }
            }
            assertTrue(max <= 16, "max difference " + max);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "compact-icc-DisplayP3-v4.icc, compact-icc-sRGB-v4.icc, 1",
                "skia-icc-color.org-sRGB_ICC_v4_Appearance.icc, compact-icc-sRGB-v4.icc, 3",
                "skia-icc-misc-Coated_FOGRA39_CMYK.icc, compact-icc-sRGB-v4.icc, 2"
        })
        @DisplayName("agree with the 8-bit transforms")
        void consistentWithEightBit(String source, String target, int tolerance) {
            var from = TestProfiles.profile(source);
            var to = TestProfiles.profile(target);
            var transform = ColorTransform.create(from, to);
            var input = TestProfiles.inputs(from.colorSpace());
            var channels = from.channels();
            var pixels = input.length / channels;
            var wide = new short[input.length];
            for (var i = 0; i < input.length; i++) {
                wide[i] = (short) ((input[i] & 0xFF) * 257);
            }
            var narrow = new byte[pixels * 3];
            var wideOut = new short[pixels * 3];
            transform.transform(input, 0, channels, narrow, 0, 3, pixels);
            transform.transform(wide, 0, channels, wideOut, 0, 3, pixels);
            for (var i = 0; i < narrow.length; i++) {
                var difference = Math.abs((narrow[i] & 0xFF) - ColorMath.wordToByte(wideOut[i] & 0xFFFF));
                assertTrue(difference <= tolerance, "sample " + i + " differs by " + difference);
            }
        }
    }

    @Nested
    @DisplayName("high dynamic range")
    class HighDynamicRange {
        @Test
        @DisplayName("PQ maps the 203 cd/m2 reference white to sRGB white without compression and black to black")
        void pqWhite() {
            var transform = ColorTransform.create(hdr(TransferCharacteristics.PQ, 0), ColorProfile.srgb(),
                    ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
            var white = Hdr.nitsToPq(203);
            assertArrayEquals(new int[]{255, 255, 255}, toSrgb8(transform, white, white, white));
            assertArrayEquals(new int[]{0, 0, 0}, toSrgb8(transform, 0, 0, 0));
            var compressed = ColorTransform.create(hdr(TransferCharacteristics.PQ, 0), ColorProfile.srgb());
            var dimmed = toSrgb8(compressed, white, white, white);
            assertTrue(dimmed[0] >= 220 && dimmed[0] < 255 && dimmed[0] == dimmed[1] && dimmed[1] == dimmed[2],
                    "BT.2390 places the reference white of 1000 cd/m2 content at " + dimmed[0]);
            var fits = ColorTransform.create(hdr(TransferCharacteristics.PQ, 200), ColorProfile.srgb());
            assertArrayEquals(new int[]{255, 255, 255}, toSrgb8(fits, white, white, white));
            var mid = toSrgb8(transform, Hdr.nitsToPq(50), Hdr.nitsToPq(50), Hdr.nitsToPq(50));
            var expected = (int) Math.round(255 * (1.055 * Math.pow(50 / 203.0, 1 / 2.4) - 0.055));
            assertTrue(Math.abs(mid[0] - expected) <= 1, mid[0] + " vs " + expected);
        }

        @Test
        @DisplayName("BT.2390 rolls highlights off where clipping saturates")
        void rollOff() {
            var profile = hdr(TransferCharacteristics.PQ, 1000);
            var tonemapped = ColorTransform.create(profile, ColorProfile.srgb());
            var clipped = ColorTransform.create(profile, ColorProfile.srgb(),
                    ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
            var bright = Hdr.nitsToPq(150);
            assertTrue(toSrgb8(tonemapped, bright, bright, bright)[0] < toSrgb8(clipped, bright, bright, bright)[0]);
            var peak = Hdr.nitsToPq(1000);
            assertEquals(255, toSrgb8(tonemapped, peak, peak, peak)[0]);
            var previous = -1;
            for (var nits = 0; nits <= 1000; nits += 5) {
                var signal = Hdr.nitsToPq(nits);
                var value = toSrgb8(tonemapped, signal, signal, signal)[1];
                assertTrue(value >= previous, "monotonic at " + nits);
                previous = value;
            }
            var dark = Hdr.nitsToPq(20);
            assertArrayEquals(toSrgb8(clipped, dark, dark, dark), toSrgb8(tonemapped, dark, dark, dark));
        }

        @Test
        @DisplayName("HLG maps its 75% reference level to sRGB white")
        void hlg() {
            var transform = ColorTransform.create(hdr(TransferCharacteristics.HLG, 0), ColorProfile.srgb(),
                    ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
            var white = toSrgb8(transform, 0.75, 0.75, 0.75);
            assertTrue(white[0] >= 254, "HLG 75% is " + white[0]);
            assertArrayEquals(new int[]{0, 0, 0}, toSrgb8(transform, 0, 0, 0));
        }

        @Test
        @DisplayName("8-bit and 16-bit inputs give the same result")
        void depths() {
            var transform = ColorTransform.create(hdr(TransferCharacteristics.PQ, 4000), ColorProfile.srgb());
            var narrow = new byte[]{(byte) 130, (byte) 160, (byte) 190};
            var wide = new short[]{(short) (130 * 257), (short) (160 * 257), (short) (190 * 257)};
            var narrowOut = new byte[3];
            var wideOut = new short[3];
            transform.transform(narrow, 0, 3, narrowOut, 0, 3, 1);
            transform.transform(wide, 0, 3, wideOut, 0, 3, 1);
            for (var c = 0; c < 3; c++) {
                assertEquals(narrowOut[c] & 0xFF, ColorMath.wordToByte(wideOut[c] & 0xFFFF));
            }
        }
    }

    @Nested
    @DisplayName("intents")
    class Intents {
        @Test
        @DisplayName("a lookup-table profile honors its intents and black point compensation")
        void differ() {
            var cmyk = TestProfiles.profile("skia-icc-misc-Coated_FOGRA39_CMYK.icc");
            var input = TestProfiles.inputs(ProfileColorSpace.CMYK);
            var pixels = input.length / 4;
            var results = new java.util.ArrayList<byte[]>();
            for (var options : List.of(ColorTransformOptions.defaults(), ColorTransformOptions.relativeColorimetric(),
                    ColorTransformOptions.absoluteColorimetric(),
                    ColorTransformOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC).build())) {
                var out = new byte[pixels * 3];
                ColorTransform.create(cmyk, ColorProfile.srgb(), options).transform(input, 0, 4, out, 0, 3, pixels);
                results.add(out);
            }
            for (var i = 0; i < results.size(); i++) {
                for (var j = i + 1; j < results.size(); j++) {
                    assertTrue(!java.util.Arrays.equals(results.get(i), results.get(j)), i + " and " + j);
                }
            }
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("checks its arguments")
        void arguments() {
            var transform = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
            assertEquals(3, transform.inputChannels());
            assertEquals(3, transform.outputChannels());
            var pixels = new byte[12];
            assertThrows(IllegalArgumentException.class, () -> transform.transform(pixels, 0, 2, pixels, 0, 3, 1));
            assertThrows(IllegalArgumentException.class, () -> transform.transform(pixels, 0, 3, pixels, 0, 3, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> transform.transform(pixels, 0, 4, pixels, 0, 4, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> transform.transform(pixels, -1, 4, pixels, 0, 4, 1));
            assertThrows(NullPointerException.class, () -> transform.transform((byte[]) null, 0, 4, pixels, 0, 4, 1));
            assertThrows(NullPointerException.class, () -> ColorTransform.create(null, ColorProfile.srgb()));
            transform.transform(pixels, 0, 4, pixels, 0, 4, 0);
        }

        @Test
        @DisplayName("works in place and leaves extra samples untouched")
        void inPlace() {
            var transform = ColorTransform.create(ColorProfile.adobeRgb(), ColorProfile.srgb());
            var random = new SplittableRandom(3);
            var rgba = new byte[4 * 1000];
            random.nextBytes(rgba);
            var copy = rgba.clone();
            var separate = new byte[rgba.length];
            transform.transform(rgba, 0, 4, separate, 0, 4, 1000);
            transform.transform(rgba, 0, 4, rgba, 0, 4, 1000);
            for (var i = 0; i < rgba.length; i++) {
                if (i % 4 == 3) {
                    assertEquals(copy[i], rgba[i], "alpha");
                } else {
                    assertEquals(separate[i], rgba[i]);
                }
            }
        }

        @Test
        @DisplayName("reuses recent transforms")
        void cache() {
            var a = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
            var b = ColorTransform.create(ColorProfile.parse(ColorProfile.displayP3().toByteArray()), ColorProfile.srgb());
            assertSame(a, b);
            assertNotSame(a, ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb(),
                    ColorTransformOptions.relativeColorimetric()));
        }

        @Test
        @DisplayName("gives the same results from several threads")
        void threads() throws Exception {
            var transform = ColorTransform.create(TestProfiles.profile("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc"),
                    ColorProfile.displayP3(), ColorTransformOptions.relativeColorimetric());
            var input = TestProfiles.inputs(ProfileColorSpace.CMYK);
            var pixels = input.length / 4;
            Callable<byte[]> task = () -> {
                var out = new byte[pixels * 3];
                transform.transform(input, 0, 4, out, 0, 3, pixels);
                return out;
            };
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = executor.invokeAll(List.of(task, task, task, task));
                var first = futures.getFirst().get();
                for (var future : futures) {
                    assertArrayEquals(first, future.get());
                }
            }
        }
    }
}
