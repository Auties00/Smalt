package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers BMP decoding: ffmpeg-written files compared with ffmpeg, and hand-built files for every
 * header version, bit depth, bit-field layout and run-length case, compared with an independent
 * model of the browser rules; plus unsupported features, damage, limits and mutations.
 */
class BmpDecoderTest {
    private static final ImageDecoderOptions OPTIONS = ImageDecoderOptions.defaults();

    private static RgbaPicture decode(byte[] data) {
        return (RgbaPicture) BmpDecoder.decode(MediaSource.of(data), OPTIONS).picture();
    }

    private static int[] palette(int count, long seed) {
        var random = new Random(seed);
        var colors = new int[count];
        for (var i = 0; i < count; i++) {
            colors[i] = random.nextInt(1 << 24);
        }
        return colors;
    }

    private static int[] values(int width, int height, int bound, long seed) {
        var random = new Random(seed);
        var values = new int[width * height];
        for (var i = 0; i < values.length; i++) {
            values[i] = bound == 0 ? random.nextInt() : random.nextInt(bound);
        }
        return values;
    }

    static int scale(int value, int bits) {
        if (bits == 0) {
            return 0;
        }
        var maximum = (1L << bits) - 1;
        return (int) Math.round((value & 0xFFFFFFFFL) * 255.0 / maximum);
    }

    static byte[] expectPalette(int[] indices, int[] colors, int size) {
        var out = new byte[indices.length * 4];
        for (var i = 0; i < indices.length; i++) {
            var color = indices[i] < size ? colors[indices[i]] : 0;
            out[i * 4] = (byte) (color >>> 16);
            out[i * 4 + 1] = (byte) (color >>> 8);
            out[i * 4 + 2] = (byte) color;
            out[i * 4 + 3] = (byte) 0xFF;
        }
        return out;
    }

    static byte[] expectMasked(int[] pixels, int[] masks, boolean alphaUsed) {
        var out = new byte[pixels.length * 4];
        for (var i = 0; i < pixels.length; i++) {
            for (var c = 0; c < 4; c++) {
                var mask = c < masks.length ? masks[c] : 0;
                int value;
                if (c == 3 && (!alphaUsed || mask == 0)) {
                    value = 255;
                } else if (mask == 0) {
                    value = 0;
                } else {
                    value = scale((pixels[i] & mask) >>> Integer.numberOfTrailingZeros(mask), Integer.bitCount(mask));
                }
                out[i * 4 + c] = (byte) value;
            }
        }
        return out;
    }

