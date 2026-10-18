package com.github.auties00.smalt.video.av1.internal;

/**
 * Derives warped motion parameters: the shear parameters of a model (AV1 specification section
 * 7.11.3.6) and the local warp model of a block fitted to its neighbors' motion (section 7.11.3.8).
 *
 * @implNote This implementation ports dav1d's {@code src/warpmv.c}.
 */
public final class WarpMv {
    /**
     * Holds the reciprocal multipliers of the divisor approximation ({@code Div_Lut}, AV1
     * specification section 7.11.3.7).
     */
    private static final int[] DIV_LUT = {
            16384, 16320, 16257, 16194, 16132, 16070, 16009, 15948, 15888, 15828, 15768,
            15709, 15650, 15592, 15534, 15477, 15420, 15364, 15308, 15252, 15197, 15142,
            15087, 15033, 14980, 14926, 14873, 14821, 14769, 14717, 14665, 14614, 14564,
            14513, 14463, 14413, 14364, 14315, 14266, 14218, 14170, 14122, 14075, 14028,
            13981, 13935, 13888, 13843, 13797, 13752, 13707, 13662, 13618, 13574, 13530,
            13487, 13443, 13400, 13358, 13315, 13273, 13231, 13190, 13148, 13107, 13066,
            13026, 12985, 12945, 12906, 12866, 12827, 12788, 12749, 12710, 12672, 12633,
            12596, 12558, 12520, 12483, 12446, 12409, 12373, 12336, 12300, 12264, 12228,
            12193, 12157, 12122, 12087, 12053, 12018, 11984, 11950, 11916, 11882, 11848,
            11815, 11782, 11749, 11716, 11683, 11651, 11619, 11586, 11555, 11523, 11491,
            11460, 11429, 11398, 11367, 11336, 11305, 11275, 11245, 11215, 11185, 11155,
            11125, 11096, 11067, 11038, 11009, 10980, 10951, 10923, 10894, 10866, 10838,
            10810, 10782, 10755, 10727, 10700, 10673, 10645, 10618, 10592, 10565, 10538,
            10512, 10486, 10460, 10434, 10408, 10382, 10356, 10331, 10305, 10280, 10255,
            10230, 10205, 10180, 10156, 10131, 10107, 10082, 10058, 10034, 10010, 9986,
            9963, 9939, 9916, 9892, 9869, 9846, 9823, 9800, 9777, 9754, 9732,
            9709, 9687, 9664, 9642, 9620, 9598, 9576, 9554, 9533, 9511, 9489,
            9468, 9447, 9425, 9404, 9383, 9362, 9341, 9321, 9300, 9279, 9259,
            9239, 9218, 9198, 9178, 9158, 9138, 9118, 9098, 9079, 9059, 9039,
            9020, 9001, 8981, 8962, 8943, 8924, 8905, 8886, 8867, 8849, 8830,
            8812, 8793, 8775, 8756, 8738, 8720, 8702, 8684, 8666, 8648, 8630,
            8613, 8595, 8577, 8560, 8542, 8525, 8508, 8490, 8473, 8456, 8439,
            8422, 8405, 8389, 8372, 8355, 8339, 8322, 8306, 8289, 8273, 8257,
            8240, 8224, 8208, 8192,
    };

    /**
     * Prevents instantiation.
     */
    private WarpMv() {
        throw new AssertionError();
    }

    /**
     * Clamps a shear parameter to 16 bits and rounds it to a multiple of 64.
     *
     * @param v the parameter
     * @return the rounded parameter
     */
    private static int clipWmp(int v) {
        var cv = Math.clamp(v, Short.MIN_VALUE, Short.MAX_VALUE);
        var r = (Math.abs(cv) + 32) >> 6;
        return (cv < 0 ? -r : r) * 64;
    }

    /**
     * Computes the shear parameters of a warp model and stores them in its {@code abcd}.
     *
     * @param wm the model
     * @return {@code true} when the model is not a valid warp (its shear is too large or its matrix
     * degenerate), {@code false} when it is usable
     */
    public static boolean getShearParams(WarpedMotionParams wm) {
        var mat = wm.matrix;
        if (mat[2] <= 0) {
            return true;
        }
        wm.abcd[0] = (short) clipWmp(mat[2] - 0x10000);
        wm.abcd[1] = (short) clipWmp(mat[3]);
        var d = Math.abs(mat[2]);
        var shift = 31 - Integer.numberOfLeadingZeros(d);
        var e = d - (1 << shift);
        var f = shift > 8 ? (e + (1 << (shift - 9))) >> (shift - 8) : e << (8 - shift);
        shift += 14;
        var y = mat[2] < 0 ? -DIV_LUT[f] : DIV_LUT[f];
        var v1 = ((long) mat[4] * 0x10000) * y;
        var rnd = (1L << shift) >> 1;
        var g = (int) ((Math.abs(v1) + rnd) >> shift);
        wm.abcd[2] = (short) clipWmp(v1 < 0 ? -g : g);
        var v2 = ((long) mat[3] * mat[4]) * y;
        var dl = (int) ((Math.abs(v2) + rnd) >> shift);
        wm.abcd[3] = (short) clipWmp(mat[5] - (v2 < 0 ? -dl : dl) - 0x10000);
        return 4 * Math.abs((int) wm.abcd[0]) + 7 * Math.abs((int) wm.abcd[1]) >= 0x10000
                || 4 * Math.abs((int) wm.abcd[2]) + 4 * Math.abs((int) wm.abcd[3]) >= 0x10000;
    }


