/* Ports SVT-JPEG-XS 0.9.0 (Idwt.c, Dwt.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * The reversible 5-3 wavelet of JPEG XS (ISO/IEC 21122-1 annex E), applied to a component plane in which every band
 * sits at its {@link JxsLayout} position.
 *
 * <p>The inverse transform runs, from the coarsest level, the horizontal-only levels and then the levels that are
 * both horizontal and vertical, each of these horizontally first; the forward transform runs the same steps in
 * reverse. Both are exact integer lifting with symmetric extension at the plane edges.
 */
public final class JxsWavelet {
    private static final int STRIP = 64;

    private JxsWavelet() {
        throw new AssertionError();
    }

    /**
     * Reconstructs the samples of component {@code c} in place.
     *
     * @param plane the coefficients, replaced by the samples
     * @param layout the picture layout
     * @param c the component
     */
    public static void inverse(int[] plane, JxsLayout layout, int c) {
        var stride = layout.componentWidth[c];
        var x = layout.bandX[c];
        var y = layout.bandY[c];
        var w = layout.bandWidth[c];
        var h = layout.bandHeight[c];
        var horizontal = layout.levelsX[c] - layout.levelsY[c];
        for (var k = 1; k <= horizontal; k++) {
            rows(plane, stride, 0, h[0], x[k] + w[k], x[k], false);
        }
        for (var j = 0; j < layout.levelsY[c]; j++) {
            var i = horizontal + 1 + 3 * j;
            var regionWidth = x[i] + w[i];
            var regionHeight = y[i + 1] + h[i + 1];
            rows(plane, stride, 0, regionHeight, regionWidth, x[i], false);
            columns(plane, stride, regionWidth, regionHeight, y[i + 1], false);
        }
    }

    /**
     * Decomposes the samples of component {@code c} in place into the bands of the layout.
     *
     * @param plane the samples, replaced by the coefficients
     * @param layout the picture layout
     * @param c the component
     */
    public static void forward(int[] plane, JxsLayout layout, int c) {
        var stride = layout.componentWidth[c];
        var x = layout.bandX[c];
        var y = layout.bandY[c];
        var w = layout.bandWidth[c];
        var h = layout.bandHeight[c];
        var horizontal = layout.levelsX[c] - layout.levelsY[c];
        for (var j = layout.levelsY[c] - 1; j >= 0; j--) {
            var i = horizontal + 1 + 3 * j;
            var regionWidth = x[i] + w[i];
            var regionHeight = y[i + 1] + h[i + 1];
            columns(plane, stride, regionWidth, regionHeight, y[i + 1], true);
            rows(plane, stride, 0, regionHeight, regionWidth, x[i], true);
        }
        for (var k = horizontal; k >= 1; k--) {
            rows(plane, stride, 0, h[0], x[k] + w[k], x[k], true);
        }
    }

    private static void rows(int[] plane, int stride, int first, int last, int length, int low, boolean forward) {
        if (length < 2) {
            return;
        }
        var count = last - first;
        var bands = ParallelBands.bands(count, (long) count * length);
        ParallelBands.run(bands, band -> {
            var from = first + ParallelBands.start(band, bands, count);
            var to = first + ParallelBands.start(band + 1, bands, count);
            var scratch = new int[length];
            for (var r = from; r < to; r++) {
                var offset = r * stride;
                System.arraycopy(plane, offset, scratch, 0, length);
                if (forward) {
                    analyze(scratch, 0, 1, plane, offset, offset + low, 1, length);
                } else {
                    synthesize(scratch, 0, low, 1, plane, offset, 1, length);
                }
            }
        });
    }

    private static void columns(int[] plane, int stride, int width, int length, int low, boolean forward) {
        if (length < 2) {
            return;
        }
        var strips = (width + STRIP - 1) / STRIP;
        var bands = ParallelBands.bands(strips, (long) width * length);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, strips);
            var to = ParallelBands.start(band + 1, bands, strips);
            var scratch = new int[length * STRIP];
            for (var s = from; s < to; s++) {
                var x0 = s * STRIP;
                var span = Math.min(STRIP, width - x0);
                for (var r = 0; r < length; r++) {
                    System.arraycopy(plane, r * stride + x0, scratch, r * STRIP, span);
                }
                for (var i = 0; i < span; i++) {
                    if (forward) {
                        analyze(scratch, i, STRIP, plane, x0 + i, low * stride + x0 + i, stride, length);
                    } else {
                        synthesize(scratch, i, low * STRIP + i, STRIP, plane, x0 + i, stride, length);
                    }
                }
            }
        });
    }

    /**
     * Synthesizes {@code n} samples from {@code ceil(n / 2)} low-pass coefficients at {@code lowStart} and the
     * high-pass ones at {@code highStart}, both {@code step} apart in {@code in}, into {@code out}.
     */
    private static void synthesize(int[] in, int lowStart, int highStart, int step, int[] out, int outStart,
                                   int outStep, int n) {
        var nl = (n + 1) >>> 1;
        var nh = n >>> 1;
        for (var i = 0; i < nl; i++) {
            var previous = in[highStart + Math.max(i - 1, 0) * step];
            var current = in[highStart + Math.min(i, nh - 1) * step];
            out[outStart + 2 * i * outStep] = in[lowStart + i * step] - ((previous + current + 2) >> 2);
        }
        for (var i = 0; i < nh; i++) {
            var left = out[outStart + 2 * i * outStep];
            var right = 2 * i + 2 < n ? out[outStart + (2 * i + 2) * outStep] : left;
            out[outStart + (2 * i + 1) * outStep] = in[highStart + i * step] + ((left + right) >> 1);
        }
    }

    /**
     * Decomposes {@code n} samples {@code step} apart in {@code in} into the low-pass coefficients at
     * {@code lowStart} and the high-pass ones at {@code highStart}, {@code outStep} apart in {@code out}.
     */
    private static void analyze(int[] in, int start, int step, int[] out, int lowStart, int highStart, int outStep,
                                int n) {
        var nl = (n + 1) >>> 1;
        var nh = n >>> 1;
        for (var i = 0; i < nh; i++) {
            var left = in[start + 2 * i * step];
            var right = 2 * i + 2 < n ? in[start + (2 * i + 2) * step] : left;
            out[highStart + i * outStep] = in[start + (2 * i + 1) * step] - ((left + right) >> 1);
        }
        for (var i = 0; i < nl; i++) {
            var previous = out[highStart + Math.max(i - 1, 0) * outStep];
            var current = out[highStart + Math.min(i, nh - 1) * outStep];
            out[lowStart + i * outStep] = in[start + 2 * i * step] + ((previous + current + 2) >> 2);
        }
    }
}
