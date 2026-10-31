package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.ScalarConversionKernel;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Converts random frames of the 4:2:2, 4:4:4, R'G'B' and luma-only formats against per-sample
 * references written from the V4L2 format definitions, multi-planar buffers against their
 * contiguous twins, and every field order against the progressive frame it was cut from.
 */
@DisplayName("V4L2 frame layouts")
class V4l2FrameLayoutTest {
    private static final ColorInfo LIMITED_601 = ColorInfo.BT601_LIMITED;

    private static List<ConversionKernel> kernels() {
        var kernels = new ArrayList<ConversionKernel>();
        kernels.add(new ScalarConversionKernel());
        if (Simd.ENABLED) {
            kernels.add(ConversionKernel.INSTANCE);
        }
        return kernels;
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

    private static void assertSamePlanes(byte[][] expected, YuvPicture actual, String what) {
        var planes = planes(actual);
        assertArrayEquals(expected[0], planes[0], what + " luma");
        assertArrayEquals(expected[1], planes[1], what + " cb");
        assertArrayEquals(expected[2], planes[2], what + " cr");
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }

    /** Returns the tight luma stride of a contiguous format, as the V4L2 definitions size it. */
    private static int tightStride(V4l2PixelFormat format, int width) {
        var chromaWidth = (width + 1) / 2;
        return switch (format.kind()) {
            case PLANAR, PLANAR_422, SEMI_PLANAR, SEMI_PLANAR_422 -> 2 * chromaWidth;
            case SEMI_PLANAR_444 -> width;
            case RGB, LUMA -> format.bytesPerPixel() * width;
            default -> 4 * chromaWidth;
        };
    }

    /** Returns the size of a contiguous frame: the luma plane followed by its chroma planes. */
    private static int frameSize(V4l2PixelFormat format, int width, int height, int stride) {
        return switch (format.kind()) {
            case PLANAR -> stride * height + 2 * (stride / 2) * ((height + 1) / 2);
            case PLANAR_422 -> stride * height + 2 * (stride / 2) * height;
            case SEMI_PLANAR -> stride * height + stride * ((height + 1) / 2);
            case SEMI_PLANAR_422 -> 2 * stride * height;
            case SEMI_PLANAR_444 -> 3 * stride * height;
            default -> stride * height;
        };
    }

    /** Computes the 4:2:0 output of a contiguous Y'CbCr frame sample by sample from the format definitions. */
    private static byte[][] yuvReference(V4l2PixelFormat format, byte[] frame, int width, int height, int stride) {
        var outWidth = width & ~1;
        var outHeight = height & ~1;
        var chroma = outWidth / 2;
        var y = new byte[outWidth * outHeight];
        var u = new byte[chroma * (outHeight / 2)];
        var v = new byte[chroma * (outHeight / 2)];
        var cbFirst = format.cbIndex() == 0;
        for (var row = 0; row < outHeight; row++) {
            for (var x = 0; x < outWidth; x++) {
                if (format.kind() == V4l2PixelFormat.Kind.LUMA) {
                    if (format.bytesPerPixel() == 1) {
                        y[row * outWidth + x] = frame[row * stride + x];
                    } else {
                        var at = row * stride + 2 * x;
                        var value = format.bigEndian() ? u8(frame[at]) << 8 | u8(frame[at + 1]) : u8(frame[at + 1]) << 8 | u8(frame[at]);
                        y[row * outWidth + x] = (byte) Math.min(255, value >> (format.significantBits() - 8));
                    }
                } else {
                    y[row * outWidth + x] = frame[row * stride + x];
                }
            }
        }
        var lumaSize = stride * height;
        for (var row = 0; row < outHeight / 2; row++) {
            for (var k = 0; k < chroma; k++) {
                int cb;
                int cr;
                switch (format.kind()) {
                    case PLANAR_422 -> {
                        var half = stride / 2;
                        var first = lumaSize;
                        var second = lumaSize + half * height;
                        var cbBase = cbFirst ? first : second;
                        var crBase = cbFirst ? second : first;
                        cb = (u8(frame[cbBase + 2 * row * half + k]) + u8(frame[cbBase + (2 * row + 1) * half + k]) + 1) >> 1;
                        cr = (u8(frame[crBase + 2 * row * half + k]) + u8(frame[crBase + (2 * row + 1) * half + k]) + 1) >> 1;
                    }
                    case SEMI_PLANAR_422 -> {
                        var upper = lumaSize + 2 * row * stride + 2 * k;
                        var lower = upper + stride;
                        var a = (u8(frame[upper]) + u8(frame[lower]) + 1) >> 1;
                        var b = (u8(frame[upper + 1]) + u8(frame[lower + 1]) + 1) >> 1;
                        cb = cbFirst ? a : b;
                        cr = cbFirst ? b : a;
                    }
                    case SEMI_PLANAR_444 -> {
                        var upper = lumaSize + 2 * row * (2 * stride) + 4 * k;
                        var lower = upper + 2 * stride;
                        var a = (u8(frame[upper]) + u8(frame[upper + 2]) + u8(frame[lower]) + u8(frame[lower + 2]) + 2) >> 2;
                        var b = (u8(frame[upper + 1]) + u8(frame[upper + 3]) + u8(frame[lower + 1]) + u8(frame[lower + 3]) + 2) >> 2;
                        cb = cbFirst ? a : b;
                        cr = cbFirst ? b : a;
                    }
                    case LUMA -> {
                        cb = 128;
                        cr = 128;
                    }
                    default -> throw new AssertionError(format);
                }
                u[row * chroma + k] = (byte) cb;
                v[row * chroma + k] = (byte) cr;
            }
        }
        return new byte[][]{y, u, v};
    }

    /** Decodes a contiguous R'G'B' frame to RGBA from the format definitions and converts it with ImageOps. */
    private static byte[][] rgbReference(V4l2PixelFormat format, byte[] frame, int width, int height, int stride,
                                         boolean limited, ColorInfo color) {
        var outWidth = width & ~1;
        var outHeight = height & ~1;
        var rgba = RgbaPicture.allocate(outWidth, outHeight, false);
        for (var row = 0; row < outHeight; row++) {
            for (var x = 0; x < outWidth; x++) {
                int r;
                int g;
                int b;
                var at = row * stride + format.bytesPerPixel() * x;
                switch (format) {
                    case RGB565 -> {
                        var value = u8(frame[at]) | u8(frame[at + 1]) << 8;
                        var r5 = value >> 11;
                        var g6 = (value >> 5) & 63;
                        var b5 = value & 31;
                        r = (int) Math.round(r5 * 255.0 / 31);
                        g = (int) Math.round(g6 * 255.0 / 63);
                        b = (int) Math.round(b5 * 255.0 / 31);
                    }
                    case RGB24 -> {
                        r = u8(frame[at]);
                        g = u8(frame[at + 1]);
                        b = u8(frame[at + 2]);
                    }
                    case BGR24 -> {
                        b = u8(frame[at]);
                        g = u8(frame[at + 1]);
                        r = u8(frame[at + 2]);
                    }
                    case XRGB32, ARGB32 -> {
                        r = u8(frame[at + 1]);
                        g = u8(frame[at + 2]);
                        b = u8(frame[at + 3]);
                    }
                    case XBGR32, ABGR32 -> {
                        b = u8(frame[at]);
                        g = u8(frame[at + 1]);
                        r = u8(frame[at + 2]);
                    }
                    case RGBX32, RGBA32 -> {
                        r = u8(frame[at]);
                        g = u8(frame[at + 1]);
                        b = u8(frame[at + 2]);
                    }
                    case BGRX32, BGRA32 -> {
                        b = u8(frame[at + 1]);
                        g = u8(frame[at + 2]);
                        r = u8(frame[at + 3]);
                    }
                    default -> throw new AssertionError(format);
                }
                if (limited) {
                    r = expand(r);
                    g = expand(g);
                    b = expand(b);
                }
                var pixel = 4 * (row * outWidth + x);
                rgba.pixels()[pixel] = (byte) r;
                rgba.pixels()[pixel + 1] = (byte) g;
                rgba.pixels()[pixel + 2] = (byte) b;
                rgba.pixels()[pixel + 3] = (byte) 255;
            }
        }
        return planes(ImageOps.toYuv(rgba, ChromaFormat.YUV420, color));
    }

    /** Expands a limited-range R'G'B' sample (16 to 235) to full range, rounding to nearest. */
    private static int expand(int sample) {
        return (int) Math.max(0, Math.min(255, Math.round((sample - 16) * 255.0 / 219.0)));
    }

    private static final List<V4l2PixelFormat> YUV = List.of(V4l2PixelFormat.NV16, V4l2PixelFormat.NV61, V4l2PixelFormat.NV24,
            V4l2PixelFormat.NV42, V4l2PixelFormat.YUV422P, V4l2PixelFormat.GREY, V4l2PixelFormat.Y16, V4l2PixelFormat.Y16_BE,
            V4l2PixelFormat.Y12, V4l2PixelFormat.Y10);

    private static final List<V4l2PixelFormat> RGB = List.of(V4l2PixelFormat.RGB24, V4l2PixelFormat.BGR24, V4l2PixelFormat.XRGB32,
            V4l2PixelFormat.ARGB32, V4l2PixelFormat.XBGR32, V4l2PixelFormat.ABGR32, V4l2PixelFormat.RGBX32, V4l2PixelFormat.RGBA32,
            V4l2PixelFormat.BGRX32, V4l2PixelFormat.BGRA32, V4l2PixelFormat.RGB565);

    private static final int[][] SIZES = {{2, 2}, {8, 6}, {66, 34}, {130, 7}, {7, 5}, {33, 17}};

    static Stream<Arguments> yuvCases() {
        var cases = new ArrayList<Arguments>();
        for (var format : YUV) {
            for (var size : SIZES) {
                cases.add(Arguments.of(format, size[0], size[1], 0));
                cases.add(Arguments.of(format, size[0], size[1], 24));
            }
        }
        return cases.stream();
    }

    static Stream<Arguments> rgbCases() {
        var cases = new ArrayList<Arguments>();
        for (var format : RGB) {
            for (var size : SIZES) {
                for (var limited : new boolean[]{false, true}) {
                    cases.add(Arguments.of(format, size[0], size[1], limited ? 12 : 0, limited));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}x{2} padding {3}")
    @MethodSource("yuvCases")
    @DisplayName("4:2:2, 4:4:4 and luma-only frames convert exactly like the reference")
    void yuv(V4l2PixelFormat format, int width, int height, int padding) {
        var stride = tightStride(format, width) + padding;
        for (var kernel : kernels()) {
            var converter = new FrameConverter(format, width, height, padding == 0 ? 0 : stride, LIMITED_601, kernel);
            var frame = new byte[frameSize(format, width, height, stride) + 3];
            new SplittableRandom(format.ordinal() * 1000L + width * 31L + height).nextBytes(frame);
            var picture = converter.convert(frame, converter.requiredBytes());
            assertNotNull(picture);
            assertEquals(LIMITED_601, picture.color());
            assertSamePlanes(yuvReference(format, frame, width, height, stride), picture, format + " " + kernel.getClass().getSimpleName());
        }
    }

    @ParameterizedTest(name = "{0} {1}x{2} padding {3} limited {4}")
    @MethodSource("rgbCases")
    @DisplayName("R'G'B' frames convert exactly like ImageOps of the decoded pixels")
    void rgb(V4l2PixelFormat format, int width, int height, int padding, boolean limited) {
        var stride = tightStride(format, width) + padding;
        var input = new ColorInfo(MatrixCoefficients.BT601, limited ? ColorRange.LIMITED : ColorRange.FULL,
                ColorPrimaries.BT709, TransferCharacteristics.SRGB);
        var output = new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED, ColorPrimaries.BT709, TransferCharacteristics.SRGB);
        for (var kernel : kernels()) {
            var converter = new FrameConverter(new V4l2FrameLayout(format, width, height, V4l2.FIELD_NONE, padding == 0 ? 0 : stride),
                    input, kernel);
            var frame = new byte[stride * height];
            new SplittableRandom(format.ordinal() * 7919L + width * 31L + height).nextBytes(frame);
            assertEquals(stride * (height - 1) + format.bytesPerPixel() * width, converter.requiredBytes());
            var picture = converter.convert(frame, frame.length);
            assertNotNull(picture);
            assertEquals(output, picture.color());
            assertSamePlanes(rgbReference(format, frame, width, height, stride, limited, output), picture,
                    format + " " + kernel.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("luma-only frames keep their most significant bits and clamp stray high bits")
    void lumaBits() {
        var converter = new FrameConverter(V4l2PixelFormat.Y10, 2, 2, 0, LIMITED_601, new ScalarConversionKernel());
        var frame = new byte[]{(byte) 0xFF, 0x03, 0x00, 0x02, 0x04, 0x00, (byte) 0xFF, (byte) 0xFF};
        var picture = converter.convert(frame, frame.length);
        assertEquals(255, u8(picture.y()[0]), "1023 >> 2");
        assertEquals(128, u8(picture.y()[1]), "512 >> 2");
        assertEquals(1, u8(picture.y()[picture.yStride()]), "4 >> 2");
        assertEquals(255, u8(picture.y()[picture.yStride() + 1]), "a value beyond 10 bits is clamped");
        assertEquals(128, u8(picture.u()[0]));
        assertEquals(128, u8(picture.v()[0]));
    }

    /** The contiguous twin of every multi-planar format. */
    private static V4l2PixelFormat twin(V4l2PixelFormat format) {
        return switch (format) {
            case YUV420M -> V4l2PixelFormat.YUV420;
            case YVU420M -> V4l2PixelFormat.YVU420;
            case NV12M -> V4l2PixelFormat.NV12;
            case NV21M -> V4l2PixelFormat.NV21;
            case NV16M -> V4l2PixelFormat.NV16;
            case NV61M -> V4l2PixelFormat.NV61;
            case YUV422M -> V4l2PixelFormat.YUV422P;
            case YVU422M -> null;
            default -> throw new AssertionError(format);
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = V4l2PixelFormat.class, names = {"YUV420M", "YVU420M", "NV12M", "NV21M", "NV16M", "NV61M", "YUV422M"})
    @DisplayName("multi-planar buffers with their own strides and data offsets convert like their contiguous twin")
    void multiPlanar(V4l2PixelFormat format) {
        var width = 46;
        var height = 30;
        var contiguous = twin(format);
        var reference = new V4l2FrameLayout(contiguous, width, height, V4l2.FIELD_NONE, 0);
        var source = new byte[reference.frameBytes()];
        new SplittableRandom(format.ordinal()).nextBytes(source);
        var expected = planes(new FrameConverter(reference, LIMITED_601, new ScalarConversionKernel()).convert(source, source.length));
        var planes = format.memoryPlanes();
        var strides = new int[planes];
        var memory = new MemorySegment[planes];
        var dataOffset = new long[planes];
        var used = new long[planes];
        for (var plane = 0; plane < planes; plane++) {
            strides[plane] = reference.rowBytes(plane) + 16 * (plane + 1);
            dataOffset[plane] = 64L * plane;
            var rows = reference.frameRows(plane);
            var bytes = new byte[(int) dataOffset[plane] + strides[plane] * rows];
            new SplittableRandom(plane).nextBytes(bytes);
            for (var row = 0; row < rows; row++) {
                System.arraycopy(source, reference.frameOffset(plane) + row * reference.stride(plane), bytes,
                        (int) dataOffset[plane] + row * strides[plane], reference.rowBytes(plane));
            }
            memory[plane] = MemorySegment.ofArray(bytes);
            used[plane] = bytes.length;
        }
        var layout = new V4l2FrameLayout(format, width, height, V4l2.FIELD_NONE, strides);
        for (var plane = 0; plane < planes; plane++) {
            assertEquals((long) strides[plane] * (reference.frameRows(plane) - 1) + reference.rowBytes(plane), layout.requiredBytes(plane));
        }
        var frame = new byte[layout.frameBytes()];
        assertEquals(layout.frameBytes(), layout.copy(memory, dataOffset, used, false, frame));
        for (var kernel : kernels()) {
            var picture = new FrameConverter(layout, LIMITED_601, kernel).convert(frame, frame.length);
            assertNotNull(picture);
            assertArrayEquals(expected[0], planes(picture)[0]);
            assertArrayEquals(expected[1], planes(picture)[1]);
            assertArrayEquals(expected[2], planes(picture)[2]);
        }
        used[planes - 1] = dataOffset[planes - 1] + layout.requiredBytes(planes - 1) - 1;
        assertEquals(-1, layout.copy(memory, dataOffset, used, false, frame), "a plane one byte short is rejected");
    }

    @Test
    @DisplayName("YVU422M swaps the chroma planes of YUV422M")
    void yvu422m() {
        var width = 8;
        var height = 4;
        var strides = new int[]{8, 4, 4};
        var y = new byte[32];
        var first = new byte[16];
        var second = new byte[16];
        java.util.Arrays.fill(first, (byte) 50);
        java.util.Arrays.fill(second, (byte) 200);
        var memory = new MemorySegment[]{MemorySegment.ofArray(y), MemorySegment.ofArray(first), MemorySegment.ofArray(second)};
        var layout = new V4l2FrameLayout(V4l2PixelFormat.YVU422M, width, height, V4l2.FIELD_NONE, strides);
        var frame = new byte[layout.frameBytes()];
        layout.copy(memory, new long[3], new long[]{32, 16, 16}, false, frame);
        var picture = new FrameConverter(layout, LIMITED_601, new ScalarConversionKernel()).convert(frame, frame.length);
        assertEquals(200, u8(picture.u()[0]));
        assertEquals(50, u8(picture.v()[0]));
    }

    @Nested
    @DisplayName("fields")
    class Fields {
        private static final int WIDTH = 34;
        private static final int HEIGHT = 24;

        static Stream<V4l2PixelFormat> formats() {
            return Stream.of(V4l2PixelFormat.YUYV, V4l2PixelFormat.NV12, V4l2PixelFormat.YUV420, V4l2PixelFormat.NV16,
                    V4l2PixelFormat.RGB24, V4l2PixelFormat.GREY, V4l2PixelFormat.XBGR32);
        }

        /** Returns the progressive frame of a contiguous format and its layout. */
        private static V4l2FrameLayout progressive(V4l2PixelFormat format) {
            return new V4l2FrameLayout(format, WIDTH, HEIGHT, V4l2.FIELD_NONE, 0);
        }

        private static byte[] random(V4l2FrameLayout layout, long seed) {
            var frame = new byte[layout.frameBytes()];
            new SplittableRandom(seed).nextBytes(frame);
            return frame;
        }

        /** Copies the rows of every plane of a progressive frame in a new order, given per plane by {@code order}. */
        private static byte[] reorder(V4l2FrameLayout layout, byte[] frame, java.util.function.IntBinaryOperator order) {
            var result = new byte[frame.length];
            for (var plane = 0; plane < layout.planes(); plane++) {
                var rows = layout.frameRows(plane);
                for (var row = 0; row < rows; row++) {
                    System.arraycopy(frame, layout.frameOffset(plane) + order.applyAsInt(row, rows) * layout.stride(plane), result,
                            layout.frameOffset(plane) + row * layout.stride(plane), layout.stride(plane));
                }
            }
            return result;
        }

        /** Cuts one field of a progressive frame into a buffer of the field-height layout. */
        private static byte[] field(V4l2FrameLayout frameLayout, V4l2FrameLayout fieldLayout, byte[] frame, int parity) {
            var buffer = new byte[fieldLayout.frameBytes() / (fieldLayout.alternate() ? 2 : 1)];
            var offset = 0;
            for (var plane = 0; plane < frameLayout.planes(); plane++) {
                var rows = frameLayout.frameRows(plane) / 2;
                for (var row = 0; row < rows; row++) {
                    System.arraycopy(frame, frameLayout.frameOffset(plane) + (2 * row + parity) * frameLayout.stride(plane), buffer,
                            offset + row * frameLayout.stride(plane), frameLayout.stride(plane));
                }
                offset += rows * frameLayout.stride(plane);
            }
            return buffer;
        }

        private static byte[][] convert(V4l2FrameLayout layout, byte[] frame) {
            var picture = new FrameConverter(layout, LIMITED_601, new ScalarConversionKernel()).convert(frame, frame.length);
            assertNotNull(picture);
            return planes(picture);
        }

        private static void assertSame(byte[][] expected, byte[][] actual, String what) {
            for (var plane = 0; plane < 3; plane++) {
                assertArrayEquals(expected[plane], actual[plane], what + " plane " + plane);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("formats")
        @DisplayName("sequential fields of either order are woven back into the progressive frame")
        void sequential(V4l2PixelFormat format) {
            var layout = progressive(format);
            var frame = random(layout, format.ordinal());
            var expected = convert(layout, frame);
            var topFirst = reorder(layout, frame, (row, rows) -> row < (rows + 1) / 2 ? 2 * row : 2 * (row - (rows + 1) / 2) + 1);
            var bottomFirst = reorder(layout, frame, (row, rows) -> row < rows / 2 ? 2 * row + 1 : 2 * (row - rows / 2));
            var tb = new V4l2FrameLayout(format, WIDTH, HEIGHT, V4l2.FIELD_SEQ_TB, 0);
            var bt = new V4l2FrameLayout(format, WIDTH, HEIGHT, V4l2.FIELD_SEQ_BT, 0);
            var memory = new MemorySegment[1];
            for (var test : new Object[][]{{tb, topFirst}, {bt, bottomFirst}}) {
                var fieldLayout = (V4l2FrameLayout) test[0];
                var buffer = (byte[]) test[1];
                memory[0] = MemorySegment.ofArray(buffer);
                var assembled = new byte[fieldLayout.frameBytes()];
                assertEquals(fieldLayout.frameBytes(), fieldLayout.copy(memory, new long[1], new long[]{buffer.length}, false, assembled));
                assertSame(expected, convert(fieldLayout, assembled), format + " " + fieldLayout.order());
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("formats")
        @DisplayName("alternating field buffers are woven into the progressive frame in either arrival order")
        void alternate(V4l2PixelFormat format) {
            var layout = progressive(format);
            var frame = random(layout, 100 + format.ordinal());
            var expected = convert(layout, frame);
            var alternate = new V4l2FrameLayout(format, WIDTH, HEIGHT / 2, V4l2.FIELD_ALTERNATE, 0);
            assertEquals(HEIGHT, alternate.height());
            var top = field(layout, alternate, frame, 0);
            var bottom = field(layout, alternate, frame, 1);
            for (var bottomFirst : new boolean[]{false, true}) {
                var assembled = new byte[alternate.frameBytes()];
                for (var isBottom : bottomFirst ? new boolean[]{true, false} : new boolean[]{false, true}) {
                    var buffer = isBottom ? bottom : top;
                    assertEquals(alternate.frameBytes(), alternate.copy(new MemorySegment[]{MemorySegment.ofArray(buffer)},
                            new long[1], new long[]{buffer.length}, isBottom, assembled));
                }
                assertSame(expected, convert(alternate, assembled), format + " bottom first " + bottomFirst);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("formats")
        @DisplayName("a single top or bottom field is line-doubled to the frame height")
        void single(V4l2PixelFormat format) {
            var layout = progressive(format);
            var frame = random(layout, 200 + format.ordinal());
            for (var parity = 0; parity < 2; parity++) {
                var fieldLayout = new V4l2FrameLayout(format, WIDTH, HEIGHT / 2, parity == 0 ? V4l2.FIELD_TOP : V4l2.FIELD_BOTTOM, 0);
                assertEquals(V4l2FrameLayout.RowOrder.DOUBLED, fieldLayout.order());
                var buffer = field(layout, fieldLayout, frame, parity);
                var finalParity = parity;
                var doubled = reorder(layout, frame, (row, rows) -> 2 * (row / 2) + finalParity);
                var assembled = new byte[fieldLayout.frameBytes()];
                fieldLayout.copy(new MemorySegment[]{MemorySegment.ofArray(buffer)}, new long[1], new long[]{buffer.length}, false, assembled);
                assertSame(convert(layout, doubled), convert(fieldLayout, assembled), format + " parity " + parity);
            }
        }

        @Test
        @DisplayName("a JPEG buffer is copied from its data offset")
        void jpegCopy() {
            var layout = new V4l2FrameLayout(V4l2PixelFormat.MJPEG, 64, 48, V4l2.FIELD_NONE, 0);
            var bytes = new byte[100];
            new SplittableRandom(5).nextBytes(bytes);
            var frame = new byte[100];
            assertEquals(60, layout.copy(new MemorySegment[]{MemorySegment.ofArray(bytes)}, new long[]{10}, new long[]{70}, false, frame));
            assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 10, 70), java.util.Arrays.copyOf(frame, 60));
            assertEquals(-1, layout.copy(new MemorySegment[]{MemorySegment.ofArray(bytes)}, new long[]{10}, new long[]{10}, false, frame));
        }

        @Test
        @DisplayName("an unknown field order and a mismatched plane count raise Unsupported")
        void invalid() {
            assertThrows(MediaException.Unsupported.class, () -> new V4l2FrameLayout(V4l2PixelFormat.YUYV, 64, 48, 10, 0));
            assertThrows(MediaException.Unsupported.class, () -> new V4l2FrameLayout(V4l2PixelFormat.NV12M, 64, 48, V4l2.FIELD_NONE, 0));
            assertThrows(MediaException.Unsupported.class, () -> new V4l2FrameLayout(V4l2PixelFormat.YUYV, 64, 1, V4l2.FIELD_NONE, 0));
            assertEquals(2, new V4l2FrameLayout(V4l2PixelFormat.YUYV, 64, 1, V4l2.FIELD_TOP, 0).height());
        }
    }
}
