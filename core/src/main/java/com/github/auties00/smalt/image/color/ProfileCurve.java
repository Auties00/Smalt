package com.github.auties00.smalt.image.color;

import java.util.Arrays;

/**
 * A tone reproduction curve of an ICC profile: an ICC {@code parametricCurveType} function or a {@code curveType}
 * table, as {@link ColorProfile#rgb(com.github.auties00.smalt.image.Chromaticity,
 * com.github.auties00.smalt.image.Chromaticity, com.github.auties00.smalt.image.Chromaticity,
 * com.github.auties00.smalt.image.Chromaticity, ProfileCurve)} and the other profile builders write it.
 *
 * <p>A curve maps an encoded value {@code X} in {@code 0..1} to linear light {@code Y}. The parametric functions of
 * ICC.1:2022 section 10.18 are:
 * <table class="striped">
 * <caption>Parametric functions</caption>
 * <thead><tr><th scope="col">Function</th><th scope="col">Parameters</th><th scope="col">Curve</th></tr></thead>
 * <tbody>
 * <tr><td>0</td><td>{@code g}</td><td>{@code Y = X^g}</td></tr>
 * <tr><td>1</td><td>{@code g, a, b}</td><td>{@code Y = (aX + b)^g} for {@code X >= -b/a}, else {@code 0}</td></tr>
 * <tr><td>2</td><td>{@code g, a, b, c}</td><td>{@code Y = (aX + b)^g + c} for {@code X >= -b/a}, else
 * {@code c}</td></tr>
 * <tr><td>3</td><td>{@code g, a, b, c, d}</td><td>{@code Y = (aX + b)^g} for {@code X >= d}, else
 * {@code cX}</td></tr>
 * <tr><td>4</td><td>{@code g, a, b, c, d, e, f}</td><td>{@code Y = (aX + b)^g + e} for {@code X >= d}, else
 * {@code cX + f}</td></tr>
 * </tbody>
 * </table>
 * Parameters are stored as {@code s15Fixed16Number} values, a multiple of {@code 1/65536} from {@code -32768} to
 * just below {@code 32768}, and table samples as 16-bit values; a profile reproduces the curve at that precision.
 *
 * <p>Instances are immutable and thread-safe. Two curves are equal when they write the same data.
 */
public final class ProfileCurve {
    /**
     * The largest number of samples of a {@linkplain #sampled(float...) sampled} curve.
     */
    public static final int MAX_SAMPLES = 32767;

    /**
     * The number of parameters of each function, indexed by the function.
     */
    private static final int[] PARAMETERS = {1, 3, 4, 5, 7};

    /**
     * The sRGB curve.
     */
    private static final ProfileCurve SRGB = new ProfileCurve(IccWriter.Curve.SRGB);

    /**
     * The curve as the writer encodes it.
     */
    private final IccWriter.Curve curve;

    /**
     * The curve as transforms evaluate it, at the encoded precision.
     */
    private final ToneCurve decoded;

    /**
     * Constructs a curve.
     *
     * @param curve the curve as the writer encodes it
     */
    private ProfileCurve(IccWriter.Curve curve) {
        this.curve = curve;
        this.decoded = curve.toToneCurve();
    }

    /**
     * Returns a pure power curve, function 0.
     *
     * @param gamma the exponent that decodes values to linear light, for example {@code 2.2}
     * @return the curve
     * @throws IllegalArgumentException if the gamma is not positive or does not fit the encoding
     */
    public static ProfileCurve gamma(double gamma) {
        if (!(gamma > 0)) {
            throw new IllegalArgumentException("gamma must be positive, got " + gamma);
        }
        return parametric(0, gamma);
    }

    /**
     * Returns the identity curve, a pure power curve of exponent 1.
     *
     * @return the curve
     */
    public static ProfileCurve linear() {
        return gamma(1.0);
    }

