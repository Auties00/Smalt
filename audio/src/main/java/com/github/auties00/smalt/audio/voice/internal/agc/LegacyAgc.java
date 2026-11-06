package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Runs WebRTC's legacy automatic gain controller on the lowest 16 kHz band of one channel: the analog volume
 * adaptation (a feedback loop that recommends a microphone level from the speech energy), its
 * emulation on the signal itself (the virtual microphone of the adaptive digital mode) and the
 * digital compressor of {@link DigitalAgc}.
 *
 * <p>A frame is first given to {@link #addMic(short[], int)} (adaptive analog mode) or
 * {@link #virtualMic(short[], int, int)} (adaptive digital mode), which collect its envelope and
 * energy and update the speech detector; {@link #analyze(short[], int, int, boolean, int[])}
 * then computes the digital gains of the frame and runs the analog adaptation. The microphone
 * level is an integer in {@code [minLevel, maxLevel]} as given to {@link #init(int, int, int)}.
 *
 * @implNote This implementation ports {@code LegacyAgc} and the {@code WebRtcAgc_*} functions of
 * {@code modules/audio_processing/agc/legacy/analog_agc.cc} (webrtc-audio-processing 2.1) for a
 * 16, 32 or 48 kHz signal, keeping the 16-bit and 32-bit widths of every field. The upper bands
 * only receive gains, which {@link GainController} applies, so they are not passed in. The fixed-point
 * application of the gains ({@code WebRtcAgc_Process}) is not ported: the audio processing module
 * applies the gains in floating point, which {@link GainController} reproduces.
 */
final class LegacyAgc {
    /**
     * The number of subframe energies of the energy history ({@code kRxxBufferLen}).
     */
    private static final int RXX_BUFFER_LEN = 10;

    /**
     * The initial minimum duration of speech between inner-range volume changes, in ms.
     */
    private static final short MSEC_SPEECH_INNER = 520;

    /**
     * The initial minimum duration of speech between outer-range volume changes, in ms.
     */
    private static final short MSEC_SPEECH_OUTER = 340;

    /**
     * The default speech detector threshold.
     */
    private static final short NORMAL_VAD_THRESHOLD = 400;

    /**
     * The shift of the short-term energy smoother ({@code kAlphaShortTerm}).
     */
    private static final int ALPHA_SHORT_TERM = 6;

    /**
     * The shift of the long-term energy smoother ({@code kAlphaLongTerm}).
     */
    private static final int ALPHA_LONG_TERM = 10;

    /**
     * The slopes of the first volume increase curves, in Q13.
     */
    private static final short[] SLOPE_1 = {21793, 12517, 7189, 4129, 2372, 1362, 472, 78};

    /**
     * The offsets of the first volume increase curves, in Q14.
     */
    private static final short[] OFFSET_1 = {25395, 23911, 22206, 20737, 19612, 18805, 17951, 17367};

    /**
     * The slopes of the second volume increase curves, in Q13.
     */
    private static final short[] SLOPE_2 = {2063, 1731, 1452, 1218, 1021, 857, 597, 337};

    /**
     * The offsets of the second volume increase curves, in Q14.
     */
    private static final short[] OFFSET_2 = {18432, 18379, 18290, 18177, 18052, 17920, 17670, 17286};

    /**
     * The time after a mute during which the volume is not raised, in ms.
     */
    private static final short MUTE_GUARD_TIME_MS = 8000;

    /**
     * The number of 1 ms subframes of a frame.
     */
    private static final int NUM_SUBFRAMES = 10;

    /**
     * The default target level, in dB below full scale.
     */
    static final int DEFAULT_TARGET_LEVEL = 3;

    /**
     * The default compression gain, in dB.
     */
    static final int DEFAULT_COMP_GAIN = 9;

    /**
     * The analog target level in the envelope scale.
     */
    private static final int ANALOG_TARGET_LEVEL = 11;

    /**
     * Half of {@link #ANALOG_TARGET_LEVEL}, rounded down.
     */
    private static final int ANALOG_TARGET_LEVEL_2 = 5;

    /**
     * The offset between the RMS scale of the analog part and the envelope scale of the digital
     * part.
     */
    private static final int OFFSET_ENV_TO_RMS = 9;

    /**
     * The input level at which the digital part outputs the target level without compression.
     */
    private static final int DIGITAL_REF_AT_0_COMP_GAIN = 4;

    /**
     * The speed of the reference level decrease.
     */
    private static final int DIFF_REF_TO_ANALOG = 5;

    /**
     * The number of entries of {@link #GAIN_TABLE_ANALOG}.
     */
    private static final int GAIN_TBL_LEN = 32;

    /**
     * The supplemental digital gains above the maximum analog level, in Q12.
     */
    private static final int[] GAIN_TABLE_ANALOG = {
            4096, 4251, 4412, 4579, 4752, 4932, 5118, 5312, 5513, 5722, 5938,
            6163, 6396, 6638, 6889, 7150, 7420, 7701, 7992, 8295, 8609, 8934,
            9273, 9623, 9987, 10365, 10758, 11165, 11587, 12025, 12480, 12953
    };

    /**
     * The gains of the virtual microphone above its mid level, in Q10.
     */
    private static final int[] GAIN_TABLE_VIRTUAL_MIC = {
            1052, 1081, 1110, 1141, 1172, 1204, 1237, 1271, 1305, 1341, 1378,
            1416, 1454, 1494, 1535, 1577, 1620, 1664, 1710, 1757, 1805, 1854,
            1905, 1957, 2010, 2065, 2122, 2180, 2239, 2301, 2364, 2428, 2495,
            2563, 2633, 2705, 2779, 2855, 2933, 3013, 3096, 3180, 3267, 3357,
            3449, 3543, 3640, 3739, 3842, 3947, 4055, 4166, 4280, 4397, 4517,
            4640, 4767, 4898, 5032, 5169, 5311, 5456, 5605, 5758, 5916, 6078,
            6244, 6415, 6590, 6770, 6956, 7146, 7341, 7542, 7748, 7960, 8178,
            8402, 8631, 8867, 9110, 9359, 9615, 9878, 10148, 10426, 10711, 11004,
            11305, 11614, 11932, 12258, 12593, 12938, 13292, 13655, 14029, 14412, 14807,
            15212, 15628, 16055, 16494, 16945, 17409, 17885, 18374, 18877, 19393, 19923,
            20468, 21028, 21603, 22194, 22801, 23425, 24065, 24724, 25400, 26095, 26808,
            27541, 28295, 29069, 29864, 30681, 31520, 32382
    };

    /**
     * The attenuations of the virtual microphone below its mid level, in Q10.
     */
    private static final int[] SUPPRESSION_TABLE_VIRTUAL_MIC = {
            1024, 1006, 988, 970, 952, 935, 918, 902, 886, 870, 854, 839, 824, 809, 794,
            780, 766, 752, 739, 726, 713, 700, 687, 675, 663, 651, 639, 628, 616, 605,
            594, 584, 573, 563, 553, 543, 533, 524, 514, 505, 496, 487, 478, 470, 461,
            453, 445, 437, 429, 421, 414, 406, 399, 392, 385, 378, 371, 364, 358, 351,
            345, 339, 333, 327, 321, 315, 309, 304, 298, 293, 288, 283, 278, 273, 268,
            263, 258, 254, 249, 244, 240, 236, 232, 227, 223, 219, 215, 211, 208, 204,
            200, 197, 193, 190, 186, 183, 180, 176, 173, 170, 167, 164, 161, 158, 155,
            153, 150, 147, 145, 142, 139, 137, 134, 132, 130, 127, 125, 123, 121, 118,
            116, 114, 112, 110, 108, 106, 104, 102
    };

    /**
     * The target energies, in Q(-7), of the levels 0 to 63 dB below full scale.
     */
    private static final int[] TARGET_LEVEL_TABLE = {
            134209536, 106606424, 84680493, 67264106, 53429779, 42440782, 33711911,
            26778323, 21270778, 16895980, 13420954, 10660642, 8468049, 6726411,
            5342978, 4244078, 3371191, 2677832, 2127078, 1689598, 1342095,
            1066064, 846805, 672641, 534298, 424408, 337119, 267783,
            212708, 168960, 134210, 106606, 84680, 67264, 53430,
            42441, 33712, 26778, 21271, 16896, 13421, 10661,
            8468, 6726, 5343, 4244, 3371, 2678, 2127,
            1690, 1342, 1066, 847, 673, 534, 424,
            337, 268, 213, 169, 134, 107, 85,
            67
    };

    /**
     * The compression gain in effect, in dB.
     */
    private short compressionGaindB;

    /**
     * The target level, in dB below full scale.
     */
    private short targetLevelDbfs;

    /**
     * The mode, one of the {@code DigitalAgc.MODE_*} constants.
     */
    private int agcMode;

    /**
     * Whether the limiter is enabled.
     */
    private boolean limiterEnable;

    /**
     * The analog target energy.
     */
    private int analogTargetLevel;

    /**
     * The upper energy limit at start-up.
     */
    private int startUpperLimit;

    /**
     * The lower energy limit at start-up.
     */
    private int startLowerLimit;

    /**
     * The upper energy limit once adapted.
     */
    private int upperPrimaryLimit;

    /**
     * The lower energy limit once adapted.
     */
    private int lowerPrimaryLimit;

    /**
     * The upper energy limit beyond which the volume drops faster.
     */
    private int upperSecondaryLimit;

    /**
     * The lower energy limit beyond which the volume rises faster.
     */
    private int lowerSecondaryLimit;

    /**
     * The index of the target in {@link #TARGET_LEVEL_TABLE}.
     */
    private int targetIdx;

    /**
     * The digital reference level, in the envelope scale.
     */
    private short analogTarget;

    /**
     * The states of the 16 kHz to 8 kHz decimator of the energy.
     */
    private final int[] filterState;

    /**
     * The current upper energy limit.
     */
    private int upperLimit;

    /**
     * The current lower energy limit.
     */
    private int lowerLimit;

    /**
     * The energy of the last 160 ms.
     */
    private int rxx160w32;

    /**
     * The low-pass filtered subframe energy.
     */
    private int rxx16LPw32;

    /**
     * The low-pass filtered frame energy.
     */
    private int rxx160LPw32;

    /**
     * The largest low-pass filtered subframe energy since the last reset.
     */
    private int rxx16LPw32Max;

    /**
     * The ring of subframe energies.
     */
    private final int[] rxx16VectorW32;

    /**
     * The subframe energies of the current and the queued frame.
     */
    private final int[][] rxx16w32Array;

    /**
     * The subframe envelopes of the current and the queued frame.
     */
    private final int[][] env;

    /**
     * The position in {@link #rxx16VectorW32}.
     */
    private short rxx16pos;

    /**
     * The decaying sum of saturated envelopes.
     */
    private short envSum;

    /**
     * The speech detector threshold.
     */
    private short vadThreshold;

    /**
     * The milliseconds of speech at a too low level.
     */
    private short msTooLow;

    /**
     * The milliseconds of speech at a too high level.
     */
    private short msTooHigh;

    /**
     * The milliseconds at target before the slow mode starts.
     */
    private short changeToSlowMode;

    /**
     * Whether the analog adaptation has run.
     */
    private boolean firstCallDone;

    /**
     * The milliseconds of zero input.
     */
    private short msZero;

    /**
     * The minimum milliseconds of speech between outer-range volume changes.
     */
    private short msecSpeechOuterChange;

    /**
     * The minimum milliseconds of speech between inner-range volume changes.
     */
    private short msecSpeechInnerChange;

    /**
     * The milliseconds of active speech.
     */
    private short activeSpeech;

    /**
     * The countdown that prevents raising the volume after a mute.
     */
    private short muteGuardMs;

    /**
     * The number of frames whose envelope and energy wait for {@link #analyze}.
     */
    private short inQueue;

    /**
     * The reference microphone level of the virtual microphone.
     */
    private int micRef;

    /**
     * The position in {@link #GAIN_TABLE_ANALOG}.
     */
    private int gainTableIdx;

    /**
     * The gain index of the virtual microphone.
     */
    private int micGainIdx;

    /**
     * The microphone volume carried between frames.
     */
    private int micVol;

    /**
     * The maximum volume, including the supplemental digital gain.
     */
    private int maxLevel;

    /**
     * The maximum analog volume.
     */
    private int maxAnalog;

    /**
     * The initial value of {@link #maxLevel}.
     */
    private int maxInit;

    /**
     * The minimum volume.
     */
    private int minLevel;

    /**
     * The minimum output volume.
     */
    private int minOutput;

    /**
     * The volume above which a zero input does not raise the volume.
     */
    private int zeroCtrlMax;

    /**
     * The input level of the previous analog adaptation.
     */
    private int lastInMicLevel;

    /**
     * The scale of the internal volume levels, always {@code 0}.
     */
    private int scale;

    /**
     * The speech detector of the microphone signal.
     */
    private final AgcVad vadMic;

    /**
     * The digital compressor.
     */
    final DigitalAgc digitalAgc;

    /**
     * Whether the last virtual microphone frame was classified as a low-level signal.
     */
    private short lowLevelSignal;

    /**
     * The 8 kHz samples of one 2 ms block.
     */
    private final short[] tmpSpeech;

    /**
     * Whether the signal is split into bands (32 or 48 kHz), in which case the energy of a 2 ms
     * block is computed on its first 16 samples instead of on the block downsampled to 8 kHz.
     */
    private final boolean multiband;

    /**
     * Creates an uninitialized controller; {@link #init(int, int, int)} must be called first.
     *
     * @param multiband whether the signal is split into bands, at 32 or 48 kHz
     */
    LegacyAgc(boolean multiband) {
        this.multiband = multiband;
        this.filterState = new int[8];
        this.rxx16VectorW32 = new int[RXX_BUFFER_LEN];
        this.rxx16w32Array = new int[2][5];
        this.env = new int[2][10];
        this.vadMic = new AgcVad();
        this.digitalAgc = new DigitalAgc();
        this.tmpSpeech = new short[16];
    }

    /**
     * Collects the envelope and energy of a microphone frame, after applying the supplemental
     * digital gain above the maximum analog level ({@code WebRtcAgc_AddMic}).
     *
     * @param inMic  the 160 microphone samples, modified in place
     * @param offset the index of the first sample
     */
    void addMic(short[] inMic, int offset) {
        final var l = 16;
        final var samples = 160;
        if (micVol > maxAnalog) {
            var tmp16 = (short) (micVol - maxAnalog);
            var tmp32 = (GAIN_TBL_LEN - 1) * tmp16;
            tmp16 = (short) (maxLevel - maxAnalog);
            var targetGainIdx = (tmp32 / tmp16) & 0xFFFF;
            if (gainTableIdx < targetGainIdx) {
                gainTableIdx++;
            } else if (gainTableIdx > targetGainIdx) {
                gainTableIdx--;
            }
            var gain = GAIN_TABLE_ANALOG[gainTableIdx];
            for (var i = 0; i < samples; i++) {
                var sample = (inMic[offset + i] * gain) >> 12;
                if (sample > 32767) {
                    inMic[offset + i] = 32767;
                } else if (sample < -32768) {
                    inMic[offset + i] = -32768;
                } else {
                    inMic[offset + i] = (short) sample;
                }
            }
        } else {
            gainTableIdx = 0;
        }

        var envelope = inQueue > 0 ? env[1] : env[0];
        for (var i = 0; i < NUM_SUBFRAMES; i++) {
            var maxNrg = 0;
            for (var n = 0; n < l; n++) {
                var sample = inMic[offset + i * l + n];
                var nrg = sample * sample;
                if (nrg > maxNrg) {
                    maxNrg = nrg;
                }
            }
            envelope[i] = maxNrg;
        }

        var energies = inQueue > 0 ? rxx16w32Array[1] : rxx16w32Array[0];
        for (var i = 0; i < NUM_SUBFRAMES / 2; i++) {
            if (multiband) {
                System.arraycopy(inMic, offset + i * 16, tmpSpeech, 0, 16);
            } else {
                FixedPointMath.downsampleBy2(inMic, offset + i * 32, 32, tmpSpeech, 0, filterState);
            }
            energies[i] = FixedPointMath.dotProductWithScale(tmpSpeech, 0, tmpSpeech, 0, 16, 4);
        }

        inQueue = (short) (inQueue == 0 ? 1 : 2);
        vadMic.process(inMic, offset, samples);
    }

    /**
     * Feeds a far-end frame to the far-end speech detector ({@code WebRtcAgc_AddFarend}).
     *
     * @param farend the 160 render samples
     * @param offset the index of the first sample
     */
    void addFarend(short[] farend, int offset) {
        digitalAgc.addFarend(farend, offset, 160);
    }

    /**
     * Emulates the microphone volume on the signal, classifies the frame as low-level or not and
     * collects it as in {@link #addMic(short[], int)} ({@code WebRtcAgc_VirtualMic}).
     *
     * @param inNear     the 160 samples, replaced by the emulated microphone signal
     * @param offset     the index of the first sample
     * @param micLevelIn the reported microphone level
     * @return the microphone level the emulation used
     */
    int virtualMic(short[] inNear, int offset, int micLevelIn) {
        final var samples = 160;
        var frameNrgLimit = 5500 << 1;
        var frameNrg = inNear[offset] * inNear[offset];
        short numZeroCrossing = 0;
        for (var sampleCntr = 1; sampleCntr < samples; sampleCntr++) {
            if (Integer.compareUnsigned(frameNrg, frameNrgLimit) < 0) {
                var nrg = inNear[offset + sampleCntr] * inNear[offset + sampleCntr];
                frameNrg += nrg;
            }
            numZeroCrossing += (short) ((inNear[offset + sampleCntr] ^ inNear[offset + sampleCntr - 1]) < 0 ? 1 : 0);
        }
        if (Integer.compareUnsigned(frameNrg, 500) < 0 || numZeroCrossing <= 5) {
            lowLevelSignal = 1;
        } else if (numZeroCrossing <= 15) {
            lowLevelSignal = 0;
        } else if (Integer.compareUnsigned(frameNrg, frameNrgLimit) <= 0) {
            lowLevelSignal = 1;
        } else if (numZeroCrossing >= 20) {
            lowLevelSignal = 1;
        } else {
            lowLevelSignal = 0;
        }

        var micLevelTmp = micLevelIn << scale;
        var gainIdx = micVol;
        if (micVol > maxAnalog) {
            gainIdx = maxAnalog;
        }
        int micLevelOut = 0;
        if (micLevelTmp != micRef) {
            micRef = micLevelTmp;
            micVol = 127;
            micLevelOut = 127;
            micGainIdx = 127;
            gainIdx = 127;
        }
        int gain;
        if (gainIdx > 127) {
            gain = GAIN_TABLE_VIRTUAL_MIC[gainIdx - 128];
        } else {
            gain = SUPPRESSION_TABLE_VIRTUAL_MIC[127 - gainIdx];
        }
        for (var ii = 0; ii < samples; ii++) {
            var tmpFlt = (inNear[offset + ii] * gain) >> 10;
            if (tmpFlt > 32767) {
                tmpFlt = 32767;
                gainIdx--;
                if (gainIdx >= 127) {
                    gain = GAIN_TABLE_VIRTUAL_MIC[gainIdx - 127];
                } else {
                    gain = SUPPRESSION_TABLE_VIRTUAL_MIC[127 - gainIdx];
                }
            }
            if (tmpFlt < -32768) {
                tmpFlt = -32768;
                gainIdx--;
                if (gainIdx >= 127) {
                    gain = GAIN_TABLE_VIRTUAL_MIC[gainIdx - 127];
                } else {
                    gain = SUPPRESSION_TABLE_VIRTUAL_MIC[127 - gainIdx];
                }
            }
            inNear[offset + ii] = (short) tmpFlt;
        }
        micGainIdx = gainIdx;
        micLevelOut = micGainIdx >> scale;
        addMic(inNear, offset);
        return micLevelOut;
    }

    /**
     * Sets the analog target and the energy limits from the configuration
     * ({@code WebRtcAgc_UpdateAgcThresholds}).
     */
    private void updateAgcThresholds() {
        var tmp16 = (short) (DIFF_REF_TO_ANALOG * compressionGaindB + ANALOG_TARGET_LEVEL_2);
        tmp16 = FixedPointMath.divW32W16ResW16(tmp16, ANALOG_TARGET_LEVEL);
        analogTarget = (short) (DIGITAL_REF_AT_0_COMP_GAIN + tmp16);
        if (analogTarget < DIGITAL_REF_AT_0_COMP_GAIN) {
            analogTarget = DIGITAL_REF_AT_0_COMP_GAIN;
        }
        if (agcMode == DigitalAgc.MODE_FIXED_DIGITAL) {
            analogTarget = compressionGaindB;
        }
        targetIdx = ANALOG_TARGET_LEVEL + OFFSET_ENV_TO_RMS;
        analogTargetLevel = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx];
        startUpperLimit = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx - 1];
        startLowerLimit = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx + 1];
        upperPrimaryLimit = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx - 2];
        lowerPrimaryLimit = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx + 2];
        upperSecondaryLimit = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx - 5];
        lowerSecondaryLimit = RXX_BUFFER_LEN * TARGET_LEVEL_TABLE[targetIdx + 5];
        upperLimit = startUpperLimit;
        lowerLimit = startLowerLimit;
    }

    /**
     * Detects saturation from the subframe envelopes ({@code WebRtcAgc_SaturationCtrl}).
     *
     * @param envelope the envelopes of the frame
     * @return whether the signal is saturated
     */
    private boolean saturationCtrl(int[] envelope) {
        var saturated = false;
        for (var i = 0; i < 10; i++) {
            var tmpW16 = (short) (envelope[i] >> 20);
            if (tmpW16 > 875) {
                envSum += tmpW16;
            }
        }
        if (envSum > 25000) {
            saturated = true;
            envSum = 0;
        }
        envSum = (short) ((envSum * 32440) >> 15);
        return saturated;
    }

    /**
     * Raises the volume after half a second of zero input ({@code WebRtcAgc_ZeroCtrl}).
     *
     * @param inMicLevel the current volume
     * @param envelope   the envelopes of the frame
     * @return the possibly raised volume
     */
    private int zeroCtrl(int inMicLevel, int[] envelope) {
        long tmp = 0;
        for (var i = 0; i < 10; i++) {
            tmp += envelope[i];
        }
        if (tmp < 500) {
            msZero += 10;
        } else {
            msZero = 0;
        }
        if (muteGuardMs > 0) {
            muteGuardMs -= 10;
        }
        if (msZero > 500) {
            msZero = 0;
            var midVal = (maxAnalog + minLevel + 1) / 2;
            if (inMicLevel < midVal) {
                inMicLevel = (1126 * inMicLevel) >> 10;
                inMicLevel = Math.min(inMicLevel, zeroCtrlMax);
                micVol = inMicLevel;
            }
            activeSpeech = 0;
            rxx16LPw32Max = 0;
            muteGuardMs = MUTE_GUARD_TIME_MS;
        }
        return inMicLevel;
    }

    /**
     * Raises the speech detector threshold after a long inactivity
     * ({@code WebRtcAgc_SpeakerInactiveCtrl}).
     */
    private void speakerInactiveCtrl() {
        if (vadMic.stdLongTerm < 2500) {
            vadThreshold = 1500;
        } else {
            var vadThresh = NORMAL_VAD_THRESHOLD;
            if (vadMic.stdLongTerm < 4500) {
                vadThresh += (short) ((4500 - vadMic.stdLongTerm) / 2);
            }
            var tmp32 = vadThresh + 31 * vadThreshold;
            vadThreshold = (short) (tmp32 >> 5);
        }
    }

    /**
     * Selects the volume increase curve for a normalized volume ({@code WebRtcAgc_ExpCurve}).
     *
     * @param volume the normalized volume, in Q14
     * @return the curve index, in {@code [0, 7]}
     */
    private static int expCurve(short volume) {
        if (volume > 5243) {
            if (volume > 7864) {
                return volume > 12124 ? 7 : 6;
            }
            return volume > 6554 ? 5 : 4;
        }
        if (volume > 2621) {
            return volume > 3932 ? 3 : 2;
        }
        return volume > 1311 ? 1 : 0;
    }

    /**
     * Runs the analog volume adaptation of one frame ({@code WebRtcAgc_ProcessAnalog}).
     *
     * @param inMicLevel  the reported microphone level
     * @param vadLogRatio the speech log likelihood ratio of the frame
     * @param echo        whether the frame holds echo, which forbids raising the volume
     * @return the recommended microphone level, or {@code -1} when {@code inMicLevel} is outside
     *         the level range
     */
    private int processAnalog(int inMicLevel, short vadLogRatio, boolean echo) {
        var inMicLevelTmp = inMicLevel << scale;
        if (inMicLevelTmp > maxAnalog) {
            return -1;
        } else if (inMicLevelTmp < minLevel) {
            return -1;
        }

        if (!firstCallDone) {
            firstCallDone = true;
            var tmp32 = ((maxLevel - minLevel) * 51) >> 9;
            var tmpVol = minLevel + tmp32;
            if (inMicLevelTmp < tmpVol && agcMode == DigitalAgc.MODE_ADAPTIVE_ANALOG) {
                inMicLevelTmp = tmpVol;
            }
            micVol = inMicLevelTmp;
        }

        if (inMicLevelTmp == maxAnalog && micVol > maxAnalog) {
            inMicLevelTmp = micVol;
        }

        if (inMicLevelTmp != micVol && inMicLevelTmp < minOutput) {
            var tmp32 = ((maxLevel - minLevel) * 51) >> 9;
            inMicLevelTmp = minLevel + tmp32;
            micVol = inMicLevelTmp;
        }

        if (inMicLevelTmp != micVol) {
            if (inMicLevel == lastInMicLevel) {
                inMicLevelTmp = micVol;
            } else {
                micVol = inMicLevelTmp;
            }
        }

        if (inMicLevelTmp > maxLevel) {
            maxLevel = inMicLevelTmp;
        }

        lastInMicLevel = inMicLevel;
        var lastMicVol = micVol;

        if (saturationCtrl(env[0])) {
            rxx160LPw32 = (rxx160LPw32 / 8) * 7;
            zeroCtrlMax = micVol;
            var tmp32 = inMicLevelTmp - minLevel;
            var tmpU32 = 29591 * tmp32;
            micVol = (tmpU32 >>> 15) + minLevel;
            if (micVol > lastMicVol - 2) {
                micVol = lastMicVol - 2;
            }
            inMicLevelTmp = micVol;
            msTooHigh = -100;
            activeSpeech = 0;
            rxx16LPw32Max = 0;
            msecSpeechInnerChange = MSEC_SPEECH_INNER;
            msecSpeechOuterChange = MSEC_SPEECH_OUTER;
            changeToSlowMode = 0;
            muteGuardMs = 0;
            upperLimit = startUpperLimit;
            lowerLimit = startLowerLimit;
        }

        inMicLevelTmp = zeroCtrl(inMicLevelTmp, env[0]);
        speakerInactiveCtrl();

        for (var i = 0; i < 5; i++) {
            var rxx16w32 = rxx16w32Array[0][i];
            var tmp32 = (rxx16w32 - rxx16VectorW32[rxx16pos]) >> 3;
            rxx160w32 += tmp32;
            rxx16VectorW32[rxx16pos] = rxx16w32;
            rxx16pos++;
            if (rxx16pos == RXX_BUFFER_LEN) {
                rxx16pos = 0;
            }
            tmp32 = (rxx16w32 - rxx16LPw32) >> ALPHA_SHORT_TERM;
            rxx16LPw32 += tmp32;

            if (vadLogRatio > vadThreshold) {
                if (activeSpeech < 250) {
                    activeSpeech += 2;
                    if (rxx16LPw32 > rxx16LPw32Max) {
                        rxx16LPw32Max = rxx16LPw32;
                    }
                } else if (activeSpeech == 250) {
                    activeSpeech += 2;
                    tmp32 = rxx16LPw32Max >> 3;
                    rxx160LPw32 = tmp32 * RXX_BUFFER_LEN;
                }

                tmp32 = (rxx160w32 - rxx160LPw32) >> ALPHA_LONG_TERM;
                rxx160LPw32 += tmp32;

                if (rxx160LPw32 > upperSecondaryLimit) {
                    msTooHigh += 2;
                    msTooLow = 0;
                    changeToSlowMode = 0;
                    if (msTooHigh > msecSpeechOuterChange) {
                        msTooHigh = 0;
                        tmp32 = rxx160LPw32 >> 6;
                        rxx160LPw32 = tmp32 * 53;
                        maxLevel = (15 * maxLevel + micVol) / 16;
                        maxLevel = Math.max(maxLevel, maxAnalog);
                        zeroCtrlMax = micVol;
                        tmp32 = inMicLevelTmp - minLevel;
                        var tmpU32 = 31130 * tmp32;
                        micVol = (tmpU32 >>> 15) + minLevel;
                        if (micVol > lastMicVol - 1) {
                            micVol = lastMicVol - 1;
                        }
                        inMicLevelTmp = micVol;
                        activeSpeech = 0;
                        rxx16LPw32Max = 0;
                    }
                } else if (rxx160LPw32 > upperLimit) {
                    msTooHigh += 2;
                    msTooLow = 0;
                    changeToSlowMode = 0;
                    if (msTooHigh > msecSpeechInnerChange) {
                        msTooHigh = 0;
                        rxx160LPw32 = (rxx160LPw32 / 64) * 53;
                        maxLevel = (15 * maxLevel + micVol) / 16;
                        maxLevel = Math.max(maxLevel, maxAnalog);
                        zeroCtrlMax = micVol;
                        var tmpU32 = 31621 * (inMicLevelTmp - minLevel);
                        micVol = (tmpU32 >>> 15) + minLevel;
                        if (micVol > lastMicVol - 1) {
                            micVol = lastMicVol - 1;
                        }
                        inMicLevelTmp = micVol;
                    }
                } else if (rxx160LPw32 < lowerSecondaryLimit) {
                    msTooHigh = 0;
                    changeToSlowMode = 0;
                    msTooLow += 2;
                    if (msTooLow > msecSpeechOuterChange) {
                        short volNormFix = 16384;
                        msTooLow = 0;
                        tmp32 = (inMicLevelTmp - minLevel) << 14;
                        if (maxInit != minLevel) {
                            volNormFix = (short) (tmp32 / (maxInit - minLevel));
                        }
                        var index = expCurve(volNormFix);
                        var weightFix = (short) (OFFSET_1[index] - (short) ((SLOPE_1[index] * volNormFix) >> 13));
                        rxx160LPw32 = (rxx160LPw32 / 64) * 67;
                        var tmpU32 = weightFix * (inMicLevelTmp - minLevel);
                        micVol = (tmpU32 >>> 14) + minLevel;
                        if (micVol < lastMicVol + 2) {
                            micVol = lastMicVol + 2;
                        }
                        inMicLevelTmp = micVol;
                    }
                } else if (rxx160LPw32 < lowerLimit) {
                    msTooHigh = 0;
                    changeToSlowMode = 0;
                    msTooLow += 2;
                    if (msTooLow > msecSpeechInnerChange) {
                        short volNormFix = 16384;
                        msTooLow = 0;
                        tmp32 = (inMicLevelTmp - minLevel) << 14;
                        if (maxInit != minLevel) {
                            volNormFix = (short) (tmp32 / (maxInit - minLevel));
                        }
                        var index = expCurve(volNormFix);
                        var weightFix = (short) (OFFSET_2[index] - (short) ((SLOPE_2[index] * volNormFix) >> 13));
                        rxx160LPw32 = (rxx160LPw32 / 64) * 67;
                        var tmpU32 = weightFix * (inMicLevelTmp - minLevel);
                        micVol = (tmpU32 >>> 14) + minLevel;
                        if (micVol < lastMicVol + 1) {
                            micVol = lastMicVol + 1;
                        }
                        inMicLevelTmp = micVol;
                    }
                } else {
                    if (changeToSlowMode > 4000) {
                        msecSpeechInnerChange = 1000;
                        msecSpeechOuterChange = 500;
                        upperLimit = upperPrimaryLimit;
                        lowerLimit = lowerPrimaryLimit;
                    } else {
                        changeToSlowMode += 2;
                    }
                    msTooLow = 0;
                    msTooHigh = 0;
                    micVol = inMicLevelTmp;
                }
            }
        }

        if (echo || (muteGuardMs > 0 && muteGuardMs < MUTE_GUARD_TIME_MS)) {
            if (micVol > lastMicVol) {
                micVol = lastMicVol;
            }
        }

        if (micVol > maxLevel) {
            micVol = maxLevel;
        } else if (micVol < minOutput) {
            micVol = minOutput;
        }

        return Math.min(micVol, maxAnalog) >> scale;
    }

    /**
     * Computes the digital gains of a frame and runs the analog adaptation
     * ({@code WebRtcAgc_Analyze}).
     *
     * <p>When the analog adaptation rejects {@code inMicLevel} (outside the level range), the
     * returned level is {@code inMicLevel} and the queued statistics are not advanced, as in the
     * reference.
     *
     * @param inNear     the 160 samples of the frame, after the earlier processing stages
     * @param offset     the index of the first sample
     * @param inMicLevel the microphone level of the frame
     * @param echo       whether the frame holds echo
     * @param gains      receives the eleven gains, in Q16
     * @return the recommended microphone level
     */
    int analyze(short[] inNear, int offset, int inMicLevel, boolean echo, int[] gains) {
        digitalAgc.computeDigitalGains(inNear, offset, lowLevelSignal, gains);
        var outMicLevel = inMicLevel;
        if (agcMode < DigitalAgc.MODE_FIXED_DIGITAL
                && (lowLevelSignal == 0 || agcMode != DigitalAgc.MODE_ADAPTIVE_DIGITAL)) {
            var recommended = processAnalog(inMicLevel, vadMic.logRatio, echo);
            if (recommended == -1) {
                return inMicLevel;
            }
            outMicLevel = recommended;
        }
        if (inQueue > 1) {
            System.arraycopy(env[1], 0, env[0], 0, 10);
            System.arraycopy(rxx16w32Array[1], 0, rxx16w32Array[0], 0, 5);
        }
        if (inQueue > 0) {
            inQueue--;
        }
        return outMicLevel;
    }

    /**
     * Applies a configuration ({@code WebRtcAgc_set_config}).
     *
     * @param targetLevel     the target level, in dB below full scale, in {@code [0, 31]}
     * @param compressionGain the compression gain, in dB
     * @param limiter         whether the limiter is enabled
     * @return {@code 0}, or {@code -1} when the configuration is rejected
     */
    int setConfig(int targetLevel, int compressionGain, boolean limiter) {
        limiterEnable = limiter;
        compressionGaindB = (short) compressionGain;
        if (targetLevel < 0 || targetLevel > 31) {
            return -1;
        }
        targetLevelDbfs = (short) targetLevel;
        if (agcMode == DigitalAgc.MODE_FIXED_DIGITAL) {
            compressionGaindB += (short) targetLevel;
        }
        updateAgcThresholds();
        return DigitalAgc.calculateGainTable(digitalAgc.gainTable, compressionGaindB, targetLevelDbfs,
                limiterEnable, analogTarget);
    }

    /**
     * Resets the controller for a level range and a mode ({@code WebRtcAgc_Init}) and applies the
     * default configuration.
     *
     * @param minLevelIn the minimum microphone level
     * @param maxLevelIn the maximum microphone level
     * @param mode       the mode, one of the {@code DigitalAgc.MODE_*} constants
     * @return {@code 0}, or {@code -1} when the arguments are rejected
     */
    int init(int minLevelIn, int maxLevelIn, int mode) {
        digitalAgc.init(mode);
        envSum = 0;
        if (mode < 0 || mode > DigitalAgc.MODE_FIXED_DIGITAL) {
            return -1;
        }
        agcMode = mode;
        vadMic.init();
        scale = 0;
        var maxLvl = maxLevelIn;
        var minLvl = minLevelIn;
        if (agcMode == DigitalAgc.MODE_ADAPTIVE_DIGITAL) {
            minLvl = 0;
            maxLvl = 255;
        }
        var maxAdd = (maxLvl - minLvl) / 4;
        minLevel = minLvl;
        maxAnalog = maxLvl;
        maxLevel = maxLvl + maxAdd;
        maxInit = maxLevel;
        zeroCtrlMax = maxAnalog;
        lastInMicLevel = 0;
        micVol = maxAnalog;
        if (agcMode == DigitalAgc.MODE_ADAPTIVE_DIGITAL) {
            micVol = 127;
        }
        micRef = micVol;
        micGainIdx = 127;
        var tmp32 = ((maxLevel - minLevel) * 10) >> 8;
        minOutput = minLevel + tmp32;
        msTooLow = 0;
        msTooHigh = 0;
        changeToSlowMode = 0;
        firstCallDone = false;
        msZero = 0;
        muteGuardMs = 0;
        gainTableIdx = 0;
        msecSpeechInnerChange = MSEC_SPEECH_INNER;
        msecSpeechOuterChange = MSEC_SPEECH_OUTER;
        activeSpeech = 0;
        rxx16LPw32Max = 0;
        vadThreshold = NORMAL_VAD_THRESHOLD;
        java.util.Arrays.fill(rxx16VectorW32, 1000);
        rxx160w32 = 125 * RXX_BUFFER_LEN;
        rxx16pos = 0;
        rxx16LPw32 = 16284;
        java.util.Arrays.fill(rxx16w32Array[0], 0);
        java.util.Arrays.fill(env[0], 0);
        java.util.Arrays.fill(env[1], 0);
        inQueue = 0;
        java.util.Arrays.fill(filterState, 0);
        if (setConfig(DEFAULT_TARGET_LEVEL, DEFAULT_COMP_GAIN, true) == -1) {
            return -1;
        }
        rxx160LPw32 = analogTargetLevel;
        lowLevelSignal = 0;
        if (minLvl >= maxLvl || (maxLvl & 0xFC000000) != 0) {
            return -1;
        }
        return 0;
    }
}
