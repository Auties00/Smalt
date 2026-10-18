/* Ports libopus 1.5.2 (silk/gain_quant.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_DELTA_GAIN_QUANT;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_QGAIN_DB;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MIN_DELTA_GAIN_QUANT;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MIN_QGAIN_DB;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.N_LEVELS_QGAIN;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.limit;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Quantizes and dequantizes the subframe gains on a uniform log scale with hysteresis.
 *
 * <p>The previous index is an {@code opus_int8} passed by pointer in the reference; here it is
 * passed by value and the updated value is returned.
 */
public final class GainQuant {
    /**
     * The log-domain offset of the lowest quantization level, in Q7.
     */
    private static final int OFFSET = (MIN_QGAIN_DB * 128) / 6 + 16 * 128;

    /**
     * The scale from the Q7 log domain to quantization indices, in Q16.
     */
    private static final int SCALE_Q16 = (65536 * (N_LEVELS_QGAIN - 1)) / (((MAX_QGAIN_DB - MIN_QGAIN_DB) * 128) / 6);

    /**
     * The scale from quantization indices to the Q7 log domain, in Q16.
     */
    private static final int INV_SCALE_Q16 = (65536 * (((MAX_QGAIN_DB - MIN_QGAIN_DB) * 128) / 6)) / (N_LEVELS_QGAIN - 1);

    private GainQuant() {
        throw new AssertionError();
    }

    /**
     * Quantizes the gains with hysteresis and replaces them with their quantized values
     * ({@code silk_gains_quant}).
     *
     * @param ind         receives the gain indices
     * @param gain_Q16    the gains in Q16; replaced with the quantized gains
     * @param prev_ind    the last index of the previous frame
     * @param conditional whether the first gain is delta coded
     * @param nb_subfr    the number of subframes
     * @return the updated last index
     */
    public static byte gainsQuant(byte[] ind, int[] gain_Q16, byte prev_ind, int conditional, int nb_subfr) {
        for (var k = 0; k < nb_subfr; k++) {
            ind[k] = (byte) smulwb(SCALE_Q16, LinLog.lin2log(gain_Q16[k]) - OFFSET);
            if (ind[k] < prev_ind) {
                ind[k]++;
            }
            ind[k] = (byte) limit(ind[k], 0, N_LEVELS_QGAIN - 1);
            if (k == 0 && conditional == 0) {
                ind[k] = (byte) limit(ind[k], prev_ind + MIN_DELTA_GAIN_QUANT, N_LEVELS_QGAIN - 1);
                prev_ind = ind[k];
            } else {
                ind[k] = (byte) (ind[k] - prev_ind);
                var double_step_size_threshold = 2 * MAX_DELTA_GAIN_QUANT - N_LEVELS_QGAIN + prev_ind;
                if (ind[k] > double_step_size_threshold) {
                    ind[k] = (byte) (double_step_size_threshold + ((ind[k] - double_step_size_threshold + 1) >> 1));
                }
                ind[k] = (byte) limit(ind[k], MIN_DELTA_GAIN_QUANT, MAX_DELTA_GAIN_QUANT);
                if (ind[k] > double_step_size_threshold) {
                    prev_ind = (byte) (prev_ind + ((ind[k] << 1) - double_step_size_threshold));
                    prev_ind = (byte) Math.min(prev_ind, N_LEVELS_QGAIN - 1);
                } else {
                    prev_ind = (byte) (prev_ind + ind[k]);
                }
                ind[k] = (byte) (ind[k] - MIN_DELTA_GAIN_QUANT);
            }
            gain_Q16[k] = LinLog.log2lin(Math.min(smulwb(INV_SCALE_Q16, prev_ind) + OFFSET, 3967));
        }
        return prev_ind;
    }

    /**
     * Dequantizes the gain indices ({@code silk_gains_dequant}).
     *
     * <p>Every index value is accepted: the accumulated index is clamped to the valid range.
     *
     * @param gain_Q16    receives the quantized gains in Q16
     * @param ind         the gain indices
     * @param prev_ind    the last index of the previous frame
     * @param conditional whether the first gain is delta coded
     * @param nb_subfr    the number of subframes
     * @return the updated last index
     */
    public static byte gainsDequant(int[] gain_Q16, byte[] ind, byte prev_ind, int conditional, int nb_subfr) {
        for (var k = 0; k < nb_subfr; k++) {
            if (k == 0 && conditional == 0) {
                prev_ind = (byte) Math.max(ind[k], prev_ind - 16);
            } else {
                var ind_tmp = ind[k] + MIN_DELTA_GAIN_QUANT;
                var double_step_size_threshold = 2 * MAX_DELTA_GAIN_QUANT - N_LEVELS_QGAIN + prev_ind;
                if (ind_tmp > double_step_size_threshold) {
                    prev_ind = (byte) (prev_ind + ((ind_tmp << 1) - double_step_size_threshold));
                } else {
                    prev_ind = (byte) (prev_ind + ind_tmp);
                }
            }
            prev_ind = (byte) limit(prev_ind, 0, N_LEVELS_QGAIN - 1);
            gain_Q16[k] = LinLog.log2lin(Math.min(smulwb(INV_SCALE_Q16, prev_ind) + OFFSET, 3967));
        }
        return prev_ind;
    }

    /**
     * Computes a unique identifier of a gain index vector ({@code silk_gains_ID}).
     *
     * @param ind      the gain indices
     * @param nb_subfr the number of subframes
     * @return the identifier
     */
    public static int gainsID(byte[] ind, int nb_subfr) {
        var gainsID = 0;
        for (var k = 0; k < nb_subfr; k++) {
            gainsID = ind[k] + (gainsID << 8);
        }
        return gainsID;
    }
}
