package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Computes the digital gains of the gain controller: a compressor whose input level follows a
 * fast and a slow envelope of the signal, a gate that lowers the gain while no one speaks and a
 * protection against overload.
 *
 * <p>For each 10 ms frame, {@link #computeDigitalGains(short[], int, int, int[])} returns eleven
 * gains in Q16, one at the start of each millisecond plus one at the end of the frame, which the
 * caller interpolates linearly over the samples. The gain curve comes from
 * {@link #calculateGainTable(int[], int, int, boolean, int)}: 32 gains indexed by the number of
 * leading zeros of the envelope.
 *
 * @implNote This implementation ports {@code DigitalAgc}, {@code WebRtcAgc_InitDigital},
 * {@code WebRtcAgc_AddFarendToDigital}, {@code WebRtcAgc_ComputeDigitalGains} and
 * {@code WebRtcAgc_CalculateGainTable} of WebRTC's legacy gain controller
 * ({@code modules/audio_processing/agc/legacy/digital_agc.cc}, webrtc-audio-processing 2.1).
 */
final class DigitalAgc {
    /**
     * The number of entries of {@link #GEN_FUNC_TABLE}.
     */
    private static final int GEN_FUNC_TABLE_SIZE = 128;

    /**
     * The generator table of {@code log2(1 + e^x)} in Q8 ({@code kGenFuncTable}).
     */
    private static final int[] GEN_FUNC_TABLE = {
            256, 485, 786, 1126, 1484, 1849, 2217, 2586, 2955, 3324, 3693,
            4063, 4432, 4801, 5171, 5540, 5909, 6279, 6648, 7017, 7387, 7756,
            8125, 8495, 8864, 9233, 9603, 9972, 10341, 10711, 11080, 11449, 11819,
            12188, 12557, 12927, 13296, 13665, 14035, 14404, 14773, 15143, 15512, 15881,
            16251, 16620, 16989, 17359, 17728, 18097, 18466, 18836, 19205, 19574, 19944,
            20313, 20682, 21052, 21421, 21790, 22160, 22529, 22898, 23268, 23637, 24006,
            24376, 24745, 25114, 25484, 25853, 26222, 26592, 26961, 27330, 27700, 28069,
            28438, 28808, 29177, 29546, 29916, 30285, 30654, 31024, 31393, 31762, 32132,
            32501, 32870, 33240, 33609, 33978, 34348, 34717, 35086, 35456, 35825, 36194,
            36564, 36933, 37302, 37672, 38041, 38410, 38780, 39149, 39518, 39888, 40257,
            40626, 40996, 41365, 41734, 42104, 42473, 42842, 43212, 43581, 43950, 44320,
            44689, 45058, 45428, 45797, 46166, 46536, 46905
    };

    /**
     * The adaptive analog mode of the legacy controller ({@code kAgcModeAdaptiveAnalog}).
     */
    static final int MODE_ADAPTIVE_ANALOG = 1;

    /**
     * The adaptive digital mode of the legacy controller ({@code kAgcModeAdaptiveDigital}).
     */
    static final int MODE_ADAPTIVE_DIGITAL = 2;

    /**
     * The fixed digital mode of the legacy controller ({@code kAgcModeFixedDigital}).
     */
    static final int MODE_FIXED_DIGITAL = 3;

    /**
     * The slow envelope, in the squared 16-bit domain.
     */
    private int capacitorSlow;

    /**
     * The fast envelope, in the squared 16-bit domain.
     */
    private int capacitorFast;

    /**
     * The gain at the end of the previous frame, in Q16.
     */
    private int gain;

    /**
     * The compressor gain curve, in Q16, indexed by the leading zeros of the envelope.
     */
    final int[] gainTable;

    /**
     * The smoothed gate value of the previous frame.
     */
    private short gatePrevious;

    /**
     * The legacy controller mode, one of the {@code MODE_*} constants.
     */
    private int agcMode;

    /**
     * The speech activity detector of the near-end signal.
     */
    private final AgcVad vadNearend;

    /**
     * The speech activity detector of the far-end signal.
     */
    private final AgcVad vadFarend;

    /**
     * The peak energies of the ten 1 ms subframes of the current frame.
     */
    private final int[] env;

    /**
     * Creates the digital part with an empty gain table.
     */
    DigitalAgc() {
        this.gainTable = new int[32];
        this.vadNearend = new AgcVad();
        this.vadFarend = new AgcVad();
        this.env = new int[10];
    }

    /**
     * Resets the envelopes, the gain and both detectors for a mode ({@code WebRtcAgc_InitDigital}).
     *
     * @param mode the legacy controller mode
     */
    void init(int mode) {
        capacitorSlow = mode == MODE_FIXED_DIGITAL ? 0 : 134217728;
        capacitorFast = 0;
        gain = 65536;
        gatePrevious = 0;
        agcMode = mode;
        vadNearend.init();
        vadFarend.init();
    }

    /**
     * Updates the far-end detector with one 10 ms render frame
     * ({@code WebRtcAgc_AddFarendToDigital}).
     *
     * @param farend    the render samples
     * @param offset    the index of the first sample
     * @param nrSamples the number of samples
     */
    void addFarend(short[] farend, int offset, int nrSamples) {
        vadFarend.process(farend, offset, nrSamples);
    }

    /**
     * Computes the eleven gains of one 10 ms frame ({@code WebRtcAgc_ComputeDigitalGains}).
     *
     * @param near           the near-end samples of the lowest band
     * @param offset         the index of the first sample
     * @param lowLevelSignal whether the frame was classified as a low-level signal, which
     *                       freezes the slow envelope in the adaptive modes
     * @param gains          receives the gains in Q16
     */
    void computeDigitalGains(short[] near, int offset, int lowLevelSignal, int[] gains) {
        final var l = 16;
        var logratio = vadNearend.process(near, offset, l * 10);

        if (vadFarend.counter > 10) {
            var tmp32 = 3 * logratio;
            logratio = (short) ((tmp32 - vadFarend.logRatio) >> 2);
        }

        final short upperThr = 1024;
        final short lowerThr = 0;
        short decay;
        if (logratio > upperThr) {
            decay = -65;
        } else if (logratio < lowerThr) {
            decay = 0;
        } else {
            var tmp32 = (lowerThr - logratio) * 65;
            decay = (short) (tmp32 >> 10);
        }

        if (agcMode != MODE_FIXED_DIGITAL) {
            if (vadNearend.stdLongTerm < 4000) {
                decay = 0;
            } else if (vadNearend.stdLongTerm < 8096) {
                var tmp32 = (vadNearend.stdLongTerm - 4000) * decay;
                decay = (short) (tmp32 >> 12);
            }
            if (lowLevelSignal != 0) {
                decay = 0;
            }
        }

        for (var k = 0; k < 10; k++) {
            var maxNrg = 0;
            for (var n = 0; n < l; n++) {
                var sample = near[offset + k * l + n];
                var nrg = sample * sample;
                if (nrg > maxNrg) {
                    maxNrg = nrg;
                }
            }
            env[k] = maxNrg;
        }

        gains[0] = gain;
        short zeros = 0;
        short frac = 0;
        for (var k = 0; k < 10; k++) {
            capacitorFast = agcScaleDiff32(-1000, capacitorFast, capacitorFast);
            if (env[k] > capacitorFast) {
                capacitorFast = env[k];
            }
            if (env[k] > capacitorSlow) {
                capacitorSlow = agcScaleDiff32(500, env[k] - capacitorSlow, capacitorSlow);
            } else {
                capacitorSlow = agcScaleDiff32(decay, capacitorSlow, capacitorSlow);
            }
            var curLevel = capacitorFast > capacitorSlow ? capacitorFast : capacitorSlow;
            zeros = (short) FixedPointMath.normU32(curLevel);
            if (curLevel == 0) {
                zeros = 31;
            }
            var tmp32 = (curLevel << zeros) & 0x7FFFFFFF;
            frac = (short) (tmp32 >> 19);
            var interpolated = (int) (((long) (gainTable[zeros - 1] - gainTable[zeros]) * frac) >> 12);
            gains[k + 1] = gainTable[zeros] + interpolated;
        }

        zeros = (short) ((zeros << 9) - (frac >> 3));
        var zerosFast = (short) FixedPointMath.normU32(capacitorFast);
        if (capacitorFast == 0) {
            zerosFast = 31;
        }
        var tmp32 = (capacitorFast << zerosFast) & 0x7FFFFFFF;
        zerosFast <<= 9;
        zerosFast -= (short) (tmp32 >> 22);

        var gate = (short) (1000 + zerosFast - zeros - vadNearend.stdShortTerm);
        if (gate < 0) {
            gatePrevious = 0;
        } else {
            tmp32 = gatePrevious * 7;
            gate = (short) ((gate + tmp32) >> 3);
            gatePrevious = gate;
        }
        if (gate > 0) {
            short gainAdj;
            if (gate < 2500) {
                gainAdj = (short) ((2500 - gate) >> 5);
            } else {
                gainAdj = 0;
            }
            for (var k = 0; k < 10; k++) {
                if (gains[k + 1] - gainTable[0] > 8388608) {
                    tmp32 = (gains[k + 1] - gainTable[0]) >> 8;
                    tmp32 *= 178 + gainAdj;
                } else {
                    tmp32 = (gains[k + 1] - gainTable[0]) * (178 + gainAdj);
                    tmp32 >>= 8;
                }
                gains[k + 1] = gainTable[0] + tmp32;
            }
        }

        for (var k = 0; k < 10; k++) {
            var shift = 10;
            if (gains[k + 1] > 47452159) {
                shift = 16 - FixedPointMath.normW32(gains[k + 1]);
            }
            var gain32 = (gains[k + 1] >> shift) + 1;
            gain32 *= gain32;
            while (agcMul32((env[k] >> 12) + 1, gain32) > shiftW32(32767, 2 * (1 - shift + 10))) {
                if (gains[k + 1] > 8388607) {
                    gains[k + 1] = (gains[k + 1] / 256) * 253;
                } else {
                    gains[k + 1] = (gains[k + 1] * 253) / 256;
                }
                gain32 = (gains[k + 1] >> shift) + 1;
                gain32 *= gain32;
            }
        }
        for (var k = 1; k < 10; k++) {
            if (gains[k] > gains[k + 1]) {
                gains[k] = gains[k + 1];
            }
        }
        gain = gains[10];
    }

    /**
     * Generates the compressor gain curve ({@code WebRtcAgc_CalculateGainTable}).
     *
     * @param gainTable     receives the 32 gains in Q16
     * @param digCompGaindB the compression gain, in dB
     * @param targetLevelDbfs the target level, in dB below full scale
     * @param limiterEnable whether the limiter is enabled
     * @param analogTarget  the analog target level, in dB
     * @return {@code 0}, or {@code -1} when the compression gain is out of the table's range
     */
    static int calculateGainTable(int[] gainTable, int digCompGaindB, int targetLevelDbfs, boolean limiterEnable,
                                  int analogTarget) {
        final var kLog10 = 54426;
        final var kLog10x2 = 49321;
        final var kLogE1 = 23637;
        final var kCompRatio = 3;
        short limiterOffset = 0;

        var tmp32no1 = (digCompGaindB - analogTarget) * (kCompRatio - 1);
        var tmp16no1 = (short) (analogTarget - targetLevelDbfs);
        tmp16no1 += FixedPointMath.divW32W16ResW16(tmp32no1 + (kCompRatio >> 1), kCompRatio);
        var maxGain = (short) Math.max(tmp16no1, analogTarget - targetLevelDbfs);
        if (digCompGaindB <= analogTarget && limiterEnable) {
            limiterOffset = 0;
        }

        tmp32no1 = digCompGaindB * (kCompRatio - 1);
        var diffGain = FixedPointMath.divW32W16ResW16(tmp32no1 + (kCompRatio >> 1), kCompRatio);
        if (diffGain < 0 || diffGain >= GEN_FUNC_TABLE_SIZE) {
            return -1;
        }

        var limiterLvlX = (short) (analogTarget - limiterOffset);
        var limiterIdx = (short) (2 + FixedPointMath.divW32W16ResW16(limiterLvlX * (1 << 13), kLog10x2 / 2));
        tmp16no1 = FixedPointMath.divW32W16ResW16(limiterOffset + (kCompRatio >> 1), kCompRatio);
        var limiterLvl = targetLevelDbfs + tmp16no1;

        var constMaxGain = GEN_FUNC_TABLE[diffGain];
        final var constLinApprox = 22817;
        var den = 20 * constMaxGain;

        for (var i = 0; i < 32; i++) {
            var tmp16 = (short) ((kCompRatio - 1) * (i - 1));
            var tmp32 = tmp16 * kLog10x2 + 1;
            var inLevel = FixedPointMath.divW32W16(tmp32, kCompRatio);
            inLevel = diffGain * (1 << 14) - inLevel;
            var absInLevel = inLevel >= 0 ? inLevel : -inLevel;

            var intPart = (absInLevel >>> 14) & 0xFFFF;
            var fracPart = absInLevel & 0x00003FFF;
            var tmpU16 = (GEN_FUNC_TABLE[intPart + 1] - GEN_FUNC_TABLE[intPart]) & 0xFFFF;
            var tmpU32no1 = tmpU16 * fracPart;
            tmpU32no1 += GEN_FUNC_TABLE[intPart] << 14;
            var logApprox = tmpU32no1 >>> 8;
            if (inLevel < 0) {
                var zeros = FixedPointMath.normU32(absInLevel);
                var zerosScale = 0;
                int tmpU32no2;
                if (zeros < 15) {
                    tmpU32no2 = absInLevel >>> (15 - zeros);
                    tmpU32no2 = tmpU32no2 * kLogE1;
                    if (zeros < 9) {
                        zerosScale = 9 - zeros;
                        tmpU32no1 >>>= zerosScale;
                    } else {
                        tmpU32no2 >>>= zeros - 9;
                    }
                } else {
                    tmpU32no2 = absInLevel * kLogE1;
                    tmpU32no2 >>>= 6;
                }
                logApprox = 0;
                if (Integer.compareUnsigned(tmpU32no2, tmpU32no1) < 0) {
                    logApprox = (tmpU32no1 - tmpU32no2) >>> (8 - zerosScale);
                }
            }
            var numFix = (maxGain * constMaxGain) * (1 << 6);
            numFix -= logApprox * diffGain;

            int zeros;
            if (numFix > (den >> 8) || -numFix > (den >> 8)) {
                zeros = FixedPointMath.normW32(numFix);
            } else {
                zeros = FixedPointMath.normW32(den) + 8;
            }
            numFix *= 1 << zeros;

            tmp32no1 = shiftW32(den, zeros - 9);
            var y32 = numFix / tmp32no1;
            y32 = y32 >= 0 ? (y32 + 1) >> 1 : -((-y32 + 1) >> 1);

            if (limiterEnable && i < limiterIdx) {
                tmp32 = (short) (i - 1) * kLog10x2;
                tmp32 -= limiterLvl * (1 << 14);
                y32 = FixedPointMath.divW32W16(tmp32 + 10, 20);
            }
            if (y32 > 39000) {
                tmp32 = (y32 >> 1) * kLog10 + 4096;
                tmp32 >>= 13;
            } else {
                tmp32 = y32 * kLog10 + 8192;
                tmp32 >>= 14;
            }
            tmp32 += 16 << 14;

            if (tmp32 > 0) {
                var power = (short) (tmp32 >> 14);
                var fraction = tmp32 & 0x00003FFF;
                int tmp32no2;
                if ((fraction >> 13) != 0) {
                    var factor = (short) ((2 << 14) - constLinApprox);
                    tmp32no2 = (1 << 14) - fraction;
                    tmp32no2 *= factor;
                    tmp32no2 >>= 13;
                    tmp32no2 = (1 << 14) - tmp32no2;
                } else {
                    var factor = (short) (constLinApprox - (1 << 14));
                    tmp32no2 = (fraction * factor) >> 13;
                }
                fraction = tmp32no2 & 0xFFFF;
                gainTable[i] = (1 << power) + shiftW32(fraction, power - 14);
            } else {
                gainTable[i] = 0;
            }
        }
        return 0;
    }

    /**
     * Returns {@code c + (b * a >> 16)} computed in two signed halves ({@code AGC_SCALEDIFF32}).
     *
     * @param a the signed factor
     * @param b the 32-bit value
     * @param c the accumulator
     * @return the scaled sum, wrapped to 32 bits
     */
    private static int agcScaleDiff32(int a, int b, int c) {
        return c + (b >> 16) * a + (((0x0000FFFF & b) * a) >> 16);
    }

    /**
     * Returns the 32 most significant bits of {@code a * b >> 13} computed in two halves
     * ({@code AGC_MUL32}).
     *
     * @param a the first factor
     * @param b the second factor
     * @return the product, wrapped to 32 bits
     */
    private static int agcMul32(int a, int b) {
        return (b >> 13) * a + (((0x00001FFF & b) * a) >> 13);
    }

    /**
     * Shifts left for a non-negative count and right for a negative one
     * ({@code WEBRTC_SPL_SHIFT_W32}).
     *
     * @param x the value
     * @param c the shift count
     * @return the shifted value
     */
    private static int shiftW32(int x, int c) {
        return c >= 0 ? x * (1 << c) : x >> -c;
    }
}
