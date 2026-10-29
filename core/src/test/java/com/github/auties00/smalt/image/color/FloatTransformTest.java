package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the floating-point transforms against the 16-bit ones and the properties of the floating-point encoding:
 * values beyond {@code 0..1} through matrix/TRC profiles, high-dynamic-range sources and targets, named color indexes,
 * the layout rules and the conversion of floating-point pictures; {@link LcmsProofingDifferentialTest} compares them
 * with Little CMS's floating-point transforms.
 */
class FloatTransformTest {
    private static float[] widen(short[] in) {
        var out = new float[in.length];
        for (var i = 0; i < in.length; i++) {
            out[i] = (in[i] & 0xFFFF) / 65535.0f;
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"srgb,p3", "fogra,srgb", "srgb,swop", "sgrey,srgb", "mpe-rgb,srgb", "swop,fogra"})
    @DisplayName("floating-point outputs are the linked pipeline's values, which the 16-bit transform approximates")
    void agreesWithSixteenBits(String names) {
        var transform = ColorTransform.create(ProofingFixtures.profiles(names));
        var inputs = transform.inputChannels();
        var outputs = transform.outputChannels();
        var in16 = ProofingFixtures.inputs16(inputs);
        var pixels = in16.length / inputs;
        var out16 = new short[pixels * outputs];
        transform.transform(in16, 0, inputs, out16, 0, outputs, pixels);
        var outFloat = new float[pixels * outputs];
        transform.transform(widen(in16), 0, inputs, outFloat, 0, outputs, pixels);
        var pipeline = transform.pipeline();
        var in = new int[inputs];
        var out = new int[outputs];
        var worstPipeline = 0.0;
        var worstEngine = 0.0;
        for (var p = 0; p < pixels; p++) {
            for (var c = 0; c < inputs; c++) {
                in[c] = in16[p * inputs + c] & 0xFFFF;
            }
            pipeline.evaluate16(in, out);
            for (var o = 0; o < outputs; o++) {
                var value = Math.clamp(outFloat[p * outputs + o], 0f, 1f);
                worstPipeline = Math.max(worstPipeline, Math.abs(value - out[o] / 65535.0));
                worstEngine = Math.max(worstEngine, Math.abs(value - (out16[p * outputs + o] & 0xFFFF) / 65535.0));
            }
        }
        assertTrue(worstPipeline <= 0.6 / 65535, names + ": float and the 16-bit pipeline differ by " + worstPipeline);
        assertTrue(worstEngine < 0.06, names + ": float and the 16-bit transform differ by " + worstEngine);
    }

    @Test
    @DisplayName("matrix/TRC transforms keep values outside 0..1 and invert exactly enough")
    void extendedRange() {
        var forward = ColorTransform.create(ColorProfile.srgb(), ColorProfile.rec2020());
        var back = ColorTransform.create(ColorProfile.rec2020(), ColorProfile.srgb());
        float[] in = {1.5f, 0.5f, -0.25f, 2f, 2f, 2f, -0.1f, 0f, 0f};
        var mid = new float[in.length];
        var out = new float[in.length];
        forward.transform(in, 0, 3, mid, 0, 3, 3);
        back.transform(mid, 0, 3, out, 0, 3, 3);
        assertTrue(mid[3] > 1.5f, "white above 1 clipped: " + mid[3]);
        for (var i = 0; i < in.length; i++) {
            assertEquals(in[i], out[i], 2e-3, "sample " + i);
        }
    }

    @Test
    @DisplayName("L*a*b* and XYZ samples are in natural units")
    void naturalUnits() {
        var toLab = ColorTransform.create(List.of(ColorProfile.srgb(), ColorProfile.lab()));
        var toXyz = ColorTransform.create(List.of(ColorProfile.srgb(), ColorProfile.xyz()));
        var white = new float[]{1, 1, 1};
        var lab = new float[3];
        var xyz = new float[3];
        toLab.transform(white, 0, 3, lab, 0, 3, 1);
        toXyz.transform(white, 0, 3, xyz, 0, 3, 1);
        assertEquals(100.0, lab[0], 0.05);
        assertEquals(0.0, lab[1], 0.05);
        assertEquals(0.0, lab[2], 0.05);
        assertEquals(ColorMath.D50_X, xyz[0], 1e-3);
        assertEquals(1.0, xyz[1], 1e-3);
        assertEquals(ColorMath.D50_Z, xyz[2], 1e-3);
        var fromLab = ColorTransform.create(List.of(ColorProfile.lab(), ColorProfile.srgb()));
        var rgb = new float[3];
        fromLab.transform(lab, 0, 3, rgb, 0, 3, 1);
        assertArrayEquals(white, rgb, 1e-3f);
    }

