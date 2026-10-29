package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers metadata edits that replace the color profile with a high-dynamic-range one: its ICC form in JPEG, WebP
 * and TIFF files, and in PNG files the {@code cICP}, {@code mDCV} and {@code cLLI} chunks that take precedence
 * over {@code iCCP}, which a replacing profile also replaces.
 */
class HdrProfileEditTest {
    static ColorInfo pqColor() {
        return new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ)
                .withMasteringDisplay(new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                        new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 4000, 0.005))
                .withContentLightLevel(new ContentLightLevel(2000, 500));
    }

    static RgbaPicture picture(boolean gray) {
        var picture = RgbaPicture.allocate(23, 17, false);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            var v = (byte) (i * 3 + i / 41);
            pixels[i] = v;
            pixels[i + 1] = gray ? v : (byte) (i / 5);
            pixels[i + 2] = gray ? v : (byte) (255 - i % 256);
            pixels[i + 3] = (byte) 255;
        }
        return picture;
    }

    static List<String> types(byte[] png) {
        var types = new ArrayList<String>();
        for (var at = 8; at + 8 <= png.length; ) {
            var length = (png[at] & 0xFF) << 24 | (png[at + 1] & 0xFF) << 16 | (png[at + 2] & 0xFF) << 8 | png[at + 3] & 0xFF;
            types.add(new String(png, at + 4, 4, StandardCharsets.US_ASCII));
            at += 12 + length;
        }
        return types;
    }

    static ColorProfile decoded(byte[] file) {
        return Images.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).colorProfile().orElseThrow();
    }

    @Test
    @DisplayName("JPEG, WebP and TIFF files take the ICC form, which decodes back as high dynamic range")
    void iccFormats() {
        var hlg = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                ColorPrimaries.BT2020, TransferCharacteristics.HLG));
        var edit = ImageMetadataEdit.builder().colorProfile(hlg).build();
        for (var file : List.of(JpegEncoder.encode(picture(false)), WebpEncoder.encode(picture(false)),
                TiffEncoder.encode(picture(false)))) {
            var edited = ImageMetadataEditor.apply(file, edit);
            assertArrayEquals(hlg.toByteArray(), ImageMetadata.read(edited).iccProfile().orElseThrow());
            var profile = decoded(edited);
            assertTrue(profile.isHighDynamicRange());
            assertEquals(hlg, profile);
        }
    }

    @Test
    @DisplayName("a PNG takes cICP, mDCV, cLLI and iCCP, keeping the mastering metadata")
    void png() {
        var hdr = ColorProfile.of(pqColor());
        var edited = ImageMetadataEditor.apply(PngEncoder.encode(picture(false)),
                ImageMetadataEdit.builder().colorProfile(hdr).build());
        assertEquals(List.of("IHDR", "cICP", "mDCV", "cLLI", "iCCP"), types(edited).subList(0, 5));
        var profile = decoded(edited);
        assertEquals(hdr, profile);
        assertEquals(pqColor().masteringDisplay(), profile.colorInfo().orElseThrow().masteringDisplay());
        assertEquals(pqColor().contentLightLevel(), profile.colorInfo().orElseThrow().contentLightLevel());
        assertArrayEquals(hdr.toByteArray(), ImageMetadata.read(edited).iccProfile().orElseThrow());
    }

    @Test
    @DisplayName("a grayscale PNG takes the cICP chunks without the RGB iCCP")
    void grayPng() {
        var original = PngEncoder.encode(picture(true));
        assertEquals(0, original[25]);
        var edited = ImageMetadataEditor.apply(original,
                ImageMetadataEdit.builder().colorProfile(ColorProfile.of(pqColor())).build());
        assertFalse(types(edited).contains("iCCP"));
        assertTrue(types(edited).contains("cICP"));
        assertEquals(ColorProfile.of(pqColor()), decoded(edited));
    }

    @Test
    @DisplayName("replacing the profile of an HDR PNG removes its cICP, mDCV and cLLI chunks")
    void replacesCicp() {
        var hdr = PngEncoder.encode(picture(false), PngEncoderOptions.builder().colorProfile(ColorProfile.of(pqColor()))
                .build());
        assertTrue(types(hdr).contains("cICP"));
        var edited = ImageMetadataEditor.apply(hdr, ImageMetadataEdit.builder().colorProfile(ColorProfile.displayP3())
                .build());
        var types = types(edited);
        for (var type : List.of("cICP", "mDCV", "cLLI")) {
            assertFalse(types.contains(type), type);
        }
        assertEquals(1, types.stream().filter("iCCP"::equals).count());
        assertEquals(ColorProfile.displayP3(), decoded(edited));
        var hlg = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                ColorPrimaries.BT2020, TransferCharacteristics.HLG));
        var swapped = ImageMetadataEditor.apply(hdr, ImageMetadataEdit.builder().colorProfile(hlg).build());
        assertEquals(List.of("IHDR", "cICP", "iCCP"), types(swapped).subList(0, 3));
        assertEquals(1, types(swapped).stream().filter("cICP"::equals).count());
        assertEquals(1, types(swapped).stream().filter("iCCP"::equals).count());
        assertFalse(types(swapped).contains("mDCV") || types(swapped).contains("cLLI"));
        assertEquals(hlg, decoded(swapped));
        var kept = ImageMetadataEditor.apply(hdr, ImageMetadataEdit.stripAll());
        assertTrue(types(kept).contains("cICP"));
        assertEquals(ColorProfile.of(pqColor()), decoded(kept));
    }
}
