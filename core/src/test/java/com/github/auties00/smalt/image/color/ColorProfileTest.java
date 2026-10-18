package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ICC profile parsing, the built-in profiles, the CICP mapping and the profile factories.
 */
class ColorProfileTest {
    static Stream<String> profiles() {
        return TestProfiles.names().stream().filter(name -> !name.contains("D65_MAT"));
    }

    static List<Supplier<ColorProfile>> builtIns() {
        return List.of(ColorProfile::srgb, ColorProfile::displayP3, ColorProfile::adobeRgb, ColorProfile::proPhotoRgb,
                ColorProfile::rec709, ColorProfile::rec2020, ColorProfile::grayGamma22);
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.color.ColorProfileTest#profiles")
        @DisplayName("reads the header and links to sRGB")
        void reads(String name) {
            var profile = TestProfiles.profile(name);
            assertTrue(profile.channels() == 1 || profile.channels() == 3 || profile.channels() == 4);
            assertTrue(profile.version().startsWith("2.") || profile.version().startsWith("4."), profile.version());
            assertTrue(profile.connectionSpace() == ProfileColorSpace.XYZ || profile.connectionSpace() == ProfileColorSpace.LAB);
            assertFalse(profile.isHighDynamicRange());
            assertTrue(profile.description().isPresent(), name);
            var transform = ColorTransform.create(profile, ColorProfile.srgb());
            assertEquals(profile.channels(), transform.inputChannels());
            assertArrayEquals(TestProfiles.bytes(name), profile.toByteArray());
        }

        @Test
        @DisplayName("recognizes sRGB profiles of every encoding")
        void detectsSrgb() {
            for (var name : List.of("compact-icc-sRGB-v4.icc", "compact-icc-sRGB-v2-micro.icc", "compact-icc-sRGB-v2-magic.icc")) {
                assertTrue(TestProfiles.profile(name).isSrgb(), name);
            }
            for (var name : List.of("compact-icc-DisplayP3-v4.icc", "compact-icc-AdobeCompat-v2.icc", "compact-icc-sGrey-v4.icc",
                    "skia-icc-color.org-sRGB_ICC_v4_Appearance.icc")) {
                assertFalse(TestProfiles.profile(name).isSrgb(), name);
            }
        }

        @Test
        @DisplayName("rejects an iccMAX profile when a transform needs its tags")
        void iccMax() {
            var profile = TestProfiles.profile("skia-icc-color.org-sRGB_D65_MAT.icc");
            assertThrows(MediaException.class, () -> ColorTransform.create(profile, ColorProfile.srgb()));
        }

        @Test
        @DisplayName("rejects data that is not an ICC profile")
        void rejectsGarbage() {
            assertThrows(MediaException.Malformed.class, () -> ColorProfile.parse(new byte[10]));
            assertThrows(MediaException.Malformed.class, () -> ColorProfile.parse(new byte[200]));
            assertThrows(NullPointerException.class, () -> ColorProfile.parse(null));
        }

        @Test
        @DisplayName("copies its input and its output")
        void defensiveCopies() {
            var bytes = ColorProfile.srgb().toByteArray();
            var profile = ColorProfile.parse(bytes);
            bytes[200] ^= 1;
            assertEquals(ColorProfile.srgb(), profile);
            profile.toByteArray()[200] ^= 1;
            assertEquals(ColorProfile.srgb(), profile);
        }
    }

    @Nested
    @DisplayName("built-in profiles")
    class BuiltIn {
        @Test
        @DisplayName("are version 4 matrix/TRC profiles that round-trip through their bytes")
        void roundTrip() {
            for (var supplier : builtIns()) {
                var profile = supplier.get();
                assertSame(profile, supplier.get());
                assertEquals("4.3.0", profile.version());
                assertTrue(profile.isMatrixShaper());
                assertEquals(ProfileClass.DISPLAY, profile.deviceClass());
                var parsed = ColorProfile.parse(profile.toByteArray());
                assertEquals(profile, parsed);
                assertEquals(profile.hashCode(), parsed.hashCode());
            }
            assertTrue(ColorProfile.srgb().isSrgb());
            assertEquals(ProfileColorSpace.GRAY, ColorProfile.grayGamma22().colorSpace());
        }

