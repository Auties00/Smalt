/* Ports HM-16.21, the ITU-T / ISO/IEC H.265 reference software (source/Lib/TLibCommon/TComTrQuant.cpp and TComRom.cpp:
   the forward transform shifts, the g_quantScales table, the quantization of xQuant and the sign bit hiding of
   signBitHidingHDQ). Copyright (c) 2010-2017, ITU/ISO/IEC; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.internal.HevcTransform;
import com.github.auties00.smalt.video.hevc.internal.ScanTables;

/**
 * Computes the forward transforms of the encoder, the counterparts of the inverse transforms of ITU-T H.265
 * 8.6.4: the 4x4 to 32x32 integer DCT and the 4x4 DST of intra luma blocks, and quantizes and scales their
 * coefficients.
 *
 * <p>The forward transform is not normative; it uses the basis of the inverse transform with the shifts of
 * the HM reference encoder, so that the dynamic range of the coefficients matches the 16 bits the
 * quantization expects. Blocks are raster arrays indexed {@code y * size + x}. Instances are not thread-safe.
 */
final class ForwardTransform {
    /**
     * {@code quantScales} of the HM reference encoder, indexed by {@code qP % 6}.
     */
    private static final int[] QUANT_SCALE = {26214, 23302, 20560, 18396, 16384, 14564};

