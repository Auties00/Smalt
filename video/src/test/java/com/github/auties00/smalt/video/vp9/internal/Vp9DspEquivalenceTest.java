package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API kernels of the VP9 decoder produce exactly the output of the scalar
 * reference on randomized input: every filter phase, block size and averaging mode of motion
 * compensation, the loop filters on planes smooth enough to reach their flat paths (including the
 * transposes of vertical edges), the inverse transforms of every size and type with the
 * coefficient counts that select libvpx's reduced transforms, and the intra predictors. Transform
 * inputs keep the sum of the coefficient magnitudes within the range a conformant stream can
 * produce, so that no intermediate value leaves 16 bits.
 */
@DisplayName("VP9 decoder kernels: vector equals scalar")
class Vp9DspEquivalenceTest {
    static final int STRIDE = 160;
    static final ScalarVp9Dsp SCALAR = new ScalarVp9Dsp();
    static final Vp9Dsp VECTOR = VectorVp9Dsp.create();

    @BeforeAll
    static void vectorAvailable() {
        Skip.unless(VECTOR instanceof VectorVp9Dsp, "simd:narrow", "no 256-bit vectors on this machine");
    }

    static byte[] randomPlane(Random random, int size) {
        var plane = new byte[size];
        random.nextBytes(plane);
        return plane;
    }

    static byte[] smoothPlane(Random random, int size, int noise) {
        var plane = new byte[size];
        var base = random.nextInt(256);
        for (var i = 0; i < size; i++) {
            var x = i % STRIDE;
            var y = i / STRIDE;
            var v = base + (x / 16) * random.nextInt(3) * (random.nextBoolean() ? 1 : -1) + y / 7
                    + random.nextInt(noise + 1) - noise / 2;
            if (random.nextInt(50) == 0) {
                v += random.nextInt(120) - 60;
            }
            plane[i] = (byte) Math.clamp(v, 0, 255);
        }
        return plane;
    }

    @Test
    @DisplayName("motion compensation for every filter, phase pair, block size and averaging mode")
    void convolve() {
        var random = new Random(1);
        int[] sizes = {4, 8, 16, 32, 64};
        for (var trial = 0; trial < 3000; trial++) {
            var w = sizes[random.nextInt(sizes.length)];
            var h = sizes[random.nextInt(sizes.length)];
            var filter = random.nextInt(4);
            var subX = random.nextInt(4) == 0 ? 0 : random.nextInt(16);
            var subY = random.nextInt(4) == 0 ? 0 : random.nextInt(16);
            var average = random.nextBoolean();
            var src = random.nextInt(3) == 0 ? smoothPlane(random, STRIDE * 96, 255) : randomPlane(random, STRIDE * 96);
            var srcOff = 8 * STRIDE + 8;
            var dst = randomPlane(random, STRIDE * 80);
            var dstOff = 4 * STRIDE + 4;
            var expected = dst.clone();
            var actual = dst.clone();
            SCALAR.convolve(src, srcOff, STRIDE, expected, dstOff, STRIDE, Vp9Constants.FILTER_KERNELS, filter * 128,
                    subX, 16, subY, 16, w, h, average, new byte[Vp9Dsp.CONVOLVE_SCRATCH]);
            VECTOR.convolve(src, srcOff, STRIDE, actual, dstOff, STRIDE, Vp9Constants.FILTER_KERNELS, filter * 128,
                    subX, 16, subY, 16, w, h, average, new byte[Vp9Dsp.CONVOLVE_SCRATCH]);
            assertArrayEquals(expected, actual, "trial " + trial + ": " + w + "x" + h + " filter " + filter + " phases "
                    + subX + "," + subY + " average " + average);
        }
    }

