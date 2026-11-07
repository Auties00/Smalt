package com.github.auties00.smalt.image.raw.internal;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * Builds the {@link LensCorrection} of a camera raw file from the lens correction data its vendor records.
 *
 * <ul>
 * <li>Sony ARW: tag {@code 0x7037} of the raw directory, a count {@code n} then {@code n} signed 16-bit
 * distortion knots, equally spaced from the center to the corner of the active area, each the factor minus 1 in
 * units of 2^-14; tag {@code 0x7035}, a count {@code 2m} then {@code m} red and {@code m} blue knots, equally
 * spaced the same way, each the plane's factor relative to green minus 1 in units of 2^-21. Tags {@code 0x7036}
 * and {@code 0x7034} switch them off when 0. The distortion of the cameras of interchangeable lenses describes the
 * lens mounted, which raw converters correct from lens profiles of their own, and is not applied.</li>
 * <li>Panasonic RW2: tag {@code 0x0119}, sixteen signed 16-bit fields: field 7's low nibble switches the
 * correction on, field 12 is the radius {@code N} in pixels, field 5 the scale {@code 1 / (1 - f5 / 32768)}, and
 * fields 8, 4 and 11 the coefficients {@code a2}, {@code a4} and {@code a6} in units of 2^-15 of the map from a
 * source radius {@code u} (in units of {@code N}, from the center of the image area) to the output radius
 * {@code u (1 + a2 u^2 + a4 u^4 + a6 u^6)}; tag {@code 0x011B}, 32 signed 16-bit fields of the lateral chromatic
 * aberration: fields 17, 16, 4 and 11 are four increasing radii in pixels of the stored image, and fields 12, 23, 8
 * and 26 (red) and 27, 10, 20 and 29 (blue) the displacement of the plane toward the center at each of them, in
 * thousandths of a pixel, measured in the source image (after the distortion) and interpolated linearly from 0 at the
 * center.</li>
 * <li>Olympus ORF: tag {@code 0x150A} of the ImageProcessing directory, four floats {@code k1, k2, k3, k0} of
 * the factor {@code k0 + k1 u^2 + k2 u^4 + k3 u^6}, with {@code u} the radius from the center of the image area
 * in units of its half diagonal divided by {@code k0^1.5}; tag {@code 0x150C}, six floats {@code c0} to
 * {@code c5} of the red and blue factors relative to green, {@code 1 + c0 + c1 u^2 + c2 u^4} and
 * {@code 1 + c3 + c4 u^2 + c5 u^4}.</li>
 * <li>Fujifilm RAF: tags {@code 0xF00B} (distortion), {@code 0xF00F} (chromatic aberration) and {@code 0xF010}
 * (vignetting) of the sensor block's private directory, signed rationals: the radius {@code R} in pixels divided
 * by the knot count {@code n}, then {@code n} knot radii in units of {@code R}, then for distortion {@code n}
 * displacements in percent of the radius, for chromatic aberration {@code n} red and {@code n} blue factors
 * relative to green minus 1, and for vignetting {@code n} brightnesses in percent of the center's. The distortion
 * is scaled so that the image fills its area.</li>
 * </ul>
 *
 * @implNote This implementation derives the layouts and the units from CC0 sample files of every vendor, by
 * comparing the recorded data with the {@code WarpRectilinear} and {@code FixVignetteRadial} opcodes Adobe DNG
 * Converter 18.0, run as a black box, writes for the same files; tag names follow the ExifTool tag documentation.
 * The Olympus radius scale {@code k0^1.5} and the Fujifilm fill scale are fits to those opcodes, not documented
 * rules. Vignetting data the converter does not apply (Sony tag {@code 0x7032} and the Olympus and Panasonic
 * vignetting) is not applied either.
 */
final class VendorLens {
    /**
     * The spacing of the knots that sample an analytic model, in units of the model's radius.
     */
    private static final double STEP = 1.0 / 64;

    /**
     * The largest radius an analytic model is sampled to, in units of the model's radius.
     */
    private static final double REACH = 1.5;

