/* Ports libspeex 1.2.1 (libspeex/nb_celp.c, libspeex/speex_callbacks.c). Copyright 2002-2008 Xiph.org Foundation,
   Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.speex.SpeexFixed.add16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_p14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_p15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_q14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_q15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_32_q14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_32_q15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pdiv32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pshr16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pshr32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.q16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.q32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.saturate;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.shl16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.shl32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.sub16;

/**
 * Decodes the narrowband (8 kHz) layer of Speex: one 160-sample frame of CELP per call, the vocoder mode, the
 * perceptual enhancer, packet loss concealment, discontinuous transmission, and the in-band requests that precede a
 * frame.
 *
 * <p>Instances are not thread-safe.
 */
final class SpeexNarrowbandDecoder implements SpeexFrameDecoder {
    static final int FRAME_SIZE = 160;
    static final int SUBFRAME_SIZE = 40;
    static final int SUBFRAMES = 4;
    static final int ORDER = 10;
    static final int PITCH_START = 17;
    static final int PITCH_END = 144;
    private static final int DECODE_BUFFER = FRAME_SIZE + 2 * PITCH_END + SUBFRAME_SIZE + 12;
    private static final int EXC = 2 * PITCH_END + SUBFRAME_SIZE + 6;
    private static final int LSP_MARGIN = 16;
    private static final int SB_SUBMODE_BITS = 3;

    private static final int[] OL_GAIN_TABLE = {18900, 25150, 33468, 44536, 59265, 78865, 104946, 139653, 185838,
            247297, 329081, 437913, 582736, 775454, 1031906, 1373169, 1827293, 2431601, 3235761, 4305867, 5729870,
            7624808, 10146425, 13501971, 17967238, 23909222, 31816294, 42338330, 56340132, 74972501, 99766822,
            132760927};

    private static final short[] EXC_GAIN_SCALE3 = {1002, 2680, 5086, 7016, 9108, 11781, 15380, 21740};

    private static final short[] EXC_GAIN_SCALE1 = {11546, 17224};

    private static final short[] ATTENUATION = {32767, 31483, 27923, 22861, 17278, 12055, 7764, 4616, 2533, 1283};

    private static final int[] WB_SKIP = {0, 36, 112, 192, 352, 0, 0, 0};

    private static final short Q_0_98 = q16(.98, 15);
    private static final short Q_0_93 = q16(0.93f, 15);
    private static final short Q_0_066667 = q16(0.066667, 15);
    private static final short Q_0_454545 = q16(0.454545f, 15);
    private static final short Q_0_6 = q16(.6, 15);
    private static final short Q_0_67 = q16(.67, 15);
    private static final short Q_1_5_14 = q16(1.5f, 14);
    private static final short Q_0_2_6 = q16(.2f, 6);
    private static final short Q_0_7 = q16(.7f, 15);
    private static final short Q_0_3 = q16(.3f, 15);
    private static final short Q_0_85_9 = q16(.85f, 9);
    private static final short Q_0_15_9 = q16(.15f, 9);
    private static final short Q_0_8 = q16(.8f, 15);
    private static final short Q_0_2 = q16(.2f, 15);

    private final short[] excBuf = new short[DECODE_BUFFER];

    private final short[] oldQlsp = new short[ORDER];

    private final short[] interpQlpc = new short[ORDER];

    private final int[] memSp = new int[ORDER];

    private final int[] memHp = new int[2];

    private final int[] piGain = new int[SUBFRAMES];

    private final short[] pitchGainBuffer = new short[3];

    private final int[] seed = {1000};

    private boolean first = true;

    private int countLost;

    private int lastPitch = 40;

    private short lastPitchGain;

    private int pitchGainIndex;

    private int submodeId = 5;

    private final boolean enhancement;

    private boolean wideband;

    private short vocM1;

    private int vocM2;

    private short vocMean;

    private int vocOffset;

    private boolean dtx;

    private short[] innovSave;

    private int innovSaveOffset;

    private SpeexStereoDecoder stereo;

    private final short[] qlsp = new short[ORDER];

    private final short[] interpQlsp = new short[ORDER];

    private final short[] ak = new short[ORDER];

    private final short[] lpc = new short[ORDER];

    private final int[] innov = new int[SUBFRAME_SIZE];

    private final int[] innov2 = new int[SUBFRAME_SIZE];

    private final int[] exc32 = new int[SUBFRAME_SIZE];

