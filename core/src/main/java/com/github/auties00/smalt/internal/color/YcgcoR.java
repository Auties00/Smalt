package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;

/**
 * The reversible integer YCgCo-R transform of the YCgCo-Re and YCgCo-Ro matrices (ITU-T H.273 equations 23 to 29,
 * 33 to 35 and 58 to 65).
 *
 * <p>The R'G'B' samples have {@link #rgbDepth} bits: two fewer than the Y'CbCr samples for YCgCo-Re and one fewer for
 * YCgCo-Ro. Luma has no offset and the color differences Cg (stored as Cb) and Co (stored as Cr) are offset by
 * {@code 1 << (depth - 1)}; {@link #forward} followed by {@link #inverse} returns its R'G'B' input exactly.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class YcgcoR {
    /**
     * Prevents instantiation.
     */
    private YcgcoR() {
        throw new AssertionError();
    }

    /**
     * Returns whether a matrix is one of the YCgCo-R matrices.
     *
     * @param matrix the matrix coefficients
     * @return {@code true} for {@link MatrixCoefficients#YCGCO_RE} and {@link MatrixCoefficients#YCGCO_RO}
     */
    public static boolean applies(MatrixCoefficients matrix) {
        return matrix == MatrixCoefficients.YCGCO_RE || matrix == MatrixCoefficients.YCGCO_RO;
    }

    /**
     * Returns the depth of the R'G'B' samples (equations 24 and 25).
     *
     * @param matrix {@link MatrixCoefficients#YCGCO_RE} or {@link MatrixCoefficients#YCGCO_RO}
     * @param depth the depth of the Y'CbCr samples
     * @return {@code depth - 2} for YCgCo-Re, {@code depth - 1} for YCgCo-Ro
     */
    public static int rgbDepth(MatrixCoefficients matrix, int depth) {
        return matrix == MatrixCoefficients.YCGCO_RE ? depth - 2 : depth - 1;
    }

    /**
     * Quantizes a normalized R'G'B' signal to a sample (equations 27 to 29 or 33 to 35, then rounding).
     *
     * @param signal the signal, 0 at nominal black and 1 at nominal white
     * @param range the quantization range
     * @param rgbDepth the depth of the R'G'B' samples, at least 1
     * @return the sample, clipped to {@code 0..(1 << rgbDepth) - 1}
     */
    public static int toSample(double signal, ColorRange range, int rgbDepth) {
        var max = (1 << rgbDepth) - 1;
        var value = range == ColorRange.FULL ? max * signal : Math.scalb(219 * signal + 16, rgbDepth - 8);
        return (int) Math.round(Math.clamp(value, 0, max));
    }

    /**
     * Returns the normalized signal of an R'G'B' sample, the inverse of {@link #toSample} before rounding.
     *
     * @param sample the R'G'B' code value
     * @param range the quantization range
     * @param rgbDepth the depth of the R'G'B' samples, at least 1
     * @return the signal
     */
    public static double toSignal(int sample, ColorRange range, int rgbDepth) {
        if (range == ColorRange.FULL) {
            return sample / (double) ((1 << rgbDepth) - 1);
        }
        return (Math.scalb((double) sample, 8 - rgbDepth) - 16) / 219;
    }

    /**
     * Transforms R'G'B' samples to Y'CgCo samples (equations 58 to 61).
     *
     * @param r the red sample
     * @param g the green sample
     * @param b the blue sample
     * @param depth the depth of the Y'CbCr samples
     * @param out receives Y, Cg and Co, at least 3 long
     */
    public static void forward(int r, int g, int b, int depth, int[] out) {
        var half = 1 << (depth - 1);
        var co = r - b;
        var t = b + (co >> 1);
        var cg = g - t;
        out[0] = t + (cg >> 1);
        out[1] = cg + half;
        out[2] = co + half;
    }

    /**
     * Transforms Y'CgCo samples back to R'G'B' samples (equations 62 to 65).
     *
     * @param y the luma sample
     * @param cg the Cg sample, stored as Cb
     * @param co the Co sample, stored as Cr
     * @param depth the depth of the Y'CbCr samples
     * @param rgbDepth the depth of the R'G'B' samples
     * @param out receives red, green and blue, each clipped to {@code 0..(1 << rgbDepth) - 1}, at least 3 long
     */
    public static void inverse(int y, int cg, int co, int depth, int rgbDepth, int[] out) {
        var half = 1 << (depth - 1);
        var max = (1 << rgbDepth) - 1;
        var g0 = cg - half;
        var c0 = co - half;
        var t = y - (g0 >> 1);
        var b = Math.clamp(t - (c0 >> 1), 0, max);
        out[0] = Math.clamp(b + c0, 0, max);
        out[1] = Math.clamp(t + g0, 0, max);
        out[2] = b;
    }
}
