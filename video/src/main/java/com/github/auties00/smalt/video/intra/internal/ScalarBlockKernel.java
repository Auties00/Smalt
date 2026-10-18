package com.github.auties00.smalt.video.intra.internal;

/**
 * Implements {@link BlockKernel} in plain Java, one lane at a time; the reference for the vector
 * implementation.
 *
 * <p>Instances hold no state and are thread-safe.
 */
public final class ScalarBlockKernel implements BlockKernel {
    /**
     * {@code cos(pi / 16) / 2}.
     */
    static final float A1 = (float) (StrictMath.cos(Math.PI / 16) / 2);

    /**
     * {@code cos(2 pi / 16) / 2}.
     */
    static final float A2 = (float) (StrictMath.cos(2 * Math.PI / 16) / 2);

    /**
     * {@code cos(3 pi / 16) / 2}.
     */
    static final float A3 = (float) (StrictMath.cos(3 * Math.PI / 16) / 2);

    /**
     * {@code cos(4 pi / 16) / 2}, which is also the weight {@code C(0) / 2} of the DC term.
     */
    static final float A4 = (float) (StrictMath.cos(4 * Math.PI / 16) / 2);

    /**
     * {@code cos(5 pi / 16) / 2}.
     */
    static final float A5 = (float) (StrictMath.cos(5 * Math.PI / 16) / 2);

    /**
     * {@code cos(6 pi / 16) / 2}.
     */
    static final float A6 = (float) (StrictMath.cos(6 * Math.PI / 16) / 2);

    /**
     * {@code cos(7 pi / 16) / 2}.
     */
    static final float A7 = (float) (StrictMath.cos(7 * Math.PI / 16) / 2);

    /**
     * Creates the kernel.
     */
    public ScalarBlockKernel() {
    }

    @Override
    public void inverse(float[] batch) {
        for (var row = 0; row < 8; row++) {
            for (var lane = 0; lane < LANES; lane++) {
                inverse1(batch, row * 64 + lane, LANES);
            }
        }
        for (var column = 0; column < 8; column++) {
            for (var lane = 0; lane < LANES; lane++) {
                inverse1(batch, column * LANES + lane, 64);
            }
        }
    }

    @Override
    public void forward(float[] batch) {
        for (var row = 0; row < 8; row++) {
            for (var lane = 0; lane < LANES; lane++) {
                forward1(batch, row * 64 + lane, LANES);
            }
        }
        for (var column = 0; column < 8; column++) {
            for (var lane = 0; lane < LANES; lane++) {
                forward1(batch, column * LANES + lane, 64);
            }
        }
    }

    @Override
    public void toSamples(float[] batch, float scale, float offset, float limit, int[] out) {
        for (var i = 0; i < BATCH_LENGTH; i++) {
            var value = batch[i] * scale + offset;
            value = Math.max(value, 0f);
            value = Math.min(value, limit);
            out[i] = (int) value;
        }
    }

    @Override
    public void quantize(float[] batch, float[] weights, float rounding, int[] out) {
        for (var i = 0; i < BATCH_LENGTH; i++) {
            var coefficient = batch[i];
            var level = (int) (Math.abs(coefficient) * weights[i] + rounding);
            out[i] = coefficient < 0 ? -level : level;
        }
    }

    @Override
    public void loadBlocks(short[][] sources, int[] offsets, int[] strides, int lanes, float subtract, float scale,
                           float[] batch, float[] scratch) {
        for (var lane = 0; lane < LANES; lane++) {
            if (lane >= lanes) {
                for (var position = 0; position < 64; position++) {
                    batch[position * LANES + lane] = 0f;
                }
                continue;
            }
            var plane = sources[lane];
            for (var row = 0; row < 8; row++) {
                var at = offsets[lane] + row * strides[lane];
                for (var column = 0; column < 8; column++) {
                    batch[(row * 8 + column) * LANES + lane] = ((plane[at + column] & 0xFFFF) - subtract) * scale;
                }
            }
        }
    }

