package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers gain map rendering and computation against a reference written in the test from the definitions: the
 * sRGB EOTF, the ISO 21496-1 application equation with its weight, the PQ inverse EOTF of SMPTE ST 2084, the HLG
 * OETF and system gamma of ITU-R BT.2100, and the BT.709 to BT.2020 matrix of ITU-R BT.2087. The renderer's
 * tables and single-precision arithmetic must stay within two 16-bit code values of the double-precision
 * reference.
 */
@DisplayName("Gain map rendering and computation")
class GainMapsTest {
    static final double M1 = 2610.0 / 16384;
    static final double M2 = 2523.0 / 4096 * 128;
    static final double C1 = 3424.0 / 4096;
    static final double C2 = 2413.0 / 4096 * 32;
    static final double C3 = 2392.0 / 4096 * 32;

    static double srgb(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double pq(double nits) {
        var y = Math.min(Math.max(nits / 10000, 0), 1);
        var p = Math.pow(y, M1);
        return Math.pow((C1 + C2 * p) / (1 + C3 * p), M2);
    }

    static double pqToNits(double signal) {
        var p = Math.pow(signal, 1 / M2);
        return Math.pow(Math.max(p - C1, 0) / (C2 - C3 * p), 1 / M1) * 10000;
    }

    static double hlgOetf(double e) {
        var a = 0.17883277;
        var b = 1 - 4 * a;
        var c = 0.5 - a * Math.log(4 * a);
        return e <= 1.0 / 12 ? Math.sqrt(3 * e) : a * Math.log(12 * e - b) + c;
    }

    /** A random sRGB picture. */
    static RgbaPicture base(int width, int height, long seed) {
        var picture = RgbaPicture.allocate(width, height, true);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) random.nextInt(256);
        }
        return picture;
    }

    /** A random picture of neutral colors, which a single-channel gain map brightens exactly. */
    static RgbaPicture neutral(int width, int height, long seed) {
        var picture = RgbaPicture.allocate(width, height, false);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var i = 0; i < width * height; i++) {
            var value = random.nextInt(256);
            for (var c = 0; c < 3; c++) {
                pixels[i * 4 + c] = (byte) value;
            }
            pixels[i * 4 + 3] = (byte) 0xFF;
        }
        return picture;
    }

    /** A random gray gain map. */
    static YuvPicture gray(int width, int height, long seed) {
        var plane = new byte[width * height];
        new Random(seed).nextBytes(plane);
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                .luma(plane, width).build();
    }

    static DecodedImage decoded(Picture picture, Optional<ColorProfile> profile) {
        return new DecodedImage(picture, ImageFormat.JPEG, Orientation.NORMAL, picture.width(), picture.height(),
                Optional.empty(), Optional.empty(), Optional.empty(), profile);
    }

    static int sample(Picture picture, int x, int y) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.y()[y * yuv.yStride() + x] & 0xFF;
            case RgbaPicture rgba -> rgba.pixels()[y * rgba.stride() + x * 4] & 0xFF;
            default -> throw new AssertionError();
        };
    }

    /** Applies a single-channel gain map of the image's size to one pixel, in double precision. */
    static double[] reference(RgbaPicture base, Picture map, GainMapMetadata metadata, double headroom, int x, int y,
                              double[] matrix) {
        var weight = metadata.weight(headroom) * (metadata.isBaseBrighter() ? -1 : 1);
        var at = y * base.stride() + x * 4;
        var result = new double[3];
        for (var c = 0; c < 3; c++) {
            var linear = srgb((base.pixels()[at + c] & 0xFF) / 255.0);
            if (weight == 0) {
                // a display at the base headroom shows the base rendition, without the offsets
                result[c] = linear;
                continue;
            }
            var channel = metadata.channel(c);
            var gain = channel.log2Gain(sample(map, x, y) / 255.0);
            result[c] = (linear + channel.baseOffset()) * Math.pow(2, weight * gain) - channel.alternateOffset();
        }
        if (matrix != null) {
            var r = result[0];
            var g = result[1];
            var b = result[2];
            for (var row = 0; row < 3; row++) {
                result[row] = matrix[row * 3] * r + matrix[row * 3 + 1] * g + matrix[row * 3 + 2] * b;
            }
        }
        return result;
    }

    static void assertClose(double expected, int actual, int tolerance, String what) {
        var code = (int) Math.round(Math.min(Math.max(expected, 0), 1) * 65535);
        assertTrue(Math.abs(code - actual) <= tolerance, what + ": expected " + code + ", got " + actual);
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {
        @ParameterizedTest(name = "headroom {0}")
        @ValueSource(doubles = {0, 0.75, 1.5, 2.5, 4})
        @DisplayName("matches the ISO 21496-1 equation encoded as PQ")
        void pqReference(double headroom) {
            var base = base(37, 23, 1);
            var map = gray(37, 23, 2);
            var metadata = GainMapMetadata.of(0, 2.5, new GainMapChannel(-0.5, 2.5, 1.5, 1.0 / 64, 1.0 / 128));
            var image = GainMaps.apply(decoded(base, Optional.empty()), new GainMap(metadata, map),
                    GainMapOptions.builder().headroom(headroom).primaries(ColorPrimaries.BT709).build());
            var picture = assertInstanceOf(Rgba16Picture.class, image.picture());
            var info = image.colorProfile().orElseThrow().colorInfo().orElseThrow();
            assertEquals(TransferCharacteristics.PQ, info.transfer());
            assertEquals(ColorPrimaries.BT709, info.primaries());
            assertTrue(image.iccProfile().isEmpty());
            var peak = 0.0;
            for (var y = 0; y < 23; y++) {
                for (var x = 0; x < 37; x++) {
                    var expected = reference(base, map, metadata, headroom, x, y, null);
                    for (var c = 0; c < 3; c++) {
                        assertClose(pq(expected[c] * 203), picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF,
                                2, "pixel " + x + "," + y + " channel " + c);
                        peak = Math.max(peak, Math.min(expected[c] * 203, 10000));
                    }
                    assertEquals((base.pixels()[y * base.stride() + x * 4 + 3] & 0xFF) * 257,
                            picture.pixels()[y * picture.stride() + x * 4 + 3] & 0xFFFF);
                }
            }
            assertEquals(Math.round(peak), info.contentLightLevel().orElseThrow().maxContentLightLevel(), 1);
        }

        @Test
        @DisplayName("dims a brighter base toward its alternate rendition")
        void baseBrighter() {
            var base = base(16, 16, 3);
            var map = gray(16, 16, 4);
            var metadata = GainMapMetadata.of(2, 0, new GainMapChannel(0, 2, 1, 1.0 / 64, 1.0 / 64));
            var picture = (Rgba16Picture) GainMaps.apply(decoded(base, Optional.empty()), new GainMap(metadata, map),
                    GainMapOptions.builder().headroom(0).primaries(ColorPrimaries.BT709).build()).picture();
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 16; x++) {
                    var expected = reference(base, map, metadata, 0, x, y, null);
                    assertClose(pq(expected[1] * 203), picture.pixels()[y * picture.stride() + x * 4 + 1] & 0xFFFF,
                            2, "pixel " + x + "," + y);
                    assertTrue(expected[1] <= srgb((base.pixels()[y * base.stride() + x * 4 + 1] & 0xFF) / 255.0)
                            + 1e-12);
                }
            }
        }

        @Test
        @DisplayName("converts to BT.2020 with the BT.2087 matrix")
        void bt2020() {
            double[] bt2087 = {0.6274, 0.3293, 0.0433, 0.0691, 0.9195, 0.0114, 0.0164, 0.0880, 0.8956};
            var base = base(20, 10, 5);
            var map = gray(20, 10, 6);
            var metadata = GainMapMetadata.of(0, 3, GainMapChannel.of(0, 3));
            var image = GainMaps.apply(decoded(base, Optional.empty()), new GainMap(metadata, map),
                    GainMapOptions.builder().primaries(ColorPrimaries.BT2020).build());
            assertEquals(ColorPrimaries.BT2020, image.colorProfile().orElseThrow().colorInfo().orElseThrow()
                    .primaries());
            var picture = (Rgba16Picture) image.picture();
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 20; x++) {
                    var expected = reference(base, map, metadata, 3, x, y, bt2087);
                    for (var c = 0; c < 3; c++) {
                        // BT.2087 rounds its coefficients to four decimals
                        assertClose(pq(expected[c] * 203), picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF,
                                40, "pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("applies the gain map in the alternate primaries when the metadata says so")
        void alternateColorSpace() {
            var base = base(8, 8, 7);
            var map = base(8, 8, 8);
            var channels = List.of(GainMapChannel.of(0, 2), GainMapChannel.of(0, 1), GainMapChannel.of(-1, 2));
            var alternate = Optional.of(ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.BT2020, TransferCharacteristics.PQ)));
            var inBase = new GainMap(new GainMapMetadata(0, 2, channels, true), map, alternate, Optional.empty());
            var inAlternate = new GainMap(new GainMapMetadata(0, 2, channels, false), map, alternate,
                    Optional.empty());
            var first = (Rgba16Picture) GainMaps.apply(decoded(base, Optional.empty()), inBase).picture();
            var second = (Rgba16Picture) GainMaps.apply(decoded(base, Optional.empty()), inAlternate).picture();
            var differs = false;
            for (var i = 0; i < first.pixels().length; i++) {
                differs |= Math.abs(first.pixels()[i] - second.pixels()[i]) > 2;
            }
            // per-channel gains act on other primaries, so saturated colors differ
            assertTrue(differs);
            var image = GainMaps.apply(decoded(base, Optional.empty()), inBase);
            assertEquals(ColorPrimaries.BT2020, image.colorProfile().orElseThrow().colorInfo().orElseThrow()
                    .primaries());
        }

        @Test
        @DisplayName("matches the BT.2100 HLG OETF and inverse system gamma")
        void hlg() {
            var base = base(19, 13, 9);
            var map = gray(19, 13, 10);
            var metadata = GainMapMetadata.of(0, 2, GainMapChannel.of(0, 2));
            var image = GainMaps.apply(decoded(base, Optional.empty()), new GainMap(metadata, map),
                    GainMapOptions.builder().transfer(TransferCharacteristics.HLG).primaries(ColorPrimaries.BT709)
                            .build());
            assertEquals(TransferCharacteristics.HLG, image.colorProfile().orElseThrow().colorInfo().orElseThrow()
                    .transfer());
            var picture = (Rgba16Picture) image.picture();
            var luminance = Primaries.of(ColorPrimaries.BT709).luminance();
            for (var y = 0; y < 13; y++) {
                for (var x = 0; x < 19; x++) {
                    var display = reference(base, map, metadata, 2, x, y, null);
                    for (var c = 0; c < 3; c++) {
                        display[c] = Math.min(Math.max(display[c] * 203 / 1000, 0), 1);
                    }
                    var yd = luminance[0] * display[0] + luminance[1] * display[1] + luminance[2] * display[2];
                    for (var c = 0; c < 3; c++) {
                        var scene = yd > 0 ? display[c] * Math.pow(yd, 1 / 1.2 - 1) : 0;
                        assertClose(hlgOetf(Math.min(scene, 1)), picture.pixels()[y * picture.stride() + x * 4 + c]
                                & 0xFFFF, 3, "pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("resamples a smaller gain map bilinearly in the log2 domain")
        void resampled() {
            var base = base(8, 6, 11);
            var map = gray(4, 3, 12);
            var metadata = GainMapMetadata.of(0, 3, new GainMapChannel(0, 3, 1, 0, 0));
            var picture = (Rgba16Picture) GainMaps.apply(decoded(base, Optional.empty()), new GainMap(metadata, map),
                    GainMapOptions.builder().primaries(ColorPrimaries.BT709).build()).picture();
            // pixel (3, 3) sits at gain map position (1.25, 1.25): weights 0.75/0.25 on columns 1-2 and rows 1-2
            var g11 = 3 * sample(map, 1, 1) / 255.0;
            var g21 = 3 * sample(map, 2, 1) / 255.0;
            var g12 = 3 * sample(map, 1, 2) / 255.0;
            var g22 = 3 * sample(map, 2, 2) / 255.0;
            var gain = 0.75 * (0.75 * g11 + 0.25 * g21) + 0.25 * (0.75 * g12 + 0.25 * g22);
            var linear = srgb((base.pixels()[3 * base.stride() + 3 * 4] & 0xFF) / 255.0) * Math.pow(2, gain);
            assertClose(pq(linear * 203), picture.pixels()[3 * picture.stride() + 3 * 4] & 0xFFFF, 2, "pixel 3,3");
            // the corner pixel takes the corner sample
            var corner = srgb((base.pixels()[0] & 0xFF) / 255.0) * Math.pow(2, 3 * sample(map, 0, 0) / 255.0);
            assertClose(pq(corner * 203), picture.pixels()[0] & 0xFFFF, 2, "pixel 0,0");
        }

        @Test
        @DisplayName("gives each channel its own gain with a color gain map")
        void multichannel() {
            var base = base(9, 7, 13);
            var map = base(9, 7, 14);
            var metadata = new GainMapMetadata(0, 2, List.of(GainMapChannel.of(0, 1), GainMapChannel.of(0, 2),
                    GainMapChannel.of(-1, 2)), true);
            var picture = (Rgba16Picture) GainMaps.apply(decoded(base, Optional.empty()), new GainMap(metadata, map),
                    GainMapOptions.builder().primaries(ColorPrimaries.BT709).build()).picture();
            for (var y = 0; y < 7; y++) {
                for (var x = 0; x < 9; x++) {
                    for (var c = 0; c < 3; c++) {
                        var at = y * base.stride() + x * 4 + c;
                        var channel = metadata.channel(c);
                        var linear = (srgb((base.pixels()[at] & 0xFF) / 255.0) + channel.baseOffset())
                                * Math.pow(2, channel.log2Gain((map.pixels()[at] & 0xFF) / 255.0))
                                - channel.alternateOffset();
                        assertClose(pq(linear * 203), picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF, 2,
                                "pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("reads an ICC base through its profile as the matching CICP primaries do")
        void iccBase() {
            var base = base(24, 16, 15);
            var map = gray(24, 16, 16);
            var metadata = GainMapMetadata.of(0, 2, GainMapChannel.of(0, 2));
            var options = GainMapOptions.builder().primaries(ColorPrimaries.SMPTE432).build();
            var icc = GainMaps.apply(decoded(base, Optional.of(ColorProfile.displayP3())), new GainMap(metadata, map),
                    options);
            assertEquals(ColorPrimaries.SMPTE432, Primaries.of(ColorProfile.displayP3()).cicp());
            var picture = (Rgba16Picture) icc.picture();
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 24; x++) {
                    var expected = reference(base, map, metadata, 2, x, y, null);
                    for (var c = 0; c < 3; c++) {
                        assertClose(pq(expected[c] * 203), picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF,
                                24, "pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps the size, orientation and metadata of the base image")
        void decodedImage() {
            var base = base(5, 4, 17);
            var decoded = new DecodedImage(base, ImageFormat.AVIF, Orientation.ROTATE_90, 5, 4, Optional.of(new byte[3]),
                    Optional.of(new byte[]{1}), Optional.of(new byte[]{2}), Optional.empty());
            var image = GainMaps.apply(decoded, new GainMap(GainMapMetadata.of(0, 1, GainMapChannel.of(0, 1)),
                    gray(5, 4, 18)));
            assertEquals(ImageFormat.AVIF, image.format());
            assertEquals(Orientation.ROTATE_90, image.orientation());
            assertEquals(1, image.exif().orElseThrow()[0]);
            assertEquals(2, image.xmp().orElseThrow()[0]);
            assertTrue(image.iccProfile().isEmpty());
            assertTrue(image.picture().hasAlpha());
        }
    }

    @Nested
    @DisplayName("computation")
    class Computation {
        /** A PQ rendition brighter than the base by a smooth factor of up to 6. */
        static DecodedImage alternate(RgbaPicture base) {
            var picture = Rgba16Picture.allocate(base.width(), base.height(), false);
            for (var y = 0; y < base.height(); y++) {
                for (var x = 0; x < base.width(); x++) {
                    var boost = 1 + 5.0 * x / (base.width() - 1);
                    for (var c = 0; c < 3; c++) {
                        var linear = srgb((base.pixels()[y * base.stride() + x * 4 + c] & 0xFF) / 255.0) * boost;
                        picture.pixels()[y * picture.stride() + x * 4 + c] = (short) Math.round(pq(linear * 203)
                                * 65535);
                    }
                    picture.pixels()[y * picture.stride() + x * 4 + 3] = (short) 0xFFFF;
                }
            }
            return decoded(picture, Optional.of(ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY,
                    ColorRange.FULL, ColorPrimaries.BT709, TransferCharacteristics.PQ))));
        }

        @ParameterizedTest(name = "multichannel {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("gives back the alternate rendition when applied in full")
        void roundTrip(boolean multichannel) {
            var base = multichannel ? base(64, 48, 19) : neutral(64, 48, 19);
            var alternate = alternate(base);
            var gainMap = GainMaps.compute(decoded(base, Optional.empty()), alternate,
                    GainMapComputeOptions.builder().multichannel(multichannel).build());
            var metadata = gainMap.metadata();
            assertEquals(multichannel, metadata.isMultichannel());
            assertEquals(0, metadata.baseHeadroom());
            // the brightest alternate value is the brightest base value of the last columns, boosted 6 times
            assertTrue(metadata.alternateHeadroom() > 2.3 && metadata.alternateHeadroom() <= Math.log(6) / Math.log(2) + 1e-3,
                    "alternate headroom " + metadata.alternateHeadroom());
            assertEquals(Math.log(6) / Math.log(2), metadata.channel(0).maximum(), 0.05);
            assertEquals(multichannel, !GainSampler.isGray(gainMap.image()));
            var rendered = (Rgba16Picture) GainMaps.apply(decoded(base, Optional.empty()), gainMap,
                    GainMapOptions.builder().primaries(ColorPrimaries.BT709).build()).picture();
            var expected = (Rgba16Picture) alternate.picture();
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    for (var c = 0; c < 3; c++) {
                        var e = pqToNits((expected.pixels()[y * expected.stride() + x * 4 + c] & 0xFFFF) / 65535.0);
                        var a = pqToNits((rendered.pixels()[y * rendered.stride() + x * 4 + c] & 0xFFFF) / 65535.0);
                        var tolerance = multichannel ? 0.02 : 0.06;
                        assertTrue(Math.abs(a - e) <= tolerance * e + 0.3, "pixel " + x + "," + y + " channel " + c
                                + ": " + e + " vs " + a);
                    }
                }
            }
        }

        @Test
        @DisplayName("stores the dimming of a brighter base as negated gains")
        void brighterBase() {
            var sdr = neutral(32, 8, 20);
            var hdr = alternate(sdr);
            var gainMap = GainMaps.compute(hdr, decoded(sdr, Optional.empty()));
            assertTrue(gainMap.metadata().isBaseBrighter());
            assertTrue(gainMap.metadata().channel(0).maximum() > 2);
            var rendered = (Rgba16Picture) GainMaps.apply(hdr, gainMap, GainMapOptions.builder().headroom(0)
                    .primaries(ColorPrimaries.BT709).build()).picture();
            for (var x = 0; x < 32; x++) {
                var expected = srgb((sdr.pixels()[x * 4 + 1] & 0xFF) / 255.0) * 203;
                var actual = pqToNits((rendered.pixels()[x * 4 + 1] & 0xFFFF) / 65535.0);
                assertTrue(Math.abs(actual - expected) <= 0.06 * expected + 0.3, "pixel " + x + ": " + expected
                        + " vs " + actual);
            }
        }

        @Test
        @DisplayName("downscales the gain map by block means")
        void scale() {
            var base = base(30, 20, 21);
            var gainMap = GainMaps.compute(decoded(base, Optional.empty()), alternate(base),
                    GainMapComputeOptions.builder().scale(4).gamma(2).alternateHeadroom(3).build());
            assertEquals(8, gainMap.image().width());
            assertEquals(5, gainMap.image().height());
            assertEquals(3, gainMap.metadata().alternateHeadroom());
            assertEquals(2, gainMap.metadata().channel(0).gamma());
            assertTrue(gainMap.alternateProfile().orElseThrow().isHighDynamicRange());
        }

        @Test
        @DisplayName("leaves out isolated outliers of the gain range")
        void outliers() {
            var gains = new float[100000];
            var random = new Random(22);
            for (var i = 0; i < gains.length; i++) {
                gains[i] = (float) (random.nextDouble() * 2);
            }
            gains[5] = 9;
            gains[6] = -7;
            var range = GainMapComputer.range(gains);
            assertTrue(range[0] > -0.02 && range[0] < 0.02, "minimum " + range[0]);
            assertTrue(range[1] > 1.98 && range[1] < 2.02, "maximum " + range[1]);
        }

        @Test
        @DisplayName("rejects renditions of different sizes and invalid options")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> GainMaps.compute(decoded(base(4, 4, 1),
                    Optional.empty()), decoded(base(4, 5, 1), Optional.empty())));
            assertThrows(IllegalArgumentException.class, () -> GainMapComputeOptions.builder().scale(0).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapComputeOptions.builder().gamma(0).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapComputeOptions.builder().baseOffset(2).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapOptions.builder()
                    .transfer(TransferCharacteristics.SRGB).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapOptions.builder().headroom(-1).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapOptions.builder().referenceWhite(0).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapOptions.builder()
                    .primaries(ColorPrimaries.UNSPECIFIED).build());
            // NaN is not a way to ask for the automatic value; the named resets are
            assertThrows(IllegalArgumentException.class, () -> GainMapOptions.builder().headroom(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapComputeOptions.builder()
                    .alternateHeadroom(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> GainMapComputeOptions.builder()
                    .alternateHeadroom(Double.POSITIVE_INFINITY).build());
            assertFalse(GainMapOptions.ofHeadroom(1).toBuilder().automaticHeadroom().build().headroom().isPresent());
            assertFalse(GainMapComputeOptions.builder().alternateHeadroom(2).automaticAlternateHeadroom().build()
                    .alternateHeadroom().isPresent());
            assertEquals(2, GainMapComputeOptions.builder().alternateHeadroom(2).build().toBuilder().build()
                    .alternateHeadroom().orElseThrow());
            assertFalse(GainMapOptions.defaults().headroom().isPresent());
            assertEquals(GainMapOptions.ofHeadroom(1), GainMapOptions.ofHeadroom(1).toBuilder().build());
        }
    }

    @Nested
    @DisplayName("transfer tables")
    class Tables {
        @Test
        @DisplayName("encode PQ and HLG within half a 16-bit code value")
        void accuracy() {
            var random = new Random(23);
            for (var i = 0; i < 200000; i++) {
                var light = i < 1000 ? i / 1000.0 * 1e-4 : random.nextDouble();
                light = i % 3 == 0 ? light * light * light : light;
                assertEquals(Transfers.pqExact(light), Transfers.pq((float) light), 0.5 / 65535, "PQ of " + light);
                assertEquals(Transfers.hlgOetf(light), Transfers.hlg((float) light), 0.5 / 65535, "HLG of " + light);
            }
        }
    }
}
