/* Ports webrtc-audio-processing 2.1 (common_audio/vad/webrtc_vad.c, vad_core.c, vad_filterbank.c, vad_gmm.c,
   vad_sp.c, common_audio/signal_processing/energy.c, get_scaling_square.c). Copyright (c) 2012, The WebRTC project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Detects voice in 16 kHz frames with WebRTC's fixed-point Gaussian mixture voice activity
 * detector, in its most aggressive mode.
 *
 * <p>Each call classifies one frame of 10, 20 or 30 ms from the log energies of six sub-bands
 * between 80 Hz and 4 kHz, adapting the noise and speech models as it goes. Instances are not
 * thread-safe.
 *
 * @implNote This implementation ports {@code WebRtcVad_Init}, {@code WebRtcVad_set_mode} with mode
 * 3 and {@code WebRtcVad_Process} for the 16 kHz rate the standalone detector of
 * {@code StandaloneVad} uses.
 */
final class WebRtcVad {
    private static final int NUM_CHANNELS = 6;
    private static final int NUM_GAUSSIANS = 2;
    private static final int TABLE_SIZE = NUM_CHANNELS * NUM_GAUSSIANS;
    private static final int MIN_ENERGY = 10;

    private static final short[] SPECTRUM_WEIGHT = {6, 8, 10, 12, 14, 16};
    private static final int NOISE_UPDATE_CONST = 655;
    private static final int SPEECH_UPDATE_CONST = 6554;
    private static final int BACK_ETA = 154;
    private static final short[] MINIMUM_DIFFERENCE = {544, 544, 576, 576, 576, 576};
    private static final short[] MAXIMUM_SPEECH = {11392, 11392, 11520, 11520, 11520, 11520};
    private static final short[] MINIMUM_MEAN = {640, 768};
    private static final short[] MAXIMUM_NOISE = {9216, 9088, 8960, 8832, 8704, 8576};
    private static final short[] NOISE_DATA_WEIGHTS = {34, 62, 72, 66, 53, 25, 94, 66, 56, 62, 75, 103};
    private static final short[] SPEECH_DATA_WEIGHTS = {48, 82, 45, 87, 50, 47, 80, 46, 83, 41, 78, 81};
    private static final short[] NOISE_DATA_MEANS = {6738, 4892, 7065, 6715, 6771, 3369, 7646, 3863, 7820, 7266, 5020,
            4362};
    private static final short[] SPEECH_DATA_MEANS = {8306, 10085, 10078, 11823, 11843, 6309, 9473, 9571, 10879, 7581,
            8180, 7483};
    private static final short[] NOISE_DATA_STDS = {378, 1064, 493, 582, 688, 593, 474, 697, 475, 688, 421, 455};
    private static final short[] SPEECH_DATA_STDS = {555, 505, 567, 524, 585, 1231, 509, 828, 492, 1540, 1079, 850};
    private static final int MAX_SPEECH_FRAMES = 6;
    private static final int MIN_STD = 384;

    private static final short[] OVER_HANG_MAX_1 = {6, 3, 2};
    private static final short[] OVER_HANG_MAX_2 = {9, 5, 3};
    private static final short[] LOCAL_THRESHOLD = {94, 94, 94};
    private static final short[] GLOBAL_THRESHOLD = {1100, 1050, 1100};

    private static final int LOG_CONST = 24660;
    private static final int LOG_ENERGY_INT_PART = 14336;
    private static final short[] HP_ZERO_COEFS = {6631, -13262, 6631};
    private static final short[] HP_POLE_COEFS = {16384, -7756, 5620};
    private static final short[] ALL_PASS_COEFS_Q15 = {20972, 5571};
    private static final short[] OFFSET_VECTOR = {368, 368, 272, 176, 176, 176};
    private static final short[] ALL_PASS_COEFS_Q13 = {5243, 1392};
    private static final int SMOOTHING_DOWN = 6553;
    private static final int SMOOTHING_UP = 32439;
    private static final int COMP_VAR = 22005;
    private static final int LOG2_EXP = 5909;

    private final int[] downsamplingFilterStates = new int[4];
    private final short[] noiseMeans = new short[TABLE_SIZE];
    private final short[] speechMeans = new short[TABLE_SIZE];
    private final short[] noiseStds = new short[TABLE_SIZE];
    private final short[] speechStds = new short[TABLE_SIZE];
    private int frameCounter;
    private short overHang;
    private short numOfSpeech;
    private final short[] indexVector = new short[16 * NUM_CHANNELS];
    private final short[] lowValueVector = new short[16 * NUM_CHANNELS];
    private final short[] meanValue = new short[NUM_CHANNELS];
    private final short[] upperState = new short[5];
    private final short[] lowerState = new short[5];
    private final short[] hpFilterState = new short[4];

