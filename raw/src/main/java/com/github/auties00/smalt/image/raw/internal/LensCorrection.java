package com.github.auties00.smalt.image.raw.internal;

import java.nio.ByteBuffer;

/**
 * The lens corrections a camera raw file records for its shot: radial distortion, lateral chromatic aberration
 * and vignetting, each a function of the distance from the optical center.
 *
 * <p>Each function is sampled at knots: radii in units of {@link #radius} pixels, increasing, with linear
 * interpolation between them and the last value held beyond the last knot. Distortion gives, for each of the
 * red, green and blue planes, the factor {@code f} by which an output pixel at radius {@code r} takes the source
 * pixel at radius {@code r * f}; vignetting gives the gain an output pixel is multiplied by.
 *
 * <p>{@link #opcodes} turns the corrections into a DNG opcode list ({@code FixVignetteRadial} followed by
 * {@code WarpRectilinear}), fitting the polynomials of those opcodes to the functions by least squares in the
 * normalization DNG defines: the distance from the optical center to the farthest corner of the image. Distances are
 * measured in units of a pixel's height: for an image of pixels {@link #aspect} times as wide as they are high
 * (the staggered images of Fujifilm EXR sensors), horizontal offsets count {@code aspect} times their pixels, as
 * {@link Opcodes} measures them.
 */
final class LensCorrection {
    /**
     * The DNG opcode id of {@code WarpRectilinear}.
     */
    private static final int WARP_RECTILINEAR = 1;

    /**
     * The DNG opcode id of {@code FixVignetteRadial}.
     */
    private static final int FIX_VIGNETTE_RADIAL = 3;

    /**
     * The DNG version that introduced the opcodes, 1.3.0.0.
     */
    private static final int OPCODE_VERSION = 0x01030000;

    /**
     * The radii, from the center to the farthest corner, at which a fit samples the functions.
     */
    private static final int FIT_SAMPLES = 256;

    /**
     * The column of the optical center, in pixels from the left edge of the active area.
     */
    final double centerX;

    /**
     * The row of the optical center, in pixels from the top edge of the active area.
     */
    final double centerY;

    /**
     * The radius, in pixel heights of the image the corrections apply to, that the knots are measured in.
     */
    final double radius;

    /**
     * The width of a pixel of the image the corrections apply to, relative to its height.
     */
    final double aspect;

    /**
     * The knots of the distortion functions, in units of {@link #radius}, or {@code null} for none.
     */
    final double[] distortionKnots;

    /**
     * The distortion factor of each knot, for the red, green and blue planes, or {@code null} for none.
     */
    final double[][] distortion;

    /**
     * The knots of the vignetting gain, in units of {@link #radius}, or {@code null} for none.
     */
    final double[] vignetteKnots;

    /**
     * The vignetting gain of each knot, or {@code null} for none.
     */
    final double[] vignette;

    /**
     * Constructs the corrections.
     *
     * @param centerX the column of the optical center, in pixels from the left edge of the active area
     * @param centerY the row of the optical center, in pixels from the top edge of the active area
     * @param radius the radius the knots are measured in, in pixels, positive
     * @param distortionKnots the distortion knots, or {@code null}
     * @param distortion the distortion factors of the red, green and blue planes at the knots, or {@code null}
     * @param vignetteKnots the vignetting knots, or {@code null}
     * @param vignette the vignetting gains at the knots, or {@code null}
     */
    LensCorrection(double centerX, double centerY, double radius, double[] distortionKnots, double[][] distortion, double[] vignetteKnots,
                   double[] vignette) {
        this(centerX, centerY, radius, 1, distortionKnots, distortion, vignetteKnots, vignette);
    }

