/* Ports jxl-oxide 0.12.6 (crates/jxl-render: the generic filters). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Applies the restoration filters of a frame (ISO/IEC 18181-1 section J.2 and J.3): the
 * Gaborish 3x3 convolution and the edge-preserving filter.
 *
 * <p>Both filters read their input mirrored at the frame borders, and both run over the rows of
 * the frame on the shared helper threads; the result does not depend on the scheduling.
 */
public final class RestorationFilters {
    /**
     * The kernel of EPF step 0: the 12 neighbors within distance 2.
     */
    private static final int[] KERNEL_STEP0 = {0, -2, -1, -1, 0, -1, 1, -1, -2, 0, -1, 0, 1, 0, 2, 0, -1, 1, 0, 1, 1, 1, 0, 2};

    /**
     * The kernel of EPF steps 1 and 2: the 4 direct neighbors.
     */
    private static final int[] KERNEL_STEP12 = {0, -1, 0, 1, -1, 0, 1, 0};

    /**
     * The distance offsets of EPF step 0.
     */
    private static final int[] DIST_STEP0 = {0, -1, 1, 0, 0, 0, -1, 0, 0, 1};

    /**
     * The distance offsets of EPF step 1.
     */
    private static final int[] DIST_STEP1 = {0, -1, 0, 0, 0, 1, -1, 0, 1, 0};

    /**
     * The distance offsets of EPF step 2.
     */
    private static final int[] DIST_STEP2 = {0, 0};

    /**
     * The numerator of the weight slope, {@code -6.6 (1 - 1 / sqrt(2))}.
     */
    private static final float SLOPE_SCALE = 6.6f * ((float) (1.0 / Math.sqrt(2.0)) - 1.0f);

    /**
     * Prevents instantiation.
     */
    private RestorationFilters() {
        throw new AssertionError();
    }

    /**
     * Mirrors a coordinate into {@code 0..length-1}, duplicating the edge sample.
     *
     * @param offset the coordinate
     * @param length the length, positive
     * @return the mirrored coordinate
     */
    static int mirror(int offset, int length) {
        while (true) {
            if (offset < 0) {
                offset = -(offset + 1);
            } else if (offset >= length) {
                offset = 2 * length - offset - 1;
            } else {
                return offset;
            }
        }
    }

    /**
     * Applies the Gaborish filter to the three color planes.
     *
     * @param planes the X, Y and B planes, replaced by the filtered planes
     * @param width the plane width
     * @param height the plane height
     * @param weights the {@code w1, w2} weights of each channel
     */
    public static void gaborish(float[][] planes, int width, int height, float[] weights) {
        var paddedWidth = width + 2 * PAD;
        for (var c = 0; c < 3; c++) {
            var input = pad(planes[c], width, height);
            var output = new float[width * height];
            var w1 = weights[2 * c];
            var w2 = weights[2 * c + 1];
            var norm = 1.0f / (1.0f + 4.0f * w1 + 4.0f * w2);
            var bands = ParallelBands.bands(height, (long) width * height);
            ParallelBands.run(bands, band -> {
                var from = ParallelBands.start(band, bands, height);
                var to = ParallelBands.start(band + 1, bands, height);
                for (var y = from; y < to; y++) {
                    var mid = (y + PAD) * paddedWidth + PAD;
                    var top = mid - paddedWidth;
                    var bottom = mid + paddedWidth;
                    var out = y * width;
                    for (var x = 0; x < width; x++) {
                        var side = input[top + x] + input[mid + x - 1] + input[mid + x + 1] + input[bottom + x];
                        var diag = input[top + x - 1] + input[top + x + 1] + input[bottom + x - 1]
                                + input[bottom + x + 1];
                        output[out + x] = (input[mid + x] + side * w1 + diag * w2) * norm;
                    }
                }
            });
            planes[c] = output;
        }
    }

    /**
     * Applies the edge-preserving filter to the three color planes.
     *
     * @param planes the X, Y and B planes, replaced by the filtered planes
     * @param width the plane width
     * @param height the plane height
     * @param sigma the filter sigma of every 8x8 block
     * @param sigmaWidth the number of block columns of {@code sigma}
     * @param epf the filter parameters
     */
    public static void epf(float[][] planes, int width, int height, float[] sigma, int sigmaWidth,
                           FrameHeader.EpfParams epf) {
        var iterations = epf.iterations();
        var initial = new float[3][];
        ParallelBands.run(3, c -> initial[c] = pad(planes[c], width, height));
        var padded = initial;
        if (iterations == 3) {
            padded = step(padded, width, height, sigma, sigmaWidth, epf, 0);
        }
        padded = step(padded, width, height, sigma, sigmaWidth, epf, 1);
        if (iterations >= 2) {
            padded = step(padded, width, height, sigma, sigmaWidth, epf, 2);
        }
        var result = padded;
        var paddedWidth = width + 2 * PAD;
        ParallelBands.run(3, c -> {
            var plane = new float[width * height];
            for (var y = 0; y < height; y++) {
                System.arraycopy(result[c], (y + PAD) * paddedWidth + PAD, plane, y * width, width);
            }
            planes[c] = plane;
        });
    }

    /**
     * The number of samples each padded plane extends past every border: the reach of step 0, a
     * kernel offset of 2 plus a distance offset of 1.
     */
    private static final int PAD = 3;

