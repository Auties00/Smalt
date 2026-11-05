package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the EXIF 3.0 {@code UTF-8} type and uncompressed thumbnails against exiftool: text that
 * exiftool writes as UTF-8 survives Smalt's in-place and fresh layouts byte for byte, values Smalt
 * writes with type 129 are what exiftool decodes, and an uncompressed thumbnail Smalt writes is one
 * exiftool describes and validates. Values with non-ASCII characters reach exiftool through a
 * UTF-8 argument file, which works the same on every platform.
 */
@DisplayName("EXIF extended types against exiftool")
class ExifExtendedTypesOracleTest {
    static final String UNICODE = "Caffè 日本 📷";

    /** Runs exiftool with arguments passed through a UTF-8 argument file. */
    static String exiftoolArgs(String name, List<String> arguments) {
        var file = MetadataSupport.DIRECTORY.resolve(name + ".args");
        try {
            Files.createDirectories(MetadataSupport.DIRECTORY);
            Files.writeString(file, String.join("\n", arguments) + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return MetadataSupport.exiftool(List.of("-charset", "filename=utf8", "-@", file.toString()));
    }

    /**
     * Keeps the validation warnings about the structure and types of the block, leaving out the
     * ones about required tags the minimal fixtures do not carry, the one for {@code LensModel},
     * whose UTF-8 form exiftool's tag table does not list, the minor ones noting that EXIF reserves
     * uncompressed thumbnails for TIFF files, and the size heuristic of IFD1.
     */
    static List<String> structural(List<String> warnings) {
        return warnings.stream().filter(warning -> !warning.contains("Missing required")
                && !warning.contains("(utf8) for ExifIFD 0xa434")
                && !(warning.contains("[minor]") && (warning.contains("is not allowed in JPEG")
                        || warning.contains("Invalid value for IFD1 tag 0x0103 Compression")
                        || warning.contains("Missing required JPEG IFD1 tag")))
                // exiftool flags IFD1 so whenever an in-place rewrite appends a grown IFD0 after it, JPEG thumbnails too
                && !warning.contains("Short directory size for IFD1")).toList();
    }

    static byte[] withUtf8Artist(byte[] jpeg) {
        var file = MetadataSupport.save("utf8-artist.jpg", jpeg);
        exiftoolArgs("utf8-artist", List.of("-overwrite_original", "-charset", "exif=utf8", "-EXIF:Artist=" + UNICODE,
                "-EXIF:Make=SmaltCam", file.toString()));
        return Fixtures.read(file);
    }

    @Test
    @DisplayName("exiftool's UTF-8 text survives an in-place rewrite and a fresh layout byte for byte")
    void exiftoolText() {
        var image = withUtf8Artist(MetadataSupport.jpeg());
        var read = ImageMetadata.read(com.github.auties00.smalt.io.MediaSource.of(image));
        var artist = (ExifValue.Ascii) read.exif().orElseThrow().ifd0().get(0x013B);
        assertArrayEquals(UNICODE.getBytes(StandardCharsets.UTF_8), artist.value().getBytes(StandardCharsets.ISO_8859_1));
        var inPlace = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                .orientation(Orientation.ROTATE_90).build());
        var bare = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder().removeExif().build());
        var fresh = ImageMetadataEditor.apply(bare, ImageMetadataEdit.keepAll().toBuilder()
                .exif(ExifReader.parse(ExifWriter.write(read.exif().orElseThrow(), ByteOrder.LITTLE_ENDIAN))).build());
        for (var edited : List.of(inPlace, fresh)) {
            var tags = MetadataSupport.exiftoolRead(edited, "utf8-edited.jpg");
            assertEquals(UNICODE, tags.get("IFD0:Artist"));
            assertEquals("SmaltCam", tags.get("IFD0:Make"));
        }
        assertEquals("6", MetadataSupport.exiftoolRead(inPlace, "utf8-rotated.jpg").get("IFD0:Orientation"));
    }

