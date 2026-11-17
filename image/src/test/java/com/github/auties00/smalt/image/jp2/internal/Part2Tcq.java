package com.github.auties00.smalt.image.jp2.internal;

/**
 * Trellis coded quantization written from ISO/IEC 15444-2 D.3, independently of the decoder: the scalar quantizers of
 * Figure D.1, the parent tables of Table D.1 and the Viterbi search of Table D.2, over a code-block in the scan order
 * of ISO/IEC 15444-1 D.1. Besides the indices it records the reconstruction each index stands for, which the full
 * dequantization of D.4.1 must give back.
 */
final class Part2Tcq {
    /** The parent states of each trellis state, two per state (Table D.1). */
    private static final int[][] PARENTS = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 1}, {2, 3}, {4, 5}, {6, 7}};

    /** The scalar quantizer leading from each parent to each state, two per state (Table D.1). */
    private static final int[][] QUANTIZERS = {{0, 2}, {1, 3}, {2, 0}, {3, 1}, {2, 0}, {3, 1}, {0, 2}, {1, 3}};

    /** The reconstruction offset this test expects of the decoder, in steps, for indices of magnitude at most 2. */
    static final double OFFSET = 0.125;

    private Part2Tcq() {
    }

    /** The reconstruction level of index {@code m} of scalar quantizer {@code D_i}, in steps of equation D-1. */
    static long level(int quantizer, long m) {
        return switch (quantizer) {
            case 0 -> m > 0 ? 4 * m - 1 : 4 * m;
            case 1 -> m > 0 ? 4 * m : m == 0 ? 0 : 4 * m + 1;
            case 2 -> m >= 0 ? 4 * m + 1 : 4 * m + 2;
            default -> m > 0 ? 4 * m - 2 : 4 * m - 1;
        };
    }

    /** The union quantizer index of index {@code m} of scalar quantizer {@code D_i} (Figure D.1). */
    static long union(int quantizer, long m) {
        return switch (quantizer) {
            case 0, 1 -> 2 * m;
            case 2 -> 2 * m + 1;
            default -> 2 * m - 1;
        };
    }

    /**
     * Quantizes a code-block, row-major, with steps of equation D-1, writing the transmitted indices (after the
     * {@code A1} negation) and the reconstruction the full dequantization gives each of them, in steps.
     */
    static void quantize(double[] values, int width, int height, long[] indices, double[] reconstructions) {
        var count = width * height;
        var order = new int[count];
        var k = 0;
        for (var stripe = 0; stripe < height; stripe += 4) {
            for (var i = 0; i < width; i++) {
                for (var j = stripe; j < Math.min(stripe + 4, height); j++) {
                    order[k++] = j * width + i;
                }
            }
        }
        var distortion = new double[8];
        java.util.Arrays.fill(distortion, Double.POSITIVE_INFINITY);
        distortion[0] = 0;
        var back = new int[count][8];
        var chosenQuantizer = new int[count][8];
        var chosenIndex = new long[count][8];
        var best = new long[4];
        var bestError = new double[4];
        for (k = 0; k < count; k++) {
            var x = values[order[k]];
            for (var q = 0; q < 4; q++) {
                var center = (long) Math.floor(x / 4);
                bestError[q] = Double.POSITIVE_INFINITY;
                for (var m = center - 2; m <= center + 2; m++) {
                    var error = x - level(q, m);
                    if (error * error < bestError[q]) {
                        bestError[q] = error * error;
                        best[q] = m;
                    }
                }
            }
            var next = new double[8];
            for (var s = 0; s < 8; s++) {
                var first = distortion[PARENTS[s][0]] + bestError[QUANTIZERS[s][0]];
                var second = distortion[PARENTS[s][1]] + bestError[QUANTIZERS[s][1]];
                var pick = first <= second ? 0 : 1;
                next[s] = pick == 0 ? first : second;
                back[k][s] = PARENTS[s][pick];
                chosenQuantizer[k][s] = QUANTIZERS[s][pick];
                chosenIndex[k][s] = best[QUANTIZERS[s][pick]];
            }
            distortion = next;
        }
        var state = 0;
        for (var s = 1; s < 8; s++) {
            if (distortion[s] < distortion[state]) {
                state = s;
            }
        }
        for (k = count - 1; k >= 0; k--) {
            var quantizer = chosenQuantizer[k][state];
            var m = chosenIndex[k][state];
            var index = union(quantizer, m);
            if ((quantizer == 1 || quantizer == 3) && (index == 1 || index == -1)) {
                index = -index;
            }
            var level = (double) level(quantizer, m);
            if (level != 0 && Math.abs(index) <= 2) {
                level -= Math.signum(level) * OFFSET;
            }
            indices[order[k]] = index;
            reconstructions[order[k]] = level;
            state = back[k][state];
        }
    }
}
