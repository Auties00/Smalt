/* Ports jxl-oxide 0.12.6 (crates/jxl-render: upsample, apply_jpeg_upsampling_single). Copyright Wonwoo Choi and the
   jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Upsamples planes by 2, 4 or 8 with the non-separable kernels of the image header (ISO/IEC
 * 18181-1 section K.2), and upsamples JPEG-style subsampled chroma (section J.1).
 *
 * <p>Each output sample of the non-separable upsampling is a weighted sum of the 5x5
 * neighborhood of its source sample, clamped to the range of that neighborhood; a factor of 2^k
 * applies the 8x kernel {@code k / 3} times, then the 2x or 4x kernel.
 *
 * @implNote This implementation expands the weights into symmetric quarter kernels.
 */
public final class Upsampler {
    /**
     * Prevents instantiation.
     */
    private Upsampler() {
        throw new AssertionError();
    }

    /**
     * Upsamples a plane by {@code 2^shift}.
     *
     * @param plane the source samples
     * @param width the source width in samples
     * @param height the source height in samples
     * @param shift the base-2 logarithm of the factor, from 1 to 6
     * @param image the image header holding the kernels
     * @return the upsampled plane, {@code width << shift} by {@code height << shift}
     */
    public static float[] upsample(float[] plane, int width, int height, int shift, ImageHeader image) {
        var current = plane;
        var w = width;
        var h = height;
        for (var i = 0; i < shift / 3; i++) {
            current = upsampleOnce(current, w, h, 8, image.upsampling8());
            w *= 8;
            h *= 8;
        }
        switch (shift % 3) {
            case 1 -> current = upsampleOnce(current, w, h, 2, image.upsampling2());
            case 2 -> current = upsampleOnce(current, w, h, 4, image.upsampling4());
            default -> {
            }
        }
        return current;
    }

    /**
     * Upsamples a plane by one kernel.
     *
     * @param plane the source samples
     * @param width the source width in samples
     * @param height the source height in samples
     * @param k the factor: 2, 4 or 8
     * @param weights the kernel weights
     * @return the upsampled plane
     */
    private static float[] upsampleOnce(float[] plane, int width, int height, int k, float[] weights) {
        var matN = k / 2;
        var quarter = new float[matN * matN][25];
        var idx = 0;
        for (var y = 0; y < 5 * matN; y++) {
            var matY = y / 5;
            var ky = y % 5;
            for (var x = y; x < 5 * matN; x++) {
                var matX = x / 5;
                var kx = x % 5;
                var w = weights[idx++];
                quarter[matY * matN + matX][ky * 5 + kx] = w;
                quarter[matX * matN + matY][kx * 5 + ky] = w;
            }
        }
        var outWidth = width * k;
        var outHeight = height * k;
        var out = new float[outWidth * outHeight];
        var bands = ParallelBands.bands(height, (long) outWidth * outHeight * 4);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, height);
            var to = ParallelBands.start(band + 1, bands, height);
            var cols = new int[5];
            var rows = new int[5];
            var window = new float[25];
            for (var refY = from; refY < to; refY++) {
                for (var i = 0; i < 5; i++) {
                    rows[i] = RestorationFilters.mirror(refY + i - 2, height) * width;
                }
                for (var refX = 0; refX < width; refX++) {
                    for (var i = 0; i < 5; i++) {
                        cols[i] = RestorationFilters.mirror(refX + i - 2, width);
                    }
                    var min = Float.POSITIVE_INFINITY;
                    var max = Float.NEGATIVE_INFINITY;
                    for (var iy = 0; iy < 5; iy++) {
                        for (var ix = 0; ix < 5; ix++) {
                            var sample = plane[rows[iy] + cols[ix]];
                            window[iy * 5 + ix] = sample;
                            min = Math.min(min, sample);
                            max = Math.max(max, sample);
                        }
                    }
                    for (var dy = 0; dy < k; dy++) {
                        var matY = Math.min(dy, k - dy - 1);
                        var flipV = dy >= matN;
                        var outRow = (refY * k + dy) * outWidth + refX * k;
                        for (var dx = 0; dx < k; dx++) {
                            var matX = Math.min(dx, k - dx - 1);
                            var flipH = dx >= matN;
                            var kernel = quarter[matY * matN + matX];
                            var sum = 0.0f;
                            for (var iy = 0; iy < 5; iy++) {
                                var kyy = flipV ? 4 - iy : iy;
                                for (var ix = 0; ix < 5; ix++) {
                                    var kxx = flipH ? 4 - ix : ix;
                                    sum += kernel[kyy * 5 + kxx] * window[iy * 5 + ix];
                                }
                            }
                            out[outRow + dx] = Float.isFinite(min) ? Math.clamp(sum, min, max) : Float.NaN;
                        }
                    }
                }
            }
        });
        return out;
    }

    /**
     * Upsamples a JPEG-style subsampled plane to full resolution with the 1:3 interpolation.
     *
     * @param plane the plane, at least {@code ceil(width / 2^hshift)} by
     * {@code ceil(height / 2^vshift)} valid samples
     * @param stride the row stride of {@code plane}
     * @param hshift the horizontal subsampling shift, 0 or 1
     * @param vshift the vertical subsampling shift, 0 or 1
     * @param width the full-resolution width
     * @param height the full-resolution height
     * @return the full-resolution plane
     */
    public static float[] upsampleChroma(float[] plane, int stride, int hshift, int vshift, int width, int height) {
        var srcWidth = (width + (1 << hshift) - 1) >> hshift;
        var srcHeight = (height + (1 << vshift) - 1) >> vshift;
        var horizontal = new float[width * srcHeight];
        for (var y = 0; y < srcHeight; y++) {
            var in = y * stride;
            var out = y * width;
            if (hshift == 0) {
                System.arraycopy(plane, in, horizontal, out, width);
                continue;
            }
            for (var x = 0; x < srcWidth; x++) {
                var prev = plane[in + Math.max(x - 1, 0)];
                var cur = plane[in + x];
                var next = plane[in + Math.min(x + 1, srcWidth - 1)];
                var a = 0.25f * prev + 0.75f * cur;
                var b = 0.75f * cur + 0.25f * next;
                horizontal[out + 2 * x] = a;
                if (2 * x + 1 < width) {
                    horizontal[out + 2 * x + 1] = b;
                }
            }
        }
        if (vshift == 0) {
            return horizontal;
        }
        var result = new float[width * height];
        for (var y = 0; y < srcHeight; y++) {
            var prev = Math.max(y - 1, 0) * width;
            var cur = y * width;
            var next = Math.min(y + 1, srcHeight - 1) * width;
            for (var x = 0; x < width; x++) {
                var top = 0.25f * horizontal[prev + x] + 0.75f * horizontal[cur + x];
                var bottom = 0.75f * horizontal[cur + x] + 0.25f * horizontal[next + x];
                result[(2 * y) * width + x] = top;
                if (2 * y + 1 < height) {
                    result[(2 * y + 1) * width + x] = bottom;
                }
            }
        }
        return result;
    }
}
