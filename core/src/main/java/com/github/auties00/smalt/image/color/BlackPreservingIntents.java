/* Ports Little CMS 2.17 (cmscnvrt.c, cmsgmt.c, cmslut.c). Copyright Marti Maria Saguer; MIT License.
 * Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Links CMYK to CMYK chains with the black-preserving intents, which are not ICC intents: they keep the colors
 * printed with black ink alone on the black channel ({@link Linker#K_ONLY}), or keep the black channel of every
 * color and recompute cyan, magenta and yellow around it ({@link Linker#K_PLANE}).
 *
 * <p>Both sample a CMYK to CMYK lookup table of 17 points per channel from the chain linked with the ICC intents
 * the black-preserving ones are based on. Black is mapped through the curve that joins the black-to-lightness
 * relationship of the source part of the chain with the inverse of that of the last profile. CMYK device links at the
 * end of the chain apply after the table. A chain that does not start with a CMYK profile and end with a CMYK output
 * profile whose black curve is monotonic links with the ICC intents instead, as does a black-plane chain whose last
 * profile has no measurable total area coverage or no CMYK to L*a*b* table.
 *
 * @implNote This implementation follows Little CMS 2.17: {@code BlackPreservingKOnlyIntents},
 * {@code BlackPreservingKPlaneIntents} and their samplers ({@code cmscnvrt.c}), {@code _cmsBuildKToneCurve},
 * {@code ComputeKToLstar} and {@code cmsDetectTAC} ({@code cmsgmt.c}) and {@code cmsPipelineEvalReverseFloat}
 * ({@code cmslut.c}). Where Little CMS fails to link, or links an empty pipeline (an identity), because the black
 * curve, the total area coverage or the input table of the last profile cannot be computed, this implementation links
 * with the ICC intents.
 */
final class BlackPreservingIntents {
    /**
     * The number of samples of the black curves ({@code _cmsBuildKToneCurve} is called with 4096).
     */
    private static final int K_TONE_POINTS = 4096;

    /**
     * The grid points per channel of the sampled table, {@code _cmsReasonableGridpointsByColorspace} for CMYK with the
     * default flags.
     */
    private static final int GRID_POINTS = 17;

    /**
     * The step of the finite differences of the reverse evaluation ({@code JACOBIAN_EPSILON}).
     */
    private static final float JACOBIAN_EPSILON = 0.001f;

    /**
     * The largest number of Newton iterations of the reverse evaluation ({@code INVERSION_MAX_ITERATIONS}).
     */
    private static final int MAX_ITERATIONS = 30;

    /**
     * The estimated cost of sampling one node, in the units of {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long NODE_WORK = 4096;

    /**
     * Prevents instantiation.
     */
    private BlackPreservingIntents() {
        throw new AssertionError();
    }

    /**
     * Links a chain with the black-only preserving intent ({@code BlackPreservingKOnlyIntents}).
     *
     * @param nodes the nodes with their ICC intents and the black point compensation that applies to each
     * @return the pipeline
     * @throws MediaException.Unsupported if consecutive color spaces do not match
     * @throws MediaException.Malformed if a profile lacks the tags its role needs
     */
    static Pipeline kOnly(Linker.Node[] nodes) {
        var last = lastPreserved(nodes);
        if (!applies(nodes, last)) {
            return Linker.link(nodes);
        }
        var cmyk2cmyk = Linker.link(Arrays.copyOf(nodes, last + 1));
        var kTone = kTone(Arrays.copyOf(nodes, last + 1));
        if (kTone == null) {
            return Linker.link(nodes);
        }
        var table = sample(band -> {
            var evaluator = cmyk2cmyk.evaluator();
            var in = new float[16];
            var out = new float[16];
            return (words, result) -> {
                if (words[0] == 0 && words[1] == 0 && words[2] == 0) {
                    result[0] = result[1] = result[2] = 0;
                    result[3] = kTone.evaluate16(words[3]);
                    return;
                }
                for (var i = 0; i < 4; i++) {
                    in[i] = words[i] / 65535.0f;
                }
                evaluator.evaluate(in, out);
                for (var i = 0; i < 4; i++) {
                    result[i] = ColorMath.saturateWord(out[i] * 65535.0);
                }
            };
        });
        return finish(nodes, last, table);
    }

