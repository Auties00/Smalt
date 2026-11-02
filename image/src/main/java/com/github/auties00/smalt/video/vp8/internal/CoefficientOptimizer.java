/* Ports libvpx 1.15.1 (vp8/encoder/encodemb.c: optimize_b, check_reset_2nd_coeffs, optimize_mb). Copyright (c)
   2010 The WebM project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Optimizes the quantized levels of a VP8 block by their rate and distortion, libvpx's trellis
 * quantization: each nonzero level may be kept or lowered by one in magnitude, and a Viterbi
 * search over the block finds the choice of the smallest rate-distortion score, the rate counted
 * from the token costs of the frame, the end of block moving back when the trailing levels
 * vanish.
 *
 * <p>An instance holds the trellis of one block and belongs to one coding thread.
 */
final class CoefficientOptimizer {
    /**
     * The block type of luma blocks whose DC the Y2 block codes.
     */
    static final int TYPE_Y_NO_DC = 0;

    /**
     * The block type of the Y2 block.
     */
    static final int TYPE_Y2 = 1;

    /**
     * The block type of chroma blocks.
     */
    static final int TYPE_UV = 2;

    /**
     * The block type of luma blocks that code their DC.
     */
    static final int TYPE_Y_WITH_DC = 3;

    /**
     * The weight of the distortion of each block type, libvpx's {@code plane_rd_mult}.
     */
    private static final int[] PLANE_RD_MULT = {4, 16, 2, 4};

    /**
     * The sum of absolute dequantized Y2 values below which the Y2 block reconstructs to zero,
     * libvpx's {@code check_reset_2nd_coeffs} bound.
     */
    private static final int Y2_RESET_SUM = 35;

    /**
     * The zero token.
     */
    private static final int ZERO_TOKEN = 0;

    /**
     * The rate of each trellis node, {@code [position 17][choice 2]}.
     */
    private final int[] rate = new int[34];

    /**
     * The distortion of each trellis node.
     */
    private final int[] error = new int[34];

    /**
     * The position of the node that follows each node.
     */
    private final int[] next = new int[34];

    /**
     * The token of each node.
     */
    private final int[] token = new int[34];

    /**
     * The level of each node.
     */
    private final int[] qc = new int[34];

    /**
     * The costs of the frame's tokens.
     */
    private final RdCosts costs;

    /**
     * Creates an optimizer that reads token costs from a table.
     *
     * @param costs the table, filled for each frame before use
     */
    CoefficientOptimizer(RdCosts costs) {
        this.costs = costs;
    }

    /**
     * Returns the rate-distortion score of a rate and a distortion, libvpx's {@code RDCOST} in
     * 32-bit arithmetic.
     */
    private static int rdCost(int rdMult, int rdDiv, int rate, int error) {
        return ((128 + rate * rdMult) >> 8) + rdDiv * error;
    }

    /**
     * Returns the fraction that breaks a tie of {@link #rdCost}, libvpx's {@code RDTRUNC}.
     */
    private static int rdTrunc(int rdMult, int rate) {
        return (128 + rate * rdMult) & 0xFF;
    }

