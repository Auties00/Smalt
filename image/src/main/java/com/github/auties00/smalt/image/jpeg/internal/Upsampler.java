package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Brings a subsampled component plane to the full picture resolution.
 *
 * <p>Ratios of exactly two horizontally, vertically, or both use libjpeg's "fancy" triangle
 * filter, which interpolates each output sample from the nearest input sample (weight 3/4) and
 * the next nearest (weight 1/4) in each upsampled direction, replicating the edge samples. Other
 * integer ratios replicate samples, and non-integer ratios map each output sample to the input
 * sample that covers it.
 *
 * @implNote This implementation is a port of libjpeg-turbo's jdsample.c
 * ({@code h2v1_fancy_upsample}, {@code h1v2_fancy_upsample}, {@code h2v2_fancy_upsample},
 * {@code int_upsample}), with the same choice of filter: the triangle filter is used only when the
 * picture is not decoded at 1/8 size and, for horizontal upsampling, when the input is wider than
 * two samples. Non-integer ratios, which libjpeg rejects, are an extension.
 */
public final class Upsampler {
    /**
     * Prevents instantiation.
     */
    private Upsampler() {
        throw new AssertionError();
    }

    /**
     * Upsamples one component plane.
     *
     * @param input       the component samples
     * @param inputStride the input row stride
     * @param inputWidth  the meaningful input width, positive
     * @param inputHeight the meaningful input height, positive
     * @param h           the component's horizontal sampling factor
     * @param v           the component's vertical sampling factor
     * @param hMax        the frame's largest horizontal sampling factor
     * @param vMax        the frame's largest vertical sampling factor
     * @param width       the output width
     * @param height      the output height
     * @param fancy       whether the triangle filter may be used
     * @return the upsampled plane, {@code width} samples per row with a tight stride
     */
    public static byte[] upsample(byte[] input, int inputStride, int inputWidth, int inputHeight, int h, int v,
                                  int hMax, int vMax, int width, int height, boolean fancy) {
        var output = new byte[width * height];
        var integral = hMax % h == 0 && vMax % v == 0;
        var rx = hMax / h;
        var ry = vMax / v;
        if (integral && fancy && rx == 2 && ry == 1 && inputWidth > 2) {
            h2v1(input, inputStride, inputWidth, output, width, height);
        } else if (integral && fancy && rx == 1 && ry == 2) {
            h1v2(input, inputStride, inputWidth, inputHeight, output, width, height);
        } else if (integral && fancy && rx == 2 && ry == 2 && inputWidth > 2) {
            h2v2(input, inputStride, inputWidth, inputHeight, output, width, height);
        } else {
            nearest(input, inputStride, inputWidth, inputHeight, h, v, hMax, vMax, output, width, height);
        }
        return output;
    }

    /**
     * Upsamples by two horizontally with the triangle filter.
     *
     * @param input       the input samples
     * @param inputStride the input row stride
     * @param inputWidth  the input width, at least 3
     * @param output      the output plane
     * @param width       the output width
     * @param height      the output height, equal to the input height
     */
    private static void h2v1(byte[] input, int inputStride, int inputWidth, byte[] output, int width, int height) {
        var row = new byte[inputWidth * 2];
        for (var y = 0; y < height; y++) {
            var in = y * inputStride;
            var value = input[in] & 0xFF;
            row[0] = (byte) value;
            row[1] = (byte) ((value * 3 + (input[in + 1] & 0xFF) + 2) >> 2);
            for (var x = 1; x < inputWidth - 1; x++) {
                var near = (input[in + x] & 0xFF) * 3;
                row[2 * x] = (byte) ((near + (input[in + x - 1] & 0xFF) + 1) >> 2);
                row[2 * x + 1] = (byte) ((near + (input[in + x + 1] & 0xFF) + 2) >> 2);
            }
            var last = inputWidth - 1;
            value = input[in + last] & 0xFF;
            row[2 * last] = (byte) ((value * 3 + (input[in + last - 1] & 0xFF) + 1) >> 2);
            row[2 * last + 1] = (byte) value;
            System.arraycopy(row, 0, output, y * width, width);
        }
    }

