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
 * {@code (i + 0.5) * n / m}, where source pixel {@code p} covers {@code [p, p + 1)}. A source
 * sample whose distance from that center is at least the (stretched) support gets no weight,
 * except as {@link #BOX} describes.
 *
 * <p>The cost of resampling is linear in the support: each output sample reads about
 * {@code 2 * support * max(1, s)} source samples per axis.
 */
public enum ResizeFilter {
    /**
     * Copies the source pixel whose area contains the output pixel's center. It never blends, so
     * it keeps hard edges and exact colors but aliases when reducing.
     */
    NEAREST(0),

    /**
     * Averages, with equal weights, the source samples whose centers lie closer than half a
     * (stretched) pixel to the output center, and with half weight those exactly half a pixel away
     * (the box {@code 1} for {@code |x| < 1/2} and {@code 1/2} at {@code |x| = 1/2}, so the filter
     * stays symmetric). A reduction by an integer factor averages exact blocks; when enlarging it
     * copies the nearest pixel, averaging the two nearest where the output center lies exactly
     * between them. Apart from samples exactly at the edge of the box, which Pillow's {@code BOX}
     * filter weights fully on one side only, this is Pillow's filter.
     */
    BOX(1),

    /**
     * Interpolates linearly (the triangle filter {@code 1 - |x|}, support 1).
     */
    BILINEAR(2),

    /**
     * Interpolates with the Hamming-windowed sinc {@code sinc(x) * (0.54 + 0.46 * cos(pi * x))},
     * support 1: as cheap as {@link #BILINEAR} with a sharper response. This is Pillow's
     * {@code HAMMING} filter.
     */
    HAMMING(2),

    /**
     * Interpolates with the Keys cubic convolution kernel with {@code a = -0.5} (Catmull-Rom,
     * support 2): sharper than {@link #BILINEAR}, with a small overshoot at edges. This is
     * ffmpeg's bicubic with {@code param0=0:param1=0.5} (B = 0, C = 0.5 in Mitchell-Netravali
     * terms), not its default B = 0, C = 0.6.
     */
    BICUBIC(4),

    /**
     * Interpolates with the Mitchell-Netravali cubic with {@code B = C = 1/3} (support 2), the
     * compromise between blurring, ringing and anisotropy recommended by Mitchell and Netravali
     * (1988): softer than {@link #BICUBIC}, with almost no overshoot. This is ffmpeg's bicubic with
     * {@code param0=1/3:param1=1/3}.
     */
    MITCHELL(4),

    /**
     * Interpolates with the Lanczos kernel {@code sinc(x) * sinc(x / 2)}, support 2: between
     * {@link #BICUBIC} and {@link #LANCZOS3}, with less ringing than the latter.
     */
    LANCZOS2(4),

    /**
     * Interpolates with the Lanczos kernel {@code sinc(x) * sinc(x / 3)}, support 3: sharp, with
     * some ringing at hard edges.
     */
    LANCZOS3(6),

    /**
     * Interpolates with the Lanczos kernel {@code sinc(x) * sinc(x / 4)}, support 4: the sharpest
     * filter, with the most ringing at hard edges and the most source samples per output sample.
     */
    LANCZOS4(8),

    /**
     * Averages the source area each output pixel covers, weighting every source pixel by the
     * exact length of its overlap with the output pixel's footprint. It is the best filter for
     * large reductions (a reduction by an integer factor averages exact blocks) and, when
     * enlarging, replicates pixels with anti-aliased boundaries.
     */
    AREA(2);

    /**
     * Twice the half-width of the unscaled kernel, in source pixels.
     */
    private final int doubleSupport;

    /**
     * Constructs a constant.
     *
     * @param doubleSupport twice the half-width of the unscaled kernel
     */
    ResizeFilter(int doubleSupport) {
        this.doubleSupport = doubleSupport;
    }

    /**
     * Returns twice the half-width of the unscaled kernel, in source pixels, so that the half
     * support of {@link #BOX} stays an integer.
     *
     * @return twice the support; {@code 0} for {@link #NEAREST}
     */
    int doubleSupport() {
        return doubleSupport;
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
            case BOX -> x < 0.5 ? 1 : x == 0.5 ? 0.5 : 0;
            case BILINEAR -> x < 1 ? 1 - x : 0;
            case HAMMING -> {
                if (x == 0) {
                    yield 1;
                }
                if (x >= 1) {
                    yield 0;
                }
                var px = Math.PI * x;
                yield Math.sin(px) / px * (0.54 + 0.46 * Math.cos(px));
            }
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
            case MITCHELL -> {
                // Mitchell and Netravali (1988) with B = C = 1/3, both pieces divided by 6
                if (x < 1) {
                    yield ((7 * x - 12) * x * x + 16.0 / 3) / 6;
                }
                if (x < 2) {
                    yield (((-7.0 / 3 * x + 12) * x - 20) * x + 32.0 / 3) / 6;
                }
                yield 0;
            }
            case LANCZOS2 -> lanczos(x, 2);
            case LANCZOS3 -> lanczos(x, 3);
            case LANCZOS4 -> lanczos(x, 4);
            case NEAREST, AREA -> throw new IllegalStateException(this + " is not point evaluated");
        };
    }

    /**
     * Evaluates the Lanczos kernel {@code sinc(x) * sinc(x / lobes)}.
     *
     * @param x the distance from the center; non-negative
     * @param lobes the number of lobes, which is also the support
     * @return the kernel value, {@code 0} at or past the support
     */
    private static double lanczos(double x, int lobes) {
        if (x == 0) {
            return 1;
        }
        if (x >= lobes) {
            return 0;
        }
        var px = Math.PI * x;
        return lobes * Math.sin(px) * Math.sin(px / lobes) / (px * px);
    }
}
