package com.github.auties00.smalt.image;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.metadata.XmpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the metadata settings of {@link ImageEncoderOptions}: defaults, copies, the EXIF identifier and
 * byte-order checks, resets, value equality and {@code metadataOf}.
 */
class ImageEncoderMetadataTest {
    static final byte[] TIFF = {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Test
    @DisplayName("records no metadata by default")
    void defaults() {
        var options = ImageEncoderOptions.defaults();
        assertTrue(options.colorProfile().isEmpty());
        assertTrue(options.exif().isEmpty());
        assertTrue(options.xmp().isEmpty());
        assertTrue(options.orientation().isEmpty());
        assertEquals(options, ImageEncoderOptions.builder().build());
        assertTrue(ImageEncoderOptions.lossless().exif().isEmpty());
    }

    @Test
    @DisplayName("copies the EXIF and XMP arrays in and out and strips the JPEG identifier")
    void copies() {
        var exif = TIFF.clone();
        var xmp = "<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8);
        var options = ImageEncoderOptions.builder().exif(exif).xmp(xmp).build();
        exif[0] = 'M';
        xmp[0] = 'X';
        assertArrayEquals(TIFF, options.exif().orElseThrow());
        assertEquals('<', options.xmp().orElseThrow()[0]);
        options.exif().orElseThrow()[0] = 'Z';
        assertArrayEquals(TIFF, options.exif().orElseThrow());
        var prefixed = new byte[6 + TIFF.length];
        System.arraycopy("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, prefixed, 0, 6);
        System.arraycopy(TIFF, 0, prefixed, 6, TIFF.length);
        assertArrayEquals(TIFF, ImageEncoderOptions.builder().exif(prefixed).build().exif().orElseThrow());
    }

    @Test
    @DisplayName("rejects an EXIF block without a TIFF byte-order mark when built")
    void checksExif() {
        var builder = ImageEncoderOptions.builder().exif("not tiff".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> ImageEncoderOptions.builder().exif(new byte[4]).build());
        assertDoesNotThrow(() -> ImageEncoderOptions.builder().exif(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8}).build());
        assertThrows(NullPointerException.class, () -> ImageEncoderOptions.builder().exif(null));
        assertThrows(NullPointerException.class, () -> ImageEncoderOptions.builder().colorProfile(null));
        assertThrows(NullPointerException.class, () -> ImageEncoderOptions.builder().orientation(null));
    }

    @Test
    @DisplayName("resets every metadata setting with its named reset and compares by value")
    void resets() {
        var set = ImageEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).exif(TIFF)
                .xmp(XmpPacket.empty().with(XmpPacket.DC, "format", "image/png")).orientation(Orientation.ROTATE_90)
                .quality(80).build();
        var same = set.toBuilder().build();
        assertEquals(set, same);
        assertEquals(set.hashCode(), same.hashCode());
        assertNotEquals(set, set.toBuilder().noExif().build());
        var reset = set.toBuilder().noColorProfile().noExif().noXmp().automaticOrientation().build();
        assertEquals(ImageEncoderOptions.ofQuality(80), reset);
        assertTrue(set.toString().contains("exif=14 bytes"), set.toString());
        assertEquals("image/png", XmpPacket.parse(set.xmp().orElseThrow()).text(XmpPacket.DC, "format")
                .orElseThrow());
    }

    @Test
    @DisplayName("takes the metadata of a decoded image, leaving out an implied sRGB profile")
    void metadataOf() {
        var picture = RgbaPicture.allocate(2, 2, false);
        var xmp = "<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8);
        var decoded = new DecodedImage(picture, ImageFormat.PNG, Orientation.ROTATE_180, 2, 2, Optional.empty(),
                Optional.of(TIFF), Optional.of(xmp), Optional.of(ColorProfile.srgb()));
        var options = ImageEncoderOptions.builder().metadataOf(decoded).build();
        assertTrue(options.colorProfile().isEmpty());
        assertArrayEquals(TIFF, options.exif().orElseThrow());
        assertArrayEquals(xmp, options.xmp().orElseThrow());
        assertEquals(Orientation.ROTATE_180, options.orientation().orElseThrow());
        var p3 = new DecodedImage(picture, ImageFormat.JPEG, Orientation.NORMAL, 2, 2,
                Optional.of(ColorProfile.displayP3().toByteArray()), Optional.empty(),
                Optional.of(ColorProfile.displayP3()));
        var withProfile = ImageEncoderOptions.builder().exif(TIFF).metadataOf(p3).build();
        assertEquals(ColorProfile.displayP3(), withProfile.colorProfile().orElseThrow());
        assertTrue(withProfile.exif().isEmpty(), "the image's absent EXIF replaces the builder's");
        var explicitSrgb = new DecodedImage(picture, ImageFormat.JPEG, Orientation.NORMAL, 2, 2,
                Optional.of(ColorProfile.srgb().toByteArray()), Optional.empty(), Optional.of(ColorProfile.srgb()));
        assertTrue(ImageEncoderOptions.builder().metadataOf(explicitSrgb).build().colorProfile().isPresent(),
                "an embedded sRGB profile is kept");
    }
}
