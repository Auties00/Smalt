/* Ports opencore-amr 0.1.6 (cod_amr.cpp, pre_big.cpp, spreproc.cpp, spstproc.cpp, pre_proc.cpp, sp_enc.cpp).
   Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME_BY2;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_INTERPOL;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_NEXT;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_TOTAL;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_WINDOW;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MAX_PRM_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MAX;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.SHARPMAX;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.SHARPMIN;

/**
 * Encodes one 20 millisecond frame of the AMR-NB encoder into its parameters ({@code cod_amr}),
 * after the high-pass pre-processing ({@code Pre_Process}): voice activity and discontinuous
 * transmission, LPC analysis and LSP quantization, open-loop pitch estimation, and per subframe
 * the target computation, adaptive and algebraic codebook searches, gain quantization and the
 * filter memory updates.
 */
final class EncoderCore {

    /**
     * The scratch of the integer filters.
     */
    private final int[] work = new int[96];
    /**
     * The perceptual weighting factors of the numerator below 10.2 kbit/s, from
     * {@code cod_amr.cpp}.
     */
    private static final short[] GAMMA1 = {30802, 28954, 27217, 25584, 24049, 22606, 21250, 19975, 18777, 17650};

    /**
     * The perceptual weighting factors of the numerator at 10.2 and 12.2 kbit/s, from
     * {@code cod_amr.cpp}.
     */
    private static final short[] GAMMA1_12K2 = {29491, 26542, 23888, 21499, 19349, 17414, 15672, 14105, 12694, 11425};

    /**
     * The perceptual weighting factors of the denominator, from {@code cod_amr.cpp}.
     */
    private static final short[] GAMMA2 = {19661, 11797, 7078, 4247, 2548, 1529, 917, 550, 330, 198};

    /**
     * The index of the new frame in {@link #oldSpeech}.
     */
    private static final int NEW_SPEECH = L_TOTAL - L_FRAME;

    /**
     * The index of the coded frame in {@link #oldSpeech}, which trails the input by the
     * look-ahead.
     */
    private static final int SPEECH = NEW_SPEECH - L_NEXT;

    /**
     * The index of the LPC analysis window in {@link #oldSpeech}.
     */
    private static final int P_WINDOW = L_TOTAL - L_WINDOW;

    /**
     * The index of the 12.2 kbit/s LPC analysis window in {@link #oldSpeech}.
     */
    private static final int P_WINDOW_12K2 = P_WINDOW - L_NEXT;

    /**
     * The index of the weighted speech of the frame in {@link #oldWsp}.
     */
    private static final int WSP = PIT_MAX;

    /**
     * The index of the excitation of the frame in {@link #oldExc}.
     */
    private static final int EXC = PIT_MAX + L_INTERPOL;

    /**
     * The index of the zero vector in {@link #aiZero}.
     */
    private static final int ZERO = MP1;

    /**
     * The index of the impulse response in {@link #hvec}.
     */
    private static final int H1 = L_SUBFR;

    /**
     * The index of the error signal in {@link #memErr}.
     */
    private static final int ERROR = M;

    /**
     * Whether discontinuous transmission is on.
     */
    private final boolean dtx;

    /**
     * The saturation flag of the encoder.
     */
    private final Flag overflow = new Flag();

    /**
     * The speech buffer: past samples, the frame and the look-ahead.
     */
    private final short[] oldSpeech = new short[L_TOTAL];

    /**
     * The weighted speech buffer with its pitch history.
     */
    private final short[] oldWsp = new short[L_FRAME + PIT_MAX];

    /**
     * The lag history of the 10.2 kbit/s open-loop search.
     */
    private final short[] oldLags = new short[5];

    /**
     * The open-loop gain flags of the two half frames.
     */
    private final short[] olGainFlg = new short[2];

    /**
     * The excitation buffer with its pitch history.
     */
    private final short[] oldExc = new short[L_FRAME + PIT_MAX + L_INTERPOL];

    /**
     * The weighted filter coefficients followed by the zero vector used to compute the impulse
     * response.
     */
    private final short[] aiZero = new short[L_SUBFR + MP1];

    /**
     * The impulse response, preceded by 40 zeros the codebook searches read.
     */
    private final short[] hvec = new short[L_SUBFR * 2];

    /**
     * The synthesis filter memory.
     */
    private final short[] memSyn = new short[M];

