/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: the spline parser; crates/jxl-render: the spline renderer). Copyright
   Wonwoo Choi and the jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Holds the quantized splines of a frame and renders them (ISO/IEC 18181-1 section G.1.5).
 *
 * <p>A spline is a centripetal Catmull-Rom curve through its control points along which a
 * Gaussian-profiled stroke is added to the X, Y and B channels; its color and width vary along
 * the curve as given by 32 DCT coefficients each.
 *
 * @implNote This implementation keeps the reference decoder's 16 segments per curve span,
 * arc-length resampling, error function approximation and estimated-area limit of profile level
 * 10.
 *
 * @param splines the quantized splines
 * @param quantAdjust the quantization adjustment
 */
public record Splines(List<QuantSpline> splines, int quantAdjust) {
    /**
     * The largest number of splines.
     */
    private static final long MAX_SPLINES = 1 << 24;

    /**
     * The largest total number of control points.
     */
    private static final long MAX_CONTROL_POINTS = 1 << 20;

    /**
     * The dequantization weights of the X, Y, B and sigma coefficients.
     */
    private static final float[] CHANNEL_WEIGHTS = {0.0042f, 0.075f, 0.07f, 0.3333f};

    /**
     * Holds one quantized spline.
     *
     * @param points the control points, as {@code x, y} pairs
     * @param manhattanDistance the sum of the Manhattan lengths of the point deltas
     * @param xybDct the 32 quantized color coefficients of each channel
     * @param sigmaDct the 32 quantized width coefficients
     */
    public record QuantSpline(long[] points, long manhattanDistance, int[][] xybDct, int[] sigmaDct) {
    }

    /**
     * Reads the splines.
     *
     * @param br the bitstream
     * @param frame the frame header
     * @return the splines
     * @throws MediaException.Malformed if the splines are invalid or too many
     */
    public static Splines read(JxlBitReader br, FrameHeader frame) {
        var decoder = EntropyDecoder.read(br, 6);
        decoder.begin(br);
        var numPixels = (long) frame.width * frame.height;
        var count = decoder.readUint(br, 2) & 0xFFFF_FFFFL;
        var maxSplines = Math.min(MAX_SPLINES, numPixels / 4);
        if (count >= maxSplines) {
            throw new MediaException.Malformed("too many splines");
        }
        var numSplines = (int) count + 1;
        var starts = new long[numSplines * 2];
        long px = decoder.readUint(br, 1) & 0xFFFF_FFFFL;
        long py = decoder.readUint(br, 1) & 0xFFFF_FFFFL;
        starts[0] = px;
        starts[1] = py;
        for (var i = 1; i < numSplines; i++) {
            px += unpackSigned(decoder.readUint(br, 1));
            py += unpackSigned(decoder.readUint(br, 1));
            starts[2 * i] = px;
            starts[2 * i + 1] = py;
        }
        var quantAdjust = unpackSigned(decoder.readUint(br, 0));
        var splines = new ArrayList<QuantSpline>(numSplines);
        var accumulated = 0L;
        var maxPoints = Math.min(MAX_CONTROL_POINTS, numPixels / 2);
        for (var i = 0; i < numSplines; i++) {
            var numPoints = decoder.readUint(br, 3) & 0xFFFF_FFFFL;
            accumulated += numPoints;
            if (accumulated > maxPoints) {
                throw new MediaException.Malformed("too many spline control points");
            }
            var points = new long[(int) (numPoints + 1) * 2];
            var x = starts[2 * i];
            var y = starts[2 * i + 1];
            points[0] = x;
            points[1] = y;
            var dx = 0L;
            var dy = 0L;
            var manhattan = 0L;
            for (var p = 1; p <= numPoints; p++) {
                dx += unpackSigned(decoder.readUint(br, 4));
                dy += unpackSigned(decoder.readUint(br, 4));
                manhattan += Math.abs(dx) + Math.abs(dy);
                var nx = x + dx;
                var ny = y + dy;
                if (Math.abs(nx) > 1L << 40 || Math.abs(ny) > 1L << 40) {
                    throw new MediaException.Malformed("spline control point overflow");
                }
                if (nx == x && ny == y) {
                    throw new MediaException.Malformed("two consecutive spline points are equal");
                }
                x = nx;
                y = ny;
                points[2 * p] = x;
                points[2 * p + 1] = y;
            }
            var xyb = new int[3][32];
            for (var c = 0; c < 3; c++) {
                for (var k = 0; k < 32; k++) {
                    xyb[c][k] = unpackSigned(decoder.readUint(br, 5));
                }
            }
            var sigma = new int[32];
            for (var k = 0; k < 32; k++) {
                sigma[k] = unpackSigned(decoder.readUint(br, 5));
            }
            splines.add(new QuantSpline(points, manhattan, xyb, sigma));
        }
        decoder.checkFinalState();
        return new Splines(splines, quantAdjust);
    }

