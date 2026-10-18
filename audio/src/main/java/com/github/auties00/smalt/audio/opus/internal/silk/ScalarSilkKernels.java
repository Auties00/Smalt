/* Ports libopus 1.5.2 (silk/resampler_private_IIR_FIR.c, silk/resampler_private_down_FIR.c). Copyright Skype Limited;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.ResamplerRom.RESAMPLER_DOWN_ORDER_FIR0;
import static com.github.auties00.smalt.audio.opus.internal.silk.ResamplerRom.RESAMPLER_DOWN_ORDER_FIR1;
import static com.github.auties00.smalt.audio.opus.internal.silk.ResamplerRom.RESAMPLER_DOWN_ORDER_FIR2;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Implements {@link SilkKernels} in plain Java; this is the reference implementation, in the evaluation order of the
 * libopus C code.
 */
public final class ScalarSilkKernels implements SilkKernels {
    /**
     * The 8-tap coefficient rows of the 12 phases of the upsampling interpolator, flattened: row
     * {@code t} holds the four taps of {@code silk_resampler_frac_FIR_12[t]} followed by the four
     * taps of {@code silk_resampler_frac_FIR_12[11 - t]} in reverse, the order in which the
     * reference applies them.
     */
    static final int[] FRAC_FIR_12_ROWS = buildRows();

    /**
     * Creates the scalar kernels.
     */
    public ScalarSilkKernels() {
    }

    /**
     * Builds {@link #FRAC_FIR_12_ROWS} from {@code silk_resampler_frac_FIR_12}.
     *
     * @return the flattened rows
     */
    private static int[] buildRows() {
        var frac = ResamplerRom.silk_resampler_frac_FIR_12;
        var rows = new int[12 * 8];
        for (var t = 0; t < 12; t++) {
            for (var i = 0; i < 4; i++) {
                rows[t * 8 + i] = frac[t][i];
                rows[t * 8 + 4 + i] = frac[11 - t][3 - i];
            }
        }
        return rows;
    }

    @Override
    public int iirFirInterpol(short[] out, int outOff, short[] buf, int max_index_Q16, int index_increment_Q16,
                              int[] scratch) {
        var rows = FRAC_FIR_12_ROWS;
        for (var index_Q16 = 0; index_Q16 < max_index_Q16; index_Q16 += index_increment_Q16) {
            var table_index = smulwb(index_Q16 & 0xFFFF, 12);
            var p = index_Q16 >> 16;
            var r = table_index << 3;
            var res_Q15 = buf[p] * rows[r];
            res_Q15 = res_Q15 + buf[p + 1] * rows[r + 1];
            res_Q15 = res_Q15 + buf[p + 2] * rows[r + 2];
            res_Q15 = res_Q15 + buf[p + 3] * rows[r + 3];
            res_Q15 = res_Q15 + buf[p + 4] * rows[r + 4];
            res_Q15 = res_Q15 + buf[p + 5] * rows[r + 5];
            res_Q15 = res_Q15 + buf[p + 6] * rows[r + 6];
            res_Q15 = res_Q15 + buf[p + 7] * rows[r + 7];
            out[outOff++] = (short) sat16(rshiftRound(res_Q15, 15));
        }
        return outOff;
    }