    /**
     * The weighting filter memory of the target computation.
     */
    private final short[] memW0 = new short[M];

    /**
     * The weighting filter memory of the weighted speech.
     */
    private final short[] memW = new short[M];

    /**
     * The error filter memory followed by the error signal.
     */
    private final short[] memErr = new short[M + L_SUBFR];

    /**
     * The pitch sharpening gain.
     */
    private short sharp;

    /**
     * The pre-processing filter state: y2 high and low, y1 high and low, x0 and x1.
     */
    private short y2Hi;

    /**
     * The low part of the second past output of the pre-processing filter.
     */
    private short y2Lo;

    /**
     * The high part of the first past output of the pre-processing filter.
     */
    private short y1Hi;

    /**
     * The low part of the first past output of the pre-processing filter.
     */
    private short y1Lo;

    /**
     * The first past input of the pre-processing filter.
     */
    private short x0;

    /**
     * The second past input of the pre-processing filter.
     */
    private short x1;

    /**
     * The LPC analysis.
     */
    private final LpcAnalysis lpcSt = new LpcAnalysis();

    /**
     * The LSP computation and quantization.
     */
    private final Lsp lspSt = new Lsp();

    /**
     * The adaptive codebook search.
     */
    private final ClosedLoopPitch clLtpSt = new ClosedLoopPitch();

    /**
     * The gain quantization.
     */
    private final GainQuantizer gainQuantSt = new GainQuantizer();

    /**
     * The open-loop pitch estimation.
     */
    private final OpenLoopPitch pitchOlSt = new OpenLoopPitch();

    /**
     * The tone stability detector.
     */
    private final ToneStability tonStabSt = new ToneStability();

    /**
     * The voice activity detector.
     */
    private final Vad1 vadSt = new Vad1();

    /**
     * The discontinuous transmission state.
     */
    private final DtxEncoder dtxEncSt = new DtxEncoder();

    /**
     * The algebraic codebook search.
     */
    private final FixedCodebook codebook = new FixedCodebook();

    /**
     * The unquantized filters of the four subframes.
     */
    private final short[] aT = new short[MP1 * 4];

    /**
     * The quantized filters of the four subframes.
     */
    private final short[] aqT = new short[MP1 * 4];

    /**
     * The unquantized LSPs of the frame.
     */
    private final short[] lspNew = new short[M];

    /**
     * The target.
     */
    private final short[] xn = new short[L_SUBFR];

    /**
     * The target of the algebraic codebook search.
     */
    private final short[] xn2 = new short[L_SUBFR];

    /**
     * The codevector.
     */
    private final short[] code = new short[L_SUBFR];

    /**
     * The filtered adaptive codevector.
     */
    private final short[] y1 = new short[L_SUBFR];

    /**
     * The filtered codevector.
     */
    private final short[] y2 = new short[L_SUBFR];

    /**
     * The correlations of the pitch gain.
     */
    private final short[] gCoeff = new short[6];

    /**
     * The LP residual.
     */
    private final short[] res = new short[L_SUBFR];

    /**
     * The long-term prediction residual.
     */
    private final short[] res2 = new short[L_SUBFR];

    /**
     * The target of the first subframe of a 4.75 kbit/s pair.
     */
    private final short[] xnSf0 = new short[L_SUBFR];

    /**
     * The filtered codevector of the first subframe of a 4.75 kbit/s pair.
     */
    private final short[] y2Sf0 = new short[L_SUBFR];

    /**
     * The codevector of the first subframe of a 4.75 kbit/s pair.
     */
    private final short[] codeSf0 = new short[L_SUBFR];

    /**
     * The impulse response of the first subframe of a 4.75 kbit/s pair.
     */
    private final short[] h1Sf0 = new short[L_SUBFR];

    /**
     * The saved synthesis filter memory of a 4.75 kbit/s pair.
     */
    private final short[] memSynSave = new short[M];

    /**
     * The saved weighting filter memory of a 4.75 kbit/s pair.
     */
    private final short[] memW0Save = new short[M];

    /**
     * The saved error filter memory of a 4.75 kbit/s pair.
     */
    private final short[] memErrSave = new short[M];

    /**
     * The open-loop lags of the two half frames.
     */
    private final short[] tOp = new short[2];

    /**
     * The adaptive codebook search results: lag, fraction, pitch gain, gain limit.
     */
    private final short[] ltp = new short[4];

