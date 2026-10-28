/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/agc2/rnn_vad/features_extraction.cc, lp_residual.cc,
   pitch_search.cc, pitch_search_internal.cc, auto_correlation.cc, spectral_features.cc,
   spectral_features_internal.cc, rnn.cc, rnn_fc.cc, rnn_gru.cc; third_party/rnnoise/src/rnn_activations.h). Copyright
   (c) 2018, The WebRTC project authors; BSD 3-Clause License. Copyright (c) 2008-2011 Octasic Inc., 2012-2017
   Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc2;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

/**
 * Estimates the probability that a 10 ms frame of 24 kHz audio holds speech with RNNoise's voice
 * activity network: 42 features (band cepstra and their derivatives, the pitch period, the pitch
 * correlation per band and the spectral variability) feed a dense layer, a gated recurrent layer
 * and a dense output layer.
 *
 * <p>Silent frames (total band energy below 0.04) reset the recurrent state and get probability
 * zero. Instances are not thread-safe.
 *
 * @implNote This implementation ports WebRTC's {@code rnn_vad::FeaturesExtractor} (without its
 * disabled high-pass filter) and {@code rnn_vad::RnnVad} with their scalar code paths and the
 * scalar PFFFT; dot products are sums in index order.
 */
final class RnnVad {
    static final int FRAME_SIZE_10MS = 240;
    private static final int FRAME_SIZE_20MS = 480;
    private static final int MIN_PITCH = 30;
    private static final int MAX_PITCH = 384;
    private static final int BUF_SIZE = MAX_PITCH + FRAME_SIZE_20MS;
    private static final int INITIAL_MIN_PITCH = 3 * MIN_PITCH;
    private static final int INITIAL_NUM_LAGS = MAX_PITCH - INITIAL_MIN_PITCH;
    private static final int REFINE_NUM_LAGS = MAX_PITCH + 1;
    private static final int FRAME_SIZE_20MS_12K = 240;
    private static final int BUF_SIZE_12K = BUF_SIZE / 2;
    private static final int INITIAL_MIN_PITCH_12K = INITIAL_MIN_PITCH / 2;
    private static final int MAX_PITCH_12K = MAX_PITCH / 2;
    private static final int NUM_LAGS_12K = MAX_PITCH_12K - INITIAL_MIN_PITCH_12K;
    private static final int MIN_PITCH_48K = MIN_PITCH * 2;
    private static final int MAX_PITCH_48K = MAX_PITCH * 2;
    private static final int NUM_BANDS = 22;
    private static final int NUM_LOWER_BANDS = 6;
    private static final int HISTORY = 8;
    private static final int FEATURE_VECTOR_SIZE = 42;
    private static final int NUM_LPC = 5;
    private static final int OPUS_BANDS = 20;
    private static final int[] OPUS_SCALE_NUM_BINS = {4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 16, 16, 16, 24, 24, 32, 48};
    private static final int[] SUB_HARMONIC_MULTIPLIERS = {3, 2, 3, 2, 5, 2, 3, 2, 3, 2, 5, 2, 3, 2};
    private static final int[] INITIAL_PITCH_PERIOD_THRESHOLDS =
            {20, 45, 80, 125, 180, 245, 320, 405, 500, 605, 720, 845, 980, 1125};
    private static final float SILENCE_THRESHOLD = 0.04f;
    private static final float WEIGHTS_SCALE = 1.f / 256.f;
    private static final int INPUT_SIZE = 42;
    private static final int HIDDEN_SIZE = 24;

    /**
     * {@code tanh} on the grid {@code i / 25}, from 0 to 8 ({@code kTansigTable}).
     */
    private static final float[] TANSIG_TABLE = {
            0.000000f, 0.039979f, 0.079830f, 0.119427f, 0.158649f, 0.197375f,
            0.235496f, 0.272905f, 0.309507f, 0.345214f, 0.379949f, 0.413644f,
            0.446244f, 0.477700f, 0.507977f, 0.537050f, 0.564900f, 0.591519f,
            0.616909f, 0.641077f, 0.664037f, 0.685809f, 0.706419f, 0.725897f,
            0.744277f, 0.761594f, 0.777888f, 0.793199f, 0.807569f, 0.821040f,
            0.833655f, 0.845456f, 0.856485f, 0.866784f, 0.876393f, 0.885352f,
            0.893698f, 0.901468f, 0.908698f, 0.915420f, 0.921669f, 0.927473f,
            0.932862f, 0.937863f, 0.942503f, 0.946806f, 0.950795f, 0.954492f,
            0.957917f, 0.961090f, 0.964028f, 0.966747f, 0.969265f, 0.971594f,
            0.973749f, 0.975743f, 0.977587f, 0.979293f, 0.980869f, 0.982327f,
            0.983675f, 0.984921f, 0.986072f, 0.987136f, 0.988119f, 0.989027f,
            0.989867f, 0.990642f, 0.991359f, 0.992020f, 0.992631f, 0.993196f,
            0.993718f, 0.994199f, 0.994644f, 0.995055f, 0.995434f, 0.995784f,
            0.996108f, 0.996407f, 0.996682f, 0.996937f, 0.997172f, 0.997389f,
            0.997590f, 0.997775f, 0.997946f, 0.998104f, 0.998249f, 0.998384f,
            0.998508f, 0.998623f, 0.998728f, 0.998826f, 0.998916f, 0.999000f,
            0.999076f, 0.999147f, 0.999213f, 0.999273f, 0.999329f, 0.999381f,
            0.999428f, 0.999472f, 0.999513f, 0.999550f, 0.999585f, 0.999617f,
            0.999646f, 0.999673f, 0.999699f, 0.999722f, 0.999743f, 0.999763f,
            0.999781f, 0.999798f, 0.999813f, 0.999828f, 0.999841f, 0.999853f,
            0.999865f, 0.999875f, 0.999885f, 0.999893f, 0.999902f, 0.999909f,
            0.999916f, 0.999923f, 0.999929f, 0.999934f, 0.999939f, 0.999944f,
            0.999948f, 0.999952f, 0.999956f, 0.999959f, 0.999962f, 0.999965f,
            0.999968f, 0.999970f, 0.999973f, 0.999975f, 0.999977f, 0.999978f,
            0.999980f, 0.999982f, 0.999983f, 0.999984f, 0.999986f, 0.999987f,
            0.999988f, 0.999989f, 0.999990f, 0.999990f, 0.999991f, 0.999992f,
            0.999992f, 0.999993f, 0.999994f, 0.999994f, 0.999994f, 0.999995f,
            0.999995f, 0.999996f, 0.999996f, 0.999996f, 0.999997f, 0.999997f,
            0.999997f, 0.999997f, 0.999997f, 0.999998f, 0.999998f, 0.999998f,
            0.999998f, 0.999998f, 0.999998f, 0.999999f, 0.999999f, 0.999999f,
            0.999999f, 0.999999f, 0.999999f, 0.999999f, 0.999999f, 0.999999f,
            0.999999f, 0.999999f, 0.999999f, 0.999999f, 1.000000f, 1.000000f,
            1.000000f, 1.000000f, 1.000000f, 1.000000f, 1.000000f, 1.000000f,
            1.000000f, 1.000000f, 1.000000f
    };

