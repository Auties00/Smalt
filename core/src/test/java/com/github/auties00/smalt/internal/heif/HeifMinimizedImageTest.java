package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the transformation of the low-overhead image file format's {@code mini} box (ISO/IEC 23008-12:2024
 * Amendment 2, clause O.4) into the items of the equivalent {@code meta} box: libheif's {@code mif3} files, whose
 * chunk sizes ({@code icc_data_size_minus1} 671, {@code main_item_data_size_minus1} 1002, {@code alpha_item_data_size}
 * 219, {@code exif_data_size_minus1} 313, {@code xmp_data_size_minus1} 4275) the expectations restate, and a box
 * written field by field for the gain map, HDR, CICP and orientation fields those files do not use.
 */
@DisplayName("HEIF mini box")
class HeifMinimizedImageTest {
    private static final byte[] AV1C = {(byte) 0x81, 0x00, 0x0C, 0x00};

    private static HeifFile parse(byte[] data) {
        return HeifFile.parse(MediaSource.of(data), MediaLimits.DEFAULT);
    }

    private static byte[] vector(String name) {
        return Fixtures.read(Conformance.vector(name));
    }

    @Test
    @DisplayName("libheif's tile with alpha: an image item, an alpha item and an ICC profile")
    void alpha() {
        var data = vector("heif-libheif-simple_osm_tile_alpha.avif");
        var file = parse(data);
        assertTrue(file.hasBrand("mif3") && file.hasBrand("avif"), file.brands().toString());
        var image = file.primaryItem();
        assertEquals(1, image.id());
        assertEquals("av01", image.type());
        assertEquals(1003, image.size());
        var extent = image.property(HeifProperty.SpatialExtent.class);
        assertEquals(256, extent.width());
        assertEquals(256, extent.height());
        assertArrayEquals(new int[]{8, 8, 8}, image.property(HeifProperty.PixelInformation.class).depths());
        // no explicit CICP with an ICC profile: unspecified primaries and transfer, BT.601 matrix for 4:4:4
        assertEquals(new HeifProperty.ColorNclx(2, 2, 6, true), image.property(HeifProperty.ColorNclx.class));
        assertEquals(672, image.property(HeifProperty.ColorIcc.class).profile().length);
        assertNotNull(image.property(HeifProperty.Av1Configuration.class));
        var auxiliaries = file.auxiliariesOf(image.id());
        assertEquals(1, auxiliaries.size());
        var alpha = auxiliaries.getFirst();
        assertEquals(219, alpha.size());
        assertTrue(alpha.property(HeifProperty.AuxiliaryType.class).isAlpha());
        assertArrayEquals(new int[]{8}, alpha.property(HeifProperty.PixelInformation.class).depths());
        assertEquals(219, file.readItem(MediaSource.of(data), alpha, MediaLimits.DEFAULT).length);
    }

    @Test
    @DisplayName("libheif's tile with metadata: EXIF and XMP items that describe the image")
    void metadata() {
        var data = vector("heif-libheif-simple_osm_tile_meta.avif");
        var file = parse(data);
        var descriptions = file.descriptionsOf(file.primaryItem().id());
        assertEquals(List.of("Exif", "mime"), descriptions.stream().map(HeifItem::type).toList());
        var tiff = HeifFile.exifTiff(file.readItem(MediaSource.of(data), descriptions.get(0), MediaLimits.DEFAULT));
        assertNotNull(tiff);
        assertTrue(tiff[0] == 'M' || tiff[0] == 'I');
        assertEquals(HeifFile.XMP_TYPE, descriptions.get(1).contentType());
        var xmp = file.readItem(MediaSource.of(data), descriptions.get(1), MediaLimits.DEFAULT);
        assertEquals(4276, xmp.length);
        assertTrue(new String(xmp, StandardCharsets.UTF_8).contains("x:xmpmeta"));
    }

    @Test
    @DisplayName("a mini box whose size field is 0 extends to the end of the file")
    void sizeZero() {
        var sized = vector("heif-libheif-simple_osm_tile_alpha.avif");
        var unsized = vector("heif-libheif-mini_size_zero.avif");
        var expected = parse(sized);
        var actual = parse(unsized);
        assertEquals(expected.items().size(), actual.items().size());
        for (var item : expected.items()) {
            assertArrayEquals(expected.readItem(MediaSource.of(sized), item, MediaLimits.DEFAULT),
                    actual.readItem(MediaSource.of(unsized), actual.item(item.id()), MediaLimits.DEFAULT));
        }
    }

    @Test
    @DisplayName("the minor version heic names the HEVC coding")
    void hevc() {
        var file = parse(vector("heif-libheif-lightning_mini.heif"));
        assertTrue(file.hasBrand("heic"), file.brands().toString());
        var image = file.primaryItem();
        assertEquals("hvc1", image.type());
        assertEquals(19098, image.size());
        assertEquals(113, image.property(HeifProperty.HevcConfiguration.class).record().length);
        // no explicit CICP and no ICC profile: sRGB (BT.709 primaries, sRGB transfer), BT.601 matrix
        assertEquals(new HeifProperty.ColorNclx(1, 13, 6, true), image.property(HeifProperty.ColorNclx.class));
    }

