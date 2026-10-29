/* Ports opencore-amr 0.1.6 (vad2.cpp, r_fft.cpp, lflg_upd.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractHi;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractLo;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lNegate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lSub;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy3216;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.multR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;

/**
 * Detects voice activity in the AMR-NB encoder input with the TS 26.094 option 2 detector: a
 * 128-point FFT of each half frame split into 16 channels, a channel noise estimate, a voice
 * metric summed over the channel signal-to-noise ratios with a threshold, hangover and burst
 * count driven by the total signal-to-noise ratio, and noise updates gated by the spectral
 * deviation and the long-term prediction flag the open-loop pitch search sets.
 *
 * <p>The frame decision is the logical or of the decisions on its two halves. The open-loop
 * search accumulates the correlation and the energy of its selected lags through
 * {@link #pitchSelected(int, int, Flag)}, and {@link #openLoopEnd(int, short[], Flag)} turns them
 * into the long-term prediction flag the next frame's noise update reads.
 */
final class Vad2 implements VoiceActivityDetector {
    /**
     * The samples of a half frame, the unit the detector analyzes.
     */
    private static final int FRM_LEN = 80;

    /**
     * The zero samples ahead of the pre-emphasized half frame in the FFT buffer.
     */
    private static final int DELAY = 24;

    /**
     * The length of the real FFT.
     */
    private static final int FFT_LEN = 128;

    /**
     * The number of spectral channels.
     */
    private static final int NUM_CHAN = 16;

    /**
     * The voice metric at or below which the noise estimate is updated.
     */
    private static final int UPDATE_THLD = 35;

    /**
     * The half frames with an unchanged forced-update count after which the count restarts.
     */
    private static final int HYSTER_CNT_THLD = 6;

    /**
     * The forced-update count at which a stationary signal updates the noise estimate.
     */
    private static final int UPDATE_CNT_THLD = 50;

    /**
     * The smallest channel noise energy (0.0625, scaled as 22,9).
     */
    private static final int MIN_NOISE_ENRG_0 = 32;

    /**
     * The initial channel noise energy (16.0, scaled as 22,9).
     */
    private static final int INE_NOISE_0 = 8192;

    /**
     * The fractional bits of the channel noise energies.
     */
    private static final short FRACTIONAL_BITS_0 = 9;

    /**
     * The largest smoothing factor of the long-term channel energies, Q15.
     */
    private static final short HIGH_ALPHA = 29491;

    /**
     * The smallest smoothing factor of the long-term channel energies, Q15.
     */
    private static final short LOW_ALPHA = 22938;

    /**
     * The range of the smoothing factor of the long-term channel energies, Q15.
     */
    private static final short ALPHA_RANGE = HIGH_ALPHA - LOW_ALPHA;

    /**
     * The spectral deviation below which a signal counts as stationary (28.0 dB, scaled as 7,8).
     */
    private static final short DEV_THLD = 7168;

    /**
     * The pre-emphasis factor (-0.8), Q15.
     */
    private static final short PRE_EMP_FAC = -26214;

    /**
     * The smoothing factor of the channel energy estimate (0.55), Q15.
     */
    private static final short CEE_SM_FAC = 18022;

    /**
     * One minus {@link #CEE_SM_FAC}, Q15.
     */
    private static final short ONE_MINUS_CEE_SM_FAC = 14746;

    /**
     * The smoothing factor of the channel noise update (0.1), Q15.
     */
    private static final short CNE_SM_FAC = 3277;

    /**
     * One minus {@link #CNE_SM_FAC}, Q15.
     */
    private static final short ONE_MINUS_CNE_SM_FAC = 29491;

    /**
     * The bits of headroom the block normalization leaves for the FFT.
     */
    private static final short FFT_HEADROOM = 2;

    /**
     * The channel noise floor of each scaling state (1.0 scaled as 22,9 and as 27,4).
     */
    private static final int[] NOISE_FLOOR_CHAN = {512, 16};

