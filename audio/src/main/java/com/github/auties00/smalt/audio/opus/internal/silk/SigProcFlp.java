/* Ports libopus 1.5.2 (silk/float/SigProc_FLP.h and the silk/float files autocorrelation_FLP.c, burg_modified_FLP.c,
   bwexpander_FLP.c, energy_FLP.c, inner_product_FLP.c, k2a_FLP.c, schur_FLP.c, scale_copy_vector_FLP.c,
   scale_vector_FLP.c, sort_FLP.c, apply_sine_window_FLP.c, warped_autocorrelation_FLP.c, LPC_analysis_filter_FLP.c,
   LTP_analysis_filter_FLP.c, residual_energy_FLP.c, corrMatrix_FLP.c). Copyright Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;

import java.util.Arrays;

/**
 * Provides the floating-point signal-processing primitives of the SILK encoder.
 *
 * <p>Every function keeps the evaluation order, the float/double promotions and the unrolling
 * pattern of the reference, so results are bit-identical to the float build of libopus.
 */
public final class SigProcFlp {
    /**
     * The single-precision pi of the SILK float code ({@code PI} in {@code SigProc_FLP.h}).
     */
    static final float PI = 3.1415926536f;

    /**
     * The maximum LPC order handled by the generic routines ({@code SILK_MAX_ORDER_LPC}).
     */
    static final int SILK_MAX_ORDER_LPC = 24;

    /**
     * The maximum prediction power gain ({@code MAX_PREDICTION_POWER_GAIN}).
     */
    static final float MAX_PREDICTION_POWER_GAIN = 1e4f;

    /**
     * The LPC analysis regularization factor ({@code FIND_LPC_COND_FAC}).
     */
    static final float FIND_LPC_COND_FAC = 1e-5f;

    /**
     * The number of LTP taps ({@code LTP_ORDER}).
     */
    private static final int LTP_ORDER = 5;

    private SigProcFlp() {
        throw new AssertionError();
    }

