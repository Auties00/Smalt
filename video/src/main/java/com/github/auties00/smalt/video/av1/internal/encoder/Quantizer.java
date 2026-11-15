package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.DequantTables;

/**
 * Quantizes and dequantizes AV1 transform coefficients for one plane at one quantizer index.
 *
 * <p>Quantization follows the encoder's dead-zone rule; dequantization reproduces the decoder's
 * arithmetic exactly, so the encoder's reconstruction matches what a decoder computes from the
 * coded levels.
 *
 * <p>The derivation, the dead-zone loop and the dequantization are static so that
 * {@link StillQuant} shares them.
 *
 * @implNote This implementation ports libaom's {@code av1_build_quantizer} and {@code invert_quant}
 * ({@code av1/encoder/av1_quantize.c}) and {@code aom_quantize_b_helper_c} ({@code aom_dsp/quantize.c})
 * without quantizer matrices; dequantization mirrors the decoder's coefficient reader.
 */
final class Quantizer {
    /**
     * The bit depth of the samples, 8, 10 or 12.
     */
    private final int bitDepth;

    /**
     * The largest magnitude of a dequantized coefficient, {@code (128 << bitDepth) - 1}.
     */
    private final int cfMax;

    /**
     * The 8-bit dequantizers of the DC and AC coefficients at the same quantizer index and deltas,
     * the steps the analysis models and thresholds use at every bit depth.
     */
    final int[] analysisDequant = new int[2];

    /**
     * The dead-zone thresholds of the DC and AC coefficients.
     */
    final int[] zbin = new int[2];

    /**
     * The rounding offsets of the DC and AC coefficients.
     */
    final int[] round = new int[2];

    /**
     * The rounding offsets of the DC and AC coefficients before the levels are optimized by rate
     * and distortion.
     */
    final int[] roundRd = new int[2];

    /**
     * The rounding factor in 1/128 units of the levels the level optimization starts from: the
     * nearest level.
     */
    private static final int RD_ROUND = 64;

    /**
     * The fixed-point reciprocal multipliers of the DC and AC coefficients.
     */
    final int[] quant = new int[2];

    /**
     * The fixed-point reciprocal shifts of the DC and AC coefficients.
     */
    final int[] quantShift = new int[2];

    /**
     * The fast-path reciprocal multipliers of the DC and AC coefficients ({@code quant_fp}).
     */
    final int[] quantFp = new int[2];

    /**
     * The fast-path rounding offsets of the DC and AC coefficients ({@code round_fp}).
     */
    final int[] roundFp = new int[2];

    /**
     * The dequantizers of the DC and AC coefficients.
     */
    final int[] dequant = new int[2];

    /**
     * Creates an unset quantizer of 8-bit samples.
     */
    Quantizer() {
        this(8);
    }

    /**
     * Creates an unset quantizer of a bit depth.
     *
     * @param bitDepth the bit depth, 8, 10 or 12
     */
    Quantizer(int bitDepth) {
        this.bitDepth = bitDepth;
        this.cfMax = (128 << bitDepth) - 1;
    }

    /**
     * Returns the 8-bit DC dequantizer of a quantizer index ({@code av1_dc_quant_QTX}).
     *
     * @param qindex the quantizer index
     * @param delta the DC delta
     * @return the dequantizer
     */
    static int dcQuant(int qindex, int delta) {
        return DequantTables.DQ[Math.clamp(qindex + delta, 0, 255) * 2];
    }

    /**
     * Returns the 8-bit AC dequantizer of a quantizer index ({@code av1_ac_quant_QTX}).
     *
     * @param qindex the quantizer index
     * @param delta the AC delta
     * @return the dequantizer
     */
    static int acQuant(int qindex, int delta) {
        return DequantTables.DQ[Math.clamp(qindex + delta, 0, 255) * 2 + 1];
    }

    /**
     * Returns the DC dequantizer of a quantizer index at a bit depth ({@code av1_dc_quant_QTX}).
     *
     * @param qindex the quantizer index
     * @param delta the DC delta
     * @param hbd 0 for 8 bits, 1 for 10 bits, 2 for 12 bits
     * @return the dequantizer
     */
    static int dcQuant(int qindex, int delta, int hbd) {
        return DequantTables.DQ[hbd * 512 + Math.clamp(qindex + delta, 0, 255) * 2];
    }

    /**
     * Returns the AC dequantizer of a quantizer index at a bit depth ({@code av1_ac_quant_QTX}).
     *
     * @param qindex the quantizer index
     * @param delta the AC delta
     * @param hbd 0 for 8 bits, 1 for 10 bits, 2 for 12 bits
     * @return the dequantizer
     */
    static int acQuant(int qindex, int delta, int hbd) {
        return DequantTables.DQ[hbd * 512 + Math.clamp(qindex + delta, 0, 255) * 2 + 1];
    }

