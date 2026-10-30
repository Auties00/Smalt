package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.Ssimulacra2;
import com.github.auties00.smalt.testkit.image.SyntheticProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the JPEG XL encoder: lossless modular round trips at every effort, decoded exactly by Smalt and by djxl;
 * lossy VarDCT quality against the source and against cjxl at the same distance, scored with SSIMULACRA 2; JPEG
 * files recompressed and rebuilt byte for byte by Smalt and djxl; animations, metadata boxes and the registry.
 * The oracle comparisons skip when cjxl or djxl is not on the PATH.
 */
class JxlEncoderTest {
    static final Tolerance EXACT = Tolerance.exact();

    /** djxl and Smalt evaluate the XYB and IDCT float transforms with different approximations. */
    static final Tolerance DECODERS = Tolerance.offByOne();

    static Path scratch(String name) {
        return Scratch.directory("jxl-encoder").resolve(name);
    }

    /** A photograph-like picture: smooth shading, hard edges, fine texture, and a gradient alpha when asked. */
    static RgbaPicture photo(int width, int height, boolean alpha, long seed) {
        var pixels = new byte[width * height * 4];
        var random = new Random(seed);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                var shade = 0.5 + 0.35 * Math.sin(x / 23.0) * Math.cos(y / 31.0);
                var edge = ((x / 37) + (y / 29)) % 3 == 0 ? 0.25 : 0.0;
                var texture = (random.nextGaussian() * 0.02) * ((x / 64) % 2);
                pixels[i] = (byte) clamp(255 * (shade + edge + texture));
                pixels[i + 1] = (byte) clamp(255 * (0.4 + 0.3 * Math.sin((x + y) / 17.0) + texture));
                pixels[i + 2] = (byte) clamp(255 * (0.6 - 0.4 * shade + edge * 0.5));
                pixels[i + 3] = alpha ? (byte) clamp(255.0 * ((x + 2 * y) % 97) / 96.0) : (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    static RgbaPicture palette(int width, int height, int colors, long seed) {
        var random = new Random(seed);
        var table = new int[colors];
        for (var i = 0; i < colors; i++) {
            table[i] = random.nextInt(1 << 24);
        }
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var c = table[((x / 5) * 7 + (y / 3) * 3 + (random.nextInt(9) == 0 ? 1 : 0)) % colors];
                var i = (y * width + x) * 4;
                pixels[i] = (byte) (c >> 16);
                pixels[i + 1] = (byte) (c >> 8);
                pixels[i + 2] = (byte) c;
                pixels[i + 3] = (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    /** Screen-like content: flat color bars, dense black glyph strokes on a light ground, and checkered ramps. */
    static RgbaPicture graphics(int width, int height) {
        var pixels = new byte[width * height * 4];
        int[] bars = {0xC0C0C0, 0xC0C000, 0x00C0C0, 0x00C000, 0xC000C0, 0xC00000, 0x0000C0, 0x101010};
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int c;
                if (y < height / 3) {
                    c = bars[x * bars.length / width];
                } else if (y < 2 * height / 3) {
                    var glyph = ((x / 3) * 7 + (y / 3) * 13) % 11 < 4 && (x / 24 + y / 16) % 2 == 0;
                    c = glyph ? 0x000000 : 0xF0F0E0;
                } else {
                    var v = x * 255 / Math.max(1, width - 1);
                    c = (x / 16 + y / 16) % 2 == 0 ? (v << 16) | (v << 8) | v : 0x2040A0;
                }
                var i = (y * width + x) * 4;
                pixels[i] = (byte) (c >> 16);
                pixels[i + 1] = (byte) (c >> 8);
                pixels[i + 2] = (byte) c;
                pixels[i + 3] = (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    static RgbaPicture gray(int width, int height) {
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var v = (byte) ((x * 7 + y * 3 + (x * y) % 13) & 0xFF);
                var i = (y * width + x) * 4;
                pixels[i] = v;
                pixels[i + 1] = v;
                pixels[i + 2] = v;
                pixels[i + 3] = (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    static Rgba16Picture deep(int width, int height, boolean alpha) {
        var random = new Random(width ^ height);
        var pixels = new short[width * height * 4];
        for (var i = 0; i < pixels.length; i++) {
            var x = (i / 4) % width;
            pixels[i] = (short) (i % 4 == 3 && !alpha ? 65535 : (x * 211 + random.nextInt(64)) & 0xFFFF);
        }
        return Rgba16Picture.of(width, height, width * 4, pixels, alpha);
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    static byte[] tight(RgbaPicture picture) {
        var width = picture.width();
        var out = new byte[width * picture.height() * 4];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * width * 4, width * 4);
        }
        return out;
    }

    static RgbaPicture decode(byte[] file) {
        return (RgbaPicture) JxlDecoder.decode(MediaSource.of(file)).picture();
    }

    static RgbaPicture djxl(byte[] file, String name) {
        Oracle.assume(Oracle.Tool.DJXL);
        var jxl = scratch(name + ".jxl");
        var png = scratch(name + ".png");
        write(jxl, file);
        Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(jxl.toString(), png.toString(), "--quiet"));
        return (RgbaPicture) ImageOps8.rgba(read(png));
    }

    /** Decodes a file with djxl to 16-bit RGBA. */
    static Rgba16Picture djxl16(byte[] file, String name) {
        Oracle.assume(Oracle.Tool.DJXL);
        var jxl = scratch(name + ".jxl");
        var png = scratch(name + ".png");
        write(jxl, file);
        Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(jxl.toString(), png.toString(), "--quiet"));
        return (Rgba16Picture) Images.decode(MediaSource.of(read(png)), ImageDecoderOptions.builder()
                .highBitDepth(true).build()).picture();
    }

    static Rgba16Picture decode16(byte[] file) {
        return (Rgba16Picture) JxlDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder()
                .highBitDepth(true).build()).picture();
    }

    /** The photograph-like picture with every color sample set to its green sample. */
    static RgbaPicture grayPhoto(int width, int height, long seed) {
        var source = photo(width, height, false, seed);
        var pixels = source.pixels().clone();
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = pixels[i + 1];
            pixels[i + 2] = pixels[i + 1];
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    /** Returns the image header of a bare codestream or of the jxlc box of a container. */
    static ImageHeader header(byte[] file) {
        var offset = 0;
        while (file[0] != (byte) 0xFF) {
            var size = ((file[offset] & 0xFF) << 24) | ((file[offset + 1] & 0xFF) << 16)
                    | ((file[offset + 2] & 0xFF) << 8) | (file[offset + 3] & 0xFF);
            if (new String(file, offset + 4, 4, StandardCharsets.ISO_8859_1).equals("jxlc")) {
                offset += 8;
                break;
            }
            assertTrue(size >= 8, "a box of " + size + " bytes before the jxlc box");
            offset += size;
        }
        return ImageHeader.read(new JxlBitReader(file, offset, file.length - offset));
    }

    static void write(Path file, byte[] data) {
        try {
            Files.write(file, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes a PNG to 8-bit RGBA, forcing an alpha channel so that pictures compare sample for sample. */
    static final class ImageOps8 {
        static RgbaPicture rgba(byte[] png) {
            var picture = (RgbaPicture) Images.decode(MediaSource.of(png)).picture();
            return RgbaPicture.of(picture.width(), picture.height(), picture.stride(), picture.pixels(), true);
        }
    }

    static void assertSamePixels(String what, RgbaPicture expected, RgbaPicture actual) {
        assertEquals(expected.width(), actual.width(), what + " width");
        assertEquals(expected.height(), actual.height(), what + " height");
        EXACT.assertWithin(what, tight(expected), tight(actual));
    }

    static Stream<Arguments> efforts() {
        return IntStream.rangeClosed(1, 10).boxed().flatMap(effort -> Stream.of(
                Arguments.of(effort, "photo"), Arguments.of(effort, "palette"), Arguments.of(effort, "gray")));
    }

    static RgbaPicture named(String name) {
        return switch (name) {
            case "photo" -> photo(300, 260, true, 1);
            case "palette" -> palette(200, 150, 40, 2);
            default -> gray(64, 48);
        };
    }

    @Nested
    @DisplayName("lossless coding")
    class Lossless {
        @ParameterizedTest(name = "effort {0} {1}")
        @MethodSource("com.github.auties00.smalt.image.jxl.JxlEncoderTest#efforts")
        @DisplayName("decodes to the exact samples in Smalt and in djxl")
        void efforts(int effort, String name) {
            var source = named(name);
            var options = JxlEncoderOptions.lossless().toBuilder().effort(effort).build();
            var file = JxlEncoder.encode(source, options);
            var decoded = decode(file);
            assertSamePixels("Smalt " + name, source, decoded.hasAlpha() ? decoded : withAlpha(decoded));
            assertSamePixels("djxl " + name, withAlpha(source), djxl(file, "lossless-" + name + "-e" + effort));
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"1, 1", "1, 300", "300, 1", "257, 257", "2100, 30", "513, 700", "8, 2049"})
        @DisplayName("keeps every sample at sizes across group and LF group boundaries")
        void sizes(int width, int height) {
            var source = photo(width, height, true, width * 7L + height);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.lossless());
            assertSamePixels(width + "x" + height, source, decode(file));
            assertSamePixels("djxl " + width + "x" + height, source, djxl(file, "lossless-" + width + "x" + height));
        }

        @Test
        @DisplayName("is smaller than the PNG of the same pixels and close to cjxl at the same effort")
        void size() {
            var source = photo(300, 260, false, 3);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.lossless());
            assertTrue(file.length < Images.encode(source, ImageFormat.PNG).length,
                    "lossless JPEG XL of " + file.length + " bytes is not smaller than PNG");
            Oracle.assume(Oracle.Tool.CJXL);
            var png = scratch("size-source.png");
            write(png, Images.encode(source, ImageFormat.PNG));
            var reference = scratch("size-cjxl.jxl");
            Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2), List.of(png.toString(), reference.toString(), "-d",
                    "0", "-e", "7", "--quiet"));
            var cjxl = read(reference).length;
            assertTrue(file.length <= cjxl * 1.25, file.length + " bytes against cjxl's " + cjxl);
        }

        @Test
        @DisplayName("codes flat screen content at effort 1 in fewer bytes than cjxl -e 1, exactly for djxl")
        void fastRuns() {
            var source = graphics(320, 240);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.lossless().toBuilder().effort(1).build());
            assertSamePixels("Smalt", source, withAlpha(decode(file)));
            assertSamePixels("djxl", withAlpha(source), djxl(file, "fast-runs"));
            Oracle.assume(Oracle.Tool.CJXL);
            var png = scratch("fast-runs.png");
            write(png, Images.encode(source, ImageFormat.PNG));
            var reference = scratch("fast-runs-cjxl.jxl");
            Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2), List.of(png.toString(), reference.toString(), "-d",
                    "0", "-e", "1", "--quiet"));
            // Measured 2302 bytes against cjxl 0.11.2's 3373; without LZ77 runs Smalt took 11135.
            var cjxl = read(reference).length;
            assertTrue(file.length <= cjxl * 0.9, file.length + " bytes against cjxl's " + cjxl);
        }

        @ParameterizedTest(name = "alpha {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("keeps 16-bit samples")
        void deepSamples(boolean alpha) {
            var source = deep(97, 61, alpha);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.lossless());
            var decoded = (Rgba16Picture) JxlDecoder.decode(MediaSource.of(file),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            EXACT.assertWithin("16-bit", source.pixels(), decoded.pixels(), 16);
            EXACT.assertWithin("djxl 16-bit", source.pixels(), djxl16(file, "deep-" + alpha).pixels(), 16);
        }

        @Test
        @DisplayName("keeps floating-point samples and extra channels bit for bit")
        void floats() {
            var layout = PixelLayout.of(Channel.of(ChannelType.RED), Channel.of(ChannelType.GREEN),
                    Channel.of(ChannelType.BLUE), Channel.of(ChannelType.ALPHA), Channel.of(ChannelType.DEPTH));
            var w = 45;
            var h = 33;
            var pixels = new float[w * h * 5];
            var random = new Random(5);
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = i % 5 == 4 ? random.nextFloat() * 100 : (float) (random.nextGaussian() * 0.6 + 0.4);
            }
            var source = ChannelFloatPicture.of(w, h, w * 5, pixels, layout);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.lossless());
            var decoded = JxlDecoder.decodeFloat(MediaSource.of(file));
            assertEquals(3, decoded.colorChannels());
            for (var c = 0; c < 5; c++) {
                var plane = decoded.plane(c);
                for (var i = 0; i < w * h; i++) {
                    assertEquals(Float.floatToRawIntBits(pixels[i * 5 + c]), Float.floatToRawIntBits(plane[i]),
                            "channel " + c + " sample " + i);
                }
            }
            assertTrue(decoded.extraChannel(JxlChannelType.DEPTH).isPresent());
        }

