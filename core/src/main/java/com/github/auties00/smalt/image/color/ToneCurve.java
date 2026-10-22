/* Ports Little CMS 2.17 (cmsgamma.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Maps one channel through a one-dimensional transfer function: an ICC parametric curve, a
 * table of 16-bit samples, or a segmented curve of a {@code multiProcessElementType} curve set.
 *
 * <p>Parametric curves use the Little CMS numbering: types {@code 1} to {@code 5} are the ICC
 * {@code parametricCurveType} functions {@code 0} to {@code 4}, and a negative type is the
 * inverse of the positive one:
 * <ul>
 *   <li>{@code 1}: {@code Y = X^g};</li>
 *   <li>{@code 2}: {@code Y = (aX + b)^g} for {@code X >= -b/a}, else {@code 0};</li>
 *   <li>{@code 3}: {@code Y = (aX + b)^g + c} for {@code X >= -b/a}, else {@code c};</li>
 *   <li>{@code 4}: {@code Y = (aX + b)^g} for {@code X >= d}, else {@code cX};</li>
 *   <li>{@code 5}: {@code Y = (aX + b)^g + e} for {@code X >= d}, else {@code cX + f}.</li>
 * </ul>
 * A tabulated curve interpolates linearly between its samples, which span {@code 0..1} evenly.
 *
 * <p>A segmented curve ({@code segmentedCurveType}, ICC.1:2022 section 11.2.2) is evaluated in floating point
 * over the whole real line, split at its breakpoints: segment {@code i} covers {@code x0 < x <= x1}, from
 * {@link #MINUS_INFINITY} to {@link #PLUS_INFINITY}, and is either a formula ({@link Segment#type()} {@code 6} to
 * {@code 8}, the ICC formula types {@code 0} to {@code 2}) or a sampled curve interpolated linearly. Its 16-bit
 * operations (inversion, linearity and slope checks) use 4096 samples of it.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17 ({@code cmsgamma.c}): parametric curves are evaluated in double
 * precision and returned as {@code float}, tabulated curves quantize their input to 16 bits and interpolate in 16.16
 * fixed point, and a tabulated curve is inverted into a 4096-sample table by searching the interval that holds each
 * output, exactly as {@code cmsReverseToneCurveEx} does. Segmented curves follow {@code EvalSegmentedFn} and
 * {@code cmsBuildSegmentedToneCurve}, including the 4096 samples taken while the implicit first point of every
 * sampled segment is still zero, as {@code ReadSegmentedCurve} ({@code cmstypes.c}) leaves them.
 */
final class ToneCurve {
    /**
     * The number of samples of an inverted tabulated curve.
     */
    private static final int REVERSED_SAMPLES = 4096;

    /**
     * The number of parameters of each parametric type, indexed by {@code |type| - 1}.
     */
    private static final int[] PARAMETERS = {1, 3, 4, 5, 7};

    /**
     * The number of samples of the 16-bit table of a segmented curve.
     */
    private static final int SEGMENTED_SAMPLES = 4096;

    /**
     * The start of the first segment of a segmented curve, and the value of a segmented curve outside its segments.
     */
    static final float MINUS_INFINITY = -1e22f;

    /**
     * The end of the last segment of a segmented curve, and the value of a segmented curve that overflows.
     */
    static final float PLUS_INFINITY = 1e22f;

    /**
     * The number of parameters of the segment formulas {@code 6} to {@code 8}, indexed by {@code type - 6}.
     */
    static final int[] SEGMENT_PARAMETERS = {4, 5, 5};

    /**
     * The identity curve.
     */
    static final ToneCurve IDENTITY = parametric(1, new double[]{1.0});

