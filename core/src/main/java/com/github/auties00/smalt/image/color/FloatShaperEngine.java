package com.github.auties00.smalt.image.color;

import java.util.List;

/**
 * Transforms 16-bit pixels through input curves, an affine map and output curves in
 * single-precision floating point.
 *
 * <p>The engine handles every pipeline of the form curves, one or more matrices (with offsets),
 * curves, on one or three channels at each end: matrix-shaper RGB and gray profiles linked with
 * any intent, including black point compensation and absolute colorimetric scaling. Input curves
 * are tabulated at every 16-bit input. Output curves are tabulated at 16384 steps of the square
 * root of their input and interpolated linearly, which keeps steep power-law encodings accurate
 * near black.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation deliberately differs from Little CMS, which samples 16-bit matrix-shaper transforms
 * into a 33-point lookup table: evaluating the curves and the matrix directly is both faster and more accurate.
 */
final class FloatShaperEngine implements ColorEngine {
    /**
     * The number of steps of the output tables.
     */
    private static final int STEPS = 16384;

    /**
     * The linearized value of every 16-bit input sample, per input channel.
     */
    private final float[][] input;

    /**
     * The affine coefficients, {@code outputs} rows of {@code inputs} columns.
     */
    private final float[] matrix;

    /**
     * The affine offsets.
     */
    private final float[] offset;

    /**
     * The encoded output, in 16-bit units, at {@code (k / STEPS)^2} for {@code k = 0..STEPS},
     * per output channel, with the last entry repeated.
     */
    private final float[][] output;

    /**
     * Constructs an engine.
     *
     * @param input the linearized inputs
     * @param matrix the affine coefficients
     * @param offset the affine offsets
     * @param output the output tables
     */
    private FloatShaperEngine(float[][] input, float[] matrix, float[] offset, float[][] output) {
        this.input = input;
        this.matrix = matrix;
        this.offset = offset;
        this.output = output;
    }

    /**
     * Builds an engine from a simplified pipeline, if it has the supported shape.
     *
     * @param pipeline the simplified transform pipeline
     * @return the engine, or {@code null} when the pipeline is not curves, matrices, curves on one or three channels
     */
    static FloatShaperEngine of(Pipeline pipeline) {
        List<Stage> stages = pipeline.stages();
        if (stages.size() < 3 || !(stages.getFirst() instanceof Stage.Curves first)
                || !(stages.getLast() instanceof Stage.Curves last)) {
            return null;
        }
        var inputs = first.outputs;
        var outputs = last.inputs;
        if ((inputs != 1 && inputs != 3) || (outputs != 1 && outputs != 3)) {
            return null;
        }
        var rows = inputs;
        double[] a = null;
        double[] c = null;
        for (var i = 1; i < stages.size() - 1; i++) {
            if (!(stages.get(i) instanceof Stage.Matrix m) || m.inputs != rows) {
                return null;
            }
            if (a == null) {
                a = m.coefficients.clone();
                c = m.offset == null ? new double[m.outputs] : m.offset.clone();
            } else {
                var na = new double[m.outputs * inputs];
                var nc = new double[m.outputs];
                for (var r = 0; r < m.outputs; r++) {
                    for (var k = 0; k < inputs; k++) {
                        var sum = 0.0;
                        for (var j = 0; j < m.inputs; j++) {
                            sum += m.coefficients[r * m.inputs + j] * a[j * inputs + k];
                        }
                        na[r * inputs + k] = sum;
                    }
                    var sum = m.offset == null ? 0.0 : m.offset[r];
                    for (var j = 0; j < m.inputs; j++) {
                        sum += m.coefficients[r * m.inputs + j] * c[j];
                    }
                    nc[r] = sum;
                }
                a = na;
                c = nc;
            }
            rows = m.outputs;
        }
        if (rows != outputs) {
            return null;
        }
        var in = new float[inputs][65536];
        for (var ch = 0; ch < inputs; ch++) {
            for (var v = 0; v < 65536; v++) {
                in[ch][v] = first.curves[ch].evaluate((float) (v / 65535.0));
            }
        }
        var out = new float[outputs][STEPS + 2];
        for (var ch = 0; ch < outputs; ch++) {
            for (var k = 0; k <= STEPS; k++) {
                var x = (double) k / STEPS;
                var value = last.curves[ch].evaluate((float) (x * x));
                out[ch][k] = Math.min(Math.max(value, 0f), 1f) * 65535f;
            }
            out[ch][STEPS + 1] = out[ch][STEPS];
        }
        var matrix = new float[a.length];
        for (var i = 0; i < a.length; i++) {
            matrix[i] = (float) a[i];
        }
        var offset = new float[c.length];
        for (var i = 0; i < c.length; i++) {
            offset[i] = (float) c[i];
        }
        return new FloatShaperEngine(in, matrix, offset, out);
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        throw new IllegalStateException("16-bit engine");
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var inputs = input.length;
        var outputs = output.length;
        if (inputs == 3 && outputs == 3) {
            applyRgb(src, srcOffset, srcStride, dst, dstOffset, dstStride, count);
            return;
        }
        var linear = new float[inputs];
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < inputs; c++) {
                linear[c] = input[c][src[s + c] & 0xFFFF];
            }
            for (var r = 0; r < outputs; r++) {
                var sum = offset[r];
                for (var c = 0; c < inputs; c++) {
                    sum += matrix[r * inputs + c] * linear[c];
                }
                dst[d + r] = (short) encode(output[r], sum);
            }
        }
    }

    /**
     * Transforms RGB to RGB with the coefficients held in locals; the arithmetic is that of the
     * general loop, operation for operation.
     *
     * @param src the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcStride the samples between consecutive source pixels
     * @param dst the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstStride the samples between consecutive destination pixels
     * @param count the number of pixels
     */
    private void applyRgb(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                          int count) {
        var inR = input[0];
        var inG = input[1];
        var inB = input[2];
        var outR = output[0];
        var outG = output[1];
        var outB = output[2];
        var m = matrix;
        float m00 = m[0], m01 = m[1], m02 = m[2], m10 = m[3], m11 = m[4], m12 = m[5], m20 = m[6], m21 = m[7], m22 = m[8];
        float o0 = offset[0], o1 = offset[1], o2 = offset[2];
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            var r = inR[src[s] & 0xFFFF];
            var g = inG[src[s + 1] & 0xFFFF];
            var b = inB[src[s + 2] & 0xFFFF];
            dst[d] = (short) encode(outR, o0 + m00 * r + m01 * g + m02 * b);
            dst[d + 1] = (short) encode(outG, o1 + m10 * r + m11 * g + m12 * b);
            dst[d + 2] = (short) encode(outB, o2 + m20 * r + m21 * g + m22 * b);
        }
    }

    /**
     * Encodes a linear value through an output table.
     *
     * @param table the output table
     * @param value the linear value
     * @return the 16-bit sample
     */
    private static int encode(float[] table, float value) {
        if (!(value > 0f)) {
            return (int) (table[0] + 0.5f);
        }
        if (value >= 1f) {
            return (int) (table[STEPS] + 0.5f);
        }
        var position = (float) Math.sqrt(value) * STEPS;
        var index = (int) position;
        var fraction = position - index;
        var low = table[index];
        return (int) (low + (table[index + 1] - low) * fraction + 0.5f);
    }
}
