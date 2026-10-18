package com.github.auties00.smalt.image.color;

/**
 * Transforms RGB to RGB through per-channel prelinearization curves and a lookup table.
 *
 * <p>The pipeline is first evaluated on the gray ramp, which gives one curve per channel; each
 * curve is slope-limited at its ends and, when it is monotonic and not degenerate, its inverse is
 * prepended to the pipeline before the pipeline is sampled into a 33-point grid. Pixels then go
 * through the curves and are interpolated tetrahedrally in the grid. Placing the grid points
 * where the transform changes most keeps gamma-encoded transforms accurate with a coarse grid.
 *
 * <p>16-bit pixels go through the same curves at full precision and are interpolated in the same
 * grid.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's
 *           {@code OptimizeByComputingLinearization}, {@code SlopeLimiting},
 *           {@code IsDegenerated}, {@code PrelinOpt8alloc} and {@code PrelinEval8}
 *           ({@code cmsopt.c}), with its white fix-up. Little CMS applies the optimization to
 *           16-bit pixels only on request ({@code cmsFLAGS_CLUT_PRE_LINEARIZATION}); this
 *           implementation always does, because it is more accurate than a lookup table alone.
 */
final class PrelinEngine implements ColorEngine {
    /**
     * The number of samples of the prelinearization curves.
     */
    private static final int POINTS = 4096;

    /**
     * The number of grid points per input.
     */
    private static final int GRID = 33;

    /**
     * The grid samples.
     */
    private final int[] table;

    /**
     * The prelinearization curves.
     */
    private final ToneCurve[] curves;

    /**
     * The interpolator over {@link #table} for 16-bit pixels.
     */
    private final Interpolator interpolator;

    /**
     * The table offset of the lower red node of each 8-bit red sample.
     */
    private final int[] x0 = new int[256];

    /**
     * The table offset of the lower green node of each 8-bit green sample.
     */
    private final int[] y0 = new int[256];

    /**
     * The table offset of the lower blue node of each 8-bit blue sample.
     */
    private final int[] z0 = new int[256];

    /**
     * The red fraction between nodes of each 8-bit red sample.
     */
    private final int[] rx = new int[256];

    /**
     * The green fraction between nodes of each 8-bit green sample.
     */
    private final int[] ry = new int[256];

    /**
     * The blue fraction between nodes of each 8-bit blue sample.
     */
    private final int[] rz = new int[256];

    /**
     * Constructs an engine.
     *
     * @param curves the prelinearization curves
     * @param table  the grid samples
     */
    private PrelinEngine(ToneCurve[] curves, int[] table) {
        this.table = table;
        this.curves = curves;
        this.interpolator = new Interpolator(new int[]{GRID, GRID, GRID}, 3, table, false);
        var domain = GRID - 1;
        for (var i = 0; i < 256; i++) {
            var v = (i << 8) | i;
            var v1 = ToneCurve.toFixedDomain(curves[0].evaluate16(v) * domain);
            var v2 = ToneCurve.toFixedDomain(curves[1].evaluate16(v) * domain);
            var v3 = ToneCurve.toFixedDomain(curves[2].evaluate16(v) * domain);
            x0[i] = GRID * GRID * 3 * (v1 >> 16);
            y0[i] = GRID * 3 * (v2 >> 16);
            z0[i] = 3 * (v3 >> 16);
            rx[i] = v1 & 0xFFFF;
            ry[i] = v2 & 0xFFFF;
            rz[i] = v3 & 0xFFFF;
        }
    }

