package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Requires the 8-bit RGBA conversion of decoded YCgCo-Re and YCgCo-Ro pictures to equal
 * {@link ImageOps#toRgba(com.github.auties00.smalt.image.Picture)} on the same samples, over random samples in every
 * chroma layout, both ranges, 10 and 12 bits, odd sizes, the narrow widths whose chroma is replicated, frames large
 * enough to be split into bands, and a converter reused across shapes.
 */
@DisplayName("YCgCo-R to 8-bit RGBA conversion")
class YcgcoRgbaTest {
    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        int[][] sizes = {{1, 1}, {2, 3}, {3, 2}, {4, 4}, {5, 7}, {17, 9}, {64, 33}};
        for (var format : new ChromaFormat[]{ChromaFormat.YUV444, ChromaFormat.YUV422, ChromaFormat.YUV420,
                ChromaFormat.MONOCHROME}) {
            for (var matrix : new MatrixCoefficients[]{MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO}) {
                for (var range : ColorRange.values()) {
                    for (var depth : new int[]{10, 12}) {
                        for (var size : sizes) {
                            cases.add(Arguments.of(format, matrix, range, depth, size[0], size[1]));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}-bit {4}x{5}")
    @MethodSource("cases")
    @DisplayName("matches ImageOps.toRgba")
    void matchesImageOps(ChromaFormat format, MatrixCoefficients matrix, ColorRange range, int depth, int width,
                         int height) {
        var seed = ((long) format.ordinal() << 40) ^ ((long) matrix.ordinal() << 32) ^ (range.ordinal() << 24)
                ^ (depth << 16) ^ (width << 8) ^ height;
        check(new YcgcoRgba(), format, matrix, range, depth, width, height, new SplittableRandom(seed));
    }

    @Test
    @DisplayName("matches ImageOps.toRgba on frames split into bands")
    void large() {
        var converter = new YcgcoRgba();
        var random = new SplittableRandom(7);
        for (var format : new ChromaFormat[]{ChromaFormat.YUV444, ChromaFormat.YUV420, ChromaFormat.YUV422}) {
            check(converter, format, MatrixCoefficients.YCGCO_RE, ColorRange.FULL, 10, 1921, 1081, random);
        }
    }

    @Test
    @DisplayName("one converter gives the same result as fresh ones across shapes, ranges and depths")
    void reused() {
        var converter = new YcgcoRgba();
        var random = new SplittableRandom(11);
        for (var i = 0; i < 40; i++) {
            var formats = ChromaFormat.values();
            var format = formats[random.nextInt(formats.length)];
            if (format == ChromaFormat.YUV440) {
                continue;
            }
            check(converter, format, random.nextBoolean() ? MatrixCoefficients.YCGCO_RE : MatrixCoefficients.YCGCO_RO,
                    random.nextBoolean() ? ColorRange.FULL : ColorRange.LIMITED, random.nextBoolean() ? 10 : 12,
                    1 + random.nextInt(40), 1 + random.nextInt(40), random);
        }
    }

    private static void check(YcgcoRgba converter, ChromaFormat format, MatrixCoefficients matrix, ColorRange range,
                              int depth, int width, int height, SplittableRandom random) {
        var picture = Av1Picture.allocate(width, height, SequenceHeader.layoutOf(format), depth);
        var color = new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.SRGB);
        var reference = Yuv16Picture.allocate(width, height, format, depth, false, color);
        var max = (1 << depth) - 1;
        fill(random, picture.data[0], picture.stride[0], reference.y(), reference.yStride(), width, height, max);
        if (format.hasChroma()) {
            var cw = format.chromaWidth(width);
            var ch = format.chromaHeight(height);
            fill(random, picture.data[1], picture.stride[1], reference.u(), reference.uvStride(), cw, ch, max);
            fill(random, picture.data[2], picture.stride[1], reference.v(), reference.uvStride(), cw, ch, max);
        }
        var out = RgbaPicture.allocate(width, height, false);
        java.util.Arrays.fill(out.pixels(), (byte) 0x5A);
        converter.convert(picture, format, matrix, range, out);
        var what = format + " " + matrix + " " + range + " " + depth + "-bit " + width + "x" + height;
        Tolerance.exact().assertWithin(what, RasterTesting.tight(ImageOps.toRgba(reference)), RasterTesting.tight(out));
        assertEquals(width, out.width());
    }

    /** Fills a plane with random samples, mostly in the transform's range and some at its extremes. */
    private static void fill(SplittableRandom random, short[] plane, int stride, short[] copy, int copyStride,
                             int width, int height, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var pick = random.nextInt(16);
                var value = pick == 0 ? 0 : pick == 1 ? max : random.nextInt(max + 1);
                plane[y * stride + x] = (short) value;
                copy[y * copyStride + x] = (short) value;
            }
        }
    }
}
