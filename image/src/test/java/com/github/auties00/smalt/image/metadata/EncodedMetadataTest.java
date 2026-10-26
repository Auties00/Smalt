package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.ExifBlockBuilder;
import com.github.auties00.smalt.testkit.image.SyntheticProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the metadata of {@link ImageEncoderOptions} through every encoder provider of this artifact: the
 * color profile recorded or the pixels converted to sRGB, the EXIF block and XMP packet recorded byte for byte
 * where the format keeps them, and the orientation recorded or applied. Smalt's own decoders read the files
 * back, and exiftool, where installed, reads them independently.
 */
class EncodedMetadataTest {
    static final ColorProfile P3 = ColorProfile.displayP3();

    /**
     * The still formats of this artifact, with what each records.
     */
    enum Still {
        JPEG(ImageFormat.JPEG, true, true, true, true, false),
        PNG(ImageFormat.PNG, true, true, true, true, true),
        WEBP_LOSSY(ImageFormat.WEBP, true, true, true, true, false),
        WEBP_LOSSLESS(ImageFormat.WEBP, true, true, true, true, true),
        GIF(ImageFormat.GIF, true, false, true, false, false),
        TIFF(ImageFormat.TIFF, true, true, true, true, true),
        JP2(ImageFormat.JP2, true, true, true, true, true),
        BMP(ImageFormat.BMP, true, false, false, false, true),
        ICO(ImageFormat.ICO, false, false, false, false, true);

        final ImageFormat format;
        final boolean profile;
        final boolean exif;
        final boolean xmp;
        final boolean orientation;
        final boolean lossless;

        Still(ImageFormat format, boolean profile, boolean exif, boolean xmp, boolean orientation, boolean lossless) {
            this.format = format;
            this.profile = profile;
            this.exif = exif;
            this.xmp = xmp;
            this.orientation = orientation;
            this.lossless = lossless;
        }

        ImageEncoderOptions.Builder options() {
            return lossless && (format == ImageFormat.WEBP || format == ImageFormat.JP2)
                    ? ImageEncoderOptions.lossless().toBuilder() : ImageEncoderOptions.builder().quality(95);
        }

        /** The formats whose exact EXIF bytes survive: those that embed the block rather than rebuilding it. */
        boolean rawExif() {
            return exif && format != ImageFormat.TIFF && format != ImageFormat.JP2;
        }

        /** The formats that keep the exact XMP bytes. */
        boolean rawXmp() {
            return xmp && format != ImageFormat.TIFF && format != ImageFormat.JP2;
        }
    }

    static byte[] xmp() {
        return XmpPacket.empty().with(XmpPacket.DC, "title", XmpValue.alternative("Smalt test"))
                .with(XmpPacket.XMP, "CreatorTool", "Smalt").with(XmpPacket.TIFF, "Orientation", "1").toBytes();
    }

