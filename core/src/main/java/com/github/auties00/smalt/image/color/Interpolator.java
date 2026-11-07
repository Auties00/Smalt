package com.github.auties00.smalt.image.color;

/**
 * Interpolates a multidimensional lookup table of 16-bit samples at 16-bit input coordinates.
 *
 * <p>The table holds one node per combination of grid coordinates, the first input varying
 * slowest, and each node holds {@link #outputs()} samples. Coordinates span {@code 0..65535} over
 * the whole grid. The interpolation depends on the number of inputs: linear for one, bilinear for
 * two, tetrahedral (or trilinear on request) for three, and for four or more a linear
 * interpolation along the first input between two interpolations of the remaining ones.
 *
 * <p>Instances are immutable and thread-safe; the table is not copied and must not change.
 *
 * @implNote This implementation follows the 16-bit interpolators of Little CMS 2.17
 *           ({@code cmsintrp.c}: {@code LinLerp1D}, {@code Eval1Input},
 *           {@code BilinearInterp16}, {@code TrilinearInterp16}, {@code TetrahedralInterp16},
 *           {@code Eval4Inputs} and the {@code EvalNInputs} family) operation for operation,
 *           including their two different roundings of the tetrahedral sum, so that a table
 *           evaluated here gives the samples Little CMS gives.
 */
final class Interpolator {
    /**
     * The number of grid points per input, minus one.
     */
    private final int[] domain;

    /**
     * The table stride of each input, indexed from the last input: {@code opta[0]} is the number
     * of outputs, the stride of the last input, and {@code opta[i]} the stride of input
     * {@code inputs - 1 - i}.
     */
    private final int[] opta;

    /**
     * The number of outputs per node.
     */
    private final int outputs;

    /**
     * The table samples, {@code 0..65535}.
     */
    private final int[] table;

    /**
     * Whether three-input tables interpolate trilinearly instead of tetrahedrally.
     */
    private final boolean trilinear;

    /**
     * Creates an interpolator.
     *
     * @param grid      the number of grid points per input, each at least two
     * @param outputs   the number of outputs per node
     * @param table     the samples, {@code product(grid) * outputs} of them
     * @param trilinear whether three-input tables interpolate trilinearly
     */
    Interpolator(int[] grid, int outputs, int[] table, boolean trilinear) {
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
        this.trilinear = trilinear;
    }

    /**
     * Returns the number of inputs.
     *
     * @return the input count
     */
    int inputs() {
        return domain.length;
    }

    /**
     * Returns the number of outputs.
     *
     * @return the output count
     */
    int outputs() {
        return outputs;
    }

    /**
     * Evaluates the table.
     *
     * @param input  the coordinates, {@code 0..65535}, one per input
     * @param output receives the samples, one per output
     */
    void evaluate(int[] input, int[] output) {
        switch (domain.length) {
            case 1 -> {
                if (outputs == 1) {
                    linear1(input[0], output);
                } else {
                    eval1(input[0], output);
                }
            }
            case 2 -> bilinear(input, output);
            case 3 -> {
                if (trilinear) {
                    trilinear(input, output);
                } else {
                    tetrahedral(input, output);
                }
            }
            default -> evalN(input, 0, 0, output, domain.length, null);
        }
    }

    /**
     * Evaluates the table without allocating, for tables of four or more inputs, with scratch space from
     * {@link #scratch()}.
     *
     * @param input the coordinates, {@code 0..65535}, one per input
     * @param output receives the samples, one per output
     * @param scratch the scratch space of this interpolator, or {@code null} to allocate it
     */
    void evaluate(int[] input, int[] output, int[][] scratch) {
        if (domain.length < 4) {
            evaluate(input, output);
        } else {
            evalN(input, 0, 0, output, domain.length, scratch);
        }
    }

    /**
     * {@return scratch space for {@link #evaluate(int[], int[], int[][])}, two arrays of {@link #outputs()} samples
     * per interpolation level, or {@code null} for fewer than four inputs}
     */
    int[][] scratch() {
        if (domain.length < 4) {
            return null;
        }
        var levels = new int[2 * (domain.length - 3)][];
        for (var i = 0; i < levels.length; i++) {
            levels[i] = new int[outputs];
        }
        return levels;
    }

