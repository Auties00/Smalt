package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
            assertEquals(name.contains("_PQ_cicp"), profile.isHighDynamicRange(), name);
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
        @DisplayName("maps PQ and HLG to high-dynamic-range profiles with a cicp-tagged ICC form")
        void hdr() {
            for (var transfer : List.of(TransferCharacteristics.PQ, TransferCharacteristics.HLG)) {
                var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                        ColorPrimaries.BT2020, transfer));
                assertTrue(profile.isHighDynamicRange());
                assertEquals(3, profile.channels());
                assertEquals("4.4.0", profile.version());
                assertTrue(profile.isMatrixShaper());
                assertFalse(profile.isSrgb());
                var parsed = ColorProfile.parse(profile.toByteArray());
                assertTrue(parsed.isHighDynamicRange());
                assertEquals(profile, parsed);
                assertEquals(3, ColorTransform.create(ColorProfile.srgb(), profile).outputChannels());
            }
        }

        @Test
        @DisplayName("keeps the R'G'B' description of a high-dynamic-range profile, metadata included")
        void hdrColorInfo() {
            var display = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 1000, 0.005);
            var source = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ).withChromaSiting(ChromaSiting.LEFT).withMasteringDisplay(display)
                    .withContentLightLevel(new ContentLightLevel(1200, 300));
            var profile = ColorProfile.of(source);
            var color = profile.colorInfo().orElseThrow();
            assertEquals(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ).withMasteringDisplay(display)
                    .withContentLightLevel(new ContentLightLevel(1200, 300)), color);
            assertEquals(profile, ColorProfile.of(source.withMatrix(MatrixCoefficients.BT709).withRange(ColorRange.FULL)));
            assertEquals(profile.hashCode(), ColorProfile.of(source.withRange(ColorRange.FULL)).hashCode());
            assertNotEquals(profile, ColorProfile.of(source.withContentLightLevel(new ContentLightLevel(1200, 301))));
            assertNotEquals(profile, ColorProfile.of(source.withMasteringDisplay(new MasteringDisplay(display.red(),
                    display.green(), display.blue(), new Chromaticity(0.3128, 0.329), 1000, 0.005))));
            assertTrue(ColorProfile.of(source.withTransfer(TransferCharacteristics.HLG)).colorInfo().isPresent());
            // unspecified primaries are BT.709 for the profile, so its description names them
            var unspecified = ColorProfile.of(source.withPrimaries(ColorPrimaries.UNSPECIFIED));
            assertEquals(ColorPrimaries.BT709, unspecified.colorInfo().orElseThrow().primaries());
            assertEquals(ColorProfile.of(source.withPrimaries(ColorPrimaries.BT709)), unspecified);
            assertTrue(ColorProfile.srgb().colorInfo().isEmpty());
            assertTrue(ColorProfile.of(ColorInfo.BT709_LIMITED).colorInfo().isEmpty());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TransferCharacteristics.class)
        @DisplayName("maps every transfer to a profile")
        void everyTransfer(TransferCharacteristics transfer) {
            var color = new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED, ColorPrimaries.BT709, transfer);
            var profile = ColorProfile.of(color);
            assertEquals(ProfileColorSpace.RGB, profile.colorSpace());
            assertEquals(3, ColorTransform.create(profile, ColorProfile.srgb()).outputChannels());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"LOG100, 2", "LOG316, 2.5"})
        @DisplayName("decodes the logarithmic transfers with the inverse of their function")
        void logarithmic(TransferCharacteristics transfer, double decades) {
            var source = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709,
                    transfer));
            var linear = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.LINEAR));
            var levels = new int[]{0, 64, 128, 192, 255};
            var out = grays(source, linear, levels);
            assertEquals(0, out[0], 1);
            for (var i = 1; i < out.length; i++) {
                var v = levels[i] / 255.0;
                assertEquals(255 * Math.pow(10, decades * (v - 1)), out[i], 1.01, transfer + " at " + v);
            }
        }

        @Test
        @DisplayName("decodes SMPTE ST 428-1 with 52.37 / 48 * V^2.6, clipping above the reference white")
        void dci() {
            var source = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.SMPTE428));
            var linear = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.LINEAR));
            var levels = new int[]{0, 60, 120, 180, 240, 250, 255};
            var out = grays(source, linear, levels);
            for (var i = 0; i < levels.length; i++) {
                var expected = Math.min(255, 255 * 52.37 / 48 * Math.pow(levels[i] / 255.0, 2.6));
                assertEquals(expected, out[i], 1.01, "at " + levels[i]);
            }
        }

        @Test
        @DisplayName("renders BT.1361 like BT.709 inside the nominal range")
        void bt1361() {
            var bt709 = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED,
                    ColorPrimaries.BT709, TransferCharacteristics.BT709));
            var bt1361 = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED,
                    ColorPrimaries.BT709, TransferCharacteristics.BT1361));
            var levels = new int[]{0, 30, 90, 180, 255};
            assertArrayEquals(grays(bt709, ColorProfile.srgb(), levels), grays(bt1361, ColorProfile.srgb(), levels));
        }

        @Test
        @DisplayName("maps the CIE XYZ primaries with their equal-energy white, so equal X, Y and Z is neutral")
        void xyzPrimaries() {
            var xyz = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.SMPTE428, TransferCharacteristics.LINEAR));
            var linear709 = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.BT709, TransferCharacteristics.LINEAR));
            var picture = RgbaPicture.allocate(3, 1, false);
            int[][] colors = {{128, 128, 128}, {255, 255, 255}, {0, 255, 0}};
            for (var i = 0; i < 3; i++) {
                for (var c = 0; c < 3; c++) {
                    picture.pixels()[4 * i + c] = (byte) colors[i][c];
                }
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
            var out = (RgbaPicture) ImageOps.convertColor(picture, xyz, linear709);
            for (var c = 0; c < 3; c++) {
                assertEquals(128, out.pixels()[c] & 0xFF, 2);
                assertEquals(255, out.pixels()[4 + c] & 0xFF, 2);
            }
            // pure Y is outside the BT.709 gamut: green saturates and red and blue clip at 0
            assertEquals(0, out.pixels()[8] & 0xFF, 1);
            assertEquals(255, out.pixels()[9] & 0xFF);
            assertEquals(0, out.pixels()[10] & 0xFF, 1);
            var hdr = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.SMPTE428, TransferCharacteristics.PQ));
            assertTrue(hdr.isHighDynamicRange());
            assertEquals(ColorPrimaries.SMPTE428, hdr.colorInfo().orElseThrow().primaries());
        }

        private static int[] grays(ColorProfile source, ColorProfile target, int[] levels) {
            var picture = RgbaPicture.allocate(levels.length, 1, false);
            for (var i = 0; i < levels.length; i++) {
                picture.pixels()[4 * i] = (byte) levels[i];
                picture.pixels()[4 * i + 1] = (byte) levels[i];
                picture.pixels()[4 * i + 2] = (byte) levels[i];
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
            var out = (RgbaPicture) ImageOps.convertColor(picture, source, target);
            var result = new int[levels.length];
            for (var i = 0; i < levels.length; i++) {
                result[i] = out.pixels()[4 * i + 1] & 0xFF;
            }
            return result;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ColorPrimaries.class)
        @DisplayName("maps every set of primaries")
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
