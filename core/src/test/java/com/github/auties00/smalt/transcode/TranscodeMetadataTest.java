package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.tiff.ExifBlocks;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.image.ExifBlockBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers how an image transcode derives the metadata of its output from the decoded input, for every
 * {@link MetadataMode} and {@link ColorMode}, and the image transcode options that select them.
 */
class TranscodeMetadataTest {
    static final String GPS_LATITUDE = "GPSLatitude";

    static byte[] xmp() {
        return XmpPacket.empty().with(XmpPacket.DC, "format", "image/jpeg")
                .with(XmpPacket.EXIF, GPS_LATITUDE, "45,27.52N").with(XmpPacket.EXIF, "GPSLongitude", "9,11.76E")
                .with(XmpPacket.EXIF_EX, "GPSHPositioningError", "5/1").with(XmpPacket.PHOTOSHOP, "City", "Milano")
                .with(XmpPacket.PHOTOSHOP, "Headline", "Duomo")
                .with(XmpPacket.EXIF, "PixelXDimension", "640").with(XmpPacket.TIFF, "Orientation", "6").toBytes();
    }

    static DecodedImage camera(Optional<ColorProfile> profile, byte[] xmp) {
        var exif = ExifBlockBuilder.camera(true, 6).build();
        return new DecodedImage(RgbaPicture.allocate(4, 4, false), ImageFormat.JPEG, Orientation.ROTATE_90, 4, 4,
                profile.map(ColorProfile::toByteArray), Optional.of(exif), Optional.ofNullable(xmp), profile);
    }

    @Nested
    @DisplayName("EXIF and XMP")
    class Metadata {
        @Test
        @DisplayName("keeps the EXIF block without its thumbnail, with the output size and a normal orientation")
        void keep() {
            var decoded = camera(Optional.empty(), xmp());
            var options = TranscodeMetadata.of(decoded, MetadataMode.KEEP, ColorMode.KEEP_PROFILE)
                    .apply(ImageEncoderOptions.ofQuality(80), ImageFormat.PNG, 320, 240);
            assertEquals(Orientation.NORMAL, options.orientation().orElseThrow());
            var exif = options.exif().orElseThrow();
            assertTrue(ExifBlocks.hasGps(exif));
            var thumbnailless = ExifBlocks.withoutThumbnail(decoded.exif().orElseThrow());
            assertArrayEquals(ExifBlocks.withPixelDimensions(thumbnailless, 320, 240), exif);
            assertTrue(exif.length < decoded.exif().orElseThrow().length, "the thumbnail is cut off");
            // the orientation entry is still 6 here: the encoder patches it from the explicit orientation
            assertEquals(Orientation.ROTATE_90, TiffExif.orientation(exif, 0));
            var packet = XmpPacket.parse(options.xmp().orElseThrow());
            assertEquals("320", packet.text(XmpPacket.EXIF, "PixelXDimension").orElseThrow());
            assertTrue(packet.text(XmpPacket.EXIF, GPS_LATITUDE).isPresent());
            assertEquals(80, options.quality().getAsInt());
        }

        @Test
        @DisplayName("removes the GPS directory and the exif:GPS properties and keeps the rest")
        void stripLocation() {
            var decoded = camera(Optional.empty(), xmp());
            var options = TranscodeMetadata.of(decoded, MetadataMode.STRIP_LOCATION, ColorMode.KEEP_PROFILE)
                    .apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 4, 4);
            var exif = options.exif().orElseThrow();
            assertFalse(ExifBlocks.hasGps(exif));
            var text = new String(exif, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("SmaltCam") && text.contains("2026:10:25 12:34:56"));
            var packet = XmpPacket.parse(options.xmp().orElseThrow());
            assertTrue(packet.text(XmpPacket.EXIF, GPS_LATITUDE).isEmpty());
            assertTrue(packet.text(XmpPacket.EXIF, "GPSLongitude").isEmpty());
            assertTrue(packet.text(XmpPacket.EXIF_EX, "GPSHPositioningError").isEmpty());
            assertTrue(packet.text(XmpPacket.PHOTOSHOP, "City").isEmpty(), "place names go too");
            assertEquals("Duomo", packet.text(XmpPacket.PHOTOSHOP, "Headline").orElseThrow());
            assertEquals("image/jpeg", packet.text(XmpPacket.DC, "format").orElseThrow());
        }

