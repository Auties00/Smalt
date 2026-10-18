package com.github.auties00.smalt.video.av1.internal;

/**
 * Implements the AV1 intra prediction kernels with plain scalar Java; the reference for any
 * vectorized implementation.
 *
 * @implNote This implementation ports dav1d's C kernels in {@code src/ipred_tmpl.c}
 *           ({@code ipred_dc_c}, {@code ipred_v_c}, {@code ipred_h_c}, {@code ipred_paeth_c},
 *           {@code ipred_smooth*_c}, {@code ipred_z1_c}, {@code ipred_z2_c}, {@code ipred_z3_c},
 *           {@code ipred_filter_c}, {@code cfl_ac_c}, {@code cfl_pred}, {@code pal_pred_c}) operation
 *           by operation. The two bit-depth templates differ only in the fixed-point reciprocal
 *           used for the DC average of rectangular blocks, which is selected from
 *           {@code bitdepthMax}.
 */
public final class ScalarIpredDsp extends IpredDsp {
    /**
     * Creates the scalar implementation.
     */
    public ScalarIpredDsp() {
    }

    @Override
    public void intraPred(int mode, short[] dst, int dstOff, int stride, short[] topleft, int topleftOff,
                          int width, int height, int angle, int maxWidth, int maxHeight, short[] scratch,
                          int bitdepthMax) {
        switch (mode) {
            case DC_PRED -> splat(dst, dstOff, stride, width, height, dcGen(topleft, topleftOff, width, height,
                    bitdepthMax));
            case VERT_PRED -> vertical(dst, dstOff, stride, topleft, topleftOff, width, height);
            case HOR_PRED -> horizontal(dst, dstOff, stride, topleft, topleftOff, width, height);
            case LEFT_DC_PRED -> splat(dst, dstOff, stride, width, height, dcGenLeft(topleft, topleftOff, height));
            case TOP_DC_PRED -> splat(dst, dstOff, stride, width, height, dcGenTop(topleft, topleftOff, width));
            case DC_128_PRED -> splat(dst, dstOff, stride, width, height, (bitdepthMax + 1) >> 1);
            case Z1_PRED -> z1(dst, dstOff, stride, topleft, topleftOff, width, height, angle, scratch, bitdepthMax);
            case Z2_PRED -> z2(dst, dstOff, stride, topleft, topleftOff, width, height, angle, maxWidth, maxHeight,
                    scratch, bitdepthMax);
            case Z3_PRED -> z3(dst, dstOff, stride, topleft, topleftOff, width, height, angle, scratch, bitdepthMax);
            case SMOOTH_PRED -> smooth(dst, dstOff, stride, topleft, topleftOff, width, height);
            case SMOOTH_V_PRED -> smoothV(dst, dstOff, stride, topleft, topleftOff, width, height);
            case SMOOTH_H_PRED -> smoothH(dst, dstOff, stride, topleft, topleftOff, width, height);
            case PAETH_PRED -> paeth(dst, dstOff, stride, topleft, topleftOff, width, height);
            case FILTER_PRED -> filter(dst, dstOff, stride, topleft, topleftOff, width, height, angle, bitdepthMax);
            default -> throw new IllegalArgumentException("invalid intra prediction mode " + mode);
        }
    }

    @Override
    public void cflAc(int layout, short[] ac, short[] y, int yOff, int stride, int wPad, int hPad, int cw, int ch) {
        var ssHor = layout != 3 ? 1 : 0;
        var ssVer = layout == 1 ? 1 : 0;
        var shift = 1 + (1 - ssVer) + (1 - ssHor);
        var acOff = 0;
        var row = 0;
        var visibleWidth = cw - 4 * wPad;
        var visibleHeight = ch - 4 * hPad;
        for (; row < visibleHeight; row++) {
            var x = 0;
            for (; x < visibleWidth; x++) {
                var sum = (int) y[yOff + (x << ssHor)];
                if (ssHor != 0) {
                    sum += y[yOff + x * 2 + 1];
                }
                if (ssVer != 0) {
                    sum += y[yOff + (x << ssHor) + stride];
                    if (ssHor != 0) {
                        sum += y[yOff + x * 2 + 1 + stride];
                    }
                }
                ac[acOff + x] = (short) (sum << shift);
            }
            for (; x < cw; x++) {
                ac[acOff + x] = ac[acOff + x - 1];
            }
            acOff += cw;
            yOff += stride << ssVer;
        }
        for (; row < ch; row++) {
            System.arraycopy(ac, acOff - cw, ac, acOff, cw);
            acOff += cw;
        }
        var log2sz = Integer.numberOfTrailingZeros(cw) + Integer.numberOfTrailingZeros(ch);
        var total = cw * ch;
        var sum = (1 << log2sz) >> 1;
        for (var i = 0; i < total; i++) {
            sum += ac[i];
        }
        sum >>= log2sz;
        for (var i = 0; i < total; i++) {
            ac[i] = (short) (ac[i] - sum);
        }
    }

