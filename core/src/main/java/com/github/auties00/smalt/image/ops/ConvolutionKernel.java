package com.github.auties00.smalt.image.ops;

import java.util.Arrays;

/**
 * Describes a two-dimensional convolution kernel: a grid of weights, a scale that divides their
 * weighted sum and an offset added after the division.
 *
 * <p>{@link ImageFilters#convolve} computes each output sample as
 * {@code sum(weight(i, j) * input(x + i - cx, y + j - cy)) / scale + offset}, with
 * {@code (cx, cy)} the center of the kernel and the weights in row-major order, the first row
 * applying to the row above the center: the kernel is applied as written (a correlation), not
 * mirrored. The constants of this class reproduce Pillow's built-in filters; note that Pillow's
 * {@code ImageFilter.Kernel} applies the first row of its weights to the row below the center (it
 * mirrors kernels vertically), so an asymmetric Pillow kernel must be flipped vertically to give
 * the same result here.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ConvolutionKernel {
    /**
     * The largest width and height of a kernel.
     */
    public static final int MAX_SIZE = 99;

    /**
     * The number of columns, odd.
     */
    private final int width;

    /**
     * The number of rows, odd.
     */
    private final int height;

    /**
     * The weights in row-major order.
     */
    private final float[] weights;

    /**
     * The divisor of the weighted sum.
     */
    private final double scale;

    /**
     * The value added after the division.
     */
    private final double offset;

    /**
     * Creates a kernel.
     *
     * @param width   the number of columns
     * @param height  the number of rows
     * @param weights the weights, copied
     * @param scale   the divisor
     * @param offset  the value added after the division
     */
    private ConvolutionKernel(int width, int height, float[] weights, double scale, double offset) {
        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE || width % 2 == 0 || height % 2 == 0) {
            throw new IllegalArgumentException("kernel size " + width + "x" + height + " is not odd and within 1.." + MAX_SIZE);
        }
        if (weights.length != width * height) {
            throw new IllegalArgumentException("a " + width + "x" + height + " kernel needs " + width * height
                    + " weights, not " + weights.length);
        }
        for (var weight : weights) {
            if (!Float.isFinite(weight)) {
                throw new IllegalArgumentException("kernel weight " + weight + " is not finite");
            }
        }
        if (!Double.isFinite(scale) || scale == 0 || !Double.isFinite(offset)) {
            throw new IllegalArgumentException("invalid kernel scale " + scale + " or offset " + offset);
        }
        this.width = width;
        this.height = height;
        this.weights = weights.clone();
        this.scale = scale;
        this.offset = offset;
    }

    /**
     * Returns a kernel whose scale is the sum of its weights (or {@code 1} when they sum to zero)
     * and whose offset is zero, so a kernel of non-negative weights preserves the mean.
     *
     * @param width   the number of columns; odd, from 1 to {@link #MAX_SIZE}
     * @param height  the number of rows; odd, from 1 to {@link #MAX_SIZE}
     * @param weights the {@code width * height} weights in row-major order, copied
     * @return the kernel
     * @throws NullPointerException     if {@code weights} is {@code null}
     * @throws IllegalArgumentException if a dimension is invalid, the number of weights does not
     *                                  match, or a weight is not finite
     */
    public static ConvolutionKernel of(int width, int height, float... weights) {
        double sum = 0;
        for (var weight : weights) {
            sum += weight;
        }
        return new ConvolutionKernel(width, height, weights, sum == 0 ? 1 : sum, 0);
    }

    /**
     * Returns a kernel with an explicit scale and offset.
     *
     * @param width   the number of columns; odd, from 1 to {@link #MAX_SIZE}
     * @param height  the number of rows; odd, from 1 to {@link #MAX_SIZE}
     * @param weights the {@code width * height} weights in row-major order, copied
     * @param scale   the divisor of the weighted sum; finite and non-zero
     * @param offset  the value added after the division, in 8-bit sample units (scaled by 257
     *                for 16-bit pictures); finite
     * @return the kernel
     * @throws NullPointerException     if {@code weights} is {@code null}
     * @throws IllegalArgumentException if a dimension is invalid, the number of weights does not
     *                                  match, or a number is not finite or the scale is zero
     */
    public static ConvolutionKernel of(int width, int height, float[] weights, double scale, double offset) {
        return new ConvolutionKernel(width, height, weights, scale, offset);
    }

    /**
     * Returns Pillow's {@code SHARPEN} kernel: {@code 32} at the center and {@code -2} around it,
     * divided by 16.
     *
     * @return the kernel
     */
    public static ConvolutionKernel sharpen() {
        return of(3, 3, new float[]{-2, -2, -2, -2, 32, -2, -2, -2, -2}, 16, 0);
    }

    /**
     * Returns Pillow's {@code SMOOTH} kernel: {@code 5} at the center and {@code 1} around it,
     * divided by 13.
     *
     * @return the kernel
     */
    public static ConvolutionKernel smooth() {
        return of(3, 3, new float[]{1, 1, 1, 1, 5, 1, 1, 1, 1}, 13, 0);
    }

    /**
     * Returns Pillow's {@code DETAIL} kernel: {@code 10} at the center and {@code -1} on its four
     * sides, divided by 6.
     *
     * @return the kernel
     */
    public static ConvolutionKernel detail() {
        return of(3, 3, new float[]{0, -1, 0, -1, 10, -1, 0, -1, 0}, 6, 0);
    }

    /**
     * Returns Pillow's {@code EDGE_ENHANCE} kernel: {@code 10} at the center and {@code -1}
     * around it, divided by 2.
     *
     * @return the kernel
     */
    public static ConvolutionKernel edgeEnhance() {
        return of(3, 3, new float[]{-1, -1, -1, -1, 10, -1, -1, -1, -1}, 2, 0);
    }

    /**
     * Returns Pillow's {@code FIND_EDGES} kernel, a Laplacian: {@code 8} at the center and
     * {@code -1} around it.
     *
     * @return the kernel
     */
    public static ConvolutionKernel findEdges() {
        return of(3, 3, new float[]{-1, -1, -1, -1, 8, -1, -1, -1, -1}, 1, 0);
    }

    /**
     * Returns the kernel of Pillow's {@code EMBOSS} filter: the difference between a pixel and its
     * lower-left neighbor, offset by 128.
     *
     * @return the kernel
     */
    public static ConvolutionKernel emboss() {
        return of(3, 3, new float[]{0, 0, 0, 0, 1, 0, -1, 0, 0}, 1, 128);
    }

    /**
     * Returns the number of columns.
     *
     * @return the width, odd
     */
    public int width() {
        return width;
    }

    /**
     * Returns the number of rows.
     *
     * @return the height, odd
     */
    public int height() {
        return height;
    }

    /**
     * Returns the weights in row-major order.
     *
     * @return a copy of the weights
     */
    public float[] weights() {
        return weights.clone();
    }

    /**
     * Returns the weight at a position of the grid.
     *
     * @param column the column, from {@code 0}
     * @param row    the row, from {@code 0}
     * @return the weight
     * @throws IndexOutOfBoundsException if the position is outside the kernel
     */
    public float weight(int column, int row) {
        java.util.Objects.checkIndex(column, width);
        java.util.Objects.checkIndex(row, height);
        return weights[row * width + column];
    }

    /**
     * Returns the divisor of the weighted sum.
     *
     * @return the scale, non-zero
     */
    public double scale() {
        return scale;
    }

    /**
     * Returns the value added after the division, in 8-bit sample units.
     *
     * @return the offset
     */
    public double offset() {
        return offset;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConvolutionKernel kernel
                && width == kernel.width
                && height == kernel.height
                && Arrays.equals(weights, kernel.weights)
                && Double.compare(scale, kernel.scale) == 0
                && Double.compare(offset, kernel.offset) == 0;
    }

    @Override
    public int hashCode() {
        return ((31 * width + height) * 31 + Arrays.hashCode(weights)) * 31 + Double.hashCode(scale) * 7 + Double.hashCode(offset);
    }

    @Override
    public String toString() {
        return "ConvolutionKernel[" + width + "x" + height + ", weights=" + Arrays.toString(weights) + ", scale=" + scale
                + ", offset=" + offset + "]";
    }
}
