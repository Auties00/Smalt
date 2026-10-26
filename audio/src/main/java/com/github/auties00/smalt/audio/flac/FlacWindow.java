/* Ports libFLAC 1.4 (src/libFLAC/window.c). Copyright (C) 2000-2009 Josh Coalson, (C) 2011-2025 Xiph.Org Foundation;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.flac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Holds one analysis window of a {@link FlacEncoder}: a window function expanded from a
 * {@link FlacApodization} and its coefficients for the current block size.
 *
 * <p>A partial or punch-out Tukey apodization expands into one window per section; a
 * subdivide-Tukey apodization stays one window, over the whole block, that the analysis also
 * applies to sections of the block.
 *
 * @implNote This implementation follows libFLAC's {@code window.c} (BSD-3-Clause), computing the
 * coefficients in double precision.
 */
final class FlacWindow {
    /**
     * The window function.
     */
    private final FlacApodization.Kind kind;

    /**
     * The tapered fraction or Gaussian deviation.
     */
    private final double parameter;

    /**
     * The start of the section, as a fraction of the block, for partial and punch-out windows.
     */
    private final double start;

    /**
     * The end of the section, as a fraction of the block, for partial and punch-out windows.
     */
    private final double end;

    /**
     * The deepest subdivision of a subdivide-Tukey window, otherwise zero.
     */
    private final int subdivisions;

    /**
     * The coefficients for {@link #length}.
     */
    private double[] coefficients;

    /**
     * The block size the coefficients were computed for, zero before the first.
     */
    private int length;

    /**
     * Creates a window.
     *
     * @param kind the window function
     * @param parameter the tapered fraction or deviation
     * @param start the start of the section
     * @param end the end of the section
     * @param subdivisions the deepest subdivision, or zero
     */
    private FlacWindow(FlacApodization.Kind kind, double parameter, double start, double end, int subdivisions) {
        this.kind = kind;
        this.parameter = parameter;
        this.start = start;
        this.end = end;
        this.subdivisions = subdivisions;
        this.coefficients = new double[0];
    }

    /**
     * Expands apodizations into analysis windows, as libFLAC's
     * {@code FLAC__stream_encoder_set_apodization} does.
     *
     * @param apodizations the window functions, in order
     * @return the windows, in order
     */
    static List<FlacWindow> expand(List<FlacApodization> apodizations) {
        var windows = new ArrayList<FlacWindow>();
        for (var apodization : apodizations) {
            var parts = apodization.parts();
            var taper = apodization.parameter();
            switch (apodization.kind()) {
                case PARTIAL_TUKEY, PUNCHOUT_TUKEY -> {
                    if (parts <= 1) {
                        windows.add(new FlacWindow(FlacApodization.Kind.TUKEY, taper, 0, 1, 0));
                        continue;
                    }
                    var overlapUnits = 1.0 / (1.0 - apodization.overlap()) - 1.0;
                    for (var m = 0; m < parts; m++) {
                        windows.add(new FlacWindow(apodization.kind(), taper, m / (parts + overlapUnits),
                                (m + 1 + overlapUnits) / (parts + overlapUnits), 0));
                    }
                }
                case SUBDIVIDE_TUKEY -> windows.add(new FlacWindow(FlacApodization.Kind.TUKEY, taper / parts, 0, 1, parts));
                default -> windows.add(new FlacWindow(apodization.kind(), taper, 0, 1, 0));
            }
        }
        return windows;
    }

    /**
     * Returns the deepest subdivision of a subdivide-Tukey window.
     *
     * @return the number of sections of the deepest subdivision, or zero for other windows
     */
    int subdivisions() {
        return subdivisions;
    }

    /**
     * Returns the coefficients for a block size, computing them on first use.
     *
     * @param size the block size, at least one
     * @return the coefficients, at least {@code size} long
     */
    double[] coefficients(int size) {
        if (size != length) {
            if (coefficients.length < size) {
                coefficients = new double[size];
            }
            compute(coefficients, size);
            length = size;
        }
        return coefficients;
    }