        @Test
        @DisplayName("sRGB is byte-identical to the recorded copy the Little CMS oracle uses")
        void stableBytes() {
            assertArrayEquals(TestProfiles.resource("builtin-srgb.icc"), ColorProfile.srgb().toByteArray());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"sRGB-v4", "DisplayP3-v4", "AdobeCompat-v4", "ProPhoto-v4", "Rec2020-v4"})
        @DisplayName("convert like the compact ICC profiles of the same space within two code values")
        void matchesCompactProfiles(String compact) {
            var builtIn = switch (compact) {
                case "sRGB-v4" -> ColorProfile.srgb();
                case "DisplayP3-v4" -> ColorProfile.displayP3();
                case "AdobeCompat-v4" -> ColorProfile.adobeRgb();
                case "ProPhoto-v4" -> ColorProfile.proPhotoRgb();
                default -> ColorProfile.rec2020();
            };
            var reference = TestProfiles.profile("compact-icc-" + compact + ".icc");
            var target = compact.equals("DisplayP3-v4") ? ColorProfile.srgb() : ColorProfile.displayP3();
            var input = TestProfiles.inputs(builtIn.colorSpace());
            var channels = builtIn.channels();
            var pixels = input.length / channels;
            var expected = new byte[pixels * 3];
            var actual = new byte[pixels * 3];
            ColorTransform.create(reference, target).transform(input, 0, channels, expected, 0, 3, pixels);
            ColorTransform.create(builtIn, target).transform(input, 0, channels, actual, 0, 3, pixels);
            // the compact profiles quantize their colorants and curves differently
            var tolerance = compact.equals("sRGB-v4") || compact.equals("DisplayP3-v4") ? 1 : 2;
            for (var i = 0; i < actual.length; i++) {
                var difference = Math.abs((actual[i] & 0xFF) - (expected[i] & 0xFF));
                assertTrue(difference <= tolerance, compact + " sample " + i + " differs by " + difference);
            }
        }
    }

    @Nested
    @DisplayName("CICP and factories")
    class Factories {
        @Test
        @DisplayName("maps BT.709 and sRGB descriptions to sRGB")
        void bt709() {
            assertTrue(ColorProfile.of(ColorInfo.BT709_LIMITED).isSrgb());
            assertTrue(ColorProfile.of(ColorInfo.JPEG).isSrgb());
            assertFalse(ColorProfile.of(ColorInfo.BT601_LIMITED).isSrgb());
        }

        @Test
        @DisplayName("maps PQ and HLG to high-dynamic-range profiles without an ICC form")
        void hdr() {
            for (var transfer : List.of(TransferCharacteristics.PQ, TransferCharacteristics.HLG)) {
                var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                        ColorPrimaries.BT2020, transfer));
                assertTrue(profile.isHighDynamicRange());
                assertEquals(3, profile.channels());
                assertThrows(MediaException.Unsupported.class, profile::toByteArray);
                assertThrows(MediaException.Unsupported.class, () -> ColorTransform.create(ColorProfile.srgb(), profile));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TransferCharacteristics.class, names = {"LOG100", "LOG316", "BT1361", "SMPTE428"})
        @DisplayName("rejects transfers without a defined inverse")
        void unsupportedTransfers(TransferCharacteristics transfer) {
            var color = new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED, ColorPrimaries.BT709, transfer);
            assertThrows(MediaException.Unsupported.class, () -> ColorProfile.of(color));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ColorPrimaries.class, names = "SMPTE428", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("maps every chromaticity-defined set of primaries")
        void primaries(ColorPrimaries primaries) {
            var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED, primaries,
                    TransferCharacteristics.BT709));
            assertEquals(ProfileColorSpace.RGB, profile.colorSpace());
            ColorTransform.create(profile, ColorProfile.srgb());
        }

        @Test
        @DisplayName("builds RGB and gray profiles from a gamma")
        void gamma() {
            var white = new Chromaticity(0.3127, 0.3290);
            var linear = ColorProfile.rgb(new Chromaticity(0.64, 0.33), new Chromaticity(0.30, 0.60),
                    new Chromaticity(0.15, 0.06), white, 1.0);
            assertFalse(linear.isSrgb());
            assertNotEquals(linear, ColorProfile.srgb());
            assertEquals(ProfileColorSpace.GRAY, ColorProfile.gray(2.2).colorSpace());
            assertThrows(IllegalArgumentException.class, () -> ColorProfile.gray(0));
            assertThrows(IllegalArgumentException.class, () -> ColorProfile.gray(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> ColorProfile.rgb(white, white, white, white, 2.2));
        }
    }
}
