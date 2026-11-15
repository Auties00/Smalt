/* Ports libopus 1.5.2 (dnn/freq.c, dnn/burg.c). Copyright Mozilla and Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.audio.opus.internal.celt.KissFft;
import com.github.auties00.smalt.audio.opus.internal.celt.KissFftState;

/**
 * Computes the spectral features of the LPCNet analysis: band energies of a 20 ms window, the Burg cepstrum,
 * the DCT and LPC coefficients from a cepstrum.
 *
 * <p>Instances own their scratch space and are not thread-safe. Arguments are not checked; values outside the
 * documented ranges give unspecified results.
 */
final class Freq {
    /**
     * The order of the LPC filters ({@code LPC_ORDER}).
     */
    static final int LPC_ORDER = 16;

    /**
     * The pre-emphasis coefficient ({@code PREEMPHASIS}).
     */
    static final float PREEMPHASIS = 0.85f;

    /**
     * The frame size at 16 kHz, 10 ms ({@code FRAME_SIZE}).
     */
    static final int FRAME_SIZE = 160;

    /**
     * The overlap of consecutive analysis windows ({@code OVERLAP_SIZE}).
     */
    static final int OVERLAP_SIZE = 160;

    /**
     * The delay of the features relative to the frame ({@code TRAINING_OFFSET}).
     */
    static final int TRAINING_OFFSET = 80;

    /**
     * The analysis window size ({@code WINDOW_SIZE}).
     */
    static final int WINDOW_SIZE = FRAME_SIZE + OVERLAP_SIZE;

    /**
     * The number of non-negative frequency bins ({@code FREQ_SIZE}).
     */
    static final int FREQ_SIZE = WINDOW_SIZE / 2 + 1;

    /**
     * The number of bands ({@code NB_BANDS}).
     */
    static final int NB_BANDS = 18;

    /**
     * The bins per 5 ms band unit ({@code WINDOW_SIZE_5MS}).
     */
    private static final int WINDOW_SIZE_5MS = 4;