    /**
     * Estimates the area the splines cover, which profile level 10 bounds.
     *
     * @param corrX the X from Y correlation
     * @param corrB the B from Y correlation
     * @return the estimated area
     */
    public long estimateArea(float corrX, float corrB) {
        var cx = (long) Math.ceil(Math.abs(corrX));
        var cb = (long) Math.ceil(Math.abs(corrB));
        var total = 0L;
        for (var spline : splines) {
            var color = new long[3];
            for (var c = 0; c < 3; c++) {
                for (var q : spline.xybDct()[c]) {
                    color[c] += divCeilQa(Math.abs((long) q), quantAdjust);
                }
            }
            color[0] += cx * color[1];
            color[2] += cb * color[1];
            var max = Math.max(color[0], Math.max(color[1], color[2]));
            var logColor = 64 - Long.numberOfLeadingZeros(max);
            var width = 0L;
            for (var q : spline.sigmaDct()) {
                var weight = 1 + divCeilQa(Math.abs((long) q), quantAdjust);
                width += weight * weight * logColor;
            }
            total += width * spline.manhattanDistance();
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /**
     * Applies the quantization adjustment to an absolute coefficient, rounding up.
     *
     * @param dividend the absolute coefficient
     * @param quantAdjust the adjustment
     * @return the adjusted value
     */
    private static long divCeilQa(long dividend, int quantAdjust) {
        if (quantAdjust >= 0) {
            return (8 * dividend + 7 + quantAdjust) / (8 + quantAdjust);
        }
        var abs = -(long) quantAdjust;
        return dividend + (dividend * abs + 7) / 8;
    }

    /**
     * Adds every spline to the color planes of a frame.
     *
     * @param x the X plane
     * @param y the Y plane
     * @param b the B plane
     * @param width the plane width
     * @param height the plane height
     * @param corrX the X from Y correlation
     * @param corrB the B from Y correlation
     */
    public void render(float[] x, float[] y, float[] b, int width, int height, float corrX, float corrB) {
        var planes = new float[][]{x, y, b};
        for (var quant : splines) {
            renderOne(quant, planes, width, height, corrX, corrB);
        }
    }

    /**
     * Adds one spline to the color planes.
     *
     * @param quant the quantized spline
     * @param planes the X, Y and B planes
     * @param width the plane width
     * @param height the plane height
     * @param corrX the X from Y correlation
     * @param corrB the B from Y correlation
     */
    private void renderOne(QuantSpline quant, float[][] planes, int width, int height, float corrX, float corrB) {
        var qa = (float) quantAdjust;
        var inverted = qa >= 0 ? 1.0f / (1.0f + qa / 8.0f) : 1.0f - qa / 8.0f;
        var xyb = new float[3][32];
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < 32; i++) {
                xyb[c][i] = quant.xybDct()[c][i] * CHANNEL_WEIGHTS[c] * inverted;
            }
        }
        for (var i = 0; i < 32; i++) {
            xyb[0][i] += corrX * xyb[1][i];
            xyb[2][i] += corrB * xyb[1][i];
        }
        var sigmaDct = new float[32];
        for (var i = 0; i < 32; i++) {
            sigmaDct[i] = quant.sigmaDct()[i] * CHANNEL_WEIGHTS[3] * inverted;
        }
        var samples = samples(upsampledPoints(quant.points()));
        var count = samples.length / 3;
        var arcLength = count - 2.0f + samples[(count - 1) * 3 + 2];
        for (var i = 0; i < count; i++) {
            var px = samples[i * 3];
            var py = samples[i * 3 + 1];
            var length = samples[i * 3 + 2];
            var fromStart = Math.min(1.0f, i / arcLength);
            var t = 31.0f * fromStart;
            var sigma = continuousIdct(sigmaDct, t);
            var invSigma = 1.0f / sigma;
            var values = new float[]{
                    continuousIdct(xyb[0], t) * length,
                    continuousIdct(xyb[1], t) * length,
                    continuousIdct(xyb[2], t) * length,
            };
            var maxColor = Math.max(0.01f, Math.max(values[0], Math.max(values[1], values[2])));
            var maxDistance = (float) Math.sqrt(2.0f * ((float) StrictMath.log(10.0) * 3.0f + maxColor)) * Math.abs(sigma);
            var xBegin = Math.max(0, (int) Math.floor(px - maxDistance + 0.5f));
            var xEnd = Math.min(width, (int) Math.floor(px + maxDistance + 1.5f));
            var yBegin = Math.max(0, (int) Math.floor(py - maxDistance + 0.5f));
            var yEnd = Math.min(height, (int) Math.floor(py + maxDistance + 1.5f));
            for (var c = 0; c < 3; c++) {
                var plane = planes[c];
                var value = values[c];
                for (var yy = yBegin; yy < yEnd; yy++) {
                    var dy = yy - py;
                    for (var xx = xBegin; xx < xEnd; xx++) {
                        var dx = xx - px;
                        var distance = (float) Math.sqrt(dx * dx + dy * dy);
                        var factor = erf((0.5f * distance + 0.35355338f) * invSigma)
                                - erf((0.5f * distance - 0.35355338f) * invSigma);
                        plane[yy * width + xx] += 0.25f * value * sigma * factor * factor;
                    }
                }
            }
        }
    }