    /**
     * Describes one segment of a segmented curve, covering {@code x0 < x <= x1}.
     *
     * @param x0 the start of the domain, exclusive
     * @param x1 the end of the domain, inclusive
     * @param type {@code 0} for a sampled segment, or {@code 6}, {@code 7} or {@code 8} for the formulas
     * {@code Y = (aX + b)^g + c}, {@code Y = a log10(b X^g + c) + d} and {@code Y = a b^(cX + d) + e}
     * @param parameters the formula parameters in file order ({@code g, a, b, c}, {@code g, a, b, c, d} or
     * {@code a, b, c, d, e}), or {@code null} for a sampled segment
     * @param samples the samples of a sampled segment, from {@code x0} to {@code x1} evenly; the first is the value
     * of the previous segment at {@code x0}, filled in by {@link #segmented}; {@code null} for a formula
     */
    record Segment(float x0, float x1, int type, double[] parameters, float[] samples) {
    }

    /**
     * Finds the segment of a segmented curve that applies to an input in logarithmic time.
     *
     * <p>The distinct breakpoints of all segments, sorted, split the line into elementary intervals
     * {@code bounds[k] < x <= bounds[k + 1]}; every segment domain is a union of them, so the last segment whose
     * domain holds an input is the one recorded for its interval.
     *
     * @param bounds the distinct breakpoints of the segments in ascending order, NaN excluded
     * @param owners the index of the segment applying to each elementary interval, or {@code -1} for none;
     * {@code bounds.length - 1} entries
     */
    private record SegmentIndex(float[] bounds, int[] owners) {
        /**
         * Builds the index of segments, sweeping the elementary intervals in ascending order with a heap, ordered
         * by segment index, of the segments that start at or before the current interval.
         *
         * @param segments the segments, at least one
         * @return the index
         */
        static SegmentIndex of(Segment[] segments) {
            var points = new float[segments.length * 2];
            var count = 0;
            for (var segment : segments) {
                if (!Float.isNaN(segment.x0()) && !Float.isNaN(segment.x1())) {
                    points[count++] = segment.x0();
                    points[count++] = segment.x1();
                }
            }
            Arrays.sort(points, 0, count);
            var distinct = 0;
            for (var i = 0; i < count; i++) {
                if (distinct == 0 || points[i] != points[distinct - 1]) {
                    points[distinct++] = points[i];
                }
            }
            var bounds = Arrays.copyOf(points, distinct);
            var owners = new int[Math.max(0, distinct - 1)];
            var order = new Integer[segments.length];
            for (var i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Float.compare(segments[a].x0(), segments[b].x0()));
            var active = new PriorityQueue<Integer>(Comparator.reverseOrder());
            var next = 0;
            for (var k = 0; k < owners.length; k++) {
                while (next < order.length && segments[order[next]].x0() <= bounds[k]) {
                    if (!Float.isNaN(segments[order[next]].x1())) {
                        active.add(order[next]);
                    }
                    next++;
                }
                while (!active.isEmpty() && segments[active.peek()].x1() < bounds[k + 1]) {
                    active.poll();
                }
                owners[k] = active.isEmpty() ? -1 : active.peek();
            }
            return new SegmentIndex(bounds, owners);
        }

        /**
         * Returns the segment that applies to an input.
         *
         * @param r the input
         * @return the index of the last segment whose domain holds {@code r}, or {@code -1} when none does, NaN
         * included
         */
        int find(double r) {
            var low = 0;
            var high = bounds.length;
            while (low < high) {
                var middle = (low + high) >>> 1;
                if (r <= bounds[middle]) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }
            return low == 0 || low == bounds.length ? -1 : owners[low - 1];
        }
    }

    /**
     * The parametric type, or {@code 0} for a tabulated curve.
     */
    private final int type;

    /**
     * The parameters of a parametric curve, or {@code null}.
     */
    private final double[] parameters;

    /**
     * The 16-bit samples of a tabulated curve, or of a parametric or segmented curve sampled at 4096 points
     * (2 for the identity), which decide whether the curve descends.
     */
    private final int[] table;

    /**
     * The segments of a segmented curve, or {@code null}.
     */
    private final Segment[] segments;

    /**
     * The index that finds the segment of a segmented curve applying to an input, or {@code null}.
     */
    private final SegmentIndex index;

    /**
     * Constructs a curve.
     *
     * @param type the parametric type, or {@code 0}
     * @param parameters the parameters, or {@code null}
     * @param table the 16-bit samples
     * @param segments the segments of a segmented curve, or {@code null}
     * @param index the index of the segments, or {@code null}
     */
    private ToneCurve(int type, double[] parameters, int[] table, Segment[] segments, SegmentIndex index) {
        this.type = type;
        this.parameters = parameters;
        this.table = table;
        this.segments = segments;
        this.index = index;
    }