    /**
     * Links a chain with the black-plane preserving intent ({@code BlackPreservingKPlaneIntents}).
     *
     * @param nodes the nodes with their ICC intents and the black point compensation that applies to each
     * @return the pipeline
     * @throws MediaException.Unsupported if consecutive color spaces do not match
     * @throws MediaException.Malformed if a profile lacks the tags its role needs
     */
    static Pipeline kPlane(Linker.Node[] nodes) {
        var last = lastPreserved(nodes);
        var output = nodes[last];
        if (!applies(nodes, last) || output.isLab() || output.profile().deviceClass != IccReader.signature("prtr")) {
            return Linker.link(nodes);
        }
        var labK2cmyk = ProfileLuts.input(output.profile(), Linker.RELATIVE);
        var maxTac = detectTotalAreaCoverage(output.profile()) / 100.0;
        var kTone = kTone(Arrays.copyOf(nodes, last + 1));
        if (labK2cmyk.inputs() != 4 || labK2cmyk.outputs() != 3 || !(maxTac > 0) || kTone == null) {
            return Linker.link(nodes);
        }
        var cmyk2cmyk = Linker.link(Arrays.copyOf(nodes, last + 1));
        var cmyk2Lab = Linker.link(List.of(Linker.Node.of(output.profile(), Linker.RELATIVE, false),
                Linker.Node.lab(4, Linker.RELATIVE)));
        cmyk2Lab.simplify();
        var table = sample(band -> {
            var transform = cmyk2cmyk.evaluator();
            var toLab = cmyk2Lab.evaluator();
            var reverse = labK2cmyk.evaluator();
            var inf = new float[16];
            var outf = new float[16];
            var labK = new float[16];
            var search = new Search();
            return (words, result) -> {
                for (var i = 0; i < 4; i++) {
                    inf[i] = (float) (words[i] / 65535.0);
                }
                labK[3] = kTone.evaluate(inf[3]);
                if (words[0] == 0 && words[1] == 0 && words[2] == 0) {
                    result[0] = result[1] = result[2] = 0;
                    result[3] = ColorMath.saturateWord(labK[3] * 65535.0);
                    return;
                }
                transform.evaluate(inf, outf);
                for (var i = 0; i < 4; i++) {
                    result[i] = ColorMath.saturateWord(outf[i] * 65535.0);
                }
                if (Math.abs(outf[3] - labK[3]) < 3.0 / 65535.0) {
                    return;
                }
                toLab.evaluate(outf, search.lab);
                System.arraycopy(search.lab, 0, labK, 0, 3);
                if (!evaluateReverse(reverse, 4, labK, outf, outf, search)) {
                    return;
                }
                outf[3] = labK[3];
                var sumCmy = (double) outf[0] + outf[1] + outf[2];
                var sumCmyk = sumCmy + outf[3];
                double ratio;
                if (sumCmyk > maxTac) {
                    ratio = 1 - ((sumCmyk - maxTac) / sumCmy);
                    if (ratio < 0) {
                        ratio = 0;
                    }
                } else {
                    ratio = 1.0;
                }
                result[0] = ColorMath.saturateWord(outf[0] * ratio * 65535.0);
                result[1] = ColorMath.saturateWord(outf[1] * ratio * 65535.0);
                result[2] = ColorMath.saturateWord(outf[2] * ratio * 65535.0);
                result[3] = ColorMath.saturateWord(outf[3] * 65535.0);
            };
        });
        return finish(nodes, last, table);
    }

    /**
     * Returns the index of the last node of the chain part that preserves black: the last one, before the CMYK device
     * links that end the chain, but never before the second node.
     *
     * @param nodes the chain
     * @return the index
     */
    private static int lastPreserved(Linker.Node[] nodes) {
        var last = nodes.length - 1;
        while (isCmykDeviceLink(nodes[last])) {
            if (last < 2) {
                break;
            }
            last--;
        }
        return last;
    }

