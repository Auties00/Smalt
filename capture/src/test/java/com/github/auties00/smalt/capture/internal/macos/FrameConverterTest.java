package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.ScalarConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Feeds the converter heap segments laid out like {@code CVPixelBuffer} planes, with row padding
 * of arbitrary bytes per row, and compares every destination sample with a direct per-sample
 * reading of the source.
 */
@DisplayName("frame converter")
class FrameConverterTest {
    private static YuvPicture picture(int width, int height) {
        return YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
    }

    private static byte[] random(long seed, int length) {
        var bytes = new byte[length];
        new SplittableRandom(seed).nextBytes(bytes);
        return bytes;
    }

    @Nested
    @DisplayName("NV12")
    class Nv12 {
        @ParameterizedTest(name = "{0}x{1} at strides {2}/{3} into {4}x{5}")
        @CsvSource({
                "64,48,64,64,64,48",
                "64,48,80,96,64,48",
                "30,20,33,47,30,20",
                "1920,1080,2048,2048,1920,1080",
                "66,50,70,70,64,48",
                "62,46,64,64,64,48",
                "2,2,2,2,2,2",
                "1,1,16,16,2,2"
        })
        @DisplayName("copies luma and splits chroma respecting each plane's bytes per row, cropping or padding")
        void copies(int sourceWidth, int sourceHeight, int lumaStride, int chromaStride, int width, int height) {
            var chromaWidth = (sourceWidth + 1) / 2;
            var chromaHeight = (sourceHeight + 1) / 2;
            var luma = random(sourceWidth * 31L + lumaStride, lumaStride * (sourceHeight - 1) + sourceWidth);
            var chroma = random(sourceHeight * 17L + chromaStride, chromaStride * (chromaHeight - 1) + 2 * chromaWidth);
            var target = picture(width, height);
            new FrameConverter().nv12(MemorySegment.ofArray(luma), lumaStride, sourceWidth, sourceHeight,
                    MemorySegment.ofArray(chroma), chromaStride, chromaWidth, chromaHeight, target, 16);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var expected = x < sourceWidth && y < sourceHeight ? luma[y * lumaStride + x] : (byte) 16;
                    assertEquals(expected, target.y()[y * target.yStride() + x], "luma " + x + "," + y);
                }
            }
            for (var y = 0; y < height / 2; y++) {
                for (var x = 0; x < width / 2; x++) {
                    var inside = x < chromaWidth && y < chromaHeight;
                    var expectedU = inside ? chroma[y * chromaStride + 2 * x] : (byte) 128;
                    var expectedV = inside ? chroma[y * chromaStride + 2 * x + 1] : (byte) 128;
                    assertEquals(expectedU, target.u()[y * target.uvStride() + x], "cb " + x + "," + y);
                    assertEquals(expectedV, target.v()[y * target.uvStride() + x], "cr " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("rejects planes shorter than their stride and height require")
        void rejectsShortPlanes() {
            var converter = new FrameConverter();
            var target = picture(8, 8);
            var luma = MemorySegment.ofArray(new byte[16 * 7 + 7]);
            var chroma = MemorySegment.ofArray(new byte[16 * 4]);
            assertThrows(IllegalArgumentException.class, () -> converter.nv12(luma, 16, 8, 8, chroma, 16, 4, 4, target, 16));
            var fullLuma = MemorySegment.ofArray(new byte[16 * 8]);
            assertThrows(IllegalArgumentException.class, () -> converter.nv12(fullLuma, 4, 8, 8, chroma, 16, 4, 4, target, 16));
            assertThrows(IllegalArgumentException.class, () -> converter.nv12(fullLuma, 16, -1, 8, chroma, 16, 4, 4, target, 16));
            assertThrows(IllegalArgumentException.class, () -> converter.nv12(fullLuma, 16, 8, 8, MemorySegment.ofArray(new byte[3]), 16, 4, 4, target, 16));
        }

        @Test
        @DisplayName("rejects targets that are not 4:2:0 or have an odd size")
        void rejectsTargets() {
            var converter = new FrameConverter();
            var plane = MemorySegment.ofArray(new byte[64]);
            var odd = YuvPicture.allocate(7, 8, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            var full = YuvPicture.allocate(8, 8, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> converter.nv12(plane, 8, 8, 8, plane, 8, 4, 4, odd, 16));
            assertThrows(IllegalArgumentException.class, () -> converter.nv12(plane, 8, 8, 8, plane, 8, 4, 4, full, 16));
        }
    }

    @Nested
    @DisplayName("BGRA")
    class Bgra {
        @ParameterizedTest(name = "{0}x{1} at stride {2} into {3}x{4}")
        @CsvSource({
                "64,48,256,64,48",
                "64,48,300,64,48",
                "31,21,128,30,20",
                "40,40,160,64,48",
                "70,50,280,64,48",
                "1,1,4,2,2"
        })
        @DisplayName("converts every 2x2 block like the scalar kernel reading the source directly")
        void converts(int sourceWidth, int sourceHeight, int stride, int width, int height) {
            var pixels = random(stride * 7L + sourceWidth, stride * (sourceHeight - 1) + 4 * sourceWidth);
            var target = picture(width, height);
            new FrameConverter(new ScalarConversionKernel()).bgra(MemorySegment.ofArray(pixels), stride, sourceWidth, sourceHeight,
                    target, YuvCoefficients.BT709_LIMITED);
            var reference = new FrameConverter();
            var vectorTarget = picture(width, height);
            reference.bgra(MemorySegment.ofArray(pixels), stride, sourceWidth, sourceHeight, vectorTarget, YuvCoefficients.BT709_LIMITED);
            var kernel = new ScalarConversionKernel();
            var coveredWidth = Math.min(width, sourceWidth & ~1);
            var coveredHeight = Math.min(height, sourceHeight & ~1);
            var pixel = new byte[4];
            var luma = new byte[1];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    byte expected = 16;
                    if (x < coveredWidth && y < coveredHeight) {
                        System.arraycopy(pixels, y * stride + 4 * x, pixel, 0, 4);
                        kernel.rgbToLuma(pixel, 0, 1, RgbLayout.BGRX, YuvCoefficients.BT709_LIMITED, luma, 0);
                        expected = luma[0];
                    }
                    assertEquals(expected, target.y()[y * target.yStride() + x], "luma " + x + "," + y);
                    assertEquals(expected, vectorTarget.y()[y * vectorTarget.yStride() + x], "selected kernel luma " + x + "," + y);
                }
            }
            var block = new byte[16];
            var u = new byte[1];
            var v = new byte[1];
            for (var y = 0; y < height / 2; y++) {
                for (var x = 0; x < width / 2; x++) {
                    byte expectedU = (byte) 128;
                    byte expectedV = (byte) 128;
                    if (2 * x < coveredWidth && 2 * y < coveredHeight) {
                        System.arraycopy(pixels, 2 * y * stride + 8 * x, block, 0, 8);
                        System.arraycopy(pixels, (2 * y + 1) * stride + 8 * x, block, 8, 8);
                        kernel.rgbToChroma(block, 0, 8, 1, RgbLayout.BGRX, YuvCoefficients.BT709_LIMITED, u, v, 0);
                        expectedU = u[0];
                        expectedV = v[0];
                    }
                    assertEquals(expectedU, target.u()[y * target.uvStride() + x], "cb " + x + "," + y);
                    assertEquals(expectedV, target.v()[y * target.uvStride() + x], "cr " + x + "," + y);
                    assertEquals(expectedU, vectorTarget.u()[y * vectorTarget.uvStride() + x], "selected kernel cb " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("rejects a stride shorter than a row of pixels")
        void rejectsStride() {
            var converter = new FrameConverter();
            var pixels = MemorySegment.ofArray(new byte[4 * 8 * 8]);
            assertThrows(IllegalArgumentException.class, () -> converter.bgra(pixels, 31, 8, 8, picture(8, 8), YuvCoefficients.BT709_LIMITED));
            assertThrows(NullPointerException.class, () -> converter.bgra(pixels, 32, 8, 8, picture(8, 8), null));
        }
    }
}
