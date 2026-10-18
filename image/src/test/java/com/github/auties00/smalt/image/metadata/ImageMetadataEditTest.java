package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.metadata.XmpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the builder of {@link ImageMetadataEdit}: the color profile setter, the keep resets that
 * undo a replacement or a removal, and the other-metadata switch; and the raw EXIF accessor of
 * {@link ImageMetadata}.
 */
@DisplayName("ImageMetadataEdit.Builder")
class ImageMetadataEditTest {
    @Nested
    @DisplayName("keep resets")
    class Keep {
        @Test
        @DisplayName("each keepX() undoes a replacement, giving back the edit that changes nothing")
        void undoReplace() {
            var exif = new ExifData(Map.of(ExifData.ORIENTATION, ExifValue.ofShort(1)), Map.of(), Map.of());
            var edit = ImageMetadataEdit.builder()
                    .exif(exif).xmp(XmpPacket.empty())
                    .iptc(IptcData.of(List.of())).colorProfile(ColorProfile.srgb())
                    .comments(List.of("c")).text(Map.of("k", "v"))
                    .keepExif().keepXmp().keepIptc().keepIccProfile().keepComments().keepText()
                    .build();
            assertEquals(ImageMetadataEdit.keepAll(), edit);
        }

        @Test
        @DisplayName("each keepX() undoes a removal")
        void undoRemove() {
            var edit = ImageMetadataEdit.stripAll().toBuilder()
                    .keepExif().keepXmp().keepIptc().keepIccProfile().keepComments().keepText()
                    .build();
            for (var change : List.of(edit.exif(), edit.xmp(), edit.iptc(), edit.colorProfile(), edit.comments(),
                    edit.text())) {
                assertInstanceOf(ImageMetadataEdit.Change.Keep.class, change);
            }
        }
    }

    @Nested
    @DisplayName("colorProfile and removeOtherMetadata")
    class Setters {
        @Test
        @DisplayName("colorProfile(profile) replaces the ICC profile and build() rejects a profile without ICC form")
        void colorProfile() {
            var profile = ColorProfile.displayP3();
            var edit = ImageMetadataEdit.builder().colorProfile(profile).build();
            assertEquals(new ImageMetadataEdit.Change.Replace<>(profile), edit.colorProfile());
            var hdr = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                    ColorPrimaries.BT2020, TransferCharacteristics.PQ));
            var builder = ImageMetadataEdit.builder().colorProfile(hdr);
            assertThrows(MediaException.Unsupported.class, builder::build);
            assertEquals(ImageMetadataEdit.keepAll().toBuilder().colorProfile(profile).build(), edit);
        }

        @Test
        @DisplayName("removeOtherMetadata(flag) sets removesOtherMetadata() and the last call wins")
        void removeOtherMetadata() {
            assertTrue(ImageMetadataEdit.builder().removeOtherMetadata(true).build().removesOtherMetadata());
            assertEquals(ImageMetadataEdit.builder().removeOtherMetadata(true).build(),
                    ImageMetadataEdit.builder().removeOtherMetadata(true).build());
            assertEquals(ImageMetadataEdit.keepAll(),
                    ImageMetadataEdit.builder().removeOtherMetadata(true).removeOtherMetadata(false).build());
        }
    }

    @Nested
    @DisplayName("ImageMetadata.exifBytes")
    class ExifBytes {
        @Test
        @DisplayName("returns a copy of the EXIF block that exif() parses, and is empty without one")
        void copy() {
            var exif = new ExifData(Map.of(ExifData.ORIENTATION, ExifValue.ofShort(6)), Map.of(), Map.of());
            var picture = RgbaPicture.allocate(16, 8, false);
            java.util.Arrays.fill(picture.pixels(), (byte) 0x7F);
            var jpeg = ImageMetadataEditor.apply(JpegEncoder.encode(picture), ImageMetadataEdit.builder().exif(exif).build());
            var metadata = ImageMetadata.read(jpeg);
            var bytes = metadata.exifBytes().orElseThrow();
            assertArrayEquals(metadata.exifBytes().orElseThrow(), bytes);
            bytes[0] ^= 1;
            assertEquals(ExifData.parse(metadata.exifBytes().orElseThrow()), metadata.exif().orElseThrow());
            var stripped = ImageMetadataEditor.apply(jpeg, ImageMetadataEdit.stripAll());
            assertTrue(ImageMetadata.read(stripped).exifBytes().isEmpty());
        }
    }
}
