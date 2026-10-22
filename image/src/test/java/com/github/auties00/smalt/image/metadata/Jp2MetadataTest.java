package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads and edits the metadata of JPEG 2000 files: the EXIF, XMP and IPTC {@code uuid} boxes, {@code xml } boxes
 * holding XMP, and the ICC color specification of the JP2 header. The codestream must come out byte for byte, the
 * image must decode to the same pixels, and exiftool must read what Smalt writes, as Smalt must read what exiftool
 * writes.
 */
@DisplayName("JPEG 2000 metadata")
class Jp2MetadataTest {
    static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    record Box(String type, byte[] content) {
    }

    static List<Box> boxes(byte[] file) {
        var result = new ArrayList<Box>();
        var at = 0;
        while (at + 8 <= file.length) {
            var size = (int) (((file[at] & 0xFFL) << 24) | ((file[at + 1] & 0xFF) << 16) | ((file[at + 2] & 0xFF) << 8)
                    | (file[at + 3] & 0xFF));
            if (size == 0) {
                size = file.length - at;
            }
            result.add(new Box(new String(file, at + 4, 4, StandardCharsets.ISO_8859_1),
                    Arrays.copyOfRange(file, at + 8, at + size)));
            at += size;
        }
        return result;
    }

    static List<String> types(byte[] file) {
        return boxes(file).stream().map(Box::type).toList();
    }

    static byte[] content(byte[] file, String type) {
        return boxes(file).stream().filter(box -> box.type().equals(type)).findFirst().orElseThrow().content();
    }

    static byte[] box(String type, byte[] content) {
        var out = new ByteArrayOutputStream();
        var size = content.length + 8;
        out.write(size >>> 24);
        out.write(size >>> 16);
        out.write(size >>> 8);
        out.write(size);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(content);
        return out.toByteArray();
    }

    static RgbaPicture picture() {
        var pixels = new byte[40 * 24 * 4];
        var random = new Random(3);
        random.nextBytes(pixels);
        for (var i = 3; i < pixels.length; i += 4) {
            pixels[i] = (byte) 255;
        }
        return RgbaPicture.of(40, 24, 160, pixels, false);
    }

    static byte[] jp2() {
        return Jp2Encoder.encode(picture());
    }

