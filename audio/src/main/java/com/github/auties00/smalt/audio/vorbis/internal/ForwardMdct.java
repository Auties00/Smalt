package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Computes the forward MDCT of a Vorbis block, scaled by {@code 4/n}.
 *
 * <p>An instance keeps a work buffer and is not thread-safe.
 *
 * @implNote This implementation ports libvorbis 1.3.7's {@code mdct.c} forward path (the
 * split-radix butterflies and bit reversal of {@code mdct_forward}, float build) (BSD-3).
 */
public final class ForwardMdct {
    /**
     * The cosine of 3 pi / 8.
     */
    private static final float PI3_8 = .38268343236508977175F;

    /**
     * The cosine of 2 pi / 8.
     */
    private static final float PI2_8 = .70710678118654752441F;

    /**
     * The cosine of pi / 8.
     */
    private static final float PI1_8 = .92387953251128675613F;

    /**
     * The transform size.
     */
    private final int n;

    /**
     * The base-2 logarithm of {@link #n}.
     */
    private final int log2n;

    /**
     * The twiddle factors.
     */
    private final float[] trig;

    /**
     * The bit reversal table.
     */
    private final int[] bitrev;

    /**
     * The output scale.
     */
    private final float scale;

    /**
     * The work buffer.
     */
    private final float[] work;

    /**
     * Creates a transform of a size.
     *
     * @param n the block size, a power of two of at least 64
     */
    public ForwardMdct(int n) {
        this.n = n;
        this.log2n = (int) Math.rint(StrictMath.log((float) n) / StrictMath.log(2.f));
        this.trig = new float[n + n / 4];
        this.bitrev = new int[n / 4];
        this.work = new float[n];
        var n2 = n >> 1;
        for (var i = 0; i < n / 4; i++) {
            trig[i * 2] = (float) StrictMath.cos((Math.PI / n) * (4 * i));
            trig[i * 2 + 1] = (float) -StrictMath.sin((Math.PI / n) * (4 * i));
            trig[n2 + i * 2] = (float) StrictMath.cos((Math.PI / (2 * n)) * (2 * i + 1));
            trig[n2 + i * 2 + 1] = (float) StrictMath.sin((Math.PI / (2 * n)) * (2 * i + 1));
        }
        for (var i = 0; i < n / 8; i++) {
            trig[n + i * 2] = (float) (StrictMath.cos((Math.PI / n) * (4 * i + 2)) * .5);
            trig[n + i * 2 + 1] = (float) (-StrictMath.sin((Math.PI / n) * (4 * i + 2)) * .5);
        }
        var mask = (1 << (log2n - 1)) - 1;
        var msb = 1 << (log2n - 2);
        for (var i = 0; i < n / 8; i++) {
            var acc = 0;
            for (var j = 0; (msb >> j) != 0; j++) {
                if (((msb >> j) & i) != 0) {
                    acc |= 1 << j;
                }
            }
            bitrev[i * 2] = ((~acc) & mask) - 1;
            bitrev[i * 2 + 1] = acc;
        }
        this.scale = 4.f / n;
    }

    /**
     * Transforms a block.
     *
     * @param in     the windowed block of {@code n} samples
     * @param inOff  the index of the block's first sample
     * @param out    receives {@code n/2} coefficients
     * @param outOff the index of the first coefficient
     */
    public void forward(float[] in, int inOff, float[] out, int outOff) {
        var n2 = n >> 1;
        var n4 = n >> 2;
        var n8 = n >> 3;
        var w = work;
        var w2 = n2;
        var x0 = inOff + n2 + n4;
        var x1 = x0 + 1;
        var t = n2;
        var i = 0;
        for (; i < n8; i += 2) {
            x0 -= 4;
            t -= 2;
            var r0 = in[x0 + 2] + in[x1];
            var r1 = in[x0] + in[x1 + 2];
            w[w2 + i] = r1 * trig[t + 1] + r0 * trig[t];
            w[w2 + i + 1] = r1 * trig[t] - r0 * trig[t + 1];
            x1 += 4;
        }
        x1 = inOff + 1;
        for (; i < n2 - n8; i += 2) {
            t -= 2;
            x0 -= 4;
            var r0 = in[x0 + 2] - in[x1];
            var r1 = in[x0] - in[x1 + 2];
            w[w2 + i] = r1 * trig[t + 1] + r0 * trig[t];
            w[w2 + i + 1] = r1 * trig[t] - r0 * trig[t + 1];
            x1 += 4;
        }
        x0 = inOff + n;
        for (; i < n2; i += 2) {
            t -= 2;
            x0 -= 4;
            var r0 = -in[x0 + 2] - in[x1];
            var r1 = -in[x0] - in[x1 + 2];
            w[w2 + i] = r1 * trig[t + 1] + r0 * trig[t];
            w[w2 + i + 1] = r1 * trig[t] - r0 * trig[t + 1];
            x1 += 4;
        }
        butterflies(w, n2, n2);
        bitReverse(w);
        t = n2;
        var o = outOff + n2;
        var wi = 0;
        for (i = 0; i < n4; i++) {
            o--;
            out[outOff + i] = (w[wi] * trig[t] + w[wi + 1] * trig[t + 1]) * scale;
            out[o] = (w[wi] * trig[t + 1] - w[wi + 1] * trig[t]) * scale;
            wi += 2;
            t += 2;
        }
    }

