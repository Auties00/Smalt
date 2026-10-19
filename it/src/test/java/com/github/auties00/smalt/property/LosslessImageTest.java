package com.github.auties00.smalt.property;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lossless image coding is the identity: encoding a picture losslessly and decoding it at high bit depth with its
 * channels kept returns every sample unchanged, for every layout (gray, gray and alpha, RGB, RGBA) and depth
 * (8 and 16 bits) a format holds, over a grid of sizes (one pixel, odd sizes, wide and tall strips) and contents
 * (seeded noise, gradients, flat fields, alternating extremes). Formats that hold fewer bits than the picture reject
 * the lossless request unless the samples are exactly 8-bit; WebP keeps the color of fully transparent pixels only
 * when asked to be exact. JPEG XL and lossless JPEG, which Smalt decodes but does not encode, are encoded by the
 * reference tools (cjxl or ffmpeg's libjxl, and ffmpeg's ljpeg).
 */
@DisplayName("Lossless image round trips")
class LosslessImageTest {
    private static final int[][] SIZES = {{1, 1}, {2, 3}, {17, 9}, {64, 33}, {255, 2}, {3, 130}};

    private static final PixelLayout[] LAYOUTS = {PixelLayout.GRAY, PixelLayout.GRAY_ALPHA, PixelLayout.RGB,
            PixelLayout.RGBA};

    enum Content {
        NOISE, GRADIENT, FLAT, EXTREMES
    }

    record Case(int width, int height, PixelLayout layout, int depth, Content content, long seed) {
        @Override
        public String toString() {
            return width + "x" + height + " " + name(layout) + " " + depth + "-bit " + content.name().toLowerCase();
        }
    }

    static Stream<Case> cases(int... depths) {
        var cases = new ArrayList<Case>();
        var seed = 0L;
        for (var size : SIZES) {
            for (var layout : LAYOUTS) {
                for (var depth : depths) {
                    for (var content : Content.values()) {
                        cases.add(new Case(size[0], size[1], layout, depth, content, seed++));
                    }
                }
            }
        }
        return cases.stream();
    }

    static Stream<Case> allDepths() {
        return cases(8, 16);
    }

    static Stream<Case> eightBit() {
        return cases(8);
    }

    static Stream<Case> sixteenBit() {
        // Flat and alternating 16-bit fields hold only multiples of 257, which are exactly 8-bit.
        return cases(16).filter(test -> (test.content() == Content.NOISE || test.content() == Content.GRADIENT)
                && test.width() * test.height() >= 6);
    }

    static Stream<Arguments> deepFormats() {
        var arguments = new ArrayList<Arguments>();
        for (var format : List.of(ImageFormat.PNG, ImageFormat.TIFF, ImageFormat.JP2)) {
            allDepths().forEach(test -> arguments.add(Arguments.of(format, test)));
        }
        return arguments.stream();
    }

    static Stream<Arguments> eightBitFormats() {
        var arguments = new ArrayList<Arguments>();
        for (var format : List.of(ImageFormat.AVIF, ImageFormat.ICO)) {
            eightBit().forEach(test -> arguments.add(Arguments.of(format, test)));
        }
        return arguments.stream();
    }

