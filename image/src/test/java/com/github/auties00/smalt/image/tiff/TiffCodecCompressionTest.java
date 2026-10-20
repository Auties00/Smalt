package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the TIFF compressions that store complete WebP, JPEG XL or JPEG 2000 images per strip or
 * tile: vectors written by libwebp, libjxl and OpenJPEG through tifffile (see
 * tools/testdata/tiff-codecs) against the samples those libraries decode, libtiff's tiffcp and
 * tiff2rgba as black-box writer and reader of WebP pages, ffmpeg decoding the codestreams of the
 * JPEG 2000 pages Smalt writes, Aperio slides against OpenSlide's rendering, the Aperio YCbCr
 * conversion against libtiff's, and the rejection of the layouts these compressions cannot hold.
 */
class TiffCodecCompressionTest {
    private static final String VECTORS = "/com/github/auties00/smalt/image/tiff/codecs/";

    private static final ImageDecoderOptions SAMPLES = ImageDecoderOptions.builder().highBitDepth(true).keepChannels(true)
            .build();

    // libjxl and Smalt's decoder render lossy JPEG XL with different float arithmetic
    private static final Map<String, Integer> TOLERANCE = Map.of("jxl-rgb8-lossy", 1);

    static Stream<String> vectors() {
        var manifest = new String(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/codecs.sha256"),
                java.nio.charset.StandardCharsets.US_ASCII);
        return manifest.lines().map(line -> line.substring(line.indexOf("  ") + 2))
                .filter(name -> !name.endsWith(".ref.tif")).map(name -> name.substring(0, name.length() - 4));
    }

    // OpenSlide's OpenJPEG build rounds the irreversible component transform of 7 samples of this level
    // differently from OpenJPEG 2.5.4, whose tile decoding the level matches exactly
    private static final Map<String, Integer> OPENSLIDE_TOLERANCE = Map.of("openslide-aperio-cmu-1-jp2k-33005.svs", 1);

    static double[] bytes(byte[] data) {
        var out = new double[data.length];
        for (var i = 0; i < data.length; i++) {
            out[i] = data[i] & 0xFF;
        }
        return out;
    }

    static byte[] vector(String name) {
        return TiffJpeg12Test.resource(VECTORS + name);
    }

    static Picture decode(byte[] tiff) {
        return TiffDecoder.decode(MediaSource.of(tiff), SAMPLES).picture();
    }

    /** Flattens the samples of a picture, row by row, without the stride padding. */
    static double[] samples(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        return switch (picture) {
            case RgbaPicture rgba -> flatten(width * 4, height, rgba.stride(), i -> rgba.pixels()[i] & 0xFF);
            case Rgba16Picture rgba -> flatten(width * 4, height, rgba.stride(), i -> rgba.pixels()[i] & 0xFFFF);
            case Channel8Picture channels -> flatten(width * channels.channelCount(), height, channels.stride(),
                    i -> channels.pixels()[i] & 0xFF);
            case Channel16Picture channels -> flatten(width * channels.channelCount(), height, channels.stride(),
                    i -> channels.pixels()[i] & 0xFFFF);
            case ChannelFloatPicture channels -> flatten(width * channels.channelCount(), height, channels.stride(),
                    i -> channels.pixels()[i]);
            default -> throw new AssertionError(picture.getClass().toString());
        };
    }

    private static double[] flatten(int rowSamples, int height, int stride, java.util.function.IntToDoubleFunction sample) {
        var out = new double[rowSamples * height];
        for (var y = 0; y < height; y++) {
            for (var i = 0; i < rowSamples; i++) {
                out[y * rowSamples + i] = sample.applyAsDouble(y * stride + i);
            }
        }
        return out;
    }

    static void assertSamples(double[] expected, double[] actual, double tolerance, String what) {
        assertEquals(expected.length, actual.length, what + ": sample count");
        var worst = 0.0;
        var at = -1;
        for (var i = 0; i < expected.length; i++) {
            var difference = Math.abs(expected[i] - actual[i]);
            if (difference > worst || Double.isNaN(difference)) {
                worst = Double.isNaN(difference) ? Double.POSITIVE_INFINITY : difference;
                at = i;
            }
        }
        if (worst > tolerance) {
            fail(what + ": max difference " + worst + " at sample " + at + ", expected " + expected[at] + " got "
                    + actual[at]);
        }
    }

    static RgbaPicture rgb(int width, int height, boolean alpha, long seed) {
        return TiffEncoderTest.picture(width, height, alpha, false, seed);
    }

