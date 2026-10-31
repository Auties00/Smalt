/* Ports Little CMS 2.17 (cmsgmt.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.List;

/**
 * Marks the source colors that a proofing profile cannot reproduce.
 *
 * <p>A lookup table with one output is sampled over the source device values: each node is converted to L*a*b*, sent
 * colorimetrically to the proofing profile's device values and back twice, and holds how far the round trips move it
 * beyond a threshold (1 delta E for a matrix-shaper proofing profile, 5 otherwise), or the ratio of the two moves when
 * both exceed it. A color whose interpolated value is at least one 16-bit code is out of gamut.
 *
 * <p>The table spans the larger of the source's and the proofing profile's channel counts, with 49 points per channel
 * for up to three channels, 23 for four and 7 for more; table inputs beyond the source's channels are zero.
 * Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's {@code _cmsCreateGamutCheckPipeline} and
 * {@code GamutSampler} ({@code cmsgmt.c}), with the {@code cmsFLAGS_HIGHRESPRECALC} grid. When the source has more
 * channels than the proofing profile, Little CMS reads only the leading ones; this implementation spans the table
 * over the source's channels instead, with the grid of the source color space, so that every source channel counts.
 */
final class GamutCheck {
    /**
     * The estimated cost of sampling one node, in the units of {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long NODE_WORK = 2048;

    /**
     * The interpolator over the table.
     */
    private final Interpolator interpolator;

    /**
     * The number of table inputs.
     */
    private final int inputs;

    /**
     * Constructs a gamut check.
     *
     * @param interpolator the interpolator over the sampled table
     */
    private GamutCheck(Interpolator interpolator) {
        this.interpolator = interpolator;
        this.inputs = interpolator.inputs();
    }

    /**
     * Builds the gamut check of a source and a proofing profile.
     *
     * @param source the source node of the proofing chain, with its intent and black point compensation
     * @param proof the proofing profile
     * @return the gamut check
     * @throws com.github.auties00.smalt.MediaException.Malformed if a profile lacks the tags its role needs
     * @throws com.github.auties00.smalt.MediaException.Unsupported if a profile cannot be linked to L*a*b*
     */
    static GamutCheck of(Linker.Node source, IccReader proof) {
        var lab = Linker.Node.lab(4, Linker.RELATIVE);
        var toLab = simplified(List.of(source, lab));
        var forward = simplified(List.of(lab, Linker.Node.of(proof, Linker.RELATIVE, false)));
        var reverse = simplified(List.of(Linker.Node.of(proof, Linker.RELATIVE, false), lab));
        var threshold = ProfileLuts.isMatrixShaper(proof) ? 1.0 : 5.0;
        var sourceChannels = toLab.inputs();
        var dimensions = Math.max(sourceChannels, forward.outputs());
        var points = gridPoints(dimensions);
        var grid = new int[dimensions];
        Arrays.fill(grid, points);
        var nodes = 1;
        for (var i = 0; i < dimensions; i++) {
            nodes *= points;
        }
        var table = new int[nodes];
        var used = sourceChannels;
        var total = nodes;
        var bands = ParallelBands.bands(total, NODE_WORK * total);
        ParallelBands.run(bands, band -> {
            var sampler = new Sampler(toLab, forward, reverse, threshold, sourceChannels);
            var words = new int[dimensions];
            var end = ParallelBands.start(band + 1, bands, total);
            for (var node = ParallelBands.start(band, bands, total); node < end; node++) {
                var rest = node;
                var redundant = false;
                for (var t = dimensions - 1; t >= 0; --t) {
                    var colorant = rest % points;
                    rest /= points;
                    words[t] = ColorMath.saturateWord(colorant * 65535.0 / (points - 1));
                    if (t >= used && colorant != 0) {
                        redundant = true;
                    }
                }
                if (!redundant) {
                    table[node] = sampler.sample(words);
                }
            }
        });
        if (used < dimensions) {
            var block = 1;
            for (var i = used; i < dimensions; i++) {
                block *= points;
            }
            for (var node = 0; node < nodes; node++) {
                if (node % block != 0) {
                    table[node] = table[node - node % block];
                }
            }
        }
        return new GamutCheck(new Interpolator(grid, 1, table, false));
    }

    /**
     * Returns the grid points per channel for a color space of a channel count ({@code
     * _cmsReasonableGridpointsByColorspace} with {@code cmsFLAGS_HIGHRESPRECALC}).
     *
     * @param channels the channel count
     * @return the grid points
     */
    private static int gridPoints(int channels) {
        if (channels > 4) {
            return 7;
        }
        return channels == 4 ? 23 : 49;
    }

    /**
     * Links and simplifies a chain.
     *
     * @param chain the nodes
     * @return the pipeline
     */
    private static Pipeline simplified(List<Linker.Node> chain) {
        var pipeline = Linker.link(chain);
        pipeline.simplify();
        return pipeline;
    }

    /**
     * {@return the number of table inputs, the larger of the source's and the proofing profile's channel counts}
     */
    int inputs() {
        return inputs;
    }

    /**
     * {@return scratch space for {@link #isOutOfGamut(int[], int[], int[][])}: the interpolator's, or {@code null}}
     */
    int[][] scratch() {
        return interpolator.scratch();
    }

