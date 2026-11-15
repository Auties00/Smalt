/* Ports libaom 3.13.1 (aom_dsp/noise_model.c: the noise strength solver, the flat block finder and the
   autoregressive noise model; aom_dsp/mathutils.h: linsolve). Copyright (c) 2017, Alliance for Open Media;
   BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.Av1FilmGrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Estimates the film grain of a video from the difference between its pictures and their denoised
 * versions: an autoregressive model of the noise of each plane, fitted on the flat blocks of the
 * pictures, and the noise strength as a function of the sample intensity, accumulated over the
 * frames whose noise looks alike and restarted when it changes.
 *
 * <p>Planes are 16-bit sample arrays of a given bit depth. An instance is used by one thread at a
 * time.
 *
 * @implNote This implementation follows libaom's {@code aom_noise_model_*},
 * {@code aom_noise_strength_solver_*} and {@code aom_flat_block_finder_*}, with a square model of
 * lag 3, and also models 4:2:2 and monochrome pictures.
 */
final class NoiseModel {
    /**
     * The number of intensity bins of a noise strength solver.
     */
    private static final int STRENGTH_BINS = 20;

    /**
     * The number of parameters of the low-order planar model of a block.
     */
    private static final int LOW_POLY_PARAMS = 3;

    /**
     * The magnitude below which a pivot counts as zero ({@code TINY_NEAR_ZERO}).
     */
    private static final double TINY_NEAR_ZERO = 1.0E-16;

    /**
     * The outcome of a model update.
     */
    enum Status {
        /**
         * The frame's noise was added to the model.
         */
        OK,

        /**
         * The frame has too few flat blocks to measure its noise.
         */
        INSUFFICIENT_FLAT_BLOCKS,

        /**
         * The frame's noise differs from the model's, which was left as it was.
         */
        DIFFERENT_NOISE_TYPE,

        /**
         * An equation system could not be solved.
         */
        INTERNAL_ERROR
    }

    /**
     * A linear system {@code A x = b}.
     */
    static final class Equations {
        /**
         * The matrix, row-major.
         */
        final double[] a;

        /**
         * The right-hand side.
         */
        final double[] b;

        /**
         * The solution.
         */
        final double[] x;

        /**
         * The number of unknowns.
         */
        final int n;

        /**
         * Creates a cleared system.
         *
         * @param n the number of unknowns
         */
        Equations(int n) {
            this.n = n;
            this.a = new double[n * n];
            this.b = new double[n];
            this.x = new double[n];
        }

        /**
         * Clears the matrix, the right-hand side and the solution.
         */
        void clear() {
            Arrays.fill(a, 0);
            Arrays.fill(b, 0);
            Arrays.fill(x, 0);
        }

        /**
         * Copies another system of the same size.
         *
         * @param src the system to copy
         */
        void copyFrom(Equations src) {
            System.arraycopy(src.a, 0, a, 0, a.length);
            System.arraycopy(src.b, 0, b, 0, b.length);
            System.arraycopy(src.x, 0, x, 0, x.length);
        }

        /**
         * Adds the matrix and right-hand side of another system of the same size.
         *
         * @param src the system to add
         */
        void add(Equations src) {
            for (var i = 0; i < a.length; i++) {
                a[i] += src.a[i];
            }
            for (var i = 0; i < n; i++) {
                b[i] += src.b[i];
            }
        }

        /**
         * Solves the system into {@link #x}, leaving the matrix and right-hand side unchanged.
         *
         * @return whether the system has a solution
         */
        boolean solve() {
            return solve(a);
        }

        /**
         * Solves the system with another matrix into {@link #x}.
         *
         * @param matrix the matrix, left unchanged
         * @return whether the system has a solution
         */
        boolean solve(double[] matrix) {
            return linsolve(n, matrix.clone(), n, b.clone(), x);
        }
    }

    /**
     * Solves {@code A x = b} by Gaussian elimination with partial pivoting ({@code linsolve}),
     * overwriting {@code a} and {@code b}.
     *
     * @param n the number of unknowns
     * @param a the matrix, row-major with stride {@code stride}
     * @param stride the matrix stride
     * @param b the right-hand side
     * @param x receives the solution
     * @return whether the system has a solution
     */
    static boolean linsolve(int n, double[] a, int stride, double[] b, double[] x) {
        for (var k = 0; k < n - 1; k++) {
            for (var i = n - 1; i > k; i--) {
                if (Math.abs(a[(i - 1) * stride + k]) < Math.abs(a[i * stride + k])) {
                    for (var j = 0; j < n; j++) {
                        var c = a[i * stride + j];
                        a[i * stride + j] = a[(i - 1) * stride + j];
                        a[(i - 1) * stride + j] = c;
                    }
                    var c = b[i];
                    b[i] = b[i - 1];
                    b[i - 1] = c;
                }
            }
            for (var i = k; i < n - 1; i++) {
                if (Math.abs(a[k * stride + k]) < TINY_NEAR_ZERO) {
                    return false;
                }
                var c = a[(i + 1) * stride + k] / a[k * stride + k];
                for (var j = 0; j < n; j++) {
                    a[(i + 1) * stride + j] -= c * a[k * stride + j];
                }
                b[i + 1] -= c * b[k];
            }
        }
        for (var i = n - 1; i >= 0; i--) {
            if (Math.abs(a[i * stride + i]) < TINY_NEAR_ZERO) {
                return false;
            }
            var c = 0.0;
            for (var j = i + 1; j <= n - 1; j++) {
                c += a[i * stride + j] * x[j];
            }
            x[i] = (b[i] - c) / a[i * stride + i];
        }
        return true;
    }

