package com.github.auties00.smalt.testkit.audio;

/**
 * Measures how audible the coding noise of a lossy audio codec is, with a noise-to-mask ratio in
 * the style of the ITU-R BS.1387 (PEAQ) basic model's {@code NMRtotB} and {@code RelDistFramesB}
 * outputs.
 *
 * <p>It is an approximation written for regression tests and encoder comparisons, not a PEAQ
 * implementation: 2048-sample Hann frames with 50% overlap; the outer and middle ear weighting of
 * BS.1387; quarter-Bark bands from 80 Hz to 18 kHz; reference excitation spread with BS.1387's
 * slopes (27 dB per Bark downwards, {@code 24 + 230/f - 0.2 L} dB per Bark upwards) summed in
 * power; a masking offset of 3 dB below 12 Bark and {@code 0.25 z} dB above; the threshold in
 * quiet with a full-scale sine at 92 dB SPL. The noise is the difference of the magnitude spectra,
 * weighted like the reference. Frames quieter than -70 dBFS are skipped. Lower is better; coding
 * noise at the masking threshold gives about 0 dB.
 *
 * @param nmr               the total noise-to-mask ratio in decibels: the mean over frames of the
 *                          mean over bands of noise over mask
 * @param disturbedFrames   the fraction of frames whose worst band exceeds its mask by 1.5 dB
 * @param worstFrame        the largest per-frame noise-to-mask ratio in decibels
 * @param frames            the number of frames measured
 */
public record PerceptualDistortion(double nmr, double disturbedFrames, double worstFrame, int frames) {
    /**
     * The frame length.
     */
    private static final int N = 2048;

    /**
     * Measures one channel.
     *
     * @param reference the original samples
     * @param test      the decoded samples
     * @param lag       the index in {@code test} of the sample matching {@code reference[0]}
     * @param rate      the sampling rate in hertz
     * @return the measurement
     */
    public static PerceptualDistortion measure(float[] reference, float[] test, int lag, int rate) {
        var fs = (double) rate;
        var zlo = bark(80);
        var zhi = bark(Math.min(18000, fs / 2 * 0.98));
        var bands = (int) ((zhi - zlo) / 0.25);
        var lo = new int[bands];
        var hi = new int[bands];
        var center = new double[bands];
        var rateBark = new double[bands];
        for (var j = 0; j < bands; j++) {
            var f1 = inverseBark(zlo + 0.25 * j);
            var f2 = inverseBark(zlo + 0.25 * (j + 1));
            lo[j] = (int) Math.round(f1 * N / fs);
            hi[j] = Math.max(lo[j] + 1, (int) Math.round(f2 * N / fs));
            rateBark[j] = zlo + 0.25 * (j + 0.5);
            center[j] = inverseBark(rateBark[j]);
        }
        var weight = new double[N / 2 + 1];
        for (var k = 1; k <= N / 2; k++) {
            var f = k * fs / N / 1000;
            var w = -0.6 * 3.64 * Math.pow(f, -0.8) + 6.5 * Math.exp(-0.6 * (f - 3.3) * (f - 3.3)) - 1e-3 * Math.pow(f, 3.6);
            weight[k] = Math.pow(10, w / 10);
        }
        var window = new double[N];
        for (var i = 0; i < N; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
        }
        var fullScale = Math.pow(N / 4.0, 2);
        var quiet = new double[bands];
        for (var j = 0; j < bands; j++) {
            var f = center[j] / 1000;
            var spl = 3.64 * Math.pow(f, -0.8) - 6.5 * Math.exp(-0.6 * (f - 3.3) * (f - 3.3)) + 1e-3 * Math.pow(f, 4);
            quiet[j] = fullScale * Math.pow(10, (spl - 92) / 10);
        }
        var re = new double[N];
        var im = new double[N];
        var re2 = new double[N];
        var im2 = new double[N];
        var energy = new double[bands];
        var noise = new double[bands];
        var spread = new double[bands];
        var sum = 0.0;
        var frames = 0;
        var disturbed = 0;
        var worst = 0.0;
        var n = Math.min(reference.length, test.length - lag);
        for (var start = 0; start + N <= n; start += N / 2) {
            var power = 0.0;
            for (var i = 0; i < N; i++) {
                double x = reference[start + i];
                double y = test[start + i + lag];
                re[i] = x * window[i];
                im[i] = 0;
                re2[i] = y * window[i];
                im2[i] = 0;
                power += x * x;
            }
            if (power / N < 1e-7) {
                continue;
            }
            fft(re, im);
            fft(re2, im2);
            for (var j = 0; j < bands; j++) {
                energy[j] = 0;
                noise[j] = 0;
                spread[j] = 0;
                for (var k = lo[j]; k < hi[j] && k <= N / 2; k++) {
                    var magnitude = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                    var decoded = Math.sqrt(re2[k] * re2[k] + im2[k] * im2[k]);
                    energy[j] += magnitude * magnitude * weight[k];
                    noise[j] += (magnitude - decoded) * (magnitude - decoded) * weight[k];
                }
            }
            for (var m = 0; m < bands; m++) {
                if (energy[m] <= 0) {
                    continue;
                }
                var level = 10 * Math.log10(energy[m] / fullScale) + 92;
                var upper = Math.max(5, 24 + 230 / center[m] - 0.2 * level);
                for (var j = 0; j < bands; j++) {
                    var dz = (j - m) * 0.25;
                    var attenuation = dz >= 0 ? upper * dz : -27 * dz;
                    if (attenuation < 100) {
                        spread[j] += energy[m] * Math.pow(10, -attenuation / 10);
                    }
                }
            }
            var frame = 0.0;
            var frameMax = 0.0;
            for (var j = 0; j < bands; j++) {
                var offset = rateBark[j] <= 12 ? 3 : 0.25 * rateBark[j];
                var mask = Math.max(spread[j] * Math.pow(10, -offset / 10), quiet[j]);
                var ratio = noise[j] / mask;
                frame += ratio;
                frameMax = Math.max(frameMax, ratio);
            }
            frame /= bands;
            sum += frame;
            frames++;
            if (frameMax > Math.pow(10, 0.15)) {
                disturbed++;
            }
            worst = Math.max(worst, frame);
        }
        if (frames == 0) {
            return new PerceptualDistortion(Double.NEGATIVE_INFINITY, 0, Double.NEGATIVE_INFINITY, 0);
        }
        return new PerceptualDistortion(10 * Math.log10(sum / frames), (double) disturbed / frames, 10 * Math.log10(worst), frames);
    }