    @Test
    @DisplayName("ink values are fractions, 1.0 being full ink")
    void inkFractions() {
        var transform = ColorTransform.create(ProofingFixtures.profile("swop"), ColorProfile.srgb());
        var in16 = new short[]{0, 0, 0, 0, (short) 0xFFFF, 0, 0, 0};
        var out16 = new short[6];
        transform.transform(in16, 0, 4, out16, 0, 3, 2);
        var out = new float[6];
        transform.transform(new float[]{0, 0, 0, 0, 1, 0, 0, 0}, 0, 4, out, 0, 3, 2);
        for (var i = 0; i < 6; i++) {
            assertEquals((out16[i] & 0xFFFF) / 65535.0, Math.clamp(out[i], 0f, 1f), 2e-3, "sample " + i);
        }
    }

    @Test
    @DisplayName("named color indexes are the color numbers, rounded")
    void namedIndexes() {
        var transform = ColorTransform.create(List.of(ProofingFixtures.profile("named-cmyk"), ColorProfile.srgb()));
        var colors = transform.source().namedColors().size();
        var outputs = transform.outputChannels();
        var indexes = new short[colors + 1];
        var floats = new float[colors + 1];
        for (var i = 0; i <= colors; i++) {
            indexes[i] = (short) i;
            floats[i] = i + (i % 2 == 0 ? 0.3f : -0.3f);
        }
        floats[colors] = -1;
        var expected = new short[indexes.length * outputs];
        transform.transform(indexes, 0, 1, expected, 0, outputs, indexes.length);
        var actual = new float[floats.length * outputs];
        transform.transform(floats, 0, 1, actual, 0, outputs, floats.length);
        for (var i = 0; i < actual.length; i++) {
            assertEquals((expected[i] & 0xFFFF) / 65535.0, Math.clamp(actual[i], 0f, 1f), 1.0 / 65535, "sample " + i);
        }
    }

