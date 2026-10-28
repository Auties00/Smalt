package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.avif.AvifEncoder;
import com.github.auties00.smalt.image.avif.AvifEncoderOptions;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoder;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.ExifBlockBuilder;
import com.github.auties00.smalt.testkit.image.SyntheticProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers what an image transcode does with the color profile, EXIF block and XMP packet of its input: Display P3
 * and CMYK JPEG, Adobe RGB TIFF and Display P3 AVIF sources, made here with a camera-like EXIF block (orientation
 * 6, GPS position, maker note, thumbnail) and an XMP packet with a location, transcoded to every encodable format.
 * The converted colors are checked against Smalt's own conversion of the source and against Little CMS
 * ({@code smalt-lcmsdump}), and the metadata against Smalt's reader and exiftool, where they are installed.
 */
class ImageTranscodeMetadataTest {
    static final int WIDTH = 96;
    static final int HEIGHT = 64;

    static final Path DIRECTORY = Scratch.directory("transcode-metadata");

    /**
     * A transcode input and how it was made.
     *
     * @param name the name of the case
     * @param file the encoded input
     * @param profile the profile of its pixels
     */
    record Source(String name, byte[] file, ColorProfile profile) {
        @Override
        public String toString() {
            return name;
        }
    }

    static byte[] xmp() {
        return XmpPacket.empty().with(XmpPacket.XMP, "CreatorTool", "Smalt camera")
                .with(XmpPacket.TIFF, "Orientation", "6").with(XmpPacket.EXIF, "GPSLatitude", "45,27.52N")
                .with(XmpPacket.EXIF, "GPSLongitude", "9,11.76E").with(XmpPacket.EXIF, "PixelXDimension", "96")
                .with(XmpPacket.PHOTOSHOP, "City", "Milano")
                .toBytes();
    }

    static byte[] exif() {
        return ExifBlockBuilder.camera(false, 6).build();
    }

    static Source p3Jpeg() {
        var options = JpegEncoderOptions.ofQuality(97).toBuilder().colorProfile(ColorProfile.displayP3())
                .chromaFormat(com.github.auties00.smalt.image.ChromaFormat.YUV444).exif(exif()).xmp(xmp()).build();
        return new Source("Display P3 JPEG", JpegEncoder.encode(SyntheticProfiles.wideGradient(WIDTH, HEIGHT), options),
                ColorProfile.displayP3());
    }

    static Source adobeTiff() {
        var options = TiffEncoderOptions.defaults().toBuilder().colorProfile(ColorProfile.adobeRgb())
                .orientation(Orientation.ROTATE_90).exif(ExifData.parse(exif())).xmp(XmpPacket.parse(xmp())).build();
        return new Source("Adobe RGB TIFF", TiffEncoder.encode(SyntheticProfiles.wideGradient(WIDTH, HEIGHT), options),
                ColorProfile.adobeRgb());
    }

    static Source cmykJpeg() {
        var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
        var options = JpegEncoderOptions.ofQuality(97).toBuilder().colorProfile(profile)
                .colorSpace(JpegEncoderOptions.ColorSpace.CMYK).exif(exif()).xmp(xmp()).build();
        return new Source("CMYK JPEG", JpegEncoder.encode(SyntheticProfiles.cmykPicture(WIDTH, HEIGHT), options), profile);
    }

    static Source p3Avif() {
        var options = AvifEncoderOptions.lossless().toBuilder().colorProfile(ColorProfile.displayP3())
                .orientation(Orientation.ROTATE_90).exif(exif()).xmp(xmp()).build();
        return new Source("Display P3 AVIF", AvifEncoder.encode(SyntheticProfiles.wideGradient(WIDTH, HEIGHT), options),
                ColorProfile.displayP3());
    }

    static List<Source> sources() {
        return List.of(p3Jpeg(), adobeTiff(), cmykJpeg(), p3Avif());
    }