    /**
     * Runs every butterfly stage.
     *
     * @param x      the data
     * @param base   the index of the first point
     * @param points the number of points
     */
    private void butterflies(float[] x, int base, int points) {
        var stages = log2n - 5;
        if (--stages > 0) {
            butterflyFirst(x, base, points);
        }
        for (var i = 1; --stages > 0; i++) {
            for (var j = 0; j < (1 << i); j++) {
                butterflyGeneric(x, base + (points >> i) * j, points >> i, 4 << i);
            }
        }
        for (var j = 0; j < points; j += 32) {
            butterfly32(x, base + j);
        }
    }

    /**
     * Runs the first butterfly stage.
     *
     * @param x      the data
     * @param base   the index of the first point
     * @param points the number of points
     */
    private void butterflyFirst(float[] x, int base, int points) {
        var t = trig;
        var ti = 0;
        var x1 = base + points - 8;
        var x2 = base + (points >> 1) - 8;
        do {
            var r0 = x[x1 + 6] - x[x2 + 6];
            var r1 = x[x1 + 7] - x[x2 + 7];
            x[x1 + 6] += x[x2 + 6];
            x[x1 + 7] += x[x2 + 7];
            x[x2 + 6] = r1 * t[ti + 1] + r0 * t[ti];
            x[x2 + 7] = r1 * t[ti] - r0 * t[ti + 1];
            r0 = x[x1 + 4] - x[x2 + 4];
            r1 = x[x1 + 5] - x[x2 + 5];
            x[x1 + 4] += x[x2 + 4];
            x[x1 + 5] += x[x2 + 5];
            x[x2 + 4] = r1 * t[ti + 5] + r0 * t[ti + 4];
            x[x2 + 5] = r1 * t[ti + 4] - r0 * t[ti + 5];
            r0 = x[x1 + 2] - x[x2 + 2];
            r1 = x[x1 + 3] - x[x2 + 3];
            x[x1 + 2] += x[x2 + 2];
            x[x1 + 3] += x[x2 + 3];
            x[x2 + 2] = r1 * t[ti + 9] + r0 * t[ti + 8];
            x[x2 + 3] = r1 * t[ti + 8] - r0 * t[ti + 9];
            r0 = x[x1] - x[x2];
            r1 = x[x1 + 1] - x[x2 + 1];
            x[x1] += x[x2];
            x[x1 + 1] += x[x2 + 1];
            x[x2] = r1 * t[ti + 13] + r0 * t[ti + 12];
            x[x2 + 1] = r1 * t[ti + 12] - r0 * t[ti + 13];
            x1 -= 8;
            x2 -= 8;
            ti += 16;
        } while (x2 >= base);
    }

