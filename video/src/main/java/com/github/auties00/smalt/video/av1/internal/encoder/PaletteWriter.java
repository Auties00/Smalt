package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.TaskContext;

/**
 * Writes palette mode syntax (AV1 specification sections 5.11.46 and 5.11.49): the palette
 * colors of each plane, coded against the colors of the neighboring blocks' palettes, and the
 * color index map in wavefront order, each index coded by its rank in an order the neighboring
 * indices determine.
 *
 * <p>The same methods count the cost of the syntax when given a {@link RangeEncoder} in counting
 * mode. An instance holds scratch buffers and is used by one thread at a time.
 *
 * @implNote This implementation ports libaom's {@code write_palette_colors_y},
 * {@code write_palette_colors_uv}, {@code delta_encode_palette_colors},
 * {@code av1_get_palette_delta_bits_v} ({@code av1/encoder/bitstream.c},
 * {@code av1/encoder/palette.c}) and {@code av1_get_palette_cache}
 * ({@code av1/common/pred_common.c}), with the index ordering of dav1d's
 * {@code order_palette} that the module's decoder uses.
 */
final class PaletteWriter {
    /**
     * The color cache of the plane being written.
     */
    private final int[] cache = new int[16];

    /**
     * The index order of one wavefront diagonal.
     */
    private final byte[] order = new byte[64 * 8];

    /**
     * The index contexts of one wavefront diagonal.
     */
    private final byte[] ctx = new byte[64];

    /**
     * Creates a writer.
     */
    PaletteWriter() {
    }

    /**
     * Builds the color cache of a plane from the palettes of the blocks above (within the
     * superblock row) and to the left ({@code av1_get_palette_cache}).
     *
     * @param t the task context
     * @param pl 0 for luma, 1 for the first chroma plane
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @return the number of cached colors
     */
    int buildCache(TaskContext t, int pl, int bx4, int by4) {
        var lCache = pl != 0 ? t.palSzUv[32 + by4] : t.l.palSz[by4];
        var aCache = (by4 & 15) != 0 ? (pl != 0 ? t.palSzUv[bx4] : t.a.palSz[bx4]) : 0;
        var alPal = t.alPal;
        var l = ((32 + by4) * 3 + pl) * 8;
        var a = (bx4 * 3 + pl) * 8;
        var n = 0;
        while (lCache != 0 && aCache != 0) {
            int lv = alPal[l];
            int av = alPal[a];
            if (lv < av) {
                if (n == 0 || cache[n - 1] != lv) {
                    cache[n++] = lv;
                }
                l++;
                lCache--;
            } else {
                if (av == lv) {
                    l++;
                    lCache--;
                }
                if (n == 0 || cache[n - 1] != av) {
                    cache[n++] = av;
                }
                a++;
                aCache--;
            }
        }
        while (lCache-- > 0) {
            int lv = alPal[l++];
            if (n == 0 || cache[n - 1] != lv) {
                cache[n++] = lv;
            }
        }
        while (aCache-- > 0) {
            int av = alPal[a++];
            if (n == 0 || cache[n - 1] != av) {
                cache[n++] = av;
            }
        }
        return n;
    }

    /**
     * Writes the palette size and colors of a plane coded against the color cache
     * ({@code write_palette_colors_y}, and the U colors of {@code write_palette_colors_uv}).
     *
     * @param ec the coder
     * @param cdf the CDFs
     * @param t the task context, for the cache
     * @param pl 0 for luma, 1 for U
     * @param szCtx the block size context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param colors the colors, ascending (strictly for luma)
     * @param off the offset of the colors
     * @param n the palette size, 2 to 8
     * @param depth the bit depth
     */
    void writeColors(RangeEncoder ec, char[] cdf, TaskContext t, int pl, int szCtx, int bx4, int by4, short[] colors,
                     int off, int n, int depth) {
        ec.encodeSymbol(cdf, Cdf.PAL_SZ + (pl * 7 + szCtx) * 8, n - 2, 6);
        var nCache = buildCache(t, pl, bx4, by4);
        var out = new int[8];
        var nOut = 0;
        var found = new boolean[8];
        var inCache = 0;
        for (var k = 0; k < nCache && inCache < n; k++) {
            var hit = -1;
            for (var j = 0; j < n; j++) {
                if (!found[j] && colors[off + j] == cache[k]) {
                    hit = j;
                    break;
                }
            }
            ec.encodeBoolEqui(hit >= 0 ? 1 : 0);
            if (hit >= 0) {
                found[hit] = true;
                inCache++;
            }
        }
        for (var j = 0; j < n; j++) {
            if (!found[j]) {
                out[nOut++] = colors[off + j];
            }
        }
        deltaEncode(ec, out, nOut, depth, pl == 0 ? 1 : 0);
    }

