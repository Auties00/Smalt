/* Ports opencore-amr 0.1.6 (lsp.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;

/**
 * Converts the encoder's LPC coefficients of a frame to LSPs, quantizes them and interpolates
 * the quantized and unquantized LPC coefficients of every subframe.
 *
 * <p>An instance is the LSP state ({@code lspState}): the previous unquantized and quantized
 * LSPs and the quantizer state, plus scratch buffers. It is not thread-safe.
 */
final class Lsp {
    /**
     * Holds the unquantized LSPs of the previous frame ({@code lsp_old}).
     */
    final short[] lspOld = new short[M];

    /**
     * Holds the quantized LSPs of the previous frame ({@code lsp_old_q}).
     */
    final short[] lspOldQ = new short[M];

    /**
     * The LSP quantizer state ({@code qSt}).
     */
    final QPlsf qSt = new QPlsf();

    /**
     * The LPC and LSP conversions.
     */
    private final Lpc lpc = new Lpc();

    /**
     * Holds the quantized LSPs of subframe 4.
     */
    private final short[] lspNewQ = new short[M];

    /**
     * Holds the unquantized LSPs of subframe 2 in {@code MR122}.
     */
    private final short[] lspMid = new short[M];

    /**
     * Holds the quantized LSPs of subframe 2 in {@code MR122}.
     */
    private final short[] lspMidQ = new short[M];

    /**
     * Creates the LSP state in its reset state ({@code lsp_init}).
     */
    Lsp() {
        reset();
    }

    /**
     * Restores the initial LSPs and clears the quantizer ({@code lsp_reset}).
     */
    void reset() {
        System.arraycopy(CommonTables.LSP_INIT_DATA, 0, lspOld, 0, M);
        System.arraycopy(lspOld, 0, lspOldQ, 0, M);
        qSt.reset();
    }

    /**
     * Converts, quantizes and interpolates the LPC coefficients of a frame ({@code lsp}).
     *
     * <p>{@code az} holds the 4 sets of unquantized coefficients from the LPC analysis; in
     * {@link Mode#MR122} sets 2 and 4 are converted, otherwise set 4, and the interpolated
     * unquantized sets are written back into {@code az}. Unless {@code usedMode} is
     * {@link Mode#MRDTX}, the LSPs are quantized, the codebook indices are appended to
     * {@code anap} and the 4 quantized sets are written to {@code azQ}.
     *
     * @param reqMode the requested mode, which selects the quantizer
     * @param usedMode the mode actually used, {@link Mode#MRDTX} to skip quantization
     * @param az the 4 sets of 11 LPC coefficients, interpolated in place
     * @param azOffset the index of the first coefficient
     * @param azQ receives the 4 sets of quantized LPC coefficients
     * @param azQOffset the index of the first quantized coefficient
     * @param lspNew receives the 10 unquantized LSPs of subframe 4
     * @param lspNewOffset the index of the first LSP
     * @param anap receives the LSP codebook indices
     * @param anapOffset the index where the indices are written
     * @return the index after the written indices: {@code anapOffset} plus 5 ({@code MR122}),
     * 3 (other modes) or 0 ({@link Mode#MRDTX})
     */
    int lsp(int reqMode, int usedMode, short[] az, int azOffset, short[] azQ, int azQOffset,
            short[] lspNew, int lspNewOffset, short[] anap, int anapOffset) {
        if (reqMode == Mode.MR122) {
            lpc.azLsp(az, azOffset + MP1, lspMid, 0, lspOld, 0);
            lpc.azLsp(az, azOffset + MP1 * 3, lspNew, lspNewOffset, lspMid, 0);
            lpc.intLpc1and3Partial(lspOld, 0, lspMid, 0, lspNew, lspNewOffset, az, azOffset);
            if (usedMode != Mode.MRDTX) {
                qSt.qPlsf5(lspMid, 0, lspNew, lspNewOffset, lspMidQ, 0, lspNewQ, 0, anap, anapOffset);
                lpc.intLpc1and3(lspOldQ, 0, lspMidQ, 0, lspNewQ, 0, azQ, azQOffset);
                anapOffset += 5;
            }
        } else {
            lpc.azLsp(az, azOffset + MP1 * 3, lspNew, lspNewOffset, lspOld, 0);
            lpc.intLpc1to3Partial(lspOld, 0, lspNew, lspNewOffset, az, azOffset);
            if (usedMode != Mode.MRDTX) {
                qSt.qPlsf3(reqMode, lspNew, lspNewOffset, lspNewQ, 0, anap, anapOffset);
                lpc.intLpc1to3(lspOldQ, 0, lspNewQ, 0, azQ, azQOffset);
                anapOffset += 3;
            }
        }
        System.arraycopy(lspNew, lspNewOffset, lspOld, 0, M);
        if (usedMode != Mode.MRDTX) {
            System.arraycopy(lspNewQ, 0, lspOldQ, 0, M);
        }
        return anapOffset;
    }
}