    @Test
    @DisplayName("type 129 values Smalt writes are decoded by exiftool as UTF-8, fresh and rewritten")
    void smaltUtf8() {
        var exif = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, ExifValue.ofUtf8(UNICODE))
                .with(ExifData.Directory.IFD0, 0x010E, ExifValue.ofUtf8("été"))
                .with(ExifData.Directory.EXIF, 0xA434, ExifValue.ofUtf8("Lens µ"));
        var fresh = ImageMetadataEditor.apply(MetadataSupport.jpeg(), ImageMetadataEdit.keepAll().toBuilder().exif(exif).build());
        var rewritten = ImageMetadataEditor.apply(fresh, ImageMetadataEdit.keepAll().toBuilder()
                .orientation(Orientation.FLIP_VERTICAL).build());
        for (var image : List.of(fresh, rewritten)) {
            var tags = MetadataSupport.exiftoolRead(image, "smalt-utf8.jpg");
            assertEquals(UNICODE, tags.get("IFD0:Artist"));
            assertEquals("été", tags.get("IFD0:ImageDescription"));
            assertEquals("Lens µ", tags.get("ExifIFD:LensModel"));
            var verbose = MetadataSupport.exiftool(List.of("-v3", MetadataSupport.save("smalt-utf8-v.jpg", image).toString()));
            assertTrue(verbose.contains("utf8["), verbose);
            assertEquals(List.of(), structural(MetadataSupport.exiftoolWarnings(image, "smalt-utf8-w.jpg")));
        }
    }

    @Test
    @DisplayName("an uncompressed thumbnail Smalt writes is described and validated by exiftool")
    void uncompressedThumbnail() {
        var ifd1 = new LinkedHashMap<Integer, ExifValue>();
        ifd1.put(0x0100, ExifValue.ofShort(8));
        ifd1.put(0x0101, ExifValue.ofShort(6));
        ifd1.put(0x0102, ExifValue.ofShort(8, 8, 8));
        ifd1.put(0x0106, ExifValue.ofShort(2));
        ifd1.put(0x0115, ExifValue.ofShort(3));
        ifd1.put(0x011C, ExifValue.ofShort(1));
        ifd1.put(0x011A, ExifValue.ofRational(72, 1));
        ifd1.put(0x011B, ExifValue.ofRational(72, 1));
        ifd1.put(0x0128, ExifValue.ofShort(2));
        var samples = new byte[8 * 6 * 3];
        new Random(5).nextBytes(samples);
        var exif = new ExifData(Map.of(0x010F, new ExifValue.Ascii("SmaltCam"), 0x011A, ExifValue.ofRational(72, 1),
                0x011B, ExifValue.ofRational(72, 1), 0x0128, ExifValue.ofShort(2), 0x0213, ExifValue.ofShort(1)),
                Map.of(0x9000, new ExifValue.Undefined("0232".getBytes(StandardCharsets.US_ASCII))), Map.of(), Map.of(),
                ifd1, Optional.empty()).withUncompressedThumbnail(samples);
        var image = ImageMetadataEditor.apply(MetadataSupport.jpeg(), ImageMetadataEdit.keepAll().toBuilder().exif(exif).build());
        var renamed = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                .exif(ImageMetadata.read(com.github.auties00.smalt.io.MediaSource.of(image)).exif().orElseThrow()
                        .with(ExifData.Directory.IFD0, 0x0110, new ExifValue.Ascii("A longer model name"))).build());
        var results = new ArrayList<byte[]>(List.of(image, renamed));
        for (var edited : results) {
            var tags = MetadataSupport.exiftoolRead(edited, "uncompressed-thumb.jpg");
            assertEquals("1", tags.get("IFD1:Compression"));
            assertEquals("8", tags.get("IFD1:ImageWidth"));
            assertEquals(String.valueOf(samples.length), tags.get("IFD1:StripByteCounts"));
            var read = ImageMetadata.read(com.github.auties00.smalt.io.MediaSource.of(edited)).exif().orElseThrow();
            assertTrue(read.hasUncompressedThumbnail());
            assertArrayEquals(samples, read.thumbnail().orElseThrow());
            assertEquals(List.of(), structural(MetadataSupport.exiftoolWarnings(edited, "uncompressed-thumb-w.jpg")));
        }
    }
}
