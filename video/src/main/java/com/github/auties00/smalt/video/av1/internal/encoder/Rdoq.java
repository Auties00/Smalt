package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.Tables;

import java.util.Arrays;

/**
 * Optimizes the quantized levels of a transform block by rate and distortion: each level of a
 * two-dimensional class block may drop by one when that lowers the Lagrangian cost, and trailing
 * coefficients, or the whole block, may be zeroed.
 *
 * <p>Rates come from the frame's initial CDFs and are exact for the base and range tokens of each
 * coefficient given the levels already decided after it in scan order, which is the order the
 * decoder's contexts follow; the effect of a decision on the contexts of the coefficients before it
 * is not modeled. Distortion is the squared error in the transform domain, scaled like
 * {@link TileEncoder}'s transform type search.
 *
 * <p>An instance holds the frame's cost tables and scratch buffers and is used by one thread at a
 * time.
 *
 * @implNote This implementation follows the structure of libaom's {@code av1_optimize_txb}
 * ({@code av1/encoder/txb_rdopt.c}): a backward pass over the scan deciding each level
 * among its value and the next lower one with the neighborhood contexts of the decided
 * levels, then a search for the cheapest end of block. It is a greedy optimization, not a
 * trellis.
 */
final class Rdoq {
    /**
     * The cost of one equiprobable bit.
     */
    private static final int BIT = 1 << RdCost.PROB_COST_SHIFT;

    /**
     * The costs of the base tokens: transform context, plane type, context and token.
     */
    private final int[] baseCost = new int[5 * 2 * 41 * 4];

    /**
     * The costs of the last coefficient's token: transform context, plane type, context and token
     * minus one.
     */
    private final int[] eobBaseCost = new int[5 * 2 * 4 * 4];

    /**
     * The costs of a whole range token sequence: capped transform context, plane type, context and
     * level minus three.
     */
    private final int[] brCost = new int[4 * 2 * 21 * 13];

    /**
     * The costs of the end of block bin: size context, plane type and bin.
     */
    private final int[] eobPtCost = new int[7 * 2 * 12];

    /**
     * The costs of the end of block bin's extra high bit: transform context, plane type, bin
     * minus one, bit.
     */
    private final int[] eobHiCost = new int[5 * 2 * 9 * 2];

    /**
     * The costs of the all-zero flag: transform context, context and flag.
     */
    private final int[] zeroCost = new int[5 * 13 * 2];

    /**
     * The costs of the DC sign: plane group, context and sign.
     */
    private final int[] dcSignCost = new int[6 * 2];

    /**
     * The level map of the block being optimized.
     */
    private final byte[] map = new byte[32 * 34];

    /**
     * The token rate of each scan position as decided.
     */
    private final int[] rateAt = new int[1024];

    /**
     * The distortion increase of zeroing each scan position as decided, 0 for zero levels.
     */
    private final long[] gainAt = new long[1024];

    /**
     * Creates empty tables.
     */
    Rdoq() {
    }

    /**
     * The bit depth above 8 of the coefficients, by which the distortion is scaled down to the
     * 8-bit scale of the Lagrangian multiplier.
     */
    private int depthShift;

    /**
     * Fills the cost tables from a frame's initial CDFs, for coefficients of a bit depth.
     *
     * @param cdf the CDFs
     * @param bitDepth the bit depth of the samples, 8, 10 or 12
     */
    void setup(char[] cdf, int bitDepth) {
        depthShift = bitDepth - 8;
        setup(cdf);
    }

