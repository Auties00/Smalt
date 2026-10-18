/* Ports libopus 1.5.2 (silk/decode_pulses.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LOG2_SHELL_CODEC_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SHELL_BLOCKS;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.N_RATE_LEVELS;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SHELL_CODEC_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SILK_MAX_PULSES;

/**
 * Decodes the quantized excitation of a SILK frame.
 */
public final class DecodePulses {
    private DecodePulses() {
        throw new AssertionError();
    }

    /**
     * Decodes the excitation pulses of a frame ({@code silk_decode_pulses}).
     *
     * <p>The pulse array must hold the frame rounded up to a whole number of 16-sample shell
     * blocks. The number of least significant bit layers per block is bounded at ten, so every
     * loop terminates on any input.
     *
     * @param psRangeDec      the range decoder
     * @param pulses          receives the signed pulses
     * @param signalType      the signal type
     * @param quantOffsetType the quantization offset type
     * @param frame_length    the frame length in samples
     */
    public static void decodePulses(RangeDecoder psRangeDec, short[] pulses, int signalType, int quantOffsetType,
                                    int frame_length) {
        decodePulses(psRangeDec, pulses, signalType, quantOffsetType, frame_length, new int[MAX_NB_SHELL_BLOCKS],
                new int[MAX_NB_SHELL_BLOCKS], new short[2]);
    }

    /**
     * Decodes the excitation pulses of a frame using caller-provided work arrays
     * ({@code silk_decode_pulses}).
     *
     * @param psRangeDec      the range decoder
     * @param pulses          receives the signed pulses
     * @param signalType      the signal type
     * @param quantOffsetType the quantization offset type
     * @param frame_length    the frame length in samples
     * @param sum_pulses      a work array of {@link Define#MAX_NB_SHELL_BLOCKS} elements, overwritten
     * @param nLshifts        a work array of {@link Define#MAX_NB_SHELL_BLOCKS} elements, overwritten
     * @param signIcdf        a work array of two elements for the sign distribution, overwritten
     */
    public static void decodePulses(RangeDecoder psRangeDec, short[] pulses, int signalType, int quantOffsetType,
                                    int frame_length, int[] sum_pulses, int[] nLshifts, short[] signIcdf) {
        var RateLevelIndex = psRangeDec.decIcdf(Tables.silk_rate_levels_iCDF[signalType >> 1], 8);
        var iter = frame_length >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        if (iter * SHELL_CODEC_FRAME_LENGTH < frame_length) {
            iter++;
        }
        var cdf_ptr = Tables.silk_pulses_per_block_iCDF[RateLevelIndex];
        for (var i = 0; i < iter; i++) {
            nLshifts[i] = 0;
            sum_pulses[i] = psRangeDec.decIcdf(cdf_ptr, 8);
            while (sum_pulses[i] == SILK_MAX_PULSES + 1) {
                nLshifts[i]++;
                sum_pulses[i] = psRangeDec.decIcdf(Tables.silk_pulses_per_block_iCDF[N_RATE_LEVELS - 1],
                        nLshifts[i] == 10 ? 1 : 0, 8);
            }
        }
        for (var i = 0; i < iter; i++) {
            if (sum_pulses[i] > 0) {
                ShellCoder.shellDecoder(pulses, i * SHELL_CODEC_FRAME_LENGTH, psRangeDec, sum_pulses[i]);
            } else {
                Arrays.fill(pulses, i * SHELL_CODEC_FRAME_LENGTH, (i + 1) * SHELL_CODEC_FRAME_LENGTH, (short) 0);
            }
        }
        for (var i = 0; i < iter; i++) {
            if (nLshifts[i] > 0) {
                var nLS = nLshifts[i];
                var pulses_ptr = i * SHELL_CODEC_FRAME_LENGTH;
                for (var k = 0; k < SHELL_CODEC_FRAME_LENGTH; k++) {
                    int abs_q = pulses[pulses_ptr + k];
                    for (var j = 0; j < nLS; j++) {
                        abs_q = abs_q << 1;
                        abs_q += psRangeDec.decIcdf(Tables.silk_lsb_iCDF, 8);
                    }
                    pulses[pulses_ptr + k] = (short) abs_q;
                }
                sum_pulses[i] |= nLS << 5;
            }
        }
        CodeSigns.decodeSigns(psRangeDec, pulses, frame_length, signalType, quantOffsetType, sum_pulses, signIcdf);
    }
}
