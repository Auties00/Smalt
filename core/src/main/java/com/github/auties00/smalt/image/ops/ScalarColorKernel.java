package com.github.auties00.smalt.image.ops;

/**
 * Implements {@link ColorKernel} with plain Java arithmetic.
 *
 * <p>This is the reference implementation: the vector implementation must produce identical
 * bytes, and delegates to the range methods of this class for the pixels its vector loops do not
 * cover.
 *
 * @implNote This implementation follows libjpeg-turbo's {@code jdsample.c} ({@code h2v1} and {@code h2v2} fancy
 * upsampling) and {@code jccolor.c}/{@code jdcolor.c} (fixed-point color conversion with 16 fractional bits).
 */
final class ScalarColorKernel implements ColorKernel {
    /**
     * Constructs the kernel.
     */
    ScalarColorKernel() {
    }

    @Override
    public void yuvToRgba(byte[] y, int yOff, byte[] u, byte[] v, int cOff, int farOff, byte[] alpha, int aOff,
                          byte[] dst, int dOff, int width, int mode, ColorMatrix m) {
        yuvToRgbaRange(y, yOff, u, v, cOff, farOff, alpha, aOff, dst, dOff, width, mode, m, 0, width);
    }

    /**
     * Converts the pixels {@code [from, to)} of one row of Y'CbCr samples to packed RGBA.
     *
     * <p>Chroma indices and edge clamping are computed relative to the whole row of
     * {@code width} pixels, so the vector kernel can hand over any sub-range.
     *
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the first sample of the (nearer) chroma row
     * @param farOff the index of the first sample of the farther chroma row
     * @param alpha the alpha plane, or {@code null} for opaque output
     * @param aOff the index of the row's first alpha sample
     * @param dst the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param width the number of pixels in the row
     * @param mode the chroma sampling mode
     * @param m the conversion coefficients
     * @param from the first pixel to convert
     * @param to the pixel after the last one to convert
     */
    void yuvToRgbaRange(byte[] y, int yOff, byte[] u, byte[] v, int cOff, int farOff, byte[] alpha, int aOff,
                        byte[] dst, int dOff, int width, int mode, ColorMatrix m, int from, int to) {
        var chromaWidth = (width + 1) >> 1;
        var converter = m.converter;
        switch (mode) {
            case CHROMA_FULL -> {
                for (var x = from; x < to; x++) {
                    converter.store(dst, dOff + 4 * x, y[yOff + x] & 0xFF, u[cOff + x] & 0xFF, v[cOff + x] & 0xFF,
                            alpha == null ? 255 : alpha[aOff + x] & 0xFF);
                }
            }
            case CHROMA_NEAREST -> {
                for (var x = from; x < to; x++) {
                    var k = cOff + (x >> 1);
                    converter.store(dst, dOff + 4 * x, y[yOff + x] & 0xFF, u[k] & 0xFF, v[k] & 0xFF,
                            alpha == null ? 255 : alpha[aOff + x] & 0xFF);
                }
            }
            case CHROMA_FANCY_H -> {
                for (var x = from; x < to; x++) {
                    var k = x >> 1;
                    int cb;
                    int cr;
                    if ((x & 1) == 0) {
                        var n = k > 0 ? k - 1 : 0;
                        cb = (3 * (u[cOff + k] & 0xFF) + (u[cOff + n] & 0xFF) + 1) >> 2;
                        cr = (3 * (v[cOff + k] & 0xFF) + (v[cOff + n] & 0xFF) + 1) >> 2;
                    } else {
                        var n = k + 1 < chromaWidth ? k + 1 : chromaWidth - 1;
                        cb = (3 * (u[cOff + k] & 0xFF) + (u[cOff + n] & 0xFF) + 2) >> 2;
                        cr = (3 * (v[cOff + k] & 0xFF) + (v[cOff + n] & 0xFF) + 2) >> 2;
                    }
                    converter.store(dst, dOff + 4 * x, y[yOff + x] & 0xFF, cb, cr, alpha == null ? 255 : alpha[aOff + x] & 0xFF);
                }
            }
            case CHROMA_FANCY_HV -> {
                for (var x = from; x < to; x++) {
                    var k = x >> 1;
                    var n = (x & 1) == 0 ? (k > 0 ? k - 1 : 0) : (k + 1 < chromaWidth ? k + 1 : chromaWidth - 1);
                    var bias = (x & 1) == 0 ? 8 : 7;
                    var cb = (3 * columnSum(u, cOff, farOff, k) + columnSum(u, cOff, farOff, n) + bias) >> 4;
                    var cr = (3 * columnSum(v, cOff, farOff, k) + columnSum(v, cOff, farOff, n) + bias) >> 4;
                    converter.store(dst, dOff + 4 * x, y[yOff + x] & 0xFF, cb, cr, alpha == null ? 255 : alpha[aOff + x] & 0xFF);
                }
            }
            default -> throw new IllegalArgumentException("unknown chroma mode " + mode);
        }
    }

