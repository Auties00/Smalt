/* Ports libopus 1.5.2 (celt/celt_lpc.c, celt/pitch.h). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Computes the linear-prediction helpers of CELT: autocorrelation, Levinson-Durbin recursion,
 * and FIR and IIR filtering, used by the pitch analysis and the packet-loss concealment.
 *
 * @implNote This implementation unrolls with the {@code xcorr_kernel_c} of the reference, keeping its exact
 * accumulation order.
 */
public final class CeltLpc {
    /**
     * The order of the concealment LPC ({@code CELT_LPC_ORDER}).
     */
    public static final int CELT_LPC_ORDER = 24;

    private CeltLpc() {
        throw new AssertionError();
    }

    /**
     * Computes LPC coefficients from an autocorrelation with the Levinson-Durbin recursion,
     * stopping early at 30 dB of prediction gain ({@code _celt_lpc}).
     *
     * @param lpc    receives {@code p} coefficients
     * @param lpcOff the index of the first coefficient
     * @param ac     the autocorrelation, {@code p + 1} values
     * @param p      the order
     */
    public static void celtLpc(float[] lpc, int lpcOff, float[] ac, int p) {
        float r;
        var error = ac[0];
        java.util.Arrays.fill(lpc, lpcOff, lpcOff + p, 0);
        if (ac[0] > 1e-10f) {
            for (var i = 0; i < p; i++) {
                var rr = 0f;
                for (var j = 0; j < i; j++) {
                    rr += lpc[lpcOff + j] * ac[i - j];
                }
                rr += ac[i + 1];
                r = -(rr / error);
                lpc[lpcOff + i] = r;
                for (var j = 0; j < (i + 1) >> 1; j++) {
                    var tmp1 = lpc[lpcOff + j];
                    var tmp2 = lpc[lpcOff + i - 1 - j];
                    lpc[lpcOff + j] = tmp1 + r * tmp2;
                    lpc[lpcOff + i - 1 - j] = tmp2 + r * tmp1;
                }
                error = error - (r * r) * error;
                if (error <= .001f * ac[0]) {
                    break;
                }
            }
        }
    }

    /**
     * Accumulates four correlations at consecutive lags with the exact operation order of the
     * reference unrolled kernel ({@code xcorr_kernel_c}).
     *
     * @param x    the filter taps
     * @param xOff the index of the first tap
     * @param y    the signal
     * @param yOff the index of the first signal sample
     * @param sum  the four running sums, updated in place
     * @param len  the number of taps, at least 3
     */
    static void xcorrKernel(float[] x, int xOff, float[] y, int yOff, float[] sum, int len) {
        float y0, y1, y2, y3;
        var s0 = sum[0];
        var s1 = sum[1];
        var s2 = sum[2];
        var s3 = sum[3];
        y3 = 0;
        y0 = y[yOff++];
        y1 = y[yOff++];
        y2 = y[yOff++];
        int j;
        for (j = 0; j < len - 3; j += 4) {
            float tmp;
            tmp = x[xOff++];
            y3 = y[yOff++];
            s0 = s0 + tmp * y0;
            s1 = s1 + tmp * y1;
            s2 = s2 + tmp * y2;
            s3 = s3 + tmp * y3;
            tmp = x[xOff++];
            y0 = y[yOff++];
            s0 = s0 + tmp * y1;
            s1 = s1 + tmp * y2;
            s2 = s2 + tmp * y3;
            s3 = s3 + tmp * y0;
            tmp = x[xOff++];
            y1 = y[yOff++];
            s0 = s0 + tmp * y2;
            s1 = s1 + tmp * y3;
            s2 = s2 + tmp * y0;
            s3 = s3 + tmp * y1;
            tmp = x[xOff++];
            y2 = y[yOff++];
            s0 = s0 + tmp * y3;
            s1 = s1 + tmp * y0;
            s2 = s2 + tmp * y1;
            s3 = s3 + tmp * y2;
        }
        if (j++ < len) {
            var tmp = x[xOff++];
            y3 = y[yOff++];
            s0 = s0 + tmp * y0;
            s1 = s1 + tmp * y1;
            s2 = s2 + tmp * y2;
            s3 = s3 + tmp * y3;
        }
        if (j++ < len) {
            var tmp = x[xOff++];
            y0 = y[yOff++];
            s0 = s0 + tmp * y1;
            s1 = s1 + tmp * y2;
            s2 = s2 + tmp * y3;
            s3 = s3 + tmp * y0;
        }
        if (j < len) {
            var tmp = x[xOff];
            y1 = y[yOff];
            s0 = s0 + tmp * y2;
            s1 = s1 + tmp * y3;
            s2 = s2 + tmp * y0;
            s3 = s3 + tmp * y1;
        }
        sum[0] = s0;
        sum[1] = s1;
        sum[2] = s2;
        sum[3] = s3;
    }

