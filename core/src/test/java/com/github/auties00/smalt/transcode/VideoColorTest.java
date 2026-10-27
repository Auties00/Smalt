package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.color.ToneMapping;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the color conversion of encoded video pictures: tone mapping PQ video to standard dynamic range
 * agrees with {@link ImageOps#toSrgb}, standard-dynamic-range white and grays land at the ITU-R BT.2408
 * reference white of PQ and HLG targets as the SMPTE ST 2084 and ARIB STD-B67 formulas place them, BT.2020
 * colors convert to BT.709 with the matrix of ITU-R BT.2087, and colors that share primaries and transfer
 * change only their matrix and range.
 */
@DisplayName("Video color conversion")
class VideoColorTest {
    static final ColorInfo HDR10 = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
            ColorPrimaries.BT2020, TransferCharacteristics.PQ);

    static final ColorInfo HLG = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
            ColorPrimaries.BT2020, TransferCharacteristics.HLG);

    /** A 10-bit 4:4:4 PQ picture: luma ramps across, chroma varies down, so highlights and colors are covered. */
    static Yuv16Picture hdrPicture() {
        var picture = Yuv16Picture.allocate(64, 32, ChromaFormat.YUV444, 10, false, HDR10);
        for (var y = 0; y < 32; y++) {
            for (var x = 0; x < 64; x++) {
                picture.y()[y * picture.yStride() + x] = (short) (64 + x * 876 / 63);
                picture.u()[y * picture.uvStride() + x] = (short) (512 + (y - 16) * 6);
                picture.v()[y * picture.uvStride() + x] = (short) (512 - (y - 16) * 5);
            }
        }
        return picture;
    }

    static VideoColor conversion(ColorInfo target, ChromaFormat format, int depth) {
        return new VideoColor(target, format, depth, ColorTransformOptions.defaults(), null);
    }

    static double srgbToLinear(double v) {
        return v <= 0.04045 ? v / 12.92 : StrictMath.pow((v + 0.055) / 1.055, 2.4);
    }

    static double linearToSrgb(double l) {
        return l <= 0.0031308 ? 12.92 * l : 1.055 * StrictMath.pow(l, 1 / 2.4) - 0.055;
    }

    /** The SMPTE ST 2084 inverse EOTF of a luminance in candelas per square meter. */
    static double pq(double nits) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var y = StrictMath.pow(nits / 10000, m1);
        return StrictMath.pow((c1 + c2 * y) / (1 + c3 * y), m2);
    }

    @Test
    @DisplayName("tone-maps PQ video to BT.709 as ImageOps.toSrgb renders the decoded pictures")
    void hdrToSdr() {
        var source = hdrPicture();
        var converted = conversion(ColorInfo.BT709_LIMITED, ChromaFormat.YUV444, 0).apply(source);
        var yuv = assertInstanceOf(YuvPicture.class, converted);
        assertEquals(ColorInfo.BT709_LIMITED, yuv.color());
        var expected = ImageOps.toSrgb(source);
        var actual = ImageOps.toRgba(yuv);
        var max = 0;
        var sum = 0L;
        for (var y = 0; y < 32; y++) {
            for (var x = 0; x < 64 * 4; x++) {
                if (x % 4 == 3) {
                    continue;
                }
                var difference = Math.abs((expected.pixels()[y * expected.stride() + x] & 0xFF)
                        - (actual.pixels()[y * actual.stride() + x] & 0xFF));
                max = Math.max(max, difference);
                sum += difference;
            }
        }
        // the 8-bit Y'CbCr of the output quantizes what toSrgb writes as RGB directly
        assertTrue(max <= 3, "largest difference " + max);
        assertTrue(sum / (64.0 * 32 * 3) < 0.8, "mean difference " + sum / (64.0 * 32 * 3));
    }

    @Test
    @DisplayName("tone-maps 4:2:0 PQ video as toSrgb renders it once subsampled the same way")
    void hdrToSdr420() {
        var source = ImageOps.toYuv16(ImageOps.toRgba16(hdrPicture()), ChromaFormat.YUV420, HDR10, 10);
        var converted = assertInstanceOf(YuvPicture.class, conversion(ColorInfo.BT709_LIMITED, null, 0).apply(source));
        assertEquals(ChromaFormat.YUV420, converted.chromaFormat());
        // the conversion replicates each chroma sample over its pixels, then averages the result back
        var replicated = ImageOps.toYuv16(ImageOps.toRgba16(source, com.github.auties00.smalt.image.ops.ChromaUpsampling
                .NEAREST), ChromaFormat.YUV444, HDR10, 16);
        var expected = ImageOps.toRgba(ImageOps.toYuv(ImageOps.toSrgb(replicated), ChromaFormat.YUV420,
                ColorInfo.BT709_LIMITED));
        var actual = ImageOps.toRgba(converted);
        var max = 0;
        for (var index = 0; index < expected.pixels().length; index++) {
            max = Math.max(max, Math.abs((expected.pixels()[index] & 0xFF) - (actual.pixels()[index] & 0xFF)));
        }
        assertTrue(max <= 4, "largest difference " + max);
    }

    @Test
    @DisplayName("clips instead of rolling off when the conversion options ask for it")
    void clipping() {
        var source = hdrPicture();
        var clipped = conversion(ColorInfo.BT709_LIMITED, ChromaFormat.YUV444, 0);
        var rolled = clipped.apply(source);
        var clipping = new VideoColor(ColorInfo.BT709_LIMITED, ChromaFormat.YUV444, 0,
                ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build(), null).apply(source);
        var expected = ImageOps.toSrgb(source, ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
        var actual = ImageOps.toRgba(clipping);
        for (var index = 0; index < expected.pixels().length; index++) {
            assertTrue(Math.abs((expected.pixels()[index] & 0xFF) - (actual.pixels()[index] & 0xFF)) <= 3, "at " + index);
        }
        var rolledPixels = ImageOps.toRgba(rolled).pixels();
        var differing = 0;
        for (var index = 0; index < rolledPixels.length; index++) {
            if (Math.abs((rolledPixels[index] & 0xFF) - (actual.pixels()[index] & 0xFF)) > 3) {
                differing++;
            }
        }
        assertNotEquals(0, differing, "the BT.2390 roll-off and clipping differ on the highlights");
    }

    @Test
    @DisplayName("places standard-dynamic-range grays at the BT.2408 reference white of a PQ target")
    void sdrToPq() {
        var source = YuvPicture.allocate(220, 2, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
        for (var x = 0; x < 220; x++) {
            source.y()[x] = (byte) (16 + x);
            source.y()[source.yStride() + x] = (byte) (16 + x);
        }
        var converted = assertInstanceOf(Yuv16Picture.class, conversion(HDR10, ChromaFormat.YUV444, 0).apply(source));
        assertEquals(10, converted.bitDepth());
        assertEquals(HDR10, converted.color());
        for (var x = 0; x < 220; x++) {
            var signal = Math.min(1, x / 219.0);
            var nits = 203 * srgbToLinear(signal);
            var expected = 64 + 876 * pq(nits);
            var actual = converted.y()[x] & 0xFFFF;
            assertEquals(expected, actual, 1.5, "gray " + (16 + x));
            assertEquals(512, converted.u()[x], 1, "Cb of gray " + (16 + x));
            assertEquals(512, converted.v()[x], 1, "Cr of gray " + (16 + x));
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> roundTrips() {
        var list = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (var color : List.of(ColorInfo.BT709_LIMITED, ColorInfo.BT601_LIMITED)) {
            for (var format : List.of(ChromaFormat.YUV444, ChromaFormat.YUV420)) {
                for (var depth : List.of(10, 12)) {
                    for (var target : List.of(HDR10, HLG)) {
                        list.add(org.junit.jupiter.params.provider.Arguments.of(color, format, depth, target));
                    }
                }
            }
        }
        return list.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1} {2} bits to {3}")
    @org.junit.jupiter.params.provider.MethodSource("roundTrips")
    @DisplayName("converts standard-dynamic-range colors to PQ or HLG so that converting back restores them")
    void sdrColorsRoundTrip(ColorInfo color, ChromaFormat format, int depth, ColorInfo target) {
        // 2x2 blocks of one color, so that 4:2:0 subsampling keeps every color
        var source = RgbaPicture.allocate(64, 4, false);
        var random = new java.util.SplittableRandom(7);
        for (var block = 0; block < 32 * 2; block++) {
            var rgb = new int[]{30 + random.nextInt(200), 30 + random.nextInt(200), 30 + random.nextInt(200)};
            var bx = 2 * (block % 32);
            var by = 2 * (block / 32);
            for (var y = by; y < by + 2; y++) {
                for (var x = bx; x < bx + 2; x++) {
                    for (var channel = 0; channel < 3; channel++) {
                        source.pixels()[y * source.stride() + 4 * x + channel] = (byte) rgb[channel];
                    }
                    source.pixels()[y * source.stride() + 4 * x + 3] = (byte) 255;
                }
            }
        }
        var sdr = ImageOps.toYuv16(source, format, color, 16);
        var hdr = assertInstanceOf(Yuv16Picture.class, conversion(target, format, depth).apply(sdr));
        var back = new VideoColor(color, format, 16, ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP)
                .build(), null).apply(hdr);
        var restored = ImageOps.toRgba(back);
        var expected = ImageOps.toRgba(sdr);
        var max = 0;
        var worst = 0;
        for (var index = 0; index < expected.pixels().length; index++) {
            if (index % 4 != 3) {
                var difference = Math.abs((expected.pixels()[index] & 0xFF) - (restored.pixels()[index] & 0xFF));
                if (difference > max) {
                    max = difference;
                    worst = index / 4;
                }
            }
        }
        var x = worst % 64;
        var y = worst / 64;
        // 10-bit PQ and HLG quantize the dark components of saturated colors more coarsely than 8-bit SDR
        var tolerance = depth == 10 ? 8 : 2;
        assertTrue(max <= tolerance, "largest difference " + max + " at " + x + "," + y + ": source " + pixel(expected, x, y)
                + " restored " + pixel(restored, x, y) + " sdr " + sample(sdr, x, y) + " hdr " + sample(hdr, x, y)
                + " back " + sample((Yuv16Picture) back, x, y));
    }

    static String pixel(RgbaPicture picture, int x, int y) {
        var index = y * picture.stride() + 4 * x;
        return (picture.pixels()[index] & 0xFF) + "/" + (picture.pixels()[index + 1] & 0xFF) + "/"
                + (picture.pixels()[index + 2] & 0xFF);
    }

    static String sample(Yuv16Picture picture, int x, int y) {
        var cx = x >> picture.chromaFormat().shiftX();
        var cy = y >> picture.chromaFormat().shiftY();
        return (picture.y()[y * picture.yStride() + x] & 0xFFFF) + "/" + (picture.u()[cy * picture.uvStride() + cx]
                & 0xFFFF) + "/" + (picture.v()[cy * picture.uvStride() + cx] & 0xFFFF) + "@" + picture.bitDepth();
    }

    @Test
    @DisplayName("converts standard-dynamic-range colors to PQ so that clipping to sRGB restores them")
    void sdrColorsToSrgb() {
        var source = RgbaPicture.allocate(64, 4, false);
        var random = new java.util.SplittableRandom(7);
        for (var index = 0; index < source.pixels().length; index++) {
            source.pixels()[index] = (byte) (index % 4 == 3 ? 255 : 30 + random.nextInt(200));
        }
        var sdr = ImageOps.toYuv16(source, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED, 16);
        var pq = assertInstanceOf(Yuv16Picture.class, conversion(HDR10, ChromaFormat.YUV444, 12).apply(sdr));
        var back = ImageOps.toSrgb(pq, ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build());
        var max = 0;
        for (var index = 0; index < source.pixels().length; index++) {
            if (index % 4 != 3) {
                max = Math.max(max, Math.abs((source.pixels()[index] & 0xFF) - (back.pixels()[index] & 0xFF)));
            }
        }
        assertTrue(max <= 2, "largest difference " + max);
    }

    @Test
    @DisplayName("places standard-dynamic-range white at HLG signal 0.75")
    void sdrToHlg() {
        var source = YuvPicture.allocate(4, 2, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
        Arrays.fill(source.y(), (byte) 235);
        var converted = assertInstanceOf(Yuv16Picture.class, conversion(HLG, ChromaFormat.YUV444, 0).apply(source));
        assertEquals(64 + 876 * 0.75, converted.y()[0] & 0xFFFF, 2);
    }

    @Test
    @DisplayName("converts BT.2020 colors to BT.709 with the BT.2087 matrix")
    void gamut() {
        var bt2020 = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.FULL, ColorPrimaries.BT2020,
                TransferCharacteristics.BT709);
        var full709 = ColorInfo.BT709_LIMITED.withRange(ColorRange.FULL);
        // colors well inside both gamuts, as R'G'B' of BT.2020
        double[][] colors = {{0.5, 0.5, 0.5}, {0.6, 0.45, 0.4}, {0.35, 0.5, 0.45}, {0.4, 0.42, 0.6}};
        var source = RgbaPicture.allocate(colors.length, 1, false);
        for (var index = 0; index < colors.length; index++) {
            for (var channel = 0; channel < 3; channel++) {
                source.pixels()[4 * index + channel] = (byte) Math.round(colors[index][channel] * 255);
            }
            source.pixels()[4 * index + 3] = (byte) 255;
        }
        var input = ImageOps.toYuv16(source, ChromaFormat.YUV444, bt2020, 16);
        var output = conversion(full709, ChromaFormat.YUV444, 16).apply(input);
        var rgb = ImageOps.toRgba16(output);
        double[][] matrix = {{1.6605, -0.5876, -0.0728}, {-0.1246, 1.1329, -0.0083}, {-0.0182, -0.1006, 1.1187}};
        for (var index = 0; index < colors.length; index++) {
            var linear = new double[3];
            for (var channel = 0; channel < 3; channel++) {
                linear[channel] = srgbToLinear(Math.round(colors[index][channel] * 255) / 255.0);
            }
            for (var channel = 0; channel < 3; channel++) {
                var value = matrix[channel][0] * linear[0] + matrix[channel][1] * linear[1]
                        + matrix[channel][2] * linear[2];
                var expected = linearToSrgb(value) * 255;
                var actual = (rgb.pixels()[4 * index + channel] & 0xFFFF) / 257.0;
                assertEquals(expected, actual, 1.0, "color " + index + " channel " + channel);
            }
        }
    }

    @Test
    @DisplayName("changes only the matrix and range between colors that share primaries and transfer")
    void matrixOnly() {
        var source = YuvPicture.allocate(16, 8, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        for (var index = 0; index < source.y().length; index++) {
            source.y()[index] = (byte) (20 + index);
        }
        var target = ColorInfo.BT709_LIMITED.withRange(ColorRange.FULL).withMatrix(MatrixCoefficients.BT601);
        var converted = conversion(target, null, 0).apply(source);
        var expected = ImageOps.toYuv(source, ChromaFormat.YUV420, target);
        var actual = assertInstanceOf(YuvPicture.class, converted);
        assertEquals(Arrays.toString(expected.y()), Arrays.toString(actual.y()));
        assertEquals(Arrays.toString(expected.u()), Arrays.toString(actual.u()));
        assertTrue(VideoColor.sameColorimetry(ColorInfo.BT709_LIMITED, new ColorInfo(MatrixCoefficients.BT709,
                ColorRange.LIMITED, ColorPrimaries.UNSPECIFIED, TransferCharacteristics.SRGB)));
        assertTrue(!VideoColor.sameColorimetry(ColorInfo.BT709_LIMITED, HDR10));
    }

    @Test
    @DisplayName("passes a picture already in the target format through unchanged")
    void unchanged() {
        var source = YuvPicture.allocate(8, 8, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        assertSame(source, conversion(null, null, 0).apply(source));
        var deep = hdrPicture();
        assertSame(deep, conversion(null, null, 0).apply(deep));
        var rgba = RgbaPicture.allocate(4, 4, false);
        assertSame(rgba, conversion(null, null, 0).apply(rgba));
    }

    @Test
    @DisplayName("codes PQ and HLG output at 10 bits and the rest at 8 bits unless a depth is set")
    void depths() {
        var color = conversion(null, null, 0);
        assertEquals(10, color.outputDepth(HDR10));
        assertEquals(10, color.outputDepth(HLG));
        assertEquals(8, color.outputDepth(ColorInfo.BT709_LIMITED));
        assertEquals(12, conversion(null, null, 12).outputDepth(ColorInfo.BT709_LIMITED));
        var narrowed = conversion(null, null, 8).apply(hdrPicture());
        assertInstanceOf(YuvPicture.class, narrowed);
    }

    @Test
    @DisplayName("takes the mastering display and content light level of the track when pictures lack them")
    void trackMetadata() {
        var mastering = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 4000, 0.005);
        var level = new ContentLightLevel(3000, 400);
        var track = HDR10.withMasteringDisplay(mastering).withContentLightLevel(level);
        var color = new VideoColor(ColorInfo.BT709_LIMITED, null, 0, ColorTransformOptions.defaults(), track);
        var merged = color.sourceColor(hdrPicture());
        assertEquals(Optional.of(mastering), merged.masteringDisplay());
        assertEquals(Optional.of(level), merged.contentLightLevel());
        // a 4000 nit master compresses its highlights more than the 1000 nit default assumption; measured on the
        // neutral row 16, where every channel follows the tone curve (colors beyond a peak keep their hue)
        var withMaster = ImageOps.toRgba(color.apply(hdrPicture()));
        var withoutMaster = ImageOps.toRgba(conversion(ColorInfo.BT709_LIMITED, null, 0).apply(hdrPicture()));
        var brighter = 0;
        for (var x = 40; x < 60; x++) {
            var at = 4 * (16 * 64 + x) + 1;
            if ((withoutMaster.pixels()[at] & 0xFF) > (withMaster.pixels()[at] & 0xFF)) {
                brighter++;
            }
        }
        assertTrue(brighter > 10, "highlights of the 4000 nit master are darker: " + brighter);
        var sdr = new VideoColor(null, null, 0, ColorTransformOptions.defaults(), track);
        assertEquals(ColorInfo.BT709_LIMITED, sdr.sourceColor(YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false,
                ColorInfo.BT709_LIMITED)));
    }
}