    /**
     * Fills the cost tables from a frame's initial CDFs, keeping the bit depth.
     *
     * @param cdf the CDFs
     */
    void setup(char[] cdf) {
        for (var t = 0; t < 5; t++) {
            for (var c = 0; c < 2; c++) {
                var group = t * 2 + c;
                for (var ctx = 0; ctx < 41; ctx++) {
                    RdCost.costsFromCdf(baseCost, (group * 41 + ctx) * 4, cdf,
                            Cdf.COEF_BASE_TOK + (group * 41 + ctx) * 4, 3);
                }
                for (var ctx = 0; ctx < 4; ctx++) {
                    RdCost.costsFromCdf(eobBaseCost, (group * 4 + ctx) * 4, cdf,
                            Cdf.COEF_EOB_BASE_TOK + group * 16 + ctx * 4, 2);
                }
                for (var bin = 0; bin < 9; bin++) {
                    RdCost.costsFromCdf(eobHiCost, ((group) * 9 + bin) * 2, cdf,
                            Cdf.COEF_EOB_HI_BIT + (group * 9 + bin) * 2, 1);
                }
            }
        }
        var tmp = new int[4];
        for (var t = 0; t < 4; t++) {
            for (var c = 0; c < 2; c++) {
                for (var ctx = 0; ctx < 21; ctx++) {
                    RdCost.costsFromCdf(tmp, 0, cdf, Cdf.COEF_BR_TOK + ((t * 2 + c) * 21 + ctx) * 4, 3);
                    var out = ((t * 2 + c) * 21 + ctx) * 13;
                    for (var br = 0; br < 13; br++) {
                        var cost = 0;
                        for (var k = 0; k < 4; k++) {
                            var s = Math.min(br - 3 * k, 3);
                            cost += tmp[s];
                            if (s < 3) {
                                break;
                            }
                        }
                        brCost[out + br] = cost;
                    }
                }
            }
        }
        for (var c = 0; c < 2; c++) {
            for (var sz = 0; sz < 7; sz++) {
                var off = switch (sz) {
                    case 0 -> Cdf.COEF_EOB_BIN_16 + c * 2 * 8;
                    case 1 -> Cdf.COEF_EOB_BIN_32 + c * 2 * 8;
                    case 2 -> Cdf.COEF_EOB_BIN_64 + c * 2 * 8;
                    case 3 -> Cdf.COEF_EOB_BIN_128 + c * 2 * 8;
                    case 4 -> Cdf.COEF_EOB_BIN_256 + c * 2 * 16;
                    case 5 -> Cdf.COEF_EOB_BIN_512 + c * 16;
                    default -> Cdf.COEF_EOB_BIN_1024 + c * 16;
                };
                RdCost.costsFromCdf(eobPtCost, (sz * 2 + c) * 12, cdf, off, 4 + sz);
            }
        }
        for (var t = 0; t < 5; t++) {
            for (var ctx = 0; ctx < 13; ctx++) {
                RdCost.costsFromCdf(zeroCost, (t * 13 + ctx) * 2, cdf, Cdf.COEF_SKIP + (t * 13 + ctx) * 2, 1);
            }
        }
        for (var g = 0; g < 6; g++) {
            RdCost.costsFromCdf(dcSignCost, g * 2, cdf, Cdf.COEF_DC_SIGN + g * 2, 1);
        }
    }

