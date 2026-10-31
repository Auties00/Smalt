package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.AuxiliaryImageType;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapChannel;
import com.github.auties00.smalt.image.gainmap.GainMapComputeOptions;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.image.jpeg.internal.JpegContainer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers JPEG gain maps: the Adobe gain map JPEG files of libavif's test data (Photoshop and Camera Raw, with
 * big- and little-endian Multi-Picture Format indexes and per-channel XMP metadata), Ultra HDR files written by
 * {@link JpegEncoder} and read back (structure, metadata, the auxiliary image listing and decoding), and damaged
 * files. The libultrahdr comparisons are in {@link JpegGainMapDifferentialTest}.
 */
@DisplayName("JPEG gain maps")
class JpegGainMapTest {
    static byte[] vector(String name) {
        try {
            return Files.readAllBytes(Conformance.fetchListed(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A smooth sRGB picture with saturated and dark regions. */
    static RgbaPicture sdr(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (255 * x / (width - 1));
                pixels[at + 1] = (byte) (255 * y / (height - 1));
                pixels[at + 2] = (byte) (128 + 100 * Math.sin(x * 0.2 + y * 0.1));
                pixels[at + 3] = (byte) 0xFF;
            }
        }
        return picture;
    }

    static double srgb(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double pqToNits(double signal) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var p = Math.pow(signal, 1 / m2);
        return Math.pow(Math.max(p - c1, 0) / (c2 - c3 * p), 1 / m1) * 10000;
    }

    static double pq(double nits) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var p = Math.pow(Math.min(Math.max(nits / 10000, 0), 1), m1);
        return Math.pow((c1 + c2 * p) / (1 + c3 * p), m2);
    }

    /** A PQ rendition of a picture brightened toward its center by up to 8 times. */
    static DecodedImage hdr(RgbaPicture base) {
        var picture = Rgba16Picture.allocate(base.width(), base.height(), false);
        for (var y = 0; y < base.height(); y++) {
            for (var x = 0; x < base.width(); x++) {
                var dx = (x - base.width() / 2.0) / base.width();
                var dy = (y - base.height() / 2.0) / base.height();
                var boost = 1 + 7 * Math.exp(-(dx * dx + dy * dy) * 8);
                for (var c = 0; c < 3; c++) {
                    var linear = srgb((base.pixels()[y * base.stride() + x * 4 + c] & 0xFF) / 255.0) * boost;
                    picture.pixels()[y * picture.stride() + x * 4 + c] = (short) Math.round(pq(linear * 203) * 65535);
                }
                picture.pixels()[y * picture.stride() + x * 4 + 3] = (short) 0xFFFF;
            }
        }
        return image(picture, Optional.of(ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY,
                ColorRange.FULL, ColorPrimaries.BT709, TransferCharacteristics.PQ))));
    }

    static DecodedImage image(Picture picture, Optional<ColorProfile> profile) {
        return new DecodedImage(picture, ImageFormat.JPEG, Orientation.NORMAL, picture.width(), picture.height(),
                Optional.empty(), Optional.empty(), Optional.empty(), profile);
    }

    static byte[] ultraHdr(RgbaPicture base, GainMapComputeOptions options) {
        var gainMap = GainMaps.compute(image(base, Optional.empty()), hdr(base), options);
        return JpegEncoder.encode(base, gainMap, JpegEncoderOptions.builder().quality(95)
                .chromaFormat(ChromaFormat.YUV444).build(), JpegEncoderOptions.ofQuality(95));
    }

    @Nested
    @DisplayName("Adobe gain map files")
    class Adobe {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"libavif-paris_exif_xmp_gainmap_bigendian.jpg",
                "libavif-paris_exif_xmp_gainmap_littleendian.jpg", "libavif-paris_exif_xmp_icc_gainmap_bigendian.jpg"})
        @DisplayName("reads the Photoshop gain map of either index byte order")
        void paris(String name) {
            var data = vector(name);
            var gainMap = JpegDecoder.decodeGainMap(MediaSource.of(data)).orElseThrow();
            var metadata = gainMap.metadata();
            assertTrue(metadata.isMultichannel());
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(3.5, metadata.alternateHeadroom());
            assertTrue(metadata.useBaseColorSpace());
            assertEquals(new GainMapChannel(0, 3.5, 1, 0, 0), metadata.channel(0));
            assertEquals(new GainMapChannel(0, 3.6, 1, 0, 0), metadata.channel(1));
            assertEquals(new GainMapChannel(0, 3.7, 1, 0, 0), metadata.channel(2));
            var image = assertInstanceOf(YuvPicture.class, gainMap.image());
            assertEquals(ChromaFormat.MONOCHROME, image.chromaFormat());
            assertEquals(512, image.width());
            assertEquals(384, image.height());
            assertTrue(gainMap.alternateProfile().isEmpty());
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(data));
            assertEquals(1, listed.size());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getFirst().type());
            assertEquals("mpf:000000/GainMap", listed.getFirst().identifier());
            assertEquals(512, listed.getFirst().width());
            var decoded = JpegDecoder.decodeAuxiliaryImage(MediaSource.of(data), 0);
            var plane = ((YuvPicture) decoded.picture()).y();
            assertArrayEquals(Arrays.copyOf(image.y(), 512), Arrays.copyOf(plane, 512));
            var base = JpegDecoder.decode(MediaSource.of(data));
            var rendered = GainMaps.apply(base, gainMap);
            assertEquals(base.picture().width(), rendered.picture().width());
        }

        @Test
        @DisplayName("reads the Camera Raw gain map as libavif converts it to AVIF")
        void seine() {
            var data = vector("libavif-seine_sdr_gainmap_srgb.jpg");
            var metadata = JpegDecoder.decodeGainMap(MediaSource.of(data)).orElseThrow().metadata();
            assertTrue(metadata.isMultichannel());
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(1.3, metadata.alternateHeadroom());
            assertEquals(new GainMapChannel(-0.256907, 1.277177, 0.953784, 0.015625, 0.015625), metadata.channel(0));
            assertEquals(new GainMapChannel(-0.261365, 1.277203, 0.941095, 0.015625, 0.015625), metadata.channel(1));
            assertEquals(new GainMapChannel(-0.280284, 1.277969, 0.919422, 0.015625, 0.015625), metadata.channel(2));
            // the EXIF and Photoshop thumbnails are inside segments, so only the index's gain map is listed
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(data));
            assertEquals(1, listed.size());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getFirst().type());
        }
    }

    @Nested
    @DisplayName("Ultra HDR writing")
    class Writing {
        @ParameterizedTest(name = "multichannel {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("writes a file whose gain map reads back")
        void roundTrip(boolean multichannel) {
            var base = sdr(96, 64);
            var gainMap = GainMaps.compute(image(base, Optional.empty()), hdr(base),
                    GainMapComputeOptions.builder().multichannel(multichannel).build());
            var options = JpegEncoderOptions.builder().quality(95).chromaFormat(ChromaFormat.YUV444).build();
            var file = JpegEncoder.encode(base, gainMap, options, JpegEncoderOptions.ofQuality(95));
            var primary = JpegDecoder.decode(MediaSource.of(file));
            var plain = JpegDecoder.decode(MediaSource.of(JpegEncoder.encode(base, options)));
            assertArrayEquals(((YuvPicture) plain.picture()).y(), ((YuvPicture) primary.picture()).y());
            var read = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            assertMetadata(gainMap.metadata(), read.metadata());
            assertEquals(gainMap.image().width(), read.image().width());
            var gray = read.image() instanceof YuvPicture yuv && yuv.chromaFormat() == ChromaFormat.MONOCHROME;
            assertEquals(!multichannel, gray);
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(file));
            assertEquals(1, listed.size());
            assertEquals("mpf:000000/GainMap", listed.getFirst().identifier());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getFirst().type());
            var rendered = (Rgba16Picture) GainMaps.apply(primary, read, GainMapOptions.builder()
                    .primaries(ColorPrimaries.BT709).build()).picture();
            var reference = (Rgba16Picture) GainMaps.apply(image(base, Optional.empty()), gainMap, GainMapOptions
                    .builder().primaries(ColorPrimaries.BT709).build()).picture();
            // the rendering of the source images, against that of the coded ones as display light: JPEG coding of
            // both images keeps it within 10% and 2 candelas, 20% for a color gain map, whose channels go through
            // Y'CbCr
            var relative = multichannel ? 0.2 : 0.1;
            for (var i = 0; i < reference.pixels().length; i++) {
                if (i % 4 == 3) {
                    continue;
                }
                var referenceNits = pqToNits((reference.pixels()[i] & 0xFFFF) / 65535.0);
                var renderedNits = pqToNits((rendered.pixels()[i] & 0xFFFF) / 65535.0);
                assertTrue(Math.abs(renderedNits - referenceNits) <= relative * referenceNits + 2, "sample " + i + ": "
                        + referenceNits + " vs " + renderedNits + " cd/m2");
            }
        }

        @Test
        @DisplayName("lays out the Multi-Picture Format index and the XMP container as libultrahdr does")
        void structure() {
            var file = ultraHdr(sdr(64, 48), GainMapComputeOptions.defaults());
            var headers = JpegContainer.headers(file, 0, file.length);
            assertEquals(0xE0, headers.get(0).marker());
            var xmp = JpegContainer.xmp(file, headers, MediaLimits.DEFAULT);
            assertEquals("1.0", xmp.text(GainMapMetadata.XMP_NAMESPACE, "Version").orElseThrow());
            assertTrue(GainMapMetadata.fromXmp(xmp).isEmpty());
            var images = JpegContainer.images(file, xmp);
            assertEquals(2, images.size());
            assertEquals(0, images.get(0).offset());
            assertEquals(0x030000, images.get(0).type());
            assertEquals("Primary", images.get(0).semantic());
            assertEquals(images.get(0).length(), images.get(1).offset());
            assertEquals(file.length, images.get(1).offset() + images.get(1).length());
            assertEquals("GainMap", images.get(1).semantic());
            var iso = headers.stream().filter(segment -> segment.startsWith(file, JpegContainer.ISO_ID)).toList();
            assertEquals(1, iso.size());
            assertEquals(JpegContainer.ISO_ID.length + 4, iso.getFirst().length());
            var second = JpegContainer.headers(file, images.get(1).offset(), file.length);
            var metadata = GainMapMetadata.parse(JpegContainer.isoMetadata(file, second));
            assertMetadata(metadata, GainMapMetadata.fromXmp(JpegContainer.xmp(file, second, MediaLimits.DEFAULT))
                    .orElseThrow());
            // the container directory alone locates the gain map too
            var withoutIndex = file.clone();
            var mpf = headers.stream().filter(segment -> segment.startsWith(file, JpegContainer.MPF_ID)).findFirst()
                    .orElseThrow();
            withoutIndex[mpf.offset()] = 'X';
            var fallback = JpegContainer.images(withoutIndex, xmp);
            assertEquals(2, fallback.size());
            assertEquals(images.get(1).offset(), fallback.get(1).offset());
            assertEquals(-1, fallback.get(1).type());
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(withoutIndex));
            assertEquals(1, listed.size());
            assertEquals("container:GainMap", listed.getFirst().identifier());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getFirst().type());
            assertMetadata(metadata, JpegDecoder.decodeGainMap(MediaSource.of(withoutIndex)).orElseThrow().metadata());
        }

        @Test
        @DisplayName("leaves the XMP form out when the gain map applies in the alternate color space")
        void alternateColorSpace() {
            var base = sdr(32, 32);
            var alternate = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.BT2020, TransferCharacteristics.PQ));
            var metadata = new GainMapMetadata(0, 2, List.of(GainMapChannel.of(0, 2)), false);
            var gainMap = new GainMap(metadata, GainMaps.compute(image(base, Optional.empty()), hdr(base)).image(),
                    Optional.of(alternate), Optional.empty());
            var file = JpegEncoder.encode(base, gainMap);
            var read = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            assertFalse(read.metadata().useBaseColorSpace());
            assertEquals(ColorPrimaries.BT2020, read.alternateProfile().orElseThrow().colorInfo().orElseThrow()
                    .primaries());
            var images = JpegContainer.images(file, null);
            var second = JpegContainer.headers(file, images.get(1).offset(), file.length);
            assertTrue(JpegContainer.xmp(file, second, MediaLimits.DEFAULT) == null);
        }
    }

    /** Returns the index after the SOI marker and a JFIF segment that follows it. */
    static int insertionPoint(byte[] jpeg) {
        var at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at += 2 + ((jpeg[4] & 0xFF) << 8 | jpeg[5] & 0xFF);
        }
        return at;
    }

    /** Inserts marker segments, each a marker code followed by its payload, after the SOI and JFIF segments. */
    static byte[] insert(byte[] jpeg, byte[]... segments) {
        var at = insertionPoint(jpeg);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        for (var segment : segments) {
            var length = segment.length + 1;
            out.write(0xFF);
            out.write(segment[0] & 0xFF);
            out.write(length >>> 8);
            out.write(length);
            out.write(segment, 1, segment.length - 1);
        }
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    /** Returns a marker code followed by an identifier and a payload, as {@link #insert} takes segments. */
    static byte[] segment(int marker, String id, byte[] payload) {
        var head = id.getBytes(StandardCharsets.ISO_8859_1);
        var segment = new byte[1 + head.length + payload.length];
        segment[0] = (byte) marker;
        System.arraycopy(head, 0, segment, 1, head.length);
        System.arraycopy(payload, 0, segment, 1 + head.length, payload.length);
        return segment;
    }

    static void put32(byte[] data, int at, long value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    /**
     * Joins JPEG files into a Multi-Picture Format file (CIPA DC-007): the first one with the given segments and a
     * big-endian MP index of all of them, with the given individual image attributes, then the others in order.
     */
    static byte[] mpo(byte[][] images, long[] attributes, byte[]... primarySegments) {
        var count = images.length;
        var mpf = new byte[4 + 8 + 2 + 3 * 12 + 4 + 16 * count];
        System.arraycopy(JpegContainer.MPF_ID, 0, mpf, 0, 4);
        mpf[4] = 'M';
        mpf[5] = 'M';
        mpf[7] = 0x2A;
        put32(mpf, 8, 8);
        mpf[13] = 3;
        // MPFVersion "0100", NumberOfImages, MPEntry
        mpf[14] = (byte) 0xB0;
        mpf[17] = 7;
        put32(mpf, 18, 4);
        System.arraycopy("0100".getBytes(StandardCharsets.US_ASCII), 0, mpf, 22, 4);
        mpf[26] = (byte) 0xB0;
        mpf[27] = 1;
        mpf[29] = 4;
        put32(mpf, 30, 1);
        put32(mpf, 34, count);
        mpf[38] = (byte) 0xB0;
        mpf[39] = 2;
        mpf[41] = 7;
        put32(mpf, 42, 16L * count);
        put32(mpf, 46, 8 + 2 + 3 * 12 + 4);
        var segments = Arrays.copyOf(primarySegments, primarySegments.length + 1);
        var mpfSegment = new byte[1 + mpf.length];
        mpfSegment[0] = (byte) 0xE2;
        segments[primarySegments.length] = mpfSegment;
        var primarySize = insert(images[0], segments).length;
        // the TIFF header follows the MPF segment's marker, length and identifier
        var tiffHeader = insertionPoint(images[0]) + 4 + 4;
        for (var segment : primarySegments) {
            tiffHeader += segment.length + 3;
        }
        long offset = primarySize;
        for (var image = 0; image < count; image++) {
            var at = 4 + 8 + 2 + 3 * 12 + 4 + 16 * image;
            put32(mpf, at, attributes[image]);
            put32(mpf, at + 4, image == 0 ? primarySize : images[image].length);
            put32(mpf, at + 8, image == 0 ? 0 : offset - tiffHeader);
            if (image > 0) {
                offset += images[image].length;
            }
        }
        System.arraycopy(mpf, 0, mpfSegment, 1, mpf.length);
        var out = new ByteArrayOutputStream();
        out.writeBytes(insert(images[0], segments));
        for (var image = 1; image < count; image++) {
            out.writeBytes(images[image]);
        }
        return out.toByteArray();
    }

    /** A gray JPEG file whose samples rise from 0 to 255 left to right. */
    static byte[] grayJpeg(int width, int height) {
        var luma = new byte[width * height];
        for (var i = 0; i < luma.length; i++) {
            luma[i] = (byte) (i % width * 255 / (width - 1));
        }
        var gray = YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME)
                .color(ColorInfo.JPEG).luma(luma, width).build();
        return JpegEncoder.encode(gray, JpegEncoderOptions.builder().quality(100)
                .chromaFormat(ChromaFormat.MONOCHROME).build());
    }

    /** An EXIF block (a TIFF structure) whose Apple maker note holds tags 33 and 48 as signed rationals. */
    static byte[] appleExif(int maker33Numerator, int maker33Denominator, int maker48Numerator,
                            int maker48Denominator) {
        var values = 14 + 2 + 2 * 12 + 4;
        var note = new byte[values + 16];
        System.arraycopy("Apple iOS\0".getBytes(StandardCharsets.ISO_8859_1), 0, note, 0, 10);
        note[11] = 1;
        note[12] = 'M';
        note[13] = 'M';
        note[15] = 2;
        for (var i = 0; i < 2; i++) {
            var at = 16 + 12 * i;
            note[at + 1] = (byte) (i == 0 ? 33 : 48);
            note[at + 3] = 10;
            put32(note, at + 4, 1);
            // the offsets of an Apple maker note count from its start
            put32(note, at + 8, values + 8 * i);
        }
        put32(note, values, maker33Numerator);
        put32(note, values + 4, maker33Denominator);
        put32(note, values + 8, maker48Numerator);
        put32(note, values + 12, maker48Denominator);
        var exifIfd = 8 + 2 + 12 + 4;
        var noteAt = exifIfd + 2 + 12 + 4;
        var tiff = new byte[noteAt + note.length];
        tiff[0] = 'M';
        tiff[1] = 'M';
        tiff[3] = 0x2A;
        put32(tiff, 4, 8);
        tiff[9] = 1;
        tiff[10] = (byte) 0x87;
        tiff[11] = 0x69;
        tiff[13] = 4;
        put32(tiff, 14, 1);
        put32(tiff, 18, exifIfd);
        tiff[exifIfd + 1] = 1;
        tiff[exifIfd + 2] = (byte) 0x92;
        tiff[exifIfd + 3] = 0x7C;
        tiff[exifIfd + 5] = 7;
        put32(tiff, exifIfd + 6, note.length);
        put32(tiff, exifIfd + 10, noteAt);
        System.arraycopy(note, 0, tiff, noteAt, note.length);
        return tiff;
    }

    @Nested
    @DisplayName("Multi-Picture Format images")
    class MultiPicture {
        @Test
        @DisplayName("lists thumbnails, stereo views, panorama frames and others by their type code")
        void types() {
            var file = mpo(new byte[][]{JpegEncoder.encode(sdr(64, 48)), JpegEncoder.encode(sdr(32, 24)),
                    JpegEncoder.encode(sdr(64, 48)), JpegEncoder.encode(sdr(40, 30)), grayJpeg(16, 8)},
                    // a representative primary image, a dependent thumbnail, then stereo, panorama and undefined
                    // images; the flags above the type code are not part of the type
                    new long[]{0x20030000L, 0x40010001L, 0x020002, 0x020001, 0});
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(file));
            assertEquals(List.of(AuxiliaryImageType.THUMBNAIL, AuxiliaryImageType.DISPARITY, AuxiliaryImageType.VIEW,
                    AuxiliaryImageType.OTHER), listed.stream().map(AuxiliaryImage::type).toList());
            assertEquals(List.of("mpf:010001", "mpf:020002", "mpf:020001", "mpf:000000"),
                    listed.stream().map(AuxiliaryImage::identifier).toList());
            assertEquals(List.of(0, 1, 2, 3), listed.stream().map(AuxiliaryImage::index).toList());
            assertEquals(List.of(32, 64, 40, 16), listed.stream().map(AuxiliaryImage::width).toList());
            assertEquals(List.of(24, 48, 30, 8), listed.stream().map(AuxiliaryImage::height).toList());
            var view = JpegDecoder.decodeAuxiliaryImage(MediaSource.of(file), 1);
            assertEquals(64, view.picture().width());
            assertEquals(48, view.picture().height());
            var gray = assertInstanceOf(YuvPicture.class, JpegDecoder.decodeAuxiliaryImage(MediaSource.of(file), 3)
                    .picture());
            assertEquals(ChromaFormat.MONOCHROME, gray.chromaFormat());
            assertEquals(255, gray.y()[15] & 0xFF, 2);
            assertThrows(IndexOutOfBoundsException.class, () -> JpegDecoder.decodeAuxiliaryImage(MediaSource.of(file),
                    4));
            assertThrows(IndexOutOfBoundsException.class, () -> JpegDecoder.decodeAuxiliaryImage(MediaSource.of(file),
                    -1));
            assertTrue(JpegDecoder.decodeGainMap(MediaSource.of(file)).isEmpty());
            assertEquals(64, JpegDecoder.decode(MediaSource.of(file)).picture().width());
        }

        @Test
        @DisplayName("converts an Apple gain map with the headroom of the primary image's maker note")
        void apple() {
            var gainMapXmp = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                    + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                    + " xmlns:HDRGainMap=\"http://ns.apple.com/HDRGainMap/1.0/\" HDRGainMap:HDRGainMapVersion=\"65536\"/>"
                    + "</rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
            var gainMapFile = insert(grayJpeg(32, 24), segment(0xE1, "http://ns.adobe.com/xap/1.0/\0", gainMapXmp));
            // maker33 = 0.5 and maker48 = 0.003: Apple's formula gives -20 * 0.003 + 1.8 = 1.74 stops
            var exif = segment(0xE1, "Exif\0\0", appleExif(1, 2, 3, 1000));
            var file = mpo(new byte[][]{JpegEncoder.encode(sdr(64, 48)), gainMapFile}, new long[]{0x20030000L, 0},
                    exif);
            var gainMap = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            var metadata = gainMap.metadata();
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(1.74, metadata.alternateHeadroom(), 1e-9);
            var channel = metadata.channel(0);
            assertEquals(0, channel.minimum());
            assertEquals(1.74, channel.maximum(), 1e-9);
            assertEquals(1, channel.gamma());
            assertEquals(0, channel.baseOffset());
            assertEquals(0, channel.alternateOffset());
            assertEquals(32, gainMap.image().width());
            assertEquals(24, gainMap.image().height());
            // the darkest gain map sample keeps the base, the brightest applies the full headroom
            var samples = assertInstanceOf(Channel16Picture.class, gainMap.image());
            assertTrue((samples.pixels()[0] & 0xFFFF) < 600, "darkest " + (samples.pixels()[0] & 0xFFFF));
            assertTrue((samples.pixels()[31] & 0xFFFF) > 64000, "brightest " + (samples.pixels()[31] & 0xFFFF));
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(file));
            assertEquals(1, listed.size());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getFirst().type());
            assertEquals("mpf:000000", listed.getFirst().identifier());
            // without the maker note the headroom is unknown, so no gain map is returned, though one is listed
            var withoutExif = mpo(new byte[][]{JpegEncoder.encode(sdr(64, 48)), gainMapFile},
                    new long[]{0x20030000L, 0});
            assertTrue(JpegDecoder.decodeGainMap(MediaSource.of(withoutExif)).isEmpty());
            assertEquals(AuxiliaryImageType.GAIN_MAP, JpegDecoder.auxiliaryImages(MediaSource.of(withoutExif))
                    .getFirst().type());
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("finds no gain map in a plain JPEG")
        void plain() {
            var file = JpegEncoder.encode(sdr(16, 16));
            assertTrue(JpegDecoder.decodeGainMap(MediaSource.of(file)).isEmpty());
            assertTrue(JpegDecoder.auxiliaryImages(MediaSource.of(file)).isEmpty());
            assertThrows(IndexOutOfBoundsException.class, () -> JpegDecoder.decodeAuxiliaryImage(
                    MediaSource.of(file), 0));
        }

        @Test
        @DisplayName("ignores index entries that point outside the file")
        void badIndex() {
            var file = ultraHdr(sdr(32, 24), GainMapComputeOptions.defaults());
            var cut = Arrays.copyOf(file, file.length - 100);
            var listed = JpegDecoder.auxiliaryImages(MediaSource.of(cut));
            assertTrue(listed.isEmpty() || listed.getFirst().type() == AuxiliaryImageType.GAIN_MAP);
        }

        @Test
        @DisplayName("never fails with an unchecked exception on damaged files")
        void damaged() {
            var file = ultraHdr(sdr(32, 24), GainMapComputeOptions.defaults());
            var random = new Random(9);
            for (var i = 0; i < 300; i++) {
                var damaged = file.clone();
                for (var k = 0; k < 4; k++) {
                    damaged[random.nextInt(Math.min(damaged.length, 1200))] = (byte) random.nextInt(256);
                }
                var length = random.nextBoolean() ? damaged.length : random.nextInt(damaged.length);
                var source = Arrays.copyOf(damaged, length);
                try {
                    JpegDecoder.decodeGainMap(MediaSource.of(source));
                    JpegDecoder.auxiliaryImages(MediaSource.of(source));
                } catch (MediaException expected) {
                    // rejected cleanly
                }
            }
        }
    }

    static void assertMetadata(GainMapMetadata expected, GainMapMetadata actual) {
        assertEquals(expected.isMultichannel(), actual.isMultichannel());
        assertEquals(expected.useBaseColorSpace(), actual.useBaseColorSpace());
        assertEquals(expected.baseHeadroom(), actual.baseHeadroom(), 1e-8);
        assertEquals(expected.alternateHeadroom(), actual.alternateHeadroom(), 1e-8);
        for (var c = 0; c < 3; c++) {
            assertEquals(expected.channel(c).minimum(), actual.channel(c).minimum(), 1e-8);
            assertEquals(expected.channel(c).maximum(), actual.channel(c).maximum(), 1e-8);
            assertEquals(expected.channel(c).gamma(), actual.channel(c).gamma(), 1e-8);
            assertEquals(expected.channel(c).baseOffset(), actual.channel(c).baseOffset(), 1e-8);
            assertEquals(expected.channel(c).alternateOffset(), actual.channel(c).alternateOffset(), 1e-8);
        }
    }

    @Test
    @DisplayName("keeps the gain map picture as decoded")
    void sameInstance() {
        var gainMap = new GainMap(GainMapMetadata.of(0, 1, GainMapChannel.of(0, 1)), sdr(4, 4));
        assertSame(gainMap.image(), gainMap.withMetadata(gainMap.metadata()).image());
    }
}