    /**
     * The first band unit of each band ({@code eband5ms}).
     */
    private static final short[] EBAND_5MS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 34, 40};

    /**
     * The energy compensation of each band ({@code compensation}).
     */
    private static final float[] COMPENSATION = {
        0.8f, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, 0.666667f, 0.5f, 0.5f, 0.5f, 0.333333f, 0.25f, 0.25f, 0.2f,
        0.166667f, 0.173913f
    };

    /**
     * The 320-point FFT ({@code kfft}).
     */
    static final KissFftState KFFT = KissFftState.of(LpcnetTables.FFT_SIZE, LpcnetTables.FFT_SCALE,
            LpcnetTables.FFT_FACTORS, LpcnetTables.FFT_BITREV, LpcnetTables.FFT_TWIDDLES);

    /**
     * The FFT input, interleaved complex.
     */
    private final float[] fftIn = new float[2 * WINDOW_SIZE];

    /**
     * The FFT output, interleaved complex.
     */
    private final float[] fftOut = new float[2 * WINDOW_SIZE];

    /**
     * The FFT scratch.
     */
    private final float[] fftScratch = new float[2 * WINDOW_SIZE];

    /**
     * The spectrum of an LPC filter or of an autocorrelation, interleaved complex.
     */
    private final float[] spectrum = new float[2 * FREQ_SIZE];

    /**
     * The scratch of the Burg cepstrum and of the LPC computation.
     */
    private final float[] burgIn = new float[FRAME_SIZE];
    private final float[] burgLpc = new float[LPC_ORDER];
    private final float[] window = new float[WINDOW_SIZE];
    private final float[] bands = new float[NB_BANDS];
    private final float[] ly = new float[NB_BANDS];
    private final float[] xr = new float[FREQ_SIZE];
    private final float[] ac = new float[LPC_ORDER + 1];
    private final float[] rc = new float[LPC_ORDER];
    private final float[] tmp = new float[NB_BANDS];
    private final double[] cFirstRow = new double[LPC_ORDER];
    private final double[] cLastRow = new double[LPC_ORDER];
    private final double[] caf = new double[LPC_ORDER + 1];
    private final double[] cab = new double[LPC_ORDER + 1];
    private final double[] af = new double[LPC_ORDER];

    /**
     * Computes the energy of each band with triangular interpolation between band centers
     * ({@code lpcn_compute_band_energy}).
     *
     * @param bandE receives {@link #NB_BANDS} energies
     * @param X the {@link #FREQ_SIZE} bins, interleaved complex
     */
    static void computeBandEnergy(float[] bandE, float[] X) {
        for (var i = 0; i < NB_BANDS; i++) {
            bandE[i] = 0;
        }
        for (var i = 0; i < NB_BANDS - 1; i++) {
            var bandSize = (EBAND_5MS[i + 1] - EBAND_5MS[i]) * WINDOW_SIZE_5MS;
            for (var j = 0; j < bandSize; j++) {
                var frac = (float) j / bandSize;
                var k = EBAND_5MS[i] * WINDOW_SIZE_5MS + j;
                var t = X[2 * k] * X[2 * k];
                t += X[2 * k + 1] * X[2 * k + 1];
                bandE[i] += (1 - frac) * t;
                bandE[i + 1] += frac * t;
            }
        }
        bandE[0] *= 2;
        bandE[NB_BANDS - 1] *= 2;
    }

    /**
     * Computes the band energies of the inverse power spectrum ({@code compute_band_energy_inverse}).
     */
    private static void computeBandEnergyInverse(float[] bandE, float[] X) {
        for (var i = 0; i < NB_BANDS; i++) {
            bandE[i] = 0;
        }
        for (var i = 0; i < NB_BANDS - 1; i++) {
            var bandSize = (EBAND_5MS[i + 1] - EBAND_5MS[i]) * WINDOW_SIZE_5MS;
            for (var j = 0; j < bandSize; j++) {
                var frac = (float) j / bandSize;
                var k = EBAND_5MS[i] * WINDOW_SIZE_5MS + j;
                var t = X[2 * k] * X[2 * k];
                t += X[2 * k + 1] * X[2 * k + 1];
                t = (float) (1.f / (t + 1e-9));
                bandE[i] += (1 - frac) * t;
                bandE[i + 1] += frac * t;
            }
        }
        bandE[0] *= 2;
        bandE[NB_BANDS - 1] *= 2;
    }

    /**
     * Computes the LPC coefficients of an autocorrelation with the Levinson-Durbin recursion, stopping at a
     * 30 dB prediction gain ({@code lpcn_lpc}).
     *
     * @return the prediction error
     */
    private static float lpc(float[] lpc, float[] rc, float[] ac, int p) {
        var error = ac[0];
        for (var i = 0; i < p; i++) {
            lpc[i] = 0;
            rc[i] = 0;
        }
        if (ac[0] != 0) {
            for (var i = 0; i < p; i++) {
                var rr = 0f;
                for (var j = 0; j < i; j++) {
                    rr += lpc[j] * ac[i - j];
                }
                rr += ac[i + 1];
                var r = -rr / error;
                rc[i] = r;
                lpc[i] = r;
                for (var j = 0; j < (i + 1) >> 1; j++) {
                    var tmp1 = lpc[j];
                    var tmp2 = lpc[i - 1 - j];
                    lpc[j] = tmp1 + r * tmp2;
                    lpc[i - 1 - j] = tmp2 + r * tmp1;
                }
                error = error - (r * r) * error;
                if (error < .001f * ac[0]) {
                    break;
                }
            }
        }
        return error;
    }

    /**
     * Computes the forward FFT of a real window and keeps the non-negative frequencies
     * ({@code forward_transform}).
     *
     * @param out receives {@link #FREQ_SIZE} bins, interleaved complex
     * @param in the {@link #WINDOW_SIZE} samples
     * @param inOffset the index of the first sample
     */
    void forwardTransform(float[] out, float[] in, int inOffset) {
        for (var i = 0; i < WINDOW_SIZE; i++) {
            fftIn[2 * i] = in[inOffset + i];
            fftIn[2 * i + 1] = 0;
        }
        KissFft.opusFft(KFFT, fftIn, fftOut, fftScratch);
        System.arraycopy(fftOut, 0, out, 0, 2 * FREQ_SIZE);
    }

    /**
     * Computes the real inverse FFT of a Hermitian spectrum ({@code inverse_transform}).
     */
    private void inverseTransform(float[] out, float[] in) {
        System.arraycopy(in, 0, fftIn, 0, 2 * FREQ_SIZE);
        for (var i = FREQ_SIZE; i < WINDOW_SIZE; i++) {
            fftIn[2 * i] = fftIn[2 * (WINDOW_SIZE - i)];
            fftIn[2 * i + 1] = -fftIn[2 * (WINDOW_SIZE - i) + 1];
        }
        KissFft.opusFft(KFFT, fftIn, fftOut, fftScratch);
        out[0] = WINDOW_SIZE * fftOut[0];
        for (var i = 1; i < WINDOW_SIZE; i++) {
            out[i] = WINDOW_SIZE * fftOut[2 * (WINDOW_SIZE - i)];
        }
    }

    /**
     * Computes the cepstrum of the Burg LPC envelope of {@code len} samples ({@code compute_burg_cepstrum}).
     */
    private void computeBurgCepstrum(float[] pcm, int pcmOffset, float[] burgCepstrum, int cepstrumOffset, int len,
                                     int order) {
        var logMax = -2f;
        var follow = -2f;
        for (var i = 0; i < len - 1; i++) {
            burgIn[i] = pcm[pcmOffset + i + 1] - PREEMPHASIS * pcm[pcmOffset + i];
        }
        var g = burgAnalysis(burgLpc, burgIn, 1e-3f, len - 1, 1, order);
        g /= len - 2 * (order - 1);
        for (var i = 0; i < WINDOW_SIZE; i++) {
            window[i] = 0;
        }
        window[0] = 1;
        for (var i = 0; i < order; i++) {
            window[i + 1] = (float) (-burgLpc[i] * StrictMath.pow(.995, i + 1));
        }
        forwardTransform(spectrum, window, 0);
        computeBandEnergyInverse(bands, spectrum);
        var norm = (double) (1.f / ((float) WINDOW_SIZE * WINDOW_SIZE * WINDOW_SIZE));
        for (var i = 0; i < NB_BANDS; i++) {
            bands[i] = (float) (bands[i] * (.45 * g * norm));
        }
        for (var i = 0; i < NB_BANDS; i++) {
            var y = (float) StrictMath.log10(1e-2 + bands[i]);
            // follow - 2.5 is a double expression in the C source, so both maxima compare in double.
            double f = follow - 2.5;
            var inner = f > y ? f : y;
            float floor = logMax - 8;
            y = (float) (floor > inner ? floor : inner);
            ly[i] = y;
            logMax = logMax > y ? logMax : y;
            follow = (float) (f > y ? f : y);
        }
        dct(burgCepstrum, cepstrumOffset, ly, 0);
        burgCepstrum[cepstrumOffset] += -4;
    }

    /**
     * Computes the Burg cepstra of the two halves of a frame and stores their mean and difference
     * ({@code burg_cepstral_analysis}).
     *
     * @param ceps receives {@code 2 * NB_BANDS} values
     * @param x the {@link #FRAME_SIZE} samples
     */
    void burgCepstralAnalysis(float[] ceps, float[] x) {
        computeBurgCepstrum(x, 0, ceps, 0, FRAME_SIZE / 2, LPC_ORDER);
        computeBurgCepstrum(x, FRAME_SIZE / 2, ceps, NB_BANDS, FRAME_SIZE / 2, LPC_ORDER);
        for (var i = 0; i < NB_BANDS; i++) {
            var c0 = ceps[i];
            var c1 = ceps[NB_BANDS + i];
            ceps[i] = (float) (.5 * (c0 + c1));
            ceps[NB_BANDS + i] = c0 - c1;
        }
    }

    /**
     * Computes the orthonormal DCT-II of the band values ({@code dct}).
     *
     * @param out receives {@link #NB_BANDS} coefficients
     * @param outOffset the index of the first coefficient
     * @param in the {@link #NB_BANDS} band values
     * @param inOffset the index of the first band value
     */
    static void dct(float[] out, int outOffset, float[] in, int inOffset) {
        for (var i = 0; i < NB_BANDS; i++) {
            var sum = 0f;
            for (var j = 0; j < NB_BANDS; j++) {
                sum += in[inOffset + j] * LpcnetTables.DCT_TABLE[j * NB_BANDS + i];
            }
            out[outOffset + i] = (float) (sum * StrictMath.sqrt(2. / NB_BANDS));
        }
    }

    /**
     * Computes the inverse of {@link #dct} ({@code idct}).
     */
    private static void idct(float[] out, float[] in) {
        for (var i = 0; i < NB_BANDS; i++) {
            var sum = 0f;
            for (var j = 0; j < NB_BANDS; j++) {
                sum += in[j] * LpcnetTables.DCT_TABLE[i * NB_BANDS + j];
            }
            out[i] = (float) (sum * StrictMath.sqrt(2. / NB_BANDS));
        }
    }

    /**
     * Computes LPC coefficients from band energies through the autocorrelation of the interpolated power
     * spectrum ({@code lpc_from_bands}).
     */
    private float lpcFromBands(float[] lpc, float[] ex) {
        for (var i = 0; i < NB_BANDS - 1; i++) {
            var bandSize = (EBAND_5MS[i + 1] - EBAND_5MS[i]) * WINDOW_SIZE_5MS;
            for (var j = 0; j < bandSize; j++) {
                var frac = (float) j / bandSize;
                xr[EBAND_5MS[i] * WINDOW_SIZE_5MS + j] = (1 - frac) * ex[i] + frac * ex[i + 1];
            }
        }
        xr[FREQ_SIZE - 1] = 0;
        for (var i = 0; i < FREQ_SIZE; i++) {
            spectrum[2 * i] = xr[i];
            spectrum[2 * i + 1] = 0;
        }
        inverseTransform(window, spectrum);
        System.arraycopy(window, 0, ac, 0, LPC_ORDER + 1);
        // -40 dB noise floor; 320 / 12 is an integer division in the C source.
        ac[0] = (float) (ac[0] + (ac[0] * 1e-4 + (320 / 12) / 38.));
        for (var i = 1; i < LPC_ORDER + 1; i++) {
            ac[i] = (float) (ac[i] * (1 - 6e-5 * i * i));
        }
        return lpc(lpc, rc, ac, LPC_ORDER);
    }

    /**
     * Computes LPC coefficients from a cepstrum ({@code lpc_from_cepstrum}).
     *
     * @param lpc receives {@link #LPC_ORDER} coefficients
     * @param cepstrum the {@link #NB_BANDS} cepstral coefficients
     * @param cepstrumOffset the index of the first coefficient
     * @return the prediction error
     */
    float lpcFromCepstrum(float[] lpc, float[] cepstrum, int cepstrumOffset) {
        System.arraycopy(cepstrum, cepstrumOffset, tmp, 0, NB_BANDS);
        tmp[0] += 4;
        idct(bands, tmp);
        for (var i = 0; i < NB_BANDS; i++) {
            bands[i] = (float) (StrictMath.pow(10.f, bands[i]) * COMPENSATION[i]);
        }
        return lpcFromBands(lpc, bands);
    }

    /**
     * Applies the analysis window to both ends of a window of samples ({@code apply_window}).
     *
     * @param x the {@link #WINDOW_SIZE} samples, windowed in place
     */
    static void applyWindow(float[] x) {
        for (var i = 0; i < OVERLAP_SIZE; i++) {
            x[i] *= LpcnetTables.HALF_WINDOW[i];
            x[WINDOW_SIZE - 1 - i] *= LpcnetTables.HALF_WINDOW[i];
        }
    }

    /**
     * Returns the energy of a signal accumulated in double precision in groups of four
     * ({@code silk_energy_FLP}).
     */
    private static double energy(float[] data, int offset, int size) {
        var result = 0.0;
        var i = 0;
        for (; i < size - 3; i += 4) {
            result += data[offset + i] * (double) data[offset + i]
                    + data[offset + i + 1] * (double) data[offset + i + 1]
                    + data[offset + i + 2] * (double) data[offset + i + 2]
                    + data[offset + i + 3] * (double) data[offset + i + 3];
        }
        for (; i < size; i++) {
            result += data[offset + i] * (double) data[offset + i];
        }
        return result;
    }

    /**
     * Returns the inner product of two signals accumulated in double precision in groups of four
     * ({@code silk_inner_product_FLP}).
     */
    private static double innerProduct(float[] data, int offset1, int offset2, int size) {
        var result = 0.0;
        var i = 0;
        for (; i < size - 3; i += 4) {
            result += data[offset1 + i] * (double) data[offset2 + i]
                    + data[offset1 + i + 1] * (double) data[offset2 + i + 1]
                    + data[offset1 + i + 2] * (double) data[offset2 + i + 2]
                    + data[offset1 + i + 3] * (double) data[offset2 + i + 3];
        }
        for (; i < size; i++) {
            result += data[offset1 + i] * (double) data[offset2 + i];
        }
        return result;
    }

    /**
     * Computes prediction coefficients with Burg's method ({@code silk_burg_analysis}).
     *
     * @param a receives {@code d} prediction coefficients
     * @param x the input, {@code nbSubfr} subframes of {@code subfrLength} samples
     * @param minInvGain the minimum inverse prediction gain
     * @param subfrLength the subframe length, including the {@code d} preceding samples
     * @param nbSubfr the number of subframes
     * @param d the order
     * @return the residual energy
     */
    private float burgAnalysis(float[] a, float[] x, float minInvGain, int subfrLength, int nbSubfr, int d) {
        final var condFac = 1e-5f;
        var c0 = energy(x, 0, nbSubfr * subfrLength);
        java.util.Arrays.fill(cFirstRow, 0);
        for (var s = 0; s < nbSubfr; s++) {
            var p = s * subfrLength;
            for (var n = 1; n < d + 1; n++) {
                cFirstRow[n - 1] += innerProduct(x, p, p + n, subfrLength - n);
            }
        }
        System.arraycopy(cFirstRow, 0, cLastRow, 0, LPC_ORDER);
        cab[0] = caf[0] = c0 + condFac * c0 + 1e-9f;
        double invGain = 1.0f;
        var reachedMaxGain = false;
        for (var n = 0; n < d; n++) {
            for (var s = 0; s < nbSubfr; s++) {
                var p = s * subfrLength;
                double tmp1 = x[p + n];
                double tmp2 = x[p + subfrLength - n - 1];
                for (var k = 0; k < n; k++) {
                    cFirstRow[k] -= x[p + n] * x[p + n - k - 1];
                    cLastRow[k] -= x[p + subfrLength - n - 1] * x[p + subfrLength - n + k];
                    var atmp = af[k];
                    tmp1 += x[p + n - k - 1] * atmp;
                    tmp2 += x[p + subfrLength - n + k] * atmp;
                }
                for (var k = 0; k <= n; k++) {
                    caf[k] -= tmp1 * x[p + n - k];
                    cab[k] -= tmp2 * x[p + subfrLength - n + k - 1];
                }
            }
            var tmp1 = cFirstRow[n];
            var tmp2 = cLastRow[n];
            for (var k = 0; k < n; k++) {
                var atmp = af[k];
                tmp1 += cLastRow[n - k - 1] * atmp;
                tmp2 += cFirstRow[n - k - 1] * atmp;
            }
            caf[n + 1] = tmp1;
            cab[n + 1] = tmp2;
            var num = cab[n + 1];
            var nrgB = cab[0];
            var nrgF = caf[0];
            for (var k = 0; k < n; k++) {
                var atmp = af[k];
                num += cab[n - k] * atmp;
                nrgB += cab[k + 1] * atmp;
                nrgF += caf[k + 1] * atmp;
            }
            var rcoef = -2.0 * num / (nrgF + nrgB);
            tmp1 = invGain * (1.0 - rcoef * rcoef);
            if (tmp1 <= minInvGain) {
                rcoef = StrictMath.sqrt(1.0 - minInvGain / invGain);
                if (num > 0) {
                    rcoef = -rcoef;
                }
                invGain = minInvGain;
                reachedMaxGain = true;
            } else {
                invGain = tmp1;
            }
            for (var k = 0; k < (n + 1) >> 1; k++) {
                var t1 = af[k];
                var t2 = af[n - k - 1];
                af[k] = t1 + rcoef * t2;
                af[n - k - 1] = t2 + rcoef * t1;
            }
            af[n] = rcoef;
            if (reachedMaxGain) {
                for (var k = n + 1; k < d; k++) {
                    af[k] = 0.0;
                }
                break;
            }
            for (var k = 0; k <= n + 1; k++) {
                var t1 = caf[k];
                caf[k] += rcoef * cab[n - k + 1];
                cab[n - k + 1] += rcoef * t1;
            }
        }
        double nrgF;
        if (reachedMaxGain) {
            for (var k = 0; k < d; k++) {
                a[k] = (float) (-af[k]);
            }
            for (var s = 0; s < nbSubfr; s++) {
                c0 -= energy(x, s * subfrLength, d);
            }
            nrgF = c0 * invGain;
        } else {
            nrgF = caf[0];
            var t1 = 1.0;
            for (var k = 0; k < d; k++) {
                var atmp = af[k];
                nrgF += caf[k + 1] * atmp;
                t1 += atmp * atmp;
                a[k] = (float) (-atmp);
            }
            nrgF -= condFac * c0 * t1;
        }
        var result = (float) nrgF;
        return 0 > result ? 0 : result;
    }
}
