package com.github.auties00.smalt.video.hevc.internal;

/**
 * Implements the HEVC kernels in plain Java; the reference every other implementation must match.
 *
 * <p>Each filter loop holds its taps in locals, so the JIT compiler keeps them in registers and
 * the inner loops reduce to multiply-adds over consecutive samples.
 *
 * <p>Besides the kernels of {@link HevcDsp}, which keep 16-bit intermediate samples, the class
 * provides the same processes on 32-bit intermediate samples ({@code wide*}): the prediction of
 * pictures deeper than 12 bits, whose intermediate samples exceed 16 bits, and of the rare
 * bi-directionally filtered luma blocks of shallower pictures whose intermediate samples do.
 *
 * <p>The kernels of 8-bit planes are the bit depth 8 cases of the 16-bit ones, reading and
 * writing unsigned bytes.
 */
final class ScalarHevcDsp extends HevcDsp {
    /**
     * Constructs the kernels.
     */
    ScalarHevcDsp() {
    }

    @Override
    public boolean lumaMc(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                          int bitDepth, short[] scratch) {
        var shift1 = bitDepth - 8;
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h, 14 - bitDepth);
            return true;
        }
        if (yFrac == 0) {
            return filter8(src, srcOff - 3, 1, srcStride, dst, 0, w, w, h, xFrac * 8, shift1);
        }
        if (xFrac == 0) {
            return filter8(src, srcOff - 3 * srcStride, srcStride, srcStride, dst, 0, w, w, h, yFrac * 8, shift1);
        }
        filter8(src, srcOff - 3 * srcStride - 3, 1, srcStride, scratch, 0, w, w, h + 7, xFrac * 8, shift1);
        return filter8(scratch, 0, w, w, dst, 0, w, w, h, yFrac * 8, 6);
    }

    @Override
    public boolean chromaMc(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                            int bitDepth, short[] scratch) {
        var shift1 = bitDepth - 8;
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h, 14 - bitDepth);
            return true;
        }
        if (yFrac == 0) {
            return filter4(src, srcOff - 1, 1, srcStride, dst, 0, w, w, h, xFrac * 4, shift1);
        }
        if (xFrac == 0) {
            return filter4(src, srcOff - srcStride, srcStride, srcStride, dst, 0, w, w, h, yFrac * 4, shift1);
        }
        filter4(src, srcOff - srcStride - 1, 1, srcStride, scratch, 0, w, w, h + 3, xFrac * 4, shift1);
        return filter4(scratch, 0, w, w, dst, 0, w, w, h, yFrac * 4, 6);
    }

    /**
     * Scales a block of samples to the 14-bit intermediate precision, keeping 16-bit values.
     *
     * @param src the source plane
     * @param srcOff the index of the block's top-left sample
     * @param srcStride the source row stride
     * @param dst receives the block, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param shift the left shift, {@code 14 - bitDepth}
     */
    static void copy(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int shift) {
        for (var y = 0; y < h; y++) {
            var row = srcOff + y * srcStride;
            var out = y * w;
            for (var x = 0; x < w; x++) {
                dst[out + x] = (short) ((src[row + x] & 0xFFFF) << shift);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of samples or 16-bit intermediate values, keeping the
     * results as 16-bit values: {@code dst = sum(f[k] * src[origin + k * step]) >> shift}.
     *
     * @param src the source values, unsigned samples when {@code shift} is not 6, signed
     * intermediate values otherwise
     * @param origin the index of the value under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #LUMA_FILTER}
     * @param shift the right shift
     * @return {@code true} when every result fits 16 bits, {@code false} when one was truncated
     */
    static boolean filter8(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff, int dstStride,
                           int w, int rows, int filter, int shift) {
        var f = LUMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        int c4 = f[filter + 4], c5 = f[filter + 5], c6 = f[filter + 6], c7 = f[filter + 7];
        var mask = shift == 6 ? -1 : 0xFFFF;
        var fits = true;
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                var value = (c0 * (src[p] & mask) + c1 * (src[p + step] & mask) + c2 * (src[p + 2 * step] & mask)
                        + c3 * (src[p + 3 * step] & mask) + c4 * (src[p + 4 * step] & mask)
                        + c5 * (src[p + 5 * step] & mask) + c6 * (src[p + 6 * step] & mask)
                        + c7 * (src[p + 7 * step] & mask)) >> shift;
                fits &= value == (short) value;
                dst[out + x] = (short) value;
            }
        }
        return fits;
    }

    /**
     * Applies a 4-tap filter to a block of samples or 16-bit intermediate values, keeping the
     * results as 16-bit values: {@code dst = sum(f[k] * src[origin + k * step]) >> shift}.
     *
     * @param src the source values, unsigned samples when {@code shift} is not 6, signed
     * intermediate values otherwise
     * @param origin the index of the value under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #CHROMA_FILTER}
     * @param shift the right shift
     * @return {@code true} when every result fits 16 bits, {@code false} when one was truncated
     */
    static boolean filter4(short[] src, int origin, int step, int srcStride, short[] dst, int dstOff, int dstStride,
                           int w, int rows, int filter, int shift) {
        var f = CHROMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        var mask = shift == 6 ? -1 : 0xFFFF;
        var fits = true;
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                var value = (c0 * (src[p] & mask) + c1 * (src[p + step] & mask) + c2 * (src[p + 2 * step] & mask)
                        + c3 * (src[p + 3 * step] & mask)) >> shift;
                fits &= value == (short) value;
                dst[out + x] = (short) value;
            }
        }
        return fits;
    }

    @Override
    public void putUni(short[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int bitDepth) {
        var shift = 14 - bitDepth;
        var offset = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (short) Math.clamp((pred[in + x] + offset) >> shift, 0, max);
            }
        }
    }

    @Override
    public void putBi(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                      int bitDepth) {
        var shift = 15 - bitDepth;
        var offset = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (short) Math.clamp((pred0[in + x] + pred1[in + x] + offset) >> shift, 0, max);
            }
        }
    }

    @Override
    public void putWeightedUni(short[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int bitDepth,
                               int weight, int offset, int log2Wd) {
        var max = (1 << bitDepth) - 1;
        var round = 1 << (log2Wd - 1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (short) Math.clamp(((pred[in + x] * weight + round) >> log2Wd) + offset, 0, max);
            }
        }
    }

    @Override
    public void putWeightedBi(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                              int bitDepth, int weight0, int weight1, int offsets, int log2Wd) {
        var max = (1 << bitDepth) - 1;
        var round = (offsets + 1) << log2Wd;
        var shift = log2Wd + 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                var value = (pred0[in + x] * weight0 + pred1[in + x] * weight1 + round) >> shift;
                dst[row + x] = (short) Math.clamp(value, 0, max);
            }
        }
    }

    /**
     * Interpolates a luma block (8.5.3.3.3.2) into 32-bit intermediate samples, for any bit depth
     * up to 16.
     *
     * @param src the reference luma plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the plane's row stride
     * @param dst receives the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param xFrac the horizontal quarter-sample fraction, from 0 to 3
     * @param yFrac the vertical quarter-sample fraction, from 0 to 3
     * @param bitDepth the luma bit depth, from 8 to 16
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SIZE} entries
     */
    static void wideLumaMc(short[] src, int srcOff, int srcStride, int[] dst, int w, int h, int xFrac, int yFrac,
                           int bitDepth, int[] scratch) {
        var shift1 = Math.min(4, bitDepth - 8);
        if (xFrac == 0 && yFrac == 0) {
            wideCopy(src, srcOff, srcStride, dst, w, h, Math.max(2, 14 - bitDepth));
        } else if (yFrac == 0) {
            wideFilter8(src, srcOff - 3, 1, srcStride, dst, 0, w, w, h, xFrac * 8, shift1);
        } else if (xFrac == 0) {
            wideFilter8(src, srcOff - 3 * srcStride, srcStride, srcStride, dst, 0, w, w, h, yFrac * 8, shift1);
        } else {
            wideFilter8(src, srcOff - 3 * srcStride - 3, 1, srcStride, scratch, 0, w, w, h + 7, xFrac * 8, shift1);
            wideFilter8(scratch, 0, w, w, dst, 0, w, w, h, yFrac * 8, 6);
        }
    }

    /**
     * Interpolates a chroma block (8.5.3.3.3.3) into 32-bit intermediate samples, for any bit
     * depth up to 16.
     *
     * @param src the reference chroma plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the plane's row stride
     * @param dst receives the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param xFrac the horizontal eighth-sample fraction, from 0 to 7
     * @param yFrac the vertical eighth-sample fraction, from 0 to 7
     * @param bitDepth the chroma bit depth, from 8 to 16
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SIZE} entries
     */
    static void wideChromaMc(short[] src, int srcOff, int srcStride, int[] dst, int w, int h, int xFrac, int yFrac,
                             int bitDepth, int[] scratch) {
        var shift1 = Math.min(4, bitDepth - 8);
        if (xFrac == 0 && yFrac == 0) {
            wideCopy(src, srcOff, srcStride, dst, w, h, Math.max(2, 14 - bitDepth));
        } else if (yFrac == 0) {
            wideFilter4(src, srcOff - 1, 1, srcStride, dst, 0, w, w, h, xFrac * 4, shift1);
        } else if (xFrac == 0) {
            wideFilter4(src, srcOff - srcStride, srcStride, srcStride, dst, 0, w, w, h, yFrac * 4, shift1);
        } else {
            wideFilter4(src, srcOff - srcStride - 1, 1, srcStride, scratch, 0, w, w, h + 3, xFrac * 4, shift1);
            wideFilter4(scratch, 0, w, w, dst, 0, w, w, h, yFrac * 4, 6);
        }
    }

    /**
     * Scales a block of samples to the intermediate precision in 32-bit values.
     *
     * @param src the source plane
     * @param srcOff the index of the block's top-left sample
     * @param srcStride the source row stride
     * @param dst receives the block, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param shift the left shift, {@code shift3}
     */
    private static void wideCopy(short[] src, int srcOff, int srcStride, int[] dst, int w, int h, int shift) {
        for (var y = 0; y < h; y++) {
            var row = srcOff + y * srcStride;
            var out = y * w;
            for (var x = 0; x < w; x++) {
                dst[out + x] = (src[row + x] & 0xFFFF) << shift;
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of samples into 32-bit values:
     * {@code dst = sum(f[k] * src[origin + k * step]) >> shift}.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #LUMA_FILTER}
     * @param shift the right shift
     */
    private static void wideFilter8(short[] src, int origin, int step, int srcStride, int[] dst, int dstOff,
                                    int dstStride, int w, int rows, int filter, int shift) {
        var f = LUMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        int c4 = f[filter + 4], c5 = f[filter + 5], c6 = f[filter + 6], c7 = f[filter + 7];
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                dst[out + x] = (c0 * (src[p] & 0xFFFF) + c1 * (src[p + step] & 0xFFFF) + c2 * (src[p + 2 * step] & 0xFFFF)
                        + c3 * (src[p + 3 * step] & 0xFFFF) + c4 * (src[p + 4 * step] & 0xFFFF)
                        + c5 * (src[p + 5 * step] & 0xFFFF) + c6 * (src[p + 6 * step] & 0xFFFF)
                        + c7 * (src[p + 7 * step] & 0xFFFF)) >> shift;
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of 32-bit intermediate values:
     * {@code dst = sum(f[k] * src[origin + k * step]) >> shift}.
     *
     * @param src the source values
     * @param origin the index of the value under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #LUMA_FILTER}
     * @param shift the right shift
     */
    private static void wideFilter8(int[] src, int origin, int step, int srcStride, int[] dst, int dstOff,
                                    int dstStride, int w, int rows, int filter, int shift) {
        var f = LUMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        int c4 = f[filter + 4], c5 = f[filter + 5], c6 = f[filter + 6], c7 = f[filter + 7];
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                dst[out + x] = (c0 * src[p] + c1 * src[p + step] + c2 * src[p + 2 * step] + c3 * src[p + 3 * step]
                        + c4 * src[p + 4 * step] + c5 * src[p + 5 * step] + c6 * src[p + 6 * step]
                        + c7 * src[p + 7 * step]) >> shift;
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of samples into 32-bit values:
     * {@code dst = sum(f[k] * src[origin + k * step]) >> shift}.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #CHROMA_FILTER}
     * @param shift the right shift
     */
    private static void wideFilter4(short[] src, int origin, int step, int srcStride, int[] dst, int dstOff,
                                    int dstStride, int w, int rows, int filter, int shift) {
        var f = CHROMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                dst[out + x] = (c0 * (src[p] & 0xFFFF) + c1 * (src[p + step] & 0xFFFF) + c2 * (src[p + 2 * step] & 0xFFFF)
                        + c3 * (src[p + 3 * step] & 0xFFFF)) >> shift;
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of 32-bit intermediate values:
     * {@code dst = sum(f[k] * src[origin + k * step]) >> shift}.
     *
     * @param src the source values
     * @param origin the index of the value under the first tap of the first output
     * @param step the distance between taps
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #CHROMA_FILTER}
     * @param shift the right shift
     */
    private static void wideFilter4(int[] src, int origin, int step, int srcStride, int[] dst, int dstOff,
                                    int dstStride, int w, int rows, int filter, int shift) {
        var f = CHROMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                dst[out + x] = (c0 * src[p] + c1 * src[p + step] + c2 * src[p + 2 * step] + c3 * src[p + 3 * step])
                        >> shift;
            }
        }
    }

    /**
     * Writes a uni-directional 32-bit prediction into a plane with the default weighted sample
     * prediction (equation 8-262), {@code shift1 = Max(2, 14 - bitDepth)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 16
     */
    static void widePutUni(short[] dst, int dstOff, int dstStride, int[] pred, int w, int h, int bitDepth) {
        var shift = Math.max(2, 14 - bitDepth);
        var offset = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (short) Math.clamp((pred[in + x] + offset) >> shift, 0, max);
            }
        }
    }

    /**
     * Writes a bi-directional 32-bit prediction into a plane with the default weighted sample
     * prediction (equation 8-264), {@code shift2 = Max(3, 15 - bitDepth)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 16
     */
    static void widePutBi(short[] dst, int dstOff, int dstStride, int[] pred0, int[] pred1, int w, int h,
                          int bitDepth) {
        var shift = Math.max(3, 15 - bitDepth);
        var offset = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (short) Math.clamp((pred0[in + x] + pred1[in + x] + offset) >> shift, 0, max);
            }
        }
    }

    /**
     * Writes a uni-directional 32-bit prediction into a plane with explicit weighted sample
     * prediction (equation 8-275).
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 16
     * @param weight the weighting factor of the reference picture ({@code w0} of equation 8-265)
     * @param offset the offset, scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    static void widePutWeightedUni(short[] dst, int dstOff, int dstStride, int[] pred, int w, int h, int bitDepth,
                                   int weight, int offset, int log2Wd) {
        var max = (1 << bitDepth) - 1;
        var round = 1L << (log2Wd - 1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (short) Math.clamp((((long) pred[in + x] * weight + round) >> log2Wd) + offset, 0, max);
            }
        }
    }

    /**
     * Writes a bi-directional 32-bit prediction into a plane with explicit weighted sample
     * prediction (equation 8-277).
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 16
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offsets the sum of the two offsets scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    static void widePutWeightedBi(short[] dst, int dstOff, int dstStride, int[] pred0, int[] pred1, int w, int h,
                                  int bitDepth, int weight0, int weight1, int offsets, int log2Wd) {
        var max = (1 << bitDepth) - 1;
        var round = (offsets + 1L) << log2Wd;
        var shift = log2Wd + 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                var value = ((long) pred0[in + x] * weight0 + (long) pred1[in + x] * weight1 + round) >> shift;
                dst[row + x] = (short) Math.clamp(value, 0, max);
            }
        }
    }

    @Override
    public void saoEdge(short[] dst, short[] src, int offset, int stride, int w, int h, int deltaA, int deltaB,
                        int[] lut, int max) {
        for (var y = 0; y < h; y++) {
            var start = offset + y * stride;
            var end = start + w;
            for (var p = start; p < end; p++) {
                var value = src[p] & 0xFFFF;
                var edgeIdx = 2 + Integer.signum(value - (src[p + deltaA] & 0xFFFF))
                        + Integer.signum(value - (src[p + deltaB] & 0xFFFF));
                dst[p] = (short) Math.clamp(value + lut[edgeIdx], 0, max);
            }
        }
    }

    @Override
    public void saoBand(short[] dst, short[] src, int offset, int stride, int w, int h, int position, int shift,
                        int o0, int o1, int o2, int o3, int max) {
        for (var y = 0; y < h; y++) {
            var start = offset + y * stride;
            var end = start + w;
            for (var p = start; p < end; p++) {
                var value = src[p] & 0xFFFF;
                var k = ((value >> shift) - position) & 31;
                var add = k == 0 ? o0 : k == 1 ? o1 : k == 2 ? o2 : k == 3 ? o3 : 0;
                dst[p] = (short) Math.clamp(value + add, 0, max);
            }
        }
    }

    @Override
    public void addResidual(short[] plane, int offset, int stride, int[] residual, int size, int max) {
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            var in = y * size;
            for (var x = 0; x < size; x++) {
                plane[row + x] = (short) Math.clamp((plane[row + x] & 0xFFFF) + residual[in + x], 0, max);
            }
        }
    }

    @Override
    public void deblockLuma(byte[] plane, int origin, int stride, short[] params) {
        deblockLuma(plane, origin, stride, 1, params);
    }

    @Override
    public void deblockLumaVertical(byte[] plane, int origin, int stride, short[] params, byte[] scratch) {
        deblockLuma(plane, origin, 1, stride, params);
    }

    /**
     * Applies the luma deblocking filter of {@link #deblockLuma} to 16 lines crossing an edge.
     *
     * @param plane the plane, modified in place
     * @param origin the index of {@code q0} of the first line
     * @param stride the distance between samples across the edge
     * @param along the distance between lines along the edge
     * @param params {@code tC} of each line in entries 0 to 15 and its flags in entries 16 to 31
     */
    private static void deblockLuma(byte[] plane, int origin, int stride, int along, short[] params) {
        for (var i = 0; i < 16; i++) {
            var flags = params[16 + i];
            if ((flags & (DEBLOCK_STRONG | DEBLOCK_NORMAL)) == 0) {
                continue;
            }
            var tc = params[i];
            var o = origin + i * along;
            var p0 = plane[o - stride] & 0xFF;
            var p1 = plane[o - 2 * stride] & 0xFF;
            var p2 = plane[o - 3 * stride] & 0xFF;
            var q0 = plane[o] & 0xFF;
            var q1 = plane[o + stride] & 0xFF;
            var q2 = plane[o + 2 * stride] & 0xFF;
            var writeP = (flags & DEBLOCK_WRITE_P) != 0;
            var writeQ = (flags & DEBLOCK_WRITE_Q) != 0;
            if ((flags & DEBLOCK_STRONG) != 0) {
                var p3 = plane[o - 4 * stride] & 0xFF;
                var q3 = plane[o + 3 * stride] & 0xFF;
                var tc2 = 2 * tc;
                if (writeP) {
                    plane[o - stride] = (byte) Math.clamp((p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3, p0 - tc2,
                            p0 + tc2);
                    plane[o - 2 * stride] = (byte) Math.clamp((p2 + p1 + p0 + q0 + 2) >> 2, p1 - tc2, p1 + tc2);
                    plane[o - 3 * stride] = (byte) Math.clamp((2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) >> 3, p2 - tc2,
                            p2 + tc2);
                }
                if (writeQ) {
                    plane[o] = (byte) Math.clamp((p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3, q0 - tc2, q0 + tc2);
                    plane[o + stride] = (byte) Math.clamp((p0 + q0 + q1 + q2 + 2) >> 2, q1 - tc2, q1 + tc2);
                    plane[o + 2 * stride] = (byte) Math.clamp((p0 + q0 + q1 + 3 * q2 + 2 * q3 + 4) >> 3, q2 - tc2,
                            q2 + tc2);
                }
                continue;
            }
            var delta = (9 * (q0 - p0) - 3 * (q1 - p1) + 8) >> 4;
            if (Math.abs(delta) >= tc * 10) {
                continue;
            }
            delta = Math.clamp(delta, -tc, tc);
            if (writeP) {
                plane[o - stride] = (byte) Math.clamp(p0 + delta, 0, 255);
                if ((flags & DEBLOCK_P1) != 0) {
                    var deltaP = Math.clamp((((p2 + p0 + 1) >> 1) - p1 + delta) >> 1, -(tc >> 1), tc >> 1);
                    plane[o - 2 * stride] = (byte) Math.clamp(p1 + deltaP, 0, 255);
                }
            }
            if (writeQ) {
                plane[o] = (byte) Math.clamp(q0 - delta, 0, 255);
                if ((flags & DEBLOCK_Q1) != 0) {
                    var deltaQ = Math.clamp((((q2 + q0 + 1) >> 1) - q1 - delta) >> 1, -(tc >> 1), tc >> 1);
                    plane[o + stride] = (byte) Math.clamp(q1 + deltaQ, 0, 255);
                }
            }
        }
    }

    @Override
    public void inverseDct(int[] coefficients, int log2Size, int bdShift, int columns, int rows, int[] residual,
                           int[] scratch) {
        HevcTransform.dct(coefficients, log2Size, bdShift, columns, rows, residual, scratch);
    }

    @Override
    public boolean lumaMc(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                          short[] scratch) {
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h);
            return true;
        }
        if (yFrac == 0) {
            filter8(src, srcOff - 3, 1, srcStride, dst, 0, w, w, h, xFrac * 8);
            return true;
        }
        if (xFrac == 0) {
            filter8(src, srcOff - 3 * srcStride, srcStride, srcStride, dst, 0, w, w, h, yFrac * 8);
            return true;
        }
        filter8(src, srcOff - 3 * srcStride - 3, 1, srcStride, scratch, 0, w, w, h + 7, xFrac * 8);
        return filter8(scratch, 0, w, w, dst, 0, w, w, h, yFrac * 8, 6);
    }

    @Override
    public boolean chromaMc(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac, int yFrac,
                            short[] scratch) {
        if (xFrac == 0 && yFrac == 0) {
            copy(src, srcOff, srcStride, dst, w, h);
            return true;
        }
        if (yFrac == 0) {
            filter4(src, srcOff - 1, 1, srcStride, dst, 0, w, w, h, xFrac * 4);
            return true;
        }
        if (xFrac == 0) {
            filter4(src, srcOff - srcStride, srcStride, srcStride, dst, 0, w, w, h, yFrac * 4);
            return true;
        }
        filter4(src, srcOff - srcStride - 1, 1, srcStride, scratch, 0, w, w, h + 3, xFrac * 4);
        return filter4(scratch, 0, w, w, dst, 0, w, w, h, yFrac * 4, 6);
    }

    /**
     * Scales a block of 8-bit samples to the 14-bit intermediate precision.
     *
     * @param src the source plane
     * @param srcOff the index of the block's top-left sample
     * @param srcStride the source row stride
     * @param dst receives the block, {@code w} values per row
     * @param w the block width
     * @param h the block height
     */
    static void copy(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h) {
        for (var y = 0; y < h; y++) {
            var row = srcOff + y * srcStride;
            var out = y * w;
            for (var x = 0; x < w; x++) {
                dst[out + x] = (short) ((src[row + x] & 0xFF) << 6);
            }
        }
    }

    /**
     * Applies an 8-tap filter to a block of 8-bit samples: {@code dst = sum(f[k] * src[origin + k * step])},
     * which always fits 16 bits.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #LUMA_FILTER}
     */
    static void filter8(byte[] src, int origin, int step, int srcStride, short[] dst, int dstOff, int dstStride,
                        int w, int rows, int filter) {
        var f = LUMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        int c4 = f[filter + 4], c5 = f[filter + 5], c6 = f[filter + 6], c7 = f[filter + 7];
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                dst[out + x] = (short) (c0 * (src[p] & 0xFF) + c1 * (src[p + step] & 0xFF)
                        + c2 * (src[p + 2 * step] & 0xFF) + c3 * (src[p + 3 * step] & 0xFF)
                        + c4 * (src[p + 4 * step] & 0xFF) + c5 * (src[p + 5 * step] & 0xFF)
                        + c6 * (src[p + 6 * step] & 0xFF) + c7 * (src[p + 7 * step] & 0xFF));
            }
        }
    }

    /**
     * Applies a 4-tap filter to a block of 8-bit samples: {@code dst = sum(f[k] * src[origin + k * step])},
     * which always fits 16 bits.
     *
     * @param src the source samples
     * @param origin the index of the sample under the first tap of the first output
     * @param step the distance between taps: 1 horizontally, a row stride vertically
     * @param srcStride the source row stride
     * @param dst the destination
     * @param dstOff the index of the first output
     * @param dstStride the destination row stride
     * @param w the number of outputs per row
     * @param rows the number of rows
     * @param filter the offset of the filter's taps in {@link #CHROMA_FILTER}
     */
    static void filter4(byte[] src, int origin, int step, int srcStride, short[] dst, int dstOff, int dstStride,
                        int w, int rows, int filter) {
        var f = CHROMA_FILTER;
        int c0 = f[filter], c1 = f[filter + 1], c2 = f[filter + 2], c3 = f[filter + 3];
        for (var y = 0; y < rows; y++) {
            var row = origin + y * srcStride;
            var out = dstOff + y * dstStride;
            for (var x = 0; x < w; x++) {
                var p = row + x;
                dst[out + x] = (short) (c0 * (src[p] & 0xFF) + c1 * (src[p + step] & 0xFF)
                        + c2 * (src[p + 2 * step] & 0xFF) + c3 * (src[p + 3 * step] & 0xFF));
            }
        }
    }

    @Override
    public void putUni(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h) {
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (byte) Math.clamp((pred[in + x] + 32) >> 6, 0, 255);
            }
        }
    }

    @Override
    public void putBi(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h) {
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (byte) Math.clamp((pred0[in + x] + pred1[in + x] + 64) >> 7, 0, 255);
            }
        }
    }

    @Override
    public void putWeightedUni(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int weight,
                               int offset, int log2Wd) {
        var round = 1 << (log2Wd - 1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                dst[row + x] = (byte) Math.clamp(((pred[in + x] * weight + round) >> log2Wd) + offset, 0, 255);
            }
        }
    }

    @Override
    public void putWeightedBi(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                              int weight0, int weight1, int offsets, int log2Wd) {
        var round = (offsets + 1) << log2Wd;
        var shift = log2Wd + 1;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * dstStride;
            var in = y * w;
            for (var x = 0; x < w; x++) {
                var value = (pred0[in + x] * weight0 + pred1[in + x] * weight1 + round) >> shift;
                dst[row + x] = (byte) Math.clamp(value, 0, 255);
            }
        }
    }

    @Override
    public void saoEdge(byte[] dst, byte[] src, int offset, int stride, int w, int h, int deltaA, int deltaB,
                        int[] lut) {
        for (var y = 0; y < h; y++) {
            var start = offset + y * stride;
            var end = start + w;
            for (var p = start; p < end; p++) {
                var value = src[p] & 0xFF;
                var edgeIdx = 2 + Integer.signum(value - (src[p + deltaA] & 0xFF))
                        + Integer.signum(value - (src[p + deltaB] & 0xFF));
                dst[p] = (byte) Math.clamp(value + lut[edgeIdx], 0, 255);
            }
        }
    }

    @Override
    public void saoBand(byte[] dst, byte[] src, int offset, int stride, int w, int h, int position, int o0, int o1,
                        int o2, int o3) {
        for (var y = 0; y < h; y++) {
            var start = offset + y * stride;
            var end = start + w;
            for (var p = start; p < end; p++) {
                var value = src[p] & 0xFF;
                var k = ((value >> 3) - position) & 31;
                var add = k == 0 ? o0 : k == 1 ? o1 : k == 2 ? o2 : k == 3 ? o3 : 0;
                dst[p] = (byte) Math.clamp(value + add, 0, 255);
            }
        }
    }

    @Override
    public void addConstant(byte[] plane, int offset, int stride, int size, int value) {
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                plane[row + x] = (byte) Math.clamp((plane[row + x] & 0xFF) + value, 0, 255);
            }
        }
    }

    @Override
    public void addResidual(byte[] plane, int offset, int stride, int[] residual, int size) {
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            var in = y * size;
            for (var x = 0; x < size; x++) {
                plane[row + x] = (byte) Math.clamp((plane[row + x] & 0xFF) + residual[in + x], 0, 255);
            }
        }
    }
}
