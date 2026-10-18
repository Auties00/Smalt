package com.github.auties00.smalt.internal.plane;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the plane helpers: strided copies and edge replication, the picture copy with recycling,
 * and the 16-to-8-bit narrowing twins, whose vector kernel must equal the scalar reference for
 * every 16-bit input and whose results are pinned against the narrowing copies they replace.
 */
class PlaneHelpersTest {
    @Nested
    @DisplayName("Planes")
    class Copies {
        @Test
        @DisplayName("copies strided and contiguous rectangles of bytes and shorts")
        void copy() {
            var src = new byte[40];
            for (var i = 0; i < src.length; i++) {
                src[i] = (byte) i;
            }
            var dst = new byte[40];
            Planes.copy(src, 2, 10, dst, 1, 8, 5, 3);
            assertArrayEquals(Arrays.copyOfRange(src, 2, 7), Arrays.copyOfRange(dst, 1, 6));
            assertArrayEquals(Arrays.copyOfRange(src, 22, 27), Arrays.copyOfRange(dst, 17, 22));
            assertEquals(0, dst[6]);
            var contiguous = new short[12];
            var shorts = new short[12];
            for (var i = 0; i < shorts.length; i++) {
                shorts[i] = (short) (1000 + i);
            }
            Planes.copy(shorts, 0, 4, contiguous, 0, 4, 4, 3);
            assertArrayEquals(shorts, contiguous);
        }

        @Test
        @DisplayName("replicates the edge samples into the side, top and bottom borders")
        void extend() {
            var stride = 8;
            var plane = new byte[stride * 6];
            for (var row = 2; row < 4; row++) {
                for (var x = 0; x < 4; x++) {
                    plane[row * stride + 2 + x] = (byte) (10 * row + x);
                }
            }
            Planes.extendSides(plane, 2 * stride + 2, stride, 4, 2, 2, 2);
            assertArrayEquals(new byte[]{20, 20, 20, 21, 22, 23, 23, 23}, Arrays.copyOfRange(plane, 16, 24));
            Planes.extendTop(plane, 2 * stride, stride, stride, 2);
            Planes.extendBottom(plane, 3 * stride, stride, stride, 2);
            assertArrayEquals(Arrays.copyOfRange(plane, 16, 24), Arrays.copyOfRange(plane, 0, 8));
            assertArrayEquals(Arrays.copyOfRange(plane, 24, 32), Arrays.copyOfRange(plane, 40, 48));
            var wide = new short[]{0, 0, 7, 9, 0, 0};
            Planes.extendSides(wide, 2, 6, 2, 1, 2, 2);
            assertArrayEquals(new short[]{7, 7, 7, 9, 9, 9}, wide);
            var rows = new short[]{0, 0, 5, 6, 0, 0};
            Planes.extendTop(rows, 2, 2, 2, 1);
            Planes.extendBottom(rows, 2, 2, 2, 1);
            assertArrayEquals(new short[]{5, 6, 5, 6, 5, 6}, rows);
        }
    }

    @Nested
    @DisplayName("PictureCopy")
    class Pictures {
        @Test
        @DisplayName("copies every plane of a planar picture with alpha and recycles a picture of the same shape")
        void yuv() {
            var random = new Random(1);
            var source = YuvPicture.allocate(7, 5, ChromaFormat.YUV420, true, ColorInfo.BT709_LIMITED);
            random.nextBytes(source.y());
            random.nextBytes(source.u());
            random.nextBytes(source.v());
            random.nextBytes(source.alpha());
            var copy = (YuvPicture) PictureCopy.copy(source, null);
            assertNotSame(source.y(), copy.y());
            assertArrayEquals(source.y(), copy.y());
            assertArrayEquals(source.u(), copy.u());
            assertArrayEquals(source.v(), copy.v());
            assertArrayEquals(source.alpha(), copy.alpha());
            assertSame(copy, PictureCopy.copy(source, copy));
            var other = YuvPicture.allocate(7, 5, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            assertNotSame(other, PictureCopy.copy(source, other));
            var otherColor = YuvPicture.allocate(7, 5, ChromaFormat.YUV420, true, ColorInfo.BT601_LIMITED);
            assertNotSame(otherColor, PictureCopy.copy(source, otherColor));
        }

        @Test
        @DisplayName("copies deep and packed pictures and refuses a recycled picture of another depth or size")
        void others() {
            var deep = Yuv16Picture.allocate(4, 4, ChromaFormat.YUV444, 10, false, ColorInfo.BT709_LIMITED);
            deep.y()[3] = 1023;
            var deepCopy = (Yuv16Picture) PictureCopy.copy(deep, null);
            assertArrayEquals(deep.y(), deepCopy.y());
            var deeper = Yuv16Picture.allocate(4, 4, ChromaFormat.YUV444, 12, false, ColorInfo.BT709_LIMITED);
            assertNotSame(deeper, PictureCopy.copy(deep, deeper));
            var rgba = RgbaPicture.allocate(3, 2, true);
            rgba.pixels()[5] = 9;
            var rgbaCopy = (RgbaPicture) PictureCopy.copy(rgba, RgbaPicture.allocate(3, 2, true));
            assertArrayEquals(rgba.pixels(), rgbaCopy.pixels());
            assertNotSame(rgba, PictureCopy.copy(rgba, RgbaPicture.allocate(2, 3, true)));
            var rgba16 = Rgba16Picture.allocate(2, 2, false);
            rgba16.pixels()[1] = 300;
            assertArrayEquals(rgba16.pixels(), ((Rgba16Picture) PictureCopy.copy(rgba16, null)).pixels());
        }
    }

