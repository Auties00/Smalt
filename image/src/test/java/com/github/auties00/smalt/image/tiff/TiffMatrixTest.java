package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential tests over every sample layout the decoder supports: an uncompressed base file is
 * built independently ({@link TiffFiles}), libtiff's tiffcp transcodes it to every compression,
 * predictor, strip and tile layout, planar configuration, byte order and BigTIFF, and the decoded
 * pixels must equal an independent model of the documented sample conversions exactly, at 8 and
 * at 16 bits.
 */
class TiffMatrixTest {
    private static final int WIDTH = 75;
    private static final int HEIGHT = 41;

    /** A base image and the RGBA16 values its pixels must decode to. */
    record Case(String name, byte[] base, long[] expected, boolean wide, List<List<String>> variants, byte[] planarBase) {
        Case(String name, byte[] base, long[] expected, boolean wide, List<List<String>> variants) {
            this(name, base, expected, wide, variants, null);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<List<String>> BYTE_VARIANTS = List.of(
            List.of("-c", "none"),
            List.of("-c", "lzw"),
            List.of("-c", "lzw:2"),
            List.of("-c", "zip"),
            List.of("-c", "zip:2"),
            List.of("-c", "packbits"),
            List.of("-c", "lzw", "-p", "separate"),
            List.of("-c", "zip:2", "-p", "separate", "-r", "7"),
            List.of("-c", "zip:2", "-t", "-w", "32", "-l", "16"),
            List.of("-c", "lzw:2", "-t", "-w", "16", "-l", "32", "-p", "separate"),
            List.of("-c", "lzw:2", "-r", "5"),
            List.of("-c", "none", "-B"),
            List.of("-c", "lzw:2", "-B"),
            List.of("-c", "zip:2", "-B", "-p", "separate"),
            List.of("-c", "zip", "-8"),
            List.of("-c", "lzw:2", "-8", "-B", "-t", "-w", "16", "-l", "16"));

    private static final List<List<String>> PACKED_VARIANTS = List.of(
            List.of("-c", "none"),
            List.of("-c", "lzw"),
            List.of("-c", "zip"),
            List.of("-c", "packbits"),
            List.of("-c", "lzw", "-t", "-w", "32", "-l", "16"),
            List.of("-c", "none", "-B"),
            List.of("-c", "zip", "-8", "-r", "3"));

    private static final List<List<String>> FLOAT_VARIANTS = List.of(
            List.of("-c", "none"),
            List.of("-c", "lzw"),
            List.of("-c", "lzw:3"),
            List.of("-c", "zip:3"),
            List.of("-c", "zip:3", "-B"),
            List.of("-c", "lzw:3", "-p", "separate", "-t", "-w", "16", "-l", "16"),
            List.of("-c", "zip:2", "-8"));

    private static final List<List<String>> BILEVEL_VARIANTS = List.of(
            List.of("-c", "none"),
            List.of("-c", "packbits"),
            List.of("-c", "lzw"),
            List.of("-c", "g3"),
            List.of("-c", "g3:1d"),
            List.of("-c", "g3:2d"),
            List.of("-c", "g3:2d:fill"),
            List.of("-c", "g3:fill"),
            List.of("-c", "g4"),
            List.of("-c", "g4", "-r", "8"),
            List.of("-c", "g3:2d", "-f", "lsb2msb"),
            List.of("-c", "g4", "-f", "lsb2msb"),
            List.of("-c", "g4", "-t", "-w", "32", "-l", "16"));

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Case>();
        for (var bits : new int[]{1, 2, 4, 8, 12, 16}) {
            for (var white : new boolean[]{false, true}) {
                var max = (1L << bits) - 1;
                var values = TiffFiles.pattern(WIDTH, HEIGHT, 1, max, bits * 31L + (white ? 1 : 0));
                var file = TiffFiles.of(WIDTH, HEIGHT, 1, bits).photometric(white ? 0 : 1)
                        .data(TiffFiles.pack(values, WIDTH, HEIGHT, 1, bits, true));
                var expected = new long[WIDTH * HEIGHT * 4];
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    var g = scale(values[i], bits);
                    if (white) {
                        g = 65535 - g;
                    }
                    set(expected, i, g, g, g, 65535);
                }
                var variants = bits == 1 ? BILEVEL_VARIANTS : bits % 8 == 0 ? BYTE_VARIANTS : PACKED_VARIANTS;
                cases.add(new Case("gray " + bits + "-bit" + (white ? " white-is-zero" : ""), file.bytes(), expected, bits > 8, variants));
            }
        }
        for (var bits : new int[]{2, 8, 10, 16}) {
            for (var alpha : new int[]{0, 1, 2}) {
                var samples = alpha == 0 ? 3 : 4;
                var max = (1L << bits) - 1;
                var values = TiffFiles.pattern(WIDTH, HEIGHT, samples, max, bits * 7L + alpha);
                if (alpha == 1) {
                    for (var i = 0; i < WIDTH * HEIGHT; i++) {
                        for (var c = 0; c < 3; c++) {
                            values[i * 4 + c] = Math.min(values[i * 4 + c], values[i * 4 + 3]);
                        }
                    }
                }
                var file = TiffFiles.of(WIDTH, HEIGHT, samples, bits).data(TiffFiles.pack(values, WIDTH, HEIGHT, samples, bits, true));
                var planar = TiffFiles.of(WIDTH, HEIGHT, samples, bits).planar()
                        .data(TiffFiles.packPlanar(values, WIDTH, HEIGHT, samples, bits, true));
                if (alpha > 0) {
                    file.extra(alpha);
                    planar.extra(alpha);
                }
                var expected = new long[WIDTH * HEIGHT * 4];
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    long r = scale(values[i * samples], bits);
                    long g = scale(values[i * samples + 1], bits);
                    long b = scale(values[i * samples + 2], bits);
                    long a = alpha == 0 ? 65535 : scale(values[i * samples + 3], bits);
                    if (alpha == 1) {
                        r = unpremultiply(r, a, bits);
                        g = unpremultiply(g, a, bits);
                        b = unpremultiply(b, a, bits);
                    }
                    set(expected, i, r, g, b, a);
                }
                var name = "rgb " + bits + "-bit" + (alpha == 1 ? " associated alpha" : alpha == 2 ? " unassociated alpha" : "");
                var variants = bits % 8 == 0 ? BYTE_VARIANTS : PACKED_VARIANTS;
                cases.add(new Case(name, file.bytes(), expected, bits > 8, variants, planar.bytes()));
            }
        }
        for (var bits : new int[]{1, 2, 4, 8, 16}) {
            var entries = 1 << bits;
            var map = new int[entries * 3];
            var random = new java.util.Random(bits);
            for (var i = 0; i < map.length; i++) {
                map[i] = bits == 16 ? random.nextInt(65536) : random.nextInt(256) * 257;
            }
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 1, entries - 1, 99 + bits);
            var file = TiffFiles.of(WIDTH, HEIGHT, 1, bits).photometric(3).colorMap(map)
                    .data(TiffFiles.pack(values, WIDTH, HEIGHT, 1, bits, true));
            var expected = new long[WIDTH * HEIGHT * 4];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                var index = (int) values[i];
                set(expected, i, map[index], map[entries + index], map[2 * entries + index], 65535);
            }
            cases.add(new Case("palette " + bits + "-bit", file.bytes(), expected, bits > 8,
                    bits % 8 == 0 ? BYTE_VARIANTS : PACKED_VARIANTS));
        }
        for (var bits : new int[]{8, 16}) {
            var max = (1L << bits) - 1;
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 4, max, 555 + bits);
            var file = TiffFiles.of(WIDTH, HEIGHT, 4, bits).photometric(5).data(TiffFiles.pack(values, WIDTH, HEIGHT, 4, bits, true));
            var planar = TiffFiles.of(WIDTH, HEIGHT, 4, bits).photometric(5).planar()
                    .data(TiffFiles.packPlanar(values, WIDTH, HEIGHT, 4, bits, true));
            var expected = new long[WIDTH * HEIGHT * 4];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                if (bits == 8) {
                    var k = 255 - values[i * 4 + 3];
                    set(expected, i, k * (255 - values[i * 4]) / 255 * 257, k * (255 - values[i * 4 + 1]) / 255 * 257,
                            k * (255 - values[i * 4 + 2]) / 255 * 257, 65535);
                } else {
                    var k = 65535 - values[i * 4 + 3];
                    set(expected, i, k * (65535 - values[i * 4]) / 65535, k * (65535 - values[i * 4 + 1]) / 65535,
                            k * (65535 - values[i * 4 + 2]) / 65535, 65535);
                }
            }
            cases.add(new Case("cmyk " + bits + "-bit", file.bytes(), expected, bits > 8, BYTE_VARIANTS, planar.bytes()));
        }
        for (var bits : new int[]{8, 16, 32}) {
            var max = (1L << bits) - 1;
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 1, max, 777 + bits);
            var stored = new long[values.length];
            var expected = new long[WIDTH * HEIGHT * 4];
            for (var i = 0; i < values.length; i++) {
                stored[i] = values[i] ^ (1L << (bits - 1));
                var g = scale(values[i], bits);
                set(expected, i, g, g, g, 65535);
            }
            var file = TiffFiles.of(WIDTH, HEIGHT, 1, bits).format(2).data(TiffFiles.pack(stored, WIDTH, HEIGHT, 1, bits, true));
            cases.add(new Case("signed " + bits + "-bit", file.bytes(), expected, bits > 8, bits == 32 ? FLOAT_VARIANTS.subList(0, 2) : BYTE_VARIANTS));
        }
        for (var bits : new int[]{32, 64}) {
            for (var samples : new int[]{1, 3}) {
                var values = TiffFiles.pattern(WIDTH, HEIGHT, samples, 1 << 20, 1234 + bits + samples);
                var raw = new long[values.length];
                var expected = new long[WIDTH * HEIGHT * 4];
                for (var i = 0; i < values.length; i++) {
                    var real = values[i] / (double) (1 << 20) * 1.2 - 0.1;
                    raw[i] = bits == 32 ? Float.floatToRawIntBits((float) real) & 0xFFFFFFFFL : Double.doubleToRawLongBits(real);
                    var effective = bits == 32 ? (double) (float) real : real;
                    values[i] = effective <= 0 ? 0 : effective >= 1 ? 65535 : Math.min(65535, (long) (effective * 65535 + 0.5));
                }
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    if (samples == 1) {
                        set(expected, i, values[i], values[i], values[i], 65535);
                    } else {
                        set(expected, i, values[i * 3], values[i * 3 + 1], values[i * 3 + 2], 65535);
                    }
                }
                var file = TiffFiles.of(WIDTH, HEIGHT, samples, bits).format(3).data(TiffFiles.pack(raw, WIDTH, HEIGHT, samples, bits, true));
                var planar = TiffFiles.of(WIDTH, HEIGHT, samples, bits).format(3).planar()
                        .data(TiffFiles.packPlanar(raw, WIDTH, HEIGHT, samples, bits, true));
                cases.add(new Case("float " + bits + "-bit x" + samples, file.bytes(), expected, true, FLOAT_VARIANTS,
                        samples > 1 ? planar.bytes() : null));
            }
        }
        var arguments = new ArrayList<Arguments>();
        for (var c : cases) {
            for (var variant : c.variants()) {
                // tiffcp separates the planes only of 8-bit samples, so planar bases cover the
                // others; it also scrambles planar 16-bit and wider samples cut into tiles (libtiff
                // reads its output as wrongly as Smalt), so that combination is left out
                var planarBase = variant.contains("separate") && !c.name().contains(" 8-bit") && c.planarBase() != null;
                if ((c.planarBase() != null || !variant.contains("separate")) && !(planarBase && variant.contains("-t"))) {
                    arguments.add(Arguments.of(c, String.join(" ", variant)));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    @DisplayName("tiffcp variants decode to the modeled pixels at 8 and 16 bits")
    void variant(Case c, String options) {
        LibTiff.assume("tiffcp");
        var arguments = new ArrayList<>(List.of(options.split(" ")));
        var base = c.base();
        if (arguments.contains("separate") && c.planarBase() != null) {
            // tiffcp separates planes only of 8-bit samples, so the base itself is made planar
            var at = arguments.indexOf("-p");
            arguments.remove(at);
            arguments.remove(at);
            base = c.planarBase();
        }
        var file = LibTiff.tiffcp(base, arguments);
        var wide = TiffDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().highBitDepth(true).build())
                .picture();
        var narrow = TiffDecoder.decode(MediaSource.of(file)).picture();
        assertInstanceOf(RgbaPicture.class, narrow);
        compare(c, options, narrow, false);
        if (c.wide()) {
            assertInstanceOf(Rgba16Picture.class, wide);
            compare(c, options, wide, true);
        } else {
            assertInstanceOf(RgbaPicture.class, wide);
        }
    }

    static Stream<Arguments> jpegVariants() {
        return Stream.of(
                Arguments.of(3, "-c jpeg -r 32"),
                Arguments.of(3, "-c jpeg:50 -r 48"),
                Arguments.of(3, "-c jpeg:r -r 8"),
                Arguments.of(3, "-c jpeg -r 16"),
                Arguments.of(3, "-c jpeg -t -w 32 -l 16"),
                Arguments.of(3, "-c jpeg:r -t -w 16 -l 32 -B"),
                Arguments.of(3, "-c jpeg -8 -r 48"),
                Arguments.of(1, "-c jpeg -r 8"),
                Arguments.of(1, "-c jpeg -t -w 16 -l 16"),
                Arguments.of(4, "-c jpeg -r 16"));
    }

    @ParameterizedTest(name = "{0} samples {1}")
    @MethodSource("jpegVariants")
    @DisplayName("JPEG-compressed strips and tiles decode exactly as libtiff with libjpeg-turbo decodes them")
    void jpeg(int samples, String options) {
        LibTiff.assume("tiffcp", "tiff2rgba");
        var values = TiffFiles.pattern(WIDTH, HEIGHT, samples, 255, 4242 + samples);
        var base = TiffFiles.of(WIDTH, HEIGHT, samples, 8).photometric(samples == 4 ? 5 : samples == 3 ? 2 : 1)
                .data(TiffFiles.pack(values, WIDTH, HEIGHT, samples, 8, true)).bytes();
        var file = LibTiff.tiffcp(base, List.of(options.split(" ")));
        var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
        var expected = LibTiff.rgba(file);
        assertNotNull(expected, "libtiff decodes " + options);
        com.github.auties00.smalt.testkit.image.RasterTesting.assertSameRgba(expected,
                com.github.auties00.smalt.testkit.image.RasterTesting.tight(picture), WIDTH, options);
    }

    private static void compare(Case c, String options, Picture picture, boolean sixteen) {
        assertEquals(WIDTH, picture.width());
        assertEquals(HEIGHT, picture.height());
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            var x = i % WIDTH;
            var y = i / WIDTH;
            for (var ch = 0; ch < 4; ch++) {
                var expected = c.expected()[i * 4 + ch];
                long actual;
                if (picture instanceof Rgba16Picture rgba) {
                    actual = rgba.pixels()[y * rgba.stride() + x * 4 + ch] & 0xFFFF;
                } else {
                    var rgba = (RgbaPicture) picture;
                    actual = rgba.pixels()[y * rgba.stride() + x * 4 + ch] & 0xFF;
                    expected = c.wide() ? (expected * 255 + 32767) / 65535 : expected / 257;
                }
                if (expected != actual) {
                    fail(c.name() + " [" + options + "]" + (sixteen ? " 16-bit" : " 8-bit") + ": pixel (" + x + "," + y + ") channel " + ch
                            + " expected " + expected + " got " + actual);
                }
            }
        }
    }

    /** Scales an n-bit value as the decoder documents: to 8 bits for n <= 8 (times 257), else to 16. */
    private static long scale(long value, int bits) {
        var max = (1L << bits) - 1;
        if (bits <= 8) {
            return (value * 255 + (max >> 1)) / max * 257;
        }
        return (value * 65535 + (max >> 1)) / max;
    }

    /** Divides a premultiplied value by its alpha at the work depth, as documented. */
    private static long unpremultiply(long value, long alpha, int bits) {
        if (bits <= 8) {
            var v = value / 257;
            var a = alpha / 257;
            return a == 0 ? 0 : Math.min(255, (v * 255 + a / 2) / a) * 257;
        }
        return alpha == 0 ? 0 : Math.min(65535, (value * 65535 + alpha / 2) / alpha);
    }

    private static void set(long[] expected, int pixel, long r, long g, long b, long a) {
        expected[pixel * 4] = r;
        expected[pixel * 4 + 1] = g;
        expected[pixel * 4 + 2] = b;
        expected[pixel * 4 + 3] = a;
    }
}
