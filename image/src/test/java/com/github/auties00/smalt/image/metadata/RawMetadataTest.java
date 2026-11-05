package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the metadata of DNG and camera raw files: synthetic files of every layout the reader handles
 * ({@link MetadataSeeds}), and camera files from raw.pixls.us compared with exiftool, the smoke-tier
 * ones on every build and the others where the full test-data tier is present. DNG files and the raw
 * files with a TIFF signature are edited like TIFF files; the others are read only.
 */
@DisplayName("Camera raw metadata")
class RawMetadataTest {
    static ImageMetadata read(byte[] file) {
        return ImageMetadata.read(MediaSource.of(file));
    }

    static ExifValue ifd0(ImageMetadata metadata, int tag) {
        return metadata.exif().orElseThrow().ifd0().get(tag);
    }

    @Nested
    @DisplayName("synthetic files")
    class Synthetic {
        @Test
        @DisplayName("an ORF file is read through its own signature, and cannot be edited")
        void orf() {
            var file = MetadataSeeds.orf();
            var metadata = read(file);
            assertEquals(ImageFormat.RAW, metadata.format());
            assertEquals(new ExifValue.Ascii("OLYMPUS IMAGING CORP."), ifd0(metadata, 0x010F));
            assertEquals(MetadataSeeds.shot(), metadata.exif().orElseThrow().exif());
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.stripAll(file));
            assertSame(file, ImageMetadataEditor.apply(file, ImageMetadataEdit.keepAll()));
        }

        @Test
        @DisplayName("an RW2 file leaves its sensor tags below 254 out of the EXIF block")
        void rw2() {
            var metadata = read(MetadataSeeds.rw2());
            assertEquals(ImageFormat.RAW, metadata.format());
            var ifd0 = metadata.exif().orElseThrow().ifd0();
            assertEquals(new ExifValue.Ascii("Panasonic"), ifd0.get(0x010F));
            assertFalse(ifd0.containsKey(0x0002));
            assertFalse(ifd0.containsKey(0x0017));
            assertEquals(MetadataSeeds.shot(), metadata.exif().orElseThrow().exif());
        }

        @Test
        @DisplayName("a RAF file gives the EXIF block and XMP packet of its JPEG preview")
        void raf() {
            var file = MetadataSeeds.raf();
            var metadata = read(file);
            assertEquals(ImageFormat.RAW, metadata.format());
            var ifd0 = new java.util.LinkedHashMap<>(metadata.exif().orElseThrow().ifd0());
            ifd0.remove(ExifData.EXIF_IFD_POINTER);
            assertEquals(MetadataSeeds.rafExif().ifd0(), ifd0);
            assertEquals(MetadataSeeds.shot(), metadata.exif().orElseThrow().exif());
            assertEquals("RAF seed", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.stripAll(file));
        }