    /**
     * Computes the points of the Catmull-Rom curve through the control points, 16 per span.
     *
     * @param points the control points, as {@code x, y} pairs
     * @return the curve points, as {@code x, y} pairs
     */
    private static float[] upsampledPoints(long[] points) {
        var n = points.length / 2;
        if (n == 1) {
            return new float[]{points[0], points[1]};
        }
        var ext = new float[(n + 2) * 2];
        ext[0] = 2.0f * points[0] - points[2];
        ext[1] = 2.0f * points[1] - points[3];
        for (var i = 0; i < n * 2; i++) {
            ext[2 + i] = points[i];
        }
        ext[(n + 1) * 2] = 2.0f * points[(n - 1) * 2] - points[(n - 2) * 2];
        ext[(n + 1) * 2 + 1] = 2.0f * points[(n - 1) * 2 + 1] - points[(n - 2) * 2 + 1];
        var extCount = n + 2;
        var out = new float[(16 * (extCount - 3) + 1) * 2];
        var o = 0;
        var t = new float[4];
        var ax = new float[3];
        var ay = new float[3];
        var bx = new float[2];
        var by = new float[2];
        for (var i = 0; i < extCount - 3; i++) {
            var p0 = i * 2;
            out[o++] = ext[p0 + 2];
            out[o++] = ext[p0 + 3];
            t[0] = 0;
            for (var k = 1; k < 4; k++) {
                var dx = ext[p0 + 2 * k] - ext[p0 + 2 * (k - 1)];
                var dy = ext[p0 + 2 * k + 1] - ext[p0 + 2 * (k - 1) + 1];
                t[k] = t[k - 1] + (float) StrictMath.pow(dx * dx + dy * dy, 0.25);
            }
            for (var step = 1; step < 16; step++) {
                var knot = t[1] + (step / 16.0f) * (t[2] - t[1]);
                for (var k = 0; k < 3; k++) {
                    var f = (knot - t[k]) / (t[k + 1] - t[k]);
                    ax[k] = ext[p0 + 2 * k] + (ext[p0 + 2 * (k + 1)] - ext[p0 + 2 * k]) * f;
                    ay[k] = ext[p0 + 2 * k + 1] + (ext[p0 + 2 * (k + 1) + 1] - ext[p0 + 2 * k + 1]) * f;
                }
                for (var k = 0; k < 2; k++) {
                    var f = (knot - t[k]) / (t[k + 2] - t[k]);
                    bx[k] = ax[k] + (ax[k + 1] - ax[k]) * f;
                    by[k] = ay[k] + (ay[k + 1] - ay[k]) * f;
                }
                var f = (knot - t[1]) / (t[2] - t[1]);
                out[o++] = bx[0] + (bx[1] - bx[0]) * f;
                out[o++] = by[0] + (by[1] - by[0]) * f;
            }
        }
        out[o++] = points[(n - 1) * 2];
        out[o] = points[(n - 1) * 2 + 1];
        return out;
    }