    private final short[] speechNb = new short[240];
    private final short[] features = new short[NUM_CHANNELS];
    private final short[] hp120 = new short[120];
    private final short[] lp120 = new short[120];
    private final short[] hp60 = new short[60];
    private final short[] lp60 = new short[60];
    private final short[] deltaN = new short[TABLE_SIZE];
    private final short[] deltaS = new short[TABLE_SIZE];
    private final short[] ngprvec = new short[TABLE_SIZE];
    private final short[] sgprvec = new short[TABLE_SIZE];
    private final int[] noiseProbability = new int[NUM_GAUSSIANS];
    private final int[] speechProbability = new int[NUM_GAUSSIANS];
    private short totalEnergy;
    private short delta;

    /**
     * Creates a detector in its initial state ({@code WebRtcVad_Init}, mode 3).
     */
    WebRtcVad() {
        System.arraycopy(NOISE_DATA_MEANS, 0, noiseMeans, 0, TABLE_SIZE);
        System.arraycopy(SPEECH_DATA_MEANS, 0, speechMeans, 0, TABLE_SIZE);
        System.arraycopy(NOISE_DATA_STDS, 0, noiseStds, 0, TABLE_SIZE);
        System.arraycopy(SPEECH_DATA_STDS, 0, speechStds, 0, TABLE_SIZE);
        java.util.Arrays.fill(lowValueVector, (short) 10000);
        java.util.Arrays.fill(meanValue, (short) 1600);
    }

    /**
     * Classifies a 16 kHz frame ({@code WebRtcVad_Process}).
     *
     * @param frame the samples
     * @param length the number of samples, 160, 320 or 480
     * @return {@code 1} for voice, {@code 0} for no voice
     */
    int process(short[] frame, int length) {
        downsampling(frame, 0, speechNb, downsamplingFilterStates, 0, length);
        var vad = gmmProbability(calculateFeatures(speechNb, length / 2), length / 2);
        return vad > 0 ? 1 : 0;
    }

    private static void downsampling(short[] in, int inOffset, short[] out, int[] state, int stateOffset,
                                     int inLength) {
        var tmp32a = state[stateOffset];
        var tmp32b = state[stateOffset + 1];
        var halfLength = inLength >> 1;
        var i = inOffset;
        for (var n = 0; n < halfLength; n++) {
            var tmp16a = (short) ((tmp32a >> 1) + ((ALL_PASS_COEFS_Q13[0] * in[i]) >> 14));
            out[n] = tmp16a;
            tmp32a = in[i++] - ((ALL_PASS_COEFS_Q13[0] * tmp16a) >> 12);
            var tmp16b = (short) ((tmp32b >> 1) + ((ALL_PASS_COEFS_Q13[1] * in[i]) >> 14));
            out[n] = (short) (out[n] + tmp16b);
            tmp32b = in[i++] - ((ALL_PASS_COEFS_Q13[1] * tmp16b) >> 12);
        }
        state[stateOffset] = tmp32a;
        state[stateOffset + 1] = tmp32b;
    }

    private short calculateFeatures(short[] data, int dataLength) {
        totalEnergy = 0;
        var halfDataLength = dataLength >> 1;
        var length = halfDataLength;
        splitFilter(data, dataLength, 0, hp120, lp120);
        splitFilter(hp120, length, 1, hp60, lp60);
        length >>= 1;
        features[5] = logOfEnergy(hp60, length, OFFSET_VECTOR[5]);
        features[4] = logOfEnergy(lp60, length, OFFSET_VECTOR[4]);
        length = halfDataLength;
        splitFilter(lp120, length, 2, hp60, lp60);
        length >>= 1;
        features[3] = logOfEnergy(hp60, length, OFFSET_VECTOR[3]);
        splitFilter(lp60, length, 3, hp120, lp120);
        length >>= 1;
        features[2] = logOfEnergy(hp120, length, OFFSET_VECTOR[2]);
        splitFilter(lp120, length, 4, hp60, lp60);
        length >>= 1;
        features[1] = logOfEnergy(hp60, length, OFFSET_VECTOR[1]);
        highPassFilter(lp60, length, hp120);
        features[0] = logOfEnergy(hp120, length, OFFSET_VECTOR[0]);
        return totalEnergy;
    }