    /** A smooth picture, on which lossy coding reaches a high PSNR. */
    static RgbaPicture smooth(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (128 + 100 * Math.sin(x / 9.0));
                pixels[at + 1] = (byte) (128 + 100 * Math.cos(y / 7.0));
                pixels[at + 2] = (byte) ((x + y) * 2);
                pixels[at + 3] = (byte) (alpha ? 255 - x * 2 : 255);
            }
        }
        return picture;
    }

    static double psnr(byte[] expected, byte[] actual) {
        var error = 0.0;
        for (var i = 0; i < expected.length; i++) {
            var difference = (expected[i] & 0xFF) - (actual[i] & 0xFF);
            error += difference * difference;
        }
        return error == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 * expected.length / error);
    }

    @Nested
    @DisplayName("vectors written by libwebp, libjxl and OpenJPEG")
    class Vectors {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffCodecCompressionTest#vectors")
        @DisplayName("decode to the samples the coding library decodes")
        void decodesLikeTheLibrary(String name) {
            var expected = samples(decode(vector(name + ".ref.tif")));
            var actual = samples(decode(vector(name + ".tif")));
            assertSamples(expected, actual, TOLERANCE.getOrDefault(name, 0), name);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffCodecCompressionTest#vectors")
        @DisplayName("report their compression scheme")
        void reportCompression(String name) {
            var expected = name.startsWith("webp") ? TiffCompression.WEBP
                    : name.startsWith("jxl") ? TiffCompression.JPEG_XL : TiffCompression.JPEG_2000;
            try (var pages = TiffDecoder.openPages(MediaSource.of(vector(name + ".tif")))) {
                assertEquals(expected, pages.page(0).compression());
            }
        }

        @Test
        @DisplayName("the lossless vectors decode to their source pattern at full precision")
        void losslessAtFullPrecision() {
            // the 16-bit reference keeps every bit, so a codec path that narrowed samples would differ from it
            var picture = decode(vector("jxl-gray16-tiles-be.tif"));
            assertInstanceOf(Rgba16Picture.class, picture);
            var values = samples(picture);
            assertTrue(java.util.Arrays.stream(values).anyMatch(v -> v % 257 != 0), "16-bit samples were narrowed");
            var floats = decode(vector("jxl-gray-float32.tif"));
            assertInstanceOf(ChannelFloatPicture.class, floats);
        }

        @Test
        @DisplayName("the Aperio, DNG and code aliases map to the named schemes")
        void aliases() {
            assertEquals(TiffCompression.JPEG_2000, TiffCompression.of(33003));
            assertEquals(TiffCompression.JPEG_2000, TiffCompression.of(33005));
            assertEquals(TiffCompression.JPEG_2000, TiffCompression.of(34712));
            assertEquals(TiffCompression.JPEG_XL, TiffCompression.of(52546));
            assertEquals(TiffCompression.JPEG_XL, TiffCompression.of(50002));
            assertEquals(TiffCompression.WEBP, TiffCompression.of(50001));
        }
    }

    @Nested
    @DisplayName("WebP pages against libtiff")
    class LibTiffWebp {
        static Stream<String> layouts() {
            return Stream.of("-r 9", "-t -w 32 -l 16", "-r 64", "-t -w 16 -l 32");
        }

        @Test
        @DisplayName("tiffcp's bit-reversed (FillOrder 2) WebP pages decode to their source exactly")
        void bitReversed() {
            // libtiff writes the WebP data of FillOrder 2 bit-reversed, but its reader does not reverse
            // it back, so tiff2rgba cannot serve as the oracle here
            LibTiff.assume("tiffcp");
            var source = rgb(61, 45, true, 7);
            var base = TiffEncoder.encode(source, TiffEncoderOptions.uncompressed().toBuilder()
                    .layout(TiffEncoderOptions.Layout.RGBA).build());
            var webp = LibTiff.tiffcp(base, List.of("-c", "webp:p100", "-f", "lsb2msb", "-t", "-w", "16", "-l", "32"));
            assertEquals((byte) 0x4A, CodedTiff.read(webp).getFirst()[0], "the stored data is bit-reversed");
            var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(webp)).picture();
            RasterTesting.assertSameRgba(RasterTesting.tight(source), RasterTesting.tight(decoded), source.width(),
                    "FillOrder 2");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layouts")
        @DisplayName("tiffcp's lossless WebP pages decode to their source exactly")
        void lossless(String layout) {
            LibTiff.assume("tiffcp");
            for (var alpha : new boolean[]{false, true}) {
                var source = rgb(61, 45, alpha, alpha ? 3 : 4);
                var base = TiffEncoder.encode(source, TiffEncoderOptions.uncompressed().toBuilder()
                        .layout(alpha ? TiffEncoderOptions.Layout.RGBA : TiffEncoderOptions.Layout.RGB).build());
                var options = new ArrayList<>(List.of("-c", "webp:p100"));
                options.addAll(List.of(layout.split(" ")));
                var webp = LibTiff.tiffcp(base, options);
                var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(webp)).picture();
                RasterTesting.assertSameRgba(RasterTesting.tight(source), RasterTesting.tight(decoded), source.width(),
                        layout + (alpha ? " rgba" : " rgb"));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layouts")
        @DisplayName("tiffcp's lossy WebP pages decode like libtiff's tiff2rgba")
        void lossy(String layout) {
            LibTiff.assume("tiffcp", "tiff2rgba");
            for (var alpha : new boolean[]{false, true}) {
                var base = TiffEncoder.encode(rgb(61, 45, alpha, 5), TiffEncoderOptions.uncompressed().toBuilder()
                        .layout(alpha ? TiffEncoderOptions.Layout.RGBA : TiffEncoderOptions.Layout.RGB).build());
                var options = new ArrayList<>(List.of("-c", "webp:p70"));
                options.addAll(List.of(layout.split(" ")));
                var webp = LibTiff.tiffcp(base, options);
                var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(webp)).picture();
                var expected = LibTiff.rgba(webp);
                assertNotNull(expected, "tiff2rgba failed");
                assertArrayEquals(expected, LibTiff.premultiply(RasterTesting.tight(decoded)), layout);
            }
        }
    }

    @Nested
    @DisplayName("the encoder")
    class Encoder {
        static Stream<TiffEncoderOptions> lossless() {
            var webp = TiffEncoderOptions.builder().compression(TiffCompression.WEBP).build();
            var jp2 = TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000).build();
            return Stream.of(webp, webp.toBuilder().tiles(32, 16).build(), webp.toBuilder().strips(7).bigEndian(true).build(),
                    jp2, jp2.toBuilder().tiles(32, 16).build(), jp2.toBuilder().strips(7).bigEndian(true).bigTiff(true).build(),
                    jp2.toBuilder().depth(TiffEncoderOptions.Depth.SIXTEEN).tiles(48, 32).build(),
                    jp2.toBuilder().layout(TiffEncoderOptions.Layout.GRAY).build());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("lossless")
        @DisplayName("writes lossless WebP and JPEG 2000 pages that decode to the source")
        void roundTrip(TiffEncoderOptions options) {
            for (var source : new RgbaPicture[]{rgb(53, 37, true, 1), rgb(53, 37, false, 2),
                    TiffEncoderTest.picture(53, 37, false, true, 3), rgb(1, 1, false, 4)}) {
                var encoded = TiffEncoder.encode(source, options);
                var decoded = TiffDecoder.decode(MediaSource.of(encoded), ImageDecoderOptions.builder().highBitDepth(true)
                        .build()).picture();
                // the uncompressed page holds the same samples (the luma of a gray layout) for the same options
                var plain = TiffEncoder.encode(source, options.toBuilder().compression(TiffCompression.NONE)
                        .layout(options.compression() == TiffCompression.WEBP && !source.hasAlpha()
                                ? TiffEncoderOptions.Layout.RGB : options.layout()).build());
                var expected = TiffDecoder.decode(MediaSource.of(plain), ImageDecoderOptions.builder().highBitDepth(true)
                        .build()).picture();
                assertSamples(samples(expected), samples(decoded), 0, options.toString());
                if (options.compression() == TiffCompression.WEBP) {
                    try (var pages = TiffDecoder.openPages(MediaSource.of(encoded))) {
                        assertTrue(pages.page(0).samplesPerPixel() >= 3, "WebP pages are RGB or RGBA");
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps the colors of transparent pixels in lossless WebP pages")
        void exactTransparentColors() {
            var source = rgb(40, 30, true, 9);
            var pixels = source.pixels();
            for (var i = 3; i < pixels.length; i += 16) {
                pixels[i] = 0;
            }
            var encoded = TiffEncoder.encode(source, TiffEncoderOptions.builder().compression(TiffCompression.WEBP).build());
            var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(encoded)).picture();
            assertArrayEquals(RasterTesting.tight(source), RasterTesting.tight(decoded));
        }

        @Test
        @DisplayName("writes WebP pages libtiff's tiff2rgba reads like Smalt, lossless and lossy")
        void webpInterop() {
            LibTiff.assume("tiff2rgba");
            var source = smooth(70, 50, true);
            var lossless = TiffEncoder.encode(source, TiffEncoderOptions.builder().compression(TiffCompression.WEBP)
                    .tiles(32, 32).build());
            assertArrayEquals(LibTiff.premultiply(RasterTesting.tight(source)), LibTiff.rgba(lossless));
            var lossy = TiffEncoder.encode(source, TiffEncoderOptions.builder().compression(TiffCompression.WEBP)
                    .webpOptions(WebpEncoderOptions.lossy(80)).strips(16).build());
            var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(lossy)).picture();
            assertArrayEquals(LibTiff.rgba(lossy), LibTiff.premultiply(RasterTesting.tight(decoded)));
            var quality = psnr(RasterTesting.tight(source), RasterTesting.tight(decoded));
            assertTrue(quality > 35, "PSNR " + quality);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"rgb24,8,3", "rgba,8,4", "gray,8,1", "gray16le,16,1", "rgba64le,16,4"})
        @DisplayName("writes JPEG 2000 codestreams that ffmpeg decodes to the source tiles")
        void jpeg2000Interop(String pixelFormat, int bits, int channels) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var width = 50;
            var height = 41;
            var values = TiffFiles.pattern(width, height, channels, (1L << bits) - 1, bits + channels);
            var layout = switch (channels) {
                case 1 -> PixelLayout.GRAY;
                case 3 -> PixelLayout.RGB;
                default -> PixelLayout.RGBA;
            };
            Picture picture;
            if (bits == 8) {
                var pixels = new byte[values.length];
                for (var i = 0; i < values.length; i++) {
                    pixels[i] = (byte) values[i];
                }
                picture = Channel8Picture.of(width, height, width * channels, pixels, layout);
            } else {
                var pixels = new short[values.length];
                for (var i = 0; i < values.length; i++) {
                    pixels[i] = (short) values[i];
                }
                picture = Channel16Picture.of(width, height, width * channels, pixels, layout);
            }
            var tiff = TiffEncoder.encode(picture, TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000)
                    .tiles(32, 32).build());
            var tiles = CodedTiff.read(tiff);
            assertEquals(4, tiles.size());
            var bytes = bits / 8;
            for (var t = 0; t < tiles.size(); t++) {
                var file = RasterTesting.write("tiff-jp2-" + pixelFormat + "-" + t + ".j2k", tiles.get(t));
                var decoded = RasterTesting.ffmpegFrames(file, pixelFormat);
                assertEquals(32 * 32 * channels * bytes, decoded.length, "tile " + t);
                var x0 = (t % 2) * 32;
                var y0 = (t / 2) * 32;
                for (var y = 0; y < 32 && y0 + y < height; y++) {
                    for (var x = 0; x < 32 && x0 + x < width; x++) {
                        for (var c = 0; c < channels; c++) {
                            var at = ((y * 32 + x) * channels + c) * bytes;
                            var value = bytes == 1 ? decoded[at] & 0xFF : (decoded[at] & 0xFF) | (decoded[at + 1] & 0xFF) << 8;
                            assertEquals(values[((y0 + y) * width + x0 + x) * channels + c], value,
                                    "tile " + t + " pixel " + x + "," + y + " channel " + c);
                        }
                    }
                }
            }
            var plain = TiffEncoder.encode(picture, TiffEncoderOptions.uncompressed());
            assertSamples(samples(decode(plain)), samples(decode(tiff)), 0, "Smalt round trip");
        }

        @Test
        @DisplayName("writes channel pictures losslessly as JPEG 2000: CMYK, named inks and extra samples")
        void jpeg2000Channels() {
            var width = 37;
            var height = 29;
            var cmyk = TiffFiles.pattern(width, height, 5, 65535, 21);
            var wide = new short[cmyk.length];
            for (var i = 0; i < cmyk.length; i++) {
                wide[i] = (short) cmyk[i];
            }
            var layouts = List.of(PixelLayout.CMYKA, PixelLayout.ofInks(List.of("Cyan", "Spot", "Gold", "Black", "Varnish")));
            for (var layout : layouts) {
                var picture = Channel16Picture.of(width, height, width * 5, wide, layout);
                var tiff = TiffEncoder.encode(picture, TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000)
                        .tiles(16, 16).build());
                var decoded = decode(tiff);
                assertInstanceOf(Channel16Picture.class, decoded);
                assertEquals(layout, ((Channel16Picture) decoded).layout());
                assertSamples(samples(picture), samples(decoded), 0, layout.toString());
            }
        }

        @Test
        @DisplayName("writes lossy JPEG 2000 pages close to the source")
        void jpeg2000Lossy() {
            var source = smooth(96, 64, false);
            var tiff = TiffEncoder.encode(source, TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000)
                    .jpeg2000Options(Jp2EncoderOptions.lossy(8)).tiles(32, 32).build());
            var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(tiff)).picture();
            var plain = TiffEncoder.encode(source, TiffEncoderOptions.uncompressed()).length;
            assertTrue(tiff.length < plain / 4, tiff.length + " of " + plain + " bytes");
            var quality = psnr(RasterTesting.tight(source), RasterTesting.tight(decoded));
            assertTrue(quality > 35, "PSNR " + quality);
        }

        @Test
        @DisplayName("converts channel pictures and 16-bit pictures to 8-bit RGBA under WebP")
        void webpConverts() {
            var wide = TiffEncoderTest.picture16(30, 20, true, 6);
            var tiff = TiffEncoder.encode(wide, TiffEncoderOptions.builder().compression(TiffCompression.WEBP).build());
            var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(tiff)).picture();
            assertArrayEquals(RasterTesting.tight(com.github.auties00.smalt.image.ops.ImageOps.toEightBit(wide)),
                    RasterTesting.tight(decoded));
            var channels = Channel8Picture.of(4, 3, 4 * 4, new byte[48], PixelLayout.CMYK);
            var cmyk = TiffEncoder.encode(channels, TiffEncoderOptions.builder().compression(TiffCompression.WEBP).build());
            try (var pages = TiffDecoder.openPages(MediaSource.of(cmyk))) {
                assertEquals(TiffPhotometric.RGB, pages.page(0).photometric());
            }
        }

        @Test
        @DisplayName("rejects settings and pictures the compressions cannot hold")
        void rejects() {
            var webp = TiffEncoderOptions.builder().compression(TiffCompression.WEBP);
            assertThrows(IllegalArgumentException.class, () -> webp.layout(TiffEncoderOptions.Layout.GRAY).build());
            assertThrows(IllegalArgumentException.class, () -> webp.layout(TiffEncoderOptions.Layout.GRAY_ALPHA).build());
            assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.builder()
                    .compression(TiffCompression.WEBP).depth(TiffEncoderOptions.Depth.SIXTEEN).build());
            assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.builder()
                    .compression(TiffCompression.JPEG_XL).build());
            var floats = ChannelFloatPicture.of(2, 2, 2, new float[4], PixelLayout.GRAY);
            var jp2 = TiffEncoderOptions.builder().compression(TiffCompression.JPEG_2000).build();
            assertThrows(IllegalArgumentException.class, () -> TiffEncoder.encode(floats, jp2));
            var sixteen = TiffEncoder.encode(floats, jp2.toBuilder().depth(TiffEncoderOptions.Depth.SIXTEEN).build());
            assertInstanceOf(Rgba16Picture.class, decode(sixteen));
            // a WebP strip spans the page width, which WebP caps at 16383 pixels; tiles lift the cap
            var wide = RgbaPicture.allocate(16384, 2, false);
            var webpOptions = TiffEncoderOptions.builder().compression(TiffCompression.WEBP).build();
            assertThrows(MediaException.LimitExceeded.class, () -> TiffEncoder.encode(wide, webpOptions));
            var tiles = TiffEncoder.encode(wide, webpOptions.toBuilder().tiles(1024, 16).build());
            assertEquals(16384, decode(tiles).width());
            assertThrows(NullPointerException.class, () -> TiffEncoderOptions.builder().webpOptions(null));
            assertThrows(NullPointerException.class, () -> TiffEncoderOptions.builder().jpeg2000Options(null));
        }

        @Test
        @DisplayName("options carry the codec settings through toBuilder, equality and their description")
        void options() {
            var webp = WebpEncoderOptions.lossy(42);
            var jp2 = Jp2EncoderOptions.lossy(12);
            var options = TiffEncoderOptions.builder().compression(TiffCompression.WEBP).webpOptions(webp)
                    .jpeg2000Options(jp2).build();
            assertEquals(webp, options.webpOptions());
            assertEquals(jp2, options.jpeg2000Options());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().webpOptions(WebpEncoderOptions.lossless()).build());
            assertNotEquals(options, options.toBuilder().jpeg2000Options(Jp2EncoderOptions.lossless()).build());
            assertTrue(options.toString().contains("webpOptions"));
            assertTrue(TiffEncoderOptions.defaults().webpOptions().isLossless());
            assertTrue(TiffEncoderOptions.defaults().webpOptions().exact());
            assertEquals(Jp2EncoderOptions.lossless(), TiffEncoderOptions.defaults().jpeg2000Options());
        }

        @Test
        @DisplayName("writes raw codestreams whatever the JPEG 2000 format option says")
        void codestreams() {
            var tiff = TiffEncoder.encode(rgb(20, 20, false, 13), TiffEncoderOptions.builder()
                    .compression(TiffCompression.JPEG_2000).jpeg2000Options(Jp2EncoderOptions.builder()
                            .format(Jp2Format.JP2).build()).build());
            var chunk = CodedTiff.read(tiff).getFirst();
            assertEquals((byte) 0xFF, chunk[0]);
            assertEquals((byte) 0x4F, chunk[1]);
        }
    }

    @Nested
    @DisplayName("Aperio JPEG 2000")
    class Aperio {
        /** Converts RGB to full-range BT.601 YCbCr, the chroma of each pixel pair taken from its left pixel. */
        static long[] ycbcr(RgbaPicture source) {
            var width = source.width();
            var height = source.height();
            var pixels = RasterTesting.tight(source);
            var out = new long[width * height * 3];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = (y * width + x) * 4;
                    var left = (y * width + (x & ~1)) * 4;
                    out[(y * width + x) * 3] = luma(pixels, at);
                    out[(y * width + x) * 3 + 1] = chroma(pixels, left, -0.168736, -0.331264, 0.5);
                    out[(y * width + x) * 3 + 2] = chroma(pixels, left, 0.5, -0.418688, -0.081312);
                }
            }
            return out;
        }

        private static long luma(byte[] p, int at) {
            return Math.clamp(Math.round(0.299 * (p[at] & 0xFF) + 0.587 * (p[at + 1] & 0xFF) + 0.114 * (p[at + 2] & 0xFF)), 0, 255);
        }

        private static long chroma(byte[] p, int at, double r, double g, double b) {
            return Math.clamp(Math.round(128 + r * (p[at] & 0xFF) + g * (p[at + 1] & 0xFF) + b * (p[at + 2] & 0xFF)), 0, 255);
        }

        /** Codes each tile as a codestream of a full Y component and Cb and Cr subsampled 2:1 horizontally. */
        static List<byte[]> tiles(long[] ycc, int width, int height, int tile) {
            var options = Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM).colorTransform(false).build();
            var tiles = new ArrayList<byte[]>();
            for (var ty = 0; ty < height; ty += tile) {
                for (var tx = 0; tx < width; tx += tile) {
                    var channels = new ArrayList<Jp2Channel>();
                    for (var c = 0; c < 3; c++) {
                        var step = c == 0 ? 1 : 2;
                        var columns = (tile + step - 1) / step;
                        var values = new int[columns * tile];
                        for (var y = 0; y < tile; y++) {
                            for (var x = 0; x < columns; x++) {
                                var px = Math.min(tx + x * step, width - 1);
                                var py = Math.min(ty + y, height - 1);
                                values[y * columns + x] = (int) ycc[(py * width + px) * 3 + c];
                            }
                        }
                        channels.add(new Jp2Channel(0, 0, columns, tile, step, 1, 8, false, Jp2ChannelType.COLOR, c + 1, values));
                    }
                    tiles.add(Jp2Encoder.encode(new Jp2Image(0, 0, tile, tile, tile, tile, 0, channels, Jp2ColorSpace.UNSPECIFIED,
                            Optional.empty(), Optional.empty(), Optional.empty()), options));
                }
            }
            return tiles;
        }

        @ParameterizedTest(name = "compression {0}")
        @ValueSource(ints = {33003, 34712})
        @DisplayName("subsampled YCbCr codestreams convert to RGB as libtiff converts YCbCr")
        void ycbcrConversion(int compression) {
            var width = 100;
            var height = 70;
            var ycc = ycbcr(rgb(width, height, false, 14));
            var coded = CodedTiff.of(width, height, 3, 8, compression).photometric(compression == 33003 ? 2 : 6)
                    .tiles(64, 64).chunks(tiles(ycc, width, height, 64)).bytes();
            var reference = TiffFiles.of(width, height, 3, 8).photometric(6).tag(530, 1, 1)
                    .data(TiffFiles.pack(ycc, width, height, 3, 8, true)).bytes();
            var expected = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(reference)).picture());
            if (LibTiff.find("tiff2rgba").isPresent()) {
                assertArrayEquals(LibTiff.rgba(reference), expected, "the uncompressed YCbCr reference differs from libtiff");
            }
            var actual = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(coded)).picture());
            assertArrayEquals(expected, actual);
        }

        @ParameterizedTest(name = "photometric {0}")
        @ValueSource(ints = {2, 6})
        @DisplayName("33005 channels are RGB whatever the photometric tag says")
        void rgbCodestreams(int photometric) {
            var values = TiffFiles.pattern(24, 16, 3, 255, 33);
            var coded = CodedTiff.of(24, 16, 3, 8, 33005).photometric(photometric).strips(16)
                    .chunks(List.of(Rejections.jpeg2000(24, 16, 3, values))).bytes();
            var pixels = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(coded)).picture());
            for (var i = 0; i < 24 * 16; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(values[i * 3 + c], pixels[i * 4 + c] & 0xFF, "pixel " + i + " channel " + c);
                }
            }
        }

        static Stream<String[]> slides() {
            return Stream.of(
                    new String[]{"openslide-aperio-cmu-1-jp2k-33005.svs", "3",
                            "https://openslide.cs.cmu.edu/download/openslide-testdata/Aperio/CMU-1-JP2K-33005.svs",
                            "9a1923cd9bcb260ba4d99d64f8d6e32550648c332ba48817f920662f3a513420"},
                    new String[]{"openslide-aperio-jp2k-33003-1.svs", "3",
                            "https://openslide.cs.cmu.edu/download/openslide-testdata/Aperio/JP2K-33003-1.svs",
                            "6205ccf75a8fa6c32df7c5c04b7377398971a490fb6b320d50d91f7ba6a0e6fd"});
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("slides")
        @DisplayName("pyramid levels of public Aperio slides decode as OpenSlide renders them")
        void slides(String name, String page, String url, String sha256) {
            var directory = Path.of(System.getProperty("smalt.test.svsOracle",
                    Conformance.directory().resolve("svs-oracle").toString()));
            var oracle = directory.resolve(name + "-page" + page + ".rgba");
            Assumptions.assumeTrue(Files.isRegularFile(oracle), "no OpenSlide rendering " + oracle
                    + " (tools/testdata/svs-oracle.py)");
            var slide = Conformance.fetch(name, url, sha256);
            byte[] expected;
            try {
                expected = Files.readAllBytes(oracle);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            try (var pages = TiffDecoder.openPages(MediaSource.of(slide), ImageDecoderOptions.builder()
                    .limits(MediaLimits.builder().maxBytes(1L << 30).build()).build())) {
                assertEquals(TiffCompression.JPEG_2000, pages.page(Integer.parseInt(page)).compression());
                var picture = (RgbaPicture) pages.read(Integer.parseInt(page)).picture();
                var actual = RasterTesting.tight(picture);
                assertSamples(bytes(expected), bytes(actual), OPENSLIDE_TOLERANCE.getOrDefault(name, 0), name + " vs OpenSlide");
                var openjpeg = directory.resolve(name + "-page" + page + ".openjpeg");
                if (Files.isRegularFile(openjpeg)) {
                    byte[] tiles;
                    try {
                        tiles = Files.readAllBytes(openjpeg);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                    var rgb = new byte[tiles.length];
                    for (int i = 0, o = 0; o < rgb.length; i += 4, o += 3) {
                        rgb[o] = actual[i];
                        rgb[o + 1] = actual[i + 1];
                        rgb[o + 2] = actual[i + 2];
                    }
                    assertArrayEquals(tiles, rgb, name + " vs OpenJPEG");
                }
            }
        }
    }

    @Nested
    @DisplayName("layouts the compressions cannot hold")
    class Rejections {
        static byte[] webp(int width, int height, boolean alpha) {
            return WebpEncoder.encode(rgb(width, height, alpha, 15), WebpEncoderOptions.lossless().toBuilder().exact(true)
                    .build());
        }

        static byte[] jpeg2000(int width, int height, int channels) {
            return jpeg2000(width, height, channels, new long[width * height * channels]);
        }

        /** Codes interleaved 8-bit samples as a lossless codestream. */
        static byte[] jpeg2000(int width, int height, int channels, long[] interleaved) {
            var list = new ArrayList<Jp2Channel>();
            for (var c = 0; c < channels; c++) {
                var plane = new int[width * height];
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (int) interleaved[i * channels + c];
                }
                list.add(new Jp2Channel(0, 0, width, height, 1, 1, 8, false, Jp2ChannelType.COLOR, -1, plane));
            }
            return Jp2Encoder.encode(new Jp2Image(0, 0, width, height, width, height, 0, list, Jp2ColorSpace.UNSPECIFIED,
                    Optional.empty(), Optional.empty(), Optional.empty()), Jp2EncoderOptions.builder()
                    .format(Jp2Format.CODESTREAM).build());
        }

        static Stream<Object[]> unsupported() {
            var chunk = List.of(new byte[16]);
            return Stream.of(
                    new Object[]{"16-bit WebP", CodedTiff.of(8, 8, 3, 16, 50001).chunks(chunk), "tiff webp layout"},
                    new Object[]{"gray WebP", CodedTiff.of(8, 8, 1, 8, 50001).chunks(chunk), "tiff webp layout"},
                    new Object[]{"planar WebP", CodedTiff.of(8, 8, 3, 8, 50001).planar().chunks(List.of(new byte[1], new byte[1],
                            new byte[1])), "tiff webp layout"},
                    new Object[]{"CMYK WebP", CodedTiff.of(8, 8, 4, 8, 50001).photometric(5).chunks(chunk), "tiff webp layout"},
                    new Object[]{"signed JPEG XL", CodedTiff.of(8, 8, 1, 16, 50002).format(2).chunks(chunk), "tiff jpeg xl 16-bit"},
                    new Object[]{"32-bit JPEG XL", CodedTiff.of(8, 8, 1, 32, 52546).chunks(chunk), "tiff jpeg xl 32-bit"},
                    new Object[]{"64-bit float JPEG XL", CodedTiff.of(8, 8, 1, 64, 50002).format(3).chunks(chunk),
                            "tiff jpeg xl 64-bit"},
                    new Object[]{"float JPEG 2000", CodedTiff.of(8, 8, 1, 32, 34712).format(3).chunks(chunk),
                            "tiff jpeg 2000 floating-point"},
                    new Object[]{"16-bit Aperio YCbCr", CodedTiff.of(8, 8, 3, 16, 33003).chunks(chunk), "tiff jpeg 2000 ycbcr"},
                    new Object[]{"planar JPEG 2000 YCbCr", CodedTiff.of(8, 8, 3, 8, 34712).photometric(6).planar()
                            .chunks(List.of(new byte[1], new byte[1], new byte[1])), "tiff jpeg 2000 ycbcr"});
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unsupported")
        @DisplayName("are rejected as unsupported, naming the feature")
        void unsupported(String what, CodedTiff tiff, String format) {
            var exception = assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.decode(MediaSource.of(tiff.bytes())),
                    what);
            assertEquals(format, exception.format(), what);
        }

        @Test
        @DisplayName("coded images with fewer channels than samples, or invalid data, are malformed")
        void malformed() {
            var grayInRgb = CodedTiff.of(16, 16, 3, 8, 34712).chunks(List.of(jpeg2000(16, 16, 1))).bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(grayInRgb)));
            var garbage = CodedTiff.of(16, 16, 3, 8, 50001).chunks(List.of(new byte[]{'R', 'I', 'F', 'F', 1, 2, 3}))
                    .bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(garbage)));
            var jxl = CodedTiff.of(16, 16, 1, 8, 50002).chunks(List.of(new byte[]{(byte) 0xFF, 0x0A, 0, 0})).bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(jxl)));
            // the 4-sample page takes its fourth sample from a channel the 3-channel image lacks
            var rgbInRgba = CodedTiff.of(32, 32, 4, 8, 50002).extra(2).chunks(List.of(vectorTile("jxl-rgb8-tiles.tif")))
                    .bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(rgbInRgba)));
        }

        @Test
        @DisplayName("coded images of another size fill what they cover and leave the rest zero")
        void sizeMismatch() {
            var small = CodedTiff.of(20, 12, 4, 8, 50001).extra(2).strips(12).chunks(List.of(webp(10, 6, true))).bytes();
            var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(small)).picture();
            var pixels = RasterTesting.tight(picture);
            var source = RasterTesting.tight(rgb(10, 6, true, 15));
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 20; x++) {
                    for (var c = 0; c < 4; c++) {
                        var expected = x < 10 && y < 6 ? source[(y * 10 + x) * 4 + c] : 0;
                        assertEquals(expected, pixels[(y * 20 + x) * 4 + c], x + "," + y + "," + c);
                    }
                }
            }
            // wider than the strip but within its area: cropped to the strip's columns
            var values = TiffFiles.pattern(24, 8, 3, 255, 16);
            var wide = CodedTiff.of(20, 12, 3, 8, 34712).strips(12).chunks(List.of(jpeg2000(24, 8, 3, values))).bytes();
            var cropped = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(wide)).picture());
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 20; x++) {
                    for (var c = 0; c < 3; c++) {
                        var expected = y < 8 ? (int) values[(y * 24 + x) * 3 + c] : 0;
                        assertEquals(expected, cropped[(y * 20 + x) * 4 + c] & 0xFF, x + "," + y + "," + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("coded images with more pixels than their strip or tile exceed the limits before decoding")
        void oversized() {
            // a 1x1 lossless WebP whose VP8L header is rewritten to 10000x10000, within the default limits
            var webp = webp(1, 1, false);
            assertEquals('L', webp[15], "a simple VP8L file");
            var header = (webp[21] & 0xFF) | (webp[22] & 0xFF) << 8 | (webp[23] & 0xFF) << 16 | (webp[24] & 0xFF) << 24;
            header = (header & ~0x0FFFFFFF) | 9999 | 9999 << 14;
            for (var i = 0; i < 4; i++) {
                webp[21 + i] = (byte) (header >>> (8 * i));
            }
            var tiled = CodedTiff.of(64, 64, 3, 8, 50001).tiles(32, 32).chunks(List.of(webp, webp, webp, webp)).bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> TiffDecoder.decode(MediaSource.of(tiled)));
            // a JPEG 2000 codestream whose SIZ marker is rewritten to the same image and tile size
            var codestream = jpeg2000(16, 16, 3);
            assertEquals((byte) 0x51, codestream[3], "SIZ follows SOC");
            for (var at : new int[]{8, 12, 24, 28}) {
                codestream[at] = 0;
                codestream[at + 1] = 0;
                codestream[at + 2] = (byte) (10000 >>> 8);
                codestream[at + 3] = (byte) 10000;
            }
            var strip = CodedTiff.of(16, 16, 3, 8, 34712).strips(16).chunks(List.of(codestream)).bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> TiffDecoder.decode(MediaSource.of(strip)));
        }

        static byte[] vectorTile(String name) {
            return CodedTiff.read(vector(name)).getFirst();
        }
    }

    @Test
    @DisplayName("mutated codec pages decode or raise MediaException, quickly")
    void mutations() {
        var seeds = new ArrayList<byte[]>();
        vectors().filter(name -> !name.contains("float") && !name.contains("16")).forEach(name -> seeds.add(vector(name + ".tif")));
        var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds() * 3), () ->
                RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(40), 0x7C0DEC, source -> {
                    try (var pages = TiffDecoder.openPages(source, ImageDecoderOptions.builder()
                            .limits(MediaLimits.builder().maxBytes(64 << 20).maxPixels(4 << 20).build()).build())) {
                        for (var i = 0; i < pages.pageCount(); i++) {
                            pages.read(i);
                        }
                    }
                }));
        assertTrue(stats.decoded() > 0 && stats.rejected() > 0, stats.toString());
    }
}
