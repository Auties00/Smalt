package com.github.auties00.smalt.video.av1.internal;

/**
 * Implements the AV1 deblocking filter kernels with plain scalar Java; the reference for any
 * vector implementation.
 *
 * @implNote This implementation ports dav1d's {@code src/loopfilter_tmpl.c}
 *           ({@code loop_filter}, {@code loop_filter_h_sb128y_c}, {@code loop_filter_v_sb128y_c},
 *           {@code loop_filter_h_sb128uv_c}, {@code loop_filter_v_sb128uv_c}) operation by
 *           operation. One implementation serves every bit depth: the thresholds and clipping
 *           ranges scale with {@code bitdepthMax} exactly as dav1d's 16 bits per component
 *           template does, which at 8 bits reduces to its 8 bits per component template.
 */
public final class ScalarLoopFilterDsp extends LoopFilterDsp {
    /**
     * Creates the scalar implementation.
     */
    public ScalarLoopFilterDsp() {
    }

    @Override
    public void loopFilterSb(int plane, int dir, short[] dst, int dstOff, int stride,
                             int mask0, int mask1, int mask2,
                             byte[] lvl, int lvlOff, int b4Stride, FilterLut lut,
                             int length, short[] scratch, int bitdepthMax) {
        var bitdepthMin8 = 24 - Integer.numberOfLeadingZeros(bitdepthMax);
        if (plane == PLANE_LUMA) {
            if (dir == DIR_COLUMN_EDGES) {
                columnsLuma(dst, dstOff, stride, mask0, mask1, mask2, lvl, lvlOff, b4Stride, lut, bitdepthMin8, bitdepthMax);
            } else {
                rowsLuma(dst, dstOff, stride, mask0, mask1, mask2, lvl, lvlOff, b4Stride, lut, bitdepthMin8, bitdepthMax);
            }
        } else if (dir == DIR_COLUMN_EDGES) {
            columnsChroma(dst, dstOff, stride, mask0, mask1, lvl, lvlOff, b4Stride, lut, bitdepthMin8, bitdepthMax);
        } else {
            rowsChroma(dst, dstOff, stride, mask0, mask1, lvl, lvlOff, b4Stride, lut, bitdepthMin8, bitdepthMax);
        }
    }

    /**
     * Filters the luma column edges of a run of blocks stacked downwards.
     *
     * @param dst          the plane
     * @param dstOff       the index of the first sample right of the edge in the first block
     * @param stride       the row stride
     * @param mask0        the blocks to filter (any width)
     * @param mask1        the blocks filtered 8 samples wide
     * @param mask2        the blocks filtered 16 samples wide
     * @param lvl          the level cache
     * @param lvlOff       the index of the level of the first block
     * @param b4Stride     the level cache row stride, in blocks
     * @param lut          the filter limits
     * @param bitdepthMin8 the bit depth minus 8
     * @param bitdepthMax  the largest sample value
     */
    private static void columnsLuma(short[] dst, int dstOff, int stride, int mask0, int mask1, int mask2,
                                    byte[] lvl, int lvlOff, int b4Stride, FilterLut lut,
                                    int bitdepthMin8, int bitdepthMax) {
        var vm = mask0 | mask1 | mask2;
        var lstep = 4 * b4Stride;
        for (var y = 1; (vm & ~(y - 1)) != 0; y <<= 1, dstOff += 4 * stride, lvlOff += lstep) {
            if ((vm & y) != 0) {
                var level = lvl[lvlOff] & 0xFF;
                if (level == 0) {
                    level = lvl[lvlOff - 4] & 0xFF;
                    if (level == 0) {
                        continue;
                    }
                }
                var wd = (mask2 & y) != 0 ? 16 : (mask1 & y) != 0 ? 8 : 4;
                filter(dst, dstOff, lut.e[level], lut.i[level], level >> 4, stride, 1, wd, bitdepthMin8, bitdepthMax);
            }
        }
    }

