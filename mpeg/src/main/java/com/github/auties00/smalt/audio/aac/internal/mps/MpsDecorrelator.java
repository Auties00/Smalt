/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_decor.c: the lattice all-pass
   decorrelator with the non-fractional delays and the energy ducker). Copyright Agere Systems, Coding Technologies,
   Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;

/**
 * Decorrelates one hybrid domain signal with the delay and all-pass filters of ISO/IEC 23003-1 subclause 6.6.2 and
 * equalizes its energy with the ducker.
 *
 * <p>Instances keep the filter states and delay lines of one signal; they are not thread-safe.
 */
public final class MpsDecorrelator {
    private static final int MAX_DELAY = 14;
    private static final int[] DELAYS = {11, 10, 5, 2};
    private static final int[][] SPLIT_FREQUENCIES = {{3, 15, 24, 65}, {3, 50, 65, 65}, {0, 15, 65, 65}};
    private static final float[][] LATTICE = {
            {-0.6135f, -0.3819f, -0.2331f, -0.1467f, -0.0074f, 0.0281f, 0.1061f, -0.2914f, 0.1576f, 0.0898f},
            {-0.2874f, -0.0732f, 0.1000f, -0.1121f, 0.0822f, 0.0202f, -0.0521f, -0.1221f},
            {0.1358f, -0.0373f, 0.0357f},
            {0.0352f, -0.0130f}
    };
    private static final float DUCK_ALPHA = (float) 0.8;
    private static final float DUCK_GAMMA = (float) 1.5;
    private static final float ABS_THR = 1e-9f * 32768 * 32768;
    private static final int PARAMETER_BANDS = 28;

    private final int slots;
    private final int bands;
    private final int[] delays;
    private final float[][] numerators;
    private final float[][] denominators;
    private final float[][] stateReal;
    private final float[][] stateImag;
    private final float[][] delayReal;
    private final float[][] delayImag;
    private final float[] smoothDirect = new float[PARAMETER_BANDS];
    private final float[] smoothReverb = new float[PARAMETER_BANDS];
    private final float[] directEnergy = new float[PARAMETER_BANDS];
    private final float[] reverbEnergy = new float[PARAMETER_BANDS];
    private final float[] duckGain = new float[PARAMETER_BANDS];

    /**
     * Constructs a decorrelator of the 71 hybrid subbands of 64 QMF subbands.
     *
     * @param slots the number of slots per frame
     * @param config {@code bsDecorrConfig}, 0 to 2
     * @throws MediaException.Malformed if the configuration is reserved
     */
    public MpsDecorrelator(int slots, int config) {
        this(slots, config, MpsHybrid.BANDS);
    }

    /**
     * Constructs a decorrelator.
     *
     * @param slots the number of slots per frame
     * @param config {@code bsDecorrConfig}, 0 to 2
     * @param bands the number of hybrid subbands
     * @throws MediaException.Malformed if the configuration is reserved
     */
    public MpsDecorrelator(int slots, int config, int bands) {
        if (config < 0 || config > 2) {
            throw new MediaException.Malformed("MPEG Surround decorrelator configuration " + config + " is reserved");
        }
        this.slots = slots;
        this.bands = bands;
        this.delays = new int[bands];
        this.numerators = new float[bands][];
        this.denominators = new float[bands][];
        this.stateReal = new float[bands][];
        this.stateImag = new float[bands][];
        var split = SPLIT_FREQUENCIES[config];
        for (var i = 0; i < bands; i++) {
            var qmf = qmfBand(i);
            var band = 0;
            while (band < 3 && qmf >= split[band] - 1) {
                band++;
            }
            delays[i] = DELAYS[band];
            var coefficients = LATTICE[band];
            var order = coefficients.length;
            var denominator = new float[order + 1];
            var tmp = new float[order + 1];
            denominator[0] = 1.0f;
            for (var k = 0; k < order; k++) {
                denominator[k + 1] = coefficients[k];
                for (var j = 0; j < k; j++) {
                    denominator[j + 1] = tmp[j] + coefficients[k] * tmp[k - j - 1];
                }
                for (var j = 0; j <= k; j++) {
                    tmp[j] = denominator[j + 1];
                }
            }
            var numerator = new float[order + 1];
            for (var k = 0; k <= order; k++) {
                numerator[k] = denominator[order - k];
            }
            numerators[i] = numerator;
            denominators[i] = denominator;
            stateReal[i] = new float[order + 1];
            stateImag[i] = new float[order + 1];
        }
        this.delayReal = new float[bands][slots + MAX_DELAY];
        this.delayImag = new float[bands][slots + MAX_DELAY];
    }