    /**
     * Writes colors ascending by their differences ({@code delta_encode_palette_colors}),
     * stopping where the decoder infers the remaining colors.
     *
     * @param ec the coder
     * @param colors the colors, ascending
     * @param num the number of colors
     * @param depth the bit depth
     * @param minVal the smallest difference, 1 for luma and 0 for U
     */
    private static void deltaEncode(RangeEncoder ec, int[] colors, int num, int depth, int minVal) {
        if (num <= 0) {
            return;
        }
        ec.encodeBits(colors[0], depth);
        if (num == 1) {
            return;
        }
        var maxDelta = 0;
        for (var i = 1; i < num; i++) {
            maxDelta = Math.max(maxDelta, colors[i] - colors[i - 1]);
        }
        var minBits = depth - 3;
        var bits = Math.max(ceilLog2(maxDelta + 1 - minVal), minBits);
        var range = (1 << depth) - colors[0] - minVal;
        ec.encodeBits(bits - minBits, 2);
        var max = (1 << depth) - 1;
        for (var i = 1; i < num; i++) {
            var delta = colors[i] - colors[i - 1];
            ec.encodeBits(delta - minVal, bits);
            if (colors[i] + minVal >= max) {
                break;
            }
            range -= delta;
            bits = Math.min(bits, ceilLog2(range));
        }
    }

