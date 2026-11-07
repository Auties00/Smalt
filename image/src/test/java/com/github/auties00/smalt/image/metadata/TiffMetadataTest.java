package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.TiffCompression;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffPredictor;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.auties00.smalt.image.metadata.TiffStructure.Field;
import static com.github.auties00.smalt.image.metadata.TiffStructure.ints;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the metadata of TIFF and BigTIFF files in both byte orders, with strips and tiles and
 * several pages, and checks the result three ways: Smalt and exiftool read the new metadata back,
 * every page decodes to the same pixels (through Smalt and through libtiff's tiffcp), and an
 * independent structure reader finds every byte outside IFD0 and its metadata directories where it
 * was. Crafted files cover shared values, maker notes, private directories, GeoTIFF and Photoshop
 * entries; the libtiff test images and mutated files cover robustness.
 */
@DisplayName("TIFF metadata editing")
class TiffMetadataTest {
    /** The IFD0 tags the test treats as image layout, which every edit must keep as stored. */
    static final Set<Integer> LAYOUT = Set.of(254, 256, 257, 258, 259, 262, 273, 277, 278, 279, 284, 317, 320, 322, 323,
            324, 325, 330, 338, 339, 33550, 33922, 34735, 34736, 34737);

    static RgbaPicture picture(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 7 + random.nextInt(5));
                pixels[at + 1] = (byte) (y * 5);
                pixels[at + 2] = (byte) ((x ^ y) * 3);
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    static byte[] encode(TiffEncoderOptions options, int pages) {
        var pictures = new ArrayList<RgbaPicture>();
        for (var page = 0; page < pages; page++) {
            pictures.add(picture(48 + 8 * page, 40, page));
        }
        return TiffEncoder.encode(pictures, options);
    }

    record Layout(String name, TiffEncoderOptions options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> layouts() {
        var deflate = TiffEncoderOptions.builder().compression(TiffCompression.DEFLATE).predictor(TiffPredictor.HORIZONTAL);
        return Stream.of(
                new Layout("classic II strips", deflate.build()),
                new Layout("classic MM tiles", deflate.tiles(16, 16).bigEndian(true).build()),
                new Layout("BigTIFF II strips", TiffEncoderOptions.builder().compression(TiffCompression.LZW).strips(7)
                        .bigTiff(true).build()),
                new Layout("BigTIFF MM tiles", TiffEncoderOptions.builder().compression(TiffCompression.PACKBITS)
                        .tiles(16, 32).bigEndian(true).bigTiff(true).build())
        ).map(Arguments::of);
    }

    static ExifData richExif() {
        return ExifData.EMPTY
                .with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Grace Hopper"))
                .with(ExifData.Directory.IFD0, 0x010E, new ExifValue.Ascii("A description long enough to be stored out of line"))
                .with(ExifData.Directory.IFD0, 0x010F, new ExifValue.Ascii("SmaltCam"))
                .with(ExifData.Directory.EXIF, 0x9003, new ExifValue.Ascii("2024:05:01 12:00:00"))
                .with(ExifData.Directory.EXIF, 0x927C, new ExifValue.Undefined(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}))
                .with(ExifData.Directory.INTEROP, 0x0001, new ExifValue.Ascii("R98"))
                .with(ExifData.Directory.GPS, 0x0001, new ExifValue.Ascii("N"))
                .with(ExifData.Directory.GPS, 0x0002, new ExifValue.Rationals(ExifValue.Type.RATIONAL,
                        new long[]{45, 27, 51}, new long[]{1, 1, 1}))
                .withOrientation(Orientation.ROTATE_90);
    }

    static ImageMetadataEdit richEdit() {
        return ImageMetadataEdit.keepAll().toBuilder().exif(richExif())
                .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Tiff title").with(XmpPacket.PHOTOSHOP, "City", "Milano"))
                .iptc(IptcData.of(List.of(new IptcData.DataSet(2, 90, "Milano".getBytes(StandardCharsets.UTF_8)),
                        new IptcData.DataSet(2, 25, "duomo".getBytes(StandardCharsets.UTF_8)))))
                .colorProfile(ColorProfile.displayP3()).build();
    }

    @Nested
    @DisplayName("encoded files")
    class Encoded {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.TiffMetadataTest#layouts")
        @DisplayName("adds every kind of metadata, which Smalt, exiftool and libtiff read, keeping every page")
        void add(Layout layout) {
            var original = encode(layout.options(), 3);
            var edited = ImageMetadataEditor.apply(original, richEdit());
            assertOnlyMetadataChanged(original, edited);
            assertPagesIdentical(original, edited);
            assertLibtiffAgrees(original, edited);
            var metadata = ImageMetadata.read(edited);
            assertEquals(ImageFormat.TIFF, metadata.format());
            var exif = metadata.exif().orElseThrow();
            assertEquals(new ExifValue.Ascii("Grace Hopper"), exif.ifd0().get(0x013B));
            assertEquals(new ExifValue.Ascii("2024:05:01 12:00:00"), exif.exif().get(0x9003));
            assertEquals(new ExifValue.Ascii("R98"), exif.interop().get(0x0001));
            assertEquals(Orientation.ROTATE_90, metadata.orientation());
            assertTrue(metadata.hasLocation());
            assertEquals("Tiff title", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertEquals("Milano", metadata.iptc().orElseThrow().string(IptcData.Field.CITY).orElseThrow());
            assertArrayEquals(ColorProfile.displayP3().toByteArray(), metadata.iccProfile().orElseThrow());
            assertFalse(exif.ifd0().containsKey(256), "the layout entries are not part of the EXIF block");
            assertEquals(Orientation.ROTATE_90, TiffDecoder.decode(MediaSource.of(edited)).orientation());
            if (hasExiftool()) {
                var tags = MetadataSupport.exiftoolRead(edited, "added-" + layout.name().replace(' ', '-') + ".tif");
                assertEquals("Grace Hopper", tags.get("IFD0:Artist"), tags.toString());
                assertEquals("6", tags.get("IFD0:Orientation"));
                // exiftool names the sub-directories of a BigTIFF after their pointer tags and reads them with the IFD0 table
                var big = layout.options().isBigTiff();
                assertEquals("2024:05:01 12:00:00", tags.get(big ? "ExifOffset:DateTimeOriginal" : "ExifIFD:DateTimeOriginal"));
                assertEquals("R98", tags.get(big ? "InteropOffset:InteropIndex" : "InteropIFD:InteropIndex"));
                assertEquals("N", tags.get(big ? "GPSInfo:InteropIndex" : "GPS:GPSLatitudeRef"));
                assertEquals("Tiff title", tags.get("XMP-dc:Title"));
                assertEquals("Milano", tags.get("IPTC:City"));
                assertTrue(tags.values().stream().anyMatch(value -> value.contains("Display P3")), tags.toString());
                assertEquals(String.valueOf(48 + 16), tags.get("IFD2:ImageWidth"), "the third page is still read");
            }

            var located = ImageMetadataEditor.stripLocation(edited);
            assertOnlyMetadataChanged(edited, located);
            assertPagesIdentical(original, located);
            var withoutLocation = ImageMetadata.read(located);
            assertFalse(withoutLocation.hasLocation());
            assertEquals(new ExifValue.Ascii("Grace Hopper"), withoutLocation.exif().orElseThrow().ifd0().get(0x013B));
            assertFalse(new String(located, StandardCharsets.ISO_8859_1).contains("Milano"), "a place name survived");
            if (hasExiftool()) {
                var locatedTags = MetadataSupport.exiftoolRead(located, "located-" + layout.name().replace(' ', '-') + ".tif");
                assertTrue(locatedTags.keySet().stream().noneMatch(key -> key.startsWith("GPS:") || key.endsWith(":City")),
                        locatedTags.toString());
            }

            var stripped = ImageMetadataEditor.stripAll(located);
            assertOnlyMetadataChanged(located, stripped);
            assertPagesIdentical(original, stripped);
            assertLibtiffAgrees(original, stripped);
            var bare = ImageMetadata.read(stripped);
            var kept = bare.exif().orElseThrow();
            assertEquals(Set.of(282, 283, 296), kept.ifd0().keySet(), "only the resolution TIFF requires stays");
            assertTrue(kept.exif().isEmpty() && kept.gps().isEmpty() && kept.interop().isEmpty());
            assertTrue(bare.xmp().isEmpty());
            assertTrue(bare.iptc().isEmpty());
            assertArrayEquals(ColorProfile.displayP3().toByteArray(), bare.iccProfile().orElseThrow());
            var text = new String(stripped, StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("Grace") || text.contains("Tiff title") || text.contains("duomo"), "removed text survived");
            var withoutIcc = ImageMetadataEditor.apply(stripped, ImageMetadataEdit.stripAll().toBuilder().removeIccProfile().build());
            var none = ImageMetadata.read(withoutIcc);
            assertTrue(none.iccProfile().isEmpty() && none.xmp().isEmpty() && none.iptc().isEmpty());
            assertPagesIdentical(original, withoutIcc);
            assertTrue(withoutIcc.length <= original.length + 400, "the freed tail is reclaimed: " + withoutIcc.length
                    + " bytes after a round trip of a " + original.length + " byte file");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.TiffMetadataTest#layouts")
        @DisplayName("keeps every byte when the edit changes nothing, and when it is applied twice")
        void identity(Layout layout) {
            var original = encode(layout.options(), 2);
            assertSame(original, ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll()));
            assertSame(original, ImageMetadataEditor.stripAll(original));
            var edited = ImageMetadataEditor.apply(original, richEdit());
            assertSame(edited, ImageMetadataEditor.apply(edited, richEdit()));
            assertSame(edited, ImageMetadataEditor.apply(edited, ImageMetadataEdit.keepAll().toBuilder()
                    .orientation(Orientation.ROTATE_90).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.TiffMetadataTest#layouts")
        @DisplayName("changes only the orientation, keeping the IFD0 table in place")
        void orientation(Layout layout) {
            var original = ImageMetadataEditor.apply(encode(layout.options(), 2), richEdit());
            var edited = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder()
                    .orientation(Orientation.ROTATE_270).build());
            assertEquals(original.length, edited.length);
            var differences = 0;
            for (var i = 0; i < original.length; i++) {
                differences += original[i] != edited[i] ? 1 : 0;
            }
            assertEquals(1, differences, "only the orientation value changes");
            assertEquals(Orientation.ROTATE_270, TiffDecoder.decode(MediaSource.of(edited)).orientation());
            assertEquals("8", MetadataSupport.exiftoolRead(edited, "oriented-" + layout.name().replace(' ', '-') + ".tif")
                    .get("IFD0:Orientation"));
        }

        @Test
        @DisplayName("repeated edits of growing and shrinking metadata do not grow the file without bound")
        void repeated() {
            var original = encode(TiffEncoderOptions.deflate(), 2);
            var current = original;
            var longest = 0;
            for (var round = 0; round < 12; round++) {
                var title = "t".repeat(round % 2 == 0 ? 40 + round * 50 : 10);
                current = ImageMetadataEditor.apply(current, ImageMetadataEdit.keepAll().toBuilder()
                        .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", title))
                        .exif(ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii(title))).build());
                assertEquals(title, ImageMetadata.read(current).xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
                longest = Math.max(longest, title.length());
            }
            assertPagesIdentical(original, current);
            assertOnlyMetadataChanged(original, current);
            assertTrue(current.length < original.length + 8 * longest, current.length + " bytes after 12 edits");
        }

        @Test
        @DisplayName("edits a file on disk in place and through a sink, with the same result as in memory")
        void fileAndStream(@TempDir Path directory) throws IOException {
            var original = encode(TiffEncoderOptions.deflate().toBuilder().bigTiff(true).build(), 3);
            var expected = ImageMetadataEditor.apply(original, richEdit());
            var file = directory.resolve("edit.tif");
            Files.write(file, original);
            ImageMetadataEditor.apply(file, richEdit());
            assertArrayEquals(expected, Files.readAllBytes(file));
            ImageMetadataEditor.apply(file, ImageMetadataEdit.stripAll());
            assertArrayEquals(ImageMetadataEditor.stripAll(expected), Files.readAllBytes(file));
            assertTrue(Files.size(file) < expected.length, "the file is truncated in place");
            var stripped = Files.readAllBytes(file);
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file,
                    ImageMetadataEdit.keepAll().toBuilder().comments(List.of("a comment")).build()));
            assertArrayEquals(stripped, Files.readAllBytes(file), "a refused edit leaves the file untouched");
            var sink = MediaSink.memory();
            ImageMetadataEditor.apply(MediaSource.of(original), sink, richEdit());
            assertArrayEquals(expected, sink.toByteArray());
        }

        @Test
        @DisplayName("rejects comments, text and an EXIF thumbnail, which TIFF files cannot store")
        void unsupported() {
            var original = encode(TiffEncoderOptions.deflate(), 1);
            var thumbnail = MetadataSupport.jpeg();
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(original,
                    ImageMetadataEdit.keepAll().toBuilder().comments(List.of("a comment")).build()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(original,
                    ImageMetadataEdit.keepAll().toBuilder().text(Map.of("Title", "x")).build()));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(original,
                    ImageMetadataEdit.keepAll().toBuilder().exif(ExifData.EMPTY.withThumbnail(thumbnail)).build()));
        }
    }

    @Nested
    @DisplayName("exiftool files")
    class Exiftool {
        byte[] rich() {
            var image = encode(TiffEncoderOptions.deflate().toBuilder().tiles(16, 16).build(), 2);
            var tags = new ArrayList<>(ImageMetadataTest.richTags(ImageMetadataTest.displayP3()));
            tags.addAll(List.of("-IPTC:City=Milano", "-IPTC:Keywords=church", "-EXIF:XResolution=300",
                    "-EXIF:ResolutionUnit=2", "-ExifIFD:UserComment=kept comment"));
            return MetadataSupport.exiftoolWrite("rich.tif", image, tags);
        }

        @Test
        @DisplayName("reads what exiftool wrote and strips the location of it")
        void stripLocation() {
            var image = rich();
            var metadata = ImageMetadata.read(image);
            var exif = metadata.exif().orElseThrow();
            assertEquals(new ExifValue.Ascii("Ada"), exif.ifd0().get(0x013B));
            assertEquals(Orientation.ROTATE_90, metadata.orientation());
            assertTrue(exif.hasLocation());
            assertEquals("Duomo", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertEquals("Milano", metadata.iptc().orElseThrow().string(IptcData.Field.CITY).orElseThrow());
            assertTrue(metadata.colorProfile().orElseThrow().description().orElse("").contains("P3"));
            var edited = ImageMetadataEditor.stripLocation(image);
            assertOnlyMetadataChanged(image, edited);
            assertPagesIdentical(image, edited);
            assertLibtiffAgrees(image, edited);
            assertFalse(ImageMetadata.read(edited).hasLocation());
            assertFalse(new String(edited, StandardCharsets.ISO_8859_1).contains("Milano"), "a place name survived");
            var tags = MetadataSupport.exiftoolRead(edited, "exiftool-located.tif");
            assertTrue(tags.keySet().stream().noneMatch(key -> key.contains("GPS") || key.endsWith(":City")), tags.toString());
            assertEquals("Ada", tags.get("IFD0:Artist"));
            assertEquals("SmaltCam", tags.get("IFD0:Make"));
            assertEquals("300", tags.get("IFD0:XResolution"));
            assertEquals("kept comment", tags.get("ExifIFD:UserComment"));
            assertEquals("church", tags.get("IPTC:Keywords"));
            assertTrue(MetadataSupport.exiftoolWarnings(image, "exiftool-before.tif")
                    .containsAll(MetadataSupport.exiftoolWarnings(edited, "exiftool-after.tif")));
            assertSame(image, ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll()));
        }

        @Test
        @DisplayName("strips everything but the color profile and the resolution TIFF requires")
        void stripAll() {
            var image = rich();
            var edited = ImageMetadataEditor.stripAll(image);
            assertOnlyMetadataChanged(image, edited);
            assertPagesIdentical(image, edited);
            var tags = MetadataSupport.exiftoolRead(edited, "exiftool-stripped.tif");
            assertTrue(tags.keySet().stream().noneMatch(key -> key.startsWith("ExifIFD:") || key.startsWith("GPS:")
                    || key.startsWith("XMP") || key.startsWith("IPTC:") || key.equals("IFD0:Artist")
                    || key.equals("IFD0:Make") || key.equals("IFD0:Orientation")), tags.toString());
            assertEquals("300", tags.get("IFD0:XResolution"));
            assertEquals("2", tags.get("IFD0:ResolutionUnit"));
            assertTrue(tags.values().stream().anyMatch(value -> value.contains("P3")), tags.toString());
            assertEquals(Orientation.NORMAL, TiffDecoder.decode(MediaSource.of(edited)).orientation());
        }

        @Test
        @DisplayName("changes the orientation in EXIF and XMP")
        void orientation() {
            var image = MetadataSupport.exiftoolWrite("oriented.tif", encode(TiffEncoderOptions.deflate(), 1),
                    List.of("-EXIF:Orientation#=6", "-XMP-tiff:Orientation#=6"));
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .orientation(Orientation.ROTATE_270).build());
            var tags = MetadataSupport.exiftoolRead(edited, "reoriented.tif");
            assertEquals("8", tags.get("IFD0:Orientation"));
            assertEquals("8", tags.get("XMP-tiff:Orientation"));
            assertEquals(Orientation.ROTATE_270, TiffDecoder.decode(MediaSource.of(edited)).orientation());
            assertPagesIdentical(image, edited);
        }
    }

    @Nested
    @DisplayName("crafted files")
    class Crafted {
        static Stream<Arguments> orders() {
            return Stream.of(Arguments.of(true, false), Arguments.of(false, false), Arguments.of(true, true),
                    Arguments.of(false, true));
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("keeps a value IFD0 shares with another page when IFD0 changes it")
        void sharedValue(boolean little, boolean big) {
            var resolution = Field.of(282, 5, ints(little, 4, 72, 1));
            var image = TiffStructure.build(little, big, List.of(List.of(resolution, Field.ascii(0x013B, "Someone famous")),
                    List.of(resolution)));
            var parsed = TiffStructure.parse(image);
            var second = parsed.pages().get(1).find(282);
            var first = parsed.ifd0().find(282);
            if (!first.inline()) {
                var size = big ? 8 : 4;
                TiffStructure.write(image, (int) first.fieldPosition(), size, second.valueOffset(), little);
            }
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder().exif(ExifData.EMPTY
                    .with(ExifData.Directory.IFD0, 282, ExifValue.ofRational(300, 1))).build());
            var after = TiffStructure.parse(edited);
            assertArrayEquals(ints(little, 4, 300, 1), after.value(after.ifd0().find(282)));
            assertArrayEquals(ints(little, 4, 72, 1), after.value(after.pages().get(1).find(282)));
            assertEquals(second, after.pages().get(1).find(282));
            assertNull(after.ifd0().find(0x013B));
            assertOnlyMetadataChanged(image, edited);
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("keeps an unchanged maker note at its offset")
        void makerNote(boolean little, boolean big) {
            var note = new byte[96];
            for (var i = 0; i < note.length; i++) {
                note[i] = (byte) (i * 31 + 7);
            }
            var image = TiffStructure.build(little, big, List.of(List.of(Field.ascii(0x013B, "Ada Lovelace"),
                    Field.dir(TiffStructure.EXIF, List.of(Field.ascii(0x9003, "2020:01:01 00:00:00"),
                            Field.of(0x927C, 7, note))))));
            var before = TiffStructure.parse(image);
            var oldNote = before.child(before.ifd0(), TiffStructure.EXIF).find(0x927C);
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder().exif(
                    ImageMetadata.read(image).exif().orElseThrow().with(ExifData.Directory.IFD0, 0x013B,
                            new ExifValue.Ascii("Grace Hopper, a longer name"))).build());
            var after = TiffStructure.parse(edited);
            var newNote = after.child(after.ifd0(), TiffStructure.EXIF).find(0x927C);
            assertEquals(oldNote.valueOffset(), newNote.valueOffset());
            assertArrayEquals(note, after.value(newNote));
            assertEquals("Grace Hopper, a longer name", new String(after.value(after.ifd0().find(0x013B)), 0, 27,
                    StandardCharsets.ISO_8859_1));
            assertOnlyMetadataChanged(image, edited);
            assertPagesIdentical(image, edited);
        }

        @Test
        @DisplayName("keeps the entry of a repeated tag that a reader resolves, the first of a known type")
        void repeatedTag() {
            var image = TiffStructure.build(true, false, List.of(List.of(Field.of(255, 99, ints(true, 4, 7)))));
            // turn the unknown-typed entry, sorted before ImageWidth, into a second ImageWidth
            var entry = TiffStructure.parse(image).ifd0().find(255);
            TiffStructure.write(image, (int) entry.fieldPosition() - 8, 2, 256, true);
            assertEquals(8, TiffDecoder.decode(MediaSource.of(image)).picture().width());
            var stripped = ImageMetadataEditor.stripAll(image);
            assertEquals(8, TiffDecoder.decode(MediaSource.of(stripped)).picture().width());
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("keeps GeoTIFF, private directories and unknown types, and drops what removeOther names")
        void structuralAndOther(boolean little, boolean big) {
            var geoKeys = ints(little, 2, 1, 1, 0, 1, 1024, 0, 1, 2);
            var scale = new byte[24];
            for (var i = 0; i < 3; i++) {
                TiffStructure.write(scale, 8 * i, 8, Double.doubleToLongBits(0.5 + i), little);
            }
            var image = TiffStructure.build(little, big, List.of(List.of(Field.of(33550, 12, scale),
                    Field.of(34735, 3, geoKeys), Field.ascii(0x013B, "Someone famous"),
                    Field.of(282, 5, ints(little, 4, 300, 1)), Field.of(296, 3, ints(little, 2, 2)),
                    Field.of(65001, 99, ints(little, 4, 12345)),
                    Field.of(0x02BC, 1, "<x:xmpmeta xmlns:x='adobe:ns:meta/'/>".getBytes(StandardCharsets.UTF_8)))));
            var before = TiffStructure.parse(image);
            var unknown = before.ifd0().find(65001);
            var unknownField = Arrays.copyOfRange(image, (int) unknown.fieldPosition(), (int) unknown.fieldPosition() + 4);
            var xmpChanged = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "x")).build());
            var after = TiffStructure.parse(xmpChanged);
            assertEquals(before.ifd0().find(65001).type(), after.ifd0().find(65001).type());
            var moved = after.ifd0().find(65001);
            assertArrayEquals(unknownField, Arrays.copyOfRange(xmpChanged, (int) moved.fieldPosition(),
                    (int) moved.fieldPosition() + 4));
            // an EXIF block naming layout tags changes none of them
            var layoutEdit = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder().exif(ExifData.EMPTY
                    .with(ExifData.Directory.IFD0, 256, ExifValue.ofLong(999))
                    .with(ExifData.Directory.IFD0, 34735, new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{9}))
                    .with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Other"))).build());
            assertOnlyMetadataChanged(image, layoutEdit);
            var layoutParsed = TiffStructure.parse(layoutEdit);
            assertArrayEquals(geoKeys, layoutParsed.value(layoutParsed.ifd0().find(34735)));
            assertArrayEquals(ints(little, 2, 8), layoutParsed.value(layoutParsed.ifd0().find(256)));
            // removing EXIF alone keeps the entries EXIF cannot represent; only removeOther drops them
            var withoutExif = TiffStructure.parse(ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .removeExif().build()));
            assertNull(withoutExif.ifd0().find(0x013B));
            assertNotNull(withoutExif.ifd0().find(65001), "an entry of an unknown type is other metadata, not EXIF");
            assertNotNull(withoutExif.ifd0().find(0x02BC));
            var stripped = ImageMetadataEditor.stripAll(image);
            var bare = TiffStructure.parse(stripped);
            assertArrayEquals(scale, bare.value(bare.ifd0().find(33550)));
            assertArrayEquals(geoKeys, bare.value(bare.ifd0().find(34735)));
            assertArrayEquals(ints(little, 4, 300, 1), bare.value(bare.ifd0().find(282)));
            assertNotNull(bare.ifd0().find(296));
            assertNull(bare.ifd0().find(0x013B));
            assertNull(bare.ifd0().find(0x02BC));
            assertNull(bare.ifd0().find(65001), "removeOther drops entries the EXIF model cannot represent");
            assertOnlyMetadataChanged(image, stripped);
            assertPagesIdentical(image, stripped);
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("keeps the SubIFDs of IFD0, their values and a value they share with IFD0")
        void subIfds(boolean little, boolean big) {
            var preview = new byte[40];
            for (var i = 0; i < preview.length; i++) {
                preview[i] = (byte) (i * 13 + 1);
            }
            var resolution = Field.of(282, 5, ints(little, 4, 240, 1));
            var image = TiffStructure.build(little, big, List.of(List.of(resolution, Field.ascii(0x013B, "Someone famous"),
                    Field.dir(TiffStructure.SUB_IFDS, List.of(Field.of(254, 4, ints(little, 4, 1)), resolution,
                            Field.of(50000, 1, preview))))));
            var parsed = TiffStructure.parse(image);
            var child = parsed.child(parsed.ifd0(), TiffStructure.SUB_IFDS);
            var shared = child.find(282);
            var first = parsed.ifd0().find(282);
            if (!first.inline()) {
                TiffStructure.write(image, (int) first.fieldPosition(), big ? 8 : 4, shared.valueOffset(), little);
            }
            var resolutionEdit = ImageMetadataEdit.keepAll().toBuilder().exif(ExifData.EMPTY
                    .with(ExifData.Directory.IFD0, 282, ExifValue.ofRational(96, 1))).build();
            for (var edit : List.of(richEdit(), ImageMetadataEdit.stripAll(), resolutionEdit)) {
                var edited = ImageMetadataEditor.apply(image, edit);
                assertOnlyMetadataChanged(image, edited);
                var after = TiffStructure.parse(edited);
                var kept = after.child(after.ifd0(), TiffStructure.SUB_IFDS);
                assertEquals(child, kept, "the SubIFD moved or changed");
                assertArrayEquals(ints(little, 4, 240, 1), after.value(kept.find(282)), "the shared value changed");
                assertArrayEquals(preview, after.value(kept.find(50000)));
                assertArrayEquals(TiffStructure.parse(image).value(parsed.ifd0().find(TiffStructure.SUB_IFDS)),
                        after.value(after.ifd0().find(TiffStructure.SUB_IFDS)));
            }
        }

        static Stream<Arguments> referencedTails() {
            return orders().flatMap(order -> Stream.of("GlobalParametersIFD", "JPEGQTables", "JPEGDCTables",
                    "JPEGACTables", "JPEGQTables cut off", "JPEGDCTables cut off")
                    .map(kind -> Arguments.of(order.get()[0], order.get()[1], kind)));
        }

        @ParameterizedTest(name = "little={0} big={1} {2}")
        @MethodSource("referencedTails")
        @DisplayName("keeps zero bytes ending the file that a GlobalParametersIFD or an old-style JPEG table references")
        void referencedTail(boolean little, boolean big, String kind) {
            var scale = new byte[24];
            for (var i = 0; i < 3; i++) {
                TiffStructure.write(scale, 8 * i, 8, Double.doubleToLongBits(0.25 + i), little);
            }
            // the kept ModelPixelScale value is the last referenced byte before the old IFD0 table, so
            // only what the referencing entry names keeps the zeros appended after that table
            var image = TiffStructure.build(little, big, List.of(List.of(Field.ascii(0x013B, "Someone famous"),
                    Field.dir(400, List.of(Field.of(273, 4, ints(little, 4, 0)), Field.of(279, 4, ints(little, 4, 32)))),
                    Field.of(519, 4, ints(little, 4, 0)), Field.of(520, 4, ints(little, 4, 0)),
                    Field.of(521, 4, ints(little, 4, 0)),
                    Field.ascii(0x8298, "Nobody"), Field.of(33550, 12, scale))));
            var base = (image.length + 7) & ~7;
            var block = switch (kind) {
                case "GlobalParametersIFD" -> new byte[32];
                case "JPEGQTables" -> new byte[64];
                // a table the end of the file cuts off is kept up to the end
                case "JPEGQTables cut off" -> new byte[10];
                // code length counts the end of the file cuts off
                case "JPEGDCTables cut off" -> new byte[8];
                // 16 zero code length counts
                case "JPEGACTables" -> new byte[16];
                default -> {
                    // 16 code length counts naming 40 codes, then 40 zero values
                    var table = new byte[16 + 40];
                    table[1] = 40;
                    yield table;
                }
            };
            var file = Arrays.copyOf(image, base + block.length);
            System.arraycopy(block, 0, file, base, block.length);
            var parsed = TiffStructure.parse(file);
            var global = TiffStructure.directory(file, parsed.integers(parsed.ifd0().find(400))[0], little, big);
            var pointer = switch (kind) {
                case "GlobalParametersIFD" -> global.find(273);
                case "JPEGQTables", "JPEGQTables cut off" -> parsed.ifd0().find(519);
                case "JPEGACTables" -> parsed.ifd0().find(521);
                default -> parsed.ifd0().find(520);
            };
            TiffStructure.write(file, (int) pointer.fieldPosition(), 4, base, little);
            var original = TiffStructure.parse(file);
            var resolutionEdit = ImageMetadataEdit.keepAll().toBuilder().exif(ExifData.EMPTY
                    .with(ExifData.Directory.IFD0, 282, ExifValue.ofRational(96, 1))).build();
            for (var edit : List.of(richEdit(), ImageMetadataEdit.stripAll(), resolutionEdit)) {
                var edited = ImageMetadataEditor.apply(file, edit);
                assertTrue(edited.length >= base + block.length, "the referenced data was cut off");
                assertArrayEquals(block, Arrays.copyOfRange(edited, base, base + block.length),
                        "the referenced data was overwritten");
                var after = TiffStructure.parse(edited);
                for (var directory : after.directories().values()) {
                    assertTrue(directory.end() <= base || directory.offset() >= base + block.length,
                            "a directory was written over the referenced data");
                    for (var entry : directory.entries()) {
                        if (!entry.inline() && entry.valueOffset() < base + block.length
                                && entry.valueOffset() + entry.length() > base) {
                            throw new AssertionError("tag " + entry.tag() + " was written over the referenced data");
                        }
                    }
                }
                var offset = original.integers(original.ifd0().find(400))[0];
                assertArrayEquals(original.value(original.ifd0().find(400)), after.value(after.ifd0().find(400)));
                assertEquals(TiffStructure.directory(file, offset, little, big),
                        TiffStructure.directory(edited, offset, little, big), "the GlobalParametersIFD changed");
                for (var tag : List.of(519, 520, 521)) {
                    assertArrayEquals(original.value(original.ifd0().find(tag)), after.value(after.ifd0().find(tag)));
                }
                assertOnlyMetadataChanged(file, edited);
            }
        }

        static Stream<Arguments> integerTails() {
            return orders().flatMap(order -> Stream.of(true, false).flatMap(inExif -> Stream.of(65000, 40962)
                    .map(tag -> Arguments.of(order.get()[0], order.get()[1], inExif, tag))));
        }

        @ParameterizedTest(name = "little={0} big={1} exif={2} tag={3}")
        @MethodSource("integerTails")
        @DisplayName("keeps zero bytes ending the file that a kept LONG entry of an unrecognized tag may point at")
        void integerTail(boolean little, boolean big, boolean inExif, int tag) {
            var scale = new byte[24];
            for (var i = 0; i < 3; i++) {
                TiffStructure.write(scale, 8 * i, 8, Double.doubleToLongBits(0.25 + i), little);
            }
            // a BigTIFF stores the private tag as LONG8, a classic TIFF as LONG
            var wide = big && tag == 65000;
            var integer = Field.of(tag, wide ? 16 : 4, ints(little, wide ? 8 : 4, 0));
            var fields = new ArrayList<>(List.of(Field.ascii(0x013B, "Someone famous"), Field.ascii(0x8298, "Nobody"),
                    Field.of(33550, 12, scale)));
            fields.add(inExif ? Field.dir(TiffStructure.EXIF, List.of(Field.ascii(0x9003, "2020:01:01 00:00:00"), integer))
                    : integer);
            var image = TiffStructure.build(little, big, List.of(fields));
            var base = (image.length + 7) & ~7;
            var block = new byte[48];
            var file = Arrays.copyOf(image, base + block.length);
            var parsed = TiffStructure.parse(file);
            var holder = inExif ? parsed.child(parsed.ifd0(), TiffStructure.EXIF) : parsed.ifd0();
            TiffStructure.write(file, (int) holder.find(tag).fieldPosition(), wide ? 8 : 4, base, little);
            var xmpEdit = ImageMetadataEdit.keepAll().toBuilder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "x"))
                    .build();
            var exifEdit = ImageMetadataEdit.keepAll().toBuilder().exif(ImageMetadata.read(file).exif().orElseThrow()
                    .with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Grace Hopper, a longer name"))).build();
            for (var edit : List.of(xmpEdit, exifEdit)) {
                if (wide && inExif && edit == exifEdit) {
                    // a rebuilt Exif directory holds only entries of the EXIF types, which LONG8 is not
                    continue;
                }
                var edited = ImageMetadataEditor.apply(file, edit);
                assertOnlyMetadataChanged(file, edited);
                var after = TiffStructure.parse(edited);
                var kept = inExif ? after.child(after.ifd0(), TiffStructure.EXIF) : after.ifd0();
                assertNotNull(kept.find(tag), "the edit dropped the entry");
                assertEquals(base, TiffStructure.read(edited, (int) kept.find(tag).fieldPosition(), wide ? 8 : 4, little));
                var blockKept = edited.length >= base + block.length
                        && Arrays.equals(block, Arrays.copyOfRange(edited, base, base + block.length))
                        && after.directories().values().stream().allMatch(directory -> (directory.end() <= base
                        || directory.offset() >= base + block.length) && directory.entries().stream().allMatch(entry ->
                        entry.inline() || entry.valueOffset() >= base + block.length
                                || entry.valueOffset() + entry.length() <= base));
                if (tag == 65000) {
                    assertTrue(blockKept, "the data an unrecognized LONG entry may point at was cut off or overwritten");
                } else {
                    // PixelXDimension holds a value, so the zeros after the last referenced byte are reclaimed
                    assertFalse(blockKept, "the zeros a recognized LONG value names were kept");
                }
            }
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("rewrites IFD0 at its old offset whatever its alignment")
        void unaligned(boolean little, boolean big) {
            var image = TiffStructure.build(little, big, List.of(List.of(Field.of(274, 3, ints(little, 2, 1)),
                    Field.ascii(0x013B, "Ada Byron"))));
            var offset = TiffStructure.parse(image).ifd0().offset();
            if (big) {
                assertTrue(offset % 8 != 0, "the test needs IFD0 off the 8-byte alignment of new blocks: " + offset);
            }
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .orientation(Orientation.ROTATE_180).build());
            assertEquals(image.length, edited.length);
            var differences = 0;
            for (var i = 0; i < image.length; i++) {
                differences += image[i] != edited[i] ? 1 : 0;
            }
            assertEquals(1, differences, "only the orientation value changes");
            assertEquals(Orientation.ROTATE_180, ImageMetadata.read(edited).orientation());
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("reads IPTC from the Photoshop resources and edits it there, keeping the other resources")
        void photoshop(boolean little, boolean big) {
            var iim = IptcData.of(List.of(new IptcData.DataSet(2, 90, "Milano".getBytes(StandardCharsets.UTF_8)),
                    new IptcData.DataSet(2, 5, "Title".getBytes(StandardCharsets.UTF_8)))).toBytes();
            var resolution = new byte[16];
            Arrays.fill(resolution, (byte) 0x42);
            var resources = PhotoshopResources.encode(List.of(new PhotoshopResources.Resource("8BIM", 0x03ED, new byte[0],
                    resolution), new PhotoshopResources.Resource("8BIM", 0x0404, new byte[0], iim)));
            var image = TiffStructure.build(little, big, List.of(List.of(Field.of(34377, 7, resources))));
            assertEquals("Milano", ImageMetadata.read(image).iptc().orElseThrow().string(IptcData.Field.CITY).orElseThrow());
            var located = ImageMetadataEditor.stripLocation(image);
            assertFalse(new String(located, StandardCharsets.ISO_8859_1).contains("Milano"));
            var parsed = TiffStructure.parse(located);
            var kept = PhotoshopResources.parse(parsed.value(parsed.ifd0().find(34377)), 0,
                    (int) parsed.ifd0().find(34377).length());
            assertEquals(List.of(0x03ED, 0x0404), kept.stream().map(PhotoshopResources.Resource::id).toList());
            assertArrayEquals(resolution, kept.getFirst().data());
            assertEquals("Title", ImageMetadata.read(located).iptc().orElseThrow().string(IptcData.Field.OBJECT_NAME)
                    .orElseThrow());
            assertNull(TiffStructure.parse(ImageMetadataEditor.stripAll(image)).ifd0().find(34377));
            assertOnlyMetadataChanged(image, located);
        }

        @ParameterizedTest(name = "little={0} big={1}")
        @MethodSource("orders")
        @DisplayName("writes IPTC as padded LONG values and XMP as BYTE values, as exiftool does")
        void entryTypes(boolean little, boolean big) {
            var image = TiffStructure.build(little, big, List.of(List.of()));
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .iptc(IptcData.of(List.of(new IptcData.DataSet(2, 90, "Roma".getBytes(StandardCharsets.UTF_8)))))
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "x")).build());
            var parsed = TiffStructure.parse(edited);
            var iptc = parsed.ifd0().find(33723);
            assertEquals(4, iptc.type());
            assertEquals(0, iptc.length() % 4);
            assertEquals(1, parsed.ifd0().find(0x02BC).type());
            assertEquals("Roma", ImageMetadata.read(edited).iptc().orElseThrow().string(IptcData.Field.CITY).orElseThrow());
            assertPagesIdentical(image, edited);
            if (!big && hasExiftool()) {
                assertEquals("Roma", MetadataSupport.exiftoolRead(edited, "types-" + little + ".tif").get("IPTC:City"));
            }
        }

        @Test
        @DisplayName("reads the metadata of a truncated IFD0 but refuses to edit it")
        void truncated() {
            var image = TiffStructure.build(true, false, List.of(List.of(Field.ascii(0x013B, "Ada"))));
            var parsed = TiffStructure.parse(image);
            var cut = Arrays.copyOf(image, (int) parsed.ifd0().end() - 2);
            assertEquals(new ExifValue.Ascii("Ada"), ImageMetadata.read(cut).exif().orElseThrow().ifd0().get(0x013B));
            assertThrows(MediaException.Malformed.class, () -> ImageMetadataEditor.stripAll(cut));
        }

        @Test
        @DisplayName("rejects an edit whose output a classic TIFF cannot address")
        void classicLimit() {
            var image = TiffStructure.build(true, false, List.of(List.of(Field.of(274, 3, ints(true, 2, 1)))));
            var size = 0xFFFF_FFF0L;
            var source = new MediaSource() {
                @Override
                public long size() {
                    return size;
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    if (position >= size) {
                        return -1;
                    }
                    var count = (int) Math.min(length, size - position);
                    Arrays.fill(buffer, offset, offset + count, (byte) 0);
                    if (position < image.length) {
                        System.arraycopy(image, (int) position, buffer, offset, (int) Math.min(count, image.length - position));
                    }
                    return count;
                }

                @Override
                public void close() {
                }
            };
            var edit = ImageMetadataEdit.keepAll().toBuilder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title",
                    "t".repeat(400))).build();
            var failure = assertThrows(MediaException.LimitExceeded.class, () -> ImageMetadataEditor.apply(source,
                    MediaSink.memory(), edit));
            assertTrue(failure.getMessage().contains("4 GiB"), failure.getMessage());
            var small = ImageMetadataEdit.keepAll().toBuilder().orientation(Orientation.ROTATE_180).build();
            var sink = MediaSink.memory();
            var counting = new MediaSink() {
                long written;

                @Override
                public long position() {
                    return written;
                }

                @Override
                public void position(long position) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isSeekable() {
                    return false;
                }

                @Override
                public void write(java.nio.ByteBuffer data) {
                    var length = data.remaining();
                    if (written < 4096) {
                        var chunk = new byte[(int) Math.min(length, 4096 - written)];
                        data.get(chunk);
                        sink.write(chunk);
                    }
                    data.position(data.limit());
                    written += length;
                }

                @Override
                public void close() {
                }
            };
            ImageMetadataEditor.apply(source, counting, small);
            assertEquals(size, counting.written, "an edit that fits the old IFD0 keeps the file size");
            assertEquals(Orientation.ROTATE_180, ImageMetadata.read(Arrays.copyOf(sink.toByteArray(), image.length))
                    .orientation());
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("edits every libtiff test image that it can read, keeping its pages")
        void libtiffPictures() {
            Map<String, byte[]> pictures = RasterTesting.untarGz(Conformance.fetch("libtiff-pics-3.8.0.tar.gz",
                    "http://download.osgeo.org/libtiff/pics-3.8.0.tar.gz",
                    "e0e34732b61e1ce49eff2c7a079994c856d2a5f772f5228c84678272bc6829a9"));
            var edited = 0;
            for (var entry : pictures.entrySet()) {
                var name = entry.getKey();
                if (!name.endsWith(".tif") && !name.endsWith(".tiff")) {
                    continue;
                }
                var image = entry.getValue();
                byte[] result;
                try {
                    ImageMetadata.read(image);
                    result = ImageMetadataEditor.apply(image, richEdit());
                } catch (MediaException exception) {
                    continue;
                }
                edited++;
                try {
                    assertOnlyMetadataChanged(image, result);
                    assertPagesIdentical(image, result);
                    assertEquals("Tiff title", ImageMetadata.read(result).xmp().orElseThrow().text(XmpPacket.DC, "title")
                            .orElseThrow());
                    var stripped = ImageMetadataEditor.stripAll(result);
                    assertOnlyMetadataChanged(result, stripped);
                    assertPagesIdentical(image, stripped);
                    assertLibtiffAgrees(image, stripped);
                } catch (AssertionError | RuntimeException failure) {
                    throw new AssertionError(name + ": " + failure.getMessage(), failure);
                }
            }
            assertTrue(edited >= 20, edited + " libtiff images edited");
        }

        @Test
        @DisplayName("fails cleanly on mutated files, and edits what it accepts into readable files")
        void mutations() {
            var seeds = List.of(
                    ImageMetadataEditor.apply(encode(TiffEncoderOptions.deflate(), 2), richEdit()),
                    ImageMetadataEditor.apply(encode(TiffEncoderOptions.deflate().toBuilder().bigTiff(true).bigEndian(true)
                            .build(), 2), richEdit()),
                    TiffStructure.build(false, false, List.of(List.of(Field.ascii(0x013B, "Ada Lovelace"),
                            Field.dir(TiffStructure.EXIF, List.of(Field.ascii(0x9003, "2020:01:01 00:00:00")))),
                            List.of())));
            var edits = List.of(richEdit(), ImageMetadataEdit.stripAll(), ImageMetadataEdit.stripLocation());
            assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds()), () -> {
                var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(400), 0x7157, source -> {
                    var bytes = new byte[(int) source.size()];
                    source.readFully(0, bytes, 0, bytes.length);
                    for (var edit : edits) {
                        var result = ImageMetadataEditor.apply(bytes, edit);
                        ImageMetadata.read(result);
                    }
                });
                assertTrue(stats.decoded() > 0 && stats.rejected() > 0, stats.toString());
                var random = new Random(0x5EED);
                for (var seed : seeds) {
                    var parsed = TiffStructure.parse(seed);
                    var fields = new ArrayList<Long>();
                    for (var directory : parsed.directories().values()) {
                        for (var entry : directory.entries()) {
                            fields.add(entry.fieldPosition());
                            fields.add(entry.fieldPosition() - (parsed.big() ? 8 : 4));
                        }
                    }
                    for (var round = 0; round < 300; round++) {
                        var mutated = seed.clone();
                        var at = fields.get(random.nextInt(fields.size())).intValue();
                        var value = switch (random.nextInt(4)) {
                            case 0 -> random.nextInt(seed.length);
                            case 1 -> seed.length - 1 - random.nextInt(16);
                            case 2 -> -1;
                            default -> random.nextInt(1 << 16);
                        };
                        TiffStructure.write(mutated, at, 4, value & 0xFFFFFFFFL, parsed.little());
                        try {
                            ImageMetadata.read(ImageMetadataEditor.apply(mutated, edits.get(round % edits.size())));
                        } catch (MediaException expected) {
                            // rejected
                        }
                    }
                }
            });
        }
    }

    /**
     * Asserts that an edit kept every page after the first, every sub-directory of them, the image
     * data of every page and the layout entries of IFD0 where and as they were, and that every
     * byte outside the old IFD0, its metadata values and its Exif, GPS and interoperability
     * directories is unchanged.
     */
    static void assertOnlyMetadataChanged(byte[] original, byte[] edited) {
        var before = TiffStructure.parse(original);
        var after = TiffStructure.parse(edited);
        assertEquals(before.little(), after.little());
        assertEquals(before.big(), after.big());
        assertEquals(before.pages().size(), after.pages().size(), "page count");
        var allowed = new boolean[original.length];
        mark(allowed, before.big() ? 8 : 4, before.big() ? 8 : 4);
        var ifd0 = before.ifd0();
        mark(allowed, ifd0.offset(), ifd0.end() - ifd0.offset());
        for (var entry : ifd0.entries()) {
            if (!LAYOUT.contains(entry.tag()) && !entry.inline()) {
                mark(allowed, entry.valueOffset(), entry.length());
            }
        }
        for (var offset : before.directories().keySet()) {
            var role = before.roles().get(offset);
            if (role.equals("page 0/34665") || role.equals("page 0/34853") || role.equals("page 0/34665/40965")) {
                var directory = before.directories().get(offset);
                mark(allowed, directory.offset(), directory.end() - directory.offset());
                for (var entry : directory.entries()) {
                    if (!entry.inline()) {
                        mark(allowed, entry.valueOffset(), entry.length());
                    }
                }
            }
        }
        // zero bytes nothing references (padding, space an earlier edit freed) may be reused or cut off
        var referenced = new boolean[original.length];
        mark(referenced, 0, before.big() ? 16 : 8);
        for (var directory : before.directories().values()) {
            mark(referenced, directory.offset(), directory.end() - directory.offset());
            for (var entry : directory.entries()) {
                if (!entry.inline()) {
                    mark(referenced, entry.valueOffset(), entry.length());
                }
            }
            for (var pair : new int[][]{{273, 279}, {324, 325}}) {
                var offsets = directory.find(pair[0]);
                var counts = directory.find(pair[1]);
                if (offsets != null && counts != null) {
                    var starts = before.integers(offsets);
                    var lengths = before.integers(counts);
                    for (var i = 0; i < starts.length; i++) {
                        mark(referenced, starts[i], lengths[i]);
                    }
                }
            }
        }
        for (var i = 0; i < original.length; i++) {
            allowed[i] |= original[i] == 0 && !referenced[i];
        }
        for (var tag : LAYOUT) {
            var old = ifd0.find(tag);
            var now = after.ifd0().find(tag);
            if (old == null) {
                assertNull(now, "tag " + tag + " appeared");
                continue;
            }
            assertNotNull(now, "layout tag " + tag + " lost");
            assertEquals(old.type(), now.type());
            assertEquals(old.count(), now.count());
            assertArrayEquals(before.value(old), after.value(now), "layout tag " + tag);
            assertEquals(old.valueOffset(), now.valueOffset(), "layout tag " + tag + " moved");
        }
        for (var page = 0; page < before.pages().size(); page++) {
            assertArrayEquals(before.imageData(before.pages().get(page)), after.imageData(after.pages().get(page)),
                    "image data of page " + page);
        }
        for (var entry : before.directories().entrySet()) {
            var role = before.roles().get(entry.getKey());
            if (role.startsWith("page 0") && !role.startsWith("page 0/330")) {
                continue;
            }
            var directory = entry.getValue();
            assertEquals(directory, after.directories().get(entry.getKey()), role + " moved or changed");
        }
        var common = Math.min(original.length, edited.length);
        for (var i = 0; i < common; i++) {
            if (!allowed[i] && original[i] != edited[i]) {
                throw new AssertionError("byte " + i + " outside the metadata changed");
            }
        }
        for (var i = common; i < original.length; i++) {
            if (!allowed[i]) {
                throw new AssertionError("byte " + i + " outside the metadata was cut off");
            }
        }
    }

    /** Returns whether exiftool is installed; the checks through it are skipped otherwise. */
    static boolean hasExiftool() {
        return Oracle.find(Oracle.Tool.EXIFTOOL).isPresent();
    }

    private static void mark(boolean[] allowed, long start, long length) {
        for (var i = start; i < start + length && i < allowed.length; i++) {
            allowed[(int) i] = true;
        }
    }

    /** Asserts that every page of the edited file decodes to the pixels of the original, when Smalt decodes it. */
    static void assertPagesIdentical(byte[] original, byte[] edited) {
        List<short[]> expected;
        try {
            expected = pages(original);
        } catch (MediaException exception) {
            return;
        }
        var actual = pages(edited);
        assertEquals(expected.size(), actual.size(), "page count");
        for (var page = 0; page < expected.size(); page++) {
            assertArrayEquals(expected.get(page), actual.get(page), "pixels of page " + page);
        }
    }

    private static List<short[]> pages(byte[] tiff) {
        var result = new ArrayList<short[]>();
        try (var pages = TiffDecoder.openPages(MediaSource.of(tiff))) {
            for (var page = 0; page < pages.pageCount(); page++) {
                result.add(samples(pages.read(page).picture()));
            }
        }
        return result;
    }

    private static short[] samples(Picture picture) {
        var rgba = ImageOps.toRgba16(picture);
        var out = new short[rgba.width() * rgba.height() * 4 + 2];
        out[0] = (short) rgba.width();
        out[1] = (short) rgba.height();
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, 2 + y * rgba.width() * 4, rgba.width() * 4);
        }
        return out;
    }

    /**
     * Asserts that libtiff reads the edited file with no error the original lacks and decodes the
     * same samples from every page: tiffcp writes both uncompressed, and the image data of the
     * outputs are compared. Nothing is checked when the libtiff tools are not installed, so that
     * the callers' other checks still run.
     */
    static void assertLibtiffAgrees(byte[] original, byte[] edited) {
        if (Oracle.find("tiffinfo").isEmpty() || Oracle.find("tiffdump").isEmpty() || Oracle.find("tiffcp").isEmpty()) {
            return;
        }
        var before = libtiffErrors(original);
        var after = libtiffErrors(edited);
        assertTrue(before.containsAll(after), "libtiff errors after the edit: " + after + ", before: " + before);
        var plainBefore = tiffcp(original);
        var plainAfter = tiffcp(edited);
        if (plainBefore == null) {
            return;
        }
        assertNotNull(plainAfter, "tiffcp cannot read the edited file");
        var first = TiffStructure.parse(plainBefore);
        var second = TiffStructure.parse(plainAfter);
        assertEquals(first.pages().size(), second.pages().size());
        for (var page = 0; page < first.pages().size(); page++) {
            assertArrayEquals(first.imageData(first.pages().get(page)), second.imageData(second.pages().get(page)),
                    "libtiff samples of page " + page);
        }
    }

    private static List<String> libtiffErrors(byte[] tiff) {
        var file = MetadataSupport.save("libtiff-check.tif", tiff);
        var output = run("tiffinfo", List.of("-D", file.toString()));
        var dump = run("tiffdump", List.of(file.toString()));
        var result = new ArrayList<String>();
        for (var line : (output + "\n" + dump).split("\\R")) {
            var lower = line.toLowerCase();
            if (lower.contains("error") || lower.contains("cannot") || lower.contains("bogus")) {
                result.add(line.replace(file.toString(), "FILE"));
            }
        }
        return result;
    }

    private static byte[] tiffcp(byte[] tiff) {
        var input = MetadataSupport.save("tiffcp-in.tif", tiff);
        var output = MetadataSupport.DIRECTORY.resolve("tiffcp-out.tif").toAbsolutePath();
        try {
            Files.deleteIfExists(output);
            run("tiffcp", List.of("-c", "none", "-p", "contig", input.toString(), output.toString()));
            return Files.exists(output) ? Files.readAllBytes(output) : null;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (AssertionError exception) {
            return null;
        }
    }

    private static String run(String tool, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(Oracle.find(tool).orElseThrow().toString());
        command.addAll(arguments);
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new ByteArrayOutputStream();
            try (var input = process.getInputStream()) {
                input.transferTo(out);
            }
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError(tool + " timed out");
            }
            return out.toString(StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
