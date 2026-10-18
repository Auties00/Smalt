package com.github.auties00.smalt.video.av1.internal;

/**
 * Implements the AV1 motion compensation kernels with plain scalar Java; the reference for any
 * vectorized implementation.
 *
 * @implNote This implementation ports dav1d's C kernels ({@code src/mc_tmpl.c}: {@code put_8tap_c},
 *           {@code prep_8tap_c}, their scaled and bilinear forms, {@code avg_c}, {@code w_avg_c},
 *           {@code mask_c}, {@code w_mask_c}, {@code blend_c}, {@code blend_v_c},
 *           {@code blend_h_c}, {@code warp_affine_8x8_c}, {@code warp_affine_8x8t_c},
 *           {@code emu_edge_c} and {@code resize_c}) operation by operation. One implementation
 *           serves every bit depth: it reproduces dav1d's 8-bit template ({@code intermediate_bits}
 *           4, no prep bias) when {@code bitdepthMax} is 255 and its high bit depth template
 *           ({@code intermediate_bits} {@code 14 - bitDepth}, prep bias 8192) otherwise, and it
 *           stores filter intermediates in 16-bit arrays as the C code does, so the output is
 *           bit-exact with dav1d at every bit depth.
 */
public final class ScalarMcDsp extends McDsp {
    /**
     * The row stride of the filter intermediates in the scratch buffer.
     */
    static final int MID_STRIDE = 128;

    /**
     * The bias subtracted from high bit depth compound intermediates so they fit in 16 bits.
     */
    private static final int HIGH_PREP_BIAS = 8192;

    /**
     * Creates the scalar implementation.
     */
    public ScalarMcDsp() {
    }

    /**
     * Returns the number of fractional bits of the filter intermediates at a bit depth.
     *
     * @param bitdepthMax the largest sample value
     * @return 4 for 8- and 10-bit video, 2 for 12-bit video
     */
    static int intermediateBits(int bitdepthMax) {
        return bitdepthMax == 255 ? 4 : 14 - (32 - Integer.numberOfLeadingZeros(bitdepthMax));
    }

    /**
     * Returns the bias subtracted from the compound intermediates at a bit depth.
     *
     * @param bitdepthMax the largest sample value
     * @return 0 for 8-bit video, 8192 otherwise
     */
    static int prepBias(int bitdepthMax) {
        return bitdepthMax == 255 ? 0 : HIGH_PREP_BIAS;
    }

    /**
     * Clamps a value to the sample range.
     *
     * @param value       the unclamped sample
     * @param bitdepthMax the largest sample value
     * @return the value clamped to {@code [0, bitdepthMax]}
     */
    private static int clip(int value, int bitdepthMax) {
        return value < 0 ? 0 : Math.min(value, bitdepthMax);
    }

    /**
     * Returns the index in {@link McTables#SUBPEL_FILTERS} of the horizontal filter of a block.
     *
     * @param fraction   the horizontal fraction in sixteenths
     * @param w          the block width
     * @param filterType the filter types, horizontal in bits 0-1 and vertical in bits 2-3
     * @return the index of the filter's first tap, or {@code -1} for the integer position
     */
    static int horizontalFilter(int fraction, int w, int filterType) {
        if (fraction == 0) {
            return -1;
        }
        var type = w > 4 ? filterType & 3 : 3 + (filterType & 1);
        return type * McTables.SUBPEL_TYPE_STRIDE + (fraction - 1) * McTables.TAPS;
    }

    /**
     * Returns the index in {@link McTables#SUBPEL_FILTERS} of the vertical filter of a block.
     *
     * @param fraction   the vertical fraction in sixteenths
     * @param h          the block height
     * @param filterType the filter types, horizontal in bits 0-1 and vertical in bits 2-3
     * @return the index of the filter's first tap, or {@code -1} for the integer position
     */
    static int verticalFilter(int fraction, int h, int filterType) {
        if (fraction == 0) {
            return -1;
        }
        var type = h > 4 ? filterType >> 2 : 3 + ((filterType >> 2) & 1);
        return type * McTables.SUBPEL_TYPE_STRIDE + (fraction - 1) * McTables.TAPS;
    }

    /**
     * Returns the packed filter types of an 8-tap filter combination.
     *
     * @param filter2d the filter combination, from {@link #FILTER_2D_8TAP_REGULAR} to
     *                 {@link #FILTER_2D_8TAP_SMOOTH_SHARP}
     * @return the horizontal type (0 regular, 1 smooth, 2 sharp) in bits 0-1 and the vertical
     *         type in bits 2-3
     */
    static int filterType(int filter2d) {
        return switch (filter2d) {
            case FILTER_2D_8TAP_REGULAR -> 0;
            case FILTER_2D_8TAP_REGULAR_SMOOTH -> 1 << 2;
            case FILTER_2D_8TAP_REGULAR_SHARP -> 2 << 2;
            case FILTER_2D_8TAP_SHARP_REGULAR -> 2;
            case FILTER_2D_8TAP_SHARP_SMOOTH -> 2 | (1 << 2);
            case FILTER_2D_8TAP_SHARP -> 2 | (2 << 2);
            case FILTER_2D_8TAP_SMOOTH_REGULAR -> 1;
            case FILTER_2D_8TAP_SMOOTH -> 1 | (1 << 2);
            case FILTER_2D_8TAP_SMOOTH_SHARP -> 1 | (2 << 2);
            default -> throw new IllegalArgumentException("invalid 8-tap filter " + filter2d);
        };
    }

