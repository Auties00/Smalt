package com.github.auties00.smalt.testkit;

import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the IEEE Std 1180-1990 accuracy test of an 8x8 inverse discrete cosine transform, the accuracy that ITU-T H.261,
 * H.263 (Annex A), ISO/IEC 11172-2, 13818-2 and 14496-2 (Annex A) require of a decoder's inverse DCT.
 *
 * <p>Each run draws 10000 blocks of integer samples, uniformly from {@code -L} to {@code H} with the standard's
 * linear congruential generator (seeded with 1 at the start of the run), optionally negates them, transforms them
 * with a double precision forward DCT, rounds the coefficients to integers and clips them to -2048..2047, and
 * compares the transform under test with the double precision inverse DCT rounded to integers, both clipped to
 * -256..255. A transform complies when, over each of the six runs ({@code L = 256, H = 255}, {@code L = H = 5} and
 * {@code L = H = 300}, each with and without the sign change):
 * <ul>
 *   <li>no sample differs by more than 1 (peak error);</li>
 *   <li>the mean squared error of every sample position is at most 0.06, and of all samples at most 0.02;</li>
 *   <li>the mean error of every sample position is at most 0.015 in magnitude, and of all samples at most
 *       0.0015;</li>
 * </ul>
 * and an all-zero block transforms to an all-zero block.
 */
public final class Ieee1180 {
    /** The blocks of one run. */
    public static final int BLOCKS = 10_000;

    /** The largest peak error. */
    public static final int PEAK_ERROR = 1;

    /** The largest mean squared error of one sample position. */
    public static final double PIXEL_MSE = 0.06;

    /** The largest mean squared error of all samples. */
    public static final double OVERALL_MSE = 0.02;

    /** The largest magnitude of the mean error of one sample position. */
    public static final double PIXEL_MEAN_ERROR = 0.015;

    /** The largest magnitude of the mean error of all samples. */
    public static final double OVERALL_MEAN_ERROR = 0.0015;

    /** The cosine table of the reference transforms, {@code C(u) / 2 * cos((2x + 1) u pi / 16)} at {@code [u][x]}. */
    private static final double[][] BASIS = new double[8][8];

    static {
        for (var u = 0; u < 8; u++) {
            var scale = u == 0 ? Math.sqrt(0.125) : 0.5;
            for (var x = 0; x < 8; x++) {
                BASIS[u][x] = scale * Math.cos((2 * x + 1) * u * Math.PI / 16);
            }
        }
    }

    private Ieee1180() {
        throw new AssertionError();
    }

    /** An 8x8 inverse DCT under test. */
    @FunctionalInterface
    public interface Idct {
        /**
         * Transforms one block.
         *
         * @param coefficients the 64 coefficients in row-major order, the coefficient of vertical frequency {@code v}
         *                     and horizontal frequency {@code u} at {@code v * 8 + u}; not modified by the test after
         *                     the call, so the transform may clear it
         * @param samples receives the 64 samples in row-major order, unclipped
         */
        void inverse(int[] coefficients, int[] samples);
    }