    /**
     * Measures interleaved multichannel audio, averaging the channels' ratios in power.
     *
     * @param reference the original interleaved samples
     * @param test      the decoded interleaved samples
     * @param channels  the number of channels
     * @param lag       the frame index in {@code test} matching the first reference frame
     * @param rate      the sampling rate in hertz
     * @return the measurement
     */
    public static PerceptualDistortion measureInterleaved(float[] reference, float[] test, int channels, int lag, int rate) {
        var nmr = 0.0;
        var disturbed = 0.0;
        var worst = Double.NEGATIVE_INFINITY;
        var frames = 0;
        for (var c = 0; c < channels; c++) {
            var result = measure(deinterleave(reference, channels, c), deinterleave(test, channels, c), lag, rate);
            nmr += Math.pow(10, result.nmr / 10);
            disturbed += result.disturbedFrames;
            worst = Math.max(worst, result.worstFrame);
            frames += result.frames;
        }
        return new PerceptualDistortion(10 * Math.log10(nmr / channels), disturbed / channels, worst, frames);
    }

    /**
     * Extracts one channel of interleaved samples.
     *
     * @param interleaved the samples
     * @param channels    the number of channels
     * @param channel     the channel
     * @return the channel's samples
     */
    public static float[] deinterleave(float[] interleaved, int channels, int channel) {
        var out = new float[interleaved.length / channels];
        for (var i = 0; i < out.length; i++) {
            out[i] = interleaved[i * channels + channel];
        }
        return out;
    }

    /**
     * Returns the Bark rate of a frequency (Schroeder).
     *
     * @param f the frequency in hertz
     * @return the rate in Bark
     */
    private static double bark(double f) {
        var x = f / 650;
        return 7 * Math.log(x + Math.sqrt(x * x + 1));
    }

    /**
     * Returns the frequency of a Bark rate (Schroeder).
     *
     * @param z the rate in Bark
     * @return the frequency in hertz
     */
    private static double inverseBark(double z) {
        return 650 * Math.sinh(z / 7);
    }

    /**
     * Transforms in place with a radix-2 complex FFT.
     *
     * @param re the real parts
     * @param im the imaginary parts
     */
    private static void fft(double[] re, double[] im) {
        var n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            var bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                var t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (var length = 2; length <= n; length <<= 1) {
            var angle = -2 * Math.PI / length;
            var wr = Math.cos(angle);
            var wi = Math.sin(angle);
            for (var i = 0; i < n; i += length) {
                var cr = 1.0;
                var ci = 0.0;
                for (var k = 0; k < length / 2; k++) {
                    var a = i + k;
                    var b = i + k + length / 2;
                    var xr = re[b] * cr - im[b] * ci;
                    var xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    var t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
