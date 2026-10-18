package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Quantizes and dequantizes the transform coefficients of the three planes of a frame at any bit
 * depth.
 *
 * <p>Quantization follows the encoder's dead-zone rule; dequantization reproduces the decoder's
 * arithmetic exactly, including the clamping of large levels at the frame's bit depth, so the
 * encoder's reconstruction matches what a decoder computes from the coded levels. A frame coded at
 * quantizer index 0 with no deltas is lossless: every coefficient of its Walsh-Hadamard transforms
 * is a multiple of the step and is kept exactly.
 *
 * <p>The derivation, the dead-zone loop and the dequantization are {@link Quantizer}'s.
 *
 * @implNote This implementation ports libaom's {@code get_qzbin_factor} ({@code av1/encoder/av1_quantize.c})
 * and {@code aom_highbd_quantize_b_c} ({@code aom_dsp/quantize.c}) without quantizer matrices, which
 * equals the 8-bit {@code aom_quantize_b_c} for 8-bit coefficients.
 */
final class StillQuant {
    /**
     * The dead-zone thresholds, as {@code [plane * 2 + ac]}.
     */
    private final int[] zbin = new int[6];

    /**
     * The rounding offsets, as {@code [plane * 2 + ac]}.
     */
    private final int[] round = new int[6];

    /**
     * The fixed-point reciprocal multipliers, as {@code [plane * 2 + ac]}.
     */
    private final int[] quant = new int[6];

    /**
     * The fixed-point reciprocal shifts, as {@code [plane * 2 + ac]}.
     */
    private final int[] quantShift = new int[6];

    /**
     * The dequantizers, as {@code [plane * 2 + ac]}.
     */
    private final int[] dequant = new int[6];

    /**
     * The largest dequantized magnitude of a coefficient, {@code (128 << bitDepth) - 1}.
     */
    private final int cfMax;

    /**
     * Whether the frame is lossless.
     */
    private final boolean lossless;

    /**
     * Creates the quantizers of a frame.
     *
     * @param dq the decoder's dequantizers of segment 0, as {@code [plane 3][dc/ac 2]}
     * @param qindex the base quantizer index
     * @param bitDepth the bit depth: 8, 10 or 12
     * @param lossless whether the frame is lossless
     */
    StillQuant(int[] dq, int qindex, int bitDepth, boolean lossless) {
        this.cfMax = (128 << bitDepth) - 1;
        this.lossless = lossless;
        var dcBase = dq[0];
        var threshold = bitDepth == 8 ? 148 : bitDepth == 10 ? 592 : 2368;
        var zbinFactor = qindex == 0 ? 64 : dcBase < threshold ? 84 : 80;
        var roundFactor = qindex == 0 ? 64 : 48;
        for (var i = 0; i < 6; i++) {
            Quantizer.build(dq[i], zbinFactor, roundFactor, i, quant, quantShift, zbin, round);
            dequant[i] = dq[i];
        }
    }

    /**
     * Returns whether the frame is lossless.
     *
     * @return {@code true} for a lossless frame
     */
    boolean lossless() {
        return lossless;
    }

    /**
     * Returns the dequantizer of a plane's DC or AC coefficients.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param ac whether the AC dequantizer is wanted
     * @return the dequantizer
     */
    int dequant(int plane, boolean ac) {
        return dequant[plane * 2 + (ac ? 1 : 0)];
    }

    /**
     * Quantizes the coefficients of one transform block with the dead-zone rule, or exactly for a
     * lossless frame ({@code aom_highbd_quantize_b}).
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param coeff the transform coefficients, in the decoder's layout
     * @param nCoeffs the number of coded coefficients
     * @param scan the scan order
     * @param iscan the inverse of {@code scan}; ignored for a lossless frame
     * @param logScale the transform's dequantization shift: 0, 1 or 2
     * @param qcoeff receives the signed levels, in the decoder's layout; entries of the coded region
     * are all written
     * @return the number of coefficients up to and including the last non-zero one in scan order
     */
    int quantize(int plane, int[] coeff, int nCoeffs, int[] scan, int[] iscan, int logScale, int[] qcoeff) {
        // TODO: optimize the levels by rate-distortion after quantizing (libaom's av1_optimize_txb).
        if (lossless) {
            var eob = 0;
            for (var i = 0; i < nCoeffs; i++) {
                var rc = scan[i];
                var level = coeff[rc] / 4;
                qcoeff[rc] = level;
                if (level != 0) {
                    eob = i + 1;
                }
            }
            return eob;
        }
        return Quantizer.quantize(coeff, nCoeffs, scan, iscan, logScale, qcoeff, zbin, round, quant, quantShift,
                plane * 2, Integer.MAX_VALUE, cfMax == (128 << 8) - 1);
    }

    /**
     * Returns the squared error between the transform coefficients of a block and the
     * dequantization of their levels, in the coefficient domain.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param coeff the coefficients
     * @param levels the quantized levels of every coefficient
     * @param nCoeffs the number of coefficients
     * @param logScale the transform's dequantization shift
     * @return the squared error
     */
    long error(int plane, int[] coeff, int[] levels, int nCoeffs, int logScale) {
        var dcDq = dequant[plane * 2];
        var acDq = dequant[plane * 2 + 1];
        if ((nCoeffs & 7) == 0) {
            return EncDsp.select().levelError(coeff, levels, nCoeffs, dcDq, acDq, logScale);
        }
        long error = 0;
        var c0 = coeff[0];
        var l0 = levels[0];
        var d0 = (Math.abs(l0) * dcDq) >> logScale;
        var e0 = (long) c0 - (l0 < 0 ? -d0 : d0);
        error += e0 * e0;
        for (var i = 1; i < nCoeffs; i++) {
            var level = levels[i];
            long e;
            if (level == 0) {
                e = coeff[i];
            } else {
                var d = (Math.abs(level) * acDq) >> logScale;
                e = (long) coeff[i] - (level < 0 ? -d : d);
            }
            error += e * e;
        }
        return error;
    }

    /**
     * Dequantizes levels into decoder coefficients exactly as the decoder does.
     *
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param qcoeff the signed levels, in the decoder's layout
     * @param eob the number of coded coefficients in scan order
     * @param scan the scan order
     * @param dqShift the transform's dequantization shift
     * @param out receives the dequantized coefficients; positions outside the scan prefix are left
     * untouched and must be zero
     */
    void dequantize(int plane, int[] qcoeff, int eob, int[] scan, int dqShift, int[] out) {
        Quantizer.dequantize(qcoeff, eob, scan, dqShift, dequant[plane * 2], dequant[plane * 2 + 1], cfMax, out);
    }
}
