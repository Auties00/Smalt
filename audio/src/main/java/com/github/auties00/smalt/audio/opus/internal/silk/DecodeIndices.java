/* Ports libopus 1.5.2 (silk/decode_indices.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CODE_CONDITIONALLY;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CODE_INDEPENDENTLY;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SUBFR;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.NLSF_QUANT_MAX_AMPLITUDE;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_VOICED;

/**
 * Decodes the side information indices of a SILK frame.
 */
public final class DecodeIndices {
    private DecodeIndices() {
        throw new AssertionError();
    }

    /**
     * Decodes the side information indices into {@code psDec.indices} ({@code silk_decode_indices}).
     *
     * <p>Every decoded index lies within its table range, so any bitstream content is accepted.
     *
     * @param psDec       the channel state
     * @param psRangeDec  the range decoder
     * @param FrameIndex  the index of the frame within the packet
     * @param decode_LBRR nonzero when LBRR data is decoded
     * @param condCoding  the conditional coding type
     */
    public static void decodeIndices(DecoderState psDec, RangeDecoder psRangeDec, int FrameIndex, int decode_LBRR,
                                     int condCoding) {
        var ec_ix = psDec.scratchEc_ix;
        var pred_Q8 = psDec.scratchPred_Q8;
        var indices = psDec.indices;
        int Ix;
        if (decode_LBRR != 0 || psDec.VAD_flags[FrameIndex] != 0) {
            Ix = psRangeDec.decIcdf(Tables.silk_type_offset_VAD_iCDF, 8) + 2;
        } else {
            Ix = psRangeDec.decIcdf(Tables.silk_type_offset_no_VAD_iCDF, 8);
        }
        indices.signalType = (byte) (Ix >> 1);
        indices.quantOffsetType = (byte) (Ix & 1);
        if (condCoding == CODE_CONDITIONALLY) {
            indices.GainsIndices[0] = (byte) psRangeDec.decIcdf(Tables.silk_delta_gain_iCDF, 8);
        } else {
            indices.GainsIndices[0] = (byte) (psRangeDec.decIcdf(Tables.silk_gain_iCDF[indices.signalType], 8) << 3);
            indices.GainsIndices[0] += (byte) psRangeDec.decIcdf(Tables.silk_uniform8_iCDF, 8);
        }
        for (var i = 1; i < psDec.nb_subfr; i++) {
            indices.GainsIndices[i] = (byte) psRangeDec.decIcdf(Tables.silk_delta_gain_iCDF, 8);
        }
        var cb = psDec.psNLSF_CB;
        indices.NLSFIndices[0] = (byte) psRangeDec.decIcdf(cb.CB1_iCDF, (indices.signalType >> 1) * cb.nVectors, 8);
        NlsfDecode.nlsfUnpack(ec_ix, pred_Q8, cb, indices.NLSFIndices[0]);
        for (var i = 0; i < cb.order; i++) {
            Ix = psRangeDec.decIcdf(cb.ec_iCDF, ec_ix[i], 8);
            if (Ix == 0) {
                Ix -= psRangeDec.decIcdf(Tables.silk_NLSF_EXT_iCDF, 8);
            } else if (Ix == 2 * NLSF_QUANT_MAX_AMPLITUDE) {
                Ix += psRangeDec.decIcdf(Tables.silk_NLSF_EXT_iCDF, 8);
            }
            indices.NLSFIndices[i + 1] = (byte) (Ix - NLSF_QUANT_MAX_AMPLITUDE);
        }
        if (psDec.nb_subfr == MAX_NB_SUBFR) {
            indices.NLSFInterpCoef_Q2 = (byte) psRangeDec.decIcdf(Tables.silk_NLSF_interpolation_factor_iCDF, 8);
        } else {
            indices.NLSFInterpCoef_Q2 = 4;
        }
        if (indices.signalType == TYPE_VOICED) {
            var decode_absolute_lagIndex = 1;
            if (condCoding == CODE_CONDITIONALLY && psDec.ec_prevSignalType == TYPE_VOICED) {
                int delta_lagIndex = (short) psRangeDec.decIcdf(Tables.silk_pitch_delta_iCDF, 8);
                if (delta_lagIndex > 0) {
                    delta_lagIndex = delta_lagIndex - 9;
                    indices.lagIndex = (short) (psDec.ec_prevLagIndex + delta_lagIndex);
                    decode_absolute_lagIndex = 0;
                }
            }
            if (decode_absolute_lagIndex != 0) {
                indices.lagIndex = (short) (psRangeDec.decIcdf(Tables.silk_pitch_lag_iCDF, 8) * (psDec.fs_kHz >> 1));
                indices.lagIndex += (short) psRangeDec.decIcdf(psDec.pitch_lag_low_bits_iCDF, 8);
            }
            psDec.ec_prevLagIndex = indices.lagIndex;
            indices.contourIndex = (byte) psRangeDec.decIcdf(psDec.pitch_contour_iCDF, 8);
            indices.PERIndex = (byte) psRangeDec.decIcdf(Tables.silk_LTP_per_index_iCDF, 8);
            for (var k = 0; k < psDec.nb_subfr; k++) {
                indices.LTPIndex[k] = (byte) psRangeDec.decIcdf(Tables.silk_LTP_gain_iCDF_ptrs[indices.PERIndex], 8);
            }
            if (condCoding == CODE_INDEPENDENTLY) {
                indices.LTP_scaleIndex = (byte) psRangeDec.decIcdf(Tables.silk_LTPscale_iCDF, 8);
            } else {
                indices.LTP_scaleIndex = 0;
            }
        }
        psDec.ec_prevSignalType = indices.signalType;
        indices.Seed = (byte) psRangeDec.decIcdf(Tables.silk_uniform4_iCDF, 8);
    }
}
