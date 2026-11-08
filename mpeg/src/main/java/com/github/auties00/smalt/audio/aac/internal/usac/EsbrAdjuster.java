/* Ports the ISO/IEC 23003-3:2019 reference software (ct_envcalc.c: calculateSbrEnvelope with the predictive
   vector coding path, createLimiterBands, apply_inter_tes). Copyright Coding Technologies, Sony Corporation,
   Dolby Laboratories and ISO/IEC 2002-2008; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.Arrays;

/**
 * Adjusts the envelope of the generated high band of one SBR channel ({@code calculateSbrEnvelope}): the
 * envelope and noise floor estimation, the limiter, the gain and noise smoothing, the additional sinusoids and
 * the inter-subband temporal envelope shaping of ISO/IEC 23003-3 subclause 7.5.5, and the envelope of predictive
 * vector coding frames.
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrAdjuster {
    private static final float EPS = 1e-12f;
    private static final float[] LIMITER_GAINS = {0.70795f, 1.0f, 1.41254f, 1e10f};
    private static final int[] SMOOTH_LENGTHS = {4, 0};
    private static final float[][] SMOOTHING = {
            {1.0f},
            {0.33333333333333f, 0.66666666666666f},
            {0.12500000000000f, 0.37500000000000f, 0.50000000000000f},
            {0.05857864376269f, 0.20000000000000f, 0.34142135623731f, 0.40000000000000f},
            {0.03183050093751f, 0.11516383427084f, 0.21816949906249f, 0.30150283239582f, 0.33333333333333f}
    };
    private static final float[] PHASE_REAL = {1.0f, 0.0f, -1.0f, 0.0f};
    private static final float[] PHASE_IMAG = {0.0f, 1.0f, 0.0f, -1.0f};
    private static final float[] TES_GAMMA = {0.0f, 1.0f, 2.0f, 4.0f};
    private static final double[] LIMITER_BANDS_PER_OCTAVE = {0, 1.2, 2.0, 3.0};
    private static final int MAX_SMOOTH = 4;
    private static final int SLOTS = 48;

    private boolean initialized;
    private int harmonicIndex;
    private int phaseIndex;
    private final int[] previousHarmonics = new int[64];
    private boolean startUp = true;
    private boolean startUpPvc = true;
    private final float[][] gainHistory = new float[MAX_SMOOTH + 1][64];
    private final float[][] noiseHistory = new float[MAX_SMOOTH + 1][64];
    private final int[][] limiterTable = new int[4][64];
    private final int[] limiterCount = new int[4];
    private int previousPatchingMode;
    private final int[] previousHarmonicsVarlen = new int[64];
    private final int[] harmonicsVarlen = new int[64];

    /**
     * The noise floor of the last noise envelope of the previous frame, used by predictive vector coding.
     */
    private final float[] previousLastNoise = new float[EsbrFrame.MAX_NOISE_VALUES];

    /**
     * The number of noise floor bands of the last predictive vector coding frame.
     */
    int pvcNoiseBands;

    private final int[] harmonics = new int[64];
    private final float[] estimate = new float[64];
    private final float[] reference = new float[64];
    private final float[] gain = new float[64];
    private final float[] tone = new float[64];
    private final float[] noise = new float[64];
    private final float[] temporary = new float[64];
    private final int[] patchBorders = new int[EsbrHfGenerator.MAX_PATCHES + 1];
    private final int[] work = new int[64 + EsbrHfGenerator.MAX_PATCHES + 1];
    private final float[] tesLow = new float[80];
    private final float[] tesHigh = new float[80];
    private final float[] tesGain = new float[80];

    private final float[][] mapped;
    private final float[][] pvcNoise;
    private final float[][] pvcEstimate;
    private final float[][] pvcReference;
    private final float[][] pvcGain;
    private final float[][] pvcTone;
    private final float[][] pvcEnvelope;

    /**
     * Constructs an adjuster.
     *
     * @param pvc whether the channel may use predictive vector coding
     */
    EsbrAdjuster(boolean pvc) {
        if (pvc) {
            mapped = new float[64][SLOTS];
            pvcNoise = new float[64][SLOTS];
            pvcEstimate = new float[64][SLOTS];
            pvcReference = new float[64][SLOTS];
            pvcGain = new float[64][SLOTS];
            pvcTone = new float[64][SLOTS];
            pvcEnvelope = new float[64][SLOTS];
        } else {
            mapped = null;
            pvcNoise = null;
            pvcEstimate = null;
            pvcReference = null;
            pvcGain = null;
            pvcTone = null;
            pvcEnvelope = null;
        }
    }

    /**
     * Adjusts the high band of one frame.
     *
     * @param frame the channel's frame data
     * @param bands the channel's band tables
     * @param high the real parts of the generated high band, adjusted in place
     * @param highImag the imaginary parts of the generated high band, adjusted in place
     * @param highBase the index of the frame's first high band slot
     * @param codec the real parts of the core band
     * @param codecImag the imaginary parts of the core band
     * @param codecBase the index of the frame's first core band slot
     * @param reset whether the band tables were rebuilt for this frame
     * @param crossover the crossover subbands of the harmonic transposer, or {@code null} without it
     * @param generator the generator that produced the high band
     * @param sbr41 whether the element uses the 4:1 ratio
     * @param pvcFrame whether the frame uses predictive vector coding
     * @param pvcOut the predicted energies of a predictive vector coding frame
     * @param previousPvcFrame whether the previous frame used predictive vector coding
     */
    void adjust(EsbrFrame frame, EsbrFrequencies bands, float[][] high, float[][] highImag, int highBase,
                float[][] codec, float[][] codecImag, int codecBase, boolean reset, int[] crossover,
                EsbrHfGenerator generator, boolean sbr41, boolean pvcFrame, float[] pvcOut,
                boolean previousPvcFrame) {
        var table = bands.table;
        var nSfb = bands.bands;
        var noiseTable = bands.noise;
        var noiseBands = bands.noiseBands;
        var envelope = frame.envelope;
        var nL = frame.noiseLevel;
        var frameInfo = frame.frameInfo;
        var pvcFrameInfo = frame.pvcFrameInfo;
        var interpolation = frame.header.interpolation;
        var smoothingLength = frame.header.smoothing;
        var limiterBand = frame.header.limiterBands;
        var limiterGains = frame.header.limiterGains;
        var addHarmonics = frame.addHarmonics;
        var lowSubband = table[EsbrFrequencies.LOW][0];
        var highSubband = table[EsbrFrequencies.LOW][nSfb[EsbrFrequencies.LOW]];
        var noSubbands = highSubband - lowSubband;
        var nEnv = frameInfo[0];
        var sEnv = frameInfo[2 * (nEnv + 1)];
        var rate = sbr41 ? frame.rate : 2;
        if (!initialized) {
            initialized = true;
            Arrays.fill(previousHarmonics, 0);
            frame.previousEnvelopeIsShort = -1;
        }
        if (reset) {
            startUp = true;
            startUpPvc = true;
            phaseIndex = 0;
            createLimiterBands(table[EsbrFrequencies.LOW], nSfb[EsbrFrequencies.LOW], crossover,
                    frame.patchingMode, sbr41, generator);
        }
        if (frame.patchingMode != previousPatchingMode) {
            createLimiterBands(table[EsbrFrequencies.LOW], nSfb[EsbrFrequencies.LOW], crossover,
                    frame.patchingMode, sbr41, generator);
            previousPatchingMode = frame.patchingMode;
        }
        Arrays.fill(harmonics, 0);
        var hiTable = table[EsbrFrequencies.HIGH];
        for (var i = 0; i < nSfb[EsbrFrequencies.HIGH]; i++) {
            var li = hiTable[i] - lowSubband;
            var ui = hiTable[i + 1] - lowSubband;
            var middle = (int) ((float) (ui - li) / 2.0f) + li;
            harmonics[middle] = addHarmonics[i];
        }
        var kk = 0;
        var next = -1;
        var m = 0;
        if (pvcFrame) {
            for (var t = 0; t < frameInfo[1]; t++) {
                for (var c = 0; c < 64; c++) {
                    mapped[c][t] = mapped[c][t + 16];
                }
            }
            for (var i = 0; i < nEnv; i++) {
                if (frameInfo[1 + i] == frameInfo[2 * nEnv + 4 + kk]) {
                    kk++;
                    next++;
                }
                var resolution = frameInfo[nEnv + 2 + i];
                for (var t = frameInfo[i + 1]; t < frameInfo[i + 2]; t++) {
                    var c = 0;
                    var o = 0;
                    for (var j = 0; j < nSfb[resolution]; j++) {
                        var li = table[resolution][j];
                        var ui = table[resolution][j + 1];
                        var ui2 = noiseTable[o + 1];
                        for (var k = 0; k < ui - li; k++) {
                            o = k + li >= ui2 ? o + 1 : o;
                            ui2 = noiseTable[o + 1];
                            mapped[c][t] = nL[next * noiseBands + o];
                            c++;
                        }
                    }
                }
            }
            kk = 0;
            next = -1;
        }
        for (var i = 0; i < nEnv; i++) {
            if (frameInfo[1 + i] == frameInfo[2 * nEnv + 4 + kk]) {
                kk++;
                next++;
            }
            var startPos = 0;
            var stopPos = 0;
            if (pvcFrame) {
                startPos = pvcFrameInfo[i + 1];
                stopPos = pvcFrameInfo[i + 2];
                for (var t = startPos; t < stopPos; t++) {
                    for (var c = 0; c < 64; c++) {
                        pvcEnvelope[c][t] = pvcOut[64 * t + c];
                    }
                }
            }
            var noNoise = i == sEnv || i == frame.previousEnvelopeIsShort;
            if (!previousPvcFrame && pvcFrame) {
                noNoise = false;
            }
            var smoothLength = noNoise ? 0 : SMOOTH_LENGTHS[smoothingLength];
            var filter = SMOOTHING[smoothLength];
            if (pvcFrame) {
                adjustPvc(frame, table, nSfb, noiseTable, high, highImag, highBase, rate, lowSubband, noSubbands,
                        startPos, stopPos, pvcFrameInfo, nEnv, i, interpolation, noNoise, limiterBand,
                        limiterGains, smoothLength, filter);
                continue;
            }
            var resolution = frameInfo[nEnv + 2 + i];
            var border0 = rate * frameInfo[1 + i];
            var border1 = rate * frameInfo[2 + i];
            var c = 0;
            var o = 0;
            for (var j = 0; j < nSfb[resolution]; j++) {
                var li = table[resolution][j];
                var ui = table[resolution][j + 1];
                var ui2 = noiseTable[o + 1];
                var flag = false;
                for (var k = li; k < ui; k++) {
                    var nrg = 0.0f;
                    for (var l = border0; l < border1; l++) {
                        var re = high[highBase + l][k];
                        var im = highImag[highBase + l][k];
                        nrg += (re * re) + (im * im);
                    }
                    flag = harmonics[c] != 0 && (i >= sEnv || previousHarmonics[c + lowSubband] != 0) || flag;
                    estimate[c++] = nrg / (border1 - border0);
                }
                var average = 0.0f;
                if (interpolation == 0) {
                    for (var k = c - (ui - li); k < c; k++) {
                        average += estimate[k];
                    }
                    average /= (ui - li);
                }
                c -= (ui - li);
                for (var k = 0; k < ui - li; k++) {
                    o = k + li >= ui2 ? o + 1 : o;
                    ui2 = noiseTable[o + 1];
                    var level = nL[next * noiseBands + o];
                    reference[c] = envelope[m];
                    estimate[c] = interpolation == 0 ? average : estimate[c];
                    tone[c] = 0;
                    double tmp = level / (1 + level);
                    if (flag) {
                        gain[c] = (float) Math.sqrt(reference[c] * tmp / (estimate[c] + 1));
                        if (harmonics[c] != 0 && (i >= sEnv || previousHarmonics[c + lowSubband] != 0)) {
                            tone[c] = (float) Math.sqrt(reference[c] * tmp / level);
                        }
                    } else if (noNoise) {
                        gain[c] = (float) Math.sqrt(reference[c] / (estimate[c] + 1));
                    } else {
                        gain[c] = (float) Math.sqrt(reference[c] * tmp / ((estimate[c] + 1) * level));
                    }
                    noise[c] = (float) Math.sqrt(reference[c] * tmp);
                    c++;
                }
                m++;
            }
            limit(reference, estimate, gain, noise, tone, limiterBand, limiterGains, noNoise);
            if (startUp) {
                for (var n = 0; n < MAX_SMOOTH; n++) {
                    System.arraycopy(gain, 0, gainHistory[n], 0, noSubbands);
                    System.arraycopy(noise, 0, noiseHistory[n], 0, noSubbands);
                }
                startUp = false;
                startUpPvc = false;
            }
            for (var l = border0; l < border1; l++) {
                var re = high[highBase + l];
                var im = highImag[highBase + l];
                for (var k = 0; k < noSubbands; k++) {
                    gainHistory[MAX_SMOOTH][k] = gain[k];
                    noiseHistory[MAX_SMOOTH][k] = noise[k];
                    var cc = 0;
                    var sbGain = 0.0f;
                    var sbNoise = 0.0f;
                    for (var n = MAX_SMOOTH - smoothLength; n <= MAX_SMOOTH; n++) {
                        sbGain += gainHistory[n][k] * filter[cc];
                        sbNoise += noiseHistory[n][k] * filter[cc++];
                    }
                    phaseIndex = (phaseIndex + 1) & 511;
                    sbNoise = tone[k] != 0 || noNoise ? 0 : sbNoise;
                    var b = lowSubband + k;
                    re[b] = re[b] * sbGain + sbNoise * EsbrTables.NOISE[2 * phaseIndex];
                    im[b] = im[b] * sbGain + sbNoise * EsbrTables.NOISE[2 * phaseIndex + 1];
                }
                rotateHistory();
            }
            interTes(codec, codecImag, codecBase + border0, high, highImag, highBase + border0, border1 - border0,
                    lowSubband, noSubbands, frame.interTesGamma[i]);
            for (var l = border0; l < border1; l++) {
                var re = high[highBase + l];
                var im = highImag[highBase + l];
                for (var k = 0; k < noSubbands; k++) {
                    var b = lowSubband + k;
                    re[b] += tone[k] * PHASE_REAL[harmonicIndex];
                    im[b] += tone[k] * ((b & 1) == 0 ? 1 : -1) * PHASE_IMAG[harmonicIndex];
                }
                harmonicIndex = (harmonicIndex + 1) & 3;
            }
        }
        for (var i = 0; i < 64; i++) {
            previousHarmonicsVarlen[i] = previousHarmonics[i];
            harmonicsVarlen[i] = harmonics[i];
        }
        System.arraycopy(harmonics, 0, previousHarmonics, lowSubband, 64 - lowSubband);
        frame.previousEnvelopeIsShort = sEnv == nEnv ? 0 : -1;
        System.arraycopy(frame.frameInfo, 0, frame.previousFrameInfo, 0, EsbrFrame.FRAME_INFO_LENGTH);
        if (frame.frameInfo[0] == 1) {
            frame.previousVarlenEnvelope = 0;
        } else if (frame.frameInfo[0] == 2) {
            frame.previousVarlenEnvelope = 1;
        }
        System.arraycopy(frame.lastNoise, 0, previousLastNoise, 0, pvcNoiseBands);
    }

    private void adjustPvc(EsbrFrame frame, int[][] table, int[] nSfb, int[] noiseTable, float[][] high,
                           float[][] highImag, int highBase, int rate, int lowSubband, int noSubbands, int startPos,
                           int stopPos, int[] pvcFrameInfo, int nEnv, int i, int interpolation, boolean noNoise,
                           int limiterBand, int limiterGains, int smoothLength, float[] filter) {
        var previousInfo = frame.previousFrameInfo;
        var previousResolution = previousInfo[previousInfo[0] + 2 + frame.previousVarlenEnvelope];
        var currentResolution = pvcFrameInfo[nEnv + 2 + i];
        var sineLength = frame.sineLengthCurrent;
        var sineStart = frame.sineStartCurrent;
        var sinePosition = frame.sinePosition;
        for (var t = startPos; t < stopPos; t++) {
            var resolution = t < sineLength ? previousResolution : currentResolution;
            var c = 0;
            var o = 0;
            for (var j = 0; j < nSfb[resolution]; j++) {
                var li = table[resolution][j];
                var ui = table[resolution][j + 1];
                var ui2 = noiseTable[o + 1];
                var flag = false;
                for (var k = li; k < ui; k++) {
                    if (t < sineLength) {
                        flag = harmonicsVarlen[c] != 0 && (t >= sineStart || previousHarmonicsVarlen[c + lowSubband] != 0)
                                || flag;
                    } else {
                        flag = harmonics[c] != 0 && (t >= sinePosition || previousHarmonics[c + lowSubband] != 0)
                                || flag;
                    }
                    pvcReference[c][t] = pvcEnvelope[k][t];
                    var nrg = 0.0f;
                    for (var l = 0; l < rate; l++) {
                        var re = high[highBase + rate * t + l][k];
                        var im = highImag[highBase + rate * t + l][k];
                        nrg += (re * re) + (im * im);
                    }
                    pvcEstimate[c][t] = nrg / rate;
                    c++;
                }
                var average = 0.0f;
                if (interpolation == 0) {
                    for (var k = c - (ui - li); k < c; k++) {
                        average += pvcEstimate[k][t];
                    }
                    average /= (ui - li);
                }
                c -= (ui - li);
                for (var k = 0; k < ui - li; k++) {
                    o = k + li >= ui2 ? o + 1 : o;
                    ui2 = noiseTable[o + 1];
                    pvcEstimate[c][t] = interpolation == 0 ? average : pvcEstimate[c][t];
                    pvcTone[c][t] = 0.0f;
                    var q = mapped[c][t];
                    double tmp = q / (1 + q);
                    var ref = pvcReference[c][t];
                    var est = pvcEstimate[c][t];
                    if (flag) {
                        pvcGain[c][t] = (float) Math.sqrt(ref * tmp / (est + 1));
                        if (harmonics[c] != 0 && (t >= sinePosition || previousHarmonics[c + lowSubband] != 0)) {
                            pvcTone[c][t] = (float) Math.sqrt(ref * tmp / q);
                        }
                        if (t < sineLength && harmonicsVarlen[c] != 0
                                && (t >= sineStart || previousHarmonicsVarlen[c + lowSubband] != 0)) {
                            pvcTone[c][t] = (float) Math.sqrt(ref * tmp / previousLastNoise[o]);
                        }
                    } else if (noNoise) {
                        pvcGain[c][t] = (float) Math.sqrt(ref / (est + 1));
                    } else {
                        pvcGain[c][t] = (float) Math.sqrt(ref * tmp / ((est + 1) * q));
                    }
                    pvcNoise[c][t] = (float) Math.sqrt(ref * tmp);
                    c++;
                }
            }
            for (var g = 0; g < limiterCount[limiterBand]; g++) {
                var pRef = 0.0f;
                var pEst = 0.0f;
                var from = limiterTable[limiterBand][g];
                var to = limiterTable[limiterBand][g + 1];
                for (var k = from; k < to; k++) {
                    pRef += pvcReference[k][t];
                    pEst += pvcEstimate[k][t];
                }
                var averageGain = (float) Math.sqrt((pRef + EPS) / (pEst + EPS));
                var maxGain = averageGain * LIMITER_GAINS[limiterGains];
                if (maxGain > 1.0e5f) {
                    maxGain = 1.0e5f;
                }
                for (var k = from; k < to; k++) {
                    if (maxGain <= pvcGain[k][t]) {
                        pvcNoise[k][t] = pvcNoise[k][t] * (maxGain / pvcGain[k][t]);
                        pvcGain[k][t] = maxGain;
                    }
                }
                var pAdj = 0.0f;
                for (var k = from; k < to; k++) {
                    pAdj += pvcGain[k][t] * pvcGain[k][t] * pvcEstimate[k][t];
                    if (pvcTone[k][t] != 0) {
                        pAdj += pvcTone[k][t] * pvcTone[k][t];
                    } else if (!noNoise) {
                        pAdj += pvcNoise[k][t] * pvcNoise[k][t];
                    }
                }
                var boost = (float) Math.sqrt((pRef + EPS) / (pAdj + EPS));
                boost = boost > 1.584893192f ? 1.584893192f : boost;
                for (var k = from; k < to; k++) {
                    pvcGain[k][t] *= boost;
                    pvcNoise[k][t] *= boost;
                    pvcTone[k][t] *= boost;
                }
            }
        }
        if (startUpPvc) {
            for (var n = 0; n < MAX_SMOOTH; n++) {
                for (var c = 0; c < noSubbands; c++) {
                    gainHistory[n][c] = pvcGain[c][startPos];
                    noiseHistory[n][c] = pvcNoise[c][startPos];
                }
            }
            startUpPvc = false;
            startUp = false;
        }
        for (var l = rate * pvcFrameInfo[1 + i]; l < rate * pvcFrameInfo[2 + i]; l++) {
            var re = high[highBase + l];
            var im = highImag[highBase + l];
            var slot = l / rate;
            for (var k = 0; k < noSubbands; k++) {
                gainHistory[MAX_SMOOTH][k] = pvcGain[k][slot];
                noiseHistory[MAX_SMOOTH][k] = pvcNoise[k][slot];
                var cc = 0;
                var sbGain = 0.0f;
                var sbNoise = 0.0f;
                for (var n = MAX_SMOOTH - smoothLength; n <= MAX_SMOOTH; n++) {
                    sbGain += gainHistory[n][k] * filter[cc];
                    sbNoise += noiseHistory[n][k] * filter[cc++];
                }
                phaseIndex = (phaseIndex + 1) & 511;
                var toneValue = pvcTone[k][slot];
                sbNoise = toneValue != 0 || noNoise ? 0 : sbNoise;
                var b = lowSubband + k;
                re[b] = re[b] * sbGain + sbNoise * EsbrTables.NOISE[2 * phaseIndex] + toneValue * PHASE_REAL[harmonicIndex];
                im[b] = im[b] * sbGain + sbNoise * EsbrTables.NOISE[2 * phaseIndex + 1]
                        + toneValue * ((b & 1) == 0 ? 1 : -1) * PHASE_IMAG[harmonicIndex];
            }
            harmonicIndex = (harmonicIndex + 1) & 3;
            rotateHistory();
        }
    }

    private void limit(float[] reference, float[] estimate, float[] gain, float[] noise, float[] tone,
                       int limiterBand, int limiterGains, boolean noNoise) {
        for (var c = 0; c < limiterCount[limiterBand]; c++) {
            var pRef = 0.0f;
            var pEst = 0.0f;
            var from = limiterTable[limiterBand][c];
            var to = limiterTable[limiterBand][c + 1];
            for (var k = from; k < to; k++) {
                pRef += reference[k];
                pEst += estimate[k];
            }
            var averageGain = (float) Math.sqrt((pRef + EPS) / (pEst + EPS));
            var maxGain = averageGain * LIMITER_GAINS[limiterGains];
            if (maxGain > 1.0e5f) {
                maxGain = 1.0e5f;
            }
            for (var k = from; k < to; k++) {
                if (maxGain <= gain[k]) {
                    noise[k] = noise[k] * (maxGain / gain[k]);
                    gain[k] = maxGain;
                }
            }
            var pAdj = 0.0f;
            for (var k = from; k < to; k++) {
                pAdj += gain[k] * gain[k] * estimate[k];
                if (tone[k] != 0) {
                    pAdj += tone[k] * tone[k];
                } else if (!noNoise) {
                    pAdj += noise[k] * noise[k];
                }
            }
            var boost = (float) Math.sqrt((pRef + EPS) / (pAdj + EPS));
            boost = boost > 1.584893192f ? 1.584893192f : boost;
            for (var k = from; k < to; k++) {
                gain[k] *= boost;
                noise[k] *= boost;
                tone[k] *= boost;
            }
        }
    }

    private void rotateHistory() {
        System.arraycopy(gainHistory[0], 0, temporary, 0, 64);
        for (var n = 0; n < MAX_SMOOTH; n++) {
            System.arraycopy(gainHistory[n + 1], 0, gainHistory[n], 0, 64);
        }
        System.arraycopy(temporary, 0, gainHistory[MAX_SMOOTH], 0, 64);
        System.arraycopy(noiseHistory[0], 0, temporary, 0, 64);
        for (var n = 0; n < MAX_SMOOTH; n++) {
            System.arraycopy(noiseHistory[n + 1], 0, noiseHistory[n], 0, 64);
        }
        System.arraycopy(temporary, 0, noiseHistory[MAX_SMOOTH], 0, 64);
    }

    private void createLimiterBands(int[] frequencies, int count, int[] crossover, int patchingMode, boolean sbr41,
                                    EsbrHfGenerator generator) {
        var lowSubband = frequencies[0];
        var highSubband = frequencies[count];
        int patches;
        if (patchingMode == 0 && crossover != null) {
            patches = 0;
            var limit = sbr41 ? EsbrHfGenerator.MAX_PATCHES : 4;
            for (var i = 1; i < limit; i++) {
                if (crossover[i] != 0) {
                    patches++;
                }
            }
            for (var i = 0; i < patches; i++) {
                patchBorders[i] = crossover[i] - lowSubband;
            }
        } else {
            patches = generator.patches;
            for (var i = 0; i < patches; i++) {
                patchBorders[i] = generator.patchStart[i] - lowSubband;
            }
        }
        patchBorders[patches] = highSubband - lowSubband;
        limiterTable[0][0] = frequencies[0] - lowSubband;
        limiterTable[0][1] = frequencies[count] - lowSubband;
        limiterCount[0] = 1;
        var log2 = StrictMath.log(2.0);
        for (var i = 1; i < 4; i++) {
            for (var k = 0; k <= count; k++) {
                work[k] = frequencies[k] - lowSubband;
            }
            for (var k = 1; k < patches; k++) {
                work[count + k] = patchBorders[k];
            }
            var gates = count + patches - 1;
            EsbrFrequencies.shellsort(work, gates + 1);
            for (var j = 1; j <= gates; j++) {
                var octaves = StrictMath.log((double) (work[j] + lowSubband) / (work[j - 1] + lowSubband)) / log2;
                if (octaves * LIMITER_BANDS_PER_OCTAVE[i] < 0.49) {
                    if (work[j] == work[j - 1]) {
                        work[j] = highSubband;
                        EsbrFrequencies.shellsort(work, gates + 1);
                        gates--;
                        j--;
                        continue;
                    }
                    var border0 = false;
                    var border1 = false;
                    for (var k = 0; k <= patches; k++) {
                        if (work[j - 1] == patchBorders[k]) {
                            border0 = true;
                            break;
                        }
                    }
                    for (var k = 0; k <= patches; k++) {
                        if (work[j] == patchBorders[k]) {
                            border1 = true;
                            break;
                        }
                    }
                    if (!border1) {
                        work[j] = highSubband;
                        EsbrFrequencies.shellsort(work, gates + 1);
                        gates--;
                        j--;
                    } else if (!border0) {
                        work[j - 1] = highSubband;
                        EsbrFrequencies.shellsort(work, gates + 1);
                        gates--;
                        j--;
                    }
                }
            }
            limiterCount[i] = gates;
            for (var k = 0; k <= gates; k++) {
                limiterTable[i][k] = work[k];
            }
        }
    }

    private void interTes(float[][] codec, float[][] codecImag, int codecBase, float[][] high, float[][] highImag,
                          int highBase, int slots, int lowSubband, int count, int gammaIndex) {
        var gamma = TES_GAMMA[gammaIndex];
        if (!(gamma > 0)) {
            return;
        }
        var highSubband = lowSubband + count;
        for (var i = 0; i < slots; i++) {
            System.arraycopy(codec[codecBase + i], 0, high[highBase + i], 0, lowSubband);
            System.arraycopy(codecImag[codecBase + i], 0, highImag[highBase + i], 0, lowSubband);
        }
        var totalLow = 0.0f;
        var totalHigh = 0.0f;
        for (var i = 0; i < slots; i++) {
            var re = high[highBase + i];
            var im = highImag[highBase + i];
            var low = 0.0f;
            for (var j = 0; j < lowSubband; j++) {
                low += re[j] * re[j];
                low += im[j] * im[j];
            }
            var hi = 0.0f;
            for (var j = lowSubband; j < highSubband; j++) {
                hi += re[j] * re[j];
                hi += im[j] * im[j];
            }
            tesLow[i] = low;
            tesHigh[i] = hi;
            totalLow += low;
            totalHigh += hi;
        }
        for (var i = 0; i < slots; i++) {
            tesGain[i] = (float) Math.sqrt(tesLow[i] * slots / (totalLow + 1.0e-6f));
        }
        var totalAfter = 1.0e-6f;
        for (var i = 0; i < slots; i++) {
            tesGain[i] = 1.0f + gamma * (tesGain[i] - 1.0f);
            if (tesGain[i] < 0.2f) {
                tesGain[i] = 0.2f;
            }
            tesHigh[i] *= tesGain[i] * tesGain[i];
            totalAfter += tesHigh[i];
        }
        var adjust2 = totalHigh / totalAfter;
        var adjust = (float) Math.sqrt(adjust2);
        for (var i = 0; i < slots; i++) {
            tesGain[i] *= adjust;
            var re = high[highBase + i];
            var im = highImag[highBase + i];
            for (var j = lowSubband; j < highSubband; j++) {
                re[j] *= tesGain[i];
                im[j] *= tesGain[i];
            }
        }
    }
}
