package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the descriptive tags the TIFF encoder writes: the resolution and its rational encoding,
 * the orientation, and EXIF (with its Exif, GPS and interoperability directories), XMP and IPTC
 * metadata, read back through Smalt's decoder and metadata reader, libtiff's tiffinfo and, when it
 * is on the path, exiftool.
 */
class TiffEncoderMetadataTest {
    static final ExifData EXIF = ExifData.EMPTY
            .with(ExifData.Directory.IFD0, 0x010F, ExifValue.ofAscii("Smalt Camera Works"))
            .with(ExifData.Directory.IFD0, 0x0110, ExifValue.ofAscii("Model 7"))
            .with(ExifData.Directory.IFD0, 0x0132, ExifValue.ofAscii("2026:10:20 12:34:56"))
            .with(ExifData.Directory.IFD0, 0x8298, ExifValue.ofAscii("(c) nobody"))
            // a structural entry, which the encoder writes itself
            .with(ExifData.Directory.IFD0, 0x0103, ExifValue.ofShort(7))
            .with(ExifData.Directory.EXIF, 0x829A, ExifValue.ofRational(1, 250))
            .with(ExifData.Directory.EXIF, 0x8827, ExifValue.ofShort(400))
            .with(ExifData.Directory.EXIF, 0x9003, ExifValue.ofAscii("2026:10:20 12:34:56"))
            .with(ExifData.Directory.EXIF, 0x9286, new ExifValue.Undefined("ASCII\0\0\0a long user comment"
                    .getBytes(StandardCharsets.ISO_8859_1)))
            .with(ExifData.Directory.INTEROP, 0x0001, ExifValue.ofAscii("R98"))
            .with(ExifData.Directory.GPS, 0x0001, ExifValue.ofAscii("N"))
            .with(ExifData.Directory.GPS, 0x0002, new ExifValue.Rationals(ExifValue.Type.RATIONAL,
                    new long[]{45, 30, 1234}, new long[]{1, 1, 100}))
            .with(ExifData.Directory.GPS, 0x0003, ExifValue.ofAscii("E"))
            .with(ExifData.Directory.GPS, 0x0004, new ExifValue.Rationals(ExifValue.Type.RATIONAL,
                    new long[]{9, 11, 0}, new long[]{1, 1, 1}));

