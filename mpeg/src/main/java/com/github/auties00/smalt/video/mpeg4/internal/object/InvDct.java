/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/dct.cpp: CBlockDCT and CInvBlockDCT, double precision). Copyright 1996-2000 Microsoft Corporation and the
 * contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for
 * Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The separable floating-point 8x8 inverse DCT of the reference software, rows first, in double precision
 * ({@code CInvBlockDCT}); its results are the reference decoder's to the bit. The shape-adaptive inverse DCT extends
 * it ({@link InvSadct}).
 */
class InvDct {
    private static final double C0 = 0.7071068;
    private static final double C1 = 0.4903926;
    private static final double C2 = 0.4619398;
    private static final double C3 = 0.4157348;
    private static final double C4 = 0.3535534;
    private static final double C5 = 0.2777851;
    private static final double C6 = 0.1913417;
    private static final double C7 = 0.0975452;

    final int maxValue;
    private final double[] buf1 = new double[8];
    private final double[] buf2 = new double[8];
    private final double[] out = new double[8];
    private final double[] afterRows = new double[64];

    InvDct(int nBits) {
        this.maxValue = (1 << nBits) - 1;
    }

    /**
     * Transforms coefficients into samples clipped to the sample range.
     */
    void applyClipped(int[] src, int srcOffset, int srcStride, int[] dst, int dstOffset, int dstStride) {
        rows(src, srcOffset, srcStride);
        for (var col = 0; col < 8; col++) {
            column(col);
            for (var j = 0; j < 8; j++) {
                var value = round(out[j]);
                dst[dstOffset + j * dstStride + col] = value < 0 ? 0 : Math.min(value, maxValue);
            }
        }
    }

    /**
     * Transforms coefficients into unclipped residuals.
     */
    void applyResidual(int[] src, int srcOffset, int srcStride, int[] dst, int dstOffset, int dstStride) {
        rows(src, srcOffset, srcStride);
        for (var col = 0; col < 8; col++) {
            column(col);
            for (var j = 0; j < 8; j++) {
                dst[dstOffset + j * dstStride + col] = round(out[j]);
            }
        }
    }

    /**
     * Transforms coefficients into samples clipped to the sample range; the shape-adaptive transform uses the mask.
     */
    void applyClipped(int[] src, int srcOffset, int srcStride, int[] dst, int dstOffset, int dstStride, int[] mask,
                      int maskOffset, int maskStride) {
        applyClipped(src, srcOffset, srcStride, dst, dstOffset, dstStride);
    }

    /**
     * Transforms coefficients into unclipped residuals; the shape-adaptive transform uses the mask.
     */
    void applyResidual(int[] src, int srcOffset, int srcStride, int[] dst, int dstOffset, int dstStride, int[] mask,
                       int maskOffset, int maskStride) {
        applyResidual(src, srcOffset, srcStride, dst, dstOffset, dstStride);
    }

    private static int round(double value) {
        return value >= 0 ? (int) (value + .5) : (int) (value - .5);
    }

    private void rows(int[] src, int offset, int stride) {
        for (var row = 0; row < 8; row++) {
            for (var j = 0; j < 8; j++) {
                buf1[j] = src[offset + row * stride + j];
            }
            oneDimensional();
            System.arraycopy(out, 0, afterRows, row * 8, 8);
        }
    }

    private void column(int col) {
        for (var j = 0; j < 8; j++) {
            buf1[j] = afterRows[j * 8 + col];
        }
        oneDimensional();
    }

    private void oneDimensional() {
        var b1 = buf1;
        var b2 = buf2;
        double flt1 = b1[1] * C7 - b1[7] * C1;
        double flt2 = b1[7] * C7 + b1[1] * C1;
        double flt3 = b1[5] * C3 - b1[3] * C5;
        double flt4 = b1[3] * C3 + b1[5] * C5;
        b2[0] = (b1[0] + b1[4]) * C4;
        b2[1] = (b1[0] - b1[4]) * C4;
        b2[2] = b1[2] * C6 - b1[6] * C2;
        b2[3] = b1[6] * C6 + b1[2] * C2;
        b1[4] = flt1 + flt3;
        b2[5] = flt1 - flt3;
        b2[6] = flt2 - flt4;
        b1[7] = flt2 + flt4;
        b1[5] = (b2[6] - b2[5]) * C0;
        b1[6] = (b2[6] + b2[5]) * C0;
        b1[0] = b2[0] + b2[3];
        b1[1] = b2[1] + b2[2];
        b1[2] = b2[1] - b2[2];
        b1[3] = b2[0] - b2[3];
        for (var j = 0; j < 4; j++) {
            var j1 = 7 - j;
            out[j] = b1[j] + b1[j1];
            out[j1] = b1[j] - b1[j1];
        }
    }
}
