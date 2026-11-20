/* Ports opencore-amr 0.1.6 (dtx_enc.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_ELAPSED_FRAMES_THRESH;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_HANG_CONST;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_HIST_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.LSF_GAP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;

/**
 * Runs the discontinuous transmission of the AMR-NB encoder ({@code dtx_enc}): the hangover that
 * decides when the encoder switches to comfort noise, the history of LSPs and frame energies,
 * and the comfort noise (SID) parameters.
 */
final class DtxEncoder {
    /**
     * The LSPs of the last {@link Constants#DTX_HIST_SIZE} frames.
     */
    private final short[] lspHist = new short[M * DTX_HIST_SIZE];

    /**
     * The log energies of the last {@link Constants#DTX_HIST_SIZE} frames.
     */
    private final short[] logEnHist = new short[DTX_HIST_SIZE];

    /**
     * The index of the newest history entry.
     */
    private short histPtr;

    /**
     * The quantized log energy of the comfort noise.
     */
    private short logEnIndex;

    /**
     * The initial LSF quantizer index of the comfort noise.
     */
    private short initLsfVqIndex;

    /**
     * The LSF quantizer indices of the comfort noise.
     */
    private final short[] lspIndex = new short[3];

    /**
     * The hangover counter.
     */
    private short dtxHangoverCount;

    /**
     * The number of frames since the last SID computation.
     */
    private short decAnaElapsedCount;

    /**
     * The averaged LSPs.
     */
    private final short[] lsp = new short[M];

    /**
     * The averaged LSFs.
     */
    private final short[] lsf = new short[M];

    /**
     * The quantized averaged LSPs.
     */
    private final short[] lspQ = new short[M];

    /**
     * The LSP sums.
     */
    private final int[] lLsp = new int[M];

    /**
     * Creates the encoder in its reset state.
     */
    DtxEncoder() {
        reset();
    }

