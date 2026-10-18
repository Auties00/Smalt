/* Ports libopus 1.5.2 (silk/encode_indices.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;

/**
 * Entropy codes the side information of a SILK frame: signal type, gains, NLSFs, pitch and LTP
 * parameters, and the dither seed.
 *
 * <p>An instance holds the scratch of one encoder channel.
 */
final class EncodeIndices {
    /**
     * The entropy-table indices of the first-stage NLSF vector.
     */
    private final short[] ec_ix = new short[MAX_LPC_ORDER];

    /**
     * The backward prediction coefficients of the first-stage NLSF vector.
     */
    private final short[] pred_Q8 = new short[MAX_LPC_ORDER];

    /**
     * Creates the encoder scratch.
     */
    EncodeIndices() {
    }

    /**
     * Encodes the side information of one frame ({@code silk_encode_indices}).
     *
     * @param psEncC      the encoder state; {@code ec_prevLagIndex} and {@code ec_prevSignalType}
     *                    are updated
     * @param psRangeEnc  the range encoder
     * @param frameIndex  the frame number within the packet
     * @param encode_LBRR {@code 1} to encode the LBRR indices of that frame
     * @param condCoding  the type of conditional coding
     */
    void encodeIndices(EncoderState psEncC, RangeEncoder psRangeEnc, int frameIndex, int encode_LBRR,
                              int condCoding) {
        int i, k, typeOffset;
        int encode_absolute_lagIndex, delta_lagIndex;
        var ec_ix = this.ec_ix;
        var pred_Q8 = this.pred_Q8;
        SideInfoIndices psIndices;

        if (encode_LBRR != 0) {
            psIndices = psEncC.indices_LBRR[frameIndex];
        } else {
            psIndices = psEncC.indices;
        }

        typeOffset = 2 * psIndices.signalType + psIndices.quantOffsetType;
        if (encode_LBRR != 0 || typeOffset >= 2) {
            psRangeEnc.encIcdf(typeOffset - 2, Tables.silk_type_offset_VAD_iCDF, 8);
        } else {
            psRangeEnc.encIcdf(typeOffset, Tables.silk_type_offset_no_VAD_iCDF, 8);
        }

        if (condCoding == CODE_CONDITIONALLY) {
            psRangeEnc.encIcdf(psIndices.GainsIndices[0], Tables.silk_delta_gain_iCDF, 8);
        } else {
            psRangeEnc.encIcdf(psIndices.GainsIndices[0] >> 3, Tables.silk_gain_iCDF[psIndices.signalType], 8);
            psRangeEnc.encIcdf(psIndices.GainsIndices[0] & 7, Tables.silk_uniform8_iCDF, 8);
        }

        for (i = 1; i < psEncC.nb_subfr; i++) {
            psRangeEnc.encIcdf(psIndices.GainsIndices[i], Tables.silk_delta_gain_iCDF, 8);
        }

        var cb = psEncC.psNLSF_CB;
        psRangeEnc.encIcdf(psIndices.NLSFIndices[0], cb.CB1_iCDF, (psIndices.signalType >> 1) * cb.nVectors, 8);
        NlsfDecode.nlsfUnpack(ec_ix, pred_Q8, cb, psIndices.NLSFIndices[0]);
        for (i = 0; i < cb.order; i++) {
            if (psIndices.NLSFIndices[i + 1] >= NLSF_QUANT_MAX_AMPLITUDE) {
                psRangeEnc.encIcdf(2 * NLSF_QUANT_MAX_AMPLITUDE, cb.ec_iCDF, ec_ix[i], 8);
                psRangeEnc.encIcdf(psIndices.NLSFIndices[i + 1] - NLSF_QUANT_MAX_AMPLITUDE, Tables.silk_NLSF_EXT_iCDF, 8);
            } else if (psIndices.NLSFIndices[i + 1] <= -NLSF_QUANT_MAX_AMPLITUDE) {
                psRangeEnc.encIcdf(0, cb.ec_iCDF, ec_ix[i], 8);
                psRangeEnc.encIcdf(-psIndices.NLSFIndices[i + 1] - NLSF_QUANT_MAX_AMPLITUDE, Tables.silk_NLSF_EXT_iCDF, 8);
            } else {
                psRangeEnc.encIcdf(psIndices.NLSFIndices[i + 1] + NLSF_QUANT_MAX_AMPLITUDE, cb.ec_iCDF, ec_ix[i], 8);
            }
        }

        if (psEncC.nb_subfr == MAX_NB_SUBFR) {
            psRangeEnc.encIcdf(psIndices.NLSFInterpCoef_Q2, Tables.silk_NLSF_interpolation_factor_iCDF, 8);
        }

        if (psIndices.signalType == TYPE_VOICED) {
            encode_absolute_lagIndex = 1;
            if (condCoding == CODE_CONDITIONALLY && psEncC.ec_prevSignalType == TYPE_VOICED) {
                delta_lagIndex = psIndices.lagIndex - psEncC.ec_prevLagIndex;
                if (delta_lagIndex < -8 || delta_lagIndex > 11) {
                    delta_lagIndex = 0;
                } else {
                    delta_lagIndex = delta_lagIndex + 9;
                    encode_absolute_lagIndex = 0;
                }
                psRangeEnc.encIcdf(delta_lagIndex, Tables.silk_pitch_delta_iCDF, 8);
            }
            if (encode_absolute_lagIndex != 0) {
                int pitch_high_bits, pitch_low_bits;
                pitch_high_bits = psIndices.lagIndex / (psEncC.fs_kHz >> 1);
                pitch_low_bits = psIndices.lagIndex - SigProcFix.smulbb(pitch_high_bits, psEncC.fs_kHz >> 1);
                psRangeEnc.encIcdf(pitch_high_bits, Tables.silk_pitch_lag_iCDF, 8);
                psRangeEnc.encIcdf(pitch_low_bits, psEncC.pitch_lag_low_bits_iCDF, 8);
            }
            psEncC.ec_prevLagIndex = psIndices.lagIndex;

            psRangeEnc.encIcdf(psIndices.contourIndex, psEncC.pitch_contour_iCDF, 8);

            psRangeEnc.encIcdf(psIndices.PERIndex, Tables.silk_LTP_per_index_iCDF, 8);

            for (k = 0; k < psEncC.nb_subfr; k++) {
                psRangeEnc.encIcdf(psIndices.LTPIndex[k], Tables.silk_LTP_gain_iCDF_ptrs[psIndices.PERIndex], 8);
            }

            if (condCoding == CODE_INDEPENDENTLY) {
                psRangeEnc.encIcdf(psIndices.LTP_scaleIndex, Tables.silk_LTPscale_iCDF, 8);
            }
        }

        psEncC.ec_prevSignalType = psIndices.signalType;

        psRangeEnc.encIcdf(psIndices.Seed, Tables.silk_uniform4_iCDF, 8);
    }
}
