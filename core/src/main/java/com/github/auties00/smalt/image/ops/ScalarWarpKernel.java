package com.github.auties00.smalt.image.ops;

/**
 * Implements {@link WarpKernel} with scalar code; the reference for the Vector API
 * implementation, which also uses it for the ends of runs.
 */
final class ScalarWarpKernel implements WarpKernel {
    /**
     * Creates the kernel.
     */
    ScalarWarpKernel() {
    }

    @Override
    public void accumulate2(int[] taps0, int[] taps1, float[] w0, float[] w1, float[] wy, float[] red,
                            float[] green, float[] blue, float[] alpha, int count) {
        accumulate2Range(taps0, taps1, w0, w1, wy, red, green, blue, alpha, 0, count);
    }

    /**
     * Accumulates one row of two taps for the pixels {@code [from, to)} of a run.
     *
     * @param taps0  the first column taps
     * @param taps1  the second column taps
     * @param w0     the first column weights
     * @param w1     the second column weights
     * @param wy     the row weights
     * @param red    the red sums
     * @param green  the green sums
     * @param blue   the blue sums
     * @param alpha  the alpha sums
     * @param from   the first pixel
     * @param to     the pixel after the last one
     */
    void accumulate2Range(int[] taps0, int[] taps1, float[] w0, float[] w1, float[] wy, float[] red,
                          float[] green, float[] blue, float[] alpha, int from, int to) {
        for (var i = from; i < to; i++) {
            tap(taps0[i], w0[i] * wy[i], red, green, blue, alpha, i);
            tap(taps1[i], w1[i] * wy[i], red, green, blue, alpha, i);
        }
    }

    @Override
    public void accumulate4(int[] taps0, int[] taps1, int[] taps2, int[] taps3, float[] w0, float[] w1, float[] w2,
                            float[] w3, float[] wy, float[] red, float[] green, float[] blue, float[] alpha,
                            int count) {
        accumulate4Range(taps0, taps1, taps2, taps3, w0, w1, w2, w3, wy, red, green, blue, alpha, 0, count);
    }

    /**
     * Accumulates one row of four taps for the pixels {@code [from, to)} of a run.
     *
     * @param taps0  the first column taps
     * @param taps1  the second column taps
     * @param taps2  the third column taps
     * @param taps3  the fourth column taps
     * @param w0     the first column weights
     * @param w1     the second column weights
     * @param w2     the third column weights
     * @param w3     the fourth column weights
     * @param wy     the row weights
     * @param red    the red sums
     * @param green  the green sums
     * @param blue   the blue sums
     * @param alpha  the alpha sums
     * @param from   the first pixel
     * @param to     the pixel after the last one
     */
    void accumulate4Range(int[] taps0, int[] taps1, int[] taps2, int[] taps3, float[] w0, float[] w1, float[] w2,
                          float[] w3, float[] wy, float[] red, float[] green, float[] blue, float[] alpha, int from,
                          int to) {
        for (var i = from; i < to; i++) {
            tap(taps0[i], w0[i] * wy[i], red, green, blue, alpha, i);
            tap(taps1[i], w1[i] * wy[i], red, green, blue, alpha, i);
            tap(taps2[i], w2[i] * wy[i], red, green, blue, alpha, i);
            tap(taps3[i], w3[i] * wy[i], red, green, blue, alpha, i);
        }
    }

    /**
     * Adds one weighted tap to the sums of a pixel.
     *
     * @param pixel  the packed tap
     * @param weight the tap's weight
     * @param red    the red sums
     * @param green  the green sums
     * @param blue   the blue sums
     * @param alpha  the alpha sums
     * @param i      the pixel
     */
    private static void tap(int pixel, float weight, float[] red, float[] green, float[] blue, float[] alpha, int i) {
        var a = weight * (float) (pixel >>> 24);
        red[i] = red[i] + a * (float) (pixel & 0xFF);
        green[i] = green[i] + a * (float) ((pixel >>> 8) & 0xFF);
        blue[i] = blue[i] + a * (float) ((pixel >>> 16) & 0xFF);
        alpha[i] = alpha[i] + a;
    }

    @Override
    public void resolve(float[] red, float[] green, float[] blue, float[] alpha, int count, byte[] dst, int dstOffset) {
        resolveRange(red, green, blue, alpha, dst, dstOffset, 0, count);
    }

    /**
     * Writes the sums of the pixels {@code [from, to)} of a run.
     *
     * @param red       the red sums
     * @param green     the green sums
     * @param blue      the blue sums
     * @param alpha     the alpha sums
     * @param dst       the RGBA destination
     * @param dstOffset the index of the run's first destination byte
     * @param from      the first pixel
     * @param to        the pixel after the last one
     */
    void resolveRange(float[] red, float[] green, float[] blue, float[] alpha, byte[] dst, int dstOffset, int from, int to) {
        for (var i = from; i < to; i++) {
            var d = dstOffset + 4 * i;
            var a = (int) (alpha[i] + 0.5f);
            if (a <= 0) {
                dst[d] = 0;
                dst[d + 1] = 0;
                dst[d + 2] = 0;
                dst[d + 3] = 0;
                continue;
            }
            var reciprocal = 1 / alpha[i];
            dst[d] = (byte) round(red[i] * reciprocal);
            dst[d + 1] = (byte) round(green[i] * reciprocal);
            dst[d + 2] = (byte) round(blue[i] * reciprocal);
            dst[d + 3] = (byte) Math.min(a, 255);
        }
    }

    /**
     * Rounds a color to nearest and clamps it to {@code 0..255}.
     *
     * @param value the color
     * @return the sample
     */
    private static int round(float value) {
        return Math.clamp((int) (value + 0.5f), 0, 255);
    }
}
