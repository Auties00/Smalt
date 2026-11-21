package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes the libtiff test images (the pics-3.8.0 set and the images of libtiff 4.7.1's test
 * suite) and compares them with libtiff's own TIFFReadRGBAImage (tiff2rgba) as a black-box
 * oracle. Smalt's straight alpha is premultiplied as libtiff does before comparing.
 */
class TiffConformanceTest {
    // libtiff reduces 16-bit samples and 16-bit palette entries by truncation where Smalt rounds,
    // and associated alpha does not round-trip exactly through straight alpha

    private static final Map<String, Integer> TOLERANCE = Map.ofEntries(
            Map.entry("ladoga.tif", 1),
            Map.entry("minisblack-1c-16b.tiff", 1),
            Map.entry("rgb-3c-16b.tiff", 1),
            Map.entry("flower-minisblack-16.tif", 1),
            Map.entry("flower-rgb-contig-16.tif", 1),
            Map.entry("flower-rgb-planar-16.tif", 1),
            Map.entry("flower-separated-contig-16.tif", 1),
            Map.entry("flower-separated-planar-16.tif", 1),
            // libtiff truncates the naive CMYK conversion, Smalt rounds it
            Map.entry("flower-separated-contig-08.tif", 1),
            Map.entry("flower-separated-planar-08.tif", 1),
            Map.entry("flower-palette-02.tif", 1),
            Map.entry("flower-palette-04.tif", 1),
            Map.entry("flower-palette-08.tif", 1),
            Map.entry("flower-palette-16.tif", 1),
            Map.entry("jello.tif", 1),
            Map.entry("palette-1c-4b.tiff", 1),
            Map.entry("palette-1c-8b.tiff", 1),
            Map.entry("strike.tif", 1));

    private static final Map<String, String> UNSUPPORTED = Map.of();

    // libtiff fails the last strip of this ThunderScan file ("Not enough data at scanline 320") and
    // leaves stale buffer contents in its rows, where Smalt leaves the missing pixels zero
    private static final Map<String, Integer> LIBTIFF_FAILS_FROM_ROW = Map.of("text.tif", 320);

    static Stream<String> pics() {
        return LibTiff.pics().keySet().stream().filter(name -> name.endsWith(".tif")).sorted();
    }

