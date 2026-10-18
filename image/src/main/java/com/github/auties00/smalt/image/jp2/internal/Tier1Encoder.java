package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Codes the coefficients of a code-block into coding passes (ISO/IEC 15444-1 annex C and D): the
 * MQ coder, the raw coder of the arithmetic coding bypass, the three passes under every code-block
 * style, and the distortion reduction of each pass that rate allocation weighs.
 *
 * <p>An instance is reused for many code-blocks but is not thread-safe.
 *
 * @implNote This implementation follows OpenJPEG's {@code t1.c} encoder
 * ({@code opj_t1_encode_cblk} and its passes) and {@code mqc.c} (including its pass termination,
 * its bypass flush and its rate estimates of unterminated passes), so the coded bytes and pass
 * lengths are identical to OpenJPEG's.
 */
final class Tier1Encoder {
    /**
     * The significance flags of the eight neighbors of stripe row 0.
     */
    private static final int SIGMA_NEIGHBORS = 0x1EF;

    /**
     * The significance flag of the sample itself, at stripe row 0.
     */
    private static final int SIGMA_THIS = 1 << 4;

    /**
     * The position of the sign flag of the sample itself, at stripe row 0.
     */
    private static final int CHI_THIS_SHIFT = 19;

    /**
     * The refined flag of the sample, at stripe row 0.
     */
    private static final int MU_THIS = 1 << 20;

    /**
     * The visited flag of the sample, at stripe row 0.
     */
    private static final int PI_THIS = 1 << 21;

    /**
     * The visited flags of the four stripe rows.
     */
    private static final int PI_ALL = (1 << 21) | (1 << 24) | (1 << 27) | (1 << 30);

    /**
     * The significance flags of the four stripe rows.
     */
    private static final int SIGMA_ALL = (1 << 4) | (1 << 7) | (1 << 10) | (1 << 13);

    /**
     * The value of {@link #ct} before the first bit of a bypass segment: larger than any count,
     * so a segment that codes no bit flushes nothing.
     */
    private static final int BYPASS_INITIAL = Integer.MAX_VALUE;

    /**
     * The 5-3 wavelet norms of each orientation and level (OpenJPEG's {@code opj_dwt_norms}).
     */
    private static final double[][] NORMS = {
            {1.000, 1.500, 2.750, 5.375, 10.68, 21.34, 42.67, 85.33, 170.7, 341.3},
            {1.038, 1.592, 2.919, 5.703, 11.33, 22.64, 45.25, 90.48, 180.9},
            {1.038, 1.592, 2.919, 5.703, 11.33, 22.64, 45.25, 90.48, 180.9},
            {.7186, .9218, 1.586, 3.043, 6.019, 12.01, 24.00, 47.97, 95.93}
    };

    /**
     * The 9-7 wavelet norms of each orientation and level (OpenJPEG's {@code opj_dwt_norms_real}).
     */
    private static final double[][] NORMS_REAL = {
            {1.000, 1.965, 4.177, 8.403, 16.90, 33.84, 67.69, 135.3, 270.6, 540.9},
            {2.022, 3.989, 8.355, 17.04, 34.27, 68.63, 137.3, 274.6, 549.0},
            {2.022, 3.989, 8.355, 17.04, 34.27, 68.63, 137.3, 274.6, 549.0},
            {2.080, 3.865, 8.307, 17.18, 34.71, 69.59, 139.3, 278.6, 557.2}
    };

    /**
     * The coefficients in stripe order, in sign-magnitude form.
     */
    private final int[] data = new int[4096];

    /**
     * The flag words, laid out as the decoder's.
     */
    private final int[] flags = new int[3078];

    /**
     * The context states.
     */
    private final int[] contexts = new int[Tier1Tables.CONTEXTS];

    /**
     * The coded bytes; index 0 is a zero byte before the first coded byte.
     */
    private byte[] out = new byte[4096 * 4 + 76];

    /**
     * The code-block width.
     */
    private int width;

    /**
     * The code-block height.
     */
    private int height;

    /**
     * The offset of the zero coding contexts of the current orientation.
     */
    private int zeroContexts;

    /**
     * The MQ interval register.
     */
    private int a;

    /**
     * The MQ code register.
     */
    private int c;

    /**
     * The number of bits left before the next byte output.
     */
    private int ct;

    /**
     * The index of the last byte written while arithmetic coding (index 0 is the zero byte before
     * the data), and of the next byte to write while coding raw bits or after a termination.
     */
    private int bp;