    /**
     * Filters {@code x} with the FIR filter {@code 1 + num(z)}; {@code x} must provide
     * {@code ord} samples of history before {@code xOff} ({@code celt_fir_c}).
     *
     * @param x    the input
     * @param xOff the index of the first input sample
     * @param num  the filter coefficients
     * @param nOff the index of the first coefficient
     * @param y    receives the output; must not overlap the input
     * @param yOff the index of the first output sample
     * @param N    the number of samples
     * @param ord  the filter order
     */
    public static void celtFir(float[] x, int xOff, float[] num, int nOff, float[] y, int yOff, int N, int ord) {
        var rnum = new float[ord];
        var sum = new float[4];
        for (var i = 0; i < ord; i++) {
            rnum[i] = num[nOff + ord - i - 1];
        }
        int i;
        for (i = 0; i < N - 3; i += 4) {
            sum[0] = x[xOff + i];
            sum[1] = x[xOff + i + 1];
            sum[2] = x[xOff + i + 2];
            sum[3] = x[xOff + i + 3];
            xcorrKernel(rnum, 0, x, xOff + i - ord, sum, ord);
            y[yOff + i] = sum[0];
            y[yOff + i + 1] = sum[1];
            y[yOff + i + 2] = sum[2];
            y[yOff + i + 3] = sum[3];
        }
        for (; i < N; i++) {
            var s = x[xOff + i];
            for (var j = 0; j < ord; j++) {
                s = s + rnum[j] * x[xOff + i + j - ord];
            }
            y[yOff + i] = s;
        }
    }

    /**
     * Filters {@code x} with the all-pole filter {@code 1 / (1 + den(z))}, updating its memory
     * ({@code celt_iir}).
     *
     * @param x    the input
     * @param xOff the index of the first input sample
     * @param den  the filter coefficients, {@code ord} of them, {@code ord} a multiple of 4
     * @param dOff the index of the first coefficient
     * @param y    receives the output; may be the same region as the input
     * @param yOff the index of the first output sample
     * @param N    the number of samples
     * @param ord  the filter order
     * @param mem  the filter memory, most recent output first; updated in place
     */
    public static void celtIir(float[] x, int xOff, float[] den, int dOff, float[] y, int yOff, int N, int ord,
                               float[] mem) {
        var rden = new float[ord];
        var yy = new float[N + ord];
        var sum = new float[4];
        int i, j;
        for (i = 0; i < ord; i++) {
            rden[i] = den[dOff + ord - i - 1];
        }
        for (i = 0; i < ord; i++) {
            yy[i] = -mem[ord - i - 1];
        }
        for (; i < N + ord; i++) {
            yy[i] = 0;
        }
        for (i = 0; i < N - 3; i += 4) {
            sum[0] = x[xOff + i];
            sum[1] = x[xOff + i + 1];
            sum[2] = x[xOff + i + 2];
            sum[3] = x[xOff + i + 3];
            xcorrKernel(rden, 0, yy, i, sum, ord);
            yy[i + ord] = -sum[0];
            y[yOff + i] = sum[0];
            sum[1] = sum[1] + yy[i + ord] * den[dOff];
            yy[i + ord + 1] = -sum[1];
            y[yOff + i + 1] = sum[1];
            sum[2] = sum[2] + yy[i + ord + 1] * den[dOff];
            sum[2] = sum[2] + yy[i + ord] * den[dOff + 1];
            yy[i + ord + 2] = -sum[2];
            y[yOff + i + 2] = sum[2];
            sum[3] = sum[3] + yy[i + ord + 2] * den[dOff];
            sum[3] = sum[3] + yy[i + ord + 1] * den[dOff + 1];
            sum[3] = sum[3] + yy[i + ord] * den[dOff + 2];
            yy[i + ord + 3] = -sum[3];
            y[yOff + i + 3] = sum[3];
        }
        for (; i < N; i++) {
            var s = x[xOff + i];
            for (j = 0; j < ord; j++) {
                s -= rden[j] * yy[i + j];
            }
            yy[i + ord] = s;
            y[yOff + i] = s;
        }
        for (i = 0; i < ord; i++) {
            mem[i] = y[yOff + N - i - 1];
        }
    }

    /**
     * Computes the autocorrelation of a signal, optionally windowed at both ends
     * ({@code _celt_autocorr}).
     *
     * @param x       the signal
     * @param xOff    the index of the first sample
     * @param ac      receives {@code lag + 1} autocorrelation values
     * @param window  the window applied to the first and last {@code overlap} samples, or
     *                {@code null} when {@code overlap} is zero
     * @param overlap the window length
     * @param lag     the largest lag
     * @param n       the number of samples
     * @return the scaling shift, always 0 in the float build
     */
    public static int celtAutocorr(float[] x, int xOff, float[] ac, float[] window, int overlap, int lag, int n) {
        float d;
        int i, k;
        var fastN = n - lag;
        float[] xptr;
        int xpOff;
        if (overlap == 0) {
            xptr = x;
            xpOff = xOff;
        } else {
            var xx = new float[n];
            for (i = 0; i < n; i++) {
                xx[i] = x[xOff + i];
            }
            for (i = 0; i < overlap; i++) {
                xx[i] = x[xOff + i] * window[i];
                xx[n - i - 1] = x[xOff + n - i - 1] * window[i];
            }
            xptr = xx;
            xpOff = 0;
        }
        Pitch.celtPitchXcorr(xptr, xpOff, xptr, xpOff, ac, 0, fastN, lag + 1);
        for (k = 0; k <= lag; k++) {
            for (i = k + fastN, d = 0; i < n; i++) {
                d = d + xptr[xpOff + i] * xptr[xpOff + i - k];
            }
            ac[k] += d;
        }
        return 0;
    }
}