    /**
     * Upsamples by two vertically with the triangle filter.
     *
     * @param input       the input samples
     * @param inputStride the input row stride
     * @param inputWidth  the input width, equal to the output width
     * @param inputHeight the input height
     * @param output      the output plane
     * @param width       the output width
     * @param height      the output height
     */
    private static void h1v2(byte[] input, int inputStride, int inputWidth, int inputHeight, byte[] output,
                             int width, int height) {
        for (var y = 0; y < height; y++) {
            var near = y >> 1;
            var far = (y & 1) == 0 ? Math.max(near - 1, 0) : Math.min(near + 1, inputHeight - 1);
            var bias = (y & 1) == 0 ? 1 : 2;
            var a = near * inputStride;
            var b = far * inputStride;
            var out = y * width;
            var columns = Math.min(width, inputWidth);
            for (var x = 0; x < columns; x++) {
                output[out + x] = (byte) (((input[a + x] & 0xFF) * 3 + (input[b + x] & 0xFF) + bias) >> 2);
            }
        }
    }

    /**
     * Upsamples by two in both directions with the triangle filter.
     *
     * @param input       the input samples
     * @param inputStride the input row stride
     * @param inputWidth  the input width, at least 3
     * @param inputHeight the input height
     * @param output      the output plane
     * @param width       the output width
     * @param height      the output height
     */
    private static void h2v2(byte[] input, int inputStride, int inputWidth, int inputHeight, byte[] output,
                             int width, int height) {
        var row = new byte[inputWidth * 2];
        for (var y = 0; y < height; y++) {
            var near = y >> 1;
            var far = (y & 1) == 0 ? Math.max(near - 1, 0) : Math.min(near + 1, inputHeight - 1);
            var a = near * inputStride;
            var b = far * inputStride;
            var thisSum = (input[a] & 0xFF) * 3 + (input[b] & 0xFF);
            var nextSum = (input[a + 1] & 0xFF) * 3 + (input[b + 1] & 0xFF);
            row[0] = (byte) ((thisSum * 4 + 8) >> 4);
            row[1] = (byte) ((thisSum * 3 + nextSum + 7) >> 4);
            var lastSum = thisSum;
            thisSum = nextSum;
            for (var x = 1; x < inputWidth - 1; x++) {
                nextSum = (input[a + x + 1] & 0xFF) * 3 + (input[b + x + 1] & 0xFF);
                row[2 * x] = (byte) ((thisSum * 3 + lastSum + 8) >> 4);
                row[2 * x + 1] = (byte) ((thisSum * 3 + nextSum + 7) >> 4);
                lastSum = thisSum;
                thisSum = nextSum;
            }
            var last = inputWidth - 1;
            row[2 * last] = (byte) ((thisSum * 3 + lastSum + 8) >> 4);
            row[2 * last + 1] = (byte) ((thisSum * 4 + 7) >> 4);
            System.arraycopy(row, 0, output, y * width, width);
        }
    }

    /**
     * Upsamples by replicating the input sample that covers each output sample.
     *
     * @param input       the input samples
     * @param inputStride the input row stride
     * @param inputWidth  the input width
     * @param inputHeight the input height
     * @param h           the component's horizontal sampling factor
     * @param v           the component's vertical sampling factor
     * @param hMax        the frame's largest horizontal sampling factor
     * @param vMax        the frame's largest vertical sampling factor
     * @param output      the output plane
     * @param width       the output width
     * @param height      the output height
     */
    private static void nearest(byte[] input, int inputStride, int inputWidth, int inputHeight, int h, int v,
                                int hMax, int vMax, byte[] output, int width, int height) {
        var columns = new int[width];
        for (var x = 0; x < width; x++) {
            columns[x] = Math.min((int) ((long) x * h / hMax), inputWidth - 1);
        }
        for (var y = 0; y < height; y++) {
            var source = Math.min((int) ((long) y * v / vMax), inputHeight - 1) * inputStride;
            var out = y * width;
            for (var x = 0; x < width; x++) {
                output[out + x] = input[source + columns[x]];
            }
        }
    }
}
