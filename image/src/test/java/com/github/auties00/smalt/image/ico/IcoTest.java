package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers icon and cursor decoding and encoding: hand-built bitmap entries of every depth with AND
 * masks, PNG entries, ffmpeg-written icons, ffmpeg reading Smalt's icons, entry selection,
 * cursor hotspots, the registry and page access, and mutations.
 */
class IcoTest {
    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var i = 0; i < width * height; i++) {
            pixels[i * 4] = (byte) (i * 7);
            pixels[i * 4 + 1] = (byte) random.nextInt(256);
            pixels[i * 4 + 2] = (byte) (i / width * 11);
            pixels[i * 4 + 3] = (byte) (alpha ? (i % 5 == 0 ? 0 : random.nextInt(256)) : 255);
        }
        return picture;
    }

    /** Builds an icon file from raw entries: each a byte array, with its directory size and depth. */
    static byte[] file(boolean cursor, List<byte[]> images, int[] widths, int[] heights, int[] fieldA, int[] fieldB) {
        var out = new ByteArrayOutputStream();
        var count = images.size();
        write(out, 0, 2);
        write(out, cursor ? 2 : 1, 2);
        write(out, count, 2);
        var offset = 6 + 16 * count;
        for (var i = 0; i < count; i++) {
            out.write(widths[i] & 0xFF);
            out.write(heights[i] & 0xFF);
            out.write(0);
            out.write(0);
            write(out, fieldA[i], 2);
            write(out, fieldB[i], 2);
            write(out, images.get(i).length, 4);
            write(out, offset, 4);
            offset += images.get(i).length;
        }
        for (var image : images) {
            out.writeBytes(image);
        }
        return out.toByteArray();
    }

    /** Builds a bitmap entry: header, palette, XOR rows and AND rows, bottom-up. */
    static byte[] bitmap(int width, int height, int bits, int[] palette, int[] indices, byte[] bgra, boolean[] mask) {
        var out = new ByteArrayOutputStream();
        var xorRow = ((width * bits + 31) / 32) * 4;
        var andRow = ((width + 31) / 32) * 4;
        write(out, 40, 4);
        write(out, width, 4);
        write(out, height * 2L, 4);
        write(out, 1, 2);
        write(out, bits, 2);
        write(out, 0, 4);
        write(out, (long) (xorRow + andRow) * height, 4);
        write(out, 0, 4);
        write(out, 0, 4);
        write(out, palette == null ? 0 : palette.length, 4);
        write(out, 0, 4);
        if (palette != null) {
            for (var color : palette) {
                write(out, color & 0xFFFFFF, 4);
            }
        }
        for (var row = height - 1; row >= 0; row--) {
            var line = new byte[xorRow];
            for (var x = 0; x < width; x++) {
                var i = row * width + x;
                switch (bits) {
                    case 32 -> System.arraycopy(bgra, i * 4, line, x * 4, 4);
                    case 24 -> System.arraycopy(bgra, i * 4, line, x * 3, 3);
                    case 8 -> line[x] = (byte) indices[i];
                    case 4 -> line[x / 2] |= (byte) (indices[i] << ((x & 1) == 0 ? 4 : 0));
                    default -> line[x / 8] |= (byte) (indices[i] << (7 - x % 8));
                }
            }
            out.writeBytes(line);
        }
        for (var row = height - 1; row >= 0; row--) {
            var line = new byte[andRow];
            for (var x = 0; x < width; x++) {
                if (mask[row * width + x]) {
                    line[x / 8] |= (byte) (0x80 >>> (x % 8));
                }
            }
            out.writeBytes(line);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, long value, int bytes) {
        for (var i = 0; i < bytes; i++) {
            out.write((int) (value >>> (8 * i)));
        }
    }

    @Nested
    @DisplayName("bitmap entries")
    class Bitmaps {
        @ParameterizedTest(name = "{0} bits per pixel")
        @ValueSource(ints = {1, 4, 8, 24, 32, 0})
        @DisplayName("decode with their AND mask, or their alpha when it is in use")
        void depths(int depth) {
            var bits = depth == 0 ? 32 : depth;
            var width = 13;
            var height = 7;
            var random = new Random(depth);
            var palette = bits <= 8 ? new int[1 << bits] : null;
            if (palette != null) {
                for (var i = 0; i < palette.length; i++) {
                    palette[i] = random.nextInt(1 << 24);
                }
            }
            var indices = new int[width * height];
            var bgra = new byte[width * height * 4];
            var mask = new boolean[width * height];
            var expected = new byte[width * height * 4];
            for (var i = 0; i < width * height; i++) {
                mask[i] = random.nextInt(3) == 0;
                int rgb;
                if (palette != null) {
                    indices[i] = random.nextInt(palette.length);
                    rgb = palette[indices[i]];
                } else {
                    rgb = random.nextInt(1 << 24);
                }
                var alpha = depth == 32 ? random.nextInt(256) : 0;
                bgra[i * 4] = (byte) rgb;
                bgra[i * 4 + 1] = (byte) (rgb >>> 8);
                bgra[i * 4 + 2] = (byte) (rgb >>> 16);
                bgra[i * 4 + 3] = (byte) alpha;
                var transparent = depth == 32 ? alpha == 0 : mask[i];
                if (depth == 32) {
                    expected[i * 4] = (byte) (rgb >>> 16);
                    expected[i * 4 + 1] = (byte) (rgb >>> 8);
                    expected[i * 4 + 2] = (byte) rgb;
                    expected[i * 4 + 3] = (byte) alpha;
                } else if (!transparent) {
                    expected[i * 4] = (byte) (rgb >>> 16);
                    expected[i * 4 + 1] = (byte) (rgb >>> 8);
                    expected[i * 4 + 2] = (byte) rgb;
                    expected[i * 4 + 3] = (byte) 0xFF;
                }
            }
            var entry = bitmap(width, height, bits, palette, indices, bgra, mask);
            var ico = file(false, List.of(entry), new int[]{width}, new int[]{height}, new int[]{1}, new int[]{bits});
            assertEquals(ImageFormat.ICO, Images.detect(MediaSource.of(ico)).orElseThrow());
            var decoded = IcoDecoder.decode(MediaSource.of(ico));
            var picture = (RgbaPicture) decoded.picture();
            assertTrue(picture.hasAlpha());
            RasterTesting.assertSameRgba(expected, RasterTesting.tight(picture), width, bits + "-bit");
            var entries = IcoDecoder.entries(MediaSource.of(ico));
            assertEquals(new IcoEntry(0, width, height, bits, IcoEntry.Encoding.BMP, java.util.Optional.empty()), entries.getFirst());
        }

        @ParameterizedTest(name = "{0} bits per pixel")
        @ValueSource(ints = {1, 4, 8, 16, 24, 32})
        @DisplayName("probe as possibly transparent, since any of them can carry an AND mask")
        void probeAlpha(int bits) {
            var width = 4;
            var height = 2;
            var palette = bits <= 8 ? new int[1 << bits] : null;
            var mask = new boolean[width * height];
            mask[0] = true;
            var entry = bitmap(width, height, bits == 16 ? 24 : bits, palette, new int[width * height],
                    new byte[width * height * 4], mask);
            if (bits == 16) {
                entry[14] = 16;
            }
            var ico = file(false, List.of(entry), new int[]{width}, new int[]{height}, new int[]{1}, new int[]{bits});
            assertTrue(IcoDecoder.probe(MediaSource.of(ico)).hasAlpha());
        }

        @Test
        @DisplayName("find the AND mask after the color table the bitmap header resolves")
        void maskAfterResolvedPalette() {
            var width = 5;
            var height = 3;
            var palette = new int[256];
            for (var i = 0; i < palette.length; i++) {
                palette[i] = i * 0x010101;
            }
            var indices = new int[width * height];
            var mask = new boolean[width * height];
            for (var i = 0; i < indices.length; i++) {
                indices[i] = 40 + i;
                mask[i] = i % 2 == 0;
            }
            var entry = bitmap(width, height, 8, palette, indices, null, mask);
            // biClrUsed above 256 declares more colors than an 8-bit table holds; the table has 256
            entry[32] = (byte) 300;
            entry[33] = (byte) (300 >>> 8);
            var ico = file(false, List.of(entry), new int[]{width}, new int[]{height}, new int[]{1}, new int[]{8});
            var picture = (RgbaPicture) IcoDecoder.decode(MediaSource.of(ico)).picture();
            var pixels = RasterTesting.tight(picture);
            for (var i = 0; i < width * height; i++) {
                var gray = (byte) (40 + i);
                var expected = mask[i] ? new byte[]{0, 0, 0, 0} : new byte[]{gray, gray, gray, (byte) 0xFF};
                for (var c = 0; c < 4; c++) {
                    assertEquals(expected[c], pixels[i * 4 + c], "pixel " + i + " sample " + c);
                }
            }
        }

        @Test
        @DisplayName("ffmpeg-written icons decode to their source pixels")
        void ffmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = picture(48, 48, true, 3);
            var png = RasterTesting.write("ico-source.png", PngEncoder.encode(source));
            for (var format : new String[]{"bgra", "bgr24"}) {
                var out = RasterTesting.write("ffmpeg-" + format + ".ico", new byte[0]);
                Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", png.toString(),
                        "-pix_fmt", format, out.toString()));
                var decoded = (RgbaPicture) IcoDecoder.decode(MediaSource.of(readAll(out))).picture();
                var expected = RasterTesting.tight(source);
                if (format.equals("bgr24")) {
                    // ffmpeg writes an empty AND mask for 24-bit entries
                    for (var i = 3; i < expected.length; i += 4) {
                        expected[i] = (byte) 0xFF;
                    }
                }
                RasterTesting.assertSameVisible(expected, RasterTesting.tight(decoded), 48, format);
            }
        }
    }

    private static byte[] readAll(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.readAllBytes(path);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("encoder")
    class Encoder {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"AUTO", "BMP", "PNG"})
        @DisplayName("round-trips through the decoder and through ffmpeg")
        void roundTrip(String encoding) {
            var options = IcoEncoderOptions.builder().encoding(IcoEncoderOptions.Encoding.valueOf(encoding)).build();
            for (var source : new RgbaPicture[]{picture(32, 32, true, 1), picture(17, 5, false, 2), picture(256, 256, true, 3), picture(1, 1, true, 4)}) {
                var ico = IcoEncoder.encode(source, options);
                var decoded = (RgbaPicture) IcoDecoder.decode(MediaSource.of(ico)).picture();
                RasterTesting.assertSameVisible(RasterTesting.tight(source), RasterTesting.tight(decoded), source.width(), encoding);
                var entry = IcoDecoder.entries(MediaSource.of(ico)).getFirst();
                var png = options.encoding() == IcoEncoderOptions.Encoding.PNG
                        || (options.encoding() == IcoEncoderOptions.Encoding.AUTO && source.width() == 256);
                assertEquals(png ? IcoEntry.Encoding.PNG : IcoEntry.Encoding.BMP, entry.encoding());
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var path = RasterTesting.write("smalt-" + encoding + "-" + source.width() + ".ico", ico);
                    var frames = RasterTesting.ffmpegFrames(path, "rgba");
                    var expected = RasterTesting.tight(source);
                    // ffmpeg reads the color of 32-bit bitmap entries but neither their alpha nor their mask
                    for (var i = 0; i < expected.length; i += 4) {
                        if (expected[i + 3] == 0) {
                            continue;
                        }
                        for (var c = 0; c < (png ? 4 : 3); c++) {
                            assertEquals(expected[i + c], frames[i + c], "ffmpeg " + encoding + " sample " + (i + c));
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("writes one square entry per size and decodes the best fit")
        void sizes() {
            var source = picture(300, 200, true, 5);
            var ico = IcoEncoder.encode(source, IcoEncoderOptions.windowsSizes());
            var entries = IcoDecoder.entries(MediaSource.of(ico));
            assertEquals(List.of(16, 24, 32, 48, 64, 256), entries.stream().map(IcoEntry::width).toList());
            assertEquals(256, IcoDecoder.decode(MediaSource.of(ico)).picture().width());
            assertEquals(32, IcoDecoder.decode(MediaSource.of(ico), ImageDecoderOptions.ofMinimumSize(30, 30)).picture().width());
            assertEquals(256, IcoDecoder.decode(MediaSource.of(ico), ImageDecoderOptions.ofMinimumSize(500, 500)).picture().width());
            assertEquals(48, IcoDecoder.decode(MediaSource.of(ico), 3, ImageDecoderOptions.defaults()).picture().width());
            assertThrows(IndexOutOfBoundsException.class, () -> IcoDecoder.decode(MediaSource.of(ico), 6, ImageDecoderOptions.defaults()));
            assertEquals(6, Images.probe(MediaSource.of(ico)).frameCount());
            try (var pages = Images.openPages(MediaSource.of(ico))) {
                assertEquals(6, pages.pageCount());
                assertEquals(24, pages.read(1).picture().width());
                assertEquals(64, pages.probe(4).width());
            }
        }

        @Test
        @DisplayName("writes cursors whose hotspot scales with each entry")
        void cursor() {
            var source = picture(64, 64, true, 6);
            var cur = IcoEncoder.encode(source, IcoEncoderOptions.builder().sizes(32, 64).hotspot(10, 20).build());
            assertEquals(2, cur[2]);
            assertEquals(ImageFormat.ICO, Images.detect(MediaSource.of(cur)).orElseThrow());
            var entries = IcoDecoder.entries(MediaSource.of(cur));
            assertEquals(new IcoEntry.Hotspot(5, 10), entries.get(0).hotspot().orElseThrow());
            assertEquals(new IcoEntry.Hotspot(10, 20), entries.get(1).hotspot().orElseThrow());
            assertTrue(IcoDecoder.entries(MediaSource.of(IcoEncoder.encode(source))).getFirst().hotspot().isEmpty());
        }

        @Test
        @DisplayName("validates entries and options")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> IcoEncoder.encode(picture(257, 10, false, 1)));
            assertThrows(IllegalArgumentException.class, () -> IcoEncoderOptions.builder().sizes(0).build());
            assertThrows(IllegalArgumentException.class, () -> IcoEncoderOptions.builder().sizes(16, 16).build());
            assertThrows(IllegalArgumentException.class, () -> IcoEncoderOptions.builder().hotspot(-1, 0).build());
            assertThrows(IllegalArgumentException.class, () -> IcoEncoder.encode(List.of(picture(4, 4, false, 1)),
                    IcoEncoderOptions.windowsSizes()));
            assertEquals(IcoEncoderOptions.windowsSizes(), IcoEncoderOptions.builder().sizes(16, 24, 32, 48, 64, 256).build());
            var file = IcoEncoder.encode(List.of(picture(16, 16, false, 1), picture(32, 32, true, 2)), IcoEncoderOptions.defaults());
            assertEquals(2, IcoDecoder.entries(MediaSource.of(file)).size());
        }

        @Test
        @DisplayName("is reached through the registry, scaling pictures over 256 pixels")
        void registry() {
            assertTrue(Images.isDecodable(ImageFormat.ICO));
            assertTrue(Images.isEncodable(ImageFormat.ICO));
            var big = Images.encode(picture(400, 100, false, 2), ImageFormat.ICO);
            assertEquals(256, Images.decode(MediaSource.of(big)).picture().width());
            var small = Images.encode(picture(20, 10, false, 3), ImageFormat.ICO);
            assertEquals(20, Images.probe(MediaSource.of(small)).width());
        }
    }

    @Test
    @DisplayName("rejects broken directories and survives mutations")
    void robustness() {
        assertThrows(MediaException.Malformed.class, () -> IcoDecoder.decode(MediaSource.of(new byte[]{0, 0, 1, 0, 0, 0})));
        assertThrows(MediaException.Malformed.class, () -> IcoDecoder.decode(MediaSource.of(new byte[]{0, 0, 1, 0, 1, 0, 16, 16})));
        var seeds = new ArrayList<byte[]>();
        seeds.add(IcoEncoder.encode(picture(16, 16, true, 1), IcoEncoderOptions.builder()
                .encoding(IcoEncoderOptions.Encoding.BMP).build()));
        seeds.add(IcoEncoder.encode(picture(16, 16, true, 2), IcoEncoderOptions.builder()
                .encoding(IcoEncoderOptions.Encoding.PNG).build()));
        seeds.add(IcoEncoder.encode(picture(40, 40, false, 3), IcoEncoderOptions.builder().sizes(16, 32).hotspot(1, 1)
                .build()));
        var random = new Random(4);
        var palette = new int[16];
        var indices = new int[9 * 9];
        for (var i = 0; i < indices.length; i++) {
            indices[i] = random.nextInt(16);
        }
        seeds.add(file(false, List.of(bitmap(9, 9, 4, palette, indices, null, new boolean[81])), new int[]{9}, new int[]{9},
                new int[]{1}, new int[]{4}));
        var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds()), () ->
                RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(200), 0x1C0, source -> {
                    for (var entry : IcoDecoder.entries(source)) {
                        IcoDecoder.decode(source, entry.index(), ImageDecoderOptions.defaults());
                    }
                    IcoDecoder.probe(source);
                }));
        assertTrue(stats.decoded() > 0 && stats.rejected() > 0, stats.toString());
    }

    @Nested
    @DisplayName("openPages, entries with options and Builder.hotspot(int, int)")
    class PagesEntriesAndHotspot {
        static byte[] twoEntries() {
            return IcoEncoder.encode(List.of(picture(16, 16, true, 1), picture(32, 32, true, 2)),
                    IcoEncoderOptions.defaults());
        }

        @Test
        @DisplayName("openPages(source) reads every entry at its own size, as openPages(source, defaults) does")
        void openPages() throws Exception {
            var ico = twoEntries();
            try (var pages = IcoDecoder.openPages(MediaSource.of(ico));
                 var reference = IcoDecoder.openPages(MediaSource.of(ico), ImageDecoderOptions.defaults())) {
                assertEquals(2, pages.pageCount());
                for (var i = 0; i < 2; i++) {
                    assertEquals(reference.probe(i), pages.probe(i));
                    assertArrayEquals(RasterTesting.tight((RgbaPicture) reference.read(i).picture()),
                            RasterTesting.tight((RgbaPicture) pages.read(i).picture()));
                }
            }
            try (var pages = IcoDecoder.openPages(MediaSource.of(ico), ImageDecoderOptions.ofMinimumSize(32, 32))) {
                assertEquals(16, pages.probe(0).width());
            }
        }

        @Test
        @DisplayName("entries(source, options) lists the entries and enforces the frame limit of the options")
        void entriesWithOptions() {
            var ico = twoEntries();
            assertEquals(IcoDecoder.entries(MediaSource.of(ico)),
                    IcoDecoder.entries(MediaSource.of(ico), ImageDecoderOptions.defaults()));
            var limited = ImageDecoderOptions.builder()
                    .limits(com.github.auties00.smalt.MediaLimits.builder().maxFrames(1).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> IcoDecoder.entries(MediaSource.of(ico), limited));
        }

        @Test
        @DisplayName("Builder.hotspot(x, y) equals hotspot(new Hotspot(x, y)) and writes a cursor")
        void hotspot() {
            var options = IcoEncoderOptions.builder().hotspot(3, 5).build();
            assertEquals(IcoEncoderOptions.builder().hotspot(new IcoEntry.Hotspot(3, 5)).build(), options);
            assertTrue(options.isCursor());
            var cur = IcoEncoder.encode(picture(16, 16, true, 3), options);
            assertEquals(new IcoEntry.Hotspot(3, 5), IcoDecoder.entries(MediaSource.of(cur)).getFirst().hotspot().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> IcoEncoderOptions.builder().hotspot(-1, 0).build());
        }
    }
}
