/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * type/warp.cpp: CPerspective2D from warping points). Copyright 1996-2000 Microsoft Corporation and the contributors
 * named in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The warping of a static sprite from up to four reference points (7.8.4), as a projective transform whose output
 * is in units of {@code 1 / 2^(accuracy + 1)} samples ({@code CPerspective2D}).
 */
final class Perspective {
    private final double[] coeff = new double[9];
    private final double x0;
    private final double y0;
    int resultX;
    int resultY;

    /**
     * Builds the transform from the source corners of the VOP to the warped points.
     *
     * @param srcX the horizontal source points
     * @param dstX the horizontal destination points, in samples
     */
    Perspective(int points, double[] srcX, double[] srcY, double[] dstX, double[] dstY, int accuracy) {
        var scale = 1 << (accuracy + 1);
        x0 = srcX[0];
        y0 = srcY[0];
        var x = new double[4];
        var y = new double[4];
        for (var k = 0; k < points; k++) {
            x[k] = dstX[k] * scale;
            y[k] = dstY[k] * scale;
        }
        var width = points > 1 ? (int) (srcX[1] - srcX[0]) : 0;
        var height = points > 2 ? (int) (srcY[2] - srcY[0]) : 0;
        double a11;
        double a21;
        double a31;
        double a12;
        double a22;
        double a32;
        double a13 = 0;
        double a23 = 0;
        double a33;
        if (points == 1) {
            a11 = scale;
            a21 = 0;
            a31 = x[0];
            a12 = 0;
            a22 = scale;
            a32 = y[0];
            a33 = 1.0;
        } else if (points == 2) {
            a11 = x[1] - x[0];
            a21 = y[0] - y[1];
            a31 = x[0] * width;
            a12 = y[1] - y[0];
            a22 = x[1] - x[0];
            a32 = y[0] * width;
            a33 = width;
        } else {
            var dx1 = x[1] - x[3];
            var dx2 = x[2] - x[3];
            var dx3 = x[0] - x[1] + x[3] - x[2];
            var dy1 = y[1] - y[3];
            var dy2 = y[2] - y[3];
            var dy3 = y[0] - y[1] + y[3] - y[2];
            if (points == 3 || (dx3 == 0 && dy3 == 0)) {
                a11 = (x[1] - x[0]) * height;
                a21 = (x[2] - x[0]) * width;
                a31 = x[0] * height * width;
                a12 = (y[1] - y[0]) * height;
                a22 = (y[2] - y[0]) * width;
                a32 = y[0] * height * width;
                a33 = height * width;
            } else {
                var deltaD = dx1 * dy2 - dx2 * dy1;
                a13 = (dx3 * dy2 - dx2 * dy3) * height;
                a23 = (dx1 * dy3 - dx3 * dy1) * width;
                a11 = (x[1] - x[0]) * height * deltaD + a13 * x[1];
                a21 = (x[2] - x[0]) * width * deltaD + a23 * x[2];
                a31 = x[0] * height * width * deltaD;
                a12 = (y[1] - y[0]) * deltaD * height + a13 * y[1];
                a22 = (y[2] - y[0]) * deltaD * width + a23 * y[2];
                a32 = y[0] * height * width * deltaD;
                a33 = height * width * deltaD;
            }
        }
        coeff[0] = a11;
        coeff[1] = a21;
        coeff[2] = a31;
        coeff[3] = a12;
        coeff[4] = a22;
        coeff[5] = a32;
        coeff[6] = a13;
        coeff[7] = a23;
        coeff[8] = a33;
    }

    /**
     * Maps a sample position into {@link #resultX} and {@link #resultY} with the rounding of the reference
     * software ({@code apply (const CSite&)}).
     *
     * @return {@code false} when the denominator is zero
     */
    boolean apply(int sx, int sy) {
        var destX = (sx - x0) * coeff[0] + (sy - y0) * coeff[1] + coeff[2];
        var destY = (sx - x0) * coeff[3] + (sy - y0) * coeff[4] + coeff[5];
        var destW = (sx - x0) * coeff[6] + (sy - y0) * coeff[7] + coeff[8];
        if (destW == 0.0) {
            return false;
        }
        resultX = round(destX, destW);
        resultY = round(destY, destW);
        return true;
    }

    private static int round(double num, double w) {
        if ((num >= 0 && w > 0) || (num <= 0 && w < 0)) {
            return (int) ((num + w / 2) / w);
        }
        if (num > 0 && w < 0) {
            return (int) ((num - (w + 1) / 2) / w);
        }
        return (int) ((num - (w - 1) / 2) / w);
    }
}