    /**
     * Converts Y'CbCr sample triples with coefficients copied out of a {@link ColorMatrix}, so
     * the per-pixel arithmetic reads no arrays.
     */
    static final class Converter {
        /**
         * The luma coefficient of red.
         */
        private final int ry;

        /**
         * The Cb coefficient of red.
         */
        private final int ru;

        /**
         * The Cr coefficient of red.
         */
        private final int rv;

        /**
         * The constant of red.
         */
        private final int rk;

        /**
         * The luma coefficient of green.
         */
        private final int gy;

        /**
         * The Cb coefficient of green.
         */
        private final int gu;

        /**
         * The Cr coefficient of green.
         */
        private final int gv;

        /**
         * The constant of green.
         */
        private final int gk;

        /**
         * The luma coefficient of blue.
         */
        private final int by;

        /**
         * The Cb coefficient of blue.
         */
        private final int bu;

        /**
         * The Cr coefficient of blue.
         */
        private final int bv;

        /**
         * The constant of blue.
         */
        private final int bk;

        /**
         * Copies the inverse coefficients of a matrix.
         *
         * @param m the conversion coefficients
         */
        Converter(ColorMatrix m) {
            this.ry = m.ir[0];
            this.ru = m.ir[1];
            this.rv = m.ir[2];
            this.rk = m.ik[0];
            this.gy = m.ig[0];
            this.gu = m.ig[1];
            this.gv = m.ig[2];
            this.gk = m.ik[1];
            this.by = m.ib[0];
            this.bu = m.ib[1];
            this.bv = m.ib[2];
            this.bk = m.ik[2];
        }

        /**
         * Converts one Y'CbCr sample triple to RGB and stores it with the given alpha.
         *
         * @param dst the RGBA destination
         * @param index the index of the pixel's first byte
         * @param luma the luma sample
         * @param cb the Cb sample
         * @param cr the Cr sample
         * @param a the alpha value
         */
        void store(byte[] dst, int index, int luma, int cb, int cr, int a) {
            dst[index] = (byte) clamp((ry * luma + ru * cb + rv * cr + rk) >> ColorMatrix.BITS);
            dst[index + 1] = (byte) clamp((gy * luma + gu * cb + gv * cr + gk) >> ColorMatrix.BITS);
            dst[index + 2] = (byte) clamp((by * luma + bu * cb + bv * cr + bk) >> ColorMatrix.BITS);
            dst[index + 3] = (byte) a;
        }
    }

    /**
     * Returns libjpeg's vertical triangle sum {@code 3 * near + far} of one chroma column.
     *
     * @param plane the chroma plane
     * @param near the index of the nearer row's first sample
     * @param far the index of the farther row's first sample
     * @param column the chroma column
     * @return the weighted sum, from {@code 0} to {@code 1020}
     */
    private static int columnSum(byte[] plane, int near, int far, int column) {
        return 3 * (plane[near + column] & 0xFF) + (plane[far + column] & 0xFF);
    }

    /**
     * Clamps a value to the 8-bit range.
     *
     * @param value the unclamped sample
     * @return the value limited to {@code 0..255}
     */
    static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    @Override
    public void grayToRgba(byte[] y, int yOff, byte[] alpha, int aOff, byte[] dst, int dOff, int width, ColorMatrix m) {
        grayToRgbaRange(y, yOff, alpha, aOff, dst, dOff, m, 0, width);
    }

    /**
     * Converts the pixels {@code [from, to)} of one row of luma samples to gray packed RGBA.
     *
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     * @param alpha the alpha plane, or {@code null} for opaque output
     * @param aOff the index of the row's first alpha sample
     * @param dst the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param m the conversion coefficients
     * @param from the first pixel to convert
     * @param to the pixel after the last one to convert
     */
    void grayToRgbaRange(byte[] y, int yOff, byte[] alpha, int aOff, byte[] dst, int dOff, ColorMatrix m, int from, int to) {
        for (var x = from; x < to; x++) {
            var gray = (byte) clamp(((y[yOff + x] & 0xFF) * m.grayScale + m.grayConstant) >> ColorMatrix.BITS);
            var index = dOff + 4 * x;
            dst[index] = gray;
            dst[index + 1] = gray;
            dst[index + 2] = gray;
            dst[index + 3] = alpha == null ? (byte) 255 : alpha[aOff + x];
        }
    }

