package com.github.auties00.smalt.image.color;

/**
 * Transforms pixels through a lookup table sampled from a linked pipeline.
 *
 * <p>The pipeline is sampled on a regular grid of 16-bit coordinates (33 points per input for one
 * to three inputs, 17 for four, 7 for more) and pixels are interpolated in the grid: linearly for
 * one input, tetrahedrally for three, and for four inputs tetrahedrally in the last three
 * between the two grid planes around the first. When the device white of the source does not map
 * exactly to the device white of the target, the grid node at the source white is set to the
 * target white, so that white paper stays white.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's {@code OptimizeByResampling}, {@code XFormSampler16},
 * {@code FixWhiteMisalignment} and {@code PatchLUT} ({@code cmsopt.c}) and {@code _cmsReasonableGridpointsByColorspace}
 * ({@code cmspcs.c}), with the default flags.
 */
final class ClutEngine implements ColorEngine {
    /**
     * The grid interpolator.
     */
    private final Interpolator interpolator;

    /**
     * The number of inputs.
     */
    private final int inputs;

    /**
     * The number of outputs.
     */
    private final int outputs;

    /**
     * The grid samples.
     */
    private final int[] table;

    /**
     * Per input and 8-bit sample: the table offset of the lower grid node, for three or four
     * inputs; {@code null} otherwise.
     */
    private final int[][] nodes;

    /**
     * Per input and 8-bit sample: the 16-bit fraction between grid nodes.
     */
    private final int[][] fractions;

    /**
     * Per input and 8-bit sample: the offset of the upper grid node from the lower one, zero at
     * the last node.
     */
    private final int[][] steps;

    /**
     * Samples a pipeline into a grid.
     *
     * @param pipeline the transform pipeline to sample
     * @param inputSpace the source color space signature, which decides the device white
     * @param outputSpace the target color space signature, which decides the device white
     * @param fixWhite whether the white node is corrected
     */
    ClutEngine(Pipeline pipeline, int inputSpace, int outputSpace, boolean fixWhite) {
        this.inputs = pipeline.inputs();
        this.outputs = pipeline.outputs();
        var points = gridPoints(inputs, pipeline.stages().isEmpty());
        var grid = new int[inputs];
        java.util.Arrays.fill(grid, points);
        var nodes = 1;
        for (var i = 0; i < inputs; i++) {
            nodes *= points;
        }
        var table = new int[nodes * outputs];
        var in = new float[inputs];
        var out = new float[outputs];
        for (var node = 0; node < nodes; node++) {
            var rest = node;
            for (var t = inputs - 1; t >= 0; --t) {
                var colorant = rest % points;
                rest /= points;
                var quantized = ColorMath.saturateWord(colorant * 65535.0 / (points - 1));
                in[t] = (float) (quantized / 65535.0);
            }
            pipeline.evaluate(in, out);
            for (var o = 0; o < outputs; o++) {
                table[node * outputs + o] = ColorMath.saturateWord(out[o] * 65535.0);
            }
        }
        var interpolator = new Interpolator(grid, outputs, table, false);
        if (fixWhite) {
            fixWhite(interpolator, table, grid, inputSpace, outputSpace);
        }
        this.interpolator = interpolator;
        this.table = table;
        if (inputs == 3 || inputs == 4) {
            this.nodes = new int[inputs][256];
            this.fractions = new int[inputs][256];
            this.steps = new int[inputs][256];
            var stride = outputs;
            for (var d = inputs - 1; d >= 0; d--) {
                for (var v = 0; v < 256; v++) {
                    var fixed = ToneCurve.toFixedDomain(((v << 8) | v) * (points - 1));
                    this.nodes[d][v] = stride * (fixed >> 16);
                    this.fractions[d][v] = fixed & 0xFFFF;
                    this.steps[d][v] = v == 255 ? 0 : stride;
                }
                stride *= points;
            }
        } else {
            this.nodes = null;
            this.fractions = null;
            this.steps = null;
        }
    }