    /**
     * {@return the QMF subband a hybrid subband belongs to}
     *
     * @param hybrid the hybrid subband
     */
    public static int qmfBand(int hybrid) {
        if (hybrid < 6) {
            return 0;
        }
        if (hybrid < 8) {
            return 1;
        }
        if (hybrid < 10) {
            return 2;
        }
        return hybrid - 7;
    }

    /**
     * Decorrelates one frame ({@code SpatialDecDecorrelateApply}).
     *
     * @param inputReal the real parts of the input, {@code [slot][bands]}
     * @param inputImag the imaginary parts of the input
     * @param outputReal receives the real parts of the output
     * @param outputImag receives the imaginary parts of the output
     */
    public void apply(float[][] inputReal, float[][] inputImag, float[][] outputReal, float[][] outputImag) {
        for (var band = 0; band < bands; band++) {
            var delay = delays[band];
            var bufferReal = delayReal[band];
            var bufferImag = delayImag[band];
            for (var ts = 0; ts < slots; ts++) {
                bufferReal[delay + ts] = inputReal[ts][band];
                bufferImag[delay + ts] = inputImag[ts][band];
            }
            var numerator = numerators[band];
            var denominator = denominators[band];
            var sr = stateReal[band];
            var si = stateImag[band];
            var length = numerator.length;
            for (var ts = 0; ts < slots; ts++) {
                var xReal = bufferReal[ts];
                var xImag = bufferImag[ts];
                var yReal = sr[0] + xReal * numerator[0];
                var yImag = si[0] + xImag * numerator[0];
                outputReal[ts][band] = yReal;
                outputImag[ts][band] = yImag;
                for (var j = 1; j < length; j++) {
                    sr[j - 1] = sr[j] + numerator[j] * xReal - denominator[j] * yReal;
                    si[j - 1] = si[j] + numerator[j] * xImag - denominator[j] * yImag;
                }
            }
            for (var ts = 0; ts < delay; ts++) {
                bufferReal[ts] = bufferReal[slots + ts];
                bufferImag[ts] = bufferImag[slots + ts];
            }
        }
        duck(inputReal, inputImag, outputReal, outputImag);
    }

    private void duck(float[][] inputReal, float[][] inputImag, float[][] outputReal, float[][] outputImag) {
        for (var ts = 0; ts < slots; ts++) {
            java.util.Arrays.fill(directEnergy, 0.0f);
            java.util.Arrays.fill(reverbEnergy, 0.0f);
            var ir = inputReal[ts];
            var ii = inputImag[ts];
            var or = outputReal[ts];
            var oi = outputImag[ts];
            for (var qs = 0; qs < bands; qs++) {
                var pb = MpsTables.parameterBand28(qs);
                directEnergy[pb] += ir[qs] * ir[qs] + ii[qs] * ii[qs];
                reverbEnergy[pb] += or[qs] * or[qs] + oi[qs] * oi[qs];
            }
            for (var pb = 0; pb < PARAMETER_BANDS; pb++) {
                smoothDirect[pb] = smoothDirect[pb] * DUCK_ALPHA + directEnergy[pb] * (1.0f - DUCK_ALPHA);
                smoothReverb[pb] = smoothReverb[pb] * DUCK_ALPHA + reverbEnergy[pb] * (1.0f - DUCK_ALPHA);
                duckGain[pb] = 1.0f;
                if (smoothReverb[pb] > smoothDirect[pb] * DUCK_GAMMA) {
                    duckGain[pb] = (float) Math.sqrt(smoothDirect[pb] * DUCK_GAMMA / (smoothReverb[pb] + ABS_THR));
                }
                if (smoothDirect[pb] > smoothReverb[pb] * DUCK_GAMMA) {
                    duckGain[pb] = Math.min(2.0f, (float) Math.sqrt(smoothDirect[pb]
                            / (DUCK_GAMMA * smoothReverb[pb] + ABS_THR)));
                }
            }
            for (var qs = 0; qs < bands; qs++) {
                var gain = duckGain[MpsTables.parameterBand28(qs)];
                or[qs] *= gain;
                oi[qs] *= gain;
            }
        }
    }
}