    /**
     * Runs a generic butterfly stage.
     *
     * @param x       the data
     * @param base    the index of the first point
     * @param points  the number of points
     * @param trigint the twiddle stride
     */
    private void butterflyGeneric(float[] x, int base, int points, int trigint) {
        var t = trig;
        var ti = 0;
        var x1 = base + points - 8;
        var x2 = base + (points >> 1) - 8;
        do {
            var r0 = x[x1 + 6] - x[x2 + 6];
            var r1 = x[x1 + 7] - x[x2 + 7];
            x[x1 + 6] += x[x2 + 6];
            x[x1 + 7] += x[x2 + 7];
            x[x2 + 6] = r1 * t[ti + 1] + r0 * t[ti];
            x[x2 + 7] = r1 * t[ti] - r0 * t[ti + 1];
            ti += trigint;
            r0 = x[x1 + 4] - x[x2 + 4];
            r1 = x[x1 + 5] - x[x2 + 5];
            x[x1 + 4] += x[x2 + 4];
            x[x1 + 5] += x[x2 + 5];
            x[x2 + 4] = r1 * t[ti + 1] + r0 * t[ti];
            x[x2 + 5] = r1 * t[ti] - r0 * t[ti + 1];
            ti += trigint;
            r0 = x[x1 + 2] - x[x2 + 2];
            r1 = x[x1 + 3] - x[x2 + 3];
            x[x1 + 2] += x[x2 + 2];
            x[x1 + 3] += x[x2 + 3];
            x[x2 + 2] = r1 * t[ti + 1] + r0 * t[ti];
            x[x2 + 3] = r1 * t[ti] - r0 * t[ti + 1];
            ti += trigint;
            r0 = x[x1] - x[x2];
            r1 = x[x1 + 1] - x[x2 + 1];
            x[x1] += x[x2];
            x[x1 + 1] += x[x2 + 1];
            x[x2] = r1 * t[ti + 1] + r0 * t[ti];
            x[x2 + 1] = r1 * t[ti] - r0 * t[ti + 1];
            ti += trigint;
            x1 -= 8;
            x2 -= 8;
        } while (x2 >= base);
    }

    /**
     * Runs the 32-point butterfly.
     *
     * @param x the data
     * @param b the index of the first point
     */
    private static void butterfly32(float[] x, int b) {
        var r0 = x[b + 30] - x[b + 14];
        var r1 = x[b + 31] - x[b + 15];
        x[b + 30] += x[b + 14];
        x[b + 31] += x[b + 15];
        x[b + 14] = r0;
        x[b + 15] = r1;
        r0 = x[b + 28] - x[b + 12];
        r1 = x[b + 29] - x[b + 13];
        x[b + 28] += x[b + 12];
        x[b + 29] += x[b + 13];
        x[b + 12] = r0 * PI1_8 - r1 * PI3_8;
        x[b + 13] = r0 * PI3_8 + r1 * PI1_8;
        r0 = x[b + 26] - x[b + 10];
        r1 = x[b + 27] - x[b + 11];
        x[b + 26] += x[b + 10];
        x[b + 27] += x[b + 11];
        x[b + 10] = (r0 - r1) * PI2_8;
        x[b + 11] = (r0 + r1) * PI2_8;
        r0 = x[b + 24] - x[b + 8];
        r1 = x[b + 25] - x[b + 9];
        x[b + 24] += x[b + 8];
        x[b + 25] += x[b + 9];
        x[b + 8] = r0 * PI3_8 - r1 * PI1_8;
        x[b + 9] = r1 * PI3_8 + r0 * PI1_8;
        r0 = x[b + 22] - x[b + 6];
        r1 = x[b + 7] - x[b + 23];
        x[b + 22] += x[b + 6];
        x[b + 23] += x[b + 7];
        x[b + 6] = r1;
        x[b + 7] = r0;
        r0 = x[b + 4] - x[b + 20];
        r1 = x[b + 5] - x[b + 21];
        x[b + 20] += x[b + 4];
        x[b + 21] += x[b + 5];
        x[b + 4] = r1 * PI1_8 + r0 * PI3_8;
        x[b + 5] = r1 * PI3_8 - r0 * PI1_8;
        r0 = x[b + 2] - x[b + 18];
        r1 = x[b + 3] - x[b + 19];
        x[b + 18] += x[b + 2];
        x[b + 19] += x[b + 3];
        x[b + 2] = (r1 + r0) * PI2_8;
        x[b + 3] = (r1 - r0) * PI2_8;
        r0 = x[b] - x[b + 16];
        r1 = x[b + 1] - x[b + 17];
        x[b + 16] += x[b];
        x[b + 17] += x[b + 1];
        x[b] = r1 * PI3_8 + r0 * PI1_8;
        x[b + 1] = r1 * PI1_8 - r0 * PI3_8;
        butterfly16(x, b);
        butterfly16(x, b + 16);
    }