    /**
     * Prevents instantiation.
     */
    private VendorLens() {
        throw new AssertionError();
    }

    /**
     * Returns the corrections of a Sony ARW file. The distortion applies only to the cameras of a fixed lens
     * (the {@code DSC} models and the {@code ZV-1} family); the chromatic aberration to every camera.
     *
     * @param raw the raw directory
     * @param model the camera model, or {@code null}
     * @param width the width of the active area
     * @param height the height of the active area
     * @return the corrections, or {@code null} when the file records none
     */
    static LensCorrection sony(Ifd raw, String model, int width, int height) {
        var name = model == null ? "" : model.strip();
        var fixedLens = name.startsWith("DSC-") || name.startsWith("ZV-1");
        var distortion = fixedLens ? raw.longs(0x7037) : null;
        var aberration = raw.longs(0x7035);
        var useDistortion = distortion != null && raw.longValue(0x7036, 1) != 0 && distortion.length > 2
                && distortion[0] >= 2 && distortion[0] < distortion.length;
        var useAberration = aberration != null && raw.longValue(0x7034, 1) != 0 && aberration.length > 4
                && aberration[0] >= 4 && aberration[0] < aberration.length;
        if (!useDistortion && !useAberration) {
            return null;
        }
        DoubleUnaryOperator green = u -> 1;
        if (useDistortion) {
            var n = (int) distortion[0];
            var values = new double[n];
            for (var index = 0; index < n; index++) {
                values[index] = 1 + (short) distortion[1 + index] / 16384.0;
            }
            var knots = spaced(n);
            green = u -> LensCorrection.evaluate(knots, values, u);
        }
        DoubleUnaryOperator red = u -> 1;
        DoubleUnaryOperator blue = u -> 1;
        if (useAberration) {
            var m = (int) aberration[0] / 2;
            var reds = new double[m];
            var blues = new double[m];
            for (var index = 0; index < m; index++) {
                reds[index] = 1 + (short) aberration[1 + index] / 2097152.0;
                blues[index] = 1 + (short) aberration[1 + m + index] / 2097152.0;
            }
            var knots = spaced(m);
            red = u -> LensCorrection.evaluate(knots, reds, u);
            blue = u -> LensCorrection.evaluate(knots, blues, u);
        }
        return sampled(width / 2.0, height / 2.0, StrictMath.hypot(width / 2.0, height / 2.0), 1, green, red, blue, null);
    }

    /**
     * Returns the corrections of a Panasonic RW2 file.
     *
     * @param tag the 32 bytes of tag {@code 0x0119}, or {@code null}
     * @param aberration the 64 bytes of tag {@code 0x011B}, or {@code null}
     * @param crop the image area as x, y, width and height in the stored image, or {@code null}
     * @param width the stored width
     * @param height the stored height
     * @return the corrections, or {@code null} when the file records none
     */
    static LensCorrection panasonic(byte[] tag, byte[] aberration, double[] crop, int width, int height) {
        var area = crop != null ? crop : new double[]{0, 0, width, height};
        var shifts = panasonicAberration(aberration);
        var field = tag != null && tag.length >= 32 ? shorts(tag, 16) : null;
        var distorted = field != null && (field[7] & 0x0F) != 0 && field[12] > 0;
        if (!distorted) {
            if (shifts == null) {
                return null;
            }
            var radius = shifts[0][shifts[0].length - 1];
            DoubleUnaryOperator identity = u -> 1;
            return sampled(area[0] + area[2] / 2, area[1] + area[3] / 2, radius, 1, identity,
                    shift(shifts[0], shifts[1], identity, radius), shift(shifts[0], shifts[2], identity, radius), null);
        }
        var radius = field[12];
        var scale = 1 / (1 - field[5] / 32768.0);
        var a2 = field[8] / 32768.0;
        var a4 = field[4] / 32768.0;
        var a6 = field[11] / 32768.0;
        var count = (int) Math.round(REACH / STEP) + 1;
        var knots = new double[count];
        var values = new double[count];
        var source = 0.0;
        var used = 0;
        for (var index = 0; index < count; index++) {
            var target = index * STEP;
            knots[index] = target;
            if (index == 0) {
                values[index] = scale;
                used = 1;
                continue;
            }
            var solved = false;
            for (var iteration = 0; iteration < 50; iteration++) {
                var u2 = source * source;
                var mapped = source * (1 + u2 * (a2 + u2 * (a4 + u2 * a6)));
                var slope = 1 + u2 * (3 * a2 + u2 * (5 * a4 + u2 * 7 * a6));
                if (!(slope > 0)) {
                    break;
                }
                var next = source - (mapped - target) / slope;
                if (Math.abs(next - source) < 1e-12) {
                    source = next;
                    solved = true;
                    break;
                }
                source = next;
            }
            if (!solved || !(source > 0)) {
                break;
            }
            values[index] = scale * source / target;
            used = index + 1;
        }
        var usedKnots = Arrays.copyOf(knots, used);
        var usedValues = Arrays.copyOf(values, used);
        DoubleUnaryOperator green = u -> LensCorrection.evaluate(usedKnots, usedValues, u);
        DoubleUnaryOperator red = u -> 1;
        DoubleUnaryOperator blue = u -> 1;
        if (shifts != null) {
            red = shift(shifts[0], shifts[1], green, radius);
            blue = shift(shifts[0], shifts[2], green, radius);
        }
        return sampled(area[0] + area[2] / 2, area[1] + area[3] / 2, radius, 1, green, red, blue, null);
    }

