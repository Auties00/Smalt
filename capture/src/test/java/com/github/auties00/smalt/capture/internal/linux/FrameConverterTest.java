package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.ScalarConversionKernel;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Converts random raw frames of every format, size parity and stride padding, and compares the
 * result with a per-sample reference written from the V4L2 format definitions.
 */
@DisplayName("frame conversion")
class FrameConverterTest {
    private static final List<V4l2PixelFormat> RAW = List.of(V4l2PixelFormat.YUV420, V4l2PixelFormat.YVU420, V4l2PixelFormat.NV12,
            V4l2PixelFormat.NV21, V4l2PixelFormat.YUYV, V4l2PixelFormat.YVYU, V4l2PixelFormat.UYVY, V4l2PixelFormat.VYUY);

    private static List<ConversionKernel> kernels() {
        var kernels = new ArrayList<ConversionKernel>();
        kernels.add(new ScalarConversionKernel());
        if (Simd.ENABLED) {
            kernels.add(ConversionKernel.INSTANCE);
        }
        return kernels;
    }

    static Stream<Arguments> rawCases() {
        var cases = new ArrayList<Arguments>();
        int[][] sizes = {{2, 2}, {8, 6}, {64, 48}, {66, 34}, {130, 7}, {7, 5}, {33, 17}};
        for (var format : RAW) {
            for (var size : sizes) {
                if (format.kind() == V4l2PixelFormat.Kind.PACKED && (size[0] & 1) != 0) {
                    continue;
                }
                cases.add(Arguments.of(format, size[0], size[1], 0));
                cases.add(Arguments.of(format, size[0], size[1], 32));
            }
        }
        return cases.stream();
    }

    /** Returns the tight stride of a format, rounded up so a planar chroma row fits in half of it. */
    private static int tightStride(V4l2PixelFormat format, int width) {
        var chromaWidth = (width + 1) / 2;
        return format.kind() == V4l2PixelFormat.Kind.PACKED ? 4 * chromaWidth : 2 * chromaWidth;
    }

    /** Computes the expected 4:2:0 output of a raw frame, sample by sample. */
    private static byte[][] reference(V4l2PixelFormat format, byte[] frame, int width, int height, int stride) {
        var outWidth = width & ~1;
        var outHeight = height & ~1;
        var chroma = outWidth / 2;
        var y = new byte[outWidth * outHeight];
        var u = new byte[chroma * (outHeight / 2)];
        var v = new byte[chroma * (outHeight / 2)];
        var chromaHeight = (height + 1) / 2;
        for (var row = 0; row < outHeight; row++) {
            for (var x = 0; x < outWidth; x++) {
                y[row * outWidth + x] = switch (format.kind()) {
                    case PACKED -> frame[row * stride + 4 * (x / 2) + (format == V4l2PixelFormat.UYVY || format == V4l2PixelFormat.VYUY ? 1 : 0) + 2 * (x % 2)];
                    default -> frame[row * stride + x];
                };
            }
        }
        for (var row = 0; row < outHeight / 2; row++) {
            for (var k = 0; k < chroma; k++) {
                int cb;
                int cr;
                switch (format) {
                    case YUV420, YVU420 -> {
                        var first = stride * height + row * (stride / 2) + k;
                        var second = first + (stride / 2) * chromaHeight;
                        cb = frame[format == V4l2PixelFormat.YUV420 ? first : second] & 0xFF;
                        cr = frame[format == V4l2PixelFormat.YUV420 ? second : first] & 0xFF;
                    }
                    case NV12, NV21 -> {
                        var pair = stride * height + row * stride + 2 * k;
                        cb = frame[pair + (format == V4l2PixelFormat.NV12 ? 0 : 1)] & 0xFF;
                        cr = frame[pair + (format == V4l2PixelFormat.NV12 ? 1 : 0)] & 0xFF;
                    }
                    default -> {
                        int cbAt = switch (format) {
                            case YUYV -> 1;
                            case YVYU -> 3;
                            case UYVY -> 0;
                            default -> 2;
                        };
                        int crAt = switch (format) {
                            case YUYV -> 3;
                            case YVYU -> 1;
                            case UYVY -> 2;
                            default -> 0;
                        };
                        var upper = 2 * row * stride + 4 * k;
                        var lower = upper + stride;
                        cb = ((frame[upper + cbAt] & 0xFF) + (frame[lower + cbAt] & 0xFF) + 1) >> 1;
                        cr = ((frame[upper + crAt] & 0xFF) + (frame[lower + crAt] & 0xFF) + 1) >> 1;
                    }
                }
                u[row * chroma + k] = (byte) cb;
                v[row * chroma + k] = (byte) cr;
            }
        }
        return new byte[][]{y, u, v};
    }

