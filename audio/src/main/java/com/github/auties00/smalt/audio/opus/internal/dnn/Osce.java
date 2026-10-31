/* Ports libopus 1.5.2 (dnn/osce.c, dnn/osce_features.c, dnn/osce_structs.h, dnn/osce_config.h).
   Copyright Amazon; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;

import java.util.Arrays;

/**
 * Enhances 20 ms frames of 16 kHz SILK speech with the Opus Speech Coding Enhancement of libopus 1.5.2
 * ({@code silk_OSCE_struct}): the LACE or NoLACE network predicts adaptive comb and convolution filters from
 * features of the decoder parameters and of the decoded signal.
 *
 * <p>One instance serves one SILK channel. After a reset or a change of method, the first frame passes through
 * unchanged and the second is cross-faded into the enhanced signal. The output is bit-identical to the generic C
 * build of libopus 1.5.2. Instances are not thread-safe.
 */
public final class Osce {
    /**
     * No enhancement ({@code OSCE_METHOD_NONE}).
     */
    public static final int METHOD_NONE = 0;

    /**
     * The LACE enhancer ({@code OSCE_METHOD_LACE}).
     */
    public static final int METHOD_LACE = 1;

    /**
     * The NoLACE enhancer ({@code OSCE_METHOD_NOLACE}), the method a new SILK decoder starts with.
     */
    public static final int METHOD_NOLACE = 2;

    private static final int FEATURES_MAX_HISTORY = 350;
    private static final int FEATURE_DIM = 93;
    private static final int MAX_FEATURE_FRAMES = 4;
    private static final int CLEAN_SPEC_NUM_BANDS = 64;
    private static final int NOISY_SPEC_NUM_BANDS = 18;
    private static final int NO_PITCH_VALUE = 7;
    private static final int CLEAN_SPEC_START = 0;
    private static final int CLEAN_SPEC_LENGTH = 64;
    private static final int NOISY_CEPSTRUM_START = 64;
    private static final int NOISY_CEPSTRUM_LENGTH = 18;
    private static final int ACORR_START = 82;
    private static final int LTP_START = 87;
    private static final int LTP_LENGTH = 5;
    private static final int LOG_GAIN_START = 92;
    private static final int SPEC_WINDOW_SIZE = 320;
    private static final int SPEC_NUM_FREQS = 161;
    private static final int TYPE_VOICED = 2;

    private static final int[] CENTER_BINS_CLEAN = {
        0, 2, 5, 8, 10, 12, 15, 18, 20, 22, 25, 28, 30, 33, 35, 38, 40, 42, 45, 48, 50, 52, 55, 58, 60, 62, 65, 68,
        70, 73, 75, 78, 80, 82, 85, 88, 90, 92, 95, 98, 100, 102, 105, 108, 110, 112, 115, 118, 120, 122, 125, 128,
        130, 132, 135, 138, 140, 142, 145, 148, 150, 152, 155, 160
    };

    private static final int[] CENTER_BINS_NOISY = {
        0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 136, 160
    };

    private static final float[] BAND_WEIGHTS_CLEAN = {
        0.666666666667f, 0.400000000000f, 0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f,
        0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f,
        0.400000000000f, 0.400000000000f, 0.400000000000f, 0.400000000000f, 0.500000000000f, 0.400000000000f,
        0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f,
        0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f, 0.400000000000f, 0.400000000000f,
        0.400000000000f, 0.400000000000f, 0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f,
        0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f,
        0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f,
        0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f,
        0.333333333333f, 0.400000000000f, 0.500000000000f, 0.400000000000f, 0.333333333333f, 0.400000000000f,
        0.500000000000f, 0.400000000000f, 0.250000000000f, 0.333333333333f
    };

    private static final float[] BAND_WEIGHTS_NOISY = {
        0.400000000000f, 0.250000000000f, 0.250000000000f, 0.250000000000f, 0.250000000000f, 0.250000000000f,
        0.250000000000f, 0.250000000000f, 0.166666666667f, 0.125000000000f, 0.125000000000f, 0.125000000000f,
        0.083333333333f, 0.062500000000f, 0.062500000000f, 0.050000000000f, 0.041666666667f, 0.080000000000f
    };

    /**
     * The analysis window of the noisy cepstrum, a sine window of 320 samples; its first half is the cross-fade
     * window ({@code osce_window}).
     */
    private static final float[] WINDOW = new float[SPEC_WINDOW_SIZE];