    /**
     * {@code levelScale} of equation 8-309, indexed by {@code qP % 6}.
     */
    static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};

    /**
     * The intermediate block after the horizontal pass, transposed.
     */
    private final int[] tmp = new int[32 * 32];

    /**
     * The even half of one row.
     */
    private final int[] even = new int[16];

    /**
     * The odd half of one row.
     */
    private final int[] odd = new int[16];

    /**
     * Constructs a transform with its own scratch memory.
     */
    ForwardTransform() {
    }

    /**
     * Transforms a block of residual samples into coefficients.
     *
     * @param residual the residual, {@code size * size} entries
     * @param log2Size the block size as a power of two, 2 to 5
     * @param dst whether the block uses the DST: 4x4 intra luma blocks
     * @param bitDepth the bit depth of the component
     * @param coeffs receives the coefficients, {@code size * size} entries
     */
    void forward(int[] residual, int log2Size, boolean dst, int bitDepth, int[] coeffs) {
        var size = 1 << log2Size;
        var shift1 = log2Size + bitDepth - 9;
        var shift2 = log2Size + 6;
        if (dst) {
            dst4(residual, tmp, shift1);
            dst4(tmp, coeffs, shift2);
            return;
        }
        pass(residual, tmp, size, log2Size, shift1);
        pass(tmp, coeffs, size, log2Size, shift2);
    }

    /**
     * Applies the one-dimensional DCT to every row of a block, writing the result transposed.
     *
     * <p>The sums fit in 32 bits for residuals of up to 10 bits: the first pass sums at most 16 products of
     * halves below {@code 2^12} and basis values of at most 90, and its shift leaves the second pass halves
     * below {@code 2^18}, whose sums stay below {@code 2^29}.
     *
     * @param in the rows, {@code size} entries each
     * @param out receives the transform of row {@code j} in column {@code j}
     * @param size the block size
     * @param log2Size the block size as a power of two
     * @param shift the right shift with rounding of the results
     */
    private void pass(int[] in, int[] out, int size, int log2Size, int shift) {
        var basis = HevcTransform.DCT32;
        var step = (32 >> log2Size) * 32;
        var half = size >> 1;
        var add = shift > 0 ? 1 << (shift - 1) : 0;
        var e = even;
        var o = odd;
        for (var j = 0; j < size; j++) {
            var row = j * size;
            for (var n = 0; n < half; n++) {
                var a = in[row + n];
                var b = in[row + size - 1 - n];
                e[n] = a + b;
                o[n] = a - b;
            }
            for (var k = 0; k < size; k += 2) {
                var base = k * step;
                var sum = 0;
                for (var n = 0; n < half; n++) {
                    sum += basis[base + n] * e[n];
                }
                out[k * size + j] = (sum + add) >> shift;
            }
            for (var k = 1; k < size; k += 2) {
                var base = k * step;
                var sum = 0;
                for (var n = 0; n < half; n++) {
                    sum += basis[base + n] * o[n];
                }
                out[k * size + j] = (sum + add) >> shift;
            }
        }
    }

    /**
     * Applies the one-dimensional DST to every row of a 4x4 block, writing the result transposed.
     *
     * @param in the rows
     * @param out receives the transform of row {@code j} in column {@code j}
     * @param shift the right shift with rounding of the results
     */
    private static void dst4(int[] in, int[] out, int shift) {
        var basis = HevcTransform.DST4;
        var add = 1 << (shift - 1);
        for (var j = 0; j < 4; j++) {
            for (var k = 0; k < 4; k++) {
                var sum = 0L;
                for (var n = 0; n < 4; n++) {
                    sum += (long) basis[k * 4 + n] * in[j * 4 + n];
                }
                out[k * 4 + j] = (int) ((sum + add) >> shift);
            }
        }
    }

    /**
     * The smallest distance in scan positions between the first and the last non-zero level of a 4x4
     * sub-block for which the sign of the first is hidden ({@code SBH_THRESHOLD}, 7.3.8.11).
     */
    private static final int SIGN_HIDING_DISTANCE = 4;

    /**
     * Quantizes transform coefficients with a dead zone and, when sign data hiding is on, adjusts the levels
     * so that the parity of every sub-block whose first sign is hidden gives that sign.
     *
     * @param coeffs the coefficients, {@code size * size} entries
     * @param log2Size the block size as a power of two
     * @param qp {@code qP}, including the bit depth offset
     * @param bitDepth the bit depth of the component
     * @param intra whether the block is intra, which rounds levels up more
     * @param levels receives the levels, clipped to 16 bits
     * @param offset the index of the block's top-left level in {@code levels}
     * @param stride the row stride of {@code levels}
     * @param scanIdx the scan order of the block, or -1 without sign data hiding
     * @param deltaU scratch of {@code size * size} entries for the rounding errors
     * @return the sum of the absolute levels
     */
    static int quantize(int[] coeffs, int log2Size, int qp, int bitDepth, boolean intra, short[] levels, int offset,
                        int stride, int scanIdx, int[] deltaU) {
        // TODO: rate-distortion optimized quantization (the levels chosen by their estimated bits, as HM's
        //  xRateDistOptQuant does) at the slower speeds
        var size = 1 << log2Size;
        var transformShift = 15 - bitDepth - log2Size;
        var qBits = 14 + qp / 6 + transformShift;
        var qBits8 = qBits - 8;
        var scale = QUANT_SCALE[qp % 6];
        var add = (long) (intra ? 171 : 85) << (qBits - 9);
        var sum = 0;
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                var c = coeffs[y * size + x];
                var scaled = (long) Math.abs(c) * scale;
                var level = (int) ((scaled + add) >> qBits);
                deltaU[y * size + x] = (int) ((scaled - ((long) level << qBits)) >> qBits8);
                level = Math.min(32767, level);
                sum += level;
                levels[row + x] = (short) (c < 0 ? -level : level);
            }
        }
        if (scanIdx >= 0 && sum >= 2) {
            sum += hideSigns(coeffs, log2Size, levels, offset, stride, scanIdx, deltaU);
        }
        return sum;
    }

    /**
     * Changes one level by one in every sub-block whose first sign is hidden and whose parity does not give
     * it, choosing the change of least added rounding error ({@code signBitHidingHDQ}).
     *
     * @param coeffs the coefficients
     * @param log2Size the block size as a power of two
     * @param levels the levels, adjusted in place
     * @param offset the index of the block's top-left level in {@code levels}
     * @param stride the row stride of {@code levels}
     * @param scanIdx the scan order of the block
     * @param deltaU the rounding error of each coefficient, in 1/256 of a level
     * @return the change of the sum of the absolute levels
     */
    private static int hideSigns(int[] coeffs, int log2Size, short[] levels, int offset, int stride, int scanIdx,
                                 int[] deltaU) {
        var size = 1 << log2Size;
        var sbScan = ScanTables.BY_INDEX[scanIdx][log2Size - 2];
        var posScan = ScanTables.BY_INDEX[scanIdx][2];
        var lastGroup = -1;
        var change = 0;
        for (var group = (1 << (2 * (log2Size - 2))) - 1; group >= 0; group--) {
            var sb = sbScan[group];
            var xS = (sb & 0xFF) << 2;
            var yS = (sb >> 8) << 2;
            var first = 16;
            var last = -1;
            for (var n = 15; n >= 0; n--) {
                if (levels[offset + position(posScan[n], xS, yS, stride)] != 0) {
                    last = n;
                    break;
                }
            }
            for (var n = 0; n < 16; n++) {
                if (levels[offset + position(posScan[n], xS, yS, stride)] != 0) {
                    first = n;
                    break;
                }
            }
            var absSum = 0;
            for (var n = first; n <= last; n++) {
                absSum += Math.abs(levels[offset + position(posScan[n], xS, yS, stride)]);
            }
            if (last >= 0 && lastGroup == -1) {
                lastGroup = 1;
            }
            if (last - first >= SIGN_HIDING_DISTANCE) {
                var signBit = levels[offset + position(posScan[first], xS, yS, stride)] > 0 ? 0 : 1;
                if (signBit != (absSum & 1)) {
                    var minCost = Integer.MAX_VALUE;
                    var minPos = -1;
                    var minBlock = -1;
                    var finalChange = 0;
                    for (var n = lastGroup == 1 ? last : 15; n >= 0; n--) {
                        var pos = posScan[n];
                        var block = (yS + (pos >> 8)) * size + xS + (pos & 0xFF);
                        var index = offset + position(pos, xS, yS, stride);
                        int cost;
                        var curChange = 0;
                        if (levels[index] != 0) {
                            if (deltaU[block] > 0) {
                                cost = -deltaU[block];
                                curChange = 1;
                            } else if (n == first && Math.abs(levels[index]) == 1) {
                                cost = Integer.MAX_VALUE;
                            } else {
                                cost = deltaU[block];
                                curChange = -1;
                            }
                        } else if (n < first && (coeffs[block] >= 0 ? 0 : 1) != signBit) {
                            cost = Integer.MAX_VALUE;
                        } else {
                            cost = -deltaU[block];
                            curChange = 1;
                        }
                        if (cost < minCost) {
                            minCost = cost;
                            finalChange = curChange;
                            minPos = index;
                            minBlock = block;
                        }
                    }
                    if (minPos < 0) {
                        continue;
                    }
                    var level = levels[minPos];
                    if (level == 32767 || level == -32768) {
                        finalChange = -1;
                    }
                    var updated = coeffs[minBlock] >= 0 ? level + finalChange : level - finalChange;
                    change += Math.abs(updated) - Math.abs(level);
                    levels[minPos] = (short) updated;
                }
            }
            if (lastGroup == 1) {
                lastGroup = 0;
            }
        }
        return change;
    }

    /**
     * Returns the offset of a position of a sub-block in a level plane.
     *
     * @param pos the position in the sub-block, packed {@code (y << 8) | x}
     * @param xS the x of the sub-block in levels
     * @param yS the y of the sub-block in levels
     * @param stride the row stride of the level plane
     * @return the offset from the block's top-left level
     */
    private static int position(int pos, int xS, int yS, int stride) {
        return (yS + (pos >> 8)) * stride + xS + (pos & 0xFF);
    }

    /**
     * Scales levels into the coefficients the inverse transform takes (8.6.3), with flat scaling lists.
     *
     * @param levels the quantized levels
     * @param offset the index of the block's top-left level in {@code levels}
     * @param stride the row stride of {@code levels}
     * @param log2Size the block size as a power of two
     * @param qp {@code qP}, including the bit depth offset
     * @param bitDepth the bit depth of the component
     * @param coeffs receives the scaled coefficients, {@code size * size} entries
     * @return the packed extent of the non-zero coefficients, {@code (rows << 8) | columns}
     */
    static int dequantize(short[] levels, int offset, int stride, int log2Size, int qp, int bitDepth,
                          int[] coeffs) {
        var size = 1 << log2Size;
        var bdShift = bitDepth + log2Size - 5;
        var add = 1L << (bdShift - 1);
        var scale = (long) 16 * LEVEL_SCALE[qp % 6] << (qp / 6);
        var columns = 0;
        var rows = 0;
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                var level = levels[row + x];
                if (level == 0) {
                    coeffs[y * size + x] = 0;
                    continue;
                }
                coeffs[y * size + x] = (int) Math.clamp((level * scale + add) >> bdShift, -32768, 32767);
                columns = Math.max(columns, x + 1);
                rows = Math.max(rows, y + 1);
            }
        }
        return (rows << 8) | columns;
    }
}