        @Test
        @DisplayName("a CR3 file combines its CMT1, CMT2 and CMT4 directories, leaving the maker note out")
        void cr3() {
            var metadata = read(MetadataSeeds.cr3());
            assertEquals(ImageFormat.RAW, metadata.format());
            var exif = metadata.exif().orElseThrow();
            assertEquals(MetadataSeeds.camera("Canon", "Canon EOS SEED"), exif.ifd0().entrySet().stream()
                    .filter(entry -> entry.getKey() != ExifData.EXIF_IFD_POINTER && entry.getKey() != ExifData.GPS_IFD_POINTER)
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            assertEquals(MetadataSeeds.shot(), exif.exif());
            assertEquals(MetadataSeeds.position(), exif.gps());
            assertTrue(metadata.hasLocation());
            assertEquals("CR3 seed", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
        }

        @Test
        @DisplayName("an MRW file gives the TIFF structure of its TTW block")
        void mrw() {
            var metadata = read(MetadataSeeds.mrw());
            assertEquals(ImageFormat.RAW, metadata.format());
            assertEquals(new ExifValue.Ascii("KONICA MINOLTA"), ifd0(metadata, 0x010F));
            assertEquals(MetadataSeeds.shot(), metadata.exif().orElseThrow().exif());
        }

        @Test
        @DisplayName("a raw file with a TIFF signature is read and edited in place like a TIFF file")
        void tiffRaw() {
            var file = MetadataSeeds.tiffRaw();
            var metadata = read(file);
            assertEquals(ImageFormat.RAW, metadata.format());
            assertFalse(metadata.exif().orElseThrow().ifd0().containsKey(0x0106), "a structural tag in the EXIF block");
            var located = ImageMetadataEditor.stripLocation(file);
            assertFalse(read(located).hasLocation());
            assertEquals(new ExifValue.Ascii("NIKON CORPORATION"), ifd0(read(located), 0x010F));
            assertArrayEquals(Arrays.copyOfRange(file, file.length - 256, file.length),
                    Arrays.copyOfRange(located, file.length - 256, file.length), "the image data moved");
        }

        @Test
        @DisplayName("a DNG file is reported as DNG and edited, keeping its DNG tags")
        void dng() {
            var file = MetadataSeeds.dng();
            var metadata = read(file);
            assertEquals(ImageFormat.DNG, metadata.format());
            assertFalse(metadata.exif().orElseThrow().ifd0().containsKey(50706));
            var edited = ImageMetadataEditor.apply(file, ImageMetadataEdit.keepAll().toBuilder()
                    .exif(metadata.exif().orElseThrow().with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Ada")))
                    .build());
            var reread = read(edited);
            assertEquals(ImageFormat.DNG, reread.format());
            assertEquals(new ExifValue.Ascii("Ada"), ifd0(reread, 0x013B));
            var tags = MetadataSupport.exiftoolRead(edited, "seed-edited.dng");
            assertEquals("1 4 0 0", tags.get("IFD0:DNGVersion"), tags.toString());
            assertEquals("Canon EOS SEED", tags.get("IFD0:UniqueCameraModel"));
            assertEquals("Ada", tags.get("IFD0:Artist"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"crw", "x3f"})
        @DisplayName("CRW and X3F files raise Unsupported")
        void unreadable(String kind) {
            var file = kind.equals("crw")
                    ? MetadataSeeds.concat(new byte[]{'I', 'I', 0x1A, 0, 0, 0}, "HEAPCCDR".getBytes(), new byte[64])
                    : MetadataSeeds.concat("FOVb".getBytes(), new byte[64]);
            assertThrows(MediaException.Unsupported.class, () -> read(file));
        }

        @Test
        @DisplayName("mutated raw files fail only with media exceptions")
        void hostile() {
            var random = new Random(31);
            for (Supplier<byte[]> seed : List.<Supplier<byte[]>>of(MetadataSeeds::orf, MetadataSeeds::rw2, MetadataSeeds::raf,
                    MetadataSeeds::cr3, MetadataSeeds::mrw)) {
                var original = seed.get();
                for (var round = 0; round < 300; round++) {
                    var mutated = original.clone();
                    for (var i = 0; i < 4; i++) {
                        mutated[random.nextInt(Math.min(mutated.length, 600))] = (byte) random.nextInt(256);
                    }
                    try {
                        read(mutated).exif();
                    } catch (MediaException expected) {
                        // corrupt input may be rejected, never crash
                    }
                }
                for (var length = 0; length < original.length; length += 7) {
                    try {
                        read(Arrays.copyOf(original, length));
                    } catch (MediaException expected) {
                        // truncated input may be rejected, never crash
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("camera files against exiftool")
    class Camera {
        static byte[] vector(String name) {
            try {
                return Files.readAllBytes(Conformance.fetchListed(name));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        /** Compares the make, model, capture date, exposure time and ISO speed with exiftool's. */
        static void compare(String name, ImageFormat format) {
            var file = vector(name);
            var metadata = read(file);
            assertEquals(format, metadata.format());
            var tags = MetadataSupport.exiftoolRead(file, Path.of(name).getFileName().toString());
            var exif = metadata.exif().orElseThrow();
            assertEquals(tags.get("IFD0:Make"), text(exif.ifd0().get(0x010F)), tags.toString());
            assertEquals(tags.get("IFD0:Model"), text(exif.ifd0().get(0x0110)));
            assertEquals(tags.get("ExifIFD:DateTimeOriginal"), text(exif.exif().get(0x9003)));
            var exposure = (ExifValue.Rationals) exif.exif().get(0x829A);
            assertEquals(new BigDecimal(tags.get("ExifIFD:ExposureTime")).doubleValue(),
                    exposure.doubleValue(0), 1e-6);
            if (exif.exif().get(0x8827) instanceof ExifValue.Integers iso) {
                assertEquals(tags.get("ExifIFD:ISO"), String.valueOf(iso.get(0)));
            }
        }

        static String text(ExifValue value) {
            return value instanceof ExifValue.Ascii ascii ? ascii.value().strip() : null;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"rawpixls-adobe-dng-converter-canon-eos-5d-mark-iii-lossy-jpeg-compression-rgb-3-2.dng",
                "rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng", "rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng"})
        @DisplayName("DNG files")
        void dng(String name) {
            compare(name, ImageFormat.DNG);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2", "rawpixls-canon-eos-5d-mark-iii.cr2",
                "rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef", "rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw",
                "rawpixls-olympus-e-410-16bit-4-3.orf", "rawpixls-panasonic-dmc-gf6-4-3.rw2",
                "rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf", "rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf",
                "rawpixls-canon-eos-250d-raw-3-2.cr3"})
        @DisplayName("vendor raw files")
        void vendor(String name) {
            compare(name, ImageFormat.RAW);
        }
    }
}
