package com.github.auties00.smalt.video.vp9.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks libvpx's greedy VP9 trellis quantization on random blocks of every transform size: each
 * level is kept or lowered by one in magnitude, the dequantized values follow the levels, the end
 * of block follows the last level, and the rate-distortion score, the rate counted from the token
 * costs as {@code cost_coeffs} counts it, drops below the input's for many blocks and never
 * rises above it by more than the greedy search's slack.
 */
@DisplayName("VP9 coefficient optimization")
class Vp9CoefficientOptimizerTest {
    /** The outcome of a batch of random blocks. */
    record Stats(int trials, int improved, int worse, double inputScore, double outputScore) {
    }

    /** Returns the rate of a block's levels from the token costs ({@code cost_coeffs}). */
    static long rate(Vp9RdCosts costs, int[] qc, int eob, int txSize, int txType, int plane, boolean inter, int ctx) {
        var tc = costs.token;
        var bands = txSize == Vp9Constants.TX_4X4 ? Vp9ProbTables.BAND_4X4 : Vp9ProbTables.BAND_8X8_PLUS;
        if (eob == 0) {
            return tc[Vp9RdCosts.tokenBase(txSize, plane, inter, 0, false, ctx) + Vp9EncTables.EOB_TOKEN];
        }
        var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
        var nb = Vp9RtCoder.NEIGHBORS[txSize * 4 + txType];
        var cache = new byte[1024];
        long cost = 0;
        var prev = -1;
        var pt = ctx;
        var i = 0;
        for (; i < eob; i++) {
            var rc = scan[i];
            var vc = costs.valueCost(qc[rc]);
            var tok = vc & 15;
            if (i > 0) {
                pt = (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
            }
            cost += (vc >> 4) + tc[Vp9RdCosts.tokenBase(txSize, plane, inter, bands[i], prev == Vp9EncTables.ZERO_TOKEN,
                    pt) + tok];
            cache[rc] = Vp9Constants.ENERGY_CLASS[tok];
            prev = tok;
        }
        if (i < (16 << (txSize << 1))) {
            pt = (1 + cache[nb[2 * i]] + cache[nb[2 * i + 1]]) >> 1;
            cost += tc[Vp9RdCosts.tokenBase(txSize, plane, inter, bands[i], false, pt) + Vp9EncTables.EOB_TOKEN];
        }
        return cost;
    }

    /** Returns the unrounded score of a block. */
    static double score(Vp9RdCosts costs, int[] coeff, int[] qc, int[] dq, int txSize, int txType, int plane,
                        boolean inter, int ctx, long mult) {
        var n = 16 << (txSize << 1);
        var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
        var eob = 0;
        for (var i = 0; i < n; i++) {
            if (qc[scan[i]] != 0) {
                eob = i + 1;
            }
        }
        var shift = txSize == Vp9Constants.TX_32X32 ? 1 : 0;
        double error = 0;
        for (var k = 0; k < n; k++) {
            var d = (double) (dq[k] - coeff[k]) * (1 << shift);
            error += d * d;
        }
        return rate(costs, qc, eob, txSize, txType, plane, inter, ctx) * (double) mult / 512 + error * 128;
    }

    static Stats measure(int txSize, int plane, boolean inter, int seed, int trials) {
        var costs = new Vp9RdCosts(8);
        var fc = new Vp9FrameContext();
        fc.setDefaults();
        costs.build(fc.probs, !inter);
        var optimizer = new Vp9CoefficientOptimizer(costs, costs);
        var random = new Random(seed);
        var n = 16 << (txSize << 1);
        var size = 4 << txSize;
        var shift = txSize == Vp9Constants.TX_32X32 ? 1 : 0;
        var improved = 0;
        var worse = 0;
        double inputTotal = 0;
        double outputTotal = 0;
        for (var trial = 0; trial < trials; trial++) {
            var txType = !inter && plane == 0 && txSize != Vp9Constants.TX_32X32 ? random.nextInt(4)
                    : Vp9Constants.DCT_DCT;
            var dc = 8 + random.nextInt(120);
            var ac = dc + random.nextInt(dc / 2 + 1);
            var coeff = new int[n];
            var qc = new int[n];
            var dq = new int[n];
            var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
            var eob = 0;
            for (var i = 0; i < n; i++) {
                var rc = scan[i];
                var step = rc == 0 ? dc : ac;
                var radius = Math.max(1, (int) (6 * step * StrictMath.exp(-i / (double) (size * 1.5))));
                if (random.nextInt(4) == 0) {
                    continue;
                }
                var c = random.nextInt(2 * radius + 1) - radius;
                coeff[rc] = c;
                var level = (Math.abs(c) * (1 << shift) + step / 2) / step;
                qc[rc] = c < 0 ? -level : level;
                dq[rc] = c < 0 ? -((level * step) >> shift) : (level * step) >> shift;
                if (level != 0) {
                    eob = i + 1;
                }
            }
            var ctx = random.nextInt(3);
            var rdMult = (int) (88L * dc * dc / 24 * (50 + random.nextInt(101)) / 100);
            var mult = ((long) rdMult * (inter ? (plane == 0 ? 8 : 5) : (plane == 0 ? 10 : 6))) >> 1;
            var inputQc = qc.clone();
            var inputScore = score(costs, coeff, qc, dq, txSize, txType, plane, inter, ctx, mult);
            var out = optimizer.optimize(coeff, qc, dq, 0, eob, txSize, txType, plane, inter, ctx, dc, ac, rdMult, 8);
            var last = 0;
            for (var i = 0; i < n; i++) {
                var rc = scan[i];
                var step = rc == 0 ? dc : ac;
                var delta = Math.abs(inputQc[rc]) - Math.abs(qc[rc]);
                assertTrue(delta == 0 || delta == 1 || (qc[rc] == 0 && i >= out),
                        "position " + i + " of trial " + trial + ": " + inputQc[rc] + " -> " + qc[rc]);
                assertTrue(qc[rc] == 0 || Integer.signum(qc[rc]) == Integer.signum(inputQc[rc]), "sign");
                var expected = qc[rc] < 0 ? -((-qc[rc] * step) >> shift) : (qc[rc] * step) >> shift;
                assertEquals(expected, dq[rc], "dequantized " + i);
                if (qc[rc] != 0) {
                    last = i + 1;
                }
            }
            assertEquals(last, out, "end of block of trial " + trial);
            var outputScore = score(costs, coeff, qc, dq, txSize, txType, plane, inter, ctx, mult);
            inputTotal += inputScore;
            outputTotal += outputScore;
            if (outputScore < inputScore * 0.999) {
                improved++;
            } else if (outputScore > inputScore * 1.001) {
                worse++;
            }
        }
        return new Stats(trials, improved, worse, inputTotal, outputTotal);
    }

    @ParameterizedTest(name = "transform size {0}, plane {1}, inter {2}")
    @CsvSource({"0, 0, false", "0, 1, true", "1, 0, true", "1, 2, false", "2, 0, false", "2, 1, true",
            "3, 0, true", "3, 1, false"})
    @DisplayName("lowers the scores of random blocks, keeping each level or lowering it by one")
    void randomBlocks(int txSize, int plane, boolean inter) {
        var stats = measure(txSize, plane, inter, 17 * txSize + plane, txSize == 3 ? 300 : 1500);
        assertTrue(stats.improved() > stats.trials() / 2, "improved " + stats);
        assertTrue(stats.outputScore() < stats.inputScore() * 0.99, "total " + stats);
        assertTrue(stats.worse() <= stats.trials() / 25, "worse " + stats);
    }
}