    /**
     * A piecewise linear function of the sample intensity.
     */
    static final class StrengthLut {
        /**
         * The points, as intensity and value pairs of increasing intensity.
         */
        final double[][] points;

        /**
         * The number of points in use.
         */
        int numPoints;

        /**
         * Creates a function of a number of points, all zero.
         *
         * @param numPoints the number of points
         */
        StrengthLut(int numPoints) {
            this.points = new double[numPoints][2];
            this.numPoints = numPoints;
        }

        /**
         * Evaluates the function, constant beyond its first and last points
         * ({@code aom_noise_strength_lut_eval}).
         *
         * @param x the intensity
         * @return the value
         */
        double eval(double x) {
            if (x < points[0][0]) {
                return points[0][1];
            }
            for (var i = 0; i < numPoints - 1; i++) {
                if (x >= points[i][0] && x <= points[i + 1][0]) {
                    var a = (x - points[i][0]) / (points[i + 1][0] - points[i][0]);
                    return points[i + 1][1] * a + points[i][1] * (1.0 - a);
                }
            }
            return points[numPoints - 1][1];
        }
    }

    /**
     * Fits the noise strength as a piecewise linear function of the block intensity, from block
     * measurements, with a smoothness prior ({@code aom_noise_strength_solver_t}).
     */
    static final class StrengthSolver {
        /**
         * The system of the bin values.
         */
        final Equations eqns = new Equations(STRENGTH_BINS);

        /**
         * The smallest intensity.
         */
        final double minIntensity;

        /**
         * The largest intensity.
         */
        final double maxIntensity;

        /**
         * The number of measurements.
         */
        int numEquations;

        /**
         * The sum of the measured strengths.
         */
        double total;

        /**
         * Creates a solver of a bit depth's intensities.
         *
         * @param bitDepth the bit depth
         */
        StrengthSolver(int bitDepth) {
            this.minIntensity = 0;
            this.maxIntensity = (1 << bitDepth) - 1;
        }

        /**
         * Forgets every measurement.
         */
        void clear() {
            eqns.clear();
            numEquations = 0;
            total = 0;
        }

        /**
         * Adds the measurements of another solver.
         *
         * @param src the other solver
         */
        void add(StrengthSolver src) {
            eqns.add(src.eqns);
            numEquations += src.numEquations;
            total += src.total;
        }

        /**
         * Returns the fractional bin of an intensity.
         *
         * @param value the intensity
         * @return the bin, from 0 to the number of bins minus one
         */
        double binIndex(double value) {
            var val = Math.clamp(value, minIntensity, maxIntensity);
            var range = maxIntensity - minIntensity;
            return (STRENGTH_BINS - 1) * (val - minIntensity) / range;
        }

        /**
         * Returns the solved strength at an intensity, interpolating between the bins.
         *
         * @param x the intensity
         * @return the strength
         */
        double value(double x) {
            var bin = binIndex(x);
            var i0 = (int) Math.floor(bin);
            var i1 = Math.min(STRENGTH_BINS - 1, i0 + 1);
            var a = bin - i0;
            return (1.0 - a) * eqns.x[i0] + a * eqns.x[i1];
        }

        /**
         * Returns the intensity at the center of a bin.
         *
         * @param i the bin
         * @return the intensity
         */
        double center(int i) {
            var range = maxIntensity - minIntensity;
            return (double) i / (STRENGTH_BINS - 1) * range + minIntensity;
        }

        /**
         * Adds a measurement of a block ({@code aom_noise_strength_solver_add_measurement}).
         *
         * @param blockMean the block's mean intensity
         * @param noiseStd the block's noise standard deviation
         */
        void addMeasurement(double blockMean, double noiseStd) {
            var bin = binIndex(blockMean);
            var i0 = (int) Math.floor(bin);
            var i1 = Math.min(STRENGTH_BINS - 1, i0 + 1);
            var a = bin - i0;
            var n = STRENGTH_BINS;
            eqns.a[i0 * n + i0] += (1.0 - a) * (1.0 - a);
            eqns.a[i1 * n + i0] += a * (1.0 - a);
            eqns.a[i1 * n + i1] += a * a;
            eqns.a[i0 * n + i1] += a * (1.0 - a);
            eqns.b[i0] += (1.0 - a) * noiseStd;
            eqns.b[i1] += a * noiseStd;
            total += noiseStd;
            numEquations++;
        }

        /**
         * Solves the bin values with a smoothness regularization proportional to the number of
         * measurements and a small pull toward the mean strength ({@code aom_noise_strength_solver_solve}).
         *
         * @return whether the system was solved
         */
        boolean solve() {
            var n = STRENGTH_BINS;
            var alpha = 2.0 * numEquations / n;
            var a = eqns.a.clone();
            for (var i = 0; i < n; i++) {
                var lo = Math.max(0, i - 1);
                var hi = Math.min(n - 1, i + 1);
                a[i * n + lo] -= alpha;
                a[i * n + i] += 2 * alpha;
                a[i * n + hi] -= alpha;
            }
            var mean = total / numEquations;
            for (var i = 0; i < n; i++) {
                a[i * n + i] += 1.0 / 8192.0;
                eqns.b[i] += mean / 8192.0;
            }
            return eqns.solve(a);
        }

