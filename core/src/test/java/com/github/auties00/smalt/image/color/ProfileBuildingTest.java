package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the profiles Smalt builds: their curves, classes and color spaces, what applying them does, and that a
 * text tag can be replaced without touching the rest; {@link LcmsProofingDifferentialTest} compares them with the
 * profiles Little CMS builds from the same parameters and has Little CMS read the bytes Smalt writes.
 */
class ProfileBuildingTest {
    private static final Chromaticity RED = new Chromaticity(0.64, 0.33);
    private static final Chromaticity GREEN = new Chromaticity(0.30, 0.60);
    private static final Chromaticity BLUE = new Chromaticity(0.15, 0.06);
    private static final Chromaticity D65 = new Chromaticity(0.3127, 0.3290);

    private static short[] apply(ColorTransform transform, short[] in) {
        var pixels = in.length / transform.inputChannels();
        var out = new short[pixels * transform.outputChannels()];
        transform.transform(in, 0, transform.inputChannels(), out, 0, transform.outputChannels(), pixels);
        return out;
    }

    @Nested
    @DisplayName("curves")
    class Curves {
        @Test
        @DisplayName("evaluate the ICC functions at the encoded precision")
        void evaluate() {
            assertEquals(Math.pow(0.5, 2.2), ProfileCurve.gamma(2.2).evaluate(0.5), 1e-4);
            assertEquals(0.2140, ProfileCurve.srgb().evaluate(0.5), 1e-4);
            assertEquals(0.02 / 12.92, ProfileCurve.srgb().evaluate(0.02), 1e-6);
            assertEquals(0.75, ProfileCurve.linear().evaluate(0.75), 1e-6);
            var piecewise = ProfileCurve.parametric(4, 2.0, 1.0, 0.0, 0.5, 0.2, 0.1, 0.05);
            assertEquals(0.5 * 0.1 + 0.05, piecewise.evaluate(0.1), 1e-4);
            assertEquals(0.3 * 0.3 + 0.1, piecewise.evaluate(0.3), 1e-4);
            var sampled = ProfileCurve.sampled(0f, 0.2f, 1f);
            assertEquals(0.1, sampled.evaluate(0.25), 1e-4);
            assertEquals(0.6, sampled.evaluate(0.75), 1e-4);
            assertEquals(1.0, sampled.evaluate(2.0), 1e-6);
            assertEquals(Math.pow(1.5, 2.2), ProfileCurve.gamma(2.2).evaluate(1.5), 1e-3);
        }

