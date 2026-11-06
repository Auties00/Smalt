package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the lossless JPEG transforms on files whose EXIF thumbnail is uncompressed: the samples
 * move exactly as {@link ImageOps#orient} moves the pixels of a picture, a grayscale transform keeps
 * the JFIF luma, and a layout the transform cannot move loses its thumbnail.
 */
@DisplayName("JPEG transforms of uncompressed EXIF thumbnails")
class JpegUncompressedThumbnailTest {
    static final int WIDTH = 5;
    static final int HEIGHT = 3;

    static byte[] rgb(long seed) {
        var data = new byte[WIDTH * HEIGHT * 3];
        new Random(seed).nextBytes(data);
        return data;
    }

    static Map<Integer, ExifValue> ifd1(int bits, int samples, int photometric, int planar) {
        var ifd1 = new LinkedHashMap<Integer, ExifValue>();
        ifd1.put(0x0100, ExifValue.ofShort(WIDTH));
        ifd1.put(0x0101, ExifValue.ofShort(HEIGHT));
        var depths = new int[samples];
        Arrays.fill(depths, bits);
        ifd1.put(0x0102, ExifValue.ofShort(depths));
        ifd1.put(0x0106, ExifValue.ofShort(photometric));
        ifd1.put(0x0115, ExifValue.ofShort(samples));
        ifd1.put(0x011C, ExifValue.ofShort(planar));
        return ifd1;
    }

    static byte[] jpegWith(Map<Integer, ExifValue> ifd1, byte[] samples, boolean little) {
        var image = JpegEncoder.encode(RgbaPicture.allocate(32, 16, false), JpegEncoderOptions.ofQuality(80));
        var data = new ExifData(Map.of(0x010F, new ExifValue.Ascii("Smalt")), Map.of(), Map.of(), Map.of(), ifd1,
                Optional.empty()).withUncompressedThumbnail(samples);
        var block = ExifWriter.write(data, little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        var out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write((block.length + 8) >> 8);
        out.write(block.length + 8);
        out.writeBytes("Exif\0\0".getBytes());
        out.writeBytes(block);
        out.write(image, 2, image.length - 2);
        return out.toByteArray();
    }

    static ExifData exifOf(byte[] jpeg) {
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if ((jpeg[position + 1] & 0xFF) == 0xE1) {
                return ExifReader.parse(Arrays.copyOfRange(jpeg, position + 10, position + 2 + length));
            }
            position += 2 + length;
        }
        throw new AssertionError("no EXIF segment");
    }

    /** Returns the expected samples: the RGB thumbnail as a picture, oriented by ImageOps. */
    static RgbaPicture oriented(byte[] rgb, Orientation transform) {
        var picture = RgbaPicture.allocate(WIDTH, HEIGHT, false);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            var at = i % WIDTH * 4 + i / WIDTH * picture.stride();
            picture.pixels()[at] = rgb[3 * i];
            picture.pixels()[at + 1] = rgb[3 * i + 1];
            picture.pixels()[at + 2] = rgb[3 * i + 2];
            picture.pixels()[at + 3] = (byte) 255;
        }
        return (RgbaPicture) ImageOps.orient(picture, transform);
    }

    static byte[] packed(RgbaPicture picture) {
        var out = new byte[picture.width() * picture.height() * 3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                System.arraycopy(picture.pixels(), y * picture.stride() + 4 * x, out, 3 * (y * picture.width() + x), 3);
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Orientation.class)
    @DisplayName("moves chunky RGB samples as ImageOps.orient moves pixels and records the new size")
    void chunky(Orientation transform) {
        var rgb = rgb(transform.ordinal());
        var jpeg = jpegWith(ifd1(8, 3, 2, 1), rgb, true);
        var options = JpegTransformOptions.of(transform).toBuilder().trim(true).build();
        var result = exifOf(JpegTransforms.transform(MediaSource.of(jpeg), options));
        var expected = oriented(rgb, transform);
        assertTrue(result.hasUncompressedThumbnail());
        assertArrayEquals(packed(expected), result.thumbnail().orElseThrow());
        assertEquals(ExifValue.ofShort(expected.width()), result.thumbnailIfd().get(0x0100));
        assertEquals(ExifValue.ofShort(expected.height()), result.thumbnailIfd().get(0x0101));
    }

    @Test
    @DisplayName("moves planar 16-bit samples plane by plane")
    void planar16() {
        var data = new byte[WIDTH * HEIGHT * 3 * 2];
        new Random(9).nextBytes(data);
        var jpeg = jpegWith(ifd1(16, 3, 2, 2), data, false);
        var result = exifOf(JpegTransforms.rotate(MediaSource.of(jpeg), 90)).thumbnail().orElseThrow();
        var plane = WIDTH * HEIGHT;
        for (var c = 0; c < 3; c++) {
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    // a clockwise quarter turn sends (x, y) to (HEIGHT - 1 - y, x) in a HEIGHT-wide picture
                    var from = 2 * (c * plane + y * WIDTH + x);
                    var to = 2 * (c * plane + x * HEIGHT + (HEIGHT - 1 - y));
                    assertArrayEquals(Arrays.copyOfRange(data, from, from + 2), Arrays.copyOfRange(result, to, to + 2));
                }
            }
        }
    }

    @Test
    @DisplayName("a grayscale transform keeps the JFIF luma of each pixel as a one-sample thumbnail")
    void grayscale() {
        var rgb = rgb(11);
        var jpeg = jpegWith(ifd1(8, 3, 2, 1), rgb, true);
        var result = exifOf(JpegTransforms.grayscale(MediaSource.of(jpeg)));
        var samples = result.thumbnail().orElseThrow();
        assertEquals(WIDTH * HEIGHT, samples.length);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            var luma = Math.round(0.299 * (rgb[3 * i] & 0xFF) + 0.587 * (rgb[3 * i + 1] & 0xFF) + 0.114 * (rgb[3 * i + 2] & 0xFF));
            assertTrue(Math.abs(luma - (samples[i] & 0xFF)) <= 1, "pixel " + i);
        }
        assertEquals(ExifValue.ofShort(1), result.thumbnailIfd().get(0x0115));
        assertEquals(ExifValue.ofShort(1), result.thumbnailIfd().get(0x0106));
        assertEquals(ExifValue.ofShort(8), result.thumbnailIfd().get(0x0102));
    }

    @Test
    @DisplayName("a grayscale RGBA thumbnail drops the entries that counted the old samples")
    void grayscaleAlpha() {
        var data = new byte[WIDTH * HEIGHT * 4];
        new Random(14).nextBytes(data);
        var ifd1 = ifd1(8, 4, 2, 1);
        ifd1.put(0x0152, ExifValue.ofShort(2));
        ifd1.put(0x0153, ExifValue.ofShort(1, 1, 1, 1));
        var result = exifOf(JpegTransforms.transform(MediaSource.of(jpegWith(ifd1, data, true)),
                JpegTransformOptions.of(Orientation.ROTATE_90).toBuilder().grayscale(true).build()));
        var samples = result.thumbnail().orElseThrow();
        assertEquals(WIDTH * HEIGHT, samples.length);
        assertFalse(result.thumbnailIfd().containsKey(0x0152));
        assertFalse(result.thumbnailIfd().containsKey(0x0153));
        assertEquals(ExifValue.ofShort(1), result.thumbnailIfd().get(0x0115));
        // the pixel at (0, 0) lands in the last column of the first row after a clockwise quarter turn
        var expected = (19595 * (data[0] & 0xFF) + 38470 * (data[1] & 0xFF) + 7471 * (data[2] & 0xFF) + 32768) >>> 16;
        assertEquals(expected, samples[HEIGHT - 1] & 0xFF);
    }

    @Test
    @DisplayName("a grayscale YCbCr thumbnail keeps its luma, stretched from the reference range to full range")
    void grayscaleYcbcr() {
        var data = new byte[WIDTH * HEIGHT * 3];
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            data[3 * i] = (byte) (16 + i * 14);
            data[3 * i + 1] = (byte) 128;
            data[3 * i + 2] = (byte) 128;
        }
        var ifd1 = ifd1(8, 3, 6, 1);
        ifd1.put(0x0212, ExifValue.ofShort(1, 1));
        var full = exifOf(JpegTransforms.grayscale(MediaSource.of(jpegWith(ifd1, data, true))));
        var studio = new LinkedHashMap<>(ifd1);
        studio.put(0x0214, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{16, 235, 128, 240, 128, 240},
                new long[]{1, 1, 1, 1, 1, 1}));
        var stretched = exifOf(JpegTransforms.grayscale(MediaSource.of(jpegWith(studio, data, false))));
        for (var result : List.of(full, stretched)) {
            assertEquals(ExifValue.ofShort(1), result.thumbnailIfd().get(0x0106));
            assertFalse(result.thumbnailIfd().containsKey(0x0214));
            assertFalse(result.thumbnailIfd().containsKey(0x0212));
        }
        var fullSamples = full.thumbnail().orElseThrow();
        var stretchedSamples = stretched.thumbnail().orElseThrow();
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            var y = 16 + i * 14;
            assertEquals(y, fullSamples[i] & 0xFF, "pixel " + i);
            assertEquals(Math.min(255, Math.round((y - 16) * 255.0 / 219)), stretchedSamples[i] & 0xFF, "pixel " + i);
        }
    }

    @Test
    @DisplayName("dimensions whose product overflows remove the thumbnail instead of failing the transform")
    void hugeDimensions() {
        var ifd1 = ifd1(8, 3, 2, 1);
        ifd1.put(0x0100, ExifValue.ofLong(0xFFFF_FFFFL));
        ifd1.put(0x0101, ExifValue.ofLong(0x8000_0001L));
        var jpeg = jpegWith(ifd1, rgb(15), true);
        for (var options : List.of(JpegTransformOptions.of(Orientation.ROTATE_90),
                JpegTransformOptions.of(Orientation.NORMAL).toBuilder().grayscale(true).build())) {
            var result = exifOf(JpegTransforms.transform(MediaSource.of(jpeg), options));
            assertTrue(result.thumbnail().isEmpty());
            assertEquals(new ExifValue.Ascii("Smalt"), result.ifd0().get(0x010F));
        }
    }

    @Test
    @DisplayName("a thumbnail of a layout the transform cannot move is removed")
    void unsupported() {
        var ycc = ifd1(8, 3, 6, 1);
        var jpeg = jpegWith(ycc, rgb(12), true);
        var result = exifOf(JpegTransforms.rotate(MediaSource.of(jpeg), 180));
        assertTrue(result.thumbnail().isEmpty());
        assertEquals(new ExifValue.Ascii("Smalt"), result.ifd0().get(0x010F));
        var untouched = exifOf(JpegTransforms.transform(MediaSource.of(jpeg),
                JpegTransformOptions.of(Orientation.ROTATE_180).toBuilder().transformThumbnail(false).build()));
        assertTrue(untouched.hasUncompressedThumbnail());
        var subsampled = new LinkedHashMap<>(ycc);
        subsampled.put(0x0212, ExifValue.ofShort(1, 1));
        var moved = exifOf(JpegTransforms.rotate(MediaSource.of(jpegWith(subsampled, rgb(13), true)), 180));
        assertFalse(moved.thumbnail().isEmpty());
    }
}