    private final float[] pitchBuffer = new float[BUF_SIZE];
    private final float[] lpResidual = new float[BUF_SIZE];
    private final float[] lpcCoeffs = new float[NUM_LPC];
    private final float[] autoCorrLpc = new float[NUM_LPC];
    private final float[] lpcCoeffsPre = new float[NUM_LPC - 1];
    private final float[] yEnergy = new float[REFINE_NUM_LAGS];
    private final float[] pitchBuffer12k = new float[BUF_SIZE_12K];
    private final float[] autoCorrelation12k = new float[NUM_LAGS_12K];
    private final float[] autoCorrelation24k = new float[INITIAL_NUM_LAGS];
    private final int[] invertedLags = new int[10];
    private int numInvertedLags;
    private int lastPitchPeriod;
    private float lastPitchStrength;
    private final Pffft autoCorrFft = new Pffft(512);
    private final float[] autoCorrTmp = new float[512];
    private final float[] autoCorrX = new float[512];
    private final float[] autoCorrH = new float[512];
    private final Pffft spectralFft = new Pffft(FRAME_SIZE_20MS);
    private final float[] halfWindow = new float[FRAME_SIZE_20MS / 2];
    private final float[] fftBuffer = new float[FRAME_SIZE_20MS];
    private final float[] referenceFrameFft = new float[FRAME_SIZE_20MS];
    private final float[] laggedFrameFft = new float[FRAME_SIZE_20MS];
    private final float[] referenceFrameBandsEnergy = new float[OPUS_BANDS];
    private final float[] laggedFrameBandsEnergy = new float[OPUS_BANDS];
    private final float[] bandsCrossCorr = new float[OPUS_BANDS];
    private final float[] dctTable = new float[NUM_BANDS * NUM_BANDS];
    private final float[] cepstralRing = new float[NUM_BANDS * HISTORY];
    private int cepstralRingTail;
    private final float[] cepstralDiffs = new float[(HISTORY - 1) * (HISTORY - 1)];
    private final float[] logBandsEnergy = new float[NUM_BANDS];
    private final float[] cepstrum = new float[NUM_BANDS];
    private final float[] distances = new float[HISTORY - 1];
    private final float[] crossCorrDct = new float[NUM_LOWER_BANDS];
    private final float[] features = new float[FEATURE_VECTOR_SIZE];
    private final float[] inputBias;
    private final float[] inputWeights;
    private final float[] gruBias;
    private final float[] gruWeights;
    private final float[] gruRecurrentWeights;
    private final float[] outputBias;
    private final float[] outputWeights;
    private final float[] inputOutput = new float[HIDDEN_SIZE];
    private final float[] gruState = new float[HIDDEN_SIZE];
    private final float[] update = new float[HIDDEN_SIZE];
    private final float[] reset = new float[HIDDEN_SIZE];
    private final float[] resetXState = new float[HIDDEN_SIZE];

