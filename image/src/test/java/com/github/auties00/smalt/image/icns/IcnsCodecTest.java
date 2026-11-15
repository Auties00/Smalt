package com.github.auties00.smalt.image.icns;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ICNS codec: the samples of tools/testdata/behavior/make-raster-samples.py (whose 24-bit images Pillow
 * 13.0.0 decodes to the pinned SHA-256 digests below), every element type it writes read back exactly, the 1, 4 and
 * 8-bit icons in the system palettes, the dark appearance, the choice of image, the provider and page writer, and
 * rejection of malformed files.
 */
@DisplayName("ICNS codec")
class IcnsCodecTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "icns");

    /**
     * The 16-color system palette, as listed in Apple's Inside Macintosh.
     */
    private static final int[] CLUT4 = {0xFFFFFF, 0xFCF305, 0xFF6402, 0xDD0806, 0xF20884, 0x4600A5, 0x0000D4,
            0x02ABEA, 0x1FB714, 0x006411, 0x562C05, 0x90713A, 0xC0C0C0, 0x808080, 0x404040, 0x000000};

    private static byte[] sample(String name) {
        try {
            return Files.readAllBytes(SAMPLES.resolve(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The pattern of make-raster-samples.py's icns_rgba: a flat band, gradients and an alpha pattern. */
    static byte[] pattern(int size, int seed) {
        var pixels = new byte[size * size * 4];
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                int r;
                int g;
                int b;
                if (y < size / 4) {
                    r = g = b = 200;
                } else {
                    r = x * 255 / (size - 1);
                    g = y * 255 / (size - 1);
                    b = ((x + y) * 37 + seed) & 255;
                }
                var a = y >= size / 2 ? (x * 16 + seed) & 255 : ((x + y) % 5 != 0 ? 255 : 0);
                var at = (y * size + x) * 4;
                pixels[at] = (byte) r;
                pixels[at + 1] = (byte) g;
                pixels[at + 2] = (byte) b;
                pixels[at + 3] = (byte) a;
            }
        }
        return pixels;
    }

    static RgbaPicture picture(int size, int seed) {
        return RgbaPicture.of(size, size, size * 4, pattern(size, seed), true);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] decode(byte[] file, int index) {
        return RasterTesting.tight(IcnsDecoder.decode(MediaSource.of(file), index, ImageDecoderOptions.defaults())
                .picture());
    }

    private static List<String> types(byte[] file) {
        return IcnsDecoder.entries(MediaSource.of(file)).stream().map(IcnsEntry::type).toList();
    }

    private static byte[] element(String type, byte[] data) {
        return ByteBuffer.allocate(8 + data.length).put(type.getBytes(StandardCharsets.ISO_8859_1))
                .putInt(8 + data.length).put(data).array();
    }

    private static byte[] family(byte[]... elements) {
        var body = RasterTesting.concat(elements);
        return RasterTesting.concat(ByteBuffer.allocate(8).put("icns".getBytes(StandardCharsets.ISO_8859_1))
                .putInt(8 + body.length).array(), body);
    }

    private static void assertPixel(byte[] rgba, int index, int rgb, int alpha, String what) {
        var actual = (rgba[4 * index] & 0xFF) << 16 | (rgba[4 * index + 1] & 0xFF) << 8 | rgba[4 * index + 2] & 0xFF;
        assertEquals(rgb, actual, what + " color of pixel " + index);
        assertEquals(alpha, rgba[4 * index + 3] & 0xFF, what + " alpha of pixel " + index);
    }

    @Nested
    @DisplayName("recognition")
    class Recognition {
        @Test
        @DisplayName("recognizes the magic, registers the codec and probes the largest image")
        void registered() {
            var file = sample("legacy.icns");
            assertEquals(ImageFormat.ICNS, ImageFormat.detect(file, file.length).orElseThrow());
            assertTrue(Images.isDecodable(ImageFormat.ICNS));
            assertTrue(Images.isEncodable(ImageFormat.ICNS));
            assertTrue(Images.isPageEncodable(ImageFormat.ICNS));
            var info = IcnsDecoder.probe(MediaSource.of(file));
            assertEquals(128, info.width());
            assertEquals(9, info.frameCount());
            assertTrue(info.hasAlpha());
            var core = Images.probe(MediaSource.of(file));
            assertEquals(128, core.width());
        }

        @Test
        @DisplayName("counts the images of the dark appearance in the core probe")
        void coreDark() {
            var file = sample("modern.icns");
            var info = Media.probe(MediaSource.of(file)).image().orElseThrow();
            assertEquals(5, IcnsDecoder.entries(MediaSource.of(file)).size());
            assertEquals(5, info.frameCount().orElseThrow());
            assertEquals(128, info.width());
            assertEquals(9, Media.probe(MediaSource.of(sample("legacy.icns"))).image().orElseThrow().frameCount()
                    .orElseThrow());
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @Test
        @DisplayName("lists the legacy images with their masks and decodes the 24-bit ones as Pillow does")
        void legacy() {
            var file = sample("legacy.icns");
            var entries = IcnsDecoder.entries(MediaSource.of(file));
            assertEquals(List.of("ics#", "ics4", "ics8", "is32", "ICN#", "icl8", "il32", "ih32", "it32"),
                    entries.stream().map(IcnsEntry::type).toList());
            assertEquals(IcnsEntry.Encoding.RGB, entries.get(3).encoding());
            assertEquals(32, entries.get(3).bitDepth());
            assertEquals(24, entries.get(7).bitDepth());
            assertEquals(4, entries.get(1).bitDepth());
            assertTrue(entries.stream().noneMatch(IcnsEntry::dark));
            // SHA-256 of Pillow 13.0.0's IcnsFile.getimage((s, s, 1)).convert("RGBA").tobytes()
            assertEquals("889ad2f6562c57a609fe3fafd9d96d49bb1c579410f7d6f95e03a26e2ebfd062", sha256(decode(file, 3)));
            assertEquals("9e11aea633c045aafb169983ecf063649c5481cec3a6d4aaab243efab19f5e9b", sha256(decode(file, 6)));
            assertEquals("722503436be3e4298af71f522f1e0154f90af1fe022d336bb82245b898f55dde", sha256(decode(file, 7)));
            assertEquals("afdc3f1938c5e3919bd2311436093b4988371cb4772a6dad46e04b73c790ac29", sha256(decode(file, 8)));
            assertArrayEquals(pattern(16, 1), decode(file, 3));
            assertArrayEquals(pattern(128, 4), decode(file, 8));
            assertArrayEquals(RasterTesting.tight(IcnsDecoder.decode(MediaSource.of(file)).picture()), decode(file, 8));
        }

        @Test
        @DisplayName("decodes 1-bit icons and their masks")
        void mono() {
            var file = sample("legacy.icns");
            var rgba = decode(file, 0);
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 16; x++) {
                    assertPixel(rgba, y * 16 + x, ((x ^ y) & 1) != 0 ? 0 : 0xFFFFFF, x < 14 ? 255 : 0, "ics#");
                }
            }
        }

        @Test
        @DisplayName("decodes 4-bit icons in the 16-color palette, masked by the 1-bit icon of their size")
        void indexed4() {
            var rgba = decode(sample("legacy.icns"), 1);
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 16; x++) {
                    assertPixel(rgba, y * 16 + x, CLUT4[(x * 5 + y * 3) & 15], x < 14 ? 255 : 0, "ics4");
                }
            }
        }

        @Test
        @DisplayName("decodes 8-bit icons in the 256-color palette")
        void indexed8() {
            var indices = new byte[32 * 32];
            for (var index = 0; index < 256; index++) {
                indices[index] = (byte) index;
            }
            var rgba = decode(family(element("icl8", indices)), 0);
            // spot entries of the system palette: the color cube from white down, the ramps and black
            int[][] spots = {{0, 0xFFFFFF}, {1, 0xFFFFCC}, {5, 0xFFFF00}, {6, 0xFFCCFF}, {35, 0xFF0000},
                    {214, 0x000033}, {215, 0xEE0000}, {224, 0x110000}, {225, 0x00EE00}, {235, 0x0000EE},
                    {245, 0xEEEEEE}, {254, 0x111111}, {255, 0x000000}};
            for (var spot : spots) {
                assertPixel(rgba, spot[0], spot[1], 255, "icl8 index " + spot[0]);
            }
            var small = decode(sample("legacy.icns"), 2);
            assertPixel(small, 0, 0xFFFFFF, 255, "ics8 index 0");
            assertEquals(0, small[15 * 4 + 3], "ics8 masked column");
            assertEquals(255, small[13 * 4 + 3] & 0xFF, "ics8 opaque column");
        }

        @Test
        @DisplayName("decodes stored 24-bit planes, 16x12 icons and the unmasked 1-bit icon")
        void stored() {
            var file = sample("stored.icns");
            assertEquals(List.of("is32", "icm#", "icm4", "ICON"), types(file));
            // Pillow 13.0.0 reads the 768-byte is32 as stored RGB
            assertEquals("d93838557d08904c9ff3bfc93ac2d36a6967205b95ab29af4c66657f16109612", sha256(decode(file, 0)));
            var icm4 = decode(file, 2);
            assertEquals(16 * 12 * 4, icm4.length);
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 16; x++) {
                    assertPixel(icm4, y * 16 + x, CLUT4[(x + y) & 15], x < 14 ? 255 : 0, "icm4");
                }
            }
            var icon = decode(file, 3);
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 32; x++) {
                    assertPixel(icon, y * 32 + x, ((x / 4 + y / 4) & 1) != 0 ? 0 : 0xFFFFFF, 255, "ICON");
                }
            }
            assertFalse(IcnsDecoder.openPages(MediaSource.of(file)).probe(3).hasAlpha());
        }

        @Test
        @DisplayName("decodes ARGB and PNG images and the dark appearance, preferring the light one")
        void modern() {
            var file = sample("modern.icns");
            var entries = IcnsDecoder.entries(MediaSource.of(file));
            assertEquals(List.of("ic04", "ic05", "ic07", "ic04", "ic05"), entries.stream().map(IcnsEntry::type)
                    .toList());
            assertEquals(List.of(false, false, false, true, true), entries.stream().map(IcnsEntry::dark).toList());
            assertEquals(IcnsEntry.Encoding.ARGB, entries.get(0).encoding());
            assertEquals(IcnsEntry.Encoding.PNG, entries.get(2).encoding());
            assertArrayEquals(pattern(16, 6), decode(file, 0));
            assertArrayEquals(pattern(32, 7), decode(file, 1));
            assertArrayEquals(pattern(128, 8), decode(file, 2));
            assertArrayEquals(pattern(16, 9), decode(file, 3));
            assertArrayEquals(pattern(32, 10), decode(file, 4));
            var hinted = IcnsDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().minSize(20, 20)
                    .build());
            assertArrayEquals(pattern(32, 7), RasterTesting.tight(hinted.picture()));
            assertEquals(ImageFormat.ICNS, hinted.format());
            var pages = IcnsDecoder.openPages(MediaSource.of(file));
            assertEquals(5, pages.pageCount());
            assertArrayEquals(pattern(32, 10), RasterTesting.tight(pages.read(4).picture()));
        }

        @Test
        @DisplayName("keeps the images before an element cut by the end of the file")
        void truncated() {
            var file = sample("modern.icns");
            var first = 16 + ByteBuffer.wrap(file).getInt(20);
            var cut = java.util.Arrays.copyOf(file, first + 8 + 20);
            assertEquals(List.of("ic04"), types(cut));
            assertArrayEquals(pattern(16, 6), decode(cut, 0));
        }

        @ParameterizedTest
        @ValueSource(strings = {"header", "empty", "short-element", "rle", "mask"})
        @DisplayName("rejects malformed files")
        void malformed(String kind) {
            var file = switch (kind) {
                case "header" -> "icnz\0\0\0\u0010".getBytes(StandardCharsets.ISO_8859_1);
                case "empty" -> family(element("TOC ", new byte[0]));
                case "short-element" -> family(new byte[]{'i', 's', '3', '2', 0, 0, 0, 4});
                case "rle" -> family(element("is32", new byte[]{(byte) 0xFF, 1}));
                default -> family(element("is32", new byte[768]), element("s8mk", new byte[10]));
            };
            assertThrows(MediaException.Malformed.class, () -> IcnsDecoder.decode(MediaSource.of(file)));
        }

        @Test
        @DisplayName("survives mutated samples")
        void mutations() {
            var seeds = List.of(sample("legacy.icns"), sample("stored.icns"), sample("modern.icns"));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(200), 11,
                    source -> IcnsDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        private static String expectedType(IcnsEncoderOptions.Encoding encoding, int size) {
            return switch (encoding) {
                case AUTO -> switch (size) {
                    case 16 -> "ic04";
                    case 32 -> "ic05";
                    case 48 -> "ih32";
                    default -> pngType(size);
                };
                case PNG -> pngType(size);
                case RLE -> switch (size) {
                    case 16 -> "is32";
                    case 32 -> "il32";
                    case 48 -> "ih32";
                    case 128 -> "it32";
                    default -> pngType(size);
                };
            };
        }

        private static String pngType(int size) {
            return switch (size) {
                case 16 -> "icp4";
                case 18 -> "icsb";
                case 24 -> "sb24";
                case 32 -> "icp5";
                case 36 -> "icsB";
                case 48 -> "icp6";
                case 64 -> "ic12";
                case 128 -> "ic07";
                case 256 -> "ic08";
                case 512 -> "ic09";
                default -> "ic10";
            };
        }

        @ParameterizedTest
        @EnumSource(IcnsEncoderOptions.Encoding.class)
        @DisplayName("round trips every size exactly in the element type each encoding selects")
        void roundTrip(IcnsEncoderOptions.Encoding encoding) {
            var options = IcnsEncoderOptions.builder().encoding(encoding).build();
            var pictures = new ArrayList<RgbaPicture>();
            for (var size : IcnsEncoderOptions.SIZES) {
                var picture = picture(size, size);
                pictures.add(picture);
                var file = IcnsEncoder.encode(picture, options);
                assertEquals(List.of(expectedType(encoding, size)), types(file), encoding + " " + size);
                assertArrayEquals(pattern(size, size), decode(file, 0), encoding + " " + size);
                assertEquals(file.length, ByteBuffer.wrap(file).getInt(4));
            }
            var all = IcnsEncoder.encode(pictures.reversed(), options);
            var entries = IcnsDecoder.entries(MediaSource.of(all));
            assertEquals(IcnsEncoderOptions.SIZES.length, entries.size());
            for (var index = 0; index < entries.size(); index++) {
                var size = IcnsEncoderOptions.SIZES[index];
                assertEquals(size, entries.get(index).width());
                assertArrayEquals(pattern(size, size), decode(all, index));
            }
            assertEquals("TOC ", new String(all, 8, 4, StandardCharsets.ISO_8859_1));
        }

        @Test
        @DisplayName("writes opaque pictures with an opaque mask")
        void opaque() {
            var picture = RgbaPicture.allocate(16, 16, false);
            java.util.Arrays.fill(picture.pixels(), (byte) 77);
            for (var encoding : IcnsEncoderOptions.Encoding.values()) {
                var rgba = decode(IcnsEncoder.encode(picture, IcnsEncoderOptions.builder().encoding(encoding)
                        .build()), 0);
                for (var index = 0; index < 256; index++) {
                    assertPixel(rgba, index, 0x4D4D4D, 255, encoding.toString());
                }
            }
        }

        @Test
        @DisplayName("scales one picture into the macOS sizes")
        void macSizes() {
            var source = RgbaPicture.of(60, 40, 60 * 4, java.util.Arrays.copyOf(pattern(60, 3), 60 * 40 * 4), true);
            var file = IcnsEncoder.encode(source, IcnsEncoderOptions.macSizes());
            assertEquals(List.of("ic04", "ic05", "ic12", "ic07", "ic08", "ic09", "ic10"), types(file));
            var sink = MediaSink.memory();
            IcnsEncoder.encode(source, IcnsEncoderOptions.macSizes(), sink);
            assertArrayEquals(file, sink.toByteArray());
            var largest = IcnsDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(1024, largest.width());
        }

        @Test
        @DisplayName("rejects pictures and options an icon family cannot hold")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> IcnsEncoder.encode(picture(20, 1)));
            assertThrows(IllegalArgumentException.class, () -> IcnsEncoder.encode(RgbaPicture.allocate(16, 32, true)));
            assertThrows(IllegalArgumentException.class,
                    () -> IcnsEncoder.encode(List.of(picture(16, 1), picture(16, 2)), IcnsEncoderOptions.defaults()));
            assertThrows(IllegalArgumentException.class,
                    () -> IcnsEncoder.encode(List.of(), IcnsEncoderOptions.defaults()));
            assertThrows(IllegalArgumentException.class,
                    () -> IcnsEncoder.encode(List.of(picture(16, 1)), IcnsEncoderOptions.macSizes()));
            assertThrows(IllegalArgumentException.class, () -> IcnsEncoderOptions.builder().sizes(17).build());
            assertThrows(IllegalArgumentException.class, () -> IcnsEncoderOptions.builder().sizes(16, 16).build());
            assertThrows(NullPointerException.class, () -> IcnsEncoderOptions.builder().encoding(null));
            var options = IcnsEncoderOptions.builder().sizes(32, 16).encoding(IcnsEncoderOptions.Encoding.RLE)
                    .build();
            assertEquals(options, options.toBuilder().build());
            assertArrayEquals(new int[]{32, 16}, options.sizes());
        }

        @Test
        @DisplayName("encodes through Images and its page writer")
        void provider() {
            var file = Images.encode(picture(48, 2), ImageFormat.ICNS);
            assertArrayEquals(pattern(48, 2), RasterTesting.tight(Images.decode(MediaSource.of(file)).picture()));
            var wide = Images.encode(RgbaPicture.allocate(100, 50, true), ImageFormat.ICNS);
            assertEquals(128, IcnsDecoder.probe(MediaSource.of(wide)).width());
            var sink = MediaSink.memory();
            try (var writer = Images.openPageWriter(ImageFormat.ICNS, sink)) {
                writer.write(picture(16, 1));
                writer.write(picture(256, 2));
                assertThrows(IllegalArgumentException.class, () -> writer.write(picture(16, 3)));
                writer.finish();
            }
            var pages = Images.openPages(MediaSource.of(sink.toByteArray()));
            assertEquals(2, pages.pageCount());
            assertArrayEquals(pattern(256, 2), RasterTesting.tight(pages.read(1).picture()));
        }
    }
}