    private void highPassFilter(short[] in, int length, short[] out) {
        var state = hpFilterState;
        for (var i = 0; i < length; i++) {
            var tmp32 = HP_ZERO_COEFS[0] * in[i];
            tmp32 += HP_ZERO_COEFS[1] * state[0];
            tmp32 += HP_ZERO_COEFS[2] * state[1];
            state[1] = state[0];
            state[0] = in[i];
            tmp32 -= HP_POLE_COEFS[1] * state[2];
            tmp32 -= HP_POLE_COEFS[2] * state[3];
            state[3] = state[2];
            state[2] = (short) (tmp32 >> 14);
            out[i] = state[2];
        }
    }

    private static short allPassFilter(short[] in, int inOffset, int length, int coefficient, short state,
                                       short[] out) {
        var state32 = state * (1 << 16);
        var i = inOffset;
        for (var n = 0; n < length; n++) {
            var tmp32 = state32 + coefficient * in[i];
            var tmp16 = (short) (tmp32 >> 16);
            out[n] = tmp16;
            state32 = (in[i] * (1 << 14)) - coefficient * tmp16;
            state32 *= 2;
            i += 2;
        }
        return (short) (state32 >> 16);
    }

    private void splitFilter(short[] in, int length, int band, short[] hpOut, short[] lpOut) {
        var halfLength = length >> 1;
        upperState[band] = allPassFilter(in, 0, halfLength, ALL_PASS_COEFS_Q15[0], upperState[band], hpOut);
        lowerState[band] = allPassFilter(in, 1, halfLength, ALL_PASS_COEFS_Q15[1], lowerState[band], lpOut);
        for (var i = 0; i < halfLength; i++) {
            var tmp = hpOut[i];
            hpOut[i] = (short) (hpOut[i] - lpOut[i]);
            lpOut[i] = (short) (lpOut[i] + tmp);
        }
    }

    private short logOfEnergy(short[] data, int length, short offset) {
        var scaling = scalingSquare(data, length, length);
        var en = 0;
        for (var i = 0; i < length; i++) {
            en += (data[i] * data[i]) >> scaling;
        }
        var totRshifts = scaling;
        var energy = en;
        short logEnergy;
        if (energy != 0) {
            var normalizingRshifts = 17 - FixedPointMath.normU32(energy);
            var log2Energy = (short) LOG_ENERGY_INT_PART;
            totRshifts += normalizingRshifts;
            if (normalizingRshifts < 0) {
                energy <<= -normalizingRshifts;
            } else {
                energy >>>= normalizingRshifts;
            }
            log2Energy = (short) (log2Energy + (short) ((energy & 0x00003FFF) >>> 4));
            logEnergy = (short) (((LOG_CONST * log2Energy) >> 19) + ((totRshifts * LOG_CONST) >> 9));
            if (logEnergy < 0) {
                logEnergy = 0;
            }
        } else {
            return offset;
        }
        logEnergy = (short) (logEnergy + offset);
        if (totalEnergy <= MIN_ENERGY) {
            if (totRshifts >= 0) {
                totalEnergy = (short) (totalEnergy + MIN_ENERGY + 1);
            } else {
                totalEnergy = (short) (totalEnergy + (short) (energy >>> -totRshifts));
            }
        }
        return logEnergy;
    }

    private static int scalingSquare(short[] in, int length, int times) {
        var nbits = 32 - Integer.numberOfLeadingZeros(times);
        short smax = -1;
        for (var i = 0; i < length; i++) {
            var sabs = (short) (in[i] > 0 ? in[i] : -in[i]);
            smax = sabs > smax ? sabs : smax;
        }
        var t = FixedPointMath.normW32(smax * smax);
        if (smax == 0) {
            return 0;
        }
        return t > nbits ? 0 : nbits - t;
    }