    static Stream<Arguments> headersAndDepths() {
        var list = new ArrayList<Arguments>();
        for (var header : new int[]{12, 16, 40, 52, 56, 64, 108, 124}) {
            for (var depth : new int[]{1, 2, 4, 8, 24}) {
                list.add(Arguments.of(header, depth));
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("hand-built files")
    class HandBuilt {
        @ParameterizedTest(name = "header {0}, {1} bits")
        @MethodSource("com.github.auties00.smalt.image.bmp.BmpDecoderTest#headersAndDepths")
        @DisplayName("decodes every header version and palette or 24-bit depth at odd sizes, both row orders")
        void headers(int header, int depth) {
            for (var size : new int[][]{{1, 1}, {7, 3}, {33, 17}}) {
                for (var topDown : new boolean[]{false, true}) {
                    if (topDown && header == 12) {
                        continue;
                    }
                    var width = size[0];
                    var height = size[1];
                    var colors = depth <= 8 ? palette(1 << depth, depth) : null;
                    var values = values(width, height, depth <= 8 ? 1 << depth : 1 << 24, header * 31L + depth);
                    var data = BmpFiles.of(width, topDown ? -height : height, depth).header(header).palette(colors, 0)
                            .pixels(BmpFiles.rows(width, height, depth, values, topDown)).bytes();
                    var picture = decode(data);
                    byte[] expected;
                    if (depth <= 8) {
                        expected = expectPalette(values, colors, colors.length);
                    } else {
                        expected = expectMasked(values, new int[]{0xFF0000, 0xFF00, 0xFF}, false);
                    }
                    RasterTesting.assertSameRgba(expected, RasterTesting.tight(picture), width, header + "/" + depth + " " + width + "x" + height);
                    assertFalse(picture.hasAlpha());
                    var info = BmpDecoder.probe(MediaSource.of(data));
                    assertEquals(width, info.width());
                    assertEquals(height, info.height());
                    assertEquals(ImageFormat.BMP, info.format());
                }
            }
        }

        static Stream<Arguments> bitFields() {
            return Stream.of(
                    Arguments.of(16, 0, new int[]{0x7C00, 0x03E0, 0x001F}, false),
                    Arguments.of(16, 3, new int[]{0xF800, 0x07E0, 0x001F}, false),
                    Arguments.of(16, 3, new int[]{0x0F00, 0x00F0, 0x000F, 0xF000}, true),
                    Arguments.of(16, 3, new int[]{0x7C00, 0x03E0, 0x001F, 0x8000}, true),
                    Arguments.of(16, 3, new int[]{0x001F, 0x07E0, 0xF800}, false),
                    Arguments.of(16, 3, new int[]{0xF800, 0, 0x001F}, false),
                    Arguments.of(32, 3, new int[]{0x3FF00000, 0x000FFC00, 0x000003FF, 0xC0000000}, true),
                    Arguments.of(32, 3, new int[]{0x000000FF, 0x0000FF00, 0x00FF0000, 0xFF000000}, true),
                    Arguments.of(32, 3, new int[]{0xFFE00000, 0x001FFC00, 0x000003FF}, false),
                    Arguments.of(32, 6, new int[]{0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000}, true));
        }

        @ParameterizedTest(name = "{0} bits, compression {1}, masks {2}")
        @MethodSource("bitFields")
        @DisplayName("decodes 16- and 32-bit bit-field layouts with browser channel scaling")
        void masks(int depth, int compression, int[] masks, boolean alpha) {
            for (var header : new int[]{40, 56, 124}) {
                if (compression == 6 && header != 40) {
                    continue;
                }
                var width = 13;
                var height = 5;
                var pixels = values(width, height, depth == 16 ? 1 << 16 : 0, depth + header);
                var data = BmpFiles.of(width, height, depth).header(header).compression(compression)
                        .masks(compression == 0 ? null : masks).pixels(BmpFiles.rows(width, height, depth, pixels, false)).bytes();
                var picture = decode(data);
                var alphaUsed = alpha && (header >= 56 || compression == 6);
                var applied = header < 56 && compression == 3 ? Arrays.copyOf(masks, 3) : masks;
                RasterTesting.assertSameRgba(expectMasked(pixels, applied, alphaUsed), RasterTesting.tight(picture), width,
                        "header " + header);
                assertEquals(alphaUsed, picture.hasAlpha());
            }
        }

        @Test
        @DisplayName("uses 32-bit BI_RGB alpha only when some pixel has non-zero alpha")
        void alphaRule() {
            var zero = new int[]{0x00112233, 0x00445566, 0x00778899, 0x00AABBCC};
            var picture = decode(BmpFiles.of(2, 2, 32).pixels(BmpFiles.rows(2, 2, 32, zero, false)).bytes());
            assertFalse(picture.hasAlpha());
            assertEquals((byte) 0xFF, picture.pixels()[3]);
            assertEquals(0x11, picture.pixels()[0]);
            var some = new int[]{0x00112233, 0x80445566, 0x00778899, 0xFFAABBCC};
            var translucent = decode(BmpFiles.of(2, 2, 32).pixels(BmpFiles.rows(2, 2, 32, some, false)).bytes());
            assertTrue(translucent.hasAlpha());
            assertEquals(0, translucent.pixels()[3]);
            assertEquals((byte) 0x80, translucent.pixels()[7]);
            assertTrue(BmpDecoder.probe(MediaSource.of(BmpFiles.of(2, 2, 32).pixels(new byte[16]).bytes())).hasAlpha());
        }

        @Test
        @DisplayName("tolerates short palettes, out-of-range indices, a zero offset and a palette overlapping the data")
        void paletteTolerance() {
            var colors = palette(3, 5);
            var indices = new int[]{0, 1, 2, 3, 200, 1};
            var data = BmpFiles.of(3, 2, 8).palette(colors, 3).pixels(BmpFiles.rows(3, 2, 8, indices, false)).bytes();
            RasterTesting.assertSameRgba(expectPalette(indices, colors, 3), RasterTesting.tight(decode(data)), 3, "short palette");
            var zeroOffset = BmpFiles.of(3, 2, 8).palette(colors, 3).pixels(BmpFiles.rows(3, 2, 8, indices, false)).offset(0).bytes();
            assertEquals(0, zeroOffset[10]);
            RasterTesting.assertSameRgba(expectPalette(indices, colors, 3), RasterTesting.tight(decode(zeroOffset)), 3, "zero offset");
            var full = palette(256, 6);
            var overlap = BmpFiles.of(3, 2, 8).palette(full, 0).pixels(BmpFiles.rows(3, 2, 8, indices, false))
                    .offset(14 + 40 + 2 * 4).bytes();
            RasterTesting.assertSameRgba(expectPalette(indices, full, 2), RasterTesting.tight(decode(overlap)), 3, "overlap");
        }

        @Test
        @DisplayName("renders truncated pixel data partially and rejects missing data")
        void truncation() {
            var values = values(8, 8, 1 << 24, 3);
            var full = BmpFiles.of(8, 8, 24).pixels(BmpFiles.rows(8, 8, 24, values, false)).bytes();
            var cut = Arrays.copyOf(full, full.length - 24 * 3 - 5);
            var picture = decode(cut);
            assertTrue(picture.hasAlpha());
            var expected = expectMasked(values, new int[]{0xFF0000, 0xFF00, 0xFF}, false);
            var tight = RasterTesting.tight(picture);
            for (var y = 4; y < 8; y++) {
                assertArrayEquals(Arrays.copyOfRange(expected, y * 32, y * 32 + 32), Arrays.copyOfRange(tight, y * 32, y * 32 + 32));
            }
            assertEquals(0, tight[3]);
            var header = Arrays.copyOf(full, 54);
            assertThrows(MediaException.Malformed.class, () -> decode(header));
            var padded = BmpFiles.of(7, 3, 24).pixels(BmpFiles.rows(7, 3, 24, values(7, 3, 1 << 24, 4), false)).bytes();
            var noPadding = Arrays.copyOf(padded, padded.length - 3);
            assertFalse(decode(noPadding).hasAlpha());
        }

        @Test
        @DisplayName("reads the ICC profile of a V5 header")
        void profile() {
            var profile = new byte[500];
            new Random(1).nextBytes(profile);
            var data = BmpFiles.of(2, 2, 24).header(124).pixels(new byte[16]).profile(profile).bytes();
            assertArrayEquals(profile, BmpDecoder.decode(MediaSource.of(data), OPTIONS).iccProfile().orElseThrow());
        }
    }

    @Nested
    @DisplayName("run-length encoding")
    class RunLength {
        private static final int[] COLORS = {0x000000, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x123456, 0x654321, 0xABCDEF,
                0x111111, 0x222222, 0x333333, 0x444444, 0x555555, 0x666666, 0x777777, 0x888888};

        private static int pixel(RgbaPicture picture, int x, int y) {
            var i = (y * picture.width() + x) * 4;
            var p = picture.pixels();
            return (p[i] & 0xFF) << 16 | (p[i + 1] & 0xFF) << 8 | (p[i + 2] & 0xFF) | (p[i + 3] & 0xFF) << 24;
        }

        @Test
        @DisplayName("decodes RLE8 encoded runs, absolute runs, deltas, end of line and end of bitmap")
        void rle8() {
            var stream = new byte[]{
                    3, 1, 0, 3, 2, 3, 4, 0, 1, 5, 0, 0,
                    0, 2, 2, 1, 2, 6,
                    0, 0,
                    9, 7, 0, 1};
            var data = BmpFiles.of(8, 4, 8).compression(1).palette(COLORS, 16).pixels(stream).bytes();
            var picture = decode(data);
            assertTrue(picture.hasAlpha());
            assertEquals(0xFFFF0000, pixel(picture, 0, 3));
            assertEquals(0xFF00FF00, pixel(picture, 3, 3));
            assertEquals(0xFF0000FF, pixel(picture, 4, 3));
            assertEquals(0xFFFFFFFF, pixel(picture, 5, 3));
            assertEquals(0xFF123456, pixel(picture, 6, 3));
            assertEquals(0, pixel(picture, 7, 3) >>> 24, "unset after end of line");
            assertEquals(0, pixel(picture, 0, 2) >>> 24, "skipped by the delta");
            assertEquals(0xFF654321, pixel(picture, 2, 1));
            assertEquals(0xFF654321, pixel(picture, 3, 1));
            assertEquals(0xFFABCDEF, pixel(picture, 7, 0), "run clipped at the right edge");
            assertEquals(0xFFABCDEF, pixel(picture, 0, 0));
        }

        @Test
        @DisplayName("decodes RLE4 nibble runs and odd absolute runs")
        void rle4() {
            var stream = new byte[]{5, 0x12, 0, 3, 0x34, 0x50, 0, 0, 2, (byte) 0xF0, 0, 1};
            var data = BmpFiles.of(8, 2, 4).compression(2).palette(COLORS, 16).pixels(stream).bytes();
            var picture = decode(data);
            int[] expectedBottom = {1, 2, 1, 2, 1, 3, 4, 5};
            for (var x = 0; x < 8; x++) {
                assertEquals(0xFF000000 | COLORS[expectedBottom[x]], pixel(picture, x, 1), "x " + x);
            }
            assertEquals(0xFF000000 | COLORS[15], pixel(picture, 0, 0));
            assertEquals(0xFF000000, pixel(picture, 1, 0));
            assertEquals(0, pixel(picture, 2, 0) >>> 24);
        }

        @Test
        @DisplayName("marks a fully covered RLE image opaque and stops at truncated data")
        void coverage() {
            var full = BmpFiles.of(2, 1, 8).compression(1).palette(COLORS, 16).pixels(new byte[]{2, 3, 0, 1}).bytes();
            assertFalse(decode(full).hasAlpha());
            var truncated = BmpFiles.of(4, 1, 8).compression(1).palette(COLORS, 16).pixels(new byte[]{0, 4, 1, 2}).bytes();
            var picture = decode(truncated);
            assertEquals(0xFFFF0000, pixel(picture, 0, 0));
            assertEquals(0, pixel(picture, 2, 0) >>> 24);
        }

        @Test
        @DisplayName("rejects top-down run-length images and mismatched bit counts")
        void invalid() {
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, -2, 8).compression(1).palette(COLORS, 16)
                    .pixels(new byte[]{0, 1}).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 2, 4).compression(1).palette(COLORS, 16)
                    .pixels(new byte[]{0, 1}).bytes()));
        }
    }

    @Nested
    @DisplayName("rejection")
    class Rejection {
        @ParameterizedTest(name = "compression {0} header {1} depth {2} -> {3}")
        @CsvSource({"99, 40, 24, bmp compression 99", "7, 40, 24, bmp compression 7", "14, 124, 32, bmp compression 14"})
        @DisplayName("reports unknown compressions by name")
        void unsupported(int compression, int header, int depth, String format) {
            var data = BmpFiles.of(2, 2, depth).header(header).compression(compression).pixels(new byte[64]).bytes();
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(data));
            assertEquals(format, exception.format());
            assertThrows(MediaException.Unsupported.class, () -> BmpDecoder.probe(MediaSource.of(data)));
        }

        @Test
        @DisplayName("rejects invalid signatures, header sizes, dimensions, depths and masks")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> decode(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> decode("BA123456789012345678".getBytes()));
            var good = BmpFiles.of(2, 2, 24).pixels(new byte[16]).bytes();
            for (var size : new int[]{0, 8, 13}) {
                var bad = good.clone();
                bad[14] = (byte) size;
                assertThrows(MediaException.Malformed.class, () -> decode(bad), "header size " + size);
            }
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(0, 2, 24).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(-3, 2, 24).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 0, 24).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, Integer.MIN_VALUE, 24).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 2, 3).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 2, 16).compression(3)
                    .masks(0xF0F0, 0x0F00, 0x000F).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 2, 16).compression(3)
                    .masks(0xFF00, 0x0FF0, 0x000F).pixels(new byte[16]).bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 2, 24).compression(3)
                    .masks(0xFF0000, 0xFF00, 0xFF).pixels(new byte[16]).bytes()));
            var insideHeader = BmpFiles.of(2, 2, 24).pixels(new byte[16]).offset(20).bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(insideHeader));
        }

        @Test
        @DisplayName("checks the size against the limits before allocating")
        void limits() {
            var huge = BmpFiles.of(100_000, 100_000, 24).pixels(new byte[16]).bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> decode(huge));
            var small = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(8).maxPixels(64)
                    .maxFrames(1).maxChannels(1).maxBytes(1 << 20).build()).build();
            var image = BmpFiles.of(9, 1, 24).pixels(new byte[28]).bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> BmpDecoder.decode(MediaSource.of(image), small));
            assertEquals(9, BmpDecoder.probe(MediaSource.of(image)).width());
        }
    }

    @Nested
    @DisplayName("ffmpeg-written files")
    class FfmpegWritten {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"bgr24", "bgra", "pal8", "rgb8", "bgr8", "rgb4_byte", "bgr4_byte", "gray", "monob",
                "rgb555le", "rgb565le", "rgb444le"})
        @DisplayName("decodes every pixel format ffmpeg writes, at an odd size")
        void formats(String format) {
            var file = Fixtures.ffmpeg("bmp", List.of("-f", "lavfi", "-i", "testsrc2=size=100x60:rate=1"),
                    List.of("-frames:v", "1", "-vf", "format=rgba,crop=97:59:1:1,format=" + format));
            var data = Fixtures.read(file);
            var picture = decode(data);
            var tight = RasterTesting.tight(picture);
            if (format.startsWith("rgb5") || format.equals("rgb444le")) {
                // swscale expands 4/5/6-bit channels by bit replication, browsers (and this decoder)
                // round v * 255 / (2^n - 1): check against the stored pixels instead
                var masks = switch (format) {
                    case "rgb555le" -> new int[]{0x7C00, 0x03E0, 0x001F};
                    case "rgb565le" -> new int[]{0xF800, 0x07E0, 0x001F};
                    default -> new int[]{0x0F00, 0x00F0, 0x000F};
                };
                var offset = (data[10] & 0xFF) | (data[11] & 0xFF) << 8;
                var stride = (97 * 2 + 3) / 4 * 4;
                var stored = new int[97 * 59];
                for (var y = 0; y < 59; y++) {
                    for (var x = 0; x < 97; x++) {
                        var at = offset + (58 - y) * stride + x * 2;
                        stored[y * 97 + x] = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
                    }
                }
                RasterTesting.assertSameRgba(expectMasked(stored, masks, false), tight, 97, format);
            } else {
                RasterTesting.assertSameRgba(RasterTesting.ffmpegFrames(file, "rgba"), tight, 97, format);
            }
        }
    }

    @Test
    @DisplayName("survives a deterministic mutation run: every variant decodes or raises a MediaException")
    void mutations() {
        var seeds = List.of(
                BmpFiles.of(17, 9, 8).palette(palette(256, 1), 0).pixels(BmpFiles.rows(17, 9, 8, values(17, 9, 256, 2), false)).bytes(),
                BmpFiles.of(11, -7, 32).header(124).compression(3).masks(0xFF0000, 0xFF00, 0xFF, 0xFF000000)
                        .pixels(BmpFiles.rows(11, 7, 32, values(11, 7, 0, 3), true)).bytes(),
                BmpFiles.of(9, 9, 4).compression(2).palette(palette(16, 4), 16)
                        .pixels(new byte[]{5, 0x12, 0, 3, 0x34, 0x50, 0, 0, 0, 2, 3, 3, 9, 0x77, 0, 1}).bytes(),
                BmpFiles.of(13, 5, 16).header(12).pixels(BmpFiles.rows(13, 5, 16, values(13, 5, 1 << 15, 5), false)).bytes());
        var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds()), () -> RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(150), 0xB3F, source -> {
            BmpDecoder.probe(source);
            BmpDecoder.decode(source, OPTIONS);
        }));
        assertEquals(seeds.size() * RasterTesting.mutationsPerSeed(150), stats.variants());
        System.out.println("BMP mutations: " + stats);
    }

    @Test
    @DisplayName("Images.openAnimation presents a BMP as one seekable frame shown for zero microseconds")
    void stillAnimationSeeks() {
        var data = Fixtures.read(Fixtures.testImage("bmp", 16, 8, List.of("-pix_fmt", "bgr24")));
        try (var reader = com.github.auties00.smalt.image.Images.openAnimation(MediaSource.of(data))) {
            assertTrue(reader.isSeekable());
            var first = assertInstanceOf(RgbaPicture.class, reader.read().picture());
            assertNull(reader.read());
            reader.seek(0);
            assertNull(reader.read());
            reader.seek(-1);
            var again = reader.read();
            assertNotNull(again);
            assertEquals(0, again.timestampMicros());
            assertSame(first, again.picture());
            assertNull(reader.read());
            reader.seek(Long.MIN_VALUE);
            assertNotNull(reader.read());
        }
    }
}