    /**
     * The smallest channel energy of each scaling state (0.0625).
     */
    private static final int[] MIN_CHAN_ENRG = {32, 1};

    /**
     * The initial noise energy of each scaling state (16.0).
     */
    private static final int[] INE_NOISE = {8192, 256};

    /**
     * The fractional bits of each scaling state.
     */
    private static final short[] FBITS = {9, 4};

    /**
     * The right shift applied to the channel energies on entering each scaling state.
     */
    private static final short[] STATE_CHANGE_SHIFT_R = {-5, 5};

    /**
     * The normalization shift of the channel energies of each scaling state.
     */
    private static final short[] ENRG_NORM_SHIFT = {9 - 1 + 2, 4 - 1 + 2};

    /**
     * The first and last FFT bin of each channel.
     */
    private static final short[] CH_TBL = {
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19,
            20, 22, 23, 26, 27, 30, 31, 35, 36, 41, 42, 48, 49, 55, 56, 63
    };

    /**
     * The inverse number of bins of each channel, Q15.
     */
    private static final short[] CH_TBL_SH = {
            16384, 16384, 16384, 16384, 16384, 16384, 10923, 10923,
            10923, 8192, 8192, 6554, 5461, 4681, 4681, 4096
    };

    /**
     * The voice metric of each quantized channel signal-to-noise ratio.
     */
    private static final short[] VM_TBL = {
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 7, 7, 7,
            8, 8, 9, 9, 10, 10, 11, 12, 12, 13, 13, 14, 15,
            15, 16, 17, 17, 18, 19, 20, 20, 21, 22, 23, 24,
            24, 25, 26, 27, 28, 28, 29, 30, 31, 32, 33, 34,
            35, 36, 37, 37, 38, 39, 40, 41, 42, 43, 44, 45,
            46, 47, 48, 49, 50, 50, 50, 50, 50, 50, 50, 50,
            50, 50
    };

    /**
     * The hangover of each quantized total signal-to-noise ratio.
     */
    private static final short[] HANGOVER_TABLE = {
            30, 30, 30, 30, 30, 30, 28, 26, 24, 22, 20, 18, 16, 14, 12, 10, 8, 8, 8, 8
    };

    /**
     * The burst count of each quantized total signal-to-noise ratio.
     */
    private static final short[] BURSTCOUNT_TABLE = {
            8, 8, 8, 8, 8, 8, 8, 8, 7, 6, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4
    };

    /**
     * The voice metric threshold of each quantized total signal-to-noise ratio.
     */
    private static final short[] VM_THRESHOLD_TABLE = {
            34, 34, 34, 34, 34, 34, 34, 34, 34, 34,
            34, 40, 51, 71, 100, 139, 191, 257, 337, 432
    };

    /**
     * The FFT twiddle factors, interleaved cosine and sine, Q15.
     */
    private static final short[] PHS_TBL = {
            32767, 0, 32729, -1608, 32610, -3212, 32413, -4808,
            32138, -6393, 31786, -7962, 31357, -9512, 30853, -11039,
            30274, -12540, 29622, -14010, 28899, -15447, 28106, -16846,
            27246, -18205, 26320, -19520, 25330, -20788, 24279, -22006,
            23170, -23170, 22006, -24279, 20788, -25330, 19520, -26320,
            18205, -27246, 16846, -28106, 15447, -28899, 14010, -29622,
            12540, -30274, 11039, -30853, 9512, -31357, 7962, -31786,
            6393, -32138, 4808, -32413, 3212, -32610, 1608, -32729,
            0, -32768, -1608, -32729, -3212, -32610, -4808, -32413,
            -6393, -32138, -7962, -31786, -9512, -31357, -11039, -30853,
            -12540, -30274, -14010, -29622, -15447, -28899, -16846, -28106,
            -18205, -27246, -19520, -26320, -20788, -25330, -22006, -24279,
            -23170, -23170, -24279, -22006, -25330, -20788, -26320, -19520,
            -27246, -18205, -28106, -16846, -28899, -15447, -29622, -14010,
            -30274, -12540, -30853, -11039, -31357, -9512, -31786, -7962,
            -32138, -6393, -32413, -4808, -32610, -3212, -32729, -1608
    };