    /**
     * Builds the engine for an RGB to RGB pipeline, if the prelinearization is suitable.
     *
     * @param pipeline the simplified pipeline
     * @param fixWhite whether the white node is corrected
     * @return the engine, or {@code null} when the curves are not monotonic or are degenerate
     */
    static PrelinEngine of(Pipeline pipeline, boolean fixWhite) {
        var stages = pipeline.stages();
        if (stages.getLast() instanceof Stage.Curves last) {
            for (var curve : last.curves) {
                if (isDegenerate(curve.samples())) {
                    return null;
                }
            }
        }
        var samples = new int[3][POINTS];
        var in = new float[3];
        var out = new float[3];
        for (var i = 0; i < POINTS; i++) {
            var v = (float) ((double) i / (POINTS - 1));
            in[0] = in[1] = in[2] = v;
            pipeline.evaluate(in, out);
            for (var t = 0; t < 3; t++) {
                samples[t][i] = ColorMath.saturateWord(out[t] * 65535.0);
            }
        }
        var curves = new ToneCurve[3];
        var reverse = new ToneCurve[3];
        for (var t = 0; t < 3; t++) {
            limitSlope(samples[t]);
            if (!isMonotonic(samples[t]) || isDegenerate(samples[t])) {
                return null;
            }
            curves[t] = ToneCurve.tabulated(samples[t]);
            reverse[t] = curves[t].reverse();
        }
        var withCurves = new Pipeline(3, 3).append(new Stage.Curves(reverse)).append(pipeline);
        var table = new int[GRID * GRID * GRID * 3];
        var node = new float[3];
        var result = new float[3];
        for (var index = 0; index < GRID * GRID * GRID; index++) {
            var rest = index;
            for (var t = 2; t >= 0; --t) {
                var quantized = ColorMath.saturateWord((rest % GRID) * 65535.0 / (GRID - 1));
                rest /= GRID;
                node[t] = (float) (quantized / 65535.0);
            }
            withCurves.evaluate(node, result);
            for (var o = 0; o < 3; o++) {
                table[index * 3 + o] = ColorMath.saturateWord(result[o] * 65535.0);
            }
        }
        var engine = new PrelinEngine(curves, table);
        if (fixWhite) {
            engine.fixWhite(curves);
        }
        return engine;
    }

    /**
     * Sets the grid node at the prelinearized white to white when the white does not already map
     * to white.
     *
     * @param curves the prelinearization curves
     */
    private void fixWhite(ToneCurve[] curves) {
        var obtained = new int[3];
        evaluate(255, 255, 255, obtained);
        var equal = true;
        for (var c = 0; c < 3; c++) {
            if (Math.abs(0xFFFF - obtained[c]) > 0xF000) {
                return;
            }
            if (obtained[c] != 0xFFFF) {
                equal = false;
            }
        }
        if (equal) {
            return;
        }
        var index = 0;
        var stride = 3;
        for (var t = 2; t >= 0; --t) {
            var position = (double) curves[t].evaluate16(0xFFFF) * (GRID - 1) / 65535.0;
            var floor = (int) Math.floor(position);
            if (position - floor != 0) {
                return;
            }
            index += stride * floor;
            stride *= GRID;
        }
        table[index] = 0xFFFF;
        table[index + 1] = 0xFFFF;
        table[index + 2] = 0xFFFF;
    }

    /**
     * Limits the slope of the first and last 2% of a curve to that of the line to its endpoint.
     *
     * @param samples the curve samples, modified in place
     */
    private static void limitSlope(int[] samples) {
        var entries = samples.length;
        var atBegin = (int) Math.floor(entries * 0.02 + 0.5);
        var atEnd = entries - atBegin - 1;
        int beginValue;
        int endValue;
        if (samples[0] > samples[entries - 1]) {
            beginValue = 0xFFFF;
            endValue = 0;
        } else {
            beginValue = 0;
            endValue = 0xFFFF;
        }
        double value = samples[atBegin];
        var slope = (value - beginValue) / atBegin;
        var beta = value - slope * atBegin;
        for (var i = 0; i < atBegin; i++) {
            samples[i] = ColorMath.saturateWord(i * slope + beta);
        }
        value = samples[atEnd];
        slope = (endValue - value) / atBegin;
        beta = value - slope * atEnd;
        for (var i = atEnd; i < entries; i++) {
            samples[i] = ColorMath.saturateWord(i * slope + beta);
        }
    }