        /**
         * Computes the error of removing each interior point of a function fitted to the solution:
         * the area between the solution and the segment joining the point's neighbors
         * ({@code update_piecewise_linear_residual}).
         *
         * @param lut the function
         * @param residual receives the error of each point
         * @param start the first point to update
         * @param end the point after the last to update
         */
        private void updateResidual(StrengthLut lut, double[] residual, int start, int end) {
            var dx = 255.0 / STRENGTH_BINS;
            for (var i = Math.max(start, 1); i < Math.min(end, lut.numPoints - 1); i++) {
                var lower = Math.max(0, (int) Math.floor(binIndex(lut.points[i - 1][0])));
                var upper = Math.min(STRENGTH_BINS - 1, (int) Math.ceil(binIndex(lut.points[i + 1][0])));
                var r = 0.0;
                for (var j = lower; j <= upper; j++) {
                    var x = center(j);
                    if (x < lut.points[i - 1][0] || x >= lut.points[i + 1][0]) {
                        continue;
                    }
                    var y = eqns.x[j];
                    var a = (x - lut.points[i - 1][0]) / (lut.points[i + 1][0] - lut.points[i - 1][0]);
                    var estimate = lut.points[i - 1][1] * (1.0 - a) + lut.points[i + 1][1] * a;
                    r += Math.abs(y - estimate);
                }
                residual[i] = r * dx;
            }
        }

        /**
         * Fits the solution with a piecewise linear function of at most a number of points,
         * removing interior points greedily while they are too many or cost little
         * ({@code aom_noise_strength_solver_fit_piecewise}).
         *
         * @param maxPoints the most points
         * @return the function
         */
        StrengthLut fitPiecewise(int maxPoints) {
            var tolerance = maxIntensity * 0.00625 / 255.0;
            var lut = new StrengthLut(STRENGTH_BINS);
            for (var i = 0; i < STRENGTH_BINS; i++) {
                lut.points[i][0] = center(i);
                lut.points[i][1] = eqns.x[i];
            }
            var residual = new double[STRENGTH_BINS];
            updateResidual(lut, residual, 0, STRENGTH_BINS);
            while (lut.numPoints > 2) {
                var minIndex = 1;
                for (var j = 1; j < lut.numPoints - 1; j++) {
                    if (residual[j] < residual[minIndex]) {
                        minIndex = j;
                    }
                }
                var dx = lut.points[minIndex + 1][0] - lut.points[minIndex - 1][0];
                var avgResidual = residual[minIndex] / dx;
                if (lut.numPoints <= maxPoints && avgResidual > tolerance) {
                    break;
                }
                var remaining = lut.numPoints - minIndex - 1;
                for (var k = 0; k < remaining; k++) {
                    lut.points[minIndex + k][0] = lut.points[minIndex + k + 1][0];
                    lut.points[minIndex + k][1] = lut.points[minIndex + k + 1][1];
                }
                System.arraycopy(residual, minIndex + 1, residual, minIndex, remaining);
                lut.numPoints--;
                updateResidual(lut, residual, minIndex - 1, minIndex + 1);
            }
            return lut;
        }
    }

    /**
     * Finds the flat blocks of a plane, whose content is mostly noise, from the gradients of
     * their difference to a planar fit ({@code aom_flat_block_finder_t}).
     */
    static final class FlatBlockFinder {
        /**
         * The inverse of {@code A^T A} of the planar model.
         */
        private final double[] atAInv = new double[LOW_POLY_PARAMS * LOW_POLY_PARAMS];

        /**
         * The design matrix of the planar model: each sample's row, column and constant term.
         */
        private final double[] a;

        /**
         * The block size.
         */
        final int blockWidth;

        /**
         * The block height.
         */
        final int blockHeight;

        /**
         * The factor normalizing samples to [0, 1].
         */
        private final double normalization;

        /**
         * Creates a finder of a block size and bit depth ({@code aom_flat_block_finder_init}).
         *
         * @param blockWidth the block width
         * @param blockHeight the block height
         * @param bitDepth the bit depth
         */
        FlatBlockFinder(int blockWidth, int blockHeight, int bitDepth) {
            this.blockWidth = blockWidth;
            this.blockHeight = blockHeight;
            this.normalization = (1 << bitDepth) - 1;
            var n = blockWidth * blockHeight;
            this.a = new double[LOW_POLY_PARAMS * n];
            var eqns = new Equations(LOW_POLY_PARAMS);
            for (var y = 0; y < blockHeight; y++) {
                var yd = (y - blockHeight / 2.0) / (blockHeight / 2.0);
                for (var x = 0; x < blockWidth; x++) {
                    var xd = (x - blockWidth / 2.0) / (blockWidth / 2.0);
                    var row = y * blockWidth + x;
                    a[LOW_POLY_PARAMS * row] = yd;
                    a[LOW_POLY_PARAMS * row + 1] = xd;
                    a[LOW_POLY_PARAMS * row + 2] = 1;
                    double[] coords = {yd, xd, 1};
                    for (var i = 0; i < LOW_POLY_PARAMS; i++) {
                        for (var j = 0; j < LOW_POLY_PARAMS; j++) {
                            eqns.a[LOW_POLY_PARAMS * i + j] += coords[i] * coords[j];
                        }
                    }
                }
            }
            for (var i = 0; i < LOW_POLY_PARAMS; i++) {
                Arrays.fill(eqns.b, 0);
                eqns.b[i] = 1;
                eqns.solve();
                for (var j = 0; j < LOW_POLY_PARAMS; j++) {
                    atAInv[j * LOW_POLY_PARAMS + i] = eqns.x[j];
                }
            }
        }

