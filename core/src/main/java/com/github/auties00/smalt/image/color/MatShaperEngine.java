package com.github.auties00.smalt.image.color;

/**
 * Transforms 8-bit RGB through input curves, a 3x3 matrix with an optional offset, and output
 * curves, in 1.14 fixed point.
 *
 * <p>Each input sample selects its linearized value from a 256-entry table in 1.14 fixed point;
 * the matrix, also in 1.14, is applied with rounding; the result is clamped to {@code 0..16384}
 * and selects the output sample from a 16385-entry table.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation reproduces Little CMS 2.17's {@code OptimizeMatrixShaper} and {@code MatShaperEval16}
 * ({@code cmsopt.c}) bit for bit, including the {@code floor(x * 16384 + 0.5)} conversion of the curves and the matrix
 * and the 8-bit output table that stores each sample already reduced to 8 bits. The per-pixel work runs in
 * {@link MatShaperKernel}, which has a scalar and a Vector API implementation.
 */
final class MatShaperEngine implements ColorEngine {
    /**
     * The number of pixels a kernel call processes at most, which sizes the scratch space.
     */
    static final int BLOCK = MatShaperKernel.BLOCK;

    /**
     * The tables and coefficients.
     */
    private final MatShaperKernel.Tables tables;

    /**
     * Builds the engine.
     *
     * @param input the input curves, one per channel
     * @param matrix the 3x3 matrix in row-major order
     * @param offset the offset, or {@code null}
     * @param output the output curves, one per channel
     */
    MatShaperEngine(ToneCurve[] input, double[] matrix, double[] offset, ToneCurve[] output) {
        var first = new int[3][256];
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < 256; i++) {
                var y = input[c].evaluate((float) (i / 255.0));
                first[c][i] = y < 131072.0 ? toFixed14(y) : 0x7FFFFFFF;
            }
        }
        var second = new byte[3][16385];
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < 16385; i++) {
                var value = output[c].evaluate((float) (i / 16384.0));
                if (value < 0) {
                    value = 0;
                }
                if (value > 1.0f) {
                    value = 1.0f;
                }
                second[c][i] = (byte) ColorMath.wordToByte(ColorMath.saturateWord(value * 65535.0));
            }
        }
        var m = new int[9];
        for (var i = 0; i < 9; i++) {
            m[i] = toFixed14(matrix[i]);
        }
        var o = new int[3];
        if (offset != null) {
            for (var i = 0; i < 3; i++) {
                o[i] = toFixed14(offset[i]);
            }
        }
        this.tables = new MatShaperKernel.Tables(first[0], first[1], first[2], m, o, second[0], second[1], second[2]);
    }

    /**
     * Converts a value to 1.14 fixed point.
     *
     * @param value the matrix coefficient or offset
     * @return {@code floor(value * 16384 + 0.5)}
     */
    private static int toFixed14(double value) {
        return (int) Math.floor(value * 16384.0 + 0.5);
    }

    /**
     * Returns the tables, for tests and benchmarks.
     *
     * @return the tables
     */
    MatShaperKernel.Tables tables() {
        return tables;
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var scratch = new int[MatShaperKernel.SCRATCH];
        var kernel = MatShaperKernel.INSTANCE;
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            kernel.apply(src, srcOffset + start * srcStride, srcStride, dst, dstOffset + start * dstStride, dstStride, n,
                    tables, scratch);
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        throw new IllegalStateException("8-bit engine");
    }
}