    @Nested
    @DisplayName("Narrow")
    class Narrowing {
        @ParameterizedTest(name = "shift {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("vector kernel equals the scalar reference for every 16-bit sample")
        void equivalence(int shift) {
            var vector = VectorNarrow.create();
            var samples = new short[65536];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (short) i;
            }
            var width = 256;
            var height = samples.length / width;
            var scalarOut = new byte[samples.length];
            var vectorOut = new byte[samples.length];
            Narrow.SCALAR.narrow(samples, 0, width, scalarOut, 0, width, width, height, shift);
            vector.narrow(samples, 0, width, vectorOut, 0, width, width, height, shift);
            assertArrayEquals(scalarOut, vectorOut);
            for (var i = 0; i < 65536; i += 97) {
                var half = shift == 0 ? 0 : 1 << (shift - 1);
                assertEquals((byte) Math.min((i + half) >> shift, 255), scalarOut[i]);
            }
        }

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {1, 15, 16, 17, 33, 100})
        @DisplayName("handles offsets, strides and row ends that do not fill a vector")
        void strided(int width) {
            var random = new Random(width);
            var height = 5;
            var srcStride = width + 3;
            var dstStride = width + 7;
            var src = new short[2 + srcStride * height];
            for (var i = 0; i < src.length; i++) {
                src[i] = (short) random.nextInt(1024);
            }
            var scalar = new byte[1 + dstStride * height];
            var vector = new byte[1 + dstStride * height];
            Narrow.SCALAR.narrow(src, 2, srcStride, scalar, 1, dstStride, width, height, 2);
            VectorNarrow.create().narrow(src, 2, srcStride, vector, 1, dstStride, width, height, 2);
            assertArrayEquals(scalar, vector);
            assertEquals(0, scalar[0]);
        }

        @ParameterizedTest(name = "depth {0}")
        @ValueSource(ints = {9, 10, 12})
        @DisplayName("equals the AV1 and VP9 narrowing for in-range samples and the high-depth narrowing for all")
        void formerKernels(int depth) {
            var random = new Random(depth);
            var shift = depth - 8;
            var samples = new short[4096];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (short) (i < 2048 ? random.nextInt(1 << depth) : random.nextInt(65536));
            }
            var out = new byte[samples.length];
            Narrow.plane(samples, 0, 64, out, 0, 64, 64, 64, shift, true);
            var round = 1 << (shift - 1);
            for (var i = 0; i < samples.length; i++) {
                if (i < 2048) {
                    assertEquals((byte) Math.min((samples[i] + round) >> shift, 255), out[i], "AV1 sample " + i);
                }
                assertEquals((byte) Math.min(((samples[i] & 0xFFFF) + round) >> shift, 255), out[i], "deep sample " + i);
            }
        }

        @Test
        @DisplayName("gives the same bytes in one band or in parallel bands")
        void parallel() {
            var random = new Random(4);
            var width = 1920;
            var height = 1080;
            var src = new short[width * height];
            for (var i = 0; i < src.length; i++) {
                src[i] = (short) random.nextInt(1024);
            }
            var serial = new byte[src.length];
            var banded = new byte[src.length];
            Narrow.plane(src, 0, width, serial, 0, width, width, height, 2, false);
            Narrow.plane(src, 0, width, banded, 0, width, width, height, 2, true);
            assertArrayEquals(serial, banded);
        }
    }
}
