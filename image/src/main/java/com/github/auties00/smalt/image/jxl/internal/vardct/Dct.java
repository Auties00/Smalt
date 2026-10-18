/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: dct). Copyright Wonwoo Choi and the jxl-oxide contributors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

/**
 * Computes the scaled DCT-II and its inverse used by VarDCT frames (ISO/IEC 18181-1 section
 * I.6.1), in one and two dimensions, for power-of-two sizes from 1 to 256.
 *
 * <p>The forward transform of {@code n} samples is
 * {@code X[k] = (1 / n) sum x[i] cos(k (2i + 1) pi / 2n)}, multiplied by {@code sqrt(2)} for
 * {@code k > 0}; the inverse is its exact inverse. Every method is a pure function of its
 * arguments apart from the arrays it writes.
 *
 * @implNote This implementation uses a recursive even-odd decomposition: sizes 2, 4 and 8 are
 * written out, larger sizes split into two halves of the next smaller size.
 */
public final class Dct {
    /**
     * The value of {@code sqrt(2)}.
     */
    private static final float SQRT2 = (float) Math.sqrt(2.0);

    /**
     * The secant tables {@code 1 / (2 cos((2k + 1) pi / 2n))} for {@code n} = 4, 8, ..., 256,
     * indexed by {@code log2(n) - 2}.
     */
    private static final float[][] SEC_HALF = new float[7][];

    static {
        SEC_HALF[0] = new float[]{0.541196100146197f, 1.3065629648763764f};
        SEC_HALF[1] = new float[]{0.5097955791041592f, 0.6013448869350453f, 0.8999762231364156f, 2.5629154477415055f};
        SEC_HALF[2] = new float[]{0.5024192861881557f, 0.5224986149396889f, 0.5669440348163577f, 0.6468217833599901f,
                0.7881546234512502f, 1.060677685990347f, 1.7224470982383342f, 5.101148618689155f};
        SEC_HALF[3] = new float[]{0.5006029982351963f, 0.5054709598975436f, 0.5154473099226246f, 0.5310425910897841f,
                0.5531038960344445f, 0.5829349682061339f, 0.6225041230356648f, 0.6748083414550057f,
                0.7445362710022984f, 0.8393496454155268f, 0.9725682378619608f, 1.1694399334328847f,
                1.4841646163141662f, 2.057781009953411f, 3.407608418468719f, 10.190008123548033f};
        for (var i = 4; i < 7; i++) {
            var n = 4 << i;
            var table = new float[n / 2];
            for (var k = 0; k < n / 2; k++) {
                var theta = (float) (2 * k + 1) / (float) (2 * n) * (float) Math.PI;
                table[k] = (1.0f / (float) Math.cos(theta)) / 2.0f;
            }
            SEC_HALF[i] = table;
        }
    }

    /**
     * The scale of the low-frequency coefficients of large transforms (ISO/IEC 18181-1 section
     * I.6.3), precomputed for {@code c} from 0 to 31 at block size 256.
     */
    private static final float[] SCALE_F = {
            1.0000000000000000f, 0.9996047255830407f, 0.9984194528776054f, 0.9964458326264695f,
            0.9936866130906366f, 0.9901456355893141f, 0.9858278282666936f, 0.9807391980963174f,
            0.9748868211368796f, 0.9682788310563117f, 0.9609244059440204f, 0.9528337534340876f,
            0.9440180941651672f, 0.9344896436056892f, 0.9242615922757944f, 0.9133480844001980f,
            0.9017641950288744f, 0.8895259056651056f, 0.8766500784429904f, 0.8631544288990163f,
            0.8490574973847023f, 0.8343786191696513f, 0.8191378932865928f, 0.8033561501721485f,
            0.7870549181591013f, 0.7702563888779096f, 0.7529833816270532f, 0.7352593067735488f,
            0.7171081282466044f, 0.6985543251889097f, 0.6796228528314652f, 0.6603391026591464f,
    };

    /**
     * Prevents instantiation.
     */
    private Dct() {
        throw new AssertionError();
    }

    /**
     * Returns the scale of low-frequency coefficient {@code c} of a block of {@code 2^(5 - logb)}
     * 8x8 blocks.
     *
     * @param c the coefficient index
     * @param logb the base-2 logarithm of {@code 32 / blocks}
     * @return the scale
     */
    static float scaleF(int c, int logb) {
        return SCALE_F[c << logb];
    }