    /**
     * Creates a detector with silent history.
     */
    RnnVad() {
        var halfSize = FRAME_SIZE_20MS / 2;
        var scaling = 1.f / (float) FRAME_SIZE_20MS;
        var kPi = 3.14159265358979323846;
        for (var i = 0; i < halfSize; ++i) {
            halfWindow[i] = (float) (scaling * StrictMath.sin(0.5 * kPi * StrictMath.sin(0.5 * kPi * (i + 0.5) / halfSize)
                    * StrictMath.sin(0.5 * kPi * (i + 0.5) / halfSize)));
        }
        var k = StrictMath.sqrt(0.5);
        for (var i = 0; i < NUM_BANDS; ++i) {
            for (var j = 0; j < NUM_BANDS; ++j) {
                dctTable[i * NUM_BANDS + j] = (float) StrictMath.cos((i + 0.5) * j * kPi / NUM_BANDS);
            }
            dctTable[i * NUM_BANDS] *= k;
        }
        this.inputBias = scaled(RnnVadWeights.INPUT_DENSE_BIAS);
        this.inputWeights = preprocessDenseWeights(RnnVadWeights.INPUT_DENSE_WEIGHTS, HIDDEN_SIZE);
        this.gruBias = preprocessGruTensor(RnnVadWeights.HIDDEN_GRU_BIAS, HIDDEN_SIZE);
        this.gruWeights = preprocessGruTensor(RnnVadWeights.HIDDEN_GRU_WEIGHTS, HIDDEN_SIZE);
        this.gruRecurrentWeights = preprocessGruTensor(RnnVadWeights.HIDDEN_GRU_RECURRENT_WEIGHTS, HIDDEN_SIZE);
        this.outputBias = scaled(RnnVadWeights.OUTPUT_DENSE_BIAS);
        this.outputWeights = scaled(RnnVadWeights.OUTPUT_DENSE_WEIGHTS);
    }

    /**
     * Resets the recurrent state, not the feature history ({@code RnnVad::Reset}).
     */
    void resetNetwork() {
        Arrays.fill(gruState, 0.f);
    }

    /**
     * Computes the speech probability of a frame.
     *
     * @param samples the 240 samples of 10 ms at 24 kHz
     * @param offset the index of the first sample
     * @return the probability, from 0 to 1
     */
    float analyze(float[] samples, int offset) {
        var isSilence = computeFeatures(samples, offset);
        if (isSilence) {
            resetNetwork();
            return 0.f;
        }
        for (var o = 0; o < HIDDEN_SIZE; ++o) {
            inputOutput[o] = tansig(inputBias[o] + dot(features, 0, inputWeights, o * INPUT_SIZE, INPUT_SIZE));
        }
        computeGru();
        return sigmoid(outputBias[0] + dot(gruState, 0, outputWeights, 0, HIDDEN_SIZE));
    }

    private void computeGru() {
        var strideWeights = HIDDEN_SIZE * HIDDEN_SIZE;
        var strideRecurrent = HIDDEN_SIZE * HIDDEN_SIZE;
        updateResetGate(0, 0, 0, update);
        updateResetGate(HIDDEN_SIZE, strideWeights, strideRecurrent, reset);
        for (var o = 0; o < HIDDEN_SIZE; ++o) {
            resetXState[o] = gruState[o] * reset[o];
        }
        for (var o = 0; o < HIDDEN_SIZE; ++o) {
            var x = gruBias[2 * HIDDEN_SIZE + o];
            x += dot(inputOutput, 0, gruWeights, 2 * strideWeights + o * HIDDEN_SIZE, HIDDEN_SIZE);
            x += dot(resetXState, 0, gruRecurrentWeights, 2 * strideRecurrent + o * HIDDEN_SIZE, HIDDEN_SIZE);
            gruState[o] = update[o] * gruState[o] + (1.f - update[o]) * FloatMath.max(0.f, x);
        }
    }

    private void updateResetGate(int biasOffset, int weightsOffset, int recurrentOffset, float[] gate) {
        for (var o = 0; o < HIDDEN_SIZE; ++o) {
            var x = gruBias[biasOffset + o];
            x += dot(inputOutput, 0, gruWeights, weightsOffset + o * HIDDEN_SIZE, HIDDEN_SIZE);
            x += dot(gruState, 0, gruRecurrentWeights, recurrentOffset + o * HIDDEN_SIZE, HIDDEN_SIZE);
            gate[o] = sigmoid(x);
        }
    }

    private static float dot(float[] x, int xo, float[] y, int yo, int n) {
        var sum = 0.f;
        for (var i = 0; i < n; i++) {
            sum += x[xo + i] * y[yo + i];
        }
        return sum;
    }

    /**
     * Approximates {@code tanh} with a table and a correction ({@code TansigApproximated}).
     *
     * @param x the argument
     * @return the approximation
     */
    static float tansig(float x) {
        if (!(x < 8.f)) {
            return 1.f;
        }
        if (!(x > -8.f)) {
            return -1.f;
        }
        var sign = 1.f;
        if (x < 0.f) {
            x = -x;
            sign = -1.f;
        }
        var i = (int) Math.floor(0.5f + 25 * x);
        var y = TANSIG_TABLE[i];
        x -= 0.04f * i;
        y = y + x * (1.f - y * y) * (1.f - y * x);
        return sign * y;
    }

    static float sigmoid(float x) {
        return 0.5f + 0.5f * tansig(0.5f * x);
    }

    private static float[] scaled(byte[] params) {
        var out = new float[params.length];
        for (var i = 0; i < params.length; i++) {
            out[i] = WEIGHTS_SCALE * (float) params[i];
        }
        return out;
    }

    private static float[] preprocessDenseWeights(byte[] weights, int outputSize) {
        var inputSize = weights.length / outputSize;
        var w = new float[weights.length];
        for (var o = 0; o < outputSize; ++o) {
            for (var i = 0; i < inputSize; ++i) {
                w[o * inputSize + i] = WEIGHTS_SCALE * (float) weights[i * outputSize + o];
            }
        }
        return w;
    }