    /**
     * The distortion reduction of the current pass, in units of {@code 2^-13}.
     */
    private int nmsedec;

    /**
     * Constructs an encoder.
     */
    Tier1Encoder() {
    }

    /**
     * Returns the 5-3 or 9-7 wavelet norm of a sub-band.
     *
     * @param level       the decomposition level, 0 for the highest resolution's bands
     * @param orientation the orientation
     * @param reversible  whether the 5-3 wavelet is used
     * @return the norm, the last tabulated level's for deeper levels
     */
    static double norm(int level, int orientation, boolean reversible) {
        if (orientation == 0 && level >= 10) {
            level = 9;
        } else if (orientation > 0 && level >= 9) {
            level = 8;
        }
        return reversible ? NORMS[orientation][level] : NORMS_REAL[orientation][level];
    }

    /**
     * Codes a code-block.
     *
     * @param block       the code-block, receiving its passes and bytes
     * @param samples     the sub-band coefficients: integers for the 5-3 wavelet
     * @param floats      the sub-band coefficients: floats for the 9-7 wavelet
     * @param offset      the index of the block's first coefficient
     * @param stride      the row stride of the coefficients
     * @param orientation the sub-band orientation
     * @param level       the decomposition level
     * @param stepSize    the quantization step size
     * @param reversible  whether the 5-3 wavelet is used
     * @param style       the code-block style flags
     * @param weight      the component's weight in the distortion (its color transform norm)
     * @return the block's total weighted distortion reduction
     */
    double encode(EncodedBlock block, int[] samples, float[] floats, int offset, int stride, int orientation,
                  int level, float stepSize, boolean reversible, int style, double weight) {
        var w = block.x1 - block.x0;
        var h = block.y1 - block.y0;
        allocate(w, h);
        var position = 0;
        var k = 0;
        for (; k < (h & ~3); k += 4) {
            for (var i = 0; i < w; i++) {
                for (var r = 0; r < 4; r++) {
                    data[position++] = quantize(samples, floats, offset + (k + r) * stride + i, stepSize, reversible);
                }
            }
        }
        if (k < h) {
            for (var i = 0; i < w; i++) {
                for (var r = k; r < h; r++) {
                    data[position++] = quantize(samples, floats, offset + r * stride + i, stepSize, reversible);
                }
            }
        }
        var max = 0;
        for (var i = 0; i < position; i++) {
            var value = data[i];
            if (value < 0) {
                if (value == Integer.MIN_VALUE) {
                    value = Integer.MIN_VALUE + 1;
                }
                max = Math.max(max, -value);
                data[i] = -value | 0x80000000;
            } else {
                max = Math.max(max, value);
            }
        }
        zeroContexts = orientation << 9;
        block.bitPlanes = max != 0 ? (31 - Integer.numberOfLeadingZeros(max)) + 1 - Tier1Tables.NMSEDEC_FRACBITS : 0;
        block.totalPasses = 0;
        if (block.bitPlanes <= 0) {
            return 0;
        }
        var bitPlane = block.bitPlanes - 1;
        var passType = 2;
        resetContexts();
        a = 0x8000;
        c = 0;
        bp = 0;
        ct = 12;
        out[0] = 0;
        var cumulative = 0.0;
        var passes = 0;
        var lazy = (style & ComponentCoding.BYPASS) != 0;
        var vsc = (style & ComponentCoding.VERTICALLY_CAUSAL) != 0;
        var predictable = (style & ComponentCoding.PREDICTABLE_TERMINATION) != 0;
        block.ensurePasses(3 * block.bitPlanes);
        for (; bitPlane >= 0; passes++) {
            var raw = bitPlane < block.bitPlanes - 4 && passType < 2 && lazy;
            if (passes > 0 && block.passTerminated[passes - 1]) {
                if (raw) {
                    c = 0;
                    ct = BYPASS_INITIAL;
                } else {
                    a = 0x8000;
                    c = 0;
                    ct = 12;
                    bp--;
                    if ((out[bp] & 0xFF) == 0xFF) {
                        ct = 13;
                    }
                }
            }
            switch (passType) {
                case 0 -> significance(bitPlane, raw, vsc);
                case 1 -> refinement(bitPlane, raw);
                default -> {
                    cleanup(bitPlane, vsc);
                    if ((style & ComponentCoding.SEGMENTATION_SYMBOLS) != 0) {
                        for (var i = 1; i < 5; i++) {
                            encode(Tier1Tables.UNI, i % 2);
                        }
                    }
                }
            }
            cumulative += weightedDistortion(nmsedec, level, orientation, bitPlane, reversible, stepSize, weight);
            block.passDistortion[passes] = cumulative;
            if (terminated(block, style, bitPlane, passType)) {
                if (raw) {
                    flushBypass(predictable);
                } else if (predictable) {
                    flushPredictable();
                } else {
                    flush();
                }
                block.passTerminated[passes] = true;
                block.passRate[passes] = bp - 1;
            } else {
                var extra = raw ? bypassExtra(predictable) : 3;
                block.passTerminated[passes] = false;
                block.passRate[passes] = bp - 1 + extra;
            }
            if (++passType == 3) {
                passType = 0;
                bitPlane--;
            }
            if ((style & ComponentCoding.RESET) != 0) {
                resetContexts();
            }
        }
        block.totalPasses = passes;
        var last = bp - 1;
        for (var p = passes - 1; p >= 0; p--) {
            if (block.passRate[p] > last) {
                block.passRate[p] = last;
            } else {
                last = block.passRate[p];
            }
        }
        for (var p = 0; p < passes; p++) {
            if ((out[block.passRate[p]] & 0xFF) == 0xFF) {
                block.passRate[p]--;
            }
        }
        block.data = Arrays.copyOfRange(out, 1, Math.max(bp, 1));
        return cumulative;
    }