    /**
     * Optimizes the levels of one block, libvpx's {@code optimize_b}.
     *
     * <p>The levels and the dequantized coefficients are updated in place.
     *
     * @param levels the levels in zigzag order
     * @param levelsOff the index of the level of zigzag position 0
     * @param levelsStride the distance between consecutive zigzag positions
     * @param dq the dequantized coefficients
     * @param dqOff the index of raster position 0 of {@code dq}
     * @param dqStride the distance between consecutive raster positions of {@code dq}
     * @param orig the coefficients before quantization
     * @param origOff the index of raster position 0 of {@code orig}
     * @param origStride the distance between consecutive raster positions of {@code orig}
     * @param steps the quantizer steps in raster order
     * @param eob one past the last nonzero position, at most 16
     * @param type the block type
     * @param ctx the context from the neighboring blocks, from 0 to 2
     * @param rdMult the rate multiplier of the macroblock
     * @param rdDiv the distortion multiplier of the macroblock
     * @param intra whether the macroblock is intra coded, which lowers the rate multiplier
     * @return one past the last nonzero position after the optimization, at least the first
     * coded position
     */
    int optimize(short[] levels, int levelsOff, int levelsStride, int[] dq, int dqOff, int dqStride,
                 int[] orig, int origOff, int origStride, int[] steps, int eob, int type, int ctx,
                 int rdMult, int rdDiv, boolean intra) {
        var i0 = type == TYPE_Y_NO_DC ? 1 : 0;
        if (eob < i0) {
            eob = i0;
        }
        var mult = rdMult * PLANE_RD_MULT[type];
        if (intra) {
            mult = (mult * 9) >> 4;
        }
        var e0 = eob * 2;
        rate[e0] = rate[e0 + 1] = 0;
        error[e0] = error[e0 + 1] = 0;
        next[e0] = next[e0 + 1] = 16;
        token[e0] = token[e0 + 1] = RdCosts.EOB;
        qc[e0] = qc[e0 + 1] = 0;
        var bestMask0 = 0;
        var bestMask1 = 0;
        var nx = eob;
        var i = eob;
        while (i-- > i0) {
            var rc = RtDsp.ZIGZAG[i];
            var x = (int) levels[levelsOff + i * levelsStride];
            var n0 = nx * 2;
            var n1 = n0 + 1;
            if (x != 0) {
                var error0 = error[n0];
                var error1 = error[n1];
                var rate0 = rate[n0];
                var rate1 = rate[n1];
                var t0 = RdCosts.valueToken(x);
                if (nx < 16) {
                    var band = Vp8Tables.BANDS[i + 1];
                    var pt = RdCosts.PREV_CLASS[t0];
                    rate0 += costs.tokenCost(type, band, pt, token[n0]);
                    rate1 += costs.tokenCost(type, band, pt, token[n1]);
                }
                var best = better(mult, rdDiv, rate0, error0, rate1, error1);
                var baseBits = RdCosts.valueCost(x);
                var coefficient = orig[origOff + rc * origStride];
                var dx = dq[dqOff + rc * dqStride] - coefficient;
                var d2 = dx * dx;
                var s = i * 2;
                rate[s] = baseBits + (best ? rate1 : rate0);
                error[s] = d2 + (best ? error1 : error0);
                next[s] = nx;
                token[s] = t0;
                qc[s] = x;
                bestMask0 |= (best ? 1 : 0) << i;
                rate0 = rate[n0];
                rate1 = rate[n1];
                var step = steps[rc];
                var magnitude = Math.abs(x) * step;
                var shortcut = magnitude > Math.abs(coefficient) && magnitude < Math.abs(coefficient) + step;
                var sz = 0;
                if (shortcut) {
                    sz = x < 0 ? -1 : 0;
                    x -= 2 * sz + 1;
                }
                int t1;
                if (x == 0) {
                    t0 = token[n0] == RdCosts.EOB ? RdCosts.EOB : ZERO_TOKEN;
                    t1 = token[n1] == RdCosts.EOB ? RdCosts.EOB : ZERO_TOKEN;
                } else {
                    t0 = t1 = RdCosts.valueToken(x);
                }
                if (nx < 16) {
                    var band = Vp8Tables.BANDS[i + 1];
                    if (t0 != RdCosts.EOB) {
                        rate0 += costs.tokenCost(type, band, RdCosts.PREV_CLASS[t0], token[n0]);
                    }
                    if (t1 != RdCosts.EOB) {
                        rate1 += costs.tokenCost(type, band, RdCosts.PREV_CLASS[t1], token[n1]);
                    }
                }
                best = better(mult, rdDiv, rate0, error0, rate1, error1);
                baseBits = RdCosts.valueCost(x);
                if (shortcut) {
                    dx -= (step + sz) ^ sz;
                    d2 = dx * dx;
                }
                rate[s + 1] = baseBits + (best ? rate1 : rate0);
                error[s + 1] = d2 + (best ? error1 : error0);
                next[s + 1] = nx;
                token[s + 1] = best ? t1 : t0;
                qc[s + 1] = x;
                bestMask1 |= (best ? 1 : 0) << i;
                nx = i;
            } else {
                var band = Vp8Tables.BANDS[i + 1];
                if (token[n0] != RdCosts.EOB) {
                    rate[n0] += costs.tokenCost(type, band, 0, token[n0]);
                    token[n0] = ZERO_TOKEN;
                }
                if (token[n1] != RdCosts.EOB) {
                    rate[n1] += costs.tokenCost(type, band, 0, token[n1]);
                    token[n1] = ZERO_TOKEN;
                }
            }
        }
        var band = Vp8Tables.BANDS[Math.max(i + 1, 0)];
        var n0 = nx * 2;
        var rate0 = rate[n0] + costs.tokenCost(type, band, ctx, token[n0]);
        var rate1 = rate[n0 + 1] + costs.tokenCost(type, band, ctx, token[n0 + 1]);
        var choice = better(mult, rdDiv, rate0, error[n0], rate1, error[n0 + 1]) ? 1 : 0;
        var finalEob = i0 - 1;
        for (i = nx; i < eob; ) {
            var s = i * 2 + choice;
            var x = qc[s];
            if (x != 0) {
                finalEob = i;
            }
            var rc = RtDsp.ZIGZAG[i];
            levels[levelsOff + i * levelsStride] = (short) x;
            dq[dqOff + rc * dqStride] = x * steps[rc];
            var following = next[s];
            choice = (((choice == 0 ? bestMask0 : bestMask1) >> i) & 1);
            i = following;
        }
        return finalEob + 1;
    }