    /**
     * Sets the parameters for a quantizer index and the plane's deltas.
     *
     * @param qindex the quantizer index
     * @param dcDelta the DC delta
     * @param acDelta the AC delta
     */
    void set(int qindex, int dcDelta, int acDelta) {
        var hbd = (bitDepth - 8) >> 1;
        // the 8-bit threshold 148 of get_qzbin_factor, scaled by four for each two bits
        var zbinFactor = qindex == 0 ? 64 : (dcQuant(qindex, 0, hbd) < 148 << (bitDepth - 8) ? 84 : 80);
        var roundFactor = qindex == 0 ? 64 : 48;
        for (var i = 0; i < 2; i++) {
            var q = i == 0 ? dcQuant(qindex, dcDelta, hbd) : acQuant(qindex, acDelta, hbd);
            analysisDequant[i] = i == 0 ? dcQuant(qindex, dcDelta) : acQuant(qindex, acDelta);
            build(q, zbinFactor, roundFactor, i, quant, quantShift, zbin, round);
            quantFp[i] = (1 << 16) / q;
            roundFp[i] = (64 * q) >> 7;
            roundRd[i] = ((qindex == 0 ? 64 : RD_ROUND) * q) >> 7;
            dequant[i] = q;
        }
    }

    /**
     * Derives the reciprocal multiplier and shift, the dead-zone threshold and the rounding offset
     * of a quantizer step ({@code av1_build_quantizer}, {@code invert_quant}).
     *
     * @param q the quantizer step, at least 4
     * @param zbinFactor the dead-zone threshold, in 1/128 of the step
     * @param roundFactor the rounding offset, in 1/128 of the step
     * @param index the entry to write in each array
     * @param quant receives the reciprocal multiplier
     * @param quantShift receives the reciprocal shift, as a power of two
     * @param zbin receives the dead-zone threshold
     * @param round receives the rounding offset
     */
    static void build(int q, int zbinFactor, int roundFactor, int index, int[] quant, int[] quantShift, int[] zbin,
                      int[] round) {
        var l = 31 - Integer.numberOfLeadingZeros(q);
        var m = 1 + (1 << (16 + l)) / q;
        quant[index] = (short) (m - (1 << 16));
        quantShift[index] = 1 << (16 - l);
        zbin[index] = (zbinFactor * q + 64) >> 7;
        round[index] = (roundFactor * q) >> 7;
    }

    /**
     * Returns the dequantizer of the DC or AC coefficients.
     *
     * @param ac whether the AC dequantizer is wanted
     * @return the dequantizer
     */
    int dequant(boolean ac) {
        return dequant[ac ? 1 : 0];
    }

    /**
     * Quantizes the coefficients of one transform block with the dead-zone rule
     * ({@code aom_quantize_b}).
     *
     * @param coeff the transform coefficients, in the decoder's layout
     * @param nCoeffs the number of coded coefficients
     * @param scan the scan order
     * @param iscan the inverse of {@code scan}
     * @param logScale the transform's dequantization shift: 0, 1 or 2
     * @param qcoeff receives the signed levels, in the decoder's layout; entries outside the coded
     * region are cleared
     * @return the number of coefficients up to and including the last non-zero one in scan order
     */
    int quantizeB(int[] coeff, int nCoeffs, int[] scan, int[] iscan, int logScale, int[] qcoeff) {
        return quantizeB(coeff, nCoeffs, scan, iscan, logScale, qcoeff, round);
    }

    /**
     * Quantizes the coefficients of one transform block with the dead-zone rule and given rounding
     * offsets.
     *
     * @param coeff the transform coefficients, in the decoder's layout
     * @param nCoeffs the number of coded coefficients
     * @param scan the scan order
     * @param iscan the inverse of {@code scan}
     * @param logScale the transform's dequantization shift: 0, 1 or 2
     * @param qcoeff receives the signed levels, in the decoder's layout
     * @param round the rounding offsets of the DC and AC coefficients
     * @return the number of coefficients up to and including the last non-zero one in scan order
     */
    int quantizeB(int[] coeff, int nCoeffs, int[] scan, int[] iscan, int logScale, int[] qcoeff, int[] round) {
        return quantize(coeff, nCoeffs, scan, iscan, logScale, qcoeff, zbin, round, quant, quantShift, 0,
                bitDepth == 8 ? Short.MAX_VALUE : Integer.MAX_VALUE, bitDepth == 8);
    }

