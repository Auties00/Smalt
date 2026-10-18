package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the metadata the decoder extracts: the orientation and TIFF block of the first
 * {@code APP1 Exif} segment, and the ICC profile reassembled from {@code APP2 ICC_PROFILE} chunks.
 */
class JpegMetadataTest {
    private static final byte[] BASE = JpegEncoder.encode(RgbaPicture.allocate(40, 24, false),
            JpegEncoderOptions.builder().quality(90).chromaFormat(ChromaFormat.YUV420).build());

    /**
     * Inserts segments right after the JFIF APP0 segment.
     */
    static byte[] withSegments(byte[]... segments) {
        var out = new ByteArrayOutputStream();
        out.write(BASE, 0, 20);
        for (var segment : segments) {
            out.writeBytes(segment);
        }
        out.write(BASE, 20, BASE.length - 20);
        return out.toByteArray();
    }

    static byte[] segment(int marker, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >>> 8);
        out.write(payload.length + 2);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static byte[] exif(byte[] tiff) {
        var payload = new byte[6 + tiff.length];
        System.arraycopy("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, payload, 0, 6);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        return segment(0xE1, payload);
    }

    static byte[] orientationTiff(Orientation orientation) {
        return ExifWriter.withOrientation(new byte[0], orientation);
    }

    static byte[] icc(int sequence, int count, byte[] chunk) {
        var payload = new byte[14 + chunk.length];
        System.arraycopy("ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1), 0, payload, 0, 12);
        payload[12] = (byte) sequence;
        payload[13] = (byte) count;
        System.arraycopy(chunk, 0, payload, 14, chunk.length);
        return segment(0xE2, payload);
    }

    @Nested
    @DisplayName("EXIF")
    class Exif {
        @ParameterizedTest
        @EnumSource(Orientation.class)
        @DisplayName("reads the orientation in probe and decode, and returns the TIFF block")
        void orientation(Orientation orientation) {
            var tiff = orientationTiff(orientation);
            var jpeg = withSegments(exif(tiff));
            var info = JpegDecoder.probe(MediaSource.of(jpeg));
            assertEquals(orientation, info.orientation());
            assertEquals(ImageFormat.JPEG, info.format());
            assertEquals(orientation.swapsDimensions() ? 24 : 40, info.displayWidth());
            var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults());
            assertEquals(orientation, decoded.orientation());
            assertArrayEquals(tiff, decoded.exif().orElseThrow());
        }

        @Test
        @DisplayName("uses only the first Exif segment, skipping XMP and later Exif segments")
        void firstOnly() {
            var xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(StandardCharsets.ISO_8859_1));
            var jpeg = withSegments(xmp, exif(orientationTiff(Orientation.ROTATE_180)), exif(orientationTiff(Orientation.ROTATE_90)));
            assertEquals(Orientation.ROTATE_180, JpegDecoder.probe(MediaSource.of(jpeg)).orientation());
            assertEquals(Orientation.ROTATE_180, JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).orientation());
        }

        @Test
        @DisplayName("treats a garbage Exif block as normal orientation but still returns it")
        void garbage() {
            var garbage = new byte[50];
            new Random(4).nextBytes(garbage);
            var jpeg = withSegments(exif(garbage));
            var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults());
            assertEquals(Orientation.NORMAL, decoded.orientation());
            assertArrayEquals(garbage, decoded.exif().orElseThrow());
            assertEquals(Orientation.NORMAL, JpegDecoder.probe(MediaSource.of(jpeg)).orientation());
            assertTrue(JpegDecoder.decode(MediaSource.of(BASE), ImageDecoderOptions.defaults()).exif().isEmpty());
        }
    }

    @Nested
    @DisplayName("ICC profile")
    class Icc {
        private final byte[] profile = profile();

        private static byte[] profile() {
            var profile = new byte[2500];
            new Random(5).nextBytes(profile);
            return profile;
        }

        private byte[] chunk(int index) {
            return java.util.Arrays.copyOfRange(profile, index * 1000, Math.min(profile.length, (index + 1) * 1000));
        }

        @Test
        @DisplayName("reassembles chunks in sequence order even when stored out of order")
        void reassembles() {
            var jpeg = withSegments(icc(2, 3, chunk(1)), icc(1, 3, chunk(0)), icc(3, 3, chunk(2)));
            assertArrayEquals(profile, JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).iccProfile().orElseThrow());
            var single = withSegments(icc(1, 1, profile));
            assertArrayEquals(profile, JpegDecoder.decode(MediaSource.of(single), ImageDecoderOptions.defaults()).iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("drops a profile with a missing, duplicated or inconsistent chunk")
        void inconsistent() {
            for (var jpeg : new byte[][]{
                    withSegments(icc(1, 3, chunk(0)), icc(3, 3, chunk(2))),
                    withSegments(icc(1, 2, chunk(0)), icc(1, 2, chunk(1))),
                    withSegments(icc(1, 2, chunk(0)), icc(2, 3, chunk(1))),
                    withSegments(icc(0, 1, chunk(0))),
                    withSegments(icc(2, 1, chunk(0)))}) {
                assertTrue(JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).iccProfile().isEmpty());
            }
        }
    }
}
