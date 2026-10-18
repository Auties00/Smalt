/* Ports libopus 1.5.2 (silk/resampler_structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SILK_RESAMPLER_MAX_FIR_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SILK_RESAMPLER_MAX_IIR_ORDER;

/**
 * Holds the state of a SILK resampler.
 *
 * <p>The reference stores the FIR history in a union of 32-bit and 16-bit views; each resampler
 * function uses exactly one view for the lifetime of a configuration and initialization clears
 * both, so the port keeps them as two arrays, {@link #sFIR_i32} and {@link #sFIR_i16}.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class ResamplerState {
    /**
     * The IIR filter state.
     */
    public final int[] sIIR = new int[SILK_RESAMPLER_MAX_IIR_ORDER];

    /**
     * The 32-bit view of the FIR history ({@code sFIR.i32}), used by the downsampler.
     */
    public final int[] sFIR_i32 = new int[SILK_RESAMPLER_MAX_FIR_ORDER];

    /**
     * The 16-bit view of the FIR history ({@code sFIR.i16}), used by the IIR/FIR upsampler.
     */
    public final short[] sFIR_i16 = new short[SILK_RESAMPLER_MAX_FIR_ORDER];

    /**
     * The input delay buffer.
     */
    public final short[] delayBuf = new short[48];

    /**
     * The resampling function in use.
     */
    public int resampler_function;

    /**
     * The number of input samples processed per batch.
     */
    public int batchSize;

    /**
     * The inverse resampling ratio in Q16.
     */
    public int invRatio_Q16;

    /**
     * The FIR order.
     */
    public int FIR_Order;

    /**
     * The number of FIR phases.
     */
    public int FIR_Fracs;

    /**
     * The input sampling rate in kHz.
     */
    public int Fs_in_kHz;

    /**
     * The output sampling rate in kHz.
     */
    public int Fs_out_kHz;

    /**
     * The number of samples of input delay.
     */
    public int inputDelay;

    /**
     * The filter coefficients, or {@code null} when the function needs none.
     */
    public short[] Coefs;

    /**
     * The scratch buffer of the 2x upsampled signal of {@code silk_resampler_private_IIR_FIR},
     * sized for the largest batch; not part of the reference structure and never copied.
     */
    final short[] scratchIirFir = new short[2 * 480 + ResamplerRom.RESAMPLER_ORDER_FIR_12];

    /**
     * The scratch buffer of the AR-filtered signal of {@code silk_resampler_private_down_FIR},
     * sized for the largest batch; not part of the reference structure and never copied.
     */
    final int[] scratchDownFir = new int[480 + SILK_RESAMPLER_MAX_FIR_ORDER];

    /**
     * The work array of the interpolation kernels; not part of the reference structure and never
     * copied.
     */
    final int[] scratchKernel = new int[SilkKernels.SCRATCH_SIZE];

    /**
     * Creates a zeroed state.
     */
    public ResamplerState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(sIIR, 0);
        Arrays.fill(sFIR_i32, 0);
        Arrays.fill(sFIR_i16, (short) 0);
        Arrays.fill(delayBuf, (short) 0);
        resampler_function = 0;
        batchSize = 0;
        invRatio_Q16 = 0;
        FIR_Order = 0;
        FIR_Fracs = 0;
        Fs_in_kHz = 0;
        Fs_out_kHz = 0;
        inputDelay = 0;
        Coefs = null;
    }

    /**
     * Copies every field of another state into this one, like {@code silk_memcpy} of the
     * structure.
     *
     * @param other the state to copy
     */
    public void copyFrom(ResamplerState other) {
        System.arraycopy(other.sIIR, 0, sIIR, 0, sIIR.length);
        System.arraycopy(other.sFIR_i32, 0, sFIR_i32, 0, sFIR_i32.length);
        System.arraycopy(other.sFIR_i16, 0, sFIR_i16, 0, sFIR_i16.length);
        System.arraycopy(other.delayBuf, 0, delayBuf, 0, delayBuf.length);
        resampler_function = other.resampler_function;
        batchSize = other.batchSize;
        invRatio_Q16 = other.invRatio_Q16;
        FIR_Order = other.FIR_Order;
        FIR_Fracs = other.FIR_Fracs;
        Fs_in_kHz = other.Fs_in_kHz;
        Fs_out_kHz = other.Fs_out_kHz;
        inputDelay = other.inputDelay;
        Coefs = other.Coefs;
    }
}