        /**
         * Extracts a block, normalized to [0, 1] with the plane's edges replicated, and splits it
         * into its planar fit and the residual ({@code aom_flat_block_finder_extract_block}).
         *
         * @param data the plane
         * @param w the plane width
         * @param h the plane height
         * @param stride the plane stride
         * @param offX the block column
         * @param offY the block row
         * @param plane receives the planar fit
         * @param block receives the residual
         */
        void extractBlock(short[] data, int w, int h, int stride, int offX, int offY, double[] plane,
                          double[] block) {
            var n = blockWidth * blockHeight;
            for (var yi = 0; yi < blockHeight; yi++) {
                var y = Math.clamp(offY + yi, 0, h - 1);
                for (var xi = 0; xi < blockWidth; xi++) {
                    var x = Math.clamp(offX + xi, 0, w - 1);
                    block[yi * blockWidth + xi] = data[y * stride + x] / normalization;
                }
            }
            var atb = new double[LOW_POLY_PARAMS];
            for (var c = 0; c < LOW_POLY_PARAMS; c++) {
                var sum = 0.0;
                for (var i = 0; i < n; i++) {
                    sum += block[i] * a[i * LOW_POLY_PARAMS + c];
                }
                atb[c] = sum;
            }
            var coords = new double[LOW_POLY_PARAMS];
            for (var r = 0; r < LOW_POLY_PARAMS; r++) {
                var sum = 0.0;
                for (var c = 0; c < LOW_POLY_PARAMS; c++) {
                    sum += atAInv[r * LOW_POLY_PARAMS + c] * atb[c];
                }
                coords[r] = sum;
            }
            for (var i = 0; i < n; i++) {
                var sum = 0.0;
                for (var c = 0; c < LOW_POLY_PARAMS; c++) {
                    sum += a[i * LOW_POLY_PARAMS + c] * coords[c];
                }
                plane[i] = sum;
                block[i] -= sum;
            }
        }

        /**
         * Marks the flat blocks of a plane of square blocks ({@code aom_flat_block_finder_run}):
         * those whose gradients are small and isotropic and whose variance is not zero, together
         * with the tenth of the blocks a logistic score finds flattest.
         *
         * @param data the plane
         * @param w the plane width
         * @param h the plane height
         * @param stride the plane stride
         * @param flat receives 255 for a flat block, 1 for a top-scored one, 0 otherwise, row-major
         * @return the number of marked blocks
         */
        int run(short[] data, int w, int h, int stride, byte[] flat) {
            var bs = blockWidth;
            var n = bs * bs;
            var traceThreshold = 0.15 / (32 * 32);
            var ratioThreshold = 1.25;
            var normThreshold = 0.08 / (32 * 32);
            var varThreshold = 0.005 / n;
            var blocksW = (w + bs - 1) / bs;
            var blocksH = (h + bs - 1) / bs;
            var numFlat = 0;
            var plane = new double[n];
            var block = new double[n];
            var scores = new float[blocksW * blocksH];
            for (var by = 0; by < blocksH; by++) {
                for (var bx = 0; bx < blocksW; bx++) {
                    extractBlock(data, w, h, stride, bx * bs, by * bs, plane, block);
                    double gxx = 0;
                    double gxy = 0;
                    double gyy = 0;
                    double mean = 0;
                    double var = 0;
                    for (var yi = 1; yi < bs - 1; yi++) {
                        for (var xi = 1; xi < bs - 1; xi++) {
                            var gx = (block[yi * bs + xi + 1] - block[yi * bs + xi - 1]) / 2;
                            var gy = (block[yi * bs + xi + bs] - block[yi * bs + xi - bs]) / 2;
                            gxx += gx * gx;
                            gxy += gx * gy;
                            gyy += gy * gy;
                            var value = block[yi * bs + xi];
                            mean += value;
                            var += value * value;
                        }
                    }
                    var count = (double) (bs - 2) * (bs - 2);
                    mean /= count;
                    gxx /= count;
                    gxy /= count;
                    gyy /= count;
                    var /= count;
                    var -= mean * mean;
                    var trace = gxx + gyy;
                    var det = gxx * gyy - gxy * gxy;
                    var e1 = (trace + Math.sqrt(trace * trace - 4 * det)) / 2.0;
                    var e2 = (trace - Math.sqrt(trace * trace - 4 * det)) / 2.0;
                    var norm = e1;
                    var ratio = e1 / Math.max(e2, 1e-6);
                    var isFlat = trace < traceThreshold && ratio < ratioThreshold && norm < normThreshold
                            && var > varThreshold;
                    var sum = -6682 * var - 0.2056 * ratio + 13087 * trace - 12434 * norm + 2.5694;
                    sum = Math.clamp(sum, -25.0, 100.0);
                    var score = (float) (1.0 / (1 + StrictMath.exp(-sum)));
                    flat[by * blocksW + bx] = (byte) (isFlat ? 255 : 0);
                    scores[by * blocksW + bx] = var > varThreshold ? score : 0;
                    numFlat += isFlat ? 1 : 0;
                }
            }
            var sorted = scores.clone();
            Arrays.sort(sorted);
            var threshold = sorted[blocksW * blocksH * 90 / 100];
            for (var i = 0; i < scores.length; i++) {
                if (scores[i] >= threshold) {
                    numFlat += flat[i] == 0 ? 1 : 0;
                    flat[i] |= 1;
                }
            }
            return numFlat;
        }
    }

