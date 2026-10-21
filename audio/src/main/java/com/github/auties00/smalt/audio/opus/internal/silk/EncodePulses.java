/* Ports libopus 1.5.2 (silk/encode_pulses.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;

/**
 * Entropy codes the quantized excitation of a SILK frame: rate level, pulse counts per shell
 * block, shell-coded pulse positions, least significant bits and signs.
 *
 * <p>An instance holds the scratch of one encoder channel.
 */
final class EncodePulses {
    /**
     * The maximum number of shell blocks of a frame, including the padding block of 10 ms frames
     * at 12 kHz.
     */
    private static final int MAX_ITER = MAX_NB_SHELL_BLOCKS + 1;

    /**
     * The pulse counts of the shell block being combined.
     */
    private final int[] pulses_comb = new int[8];

    /**
     * The absolute pulse values, possibly downscaled.
     */
    private final int[] abs_pulses = new int[MAX_ITER * SHELL_CODEC_FRAME_LENGTH];

    /**
     * The pulse count of each shell block.
     */
    private final int[] sum_pulses = new int[MAX_ITER];

    /**
     * The number of downscaling shifts of each shell block.
     */
    private final int[] nRshifts = new int[MAX_ITER];

    /**
     * The pair sums of a shell block.
     */
    private final int[] shell1 = new int[8];

    /**
     * The quadruple sums of a shell block.
     */
    private final int[] shell2 = new int[4];

    /**
     * The octuple sums of a shell block.
     */
    private final int[] shell3 = new int[2];

    /**
     * The total of a shell block.
     */
    private final int[] shell4 = new int[1];

    /**
     * The two-symbol inverse CDF of one sign.
     */
    private final short[] signIcdf = new short[2];

    /**
     * Creates the encoder scratch.
     */
    EncodePulses() {
    }

    /**
     * Sums adjacent pairs of pulse counts and reports whether any sum exceeds a limit
     * ({@code combine_and_check}).
     *
     * @param pulses_comb receives the {@code len} sums
     * @param combOff     the index of the first sum
     * @param pulses_in   the {@code 2 * len} counts
     * @param inOff       the index of the first count
     * @param max_pulses  the largest allowed sum
     * @param len         the number of sums
     * @return {@code 1} if a sum exceeds {@code max_pulses}, else {@code 0}
     */
    private static int combineAndCheck(int[] pulses_comb, int combOff, int[] pulses_in, int inOff, int max_pulses,
                                       int len) {
        for (var k = 0; k < len; k++) {
            var sum = pulses_in[inOff + 2 * k] + pulses_in[inOff + 2 * k + 1];
            if (sum > max_pulses) {
                return 1;
            }
            pulses_comb[combOff + k] = sum;
        }
        return 0;
    }

