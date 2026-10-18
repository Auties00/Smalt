package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes the two hot stages of the MPEG audio polyphase synthesis filterbank.
 *
 * <p>The subband samples of a granule are stored column-major: subband {@code sb} of time slot
 * {@code t} is at {@code base + sb * 18 + t}. {@link #dct(float[], int, int)} turns every time
 * slot into the 32 matrixed values the window consumes, in place, and
 * {@link #window(float[], int, int, float[], float[], int, float[], int)} windows two time slots
 * into 64 output samples per channel.
 *
 * <p>Implementations must produce results bit-identical to {@link ScalarSynthesisKernel}: the same
 * IEEE single-precision operations in the same order per lane, never a fused multiply-add.
 */
interface SynthesisKernel {
    /**
     * Returns a new kernel for one decoder: {@link VectorSynthesisKernel} when {@link Simd#ENABLED},
     * {@link ScalarSynthesisKernel} otherwise.
     *
     * @return the kernel
     */
    static SynthesisKernel create() {
        return Simd.ENABLED ? VectorSynthesisKernel.create() : new ScalarSynthesisKernel();
    }

    /**
     * Applies the 32-point DCT-II of the matrixing stage to {@code slots} time slots in place.
     *
     * @param grbuf the granule buffer
     * @param base the index of subband 0, time slot 0 of the channel
     * @param slots the number of time slots, 12 or 18
     */
    void dct(float[] grbuf, int base, int slots);

    /**
     * Windows two consecutive time slots of both channels into 64 output samples each.
     *
     * @param grbuf the granule buffer after {@link #dct(float[], int, int)}
     * @param left the index of the first time slot of the left (or only) channel
     * @param right the index of the first time slot of the right channel; equal to {@code left}
     * for mono
     * @param outLeft the left output plane
     * @param outRight the right output plane; may be {@code outLeft} for mono, in which case the
     * left result is the one kept
     * @param outOffset the index of the first output sample
     * @param lins the filterbank history, 64 floats per time slot
     * @param linsOffset the index in {@code lins} of the first of the 15 history slots used
     */
    void window(float[] grbuf, int left, int right, float[] outLeft, float[] outRight, int outOffset,
                float[] lins, int linsOffset);

    /**
     * Applies the windowed 36-point IMDCT with overlap-add to a run of long-block subbands.
     *
     * @param grbuf the granule buffer
     * @param base the index of the first subband
     * @param overlap the channel's overlap buffer
     * @param ovl the index in {@code overlap} of the first subband's overlap
     * @param window the window, split as in {@link Mp3Tables#MDCT_WINDOW}
     * @param bands the number of subbands
     */
    void imdct36(float[] grbuf, int base, float[] overlap, int ovl, float[] window, int bands);
}
