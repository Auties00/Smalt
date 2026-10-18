package com.github.auties00.smalt.capture.internal.convert;

/**
 * Implements {@link ConversionKernel} in plain Java; the reference implementation, which also
 * converts the row ends the vector loops of {@link VectorConversionKernel} leave.
 */
public final class ScalarConversionKernel implements ConversionKernel {
    /**
     * Constructs the kernel.
     */
    public ScalarConversionKernel() {
    }

    @Override
    public void rgbToLuma(byte[] src, int off, int width, RgbLayout layout, YuvCoefficients coefficients,
                          byte[] y, int yOff) {
        rgbToLumaRange(src, off, layout, coefficients, y, yOff, 0, width);
    }

    /**
     * Computes the luma of the pixels {@code [from, to)} of a row.
     */
    void rgbToLumaRange(byte[] src, int off, RgbLayout layout, YuvCoefficients c, byte[] y, int yOff,
                        int from, int to) {
        var ri = off + layout.rIndex();
        var gi = off + layout.gIndex();
        var bi = off + layout.bIndex();
        for (var x = from; x < to; x++) {
            var r = src[ri + 4 * x] & 0xFF;
            var g = src[gi + 4 * x] & 0xFF;
            var b = src[bi + 4 * x] & 0xFF;
            y[yOff + x] = clamp((c.yr * r + c.yg * g + c.yb * b + c.yBias) >> YuvCoefficients.BITS);
        }
    }

    @Override
    public void rgbToChroma(byte[] src, int off0, int off1, int pairs, RgbLayout layout, YuvCoefficients coefficients,
                            byte[] u, byte[] v, int cOff) {
        rgbToChromaRange(src, off0, off1, layout, coefficients, u, v, cOff, 0, pairs);
    }

    /**
     * Computes the chroma samples {@code [from, to)} of a row pair.
     */
    void rgbToChromaRange(byte[] src, int off0, int off1, RgbLayout layout, YuvCoefficients c,
                          byte[] u, byte[] v, int cOff, int from, int to) {
        var ri = layout.rIndex();
        var gi = layout.gIndex();
        var bi = layout.bIndex();
        for (var k = from; k < to; k++) {
            var a = off0 + 8 * k;
            var d = off1 + 8 * k;
            var r = (src[a + ri] & 0xFF) + (src[a + 4 + ri] & 0xFF) + (src[d + ri] & 0xFF) + (src[d + 4 + ri] & 0xFF);
            var g = (src[a + gi] & 0xFF) + (src[a + 4 + gi] & 0xFF) + (src[d + gi] & 0xFF) + (src[d + 4 + gi] & 0xFF);
            var b = (src[a + bi] & 0xFF) + (src[a + 4 + bi] & 0xFF) + (src[d + bi] & 0xFF) + (src[d + 4 + bi] & 0xFF);
            u[cOff + k] = clamp((c.ur * r + c.ug * g + c.ub * b + c.cBias) >> YuvCoefficients.CHROMA_SHIFT);
            v[cOff + k] = clamp((c.vr * r + c.vg * g + c.vb * b + c.cBias) >> YuvCoefficients.CHROMA_SHIFT);
        }
    }

    @Override
    public void packedLuma(byte[] src, int off, int width, int lumaIndex, byte[] y, int yOff) {
        packedLumaRange(src, off, lumaIndex, y, yOff, 0, width);
    }

    /**
     * Extracts the luma of the pixels {@code [from, to)} of a packed row.
     */
    void packedLumaRange(byte[] src, int off, int lumaIndex, byte[] y, int yOff, int from, int to) {
        var base = off + lumaIndex;
        for (var x = from; x < to; x++) {
            y[yOff + x] = src[base + 2 * x];
        }
    }

    @Override
    public void packedChroma(byte[] src, int off0, int off1, int pairs, int cbIndex, int crIndex,
                             byte[] u, byte[] v, int cOff) {
        packedChromaRange(src, off0, off1, cbIndex, crIndex, u, v, cOff, 0, pairs);
    }

    /**
     * Averages the chroma of the macropixels {@code [from, to)} of two packed rows.
     */
    void packedChromaRange(byte[] src, int off0, int off1, int cbIndex, int crIndex, byte[] u, byte[] v, int cOff,
                           int from, int to) {
        for (var k = from; k < to; k++) {
            var i0 = off0 + 4 * k;
            var i1 = off1 + 4 * k;
            u[cOff + k] = (byte) (((src[i0 + cbIndex] & 0xFF) + (src[i1 + cbIndex] & 0xFF) + 1) >> 1);
            v[cOff + k] = (byte) (((src[i0 + crIndex] & 0xFF) + (src[i1 + crIndex] & 0xFF) + 1) >> 1);
        }
    }

    @Override
    public void deinterleave(byte[] src, int off, int count, byte[] first, byte[] second, int dOff) {
        deinterleaveRange(src, off, first, second, dOff, 0, count);
    }

    /**
     * Splits the pairs {@code [from, to)} of an interleaved row.
     */
    void deinterleaveRange(byte[] src, int off, byte[] first, byte[] second, int dOff, int from, int to) {
        for (var k = from; k < to; k++) {
            first[dOff + k] = src[off + 2 * k];
            second[dOff + k] = src[off + 2 * k + 1];
        }
    }

    @Override
    public void averageRows(byte[] a, int aOff, byte[] b, int bOff, int count, byte[] dst, int dOff) {
        averageRowsRange(a, aOff, b, bOff, dst, dOff, 0, count);
    }

    /**
     * Averages the samples {@code [from, to)} of two rows.
     */
    void averageRowsRange(byte[] a, int aOff, byte[] b, int bOff, byte[] dst, int dOff, int from, int to) {
        for (var i = from; i < to; i++) {
            dst[dOff + i] = (byte) (((a[aOff + i] & 0xFF) + (b[bOff + i] & 0xFF) + 1) >> 1);
        }
    }

    private static byte clamp(int value) {
        return (byte) Math.clamp(value, 0, 255);
    }
}
