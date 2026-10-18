/* Ports vo-amrwbenc 0.1.3 (dtx.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Implements the discontinuous transmission of the AMR-WB encoder (TS 26.193): the hangover
 * logic that switches inactive frames to comfort noise, the history of ISFs and energies, and the
 * computation of the SID parameters and of the comfort-noise excitation.
 *
 * <p>An instance is the DTX state of one encoder; it is not thread-safe.
 */
final class WbDtx {
    /**
     * The LP order.
     */
    private static final int M = 16;

    /**
     * The number of frames of the history.
     */
    private static final int DTX_HIST_SIZE = 8;

    /**
     * The number of frames of the history minus one.
     */
    private static final int DTX_HIST_SIZE_MIN_ONE = 7;

    /**
     * The number of frames since the last SID analysis below which the hangover is skipped.
     */
    private static final int DTX_ELAPSED_FRAMES_THRESH = 24 + 7 - 1;

    /**
     * The number of inactive frames encoded as speech before comfort noise starts.
     */
    private static final int DTX_HANG_CONST = 7;

    /**
     * The threshold of the ISF outlier detection.
     */
    private static final int INV_MED_THRESH = 14564;

    /**
     * The energy variation above which comfort noise is dithered.
     */
    private static final int GAIN_THR = 180;

    /**
     * The seed of the comfort-noise generator.
     */
    private static final short RANDOM_INITSEED = 21845;

    /**
     * The energy correction of each speech mode ({@code en_adjust} of {@code dtx.c}).
     */
    private static final short[] EN_ADJUST = {230, 179, 141, 128, 122, 115, 115, 115, 115};

    /**
     * The ISFs of the last {@link #DTX_HIST_SIZE} frames.
     */
    private final short[] isfHist = new short[M * DTX_HIST_SIZE];

    /**
     * The log energies of the last {@link #DTX_HIST_SIZE} frames.
     */
    private final short[] logEnHist = new short[DTX_HIST_SIZE];

    /**
     * The index of the newest entry of the history.
     */
    private short histPtr;

    /**
     * The quantized log energy of the last SID frame.
     */
    private short logEnIndex;

    /**
     * The seed of the comfort-noise generator.
     */
    private short cngSeed;

    /**
     * The remaining hangover frames.
     */
    short dtxHangoverCount;

    /**
     * The number of frames since the last SID analysis.
     */
    private short decAnaElapsedCount;

    /**
     * The ISF distances between the frames of the history.
     */
    private final int[] d = new int[28];

    /**
     * The sum of the ISF distances of each frame of the history.
     */
    private final int[] sumD = new int[DTX_HIST_SIZE];

    /**
     * The averaged ISFs of the history.
     */
    private final int[] isfAverage = new int[M];

    /**
     * The indices of the history frames excluded from the ISF average.
     */
    private final short[] isfOrder = new short[3];

    /**
     * The ISFs of the excluded frames, saved while they are replaced.
     */
    private final short[] isfTmp = new short[2 * M];

    /**
     * The SID ISF indices.
     */
    private final short[] indices = new short[7];

    /**
     * Creates the DTX state, reset with the given initial ISFs.
     *
     * @param isfInit the initial ISFs
     */
    WbDtx(short[] isfInit) {
        reset(isfInit);
    }