    /**
     * The gains of the subframe, see {@link GainQuantizer#GAIN_PIT}.
     */
    private final short[] gains = new short[4];

    /**
     * The weighted filter of the numerator.
     */
    private final short[] ap1 = new short[MP1];

    /**
     * The weighted filter of the denominator.
     */
    private final short[] ap2 = new short[MP1];

    /**
     * The synthesized speech of the frame.
     */
    private final short[] synth = new short[L_FRAME];

    /**
     * Creates an encoder in its reset state.
     *
     * @param dtx whether discontinuous transmission is on
     */
    EncoderCore(boolean dtx) {
        this.dtx = dtx;
        reset();
    }

    /**
     * Resets the encoder ({@code Speech_Encode_Frame_reset}).
     */
    void reset() {
        y2Hi = 0;
        y2Lo = 0;
        y1Hi = 0;
        y1Lo = 0;
        x0 = 0;
        x1 = 0;
        overflow.value = false;
        java.util.Arrays.fill(oldSpeech, (short) 0);
        java.util.Arrays.fill(oldExc, 0, PIT_MAX + L_INTERPOL, (short) 0);
        java.util.Arrays.fill(oldWsp, 0, PIT_MAX, (short) 0);
        java.util.Arrays.fill(memSyn, (short) 0);
        java.util.Arrays.fill(memW, (short) 0);
        java.util.Arrays.fill(memW0, (short) 0);
        java.util.Arrays.fill(memErr, 0, M, (short) 0);
        java.util.Arrays.fill(aiZero, ZERO, ZERO + L_SUBFR, (short) 0);
        java.util.Arrays.fill(hvec, 0, L_SUBFR, (short) 0);
        java.util.Arrays.fill(oldLags, (short) 40);
        lpcSt.reset();
        lspSt.reset();
        clLtpSt.reset();
        gainQuantSt.reset();
        pitchOlSt.reset();
        tonStabSt.reset();
        vadSt.reset();
        dtxEncSt.reset();
        sharp = SHARPMIN;
    }

    /**
     * Filters the input through the 80 Hz high-pass filter and divides it by two, in place
     * ({@code Pre_Process}).
     *
     * @param signal the samples
     * @param offset the first sample
     * @param lg the number of samples
     */
    private void preProcess(short[] signal, int offset, int lg) {
        var xn2 = x1;
        var xn1 = x0;
        for (var i = 0; i < lg; i++) {
            var lTmp = y1Hi * 7807;
            lTmp += (y1Lo * 7807) >> 15;
            lTmp += y2Hi * -3733;
            y2Hi = y1Hi;
            lTmp += (y2Lo * -3733) >> 15;
            y2Lo = y1Lo;
            lTmp += xn2 * 1899;
            xn2 = xn1;
            lTmp += xn1 * -3798;
            xn1 = signal[offset + i];
            lTmp += xn1 * 1899;
            signal[offset + i] = (short) ((lTmp + 0x0000800) >> 12);
            y1Hi = (short) (lTmp >> 12);
            y1Lo = (short) ((lTmp << 3) - (y1Hi << 15));
        }
        x1 = xn2;
        x0 = xn1;
    }

    /**
     * Encodes one frame into its parameters ({@code GSMEncodeFrame} without the bit packing).
     *
     * @param mode the requested mode
     * @param newSpeech the 160 input samples, masked to 13 bits and pre-processed in place
     * @param offset the first sample
     * @param prm receives the parameters
     * @return the mode used, {@link Mode#MRDTX} for a comfort noise frame
     */
    int encode(int mode, short[] newSpeech, int offset, short[] prm) {
        java.util.Arrays.fill(prm, 0, MAX_PRM_SIZE, (short) 0);
        for (var i = 0; i < L_FRAME; i++) {
            newSpeech[offset + i] = (short) (newSpeech[offset + i] & 0xfff8);
        }
        preProcess(newSpeech, offset, L_FRAME);
        return codAmr(mode, newSpeech, offset, prm);
    }

