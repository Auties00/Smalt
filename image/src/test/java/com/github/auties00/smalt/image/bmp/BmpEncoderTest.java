package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions.HeaderVersion;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions.PixelFormat;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.RenderingIntent;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers BMP encoding: every pixel format, header version and row order round trips through {@link BmpDecoder};
 * run-length encoding is checked code by code and against the uncompressed size; the automatic choices, the color
 * profile, the header fields and the options are checked; the output is pinned by SHA-256; and the JDK's ImageIO
 * reader, Pillow and ffmpeg read every layout to the same pixels.
 */
class BmpEncoderTest {
    static RgbaPicture picture(int width, int height, int colors, long seed, boolean alpha) {
        var random = new Random(seed);
        var palette = new int[Math.max(colors, 1)];
        for (var i = 0; i < palette.length; i++) {
            palette[i] = random.nextInt();
        }
        var pixels = new byte[width * height * 4];
        for (var i = 0; i < width * height; i++) {
            var color = colors == 0 ? random.nextInt() : palette[random.nextInt(colors)];
            pixels[i * 4] = (byte) (color >>> 16);
            pixels[i * 4 + 1] = (byte) (color >>> 8);
            pixels[i * 4 + 2] = (byte) color;
            pixels[i * 4 + 3] = alpha ? (byte) (color >>> 24) : (byte) 0xFF;
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    // a picture of horizontal runs, alternating pairs and noise, the cases run-length coding treats differently
    static RgbaPicture structured(int width, int height, int colors, long seed) {
        var random = new Random(seed);
        var pixels = new byte[width * height * 4];
        var palette = new int[colors];
        for (var i = 0; i < colors; i++) {
            palette[i] = random.nextInt(1 << 24);
        }
        for (var y = 0; y < height; y++) {
            var x = 0;
            while (x < width) {
                var kind = random.nextInt(4);
                var length = 1 + random.nextInt(kind == 0 ? 300 : 12);
                var a = random.nextInt(colors);
                var b = random.nextInt(colors);
                for (var i = 0; i < length && x < width; i++, x++) {
                    var index = switch (kind) {
                        case 0 -> a;
                        case 1 -> (i & 1) == 0 ? a : b;
                        default -> random.nextInt(colors);
                    };
                    var at = (y * width + x) * 4;
                    pixels[at] = (byte) (palette[index] >>> 16);
                    pixels[at + 1] = (byte) (palette[index] >>> 8);
                    pixels[at + 2] = (byte) palette[index];
                    pixels[at + 3] = (byte) 0xFF;
                }
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    static byte[] expected(RgbaPicture picture, PixelFormat format) {
        var out = RasterTesting.tight(picture);
        for (var i = 0; i < out.length; i += 4) {
            switch (format) {
                case RGB_555, RGB_565 -> {
                    for (var c = 0; c < 3; c++) {
                        var bits = format == PixelFormat.RGB_565 && c == 1 ? 6 : 5;
                        var max = (1 << bits) - 1;
                        var stored = ((out[i + c] & 0xFF) * max + 127) / 255;
                        out[i + c] = (byte) Math.round(stored * 255.0 / max);
                    }
                    out[i + 3] = (byte) 0xFF;
                }
                case ARGB_8888 -> {
                    if (!picture.hasAlpha()) {
                        out[i + 3] = (byte) 0xFF;
                    }
                }
                default -> out[i + 3] = (byte) 0xFF;
            }
        }
        return out;
    }

    static int colorsOf(PixelFormat format) {
        return switch (format) {
            case PALETTE_1 -> 2;
            case PALETTE_4 -> 16;
            case PALETTE_8 -> 256;
            default -> 0;
        };
    }

    static RgbaPicture decode(byte[] bmp) {
        return (RgbaPicture) BmpDecoder.decode(MediaSource.of(bmp)).picture();
    }

    static Stream<Arguments> layouts() {
        var list = new ArrayList<Arguments>();
        for (var format : PixelFormat.values()) {
            for (var version : HeaderVersion.values()) {
                if (version == HeaderVersion.INFO && format == PixelFormat.ARGB_8888) {
                    continue;
                }
                list.add(Arguments.of(format, version));
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.bmp.BmpEncoderTest#layouts")
        @DisplayName("decodes every pixel format under every header, both row orders and odd sizes, to the picture")
        void layouts(PixelFormat format, HeaderVersion version) {
            for (var size : new int[][]{{1, 1}, {7, 3}, {33, 17}, {130, 5}}) {
                for (var topDown : new boolean[]{false, true}) {
                    var alpha = format == PixelFormat.ARGB_8888;
                    var picture = picture(size[0], size[1], colorsOf(format), format.ordinal() * 31L + size[0], alpha);
                    var options = BmpEncoderOptions.builder().pixelFormat(format).headerVersion(version).topDown(topDown)
                            .build();
                    var bmp = BmpEncoder.encode(picture, options);
                    var what = format + " " + version + " " + size[0] + "x" + size[1] + (topDown ? " top-down" : "");
                    var decoded = decode(bmp);
                    RasterTesting.assertSameRgba(expected(picture, format), RasterTesting.tight(decoded), size[0], what);
                    assertEquals(bmp.length, ByteArrays.i32le(bmp, 2), what + ": file size field");
                    assertEquals(switch (version) {
                        case INFO -> 40;
                        case V4 -> 108;
                        case V5 -> 124;
                    }, ByteArrays.i32le(bmp, 14), what);
                    assertEquals(topDown ? -size[1] : size[1], ByteArrays.i32le(bmp, 22), what);
                    var stride = (size[0] * ByteArrays.u16le(bmp, 28) + 31) / 32 * 4;
                    assertEquals(stride * size[1], ByteArrays.i32le(bmp, 34), what + ": image size field");
                    assertEquals(bmp.length, ByteArrays.i32le(bmp, 10) + stride * size[1], what + ": data offset");
                    if (version != HeaderVersion.INFO) {
                        assertEquals(0x73524742, ByteArrays.i32le(bmp, 14 + 56), what + ": sRGB color space");
                    }
                    var info = BmpDecoder.probe(MediaSource.of(bmp));
                    assertEquals(size[0], info.width());
                    assertEquals(size[1], info.height());
                }
            }
        }

        @Test
        @DisplayName("writes an ARGB pixel's stored color under alpha and declares the V5 alpha mask")
        void alpha() {
            var picture = picture(9, 4, 0, 5, true);
            var bmp = BmpEncoder.encode(picture);
            assertEquals(124, ByteArrays.i32le(bmp, 14));
            assertEquals(32, ByteArrays.u16le(bmp, 28));
            assertEquals(3, ByteArrays.i32le(bmp, 30));
            assertEquals(0xFF000000, ByteArrays.i32le(bmp, 14 + 52));
            var decoded = decode(bmp);
            assertTrue(decoded.hasAlpha());
            RasterTesting.assertSameRgba(RasterTesting.tight(picture), RasterTesting.tight(decoded), 9, "argb");
        }

        @Test
        @DisplayName("keeps the color of every pixel when a palette holds the picture's colors, in first appearance order")
        void exactPalette() {
            var picture = picture(40, 30, 200, 9, false);
            var bmp = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(PixelFormat.PALETTE_8).build());
            var used = ByteArrays.i32le(bmp, 46);
            assertTrue(used <= 200 && used > 150, "colors used " + used);
            assertEquals(14 + 40 + used * 4, ByteArrays.i32le(bmp, 10));
            var first = picture.pixels();
            assertEquals(first[2], bmp[54], "the first entry is the first pixel's color");
            assertEquals(first[1], bmp[55]);
            assertEquals(first[0], bmp[56]);
            RasterTesting.assertSameRgba(RasterTesting.tight(picture), RasterTesting.tight(decode(bmp)), 40, "exact");
        }

        @Test
        @DisplayName("writes the full color table of 1- and 4-bit palettes")
        void smallTables() {
            var picture = picture(5, 5, 1, 2, false);
            for (var format : new PixelFormat[]{PixelFormat.PALETTE_1, PixelFormat.PALETTE_4}) {
                var bmp = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(format).build());
                var entries = format == PixelFormat.PALETTE_1 ? 2 : 16;
                assertEquals(1, ByteArrays.i32le(bmp, 46));
                assertEquals(14 + 40 + entries * 4, ByteArrays.i32le(bmp, 10), format.name());
                RasterTesting.assertSameRgba(RasterTesting.tight(picture), RasterTesting.tight(decode(bmp)), 5, format.name());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = {"PALETTE_1", "PALETTE_4", "PALETTE_8"})
        @DisplayName("quantizes a picture with more colors than its palette holds")
        void quantizes(PixelFormat format) {
            var picture = gradient(64, 48);
            for (var dithering : Dithering.values()) {
                for (var method : QuantizeMethod.values()) {
                    var options = BmpEncoderOptions.builder().pixelFormat(format).dithering(dithering).method(method).build();
                    var bmp = BmpEncoder.encode(picture, options);
                    var used = ByteArrays.i32le(bmp, 46);
                    assertTrue(used >= 2 && used <= colorsOf(format), format + " colors " + used);
                    var decoded = RasterTesting.tight(decode(bmp));
                    var error = 0.0;
                    var source = RasterTesting.tight(picture);
                    for (var i = 0; i < source.length; i++) {
                        var difference = (source[i] & 0xFF) - (decoded[i] & 0xFF);
                        error += difference * difference;
                    }
                    var psnr = 10 * Math.log10(255.0 * 255 * source.length / Math.max(error, 1));
                    var floor = switch (format) {
                        case PALETTE_1 -> 6.0;
                        case PALETTE_4 -> 20.0;
                        default -> 25.0;
                    };
                    assertTrue(psnr > floor, format + " " + dithering + " " + method + " PSNR " + psnr);
                }
            }
        }

        @Test
        @DisplayName("narrows 16-bit pictures and through Images.encode keeps lossless requests exact or rejects them")
        void deepPictures() {
            var narrow = picture(6, 4, 0, 4, true);
            var deep = new short[6 * 4 * 4];
            for (var i = 0; i < deep.length; i++) {
                deep[i] = (short) ((narrow.pixels()[i] & 0xFF) * 257);
            }
            var exact = Rgba16Picture.of(6, 4, 24, deep, true);
            var bmp = Images.encode(exact, ImageFormat.BMP, ImageEncoderOptions.lossless());
            RasterTesting.assertSameRgba(RasterTesting.tight(narrow), RasterTesting.tight(decode(bmp)), 6, "exact 16-bit");
            deep[0] ^= 1;
            assertThrows(MediaException.Unsupported.class,
                    () -> Images.encode(exact, ImageFormat.BMP, ImageEncoderOptions.lossless()));
            assertNotNull(Images.encode(exact, ImageFormat.BMP, ImageEncoderOptions.ofQuality(50)));
        }
    }

    static RgbaPicture gradient(int width, int height) {
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                pixels[at] = (byte) (x * 255 / (width - 1));
                pixels[at + 1] = (byte) (y * 255 / (height - 1));
                pixels[at + 2] = (byte) ((x + y) * 255 / (width + height - 2));
                pixels[at + 3] = (byte) 0xFF;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    @Nested
    @DisplayName("run-length encoding")
    class RunLength {
        // walks the codes of one encoded bitmap, checking their structure, and counts the pixels they set
        static long walk(byte[] bmp, boolean four, int width, int height) {
            var position = ByteArrays.i32le(bmp, 10);
            var end = position + ByteArrays.i32le(bmp, 34);
            assertEquals(bmp.length, end);
            var x = 0;
            var rows = 0;
            var pixels = 0L;
            while (true) {
                var count = bmp[position] & 0xFF;
                var value = bmp[position + 1] & 0xFF;
                position += 2;
                if (count > 0) {
                    x += count;
                    pixels += count;
                    continue;
                }
                assertNotEquals(2, value, "no delta codes are written");
                if (value == 0 || value == 1) {
                    assertEquals(width, x, "row " + rows + " is complete");
                    rows++;
                    x = 0;
                    if (value == 1) {
                        break;
                    }
                    continue;
                }
                var bytes = four ? (value + 1) / 2 : value;
                position += (bytes + 1) & ~1;
                x += value;
                pixels += value;
            }
            assertEquals(height, rows, "every row ends with an end of line, the last with an end of bitmap");
            assertEquals(end, position, "the end of bitmap ends the data");
            return pixels;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = {"PALETTE_4", "PALETTE_8"})
        @DisplayName("codes every pixel of structured and random pictures and decodes them exactly")
        void exact(PixelFormat format) {
            var four = format == PixelFormat.PALETTE_4;
            for (var size : new int[][]{{1, 1}, {2, 3}, {3, 1}, {255, 2}, {256, 2}, {517, 13}}) {
                for (var seed = 0; seed < 4; seed++) {
                    var picture = seed == 3 ? picture(size[0], size[1], four ? 16 : 256, seed, false)
                            : structured(size[0], size[1], four ? 16 : 200, seed);
                    var bmp = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(format).runLength(true).build());
                    var what = format + " " + size[0] + "x" + size[1] + " seed " + seed;
                    assertEquals(four ? 2 : 1, ByteArrays.i32le(bmp, 30), what);
                    assertEquals((long) size[0] * size[1], walk(bmp, four, size[0], size[1]), what);
                    var decoded = decode(bmp);
                    assertFalse(decoded.hasAlpha(), what + ": every pixel is set");
                    RasterTesting.assertSameRgba(RasterTesting.tight(picture), RasterTesting.tight(decoded), size[0], what);
                }
            }
        }

        @Test
        @DisplayName("compresses runs and alternating pairs below the uncompressed size and chooses 4 or 8 bits")
        void compresses() {
            var picture = structured(400, 40, 12, 7);
            var plain = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(PixelFormat.PALETTE_8).build());
            var rle = BmpEncoder.encode(picture, BmpEncoderOptions.builder().runLength(true).build());
            assertEquals(4, ByteArrays.u16le(rle, 28), "12 colors take a 4-bit palette");
            var rle8 = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(PixelFormat.PALETTE_8)
                    .runLength(true).build());
            assertTrue(rle8.length < plain.length * 0.8, rle8.length + " vs " + plain.length);
            var many = structured(300, 20, 200, 8);
            assertEquals(8, ByteArrays.u16le(BmpEncoder.encode(many, BmpEncoderOptions.builder().runLength(true).build()), 28));
            var flat = BmpEncoder.encode(picture(1000, 1, 1, 1, false), BmpEncoderOptions.builder().runLength(true).build());
            assertEquals(14 + 40 + 16 * 4 + 4 * 2 + 2, flat.length, "1000 pixels are three runs of 255 and one of 235");
        }
    }

    @Nested
    @DisplayName("headers and options")
    class Headers {
        @Test
        @DisplayName("chooses the smallest lossless layout automatically")
        void automatic() {
            assertEquals(1, bits(BmpEncoder.encode(picture(8, 8, 2, 1, false))));
            assertEquals(4, bits(BmpEncoder.encode(picture(8, 8, 16, 1, false))));
            assertEquals(8, bits(BmpEncoder.encode(picture(30, 30, 256, 1, false))));
            assertEquals(24, bits(BmpEncoder.encode(picture(30, 30, 0, 1, false))));
            assertEquals(32, bits(BmpEncoder.encode(picture(8, 8, 0, 1, true))));
            var opaqueAlpha = picture(8, 8, 3, 1, false);
            assertEquals(4, bits(BmpEncoder.encode(RgbaPicture.of(8, 8, 32, opaqueAlpha.pixels(), true))),
                    "an opaque picture with alpha takes a palette");
            var underInfo = BmpEncoder.encode(picture(30, 30, 0, 1, true), BmpEncoderOptions.builder()
                    .headerVersion(HeaderVersion.INFO).build());
            assertEquals(24, bits(underInfo), "a BITMAPINFOHEADER cannot hold alpha");
        }

        private static int bits(byte[] bmp) {
            return ByteArrays.u16le(bmp, 28);
        }

        @Test
        @DisplayName("writes the 5-6-5 masks after a BITMAPINFOHEADER and inside a V4 header")
        void masks() {
            var picture = picture(3, 2, 0, 3, false);
            var info = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(PixelFormat.RGB_565).build());
            assertEquals(3, ByteArrays.i32le(info, 30));
            assertEquals(0xF800, ByteArrays.i32le(info, 54));
            assertEquals(0x07E0, ByteArrays.i32le(info, 58));
            assertEquals(0x001F, ByteArrays.i32le(info, 62));
            assertEquals(66, ByteArrays.i32le(info, 10));
            var v4 = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(PixelFormat.RGB_565)
                    .headerVersion(HeaderVersion.V4).build());
            assertEquals(0xF800, ByteArrays.i32le(v4, 54));
            assertEquals(0, ByteArrays.i32le(v4, 66));
            assertEquals(14 + 108, ByteArrays.i32le(v4, 10));
        }

        @Test
        @DisplayName("embeds the color profile after the pixel data under a V5 header with its rendering intent")
        void profile() {
            var profile = ColorProfile.displayP3();
            var picture = picture(11, 7, 0, 6, false);
            var bmp = BmpEncoder.encode(picture, BmpEncoderOptions.builder().colorProfile(profile).build());
            var icc = profile.toByteArray();
            assertEquals(124, ByteArrays.i32le(bmp, 14));
            assertEquals(0x4D424544, ByteArrays.i32le(bmp, 14 + 56));
            var intent = profile.renderingIntent() == RenderingIntent.PERCEPTUAL ? 4 : profile.renderingIntent()
                    == RenderingIntent.RELATIVE_COLORIMETRIC ? 2 : profile.renderingIntent() == RenderingIntent.SATURATION ? 1 : 8;
            assertEquals(intent, ByteArrays.i32le(bmp, 14 + 108));
            var offset = ByteArrays.i32le(bmp, 14 + 112) + 14;
            assertEquals(ByteArrays.i32le(bmp, 10) + ByteArrays.i32le(bmp, 34), offset, "the profile follows the pixels");
            assertEquals(icc.length, ByteArrays.i32le(bmp, 14 + 116));
            assertEquals(bmp.length, offset + icc.length);
            var decoded = BmpDecoder.decode(MediaSource.of(bmp));
            assertArrayEquals(icc, decoded.iccProfile().orElseThrow());
            assertEquals(profile, decoded.colorProfile().orElseThrow());
            var hdr = BmpEncoder.encode(picture, BmpEncoderOptions.builder().colorProfile(ColorProfile.of(
                    new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                            TransferCharacteristics.PQ))).build());
            assertTrue(BmpDecoder.decode(MediaSource.of(hdr)).colorProfile().orElseThrow().isHighDynamicRange());
        }

        @Test
        @DisplayName("writes the resolution and the same bytes to a sink as to an array")
        void fieldsAndSink() {
            var picture = picture(13, 9, 0, 8, false);
            var options = BmpEncoderOptions.builder().pixelsPerMeter(3780, 2835).build();
            var bmp = BmpEncoder.encode(picture, options);
            assertEquals(3780, ByteArrays.i32le(bmp, 38));
            assertEquals(2835, ByteArrays.i32le(bmp, 42));
            var sink = MediaSink.memory();
            BmpEncoder.encode(picture, options, sink);
            assertArrayEquals(bmp, sink.toByteArray());
            assertArrayEquals(BmpEncoder.encode(picture), Images.encode(picture, ImageFormat.BMP));
            var reset = options.toBuilder().noResolution().build();
            assertEquals(BmpEncoderOptions.defaults(), reset);
            assertEquals(options, options.toBuilder().build());
        }

        @Test
        @DisplayName("rejects option combinations a BMP header cannot hold")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().runLength(true).topDown(true).build());
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().runLength(true)
                    .pixelFormat(PixelFormat.RGB_888).build());
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().runLength(true)
                    .pixelFormat(PixelFormat.PALETTE_1).build());
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().headerVersion(HeaderVersion.INFO)
                    .pixelFormat(PixelFormat.ARGB_8888).build());
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().headerVersion(HeaderVersion.INFO)
                    .colorProfile(ColorProfile.srgb()).build());
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().headerVersion(HeaderVersion.V4)
                    .colorProfile(ColorProfile.srgb()).build());
            assertThrows(IllegalArgumentException.class, () -> BmpEncoderOptions.builder().pixelsPerMeter(-1, 0).build());
            assertThrows(MediaException.Unsupported.class, () -> BmpEncoderOptions.builder()
                    .colorProfile(ColorProfile.grayGamma22()).build());
            assertThrows(NullPointerException.class, () -> BmpEncoderOptions.builder().pixelFormat(null));
        }
    }

    // SHA-256 of the files written for a fixed picture, per layout; a change to the output fails here
    private static final Map<String, String> GOLDEN = new LinkedHashMap<>();

    static {
        GOLDEN.put("PALETTE_1",
                "c923d8099d51341b64bb8f49f9a03b19321ff8dd7b9a15e87808af11919f9e68");
        GOLDEN.put("PALETTE_4",
                "37e87ae9be9d2178ac1a1bc36cd2e8def5079b300c9987d50f250e6c0db1fd34");
        GOLDEN.put("PALETTE_8",
                "249a46d0200e186a0963790234655822100d93c82728ea2e004daf79d73e0244");
        GOLDEN.put("RGB_555",
                "4f391cd05fc612f1ddf2aa1beff97c5ee197fe703e6093135e71586cee79e653");
        GOLDEN.put("RGB_565",
                "a21b16640d902e2b13831fc31a6972515af00028954ec23c684182a396cec24c");
        GOLDEN.put("RGB_888",
                "e1e0f099ce70414d953130efa746d047590bc7612f2aad451fe4391b1ee5661c");
        GOLDEN.put("XRGB_8888",
                "0224d7272166c236c4382a73af04393ecabb75546fabd5d4bbc6347bc739ec65");
        GOLDEN.put("ARGB_8888",
                "e1ae31afc23dadfca7df7534897efe2ecce9b567ac4a080f6c001f2f794150ee");
        GOLDEN.put("PALETTE_4 rle",
                "15a7b83617a90cdbba5c4f0002a8f38093f3cdd46a734b7b479e4d79ef394f13");
        GOLDEN.put("PALETTE_8 rle",
                "9262ad1ef9b4306330567be74369016966be68ee503f0fea43af3a5715c8202e");
        GOLDEN.put("PALETTE_8 quantized",
                "f7d353e8188d773c68029c0343c01dfcaf1f2e23537e358345f9f59e1c492266");
        GOLDEN.put("RGB_888 V5 profile",
                "3abfef2f767d0ec3304e807407a774c1b5271d1124d6ec65bb0d17869eb6113c");
    }

    static Map<String, byte[]> goldenFiles() {
        var files = new LinkedHashMap<String, byte[]>();
        for (var format : PixelFormat.values()) {
            var picture = picture(29, 11, colorsOf(format), 77, format == PixelFormat.ARGB_8888);
            files.put(format.name(), BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(format).build()));
        }
        for (var format : new PixelFormat[]{PixelFormat.PALETTE_4, PixelFormat.PALETTE_8}) {
            files.put(format + " rle", BmpEncoder.encode(structured(301, 9, format == PixelFormat.PALETTE_4 ? 16 : 100, 3),
                    BmpEncoderOptions.builder().pixelFormat(format).runLength(true).build()));
        }
        files.put("PALETTE_8 quantized", BmpEncoder.encode(gradient(64, 48),
                BmpEncoderOptions.builder().pixelFormat(PixelFormat.PALETTE_8).build()));
        files.put("RGB_888 V5 profile", BmpEncoder.encode(picture(5, 3, 0, 1, false), BmpEncoderOptions.builder()
                .pixelFormat(PixelFormat.RGB_888).colorProfile(ColorProfile.adobeRgb()).pixelsPerMeter(100, 200).build()));
        return files;
    }

    @Test
    @DisplayName("writes the pinned bytes for every layout")
    void golden() throws NoSuchAlgorithmException {
        var actual = new LinkedHashMap<String, String>();
        for (var entry : goldenFiles().entrySet()) {
            actual.put(entry.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())));
        }
        assertEquals(GOLDEN, actual);
    }

    @Nested
    @DisplayName("independent readers")
    class Readers {
        private static List<byte[]> files() {
            var files = new ArrayList<byte[]>();
            for (var format : PixelFormat.values()) {
                for (var version : new HeaderVersion[]{HeaderVersion.INFO, HeaderVersion.V5}) {
                    if (version == HeaderVersion.INFO && format == PixelFormat.ARGB_8888) {
                        continue;
                    }
                    var picture = picture(37, 23, colorsOf(format), format.ordinal(), format == PixelFormat.ARGB_8888);
                    files.add(BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(format)
                            .headerVersion(version).build()));
                    if (format == PixelFormat.RGB_888) {
                        files.add(BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(format)
                                .headerVersion(version).topDown(true).build()));
                    }
                }
            }
            for (var format : new PixelFormat[]{PixelFormat.PALETTE_4, PixelFormat.PALETTE_8}) {
                files.add(BmpEncoder.encode(structured(301, 9, format == PixelFormat.PALETTE_4 ? 16 : 100, 3),
                        BmpEncoderOptions.builder().pixelFormat(format).runLength(true).build()));
            }
            return files;
        }

        private static void compare(String reader, List<byte[]> files, List<byte[]> decoded) {
            for (var i = 0; i < files.size(); i++) {
                var bmp = files.get(i);
                var what = reader + " file " + i + " (" + ByteArrays.u16le(bmp, 28) + " bits, compression "
                        + ByteArrays.i32le(bmp, 30) + ", header " + ByteArrays.i32le(bmp, 14) + ")";
                if (reader.equals("Pillow") && ByteArrays.i32le(bmp, 30) == 2) {
                    // Pillow 12.0 misreads RLE4 absolute runs; it misreads BMP Suite's g/pal4rle.bmp too
                    continue;
                }
                assertNotNull(decoded.get(i), what + " was not read");
                var smalt = RasterTesting.tight(decode(bmp));
                var width = ByteArrays.i32le(bmp, 18);
                if (ByteArrays.u16le(bmp, 28) == 16 && !reader.equals("ImageIO")) {
                    RasterTesting.assertSameRgba(widened(bmp, smalt, reader.equals("ffmpeg")), decoded.get(i), width, what);
                } else {
                    RasterTesting.assertSameRgba(smalt, decoded.get(i), width, what);
                }
            }
        }

        // ffmpeg widens 5- and 6-bit channels by bit replication and Pillow by v * 255 / max truncated; ImageIO rounds
        // as BmpDecoder does
        private static byte[] widened(byte[] bmp, byte[] smalt, boolean replicate) {
            var six = ByteArrays.i32le(bmp, 30) == 3;
            var out = smalt.clone();
            for (var i = 0; i < out.length; i += 4) {
                for (var c = 0; c < 3; c++) {
                    var bits = six && c == 1 ? 6 : 5;
                    var max = (1 << bits) - 1;
                    var stored = (int) Math.round((out[i + c] & 0xFF) * max / 255.0);
                    out[i + c] = (byte) (replicate ? stored << (8 - bits) | stored >>> (2 * bits - 8) : stored * 255 / max);
                }
            }
            return out;
        }

        @Test
        @DisplayName("the JDK's ImageIO reader reads every layout to the pixels BmpDecoder reads")
        void imageIo() {
            Skip.unless(BmpOracles.hasImageIo(), "env:java-launcher", "no JDK launcher to run ImageIoBmpDump");
            var files = files();
            compare("ImageIO", files, BmpOracles.imageIo(files));
        }

        @Test
        @DisplayName("Pillow reads every layout to the pixels BmpDecoder reads")
        void pillow() {
            var files = files();
            var decoded = BmpOracles.pillow(files);
            Skip.unless(decoded != null, "oracle:pillow", "Pillow is not installed");
            compare("Pillow", files, decoded);
        }

        @Test
        @DisplayName("ffmpeg reads every layout to the pixels BmpDecoder reads")
        void ffmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var files = files();
            var decoded = new ArrayList<byte[]>();
            for (var file : files) {
                decoded.add(BmpOracles.ffmpeg(file));
            }
            compare("ffmpeg", files, decoded);
        }
    }
}