    @Override
    public int downFirInterpol(short[] out, int outOff, int[] buf, short[] coefs, int coefsOff, int FIR_Order,
                               int FIR_Fracs, int max_index_Q16, int index_increment_Q16, int[] scratch) {
        switch (FIR_Order) {
            case RESAMPLER_DOWN_ORDER_FIR0 -> {
                for (var index_Q16 = 0; index_Q16 < max_index_Q16; index_Q16 += index_increment_Q16) {
                    var p = index_Q16 >> 16;
                    var interpol_ind = smulwb(index_Q16 & 0xFFFF, FIR_Fracs);
                    var ip = coefsOff + RESAMPLER_DOWN_ORDER_FIR0 / 2 * interpol_ind;
                    var res_Q6 = smulwb(buf[p], coefs[ip]);
                    for (var i = 1; i < 9; i++) {
                        res_Q6 = smlawb(res_Q6, buf[p + i], coefs[ip + i]);
                    }
                    ip = coefsOff + RESAMPLER_DOWN_ORDER_FIR0 / 2 * (FIR_Fracs - 1 - interpol_ind);
                    for (var i = 0; i < 9; i++) {
                        res_Q6 = smlawb(res_Q6, buf[p + 17 - i], coefs[ip + i]);
                    }
                    out[outOff++] = (short) sat16(rshiftRound(res_Q6, 6));
                }
            }
            case RESAMPLER_DOWN_ORDER_FIR1 -> {
                for (var index_Q16 = 0; index_Q16 < max_index_Q16; index_Q16 += index_increment_Q16) {
                    var p = index_Q16 >> 16;
                    var res_Q6 = smulwb(buf[p] + buf[p + 23], coefs[coefsOff]);
                    for (var i = 1; i < 12; i++) {
                        res_Q6 = smlawb(res_Q6, buf[p + i] + buf[p + 23 - i], coefs[coefsOff + i]);
                    }
                    out[outOff++] = (short) sat16(rshiftRound(res_Q6, 6));
                }
            }
            case RESAMPLER_DOWN_ORDER_FIR2 -> {
                for (var index_Q16 = 0; index_Q16 < max_index_Q16; index_Q16 += index_increment_Q16) {
                    var p = index_Q16 >> 16;
                    var res_Q6 = smulwb(buf[p] + buf[p + 35], coefs[coefsOff]);
                    for (var i = 1; i < 18; i++) {
                        res_Q6 = smlawb(res_Q6, buf[p + i] + buf[p + 35 - i], coefs[coefsOff + i]);
                    }
                    out[outOff++] = (short) sat16(rshiftRound(res_Q6, 6));
                }
            }
            default -> {
            }
        }
        return outOff;
    }

    @Override
    public void lpcAnalysisFilter(short[] out, int outOff, short[] in, int inOff, short[] B, int BOff, int len, int d) {
        lpcAnalysisFilterTail(out, outOff, in, inOff, B, BOff, d, len, d);
    }

    @Override
    public void nsqDotProducts(int[] dd, int stride, int lpcOffset, int[] lpcCoef, int arOffset, int[] arCoef,
                               int[] out) {
        for (var k = 0; k < 4; k++) {
            var base = k * stride;
            var lpc = 0;
            for (var m = 0; m < 16; m++) {
                lpc += smulwb(dd[base + lpcOffset + m], lpcCoef[m]);
            }
            var ar = 0;
            for (var m = 0; m < 24; m++) {
                ar += smulwb(dd[base + arOffset + m], arCoef[m]);
            }
            out[k] = lpc;
            out[4 + k] = ar;
        }
    }

    /**
     * Computes the residual outputs {@code [from, len)} of
     * {@link #lpcAnalysisFilter(short[], int, short[], int, short[], int, int, int)}.
     *
     * @param out    receives the residual
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param B      the prediction coefficients in Q12
     * @param BOff   the index of the first coefficient
     * @param from   the first output position, at least {@code d}
     * @param len    the number of samples
     * @param d      the filter order
     */
    void lpcAnalysisFilterTail(short[] out, int outOff, short[] in, int inOff, short[] B, int BOff, int from, int len,
                               int d) {
        for (var ix = from; ix < len; ix++) {
            var in_ptr = inOff + ix - 1;
            var out32_Q12 = in[in_ptr] * B[BOff];
            out32_Q12 = out32_Q12 + in[in_ptr - 1] * B[BOff + 1];
            out32_Q12 = out32_Q12 + in[in_ptr - 2] * B[BOff + 2];
            out32_Q12 = out32_Q12 + in[in_ptr - 3] * B[BOff + 3];
            out32_Q12 = out32_Q12 + in[in_ptr - 4] * B[BOff + 4];
            out32_Q12 = out32_Q12 + in[in_ptr - 5] * B[BOff + 5];
            for (var j = 6; j < d; j += 2) {
                out32_Q12 = out32_Q12 + in[in_ptr - j] * B[BOff + j];
                out32_Q12 = out32_Q12 + in[in_ptr - j - 1] * B[BOff + j + 1];
            }
            out32_Q12 = (in[in_ptr + 1] << 12) - out32_Q12;
            out[outOff + ix] = (short) sat16(rshiftRound(out32_Q12, 12));
        }
    }
}