    static {
        // osce_window[n] = sin(pi * (n + 0.5) / 320), the values of the C table printed with 12 decimals.
        var half = new float[]{
            0.004908718808f, 0.014725683311f, 0.024541228523f, 0.034354408400f, 0.044164277127f,
            0.053969889210f, 0.063770299562f, 0.073564563600f, 0.083351737332f, 0.093130877450f,
            0.102901041421f, 0.112661287575f, 0.122410675199f, 0.132148264628f, 0.141873117332f,
            0.151584296010f, 0.161280864678f, 0.170961888760f, 0.180626435180f, 0.190273572448f,
            0.199902370753f, 0.209511902052f, 0.219101240157f, 0.228669460829f, 0.238215641862f,
            0.247738863176f, 0.257238206902f, 0.266712757475f, 0.276161601717f, 0.285583828929f,
            0.294978530977f, 0.304344802381f, 0.313681740399f, 0.322988445118f, 0.332264019538f,
            0.341507569661f, 0.350718204573f, 0.359895036535f, 0.369037181064f, 0.378143757022f,
            0.387213886697f, 0.396246695891f, 0.405241314005f, 0.414196874117f, 0.423112513073f,
            0.431987371563f, 0.440820594212f, 0.449611329655f, 0.458358730621f, 0.467061954019f,
            0.475720161014f, 0.484332517110f, 0.492898192230f, 0.501416360796f, 0.509886201809f,
            0.518306898929f, 0.526677640552f, 0.534997619887f, 0.543266035038f, 0.551482089078f,
            0.559644990127f, 0.567753951426f, 0.575808191418f, 0.583806933818f, 0.591749407690f,
            0.599634847523f, 0.607462493302f, 0.615231590581f, 0.622941390558f, 0.630591150148f,
            0.638180132051f, 0.645707604824f, 0.653172842954f, 0.660575126926f, 0.667913743292f,
            0.675187984742f, 0.682397150168f, 0.689540544737f, 0.696617479953f, 0.703627273726f,
            0.710569250438f, 0.717442741007f, 0.724247082951f, 0.730981620454f, 0.737645704427f,
            0.744238692572f, 0.750759949443f, 0.757208846506f, 0.763584762206f, 0.769887082016f,
            0.776115198508f, 0.782268511401f, 0.788346427627f, 0.794348361383f, 0.800273734191f,
            0.806121974951f, 0.811892519997f, 0.817584813152f, 0.823198305781f, 0.828732456844f,
            0.834186732948f, 0.839560608398f, 0.844853565250f, 0.850065093356f, 0.855194690420f,
            0.860241862039f, 0.865206121757f, 0.870086991109f, 0.874883999665f, 0.879596685080f,
            0.884224593137f, 0.888767277786f, 0.893224301196f, 0.897595233788f, 0.901879654283f,
            0.906077149740f, 0.910187315596f, 0.914209755704f, 0.918144082372f, 0.921989916403f,
            0.925746887127f, 0.929414632439f, 0.932992798835f, 0.936481041442f, 0.939879024058f,
            0.943186419177f, 0.946402908026f, 0.949528180593f, 0.952561935658f, 0.955503880820f,
            0.958353732530f, 0.961111216112f, 0.963776065795f, 0.966348024735f, 0.968826845041f,
            0.971212287799f, 0.973504123096f, 0.975702130039f, 0.977806096779f, 0.979815820533f,
            0.981731107599f, 0.983551773378f, 0.985277642389f, 0.986908548290f, 0.988444333892f,
            0.989884851171f, 0.991229961288f, 0.992479534599f, 0.993633450666f, 0.994691598273f,
            0.995653875433f, 0.996520189401f, 0.997290456679f, 0.997964603026f, 0.998542563469f,
            0.999024282300f, 0.999409713092f, 0.999698818696f, 0.999891571247f, 0.999987952167f
        };
        for (var i = 0; i < SPEC_WINDOW_SIZE / 2; i++) {
            WINDOW[i] = half[i];
            WINDOW[SPEC_WINDOW_SIZE - 1 - i] = half[i];
        }
    }

    private final Nnet nnet = new Nnet();
    private final NnDsp dsp = new NnDsp(nnet);
    private final Freq freq = new Freq();
    private LaceModel lace;
    private NolaceModel nolace;
    private final float[] laceWindow = new float[LaceModel.LACE_OVERLAP_SIZE];
    private final float[] nolaceWindow = new float[NolaceModel.NOLACE_OVERLAP_SIZE];

    private int method;
    private float numbitsSmooth;
    private int pitchHangoverCount;
    private int lastLag;
    private int lastType;
    private final float[] signalHistory = new float[FEATURES_MAX_HISTORY];
    private int resetCount;

    private final float[] featureNetConv2State = new float[Math.max(LaceModel.LACE_FNET_CONV2_STATE_SIZE,
            NolaceModel.NOLACE_FNET_CONV2_STATE_SIZE)];
    private final float[] featureNetGruState = new float[NolaceModel.NOLACE_COND_DIM];
    private final float[] postCf1State = new float[NolaceModel.NOLACE_COND_DIM];
    private final float[] postCf2State = new float[NolaceModel.NOLACE_COND_DIM];
    private final float[] postAf1State = new float[NolaceModel.NOLACE_COND_DIM];
    private final float[] postAf2State = new float[NolaceModel.NOLACE_COND_DIM];
    private final float[] postAf3State = new float[NolaceModel.NOLACE_COND_DIM];
    private final NnDsp.AdaCombState cf1State = new NnDsp.AdaCombState();
    private final NnDsp.AdaCombState cf2State = new NnDsp.AdaCombState();
    private final NnDsp.AdaConvState af1State = new NnDsp.AdaConvState();
    private final NnDsp.AdaConvState af2State = new NnDsp.AdaConvState();
    private final NnDsp.AdaConvState af3State = new NnDsp.AdaConvState();
    private final NnDsp.AdaConvState af4State = new NnDsp.AdaConvState();
    private final NnDsp.AdaShapeState tdshape1State = new NnDsp.AdaShapeState();
    private final NnDsp.AdaShapeState tdshape2State = new NnDsp.AdaShapeState();
    private final NnDsp.AdaShapeState tdshape3State = new NnDsp.AdaShapeState();
    private float preemphMem;
    private float deemphMem;