    @Override
    public void cflPred(int mode, short[] dst, int dstOff, int stride, short[] topleft, int topleftOff,
                        int width, int height, short[] ac, int alpha, int bitdepthMax) {
        var dc = switch (mode) {
            case DC_PRED -> dcGen(topleft, topleftOff, width, height, bitdepthMax);
            case DC_128_PRED -> (bitdepthMax + 1) >> 1;
            case TOP_DC_PRED -> dcGenTop(topleft, topleftOff, width);
            case LEFT_DC_PRED -> dcGenLeft(topleft, topleftOff, height);
            default -> throw new IllegalArgumentException("invalid chroma-from-luma mode " + mode);
        };
        cfl(dst, dstOff, stride, width, height, dc, ac, alpha, bitdepthMax);
    }

    @Override
    public void palPred(short[] dst, int dstOff, int stride, short[] pal, int palOff, byte[] idx, int idxOff,
                        int w, int h) {
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x += 2) {
                var i = idx[idxOff++];
                dst[dstOff + x] = pal[palOff + (i & 7)];
                dst[dstOff + x + 1] = pal[palOff + ((i >> 4) & 7)];
            }
            dstOff += stride;
        }
    }

    /**
     * Fills a block with one value ({@code splat_dc}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param width  the block width
     * @param height the block height
     * @param dc     the value
     */
    private static void splat(short[] dst, int dstOff, int stride, int width, int height, int dc) {
        var v = (short) dc;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                dst[dstOff + x] = v;
            }
            dstOff += stride;
        }
    }

    /**
     * Adds the scaled luma AC contribution to a DC value over a block ({@code cfl_pred}).
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param width       the block width
     * @param height      the block height
     * @param dc          the DC value
     * @param ac          the AC contribution, row stride {@code width}
     * @param alpha       the scale in 1/8 units
     * @param bitdepthMax the largest sample value
     */
    private static void cfl(short[] dst, int dstOff, int stride, int width, int height, int dc, short[] ac, int alpha,
                            int bitdepthMax) {
        var acOff = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var diff = alpha * ac[acOff + x];
                var scaled = (Math.abs(diff) + 32) >> 6;
                dst[dstOff + x] = clip(dc + (diff < 0 ? -scaled : scaled), bitdepthMax);
            }
            acOff += width;
            dstOff += stride;
        }
    }

    /**
     * Returns the rounded average of the top edge ({@code dc_gen_top}).
     *
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width, a power of two
     * @return the average
     */
    static int dcGenTop(short[] tl, int tlOff, int width) {
        var dc = width >> 1;
        for (var i = 0; i < width; i++) {
            dc += tl[tlOff + 1 + i];
        }
        return dc >> Integer.numberOfTrailingZeros(width);
    }

    /**
     * Returns the rounded average of the left edge ({@code dc_gen_left}).
     *
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param height the block height, a power of two
     * @return the average
     */
    static int dcGenLeft(short[] tl, int tlOff, int height) {
        var dc = height >> 1;
        for (var i = 0; i < height; i++) {
            dc += tl[tlOff - (1 + i)];
        }
        return dc >> Integer.numberOfTrailingZeros(height);
    }

    /**
     * Returns the rounded average of the top and left edges ({@code dc_gen}).
     *
     * @implNote This implementation divides rectangular sums by 3 or 5 with the fixed-point
     *           reciprocals of the matching dav1d template: {@code 0x5556 / 2^16} and
     *           {@code 0x3334 / 2^16} for 8-bit samples, {@code 0xAAAB / 2^17} and
     *           {@code 0x6667 / 2^17} otherwise.
     * @param tl          the edge buffer
     * @param tlOff       the index of the top-left sample
     * @param width       the block width
     * @param height      the block height
     * @param bitdepthMax the largest sample value
     * @return the average
     */
    static int dcGen(short[] tl, int tlOff, int width, int height, int bitdepthMax) {
        var dc = (width + height) >> 1;
        for (var i = 0; i < width; i++) {
            dc += tl[tlOff + 1 + i];
        }
        for (var i = 0; i < height; i++) {
            dc += tl[tlOff - (1 + i)];
        }
        dc >>>= Integer.numberOfTrailingZeros(width + height);
        if (width != height) {
            var quarter = width > height * 2 || height > width * 2;
            if (bitdepthMax == 255) {
                dc = (dc * (quarter ? 0x3334 : 0x5556)) >>> 16;
            } else {
                dc = (int) (((long) dc * (quarter ? 0x6667 : 0xAAAB)) >>> 17);
            }
        }
        return dc;
    }

    /**
     * Copies the top edge into every row ({@code ipred_v_c}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width
     * @param height the block height
     */
    private static void vertical(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(tl, tlOff + 1, dst, dstOff, width);
            dstOff += stride;
        }
    }

    /**
     * Copies each left edge sample across its row ({@code ipred_h_c}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width
     * @param height the block height
     */
    private static void horizontal(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width,
                                   int height) {
        for (var y = 0; y < height; y++) {
            var v = tl[tlOff - (1 + y)];
            for (var x = 0; x < width; x++) {
                dst[dstOff + x] = v;
            }
            dstOff += stride;
        }
    }

    /**
     * Predicts each sample from whichever of its left, top and top-left neighbors is closest to
     * their gradient ({@code ipred_paeth_c}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width
     * @param height the block height
     */
    private static void paeth(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        int topleft = tl[tlOff];
        for (var y = 0; y < height; y++) {
            int left = tl[tlOff - (y + 1)];
            for (var x = 0; x < width; x++) {
                int top = tl[tlOff + 1 + x];
                var base = left + top - topleft;
                var ldiff = Math.abs(left - base);
                var tdiff = Math.abs(top - base);
                var tldiff = Math.abs(topleft - base);
                dst[dstOff + x] = (short) (ldiff <= tdiff && ldiff <= tldiff ? left : tdiff <= tldiff ? top : topleft);
            }
            dstOff += stride;
        }
    }

    /**
     * Blends the top and left edges with the bottom-left and top-right samples
     * ({@code ipred_smooth_c}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width
     * @param height the block height
     */
    private static void smooth(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        var weights = IpredTables.SM_WEIGHTS;
        int right = tl[tlOff + width];
        int bottom = tl[tlOff - height];
        for (var y = 0; y < height; y++) {
            var wv = weights[height + y];
            int left = tl[tlOff - (1 + y)];
            for (var x = 0; x < width; x++) {
                var wh = weights[width + x];
                var pred = wv * tl[tlOff + 1 + x] + (256 - wv) * bottom + wh * left + (256 - wh) * right;
                dst[dstOff + x] = (short) ((pred + 256) >> 9);
            }
            dstOff += stride;
        }
    }

    /**
     * Blends the top edge with the bottom-left sample ({@code ipred_smooth_v_c}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width
     * @param height the block height
     */
    private static void smoothV(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        var weights = IpredTables.SM_WEIGHTS;
        int bottom = tl[tlOff - height];
        for (var y = 0; y < height; y++) {
            var wv = weights[height + y];
            for (var x = 0; x < width; x++) {
                var pred = wv * tl[tlOff + 1 + x] + (256 - wv) * bottom;
                dst[dstOff + x] = (short) ((pred + 128) >> 8);
            }
            dstOff += stride;
        }
    }

    /**
     * Blends the left edge with the top-right sample ({@code ipred_smooth_h_c}).
     *
     * @param dst    the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param tl     the edge buffer
     * @param tlOff  the index of the top-left sample
     * @param width  the block width
     * @param height the block height
     */
    private static void smoothH(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height) {
        var weights = IpredTables.SM_WEIGHTS;
        int right = tl[tlOff + width];
        for (var y = 0; y < height; y++) {
            int left = tl[tlOff - (y + 1)];
            for (var x = 0; x < width; x++) {
                var wh = weights[width + x];
                var pred = wh * left + (256 - wh) * right;
                dst[dstOff + x] = (short) ((pred + 128) >> 8);
            }
            dstOff += stride;
        }
    }

    /**
     * Returns the strength of the intra edge smoothing filter ({@code get_filter_strength}, AV1
     * specification section 7.11.2.9).
     *
     * @param wh    the block width plus height
     * @param angle the angle between the prediction direction and the edge, in degrees
     * @param isSm  whether a neighbor is smooth-predicted
     * @return the strength, 0 (off) to 3
     */
    private static int filterStrength(int wh, int angle, boolean isSm) {
        if (isSm) {
            if (wh <= 8) {
                if (angle >= 64) {
                    return 2;
                }
                if (angle >= 40) {
                    return 1;
                }
            } else if (wh <= 16) {
                if (angle >= 48) {
                    return 2;
                }
                if (angle >= 20) {
                    return 1;
                }
            } else if (wh <= 24) {
                if (angle >= 4) {
                    return 3;
                }
            } else {
                return 3;
            }
        } else {
            if (wh <= 8) {
                if (angle >= 56) {
                    return 1;
                }
            } else if (wh <= 16) {
                if (angle >= 40) {
                    return 1;
                }
            } else if (wh <= 24) {
                if (angle >= 32) {
                    return 3;
                }
                if (angle >= 16) {
                    return 2;
                }
                if (angle >= 8) {
                    return 1;
                }
            } else if (wh <= 32) {
                if (angle >= 32) {
                    return 3;
                }
                if (angle >= 4) {
                    return 2;
                }
                return 1;
            } else {
                return 3;
            }
        }
        return 0;
    }

    /**
     * Returns whether an edge is upsampled to half-sample precision ({@code get_upsample}, AV1
     * specification section 7.11.2.10).
     *
     * @param wh    the block width plus height
     * @param angle the angle between the prediction direction and the edge, in degrees
     * @param isSm  whether a neighbor is smooth-predicted
     * @return {@code true} to upsample
     */
    private static boolean upsample(int wh, int angle, boolean isSm) {
        return angle < 40 && wh <= (16 >> (isSm ? 1 : 0));
    }

    /**
     * Smooths an edge with a 5-tap kernel ({@code filter_edge}): output samples before
     * {@code limFrom} and from {@code limTo} on are copied unfiltered, and input positions are
     * clamped to {@code from .. to - 1}.
     *
     * @param out      the output buffer
     * @param outOff   the index of the first output sample
     * @param sz       the number of output samples
     * @param limFrom  the first filtered output position
     * @param limTo    the end of the filtered output positions
     * @param in       the input buffer
     * @param inOff    the index of input position 0
     * @param from     the first readable input position
     * @param to       the end of the readable input positions
     * @param strength the filter strength, 1 to 3
     */
    private static void filterEdge(short[] out, int outOff, int sz, int limFrom, int limTo, short[] in, int inOff,
                                   int from, int to, int strength) {
        var kernel = IpredTables.EDGE_KERNEL;
        var k = (strength - 1) * 5;
        var last = to - 1;
        var i = 0;
        for (var end = Math.min(sz, limFrom); i < end; i++) {
            out[outOff + i] = in[inOff + iclip(i, from, last)];
        }
        for (var end = Math.min(limTo, sz); i < end; i++) {
            var s = 0;
            for (var j = 0; j < 5; j++) {
                s += in[inOff + iclip(i - 2 + j, from, last)] * kernel[k + j];
            }
            out[outOff + i] = (short) ((s + 8) >> 4);
        }
        for (; i < sz; i++) {
            out[outOff + i] = in[inOff + iclip(i, from, last)];
        }
    }

    /**
     * Upsamples an edge to half-sample precision with a 4-tap kernel ({@code upsample_edge}),
     * clamping input positions to {@code from .. to - 1}; it writes {@code 2 * hsz - 1} samples.
     *
     * @param out         the output buffer
     * @param outOff      the index of the first output sample
     * @param hsz         the number of input samples to interpolate between, plus one
     * @param in          the input buffer
     * @param inOff       the index of input position 0
     * @param from        the first readable input position
     * @param to          the end of the readable input positions
     * @param bitdepthMax the largest sample value
     */
    private static void upsampleEdge(short[] out, int outOff, int hsz, short[] in, int inOff, int from, int to,
                                     int bitdepthMax) {
        var last = to - 1;
        var i = 0;
        for (; i < hsz - 1; i++) {
            out[outOff + i * 2] = in[inOff + iclip(i, from, last)];
            var s = -in[inOff + iclip(i - 1, from, last)]
                    + 9 * in[inOff + iclip(i, from, last)]
                    + 9 * in[inOff + iclip(i + 1, from, last)]
                    - in[inOff + iclip(i + 2, from, last)];
            out[outOff + i * 2 + 1] = clip((s + 8) >> 4, bitdepthMax);
        }
        out[outOff + i * 2] = in[inOff + iclip(i, from, last)];
    }

    /**
     * Predicts a block along a direction below 90 degrees from the top and top-right edges
     * ({@code ipred_z1_c}).
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param tl          the edge buffer
     * @param tlOff       the index of the top-left sample
     * @param width       the block width
     * @param height      the block height
     * @param angle       the packed angle argument
     * @param scratch     the scratch buffer for the filtered or upsampled top edge
     * @param bitdepthMax the largest sample value
     */
    private static void z1(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height,
                           int angle, short[] scratch, int bitdepthMax) {
        var isSm = ((angle >> 9) & 1) != 0;
        var edgeFilter = (angle >> 10) != 0;
        angle &= 511;
        var dx = IpredTables.DR_INTRA_DERIVATIVE[angle >> 1];
        short[] top;
        int topOff;
        int maxBaseX;
        var upsampleAbove = edgeFilter && upsample(width + height, 90 - angle, isSm);
        if (upsampleAbove) {
            upsampleEdge(scratch, 0, width + height, tl, tlOff + 1, -1, width + Math.min(width, height),
                    bitdepthMax);
            top = scratch;
            topOff = 0;
            maxBaseX = 2 * (width + height) - 2;
            dx <<= 1;
        } else {
            var strength = edgeFilter ? filterStrength(width + height, 90 - angle, isSm) : 0;
            if (strength != 0) {
                filterEdge(scratch, 0, width + height, 0, width + height, tl, tlOff + 1, -1,
                        width + Math.min(width, height), strength);
                top = scratch;
                topOff = 0;
                maxBaseX = width + height - 1;
            } else {
                top = tl;
                topOff = tlOff + 1;
                maxBaseX = width + Math.min(width, height) - 1;
            }
        }
        var baseInc = upsampleAbove ? 2 : 1;
        var xpos = dx;
        for (var y = 0; y < height; y++, dstOff += stride, xpos += dx) {
            var frac = xpos & 0x3E;
            var base = xpos >> 6;
            for (var x = 0; x < width; x++, base += baseInc) {
                if (base < maxBaseX) {
                    var v = top[topOff + base] * (64 - frac) + top[topOff + base + 1] * frac;
                    dst[dstOff + x] = (short) ((v + 32) >> 6);
                } else {
                    var fill = top[topOff + maxBaseX];
                    for (; x < width; x++) {
                        dst[dstOff + x] = fill;
                    }
                    break;
                }
            }
        }
    }

    /**
     * Predicts a block along a direction between 90 and 180 degrees from the top, top-left and
     * left edges ({@code ipred_z2_c}).
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param tl          the edge buffer
     * @param tlOff       the index of the top-left sample
     * @param width       the block width
     * @param height      the block height
     * @param angle       the packed angle argument
     * @param maxWidth    the samples to the picture's right edge, limiting the top edge filter
     * @param maxHeight   the samples to the picture's bottom edge, limiting the left edge filter
     * @param scratch     the scratch buffer holding the filtered or upsampled edges
     * @param bitdepthMax the largest sample value
     */
    private static void z2(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height,
                           int angle, int maxWidth, int maxHeight, short[] scratch, int bitdepthMax) {
        var isSm = ((angle >> 9) & 1) != 0;
        var edgeFilter = (angle >> 10) != 0;
        angle &= 511;
        var dy = IpredTables.DR_INTRA_DERIVATIVE[(angle - 90) >> 1];
        var dx = IpredTables.DR_INTRA_DERIVATIVE[(180 - angle) >> 1];
        var upsampleLeft = edgeFilter && upsample(width + height, 180 - angle, isSm);
        var upsampleAbove = edgeFilter && upsample(width + height, angle - 90, isSm);
        var edge = scratch;
        var topleft = 64;
        if (upsampleAbove) {
            upsampleEdge(edge, topleft, width + 1, tl, tlOff, 0, width + 1, bitdepthMax);
            dx <<= 1;
        } else {
            var strength = edgeFilter ? filterStrength(width + height, angle - 90, isSm) : 0;
            if (strength != 0) {
                filterEdge(edge, topleft + 1, width, 0, maxWidth, tl, tlOff + 1, -1, width, strength);
            } else {
                System.arraycopy(tl, tlOff + 1, edge, topleft + 1, width);
            }
        }
        if (upsampleLeft) {
            upsampleEdge(edge, topleft - height * 2, height + 1, tl, tlOff - height, 0, height + 1, bitdepthMax);
            dy <<= 1;
        } else {
            var strength = edgeFilter ? filterStrength(width + height, 180 - angle, isSm) : 0;
            if (strength != 0) {
                filterEdge(edge, topleft - height, height, height - maxHeight, height, tl, tlOff - height, 0,
                        height + 1, strength);
            } else {
                System.arraycopy(tl, tlOff - height, edge, topleft - height, height);
            }
        }
        edge[topleft] = tl[tlOff];
        var baseIncX = upsampleAbove ? 2 : 1;
        var upLeft = upsampleLeft ? 1 : 0;
        var left = topleft - (1 + upLeft);
        var xpos = ((1 + (upsampleAbove ? 1 : 0)) << 6) - dx;
        for (var y = 0; y < height; y++, xpos -= dx, dstOff += stride) {
            var baseX = xpos >> 6;
            var fracX = xpos & 0x3E;
            var ypos = (y << (6 + upLeft)) - dy;
            for (var x = 0; x < width; x++, baseX += baseIncX, ypos -= dy) {
                int v;
                if (baseX >= 0) {
                    v = edge[topleft + baseX] * (64 - fracX) + edge[topleft + baseX + 1] * fracX;
                } else {
                    var baseY = ypos >> 6;
                    var fracY = ypos & 0x3E;
                    v = edge[left - baseY] * (64 - fracY) + edge[left - (baseY + 1)] * fracY;
                }
                dst[dstOff + x] = (short) ((v + 32) >> 6);
            }
        }
    }

    /**
     * Predicts a block along a direction above 180 degrees from the left and bottom-left edges
     * ({@code ipred_z3_c}).
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param tl          the edge buffer
     * @param tlOff       the index of the top-left sample
     * @param width       the block width
     * @param height      the block height
     * @param angle       the packed angle argument
     * @param scratch     the scratch buffer for the filtered or upsampled left edge
     * @param bitdepthMax the largest sample value
     */
    private static void z3(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height,
                           int angle, short[] scratch, int bitdepthMax) {
        var isSm = ((angle >> 9) & 1) != 0;
        var edgeFilter = (angle >> 10) != 0;
        angle &= 511;
        var dy = IpredTables.DR_INTRA_DERIVATIVE[(270 - angle) >> 1];
        short[] left;
        int leftOff;
        int maxBaseY;
        var upsampleLeft = edgeFilter && upsample(width + height, angle - 180, isSm);
        if (upsampleLeft) {
            upsampleEdge(scratch, 0, width + height, tl, tlOff - (width + height), Math.max(width - height, 0),
                    width + height + 1, bitdepthMax);
            left = scratch;
            leftOff = 2 * (width + height) - 2;
            maxBaseY = 2 * (width + height) - 2;
            dy <<= 1;
        } else {
            var strength = edgeFilter ? filterStrength(width + height, angle - 180, isSm) : 0;
            if (strength != 0) {
                filterEdge(scratch, 0, width + height, 0, width + height, tl, tlOff - (width + height),
                        Math.max(width - height, 0), width + height + 1, strength);
                left = scratch;
                leftOff = width + height - 1;
                maxBaseY = width + height - 1;
            } else {
                left = tl;
                leftOff = tlOff - 1;
                maxBaseY = height + Math.min(width, height) - 1;
            }
        }
        var baseInc = upsampleLeft ? 2 : 1;
        var ypos = dy;
        for (var x = 0; x < width; x++, ypos += dy) {
            var frac = ypos & 0x3E;
            var base = ypos >> 6;
            for (var y = 0; y < height; y++, base += baseInc) {
                if (base < maxBaseY) {
                    var v = left[leftOff - base] * (64 - frac) + left[leftOff - (base + 1)] * frac;
                    dst[dstOff + y * stride + x] = (short) ((v + 32) >> 6);
                } else {
                    var fill = left[leftOff - maxBaseY];
                    for (; y < height; y++) {
                        dst[dstOff + y * stride + x] = fill;
                    }
                    break;
                }
            }
        }
    }

    /**
     * Predicts a block 4x2 samples at a time with the recursive intra filter
     * ({@code ipred_filter_c}): each 4x2 unit is a weighted sum of the seven samples above and to
     * its left, which may themselves be predicted.
     *
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride
     * @param tl          the edge buffer
     * @param tlOff       the index of the top-left sample
     * @param width       the block width, at most 32
     * @param height      the block height, at most 32
     * @param filterIndex the filter mode in its low 9 bits, 0 to 4
     * @param bitdepthMax the largest sample value
     */
    private static void filter(short[] dst, int dstOff, int stride, short[] tl, int tlOff, int width, int height,
                               int filterIndex, int bitdepthMax) {
        var taps = IpredTables.FILTER_INTRA_TAPS;
        var tapsOff = (filterIndex & 511) * 56;
        var topArr = tl;
        var topOff = tlOff + 1;
        for (var y = 0; y < height; y += 2) {
            var cornerArr = tl;
            var cornerOff = tlOff - y;
            var leftArr = tl;
            var leftOff = cornerOff - 1;
            var leftStride = -1;
            for (var x = 0; x < width; x += 4) {
                int p0 = cornerArr[cornerOff];
                int p1 = topArr[topOff];
                int p2 = topArr[topOff + 1];
                int p3 = topArr[topOff + 2];
                int p4 = topArr[topOff + 3];
                int p5 = leftArr[leftOff];
                int p6 = leftArr[leftOff + leftStride];
                var ptr = dstOff + x;
                var t = tapsOff;
                for (var yy = 0; yy < 2; yy++) {
                    for (var xx = 0; xx < 4; xx++, t += 7) {
                        var acc = taps[t] * p0 + taps[t + 1] * p1 + taps[t + 2] * p2 + taps[t + 3] * p3
                                + taps[t + 4] * p4 + taps[t + 5] * p5 + taps[t + 6] * p6;
                        dst[ptr + xx] = clip((acc + 8) >> 4, bitdepthMax);
                    }
                    ptr += stride;
                }
                leftArr = dst;
                leftOff = dstOff + x + 3;
                leftStride = stride;
                topOff += 4;
                cornerArr = topArr;
                cornerOff = topOff - 1;
            }
            topArr = dst;
            topOff = dstOff + stride;
            dstOff += stride * 2;
        }
    }

    /**
     * Clamps a value to a range, as dav1d's {@code iclip} does: values below {@code min} become
     * {@code min}, then values above {@code max} become {@code max}.
     *
     * @param v   the value
     * @param min the lower bound
     * @param max the upper bound
     * @return the clamped value
     */
    private static int iclip(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    /**
     * Clamps a value to the sample range.
     *
     * @param v           the value
     * @param bitdepthMax the largest sample value
     * @return the clamped sample
     */
    private static short clip(int v, int bitdepthMax) {
        return (short) (v < 0 ? 0 : Math.min(v, bitdepthMax));
    }
}
