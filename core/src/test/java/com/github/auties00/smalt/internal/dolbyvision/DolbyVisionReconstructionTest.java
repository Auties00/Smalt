package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.testkit.DolbyVisionReference;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the integer composer exactly, and the color conversion within one code, with the literal implementation of
 * ETSI GS CCM 001 in {@link DolbyVisionReference}, on random mappings and pictures of every shape.
 */
@DisplayName("Dolby Vision reconstruction stages")
class DolbyVisionReconstructionTest {
    private static final ColorInfo BASE_COLOR = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.FULL,
            ColorPrimaries.BT2020, TransferCharacteristics.PQ);

    /** Returns a mapping as the test kit's reference and RPU writer take it. */
    static DolbyVisionReference.Mapping reference(DolbyVisionRpu.Mapping mapping) {
        var curves = new DolbyVisionReference.Curve[3];
        for (var component = 0; component < 3; component++) {
            var pieces = mapping.curves()[component].pieces();
            var converted = new DolbyVisionReference.Piece[pieces.length];
            for (var index = 0; index < pieces.length; index++) {
                converted[index] = new DolbyVisionReference.Piece(pieces[index].mmr(), pieces[index].order(),
                        pieces[index].coefficients());
            }
            curves[component] = new DolbyVisionReference.Curve(mapping.curves()[component].pivots(), converted);
        }
        return new DolbyVisionReference.Mapping(mapping.rpuId(), mapping.denominatorBits(), curves);
    }

    /** Writes a coefficient near a value, in fixed point. */
    private static long fixed(double value, int bits) {
        return (long) Math.floor(value * (1L << bits));
    }

    private static int[] randomPivots(SplittableRandom random, int count) {
        var pivots = new int[count];
        pivots[0] = random.nextInt(2) == 0 ? 0 : random.nextInt(32);
        pivots[count - 1] = random.nextInt(2) == 0 ? 1023 : 1023 - random.nextInt(32);
        for (var index = 1; index < count - 1; index++) {
            pivots[index] = pivots[0] + 1 + index * (pivots[count - 1] - pivots[0] - 2) / count;
        }
        return pivots;
    }

    private static DolbyVisionRpu.Piece randomPolynomial(SplittableRandom random, int bits) {
        var order = 1 + random.nextInt(2);
        var coefficients = new long[order + 1];
        coefficients[0] = fixed(-0.1 + 0.2 * random.nextDouble(), bits);
        coefficients[1] = fixed(0.5 + random.nextDouble(), bits);
        if (order == 2) {
            coefficients[2] = fixed(-0.5 + random.nextDouble(), bits);
        }
        return new DolbyVisionRpu.Piece(false, order, coefficients);
    }

    private static DolbyVisionRpu.Piece randomRegression(SplittableRandom random, int bits) {
        var order = 1 + random.nextInt(3);
        var coefficients = new long[1 + 7 * order];
        for (var index = 0; index < coefficients.length; index++) {
            coefficients[index] = fixed(-1.5 + 3 * random.nextDouble(), bits);
        }
        return new DolbyVisionRpu.Piece(true, order, coefficients);
    }

    /** Returns a mapping with up to 4 pieces per component, regressions only on chroma when asked for. */
    static DolbyVisionRpu.Mapping randomMapping(SplittableRandom random, int bits, boolean regressions) {
        var curves = new DolbyVisionRpu.Curve[3];
        for (var component = 0; component < 3; component++) {
            var pieces = new DolbyVisionRpu.Piece[1 + random.nextInt(4)];
            for (var index = 0; index < pieces.length; index++) {
                pieces[index] = regressions && component > 0 && random.nextBoolean()
                        ? randomRegression(random, bits) : randomPolynomial(random, bits);
            }
            curves[component] = new DolbyVisionRpu.Curve(randomPivots(random, pieces.length + 1), pieces);
        }
        return new DolbyVisionRpu.Mapping(0, bits, false, curves);
    }

    static Yuv16Picture randomBase(SplittableRandom random, int width, int height) {
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, 10, false, BASE_COLOR);
        fill(picture.y(), random);
        fill(picture.u(), random);
        fill(picture.v(), random);
        return picture;
    }

    private static void fill(short[] plane, SplittableRandom random) {
        for (var index = 0; index < plane.length; index++) {
            plane[index] = (short) random.nextInt(1024);
        }
    }

    static DolbyVisionReference.Planes planes(Yuv16Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var chromaWidth = picture.chromaWidth();
        var chromaHeight = picture.chromaHeight();
        var y = new int[width * height];
        var cb = new int[chromaWidth * chromaHeight];
        var cr = new int[chromaWidth * chromaHeight];
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                y[row * width + x] = picture.y()[row * picture.yStride() + x] & 0xFFFF;
            }
        }
        for (var row = 0; row < chromaHeight; row++) {
            for (var x = 0; x < chromaWidth; x++) {
                cb[row * chromaWidth + x] = picture.u()[row * picture.uvStride() + x] & 0xFFFF;
                cr[row * chromaWidth + x] = picture.v()[row * picture.uvStride() + x] & 0xFFFF;
            }
        }
        return new DolbyVisionReference.Planes(width, height, y, cb, cr);
    }

    private static int[] ints(short[] plane) {
        var out = new int[plane.length];
        for (var index = 0; index < plane.length; index++) {
            out[index] = plane[index] & 0xFFFF;
        }
        return out;
    }

    static Stream<Arguments> shapes() {
        return Stream.of(new int[][]{{2, 2}, {3, 3}, {1, 1}, {5, 2}, {2, 7}, {16, 16}, {17, 9}, {33, 64}, {64, 48}})
                .flatMap(size -> Stream.of(10, 12, 14).flatMap(depth -> Stream.of(14, 23, 32)
                        .map(bits -> Arguments.of(size[0], size[1], depth, bits))));
    }

    @Nested
    @DisplayName("composer")
    class Composer {
        @ParameterizedTest(name = "{0}x{1}, {2} bits, denominator {3}")
        @MethodSource("com.github.auties00.smalt.internal.dolbyvision.DolbyVisionReconstructionTest#shapes")
        @DisplayName("equals the pseudo-code of clause 5.4 exactly")
        void exact(int width, int height, int depth, int bits) {
            var random = new SplittableRandom(width * 1009L + height * 31L + depth * 7L + bits);
            var composer = new DolbyVisionComposer();
            for (var round = 0; round < 4; round++) {
                var mapping = randomMapping(random, bits, round > 0);
                var base = randomBase(random, width, height);
                var chromaSize = base.chromaWidth() * base.chromaHeight();
                var y = new short[width * height];
                var cb = new short[chromaSize];
                var cr = new short[chromaSize];
                composer.prepare(mapping, depth);
                composer.compose(base, y, cb, cr, 1);
                var expected = DolbyVisionReference.compose(planes(base), reference(mapping), depth);
                Tolerance.exact().assertWithin("luma", expected.y(), ints(y));
                Tolerance.exact().assertWithin("first chroma", expected.cb(), ints(cb));
                Tolerance.exact().assertWithin("second chroma", expected.cr(), ints(cr));
            }
        }

        @Test
        @DisplayName("gives the same samples with any thread count and when re-prepared")
        void threads() {
            var random = new SplittableRandom(17);
            var mapping = randomMapping(random, 23, true);
            var base = randomBase(random, 640, 360);
            var size = base.chromaWidth() * base.chromaHeight();
            var serial = new short[3][];
            var composer = new DolbyVisionComposer();
            composer.prepare(mapping, 12);
            serial[0] = new short[640 * 360];
            serial[1] = new short[size];
            serial[2] = new short[size];
            composer.compose(base, serial[0], serial[1], serial[2], 1);
            var parallel = new short[][]{new short[640 * 360], new short[size], new short[size]};
            composer.prepare(randomMapping(random, 14, true), 10);
            composer.prepare(mapping, 12);
            composer.compose(base, parallel[0], parallel[1], parallel[2], 8);
            assertArrayEquals(serial[0], parallel[0]);
            assertArrayEquals(serial[1], parallel[1]);
            assertArrayEquals(serial[2], parallel[2]);
        }

        @Test
        @DisplayName("maps with the identity to the base layer scaled to the output depth")
        void identity() {
            var identity = new DolbyVisionRpu.Piece(false, 1, new long[]{0, 1L << 23});
            var curve = new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{identity});
            var mapping = new DolbyVisionRpu.Mapping(0, 23, false, new DolbyVisionRpu.Curve[]{curve, curve, curve});
            var base = randomBase(new SplittableRandom(3), 8, 8);
            var y = new short[64];
            var cb = new short[16];
            var cr = new short[16];
            var composer = new DolbyVisionComposer();
            composer.prepare(mapping, 12);
            composer.compose(base, y, cb, cr, 1);
            for (var index = 0; index < 64; index++) {
                assertEquals(base.y()[index] * 4, y[index]);
            }
        }

        @Test
        @DisplayName("clamps a sample outside the pivots and takes the last piece at the last pivot")
        void pivotClamp() {
            var piece = new DolbyVisionRpu.Piece(false, 1, new long[]{0, 1L << 23});
            var curve = new DolbyVisionRpu.Curve(new int[]{100, 900}, new DolbyVisionRpu.Piece[]{piece});
            var mapping = new DolbyVisionRpu.Mapping(0, 23, false, new DolbyVisionRpu.Curve[]{curve, curve, curve});
            var base = Yuv16Picture.allocate(2, 2, ChromaFormat.YUV420, 10, false, BASE_COLOR);
            base.y()[0] = 0;
            base.y()[1] = 99;
            base.y()[2] = 900;
            base.y()[3] = 1023;
            var y = new short[4];
            var composer = new DolbyVisionComposer();
            composer.prepare(mapping, 10);
            composer.compose(base, y, new short[1], new short[1], 1);
            assertArrayEquals(new short[]{100, 100, 900, 900}, y);
        }

        @Test
        @DisplayName("refuses a regression of the luma component, which the clause forbids")
        void lumaRegression() {
            var regression = new DolbyVisionRpu.Piece(true, 1, new long[8]);
            var polynomial = new DolbyVisionRpu.Piece(false, 1, new long[2]);
            var mapping = new DolbyVisionRpu.Mapping(0, 23, false, new DolbyVisionRpu.Curve[]{
                    new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{regression}),
                    new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{polynomial}),
                    new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{polynomial})});
            assertThrows(MediaException.Malformed.class, () -> new DolbyVisionComposer().prepare(mapping, 12));
        }
    }

    @Nested
    @DisplayName("color conversion")
    class Color {
        private static void assertConversion(DolbyVisionReference.Planes planes, int depth, short[] ycc, long[] offsets,
                                             short[] lms, int threads) {
            var output = Yuv16Picture.allocate(planes.width(), planes.height(), ChromaFormat.YUV444, depth, false,
                    BASE_COLOR);
            var y = new short[planes.y().length];
            for (var index = 0; index < y.length; index++) {
                y[index] = (short) planes.y()[index];
            }
            var cb = new short[planes.cb().length];
            var cr = new short[planes.cr().length];
            for (var index = 0; index < cb.length; index++) {
                cb[index] = (short) planes.cb()[index];
                cr[index] = (short) planes.cr()[index];
            }
            new DolbyVisionColor().convert(y, cb, cr, depth, DolbyVisionColor.Matrices.of(ycc, offsets, lms), output,
                    threads);
            var expected = DolbyVisionReference.toRgb(planes, depth, ycc, offsets, lms);
            var scale = (1 << depth) - 1;
            var tolerance = Tolerance.maxAbs(1);
            tolerance.assertWithin("green", codes(expected[0], scale), ints(output.y()));
            tolerance.assertWithin("blue", codes(expected[1], scale), ints(output.u()));
            tolerance.assertWithin("red", codes(expected[2], scale), ints(output.v()));
        }

        private static int[] codes(double[] values, int scale) {
            var out = new int[values.length];
            for (var index = 0; index < values.length; index++) {
                out[index] = (int) Math.round(values[index] * scale);
            }
            return out;
        }

        private static DolbyVisionReference.Planes random(SplittableRandom random, int width, int height, int depth) {
            var chroma = (width + 1) / 2 * ((height + 1) / 2);
            return new DolbyVisionReference.Planes(width, height, random.ints(width * height, 0, 1 << depth).toArray(),
                    random.ints(chroma, 0, 1 << depth).toArray(), random.ints(chroma, 0, 1 << depth).toArray());
        }

        @ParameterizedTest(name = "{0}x{1}, {2} bits")
        @CsvSource({"1,1,12", "2,2,10", "3,5,12", "16,9,12", "17,17,14", "64,36,12", "127,33,10"})
        @DisplayName("agrees with the double precision equations within one code on random pictures")
        void random(int width, int height, int depth) {
            var random = new SplittableRandom(width * 131L + height + depth);
            var planes = random(random, width, height, depth);
            assertConversion(planes, depth, DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB, DolbyVisionRpus.IPT_PQ_C2_OFFSET,
                    DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS, 1);
            assertConversion(planes, depth, DolbyVisionRpus.BT2020_YCC_TO_RGB, DolbyVisionRpus.BT2020_OFFSET,
                    DolbyVisionRpus.BT2020_RGB_TO_LMS, 1);
            assertConversion(planes, depth, new short[]{8192, 0, 0, 0, 8192, 0, 0, 0, 8192}, new long[3],
                    new short[]{16384, 0, 0, 0, 16384, 0, 0, 0, 16384}, 4);
        }

        @Test
        @DisplayName("agrees on every luma code of a dark and a bright flat picture, where the curves bend most")
        void ramp() {
            var depth = 12;
            var width = 4096;
            var y = new int[width * 2];
            var chroma = 2048 * 1;
            var cb = new int[chroma];
            var cr = new int[chroma];
            for (var index = 0; index < y.length; index++) {
                y[index] = index % 4096;
            }
            java.util.Arrays.fill(cb, 2048);
            java.util.Arrays.fill(cr, 2048);
            assertConversion(new DolbyVisionReference.Planes(width, 2, y, cb, cr), depth,
                    DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB, DolbyVisionRpus.IPT_PQ_C2_OFFSET,
                    DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS, 1);
        }

        @Test
        @DisplayName("keeps the neutral axis of IPT-PQ-c2 neutral with its intensity as the PQ code")
        void neutral() {
            var picture = Yuv16Picture.allocate(1024, 2, ChromaFormat.YUV444, 12, false, BASE_COLOR);
            var y = new short[1024 * 2];
            var cb = new short[512];
            var cr = new short[512];
            for (var index = 0; index < y.length; index++) {
                y[index] = (short) (index % 1024 * 4);
            }
            java.util.Arrays.fill(cb, (short) 2048);
            java.util.Arrays.fill(cr, (short) 2048);
            new DolbyVisionColor().convert(y, cb, cr, 12, DolbyVisionColor.Matrices.of(DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB,
                    DolbyVisionRpus.IPT_PQ_C2_OFFSET, DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS), picture, 1);
            for (var code = 0; code < 1024; code++) {
                var expected = code * 4095 / 1024.0;
                assertTrue(Math.abs(picture.y()[code] - expected) <= 3, "G at " + code + ": " + picture.y()[code]);
                assertTrue(Math.abs(picture.u()[code] - expected) <= 3, "B at " + code + ": " + picture.u()[code]);
                assertTrue(Math.abs(picture.v()[code] - expected) <= 3, "R at " + code + ": " + picture.v()[code]);
            }
        }

        @Test
        @DisplayName("writes 8-bit pictures from the same values")
        void narrow() {
            var random = new SplittableRandom(23);
            var planes = random(random, 32, 18, 12);
            var ycc = DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB;
            var offsets = DolbyVisionRpus.IPT_PQ_C2_OFFSET;
            var lms = DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS;
            var output = YuvPicture.allocate(32, 18, ChromaFormat.YUV444, false, BASE_COLOR);
            var y = new short[planes.y().length];
            for (var index = 0; index < y.length; index++) {
                y[index] = (short) planes.y()[index];
            }
            var cb = new short[planes.cb().length];
            var cr = new short[planes.cr().length];
            for (var index = 0; index < cb.length; index++) {
                cb[index] = (short) planes.cb()[index];
                cr[index] = (short) planes.cr()[index];
            }
            new DolbyVisionColor().convert(y, cb, cr, 12, DolbyVisionColor.Matrices.of(ycc, offsets, lms), output, 1);
            var expected = DolbyVisionReference.toRgb(planes, 12, ycc, offsets, lms);
            for (var row = 0; row < 18; row++) {
                for (var x = 0; x < 32; x++) {
                    var index = row * 32 + x;
                    var at = row * output.yStride() + x;
                    assertTrue(Math.abs((output.y()[at] & 0xFF) - expected[0][index] * 255) <= 1);
                    assertTrue(Math.abs((output.u()[at] & 0xFF) - expected[1][index] * 255) <= 1);
                    assertTrue(Math.abs((output.v()[at] & 0xFF) - expected[2][index] * 255) <= 1);
                }
            }
        }

        @Test
        @DisplayName("takes the default matrices of clause 6.2.2 with their offsets in the RPU's units")
        void defaults() {
            var matrices = DolbyVisionColor.Matrices.DEFAULTS;
            assertEquals(1 / 16f, matrices.offsets()[0]);
            assertEquals(0.5f, matrices.offsets()[1]);
            assertEquals(0.5f, matrices.offsets()[2]);
            assertEquals(9575 / 8192f, matrices.yccToNonlinear()[0]);
            assertEquals(-4383 / 8192f, matrices.yccToNonlinear()[5]);
        }
    }

    @Nested
    @DisplayName("the reference itself")
    class Reference {
        @ParameterizedTest(name = "{0} cd/m2")
        @CsvSource({"100,0.50807", "1000,0.75183", "10000,1.0", "0.1,0.06234"})
        @DisplayName("has the ST 2084 curve of its published code values")
        void curve(double luminance, double code) {
            assertEquals(code, DolbyVisionReference.inverseEotf(luminance / 10000), 0.0002);
            assertEquals(luminance / 10000, DolbyVisionReference.eotf(DolbyVisionReference.inverseEotf(luminance / 10000)),
                    1e-9);
        }
    }
}
