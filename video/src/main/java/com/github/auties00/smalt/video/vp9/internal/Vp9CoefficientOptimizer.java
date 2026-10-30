/* Ports libvpx 1.15.1 (vp9/encoder/vp9_encodemb.c: vp9_optimize_b). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Optimizes the quantized coefficients of a VP9 transform block by their rate and distortion,
 * libvpx's greedy trellis quantization: in scan order, each nonzero level is kept or lowered by
 * one in magnitude, whichever costs less with the next token's cost, and the end of block moves
 * to the position after which dropping every remaining level scores best.
 *
 * <p>Rates come from the token costs of the frame, distortions from the coefficients in the
 * 8-bit domain (high bit depth coefficients are scaled down first). An instance holds the
 * token cache of one block and belongs to one coding thread.
 */
final class Vp9CoefficientOptimizer {
    /**
     * The weight of the rate multiplier of each reference type and plane type, libvpx's
     * {@code plane_rd_mult}, {@code [inter][chroma]}.
     */
    private static final int[] PLANE_RD_MULT = {10, 6, 8, 5};

    /**
     * The initial accumulated distortion, larger than any block's, so that the accumulation never
     * goes negative.
     */
    private static final long ERROR_BASE = 1L << 50;

    /**
     * The energy class of each coded position's token, by raster position.
     */
    private final byte[] tokenCache = new byte[1024];

    /**
     * The token costs of the frame.
     */
    private final Vp9RdCosts tokens;

    /**
     * The costs of the levels' signs and extra bits at the coefficients' bit depth.
     */
    private final Vp9RdCosts values;

    /**
     * Creates an optimizer reading a frame's token costs and the level costs of a bit depth.
     *
     * @param tokens the token costs, rebuilt for each frame before use
     * @param values the level costs of the bit depth of the coefficients optimized
     */
    Vp9CoefficientOptimizer(Vp9RdCosts tokens, Vp9RdCosts values) {
        this.tokens = tokens;
        this.values = values;
    }

    /**
     * Returns the rate-distortion score of a rate and a distortion, libvpx's {@code RDCOST}.
     */
    private static long rdCost(long rdMult, long rate, long dist) {
        return ((rate * rdMult + 256) >> Vp9EncTables.PROB_COST_SHIFT) + (dist << Vp9RtCoder.RDDIV);
    }

    /**
     * Shifts a value right, rounding its magnitude down, libvpx's
     * {@code RIGHT_SHIFT_POSSIBLY_NEGATIVE}.
     */
    private static int shiftMagnitude(int v, int shift) {
        return v >= 0 ? v >> shift : -((-v) >> shift);
    }