    private int gaussianProbability(short input, short mean, short std) {
        var tmp32 = 131072 + (std >> 1);
        var invStd = (short) FixedPointMath.divW32W16(tmp32, std);
        var tmp16 = (short) (invStd >> 2);
        var invStd2 = (short) ((tmp16 * tmp16) >> 2);
        tmp16 = (short) (input << 3);
        tmp16 = (short) (tmp16 - mean);
        delta = (short) ((invStd2 * tmp16) >> 10);
        tmp32 = (delta * tmp16) >> 9;
        short expValue = 0;
        if (tmp32 < COMP_VAR) {
            tmp16 = (short) ((LOG2_EXP * tmp32) >> 12);
            tmp16 = (short) -tmp16;
            expValue = (short) (0x0400 | (tmp16 & 0x03FF));
            tmp16 = (short) (tmp16 ^ 0xFFFF);
            tmp16 = (short) (tmp16 >> 10);
            tmp16 = (short) (tmp16 + 1);
            expValue = (short) (expValue >> tmp16);
        }
        return invStd * expValue;
    }

    private int weightedAverage(short[] data, int channel, int offset, short[] weights) {
        var weightedAverage = 0;
        for (var k = 0; k < NUM_GAUSSIANS; k++) {
            var index = channel + k * NUM_CHANNELS;
            data[index] = (short) (data[index] + offset);
            weightedAverage += data[index] * weights[index];
        }
        return weightedAverage;
    }