    /**
     * The output formats, with what each records: the profile, EXIF and XMP, and whether it codes losslessly at
     * the default settings.
     */
    enum Target {
        JPEG(ImageFormat.JPEG, true, true, true, false),
        PNG(ImageFormat.PNG, true, true, true, true),
        WEBP(ImageFormat.WEBP, true, true, true, false),
        GIF(ImageFormat.GIF, true, false, true, false),
        BMP(ImageFormat.BMP, true, false, false, true),
        TIFF(ImageFormat.TIFF, true, true, true, true),
        ICO(ImageFormat.ICO, false, false, false, true),
        JP2(ImageFormat.JP2, true, true, true, true),
        AVIF(ImageFormat.AVIF, true, true, true, false);

        final ImageFormat format;
        final boolean profile;
        final boolean exif;
        final boolean xmp;
        final boolean lossless;

        Target(ImageFormat format, boolean profile, boolean exif, boolean xmp, boolean lossless) {
            this.format = format;
            this.profile = profile;
            this.exif = exif;
            this.xmp = xmp;
            this.lossless = lossless;
        }
    }

    static Stream<Arguments> cases() {
        var result = new ArrayList<Arguments>();
        for (var source : sources()) {
            for (var target : Target.values()) {
                result.add(Arguments.of(source, target));
            }
        }
        return result.stream();
    }

    /**
     * {@return the options of a transcode to a target, without pass-through, so that a source of the target's
     * format is decoded and encoded again too}
     */
    static ImageTranscodeOptions.Builder options(Target target) {
        return ImageTranscodeOptions.of(target.format).toBuilder().passthrough(false);
    }

    /**
     * The metadata of an output: what Smalt's metadata reader finds, or for an icon (which it does not read)
     * what the decoder returns.
     *
     * @param exif the EXIF directories
     * @param xmp the XMP packet
     * @param location whether the file records where the picture was taken
     */
    record Read(Optional<ExifData> exif, Optional<XmpPacket> xmp, boolean location) {
        static Read of(byte[] output, Target target) {
            if (target == Target.ICO) {
                var decoded = decode(output, false);
                return new Read(decoded.exif().map(ExifData::parse), decoded.xmp().map(XmpPacket::parse), false);
            }
            var metadata = ImageMetadata.read(output);
            return new Read(metadata.exif(), metadata.xmp(), metadata.hasLocation());
        }
    }

    static byte[] transcode(byte[] input, ImageTranscodeOptions options) {
        var sink = MediaSink.memory();
        Transcoder.transcodeImage(MediaSource.of(input), sink, options);
        return sink.toByteArray();
    }