    @Override
    public void quantizeMasked(float[] batch, float[] weights, float rounding, int limit, int[] out, int[] masks) {
        for (var position = 0; position < 64; position++) {
            var mask = 0;
            for (var lane = 0; lane < LANES; lane++) {
                var i = position * LANES + lane;
                var coefficient = batch[i];
                var level = Math.min((int) (Math.abs(coefficient) * weights[i] + rounding), limit);
                out[i] = level;
                if (level != 0) {
                    mask |= 1 << lane;
                }
            }
            masks[position] = mask;
        }
    }

    /**
     * Computes the 8-point inverse DCT of one line of one block in place.
     *
     * @param a    the batch
     * @param base the index of the line's first value
     * @param step the distance between the line's values
     */
    static void inverse1(float[] a, int base, int step) {
        var x0 = a[base];
        var x1 = a[base + step];
        var x2 = a[base + 2 * step];
        var x3 = a[base + 3 * step];
        var x4 = a[base + 4 * step];
        var x5 = a[base + 5 * step];
        var x6 = a[base + 6 * step];
        var x7 = a[base + 7 * step];
        var e0p = (x0 + x4) * A4;
        var e1p = (x0 - x4) * A4;
        var t2 = x2 * A2 + x6 * A6;
        var t3 = x2 * A6 - x6 * A2;
        var e0 = e0p + t2;
        var e3 = e0p - t2;
        var e1 = e1p + t3;
        var e2 = e1p - t3;
        var o0 = x1 * A1 + x3 * A3 + x5 * A5 + x7 * A7;
        var o1 = x1 * A3 - x3 * A7 - x5 * A1 - x7 * A5;
        var o2 = x1 * A5 - x3 * A1 + x5 * A7 + x7 * A3;
        var o3 = x1 * A7 - x3 * A5 + x5 * A3 - x7 * A1;
        a[base] = e0 + o0;
        a[base + 7 * step] = e0 - o0;
        a[base + step] = e1 + o1;
        a[base + 6 * step] = e1 - o1;
        a[base + 2 * step] = e2 + o2;
        a[base + 5 * step] = e2 - o2;
        a[base + 3 * step] = e3 + o3;
        a[base + 4 * step] = e3 - o3;
    }

    /**
     * Computes the 8-point forward DCT of one line of one block in place.
     *
     * @param a    the batch
     * @param base the index of the line's first value
     * @param step the distance between the line's values
     */
    static void forward1(float[] a, int base, int step) {
        var y0 = a[base];
        var y1 = a[base + step];
        var y2 = a[base + 2 * step];
        var y3 = a[base + 3 * step];
        var y4 = a[base + 4 * step];
        var y5 = a[base + 5 * step];
        var y6 = a[base + 6 * step];
        var y7 = a[base + 7 * step];
        var s0 = y0 + y7;
        var s1 = y1 + y6;
        var s2 = y2 + y5;
        var s3 = y3 + y4;
        var d0 = y0 - y7;
        var d1 = y1 - y6;
        var d2 = y2 - y5;
        var d3 = y3 - y4;
        var p = s0 + s3;
        var q = s1 + s2;
        var m = s0 - s3;
        var n = s1 - s2;
        a[base] = (p + q) * A4;
        a[base + 4 * step] = (p - q) * A4;
        a[base + 2 * step] = m * A2 + n * A6;
        a[base + 6 * step] = m * A6 - n * A2;
        a[base + step] = d0 * A1 + d1 * A3 + d2 * A5 + d3 * A7;
        a[base + 3 * step] = d0 * A3 - d1 * A7 - d2 * A1 - d3 * A5;
        a[base + 5 * step] = d0 * A5 - d1 * A1 + d2 * A7 + d3 * A3;
        a[base + 7 * step] = d0 * A7 - d1 * A5 + d2 * A3 - d3 * A1;
    }
}