    /**
     * The last input sample of the previous half frame, the pre-emphasis memory.
     */
    private short preEmpMem;

    /**
     * The forced-update count of a stationary signal.
     */
    private short updateCnt;

    /**
     * The half frames over which {@link #updateCnt} has not changed.
     */
    private short hysterCnt;

    /**
     * The value of {@link #updateCnt} after the previous half frame.
     */
    private short lastUpdateCnt;

    /**
     * The long-term channel energies in dB, scaled as 7,8.
     */
    private final short[] chEnrgLongDb = new short[NUM_CHAN];

    /**
     * The number of half frames analyzed since the reset.
     */
    private int lframeCnt;

    /**
     * The channel energy estimates, scaled as 22,9 or 27,4 by {@link #shiftState}.
     */
    private final int[] lchEnrg = new int[NUM_CHAN];

    /**
     * The channel noise estimates, scaled as 22,9.
     */
    private final int[] lchNoise = new int[NUM_CHAN];

    /**
     * The block normalization shift of the previous half frame.
     */
    private short lastNormbShift;

    /**
     * The smoothed total signal-to-noise ratio, scaled as 7,8.
     */
    private short tsnr;

    /**
     * The half frames of hangover left.
     */
    private short hangover;

    /**
     * The length of the current burst of active half frames.
     */
    private short burstcount;

    /**
     * Whether the last noise update was forced by a stationary signal.
     */
    private boolean fupdateFlag;

    /**
     * The variance of the negative total signal-to-noise ratios.
     */
    private short negSnrVar;

    /**
     * The voice metric threshold bias derived from {@link #negSnrVar}.
     */
    private short negSnrBias;

    /**
     * The scaling state of the channel energies: 0 for a normal level, 1 for a high level.
     */
    private int shiftState;

    /**
     * The energy of the lags the open-loop search selected in the frame.
     */
    private int lR0;

    /**
     * The correlation of the lags the open-loop search selected in the frame.
     */
    private int lRmax;

    /**
     * Whether the previous frame had a high long-term prediction gain, which blocks a forced noise update.
     */
    private boolean ltpFlag;

    /**
     * The block-normalized input half frame.
     */
    private final short[] inputBuffer = new short[FRM_LEN];

    /**
     * The FFT buffer: the pre-emphasized half frame between zero padding, then its spectrum.
     */
    private final short[] dataBuffer = new short[FFT_LEN];

    /**
     * The channel signal-to-noise ratios of the half frame.
     */
    private final short[] chSnr = new short[NUM_CHAN];

    /**
     * The channel energies of the half frame in dB, scaled as 7,8.
     */
    private final short[] chEnrgDb = new short[NUM_CHAN];

    /**
     * Creates a detector in its reset state.
     */
    Vad2() {
        reset();
    }

