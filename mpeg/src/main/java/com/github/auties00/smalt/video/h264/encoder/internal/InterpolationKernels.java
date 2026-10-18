package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Computes the half-sample luma planes of clause 8.4.2.2, from which every quarter-sample
 * position is derived; chroma prediction uses the decoder's
 * {@link com.github.auties00.smalt.video.h264.internal.H264Dsp#chromaMc}.
 *
 * <p>This class is the scalar reference implementation; a Vector API subclass overrides the hot
 * methods with identical results.
 */
class InterpolationKernels {
    /**
     * Constructs the scalar kernels.
     */
    InterpolationKernels() {
    }

    /**
     * Computes one row of the three half-sample planes of a padded luma plane.
     *
     * <p>For each sample index {@code i} of the row, {@code h[i]} is the horizontal half-sample
     * position {@code b} between {@code full[i]} and {@code full[i + 1]}, {@code v[i]} the vertical
     * position {@code h} between {@code full[i]} and {@code full[i + stride]}, and {@code c[i]}
     * the center position {@code j} (equations 8-241 to 8-246). The caller guarantees that three
     * samples above, below, left and right of the row lie inside the array.
     *
     * @param full the full-sample plane
     * @param start the index of the first sample of the row segment
     * @param count the number of samples of the row segment
     * @param stride the row stride shared by all four planes
     * @param h receives the horizontal half-sample plane
     * @param v receives the vertical half-sample plane
     * @param c receives the center half-sample plane
     * @param temp scratch space of at least {@code count + 5} values; the vertical intermediate
     * sums fit 16 bits
     */
    void halfPelRow(byte[] full, int start, int count, int stride, byte[] h, byte[] v, byte[] c, short[] temp) {
        for (var x = -2; x < count + 3; x++) {
            var i = start + x;
            temp[x + 2] = (short) ((full[i - 2 * stride] & 0xFF) - 5 * (full[i - stride] & 0xFF) + 20 * (full[i] & 0xFF)
                    + 20 * (full[i + stride] & 0xFF) - 5 * (full[i + 2 * stride] & 0xFF) + (full[i + 3 * stride] & 0xFF));
        }
        for (var x = 0; x < count; x++) {
            var i = start + x;
            var b = (full[i - 2] & 0xFF) - 5 * (full[i - 1] & 0xFF) + 20 * (full[i] & 0xFF)
                    + 20 * (full[i + 1] & 0xFF) - 5 * (full[i + 2] & 0xFF) + (full[i + 3] & 0xFF);
            h[i] = clip((b + 16) >> 5);
            v[i] = clip((temp[x + 2] + 16) >> 5);
            var j = temp[x] - 5 * temp[x + 1] + 20 * temp[x + 2] + 20 * temp[x + 3] - 5 * temp[x + 4] + temp[x + 5];
            c[i] = clip((j + 512) >> 10);
        }
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value the filtered sample, possibly outside 0 to 255
     * @return the clipped sample
     */
    static byte clip(int value) {
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }
}
