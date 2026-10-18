package com.github.auties00.smalt.audio.process;

/**
 * Implements {@link MixKernel} with plain float arithmetic; the reference for the vector kernel.
 */
final class ScalarMixKernel implements MixKernel {
    /**
     * Constructs the kernel.
     */
    ScalarMixKernel() {
    }

    @Override
    public void mix(float[][] inputs, float[] gains, int count, float[] out, int frames) {
        var first = inputs[0];
        var g0 = gains[0];
        for (var i = 0; i < frames; i++) {
            out[i] = g0 * first[i];
        }
        for (var j = 1; j < count; j++) {
            var input = inputs[j];
            var g = gains[j];
            for (var i = 0; i < frames; i++) {
                out[i] = out[i] + g * input[i];
            }
        }
    }
}
