package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Convolves packed RGBA pictures, 8 or 16-bit, in single-precision floating point: separably with
 * two one-dimensional kernels, or with a two-dimensional kernel.
 *
 * <p>Samples keep their integer scale ({@code 0..255} or {@code 0..65535}) as floats. Taps that
 * fall outside the picture read the nearest edge pixel. Each output sample is rounded to nearest
 * (halves up) and clamped. Rows are converted to floats once per band into a ring, and every
 * multiply-accumulate runs the same loop, so output rows split into bands computed on several
 * threads by {@link ParallelBands} give the same result as one band.
 *
 * <p>Separable filtering can work in premultiplied alpha: colors are multiplied by
 * {@code alpha / max} when read, every channel is filtered, and the colors are divided by the
 * filtered alpha when written, so the color of transparent pixels never bleeds into visible
 * ones. Two-dimensional convolution filters the color channels straight and copies alpha.
 *
 * @implNote This implementation leaves the multiply-accumulate loop to the C2 compiler's automatic vectorization,
 * which measured faster in a large Gaussian blur than an explicit Vector API kernel of the same loop.
 */
final class FloatFilter {
    /**
     * Prevents instantiation.
     */
    private FloatFilter() {
        throw new AssertionError();
    }

    /**
     * Returns the normalized taps of a sampled Gaussian, truncated at three standard deviations
     * and at least one tap on each side.
     *
     * @param sigma the standard deviation, positive
     * @return the {@code 2r + 1} taps, summing to one
     */
    static float[] gaussian(double sigma) {
        var radius = (int) Math.max(1, Math.ceil(3 * sigma));
        var taps = new double[2 * radius + 1];
        double sum = 0;
        for (var k = -radius; k <= radius; k++) {
            var value = Math.exp(-(double) k * k / (2 * sigma * sigma));
            taps[k + radius] = value;
            sum += value;
        }
        var out = new float[taps.length];
        for (var i = 0; i < taps.length; i++) {
            out[i] = (float) (taps[i] / sum);
        }
        return out;
    }

