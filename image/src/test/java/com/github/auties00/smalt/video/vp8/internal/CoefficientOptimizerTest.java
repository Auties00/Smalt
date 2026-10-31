package com.github.auties00.smalt.video.vp8.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the VP8 trellis quantization against an exhaustive search: every block it leaves is one
 * of the candidates (each nonzero level kept, or lowered by one when the step overshoots the
 * coefficient by less than a step), never scores worse than its input, and, when no level can
 * drop to zero, scores within the rounding of libvpx's integer score of the best candidate.
 * Levels that drop to zero move the end of block, which libvpx's two-state trellis only
 * approximates, so those blocks are held to the input bound.
 */
@DisplayName("VP8 coefficient optimization")
class CoefficientOptimizerTest {
    private static final int[] PLANE_RD_MULT = {4, 16, 2, 4};

    private static RdCosts costs() {
        var costs = new RdCosts();
        costs.build(Vp8Tables.COEF_PROBS_DEFAULT);
        return costs;
    }

    /** Returns the unrounded score of a block's levels. */
    private static double score(RdCosts costs, short[] levels, int[] orig, int[] steps, int type, int ctx, int mult,
                                int div) {
        var first = type == CoefficientOptimizer.TYPE_Y_NO_DC ? 1 : 0;
        var eob = first;
        for (var n = first; n < 16; n++) {
            if (levels[n] != 0) {
                eob = n + 1;
            }
        }
        var rate = costs.blockCost(levels, 0, 1, first, eob, type, ctx);
        double error = 0;
        for (var n = first; n < 16; n++) {
            var rc = RtDsp.ZIGZAG[n];
            var d = orig[rc] - levels[n] * steps[rc];
            error += (double) d * d;
        }
        return rate * (double) mult / 256 + div * error;
    }

