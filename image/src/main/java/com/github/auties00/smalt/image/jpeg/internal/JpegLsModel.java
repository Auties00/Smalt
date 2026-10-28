/* Ports CharLS (commit 1153c25: jpegls_algorithm.hpp, regular_mode_context.hpp, run_mode_context.hpp,
 * default_traits.hpp, jpegls_preset_coding_parameters.hpp, scan_codec.hpp). Copyright (c) 2007 Jan de Vaan and
 * Victor Derks (Team CharLS); BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import java.util.Arrays;

/**
 * Holds the context model of a JPEG-LS scan (ITU-T T.87 annex A), shared by the decoder and the encoder: the
 * coding parameters, the gradient quantization, the regular and run-interruption context variables and the run
 * index, with the arithmetic that predicts, corrects and reconstructs samples.
 *
 * <p>The arithmetic follows T.87 for any {@code MAXVAL}: {@code RANGE}, {@code qbpp}, {@code bpp} and
 * {@code LIMIT} come from A.2.1, predictions are clamped to {@code [0, MAXVAL]}, and reconstructed values are
 * brought back into range modulo {@code RANGE * (2 * NEAR + 1)} as code segment A.9 requires.
 *
 * <p>Instances are not thread-safe.
 */
final class JpegLsModel {
    /**
     * The run lengths {@code J[RUNindex]} of T.87 A.2.1, step 3.
     */
    static final int[] J = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10,
            11, 12, 13, 14, 15};

    /**
     * The number of regular contexts.
     */
    private static final int CONTEXTS = 365;

    /**
     * The default {@code RESET}, T.87 table C.2.
     */
    static final int DEFAULT_RESET = 64;

    /**
     * The largest Golomb parameter the model produces; a context whose statistics ask for more, which only corrupt
     * data causes, is coded with this one.
     */
    private static final int MAX_K = 30;

    /**
     * The largest sample value.
     */
    final int maxval;

    /**
     * The near-lossless bound, 0 for lossless coding.
     */
    final int near;

    /**
     * The size of the error alphabet.
     */
    final int range;

    /**
     * The number of bits of a mapped error value in an escape code.
     */
    final int qbpp;

    /**
     * The number of bits of a sample.
     */
    final int bpp;

    /**
     * The longest Golomb code of a regular-mode sample.
     */
    final int limit;

    /**
     * The value at which the counters are halved.
     */
    final int reset;

    /**
     * The quantized gradient of every difference from {@code -maxval} to {@code maxval}, at index
     * {@code difference + maxval}.
     */
    private final byte[] quantization;

    /**
     * The accumulated error magnitudes {@code A} of the regular contexts.
     */
    private final long[] a = new long[CONTEXTS];

    /**
     * The bias accumulators {@code B} of the regular contexts.
     */
    private final int[] b = new int[CONTEXTS];

    /**
     * The prediction corrections {@code C} of the regular contexts.
     */
    private final int[] c = new int[CONTEXTS];

    /**
     * The occurrence counters {@code N} of the regular contexts.
     */
    private final int[] n = new int[CONTEXTS];

    /**
     * The accumulated error magnitudes of the two run-interruption contexts, by {@code RItype}.
     */
    private final long[] runA = new long[2];

    /**
     * The occurrence counters of the two run-interruption contexts.
     */
    private final int[] runN = new int[2];

    /**
     * The negative-error counters {@code Nn} of the two run-interruption contexts.
     */
    private final int[] runNn = new int[2];

    /**
     * The run index {@code RUNindex} of the component being coded.
     */
    int runIndex;

    /**
     * Constructs the model of a scan and initializes its variables.
     *
     * @param maxval the largest sample value, at least 1
     * @param near the near-lossless bound, from 0 to {@code min(255, maxval / 2)}
     * @param t1 the first gradient threshold
     * @param t2 the second gradient threshold
     * @param t3 the third gradient threshold
     * @param reset the counter reset threshold, at least 3
     */
    JpegLsModel(int maxval, int near, int t1, int t2, int t3, int reset) {
        this.maxval = maxval;
        this.near = near;
        this.range = (maxval + 2 * near) / (2 * near + 1) + 1;
        this.qbpp = ceilLog2(range);
        this.bpp = Math.max(2, ceilLog2(maxval + 1));
        this.limit = 2 * (bpp + Math.max(8, bpp));
        this.reset = reset;
        this.quantization = new byte[2 * maxval + 1];
        for (var d = -maxval; d <= maxval; d++) {
            quantization[d + maxval] = (byte) quantize(d, t1, t2, t3, near);
        }
        reset();
    }

    /**
     * Returns the default thresholds and reset value of T.87 C.2.4.1.1.1.
     *
     * @param maxval the largest sample value
     * @param near the near-lossless bound
     * @return {@code T1}, {@code T2}, {@code T3} and {@code RESET}
     */
    static int[] defaults(int maxval, int near) {
        if (maxval >= 128) {
            var factor = (Math.min(maxval, 4095) + 128) / 256;
            var t1 = clamp(factor * (3 - 2) + 2 + 3 * near, near + 1, maxval);
            var t2 = clamp(factor * (7 - 3) + 3 + 5 * near, t1, maxval);
            var t3 = clamp(factor * (21 - 4) + 4 + 7 * near, t2, maxval);
            return new int[]{t1, t2, t3, DEFAULT_RESET};
        }
        var factor = 256 / (maxval + 1);
        var t1 = clamp(Math.max(2, 3 / factor + 3 * near), near + 1, maxval);
        var t2 = clamp(Math.max(3, 7 / factor + 5 * near), t1, maxval);
        var t3 = clamp(Math.max(4, 21 / factor + 7 * near), t2, maxval);
        return new int[]{t1, t2, t3, DEFAULT_RESET};
    }

    /**
     * Applies the clamping function of T.87 figure C.3.
     *
     * @param i the value
     * @param j the replacement
     * @param maxval the largest sample value
     * @return {@code j} when {@code i} exceeds {@code maxval} or is below {@code j}, else {@code i}
     */
    private static int clamp(int i, int j, int maxval) {
        return i > maxval || i < j ? j : i;
    }

    /**
     * {@return the smallest {@code x} with {@code 2^x >= value}}
     *
     * @param value a positive value
     */
    static int ceilLog2(int value) {
        var x = 0;
        while ((1L << x) < value) {
            x++;
        }
        return x;
    }

    /**
     * Quantizes a local gradient (T.87 code segment A.4).
     *
     * @param d the gradient
     * @param t1 the first threshold
     * @param t2 the second threshold
     * @param t3 the third threshold
     * @param near the near-lossless bound
     * @return the region, from -4 to 4
     */
    private static int quantize(int d, int t1, int t2, int t3, int near) {
        if (d <= -t3) {
            return -4;
        }
        if (d <= -t2) {
            return -3;
        }
        if (d <= -t1) {
            return -2;
        }
        if (d < -near) {
            return -1;
        }
        if (d <= near) {
            return 0;
        }
        if (d < t1) {
            return 1;
        }
        if (d < t2) {
            return 2;
        }
        if (d < t3) {
            return 3;
        }
        return 4;
    }

    /**
     * Restores the initial values of every variable (T.87 A.2.1), at the start of a scan and of a restart
     * interval.
     */
    void reset() {
        var initialA = Math.max(2, (range + 32) / 64);
        Arrays.fill(a, initialA);
        Arrays.fill(b, 0);
        Arrays.fill(c, 0);
        Arrays.fill(n, 1);
        Arrays.fill(runA, initialA);
        Arrays.fill(runN, 1);
        Arrays.fill(runNn, 0);
        runIndex = 0;
    }

    /**
     * Returns the signed context of a sample from its causal neighbors.
     *
     * @param ra the reconstructed sample to the left
     * @param rb the reconstructed sample above
     * @param rc the reconstructed sample above and to the left
     * @param rd the reconstructed sample above and to the right
     * @return {@code (Q1 * 9 + Q2) * 9 + Q3}, from -364 to 364; zero selects the run mode
     */
    int context(int ra, int rb, int rc, int rd) {
        var offset = maxval;
        return (quantization[rd - rb + offset] * 9 + quantization[rb - rc + offset]) * 9
                + quantization[rc - ra + offset];
    }

    /**
     * Returns the median edge detector prediction of T.87 code segment A.5.
     *
     * @param ra the sample to the left
     * @param rb the sample above
     * @param rc the sample above and to the left
     * @return the prediction
     */
    static int predict(int ra, int rb, int rc) {
        if (rc >= Math.max(ra, rb)) {
            return Math.min(ra, rb);
        }
        if (rc <= Math.min(ra, rb)) {
            return Math.max(ra, rb);
        }
        return ra + rb - rc;
    }

    /**
     * Returns the prediction corrected by the context's bias and clamped to {@code [0, MAXVAL]} (T.87 code
     * segment A.8).
     *
     * @param prediction the median edge detector prediction
     * @param q the index of the context
     * @param sign -1 when the context was negated, else 0
     * @return the corrected prediction
     */
    int correct(int prediction, int q, int sign) {
        var corrected = prediction + ((c[q] ^ sign) - sign);
        return corrected < 0 ? 0 : Math.min(corrected, maxval);
    }

    /**
     * {@return the Golomb parameter of a regular context (T.87 code segment A.10)}
     *
     * @param q the index of the context
     */
    int golomb(int q) {
        var k = 0;
        var count = (long) n[q];
        var accumulated = a[q];
        while ((count << k) < accumulated && k < MAX_K) {
            k++;
        }
        return k;
    }

    /**
     * Returns the special mapping flag of T.87 code segment A.11: -1 when the error of a lossless context with
     * {@code k = 0} and {@code 2 * B <= -N} is coded inverted, else 0.
     *
     * @param q the index of the context
     * @param k the Golomb parameter
     * @return -1 or 0
     */
    int errorCorrection(int q, int k) {
        if (k != 0 || near != 0) {
            return 0;
        }
        return (2 * b[q] + n[q] - 1) >> 31;
    }

    /**
     * Updates the variables of a regular context after coding an error (T.87 code segments A.12 and A.13).
     *
     * @param q the index of the context
     * @param error the error value
     */
    void update(int q, int error) {
        a[q] += Math.abs(error);
        var bias = b[q] + error * (2 * near + 1);
        var count = n[q];
        if (count == reset) {
            a[q] >>= 1;
            bias >>= 1;
            count >>= 1;
        }
        count++;
        if (bias + count <= 0) {
            bias += count;
            if (bias <= -count) {
                bias = -count + 1;
            }
            if (c[q] > -128) {
                c[q]--;
            }
        } else if (bias > 0) {
            bias -= count;
            if (bias > 0) {
                bias = 0;
            }
            if (c[q] < 127) {
                c[q]++;
            }
        }
        b[q] = bias;
        n[q] = count;
    }

    /**
     * Quantizes a prediction error for near-lossless coding and reduces it modulo {@code RANGE} (T.87 code
     * segments A.6 and A.9).
     *
     * @param error the prediction error
     * @return the error value, from {@code -RANGE / 2} to {@code (RANGE + 1) / 2 - 1}
     */
    int errorValue(int error) {
        if (near > 0) {
            error = error > 0 ? (error + near) / (2 * near + 1) : -(near - error) / (2 * near + 1);
        }
        if (error < 0) {
            error += range;
        }
        if (error >= (range + 1) / 2) {
            error -= range;
        }
        return error;
    }

    /**
     * Reconstructs a sample from its prediction and error value (T.87 code segment A.8 and the modulo reduction
     * of A.9 inverted).
     *
     * @param prediction the corrected prediction
     * @param error the error value
     * @return the sample, from 0 to {@code MAXVAL}
     */
    int reconstruct(int prediction, int error) {
        var step = 2 * near + 1;
        var value = prediction + error * step;
        if (value < -near) {
            value += range * step;
        } else if (value > maxval + near) {
            value -= range * step;
        }
        return value < 0 ? 0 : Math.min(value, maxval);
    }

    /**
     * {@return whether two reconstructed samples are within {@code NEAR} of each other}
     *
     * @param x one sample
     * @param y the other sample
     */
    boolean near(int x, int y) {
        return Math.abs(x - y) <= near;
    }

    /**
     * {@return the Golomb parameter of a run-interruption context (T.87 code segment A.20)}
     *
     * @param type the run-interruption type, 0 or 1
     */
    int runGolomb(int type) {
        var temp = runA[type] + (runN[type] >> 1) * (long) type;
        var count = (long) runN[type];
        var k = 0;
        while (count < temp && k < MAX_K) {
            count <<= 1;
            k++;
        }
        return k;
    }

    /**
     * Returns the {@code map} flag of T.87 code segment A.21.
     *
     * @param type the run-interruption type
     * @param error the error value
     * @param k the Golomb parameter
     * @return whether the error is mapped with the extra subtraction
     */
    boolean runMap(int type, int error, int k) {
        var half = 2 * runNn[type] < runN[type];
        if (k == 0 && error > 0 && half) {
            return true;
        }
        if (error < 0 && !half) {
            return true;
        }
        return error < 0 && k != 0;
    }

    /**
     * Recovers a run-interruption error value from its mapped value (T.87 code segment A.22 inverted).
     *
     * @param type the run-interruption type
     * @param temp the mapped error value plus the run-interruption type
     * @param k the Golomb parameter
     * @return the error value
     */
    int runError(int type, int temp, int k) {
        var map = (temp & 1) != 0;
        var magnitude = (temp + (map ? 1 : 0)) / 2;
        if ((k != 0 || 2 * runNn[type] >= runN[type]) == map) {
            return -magnitude;
        }
        return magnitude;
    }

    /**
     * Updates the variables of a run-interruption context (T.87 code segment A.23).
     *
     * @param type the run-interruption type
     * @param error the error value
     * @param mapped the mapped error value
     */
    void runUpdate(int type, int error, int mapped) {
        if (error < 0) {
            runNn[type]++;
        }
        runA[type] += (mapped + 1 - type) >> 1;
        if (runN[type] == reset) {
            runA[type] >>= 1;
            runN[type] >>= 1;
            runNn[type] >>= 1;
        }
        runN[type]++;
    }

    /**
     * Raises the run index after a full run segment.
     */
    void incrementRun() {
        if (runIndex < 31) {
            runIndex++;
        }
    }

    /**
     * Lowers the run index after a run interruption.
     */
    void decrementRun() {
        if (runIndex > 0) {
            runIndex--;
        }
    }
}
