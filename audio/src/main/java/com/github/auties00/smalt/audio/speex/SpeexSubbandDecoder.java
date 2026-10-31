/* Ports libspeex 1.2.1 (libspeex/sb_celp.c). Copyright 2002-2008 Xiph.org Foundation, Jean-Marc Valin; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_p14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_q15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_32_p15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pdiv32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pshr32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.q16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.saturate;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.shl32;

/**
 * Decodes the high band of the wideband (16 kHz) or ultra-wideband (32 kHz) layer of Speex over the layer below it
 * and joins the two bands with the quadrature mirror filter.
 *
 * <p>Instances are not thread-safe.
 */
final class SpeexSubbandDecoder implements SpeexFrameDecoder {
    private static final int QMF_ORDER = 64;
    private static final int LPC_SIZE = 8;
    private static final int LSP_MARGIN = 16;
    private static final int SB_SUBMODE_BITS = 3;

    private static final short[] GC_QUANT_BOUND = {125, 164, 215, 282, 370, 484, 635, 832, 1090, 1428, 1871, 2452,
            3213, 4210, 5516, 7228};

    private static final short[] H0 = {2, -7, -7, 18, 15, -39, -25, 75, 35, -130, -41, 212, 38, -327, -17, 483, -32,
            -689, 124, 956, -283, -1307, 543, 1780, -973, -2467, 1733, 3633, -3339, -6409, 9059, 30153, 30153, 9059,
            -6409, -3339, 3633, 1733, -2467, -973, 1780, 543, -1307, -283, 956, 124, -689, -32, 483, -17, -327, 38,
            212, -41, -130, 35, 75, -25, -39, 15, 18, -7, -7, 2};

    private static final short Q_0_99 = q16(0.99f, 15);
    private static final short Q_0_9 = q16(.9f, 15);
    private static final short Q_0_125_11 = q16(.125f, 11);
    private static final short Q_0_87360 = q16(0.87360, 15);
    private static final short Q_1_4142_14 = q16(1.4142f, 14);
    private static final short Q_0_4 = q16(0.4f, 15);

    private final SpeexFrameDecoder low;

    private final SpeexModes.Submode[] submodes;

    private final int fullFrameSize;

    private final int frameSize;

    private final int subframeSize;

    private final int subframes;

    private final short foldingGain;

    private final short[] g0Mem = new short[QMF_ORDER];

    private final short[] g1Mem = new short[QMF_ORDER];

    private final short[] excBuf;

    private final short[] oldQlsp = new short[LPC_SIZE];

    private final short[] interpQlpc = new short[LPC_SIZE];

    private final int[] memSp = new int[2 * LPC_SIZE];

    private final int[] piGain;

    private final short[] excRms;

    private final int[] seed = {1000};

    private boolean first = true;

    private int submodeId;

    private short lastEnergy;

    private short[] innovSave;

    private int innovSaveOffset;

    private final int[] lowPiGain;

    private final short[] lowExcRms;

    private final short[] qlsp = new short[LPC_SIZE];

    private final short[] interpQlsp = new short[LPC_SIZE];

    private final short[] ak = new short[LPC_SIZE];

    private final int[] exc;

    private final int[] innov2;

    private final short[] xx1;

    private final short[] xx2;

    private final int[] indices = new int[8];

    private final int[] signs = new int[8];

    private final SpeexDsp.LpcScratch lpcScratch = new SpeexDsp.LpcScratch();

