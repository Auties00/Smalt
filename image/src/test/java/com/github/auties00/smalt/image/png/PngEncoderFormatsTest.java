package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the color types and bit depths the PNG encoder chooses (grayscale and indexed color of 1 to 8
 * bits, grayscale with alpha, 16-bit samples, tRNS keys) and Adam7 interlacing: each case is read back
 * exactly by this module's decoder, by libpng (smalt-pngdump), by the JDK's ImageIO reader and by ffmpeg,
 * whichever are available, and the interlaced scanline layout is checked byte for byte against a reference
 * built here from the specification.
 */
class PngEncoderFormatsTest {
    private static final ImageDecoderOptions HIGH = ImageDecoderOptions.builder().highBitDepth(true).build();

    /** A picture, the format the encoder must choose for it, and whether a tRNS chunk is expected. */
    record Case(String name, Supplier<Picture> picture, int colorType, int bitDepth, boolean trns) {
        @Override
        public String toString() {
            return name;
        }
    }

    static RgbaPicture narrow(int width, int height, boolean alpha, PixelFunction function) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var color = function.at(x, y);
                var i = (y * width + x) * 4;
                pixels[i] = (byte) color;
                pixels[i + 1] = (byte) (color >>> 8);
                pixels[i + 2] = (byte) (color >>> 16);
                pixels[i + 3] = (byte) (color >>> 24);
            }
        }
        return picture;
    }

    static Rgba16Picture wide(int width, int height, boolean alpha, WidePixelFunction function) {
        var picture = Rgba16Picture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var color = function.at(x, y);
                var i = (y * width + x) * 4;
                pixels[i] = (short) (color >>> 48);
                pixels[i + 1] = (short) (color >>> 32);
                pixels[i + 2] = (short) (color >>> 16);
                pixels[i + 3] = (short) color;
            }
        }
        return picture;
    }

    interface PixelFunction {
        int at(int x, int y);
    }

    interface WidePixelFunction {
        long at(int x, int y);
    }

    static int gray(int level, int alpha) {
        return alpha << 24 | level * 0x01_0101;
    }

    static int hash(int x, int y, int seed) {
        var h = x * 0x9E3779B1 ^ y * 0x85EBCA77 ^ seed * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        return h ^ h >>> 13;
    }

    static int photo(int x, int y) {
        return 0xFF00_0000 | ((x * 3 + (hash(x, y, 1) & 7)) & 0xFF) | ((y * 5 + (hash(x, y, 2) & 7)) & 0xFF) << 8
                | (((x ^ y) + (hash(x, y, 3) & 3)) & 0xFF) << 16;
    }

    static long wideColor(int r, int g, int b, int a) {
        return (long) r << 48 | (long) g << 32 | (long) b << 16 | a;
    }

    static List<Case> cases(int width, int height) {
        var list = new ArrayList<Case>();
        list.add(new Case("gray 1-bit", () -> narrow(width, height, false,
                (x, y) -> gray((hash(x, y, 4) & 1) * 255, 255)), PngHeader.GRAY, 1, false));
        list.add(new Case("gray 2-bit", () -> narrow(width, height, false,
                (x, y) -> gray((hash(x, y, 5) & 3) * 85, 255)), PngHeader.GRAY, 2, false));
        list.add(new Case("gray 4-bit", () -> narrow(width, height, false,
                (x, y) -> gray(((x + y * 3) & 15) * 17, 255)), PngHeader.GRAY, 4, false));
        list.add(new Case("gray 8-bit", () -> narrow(width, height, false,
                (x, y) -> gray((x * 7 + y * 3 + (hash(x, y, 6) & 15)) & 0xFF, 255)), PngHeader.GRAY, 8, false));
        list.add(new Case("gray 1-bit with a tRNS key", () -> narrow(width, height, true,
                (x, y) -> (hash(x, y, 7) & 3) == 0 ? 0 : gray(255, 255)), PngHeader.GRAY, 1, true));
        list.add(new Case("gray 8-bit with a tRNS key", () -> narrow(width, height, true,
                (x, y) -> (hash(x, y, 8) & 7) == 0 ? gray(50, 0) : gray(60 + (x * 7 + y * 11 + (hash(x, y, 9) & 63)) % 190, 255)),
                PngHeader.GRAY, 8, true));
        list.add(new Case("gray with alpha", () -> narrow(width, height, true,
                (x, y) -> gray((x * 5 + y) & 0xFF, (x * 3 + y * 7 + (hash(x, y, 10) & 31)) & 0xFF)), PngHeader.GRAY_ALPHA, 8,
                false));
        list.add(new Case("indexed 1-bit", () -> narrow(width, height, false,
                (x, y) -> (hash(x, y, 11) & 1) == 0 ? 0xFF2040A0 : 0xFFA04020), PngHeader.PALETTE, 1, false));
        list.add(new Case("indexed 2-bit from three gray levels", () -> narrow(width, height, false,
                (x, y) -> gray(new int[]{0, 100, 200}[(hash(x, y, 12) & 0xFFFF) % 3], 255)), PngHeader.PALETTE, 2, false));
        list.add(new Case("indexed 4-bit", () -> narrow(width, height, false,
                (x, y) -> 0xFF00_0000 | ((hash(x, y, 13) & 15) * 0x10_3517)), PngHeader.PALETTE, 4, false));
        list.add(new Case("indexed 8-bit with translucent entries", () -> narrow(width, height, true,
                (x, y) -> {
                    var i = (hash(x, y, 14) & 0xFFFF) % 200;
                    return (i < 20 ? i * 12 : 255) << 24 | i * 0x01_0305 + 7;
                }), PngHeader.PALETTE, 8, true));
        list.add(new Case("truecolor with a tRNS key", () -> narrow(width, height, true,
                (x, y) -> (hash(x, y, 15) & 7) == 0 ? 0x00FE_01FE : photo(x, y) & 0xFF7F_7F7F), PngHeader.RGB, 8, true));
        list.add(new Case("truecolor with alpha for two transparent colors", () -> narrow(width, height, true,
                (x, y) -> (hash(x, y, 16) & 7) == 0 ? (x & 1) * 0x0000_00FF : photo(x, y) & 0xFF7F_7F7F), PngHeader.RGBA, 8,
                false));
        list.add(new Case("truecolor with alpha when an opaque pixel has the transparent color", () -> narrow(width,
                height, true, (x, y) -> x == 0 && y == 0 ? 0xFF00_0000 : (hash(x, y, 17) & 7) == 0 ? 0 : photo(x, y) | 1),
                PngHeader.RGBA, 8, false));
        list.add(new Case("truecolor ignoring undeclared alpha", () -> narrow(width, height, false,
                (x, y) -> photo(x, y) & ~(hash(x, y, 18) << 24)), PngHeader.RGB, 8, false));
        list.add(new Case("16-bit gray", () -> wide(width, height, false,
                (x, y) -> {
                    var v = (x * 977 + y * 3011 + (hash(x, y, 19) & 0xFF)) & 0xFFFF;
                    return wideColor(v, v, v, 0xFFFF);
                }), PngHeader.GRAY, 16, false));
        list.add(new Case("16-bit gray with a tRNS key", () -> wide(width, height, true,
                (x, y) -> {
                    var v = 1 + (x * 977 + y * 3011 + (hash(x, y, 20) & 0xFF)) % 65000;
                    return (hash(x, y, 21) & 7) == 0 ? wideColor(0, 0, 0, 0) : wideColor(v, v, v, 0xFFFF);
                }), PngHeader.GRAY, 16, true));
        list.add(new Case("16-bit gray with alpha", () -> wide(width, height, true,
                (x, y) -> {
                    var v = (x * 977 + y * 3011) & 0xFFFF;
                    return wideColor(v, v, v, (x * 5003 + y * 17 + hash(x, y, 22)) & 0xFFFF);
                }), PngHeader.GRAY_ALPHA, 16, false));
        list.add(new Case("16-bit truecolor", () -> wide(width, height, false,
                (x, y) -> wideColor(x * 1031 & 0xFFFF, y * 2053 & 0xFFFF, hash(x, y, 23) & 0xFFFF, 0xFFFF)), PngHeader.RGB, 16,
                false));
        list.add(new Case("16-bit truecolor with a tRNS key", () -> wide(width, height, true,
                (x, y) -> (hash(x, y, 24) & 7) == 0 ? wideColor(0x1234, 0x5678, 0x9ABC, 0)
                        : wideColor(x * 1031 & 0xFFFF, y * 2053 & 0xFFFF, hash(x, y, 25) & 0xFFF0, 0xFFFF)), PngHeader.RGB, 16, true));
        list.add(new Case("16-bit truecolor with alpha", () -> wide(width, height, true,
                (x, y) -> wideColor(x * 1031 & 0xFFFF, y * 2053 & 0xFFFF, hash(x, y, 26) & 0xFFFF, hash(x, y, 27) & 0xFFFF)),
                PngHeader.RGBA, 16, false));
        return list;
    }

    static Stream<Arguments> formats() {
        var list = new ArrayList<Arguments>();
        for (var interlaced : new boolean[]{false, true}) {
            for (var c : cases(61, 37)) {
                list.add(Arguments.of(c, interlaced));
            }
        }
        return list.stream();
    }

    /** The tight RGBA16 samples a reader must return: 8-bit samples times 257, ignored alpha as opaque. */
    static int[] expected16(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var out = new int[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 4; c++) {
                    int v;
                    if (picture instanceof RgbaPicture rgba) {
                        v = (rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF) * 257;
                    } else {
                        var deep = (Rgba16Picture) picture;
                        v = deep.pixels()[y * deep.stride() + x * 4 + c] & 0xFFFF;
                    }
                    if (c == 3 && !picture.hasAlpha()) {
                        v = 0xFFFF;
                    }
                    out[(y * width + x) * 4 + c] = v;
                }
            }
        }
        return out;
    }

    static void assertSamples(int[] expected, int[] actual, int width, boolean alpha, String what) {
        assertEquals(expected.length, actual.length, what + ": sample count");
        for (var i = 0; i < expected.length; i++) {
            if (i % 4 == 3 && !alpha) {
                continue;
            }
            if (expected[i] != actual[i]) {
                var pixel = i / 4;
                fail(what + ": first difference at pixel (" + pixel % width + "," + pixel / width + ") channel " + i % 4
                        + ": expected " + expected[i] + " got " + actual[i]);
            }
        }
    }

    static int[] fromBytes8(byte[] data) {
        var out = new int[data.length];
        for (var i = 0; i < data.length; i++) {
            out[i] = (data[i] & 0xFF) * 257;
        }
        return out;
    }

    static int[] fromBytes16(byte[] data, boolean bigEndian) {
        var out = new int[data.length / 2];
        for (var i = 0; i < out.length; i++) {
            var hi = data[i * 2 + (bigEndian ? 0 : 1)] & 0xFF;
            var lo = data[i * 2 + (bigEndian ? 1 : 0)] & 0xFF;
            out[i] = hi << 8 | lo;
        }
        return out;
    }

    static int[] decoded16(Picture picture) {
        return expected16(picture);
    }

    /** Checks a datastream against every available reader. */
    static void assertReadersAgree(byte[] png, Picture source, String what) {
        var expected = expected16(source);
        var width = source.width();
        var depth = png[24];
        var colorType = png[25];
        var decoded = PngDecoder.decode(MediaSource.of(png), HIGH).picture();
        if (depth == 16) {
            assertInstanceOf(Rgba16Picture.class, decoded, what);
        } else {
            assertInstanceOf(RgbaPicture.class, decoded, what);
        }
        assertSamples(expected, decoded16(decoded), width, true, what + " (Smalt)");
        assertEquals(source.hasAlpha() && hasTransparency(source), decoded.hasAlpha(), what + ": hasAlpha");
        if (PngOracles.hasLibpng()) {
            var info = PngOracles.libpngInfo(png);
            assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), what + ": libpng warns " + info);
            var pixels = PngOracles.libpngPixels(png);
            assertSamples(expected, depth == 16 ? fromBytes16(pixels, true) : fromBytes8(pixels), width, true,
                    what + " (libpng)");
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var keyedLowGray = colorType == PngHeader.GRAY && depth < 8;
            var frames = depth == 16 ? fromBytes16(PngOracles.ffmpeg(png, "rgba64le"), false)
                    : fromBytes8(PngOracles.ffmpeg(png, "rgba"));
            assertSamples(expected, frames, width, !keyedLowGray, what + " (ffmpeg)");
        }
    }

    static boolean hasTransparency(Picture picture) {
        var samples = expected16(picture);
        for (var i = 3; i < samples.length; i += 4) {
            if (samples[i] != 0xFFFF) {
                return true;
            }
        }
        return false;
    }

    record Chunk(String type, byte[] data) {
    }

    static List<Chunk> chunks(byte[] png) {
        var list = new ArrayList<Chunk>();
        var offset = 8;
        while (offset + 8 <= png.length) {
            var length = (png[offset] & 0xFF) << 24 | (png[offset + 1] & 0xFF) << 16 | (png[offset + 2] & 0xFF) << 8
                    | png[offset + 3] & 0xFF;
            var type = new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            var data = java.util.Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
            list.add(new Chunk(type, data));
            offset += 12 + length;
        }
        return list;
    }

    static List<String> types(byte[] png) {
        return chunks(png).stream().map(Chunk::type).toList();
    }

    static byte[] imageData(byte[] png) {
        var idat = new ByteArrayOutputStream();
        for (var chunk : chunks(png)) {
            if (chunk.type().equals("IDAT")) {
                idat.writeBytes(chunk.data());
            }
        }
        var inflater = new Inflater();
        inflater.setInput(idat.toByteArray());
        var out = new ByteArrayOutputStream();
        var buffer = new byte[65536];
        try {
            while (!inflater.finished()) {
                var count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    fail("truncated zlib stream");
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException exception) {
            fail(exception);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0}, interlaced {1}")
    @MethodSource("formats")
    @DisplayName("chooses the expected format and every reader returns the source samples")
    void formats(Case c, boolean interlaced) {
        var picture = c.picture().get();
        var png = PngEncoder.encode(picture, PngEncoderOptions.builder().interlaced(interlaced).build());
        assertEquals(c.colorType(), png[25], c + ": color type");
        assertEquals(c.bitDepth(), png[24], c + ": bit depth");
        assertEquals(interlaced ? 1 : 0, png[28], c + ": interlace method");
        var types = types(png);
        assertEquals(c.trns(), types.contains("tRNS"), c + ": tRNS " + types);
        assertEquals(c.colorType() == PngHeader.PALETTE, types.contains("PLTE"), c + ": PLTE " + types);
        assertReadersAgree(png, picture, c + (interlaced ? " interlaced" : ""));
    }

    @Test
    @DisplayName("ImageIO reads every format and interlacing with the source samples and the declared chunks")
    void imageIo() {
        Skip.unless(PngOracles.hasImageIo(), "env:jvm", "no JVM to run ImageIO in");
        var pngs = new ArrayList<byte[]>();
        var pictures = new ArrayList<Picture>();
        for (var interlaced : new boolean[]{false, true}) {
            for (var c : cases(29, 23)) {
                var picture = c.picture().get();
                pictures.add(picture);
                pngs.add(PngEncoder.encode(picture, PngEncoderOptions.builder().interlaced(interlaced).build()));
            }
        }
        var results = PngOracles.imageIo(pngs);
        for (var i = 0; i < pngs.size(); i++) {
            var png = pngs.get(i);
            var result = results.get(i);
            var what = "file " + i + " (type " + png[25] + ", depth " + png[24] + ")";
            var alphaChannel = png[25] == PngHeader.RGBA || png[25] == PngHeader.GRAY_ALPHA || png[25] == PngHeader.PALETTE;
            assertSamples(expected16(pictures.get(i)), fromBytes16(result.rgba16(), true), 29, alphaChannel, what);
            assertTrue(result.metadata().contains("bitDepth=" + png[24]), what + ": " + result.metadata());
            assertTrue(result.metadata().contains("interlaceMethod=" + (png[28] == 1 ? "adam7" : "none")), what);
            assertEquals(types(png).contains("tRNS"), result.metadata().contains("tRNS"), what);
        }
    }

    static Stream<Arguments> interlaceSizes() {
        var list = new ArrayList<Arguments>();
        for (var size : new int[][]{{1, 1}, {1, 9}, {9, 1}, {2, 3}, {3, 2}, {4, 4}, {5, 5}, {7, 7}, {8, 8}, {9, 9}, {17, 3}}) {
            list.add(Arguments.of(size[0], size[1]));
        }
        return list.stream();
    }

    @ParameterizedTest(name = "{0}x{1}")
    @MethodSource("interlaceSizes")
    @DisplayName("interlaces tiny pictures whose Adam7 passes are partly empty")
    void tinyInterlaced(int width, int height) {
        for (var c : cases(width, height)) {
            var picture = c.picture().get();
            var png = PngEncoder.encode(picture, PngEncoderOptions.builder().interlaced(true).build());
            assertEquals(1, png[28]);
            assertReadersAgree(png, picture, c + " " + width + "x" + height);
        }
    }

    /** Builds the unfiltered Adam7 image data of an RGBA picture, pass by pass (PNG 3rd edition 8.2). */
    static byte[] adam7Reference(RgbaPicture picture, int bytesPerPixel, PixelPacker packer) {
        int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
        var out = new ByteArrayOutputStream();
        for (var pass : passes) {
            var xs = new ArrayList<Integer>();
            for (var x = pass[0]; x < picture.width(); x += pass[2]) {
                xs.add(x);
            }
            if (xs.isEmpty()) {
                continue;
            }
            for (var y = pass[1]; y < picture.height(); y += pass[3]) {
                out.write(0);
                out.writeBytes(packer.pack(picture, y, xs));
            }
        }
        return out.toByteArray();
    }

    interface PixelPacker {
        byte[] pack(RgbaPicture picture, int y, List<Integer> xs);
    }

    @Test
    @DisplayName("stores the Adam7 passes in order, each scanline packed from the pass's pixels")
    void adam7Layout() {
        var options = PngEncoderOptions.builder().interlaced(true).filter(PngEncoderOptions.Filter.NONE)
                .compressionLevel(0).build();
        var rgba = narrow(13, 11, true, (x, y) -> photo(x, y) & 0x7FFF_FFFF | (x * 19 + y) << 24);
        assertEquals(PngHeader.RGBA, PngEncoder.encode(rgba, options)[25]);
        assertArrayEquals(adam7Reference(rgba, 4, (picture, y, xs) -> {
            var row = new byte[xs.size() * 4];
            for (var i = 0; i < xs.size(); i++) {
                System.arraycopy(picture.pixels(), y * picture.stride() + xs.get(i) * 4, row, i * 4, 4);
            }
            return row;
        }), imageData(PngEncoder.encode(rgba, options)));
        var bilevel = narrow(13, 11, false, (x, y) -> gray((hash(x, y, 30) & 1) * 255, 255));
        assertEquals(1, PngEncoder.encode(bilevel, options)[24]);
        assertArrayEquals(adam7Reference(bilevel, 1, (picture, y, xs) -> {
            var row = new byte[(xs.size() + 7) / 8];
            for (var i = 0; i < xs.size(); i++) {
                if (picture.pixels()[y * picture.stride() + xs.get(i) * 4] != 0) {
                    row[i / 8] |= (byte) (0x80 >>> (i % 8));
                }
            }
            return row;
        }), imageData(PngEncoder.encode(bilevel, options)));
    }

    @Nested
    @DisplayName("16-bit samples")
    class Wide {
        @Test
        @DisplayName("stores samples big-endian, unchanged")
        void bigEndian() {
            var picture = wide(3, 2, true, (x, y) -> wideColor(0x0102 + x, 0x0304 + y, 0xF0E0, 0x8001 + x * y));
            var data = imageData(PngEncoder.encode(picture, PngEncoderOptions.builder()
                    .filter(PngEncoderOptions.Filter.NONE).compressionLevel(0).build()));
            assertEquals(2 * (1 + 3 * 8), data.length);
            assertArrayEquals(new byte[]{0, 0x01, 0x02, 0x03, 0x04, (byte) 0xF0, (byte) 0xE0, (byte) 0x80, 0x01},
                    java.util.Arrays.copyOf(data, 9));
        }

        @Test
        @DisplayName("round-trips random 16-bit pictures bit-exactly through a high-bit-depth decode")
        void roundTrip() {
            var random = new Random(42);
            for (var round = 0; round < 40; round++) {
                var width = 1 + random.nextInt(90);
                var height = 1 + random.nextInt(40);
                var alpha = random.nextBoolean();
                var picture = Rgba16Picture.allocate(width, height, alpha);
                for (var i = 0; i < picture.pixels().length; i++) {
                    picture.pixels()[i] = (short) random.nextInt();
                }
                var options = PngEncoderOptions.builder().interlaced(random.nextBoolean())
                        .filter(PngEncoderOptions.Filter.values()[random.nextInt(6)])
                        .compressionLevel(random.nextInt(10)).build();
                var decoded = (Rgba16Picture) PngDecoder.decode(MediaSource.of(PngEncoder.encode(picture, options)), HIGH)
                        .picture();
                assertSamples(expected16(picture), expected16(decoded), width, true, "round " + round);
                assertEquals(alpha, decoded.hasAlpha());
            }
        }

        @Test
        @DisplayName("reduces to the 8-bit decode the decoder gives without high bit depth")
        void narrowDecode() {
            var picture = wide(40, 20, true, (x, y) -> wideColor(x * 1500, y * 3000, 777 * x, 65535 - x * y * 50));
            var png = PngEncoder.encode(picture);
            var narrow = (RgbaPicture) PngDecoder.decode(MediaSource.of(png)).picture();
            assertArrayEquals(com.github.auties00.smalt.testkit.image.RasterTesting.tight(
                    com.github.auties00.smalt.image.ops.ImageOps.toEightBit(picture)),
                    com.github.auties00.smalt.testkit.image.RasterTesting.tight(narrow));
        }
    }

    @Test
    @DisplayName("keeps the scanline filters off for indexed color and depths below 8 under the adaptive selection")
    void lowDepthUnfiltered() {
        for (var c : cases(30, 12)) {
            var png = PngEncoder.encode(c.picture().get(), PngEncoderOptions.builder().compressionLevel(0).build());
            if (png[25] != PngHeader.PALETTE && png[24] >= 8) {
                continue;
            }
            var data = imageData(png);
            var rowBytes = (30 * png[24] + 7) / 8;
            for (var y = 0; y < 12; y++) {
                assertEquals(0, data[y * (rowBytes + 1)], c + " row " + y);
            }
        }
    }

    @Test
    @DisplayName("round-trips random pictures of every kind with random options through every reader")
    void fuzz() {
        var random = new Random(7);
        for (var round = 0; round < 60; round++) {
            var width = 1 + random.nextInt(70);
            var height = 1 + random.nextInt(30);
            var all = cases(width, height);
            var c = all.get(random.nextInt(all.size()));
            var picture = c.picture().get();
            var options = PngEncoderOptions.builder().interlaced(random.nextBoolean())
                    .filter(PngEncoderOptions.Filter.values()[random.nextInt(6)])
                    .compressionLevel(random.nextInt(10)).build();
            var png = PngEncoder.encode(picture, options);
            assertReadersAgree(png, picture, "round " + round + " " + c + " " + width + "x" + height + " " + options);
        }
    }

    @Test
    @DisplayName("a truncated datastream of every format decodes exactly or is malformed, a corrupted one fails cleanly")
    void truncated() {
        var random = new Random(11);
        for (var interlaced : new boolean[]{false, true}) {
            for (var c : cases(23, 17)) {
                var picture = c.picture().get();
                var png = PngEncoder.encode(picture, PngEncoderOptions.builder().interlaced(interlaced)
                        .significantBits(7).gamma(0.45).build());
                var expected = expected16(picture);
                for (var round = 0; round < 12; round++) {
                    var damaged = round % 2 == 0 ? java.util.Arrays.copyOf(png, random.nextInt(png.length))
                            : png.clone();
                    if (round % 2 == 1) {
                        damaged[33 + random.nextInt(png.length - 33)] ^= (byte) (1 + random.nextInt(255));
                    }
                    try {
                        var decoded = PngDecoder.decode(MediaSource.of(damaged), HIGH).picture();
                        assertEquals(23, decoded.width());
                        if (round % 2 == 0) {
                            // a truncation that leaves every chunk up to the image data intact loses nothing
                            assertSamples(expected, decoded16(decoded), 23, true, c + " round " + round);
                        }
                    } catch (com.github.auties00.smalt.MediaException.Malformed
                             | com.github.auties00.smalt.MediaException.Unsupported expectedFailure) {
                        // a flipped chunk type can name an unknown critical chunk, which is unsupported
                    }
                }
            }
        }
    }

    /** Copies a picture into rows padded past its width, with the padding filled with noise. */
    static Picture padded(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var random = new Random(width * 31L + height);
        if (picture instanceof RgbaPicture rgba) {
            var stride = width * 4 + 20;
            var pixels = new byte[stride * height];
            random.nextBytes(pixels);
            for (var y = 0; y < height; y++) {
                System.arraycopy(rgba.pixels(), y * rgba.stride(), pixels, y * stride, width * 4);
            }
            return RgbaPicture.of(width, height, stride, pixels, rgba.hasAlpha());
        }
        var deep = (Rgba16Picture) picture;
        var stride = width * 4 + 12;
        var pixels = new short[stride * height];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (short) random.nextInt();
        }
        for (var y = 0; y < height; y++) {
            System.arraycopy(deep.pixels(), y * deep.stride(), pixels, y * stride, width * 4);
        }
        return Rgba16Picture.of(width, height, stride, pixels, deep.hasAlpha());
    }

    @Test
    @DisplayName("encodes a picture whose rows are padded to the same bytes as its tight copy, in every format")
    void paddedRows() {
        for (var interlaced : new boolean[]{false, true}) {
            var options = PngEncoderOptions.builder().interlaced(interlaced).build();
            for (var c : cases(29, 13)) {
                var picture = c.picture().get();
                var expected = PngEncoder.encode(picture, options);
                var strided = padded(picture);
                assertArrayEquals(expected, PngEncoder.encode(strided, options), c + " interlaced " + interlaced);
            }
        }
    }

    @Test
    @DisplayName("compresses bands of interlaced scanlines independently, matching a single-band decode")
    void largeInterlaced() {
        var picture = narrow(1300, 700, true, (x, y) -> photo(x, y) & 0x00FF_FFFF | ((x + y) & 0xFF) << 24);
        var png = PngEncoder.encode(picture, PngEncoderOptions.builder().interlaced(true).build());
        assertReadersAgree(png, picture, "large interlaced");
        var wide = wide(500, 400, false, (x, y) -> wideColor(x * 131, y * 163, (x * y) & 0xFFFF, 0xFFFF));
        assertReadersAgree(PngEncoder.encode(wide, PngEncoderOptions.builder().interlaced(true).build()), wide,
                "large 16-bit interlaced");
    }

    @Test
    @DisplayName("primes each band of packed scanlines with the rows before it, without interlacing")
    void largePacked() {
        var wide = wide(500, 400, false, (x, y) -> wideColor(x * 131, y * 163, (x * y) & 0xFFFF, 0xFFFF));
        var png = PngEncoder.encode(wide);
        assertEquals(16, png[24]);
        assertReadersAgree(png, wide, "large 16-bit");
        var gray = narrow(1200, 1000, false, (x, y) -> gray((x * 3 + y * 7 + (hash(x, y, 40) & 15)) & 0xFF, 255));
        png = PngEncoder.encode(gray);
        assertEquals(PngHeader.GRAY, png[25]);
        assertReadersAgree(png, gray, "large gray");
    }

    static Yuv16Picture planar(int width, int height, ChromaFormat format, int bitDepth, boolean alpha,
                               ColorInfo color, long seed) {
        var picture = Yuv16Picture.allocate(width, height, format, bitDepth, alpha, color);
        var random = new Random(seed);
        var max = 1 << bitDepth;
        for (var plane : new short[][]{picture.y(), picture.u(), picture.v(), picture.alpha()}) {
            if (plane != null) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (short) random.nextInt(max);
                }
            }
        }
        return picture;
    }

    static Stream<Arguments> deepPlanar() {
        return Stream.of(
                Arguments.of(ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED, PngHeader.RGB),
                Arguments.of(ChromaFormat.YUV422, 12, true, ColorInfo.JPEG, PngHeader.RGBA),
                Arguments.of(ChromaFormat.YUV444, 16, false, ColorInfo.BT601_LIMITED, PngHeader.RGB),
                Arguments.of(ChromaFormat.MONOCHROME, 12, false, ColorInfo.JPEG, PngHeader.GRAY),
                Arguments.of(ChromaFormat.MONOCHROME, 10, true, ColorInfo.BT709_LIMITED, PngHeader.GRAY_ALPHA));
    }

    @ParameterizedTest(name = "{0} {1}-bit, alpha {2}")
    @MethodSource("deepPlanar")
    @DisplayName("writes a planar picture deeper than 8 bits with 16 bits per sample, every reader returning its RGBA16 conversion")
    void deepPlanar(ChromaFormat format, int bitDepth, boolean alpha, ColorInfo color, int colorType) {
        var yuv = planar(37, 23, format, bitDepth, alpha, color, bitDepth * 31L + format.ordinal());
        var converted = ImageOps.toRgba16(yuv);
        for (var interlaced : new boolean[]{false, true}) {
            var options = PngEncoderOptions.builder().interlaced(interlaced).build();
            var png = PngEncoder.encode(yuv, options);
            assertEquals(16, png[24], "bit depth");
            assertEquals(colorType, png[25], "color type");
            assertArrayEquals(PngEncoder.encode(converted, options), png, "same bytes as the RGBA16 conversion");
            assertReadersAgree(png, converted, format + " " + bitDepth + "-bit interlaced " + interlaced);
        }
    }

    @Test
    @DisplayName("writes an 8-bit planar picture with 8 bits per sample, as its RGBA conversion")
    void eightBitPlanar() {
        var yuv = planar(29, 17, ChromaFormat.YUV420, 8, true, ColorInfo.BT709_LIMITED, 8);
        var png = PngEncoder.encode(yuv);
        assertEquals(8, png[24]);
        assertArrayEquals(PngEncoder.encode(ImageOps.toRgba(yuv)), png);
    }

    @Test
    @DisplayName("applies the ICC profile constraints to a deep planar picture at 16 bits")
    void deepPlanarProfiles() {
        var mono = planar(21, 13, ChromaFormat.MONOCHROME, 12, false, ColorInfo.JPEG, 12);
        var rgbProfile = PngEncoderOptions.builder().colorProfile(ColorProfile.srgb()).build();
        var png = PngEncoder.encode(mono, rgbProfile);
        assertEquals(16, png[24]);
        assertEquals(PngHeader.RGB, png[25], "an RGB profile excludes grayscale");
        assertReadersAgree(png, ImageOps.toRgba16(mono), "monochrome under an RGB profile");
        var grayProfile = PngEncoderOptions.builder().colorProfile(ColorProfile.grayGamma22()).build();
        png = PngEncoder.encode(mono, grayProfile);
        assertEquals(16, png[24]);
        assertEquals(PngHeader.GRAY, png[25]);
        assertEquals(ColorProfile.grayGamma22(), PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow());
        var color = planar(21, 13, ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED, 10);
        assertThrows(IllegalArgumentException.class, () -> PngEncoder.encode(color, grayProfile));
    }

    @Test
    @DisplayName("writes a 10-bit PQ picture with its profile as a 16-bit cICP PNG read back with the same samples")
    void deepPlanarHdr() {
        var signal = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ);
        var yuv = planar(33, 19, ChromaFormat.YUV420, 10, false, signal, 2020);
        var profile = ColorProfile.of(signal);
        var png = PngEncoder.encode(yuv, PngEncoderOptions.builder().colorProfile(profile).build());
        assertEquals(16, png[24]);
        assertEquals(PngHeader.RGB, png[25]);
        var cicp = chunks(png).stream().filter(chunk -> chunk.type().equals("cICP")).findFirst().orElseThrow();
        assertArrayEquals(new byte[]{9, 16, 0, 1}, cicp.data());
        var decoded = PngDecoder.decode(MediaSource.of(png), HIGH);
        assertEquals(profile, decoded.colorProfile().orElseThrow());
        assertArrayEquals(ImageOps.toRgba16(yuv).pixels(), ((Rgba16Picture) decoded.picture()).pixels());
        assertReadersAgree(png, ImageOps.toRgba16(yuv), "10-bit PQ");
    }
}