    static ExifData exif(String artist) {
        return ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii(artist));
    }

    static XmpPacket xmp(String title) {
        return XmpPacket.empty().with(XmpPacket.DC, "title", title);
    }

    static IptcData iptc(String city) {
        return IptcData.of(List.of(new IptcData.DataSet(2, 90, city.getBytes(StandardCharsets.UTF_8))));
    }

    static void assertSamePicture(byte[] expected, byte[] actual) {
        assertArrayEquals(content(expected, "jp2c"), content(actual, "jp2c"), "codestream");
        var before = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(expected)).picture();
        var after = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(actual)).picture();
        Tolerance.exact().assertWithin("pixels", before.pixels(), after.pixels());
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("reads the EXIF, XMP and IPTC exiftool writes into a JP2 file")
        void exiftool() {
            Oracle.assume(Oracle.Tool.EXIFTOOL);
            var tagged = MetadataSupport.exiftoolWrite("tagged.jp2", jp2(), List.of("-EXIF:Artist=Ada",
                    "-XMP-dc:Title=Engine", "-IPTC:City=Torino"));
            var metadata = ImageMetadata.read(tagged);
            assertEquals(ImageFormat.JP2, metadata.format());
            assertEquals(new ExifValue.Ascii("Ada"), metadata.exif().orElseThrow().get(ExifData.Directory.IFD0, 0x013B)
                    .orElseThrow());
            assertTrue(metadata.xmp().orElseThrow().texts(XmpPacket.DC, "title").contains("Engine"));
            assertEquals(List.of("Torino"), metadata.iptc().orElseThrow().strings(2, 90));
            assertSamePicture(jp2(), tagged);
        }

        @Test
        @DisplayName("reads an XMP packet of an xml box and the ICC profile of the color specification in use")
        void xmlAndIcc() {
            var profile = ColorProfile.displayP3().toByteArray();
            var file = ImageMetadataEditor.apply(jp2(), ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.displayP3()).build());
            var withXml = concat(file, box("xml ", xmp("From xml").toBytes()));
            var metadata = ImageMetadata.read(withXml);
            assertArrayEquals(profile, metadata.iccProfile().orElseThrow());
            assertEquals("From xml", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertArrayEquals(profile, Jp2Decoder.decode(MediaSource.of(withXml)).iccProfile().orElseThrow());
            assertTrue(ImageMetadata.read(jp2()).isEmpty(), "an enumerated color space is no ICC profile");
        }

        @Test
        @DisplayName("a raw codestream holds no metadata")
        void codestream() {
            var raw = Jp2Encoder.encode(picture(), Jp2EncoderOptions.builder().format(Jp2Format.CODESTREAM).build());
            assertTrue(ImageMetadata.read(raw).isEmpty());
            assertSame(raw, ImageMetadataEditor.apply(raw, ImageMetadataEdit.stripAll()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(raw,
                    ImageMetadataEdit.keepAll().toBuilder().exif(exif("x")).build()));
        }
    }

    @Nested
    @DisplayName("editing")
    class Editing {
        @Test
        @DisplayName("adds EXIF, IPTC and XMP boxes after the JP2 header, which exiftool reads")
        void add() {
            var original = jp2();
            var edited = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Ada"))
                    .iptc(iptc("Milano")).xmp(xmp("Engine")).build());
            assertEquals(List.of("jP  ", "ftyp", "jp2h", "uuid", "uuid", "uuid", "jp2c"), types(edited));
            assertSamePicture(original, edited);
            var metadata = ImageMetadata.read(edited);
            assertEquals(List.of("Milano"), metadata.iptc().orElseThrow().strings(2, 90));
            if (Oracle.find(Oracle.Tool.EXIFTOOL).isPresent()) {
                var tags = MetadataSupport.exiftoolRead(edited, "added.jp2");
                assertEquals("Ada", tags.get("IFD0:Artist"), tags.toString());
                assertEquals("Engine", tags.get("XMP-dc:Title"), tags.toString());
                assertEquals("Milano", tags.get("IPTC:City"), tags.toString());
                assertTrue(MetadataSupport.exiftoolWarnings(edited, "added.jp2").isEmpty(),
                        MetadataSupport.exiftoolWarnings(edited, "added.jp2").toString());
            }
        }

        @Test
        @DisplayName("replaces a changed kind where it was, drops its other boxes, and copies a kept one byte for byte")
        void replace() {
            var first = ImageMetadataEditor.apply(jp2(), ImageMetadataEdit.keepAll().toBuilder().exif(exif("Ada"))
                    .xmp(xmp("Old")).build());
            var withXml = concat(first, box("xml ", xmp("Second").toBytes()));
            var edited = ImageMetadataEditor.apply(withXml, ImageMetadataEdit.keepAll().toBuilder().xmp(xmp("New"))
                    .build());
            assertEquals(List.of("jP  ", "ftyp", "jp2h", "uuid", "uuid", "jp2c"), types(edited));
            assertArrayEquals(boxes(first).get(3).content(), boxes(edited).get(3).content(), "EXIF kept");
            var xmp = boxes(edited).get(4).content();
            assertArrayEquals(XMP_UUID, Arrays.copyOf(xmp, 16));
            assertEquals("New", ImageMetadata.read(edited).xmp().orElseThrow().text(XmpPacket.DC, "title")
                    .orElseThrow());
            assertSamePicture(first, edited);
        }

        @Test
        @DisplayName("replaces the ICC profile with a restricted ICC color specification, and removes it for sRGB")
        void icc() {
            var original = jp2();
            var withProfile = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.displayP3()).build());
            var header = boxes(withProfile).get(2);
            assertEquals("jp2h", header.type());
            var colr = boxes(header.content()).stream().filter(box -> box.type().equals("colr")).toList();
            assertEquals(2, colr.size(), "the ICC specification first, the enumerated one kept after it");
            assertEquals(2, colr.getFirst().content()[0]);
            assertEquals(1, colr.get(1).content()[0]);
            assertSamePicture(original, withProfile);
            if (Oracle.find(Oracle.Tool.EXIFTOOL).isPresent()) {
                var tags = MetadataSupport.exiftoolRead(withProfile, "icc.jp2");
                assertEquals("Display P3", tags.get("ICC-header:ProfileDescription") != null
                        ? tags.get("ICC-header:ProfileDescription") : tags.get("ICC_Profile:ProfileDescription"),
                        tags.toString());
            }
            var removed = ImageMetadataEditor.apply(withProfile, ImageMetadataEdit.keepAll().toBuilder()
                    .removeIccProfile().build());
            assertArrayEquals(content(original, "jp2h"), content(removed, "jp2h"), "back to enumerated sRGB");
            assertTrue(ImageMetadata.read(removed).iccProfile().isEmpty());
        }

        @Test
        @DisplayName("stripAll removes every metadata box and the other metadata boxes")
        void strip() {
            var rich = ImageMetadataEditor.apply(jp2(), ImageMetadataEdit.keepAll().toBuilder().exif(exif("Ada"))
                    .iptc(iptc("Roma")).xmp(xmp("T")).colorProfile(ColorProfile.displayP3()).build());
            var other = concat(rich, box("uuid", new byte[20]), box("xml ", "<note/>".getBytes()));
            var stripped = ImageMetadataEditor.apply(other, ImageMetadataEdit.stripAll());
            assertEquals(List.of("jP  ", "ftyp", "jp2h", "jp2c"), types(stripped));
            var left = ImageMetadata.read(stripped);
            assertTrue(left.exifBytes().isEmpty() && left.xmp().isEmpty() && left.iptc().isEmpty(), left.toString());
            assertTrue(left.iccProfile().isPresent(), "stripAll keeps the ICC profile");
            var kept = ImageMetadataEditor.apply(other, ImageMetadataEdit.keepAll().toBuilder().removeExif().build());
            assertEquals(List.of("jP  ", "ftyp", "jp2h", "uuid", "uuid", "jp2c", "uuid", "xml "), types(kept));
        }

        @Test
        @DisplayName("edits a file in place on disk")
        void file(@TempDir Path directory) throws IOException {
            var path = directory.resolve("image.jp2");
            Files.write(path, jp2());
            ImageMetadataEditor.apply(path, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Turing")).build());
            assertEquals(new ExifValue.Ascii("Turing"), ImageMetadata.read(path).exif().orElseThrow()
                    .get(ExifData.Directory.IFD0, 0x013B).orElseThrow());
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {
        @Test
        @DisplayName("comments and text cannot be stored, nor edits of fragmented codestreams")
        void unsupported() {
            var file = jp2();
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file,
                    ImageMetadataEdit.keepAll().toBuilder().comments(List.of("hello")).build()));
            var fragmented = concat(file, box("ftbl", box("flst", new byte[2])));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(fragmented,
                    ImageMetadataEdit.keepAll().toBuilder().exif(exif("x")).build()));
            assertArrayEquals(fragmented, ImageMetadataEditor.apply(fragmented, ImageMetadataEdit.keepAll()
                    .toBuilder().removeExif().build()));
        }

        @Test
        @DisplayName("corrupt box structures fail as malformed input")
        void malformed() {
            var file = jp2();
            var truncated = Arrays.copyOf(file, 30);
            assertThrows(MediaException.class, () -> ImageMetadataEditor.apply(truncated,
                    ImageMetadataEdit.keepAll().toBuilder().exif(exif("x")).build()));
            var oversized = file.clone();
            oversized[12] = 0x7F;
            assertThrows(MediaException.Malformed.class, () -> ImageMetadata.read(oversized));
            var random = new Random(9);
            for (var round = 0; round < 200; round++) {
                var mutated = file.clone();
                for (var flips = 0; flips < 4; flips++) {
                    mutated[random.nextInt(Math.min(mutated.length, 200))] = (byte) random.nextInt(256);
                }
                try {
                    ImageMetadataEditor.apply(mutated, ImageMetadataEdit.keepAll().toBuilder().exif(exif("y")).build());
                } catch (MediaException expected) {
                    assertFalse(expected.getCause() instanceof RuntimeException cause
                            && !(cause instanceof MediaException), expected.toString());
                }
            }
        }
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