    /**
     * Rounds a product scaled by the reciprocal of a determinant.
     *
     * @param px the product
     * @param idet the reciprocal
     * @param shift the shift of the reciprocal
     * @return the rounded quotient
     */
    private static int multShift(long px, int idet, int shift) {
        var v1 = px * idet;
        var v = (int) ((Math.abs(v1) + ((1L << shift) >> 1)) >> shift);
        return v1 < 0 ? -v : v;
    }

    /**
     * Fits an affine warp model to the motion of neighboring blocks by least squares.
     *
     * @param pts the sample pairs, as {@code [sample 8][source/destination 2][x/y 2]} flattened
     * @param np the number of samples
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     * @param mvy the vertical motion vector component of the block
     * @param mvx the horizontal motion vector component of the block
     * @param wm the model to write
     * @param bx4 the block column in 4-sample units
     * @param by4 the block row in 4-sample units
     * @return {@code true} when no model could be fitted
     */
    public static boolean findAffineInt(int[] pts, int np, int bw4, int bh4, int mvy, int mvx,
                                        WarpedMotionParams wm, int bx4, int by4) {
        var mat = wm.matrix;
        int a00 = 0, a01 = 0, a11 = 0;
        int bx0 = 0, bx1 = 0, by0 = 0, by1 = 0;
        var rsuy = 2 * bh4 - 1;
        var rsux = 2 * bw4 - 1;
        var suy = rsuy * 8;
        var sux = rsux * 8;
        var duy = suy + mvy;
        var dux = sux + mvx;
        var isuy = by4 * 4 + rsuy;
        var isux = bx4 * 4 + rsux;
        for (var i = 0; i < np; i++) {
            var dx = pts[i * 4 + 2] - dux;
            var dy = pts[i * 4 + 3] - duy;
            var sx = pts[i * 4] - sux;
            var sy = pts[i * 4 + 1] - suy;
            if (Math.abs(sx - dx) < 256 && Math.abs(sy - dy) < 256) {
                a00 += ((sx * sx) >> 2) + sx * 2 + 8;
                a01 += ((sx * sy) >> 2) + sx + sy + 4;
                a11 += ((sy * sy) >> 2) + sy * 2 + 8;
                bx0 += ((sx * dx) >> 2) + sx + dx + 8;
                bx1 += ((sy * dx) >> 2) + sy + dx + 4;
                by0 += ((sx * dy) >> 2) + sx + dy + 4;
                by1 += ((sy * dy) >> 2) + sy + dy + 8;
            }
        }
        var det = (long) a00 * a11 - (long) a01 * a01;
        if (det == 0) {
            return true;
        }
        var ad = Math.abs(det);
        var shift = 63 - Long.numberOfLeadingZeros(ad);
        var e = ad - (1L << shift);
        var f = shift > 8 ? (e + (1L << (shift - 9))) >> (shift - 8) : e << (8 - shift);
        shift += 14;
        var idet = det < 0 ? -DIV_LUT[(int) f] : DIV_LUT[(int) f];
        shift -= 16;
        if (shift < 0) {
            idet <<= -shift;
            shift = 0;
        }
        mat[2] = Math.clamp(multShift((long) a11 * bx0 - (long) a01 * bx1, idet, shift), 0xe001, 0x11fff);
        mat[3] = Math.clamp(multShift((long) a00 * bx1 - (long) a01 * bx0, idet, shift), -0x1fff, 0x1fff);
        mat[4] = Math.clamp(multShift((long) a11 * by0 - (long) a01 * by1, idet, shift), -0x1fff, 0x1fff);
        mat[5] = Math.clamp(multShift((long) a00 * by1 - (long) a01 * by0, idet, shift), 0xe001, 0x11fff);
        mat[0] = Math.clamp(mvx * 0x2000 - (isux * (mat[2] - 0x10000) + isuy * mat[3]), -0x800000, 0x7fffff);
        mat[1] = Math.clamp(mvy * 0x2000 - (isux * mat[4] + isuy * (mat[5] - 0x10000)), -0x800000, 0x7fffff);
        return false;
    }
}
