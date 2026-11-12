/* Ports hvxcCommon.c of the ISO/IEC 14496-3 reference software (MPEG-4 Audio, subpart 2, HVXC). Copyright Sony
 * Corporation; ISO/IEC 14496-3 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

/**
 * The signal processing primitives of the HVXC decoder: the radix-2 FFT, the LSP to LPC conversion and the
 * analysis windows.
 *
 * <p>The arithmetic follows the reference decoder's mix of single and double precision. Arguments are not checked;
 * values outside the documented ranges give unspecified results.
 */
final class HvxcDsp {
    /**
     * The analysis window length.
     */
    static final int SAMPLE = 256;

    /**
     * The frame length, 20 ms at 8 kHz.
     */
    static final int FRAME = 160;

    /**
     * The LPC order.
     */
    static final int ORDER = 10;

    /**
     * The overlap of the noise windows.
     */
    static final int OVERLAP = 96;

    /**
     * The look-ahead of the low delay mode, in samples.
     */
    static final int LD_LEN = 20;

    /**
     * Prevents instantiation.
     */
    private HvxcDsp() {
    }

    /**
     * Rounds to the nearest integer, halves upward ({@code IPC_inint}).
     */
    static int round(float x) {
        return (int) Math.floor(x + 0.5);
    }

    /**
     * Transforms in place ({@code IPC_fft}), a decimation in frequency radix-2 FFT with the bit reversal last.
     *
     * @param x the real parts
     * @param y the imaginary parts
     * @param dim the base 2 logarithm of the size
     */
    static void fft(float[] x, float[] y, int dim) {
        var m = 1 << dim;
        var q = m;
        var dth = (float) (2.0 * Math.PI / (float) m);
        for (var i = 1; i <= dim; i++) {
            var p = q;
            q /= 2;
            var th = 0.0f;
            for (var j = 1; j <= q; j++) {
                var px = (float) StrictMath.cos(th);
                var py = (float) StrictMath.sin(th);
                th += dth;
                for (var k = p; k <= m; k += p) {
                    var ptr1 = k - p + j - 1;
                    var ptr2 = ptr1 + q;
                    var a = x[ptr1] - x[ptr2];
                    var b = y[ptr1] - y[ptr2];
                    x[ptr1] = x[ptr1] + x[ptr2];
                    y[ptr1] = y[ptr1] + y[ptr2];
                    x[ptr2] = px * a + py * b;
                    y[ptr2] = px * b - py * a;
                }
            }
            dth *= 2.0f;
        }
        var j = 1;
        var m1 = m - 1;
        var m2 = m / 2;
        for (var i = 1; i <= m1; i++) {
            if (i < j) {
                var a = x[j - 1];
                var b = y[j - 1];
                x[j - 1] = x[i - 1];
                y[j - 1] = y[i - 1];
                x[i - 1] = a;
                y[i - 1] = b;
            }
            var k = m2;
            while (k < j) {
                j -= k;
                k /= 2;
            }
            j += k;
        }
    }

    /**
     * Inverse transforms in place ({@code IPC_ifft}), scaled by the reciprocal of the size.
     */
    static void ifft(float[] x, float[] y, int dim) {
        var m = 1 << dim;
        for (var i = 0; i < m; i++) {
            y[i] = -y[i];
        }
        fft(x, y, dim);
        for (var i = 0; i < m; i++) {
            x[i] = x[i] / (float) m;
            y[i] = -y[i] / (float) m;
        }
    }

    /**
     * Converts LSPs to LPC coefficients ({@code IPC_lsp_lpc}).
     *
     * @param lsf the LSPs in {@code lsf[1..10]}, as fractions of the sampling frequency
     * @param alpha receives the coefficients in {@code alpha[1..10]}, of the polynomial
     * {@code 1 - sum alpha[i] z^-i}
     * @param p work space of 11 values
     * @param q work space of 11 values
     * @param c work space of 11 values
     */
    static void lspToLpc(float[] lsf, float[] alpha, double[] p, double[] q, double[] c) {
        for (var i = 1; i < 11; i++) {
            c[i] = -2.0 * StrictMath.cos(2.0 * Math.PI * lsf[i]);
        }
        for (var j = 3; j <= 10; j++) {
            p[j] = 0.0;
        }
        p[0] = 1.0;
        p[1] = c[1];
        p[2] = 1.0;
        for (var n = 1; n <= 4; n++) {
            for (var j = 2 + n * 2; j >= 0; j--) {
                if (j - 2 >= 0) {
                    p[j] = p[j - 2] + p[j - 1] * c[2 * n + 1] + p[j];
                } else if (j - 1 >= 0) {
                    p[j] = p[j - 1] * c[2 * n + 1] + p[j];
                }
            }
        }
        for (var i = 5; i > 0; i--) {
            p[i] += p[i - 1];
        }
        for (var j = 3; j <= 10; j++) {
            q[j] = 0.0;
        }
        q[0] = 1.0;
        q[1] = c[2];
        q[2] = 1.0;
        for (var n = 1; n <= 4; n++) {
            for (var j = 2 + n * 2; j >= 0; j--) {
                if (j - 2 >= 0) {
                    q[j] = q[j - 2] + q[j - 1] * c[2 * n + 2] + q[j];
                } else if (j - 1 >= 0) {
                    q[j] = q[j - 1] * c[2 * n + 2] + q[j];
                }
            }
        }
        for (var i = 5; i > 0; i--) {
            q[i] -= q[i - 1];
        }
        for (var i = 1; i < 6; i++) {
            alpha[i] = (float) (-0.5 * (p[i] + q[i]));
        }
        for (var i = 6; i < 11; i++) {
            alpha[i] = (float) (-0.5 * (p[11 - i] - q[11 - i]));
        }
    }

    /**
     * Returns the Hamming window of 256 samples normalized to unit energy ({@code IPC_makeCoefDec}).
     *
     * @param lowDelay whether to zero the first and the last eight samples before normalizing, as the low delay mode
     * does
     */
    static float[] window(boolean lowDelay) {
        var coef = new float[SAMPLE];
        for (var i = 0; i < SAMPLE; i++) {
            coef[i] = (float) (0.54 - 0.46 * StrictMath.cos(2 * Math.PI * i / (SAMPLE - 1)));
        }
        if (lowDelay) {
            for (var i = 0; i < 8; i++) {
                coef[i] = 0.0f;
                coef[SAMPLE - 1 - i] = 0.0f;
            }
        }
        var energy = 0.0f;
        for (var i = 0; i < SAMPLE; i++) {
            energy = energy + coef[i] * coef[i];
        }
        for (var i = 0; i < SAMPLE; i++) {
            coef[i] = (float) (coef[i] / StrictMath.sqrt(energy));
        }
        return coef;
    }

    /**
     * Wraps a phase into {@code [-pi, pi]} ({@code modu2pai}).
     */
    static float wrapPhase(float x) {
        if (x >= 0.0f) {
            var y = (float) (x - StrictMath.floor(x / (2 * Math.PI)) * (2 * Math.PI));
            if (y > Math.PI) {
                y = (float) (y - 2 * Math.PI);
            }
            return y;
        }
        var y = (float) (x - StrictMath.ceil(x / (2 * Math.PI)) * (2 * Math.PI));
        if (y < -Math.PI) {
            y = (float) (y + 2 * Math.PI);
        }
        return y;
    }
}
