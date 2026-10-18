package com.github.auties00.smalt.audio.flac;

/**
 * Implements {@link FlacEncoderKernel} with plain Java arithmetic; the reference the Vector API
 * implementation must match bit for bit.
 *
 * @implNote This implementation follows libFLAC's {@code lpc.c} and {@code fixed.c}
 * (BSD-3-Clause): the autocorrelation loop of {@code FLAC__lpc_compute_autocorrelation}, the
 * residual filters of {@code FLAC__lpc_compute_residual_from_qlp_coefficients} and its wide and
 * residual-limiting variants, and the error sums of {@code FLAC__fixed_compute_best_predictor}.
 */
final class ScalarFlacEncoderKernel implements FlacEncoderKernel {
    /**
     * The magnitude a residual sample must stay below.
     */
    private static final long RESIDUAL_LIMIT = 1L << 31;

    /**
     * Constructs the kernel.
     */
    ScalarFlacEncoderKernel() {
    }

    @Override
    public void window(int[] signal, int signalOffset, double[] window, int windowOffset, double[] out, int outOffset, int count) {
        for (var i = 0; i < count; i++) {
            out[outOffset + i] = signal[signalOffset + i] * window[windowOffset + i];
        }
    }

    @Override
    public void autocorrelation(double[] data, int length, int lags, double[] autoc) {
        for (var c = 0; c < lags; c++) {
            autoc[c] = 0.0;
        }
        for (var s = 0; s < length; s++) {
            var d = data[s];
            for (var c = 0; c < lags; c++) {
                autoc[c] += d * data[s + c];
            }
        }
    }

    @Override
    public void lpcResidual(int[] signal, int length, int[] coefficients, int order, int shift, int[] residual) {
        for (var i = order; i < length; i++) {
            var sum = 0;
            for (var j = 0; j < order; j++) {
                sum += coefficients[j] * signal[i - 1 - j];
            }
            residual[i] = signal[i] - (sum >> shift);
        }
    }

    @Override
    public boolean lpcResidualWide(int[] signal, int length, int[] coefficients, int order, int shift, int[] residual) {
        for (var i = order; i < length; i++) {
            var sum = 0L;
            for (var j = 0; j < order; j++) {
                sum += (long) coefficients[j] * signal[i - 1 - j];
            }
            var value = signal[i] - (sum >> shift);
            if (value <= -RESIDUAL_LIMIT || value >= RESIDUAL_LIMIT) {
                return false;
            }
            residual[i] = (int) value;
        }
        return true;
    }

    @Override
    public void fixedResidual(int[] signal, int length, int order, int[] residual) {
        switch (order) {
            case 0 -> System.arraycopy(signal, 0, residual, 0, length);
            case 1 -> {
                for (var i = 1; i < length; i++) {
                    residual[i] = signal[i] - signal[i - 1];
                }
            }
            case 2 -> {
                for (var i = 2; i < length; i++) {
                    residual[i] = signal[i] - 2 * signal[i - 1] + signal[i - 2];
                }
            }
            case 3 -> {
                for (var i = 3; i < length; i++) {
                    residual[i] = signal[i] - 3 * signal[i - 1] + 3 * signal[i - 2] - signal[i - 3];
                }
            }
            default -> {
                for (var i = 4; i < length; i++) {
                    residual[i] = signal[i] - 4 * signal[i - 1] + 6 * signal[i - 2] - 4 * signal[i - 3] + signal[i - 4];
                }
            }
        }
    }

    @Override
    public void fixedErrors(int[] signal, int length, long[] errors, int bitsPerSample) {
        if (bitsPerSample <= FIXED_EXACT_BITS) {
            long e0 = 0, e1 = 0, e2 = 0, e3 = 0, e4 = 0;
            for (var i = 4; i < length; i++) {
                var x0 = signal[i];
                var x1 = signal[i - 1];
                var x2 = signal[i - 2];
                var x3 = signal[i - 3];
                var x4 = signal[i - 4];
                e0 += Math.abs(x0);
                e1 += Math.abs(x0 - x1);
                e2 += Math.abs(x0 - 2 * x1 + x2);
                e3 += Math.abs(x0 - 3 * x1 + 3 * x2 - x3);
                e4 += Math.abs(x0 - 4 * x1 + 6 * x2 - 4 * x3 + x4);
            }
            errors[0] = e0;
            errors[1] = e1;
            errors[2] = e2;
            errors[3] = e3;
            errors[4] = e4;
            return;
        }
        long e0 = 0, e1 = 0, e2 = 0, e3 = 0, e4 = 0;
        boolean v0 = true, v1 = true, v2 = true, v3 = true, v4 = true;
        for (var i = 4; i < length; i++) {
            long x0 = signal[i];
            long x1 = signal[i - 1];
            long x2 = signal[i - 2];
            long x3 = signal[i - 3];
            long x4 = signal[i - 4];
            var a0 = Math.abs(x0);
            var a1 = Math.abs(x0 - x1);
            var a2 = Math.abs(x0 - 2 * x1 + x2);
            var a3 = Math.abs(x0 - 3 * x1 + 3 * x2 - x3);
            var a4 = Math.abs(x0 - 4 * x1 + 6 * x2 - 4 * x3 + x4);
            v0 &= a0 < RESIDUAL_LIMIT;
            v1 &= a1 < RESIDUAL_LIMIT;
            v2 &= a2 < RESIDUAL_LIMIT;
            v3 &= a3 < RESIDUAL_LIMIT;
            v4 &= a4 < RESIDUAL_LIMIT;
            e0 += a0;
            e1 += a1;
            e2 += a2;
            e3 += a3;
            e4 += a4;
        }
        errors[0] = v0 ? e0 : Long.MAX_VALUE;
        errors[1] = v1 ? e1 : Long.MAX_VALUE;
        errors[2] = v2 ? e2 : Long.MAX_VALUE;
        errors[3] = v3 ? e3 : Long.MAX_VALUE;
        errors[4] = v4 ? e4 : Long.MAX_VALUE;
    }

    @Override
    public long absSum(int[] residual, int from, int to) {
        var sum = 0L;
        for (var i = from; i < to; i++) {
            sum += Math.abs(residual[i]);
        }
        return sum;
    }

    @Override
    public void midSide(int[] left, int[] right, int[] mid, int[] side, int count) {
        for (var i = 0; i < count; i++) {
            var l = left[i];
            var r = right[i];
            side[i] = l - r;
            mid[i] = (l + r) >> 1;
        }
    }
}