    /**
     * Interpolates a one-input, one-output table.
     *
     * @param value  the coordinate
     * @param output receives the sample
     */
    private void linear1(int value, int[] output) {
        if (value == 0xFFFF || domain[0] == 0) {
            output[0] = table[domain[0]];
            return;
        }
        var fixed = ToneCurve.toFixedDomain(domain[0] * value);
        var cell = fixed >> 16;
        var rest = fixed & 0xFFFF;
        output[0] = ToneCurve.linear(rest, table[cell], table[cell + 1]);
    }

    /**
     * Interpolates a one-input table with several outputs.
     *
     * @param value  the coordinate
     * @param output receives the samples
     */
    private void eval1(int value, int[] output) {
        if (value == 0xFFFF || domain[0] == 0) {
            var y0 = domain[0] * opta[0];
            for (var o = 0; o < outputs; o++) {
                output[o] = table[y0 + o];
            }
            return;
        }
        var fixed = ToneCurve.toFixedDomain(value * domain[0]);
        var k0 = fixed >> 16;
        var rk = fixed & 0xFFFF;
        var k1 = k0 + 1;
        var base0 = opta[0] * k0;
        var base1 = opta[0] * k1;
        for (var o = 0; o < outputs; o++) {
            output[o] = ToneCurve.linear(rk, table[base0 + o], table[base1 + o]);
        }
    }

    /**
     * Rounds a 16.16 fixed-point product the way the bilinear and trilinear interpolators do.
     *
     * @param a the fraction
     * @param l the low value
     * @param h the high value
     * @return the interpolated value, truncated to 16 bits
     */
    private static int lerp(int a, int l, int h) {
        return (l + (((h - l) * a + 0x8000) >> 16)) & 0xFFFF;
    }

    /**
     * Interpolates a two-input table bilinearly.
     *
     * @param input  the coordinates
     * @param output receives the samples
     */
    private void bilinear(int[] input, int[] output) {
        var fx = ToneCurve.toFixedDomain(input[0] * domain[0]);
        var x0 = fx >> 16;
        var rx = fx & 0xFFFF;
        var fy = ToneCurve.toFixedDomain(input[1] * domain[1]);
        var y0 = fy >> 16;
        var ry = fy & 0xFFFF;
        var bx0 = opta[1] * x0;
        var bx1 = bx0 + (input[0] == 0xFFFF ? 0 : opta[1]);
        var by0 = opta[0] * y0;
        var by1 = by0 + (input[1] == 0xFFFF ? 0 : opta[0]);
        for (var o = 0; o < outputs; o++) {
            var d00 = table[bx0 + by0 + o];
            var d01 = table[bx0 + by1 + o];
            var d10 = table[bx1 + by0 + o];
            var d11 = table[bx1 + by1 + o];
            var dx0 = lerp(rx, d00, d10);
            var dx1 = lerp(rx, d01, d11);
            output[o] = lerp(ry, dx0, dx1);
        }
    }

    /**
     * Interpolates a three-input table trilinearly.
     *
     * @param input  the coordinates
     * @param output receives the samples
     */
    private void trilinear(int[] input, int[] output) {
        var fx = ToneCurve.toFixedDomain(input[0] * domain[0]);
        var fy = ToneCurve.toFixedDomain(input[1] * domain[1]);
        var fz = ToneCurve.toFixedDomain(input[2] * domain[2]);
        var rx = fx & 0xFFFF;
        var ry = fy & 0xFFFF;
        var rz = fz & 0xFFFF;
        var x0 = opta[2] * (fx >> 16);
        var x1 = x0 + (input[0] == 0xFFFF ? 0 : opta[2]);
        var y0 = opta[1] * (fy >> 16);
        var y1 = y0 + (input[1] == 0xFFFF ? 0 : opta[1]);
        var z0 = opta[0] * (fz >> 16);
        var z1 = z0 + (input[2] == 0xFFFF ? 0 : opta[0]);
        for (var o = 0; o < outputs; o++) {
            var d000 = table[x0 + y0 + z0 + o];
            var d001 = table[x0 + y0 + z1 + o];
            var d010 = table[x0 + y1 + z0 + o];
            var d011 = table[x0 + y1 + z1 + o];
            var d100 = table[x1 + y0 + z0 + o];
            var d101 = table[x1 + y0 + z1 + o];
            var d110 = table[x1 + y1 + z0 + o];
            var d111 = table[x1 + y1 + z1 + o];
            var dx00 = lerp(rx, d000, d100);
            var dx01 = lerp(rx, d001, d101);
            var dx10 = lerp(rx, d010, d110);
            var dx11 = lerp(rx, d011, d111);
            var dxy0 = lerp(ry, dx00, dx10);
            var dxy1 = lerp(ry, dx01, dx11);
            output[o] = lerp(rz, dxy0, dxy1);
        }
    }