    /**
     * The estimate of one plane: the autoregressive system and the strength solver.
     */
    private static final class State {
        /**
         * The autoregressive coefficients' system.
         */
        final Equations eqns;

        /**
         * The noise strength solver.
         */
        final StrengthSolver solver;

        /**
         * The number of observations in the system.
         */
        int numObservations;

        /**
         * The gain of the autoregressive filter.
         */
        double arGain = 1.0;

        /**
         * Creates an empty estimate.
         *
         * @param n the number of coefficients
         * @param bitDepth the bit depth
         */
        State(int n, int bitDepth) {
            this.eqns = new Equations(n);
            this.solver = new StrengthSolver(bitDepth);
        }
    }

    /**
     * The lag of the autoregressive model.
     */
    private final int lag;

    /**
     * The number of autoregressive coefficients of luma.
     */
    private final int n;

    /**
     * The bit depth.
     */
    private final int bitDepth;

    /**
     * The column and row offset of each coefficient's sample.
     */
    private final int[][] coords;

    /**
     * The estimate over the frames of the current noise.
     */
    private final State[] combined = new State[3];

    /**
     * The estimate of the last frame.
     */
    private final State[] latest = new State[3];

    /**
     * Creates a model of square neighborhoods of a lag ({@code aom_noise_model_init}).
     *
     * @param lag the lag, from 1 to 3
     * @param bitDepth the bit depth of the planes
     */
    NoiseModel(int lag, int bitDepth) {
        this.lag = lag;
        this.bitDepth = bitDepth;
        var side = 2 * lag + 1;
        this.n = side * side / 2;
        this.coords = new int[n][2];
        var i = 0;
        for (var y = -lag; y <= 0; y++) {
            var maxX = y == 0 ? -1 : lag;
            for (var x = -lag; x <= maxX; x++) {
                coords[i][0] = x;
                coords[i][1] = y;
                i++;
            }
        }
        for (var c = 0; c < 3; c++) {
            combined[c] = new State(n + (c > 0 ? 1 : 0), bitDepth);
            latest[c] = new State(n + (c > 0 ? 1 : 0), bitDepth);
        }
    }

    /**
     * Returns whether the model holds an estimate.
     *
     * @return {@code true} once a frame was added
     */
    boolean hasEstimate() {
        return combined[0].solver.numEquations > 0;
    }

    /**
     * Returns the mean of a block, clipped to the plane.
     *
     * @param data the plane
     * @param w the plane width
     * @param h the plane height
     * @param stride the plane stride
     * @param xo the block column
     * @param yo the block row
     * @param blockSize the block size
     * @return the mean
     */
    private static double blockMean(short[] data, int w, int h, int stride, int xo, int yo, int blockSize) {
        var maxH = Math.min(h - yo, blockSize);
        var maxW = Math.min(w - xo, blockSize);
        var sum = 0.0;
        for (var y = 0; y < maxH; y++) {
            for (var x = 0; x < maxW; x++) {
                sum += data[(yo + y) * stride + xo + x];
            }
        }
        return sum / (maxW * maxH);
    }

    /**
     * Returns the variance of the noise of a block, the difference of the plane and its denoised
     * version, clipped to the plane.
     *
     * @param data the plane
     * @param denoised the denoised plane
     * @param stride the stride of both
     * @param w the plane width
     * @param h the plane height
     * @param xo the block column
     * @param yo the block row
     * @param blockW the block width
     * @param blockH the block height
     * @return the variance
     */
    private static double noiseVariance(short[] data, short[] denoised, int stride, int w, int h, int xo, int yo,
                                        int blockW, int blockH) {
        var maxH = Math.min(h - yo, blockH);
        var maxW = Math.min(w - xo, blockW);
        var var = 0.0;
        var mean = 0.0;
        for (var y = 0; y < maxH; y++) {
            for (var x = 0; x < maxW; x++) {
                var i = (yo + y) * stride + xo + x;
                double noise = data[i] - denoised[i];
                mean += noise;
                var += noise * noise;
            }
        }
        mean /= maxW * maxH;
        return var / (maxW * maxH) - mean * mean;
    }

    /**
     * Extracts the noise of the neighborhood of a sample and, for chroma, the mean luma noise it
     * covers ({@code extract_ar_row}).
     *
     * @param data the plane
     * @param denoised the denoised plane
     * @param stride the stride of both
     * @param subX the horizontal chroma subsampling
     * @param subY the vertical chroma subsampling
     * @param alt the luma plane of a chroma plane, or {@code null}
     * @param altDenoised the denoised luma plane, or {@code null}
     * @param altStride the luma stride
     * @param x the sample column
     * @param y the sample row
     * @param buffer receives the neighborhood's noise, then the luma noise
     * @return the sample's noise
     */
    private double extractArRow(short[] data, short[] denoised, int stride, int subX, int subY, short[] alt,
                                short[] altDenoised, int altStride, int x, int y, double[] buffer) {
        for (var i = 0; i < n; i++) {
            var p = (y + coords[i][1]) * stride + x + coords[i][0];
            buffer[i] = data[p] - denoised[p];
        }
        double val = data[y * stride + x] - denoised[y * stride + x];
        if (alt != null) {
            var avgData = 0.0;
            var avgDenoised = 0.0;
            var samples = 0;
            for (var dy = 0; dy < (1 << subY); dy++) {
                var yUp = (y << subY) + dy;
                for (var dx = 0; dx < (1 << subX); dx++) {
                    var xUp = (x << subX) + dx;
                    avgData += alt[yUp * altStride + xUp];
                    avgDenoised += altDenoised[yUp * altStride + xUp];
                    samples++;
                }
            }
            buffer[n] = (avgData - avgDenoised) / samples;
        }
        return val;
    }

