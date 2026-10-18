package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;

/**
 * Codes the alpha macroblocks of VC-3 coding units with lossless alpha ({@code LLA} set, SMPTE
 * ST 2019-1 clauses 7.3.1.2 and 8.3): differential run-length codewords, or the 256 samples packed
 * when the codewords would exceed {@code 256 n} bits for {@code n}-bit alpha.
 *
 * <p>The samples are taken in the alpha zig-zag of Figure 16, the rows of the macroblock
 * alternately left to right and right to left. A codeword repeats one difference over a run of
 * samples, each predicted from the previous sample ({@code P = 0}) or extrapolated from the
 * previous two ({@code P = 1}); see {@link Vc3RowDecoder} for the codeword layout.
 *
 * <p>Instances hold the scratch buffer of one encoding thread and are not thread-safe.
 *
 * @implNote This implementation chooses each codeword greedily: of the two predictors it takes
 *           the one whose run costs the fewest bits per sample, the difference coded with the
 *           shorter of the plain and Golomb-Rice codes.
 */
public final class Vc3LosslessAlpha {
    /**
     * The samples of a macroblock in alpha zig-zag order.
     */
    private final int[] values = new int[256];

    /**
     * Creates a coder.
     */
    public Vc3LosslessAlpha() {
    }

    /**
     * Codes one alpha macroblock, or measures it.
     *
     * @param plane     the alpha plane, full range at {@code depth} bits
     * @param stride    the plane's row stride
     * @param x         the first column of the macroblock
     * @param top       the coding unit row of the macroblock's first row
     * @param rowStep   the distance in plane rows between coding unit rows: 2 for a field
     * @param rowOffset the plane row of the coding unit's first row
     * @param depth     the alpha bit depth {@code n}
     * @param writer    the writer, or {@code null} to measure only
     * @return the number of bits, including the 4-bit alpha macroblock header
     */
    public int code(short[] plane, int stride, int x, int top, int rowStep, int rowOffset, int depth,
                    IntraBitWriter writer) {
        var mask = (1 << depth) - 1;
        for (var count = 0; count < 256; count++) {
            var line = count >>> 4;
            var column = (line & 1) == 0 ? count & 15 : 15 - (count & 15);
            values[count] = plane[((top + line) * rowStep + rowOffset) * stride + x + column] & mask;
        }
        var bits = runs(depth, null);
        var limit = 256 * depth;
        // RL = 1b marks run-length codewords and 0b packed samples, as the prose of clause 7.3.1.2
        // defines them; Figure 50 tests the bit the other way round (see Vc3RowDecoder).
        if (bits > limit) {
            if (writer != null) {
                writer.write(0, 4);
                for (var count = 0; count < 256; count++) {
                    writer.write(values[count], depth);
                }
            }
            return 4 + limit;
        }
        if (writer != null) {
            writer.write(1, 4);
            runs(depth, writer);
        }
        return 4 + bits;
    }

    /**
     * Codes the samples of {@link #values} as run-length codewords, or measures them.
     *
     * @param depth  the alpha bit depth
     * @param writer the writer, or {@code null} to measure only
     * @return the number of bits of the codewords
     */
    private int runs(int depth, IntraBitWriter writer) {
        var mask = (1 << depth) - 1;
        var riceOrder = depth / 2 - 2;
        var last = mask >> 1;
        var beforeLast = last;
        var count = 0;
        var bits = 0;
        while (count < 256) {
            var bestCost = Integer.MAX_VALUE;
            var bestRun = 0;
            var bestPredictor = 0;
            var bestCodeword = 0;
            for (var predictor = 0; predictor < 2; predictor++) {
                var prediction = predictor == 0 ? last : 2 * last - beforeLast;
                var difference = (values[count] - prediction) & mask;
                var run = 0;
                var previous = last;
                var beforePrevious = beforeLast;
                while (count + run < 256) {
                    var value = ((predictor == 0 ? previous : 2 * previous - beforePrevious) + difference) & mask;
                    if (value != values[count + run]) {
                        break;
                    }
                    beforePrevious = previous;
                    previous = value;
                    run++;
                }
                var signed = difference > mask >> 1 ? difference - mask - 1 : difference;
                var codeword = signed >= 0 ? 2 * signed : -2 * signed - 1;
                var cost = 2 + gammaLength(run) + Math.min(depth, (codeword >>> riceOrder) + 1 + riceOrder);
                if ((long) cost * bestRun < (long) bestCost * run) {
                    bestCost = cost;
                    bestRun = run;
                    bestPredictor = predictor;
                    bestCodeword = codeword;
                }
            }
            bits += bestCost;
            if (writer != null) {
                writer.write(bestPredictor, 1);
                var zeros = 31 - Integer.numberOfLeadingZeros(bestRun);
                writer.write(1, zeros + 1);
                var remainder = bestRun - (1 << zeros);
                // The remainder goes most significant bit first, as Figures 36 and 28 lay it out; the
                // informative note to Figure 36 reads the other way (see Vc3RowDecoder).
                writer.write(remainder, zeros);
                var riceLength = (bestCodeword >>> riceOrder) + 1 + riceOrder;
                if (riceLength < depth) {
                    writer.write(1, 1);
                    for (var q = bestCodeword >>> riceOrder; q > 0; q--) {
                        writer.write(1, 1);
                    }
                    writer.write(0, 1);
                    writer.write(bestCodeword, riceOrder);
                } else {
                    writer.write(0, 1);
                    writer.write(bestCodeword, depth);
                }
            }
            for (var k = 0; k < bestRun; k++) {
                beforeLast = last;
                last = values[count + k];
            }
            count += bestRun;
        }
        return bits;
    }

    /**
     * Returns the length of the Elias gamma code of a run.
     *
     * @param run the run length, from 1 to 256
     * @return {@code 2 floor(log2 run) + 1}
     */
    private static int gammaLength(int run) {
        return 2 * (31 - Integer.numberOfLeadingZeros(run)) + 1;
    }
}
