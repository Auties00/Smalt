/* Ports Little CMS 2.17 (cmsgamma.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import java.util.Arrays;

/**
 * Maps one channel through a one-dimensional transfer function: an ICC parametric curve or a
 * table of 16-bit samples.
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
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17 ({@code cmsgamma.c}): parametric curves are evaluated in double
 * precision and returned as {@code float}, tabulated curves quantize their input to 16 bits and interpolate in 16.16
 * fixed point, and a tabulated curve is inverted into a 4096-sample table by searching the interval that holds each
 * output, exactly as {@code cmsReverseToneCurveEx} does.
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
     * The identity curve.
     */
    static final ToneCurve IDENTITY = parametric(1, new double[]{1.0});

    /**
     * The parametric type, or {@code 0} for a tabulated curve.
     */
    private final int type;

    /**
     * The parameters of a parametric curve, or {@code null}.
     */
    private final double[] parameters;

    /**
     * The 16-bit samples of a tabulated curve, or of a parametric curve sampled at 4096 points
     * (2 for the identity), which decide whether the curve descends.
     */
    private final int[] table;

    /**
     * Constructs a curve.
     *
     * @param type the parametric type, or {@code 0}
     * @param parameters the parameters, or {@code null}
     * @param table the 16-bit samples
     */
    private ToneCurve(int type, double[] parameters, int[] table) {
        this.type = type;
        this.parameters = parameters;
        this.table = table;
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
        return new ToneCurve(type, copy, table);
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
        return new ToneCurve(0, null, samples.clone());
    }

    /**
     * Returns the parametric type.
     *
     * @return the Little CMS type, or {@code 0} for a tabulated curve
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
     * <p>A parametric curve inverts analytically. A tabulated curve inverts into 4096 samples:
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
        return new ToneCurve(0, null, samples);
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