    /**
     * Returns the number of bits of the largest value below a value ({@code av1_ceil_log2}).
     *
     * @param n the value
     * @return {@code ceil(log2(n))}, 0 for values below 2
     */
    private static int ceilLog2(int n) {
        return n < 2 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    /**
     * Writes the V colors of a chroma palette, by their wrapped differences or raw, whichever is
     * shorter (the V part of {@code write_palette_colors_uv}).
     *
     * @param ec the coder
     * @param colors the colors, in the order of the U colors
     * @param off the offset of the colors
     * @param n the palette size
     * @param depth the bit depth
     */
    static void writeV(RangeEncoder ec, short[] colors, int off, int n, int depth) {
        var maxVal = 1 << depth;
        var maxD = 0;
        var zeroCount = 0;
        var minBits = depth - 4;
        for (var i = 1; i < n; i++) {
            var v = Math.abs(colors[off + i] - colors[off + i - 1]);
            var d = Math.min(v, maxVal - v);
            maxD = Math.max(maxD, d);
            if (d == 0) {
                zeroCount++;
            }
        }
        var bits = Math.max(ceilLog2(maxD + 1), minBits);
        var rateDelta = 2 + depth + (bits + 1) * (n - 1) - zeroCount;
        if (rateDelta < depth * n) {
            ec.encodeBoolEqui(1);
            ec.encodeBits(bits - minBits, 2);
            ec.encodeBits(colors[off], depth);
            for (var i = 1; i < n; i++) {
                var cur = colors[off + i];
                var prev = colors[off + i - 1];
                if (cur == prev) {
                    ec.encodeBits(0, bits);
                    continue;
                }
                var delta = Math.abs(cur - prev);
                var sign = cur < prev ? 1 : 0;
                if (delta <= maxVal - delta) {
                    ec.encodeBits(delta, bits);
                    ec.encodeBoolEqui(sign);
                } else {
                    ec.encodeBits(maxVal - delta, bits);
                    ec.encodeBoolEqui(sign ^ 1);
                }
            }
        } else {
            ec.encodeBoolEqui(0);
            for (var i = 0; i < n; i++) {
                ec.encodeBits(colors[off + i], depth);
            }
        }
    }

    /**
     * Writes a value below {@code n} with the quasi-uniform code ({@code ns(n)}).
     *
     * @param ec the coder
     * @param value the value to code
     * @param n the number of possible values
     */
    private static void writeUniform(RangeEncoder ec, int value, int n) {
        var l = 32 - Integer.numberOfLeadingZeros(n);
        var m = (1 << l) - n;
        if (value < m) {
            ec.encodeBits(value, l - 1);
        } else {
            var extra = value - m;
            ec.encodeBits(m + (extra >> 1), l - 1);
            ec.encodeBits(extra & 1, 1);
        }
    }

    /**
     * Writes the color index map of a plane in wavefront order ({@code av1_write_color_map}).
     *
     * @param ec the coder
     * @param cdf the CDFs
     * @param map the indices of the visible area, one per byte
     * @param stride the row stride of {@code map}
     * @param n the palette size
     * @param pl 0 for luma, 1 for chroma
     * @param w the visible width in samples
     * @param h the visible height in samples
     */
    void writeIndices(RangeEncoder ec, char[] cdf, byte[] map, int stride, int n, int pl, int w, int h) {
        writeUniform(ec, map[0], n);
        var cdfBase = Cdf.COLOR_MAP + (pl * 7 + n - 2) * 5 * 8;
        for (var i = 1; i < w + h - 1; i++) {
            var first = Math.min(i, w - 1);
            var last = Math.max(0, i - h + 1);
            orderDiagonal(map, stride, i, first, last);
            for (int j = first, m = 0; j >= last; j--, m++) {
                var color = map[(i - j) * stride + j];
                var rank = 0;
                while (order[m * 8 + rank] != color) {
                    rank++;
                }
                ec.encodeSymbol(cdf, cdfBase + ctx[m] * 8, rank, n - 1);
            }
        }
    }

    /**
     * Computes the color order and context of each index of one wavefront diagonal
     * ({@code order_palette}).
     *
     * @param idx the indices
     * @param stride the row stride of {@code idx}
     * @param i the diagonal
     * @param first the first column of the diagonal
     * @param last the last column of the diagonal
     */
    private void orderDiagonal(byte[] idx, int stride, int i, int first, int last) {
        var haveTop = i > first;
        var p = first + (i - first) * stride;
        for (int j = first, n = 0; j >= last; haveTop = true, j--, n++, p += stride - 1) {
            var haveLeft = j > 0;
            var mask = 0;
            var o = n * 8;
            if (!haveLeft) {
                ctx[n] = 0;
                var v = idx[p - stride];
                order[o++] = v;
                mask |= 1 << v;
            } else if (!haveTop) {
                ctx[n] = 0;
                var v = idx[p - 1];
                order[o++] = v;
                mask |= 1 << v;
            } else {
                int l = idx[p - 1];
                int tp = idx[p - stride];
                int tl = idx[p - stride - 1];
                var sameTL = tp == l;
                var sameTTl = tp == tl;
                var sameLTl = l == tl;
                if (sameTL && sameTTl && sameLTl) {
                    ctx[n] = 4;
                    order[o++] = (byte) tp;
                    mask |= 1 << tp;
                } else if (sameTL) {
                    ctx[n] = 3;
                    order[o++] = (byte) tp;
                    mask |= 1 << tp;
                    order[o++] = (byte) tl;
                    mask |= 1 << tl;
                } else if (sameTTl || sameLTl) {
                    ctx[n] = 2;
                    order[o++] = (byte) tl;
                    mask |= 1 << tl;
                    var other = sameTTl ? l : tp;
                    order[o++] = (byte) other;
                    mask |= 1 << other;
                } else {
                    ctx[n] = 1;
                    var lo = Math.min(tp, l);
                    var hi = Math.max(tp, l);
                    order[o++] = (byte) lo;
                    mask |= 1 << lo;
                    order[o++] = (byte) hi;
                    mask |= 1 << hi;
                    order[o++] = (byte) tl;
                    mask |= 1 << tl;
                }
            }
            for (var bit = 0; bit < 8; bit++) {
                if ((mask & (1 << bit)) == 0) {
                    order[o++] = (byte) bit;
                }
            }
        }
    }

    /**
     * Packs the visible indices of a plane two per byte the way the decoder keeps them,
     * replicating the last visible column and row over the rest of the block
     * ({@code pal_idx_finish_c}).
     *
     * @param dst the packed destination, {@code bw / 2} bytes per row
     * @param src the indices, {@code srcStride} per row
     * @param srcStride the row stride of {@code src}
     * @param bw the block width
     * @param bh the block height
     * @param w the visible width
     * @param h the visible height
     */
    static void pack(byte[] dst, byte[] src, int srcStride, int bw, int bh, int w, int h) {
        var dstW = w / 2;
        var dstBw = bw / 2;
        for (var y = 0; y < h; y++) {
            var s = y * srcStride;
            var d = y * dstBw;
            for (var x = 0; x < dstW; x++) {
                dst[d + x] = (byte) (src[s + x * 2] | (src[s + x * 2 + 1] << 4));
            }
            if (dstW < dstBw) {
                var v = (byte) (src[s + w - 1] * 0x11);
                for (var x = dstW; x < dstBw; x++) {
                    dst[d + x] = v;
                }
            }
        }
        for (var y = h; y < bh; y++) {
            System.arraycopy(dst, (h - 1) * dstBw, dst, y * dstBw, dstBw);
        }
    }
}
