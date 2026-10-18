/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SUBFR;

/**
 * Holds the quantization indices of the side information of one SILK frame.
 *
 * @implNote This implementation keeps the libopus field names and 8-bit storage.
 */
public final class SideInfoIndices {
    /**
     * The gain index of each subframe.
     */
    public final byte[] GainsIndices = new byte[MAX_NB_SUBFR];

    /**
     * The LTP codebook index of each subframe.
     */
    public final byte[] LTPIndex = new byte[MAX_NB_SUBFR];

    /**
     * The NLSF indices: the first-stage index followed by the residual indices.
     */
    public final byte[] NLSFIndices = new byte[MAX_LPC_ORDER + 1];

    /**
     * The pitch lag index.
     */
    public short lagIndex;

    /**
     * The pitch contour index.
     */
    public byte contourIndex;

    /**
     * The signal type.
     */
    public byte signalType;

    /**
     * The quantization offset type.
     */
    public byte quantOffsetType;

    /**
     * The NLSF interpolation factor in Q2.
     */
    public byte NLSFInterpCoef_Q2;

    /**
     * The periodicity index selecting the LTP codebook.
     */
    public byte PERIndex;

    /**
     * The LTP scaling index.
     */
    public byte LTP_scaleIndex;

    /**
     * The seed of the excitation dither.
     */
    public byte Seed;

    /**
     * Creates zeroed indices.
     */
    public SideInfoIndices() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(GainsIndices, (byte) 0);
        Arrays.fill(LTPIndex, (byte) 0);
        Arrays.fill(NLSFIndices, (byte) 0);
        lagIndex = 0;
        contourIndex = 0;
        signalType = 0;
        quantOffsetType = 0;
        NLSFInterpCoef_Q2 = 0;
        PERIndex = 0;
        LTP_scaleIndex = 0;
        Seed = 0;
    }

    /**
     * Copies every field of other indices into these, like a structure assignment.
     *
     * @param other the indices to copy
     */
    public void copyFrom(SideInfoIndices other) {
        System.arraycopy(other.GainsIndices, 0, GainsIndices, 0, GainsIndices.length);
        System.arraycopy(other.LTPIndex, 0, LTPIndex, 0, LTPIndex.length);
        System.arraycopy(other.NLSFIndices, 0, NLSFIndices, 0, NLSFIndices.length);
        lagIndex = other.lagIndex;
        contourIndex = other.contourIndex;
        signalType = other.signalType;
        quantOffsetType = other.quantOffsetType;
        NLSFInterpCoef_Q2 = other.NLSFInterpCoef_Q2;
        PERIndex = other.PERIndex;
        LTP_scaleIndex = other.LTP_scaleIndex;
        Seed = other.Seed;
    }
}