    /**
     * Quantizes one coefficient to the coder's fixed-point form with
     * {@link Tier1Tables#NMSEDEC_FRACBITS} fractional bits.
     *
     * @param samples    the integer coefficients
     * @param floats     the floating-point coefficients
     * @param index      the coefficient index
     * @param stepSize   the step size
     * @param reversible whether the coefficients are integers
     * @return the fixed-point value
     */
    private static int quantize(int[] samples, float[] floats, int index, float stepSize, boolean reversible) {
        if (reversible) {
            return samples[index] << Tier1Tables.NMSEDEC_FRACBITS;
        }
        return (int) (long) Math.rint((floats[index] / stepSize) * (1 << Tier1Tables.NMSEDEC_FRACBITS));
    }

    /**
     * Returns whether a pass ends with a termination of the coder.
     *
     * @param block    the code-block
     * @param style    the code-block style flags
     * @param bitPlane the pass's bit-plane
     * @param passType 0 significance, 1 refinement, 2 cleanup
     * @return whether the pass is terminated
     */
    private static boolean terminated(EncodedBlock block, int style, int bitPlane, int passType) {
        if (passType == 2 && bitPlane == 0) {
            return true;
        }
        if ((style & ComponentCoding.TERMINATE_ALL) != 0) {
            return true;
        }
        if ((style & ComponentCoding.BYPASS) != 0) {
            if (bitPlane == block.bitPlanes - 4 && passType == 2) {
                return true;
            }
            return bitPlane < block.bitPlanes - 4 && passType > 0;
        }
        return false;
    }

    /**
     * Computes the weighted distortion reduction of a pass (OpenJPEG's {@code opj_t1_getwmsedec}).
     *
     * @param nmsedec     the pass's normalized distortion reduction
     * @param level       the decomposition level
     * @param orientation the orientation
     * @param bitPlane    the bit-plane
     * @param reversible  whether the 5-3 wavelet is used
     * @param stepSize    the step size
     * @param weight      the component's weight
     * @return the weighted distortion reduction
     */
    private static double weightedDistortion(int nmsedec, int level, int orientation, int bitPlane, boolean reversible,
                                             float stepSize, double weight) {
        double norm;
        double step = stepSize;
        if (reversible) {
            norm = norm(level, orientation, true);
        } else {
            var gain = orientation == 0 ? 0 : orientation == 3 ? 2 : 1;
            norm = norm(level, orientation, false);
            step /= (1 << gain);
        }
        var w = weight * norm * step * (1 << bitPlane);
        w *= w * nmsedec / 8192.0;
        return w;
    }