    @Override
    public void reset() {
        preEmpMem = 0;
        updateCnt = 0;
        hysterCnt = 0;
        lastUpdateCnt = 0;
        java.util.Arrays.fill(chEnrgLongDb, (short) 0);
        lframeCnt = 0;
        java.util.Arrays.fill(lchEnrg, 0);
        java.util.Arrays.fill(lchNoise, 0);
        lastNormbShift = 0;
        tsnr = 0;
        hangover = 0;
        burstcount = 0;
        fupdateFlag = false;
        negSnrVar = 0;
        negSnrBias = 0;
        shiftState = 0;
        lR0 = 0;
        lRmax = 0;
        ltpFlag = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs the detector on both halves of the frame; both halves always update the state.
     */
    @Override
    public short vad(short[] in, int inOffset, Flag overflow) {
        var first = vad2(in, inOffset, overflow);
        var second = vad2(in, inOffset + FRM_LEN, overflow);
        return (short) (first != 0 || second != 0 ? 1 : 0);
    }

    @Override
    public void openLoopStart() {
        lRmax = 0;
        lR0 = 0;
    }

    @Override
    public void toneDetectionUpdate(boolean oneLagPerFrame, Flag overflow) {
    }

    @Override
    public void toneDetection(int t0, int t1, Flag overflow) {
    }

    @Override
    public boolean usesComplexDetection() {
        return false;
    }

    @Override
    public void complexDetectionUpdate(short bestCorrHp) {
    }

    @Override
    public void pitchSelected(int rmax, int r0, Flag overflow) {
        lRmax = lAdd(lRmax, rmax, overflow);
        lR0 = lAdd(lR0, r0, overflow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the long-term prediction flag when the accumulated correlation exceeds a
     * mode-dependent fraction of the accumulated energy ({@code LTP_flag_update}).
     */
    @Override
    public void openLoopEnd(int mode, short[] tOp, Flag overflow) {
        short thresh;
        if (mode == Mode.MR475 || mode == Mode.MR515) {
            thresh = 18022;
        } else if (mode == Mode.MR102) {
            thresh = 19660;
        } else {
            thresh = 21299;
        }
        var ltmp = mpy3216(lExtractHi(lR0), lExtractLo(lR0), thresh, overflow);
        ltpFlag = lRmax > ltmp;
    }

    /**
     * Converts a 32-bit energy to dB, scaled as 7,8 ({@code fn10Log10}).
     *
     * @param input the energy
     * @param fbits the fractional bits of {@code input}
     * @param overflow the saturation flag
     * @return {@code 10 log10(input)}, scaled as 7,8
     */
    private static short fn10Log10(int input, short fbits, Flag overflow) {
        var log = FixedMath.log2(input);
        var integer = sub((short) (log >> 16), fbits, overflow);
        var fraction = (short) log;
        var ltmp = mpy3216(integer, fraction, (short) 24660, overflow);
        ltmp = lShrR(ltmp, (short) (5 + 1));
        return (short) ltmp;
    }

    /**
     * Normalizes a block to the largest value with some headroom ({@code block_norm}).
     *
     * @param in the input
     * @param inOffset the first input sample
     * @param out the output
     * @param length the number of samples
     * @param headroom the bits of headroom left
     * @param overflow the saturation flag
     * @return the normalization shift
     */
    private static short blockNorm(short[] in, int inOffset, short[] out, int length, short headroom,
                                   Flag overflow) {
        var max = abs(in[inOffset]);
        for (var i = 1; i < length; i++) {
            var adata = abs(in[inOffset + i]);
            if (adata > max) {
                max = adata;
            }
        }
        short scnt;
        if (max != 0) {
            scnt = sub(normS(max), headroom, overflow);
            for (var i = 0; i < length; i++) {
                out[i] = shl(in[inOffset + i], scnt);
            }
        } else {
            scnt = sub((short) 16, headroom, overflow);
            java.util.Arrays.fill(out, 0, length, (short) 0);
        }
        return scnt;
    }

    /**
     * Decides whether a half frame holds speech ({@code vad2}).
     *
     * @param in the speech buffer
     * @param inOffset the first of the 80 samples
     * @param overflow the saturation flag
     * @return 1 for speech, 0 otherwise
     */
    private short vad2(short[] in, int inOffset, Flag overflow) {
        lframeCnt = lAdd(lframeCnt, 1, overflow);
        var normbShift = blockNorm(in, inOffset, inputBuffer, FRM_LEN, FFT_HEADROOM, overflow);
        java.util.Arrays.fill(dataBuffer, 0, DELAY, (short) 0);
        preEmpMem = shrR(preEmpMem, sub(lastNormbShift, normbShift, overflow), overflow);
        lastNormbShift = normbShift;
        dataBuffer[DELAY] = add(inputBuffer[0], mult(PRE_EMP_FAC, preEmpMem, overflow), overflow);
        for (int i = DELAY + 1, j = 1; i < DELAY + FRM_LEN; i++, j++) {
            dataBuffer[i] = add(inputBuffer[j], mult(PRE_EMP_FAC, inputBuffer[j - 1], overflow), overflow);
        }
        preEmpMem = inputBuffer[FRM_LEN - 1];
        java.util.Arrays.fill(dataBuffer, DELAY + FRM_LEN, FFT_LEN, (short) 0);
        realFft(dataBuffer, overflow);

        var stateChange = false;
        if (shiftState == 0) {
            if (normbShift <= -FFT_HEADROOM + 2) {
                stateChange = true;
                shiftState = 1;
            }
        } else if (normbShift >= -FFT_HEADROOM + 5) {
            stateChange = true;
            shiftState = 0;
        }
        if (stateChange) {
            for (var i = 0; i < NUM_CHAN; i++) {
                lchEnrg[i] = lShr(lchEnrg[i], STATE_CHANGE_SHIFT_R[shiftState]);
            }
        }

        short alpha;
        short oneMinusAlpha;
        if (lframeCnt == 1) {
            alpha = 32767;
            oneMinusAlpha = 0;
        } else {
            alpha = CEE_SM_FAC;
            oneMinusAlpha = ONE_MINUS_CEE_SM_FAC;
        }
        for (var i = 0; i < NUM_CHAN; i++) {
            var lenrg = 0;
            var j1 = CH_TBL[2 * i];
            var j2 = CH_TBL[2 * i + 1];
            for (var j = j1; j <= j2; j++) {
                lenrg = lMac(lenrg, dataBuffer[2 * j], dataBuffer[2 * j], overflow);
                lenrg = lMac(lenrg, dataBuffer[2 * j + 1], dataBuffer[2 * j + 1], overflow);
            }
            var tmp = shl(normbShift, (short) 1);
            tmp = sub(tmp, ENRG_NORM_SHIFT[shiftState], overflow);
            lenrg = lShrR(lenrg, tmp);
            tmp = mult(alpha, CH_TBL_SH[i], overflow);
            var ltmp = mpy3216(lExtractHi(lenrg), lExtractLo(lenrg), tmp, overflow);
            var ltmp1 = mpy3216(lExtractHi(lchEnrg[i]), lExtractLo(lchEnrg[i]), oneMinusAlpha, overflow);
            lchEnrg[i] = lAdd(ltmp, ltmp1, overflow);
            if (lchEnrg[i] < MIN_CHAN_ENRG[shiftState]) {
                lchEnrg[i] = MIN_CHAN_ENRG[shiftState];
            }
        }

        var ltce = 0;
        for (var i = 0; i < NUM_CHAN; i++) {
            ltce = lAdd(ltce, lchEnrg[i], overflow);
        }
        var lpeak = 0;
        for (var i = 2; i < NUM_CHAN; i++) {
            if (lSub(lchEnrg[i], lpeak, overflow) > 0) {
                lpeak = lchEnrg[i];
            }
        }
        var ltmpPeak = mpy3216(lExtractHi(ltce), lExtractLo(ltce), (short) 20480, overflow);
        var p2aFlag = lSub(lpeak, ltmpPeak, overflow) > 0;

        if (lframeCnt <= 4) {
            if (p2aFlag) {
                java.util.Arrays.fill(lchNoise, INE_NOISE_0);
            } else {
                for (var i = 0; i < NUM_CHAN; i++) {
                    if (lchEnrg[i] < INE_NOISE[shiftState]) {
                        lchNoise[i] = INE_NOISE_0;
                    } else if (shiftState == 1) {
                        lchNoise[i] = lShr(lchEnrg[i], STATE_CHANGE_SHIFT_R[0]);
                    } else {
                        lchNoise[i] = lchEnrg[i];
                    }
                }
            }
        }

        short vmSum = 0;
        for (var i = 0; i < NUM_CHAN; i++) {
            chEnrgDb[i] = fn10Log10(lchEnrg[i], FBITS[shiftState], overflow);
            var chNoiseDb = fn10Log10(lchNoise[i], FRACTIONAL_BITS_0, overflow);
            chSnr[i] = sub(chEnrgDb[i], chNoiseDb, overflow);
            var tmp = mult((short) 21845, chSnr[i], overflow);
            var chSnrq = shrR(tmp, (short) 6, overflow);
            int j;
            if (chSnrq < 89) {
                j = chSnrq > 0 ? chSnrq : 0;
            } else {
                j = 89;
            }
            vmSum = add(vmSum, VM_TBL[j], overflow);
        }

        short xt;
        if (lframeCnt <= 4 || fupdateFlag) {
            short tceDb = 14320;
            negSnrVar = 0;
            negSnrBias = 0;
            var ltne = 0;
            for (var i = 0; i < NUM_CHAN; i++) {
                ltne = lAdd(ltne, lchNoise[i], overflow);
            }
            var tneDb = fn10Log10(ltne, FRACTIONAL_BITS_0, overflow);
            xt = sub(tceDb, tneDb, overflow);
            tsnr = xt;
        } else {
            var ltmp1 = 0;
            for (var i = 0; i < NUM_CHAN; i++) {
                var ltmp2 = lMult(chSnr[i], (short) 10885, overflow);
                ltmp2 = lShr(ltmp2, (short) 8);
                var hi1 = add(lExtractHi(ltmp2), (short) 3, overflow);
                ltmp2 = FixedMath.pow2(hi1, lExtractLo(ltmp2), overflow);
                ltmp1 = lAdd(ltmp1, ltmp2, overflow);
            }
            xt = fn10Log10(ltmp1, (short) (4 + 3), overflow);
            if (xt > tsnr) {
                ltmp1 = lMult((short) 29491, tsnr, overflow);
                var ltmp2 = lMult((short) 3277, xt, overflow);
                ltmp1 = lAdd(ltmp1, ltmp2, overflow);
                tsnr = round(ltmp1, overflow);
            } else {
                var tmp = mult((short) 20480, tsnr, overflow);
                tmp = sub(xt, tmp, overflow);
                if (tmp > 0) {
                    ltmp1 = lMult((short) 32702, tsnr, overflow);
                    var ltmp2 = lMult((short) 66, xt, overflow);
                    ltmp1 = lAdd(ltmp1, ltmp2, overflow);
                    tsnr = round(ltmp1, overflow);
                }
            }
        }

        var tsnrq = shr(mult(tsnr, (short) 10923, overflow), (short) 8, overflow);
        if (tsnrq > 19) {
            tsnrq = 19;
        } else if (tsnrq < 0) {
            tsnrq = 0;
        }

        if (xt < 0) {
            var ltmp1 = lMult(xt, xt, overflow);
            ltmp1 = lShl(ltmp1, (short) 7);
            var tmp = round(ltmp1, overflow);
            ltmp1 = lMult((short) 32440, negSnrVar, overflow);
            var ltmp2 = lMult((short) 328, tmp, overflow);
            ltmp1 = lAdd(ltmp1, ltmp2, overflow);
            negSnrVar = round(ltmp1, overflow);
            if (negSnrVar > 1024) {
                negSnrVar = 1024;
            }
            tmp = sub(negSnrVar, (short) 166, overflow);
            tmp = shl(tmp, (short) 4);
            tmp = multR(tmp, (short) 24576, overflow);
            negSnrBias = tmp < 0 ? 0 : shr(tmp, (short) 8, overflow);
        }

        short ivad;
        var threshold = add(VM_THRESHOLD_TABLE[tsnrq], negSnrBias, overflow);
        if (vmSum > threshold) {
            ivad = 1;
            burstcount = add(burstcount, (short) 1, overflow);
            if (burstcount > BURSTCOUNT_TABLE[tsnrq]) {
                hangover = HANGOVER_TABLE[tsnrq];
            }
        } else {
            burstcount = 0;
            hangover = sub(hangover, (short) 1, overflow);
            if (hangover <= 0) {
                ivad = 0;
                hangover = 0;
            } else {
                ivad = 1;
            }
        }

        short chEnrgDev = 0;
        if (lframeCnt == 1) {
            System.arraycopy(chEnrgDb, 0, chEnrgLongDb, 0, NUM_CHAN);
        } else {
            for (var i = 0; i < NUM_CHAN; i++) {
                var tmp = abs(sub(chEnrgLongDb[i], chEnrgDb[i], overflow));
                chEnrgDev = add(chEnrgDev, tmp, overflow);
            }
        }

        var difference = sub(tsnr, xt, overflow);
        if (difference <= 0 || tsnr <= 0) {
            alpha = HIGH_ALPHA;
            oneMinusAlpha = (short) (32768 - HIGH_ALPHA);
        } else if (difference > tsnr) {
            alpha = LOW_ALPHA;
            oneMinusAlpha = (short) (32768 - LOW_ALPHA);
        } else {
            var tmp = divS(difference, tsnr);
            tmp = mult(ALPHA_RANGE, tmp, overflow);
            alpha = sub(HIGH_ALPHA, tmp, overflow);
            oneMinusAlpha = sub((short) 32767, alpha, overflow);
        }
        for (var i = 0; i < NUM_CHAN; i++) {
            var ltmp1 = lMult(oneMinusAlpha, chEnrgDb[i], overflow);
            var ltmp2 = lMult(alpha, chEnrgLongDb[i], overflow);
            ltmp1 = lAdd(ltmp1, ltmp2, overflow);
            chEnrgLongDb[i] = round(ltmp1, overflow);
        }

        var updateFlag = false;
        fupdateFlag = false;
        if (vmSum <= UPDATE_THLD) {
            if (burstcount == 0) {
                updateFlag = true;
                updateCnt = 0;
            }
        } else if (lSub(ltce, NOISE_FLOOR_CHAN[shiftState], overflow) > 0) {
            if (chEnrgDev < DEV_THLD && !p2aFlag && !ltpFlag) {
                updateCnt = add(updateCnt, (short) 1, overflow);
                if (updateCnt >= UPDATE_CNT_THLD) {
                    updateFlag = true;
                    fupdateFlag = true;
                }
            }
        }
        if (updateCnt == lastUpdateCnt) {
            hysterCnt = add(hysterCnt, (short) 1, overflow);
        } else {
            hysterCnt = 0;
        }
        lastUpdateCnt = updateCnt;
        if (hysterCnt > HYSTER_CNT_THLD) {
            updateCnt = 0;
        }

        if (updateFlag) {
            var shift = shiftState == 1 ? STATE_CHANGE_SHIFT_R[0] : (short) 0;
            for (var i = 0; i < NUM_CHAN; i++) {
                var ltmp1 = lShr(lchEnrg[i], shift);
                var ltmp = mpy3216(lExtractHi(ltmp1), lExtractLo(ltmp1), CNE_SM_FAC, overflow);
                ltmp1 = mpy3216(lExtractHi(lchNoise[i]), lExtractLo(lchNoise[i]), ONE_MINUS_CNE_SM_FAC, overflow);
                lchNoise[i] = lAdd(ltmp, ltmp1, overflow);
                if (lchNoise[i] <= MIN_NOISE_ENRG_0) {
                    lchNoise[i] = MIN_NOISE_ENRG_0;
                }
            }
        }
        return ivad;
    }

    /**
     * Computes the 64-point complex FFT of 64 interleaved complex values in place, halving at
     * every stage ({@code c_fft}).
     *
     * @param farray the values, real and imaginary interleaved
     * @param overflow the saturation flag
     */
    private static void complexFft(short[] farray, Flag overflow) {
        for (int i = 0, j = 0; i < FFT_LEN - 2; i += 2) {
            if (j > i) {
                var tmp = farray[i];
                farray[i] = farray[j];
                farray[j] = tmp;
                tmp = farray[i + 1];
                farray[i + 1] = farray[j + 1];
                farray[j + 1] = tmp;
            }
            var k = FFT_LEN / 2;
            while (j >= k) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }
        for (var stage = 0; stage < 6; stage++) {
            var jj = 2 << stage;
            var kk = jj << 1;
            var ii2 = (FFT_LEN >> (stage + 1)) << 1;
            var ji = 0;
            for (var j = 0; j < jj; j += 2) {
                for (var k = j; k < FFT_LEN; k += kk) {
                    var kj = k + jj;
                    var real = lMult(farray[kj], PHS_TBL[ji], overflow);
                    real = lMsu(real, farray[kj + 1], PHS_TBL[ji + 1], overflow);
                    var imag = lMult(farray[kj + 1], PHS_TBL[ji], overflow);
                    imag = lMac(imag, farray[kj], PHS_TBL[ji + 1], overflow);
                    var tmp1 = round(real, overflow);
                    var tmp2 = round(imag, overflow);
                    var tmp = sub(farray[k], tmp1, overflow);
                    farray[kj] = shr(tmp, (short) 1, overflow);
                    tmp = sub(farray[k + 1], tmp2, overflow);
                    farray[kj + 1] = shr(tmp, (short) 1, overflow);
                    tmp = add(farray[k], tmp1, overflow);
                    farray[k] = shr(tmp, (short) 1, overflow);
                    tmp = add(farray[k + 1], tmp2, overflow);
                    farray[k + 1] = shr(tmp, (short) 1, overflow);
                }
                ji += ii2;
            }
        }
    }

    /**
     * Computes the FFT of 128 real values in place ({@code r_fft}): the DC term and the Nyquist
     * term in the first two entries, then the real and imaginary parts of bins 1 to 63.
     *
     * @param farray the values
     * @param overflow the saturation flag
     */
    private static void realFft(short[] farray, Flag overflow) {
        complexFft(farray, overflow);
        var ftmp1Real = farray[0];
        var ftmp2Real = farray[1];
        farray[0] = add(ftmp1Real, ftmp2Real, overflow);
        farray[1] = sub(ftmp1Real, ftmp2Real, overflow);
        for (int i = 2, j = FFT_LEN - 2; i <= FFT_LEN / 2; i += 2, j = FFT_LEN - i) {
            ftmp1Real = add(farray[i], farray[j], overflow);
            var ftmp1Imag = sub(farray[i + 1], farray[j + 1], overflow);
            ftmp2Real = add(farray[i + 1], farray[j + 1], overflow);
            var ftmp2Imag = sub(farray[j], farray[i], overflow);
            var lftmp1Real = ftmp1Real << 16;
            var lftmp1Imag = ftmp1Imag << 16;
            var ltmp1 = lMac(lftmp1Real, ftmp2Real, PHS_TBL[i], overflow);
            ltmp1 = lMsu(ltmp1, ftmp2Imag, PHS_TBL[i + 1], overflow);
            farray[i] = round(lShr(ltmp1, (short) 1), overflow);
            ltmp1 = lMac(lftmp1Imag, ftmp2Imag, PHS_TBL[i], overflow);
            ltmp1 = lMac(ltmp1, ftmp2Real, PHS_TBL[i + 1], overflow);
            farray[i + 1] = round(lShr(ltmp1, (short) 1), overflow);
            ltmp1 = lMac(lftmp1Real, ftmp2Real, PHS_TBL[j], overflow);
            ltmp1 = lMac(ltmp1, ftmp2Imag, PHS_TBL[j + 1], overflow);
            farray[j] = round(lShr(ltmp1, (short) 1), overflow);
            ltmp1 = lNegate(lftmp1Imag);
            ltmp1 = lMsu(ltmp1, ftmp2Imag, PHS_TBL[j], overflow);
            ltmp1 = lMac(ltmp1, ftmp2Real, PHS_TBL[j + 1], overflow);
            farray[j + 1] = round(lShr(ltmp1, (short) 1), overflow);
        }
    }
}