    /**
     * Constructs a sub-band decoder.
     *
     * @param low the decoder of the band below
     * @param ultra whether this is the ultra-wideband layer
     */
    SpeexSubbandDecoder(SpeexFrameDecoder low, boolean ultra) {
        this.low = low;
        this.frameSize = ultra ? 320 : 160;
        this.fullFrameSize = 2 * frameSize;
        this.subframeSize = ultra ? 80 : 40;
        this.subframes = frameSize / subframeSize;
        this.submodes = ultra ? SpeexModes.ULTRA_WIDEBAND : SpeexModes.WIDEBAND;
        this.submodeId = ultra ? 1 : 3;
        this.foldingGain = ultra ? q16(0.7f, 15) : q16(0.9f, 15);
        this.excBuf = new short[subframeSize];
        this.piGain = new int[subframes];
        this.excRms = new short[subframes];
        this.lowPiGain = new int[subframes];
        this.lowExcRms = new short[subframes];
        this.exc = new int[subframeSize];
        this.innov2 = new int[subframeSize];
        this.xx1 = new short[fullFrameSize / 2 + QMF_ORDER / 2];
        this.xx2 = new short[fullFrameSize / 2 + QMF_ORDER / 2];
        low.setWideband();
    }

    @Override
    public int frameSize() {
        return fullFrameSize;
    }

    @Override
    public void setWideband() {
        low.setWideband();
    }

    @Override
    public void setStereo(SpeexStereoDecoder stereo) {
        low.setStereo(stereo);
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
        System.arraycopy(excRms, 0, target, 0, subframes);
    }

    @Override
    public boolean dtx() {
        return low.dtx();
    }

    private void lost(short[] out, int offset, boolean dtx) {
        if (!dtx) {
            SpeexDsp.bandwidthExpand(Q_0_99, interpQlpc, interpQlpc, LPC_SIZE);
        }
        first = true;
        if (!dtx) {
            lastEnergy = (short) mult16_16_q15(Q_0_9, lastEnergy);
        }
        for (var i = 0; i < frameSize; i++) {
            out[offset + frameSize + i] = (short) SpeexDsp.random(lastEnergy, seed);
        }
        SpeexDsp.iir(out, offset + frameSize, interpQlpc, out, offset + frameSize, frameSize, LPC_SIZE, memSp, 0);
        SpeexDsp.qmfSynthesis(out, offset, out, offset + frameSize, H0, out, offset, fullFrameSize, QMF_ORDER, g0Mem,
                g1Mem, xx1, xx2);
    }

