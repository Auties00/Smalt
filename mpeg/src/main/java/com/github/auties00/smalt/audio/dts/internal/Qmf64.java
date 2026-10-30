package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The 64-band synthesis filter bank of the DTS X96 extension (ETSI TS 102 114 clause 6.2) for one channel, with its
 * history: the cosine modulation of the 1024-tap prototype of D.9, structured as the 32-band bank of clause C.3.6.
 *
 * <p>Instances are not thread-safe.
 */
final class Qmf64 {
    /**
     * The prototype (D.9), with the signs of every other block of 128 coefficients changed.
     */
    static final float[] PROTOTYPE = Qmf32.floats(DtsCoreTables.parse(DtsCoreTables.QMF_64, 1024));

    /**
     * The cosine modulation coefficients.
     */
    private static final float[] COS_MOD = cosMod();

    /**
     * The cosine modulated history.
     */
    private final float[] x = new float[1024];

    /**
     * The overlap of the filter output.
     */
    private final float[] z = new float[128];

    /**
     * The subband samples of the current output block.
     */
    private final float[] in = new float[64];

    /**
     * The sums of the first cosine modulation stage.
     */
    private final float[] a = new float[32];

    /**
     * Synthesizes 64 PCM samples per subband sample.
     *
     * @param bands the 64 subband planes, each with {@link SubbandBuffer#HISTORY} history samples first
     * @param from the index of the first subband sample, history excluded
     * @param count the number of subband samples
     * @param out receives {@code 64 * count} samples
     * @param outOffset the index of the first output sample
     * @param scale the factor applied to the output
     */
    void synthesize(float[][] bands, int from, int count, float[] out, int outOffset, float scale) {
        for (var s = 0; s < count; s++) {
            var index = SubbandBuffer.HISTORY + from + s;
            for (var i = 0; i < 64; i++) {
                in[i] = bands[i][index];
            }
            var j = 0;
            for (var k = 0; k < 32; k++) {
                var sum = 0.0f;
                for (var i = 0; i < 32; i++) {
                    sum += (in[2 * i] + in[2 * i + 1]) * COS_MOD[j++];
                }
                a[k] = sum;
            }
            System.arraycopy(x, 0, x, 64, 960);
            for (var k = 0; k < 32; k++) {
                var b = in[0] * COS_MOD[j++];
                for (var i = 1; i < 32; i++) {
                    b += (in[2 * i] + in[2 * i - 1]) * COS_MOD[j++];
                }
                x[k] = COS_MOD[2048 + k] * (a[k] + b);
                x[63 - k] = COS_MOD[2048 + 32 + k] * (a[k] - b);
            }
            for (int i = 0, k = 63; i < 64; i++, k--) {
                var low = 0.0f;
                var high = 0.0f;
                for (var t = 0; t < 1024; t += 128) {
                    low += PROTOTYPE[i + t] * (x[i + t] - x[t + k]);
                    high += PROTOTYPE[64 + i + t] * (-x[i + t] - x[t + k]);
                }
                z[i] += low;
                z[64 + i] += high;
            }
            var base = outOffset + 64 * s;
            for (var i = 0; i < 64; i++) {
                out[base + i] = scale * z[i];
            }
            System.arraycopy(z, 64, z, 0, 64);
            Arrays.fill(z, 64, 128, 0);
        }
    }

    /**
     * Clears the history.
     */
    void reset() {
        Arrays.fill(x, 0);
        Arrays.fill(z, 0);
    }

    /**
     * Precomputes the cosine modulation coefficients.
     *
     * @return the coefficients
     */
    private static float[] cosMod() {
        var table = new float[2048 + 64];
        var j = 0;
        for (var k = 0; k < 32; k++) {
            for (var i = 0; i < 32; i++) {
                table[j++] = (float) StrictMath.cos((2 * i + 1) * (2 * k + 1) * Math.PI / 128);
            }
        }
        for (var k = 0; k < 32; k++) {
            for (var i = 0; i < 32; i++) {
                table[j++] = (float) StrictMath.cos(i * (2 * k + 1) * Math.PI / 64);
            }
        }
        for (var k = 0; k < 32; k++) {
            table[j++] = (float) (0.25 / (2 * StrictMath.cos((2 * k + 1) * Math.PI / 256)));
        }
        for (var k = 0; k < 32; k++) {
            table[j++] = (float) (-0.25 / (2 * StrictMath.sin((2 * k + 1) * Math.PI / 256)));
        }
        return table;
    }
}