    /**
     * Applies an 8-tap filter centered between the fourth and fifth taps.
     *
     * @param s      the samples
     * @param off    the index of the sample under the fourth tap
     * @param step   the distance between consecutive taps
     * @param filter the index of the filter's first tap in {@link McTables#SUBPEL_FILTERS}
     * @return the unrounded filter sum
     */
    private static int filter8(short[] s, int off, int step, int filter) {
        var f = McTables.SUBPEL_FILTERS;
        return f[filter] * s[off - 3 * step]
                + f[filter + 1] * s[off - 2 * step]
                + f[filter + 2] * s[off - step]
                + f[filter + 3] * s[off]
                + f[filter + 4] * s[off + step]
                + f[filter + 5] * s[off + 2 * step]
                + f[filter + 6] * s[off + 3 * step]
                + f[filter + 7] * s[off + 4 * step];
    }

    /**
     * Applies an 8-tap filter of the warp or resize tables centered between the fourth and fifth
     * taps.
     *
     * @param s      the samples
     * @param off    the index of the sample under the fourth tap
     * @param step   the distance between consecutive taps
     * @param table  the filter table
     * @param filter the index of the filter's first tap in {@code table}
     * @return the unrounded filter sum
     */
    private static int filter8(short[] s, int off, int step, byte[] table, int filter) {
        return table[filter] * s[off - 3 * step]
                + table[filter + 1] * s[off - 2 * step]
                + table[filter + 2] * s[off - step]
                + table[filter + 3] * s[off]
                + table[filter + 4] * s[off + step]
                + table[filter + 5] * s[off + 2 * step]
                + table[filter + 6] * s[off + 3 * step]
                + table[filter + 7] * s[off + 4 * step];
    }

    @Override
    public void mc(int filter2d, short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                   int w, int h, int mx, int my, short[] scratch, int bitdepthMax) {
        if (filter2d == FILTER_2D_BILINEAR) {
            putBilinear(dst, dstOff, dstStride, src, srcOff, srcStride, w, h, mx, my, scratch, bitdepthMax);
        } else {
            put8tap(dst, dstOff, dstStride, src, srcOff, srcStride, w, h, mx, my, filterType(filter2d), scratch,
                    bitdepthMax);
        }
    }

    @Override
    public void mcScaled(int filter2d, short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                         int srcStride, int w, int h, int mx, int my, int dx, int dy, short[] scratch,
                         int bitdepthMax) {
        if (filter2d == FILTER_2D_BILINEAR) {
            putBilinearScaled(dst, dstOff, dstStride, src, srcOff, srcStride, w, h, mx, my, dx, dy, scratch,
                    bitdepthMax);
        } else {
            put8tapScaled(dst, dstOff, dstStride, src, srcOff, srcStride, w, h, mx, my, dx, dy,
                    filterType(filter2d), scratch, bitdepthMax);
        }
    }

    @Override
    public void mct(int filter2d, short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w, int h,
                    int mx, int my, short[] scratch, int bitdepthMax) {
        if (filter2d == FILTER_2D_BILINEAR) {
            prepBilinear(tmp, tmpOff, src, srcOff, srcStride, w, h, mx, my, scratch, bitdepthMax);
        } else {
            prep8tap(tmp, tmpOff, src, srcOff, srcStride, w, h, mx, my, filterType(filter2d), scratch, bitdepthMax);
        }
    }

    @Override
    public void mctScaled(int filter2d, short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w,
                          int h, int mx, int my, int dx, int dy, short[] scratch, int bitdepthMax) {
        if (filter2d == FILTER_2D_BILINEAR) {
            prepBilinearScaled(tmp, tmpOff, src, srcOff, srcStride, w, h, mx, my, dx, dy, scratch, bitdepthMax);
        } else {
            prep8tapScaled(tmp, tmpOff, src, srcOff, srcStride, w, h, mx, my, dx, dy, filterType(filter2d),
                    scratch, bitdepthMax);
        }
    }

    /**
     * Copies a block unchanged.
     *
     * @param dst       the destination plane
     * @param dstOff    the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param src       the source plane
     * @param srcOff    the index of the source top-left sample
     * @param srcStride the source row stride
     * @param w         the block width
     * @param h         the block height
     */
    private static void copy(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride, int w,
                             int h) {
        for (var y = 0; y < h; y++) {
            System.arraycopy(src, srcOff, dst, dstOff, w);
            dstOff += dstStride;
            srcOff += srcStride;
        }
    }