    /**
     * Encodes the quantization indices of the excitation ({@code silk_encode_pulses}).
     *
     * @param psRangeEnc      the range encoder
     * @param signalType      the signal type
     * @param quantOffsetType the quantization offset type
     * @param pulses          the pulses, with room for one padding shell block after the frame
     * @param frame_length    the frame length
     */
    void encodePulses(RangeEncoder psRangeEnc, int signalType, int quantOffsetType, byte[] pulses,
                             int frame_length) {
        int i, k, j, iter, bit, nLS, scale_down, RateLevelIndex = 0;
        int abs_q, minSumBits_Q5, sumBits_Q5;
        var pulses_comb = this.pulses_comb;
        var abs_pulses = this.abs_pulses;
        var sum_pulses = this.sum_pulses;
        var nRshifts = this.nRshifts;
        Arrays.fill(pulses_comb, 0);

        iter = frame_length >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        if (iter * SHELL_CODEC_FRAME_LENGTH < frame_length) {
            iter++;
            for (i = frame_length; i < frame_length + SHELL_CODEC_FRAME_LENGTH; i++) {
                pulses[i] = 0;
            }
        }

        for (i = 0; i < iter * SHELL_CODEC_FRAME_LENGTH; i++) {
            abs_pulses[i] = Math.abs(pulses[i]);
        }

        var abs_pulses_ptr = 0;
        for (i = 0; i < iter; i++) {
            nRshifts[i] = 0;

            while (true) {
                scale_down = combineAndCheck(pulses_comb, 0, abs_pulses, abs_pulses_ptr, Tables.silk_max_pulses_table[0], 8);
                scale_down += combineAndCheck(pulses_comb, 0, pulses_comb, 0, Tables.silk_max_pulses_table[1], 4);
                scale_down += combineAndCheck(pulses_comb, 0, pulses_comb, 0, Tables.silk_max_pulses_table[2], 2);
                scale_down += combineAndCheck(sum_pulses, i, pulses_comb, 0, Tables.silk_max_pulses_table[3], 1);

                if (scale_down != 0) {
                    nRshifts[i]++;
                    for (k = 0; k < SHELL_CODEC_FRAME_LENGTH; k++) {
                        abs_pulses[abs_pulses_ptr + k] = abs_pulses[abs_pulses_ptr + k] >> 1;
                    }
                } else {
                    break;
                }
            }
            abs_pulses_ptr += SHELL_CODEC_FRAME_LENGTH;
        }

        minSumBits_Q5 = Integer.MAX_VALUE;
        for (k = 0; k < N_RATE_LEVELS - 1; k++) {
            var nBits_ptr = Tables.silk_pulses_per_block_BITS_Q5[k];
            sumBits_Q5 = Tables.silk_rate_levels_BITS_Q5[signalType >> 1][k];
            for (i = 0; i < iter; i++) {
                if (nRshifts[i] > 0) {
                    sumBits_Q5 += nBits_ptr[SILK_MAX_PULSES + 1];
                } else {
                    sumBits_Q5 += nBits_ptr[sum_pulses[i]];
                }
            }
            if (sumBits_Q5 < minSumBits_Q5) {
                minSumBits_Q5 = sumBits_Q5;
                RateLevelIndex = k;
            }
        }
        psRangeEnc.encIcdf(RateLevelIndex, Tables.silk_rate_levels_iCDF[signalType >> 1], 8);

        var cdf_ptr = Tables.silk_pulses_per_block_iCDF[RateLevelIndex];
        for (i = 0; i < iter; i++) {
            if (nRshifts[i] == 0) {
                psRangeEnc.encIcdf(sum_pulses[i], cdf_ptr, 8);
            } else {
                psRangeEnc.encIcdf(SILK_MAX_PULSES + 1, cdf_ptr, 8);
                for (k = 0; k < nRshifts[i] - 1; k++) {
                    psRangeEnc.encIcdf(SILK_MAX_PULSES + 1, Tables.silk_pulses_per_block_iCDF[N_RATE_LEVELS - 1], 8);
                }
                psRangeEnc.encIcdf(sum_pulses[i], Tables.silk_pulses_per_block_iCDF[N_RATE_LEVELS - 1], 8);
            }
        }

        for (i = 0; i < iter; i++) {
            if (sum_pulses[i] > 0) {
                ShellCoder.shellEncoder(psRangeEnc, abs_pulses, i * SHELL_CODEC_FRAME_LENGTH, shell1, shell2, shell3,
                        shell4);
            }
        }

        for (i = 0; i < iter; i++) {
            if (nRshifts[i] > 0) {
                var pulses_ptr = i * SHELL_CODEC_FRAME_LENGTH;
                nLS = nRshifts[i] - 1;
                for (k = 0; k < SHELL_CODEC_FRAME_LENGTH; k++) {
                    abs_q = (byte) Math.abs(pulses[pulses_ptr + k]);
                    for (j = nLS; j > 0; j--) {
                        bit = (abs_q >> j) & 1;
                        psRangeEnc.encIcdf(bit, Tables.silk_lsb_iCDF, 8);
                    }
                    bit = abs_q & 1;
                    psRangeEnc.encIcdf(bit, Tables.silk_lsb_iCDF, 8);
                }
            }
        }

        CodeSigns.encodeSigns(psRangeEnc, pulses, 0, frame_length, signalType, quantOffsetType, sum_pulses, signIcdf);
    }
}