    /**
     * Returns the logistic sigmoid of a value ({@code silk_sigmoid}).
     *
     * @param x the argument
     * @return {@code 1 / (1 + exp(-x))} rounded to float
     */
    static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + StrictMath.exp(-x)));
    }

    /**
     * Rounds a float to the nearest integer, ties to even ({@code silk_float2int}).
     *
     * @param x the value
     * @return the rounded value
     */
    static int float2int(float x) {
        return MathOps.float2int(x);
    }

    /**
     * Returns the base-2 logarithm computed through {@code log10} as the reference does
     * ({@code silk_log2}).
     *
     * @param x the argument, positive
     * @return {@code log2(x)} rounded to float
     */
    static float log2(double x) {
        return (float) (3.32192809488736 * StrictMath.log10(x));
    }

    /**
     * Rounds and saturates floats to 16-bit integers ({@code silk_float2short_array}).
     *
     * @param out    receives the samples
     * @param outOff the index of the first output sample
     * @param in     the input
     * @param inOff  the index of the first input sample
     * @param length the number of samples
     */
    static void float2shortArray(short[] out, int outOff, float[] in, int inOff, int length) {
        for (var k = length - 1; k >= 0; k--) {
            var v = MathOps.float2int(in[inOff + k]);
            out[outOff + k] = (short) (v > Short.MAX_VALUE ? Short.MAX_VALUE : (v < Short.MIN_VALUE ? Short.MIN_VALUE : v));
        }
    }

    /**
     * Converts 16-bit integers to floats ({@code silk_short2float_array}).
     *
     * @param out    receives the samples
     * @param outOff the index of the first output sample
     * @param in     the input
     * @param inOff  the index of the first input sample
     * @param length the number of samples
     */
    static void short2floatArray(float[] out, int outOff, short[] in, int inOff, int length) {
        for (var k = length - 1; k >= 0; k--) {
            out[outOff + k] = in[inOff + k];
        }
    }

    /**
     * Returns the inner product of two float vectors accumulated in double precision
     * ({@code silk_inner_product_FLP_c}).
     *
     * <p>The sum is accumulated four products at a time, each group summed left to right before
     * being added to the running total, exactly as the reference unrolls it.
     *
     * @param data1    the first vector
     * @param off1     the index of the first element of {@code data1}
     * @param data2    the second vector
     * @param off2     the index of the first element of {@code data2}
     * @param dataSize the number of elements
     * @return the inner product
     */
    static double innerProductFlp(float[] data1, int off1, float[] data2, int off2, int dataSize) {
        var result = 0.0;
        var i = 0;
        for (; i < dataSize - 3; i += 4) {
            result += data1[off1 + i] * (double) data2[off2 + i]
                    + data1[off1 + i + 1] * (double) data2[off2 + i + 1]
                    + data1[off1 + i + 2] * (double) data2[off2 + i + 2]
                    + data1[off1 + i + 3] * (double) data2[off2 + i + 3];
        }
        for (; i < dataSize; i++) {
            result += data1[off1 + i] * (double) data2[off2 + i];
        }
        return result;
    }

    /**
     * Returns the sum of squares of a float vector accumulated in double precision
     * ({@code silk_energy_FLP}).
     *
     * @param data     the vector
     * @param off      the index of the first element
     * @param dataSize the number of elements
     * @return the energy
     */
    static double energyFlp(float[] data, int off, int dataSize) {
        var result = 0.0;
        var i = 0;
        for (; i < dataSize - 3; i += 4) {
            result += data[off + i] * (double) data[off + i]
                    + data[off + i + 1] * (double) data[off + i + 1]
                    + data[off + i + 2] * (double) data[off + i + 2]
                    + data[off + i + 3] * (double) data[off + i + 3];
        }
        for (; i < dataSize; i++) {
            result += data[off + i] * (double) data[off + i];
        }
        return result;
    }

    /**
     * Computes the autocorrelation of a signal ({@code silk_autocorrelation_FLP}).
     *
     * @param results          receives {@code correlationCount} correlations
     * @param resultsOff       the index of the first correlation
     * @param inputData        the signal
     * @param inputOff         the index of the first sample
     * @param inputDataSize    the number of samples
     * @param correlationCount the number of lags, clamped to {@code inputDataSize}
     */
    static void autocorrelationFlp(float[] results, int resultsOff, float[] inputData, int inputOff, int inputDataSize,
                                   int correlationCount) {
        if (correlationCount > inputDataSize) {
            correlationCount = inputDataSize;
        }
        for (var i = 0; i < correlationCount; i++) {
            results[resultsOff + i] = (float) innerProductFlp(inputData, inputOff, inputData, inputOff + i, inputDataSize - i);
        }
    }

    /**
     * Computes prediction coefficients with the modified Burg method over stacked subframes
     * ({@code silk_burg_modified_FLP}).
     *
     * @param a           receives {@code d} prediction coefficients
     * @param aOff        the index of the first coefficient
     * @param x           the signal, {@code nbSubfr} subframes of {@code subfrLength} samples
     * @param xOff        the index of the first sample
     * @param minInvGain  the minimum inverse prediction gain
     * @param subfrLength the subframe length, including the {@code d} preceding samples
     * @param nbSubfr     the number of subframes
     * @param d           the order
     * @param scratch     the work arrays, overwritten
     * @return the residual energy
     */
    static float burgModifiedFlp(float[] a, int aOff, float[] x, int xOff, float minInvGain, int subfrLength,
                                 int nbSubfr, int d, AnalysisScratch scratch) {
        int k, n, s;
        var reachedMaxGain = 0;
        double c0, invGain, num, nrgF, nrgB, rc, atmp, tmp1, tmp2;
        var cFirstRow = scratch.burgCFirstRow;
        var cLastRow = scratch.burgCLastRow;
        var caf = scratch.burgCaf;
        var cab = scratch.burgCab;
        var af = scratch.burgAf;
        Arrays.fill(cFirstRow, 0);
        Arrays.fill(cLastRow, 0);
        Arrays.fill(caf, 0);
        Arrays.fill(cab, 0);
        Arrays.fill(af, 0);

        c0 = energyFlp(x, xOff, nbSubfr * subfrLength);
        for (s = 0; s < nbSubfr; s++) {
            var xPtr = xOff + s * subfrLength;
            for (n = 1; n < d + 1; n++) {
                cFirstRow[n - 1] += innerProductFlp(x, xPtr, x, xPtr + n, subfrLength - n);
            }
        }
        System.arraycopy(cFirstRow, 0, cLastRow, 0, SILK_MAX_ORDER_LPC);

        cab[0] = caf[0] = c0 + FIND_LPC_COND_FAC * c0 + 1e-9f;
        invGain = 1.0f;
        for (n = 0; n < d; n++) {
            for (s = 0; s < nbSubfr; s++) {
                var xPtr = xOff + s * subfrLength;
                tmp1 = x[xPtr + n];
                tmp2 = x[xPtr + subfrLength - n - 1];
                for (k = 0; k < n; k++) {
                    cFirstRow[k] -= x[xPtr + n] * x[xPtr + n - k - 1];
                    cLastRow[k] -= x[xPtr + subfrLength - n - 1] * x[xPtr + subfrLength - n + k];
                    atmp = af[k];
                    tmp1 += x[xPtr + n - k - 1] * atmp;
                    tmp2 += x[xPtr + subfrLength - n + k] * atmp;
                }
                for (k = 0; k <= n; k++) {
                    caf[k] -= tmp1 * x[xPtr + n - k];
                    cab[k] -= tmp2 * x[xPtr + subfrLength - n + k - 1];
                }
            }
            tmp1 = cFirstRow[n];
            tmp2 = cLastRow[n];
            for (k = 0; k < n; k++) {
                atmp = af[k];
                tmp1 += cLastRow[n - k - 1] * atmp;
                tmp2 += cFirstRow[n - k - 1] * atmp;
            }
            caf[n + 1] = tmp1;
            cab[n + 1] = tmp2;

            num = cab[n + 1];
            nrgB = cab[0];
            nrgF = caf[0];
            for (k = 0; k < n; k++) {
                atmp = af[k];
                num += cab[n - k] * atmp;
                nrgB += cab[k + 1] * atmp;
                nrgF += caf[k + 1] * atmp;
            }

            rc = -2.0 * num / (nrgF + nrgB);

            tmp1 = invGain * (1.0 - rc * rc);
            if (tmp1 <= minInvGain) {
                rc = Math.sqrt(1.0 - minInvGain / invGain);
                if (num > 0) {
                    rc = -rc;
                }
                invGain = minInvGain;
                reachedMaxGain = 1;
            } else {
                invGain = tmp1;
            }

            for (k = 0; k < (n + 1) >> 1; k++) {
                tmp1 = af[k];
                tmp2 = af[n - k - 1];
                af[k] = tmp1 + rc * tmp2;
                af[n - k - 1] = tmp2 + rc * tmp1;
            }
            af[n] = rc;

            if (reachedMaxGain != 0) {
                for (k = n + 1; k < d; k++) {
                    af[k] = 0.0;
                }
                break;
            }

            for (k = 0; k <= n + 1; k++) {
                tmp1 = caf[k];
                caf[k] += rc * cab[n - k + 1];
                cab[n - k + 1] += rc * tmp1;
            }
        }

        if (reachedMaxGain != 0) {
            for (k = 0; k < d; k++) {
                a[aOff + k] = (float) (-af[k]);
            }
            for (s = 0; s < nbSubfr; s++) {
                c0 -= energyFlp(x, xOff + s * subfrLength, d);
            }
            nrgF = c0 * invGain;
        } else {
            nrgF = caf[0];
            tmp1 = 1.0;
            for (k = 0; k < d; k++) {
                atmp = af[k];
                nrgF += caf[k + 1] * atmp;
                tmp1 += atmp * atmp;
                a[aOff + k] = (float) (-atmp);
            }
            nrgF -= FIND_LPC_COND_FAC * c0 * tmp1;
        }
        return (float) nrgF;
    }

    /**
     * Applies bandwidth expansion (chirp) to an AR filter ({@code silk_bwexpander_FLP}).
     *
     * @param ar    the coefficients, without the leading one, expanded in place
     * @param arOff the index of the first coefficient
     * @param d     the number of coefficients
     * @param chirp the chirp factor, typically in {@code (0, 1)}
     */
    static void bwexpanderFlp(float[] ar, int arOff, int d, float chirp) {
        var cfac = chirp;
        for (var i = 0; i < d - 1; i++) {
            ar[arOff + i] *= cfac;
            cfac *= chirp;
        }
        ar[arOff + d - 1] *= cfac;
    }

    /**
     * Converts reflection coefficients to prediction coefficients ({@code silk_k2a_FLP}).
     *
     * @param a     receives {@code order} prediction coefficients
     * @param aOff  the index of the first prediction coefficient
     * @param rc    the reflection coefficients
     * @param order the prediction order
     */
    static void k2aFlp(float[] a, int aOff, float[] rc, int order) {
        for (var k = 0; k < order; k++) {
            var rck = rc[k];
            for (var n = 0; n < (k + 1) >> 1; n++) {
                var tmp1 = a[aOff + n];
                var tmp2 = a[aOff + k - n - 1];
                a[aOff + n] = tmp1 + tmp2 * rck;
                a[aOff + k - n - 1] = tmp2 + tmp1 * rck;
            }
            a[aOff + k] = -rck;
        }
    }

    /**
     * Computes reflection coefficients from an autocorrelation sequence with the Schur
     * recursion ({@code silk_schur_FLP}).
     *
     * @param reflCoef receives {@code order} reflection coefficients
     * @param autoCorr the autocorrelation sequence, {@code order + 1} values
     * @param order the prediction order
     * @param scratch the work arrays, overwritten
     * @return the residual energy
     */
    static float schurFlp(float[] reflCoef, float[] autoCorr, int order, AnalysisScratch scratch) {
        var c0 = scratch.schurC0;
        var c1 = scratch.schurC1;
        var k = 0;
        do {
            c0[k] = c1[k] = autoCorr[k];
        } while (++k <= order);
        for (k = 0; k < order; k++) {
            var rcTmp = -c0[k + 1] / (c1[0] > 1e-9f ? c1[0] : 1e-9f);
            reflCoef[k] = (float) rcTmp;
            for (var n = 0; n < order - k; n++) {
                var ctmp1 = c0[n + k + 1];
                var ctmp2 = c1[n];
                c0[n + k + 1] = ctmp1 + ctmp2 * rcTmp;
                c1[n] = ctmp2 + ctmp1 * rcTmp;
            }
        }
        return (float) c1[0];
    }

    /**
     * Multiplies a vector by a constant into another vector ({@code silk_scale_copy_vector_FLP}).
     *
     * @param dataOut  receives the scaled vector
     * @param outOff   the index of the first output element
     * @param dataIn   the input vector
     * @param inOff    the index of the first input element
     * @param gain     the factor
     * @param dataSize the number of elements
     */
    static void scaleCopyVectorFlp(float[] dataOut, int outOff, float[] dataIn, int inOff, float gain, int dataSize) {
        for (var i = 0; i < dataSize; i++) {
            dataOut[outOff + i] = gain * dataIn[inOff + i];
        }
    }

    /**
     * Multiplies a vector by a constant in place ({@code silk_scale_vector_FLP}).
     *
     * @param data1    the vector
     * @param off      the index of the first element
     * @param gain     the factor
     * @param dataSize the number of elements
     */
    static void scaleVectorFlp(float[] data1, int off, float gain, int dataSize) {
        for (var i = 0; i < dataSize; i++) {
            data1[off + i] *= gain;
        }
    }

    /**
     * Partially sorts a vector in decreasing order, keeping the original indices of the first
     * {@code k} elements ({@code silk_insertion_sort_decreasing_FLP}).
     *
     * <p>Only the first {@code k} positions are guaranteed sorted and correct.
     *
     * @param a    the values, sorted in place
     * @param aOff the index of the first value
     * @param idx  receives the original index of each of the first {@code k} values
     * @param l    the number of values
     * @param k    the number of positions to sort
     */
    static void insertionSortDecreasingFlp(float[] a, int aOff, int[] idx, int l, int k) {
        int i, j;
        for (i = 0; i < k; i++) {
            idx[i] = i;
        }
        for (i = 1; i < k; i++) {
            var value = a[aOff + i];
            for (j = i - 1; j >= 0 && value > a[aOff + j]; j--) {
                a[aOff + j + 1] = a[aOff + j];
                idx[j + 1] = idx[j];
            }
            a[aOff + j + 1] = value;
            idx[j + 1] = i;
        }
        for (i = k; i < l; i++) {
            var value = a[aOff + i];
            if (value > a[aOff + k - 1]) {
                for (j = k - 2; j >= 0 && value > a[aOff + j]; j--) {
                    a[aOff + j + 1] = a[aOff + j];
                    idx[j + 1] = idx[j];
                }
                a[aOff + j + 1] = value;
                idx[j + 1] = i;
            }
        }
    }

    /**
     * Applies the rising (type 1) or falling (type 2) half of a sine window
     * ({@code silk_apply_sine_window_FLP}).
     *
     * @param pxWin    receives the windowed signal
     * @param pxWinOff the index of the first output sample
     * @param px       the signal
     * @param pxOff    the index of the first input sample
     * @param winType  {@code 1} for a window from 0 to pi/2, {@code 2} for pi/2 to pi
     * @param length   the window length, a multiple of 4
     */
    static void applySineWindowFlp(float[] pxWin, int pxWinOff, float[] px, int pxOff, int winType, int length) {
        float s0, s1;
        var freq = PI / (length + 1);
        var c = 2.0f - freq * freq;
        if (winType < 2) {
            s0 = 0.0f;
            s1 = freq;
        } else {
            s0 = 1.0f;
            s1 = 0.5f * c;
        }
        for (var k = 0; k < length; k += 4) {
            pxWin[pxWinOff + k] = px[pxOff + k] * 0.5f * (s0 + s1);
            pxWin[pxWinOff + k + 1] = px[pxOff + k + 1] * s1;
            s0 = c * s1 - s0;
            pxWin[pxWinOff + k + 2] = px[pxOff + k + 2] * 0.5f * (s1 + s0);
            pxWin[pxWinOff + k + 3] = px[pxOff + k + 3] * s0;
            s1 = c * s0 - s1;
        }
    }

    /**
     * Computes autocorrelations on a warped frequency axis ({@code silk_warped_autocorrelation_FLP}).
     *
     * @param corr     receives {@code order + 1} correlations
     * @param input    the signal
     * @param inputOff the index of the first sample
     * @param warping  the warping coefficient
     * @param length   the number of samples
     * @param order    the correlation order, even
     * @param state    a work array of at least {@code order + 1} values, overwritten
     * @param c        a work array of at least {@code order + 1} values, overwritten
     */
    static void warpedAutocorrelationFlp(float[] corr, float[] input, int inputOff, float warping, int length, int order,
                                         double[] state, double[] c) {
        double tmp1, tmp2;
        Arrays.fill(state, 0, order + 1, 0);
        Arrays.fill(c, 0, order + 1, 0);
        for (var n = 0; n < length; n++) {
            tmp1 = input[inputOff + n];
            for (var i = 0; i < order; i += 2) {
                tmp2 = state[i] + warping * state[i + 1] - warping * tmp1;
                state[i] = tmp1;
                c[i] += state[0] * tmp1;
                tmp1 = state[i + 1] + warping * state[i + 2] - warping * tmp2;
                state[i + 1] = tmp2;
                c[i + 1] += state[0] * tmp2;
            }
            state[order] = tmp1;
            c[order] += state[0] * tmp1;
        }
        for (var i = 0; i < order + 1; i++) {
            corr[i] = (float) c[i];
        }
    }

    /**
     * Filters a signal with an LPC analysis filter starting from a zero state
     * ({@code silk_LPC_analysis_filter_FLP}).
     *
     * <p>The first {@code order} output samples are set to zero. Orders 6, 8, 10, 12 and 16 are
     * supported.
     *
     * @param rLpc     receives the residual
     * @param rLpcOff  the index of the first residual sample
     * @param predCoef the prediction coefficients
     * @param predOff  the index of the first coefficient
     * @param s        the signal
     * @param sOff     the index of the first sample
     * @param length   the number of samples
     * @param order    the LPC order
     */
    static void lpcAnalysisFilterFlp(float[] rLpc, int rLpcOff, float[] predCoef, int predOff, float[] s, int sOff,
                                     int length, int order) {
        SilkFlpKernels.INSTANCE.lpcAnalysisFilter(rLpc, rLpcOff, predCoef, predOff, s, sOff, length, order);
        for (var i = 0; i < order; i++) {
            rLpc[rLpcOff + i] = 0;
        }
    }

    /**
     * Filters stacked subframes with the long-term prediction analysis filter and scales them
     * by the inverse gains ({@code silk_LTP_analysis_filter_FLP}).
     *
     * @param ltpRes      receives {@code nbSubfr} blocks of {@code preLength + subfrLength} samples
     * @param x           the signal, with {@code preLength} preceding samples before {@code xOff}
     * @param xOff        the index of the first sample of the first subframe minus the preceding
     *                    samples
     * @param b           the LTP coefficients, {@code LTP_ORDER} per subframe
     * @param pitchL      the pitch lags per subframe
     * @param invGains    the inverse quantization gains per subframe
     * @param subfrLength the subframe length
     * @param nbSubfr     the number of subframes
     * @param preLength   the number of preceding samples per subframe
     */
    static void ltpAnalysisFilterFlp(float[] ltpRes, float[] x, int xOff, float[] b, int[] pitchL, float[] invGains,
                                     int subfrLength, int nbSubfr, int preLength) {
        var xPtr = xOff;
        var resPtr = 0;
        for (var k = 0; k < nbSubfr; k++) {
            var xLagPtr = xPtr - pitchL[k];
            var invGain = invGains[k];
            var b0 = b[k * LTP_ORDER];
            var b1 = b[k * LTP_ORDER + 1];
            var b2 = b[k * LTP_ORDER + 2];
            var b3 = b[k * LTP_ORDER + 3];
            var b4 = b[k * LTP_ORDER + 4];
            for (var i = 0; i < subfrLength + preLength; i++) {
                var v = x[xPtr + i];
                v -= b0 * x[xLagPtr + 2];
                v -= b1 * x[xLagPtr + 1];
                v -= b2 * x[xLagPtr];
                v -= b3 * x[xLagPtr - 1];
                v -= b4 * x[xLagPtr - 2];
                v *= invGain;
                ltpRes[resPtr + i] = v;
                xLagPtr++;
            }
            resPtr += subfrLength + preLength;
            xPtr += subfrLength;
        }
    }

    /**
     * Computes the residual energy of each subframe given the quantized LPC coefficients of the
     * two frame halves ({@code silk_residual_energy_FLP}).
     *
     * @param nrgs        receives the residual energy of each subframe
     * @param x           the signal, each subframe preceded by {@code lpcOrder} samples
     * @param a           the AR coefficients of each frame half
     * @param gains       the quantization gains
     * @param subfrLength the subframe length
     * @param nbSubfr     the number of subframes
     * @param lpcOrder    the LPC order
     * @param lpcRes      a scratch buffer of at least {@code (MAX_FRAME_LENGTH + MAX_NB_SUBFR *
     *                    MAX_LPC_ORDER) / 2} samples
     */
    static void residualEnergyFlp(float[] nrgs, float[] x, float[][] a, float[] gains, int subfrLength, int nbSubfr,
                                  int lpcOrder, float[] lpcRes) {
        var lpcResPtr = lpcOrder;
        var shift = lpcOrder + subfrLength;
        lpcAnalysisFilterFlp(lpcRes, 0, a[0], 0, x, 0, 2 * shift, lpcOrder);
        nrgs[0] = (float) (gains[0] * gains[0] * energyFlp(lpcRes, lpcResPtr, subfrLength));
        nrgs[1] = (float) (gains[1] * gains[1] * energyFlp(lpcRes, lpcResPtr + shift, subfrLength));
        if (nbSubfr == 4) {
            lpcAnalysisFilterFlp(lpcRes, 0, a[1], 0, x, 2 * shift, 2 * shift, lpcOrder);
            nrgs[2] = (float) (gains[2] * gains[2] * energyFlp(lpcRes, lpcResPtr, subfrLength));
            nrgs[3] = (float) (gains[3] * gains[3] * energyFlp(lpcRes, lpcResPtr + shift, subfrLength));
        }
    }

    /**
     * Computes the correlation vector {@code X' * t} of the lagged data matrix {@code X}
     * ({@code silk_corrVector_FLP}).
     *
     * @param x     the data, {@code l + order - 1} samples from {@code xOff}
     * @param xOff  the index of the first data sample
     * @param t     the target vector
     * @param tOff  the index of the first target sample
     * @param l     the vector length
     * @param order the number of lags
     * @param xt    receives the {@code order} correlations
     * @param xtOff the index of the first correlation
     */
    static void corrVectorFlp(float[] x, int xOff, float[] t, int tOff, int l, int order, float[] xt, int xtOff) {
        var ptr1 = xOff + order - 1;
        for (var lag = 0; lag < order; lag++) {
            xt[xtOff + lag] = (float) innerProductFlp(x, ptr1, t, tOff, l);
            ptr1--;
        }
    }

    /**
     * Computes the correlation matrix {@code X' * X} of the lagged data matrix {@code X}
     * ({@code silk_corrMatrix_FLP}).
     *
     * @param x     the data, {@code l + order - 1} samples from {@code xOff}
     * @param xOff  the index of the first data sample
     * @param l     the vector length
     * @param order the number of lags
     * @param xx    receives the {@code order x order} matrix, row-major
     * @param xxOff the index of the first matrix element
     */
    static void corrMatrixFlp(float[] x, int xOff, int l, int order, float[] xx, int xxOff) {
        int j, lag;
        double energy;
        var ptr1 = xOff + order - 1;
        energy = energyFlp(x, ptr1, l);
        xx[xxOff] = (float) energy;
        for (j = 1; j < order; j++) {
            energy += x[ptr1 - j] * x[ptr1 - j] - x[ptr1 + l - j] * x[ptr1 + l - j];
            xx[xxOff + j * order + j] = (float) energy;
        }
        var ptr2 = xOff + order - 2;
        for (lag = 1; lag < order; lag++) {
            energy = innerProductFlp(x, ptr1, x, ptr2, l);
            xx[xxOff + lag * order] = (float) energy;
            xx[xxOff + lag] = (float) energy;
            for (j = 1; j < order - lag; j++) {
                energy += x[ptr1 - j] * x[ptr2 - j] - x[ptr1 + l - j] * x[ptr2 + l - j];
                xx[xxOff + (lag + j) * order + j] = (float) energy;
                xx[xxOff + j * order + lag + j] = (float) energy;
            }
            ptr2--;
        }
    }
}
