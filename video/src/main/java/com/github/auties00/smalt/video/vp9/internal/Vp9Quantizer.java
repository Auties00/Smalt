package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Quantizes the transform coefficients of the VP9 encoder and holds the quantizer parameters of
 * every quantizer index.
 *
 * <p>Two quantizers exist: the regular one with a dead zone, used for intra blocks and key frames
 * ({@link #quantizeB}), and the fast one of real-time inter frames ({@link #quantizeFp}). Both
 * walk the coefficients in scan order and return the end of block, the number of coded
 * coefficients in scan order. Parameters are kept per quantizer index and per plane type, the DC
 * value at {@code 2 * qindex} and the AC value at {@code 2 * qindex + 1}.
 *
 * @implNote This implementation ports libvpx's {@code vp9_init_quantizer} and
 *           {@code vp9_quantize_fp_c} ({@code vp9/encoder/vp9_quantize.c}) and
 *           {@code vpx_quantize_b_c} ({@code vpx_dsp/quantize.c}), BSD-3, with no sharpness
 *           and no chroma delta. The dequantized value of a coefficient is computed as the
 *           decoder computes it from the coded token, so reconstruction matches it exactly.
 */
final class Vp9Quantizer {
    /**
     * The luma quantizer multipliers, {@code [qindex][dc/ac]}.
     */
    final int[] yQuant = new int[512];

    /**
     * The luma quantizer shifts.
     */
    final int[] yQuantShift = new int[512];

    /**
     * The luma dead zones.
     */
    final int[] yZbin = new int[512];

    /**
     * The luma rounding offsets.
     */
    final int[] yRound = new int[512];

    /**
     * The luma fast quantizer multipliers.
     */
    final int[] yQuantFp = new int[512];

    /**
     * The luma fast quantizer rounding offsets.
     */
    final int[] yRoundFp = new int[512];

    /**
     * The luma dequantization steps.
     */
    final int[] yDequant = new int[512];

    /**
     * The chroma quantizer multipliers.
     */
    final int[] uvQuant = new int[512];

    /**
     * The chroma quantizer shifts.
     */
    final int[] uvQuantShift = new int[512];

    /**
     * The chroma dead zones.
     */
    final int[] uvZbin = new int[512];

    /**
     * The chroma rounding offsets.
     */
    final int[] uvRound = new int[512];

    /**
     * The chroma fast quantizer multipliers.
     */
    final int[] uvQuantFp = new int[512];

    /**
     * The chroma fast quantizer rounding offsets.
     */
    final int[] uvRoundFp = new int[512];

    /**
     * The chroma dequantization steps.
     */
    final int[] uvDequant = new int[512];

    /**
     * The rounding offset of the fast quantizer for DC coefficients, in 1/128 of a step.
     *
     * @implNote This implementation rounds at 30/128 where libvpx rounds at 48/128, a wider dead
     *           zone. With the one level lower loop filter of inter frames it lowered the bitrate
     *           at equal quality by 3.0% PSNR-Y and 3.7% SSIM-Y on average over six clips (johnny
     *           at 480p and 720p, kriste, stock, foreman, stockholm; kriste gains 1.0% SSIM-Y and
     *           loses 0.2% PSNR-Y, johnny at 720p gains 7.0% and 8.7%), against libvpx realtime
     *           cpu-used 6 on johnny at 720p from -7.0% PSNR-Y and -7.8% SSIM-Y to -14.1% and
     *           -16.5%, for 7% more cycles per frame (the finer quantizer the rate control then
     *           picks leaves fewer blocks to skip).
     */
    private static final int FAST_ROUNDING_DC = 30;

    /**
     * The rounding offset of the fast quantizer for AC coefficients, in 1/128 of a step.
     *
     * @implNote This implementation rounds at 24/128 where libvpx rounds at 42/128.
     */
    private static final int FAST_ROUNDING_AC = 24;

    /**
     * Builds the parameters of every quantizer index of 8-bit content ({@code vp9_init_quantizer}).
     */
    Vp9Quantizer() {
        this(8);
    }

    /**
     * Builds the parameters of every quantizer index of a bit depth ({@code vp9_init_quantizer}).
     *
     * @param bitDepth the bit depth, 8, 10 or 12
     */
    Vp9Quantizer(int bitDepth) {
        var zbinThreshold = 148 << (bitDepth - 8);
        for (var q = 0; q < 256; q++) {
            var dc = Vp9EncTables.dcQuant(q, 0, bitDepth);
            var qzbinFactor = q == 0 ? 64 : (dc < zbinThreshold ? 84 : 80);
            var qroundingFactor = q == 0 ? 64 : 48;
            for (var i = 0; i < 2; i++) {
                var qroundingFactorFp = i == 0 ? FAST_ROUNDING_DC : FAST_ROUNDING_AC;
                if (q == 0) {
                    qroundingFactorFp = 64;
                }
                var index = q * 2 + i;
                var quant = i == 0 ? dc : Vp9EncTables.acQuant(q, 0, bitDepth);
                fill(index, quant, qzbinFactor, qroundingFactor, qroundingFactorFp, yQuant, yQuantShift, yZbin, yRound,
                        yQuantFp, yRoundFp, yDequant);
                fill(index, quant, qzbinFactor, qroundingFactor, qroundingFactorFp, uvQuant, uvQuantShift, uvZbin,
                        uvRound, uvQuantFp, uvRoundFp, uvDequant);
            }
        }
    }

    /**
     * Fills the parameters of one step.
     *
     * @param index             the index of the parameters
     * @param quant             the quantizer step
     * @param qzbinFactor       the dead zone factor in 1/128
     * @param qroundingFactor   the rounding factor in 1/128
     * @param qroundingFactorFp the fast rounding factor in 1/128
     * @param quantArr          receives the multiplier
     * @param shiftArr          receives the shift
     * @param zbinArr           receives the dead zone
     * @param roundArr          receives the rounding offset
     * @param quantFpArr        receives the fast multiplier
     * @param roundFpArr        receives the fast rounding offset
     * @param dequantArr        receives the step
     */
    private static void fill(int index, int quant, int qzbinFactor, int qroundingFactor, int qroundingFactorFp,
                             int[] quantArr, int[] shiftArr, int[] zbinArr, int[] roundArr, int[] quantFpArr,
                             int[] roundFpArr, int[] dequantArr) {
        var l = 31 - Integer.numberOfLeadingZeros(quant);
        var m = 1 + (1 << (16 + l)) / quant;
        quantArr[index] = (short) (m - (1 << 16));
        shiftArr[index] = 1 << (16 - l);
        quantFpArr[index] = (1 << 16) / quant;
        roundFpArr[index] = (qroundingFactorFp * quant) >> 7;
        zbinArr[index] = (qzbinFactor * quant + 64) >> 7;
        roundArr[index] = (qroundingFactor * quant) >> 7;
        dequantArr[index] = quant;
    }

    /**
     * Clamps a value to the 16-bit signed range.
     *
     * @param v the value
     * @return the clamped value
     */
    private static int clamp16(int v) {
        return v < -32768 ? -32768 : (v > 32767 ? 32767 : v);
    }

    /**
     * Quantizes a block with the fast quantizer ({@code vp9_quantize_fp_c} and, for 32x32,
     * {@code vp9_quantize_fp_32x32_c}).
     *
     * @param coeff     the coefficients in raster order
     * @param off       the index of the first coefficient of the block in all three arrays
     * @param n         the number of coefficients
     * @param qcoeff    receives the quantized coefficients
     * @param dqcoeff   receives the dequantized coefficients
     * @param scan      the scan order
     * @param round     the rounding offsets array
     * @param quant     the multipliers array
     * @param dequant   the steps array
     * @param pidx      the index of the DC parameters ({@code 2 * qindex})
     * @param is32x32   whether the block is 32x32, whose coefficients are scaled by one half
     * @return the end of block
     */
    static int quantizeFp(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] round,
                          int[] quant, int[] dequant, int pidx, boolean is32x32) {
        var eob = -1;
        for (var i = 0; i < n; i++) {
            qcoeff[off + i] = 0;
            dqcoeff[off + i] = 0;
        }
        if (!is32x32) {
            for (var i = 0; i < n; i++) {
                var rc = scan[i];
                var ac = rc != 0 ? 1 : 0;
                var c = coeff[off + rc];
                var sign = c >> 31;
                var abs = (c ^ sign) - sign;
                var tmp = clamp16(abs + round[pidx + ac]);
                tmp = (tmp * quant[pidx + ac]) >> 16;
                if (tmp != 0) {
                    var q = (tmp ^ sign) - sign;
                    qcoeff[off + rc] = q;
                    dqcoeff[off + rc] = (tmp * dequant[pidx + ac] ^ sign) - sign;
                    eob = i;
                }
            }
        } else {
            for (var i = 0; i < n; i++) {
                var rc = scan[i];
                var ac = rc != 0 ? 1 : 0;
                var c = coeff[off + rc];
                var sign = c >> 31;
                var abs = (c ^ sign) - sign;
                var tmp = 0;
                var dq = dequant[pidx + ac];
                if (abs >= (dq >> 2)) {
                    abs += (round[pidx + ac] + 1) >> 1;
                    abs = clamp16(abs);
                    tmp = (abs * quant[pidx + ac]) >> 15;
                    if (tmp != 0) {
                        qcoeff[off + rc] = (tmp ^ sign) - sign;
                        dqcoeff[off + rc] = ((tmp * dq) / 2 ^ sign) - sign;
                    }
                }
                if (tmp != 0) {
                    eob = i;
                }
            }
        }
        return eob + 1;
    }

    /**
     * Quantizes a high bit depth block with the fast quantizer ({@code vp9_highbd_quantize_fp_c}
     * and, for 32x32, {@code vp9_highbd_quantize_fp_32x32_c}): the arithmetic of
     * {@link #quantizeFp} without clamping the rounded magnitude to 16 bits.
     *
     * @param coeff     the coefficients in raster order
     * @param off       the index of the first coefficient of the block in all three arrays
     * @param n         the number of coefficients
     * @param qcoeff    receives the quantized coefficients
     * @param dqcoeff   receives the dequantized coefficients
     * @param scan      the scan order
     * @param round     the rounding offsets array
     * @param quant     the multipliers array
     * @param dequant   the steps array
     * @param pidx      the index of the DC parameters ({@code 2 * qindex})
     * @param is32x32   whether the block is 32x32, whose coefficients are scaled by one half
     * @return the end of block
     */
    static int quantizeFpHigh(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] round,
                              int[] quant, int[] dequant, int pidx, boolean is32x32) {
        Arrays.fill(qcoeff, off, off + n, 0);
        Arrays.fill(dqcoeff, off, off + n, 0);
        var eob = -1;
        for (var i = 0; i < n; i++) {
            var rc = scan[i];
            var ac = rc != 0 ? 1 : 0;
            var c = coeff[off + rc];
            var sign = c >> 31;
            var abs = (c ^ sign) - sign;
            var dq = dequant[pidx + ac];
            int tmp;
            if (!is32x32) {
                tmp = (int) (((long) abs + round[pidx + ac]) * quant[pidx + ac] >> 16);
            } else if (abs >= (dq >> 2)) {
                tmp = (int) (((long) abs + ((round[pidx + ac] + 1) >> 1)) * quant[pidx + ac] >> 15);
            } else {
                tmp = 0;
            }
            if (tmp != 0) {
                var q = (tmp ^ sign) - sign;
                qcoeff[off + rc] = q;
                dqcoeff[off + rc] = is32x32 ? q * dq / 2 : q * dq;
                eob = i;
            }
        }
        return eob + 1;
    }

    /**
     * Quantizes a high bit depth block with the regular dead zone quantizer
     * ({@code vpx_highbd_quantize_b_c} and, for 32x32, {@code vpx_highbd_quantize_b_32x32_c}):
     * the arithmetic of {@link #quantizeB} without clamping the rounded magnitude to 16 bits.
     *
     * @param coeff      the coefficients in raster order
     * @param off        the index of the first coefficient of the block in all three arrays
     * @param n          the number of coefficients
     * @param qcoeff     receives the quantized coefficients
     * @param dqcoeff    receives the dequantized coefficients
     * @param scan       the scan order
     * @param zbin       the dead zones array
     * @param round      the rounding offsets array
     * @param quant      the multipliers array
     * @param quantShift the shifts array
     * @param dequant    the steps array
     * @param pidx       the index of the DC parameters ({@code 2 * qindex})
     * @param is32x32    whether the block is 32x32
     * @return the end of block
     */
    static int quantizeBHigh(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] zbin,
                             int[] round, int[] quant, int[] quantShift, int[] dequant, int pidx, boolean is32x32) {
        Arrays.fill(qcoeff, off, off + n, 0);
        Arrays.fill(dqcoeff, off, off + n, 0);
        var zbin0 = is32x32 ? (zbin[pidx] + 1) >> 1 : zbin[pidx];
        var zbin1 = is32x32 ? (zbin[pidx + 1] + 1) >> 1 : zbin[pidx + 1];
        var nonZero = n;
        if (!is32x32) {
            for (var i = n - 1; i >= 0; i--) {
                var rc = scan[i];
                var c = coeff[off + rc];
                var z = rc != 0 ? zbin1 : zbin0;
                if (c < z && c > -z) {
                    nonZero--;
                } else {
                    break;
                }
            }
        }
        var eob = -1;
        for (var i = 0; i < nonZero; i++) {
            var rc = scan[i];
            var ac = rc != 0 ? 1 : 0;
            var c = coeff[off + rc];
            var sign = c >> 31;
            var abs = (c ^ sign) - sign;
            if (abs < (ac != 0 ? zbin1 : zbin0)) {
                continue;
            }
            var tmp1 = (long) abs + (is32x32 ? (round[pidx + ac] + 1) >> 1 : round[pidx + ac]);
            var tmp2 = ((tmp1 * quant[pidx + ac]) >> 16) + tmp1;
            var tmp = (int) ((tmp2 * quantShift[pidx + ac]) >> (is32x32 ? 15 : 16));
            if (tmp != 0) {
                var q = (tmp ^ sign) - sign;
                qcoeff[off + rc] = q;
                dqcoeff[off + rc] = is32x32 ? q * dequant[pidx + ac] / 2 : q * dequant[pidx + ac];
                eob = i;
            }
        }
        return eob + 1;
    }

    /**
     * Quantizes a block with the regular dead zone quantizer ({@code vpx_quantize_b_c} and, for
     * 32x32, {@code vpx_quantize_b_32x32_c}).
     *
     * @param coeff      the coefficients in raster order
     * @param off        the index of the first coefficient of the block in all three arrays
     * @param n          the number of coefficients
     * @param qcoeff     receives the quantized coefficients
     * @param dqcoeff    receives the dequantized coefficients
     * @param scan       the scan order
     * @param zbin       the dead zones array
     * @param round      the rounding offsets array
     * @param quant      the multipliers array
     * @param quantShift the shifts array
     * @param dequant    the steps array
     * @param pidx       the index of the DC parameters ({@code 2 * qindex})
     * @param is32x32    whether the block is 32x32
     * @return the end of block
     */
    static int quantizeB(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] zbin,
                         int[] round, int[] quant, int[] quantShift, int[] dequant, int pidx, boolean is32x32) {
        for (var i = 0; i < n; i++) {
            qcoeff[off + i] = 0;
            dqcoeff[off + i] = 0;
        }
        var eob = -1;
        if (!is32x32) {
            var zbin0 = zbin[pidx];
            var zbin1 = zbin[pidx + 1];
            var nonZero = n;
            for (var i = n - 1; i >= 0; i--) {
                var rc = scan[i];
                var c = coeff[off + rc];
                var z = rc != 0 ? zbin1 : zbin0;
                if (c < z && c > -z) {
                    nonZero--;
                } else {
                    break;
                }
            }
            for (var i = 0; i < nonZero; i++) {
                var rc = scan[i];
                var ac = rc != 0 ? 1 : 0;
                var c = coeff[off + rc];
                var sign = c >> 31;
                var abs = (c ^ sign) - sign;
                if (abs >= (ac != 0 ? zbin1 : zbin0)) {
                    var tmp = clamp16(abs + round[pidx + ac]);
                    tmp = ((((tmp * quant[pidx + ac]) >> 16) + tmp) * quantShift[pidx + ac]) >> 16;
                    if (tmp != 0) {
                        qcoeff[off + rc] = (tmp ^ sign) - sign;
                        dqcoeff[off + rc] = (tmp * dequant[pidx + ac] ^ sign) - sign;
                        eob = i;
                    }
                }
            }
        } else {
            var zbin0 = (zbin[pidx] + 1) >> 1;
            var zbin1 = (zbin[pidx + 1] + 1) >> 1;
            for (var i = 0; i < n; i++) {
                var rc = scan[i];
                var c = coeff[off + rc];
                var z = rc != 0 ? zbin1 : zbin0;
                if (c >= z || c <= -z) {
                    var ac = rc != 0 ? 1 : 0;
                    var sign = c >> 31;
                    var abs = (c ^ sign) - sign;
                    abs += (round[pidx + ac] + 1) >> 1;
                    abs = clamp16(abs);
                    var tmp = ((((abs * quant[pidx + ac]) >> 16) + abs) * quantShift[pidx + ac]) >> 15;
                    if (tmp != 0) {
                        qcoeff[off + rc] = (tmp ^ sign) - sign;
                        dqcoeff[off + rc] = ((tmp * dequant[pidx + ac]) / 2 ^ sign) - sign;
                        eob = i;
                    }
                }
            }
        }
        return eob + 1;
    }
}