    /**
     * Filters the luma row edges of a run of blocks laid out to the right.
     *
     * @param dst          the plane
     * @param dstOff       the index of the first sample below the edge in the first block
     * @param stride       the row stride
     * @param mask0        the blocks to filter (any width)
     * @param mask1        the blocks filtered 8 samples wide
     * @param mask2        the blocks filtered 16 samples wide
     * @param lvl          the level cache
     * @param lvlOff       the index of the level of the first block
     * @param b4Stride     the level cache row stride, in blocks
     * @param lut          the filter limits
     * @param bitdepthMin8 the bit depth minus 8
     * @param bitdepthMax  the largest sample value
     */
    private static void rowsLuma(short[] dst, int dstOff, int stride, int mask0, int mask1, int mask2,
                                 byte[] lvl, int lvlOff, int b4Stride, FilterLut lut,
                                 int bitdepthMin8, int bitdepthMax) {
        var vm = mask0 | mask1 | mask2;
        var above = 4 * b4Stride;
        for (var x = 1; (vm & ~(x - 1)) != 0; x <<= 1, dstOff += 4, lvlOff += 4) {
            if ((vm & x) != 0) {
                var level = lvl[lvlOff] & 0xFF;
                if (level == 0) {
                    level = lvl[lvlOff - above] & 0xFF;
                    if (level == 0) {
                        continue;
                    }
                }
                var wd = (mask2 & x) != 0 ? 16 : (mask1 & x) != 0 ? 8 : 4;
                filter(dst, dstOff, lut.e[level], lut.i[level], level >> 4, 1, stride, wd, bitdepthMin8, bitdepthMax);
            }
        }
    }

    /**
     * Filters the chroma column edges of a run of blocks stacked downwards.
     *
     * @param dst          the plane
     * @param dstOff       the index of the first sample right of the edge in the first block
     * @param stride       the row stride
     * @param mask0        the blocks to filter (any width)
     * @param mask1        the blocks filtered 6 samples wide
     * @param lvl          the level cache
     * @param lvlOff       the index of the level of the first block
     * @param b4Stride     the level cache row stride, in blocks
     * @param lut          the filter limits
     * @param bitdepthMin8 the bit depth minus 8
     * @param bitdepthMax  the largest sample value
     */
    private static void columnsChroma(short[] dst, int dstOff, int stride, int mask0, int mask1,
                                      byte[] lvl, int lvlOff, int b4Stride, FilterLut lut,
                                      int bitdepthMin8, int bitdepthMax) {
        var vm = mask0 | mask1;
        var lstep = 4 * b4Stride;
        for (var y = 1; (vm & ~(y - 1)) != 0; y <<= 1, dstOff += 4 * stride, lvlOff += lstep) {
            if ((vm & y) != 0) {
                var level = lvl[lvlOff] & 0xFF;
                if (level == 0) {
                    level = lvl[lvlOff - 4] & 0xFF;
                    if (level == 0) {
                        continue;
                    }
                }
                var wd = (mask1 & y) != 0 ? 6 : 4;
                filter(dst, dstOff, lut.e[level], lut.i[level], level >> 4, stride, 1, wd, bitdepthMin8, bitdepthMax);
            }
        }
    }

    /**
     * Filters the chroma row edges of a run of blocks laid out to the right.
     *
     * @param dst          the plane
     * @param dstOff       the index of the first sample below the edge in the first block
     * @param stride       the row stride
     * @param mask0        the blocks to filter (any width)
     * @param mask1        the blocks filtered 6 samples wide
     * @param lvl          the level cache
     * @param lvlOff       the index of the level of the first block
     * @param b4Stride     the level cache row stride, in blocks
     * @param lut          the filter limits
     * @param bitdepthMin8 the bit depth minus 8
     * @param bitdepthMax  the largest sample value
     */
    private static void rowsChroma(short[] dst, int dstOff, int stride, int mask0, int mask1,
                                   byte[] lvl, int lvlOff, int b4Stride, FilterLut lut,
                                   int bitdepthMin8, int bitdepthMax) {
        var vm = mask0 | mask1;
        var above = 4 * b4Stride;
        for (var x = 1; (vm & ~(x - 1)) != 0; x <<= 1, dstOff += 4, lvlOff += 4) {
            if ((vm & x) != 0) {
                var level = lvl[lvlOff] & 0xFF;
                if (level == 0) {
                    level = lvl[lvlOff - above] & 0xFF;
                    if (level == 0) {
                        continue;
                    }
                }
                var wd = (mask1 & x) != 0 ? 6 : 4;
                filter(dst, dstOff, lut.e[level], lut.i[level], level >> 4, 1, stride, wd, bitdepthMin8, bitdepthMax);
            }
        }
    }