    private final float[] inBuffer = new float[320];
    private final float[] outBuffer = new float[320];
    private final float[] features = new float[4 * FEATURE_DIM];
    private final float[] numbits = new float[2];
    private final int[] periods = new int[4];
    private final float[] buffer = new float[FEATURES_MAX_HISTORY + MAX_FEATURE_FRAMES * 80];
    private final float[] specBuffer = new float[SPEC_WINDOW_SIZE];
    private final float[] spectrum = new float[2 * Freq.FREQ_SIZE];
    private final float[] netInput = new float[4 * Math.max(NolaceModel.NOLACE_COND_DIM,
            NolaceModel.NOLACE_HIDDEN_FEATURE_DIM) + 256];
    private final float[] netOutput = new float[4 * Math.max(NolaceModel.NOLACE_COND_DIM,
            NolaceModel.NOLACE_HIDDEN_FEATURE_DIM)];
    private final float[] numbitsEmbedded = new float[2 * NolaceModel.NOLACE_NUMBITS_EMBEDDING_DIM];
    private final float[] featureBuffer = new float[4 * NolaceModel.NOLACE_COND_DIM];
    private final float[] featureTransformBuffer = new float[4 * NolaceModel.NOLACE_COND_DIM];
    private final float[] xBuffer1 = new float[8 * NolaceModel.NOLACE_FRAME_SIZE];
    private final float[] xBuffer2 = new float[8 * NolaceModel.NOLACE_FRAME_SIZE];

    /**
     * Constructs an enhancer reset to NoLACE, as {@code silk_reset_decoder} leaves it.
     */
    public Osce() {
        NnDsp.computeOverlapWindow(laceWindow, LaceModel.LACE_OVERLAP_SIZE);
        NnDsp.computeOverlapWindow(nolaceWindow, NolaceModel.NOLACE_OVERLAP_SIZE);
        reset(METHOD_NOLACE);
    }

    /**
     * {@return the current method, one of the {@code METHOD_*} constants}
     */
    public int method() {
        return method;
    }

    /**
     * Clears the feature and filter state and selects a method ({@code osce_reset}); the next two frames pass
     * through and cross-fade into the enhanced signal.
     *
     * @param method the {@code METHOD_*} constant
     */
    public void reset(int method) {
        numbitsSmooth = 0;
        pitchHangoverCount = 0;
        lastLag = 0;
        lastType = 0;
        Arrays.fill(signalHistory, 0);
        if (method == METHOD_LACE || method == METHOD_NOLACE) {
            Arrays.fill(featureNetConv2State, 0);
            Arrays.fill(featureNetGruState, 0);
            Arrays.fill(postCf1State, 0);
            Arrays.fill(postCf2State, 0);
            Arrays.fill(postAf1State, 0);
            Arrays.fill(postAf2State, 0);
            Arrays.fill(postAf3State, 0);
            cf1State.reset();
            cf2State.reset();
            af1State.reset();
            af2State.reset();
            af3State.reset();
            af4State.reset();
            tdshape1State.reset();
            tdshape2State.reset();
            tdshape3State.reset();
            preemphMem = 0;
            deemphMem = 0;
        }
        this.method = method;
        resetCount = 2;
    }

    /**
     * Enhances one decoded SILK frame in place ({@code osce_enhance_frame}); frames other than 20 ms at 16 kHz
     * only reset the state.
     *
     * @param xq the decoded samples, enhanced in place
     * @param xqOffset the index of the first sample
     * @param fsKHz the internal sampling rate in kHz
     * @param nbSubfr the number of 5 ms subframes
     * @param lpcOrder the LPC order
     * @param signalType the SILK signal type of the frame
     * @param predCoefQ12 the LPC coefficients of the two halves of the frame, in Q12
     * @param pitchL the pitch lag of each subframe
     * @param ltpCoefQ14 the five LTP coefficients of each subframe, in Q14
     * @param gainsQ16 the gain of each subframe, in Q16
     * @param numBits the size of the frame's SILK payload in bits
     */
    public void enhance(short[] xq, int xqOffset, int fsKHz, int nbSubfr, int lpcOrder, int signalType,
                        short[][] predCoefQ12, int[] pitchL, short[] ltpCoefQ14, int[] gainsQ16, int numBits) {
        if (fsKHz != 16 || nbSubfr != 4) {
            reset(method);
            return;
        }
        calculateFeatures(xq, xqOffset, lpcOrder, signalType, predCoefQ12, pitchL, ltpCoefQ14, gainsQ16, numBits);
        for (var i = 0; i < 320; i++) {
            inBuffer[i] = ((float) xq[xqOffset + i]) * (1.f / 32768.f);
        }
        switch (method) {
            case METHOD_LACE -> laceProcess20msFrame();
            case METHOD_NOLACE -> nolaceProcess20msFrame();
            default -> System.arraycopy(inBuffer, 0, outBuffer, 0, 320);
        }
        if (resetCount > 1) {
            System.arraycopy(inBuffer, 0, outBuffer, 0, 320);
            resetCount--;
        } else if (resetCount != 0) {
            for (var i = 0; i < 160; i++) {
                outBuffer[i] = WINDOW[i] * outBuffer[i] + (1.f - WINDOW[i]) * inBuffer[i];
            }
            resetCount = 0;
        }
        for (var i = 0; i < 320; i++) {
            var tmp = 32768.f * outBuffer[i];
            if (tmp > 32767.f) {
                tmp = 32767.f;
            }
            if (tmp < -32767.f) {
                tmp = -32767.f;
            }
            xq[xqOffset + i] = (short) MathOps.float2int(tmp);
        }
    }