    /**
     * Filters a packed picture separably.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param horizontal the horizontal taps, an odd number centered on the output pixel
     * @param vertical the vertical taps, an odd number centered on the output row
     * @param premultiplied whether to filter in premultiplied alpha
     * @return the filtered picture, at the input's depth
     */
    static Picture separable(Picture picture, float[] horizontal, float[] vertical, boolean premultiplied) {
        var out = allocate(picture);
        var height = picture.height();
        var work = 4L * picture.width() * height * (horizontal.length + vertical.length);
        var bands = ParallelBands.bands(height, work);
        ParallelBands.run(bands, band -> separableRows(picture, horizontal, vertical, premultiplied, out,
                ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
        return out;
    }

    /**
     * Convolves a packed picture with a two-dimensional kernel, copying alpha.
     *
     * @param picture the packed picture, 8 or 16-bit
     * @param kernel the convolution weights
     * @return the filtered picture, at the input's depth
     */
    static Picture convolve(Picture picture, ConvolutionKernel kernel) {
        var out = allocate(picture);
        var height = picture.height();
        var work = 4L * picture.width() * height * kernel.width() * kernel.height();
        var bands = ParallelBands.bands(height, work);
        ParallelBands.run(bands, band -> convolveRows(picture, kernel, out,
                ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
        return out;
    }

    /**
     * Allocates the output of a filter.
     *
     * @param picture the packed input
     * @return a picture of the same size, depth and alpha flag
     */
    private static Picture allocate(Picture picture) {
        return switch (picture) {
            case RgbaPicture _ -> {
                OpChecks.checkArray(4L * picture.width() * picture.height());
                yield RgbaPicture.allocate(picture.width(), picture.height(), picture.hasAlpha());
            }
            case Rgba16Picture _ -> {
                OpChecks.checkArray(4L * picture.width() * picture.height());
                yield Rgba16Picture.allocate(picture.width(), picture.height(), picture.hasAlpha());
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        };
    }

    /**
     * Filters the output rows {@code [top, bottom)} separably.
     *
     * @param picture the packed input
     * @param horizontal the horizontal taps
     * @param vertical the vertical taps
     * @param premultiplied whether to filter in premultiplied alpha
     * @param out the packed output
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void separableRows(Picture picture, float[] horizontal, float[] vertical, boolean premultiplied,
                                      Picture out, int top, int bottom) {
        var width = picture.width();
        var height = picture.height();
        var rowLength = 4 * width;
        var padX = horizontal.length / 2;
        var padY = vertical.length / 2;
        var padded = new float[4 * (width + 2 * padX)];
        var ring = new float[vertical.length][rowLength];
        var held = new int[vertical.length];
        java.util.Arrays.fill(held, -1);
        var acc = new float[rowLength];
        for (var y = top; y < bottom; y++) {
            java.util.Arrays.fill(acc, 0);
            for (var k = 0; k < vertical.length; k++) {
                var source = Math.clamp(y + k - padY, 0, height - 1);
                var slot = source % vertical.length;
                var row = ring[slot];
                if (held[slot] != source) {
                    read(picture, source, padded, padX, premultiplied);
                    java.util.Arrays.fill(row, 0);
                    for (var t = 0; t < horizontal.length; t++) {
                        multiplyAdd(padded, 4 * t, horizontal[t], row, 0, rowLength);
                    }
                    held[slot] = source;
                }
                multiplyAdd(row, 0, vertical[k], acc, 0, rowLength);
            }
            write(acc, out, y, premultiplied, 0, null);
        }
    }

    /**
     * Convolves the output rows {@code [top, bottom)} with a two-dimensional kernel.
     *
     * @param picture the packed input
     * @param kernel the convolution kernel
     * @param out the packed output
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void convolveRows(Picture picture, ConvolutionKernel kernel, Picture out, int top, int bottom) {
        var width = picture.width();
        var height = picture.height();
        var rowLength = 4 * width;
        var kernelWidth = kernel.width();
        var kernelHeight = kernel.height();
        var padX = kernelWidth / 2;
        var padY = kernelHeight / 2;
        var weights = kernel.weights();
        var scale = (float) kernel.scale();
        for (var i = 0; i < weights.length; i++) {
            weights[i] /= scale;
        }
        var offset = (float) (kernel.offset() * (out instanceof Rgba16Picture ? 257 : 1));
        var ring = new float[kernelHeight][4 * (width + 2 * padX)];
        var held = new int[kernelHeight];
        java.util.Arrays.fill(held, -1);
        var acc = new float[rowLength];
        for (var y = top; y < bottom; y++) {
            java.util.Arrays.fill(acc, 0);
            for (var j = 0; j < kernelHeight; j++) {
                var source = Math.clamp(y + j - padY, 0, height - 1);
                var slot = source % kernelHeight;
                if (held[slot] != source) {
                    read(picture, source, ring[slot], padX, false);
                    held[slot] = source;
                }
                for (var i = 0; i < kernelWidth; i++) {
                    var weight = weights[j * kernelWidth + i];
                    if (weight != 0) {
                        multiplyAdd(ring[slot], 4 * i, weight, acc, 0, rowLength);
                    }
                }
            }
            write(acc, out, y, false, offset, picture);
        }
    }

    /**
     * Reads one row of a packed picture as floats, repeating its edge pixels into the padding.
     *
     * @param picture the packed picture
     * @param y the row
     * @param dst the destination, {@code 4 * (width + 2 * pad)} floats
     * @param pad the pixels of padding on each side
     * @param premultiplied whether to premultiply the colors by {@code alpha / max}
     */
    static void read(Picture picture, int y, float[] dst, int pad, boolean premultiplied) {
        var width = picture.width();
        switch (picture) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var base = y * rgba.stride();
                var alpha = rgba.hasAlpha();
                for (var x = 0; x < width; x++) {
                    var p = base + 4 * x;
                    var d = 4 * (x + pad);
                    var a = alpha ? pixels[p + 3] & 0xFF : 255;
                    if (premultiplied) {
                        var factor = a / 255f;
                        dst[d] = (pixels[p] & 0xFF) * factor;
                        dst[d + 1] = (pixels[p + 1] & 0xFF) * factor;
                        dst[d + 2] = (pixels[p + 2] & 0xFF) * factor;
                    } else {
                        dst[d] = pixels[p] & 0xFF;
                        dst[d + 1] = pixels[p + 1] & 0xFF;
                        dst[d + 2] = pixels[p + 2] & 0xFF;
                    }
                    dst[d + 3] = a;
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var base = y * rgba.stride();
                var alpha = rgba.hasAlpha();
                for (var x = 0; x < width; x++) {
                    var p = base + 4 * x;
                    var d = 4 * (x + pad);
                    var a = alpha ? pixels[p + 3] & 0xFFFF : 65535;
                    if (premultiplied) {
                        var factor = a / 65535f;
                        dst[d] = (pixels[p] & 0xFFFF) * factor;
                        dst[d + 1] = (pixels[p + 1] & 0xFFFF) * factor;
                        dst[d + 2] = (pixels[p + 2] & 0xFFFF) * factor;
                    } else {
                        dst[d] = pixels[p] & 0xFFFF;
                        dst[d + 1] = pixels[p + 1] & 0xFFFF;
                        dst[d + 2] = pixels[p + 2] & 0xFFFF;
                    }
                    dst[d + 3] = a;
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + picture);
        }
        for (var x = 0; x < pad; x++) {
            System.arraycopy(dst, 4 * pad, dst, 4 * x, 4);
            System.arraycopy(dst, 4 * (pad + width - 1), dst, 4 * (pad + width + x), 4);
        }
    }

    /**
     * Writes one row of filtered floats into a packed picture.
     *
     * @param acc the filtered samples, {@code 4 * width} floats
     * @param out the packed picture
     * @param y the row
     * @param premultiplied whether the colors are premultiplied and must be divided by alpha
     * @param offset the value added to every color before rounding
     * @param alphaSource the packed picture, of the output's depth, whose alpha to copy instead of the filtered alpha,
     * or {@code null}
     */
    static void write(float[] acc, Picture out, int y, boolean premultiplied, float offset, Picture alphaSource) {
        var width = out.width();
        switch (out) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var base = y * rgba.stride();
                for (var x = 0; x < width; x++) {
                    var i = 4 * x;
                    var a = acc[i + 3];
                    var opaque = !rgba.hasAlpha() ? 255
                            : alphaSource instanceof RgbaPicture source ? source.pixels()[y * source.stride() + i + 3] & 0xFF
                            : Warp.clamp(a, 255);
                    if (premultiplied) {
                        var inverse = a > 0 ? 255f / a : 0;
                        pixels[base + i] = (byte) Warp.clamp(acc[i] * inverse, 255);
                        pixels[base + i + 1] = (byte) Warp.clamp(acc[i + 1] * inverse, 255);
                        pixels[base + i + 2] = (byte) Warp.clamp(acc[i + 2] * inverse, 255);
                    } else {
                        pixels[base + i] = (byte) Warp.clamp(acc[i] + offset, 255);
                        pixels[base + i + 1] = (byte) Warp.clamp(acc[i + 1] + offset, 255);
                        pixels[base + i + 2] = (byte) Warp.clamp(acc[i + 2] + offset, 255);
                    }
                    pixels[base + i + 3] = (byte) opaque;
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var base = y * rgba.stride();
                for (var x = 0; x < width; x++) {
                    var i = 4 * x;
                    var a = acc[i + 3];
                    var opaque = !rgba.hasAlpha() ? 65535
                            : alphaSource instanceof Rgba16Picture source ? source.pixels()[y * source.stride() + i + 3] & 0xFFFF
                            : Warp.clamp(a, 65535);
                    if (premultiplied) {
                        var inverse = a > 0 ? 65535f / a : 0;
                        pixels[base + i] = (short) Warp.clamp(acc[i] * inverse, 65535);
                        pixels[base + i + 1] = (short) Warp.clamp(acc[i + 1] * inverse, 65535);
                        pixels[base + i + 2] = (short) Warp.clamp(acc[i + 2] * inverse, 65535);
                    } else {
                        pixels[base + i] = (short) Warp.clamp(acc[i] + offset, 65535);
                        pixels[base + i + 1] = (short) Warp.clamp(acc[i + 1] + offset, 65535);
                        pixels[base + i + 2] = (short) Warp.clamp(acc[i + 2] + offset, 65535);
                    }
                    pixels[base + i + 3] = (short) opaque;
                }
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + out);
        }
    }

    /**
     * Adds a multiple of a run of floats to an accumulator:
     * {@code acc[accOffset + i] += weight * src[srcOffset + i]}, the product rounded before the
     * sum.
     *
     * @param src the source values
     * @param srcOffset the index of the first source value
     * @param weight the multiplier
     * @param acc the accumulators
     * @param accOffset the index of the first accumulator
     * @param length the number of values
     */
    static void multiplyAdd(float[] src, int srcOffset, float weight, float[] acc, int accOffset, int length) {
        for (var i = 0; i < length; i++) {
            acc[accOffset + i] += weight * src[srcOffset + i];
        }
    }
}