    @Nested
    @DisplayName("high-dynamic-range profiles")
    class HighDynamicRange {
        private ColorProfile pq() {
            return ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ, com.github.auties00.smalt.image.ChromaSiting.UNSPECIFIED,
                    java.util.Optional.empty(), java.util.Optional.empty()));
        }

        private ColorProfile hlg() {
            return ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.HLG, com.github.auties00.smalt.image.ChromaSiting.UNSPECIFIED,
                    java.util.Optional.empty(), java.util.Optional.empty()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"pq-srgb", "hlg-srgb", "srgb-pq", "srgb-hlg", "pq-hlg", "hlg-pq"})
        @DisplayName("agree with the 16-bit engines")
        void agree(String pair) {
            var parts = pair.split("-");
            var source = switch (parts[0]) {
                case "pq" -> pq();
                case "hlg" -> hlg();
                default -> ColorProfile.srgb();
            };
            var target = switch (parts[1]) {
                case "pq" -> pq();
                case "hlg" -> hlg();
                default -> ColorProfile.srgb();
            };
            var transform = ColorTransform.create(source, target);
            var in16 = ProofingFixtures.inputs16(3);
            var pixels = in16.length / 3;
            var out16 = new short[pixels * 3];
            var outFloat = new float[pixels * 3];
            transform.transform(in16, 0, 3, out16, 0, 3, pixels);
            transform.transform(widen(in16), 0, 3, outFloat, 0, 3, pixels);
            var worst = 0.0;
            var at = 0;
            for (var i = 0; i < outFloat.length; i++) {
                var clamped = Math.clamp(outFloat[i], 0f, 1f);
                var diff = Math.abs(clamped - (out16[i] & 0xFFFF) / 65535.0);
                if (diff > worst) {
                    worst = diff;
                    at = i;
                }
            }
            var pixel = at / 3 * 3;
            assertTrue(worst < 0.01, pair + ": float and 16-bit differ by " + worst + " at sample " + at + ", input "
                    + (in16[pixel] & 0xFFFF) + " " + (in16[pixel + 1] & 0xFFFF) + " " + (in16[pixel + 2] & 0xFFFF)
                    + ", 16-bit " + (out16[pixel] & 0xFFFF) + " " + (out16[pixel + 1] & 0xFFFF) + " "
                    + (out16[pixel + 2] & 0xFFFF) + ", float " + outFloat[pixel] + " " + outFloat[pixel + 1] + " "
                    + outFloat[pixel + 2]);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"pq-pq", "hlg-hlg", "pq-hlg", "srgb-pq"})
        @DisplayName("write high-dynamic-range signals within 0..1, an identity included")
        void signalRange(String pair) {
            var parts = pair.split("-");
            var source = switch (parts[0]) {
                case "pq" -> pq();
                case "hlg" -> hlg();
                default -> ColorProfile.srgb();
            };
            var target = parts[1].equals("pq") ? pq() : hlg();
            var transform = ColorTransform.create(source, target);
            float[] in = {1.25f, -0.25f, 0.5f, 2f, 2f, 2f, -1f, -1f, -1f};
            var out = new float[in.length];
            transform.transform(in, 0, 3, out, 0, 3, 3);
            for (var i = 0; i < out.length; i++) {
                assertTrue(out[i] >= 0 && out[i] <= 1, pair + " sample " + i + " is " + out[i]);
            }
            if (parts[0].equals(parts[1])) {
                assertArrayEquals(new float[]{1, 0, 0.5f, 1, 1, 1, 0, 0, 0}, out);
            }
        }

        @Test
        @DisplayName("keep highlights above the standard-dynamic-range white without tone mapping")
        void unclipped() {
            var options = ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build();
            var transform = ColorTransform.create(pq(), ColorProfile.srgb(), options);
            var out = new float[3];
            transform.transform(new float[]{0.75f, 0.75f, 0.75f}, 0, 3, out, 0, 3, 1);
            assertTrue(out[0] > 1.5f, "1000-nit white clipped to " + out[0]);
        }
    }

    @Test
    @DisplayName("converts in place and checks its layout")
    void layout() {
        var transform = ColorTransform.create(ColorProfile.srgb(), ColorProfile.displayP3());
        var in = ProofingFixtures.inputsFloat(ProfileColorSpace.RGB, 3);
        var pixels = in.length / 3;
        var expected = new float[in.length];
        transform.transform(in, 0, 3, expected, 0, 3, pixels);
        var inPlace = in.clone();
        transform.transform(inPlace, 0, 3, inPlace, 0, 3, pixels);
        assertArrayEquals(expected, inPlace);
        var strided = new float[pixels * 4];
        for (var p = 0; p < pixels; p++) {
            System.arraycopy(in, p * 3, strided, p * 4, 3);
            strided[p * 4 + 3] = 0.5f;
        }
        transform.transform(strided, 0, 4, strided, 0, 4, pixels);
        for (var p = 0; p < pixels; p++) {
            assertEquals(0.5f, strided[p * 4 + 3]);
            for (var c = 0; c < 3; c++) {
                assertEquals(expected[p * 3 + c], strided[p * 4 + c]);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> transform.transform(in, 0, 2, in, 0, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> transform.transform(in, 0, 3, in, 0, 3, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> transform.transform(in, in.length - 2, 3, in, 0, 3, 1));
        assertThrows(NullPointerException.class, () -> transform.transform((float[]) null, 0, 3, in, 0, 3, 1));
        var nan = new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        transform.transform(nan, 0, 3, new float[3], 0, 3, 1);
    }

    @Test
    @DisplayName("floating-point pictures convert in floating point and keep their extra channels")
    void pictures() {
        var picture = ChannelFloatPicture.allocate(4, 2, PixelLayout.RGBA);
        var pixels = picture.pixels();
        for (var i = 0; i < 8; i++) {
            pixels[i * 4] = 1.25f;
            pixels[i * 4 + 1] = i / 8.0f;
            pixels[i * 4 + 2] = 0.1f;
            pixels[i * 4 + 3] = 0.75f;
        }
        var transform = ColorTransform.create(ColorProfile.srgb(), ColorProfile.rec2020());
        var converted = assertInstanceOf(ChannelFloatPicture.class, ImageOps.convertColor(picture, transform));
        assertEquals(PixelLayout.RGBA, converted.layout());
        var expected = new float[3];
        for (var i = 0; i < 8; i++) {
            transform.transform(pixels, i * 4, 4, expected, 0, 3, 1);
            for (var c = 0; c < 3; c++) {
                assertEquals(expected[c], converted.pixels()[i * 4 + c]);
            }
            assertEquals(0.75f, converted.pixels()[i * 4 + 3]);
        }
        var cmyk = assertInstanceOf(ChannelFloatPicture.class, ImageOps.convertColor(picture,
                ColorTransform.create(ColorProfile.srgb(), ProofingFixtures.profile("fogra"))));
        assertEquals(5, cmyk.layout().count());
    }

    @Test
    @DisplayName("premultiplied floating-point pictures convert their straight colors and multiply them again")
    void premultipliedPictures() {
        var layout = PixelLayout.RGB.withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA));
        var picture = ChannelFloatPicture.allocate(4, 1, layout);
        var pixels = picture.pixels();
        float[] alphas = {1.0f, 0.5f, 0.25f, 0.0f};
        for (var i = 0; i < 4; i++) {
            pixels[i * 4] = 0.8f * alphas[i];
            pixels[i * 4 + 1] = 0.3f * alphas[i];
            pixels[i * 4 + 2] = 0.6f * alphas[i];
            pixels[i * 4 + 3] = alphas[i];
        }
        var transform = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
        var converted = assertInstanceOf(ChannelFloatPicture.class, ImageOps.convertColor(picture, transform));
        assertEquals(layout, converted.layout());
        var straight = new float[3];
        transform.transform(new float[]{0.8f, 0.3f, 0.6f}, 0, 3, straight, 0, 3, 1);
        for (var i = 0; i < 4; i++) {
            for (var c = 0; c < 3; c++) {
                assertEquals(straight[c] * alphas[i], converted.pixels()[i * 4 + c], 1e-6, "pixel " + i);
            }
            assertEquals(alphas[i], converted.pixels()[i * 4 + 3]);
        }
    }
}