    /**
     * The statistics of one run.
     *
     * @param low the {@code L} of the run
     * @param high the {@code H} of the run
     * @param negated whether the samples were negated
     * @param peakError the largest absolute error of any sample
     * @param pixelMse the largest mean squared error of a sample position
     * @param overallMse the mean squared error of all samples
     * @param pixelMeanError the largest magnitude of the mean error of a sample position
     * @param overallMeanError the mean error of all samples
     */
    public record Run(int low, int high, boolean negated, int peakError, double pixelMse, double overallMse,
                      double pixelMeanError, double overallMeanError) {
        /** {@return the limits this run breaks, empty when it complies} */
        public List<String> violations() {
            var violations = new ArrayList<String>();
            if (peakError > PEAK_ERROR) {
                violations.add("peak error " + peakError + " > " + PEAK_ERROR);
            }
            if (pixelMse > PIXEL_MSE) {
                violations.add(String.format(Locale.ROOT, "pixel mse %.5f > %s", pixelMse, PIXEL_MSE));
            }
            if (overallMse > OVERALL_MSE) {
                violations.add(String.format(Locale.ROOT, "overall mse %.5f > %s", overallMse, OVERALL_MSE));
            }
            if (pixelMeanError > PIXEL_MEAN_ERROR) {
                violations.add(String.format(Locale.ROOT, "pixel mean error %.5f > %s", pixelMeanError,
                        PIXEL_MEAN_ERROR));
            }
            if (Math.abs(overallMeanError) > OVERALL_MEAN_ERROR) {
                violations.add(String.format(Locale.ROOT, "overall mean error %.5f > %s", overallMeanError,
                        OVERALL_MEAN_ERROR));
            }
            return violations;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "L=%d H=%d%s: peak %d, pixel mse %.5f, overall mse %.5f, pixel mean %.5f,"
                            + " overall mean %.6f", low, high, negated ? " negated" : "", peakError, pixelMse,
                    overallMse, pixelMeanError, overallMeanError);
        }
    }

    /**
     * Measures one run.
     *
     * @param idct the transform under test
     * @param low the {@code L} of the run, the magnitude of the smallest sample
     * @param high the {@code H} of the run, the largest sample
     * @param negated whether to negate the samples
     * @param blocks the number of blocks, {@link #BLOCKS} for the standard's runs
     * @return the statistics
     */
    public static Run measure(Idct idct, int low, int high, boolean negated, int blocks) {
        var random = new Random();
        var block = new double[64];
        var coefficients = new double[64];
        var input = new int[64];
        var reference = new double[64];
        var samples = new int[64];
        var sum = new long[64];
        var squares = new long[64];
        var peak = 0;
        for (var b = 0; b < blocks; b++) {
            for (var i = 0; i < 64; i++) {
                var value = random.next(low, high);
                block[i] = negated ? -value : value;
            }
            forward(block, coefficients);
            for (var i = 0; i < 64; i++) {
                coefficients[i] = clip(Math.floor(coefficients[i] + 0.5), -2048, 2047);
                input[i] = (int) coefficients[i];
            }
            inverse(coefficients, reference);
            java.util.Arrays.fill(samples, 0);
            idct.inverse(input, samples);
            for (var i = 0; i < 64; i++) {
                var expected = (int) clip(Math.floor(reference[i] + 0.5), -256, 255);
                var actual = (int) clip(samples[i], -256, 255);
                var error = actual - expected;
                peak = Math.max(peak, Math.abs(error));
                sum[i] += error;
                squares[i] += (long) error * error;
            }
        }
        var pixelMse = 0.0;
        var pixelMean = 0.0;
        long totalSum = 0;
        long totalSquares = 0;
        for (var i = 0; i < 64; i++) {
            pixelMse = Math.max(pixelMse, (double) squares[i] / blocks);
            pixelMean = Math.max(pixelMean, Math.abs((double) sum[i] / blocks));
            totalSum += sum[i];
            totalSquares += squares[i];
        }
        return new Run(low, high, negated, peak, pixelMse, (double) totalSquares / (64.0 * blocks), pixelMean,
                (double) totalSum / (64.0 * blocks));
    }

    /**
     * Measures the standard's six runs.
     *
     * @param idct the transform under test
     * @return the statistics of each run
     */
    public static List<Run> measure(Idct idct) {
        var runs = new ArrayList<Run>();
        for (var range : new int[][]{{256, 255}, {5, 5}, {300, 300}}) {
            for (var negated : new boolean[]{false, true}) {
                runs.add(measure(idct, range[0], range[1], negated, BLOCKS));
            }
        }
        return runs;
    }

    /**
     * Asserts that a transform complies with IEEE 1180: the six runs and the all-zero block.
     *
     * @param what the transform, for the failure message
     * @param idct the transform under test
     * @return the statistics of each run
     */
    public static List<Run> assertCompliant(String what, Idct idct) {
        var runs = measure(idct);
        var failures = new ArrayList<String>();
        for (var run : runs) {
            var violations = run.violations();
            if (!violations.isEmpty()) {
                failures.add(run + " (" + String.join(", ", violations) + ")");
            }
        }
        var zero = new int[64];
        var samples = new int[64];
        java.util.Arrays.fill(samples, 7);
        idct.inverse(zero, samples);
        for (var i = 0; i < 64; i++) {
            if (samples[i] != 0) {
                failures.add("an all-zero block transforms to " + samples[i] + " at " + i);
                break;
            }
        }
        if (!failures.isEmpty()) {
            Assertions.fail(what + " does not meet IEEE 1180: " + String.join("; ", failures));
        }
        return runs;
    }

    /**
     * Transforms a block of samples with the double precision forward DCT.
     *
     * @param samples the samples in row-major order
     * @param coefficients receives the coefficients in row-major order (vertical frequency major)
     */
    public static void forward(double[] samples, double[] coefficients) {
        var temporary = new double[64];
        for (var y = 0; y < 8; y++) {
            for (var u = 0; u < 8; u++) {
                var sum = 0.0;
                for (var x = 0; x < 8; x++) {
                    sum += BASIS[u][x] * samples[y * 8 + x];
                }
                temporary[y * 8 + u] = sum;
            }
        }
        for (var v = 0; v < 8; v++) {
            for (var u = 0; u < 8; u++) {
                var sum = 0.0;
                for (var y = 0; y < 8; y++) {
                    sum += BASIS[v][y] * temporary[y * 8 + u];
                }
                coefficients[v * 8 + u] = sum;
            }
        }
    }

    /**
     * Transforms a block of coefficients with the double precision inverse DCT, the reference of the test.
     *
     * @param coefficients the coefficients in row-major order (vertical frequency major)
     * @param samples receives the samples in row-major order, unrounded
     */
    public static void inverse(double[] coefficients, double[] samples) {
        var temporary = new double[64];
        for (var v = 0; v < 8; v++) {
            for (var x = 0; x < 8; x++) {
                var sum = 0.0;
                for (var u = 0; u < 8; u++) {
                    sum += BASIS[u][x] * coefficients[v * 8 + u];
                }
                temporary[v * 8 + x] = sum;
            }
        }
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                var sum = 0.0;
                for (var v = 0; v < 8; v++) {
                    sum += BASIS[v][y] * temporary[v * 8 + x];
                }
                samples[y * 8 + x] = sum;
            }
        }
    }

    private static double clip(double value, double low, double high) {
        return value < low ? low : value > high ? high : value;
    }

    /** The random number generator IEEE 1180 specifies, on 32-bit arithmetic. */
    private static final class Random {
        private int state = 1;

        /** Returns a sample from {@code -low} to {@code high}. */
        int next(int low, int high) {
            state = state * 1103515245 + 12345;
            var i = state & 0x7ffffffe;
            var x = (double) i / 0x7fffffff;
            x *= low + high + 1;
            return (int) x - low;
        }
    }
}