    /**
     * Scales a block into a compound intermediate without filtering.
     *
     * @param tmp         the intermediate buffer
     * @param tmpOff      the index of the first intermediate value
     * @param src         the source plane
     * @param srcOff      the index of the source top-left sample
     * @param srcStride   the source row stride
     * @param w           the block width
     * @param h           the block height
     * @param bitdepthMax the largest sample value
     */
    private static void prepCopy(short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w, int h,
                                 int bitdepthMax) {
        var shift = intermediateBits(bitdepthMax);
        var bias = prepBias(bitdepthMax);
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                tmp[tmpOff + x] = (short) ((src[srcOff + x] << shift) - bias);
            }
            tmpOff += w;
            srcOff += srcStride;
        }
    }

    /**
     * Interpolates a block with an 8-tap filter combination into a picture.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the destination top-left sample
     * @param dstStride   the destination row stride
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal fraction in sixteenths
     * @param my          the vertical fraction in sixteenths
     * @param filterType  the packed filter types
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void put8tap(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                                int w, int h, int mx, int my, int filterType, short[] mid, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var fh = horizontalFilter(mx, w, filterType);
        var fv = verticalFilter(my, h, filterType);
        if (fh >= 0) {
            if (fv >= 0) {
                var hSh = 6 - ib;
                var hRnd = (1 << hSh) >> 1;
                var s = srcOff - 3 * srcStride;
                var m = 0;
                for (var y = 0; y < h + 7; y++) {
                    for (var x = 0; x < w; x++) {
                        mid[m + x] = (short) ((filter8(src, s + x, 1, fh) + hRnd) >> hSh);
                    }
                    m += MID_STRIDE;
                    s += srcStride;
                }
                var vSh = 6 + ib;
                var vRnd = (1 << vSh) >> 1;
                m = 3 * MID_STRIDE;
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        dst[dstOff + x] = (short) clip((filter8(mid, m + x, MID_STRIDE, fv) + vRnd) >> vSh,
                                bitdepthMax);
                    }
                    m += MID_STRIDE;
                    dstOff += dstStride;
                }
            } else {
                var rnd = 32 + ((1 << (6 - ib)) >> 1);
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        dst[dstOff + x] = (short) clip((filter8(src, srcOff + x, 1, fh) + rnd) >> 6, bitdepthMax);
                    }
                    dstOff += dstStride;
                    srcOff += srcStride;
                }
            }
        } else if (fv >= 0) {
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    dst[dstOff + x] = (short) clip((filter8(src, srcOff + x, srcStride, fv) + 32) >> 6, bitdepthMax);
                }
                dstOff += dstStride;
                srcOff += srcStride;
            }
        } else {
            copy(dst, dstOff, dstStride, src, srcOff, srcStride, w, h);
        }
    }

    /**
     * Interpolates a block with an 8-tap filter combination into a compound intermediate.
     *
     * @param tmp         the intermediate buffer
     * @param tmpOff      the index of the first intermediate value
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal fraction in sixteenths
     * @param my          the vertical fraction in sixteenths
     * @param filterType  the packed filter types
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void prep8tap(short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w, int h,
                                 int mx, int my, int filterType, short[] mid, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var bias = prepBias(bitdepthMax);
        var fh = horizontalFilter(mx, w, filterType);
        var fv = verticalFilter(my, h, filterType);
        var hSh = 6 - ib;
        var hRnd = (1 << hSh) >> 1;
        if (fh >= 0) {
            if (fv >= 0) {
                var s = srcOff - 3 * srcStride;
                var m = 0;
                for (var y = 0; y < h + 7; y++) {
                    for (var x = 0; x < w; x++) {
                        mid[m + x] = (short) ((filter8(src, s + x, 1, fh) + hRnd) >> hSh);
                    }
                    m += MID_STRIDE;
                    s += srcStride;
                }
                m = 3 * MID_STRIDE;
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        tmp[tmpOff + x] = (short) (((filter8(mid, m + x, MID_STRIDE, fv) + 32) >> 6) - bias);
                    }
                    m += MID_STRIDE;
                    tmpOff += w;
                }
            } else {
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        tmp[tmpOff + x] = (short) (((filter8(src, srcOff + x, 1, fh) + hRnd) >> hSh) - bias);
                    }
                    tmpOff += w;
                    srcOff += srcStride;
                }
            }
        } else if (fv >= 0) {
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    tmp[tmpOff + x] = (short) (((filter8(src, srcOff + x, srcStride, fv) + hRnd) >> hSh) - bias);
                }
                tmpOff += w;
                srcOff += srcStride;
            }
        } else {
            prepCopy(tmp, tmpOff, src, srcOff, srcStride, w, h, bitdepthMax);
        }
    }

    /**
     * Filters the next source row of a scaled 8-tap interpolation horizontally into the scratch
     * buffer.
     *
     * @param mid        the scratch buffer
     * @param midOff     the index of the scratch row
     * @param src        the reference plane
     * @param srcOff     the index of the row's first sample
     * @param w          the block width
     * @param mx         the horizontal start fraction in 1/1024 samples
     * @param dx         the horizontal step in 1/1024 samples
     * @param filterType the packed filter types
     * @param ib         the number of fractional bits of the intermediates
     */
    private static void scaledRow(short[] mid, int midOff, short[] src, int srcOff, int w, int mx, int dx,
                                  int filterType, int ib) {
        var hSh = 6 - ib;
        var hRnd = (1 << hSh) >> 1;
        var imx = mx;
        var ioff = srcOff;
        for (var x = 0; x < w; x++) {
            var fh = horizontalFilter(imx >> 6, w, filterType);
            mid[midOff + x] = (short) (fh >= 0 ? (filter8(src, ioff, 1, fh) + hRnd) >> hSh : src[ioff] << ib);
            imx += dx;
            ioff += imx >> 10;
            imx &= 0x3ff;
        }
    }

    /**
     * Applies an 8-tap filter vertically across the eight scratch rows of a scaled interpolation.
     *
     * @param mid    the scratch buffer
     * @param loaded the number of rows loaded so far; row {@code i} of the window is in slot
     *               {@code (loaded + i) & 7}
     * @param x      the column
     * @param filter the index of the filter's first tap in {@link McTables#SUBPEL_FILTERS}
     * @return the unrounded filter sum
     */
    private static int scaledColumn(short[] mid, int loaded, int x, int filter) {
        var f = McTables.SUBPEL_FILTERS;
        var sum = 0;
        for (var i = 0; i < 8; i++) {
            sum += f[filter + i] * mid[((loaded + i) & 7) * MID_STRIDE + x];
        }
        return sum;
    }

    /**
     * Interpolates a block of a scaled reference with an 8-tap filter combination into a picture.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the destination top-left sample
     * @param dstStride   the destination row stride
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal start fraction in 1/1024 samples
     * @param my          the vertical start fraction in 1/1024 samples
     * @param dx          the horizontal step in 1/1024 samples
     * @param dy          the vertical step in 1/1024 samples
     * @param filterType  the packed filter types
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void put8tapScaled(short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                                      int srcStride, int w, int h, int mx, int my, int dx, int dy, int filterType,
                                      short[] mid, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var rnd = (1 << ib) >> 1;
        var vSh = 6 + ib;
        var vRnd = (1 << vSh) >> 1;
        var inY = -8;
        var loaded = 0;
        var s = srcOff - 3 * srcStride;
        for (var y = 0; y < h; y++) {
            var srcY = my >> 10;
            var fv = verticalFilter((my & 0x3ff) >> 6, h, filterType);
            while (inY < srcY) {
                scaledRow(mid, (loaded & 7) * MID_STRIDE, src, s, w, mx, dx, filterType, ib);
                loaded++;
                s += srcStride;
                inY++;
            }
            if (fv >= 0) {
                for (var x = 0; x < w; x++) {
                    dst[dstOff + x] = (short) clip((scaledColumn(mid, loaded, x, fv) + vRnd) >> vSh, bitdepthMax);
                }
            } else {
                var row = ((loaded + 3) & 7) * MID_STRIDE;
                for (var x = 0; x < w; x++) {
                    dst[dstOff + x] = (short) clip((mid[row + x] + rnd) >> ib, bitdepthMax);
                }
            }
            my += dy;
            dstOff += dstStride;
        }
    }

    /**
     * Interpolates a block of a scaled reference with an 8-tap filter combination into a compound
     * intermediate.
     *
     * @param tmp         the intermediate buffer
     * @param tmpOff      the index of the first intermediate value
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal start fraction in 1/1024 samples
     * @param my          the vertical start fraction in 1/1024 samples
     * @param dx          the horizontal step in 1/1024 samples
     * @param dy          the vertical step in 1/1024 samples
     * @param filterType  the packed filter types
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void prep8tapScaled(short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w,
                                       int h, int mx, int my, int dx, int dy, int filterType, short[] mid,
                                       int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var bias = prepBias(bitdepthMax);
        var inY = -8;
        var loaded = 0;
        var s = srcOff - 3 * srcStride;
        for (var y = 0; y < h; y++) {
            var srcY = my >> 10;
            var fv = verticalFilter((my & 0x3ff) >> 6, h, filterType);
            while (inY < srcY) {
                scaledRow(mid, (loaded & 7) * MID_STRIDE, src, s, w, mx, dx, filterType, ib);
                loaded++;
                s += srcStride;
                inY++;
            }
            if (fv >= 0) {
                for (var x = 0; x < w; x++) {
                    tmp[tmpOff + x] = (short) (((scaledColumn(mid, loaded, x, fv) + 32) >> 6) - bias);
                }
            } else {
                var row = ((loaded + 3) & 7) * MID_STRIDE;
                for (var x = 0; x < w; x++) {
                    tmp[tmpOff + x] = (short) (mid[row + x] - bias);
                }
            }
            my += dy;
            tmpOff += w;
        }
    }

    /**
     * Applies the bilinear filter between a sample and the one {@code step} positions after it.
     *
     * @param s    the samples
     * @param off  the index of the first sample
     * @param step the distance to the second sample
     * @param frac the fraction in sixteenths
     * @return the unrounded filter sum, in sixteenths
     */
    private static int bilinear(short[] s, int off, int step, int frac) {
        int a = s[off];
        return 16 * a + frac * (s[off + step] - a);
    }

    /**
     * Interpolates a block with the bilinear filter into a picture.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the destination top-left sample
     * @param dstStride   the destination row stride
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal fraction in sixteenths
     * @param my          the vertical fraction in sixteenths
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void putBilinear(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                                    int w, int h, int mx, int my, short[] mid, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var rnd = (1 << ib) >> 1;
        var hSh = 4 - ib;
        var hRnd = (1 << hSh) >> 1;
        if (mx != 0) {
            if (my != 0) {
                var m = 0;
                for (var y = 0; y < h + 1; y++) {
                    for (var x = 0; x < w; x++) {
                        mid[m + x] = (short) ((bilinear(src, srcOff + x, 1, mx) + hRnd) >> hSh);
                    }
                    m += MID_STRIDE;
                    srcOff += srcStride;
                }
                var vSh = 4 + ib;
                var vRnd = (1 << vSh) >> 1;
                m = 0;
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        dst[dstOff + x] = (short) clip((bilinear(mid, m + x, MID_STRIDE, my) + vRnd) >> vSh,
                                bitdepthMax);
                    }
                    m += MID_STRIDE;
                    dstOff += dstStride;
                }
            } else {
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var px = (bilinear(src, srcOff + x, 1, mx) + hRnd) >> hSh;
                        dst[dstOff + x] = (short) clip((px + rnd) >> ib, bitdepthMax);
                    }
                    dstOff += dstStride;
                    srcOff += srcStride;
                }
            }
        } else if (my != 0) {
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    dst[dstOff + x] = (short) clip((bilinear(src, srcOff + x, srcStride, my) + 8) >> 4, bitdepthMax);
                }
                dstOff += dstStride;
                srcOff += srcStride;
            }
        } else {
            copy(dst, dstOff, dstStride, src, srcOff, srcStride, w, h);
        }
    }

    /**
     * Interpolates a block with the bilinear filter into a compound intermediate.
     *
     * @param tmp         the intermediate buffer
     * @param tmpOff      the index of the first intermediate value
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal fraction in sixteenths
     * @param my          the vertical fraction in sixteenths
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void prepBilinear(short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w, int h,
                                     int mx, int my, short[] mid, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var bias = prepBias(bitdepthMax);
        var hSh = 4 - ib;
        var hRnd = (1 << hSh) >> 1;
        if (mx != 0) {
            if (my != 0) {
                var m = 0;
                for (var y = 0; y < h + 1; y++) {
                    for (var x = 0; x < w; x++) {
                        mid[m + x] = (short) ((bilinear(src, srcOff + x, 1, mx) + hRnd) >> hSh);
                    }
                    m += MID_STRIDE;
                    srcOff += srcStride;
                }
                m = 0;
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        tmp[tmpOff + x] = (short) (((bilinear(mid, m + x, MID_STRIDE, my) + 8) >> 4) - bias);
                    }
                    m += MID_STRIDE;
                    tmpOff += w;
                }
            } else {
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        tmp[tmpOff + x] = (short) (((bilinear(src, srcOff + x, 1, mx) + hRnd) >> hSh) - bias);
                    }
                    tmpOff += w;
                    srcOff += srcStride;
                }
            }
        } else if (my != 0) {
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    tmp[tmpOff + x] = (short) (((bilinear(src, srcOff + x, srcStride, my) + hRnd) >> hSh) - bias);
                }
                tmpOff += w;
                srcOff += srcStride;
            }
        } else {
            prepCopy(tmp, tmpOff, src, srcOff, srcStride, w, h, bitdepthMax);
        }
    }

    /**
     * Filters the next source row of a scaled bilinear interpolation horizontally into the
     * scratch buffer.
     *
     * @param mid    the scratch buffer
     * @param midOff the index of the scratch row
     * @param src    the reference plane
     * @param srcOff the index of the row's first sample
     * @param w      the block width
     * @param mx     the horizontal start fraction in 1/1024 samples
     * @param dx     the horizontal step in 1/1024 samples
     * @param ib     the number of fractional bits of the intermediates
     */
    private static void scaledBilinearRow(short[] mid, int midOff, short[] src, int srcOff, int w, int mx, int dx,
                                          int ib) {
        var hSh = 4 - ib;
        var hRnd = (1 << hSh) >> 1;
        var imx = mx;
        var ioff = srcOff;
        for (var x = 0; x < w; x++) {
            mid[midOff + x] = (short) ((bilinear(src, ioff, 1, imx >> 6) + hRnd) >> hSh);
            imx += dx;
            ioff += imx >> 10;
            imx &= 0x3ff;
        }
    }

    /**
     * Interpolates a block of a scaled reference with the bilinear filter into a picture.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the destination top-left sample
     * @param dstStride   the destination row stride
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal start fraction in 1/1024 samples
     * @param my          the vertical start fraction in 1/1024 samples
     * @param dx          the horizontal step in 1/1024 samples
     * @param dy          the vertical step in 1/1024 samples
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void putBilinearScaled(short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                                          int srcStride, int w, int h, int mx, int my, int dx, int dy, short[] mid,
                                          int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var vSh = 4 + ib;
        var vRnd = (1 << vSh) >> 1;
        var inY = -2;
        var s = srcOff;
        for (var row = 0; row < h; row++) {
            var y = my >> 10;
            var mid1 = (y & 1) * MID_STRIDE;
            var mid2 = ((y + 1) & 1) * MID_STRIDE;
            var dmy = (my & 0x3ff) >> 6;
            while (inY < y) {
                scaledBilinearRow(mid, (inY & 1) * MID_STRIDE, src, s, w, mx, dx, ib);
                s += srcStride;
                inY++;
            }
            for (var x = 0; x < w; x++) {
                int a = mid[mid1 + x];
                dst[dstOff + x] = (short) clip((16 * a + dmy * (mid[mid2 + x] - a) + vRnd) >> vSh, bitdepthMax);
            }
            my += dy;
            dstOff += dstStride;
        }
    }

    /**
     * Interpolates a block of a scaled reference with the bilinear filter into a compound
     * intermediate.
     *
     * @param tmp         the intermediate buffer
     * @param tmpOff      the index of the first intermediate value
     * @param src         the reference plane
     * @param srcOff      the index of the reference sample at the block's integer position
     * @param srcStride   the reference row stride
     * @param w           the block width
     * @param h           the block height
     * @param mx          the horizontal start fraction in 1/1024 samples
     * @param my          the vertical start fraction in 1/1024 samples
     * @param dx          the horizontal step in 1/1024 samples
     * @param dy          the vertical step in 1/1024 samples
     * @param mid         the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void prepBilinearScaled(short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride, int w,
                                           int h, int mx, int my, int dx, int dy, short[] mid, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var bias = prepBias(bitdepthMax);
        var inY = -2;
        var s = srcOff;
        for (var row = 0; row < h; row++) {
            var y = my >> 10;
            var mid1 = (y & 1) * MID_STRIDE;
            var mid2 = ((y + 1) & 1) * MID_STRIDE;
            var dmy = (my & 0x3ff) >> 6;
            while (inY < y) {
                scaledBilinearRow(mid, (inY & 1) * MID_STRIDE, src, s, w, mx, dx, ib);
                s += srcStride;
                inY++;
            }
            for (var x = 0; x < w; x++) {
                int a = mid[mid1 + x];
                tmp[tmpOff + x] = (short) (((16 * a + dmy * (mid[mid2 + x] - a) + 8) >> 4) - bias);
            }
            my += dy;
            tmpOff += w;
        }
    }

    @Override
    public void avg(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2, int tmp2Off,
                    int w, int h, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var sh = ib + 1;
        var rnd = (1 << ib) + prepBias(bitdepthMax) * 2;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                dst[dstOff + x] = (short) clip((tmp1[tmp1Off + x] + tmp2[tmp2Off + x] + rnd) >> sh, bitdepthMax);
            }
            tmp1Off += w;
            tmp2Off += w;
            dstOff += dstStride;
        }
    }

    @Override
    public void wAvg(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2, int tmp2Off,
                     int w, int h, int weight, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var sh = ib + 4;
        var rnd = (8 << ib) + prepBias(bitdepthMax) * 16;
        var weight2 = 16 - weight;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                dst[dstOff + x] = (short) clip((tmp1[tmp1Off + x] * weight + tmp2[tmp2Off + x] * weight2 + rnd) >> sh,
                        bitdepthMax);
            }
            tmp1Off += w;
            tmp2Off += w;
            dstOff += dstStride;
        }
    }

    @Override
    public void mask(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2, int tmp2Off,
                     int w, int h, byte[] mask, int maskOff, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var sh = ib + 6;
        var rnd = (32 << ib) + prepBias(bitdepthMax) * 64;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var m = mask[maskOff + x] & 0xFF;
                dst[dstOff + x] = (short) clip((tmp1[tmp1Off + x] * m + tmp2[tmp2Off + x] * (64 - m) + rnd) >> sh,
                        bitdepthMax);
            }
            tmp1Off += w;
            tmp2Off += w;
            maskOff += w;
            dstOff += dstStride;
        }
    }

    @Override
    public void wMask(int layoutIndex, short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off,
                      short[] tmp2, int tmp2Off, int w, int h, byte[] mask, int maskOff, int sign, int bitdepthMax) {
        var ssHor = layoutIndex > 0;
        var ssVer = layoutIndex > 1 ? 1 : 0;
        var ib = intermediateBits(bitdepthMax);
        var bitdepth = 32 - Integer.numberOfLeadingZeros(bitdepthMax);
        var sh = ib + 6;
        var rnd = (32 << ib) + prepBias(bitdepthMax) * 64;
        var maskSh = bitdepth + ib - 4;
        var maskRnd = 1 << (maskSh - 5);
        var maskStep = ssHor ? w >> 1 : w;
        for (var row = h; row > 0; row--) {
            for (var x = 0; x < w; x++) {
                var a = tmp2[tmp2Off + x];
                var diff = tmp1[tmp1Off + x] - a;
                var m = Math.min(38 + ((Math.abs(diff) + maskRnd) >> maskSh), 64);
                dst[dstOff + x] = (short) clip((diff * m + a * 64 + rnd) >> sh, bitdepthMax);
                if (ssHor) {
                    x++;
                    var b = tmp2[tmp2Off + x];
                    var diff2 = tmp1[tmp1Off + x] - b;
                    var n = Math.min(38 + ((Math.abs(diff2) + maskRnd) >> maskSh), 64);
                    dst[dstOff + x] = (short) clip((diff2 * n + b * 64 + rnd) >> sh, bitdepthMax);
                    var mi = maskOff + (x >> 1);
                    if ((row & ssVer) != 0) {
                        mask[mi] = (byte) ((m + n + (mask[mi] & 0xFF) + 2 - sign) >> 2);
                    } else if (ssVer != 0) {
                        mask[mi] = (byte) (m + n);
                    } else {
                        mask[mi] = (byte) ((m + n + 1 - sign) >> 1);
                    }
                } else {
                    mask[maskOff + x] = (byte) m;
                }
            }
            tmp1Off += w;
            tmp2Off += w;
            dstOff += dstStride;
            if (ssVer == 0 || (row & 1) != 0) {
                maskOff += maskStep;
            }
        }
    }

    /**
     * Blends two samples with a weight out of 64.
     *
     * @param a the sample weighted by {@code 64 - m}
     * @param b the sample weighted by {@code m}
     * @param m the weight of {@code b}, from 0 to 64
     * @return the rounded blend
     */
    private static int blendPixel(int a, int b, int m) {
        return (a * (64 - m) + b * m + 32) >> 6;
    }

    @Override
    public void blend(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h, byte[] mask,
                      int maskOff) {
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                dst[dstOff + x] = (short) blendPixel(dst[dstOff + x], tmp[tmpOff + x], mask[maskOff + x] & 0xFF);
            }
            dstOff += dstStride;
            tmpOff += w;
            maskOff += w;
        }
    }

    @Override
    public void blendV(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h) {
        var masks = McTables.OBMC_MASKS;
        var cols = (w * 3) >> 2;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < cols; x++) {
                dst[dstOff + x] = (short) blendPixel(dst[dstOff + x], tmp[tmpOff + x], masks[w + x] & 0xFF);
            }
            dstOff += dstStride;
            tmpOff += w;
        }
    }

    @Override
    public void blendH(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h) {
        var masks = McTables.OBMC_MASKS;
        var rows = (h * 3) >> 2;
        for (var y = 0; y < rows; y++) {
            var m = masks[h + y] & 0xFF;
            for (var x = 0; x < w; x++) {
                dst[dstOff + x] = (short) blendPixel(dst[dstOff + x], tmp[tmpOff + x], m);
            }
            dstOff += dstStride;
            tmpOff += w;
        }
    }

    /**
     * Filters the 15 rows of an affine warp horizontally into the scratch buffer.
     *
     * @param mid       the scratch buffer, receiving 15 rows of 8 values
     * @param src       the reference plane
     * @param srcOff    the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param abcd      the shear parameters
     * @param mx        the horizontal filter position of the top-left sample
     * @param ib        the number of fractional bits of the intermediates
     */
    private static void warpRows(short[] mid, short[] src, int srcOff, int srcStride, short[] abcd, int mx,
                                 int ib) {
        var sh = 7 - ib;
        var rnd = (1 << sh) >> 1;
        var alpha = abcd[0];
        var beta = abcd[1];
        var s = srcOff - 3 * srcStride;
        var m = 0;
        for (var y = 0; y < 15; y++, mx += beta) {
            var tmx = mx;
            for (var x = 0; x < 8; x++, tmx += alpha) {
                var filter = (64 + ((tmx + 512) >> 10)) * McTables.TAPS;
                mid[m + x] = (short) ((filter8(src, s + x, 1, McTables.WARP_FILTER, filter) + rnd) >> sh);
            }
            s += srcStride;
            m += 8;
        }
    }

    @Override
    public void warp8x8(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                        short[] abcd, int mx, int my, short[] scratch, int[] indices, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        warpRows(scratch, src, srcOff, srcStride, abcd, mx, ib);
        var sh = 7 + ib;
        var rnd = (1 << sh) >> 1;
        var gamma = abcd[2];
        var delta = abcd[3];
        var m = 3 * 8;
        for (var y = 0; y < 8; y++, my += delta) {
            var tmy = my;
            for (var x = 0; x < 8; x++, tmy += gamma) {
                var filter = (64 + ((tmy + 512) >> 10)) * McTables.TAPS;
                dst[dstOff + x] = (short) clip((filter8(scratch, m + x, 8, McTables.WARP_FILTER, filter) + rnd) >> sh,
                        bitdepthMax);
            }
            m += 8;
            dstOff += dstStride;
        }
    }

    @Override
    public void warp8x8t(short[] tmp, int tmpOff, int tmpStride, short[] src, int srcOff, int srcStride,
                         short[] abcd, int mx, int my, short[] scratch, int[] indices, int bitdepthMax) {
        var ib = intermediateBits(bitdepthMax);
        var bias = prepBias(bitdepthMax);
        warpRows(scratch, src, srcOff, srcStride, abcd, mx, ib);
        var gamma = abcd[2];
        var delta = abcd[3];
        var m = 3 * 8;
        for (var y = 0; y < 8; y++, my += delta) {
            var tmy = my;
            for (var x = 0; x < 8; x++, tmy += gamma) {
                var filter = (64 + ((tmy + 512) >> 10)) * McTables.TAPS;
                tmp[tmpOff + x] = (short) (((filter8(scratch, m + x, 8, McTables.WARP_FILTER, filter) + 64) >> 7) - bias);
            }
            m += 8;
            tmpOff += tmpStride;
        }
    }

    @Override
    public void emuEdge(int bw, int bh, int iw, int ih, int x, int y, short[] dst, int dstOff, int dstStride,
                        short[] ref, int refOff, int refStride) {
        var r = refOff + Math.clamp(y, 0, ih - 1) * refStride + Math.clamp(x, 0, iw - 1);
        var leftExt = Math.clamp(-x, 0, bw - 1);
        var rightExt = Math.clamp(x + bw - iw, 0, bw - 1);
        var topExt = Math.clamp(-y, 0, bh - 1);
        var bottomExt = Math.clamp(y + bh - ih, 0, bh - 1);
        var centerW = bw - leftExt - rightExt;
        var centerH = bh - topExt - bottomExt;
        var blk = dstOff + topExt * dstStride;
        for (var row = 0; row < centerH; row++) {
            System.arraycopy(ref, r, dst, blk + leftExt, centerW);
            if (leftExt != 0) {
                var v = dst[blk + leftExt];
                for (var i = 0; i < leftExt; i++) {
                    dst[blk + i] = v;
                }
            }
            if (rightExt != 0) {
                var start = blk + leftExt + centerW;
                var v = dst[start - 1];
                for (var i = 0; i < rightExt; i++) {
                    dst[start + i] = v;
                }
            }
            r += refStride;
            blk += dstStride;
        }
        var top = dstOff + topExt * dstStride;
        var d = dstOff;
        for (var row = 0; row < topExt; row++) {
            System.arraycopy(dst, top, dst, d, bw);
            d += dstStride;
        }
        d += centerH * dstStride;
        for (var row = 0; row < bottomExt; row++) {
            System.arraycopy(dst, d - dstStride, dst, d, bw);
            d += dstStride;
        }
    }

    @Override
    public void resize(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride, int dstW,
                       int h, int srcW, int dx, int mx0, int bitdepthMax) {
        var f = McTables.RESIZE_FILTER;
        var last = srcW - 1;
        for (var y = 0; y < h; y++) {
            var mx = mx0;
            var srcX = -1;
            for (var x = 0; x < dstW; x++) {
                var filter = (mx >> 8) * McTables.TAPS;
                var sum = f[filter] * src[srcOff + Math.clamp(srcX - 3, 0, last)]
                        + f[filter + 1] * src[srcOff + Math.clamp(srcX - 2, 0, last)]
                        + f[filter + 2] * src[srcOff + Math.clamp(srcX - 1, 0, last)]
                        + f[filter + 3] * src[srcOff + Math.clamp(srcX, 0, last)]
                        + f[filter + 4] * src[srcOff + Math.clamp(srcX + 1, 0, last)]
                        + f[filter + 5] * src[srcOff + Math.clamp(srcX + 2, 0, last)]
                        + f[filter + 6] * src[srcOff + Math.clamp(srcX + 3, 0, last)]
                        + f[filter + 7] * src[srcOff + Math.clamp(srcX + 4, 0, last)];
                dst[dstOff + x] = (short) clip((-sum + 64) >> 7, bitdepthMax);
                mx += dx;
                srcX += mx >> 14;
                mx &= 0x3fff;
            }
            dstOff += dstStride;
            srcOff += srcStride;
        }
    }
}
