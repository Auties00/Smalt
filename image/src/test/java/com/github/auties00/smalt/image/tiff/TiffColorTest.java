package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the TIFF color interpretations rendered for display (CIE, ICC and ITU L*a*b*, LogL and
 * LogLuv) against libtiff's tiff2rgba as a black-box oracle, the color profile a page reports
 * (CMYK pages report none, and convert through an embedded CMYK profile on request), and the
 * size hint served by reduced-resolution pyramid levels. Files are built independently of the
 * decoder ({@link TiffFiles} and a minimal pyramid writer) and re-laid out with tiffcp.
 */
class TiffColorTest {
    private static final int WIDTH = 67;
    private static final int HEIGHT = 29;

    private static final List<List<String>> LAB_VARIANTS = List.of(
            List.of("-c", "none"),
            List.of("-c", "lzw"),
            List.of("-c", "zip", "-t", "-w", "32", "-l", "16"),
            List.of("-c", "packbits", "-B", "-r", "5"));

    private static final List<List<String>> LOGLUV_VARIANTS = List.of(
            List.of(),
            List.of("-t", "-w", "64", "-l", "48"),
            List.of("-r", "7"),
            List.of("-B", "-t", "-w", "32", "-l", "32"));

    static DecodedImage decode(byte[] data) {
        return TiffDecoder.decode(MediaSource.of(data));
    }

    static DecodedImage decode(byte[] data, ImageDecoderOptions options) {
        return TiffDecoder.decode(MediaSource.of(data), options);
    }

    static byte[] lab(int photometric, int bits, long[] values) {
        return TiffFiles.of(WIDTH, HEIGHT, 3, bits).photometric(photometric)
                .data(TiffFiles.pack(values, WIDTH, HEIGHT, 3, bits, true)).bytes();
    }

