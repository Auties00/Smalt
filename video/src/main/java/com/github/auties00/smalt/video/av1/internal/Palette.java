package com.github.auties00.smalt.video.av1.internal;

/**
 * Decodes palette mode: the palette colors with their neighbor cache (AV1 specification section
 * 5.11.46) and the color index map in wavefront order (sections 5.11.49 and 7.11.4).
 *
 * @implNote This implementation ports dav1d's {@code read_pal_plane}, {@code read_pal_uv},
 *           {@code copy_pal_block_y}, {@code copy_pal_block_uv} ({@code src/recon_tmpl.c}),
 *           {@code order_palette} and {@code read_pal_indices} ({@code src/decode.c}) and
 *           {@code pal_idx_finish_c} ({@code src/pal.c}).
 */
final class Palette {
    /**
     * Prevents instantiation.
     */
    private Palette() {
        throw new AssertionError();
    }

    /**
     * Decodes the palette of one plane ({@code read_pal_plane}).
     *
     * @param t     the task context
     * @param b     the block, whose palette size is set
     * @param pl    0 for luma, 1 for the first chroma plane
     * @param szCtx the block size context
     * @param bx4   the column within the above context
     * @param by4   the row within the left context
     */
    static void readPalPlane(TaskContext t, Av1Block b, int pl, int szCtx, int bx4, int by4) {
        var ts = t.ts;
        var f = t.f;
        var msac = ts.msac;
        var palSz = b.palSz[pl] = msac.decodeSymbolAdapt(ts.cdf, Cdf.PAL_SZ + (pl * 7 + szCtx) * 8, 6) + 2;
        var cache = t.palCache;
        var usedCache = t.palUsedCache;
        var lCache = pl != 0 ? t.palSzUv[32 + by4] : t.l.palSz[by4];
        var nCache = 0;
        var aCache = (by4 & 15) != 0 ? (pl != 0 ? t.palSzUv[bx4] : t.a.palSz[bx4]) : 0;
        var alPal = t.alPal;
        var l = ((32 + by4) * 3 + pl) * 8;
        var a = (bx4 * 3 + pl) * 8;
        while (lCache != 0 && aCache != 0) {
            int lv = alPal[l];
            int av = alPal[a];
            if (lv < av) {
                if (nCache == 0 || cache[nCache - 1] != lv) {
                    cache[nCache++] = lv;
                }
                l++;
                lCache--;
            } else {
                if (av == lv) {
                    l++;
                    lCache--;
                }
                if (nCache == 0 || cache[nCache - 1] != av) {
                    cache[nCache++] = av;
                }
                a++;
                aCache--;
            }
        }
        if (lCache != 0) {
            do {
                int lv = alPal[l];
                if (nCache == 0 || cache[nCache - 1] != lv) {
                    cache[nCache++] = lv;
                }
                l++;
            } while (--lCache > 0);
        } else if (aCache != 0) {
            do {
                int av = alPal[a];
                if (nCache == 0 || cache[nCache - 1] != av) {
                    cache[nCache++] = av;
                }
                a++;
            } while (--aCache > 0);
        }
        var i = 0;
        for (var n = 0; n < nCache && i < palSz; n++) {
            if (msac.decodeBoolEqui() != 0) {
                usedCache[i++] = cache[n];
            }
        }
        var nUsedCache = i;
        var pal = t.pal;
        var po = pl * 8;
        if (i < palSz) {
            var bpc = f.cur.bpc;
            int prev = msac.decodeBools(bpc);
            pal[po + i++] = (short) prev;
            if (i < palSz) {
                var bits = bpc - 3 + msac.decodeBools(2);
                var max = (1 << bpc) - 1;
                var notPl = pl == 0 ? 1 : 0;
                do {
                    var delta = msac.decodeBools(bits);
                    prev = Math.min(prev + delta + notPl, max);
                    pal[po + i++] = (short) prev;
                    if (prev + notPl >= max) {
                        for (; i < palSz; i++) {
                            pal[po + i] = (short) max;
                        }
                        break;
                    }
                    bits = Math.min(bits, 1 + (31 - Integer.numberOfLeadingZeros(max - prev - notPl)));
                } while (i < palSz);
            }
            var n = 0;
            var m = nUsedCache;
            for (i = 0; i < palSz; i++) {
                if (n < nUsedCache && (m >= palSz || usedCache[n] <= pal[po + m])) {
                    pal[po + i] = (short) usedCache[n++];
                } else {
                    pal[po + i] = pal[po + m++];
                }
            }
        } else {
            for (var k = 0; k < nUsedCache; k++) {
                pal[po + k] = (short) usedCache[k];
            }
        }
    }

