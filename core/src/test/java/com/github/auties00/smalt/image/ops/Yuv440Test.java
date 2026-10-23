package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the 4:4:0 layout (chroma halved vertically only) through the conversions whose 4:2:0 and 4:2:2 paths have
 * dedicated code: RGBA conversion with the triangle filter (libjpeg's {@code h1v2} fancy upsampling) and by
 * replication, Y'CbCr matrix conversion in place, and reduction from RGBA, each checked against an independent
 * reference built from the 4:4:4 path.
 */
@DisplayName("4:4:0 pictures")
class Yuv440Test {
    static Stream<Arguments> cases() {
        return Stream.of(Arguments.of(37, 21, ColorInfo.BT709_LIMITED), Arguments.of(16, 16, ColorInfo.JPEG),
                Arguments.of(1, 1, ColorInfo.BT601_LIMITED), Arguments.of(5, 2, ColorInfo.BT709_LIMITED),
                Arguments.of(64, 33, ColorInfo.JPEG));
    }

    @ParameterizedTest(name = "{0}x{1} {2}")
    @MethodSource("cases")
    @DisplayName("the triangle filter upsamples 4:4:0 chroma as libjpeg's h1v2 fancy upsampling does")
    void triangle(int width, int height, ColorInfo color) {
        var picture = Pictures.randomYuv(new SplittableRandom(width * 31L + height), width, height,
                ChromaFormat.YUV440, false, color);
        var expected = ImageOps.toRgba(widened(picture, true), ChromaUpsampling.TRIANGLE);
        var actual = ImageOps.toRgba(picture, ChromaUpsampling.TRIANGLE);
        assertArrayEquals(Pictures.tight(expected), Pictures.tight(actual));
    }

    @ParameterizedTest(name = "{0}x{1} {2}")
    @MethodSource("cases")
    @DisplayName("nearest upsampling replicates every 4:4:0 chroma sample over its two rows")
    void nearest(int width, int height, ColorInfo color) {
        var picture = Pictures.randomYuv(new SplittableRandom(width * 17L + height), width, height,
                ChromaFormat.YUV440, false, color);
        var expected = ImageOps.toRgba(widened(picture, false), ChromaUpsampling.NEAREST);
        var actual = ImageOps.toRgba(picture, ChromaUpsampling.NEAREST);
        assertArrayEquals(Pictures.tight(expected), Pictures.tight(actual));
    }

    @ParameterizedTest(name = "{0}x{1} {2}")
    @MethodSource("cases")
    @DisplayName("a matrix change keeps the 4:4:0 layout, its chroma converted sample by sample, its luma within one")
    void matrix(int width, int height, ColorInfo color) {
        var picture = Pictures.randomYuv(new SplittableRandom(width * 7L + height), width, height,
                ChromaFormat.YUV440, false, color);
        var target = color.equals(ColorInfo.BT709_LIMITED) ? ColorInfo.BT601_LIMITED : ColorInfo.BT709_LIMITED;
        var converted = ImageOps.toYuv(picture, ChromaFormat.YUV440, target);
        assertEquals(ChromaFormat.YUV440, converted.chromaFormat());
        var reference = ImageOps.toYuv(widened(picture, true), ChromaFormat.YUV444, target);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var difference = (converted.y()[y * converted.yStride() + x] & 0xFF)
                        - (reference.y()[y * reference.yStride() + x] & 0xFF);
                assertTrue(Math.abs(difference) <= 1, "luma at " + x + "," + y + " differs by " + difference);
            }
        }
        // chroma is converted at its own resolution: the same as the 4:4:4 picture of the same chroma rows
        var rows = ImageOps.toYuv(replicated(picture), ChromaFormat.YUV444, target);
        for (var y = 0; y < converted.chromaHeight(); y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(rows.u()[2 * y * rows.uvStride() + x], converted.u()[y * converted.uvStride() + x]);
                assertEquals(rows.v()[2 * y * rows.uvStride() + x], converted.v()[y * converted.uvStride() + x]);
            }
        }
    }

    @ParameterizedTest(name = "{0}x{1} {2}")
    @MethodSource("cases")
    @DisplayName("RGBA reduced to 4:4:0 averages the chroma of every pair of rows, an odd last row alone")
    void reduction(int width, int height, ColorInfo color) {
        var rgba = Pictures.randomRgba(new SplittableRandom(width * 3L + height), width, height, false);
        var full = ImageOps.toYuv(rgba, ChromaFormat.YUV444, color);
        var halved = ImageOps.toYuv(rgba, ChromaFormat.YUV440, color);
        assertEquals(width, halved.chromaWidth());
        assertEquals((height + 1) / 2, halved.chromaHeight());
        assertArrayEquals(Pictures.tight(full.y(), full.yStride(), width, height),
                Pictures.tight(halved.y(), halved.yStride(), width, height));
        var back = ImageOps.toYuv(full, ChromaFormat.YUV440, color);
        for (var y = 0; y < halved.chromaHeight(); y++) {
            for (var x = 0; x < width; x++) {
                // the reduction from RGBA and from its 4:4:4 conversion agree within one code value
                assertTrue(Math.abs((halved.u()[y * halved.uvStride() + x] & 0xFF)
                        - (back.u()[y * back.uvStride() + x] & 0xFF)) <= 1);
                var top = full.u()[2 * y * full.uvStride() + x] & 0xFF;
                var bottom = full.u()[Math.min(2 * y + 1, height - 1) * full.uvStride() + x] & 0xFF;
                var expected = 2 * y + 1 < height ? (top + bottom + 1) >> 1 : top;
                assertEquals(expected, back.u()[y * back.uvStride() + x] & 0xFF, "chroma at " + x + "," + y);
            }
        }
    }

    /**
     * Returns a 4:4:4 copy of a 4:4:0 picture whose chroma rows are upsampled with libjpeg's {@code h1v2} fancy
     * filter, or replicated.
     */
    private static YuvPicture widened(YuvPicture picture, boolean fancy) {
        var width = picture.width();
        var height = picture.height();
        var out = YuvPicture.allocate(width, height, ChromaFormat.YUV444, false, picture.color());
        for (var y = 0; y < height; y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), out.y(), y * out.yStride(), width);
            var near = y >> 1;
            var even = (y & 1) == 0;
            var far = even ? Math.max(near - 1, 0) : Math.min(near + 1, picture.chromaHeight() - 1);
            for (var x = 0; x < width; x++) {
                out.u()[y * out.uvStride() + x] = sample(picture.u(), picture.uvStride(), near, far, x, even, fancy);
                out.v()[y * out.uvStride() + x] = sample(picture.v(), picture.uvStride(), near, far, x, even, fancy);
            }
        }
        return out;
    }

    private static byte sample(byte[] plane, int stride, int near, int far, int x, boolean even, boolean fancy) {
        var value = plane[near * stride + x] & 0xFF;
        if (!fancy) {
            return (byte) value;
        }
        return (byte) ((3 * value + (plane[far * stride + x] & 0xFF) + (even ? 1 : 2)) >> 2);
    }

    /** Returns a 4:4:4 copy of a 4:4:0 picture with every chroma row repeated over the two rows it covers. */
    private static YuvPicture replicated(YuvPicture picture) {
        return widened(picture, false);
    }
}
