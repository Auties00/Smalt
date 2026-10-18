package com.github.auties00.smalt.audio.opus;

/**
 * Objective audio quality measures used to compare encoders: segmental SNR and log-spectral
 * distance, both computed on 20 ms frames of time-aligned mono signals.
 */
final class AudioMetrics {
    private AudioMetrics() {
    }

    /**
     * Returns the mean segmental SNR over active frames, each frame's SNR clamped to
     * {@code [-10, 35]} dB.
     */
    static double segmentalSnr(float[] reference, float[] test, int sampleRate) {
        var frame = sampleRate / 50;
        var frames = Math.min(reference.length, test.length) / frame;
        double sum = 0;
        var count = 0;
        for (var f = 0; f < frames; f++) {
            double signal = 0;
            double noise = 0;
            for (var i = f * frame; i < (f + 1) * frame; i++) {
                signal += (double) reference[i] * reference[i];
                var d = (double) reference[i] - test[i];
                noise += d * d;
            }
            if (signal / frame < 1e-6) {
                continue;
            }
            var snr = noise == 0 ? 35 : 10 * Math.log10(signal / noise);
            sum += Math.max(-10, Math.min(35, snr));
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    /**
     * Returns the mean log-spectral distance in dB over active frames, using the bins up to
     * {@code maxHz}.
     */
    static double logSpectralDistance(float[] reference, float[] test, int sampleRate, double maxHz) {
        var n = sampleRate / 50;
        var window = new double[n];
        for (var i = 0; i < n; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
        }
        var frames = Math.min(reference.length, test.length) / n;
        var bins = (int) Math.min(n / 2, Math.floor(maxHz * n / sampleRate));
        var cos = new double[n];
        var sin = new double[n];
        for (var k = 0; k < n; k++) {
            cos[k] = Math.cos(2 * Math.PI * k / n);
            sin[k] = Math.sin(2 * Math.PI * k / n);
        }
        var x = new double[n];
        var y = new double[n];
        double total = 0;
        var count = 0;
        for (var f = 0; f < frames; f++) {
            double energy = 0;
            for (var i = 0; i < n; i++) {
                x[i] = window[i] * reference[f * n + i];
                y[i] = window[i] * test[f * n + i];
                energy += x[i] * x[i];
            }
            if (energy / n < 1e-6) {
                continue;
            }
            double sum = 0;
            for (var k = 1; k <= bins; k++) {
                double xr = 0, xi = 0, yr = 0, yi = 0;
                var t = 0;
                for (var i = 0; i < n; i++) {
                    xr += x[i] * cos[t];
                    xi -= x[i] * sin[t];
                    yr += y[i] * cos[t];
                    yi -= y[i] * sin[t];
                    t += k;
                    if (t >= n) {
                        t -= n;
                    }
                }
                var px = xr * xr + xi * xi + 1e-9;
                var py = yr * yr + yi * yi + 1e-9;
                var d = 10 * Math.log10(px / py);
                sum += d * d;
            }
            total += Math.sqrt(sum / bins);
            count++;
        }
        return count == 0 ? 0 : total / count;
    }
}
