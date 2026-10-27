package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.ExifBlockBuilder;
import com.github.auties00.smalt.testkit.image.SyntheticProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the metadata of {@link ImageEncoderOptions} through the AVIF provider: the color profile, the EXIF and
 * XMP items, the orientation as {@code irot} and {@code imir} and in EXIF, and the profile of image sequences.
 */
class AvifEncodeMetadataTest {
    static byte[] xmp() {
        return XmpPacket.empty().with(XmpPacket.XMP, "CreatorTool", "Smalt").with(XmpPacket.TIFF, "Orientation", "1")
                .toBytes();
    }

    static DecodedImage decode(byte[] file, boolean srgb) {
        return Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(srgb).build());
    }

    /**
     * {@return the value of the IFD0 orientation entry of a little-endian EXIF block, or -1}
     */
    static int orientationEntry(byte[] tiff) {
        var ifd = u32(tiff, 4);
        var count = (tiff[ifd] & 0xFF) | (tiff[ifd + 1] & 0xFF) << 8;
        for (var i = 0; i < count; i++) {
            var at = ifd + 2 + 12 * i;
            if (((tiff[at] & 0xFF) | (tiff[at + 1] & 0xFF) << 8) == 0x0112) {
                return (tiff[at + 8] & 0xFF) | (tiff[at + 9] & 0xFF) << 8;
            }
        }
        return -1;
    }

    static int u32(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | (data[at + 3] & 0xFF) << 24;
    }

    static int occurrences(byte[] data, String text) {
        var part = text.getBytes(StandardCharsets.US_ASCII);
        var count = 0;
        outer:
        for (var i = 0; i + part.length <= data.length; i++) {
            for (var j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("records the profile, the EXIF and XMP items byte for byte and the orientation")
    void still() {
        var exif = ExifBlockBuilder.camera(true, 1).build();
        var options = ImageEncoderOptions.builder().quality(90).colorProfile(ColorProfile.displayP3()).exif(exif)
                .xmp(xmp()).orientation(Orientation.ROTATE_90).build();
        var picture = SyntheticProfiles.wideGradient(48, 32);
        var file = Images.encode(picture, ImageFormat.AVIF, options);
        var decoded = decode(file, false);
        assertEquals(ColorProfile.displayP3(), decoded.colorProfile().orElseThrow());
        assertEquals(Orientation.ROTATE_90, decoded.orientation());
        var stored = decoded.exif().orElseThrow();
        assertEquals(6, orientationEntry(stored));
        assertEquals("6", XmpPacket.parse(decoded.xmp().orElseThrow()).text(XmpPacket.TIFF, "Orientation")
                .orElseThrow());
        var expected = ImageOps.toRgba(ImageOps.orient(ImageOps.convertColor(picture, ColorProfile.displayP3(),
                ColorProfile.srgb()), Orientation.ROTATE_90));
        var shown = ImageOps.toRgba(ImageOps.orient(decode(file, true).picture(), Orientation.ROTATE_90));
        Tolerance.meanAbs(3).assertWithin("AVIF in sRGB", expected, shown);
    }

    @Test
    @DisplayName("follows the orientation of the EXIF block when no orientation is given")
    void exifOrientation() {
        var options = ImageEncoderOptions.builder().exif(ExifBlockBuilder.camera(false, 8).build()).build();
        var decoded = decode(Images.encode(SyntheticProfiles.wideGradient(16, 16), ImageFormat.AVIF, options), false);
        assertEquals(Orientation.ROTATE_270, decoded.orientation());
    }

    @Test
    @DisplayName("converts a CMYK picture through its profile, which AVIF cannot record")
    void cmyk() {
        var profile = ColorProfile.parse(SyntheticProfiles.cmyk());
        var picture = SyntheticProfiles.cmykPicture(32, 32);
        var file = Images.encode(picture, ImageFormat.AVIF, ImageEncoderOptions.lossless().toBuilder()
                .colorProfile(profile).build());
        var decoded = decode(file, false);
        assertTrue(decoded.colorProfile().isEmpty() || decoded.colorProfile().orElseThrow().isSrgb());
        var expected = ImageOps.toRgba(ImageOps.toSrgb(new DecodedImage(picture, ImageFormat.AVIF, Orientation.NORMAL,
                32, 32, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(profile)))
                .picture());
        Tolerance.maxAbs(1).assertWithin("CMYK through AVIF", expected, ImageOps.toRgba(decoded.picture()));
    }

    @Test
    @DisplayName("records the profile of an image sequence in its item and in its sample entry")
    void sequence() {
        var frame = SyntheticProfiles.wideGradient(32, 24);
        var options = ImageEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).xmp(xmp()).build();
        var sink = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(ImageFormat.AVIF, 32, 24, options, sink)) {
            writer.write(frame, 100_000);
            writer.write((RgbaPicture) ImageOps.flipVertical(frame), 100_000);
            writer.finish();
        }
        var file = sink.toByteArray();
        assertEquals(2, occurrences(file, "colrprof"), "the item property and the sample entry box");
        var decoded = decode(file, false);
        assertEquals(ColorProfile.adobeRgb(), decoded.colorProfile().orElseThrow());
        assertArrayEquals(xmp(), decoded.xmp().orElseThrow());
        var plain = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(ImageFormat.AVIF, 32, 24, ImageEncoderOptions.defaults(), plain)) {
            writer.write(frame, 100_000);
            writer.finish();
        }
        assertEquals(0, occurrences(plain.toByteArray(), "colrprof"));
    }
}