    /**
     * Returns the number of grid points per input that Little CMS chooses by default.
     *
     * @param inputs the number of inputs
     * @param empty whether the pipeline has no stages
     * @return the grid points
     */
    private static int gridPoints(int inputs, boolean empty) {
        if (empty) {
            return 2;
        }
        if (inputs > 4) {
            return 7;
        }
        return inputs == 4 ? 17 : 33;
    }

    /**
     * Sets the grid node at the source device white to the target device white when they do not
     * already match.
     *
     * @param interpolator the interpolator over the table
     * @param table the sampled grid, modified in place
     * @param grid the grid points per input
     * @param inputSpace the source color space signature
     * @param outputSpace the target color space signature
     */
    private void fixWhite(Interpolator interpolator, int[] table, int[] grid, int inputSpace, int outputSpace) {
        var whiteIn = white(inputSpace);
        var whiteOut = white(outputSpace);
        if (whiteIn == null || whiteOut == null || whiteIn.length != inputs || whiteOut.length != outputs) {
            return;
        }
        var obtained = new int[outputs];
        interpolator.evaluate(whiteIn, obtained);
        if (whitesEqual(whiteOut, obtained)) {
            return;
        }
        if (inputs != 1 && inputs != 3 && inputs != 4) {
            return;
        }
        var index = 0;
        var stride = outputs;
        for (var t = inputs - 1; t >= 0; --t) {
            var position = (double) whiteIn[t] * (grid[t] - 1) / 65535.0;
            var node = (int) Math.floor(position);
            if (position - node != 0) {
                return;
            }
            index += stride * node;
            stride *= grid[t];
        }
        System.arraycopy(whiteOut, 0, table, index, outputs);
    }

    /**
     * Returns whether two device whites are equal, or so different that correcting one would be
     * wrong.
     *
     * @param expected the expected white
     * @param obtained the obtained white
     * @return {@code true} if no correction is needed
     */
    private static boolean whitesEqual(int[] expected, int[] obtained) {
        for (var i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - obtained[i]) > 0xF000) {
                return true;
            }
            if (expected[i] != obtained[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the device white of a color space in 16-bit samples.
     *
     * @param space the color space signature
     * @return the white, or {@code null} for a space without a defined white
     */
    private static int[] white(int space) {
        return switch (IccReader.name(space)) {
            case "GRAY" -> new int[]{0xFFFF};
            case "RGB " -> new int[]{0xFFFF, 0xFFFF, 0xFFFF};
            case "Lab " -> new int[]{0xFFFF, 0x8080, 0x8080};
            case "CMYK" -> new int[]{0, 0, 0, 0};
            case "CMY " -> new int[]{0, 0, 0};
            default -> null;
        };
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        if (nodes != null) {
            applyTetrahedral(src, srcOffset, srcStride, dst, dstOffset, dstStride, count);
            return;
        }
        var in = new int[inputs];
        var out = new int[outputs];
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < inputs; c++) {
                var v = src[s + c] & 0xFF;
                in[c] = (v << 8) | v;
            }
            interpolator.evaluate(in, out);
            for (var c = 0; c < outputs; c++) {
                dst[d + c] = (byte) ColorMath.wordToByte(out[c]);
            }
        }
    }

