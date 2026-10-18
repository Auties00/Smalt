package com.github.auties00.smalt.image.ops;

/**
 * Implements {@link ResampleKernel} with plain Java arithmetic.
 *
 * <p>This is the reference implementation: the vector implementation must produce identical
 * values, and delegates to the range methods of this class for the samples its vector loops do
 * not cover.
 */
final class ScalarResampleKernel implements ResampleKernel {
    /**
     * Constructs the kernel, which filters one row per block.
     */
    ScalarResampleKernel() {
    }

    @Override
    public int lanes() {
        return 1;
    }

    @Override
    public void vertical(byte[] src, int[] offsets, int[] weights, int wOff, int taps, int length, int[] dst, int shift) {
        verticalRange(src, offsets, weights, wOff, taps, dst, shift, 0, length);
    }

    /**
     * Filters the samples {@code [from, to)} of rows of 8-bit samples vertically.
     *
     * @param src the source samples
     * @param offsets the index of the first sample of each tap row
     * @param weights the fixed-point tap weights
     * @param wOff the index of the first weight
     * @param taps the number of tap rows
     * @param dst the destination row
     * @param shift the right shift applied to each sum
     * @param from the first sample
     * @param to the sample after the last one
     */
    void verticalRange(byte[] src, int[] offsets, int[] weights, int wOff, int taps, int[] dst, int shift, int from, int to) {
        java.util.Arrays.fill(dst, from, to, 1 << (shift - 1));
        for (var k = 0; k < taps; k++) {
            var weight = weights[wOff + k];
            var offset = offsets[k];
            for (var x = from; x < to; x++) {
                dst[x] += weight * (src[offset + x] & 0xFF);
            }
        }
        for (var x = from; x < to; x++) {
            dst[x] >>= shift;
        }
    }

    @Override
    public void verticalWide(char[] src, int[] offsets, int[] weights, int wOff, int taps, int length, int[] dst,
                             boolean accumulate, int shift) {
        verticalWideRange(src, offsets, weights, wOff, taps, dst, accumulate, shift, 0, length);
    }

    /**
     * Filters the samples {@code [from, to)} of rows of 16-bit samples vertically.
     *
     * @param src the source samples
     * @param offsets the index of the first sample of each tap row
     * @param weights the fixed-point tap weights
     * @param wOff the index of the first weight
     * @param taps the number of tap rows
     * @param dst the destination row
     * @param accumulate whether to add to the destination
     * @param shift the final right shift, or {@code 0}
     * @param from the first sample
     * @param to the sample after the last one
     */
    void verticalWideRange(char[] src, int[] offsets, int[] weights, int wOff, int taps, int[] dst,
                           boolean accumulate, int shift, int from, int to) {
        if (!accumulate) {
            java.util.Arrays.fill(dst, from, to, 0);
        }
        for (var k = 0; k < taps; k++) {
            var weight = weights[wOff + k];
            var offset = offsets[k];
            for (var x = from; x < to; x++) {
                dst[x] += weight * src[offset + x];
            }
        }
        if (shift > 0) {
            var half = 1 << (shift - 1);
            for (var x = from; x < to; x++) {
                dst[x] = (dst[x] + half) >> shift;
            }
        }
    }

    @Override
    public void gatherRows(int[][] rows, int rowCount, int offset, int pixelStride, int length, int lanes, int[] dst) {
        for (var r = 0; r < rowCount; r++) {
            var row = rows[r];
            for (var x = 0; x < length; x++) {
                dst[x * lanes + r] = row[offset + x * pixelStride];
            }
        }
    }

    @Override
    public void horizontalRows(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, int count,
                               byte[] dst, int shift) {
        horizontalRowsRange(src, lanes, starts, counts, weights, stride, dst, shift, 0, count);
    }

    /**
     * Filters the output samples {@code [from, to)} of a lane-interleaved block of rows into
     * 8-bit samples.
     *
     * @param src the lane-interleaved intermediate values
     * @param lanes the number of lanes
     * @param starts the first tap of each output sample
     * @param counts the number of taps of each output sample
     * @param weights the fixed-point tap weights, {@code stride} per output sample
     * @param stride the number of weights per output sample
     * @param dst the lane-interleaved destination
     * @param shift the right shift applied to each sum
     * @param from the first output sample
     * @param to the output sample after the last one
     */
    void horizontalRowsRange(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, byte[] dst,
                             int shift, int from, int to) {
        var half = 1 << (shift - 1);
        for (var i = from; i < to; i++) {
            var base = starts[i] * lanes;
            var w = i * stride;
            var taps = counts[i];
            for (var r = 0; r < lanes; r++) {
                var sum = half;
                for (var k = 0; k < taps; k++) {
                    sum += weights[w + k] * src[base + k * lanes + r];
                }
                dst[i * lanes + r] = (byte) ScalarColorKernel.clamp(sum >> shift);
            }
        }
    }