    @Test
    @DisplayName("scaled motion compensation")
    void scaledConvolve() {
        var random = new Random(2);
        for (var trial = 0; trial < 300; trial++) {
            var w = 4 << random.nextInt(5);
            var h = 4 << random.nextInt(5);
            var src = randomPlane(random, STRIDE * 160);
            var dst = randomPlane(random, STRIDE * 80);
            var expected = dst.clone();
            var actual = dst.clone();
            var xStep = 16 + random.nextInt(17);
            var yStep = 16 + random.nextInt(17);
            var subX = random.nextInt(16);
            var subY = random.nextInt(16);
            var average = random.nextBoolean();
            SCALAR.convolve(src, 8 * STRIDE + 8, STRIDE, expected, 0, STRIDE, Vp9Constants.FILTER_KERNELS, 0, subX,
                    xStep, subY, yStep, w, h, average, new byte[Vp9Dsp.CONVOLVE_SCRATCH]);
            VECTOR.convolve(src, 8 * STRIDE + 8, STRIDE, actual, 0, STRIDE, Vp9Constants.FILTER_KERNELS, 0, subX,
                    xStep, subY, yStep, w, h, average, new byte[Vp9Dsp.CONVOLVE_SCRATCH]);
            assertArrayEquals(expected, actual, "trial " + trial);
        }
    }

    @ParameterizedTest(name = "noise {0}")
    @ValueSource(ints = {0, 1, 2, 6, 20, 255})
    @DisplayName("loop filters on random planes and thresholds")
    void loopFilters(int noise) {
        var random = new Random(noise * 31L + 7);
        for (var trial = 0; trial < 600; trial++) {
            var plane = smoothPlane(random, STRIDE * 64, noise);
            var off = 24 * STRIDE + 24 + random.nextInt(16);
            var level0 = 1 + random.nextInt(63);
            var level1 = 1 + random.nextInt(63);
            var limit0 = 1 + random.nextInt(9);
            var limit1 = 1 + random.nextInt(9);
            int[] t = {2 * (level0 + 2) + limit0, limit0, level0 >> 4, 2 * (level1 + 2) + limit1, limit1, level1 >> 4};
            if (random.nextInt(8) == 0) {
                for (var i = 0; i < t.length; i++) {
                    t[i] = random.nextInt(256);
                }
            }
            var kernel = random.nextInt(14);
            var expected = plane.clone();
            var actual = plane.clone();
            apply(SCALAR, kernel, expected, off, t);
            apply(VECTOR, kernel, actual, off, t);
            assertArrayEquals(expected, actual, "trial " + trial + " kernel " + kernel);
        }
    }

    static void apply(Vp9Dsp dsp, int kernel, byte[] s, int off, int[] t) {
        var scratch = new byte[Vp9Dsp.LOOP_FILTER_SCRATCH];
        switch (kernel) {
            case 0 -> dsp.lpfHorizontal4(s, off, STRIDE, t[0], t[1], t[2], scratch);
            case 1 -> dsp.lpfHorizontal4Dual(s, off, STRIDE, t[0], t[1], t[2], t[3], t[4], t[5], scratch);
            case 2 -> dsp.lpfHorizontal8(s, off, STRIDE, t[0], t[1], t[2], scratch);
            case 3 -> dsp.lpfHorizontal8Dual(s, off, STRIDE, t[0], t[1], t[2], t[3], t[4], t[5], scratch);
            case 4 -> dsp.lpfHorizontal16(s, off, STRIDE, t[0], t[1], t[2], 1, scratch);
            case 5 -> dsp.lpfHorizontal16(s, off, STRIDE, t[0], t[1], t[2], 2, scratch);
            case 6 -> dsp.lpfVertical4(s, off, STRIDE, t[0], t[1], t[2], scratch);
            case 7 -> dsp.lpfVertical4Dual(s, off, STRIDE, t[0], t[1], t[2], t[3], t[4], t[5], scratch);
            case 8 -> dsp.lpfVertical8(s, off, STRIDE, t[0], t[1], t[2], scratch);
            case 9 -> dsp.lpfVertical8Dual(s, off, STRIDE, t[0], t[1], t[2], t[3], t[4], t[5], scratch);
            case 10 -> dsp.lpfVertical16(s, off, STRIDE, t[0], t[1], t[2], 8, scratch);
            case 11 -> dsp.lpfVertical16(s, off, STRIDE, t[0], t[1], t[2], 16, scratch);
            case 12 -> {
                dsp.lpfVertical16(s, off, STRIDE, t[0], t[1], t[2], 16, scratch);
                dsp.lpfHorizontal16(s, off, STRIDE, t[3], t[4], t[5], 2, scratch);
            }
            default -> {
                dsp.lpfVertical8Dual(s, off, STRIDE, t[0], t[1], t[2], t[3], t[4], t[5], scratch);
                dsp.lpfHorizontal4Dual(s, off + 4 * STRIDE, STRIDE, t[3], t[4], t[5], t[0], t[1], t[2], scratch);
            }
        }
    }