    @Test
    @DisplayName("a gain map, HDR properties, explicit CICP, an orientation and EXIF")
    void gainMap() {
        var gainMapMetadata = new byte[]{1, 2, 3, 4, 5};
        var exif = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0};
        var bits = new MsbBitWriter(64);
        bits.write(0, 2); // version
        // explicit_codec_types, float, full_range, alpha, explicit_cicp, hdr, icc, exif, xmp
        bits.write(0b001011010, 9);
        bits.write(1, 2); // chroma_subsampling: 4:2:0
        bits.write(5, 3); // orientation_minus1: orientation 6
        bits.write(0, 1); // large_dimensions_flag
        bits.write(63, 7);
        bits.write(31, 7);
        bits.write(0, 2); // chroma_is_horizontally_centered, chroma_is_vertically_centered
        bits.write(0, 1); // high_bit_depth_flag
        bits.write(9, 8);
        bits.write(16, 8);
        bits.write(9, 8);
        bits.write(1, 1); // gainmap_flag
        bits.write(0, 1); // gainmap_dimension_same_as_main_item_flag
        bits.write(31, 7);
        bits.write(15, 7);
        bits.write(6, 8); // gainmap_matrix_coefficients
        bits.write(1, 1); // gainmap_full_range_flag
        bits.write(0, 2); // gainmap_chroma_subsampling: 4:0:0
        bits.write(0, 2); // gainmap_float_flag, gainmap_high_bit_depth_flag
        bits.write(0, 2); // tmap_icc_flag, tmap_explicit_cicp_flag
        bits.write(0b100000, 6); // clli only
        bits.write(1000, 16);
        bits.write(400, 16);
        bits.write(0, 6); // no HDR property of the tone-mapped image
        bits.write(0, 3); // large_metadata_flag, large_codec_config_flag, large_item_data_flag
        bits.write(gainMapMetadata.length, 10);
        bits.write(3, 15); // gainmap_item_data_size
        bits.write(0, 3); // gainmap_item_codec_config_size: the main one
        bits.write(AV1C.length, 3);
        bits.write(9, 15); // main_item_data_size_minus1
        bits.write(0, 1); // exif_xmp_compressed_flag
        bits.write(exif.length - 1, 10);
        bits.alignWithZeros();
        var payload = new ByteArrayOutputStream();
        payload.writeBytes(bits.toByteArray());
        payload.writeBytes(AV1C);
        payload.writeBytes(gainMapMetadata);
        payload.writeBytes(new byte[]{7, 7, 7});
        payload.writeBytes(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        payload.writeBytes(exif);
        var data = file(payload.toByteArray());
        var file = parse(data);
        assertTrue(file.hasBrand("tmap"), file.brands().toString());
        var image = file.primaryItem();
        assertArrayEquals(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9},
                file.readItem(MediaSource.of(data), image, MediaLimits.DEFAULT));
        assertEquals(new HeifProperty.ColorNclx(9, 16, 9, true), image.property(HeifProperty.ColorNclx.class));
        assertEquals(new HeifProperty.ContentLightLevel(1000, 400),
                image.property(HeifProperty.ContentLightLevel.class));
        assertEquals(3, image.property(HeifProperty.Rotation.class).quarterTurns());
        assertNull(image.property(HeifProperty.Mirror.class));
        var toneMap = file.item(3);
        assertEquals("tmap", toneMap.type());
        assertArrayEquals(new int[]{1, 4}, toneMap.references("dimg"));
        var toneMapExtent = toneMap.property(HeifProperty.SpatialExtent.class);
        assertEquals(32, toneMapExtent.width());
        assertEquals(64, toneMapExtent.height());
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5},
                file.readItem(MediaSource.of(data), toneMap, MediaLimits.DEFAULT));
        assertTrue(file.preferredAlternatives(1).contains(toneMap));
        var gainMap = file.item(4);
        assertArrayEquals(new byte[]{7, 7, 7}, file.readItem(MediaSource.of(data), gainMap, MediaLimits.DEFAULT));
        var gainMapExtent = gainMap.property(HeifProperty.SpatialExtent.class);
        assertEquals(32, gainMapExtent.width());
        assertEquals(16, gainMapExtent.height());
        assertArrayEquals(new int[]{8}, gainMap.property(HeifProperty.PixelInformation.class).depths());
        assertEquals(new HeifProperty.ColorNclx(2, 2, 6, true), gainMap.property(HeifProperty.ColorNclx.class));
        assertEquals(3, gainMap.property(HeifProperty.Rotation.class).quarterTurns());
        var exifItem = file.item(6);
        assertArrayEquals(exif, HeifFile.exifTiff(file.readItem(MediaSource.of(data), exifItem,
                MediaLimits.DEFAULT)));
        assertTrue(exifItem.references("cdsc", 1));
    }

    @Test
    @DisplayName("reject a mini box its chunks do not fill, one that names no coding, and one outside a mif3 file")
    void rejected() {
        var data = vector("heif-libheif-simple_osm_tile_alpha.avif");
        var padded = new byte[data.length + 1];
        System.arraycopy(data, 0, padded, 0, data.length);
        padded[19]++; // the mini box size: one byte more than its chunks
        assertThrows(MediaException.Malformed.class, () -> parse(padded));
        var unnamed = data.clone();
        // minor version 0 and no explicit codec types
        unnamed[12] = 0;
        unnamed[13] = 0;
        unnamed[14] = 0;
        unnamed[15] = 0;
        assertThrows(MediaException.Malformed.class, () -> parse(unnamed));
        var unbranded = data.clone();
        unbranded[11] = '1';
        assertThrows(MediaException.Malformed.class, () -> parse(unbranded));
    }

    /**
     * Returns a {@code mif3} file of minor version {@code avif}: its {@code ftyp} box, then a {@code mini} box.
     */
    private static byte[] file(byte[] mini) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'm', 'i', 'f', '3', 'a', 'v', 'i', 'f'});
        var size = mini.length + 8;
        out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size,
                'm', 'i', 'n', 'i'});
        out.writeBytes(mini);
        return out.toByteArray();
    }
}