    /**
     * Decodes the chroma palettes ({@code read_pal_uv}).
     *
     * @param t     the task context
     * @param b     the block
     * @param szCtx the block size context
     * @param bx4   the column within the above context
     * @param by4   the row within the left context
     */
    static void readPalUv(TaskContext t, Av1Block b, int szCtx, int bx4, int by4) {
        readPalPlane(t, b, 1, szCtx, bx4, by4);
        var msac = t.ts.msac;
        var pal = t.pal;
        var bpc = t.f.cur.bpc;
        if (msac.decodeBoolEqui() != 0) {
            var bits = bpc - 4 + msac.decodeBools(2);
            int prev = msac.decodeBools(bpc);
            pal[16] = (short) prev;
            var max = (1 << bpc) - 1;
            for (var i = 1; i < b.palSz[1]; i++) {
                var delta = msac.decodeBools(bits);
                if (delta != 0 && msac.decodeBoolEqui() != 0) {
                    delta = -delta;
                }
                prev = (prev + delta) & max;
                pal[16 + i] = (short) prev;
            }
        } else {
            for (var i = 0; i < b.palSz[1]; i++) {
                pal[16 + i] = (short) msac.decodeBools(bpc);
            }
        }
    }

    /**
     * Records the luma palette of a block as the above and left neighbor palette
     * ({@code copy_pal_block_y}).
     *
     * @param t   the task context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bw4 the block width
     * @param bh4 the block height
     */
    static void copyPalBlockY(TaskContext t, int bx4, int by4, int bw4, int bh4) {
        var alPal = t.alPal;
        for (var x = 0; x < bw4; x++) {
            System.arraycopy(t.pal, 0, alPal, ((bx4 + x) * 3) * 8, 8);
        }
        for (var y = 0; y < bh4; y++) {
            System.arraycopy(t.pal, 0, alPal, ((32 + by4 + y) * 3) * 8, 8);
        }
    }

    /**
     * Records the chroma palettes of a block as the above and left neighbor palettes
     * ({@code copy_pal_block_uv}).
     *
     * @param t   the task context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bw4 the block width
     * @param bh4 the block height
     */
    static void copyPalBlockUv(TaskContext t, int bx4, int by4, int bw4, int bh4) {
        var alPal = t.alPal;
        for (var pl = 1; pl <= 2; pl++) {
            for (var x = 0; x < bw4; x++) {
                System.arraycopy(t.pal, pl * 8, alPal, ((bx4 + x) * 3 + pl) * 8, 8);
            }
            for (var y = 0; y < bh4; y++) {
                System.arraycopy(t.pal, pl * 8, alPal, ((32 + by4 + y) * 3 + pl) * 8, 8);
            }
        }
    }

    /**
     * Computes the color order and context of each index of one wavefront diagonal
     * ({@code order_palette}).
     *
     * @param t      the task context holding the order and context scratch
     * @param idx    the unpacked indices decoded so far
     * @param stride the row stride of {@code idx}
     * @param i      the diagonal
     * @param first  the first column of the diagonal
     * @param last   the last column of the diagonal
     */
    private static void orderPalette(TaskContext t, byte[] idx, int stride, int i, int first, int last) {
        var order = t.palOrder;
        var ctx = t.palCtx;
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
     * Decodes the color index map of one plane and packs it two indices per byte
     * ({@code read_pal_indices}).
     *
     * @param t      the task context
     * @param palIdx the packed index destination
     * @param palSz  the palette size
     * @param pl     0 for luma, 1 for chroma
     * @param w4     the visible block width in 4-sample units
     * @param h4     the visible block height in 4-sample units
     * @param bw4    the block width in 4-sample units
     * @param bh4    the block height in 4-sample units
     */
    static void readPalIndices(TaskContext t, byte[] palIdx, int palSz, int pl, int w4, int h4, int bw4, int bh4) {
        var ts = t.ts;
        var msac = ts.msac;
        var stride = bw4 * 4;
        var tmp = t.palIdxUv;
        tmp[0] = (byte) msac.decodeUniform(palSz);
        var cdfBase = Cdf.COLOR_MAP + (pl * 7 + palSz - 2) * 5 * 8;
        var order = t.palOrder;
        var ctx = t.palCtx;
        for (var i = 1; i < 4 * (w4 + h4) - 1; i++) {
            var first = Math.min(i, w4 * 4 - 1);
            var last = Math.max(0, i - h4 * 4 + 1);
            orderPalette(t, tmp, stride, i, first, last);
            for (int j = first, m = 0; j >= last; j--, m++) {
                var colorIdx = msac.decodeSymbolAdapt(ts.cdf, cdfBase + ctx[m] * 8, palSz - 1);
                tmp[(i - j) * stride + j] = order[m * 8 + colorIdx];
            }
        }
        finish(palIdx, tmp, bw4 * 4, bh4 * 4, w4 * 4, h4 * 4);
    }

    /**
     * Extends the decoded indices over the invisible part of the block and packs them two per byte
     * ({@code pal_idx_finish_c}); the source and destination may be the same array.
     *
     * @param dst the packed destination, {@code bw / 2} bytes per row
     * @param src the unpacked indices, {@code bw} bytes per row
     * @param bw  the block width
     * @param bh  the block height
     * @param w   the visible width
     * @param h   the visible height
     */
    private static void finish(byte[] dst, byte[] src, int bw, int bh, int w, int h) {
        var dstW = w / 2;
        var dstBw = bw / 2;
        var s = 0;
        var d = 0;
        for (var y = 0; y < h; y++, s += bw, d += dstBw) {
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
        if (h < bh) {
            var lastRow = d - dstBw;
            for (var y = h; y < bh; y++, d += dstBw) {
                System.arraycopy(dst, lastRow, dst, d, dstBw);
            }
        }
    }
}
