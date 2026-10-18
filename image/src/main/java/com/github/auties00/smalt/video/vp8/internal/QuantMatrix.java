/* Ports libwebp 1.6.0 (src/enc/vp8i_enc.h, VP8Matrix). Copyright (c) 2010, Google Inc.; BSD 3-Clause License. Modified
   for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the quantization parameters of one block type of one segment, in raster coefficient
 * order.
 */
final class QuantMatrix {
    /**
     * The number of fractional bits of {@link #iq} and {@link #bias}.
     */
    static final int QFIX = 17;

    /**
     * The quantizer steps.
     */
    final int[] q = new int[16];

    /**
     * The fixed-point reciprocals of the steps, {@code 2^17 / q}.
     */
    final int[] iq = new int[16];

    /**
     * The rounding biases in {@code 2^-17} units.
     */
    final int[] bias = new int[16];

    /**
     * The largest magnitudes that quantize to zero.
     */
    final int[] zthresh = new int[16];

    /**
     * The magnitudes added before quantization to slightly sharpen high frequencies.
     */
    final int[] sharpen = new int[16];

    /**
     * Creates a matrix of zeros.
     */
    QuantMatrix() {
    }

    /**
     * Copies another matrix into this one.
     *
     * @param other the source
     */
    void copyFrom(QuantMatrix other) {
        System.arraycopy(other.q, 0, q, 0, 16);
        System.arraycopy(other.iq, 0, iq, 0, 16);
        System.arraycopy(other.bias, 0, bias, 0, 16);
        System.arraycopy(other.zthresh, 0, zthresh, 0, 16);
        System.arraycopy(other.sharpen, 0, sharpen, 0, 16);
    }

    /**
     * Divides a magnitude by a step with a bias: {@code (n * iq + bias) >> 17} in unsigned 32-bit
     * arithmetic, as libwebp's {@code QUANTDIV}.
     *
     * @param n  the magnitude
     * @param iq the reciprocal step
     * @param b  the bias
     * @return the quantized level
     */
    static int quantDiv(int n, int iq, int b) {
        return (int) ((((long) n * iq + b) & 0xFFFFFFFFL) >>> QFIX);
    }
}