    /**
     * Codes one pre-processed frame ({@code cod_amr}).
     *
     * @param mode the requested mode
     * @param newSpeech the pre-processed samples
     * @param offset the first sample
     * @param ana receives the parameters
     * @return the mode used
     */
    private int codAmr(int mode, short[] newSpeech, int offset, short[] ana) {
        var overflow = this.overflow;
        System.arraycopy(newSpeech, offset, oldSpeech, NEW_SPEECH, L_FRAME);
        var usedMode = mode;
        var computeSidFlag = false;
        if (dtx) {
            var vadFlag = vadSt.vad(oldSpeech, NEW_SPEECH, overflow) != 0;
            var decision = dtxEncSt.txDtxHandler(vadFlag, usedMode, overflow);
            usedMode = decision >> 16;
            computeSidFlag = (decision & 1) != 0;
        }
        lpcSt.lpc(mode, oldSpeech, P_WINDOW, P_WINDOW_12K2, aT, 0, overflow);
        var anap = lspSt.lsp(mode, usedMode, aT, 0, aqT, 0, lspNew, 0, ana, 0);
        dtxEncSt.buffer(lspNew, oldSpeech, NEW_SPEECH, overflow);
        var lspFlag = false;
        if (usedMode == Mode.MRDTX) {
            anap = dtxEncSt.encode(computeSidFlag, lspSt.qSt, gainQuantSt.predictor(), ana, anap, overflow);
            java.util.Arrays.fill(oldExc, 0, PIT_MAX + L_INTERPOL, (short) 0);
            java.util.Arrays.fill(memW0, (short) 0);
            java.util.Arrays.fill(memErr, 0, M, (short) 0);
            java.util.Arrays.fill(aiZero, ZERO, ZERO + L_SUBFR, (short) 0);
            java.util.Arrays.fill(hvec, 0, L_SUBFR, (short) 0);
            lspSt.reset();
            System.arraycopy(lspNew, 0, lspSt.lspOld, 0, M);
            System.arraycopy(lspNew, 0, lspSt.lspOldQ, 0, M);
            clLtpSt.reset();
            sharp = SHARPMIN;
        } else {
            lspFlag = tonStabSt.checkLsp(lspSt.lspOld);
        }
        for (int subfrNr = 0, iSubfr = 0; subfrNr < L_FRAME / L_FRAME_BY2; subfrNr++, iSubfr += L_FRAME_BY2) {
            preBig(mode, iSubfr);
            if (mode != Mode.MR475 && mode != Mode.MR515) {
                tOp[subfrNr] = pitchOlSt.olLtp(vadSt, mode, oldWsp, WSP + iSubfr, oldLags, olGainFlg, subfrNr, dtx,
                        overflow);
            }
        }
        if (mode == Mode.MR475 || mode == Mode.MR515) {
            tOp[0] = pitchOlSt.olLtp(vadSt, mode, oldWsp, WSP, oldLags, olGainFlg, 1, dtx, overflow);
            tOp[1] = tOp[0];
        }
        if (dtx) {
            vadSt.pitchDetection(tOp, overflow);
        }
        if (usedMode != Mode.MRDTX) {
            subframes(usedMode, ana, anap, lspFlag);
            System.arraycopy(oldExc, L_FRAME, oldExc, 0, PIT_MAX + L_INTERPOL);
        }
        System.arraycopy(oldWsp, L_FRAME, oldWsp, 0, PIT_MAX);
        System.arraycopy(oldSpeech, L_FRAME, oldSpeech, 0, L_TOTAL - L_FRAME);
        return usedMode;
    }

