package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Edits the metadata of JPEG XL files: bare codestreams wrapped in a container, containers whose
 * {@code Exif} and {@code xml } boxes are replaced, kept or removed (Brotli-compressed {@code brob}
 * boxes included), and files with JPEG reconstruction data. The codestream must come out byte for
 * byte, the image must decode to the same pixels (with Smalt and with djxl), and exiftool must read
 * what was written. The committed fixtures are the fuzzing seeds; the Brotli boxes are stored
 * (uncompressed) Brotli streams built here, so the reading side decodes a real Brotli stream.
 */
@DisplayName("JPEG XL metadata")
class JxlMetadataTest {
    static final byte[] SIGNATURE = {0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    static byte[] seed(String name) {
        try (InputStream in = JxlMetadataTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/image/fuzz/ImageFuzzTestInputs/jxl/" + name)) {
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Box(String type, String inner, byte[] payload) {
    }

    static List<Box> boxes(byte[] file) {
        var result = new ArrayList<Box>();
        var at = 0;
        while (at + 8 <= file.length) {
            var size = (int) (((file[at] & 0xFFL) << 24) | ((file[at + 1] & 0xFF) << 16) | ((file[at + 2] & 0xFF) << 8)
                    | (file[at + 3] & 0xFF));
            var header = 8;
            if (size == 0) {
                size = file.length - at;
            }
            var type = new String(file, at + 4, 4, StandardCharsets.ISO_8859_1);
            var payload = Arrays.copyOfRange(file, at + header, at + size);
            var inner = type.equals("brob") ? new String(payload, 0, 4, StandardCharsets.ISO_8859_1) : "";
            result.add(new Box(type, inner, payload));
            at += size;
        }
        return result;
    }

    static List<String> types(byte[] file) {
        return boxes(file).stream().map(box -> box.inner().isEmpty() ? box.type() : box.type() + ":" + box.inner()).toList();
    }

    static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        var size = payload.length + 8;
        out.write(size >>> 24);
        out.write(size >>> 16);
        out.write(size >>> 8);
        out.write(size);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Builds a Brotli stream (RFC 7932) holding the data in one uncompressed meta-block: WBITS 16
     * (one 0 bit), ISLAST 0, MNIBBLES 4 (00), MLEN - 1 in 16 bits, ISUNCOMPRESSED 1, padding to the
     * byte, the data, then an empty last meta-block (ISLAST 1, ISLASTEMPTY 1).
     */
    static byte[] storedBrotli(byte[] data) {
        assertTrue(data.length >= 1 && data.length <= 65536);
        var bits = (long) (data.length - 1) << 4 | 1L << 20;
        var out = new ByteArrayOutputStream();
        out.write((int) bits);
        out.write((int) (bits >>> 8));
        out.write((int) (bits >>> 16));
        out.writeBytes(data);
        out.write(0x03);
        return out.toByteArray();
    }

    static byte[] codestream(byte[] file) {
        for (var box : boxes(file)) {
            if (box.type().equals("jxlc")) {
                return box.payload();
            }
        }
        throw new AssertionError("no jxlc box in " + types(file));
    }

    static byte[] exifPayload(byte[] tiff) {
        return concat(new byte[4], tiff);
    }

    static ExifData exif(String artist) {
        return ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii(artist))
                .with(ExifData.Directory.IFD0, ExifData.ORIENTATION, ExifValue.ofShort(1));
    }

    static void assertSamePixels(byte[] expected, byte[] actual) {
        Tolerance.exact().assertWithin("decoded pixels", Images.decode(MediaSource.of(expected)).picture(),
                Images.decode(MediaSource.of(actual)).picture());
    }

    @Nested
    @DisplayName("bare codestreams")
    class Bare {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"valid-grayscale.jxl", "valid-alpha-triangles.jxl", "valid-blendmodes.jxl"})
        @DisplayName("are wrapped in a container that carries the new EXIF and XMP boxes and the codestream unchanged")
        void wrap(String name) {
            var bare = seed(name);
            var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Wrapped");
            var edited = ImageMetadataEditor.apply(bare, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Ada")).xmp(xmp)
                    .build());
            assertArrayEquals(SIGNATURE, Arrays.copyOf(edited, 12));
            assertEquals(List.of("JXL ", "ftyp", "Exif", "xml ", "jxlc"), types(edited));
            assertArrayEquals(bare, codestream(edited));
            var ftyp = boxes(edited).get(1).payload();
            assertArrayEquals(new byte[]{'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x', 'l', ' '}, ftyp);
            var read = ImageMetadata.read(MediaSource.of(edited));
            assertEquals(ImageFormat.JXL, read.format());
            assertEquals(new ExifValue.Ascii("Ada"), read.exif().orElseThrow().ifd0().get(0x013B));
            assertEquals("Wrapped", read.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertSamePixels(bare, edited);
        }

        @Test
        @DisplayName("stay bare when an edit adds nothing")
        void identity() {
            var bare = seed("valid-grayscale.jxl");
            assertSame(bare, ImageMetadataEditor.apply(bare, ImageMetadataEdit.stripAll()));
            assertArrayEquals(bare, ImageMetadataEditor.apply(bare, ImageMetadataEdit.keepAll().toBuilder().removeExif()
                    .removeXmp().build()));
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        static byte[] container(boolean compressed) {
            var bare = seed("valid-grayscale.jxl");
            var tiff = exif("Grace").toBytes();
            var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Original").toBytes();
            var exifBox = compressed ? box("brob", concat("Exif".getBytes(StandardCharsets.ISO_8859_1),
                    storedBrotli(exifPayload(tiff)))) : box("Exif", exifPayload(tiff));
            var xmlBox = compressed ? box("brob", concat("xml ".getBytes(StandardCharsets.ISO_8859_1), storedBrotli(xmp)))
                    : box("xml ", xmp);
            return concat(SIGNATURE, box("ftyp", new byte[]{'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x', 'l', ' '}),
                    box("jumb", "a JUMBF superbox".getBytes(StandardCharsets.US_ASCII)), exifBox, xmlBox,
                    box("jxlc", bare));
        }

        @ParameterizedTest(name = "brob={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("read plain and Brotli-compressed metadata boxes")
        void read(boolean compressed) {
            var read = ImageMetadata.read(MediaSource.of(container(compressed)));
            assertEquals(new ExifValue.Ascii("Grace"), read.exif().orElseThrow().ifd0().get(0x013B));
            assertEquals("Original", read.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
        }

        @ParameterizedTest(name = "brob={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("replace a changed kind with one plain box in place and copy a kept one byte for byte")
        void replace(boolean compressed) {
            var original = container(compressed);
            var before = boxes(original);
            var edited = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder()
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Changed")).build());
            var after = boxes(edited);
            assertEquals(List.of("JXL ", "ftyp", "jumb", compressed ? "brob:Exif" : "Exif", "xml ", "jxlc"), types(edited));
            assertArrayEquals(before.get(3).payload(), after.get(3).payload(), "the kept EXIF box changed");
            assertArrayEquals(before.get(2).payload(), after.get(2).payload());
            assertArrayEquals(codestream(original), codestream(edited));
            var read = ImageMetadata.read(MediaSource.of(edited));
            assertEquals("Changed", read.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertEquals(new ExifValue.Ascii("Grace"), read.exif().orElseThrow().ifd0().get(0x013B));
            var exifChanged = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder()
                    .exif(exif("Hopper")).build());
            assertEquals(List.of("JXL ", "ftyp", "jumb", "Exif", compressed ? "brob:xml " : "xml ", "jxlc"),
                    types(exifChanged));
            assertEquals(new ExifValue.Ascii("Hopper"),
                    ImageMetadata.read(MediaSource.of(exifChanged)).exif().orElseThrow().ifd0().get(0x013B));
            assertSamePixels(original, exifChanged);
        }

        @ParameterizedTest(name = "brob={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("remove every metadata box, and the other metadata when asked")
        void remove(boolean compressed) {
            var original = container(compressed);
            var stripped = ImageMetadataEditor.apply(original, ImageMetadataEdit.stripAll());
            assertEquals(List.of("JXL ", "ftyp", "jxlc"), types(stripped));
            var kept = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder().removeExif().build());
            assertEquals(List.of("JXL ", "ftyp", "jumb", compressed ? "brob:xml " : "xml ", "jxlc"), types(kept));
            assertTrue(ImageMetadata.read(MediaSource.of(stripped)).exif().isEmpty());
            assertArrayEquals(codestream(original), codestream(stripped));
        }

        @Test
        @DisplayName("insert a new box before the codestream and replace an existing one where it is")
        void placement() {
            var bare = seed("valid-grayscale.jxl");
            var late = concat(SIGNATURE, box("ftyp", new byte[]{'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x', 'l', ' '}),
                    box("jxlp", concat(new byte[]{0, 0, 0, 0}, Arrays.copyOf(bare, 100))),
                    box("jxlp", concat(new byte[]{(byte) 0x80, 0, 0, 1}, Arrays.copyOfRange(bare, 100, bare.length))),
                    box("xml ", "<x/>".getBytes(StandardCharsets.US_ASCII)));
            var edited = ImageMetadataEditor.apply(late, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Lovelace"))
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Moved")).build());
            assertEquals(List.of("JXL ", "ftyp", "Exif", "jxlp", "jxlp", "xml "), types(edited));
            assertSamePixels(late, edited);
            assertEquals("Moved", ImageMetadata.read(MediaSource.of(edited)).xmp().orElseThrow()
                    .text(XmpPacket.DC, "title").orElseThrow());
        }

        @Test
        @DisplayName("edit a file in place on disk")
        void file(@TempDir Path directory) throws IOException {
            var path = directory.resolve("image.jxl");
            Files.write(path, container(false));
            ImageMetadataEditor.apply(path, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Turing")).build());
            assertEquals(new ExifValue.Ascii("Turing"),
                    ImageMetadata.read(path).exif().orElseThrow().ifd0().get(0x013B));
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {
        @Test
        @DisplayName("ICC profiles, IPTC data, comments and text cannot be stored")
        void unsupported() {
            var bare = seed("valid-grayscale.jxl");
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(bare,
                    ImageMetadataEdit.keepAll().toBuilder().colorProfile(ColorProfile.displayP3()).build()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(bare,
                    ImageMetadataEdit.keepAll().toBuilder().comments(List.of("hello")).build()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(bare,
                    ImageMetadataEdit.keepAll().toBuilder()
                            .iptc(IptcData.of(List.of(new IptcData.DataSet(2, 90, "Milano".getBytes(StandardCharsets.UTF_8)))))
                            .build()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"valid-grayscale.jxl", "valid-alpha-triangles.jxl", "valid-blendmodes.jxl"})
        @DisplayName("removing the ICC profile changes nothing when the codestream embeds none and is refused otherwise")
        void iccRemoval(String name) {
            var edit = ImageMetadataEdit.keepAll().toBuilder().removeIccProfile().build();
            var bare = seed(name);
            var container = concat(SIGNATURE, box("ftyp", new byte[]{'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x', 'l', ' '}),
                    box("Exif", exifPayload(exif("Grace").toBytes())), box("jxlc", bare));
            for (var file : List.of(bare, container)) {
                if (ImageMetadata.read(MediaSource.of(file)).iccProfile().isPresent()) {
                    assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file, edit));
                } else {
                    assertArrayEquals(file, ImageMetadataEditor.apply(file, edit));
                }
            }
        }

        @Test
        @DisplayName("corrupt box structures fail as malformed input")
        void malformed() {
            var file = Containers.container(false);
            var truncated = Arrays.copyOf(file, 60);
            assertThrows(MediaException.class, () -> ImageMetadataEditor.apply(truncated,
                    ImageMetadataEdit.keepAll().toBuilder().exif(exif("x")).build()));
            var random = new Random(3);
            for (var round = 0; round < 400; round++) {
                var mutated = file.clone();
                for (var i = 0; i < 4; i++) {
                    mutated[random.nextInt(140)] = (byte) random.nextInt(256);
                }
                try {
                    ImageMetadataEditor.apply(mutated, ImageMetadataEdit.keepAll().toBuilder().exif(exif("y")).build());
                } catch (MediaException expected) {
                    // corrupt input may be rejected, never crash
                }
            }
        }
    }

    @Nested
    @DisplayName("JPEG reconstruction")
    class Reconstruction {
        @Test
        @DisplayName("a file whose JPEG had no EXIF gains one and still rebuilds the same JPEG")
        void addExif() {
            var file = seed("valid-jpeg-reconstruction.jxl");
            var jpeg = JxlDecoder.reconstructJpeg(MediaSource.of(file)).orElseThrow();
            var edited = ImageMetadataEditor.apply(file, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Noether")).build());
            assertTrue(types(edited).contains("jbrd"));
            assertArrayEquals(jpeg, JxlDecoder.reconstructJpeg(MediaSource.of(edited)).orElseThrow());
        }

        /**
         * Returns a committed fixture: {@code tagged.jpg} is ffmpeg's {@code testsrc2} at 96x64 ({@code -q:v 3}),
         * tagged by exiftool 13.59 ({@code -charset iptc=UTF8 -codedcharacterset=UTF8 -EXIF:Artist=Ada
         * -EXIF:Orientation#=1 -XMP-dc:Title=Kept}), so that it holds APP0, Exif, APP13, XMP and comment
         * segments; {@code tagged.jxl} is libjxl 0.11.2's {@code cjxl tagged.jpg tagged.jxl --lossless_jpeg=1},
         * which splits the codestream into jxlp boxes and compresses the Exif and xml boxes into brob boxes.
         */
        static byte[] fixture(String name) {
            try (InputStream in = JxlMetadataTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/image/jxl/reconstruction/" + name)) {
                return in.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Test
        @DisplayName("the fixture rebuilds its JPEG byte for byte, comment and APP13 segments included")
        void rebuild() {
            var file = fixture("tagged.jxl");
            assertEquals(List.of("JXL ", "ftyp", "jxlp", "jbrd", "brob:Exif", "brob:xml ", "jxlp"), types(file));
            assertArrayEquals(fixture("tagged.jpg"), JxlDecoder.reconstructJpeg(MediaSource.of(file)).orElseThrow());
        }

        @Test
        @DisplayName("an EXIF edit that keeps the length rebuilds the JPEG an edit of the JPEG gives, with Smalt and djxl")
        void sameLength() {
            var file = fixture("tagged.jxl");
            var edit = ImageMetadataEdit.keepAll().toBuilder().orientation(Orientation.ROTATE_270).build();
            var edited = ImageMetadataEditor.apply(file, edit);
            assertEquals(List.of("JXL ", "ftyp", "jxlp", "jbrd", "Exif", "brob:xml ", "jxlp"), types(edited));
            var expected = ImageMetadataEditor.apply(fixture("tagged.jpg"), edit);
            assertArrayEquals(expected, JxlDecoder.reconstructJpeg(MediaSource.of(edited)).orElseThrow());
            assertEquals(Orientation.ROTATE_270, ImageMetadata.read(MediaSource.of(edited)).orientation());
            Oracle.assume(Oracle.Tool.DJXL);
            var rebuilt = MetadataSupport.DIRECTORY.resolve("jbrd-exif-djxl.jpg");
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(MetadataSupport.save("jbrd-exif-edited.jxl", edited)
                    .toString(), rebuilt.toString(), "--quiet"));
            assertArrayEquals(expected, Fixtures.read(rebuilt));
            assertEquals("8", MetadataSupport.exiftoolRead(Fixtures.read(rebuilt), "jbrd-djxl.jpg").get("IFD0:Orientation"));
        }

        @Test
        @DisplayName("an EXIF or XMP edit that changes a length the reconstruction data records is refused")
        void lengthChange() {
            var file = fixture("tagged.jxl");
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file,
                    ImageMetadataEdit.keepAll().toBuilder().exif(exif("A much longer artist name")).build()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file,
                    ImageMetadataEdit.keepAll().toBuilder().removeXmp().build()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file,
                    ImageMetadataEdit.stripAll()));
        }

        @Test
        @DisplayName("djxl rebuilds the JPEG of the committed fixture as Smalt does")
        void djxlAgrees() {
            Oracle.assume(Oracle.Tool.DJXL);
            var rebuilt = MetadataSupport.DIRECTORY.resolve("tagged-djxl.jpg");
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(MetadataSupport.save("tagged.jxl",
                    fixture("tagged.jxl")).toString(), rebuilt.toString(), "--quiet"));
            assertArrayEquals(fixture("tagged.jpg"), Fixtures.read(rebuilt));
        }
    }

    @Nested
    @DisplayName("libjxl and exiftool")
    class Oracles {
        @ParameterizedTest(name = "compress_boxes={0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("cjxl files edited by Smalt are read by exiftool and decode to the same pixels with djxl")
        void cjxl(int compressBoxes) {
            Oracle.assume(Oracle.Tool.CJXL);
            Oracle.assume(Oracle.Tool.DJXL);
            var png = MetadataSupport.save("cjxl-src-" + compressBoxes + ".png", MetadataSupport.png());
            var tagged = MetadataSupport.exiftoolWrite("cjxl-src-" + compressBoxes + ".png", MetadataSupport.png(),
                    List.of("-EXIF:Artist=Ada", "-XMP-dc:Title=Before"));
            var sources = List.of(Fixtures.read(png), tagged);
            for (var index = 0; index < sources.size(); index++) {
                var input = MetadataSupport.save("cjxl-in-" + compressBoxes + "-" + index + ".png", sources.get(index));
                var output = MetadataSupport.DIRECTORY.resolve("cjxl-" + compressBoxes + "-" + index + ".jxl");
                Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2), List.of(input.toString(), output.toString(), "-d", "1",
                        "--compress_boxes=" + compressBoxes, "--quiet"));
                var file = Fixtures.read(output);
                var edited = ImageMetadataEditor.apply(file, ImageMetadataEdit.keepAll().toBuilder().exif(exif("Hopper"))
                        .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "After")).build());
                var tags = MetadataSupport.exiftoolRead(edited, "cjxl-edited.jxl");
                assertEquals("Hopper", tags.get("IFD0:Artist"), tags.toString());
                assertEquals("After", tags.get("XMP-dc:Title"), tags.toString());
                assertArrayEquals(djxl(file, "before"), djxl(edited, "after"));
                assertSamePixels(file, edited);
                var stripped = ImageMetadataEditor.apply(file, ImageMetadataEdit.stripAll());
                assertFalse(MetadataSupport.exiftoolRead(stripped, "cjxl-stripped.jxl").containsKey("IFD0:Artist"));
                assertArrayEquals(djxl(file, "before"), djxl(stripped, "stripped"));
            }
        }

        static byte[] djxl(byte[] jxl, String name) {
            var input = MetadataSupport.save("djxl-" + name + ".jxl", jxl);
            var output = MetadataSupport.DIRECTORY.resolve("djxl-" + name + ".ppm");
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(input.toString(), output.toString(), "--quiet"));
            return Fixtures.read(output);
        }
    }
}