    /**
     * Creates a parametric curve.
     *
     * @param type the Little CMS type, {@code 1} to {@code 5} or {@code -1} to {@code -5}
     * @param parameters the parameters, at least as many as the type needs
     * @return the curve
     * @throws IllegalArgumentException if the type is unknown or parameters are missing
     */
    static ToneCurve parametric(int type, double[] parameters) {
        var absolute = Math.abs(type);
        if (absolute < 1 || absolute > 5 || parameters.length < PARAMETERS[absolute - 1]) {
            throw new IllegalArgumentException("invalid parametric curve type " + type);
        }
        var copy = Arrays.copyOf(parameters, 7);
        var samples = type == 1 && Math.abs(copy[0] - 1.0) < 0.001 ? 2 : 4096;
        var table = new int[samples];
        for (var i = 0; i < samples; i++) {
            table[i] = ColorMath.saturateWord(evaluate(type, copy, (double) i / (samples - 1)) * 65535.0);
        }
        return new ToneCurve(type, copy, table, null, null);
    }

    /**
     * Creates a gamma curve, {@code Y = X^gamma}.
     *
     * @param gamma the exponent
     * @return the curve
     */
    static ToneCurve gamma(double gamma) {
        return parametric(1, new double[]{gamma});
    }

    /**
     * Creates a tabulated curve.
     *
     * @param samples the 16-bit samples, at least two
     * @return the curve
     * @throws IllegalArgumentException if fewer than two samples are given
     */
    static ToneCurve tabulated(int[] samples) {
        if (samples.length < 2) {
            throw new IllegalArgumentException("a tabulated curve needs at least two samples");
        }
        return new ToneCurve(0, null, samples.clone(), null, null);
    }