    private int gmmProbability(short totalPower, int frameLength) {
        var mode = frameLength == 80 ? 0 : (frameLength == 160 ? 1 : 2);
        var overhead1 = OVER_HANG_MAX_1[mode];
        var overhead2 = OVER_HANG_MAX_2[mode];
        var individualTest = LOCAL_THRESHOLD[mode];
        var totalTest = GLOBAL_THRESHOLD[mode];
        int vadflag = 0;
        java.util.Arrays.fill(ngprvec, (short) 0);
        java.util.Arrays.fill(sgprvec, (short) 0);
        var sumLogLikelihoodRatios = 0;
        if (totalPower > MIN_ENERGY) {
            for (var channel = 0; channel < NUM_CHANNELS; channel++) {
                var h0Test = 0;
                var h1Test = 0;
                for (var k = 0; k < NUM_GAUSSIANS; k++) {
                    var gaussian = channel + k * NUM_CHANNELS;
                    var tmp = gaussianProbability(features[channel], noiseMeans[gaussian], noiseStds[gaussian]);
                    deltaN[gaussian] = delta;
                    noiseProbability[k] = NOISE_DATA_WEIGHTS[gaussian] * tmp;
                    h0Test += noiseProbability[k];
                    tmp = gaussianProbability(features[channel], speechMeans[gaussian], speechStds[gaussian]);
                    deltaS[gaussian] = delta;
                    speechProbability[k] = SPEECH_DATA_WEIGHTS[gaussian] * tmp;
                    h1Test += speechProbability[k];
                }
                var shiftsH0 = (short) FixedPointMath.normW32(h0Test);
                var shiftsH1 = (short) FixedPointMath.normW32(h1Test);
                if (h0Test == 0) {
                    shiftsH0 = 31;
                }
                if (h1Test == 0) {
                    shiftsH1 = 31;
                }
                var logLikelihoodRatio = (short) (shiftsH0 - shiftsH1);
                sumLogLikelihoodRatios += logLikelihoodRatio * SPECTRUM_WEIGHT[channel];
                if ((logLikelihoodRatio * 4) > individualTest) {
                    vadflag = 1;
                }
                var h0 = (short) (h0Test >> 12);
                if (h0 > 0) {
                    var tmp = (noiseProbability[0] & 0xFFFFF000) << 2;
                    ngprvec[channel] = (short) FixedPointMath.divW32W16(tmp, h0);
                    ngprvec[channel + NUM_CHANNELS] = (short) (16384 - ngprvec[channel]);
                } else {
                    ngprvec[channel] = 16384;
                }
                var h1 = (short) (h1Test >> 12);
                if (h1 > 0) {
                    var tmp = (speechProbability[0] & 0xFFFFF000) << 2;
                    sgprvec[channel] = (short) FixedPointMath.divW32W16(tmp, h1);
                    sgprvec[channel + NUM_CHANNELS] = (short) (16384 - sgprvec[channel]);
                }
            }
            vadflag |= sumLogLikelihoodRatios >= totalTest ? 1 : 0;
            var maxspe = 12800;
            for (var channel = 0; channel < NUM_CHANNELS; channel++) {
                var featureMinimum = findMinimum(features[channel], channel);
                var noiseGlobalMean = weightedAverage(noiseMeans, channel, 0, NOISE_DATA_WEIGHTS);
                var tmp1s16 = (short) (noiseGlobalMean >> 6);
                for (var k = 0; k < NUM_GAUSSIANS; k++) {
                    var gaussian = channel + k * NUM_CHANNELS;
                    var nmk = noiseMeans[gaussian];
                    var smk = speechMeans[gaussian];
                    var nsk = noiseStds[gaussian];
                    var ssk = speechStds[gaussian];
                    var nmk2 = nmk;
                    if (vadflag == 0) {
                        var delt = (short) ((ngprvec[gaussian] * deltaN[gaussian]) >> 11);
                        nmk2 = (short) (nmk + (short) ((delt * NOISE_UPDATE_CONST) >> 22));
                    }
                    var ndelt = (short) ((featureMinimum << 4) - tmp1s16);
                    var nmk3 = (short) (nmk2 + (short) ((ndelt * BACK_ETA) >> 9));
                    var tmpS16 = (short) ((k + 5) << 7);
                    if (nmk3 < tmpS16) {
                        nmk3 = tmpS16;
                    }
                    tmpS16 = (short) ((72 + k - channel) << 7);
                    if (nmk3 > tmpS16) {
                        nmk3 = tmpS16;
                    }
                    noiseMeans[gaussian] = nmk3;
                    if (vadflag != 0) {
                        var delt = (short) ((sgprvec[gaussian] * deltaS[gaussian]) >> 11);
                        tmpS16 = (short) ((delt * SPEECH_UPDATE_CONST) >> 21);
                        var smk2 = (short) (smk + ((tmpS16 + 1) >> 1));
                        var maxmu = (short) (maxspe + 640);
                        if (smk2 < MINIMUM_MEAN[k]) {
                            smk2 = MINIMUM_MEAN[k];
                        }
                        if (smk2 > maxmu) {
                            smk2 = maxmu;
                        }
                        speechMeans[gaussian] = smk2;
                        tmpS16 = (short) ((smk + 4) >> 3);
                        tmpS16 = (short) (features[channel] - tmpS16);
                        var tmp1s32 = (deltaS[gaussian] * tmpS16) >> 3;
                        var tmp2s32 = tmp1s32 - 4096;
                        tmpS16 = (short) (sgprvec[gaussian] >> 2);
                        tmp1s32 = tmpS16 * tmp2s32;
                        tmp2s32 = tmp1s32 >> 4;
                        if (tmp2s32 > 0) {
                            tmpS16 = (short) FixedPointMath.divW32W16(tmp2s32, ssk * 10);
                        } else {
                            tmpS16 = (short) FixedPointMath.divW32W16(-tmp2s32, ssk * 10);
                            tmpS16 = (short) -tmpS16;
                        }
                        tmpS16 = (short) (tmpS16 + 128);
                        ssk = (short) (ssk + (tmpS16 >> 8));
                        if (ssk < MIN_STD) {
                            ssk = MIN_STD;
                        }
                        speechStds[gaussian] = ssk;
                    } else {
                        tmpS16 = (short) (features[channel] - (nmk >> 3));
                        var tmp1s32 = (deltaN[gaussian] * tmpS16) >> 3;
                        tmp1s32 -= 4096;
                        tmpS16 = (short) ((ngprvec[gaussian] + 2) >> 2);
                        var tmp2s32 = tmpS16 * tmp1s32;
                        tmp1s32 = tmp2s32 >> 14;
                        if (tmp1s32 > 0) {
                            tmpS16 = (short) FixedPointMath.divW32W16(tmp1s32, nsk);
                        } else {
                            tmpS16 = (short) FixedPointMath.divW32W16(-tmp1s32, nsk);
                            tmpS16 = (short) -tmpS16;
                        }
                        tmpS16 = (short) (tmpS16 + 32);
                        nsk = (short) (nsk + (tmpS16 >> 6));
                        if (nsk < MIN_STD) {
                            nsk = MIN_STD;
                        }
                        noiseStds[gaussian] = nsk;
                    }
                }
                noiseGlobalMean = weightedAverage(noiseMeans, channel, 0, NOISE_DATA_WEIGHTS);
                var speechGlobalMean = weightedAverage(speechMeans, channel, 0, SPEECH_DATA_WEIGHTS);
                var diff = (short) ((short) (speechGlobalMean >> 9) - (short) (noiseGlobalMean >> 9));
                if (diff < MINIMUM_DIFFERENCE[channel]) {
                    var tmpS16 = (short) (MINIMUM_DIFFERENCE[channel] - diff);
                    var t1 = (short) ((13 * tmpS16) >> 2);
                    var t2 = (short) ((3 * tmpS16) >> 2);
                    speechGlobalMean = weightedAverage(speechMeans, channel, t1, SPEECH_DATA_WEIGHTS);
                    noiseGlobalMean = weightedAverage(noiseMeans, channel, -t2, NOISE_DATA_WEIGHTS);
                }
                maxspe = MAXIMUM_SPEECH[channel];
                var tmp2s16 = (short) (speechGlobalMean >> 7);
                if (tmp2s16 > maxspe) {
                    tmp2s16 = (short) (tmp2s16 - maxspe);
                    for (var k = 0; k < NUM_GAUSSIANS; k++) {
                        var index = channel + k * NUM_CHANNELS;
                        speechMeans[index] = (short) (speechMeans[index] - tmp2s16);
                    }
                }
                tmp2s16 = (short) (noiseGlobalMean >> 7);
                if (tmp2s16 > MAXIMUM_NOISE[channel]) {
                    tmp2s16 = (short) (tmp2s16 - MAXIMUM_NOISE[channel]);
                    for (var k = 0; k < NUM_GAUSSIANS; k++) {
                        var index = channel + k * NUM_CHANNELS;
                        noiseMeans[index] = (short) (noiseMeans[index] - tmp2s16);
                    }
                }
            }
            frameCounter++;
        }
        if (vadflag == 0) {
            if (overHang > 0) {
                vadflag = 2 + overHang;
                overHang--;
            }
            numOfSpeech = 0;
        } else {
            numOfSpeech++;
            if (numOfSpeech > MAX_SPEECH_FRAMES) {
                numOfSpeech = MAX_SPEECH_FRAMES;
                overHang = overhead2;
            } else {
                overHang = overhead1;
            }
        }
        return vadflag;
    }

