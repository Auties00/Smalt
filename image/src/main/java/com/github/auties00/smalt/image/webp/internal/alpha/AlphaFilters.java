/* Ports libwebp 1.6.0 (src/dsp/filters.c, src/utils/filters_utils.c). Copyright 2010 Google Inc.;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.alpha;

/**
 * Applies and reverts the spatial prediction filters of WebP alpha planes.
 *
 * <p>Filter 0 stores samples as they are; filter 1 (horizontal) predicts each sample from its
 * left neighbor, filter 2 (vertical) from the sample above, and filter 3 (gradient) from
 * {@code clip(left + top - topLeft)}. On the first row every filter predicts from the left, and
 * the first sample of the first row is predicted from zero. On every other row the first sample
 * is predicted from the sample above.
 *
 * @implNote This implementation follows libwebp's {@code src/dsp/filters.c} and
 * {@code src/utils/filters_utils.c}, including the estimator's quirk of sampling the row
 * above at {@code width} rather than {@code stride} bytes back.
 */
public final class AlphaFilters {
    /**
     * No filtering.
     */
    public static final int NONE = 0;

    /**
     * Prediction from the left neighbor.
     */
    public static final int HORIZONTAL = 1;

    /**
     * Prediction from the sample above.
     */
    public static final int VERTICAL = 2;

    /**
     * Prediction from the clipped gradient of the left, top and top-left neighbors.
     */
    public static final int GRADIENT = 3;

    /**
     * Prevents instantiation.
     */
    private AlphaFilters() {
        throw new AssertionError();
    }

    /**
     * Reverts the filter of one row.
     *
     * <p>{@code in} and {@code out} may be the same row; {@code prev} must be a different row.
     *
     * @param filter the filter method, from 0 to 3
     * @param prev the array holding the previous reconstructed row, or {@code null} for the
     * first row
     * @param prevPos the index of the previous row in {@code prev}
     * @param in the array holding the filtered row
     * @param inPos the index of the filtered row
     * @param out the destination array
     * @param outPos the index of the destination row
     * @param width the row length
     */
    public static void unfilter(int filter, byte[] prev, int prevPos, byte[] in, int inPos, byte[] out, int outPos, int width) {
        switch (filter) {
            case HORIZONTAL -> unfilterHorizontal(prev, prevPos, in, inPos, out, outPos, width);
            case VERTICAL -> {
                if (prev == null) {
                    unfilterHorizontal(null, 0, in, inPos, out, outPos, width);
                } else {
                    for (var i = 0; i < width; i++) {
                        out[outPos + i] = (byte) (prev[prevPos + i] + in[inPos + i]);
                    }
                }
            }
            case GRADIENT -> {
                if (prev == null) {
                    unfilterHorizontal(null, 0, in, inPos, out, outPos, width);
                } else {
                    var top = prev[prevPos] & 0xFF;
                    var topLeft = top;
                    var left = top;
                    for (var i = 0; i < width; i++) {
                        top = prev[prevPos + i] & 0xFF;
                        left = (in[inPos + i] + gradientPredictor(left, top, topLeft)) & 0xFF;
                        topLeft = top;
                        out[outPos + i] = (byte) left;
                    }
                }
            }
            default -> {
                if (in != out || inPos != outPos) {
                    System.arraycopy(in, inPos, out, outPos, width);
                }
            }
        }
    }

    /**
     * Reverts the horizontal filter of one row.
     *
     * @param prev the array holding the previous row, or {@code null} for the first row
     * @param prevPos the index of the previous row
     * @param in the array holding the filtered row
     * @param inPos the index of the filtered row
     * @param out the destination array
     * @param outPos the index of the destination row
     * @param width the row length
     */
    private static void unfilterHorizontal(byte[] prev, int prevPos, byte[] in, int inPos, byte[] out, int outPos, int width) {
        var pred = prev == null ? 0 : prev[prevPos];
        for (var i = 0; i < width; i++) {
            pred = (byte) (pred + in[inPos + i]);
            out[outPos + i] = (byte) pred;
        }
    }

    /**
     * Returns {@code a + b - c} clipped to 0..255.
     *
     * @param a the left sample
     * @param b the top sample
     * @param c the top-left sample
     * @return the gradient prediction
     */
    static int gradientPredictor(int a, int b, int c) {
        var g = a + b - c;
        return (g & ~0xFF) == 0 ? g : g < 0 ? 0 : 255;
    }

    /**
     * Filters a whole plane.
     *
     * @param filter the filter method, from 1 to 3
     * @param in the plane
     * @param width the plane width
     * @param height the plane height
     * @param stride the row stride of {@code in}
     * @param out the destination of {@code width * height} filtered samples at stride
     * {@code stride}
     */
    public static void filter(int filter, byte[] in, int width, int height, int stride, byte[] out) {
        out[0] = in[0];
        for (var i = 1; i < width; i++) {
            out[i] = (byte) (in[i] - in[i - 1]);
        }
        for (var row = 1; row < height; row++) {
            var p = row * stride;
            var up = p - stride;
            switch (filter) {
                case HORIZONTAL -> {
                    out[p] = (byte) (in[p] - in[up]);
                    for (var i = 1; i < width; i++) {
                        out[p + i] = (byte) (in[p + i] - in[p + i - 1]);
                    }
                }
                case VERTICAL -> {
                    for (var i = 0; i < width; i++) {
                        out[p + i] = (byte) (in[p + i] - in[up + i]);
                    }
                }
                default -> {
                    out[p] = (byte) (in[p] - in[up]);
                    for (var i = 1; i < width; i++) {
                        var pred = gradientPredictor(in[p + i - 1] & 0xFF, in[up + i] & 0xFF, in[up + i - 1] & 0xFF);
                        out[p + i] = (byte) (in[p + i] - pred);
                    }
                }
            }
        }
    }

    /**
     * Guesses the filter that best decorrelates a plane from a sparse sample of its pixels.
     *
     * @param data the plane
     * @param width the plane width
     * @param height the plane height
     * @param stride the row stride
     * @return the filter method, from 0 to 3
     */
    public static int estimateBestFilter(byte[] data, int width, int height, int stride) {
        var bins = new boolean[4][16];
        for (var j = 2; j < height - 1; j += 2) {
            var p = j * stride;
            var mean = data[p] & 0xFF;
            for (var i = 2; i < width - 1; i += 2) {
                var cur = data[p + i] & 0xFF;
                var diff0 = Math.abs(cur - mean) >> 4;
                var diff1 = Math.abs(cur - (data[p + i - 1] & 0xFF)) >> 4;
                var diff2 = Math.abs(cur - (data[p + i - width] & 0xFF)) >> 4;
                var grad = gradientPredictor(data[p + i - 1] & 0xFF, data[p + i - width] & 0xFF, data[p + i - width - 1] & 0xFF);
                var diff3 = Math.abs(cur - grad) >> 4;
                bins[NONE][diff0] = true;
                bins[HORIZONTAL][diff1] = true;
                bins[VERTICAL][diff2] = true;
                bins[GRADIENT][diff3] = true;
                mean = (3 * mean + cur + 2) >> 2;
            }
        }
        var bestFilter = NONE;
        var bestScore = Integer.MAX_VALUE;
        for (var filter = NONE; filter <= GRADIENT; filter++) {
            var score = 0;
            for (var i = 0; i < 16; i++) {
                if (bins[filter][i]) {
                    score += i;
                }
            }
            if (score < bestScore) {
                bestScore = score;
                bestFilter = filter;
            }
        }
        return bestFilter;
    }
}