    /**
     * Transforms {@code n} contiguous samples in place.
     *
     * @param io the samples
     * @param off the index of the first sample
     * @param scratch a buffer of at least {@code soff + n} elements
     * @param soff the first scratch index used
     * @param n the number of samples, a power of two up to 256
     * @param inverse whether to apply the inverse transform
     */
    static void dct1d(float[] io, int off, float[] scratch, int soff, int n, boolean inverse) {
        switch (n) {
            case 1 -> {
            }
            case 2 -> {
                var a = io[off];
                var b = io[off + 1];
                if (inverse) {
                    io[off] = a + b;
                    io[off + 1] = a - b;
                } else {
                    io[off] = (a + b) / 2.0f;
                    io[off + 1] = (a - b) / 2.0f;
                }
            }
            case 4 -> dct4(io, off, inverse);
            case 8 -> dct8(io, off, inverse);
            default -> dctLarge(io, off, scratch, soff, n, inverse);
        }
    }

    /**
     * Transforms four contiguous samples in place.
     *
     * @param io the samples
     * @param off the index of the first sample
     * @param inverse whether to apply the inverse transform
     */
    private static void dct4(float[] io, int off, boolean inverse) {
        var sec0 = 0.5411961f;
        var sec1 = 1.306563f;
        var i0 = io[off];
        var i1 = io[off + 1];
        var i2 = io[off + 2];
        var i3 = io[off + 3];
        if (!inverse) {
            var sum03 = i0 + i3;
            var sum12 = i1 + i2;
            var tmp0 = (i0 - i3) * sec0;
            var tmp1 = (i1 - i2) * sec1;
            var out0 = (tmp0 + tmp1) / 4.0f;
            var out1 = (tmp0 - tmp1) / 4.0f;
            io[off] = (sum03 + sum12) / 4.0f;
            io[off + 1] = out0 * SQRT2 + out1;
            io[off + 2] = (sum03 - sum12) / 4.0f;
            io[off + 3] = out1;
        } else {
            var tmp0 = i1 * SQRT2;
            var tmp1 = i1 + i3;
            var out0 = (tmp0 + tmp1) * sec0;
            var out1 = (tmp0 - tmp1) * sec1;
            var sum02 = i0 + i2;
            var sub02 = i0 - i2;
            io[off] = sum02 + out0;
            io[off + 1] = sub02 + out1;
            io[off + 2] = sub02 - out1;
            io[off + 3] = sum02 - out0;
        }
    }

    /**
     * Transforms eight contiguous samples in place.
     *
     * <p>The two 4-point halves are computed in local variables, with the same operations in the
     * same order as {@link #dct4(float[], int, boolean)}, so no scratch array is needed.
     *
     * @param io the samples
     * @param off the index of the first sample
     * @param inverse whether to apply the inverse transform
     */
    private static void dct8(float[] io, int off, boolean inverse) {
        var sec = SEC_HALF[1];
        var sec0 = 0.5411961f;
        var sec1 = 1.306563f;
        if (!inverse) {
            var i0 = io[off];
            var i1 = io[off + 1];
            var i2 = io[off + 2];
            var i3 = io[off + 3];
            var i4 = io[off + 4];
            var i5 = io[off + 5];
            var i6 = io[off + 6];
            var i7 = io[off + 7];
            var t0 = (i0 + i7) / 2.0f;
            var t1 = (i1 + i6) / 2.0f;
            var t2 = (i2 + i5) / 2.0f;
            var t3 = (i3 + i4) / 2.0f;
            var t4 = (i0 - i7) * sec[0] / 2.0f;
            var t5 = (i1 - i6) * sec[1] / 2.0f;
            var t6 = (i2 - i5) * sec[2] / 2.0f;
            var t7 = (i3 - i4) * sec[3] / 2.0f;
            var sum03 = t0 + t3;
            var sum12 = t1 + t2;
            var tmp0 = (t0 - t3) * sec0;
            var tmp1 = (t1 - t2) * sec1;
            var out0 = (tmp0 + tmp1) / 4.0f;
            var out1 = (tmp0 - tmp1) / 4.0f;
            io[off] = (sum03 + sum12) / 4.0f;
            io[off + 2] = out0 * SQRT2 + out1;
            io[off + 4] = (sum03 - sum12) / 4.0f;
            io[off + 6] = out1;
            var sum47 = t4 + t7;
            var sum56 = t5 + t6;
            var tmp4 = (t4 - t7) * sec0;
            var tmp5 = (t5 - t6) * sec1;
            var out4 = (tmp4 + tmp5) / 4.0f;
            var out5 = (tmp4 - tmp5) / 4.0f;
            var v0 = (sum47 + sum56) / 4.0f * SQRT2;
            var v1 = out4 * SQRT2 + out5;
            var v2 = (sum47 - sum56) / 4.0f;
            io[off + 1] = v0 + v1;
            io[off + 3] = v1 + v2;
            io[off + 5] = v2 + out5;
            io[off + 7] = out5;
        } else {
            var t0 = io[off];
            var t1 = io[off + 2];
            var t2 = io[off + 4];
            var t3 = io[off + 6];
            var t4 = io[off + 1] * SQRT2;
            var t5 = io[off + 3] + io[off + 1];
            var t6 = io[off + 5] + io[off + 3];
            var t7 = io[off + 7] + io[off + 5];
            var tmp0 = t1 * SQRT2;
            var tmp1 = t1 + t3;
            var out0 = (tmp0 + tmp1) * sec0;
            var out1 = (tmp0 - tmp1) * sec1;
            var sum02 = t0 + t2;
            var sub02 = t0 - t2;
            var u0 = sum02 + out0;
            var u1 = sub02 + out1;
            var u2 = sub02 - out1;
            var u3 = sum02 - out0;
            var tmp4 = t5 * SQRT2;
            var tmp5 = t5 + t7;
            var out4 = (tmp4 + tmp5) * sec0;
            var out5 = (tmp4 - tmp5) * sec1;
            var sum46 = t4 + t6;
            var sub46 = t4 - t6;
            var r0 = (sum46 + out4) * sec[0];
            var r1 = (sub46 + out5) * sec[1];
            var r2 = (sub46 - out5) * sec[2];
            var r3 = (sum46 - out4) * sec[3];
            io[off] = u0 + r0;
            io[off + 7] = u0 - r0;
            io[off + 1] = u1 + r1;
            io[off + 6] = u1 - r1;
            io[off + 2] = u2 + r2;
            io[off + 5] = u2 - r2;
            io[off + 3] = u3 + r3;
            io[off + 4] = u3 - r3;
        }
    }

