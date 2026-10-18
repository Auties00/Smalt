package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.Fft;

/**
 * Compares two decodes of a stream with perceptual noise substitution, whose noise differs
 * sample by sample, by their energies in 32-bin bands averaged over one-second blocks.
 */
final class PnsEnergy {
    private PnsEnergy() {
    }

    /** Returns the mean absolute band energy difference in dB over bands holding energy. */
    static double meanBandDeviation(float[] reference, float[] actual, int sampleRate) {
        var n = 2048;
        var fft = new Fft(n, AacKernels.scalar());
        var re = new float[n];
        var im = new float[n];
        var bands = n / 2 / 32;
        var length = Math.min(reference.length, actual.length);
        double deviation = 0;
        var count = 0;
        for (var block = 0; block + sampleRate <= length; block += sampleRate) {
            var energy = new double[2][bands];
            for (var start = block; start + n <= block + sampleRate; start += n) {
                for (var which = 0; which < 2; which++) {
                    var source = which == 0 ? reference : actual;
                    for (var i = 0; i < n; i++) {
                        re[i] = source[start + i] * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
                        im[i] = 0;
                    }
                    fft.transform(re, im);
                    for (var k = 0; k < n / 2; k++) {
                        var p = fft.position(k);
                        energy[which][k / 32] += (double) re[p] * re[p] + (double) im[p] * im[p];
                    }
                }
            }
            var peak = 0.0;
            for (var value : energy[0]) {
                peak = Math.max(peak, value);
            }
            for (var band = 0; band < bands; band++) {
                if (energy[0][band] > peak * 1e-4) {
                    deviation += Math.abs(10 * Math.log10((energy[1][band] + 1e-30) / energy[0][band]));
                    count++;
                }
            }
        }
        return deviation / count;
    }
}