    static Stream<String> suite() {
        return LibTiff.sources().keySet().stream().filter(name -> name.endsWith(".tif") || name.endsWith(".tiff"))
                .filter(name -> !name.startsWith("test_ifd_loop") && !name.equals("tiff_with_subifd_chain.tif"))
                .sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pics")
    @DisplayName("pics-3.8.0 images decode like libtiff")
    void picsSet(String name) {
        check(name, LibTiff.pics().get(name));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("suite")
    @DisplayName("libtiff test-suite images decode like libtiff")
    void testSuite(String name) {
        check(name, LibTiff.sources().get(name));
    }

    @Test
    @DisplayName("directory chains that loop end at the last new page")
    void loops() {
        for (var name : new String[]{"test_ifd_loop_to_self.tif", "test_ifd_loop_to_first.tif", "test_ifd_loop_subifd.tif",
                "test_two_ifds.tif", "tiff_with_subifd_chain.tif"}) {
            var data = LibTiff.sources().get(name);
            assertNotNull(data, name);
            try (var pages = TiffDecoder.openPages(MediaSource.of(data))) {
                assertTrue(pages.pageCount() >= 1 && pages.pageCount() <= 8, name + ": " + pages.pageCount());
                for (var i = 0; i < pages.pageCount(); i++) {
                    try {
                        pages.read(i);
                    } catch (MediaException expected) {
                        // a page of a deliberately broken file may be rejected, but only with a MediaException
                    }
                }
            }
        }
    }

    static Stream<String> depths() {
        return pics().filter(name -> name.startsWith("flower-") && !name.contains("palette"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("depths")
    @DisplayName("every depth of the GraphicsMagick depth set matches its 8-bit rendition within the depth's step")
    void depthSet(String name) {
        // every file reduces the same 32-bit master to its depth (GraphicsMagick truncates), so each
        // decodes to the 8-bit file within one step of its own depth; the naive CMYK conversion
        // doubles the rounding of the 8-bit file
        var depth = Integer.parseInt(name.substring(name.length() - 6, name.length() - 4));
        var reference = name.substring(0, name.length() - 6) + "08.tif";
        if (name.contains("rgb-planar")) {
            reference = "flower-rgb-contig-08.tif";
        }
        var expected = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(LibTiff.pics().get(reference))).picture());
        var actual = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(LibTiff.pics().get(name))).picture());
        var tolerance = depth >= 8 ? (name.contains("separated") ? 2 : 1) : (int) Math.ceil(255.0 / ((1 << depth) - 1)) + 1;
        for (var i = 0; i < expected.length; i++) {
            assertTrue(Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)) <= tolerance,
                    name + " sample " + i + ": " + (expected[i] & 0xFF) + " vs " + (actual[i] & 0xFF));
        }
    }

    @Test
    @DisplayName("the PNM references of libtiff's suite match exactly, 16-bit included")
    void pnmReferences() {
        var sources = LibTiff.sources();
        assertPnm(sources.get("minisblack-1c-8b.tiff"), sources.get("minisblack-1c-8b.pgm"));
        assertPnm(sources.get("rgb-3c-8b.tiff"), sources.get("rgb-3c-8b.ppm"));
        assertPnm(sources.get("rgb-3c-16b.tiff"), sources.get("rgb-3c-16b.ppm"));
        assertPnm(sources.get("miniswhite-1c-1b.tiff"), sources.get("miniswhite-1c-1b.pbm"));
    }

    private static void assertPnm(byte[] tiff, byte[] pnm) {
        var reference = Pnm.read(pnm);
        var decoded = TiffDecoder.decode(MediaSource.of(tiff), ImageDecoderOptions.builder().highBitDepth(true)
                .build());
        var picture = decoded.picture();
        assertEquals(reference.width(), picture.width());
        assertEquals(reference.height(), picture.height());
        for (var y = 0; y < reference.height(); y++) {
            for (var x = 0; x < reference.width(); x++) {
                for (var c = 0; c < 3; c++) {
                    var expected = reference.sample(x, y, c);
                    var actual = switch (picture) {
                        case RgbaPicture rgba -> rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF;
                        case com.github.auties00.smalt.image.Rgba16Picture rgba -> rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFFFF;
                        default -> throw new AssertionError(picture.getClass().toString());
                    };
                    assertEquals(expected, actual, "pixel " + x + "," + y + " channel " + c);
                }
            }
        }
    }

    private static void check(String name, byte[] data) {
        LibTiff.assume("tiff2rgba");
        assertNotNull(data, name);
        RgbaPicture picture;
        try {
            picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(data)).picture();
        } catch (MediaException.Unsupported unsupported) {
            assertEquals(UNSUPPORTED.get(name), unsupported.format(), name + ": " + unsupported.getMessage());
            return;
        }
        assertFalse(UNSUPPORTED.containsKey(name), name + " decoded but is listed as unsupported");
        var expected = LibTiff.rgba(data);
        if (expected == null) {
            return;
        }
        var actual = LibTiff.premultiply(RasterTesting.tight(picture));
        assertEquals(expected.length, actual.length, name + ": size");
        var tolerance = TOLERANCE.getOrDefault(name, 0);
        var worst = 0;
        var at = -1;
        for (var i = 0; i < expected.length; i++) {
            var alpha = expected[i | 3] & 0xFF;
            if (i / 4 / picture.width() >= LIBTIFF_FAILS_FROM_ROW.getOrDefault(name, Integer.MAX_VALUE)) {
                continue;
            }
            // a premultiplied sample above its alpha is invalid and clamps when the alpha is divided out
            var reference = i % 4 == 3 ? expected[i] & 0xFF : Math.min(expected[i] & 0xFF, alpha);
            var difference = Math.abs(reference - (actual[i] & 0xFF));
            if (difference > worst) {
                worst = difference;
                at = i;
            }
        }
        if (worst > tolerance) {
            var pixel = at / 4;
            fail(name + ": max difference " + worst + " at pixel (" + pixel % picture.width() + "," + pixel / picture.width()
                    + ") channel " + at % 4 + ", expected " + (expected[at] & 0xFF) + " got " + (actual[at] & 0xFF));
        }
    }

    /** A binary PGM, PPM or PBM image. */
    record Pnm(int width, int height, int max, int channels, byte[] data, int offset) {
        static Pnm read(byte[] file) {
            var kind = file[1];
            var position = new int[]{2};
            var width = token(file, position);
            var height = token(file, position);
            var max = kind == '4' ? 1 : token(file, position);
            position[0]++;
            var channels = kind == '6' ? 3 : 1;
            return new Pnm(width, height, max, channels, file, position[0]);
        }

        int sample(int x, int y, int c) {
            if (max == 1) {
                var row = (width + 7) / 8;
                var bit = (data[offset + y * row + x / 8] >> (7 - x % 8)) & 1;
                return bit == 1 ? 0 : 255;
            }
            var channel = channels == 1 ? 0 : c;
            var index = (y * width + x) * channels + channel;
            if (max > 255) {
                return (data[offset + index * 2] & 0xFF) << 8 | (data[offset + index * 2 + 1] & 0xFF);
            }
            return data[offset + index] & 0xFF;
        }

        private static int token(byte[] file, int[] position) {
            while (Character.isWhitespace(file[position[0]]) || file[position[0]] == '#') {
                if (file[position[0]] == '#') {
                    while (file[position[0]] != '\n') {
                        position[0]++;
                    }
                }
                position[0]++;
            }
            var value = 0;
            while (Character.isDigit(file[position[0]])) {
                value = value * 10 + file[position[0]++] - '0';
            }
            return value;
        }
    }
}