    /**
     * Copies a plane into a larger one whose border of {@link #PAD} samples holds the mirrored
     * samples, so the filter reads every neighbor without a bounds check.
     *
     * @param plane the source samples
     * @param width the plane width
     * @param height the plane height
     * @return the padded plane, {@code width + 2 * PAD} samples wide
     */
    static float[] pad(float[] plane, int width, int height) {
        var paddedWidth = width + 2 * PAD;
        var padded = new float[paddedWidth * (height + 2 * PAD)];
        for (var y = -PAD; y < height + PAD; y++) {
            var source = mirror(y, height) * width;
            var target = (y + PAD) * paddedWidth;
            System.arraycopy(plane, source, padded, target + PAD, width);
            for (var x = 1; x <= PAD; x++) {
                padded[target + PAD - x] = plane[source + mirror(-x, width)];
                padded[target + PAD + width - 1 + x] = plane[source + mirror(width - 1 + x, width)];
            }
        }
        return padded;
    }

    /**
     * Fills the border of a padded plane with the mirrored samples of its interior, as
     * {@link #pad(float[], int, int)} lays them out.
     *
     * @param padded the padded plane, whose interior holds the samples
     * @param width the width of the interior
     * @param height the height of the interior
     */
    static void mirrorBorders(float[] padded, int width, int height) {
        var paddedWidth = width + 2 * PAD;
        for (var y = 0; y < height; y++) {
            var row = (y + PAD) * paddedWidth;
            for (var x = 1; x <= PAD; x++) {
                padded[row + PAD - x] = padded[row + PAD + mirror(-x, width)];
                padded[row + PAD + width - 1 + x] = padded[row + PAD + mirror(width - 1 + x, width)];
            }
        }
        for (var y = 1; y <= PAD; y++) {
            System.arraycopy(padded, (mirror(-y, height) + PAD) * paddedWidth, padded, (PAD - y) * paddedWidth,
                    paddedWidth);
            System.arraycopy(padded, (mirror(height - 1 + y, height) + PAD) * paddedWidth, padded,
                    (PAD + height - 1 + y) * paddedWidth, paddedWidth);
        }
    }

    /**
     * Runs one step of the edge-preserving filter.
     *
     * <p>Each row gets the weight slope of its samples, {@code -6.6 (1 - 1 / sqrt(2)) / sigma}
     * times the block-border multiplier, or NaN where the block sigma is below 0.3 and the filter
     * leaves the samples unchanged; {@link EpfKernel} then filters the row.
     *
     * @param inputs the padded X, Y and B planes
     * @param width the plane width in samples
     * @param height the plane height in samples
     * @param sigma the block sigmas
     * @param sigmaWidth the number of block columns
     * @param epf the parameters
     * @param step the step, from 0 to 2
     * @return the padded output planes
     */
    private static float[][] step(float[][] inputs, int width, int height, float[] sigma, int sigmaWidth,
                                  FrameHeader.EpfParams epf, int step) {
        var kernel = step == 0 ? KERNEL_STEP0 : KERNEL_STEP12;
        var dist = step == 0 ? DIST_STEP0 : step == 1 ? DIST_STEP1 : DIST_STEP2;
        var multiplier = step == 0 ? epf.pass0SigmaScale() : step == 2 ? epf.pass2SigmaScale() : 1.0f;
        var border = epf.borderSadMul();
        var scale = epf.channelScale();
        var paddedWidth = width + 2 * PAD;
        var kernelOffsets = new int[kernel.length / 2];
        for (var k = 0; k < kernelOffsets.length; k++) {
            kernelOffsets[k] = kernel[2 * k + 1] * paddedWidth + kernel[2 * k];
        }
        var distOffsets = new int[dist.length / 2];
        for (var d = 0; d < distOffsets.length; d++) {
            distOffsets[d] = dist[2 * d + 1] * paddedWidth + dist[2 * d];
        }
        var outputs = new float[3][paddedWidth * (height + 2 * PAD)];
        var sigmaRows = sigma.length / sigmaWidth;
        var bands = ParallelBands.bands(height, (long) width * height * 8);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, height);
            var to = ParallelBands.start(band + 1, bands, height);
            var sm = new float[8];
            var slopes = new float[width];
            var scratch = new float[EpfKernel.SCRATCH];
            for (var y = from; y < to; y++) {
                var yBorder = ((y + 1) & 6) == 0;
                for (var i = 0; i < 8; i++) {
                    sm[i] = yBorder || i == 0 || i == 7 ? multiplier * border : multiplier;
                }
                var sigmaRow = Math.min(y / 8, sigmaRows - 1) * sigmaWidth;
                for (var x = 0; x < width; x++) {
                    var s = sigma[sigmaRow + Math.min(x >> 3, sigmaWidth - 1)];
                    slopes[x] = s < 0.3f ? Float.NaN : SLOPE_SCALE / s * sm[x & 7];
                }
                EpfKernel.INSTANCE.filterRow(inputs[0], inputs[1], inputs[2], outputs[0], outputs[1], outputs[2],
                        (y + PAD) * paddedWidth + PAD, (y + PAD) * paddedWidth + PAD, width, slopes, kernelOffsets,
                        distOffsets, scale[0], scale[1], scale[2], scratch);
            }
        });
        ParallelBands.run(3, c -> mirrorBorders(outputs[c], width, height));
        return outputs;
    }
}