    /**
     * Filters one edge segment of four lines (AV1 specification section 7.14.6).
     *
     * <p>Each of the four lines runs across the edge: sample {@code p0} is the last before the
     * edge and {@code q0} the first after it. The line is filtered only when its gradients are
     * within the edge and interior limits; then the widest flat filter its smoothness allows (up
     * to {@code wd} samples wide) replaces the samples, or the narrow filter adjusts the two or
     * four samples nearest the edge.
     *
     * @param dst          the plane
     * @param off          the index of {@code q0} on the first line
     * @param e            the edge limit at 8-bit precision
     * @param i            the interior limit at 8-bit precision
     * @param h            the high edge variance threshold at 8-bit precision
     * @param stridea      the index step from one line to the next (along the edge)
     * @param strideb      the index step from one sample to the next across the edge
     * @param wd           the filter width: 4, 6, 8 or 16
     * @param bitdepthMin8 the bit depth minus 8
     * @param bitdepthMax  the largest sample value
     */
    private static void filter(short[] dst, int off, int e, int i, int h, int stridea, int strideb, int wd,
                               int bitdepthMin8, int bitdepthMax) {
        var flatLimit = 1 << bitdepthMin8;
        e <<= bitdepthMin8;
        i <<= bitdepthMin8;
        h <<= bitdepthMin8;
        var diffMin = -128 * (1 << bitdepthMin8);
        var diffMax = 128 * (1 << bitdepthMin8) - 1;
        var b1 = strideb;
        var b2 = 2 * strideb;
        var b3 = 3 * strideb;
        for (var line = 0; line < 4; line++, off += stridea) {
            int p6 = 0, p5 = 0, p4 = 0, p3 = 0, p2 = 0;
            var p1 = dst[off - b2] & 0xFFFF;
            var p0 = dst[off - b1] & 0xFFFF;
            var q0 = dst[off] & 0xFFFF;
            var q1 = dst[off + b1] & 0xFFFF;
            int q2 = 0, q3 = 0, q4 = 0, q5 = 0, q6 = 0;
            var fm = Math.abs(p1 - p0) <= i && Math.abs(q1 - q0) <= i
                    && Math.abs(p0 - q0) * 2 + (Math.abs(p1 - q1) >> 1) <= e;
            if (wd > 4) {
                p2 = dst[off - b3] & 0xFFFF;
                q2 = dst[off + b2] & 0xFFFF;
                fm &= Math.abs(p2 - p1) <= i && Math.abs(q2 - q1) <= i;
                if (wd > 6) {
                    p3 = dst[off - 4 * strideb] & 0xFFFF;
                    q3 = dst[off + b3] & 0xFFFF;
                    fm &= Math.abs(p3 - p2) <= i && Math.abs(q3 - q2) <= i;
                }
            }
            if (!fm) {
                continue;
            }
            var flat8out = false;
            if (wd >= 16) {
                p6 = dst[off - 7 * strideb] & 0xFFFF;
                p5 = dst[off - 6 * strideb] & 0xFFFF;
                p4 = dst[off - 5 * strideb] & 0xFFFF;
                q4 = dst[off + 4 * strideb] & 0xFFFF;
                q5 = dst[off + 5 * strideb] & 0xFFFF;
                q6 = dst[off + 6 * strideb] & 0xFFFF;
                flat8out = Math.abs(p6 - p0) <= flatLimit && Math.abs(p5 - p0) <= flatLimit
                        && Math.abs(p4 - p0) <= flatLimit && Math.abs(q4 - q0) <= flatLimit
                        && Math.abs(q5 - q0) <= flatLimit && Math.abs(q6 - q0) <= flatLimit;
            }
            var flat8in = false;
            if (wd >= 6) {
                flat8in = Math.abs(p2 - p0) <= flatLimit && Math.abs(p1 - p0) <= flatLimit
                        && Math.abs(q1 - q0) <= flatLimit && Math.abs(q2 - q0) <= flatLimit;
            }
            if (wd >= 8) {
                flat8in &= Math.abs(p3 - p0) <= flatLimit && Math.abs(q3 - q0) <= flatLimit;
            }
            if (wd >= 16 && flat8out && flat8in) {
                dst[off - 6 * strideb] = (short) ((p6 + p6 + p6 + p6 + p6 + p6 * 2 + p5 * 2
                        + p4 * 2 + p3 + p2 + p1 + p0 + q0 + 8) >> 4);
                dst[off - 5 * strideb] = (short) ((p6 + p6 + p6 + p6 + p6 + p5 * 2 + p4 * 2
                        + p3 * 2 + p2 + p1 + p0 + q0 + q1 + 8) >> 4);
                dst[off - 4 * strideb] = (short) ((p6 + p6 + p6 + p6 + p5 + p4 * 2 + p3 * 2
                        + p2 * 2 + p1 + p0 + q0 + q1 + q2 + 8) >> 4);
                dst[off - b3] = (short) ((p6 + p6 + p6 + p5 + p4 + p3 * 2 + p2 * 2
                        + p1 * 2 + p0 + q0 + q1 + q2 + q3 + 8) >> 4);
                dst[off - b2] = (short) ((p6 + p6 + p5 + p4 + p3 + p2 * 2 + p1 * 2
                        + p0 * 2 + q0 + q1 + q2 + q3 + q4 + 8) >> 4);
                dst[off - b1] = (short) ((p6 + p5 + p4 + p3 + p2 + p1 * 2 + p0 * 2
                        + q0 * 2 + q1 + q2 + q3 + q4 + q5 + 8) >> 4);
                dst[off] = (short) ((p5 + p4 + p3 + p2 + p1 + p0 * 2 + q0 * 2
                        + q1 * 2 + q2 + q3 + q4 + q5 + q6 + 8) >> 4);
                dst[off + b1] = (short) ((p4 + p3 + p2 + p1 + p0 + q0 * 2 + q1 * 2
                        + q2 * 2 + q3 + q4 + q5 + q6 + q6 + 8) >> 4);
                dst[off + b2] = (short) ((p3 + p2 + p1 + p0 + q0 + q1 * 2 + q2 * 2
                        + q3 * 2 + q4 + q5 + q6 + q6 + q6 + 8) >> 4);
                dst[off + b3] = (short) ((p2 + p1 + p0 + q0 + q1 + q2 * 2 + q3 * 2
                        + q4 * 2 + q5 + q6 + q6 + q6 + q6 + 8) >> 4);
                dst[off + 4 * strideb] = (short) ((p1 + p0 + q0 + q1 + q2 + q3 * 2 + q4 * 2
                        + q5 * 2 + q6 + q6 + q6 + q6 + q6 + 8) >> 4);
                dst[off + 5 * strideb] = (short) ((p0 + q0 + q1 + q2 + q3 + q4 * 2 + q5 * 2
                        + q6 * 2 + q6 + q6 + q6 + q6 + q6 + 8) >> 4);
            } else if (wd >= 8 && flat8in) {
                dst[off - b3] = (short) ((p3 + p3 + p3 + 2 * p2 + p1 + p0 + q0 + 4) >> 3);
                dst[off - b2] = (short) ((p3 + p3 + p2 + 2 * p1 + p0 + q0 + q1 + 4) >> 3);
                dst[off - b1] = (short) ((p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2 + 4) >> 3);
                dst[off] = (short) ((p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3 + 4) >> 3);
                dst[off + b1] = (short) ((p1 + p0 + q0 + 2 * q1 + q2 + q3 + q3 + 4) >> 3);
                dst[off + b2] = (short) ((p0 + q0 + q1 + 2 * q2 + q3 + q3 + q3 + 4) >> 3);
            } else if (wd == 6 && flat8in) {
                dst[off - b2] = (short) ((p2 + 2 * p2 + 2 * p1 + 2 * p0 + q0 + 4) >> 3);
                dst[off - b1] = (short) ((p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3);
                dst[off] = (short) ((p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3);
                dst[off + b1] = (short) ((p0 + 2 * q0 + 2 * q1 + 2 * q2 + q2 + 4) >> 3);
            } else {
                var hev = Math.abs(p1 - p0) > h || Math.abs(q1 - q0) > h;
                if (hev) {
                    var f = Math.clamp(p1 - q1, diffMin, diffMax);
                    f = Math.clamp(3 * (q0 - p0) + f, diffMin, diffMax);
                    var f1 = Math.min(f + 4, diffMax) >> 3;
                    var f2 = Math.min(f + 3, diffMax) >> 3;
                    dst[off - b1] = (short) Math.clamp(p0 + f2, 0, bitdepthMax);
                    dst[off] = (short) Math.clamp(q0 - f1, 0, bitdepthMax);
                } else {
                    var f = Math.clamp(3 * (q0 - p0), diffMin, diffMax);
                    var f1 = Math.min(f + 4, diffMax) >> 3;
                    var f2 = Math.min(f + 3, diffMax) >> 3;
                    dst[off - b1] = (short) Math.clamp(p0 + f2, 0, bitdepthMax);
                    dst[off] = (short) Math.clamp(q0 - f1, 0, bitdepthMax);
                    f = (f1 + 1) >> 1;
                    dst[off - b2] = (short) Math.clamp(p1 + f, 0, bitdepthMax);
                    dst[off + b1] = (short) Math.clamp(q1 - f, 0, bitdepthMax);
                }
            }
        }
    }
}
