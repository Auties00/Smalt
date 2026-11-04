package com.github.auties00.smalt.image.ops;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Describes a tone curve: a map from input to output intensity, both normalized to
 * {@code [0, 1]}, applied to each color channel by {@link ImageAdjustments#curves}.
 *
 * <p>A curve through control points interpolates them with a monotone cubic (Fritsch and
 * Carlson, 1980): between two points it never overshoots them, so a curve through increasing
 * points is increasing, and it is flat before the first point and after the last. A curve can also
 * wrap any function, whose results are clamped to {@code [0, 1]}. Applied to a picture, the
 * curve is sampled once per possible sample value and each sample is rounded to the nearest
 * value of the picture's depth.
 *
 * <p>Instances are immutable and thread-safe if the wrapped function is.
 */
public final class ToneCurve {
    /**
     * The control point abscissas, increasing, or {@code null} for a function curve.
     */
    private final double[] xs;

    /**
     * The control point ordinates, or {@code null} for a function curve.
     */
    private final double[] ys;

    /**
     * The Hermite tangents at the control points, or {@code null} for a function curve.
     */
    private final double[] tangents;

    /**
     * The wrapped function, or {@code null} for a control point curve.
     */
    private final DoubleUnaryOperator function;

    /**
     * Creates a curve.
     *
     * @param xs       the control point abscissas, or {@code null}
     * @param ys       the control point ordinates, or {@code null}
     * @param function the function, or {@code null}
     */
    private ToneCurve(double[] xs, double[] ys, DoubleUnaryOperator function) {
        this.xs = xs;
        this.ys = ys;
        this.function = function;
        this.tangents = xs == null ? null : tangents(xs, ys);
    }

    /**
     * Returns the curve that leaves every intensity unchanged.
     *
     * @return the identity curve
     */
    public static ToneCurve identity() {
        return through(0, 0, 1, 1);
    }

    /**
     * Returns a curve through control points.
     *
     * @param points the points as {@code x0, y0, x1, y1, ...}: at least two points, abscissas
     *               strictly increasing within {@code [0, 1]}, ordinates within {@code [0, 1]}
     * @return the curve
     * @throws NullPointerException     if {@code points} is {@code null}
     * @throws IllegalArgumentException if the points are not as described
     */
    public static ToneCurve through(double... points) {
        Objects.requireNonNull(points, "points");
        if (points.length < 4 || points.length % 2 != 0) {
            throw new IllegalArgumentException("a curve needs at least two (x, y) points, got " + points.length + " values");
        }
        var count = points.length / 2;
        var xs = new double[count];
        var ys = new double[count];
        for (var i = 0; i < count; i++) {
            xs[i] = points[2 * i];
            ys[i] = points[2 * i + 1];
            if (!(xs[i] >= 0 && xs[i] <= 1 && ys[i] >= 0 && ys[i] <= 1)) {
                throw new IllegalArgumentException("point (" + xs[i] + ", " + ys[i] + ") is outside [0, 1]");
            }
            if (i > 0 && !(xs[i] > xs[i - 1])) {
                throw new IllegalArgumentException("abscissas must increase strictly: " + xs[i - 1] + ", " + xs[i]);
            }
        }
        return new ToneCurve(xs, ys, null);
    }

    /**
     * Returns a curve that wraps a function of normalized intensity.
     *
     * @param function the function; its results are clamped to {@code [0, 1]}, and a NaN result
     *                 counts as {@code 0}
     * @return the curve
     * @throws NullPointerException if {@code function} is {@code null}
     */
    public static ToneCurve of(DoubleUnaryOperator function) {
        return new ToneCurve(null, null, Objects.requireNonNull(function, "function"));
    }

    /**
     * Returns a gamma curve, {@code y = x^(1 / gamma)}: values above 1 brighten the mid-tones,
     * values below 1 darken them, and black and white stay fixed.
     *
     * @param gamma the gamma; positive and finite
     * @return the curve
     * @throws IllegalArgumentException if {@code gamma} is not positive and finite
     */
    public static ToneCurve gamma(double gamma) {
        if (!(gamma > 0) || !Double.isFinite(gamma)) {
            throw new IllegalArgumentException("gamma " + gamma + " is not positive and finite");
        }
        var exponent = 1 / gamma;
        return of(x -> StrictMath.pow(x, exponent));
    }

    /**
     * Evaluates the curve.
     *
     * @param x the input intensity; clamped to {@code [0, 1]}
     * @return the output intensity, within {@code [0, 1]}
     */
    public double apply(double x) {
        x = Math.clamp(x, 0.0, 1.0);
        if (function != null) {
            var y = function.applyAsDouble(x);
            return y > 0 ? Math.min(y, 1) : 0;
        }
        if (x <= xs[0]) {
            return ys[0];
        }
        var last = xs.length - 1;
        if (x >= xs[last]) {
            return ys[last];
        }
        var k = Arrays.binarySearch(xs, x);
        if (k >= 0) {
            return ys[k];
        }
        var i = -k - 2;
        var h = xs[i + 1] - xs[i];
        var t = (x - xs[i]) / h;
        var t2 = t * t;
        var t3 = t2 * t;
        var y = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * tangents[i]
                + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * tangents[i + 1];
        return Math.clamp(y, 0.0, 1.0);
    }

    /**
     * Samples the curve at every value of a sample depth.
     *
     * @param max the largest sample value, {@code 255} or {@code 65535}
     * @return {@code max + 1} entries, each {@code round(max * apply(i / max))}
     */
    int[] table(int max) {
        var table = new int[max + 1];
        for (var i = 0; i <= max; i++) {
            table[i] = (int) Math.round(max * apply((double) i / max));
        }
        return table;
    }

    /**
     * Computes the Fritsch-Carlson tangents of monotone cubic interpolation.
     *
     * @param xs the abscissas
     * @param ys the ordinates
     * @return one tangent per point
     */
    private static double[] tangents(double[] xs, double[] ys) {
        var n = xs.length;
        var secants = new double[n - 1];
        for (var i = 0; i < n - 1; i++) {
            secants[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        }
        var m = new double[n];
        m[0] = secants[0];
        m[n - 1] = secants[n - 2];
        for (var i = 1; i < n - 1; i++) {
            m[i] = secants[i - 1] * secants[i] <= 0 ? 0 : (secants[i - 1] + secants[i]) / 2;
        }
        for (var i = 0; i < n - 1; i++) {
            if (secants[i] == 0) {
                m[i] = 0;
                m[i + 1] = 0;
                continue;
            }
            var a = m[i] / secants[i];
            var b = m[i + 1] / secants[i];
            var s = a * a + b * b;
            if (s > 9) {
                var tau = 3 / Math.sqrt(s);
                m[i] = tau * a * secants[i];
                m[i + 1] = tau * b * secants[i];
            }
        }
        return m;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ToneCurve curve && function == null && curve.function == null
                && Arrays.equals(xs, curve.xs) && Arrays.equals(ys, curve.ys);
    }

    @Override
    public int hashCode() {
        return function != null ? System.identityHashCode(function) : 31 * Arrays.hashCode(xs) + Arrays.hashCode(ys);
    }

    @Override
    public String toString() {
        if (function != null) {
            return "ToneCurve[" + function + "]";
        }
        var builder = new StringBuilder("ToneCurve[");
        for (var i = 0; i < xs.length; i++) {
            builder.append(i == 0 ? "" : ", ").append('(').append(xs[i]).append(", ").append(ys[i]).append(')');
        }
        return builder.append(']').toString();
    }
}