    /**
     * Adds the noise of the flat blocks of a plane to the latest autoregressive system
     * ({@code add_block_observations}).
     */
    private void addBlockObservations(int c, short[] data, short[] denoised, int w, int h, int stride, int subX,
                                      int subY, short[] alt, short[] altDenoised, int altStride, byte[] flat,
                                      int blockSize, int blocksW, int blocksH) {
        var normalization = (double) ((1 << bitDepth) - 1);
        var norm2 = normalization * normalization;
        var state = latest[c];
        var a = state.eqns.a;
        var b = state.eqns.b;
        var size = state.eqns.n;
        var buffer = new double[n + 1];
        var bw = blockSize >> subX;
        var bh = blockSize >> subY;
        for (var by = 0; by < blocksH; by++) {
            var yo = by * bh;
            for (var bx = 0; bx < blocksW; bx++) {
                var xo = bx * bw;
                if (flat[by * blocksW + bx] == 0) {
                    continue;
                }
                var yStart = by > 0 && flat[(by - 1) * blocksW + bx] != 0 ? 0 : lag;
                var xStart = bx > 0 && flat[by * blocksW + bx - 1] != 0 ? 0 : lag;
                var yEnd = Math.min((h >> subY) - by * bh, bh);
                var xEnd = Math.min((w >> subX) - bx * bw - lag,
                        bx + 1 < blocksW && flat[by * blocksW + bx + 1] != 0 ? bw : bw - lag);
                for (var y = yStart; y < yEnd; y++) {
                    for (var x = xStart; x < xEnd; x++) {
                        var val = extractArRow(data, denoised, stride, subX, subY, alt, altDenoised, altStride, x + xo,
                                y + yo, buffer);
                        for (var i = 0; i < size; i++) {
                            for (var j = 0; j < size; j++) {
                                a[i * size + j] += buffer[i] * buffer[j] / norm2;
                            }
                            b[i] += buffer[i] * val / norm2;
                        }
                        state.numObservations++;
                    }
                }
            }
        }
    }

    /**
     * Adds the noise strength of the flat blocks of a plane to the latest strength solver, with
     * the part correlated with luma and the gain of the autoregressive filter removed
     * ({@code add_noise_std_observations}).
     */
    private void addNoiseStdObservations(int c, double[] coeffs, short[] data, short[] denoised, int w, int h,
                                         int stride, int subX, int subY, short[] alt, int altStride, byte[] flat,
                                         int blockSize, int blocksW, int blocksH) {
        var solver = latest[c].solver;
        var luma = latest[0].solver;
        var lumaGain = latest[0].arGain;
        var noiseGain = latest[c].arGain;
        var bw = blockSize >> subX;
        var bh = blockSize >> subY;
        for (var by = 0; by < blocksH; by++) {
            var yo = by * bh;
            for (var bx = 0; bx < blocksW; bx++) {
                var xo = bx * bw;
                if (flat[by * blocksW + bx] == 0) {
                    continue;
                }
                var samplesH = Math.min((h >> subY) - by * bh, bh);
                var samplesW = Math.min((w >> subX) - bx * bw, bw);
                if (samplesW * samplesH <= blockSize) {
                    continue;
                }
                var mean = blockMean(alt != null ? alt : data, w, h, alt != null ? altStride : stride, xo << subX,
                        yo << subY, blockSize);
                var noiseVar = noiseVariance(data, denoised, stride, w >> subX, h >> subY, xo, yo, bw, bh);
                var lumaStrength = c > 0 ? lumaGain * luma.value(mean) : 0;
                var corr = c > 0 ? coeffs[n] : 0;
                var uncorrStd = Math.sqrt(Math.max(noiseVar / 16, noiseVar - (corr * lumaStrength) * (corr * lumaStrength)));
                solver.addMeasurement(mean, uncorrStd / noiseGain);
            }
        }
    }

    /**
     * Returns whether the latest luma estimate differs from the combined one: its coefficients
     * correlate poorly or its strength curve moved ({@code is_noise_model_different}).
     *
     * @return {@code true} when the noise changed
     */
    private boolean different() {
        var coeffThreshold = 0.9;
        var strengthThreshold = 0.005 * (1 << (bitDepth - 8));
        var corr = crossCorrelation(latest[0].eqns.x, combined[0].eqns.x, combined[0].eqns.n);
        if (corr < coeffThreshold) {
            return true;
        }
        var dx = 1.0 / STRENGTH_BINS;
        var latestEqns = latest[0].solver.eqns;
        var combinedEqns = combined[0].solver.eqns;
        var diff = 0.0;
        var totalWeight = 0.0;
        for (var j = 0; j < latestEqns.n; j++) {
            var weight = 0.0;
            for (var i = 0; i < latestEqns.n; i++) {
                weight += latestEqns.a[i * latestEqns.n + j];
            }
            weight = Math.sqrt(weight);
            diff += weight * Math.abs(latestEqns.x[j] - combinedEqns.x[j]);
            totalWeight += weight;
        }
        return diff * dx / totalWeight > strengthThreshold;
    }

