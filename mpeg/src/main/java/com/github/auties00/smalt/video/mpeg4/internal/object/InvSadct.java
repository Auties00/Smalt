/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * tools/sadct/sadct.cpp: CSADCT and CInvSADCT, without the fast transform). Copyright 1998-2000 Fraunhofer HHI,
 * Microsoft Corporation and the contributors named in the sources; ISO/IEC free license for products conforming to
 * ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The inverse shape-adaptive DCT of boundary blocks (7.4.4.2): columns and rows of variable length, with the DC
 * correction of intra blocks ({@code CInvSADCT}). Blocks without a mask take the plain inverse DCT.
 *
 * <p>Like the reference software, the transform writes all 64 samples of a boundary block: the samples outside the
 * mask keep what the previous transforms of the instance left in its output matrix.
 */
final class InvSadct extends InvDct {
    private static final int N = 8;

    private final double[][][] idctMatrix = new double[N + 1][][];
    private final int[] mask = new int[N * N];
    private final double[] in = new double[N * N];
    private final double[] out = new double[N * N];
    private final double[] tmp = new double[N * N];
    private final int[] ly = new int[N];
    private final int[] lx = new int[N];
    private final int[][] reorderV = new int[N][N];
    private final int[][] reorderH = new int[N][N];

    InvSadct(int nBits) {
        super(nBits);
        for (var n = 1; n <= N; n++) {
            var mat = new double[N][N];
            var factcos = Math.PI / (2 * n);
            var a = Math.sqrt(2.0 / n);
            for (var x = 0; x < n; x++) {
                for (var u = 0; u < n; u++) {
                    mat[x][u] = a * StrictMath.cos(factcos * u * (2 * x + 1));
                    if (u == 0) {
                        mat[x][u] /= Math.sqrt(2.0);
                    }
                }
            }
            idctMatrix[n] = mat;
        }
    }

    @Override
    void applyClipped(int[] src, int srcOffset, int srcStride, int[] dst, int dstOffset, int dstStride, int[] mask,
                      int maskOffset, int maskStride) {
        if (mask == null) {
            applyClipped(src, srcOffset, srcStride, dst, dstOffset, dstStride);
            return;
        }
        prepare(src, srcOffset, srcStride, mask, maskOffset, maskStride);
        deltaTransform();
        for (var i = 0; i < N; i++) {
            for (var j = 0; j < N; j++) {
                var v = out[i * N + j];
                var value = v < 0 ? (int) (v - .5) : (int) (v + .5);
                dst[dstOffset + i * dstStride + j] = value < 0 ? 0 : Math.min(value, maxValue);
            }
        }
    }

    @Override
    void applyResidual(int[] src, int srcOffset, int srcStride, int[] dst, int dstOffset, int dstStride, int[] mask,
                       int maskOffset, int maskStride) {
        if (mask == null) {
            applyResidual(src, srcOffset, srcStride, dst, dstOffset, dstStride);
            return;
        }
        prepare(src, srcOffset, srcStride, mask, maskOffset, maskStride);
        transform();
        for (var i = 0; i < N; i++) {
            for (var j = 0; j < N; j++) {
                var v = out[i * N + j];
                dst[dstOffset + i * dstStride + j] = v < 0 ? (int) (v - .5) : (int) (v + .5);
            }
        }
    }

    private void prepare(int[] src, int srcOffset, int srcStride, int[] shape, int maskOffset, int maskStride) {
        for (var iy = 0; iy < N; iy++) {
            for (var ix = 0; ix < N; ix++) {
                mask[iy * N + ix] = shape[maskOffset + iy * maskStride + ix] != 0 ? 1 : 0;
                in[iy * N + ix] = src[srcOffset + iy * srcStride + ix];
            }
        }
    }

    /**
     * Computes the number of coefficients of each row of a boundary block from its shape
     * ({@code getRowLength}).
     *
     * @param result receives the row lengths
     */
    static void rowLengths(int[] result, int[] shape, int offset, int stride, int[] columns) {
        var out = 0;
        for (var ix = 0; ix < N; ix++) {
            var l = 0;
            for (var iy = 0; iy < N; iy++) {
                if (shape[offset + iy * stride + ix] != 0) {
                    l++;
                }
            }
            if (l != 0) {
                columns[out++] = l;
            }
        }
        for (var ix = out; ix < N; ix++) {
            columns[ix] = 0;
        }
        for (var iy = 0; iy < N; iy++) {
            var l = 0;
            for (var ix = 0; ix < N; ix++) {
                if (columns[ix] > iy) {
                    l++;
                }
            }
            result[iy] = l;
        }
    }

    private void deltaTransform() {
        var meanValue = (double) (int) ((in[0] / 8.0) + 0.5);
        in[0] = 0.0;
        java.util.Arrays.fill(out, 0.0);
        transform();
        var checkSum = 0.0;
        for (var i = 0; i < N; i++) {
            for (var j = 0; j < N; j++) {
                if (mask[i * N + j] != 0) {
                    checkSum += out[i * N + j];
                }
            }
        }
        var lySum = 0.0;
        for (var i = 0; i < N; i++) {
            if (ly[i] != 0) {
                lySum += Math.sqrt(ly[i]);
            }
        }
        var k = 0;
        var ddx = 0.0;
        for (var i = 0; i < N; i++) {
            var l = 0;
            for (var j = 0; j < N; j++) {
                if (mask[j * N + i] != 0) {
                    if (l == 0) {
                        k++;
                        l++;
                        var factor = 1.0 / (Math.sqrt(ly[k - 1]) * lySum) * checkSum;
                        ddx = checkSum > 0 ? (int) (factor + 0.5) : (int) (factor - 0.5);
                    }
                    out[j * N + i] += meanValue - ddx;
                }
            }
        }
    }

    private void transform() {
        var columnsOut = 0;
        for (var ix = 0; ix < N; ix++) {
            var row = 0;
            var l = 0;
            for (var iy = 0; iy < N; iy++) {
                if (mask[iy * N + ix] != 0) {
                    reorderV[columnsOut][row++] = iy * N + ix;
                    l++;
                }
            }
            if (l != 0) {
                ly[columnsOut++] = l;
            }
        }
        for (var ix = columnsOut; ix < N; ix++) {
            ly[ix] = 0;
        }
        java.util.Arrays.fill(lx, 0);
        for (var i = 0; i < N && ly[i] != 0; i++) {
            var jmax = ly[i];
            for (var k = 0; k < jmax; k++) {
                reorderH[k][lx[k]] = i * N + k;
                lx[k]++;
            }
        }
        for (var i = 0; i < N && lx[i] != 0; i++) {
            var jmax = lx[i];
            var trf = idctMatrix[jmax];
            var dest = reorderH[i];
            for (var k = 0; k < jmax; k++) {
                var c = 0.0;
                for (var j = 0; j < jmax; j++) {
                    c += trf[k][j] * in[i * N + j];
                }
                tmp[dest[k]] = c;
            }
        }
        for (var i = 0; i < N && ly[i] != 0; i++) {
            var jmax = ly[i];
            var trf = idctMatrix[jmax];
            var dest = reorderV[i];
            for (var k = 0; k < jmax; k++) {
                var c = 0.0;
                for (var j = 0; j < jmax; j++) {
                    c += trf[k][j] * tmp[i * N + j];
                }
                out[dest[k]] = c;
            }
        }
    }
}