    static DecodedImage decode(byte[] file, boolean srgb) {
        return Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(srgb).build());
    }

    /**
     * {@return a source decoded as stored, CMYK inks kept with their profile}
     */
    static DecodedImage decodeSource(Source source) {
        return Images.decode(MediaSource.of(source.file()), ImageDecoderOptions.builder().keepChannels(true).build());
    }

    /**
     * {@return the source as displayed in sRGB: decoded, converted through its profile by Smalt, turned upright}
     */
    static RgbaPicture displayed(Source source) {
        var decoded = ImageOps.toSrgb(decodeSource(source));
        return ImageOps.toRgba(ImageOps.orient(decoded.picture(), decoded.orientation()));
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    @DisplayName("keeps the colors: the output displays as the source does in sRGB")
    void colors(Source source, Target target) {
        var output = transcode(source.file(), options(target).build());
        var decoded = decode(output, false);
        assertEquals(Orientation.NORMAL, decoded.orientation(), "the pixels are upright");
        if (target.profile && source.profile().colorSpace() != com.github.auties00.smalt.image.color.ProfileColorSpace.CMYK) {
            assertEquals(source.profile(), decoded.colorProfile().orElseThrow(), "the source profile is kept");
        }
        var expected = displayed(source);
        var shown = ImageOps.toRgba(decode(output, true).picture());
        assertEquals(expected.width(), shown.width());
        // the sources are lossy JPEG or exact; the outputs add their own coding error
        tolerance(target).assertWithin(source + " to " + target, expected, shown);
    }

    /**
     * {@return the tolerance of an output against the source: exact but for rounding when lossless, the
     * coding error of a lossy format, the palette error of GIF}
     */
    static Tolerance tolerance(Target target) {
        return target == Target.GIF ? Tolerance.meanAbs(6)
                : target.lossless ? Tolerance.meanAbs(1).and(Tolerance.maxAbs(3)) : Tolerance.meanAbs(3);
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    @DisplayName("keeps the EXIF block without its thumbnail and with a normal orientation, and the XMP packet")
    void keepMetadata(Source source, Target target) {
        var output = transcode(source.file(), options(target).build());
        var metadata = Read.of(output, target);
        if (target.exif) {
            var exif = metadata.exif().orElseThrow();
            assertEquals(Orientation.NORMAL, exif.orientation());
            assertTrue(exif.thumbnail().isEmpty(), "the stale thumbnail is removed");
            assertTrue(exif.hasLocation());
            var make = (ExifValue.Ascii) exif.get(ExifData.Directory.IFD0, ExifBlockBuilder.MAKE).orElseThrow();
            assertEquals("SmaltCam", make.value());
            assertTrue(exif.get(ExifData.Directory.EXIF, ExifBlockBuilder.DATE_TIME_ORIGINAL).isPresent());
            var width = (ExifValue.Integers) exif.get(ExifData.Directory.EXIF, ExifBlockBuilder.PIXEL_X_DIMENSION)
                    .orElseThrow();
            assertEquals(HEIGHT, width.get(0), "the output is turned upright, " + HEIGHT + " pixels wide");
        } else {
            assertTrue(metadata.exif().isEmpty());
        }
        if (target.xmp) {
            var xmp = metadata.xmp().orElseThrow();
            assertEquals("Smalt camera", xmp.text(XmpPacket.XMP, "CreatorTool").orElseThrow());
            assertEquals("1", xmp.text(XmpPacket.TIFF, "Orientation").orElseThrow());
            assertTrue(xmp.text(XmpPacket.EXIF, "GPSLatitude").isPresent());
        } else {
            assertTrue(metadata.xmp().isEmpty());
        }
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    @DisplayName("removes only the location with STRIP_LOCATION")
    void stripLocation(Source source, Target target) {
        var output = transcode(source.file(), options(target).metadata(MetadataMode.STRIP_LOCATION).build());
        var metadata = Read.of(output, target);
        assertFalse(metadata.location());
        if (target.exif) {
            var exif = metadata.exif().orElseThrow();
            assertTrue(exif.gps().isEmpty());
            assertTrue(exif.get(ExifData.Directory.EXIF, ExifBlockBuilder.DATE_TIME_ORIGINAL).isPresent());
        }
        metadata.xmp().ifPresent(xmp -> {
            assertTrue(xmp.text(XmpPacket.EXIF, "GPSLatitude").isEmpty());
            assertTrue(xmp.text(XmpPacket.PHOTOSHOP, "City").isEmpty());
            assertEquals("Smalt camera", xmp.text(XmpPacket.XMP, "CreatorTool").orElseThrow());
        });
        // the rational 45/1 27/1 3123/100 of the latitude appears nowhere in the file
        var latitude = new byte[]{0, 0, 0, 45, 0, 0, 0, 1, 0, 0, 0, 27, 0, 0, 0, 1, 0, 0, 0x0C, 0x33, 0, 0, 0, 100};
        assertFalse(contains(output, latitude), "GPS bytes survive in the output");
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    @DisplayName("writes no EXIF or XMP with STRIP, and still records the profile")
    void strip(Source source, Target target) {
        var output = transcode(source.file(), options(target).metadata(MetadataMode.STRIP).build());
        var metadata = Read.of(output, target);
        // a TIFF page always holds the IFD0 entries that describe it, which the metadata reader returns
        metadata.exif().ifPresent(exif -> {
            assertEquals(target, Target.TIFF);
            assertTrue(exif.get(ExifData.Directory.IFD0, ExifBlockBuilder.MAKE).isEmpty());
            assertTrue(exif.exif().isEmpty());
            assertTrue(exif.gps().isEmpty());
        });
        assertTrue(metadata.xmp().isEmpty());
        if (target.profile && !source.name().startsWith("CMYK")) {
            assertEquals(source.profile(), decode(output, false).colorProfile().orElseThrow());
        }
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    @DisplayName("records no profile and holds sRGB pixels with CONVERT_TO_SRGB")
    void convert(Source source, Target target) {
        var output = transcode(source.file(), options(target).color(ColorMode.CONVERT_TO_SRGB).build());
        var decoded = decode(output, false);
        assertTrue(decoded.colorProfile().isEmpty() || decoded.colorProfile().orElseThrow().isSrgb(),
                decoded.colorProfile().toString());
        tolerance(target).assertWithin(source + " to " + target, displayed(source), ImageOps.toRgba(decoded.picture()));
    }

    @Test
    @DisplayName("copies an unchanged input byte for byte, but not when its metadata or colors change")
    void passthrough() {
        var source = p3Jpeg().file();
        var copied = JpegEncoder.encode(SyntheticProfiles.wideGradient(WIDTH, HEIGHT), JpegEncoderOptions.ofQuality(90)
                .toBuilder().colorProfile(ColorProfile.displayP3()).exif(ExifBlockBuilder.camera(false, 0).build())
                .xmp(xmp()).build());
        assertArrayEquals(copied, transcode(copied, ImageTranscodeOptions.of(ImageFormat.JPEG)));
        var stripped = transcode(copied, ImageTranscodeOptions.of(ImageFormat.JPEG).toBuilder()
                .metadata(MetadataMode.STRIP).build());
        assertFalse(java.util.Arrays.equals(copied, stripped));
        assertTrue(ImageMetadata.read(stripped).exif().isEmpty());
        var converted = transcode(source, ImageTranscodeOptions.of(ImageFormat.JPEG).toBuilder()
                .color(ColorMode.CONVERT_TO_SRGB).build());
        assertTrue(decode(converted, false).colorProfile().filter(profile -> !profile.isSrgb()).isEmpty());
    }

    @Test
    @DisplayName("flattens translucent CMYK inks for an output without alpha, converted through their profile")
    void cmykAlpha() {
        var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
        var inks = SyntheticProfiles.cmykPicture(WIDTH, HEIGHT);
        var picture = com.github.auties00.smalt.image.Channel8Picture.allocate(WIDTH, HEIGHT,
                com.github.auties00.smalt.image.PixelLayout.CMYKA);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var from = y * inks.stride() + 4 * x;
                var to = y * picture.stride() + 5 * x;
                System.arraycopy(inks.pixels(), from, picture.pixels(), to, 4);
                picture.pixels()[to + 4] = (byte) (x * 255 / (WIDTH - 1));
            }
        }
        var tiff = TiffEncoder.encode(picture, TiffEncoderOptions.defaults().toBuilder().colorProfile(profile).build());
        var background = ImageTranscodeOptions.defaults().background();
        var converted = ImageOps.toSrgb(new DecodedImage(picture, ImageFormat.TIFF, Orientation.NORMAL, WIDTH, HEIGHT,
                Optional.empty(), Optional.empty(), Optional.of(profile))).picture();
        var expected = ImageOps.toRgba(ImageOps.flatten(converted, background));
        for (var target : new Target[]{Target.BMP, Target.JPEG}) {
            var output = transcode(tiff, options(target).build());
            var decoded = decode(output, true);
            var tolerance = target == Target.BMP ? Tolerance.maxAbs(1) : Tolerance.meanAbs(3);
            tolerance.assertWithin("CMYK with alpha to " + target, expected, ImageOps.toRgba(decoded.picture()));
        }
    }

    @Test
    @DisplayName("keeps the profile and the XMP packet of an animation, or converts every frame")
    void animation() {
        var options = WebpAnimationEncoderOptions.lossless().toBuilder().colorProfile(ColorProfile.displayP3())
                .xmp(xmp()).build();
        var encoder = new WebpAnimationEncoder(WIDTH, HEIGHT, options);
        var first = SyntheticProfiles.wideGradient(WIDTH, HEIGHT);
        var second = (RgbaPicture) ImageOps.flipHorizontal(first);
        encoder.addFrame(first, 100);
        encoder.addFrame(second, 100);
        var animated = encoder.finish();
        for (var format : new ImageFormat[]{ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.GIF}) {
            var kept = transcode(animated, ImageTranscodeOptions.of(format));
            var decoded = decode(kept, false);
            assertEquals(ColorProfile.displayP3(), decoded.colorProfile().orElseThrow(), format.toString());
            assertEquals("Smalt camera", XmpPacket.parse(decoded.xmp().orElseThrow()).text(XmpPacket.XMP,
                    "CreatorTool").orElseThrow());
            var converted = transcode(animated, ImageTranscodeOptions.of(format).toBuilder()
                    .color(ColorMode.CONVERT_TO_SRGB).metadata(MetadataMode.STRIP).build());
            var plain = decode(converted, false);
            assertTrue(plain.colorProfile().isEmpty() || plain.colorProfile().orElseThrow().isSrgb());
            assertTrue(plain.xmp().isEmpty());
            var expected = ImageOps.toRgba(ImageOps.convertColor(first, ColorProfile.displayP3(), ColorProfile.srgb()));
            var tolerance = switch (format) {
                case GIF -> Tolerance.meanAbs(6);
                case WEBP -> Tolerance.meanAbs(3);
                default -> Tolerance.maxAbs(1);
            };
            tolerance.assertWithin("first frame of " + format, expected, ImageOps.toRgba(plain.picture()));
            // the frames are converted into one reused picture: the second frame must not leak into the first
            try (var reader = Images.openAnimation(MediaSource.of(converted))) {
                var frames = new ArrayList<RgbaPicture>();
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    var rgba = ImageOps.toRgba(frame.picture());
                    frames.add(RgbaPicture.of(rgba.width(), rgba.height(), rgba.stride(), rgba.pixels().clone(),
                            rgba.hasAlpha()));
                }
                assertEquals(2, frames.size(), format.toString());
                tolerance.assertWithin("first frame of " + format + " read as an animation", expected, frames.get(0));
                var expectedSecond = ImageOps.toRgba(ImageOps.convertColor(second, ColorProfile.displayP3(),
                        ColorProfile.srgb()));
                tolerance.assertWithin("second frame of " + format, expectedSecond, frames.get(1));
            }
        }
    }

    @Nested
    @DisplayName("oracles")
    class Oracles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.transcode.ImageTranscodeMetadataTest#sources")
        @DisplayName("converts to sRGB as Little CMS does")
        void littleCms(Source source) throws IOException {
            Oracle.assume(Oracle.Tool.LCMSDUMP);
            var output = transcode(source.file(), ImageTranscodeOptions.of(ImageFormat.PNG).toBuilder()
                    .color(ColorMode.CONVERT_TO_SRGB).build());
            var shown = ImageOps.toRgba(decode(output, false).picture());
            var decoded = decodeSource(source);
            var stored = decoded.picture();
            var profile = Files.write(DIRECTORY.resolve(source.name().replace(' ', '-') + ".icc"),
                    source.profile().toByteArray());
            var input = new StringBuilder();
            var channels = source.profile().channels();
            var samples = samples(stored, channels);
            for (var i = 0; i < samples.length; i += channels) {
                for (var c = 0; c < channels; c++) {
                    input.append(samples[i + c]).append(c == channels - 1 ? '\n' : ' ');
                }
            }
            // Smalt matches the 8-bit transforms of Little CMS, so the oracle runs at 8 bits too
            var lines = new String(Oracle.run(Oracle.Tool.LCMSDUMP, Duration.ofMinutes(2),
                    input.toString().getBytes(StandardCharsets.US_ASCII), List.of("pixels", profile.toString(), "8")),
                    StandardCharsets.US_ASCII).split("\\R");
            var reference = RgbaPicture.allocate(stored.width(), stored.height(), false);
            for (var pixel = 0; pixel < stored.width() * stored.height(); pixel++) {
                var rgb = lines[pixel].trim().split(" ");
                var at = (pixel / stored.width()) * reference.stride() + (pixel % stored.width()) * 4;
                for (var c = 0; c < 3; c++) {
                    reference.pixels()[at + c] = (byte) Integer.parseInt(rgb[c]);
                }
                reference.pixels()[at + 3] = (byte) 255;
            }
            var upright = ImageOps.toRgba(ImageOps.orient(reference, decoded.orientation()));
            Tolerance.offByOne().assertWithin(source + " against Little CMS", upright, shown);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.transcode.ImageTranscodeMetadataTest#exiftoolCases")
        @DisplayName("exiftool reads the kept metadata and no location after STRIP_LOCATION")
        void exiftool(Target target) {
            Oracle.assume(Oracle.Tool.EXIFTOOL);
            var kept = exiftoolRead(transcode(p3Jpeg().file(), options(target).build()), "keep", target);
            assertEquals(ColorProfile.displayP3().description().orElseThrow(), kept.get("ICC_Profile:ProfileDescription"),
                    kept.toString());
            assertEquals("Smalt camera", kept.get("XMP-xmp:CreatorTool"), kept.toString());
            if (target.exif) {
                assertEquals("SmaltCam", kept.get("IFD0:Make"), kept.toString());
                assertEquals("1", kept.get("IFD0:Orientation"), kept.toString());
                assertEquals("2026:10:25 12:34:56", kept.get("ExifIFD:DateTimeOriginal"), kept.toString());
                assertTrue(kept.containsKey("GPS:GPSLatitude"), kept.toString());
                assertFalse(kept.containsKey("IFD1:ThumbnailOffset"), kept.toString());
            }
            var stripped = exiftoolRead(transcode(p3Jpeg().file(), options(target)
                    .metadata(MetadataMode.STRIP_LOCATION).build()), "strip", target);
            for (var key : stripped.keySet()) {
                assertFalse(key.startsWith("GPS:") || key.contains(":GPSLatitude") || key.contains(":GPSLongitude"),
                        key + " in " + stripped);
            }
            if (target.exif) {
                assertEquals("SmaltCam", stripped.get("IFD0:Make"), stripped.toString());
            }
        }
    }

    static Stream<Target> exiftoolCases() {
        return Stream.of(Target.JPEG, Target.PNG, Target.WEBP, Target.TIFF, Target.JP2, Target.AVIF);
    }

    static Map<String, String> exiftoolRead(byte[] file, String name, Target target) {
        try {
            Files.createDirectories(DIRECTORY);
            var path = DIRECTORY.resolve(name + "-" + target + "." + target.format.name().toLowerCase());
            Files.write(path, file);
            var output = new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1),
                    List.of("-a", "-G1", "-s", "-n", "-charset", "UTF8", path.toString())), StandardCharsets.UTF_8);
            var result = new LinkedHashMap<String, String>();
            for (var line : output.split("\\R")) {
                var groupEnd = line.indexOf(']');
                var colon = line.indexOf(':', Math.max(groupEnd, 0));
                if (!line.startsWith("[") || groupEnd < 0 || colon < 0) {
                    continue;
                }
                result.putIfAbsent(line.substring(1, groupEnd).trim() + ":" + line.substring(groupEnd + 1, colon).trim(),
                        line.substring(colon + 1).trim());
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * {@return the 8-bit color samples of a picture, interleaved: its inks for a CMYK picture, its RGB otherwise}
     */
    static int[] samples(Picture picture, int channels) {
        if (channels == 4) {
            var cmyk = (com.github.auties00.smalt.image.Channel8Picture) picture;
            var result = new int[picture.width() * picture.height() * 4];
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    for (var c = 0; c < 4; c++) {
                        result[(y * picture.width() + x) * 4 + c] = cmyk.pixels()[y * cmyk.stride() + x * 4 + c] & 0xFF;
                    }
                }
            }
            return result;
        }
        var rgba = ImageOps.toRgba(picture);
        var result = new int[picture.width() * picture.height() * 3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var c = 0; c < 3; c++) {
                    result[(y * picture.width() + x) * 3 + c] = rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF;
                }
            }
        }
        return result;
    }

    static boolean contains(byte[] data, byte[] part) {
        outer:
        for (var i = 0; i + part.length <= data.length; i++) {
            for (var j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
