package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The 32-band synthesis filter bank of the DTS core (ETSI TS 102 114 clause C.3.6) for one channel, with its
 * history.
 *
 * <p>Instances are not thread-safe.
 */
final class Qmf32 {
    /**
     * The perfect reconstruction prototype ({@link DtsCoreTables#QMF_PERFECT}).
     */
    static final float[] PERFECT = floats(DtsCoreTables.parse(DtsCoreTables.QMF_PERFECT, 512));

    /**
     * The non-perfect reconstruction prototype (D.8).
     */
    static final float[] LOSSY = floats(DtsCoreTables.parse(DtsCoreTables.QMF_LOSSY, 512));

    /**
     * The cosine modulation coefficients ({@code PreCalCosMod}).
     */
    private static final float[] COS_MOD = cosMod();

    /**
     * The cosine modulated history.
     */
    private final float[] x = new float[512];

    /**
     * The overlap of the filter output.
     */
    private final float[] z = new float[64];

    /**
     * The subband samples of the current output block.
     */
    private final float[] in = new float[32];

    /**
     * The sums of the first cosine modulation stage.
     */
    private final float[] a = new float[16];

    /**
     * Synthesizes 32 PCM samples per subband sample.
     *
     * @param bands the 32 subband planes, each with {@link SubbandBuffer#HISTORY} history samples first
     * @param from the index of the first subband sample, history excluded
     * @param count the number of subband samples
     * @param perfect whether to use the perfect reconstruction prototype ({@code FILTS} = 1)
     * @param out receives {@code 32 * count} samples
     * @param outOffset the index of the first output sample
     * @param scale the factor applied to the output
     */
    void synthesize(float[][] bands, int from, int count, boolean perfect, float[] out, int outOffset, float scale) {
        var coefficients = perfect ? PERFECT : LOSSY;
        for (var s = 0; s < count; s++) {
            var index = SubbandBuffer.HISTORY + from + s;
            for (var i = 0; i < 32; i++) {
                in[i] = bands[i][index];
            }
            var j = 0;
            for (var k = 0; k < 16; k++) {
                var sum = 0.0f;
                for (var i = 0; i < 16; i++) {
                    sum += (in[2 * i] + in[2 * i + 1]) * COS_MOD[j++];
                }
                a[k] = sum;
            }
            System.arraycopy(x, 0, x, 32, 480);
            for (var k = 0; k < 16; k++) {
                var b = in[0] * COS_MOD[j++];
                for (var i = 1; i < 16; i++) {
                    b += (in[2 * i] + in[2 * i - 1]) * COS_MOD[j++];
                }
                var sum = a[k] + b;
                var difference = a[k] - b;
                x[k] = COS_MOD[256 + 256 + k] * sum;
                x[31 - k] = COS_MOD[256 + 256 + 16 + k] * difference;
            }
            for (int i = 0, k = 31; i < 32; i++, k--) {
                var low = 0.0f;
                var high = 0.0f;
                for (var t = 0; t < 512; t += 64) {
                    low += coefficients[i + t] * (x[i + t] - x[t + k]);
                    high += coefficients[32 + i + t] * (-x[i + t] - x[t + k]);
                }
                z[i] += low;
                z[32 + i] += high;
            }
            var base = outOffset + 32 * s;
            for (var i = 0; i < 32; i++) {
                out[base + i] = scale * z[i];
            }
            System.arraycopy(z, 32, z, 0, 32);
            Arrays.fill(z, 32, 64, 0);
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
     * Precomputes the cosine modulation coefficients ({@code PreCalCosMod}).
     *
     * @return the coefficients
     */
    private static float[] cosMod() {
        var table = new float[256 + 256 + 32];
        var j = 0;
        for (var k = 0; k < 16; k++) {
            for (var i = 0; i < 16; i++) {
                table[j++] = (float) StrictMath.cos((2 * i + 1) * (2 * k + 1) * Math.PI / 64);
            }
        }
        for (var k = 0; k < 16; k++) {
            for (var i = 0; i < 16; i++) {
                table[j++] = (float) StrictMath.cos(i * (2 * k + 1) * Math.PI / 32);
            }
        }
        for (var k = 0; k < 16; k++) {
            table[j++] = (float) (0.25 / (2 * StrictMath.cos((2 * k + 1) * Math.PI / 128)));
        }
        for (var k = 0; k < 16; k++) {
            table[j++] = (float) (-0.25 / (2 * StrictMath.sin((2 * k + 1) * Math.PI / 128)));
        }
        return table;
    }

    /**
     * Converts table entries to floats.
     *
     * @param values the entries
     * @return the entries as floats
     */
    static float[] floats(double[] values) {
        var floats = new float[values.length];
        for (var i = 0; i < values.length; i++) {
            floats[i] = (float) values[i];
        }
        return floats;
    }
}