    static DecodedImage decode(byte[] file, boolean srgb) {
        return Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(srgb).build());
    }

    @Nested
    @DisplayName("color profile")
    class Profiles {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Still.class)
        @DisplayName("records a Display P3 profile, or converts the pixels so that they display the same")
        void displayP3(Still still) {
            var picture = SyntheticProfiles.wideGradient(64, 48);
            var file = Images.encode(picture, still.format, still.options().colorProfile(P3).build());
            var decoded = decode(file, false);
            if (still.profile) {
                assertEquals(P3, decoded.colorProfile().orElseThrow(), "the profile is recorded");
            } else {
                assertTrue(decoded.colorProfile().isEmpty() || decoded.colorProfile().orElseThrow().isSrgb());
            }
            var expected = ImageOps.toRgba(ImageOps.convertColor(picture, P3, ColorProfile.srgb()));
            var shown = ImageOps.toRgba(decode(file, true).picture());
            var naive = Tolerance.Difference.of(picture, expected);
            var error = Tolerance.Difference.of(expected, shown);
            if (still.lossless) {
                Tolerance.maxAbs(2).assertWithin(still + " in sRGB", expected, shown);
            } else {
                Tolerance.meanAbs(still == Still.GIF ? 4 : 3).assertWithin(still + " in sRGB", expected, shown);
            }
            assertTrue(error.meanAbs() < naive.meanAbs() / 3, still + ": " + error + " vs " + naive);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Still.class, names = {"JPEG", "TIFF", "JP2"})
        @DisplayName("records a CMYK profile with the inks of a format that codes CMYK")
        void cmykKept(Still still) {
            var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
            var picture = SyntheticProfiles.cmykPicture(40, 30);
            var file = Images.encode(picture, still.format, still.options().colorProfile(profile).build());
            var decoded = Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().keepChannels(true).build());
            assertEquals(profile, decoded.colorProfile().orElseThrow(), "the inks keep their profile");
            assertEquals(com.github.auties00.smalt.image.ColorModel.CMYK,
                    ((com.github.auties00.smalt.image.ChannelPicture) decoded.picture()).layout().colorModel());
            var expected = ImageOps.toSrgb(new DecodedImage(picture, still.format, Orientation.NORMAL, 40, 30,
                    Optional.empty(), Optional.empty(), Optional.of(profile))).picture();
            var shown = ImageOps.toRgba(decode(file, true).picture());
            (still.lossless ? Tolerance.maxAbs(2) : Tolerance.meanAbs(3))
                    .assertWithin(still + " in sRGB", ImageOps.toRgba(expected), shown);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Still.class, names = {"PNG", "WEBP_LOSSLESS", "GIF", "BMP", "ICO"})
        @DisplayName("converts CMYK inks to sRGB through their profile for a format without CMYK")
        void cmykConverted(Still still) {
            var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
            var picture = SyntheticProfiles.cmykPicture(40, 30);
            var file = Images.encode(picture, still.format, still.options().colorProfile(profile).build());
            var decoded = decode(file, false);
            assertTrue(decoded.colorProfile().isEmpty() || decoded.colorProfile().orElseThrow().isSrgb());
            var expected = ImageOps.toRgba(ImageOps.toSrgb(new DecodedImage(picture, still.format,
                    Orientation.NORMAL, 40, 30, Optional.empty(), Optional.empty(), Optional.of(profile))).picture());
            var shown = ImageOps.toRgba(decoded.picture());
            if (still == Still.GIF) {
                Tolerance.meanAbs(6).assertWithin("GIF", expected, shown);
            } else {
                Tolerance.maxAbs(1).assertWithin(still.toString(), expected, shown);
            }
            var naive = ImageOps.toRgba(picture);
            assertTrue(Tolerance.Difference.of(naive, shown).meanAbs() > 3,
                    "the inks went through the profile, not the naive conversion");
        }

        @Test
        @DisplayName("rejects a profile that does not describe the picture")
        void mismatch() {
            var cmyk = ColorProfile.parse(SyntheticProfiles.cmyk());
            var options = ImageEncoderOptions.builder().colorProfile(cmyk).build();
            assertThrows(IllegalArgumentException.class,
                    () -> Images.encode(SyntheticProfiles.wideGradient(8, 8), ImageFormat.PNG, options));
        }

        @Test
        @DisplayName("codes a gray-profiled picture as a gray JPEG that records the profile")
        void grayJpeg() {
            var gray = ColorProfile.grayGamma22();
            var file = Images.encode(SyntheticProfiles.wideGradient(16, 16), ImageFormat.JPEG,
                    ImageEncoderOptions.builder().colorProfile(gray).build());
            var decoded = decode(file, false);
            assertEquals(gray, decoded.colorProfile().orElseThrow());
            assertEquals(1, ImageMetadata.read(file).iccProfile().map(bytes -> 1).orElse(0));
        }
    }

    @Nested
    @DisplayName("EXIF and XMP")
    class Blocks {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Still.class)
        @DisplayName("records the EXIF block and XMP packet where the format keeps them")
        void recorded(Still still) {
            var exif = ExifBlockBuilder.camera(false, 1).build();
            var xmp = xmp();
            var options = still.options().exif(exif).xmp(xmp).build();
            var file = Images.encode(SyntheticProfiles.wideGradient(32, 24), still.format, options);
            var decoded = decode(file, false);
            if (still.rawExif()) {
                assertArrayEquals(exif, decoded.exif().orElseThrow(), "the block is embedded byte for byte");
            } else if (still.exif) {
                var parsed = ExifData.parse(decoded.exif().orElseThrow());
                assertInstanceOf(ExifValue.Ascii.class, parsed.get(ExifData.Directory.IFD0, ExifBlockBuilder.MAKE)
                        .orElseThrow());
                assertEquals("SmaltCam", ((ExifValue.Ascii) parsed.get(ExifData.Directory.IFD0, ExifBlockBuilder.MAKE)
                        .orElseThrow()).value());
                assertTrue(parsed.get(ExifData.Directory.EXIF, ExifBlockBuilder.DATE_TIME_ORIGINAL).isPresent());
                assertTrue(parsed.hasLocation());
            } else {
                assertTrue(decoded.exif().isEmpty(), still + " has no place for EXIF");
            }
            if (still.rawXmp()) {
                assertArrayEquals(xmp, decoded.xmp().orElseThrow(), "the packet is embedded byte for byte");
            } else if (still.xmp) {
                assertEquals("Smalt", XmpPacket.parse(decoded.xmp().orElseThrow()).text(XmpPacket.XMP, "CreatorTool")
                        .orElseThrow());
            } else {
                assertTrue(decoded.xmp().isEmpty(), still + " has no place for XMP");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Still.class, names = {"JPEG", "PNG", "WEBP_LOSSY", "GIF", "TIFF", "JP2"})
        @DisplayName("exiftool reads the recorded EXIF, XMP and ICC metadata")
        void exiftool(Still still) {
            var options = still.options().colorProfile(P3).exif(ExifBlockBuilder.camera(true, 1).build()).xmp(xmp())
                    .build();
            var file = Images.encode(SyntheticProfiles.wideGradient(32, 24), still.format, options);
            var tags = MetadataSupport.exiftoolRead(file, "encoded-" + still + "." + still.format.name().toLowerCase());
            assertEquals("Smalt", tags.get("XMP-xmp:CreatorTool"), tags.toString());
            var description = P3.description().orElseThrow();
            assertEquals(description, tags.get("ICC_Profile:ProfileDescription"), tags.toString());
            if (still.exif) {
                assertEquals("SmaltCam", tags.get("IFD0:Make"), tags.toString());
                assertEquals("2026:10:25 12:34:56", tags.get("ExifIFD:DateTimeOriginal"), tags.toString());
                assertTrue(tags.containsKey("GPS:GPSLatitude"), tags.toString());
            }
        }

        @Test
        @DisplayName("splits a JPEG XMP packet larger than a segment into Extended XMP segments")
        void extendedXmp() {
            var large = "x".repeat(90_000);
            var packet = XmpPacket.empty().with(XmpPacket.DC, "description", XmpValue.alternative(large))
                    .with(XmpPacket.XMP, "CreatorTool", "Smalt");
            var options = ImageEncoderOptions.builder().xmp(packet).build();
            var file = Images.encode(SyntheticProfiles.wideGradient(16, 16), ImageFormat.JPEG, options);
            var read = XmpPacket.parse(decode(file, false).xmp().orElseThrow());
            assertEquals(large, read.texts(XmpPacket.DC, "description").getFirst());
            assertEquals("Smalt", read.text(XmpPacket.XMP, "CreatorTool").orElseThrow());
            var segments = 0;
            for (var i = 0; i + 4 < file.length; i++) {
                if ((file[i] & 0xFF) == 0xFF && (file[i + 1] & 0xFF) == 0xE1) {
                    segments++;
                }
            }
            assertTrue(segments >= 3, "a main segment and at least two extended ones, " + segments);
        }

        @Test
        @DisplayName("rejects a JPEG EXIF block larger than a segment, an Ultra HDR XMP packet and a GIF packet with a zero byte")
        void limits() {
            var tooLarge = new byte[JpegEncoderOptions.MAX_EXIF + 1];
            System.arraycopy(ExifBlockBuilder.camera(true, 1).build(), 0, tooLarge, 0, 64);
            assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder().exif(tooLarge).build());
            var fits = Arrays.copyOf(tooLarge, JpegEncoderOptions.MAX_EXIF);
            var file = JpegEncoder.encode(SyntheticProfiles.wideGradient(8, 8),
                    JpegEncoderOptions.builder().exif(fits).build());
            assertArrayEquals(fits, decode(file, false).exif().orElseThrow());
            var utf16 = "<x/>".getBytes(StandardCharsets.UTF_16BE);
            assertThrows(IllegalArgumentException.class,
                    () -> com.github.auties00.smalt.image.gif.GifEncoderOptions.builder().xmp(utf16).build());
            var base = SyntheticProfiles.wideGradient(16, 16);
            var pq = ColorProfile.of(new com.github.auties00.smalt.image.ColorInfo(
                    com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY,
                    com.github.auties00.smalt.image.ColorRange.FULL, com.github.auties00.smalt.image.ColorPrimaries.BT709,
                    com.github.auties00.smalt.image.TransferCharacteristics.PQ));
            var gainMap = com.github.auties00.smalt.image.gainmap.GainMaps.compute(
                    new DecodedImage(base, ImageFormat.JPEG, Orientation.NORMAL, 16, 16, Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty()),
                    new DecodedImage(base, ImageFormat.JPEG, Orientation.NORMAL, 16, 16, Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.of(pq)),
                    com.github.auties00.smalt.image.gainmap.GainMapComputeOptions.builder().build());
            var withXmp = JpegEncoderOptions.builder().xmp(xmp()).build();
            assertThrows(IllegalArgumentException.class,
                    () -> JpegEncoder.encode(base, gainMap, withXmp, JpegEncoderOptions.defaults()));
            assertThrows(IllegalArgumentException.class,
                    () -> JpegEncoder.encode(base, gainMap, JpegEncoderOptions.defaults(), withXmp));
            var exif = ExifBlockBuilder.camera(true, 1).build();
            var ultraHdr = JpegEncoder.encode(base, gainMap, JpegEncoderOptions.builder().exif(exif).build(),
                    JpegEncoderOptions.defaults());
            assertArrayEquals(exif, decode(ultraHdr, false).exif().orElseThrow(), "EXIF goes into the primary image");
        }

        @Test
        @DisplayName("leaves out an XMP packet a format would have to parse and cannot, and serializes a UTF-16 one again for GIF")
        void unparsableXmp() {
            var picture = SyntheticProfiles.wideGradient(16, 16);
            var garbage = ("<x:xmpmeta " + "y".repeat(70_000)).getBytes(StandardCharsets.UTF_8);
            var jpeg = Images.encode(picture, ImageFormat.JPEG, ImageEncoderOptions.builder().xmp(garbage).build());
            assertTrue(decode(jpeg, false).xmp().isEmpty(), "an unsplittable packet is left out of a JPEG");
            var small = "<not xmp".getBytes(StandardCharsets.UTF_8);
            var kept = Images.encode(picture, ImageFormat.JPEG, ImageEncoderOptions.builder().xmp(small).build());
            assertArrayEquals(small, decode(kept, false).xmp().orElseThrow(), "a packet that fits is kept as given");
            var packet = XmpPacket.parse(xmp());
            // UTF-16 with its byte order mark, which XmpPacket.parse recognizes
            var utf16 = ("﻿" + packet.toXml()).getBytes(StandardCharsets.UTF_16BE);
            assertEquals(0, utf16[2], "the UTF-16 packet holds zero bytes");
            var gif = Images.encode(picture, ImageFormat.GIF, ImageEncoderOptions.builder().xmp(utf16).build());
            var read = XmpPacket.parse(decode(gif, false).xmp().orElseThrow());
            assertEquals("Smalt", read.text(XmpPacket.XMP, "CreatorTool").orElseThrow());
            var broken = "<x".getBytes(StandardCharsets.UTF_16BE);
            var none = Images.encode(picture, ImageFormat.GIF, ImageEncoderOptions.builder().xmp(broken).build());
            assertTrue(decode(none, false).xmp().isEmpty());
        }

        @Test
        @DisplayName("writes the same file as before when the options carry no metadata")
        void unchangedWithout() {
            var picture = SyntheticProfiles.wideGradient(24, 24);
            for (var format : new ImageFormat[]{ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.GIF}) {
                var plain = Images.encode(picture, format, ImageEncoderOptions.ofQuality(80));
                var stripped = Images.encode(picture, format, ImageEncoderOptions.ofQuality(80).toBuilder()
                        .exif(ExifBlockBuilder.camera(true, 1).build()).noExif().build());
                assertArrayEquals(plain, stripped, format.toString());
                assertTrue(ImageMetadata.read(plain).isEmpty() || ImageMetadata.read(plain).exif().isEmpty(),
                        format.toString());
            }
        }
    }

    @Nested
    @DisplayName("orientation")
    class Orientations {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Still.class)
        @DisplayName("records the orientation, or turns the picture upright in a format that records none")
        void recorded(Still still) {
            var picture = SyntheticProfiles.wideGradient(30, 20);
            var options = still.options().orientation(Orientation.ROTATE_90).xmp(xmp()).build();
            var file = Images.encode(picture, still.format, options);
            var decoded = decode(file, false);
            if (still.orientation) {
                assertEquals(Orientation.ROTATE_90, decoded.orientation());
                assertEquals(30, decoded.picture().width());
                assertEquals(20, decoded.picture().height());
            } else {
                assertEquals(Orientation.NORMAL, decoded.orientation());
                assertEquals(20, decoded.picture().width());
                assertEquals(30, decoded.picture().height());
                var upright = (RgbaPicture) ImageOps.orient(picture, Orientation.ROTATE_90);
                var shown = ImageOps.toRgba(decoded.picture());
                (still.lossless ? Tolerance.maxAbs(0) : Tolerance.meanAbs(4)).assertWithin(still.toString(), upright,
                        shown);
            }
            decoded.xmp().ifPresent(bytes -> assertEquals(still.orientation ? "6" : "1",
                    XmpPacket.parse(bytes).text(XmpPacket.TIFF, "Orientation").orElseThrow(), still.toString()));
        }

        @Test
        @DisplayName("patches the orientation of a given EXIF block in place")
        void patched() {
            var exif = ExifBlockBuilder.camera(true, 6).build();
            var options = ImageEncoderOptions.builder().exif(exif).orientation(Orientation.NORMAL).build();
            var file = Images.encode(SyntheticProfiles.wideGradient(10, 10), ImageFormat.PNG, options);
            var stored = decode(file, false).exif().orElseThrow();
            assertEquals(exif.length, stored.length);
            assertEquals(Orientation.NORMAL, ExifData.parse(stored).orientation());
            var differences = 0;
            for (var i = 0; i < exif.length; i++) {
                differences += exif[i] != stored[i] ? 1 : 0;
            }
            assertEquals(1, differences, "only the orientation value changes");
        }
    }

    @Nested
    @DisplayName("animations")
    class Animations {
        static byte[] animate(ImageFormat format, ImageEncoderOptions options, RgbaPicture frame) {
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(format, frame.width(), frame.height(), options, sink)) {
                writer.write(frame, 100_000);
                writer.write((RgbaPicture) ImageOps.flipHorizontal(frame), 100_000);
                writer.finish();
            }
            return sink.toByteArray();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ImageFormat.class, names = {"PNG", "WEBP", "GIF"})
        @DisplayName("records an RGB profile and the XMP packet of an animation")
        void recorded(ImageFormat format) {
            var frame = SyntheticProfiles.wideGradient(20, 16);
            var options = ImageEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).xmp(xmp()).build();
            var decoded = decode(animate(format, options, frame), false);
            assertEquals(ColorProfile.adobeRgb(), decoded.colorProfile().orElseThrow());
            assertArrayEquals(xmp(), decoded.xmp().orElseThrow());
        }

        @Test
        @DisplayName("converts the frames of an animated cursor, which records no profile")
        void cursor() {
            var frame = SyntheticProfiles.wideGradient(32, 32);
            var options = ImageEncoderOptions.builder().colorProfile(P3).build();
            var file = animate(ImageFormat.ANI, options, frame);
            var expected = ImageOps.toRgba(ImageOps.convertColor(frame, P3, ColorProfile.srgb()));
            try (var reader = Images.openAnimation(MediaSource.of(file))) {
                var first = ImageOps.toRgba(reader.read().picture());
                Tolerance.maxAbs(0).assertWithin("ANI frame", expected, first);
            }
        }

        @Test
        @DisplayName("converts gray-profiled frames for an APNG, which records only RGB profiles")
        void grayApng() {
            var frame = SyntheticProfiles.wideGradient(12, 12);
            var gray = ColorProfile.grayGamma22();
            var file = animate(ImageFormat.PNG, ImageEncoderOptions.builder().colorProfile(gray).build(), frame);
            var decoded = decode(file, false);
            assertTrue(decoded.colorProfile().isEmpty() || decoded.colorProfile().orElseThrow().isSrgb());
            var expected = ImageOps.toRgba(ImageOps.convertColor(frame, gray, ColorProfile.srgb()));
            Tolerance.maxAbs(0).assertWithin("APNG frame", expected, ImageOps.toRgba(decoded.picture()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ImageFormat.class, names = {"GIF", "ANI"})
        @DisplayName("rejects an orientation the animation format cannot record")
        void orientation(ImageFormat format) {
            var options = ImageEncoderOptions.builder().orientation(Orientation.ROTATE_270).build();
            assertThrows(MediaException.Unsupported.class,
                    () -> Images.openAnimationWriter(format, 8, 8, options, MediaSink.memory()));
        }
    }

    @Nested
    @DisplayName("pages")
    class Pages {
        @Test
        @DisplayName("records the options' metadata on every TIFF page and converts every icon entry")
        void pages() {
            var picture = SyntheticProfiles.wideGradient(16, 16);
            var options = ImageEncoderOptions.builder().colorProfile(P3).xmp(xmp()).build();
            var tiff = MediaSink.memory();
            try (var writer = Images.openPageWriter(ImageFormat.TIFF, options, tiff)) {
                writer.write(picture);
                writer.write(picture);
                writer.finish();
            }
            try (var pages = Images.openPages(MediaSource.of(tiff.toByteArray()))) {
                assertEquals(2, pages.pageCount());
                for (var index = 0; index < pages.pageCount(); index++) {
                    var page = pages.read(index);
                    assertEquals(P3, page.colorProfile().orElseThrow());
                    assertTrue(page.xmp().isPresent());
                }
            }
            var icon = MediaSink.memory();
            try (var writer = Images.openPageWriter(ImageFormat.ICO, options, icon)) {
                writer.write(picture);
                writer.write(new DecodedImage(picture, ImageFormat.PNG, Orientation.ROTATE_180, 16, 16,
                        Optional.empty(), Optional.empty(), Optional.of(ColorProfile.adobeRgb())));
                writer.finish();
            }
            try (var pages = Images.openPages(MediaSource.of(icon.toByteArray()))) {
                var first = ImageOps.toRgba(pages.read(0).picture());
                Tolerance.maxAbs(0).assertWithin("icon entry 1",
                        ImageOps.toRgba(ImageOps.convertColor(picture, P3, ColorProfile.srgb())), first);
                var second = ImageOps.toRgba(pages.read(1).picture());
                Picture adobe = ImageOps.convertColor(picture, ColorProfile.adobeRgb(), ColorProfile.srgb());
                Tolerance.maxAbs(0).assertWithin("icon entry 2",
                        ImageOps.toRgba(ImageOps.orient(adobe, Orientation.ROTATE_180)), second);
            }
        }
    }
}