    /**
     * Codes the four subframes of a speech frame.
     *
     * @param mode the mode used
     * @param ana receives the parameters
     * @param anap the next free parameter
     * @param lspFlag whether the LSPs indicate a resonance
     */
    private void subframes(int mode, short[] ana, int anap, boolean lspFlag) {
        var overflow = this.overflow;
        var a = 0;
        var aq = 0;
        var evenSubfr = false;
        var subfrNr = -1;
        short sharpSave = 0;
        var iSubfrSf0 = 0;
        short t0Sf0 = 0;
        short t0FracSf0 = 0;
        for (var iSubfr = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR) {
            subfrNr++;
            evenSubfr = !evenSubfr;
            if (evenSubfr && mode == Mode.MR475) {
                System.arraycopy(memSyn, 0, memSynSave, 0, M);
                System.arraycopy(memW0, 0, memW0Save, 0, M);
                System.arraycopy(memErr, 0, memErrSave, 0, M);
                sharpSave = sharp;
            }
            if (mode != Mode.MR475) {
                subframePreProc(mode, a, aq, iSubfr, memW0);
            } else {
                subframePreProc(mode, a, aq, iSubfr, memW0Save);
                if (evenSubfr) {
                    System.arraycopy(hvec, H1, h1Sf0, 0, L_SUBFR);
                }
            }
            System.arraycopy(res, 0, res2, 0, L_SUBFR);
            anap = clLtpSt.clLtp(tonStabSt, mode, iSubfr, tOp, hvec, H1, oldExc, EXC + iSubfr, res2, xn, lspFlag, xn2,
                    y1, gCoeff, ana, anap, ltp, overflow);
            var t0 = ltp[0];
            var t0Frac = ltp[1];
            gains[GainQuantizer.GAIN_PIT] = ltp[2];
            var gpLimit = ltp[3];
            if (subfrNr == 0 && olGainFlg[0] > 0) {
                oldLags[1] = t0;
            }
            if (subfrNr == 3 && olGainFlg[1] > 0) {
                oldLags[0] = t0;
            }
            anap += codebook.search(xn2, hvec, H1, t0, sharp, gains[GainQuantizer.GAIN_PIT], res2, code, y2, ana, anap,
                    mode, subfrNr, overflow);
            anap = gainQuantSt.quant(mode, res, oldExc, EXC + iSubfr, code, xn, xn2, y1, y2, gCoeff, evenSubfr,
                    gpLimit, gains, ana, anap, overflow);
            tonStabSt.updateGpClipping(gains[GainQuantizer.GAIN_PIT]);
            if (mode != Mode.MR475) {
                sharp = subframePostProc(mode, iSubfr, gains[GainQuantizer.GAIN_PIT], gains[GainQuantizer.GAIN_COD],
                        aq, xn, code, y1, y2, memSyn, memW0);
            } else if (evenSubfr) {
                iSubfrSf0 = iSubfr;
                System.arraycopy(xn, 0, xnSf0, 0, L_SUBFR);
                System.arraycopy(y2, 0, y2Sf0, 0, L_SUBFR);
                System.arraycopy(code, 0, codeSf0, 0, L_SUBFR);
                t0Sf0 = t0;
                t0FracSf0 = t0Frac;
                subframePostProc(mode, iSubfr, gains[GainQuantizer.GAIN_PIT], gains[GainQuantizer.GAIN_COD], aq, xn,
                        code, y1, y2, memSynSave, memW0Save);
                sharp = sharpSave;
            } else {
                System.arraycopy(memErrSave, 0, memErr, 0, M);
                Filters.predLt3or6(oldExc, EXC + iSubfrSf0, t0Sf0, t0FracSf0, L_SUBFR, true, work);
                CodebookCorrelation.convolve(oldExc, EXC + iSubfrSf0, h1Sf0, 0, y1, 0, L_SUBFR);
                aq -= MP1;
                sharpSave = subframePostProc(mode, iSubfrSf0, gains[GainQuantizer.SF0_GAIN_PIT],
                        gains[GainQuantizer.SF0_GAIN_COD], aq, xnSf0, codeSf0, y1, y2Sf0, memSyn, memW0);
                aq += MP1;
                subframePreProc(mode, a, aq, iSubfr, memW0);
                Filters.predLt3or6(oldExc, EXC + iSubfr, t0, t0Frac, L_SUBFR, true, work);
                CodebookCorrelation.convolve(oldExc, EXC + iSubfr, hvec, H1, y1, 0, L_SUBFR);
                sharp = subframePostProc(mode, iSubfr, gains[GainQuantizer.GAIN_PIT], gains[GainQuantizer.GAIN_COD],
                        aq, xn, code, y1, y2, memSyn, memW0);
            }
            a += MP1;
            aq += MP1;
        }
    }

    /**
     * Computes the weighted speech of a half frame ({@code pre_big}).
     *
     * @param mode the requested mode
     * @param frameOffset the first sample of the half frame
     */
    private void preBig(int mode, int frameOffset) {
        var g1 = mode <= Mode.MR795 ? GAMMA1 : GAMMA1_12K2;
        var aOffset = frameOffset > 0 ? MP1 << 1 : 0;
        for (var i = 0; i < 2; i++) {
            Filters.weightAi(aT, aOffset, g1, 0, ap1, 0);
            Filters.weightAi(aT, aOffset, GAMMA2, 0, ap2, 0);
            Filters.residu(ap1, 0, oldSpeech, SPEECH + frameOffset, oldWsp, WSP + frameOffset, L_SUBFR, work);
            Filters.synFilt(ap2, 0, oldWsp, WSP + frameOffset, oldWsp, WSP + frameOffset, L_SUBFR, memW, 0, true);
            aOffset += MP1;
            frameOffset += L_SUBFR;
        }
    }