    /**
     * Returns whether a curve is monotonic, allowing a ripple of two units.
     *
     * @param samples the curve samples
     * @return {@code true} if monotonic
     */
    private static boolean isMonotonic(int[] samples) {
        var n = samples.length;
        if (samples[0] > samples[n - 1]) {
            var last = samples[0];
            for (var i = 1; i < n; i++) {
                if (samples[i] - last > 2) {
                    return false;
                }
                last = samples[i];
            }
        } else {
            var last = samples[n - 1];
            for (var i = n - 2; i >= 0; --i) {
                if (samples[i] - last > 2) {
                    return false;
                }
                last = samples[i];
            }
        }
        return true;
    }

    /**
     * Returns whether a curve has wide flat areas at zero or at full scale.
     *
     * @param samples the curve samples
     * @return {@code true} if more than 5% of the samples are zero, or more than 5% are full scale
     */
    private static boolean isDegenerate(int[] samples) {
        var zeros = 0;
        var poles = 0;
        for (var sample : samples) {
            if (sample == 0) {
                zeros++;
            }
            if (sample == 0xFFFF) {
                poles++;
            }
        }
        if (zeros == 1 && poles == 1) {
            return false;
        }
        return zeros > samples.length / 20 || poles > samples.length / 20;
    }

    /**
     * Interpolates one pixel.
     *
     * <p>The tetrahedron is chosen once per pixel by ordering the three fractions; walking its
     * vertices from the base node in the order of decreasing fraction gives each output as
     * {@code c0 + round((v1 - c0) * w1 + (v2 - v1) * w2 + (v3 - v2) * w3)}, which is the sum
     * {@code PrelinEval8} computes for each of its six cases, term for term.
     *
     * @param r      the red sample
     * @param g      the green sample
     * @param b      the blue sample
     * @param output receives the three 16-bit outputs
     */
    private void evaluate(int r, int g, int b, int[] output) {
        var fx = rx[r];
        var fy = ry[g];
        var fz = rz[b];
        var sx = fx == 0 ? 0 : GRID * GRID * 3;
        var sy = fy == 0 ? 0 : GRID * 3;
        var sz = fz == 0 ? 0 : 3;
        int p1;
        int p2;
        int w1;
        int w2;
        int w3;
        if (fx >= fy) {
            if (fy >= fz) {
                p1 = sx;
                p2 = sx + sy;
                w1 = fx;
                w2 = fy;
                w3 = fz;
            } else if (fz >= fx) {
                p1 = sz;
                p2 = sz + sx;
                w1 = fz;
                w2 = fx;
                w3 = fy;
            } else {
                p1 = sx;
                p2 = sx + sz;
                w1 = fx;
                w2 = fz;
                w3 = fy;
            }
        } else if (fx >= fz) {
            p1 = sy;
            p2 = sy + sx;
            w1 = fy;
            w2 = fx;
            w3 = fz;
        } else if (fy >= fz) {
            p1 = sy;
            p2 = sy + sz;
            w1 = fy;
            w2 = fz;
            w3 = fx;
        } else {
            p1 = sz;
            p2 = sz + sy;
            w1 = fz;
            w2 = fy;
            w3 = fx;
        }
        var p3 = sx + sy + sz;
        var base = x0[r] + y0[g] + z0[b];
        var t = table;
        for (var o = 0; o < 3; o++) {
            var at = base + o;
            var c0 = t[at];
            var v1 = t[at + p1];
            var v2 = t[at + p2];
            var v3 = t[at + p3];
            var rest = (v1 - c0) * w1 + (v2 - v1) * w2 + (v3 - v2) * w3 + 0x8001;
            output[o] = (c0 + ((rest + (rest >> 16)) >> 16)) & 0xFFFF;
        }
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var out = new int[3];
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            evaluate(src[s] & 0xFF, src[s + 1] & 0xFF, src[s + 2] & 0xFF, out);
            dst[d] = (byte) ColorMath.wordToByte(out[0]);
            dst[d + 1] = (byte) ColorMath.wordToByte(out[1]);
            dst[d + 2] = (byte) ColorMath.wordToByte(out[2]);
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var in = new int[3];
        var out = new int[3];
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < 3; c++) {
                in[c] = curves[c].evaluate16(src[s + c] & 0xFFFF);
            }
            interpolator.evaluate(in, out);
            for (var c = 0; c < 3; c++) {
                dst[d + c] = (short) out[c];
            }
        }
    }
}
