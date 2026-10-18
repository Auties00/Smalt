/* Ports libopus 1.5.2 (silk/code_signs.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LOG2_SHELL_CODEC_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SHELL_CODEC_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;

/**
 * Codes the signs of the nonzero excitation pulses.
 */
public final class CodeSigns {
    private CodeSigns() {
        throw new AssertionError();
    }

    /**
     * Encodes the signs of the excitation pulses ({@code silk_encode_signs}).
     *
     * @param psRangeEnc      the range encoder
     * @param pulses          the pulse signal
     * @param pulsesOff       the index of the first pulse
     * @param length          the number of pulses
     * @param signalType      the signal type
     * @param quantOffsetType the quantization offset type
     * @param sum_pulses      the sum of absolute pulses of each shell block
     */
    public static void encodeSigns(RangeEncoder psRangeEnc, byte[] pulses, int pulsesOff, int length, int signalType,
                                   int quantOffsetType, int[] sum_pulses) {
        var icdf = new short[2];
        var q_ptr = pulsesOff;
        var i = smulbb(7, quantOffsetType + (signalType << 1));
        var icdf_ptr = i;
        length = (length + SHELL_CODEC_FRAME_LENGTH / 2) >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        for (i = 0; i < length; i++) {
            var p = sum_pulses[i];
            if (p > 0) {
                icdf[0] = Tables.silk_sign_iCDF[icdf_ptr + Math.min(p & 0x1F, 6)];
                for (var j = 0; j < SHELL_CODEC_FRAME_LENGTH; j++) {
                    if (pulses[q_ptr + j] != 0) {
                        psRangeEnc.encIcdf((pulses[q_ptr + j] >> 15) + 1, icdf, 8);
                    }
                }
            }
            q_ptr += SHELL_CODEC_FRAME_LENGTH;
        }
    }

    /**
     * Decodes the signs of the excitation pulses and applies them in place
     * ({@code silk_decode_signs}).
     *
     * @param psRangeDec      the range decoder
     * @param pulses          the pulse magnitudes; receives the signed pulses
     * @param length          the number of pulses
     * @param signalType      the signal type
     * @param quantOffsetType the quantization offset type
     * @param sum_pulses      the sum of absolute pulses of each shell block
     */
    public static void decodeSigns(RangeDecoder psRangeDec, short[] pulses, int length, int signalType,
                                   int quantOffsetType, int[] sum_pulses) {
        decodeSigns(psRangeDec, pulses, length, signalType, quantOffsetType, sum_pulses, new short[2]);
    }

    /**
     * Decodes the signs of the excitation pulses using a caller-provided distribution array
     * ({@code silk_decode_signs}).
     *
     * @param psRangeDec      the range decoder
     * @param pulses          the pulse magnitudes; receives the signed pulses
     * @param length          the number of pulses
     * @param signalType      the signal type
     * @param quantOffsetType the quantization offset type
     * @param sum_pulses      the sum of absolute pulses of each shell block
     * @param icdf            a work array of two elements, overwritten
     */
    public static void decodeSigns(RangeDecoder psRangeDec, short[] pulses, int length, int signalType,
                                   int quantOffsetType, int[] sum_pulses, short[] icdf) {
        icdf[1] = 0;
        var q_ptr = 0;
        var i = smulbb(7, quantOffsetType + (signalType << 1));
        var icdf_ptr = i;
        length = (length + SHELL_CODEC_FRAME_LENGTH / 2) >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        for (i = 0; i < length; i++) {
            var p = sum_pulses[i];
            if (p > 0) {
                icdf[0] = Tables.silk_sign_iCDF[icdf_ptr + Math.min(p & 0x1F, 6)];
                for (var j = 0; j < SHELL_CODEC_FRAME_LENGTH; j++) {
                    if (pulses[q_ptr + j] > 0) {
                        pulses[q_ptr + j] = (short) (pulses[q_ptr + j] * ((psRangeDec.decIcdf(icdf, 8) << 1) - 1));
                    }
                }
            }
            q_ptr += SHELL_CODEC_FRAME_LENGTH;
        }
    }
}
