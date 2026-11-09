package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Edits the metadata of DNG files and of raw files with a TIFF signature, which the metadata editor
 * rewrites like TIFF files, and checks that the raw decoder reads the same sensor samples and renders
 * the same picture afterwards: a synthetic DNG on every build and the camera files (DNG, CR2, NEF,
 * ARW, PEF) of the smoke and full tiers where cached.
 */
@DisplayName("Metadata edits of raw files")
class RawMetadataEditTest {
    static ImageMetadataEdit edit(ImageMetadata metadata) {
        var exif = metadata.exif().orElse(ExifData.EMPTY)
                .with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("An artist name long enough to move IFD0"))
                .with(ExifData.Directory.GPS, 0x0001, new ExifValue.Ascii("S"));
        return ImageMetadataEdit.keepAll().toBuilder().exif(exif)
                .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Edited raw")).build();
    }

    static java.util.Map<Integer, ExifValue> withoutPointer(ImageMetadata metadata) {
        var exif = new java.util.TreeMap<>(metadata.exif().orElseThrow().exif());
        exif.remove(ExifData.INTEROP_IFD_POINTER);
        return exif;
    }

    static void assertSameImage(byte[] original, byte[] edited) {
        assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(original)).samples(),
                RawDecoder.decodeRaw(MediaSource.of(edited)).samples(), "the sensor samples changed");
        Tolerance.exact().assertWithin("rendered picture", RawDecoder.decode(MediaSource.of(original)).picture(),
                RawDecoder.decode(MediaSource.of(edited)).picture());
    }

    @Test
    @DisplayName("a DNG keeps its sensor data and rendering through an edit, in memory and in place on disk")
    void dng(@TempDir Path directory) throws IOException {
        var original = TinyDng.ramp(64, 48).write();
        var metadata = ImageMetadata.read(MediaSource.of(original));
        assertEquals(ImageFormat.DNG, metadata.format());
        var edited = ImageMetadataEditor.apply(original, edit(metadata));
        var reread = ImageMetadata.read(MediaSource.of(edited));
        assertEquals(new ExifValue.Ascii("An artist name long enough to move IFD0"),
                reread.exif().orElseThrow().ifd0().get(0x013B));
        assertEquals("Edited raw", reread.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
        assertSameImage(original, edited);
        var file = directory.resolve("tiny.dng");
        Files.write(file, original);
        ImageMetadataEditor.apply(file, edit(metadata));
        assertSameImage(original, Files.readAllBytes(file));
        var stripped = ImageMetadataEditor.stripAll(edited);
        assertFalse(ImageMetadata.read(MediaSource.of(stripped)).hasLocation());
        assertSameImage(original, stripped);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng",
            "rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng", "rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2",
            "rawpixls-canon-eos-5d-mark-iii.cr2", "rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef",
            "rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw", "rawpixls-sony-ilce-7m2-14bit-14bit-uncompressed-3-2.arw",
            "rawpixls-pentax-k-5-14bit-3-2.pef"})
    @DisplayName("camera files keep their sensor data and rendering through an edit")
    void camera(String name) throws IOException {
        var original = Files.readAllBytes(Conformance.fetchListed(name));
        var metadata = ImageMetadata.read(MediaSource.of(original));
        var edited = ImageMetadataEditor.apply(original, edit(metadata));
        var reread = ImageMetadata.read(MediaSource.of(edited));
        assertEquals(metadata.format(), reread.format());
        // the interoperability pointer follows its directory, which the rewrite may move
        assertEquals(withoutPointer(metadata), withoutPointer(reread));
        assertEquals(metadata.exif().orElseThrow().ifd0().get(0x0110), reread.exif().orElseThrow().ifd0().get(0x0110));
        assertSameImage(original, edited);
    }
}