    /**
     * Interpolates a three-input table tetrahedrally, rounding the sum the fast way
     * {@code TetrahedralInterp16} does.
     *
     * @param input  the coordinates
     * @param output receives the samples
     */
    private void tetrahedral(int[] input, int[] output) {
        var fx = ToneCurve.toFixedDomain(input[0] * domain[0]);
        var fy = ToneCurve.toFixedDomain(input[1] * domain[1]);
        var fz = ToneCurve.toFixedDomain(input[2] * domain[2]);
        var rx = fx & 0xFFFF;
        var ry = fy & 0xFFFF;
        var rz = fz & 0xFFFF;
        var base = opta[2] * (fx >> 16) + opta[1] * (fy >> 16) + opta[0] * (fz >> 16);
        var x1 = input[0] == 0xFFFF ? 0 : opta[2];
        var y1 = input[1] == 0xFFFF ? 0 : opta[1];
        var z1 = input[2] == 0xFFFF ? 0 : opta[0];
        int a;
        int b;
        int c;
        int kind;
        if (rx >= ry) {
            if (ry >= rz) {
                a = x1;
                b = x1 + y1;
                c = b + z1;
                kind = 0;
            } else if (rz >= rx) {
                c = z1;
                a = z1 + x1;
                b = a + y1;
                kind = 1;
            } else {
                a = x1;
                c = x1 + z1;
                b = c + y1;
                kind = 2;
            }
        } else {
            if (rx >= rz) {
                b = y1;
                a = y1 + x1;
                c = a + z1;
                kind = 3;
            } else if (ry >= rz) {
                b = y1;
                c = y1 + z1;
                a = c + x1;
                kind = 4;
            } else {
                c = z1;
                b = z1 + y1;
                a = b + x1;
                kind = 5;
            }
        }
        for (var o = 0; o < outputs; o++) {
            var at = base + o;
            var c0 = table[at];
            var c1 = table[at + a];
            var c2 = table[at + b];
            var c3 = table[at + c];
            int d1;
            int d2;
            int d3;
            switch (kind) {
                case 0 -> {
                    d3 = c3 - c2;
                    d2 = c2 - c1;
                    d1 = c1 - c0;
                }
                case 1 -> {
                    d2 = c2 - c1;
                    d1 = c1 - c3;
                    d3 = c3 - c0;
                }
                case 2 -> {
                    d2 = c2 - c3;
                    d3 = c3 - c1;
                    d1 = c1 - c0;
                }
                case 3 -> {
                    d3 = c3 - c1;
                    d1 = c1 - c2;
                    d2 = c2 - c0;
                }
                case 4 -> {
                    d1 = c1 - c3;
                    d3 = c3 - c2;
                    d2 = c2 - c0;
                }
                default -> {
                    d1 = c1 - c2;
                    d2 = c2 - c3;
                    d3 = c3 - c0;
                }
            }
            var rest = d1 * rx + d2 * ry + d3 * rz + 0x8001;
            output[o] = (c0 + ((rest + (rest >> 16)) >> 16)) & 0xFFFF;
        }
    }

    /**
     * Interpolates a table of four or more inputs: two interpolations of the remaining inputs at
     * the grid points around the first one, then a linear interpolation between them.
     *
     * @param input      the coordinates
     * @param first      the index of the first input of this level
     * @param tableBase  the table offset of this level
     * @param output     receives the samples
     * @param dimensions the number of inputs of this level, at least four
     * @param scratch the scratch space of {@link #scratch()}, or {@code null} to allocate per level
     */
    private void evalN(int[] input, int first, int tableBase, int[] output, int dimensions, int[][] scratch) {
        var value = input[first];
        var fk = ToneCurve.toFixedDomain(value * domain[first]);
        var k0 = fk >> 16;
        var rk = fk & 0xFFFF;
        var stride = opta[dimensions - 1];
        var base0 = tableBase + stride * k0;
        var level = domain.length - dimensions;
        var low = scratch == null ? new int[outputs] : scratch[2 * level];
        var high = scratch == null ? new int[outputs] : scratch[2 * level + 1];
        if (dimensions == 4) {
            var base1 = base0 + (value == 0xFFFF ? 0 : stride);
            tetrahedralExact(input, first + 1, base0, low);
            tetrahedralExact(input, first + 1, base1, high);
        } else {
            var base1 = tableBase + stride * (k0 + (value != 0xFFFF ? 1 : 0));
            evalN(input, first + 1, base0, low, dimensions - 1, scratch);
            evalN(input, first + 1, base1, high, dimensions - 1, scratch);
        }
        for (var o = 0; o < outputs; o++) {
            output[o] = ToneCurve.linear(rk, low[o], high[o]);
        }
    }

