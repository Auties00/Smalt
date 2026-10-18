package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the reconstruction filters of
 * {@link ImageOps#resize(com.github.auties00.smalt.image.Picture, int, int, ResizeFilter)}.
 *
 * <p>Every filter except {@link #NEAREST} is applied separably, horizontally and vertically. When
 * an axis is reduced by a factor {@code s > 1}, the filter is stretched by {@code s} (its support
 * widens and its amplitude drops), which low-passes the picture before decimation and prevents
 * aliasing. Filters are evaluated at the exact positions of the sample centers: output pixel
 * {@code i} of an axis mapped from {@code n} to {@code m} samples is centered on source coordinate
 * {@code (i + 0.5) * n / m}, where source pixel {@code p} covers {@code [p, p + 1)}.
 */
public enum ResizeFilter {
    /**
     * Copies the source pixel whose area contains the output pixel's center. It never blends, so
     * it keeps hard edges and exact colors but aliases when reducing.
     */
    NEAREST(0),

    /**
     * Interpolates linearly (the triangle filter {@code 1 - |x|}, support 1).
     */
    BILINEAR(1),

    /**
     * Interpolates with the Keys cubic convolution kernel with {@code a = -0.5} (Catmull-Rom,
     * support 2): sharper than {@link #BILINEAR}, with a small overshoot at edges. This is
     * ffmpeg's bicubic with {@code param0=0:param1=0.5} (B = 0, C = 0.5 in Mitchell-Netravali
     * terms), not its default B = 0, C = 0.6.
     */
    BICUBIC(2),

    /**
     * Interpolates with the Lanczos kernel {@code sinc(x) * sinc(x / 3)}, support 3: the
     * sharpest filter, with some ringing at hard edges.
     */
    LANCZOS3(3),

    /**
     * Averages the source area each output pixel covers, weighting every source pixel by the
     * exact length of its overlap with the output pixel's footprint. It is the best filter for
     * large reductions (a reduction by an integer factor averages exact blocks) and, when
     * enlarging, replicates pixels with anti-aliased boundaries.
     */
    AREA(1);

    /**
     * The half-width of the unscaled kernel, in source pixels.
     */
    private final int support;

    /**
     * Constructs a constant.
     *
     * @param support the half-width of the unscaled kernel
     */
    ResizeFilter(int support) {
        this.support = support;
    }

    /**
     * Returns the half-width of the unscaled kernel, in source pixels.
     *
     * @return the support; {@code 0} for {@link #NEAREST}
     */
    int support() {
        return support;
    }

    /**
     * Evaluates the unscaled kernel of an interpolating filter.
     *
     * @param x the distance from the kernel's center, in (possibly stretched) source pixels;
     *          non-negative
     * @return the kernel value, {@code 0} outside the support
     * @throws IllegalStateException for {@link #NEAREST} and {@link #AREA}, which are not point
     *                               evaluated
     */
    double kernel(double x) {
        return switch (this) {
            case BILINEAR -> x < 1 ? 1 - x : 0;
            case BICUBIC -> {
                // Keys (1981) cubic convolution with a = -0.5
                if (x < 1) {
                    yield (1.5 * x - 2.5) * x * x + 1;
                }
                if (x < 2) {
                    yield ((-0.5 * x + 2.5) * x - 4) * x + 2;
                }
                yield 0;
            }
            case LANCZOS3 -> {
                if (x == 0) {
                    yield 1;
                }
                if (x >= 3) {
                    yield 0;
                }
                var px = Math.PI * x;
                yield 3 * Math.sin(px) * Math.sin(px / 3) / (px * px);
            }
            case NEAREST, AREA -> throw new IllegalStateException(this + " is not point evaluated");
        };
    }
}