        @Test
        @DisplayName("reject parameters the encodings cannot hold")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.parametric(5, 1.0));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.parametric(-1, 1.0));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.parametric(1, 1.0, 2.0));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.parametric(0, Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.parametric(0, 40000.0));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.gamma(0));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.sampled(0.5f));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.sampled(0f, 1.5f));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.sampled(0f, Float.NaN));
            assertThrows(IllegalArgumentException.class, () -> ProfileCurve.sampled(new float[ProfileCurve.MAX_SAMPLES + 1]));
        }

        @Test
        @DisplayName("are equal when they write the same data")
        void equality() {
            assertEquals(ProfileCurve.gamma(2.2), ProfileCurve.parametric(0, 2.2));
            assertEquals(ProfileCurve.gamma(2.2).hashCode(), ProfileCurve.parametric(0, 2.2 + 1e-9).hashCode());
            assertNotEquals(ProfileCurve.gamma(2.2), ProfileCurve.gamma(2.4));
            assertNotEquals(ProfileCurve.gamma(1.0), ProfileCurve.sampled(0f, 1f));
            assertEquals(ProfileCurve.sampled(0f, 0.5f, 1f), ProfileCurve.sampled(0f, 0.5f, 1f));
        }
    }

    @Nested
    @DisplayName("display profiles")
    class Display {
        @Test
        @DisplayName("an RGB profile with a gamma curve converts as the gamma profile does")
        void rgbMatchesGamma() {
            var curve = ColorProfile.rgb(RED, GREEN, BLUE, D65, ProfileCurve.gamma(2.2));
            var gamma = ColorProfile.rgb(RED, GREEN, BLUE, D65, 2.2);
            assertEquals(ProfileClass.DISPLAY, curve.deviceClass());
            assertTrue(curve.isMatrixShaper());
            var in = ProofingFixtures.inputs16(3);
            assertArrayEquals(apply(ColorTransform.create(gamma, ColorProfile.srgb()), in),
                    apply(ColorTransform.create(curve, ColorProfile.srgb()), in));
        }

        @Test
        @DisplayName("an RGB profile with per-channel curves decodes each channel through its curve")
        void perChannel() {
            var profile = ColorProfile.rgb(RED, GREEN, BLUE, D65, ProfileCurve.gamma(1.0), ProfileCurve.srgb(),
                    ProfileCurve.sampled(0f, 0.25f, 1f));
            var linear = ColorProfile.rgb(RED, GREEN, BLUE, D65, ProfileCurve.linear());
            var transform = ColorTransform.create(profile, linear,
                    ColorTransformOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC).build());
            var out = new float[3];
            transform.transform(new float[]{0.5f, 0.5f, 0.5f}, 0, 3, out, 0, 3, 1);
            assertEquals(0.5, out[0], 2e-3);
            assertEquals(ProfileCurve.srgb().evaluate(0.5), out[1], 2e-3);
            assertEquals(0.25, out[2], 2e-3);
        }

        @Test
        @DisplayName("a gray profile decodes through its curve")
        void gray() {
            var profile = ColorProfile.gray(D65, ProfileCurve.gamma(1.8));
            assertEquals(ProfileColorSpace.GRAY, profile.colorSpace());
            var transform = ColorTransform.create(profile, ColorProfile.gray(D65, ProfileCurve.linear()));
            var out = new float[1];
            transform.transform(new float[]{0.5f}, 0, 1, out, 0, 1, 1);
            assertEquals(Math.pow(0.5, 1.8), out[0], 2e-3);
            assertThrows(IllegalArgumentException.class, () -> ColorProfile.gray(new Chromaticity(0.3, 0),
                    ProfileCurve.linear()));
        }
    }

    @Test
    @DisplayName("the L*a*b* and XYZ identities leave colors unchanged")
    void identities() {
        assertSame(ColorProfile.lab(), ColorProfile.lab());
        assertEquals(ProfileClass.ABSTRACT, ColorProfile.lab().deviceClass());
        assertEquals(ProfileColorSpace.LAB, ColorProfile.lab().colorSpace());
        assertEquals(ProfileColorSpace.XYZ, ColorProfile.xyz().colorSpace());
        var in = ProofingFixtures.inputs16(3);
        var plain = apply(ColorTransform.create(ColorProfile.srgb(), ColorProfile.displayP3()), in);
        for (var identity : List.of(ColorProfile.lab(), ColorProfile.xyz())) {
            var through = apply(ColorTransform.create(List.of(ColorProfile.srgb(), identity,
                    ColorProfile.displayP3())), in);
            // the identity's curves stay in the pipeline (as in Little CMS), so the 16-bit transform is sampled
            Tolerance.rms(8).assertWithin(identity.toString(), plain, through, 16);
        }
    }

    @Test
    @DisplayName("a linearization link applies one curve per channel")
    void linearization() {
        var curves = ProofingFixtures.linearCurves();
        var link = ColorProfile.linearization(ProfileColorSpace.CMYK, curves);
        assertEquals(ProfileClass.DEVICE_LINK, link.deviceClass());
        var transform = ColorTransform.create(List.of(link));
        var in = ProofingFixtures.inputsFloat(ProfileColorSpace.CMYK, 4);
        var out = new float[in.length];
        transform.transform(in, 0, 4, out, 0, 4, in.length / 4);
        for (var i = 0; i < in.length; i++) {
            var x = Math.clamp(in[i], 0f, 1f);
            var expected = curves.get(i % 4).evaluate(i % 4 == 3 ? x : in[i]);
            assertEquals(expected, out[i], 2e-3, "sample " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> ColorProfile.linearization(ProfileColorSpace.RGB, curves));
        assertThrows(IllegalArgumentException.class, () -> ColorProfile.linearization(ProfileColorSpace.OTHER,
                List.of()));
    }

    @ParameterizedTest(name = "{0}%")
    @ValueSource(doubles = {1, 120, 280, 400})
    @DisplayName("an ink limiting link keeps the ink within the limit and black unchanged")
    void inkLimiting(double limit) {
        var transform = ColorTransform.create(List.of(ColorProfile.inkLimiting(limit)));
        var in = ProofingFixtures.inputs16(4);
        var out = apply(transform, in);
        // the 5-level lattice of the 16-bit input set lies on the 17-point grid of the link
        for (var p = 0; p < 625; p++) {
            var k = (in[p * 4 + 3] & 0xFFFF) / 655.35;
            var inks = 0.0;
            var sum = 0.0;
            for (var c = 0; c < 4; c++) {
                inks += (in[p * 4 + c] & 0xFFFF) / 655.35;
                sum += (out[p * 4 + c] & 0xFFFF) / 655.35;
            }
            assertEquals(in[p * 4 + 3], out[p * 4 + 3], "black changed at pixel " + p);
            if (inks <= limit) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(in[p * 4 + c], out[p * 4 + c], "ink changed under the limit at pixel " + p);
                }
            } else {
                assertEquals(Math.max(limit, k), sum, 0.01, "ink sum at pixel " + p);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ColorProfile.inkLimiting(0.5));
        assertThrows(IllegalArgumentException.class, () -> ColorProfile.inkLimiting(Double.NaN));
    }

    @Nested
    @DisplayName("L*a*b* adjustments")
    class Adjustments {
        @Test
        @DisplayName("the defaults leave colors unchanged")
        void identity() {
            var profile = ColorProfile.labAdjustment(LabAdjustments.defaults());
            assertEquals(ProfileClass.ABSTRACT, profile.deviceClass());
            var in = ProofingFixtures.inputs16(3);
            var plain = apply(ColorTransform.create(ColorProfile.srgb(), ColorProfile.srgb()), in);
            var through = apply(ColorTransform.create(List.of(ColorProfile.srgb(), profile, ColorProfile.srgb())), in);
            Tolerance.rms(64).assertWithin("identity", plain, through, 16);
        }

        @Test
        @DisplayName("brightness and contrast act on L*")
        void lightness() {
            var brighter = ColorProfile.labAdjustment(LabAdjustments.builder().brightness(10).build());
            var transform = ColorTransform.create(List.of(ColorProfile.lab(), brighter, ColorProfile.lab()));
            var out = new float[3];
            transform.transform(new float[]{50, 0, 0}, 0, 3, out, 0, 3, 1);
            assertEquals(60, out[0], 0.1);
            var contrast = ColorProfile.labAdjustment(LabAdjustments.builder().contrast(0.5).build());
            ColorTransform.create(List.of(ColorProfile.lab(), contrast, ColorProfile.lab()))
                    .transform(new float[]{50, 20, 0}, 0, 3, out, 0, 3, 1);
            assertEquals(25, out[0], 0.1);
            assertEquals(20, out[1], 0.2);
        }

        @Test
        @DisplayName("the builder checks the ranges")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> LabAdjustments.builder().contrast(-1).build());
            assertThrows(IllegalArgumentException.class, () -> LabAdjustments.builder().brightness(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> LabAdjustments.builder().gridPoints(1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> LabAdjustments.builder().whiteBalance(3000, 6500).build());
            assertThrows(IllegalArgumentException.class, () -> LabAdjustments.builder().whiteBalance(0, 0).build());
            var balanced = LabAdjustments.builder().whiteBalance(5000, 6500).build();
            assertEquals(5000, balanced.sourceTemperature());
            assertEquals(0, balanced.toBuilder().noWhiteBalance().build().targetTemperature());
            assertEquals(balanced, balanced.toBuilder().build());
        }
    }

    @Test
    @DisplayName("a device link reproduces its transform on the grid points")
    void deviceLink() {
        var transform = ColorTransform.create(ColorProfile.srgb(), ProofingFixtures.profile("fogra"));
        var link = ColorProfile.deviceLink(transform);
        assertEquals(ProfileClass.DEVICE_LINK, link.deviceClass());
        assertEquals(ProfileColorSpace.RGB, link.colorSpace());
        var applied = ColorTransform.create(List.of(link));
        assertEquals(4, applied.outputChannels());
        var points = 33;
        var grid = new short[points * points * points * 3];
        for (var n = 0; n < points * points * points; n++) {
            grid[n * 3] = (short) ColorMath.saturateWord((n / (points * points)) * 65535.0 / (points - 1));
            grid[n * 3 + 1] = (short) ColorMath.saturateWord((n / points % points) * 65535.0 / (points - 1));
            grid[n * 3 + 2] = (short) ColorMath.saturateWord((n % points) * 65535.0 / (points - 1));
        }
        var direct = apply(transform, grid);
        var clut = applied.pipeline().stages().stream()
                .filter(stage -> stage instanceof Stage.Clut)
                .map(stage -> (Stage.Clut) stage)
                .findFirst()
                .orElseThrow();
        assertEquals(direct.length, clut.table.length);
        for (var i = 0; i < direct.length; i++) {
            assertEquals(direct[i] & 0xFFFF, clut.table[i], "node " + i / 4 + " channel " + i % 4);
        }
        // The nodes land off the grid by the 16-bit fixed-point domain rounding, and the linked transform
        // interpolates twice: the device link's table, then the table its own optimization resamples.
        Tolerance.maxAbs(4).assertWithin("grid", direct, apply(applied, grid), 16);
        var in8 = ProofingFixtures.inputs8(3);
        var a = new byte[in8.length / 3 * 4];
        var b = new byte[a.length];
        transform.transform(in8, 0, 3, a, 0, 4, in8.length / 3);
        applied.transform(in8, 0, 3, b, 0, 4, in8.length / 3);
        Tolerance.maxAbs(3).assertWithin("8-bit", a, b);
    }

    @Test
    @DisplayName("the device link of a proofing transform holds the simulation without the gamut alarm")
    void proofingDeviceLink() {
        var srgb = ColorProfile.srgb();
        var fogra = ProofingFixtures.profile("fogra");
        var options = ColorTransformOptions.defaults();
        var alarm = new int[]{0xFFFF, 0, 0xFFFF};
        var checked = ColorTransform.createProofing(srgb, fogra, srgb, options,
                ProofingOptions.builder().gamutCheck(true).alarm(alarm).build());
        var unchecked = ColorTransform.createProofing(srgb, fogra, srgb, options,
                ProofingOptions.builder().alarm(alarm).build());
        var points = 33;
        var grid = new short[points * points * points * 3];
        for (var n = 0; n < points * points * points; n++) {
            grid[n * 3] = (short) ColorMath.saturateWord((n / (points * points)) * 65535.0 / (points - 1));
            grid[n * 3 + 1] = (short) ColorMath.saturateWord((n / points % points) * 65535.0 / (points - 1));
            grid[n * 3 + 2] = (short) ColorMath.saturateWord((n % points) * 65535.0 / (points - 1));
        }
        assertFalse(Arrays.equals(apply(checked, grid), apply(unchecked, grid)), "no grid point out of gamut");
        assertArrayEquals(ColorProfile.deviceLink(unchecked).toByteArray(),
                ColorProfile.deviceLink(checked).toByteArray());
    }

    @Test
    @DisplayName("the device link of a named color transform is a named color profile of its outputs")
    void namedDeviceLink() {
        var transform = ColorTransform.create(List.of(ProofingFixtures.profile("named-cmyk"), ColorProfile.srgb()));
        var link = ColorProfile.deviceLink(transform);
        assertEquals(ProfileClass.NAMED_COLOR, link.deviceClass());
        assertEquals(ProfileColorSpace.RGB, link.colorSpace());
        var source = transform.source().namedColors();
        var colors = link.namedColors();
        assertEquals(source.size(), colors.size());
        var indexes = new short[source.size()];
        for (var i = 0; i < indexes.length; i++) {
            indexes[i] = (short) i;
        }
        var out = apply(transform, indexes);
        for (var i = 0; i < colors.size(); i++) {
            assertEquals(source.get(i).name(), colors.get(i).name());
            assertArrayEquals(source.get(i).connectionSpaceValues(), colors.get(i).connectionSpaceValues(), 1e-9);
            var device = colors.get(i).deviceValues();
            for (var c = 0; c < 3; c++) {
                assertEquals((out[i * 3 + c] & 0xFFFF) / 65535.0, device[c], 1e-9);
            }
        }
    }

    @Nested
    @DisplayName("text tags")
    class Text {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"srgb", "fogra", "mpe-rgb"})
        @DisplayName("are replaced without changing what the profile does")
        void replaced(String name) throws NoSuchAlgorithmException {
            var profile = ProofingFixtures.profile(name);
            var renamed = profile.withDescription("Renamed").withCopyright("Copyright test");
            assertEquals("Renamed", renamed.description().orElseThrow());
            assertEquals("Copyright test", renamed.copyright().orElseThrow());
            assertEquals(profile.version(), renamed.version());
            assertEquals(profile.deviceClass(), renamed.deviceClass());
            var bytes = renamed.toByteArray();
            assertEquals(bytes.length, (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8
                    | bytes[3] & 0xFF);
            var original = profile.toByteArray();
            var identified = false;
            for (var i = 84; i < 100; i++) {
                identified |= original[i] != 0;
            }
            if (identified) {
                var copy = bytes.clone();
                Arrays.fill(copy, 44, 48, (byte) 0);
                Arrays.fill(copy, 64, 68, (byte) 0);
                Arrays.fill(copy, 84, 100, (byte) 0);
                assertArrayEquals(MessageDigest.getInstance("MD5").digest(copy), Arrays.copyOfRange(bytes, 84, 100));
            }
            var in = ProofingFixtures.inputs16(profile.channels());
            var target = ColorProfile.srgb();
            assertArrayEquals(apply(ColorTransform.create(profile, target), in),
                    apply(ColorTransform.create(renamed, target), in));
            assertEquals(renamed, profile.withDescription("Renamed").withCopyright("Copyright test"));
        }

        @Test
        @DisplayName("of a high-dynamic-range profile keep its color description and its conversions")
        void highDynamicRange() {
            var pq = ColorProfile.of(new com.github.auties00.smalt.image.ColorInfo(
                    com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY,
                    com.github.auties00.smalt.image.ColorRange.FULL, com.github.auties00.smalt.image.ColorPrimaries.BT2020,
                    com.github.auties00.smalt.image.TransferCharacteristics.PQ,
                    com.github.auties00.smalt.image.ChromaSiting.UNSPECIFIED, java.util.Optional.empty(),
                    java.util.Optional.empty()));
            var renamed = pq.withDescription("Renamed PQ");
            assertEquals("Renamed PQ", renamed.description().orElseThrow());
            assertTrue(renamed.isHighDynamicRange());
            assertEquals(pq.colorInfo(), renamed.colorInfo());
            var in = ProofingFixtures.inputs16(3);
            assertArrayEquals(apply(ColorTransform.create(pq, ColorProfile.srgb()), in),
                    apply(ColorTransform.create(renamed, ColorProfile.srgb()), in));
            assertEquals(renamed.toByteArray().length, ColorProfile.parse(renamed.toByteArray()).toByteArray().length);
        }

        @Test
        @DisplayName("of version 2 profiles hold ASCII only")
        void asciiOnly() {
            var fogra = ProofingFixtures.profile("fogra");
            var accented = "caf" + (char) 0xE9;
            assertThrows(IllegalArgumentException.class, () -> fogra.withDescription(accented));
            assertEquals(accented, ColorProfile.srgb().withDescription(accented).description().orElseThrow());
        }
    }
}