    private static byte[][] planes(YuvPicture picture) {
        var y = new byte[picture.width() * picture.height()];
        for (var row = 0; row < picture.height(); row++) {
            System.arraycopy(picture.y(), row * picture.yStride(), y, row * picture.width(), picture.width());
        }
        var chroma = picture.chromaWidth();
        var u = new byte[chroma * picture.chromaHeight()];
        var v = new byte[chroma * picture.chromaHeight()];
        for (var row = 0; row < picture.chromaHeight(); row++) {
            System.arraycopy(picture.u(), row * picture.uvStride(), u, row * chroma, chroma);
            System.arraycopy(picture.v(), row * picture.uvStride(), v, row * chroma, chroma);
        }
        return new byte[][]{y, u, v};
    }

    @ParameterizedTest(name = "{0} {1}x{2} padding {3}")
    @MethodSource("rawCases")
    @DisplayName("raw frames convert exactly like the reference")
    void raw(V4l2PixelFormat format, int width, int height, int padding) {
        var stride = tightStride(format, width) + padding;
        for (var kernel : kernels()) {
            var converter = new FrameConverter(format, width, height, padding == 0 ? 0 : stride, ColorInfo.BT601_LIMITED, kernel);
            var frame = new byte[converter.requiredBytes() + 5];
            new SplittableRandom(width * 31L + height).nextBytes(frame);
            var picture = converter.convert(frame, converter.requiredBytes());
            assertNotNull(picture);
            assertEquals(width & ~1, picture.width());
            assertEquals(height & ~1, picture.height());
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            assertEquals(ColorInfo.BT601_LIMITED, picture.color());
            var expected = reference(format, frame, width, height, stride);
            var actual = planes(picture);
            assertArrayEquals(expected[0], actual[0], "luma");
            assertArrayEquals(expected[1], actual[1], "cb");
            assertArrayEquals(expected[2], actual[2], "cr");
        }
    }

    @Test
    @DisplayName("the required size follows the V4L2 plane layout")
    void requiredBytes() {
        var kernel = new ScalarConversionKernel();
        assertEquals(640 * 480 * 2, new FrameConverter(V4l2PixelFormat.YUYV, 640, 480, 0, ColorInfo.BT601_LIMITED, kernel).requiredBytes());
        assertEquals(640 * 480 * 3 / 2, new FrameConverter(V4l2PixelFormat.NV12, 640, 480, 0, ColorInfo.BT601_LIMITED, kernel).requiredBytes());
        assertEquals(640 * 480 * 3 / 2, new FrameConverter(V4l2PixelFormat.YUV420, 640, 480, 640, ColorInfo.BT601_LIMITED, kernel).requiredBytes());
        assertEquals(1, new FrameConverter(V4l2PixelFormat.MJPEG, 640, 480, 0, ColorInfo.JPEG, kernel).requiredBytes());
    }

    @Test
    @DisplayName("the target picture is reused between frames")
    void reuse() {
        var converter = new FrameConverter(V4l2PixelFormat.NV12, 16, 16, 0, ColorInfo.BT601_LIMITED, new ScalarConversionKernel());
        var frame = new byte[converter.requiredBytes()];
        assertSame(converter.convert(frame, frame.length), converter.convert(frame, frame.length));
    }

    @Nested
    @DisplayName("validation")
    class Validation {
        @Test
        @DisplayName("short frames are rejected without failing")
        void shortFrame() {
            var converter = new FrameConverter(V4l2PixelFormat.YUYV, 64, 48, 0, ColorInfo.BT601_LIMITED, new ScalarConversionKernel());
            assertNull(converter.convert(new byte[converter.requiredBytes() - 1], converter.requiredBytes() - 1));
            assertNull(converter.convert(new byte[16], 17));
        }

        @Test
        @DisplayName("a stride that cannot hold a row raises Unsupported")
        void narrowStride() {
            assertThrows(MediaException.Unsupported.class,
                    () -> new FrameConverter(V4l2PixelFormat.YUYV, 64, 48, 100, ColorInfo.BT601_LIMITED, new ScalarConversionKernel()));
            assertThrows(MediaException.Unsupported.class,
                    () -> new FrameConverter(V4l2PixelFormat.YUV420, 64, 48, 63, ColorInfo.BT601_LIMITED, new ScalarConversionKernel()));
        }