    static byte[] encode(TiffEncoderOptions options) {
        return TiffEncoder.encode(TiffEncoderTest.picture(23, 11, false, false, 1), options);
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {
        @ParameterizedTest(name = "{0} x {1}")
        @CsvSource({"300, 300", "72.5, 96", "118.11, 0.5", "4294967295, 1", "1E-7, 1E12", "0.3333333333333333, 1"})
        @DisplayName("is written as the closest 32-bit rationals in inches")
        void dotsPerInch(double horizontal, double vertical) {
            var encoded = encode(TiffEncoderOptions.builder().resolution(horizontal, vertical).build());
            var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
            var resolution = page.resolution().orElseThrow();
            assertEquals(Math.min(horizontal, 0xFFFF_FFFFL), resolution.horizontal(), Math.max(horizontal * 1e-9, 1e-6));
            assertEquals(Math.min(vertical, 0xFFFF_FFFFL), resolution.vertical(),
                    vertical > 1e9 ? vertical * 1e-2 : Math.max(vertical * 1e-9, 1e-6));
            assertArrayEquals(new long[]{2}, TiffEncoderModesTest.tag(encoded, 296));
        }

        @Test
        @DisplayName("reduces exact fractions and defaults to 72 dots per inch")
        void fractions() {
            assertArrayEquals(new long[]{145, 2}, TiffEncoder.rational(72.5));
            assertArrayEquals(new long[]{300, 1}, TiffEncoder.rational(300));
            assertArrayEquals(new long[]{1, 3}, TiffEncoder.rational(1 / 3.0));
            // a positive value below 2^-32 stays positive
            assertArrayEquals(new long[]{1, 0xFFFF_FFFFL}, TiffEncoder.rational(1e-12));
            assertArrayEquals(new long[]{72, 1, 72, 1}, concat(TiffEncoderModesTest.tag(encode(TiffEncoderOptions.defaults()),
                    282), TiffEncoderModesTest.tag(encode(TiffEncoderOptions.defaults()), 283)));
        }

        @Test
        @DisplayName("comes from the EXIF metadata when the options give none, unit included")
        void fromExif() {
            var exif = EXIF.with(ExifData.Directory.IFD0, 0x011A, ExifValue.ofRational(118, 1))
                    .with(ExifData.Directory.IFD0, 0x011B, ExifValue.ofRational(59, 1))
                    .with(ExifData.Directory.IFD0, 0x0128, ExifValue.ofShort(3));
            var encoded = encode(TiffEncoderOptions.builder().exif(exif).build());
            assertArrayEquals(new long[]{3}, TiffEncoderModesTest.tag(encoded, 296));
            var resolution = TiffDecoder.openPages(MediaSource.of(encoded)).page(0).resolution().orElseThrow();
            assertEquals(118 * 2.54, resolution.horizontal(), 1e-9);
            assertEquals(59 * 2.54, resolution.vertical(), 1e-9);
            var overridden = encode(TiffEncoderOptions.builder().exif(exif).resolution(600, 600).build());
            assertArrayEquals(new long[]{2}, TiffEncoderModesTest.tag(overridden, 296));
            assertArrayEquals(new long[]{600, 1}, TiffEncoderModesTest.tag(overridden, 282));
        }

        @Test
        @DisplayName("is printed by tiffinfo")
        void libtiff() {
            LibTiff.assume("tiffinfo");
            var file = LibTiff.write("resolution.tif", encode(TiffEncoderOptions.builder().resolution(300, 150)
                    .orientation(Orientation.ROTATE_90).build()));
            var text = new String(LibTiff.run("tiffinfo", List.of(file.toString())), StandardCharsets.UTF_8);
            assertTrue(text.contains("Resolution: 300, 150 pixels/inch"), text);
            assertTrue(text.contains("Orientation: row 0 rhs, col 0 top"), text);
        }
    }

    static long[] concat(long[] a, long[] b) {
        var out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Nested
    @DisplayName("orientation")
    class Orient {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("is written as given and read back by the decoder")
        void written(Orientation orientation) {
            var encoded = encode(TiffEncoderOptions.builder().orientation(orientation).build());
            assertArrayEquals(new long[]{orientation.exifCode()}, TiffEncoderModesTest.tag(encoded, 274));
            assertEquals(orientation, TiffDecoder.decode(MediaSource.of(encoded)).orientation());
        }

        @Test
        @DisplayName("is absent by default and taken from the EXIF metadata when the options give none")
        void automatic() {
            assertNull(TiffEncoderModesTest.tag(encode(TiffEncoderOptions.defaults()), 274));
            var exif = EXIF.withOrientation(Orientation.ROTATE_270);
            var encoded = encode(TiffEncoderOptions.builder().exif(exif).build());
            assertEquals(Orientation.ROTATE_270, TiffDecoder.decode(MediaSource.of(encoded)).orientation());
            var overridden = encode(TiffEncoderOptions.builder().exif(exif).orientation(Orientation.FLIP_VERTICAL).build());
            assertEquals(Orientation.FLIP_VERTICAL, TiffDecoder.decode(MediaSource.of(overridden)).orientation());
        }
    }

    @Nested
    @DisplayName("embedded metadata")
    class Embedded {
        @ParameterizedTest(name = "big-endian {0}")
        @CsvSource({"false, false", "true, false", "false, true", "true, true"})
        @DisplayName("writes EXIF directories that the decoder and the metadata reader return")
        void exif(boolean bigEndian, boolean bigTiff) {
            var encoded = encode(TiffEncoderOptions.builder().exif(EXIF.withThumbnail(new byte[]{(byte) 0xFF, (byte) 0xD8,
                    (byte) 0xFF, (byte) 0xD9})).bigEndian(bigEndian).bigTiff(bigTiff).build());
            var decoded = TiffDecoder.decode(MediaSource.of(encoded));
            // the reader keeps the interoperability pointer the writer computes
            var exif = ExifReader.parse(decoded.exif().orElseThrow()).without(ExifData.Directory.EXIF,
                    ExifData.INTEROP_IFD_POINTER);
            assertEquals(EXIF.exif(), exif.exif());
            assertEquals(EXIF.gps(), exif.gps());
            assertEquals(EXIF.interop(), exif.interop());
            for (var tag : List.of(0x010F, 0x0110, 0x0132, 0x8298)) {
                assertEquals(EXIF.ifd0().get(tag), exif.ifd0().get(tag), Integer.toHexString(tag));
            }
            assertTrue(exif.thumbnail().isEmpty());
            var metadata = ImageMetadata.read(encoded);
            assertTrue(metadata.hasLocation());
            assertEquals(EXIF.gps(), metadata.exif().orElseThrow().gps());
            assertEquals(EXIF.interop(), metadata.exif().orElseThrow().interop());
            var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
            assertEquals("Smalt Camera Works", page.make().orElseThrow());
            assertEquals("Model 7", page.model().orElseThrow());
        }

        @Test
        @DisplayName("writes the XMP packet and the IPTC data, read back by the metadata reader")
        void xmpAndIptc() {
            var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "A page").with(XmpPacket.XMP, "Rating", "4");
            var iptc = IptcData.of(List.of(new IptcData.DataSet(2, 5, "Object".getBytes(StandardCharsets.UTF_8)),
                    new IptcData.DataSet(2, 120, "A caption of odd length".getBytes(StandardCharsets.UTF_8))));
            for (var bigEndian : new boolean[]{false, true}) {
                var encoded = encode(TiffEncoderOptions.builder().xmp(xmp).iptc(iptc).bigEndian(bigEndian).build());
                var metadata = ImageMetadata.read(encoded);
                assertEquals(xmp, metadata.xmp().orElseThrow());
                assertEquals(iptc, metadata.iptc().orElseThrow());
                assertEquals(xmp, XmpPacket.parse(TiffDecoder.decode(MediaSource.of(encoded)).xmp().orElseThrow()));
                assertNotNull(TiffEncoderModesTest.tag(encoded, 33723));
            }
            var none = encode(TiffEncoderOptions.builder().xmp(xmp).iptc(iptc).noXmp().noIptc().build());
            assertTrue(ImageMetadata.read(none).xmp().isEmpty());
            assertTrue(ImageMetadata.read(none).iptc().isEmpty());
        }

        static java.nio.file.Path exifFile() {
            return LibTiff.write("exif.tif", encode(TiffEncoderOptions.builder().exif(EXIF)
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "A page"))
                    .iptc(IptcData.of(List.of(new IptcData.DataSet(2, 5, "Object".getBytes(StandardCharsets.UTF_8)))))
                    .build()));
        }

