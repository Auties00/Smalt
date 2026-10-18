package com.github.auties00.smalt.audio.mp3;

/**
 * Implements {@link Mp3EncoderKernel} with scalar arithmetic; the reference the vector kernel is
 * tested against.
 */
final class ScalarMp3EncoderKernel implements Mp3EncoderKernel {
    /**
     * The rounding offset of the Layer III quantizer: values round to the nearest integer after
     * subtracting 0.0946 (ISO/IEC 11172-3 C.1.5.4.4.1).
     */
    static final float ROUNDING = 0.4054f;

    /**
     * Creates the kernel.
     */
    ScalarMp3EncoderKernel() {
    }

    @Override
    public void window(float[] input, int start, float[] window, float[] sums) {
        for (var q = 0; q < 64; q++) {
            var acc = 0.0f;
            for (var j = 0; j < 8; j++) {
                acc += window[64 * j + q] * input[start + 448 - 64 * j + q];
            }
            sums[q] = acc;
        }
    }

    @Override
    public void matrix(float[] folded, float[] matrix, float[] output) {
        for (var k = 0; k < 32; k++) {
            var acc = 0.0f;
            for (var n = 0; n < 32; n++) {
                acc += folded[n] * matrix[32 * n + k];
            }
            output[k] = acc;
        }
    }

    @Override
    public void mdct(float[] input, float[] table, float[] output) {
        for (var sb = 0; sb < 32; sb++) {
            var base = 36 * sb;
            for (var m = 0; m < 18; m++) {
                var acc = 0.0f;
                for (var i = 0; i < 36; i++) {
                    acc += input[base + i] * table[18 * i + m];
                }
                output[18 * sb + m] = acc;
            }
        }
    }

    @Override
    public int quantize(float[] pow34, int from, int to, float scale, int[] output) {
        var max = 0;
        for (var i = from; i < to; i++) {
            var q = (int) (pow34[i] * scale + ROUNDING);
            output[i] = q;
            max = Math.max(max, q);
        }
        return max;
    }
}