    /**
     * Parses the lateral chromatic aberration of Panasonic tag {@code 0x011B}.
     *
     * @param tag the 64 bytes of the tag, or {@code null}
     * @return the radii in pixels, the red and the blue displacements in pixels, each starting with 0 at the
     *         center, or {@code null} when the tag is absent, all zero or inconsistent
     */
    private static double[][] panasonicAberration(byte[] tag) {
        if (tag == null || tag.length < 64) {
            return null;
        }
        var field = shorts(tag, 32);
        var radii = new double[]{0, field[17], field[16], field[4], field[11]};
        var red = new double[]{0, field[12] / 1000.0, field[23] / 1000.0, field[8] / 1000.0, field[26] / 1000.0};
        var blue = new double[]{0, field[27] / 1000.0, field[10] / 1000.0, field[20] / 1000.0, field[29] / 1000.0};
        if (!increasing(radii)) {
            return null;
        }
        var any = false;
        for (var index = 1; index < radii.length; index++) {
            any |= red[index] != 0 || blue[index] != 0;
        }
        return any ? new double[][]{radii, red, blue} : null;
    }

    /**
     * Returns the factor of a plane relative to green that displaces it toward the center by an amount
     * interpolated at the green source radius.
     *
     * @param radii the radii of the displacements, in pixels
     * @param displacements the displacements, in pixels
     * @param green the green distortion factor
     * @param radius the model's radius, in pixels
     * @return the factor, as a function of the output radius in units of the model's radius
     */
    private static DoubleUnaryOperator shift(double[] radii, double[] displacements, DoubleUnaryOperator green,
                                             double radius) {
        return u -> {
            var source = u * radius * green.applyAsDouble(u);
            return source > 0 ? 1 - LensCorrection.evaluate(radii, displacements, source) / source : 1;
        };
    }

    /**
     * {@return the little-endian signed 16-bit fields of a tag}
     *
     * @param tag the bytes of the tag
     * @param count the number of fields
     */
    private static int[] shorts(byte[] tag, int count) {
        var field = new int[count];
        for (var index = 0; index < count; index++) {
            field[index] = (short) ((tag[2 * index] & 0xFF) | (tag[2 * index + 1] & 0xFF) << 8);
        }
        return field;
    }