    /**
     * Resets the DTX state ({@code dtx_enc_reset}).
     *
     * @param isfInit the initial ISFs
     */
    void reset(short[] isfInit) {
        histPtr = 0;
        logEnIndex = 0;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            System.arraycopy(isfInit, 0, isfHist, i * M, M);
        }
        cngSeed = RANDOM_INITSEED;
        java.util.Arrays.fill(logEnHist, (short) 0);
        dtxHangoverCount = DTX_HANG_CONST;
        decAnaElapsedCount = 32767;
        java.util.Arrays.fill(d, 0);
        for (var i = 0; i < DTX_HIST_SIZE - 1; i++) {
            sumD[i] = 0;
        }
    }

    /**
     * Clears every field, including the one {@link #reset(short[])} keeps, as a newly allocated
     * state.
     *
     * @param isfInit the initial ISFs
     */
    void clear(short[] isfInit) {
        java.util.Arrays.fill(sumD, 0);
        reset(isfInit);
    }

    /**
     * Computes the SID parameters and the comfort-noise excitation of a frame
     * ({@code dtx_enc}).
     *
     * @param isf the 16 quantized ISFs of the comfort noise, written
     * @param exc2 the 256 samples of comfort-noise excitation, written
     * @param params the frame bits, to which the SID parameters are appended
     * @param quantizer the ISF quantizer
     */
    void encode(short[] isf, short[] exc2, WbParams params, WbIsfQuantizer quantizer) {
        var logEn = (short) 0;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            logEn = add(logEn, logEnHist[i]);
        }
        findFrameIndices();
        averIsfHistory();
        for (var j = 0; j < M; j++) {
            isf[j] = (short) (isfAverage[j] >> 3);
        }
        logEn = (short) (logEn >> 2);
        logEn = add(logEn, 512);
        logEn = mult(logEn, 21504);
        logEnIndex = shr(logEn, 6);
        if (logEnIndex > 63) {
            logEnIndex = 63;
        }
        if (logEnIndex < 0) {
            logEnIndex = 0;
        }
        quantizer.qisfNs(isf, isf, indices);
        params.put(indices[0], 6);
        params.put(indices[1], 6);
        params.put(indices[2], 6);
        params.put(indices[3], 5);
        params.put(indices[4], 5);
        params.put(logEnIndex, 6);
        params.put(ditheringControl(), 1);
        logEn = shl(logEnIndex, 15 - 6);
        logEn = mult(logEn, 12483);
        var logEnIntE = (short) (logEn >> 10);
        var logEnIntM = (short) (logEn & 0x3ff);
        logEnIntM = shl(logEnIntM, 5);
        logEnIntE = add(logEnIntE, 16 - 1);
        var level32 = WbBasicOps.pow2(logEnIntE, logEnIntM);
        var exp0 = normL(level32);
        level32 = level32 << exp0;
        exp0 = (short) (15 - exp0);
        var level = extractH(level32);
        for (var i = 0; i < 256; i++) {
            cngSeed = WbFilters.random(cngSeed);
            exc2[i] = (short) (cngSeed >> 4);
        }
        var packed = WbBasicOps.dotProduct12(exc2, 0, exc2, 0, 256);
        packed = WbBasicOps.isqrtN(WbBasicOps.value(packed), WbBasicOps.exponent(packed));
        var gain = extractH(WbBasicOps.value(packed));
        gain = mult(level, gain);
        var exp = add(exp0, WbBasicOps.exponent(packed));
        exp += 4;
        for (var i = 0; i < 256; i++) {
            var tmp = mult(exc2[i], gain);
            exc2[i] = shl(tmp, exp);
        }
    }

    /**
     * Stores the ISFs and the excitation energy of a frame in the history ({@code dtx_buffer}).
     *
     * @param isfNew the 16 unquantized ISFs
     * @param enr the energy of the excitation
     * @param codecMode the mode the frame was requested in, 0 to 8
     */
    void buffer(short[] isfNew, int enr, int codecMode) {
        histPtr = add(histPtr, 1);
        if (histPtr == DTX_HIST_SIZE) {
            histPtr = 0;
        }
        System.arraycopy(isfNew, 0, isfHist, histPtr * M, M);
        var log = WbBasicOps.log2(enr);
        var logEnE = (short) (log >> 16);
        var logEnM = (short) log;
        var logEn = shl(logEnE, 7);
        logEn = add(logEn, shr(logEnM, 15 - 7));
        logEn = sub(logEn, add(1024, EN_ADJUST[codecMode]));
        logEnHist[histPtr] = logEn;
    }

    /**
     * Decides whether a frame is coded as speech or comfort noise ({@code tx_dtx_handler}).
     *
     * @param vadFlag the VAD decision of the frame
     * @param usedMode the mode the frame would use
     * @return {@code usedMode}, or {@code 9} when the frame is comfort noise
     */
    int txDtxHandler(int vadFlag, int usedMode) {
        decAnaElapsedCount = add(decAnaElapsedCount, 1);
        if (vadFlag != 0) {
            dtxHangoverCount = DTX_HANG_CONST;
        } else if (dtxHangoverCount == 0) {
            decAnaElapsedCount = 0;
            usedMode = 9;
        } else {
            dtxHangoverCount = sub(dtxHangoverCount, 1);
            if (sub(add(decAnaElapsedCount, dtxHangoverCount), DTX_ELAPSED_FRAMES_THRESH) < 0) {
                usedMode = 9;
            }
        }
        return usedMode;
    }

    /**
     * Averages the ISF history into {@link #isfAverage}, replacing the frames listed in
     * {@link #isfOrder} by the median frame ({@code aver_isf_history}).
     */
    private void averIsfHistory() {
        var isfOld = isfHist;
        for (var k = 0; k < 2; k++) {
            if (isfOrder[k] + 1 != 0) {
                for (var i = 0; i < M; i++) {
                    isfTmp[k * M + i] = isfOld[isfOrder[k] * M + i];
                    isfOld[isfOrder[k] * M + i] = isfOld[isfOrder[2] * M + i];
                }
            }
        }
        for (var j = 0; j < M; j++) {
            var tmp = 0;
            for (var i = 0; i < DTX_HIST_SIZE; i++) {
                tmp = lAdd(tmp, isfOld[i * M + j]);
            }
            isfAverage[j] = tmp;
        }
        for (var k = 0; k < 2; k++) {
            if (isfOrder[k] + 1 != 0) {
                for (var i = 0; i < M; i++) {
                    isfOld[isfOrder[k] * M + i] = isfTmp[k * M + i];
                }
            }
        }
    }

    /**
     * Updates the ISF distances and finds the history frames whose ISFs deviate most into
     * {@link #isfOrder} ({@code find_frame_indices}).
     */
    private void findFrameIndices() {
        var tmp = (short) DTX_HIST_SIZE_MIN_ONE;
        var j = (short) -1;
        for (var i = 0; i < DTX_HIST_SIZE_MIN_ONE; i++) {
            j = add(j, tmp);
            sumD[i] = lSub(sumD[i], d[j]);
            tmp = sub(tmp, 1);
        }
        for (var i = DTX_HIST_SIZE_MIN_ONE; i > 0; i--) {
            sumD[i] = sumD[i - 1];
        }
        sumD[0] = 0;
        tmp = 0;
        for (var i = 27; i >= 12; i = (short) (i - tmp)) {
            tmp = add(tmp, 1);
            for (var k = tmp; k > 0; k--) {
                d[i - k + 1] = d[i - k - tmp];
            }
        }
        var ptr = histPtr;
        for (var i = 1; i < DTX_HIST_SIZE; i++) {
            ptr = sub(ptr, 1);
            if (ptr < 0) {
                ptr = DTX_HIST_SIZE_MIN_ONE;
            }
            var acc = 0;
            for (var k = 0; k < M; k++) {
                var diff = sub(isfHist[histPtr * M + k], isfHist[ptr * M + k]);
                acc = lMac(acc, diff, diff);
            }
            d[i - 1] = acc;
            sumD[0] = lAdd(sumD[0], d[i - 1]);
            sumD[i] = lAdd(sumD[i], d[i - 1]);
        }
        var summax = sumD[0];
        var summin = sumD[0];
        isfOrder[0] = 0;
        isfOrder[2] = 0;
        for (var i = 1; i < DTX_HIST_SIZE; i++) {
            if (lSub(sumD[i], summax) > 0) {
                isfOrder[0] = (short) i;
                summax = sumD[i];
            }
            if (lSub(sumD[i], summin) < 0) {
                isfOrder[2] = (short) i;
                summin = sumD[i];
            }
        }
        var summax2nd = -2147483647;
        isfOrder[1] = -1;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            if (lSub(sumD[i], summax2nd) > 0 && sub(i, isfOrder[0]) != 0) {
                isfOrder[1] = (short) i;
                summax2nd = sumD[i];
            }
        }
        for (var i = 0; i < 3; i++) {
            isfOrder[i] = sub(histPtr, isfOrder[i]);
            if (isfOrder[i] < 0) {
                isfOrder[i] = add(isfOrder[i], DTX_HIST_SIZE);
            }
        }
        var shift = normL(summax);
        summax = summax << shift;
        summin = summin << shift;
        var limit = lMult(WbBasicOps.round(summax), INV_MED_THRESH);
        if (limit <= summin) {
            isfOrder[0] = -1;
        }
        summax2nd = lShl(summax2nd, shift);
        limit = lMult(WbBasicOps.round(summax2nd), INV_MED_THRESH);
        if (limit <= summin) {
            isfOrder[1] = -1;
        }
    }

    /**
     * Decides whether the comfort noise is dithered, from the ISF and energy variations of the
     * history ({@code dithering_control}).
     *
     * @return {@code 1} to dither, else {@code 0}
     */
    private int ditheringControl() {
        var isfDiff = 0;
        for (var i = 0; i < 8; i++) {
            isfDiff = lAdd(isfDiff, sumD[i]);
        }
        var cnDith = (isfDiff >> 26) > 0 ? 1 : 0;
        var mean = (short) 0;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            mean = add(mean, logEnHist[i]);
        }
        mean = (short) (mean >> 3);
        var gainDiff = (short) 0;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            var tmp = absS(sub(logEnHist[i], mean));
            gainDiff = add(gainDiff, tmp);
        }
        if (gainDiff > GAIN_THR) {
            cnDith = 1;
        }
        return cnDith;
    }
}