    /**
     * Transforms 16 or more contiguous samples in place by splitting them into halves.
     *
     * @param io the samples
     * @param off the index of the first sample
     * @param scratch the scratch buffer
     * @param soff the first scratch index used
     * @param n the number of samples
     * @param inverse whether to apply the inverse transform
     */
    private static void dctLarge(float[] io, int off, float[] scratch, int soff, int n, boolean inverse) {
        var half = n / 2;
        var sec = SEC_HALF[Integer.numberOfTrailingZeros(n) - 2];
        if (!inverse) {
            for (var i = 0; i < half; i++) {
                var a = io[off + i];
                var b = io[off + n - i - 1];
                scratch[soff + i] = (a + b) / 2.0f;
                scratch[soff + half + i] = (a - b) / 2.0f * sec[i];
            }
            dct1d(scratch, soff, io, off, half, false);
            dct1d(scratch, soff + half, io, off + half, half, false);
            scratch[soff + half] *= SQRT2;
            for (var i = 0; i < half - 1; i++) {
                scratch[soff + half + i] += scratch[soff + half + i + 1];
            }
            for (var i = 0; i < half; i++) {
                io[off + i * 2] = scratch[soff + i];
                io[off + i * 2 + 1] = scratch[soff + half + i];
            }
        } else {
            for (var i = 0; i < half; i++) {
                scratch[soff + i] = io[off + i * 2];
                scratch[soff + half + i] = io[off + i * 2 + 1];
            }
            for (var i = 1; i < half; i++) {
                scratch[soff + half + half - i] += scratch[soff + half + half - i - 1];
            }
            scratch[soff + half] *= SQRT2;
            dct1d(scratch, soff, io, off, half, true);
            dct1d(scratch, soff + half, io, off + half, half, true);
            for (var i = 0; i < half; i++) {
                scratch[soff + half + i] *= sec[i];
            }
            for (var i = 0; i < half; i++) {
                var a = scratch[soff + i];
                var b = scratch[soff + half + i];
                io[off + i] = a + b;
                io[off + n - 1 - i] = a - b;
            }
        }
    }

    /**
     * Transforms a two-dimensional block in place: rows first, then columns.
     *
     * @param buf the samples
     * @param off the index of the top-left sample
     * @param stride the distance between rows
     * @param width the block width, a power of two
     * @param height the block height, a power of two
     * @param scratch a buffer of at least {@code 2 * max(width, height)} elements
     * @param inverse whether to apply the inverse transform
     */
    static void dct2d(float[] buf, int off, int stride, int width, int height, float[] scratch, boolean inverse) {
        if (width > 1) {
            for (var y = 0; y < height; y++) {
                dct1d(buf, off + y * stride, scratch, 0, width, inverse);
            }
        }
        if (height > 1) {
            var column = scratch;
            var base = Math.max(width, height);
            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    column[base + y] = buf[off + y * stride + x];
                }
                dct1d(column, base, scratch, 0, height, inverse);
                for (var y = 0; y < height; y++) {
                    buf[off + y * stride + x] = column[base + y];
                }
            }
        }
    }
}