    /**
     * Quantizes the coefficients of one transform block with the dead-zone rule, the DC
     * coefficient with the parameters at {@code o} and every other one with those at {@code o + 1}
     * ({@code aom_quantize_b_helper_c}): a coefficient whose magnitude reaches its threshold has
     * the level {@code (((t * quant) >> 16) + t) * quantShift >> (16 - logScale)} with
     * {@code t = min(magnitude + round, limit)} and the coefficient's sign, any other has level 0.
     *
     * @param coeff the transform coefficients, in the decoder's layout
     * @param nCoeffs the number of coded coefficients
     * @param scan the scan order
     * @param iscan the inverse of {@code scan}
     * @param logScale the transform's dequantization shift: 0, 1 or 2
     * @param qcoeff receives the signed levels, in the decoder's layout
     * @param zbin the dead-zone thresholds
     * @param round the rounding offsets
     * @param quant the reciprocal multipliers
     * @param quantShift the reciprocal shifts, as powers of two
     * @param o the index of the DC parameters in each array
     * @param limit the clamp of the rounded magnitude
     * @param vector whether the kernels may quantize blocks of a multiple of eight coefficients
     * @return the number of coefficients up to and including the last non-zero one in scan order
     */
    static int quantize(int[] coeff, int nCoeffs, int[] scan, int[] iscan, int logScale, int[] qcoeff, int[] zbin,
                        int[] round, int[] quant, int[] quantShift, int o, int limit, boolean vector) {
        var add = (1 << logScale) >> 1;
        var zbin0 = (zbin[o] + add) >> logScale;
        var zbin1 = (zbin[o + 1] + add) >> logScale;
        var round0 = (round[o] + add) >> logScale;
        var round1 = (round[o + 1] + add) >> logScale;
        if (vector && (nCoeffs & 7) == 0) {
            var finish = 16 - logScale;
            return EncDsp.select().quantizeDz(coeff, nCoeffs, iscan, qcoeff, zbin0, zbin1, round0, round1, quant[o],
                    quant[o + 1], finish - Integer.numberOfTrailingZeros(quantShift[o]),
                    finish - Integer.numberOfTrailingZeros(quantShift[o + 1]), limit);
        }
        var nonZero = nCoeffs;
        for (var i = nCoeffs - 1; i >= 0; i--) {
            var rc = scan[i];
            var c = coeff[rc];
            var z = rc != 0 ? zbin1 : zbin0;
            if (c < z && c > -z) {
                nonZero--;
                qcoeff[rc] = 0;
            } else {
                break;
            }
        }
        var eob = -1;
        var shift = 16 - logScale;
        for (var i = 0; i < nonZero; i++) {
            var rc = scan[i];
            var c = coeff[rc];
            var ac = rc != 0 ? 1 : 0;
            var abs = Math.abs(c);
            if (abs >= (ac != 0 ? zbin1 : zbin0)) {
                long tmp = Math.min(abs + (ac != 0 ? round1 : round0), limit);
                var level = (int) ((((tmp * quant[o + ac]) >> 16) + tmp) * quantShift[o + ac] >> shift);
                qcoeff[rc] = c < 0 ? -level : level;
                if (level != 0) {
                    eob = i;
                }
            } else {
                qcoeff[rc] = 0;
            }
        }
        return eob + 1;
    }

    /**
     * Dequantizes levels into decoder coefficients exactly as the decoder does, clearing the
     * levels beyond the end of block.
     *
     * @param qcoeff the signed levels, in the decoder's layout
     * @param eob the number of coded coefficients in scan order
     * @param scan the scan order
     * @param dqShift the transform's dequantization shift
     * @param out receives the dequantized coefficients; positions outside the scan prefix are left
     * untouched and must be zero
     */
    void dequantize(int[] qcoeff, int eob, int[] scan, int dqShift, int[] out) {
        dequantize(qcoeff, eob, scan, dqShift, dequant[0], dequant[1], cfMax, out);
    }

    /**
     * Dequantizes levels into decoder coefficients exactly as the decoder does: a level of
     * magnitude 15 or more keeps the low 20 bits of the magnitude, the product the low 24 bits, and
     * the result is clamped to {@code cfMax}.
     *
     * @param qcoeff the signed levels, in the decoder's layout
     * @param eob the number of coded coefficients in scan order
     * @param scan the scan order
     * @param dqShift the transform's dequantization shift
     * @param dcDq the DC dequantizer
     * @param acDq the AC dequantizer
     * @param cfMax the largest dequantized magnitude, {@code (128 << bitDepth) - 1}
     * @param out receives the dequantized coefficients; positions outside the scan prefix are left
     * untouched and must be zero
     */
    static void dequantize(int[] qcoeff, int eob, int[] scan, int dqShift, int dcDq, int acDq, int cfMax,
                           int[] out) {
        for (var i = 0; i < eob; i++) {
            var rc = scan[i];
            var level = qcoeff[rc];
            if (level == 0) {
                out[rc] = 0;
                continue;
            }
            var abs = Math.abs(level);
            var sign = level < 0 ? 1 : 0;
            var dq = rc != 0 ? acDq : dcDq;
            int v;
            if (abs >= 15) {
                v = ((dq * (abs & 0xfffff)) & 0xffffff) >> dqShift;
                v = Math.min(v, cfMax + sign);
            } else {
                v = (dq * abs) >> dqShift;
            }
            out[rc] = sign != 0 ? -v : v;
        }
    }
}