    /**
     * Returns whether a source color is out of the proofing gamut.
     *
     * @param words the 16-bit source values, {@link #inputs()} of them, zero past the source channels
     * @param result one-element scratch space
     * @param scratch the space of {@link #scratch()}
     * @return {@code true} if the color is out of gamut
     */
    boolean isOutOfGamut(int[] words, int[] result, int[][] scratch) {
        interpolator.evaluate(words, result, scratch);
        return result[0] >= 1;
    }

    /**
     * Samples the table nodes for one thread.
     */
    private static final class Sampler {
        /**
         * The evaluator from the source to L*a*b*.
         */
        private final Pipeline.Evaluator toLab;

        /**
         * The evaluator from L*a*b* to the proofing profile's device values.
         */
        private final Pipeline.Evaluator forward;

        /**
         * The evaluator from the proofing profile's device values to L*a*b*.
         */
        private final Pipeline.Evaluator reverse;

        /**
         * The delta E threshold.
         */
        private final double threshold;

        /**
         * The number of source channels.
         */
        private final int sourceChannels;

        /**
         * The pipeline input.
         */
        private final float[] in = new float[16];

        /**
         * The pipeline output.
         */
        private final float[] out = new float[16];

        /**
         * The number of device channels of the proofing profile.
         */
        private final int proofChannels;

        /**
         * The source L*a*b*.
         */
        private final double[] labIn1 = new double[3];

        /**
         * The L*a*b* after one round trip.
         */
        private final double[] labOut1 = new double[3];

        /**
         * The L*a*b* after two round trips.
         */
        private final double[] labOut2 = new double[3];

        /**
         * Creates a sampler.
         *
         * @param toLab the pipeline from the source to L*a*b*
         * @param forward the pipeline from L*a*b* to the proofing profile
         * @param reverse the pipeline from the proofing profile to L*a*b*
         * @param threshold the delta E threshold
         * @param sourceChannels the number of source channels
         */
        Sampler(Pipeline toLab, Pipeline forward, Pipeline reverse, double threshold, int sourceChannels) {
            this.toLab = toLab.evaluator();
            this.forward = forward.evaluator();
            this.reverse = reverse.evaluator();
            this.threshold = threshold;
            this.sourceChannels = sourceChannels;
            this.proofChannels = forward.outputs();
        }

        /**
         * Computes the value of one node ({@code GamutSampler}).
         *
         * @param words the 16-bit node coordinates
         * @return the 16-bit value
         */
        int sample(int[] words) {
            for (var c = 0; c < sourceChannels; c++) {
                in[c] = words[c] / 65535.0f;
            }
            toLab(toLab, labIn1);
            fromLab(labIn1);
            toLab(reverse, labOut1);
            fromLab(labOut1);
            toLab(reverse, labOut2);
            var dE1 = deltaE(labIn1, labOut1);
            var dE2 = deltaE(labOut1, labOut2);
            if (dE1 < threshold && dE2 < threshold) {
                return 0;
            }
            if (dE1 < threshold && dE2 > threshold) {
                return 0;
            }
            if (dE1 > threshold && dE2 < threshold) {
                return ColorMath.quickFloor((dE1 - threshold) + .5) & 0xFFFF;
            }
            var ratio = dE2 == 0.0 ? dE1 : dE1 / dE2;
            return ratio > threshold ? ColorMath.quickFloor((ratio - threshold) + .5) & 0xFFFF : 0;
        }

        /**
         * Evaluates a pipeline to L*a*b* on {@link #in}, as a Little CMS transform to {@code TYPE_Lab_DBL} writes it.
         *
         * @param pipeline the evaluator
         * @param lab receives L*, a* and b*
         */
        private void toLab(Pipeline.Evaluator pipeline, double[] lab) {
            pipeline.evaluate(in, out);
            lab[0] = out[0] * 100.0;
            lab[1] = out[1] * 255.0 - 128.0;
            lab[2] = out[2] * 255.0 - 128.0;
        }

        /**
         * Converts L*a*b* to the proofing profile's 16-bit device values, as a Little CMS transform from
         * {@code TYPE_Lab_DBL} to 16-bit samples does, and leaves them in {@link #in} as a transform from 16-bit
         * samples reads them.
         *
         * @param lab the L*a*b* value
         */
        private void fromLab(double[] lab) {
            in[0] = (float) (lab[0] / 100.0);
            in[1] = (float) ((lab[1] + 128) / 255.0);
            in[2] = (float) ((lab[2] + 128) / 255.0);
            forward.evaluate(in, out);
            for (var c = 0; c < proofChannels; c++) {
                in[c] = ColorMath.saturateWord(out[c] * 65535.0) / 65535.0f;
            }
        }

        /**
         * Returns the CIE 1976 color difference ({@code cmsDeltaE}).
         *
         * @param a the first L*a*b*
         * @param b the second L*a*b*
         * @return the difference
         */
        private static double deltaE(double[] a, double[] b) {
            var dL = Math.abs(a[0] - b[0]);
            var da = Math.abs(a[1] - b[1]);
            var db = Math.abs(a[2] - b[2]);
            return Math.sqrt(dL * dL + da * da + db * db);
        }
    }
}
