/* Ports Little CMS 2.17 (cmsintrp.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

/**
 * Interpolates a multidimensional lookup table of {@code float} samples at {@code float} input coordinates.
 *
 * <p>The table holds one node per combination of grid coordinates, the first input varying slowest, and each node
 * holds {@link #outputs()} samples. Coordinates span {@code 0..1} over the whole grid and are clamped to it, NaN
 * and values below {@code 1e-9} counting as zero. The interpolation depends on the number of inputs: linear for one,
 * bilinear for two, tetrahedral for three, and for four or more a linear interpolation along the first input
 * between two interpolations of the remaining ones.
 *
 * <p>Instances are immutable and thread-safe; the table is not copied and must not change.
 *
 * @implNote This implementation follows the floating-point interpolators of Little CMS 2.17 ({@code cmsintrp.c}:
 * {@code Eval1InputFloat}, {@code BilinearInterpFloat}, {@code TetrahedralInterpFloat}, {@code Eval4InputsFloat}
 * and the {@code EvalNInputsFloat} family) operation for operation in single precision, including the cell
 * selection of {@code _cmsQuickFloor}, so that a table evaluated here gives the values Little CMS gives.
 */
final class FloatInterpolator {
    /**
     * The number of grid points per input, minus one.
     */
    private final int[] domain;

    /**
     * The table stride of each input, indexed from the last input: {@code opta[0]} is the number of outputs, the
     * stride of the last input, and {@code opta[i]} the stride of input {@code inputs - 1 - i}.
     */
    private final int[] opta;

    /**
     * The number of outputs per node.
     */
    private final int outputs;

    /**
     * The table samples.
     */
    private final float[] table;

    /**
     * Creates an interpolator.
     *
     * @param grid the number of grid points per input, each at least two
     * @param outputs the number of outputs per node
     * @param table the samples, {@code product(grid) * outputs} of them
     */
    FloatInterpolator(int[] grid, int outputs, float[] table) {
        var inputs = grid.length;
        this.domain = new int[inputs];
        this.opta = new int[inputs];
        for (var i = 0; i < inputs; i++) {
            domain[i] = grid[i] - 1;
        }
        opta[0] = outputs;
        for (var i = 1; i < inputs; i++) {
            opta[i] = opta[i - 1] * grid[inputs - i];
        }
        this.outputs = outputs;
        this.table = table;
    }

    /**
     * {@return the number of outputs per node}
     */
    int outputs() {
        return outputs;
    }

    /**
     * Evaluates the table.
     *
     * @param input the coordinates, nominally {@code 0..1}, one per input
     * @param output receives the samples, one per output
     */
    void evaluate(float[] input, float[] output) {
        switch (domain.length) {
            case 1 -> linear(input[0], output);
            case 2 -> bilinear(input, output);
            case 3 -> tetrahedral(input, 0, 0, 0, output);
            default -> evaluateN(input, 0, 0, domain.length, output);
        }
    }

    /**
     * Interpolates a one-input table ({@code Eval1InputFloat}, {@code LinLerp1Dfloat}).
     *
     * @param value the coordinate
     * @param output receives the samples
     */
    private void linear(float value, float[] output) {
        var clamped = ToneCurve.clamp(value);
        if (clamped == 1.0f || domain[0] == 0) {
            var start = domain[0] * opta[0];
            for (var o = 0; o < outputs; o++) {
                output[o] = table[start + o];
            }
            return;
        }
        clamped *= domain[0];
        var cell0 = (int) Math.floor(clamped);
        var cell1 = (int) Math.ceil(clamped);
        var rest = clamped - cell0;
        cell0 *= opta[0];
        cell1 *= opta[0];
        for (var o = 0; o < outputs; o++) {
            var y0 = table[cell0 + o];
            var y1 = table[cell1 + o];
            output[o] = y0 + (y1 - y0) * rest;
        }
    }

    /**
     * Interpolates a two-input table ({@code BilinearInterpFloat}).
     *
     * @param input the coordinates
     * @param output receives the samples
     */
    private void bilinear(float[] input, float[] output) {
        var cx = ToneCurve.clamp(input[0]);
        var cy = ToneCurve.clamp(input[1]);
        var px = cx * domain[0];
        var py = cy * domain[1];
        var x0 = ColorMath.quickFloor(px);
        var fx = px - (float) x0;
        var y0 = ColorMath.quickFloor(py);
        var fy = py - (float) y0;
        var xa = opta[1] * x0;
        var xb = xa + (cx >= 1.0f ? 0 : opta[1]);
        var ya = opta[0] * y0;
        var yb = ya + (cy >= 1.0f ? 0 : opta[0]);
        for (var o = 0; o < outputs; o++) {
            var d00 = table[xa + ya + o];
            var d01 = table[xa + yb + o];
            var d10 = table[xb + ya + o];
            var d11 = table[xb + yb + o];
            var dx0 = d00 + (d10 - d00) * fx;
            var dx1 = d01 + (d11 - d01) * fx;
            output[o] = dx0 + (dx1 - dx0) * fy;
        }
    }