    private static float[] preprocessGruTensor(byte[] src, int outputSize) {
        var n = src.length / (outputSize * 3);
        var strideSrc = 3 * outputSize;
        var strideDst = n * outputSize;
        var dst = new float[src.length];
        for (var g = 0; g < 3; ++g) {
            for (var o = 0; o < outputSize; ++o) {
                for (var i = 0; i < n; ++i) {
                    dst[g * strideDst + o * n + i] = WEIGHTS_SCALE * (float) src[i * strideSrc + g * outputSize + o];
                }
            }
        }
        return dst;
    }

    /**
     * Pushes a frame and computes the feature vector ({@code CheckSilenceComputeFeatures}).
     *
     * @param samples the frame
     * @param offset the index of its first sample
     * @return whether the frame is silent, in which case the features are not complete
     */
    private boolean computeFeatures(float[] samples, int offset) {
        System.arraycopy(pitchBuffer, FRAME_SIZE_10MS, pitchBuffer, 0, BUF_SIZE - FRAME_SIZE_10MS);
        System.arraycopy(samples, offset, pitchBuffer, BUF_SIZE - FRAME_SIZE_10MS, FRAME_SIZE_10MS);
        computeLpcCoefficients();
        computeLpResidual();
        var pitchPeriod48k = estimatePitch();
        features[FEATURE_VECTOR_SIZE - 2] = 0.01f * (pitchPeriod48k - 300);
        var laggedOffset = MAX_PITCH - pitchPeriod48k / 2;
        return computeSpectralFeatures(BUF_SIZE - FRAME_SIZE_20MS, laggedOffset);
    }

    private void computeLpcCoefficients() {
        var x = pitchBuffer;
        var autoCorr = autoCorrLpc;
        for (var lag = 0; lag < NUM_LPC; ++lag) {
            var sum = 0.f;
            for (var i = 0; i < BUF_SIZE - lag; i++) {
                sum += x[i] * x[i + lag];
            }
            autoCorr[lag] = sum;
        }
        if (autoCorr[0] == 0.f) {
            Arrays.fill(lpcCoeffs, 0.f);
            return;
        }
        autoCorr[0] *= 1.0001f;
        autoCorr[1] -= autoCorr[1] * 0.000064f;
        autoCorr[2] -= autoCorr[2] * 0.000256f;
        autoCorr[3] -= autoCorr[3] * 0.000576f;
        autoCorr[4] -= autoCorr[4] * 0.001024f;
        var pre = lpcCoeffsPre;
        Arrays.fill(pre, 0.f);
        var error = autoCorr[0];
        for (var i = 0; i < NUM_LPC - 1; ++i) {
            var reflectionCoeff = 0.f;
            for (var j = 0; j < i; ++j) {
                reflectionCoeff += pre[j] * autoCorr[i - j];
            }
            reflectionCoeff += autoCorr[i + 1];
            var minErrorMagnitude = 1e-6f;
            if (Math.abs(error) < minErrorMagnitude) {
                error = Math.copySign(minErrorMagnitude, error);
            }
            reflectionCoeff /= -error;
            pre[i] = reflectionCoeff;
            for (var j = 0; j < ((i + 1) >> 1); ++j) {
                var tmp1 = pre[j];
                var tmp2 = pre[i - 1 - j];
                pre[j] = tmp1 + reflectionCoeff * tmp2;
                pre[i - 1 - j] = tmp2 + reflectionCoeff * tmp1;
            }
            error -= reflectionCoeff * reflectionCoeff * error;
            if (error < 0.001f * autoCorr[0]) {
                break;
            }
        }
        pre[0] *= 0.9f;
        pre[1] *= 0.9f * 0.9f;
        pre[2] *= 0.9f * 0.9f * 0.9f;
        pre[3] *= 0.9f * 0.9f * 0.9f * 0.9f;
        var c = 0.8f;
        lpcCoeffs[0] = pre[0] + c;
        lpcCoeffs[1] = pre[1] + c * pre[0];
        lpcCoeffs[2] = pre[2] + c * pre[1];
        lpcCoeffs[3] = pre[3] + c * pre[2];
        lpcCoeffs[4] = c * pre[3];
    }

    private void computeLpResidual() {
        var x = pitchBuffer;
        var y = lpResidual;
        var lpc = lpcCoeffs;
        y[0] = x[0];
        for (var i = 1; i < NUM_LPC; ++i) {
            var sum = x[i];
            for (var j = 0; j < i; j++) {
                sum = sum + x[i - 1 - j] * lpc[j];
            }
            y[i] = sum;
        }
        for (var i = NUM_LPC; i < BUF_SIZE; ++i) {
            var sum = x[i];
            for (var j = 0; j < NUM_LPC; j++) {
                sum = sum + x[i - 1 - j] * lpc[j];
            }
            y[i] = sum;
        }
    }

    /**
     * Estimates the pitch period of the residual at 48 kHz ({@code PitchEstimator::Estimate}).
     *
     * @return the period
     */
    private int estimatePitch() {
        var buffer = lpResidual;
        for (var i = 0; i < BUF_SIZE_12K; ++i) {
            pitchBuffer12k[i] = buffer[2 * i];
        }
        autoCorrelation12k();
        var candidates = pitchPeriod12k();
        var best = (candidates >>> 16) * 2;
        var secondBest = (candidates & 0xFFFF) * 2;
        slidingFrameSquareEnergies();
        var pitchLag48k = pitchPeriod48k(best, secondBest);
        extendedPitchPeriod48k(MAX_PITCH_48K - pitchLag48k);
        return lastPitchPeriod;
    }