    /**
     * Optimizes the levels of a two-dimensional class transform block.
     *
     * @param coef the transform coefficients, in the decoder's layout
     * @param levels the signed levels, in the decoder's layout; updated in place
     * @param eob the number of coefficients up to the last non-zero level in scan order
     * @param scan the scan order
     * @param tx the transform size
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @param dcCtx the DC sign context, offset by three for the chroma planes
     * @param zeroCtx the all-zero flag context
     * @param dcDq the DC dequantizer
     * @param acDq the AC dequantizer
     * @param logScale the transform's dequantization shift
     * @param rdmult the Lagrangian multiplier
     * @return the new end of block, 0 when every level was zeroed
     */
    int optimize(int[] coef, int[] levels, int eob, int[] scan, int tx, int plane, int dcCtx, int zeroCtx, int dcDq,
                 int acDq, int logScale, int rdmult) {
        if (eob == 0) {
            return 0;
        }
        var chroma = plane != 0 ? 1 : 0;
        var tctx = Tables.TX_CTX[tx];
        var group = tctx * 2 + chroma;
        var slw = Math.min(Tables.TX_LW[tx], 3);
        var slh = Math.min(Tables.TX_LH[tx], 3);
        var tx2dszctx = slw + slh;
        var last = eob - 1;
        var nonsquare = tx >= 5 ? 1 : 0;
        var ctxOffsets = (nonsquare + (tx & nonsquare)) * 25;
        var stride = 4 << slh;
        var shift = slh + 2;
        var mask = (4 << slh) - 1;
        var lv = map;
        Arrays.fill(lv, 0, stride * ((4 << slw) + 2), (byte) 0);
        var distShift = 14 + 2 * logScale - 2 * depthShift;
        var dcSignCtx = dcCtx;
        var brGroup = (Math.min(tctx, 3) * 2 + chroma) * 21;
        for (var i = last; i >= 0; i--) {
            var rc = scan[i];
            var x = rc >> shift;
            var y = rc & mask;
            int baseOff;
            int hiCtx;
            if (i == last) {
                baseOff = -1 - ((group * 4 + eobCtx(last, tx2dszctx)) * 4);
                hiCtx = last == 0 ? 0 : (x | y) > 1 ? 14 : 7;
            } else if (i == 0) {
                baseOff = group * 41 * 4;
                var mag = ((lv[1] & 0xFF) + (lv[stride] & 0xFF) + (lv[stride + 1] & 0xFF)) & 63;
                hiCtx = mag > 12 ? 6 : (mag + 1) >> 1;
            } else {
                var lc = CoefWriter.loCtx(lv, rc, ctxOffsets, x, y, stride);
                baseOff = (group * 41 + (lc & 0xFFFF)) * 4;
                var mag = (lc >>> 16) & 63;
                hiCtx = ((x | y) > 1 ? 14 : 7) + (mag > 12 ? 6 : (mag + 1) >> 1);
            }
            var brOff = (brGroup + hiCtx) * 13;
            var dq = rc != 0 ? acDq : dcDq;
            var c = coef[rc];
            var ac = Math.abs(c);
            var level = levels[rc];
            var abs = Math.abs(level);
            var best = abs;
            var bestRate = tokenRate(baseOff, brOff, abs, i == 0, dcSignCtx, level < 0 ? 1 : 0);
            if (abs > 0) {
                var errKeep = error(ac, abs, dq, logScale);
                var bestCost = (long) bestRate * rdmult + (errKeep << distShift);
                var lower = abs - 1;
                if (lower > 0 || i != last) {
                    var rate = tokenRate(baseOff, brOff, lower, i == 0, dcSignCtx, level < 0 ? 1 : 0);
                    var errLower = error(ac, lower, dq, logScale);
                    var cost = (long) rate * rdmult + (errLower << distShift);
                    if (cost <= bestCost) {
                        best = lower;
                        bestRate = rate;
                        errKeep = errLower;
                    }
                }
                gainAt[i] = best == 0 ? 0 : (long) ac * ac - errKeep;
            } else {
                gainAt[i] = 0;
            }
            rateAt[i] = bestRate;
            if (best != abs) {
                levels[rc] = level < 0 ? -best : best;
            }
            lv[rc] = CoefWriter.levelByte(Math.min(best, 15));
        }
        var bestPos = last;
        var bestDelta = 0L;
        var removedRate = 0L;
        var removedGain = 0L;
        var lastSym = eobSymbolCost(last, tx2dszctx, chroma, group);
        var examined = 0;
        for (var p = last; p >= 0 && examined < 20; p--) {
            removedRate += rateAt[p];
            removedGain += gainAt[p];
            if (p == 0) {
                break;
            }
            var below = p - 1;
            if (levels[scan[below]] == 0) {
                continue;
            }
            examined++;
            var rc = scan[below];
            var abs = Math.abs(levels[rc]);
            var x = rc >> shift;
            var y = rc & mask;
            var eobTok = tokenRate(-1 - ((group * 4 + eobCtx(below, tx2dszctx)) * 4),
                    (brGroup + (below == 0 ? 0 : (x | y) > 1 ? 14 : 7)) * 13, abs,
                    below == 0, dcSignCtx, levels[rc] < 0 ? 1 : 0);
            var dRate = -removedRate + eobSymbolCost(below, tx2dszctx, chroma, group) - lastSym + eobTok
                    - rateAt[below];
            var delta = dRate * rdmult + (removedGain << distShift);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestPos = below;
            }
        }
        var allRate = 0L;
        var allGain = 0L;
        for (var i = last; i >= 0; i--) {
            allRate += rateAt[i];
            allGain += gainAt[i];
        }
        var zeroBase = (tctx * 13 + zeroCtx) * 2;
        var dAll = -allRate - lastSym + zeroCost[zeroBase + 1] - zeroCost[zeroBase];
        var deltaAll = dAll * rdmult + (allGain << distShift);
        if (deltaAll < bestDelta) {
            for (var i = last; i >= 0; i--) {
                levels[scan[i]] = 0;
            }
            return 0;
        }
        for (var i = last; i > bestPos; i--) {
            levels[scan[i]] = 0;
        }
        return bestPos + 1;
    }

    /**
     * Returns the squared error of a level in the transform domain.
     *
     * @param abs the coefficient magnitude
     * @param level the level magnitude
     * @param dq the dequantizer
     * @param logScale the dequantization shift
     * @return the squared error
     */
    private static long error(int abs, int level, int dq, int logScale) {
        long d;
        if (level >= 15) {
            d = ((dq * (level & 0xfffff)) & 0xffffff) >> logScale;
        } else {
            d = ((long) dq * level) >> logScale;
        }
        var e = abs - d;
        return e * e;
    }

    /**
     * Returns the rate of a coefficient's token and sign.
     *
     * @param baseOff the offset of the base token costs, or minus one minus the offset of the last
     * coefficient token costs
     * @param brOff the offset of the range token costs
     * @param level the level magnitude
     * @param dc whether the coefficient is the DC
     * @param dcCtx the DC sign context
     * @param sign the coefficient's sign, 1 when negative
     * @return the rate
     */
    private int tokenRate(int baseOff, int brOff, int level, boolean dc, int dcCtx, int sign) {
        int rate;
        var sym = Math.min(level, 3);
        if (baseOff < 0) {
            rate = eobBaseCost[-1 - baseOff + sym - 1];
        } else {
            rate = baseCost[baseOff + sym];
        }
        if (level == 0) {
            return rate;
        }
        if (level >= 3) {
            rate += brCost[brOff + Math.min(level, 15) - 3];
        }
        if (dc) {
            rate += dcSignCost[dcCtx * 2 + sign];
        } else {
            rate += BIT;
        }
        if (level >= 15) {
            var v = level - 15 + 1;
            rate += (2 * (31 - Integer.numberOfLeadingZeros(v)) + 1) * BIT;
        }
        return rate;
    }

    /**
     * Returns the context of the last coefficient's token.
     *
     * @param last the scan position of the last coefficient
     * @param tx2dszctx the size context
     * @return the context
     */
    private static int eobCtx(int last, int tx2dszctx) {
        return last == 0 ? 0 : 1 + (last > (2 << tx2dszctx) ? 1 : 0) + (last > (4 << tx2dszctx) ? 1 : 0);
    }

    /**
     * Returns the rate of coding a scan position as the end of block.
     *
     * @param last the scan position of the last coefficient
     * @param tx2dszctx the size context
     * @param chroma the plane type
     * @param group the transform context and plane type group
     * @return the rate
     */
    private int eobSymbolCost(int last, int tx2dszctx, int chroma, int group) {
        var base = (tx2dszctx * 2 + chroma) * 12;
        if (last < 2) {
            return eobPtCost[base + last];
        }
        var n = 31 - Integer.numberOfLeadingZeros(last);
        var eobBin = n - 1;
        return eobPtCost[base + n + 1] + eobHiCost[(group * 9 + eobBin) * 2 + ((last >> eobBin) & 1)]
                + eobBin * BIT;
    }
}
