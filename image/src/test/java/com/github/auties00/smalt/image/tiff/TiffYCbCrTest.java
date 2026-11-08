package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers uncompressed-style YCbCr pages beyond 8-bit interleaved data units: samples of other depths against the
 * TIFF 6.0 section 20 and 21 formulas computed here in double precision, separate planes with subsampled Cb and
 * Cr planes (strips and tiles, with extra samples) against the same samples in interleaved data units, which
 * libtiff's {@code tiff2rgba} checks, and the horizontal predictor on data units as libtiff's {@code tiffcp}
 * writes it.
 */
@DisplayName("TIFF YCbCr variants")
class TiffYCbCrTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static final double[] BT601 = {0.299, 0.587, 0.114};

    private static byte[] vector(String name) {
        return TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/ycbcr/" + name);
    }

    /** The samples of a YCbCr image: full-resolution Y, subsampled Cb and Cr, optional full-resolution alpha. */
    record Samples(int width, int height, int sx, int sy, int bits, long[] y, long[] cb, long[] cr, long[] alpha) {
        int chromaWidth() {
            return (width + sx - 1) / sx;
        }

        int chromaHeight() {
            return (height + sy - 1) / sy;
        }

        static Samples random(int width, int height, int sx, int sy, int bits, boolean alpha, long seed) {
            var random = new Random(seed);
            var max = (1L << bits) - 1;
            var cw = (width + sx - 1) / sx;
            var ch = (height + sy - 1) / sy;
            var y = new long[width * height];
            for (var i = 0; i < y.length; i++) {
                y[i] = Math.clamp(Math.round((double) (i % width) / width * max) + random.nextInt(9) - 4, 0, max);
            }
            var cb = new long[cw * ch];
            var cr = new long[cw * ch];
            for (var i = 0; i < cb.length; i++) {
                cb[i] = random.nextLong(max + 1);
                cr[i] = Math.clamp((max + 1) / 2 + random.nextLong(max / 2 + 1) - max / 4, 0, max);
            }
            long[] a = null;
            if (alpha) {
                a = new long[width * height];
                for (var i = 0; i < a.length; i++) {
                    a[i] = i % 7 == 0 ? 0 : random.nextLong(max + 1);
                }
            }
            return new Samples(width, height, sx, sy, bits, y, cb, cr, a);
        }

        /** The data units, row of units by row of units, each row padded to a byte. */
        byte[] dataUnits(boolean little) {
            var cw = chromaWidth();
            var ch = chromaHeight();
            var unit = sx * sy + 2;
            var values = new long[cw * ch * unit];
            for (var uy = 0; uy < ch; uy++) {
                for (var ux = 0; ux < cw; ux++) {
                    var at = (uy * cw + ux) * unit;
                    for (var dy = 0; dy < sy; dy++) {
                        for (var dx = 0; dx < sx; dx++) {
                            var x = Math.min(ux * sx + dx, width - 1);
                            var yy = Math.min(uy * sy + dy, height - 1);
                            values[at + dy * sx + dx] = y[yy * width + x];
                        }
                    }
                    values[at + sx * sy] = cb[uy * cw + ux];
                    values[at + sx * sy + 1] = cr[uy * cw + ux];
                }
            }
            return TiffFiles.pack(values, cw * unit, ch, 1, bits, little);
        }

        /** The planes Y, Cb, Cr and alpha, each its own array of packed rows. */
        long[][] planes() {
            return alpha == null ? new long[][]{y, cb, cr} : new long[][]{y, cb, cr, alpha};
        }

        int planeWidth(int plane) {
            return plane == 1 || plane == 2 ? chromaWidth() : width;
        }

        int planeHeight(int plane) {
            return plane == 1 || plane == 2 ? chromaHeight() : height;
        }
    }

    /** Converts one pixel with the TIFF 6.0 formulas, to 16 bits per component. */
    static int[] reference(long y, long cb, long cr, int bits, double[] luma, double[] reference) {
        var max = (1L << bits) - 1;
        var chroma = (1L << (bits - 1)) - 1;
        var fullY = (y - reference[0]) * max / (reference[1] - reference[0]);
        var fullCb = (cb - reference[2]) * chroma / (reference[3] - reference[2]);
        var fullCr = (cr - reference[4]) * chroma / (reference[5] - reference[4]);
        var r = fullCr * (2 - 2 * luma[0]) + fullY;
        var b = fullCb * (2 - 2 * luma[2]) + fullY;
        var g = (fullY - luma[2] * b - luma[0] * r) / luma[1];
        return new int[]{to16(r, max), to16(g, max), to16(b, max)};
    }

    private static int to16(double value, long max) {
        return (int) Math.clamp(Math.round(value * 65535 / max), 0, 65535);
    }

    static double[] defaultReference(int bits) {
        var max = (1L << bits) - 1;
        var half = 1L << (bits - 1);
        return new double[]{0, max, half, max, half, max};
    }

    /** Checks a 16-bit decode against the formulas, within one 16-bit step. */
    static void assertReference(Samples samples, Rgba16Picture picture, double[] luma, double[] reference) {
        assertEquals(samples.width(), picture.width());
        assertEquals(samples.height(), picture.height());
        var pixels = picture.pixels();
        for (var yy = 0; yy < samples.height(); yy++) {
            for (var x = 0; x < samples.width(); x++) {
                var c = (yy / samples.sy()) * samples.chromaWidth() + x / samples.sx();
                var expected = reference(samples.y()[yy * samples.width() + x], samples.cb()[c], samples.cr()[c],
                        samples.bits(), luma, reference);
                var o = (yy * samples.width() + x) * 4;
                for (var k = 0; k < 3; k++) {
                    var actual = pixels[o + k] & 0xFFFF;
                    assertTrue(Math.abs(actual - expected[k]) <= 1, "pixel " + x + "," + yy + " component " + k
                            + " expected " + expected[k] + " got " + actual);
                }
                var alpha = samples.alpha() == null ? 65535
                        : (int) Math.round(samples.alpha()[yy * samples.width() + x] * 65535.0 / ((1L << samples.bits()) - 1));
                assertEquals(alpha, pixels[o + 3] & 0xFFFF, "alpha of pixel " + x + "," + yy);
            }
        }
    }

    /** Checks an 8-bit decode against the formulas reduced to 8 bits, within one step. */
    static void assertReference8(Samples samples, RgbaPicture picture, double[] luma, double[] reference) {
        var pixels = picture.pixels();
        for (var yy = 0; yy < samples.height(); yy++) {
            for (var x = 0; x < samples.width(); x++) {
                var c = (yy / samples.sy()) * samples.chromaWidth() + x / samples.sx();
                var expected = reference(samples.y()[yy * samples.width() + x], samples.cb()[c], samples.cr()[c],
                        samples.bits(), luma, reference);
                var o = (yy * samples.width() + x) * 4;
                for (var k = 0; k < 3; k++) {
                    var actual = pixels[o + k] & 0xFF;
                    var want = (expected[k] * 255 + 32767) / 65535;
                    assertTrue(Math.abs(actual - want) <= 1, "pixel " + x + "," + yy + " component " + k
                            + " expected " + want + " got " + actual);
                }
                var alpha = samples.alpha() == null ? 255
                        : (int) Math.round(samples.alpha()[yy * samples.width() + x] * 255.0 / ((1L << samples.bits()) - 1));
                assertEquals(alpha, pixels[o + 3] & 0xFF, "alpha of pixel " + x + "," + yy);
            }
        }
    }

    static TiffFiles interleaved(Samples samples, boolean little) {
        var file = TiffFiles.of(samples.width(), samples.height(), 3, samples.bits()).photometric(6)
                .tag(530, samples.sx(), samples.sy()).data(samples.dataUnits(little));
        return little ? file : file.bigEndian();
    }

    /** Cuts each plane into strips of the given rows (or into tiles), each padded to the full strip or tile. */
    static TiffFiles planar(Samples samples, int stripRows, int tileWidth, int tileHeight, boolean little) {
        var planes = samples.planes();
        var chunks = new ArrayList<byte[]>();
        var tiled = tileWidth > 0;
        for (var p = 0; p < planes.length; p++) {
            var chroma = p == 1 || p == 2;
            var pw = samples.planeWidth(p);
            var ph = samples.planeHeight(p);
            var cw = tiled ? (chroma ? tileWidth / samples.sx() : tileWidth) : pw;
            var lumaRows = tiled ? tileHeight : stripRows;
            var ch = chroma ? (lumaRows + samples.sy() - 1) / samples.sy() : lumaRows;
            var across = tiled ? (samples.width() + tileWidth - 1) / tileWidth : 1;
            var down = (samples.height() + (tiled ? tileHeight : stripRows) - 1) / (tiled ? tileHeight : stripRows);
            for (var ty = 0; ty < down; ty++) {
                for (var tx = 0; tx < across; tx++) {
                    var rows = tiled ? ch : Math.min(ch, ph - ty * ch);
                    var values = new long[cw * rows];
                    for (var r = 0; r < rows; r++) {
                        for (var c = 0; c < cw; c++) {
                            var x = tx * cw + c;
                            var yy = ty * ch + r;
                            values[r * cw + c] = x < pw && yy < ph ? planes[p][yy * pw + x] : 0;
                        }
                    }
                    chunks.add(TiffFiles.pack(values, cw, rows, 1, samples.bits(), little));
                }
            }
        }
        var file = TiffFiles.of(samples.width(), samples.height(), planes.length, samples.bits()).photometric(6)
                .tag(530, samples.sx(), samples.sy()).planar().chunks(chunks.toArray(byte[][]::new));
        if (planes.length == 4) {
            file.extra(2);
        }
        if (tiled) {
            file.tiles(tileWidth, tileHeight);
        } else {
            file.rowsPerStrip(stripRows);
        }
        return little ? file : file.bigEndian();
    }

    static Stream<Arguments> depths() {
        var cases = new ArrayList<Arguments>();
        for (var bits : new int[]{2, 4, 10, 12, 16}) {
            for (var factors : new int[][]{{1, 1}, {2, 1}, {2, 2}, {4, 2}, {4, 4}}) {
                cases.add(Arguments.of(bits, factors[0], factors[1]));
            }
        }
        return cases.stream();
    }

    @Nested
    @DisplayName("samples of other depths")
    class Depths {
        @ParameterizedTest(name = "{0}-bit, {1}x{2}")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffYCbCrTest#depths")
        @DisplayName("convert with the TIFF 6.0 formulas and the default reference black and white")
        void defaults(int bits, int sx, int sy) {
            var samples = Samples.random(21, 13, sx, sy, bits, false, bits * 31L + sx * 7L + sy);
            for (var little : new boolean[]{true, false}) {
                var file = interleaved(samples, little).bytes();
                var narrow = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
                if (bits < 8) {
                    // samples of up to 8 bits decode to 8 bits whatever the options ask
                    assertReference8(samples, narrow, BT601, defaultReference(bits));
                    continue;
                }
                var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture();
                assertReference(samples, wide, BT601, defaultReference(bits));
                assertArrayEquals(ImageOps.toEightBit(wide).pixels(), narrow.pixels(), "8-bit output");
            }
        }

        @Test
        @DisplayName("honor YCbCrCoefficients and ReferenceBlackWhite with headroom and footroom")
        void tags() {
            var samples = Samples.random(19, 11, 2, 2, 16, false, 5);
            // BT.709 weights and 16-bit video ranges
            var luma = new double[]{0.2125, 0.7154, 0.0721};
            var reference = new double[]{4096, 60160, 32768, 61440, 32768, 61440};
            var file = interleaved(samples, true).rational(529, 2125, 10000, 7154, 10000, 721, 10000)
                    .rational(532, 4096, 1, 60160, 1, 32768, 1, 61440, 1, 32768, 1, 61440, 1).bytes();
            var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture();
            assertReference(samples, wide, luma, reference);
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {2, 4, 10, 12, 16})
        @DisplayName("scale an alpha sample of unsubsampled YCbCr from its own depth")
        void alpha(int bits) {
            var samples = Samples.random(9, 7, 1, 1, bits, true, 77 + bits);
            var values = new long[9 * 7 * 4];
            for (var i = 0; i < 9 * 7; i++) {
                values[i * 4] = samples.y()[i];
                values[i * 4 + 1] = samples.cb()[i];
                values[i * 4 + 2] = samples.cr()[i];
                values[i * 4 + 3] = samples.alpha()[i];
            }
            var file = TiffFiles.of(9, 7, 4, bits).photometric(6).tag(530, 1, 1).extra(2)
                    .data(TiffFiles.pack(values, 9, 7, 4, bits, true)).bytes();
            var narrow = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
            assertTrue(narrow.hasAlpha());
            assertReference8(samples, narrow, BT601, defaultReference(bits));
            if (bits > 8) {
                var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture();
                assertTrue(wide.hasAlpha());
                assertReference(samples, wide, BT601, defaultReference(bits));
            }
        }
    }

    @Nested
    @DisplayName("separate planes")
    class Planes {
        static Stream<Arguments> layouts() {
            var cases = new ArrayList<Arguments>();
            for (var factors : new int[][]{{2, 1}, {2, 2}, {4, 2}, {4, 4}}) {
                for (var alpha : new boolean[]{false, true}) {
                    cases.add(Arguments.of(factors[0], factors[1], alpha));
                }
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "{0}x{1}, alpha {2}")
        @MethodSource("layouts")
        @DisplayName("decode subsampled chroma planes as the same samples in data units, which libtiff checks")
        void eightBit(int sx, int sy, boolean alpha) {
            var samples = Samples.random(23, 17, sx, sy, 8, alpha, sx * 11L + sy + (alpha ? 100 : 0));
            var units = interleaved(samples, true).bytes();
            var expected = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(units)).picture());
            var libtiff = LibTiff.find("tiff2rgba").isPresent() ? LibTiff.rgba(units) : null;
            if (libtiff != null) {
                assertArrayEquals(libtiff, expected, "the data-unit reference differs from libtiff");
            }
            for (var layout : List.of(new int[]{17, 0, 0}, new int[]{4, 0, 0}, new int[]{0, 16, 16},
                    new int[]{0, 32, 8})) {
                var file = planar(samples, layout[0], layout[1], layout[2], true).bytes();
                var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
                assertEquals(alpha, picture.hasAlpha());
                var actual = RasterTesting.tight(picture);
                for (var i = 0; i < 23 * 17; i++) {
                    for (var k = 0; k < 3; k++) {
                        assertEquals(expected[i * 4 + k], actual[i * 4 + k],
                                "pixel " + i + " in layout " + List.of(layout[0], layout[1], layout[2]));
                    }
                    assertEquals(alpha ? (byte) samples.alpha()[i] : (byte) 0xFF, actual[i * 4 + 3]);
                }
            }
        }

        @Test
        @DisplayName("decode 16-bit subsampled planes with the TIFF 6.0 formulas, big-endian and tiled")
        void sixteenBit() {
            var samples = Samples.random(18, 14, 2, 2, 16, true, 9);
            for (var little : new boolean[]{true, false}) {
                var file = planar(samples, 0, 16, 16, little).bytes();
                assertReference(samples, (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture(),
                        BT601, defaultReference(16));
                file = planar(samples, 6, 0, 0, little).bytes();
                assertReference(samples, (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture(),
                        BT601, defaultReference(16));
            }
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {4, 12})
        @DisplayName("decode subsampled planes of other depths with an alpha plane scaled from its depth")
        void deepAlpha(int bits) {
            var samples = Samples.random(18, 14, 2, 2, bits, true, 30 + bits);
            for (var layout : List.of(new int[]{6, 0, 0}, new int[]{0, 16, 16})) {
                var file = planar(samples, layout[0], layout[1], layout[2], false).bytes();
                assertReference8(samples, (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture(), BT601,
                        defaultReference(bits));
                if (bits > 8) {
                    assertReference(samples, (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture(),
                            BT601, defaultReference(bits));
                }
            }
        }

        @Test
        @DisplayName("reverse the horizontal predictor on each plane of its own width")
        void predictor() {
            var samples = Samples.random(22, 12, 4, 2, 8, true, 21);
            var plain = planar(samples, 12, 0, 0, true).bytes();
            var expected = (RgbaPicture) TiffDecoder.decode(MediaSource.of(plain)).picture();
            var planes = samples.planes();
            var chunks = new byte[planes.length][];
            for (var p = 0; p < planes.length; p++) {
                var pw = samples.planeWidth(p);
                var ph = samples.planeHeight(p);
                var packed = TiffFiles.pack(planes[p], pw, ph, 1, 8, true);
                for (var r = 0; r < ph; r++) {
                    for (var c = pw - 1; c > 0; c--) {
                        packed[r * pw + c] -= packed[r * pw + c - 1];
                    }
                }
                chunks[p] = deflate(packed);
            }
            var predicted = TiffFiles.of(22, 12, 4, 8).photometric(6).tag(530, 4, 2).planar().extra(2)
                    .tag(259, 8).tag(317, 2).chunks(chunks).bytes();
            var actual = (RgbaPicture) TiffDecoder.decode(MediaSource.of(predicted)).picture();
            assertArrayEquals(RasterTesting.tight(expected), RasterTesting.tight(actual));
        }
    }

    @Nested
    @DisplayName("data units")
    class DataUnits {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ycbcr22-8-lzw", "ycbcr42-8-zip", "ycbcr21-8-lzw", "ycbcr22-16-zip", "ycbcr44-16-lzw"})
        @DisplayName("reverse the horizontal predictor libtiff writes on rows of data units")
        void predictor(String name) throws Exception {
            var predicted = vector(name + ".tif");
            var raw = vector(name + ".raw.tif");
            assertEquals(2, TiffDecoder.openPages(MediaSource.of(predicted)).page(0).predictor().code());
            var expected = TiffDecoder.decode(MediaSource.of(raw), WIDE).picture();
            var actual = TiffDecoder.decode(MediaSource.of(predicted), WIDE).picture();
            if (expected instanceof Rgba16Picture wide) {
                assertArrayEquals(wide.pixels(), ((Rgba16Picture) actual).pixels());
                return;
            }
            var pixels = RasterTesting.tight((RgbaPicture) actual);
            assertArrayEquals(RasterTesting.tight((RgbaPicture) expected), pixels);
            if (LibTiff.find("tiff2rgba").isPresent()) {
                assertArrayEquals(LibTiff.rgba(predicted), pixels, "libtiff reads the predicted file differently");
            }
        }

        @Test
        @DisplayName("the libtiff vectors match their pinned checksums")
        void checksums() throws Exception {
            var manifest = new String(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/ycbcr.sha256"),
                    java.nio.charset.StandardCharsets.US_ASCII);
            var lines = manifest.lines().filter(line -> !line.isBlank()).toList();
            assertEquals(10, lines.size());
            for (var line : lines) {
                var parts = line.trim().split("\\s+\\*?");
                var digest = java.security.MessageDigest.getInstance("SHA-256").digest(vector(parts[1]));
                assertEquals(parts[0], java.util.HexFormat.of().formatHex(digest), parts[1]);
            }
        }

        @Test
        @DisplayName("reject interleaved subsampled data units with extra samples, whose layout TIFF does not define")
        void extraSamples() {
            var file = TiffFiles.of(8, 8, 4, 8).photometric(6).tag(530, 2, 2).extra(2).data(new byte[8 * 8 * 4])
                    .bytes();
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> TiffDecoder.decode(MediaSource.of(file)));
            assertEquals("tiff ycbcr subsampling", failure.format());
        }

        @Test
        @DisplayName("reject YCbCr of samples of different sizes or floating point")
        void unsupportedSamples() {
            var mixed = TiffFiles.of(4, 4, 3, 8).bits(8, 16, 16).photometric(6).tag(530, 1, 1)
                    .data(new byte[4 * 4 * 5]).bytes();
            assertTrue(assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.decode(MediaSource.of(mixed)))
                    .format().startsWith("tiff ycbcr"));
            var floating = TiffFiles.of(4, 4, 3, 32).format(3).photometric(6).tag(530, 1, 1)
                    .data(new byte[4 * 4 * 12]).bytes();
            assertTrue(assertThrows(MediaException.Unsupported.class,
                    () -> TiffDecoder.decode(MediaSource.of(floating))).format().startsWith("tiff ycbcr"));
        }

        @Test
        @DisplayName("decode damaged deep and planar YCbCr files with media exceptions only")
        void damaged() {
            var random = new Random(4);
            var seeds = List.of(interleaved(Samples.random(13, 9, 4, 2, 16, false, 1), true).bytes(),
                    planar(Samples.random(13, 9, 2, 2, 12, true, 2), 0, 16, 16, false).bytes(),
                    planar(Samples.random(13, 9, 4, 4, 8, false, 3), 4, 0, 0, true).bytes());
            for (var seed : seeds) {
                for (var round = 0; round < 200; round++) {
                    var data = seed.clone();
                    for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                        data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    }
                    if (random.nextBoolean()) {
                        data = java.util.Arrays.copyOf(data, random.nextInt(data.length));
                    }
                    try {
                        TiffDecoder.decode(MediaSource.of(data), WIDE);
                    } catch (MediaException expected) {
                        // a damaged structure is malformed, unsupported or over a limit
                    }
                }
            }
        }
    }

    static byte[] deflate(byte[] data) {
        var deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
