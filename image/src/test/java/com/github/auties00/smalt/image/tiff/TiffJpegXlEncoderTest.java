package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jxl.JxlChannelType;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.jxl.JxlEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the JPEG XL pages the TIFF encoder writes (compression 50002, or DNG's 52546): lossless round trips
 * of every layout, depth and arrangement through Smalt's decoder, the channels of the coded images as GDAL
 * writes them, libjxl's djxl decoding the extracted strips and tiles to the source samples, libtiff's tiffinfo
 * reading the pages' structure, lossy coding and the options.
 */
class TiffJpegXlEncoderTest {
    private static final TiffEncoderOptions JXL = TiffEncoderOptions.builder().compression(TiffCompression.JPEG_XL)
            .build();

    private static final PixelLayout RGB_DEPTH = PixelLayout.RGB.withChannel(Channel.of(ChannelType.DEPTH));

    private static final PixelLayout INKS = PixelLayout.ofInks(List.of("Cyan", "Spot", "Gold"));

    /** Builds a channel picture of a layout and depth (8, 16, or 32 for floating point) over a noisy pattern. */
    static Picture picture(int width, int height, PixelLayout layout, int depth, long seed) {
        var count = layout.count();
        var stride = width * count;
        var max = depth == 8 ? 255 : 65535;
        var values = TiffFiles.pattern(width, height, count, max, seed);
        return switch (depth) {
            case 8 -> {
                var pixels = new byte[values.length];
                for (var i = 0; i < values.length; i++) {
                    pixels[i] = (byte) values[i];
                }
                yield Channel8Picture.of(width, height, stride, pixels, layout);
            }
            case 16 -> {
                var pixels = new short[values.length];
                for (var i = 0; i < values.length; i++) {
                    pixels[i] = (short) values[i];
                }
                yield Channel16Picture.of(width, height, stride, pixels, layout);
            }
            default -> {
                var random = new Random(seed);
                var pixels = new float[values.length];
                for (var i = 0; i < values.length; i++) {
                    pixels[i] = switch (random.nextInt(16)) {
                        case 0 -> -values[i] / 3000f;
                        case 1 -> values[i] * 1.0e6f;
                        case 2 -> Float.MIN_VALUE * (1 + random.nextInt(100));
                        case 3 -> -0.0f;
                        default -> values[i] / 65535f;
                    };
                }
                yield ChannelFloatPicture.of(width, height, stride, pixels, layout);
            }
        };
    }

    static Picture decode(byte[] tiff) {
        return TiffCodecCompressionTest.decode(tiff);
    }

    /** Asserts that two pictures hold the same samples, floating-point ones bit for bit. */
    static void assertSameSamples(Picture expected, Picture actual, String what) {
        assertEquals(expected.getClass(), actual.getClass(), what + ": picture type");
        if (expected instanceof ChannelFloatPicture e && actual instanceof ChannelFloatPicture a) {
            assertEquals(e.channelCount(), a.channelCount(), what + ": channels");
            for (var y = 0; y < e.height(); y++) {
                for (var i = 0; i < e.width() * e.channelCount(); i++) {
                    var left = Float.floatToRawIntBits(e.pixels()[y * e.stride() + i]);
                    var right = Float.floatToRawIntBits(a.pixels()[y * a.stride() + i]);
                    if (left != right) {
                        fail(what + ": row " + y + " sample " + i + " expected " + Float.intBitsToFloat(left)
                                + " got " + Float.intBitsToFloat(right));
                    }
                }
            }
            return;
        }
        TiffCodecCompressionTest.assertSamples(TiffCodecCompressionTest.samples(expected),
                TiffCodecCompressionTest.samples(actual), 0, what);
    }

    static Stream<Arguments> lossless() {
        var layouts = List.of(PixelLayout.GRAY, PixelLayout.GRAY_ALPHA, PixelLayout.RGB, PixelLayout.RGBA,
                PixelLayout.CMYK, PixelLayout.CMYKA, INKS, RGB_DEPTH);
        var arrangements = List.of(JXL, JXL.toBuilder().strips(7).build(), JXL.toBuilder().tiles(32, 16).build(),
                JXL.toBuilder().planar(true).tiles(16, 16).build(), JXL.toBuilder().bigEndian(true).strips(9).build(),
                JXL.toBuilder().bigTiff(true).dngJpegXlCode(true).build());
        var result = new ArrayList<Arguments>();
        var index = 0;
        for (var layout : layouts) {
            for (var depth : new int[]{8, 16, 32}) {
                result.add(Arguments.of(layout, depth, arrangements.get(index++ % arrangements.size())));
            }
        }
        for (var options : arrangements) {
            result.add(Arguments.of(PixelLayout.RGBA, 16, options));
        }
        return result.stream();
    }

    @ParameterizedTest(name = "{0}, {1} bits, {2}")
    @MethodSource("lossless")
    @DisplayName("lossless pages of every layout, depth and arrangement decode to the source sample for sample")
    void losslessChannels(PixelLayout layout, int depth, TiffEncoderOptions options) {
        var source = picture(53, 37, layout, depth, layout.hashCode() + depth);
        var tiff = TiffEncoder.encode(source, options);
        var decoded = decode(tiff);
        var plain = decode(TiffEncoder.encode(source, options.toBuilder().compression(TiffCompression.NONE).build()));
        assertSameSamples(plain, decoded, layout + " " + depth + " against the uncompressed page");
        if (decoded instanceof com.github.auties00.smalt.image.ChannelPicture channels) {
            assertEquals(((com.github.auties00.smalt.image.ChannelPicture) source).layout().count(),
                    channels.layout().count(), "channel count");
            assertSameSamples(source, decoded, layout + " " + depth + " against the source");
        }
        try (var pages = TiffDecoder.openPages(MediaSource.of(tiff))) {
            assertEquals(TiffCompression.JPEG_XL, pages.page(0).compression());
        }
    }

    @Test
    @DisplayName("RGBA, gray and 16-bit pictures written under the automatic layout round-trip exactly")
    void losslessPictures() {
        var sources = List.<Picture>of(TiffCodecCompressionTest.rgb(53, 37, true, 1),
                TiffCodecCompressionTest.rgb(53, 37, false, 2), TiffEncoderTest.picture(53, 37, false, true, 3),
                TiffEncoderTest.picture16(41, 29, true, 4), TiffCodecCompressionTest.rgb(1, 1, false, 5));
        for (var source : sources) {
            for (var options : List.of(JXL, JXL.toBuilder().tiles(16, 32).build(),
                    JXL.toBuilder().layout(TiffEncoderOptions.Layout.GRAY).build())) {
                var decoded = decode(TiffEncoder.encode(source, options));
                var plain = decode(TiffEncoder.encode(source, options.toBuilder().compression(TiffCompression.NONE)
                        .build()));
                assertSameSamples(plain, decoded, source + " " + options);
            }
        }
    }

    @Nested
    @DisplayName("the coded strips and tiles")
    class Coded {
        @Test
        @DisplayName("hold the color channels of gray and RGB pages and their extra samples as extra channels")
        void channels() {
            var rgba = TiffEncoder.encode(picture(20, 10, PixelLayout.RGBA, 8, 1), JXL);
            var image = JxlDecoder.decodeFloat(MediaSource.of(CodedTiff.read(rgba).getFirst()));
            assertEquals(3, image.colorChannels());
            assertEquals(List.of(JxlChannelType.ALPHA), image.extraChannelTypes());
            var grayAlpha = TiffEncoder.encode(picture(20, 10, PixelLayout.GRAY_ALPHA, 16, 2), JXL);
            image = JxlDecoder.decodeFloat(MediaSource.of(CodedTiff.read(grayAlpha).getFirst()));
            assertEquals(1, image.colorChannels());
            assertEquals(List.of(JxlChannelType.ALPHA), image.extraChannelTypes());
            var depth = TiffEncoder.encode(picture(20, 10, RGB_DEPTH, 8, 3), JXL);
            image = JxlDecoder.decodeFloat(MediaSource.of(CodedTiff.read(depth).getFirst()));
            assertEquals(3, image.colorChannels());
            assertEquals(List.of(JxlChannelType.OPTIONAL), image.extraChannelTypes());
        }

        @Test
        @DisplayName("hold the inks of a CMYK page unchanged, as a gray channel and extra channels")
        void inks() {
            var source = (Channel8Picture) picture(20, 10, PixelLayout.CMYKA, 8, 4);
            var tiff = TiffEncoder.encode(source, JXL);
            var image = JxlDecoder.decodeFloat(MediaSource.of(CodedTiff.read(tiff).getFirst()));
            assertEquals(1, image.colorChannels());
            assertEquals(List.of(JxlChannelType.OPTIONAL, JxlChannelType.OPTIONAL, JxlChannelType.OPTIONAL,
                    JxlChannelType.ALPHA), image.extraChannelTypes());
            for (var c = 0; c < 5; c++) {
                var plane = image.plane(c);
                for (var i = 0; i < 200; i++) {
                    assertEquals(source.pixels()[i * 5 + c] & 0xFF, Math.round(plane[i] * 255), "channel " + c);
                }
            }
        }

        @Test
        @DisplayName("of separate planes are gray images of one sample each")
        void planes() {
            var source = (Channel16Picture) picture(24, 20, PixelLayout.RGBA, 16, 5);
            var tiff = TiffEncoder.encode(source, JXL.toBuilder().planar(true).strips(8).build());
            var chunks = CodedTiff.read(tiff);
            assertEquals(4 * 3, chunks.size());
            for (var index = 0; index < chunks.size(); index++) {
                var image = JxlDecoder.decodeFloat(MediaSource.of(chunks.get(index)));
                assertEquals(1, image.colorChannels());
                assertTrue(image.extraChannelTypes().isEmpty());
                var sample = index / 3;
                var top = (index % 3) * 8;
                assertEquals(24, image.width());
                assertEquals(Math.min(8, 20 - top), image.height());
                for (var i = 0; i < image.width() * image.height(); i++) {
                    assertEquals(source.pixels()[(top * 24 + i) * 4 + sample] & 0xFFFF,
                            Math.round(image.plane(0)[i] * 65535), "plane " + sample);
                }
            }
        }

        @Test
        @DisplayName("are tagged 50002, or 52546 with the DNG code, and decode the same under both")
        void codes() {
            var source = picture(30, 20, PixelLayout.RGB, 16, 6);
            var gdal = TiffEncoder.encode(source, JXL);
            var dng = TiffEncoder.encode(source, JXL.toBuilder().dngJpegXlCode(true).build());
            assertEquals(50002, compressionTag(gdal));
            assertEquals(52546, compressionTag(dng));
            assertSameSamples(decode(gdal), decode(dng), "DNG code");
            // the code alone differs: the strips are the same bytes
            var left = CodedTiff.read(gdal);
            var right = CodedTiff.read(dng);
            assertEquals(left.size(), right.size());
            for (var i = 0; i < left.size(); i++) {
                assertArrayEquals(left.get(i), right.get(i));
            }
            var plain = TiffEncoder.encode(source, JXL.toBuilder().compression(TiffCompression.LZW)
                    .dngJpegXlCode(true).build());
            assertEquals(5, compressionTag(plain), "the DNG code applies to JPEG XL only");
        }

        @Test
        @DisplayName("are bare codestreams, or containers only when the conformance level needs one")
        void containers() {
            var eight = CodedTiff.read(TiffEncoder.encode(picture(20, 10, PixelLayout.RGB, 8, 7),
                    JXL.toBuilder().jpegXlOptions(JxlEncoderOptions.lossless().toBuilder().container(true).build())
                            .build())).getFirst();
            assertEquals((byte) 0xFF, eight[0]);
            assertEquals((byte) 0x0A, eight[1]);
            var floating = CodedTiff.read(TiffEncoder.encode(picture(20, 10, PixelLayout.GRAY, 32, 8), JXL))
                    .getFirst();
            assertEquals("JXL ", new String(floating, 4, 4, StandardCharsets.US_ASCII));
        }
    }

    static int compressionTag(byte[] tiff) {
        var little = tiff[0] == 'I';
        var buffer = ByteBuffer.wrap(tiff).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        var ifd = buffer.getInt(4);
        var count = buffer.getShort(ifd) & 0xFFFF;
        for (var i = 0; i < count; i++) {
            var at = ifd + 2 + i * 12;
            if ((buffer.getShort(at) & 0xFFFF) == 259) {
                return buffer.getShort(at + 8) & 0xFFFF;
            }
        }
        throw new AssertionError("no Compression tag");
    }

    @Nested
    @DisplayName("against libjxl and libtiff")
    class Oracles {
        static Stream<Arguments> djxlCases() {
            return Stream.of(Arguments.of(PixelLayout.GRAY, 8, "pgm"), Arguments.of(PixelLayout.RGB, 8, "ppm"),
                    Arguments.of(PixelLayout.RGBA, 8, "pam"), Arguments.of(PixelLayout.GRAY_ALPHA, 16, "pam"),
                    Arguments.of(PixelLayout.RGB, 16, "ppm"), Arguments.of(PixelLayout.CMYK, 16, "pgm"),
                    Arguments.of(PixelLayout.GRAY, 32, "pfm"), Arguments.of(PixelLayout.RGB, 32, "pfm"));
        }

        @ParameterizedTest(name = "{0}, {1} bits")
        @MethodSource("djxlCases")
        @DisplayName("djxl decodes each extracted tile to the source samples of its color and alpha channels")
        void djxl(PixelLayout layout, int depth, String extension) {
            Oracle.assume(Oracle.Tool.DJXL);
            var width = 50;
            var height = 41;
            var source = picture(width, height, layout, depth, 31L * depth + layout.count());
            var tiff = TiffEncoder.encode(source, JXL.toBuilder().tiles(32, 32).build());
            var tiles = CodedTiff.read(tiff);
            assertEquals(4, tiles.size());
            var count = layout.count();
            // the channels djxl writes: the color channels (the first ink of CMYK) and alpha
            var colors = layout.colorModel() == com.github.auties00.smalt.image.ColorModel.RGB ? 3 : 1;
            var written = colors + (layout.hasAlpha() ? 1 : 0);
            for (var t = 0; t < tiles.size(); t++) {
                var input = RasterTesting.write("tiff-jxl-" + layout.count() + "-" + depth + "-" + t + ".jxl",
                        tiles.get(t));
                var output = input.resolveSibling(input.getFileName() + "." + extension);
                Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(input.toString(), output.toString(),
                        "--quiet"));
                var decoded = Netpbm.read(LibTiff.read(output));
                assertEquals(32, decoded.width(), "tile " + t);
                assertEquals(32, decoded.height(), "tile " + t);
                var x0 = (t % 2) * 32;
                var y0 = (t / 2) * 32;
                for (var y = 0; y < 32 && y0 + y < height; y++) {
                    for (var x = 0; x < 32 && x0 + x < width; x++) {
                        for (var c = 0; c < written; c++) {
                            var sample = c < colors ? c : layout.alphaIndex();
                            var expected = sample(source, ((y0 + y) * width + x0 + x) * count + sample);
                            var actual = decoded.sample(x, y, depth == 32 && decoded.depth() == 3 && colors == 1 ? 0 : c);
                            if (depth == 32) {
                                assertEquals(Float.floatToRawIntBits((float) expected),
                                        Float.floatToRawIntBits((float) actual), "tile " + t + " pixel " + x + ","
                                                + y + " channel " + c);
                            } else {
                                assertEquals(expected, actual, "tile " + t + " pixel " + x + "," + y + " channel " + c);
                            }
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("tiffinfo reads the structure of JPEG XL pages in strips and tiles")
        void tiffinfo() {
            LibTiff.assume("tiffinfo");
            var source = picture(53, 37, PixelLayout.RGBA, 16, 9);
            for (var options : List.of(JXL.toBuilder().strips(16).build(), JXL.toBuilder().tiles(32, 16).build())) {
                var file = LibTiff.write("jxl.tif", TiffEncoder.encode(source, options));
                var text = new String(LibTiff.run("tiffinfo", List.of(file.toString())), StandardCharsets.UTF_8);
                assertTrue(text.contains("Image Width: 53 Image Length: 37"), text);
                assertTrue(text.contains("Bits/Sample: 16"), text);
                assertTrue(text.contains("Samples/Pixel: 4"), text);
                assertTrue(Pattern.compile("Compression Scheme: (JXL|JPEG ?XL|50002)", Pattern.CASE_INSENSITIVE)
                        .matcher(text).find(), text);
                assertTrue(text.contains(options.tileWidth().isPresent() ? "Tile Width: 32 Tile Length: 16"
                        : "Rows/Strip: 16"), text);
            }
        }
    }

    /** The sample of a picture at a flat index of its tight samples, as a double (floating-point ones exactly). */
    static double sample(Picture picture, int index) {
        return switch (picture) {
            case Channel8Picture p -> p.pixels()[index] & 0xFF;
            case Channel16Picture p -> p.pixels()[index] & 0xFFFF;
            case ChannelFloatPicture p -> p.pixels()[index];
            default -> throw new AssertionError(picture.getClass().toString());
        };
    }

    /** A decoded PGM, PPM, PAM or PFM image. */
    record Netpbm(int width, int height, int depth, double[] samples) {
        double sample(int x, int y, int channel) {
            return samples[(y * width + x) * depth + channel];
        }

        static Netpbm read(byte[] data) {
            var at = new int[]{2};
            var magic = new String(data, 0, 2, StandardCharsets.US_ASCII);
            int width;
            int height;
            int depth;
            double maxval;
            if (magic.equals("P7")) {
                width = height = depth = 0;
                maxval = 0;
                while (true) {
                    var line = line(data, at).trim();
                    if (line.equals("ENDHDR")) {
                        break;
                    }
                    var parts = line.split("\\s+");
                    switch (parts[0]) {
                        case "WIDTH" -> width = Integer.parseInt(parts[1]);
                        case "HEIGHT" -> height = Integer.parseInt(parts[1]);
                        case "DEPTH" -> depth = Integer.parseInt(parts[1]);
                        case "MAXVAL" -> maxval = Integer.parseInt(parts[1]);
                        default -> {
                        }
                    }
                }
            } else {
                width = Integer.parseInt(token(data, at));
                height = Integer.parseInt(token(data, at));
                maxval = Double.parseDouble(token(data, at));
                at[0]++;
                depth = switch (magic) {
                    case "P5", "Pf" -> 1;
                    case "P6", "PF" -> 3;
                    default -> throw new AssertionError("unexpected Netpbm magic " + magic);
                };
            }
            var samples = new double[width * height * depth];
            if (magic.startsWith("P") && (magic.equals("Pf") || magic.equals("PF"))) {
                var order = maxval < 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                var buffer = ByteBuffer.wrap(data, at[0], data.length - at[0]).slice().order(order);
                for (var row = 0; row < height; row++) {
                    var y = height - 1 - row;
                    for (var i = 0; i < width * depth; i++) {
                        samples[y * width * depth + i] = buffer.getFloat((row * width * depth + i) * 4);
                    }
                }
            } else {
                var wide = maxval > 255;
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = wide ? ((data[at[0] + 2 * i] & 0xFF) << 8 | (data[at[0] + 2 * i + 1] & 0xFF))
                            : data[at[0] + i] & 0xFF;
                }
            }
            return new Netpbm(width, height, depth, samples);
        }

        private static String line(byte[] data, int[] at) {
            var start = at[0];
            while (data[at[0]] != '\n') {
                at[0]++;
            }
            return new String(data, start, at[0]++ - start, StandardCharsets.US_ASCII);
        }

        private static String token(byte[] data, int[] at) {
            while (Character.isWhitespace(data[at[0]])) {
                at[0]++;
            }
            var start = at[0];
            while (!Character.isWhitespace(data[at[0]])) {
                at[0]++;
            }
            return new String(data, start, at[0] - start, StandardCharsets.US_ASCII);
        }
    }

    @Nested
    @DisplayName("lossy settings and options")
    class Settings {
        @Test
        @DisplayName("lossy pages decode close to the source, and noisy ones are smaller than lossless ones")
        void lossy() {
            var lossyOptions = JXL.toBuilder().tiles(32, 32).jpegXlOptions(JxlEncoderOptions.ofDistance(1.0f)).build();
            var source = TiffCodecCompressionTest.smooth(96, 64, false);
            var decoded = (RgbaPicture) TiffDecoder.decode(MediaSource.of(TiffEncoder.encode(source, lossyOptions)))
                    .picture();
            var tight = RasterTesting.tight(decoded);
            assertFalse(java.util.Arrays.equals(RasterTesting.tight(source), tight), "the page is coded lossily");
            Tolerance.psnr(35).assertWithin("lossy JPEG XL page", RasterTesting.tight(source), tight);
            var noisy = TiffCodecCompressionTest.rgb(96, 64, false, 12);
            var lossless = TiffEncoder.encode(noisy, JXL.toBuilder().tiles(32, 32).build());
            var lossy = TiffEncoder.encode(noisy, lossyOptions);
            assertTrue(lossy.length < lossless.length, lossy.length + " of " + lossless.length + " bytes");
        }

        @Test
        @DisplayName("ignore the codestream metadata of the JPEG XL settings")
        void metadata() {
            var source = picture(20, 10, PixelLayout.RGB, 8, 10);
            var plain = TiffEncoder.encode(source, JXL);
            var noisy = TiffEncoder.encode(source, JXL.toBuilder().jpegXlOptions(JxlEncoderOptions.lossless()
                    .toBuilder().exif(new byte[]{0, 0, 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0}).xmp(
                            "<x:xmpmeta xmlns:x='adobe:ns:meta/'/>".getBytes(StandardCharsets.UTF_8))
                    .orientation(com.github.auties00.smalt.image.Orientation.ROTATE_90).build()).build());
            assertArrayEquals(plain, noisy);
        }

        @Test
        @DisplayName("reject the layouts JPEG XL pages cannot hold")
        void rejects() {
            for (var layout : List.of(TiffEncoderOptions.Layout.PALETTE, TiffEncoderOptions.Layout.BILEVEL,
                    TiffEncoderOptions.Layout.YCBCR)) {
                assertThrows(IllegalArgumentException.class, () -> JXL.toBuilder().layout(layout).build(),
                        layout.toString());
            }
            assertThrows(NullPointerException.class, () -> TiffEncoderOptions.builder().jpegXlOptions(null));
            var floats = JXL.toBuilder().depth(TiffEncoderOptions.Depth.FLOAT).planar(true).build();
            assertEquals(TiffCompression.JPEG_XL, floats.compression());
        }

        @Test
        @DisplayName("carry the JPEG XL settings and the code through toBuilder, equality and the description")
        void options() {
            var jxl = JxlEncoderOptions.ofDistance(2.5f);
            var options = JXL.toBuilder().jpegXlOptions(jxl).dngJpegXlCode(true).build();
            assertEquals(jxl, options.jpegXlOptions());
            assertTrue(options.isDngJpegXlCode());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().dngJpegXlCode(false).build());
            assertNotEquals(options, options.toBuilder().jpegXlOptions(JxlEncoderOptions.lossless()).build());
            assertTrue(options.toString().contains("jpegXlOptions"), options.toString());
            assertTrue(options.toString().contains("dngJpegXlCode"), options.toString());
            assertEquals(JxlEncoderOptions.lossless(), TiffEncoderOptions.defaults().jpegXlOptions());
            assertFalse(TiffEncoderOptions.defaults().isDngJpegXlCode());
            assertFalse(TiffEncoderOptions.defaults().toString().contains("jpegXlOptions"));
        }
    }
}