    static short[] scan(int txSize, int txType) {
        return switch (txSize) {
            case Vp9Constants.TX_4X4 -> txType == 1 ? Vp9ScanTables.ROW_SCAN_4X4
                    : txType == 2 ? Vp9ScanTables.COL_SCAN_4X4 : Vp9ScanTables.DEFAULT_SCAN_4X4;
            case Vp9Constants.TX_8X8 -> txType == 1 ? Vp9ScanTables.ROW_SCAN_8X8
                    : txType == 2 ? Vp9ScanTables.COL_SCAN_8X8 : Vp9ScanTables.DEFAULT_SCAN_8X8;
            case Vp9Constants.TX_16X16 -> txType == 1 ? Vp9ScanTables.ROW_SCAN_16X16
                    : txType == 2 ? Vp9ScanTables.COL_SCAN_16X16 : Vp9ScanTables.DEFAULT_SCAN_16X16;
            default -> Vp9ScanTables.DEFAULT_SCAN_32X32;
        };
    }

    @Test
    @DisplayName("inverse transforms of every size, type and coefficient count")
    void transforms() {
        var random = new Random(3);
        for (var trial = 0; trial < 6000; trial++) {
            var txSize = random.nextInt(4);
            var txType = txSize == Vp9Constants.TX_32X32 ? 0 : random.nextInt(4);
            var size = 4 << txSize;
            var scan = scan(txSize, txType);
            var eob = switch (random.nextInt(4)) {
                case 0 -> 1;
                case 1 -> 1 + random.nextInt(Math.min(12, size * size));
                case 2 -> 1 + random.nextInt(Math.min(40, size * size));
                default -> 1 + random.nextInt(size * size);
            };
            var coef = new int[1024 + 8];
            var scalarCoef = new int[1024 + 8];
            var scalarLayout = SCALAR.coefficientLayout(txSize);
            var vectorLayout = VECTOR.coefficientLayout(txSize);
            var budget = 1500 + random.nextInt(600);
            for (var c = 0; c < eob && budget > 0; c++) {
                if (c > 0 && random.nextInt(3) == 0) {
                    continue;
                }
                var magnitude = Math.min(budget, 1 + random.nextInt(c == 0 ? 1200 : 200));
                budget -= magnitude;
                var value = random.nextBoolean() ? magnitude : -magnitude;
                scalarCoef[4 + scalarLayout[scan[c]]] = value;
                coef[4 + vectorLayout[scan[c]]] = value;
            }
            var dst = random.nextInt(3) == 0 ? smoothPlane(random, STRIDE * 40, 4) : randomPlane(random, STRIDE * 40);
            var expected = dst.clone();
            var actual = dst.clone();
            SCALAR.inverseTransformAdd(txSize, txType, eob, scalarCoef, 4, expected, 3 * STRIDE + 5, STRIDE,
                    new int[Vp9Dsp.TRANSFORM_SCRATCH]);
            VECTOR.inverseTransformAdd(txSize, txType, eob, coef, 4, actual, 3 * STRIDE + 5, STRIDE,
                    new int[Vp9Dsp.TRANSFORM_SCRATCH]);
            assertArrayEquals(expected, actual, "trial " + trial + ": size " + size + " type " + txType + " eob " + eob);
        }
    }

    @Test
    @DisplayName("intra predictors for every mode, size and available edge")
    void intraPredictors() {
        var random = new Random(4);
        for (var trial = 0; trial < 4000; trial++) {
            var mode = random.nextInt(10);
            var txSize = random.nextInt(4);
            var dcEdges = random.nextInt(4);
            var edges = random.nextInt(3) == 0 ? smoothPlane(random, 256, 8) : randomPlane(random, 256);
            var dst = randomPlane(random, STRIDE * 40);
            var expected = dst.clone();
            var actual = dst.clone();
            SCALAR.intraPredict(mode, txSize, dcEdges, expected, 2 * STRIDE + 3, STRIDE, edges, 17, edges, 150);
            VECTOR.intraPredict(mode, txSize, dcEdges, actual, 2 * STRIDE + 3, STRIDE, edges, 17, edges, 150);
            assertArrayEquals(expected, actual, "trial " + trial + ": mode " + mode + " size " + (4 << txSize));
        }
    }
}
