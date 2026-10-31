package com.github.auties00.smalt.audio.aac;

/**
 * Compares a Low Complexity core decode with a full HE-AAC decode: the full decode is low-passed
 * and decimated to the core rate, then aligned by searching the lag with the best SNR.
 */
final class HeAacCompare {
    private HeAacCompare() {
    }

    /** Low-passes below a third of the core band and decimates by {@code ratio}. */
    static float[] lowBand(float[] signal, int ratio) {
        return filter(signal, 0.3 / ratio, ratio);
    }

    /** Low-passes at {@code cutoff}, a fraction of the sampling rate, without decimating. */
    static float[] lowPass(float[] signal, double cutoff) {
        return filter(signal, cutoff, 1);
    }

    private static float[] filter(float[] signal, double cutoff, int ratio) {
        var taps = 255;
        var kernel = new double[taps];
        var sum = 0.0;
        for (var i = 0; i < taps; i++) {
            var x = i - (taps - 1) / 2.0;
            var sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            var window = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (taps - 1));
            kernel[i] = sinc * window;
            sum += kernel[i];
        }
        var out = new float[signal.length / ratio];
        for (var n = 0; n < out.length; n++) {
            var center = n * ratio;
            var acc = 0.0;
            for (var i = 0; i < taps; i++) {
                var index = center + i - (taps - 1) / 2;
                if (index >= 0 && index < signal.length) {
                    acc += kernel[i] * signal[index];
                }
            }
            out[n] = (float) (acc / sum);
        }
        return out;
    }

    /** Low-passes at the core rate with the same relative cutoff as {@link #lowBand}. */
    static float[] lowPass(float[] signal) {
        return lowBand(signal, 1);
    }

    /** Returns {best SNR, lag} of {@code actual} (low-passed) against {@code expected} over lags -4096..4096. */
    static double[] bestSnr(float[] expected, float[] actualRaw) {
        var actual = lowPassSame(actualRaw, expected);
        var best = Double.NEGATIVE_INFINITY;
        var bestLag = 0;
        for (var lag = -4096; lag <= 4096; lag += 1) {
            double signal = 0;
            double noise = 0;
            var start = Math.max(0, -lag) + 4096;
            var end = Math.min(expected.length, actual.length - lag) - 4096;
            for (var i = start; i < end; i += 4) {
                var e = expected[i];
                var d = e - actual[i + lag];
                signal += e * e;
                noise += d * d;
            }
            var snr = 10 * Math.log10(signal / Math.max(noise, 1e-30));
            if (snr > best) {
                best = snr;
                bestLag = lag;
            }
        }
        return new double[]{best, bestLag};
    }

    /**
     * Returns {best SNR, lag} of {@code actual} against {@code expected}, both already filtered, over
     * lags -{@code maxLag}..{@code maxLag}, skipping {@code margin} samples at each end of the overlap.
     */
    static double[] align(float[] expected, float[] actual, int maxLag, int margin) {
        var best = Double.NEGATIVE_INFINITY;
        var bestLag = 0;
        for (var lag = -maxLag; lag <= maxLag; lag++) {
            var start = Math.max(0, -lag) + margin;
            var end = Math.min(expected.length, actual.length - lag) - margin;
            if (end - start < 4096) {
                continue;
            }
            double signal = 0;
            double noise = 0;
            for (var i = start; i < end; i += 4) {
                var e = expected[i];
                var d = e - actual[i + lag];
                signal += e * e;
                noise += d * d;
            }
            var snr = 10 * Math.log10(signal / Math.max(noise, 1e-30));
            if (snr > best) {
                best = snr;
                bestLag = lag;
            }
        }
        return new double[]{best, bestLag};
    }

    private static float[] lowPassSame(float[] signal, float[] reference) {
        return lowBand(signal, 1);
    }
}