    /**
     * Runs the 16-point butterfly.
     *
     * @param x the data
     * @param b the index of the first point
     */
    private static void butterfly16(float[] x, int b) {
        var r0 = x[b + 1] - x[b + 9];
        var r1 = x[b] - x[b + 8];
        x[b + 8] += x[b];
        x[b + 9] += x[b + 1];
        x[b] = (r0 + r1) * PI2_8;
        x[b + 1] = (r0 - r1) * PI2_8;
        r0 = x[b + 3] - x[b + 11];
        r1 = x[b + 10] - x[b + 2];
        x[b + 10] += x[b + 2];
        x[b + 11] += x[b + 3];
        x[b + 2] = r0;
        x[b + 3] = r1;
        r0 = x[b + 12] - x[b + 4];
        r1 = x[b + 13] - x[b + 5];
        x[b + 12] += x[b + 4];
        x[b + 13] += x[b + 5];
        x[b + 4] = (r0 - r1) * PI2_8;
        x[b + 5] = (r0 + r1) * PI2_8;
        r0 = x[b + 14] - x[b + 6];
        r1 = x[b + 15] - x[b + 7];
        x[b + 14] += x[b + 6];
        x[b + 15] += x[b + 7];
        x[b + 6] = r0;
        x[b + 7] = r1;
        butterfly8(x, b);
        butterfly8(x, b + 8);
    }

    /**
     * Runs the 8-point butterfly.
     *
     * @param x the data
     * @param b the index of the first point
     */
    private static void butterfly8(float[] x, int b) {
        var r0 = x[b + 6] + x[b + 2];
        var r1 = x[b + 6] - x[b + 2];
        var r2 = x[b + 4] + x[b];
        var r3 = x[b + 4] - x[b];
        x[b + 6] = r0 + r2;
        x[b + 4] = r0 - r2;
        r0 = x[b + 5] - x[b + 1];
        r2 = x[b + 7] - x[b + 3];
        x[b] = r1 + r0;
        x[b + 2] = r1 - r0;
        r0 = x[b + 5] + x[b + 1];
        r1 = x[b + 7] + x[b + 3];
        x[b + 3] = r2 + r3;
        x[b + 1] = r2 - r3;
        x[b + 7] = r1 + r0;
        x[b + 5] = r1 - r0;
    }

    /**
     * Applies the bit reversal and the final rotation of the butterflies' output.
     *
     * @param x the work buffer
     */
    private void bitReverse(float[] x) {
        var bit = bitrev;
        var bi = 0;
        var w0 = 0;
        var half = n >> 1;
        var w1 = half;
        var t = n;
        do {
            var x0 = half + bit[bi];
            var x1 = half + bit[bi + 1];
            var r0 = x[x0 + 1] - x[x1 + 1];
            var r1 = x[x0] + x[x1];
            var r2 = r1 * trig[t] + r0 * trig[t + 1];
            var r3 = r1 * trig[t + 1] - r0 * trig[t];
            w1 -= 4;
            r0 = (x[x0 + 1] + x[x1 + 1]) * .5f;
            r1 = (x[x0] - x[x1]) * .5f;
            x[w0] = r0 + r2;
            x[w1 + 2] = r0 - r2;
            x[w0 + 1] = r1 + r3;
            x[w1 + 3] = r3 - r1;
            x0 = half + bit[bi + 2];
            x1 = half + bit[bi + 3];
            r0 = x[x0 + 1] - x[x1 + 1];
            r1 = x[x0] + x[x1];
            r2 = r1 * trig[t + 2] + r0 * trig[t + 3];
            r3 = r1 * trig[t + 3] - r0 * trig[t + 2];
            r0 = (x[x0 + 1] + x[x1 + 1]) * .5f;
            r1 = (x[x0] - x[x1]) * .5f;
            x[w0 + 2] = r0 + r2;
            x[w1] = r0 - r2;
            x[w0 + 3] = r1 + r3;
            x[w1 + 1] = r3 - r1;
            t += 4;
            bi += 4;
            w0 += 4;
        } while (w0 < w1);
    }
}
