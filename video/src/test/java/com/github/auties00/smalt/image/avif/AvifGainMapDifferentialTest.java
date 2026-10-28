package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares Smalt's AVIF gain map rendering with libavif 1.3.0's {@code avifgainmaputil tonemap} as a black box, on
 * libavif's test files and on files Smalt writes, at the full headroom and between the renditions. Both render to
 * PQ in the BT.709 primaries, compared at 10 bits; libavif's result is read from its 12-bit 4:4:4 Y4M output
 * ({@code --cicp-output 1/16/6}: the tool converts to Y'CbCr with the BT.601 matrix whatever the matrix it is
 * given, so the test converts back with it). libavif converts the base to R'G'B' of its own depth, in single
 * precision, before it applies the gain map; given the same R'G'B' (Smalt's 8-bit conversion, or libavif's
 * conversion of deeper Y'CbCr written in the test), a gain map of the image's size renders within one code value
 * above about 0.1 candela per square meter and within 0.1 candela below, where PQ is steepest and libavif's single
 * precision decides the sign of a result near zero. Smalt's own files are written losslessly in R'G'B' for that
 * comparison; from Y'CbCr samples, at the precision Smalt keeps, an 8-bit base renders within 8 code values at
 * most and 0.75 on average. libavif resamples a gain map of another size before converting it to RGB, Smalt
 * after, in the log2 domain, so those files are compared by RMS.
 */
@DisplayName("AVIF gain maps against libavif")
class AvifGainMapDifferentialTest {
    static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** Tone maps a file with avifgainmaputil and reads the 10-bit R'G'B' samples, red, green and blue per pixel. */
    static int[] oracle(Path file, double headroom, int width, int height) throws IOException {
        var out = file.resolveSibling(file.getFileName() + "-" + headroom + ".y4m");
        Oracle.run(Oracle.Tool.AVIFGAINMAPUTIL, TIMEOUT, List.of("tonemap", file.toString(), out.toString(),
                "--headroom", Double.toString(headroom), "--cicp-output", "1/16/6", "--depth", "12", "--yuv", "444"));
        var data = Files.readAllBytes(out);
        var header = 0;
        while (data[header] != '\n') {
            header++;
        }
        var text = new String(data, 0, header, StandardCharsets.US_ASCII);
        assertTrue(text.contains(" W" + width + " ") && text.contains(" H" + height + " "), text);
        var frame = header + 1;
        while (data[frame] != '\n') {
            frame++;
        }
        frame++;
        var plane = width * height;
        var samples = new int[plane * 3];
        for (var i = 0; i < plane; i++) {
            // avifgainmaputil converts its RGB result with the BT.601 matrix at full range, 12 bits
            var y = u16(data, frame + i * 2) / 4095.0;
            var cb = (u16(data, frame + (plane + i) * 2) - 2048) / 4095.0;
            var cr = (u16(data, frame + (2 * plane + i) * 2) - 2048) / 4095.0;
            var red = y + 1.402 * cr;
            var blue = y + 1.772 * cb;
            var green = (y - 0.299 * red - 0.114 * blue) / 0.587;
            samples[i * 3] = tenBit(red);
            samples[i * 3 + 1] = tenBit(green);
            samples[i * 3 + 2] = tenBit(blue);
        }
        return samples;
    }

    /** Describes the samples that differ most, with the base and gain map samples of their pixels. */
    static String worst(int[] expected, int[] actual, DecodedImage base, GainMap gainMap, int width) {
        var order = new java.util.ArrayList<Integer>();
        for (var i = 0; i < expected.length; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(Math.abs(expected[b] - actual[b]), Math.abs(expected[a] - actual[a])));
        var rgb = com.github.auties00.smalt.image.ops.ImageOps.toRgba16(base.picture());
        var map = com.github.auties00.smalt.image.ops.ImageOps.toChannel16(gainMap.image());
        var text = new StringBuilder("; worst:");
        for (var k = 0; k < 5; k++) {
            var i = order.get(k);
            var pixel = i / 3;
            var x = pixel % width;
            var y = pixel / width;
            var at = y * rgb.stride() + x * 4;
            var mx = Math.min(x * map.width() / width, map.width() - 1);
            var my = Math.min(y * map.height() / base.picture().height(), map.height() - 1);
            var mat = my * map.stride() + mx * map.channelCount();
            text.append(" (").append(x).append(',').append(y).append(" c").append(i % 3).append(' ')
                    .append(expected[i]).append(" vs ").append(actual[i]).append(", base ")
                    .append(rgb.pixels()[at] & 0xFFFF).append('/').append(rgb.pixels()[at + 1] & 0xFFFF).append('/')
                    .append(rgb.pixels()[at + 2] & 0xFFFF).append(", gain ").append(map.pixels()[mat] & 0xFFFF);
            if (map.channelCount() >= 3) {
                text.append('/').append(map.pixels()[mat + 1] & 0xFFFF).append('/').append(map.pixels()[mat + 2] & 0xFFFF);
            }
            text.append(')');
        }
        return text.toString() + "; metadata " + gainMap.metadata() + " base color " + base.colorProfile()
                + " " + (base.picture() instanceof com.github.auties00.smalt.image.YuvPicture yuv ? yuv.color() : "");
    }