    private final short[] pitchGain = new short[3];

    private final short[] gainScratch = new short[3];

    private final int[] pitchResult = new int[1];

    private final int[] indices = new int[8];

    private final int[] signs = new int[8];

    private final SpeexDsp.LpcScratch lpcScratch = new SpeexDsp.LpcScratch();

    private final SpeexDsp.CombScratch combScratch = new SpeexDsp.CombScratch();

    /**
     * Constructs a narrowband decoder.
     *
     * @param enhancement whether the perceptual enhancer runs
     */
    SpeexNarrowbandDecoder(boolean enhancement) {
        this.enhancement = enhancement;
    }

    @Override
    public int frameSize() {
        return FRAME_SIZE;
    }

    @Override
    public void setWideband() {
        wideband = true;
    }

    @Override
    public void setStereo(SpeexStereoDecoder stereo) {
        this.stereo = stereo;
    }

    @Override
    public void setInnovationSave(short[] target, int offset) {
        innovSave = target;
        innovSaveOffset = offset;
    }

    @Override
    public int[] piGain() {
        return piGain;
    }

    @Override
    public void excitationRms(short[] target) {
        for (var i = 0; i < SUBFRAMES; i++) {
            target[i] = (short) SpeexDsp.rms16(excBuf, EXC + i * SUBFRAME_SIZE, SUBFRAME_SIZE);
        }
    }

    @Override
    public boolean dtx() {
        return dtx;
    }

    private void lost(short[] out, int outOffset) {
        var fact = countLost < 10 ? ATTENUATION[countLost] : 0;
        var gainMedian = SpeexDsp.median3(pitchGainBuffer[0], pitchGainBuffer[1], pitchGainBuffer[2]);
        if (gainMedian < lastPitchGain) {
            lastPitchGain = (short) gainMedian;
        }
        var pitchGainValue = (int) lastPitchGain;
        if (pitchGainValue > 54) {
            pitchGainValue = 54;
        }
        pitchGainValue = shl16(pitchGainValue, 9);
        pitchGainValue = (short) mult16_16_q15(fact, pitchGainValue);
        var innovGain = (short) SpeexDsp.rms16(excBuf, EXC, FRAME_SIZE);
        var noiseGain = (short) mult16_16_q15(innovGain, mult16_16_q15(fact,
                sub16(32767, mult16_16_q15(pitchGainValue, pitchGainValue))));
        System.arraycopy(excBuf, FRAME_SIZE, excBuf, 0, 2 * PITCH_END + SUBFRAME_SIZE + 12);
        var pitchValue = lastPitch + (SpeexDsp.random(1 + countLost, seed) >> SpeexDsp.SIG_SHIFT);
        if (pitchValue > PITCH_END) {
            pitchValue = PITCH_END;
        }
        if (pitchValue < PITCH_START) {
            pitchValue = PITCH_START;
        }
        for (var i = 0; i < FRAME_SIZE; i++) {
            excBuf[EXC + i] = (short) (mult16_16_q15(pitchGainValue, excBuf[EXC + i - pitchValue])
                    + SpeexDsp.random(noiseGain, seed));
        }
        SpeexDsp.bandwidthExpand(Q_0_98, interpQlpc, interpQlpc, ORDER);
        SpeexDsp.iir(excBuf, EXC - SUBFRAME_SIZE, interpQlpc, out, outOffset, FRAME_SIZE, ORDER, memSp, 0);
        SpeexDsp.highpass(out, outOffset, FRAME_SIZE, SpeexDsp.HIGHPASS_NARROWBAND | SpeexDsp.HIGHPASS_OUTPUT,
                memHp);
        first = false;
        countLost++;
        pitchGainBuffer[pitchGainIndex++] = (short) pshr16(pitchGainValue, 9);
        if (pitchGainIndex > 2) {
            pitchGainIndex = 0;
        }
    }

    /**
     * Handles a Speex in-band request ({@code speex_inband_handler}): the stereo request when a stereo decoder is
     * attached, and skips the others.
     */
    private void inband(SpeexBits bits) {
        var id = bits.unsigned(4);
        if (id == 9 && stereo != null) {
            stereo.request(bits);
            return;
        }
        int advance;
        if (id < 2) {
            advance = 1;
        } else if (id < 8) {
            advance = 4;
        } else if (id < 10) {
            advance = 8;
        } else if (id < 12) {
            advance = 16;
        } else if (id < 14) {
            advance = 32;
        } else {
            advance = 64;
        }
        bits.advance(advance);
    }

