package com.github.auties00.smalt.video.vp9.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API kernels of the VP9 encoder produce exactly the scalar kernels'
 * results, on random and extreme samples, for every block shape the encoder uses.
 */
@DisplayName("VP9 encoder pixel kernels: vector equals scalar")
class Vp9EncDspEquivalenceTest {
    private static final ScalarVp9EncDsp SCALAR = new ScalarVp9EncDsp();

    private static final VectorVp9EncDsp VECTOR = new VectorVp9EncDsp();

    private static byte[] plane(Random random, int size, int mode) {
        var plane = new byte[size];
        for (var i = 0; i < size; i++) {
            plane[i] = switch (mode) {
                case 0 -> (byte) random.nextInt(256);
                case 1 -> (byte) (random.nextBoolean() ? 0 : 255);
                default -> (byte) (128 + random.nextInt(9) - 4);
            };
        }
        return plane;
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"4, 4", "4, 8", "8, 4", "8, 8", "8, 16", "16, 8", "16, 16", "16, 32", "32, 16", "32, 32", "32, 64",
            "64, 32", "64, 64"})
    @DisplayName("sums of differences, variances, sub-pixel variances and residuals agree")
    void kernels(int w, int h) {
        var random = new Random(w * 131L + h);
        var stride = 160;
        var scratchA = new short[Vp9EncDsp.SUBPEL_SCRATCH];
        var scratchB = new short[Vp9EncDsp.SUBPEL_SCRATCH];
        for (var mode = 0; mode < 3; mode++) {
            for (var trial = 0; trial < 20; trial++) {
                var a = plane(random, stride * 80, mode);
                var b = plane(random, stride * 80, mode);
                var aOff = random.nextInt(16) + stride * random.nextInt(8);
                var bOff = random.nextInt(16) + stride * random.nextInt(8);
                assertEquals(SCALAR.sad(a, aOff, stride, b, bOff, stride, w, h),
                        VECTOR.sad(a, aOff, stride, b, bOff, stride, w, h), "sad");
                var sadsA = new int[4];
                var sadsB = new int[4];
                SCALAR.sad4(a, aOff, stride, b, bOff, bOff + 1, bOff + stride, bOff + 3 * stride + 5, stride, w, h, sadsA);
                VECTOR.sad4(a, aOff, stride, b, bOff, bOff + 1, bOff + stride, bOff + 3 * stride + 5, stride, w, h, sadsB);
                assertArrayEquals(sadsA, sadsB, "sad4");
                assertEquals(SCALAR.variance(a, aOff, stride, b, bOff, stride, w, h),
                        VECTOR.variance(a, aOff, stride, b, bOff, stride, w, h), "variance");
                for (var x = 0; x < 8; x++) {
                    for (var y = 0; y < 8; y++) {
                        assertEquals(SCALAR.subPixelVariance(a, aOff, stride, x, y, b, bOff, stride, w, h, scratchA),
                                VECTOR.subPixelVariance(a, aOff, stride, x, y, b, bOff, stride, w, h, scratchB),
                                "sub-pixel variance " + x + "," + y);
                    }
                }
                var da = new short[64 * 64];
                var db = new short[64 * 64];
                SCALAR.subtract(da, 0, w, a, aOff, stride, b, bOff, stride, w, h);
                VECTOR.subtract(db, 0, w, a, aOff, stride, b, bOff, stride, w, h);
                assertArrayEquals(da, db, "subtract");
            }
        }
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"8, 8", "16, 16", "16, 32", "32, 32", "32, 64", "64, 32", "64, 64"})
    @DisplayName("maximal differences fill the accumulators without overflow")
    void saturated(int w, int h) {
        var stride = 160;
        var low = new byte[stride * 80];
        var high = new byte[stride * 80];
        java.util.Arrays.fill(high, (byte) 255);
        var scratchA = new short[Vp9EncDsp.SUBPEL_SCRATCH];
        var scratchB = new short[Vp9EncDsp.SUBPEL_SCRATCH];
        for (var flip = 0; flip < 2; flip++) {
            var a = flip == 0 ? low : high;
            var b = flip == 0 ? high : low;
            assertEquals(SCALAR.sad(a, 3, stride, b, 5, stride, w, h), VECTOR.sad(a, 3, stride, b, 5, stride, w, h),
                    "sad");
            var sadsA = new int[4];
            var sadsB = new int[4];
            SCALAR.sad4(a, 3, stride, b, 5, 6, 7, 8, stride, w, h, sadsA);
            VECTOR.sad4(a, 3, stride, b, 5, 6, 7, 8, stride, w, h, sadsB);
            assertArrayEquals(sadsA, sadsB, "sad4");
            assertEquals(SCALAR.variance(a, 3, stride, b, 5, stride, w, h),
                    VECTOR.variance(a, 3, stride, b, 5, stride, w, h), "variance");
            for (var x = 0; x < 8; x += 3) {
                for (var y = 0; y < 8; y += 3) {
                    assertEquals(SCALAR.subPixelVariance(a, 3, stride, x, y, b, 5, stride, w, h, scratchA),
                            VECTOR.subPixelVariance(a, 3, stride, x, y, b, 5, stride, w, h, scratchB),
                            "sub-pixel variance " + x + "," + y);
                }
            }
        }
    }

    @ParameterizedTest(name = "tx size {0}")
    @CsvSource({"0", "1", "2", "3"})
    @DisplayName("fast quantization, coefficient magnitudes and errors agree")
    void coefficients(int txSize) {
        var random = new Random(txSize * 977L + 5);
        var quantizer = new Vp9Quantizer();
        var n = 16 << (txSize * 2);
        var off = 48;
        var coeff = new int[off + n];
        for (var qindex : new int[]{0, 1, 20, 60, 120, 200, 255}) {
            var pidx = qindex * 2;
            for (var t = 0; t < 4; t++) {
                var scan = Vp9RtCoder.SCANS[txSize * 4 + t];
                var iscan = Vp9RtCoder.ISCANS[txSize * 4 + t];
                for (var trial = 0; trial < 30; trial++) {
                    var range = switch (trial % 3) {
                        case 0 -> 1 << 6;
                        case 1 -> 1 << 12;
                        default -> 1 << 18;
                    };
                    for (var i = 0; i < n; i++) {
                        coeff[off + i] = random.nextInt(4) == 0 ? random.nextInt(2 * range) - range
                                : random.nextInt(9) - 4;
                    }
                    var qa = new int[off + n];
                    var da = new int[off + n];
                    var qb = new int[off + n];
                    var db = new int[off + n];
                    var is32 = txSize == 3;
                    assertEquals(SCALAR.quantizeFp(coeff, off, n, qa, da, scan, iscan, quantizer.yRoundFp,
                                    quantizer.yQuantFp, quantizer.yDequant, pidx, is32),
                            VECTOR.quantizeFp(coeff, off, n, qb, db, scan, iscan, quantizer.yRoundFp,
                                    quantizer.yQuantFp, quantizer.yDequant, pidx, is32), "eob");
                    assertArrayEquals(qa, qb, "qcoeff");
                    assertArrayEquals(da, db, "dqcoeff");
                    assertEquals(SCALAR.satd(qa, off, n), VECTOR.satd(qb, off, n), "satd");
                    if (!is32) {
                        // the fused kernel's contract limits coefficients to 16 bits
                        var narrow = new int[off + n];
                        for (var i = 0; i < n; i++) {
                            narrow[off + i] = Math.clamp(coeff[off + i], -32768, 32767);
                        }
                        var rdA = new long[2];
                        var rdB = new long[2];
                        assertEquals(SCALAR.quantizeFpRd(narrow, off, n, new int[off + n], new int[off + n], scan,
                                        iscan, quantizer.yRoundFp, quantizer.yQuantFp, quantizer.yDequant, pidx, rdA),
                                VECTOR.quantizeFpRd(narrow, off, n, new int[off + n], new int[off + n], scan, iscan,
                                        quantizer.yRoundFp, quantizer.yQuantFp, quantizer.yDequant, pidx, rdB),
                                "fused eob");
                        assertArrayEquals(rdA, rdB, "fused magnitudes and error");
                    }
                    assertEquals(SCALAR.blockErrorFp(coeff, da, off, n), VECTOR.blockErrorFp(coeff, db, off, n),
                            "error");
                }
            }
        }
    }

    @ParameterizedTest(name = "range {0}")
    @CsvSource({"9", "256", "511", "65536"})
    @DisplayName("8x8 and 16x16 Hadamard transforms agree, including the 16-bit wrap-around")
    void hadamard(int range) {
        var random = new Random(range * 7919L);
        var stride = 40;
        for (var trial = 0; trial < 50; trial++) {
            var diff = new short[stride * 20];
            for (var i = 0; i < diff.length; i++) {
                diff[i] = (short) (trial % 5 == 0 ? (random.nextBoolean() ? range / 2 : -range / 2)
                        : random.nextInt(range) - range / 2);
            }
            var off = random.nextInt(8) + stride * random.nextInt(4);
            var a = new int[300];
            var b = new int[300];
            SCALAR.hadamard8x8(diff, off, stride, a, 7, new short[64]);
            VECTOR.hadamard8x8(diff, off, stride, b, 7, new short[64]);
            assertArrayEquals(a, b, "8x8");
            SCALAR.hadamard16x16(diff, off, stride, a, 11, new short[64]);
            VECTOR.hadamard16x16(diff, off, stride, b, 11, new short[64]);
            assertArrayEquals(a, b, "16x16");
        }
    }

    @ParameterizedTest(name = "mode {0}")
    @CsvSource({"0", "1", "2"})
    @DisplayName("the four 8x8 averages of a 16x16 block agree")
    void averages(int mode) {
        var random = new Random(mode * 7L + 3);
        var stride = 80;
        for (var trial = 0; trial < 100; trial++) {
            var a = plane(random, stride * 40, mode);
            var off = random.nextInt(32) + stride * random.nextInt(16);
            var expected = new int[4];
            var actual = new int[4];
            SCALAR.avg8x8Quad(a, off, stride, expected);
            VECTOR.avg8x8Quad(a, off, stride, actual);
            assertArrayEquals(expected, actual);
        }
    }

    @ParameterizedTest(name = "range {0}")
    @CsvSource({"3", "64", "511"})
    @DisplayName("8x8 cosine transforms of 8-bit residuals agree, including extreme blocks")
    void forwardTransforms(int range) {
        var random = new Random(range * 104729L);
        var stride = 40;
        for (var trial = 0; trial < 200; trial++) {
            var diff = new short[stride * 20];
            for (var i = 0; i < diff.length; i++) {
                diff[i] = (short) switch (trial % 4) {
                    case 0 -> random.nextBoolean() ? 255 : -255;
                    case 1 -> ((i % stride + i / stride) & 1) == 0 ? 255 : -255;
                    default -> random.nextInt(range) - range / 2;
                };
            }
            var off = random.nextInt(8) + stride * random.nextInt(4);
            var a = new int[100];
            var b = new int[100];
            SCALAR.fdct8x8(diff, off, stride, a, 5, new long[128]);
            VECTOR.fdct8x8(diff, off, stride, b, 5, new long[128]);
            assertArrayEquals(a, b, "8x8");
        }
    }

    @ParameterizedTest(name = "{0}x{0}, range {1}")
    @CsvSource({"4, 3", "4, 511", "16, 3", "16, 64", "16, 511", "32, 3", "32, 64", "32, 511"})
    @DisplayName("4x4, 16x16 and 32x32 cosine transforms of 8-bit residuals agree, including extreme blocks")
    void largeForwardTransforms(int size, int range) {
        var random = new Random(size * 7919L + range);
        var stride = size + 24;
        for (var trial = 0; trial < (size == 32 ? 60 : 150); trial++) {
            var diff = new short[stride * (size + 8)];
            var sign = random.nextBoolean() ? 255 : -255;
            for (var i = 0; i < diff.length; i++) {
                // Constant extremes give the largest sums, checkerboards the largest high frequencies.
                diff[i] = (short) switch (trial % 6) {
                    case 0 -> random.nextBoolean() ? 255 : -255;
                    case 1 -> ((i % stride + i / stride) & 1) == 0 ? 255 : -255;
                    case 2 -> sign;
                    case 3 -> (i % stride) < size / 2 ? sign : -sign;
                    default -> random.nextInt(range) - range / 2;
                };
            }
            var off = random.nextInt(8) + stride * random.nextInt(4);
            var a = new int[size * size + 9];
            var b = new int[size * size + 9];
            switch (size) {
                case 4 -> {
                    SCALAR.fdct4x4(diff, off, stride, a, 9, new long[32]);
                    VECTOR.fdct4x4(diff, off, stride, b, 9, new long[32]);
                    assertArrayEquals(a, b, "4x4");
                }
                case 16 -> {
                    SCALAR.fdct16x16(diff, off, stride, a, 9, new long[288], new int[Vp9EncDsp.FDCT_SCRATCH]);
                    VECTOR.fdct16x16(diff, off, stride, b, 9, new long[288], new int[Vp9EncDsp.FDCT_SCRATCH]);
                    assertArrayEquals(a, b, "16x16");
                }
                default -> {
                    for (var rd : new boolean[]{false, true}) {
                        SCALAR.fdct32x32(diff, off, stride, a, 9, rd, new long[1200], new int[Vp9EncDsp.FDCT_SCRATCH]);
                        VECTOR.fdct32x32(diff, off, stride, b, 9, rd, new long[1200], new int[Vp9EncDsp.FDCT_SCRATCH]);
                        assertArrayEquals(a, b, "32x32 rd " + rd);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}x{0}")
    @CsvSource({"16", "32", "64"})
    @DisplayName("integral projections and their variances agree")
    void projections(int size) {
        var random = new Random(size * 31L);
        var stride = 160;
        var bwl = Integer.numberOfTrailingZeros(size) - 2;
        for (var mode = 0; mode < 3; mode++) {
            for (var trial = 0; trial < 20; trial++) {
                var a = plane(random, stride * 80, mode);
                var off = random.nextInt(16) + stride * random.nextInt(8);
                var ha = new short[80];
                var hb = new short[80];
                for (var x = 0; x < size; x += 16) {
                    SCALAR.intProRow(ha, 8 + x, a, off + x, stride, size);
                    VECTOR.intProRow(hb, 8 + x, a, off + x, stride, size);
                }
                assertArrayEquals(ha, hb, "row projection");
                for (var r = 0; r < size; r++) {
                    assertEquals(SCALAR.intProCol(a, off + r * stride, size), VECTOR.intProCol(a, off + r * stride,
                            size), "column projection");
                }
                var other = new short[80];
                for (var i = 0; i < other.length; i++) {
                    other[i] = (short) (mode == 1 ? (random.nextBoolean() ? 0 : 510) : random.nextInt(511));
                }
                var start = random.nextInt(8);
                assertEquals(SCALAR.vectorVar(ha, 8, other, start, bwl), VECTOR.vectorVar(hb, 8, other, start, bwl),
                        "vector variance");
            }
        }
    }
}