    @Override
    public void rgbaToLuma(byte[] src, int sOff, int width, byte[] y, int yOff, byte[] alpha, int aOff, ColorMatrix m) {
        rgbaToLumaRange(src, sOff, y, yOff, alpha, aOff, m, 0, width);
    }

    /**
     * Computes the luma, and optionally copies the alpha, of the pixels {@code [from, to)} of one
     * row.
     *
     * @param src the RGBA source
     * @param sOff the index of the row's first source byte
     * @param y the luma destination
     * @param yOff the index of the row's first luma sample
     * @param alpha the alpha destination, or {@code null} to drop alpha
     * @param aOff the index of the row's first alpha sample
     * @param m the conversion coefficients
     * @param from the first pixel to convert
     * @param to the pixel after the last one to convert
     */
    void rgbaToLumaRange(byte[] src, int sOff, byte[] y, int yOff, byte[] alpha, int aOff, ColorMatrix m, int from, int to) {
        var c0 = m.fy[0];
        var c1 = m.fy[1];
        var c2 = m.fy[2];
        var k = m.fk[0];
        for (var x = from; x < to; x++) {
            var index = sOff + 4 * x;
            var r = src[index] & 0xFF;
            var g = src[index + 1] & 0xFF;
            var b = src[index + 2] & 0xFF;
            y[yOff + x] = (byte) clamp((c0 * r + c1 * g + c2 * b + k) >> ColorMatrix.BITS);
            if (alpha != null) {
                alpha[aOff + x] = src[index + 3];
            }
        }
    }

    @Override
    public void rgbaToChroma(byte[] src, int off0, int off1, int width, boolean pairX, boolean pairY,
                             byte[] u, byte[] v, int cOff, ColorMatrix m) {
        var count = pairX ? (width + 1) >> 1 : width;
        rgbaToChromaRange(src, off0, off1, width, pairX, pairY, u, v, cOff, m, 0, count);
    }

    /**
     * Computes the chroma samples {@code [from, to)} of one chroma row.
     *
     * @param src the RGBA source
     * @param off0 the index of the first source byte of the upper row
     * @param off1 the index of the first source byte of the lower row
     * @param width the number of pixels per row
     * @param pairX whether a chroma sample covers two columns
     * @param pairY whether a chroma sample covers two rows
     * @param u the Cb destination
     * @param v the Cr destination
     * @param cOff the index of the row's first chroma sample
     * @param m the conversion coefficients
     * @param from the first chroma sample to compute
     * @param to the chroma sample after the last one to compute
     */
    void rgbaToChromaRange(byte[] src, int off0, int off1, int width, boolean pairX, boolean pairY,
                           byte[] u, byte[] v, int cOff, ColorMatrix m, int from, int to) {
        var shift = ColorMatrix.BITS + (pairX ? 1 : 0) + (pairY ? 1 : 0);
        var ku = (m.offsets[1] << shift) + (1 << (shift - 1)) - 1;
        var kv = (m.offsets[2] << shift) + (1 << (shift - 1)) - 1;
        for (var k = from; k < to; k++) {
            var x0 = pairX ? 2 * k : k;
            var x1 = pairX ? Math.min(2 * k + 1, width - 1) : x0;
            var sr = 0;
            var sg = 0;
            var sb = 0;
            for (var row = 0; row < (pairY ? 2 : 1); row++) {
                var base = row == 0 ? off0 : off1;
                for (var x = x0; x <= x1; x++) {
                    sr += src[base + 4 * x] & 0xFF;
                    sg += src[base + 4 * x + 1] & 0xFF;
                    sb += src[base + 4 * x + 2] & 0xFF;
                }
                if (pairX && x0 == x1) {
                    sr += src[base + 4 * x0] & 0xFF;
                    sg += src[base + 4 * x0 + 1] & 0xFF;
                    sb += src[base + 4 * x0 + 2] & 0xFF;
                }
            }
            u[cOff + k] = (byte) clamp((m.fu[0] * sr + m.fu[1] * sg + m.fu[2] * sb + ku) >> shift);
            v[cOff + k] = (byte) clamp((m.fv[0] * sr + m.fv[1] * sg + m.fv[2] * sb + kv) >> shift);
        }
    }
}
