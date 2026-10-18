package com.github.auties00.smalt.video.av1.internal.encoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API kernels of the AV1 encoder return exactly what the scalar kernels
 * return, over every block shape the encoder uses, on random, flat and extreme samples.
 */
class EncDspEquivalenceTest {
    private static final int STRIDE = 160;

    private static final ScalarEncDsp SCALAR = new ScalarEncDsp();

    private static final VectorEncDsp VECTOR = new VectorEncDsp();

    static List<Arguments> shapes() {
        var out = new ArrayList<Arguments>();
        int[] sizes = {2, 4, 6, 8, 16, 24, 32, 64};
        for (var w : sizes) {
            for (var h : sizes) {
                for (var pattern = 0; pattern < 3; pattern++) {
                    out.add(Arguments.of(w, h, pattern));
                }
            }
        }
        return out;
    }

    private static short[] plane(int pattern, long seed) {
        var random = new Random(seed);
        var p = new short[STRIDE * 80];
        for (var i = 0; i < p.length; i++) {
            p[i] = (short) switch (pattern) {
                case 0 -> random.nextInt(256);
                case 1 -> 128;
                default -> random.nextBoolean() ? 255 : 0;
            };
        }
        return p;
    }

    @Nested
    @DisplayName("pixel kernels")
    class Pixels {
        @ParameterizedTest(name = "{0}x{1} pattern {2}")
        @MethodSource("com.github.auties00.smalt.video.av1.internal.encoder.EncDspEquivalenceTest#shapes")
        @DisplayName("sad, sseSum, avgDiff8x8, subtract and subpelSseSum match the scalar kernels")
        void pixels(int w, int h, int pattern) {
            var a = plane(pattern, 1 + w * 100L + h);
            var b = plane(pattern == 1 ? 0 : pattern, 7 + w * 31L + h);
            for (var off = 0; off < 3; off++) {
                var bo = 2 * STRIDE + 5 + off;
                assertEquals(SCALAR.sad(a, off, STRIDE, b, bo, STRIDE, w, h),
                        VECTOR.sad(a, off, STRIDE, b, bo, STRIDE, w, h));
                assertEquals(SCALAR.sseSum(a, off, STRIDE, b, bo, STRIDE, w, h),
                        VECTOR.sseSum(a, off, STRIDE, b, bo, STRIDE, w, h));
                assertEquals(SCALAR.planeSse(a, b, off, STRIDE, w, h), VECTOR.planeSse(a, b, off, STRIDE, w, h));
                if (w == 8 && h == 8) {
                    assertEquals(SCALAR.avgDiff8x8(a, off, STRIDE, b, bo, STRIDE),
                            VECTOR.avgDiff8x8(a, off, STRIDE, b, bo, STRIDE));
                }
                if (w == 16 && h == 16) {
                    var res = new int[16 * 16];
                    SCALAR.subtract(a, off, STRIDE, b, bo, STRIDE, res, 16, 16);
                    var h1 = new int[256];
                    var h2 = new int[256];
                    SCALAR.hadamard16x16(res, 0, 16, h1, 0, new int[64]);
                    VECTOR.hadamard16x16(res, 0, 16, h2, 0, new int[64]);
                    assertArrayEquals(h1, h2);
                    SCALAR.hadamard8x8(res, 8, 16, h1, 64, new int[64]);
                    VECTOR.hadamard8x8(res, 8, 16, h2, 64, new int[64]);
                    assertArrayEquals(h1, h2);
                }
                var d1 = new int[64 * 64];
                var d2 = new int[64 * 64];
                SCALAR.subtract(a, off, STRIDE, b, bo, STRIDE, d1, w, h);
                VECTOR.subtract(a, off, STRIDE, b, bo, STRIDE, d2, w, h);
                assertArrayEquals(d1, d2);
                for (var x = 0; x < 8; x++) {
                    for (var y = 0; y < 8; y += 3) {
                        assertEquals(SCALAR.subpelSseSum(b, bo, STRIDE, x, y, a, off, STRIDE, w, h, new short[65 * 64]),
                                VECTOR.subpelSseSum(b, bo, STRIDE, x, y, a, off, STRIDE, w, h, new short[65 * 64]),
                                "subpel " + x + "," + y);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("compound averaging")
    class Averaging {
        @Test
        @DisplayName("average matches the scalar kernel for every length, offset and extreme sample")
        void average() {
            var random = new Random(5);
            for (var n = 0; n <= 70; n++) {
                for (var offset = 0; offset < 3; offset++) {
                    var a = new short[80 + offset];
                    var b = new short[80 + offset];
                    for (var i = 0; i < a.length; i++) {
                        a[i] = (short) (n % 3 == 0 ? random.nextInt(256) : n % 3 == 1 ? 255 : 0);
                        b[i] = (short) (n % 3 == 0 ? random.nextInt(256) : 255);
                    }
                    var expected = a.clone();
                    var actual = a.clone();
                    SCALAR.average(expected, offset, b, offset, n);
                    VECTOR.average(actual, offset, b, offset, n);
                    assertArrayEquals(expected, actual, "n " + n + " offset " + offset);
                }
            }
        }
    }

    @Nested
    @DisplayName("forward transforms")
    class Transforms {
        @Test
        @DisplayName("the vector transforms match the scalar ones at every size and type")
        void transforms() {
            var random = new Random(9);
            for (var tx = 0; tx < 19; tx++) {
                var w = com.github.auties00.smalt.video.av1.internal.Tables.TX_W[tx] * 4;
                var h = com.github.auties00.smalt.video.av1.internal.Tables.TX_H[tx] * 4;
                for (var type = 0; type < 4; type++) {
                    if (type != 0 && (w > 16 || h > 16)) {
                        continue;
                    }
                    for (var pattern = 0; pattern < 4; pattern++) {
                        var res = new int[64 * 64 + 8];
                        for (var i = 0; i < res.length; i++) {
                            res[i] = switch (pattern) {
                                case 0 -> random.nextInt(511) - 255;
                                case 1 -> 255;
                                case 2 -> (i & 1) == 0 ? 255 : -255;
                                default -> ((i / 64 + i) & 1) == 0 ? 255 : -255;
                            };
                        }
                        var a = new int[32 * 32];
                        var b = new int[32 * 32];
                        SCALAR.forwardTransform(new FwdTxfm(), tx, type, res, 3, 64, a);
                        VECTOR.forwardTransform(new FwdTxfm(), tx, type, res, 3, 64, b);
                        assertArrayEquals(a, b, "tx " + tx + " type " + type + " pattern " + pattern);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("coefficient kernels")
    class Coefficients {
        @Test
        @DisplayName("quantizeLp, satd and blockError match the scalar kernels")
        void coefficients() {
            var random = new Random(42);
            for (var n : new int[]{16, 64, 256}) {
                var scan = new int[n];
                var iscan = new int[n];
                for (var i = 0; i < n; i++) {
                    scan[i] = (i * (n == 64 ? 5 : 7)) & (n - 1);
                    iscan[scan[i]] = i + 1;
                }
                for (var round = 0; round < 200; round++) {
                    var coeff = new int[n + 3];
                    var sparse = round % 3;
                    for (var i = 0; i < coeff.length; i++) {
                        coeff[i] = sparse == 0 ? random.nextInt(65535) - 32767
                                : random.nextInt(sparse * 10) == 0 ? random.nextInt(4001) - 2000 : 0;
                    }
                    int[] roundFp = {random.nextInt(200), random.nextInt(200)};
                    int[] quantFp = {1 + random.nextInt(16384), 1 + random.nextInt(16384)};
                    int[] dequant = {4 + random.nextInt(1000), 4 + random.nextInt(1000)};
                    var q1 = new int[n];
                    var q2 = new int[n];
                    var dq1 = new int[n];
                    var dq2 = new int[n];
                    var off = round % 3;
                    assertEquals(SCALAR.quantizeLp(coeff, off, n, roundFp, quantFp, dequant, scan, iscan, q1, dq1),
                            VECTOR.quantizeLp(coeff, off, n, roundFp, quantFp, dequant, scan, iscan, q2, dq2));
                    assertArrayEquals(q1, q2);
                    assertArrayEquals(dq1, dq2);
                    assertEquals(SCALAR.satd(q1, n), VECTOR.satd(q1, n));
                    var near = new int[n];
                    for (var i = 0; i < n; i++) {
                        near[i] = coeff[off + i] + random.nextInt(80001) - 40000;
                    }
                    assertEquals(SCALAR.blockError(coeff, off, near, n), VECTOR.blockError(coeff, off, near, n));
                }
            }
        }

        @Test
        @DisplayName("the level error matches on every kernel set")
        void levelError() {
            var random = new Random(91);
            for (var n : new int[]{16, 64, 256, 1024}) {
                for (var round = 0; round < 300; round++) {
                    var coeff = new int[n];
                    var levels = new int[n];
                    for (var i = 0; i < n; i++) {
                        coeff[i] = round % 3 == 0 ? random.nextInt(131071) - 65535 : random.nextInt(4001) - 2000;
                        levels[i] = random.nextInt(4) == 0 ? random.nextInt(61) - 30 : 0;
                    }
                    var dcDq = 4 + random.nextInt(1500);
                    var acDq = 4 + random.nextInt(1500);
                    var logScale = round % 3;
                    assertEquals(SCALAR.levelError(coeff, levels, n, dcDq, acDq, logScale),
                            VECTOR.levelError(coeff, levels, n, dcDq, acDq, logScale));
                }
            }
        }

        @Test
        @DisplayName("the dead-zone quantizer matches the scan-order rule of aom_quantize_b on every kernel set")
        void deadZone() {
            var random = new Random(77);
            for (var n : new int[]{16, 64, 256, 1024}) {
                var scan = new int[n];
                for (var i = 0; i < n; i++) {
                    scan[i] = (int) ((i * 37L) % n);
                }
                var iscan = CoefWriter.inverseScan(scan);
                for (var round = 0; round < 400; round++) {
                    var qindex = random.nextInt(256);
                    var quantizer = new Quantizer();
                    quantizer.set(qindex, 0, 0);
                    var logScale = round % 3;
                    var coeff = new int[n];
                    var kind = round % 5;
                    for (var i = 0; i < n; i++) {
                        coeff[i] = switch (kind) {
                            case 0 -> random.nextInt(65535) - 32767;
                            case 1 -> random.nextInt(4001) - 2000;
                            case 2 -> random.nextInt(8) == 0 ? random.nextInt(2001) - 1000 : 0;
                            case 3 -> random.nextBoolean() ? 20000 : -20000;
                            default -> random.nextInt(201) - 100;
                        };
                    }
                    var expected = new int[n];
                    var expectedEob = legacy(quantizer, coeff, n, scan, logScale, expected);
                    var actual = new int[n];
                    assertEquals(expectedEob, quantizer.quantizeB(coeff, n, scan, iscan, logScale, actual), "eob " + qindex);
                    assertArrayEquals(expected, actual, "levels " + qindex);
                    var finish = 16 - logScale;
                    var add = (1 << logScale) >> 1;
                    for (var kernels : new EncDsp[]{SCALAR, VECTOR}) {
                        var levels = new int[n];
                        assertEquals(expectedEob, kernels.quantizeDz(coeff, n, iscan, levels,
                                (quantizer.zbin[0] + add) >> logScale, (quantizer.zbin[1] + add) >> logScale,
                                (quantizer.round[0] + add) >> logScale, (quantizer.round[1] + add) >> logScale,
                                quantizer.quant[0], quantizer.quant[1],
                                finish - Integer.numberOfTrailingZeros(quantizer.quantShift[0]),
                                finish - Integer.numberOfTrailingZeros(quantizer.quantShift[1]), Short.MAX_VALUE));
                        assertArrayEquals(expected, levels);
                    }
                }
            }
        }

        private static int legacy(Quantizer q, int[] coeff, int nCoeffs, int[] scan, int logScale, int[] qcoeff) {
            var zbin0 = (q.zbin[0] + ((1 << logScale) >> 1)) >> logScale;
            var zbin1 = (q.zbin[1] + ((1 << logScale) >> 1)) >> logScale;
            var round0 = (q.round[0] + ((1 << logScale) >> 1)) >> logScale;
            var round1 = (q.round[1] + ((1 << logScale) >> 1)) >> logScale;
            var nonZero = nCoeffs;
            for (var i = nCoeffs - 1; i >= 0; i--) {
                var rc = scan[i];
                var c = coeff[rc];
                var z = rc != 0 ? zbin1 : zbin0;
                if (c < z && c > -z) {
                    nonZero--;
                    qcoeff[rc] = 0;
                } else {
                    break;
                }
            }
            var eob = -1;
            var shift = 16 - logScale;
            for (var i = 0; i < nonZero; i++) {
                var rc = scan[i];
                var c = coeff[rc];
                var ac = rc != 0 ? 1 : 0;
                var abs = Math.abs(c);
                if (abs >= (ac != 0 ? zbin1 : zbin0)) {
                    long tmp = Math.clamp(abs + (ac != 0 ? round1 : round0), Short.MIN_VALUE, Short.MAX_VALUE);
                    var level = (int) ((((tmp * q.quant[ac]) >> 16) + tmp) * q.quantShift[ac] >> shift);
                    qcoeff[rc] = c < 0 ? -level : level;
                    if (level != 0) {
                        eob = i;
                    }
                } else {
                    qcoeff[rc] = 0;
                }
            }
            return eob + 1;
        }

        @Test
        @DisplayName("the fused quantizer measures what quantizeLp, satd and blockError measure")
        void fused() {
            var random = new Random(4242);
            for (var n : new int[]{16, 64, 256}) {
                var scan = new int[n];
                var iscan = new int[n];
                for (var i = 0; i < n; i++) {
                    scan[i] = (i * (n == 64 ? 5 : 7)) & (n - 1);
                    iscan[scan[i]] = i + 1;
                }
                for (var round = 0; round < 300; round++) {
                    var coeff = new int[n];
                    var sparse = round % 4;
                    for (var i = 0; i < n; i++) {
                        coeff[i] = switch (sparse) {
                            case 0 -> random.nextInt(65535) - 32767;
                            case 1 -> random.nextBoolean() ? 32767 : -32768;
                            default -> random.nextInt(sparse * 10) == 0 ? random.nextInt(4001) - 2000 : 0;
                        };
                    }
                    // quantizer pairs as av1_build_quantizer derives them: quant_fp = 2^16 / dequant
                    int[] dequant = {4 + random.nextInt(1333), 4 + random.nextInt(1825)};
                    int[] quantFp = {(1 << 16) / dequant[0], (1 << 16) / dequant[1]};
                    int[] roundFp = {dequant[0] * random.nextInt(128) >> 7, dequant[1] * random.nextInt(128) >> 7};
                    var q = new int[n];
                    var dq = new int[n];
                    var expectedEob = SCALAR.quantizeLp(coeff, 0, n, roundFp, quantFp, dequant, scan, iscan, q, dq);
                    var expected = new long[]{SCALAR.satd(q, n), SCALAR.blockError(coeff, 0, dq, n)};
                    for (var kernels : new EncDsp[]{SCALAR, VECTOR}) {
                        var result = new long[2];
                        assertEquals(expectedEob, kernels.quantizeLpRd(coeff, n, roundFp, quantFp, dequant, scan,
                                iscan, new int[n], new int[n], result), "eob");
                        assertArrayEquals(expected, result, "measures");
                    }
                }
            }
        }
    }
}