    @Override
    public int decode(SpeexBits bits, short[] out, int offset) {
        low.setInnovationSave(out, offset + frameSize);
        var ret = low.decode(bits, out, offset);
        var dtx = low.dtx();
        if (ret != 0) {
            return ret;
        }
        if (bits == null) {
            lost(out, offset, dtx);
            return 0;
        }
        var wideband = bits.remaining() > 0 ? bits.peek() : 0;
        if (wideband != 0) {
            bits.unsigned(1);
            submodeId = bits.unsigned(SB_SUBMODE_BITS);
        } else {
            submodeId = 0;
        }
        if (submodeId != 0 && submodes[submodeId] == null) {
            return -2;
        }
        var mode = submodes[submodeId];
        if (mode == null) {
            if (dtx) {
                lost(out, offset, true);
                return 0;
            }
            Arrays.fill(out, offset + frameSize, offset + 2 * frameSize, (short) 0);
            first = true;
            SpeexDsp.iir(out, offset + frameSize, interpQlpc, out, offset + frameSize, frameSize, LPC_SIZE, memSp, 0);
            SpeexDsp.qmfSynthesis(out, offset, out, offset + frameSize, H0, out, offset, fullFrameSize, QMF_ORDER,
                    g0Mem, g1Mem, xx1, xx2);
            return 0;
        }
        System.arraycopy(low.piGain(), 0, lowPiGain, 0, subframes);
        low.excitationRms(lowExcRms);
        SpeexDsp.lspUnquantHigh(qlsp, LPC_SIZE, bits);
        if (first) {
            System.arraycopy(qlsp, 0, oldQlsp, 0, LPC_SIZE);
        }
        var energySum = 0;
        for (var sub = 0; sub < subframes; sub++) {
            var subOffset = subframeSize * sub;
            var sp = offset + frameSize + subOffset;
            if (innovSave != null) {
                Arrays.fill(innovSave, innovSaveOffset + 2 * subOffset, innovSaveOffset + 2 * subOffset
                        + 2 * subframeSize, (short) 0);
            }
            SpeexDsp.lspInterpolate(oldQlsp, qlsp, interpQlsp, LPC_SIZE, sub, subframes, LSP_MARGIN);
            SpeexDsp.lspToLpc(interpQlsp, ak, LPC_SIZE, lpcScratch);
            var pi = SpeexDsp.LPC_SCALING;
            var rh = SpeexDsp.LPC_SCALING;
            for (var i = 0; i < LPC_SIZE; i += 2) {
                rh += ak[i + 1] - ak[i];
                pi += ak[i] + ak[i + 1];
            }
            piGain[sub] = pi;
            var rl = lowPiGain[sub];
            var filterRatio = (short) saturate(pdiv32(shl32(rl + 82, 7), 82 + rh), 32767);
            Arrays.fill(exc, 0);
            if (mode.innovation() == null) {
                var quant = bits.unsigned(5);
                var g = SpeexFixed.exp(mult16_16(Q_0_125_11, quant - 10));
                g = pdiv32(g, filterRatio);
                var lowInnov = offset + frameSize;
                for (var i = 0; i < subframeSize; i += 2) {
                    exc[i] = shl32(mult16_32_p15(mult16_16_q15(foldingGain, out[lowInnov + subOffset + i]),
                            shl32(g, 6)), SpeexDsp.SIG_SHIFT);
                    exc[i + 1] = -shl32(mult16_32_p15(mult16_16_q15(foldingGain, out[lowInnov + subOffset + i + 1]),
                            shl32(g, 6)), SpeexDsp.SIG_SHIFT);
                }
            } else {
                var qgc = bits.unsigned(4);
                var el = lowExcRms[sub];
                var gc = (short) mult16_16_q15(Q_0_87360, GC_QUANT_BOUND[qgc]);
                if (subframeSize == 80) {
                    gc = (short) mult16_16_p14(Q_1_4142_14, gc);
                }
                var scale = shl32(pdiv32(shl32(mult16_16(gc, el), 3), filterRatio), SpeexDsp.SIG_SHIFT - 3);
                SpeexDsp.splitCodebookUnquant(exc, 0, mode.innovation(), bits, indices, signs);
                SpeexDsp.signalMultiply(exc, 0, exc, 0, scale, subframeSize);
                if (mode.doubleCodebook()) {
                    Arrays.fill(innov2, 0);
                    SpeexDsp.splitCodebookUnquant(innov2, 0, mode.innovation(), bits, indices, signs);
                    SpeexDsp.signalMultiply(innov2, 0, innov2, 0, mult16_32_p15(Q_0_4, scale), subframeSize);
                    for (var i = 0; i < subframeSize; i++) {
                        exc[i] += innov2[i];
                    }
                }
            }
            if (innovSave != null) {
                for (var i = 0; i < subframeSize; i++) {
                    innovSave[innovSaveOffset + 2 * subOffset + 2 * i] = (short) pshr32(exc[i], SpeexDsp.SIG_SHIFT);
                }
            }
            SpeexDsp.iir(excBuf, 0, interpQlpc, out, sp, subframeSize, LPC_SIZE, memSp, 0);
            for (var i = 0; i < subframeSize; i++) {
                excBuf[i] = (short) pshr32(exc[i], SpeexDsp.SIG_SHIFT);
            }
            System.arraycopy(ak, 0, interpQlpc, 0, LPC_SIZE);
            excRms[sub] = (short) SpeexDsp.rms16(excBuf, 0, subframeSize);
            energySum += mult16_16(excRms[sub], excRms[sub]) / subframes;
        }
        lastEnergy = (short) SpeexFixed.sqrt(energySum);
        SpeexDsp.qmfSynthesis(out, offset, out, offset + frameSize, H0, out, offset, fullFrameSize, QMF_ORDER, g0Mem,
                g1Mem, xx1, xx2);
        System.arraycopy(qlsp, 0, oldQlsp, 0, LPC_SIZE);
        first = false;
        return 0;
    }
}