        @Test
        @DisplayName("lists the EXIF and GPS directories in tiffinfo")
        void tiffinfo() {
            LibTiff.assume("tiffinfo");
            var file = exifFile();
            var info = new String(LibTiff.run("tiffinfo", List.of(file.toString())), StandardCharsets.UTF_8);
            assertTrue(info.contains("Make: Smalt Camera Works"), info);
            assertTrue(info.contains("EXIFIFDOffset") || info.contains("EXIF IFD"), info);
            assertTrue(info.contains("GPSIFDOffset") || info.contains("GPS IFD"), info);
        }

        @Test
        @DisplayName("writes metadata exiftool reads without a warning")
        void exiftool() {
            Oracle.assume("exiftool");
            var file = exifFile();
            var text = new String(Oracle.run("exiftool", Duration.ofMinutes(1), new byte[0],
                    List.of("-a", "-G1", "-n", "-s", file.toString())), StandardCharsets.UTF_8);
            assertTrue(text.matches("(?s).*\\[ExifIFD\\]\\s*ExposureTime\\s*:\\s*0\\.004.*"), text);
            assertTrue(text.matches("(?s).*\\[ExifIFD\\]\\s*ISO\\s*:\\s*400.*"), text);
            assertTrue(text.matches("(?s).*\\[GPS\\]\\s*GPSLatitude\\s*:\\s*45\\.50342.*"), text);
            assertTrue(text.matches("(?s).*\\[InteropIFD\\]\\s*InteropIndex\\s*:\\s*R98.*"), text);
            assertTrue(text.matches("(?s).*\\[XMP-dc\\]\\s*Title\\s*:\\s*A page.*"), text);
            assertTrue(text.matches("(?s).*\\[IPTC\\]\\s*ObjectName\\s*:\\s*Object.*"), text);
            assertTrue(text.matches("(?s).*\\[IFD0\\]\\s*Make\\s*:\\s*Smalt Camera Works.*"), text);
            assertFalse(text.contains("Warning"), text);
        }
    }
}
