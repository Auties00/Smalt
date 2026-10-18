package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Converts RGBA rows to Y'CbCr with scalar arithmetic; the reference {@link ColorKernel}.
 */
public final class ScalarColorKernel implements ColorKernel {
    /**
     * Constructs the kernel.
     */
    public ScalarColorKernel() {
    }

    /**
     * {@code FIX(0.29900)}.
     */
    static final int R_Y = 19595;

    /**
     * {@code FIX(0.58700)}.
     */
    static final int G_Y = 38470;

    /**
     * {@code FIX(0.11400)}.
     */
    static final int B_Y = 7471;

    /**
     * {@code FIX(0.16874)}.
     */
    static final int R_CB = 11059;

    /**
     * {@code FIX(0.33126)}.
     */
    static final int G_CB = 21709;

    /**
     * {@code FIX(0.50000)}.
     */
    static final int HALF = 32768;

    /**
     * {@code FIX(0.41869)}.
     */
    static final int G_CR = 27439;

    /**
     * {@code FIX(0.08131)}.
     */
    static final int B_CR = 5329;

    /**
     * The luma rounding term, one half.
     */
    static final int Y_ROUND = 1 << 15;

    /**
     * The chroma offset plus a rounding term of one half minus one unit, which keeps the maximum
     * output at 255.
     */
    static final int C_ROUND = (128 << 16) + (1 << 15) - 1;

    @Override
    public void toYcc(byte[] rgba, int offset, int count, byte[] y, byte[] cb, byte[] cr) {
        for (var i = 0; i < count; i++) {
            var p = offset + 4 * i;
            var r = rgba[p] & 0xFF;
            var g = rgba[p + 1] & 0xFF;
            var b = rgba[p + 2] & 0xFF;
            y[i] = (byte) ((R_Y * r + G_Y * g + B_Y * b + Y_ROUND) >>> 16);
            cb[i] = (byte) ((-R_CB * r - G_CB * g + HALF * b + C_ROUND) >>> 16);
            cr[i] = (byte) ((HALF * r - G_CR * g - B_CR * b + C_ROUND) >>> 16);
        }
    }

    @Override
    public void toGray(byte[] rgba, int offset, int count, byte[] y) {
        for (var i = 0; i < count; i++) {
            var p = offset + 4 * i;
            y[i] = (byte) ((R_Y * (rgba[p] & 0xFF) + G_Y * (rgba[p + 1] & 0xFF) + B_Y * (rgba[p + 2] & 0xFF) + Y_ROUND) >>> 16);
        }
    }
}