    /**
     * Interpolates the last three inputs of a four-input level tetrahedrally, rounding the sum
     * exactly as {@code Eval4Inputs} does.
     *
     * @param input     the coordinates
     * @param first     the index of the first of the three inputs
     * @param tableBase the table offset of the sub-cube
     * @param output    receives the samples
     */
    private void tetrahedralExact(int[] input, int first, int tableBase, int[] output) {
        var fx = ToneCurve.toFixedDomain(input[first] * domain[first]);
        var fy = ToneCurve.toFixedDomain(input[first + 1] * domain[first + 1]);
        var fz = ToneCurve.toFixedDomain(input[first + 2] * domain[first + 2]);
        var rx = fx & 0xFFFF;
        var ry = fy & 0xFFFF;
        var rz = fz & 0xFFFF;
        var x0 = opta[2] * (fx >> 16);
        var x1 = x0 + (input[first] == 0xFFFF ? 0 : opta[2]);
        var y0 = opta[1] * (fy >> 16);
        var y1 = y0 + (input[first + 1] == 0xFFFF ? 0 : opta[1]);
        var z0 = opta[0] * (fz >> 16);
        var z1 = z0 + (input[first + 2] == 0xFFFF ? 0 : opta[0]);
        var t = table;
        for (var o = 0; o < outputs; o++) {
            var b = tableBase + o;
            var c0 = t[b + x0 + y0 + z0];
            int c1;
            int c2;
            int c3;
            if (rx >= ry && ry >= rz) {
                c1 = t[b + x1 + y0 + z0] - c0;
                c2 = t[b + x1 + y1 + z0] - t[b + x1 + y0 + z0];
                c3 = t[b + x1 + y1 + z1] - t[b + x1 + y1 + z0];
            } else if (rx >= rz && rz >= ry) {
                c1 = t[b + x1 + y0 + z0] - c0;
                c2 = t[b + x1 + y1 + z1] - t[b + x1 + y0 + z1];
                c3 = t[b + x1 + y0 + z1] - t[b + x1 + y0 + z0];
            } else if (rz >= rx && rx >= ry) {
                c1 = t[b + x1 + y0 + z1] - t[b + x0 + y0 + z1];
                c2 = t[b + x1 + y1 + z1] - t[b + x1 + y0 + z1];
                c3 = t[b + x0 + y0 + z1] - c0;
            } else if (ry >= rx && rx >= rz) {
                c1 = t[b + x1 + y1 + z0] - t[b + x0 + y1 + z0];
                c2 = t[b + x0 + y1 + z0] - c0;
                c3 = t[b + x1 + y1 + z1] - t[b + x1 + y1 + z0];
            } else if (ry >= rz && rz >= rx) {
                c1 = t[b + x1 + y1 + z1] - t[b + x0 + y1 + z1];
                c2 = t[b + x0 + y1 + z0] - c0;
                c3 = t[b + x0 + y1 + z1] - t[b + x0 + y1 + z0];
            } else if (rz >= ry && ry >= rx) {
                c1 = t[b + x1 + y1 + z1] - t[b + x0 + y1 + z1];
                c2 = t[b + x0 + y1 + z1] - t[b + x0 + y0 + z1];
                c3 = t[b + x0 + y0 + z1] - c0;
            } else {
                c1 = c2 = c3 = 0;
            }
            var rest = c1 * rx + c2 * ry + c3 * rz;
            output[o] = (c0 + ((ToneCurve.toFixedDomain(rest) + 0x8000) >> 16)) & 0xFFFF;
        }
    }
}