    private void autoCorrelation12k() {
        var convolutionLength = BUF_SIZE_12K - MAX_PITCH_12K;
        var tmp = autoCorrTmp;
        var pitchBuf = pitchBuffer12k;
        for (var i = 0; i < convolutionLength; i++) {
            tmp[i] = pitchBuf[BUF_SIZE_12K - 1 - i];
        }
        Arrays.fill(tmp, convolutionLength, 512, 0.f);
        autoCorrFft.forward(tmp, autoCorrH);
        System.arraycopy(pitchBuf, 0, tmp, 0, convolutionLength + NUM_LAGS_12K);
        Arrays.fill(tmp, NUM_LAGS_12K + convolutionLength, 512, 0.f);
        autoCorrFft.forward(tmp, autoCorrX);
        var scalingFactor = 1.f / (float) 512;
        Arrays.fill(tmp, 0.f);
        autoCorrFft.convolveAccumulate(autoCorrX, autoCorrH, tmp, scalingFactor);
        autoCorrFft.backward(tmp, tmp);
        System.arraycopy(tmp, convolutionLength - 1, autoCorrelation12k, 0, NUM_LAGS_12K);
    }

    /**
     * Finds the two strongest pitch candidates at 12 kHz ({@code ComputePitchPeriod12kHz}).
     *
     * @return the best inverted lag in the high 16 bits and the second best in the low 16 bits
     */
    private int pitchPeriod12k() {
        var pitchBuf = pitchBuffer12k;
        var autoCorr = autoCorrelation12k;
        var denominator = 1.f + dot(pitchBuf, 0, pitchBuf, 0, FRAME_SIZE_20MS_12K + 1);
        var bestLag = 0;
        var bestNumerator = -1.f;
        var bestDenominator = 0.f;
        var secondLag = 1;
        var secondNumerator = -1.f;
        var secondDenominator = 0.f;
        for (var invertedLag = 0; invertedLag < NUM_LAGS_12K; ++invertedLag) {
            if (autoCorr[invertedLag] > 0.f) {
                var numerator = autoCorr[invertedLag] * autoCorr[invertedLag];
                if (numerator * secondDenominator > secondNumerator * denominator) {
                    if (numerator * bestDenominator > bestNumerator * denominator) {
                        secondLag = bestLag;
                        secondNumerator = bestNumerator;
                        secondDenominator = bestDenominator;
                        bestLag = invertedLag;
                        bestNumerator = numerator;
                        bestDenominator = denominator;
                    } else {
                        secondLag = invertedLag;
                        secondNumerator = numerator;
                        secondDenominator = denominator;
                    }
                }
            }
            var yOld = pitchBuf[invertedLag];
            var yNew = pitchBuf[invertedLag + FRAME_SIZE_20MS_12K];
            denominator -= yOld * yOld;
            denominator += yNew * yNew;
            denominator = FloatMath.max(0.f, denominator);
        }
        return (bestLag << 16) | secondLag;
    }

    private void slidingFrameSquareEnergies() {
        var buffer = lpResidual;
        var yy = dot(buffer, 0, buffer, 0, FRAME_SIZE_20MS);
        yEnergy[0] = yy;
        for (var invertedLag = 0; invertedLag < MAX_PITCH; ++invertedLag) {
            yy -= buffer[invertedLag] * buffer[invertedLag];
            yy += buffer[invertedLag + FRAME_SIZE_20MS] * buffer[invertedLag + FRAME_SIZE_20MS];
            yy = FloatMath.max(1.f, yy);
            yEnergy[invertedLag + 1] = yy;
        }
    }

    private float autoCorrelation(int invertedLag) {
        return dot(lpResidual, MAX_PITCH, lpResidual, invertedLag, FRAME_SIZE_20MS);
    }

    private static int pseudoInterpolationOffset(float prev, float curr, float next) {
        if ((next - prev) > 0.7f * (curr - prev)) {
            return 1;
        } else if ((prev - next) > 0.7f * (curr - next)) {
            return -1;
        }
        return 0;
    }

    private void computeAutoCorrelationRange(int min, int max) {
        var autoCorr = autoCorrelation24k;
        if (min > 0) {
            autoCorr[min - 1] = 0.f;
        }
        if (max < INITIAL_NUM_LAGS - 1) {
            autoCorr[max + 1] = 0.f;
        }
        for (var invertedLag = min; invertedLag <= max; ++invertedLag) {
            autoCorr[invertedLag] = autoCorrelation(invertedLag);
            invertedLags[numInvertedLags++] = invertedLag;
        }
    }