    /**
     * Interpolates power bins into overlapping triangular bands ({@code apply_filterbank}).
     */
    private static void applyFilterbank(float[] xOut, int xOutOffset, float[] xIn, int[] centerBins, float[] weights,
                                        int numBands) {
        xOut[xOutOffset] = 0;
        for (var b = 0; b < numBands - 1; b++) {
            xOut[xOutOffset + b + 1] = 0;
            for (var i = centerBins[b]; i < centerBins[b + 1]; i++) {
                var frac = (float) (centerBins[b + 1] - i) / (centerBins[b + 1] - centerBins[b]);
                xOut[xOutOffset + b] += weights[b] * frac * xIn[i];
                xOut[xOutOffset + b + 1] += weights[b + 1] * (1 - frac) * xIn[i];
            }
        }
        xOut[xOutOffset + numBands - 1] += weights[numBands - 1] * xIn[centerBins[numBands - 1]];
    }

    /**
     * Replaces the first 161 values of {@link #specBuffer} by the magnitude spectrum of its 320 samples
     * ({@code mag_spec_320_onesided}).
     */
    private void magSpec320() {
        freq.forwardTransform(spectrum, specBuffer, 0);
        for (var k = 0; k < SPEC_NUM_FREQS; k++) {
            var re = spectrum[2 * k];
            var im = spectrum[2 * k + 1];
            specBuffer[k] = (float) (SPEC_WINDOW_SIZE * StrictMath.sqrt(re * re + im * im));
        }
    }

    /**
     * Computes the clean log spectrum of the LPC envelope ({@code calculate_log_spectrum_from_lpc}).
     */
    private void logSpectrumFromLpc(float[] spec, int specOffset, short[] aQ12, int lpcOrder) {
        Arrays.fill(specBuffer, 0);
        specBuffer[0] = 1;
        for (var i = 0; i < lpcOrder; i++) {
            specBuffer[i + 1] = -(float) aQ12[i] / (1 << 12);
        }
        magSpec320();
        for (var i = 0; i < SPEC_NUM_FREQS; i++) {
            specBuffer[i] = 1.f / (specBuffer[i] + 1e-9f);
        }
        applyFilterbank(spec, specOffset, specBuffer, CENTER_BINS_CLEAN, BAND_WEIGHTS_CLEAN, CLEAN_SPEC_NUM_BANDS);
        for (var i = 0; i < CLEAN_SPEC_NUM_BANDS; i++) {
            spec[specOffset + i] = (float) (0.3f * StrictMath.log(spec[specOffset + i] + 1e-9f));
        }
    }

    /**
     * Computes the cepstrum of 320 samples of the decoded signal ({@code calculate_cepstrum}).
     */
    private void cepstrum(float[] cepstrum, int cepstrumOffset, float[] signal, int signalOffset) {
        for (var n = 0; n < SPEC_WINDOW_SIZE; n++) {
            specBuffer[n] = WINDOW[n] * signal[signalOffset + n];
        }
        magSpec320();
        var spec = SPEC_NUM_FREQS + 3;
        applyFilterbank(specBuffer, spec, specBuffer, CENTER_BINS_NOISY, BAND_WEIGHTS_NOISY, NOISY_SPEC_NUM_BANDS);
        for (var n = 0; n < NOISY_SPEC_NUM_BANDS; n++) {
            specBuffer[spec + n] = (float) StrictMath.log(specBuffer[spec + n] + 1e-9f);
        }
        Freq.dct(cepstrum, cepstrumOffset, specBuffer, spec);
    }

    /**
     * Computes the normalized correlations at five lags around the pitch lag ({@code calculate_acorr}).
     */
    private static void acorr(float[] acorr, int acorrOffset, float[] signal, int signalOffset, int lag) {
        for (var k = -2; k <= 2; k++) {
            var xx = 0f;
            var xy = 0f;
            var yy = 0f;
            for (var n = 0; n < 80; n++) {
                var s = signal[signalOffset + n];
                var t = signal[signalOffset + n - lag + k];
                xx += s * s;
                yy += t * t;
                xy += s * t;
            }
            acorr[acorrOffset + k + 2] = (float) (xy / StrictMath.sqrt(xx * yy + 1e-9f));
        }
    }

    /**
     * Replaces the pitch lag of unvoiced frames ({@code pitch_postprocessing}, hangover disabled).
     */
    private int pitchPostprocessing(int lag, int type) {
        int newLag;
        if (type != TYPE_VOICED) {
            newLag = NO_PITCH_VALUE;
            pitchHangoverCount = 0;
        } else {
            newLag = lag;
            lastLag = lag;
            pitchHangoverCount = 0;
        }
        lastType = type;
        return newLag;
    }

