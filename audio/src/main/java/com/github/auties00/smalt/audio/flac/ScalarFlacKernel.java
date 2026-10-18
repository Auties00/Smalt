package com.github.auties00.smalt.audio.flac;

/**
 * Implements {@link FlacKernel} with plain loops; the reference for {@link VectorFlacKernel}.
 */
final class ScalarFlacKernel implements FlacKernel {
    /**
     * Constructs the kernel.
     */
    ScalarFlacKernel() {
    }

    @Override
    public void toFloat(int[] in, float[] out, int count, float scale) {
        for (var i = 0; i < count; i++) {
            out[i] = in[i] * scale;
        }
    }

    @Override
    public void leftSide(int[] left, int[] side, int count) {
        for (var i = 0; i < count; i++) {
            side[i] = left[i] - side[i];
        }
    }

    @Override
    public void sideRight(int[] side, int[] right, int count) {
        for (var i = 0; i < count; i++) {
            side[i] = side[i] + right[i];
        }
    }

    @Override
    public void midSide(int[] mid, int[] side, int count) {
        for (var i = 0; i < count; i++) {
            var s = side[i];
            var m = (mid[i] << 1) | (s & 1);
            mid[i] = (m + s) >> 1;
            side[i] = (m - s) >> 1;
        }
    }
}