    private int pitchPeriod48k(int best, int secondBest) {
        numInvertedLags = 0;
        var swap = best > secondBest;
        var c1 = swap ? secondBest : best;
        var c2 = swap ? best : secondBest;
        var r1Min = Math.max(c1 - 2, 0);
        var r1Max = Math.min(c1 + 2, INITIAL_NUM_LAGS - 1);
        var r2Min = Math.max(c2 - 2, 0);
        var r2Max = Math.min(c2 + 2, INITIAL_NUM_LAGS - 1);
        if (r1Max + 1 >= r2Min) {
            computeAutoCorrelationRange(r1Min, r2Max);
        } else {
            computeAutoCorrelationRange(r1Min, r1Max);
            computeAutoCorrelationRange(r2Min, r2Max);
        }
        var autoCorr = autoCorrelation24k;
        var bestInvertedLag = 0;
        var bestNumerator = -1.f;
        var bestDenominator = 0.f;
        for (var n = 0; n < numInvertedLags; n++) {
            var invertedLag = invertedLags[n];
            if (autoCorr[invertedLag] > 0.f) {
                var numerator = autoCorr[invertedLag] * autoCorr[invertedLag];
                var denominator = yEnergy[invertedLag];
                if (numerator * bestDenominator > bestNumerator * denominator) {
                    bestInvertedLag = invertedLag;
                    bestNumerator = numerator;
                    bestDenominator = denominator;
                }
            }
        }
        if (bestInvertedLag == 0 || bestInvertedLag >= INITIAL_NUM_LAGS - 1) {
            return bestInvertedLag * 2;
        }
        var offset = pseudoInterpolationOffset(autoCorr[bestInvertedLag + 1], autoCorr[bestInvertedLag],
                autoCorr[bestInvertedLag - 1]);
        return 2 * bestInvertedLag + offset;
    }

    private static int alternativePitchPeriod(int pitchPeriod, int multiplier, int divisor) {
        return (2 * multiplier * pitchPeriod + divisor) / (2 * divisor);
    }

    private float pitchStrength(float xy, float yEnergyValue) {
        var xEnergy = yEnergy[MAX_PITCH];
        return xy / (float) Math.sqrt(1.f + xEnergy * yEnergyValue);
    }

    private void extendedPitchPeriod48k(int initialPitchPeriod48k) {
        var bestPeriod = Math.min(initialPitchPeriod48k / 2, MAX_PITCH - 1);
        var bestXy = autoCorrelation(MAX_PITCH - bestPeriod);
        var bestYEnergy = yEnergy[MAX_PITCH - bestPeriod];
        var bestStrength = pitchStrength(bestXy, bestYEnergy);
        var initialPeriod = bestPeriod;
        var initialStrength = bestStrength;
        var lastPeriod = lastPitchPeriod / 2;
        var lastStrength = lastPitchStrength;
        var maxPeriodDivisor = (2 * initialPeriod) / (2 * MIN_PITCH - 1);
        for (var periodDivisor = 2; periodDivisor <= maxPeriodDivisor; ++periodDivisor) {
            var alternativePeriod = alternativePitchPeriod(initialPeriod, 1, periodDivisor);
            var dualAlternativePeriod = alternativePitchPeriod(initialPeriod,
                    SUB_HARMONIC_MULTIPLIERS[periodDivisor - 2], periodDivisor);
            if (periodDivisor == 2 && dualAlternativePeriod > MAX_PITCH) {
                dualAlternativePeriod = initialPeriod;
            }
            var xyPrimary = autoCorrelation(MAX_PITCH - alternativePeriod);
            var xySecondary = autoCorrelation(MAX_PITCH - dualAlternativePeriod);
            var xy = 0.5f * (xyPrimary + xySecondary);
            var yy = 0.5f * (yEnergy[MAX_PITCH - alternativePeriod] + yEnergy[MAX_PITCH - dualAlternativePeriod]);
            var alternativeStrength = pitchStrength(xy, yy);
            var lowerThresholdTerm = 0.f;
            if (Math.abs(alternativePeriod - lastPeriod) <= 1) {
                lowerThresholdTerm = lastStrength;
            } else if (Math.abs(alternativePeriod - lastPeriod) == 2
                    && initialPeriod > INITIAL_PITCH_PERIOD_THRESHOLDS[periodDivisor - 2]) {
                lowerThresholdTerm = 0.5f * lastStrength;
            }
            var threshold = FloatMath.max(0.3f, 0.7f * initialStrength - lowerThresholdTerm);
            if (alternativePeriod < 3 * MIN_PITCH) {
                threshold = FloatMath.max(0.4f, 0.85f * initialStrength - lowerThresholdTerm);
            } else if (alternativePeriod < 2 * MIN_PITCH) {
                threshold = FloatMath.max(0.5f, 0.9f * initialStrength - lowerThresholdTerm);
            }
            if (alternativeStrength > threshold) {
                bestPeriod = alternativePeriod;
                bestStrength = alternativeStrength;
                bestXy = xy;
                bestYEnergy = yy;
            }
        }
        bestXy = FloatMath.max(0.f, bestXy);
        var finalPitchStrength = (bestYEnergy <= bestXy) ? 1.f : bestXy / (bestYEnergy + 1.f);
        finalPitchStrength = FloatMath.min(bestStrength, finalPitchStrength);
        var offset = 0;
        if (bestPeriod > 0 && bestPeriod < MAX_PITCH) {
            var invertedLag = MAX_PITCH - bestPeriod;
            offset = pseudoInterpolationOffset(autoCorrelation(invertedLag + 1), autoCorrelation(invertedLag),
                    autoCorrelation(invertedLag - 1));
        }
        lastPitchPeriod = Math.max(MIN_PITCH_48K, 2 * bestPeriod + offset);
        lastPitchStrength = finalPitchStrength;
    }