    /**
     * Computes the 93 features of each subframe, the bit counts and the pitch lags
     * ({@code osce_calculate_features}).
     */
    private void calculateFeatures(short[] xq, int xqOffset, int lpcOrder, int signalType, short[][] predCoefQ12,
                                   int[] pitchL, short[] ltpCoefQ14, int[] gainsQ16, int numBits) {
        Arrays.fill(buffer, 0);
        var numSamples = 4 * 80;
        numbitsSmooth = 0.9f * numbitsSmooth + 0.1f * numBits;
        numbits[0] = numBits;
        numbits[1] = numbitsSmooth;
        for (var n = 0; n < numSamples; n++) {
            buffer[FEATURES_MAX_HISTORY + n] = (float) xq[xqOffset + n] / (1 << 15);
        }
        System.arraycopy(signalHistory, 0, buffer, 0, FEATURES_MAX_HISTORY);
        for (var k = 0; k < 4; k++) {
            var p = k * FEATURE_DIM;
            var frame = FEATURES_MAX_HISTORY + k * 80;
            if (k % 2 == 0) {
                logSpectrumFromLpc(features, p + CLEAN_SPEC_START, predCoefQ12[k >> 1], lpcOrder);
                cepstrum(features, p + NOISY_CEPSTRUM_START, buffer, frame - 160);
            } else {
                System.arraycopy(features, p + CLEAN_SPEC_START - FEATURE_DIM, features, p + CLEAN_SPEC_START,
                        CLEAN_SPEC_LENGTH);
                System.arraycopy(features, p + NOISY_CEPSTRUM_START - FEATURE_DIM, features,
                        p + NOISY_CEPSTRUM_START, NOISY_CEPSTRUM_LENGTH);
            }
            periods[k] = pitchPostprocessing(pitchL[k], signalType);
            acorr(features, p + ACORR_START, buffer, frame, periods[k]);
            for (var i = 0; i < LTP_LENGTH; i++) {
                features[p + LTP_START + i] = (float) ltpCoefQ14[k * LTP_LENGTH + i] / (1 << 14);
            }
            features[p + LOG_GAIN_START] = (float) StrictMath.log((float) gainsQ16[k] / (1L << 16) + 1e-9f);
        }
        System.arraycopy(buffer, numSamples, signalHistory, 0, FEATURES_MAX_HISTORY);
    }

    /**
     * Embeds a bit count as eight sines of its clipped logarithm ({@code compute_lace_numbits_embedding}).
     */
    private static void numbitsEmbedding(float[] emb, int offset, float numbits, float minVal, float maxVal,
                                         float s0, float s1, float s2, float s3, float s4, float s5, float s6,
                                         float s7) {
        numbits = (float) StrictMath.log(numbits);
        // The C CLIP macro returns the unclipped value below the minimum.
        var low = numbits < minVal ? minVal : numbits;
        var clipped = low > maxVal ? maxVal : numbits;
        var x = clipped - (maxVal + minVal) / 2;
        emb[offset] = (float) MathOps.sin(x * s0 - 0.5f);
        emb[offset + 1] = (float) MathOps.sin(x * s1 - 0.5f);
        emb[offset + 2] = (float) MathOps.sin(x * s2 - 0.5f);
        emb[offset + 3] = (float) MathOps.sin(x * s3 - 0.5f);
        emb[offset + 4] = (float) MathOps.sin(x * s4 - 0.5f);
        emb[offset + 5] = (float) MathOps.sin(x * s5 - 0.5f);
        emb[offset + 6] = (float) MathOps.sin(x * s6 - 0.5f);
        emb[offset + 7] = (float) MathOps.sin(x * s7 - 0.5f);
    }

