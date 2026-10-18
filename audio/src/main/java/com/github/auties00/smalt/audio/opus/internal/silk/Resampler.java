/* Ports libopus 1.5.2 (silk/resampler.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulww;

/**
 * Converts 16-bit signals between the SILK internal rates and the API rates.
 *
 * <p>The method depends on the rate pair: a copy for equal rates, all-pass 2x upsampling, all-pass
 * 2x upsampling followed by fractional FIR interpolation, or an AR2 filter followed by FIR
 * decimation. The encoder and decoder use different delay compensations so their total delays
 * line up. The first millisecond of every call is taken from a delay buffer, so every call must
 * provide at least one millisecond of input.
 */
public final class Resampler {
    /**
     * The resampling function that copies the input.
     */
    static final int USE_silk_resampler_copy = 0;

    /**
     * The resampling function that upsamples by exactly two.
     */
    static final int USE_silk_resampler_private_up2_HQ_wrapper = 1;

    /**
     * The resampling function that upsamples by two and interpolates.
     */
    static final int USE_silk_resampler_private_IIR_FIR = 2;

    /**
     * The resampling function that filters and decimates.
     */
    static final int USE_silk_resampler_private_down_FIR = 3;

    /**
     * The maximum batch size in milliseconds.
     */
    static final int RESAMPLER_MAX_BATCH_SIZE_MS = 10;

    /**
     * The maximum sampling rate in kHz.
     */
    static final int RESAMPLER_MAX_FS_KHZ = 48;

    /**
     * The maximum batch size in input samples.
     */
    static final int RESAMPLER_MAX_BATCH_SIZE_IN = RESAMPLER_MAX_BATCH_SIZE_MS * RESAMPLER_MAX_FS_KHZ;

    /**
     * The delay compensation of the encoder, indexed by input rate ID (8, 12, 16, 24, 48 kHz)
     * and output rate ID (8, 12, 16 kHz).
     */
    private static final byte[][] delay_matrix_enc = {
            {6, 0, 3},
            {0, 7, 3},
            {0, 1, 10},
            {0, 2, 6},
            {18, 10, 12}
    };

    /**
     * The delay compensation of the decoder, indexed by input rate ID (8, 12, 16 kHz) and output
     * rate ID (8, 12, 16, 24, 48 kHz).
     */
    private static final byte[][] delay_matrix_dec = {
            {4, 0, 2, 0, 0},
            {0, 9, 4, 7, 4},
            {0, 3, 12, 7, 7}
    };

    private Resampler() {
        throw new AssertionError();
    }

    /**
     * Maps 8000, 12000, 16000, 24000 and 48000 to 0 through 4 ({@code rateID}).
     *
     * @param R the rate in Hz
     * @return the rate ID
     */
    private static int rateID(int R) {
        return (((R >> 12) - (R > 16000 ? 1 : 0)) >> (R > 24000 ? 1 : 0)) - 1;
    }