    private void windowedForwardFft(int frameOffset, float[] out) {
        var in = fftBuffer;
        var frame = pitchBuffer;
        for (int i = 0, j = FRAME_SIZE_20MS - 1; i < halfWindow.length; ++i, --j) {
            in[i] = frame[frameOffset + i] * halfWindow[i];
            in[j] = frame[frameOffset + j] * halfWindow[i];
        }
        spectralFft.forwardOrdered(in, out);
        out[1] = 0.f;
    }

    private static void crossCorrelation(float[] x, float[] y, float[] crossCorr) {
        var k = 0;
        crossCorr[0] = 0.f;
        for (var i = 0; i < OPUS_BANDS - 1; ++i) {
            crossCorr[i + 1] = 0.f;
            for (var j = 0; j < OPUS_SCALE_NUM_BINS[i]; ++j) {
                var v = x[2 * k] * y[2 * k] + x[2 * k + 1] * y[2 * k + 1];
                var tmp = OPUS_BAND_WEIGHTS[k] * v;
                crossCorr[i] += v - tmp;
                crossCorr[i + 1] += tmp;
                k++;
            }
        }
        crossCorr[0] *= 2.f;
    }

    private boolean computeSpectralFeatures(int referenceOffset, int laggedOffset) {
        windowedForwardFft(referenceOffset, referenceFrameFft);
        crossCorrelation(referenceFrameFft, referenceFrameFft, referenceFrameBandsEnergy);
        var totEnergy = 0.f;
        for (var e : referenceFrameBandsEnergy) {
            totEnergy = totEnergy + e;
        }
        if (totEnergy < SILENCE_THRESHOLD) {
            return true;
        }
        windowedForwardFft(laggedOffset, laggedFrameFft);
        crossCorrelation(laggedFrameFft, laggedFrameFft, laggedFrameBandsEnergy);
        smoothedLogMagnitudeSpectrum();
        computeDct(logBandsEnergy, NUM_BANDS, cepstrum, NUM_BANDS);
        cepstrum[0] -= 12.f;
        cepstrum[1] -= 4.f;
        System.arraycopy(cepstrum, 0, cepstralRing, NUM_BANDS * cepstralRingTail, NUM_BANDS);
        cepstralRingTail = cepstralRingTail + 1 == HISTORY ? 0 : cepstralRingTail + 1;
        for (var i = 0; i < HISTORY - 1; ++i) {
            var old = ringOffset(i + 1);
            var d = 0.f;
            for (var k = 0; k < NUM_BANDS; ++k) {
                var c = cepstrum[k] - cepstralRing[old + k];
                d += c * c;
            }
            distances[i] = d;
        }
        var s = HISTORY;
        System.arraycopy(cepstralDiffs, s, cepstralDiffs, 0, cepstralDiffs.length - s);
        for (var i = 0; i < HISTORY - 1; ++i) {
            cepstralDiffs[(s - 1 - i) * (s - 1) - 1] = distances[i];
        }
        System.arraycopy(cepstrum, NUM_LOWER_BANDS, features, NUM_LOWER_BANDS, NUM_BANDS - NUM_LOWER_BANDS);
        var curr = ringOffset(0);
        var prev1 = ringOffset(1);
        var prev2 = ringOffset(2);
        for (var i = 0; i < NUM_LOWER_BANDS; ++i) {
            var c = cepstralRing[curr + i];
            var p1 = cepstralRing[prev1 + i];
            var p2 = cepstralRing[prev2 + i];
            features[i] = c + p1 + p2;
            features[NUM_BANDS + i] = c - p2;
            features[NUM_BANDS + NUM_LOWER_BANDS + i] = c - 2 * p1 + p2;
        }
        crossCorrelation(referenceFrameFft, laggedFrameFft, bandsCrossCorr);
        for (var i = 0; i < OPUS_BANDS; ++i) {
            bandsCrossCorr[i] = bandsCrossCorr[i]
                    / (float) Math.sqrt(0.001f + referenceFrameBandsEnergy[i] * laggedFrameBandsEnergy[i]);
        }
        computeDct(bandsCrossCorr, OPUS_BANDS, crossCorrDct, NUM_LOWER_BANDS);
        crossCorrDct[0] -= 1.3f;
        crossCorrDct[1] -= 0.9f;
        System.arraycopy(crossCorrDct, 0, features, NUM_BANDS + 2 * NUM_LOWER_BANDS, NUM_LOWER_BANDS);
        var variability = 0.f;
        for (var delay1 = 0; delay1 < HISTORY; ++delay1) {
            var minDist = Float.MAX_VALUE;
            for (var delay2 = 0; delay2 < HISTORY; ++delay2) {
                if (delay1 == delay2) {
                    continue;
                }
                minDist = FloatMath.min(minDist, diffValue(delay1, delay2));
            }
            variability += minDist;
        }
        features[FEATURE_VECTOR_SIZE - 1] = variability / HISTORY - 2.1f;
        return false;
    }

    private int ringOffset(int delay) {
        var offset = cepstralRingTail - 1 - delay;
        if (offset < 0) {
            offset += HISTORY;
        }
        return NUM_BANDS * offset;
    }

    private float diffValue(int delay1, int delay2) {
        var row = HISTORY - 1 - delay1;
        var col = HISTORY - 1 - delay2;
        if (row > col) {
            var t = row;
            row = col;
            col = t;
        }
        return cepstralDiffs[row * (HISTORY - 1) + (col - 1)];
    }