    /**
     * Computes the impulse response, the LP residual and the target of a subframe
     * ({@code subframePreProc}).
     *
     * @param mode the mode used
     * @param a the index of the unquantized filter in {@link #aT}
     * @param aq the index of the quantized filter in {@link #aqT}
     * @param iSubfr the first sample of the subframe
     * @param memW0 the weighting filter memory to use
     */
    private void subframePreProc(int mode, int a, int aq, int iSubfr, short[] memW0) {
        var g1 = mode == Mode.MR122 || mode == Mode.MR102 ? GAMMA1_12K2 : GAMMA1;
        Filters.weightAi(aT, a, g1, 0, ap1, 0);
        Filters.weightAi(aT, a, GAMMA2, 0, ap2, 0);
        System.arraycopy(ap1, 0, aiZero, 0, M + 1);
        Filters.synFilt(aqT, aq, aiZero, 0, hvec, H1, L_SUBFR, aiZero, ZERO, false);
        Filters.synFilt(ap2, 0, hvec, H1, hvec, H1, L_SUBFR, aiZero, ZERO, false);
        Filters.residu(aqT, aq, oldSpeech, SPEECH + iSubfr, res, 0, L_SUBFR, work);
        System.arraycopy(res, 0, oldExc, EXC + iSubfr, L_SUBFR);
        Filters.synFilt(aqT, aq, oldExc, EXC + iSubfr, memErr, ERROR, L_SUBFR, memErr, 0, false);
        Filters.residu(ap1, 0, memErr, ERROR, xn, 0, L_SUBFR, work);
        Filters.synFilt(ap2, 0, xn, 0, xn, 0, L_SUBFR, memW0, 0, false);
    }

    /**
     * Builds the excitation, synthesizes the subframe and updates the filter memories
     * ({@code subframePostProc}).
     *
     * @param mode the mode used
     * @param iSubfr the first sample of the subframe
     * @param gainPit the quantized pitch gain
     * @param gainCode the quantized codebook gain
     * @param aq the index of the quantized filter in {@link #aqT}
     * @param xn the target
     * @param code the codevector
     * @param y1 the filtered adaptive codevector
     * @param y2 the filtered codevector
     * @param memSyn the synthesis filter memory
     * @param memW0 the weighting filter memory
     * @return the new pitch sharpening gain
     */
    private short subframePostProc(int mode, int iSubfr, short gainPit, short gainCode, int aq, short[] xn,
                                   short[] code, short[] y1, short[] y2, short[] memSyn, short[] memW0) {
        int tempShift;
        int kShift;
        short pitchFac;
        if (mode != Mode.MR122) {
            tempShift = 1;
            kShift = 16 - 2 - 1;
            pitchFac = gainPit;
        } else {
            tempShift = 2;
            kShift = 16 - 4 - 1;
            pitchFac = (short) (gainPit >> 1);
        }
        var newSharp = gainPit < SHARPMAX ? gainPit : SHARPMAX;
        var exc = oldExc;
        var base = EXC + iSubfr;
        for (var i = 0; i < L_SUBFR; i++) {
            var lTemp = (exc[base + i] * pitchFac) << 1;
            lTemp += (code[i] * gainCode) << 1;
            lTemp <<= tempShift;
            exc[base + i] = (short) ((lTemp + 0x08000) >> 16);
        }
        Filters.synFilt(aqT, aq, exc, base, synth, iSubfr, L_SUBFR, memSyn, 0, true);
        for (int i = L_SUBFR - M, j = 0; i < L_SUBFR; i++, j++) {
            memErr[j] = (short) (oldSpeech[SPEECH + iSubfr + i] - synth[iSubfr + i]);
            var temp = (short) ((y1[i] * gainPit) >> 14);
            temp += (short) ((y2[i] * gainCode) >> kShift);
            memW0[j] = (short) (xn[i] - temp);
        }
        return newSharp;
    }
}
