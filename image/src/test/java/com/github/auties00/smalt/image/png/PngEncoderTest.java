package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the PNG encoder: color type selection, exact round trips through this module's decoder
 * and through ffmpeg for every filter and compression level, clean ffmpeg decodes, edge cases,
 * and the size comparison with ffmpeg's encoder.
 */
class PngEncoderTest {
    private static RgbaPicture photo(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        var random = new Random(width * 7L + height);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                pixels[i] = (byte) (x * 3 + random.nextInt(8));
                pixels[i + 1] = (byte) (y * 5 + random.nextInt(8));
                pixels[i + 2] = (byte) ((x ^ y) + random.nextInt(4));
                pixels[i + 3] = (byte) (alpha ? (x + y * 2) : 255);
            }
        }
        return picture;
    }

    private static RgbaPicture indexed(int width, int height, int colors, boolean translucent) {
        var picture = RgbaPicture.allocate(width, height, translucent);
        var pixels = picture.pixels();
        var random = new Random(colors);
        var table = new int[colors];
        for (var i = 0; i < colors; i++) {
            table[i] = random.nextInt() | (translucent ? 0 : 0xFF000000);
        }
        for (var p = 0; p < width * height; p++) {
            var color = table[(p / 3 + p % 7) % colors];
            pixels[p * 4] = (byte) color;
            pixels[p * 4 + 1] = (byte) (color >>> 8);
            pixels[p * 4 + 2] = (byte) (color >>> 16);
            pixels[p * 4 + 3] = (byte) (color >>> 24);
        }
        return picture;
    }

    private static RgbaPicture decode(byte[] png) {
        return (RgbaPicture) PngDecoder.decode(MediaSource.of(png), ImageDecoderOptions.defaults()).picture();
    }

    private static int colorType(byte[] png) {
        return png[25];
    }

    private static void assertRoundTrip(RgbaPicture source, byte[] png, String what) {
        RasterTesting.assertSameRgba(RasterTesting.tight(source), RasterTesting.tight(decode(png)), source.width(), what);
    }

    private static void assertFfmpegRoundTrip(RgbaPicture source, byte[] png, String what) {
        var path = RasterTesting.write("encoded-" + what.replaceAll("[^A-Za-z0-9]", "_") + ".png", png);
        Oracle.assertDecodesCleanly(path);
        RasterTesting.assertSameRgba(RasterTesting.tight(source), RasterTesting.ffmpegFrames(path, "rgba"), source.width(), what);
    }

    @Nested
    @DisplayName("color type selection")
    class Selection {
        @Test
        @DisplayName("writes truecolor for opaque pictures with many colors")
        void rgb() {
            var png = PngEncoder.encode(photo(64, 64, false), PngEncoderOptions.defaults());
            assertEquals(2, colorType(png));
        }

        @Test
        @DisplayName("writes truecolor with alpha for translucent pictures with many colors")
        void rgba() {
            var png = PngEncoder.encode(photo(64, 64, true), PngEncoderOptions.defaults());
            assertEquals(6, colorType(png));
        }

        @Test
        @DisplayName("writes truecolor when alpha is declared but every pixel is opaque")
        void declaredButOpaque() {
            var picture = photo(64, 64, false);
            var declared = RgbaPicture.of(64, 64, 256, picture.pixels(), true);
            assertEquals(2, colorType(PngEncoder.encode(declared, PngEncoderOptions.defaults())));
        }

        @ParameterizedTest(name = "{0} colors")
        @ValueSource(ints = {1, 2, 16, 255, 256})
        @DisplayName("writes an 8-bit palette for up to 256 colors")
        void palette(int colors) {
            var picture = indexed(40, 30, colors, false);
            var png = PngEncoder.encode(picture, PngEncoderOptions.defaults());
            assertEquals(3, colorType(png));
            assertEquals(8, png[24]);
            assertRoundTrip(picture, png, colors + " colors");
        }

        @Test
        @DisplayName("writes truecolor for 257 colors")
        void tooManyColors() {
            assertEquals(2, colorType(PngEncoder.encode(indexed(40, 30, 257, false), PngEncoderOptions.defaults())));
        }

        @Test
        @DisplayName("puts translucent palette entries first so tRNS stays short")
        void translucentPalette() {
            var picture = indexed(20, 20, 6, false);
            var pixels = picture.pixels();
            var translucent = RgbaPicture.of(20, 20, 80, pixels.clone(), true);
            translucent.pixels()[3 + 4 * 17] = 10;
            translucent.pixels()[3 + 4 * 200] = 0;
            var png = PngEncoder.encode(translucent, PngEncoderOptions.defaults());
            assertEquals(3, colorType(png));
            var trns = indexOf(png, "tRNS");
            assertTrue(trns > 0);
            var length = ((png[trns - 4] & 0xFF) << 24) | ((png[trns - 3] & 0xFF) << 16) | ((png[trns - 2] & 0xFF) << 8) | (png[trns - 1] & 0xFF);
            assertEquals(2, length);
            assertRoundTrip(translucent, png, "translucent palette");
        }

        @Test
        @DisplayName("prefers truecolor when the palette chunks outweigh the savings")
        void tinyImage() {
            var picture = indexed(2, 2, 4, true);
            assertEquals(6, colorType(PngEncoder.encode(picture, PngEncoderOptions.defaults())));
        }

        @Test
        @DisplayName("ignores the alpha samples of a picture that declares no alpha")
        void undeclaredAlpha() {
            var picture = photo(16, 16, true);
            var opaque = RgbaPicture.of(16, 16, 64, picture.pixels(), false);
            var png = PngEncoder.encode(opaque, PngEncoderOptions.defaults());
            assertEquals(2, colorType(png));
            var decoded = RasterTesting.tight(decode(png));
            var source = picture.pixels();
            for (var i = 0; i < decoded.length; i += 4) {
                assertEquals(source[i], decoded[i]);
                assertEquals((byte) 255, decoded[i + 3]);
            }
        }
    }

    static Stream<Arguments> filtersAndLevels() {
        var list = new ArrayList<Arguments>();
        for (var filter : PngEncoderOptions.Filter.values()) {
            for (var level : new int[]{0, 1, 6, 9}) {
                list.add(Arguments.of(filter, level));
            }
        }
        for (var level = 2; level <= 8; level++) {
            if (level != 6) {
                list.add(Arguments.of(PngEncoderOptions.Filter.ADAPTIVE, level));
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0} level {1}")
        @MethodSource("com.github.auties00.smalt.image.png.PngEncoderTest#filtersAndLevels")
        @DisplayName("returns the source pixels through this decoder and ffmpeg for opaque, alpha and palette pictures")
        void exact(PngEncoderOptions.Filter filter, int level) {
            var options = PngEncoderOptions.builder().compressionLevel(level).filter(filter).build();
            var pictures = List.of(photo(67, 45, false), photo(45, 67, true), indexed(71, 33, 200, true), indexed(33, 71, 12, false));
            for (var picture : pictures) {
                var png = PngEncoder.encode(picture, options);
                var what = filter + " level " + level + " " + picture.width() + "x" + picture.height();
                assertRoundTrip(picture, png, what);
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    assertFfmpegRoundTrip(picture, png, what);
                }
            }
        }

        @Test
        @DisplayName("handles 1x1 pictures, strided pictures and single rows and columns")
        void edges() {
            for (var size : new int[][]{{1, 1}, {1, 300}, {300, 1}, {3, 3}}) {
                for (var alpha : new boolean[]{false, true}) {
                    var picture = photo(size[0], size[1], alpha);
                    assertRoundTrip(picture, PngEncoder.encode(picture, PngEncoderOptions.defaults()), size[0] + "x" + size[1]);
                }
            }
            var source = photo(10, 7, true);
            var strided = new byte[64 * 7];
            for (var y = 0; y < 7; y++) {
                System.arraycopy(source.pixels(), y * 40, strided, y * 64, 40);
            }
            var picture = RgbaPicture.of(10, 7, 64, strided, true);
            assertRoundTrip(source, PngEncoder.encode(picture, PngEncoderOptions.defaults()), "strided");
        }

        @Test
        @DisplayName("validates its options")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().compressionLevel(-1).filter(PngEncoderOptions.Filter.NONE).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().compressionLevel(10).filter(PngEncoderOptions.Filter.NONE).build());
            assertThrows(NullPointerException.class, () -> PngEncoderOptions.builder().compressionLevel(6).filter(null).build());
            assertThrows(NullPointerException.class, () -> PngEncoder.encode(null, PngEncoderOptions.defaults()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(PngEncoderOptions.Filter.class)
        @DisplayName("uses the requested filter on every scanline")
        void filterBytes(PngEncoderOptions.Filter filter) {
            var picture = photo(20, 9, false);
            var png = PngEncoder.encode(picture, PngEncoderOptions.builder().compressionLevel(0).filter(filter).build());
            var idat = indexOf(png, "IDAT");
            var inflater = new java.util.zip.Inflater();
            var length = ((png[idat - 4] & 0xFF) << 24) | ((png[idat - 3] & 0xFF) << 16) | ((png[idat - 2] & 0xFF) << 8) | (png[idat - 1] & 0xFF);
            inflater.setInput(png, idat + 4, length);
            var raw = new byte[9 * 61];
            try {
                assertEquals(raw.length, inflater.inflate(raw));
            } catch (java.util.zip.DataFormatException exception) {
                fail(exception);
            }
            inflater.end();
            for (var y = 0; y < 9; y++) {
                var type = raw[y * 61];
                if (filter == PngEncoderOptions.Filter.ADAPTIVE) {
                    assertTrue(type >= 0 && type <= 4);
                } else {
                    assertEquals(filter.ordinal(), type);
                }
            }
        }
    }

    @Test
    @DisplayName("stays within 10% of ffmpeg's encoder with -pred mixed on a screenshot and a photo")
    void sizeAgainstFfmpeg() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        for (var source : List.of("testsrc2=size=640x360:rate=1", "mandelbrot=size=640x360:rate=1")) {
            var input = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", source),
                    List.of("-frames:v", "1", "-vf", "format=rgb24,noise=alls=4:allf=u", "-pred", "none", "-compression_level", "0"));
            var reference = Fixtures.ffmpeg("png", List.of("-i", input.toString()), List.of("-pred", "mixed"));
            var picture = decode(Fixtures.read(input));
            var ours = PngEncoder.encode(picture, PngEncoderOptions.defaults());
            var theirs = Fixtures.read(reference).length;
            System.out.printf("PNG size %s: ours %d bytes, ffmpeg -pred mixed %d bytes (%.3fx)%n", source, ours.length, theirs,
                    ours.length / (double) theirs);
            assertTrue(ours.length <= theirs * 1.10, "ours " + ours.length + " vs ffmpeg " + theirs);
            assertFfmpegRoundTrip(picture, ours, "size-" + source.substring(0, 6));
        }
    }

    @Test
    @DisplayName("encodes a 16-megapixel picture within the time budget")
    void large() {
        var picture = photo(4096, 4096, true);
        var png = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> PngEncoder.encode(picture, PngEncoderOptions.builder().compressionLevel(1).filter(PngEncoderOptions.Filter.ADAPTIVE).build()));
        assertRoundTrip(picture, png, "large");
    }

    private static int indexOf(byte[] data, String type) {
        var needle = PngFiles.ascii(type);
        outer:
        for (var i = 0; i + 4 <= data.length; i++) {
            for (var j = 0; j < 4; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