    private void smoothedLogMagnitudeSpectrum() {
        var oneByHundred = 1e-2f;
        var logOneByHundred = -2.f;
        var logMax = logOneByHundred;
        var follow = logOneByHundred;
        for (var i = 0; i < NUM_BANDS; ++i) {
            var x = i < OPUS_BANDS ? FloatMath.log10f(oneByHundred + referenceFrameBandsEnergy[i]) : logOneByHundred;
            x = FloatMath.max(logMax - 7.f, FloatMath.max(follow - 1.5f, x));
            logMax = FloatMath.max(logMax, x);
            follow = FloatMath.max(follow - 1.5f, x);
            logBandsEnergy[i] = x;
        }
    }

    private void computeDct(float[] in, int inSize, float[] out, int outSize) {
        var dctScalingFactor = 0.301511345f;
        for (var i = 0; i < outSize; ++i) {
            out[i] = 0.f;
            for (var j = 0; j < inSize; ++j) {
                out[i] += in[j] * dctTable[j * NUM_BANDS + i];
            }
            out[i] *= dctScalingFactor;
        }
    }

    private static float[] opusBandWeights() {
        var weights = new float[FRAME_SIZE_20MS / 2];
        var k = 0;
        for (var band = 0; band < OPUS_BANDS - 1; band++) {
            var bins = OPUS_SCALE_NUM_BINS[band];
            for (var j = 0; j < bins; j++) {
                weights[k++] = WEIGHT_TABLE[band][j];
            }
        }
        return weights;
    }

    /**
     * The per-band weights of {@code kOpusBandWeights24kHz20ms}, as written there with six
     * significant digits.
     */
    private static final float[][] WEIGHT_TABLE = {
            {0.f, 0.25f, 0.5f, 0.75f}, {0.f, 0.25f, 0.5f, 0.75f}, {0.f, 0.25f, 0.5f, 0.75f},
            {0.f, 0.25f, 0.5f, 0.75f}, {0.f, 0.25f, 0.5f, 0.75f}, {0.f, 0.25f, 0.5f, 0.75f},
            {0.f, 0.25f, 0.5f, 0.75f}, {0.f, 0.25f, 0.5f, 0.75f},
            {0.f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f},
            {0.f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f},
            {0.f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f},
            {0.f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f},
            {0.f, 0.0625f, 0.125f, 0.1875f, 0.25f, 0.3125f, 0.375f, 0.4375f, 0.5f, 0.5625f, 0.625f, 0.6875f, 0.75f,
                    0.8125f, 0.875f, 0.9375f},
            {0.f, 0.0625f, 0.125f, 0.1875f, 0.25f, 0.3125f, 0.375f, 0.4375f, 0.5f, 0.5625f, 0.625f, 0.6875f, 0.75f,
                    0.8125f, 0.875f, 0.9375f},
            {0.f, 0.0625f, 0.125f, 0.1875f, 0.25f, 0.3125f, 0.375f, 0.4375f, 0.5f, 0.5625f, 0.625f, 0.6875f, 0.75f,
                    0.8125f, 0.875f, 0.9375f},
            {0.f, 0.0416667f, 0.0833333f, 0.125f, 0.166667f, 0.208333f, 0.25f, 0.291667f, 0.333333f, 0.375f,
                    0.416667f, 0.458333f, 0.5f, 0.541667f, 0.583333f, 0.625f, 0.666667f, 0.708333f, 0.75f, 0.791667f,
                    0.833333f, 0.875f, 0.916667f, 0.958333f},
            {0.f, 0.0416667f, 0.0833333f, 0.125f, 0.166667f, 0.208333f, 0.25f, 0.291667f, 0.333333f, 0.375f,
                    0.416667f, 0.458333f, 0.5f, 0.541667f, 0.583333f, 0.625f, 0.666667f, 0.708333f, 0.75f, 0.791667f,
                    0.833333f, 0.875f, 0.916667f, 0.958333f},
            {0.f, 0.03125f, 0.0625f, 0.09375f, 0.125f, 0.15625f, 0.1875f, 0.21875f, 0.25f, 0.28125f, 0.3125f,
                    0.34375f, 0.375f, 0.40625f, 0.4375f, 0.46875f, 0.5f, 0.53125f, 0.5625f, 0.59375f, 0.625f,
                    0.65625f, 0.6875f, 0.71875f, 0.75f, 0.78125f, 0.8125f, 0.84375f, 0.875f, 0.90625f, 0.9375f,
                    0.96875f},
            {0.f, 0.0208333f, 0.0416667f, 0.0625f, 0.0833333f, 0.104167f, 0.125f, 0.145833f, 0.166667f, 0.1875f,
                    0.208333f, 0.229167f, 0.25f, 0.270833f, 0.291667f, 0.3125f, 0.333333f, 0.354167f, 0.375f,
                    0.395833f, 0.416667f, 0.4375f, 0.458333f, 0.479167f, 0.5f, 0.520833f, 0.541667f, 0.5625f,
                    0.583333f, 0.604167f, 0.625f, 0.645833f, 0.666667f, 0.6875f, 0.708333f, 0.729167f, 0.75f,
                    0.770833f, 0.791667f, 0.8125f, 0.833333f, 0.854167f, 0.875f, 0.895833f, 0.916667f, 0.9375f,
                    0.958333f, 0.979167f}
    };

    /**
     * The weights of every Fourier coefficient in the Opus band it rises into
     * ({@code kOpusBandWeights24kHz20ms}).
     */
    private static final float[] OPUS_BAND_WEIGHTS = opusBandWeights();
}