    /**
     * Returns the sRGB curve (IEC 61966-2-1), function 3 with {@code g = 2.4}, {@code a = 1 / 1.055},
     * {@code b = 0.055 / 1.055}, {@code c = 1 / 12.92} and {@code d = 0.04045}.
     *
     * @return the curve
     */
    public static ProfileCurve srgb() {
        return SRGB;
    }

    /**
     * Returns a parametric curve.
     *
     * @param function the ICC function, {@code 0} to {@code 4}, as the class documentation lists them
     * @param parameters the parameters in the order the class documentation lists them, exactly as many as the
     * function takes; copied
     * @return the curve
     * @throws IllegalArgumentException if the function is unknown, the parameter count does not match it, or a
     * parameter is not finite or outside the {@code s15Fixed16Number} range
     */
    public static ProfileCurve parametric(int function, double... parameters) {
        if (function < 0 || function > 4) {
            throw new IllegalArgumentException("unknown parametric function " + function);
        }
        if (parameters.length != PARAMETERS[function]) {
            throw new IllegalArgumentException("function " + function + " takes " + PARAMETERS[function]
                    + " parameters, got " + parameters.length);
        }
        for (var parameter : parameters) {
            if (!(parameter >= -32768.0 && parameter < 32768.0 - 0.5 / 65536.0)) {
                throw new IllegalArgumentException("parameter " + parameter + " is outside the s15Fixed16 range");
            }
        }
        return new ProfileCurve(new IccWriter.Curve(function, parameters.clone()));
    }

    /**
     * Returns a curve sampled at evenly spaced encoded values, interpolated linearly between them.
     *
     * @param samples the linear-light values at encoded values {@code 0}, {@code 1 / (n - 1)}, ... {@code 1}, each
     * from {@code 0} to {@code 1}; from 2 to {@value #MAX_SAMPLES} of them; copied
     * @return the curve
     * @throws IllegalArgumentException if there are fewer than two or more than {@value #MAX_SAMPLES} samples, or a
     * sample is NaN or outside {@code 0..1}
     */
    public static ProfileCurve sampled(float... samples) {
        if (samples.length < 2 || samples.length > MAX_SAMPLES) {
            throw new IllegalArgumentException("a sampled curve needs 2 to " + MAX_SAMPLES + " samples, got "
                    + samples.length);
        }
        var values = new double[samples.length];
        for (var i = 0; i < samples.length; i++) {
            if (!(samples[i] >= 0 && samples[i] <= 1)) {
                throw new IllegalArgumentException("sample " + i + " is " + samples[i] + ", outside 0..1");
            }
            values[i] = samples[i];
        }
        return new ProfileCurve(new IccWriter.Curve(-2, values));
    }

    /**
     * Returns the linear-light value of an encoded value as a transform reads the curve from a profile.
     *
     * @param value the encoded value; parametric curves extend beyond {@code 0..1}, sampled curves clamp to it
     * @return the linear-light value
     */
    public double evaluate(double value) {
        if (decoded.type() != 0) {
            return ToneCurve.evaluate(decoded.type(), decoded.parameters(), value);
        }
        return decoded.evaluate((float) value);
    }

    /**
     * {@return the curve as the writer encodes it}
     */
    IccWriter.Curve curve() {
        return curve;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProfileCurve that && curve.function() == that.curve.function()
                && Arrays.equals(encodedParameters(), that.encodedParameters());
    }

    @Override
    public int hashCode() {
        return 31 * curve.function() + Arrays.hashCode(encodedParameters());
    }

    /**
     * Returns the parameters or samples at the encoded precision.
     *
     * @return the encoded values
     */
    private long[] encodedParameters() {
        var params = curve.params();
        var encoded = new long[params.length];
        for (var i = 0; i < params.length; i++) {
            encoded[i] = curve.function() == -2 ? Math.round(params[i] * 65535.0) : IccWriter.s15(params[i]);
        }
        return encoded;
    }

    @Override
    public String toString() {
        if (curve.function() == -2) {
            return "ProfileCurve[sampled, " + curve.params().length + " samples]";
        }
        return "ProfileCurve[function " + curve.function() + ", " + Arrays.toString(curve.params()) + "]";
    }
}
