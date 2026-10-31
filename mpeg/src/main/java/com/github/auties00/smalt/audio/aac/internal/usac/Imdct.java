package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.AacKernel;
import com.github.auties00.smalt.audio.aac.internal.Dct4;

/**
 * Computes the inverse MDCTs of the USAC filterbanks,
 * {@code x[n] = 2/N sum X[k] cos(2 pi / N (n + n0)(k + 1/2))} with {@code n0 = (N/2 + 1)/2}, for the
 * transform lengths the decoder meets.
 *
 * <p>Instances are not thread-safe.
 */
final class Imdct {
    private final AacKernel kernel;
    private final Dct4[] transforms = new Dct4[4];
    private float[] buffer = new float[0];

    /**
     * Constructs the transforms.
     *
     * @param kernel the kernel running the FFTs
     */
    Imdct(AacKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * {@return the kernel running the FFTs}
     */
    AacKernel kernel() {
        return kernel;
    }

    /**
     * Transforms {@code lines} coefficients into {@code 2 * lines} samples.
     *
     * @param in the coefficients
     * @param inOffset the index of the first coefficient
     * @param lines the number of coefficients, the half transform length
     * @param out receives the samples
     * @param outOffset the index of the first sample
     */
    void inverse(float[] in, int inOffset, int lines, float[] out, int outOffset) {
        var dct = transform(lines);
        if (buffer.length < lines) {
            buffer = new float[lines];
        }
        var t = buffer;
        dct.transform(in, inOffset, t, 0);
        var quarter = lines / 2;
        for (var i = 0; i < quarter; i++) {
            out[outOffset + i] = t[quarter + i];
            out[outOffset + lines - 1 - i] = -t[quarter + i];
            out[outOffset + lines + quarter + i] = -t[i];
            out[outOffset + lines + quarter - 1 - i] = -t[i];
        }
    }

    /**
     * Returns the DCT-IV of a length, created on first use; a decoder meets at most four lengths.
     */
    private Dct4 transform(int lines) {
        for (var i = 0; i < transforms.length; i++) {
            var dct = transforms[i];
            if (dct == null) {
                dct = new Dct4(lines, 1.0 / lines, kernel);
                transforms[i] = dct;
                return dct;
            }
            if (dct.size() == lines) {
                return dct;
            }
        }
        return new Dct4(lines, 1.0 / lines, kernel);
    }
}