    /**
     * Returns whether a node is a device link from CMYK.
     *
     * @param node the chain element to test
     * @return {@code true} for a CMYK device link
     */
    private static boolean isCmykDeviceLink(Linker.Node node) {
        return !node.isLab() && node.profile().deviceClass == IccReader.signature("link")
                && node.profile().colorSpace == IccReader.signature("CMYK");
    }

    /**
     * Returns whether black preservation applies to a chain: one that starts in CMYK and whose preserving part ends
     * with a CMYK or output profile.
     *
     * @param nodes the chain
     * @param last the index of the last node of the preserving part
     * @return {@code true} if black preservation applies
     */
    private static boolean applies(Linker.Node[] nodes, int last) {
        var cmyk = IccReader.signature("CMYK");
        var end = nodes[last];
        return nodes[0].colorSpace() == cmyk
                && (end.colorSpace() == cmyk
                || (!end.isLab() && end.profile().deviceClass == IccReader.signature("prtr")));
    }

    /**
     * Appends the trailing device links to the sampled table.
     *
     * @param nodes the chain
     * @param last the index of the last node of the preserving part
     * @param table the CMYK to CMYK samples
     * @return the pipeline
     * @throws MediaException.Malformed if a device link lacks its table
     */
    private static Pipeline finish(Linker.Node[] nodes, int last, int[] table) {
        var grid = new int[]{GRID_POINTS, GRID_POINTS, GRID_POINTS, GRID_POINTS};
        var stages = new ArrayList<Stage>();
        stages.add(new Stage.Clut(grid, 4, table, false, Stage.Role.PLAIN));
        var outputs = 4;
        for (var i = last + 1; i < nodes.length; i++) {
            var link = ProfileLuts.deviceLink(nodes[i].profile(), nodes[i].intent());
            stages.addAll(link.stages());
            outputs = link.outputs();
        }
        var result = new Pipeline(4, outputs);
        for (var stage : stages) {
            result.append(stage);
        }
        return result;
    }

    /**
     * Computes a CMYK to CMYK lookup table node by node, in parallel.
     *
     * @param samplers creates, for each band, a sampler with scratch space of its own
     * @return the 16-bit samples, four per node, the first channel varying slowest
     */
    private static int[] sample(SamplerFactory samplers) {
        var nodes = GRID_POINTS * GRID_POINTS * GRID_POINTS * GRID_POINTS;
        var table = new int[nodes * 4];
        var bands = ParallelBands.bands(nodes, NODE_WORK * nodes);
        ParallelBands.run(bands, band -> {
            var sampler = samplers.create(band);
            var words = new int[4];
            var result = new int[4];
            var end = ParallelBands.start(band + 1, bands, nodes);
            for (var node = ParallelBands.start(band, bands, nodes); node < end; node++) {
                var rest = node;
                for (var t = 3; t >= 0; --t) {
                    words[t] = ColorMath.saturateWord((rest % GRID_POINTS) * 65535.0 / (GRID_POINTS - 1));
                    rest /= GRID_POINTS;
                }
                sampler.sample(words, result);
                System.arraycopy(result, 0, table, node * 4, 4);
            }
        });
        return table;
    }

    /**
     * Creates the sampler of one band.
     */
    @FunctionalInterface
    private interface SamplerFactory {
        /**
         * Creates a sampler.
         *
         * @param band the band index
         * @return the sampler, used by one thread
         */
        Sampler create(int band);
    }

    /**
     * Computes the output of one table node.
     */
    @FunctionalInterface
    private interface Sampler {
        /**
         * Samples a node.
         *
         * @param words the 16-bit coordinates of the node
         * @param result receives the four 16-bit outputs
         */
        void sample(int[] words, int[] result);
    }