    /**
     * Resets the history and the hangover.
     */
    void reset() {
        histPtr = 0;
        logEnIndex = 0;
        initLsfVqIndex = 0;
        lspIndex[0] = 0;
        lspIndex[1] = 0;
        lspIndex[2] = 0;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            System.arraycopy(CommonTables.LSP_INIT_DATA, 0, lspHist, i * M, M);
        }
        java.util.Arrays.fill(logEnHist, (short) 0);
        dtxHangoverCount = DTX_HANG_CONST;
        decAnaElapsedCount = 32767;
    }

    /**
     * Computes the comfort noise parameters when needed and writes them ({@code dtx_enc}).
     *
     * @param computeSidFlag whether new parameters must be computed
     * @param qSt the LSF quantizer
     * @param predState the gain predictor, reset to the comfort noise energy
     * @param ana receives the 5 parameters
     * @param anaOffset the next free parameter
     * @param overflow the saturation flag
     * @return the next free parameter
     */
    int encode(boolean computeSidFlag, QPlsf qSt, GcPred predState, short[] ana, int anaOffset, Flag overflow) {
        if (computeSidFlag) {
            short logEn = 0;
            java.util.Arrays.fill(lLsp, 0);
            for (var i = DTX_HIST_SIZE - 1; i >= 0; i--) {
                short temp;
                if (logEnHist[i] < 0) {
                    temp = (short) ~((~logEnHist[i]) >> 2);
                } else {
                    temp = (short) (logEnHist[i] >> 2);
                }
                logEn = add(logEn, temp, overflow);
                for (var j = M - 1; j >= 0; j--) {
                    lLsp[j] = lAdd(lLsp[j], lspHist[i * M + j], overflow);
                }
            }
            if (logEn < 0) {
                logEn = (short) ~((~logEn) >> 1);
            } else {
                logEn = (short) (logEn >> 1);
            }
            for (var j = M - 1; j >= 0; j--) {
                if (lLsp[j] < 0) {
                    lsp[j] = (short) ~((~lLsp[j]) >> 3);
                } else {
                    lsp[j] = (short) (lLsp[j] >> 3);
                }
            }
            logEnIndex = (short) (logEn + 2560);
            logEnIndex += 128;
            if (logEnIndex < 0) {
                logEnIndex = (short) ~((~logEnIndex) >> 8);
            } else {
                logEnIndex = (short) (logEnIndex >> 8);
            }
            if (logEnIndex > 63) {
                logEnIndex = 63;
            } else if (logEnIndex < 0) {
                logEnIndex = 0;
            }
            logEn = (short) (logEnIndex << (-2 + 10));
            logEn = sub(logEn, (short) 11560, overflow);
            if (logEn > 0) {
                logEn = 0;
            } else if (logEn < -14436) {
                logEn = -14436;
            }
            for (var i = 0; i < 4; i++) {
                predState.pastQuaEn[i] = logEn;
            }
            logEn = (short) ((5443 * logEn) >> 15);
            for (var i = 0; i < 4; i++) {
                predState.pastQuaEnMR122[i] = logEn;
            }
            Lsf.lspLsf(lsp, 0, lsf, 0, M);
            Lsf.reorderLsf(lsf, 0, LSF_GAP, M);
            Lsf.lsfLsp(lsf, 0, lsp, 0, M);
            initLsfVqIndex = qSt.qPlsf3(Mode.MRDTX, lsp, 0, lspQ, 0, lspIndex, 0);
        }
        ana[anaOffset++] = initLsfVqIndex;
        ana[anaOffset++] = lspIndex[0];
        ana[anaOffset++] = lspIndex[1];
        ana[anaOffset++] = lspIndex[2];
        ana[anaOffset++] = logEnIndex;
        return anaOffset;
    }

    /**
     * Stores the LSPs and the log energy of a frame in the history ({@code dtx_buffer}).
     *
     * @param lspNew the unquantized LSPs of the frame
     * @param speech the speech buffer
     * @param speechOffset the first sample of the frame
     * @param overflow the saturation flag
     */
    void buffer(short[] lspNew, short[] speech, int speechOffset, Flag overflow) {
        histPtr += 1;
        if (histPtr == DTX_HIST_SIZE) {
            histPtr = 0;
        }
        System.arraycopy(lspNew, 0, lspHist, histPtr * M, M);
        var lFrameEn = 0;
        for (var i = 0; i < L_FRAME; i++) {
            var x = speech[speechOffset + i];
            lFrameEn += (x * x) << 1;
            if (lFrameEn < 0) {
                lFrameEn = Integer.MAX_VALUE;
                break;
            }
        }
        var log = FixedMath.log2(lFrameEn);
        var logEnE = (short) (log >> 16);
        var logEnM = (short) log;
        var lTemp = logEnE << 10;
        short logEn;
        if (lTemp != (short) lTemp) {
            overflow.value = true;
            logEn = logEnE > 0 ? MAX_16 : MIN_16;
        } else {
            logEn = (short) lTemp;
        }
        logEn += (short) (logEnM >> (15 - 10));
        logEn -= 8521;
        logEnHist[histPtr] = (short) (logEn >> 1);
    }

    /**
     * Decides whether the frame is coded as comfort noise ({@code tx_dtx_handler}).
     *
     * @param vadFlag the voice activity decision
     * @param mode the requested mode
     * @param overflow the saturation flag
     * @return the used mode, {@link Mode#MRDTX} for comfort noise, in the high half, and whether
     * new comfort noise parameters may be computed in bit 0
     */
    int txDtxHandler(boolean vadFlag, int mode, Flag overflow) {
        decAnaElapsedCount = add(decAnaElapsedCount, (short) 1, overflow);
        var computeNewSidPossible = 0;
        var usedMode = mode;
        if (vadFlag) {
            dtxHangoverCount = DTX_HANG_CONST;
        } else if (dtxHangoverCount == 0) {
            decAnaElapsedCount = 0;
            usedMode = Mode.MRDTX;
            computeNewSidPossible = 1;
        } else {
            dtxHangoverCount -= 1;
            var count = add(decAnaElapsedCount, dtxHangoverCount, overflow);
            if (count < DTX_ELAPSED_FRAMES_THRESH) {
                usedMode = Mode.MRDTX;
            }
        }
        return (usedMode << 16) | computeNewSidPossible;
    }
}