    /**
     * Interpolates the last three inputs of a table tetrahedrally ({@code TetrahedralInterpFloat}).
     *
     * @param input the coordinates
     * @param first the index of the first of the three coordinates in {@code input}
     * @param dimension the index in {@link #domain} of that coordinate
     * @param base the table offset of the sub-table the three coordinates address
     * @param output receives the samples
     */
    private void tetrahedral(float[] input, int first, int dimension, int base, float[] output) {
        var cx = ToneCurve.clamp(input[first]);
        var cy = ToneCurve.clamp(input[first + 1]);
        var cz = ToneCurve.clamp(input[first + 2]);
        var px = cx * domain[dimension];
        var py = cy * domain[dimension + 1];
        var pz = cz * domain[dimension + 2];
        var x0 = (int) Math.floor(px);
        var rx = px - (float) x0;
        var y0 = (int) Math.floor(py);
        var ry = py - (float) y0;
        var z0 = (int) Math.floor(pz);
        var rz = pz - (float) z0;
        var xa = opta[2] * x0;
        var xb = xa + (cx >= 1.0f ? 0 : opta[2]);
        var ya = opta[1] * y0;
        var yb = ya + (cy >= 1.0f ? 0 : opta[1]);
        var za = opta[0] * z0;
        var zb = za + (cz >= 1.0f ? 0 : opta[0]);
        for (var o = 0; o < outputs; o++) {
            var t = base + o;
            var c0 = table[t + xa + ya + za];
            float c1;
            float c2;
            float c3;
            if (rx >= ry && ry >= rz) {
                c1 = table[t + xb + ya + za] - c0;
                c2 = table[t + xb + yb + za] - table[t + xb + ya + za];
                c3 = table[t + xb + yb + zb] - table[t + xb + yb + za];
            } else if (rx >= rz && rz >= ry) {
                c1 = table[t + xb + ya + za] - c0;
                c2 = table[t + xb + yb + zb] - table[t + xb + ya + zb];
                c3 = table[t + xb + ya + zb] - table[t + xb + ya + za];
            } else if (rz >= rx && rx >= ry) {
                c1 = table[t + xb + ya + zb] - table[t + xa + ya + zb];
                c2 = table[t + xb + yb + zb] - table[t + xb + ya + zb];
                c3 = table[t + xa + ya + zb] - c0;
            } else if (ry >= rx && rx >= rz) {
                c1 = table[t + xb + yb + za] - table[t + xa + yb + za];
                c2 = table[t + xa + yb + za] - c0;
                c3 = table[t + xb + yb + zb] - table[t + xb + yb + za];
            } else if (ry >= rz && rz >= rx) {
                c1 = table[t + xb + yb + zb] - table[t + xa + yb + zb];
                c2 = table[t + xa + yb + za] - c0;
                c3 = table[t + xa + yb + zb] - table[t + xa + yb + za];
            } else if (rz >= ry && ry >= rx) {
                c1 = table[t + xb + yb + zb] - table[t + xa + yb + zb];
                c2 = table[t + xa + yb + zb] - table[t + xa + ya + zb];
                c3 = table[t + xa + ya + zb] - c0;
            } else {
                c1 = 0;
                c2 = 0;
                c3 = 0;
            }
            output[o] = c0 + c1 * rx + c2 * ry + c3 * rz;
        }
    }

    /**
     * Interpolates the last {@code count} inputs of a table, four or more, between two interpolations of the
     * remaining ones ({@code Eval4InputsFloat} to {@code Eval15InputsFloat}).
     *
     * @param input the coordinates
     * @param first the index of the first of the coordinates in {@code input}
     * @param base the table offset of the sub-table the coordinates address
     * @param count the number of coordinates, at least four
     * @param output receives the samples
     */
    private void evaluateN(float[] input, int first, int base, int count, float[] output) {
        var dimension = domain.length - count;
        var clamped = ToneCurve.clamp(input[first]);
        var pk = clamped * domain[dimension];
        var k0 = ColorMath.quickFloor(pk);
        var rest = pk - (float) k0;
        var low = base + opta[count - 1] * k0;
        var high = low + (clamped >= 1.0f ? 0 : opta[count - 1]);
        var tmp1 = new float[outputs];
        var tmp2 = new float[outputs];
        if (count == 4) {
            tetrahedral(input, first + 1, dimension + 1, low, tmp1);
            tetrahedral(input, first + 1, dimension + 1, high, tmp2);
        } else {
            evaluateN(input, first + 1, low, count - 1, tmp1);
            evaluateN(input, first + 1, high, count - 1, tmp2);
        }
        for (var o = 0; o < outputs; o++) {
            var y0 = tmp1[o];
            var y1 = tmp2[o];
            output[o] = y0 + (y1 - y0) * rest;
        }
    }
}