        @Test
        @DisplayName("degenerate and oversized frames are rejected")
        void sizes() {
            assertThrows(MediaException.Unsupported.class,
                    () -> new FrameConverter(V4l2PixelFormat.NV12, 1, 1, 0, ColorInfo.BT601_LIMITED, new ScalarConversionKernel()));
            assertThrows(MediaException.Malformed.class,
                    () -> new FrameConverter(V4l2PixelFormat.NV12, 0, 480, 0, ColorInfo.BT601_LIMITED, new ScalarConversionKernel()));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> new FrameConverter(V4l2PixelFormat.NV12, 20_000, 480, 0, ColorInfo.BT601_LIMITED, new ScalarConversionKernel()));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> new FrameConverter(V4l2PixelFormat.YUYV, 64, 48, Integer.MAX_VALUE / 16, ColorInfo.BT601_LIMITED, new ScalarConversionKernel()));
        }
    }

    @Nested
    @DisplayName("Motion-JPEG")
    class Mjpeg {
        private YuvPicture convert(byte[] jpeg, int width, int height) {
            var converter = new FrameConverter(V4l2PixelFormat.MJPEG, width, height, 0, ColorInfo.JPEG, new ScalarConversionKernel());
            var frame = new byte[jpeg.length + 64];
            System.arraycopy(jpeg, 0, frame, 0, jpeg.length);
            return converter.convert(frame, jpeg.length);
        }

        @Test
        @DisplayName("4:2:2 frames without tables have their chroma rows averaged")
        void yuv422() {
            var jpeg = MotionJpegTest.tablelessFrame(64, 48, ChromaFormat.YUV422);
            var picture = convert(jpeg, 64, 48);
            assertNotNull(picture);
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            assertEquals(ColorInfo.JPEG, picture.color());
            var decoded = (YuvPicture) new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length);
            var actual = planes(picture);
            for (var row = 0; row < 24; row++) {
                for (var k = 0; k < 32; k++) {
                    var upper = decoded.u()[2 * row * decoded.uvStride() + k] & 0xFF;
                    var lower = decoded.u()[(2 * row + 1) * decoded.uvStride() + k] & 0xFF;
                    assertEquals((upper + lower + 1) >> 1, actual[1][row * 32 + k] & 0xFF);
                }
            }
            for (var row = 0; row < 48; row++) {
                for (var x = 0; x < 64; x++) {
                    assertEquals(decoded.y()[row * decoded.yStride() + x], actual[0][row * 64 + x]);
                }
            }
        }

        @Test
        @DisplayName("4:2:0 frames keep their planes and odd sizes are cropped")
        void yuv420() {
            var jpeg = MotionJpegTest.tablelessFrame(35, 19, ChromaFormat.YUV420);
            var picture = convert(jpeg, 35, 19);
            assertNotNull(picture);
            assertEquals(34, picture.width());
            assertEquals(18, picture.height());
        }

        @Test
        @DisplayName("4:4:4 and gray frames are reduced to 4:2:0")
        void otherLayouts() {
            var full = JpegEncoder.encode(MotionJpegTest.gradient(32, 32), JpegEncoderOptions.builder().quality(90).chromaFormat(ChromaFormat.YUV444).optimizeHuffman(false).build());
            var picture = convert(full, 32, 32);
            assertNotNull(picture);
            var reference = ImageOps.toYuv(new MjpegDecoder().decodeFrame(full, 0, full.length), ChromaFormat.YUV420, ColorInfo.JPEG);
            assertArrayEquals(planes(reference)[1], planes(picture)[1]);
            var gray = JpegEncoder.encode(MotionJpegTest.gradient(32, 32), JpegEncoderOptions.builder().quality(90).chromaFormat(ChromaFormat.MONOCHROME).optimizeHuffman(false).build());
            var grayPicture = convert(gray, 32, 32);
            assertNotNull(grayPicture);
            for (var sample : planes(grayPicture)[2]) {
                assertEquals((byte) 128, sample);
            }
        }

        @Test
        @DisplayName("corrupt, empty and wrongly sized frames are skipped")
        void unusable() {
            var jpeg = MotionJpegTest.tablelessFrame(32, 32, ChromaFormat.YUV422);
            assertNull(convert(jpeg, 64, 32));
            assertNull(convert(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}, 32, 32));
            var converter = new FrameConverter(V4l2PixelFormat.MJPEG, 32, 32, 0, ColorInfo.JPEG, new ScalarConversionKernel());
            assertNull(converter.convert(new byte[16], 0));
            var garbage = new byte[4096];
            new SplittableRandom(3).nextBytes(garbage);
            assertNull(converter.convert(garbage, garbage.length));
        }
    }
}