    /**
     * Returns whether the second of two candidates has the smaller score, ties broken by the
     * fraction the score truncates.
     */
    private static boolean better(int mult, int rdDiv, int rate0, int error0, int rate1, int error1) {
        var cost0 = rdCost(mult, rdDiv, rate0, error0);
        var cost1 = rdCost(mult, rdDiv, rate1, error1);
        if (cost0 == cost1) {
            cost0 = rdTrunc(mult, rate0);
            cost1 = rdTrunc(mult, rate1);
        }
        return cost1 < cost0;
    }

    /**
     * Zeroes a Y2 block whose dequantized values are too small to change the reconstruction,
     * libvpx's {@code check_reset_2nd_coeffs}; the inverse Walsh-Hadamard transform and the DC
     * inverse transform then leave every luma block's DC at zero.
     *
     * @param levels the levels in zigzag order
     * @param levelsOff the index of the level of zigzag position 0
     * @param levelsStride the distance between consecutive zigzag positions
     * @param dq the dequantized coefficients in raster order
     * @param dqOff the index of raster position 0
     * @param steps the Y2 quantizer steps in raster order
     * @param eob the end of block
     * @return the end of block, {@code 0} when the block was zeroed
     */
    static int resetY2(short[] levels, int levelsOff, int levelsStride, short[] dq, int dqOff, int[] steps,
                       int eob) {
        if (steps[0] >= Y2_RESET_SUM && steps[1] >= Y2_RESET_SUM) {
            return eob;
        }
        var sum = 0;
        for (var n = 0; n < eob; n++) {
            sum += Math.abs(dq[dqOff + RtDsp.ZIGZAG[n]]);
            if (sum >= Y2_RESET_SUM) {
                return eob;
            }
        }
        for (var n = 0; n < eob; n++) {
            levels[levelsOff + n * levelsStride] = 0;
            dq[dqOff + RtDsp.ZIGZAG[n]] = 0;
        }
        return 0;
    }
}
