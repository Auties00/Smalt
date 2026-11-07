package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the physical pixel density of the image formats that store one: written by each encoder, read back by
 * Smalt and by exiftool, read from files that exiftool and ffmpeg wrote, and the rules that leave an invalid or
 * misplaced field out.
 */
@DisplayName("Pixel density")
class PixelDensityFormatsTest {
    private static RgbaPicture picture() {
        var picture = RgbaPicture.allocate(16, 12, false);
        var pixels = picture.pixels();
        for (var i = 0; i < 16 * 12; i++) {
            pixels[i * 4] = (byte) (i * 5);
            pixels[i * 4 + 1] = (byte) (i * 3);
            pixels[i * 4 + 2] = (byte) (255 - i);
            pixels[i * 4 + 3] = (byte) 255;
        }
        return picture;
    }

    private static PixelDensity perMeter(PixelDensity density) {
        return density.to(PixelDensity.Unit.DOTS_PER_METER);
    }

    private static Optional<PixelDensity> decode(byte[] data) {
        return Images.decode(MediaSource.of(data)).density();
    }

    @Nested
    @DisplayName("round trips through Smalt")
    class RoundTrip {
        static Stream<Arguments> jpeg() {
            return Stream.of(
                    Arguments.of(PixelDensity.ofDotsPerInch(300, 150), PixelDensity.ofDotsPerInch(300, 150)),
                    Arguments.of(PixelDensity.ofDotsPerCentimeter(40, 41), PixelDensity.ofDotsPerCentimeter(40, 41)),
                    Arguments.of(PixelDensity.ofDotsPerMeter(4000, 4100), PixelDensity.ofDotsPerCentimeter(40, 41)),
                    Arguments.of(PixelDensity.ofDotsPerInch(72.4), PixelDensity.ofDotsPerInch(72)),
                    Arguments.of(PixelDensity.ofAspectRatio(1, 2), PixelDensity.ofAspectRatio(1, 2)),
                    Arguments.of(PixelDensity.ofAspectRatio(1.5, 1), PixelDensity.ofAspectRatio(3, 2)),
                    Arguments.of(PixelDensity.ofDotsPerInch(65535, 1), PixelDensity.ofDotsPerInch(65535, 1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("JPEG writes the JFIF density in its unit, whole values")
        void jpeg(PixelDensity written, PixelDensity read) {
            var jpeg = JpegEncoder.encode(picture(), JpegEncoderOptions.builder().density(written).build());
            assertEquals(read, JpegDecoder.decode(MediaSource.of(jpeg)).density().orElseThrow());
            assertEquals(Optional.of(read), decode(jpeg));
            var progressive = JpegEncoder.encode(picture(), JpegEncoderOptions.builder().progressive(true)
                    .density(written).build());
            assertEquals(Optional.of(read), decode(progressive));
        }

        @Test
        @DisplayName("JPEG writes a 1:1 aspect ratio by default")
        void jpegDefault() {
            assertEquals(Optional.of(PixelDensity.ofAspectRatio(1, 1)), decode(JpegEncoder.encode(picture())));
        }

        static Stream<Arguments> png() {
            return Stream.of(
                    Arguments.of(PixelDensity.ofDotsPerInch(300), PixelDensity.ofDotsPerMeter(11811, 11811)),
                    Arguments.of(PixelDensity.ofDotsPerInch(72), PixelDensity.ofDotsPerMeter(2835, 2835)),
                    Arguments.of(PixelDensity.ofDotsPerCentimeter(40, 41.5), PixelDensity.ofDotsPerMeter(4000, 4150)),
                    Arguments.of(PixelDensity.ofDotsPerMeter(2147483647, 1), PixelDensity.ofDotsPerMeter(2147483647, 1)),
                    Arguments.of(PixelDensity.ofAspectRatio(1, 2), PixelDensity.ofAspectRatio(1, 2)),
                    Arguments.of(PixelDensity.ofAspectRatio(0.25, 1), PixelDensity.ofAspectRatio(1, 4)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("PNG writes pHYs in pixels per meter or as an aspect ratio")
        void png(PixelDensity written, PixelDensity read) {
            var png = PngEncoder.encode(picture(), PngEncoderOptions.builder().density(written).build());
            assertEquals(read, PngDecoder.decode(MediaSource.of(png)).density().orElseThrow());
            var interlaced = PngEncoder.encode(picture(), PngEncoderOptions.builder().interlaced(true)
                    .density(written).build());
            assertEquals(Optional.of(read), decode(interlaced));
            assertTrue(decode(PngEncoder.encode(picture())).isEmpty());
        }

        static Stream<Arguments> tiff() {
            return Stream.of(
                    Arguments.of(PixelDensity.ofDotsPerInch(300.5, 72), PixelDensity.ofDotsPerInch(300.5, 72)),
                    Arguments.of(PixelDensity.ofDotsPerCentimeter(40, 41.25),
                            PixelDensity.ofDotsPerCentimeter(40, 41.25)),
                    Arguments.of(PixelDensity.ofDotsPerMeter(4000, 4125), PixelDensity.ofDotsPerCentimeter(40, 41.25)),
                    Arguments.of(PixelDensity.ofAspectRatio(1, 2), PixelDensity.ofAspectRatio(1, 2)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("TIFF writes the resolution as rationals with its unit, and 72 dots per inch by default")
        void tiff(PixelDensity written, PixelDensity read) {
            var tiff = TiffEncoder.encode(picture(), TiffEncoderOptions.builder().density(written).build());
            assertEquals(read, TiffDecoder.decode(MediaSource.of(tiff)).density().orElseThrow());
            try (var pages = TiffDecoder.openPages(MediaSource.of(tiff))) {
                assertEquals(read, pages.page(0).density().orElseThrow());
                assertEquals(read, pages.read(0).density().orElseThrow());
            }
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72)), decode(TiffEncoder.encode(picture())));
        }

        static Stream<Arguments> bmp() {
            return Stream.of(
                    Arguments.of(PixelDensity.ofDotsPerInch(300), Optional.of(PixelDensity.ofDotsPerMeter(11811, 11811))),
                    Arguments.of(PixelDensity.ofDotsPerMeter(3780, 2835), Optional.of(PixelDensity.ofDotsPerMeter(3780,
                            2835))),
                    Arguments.of(PixelDensity.ofDotsPerCentimeter(1.5, 2), Optional.of(PixelDensity.ofDotsPerMeter(150,
                            200))),
                    Arguments.of(PixelDensity.ofAspectRatio(1, 2), Optional.empty()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("BMP writes pixels per meter and no aspect ratio")
        void bmp(PixelDensity written, Optional<PixelDensity> read) {
            for (var version : BmpEncoderOptions.HeaderVersion.values()) {
                var bmp = BmpEncoder.encode(picture(), BmpEncoderOptions.builder().headerVersion(version)
                        .density(written).build());
                assertEquals(read, BmpDecoder.decode(MediaSource.of(bmp)).density(), version.toString());
                assertEquals(read, decode(bmp), version.toString());
            }
            assertTrue(decode(BmpEncoder.encode(picture())).isEmpty());
        }

        @ParameterizedTest(name = "{0} dpi")
        @ValueSource(doubles = {1, 7, 72, 96, 150, 300, 300.5, 600, 1200, 2400, 9600, 65535})
        @DisplayName("JPEG 2000 writes whole dots per inch exactly in the capture resolution box")
        void jp2DotsPerInch(double dpi) {
            var density = PixelDensity.ofDotsPerInch(dpi, dpi / 2);
            var jp2 = Jp2Encoder.encode(picture(), Jp2EncoderOptions.lossless().toBuilder().density(density).build());
            var read = Jp2Decoder.decode(MediaSource.of(jp2)).density().orElseThrow();
            assertEquals(perMeter(density), read);
            assertEquals(dpi, read.to(PixelDensity.Unit.DOTS_PER_INCH).horizontal(), dpi * 1e-12);
        }

        static Stream<Arguments> jp2() {
            return Stream.of(
                    Arguments.of(PixelDensity.ofDotsPerMeter(2835, 3780), PixelDensity.ofDotsPerMeter(2835, 3780)),
                    Arguments.of(PixelDensity.ofDotsPerCentimeter(40, 41), PixelDensity.ofDotsPerMeter(4000, 4100)),
                    Arguments.of(PixelDensity.ofDotsPerMeter(1e9, 0.001), PixelDensity.ofDotsPerMeter(1e9, 0.001)),
                    Arguments.of(PixelDensity.ofDotsPerMeter(123456789, 1), PixelDensity.ofDotsPerMeter(123456789, 1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("JPEG 2000 writes the capture resolution in grid points per meter")
        void jp2(PixelDensity written, PixelDensity read) {
            var jp2 = Jp2Encoder.encode(picture(), Jp2EncoderOptions.lossless().toBuilder().density(written).build());
            var decoded = decode(jp2).orElseThrow();
            assertEquals(PixelDensity.Unit.DOTS_PER_METER, decoded.unit());
            assertEquals(read.horizontal(), decoded.horizontal(), read.horizontal() * 1e-4);
            assertEquals(read.vertical(), decoded.vertical(), read.vertical() * 1e-4);
        }

        @Test
        @DisplayName("JPEG 2000 writes no box for an aspect ratio, none by default, and none in a raw codestream")
        void jp2Absent() {
            var options = Jp2EncoderOptions.lossless();
            assertTrue(decode(Jp2Encoder.encode(picture(), options)).isEmpty());
            assertTrue(decode(Jp2Encoder.encode(picture(), options.toBuilder()
                    .density(PixelDensity.ofAspectRatio(1, 2)).build())).isEmpty());
            assertTrue(decode(Jp2Encoder.encode(picture(), options.toBuilder().format(Jp2Format.CODESTREAM)
                    .density(PixelDensity.ofDotsPerInch(300)).build())).isEmpty());
            var withBox = options.toBuilder().density(PixelDensity.ofDotsPerInch(300)).build();
            assertEquals(options, withBox.toBuilder().noDensity().build());
        }

        @Test
        @DisplayName("TIFF pages keep the density of a decoded page, except the aspect ratio of square pixels")
        void pageWriter() {
            var densities = List.of(Optional.of(PixelDensity.ofDotsPerCentimeter(40, 41)),
                    Optional.of(PixelDensity.ofAspectRatio(1, 2)), Optional.of(PixelDensity.ofAspectRatio(3, 3)),
                    Optional.<PixelDensity>empty());
            var sink = MediaSink.memory();
            try (var writer = Images.openPageWriter(ImageFormat.TIFF, sink)) {
                for (var density : densities) {
                    writer.write(new DecodedImage(picture(), ImageFormat.JPEG, Orientation.NORMAL, 16, 12,
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), density));
                }
                writer.finish();
            }
            try (var pages = TiffDecoder.openPages(MediaSource.of(sink.toByteArray()))) {
                assertEquals(densities.get(0), pages.read(0).density());
                assertEquals(densities.get(1), pages.read(1).density());
                assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72)), pages.read(2).density());
                assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72)), pages.read(3).density());
            }
        }

        static Stream<Arguments> decoderOptions() {
            var density = PixelDensity.ofDotsPerInch(300, 150);
            var jpeg = JpegEncoder.encode(picture(), JpegEncoderOptions.builder().density(density).build());
            var png = PngEncoder.encode(picture(), PngEncoderOptions.builder().density(density).build());
            var tiff = TiffEncoder.encode(picture(), TiffEncoderOptions.builder().density(density).build());
            var bmp = BmpEncoder.encode(picture(), BmpEncoderOptions.builder().density(density).build());
            var jp2 = Jp2Encoder.encode(picture(), Jp2EncoderOptions.lossless().toBuilder().density(density).build());
            var defaults = ImageDecoderOptions.defaults();
            var arguments = Stream.<Arguments>builder();
            for (var file : List.of(jpeg, png, tiff, bmp, jp2)) {
                var expected = decode(file).orElseThrow();
                for (var options : List.of(defaults.toBuilder().minSize(4, 3).build(),
                        defaults.toBuilder().highBitDepth(true).build(),
                        defaults.toBuilder().convertToSrgb(true).build(),
                        defaults.toBuilder().keepChannels(true).convertToSrgb(false).build())) {
                    arguments.add(Arguments.of(Images.probe(MediaSource.of(file)).format(), options, file, expected));
                }
            }
            return arguments.build();
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource
        @DisplayName("keeps the density under a size hint, a deeper output and color conversion")
        void decoderOptions(ImageFormat format, ImageDecoderOptions options, byte[] file, PixelDensity expected) {
            var decoded = Images.decode(MediaSource.of(file), options);
            assertEquals(Optional.of(expected), decoded.density());
        }

        @Test
        @DisplayName("keeps the density of a CMYK JPEG converted to sRGB or kept as channels")
        void jpegCmyk() {
            var cmyk = JpegEncoder.encode(picture(), JpegEncoderOptions.builder()
                    .colorSpace(JpegEncoderOptions.ColorSpace.CMYK).build());
            var jpeg = new byte[cmyk.length + Jpeg.app1Exif(2).length];
            System.arraycopy(cmyk, 0, jpeg, 0, 2);
            System.arraycopy(Jpeg.app1Exif(2), 0, jpeg, 2, Jpeg.app1Exif(2).length);
            System.arraycopy(cmyk, 2, jpeg, 2 + Jpeg.app1Exif(2).length, cmyk.length - 2);
            var expected = Optional.of(PixelDensity.ofDotsPerInch(180, 90));
            var defaults = ImageDecoderOptions.defaults();
            for (var options : List.of(defaults, defaults.toBuilder().convertToSrgb(true).build(),
                    defaults.toBuilder().keepChannels(true).convertToSrgb(false).build(),
                    defaults.toBuilder().keepChannels(true).convertToSrgb(false).highBitDepth(true).build())) {
                assertEquals(expected, Images.decode(MediaSource.of(jpeg), options).density(), options.toString());
            }
        }

        @Test
        @DisplayName("carries a decoded density into another format")
        void convert() {
            var jpeg = JpegEncoder.encode(picture(), JpegEncoderOptions.builder()
                    .density(PixelDensity.ofDotsPerInch(300, 600)).build());
            var decoded = JpegDecoder.decode(MediaSource.of(jpeg));
            var png = PngEncoder.encode(decoded.picture(), PngEncoderOptions.builder()
                    .density(decoded.density().orElseThrow()).build());
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(11811, 23622)), decode(png));
            var tiff = TiffEncoder.encode(decoded.picture(), TiffEncoderOptions.builder()
                    .density(decode(png).orElseThrow()).build());
            var back = decode(tiff).orElseThrow();
            assertEquals(PixelDensity.ofDotsPerCentimeter(118.11, 236.22), back);
            assertEquals(300, back.to(PixelDensity.Unit.DOTS_PER_INCH).horizontal(), 0.01);
        }
    }

    @Nested
    @DisplayName("encoder options")
    class Options {
        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("JPEG rejects a density the JFIF header cannot hold")
        void jpegRejects(PixelDensity density) {
            var builder = JpegEncoderOptions.builder().density(density);
            assertThrows(IllegalArgumentException.class, builder::build);
        }

        static Stream<PixelDensity> jpegRejects() {
            return Stream.of(PixelDensity.ofDotsPerInch(65535.5), PixelDensity.ofDotsPerInch(0.4),
                    PixelDensity.ofDotsPerCentimeter(1, 0.49), PixelDensity.ofDotsPerMeter(49, 100),
                    PixelDensity.ofAspectRatio(65535.5, 1), PixelDensity.ofAspectRatio(1, 70000.5),
                    PixelDensity.ofAspectRatio(1e6, 1));
        }

        @Test
        @DisplayName("JPEG accepts the extremes of the JFIF fields")
        void jpegExtremes() {
            for (var density : List.of(PixelDensity.ofDotsPerInch(65535.4), PixelDensity.ofDotsPerInch(0.5),
                    PixelDensity.ofAspectRatio(65535, 1), PixelDensity.ofAspectRatio(1, 65535))) {
                var jpeg = JpegEncoder.encode(picture(), JpegEncoderOptions.builder().density(density).build());
                var read = decode(jpeg).orElseThrow();
                assertEquals(density.unit(), read.unit());
                assertEquals(Math.round(density.horizontal()), read.horizontal());
                assertEquals(Math.round(density.vertical()), read.vertical());
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("PNG rejects a density the pHYs chunk cannot hold")
        void pngRejects(PixelDensity density) {
            var builder = PngEncoderOptions.builder().density(density);
            assertThrows(IllegalArgumentException.class, builder::build);
        }

        static Stream<PixelDensity> pngRejects() {
            return Stream.of(PixelDensity.ofDotsPerMeter(0.4, 1), PixelDensity.ofDotsPerMeter(1, 2147483647.5),
                    PixelDensity.ofDotsPerInch(1e9), PixelDensity.ofAspectRatio(3e9, 1),
                    PixelDensity.ofAspectRatio(1, 3e9));
        }

        @Test
        @DisplayName("BMP rejects a physical density outside the header range and ignores an aspect ratio")
        void bmp() {
            assertThrows(IllegalArgumentException.class,
                    () -> BmpEncoderOptions.builder().density(PixelDensity.ofDotsPerMeter(0.4, 1)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> BmpEncoderOptions.builder().density(PixelDensity.ofDotsPerInch(1e9)).build());
            var aspect = BmpEncoderOptions.builder().density(PixelDensity.ofAspectRatio(1e12, 1)).build();
            assertTrue(decode(BmpEncoder.encode(picture(), aspect)).isEmpty());
            var plain = BmpEncoderOptions.builder().build();
            assertEquals(plain, aspect.toBuilder().noDensity().build());
        }

        @Test
        @DisplayName("JPEG 2000 clamps a density beyond the resolution box range to its extremes")
        void jp2Clamps() {
            var huge = Jp2Encoder.encode(picture(), Jp2EncoderOptions.lossless().toBuilder()
                    .density(PixelDensity.ofDotsPerMeter(1e40, 1e-40)).build());
            var read = decode(huge).orElseThrow();
            assertEquals(65535e20, read.horizontal(), 65535e20 * 1e-12);
            assertEquals(1e-20 / 65535, read.vertical(), 1e-20 / 65535 * 1e-12);
        }

        @Test
        @DisplayName("PNG and TIFF reset to their defaults")
        void resets() {
            var png = PngEncoderOptions.builder().density(PixelDensity.ofDotsPerInch(300)).noDensity().build();
            assertEquals(PngEncoderOptions.builder().build(), png);
            var tiff = TiffEncoderOptions.builder().density(PixelDensity.ofDotsPerInch(300)).automaticDensity()
                    .build();
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72)), decode(TiffEncoder.encode(picture(), tiff)));
        }
    }

    @Nested
    @DisplayName("is read by exiftool as written")
    class ExiftoolReads {
        private static String value(Map<String, String> tags, String suffix) {
            return tags.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + suffix)).findFirst()
                    .map(Map.Entry::getValue).orElseThrow(() -> new AssertionError("no " + suffix + " in " + tags));
        }

        @Test
        @DisplayName("JPEG: the JFIF resolution and unit")
        void jpeg() {
            var jpeg = JpegEncoder.encode(picture(), JpegEncoderOptions.builder()
                    .density(PixelDensity.ofDotsPerCentimeter(40, 41)).build());
            var tags = MetadataSupport.exiftoolRead(jpeg, "density.jpg");
            assertEquals("40", tags.get("JFIF:XResolution"), tags.toString());
            assertEquals("41", tags.get("JFIF:YResolution"), tags.toString());
            assertEquals("2", tags.get("JFIF:ResolutionUnit"), tags.toString());
        }

        @Test
        @DisplayName("PNG: the pHYs pixels per unit and unit")
        void png() {
            var png = PngEncoder.encode(picture(), PngEncoderOptions.builder()
                    .density(PixelDensity.ofDotsPerInch(300, 150)).build());
            var tags = MetadataSupport.exiftoolRead(png, "density.png");
            assertEquals("11811", value(tags, "PixelsPerUnitX"));
            assertEquals("5906", value(tags, "PixelsPerUnitY"));
            assertEquals("1", value(tags, "PixelUnits"));
        }

        @Test
        @DisplayName("TIFF: the IFD0 resolution and unit")
        void tiff() {
            var tiff = TiffEncoder.encode(picture(), TiffEncoderOptions.builder()
                    .density(PixelDensity.ofDotsPerInch(300.5, 72)).build());
            var tags = MetadataSupport.exiftoolRead(tiff, "density.tif");
            assertEquals(300.5, Double.parseDouble(tags.get("IFD0:XResolution")), tags.toString());
            assertEquals(72, Double.parseDouble(tags.get("IFD0:YResolution")), tags.toString());
            assertEquals("2", tags.get("IFD0:ResolutionUnit"), tags.toString());
        }

        @Test
        @DisplayName("BMP: the pixels per meter")
        void bmp() {
            var bmp = BmpEncoder.encode(picture(), BmpEncoderOptions.builder()
                    .density(PixelDensity.ofDotsPerMeter(3780, 2835)).build());
            var tags = MetadataSupport.exiftoolRead(bmp, "density.bmp");
            assertEquals("3780", value(tags, "PixelsPerMeterX"));
            assertEquals("2835", value(tags, "PixelsPerMeterY"));
        }

        @Test
        @DisplayName("JPEG 2000: the capture resolution and its decimal exponent")
        void jp2() {
            var density = PixelDensity.ofDotsPerInch(300, 72);
            var jp2 = Jp2Encoder.encode(picture(), Jp2EncoderOptions.lossless().toBuilder().density(density).build());
            var tags = MetadataSupport.exiftoolRead(jp2, "density.jp2");
            var expected = perMeter(density);
            var x = Double.parseDouble(value(tags, "CaptureXResolution"))
                    * StrictMath.pow(10, Integer.parseInt(value(tags, "CaptureXResolutionUnit")));
            var y = Double.parseDouble(value(tags, "CaptureYResolution"))
                    * StrictMath.pow(10, Integer.parseInt(value(tags, "CaptureYResolutionUnit")));
            // exiftool prints four decimals
            assertEquals(expected.horizontal(), x, expected.horizontal() * 1e-5, tags.toString());
            assertEquals(expected.vertical(), y, expected.vertical() * 1e-5, tags.toString());
        }
    }

    @Nested
    @DisplayName("is read from files other tools wrote")
    class OtherWriters {
        @Test
        @DisplayName("JPEG: the JFIF density exiftool writes")
        void jpegJfif() {
            var jpeg = MetadataSupport.exiftoolWrite("jfif.jpg", JpegEncoder.encode(picture()),
                    List.of("-JFIF:XResolution=300", "-JFIF:YResolution=200", "-JFIF:ResolutionUnit#=1"));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(300, 200)), decode(jpeg));
            var cm = MetadataSupport.exiftoolWrite("jfif-cm.jpg", JpegEncoder.encode(picture()),
                    List.of("-JFIF:XResolution=118", "-JFIF:YResolution=59", "-JFIF:ResolutionUnit#=2"));
            assertEquals(Optional.of(PixelDensity.ofDotsPerCentimeter(118, 59)), decode(cm));
        }

        @Test
        @DisplayName("JPEG: the EXIF resolution exiftool writes into a file without JFIF")
        void jpegExif() {
            var rgb = JpegEncoder.encode(picture(), JpegEncoderOptions.builder()
                    .colorSpace(JpegEncoderOptions.ColorSpace.RGB).build());
            assertTrue(decode(rgb).isEmpty());
            var jpeg = MetadataSupport.exiftoolWrite("exif.jpg", rgb,
                    List.of("-IFD0:XResolution=180", "-IFD0:YResolution=360", "-IFD0:ResolutionUnit#=2"));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(180, 360)), decode(jpeg));
            // the default JFIF header holds only a 1:1 aspect ratio, which the EXIF resolution outranks
            var jfif = MetadataSupport.exiftoolWrite("exif-jfif.jpg", JpegEncoder.encode(picture()),
                    List.of("-IFD0:XResolution=240", "-IFD0:YResolution=120", "-IFD0:ResolutionUnit#=2"));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(240, 120)), decode(jfif));
        }

        @Test
        @DisplayName("TIFF: the resolution exiftool rewrites")
        void tiff() {
            var tiff = MetadataSupport.exiftoolWrite("resolution.tif", TiffEncoder.encode(picture()),
                    List.of("-IFD0:XResolution=300", "-IFD0:YResolution=400", "-IFD0:ResolutionUnit#=3"));
            assertEquals(Optional.of(PixelDensity.ofDotsPerCentimeter(300, 400)), decode(tiff));
        }

        @Test
        @DisplayName("PNG: the pHYs chunk ffmpeg writes for its dpi and dpm options")
        void pngFfmpeg() {
            var dpi = Fixtures.read(Fixtures.testImage("png", 32, 24, List.of("-dpi", "300")));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(11811, 11811)), decode(dpi));
            var dpm = Fixtures.read(Fixtures.testImage("png", 32, 24, List.of("-dpm", "4000")));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(4000, 4000)), decode(dpm));
        }

        @Test
        @DisplayName("TIFF and BMP: the 150 dpi ffmpeg writes for its dpi option and the unset BMP resolution")
        void tiffAndBmpFfmpeg() {
            var tiff = Fixtures.read(Fixtures.testImage("tif", 32, 24, List.of("-dpi", "150")));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(150)), decode(tiff));
            var bmp = Fixtures.read(Fixtures.testImage("bmp", 32, 24, List.of()));
            assertTrue(decode(bmp).isEmpty());
        }
    }

    @Test
    @DisplayName("is read from the committed fuzz seeds, those ffmpeg wrote and those edited to carry one")
    void fuzzSeeds() {
        var seeds = FuzzSeeds.directory("com.github.auties00.smalt.image.fuzz.ImageFuzzTest", "jpeg").getParent();
        Function<String, Optional<PixelDensity>> read = name -> decode(Fixtures.read(seeds.resolve(name)));
        assertEquals(Optional.of(PixelDensity.ofAspectRatio(1, 1)), read.apply("jpeg/valid-baseline-420.jpg"));
        assertEquals(Optional.of(PixelDensity.ofDotsPerInch(300)), read.apply("jpeg/valid-jfif-dpi.jpg"));
        assertEquals(Optional.of(PixelDensity.ofAspectRatio(1, 1)), read.apply("png/valid-rgba.png"));
        assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(11811, 11811)), read.apply("png/valid-phys-meter.png"));
        assertEquals(Optional.empty(), read.apply("bmp/valid-24bit.bmp"));
        assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(2835, 3780)), read.apply("bmp/valid-density.bmp"));
        assertEquals(Optional.empty(), read.apply("jp2/valid-jp2.jp2"));
        assertEquals(Optional.of(perMeter(PixelDensity.ofDotsPerInch(300))), read.apply("jp2/valid-res.jp2"));
    }

    @Nested
    @DisplayName("JFIF and EXIF in JPEG")
    class Jpeg {
        private static byte[] app0(int units, int x, int y) {
            return new byte[]{(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 2, (byte) units,
                    (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y, 0, 0};
        }

        static byte[] app1Exif(int unit) {
            var exif = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x011A, ExifValue.ofRational(180, 1))
                    .with(ExifData.Directory.IFD0, 0x011B, ExifValue.ofRational(90, 1))
                    .with(ExifData.Directory.IFD0, 0x0128, ExifValue.ofShort(unit)).toBytes(ByteOrder.LITTLE_ENDIAN);
            var segment = new ByteArrayOutputStream();
            segment.write(0xFF);
            segment.write(0xE1);
            var length = 2 + 6 + exif.length;
            segment.write(length >> 8);
            segment.write(length);
            segment.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
            segment.writeBytes(exif);
            return segment.toByteArray();
        }

        /** Returns a JPEG without JFIF segment holding the given segments after its SOI. */
        private static byte[] withSegments(byte[]... segments) {
            var jpeg = JpegEncoder.encode(picture(), JpegEncoderOptions.builder()
                    .colorSpace(JpegEncoderOptions.ColorSpace.RGB).build());
            var out = new ByteArrayOutputStream();
            out.write(jpeg, 0, 2);
            for (var segment : segments) {
                out.writeBytes(segment);
            }
            out.write(jpeg, 2, jpeg.length - 2);
            return out.toByteArray();
        }

        @Test
        @DisplayName("reads every JFIF unit and keeps the first JFIF segment")
        void units() {
            assertEquals(Optional.of(PixelDensity.ofAspectRatio(2, 3)), decode(withSegments(app0(0, 2, 3))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72, 96)), decode(withSegments(app0(1, 72, 96))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerCentimeter(65535, 1)),
                    decode(withSegments(app0(2, 65535, 1))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72, 96)),
                    decode(withSegments(app0(1, 72, 96), app0(2, 10, 10))));
        }

        @Test
        @DisplayName("ignores a JFIF density of zero or of an unknown unit, falling back to the EXIF resolution")
        void invalid() {
            assertTrue(decode(withSegments(app0(1, 0, 72))).isEmpty());
            assertTrue(decode(withSegments(app0(1, 72, 0))).isEmpty());
            assertTrue(decode(withSegments(app0(3, 72, 72))).isEmpty());
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(180, 90)),
                    decode(withSegments(app0(3, 72, 72), app1Exif(2))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(72, 72)),
                    decode(withSegments(app0(1, 72, 72), app1Exif(3))));
        }

        @Test
        @DisplayName("prefers a physical EXIF resolution to a JFIF aspect ratio, and the JFIF aspect ratio to an EXIF one")
        void precedence() {
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(180, 90)),
                    decode(withSegments(app0(0, 1, 1), app1Exif(2))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerCentimeter(180, 90)),
                    decode(withSegments(app1Exif(3), app0(0, 1, 1))));
            assertEquals(Optional.of(PixelDensity.ofAspectRatio(2, 3)),
                    decode(withSegments(app0(0, 2, 3), app1Exif(1))));
            assertEquals(Optional.of(PixelDensity.ofAspectRatio(2, 3)),
                    decode(withSegments(app0(0, 2, 3), app1Exif(4))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerCentimeter(10, 20)),
                    decode(withSegments(app0(2, 10, 20), app1Exif(2))));
        }

        @ParameterizedTest(name = "unit {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("reads the EXIF IFD0 resolution of a file without JFIF")
        void exif(int unit) {
            var expected = switch (unit) {
                case 1 -> Optional.of(PixelDensity.ofAspectRatio(180, 90));
                case 2 -> Optional.of(PixelDensity.ofDotsPerInch(180, 90));
                case 3 -> Optional.of(PixelDensity.ofDotsPerCentimeter(180, 90));
                default -> Optional.<PixelDensity>empty();
            };
            assertEquals(expected, decode(withSegments(app1Exif(unit))));
        }
    }

    @Nested
    @DisplayName("pHYs in PNG")
    class Png {
        private static byte[] chunk(String type, byte[] data, boolean corrupt) {
            var out = new ByteArrayOutputStream();
            out.write(data.length >>> 24);
            out.write(data.length >>> 16);
            out.write(data.length >>> 8);
            out.write(data.length);
            var name = type.getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(name);
            out.writeBytes(data);
            var crc = new CRC32();
            crc.update(name);
            crc.update(data);
            var value = crc.getValue() ^ (corrupt ? 1 : 0);
            out.write((int) (value >>> 24));
            out.write((int) (value >>> 16));
            out.write((int) (value >>> 8));
            out.write((int) value);
            return out.toByteArray();
        }

        private static byte[] phys(long x, long y, int unit) {
            return new byte[]{(byte) (x >>> 24), (byte) (x >>> 16), (byte) (x >>> 8), (byte) x, (byte) (y >>> 24),
                    (byte) (y >>> 16), (byte) (y >>> 8), (byte) y, (byte) unit};
        }

        /** Inserts chunks before the first chunk of a type in a PNG without pHYs. */
        private static byte[] insertBefore(String type, byte[]... chunks) {
            var png = PngEncoder.encode(picture());
            var position = 8;
            while (!new String(png, position + 4, 4, StandardCharsets.ISO_8859_1).equals(type)) {
                var length = ((png[position] & 0xFF) << 24) | ((png[position + 1] & 0xFF) << 16)
                        | ((png[position + 2] & 0xFF) << 8) | (png[position + 3] & 0xFF);
                position += 12 + length;
            }
            var out = new ByteArrayOutputStream();
            out.write(png, 0, position);
            for (var chunk : chunks) {
                out.writeBytes(chunk);
            }
            out.write(png, position, png.length - position);
            return out.toByteArray();
        }

        @Test
        @DisplayName("reads the first pHYs chunk before the image data")
        void valid() {
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(2835, 3000)),
                    decode(insertBefore("IDAT", chunk("pHYs", phys(2835, 3000, 1), false))));
            assertEquals(Optional.of(PixelDensity.ofAspectRatio(1, 2)),
                    decode(insertBefore("IDAT", chunk("pHYs", phys(1, 2, 0), false))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(2147483647L, 1)),
                    decode(insertBefore("IDAT", chunk("pHYs", phys(2147483647L, 1, 1), false))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(10, 20)),
                    decode(insertBefore("IDAT", chunk("pHYs", phys(10, 20, 1), false),
                            chunk("pHYs", phys(30, 40, 1), false))));
        }

        @Test
        @DisplayName("ignores a pHYs chunk that is invalid, corrupt, mis-sized or after the image data")
        void invalid() {
            for (var data : List.of(phys(0, 1, 1), phys(1, 0, 1), phys(2147483648L, 1, 1), phys(1, 1, 2),
                    new byte[8], new byte[10])) {
                assertTrue(decode(insertBefore("IDAT", chunk("pHYs", data, false))).isEmpty());
            }
            assertTrue(decode(insertBefore("IDAT", chunk("pHYs", phys(2835, 2835, 1), true))).isEmpty());
            assertTrue(decode(insertBefore("IEND", chunk("pHYs", phys(2835, 2835, 1), false))).isEmpty());
            // a damaged first chunk still decides: the second one is a duplicate
            for (var first : List.of(phys(0, 1, 1), new byte[8], new byte[10])) {
                assertTrue(decode(insertBefore("IDAT", chunk("pHYs", first, false),
                        chunk("pHYs", phys(2835, 2835, 1), false))).isEmpty());
            }
            assertTrue(decode(insertBefore("IDAT", chunk("pHYs", phys(2835, 2835, 1), true),
                    chunk("pHYs", phys(2835, 2835, 1), false))).isEmpty());
        }
    }

    @Nested
    @DisplayName("resolution fields in TIFF")
    class Tiff {
        /** Returns the file offset of the IFD0 entry of a tag. */
        private static int entry(byte[] tiff, int tag) {
            var little = tiff[0] == 'I';
            var ifd = (int) u32(tiff, 4, little);
            var count = u16(tiff, ifd, little);
            for (var i = 0; i < count; i++) {
                var at = ifd + 2 + 12 * i;
                if (u16(tiff, at, little) == tag) {
                    return at;
                }
            }
            throw new AssertionError("no tag " + tag);
        }

        private static int u16(byte[] data, int at, boolean little) {
            return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
        }

        private static long u32(byte[] data, int at, boolean little) {
            return little ? (u16(data, at, true) | (long) u16(data, at + 2, true) << 16)
                    : ((long) u16(data, at, false) << 16 | u16(data, at + 2, false));
        }

        private static void put16(byte[] data, int at, int value, boolean little) {
            data[at] = (byte) (little ? value : value >> 8);
            data[at + 1] = (byte) (little ? value >> 8 : value);
        }

        @Test
        @DisplayName("takes inches when ResolutionUnit is absent")
        void missingUnit() {
            var tiff = TiffEncoder.encode(picture(), TiffEncoderOptions.builder()
                    .density(PixelDensity.ofDotsPerCentimeter(40, 41)).build());
            // tag 298 is unassigned and keeps the directory in tag order
            put16(tiff, entry(tiff, 296), 298, tiff[0] == 'I');
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(40, 41)), decode(tiff));
        }

        @Test
        @DisplayName("gives none for an unknown unit or a zero resolution, and still decodes the page")
        void invalid() {
            var tiff = TiffEncoder.encode(picture());
            var little = tiff[0] == 'I';
            var unit = tiff.clone();
            put16(unit, entry(unit, 296) + 8, 4, little);
            assertTrue(decode(unit).isEmpty());
            var zero = tiff.clone();
            var value = (int) u32(zero, entry(zero, 282) + 8, little);
            zero[value] = zero[value + 1] = zero[value + 2] = zero[value + 3] = 0;
            assertTrue(decode(zero).isEmpty());
            try (var pages = TiffDecoder.openPages(MediaSource.of(zero))) {
                assertTrue(pages.page(0).density().isEmpty());
            }
            // a YResolution of type ASCII is not numeric
            var text = tiff.clone();
            put16(text, entry(text, 283) + 2, 2, little);
            assertTrue(decode(text).isEmpty());
        }
    }

    @Nested
    @DisplayName("pixels per meter in BMP")
    class Bmp {
        private static byte[] withPixelsPerMeter(int x, int y) {
            var bmp = BmpEncoder.encode(picture());
            for (var i = 0; i < 4; i++) {
                bmp[38 + i] = (byte) (x >> 8 * i);
                bmp[42 + i] = (byte) (y >> 8 * i);
            }
            return bmp;
        }

        @Test
        @DisplayName("reads two positive values and ignores zero or negative ones")
        void values() {
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(1, Integer.MAX_VALUE)),
                    decode(withPixelsPerMeter(1, Integer.MAX_VALUE)));
            assertTrue(decode(withPixelsPerMeter(0, 2835)).isEmpty());
            assertTrue(decode(withPixelsPerMeter(2835, 0)).isEmpty());
            assertTrue(decode(withPixelsPerMeter(-2835, 2835)).isEmpty());
        }

        @Test
        @DisplayName("prefers the header to the density of an embedded PNG and falls back to it")
        void embedded() {
            var png = PngEncoder.encode(picture(), PngEncoderOptions.builder()
                    .density(PixelDensity.ofAspectRatio(1, 2)).build());
            var header = BmpEncoder.encode(picture());
            // a BITMAPINFOHEADER of compression BI_PNG whose pixel data is the PNG file
            var bmp = new byte[54 + png.length];
            System.arraycopy(header, 0, bmp, 0, 54);
            System.arraycopy(png, 0, bmp, 54, png.length);
            put32(bmp, 2, bmp.length);
            put32(bmp, 10, 54);
            put32(bmp, 14, 40);
            bmp[28] = 0;
            bmp[29] = 0;
            put32(bmp, 30, 5);
            put32(bmp, 34, png.length);
            assertEquals(Optional.of(PixelDensity.ofAspectRatio(1, 2)), decode(bmp));
            put32(bmp, 38, 3780);
            put32(bmp, 42, 3780);
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(3780, 3780)), decode(bmp));
        }

        private static void put32(byte[] data, int at, int value) {
            for (var i = 0; i < 4; i++) {
                data[at + i] = (byte) (value >> 8 * i);
            }
        }
    }

    @Nested
    @DisplayName("resolution boxes in JPEG 2000")
    class Jp2 {
        private static byte[] box(String type, byte[]... contents) {
            var out = new ByteArrayOutputStream();
            var length = 8;
            for (var content : contents) {
                length += content.length;
            }
            out.write(length >>> 24);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
            out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
            for (var content : contents) {
                out.writeBytes(content);
            }
            return out.toByteArray();
        }

        private static byte[] resolution(int vn, int vd, int hn, int hd, int ve, int he) {
            return new byte[]{(byte) (vn >> 8), (byte) vn, (byte) (vd >> 8), (byte) vd, (byte) (hn >> 8), (byte) hn,
                    (byte) (hd >> 8), (byte) hd, (byte) ve, (byte) he};
        }

        /** Appends a box to the JP2 header box of a file without a resolution box. */
        private static byte[] withHeaderBox(byte[] box) {
            var jp2 = Jp2Encoder.encode(picture());
            var position = 0;
            while (!new String(jp2, position + 4, 4, StandardCharsets.ISO_8859_1).equals("jp2h")) {
                position += ((jp2[position] & 0xFF) << 24) | ((jp2[position + 1] & 0xFF) << 16)
                        | ((jp2[position + 2] & 0xFF) << 8) | (jp2[position + 3] & 0xFF);
            }
            var length = ((jp2[position] & 0xFF) << 24) | ((jp2[position + 1] & 0xFF) << 16)
                    | ((jp2[position + 2] & 0xFF) << 8) | (jp2[position + 3] & 0xFF);
            var out = new ByteArrayOutputStream();
            out.write(jp2, 0, position + length);
            out.writeBytes(box);
            out.write(jp2, position + length, jp2.length - position - length);
            var result = out.toByteArray();
            var grown = length + box.length;
            result[position] = (byte) (grown >>> 24);
            result[position + 1] = (byte) (grown >>> 16);
            result[position + 2] = (byte) (grown >>> 8);
            result[position + 3] = (byte) grown;
            return result;
        }

        @Test
        @DisplayName("reads N / D * 10^E per meter, vertical first, with signed exponents")
        void terms() {
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(4000, 2835)),
                    decode(withHeaderBox(box("res ", box("resc", resolution(2835, 1, 40, 1, 0, 2))))));
            var negative = decode(withHeaderBox(box("res ", box("resc", resolution(1, 4, 3, 2, -2, -1)))))
                    .orElseThrow();
            assertEquals(0.15, negative.horizontal(), 1e-15);
            assertEquals(0.0025, negative.vertical(), 1e-15);
        }

        @Test
        @DisplayName("prefers the capture resolution to the display one and falls back to the display one")
        void preference() {
            var capture = box("resc", resolution(100, 1, 200, 1, 0, 0));
            var display = box("resd", resolution(300, 1, 400, 1, 0, 0));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(200, 100)),
                    decode(withHeaderBox(box("res ", display, capture))));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(400, 300)), decode(withHeaderBox(box("res ",
                    display))));
            var invalidCapture = box("resc", resolution(0, 1, 200, 1, 0, 0));
            assertEquals(Optional.of(PixelDensity.ofDotsPerMeter(400, 300)),
                    decode(withHeaderBox(box("res ", invalidCapture, display))));
        }

        @Test
        @DisplayName("ignores zero terms, short boxes, other boxes and a sub-box running past its parent")
        void invalid() {
            for (var content : List.of(resolution(1, 0, 1, 1, 0, 0), resolution(1, 1, 0, 1, 0, 0),
                    new byte[9])) {
                assertTrue(decode(withHeaderBox(box("res ", box("resc", content)))).isEmpty());
            }
            assertTrue(decode(withHeaderBox(box("res ", box("resx", resolution(1, 1, 1, 1, 0, 0))))).isEmpty());
            var overrun = box("res ", box("resc", resolution(1, 1, 1, 1, 0, 0)));
            overrun[11] = 40;
            var decoded = Images.decode(MediaSource.of(withHeaderBox(overrun)));
            assertTrue(decoded.density().isEmpty());
            assertEquals(16, decoded.picture().width());
        }
    }
}