    /**
     * Returns the normalized cross correlation of two vectors ({@code aom_normalized_cross_correlation}).
     *
     * @param a one vector
     * @param b the other vector
     * @param n the length
     * @return the correlation
     */
    private static double crossCorrelation(double[] a, double[] b, int n) {
        var c = 0.0;
        var aLen = 0.0;
        var bLen = 0.0;
        for (var i = 0; i < n; i++) {
            aLen += a[i] * a[i];
            bLen += b[i] * b[i];
            c += a[i] * b[i];
        }
        return c / (Math.sqrt(aLen) * Math.sqrt(bLen));
    }

    /**
     * Solves an autoregressive system and derives the filter's gain from it
     * ({@code ar_equation_system_solve}).
     *
     * @param state the estimate
     * @param chroma whether the plane is chroma, whose last coefficient weights luma
     * @return whether the system was solved
     */
    private static boolean solveAr(State state, boolean chroma) {
        var ok = state.eqns.solve();
        state.arGain = 1.0;
        if (!ok) {
            return false;
        }
        var size = state.eqns.n;
        var last = chroma ? 1 : 0;
        var var = 0.0;
        for (var i = 0; i < size - last; i++) {
            var += state.eqns.a[i * size + i] / state.numObservations;
        }
        var /= size - last;
        var sumCovar = 0.0;
        for (var i = 0; i < size - last; i++) {
            var bi = state.eqns.b[i];
            if (chroma) {
                bi -= state.eqns.a[i * size + (size - 1)] * state.eqns.x[size - 1];
            }
            sumCovar += bi * state.eqns.x[i] / state.numObservations;
        }
        var noiseVar = Math.max(var - sumCovar, 1e-6);
        state.arGain = Math.max(1, Math.sqrt(Math.max(var / noiseVar, 1e-6)));
        return true;
    }

    /**
     * Keeps only the luma correlation of a chroma system that could not be solved
     * ({@code set_chroma_coefficient_fallback_soln}).
     *
     * @param eqns the system
     */
    private static void chromaFallback(Equations eqns) {
        var last = eqns.n - 1;
        Arrays.fill(eqns.x, 0);
        if (Math.abs(eqns.a[last * eqns.n + last]) > 1e-6) {
            eqns.x[last] = eqns.b[last] / eqns.a[last * eqns.n + last];
        }
    }

    /**
     * Adds a frame to the model ({@code aom_noise_model_update}).
     *
     * @param data the planes, luma first; {@code null} chroma planes for monochrome
     * @param denoised the denoised planes
     * @param w the luma width
     * @param h the luma height
     * @param strides the stride of each plane
     * @param subX the horizontal chroma subsampling
     * @param subY the vertical chroma subsampling
     * @param flat the flat block flags of the luma blocks
     * @param blockSize the luma block size, at least {@code 2 * lag + 1}
     * @return the outcome
     */
    Status update(short[][] data, short[][] denoised, int w, int h, int[] strides, int subX, int subY, byte[] flat,
                  int blockSize) {
        var blocksW = (w + blockSize - 1) / blockSize;
        var blocksH = (h + blockSize - 1) / blockSize;
        for (var c = 0; c < 3; c++) {
            latest[c].eqns.clear();
            latest[c].numObservations = 0;
            latest[c].solver.clear();
        }
        var blocks = 0;
        for (var i = 0; i < blocksW * blocksH; i++) {
            blocks += flat[i] != 0 ? 1 : 0;
        }
        if (blocks <= 1) {
            return Status.INSUFFICIENT_FLAT_BLOCKS;
        }
        var yDifferent = false;
        for (var c = 0; c < 3; c++) {
            if (data[c] == null || denoised[c] == null) {
                break;
            }
            var chroma = c != 0;
            var sx = chroma ? subX : 0;
            var sy = chroma ? subY : 0;
            var alt = chroma ? data[0] : null;
            var altDenoised = chroma ? denoised[0] : null;
            addBlockObservations(c, data[c], denoised[c], w, h, strides[c], sx, sy, alt, altDenoised, strides[0], flat,
                    blockSize, blocksW, blocksH);
            if (!solveAr(latest[c], chroma)) {
                if (!chroma) {
                    return Status.INTERNAL_ERROR;
                }
                chromaFallback(latest[c].eqns);
            }
            addNoiseStdObservations(c, latest[c].eqns.x, data[c], denoised[c], w, h, strides[c], sx, sy, alt,
                    strides[0], flat, blockSize, blocksW, blocksH);
            if (!latest[c].solver.solve()) {
                return Status.INTERNAL_ERROR;
            }
            if (c == 0 && combined[0].solver.numEquations > 0 && different()) {
                yDifferent = true;
            }
            if (yDifferent) {
                continue;
            }
            combined[c].numObservations += latest[c].numObservations;
            combined[c].eqns.add(latest[c].eqns);
            if (!solveAr(combined[c], chroma)) {
                if (!chroma) {
                    return Status.INTERNAL_ERROR;
                }
                chromaFallback(combined[c].eqns);
            }
            combined[c].solver.add(latest[c].solver);
            if (!combined[c].solver.solve()) {
                return Status.INTERNAL_ERROR;
            }
        }
        return yDifferent ? Status.DIFFERENT_NOISE_TYPE : Status.OK;
    }