    @Override
    public void horizontalRowsWide(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, int count, int[] dst) {
        for (var i = 0; i < count; i++) {
            var base = starts[i] * lanes;
            var w = i * stride;
            var taps = counts[i];
            for (var r = 0; r < lanes; r++) {
                var sum = 0;
                for (var k = 0; k < taps; k++) {
                    sum += weights[w + k] * src[base + k * lanes + r];
                }
                dst[i * lanes + r] = sum;
            }
        }
    }

    @Override
    public void interleave(byte[] planes, int pOff, int width, boolean opaque, byte[] dst, int dOff) {
        interleaveRange(planes, pOff, width, opaque, dst, dOff, 0, width);
    }

    /**
     * Joins the pixels {@code [from, to)} of four planar rows into packed RGBA.
     *
     * @param planes the planar samples
     * @param pOff the index of the first red sample
     * @param width the number of pixels in the row, the distance between planes
     * @param opaque whether to write opaque alpha
     * @param dst the RGBA destination
     * @param dOff the index of pixel {@code 0}'s first byte
     * @param from the first pixel
     * @param to the pixel after the last one
     */
    void interleaveRange(byte[] planes, int pOff, int width, boolean opaque, byte[] dst, int dOff, int from, int to) {
        for (var x = from; x < to; x++) {
            var out = dOff + 4 * x;
            dst[out] = planes[pOff + x];
            dst[out + 1] = planes[pOff + width + x];
            dst[out + 2] = planes[pOff + 2 * width + x];
            dst[out + 3] = opaque ? (byte) 255 : planes[pOff + 3 * width + x];
        }
    }

    @Override
    public void premultiply(byte[] src, int sOff, int width, char[] dst, int dOff) {
        premultiplyRange(src, sOff, width, dst, dOff, 0, width);
    }

    /**
     * Premultiplies the pixels {@code [from, to)} of one row.
     *
     * @param src the RGBA source
     * @param sOff the index of the row's first byte
     * @param width the number of pixels in the row, the distance between planes
     * @param dst the planar destination
     * @param dOff the index of the first red value
     * @param from the first pixel
     * @param to the pixel after the last one
     */
    void premultiplyRange(byte[] src, int sOff, int width, char[] dst, int dOff, int from, int to) {
        for (var x = from; x < to; x++) {
            var index = sOff + 4 * x;
            var a = src[index + 3] & 0xFF;
            dst[dOff + x] = (char) ((src[index] & 0xFF) * a);
            dst[dOff + width + x] = (char) ((src[index + 1] & 0xFF) * a);
            dst[dOff + 2 * width + x] = (char) ((src[index + 2] & 0xFF) * a);
            dst[dOff + 3 * width + x] = (char) (a * 255);
        }
    }

    @Override
    public void unpremultiply(int[] src, int sOff, int width, int scale, byte[] dst, int dOff) {
        unpremultiplyRange(src, sOff, width, scale, dst, dOff, 0, width);
    }

    /**
     * Unpremultiplies the pixels {@code [from, to)} of one row.
     *
     * @param src the planar sums
     * @param sOff the index of the first red sum
     * @param width the number of pixels in the row, the distance between planes
     * @param scale the fixed-point unit of the sums
     * @param dst the RGBA destination
     * @param dOff the index of pixel {@code 0}'s first byte
     * @param from the first pixel
     * @param to the pixel after the last one
     */
    void unpremultiplyRange(int[] src, int sOff, int width, int scale, byte[] dst, int dOff, int from, int to) {
        var alphaDivisor = 510L * scale;
        for (var x = from; x < to; x++) {
            var out = dOff + 4 * x;
            long a = src[sOff + 3 * width + x];
            if (a <= 0) {
                dst[out] = 0;
                dst[out + 1] = 0;
                dst[out + 2] = 0;
                dst[out + 3] = 0;
                continue;
            }
            dst[out] = (byte) divide(src[sOff + x], a);
            dst[out + 1] = (byte) divide(src[sOff + width + x], a);
            dst[out + 2] = (byte) divide(src[sOff + 2 * width + x], a);
            dst[out + 3] = (byte) Math.min(255, (2 * a + 255L * scale) / alphaDivisor);
        }
    }

    /**
     * Returns {@code 255 * premultiplied / alpha} rounded half up and clamped to {@code 0..255}.
     *
     * @param premultiplied the premultiplied color sum
     * @param alpha the alpha sum; positive
     * @return the straight color
     */
    private static int divide(int premultiplied, long alpha) {
        var quotient = Math.floorDiv(510L * premultiplied + alpha, 2 * alpha);
        return (int) Math.clamp(quotient, 0, 255);
    }
}