    /**
     * Computes the coefficients.
     *
     * @param w the destination
     * @param l the window length
     */
    private void compute(double[] w, int l) {
        var n1 = l - 1;
        switch (kind) {
            case BARTLETT -> {
                if (l == 1) {
                    w[0] = 1;
                    return;
                }
                var half = (l & 1) != 0 ? n1 / 2 : l / 2 - 1;
                for (var n = 0; n <= n1; n++) {
                    w[n] = n <= half ? 2.0 * n / n1 : 2.0 - 2.0 * n / n1;
                }
            }
            case BARTLETT_HANN -> {
                for (var n = 0; n < l; n++) {
                    var x = n1 == 0 ? 0 : (double) n / n1;
                    w[n] = 0.62 - 0.48 * Math.abs(x - 0.5) - 0.38 * StrictMath.cos(2 * Math.PI * x);
                }
            }
            case BLACKMAN -> cosineSum(w, l, 0.42, -0.5, 0.08, 0, 0);
            case BLACKMAN_HARRIS_4TERM_92DB -> cosineSum(w, l, 0.35875, -0.48829, 0.14128, -0.01168, 0);
            case CONNES -> {
                var n2 = n1 / 2.0;
                for (var n = 0; n <= n1; n++) {
                    var k = n2 == 0 ? 0 : (n - n2) / n2;
                    k = 1.0 - k * k;
                    w[n] = k * k;
                }
            }
            case FLATTOP -> cosineSum(w, l, 0.21557895, -0.41663158, 0.277263158, -0.083578947, 0.006947368);
            case GAUSS -> {
                var n2 = n1 / 2.0;
                for (var n = 0; n <= n1; n++) {
                    var k = n2 == 0 ? 0 : (n - n2) / (parameter * n2);
                    w[n] = StrictMath.exp(-0.5 * k * k);
                }
            }
            case HAMMING -> cosineSum(w, l, 0.54, -0.46, 0, 0, 0);
            case HANN -> cosineSum(w, l, 0.5, -0.5, 0, 0, 0);
            case KAISER_BESSEL -> cosineSum(w, l, 0.402, -0.498, 0.098, -0.001, 0);
            case NUTTALL -> cosineSum(w, l, 0.3635819, -0.4891775, 0.1365995, -0.0106411, 0);
            case RECTANGLE -> Arrays.fill(w, 0, l, 1.0);
            case TRIANGLE -> {
                for (var n = 1; n <= l; n++) {
                    w[n - 1] = n <= (l + 1) / 2 ? 2.0 * n / (l + 1.0) : 2.0 * (l - n + 1) / (l + 1.0);
                }
            }
            case TUKEY -> tukey(w, l, parameter);
            case PARTIAL_TUKEY -> partialTukey(w, l);
            case PUNCHOUT_TUKEY -> punchoutTukey(w, l);
            case WELCH -> {
                var n2 = n1 / 2.0;
                for (var n = 0; n <= n1; n++) {
                    var k = n2 == 0 ? 0 : (n - n2) / n2;
                    w[n] = 1.0 - k * k;
                }
            }
            case SUBDIVIDE_TUKEY -> throw new IllegalStateException("subdivide windows expand to Tukey windows");
        }
    }

    /**
     * Computes a generalized cosine window {@code a0 + a1 cos(2 pi x) + a2 cos(4 pi x) + ...}.
     *
     * @param w the destination
     * @param l the window length
     * @param a0 the constant term
     * @param a1 the first cosine coefficient
     * @param a2 the second cosine coefficient
     * @param a3 the third cosine coefficient
     * @param a4 the fourth cosine coefficient
     */
    private static void cosineSum(double[] w, int l, double a0, double a1, double a2, double a3, double a4) {
        var n1 = l - 1;
        for (var n = 0; n < l; n++) {
            var x = n1 == 0 ? 0 : 2 * Math.PI * n / n1;
            w[n] = a0 + a1 * StrictMath.cos(x) + a2 * StrictMath.cos(2 * x) + a3 * StrictMath.cos(3 * x) + a4 * StrictMath.cos(4 * x);
        }
    }

    /**
     * Computes a Tukey window.
     *
     * @param w the destination
     * @param l the window length
     * @param p the tapered fraction
     */
    private static void tukey(double[] w, int l, double p) {
        if (p >= 1.0) {
            cosineSum(w, l, 0.5, -0.5, 0, 0, 0);
            return;
        }
        Arrays.fill(w, 0, l, 1.0);
        var np = (int) (p / 2.0 * l) - 1;
        if (np > 0) {
            for (var n = 0; n <= np; n++) {
                w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * n / np);
                w[l - np - 1 + n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * (n + np) / np);
            }
        }
    }

    /**
     * Computes a partial Tukey window: a Tukey window over the section, zero elsewhere.
     *
     * @param w the destination
     * @param l the window length
     */
    private void partialTukey(double[] w, int l) {
        var p = Math.min(Math.max(parameter, 0.05), 0.95);
        var startN = (int) (start * l);
        var endN = (int) (end * l);
        var np = (int) (p / 2.0 * (endN - startN));
        var n = 0;
        for (; n < startN && n < l; n++) {
            w[n] = 0;
        }
        for (var i = 1; n < startN + np && n < l; n++, i++) {
            w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * i / np);
        }
        for (; n < endN - np && n < l; n++) {
            w[n] = 1;
        }
        for (var i = np; n < endN && n < l; n++, i--) {
            w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * i / np);
        }
        for (; n < l; n++) {
            w[n] = 0;
        }
    }

    /**
     * Computes a punch-out Tukey window: Tukey tapers on both sides of a zeroed section.
     *
     * @param w the destination
     * @param l the window length
     */
    private void punchoutTukey(double[] w, int l) {
        var p = Math.min(Math.max(parameter, 0.05), 0.95);
        var startN = (int) (start * l);
        var endN = (int) (end * l);
        var ns = (int) (p / 2.0 * startN);
        var ne = (int) (p / 2.0 * (l - endN));
        var n = 0;
        for (var i = 1; n < ns && n < l; n++, i++) {
            w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * i / ns);
        }
        for (; n < startN - ns && n < l; n++) {
            w[n] = 1;
        }
        for (var i = ns; n < startN && n < l; n++, i--) {
            w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * i / ns);
        }
        for (; n < endN && n < l; n++) {
            w[n] = 0;
        }
        for (var i = 1; n < endN + ne && n < l; n++, i++) {
            w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * i / ne);
        }
        for (; n < l - ne && n < l; n++) {
            w[n] = 1;
        }
        for (var i = ne; n < l; n++, i--) {
            w[n] = 0.5 - 0.5 * StrictMath.cos(Math.PI * i / ne);
        }
    }
}