    /**
     * Optimizes the levels of one transform block, libvpx's {@code vp9_optimize_b}, updating its
     * quantized and dequantized coefficients in place.
     *
     * @param coeff the coefficients before quantization
     * @param qcoeff the quantized coefficients
     * @param dqcoeff the dequantized coefficients
     * @param off the index of the block's first coefficient in the three arrays
     * @param eob the end of block of the quantization
     * @param txSize the transform size
     * @param txType the transform type, which selects the scan
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param inter whether the block is an inter block
     * @param ctx the block's nonzero context, from 0 to 2
     * @param dcStep the DC dequantization factor
     * @param acStep the AC dequantization factor
     * @param rdMult the rate multiplier of the block
     * @param bitDepth the bit depth of the coefficients, 8, 10 or 12
     * @return the end of block after the optimization
     */
    int optimize(int[] coeff, int[] qcoeff, int[] dqcoeff, int off, int eob, int txSize, int txType, int plane,
                 boolean inter, int ctx, int dcStep, int acStep, int rdMult, int bitDepth) {
        var chroma = plane != 0;
        var defaultEob = 16 << (txSize << 1);
        var shift = txSize == Vp9Constants.TX_32X32 ? 1 : 0;
        var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
        var nb = Vp9RtCoder.NEIGHBORS[txSize * 4 + txType];
        var bands = txSize == Vp9Constants.TX_4X4 ? Vp9ProbTables.BAND_4X4 : Vp9ProbTables.BAND_8X8_PLUS;
        var mult = ((long) rdMult * PLANE_RD_MULT[(inter ? 2 : 0) + (chroma ? 1 : 0)]) >> 1;
        var bdShift = bitDepth - 8;
        var tc = tokens.token;
        var costs = values;
        var cache = tokenCache;
        for (var i = 0; i < eob; i++) {
            var rc = scan[i];
            cache[rc] = Vp9Constants.ENERGY_CLASS[costs.valueCost(qcoeff[off + rc]) & 15];
        }
        var finalEob = 0;
        long accuRate = 0;
        var accuError = ERROR_BASE;
        var rate = (long) tc[Vp9RdCosts.tokenBase(txSize, plane, inter, bands[0], false, ctx) + Vp9EncTables.EOB_TOKEN];
        var bestBlockRd = rdCost(mult, rate, accuError);
        var xPrev = 1;
        var beforeBestEobQc = 0;
        var beforeBestEobDqc = 0;
        for (var i = 0; i < eob; i++) {
            var rc = scan[i];
            var at = off + rc;
            var x = qcoeff[at];
            var bandCur = bands[i];
            var ctxCur = i == 0 ? ctx : (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
            var curBase = Vp9RdCosts.tokenBase(txSize, plane, inter, bandCur, xPrev == 0, ctxCur);
            if (x == 0) {
                accuRate += tc[curBase + Vp9EncTables.ZERO_TOKEN];
                xPrev = 0;
                continue;
            }
            var dqv = rc != 0 ? acStep : dcStep;
            var diffForZero = shiftMagnitude(-coeff[at] * (1 << shift), bdShift);
            var distortionForZero = (long) diffForZero * diffForZero;
            var diff0 = shiftMagnitude((dqcoeff[at] - coeff[at]) * (1 << shift), bdShift);
            var distortion0 = (long) diff0 * diff0;
            var sign = x < 0 ? -1 : 0;
            var x1 = x - 2 * sign - 1;
            long distortion1;
            if (x1 != 0) {
                var diffStep = ((dqv >> bdShift) + sign) ^ sign;
                var diff1 = diff0 - diffStep;
                distortion1 = (long) diff1 * diff1;
            } else {
                distortion1 = distortionForZero;
            }
            var vc0 = costs.valueCost(x);
            var vc1 = costs.valueCost(x1);
            var t0 = vc0 & 15;
            var t1 = vc1 & 15;
            var rate0 = (long) (vc0 >> 4) + tc[curBase + t0];
            var rate1 = (long) (vc1 >> 4) + tc[curBase + t1];
            long nextBits0 = 0;
            long nextBits1 = 0;
            long nextEobBits0 = 0;
            long nextEobBits1 = 0;
            if (i < defaultEob - 1) {
                var bandNext = bands[i + 1];
                var tokenNext = i + 1 != eob ? costs.valueCost(qcoeff[off + scan[i + 1]]) & 15 : Vp9EncTables.EOB_TOKEN;
                cache[rc] = Vp9Constants.ENERGY_CLASS[t0];
                var ctxNext = (1 + cache[nb[2 * (i + 1)]] + cache[nb[2 * (i + 1) + 1]]) >> 1;
                var nextBase = Vp9RdCosts.tokenBase(txSize, plane, inter, bandNext, false, ctxNext);
                nextBits0 = tc[nextBase + tokenNext];
                nextEobBits0 = tc[nextBase + Vp9EncTables.EOB_TOKEN];
                cache[rc] = Vp9Constants.ENERGY_CLASS[t1];
                ctxNext = (1 + cache[nb[2 * (i + 1)]] + cache[nb[2 * (i + 1) + 1]]) >> 1;
                nextBase = Vp9RdCosts.tokenBase(txSize, plane, inter, bandNext, x1 == 0, ctxNext);
                nextBits1 = tc[nextBase + tokenNext];
                if (x1 != 0) {
                    nextEobBits1 = tc[nextBase + Vp9EncTables.EOB_TOKEN];
                }
            }
            var rdCost0 = rdCost(mult, rate0 + nextBits0, distortion0);
            var rdCost1 = rdCost(mult, rate1 + nextBits1, distortion1);
            var betterX1 = rdCost1 < rdCost0;
            var eobCost0 = rdCost(mult, accuRate + rate0 + nextEobBits0, accuError + distortion0 - distortionForZero);
            var eobCost1 = eobCost0;
            var eobBetterX1 = false;
            if (x1 != 0) {
                eobCost1 = rdCost(mult, accuRate + rate1 + nextEobBits1, accuError + distortion1 - distortionForZero);
                eobBetterX1 = eobCost1 < eobCost0;
            }
            var dqc0 = dqcoeff[at];
            var dqc1 = 0;
            if ((betterX1 || eobBetterX1) && x1 != 0) {
                dqc1 = shiftMagnitude(x1 * dqv, shift);
            }
            if (betterX1) {
                qcoeff[at] = x1;
                dqcoeff[at] = dqc1;
                accuRate += rate1;
                accuError += distortion1 - distortionForZero;
                cache[rc] = Vp9Constants.ENERGY_CLASS[t1];
            } else {
                accuRate += rate0;
                accuError += distortion0 - distortionForZero;
                cache[rc] = Vp9Constants.ENERGY_CLASS[t0];
            }
            xPrev = qcoeff[at];
            var useX1 = x1 != 0 && eobBetterX1;
            var bestEobCostCur = useX1 ? eobCost1 : eobCost0;
            if (bestEobCostCur < bestBlockRd) {
                bestBlockRd = bestEobCostCur;
                finalEob = i + 1;
                if (useX1) {
                    beforeBestEobQc = x1;
                    beforeBestEobDqc = dqc1;
                } else {
                    beforeBestEobQc = x;
                    beforeBestEobDqc = dqc0;
                }
            }
        }
        if (finalEob > 0) {
            var rc = scan[finalEob - 1];
            qcoeff[off + rc] = beforeBestEobQc;
            dqcoeff[off + rc] = beforeBestEobDqc;
        }
        for (var i = finalEob; i < eob; i++) {
            var rc = scan[i];
            qcoeff[off + rc] = 0;
            dqcoeff[off + rc] = 0;
        }
        return finalEob;
    }
}