    /**
     * Prepares the flag array for a code-block.
     *
     * @param w the width
     * @param h the height
     */
    private void allocate(int w, int h) {
        width = w;
        height = h;
        var stride = w + 2;
        var stripes = (h + 3) / 4;
        Arrays.fill(flags, 0, (stripes + 2) * stride, 0);
        Arrays.fill(flags, 0, stride, PI_ALL);
        Arrays.fill(flags, (stripes + 1) * stride, (stripes + 2) * stride, PI_ALL);
        if ((h & 3) != 0) {
            var v = switch (h & 3) {
                case 1 -> (1 << 24) | (1 << 27) | (1 << 30);
                case 2 -> (1 << 27) | (1 << 30);
                default -> 1 << 30;
            };
            Arrays.fill(flags, stripes * stride, (stripes + 1) * stride, v);
        }
        if (out.length < w * h * 4 + 76) {
            out = new byte[w * h * 4 + 76];
        }
    }

    /**
     * Resets every context to its initial state.
     */
    private void resetContexts() {
        Arrays.fill(contexts, 0);
        contexts[Tier1Tables.UNI] = 46 << 1;
        contexts[Tier1Tables.AGG] = 3 << 1;
        contexts[Tier1Tables.ZC] = 4 << 1;
    }

    /**
     * Codes one decision with the MQ coder (C.2).
     *
     * @param context the context
     * @param d       the decision
     */
    private void encode(int context, int d) {
        var state = contexts[context];
        var qe = Tier1Tables.QE[state];
        if (Tier1Tables.MPS[state] == d) {
            a -= qe;
            if ((a & 0x8000) == 0) {
                if (a < qe) {
                    a = qe;
                } else {
                    c += qe;
                }
                contexts[context] = Tier1Tables.NEXT_MPS[state];
                renormalize();
            } else {
                c += qe;
            }
        } else {
            a -= qe;
            if (a < qe) {
                c += qe;
            } else {
                a = qe;
            }
            contexts[context] = Tier1Tables.NEXT_LPS[state];
            renormalize();
        }
    }

    /**
     * Renormalizes the coder registers (C.2.6).
     */
    private void renormalize() {
        do {
            a <<= 1;
            c <<= 1;
            ct--;
            if (ct == 0) {
                byteOut();
            }
        } while ((a & 0x8000) == 0);
    }

    /**
     * Outputs a byte with bit stuffing (C.2.7).
     */
    private void byteOut() {
        if ((out[bp] & 0xFF) == 0xFF) {
            bp++;
            out[bp] = (byte) (c >>> 20);
            c &= 0xFFFFF;
            ct = 7;
        } else if ((c & 0x8000000) == 0) {
            bp++;
            out[bp] = (byte) (c >>> 19);
            c &= 0x7FFFF;
            ct = 8;
        } else {
            out[bp]++;
            if ((out[bp] & 0xFF) == 0xFF) {
                c &= 0x7FFFFFF;
                bp++;
                out[bp] = (byte) (c >>> 20);
                c &= 0xFFFFF;
                ct = 7;
            } else {
                bp++;
                out[bp] = (byte) (c >>> 19);
                c &= 0x7FFFF;
                ct = 8;
            }
        }
    }

    /**
     * Terminates the MQ coder (C.2.9).
     */
    private void flush() {
        var temp = c + a;
        c |= 0xFFFF;
        if (Integer.compareUnsigned(c, temp) >= 0) {
            c -= 0x8000;
        }
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if ((out[bp] & 0xFF) != 0xFF) {
            bp++;
        }
    }

    /**
     * Terminates the MQ coder predictably (D.4.2).
     */
    private void flushPredictable() {
        var k = 11 - ct + 1;
        while (k > 0) {
            c <<= ct;
            ct = 0;
            byteOut();
            k -= ct;
        }
        if ((out[bp] & 0xFF) != 0xFF) {
            byteOut();
        }
    }

    /**
     * Codes one raw bit of a bypass segment (D.6).
     *
     * @param d the bit
     */
    private void rawBit(int d) {
        if (ct == BYPASS_INITIAL) {
            ct = 8;
        }
        ct--;
        c += d << ct;
        if (ct == 0) {
            out[bp] = (byte) c;
            ct = 8;
            if ((out[bp] & 0xFF) == 0xFF) {
                ct = 7;
            }
            bp++;
            c = 0;
        }
    }

    /**
     * Returns the bytes a bypass segment would still need if it ended now.
     *
     * @param predictable whether predictable termination is used
     * @return 0 or 1
     */
    private int bypassExtra(boolean predictable) {
        return ct < 7 || (ct == 7 && (predictable || (out[bp - 1] & 0xFF) != 0xFF)) ? 1 : 0;
    }