    /**
     * Builds the curve that maps the black of the chain's source to the black of its last profile at the same lightness
     * ({@code _cmsBuildKToneCurve}).
     *
     * @param nodes the preserving part of the chain, with their ICC intents
     * @return the curve, or {@code null} when the chain is not CMYK to a CMYK output profile or the curve is not
     * monotonic
     */
    static ToneCurve kTone(Linker.Node[] nodes) {
        var cmyk = IccReader.signature("CMYK");
        var lastNode = nodes[nodes.length - 1];
        if (nodes[0].colorSpace() != cmyk || lastNode.colorSpace() != cmyk) {
            return null;
        }
        if (lastNode.isLab() || lastNode.profile().deviceClass != IccReader.signature("prtr")) {
            return null;
        }
        var in = kToLightness(Arrays.copyOf(nodes, nodes.length - 1));
        var out = kToLightness(new Linker.Node[]{lastNode});
        var kTone = in.join(out, K_TONE_POINTS);
        return kTone.isMonotonic() ? kTone : null;
    }

    /**
     * Computes the curve from black ink to one minus the lightness through a chain ({@code ComputeKToLstar}).
     *
     * @param nodes the chain, starting in CMYK
     * @return the curve, segmented
     */
    private static ToneCurve kToLightness(Linker.Node[] nodes) {
        var chain = new ArrayList<>(Arrays.asList(nodes));
        chain.add(Linker.Node.lab(4, Linker.RELATIVE));
        var pipeline = Linker.link(chain);
        pipeline.simplify();
        var evaluator = pipeline.evaluator();
        var samples = new float[K_TONE_POINTS];
        var in = new float[16];
        var out = new float[16];
        for (var i = 0; i < K_TONE_POINTS; i++) {
            var k = (float) ((i * 100.0) / (K_TONE_POINTS - 1));
            in[0] = 0;
            in[1] = 0;
            in[2] = 0;
            in[3] = k / 100.0f;
            evaluator.evaluate(in, out);
            var lightness = (double) (out[0] * 100.0);
            samples[i] = (float) (1.0 - lightness / 100.0);
        }
        return ToneCurve.tabulatedFloat(samples);
    }

    /**
     * Estimates the total area coverage of an output profile: the largest sum of its ink amounts over a set of
     * L*a*b* colors converted with the perceptual intent ({@code cmsDetectTAC}).
     *
     * @param profile the parsed ICC profile
     * @return the coverage, in percent for ink spaces, or {@code 0} when the profile is not an output profile
     */
    static double detectTotalAreaCoverage(IccReader profile) {
        if (profile.deviceClass != IccReader.signature("prtr")) {
            return 0;
        }
        var channels = IccReader.channels(profile.colorSpace);
        if (channels == 0 || channels >= 16) {
            return 0;
        }
        var maximum = isInkSpace(profile.colorSpace) ? 100.0 : 1.0;
        Pipeline roundTrip;
        try {
            roundTrip = Linker.link(List.of(Linker.Node.lab(4, Linker.PERCEPTUAL),
                    Linker.Node.of(profile, Linker.PERCEPTUAL, false)));
        } catch (MediaException exception) {
            return 0;
        }
        roundTrip.simplify();
        var evaluator = roundTrip.evaluator();
        var points = new int[]{6, 74, 74};
        var in = new float[16];
        var out = new float[16];
        var maxTac = 0.0f;
        var total = points[0] * points[1] * points[2];
        for (var i = 0; i < total; i++) {
            var rest = i;
            for (var t = 2; t >= 0; --t) {
                var colorant = rest % points[t];
                rest /= points[t];
                in[t] = ColorMath.saturateWord(colorant * 65535.0 / (points[t] - 1)) / 65535.0f;
            }
            evaluator.evaluate(in, out);
            var sum = 0.0f;
            for (var c = 0; c < channels; c++) {
                sum += (float) (out[c] * maximum);
            }
            if (sum > maxTac) {
                maxTac = sum;
            }
        }
        return maxTac;
    }

    /**
     * Returns whether a color space holds ink amounts, which floating-point Little CMS formats scale to percents.
     *
     * @param space the color space signature
     * @return {@code true} for CMY, CMYK and the five to fifteen color spaces
     */
    static boolean isInkSpace(int space) {
        var name = IccReader.name(space);
        if (name.equals("CMY ") || name.equals("CMYK")) {
            return true;
        }
        if (name.endsWith("CLR")) {
            return Character.digit(name.charAt(0), 16) >= 5;
        }
        if (name.startsWith("MCH")) {
            return Character.digit(name.charAt(3), 16) >= 5;
        }
        return false;
    }