    /**
     * Creates a segmented curve.
     *
     * <p>The first sample of every sampled segment is replaced by the value of the curve at the start of that
     * segment, the end of the previous one.
     *
     * @param segments the segments with ascending domains, at least one, the first starting at
     * {@link #MINUS_INFINITY} and the last ending at {@link #PLUS_INFINITY}; copied
     * @return the curve
     * @throws IllegalArgumentException if a segment type is unknown, a formula lacks parameters or a sampled
     * segment has no samples
     */
    static ToneCurve segmented(Segment[] segments) {
        if (segments.length == 0) {
            throw new IllegalArgumentException("a segmented curve needs a segment");
        }
        var copy = new Segment[segments.length];
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (segment.type() == 0) {
                if (segment.samples() == null || segment.samples().length == 0) {
                    throw new IllegalArgumentException("sampled segment without samples");
                }
                var samples = segment.samples().clone();
                samples[0] = 0;
                copy[i] = new Segment(segment.x0(), segment.x1(), 0, null, samples);
            } else {
                if (segment.type() < 6 || segment.type() > 8 || segment.parameters() == null
                        || segment.parameters().length < SEGMENT_PARAMETERS[segment.type() - 6]) {
                    throw new IllegalArgumentException("invalid curve segment of type " + segment.type());
                }
                copy[i] = new Segment(segment.x0(), segment.x1(), segment.type(), segment.parameters().clone(), null);
            }
        }
        var index = SegmentIndex.of(copy);
        var table = new int[SEGMENTED_SAMPLES];
        for (var i = 0; i < SEGMENTED_SAMPLES; i++) {
            var value = evaluateSegments(copy, index, (double) i / (SEGMENTED_SAMPLES - 1));
            table[i] = ColorMath.saturateWord(value * 65535.0);
        }
        for (var segment : copy) {
            if (segment.type() == 0) {
                segment.samples()[0] = (float) evaluateSegments(copy, index, segment.x0());
            }
        }
        return new ToneCurve(0, null, table, copy, index);
    }

    /**
     * Evaluates segments ({@code EvalSegmentedFn}): the last segment whose domain holds the input applies.
     *
     * @param segments the segments of the curve
     * @param index the index of the segments
     * @param r the input
     * @return the output; {@link #PLUS_INFINITY} for an infinite result of either sign, {@link #MINUS_INFINITY}
     * when no segment covers the input
     */
    private static double evaluateSegments(Segment[] segments, SegmentIndex index, double r) {
        var owner = index.find(r);
        if (owner < 0) {
            return MINUS_INFINITY;
        }
        var segment = segments[owner];
        double out;
        if (segment.type() == 0) {
            var r1 = (float) (r - segment.x0()) / (segment.x1() - segment.x0());
            out = interpolate(segment.samples(), r1);
        } else {
            out = formula(segment.type(), segment.parameters(), r);
        }
        return Double.isInfinite(out) ? PLUS_INFINITY : out;
    }

    /**
     * Interpolates evenly spaced samples linearly in single precision ({@code LinLerp1Dfloat}).
     *
     * @param samples the samples over {@code 0..1}
     * @param value the input, clamped to {@code 0..1}
     * @return the output
     */
    private static float interpolate(float[] samples, float value) {
        var domain = samples.length - 1;
        var clamped = clamp(value);
        if (clamped == 1.0f || domain == 0) {
            return samples[domain];
        }
        clamped *= domain;
        var cell0 = (int) Math.floor(clamped);
        var cell1 = (int) Math.ceil(clamped);
        var rest = clamped - cell0;
        var y0 = samples[cell0];
        var y1 = samples[cell1];
        return y0 + (y1 - y0) * rest;
    }

    /**
     * Clamps a value to {@code 0..1}, mapping NaN and values below {@code 1e-9} to zero ({@code fclamp}).
     *
     * @param value the value to clamp
     * @return the clamped value
     */
    static float clamp(float value) {
        return value < 1.0e-9f || Float.isNaN(value) ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    /**
     * Evaluates a segment formula.
     *
     * @param type the formula, {@code 6} to {@code 8}
     * @param p the parameters in file order
     * @param r the input
     * @return the output
     */
    private static double formula(int type, double[] p, double r) {
        return switch (type) {
            case 6 -> {
                var e = p[1] * r + p[2];
                if (p[0] == 1.0) {
                    yield e + p[3];
                }
                yield e < 0 ? p[3] : StrictMath.pow(e, p[0]) + p[3];
            }
            case 7 -> {
                var e = p[2] * StrictMath.pow(r, p[0]) + p[3];
                yield e <= 0 ? p[4] : p[1] * StrictMath.log10(e) + p[4];
            }
            default -> p[0] * StrictMath.pow(p[1], p[2] * r + p[3]) + p[4];
        };
    }

    /**
     * Returns the parametric type.
     *
     * @return the Little CMS type, or {@code 0} for a tabulated or segmented curve
     */
    int type() {
        return type;
    }

    /**
     * Returns a copy of the parameters of a parametric curve.
     *
     * @return the seven parameters, unused ones zero, or {@code null} for a tabulated curve
     */
    double[] parameters() {
        return parameters == null ? null : parameters.clone();
    }

    /**
     * Returns a copy of the 16-bit samples.
     *
     * @return the samples
     */
    int[] samples() {
        return table.clone();
    }

    /**
     * Returns whether the curve is the identity.
     *
     * @return {@code true} if every input maps to itself
     */
    boolean isIdentity() {
        if (type == 1) {
            return Math.abs(parameters[0] - 1.0) < 0.001;
        }
        if (type != 0) {
            return false;
        }
        for (var i = 0; i < table.length; i++) {
            var expected = ColorMath.saturateWord((double) i * 65535.0 / (table.length - 1));
            if (Math.abs(table[i] - expected) > 0x0F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates the curve.
     *
     * @param value the input, nominally {@code 0..1}
     * @return the output
     */
    float evaluate(float value) {
        if (segments != null) {
            return (float) evaluateSegments(segments, index, value);
        }
        if (type == 0) {
            return (float) (evaluate16(ColorMath.saturateWord(value * 65535.0)) / 65535.0);
        }
        var result = evaluate(type, parameters, value);
        if (Double.isInfinite(result)) {
            return result > 0 ? 1e22f : -1e22f;
        }
        return (float) result;
    }

    /**
     * Evaluates the curve on a 16-bit sample.
     *
     * @param value the input sample, {@code 0..65535}
     * @return the output sample
     */
    int evaluate16(int value) {
        if (type != 0) {
            return ColorMath.saturateWord(evaluate((float) (value / 65535.0)) * 65535.0);
        }
        var domain = table.length - 1;
        if (value == 0xFFFF) {
            return table[domain];
        }
        var fixed = toFixedDomain(domain * value);
        var cell = fixed >> 16;
        var rest = fixed & 0xFFFF;
        return linear(rest, table[cell], table[cell + 1]);
    }

    /**
     * Returns whether the curve descends from its first to its last sample.
     *
     * @return {@code true} for a descending curve
     */
    boolean isDescending() {
        return table[0] > table[table.length - 1];
    }

    /**
     * Returns the inverse of this curve.
     *
     * <p>A parametric curve inverts analytically. A tabulated or segmented curve inverts its samples into 4096:
     * each output is found in the last interval (scanning from the top for an ascending curve)
     * whose samples bracket it, and interpolated linearly inside it.
     *
     * @return the inverse curve
     */
    ToneCurve reverse() {
        if (type > 0) {
            return parametric(-type, parameters);
        }
        var samples = new int[REVERSED_SAMPLES];
        var ascending = !isDescending();
        var count = table.length;
        double a = 0, b = 0;
        for (var i = 0; i < REVERSED_SAMPLES; i++) {
            var y = (double) i * 65535.0 / (REVERSED_SAMPLES - 1);
            var j = interval(y);
            if (j >= 0) {
                double x1 = table[j];
                double x2 = table[j + 1];
                var y1 = (j * 65535.0) / (count - 1);
                var y2 = ((j + 1) * 65535.0) / (count - 1);
                if (x1 == x2) {
                    samples[i] = ColorMath.saturateWord(ascending ? y2 : y1);
                    continue;
                }
                a = (y2 - y1) / (x2 - x1);
                b = y2 - a * x2;
            }
            samples[i] = ColorMath.saturateWord(a * y + b);
        }
        return new ToneCurve(0, null, samples, null, null);
    }

    /**
     * Finds the interval of the table that holds an output value.
     *
     * @param value the output value in 16-bit units
     * @return the index of the first sample of the interval, or {@code -1} when none holds it
     */
    private int interval(double value) {
        var domain = table.length - 1;
        if (table[0] < table[domain]) {
            for (var i = domain - 1; i >= 0; --i) {
                if (holds(value, table[i], table[i + 1])) {
                    return i;
                }
            }
        } else {
            for (var i = 0; i < domain; i++) {
                if (holds(value, table[i], table[i + 1])) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Returns whether a value lies between two samples, in either order.
     *
     * @param value the value to test
     * @param y0 the first sample
     * @param y1 the second sample
     * @return {@code true} if the value lies in the closed interval
     */
    private static boolean holds(double value, int y0, int y1) {
        return y0 <= y1 ? value >= y0 && value <= y1 : value >= y1 && value <= y0;
    }

    /**
     * Interpolates between two 16-bit values in 16.16 fixed point.
     *
     * @param a the fraction, {@code 0..65535}
     * @param l the low value
     * @param h the high value
     * @return the interpolated value
     */
    static int linear(int a, int l, int h) {
        var dif = (int) (((long) (h - l) * a + 0x8000) & 0xFFFFFFFFL);
        dif = (dif >>> 16) + l;
        return dif & 0xFFFF;
    }

    /**
     * Converts a product of a 16-bit sample and a grid domain to 16.16 fixed point.
     *
     * @param a the product
     * @return the fixed-point value
     */
    static int toFixedDomain(int a) {
        return a + ((a + 0x7FFF) / 0xFFFF);
    }

    /**
     * Evaluates a parametric function.
     *
     * @param type the Little CMS type
     * @param parameters the parameters of that type, in Little CMS order
     * @param r the input
     * @return the output
     */
    static double evaluate(int type, double[] parameters, double r) {
        var p = parameters;
        double e;
        return switch (type) {
            case 1 -> r < 0 ? (Math.abs(p[0] - 1.0) < ColorMath.DETERMINANT_TOLERANCE ? r : 0) : StrictMath.pow(r, p[0]);
            case -1 -> {
                if (r < 0) {
                    yield Math.abs(p[0] - 1.0) < ColorMath.DETERMINANT_TOLERANCE ? r : 0;
                }
                yield Math.abs(p[0]) < ColorMath.DETERMINANT_TOLERANCE ? 1e22 : StrictMath.pow(r, 1 / p[0]);
            }
            case 2 -> {
                if (Math.abs(p[1]) < ColorMath.DETERMINANT_TOLERANCE) {
                    yield 0;
                }
                var disc = -p[2] / p[1];
                if (r >= disc) {
                    e = p[1] * r + p[2];
                    yield e > 0 ? StrictMath.pow(e, p[0]) : 0;
                }
                yield 0;
            }
            case -2 -> {
                if (Math.abs(p[0]) < ColorMath.DETERMINANT_TOLERANCE || Math.abs(p[1]) < ColorMath.DETERMINANT_TOLERANCE) {
                    yield 0;
                }
                var value = r < 0 ? 0 : (StrictMath.pow(r, 1.0 / p[0]) - p[2]) / p[1];
                yield Math.max(value, 0);
            }
            case 3 -> {
                if (Math.abs(p[1]) < ColorMath.DETERMINANT_TOLERANCE) {
                    yield 0;
                }
                var disc = Math.max(-p[2] / p[1], 0);
                if (r >= disc) {
                    e = p[1] * r + p[2];
                    yield e > 0 ? StrictMath.pow(e, p[0]) + p[3] : 0;
                }
                yield p[3];
            }
            case -3 -> {
                if (Math.abs(p[0]) < ColorMath.DETERMINANT_TOLERANCE || Math.abs(p[1]) < ColorMath.DETERMINANT_TOLERANCE) {
                    yield 0;
                }
                if (r >= p[3]) {
                    e = r - p[3];
                    yield e > 0 ? (StrictMath.pow(e, 1 / p[0]) - p[2]) / p[1] : 0;
                }
                yield -p[2] / p[1];
            }
            case 4 -> {
                if (r >= p[4]) {
                    e = p[1] * r + p[2];
                    yield e > 0 ? StrictMath.pow(e, p[0]) : 0;
                }
                yield r * p[3];
            }
            case -4 -> {
                e = p[1] * p[4] + p[2];
                var disc = e < 0 ? 0 : StrictMath.pow(e, p[0]);
                if (r >= disc) {
                    if (Math.abs(p[0]) < ColorMath.DETERMINANT_TOLERANCE || Math.abs(p[1]) < ColorMath.DETERMINANT_TOLERANCE) {
                        yield 0;
                    }
                    yield (StrictMath.pow(r, 1.0 / p[0]) - p[2]) / p[1];
                }
                yield Math.abs(p[3]) < ColorMath.DETERMINANT_TOLERANCE ? 0 : r / p[3];
            }
            case 5 -> {
                if (r >= p[4]) {
                    e = p[1] * r + p[2];
                    yield e > 0 ? StrictMath.pow(e, p[0]) + p[5] : p[5];
                }
                yield r * p[3] + p[6];
            }
            case -5 -> {
                var disc = p[3] * p[4] + p[6];
                if (r >= disc) {
                    e = r - p[5];
                    if (e < 0) {
                        yield 0;
                    }
                    if (Math.abs(p[0]) < ColorMath.DETERMINANT_TOLERANCE || Math.abs(p[1]) < ColorMath.DETERMINANT_TOLERANCE) {
                        yield 0;
                    }
                    yield (StrictMath.pow(e, 1.0 / p[0]) - p[2]) / p[1];
                }
                yield Math.abs(p[3]) < ColorMath.DETERMINANT_TOLERANCE ? 0 : (r - p[6]) / p[3];
            }
            default -> throw new IllegalArgumentException("invalid parametric curve type " + type);
        };
    }
}