        @Test
        @DisplayName("keeps CMYK inks and their profile")
        void cmyk() {
            var source = SyntheticProfiles.cmykPicture(64, 40);
            var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
            var file = JxlEncoder.encode(source, JxlEncoderOptions.defaults().toBuilder().colorProfile(profile).build());
            var decoded = JxlDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().keepChannels(true)
                    .build());
            var picture = (com.github.auties00.smalt.image.Channel8Picture) decoded.picture();
            EXACT.assertWithin("inks", source.pixels(), Arrays.copyOf(picture.pixels(), source.pixels().length));
            assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
            // djxl writes the stored cyan, magenta and yellow channels of a CMYK image to PNG as they are, without
            // the profile: JPEG XL stores 1 - ink, so they must read back as 255 minus each ink.
            var stored = djxl(file, "cmyk");
            var inks = source.pixels();
            var expected = new byte[64 * 40 * 4];
            for (var i = 0; i < 64 * 40; i++) {
                for (var c = 0; c < 3; c++) {
                    expected[i * 4 + c] = (byte) (255 - (inks[i * 4 + c] & 0xFF));
                }
                expected[i * 4 + 3] = (byte) 255;
            }
            EXACT.assertWithin("djxl stored inks", expected, tight(stored));
        }

        @Test
        @DisplayName("rejects profiles that do not describe the samples, and codes gray ones of a gray profile as gray")
        void profileChannels() {
            var gray = ColorProfile.grayGamma22();
            var color = photo(30, 20, false, 16);
            assertThrows(IllegalArgumentException.class, () -> JxlEncoder.encode(color, JxlEncoderOptions.lossless()
                    .toBuilder().colorProfile(gray).build()));
            var cmyk = ColorProfile.parse(SyntheticProfiles.cmyk());
            assertThrows(IllegalArgumentException.class, () -> JxlEncoder.encode(color, JxlEncoderOptions.lossless()
                    .toBuilder().colorProfile(cmyk).build()));
            var deepGray = deep(30, 20, false);
            var pixels = deepGray.pixels();
            for (var i = 0; i < pixels.length; i += 4) {
                pixels[i + 1] = pixels[i];
                pixels[i + 2] = pixels[i];
            }
            var file = JxlEncoder.encode(deepGray, JxlEncoderOptions.lossless().toBuilder().colorProfile(gray).build());
            assertEquals(ColorEncoding.GRAY, header(file).colorEncoding().colorSpace());
            EXACT.assertWithin("16-bit gray", pixels, decode16(file).pixels(), 16);
        }

        @Test
        @DisplayName("embeds a non-sRGB profile and keeps the samples")
        void iccProfile() {
            var source = photo(70, 50, false, 6);
            var profile = ColorProfile.adobeRgb();
            var file = JxlEncoder.encode(source, JxlEncoderOptions.lossless().toBuilder().colorProfile(profile).build());
            var decoded = JxlDecoder.decode(MediaSource.of(file));
            assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
            assertSamePixels("Adobe RGB samples", withAlpha(source), withAlpha((RgbaPicture) decoded.picture()));
        }
    }

    /** Returns the levels of a file's jxll boxes; a bare codestream has none. */
    static List<Integer> levelBoxes(byte[] file) {
        // JxlDecoder.readBoxes leaves the jxll box out, so the top-level boxes are walked here.
        var levels = new ArrayList<Integer>();
        var offset = 0;
        while (file[0] != (byte) 0xFF && offset + 8 <= file.length) {
            long size = ((file[offset] & 0xFFL) << 24) | ((file[offset + 1] & 0xFF) << 16)
                    | ((file[offset + 2] & 0xFF) << 8) | (file[offset + 3] & 0xFF);
            var type = new String(file, offset + 4, 4, StandardCharsets.ISO_8859_1);
            var header = 8;
            if (size == 1) {
                size = 0;
                for (var i = 0; i < 8; i++) {
                    size = (size << 8) | (file[offset + 8 + i] & 0xFF);
                }
                header = 16;
            } else if (size == 0) {
                size = file.length - offset;
            }
            if (type.equals("jxll")) {
                levels.add(file[offset + header] & 0xFF);
            }
            offset += (int) size;
        }
        return levels;
    }

    static RgbaPicture withAlpha(RgbaPicture p) {
        return RgbaPicture.of(p.width(), p.height(), p.stride(), p.pixels(), true);
    }

    @Nested
    @DisplayName("lossy coding")
    class Lossy {
        @ParameterizedTest(name = "distance {0}")
        @ValueSource(floats = {0.5f, 1.0f, 2.0f, 4.0f, 8.0f})
        @DisplayName("loses quality and size steadily with the distance")
        void distances(float distance) {
            var source = photo(256, 192, false, 7);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.ofDistance(distance));
            var decoded = decode(file);
            var score = Ssimulacra2.score(source, decoded);
            var minimum = distance <= 0.5f ? 85 : distance <= 1 ? 80 : distance <= 2 ? 70 : distance <= 4 ? 55 : 30;
            assertTrue(score >= minimum, "SSIMULACRA 2 of " + score + " at distance " + distance);
            var coarser = JxlEncoder.encode(source, JxlEncoderOptions.ofDistance(Math.min(25, distance * 2)));
            assertTrue(coarser.length < file.length, "doubling the distance did not shrink the file");
            assertTrue(Ssimulacra2.score(source, decode(coarser)) < score);
        }

        // Bounds measured against cjxl 0.11.2: on the photograph-like picture Smalt scores within 0.4 points of
        // cjxl at up to 3% more bytes; on the graphics picture at effort 5 (where cjxl looks for no patches) within
        // 0.3 points at up to 5% more bytes. A transform search that never chose the 2x2 and identity transforms
        // scored 1.2 points lower at 6 to 9% more bytes there.
        @ParameterizedTest(name = "{0} distance {1} effort {2}")
        @CsvSource({"photo, 1.0, 7, 1.5, 1.10", "photo, 2.0, 7, 1.5, 1.10", "photo, 1.0, 3, 1.5, 1.10",
                "photo, 3.0, 5, 1.5, 1.10", "photo, 0.5, 7, 1.5, 1.10", "graphics, 1.0, 5, 0.75, 1.08",
                "graphics, 3.0, 5, 0.75, 1.08"})
        @DisplayName("scores close to cjxl at the same distance and effort and size")
        void againstCjxl(String name, float distance, int effort, double points, double sizeRatio) {
            Oracle.assume(Oracle.Tool.CJXL);
            var source = name.equals("photo") ? photo(320, 240, false, 8) : graphics(320, 240);
            var options = JxlEncoderOptions.ofDistance(distance).toBuilder().effort(effort).build();
            var file = JxlEncoder.encode(source, options);
            var tag = name + "-" + distance + "-" + effort;
            var png = scratch("cjxl-src-" + tag + ".png");
            write(png, Images.encode(source, ImageFormat.PNG));
            var reference = scratch("cjxl-" + tag + ".jxl");
            Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2), List.of(png.toString(), reference.toString(), "-d",
                    Float.toString(distance), "-e", Integer.toString(effort), "--quiet"));
            var cjxl = read(reference);
            var ours = Ssimulacra2.score(source, decode(file));
            var theirs = Ssimulacra2.score(source, decode(cjxl));
            assertTrue(ours >= theirs - points, "SSIMULACRA 2 " + ours + " against cjxl's " + theirs);
            assertTrue(file.length <= cjxl.length * sizeRatio, file.length + " bytes against cjxl's " + cjxl.length);
        }

        @ParameterizedTest(name = "effort {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("decodes in djxl as in Smalt at every effort")
        void decoders(int effort) {
            var source = photo(131, 97, true, 9);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.ofDistance(1.5f).toBuilder().effort(effort)
                    .build());
            var smalt = decode(file);
            var reference = djxl(file, "lossy-e" + effort);
            DECODERS.assertWithin("djxl against Smalt", tight(withAlpha(smalt)), tight(reference));
            var alpha = new byte[131 * 97];
            var decodedAlpha = new byte[131 * 97];
            for (var i = 0; i < alpha.length; i++) {
                alpha[i] = source.pixels()[i * 4 + 3];
                decodedAlpha[i] = smalt.pixels()[i * 4 + 3];
            }
            EXACT.assertWithin("alpha", alpha, decodedAlpha);
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"1, 1", "9, 9", "300, 7", "2100, 40", "520, 260"})
        @DisplayName("encodes every size, with partial blocks, groups and LF groups")
        void sizes(int width, int height) {
            var source = photo(width, height, false, width + height);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.defaults());
            var decoded = decode(file);
            assertEquals(width, decoded.width());
            assertEquals(height, decoded.height());
            if (width >= 8 && height >= 8) {
                assertTrue(Quality.psnr(source, decoded) > 30, "PSNR " + Quality.psnr(source, decoded));
            }
            DECODERS.assertWithin("djxl " + width + "x" + height, tight(withAlpha(decoded)),
                    tight(djxl(file, "lossy-" + width + "x" + height)));
        }

        @ParameterizedTest(name = "effort {0}")
        @ValueSource(ints = {3, 7})
        @DisplayName("codes gray pictures as gray XYB images, decoded alike by djxl")
        void gray(int effort) {
            var source = grayPhoto(150, 90, 14);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.ofDistance(1.0f).toBuilder().effort(effort)
                    .build());
            assertEquals(ColorEncoding.GRAY, header(file).colorEncoding().colorSpace());
            var decoded = decode(file);
            assertTrue(Quality.psnr(source, decoded) > 32, "PSNR " + Quality.psnr(source, decoded));
            DECODERS.assertWithin("djxl gray", tight(withAlpha(decoded)), tight(djxl(file, "lossy-gray-e" + effort)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PQ", "HLG"})
        @DisplayName("writes PQ and HLG profiles as enumerated encodings, exact losslessly and close lossily")
        void hdr(String name) {
            var transfer = TransferCharacteristics.valueOf(name);
            var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.BT2020, transfer));
            var source = deep(96, 64, false);
            var lossless = JxlEncoder.encode(source, JxlEncoderOptions.lossless().toBuilder().colorProfile(profile)
                    .build());
            var encoding = header(lossless).colorEncoding();
            assertFalse(encoding.wantIcc(), "an embedded profile");
            assertEquals(transfer == TransferCharacteristics.PQ ? ColorEncoding.TF_PQ : ColorEncoding.TF_HLG,
                    encoding.transfer());
            assertEquals(ColorEncoding.PRIMARIES_BT2100, encoding.primaries());
            EXACT.assertWithin("lossless " + name, source.pixels(), decode16(lossless).pixels(), 16);
            var info = JxlDecoder.decode(MediaSource.of(lossless)).colorProfile().orElseThrow().colorInfo()
                    .orElseThrow();
            assertEquals(transfer, info.transfer());
            assertEquals(ColorPrimaries.BT2020, info.primaries());
            var lossy = JxlEncoder.encode(source, JxlEncoderOptions.ofDistance(0.5f).toBuilder().colorProfile(profile)
                    .build());
            assertFalse(header(lossy).colorEncoding().wantIcc());
            var decoded = decode16(lossy);
            var psnr = Quality.psnr(source.pixels(), decoded.pixels(), 65535);
            assertTrue(psnr > 30, name + " PSNR " + psnr);
            Tolerance.psnr(40).assertWithin("djxl " + name, djxl16(lossy, "hdr-" + name).pixels(), decoded.pixels(),
                    16);
        }

        @Test
        @DisplayName("keeps 16-bit precision better than 8-bit output would")
        void deepLossy() {
            var source = deep(64, 64, false);
            var file = JxlEncoder.encode(source, JxlEncoderOptions.ofDistance(0.3f));
            var decoded = (Rgba16Picture) JxlDecoder.decode(MediaSource.of(file),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            assertTrue(Quality.psnr(source.pixels(), decoded.pixels(), 65535) > 30);
        }

        @Test
        @DisplayName("converts an embedded profile's colors to XYB and back")
        void iccProfile() {
            var source = photo(96, 64, false, 10);
            var profile = ColorProfile.displayP3();
            var file = JxlEncoder.encode(source, JxlEncoderOptions.defaults().toBuilder().colorProfile(profile).build());
            var decoded = JxlDecoder.decode(MediaSource.of(file));
            assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
            assertTrue(Quality.psnr(source, decoded.picture()) > 30, "PSNR " + Quality.psnr(source, decoded.picture()));
        }
    }

    @Nested
    @DisplayName("JPEG recompression")
    class Recompression {
        static Stream<String> supported() throws IOException {
            var directory = Path.of("src/test/resources/com/github/auties00/smalt/image/jpeg/cjpeg");
            try (var files = Files.list(directory)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".jpg") && !n.contains("arithmetic") && !n.contains("411"))
                        .sorted().toList().stream();
            }
        }

        static Stream<String> unsupported() throws IOException {
            var directory = Path.of("src/test/resources/com/github/auties00/smalt/image/jpeg/cjpeg");
            try (var files = Files.list(directory)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".jpg") && (n.contains("arithmetic") || n.contains("411")))
                        .sorted().toList().stream();
            }
        }

        static byte[] cjpeg(String name) {
            return read(Path.of("src/test/resources/com/github/auties00/smalt/image/jpeg/cjpeg", name));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("supported")
        @DisplayName("rebuilds the JPEG file byte for byte in Smalt and in djxl")
        void identity(String name) {
            var jpeg = cjpeg(name);
            var jxl = JxlEncoder.recompressJpeg(MediaSource.of(jpeg));
            assertArrayEquals(jpeg, JxlDecoder.reconstructJpeg(MediaSource.of(jxl)).orElseThrow(), "Smalt " + name);
            Oracle.assume(Oracle.Tool.DJXL);
            var in = scratch("rec-" + name + ".jxl");
            var out = scratch("rec-" + name);
            write(in, jxl);
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(in.toString(), out.toString(), "--quiet"));
            assertArrayEquals(jpeg, read(out), "djxl " + name);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("supported")
        @DisplayName("decodes to the pixels of the JPEG file")
        void pixels(String name) {
            var jpeg = cjpeg(name);
            var jxl = JxlEncoder.recompressJpeg(MediaSource.of(jpeg));
            var expected = Images.decode(MediaSource.of(jpeg)).picture();
            var actual = JxlDecoder.decode(MediaSource.of(jxl)).picture();
            Tolerance.psnr(38).assertWithin("decoded JPEG", com.github.auties00.smalt.image.ops.ImageOps.toRgba(expected),
                    actual);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unsupported")
        @DisplayName("rejects arithmetic coding and sampling factors JPEG XL cannot hold")
        void rejected(String name) {
            var jpeg = cjpeg(name);
            assertThrows(MediaException.class, () -> JxlEncoder.recompressJpeg(MediaSource.of(jpeg)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"rgb", "rgb-icc", "ycbcr-icc"})
        @DisplayName("rebuilds RGB files and files with an ICC profile in Smalt and in djxl")
        void colorSpaces(String mode) {
            var builder = JpegEncoderOptions.builder().quality(85);
            if (mode.startsWith("rgb")) {
                builder.colorSpace(JpegEncoderOptions.ColorSpace.RGB).chromaFormat(ChromaFormat.YUV444);
            }
            if (mode.endsWith("icc")) {
                builder.colorProfile(ColorProfile.adobeRgb());
            }
            var jpeg = JpegEncoder.encode(photo(77, 45, false, 15), builder.build());
            var jxl = JxlEncoder.recompressJpeg(MediaSource.of(jpeg));
            assertArrayEquals(jpeg, JxlDecoder.reconstructJpeg(MediaSource.of(jxl)).orElseThrow(), "Smalt " + mode);
            var decoded = JxlDecoder.decode(MediaSource.of(jxl));
            if (mode.endsWith("icc")) {
                assertArrayEquals(ColorProfile.adobeRgb().toByteArray(), decoded.iccProfile().orElseThrow());
            }
            Tolerance.psnr(38).assertWithin("decoded " + mode, com.github.auties00.smalt.image.ops.ImageOps.toRgba(
                    Images.decode(MediaSource.of(jpeg)).picture()), decoded.picture());
            Oracle.assume(Oracle.Tool.DJXL);
            var in = scratch("rec-" + mode + ".jxl");
            var out = scratch("rec-" + mode + ".jpg");
            write(in, jxl);
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(in.toString(), out.toString(), "--quiet"));
            assertArrayEquals(jpeg, read(out), "djxl " + mode);
        }

        @Test
        @DisplayName("rejects CMYK files and files whose height a DNL marker defines")
        void unsupportedLayouts() {
            var cmyk = JpegEncoder.encode(SyntheticProfiles.cmykPicture(24, 16), JpegEncoderOptions.builder()
                    .colorSpace(JpegEncoderOptions.ColorSpace.CMYK).colorProfile(ColorProfile.parse(
                            SyntheticProfiles.cmyk())).build());
            assertThrows(MediaException.Unsupported.class, () -> JxlEncoder.recompressJpeg(MediaSource.of(cmyk)));
            var dnl = cjpeg("227x149-yuv420-baseline.jpg");
            var sof = 2;
            while (!((dnl[sof] & 0xFF) == 0xFF && (dnl[sof + 1] & 0xFF) == 0xC0)) {
                sof++;
            }
            dnl[sof + 5] = 0;
            dnl[sof + 6] = 0;
            assertThrows(MediaException.Unsupported.class, () -> JxlEncoder.recompressJpeg(MediaSource.of(dnl)));
        }

        @Test
        @DisplayName("moves EXIF and XMP to their boxes and rebuilds them")
        void metadata() {
            var jpeg = read(Path.of("src/test/resources/com/github/auties00/smalt/image/fuzz/"
                    + "ImageMetadataFuzzTestInputs/metadata/valid-jpeg-all.jpg"));
            var jxl = JxlEncoder.recompressJpeg(MediaSource.of(jpeg));
            assertArrayEquals(jpeg, JxlDecoder.reconstructJpeg(MediaSource.of(jxl)).orElseThrow());
            var boxes = JxlDecoder.readBoxes(MediaSource.of(jxl)).stream().map(JxlBox::type).toList();
            assertTrue(boxes.contains("jbrd"), boxes.toString());
        }

        @Test
        @DisplayName("rejects files that are not JPEG")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> JxlEncoder.recompressJpeg(MediaSource.of(new byte[]{1,
                    2, 3, 4})));
            var truncated = Arrays.copyOf(cjpeg("227x149-yuv420-baseline.jpg"), 600);
            assertThrows(MediaException.Malformed.class, () -> JxlEncoder.recompressJpeg(MediaSource.of(truncated)));
        }
    }

    @Nested
    @DisplayName("animation")
    class Animation {
        static RgbaPicture frame(int index) {
            var w = 96;
            var h = 64;
            var pixels = new byte[w * h * 4];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var i = (y * w + x) * 4;
                    var inside = x >= 10 + index * 9 && x < 30 + index * 9 && y >= 12 && y < 40;
                    pixels[i] = (byte) (inside ? 250 : x * 2);
                    pixels[i + 1] = (byte) (inside ? 30 : y * 3);
                    pixels[i + 2] = (byte) (inside ? 60 : 128);
                    pixels[i + 3] = (byte) (inside ? 200 : 255);
                }
            }
            return RgbaPicture.of(w, h, w * 4, pixels, true);
        }

        @Test
        @DisplayName("decodes every frame exactly with its duration, merging repeated frames")
        void lossless() {
            var encoder = new JxlAnimationEncoder(96, 64, JxlEncoderOptions.lossless().toBuilder().loopCount(3).build());
            var frames = List.of(frame(0), frame(1), frame(1), frame(2), frame(3));
            for (var f : frames) {
                encoder.addFrame(f, Duration.ofMillis(40));
            }
            var file = encoder.finish();
            var info = JxlDecoder.probe(MediaSource.of(file));
            assertTrue(info.animated());
            assertEquals(4, info.frameCount());
            var expected = List.of(frame(0), frame(1), frame(2), frame(3));
            var durations = List.of(40_000L, 80_000L, 40_000L, 40_000L);
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(file))) {
                for (var k = 0; k < 4; k++) {
                    var decoded = reader.read();
                    assertNotNull(decoded, "frame " + k);
                    assertSamePixels("frame " + k, expected.get(k), (RgbaPicture) decoded.picture());
                    assertEquals(durations.get(k), decoded.durationMicros(), "duration " + k);
                }
                assertNull(reader.read());
            }
        }

        @Test
        @DisplayName("decodes in djxl as in Smalt")
        void djxlFrames() {
            Oracle.assume(Oracle.Tool.DJXL);
            var encoder = new JxlAnimationEncoder(96, 64, JxlEncoderOptions.ofDistance(1.0f));
            for (var k = 0; k < 3; k++) {
                encoder.addFrame(frame(k), Duration.ofMillis(100));
            }
            var file = encoder.finish();
            var in = scratch("anim.jxl");
            var out = scratch("anim.apng");
            write(in, file);
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(in.toString(), out.toString(), "--quiet"));
            try (var reference = Images.openAnimation(MediaSource.of(read(out)));
                 var smalt = JxlDecoder.openAnimation(MediaSource.of(file))) {
                for (var k = 0; k < 3; k++) {
                    var a = reference.read();
                    var b = smalt.read();
                    DECODERS.assertWithin("frame " + k, tight(withAlpha((RgbaPicture) a.picture())),
                            tight(withAlpha((RgbaPicture) b.picture())));
                }
            }
        }

        @Test
        @DisplayName("is written by the registry's animation writer")
        void registry() {
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.JXL, 96, 64, ImageEncoderOptions.lossless(),
                    sink)) {
                writer.write(frame(0), 50_000);
                writer.write(frame(1), 50_000);
                writer.finish();
            }
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(sink.toByteArray()))) {
                assertSamePixels("first", frame(0), (RgbaPicture) reader.read().picture());
                assertSamePixels("second", frame(1), (RgbaPicture) reader.read().picture());
            }
        }

        @Test
        @DisplayName("codes lossy frames of an embedded profile as the still encoder codes the picture")
        void lossyProfile() {
            var options = JxlEncoderOptions.ofDistance(1.0f).toBuilder().colorProfile(ColorProfile.displayP3())
                    .build();
            var encoder = new JxlAnimationEncoder(96, 64, options);
            encoder.addFrame(frame(0), Duration.ofMillis(50));
            encoder.addFrame(frame(1), Duration.ofMillis(50));
            var animation = encoder.finish();
            var still = JxlEncoder.encode(frame(0), options);
            try (var a = JxlDecoder.openAnimation(MediaSource.of(animation));
                 var s = JxlDecoder.openAnimation(MediaSource.of(still))) {
                DECODERS.assertWithin("first frame", tight((RgbaPicture) s.read().picture()),
                        tight((RgbaPicture) a.read().picture()));
            }
        }

        @Test
        @DisplayName("records the orientation of the options in the header")
        void orientation() {
            var options = ImageEncoderOptions.lossless().toBuilder().orientation(Orientation.ROTATE_90).build();
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.JXL, 96, 64, options, sink)) {
                writer.write(frame(0), 50_000);
                writer.write(frame(1), 50_000);
                writer.finish();
            }
            var file = sink.toByteArray();
            assertEquals(Orientation.ROTATE_90, JxlDecoder.probe(MediaSource.of(file)).orientation());
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(file))) {
                assertSamePixels("first", frame(0), (RgbaPicture) reader.read().picture());
                assertSamePixels("second", frame(1), (RgbaPicture) reader.read().picture());
            }
        }

        @Test
        @DisplayName("rejects a frame of another sample depth and goes on after it")
        void mixedFrames() {
            var encoder = new JxlAnimationEncoder(20, 10);
            encoder.addFrame(photo(20, 10, true, 1), Duration.ofMillis(10));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(deep(20, 10, true),
                    Duration.ofMillis(10)));
            var opaque = photo(20, 10, false, 2);
            encoder.addFrame(opaque, Duration.ofMillis(30));
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(encoder.finish()))) {
                assertSamePixels("first", photo(20, 10, true, 1), (RgbaPicture) reader.read().picture());
                var second = reader.read();
                assertSamePixels("second", withAlpha(opaque), (RgbaPicture) second.picture());
                assertEquals(30_000L, second.durationMicros());
            }
        }

        @Test
        @DisplayName("rejects frames of another size and use after finishing")
        void misuse() {
            var encoder = new JxlAnimationEncoder(96, 64);
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(photo(10, 10, false, 1),
                    Duration.ZERO));
            assertThrows(IllegalStateException.class, encoder::finish);
            encoder.addFrame(frame(0), Duration.ofMillis(10));
            encoder.finish();
            assertThrows(IllegalStateException.class, () -> encoder.addFrame(frame(0), Duration.ZERO));
        }
    }

    @Nested
    @DisplayName("metadata and registry")
    class Metadata {
        @Test
        @DisplayName("writes EXIF and XMP boxes in a container, and the orientation in the header")
        void boxes() {
            var exif = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0};
            var xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
            var options = JxlEncoderOptions.lossless().toBuilder().exif(exif).xmp(xmp)
                    .orientation(Orientation.ROTATE_90).build();
            var file = JxlEncoder.encode(photo(20, 10, false, 11), options);
            var decoded = JxlDecoder.decode(MediaSource.of(file));
            assertArrayEquals(exif, decoded.exif().orElseThrow());
            assertArrayEquals(xmp, decoded.xmp().orElseThrow());
            var prefixed = new byte[exif.length + 6];
            System.arraycopy("Exif".getBytes(StandardCharsets.US_ASCII), 0, prefixed, 0, 4);
            System.arraycopy(exif, 0, prefixed, 6, exif.length);
            assertArrayEquals(exif, JxlEncoderOptions.builder().exif(prefixed).build().exif().orElseThrow());
            assertEquals(Orientation.ROTATE_90, decoded.orientation());
            var bare = JxlEncoder.encode(photo(20, 10, false, 11), JxlEncoderOptions.lossless());
            assertEquals((byte) 0xFF, bare[0]);
            assertEquals((byte) 0x0A, bare[1]);
            var boxed = JxlEncoder.encode(photo(20, 10, false, 11), JxlEncoderOptions.lossless().toBuilder()
                    .container(true).build());
            assertEquals('J', boxed[4]);
            assertSamePixels("container", decode(bare), decode(boxed));
        }

        @Test
        @DisplayName("declares level 10 in a jxll box exactly when the image needs it")
        void level() {
            assertEquals(List.of(), levelBoxes(JxlEncoder.encode(photo(20, 10, false, 1), JxlEncoderOptions.lossless()
                    .toBuilder().container(true).build())));
            assertEquals(List.of(), levelBoxes(JxlEncoder.encode(deep(20, 10, false), JxlEncoderOptions.defaults()
                    .toBuilder().container(true).build())));
            // 16-bit lossless samples need 32-bit modular buffers, CMYK a black channel: both are level 10.
            var deep = JxlEncoder.encode(deep(20, 10, true), JxlEncoderOptions.lossless());
            assertEquals(List.of(10), levelBoxes(deep));
            var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
            var cmyk = JxlEncoder.encode(SyntheticProfiles.cmykPicture(16, 8), JxlEncoderOptions.defaults().toBuilder()
                    .colorProfile(profile).build());
            assertEquals(List.of(10), levelBoxes(cmyk));
            var recompressed = JxlEncoder.recompressJpeg(MediaSource.of(Images.encode(photo(40, 24, false, 2),
                    ImageFormat.JPEG)));
            assertEquals(List.of(), levelBoxes(recompressed));
        }

        @Test
        @DisplayName("is the registry's JPEG XL encoder")
        void registry() {
            assertTrue(Images.isEncodable(ImageFormat.JXL));
            assertTrue(Images.isAnimationEncodable(ImageFormat.JXL));
            var source = photo(40, 30, true, 12);
            assertArrayEquals(JxlEncoder.encode(source, JxlEncoderOptions.lossless()),
                    Images.encode(source, ImageFormat.JXL, ImageEncoderOptions.lossless()));
            assertArrayEquals(JxlEncoder.encode(source, JxlEncoderOptions.ofQuality(80)),
                    Images.encode(source, ImageFormat.JXL, ImageEncoderOptions.ofQuality(80)));
        }

        @Test
        @DisplayName("maps qualities to the distances of cjxl -q")
        void quality() {
            assertEquals(1.0f, JxlEncoderOptions.ofQuality(90).distance(), 1e-5f);
            assertTrue(JxlEncoderOptions.ofQuality(100).isLossless());
            assertEquals(0.1f + 70 * 0.09f, JxlEncoderOptions.ofQuality(30).distance(), 1e-5f);
            assertEquals(JxlEncoderOptions.MAX_DISTANCE, JxlEncoderOptions.ofQuality(0).distance(), 1e-5f);
            assertThrows(IllegalArgumentException.class, () -> JxlEncoderOptions.ofQuality(101));
            assertThrows(IllegalArgumentException.class, () -> JxlEncoderOptions.ofDistance(0));
            assertThrows(IllegalArgumentException.class, () -> JxlEncoderOptions.builder().effort(11).build());
            assertEquals(JxlEncoderOptions.defaults(), JxlEncoderOptions.defaults().toBuilder().build());
        }

        @Test
        @DisplayName("produces the same file on every run")
        void deterministic() {
            var source = photo(600, 400, true, 13);
            var first = JxlEncoder.encode(source, JxlEncoderOptions.defaults());
            var second = JxlEncoder.encode(source, JxlEncoderOptions.defaults());
            assertArrayEquals(first, second);
        }
    }
}