    @Override
    public int decode(SpeexBits bits, short[] out, int outOffset) {
        if (bits == null && dtx) {
            submodeId = 0;
        } else {
            if (bits == null) {
                lost(out, outOffset);
                return 0;
            }
            int m;
            do {
                if (bits.remaining() < 5) {
                    return -1;
                }
                var wb = bits.unsigned(1);
                if (wb != 0) {
                    var submode = bits.unsigned(SB_SUBMODE_BITS);
                    var advance = WB_SKIP[submode] - (SB_SUBMODE_BITS + 1);
                    bits.advance(advance);
                    if (bits.remaining() < 5) {
                        return -1;
                    }
                    wb = bits.unsigned(1);
                    if (wb != 0) {
                        submode = bits.unsigned(SB_SUBMODE_BITS);
                        advance = WB_SKIP[submode] - (SB_SUBMODE_BITS + 1);
                        bits.advance(advance);
                        wb = bits.unsigned(1);
                        if (wb != 0) {
                            return -2;
                        }
                    }
                }
                if (bits.remaining() < 4) {
                    return -1;
                }
                m = bits.unsigned(4);
                if (m == 15) {
                    return -1;
                } else if (m == 14) {
                    inband(bits);
                } else if (m == 13) {
                    var size = bits.unsigned(4);
                    bits.advance(5 + 8 * size);
                } else if (m > 8) {
                    return -2;
                }
            } while (m > 8);
            submodeId = m;
        }
        System.arraycopy(excBuf, FRAME_SIZE, excBuf, 0, 2 * PITCH_END + SUBFRAME_SIZE + 12);
        var mode = SpeexModes.NARROWBAND[submodeId];
        if (mode == null) {
            SpeexDsp.bandwidthExpand(Q_0_93, interpQlpc, lpc, ORDER);
            var innovGain = (short) SpeexDsp.rms16(excBuf, EXC, FRAME_SIZE);
            for (var i = 0; i < FRAME_SIZE; i++) {
                excBuf[EXC + i] = (short) SpeexDsp.random(innovGain, seed);
            }
            first = true;
            SpeexDsp.iir(excBuf, EXC, lpc, out, outOffset, FRAME_SIZE, ORDER, memSp, 0);
            countLost = 0;
            return 0;
        }
        switch (mode.lsp()) {
            case NB -> SpeexDsp.lspUnquantNb(qlsp, ORDER, bits);
            case LBR -> SpeexDsp.lspUnquantLbr(qlsp, ORDER, bits);
            default -> throw new MediaException.Malformed("Speex narrowband sub-mode with high band LSPs");
        }
        if (countLost != 0) {
            var lspDistance = 0;
            for (var i = 0; i < ORDER; i++) {
                lspDistance += Math.abs(oldQlsp[i] - qlsp[i]);
            }
            var fact = (short) (19661 >> (lspDistance >> (SpeexDsp.LSP_SHIFT + 2)));
            for (var i = 0; i < ORDER; i++) {
                memSp[i] = mult16_32_q15(fact, memSp[i]);
            }
        }
        if (first || countLost != 0) {
            System.arraycopy(qlsp, 0, oldQlsp, 0, ORDER);
        }
        var olPitch = 0;
        if (mode.lbrPitch() != -1) {
            olPitch = PITCH_START + bits.unsigned(7);
        }
        var olPitchCoef = 0;
        if (mode.forcedPitchGain()) {
            var quant = bits.unsigned(4);
            olPitchCoef = (short) mult16_16_p15(Q_0_066667, shl16(quant, SpeexDsp.GAIN_SHIFT));
        }
        var qe = bits.unsigned(5);
        var olGain = mult16_32_q15(28406, OL_GAIN_TABLE[qe]);
        if (submodeId == 1) {
            var extra = bits.unsigned(4);
            dtx = extra == 15;
        }
        if (submodeId > 1) {
            dtx = false;
        }
        var bestPitch = 40;
        var bestPitchGain = (short) 0;
        var pitchAverage = (short) 0;
        for (var sub = 0; sub < SUBFRAMES; sub++) {
            var offset = SUBFRAME_SIZE * sub;
            var exc = EXC + offset;
            Arrays.fill(excBuf, exc, exc + SUBFRAME_SIZE, (short) 0);
            int pitMin;
            int pitMax;
            if (mode.lbrPitch() != -1) {
                var margin = mode.lbrPitch();
                if (margin != 0) {
                    pitMin = Math.max(olPitch - margin + 1, PITCH_START);
                    pitMax = Math.min(olPitch + margin, PITCH_END);
                } else {
                    pitMin = pitMax = olPitch;
                }
            } else {
                pitMin = PITCH_START;
                pitMax = PITCH_END;
            }
            if (mode.ltp() == null) {
                SpeexDsp.forcedPitchUnquant(excBuf, exc, exc32, pitMin, olPitchCoef, SUBFRAME_SIZE, pitchResult,
                        pitchGain);
            } else {
                SpeexDsp.pitchUnquant3Tap(excBuf, exc, exc32, pitMin, mode.ltp(), SUBFRAME_SIZE, pitchResult,
                        pitchGain, bits, countLost, offset, lastPitchGain, gainScratch);
            }
            var pitch = pitchResult[0];
            SpeexDsp.sanitize(exc32, 0, -q32(32000, SpeexDsp.SIG_SHIFT - 1), q32(32000, SpeexDsp.SIG_SHIFT - 1),
                    SUBFRAME_SIZE);
            var tmp = (short) SpeexDsp.gain3TapTo1Tap(pitchGain);
            pitchAverage = (short) (pitchAverage + tmp);
            if ((tmp > bestPitchGain && Math.abs(2 * bestPitch - pitch) >= 3 && Math.abs(3 * bestPitch - pitch) >= 4
                    && Math.abs(4 * bestPitch - pitch) >= 5)
                    || (tmp > mult16_16_q15(Q_0_6, bestPitchGain) && (Math.abs(bestPitch - 2 * pitch) < 3
                    || Math.abs(bestPitch - 3 * pitch) < 4 || Math.abs(bestPitch - 4 * pitch) < 5))
                    || (mult16_16_q15(Q_0_67, tmp) > bestPitchGain && (Math.abs(2 * bestPitch - pitch) < 3
                    || Math.abs(3 * bestPitch - pitch) < 4 || Math.abs(4 * bestPitch - pitch) < 5))) {
                bestPitch = pitch;
                if (tmp > bestPitchGain) {
                    bestPitchGain = tmp;
                }
            }
            Arrays.fill(innov, 0);
            int energy;
            if (mode.subframeGain() == 3) {
                var q = bits.unsigned(3);
                energy = mult16_32_q14(EXC_GAIN_SCALE3[q], olGain);
            } else if (mode.subframeGain() == 1) {
                var q = bits.unsigned(1);
                energy = mult16_32_q14(EXC_GAIN_SCALE1[q], olGain);
            } else {
                energy = olGain;
            }
            if (mode.innovation() == null) {
                SpeexDsp.noiseCodebookUnquant(innov, 0, SUBFRAME_SIZE, seed);
            } else {
                SpeexDsp.splitCodebookUnquant(innov, 0, mode.innovation(), bits, indices, signs);
            }
            SpeexDsp.signalMultiply(innov, 0, innov, 0, energy, SUBFRAME_SIZE);
            if (mode.doubleCodebook()) {
                Arrays.fill(innov2, 0);
                SpeexDsp.splitCodebookUnquant(innov2, 0, mode.innovation(), bits, indices, signs);
                SpeexDsp.signalMultiply(innov2, 0, innov2, 0, mult16_32_q15(Q_0_454545, energy), SUBFRAME_SIZE);
                for (var i = 0; i < SUBFRAME_SIZE; i++) {
                    innov[i] += innov2[i];
                }
            }
            for (var i = 0; i < SUBFRAME_SIZE; i++) {
                excBuf[exc + i] = (short) saturate(pshr32(shl32(exc32[i], 1) + innov[i], SpeexDsp.SIG_SHIFT), 32767);
            }
            if (innovSave != null) {
                for (var i = 0; i < SUBFRAME_SIZE; i++) {
                    innovSave[innovSaveOffset + offset + i] = (short) pshr32(innov[i], SpeexDsp.SIG_SHIFT);
                }
            }
            if (submodeId == 1) {
                var g = (short) olPitchCoef;
                g = (short) mult16_16_p14(Q_1_5_14, g - Q_0_2_6);
                if (g < 0) {
                    g = 0;
                }
                if (g > SpeexDsp.GAIN_SCALING) {
                    g = SpeexDsp.GAIN_SCALING;
                }
                Arrays.fill(excBuf, exc, exc + SUBFRAME_SIZE, (short) 0);
                while (vocOffset < SUBFRAME_SIZE) {
                    if (vocOffset >= 0) {
                        excBuf[exc + vocOffset] = (short) mult16_16(SpeexFixed.sqrt(mult16_16_16(2, olPitch)),
                                (short) pshr32(mult16_16(g, pshr32(olGain, SpeexDsp.SIG_SHIFT)), 6));
                    }
                    vocOffset += olPitch;
                }
                vocOffset -= SUBFRAME_SIZE;
                for (var i = 0; i < SUBFRAME_SIZE; i++) {
                    var exci = excBuf[exc + i];
                    excBuf[exc + i] = (short) add16(add16(mult16_16_q15(Q_0_7, excBuf[exc + i]),
                                    mult16_16_q15(Q_0_3, vocM1)),
                            sub16(mult16_16_q15(32767 - mult16_16_16(Q_0_85_9, g),
                                            (short) pshr32(innov[i], SpeexDsp.SIG_SHIFT)),
                                    mult16_16_q15(mult16_16_16(Q_0_15_9, g), (short) pshr32(vocM2, SpeexDsp.SIG_SHIFT))));
                    vocM1 = exci;
                    vocM2 = innov[i];
                    vocMean = (short) pshr32(mult16_16(Q_0_8, vocMean) + mult16_16(Q_0_2, excBuf[exc + i]), 15);
                    excBuf[exc + i] = (short) (excBuf[exc + i] - vocMean);
                }
            }
        }
        if (enhancement && mode.combGain() > 0 && countLost == 0) {
            SpeexDsp.multicomb(excBuf, EXC - SUBFRAME_SIZE, out, outOffset, 2 * SUBFRAME_SIZE, bestPitch, 40,
                    mode.combGain(), combScratch);
            SpeexDsp.multicomb(excBuf, EXC + SUBFRAME_SIZE, out, outOffset + 2 * SUBFRAME_SIZE, 2 * SUBFRAME_SIZE,
                    bestPitch, 40, mode.combGain(), combScratch);
        } else {
            System.arraycopy(excBuf, EXC - SUBFRAME_SIZE, out, outOffset, FRAME_SIZE);
        }
        if (countLost != 0) {
            var excEnergy = (short) SpeexDsp.rms16(excBuf, EXC, FRAME_SIZE);
            var gain32 = pdiv32(olGain, add16(excEnergy, 1));
            if (gain32 > 32767) {
                gain32 = 32767;
            }
            var gain = (short) gain32;
            for (var i = 0; i < FRAME_SIZE; i++) {
                excBuf[EXC + i] = (short) mult16_16_q14(gain, excBuf[EXC + i]);
                out[outOffset + i] = excBuf[EXC + i - SUBFRAME_SIZE];
            }
        }
        for (var sub = 0; sub < SUBFRAMES; sub++) {
            var offset = SUBFRAME_SIZE * sub;
            SpeexDsp.lspInterpolate(oldQlsp, qlsp, interpQlsp, ORDER, sub, SUBFRAMES, LSP_MARGIN);
            SpeexDsp.lspToLpc(interpQlsp, ak, ORDER, lpcScratch);
            var pi = SpeexDsp.LPC_SCALING;
            for (var i = 0; i < ORDER; i += 2) {
                pi += ak[i + 1] - ak[i];
            }
            piGain[sub] = pi;
            SpeexDsp.iir(out, outOffset + offset, interpQlpc, out, outOffset + offset, SUBFRAME_SIZE, ORDER, memSp, 0);
            System.arraycopy(ak, 0, interpQlpc, 0, ORDER);
        }
        SpeexDsp.highpass(out, outOffset, FRAME_SIZE,
                (wideband ? SpeexDsp.HIGHPASS_WIDEBAND : SpeexDsp.HIGHPASS_NARROWBAND) | SpeexDsp.HIGHPASS_OUTPUT,
                memHp);
        System.arraycopy(qlsp, 0, oldQlsp, 0, ORDER);
        first = false;
        countLost = 0;
        lastPitch = bestPitch;
        lastPitchGain = (short) pshr16(pitchAverage, 2);
        pitchGainBuffer[pitchGainIndex++] = lastPitchGain;
        if (pitchGainIndex > 2) {
            pitchGainIndex = 0;
        }
        return 0;
    }
}