    /** The 10-bit PQ code below which samples are compared as display light, about 0.1 candela. */
    static final int DARK = 64;

    /** The largest difference of near-black samples, in thousandths of a candela per square meter. */
    static final int DARK_MILLINITS = 100;

    /**
     * The tolerance of an 8-bit base rendered from its Y'CbCr samples: libavif's 8-bit R'G'B' copy of the base
     * moves a few samples by up to 8 codes while the mean difference stays well under one code.
     */
    static final Tolerance NATIVE_EIGHT_BIT = Tolerance.maxAbs(8).and(Tolerance.meanAbs(0.75));

    static int[] column(java.util.List<int[]> pairs, int index) {
        var values = new int[pairs.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = pairs.get(i)[index];
        }
        return values;
    }

    /** Converts a 10-bit PQ code to thousandths of a candela per square meter. */
    static int millinits(int code) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var p = Math.pow(code / 1023.0, 1 / m2);
        return (int) Math.round(Math.pow(Math.max(p - c1, 0) / (c2 - c3 * p), 1 / m1) * 10000 * 1000);
    }

    static int tenBit(double value) {
        return (int) Math.round(Math.min(Math.max(value, 0), 1) * 1023);
    }

    static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    static int[] smalt(DecodedImage base, GainMap gainMap, double headroom) {
        var image = GainMaps.apply(base, gainMap, GainMapOptions.builder().headroom(headroom)
                .primaries(ColorPrimaries.BT709).build());
        var picture = (Rgba16Picture) image.picture();
        var samples = new int[picture.width() * picture.height() * 3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var c = 0; c < 3; c++) {
                    var value = picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF;
                    samples[(y * picture.width() + x) * 3 + c] = (int) Math.round(value * 1023.0 / 65535);
                }
            }
        }
        return samples;
    }

    /** Converts a picture to R'G'B' rounded to its own depth: 8-bit RGBA, or 16-bit RGBA on the grid of its depth. */
    static com.github.auties00.smalt.image.Picture rgbOfDepth(com.github.auties00.smalt.image.Picture picture) {
        var depth = picture.bitDepth();
        if (depth <= 8) {
            return com.github.auties00.smalt.image.ops.ImageOps.toRgba(picture);
        }
        if (picture instanceof com.github.auties00.smalt.image.Yuv16Picture yuv
                && yuv.chromaFormat() == ChromaFormat.YUV444
                && yuv.color().range() == com.github.auties00.smalt.image.ColorRange.FULL) {
            var matrix = yuv.color().matrix().effective();
            float kr;
            float kb;
            switch (matrix) {
                case BT601, BT470BG -> {
                    kr = 0.299f;
                    kb = 0.114f;
                }
                case BT709 -> {
                    kr = 0.2126f;
                    kb = 0.0722f;
                }
                default -> {
                    kr = Float.NaN;
                    kb = Float.NaN;
                }
            }
            if (!Float.isNaN(kr)) {
                return libavifRgb(yuv, kr, kb);
            }
        }
        var rgba = com.github.auties00.smalt.image.ops.ImageOps.toRgba16(picture);
        var copy = Rgba16Picture.allocate(rgba.width(), rgba.height(), rgba.hasAlpha());
        var max = (1 << depth) - 1;
        for (var y = 0; y < rgba.height(); y++) {
            for (var x = 0; x < rgba.width() * 4; x++) {
                var value = rgba.pixels()[y * rgba.stride() + x] & 0xFFFF;
                var code = Math.round(value * (double) max / 65535);
                copy.pixels()[y * copy.stride() + x] = (short) Math.round(code * 65535.0 / max);
            }
        }
        return copy;
    }

    /**
     * Converts full-range 4:4:4 Y'CbCr of more than 8 bits to R'G'B' of its depth the way libavif's built-in
     * conversion does (src/reformat.c): normalized samples, the matrix in single precision, clamped, rounded half up.
     */
    static Rgba16Picture libavifRgb(com.github.auties00.smalt.image.Yuv16Picture yuv, float kr, float kb) {
        var kg = 1 - kr - kb;
        var max = (1 << yuv.bitDepth()) - 1;
        var maxF = (float) max;
        var bias = 1 << (yuv.bitDepth() - 1);
        var out = Rgba16Picture.allocate(yuv.width(), yuv.height(), false);
        for (var row = 0; row < yuv.height(); row++) {
            for (var x = 0; x < yuv.width(); x++) {
                var y = (yuv.y()[row * yuv.yStride() + x] & 0xFFFF) / maxF;
                var cb = ((yuv.u()[row * yuv.uvStride() + x] & 0xFFFF) - bias) / maxF;
                var cr = ((yuv.v()[row * yuv.uvStride() + x] & 0xFFFF) - bias) / maxF;
                var r = y + 2 * (1 - kr) * cr;
                var b = y + 2 * (1 - kb) * cb;
                var g = y - 2 * (kr * (1 - kr) * cr + kb * (1 - kb) * cb) / kg;
                var rgb = new float[]{r, g, b};
                for (var c = 0; c < 3; c++) {
                    var clamped = Math.min(Math.max(rgb[c], 0f), 1f);
                    var code = (int) (0.5f + clamped * maxF);
                    out.pixels()[row * out.stride() + x * 4 + c] = (short) Math.round(code * 65535.0 / max);
                }
                out.pixels()[row * out.stride() + x * 4 + 3] = (short) 0xFFFF;
            }
        }
        return out;
    }

    static void compare(String name, byte[] data, double headroom, Tolerance tolerance) throws IOException {
        compare(name, data, headroom, tolerance, true);
    }

    /**
     * Compares a rendering; with {@code asLibavif}, the base is first rounded to R'G'B' of its own depth, as libavif
     * converts it before it applies the gain map.
     */
    static void compare(String name, byte[] data, double headroom, Tolerance tolerance, boolean asLibavif)
            throws IOException {
        var path = Scratch.directory("avif-gainmap").resolve(name);
        Files.write(path, data);
        // an HDR base of 10 or 12 bits keeps its depth, as libavif's does
        var deep = com.github.auties00.smalt.image.ImageDecoderOptions.builder().highBitDepth(true).build();
        var base = AvifDecoder.decode(MediaSource.of(data), deep);
        if (asLibavif) {
            base = new DecodedImage(rgbOfDepth(base.picture()), base.format(), base.orientation(), base.sourceWidth(),
                    base.sourceHeight(), base.iccProfile(), base.exif(), base.xmp(), base.colorProfile());
        }
        var gainMap = AvifDecoder.decodeGainMap(MediaSource.of(data), deep).orElseThrow();
        var width = base.picture().width();
        var height = base.picture().height();
        var expected = oracle(path, headroom, width, height);
        var actual = smalt(base, gainMap, headroom);
        var what = name + " at headroom " + headroom + worst(expected, actual, base, gainMap, width);
        // PQ is steepest at black: a result within a few thousandths of zero, where libavif's 8-bit RGB copy of
        // an 8-bit base and its single-precision arithmetic decide the sign, is compared as display light
        var bright = new java.util.ArrayList<int[]>();
        var dark = new java.util.ArrayList<int[]>();
        for (var i = 0; i < expected.length; i++) {
            if (expected[i] >= DARK && actual[i] >= DARK) {
                bright.add(new int[]{expected[i], actual[i]});
            } else {
                dark.add(new int[]{millinits(expected[i]), millinits(actual[i])});
            }
        }
        tolerance.assertWithin(what, column(bright, 0), column(bright, 1));
        Tolerance.maxAbs(DARK_MILLINITS).assertWithin(what + " (near black, in thousandths of a candela)",
                column(dark, 0), column(dark, 1));
    }

    @ParameterizedTest(name = "{0} at headroom {1}")
    @CsvSource({"seine_sdr_gainmap_srgb.avif, 1.3", "seine_sdr_gainmap_srgb.avif, 0.65",
            "seine_hdr_gainmap_srgb.avif, 0", "seine_hdr_gainmap_srgb.avif, 0.5",
            "color_grid_gainmap_different_grid.avif, 6"})
    @DisplayName("render libavif's files within one code value, the base through R'G'B' of its depth as libavif does")
    void libavif(String name, double headroom) throws IOException {
        Oracle.assume(Oracle.Tool.AVIFGAINMAPUTIL);
        compare(name, AvifGainMapTest.vector(name), headroom, Tolerance.offByOne());
    }

    @ParameterizedTest(name = "{0} at headroom {1}")
    @CsvSource({"seine_sdr_gainmap_srgb.avif, 1.3", "seine_sdr_gainmap_srgb.avif, 0.65"})
    @DisplayName("render libavif's files with an 8-bit base close to it from the Y'CbCr samples")
    void eightBitBase(String name, double headroom) throws IOException {
        Oracle.assume(Oracle.Tool.AVIFGAINMAPUTIL);
        compare(name + "-native", AvifGainMapTest.vector(name), headroom, NATIVE_EIGHT_BIT, false);
    }

    @ParameterizedTest(name = "{0} at headroom {1}")
    @CsvSource({"seine_hdr_gainmap_small_srgb.avif, 1", "seine_sdr_gainmap_big_srgb.avif, 1"})
    @DisplayName("render libavif's resampled gain maps alike")
    void resampled(String name, double headroom) throws IOException {
        Oracle.assume(Oracle.Tool.AVIFGAINMAPUTIL);
        compare(name, AvifGainMapTest.vector(name), headroom, Tolerance.rms(2));
    }

    @ParameterizedTest(name = "multichannel {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("write files libavif renders alike")
    void written(boolean multichannel) throws IOException {
        Oracle.assume(Oracle.Tool.AVIFGAINMAPUTIL);
        var base = AvifGainMapTest.sdr(72, 40);
        var gainMap = AvifGainMapTest.computed(base, multichannel);
        // lossless R'G'B' (the identity matrix) gives both libraries the same samples
        var file = AvifEncoder.encode(base, gainMap, AvifEncoderOptions.lossless(), AvifEncoderOptions.lossless());
        var headroom = gainMap.metadata().alternateHeadroom();
        compare("smalt-" + multichannel + ".avif", file, headroom, Tolerance.offByOne());
        compare("smalt-" + multichannel + ".avif", file, headroom / 2, Tolerance.offByOne());
        // lossy Y'CbCr: libavif's 8-bit R'G'B' conversion rounds the darkest samples differently
        var lossy = AvifEncoder.encode(base, gainMap, AvifEncoderOptions.builder().quality(95)
                .chromaFormat(ChromaFormat.YUV444).build(), AvifEncoderOptions.lossy(95));
        compare("smalt-lossy-" + multichannel + ".avif", lossy, headroom, NATIVE_EIGHT_BIT, false);
        assertEquals(72, AvifDecoder.decode(MediaSource.of(file)).picture().width());
    }

    @Test
    @DisplayName("write oriented files libavif reads and renders alike")
    void oriented() throws IOException {
        Oracle.assume(Oracle.Tool.AVIFGAINMAPUTIL);
        Oracle.assume(Oracle.Tool.AVIFDEC);
        var base = AvifGainMapTest.sdr(72, 40);
        var gainMap = AvifGainMapTest.computed(base, false);
        var file = AvifEncoder.encode(base, gainMap, AvifEncoderOptions.lossless().toBuilder()
                .orientation(Orientation.ROTATE_90).build(), AvifEncoderOptions.lossless());
        var path = Scratch.directory("avif-gainmap").resolve("smalt-rotated.avif");
        Files.write(path, file);
        // libavif refuses the whole file, base image included, when the tmap item carries the orientation
        var png = path.resolveSibling("smalt-rotated.png");
        Oracle.run(Oracle.Tool.AVIFDEC, TIMEOUT, List.of(path.toString(), png.toString()));
        assertTrue(Files.size(png) > 0);
        // both keep the coded orientation of the pixels, reporting irot and imir apart
        compare("smalt-rotated.avif", file, gainMap.metadata().alternateHeadroom(), Tolerance.offByOne());
    }
}