    /**
     * Initializes a resampler for a pair of rates ({@code silk_resampler_init}).
     *
     * @param S         the state to initialize
     * @param Fs_Hz_in  the input rate in Hz
     * @param Fs_Hz_out the output rate in Hz
     * @param forEnc    nonzero for the encoder (API rate to internal rate), zero for the decoder
     * @return zero on success, {@code -1} for an unsupported rate pair
     */
    public static int resamplerInit(ResamplerState S, int Fs_Hz_in, int Fs_Hz_out, int forEnc) {
        S.clear();
        if (forEnc != 0) {
            if ((Fs_Hz_in != 8000 && Fs_Hz_in != 12000 && Fs_Hz_in != 16000 && Fs_Hz_in != 24000 && Fs_Hz_in != 48000)
                    || (Fs_Hz_out != 8000 && Fs_Hz_out != 12000 && Fs_Hz_out != 16000)) {
                return -1;
            }
            S.inputDelay = delay_matrix_enc[rateID(Fs_Hz_in)][rateID(Fs_Hz_out)];
        } else {
            if ((Fs_Hz_in != 8000 && Fs_Hz_in != 12000 && Fs_Hz_in != 16000)
                    || (Fs_Hz_out != 8000 && Fs_Hz_out != 12000 && Fs_Hz_out != 16000 && Fs_Hz_out != 24000
                    && Fs_Hz_out != 48000)) {
                return -1;
            }
            S.inputDelay = delay_matrix_dec[rateID(Fs_Hz_in)][rateID(Fs_Hz_out)];
        }
        S.Fs_in_kHz = Fs_Hz_in / 1000;
        S.Fs_out_kHz = Fs_Hz_out / 1000;
        S.batchSize = S.Fs_in_kHz * RESAMPLER_MAX_BATCH_SIZE_MS;
        var up2x = 0;
        if (Fs_Hz_out > Fs_Hz_in) {
            if (Fs_Hz_out == Fs_Hz_in * 2) {
                S.resampler_function = USE_silk_resampler_private_up2_HQ_wrapper;
            } else {
                S.resampler_function = USE_silk_resampler_private_IIR_FIR;
                up2x = 1;
            }
        } else if (Fs_Hz_out < Fs_Hz_in) {
            S.resampler_function = USE_silk_resampler_private_down_FIR;
            if (Fs_Hz_out * 4 == Fs_Hz_in * 3) {
                S.FIR_Fracs = 3;
                S.FIR_Order = ResamplerRom.RESAMPLER_DOWN_ORDER_FIR0;
                S.Coefs = ResamplerRom.silk_Resampler_3_4_COEFS;
            } else if (Fs_Hz_out * 3 == Fs_Hz_in * 2) {
                S.FIR_Fracs = 2;
                S.FIR_Order = ResamplerRom.RESAMPLER_DOWN_ORDER_FIR0;
                S.Coefs = ResamplerRom.silk_Resampler_2_3_COEFS;
            } else if (Fs_Hz_out * 2 == Fs_Hz_in) {
                S.FIR_Fracs = 1;
                S.FIR_Order = ResamplerRom.RESAMPLER_DOWN_ORDER_FIR1;
                S.Coefs = ResamplerRom.silk_Resampler_1_2_COEFS;
            } else if (Fs_Hz_out * 3 == Fs_Hz_in) {
                S.FIR_Fracs = 1;
                S.FIR_Order = ResamplerRom.RESAMPLER_DOWN_ORDER_FIR2;
                S.Coefs = ResamplerRom.silk_Resampler_1_3_COEFS;
            } else if (Fs_Hz_out * 4 == Fs_Hz_in) {
                S.FIR_Fracs = 1;
                S.FIR_Order = ResamplerRom.RESAMPLER_DOWN_ORDER_FIR2;
                S.Coefs = ResamplerRom.silk_Resampler_1_4_COEFS;
            } else if (Fs_Hz_out * 6 == Fs_Hz_in) {
                S.FIR_Fracs = 1;
                S.FIR_Order = ResamplerRom.RESAMPLER_DOWN_ORDER_FIR2;
                S.Coefs = ResamplerRom.silk_Resampler_1_6_COEFS;
            } else {
                return -1;
            }
        } else {
            S.resampler_function = USE_silk_resampler_copy;
        }
        S.invRatio_Q16 = ((Fs_Hz_in << (14 + up2x)) / Fs_Hz_out) << 2;
        while (smulww(S.invRatio_Q16, Fs_Hz_out) < (Fs_Hz_in << up2x)) {
            S.invRatio_Q16++;
        }
        return 0;
    }

    /**
     * Resamples a block of input ({@code silk_resampler}).
     *
     * <p>The output holds {@code inLen * Fs_out / Fs_in} samples.
     *
     * @param S      the state
     * @param out    receives the output signal
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param inLen  the number of input samples, at least one millisecond
     * @return zero
     */
    public static int resampler(ResamplerState S, short[] out, int outOff, short[] in, int inOff, int inLen) {
        var nSamples = S.Fs_in_kHz - S.inputDelay;
        System.arraycopy(in, inOff, S.delayBuf, S.inputDelay, nSamples);
        switch (S.resampler_function) {
            case USE_silk_resampler_private_up2_HQ_wrapper -> {
                ResamplerPrivate.up2HqWrapper(S, out, outOff, S.delayBuf, 0, S.Fs_in_kHz);
                ResamplerPrivate.up2HqWrapper(S, out, outOff + S.Fs_out_kHz, in, inOff + nSamples,
                        inLen - S.Fs_in_kHz);
            }
            case USE_silk_resampler_private_IIR_FIR -> {
                ResamplerPrivate.iirFir(S, out, outOff, S.delayBuf, 0, S.Fs_in_kHz);
                ResamplerPrivate.iirFir(S, out, outOff + S.Fs_out_kHz, in, inOff + nSamples, inLen - S.Fs_in_kHz);
            }
            case USE_silk_resampler_private_down_FIR -> {
                ResamplerPrivate.downFir(S, out, outOff, S.delayBuf, 0, S.Fs_in_kHz);
                ResamplerPrivate.downFir(S, out, outOff + S.Fs_out_kHz, in, inOff + nSamples, inLen - S.Fs_in_kHz);
            }
            default -> {
                System.arraycopy(S.delayBuf, 0, out, outOff, S.Fs_in_kHz);
                System.arraycopy(in, inOff + nSamples, out, outOff + S.Fs_out_kHz, inLen - S.Fs_in_kHz);
            }
        }
        System.arraycopy(in, inOff + inLen - S.inputDelay, S.delayBuf, 0, S.inputDelay);
        return 0;
    }
}