    @ParameterizedTest(name = "block type {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("finds the best candidate of an exhaustive search when no level drops to zero")
    void exhaustive(int type) {
        run(type, true);
    }

    @ParameterizedTest(name = "block type {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("never scores worse than its input and lowers the score of many blocks")
    void general(int type) {
        run(type, false);
    }

    private static void run(int type, boolean noZeroing) {
        var costs = costs();
        var optimizer = new CoefficientOptimizer(costs);
        var random = new Random(31 + type);
        var first = type == CoefficientOptimizer.TYPE_Y_NO_DC ? 1 : 0;
        var improved = 0;
        var trials = 3000;
        for (var trial = 0; trial < trials; trial++) {
            var dcStep = 4 + random.nextInt(60);
            var acStep = 4 + random.nextInt(80);
            var steps = new int[16];
            for (var k = 0; k < 16; k++) {
                steps[k] = k == 0 ? dcStep : acStep;
            }
            var orig = new int[16];
            var levels = new short[16];
            var nonzero = 0;
            for (var n = first; n < 16 && nonzero < 12; n++) {
                var rc = RtDsp.ZIGZAG[n];
                var decay = Math.max(1, 6 - n / 3);
                if (random.nextInt(3) == 0) {
                    continue;
                }
                var magnitude = noZeroing ? steps[rc] + random.nextInt(decay * steps[rc] + 1)
                        : random.nextInt(decay * steps[rc] + 1);
                orig[rc] = random.nextBoolean() ? magnitude : -magnitude;
                var level = (magnitude + steps[rc] / 2) / steps[rc];
                levels[n] = (short) (orig[rc] < 0 ? -level : level);
                if (level != 0) {
                    nonzero++;
                }
            }
            var eob = first;
            for (var n = first; n < 16; n++) {
                if (levels[n] != 0) {
                    eob = n + 1;
                }
            }
            var ctx = random.nextInt(3);
            var rdMult = 20 + random.nextInt(400);
            var rdDiv = random.nextBoolean() ? 1 : 100;
            var intra = random.nextBoolean();
            var mult = rdMult * PLANE_RD_MULT[type];
            if (intra) {
                mult = (mult * 9) >> 4;
            }
            var input = levels.clone();
            var inputScore = score(costs, input, orig, steps, type, ctx, mult, rdDiv);
            var candidates = new int[16];
            var count = 0;
            for (var n = first; n < eob; n++) {
                var rc = RtDsp.ZIGZAG[n];
                var magnitude = Math.abs(input[n]) * steps[rc];
                if (input[n] != 0 && magnitude > Math.abs(orig[rc]) && magnitude < Math.abs(orig[rc]) + steps[rc]) {
                    candidates[count++] = n;
                }
            }
            var best = Double.MAX_VALUE;
            var trialLevels = new short[16];
            for (var mask = 0; mask < (1 << count); mask++) {
                System.arraycopy(input, 0, trialLevels, 0, 16);
                for (var k = 0; k < count; k++) {
                    if ((mask >> k & 1) != 0) {
                        var n = candidates[k];
                        trialLevels[n] = (short) (trialLevels[n] - Integer.signum(trialLevels[n]));
                    }
                }
                best = Math.min(best, score(costs, trialLevels, orig, steps, type, ctx, mult, rdDiv));
            }
            var dq = new int[16];
            for (var n = 0; n < 16; n++) {
                dq[RtDsp.ZIGZAG[n]] = input[n] * steps[RtDsp.ZIGZAG[n]];
            }
            var out = input.clone();
            var newEob = optimizer.optimize(out, 0, 1, dq, 0, 1, orig, 0, 1, steps, eob, type, ctx, rdMult, rdDiv,
                    intra);
            var last = first;
            for (var n = 0; n < 16; n++) {
                var rc = RtDsp.ZIGZAG[n];
                var delta = Math.abs(input[n]) - Math.abs(out[n]);
                var candidate = false;
                for (var k = 0; k < count; k++) {
                    candidate |= candidates[k] == n;
                }
                assertTrue(delta == 0 || (delta == 1 && candidate), "level " + n + " of trial " + trial);
                assertTrue(out[n] == 0 || Integer.signum(out[n]) == Integer.signum(input[n]), "sign");
                assertEquals(out[n] * steps[rc], dq[rc], "dequantized " + n);
                if (n >= first && out[n] != 0) {
                    last = n + 1;
                }
            }
            assertEquals(last, newEob, "end of block of trial " + trial);
            var outScore = score(costs, out, orig, steps, type, ctx, mult, rdDiv);
            var slack = count + 2;
            if (noZeroing) {
                assertTrue(outScore <= best + slack, "trial " + trial + ": " + outScore + " > best " + best);
            }
            assertTrue(outScore <= inputScore + slack, "trial " + trial + " worse than its input");
            if (outScore < inputScore - slack) {
                improved++;
            }
        }
        assertTrue(improved > trials / 20, "the optimization lowers the score of " + improved + " blocks");
    }

    @Test
    @DisplayName("a Y2 block too small to reconstruct is zeroed")
    void resetY2() {
        var steps = new int[16];
        java.util.Arrays.fill(steps, 10);
        var levels = new short[16];
        var dq = new short[16];
        levels[0] = 2;
        levels[1] = -1;
        dq[0] = 20;
        dq[1] = -10;
        assertEquals(0, CoefficientOptimizer.resetY2(levels, 0, 1, dq, 0, steps, 2));
        assertEquals(0, levels[0]);
        assertEquals(0, dq[0]);
        levels[0] = 3;
        levels[1] = 1;
        dq[0] = 30;
        dq[RtDsp.ZIGZAG[1]] = 10;
        assertEquals(2, CoefficientOptimizer.resetY2(levels, 0, 1, dq, 0, steps, 2), "a sum of 40 is kept");
        java.util.Arrays.fill(steps, 35);
        levels[0] = 1;
        dq[0] = 35;
        assertEquals(1, CoefficientOptimizer.resetY2(levels, 0, 1, dq, 0, steps, 1), "large steps are kept");
    }
}