    /**
     * Constructs the corrections of an image of non-square pixels.
     *
     * @param centerX the column of the optical center, in pixels from the left edge of the active area
     * @param centerY the row of the optical center, in pixels from the top edge of the active area
     * @param radius the radius the knots are measured in, in pixel heights, positive
     * @param aspect the width of a pixel relative to its height, positive
     * @param distortionKnots the distortion knots, or {@code null}
     * @param distortion the distortion factors of the red, green and blue planes at the knots, or {@code null}
     * @param vignetteKnots the vignetting knots, or {@code null}
     * @param vignette the vignetting gains at the knots, or {@code null}
     */
    LensCorrection(double centerX, double centerY, double radius, double aspect, double[] distortionKnots,
                   double[][] distortion, double[] vignetteKnots, double[] vignette) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.aspect = aspect;
        this.distortionKnots = distortionKnots;
        this.distortion = distortion;
        this.vignetteKnots = vignetteKnots;
        this.vignette = vignette;
    }

    /**
     * Returns the same corrections for an image whose pixels are {@code aspect} times as wide as they are high and
     * that these corrections describe with square pixels of the same height.
     *
     * @param aspect the width of a pixel of the image relative to its height, positive
     * @return the corrections of the image
     */
    LensCorrection withAspect(double aspect) {
        return new LensCorrection(centerX / aspect, centerY, radius, aspect, distortionKnots, distortion, vignetteKnots,
                vignette);
    }

    /**
     * Returns whether the corrections change the image: a distortion factor or a vignetting gain other than 1.
     *
     * @return whether the corrections do something
     */
    boolean active() {
        return differs(distortion) || (vignette != null && differs(new double[][]{vignette}));
    }

    /**
     * Returns whether a set of functions holds a value other than 1.
     *
     * @param functions the functions, or {@code null}
     * @return whether one differs from 1
     */
    private static boolean differs(double[][] functions) {
        if (functions == null) {
            return false;
        }
        for (var function : functions) {
            for (var value : function) {
                if (Math.abs(value - 1) > 1e-9) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Evaluates a function at a radius.
     *
     * @param knots the radii of the values, increasing, in units of {@link #radius}
     * @param values the values at the knots
     * @param u the radius, in units of {@link #radius}
     * @return the value, linearly interpolated, the first value before the first knot and the last beyond the last
     */
    static double evaluate(double[] knots, double[] values, double u) {
        if (u <= knots[0]) {
            return values[0];
        }
        var last = knots.length - 1;
        if (u >= knots[last]) {
            return values[last];
        }
        var low = 0;
        var high = last;
        while (high - low > 1) {
            var middle = (low + high) >>> 1;
            if (knots[middle] <= u) {
                low = middle;
            } else {
                high = middle;
            }
        }
        var t = (u - knots[low]) / (knots[high] - knots[low]);
        return values[low] + t * (values[high] - values[low]);
    }

    /**
     * Returns the opcode list that applies the corrections to the image of the active area.
     *
     * @param width the width of the active area, in pixels
     * @param height the height of the active area, in pixels
     * @return the DNG opcode list, or {@code null} when the corrections do nothing
     */
    byte[] opcodes(int width, int height) {
        if (!active() || width <= 0 || height <= 0) {
            return null;
        }
        var cx = centerX / width;
        var cy = centerY / height;
        var corner = StrictMath.hypot(aspect * Math.max(centerX, width - centerX), Math.max(centerY, height - centerY));
        var scale = corner / radius;
        var count = 0;
        var vignetteBody = (byte[]) null;
        if (vignette != null && differs(new double[][]{vignette})) {
            vignetteBody = vignetteParameters(fitVignette(scale), cx, cy);
            count++;
        }
        var warpBody = (byte[]) null;
        if (differs(distortion)) {
            var planes = new double[3][];
            for (var plane = 0; plane < 3; plane++) {
                planes[plane] = fitWarp(distortion[plane], scale);
            }
            warpBody = warpParameters(planes, cx, cy);
            count++;
        }
        var size = 4 + (vignetteBody != null ? 16 + vignetteBody.length : 0) + (warpBody != null ? 16 + warpBody.length : 0);
        var list = ByteBuffer.allocate(size);
        list.putInt(count);
        if (vignetteBody != null) {
            list.putInt(FIX_VIGNETTE_RADIAL).putInt(OPCODE_VERSION).putInt(0).putInt(vignetteBody.length).put(vignetteBody);
        }
        if (warpBody != null) {
            list.putInt(WARP_RECTILINEAR).putInt(OPCODE_VERSION).putInt(0).putInt(warpBody.length).put(warpBody);
        }
        return list.array();
    }

    /**
     * Fits the {@code WarpRectilinear} radial coefficients of one plane: {@code kr0} to {@code kr3} minimizing
     * the squared error of the source radius {@code r * (kr0 + kr1 r^2 + kr2 r^4 + kr3 r^6)} over the image.
     *
     * @param factors the distortion factors at the knots
     * @param scale the farthest corner's distance in units of {@link #radius}
     * @return the four coefficients
     */
    private double[] fitWarp(double[] factors, double scale) {
        var normal = new double[4][4];
        var right = new double[4];
        var basis = new double[4];
        for (var sample = 1; sample <= FIT_SAMPLES; sample++) {
            var r = (double) sample / FIT_SAMPLES;
            var target = r * evaluate(distortionKnots, factors, r * scale);
            var r2 = r * r;
            basis[0] = r;
            basis[1] = r * r2;
            basis[2] = basis[1] * r2;
            basis[3] = basis[2] * r2;
            accumulate(normal, right, basis, target);
        }
        return solve(normal, right);
    }

    /**
     * Fits the {@code FixVignetteRadial} coefficients {@code k0} to {@code k4} of the gain
     * {@code 1 + k0 r^2 + k1 r^4 + k2 r^6 + k3 r^8 + k4 r^10}, minimizing its squared error over the image.
     *
     * @param scale the farthest corner's distance in units of {@link #radius}
     * @return the five coefficients
     */
    private double[] fitVignette(double scale) {
        var normal = new double[5][5];
        var right = new double[5];
        var basis = new double[5];
        for (var sample = 1; sample <= FIT_SAMPLES; sample++) {
            var r = (double) sample / FIT_SAMPLES;
            var target = evaluate(vignetteKnots, vignette, r * scale) - 1;
            var r2 = r * r;
            basis[0] = r2;
            for (var index = 1; index < 5; index++) {
                basis[index] = basis[index - 1] * r2;
            }
            accumulate(normal, right, basis, target);
        }
        return solve(normal, right);
    }

    /**
     * Adds one sample to the normal equations of a least-squares fit.
     *
     * @param normal the matrix, updated
     * @param right the right-hand side, updated
     * @param basis the basis functions at the sample
     * @param target the value at the sample
     */
    private static void accumulate(double[][] normal, double[] right, double[] basis, double target) {
        for (var row = 0; row < basis.length; row++) {
            for (var column = 0; column < basis.length; column++) {
                normal[row][column] += basis[row] * basis[column];
            }
            right[row] += basis[row] * target;
        }
    }

    /**
     * Solves a small symmetric positive definite system by Gaussian elimination with partial pivoting.
     *
     * @param matrix the matrix, destroyed
     * @param right the right-hand side, destroyed
     * @return the solution
     */
    private static double[] solve(double[][] matrix, double[] right) {
        var n = right.length;
        for (var column = 0; column < n; column++) {
            var pivot = column;
            for (var row = column + 1; row < n; row++) {
                if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) {
                    pivot = row;
                }
            }
            var swap = matrix[pivot];
            matrix[pivot] = matrix[column];
            matrix[column] = swap;
            var value = right[pivot];
            right[pivot] = right[column];
            right[column] = value;
            for (var row = column + 1; row < n; row++) {
                var factor = matrix[row][column] / matrix[column][column];
                for (var k = column; k < n; k++) {
                    matrix[row][k] -= factor * matrix[column][k];
                }
                right[row] -= factor * right[column];
            }
        }
        var result = new double[n];
        for (var row = n - 1; row >= 0; row--) {
            var sum = right[row];
            for (var k = row + 1; k < n; k++) {
                sum -= matrix[row][k] * result[k];
            }
            result[row] = sum / matrix[row][row];
        }
        return result;
    }

    /**
     * Encodes the parameters of {@code WarpRectilinear}.
     *
     * @param planes the four radial coefficients of each plane
     * @param cx the optical center's column, normalized to the image width
     * @param cy the optical center's row, normalized to the image height
     * @return the big-endian parameters
     */
    private static byte[] warpParameters(double[][] planes, double cx, double cy) {
        var body = ByteBuffer.allocate(4 + planes.length * 48 + 16);
        body.putInt(planes.length);
        for (var plane : planes) {
            for (var coefficient : plane) {
                body.putDouble(coefficient);
            }
            body.putDouble(0).putDouble(0);
        }
        body.putDouble(cx).putDouble(cy);
        return body.array();
    }

    /**
     * Encodes the parameters of {@code FixVignetteRadial}.
     *
     * @param k the five coefficients
     * @param cx the optical center's column, normalized to the image width
     * @param cy the optical center's row, normalized to the image height
     * @return the big-endian parameters
     */
    private static byte[] vignetteParameters(double[] k, double cx, double cy) {
        var body = ByteBuffer.allocate(56);
        for (var coefficient : k) {
            body.putDouble(coefficient);
        }
        body.putDouble(cx).putDouble(cy);
        return body.array();
    }
}