    /**
     * Returns the corrections of an Olympus ORF file.
     *
     * @param distortion the four floats of tag {@code 0x150A}, or {@code null}
     * @param aberration the six floats of tag {@code 0x150C}, or {@code null}
     * @param crop the image area as x, y, width and height in the active area, or {@code null}
     * @param width the width of the active area
     * @param height the height of the active area
     * @return the corrections, or {@code null} when the file records none
     */
    static LensCorrection olympus(double[] distortion, double[] aberration, double[] crop, int width, int height) {
        var useDistortion = distortion != null && distortion.length >= 4 && finite(distortion)
                && distortion[3] > 0.5 && distortion[3] < 2;
        var useAberration = aberration != null && aberration.length >= 6 && finite(aberration);
        if (!useDistortion && !useAberration) {
            return null;
        }
        var k1 = useDistortion ? distortion[0] : 0;
        var k2 = useDistortion ? distortion[1] : 0;
        var k3 = useDistortion ? distortion[2] : 0;
        var k0 = useDistortion ? distortion[3] : 1;
        var area = crop != null ? crop : new double[]{0, 0, width, height};
        var radius = StrictMath.hypot(area[2] / 2, area[3] / 2) / StrictMath.pow(k0, 1.5);
        DoubleUnaryOperator green = u -> k0 + u * u * (k1 + u * u * (k2 + u * u * k3));
        DoubleUnaryOperator red = u -> 1;
        DoubleUnaryOperator blue = u -> 1;
        if (useAberration) {
            var c = aberration;
            red = u -> 1 + c[0] + u * u * (c[1] + u * u * c[2]);
            blue = u -> 1 + c[3] + u * u * (c[4] + u * u * c[5]);
        }
        return sampled(area[0] + area[2] / 2, area[1] + area[3] / 2, radius, 1, green, red, blue, null);
    }

    /**
     * Returns the corrections of a Fujifilm RAF file.
     *
     * @param distortion the values of tag {@code 0xF00B}, or {@code null}
     * @param aberration the values of tag {@code 0xF00F}, or {@code null}
     * @param vignetting the values of tag {@code 0xF010}, or {@code null}
     * @param crop the image area as x, y, width and height in the active area, or {@code null}
     * @param width the width of the active area
     * @param height the height of the active area
     * @return the corrections, or {@code null} when the file records none
     */
    static LensCorrection fujifilm(double[] distortion, double[] aberration, double[] vignetting, double[] crop,
                                   int width, int height) {
        var area = crop != null ? crop : new double[]{0, 0, width, height};
        var radius = 0.0;
        DoubleUnaryOperator green = null;
        var fill = 1.0;
        if (distortion != null && distortion.length >= 5 && finite(distortion)) {
            var n = (distortion.length - 1) / 2;
            radius = distortion[0] * n;
            var stored = slice(distortion, 1, n);
            var knots = stored[0] > 0 ? prepend(stored, 0) : stored;
            var displacements = slice(distortion, 1 + n, n);
            var values = stored[0] > 0 ? prepend(displacements, 0) : displacements;
            for (var index = 0; index < values.length; index++) {
                values[index] = 1 + values[index] / 100;
            }
            if (radius > 0 && increasing(knots)) {
                green = u -> LensCorrection.evaluate(knots, values, u);
                // the smallest scale that keeps every border pixel's source inside the image
                var nearest = Math.min(area[2], area[3]) / 2 / radius;
                var corner = StrictMath.hypot(area[2] / 2, area[3] / 2) / radius;
                var largest = 0.0;
                for (var step = 0; step <= 256; step++) {
                    largest = Math.max(largest, green.applyAsDouble(nearest + (corner - nearest) * step / 256));
                }
                fill = largest > 0 ? 1 / largest : 1;
            }
        }
        DoubleUnaryOperator red = u -> 1;
        DoubleUnaryOperator blue = u -> 1;
        if (aberration != null && aberration.length >= 7 && finite(aberration)) {
            var n = (aberration.length - 1) / 3;
            var knots = slice(aberration, 1, n);
            var reds = slice(aberration, 1 + n, n);
            var blues = slice(aberration, 1 + 2 * n, n);
            var base = aberration[0] * n;
            if (increasing(knots) && base > 0) {
                for (var index = 0; index < n; index++) {
                    reds[index] += 1;
                    blues[index] += 1;
                }
                var ratio = radius > 0 ? base / radius : 1;
                if (radius <= 0) {
                    radius = base;
                }
                red = u -> LensCorrection.evaluate(knots, reds, u * ratio);
                blue = u -> LensCorrection.evaluate(knots, blues, u * ratio);
            }
        }
        DoubleUnaryOperator gain = null;
        if (vignetting != null && vignetting.length >= 5 && finite(vignetting)) {
            var n = (vignetting.length - 1) / 2;
            var base = vignetting[0] * n;
            var stored = slice(vignetting, 1, n);
            var knots = stored[0] > 0 ? prepend(stored, 0) : stored;
            var brightness = slice(vignetting, 1 + n, n);
            var values = stored[0] > 0 ? prepend(brightness, 100) : brightness;
            var positive = true;
            for (var index = 0; index < values.length; index++) {
                positive &= values[index] > 0;
                values[index] = 100 / values[index];
            }
            if (base > 0 && positive && increasing(knots)) {
                if (radius <= 0) {
                    radius = base;
                }
                var ratio = base / radius;
                gain = u -> LensCorrection.evaluate(knots, values, u * ratio);
            }
        }
        if (radius <= 0 || (green == null && gain == null && red.applyAsDouble(0.5) == 1)) {
            return null;
        }
        return sampled(area[0] + area[2] / 2, area[1] + area[3] / 2, radius, fill,
                green != null ? green : u -> 1, red, blue, gain);
    }