    /**
     * Replaces the combined estimate with the latest frame's ({@code aom_noise_model_save_latest}).
     */
    void saveLatest() {
        for (var c = 0; c < 3; c++) {
            combined[c].eqns.copyFrom(latest[c].eqns);
            combined[c].solver.eqns.copyFrom(latest[c].solver.eqns);
            combined[c].solver.numEquations = latest[c].solver.numEquations;
            combined[c].numObservations = latest[c].numObservations;
            combined[c].arGain = latest[c].arGain;
        }
    }

    /**
     * Rounds half away from zero, as C's {@code round}.
     *
     * @param v the value
     * @return the rounded value
     */
    private static int round(double v) {
        return (int) (v < 0 ? -Math.floor(-v + 0.5) : Math.floor(v + 0.5));
    }

    /**
     * Converts the combined estimate to film grain parameters ({@code aom_noise_model_get_grain_parameters}):
     * scaling functions of at most 14 luma and 10 chroma points at the 8-bit scale, the
     * coefficients at the coarsest precision that holds the largest, and the chroma functions
     * indexed by luma.
     *
     * @param seed the random seed
     * @param chroma whether the chroma planes get grain
     * @return the parameters
     */
    Av1FilmGrain grainParameters(int seed, boolean chroma) {
        var luts = new StrengthLut[3];
        luts[0] = combined[0].solver.fitPiecewise(14);
        luts[1] = combined[1].solver.fitPiecewise(10);
        luts[2] = combined[2].solver.fitPiecewise(10);
        var divisor = (double) (1 << (bitDepth - 8));
        var maxScaling = 1e-4;
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < luts[c].numPoints; i++) {
                luts[c].points[i][0] = Math.min(255, luts[c].points[i][0] / divisor);
                luts[c].points[i][1] = Math.min(255, luts[c].points[i][1] / divisor);
                if (c == 0 || chroma) {
                    maxScaling = Math.max(luts[c].points[i][1], maxScaling);
                }
            }
        }
        var maxLog2 = Math.clamp(Math.getExponent(maxScaling) + 1, 2, 5);
        var scalingShift = 5 + (8 - maxLog2);
        var scale = (double) (1 << (8 - maxLog2));
        var points = new ArrayList<List<Av1FilmGrain.ScalingPoint>>(3);
        for (var c = 0; c < 3; c++) {
            var list = new ArrayList<Av1FilmGrain.ScalingPoint>();
            for (var i = 0; i < luts[c].numPoints; i++) {
                var value = (int) (luts[c].points[i][0] + 0.5);
                var scaling = Math.clamp((int) (scale * luts[c].points[i][1] + 0.5), 0, 255);
                if (!list.isEmpty() && list.getLast().value() >= value) {
                    continue;
                }
                list.add(new Av1FilmGrain.ScalingPoint(value, scaling));
            }
            points.add(list);
        }
        var maxCoeff = 1e-4;
        var minCoeff = -1e-4;
        var yCorr = new double[2];
        var avgLumaStrength = 0.0;
        for (var c = 0; c < 3; c++) {
            if (c > 0 && !chroma) {
                break;
            }
            var eqns = combined[c].eqns;
            for (var i = 0; i < n; i++) {
                maxCoeff = Math.max(maxCoeff, eqns.x[i]);
                minCoeff = Math.min(minCoeff, eqns.x[i]);
            }
            var solver = combined[c].solver;
            var average = 0.0;
            var totalWeight = 0.0;
            for (var i = 0; i < solver.eqns.n; i++) {
                var weight = 0.0;
                for (var j = 0; j < solver.eqns.n; j++) {
                    weight += solver.eqns.a[i * solver.eqns.n + j];
                }
                weight = Math.sqrt(weight);
                average += solver.eqns.x[i] * weight;
                totalWeight += weight;
            }
            average = totalWeight == 0 ? 1 : average / totalWeight;
            if (c == 0) {
                avgLumaStrength = average;
            } else {
                yCorr[c - 1] = avgLumaStrength * eqns.x[n] / average;
                maxCoeff = Math.max(maxCoeff, yCorr[c - 1]);
                minCoeff = Math.min(minCoeff, yCorr[c - 1]);
            }
        }
        var maxLog = 1 + Math.getExponent(maxCoeff);
        var neg = -minCoeff;
        var minLog = Math.getExponent(neg) + (neg == Math.scalb(1.0, Math.getExponent(neg)) ? 0 : 1);
        var arShift = Math.clamp(7 - Math.max(maxLog, minLog), 6, 9);
        var arScale = (double) (1 << arShift);
        var coefficients = new int[3][];
        for (var c = 0; c < 3; c++) {
            var out = new int[c == 0 ? n : n + 1];
            if (c == 0 || chroma) {
                var eqns = combined[c].eqns;
                for (var i = 0; i < n; i++) {
                    out[i] = Math.clamp(round(arScale * eqns.x[i]), -128, 127);
                }
                if (c > 0) {
                    out[n] = Math.clamp(round(arScale * yCorr[c - 1]), -128, 127);
                }
            }
            coefficients[c] = out;
        }
        List<Av1FilmGrain.ScalingPoint> cbPoints = chroma ? points.get(1) : List.of();
        List<Av1FilmGrain.ScalingPoint> crPoints = chroma ? points.get(2) : List.of();
        return Av1FilmGrain.builder().randomSeed(seed).lumaPoints(points.get(0)).chromaPoints(cbPoints, crPoints)
                .scalingShift(scalingShift).arCoefficients(lag, arShift, coefficients[0], coefficients[1],
                        coefficients[2]).cbCombination(128, 192, 256).crCombination(128, 192, 256).overlap(true)
                .build();
    }
}