    /**
     * Terminates a bypass segment.
     *
     * @param predictable whether predictable termination is used
     */
    private void flushBypass(boolean predictable) {
        if (ct < 7 || (ct == 7 && (predictable || (out[bp - 1] & 0xFF) != 0xFF))) {
            var bit = 0;
            while (ct > 0) {
                ct--;
                c += bit << ct;
                bit = 1 - bit;
            }
            out[bp] = (byte) c;
            bp++;
        } else if (ct == 7 && (out[bp - 1] & 0xFF) == 0xFF) {
            bp--;
        } else if (ct == 8 && !predictable && (out[bp - 1] & 0xFF) == 0x7F && (out[bp - 2] & 0xFF) == 0xFF) {
            bp -= 2;
        }
    }

    /**
     * Computes the sign neighborhood index of a stripe row.
     *
     * @param f    the flag word
     * @param west the west flag word
     * @param east the east flag word
     * @param row  the stripe row
     * @return the index into the sign tables
     */
    private static int signIndex(int f, int west, int east, int row) {
        var index = (f >>> (row * 3)) & ((1 << 1) | (1 << 3) | (1 << 5) | (1 << 7));
        index |= (west >>> (CHI_THIS_SHIFT + row * 3)) & 1;
        index |= (east >>> (CHI_THIS_SHIFT - 2 + row * 3)) & (1 << 2);
        if (row == 0) {
            index |= (f >>> (18 - 4)) & (1 << 4);
        } else {
            index |= (f >>> (CHI_THIS_SHIFT - 4 + (row - 1) * 3)) & (1 << 4);
        }
        index |= (f >>> (22 - 6 + row * 3)) & (1 << 6);
        return index;
    }

    /**
     * Records a newly significant sample in the flag words.
     *
     * @param fp   the index of the sample column's flag word
     * @param row  the stripe row
     * @param sign the sign, 1 for negative
     * @param vsc  whether the stripe above must not see the sample
     */
    private void markSignificant(int fp, int row, int sign, boolean vsc) {
        var stride = width + 2;
        flags[fp - 1] |= (1 << 5) << (3 * row);
        flags[fp] |= ((sign << CHI_THIS_SHIFT) | SIGMA_THIS) << (3 * row);
        flags[fp + 1] |= (1 << 3) << (3 * row);
        if (row == 0 && !vsc) {
            var north = fp - stride;
            flags[north] |= (sign << 31) | (1 << 16);
            flags[north - 1] |= 1 << 17;
            flags[north + 1] |= 1 << 15;
        }
        if (row == 3) {
            var south = fp + stride;
            flags[south] |= (sign << 18) | (1 << 1);
            flags[south - 1] |= 1 << 2;
            flags[south + 1] |= 1;
        }
    }

    /**
     * Returns the distortion reduction of a significance decision.
     *
     * @param magnitude the coefficient magnitude
     * @param bitPlane  the bit-plane
     * @return the reduction
     */
    private static int significanceGain(int magnitude, int bitPlane) {
        if (bitPlane > 0) {
            return Tier1Tables.NMSEDEC_SIG[(magnitude >>> bitPlane) & ((1 << Tier1Tables.NMSEDEC_BITS) - 1)];
        }
        return Tier1Tables.NMSEDEC_SIG0[magnitude & ((1 << Tier1Tables.NMSEDEC_BITS) - 1)];
    }

    /**
     * Returns the distortion reduction of a refinement decision.
     *
     * @param magnitude the coefficient magnitude
     * @param bitPlane  the bit-plane
     * @return the reduction
     */
    private static int refinementGain(int magnitude, int bitPlane) {
        if (bitPlane > 0) {
            return Tier1Tables.NMSEDEC_REF[(magnitude >>> bitPlane) & ((1 << Tier1Tables.NMSEDEC_BITS) - 1)];
        }
        return Tier1Tables.NMSEDEC_REF0[magnitude & ((1 << Tier1Tables.NMSEDEC_BITS) - 1)];
    }