    /**
     * Samples the model functions at knots up to {@link #REACH}.
     *
     * @param centerX the column of the optical center, in pixels of the active area
     * @param centerY the row of the optical center, in pixels of the active area
     * @param radius the model's radius, in pixels
     * @param scale the factor the distortion of every plane is multiplied by
     * @param green the green distortion factor
     * @param red the red factor relative to green
     * @param blue the blue factor relative to green
     * @param gain the vignetting gain, or {@code null}
     * @return the corrections
     */
    private static LensCorrection sampled(double centerX, double centerY, double radius, double scale,
                                          DoubleUnaryOperator green, DoubleUnaryOperator red,
                                          DoubleUnaryOperator blue, DoubleUnaryOperator gain) {
        var count = (int) Math.round(REACH / STEP) + 1;
        var knots = new double[count];
        var planes = new double[3][count];
        var gains = gain != null ? new double[count] : null;
        for (var index = 0; index < count; index++) {
            var u = index * STEP;
            knots[index] = u;
            var g = scale * green.applyAsDouble(u);
            planes[0][index] = g * red.applyAsDouble(u);
            planes[1][index] = g;
            planes[2][index] = g * blue.applyAsDouble(u);
            if (gains != null) {
                gains[index] = gain.applyAsDouble(u);
            }
        }
        return new LensCorrection(centerX, centerY, radius, knots, planes, gains != null ? knots : null, gains);
    }

    /**
     * {@return {@code n} knots equally spaced from 0 to 1}
     *
     * @param n the number of knots, at least 2
     */
    private static double[] spaced(int n) {
        var knots = new double[n];
        for (var index = 0; index < n; index++) {
            knots[index] = (double) index / (n - 1);
        }
        return knots;
    }

    /**
     * {@return a copy of part of an array}
     *
     * @param values the array
     * @param from the first index
     * @param count the number of values
     */
    private static double[] slice(double[] values, int from, int count) {
        var result = new double[count];
        System.arraycopy(values, from, result, 0, count);
        return result;
    }

    /**
     * {@return an array with a value put first, for a function whose first knot is past the center}
     *
     * @param values the knots or values
     * @param first the value at the center
     */
    private static double[] prepend(double[] values, double first) {
        var result = new double[values.length + 1];
        result[0] = first;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    /**
     * {@return whether knots strictly increase}
     *
     * @param knots the knot radii
     */
    private static boolean increasing(double[] knots) {
        for (var index = 1; index < knots.length; index++) {
            if (!(knots[index] > knots[index - 1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return whether every value is finite}
     *
     * @param values the knot radii or the values at them
     */
    private static boolean finite(double[] values) {
        for (var value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