    private short findMinimum(short featureValue, int channel) {
        var offset = channel << 4;
        var age = indexVector;
        var smallest = lowValueVector;
        for (var i = 0; i < 16; i++) {
            if (age[offset + i] != 100) {
                age[offset + i]++;
            } else {
                for (var j = i; j < 15; j++) {
                    smallest[offset + j] = smallest[offset + j + 1];
                    age[offset + j] = age[offset + j + 1];
                }
                age[offset + 15] = 101;
                smallest[offset + 15] = 10000;
            }
        }
        var position = -1;
        if (featureValue < smallest[offset + 7]) {
            if (featureValue < smallest[offset + 3]) {
                if (featureValue < smallest[offset + 1]) {
                    position = featureValue < smallest[offset] ? 0 : 1;
                } else if (featureValue < smallest[offset + 2]) {
                    position = 2;
                } else {
                    position = 3;
                }
            } else if (featureValue < smallest[offset + 5]) {
                position = featureValue < smallest[offset + 4] ? 4 : 5;
            } else if (featureValue < smallest[offset + 6]) {
                position = 6;
            } else {
                position = 7;
            }
        } else if (featureValue < smallest[offset + 15]) {
            if (featureValue < smallest[offset + 11]) {
                if (featureValue < smallest[offset + 9]) {
                    position = featureValue < smallest[offset + 8] ? 8 : 9;
                } else if (featureValue < smallest[offset + 10]) {
                    position = 10;
                } else {
                    position = 11;
                }
            } else if (featureValue < smallest[offset + 13]) {
                position = featureValue < smallest[offset + 12] ? 12 : 13;
            } else if (featureValue < smallest[offset + 14]) {
                position = 14;
            } else {
                position = 15;
            }
        }
        if (position > -1) {
            for (var i = 15; i > position; i--) {
                smallest[offset + i] = smallest[offset + i - 1];
                age[offset + i] = age[offset + i - 1];
            }
            smallest[offset + position] = featureValue;
            age[offset + position] = 1;
        }
        short currentMedian = 1600;
        if (frameCounter > 2) {
            currentMedian = smallest[offset + 2];
        } else if (frameCounter > 0) {
            currentMedian = smallest[offset];
        }
        var alpha = 0;
        if (frameCounter > 0) {
            alpha = currentMedian < meanValue[channel] ? SMOOTHING_DOWN : SMOOTHING_UP;
        }
        var tmp32 = (alpha + 1) * meanValue[channel];
        tmp32 += (Short.MAX_VALUE - alpha) * currentMedian;
        tmp32 += 16384;
        meanValue[channel] = (short) (tmp32 >> 15);
        return meanValue[channel];
    }
}