    static void assertLikeLibtiff(String name, byte[] file, int tolerance) {
        var expected = LibTiff.rgba(file);
        assertNotNull(expected, name + ": libtiff could not read the file");
        var picture = decode(file).picture();
        var actual = RasterTesting.tight((RgbaPicture) picture);
        assertEquals(expected.length, actual.length, name + ": size");
        for (var i = 0; i < expected.length; i++) {
            assertTrue(Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)) <= tolerance,
                    name + ": sample " + i + " expected " + (expected[i] & 0xFF) + " got " + (actual[i] & 0xFF));
        }
    }

    static void assertPixels(byte[] expected, byte[] actual, int tolerance, String message) {
        assertEquals(expected.length, actual.length, message);
        for (var i = 0; i < expected.length; i++) {
            assertTrue(Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)) <= tolerance,
                    message + ": sample " + i + " expected " + (expected[i] & 0xFF) + " got " + (actual[i] & 0xFF));
        }
    }

    @Nested
    @DisplayName("L*a*b*")
    class Lab {
        static Stream<Arguments> cases() {
            var cases = new ArrayList<Arguments>();
            for (var bits : new int[]{8, 16}) {
                for (var whitePoint : new boolean[]{false, true}) {
                    for (var variant : LAB_VARIANTS) {
                        cases.add(Arguments.of(bits, whitePoint, variant));
                    }
                }
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "{0}-bit, white point {1}, tiffcp {2}")
        @MethodSource("cases")
        @DisplayName("CIE L*a*b* renders exactly as libtiff renders it")
        void cieLab(int bits, boolean whitePoint, List<String> variant) {
            LibTiff.assume("tiffcp", "tiff2rgba");
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, (1L << bits) - 1, bits * 13L + (whitePoint ? 1 : 0));
            var file = TiffFiles.of(WIDTH, HEIGHT, 3, bits).photometric(8)
                    .data(TiffFiles.pack(values, WIDTH, HEIGHT, 3, bits, true));
            if (whitePoint) {
                file.rational(318, 3127, 10000, 3290, 10000);
            }
            var transcoded = LibTiff.tiffcp(file.bytes(), variant);
            assertLikeLibtiff("cielab " + bits, transcoded, 0);
            assertTrue(decode(transcoded).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("16-bit L*a*b* decodes to 8 bits even when high bit depth is asked for")
        void eightBitOutput() {
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, 65535, 5);
            var file = lab(8, 16, values);
            var wide = decode(file, ImageDecoderOptions.builder().highBitDepth(true).build());
            assertInstanceOf(RgbaPicture.class, wide.picture());
            assertArrayEquals(((RgbaPicture) decode(file).picture()).pixels(), ((RgbaPicture) wide.picture()).pixels());
        }

        @ParameterizedTest(name = "{0}-bit")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffColorTest#depths")
        @DisplayName("ICC L*a*b* decodes like the CIE L*a*b* it encodes")
        void iccLab(int bits) {
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, (1L << bits) - 1, 77);
            var cie = new long[values.length];
            var icc = new long[values.length];
            for (var i = 0; i < values.length; i += 3) {
                if (bits == 8) {
                    cie[i] = values[i];
                    icc[i] = values[i];
                    cie[i + 1] = values[i + 1];
                    cie[i + 2] = values[i + 2];
                    icc[i + 1] = (values[i + 1] + 128) & 0xFF;
                    icc[i + 2] = (values[i + 2] + 128) & 0xFF;
                } else {
                    // ICC version 2 16-bit L* has 0xFF00 for 100; CIE has 0xFFFF
                    var level = values[i] >>> 8;
                    cie[i] = level * 257;
                    icc[i] = level * 256;
                    cie[i + 1] = values[i + 1];
                    cie[i + 2] = values[i + 2];
                    icc[i + 1] = (values[i + 1] + 0x8000) & 0xFFFF;
                    icc[i + 2] = (values[i + 2] + 0x8000) & 0xFFFF;
                }
            }
            var expected = RasterTesting.tight((RgbaPicture) decode(lab(8, bits, cie)).picture());
            var actual = RasterTesting.tight((RgbaPicture) decode(lab(9, bits, icc)).picture());
            assertPixels(expected, actual, bits == 8 ? 0 : 1, "icclab " + bits);
        }

        @Test
        @DisplayName("ITU L*a*b* maps its codes through the Decode ranges")
        void ituLabDecode() {
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, 255, 91);
            var cie = new long[values.length];
            for (var i = 0; i < values.length; i += 3) {
                cie[i] = values[i];
                cie[i + 1] = (values[i + 1] - 128) & 0xFF;
                cie[i + 2] = (values[i + 2] - 128) & 0xFF;
            }
            var itu = TiffFiles.of(WIDTH, HEIGHT, 3, 8).photometric(10)
                    .rational(433, 0, 1, 100, 1, -128, 1, 127, 1, -128, 1, 127, 1)
                    .data(TiffFiles.pack(values, WIDTH, HEIGHT, 3, 8, true)).bytes();
            var expected = RasterTesting.tight((RgbaPicture) decode(lab(8, 8, cie)).picture());
            var actual = RasterTesting.tight((RgbaPicture) decode(itu).picture());
            assertPixels(expected, actual, 1, "itulab");
        }

        @Test
        @DisplayName("ITU L*a*b* without Decode takes the T.42 default ranges")
        void ituLabDefault() {
            // L* 100, a* -85 + 128 * 170 / 255 = 0.33, b* -75 + 96 * 200 / 255 = 0.29: the reference white
            var itu = new long[WIDTH * HEIGHT * 3];
            var cie = new long[WIDTH * HEIGHT * 3];
            for (var i = 0; i < itu.length; i += 3) {
                itu[i] = 255;
                itu[i + 1] = 128;
                itu[i + 2] = 96;
                cie[i] = 255;
            }
            var expected = RasterTesting.tight((RgbaPicture) decode(lab(8, 8, cie)).picture());
            var actual = RasterTesting.tight((RgbaPicture) decode(lab(10, 8, itu)).picture());
            assertPixels(expected, actual, 2, "itulab default");
        }

        @Test
        @DisplayName("L*a*b* of other depths renders as the equivalent 16-bit codes")
        void otherDepths() {
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, 15, 1);
            var codes = new long[values.length];
            for (var i = 0; i < values.length; i++) {
                // L* scaled to 16 bits; a* and b* signed, shifted by the 12 bits the 4-bit codes lack
                codes[i] = i % 3 == 0 ? Math.round(values[i] * 65535.0 / 15) : (values[i] << 60 >> 60 << 12) & 0xFFFF;
            }
            var expected = RasterTesting.tight((RgbaPicture) decode(lab(8, 16, codes)).picture());
            var actual = RasterTesting.tight((RgbaPicture) decode(lab(8, 4, values)).picture());
            assertArrayEquals(expected, actual);
        }
    }

    static Stream<Integer> depths() {
        return Stream.of(8, 16);
    }

    @Nested
    @DisplayName("LogL and LogLuv")
    class LogLuv {
        static Stream<Arguments> cases() {
            var cases = new ArrayList<Arguments>();
            for (var name : List.of("off_l16.tif", "off_luv24.tif", "off_luv32.tif")) {
                for (var variant : LOGLUV_VARIANTS) {
                    cases.add(Arguments.of(name, variant));
                }
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "{0}, tiffcp {1}")
        @MethodSource("cases")
        @DisplayName("SGILog strips and tiles render exactly as libtiff renders them")
        void layouts(String name, List<String> variant) {
            LibTiff.assume("tiffcp", "tiff2rgba");
            var source = LibTiff.pics().get(name);
            assertNotNull(source, name);
            var file = variant.isEmpty() ? source : LibTiff.tiffcp(source, variant);
            assertLikeLibtiff(name, file, 0);
            assertTrue(decode(file).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("truncated SGILog data decodes or fails only with a MediaException")
        void truncated() {
            var source = LibTiff.pics().get("off_luv32.tif");
            assertNotNull(source);
            var full = ((RgbaPicture) decode(source).picture()).pixels();
            for (var cut = source.length - 1; cut > source.length - 4000; cut -= 997) {
                try {
                    var damaged = decode(java.util.Arrays.copyOf(source, cut));
                    assertEquals(333, damaged.picture().width());
                } catch (MediaException expected) {
                    // a cut through the directory is malformed
                }
            }
            assertTrue(full.length > 0);
        }
    }

    @Nested
    @DisplayName("color profiles")
    class Profiles {
        static byte[] cmykProfile() {
            try {
                return Files.readAllBytes(Conformance.fetch("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc",
                        "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/icc_profiles/misc/US_Web_Coated_SWOP_CMYK.icc",
                        "4855b8fabb96bdc6495d45d089bb8c8efb1ae18389e0dc9e75a5f701a9c0b662"));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        static byte[] cmyk(int samples, byte[] icc) {
            var values = TiffFiles.pattern(WIDTH, HEIGHT, samples, 255, 17);
            var file = TiffFiles.of(WIDTH, HEIGHT, samples, 8).photometric(5)
                    .data(TiffFiles.pack(values, WIDTH, HEIGHT, samples, 8, true));
            if (samples == 5) {
                file.extra(2);
            }
            if (icc != null) {
                file.undefined(34675, icc);
            }
            return file.bytes();
        }

        @Test
        @DisplayName("a CMYK page reports its profile only through its bytes, since the pixels are naive RGB")
        void cmykProfileNotReported() {
            var icc = cmykProfile();
            var image = decode(cmyk(4, icc));
            assertTrue(image.colorProfile().isEmpty());
            assertArrayEquals(icc, image.iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("with sRGB conversion a CMYK page converts through its CMYK profile")
        void cmykConverted() {
            var icc = cmykProfile();
            var file = cmyk(4, icc);
            var converted = decode(file, ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            assertTrue(converted.iccProfile().isEmpty());
            var samples = TiffFiles.pack(TiffFiles.pattern(WIDTH, HEIGHT, 4, 255, 17), WIDTH, HEIGHT, 4, 8, true);
            var transform = ColorTransform.create(ColorProfile.parse(icc), ColorProfile.srgb());
            var expected = samples.clone();
            transform.transform(samples, 0, 4, expected, 0, 4, WIDTH * HEIGHT);
            for (var i = 3; i < expected.length; i += 4) {
                expected[i] = (byte) 0xFF;
            }
            assertArrayEquals(expected, RasterTesting.tight((RgbaPicture) converted.picture()));
        }

        @Test
        @DisplayName("a CMYK page with alpha or without a profile stays naive under sRGB conversion")
        void cmykNaive() {
            var options = ImageDecoderOptions.builder().convertToSrgb(true).build();
            for (var file : List.of(cmyk(5, cmykProfile()), cmyk(4, null))) {
                var plain = decode(file);
                var converted = decode(file, options);
                assertTrue(converted.colorProfile().isEmpty());
                assertArrayEquals(((RgbaPicture) plain.picture()).pixels(), ((RgbaPicture) converted.picture()).pixels());
            }
        }

        @Test
        @DisplayName("an RGB page with a profile converts as ImageOps.toSrgb converts it")
        void rgbConverted() throws IOException {
            var icc = Files.readAllBytes(Conformance.fetch("skia-icc-AdobeRGB1998.icc",
                    "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/icc_profiles/AdobeRGB1998.icc",
                    "304f569a83c1e5eddaddac54e99ed03339333db013738bb499ab64f049887e28"));
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, 255, 23);
            var file = TiffFiles.of(WIDTH, HEIGHT, 3, 8).undefined(34675, icc)
                    .data(TiffFiles.pack(values, WIDTH, HEIGHT, 3, 8, true)).bytes();
            var plain = decode(file);
            assertTrue(plain.colorProfile().isPresent());
            var converted = decode(file, ImageDecoderOptions.builder().convertToSrgb(true).build());
            var expected = ImageOps.toSrgb(plain);
            assertEquals(expected.colorProfile(), converted.colorProfile());
            assertArrayEquals(RasterTesting.tight((RgbaPicture) expected.picture()),
                    RasterTesting.tight((RgbaPicture) converted.picture()));
        }
    }

    @Nested
    @DisplayName("pyramids")
    class Pyramids {
        record Level(int width, int height, long subfileType, int compression) {
        }

        static byte[] rgb(int width, int height, int seed) {
            var out = new byte[width * height * 3];
            for (var i = 0; i < out.length; i++) {
                out[i] = (byte) (i * 7 + seed * 31);
            }
            return out;
        }

        /** Writes a classic little-endian TIFF: the first level is the page, the others its levels. */
        static byte[] pyramid(List<Level> levels, boolean subIfds) {
            var data = new ByteArrayOutputStream();
            var dataOffsets = new long[levels.size()];
            for (var i = 0; i < levels.size(); i++) {
                dataOffsets[i] = 8 + data.size();
                var level = levels.get(i);
                data.writeBytes(rgb(level.width(), level.height(), i));
            }
            var ifdStart = 8 + data.size() + (data.size() & 1);
            var sizes = new int[levels.size()];
            for (var i = 0; i < levels.size(); i++) {
                var entries = 11 + (i == 0 && subIfds ? 1 : 0);
                var overflow = 6 + (i == 0 && subIfds && levels.size() > 2 ? 4 * (levels.size() - 1) : 0);
                sizes[i] = 2 + entries * 12 + 4 + overflow;
            }
            var offsets = new long[levels.size()];
            var position = (long) ifdStart;
            for (var i = 0; i < levels.size(); i++) {
                offsets[i] = position;
                position += sizes[i];
            }
            var out = ByteBuffer.allocate((int) position).order(ByteOrder.LITTLE_ENDIAN);
            out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) offsets[0]);
            out.put(data.toByteArray());
            for (var i = 0; i < levels.size(); i++) {
                var level = levels.get(i);
                var at = (int) offsets[i];
                var withSubs = i == 0 && subIfds;
                var entries = 11 + (withSubs ? 1 : 0);
                var overflowAt = at + 2 + entries * 12 + 4;
                out.position(at);
                out.putShort((short) entries);
                entry(out, 254, 4, 1, level.subfileType());
                entry(out, 256, 4, 1, level.width());
                entry(out, 257, 4, 1, level.height());
                entry(out, 258, 3, 3, overflowAt);
                entry(out, 259, 3, 1, level.compression());
                entry(out, 262, 3, 1, 2);
                entry(out, 273, 4, 1, dataOffsets[i]);
                entry(out, 277, 3, 1, 3);
                entry(out, 278, 4, 1, level.height());
                entry(out, 279, 4, 1, (long) level.width() * level.height() * 3);
                entry(out, 284, 3, 1, 1);
                if (withSubs) {
                    var count = levels.size() - 1;
                    entry(out, 330, 4, count, count == 1 ? offsets[1] : overflowAt + 6);
                }
                long next;
                if (subIfds) {
                    next = 0;
                } else {
                    next = i + 1 < levels.size() ? offsets[i + 1] : 0;
                }
                out.putInt((int) next);
                out.position(overflowAt);
                out.putShort((short) 8).putShort((short) 8).putShort((short) 8);
                if (withSubs && levels.size() > 2) {
                    for (var j = 1; j < levels.size(); j++) {
                        out.putInt((int) offsets[j]);
                    }
                }
            }
            return out.array();
        }

        static void entry(ByteBuffer out, int tag, int type, int count, long value) {
            out.putShort((short) tag).putShort((short) type).putInt(count);
            if (type == 3 && count == 1) {
                out.putShort((short) value).putShort((short) 0);
            } else {
                out.putInt((int) value);
            }
        }

        static List<Level> levels() {
            return List.of(new Level(400, 300, 0, 1), new Level(200, 150, 1, 1), new Level(100, 75, 1, 1),
                    new Level(50, 38, 1, 1));
        }

        static Stream<Arguments> layouts() {
            return Stream.of(Arguments.of(true), Arguments.of(false));
        }

        static DecodedImage decodeAt(byte[] file, int minWidth, int minHeight) {
            return decode(file, ImageDecoderOptions.builder().minSize(minWidth, minHeight).build());
        }

        @ParameterizedTest(name = "SubIFDs {0}")
        @MethodSource("layouts")
        @DisplayName("the size hint picks the smallest level that covers it")
        void picksLevel(boolean subIfds) {
            var file = pyramid(levels(), subIfds);
            var expectations = new int[][]{{0, 0, 400}, {1, 1, 50}, {50, 38, 50}, {51, 38, 100}, {100, 70, 100},
                    {150, 0, 200}, {200, 150, 200}, {201, 150, 400}, {800, 600, 400}};
            for (var expectation : expectations) {
                var image = decodeAt(file, expectation[0], expectation[1]);
                assertEquals(expectation[2], image.picture().width(), expectation[0] + "x" + expectation[1]);
                assertEquals(400, image.sourceWidth());
                assertEquals(300, image.sourceHeight());
            }
        }

        @ParameterizedTest(name = "SubIFDs {0}")
        @MethodSource("layouts")
        @DisplayName("a level decodes to its own pixels")
        void levelPixels(boolean subIfds) {
            var file = pyramid(levels(), subIfds);
            var image = decodeAt(file, 100, 75);
            var expected = rgb(100, 75, 2);
            var pixels = RasterTesting.tight((RgbaPicture) image.picture());
            for (var i = 0; i < 100 * 75; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(expected[i * 3 + c], pixels[i * 4 + c]);
                }
            }
        }

        @Test
        @DisplayName("an undecodable level gives way to the next larger one")
        void undecodableLevel() {
            var file = pyramid(List.of(new Level(400, 300, 0, 1), new Level(200, 150, 1, 1), new Level(100, 75, 1, 32908)),
                    true);
            assertEquals(200, decodeAt(file, 60, 40).picture().width());
        }

        @Test
        @DisplayName("directories that are not reduced-resolution or have another shape are not levels")
        void notLevels() {
            var file = pyramid(List.of(new Level(400, 300, 0, 1), new Level(200, 150, 0, 1), new Level(100, 100, 1, 1),
                    new Level(100, 75, 5, 1)), true);
            assertEquals(400, decodeAt(file, 10, 10).picture().width());
            var chain = pyramid(List.of(new Level(400, 300, 0, 1), new Level(200, 150, 0, 1)), false);
            assertEquals(400, decodeAt(chain, 10, 10).picture().width());
            try (var pages = TiffDecoder.openPages(MediaSource.of(chain))) {
                assertEquals(2, pages.pageCount());
            }
        }
    }
}