    /**
     * Runs the LACE feature network ({@code lace_feature_net}) into {@link #featureBuffer}.
     */
    private void laceFeatureNet(LaceModel m) {
        final var numFeatures = LaceModel.LACE_NUM_FEATURES;
        final var pitchDim = LaceModel.LACE_PITCH_EMBEDDING_DIM;
        final var embDim = LaceModel.LACE_NUMBITS_EMBEDDING_DIM;
        final var hidden = LaceModel.LACE_HIDDEN_FEATURE_DIM;
        final var cond = LaceModel.LACE_COND_DIM;
        var low = (float) StrictMath.log(LaceModel.LACE_NUMBITS_RANGE_LOW);
        var high = (float) StrictMath.log(LaceModel.LACE_NUMBITS_RANGE_HIGH);
        for (var n = 0; n < 2; n++) {
            numbitsEmbedding(numbitsEmbedded, n * embDim, numbits[n], low, high, LaceModel.LACE_NUMBITS_SCALE_0,
                    LaceModel.LACE_NUMBITS_SCALE_1, LaceModel.LACE_NUMBITS_SCALE_2, LaceModel.LACE_NUMBITS_SCALE_3,
                    LaceModel.LACE_NUMBITS_SCALE_4, LaceModel.LACE_NUMBITS_SCALE_5, LaceModel.LACE_NUMBITS_SCALE_6,
                    LaceModel.LACE_NUMBITS_SCALE_7);
        }
        for (var s = 0; s < 4; s++) {
            System.arraycopy(features, s * numFeatures, netInput, 0, numFeatures);
            System.arraycopy(m.lace_pitch_embedding.float_weights, periods[s] * pitchDim, netInput, numFeatures,
                    pitchDim);
            System.arraycopy(numbitsEmbedded, 0, netInput, numFeatures + pitchDim, 2 * embDim);
            nnet.conv1d(m.lace_fnet_conv1, netOutput, s * hidden, null, 0, netInput, 0,
                    numFeatures + pitchDim + 2 * embDim, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(netOutput, 0, netInput, 0, 4 * hidden);
        nnet.conv1d(m.lace_fnet_conv2, netOutput, 0, featureNetConv2State, 0, netInput, 0, 4 * hidden,
                Nnet.ACTIVATION_TANH);
        System.arraycopy(netOutput, 0, netInput, 0, 4 * cond);
        nnet.dense(m.lace_fnet_tconv, netOutput, 0, netInput, 0, Nnet.ACTIVATION_TANH);
        System.arraycopy(netOutput, 0, netInput, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            nnet.gru(m.lace_fnet_gru_input, m.lace_fnet_gru_recurrent, featureNetGruState, 0, netInput, s * cond);
            System.arraycopy(featureNetGruState, 0, featureBuffer, s * cond, cond);
        }
    }

    /**
     * Enhances {@link #inBuffer} into {@link #outBuffer} with LACE ({@code lace_process_20ms_frame}).
     */
    private void laceProcess20msFrame() {
        var m = lace == null ? (lace = LaceModel.get()) : lace;
        final var frame = LaceModel.LACE_FRAME_SIZE;
        final var cond = LaceModel.LACE_COND_DIM;
        final var overlap = LaceModel.LACE_OVERLAP_SIZE;
        var x = xBuffer1;
        for (var i = 0; i < 4 * frame; i++) {
            x[i] = inBuffer[i] - LaceModel.LACE_PREEMPH * preemphMem;
            preemphMem = inBuffer[i];
        }
        laceFeatureNet(m);
        for (var s = 0; s < 4; s++) {
            dsp.adacomb(cf1State, x, s * frame, x, s * frame, featureBuffer, s * cond, m.lace_cf1_kernel,
                    m.lace_cf1_gain, m.lace_cf1_global_gain, periods[s], frame, overlap,
                    LaceModel.LACE_CF1_KERNEL_SIZE, LaceModel.LACE_CF1_LEFT_PADDING, LaceModel.LACE_CF1_FILTER_GAIN_A,
                    LaceModel.LACE_CF1_FILTER_GAIN_B, LaceModel.LACE_CF1_LOG_GAIN_LIMIT, laceWindow);
        }
        for (var s = 0; s < 4; s++) {
            dsp.adacomb(cf2State, x, s * frame, x, s * frame, featureBuffer, s * cond, m.lace_cf2_kernel,
                    m.lace_cf2_gain, m.lace_cf2_global_gain, periods[s], frame, overlap,
                    LaceModel.LACE_CF2_KERNEL_SIZE, LaceModel.LACE_CF2_LEFT_PADDING, LaceModel.LACE_CF2_FILTER_GAIN_A,
                    LaceModel.LACE_CF2_FILTER_GAIN_B, LaceModel.LACE_CF2_LOG_GAIN_LIMIT, laceWindow);
        }
        for (var s = 0; s < 4; s++) {
            dsp.adaconv(af1State, x, s * frame, x, s * frame, featureBuffer, s * cond, m.lace_af1_kernel,
                    m.lace_af1_gain, frame, overlap, LaceModel.LACE_AF1_IN_CHANNELS, LaceModel.LACE_AF1_OUT_CHANNELS,
                    LaceModel.LACE_AF1_KERNEL_SIZE, LaceModel.LACE_AF1_LEFT_PADDING, LaceModel.LACE_AF1_FILTER_GAIN_A,
                    LaceModel.LACE_AF1_FILTER_GAIN_B, laceWindow);
        }
        for (var i = 0; i < 4 * frame; i++) {
            outBuffer[i] = x[i] + LaceModel.LACE_PREEMPH * deemphMem;
            deemphMem = outBuffer[i];
        }
    }

    /**
     * Runs the NoLACE feature network ({@code nolace_feature_net}) into {@link #featureBuffer}.
     */
    private void nolaceFeatureNet(NolaceModel m) {
        final var numFeatures = NolaceModel.NOLACE_NUM_FEATURES;
        final var pitchDim = NolaceModel.NOLACE_PITCH_EMBEDDING_DIM;
        final var embDim = NolaceModel.NOLACE_NUMBITS_EMBEDDING_DIM;
        final var hidden = NolaceModel.NOLACE_HIDDEN_FEATURE_DIM;
        final var cond = NolaceModel.NOLACE_COND_DIM;
        var low = (float) StrictMath.log(NolaceModel.NOLACE_NUMBITS_RANGE_LOW);
        var high = (float) StrictMath.log(NolaceModel.NOLACE_NUMBITS_RANGE_HIGH);
        for (var n = 0; n < 2; n++) {
            numbitsEmbedding(numbitsEmbedded, n * embDim, numbits[n], low, high, NolaceModel.NOLACE_NUMBITS_SCALE_0,
                    NolaceModel.NOLACE_NUMBITS_SCALE_1, NolaceModel.NOLACE_NUMBITS_SCALE_2,
                    NolaceModel.NOLACE_NUMBITS_SCALE_3, NolaceModel.NOLACE_NUMBITS_SCALE_4,
                    NolaceModel.NOLACE_NUMBITS_SCALE_5, NolaceModel.NOLACE_NUMBITS_SCALE_6,
                    NolaceModel.NOLACE_NUMBITS_SCALE_7);
        }
        for (var s = 0; s < 4; s++) {
            System.arraycopy(features, s * numFeatures, netInput, 0, numFeatures);
            System.arraycopy(m.nolace_pitch_embedding.float_weights, periods[s] * pitchDim, netInput, numFeatures,
                    pitchDim);
            System.arraycopy(numbitsEmbedded, 0, netInput, numFeatures + pitchDim, 2 * embDim);
            nnet.conv1d(m.nolace_fnet_conv1, netOutput, s * hidden, null, 0, netInput, 0,
                    numFeatures + pitchDim + 2 * embDim, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(netOutput, 0, netInput, 0, 4 * hidden);
        nnet.conv1d(m.nolace_fnet_conv2, netOutput, 0, featureNetConv2State, 0, netInput, 0, 4 * hidden,
                Nnet.ACTIVATION_TANH);
        System.arraycopy(netOutput, 0, netInput, 0, 4 * cond);
        nnet.dense(m.nolace_fnet_tconv, netOutput, 0, netInput, 0, Nnet.ACTIVATION_TANH);
        System.arraycopy(netOutput, 0, netInput, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            nnet.gru(m.nolace_fnet_gru_input, m.nolace_fnet_gru_recurrent, featureNetGruState, 0, netInput, s * cond);
            System.arraycopy(featureNetGruState, 0, featureBuffer, s * cond, cond);
        }
    }

    /**
     * Enhances {@link #inBuffer} into {@link #outBuffer} with NoLACE ({@code nolace_process_20ms_frame}).
     */
    private void nolaceProcess20msFrame() {
        var m = nolace == null ? (nolace = NolaceModel.get()) : nolace;
        final var frame = NolaceModel.NOLACE_FRAME_SIZE;
        final var cond = NolaceModel.NOLACE_COND_DIM;
        final var overlap = NolaceModel.NOLACE_OVERLAP_SIZE;
        var x1 = xBuffer1;
        var x2 = xBuffer2;
        var w = nolaceWindow;
        for (var i = 0; i < 4 * frame; i++) {
            x1[i] = inBuffer[i] - NolaceModel.NOLACE_PREEMPH * preemphMem;
            preemphMem = inBuffer[i];
        }
        nolaceFeatureNet(m);
        for (var s = 0; s < 4; s++) {
            dsp.adacomb(cf1State, x1, s * frame, x1, s * frame, featureBuffer, s * cond, m.nolace_cf1_kernel,
                    m.nolace_cf1_gain, m.nolace_cf1_global_gain, periods[s], frame, overlap,
                    NolaceModel.NOLACE_CF1_KERNEL_SIZE, NolaceModel.NOLACE_CF1_LEFT_PADDING,
                    NolaceModel.NOLACE_CF1_FILTER_GAIN_A, NolaceModel.NOLACE_CF1_FILTER_GAIN_B,
                    NolaceModel.NOLACE_CF1_LOG_GAIN_LIMIT, w);
            nnet.conv1d(m.nolace_post_cf1, featureTransformBuffer, s * cond, postCf1State, 0, featureBuffer,
                    s * cond, cond, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(featureTransformBuffer, 0, featureBuffer, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            dsp.adacomb(cf2State, x1, s * frame, x1, s * frame, featureBuffer, s * cond, m.nolace_cf2_kernel,
                    m.nolace_cf2_gain, m.nolace_cf2_global_gain, periods[s], frame, overlap,
                    NolaceModel.NOLACE_CF2_KERNEL_SIZE, NolaceModel.NOLACE_CF2_LEFT_PADDING,
                    NolaceModel.NOLACE_CF2_FILTER_GAIN_A, NolaceModel.NOLACE_CF2_FILTER_GAIN_B,
                    NolaceModel.NOLACE_CF2_LOG_GAIN_LIMIT, w);
            nnet.conv1d(m.nolace_post_cf2, featureTransformBuffer, s * cond, postCf2State, 0, featureBuffer,
                    s * cond, cond, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(featureTransformBuffer, 0, featureBuffer, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            dsp.adaconv(af1State, x2, s * frame * NolaceModel.NOLACE_AF1_OUT_CHANNELS, x1, s * frame, featureBuffer,
                    s * cond, m.nolace_af1_kernel, m.nolace_af1_gain, frame, overlap,
                    NolaceModel.NOLACE_AF1_IN_CHANNELS, NolaceModel.NOLACE_AF1_OUT_CHANNELS,
                    NolaceModel.NOLACE_AF1_KERNEL_SIZE, NolaceModel.NOLACE_AF1_LEFT_PADDING,
                    NolaceModel.NOLACE_AF1_FILTER_GAIN_A, NolaceModel.NOLACE_AF1_FILTER_GAIN_B, w);
            nnet.conv1d(m.nolace_post_af1, featureTransformBuffer, s * cond, postAf1State, 0, featureBuffer,
                    s * cond, cond, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(featureTransformBuffer, 0, featureBuffer, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            var second = s * NolaceModel.NOLACE_AF1_OUT_CHANNELS * frame + frame;
            dsp.adashape(tdshape1State, x2, second, x2, second, featureBuffer, s * cond, m.nolace_tdshape1_alpha1_f,
                    m.nolace_tdshape1_alpha1_t, m.nolace_tdshape1_alpha2, NolaceModel.NOLACE_TDSHAPE1_FEATURE_DIM,
                    NolaceModel.NOLACE_TDSHAPE1_FRAME_SIZE, NolaceModel.NOLACE_TDSHAPE1_AVG_POOL_K);
            dsp.adaconv(af2State, x1, s * frame * NolaceModel.NOLACE_AF2_OUT_CHANNELS, x2,
                    s * frame * NolaceModel.NOLACE_AF2_IN_CHANNELS, featureBuffer, s * cond, m.nolace_af2_kernel,
                    m.nolace_af2_gain, frame, overlap, NolaceModel.NOLACE_AF2_IN_CHANNELS,
                    NolaceModel.NOLACE_AF2_OUT_CHANNELS, NolaceModel.NOLACE_AF2_KERNEL_SIZE,
                    NolaceModel.NOLACE_AF2_LEFT_PADDING, NolaceModel.NOLACE_AF2_FILTER_GAIN_A,
                    NolaceModel.NOLACE_AF2_FILTER_GAIN_B, w);
            nnet.conv1d(m.nolace_post_af2, featureTransformBuffer, s * cond, postAf2State, 0, featureBuffer,
                    s * cond, cond, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(featureTransformBuffer, 0, featureBuffer, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            var second = s * NolaceModel.NOLACE_AF2_OUT_CHANNELS * frame + frame;
            dsp.adashape(tdshape2State, x1, second, x1, second, featureBuffer, s * cond, m.nolace_tdshape2_alpha1_f,
                    m.nolace_tdshape2_alpha1_t, m.nolace_tdshape2_alpha2, NolaceModel.NOLACE_TDSHAPE2_FEATURE_DIM,
                    NolaceModel.NOLACE_TDSHAPE2_FRAME_SIZE, NolaceModel.NOLACE_TDSHAPE2_AVG_POOL_K);
            dsp.adaconv(af3State, x2, s * frame * NolaceModel.NOLACE_AF3_OUT_CHANNELS, x1,
                    s * frame * NolaceModel.NOLACE_AF3_IN_CHANNELS, featureBuffer, s * cond, m.nolace_af3_kernel,
                    m.nolace_af3_gain, frame, overlap, NolaceModel.NOLACE_AF3_IN_CHANNELS,
                    NolaceModel.NOLACE_AF3_OUT_CHANNELS, NolaceModel.NOLACE_AF3_KERNEL_SIZE,
                    NolaceModel.NOLACE_AF3_LEFT_PADDING, NolaceModel.NOLACE_AF3_FILTER_GAIN_A,
                    NolaceModel.NOLACE_AF3_FILTER_GAIN_B, w);
            nnet.conv1d(m.nolace_post_af3, featureTransformBuffer, s * cond, postAf3State, 0, featureBuffer,
                    s * cond, cond, Nnet.ACTIVATION_TANH);
        }
        System.arraycopy(featureTransformBuffer, 0, featureBuffer, 0, 4 * cond);
        for (var s = 0; s < 4; s++) {
            var second = s * NolaceModel.NOLACE_AF3_OUT_CHANNELS * frame + frame;
            dsp.adashape(tdshape3State, x2, second, x2, second, featureBuffer, s * cond, m.nolace_tdshape3_alpha1_f,
                    m.nolace_tdshape3_alpha1_t, m.nolace_tdshape3_alpha2, NolaceModel.NOLACE_TDSHAPE3_FEATURE_DIM,
                    NolaceModel.NOLACE_TDSHAPE3_FRAME_SIZE, NolaceModel.NOLACE_TDSHAPE3_AVG_POOL_K);
            dsp.adaconv(af4State, x1, s * frame * NolaceModel.NOLACE_AF4_OUT_CHANNELS, x2,
                    s * frame * NolaceModel.NOLACE_AF4_IN_CHANNELS, featureBuffer, s * cond, m.nolace_af4_kernel,
                    m.nolace_af4_gain, frame, overlap, NolaceModel.NOLACE_AF4_IN_CHANNELS,
                    NolaceModel.NOLACE_AF4_OUT_CHANNELS, NolaceModel.NOLACE_AF4_KERNEL_SIZE,
                    NolaceModel.NOLACE_AF4_LEFT_PADDING, NolaceModel.NOLACE_AF4_FILTER_GAIN_A,
                    NolaceModel.NOLACE_AF4_FILTER_GAIN_B, w);
        }
        for (var i = 0; i < 4 * frame; i++) {
            outBuffer[i] = x1[i] + NolaceModel.NOLACE_PREEMPH * deemphMem;
            deemphMem = outBuffer[i];
        }
    }
}
