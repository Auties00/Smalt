package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.ScalarConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Checks the conversion of every camera pixel format from synthetic sample buffers: plane
 * layouts, strides, odd sizes, bottom-up RGB, Motion-JPEG, and buffers shorter than their type.
 */
@DisplayName("camera frame conversion")
class CameraConverterTest {
    private static NativeType type(CameraFormat format, int width, int height, ColorInfo color) {
        return new NativeType(0, format, width, height, new Rational(30, 1), 0, color);
    }

    private static YuvPicture picture(CameraConverter converter, ColorInfo color) {
        return YuvPicture.allocate(converter.width(), converter.height(), ChromaFormat.YUV420, false, color);
    }

    private static byte[] random(long seed, int length) {
        var bytes = new byte[length];
        new SplittableRandom(seed).nextBytes(bytes);
        return bytes;
    }

    @Test
    @DisplayName("copies NV12 luma and splits its interleaved chroma, cropping an odd size")
    void nv12() {
        var width = 7;
        var height = 5;
        var pitch = 12;
        var converter = new CameraConverter(type(CameraFormat.NV12, width, height, ColorInfo.BT601_LIMITED));
        var buffer = random(1, (int) converter.extent(pitch));
        var out = picture(converter, ColorInfo.BT601_LIMITED);
        converter.convert(MemorySegment.ofArray(buffer), 0, pitch, out);
        assertEquals(6, out.width());
        assertEquals(4, out.height());
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 6; x++) {
                assertEquals(buffer[y * pitch + x], out.y()[y * 6 + x]);
            }
        }
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 3; x++) {
                var chroma = height * pitch + y * pitch + 2 * x;
                assertEquals(buffer[chroma], out.u()[y * 3 + x]);
                assertEquals(buffer[chroma + 1], out.v()[y * 3 + x]);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CameraFormat.class, names = {"I420", "IYUV", "YV12"})
    @DisplayName("copies the three planes of planar 4:2:0, swapping them for YV12")
    void planar(CameraFormat format) {
        var width = 8;
        var height = 6;
        var pitch = 10;
        var converter = new CameraConverter(type(format, width, height, ColorInfo.BT601_LIMITED));
        var buffer = random(2, (int) converter.extent(pitch));
        var out = picture(converter, ColorInfo.BT601_LIMITED);
        converter.convert(MemorySegment.ofArray(buffer), 0, pitch, out);
        var first = height * pitch;
        var second = first + (pitch / 2) * (height / 2);
        var cb = format == CameraFormat.YV12 ? second : first;
        var cr = format == CameraFormat.YV12 ? first : second;
        for (var y = 0; y < 3; y++) {
            for (var x = 0; x < 4; x++) {
                assertEquals(buffer[cb + y * 5 + x], out.u()[y * 4 + x]);
                assertEquals(buffer[cr + y * 5 + x], out.v()[y * 4 + x]);
            }
        }
        assertEquals(buffer[pitch + 3], out.y()[width + 3]);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CameraFormat.class, names = {"YUY2", "YUYV", "UYVY"})
    @DisplayName("converts packed 4:2:2 rows with a padded pitch")
    void packed(CameraFormat format) {
        var width = 10;
        var height = 4;
        var pitch = 24;
        var converter = new CameraConverter(type(format, width, height, ColorInfo.BT601_LIMITED));
        var buffer = random(3, pitch * height);
        var out = picture(converter, ColorInfo.BT601_LIMITED);
        converter.convert(MemorySegment.ofArray(buffer), 0, pitch, out);
        var uyvy = format == CameraFormat.UYVY;
        var expected = picture(converter, ColorInfo.BT601_LIMITED);
        var rows = new byte[4 * width];
        var kernel = new ScalarConversionKernel();
        for (var y = 0; y < height; y += 2) {
            System.arraycopy(buffer, y * pitch, rows, 0, 2 * width);
            System.arraycopy(buffer, (y + 1) * pitch, rows, 2 * width, 2 * width);
            var lumaIndex = uyvy ? 1 : 0;
            kernel.packedLuma(rows, 0, width, lumaIndex, expected.y(), y * width);
            kernel.packedLuma(rows, 2 * width, width, lumaIndex, expected.y(), (y + 1) * width);
            kernel.packedChroma(rows, 0, 2 * width, width / 2, 1 - lumaIndex, 3 - lumaIndex, expected.u(), expected.v(),
                    y / 2 * (width / 2));
        }
        assertArrayEquals(expected.y(), out.y());
        assertArrayEquals(expected.u(), out.u());
        assertArrayEquals(expected.v(), out.v());
    }

    @Test
    @DisplayName("converts bottom-up RGB32 and RGB24 to the same picture as top-down RGB32")
    void rgb() {
        var width = 6;
        var height = 4;
        var topDown = random(4, 4 * width * height);
        var reference = new CameraConverter(type(CameraFormat.RGB32, width, height, YuvCoefficients.BT709_LIMITED.color()));
        var expected = picture(reference, YuvCoefficients.BT709_LIMITED.color());
        reference.convert(MemorySegment.ofArray(topDown), 0, 4L * width, expected);

        var bottomUp = new byte[topDown.length];
        var packed = new byte[4 * width * height];
        var packedPitch = 20;
        for (var y = 0; y < height; y++) {
            System.arraycopy(topDown, 4 * y * width, bottomUp, 4 * (height - 1 - y) * width, 4 * width);
            for (var x = 0; x < width; x++) {
                System.arraycopy(topDown, 4 * (y * width + x), packed, y * packedPitch + 3 * x, 3);
            }
        }
        var bottom = picture(reference, YuvCoefficients.BT709_LIMITED.color());
        reference.convert(MemorySegment.ofArray(bottomUp), 4L * (height - 1) * width, -4L * width, bottom);
        assertArrayEquals(expected.y(), bottom.y());
        assertArrayEquals(expected.u(), bottom.u());

        var rgb24 = new CameraConverter(type(CameraFormat.RGB24, width, height, YuvCoefficients.BT709_LIMITED.color()));
        var fromPacked = picture(rgb24, YuvCoefficients.BT709_LIMITED.color());
        rgb24.convert(MemorySegment.ofArray(packed), 0, packedPitch, fromPacked);
        assertArrayEquals(expected.y(), fromPacked.y());
        assertArrayEquals(expected.v(), fromPacked.v());
    }

    @Test
    @DisplayName("uses the declared stride of contiguous buffers, negative for bottom-up images")
    void contiguousPitch() {
        assertEquals(40, new CameraConverter(type(CameraFormat.RGB32, 10, 2, YuvCoefficients.BT709_LIMITED.color())).contiguousPitch());
        assertEquals(20, new CameraConverter(type(CameraFormat.YUY2, 9, 2, ColorInfo.BT601_LIMITED)).contiguousPitch());
        var declared = new NativeType(0, CameraFormat.RGB32, 10, 2, new Rational(30, 1), -48, YuvCoefficients.BT709_LIMITED.color());
        assertEquals(-48, new CameraConverter(declared).contiguousPitch());
    }

    @Test
    @DisplayName("refuses a buffer shorter than its media type instead of reading past it")
    void shortBuffer() {
        var converter = new CameraConverter(type(CameraFormat.NV12, 16, 16, ColorInfo.BT601_LIMITED));
        var out = picture(converter, ColorInfo.BT601_LIMITED);
        assertThrows(IndexOutOfBoundsException.class,
                () -> converter.convert(MemorySegment.ofArray(new byte[16 * 16]), 0, 16, out));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ChromaFormat.class, names = {"YUV422", "YUV420", "YUV444"})
    @DisplayName("decodes Motion-JPEG frames exactly like the decoder followed by ImageOps")
    void jpeg(ChromaFormat subsampling) {
        var width = 66;
        var height = 38;
        var rgba = new byte[4 * width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var offset = 4 * (y * width + x);
                rgba[offset] = (byte) (x * 255 / width);
                rgba[offset + 1] = (byte) (y * 255 / height);
                rgba[offset + 2] = (byte) ((x ^ y) * 4);
                rgba[offset + 3] = (byte) 255;
            }
        }
        var jpeg = JpegEncoder.encode(RgbaPicture.of(width, height, 4 * width, rgba, false),
                JpegEncoderOptions.builder().quality(90).chromaFormat(subsampling).optimizeHuffman(false).build());
        var converter = new CameraConverter(type(CameraFormat.MJPG, width, height, ColorInfo.JPEG));
        var out = picture(converter, ColorInfo.JPEG);
        converter.convertJpeg(MemorySegment.ofArray(jpeg), jpeg.length, out);
        var expected = ImageOps.toYuv(new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length), ChromaFormat.YUV420, ColorInfo.JPEG);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(expected.y()[y * expected.yStride() + x], out.y()[y * out.yStride() + x], "luma " + x + "," + y);
            }
        }
        for (var y = 0; y < height / 2; y++) {
            for (var x = 0; x < width / 2; x++) {
                assertEquals(expected.u()[y * expected.uvStride() + x], out.u()[y * out.uvStride() + x], "cb " + x + "," + y);
                assertEquals(expected.v()[y * expected.uvStride() + x], out.v()[y * out.uvStride() + x], "cr " + x + "," + y);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CameraFormat.class, mode = EnumSource.Mode.EXCLUDE, names = "MJPG")
    @DisplayName("either converts or refuses buffers of random lengths and pitches, never reading out of bounds")
    void robustness(CameraFormat format) {
        var random = new SplittableRandom(format.ordinal());
        for (var i = 0; i < 200; i++) {
            var width = 2 + random.nextInt(40);
            var height = 2 + random.nextInt(30);
            var converter = new CameraConverter(type(format, width, height, ColorInfo.BT601_LIMITED));
            var out = picture(converter, ColorInfo.BT601_LIMITED);
            var buffer = new byte[random.nextInt(4 * width * height * 2 + 1)];
            var pitch = random.nextInt(-8 * width, 8 * width + 1);
            var origin = buffer.length == 0 ? 0 : random.nextInt(buffer.length);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try {
                    converter.convert(MemorySegment.ofArray(buffer), origin, pitch, out);
                } catch (IndexOutOfBoundsException expected) {
                    // the session skips such a frame
                }
            });
        }
    }

    @Test
    @DisplayName("raises only media exceptions for mutated and truncated Motion-JPEG frames")
    void jpegMutations() {
        var rgba = random(6, 4 * 48 * 32);
        var jpeg = JpegEncoder.encode(RgbaPicture.of(48, 32, 4 * 48, rgba, false), JpegEncoderOptions.builder().quality(80).chromaFormat(ChromaFormat.YUV422).optimizeHuffman(false).build());
        var converter = new CameraConverter(type(CameraFormat.MJPG, 48, 32, ColorInfo.JPEG));
        var out = picture(converter, ColorInfo.JPEG);
        var random = new SplittableRandom(7);
        for (var i = 0; i < 300; i++) {
            var mutated = jpeg.clone();
            var length = mutated.length;
            switch (i % 3) {
                case 0 -> mutated[random.nextInt(length)] ^= (byte) (1 + random.nextInt(255));
                case 1 -> length = random.nextInt(length);
                default -> {
                    for (var j = 0; j < 8; j++) {
                        mutated[random.nextInt(length)] = (byte) random.nextInt(256);
                    }
                }
            }
            var size = length;
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try {
                    converter.convertJpeg(MemorySegment.ofArray(mutated), size, out);
                } catch (MediaException expected) {
                    // the session skips such a frame
                }
            });
        }
    }

    @Test
    @DisplayName("rejects a corrupt Motion-JPEG frame with a media exception")
    void corruptJpeg() {
        var converter = new CameraConverter(type(CameraFormat.MJPG, 16, 16, ColorInfo.JPEG));
        var out = picture(converter, ColorInfo.JPEG);
        var garbage = random(5, 64);
        assertThrows(MediaException.class, () -> converter.convertJpeg(MemorySegment.ofArray(garbage), garbage.length, out));
    }
}
