package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads and edits the metadata of JPEG, PNG, WebP and GIF files, with exiftool as the
 * independent writer and reader, and checks that the compressed pixel data stays byte-identical.
 */
@DisplayName("Image metadata")
class ImageMetadataTest {
    /** The tags exiftool writes into the rich fixtures, location included. */
    static List<String> richTags(Path icc) {
        return List.of("-EXIF:Artist=Ada", "-EXIF:Make=SmaltCam", "-EXIF:Orientation#=6",
                "-EXIF:DateTimeOriginal=2024:05:01 12:00:00", "-GPSLatitude=45.4642", "-GPSLatitudeRef=N",
                "-GPSLongitude=9.19", "-GPSLongitudeRef=E", "-XMP-dc:Title=Duomo", "-XMP-dc:Subject=church",
                "-XMP-dc:Subject=milan", "-XMP-exif:GPSLatitude=45.4642 N", "-XMP-exif:GPSLongitude=9.19 E",
                "-XMP-photoshop:City=Milano", "-ICC_Profile<=" + icc);
    }

    static Path displayP3() {
        return MetadataSupport.save("display-p3.icc", ColorProfile.displayP3().toByteArray());
    }

    /** A format under test: its fixture, how to extract its pixel data, and what exiftool writes. */
    record Case(String name, Supplier<byte[]> image, Function<byte[], byte[]> pixels, List<String> extraTags,
                boolean exif, boolean icc, boolean iptc) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                new Case("jpeg", MetadataSupport::jpeg, MetadataSupport::jpegScan,
                        List.of("-IPTC:City=Milano", "-IPTC:Keywords=church", "-IPTC:Caption-Abstract=Caption", "-Comment=hello",
                                "-ThumbnailImage<=" + MetadataSupport.save("thumb.jpg", MetadataSupport.jpeg())),
                        true, true, true),
                new Case("png", MetadataSupport::png, MetadataSupport::pngImageData, List.of("-PNG:Comment=hello"), true, true,
                        false),
                new Case("webp lossy", MetadataSupport::webp, MetadataSupport::webpImageData, List.of(), true, true, false),
                new Case("webp lossless", MetadataSupport::webpLossless, MetadataSupport::webpImageData, List.of(), true, true,
                        false)
        ).map(Arguments::of);
    }

    static byte[] rich(Case format) {
        var tags = new ArrayList<>(richTags(displayP3()));
        if (!format.exif()) {
            tags.removeIf(tag -> tag.startsWith("-EXIF") || tag.startsWith("-GPS"));
        }
        if (!format.icc()) {
            tags.removeIf(tag -> tag.startsWith("-ICC"));
        }
        tags.addAll(format.extraTags());
        return MetadataSupport.exiftoolWrite("rich-" + format.name().replace(' ', '-') + "." + extension(format), format.image().get(),
                tags);
    }

    static String extension(Case format) {
        return format.name().startsWith("webp") ? "webp" : format.name().equals("jpeg") ? "jpg" : format.name();
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.ImageMetadataTest#formats")
        @DisplayName("reads what exiftool wrote")
        void readsExiftool(Case format) {
            var image = rich(format);
            var metadata = ImageMetadata.read(image);
            var exif = metadata.exif().orElseThrow();
            assertEquals(new ExifValue.Ascii("Ada"), exif.ifd0().get(0x013B));
            assertEquals(Orientation.ROTATE_90, metadata.orientation());
            assertTrue(exif.hasLocation());
            var xmp = metadata.xmp().orElseThrow();
            assertEquals("Duomo", xmp.text(XmpPacket.DC, "title").orElseThrow());
            assertEquals(List.of("church", "milan"), xmp.texts(XmpPacket.DC, "subject"));
            assertEquals("Milano", xmp.text(XmpPacket.PHOTOSHOP, "City").orElseThrow());
            assertTrue(metadata.colorProfile().orElseThrow().description().orElse("").contains("P3"));
            assertTrue(metadata.hasLocation());
            if (format.iptc()) {
                var iptc = metadata.iptc().orElseThrow();
                assertEquals("Milano", iptc.string(IptcData.Field.CITY).orElseThrow());
                assertEquals(List.of("church"), iptc.strings(IptcData.Field.KEYWORDS));
                assertTrue(exif.thumbnail().isPresent());
            }
            if (!format.name().startsWith("webp")) {
                assertEquals(List.of("hello"), metadata.comments());
            }
        }

        @Test
        @DisplayName("reads nothing from a file without metadata")
        void empty() {
            var metadata = ImageMetadata.read(MetadataSupport.png());
            assertEquals(ImageFormat.PNG, metadata.format());
            assertTrue(metadata.isEmpty());
            assertFalse(metadata.hasLocation());
        }
    }

    @Nested
    @DisplayName("editing")
    class Editing {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.ImageMetadataTest#formats")
        @DisplayName("strips the location and nothing else, keeping the pixels byte-identical")
        void stripLocation(Case format) {
            var image = rich(format);
            var edited = ImageMetadataEditor.stripLocation(image);
            assertArrayEquals(format.pixels().apply(image), format.pixels().apply(edited));
            var metadata = ImageMetadata.read(edited);
            assertFalse(metadata.hasLocation());
            assertEquals(new ExifValue.Ascii("Ada"), metadata.exif().orElseThrow().ifd0().get(0x013B));
            assertEquals("Duomo", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            var tags = MetadataSupport.exiftoolRead(edited, "located-" + format.name().replace(' ', '-') + "." + extension(format));
            assertTrue(tags.keySet().stream().noneMatch(key -> key.contains("GPS") || key.endsWith(":City")), tags.toString());
            assertEquals("Ada", tags.get("IFD0:Artist"));
            assertEquals("SmaltCam", tags.get("IFD0:Make"));
            var position = new String(edited, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(position.contains("Milano"), "a place name survived in the bytes");
            assertTrue(MetadataSupport.exiftoolWarnings(image, "located-before." + extension(format))
                    .containsAll(MetadataSupport.exiftoolWarnings(edited, "located-check." + extension(format))));
            Images.decode(MediaSource.of(edited));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.ImageMetadataTest#formats")
        @DisplayName("strips everything but the color profile, keeping the pixels byte-identical")
        void stripAll(Case format) {
            var image = rich(format);
            var edited = ImageMetadataEditor.stripAll(image);
            assertArrayEquals(format.pixels().apply(image), format.pixels().apply(edited));
            var metadata = ImageMetadata.read(edited);
            assertTrue(metadata.exifBytes().isEmpty());
            assertTrue(metadata.xmp().isEmpty());
            assertTrue(metadata.iptc().isEmpty());
            assertTrue(metadata.comments().isEmpty());
            assertArrayEquals(ImageMetadata.read(image).iccProfile().orElseThrow(), metadata.iccProfile().orElseThrow());
            var tags = MetadataSupport.exiftoolRead(edited, "stripped-" + format.name().replace(' ', '-') + "." + extension(format));
            assertTrue(tags.keySet().stream().noneMatch(key -> key.startsWith("IFD0:") || key.startsWith("GPS:")
                    || key.startsWith("XMP") || key.startsWith("IPTC:")), tags.toString());
            var withoutIcc = ImageMetadataEditor.apply(image, ImageMetadataEdit.stripAll().toBuilder()
                    .removeIccProfile().build());
            assertTrue(ImageMetadata.read(withoutIcc).isEmpty());
            assertArrayEquals(format.pixels().apply(image), format.pixels().apply(withoutIcc));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.ImageMetadataTest#formats")
        @DisplayName("adds metadata to a bare file that exiftool and ffmpeg then read")
        void addToBare(Case format) {
            var image = format.image().get();
            var exif = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Grace"))
                    .withOrientation(Orientation.ROTATE_180);
            var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Added").with(XmpPacket.XMP, "Rating", "4");
            var edit = ImageMetadataEdit.keepAll().toBuilder().exif(exif).xmp(xmp)
                    .colorProfile(ColorProfile.displayP3()).build();
            var edited = ImageMetadataEditor.apply(image, edit);
            assertArrayEquals(format.pixels().apply(image), format.pixels().apply(edited));
            var metadata = ImageMetadata.read(edited);
            assertEquals(Orientation.ROTATE_180, metadata.orientation());
            assertEquals("Added", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            var tags = MetadataSupport.exiftoolRead(edited, "added-" + format.name().replace(' ', '-') + "." + extension(format));
            assertEquals("Grace", tags.get("IFD0:Artist"), tags.toString());
            assertEquals("3", tags.get("IFD0:Orientation"));
            assertEquals("Added", tags.get("XMP-dc:Title"));
            assertEquals("4", tags.get("XMP-xmp:Rating"));
            assertTrue(tags.getOrDefault("ICC-header:ProfileDescription", tags.getOrDefault("ICC_Profile:ProfileDescription", ""))
                    .contains("P3") || tags.values().stream().anyMatch(value -> value.contains("Display P3")), tags.toString());
            // a minimal EXIF block lacks the entries the EXIF specification requires of cameras
            var warnings = MetadataSupport.exiftoolWarnings(edited, "added-check." + extension(format));
            warnings.removeIf(warning -> warning.contains("Missing required"));
            assertTrue(warnings.isEmpty(), warnings.toString());
            Oracle.assume(Oracle.Tool.FFMPEG);
            Oracle.assertDecodesCleanly(MetadataSupport.save("added-ffmpeg." + extension(format), edited));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.ImageMetadataTest#formats")
        @DisplayName("changes the orientation in EXIF and XMP")
        void orientation(Case format) {
            var image = MetadataSupport.exiftoolWrite("oriented." + extension(format), format.image().get(),
                    List.of("-EXIF:Orientation#=6", "-XMP-tiff:Orientation#=6"));
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .orientation(Orientation.ROTATE_270).build());
            var tags = MetadataSupport.exiftoolRead(edited, "reoriented." + extension(format));
            assertEquals("8", tags.get("IFD0:Orientation"));
            assertEquals("8", tags.get("XMP-tiff:Orientation"));
            assertEquals(Orientation.ROTATE_270, Images.decode(MediaSource.of(edited)).orientation());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.metadata.ImageMetadataTest#formats")
        @DisplayName("keeps every byte when the edit changes nothing")
        void identity(Case format) {
            var image = rich(format);
            assertSame(image, ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll()));
            assertArrayEquals(image, ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .removeLocation(true)
                    .removeLocation(true).build().toBuilder().removeLocation(false).build()));
        }
    }

    @Nested
    @DisplayName("JPEG")
    class Jpeg {
        @Test
        @DisplayName("splits a large XMP packet into Extended XMP and reads it back merged")
        void extendedXmp() {
            var large = "x".repeat(100_000);
            var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Big").with(XmpPacket.DC, "description", large);
            var edited = ImageMetadataEditor.apply(MetadataSupport.jpeg(), ImageMetadataEdit.keepAll().toBuilder()
                    .xmp(xmp).build());
            var read = ImageMetadata.read(edited).xmp().orElseThrow();
            assertEquals("Big", read.text(XmpPacket.DC, "title").orElseThrow());
            assertEquals(large, read.text(XmpPacket.DC, "description").orElseThrow());
            var tags = MetadataSupport.exiftoolRead(edited, "extended.jpg");
            assertEquals("Big", tags.get("XMP-dc:Title"));
            assertTrue(tags.containsKey("XMP-xmpNote:HasExtendedXMP"), tags.toString());
            assertEquals(large.length(), tags.get("XMP-dc:Description").length());
        }

        @Test
        @DisplayName("splits a large ICC profile into several APP2 segments")
        void largeIcc() {
            var profile = new byte[150_000];
            System.arraycopy(ColorProfile.displayP3().toByteArray(), 0, profile, 0, ColorProfile.displayP3().toByteArray().length);
            var edited = ImageMetadataEditor.apply(MetadataSupport.jpeg(), ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.parse(profile)).build());
            assertArrayEquals(profile, ImageMetadata.read(edited).iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("writes IPTC data, keeping the other Photoshop resources, with a fresh digest")
        void iptc() {
            var image = MetadataSupport.exiftoolWrite("iptc.jpg", MetadataSupport.jpeg(), List.of("-IPTC:City=Milano",
                    "-IPTC:Keywords=church"));
            var resolution = new PhotoshopResources.Resource("8BIM", 0x03ED, new byte[0], new byte[16]);
            var resources = new java.util.ArrayList<>(List.of(resolution));
            resources.addAll(PhotoshopResources.withIptc(List.of(), ImageMetadata.read(image).iptc().orElseThrow()));
            resources.add(new PhotoshopResources.Resource("8BIM", PhotoshopResources.IPTC_DIGEST, new byte[0], new byte[16]));
            var payload = PhotoshopResources.encode(resources);
            var segment = JpegFile.segment(0xED, JpegFile.PHOTOSHOP_ID, payload, 0, payload.length);
            var stripped = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder().removeIptc()
                    .build());
            var withResources = new byte[stripped.length + segment.length];
            System.arraycopy(stripped, 0, withResources, 0, 2);
            System.arraycopy(segment, 0, withResources, 2, segment.length);
            System.arraycopy(stripped, 2, withResources, 2 + segment.length, stripped.length - 2);
            var iptc = ImageMetadata.read(withResources).iptc().orElseThrow().without(IptcData.Field.KEYWORDS);
            var edited = ImageMetadataEditor.apply(withResources, ImageMetadataEdit.keepAll().toBuilder().iptc(iptc)
                    .build());
            var tags = MetadataSupport.exiftoolRead(edited, "iptc-edited.jpg");
            assertEquals("Milano", tags.get("IPTC:City"));
            assertFalse(tags.containsKey("IPTC:Keywords"));
            assertEquals(tags.get("File:CurrentIPTCDigest"), tags.get("Photoshop:IPTCDigest"));
            assertEquals(List.of(0x03ED, PhotoshopResources.IPTC, PhotoshopResources.IPTC_DIGEST), resourceIds(edited));
            var removed = ImageMetadataEditor.apply(edited, ImageMetadataEdit.keepAll().toBuilder().removeIptc()
                    .build());
            assertTrue(ImageMetadata.read(removed).iptc().isEmpty());
            assertEquals(List.of(0x03ED), resourceIds(removed));
            assertEquals(List.of(), resourceIds(ImageMetadataEditor.stripAll(edited)));
        }

        /** Returns the identifiers of the Photoshop resources of a JPEG file's APP13 segment. */
        static List<Integer> resourceIds(byte[] jpeg) {
            var position = 2;
            while (position + 4 <= jpeg.length && (jpeg[position + 1] & 0xFF) != 0xDA) {
                var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
                if ((jpeg[position + 1] & 0xFF) == 0xED) {
                    return PhotoshopResources.parse(jpeg, position + 4 + JpegFile.PHOTOSHOP_ID.length, position + 2 + length)
                            .stream().map(PhotoshopResources.Resource::id).toList();
                }
                position += 2 + length;
            }
            return List.of();
        }

        @Test
        @DisplayName("keeps the secondary images of a Multi-Picture file reachable")
        void multiPicture() {
            var mpf = MultiPictureFixture.build(MetadataSupport.jpeg(), MetadataSupport.jpeg());
            assertTrue(MultiPictureFixture.secondaryIsReachable(mpf));
            var edited = ImageMetadataEditor.apply(mpf, ImageMetadataEdit.keepAll()
                    .toBuilder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "x".repeat(5000)))
                    .comments(List.of("added")).build());
            assertTrue(MultiPictureFixture.secondaryIsReachable(edited));
            assertFalse(MultiPictureFixture.secondaryIsReachable(MultiPictureFixture.withSegmentOf(edited, mpf)));
            var stripped = ImageMetadataEditor.stripAll(edited);
            assertTrue(MultiPictureFixture.secondaryIsReachable(stripped));
        }

        @Test
        @DisplayName("edits a file on disk in place of the original")
        void file(@TempDir Path directory) throws Exception {
            var file = directory.resolve("photo.jpg");
            Files.write(file, rich(new Case("jpeg", MetadataSupport::jpeg, MetadataSupport::jpegScan,
                    List.of("-IPTC:City=Milano"), true, true, true)));
            var before = Files.readAllBytes(file);
            ImageMetadataEditor.apply(file, ImageMetadataEdit.stripLocation());
            var after = Files.readAllBytes(file);
            assertArrayEquals(MetadataSupport.jpegScan(before), MetadataSupport.jpegScan(after));
            assertFalse(ImageMetadata.read(file).hasLocation());
            var sink = MediaSink.memory();
            ImageMetadataEditor.apply(MediaSource.of(before), sink, ImageMetadataEdit.stripLocation());
            assertArrayEquals(after, sink.toByteArray());
        }
    }

    @Nested
    @DisplayName("PNG")
    class Png {
        @Test
        @DisplayName("writes and replaces text and comments")
        void text() {
            var edited = ImageMetadataEditor.apply(MetadataSupport.png(), ImageMetadataEdit.keepAll()
                    .toBuilder().text(Map.of("Title", "T\u00EBst", "Author", "\u4E2D\u6587"))
                            .comments(List.of("one", "two")).build());
            var metadata = ImageMetadata.read(edited);
            assertEquals(Map.of("Title", "T\u00EBst", "Author", "\u4E2D\u6587"), metadata.text());
            assertEquals(List.of("one", "two"), metadata.comments());
            var tags = MetadataSupport.exiftoolRead(edited, "text.png");
            assertEquals("T\u00EBst", tags.get("PNG:Title"));
            assertEquals("\u4E2D\u6587", tags.get("PNG:Author"));
        }

        @Test
        @DisplayName("puts the profile before PLTE and drops a contradicting sRGB chunk")
        void palette() {
            var image = com.github.auties00.smalt.testkit.Fixtures.read(com.github.auties00.smalt.testkit.Fixtures.testImage("png",
                    32, 32, List.of("-pix_fmt", "pal8")));
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.displayP3()).build());
            assertEquals(Images.decode(MediaSource.of(image)).picture().width(), Images.decode(MediaSource.of(edited)).picture().width());
            var text = new String(edited, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(text.indexOf("iCCP") < text.indexOf("PLTE"));
            assertFalse(text.contains("sRGB"));
            Oracle.assume(Oracle.Tool.FFMPEG);
            Oracle.assertDecodesCleanly(MetadataSupport.save("palette.png", edited));
        }
    }

    @Nested
    @DisplayName("GIF")
    class Gif {
        @Test
        @DisplayName("writes XMP and comments that exiftool reads, keeping the image blocks")
        void xmpAndComments() {
            var image = MetadataSupport.gif();
            var edited = ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll()
                    .toBuilder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Gif title"))
                            .comments(List.of("a comment")).build());
            assertArrayEquals(MetadataSupport.gifImageData(image), MetadataSupport.gifImageData(edited));
            var metadata = ImageMetadata.read(edited);
            assertEquals("Gif title", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertEquals(List.of("a comment"), metadata.comments());
            var tags = MetadataSupport.exiftoolRead(edited, "meta.gif");
            assertEquals("Gif title", tags.get("XMP-dc:Title"), tags.toString());
            assertEquals("a comment", tags.get("File:Comment"));
            var stripped = ImageMetadataEditor.stripAll(edited);
            assertTrue(ImageMetadata.read(stripped).isEmpty());
            assertArrayEquals(MetadataSupport.gifImageData(image), MetadataSupport.gifImageData(stripped));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(image,
                    ImageMetadataEdit.keepAll().toBuilder().exif(ExifData.EMPTY.withOrientation(Orientation.ROTATE_90))
                            .build()));
        }
    }

    @Nested
    @DisplayName("HEIF and TIFF")
    class HeifAndTiff {
        @Test
        @DisplayName("reads and strips the metadata of a TIFF file written by ffmpeg and exiftool")
        void tiff() {
            var image = MetadataSupport.exiftoolWrite("meta.tif", com.github.auties00.smalt.testkit.Fixtures.read(
                    com.github.auties00.smalt.testkit.Fixtures.testImage("tif", 32, 32, List.of())),
                    List.of("-EXIF:Artist=Tiff Artist", "-XMP-dc:Title=Tiff title", "-IPTC:City=Roma"));
            var metadata = ImageMetadata.read(image);
            assertEquals(new ExifValue.Ascii("Tiff Artist"), metadata.exif().orElseThrow().ifd0().get(0x013B));
            assertEquals("Tiff title", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertEquals("Roma", metadata.iptc().orElseThrow().string(IptcData.Field.CITY).orElseThrow());
            assertSame(image, ImageMetadataEditor.apply(image, ImageMetadataEdit.keepAll()));
            var stripped = ImageMetadataEditor.stripAll(image);
            var bare = ImageMetadata.read(stripped);
            assertTrue(bare.xmp().isEmpty() && bare.iptc().isEmpty());
            assertFalse(bare.exif().map(exif -> exif.ifd0().containsKey(0x013B)).orElse(false));
            TiffMetadataTest.assertOnlyMetadataChanged(image, stripped);
            TiffMetadataTest.assertPagesIdentical(image, stripped);
            var tags = MetadataSupport.exiftoolRead(stripped, "meta-stripped.tif");
            assertNull(tags.get("IFD0:Artist"), tags.toString());
            assertNull(tags.get("XMP-dc:Title"));
            assertNull(tags.get("IPTC:City"));
        }

        @Test
        @DisplayName("reads and edits the EXIF and XMP items of an AVIF file, keeping the image item")
        void avif() {
            var avif = com.github.auties00.smalt.testkit.Fixtures.read(com.github.auties00.smalt.testkit.Fixtures.testImage(
                    "avif", 64, 64, List.of("-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8")));
            var image = MetadataSupport.exiftoolWrite("meta.avif", avif, List.of("-EXIF:Artist=Avif Artist",
                    "-XMP-dc:Title=Avif title", "-GPSLatitude=45.4642", "-GPSLatitudeRef=N", "-XMP-photoshop:City=Milano"));
            var metadata = ImageMetadata.read(image);
            assertEquals(ImageFormat.AVIF, metadata.format());
            assertEquals(new ExifValue.Ascii("Avif Artist"), metadata.exif().orElseThrow().ifd0().get(0x013B));
            assertEquals("Avif title", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertTrue(metadata.hasLocation());
            var located = ImageMetadataEditor.stripLocation(image);
            assertArrayEquals(primaryItem(image), primaryItem(located));
            var edited = ImageMetadata.read(located);
            assertFalse(edited.hasLocation());
            assertEquals("Avif title", edited.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            var tags = MetadataSupport.exiftoolRead(located, "located.avif");
            assertTrue(tags.keySet().stream().noneMatch(key -> key.contains("GPS") || key.endsWith(":City")), tags.toString());
            assertEquals("Avif Artist", tags.get("IFD0:Artist"));
            assertFalse(new String(located, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Milano"));
            assertTrue(MetadataSupport.exiftoolWarnings(image, "avif-before.avif")
                    .containsAll(MetadataSupport.exiftoolWarnings(located, "avif-after.avif")));
            Oracle.assertDecodesCleanly(MetadataSupport.save("located-ffmpeg.avif", located));
            var stripped = ImageMetadataEditor.stripAll(image);
            assertArrayEquals(primaryItem(image), primaryItem(stripped));
            assertTrue(ImageMetadata.read(stripped).exifBytes().isEmpty());
            assertTrue(ImageMetadata.read(stripped).xmp().isEmpty());
            var added = ImageMetadataEditor.apply(avif, ImageMetadataEdit.keepAll()
                    .toBuilder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Fresh")).build());
            assertEquals("Fresh", MetadataSupport.exiftoolRead(added, "added.avif").get("XMP-dc:Title"));
            assertArrayEquals(primaryItem(avif), primaryItem(added));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(image,
                    ImageMetadataEdit.keepAll().toBuilder().removeIccProfile().build()));
        }

        static byte[] primaryItem(byte[] heif) {
            var source = MediaSource.of(heif);
            var file = com.github.auties00.smalt.internal.heif.HeifFile.parse(source, com.github.auties00.smalt.MediaLimits.DEFAULT);
            return file.readItem(source, file.primaryItem(), com.github.auties00.smalt.MediaLimits.DEFAULT);
        }
    }

    @Test
    @DisplayName("XMP values the editor writes keep their RDF types")
    void xmpTypes() {
        var xmp = XmpPacket.empty().with(XmpPacket.DC, "creator", XmpValue.ordered("A", "B"));
        var edited = ImageMetadataEditor.apply(MetadataSupport.jpeg(), ImageMetadataEdit.keepAll().toBuilder().xmp(xmp)
                .build());
        assertEquals(List.of("A", "B"), ImageMetadata.read(edited).xmp().orElseThrow().texts(XmpPacket.DC, "creator"));
        assertNull(ImageMetadata.read(MetadataSupport.jpeg()).xmp().orElse(null));
    }

    @Nested
    @DisplayName("JPEG segments")
    class JpegSegments {
        /** Inserts segments after the start-of-image marker of a JPEG file. */
        private static byte[] insert(byte[] jpeg, byte[]... segments) {
            var out = new java.io.ByteArrayOutputStream();
            out.write(jpeg, 0, 2);
            for (var segment : segments) {
                out.writeBytes(segment);
            }
            out.write(jpeg, 2, jpeg.length - 2);
            return out.toByteArray();
        }

        /** Builds an application segment from its marker and payload. */
        private static byte[] segment(int marker, byte[] payload) {
            var segment = new byte[4 + payload.length];
            segment[0] = (byte) 0xFF;
            segment[1] = (byte) marker;
            segment[2] = (byte) ((payload.length + 2) >> 8);
            segment[3] = (byte) (payload.length + 2);
            System.arraycopy(payload, 0, segment, 4, payload.length);
            return segment;
        }

        /** Builds an {@code APP2 ICC_PROFILE} segment. */
        private static byte[] icc(int sequence, int count, byte[] chunk) {
            var payload = new byte[14 + chunk.length];
            System.arraycopy("ICC_PROFILE\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, payload, 0, 12);
            payload[12] = (byte) sequence;
            payload[13] = (byte) count;
            System.arraycopy(chunk, 0, payload, 14, chunk.length);
            return segment(0xE2, payload);
        }

        @Test
        @DisplayName("an ICC profile with a missing or repeated chunk is absent, as the decoder reads it")
        void incompleteProfile() {
            var plain = com.github.auties00.smalt.image.jpeg.JpegEncoder.encode(
                    com.github.auties00.smalt.image.RgbaPicture.allocate(8, 8, false));
            var profile = ColorProfile.srgb().toByteArray();
            var half = profile.length / 2;
            var first = java.util.Arrays.copyOf(profile, half);
            var second = java.util.Arrays.copyOfRange(profile, half, profile.length);
            for (var file : List.of(insert(plain, icc(1, 2, first)), insert(plain, icc(1, 2, first), icc(1, 2, second)),
                    insert(plain, icc(1, 2, first), icc(2, 3, second)))) {
                assertTrue(ImageMetadata.read(file).iccProfile().isEmpty());
                assertTrue(com.github.auties00.smalt.image.jpeg.JpegDecoder.decode(MediaSource.of(file)).iccProfile()
                        .isEmpty());
            }
            var whole = insert(plain, icc(2, 2, second), icc(1, 2, first));
            assertArrayEquals(profile, ImageMetadata.read(whole).iccProfile().orElseThrow());
        }

        @ParameterizedTest(name = "pad byte {0}")
        @org.junit.jupiter.params.provider.ValueSource(ints = {0x00, 0xFF})
        @DisplayName("every JPEG reader takes the EXIF identifier with either pad byte")
        void exifIdentifier(int pad) {
            var plain = com.github.auties00.smalt.image.jpeg.JpegEncoder.encode(
                    com.github.auties00.smalt.image.RgbaPicture.allocate(8, 8, false));
            var tiff = com.github.auties00.smalt.image.exif.ExifWriter.write(new ExifData(Map.of(ExifData.ORIENTATION,
                    ExifValue.ofShort(6)), Map.of(), Map.of()));
            var payload = new byte[6 + tiff.length];
            System.arraycopy("Exif".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, payload, 0, 4);
            payload[5] = (byte) pad;
            System.arraycopy(tiff, 0, payload, 6, tiff.length);
            var file = insert(plain, segment(0xE1, payload));
            assertEquals(Orientation.ROTATE_90, ImageMetadata.read(file).orientation());
            assertEquals(Orientation.ROTATE_90, com.github.auties00.smalt.image.jpeg.JpegDecoder.probe(
                    MediaSource.of(file)).orientation());
            assertEquals(Orientation.ROTATE_90, com.github.auties00.smalt.image.jpeg.JpegDecoder.decode(
                    MediaSource.of(file)).orientation());
            var upright = com.github.auties00.smalt.image.jpeg.JpegTransforms.autoOrient(MediaSource.of(file));
            assertEquals(Orientation.NORMAL, ImageMetadata.read(upright).orientation());
        }

        /** Builds an {@code APP1} Extended XMP segment holding one chunk of a packet. */
        private static byte[] extendedXmp(String guid, byte[] packet, int offset, int length) {
            var id = "http://ns.adobe.com/xmp/extension/\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            var payload = java.nio.ByteBuffer.allocate(id.length + 40 + length);
            payload.put(id).put(guid.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)).putInt(packet.length)
                    .putInt(offset).put(packet, offset, length);
            return segment(0xE1, payload.array());
        }

        @Test
        @DisplayName("Extended XMP is merged only when its chunks cover the whole packet")
        void extendedXmpCoverage() {
            var plain = com.github.auties00.smalt.image.jpeg.JpegEncoder.encode(
                    com.github.auties00.smalt.image.RgbaPicture.allocate(8, 8, false));
            var guid = "0123456789ABCDEF0123456789ABCDEF";
            var id = "http://ns.adobe.com/xap/1.0/\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            var main = XmpPacket.empty().with(XmpPacket.DC, "title", "main")
                    .with(XmpPacket.XMP_NOTE, "HasExtendedXMP", guid).toBytes();
            var mainSegment = segment(0xE1, java.nio.ByteBuffer.allocate(id.length + main.length).put(id).put(main)
                    .array());
            var body = XmpPacket.empty().with(XmpPacket.DC, "creator", "extended").toBytes();
            var packet = java.util.Arrays.copyOf(body, body.length + 100);
            java.util.Arrays.fill(packet, body.length, packet.length, (byte) ' ');
            var half = packet.length / 2;
            var complete = insert(plain, mainSegment, extendedXmp(guid, packet, half, packet.length - half),
                    extendedXmp(guid, packet, 0, half));
            assertTrue(ImageMetadata.read(complete).xmp().orElseThrow().text(XmpPacket.DC, "creator").isPresent());
            var head = packet.length - 100;
            var repeated = insert(plain, mainSegment, extendedXmp(guid, packet, 0, head),
                    extendedXmp(guid, packet, 0, head));
            var xmp = ImageMetadata.read(repeated).xmp().orElseThrow();
            assertEquals("main", xmp.text(XmpPacket.DC, "title").orElseThrow());
            assertTrue(xmp.text(XmpPacket.DC, "creator").isEmpty(), "a packet with a hole was merged");
        }
    }
}