        @Test
        @DisplayName("drops an XMP packet that does not parse when its location must go, and keeps it otherwise")
        void unparsable() {
            var garbage = "<not xmp".getBytes(StandardCharsets.UTF_8);
            var decoded = camera(Optional.empty(), garbage);
            assertTrue(TranscodeMetadata.of(decoded, MetadataMode.STRIP_LOCATION, ColorMode.KEEP_PROFILE)
                    .apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 4, 4).xmp().isEmpty());
            assertArrayEquals(garbage, TranscodeMetadata.of(decoded, MetadataMode.KEEP, ColorMode.KEEP_PROFILE)
                    .apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 4, 4).xmp().orElseThrow());
        }

        @Test
        @DisplayName("leaves out an EXIF block that is not TIFF instead of failing the transcode")
        void notTiff() {
            var broken = new DecodedImage(RgbaPicture.allocate(4, 4, false), ImageFormat.PNG, Orientation.NORMAL, 4,
                    4, Optional.empty(), Optional.of("not a TIFF block".getBytes(StandardCharsets.US_ASCII)),
                    Optional.empty(), Optional.empty());
            for (var mode : new MetadataMode[]{MetadataMode.KEEP, MetadataMode.STRIP_LOCATION}) {
                var options = TranscodeMetadata.of(broken, mode, ColorMode.KEEP_PROFILE)
                        .apply(ImageEncoderOptions.defaults(), ImageFormat.JPEG, 4, 4);
                assertTrue(options.exif().isEmpty(), mode.toString());
            }
            assertTrue(ImageEncoderOptions.builder().metadataOf(broken).build().exif().isEmpty());
        }

        @Test
        @DisplayName("leaves out of a JPEG output an EXIF block larger than its APP1 segment, and keeps it elsewhere")
        void jpegLimit() {
            var large = new ExifBlockBuilder(true).add("ifd0", ExifBlockBuilder.MAKE, 7, 70_000, new byte[70_000])
                    .build();
            var decoded = new DecodedImage(RgbaPicture.allocate(4, 4, false), ImageFormat.PNG, Orientation.NORMAL, 4,
                    4, Optional.empty(), Optional.of(large), Optional.empty(), Optional.empty());
            var metadata = TranscodeMetadata.of(decoded, MetadataMode.KEEP, ColorMode.KEEP_PROFILE);
            assertTrue(metadata.apply(ImageEncoderOptions.defaults(), ImageFormat.JPEG, 4, 4).exif().isEmpty());
            assertArrayEquals(large, metadata.apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 4, 4).exif()
                    .orElseThrow());
            var fits = new ExifBlockBuilder(true).add("ifd0", ExifBlockBuilder.MAKE, 7, 65_000, new byte[65_000])
                    .build();
            var small = new DecodedImage(RgbaPicture.allocate(4, 4, false), ImageFormat.PNG, Orientation.NORMAL, 4, 4,
                    Optional.empty(), Optional.of(fits), Optional.empty(), Optional.empty());
            assertArrayEquals(fits, TranscodeMetadata.of(small, MetadataMode.KEEP, ColorMode.KEEP_PROFILE)
                    .apply(ImageEncoderOptions.defaults(), ImageFormat.JPEG, 4, 4).exif().orElseThrow());
        }

        @Test
        @DisplayName("keeps a packet without size or location properties byte for byte")
        void untouched() {
            var plain = XmpPacket.empty().with(XmpPacket.DC, "format", "image/png").toXml(100)
                    .getBytes(StandardCharsets.UTF_8);
            var decoded = camera(Optional.empty(), plain);
            for (var mode : new MetadataMode[]{MetadataMode.KEEP, MetadataMode.STRIP_LOCATION}) {
                assertArrayEquals(plain, TranscodeMetadata.of(decoded, mode, ColorMode.KEEP_PROFILE)
                        .apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 9, 9).xmp().orElseThrow(), mode.toString());
            }
        }

        @Test
        @DisplayName("writes neither EXIF nor XMP when stripping, and replaces what the encoder options carried")
        void strip() {
            var decoded = camera(Optional.of(ColorProfile.displayP3()), xmp());
            var carried = ImageEncoderOptions.builder().exif(ExifBlockBuilder.camera(false, 1).build())
                    .xmp(xmp()).colorProfile(ColorProfile.adobeRgb()).build();
            var options = TranscodeMetadata.of(decoded, MetadataMode.STRIP, ColorMode.KEEP_PROFILE)
                    .apply(carried, ImageFormat.PNG, 4, 4);
            assertTrue(options.exif().isEmpty());
            assertTrue(options.xmp().isEmpty());
            assertEquals(ColorProfile.displayP3(), options.colorProfile().orElseThrow(),
                    "the profile follows the color mode, not the metadata mode");
        }
    }

    @Nested
    @DisplayName("color profile")
    class Color {
        @ParameterizedTest(name = "{0}")
        @EnumSource(MetadataMode.class)
        @DisplayName("records the declared profile with KEEP_PROFILE and none with CONVERT_TO_SRGB")
        void modes(MetadataMode metadata) {
            var decoded = camera(Optional.of(ColorProfile.displayP3()), null);
            assertEquals(ColorProfile.displayP3(), TranscodeMetadata.of(decoded, metadata, ColorMode.KEEP_PROFILE)
                    .apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 4, 4).colorProfile().orElseThrow());
            assertTrue(TranscodeMetadata.of(decoded, metadata, ColorMode.CONVERT_TO_SRGB)
                    .apply(ImageEncoderOptions.defaults(), ImageFormat.PNG, 4, 4).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("records no profile for pixels the input only implies are sRGB")
        void impliedSrgb() {
            var picture = RgbaPicture.allocate(2, 2, false);
            var implied = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, Optional.empty(),
                    Optional.empty(), Optional.of(ColorProfile.srgb()));
            assertNull(TranscodeMetadata.of(implied, MetadataMode.KEEP, ColorMode.KEEP_PROFILE).profile());
            assertSame(TranscodeMetadata.none(), TranscodeMetadata.of(implied, MetadataMode.STRIP,
                    ColorMode.KEEP_PROFILE));
        }
    }

    @Test
    @DisplayName("ImageTranscodeOptions keep metadata and profile by default and compare the new settings")
    void options() {
        var defaults = ImageTranscodeOptions.defaults();
        assertEquals(MetadataMode.KEEP, defaults.metadata());
        assertEquals(ColorMode.KEEP_PROFILE, defaults.color());
        var set = defaults.toBuilder().metadata(MetadataMode.STRIP_LOCATION).color(ColorMode.CONVERT_TO_SRGB).build();
        assertEquals(MetadataMode.STRIP_LOCATION, set.metadata());
        assertEquals(ColorMode.CONVERT_TO_SRGB, set.color());
        assertNotEquals(defaults, set);
        assertEquals(set, set.toBuilder().build());
        assertEquals(set.hashCode(), set.toBuilder().build().hashCode());
        assertTrue(set.toString().contains("metadata=STRIP_LOCATION"), set.toString());
        assertThrows(NullPointerException.class, () -> defaults.toBuilder().metadata(null));
        assertThrows(NullPointerException.class, () -> defaults.toBuilder().color(null));
    }
}