    /**
     * Codes one sample of a significance propagation pass.
     *
     * @param fp       the index of the sample column's flag word
     * @param index    the sample index in {@link #data}
     * @param row      the stripe row
     * @param one      the weight of the bit-plane, with the fractional bits
     * @param bitPlane the bit-plane
     * @param raw      whether the pass is a raw bypass pass
     * @param vsc      whether the stripe above must not see the sample
     */
    private void significanceStep(int fp, int index, int row, int one, int bitPlane, boolean raw, boolean vsc) {
        var f = flags[fp];
        var shift = row * 3;
        if ((f & ((SIGMA_THIS | PI_THIS) << shift)) != 0 || (f & (SIGMA_NEIGHBORS << shift)) == 0) {
            return;
        }
        var value = data[index];
        var magnitude = value & 0x7FFFFFFF;
        var v = (magnitude & one) != 0 ? 1 : 0;
        var context = Tier1Tables.ZERO_CONTEXT[zeroContexts + ((f >>> shift) & SIGMA_NEIGHBORS)];
        if (raw) {
            rawBit(v);
        } else {
            encode(context, v);
        }
        if (v != 0) {
            var lookup = signIndex(flags[fp], flags[fp - 1], flags[fp + 1], row);
            var sign = value >>> 31;
            nmsedec += significanceGain(magnitude, bitPlane);
            if (raw) {
                rawBit(sign);
            } else {
                encode(Tier1Tables.SIGN_CONTEXT[lookup], sign ^ Tier1Tables.SIGN_PREDICTION[lookup]);
            }
            markSignificant(fp, row, sign, vsc);
        }
        flags[fp] |= PI_THIS << shift;
    }