    /**
     * Resamples a curve at unit arc-length steps.
     *
     * @param curve the curve points, as {@code x, y} pairs
     * @return the samples, as {@code x, y, length} triples
     */
    private static float[] samples(float[] curve) {
        var count = curve.length / 2;
        var out = new FloatList();
        var cx = curve[0];
        var cy = curve[1];
        out.add(cx, cy, 1.0f);
        var next = 0;
        while (next < count) {
            var prevX = cx;
            var prevY = cy;
            var arc = 0.0f;
            while (true) {
                if (next >= count) {
                    out.add(prevX, prevY, arc);
                    break;
                }
                var nx = curve[next * 2];
                var ny = curve[next * 2 + 1];
                var toNext = (float) Math.sqrt((nx - prevX) * (nx - prevX) + (ny - prevY) * (ny - prevY));
                if (arc + toNext >= 1.0f) {
                    var f = (1.0f - arc) / toNext;
                    cx = prevX + (nx - prevX) * f;
                    cy = prevY + (ny - prevY) * f;
                    out.add(cx, cy, 1.0f);
                    break;
                }
                arc += toNext;
                prevX = nx;
                prevY = ny;
                next++;
            }
        }
        return out.toArray();
    }

    /**
     * Evaluates a 32-coefficient DCT at a continuous position.
     *
     * @param dct the coefficients
     * @param t the position, from 0 to 31
     * @return the value
     */
    private static float continuousIdct(float[] dct, float t) {
        var result = dct[0];
        for (var i = 1; i < 32; i++) {
            var theta = i * ((float) Math.PI / 32.0f) * (t + 0.5f);
            result += (float) Math.sqrt(2.0) * dct[i] * (float) StrictMath.cos(theta);
        }
        return result;
    }

    /**
     * Approximates the error function with an absolute error below {@code 7e-4}.
     *
     * @param x the argument
     * @return {@code erf(x)}
     */
    private static float erf(float x) {
        var ax = Math.abs(x);
        var d1 = ax * 7.77394369e-02f + 2.05260015e-04f;
        var d2 = d1 * ax + 2.32120216e-01f;
        var d3 = d2 * ax + 2.77820801e-01f;
        var d4 = d3 * ax + 1.0f;
        var d5 = d4 * d4;
        var inv = 1.0f / d5;
        var result = -inv * inv + 1.0f;
        return x < 0 ? -result : result;
    }

    /**
     * Maps an unsigned value to a signed one ({@code UnpackSigned}).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    private static int unpackSigned(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Accumulates floats.
     */
    private static final class FloatList {
        /**
         * Creates an empty instance.
         */
        FloatList() {
        }

        /**
         * The values.
         */
        private float[] values = new float[48];

        /**
         * The number of values.
         */
        private int size;

        /**
         * Appends three values.
         *
         * @param a the first
         * @param b the second
         * @param c the third
         */
        void add(float a, float b, float c) {
            if (size + 3 > values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = a;
            values[size++] = b;
            values[size++] = c;
        }

        /**
         * Returns the values.
         *
         * @return a copy
         */
        float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
