package com.github.auties00.smalt.image.raw.internal;

/**
 * Applies color matrices with plain loops: the reference implementation of {@link ColorKernel}.
 */
final class ScalarColorKernel implements ColorKernel {
    /**
     * Constructs the kernel.
     */
    ScalarColorKernel() {
    }

    @Override
    public void transform(float[] r, float[] g, float[] b, int count, float[] matrix, float scale) {
        var m0 = matrix[0];
        var m1 = matrix[1];
        var m2 = matrix[2];
        var m3 = matrix[3];
        var m4 = matrix[4];
        var m5 = matrix[5];
        var m6 = matrix[6];
        var m7 = matrix[7];
        var m8 = matrix[8];
        for (var index = 0; index < count; index++) {
            var red = r[index];
            var green = g[index];
            var blue = b[index];
            var x = m0 * red + m1 * green + m2 * blue;
            var y = m3 * red + m4 * green + m5 * blue;
            var z = m6 * red + m7 * green + m8 * blue;
            r[index] = Math.min(Math.max(x, 0f), 1f) * scale;
            g[index] = Math.min(Math.max(y, 0f), 1f) * scale;
            b[index] = Math.min(Math.max(z, 0f), 1f) * scale;
        }
    }

    @Override
    public void scale(float[] r, float[] g, float[] b, int count, float scale) {
        for (var index = 0; index < count; index++) {
            r[index] = Math.min(Math.max(r[index], 0f), 1f) * scale;
            g[index] = Math.min(Math.max(g[index], 0f), 1f) * scale;
            b[index] = Math.min(Math.max(b[index], 0f), 1f) * scale;
        }
    }
}