    /**
     * Runs a significance propagation pass.
     *
     * @param bitPlane the bit-plane
     * @param raw      whether the pass is a raw bypass pass
     * @param vsc      whether contexts are vertically causal
     */
    private void significance(int bitPlane, boolean raw, boolean vsc) {
        nmsedec = 0;
        var one = 1 << (bitPlane + Tier1Tables.NMSEDEC_FRACBITS);
        var stride = width + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, fp += 2) {
            for (var i = 0; i < width; i++, fp++, dp += 4) {
                if (flags[fp] == 0) {
                    continue;
                }
                significanceStep(fp, dp, 0, one, bitPlane, raw, vsc);
                significanceStep(fp, dp + 1, 1, one, bitPlane, raw, false);
                significanceStep(fp, dp + 2, 2, one, bitPlane, raw, false);
                significanceStep(fp, dp + 3, 3, one, bitPlane, raw, false);
            }
        }
        if (k < height) {
            var rows = height - k;
            for (var i = 0; i < width; i++, fp++) {
                if (flags[fp] == 0) {
                    dp += rows;
                    continue;
                }
                for (var j = 0; j < rows; j++, dp++) {
                    significanceStep(fp, dp, j, one, bitPlane, raw, j == 0 && vsc);
                }
            }
        }
    }

    /**
     * Codes one sample of a magnitude refinement pass.
     *
     * @param snapshot the flag word of the column at the start of the column
     * @param updated  the flag word being updated
     * @param index    the sample index
     * @param row      the stripe row
     * @param one      the weight of the bit-plane
     * @param bitPlane the bit-plane
     * @param raw      whether the pass is a raw bypass pass
     * @return the updated flag word
     */
    private int refinementStep(int snapshot, int updated, int index, int row, int one, int bitPlane, boolean raw) {
        var shift = row * 3;
        if ((snapshot & ((SIGMA_THIS | PI_THIS) << shift)) != (SIGMA_THIS << shift)) {
            return updated;
        }
        var neighborhood = snapshot >>> shift;
        var context = (neighborhood & MU_THIS) != 0 ? Tier1Tables.MAG + 2
                : (neighborhood & SIGMA_NEIGHBORS) != 0 ? Tier1Tables.MAG + 1 : Tier1Tables.MAG;
        var magnitude = data[index] & 0x7FFFFFFF;
        nmsedec += refinementGain(magnitude, bitPlane);
        var v = (magnitude & one) != 0 ? 1 : 0;
        if (raw) {
            rawBit(v);
        } else {
            encode(context, v);
        }
        return updated | (MU_THIS << shift);
    }

    /**
     * Runs a magnitude refinement pass.
     *
     * @param bitPlane the bit-plane
     * @param raw      whether the pass is a raw bypass pass
     */
    private void refinement(int bitPlane, boolean raw) {
        nmsedec = 0;
        var one = 1 << (bitPlane + Tier1Tables.NMSEDEC_FRACBITS);
        var stride = width + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, fp += 2) {
            for (var i = 0; i < width; i++, fp++, dp += 4) {
                var f = flags[fp];
                if ((f & SIGMA_ALL) == 0 || (f & PI_ALL) == PI_ALL) {
                    continue;
                }
                var updated = f;
                updated = refinementStep(f, updated, dp, 0, one, bitPlane, raw);
                updated = refinementStep(f, updated, dp + 1, 1, one, bitPlane, raw);
                updated = refinementStep(f, updated, dp + 2, 2, one, bitPlane, raw);
                updated = refinementStep(f, updated, dp + 3, 3, one, bitPlane, raw);
                flags[fp] = updated;
            }
        }
        if (k < height) {
            var rows = height - k;
            for (var i = 0; i < width; i++, fp++) {
                if ((flags[fp] & SIGMA_ALL) == 0) {
                    dp += rows;
                    continue;
                }
                for (var j = 0; j < rows; j++, dp++) {
                    flags[fp] = refinementStep(flags[fp], flags[fp], dp, j, one, bitPlane, raw);
                }
            }
        }
    }

    /**
     * Runs a cleanup pass.
     *
     * @param bitPlane the bit-plane
     * @param vsc      whether contexts are vertically causal
     */
    private void cleanup(int bitPlane, boolean vsc) {
        nmsedec = 0;
        var one = 1 << (bitPlane + Tier1Tables.NMSEDEC_FRACBITS);
        var stride = width + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, fp += 2) {
            for (var i = 0; i < width; i++, fp++) {
                var aggregated = flags[fp] == 0;
                var run = 0;
                if (aggregated) {
                    while (run < 4 && ((data[dp + run] & 0x7FFFFFFF) & one) == 0) {
                        run++;
                    }
                    encode(Tier1Tables.AGG, run != 4 ? 1 : 0);
                    if (run == 4) {
                        dp += 4;
                        continue;
                    }
                    encode(Tier1Tables.UNI, run >> 1);
                    encode(Tier1Tables.UNI, run & 1);
                }
                cleanupColumn(fp, dp + run, bitPlane, one, aggregated, run, 4, vsc);
                dp += 4;
            }
        }
        if (k < height) {
            var rows = height - k;
            for (var i = 0; i < width; i++, fp++) {
                cleanupColumn(fp, dp, bitPlane, one, false, 0, rows, vsc);
                dp += rows;
            }
        }
    }

    /**
     * Codes the samples of one stripe column of a cleanup pass, from a run onward.
     *
     * @param fp         the index of the column's flag word
     * @param index      the index of the first sample to code
     * @param bitPlane   the bit-plane
     * @param one        the weight of the bit-plane
     * @param aggregated whether the column was run-length coded
     * @param run        the stripe row of the first sample to code
     * @param limit      the number of stripe rows
     * @param vsc        whether contexts are vertically causal
     */
    private void cleanupColumn(int fp, int index, int bitPlane, int one, boolean aggregated, int run, int limit,
                               boolean vsc) {
        var check = SIGMA_ALL | PI_ALL;
        if ((flags[fp] & check) == check) {
            var mask = switch (run) {
                case 0 -> PI_ALL;
                case 1 -> (1 << 24) | (1 << 27) | (1 << 30);
                case 2 -> (1 << 27) | (1 << 30);
                default -> 1 << 30;
            };
            flags[fp] &= ~mask;
            return;
        }
        for (var row = run; row < limit; row++, index++) {
            var shift = row * 3;
            var partial = aggregated && row == run;
            if (!partial && (flags[fp] & ((SIGMA_THIS | PI_THIS) << shift)) == 0) {
                var context = Tier1Tables.ZERO_CONTEXT[zeroContexts + ((flags[fp] >>> shift) & SIGMA_NEIGHBORS)];
                var v = ((data[index] & 0x7FFFFFFF) & one) != 0 ? 1 : 0;
                encode(context, v);
                partial = v != 0;
            }
            if (partial) {
                var value = data[index];
                var lookup = signIndex(flags[fp], flags[fp - 1], flags[fp + 1], row);
                nmsedec += significanceGain(value & 0x7FFFFFFF, bitPlane);
                var sign = value >>> 31;
                encode(Tier1Tables.SIGN_CONTEXT[lookup], sign ^ Tier1Tables.SIGN_PREDICTION[lookup]);
                markSignificant(fp, row, sign, vsc && row == 0);
            }
            flags[fp] &= ~(PI_THIS << shift);
        }
    }
}