    /**
     * Transforms 8-bit pixels of three or four inputs with per-sample node tables.
     *
     * <p>For three inputs each output is {@code c0 + round((v1 - c0) * w1 + (v2 - v1) * w2 +
     * (v3 - v2) * w3)} over the tetrahedron whose vertices are visited in the order of decreasing
     * fraction, rounded as {@code TetrahedralInterp16} rounds; for four inputs the same sum is
     * rounded as {@code Eval4Inputs} rounds it in the two grid planes around the first input, and
     * the two results are interpolated linearly. Both give exactly what {@link Interpolator}
     * gives.
     *
     * @param src the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcStride the samples between consecutive source pixels
     * @param dst the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstStride the samples between consecutive destination pixels
     * @param count the number of pixels
     */
    private void applyTetrahedral(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                  int count) {
        var four = inputs == 4;
        var first = four ? 1 : 0;
        var nx = nodes[first];
        var ny = nodes[first + 1];
        var nz = nodes[first + 2];
        var fxs = fractions[first];
        var fys = fractions[first + 1];
        var fzs = fractions[first + 2];
        var sxs = steps[first];
        var sys = steps[first + 1];
        var szs = steps[first + 2];
        var t = table;
        var outs = outputs;
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var x = src[s + first] & 0xFF;
            var y = src[s + first + 1] & 0xFF;
            var z = src[s + first + 2] & 0xFF;
            var fx = fxs[x];
            var fy = fys[y];
            var fz = fzs[z];
            var sx = sxs[x];
            var sy = sys[y];
            var sz = szs[z];
            int p1;
            int p2;
            int w1;
            int w2;
            int w3;
            if (fx >= fy) {
                if (fy >= fz) {
                    p1 = sx;
                    p2 = sx + sy;
                    w1 = fx;
                    w2 = fy;
                    w3 = fz;
                } else if (fz >= fx) {
                    p1 = sz;
                    p2 = sz + sx;
                    w1 = fz;
                    w2 = fx;
                    w3 = fy;
                } else {
                    p1 = sx;
                    p2 = sx + sz;
                    w1 = fx;
                    w2 = fz;
                    w3 = fy;
                }
            } else if (fx >= fz) {
                p1 = sy;
                p2 = sy + sx;
                w1 = fy;
                w2 = fx;
                w3 = fz;
            } else if (fy >= fz) {
                p1 = sy;
                p2 = sy + sz;
                w1 = fy;
                w2 = fz;
                w3 = fx;
            } else {
                p1 = sz;
                p2 = sz + sy;
                w1 = fz;
                w2 = fy;
                w3 = fx;
            }
            var p3 = sx + sy + sz;
            var base = nx[x] + ny[y] + nz[z];
            var d = dstOffset + i * dstStride;
            if (four) {
                var k = src[s] & 0xFF;
                var low = base + nodes[0][k];
                var high = low + steps[0][k];
                var fk = fractions[0][k];
                for (var o = 0; o < outs; o++) {
                    var a = low + o;
                    var c0 = t[a];
                    var v1 = t[a + p1];
                    var v2 = t[a + p2];
                    var rest = (v1 - c0) * w1 + (v2 - v1) * w2 + (t[a + p3] - v2) * w3;
                    var lower = (c0 + ((ToneCurve.toFixedDomain(rest) + 0x8000) >> 16)) & 0xFFFF;
                    var b = high + o;
                    c0 = t[b];
                    v1 = t[b + p1];
                    v2 = t[b + p2];
                    rest = (v1 - c0) * w1 + (v2 - v1) * w2 + (t[b + p3] - v2) * w3;
                    var upper = (c0 + ((ToneCurve.toFixedDomain(rest) + 0x8000) >> 16)) & 0xFFFF;
                    dst[d + o] = (byte) ColorMath.wordToByte(ToneCurve.linear(fk, lower, upper));
                }
            } else {
                for (var o = 0; o < outs; o++) {
                    var a = base + o;
                    var c0 = t[a];
                    var v1 = t[a + p1];
                    var v2 = t[a + p2];
                    var rest = (v1 - c0) * w1 + (v2 - v1) * w2 + (t[a + p3] - v2) * w3 + 0x8001;
                    dst[d + o] = (byte) ColorMath.wordToByte((c0 + ((rest + (rest >> 16)) >> 16)) & 0xFFFF);
                }
            }
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var in = new int[inputs];
        var out = new int[outputs];
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < inputs; c++) {
                in[c] = src[s + c] & 0xFFFF;
            }
            interpolator.evaluate(in, out);
            for (var c = 0; c < outputs; c++) {
                dst[d + c] = (short) out[c];
            }
        }
    }
}