    static Stream<Arguments> shallowFormats() {
        var arguments = new ArrayList<Arguments>();
        for (var format : List.of(ImageFormat.WEBP, ImageFormat.ICO, ImageFormat.GIF, ImageFormat.AVIF)) {
            sixteenBit().forEach(test -> arguments.add(Arguments.of(format, test)));
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("a format that holds 16 bits")
    class Deep {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#deepFormats")
        @DisplayName("returns every sample of every layout and depth")
        void identity(ImageFormat format, Case test) {
            var picture = picture(test);
            assertIdentity(format + " " + test, picture, Images.encode(picture, format, ImageEncoderOptions.lossless()),
                    true);
        }
    }

    @Nested
    @DisplayName("a format that holds 8 bits per sample")
    class EightBit {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#eightBitFormats")
        @DisplayName("returns every 8-bit sample")
        void identity(ImageFormat format, Case test) {
            var picture = picture(test);
            assertIdentity(format + " " + test, picture, Images.encode(picture, format, ImageEncoderOptions.lossless()),
                    false);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#eightBit")
        @DisplayName("WebP returns every visible sample and every alpha, and with exact coding every sample")
        void webp(Case test) {
            var picture = picture(test);
            var generic = Images.encode(picture, ImageFormat.WEBP, ImageEncoderOptions.lossless());
            var source = ImageOps.toRgba16(picture);
            var decoded = ImageOps.toRgba16(decode(generic));
            for (var index = 0; index < test.width() * test.height(); index++) {
                var alpha = source.pixels()[index * 4 + 3];
                assertEquals(alpha, decoded.pixels()[index * 4 + 3], test + " alpha of pixel " + index);
                if (alpha != 0) {
                    for (var channel = 0; channel < 3; channel++) {
                        assertEquals(source.pixels()[index * 4 + channel], decoded.pixels()[index * 4 + channel],
                                test + " channel " + channel + " of pixel " + index);
                    }
                }
            }
            var exact = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().exact(true).build());
            assertIdentity("exact WebP " + test, picture, exact, false);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#eightBit")
        @DisplayName("GIF returns every sample of a picture with at most 256 colors and binary alpha")
        void gif(Case test) {
            var picture = paletted(test);
            assertIdentity("GIF " + test, picture, Images.encode(picture, ImageFormat.GIF, ImageEncoderOptions.lossless()),
                    false);
        }
    }

    @Nested
    @DisplayName("a picture deeper than the format")
    class Shallow {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#shallowFormats")
        @DisplayName("is rejected, unless its samples are exactly 8-bit, which then round-trip")
        void rejected(ImageFormat format, Case test) {
            var deep = picture(test);
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> Images.encode(deep, format, ImageEncoderOptions.lossless()), format + " " + test);
            assertTrue(exception.getMessage().contains("cannot be coded losslessly"), exception.getMessage());
            // GIF needs a palette; WebP may recolor fully transparent pixels, so it gets an opaque picture.
            var shallow = switch (format) {
                case GIF -> paletted(test);
                case WEBP -> picture(new Case(test.width(), test.height(), PixelLayout.RGB, 8, test.content(), test.seed()));
                default -> picture(new Case(test.width(), test.height(), test.layout(), 8, test.content(), test.seed()));
            };
            var widened = widen(shallow);
            assertIdentity(format + " widened " + test, widened,
                    Images.encode(widened, format, ImageEncoderOptions.lossless()), false);
        }
    }

    @Nested
    @DisplayName("a format Smalt only decodes")
    class Reference {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#referenceCases")
        @DisplayName("JPEG XL encoded losslessly by libjxl decodes to every sample")
        void jxl(Case test) {
            var picture = picture(test);
            var png = Images.encode(picture, ImageFormat.PNG);
            var jxl = OracleEncoders.jxl(png, "lossless-" + test.seed());
            try {
                assertIdentity("JPEG XL " + test, picture, jxl, true);
            } catch (AssertionError failure) {
                throw new AssertionError(failure.getMessage() + " JXLDATA=" + java.util.Base64.getEncoder().encodeToString(jxl)
                        + " PNGDATA=" + java.util.Base64.getEncoder().encodeToString(png), failure);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#cjpegCases")
        @DisplayName("lossless JPEG encoded by libjpeg-turbo decodes to every sample, every predictor")
        void cjpeg(Case test) {
            OracleEncoders.assumeCjpegLossless();
            var picture = picture(test);
            var predictor = 1 + (int) (test.seed() % 7);
            var jpeg = OracleEncoders.cjpegLossless(netpbm(picture), test.depth(), predictor, "ljpeg-" + test.seed());
            assertIdentity("lossless JPEG predictor " + predictor + " " + test, picture, jpeg, true);
        }

        @ParameterizedTest(name = "{0} {1}x{2}")
        @MethodSource("com.github.auties00.smalt.property.LosslessImageTest#ffmpegLjpegCases")
        @DisplayName("lossless YCbCr JPEG encoded by ffmpeg decodes to every plane sample")
        void ffmpegYcbcr(String pixelFormat, int width, int height) {
            OracleEncoders.assumeFfmpegEncoder("ljpeg");
            var random = new Random(width * 31L + height);
            var subsampled = pixelFormat.equals("yuvj420p");
            var chromaWidth = subsampled ? (width + 1) / 2 : width;
            var chromaHeight = subsampled ? (height + 1) / 2 : height;
            var planes = new byte[][]{new byte[width * height], new byte[chromaWidth * chromaHeight],
                    new byte[chromaWidth * chromaHeight]};
            for (var plane : planes) {
                random.nextBytes(plane);
            }
            var raw = OracleEncoders.write("ljpeg-" + pixelFormat + "-" + width + "x" + height + ".yuv",
                    concat(planes));
            var output = OracleEncoders.path("ljpeg-" + pixelFormat + "-" + width + "x" + height + ".jpg");
            OracleEncoders.ffmpeg(List.of("-f", "rawvideo", "-pix_fmt", pixelFormat, "-s", width + "x" + height,
                    "-i", raw.toString(), "-c:v", "ljpeg", "-f", "image2"), output);
            var decoded = decode(OracleEncoders.read(output));
            if (!(decoded instanceof com.github.auties00.smalt.image.YuvPicture yuv)) {
                throw new AssertionError("lossless YCbCr JPEG decoded to " + decoded.getClass().getSimpleName());
            }
            assertPlane("Y", planes[0], width, height, yuv.y(), yuv.yStride());
            assertPlane("Cb", planes[1], chromaWidth, chromaHeight, yuv.u(), yuv.uvStride());
            assertPlane("Cr", planes[2], chromaWidth, chromaHeight, yuv.v(), yuv.uvStride());
        }
    }

    static Stream<Case> cjpegCases() {
        return cases(8, 16).filter(test -> (test.layout() == PixelLayout.RGB || test.layout() == PixelLayout.GRAY)
                && test.width() * test.height() > 1);
    }

    static Stream<Arguments> ffmpegLjpegCases() {
        var arguments = new ArrayList<Arguments>();
        for (var format : List.of("yuvj444p", "yuvj420p")) {
            for (var size : SIZES) {
                if (format.equals("yuvj420p") && (size[0] % 2 != 0 || size[1] % 2 != 0)) {
                    continue;
                }
                arguments.add(Arguments.of(format, size[0], size[1]));
            }
        }
        return arguments.stream();
    }

    private static void assertPlane(String name, byte[] expected, int width, int height, byte[] actual, int stride) {
        for (var y = 0; y < height; y++) {
            assertArrayEquals(Arrays.copyOfRange(expected, y * width, (y + 1) * width),
                    Arrays.copyOfRange(actual, y * stride, y * stride + width), name + " row " + y);
        }
    }

    private static byte[] concat(byte[][] parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Returns a picture as a binary PGM (gray) or PPM (RGB) file, big-endian samples above 8 bits. */
    static byte[] netpbm(Picture picture) {
        var gray = picture instanceof com.github.auties00.smalt.image.ChannelPicture channels
                && channels.layout().channels().size() == 1;
        var deep = picture.bitDepth() > 8;
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(((gray ? "P5" : "P6") + "\n" + picture.width() + " " + picture.height() + "\n"
                + (deep ? 65535 : 255) + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var rgba = ImageOps.toRgba16(picture);
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var c = 0; c < (gray ? 1 : 3); c++) {
                    var value = rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFFFF;
                    if (deep) {
                        out.write(value >>> 8);
                        out.write(value);
                    } else {
                        out.write(value >>> 8);
                    }
                }
            }
        }
        return out.toByteArray();
    }

    static Stream<Case> referenceCases() {
        return cases(8, 16).filter(test -> test.width() * test.height() > 1 && test.content() != Content.FLAT
                && test.seed() % 3 == 0);
    }

    @Test
    @DisplayName("the exactly 8-bit check of a deep picture looks at every sample")
    void oneSampleOff() {
        var deep = (Channel16Picture) widen(picture(new Case(9, 4, PixelLayout.RGBA, 8, Content.NOISE, 3)));
        deep.pixels()[deep.pixels().length - 2] += 1;
        assertThrows(MediaException.Unsupported.class, () -> Images.encode(deep, ImageFormat.WEBP,
                ImageEncoderOptions.lossless()));
    }

    static void assertIdentity(String what, Picture source, byte[] encoded, boolean keepsDepth) {
        var decoded = decode(encoded);
        assertEquals(source.width(), decoded.width(), what + " width");
        assertEquals(source.height(), decoded.height(), what + " height");
        if (keepsDepth) {
            assertTrue(decoded.bitDepth() >= source.bitDepth(), what + " decoded at " + decoded.bitDepth() + " bits");
        }
        var want = ImageOps.toRgba16(source);
        var got = ImageOps.toRgba16(decoded);
        var row = source.width() * 4;
        for (var y = 0; y < source.height(); y++) {
            var expected = Arrays.copyOfRange(want.pixels(), y * want.stride(), y * want.stride() + row);
            var actual = Arrays.copyOfRange(got.pixels(), y * got.stride(), y * got.stride() + row);
            assertArrayEquals(expected, actual, what + " row " + y);
        }
    }

    static Picture decode(byte[] encoded) {
        return Images.decode(MediaSource.of(encoded), ImageDecoderOptions.builder().highBitDepth(true).keepChannels(true)
                .build()).picture();
    }

    static Picture picture(Case test) {
        var channels = test.layout().channels().size();
        var alpha = channels == 2 || channels == 4;
        var random = new Random(test.seed());
        var max = test.depth() == 8 ? 255 : 65535;
        var samples = new int[test.width() * test.height() * channels];
        for (var y = 0; y < test.height(); y++) {
            for (var x = 0; x < test.width(); x++) {
                for (var c = 0; c < channels; c++) {
                    var index = (y * test.width() + x) * channels + c;
                    samples[index] = switch (test.content()) {
                        case NOISE -> random.nextInt(max + 1);
                        case GRADIENT -> (int) ((long) max * (x * 3 + y * 5 + c * 7) / (3L * test.width() + 5L
                                * test.height() + 21));
                        case FLAT -> c == channels - 1 && alpha ? max : max / 3;
                        case EXTREMES -> (x + y + c) % 2 == 0 ? 0 : max;
                    };
                }
            }
        }
        if (test.depth() == 8) {
            var picture = Channel8Picture.allocate(test.width(), test.height(), test.layout());
            for (var index = 0; index < samples.length; index++) {
                picture.pixels()[index] = (byte) samples[index];
            }
            return picture;
        }
        var picture = Channel16Picture.allocate(test.width(), test.height(), test.layout());
        for (var index = 0; index < samples.length; index++) {
            picture.pixels()[index] = (short) samples[index];
        }
        return picture;
    }

    /** Returns an 8-bit picture of at most 64 colors with alpha 0 or 255, which GIF holds exactly. */
    static Picture paletted(Case test) {
        var random = new Random(test.seed());
        var picture = RgbaPicture.allocate(test.width(), test.height(), true);
        var pixels = picture.pixels();
        for (var index = 0; index < test.width() * test.height(); index++) {
            var color = random.nextInt(64);
            var transparent = random.nextInt(8) == 0;
            pixels[index * 4] = (byte) (transparent ? 0 : (color & 3) * 85);
            pixels[index * 4 + 1] = (byte) (transparent ? 0 : (color >> 2 & 3) * 85);
            pixels[index * 4 + 2] = (byte) (transparent ? 0 : (color >> 4 & 3) * 85);
            pixels[index * 4 + 3] = (byte) (transparent ? 0 : 255);
        }
        return picture;
    }

    /** Returns the 16-bit RGBA picture whose samples are those of an 8-bit one, widened as {@code v * 257}. */
    static Picture widen(Picture picture) {
        var rgba = ImageOps.toRgba16(picture);
        var out = Channel16Picture.allocate(rgba.width(), rgba.height(), PixelLayout.RGBA);
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out.pixels(), y * rgba.width() * 4, rgba.width() * 4);
        }
        return out;
    }

    private static String name(PixelLayout layout) {
        return switch (layout.channels().size()) {
            case 1 -> "gray";
            case 2 -> "gray+alpha";
            case 3 -> "RGB";
            default -> "RGBA";
        };
    }
}