    /**
     * Holds the scratch space of the reverse evaluation.
     */
    private static final class Search {
        /**
         * The L*a*b* of the colorimetric output.
         */
        final float[] lab = new float[16];

        /**
         * The current estimate.
         */
        final float[] x = new float[16];

        /**
         * The value at the current estimate.
         */
        final float[] fx = new float[16];

        /**
         * The estimate moved along one axis.
         */
        final float[] xd = new float[16];

        /**
         * The value at the moved estimate.
         */
        final float[] fxd = new float[16];

        /**
         * The Jacobian, row-major.
         */
        final double[] jacobian = new double[9];
    }

    /**
     * Finds the inputs of a three or four-input, three-output pipeline that give a target, the fourth input held at the
     * target's fourth value, by Newton's method ({@code cmsPipelineEvalReverseFloat}).
     *
     * @param lut the evaluator of the pipeline
     * @param inputs the number of inputs of the pipeline, 3 or 4
     * @param target the three outputs to reach, then the fixed fourth input
     * @param result receives the inputs of the closest value found
     * @param hint the inputs the search starts from, read before {@code result} is written
     * @param search the scratch space
     * @return {@code false} when the Jacobian became singular
     */
    private static boolean evaluateReverse(Pipeline.Evaluator lut, int inputs, float[] target, float[] result,
                                           float[] hint, Search search) {
        var x = search.x;
        var fx = search.fx;
        var xd = search.xd;
        var fxd = search.fxd;
        var jacobian = search.jacobian;
        var lastError = 1e20;
        x[0] = hint[0];
        x[1] = hint[1];
        x[2] = hint[2];
        x[3] = inputs == 4 ? target[3] : 0;
        for (var i = 0; i < MAX_ITERATIONS; i++) {
            lut.evaluate(x, fx);
            double error = distance(fx, target);
            if (error >= lastError) {
                break;
            }
            lastError = error;
            System.arraycopy(x, 0, result, 0, inputs);
            if (error <= 0) {
                break;
            }
            for (var j = 0; j < 3; j++) {
                xd[0] = x[0];
                xd[1] = x[1];
                xd[2] = x[2];
                xd[3] = x[3];
                if (xd[j] < 1.0 - JACOBIAN_EPSILON) {
                    xd[j] += JACOBIAN_EPSILON;
                } else {
                    xd[j] -= JACOBIAN_EPSILON;
                }
                lut.evaluate(xd, fxd);
                jacobian[j] = (fxd[0] - fx[0]) / JACOBIAN_EPSILON;
                jacobian[3 + j] = (fxd[1] - fx[1]) / JACOBIAN_EPSILON;
                jacobian[6 + j] = (fxd[2] - fx[2]) / JACOBIAN_EPSILON;
            }
            var inverse = ColorMath.invert(jacobian);
            if (inverse == null) {
                return false;
            }
            double b0 = fx[0] - target[0];
            double b1 = fx[1] - target[1];
            double b2 = fx[2] - target[2];
            x[0] -= (float) (inverse[0] * b0 + inverse[1] * b1 + inverse[2] * b2);
            x[1] -= (float) (inverse[3] * b0 + inverse[4] * b1 + inverse[5] * b2);
            x[2] -= (float) (inverse[6] * b0 + inverse[7] * b1 + inverse[8] * b2);
            for (var j = 0; j < 3; j++) {
                if (x[j] < 0) {
                    x[j] = 0;
                } else if (x[j] > 1.0) {
                    x[j] = 1.0f;
                }
            }
        }
        return true;
    }

    /**
     * Returns the Euclidean distance between the first three values of two vectors, in single precision.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the distance
     */
    private static float distance(float[] a, float[] b) {
        var sum = 0.0f;
        for (var i = 0; i < 3; i++) {
            var difference = b[i] - a[i];
            sum += difference * difference;
        }
        return (float) Math.sqrt(sum);
    }
}
