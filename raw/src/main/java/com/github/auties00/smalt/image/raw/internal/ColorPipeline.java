package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.RawColorSpace;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawHighlights;
import com.github.auties00.smalt.image.raw.RawToneCurve;

import java.util.List;

/**
 * Turns demosaiced camera values into output pixels: white balance, highlight treatment, the
 * camera to output color matrix, the DNG profile tables, exposure, tone curve and the output
 * color space's transfer function.
 *
 * <p>The steps follow the DNG rendering model: white-balanced camera values are mapped to linear
 * ProPhoto RGB (D50), where the hue, saturation and value map, the exposure, the gain table map,
 * the automatic black subtraction, the look table, the tone curve and the masked color tables
 * apply, then to the output primaries, clipped to the gamut and encoded. When none of the profile
 * steps applies, the matrices fold into one, which {@link ColorKernel} applies to whole rows.
 *
 * @implNote This implementation's default tone curve, used when the file has no
 * {@code ProfileToneCurve}, is the rational approximation of a filmic curve published by
 * Krzysztof Narkowicz ("ACES Filmic Tone Mapping Curve", 2016),
 * {@code f(x) = x (2.51 x + 0.03) / (x (2.43 x + 0.59) + 0.14)}, normalized so that the
 * sensor's saturation of a neutral, 1, maps to white.
 */
final class ColorPipeline {
    /**
     * The ProPhoto RGB primaries and white.
     */
    private static final double[][] PROPHOTO = {{0.7347, 0.2653}, {0.1596, 0.8404}, {0.0366, 0.0001}, {0.3457, 0.3585}};

    /**
     * The samples of the tabulated tone curve, minus one.
     */
    private static final int TONE_STEPS = 4096;

    /**
     * The white balance multipliers, the reciprocals of the camera neutral.
     */
    final float[] multipliers = new float[3];

    /**
     * The matrix from white-balanced camera values to linear output, profile steps folded in when
     * {@link #profiled} is {@code false}.
     */
    final float[] matrix = new float[9];

    /**
     * Whether the profile steps (tables or tone curve) apply, so the per-pixel path runs.
     */
    final boolean profiled;

    /**
     * The matrix from white-balanced camera values to linear ProPhoto RGB.
     */
    private final float[] toProPhoto = new float[9];

    /**
     * The matrix from linear ProPhoto RGB to linear output.
     */
    private final float[] fromProPhoto = new float[9];

    /**
     * The exposure gain.
     */
    private final float gain;

    /**
     * The hue, saturation and value map, or {@code null}.
     */
    private final HueSatTable hueSat;

    /**
     * The look table, or {@code null}.
     */
    private final HueSatTable look;

    /**
     * The tone curve sampled at {@link #TONE_STEPS} + 1 points over [0, 1], or {@code null}.
     */
    private final float[] tone;

    /**
     * The treatment of clipped highlights.
     */
    final RawHighlights highlights;

    /**
     * The largest white balance multiplier over the smallest, the level at which every channel of
     * a white-balanced value has clipped.
     */
    final float clipSpan;

    /**
     * The output color space.
     */
    final RawColorSpace space;

    /**
     * Whether the output is monochrome: the camera has one channel.
     */
    final boolean monochrome;

    /**
     * Whether the values arrive white balanced and with their highlights treated, as the
     * projection of a four-color image leaves them: {@link #balance} then only clips them under
     * {@link RawHighlights#CLIP}.
     */
    private final boolean prebalanced;

    /**
     * The gain table map, or {@code null}.
     */
    private final GainTableMap gainMap;

    /**
     * The masked color tables, or {@code null}.
     */
    private final RgbTables rgbTables;

    /**
     * The semantic mask of each of {@link #rgbTables}, or {@code null} entries.
     */
    private final SemanticMask[] tableMasks;

    /**
     * The decoded values of each of {@link #tableMasks}, or {@code null} entries.
     */
    private final float[][] maskValues;

    /**
     * Whether an automatic black point is subtracted.
     */
    final boolean autoBlack;

    /**
     * The automatic black point, in linear ProPhoto RGB after exposure; 0 until measured.
     */
    private float black;

    /**
     * Builds the pipeline of a file for a rendering.
     *
     * @param color the camera color mapping
     * @param calibration the file's color description
     * @param options the rendering options
     * @param monochrome whether the image has a single channel
     */
    ColorPipeline(CameraColor color, ColorCalibration calibration, RawDecoderOptions options, boolean monochrome) {
        this(color, calibration, options, monochrome, false, List.of(), 0);
    }

    /**
     * Builds the pipeline of a file for a rendering.
     *
     * @param color the camera color mapping
     * @param calibration the color description the rendering uses
     * @param options the rendering options
     * @param monochrome whether the image has a single channel
     * @param prebalanced whether the values arrive white balanced, as a four-color projection
     * leaves them
     * @param masks the semantic masks of the file, which the masked color tables refer to
     * @param scaleStops the exposure, in stops, that undoes a scaling of the linear values (the one
     * {@link DualSamples} applies), added whatever the tone curve
     */
    ColorPipeline(CameraColor color, ColorCalibration calibration, RawDecoderOptions options, boolean monochrome,
                  boolean prebalanced, List<SemanticMask> masks, double scaleStops) {
        this.monochrome = monochrome;
        this.prebalanced = prebalanced;
        var limits = options.limits();
        this.space = options.colorSpace();
        this.highlights = options.highlights();
        var standard = options.toneCurve() == RawToneCurve.STANDARD;
        var stops = options.exposure() + scaleStops + (standard ? calibration.baselineExposure : 0);
        this.gain = (float) StrictMath.pow(2, Math.clamp(stops, -16, 16));
        var minimum = Double.MAX_VALUE;
        var maximum = 0.0;
        for (var index = 0; index < 3; index++) {
            var multiplier = monochrome ? 1 : 1 / color.neutral[index];
            multipliers[index] = (float) multiplier;
            minimum = Math.min(minimum, multiplier);
            maximum = Math.max(maximum, multiplier);
        }
        this.clipSpan = (float) (maximum / minimum);
        var pcsToProPhoto = ColorMath.invert(ColorMath.rgbToXyz(PROPHOTO[0], PROPHOTO[1], PROPHOTO[2], PROPHOTO[3]));
        var balancedToProPhoto = ColorMath.multiply(pcsToProPhoto, color.balancedToPcs);
        var proPhotoToOutput = ColorMath.multiply(outputFromPcs(space), ColorMath.invert(pcsToProPhoto));
        copy(balancedToProPhoto, toProPhoto);
        copy(proPhotoToOutput, fromProPhoto);
        if (standard && calibration.hueSatDims != null) {
            hueSat = hueSatTable(calibration, color.weights);
        } else {
            hueSat = null;
        }
        look = standard && calibration.lookDims != null
                ? new HueSatTable(calibration.lookDims, calibration.lookTable, calibration.lookSrgb) : null;
        if (hueSat != null) {
            ColorKernels.HUE_SAT.prepare(hueSat);
        }
        if (look != null) {
            ColorKernels.HUE_SAT.prepare(look);
        }
        tone = standard ? toneTable(calibration.toneCurve) : null;
        gainMap = standard && !monochrome ? calibration.gainTableMap : null;
        rgbTables = standard && !monochrome ? calibration.rgbTables : null;
        tableMasks = rgbTables != null ? rgbTables.match(masks, limits) : null;
        maskValues = tableMasks != null ? new float[tableMasks.length][] : null;
        for (var index = 0; tableMasks != null && index < tableMasks.length; index++) {
            maskValues[index] = tableMasks[index] != null ? tableMasks[index].values(limits) : null;
        }
        autoBlack = standard && !monochrome && options.autoBlack() && calibration.blackRender == 0;
        profiled = !monochrome && (hueSat != null || look != null || tone != null || gainMap != null
                || rgbTables != null || autoBlack);
        var folded = ColorMath.multiply(proPhotoToOutput, balancedToProPhoto);
        for (var index = 0; index < 9; index++) {
            folded[index] *= gain;
        }
        if (monochrome) {
            folded = new double[]{gain, 0, 0, 0, gain, 0, 0, 0, gain};
        }
        copy(folded, matrix);
    }

    /**
     * Returns the matrix from XYZ (D50) to the linear primaries of an output color space, adapted
     * to its white with the Bradford transform.
     *
     * @param space the color space
     * @return the matrix
     */
    static double[] outputFromPcs(RawColorSpace space) {
        double[][] primaries = switch (space) {
            case SRGB, LINEAR_SRGB -> new double[][]{{0.64, 0.33}, {0.30, 0.60}, {0.15, 0.06}, ColorMath.D65_XY};
            case DISPLAY_P3 -> new double[][]{{0.680, 0.320}, {0.265, 0.690}, {0.150, 0.060}, ColorMath.D65_XY};
            case ADOBE_RGB -> new double[][]{{0.64, 0.33}, {0.21, 0.71}, {0.15, 0.06}, ColorMath.D65_XY};
            case PROPHOTO_RGB -> PROPHOTO;
            case LINEAR_REC2020 -> new double[][]{{0.708, 0.292}, {0.170, 0.797}, {0.131, 0.046}, ColorMath.D65_XY};
        };
        var toXyz = ColorMath.rgbToXyz(primaries[0], primaries[1], primaries[2], primaries[3]);
        return ColorMath.multiply(ColorMath.invert(toXyz), ColorMath.adaptation(ColorMath.D50_XY, primaries[3]));
    }

    /**
     * Samples a tone curve over [0, 1].
     *
     * @param points the profile's input and output pairs, or {@code null} for the default curve
     * @return the samples
     */
    private static float[] toneTable(float[] points) {
        var table = new float[TONE_STEPS + 1];
        if (points == null) {
            var white = filmic(1);
            for (var index = 0; index <= TONE_STEPS; index++) {
                table[index] = (float) (filmic(index / (double) TONE_STEPS) / white);
            }
            return table;
        }
        var count = points.length / 2;
        var segment = 0;
        for (var index = 0; index <= TONE_STEPS; index++) {
            var x = index / (float) TONE_STEPS;
            while (segment < count - 2 && points[2 * (segment + 1)] < x) {
                segment++;
            }
            var x0 = points[2 * segment];
            var y0 = points[2 * segment + 1];
            var x1 = points[2 * segment + 2];
            var y1 = points[2 * segment + 3];
            var t = x1 > x0 ? Math.clamp((x - x0) / (x1 - x0), 0f, 1f) : 0f;
            table[index] = y0 + (y1 - y0) * t;
        }
        return table;
    }

    /**
     * Evaluates the default filmic curve.
     *
     * @param x the linear input, non-negative
     * @return the output
     */
    private static double filmic(double x) {
        return x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);
    }

    /**
     * Copies a matrix into single precision.
     *
     * @param source the matrix
     * @param target the destination
     */
    private static void copy(double[] source, float[] target) {
        for (var index = 0; index < 9; index++) {
            target[index] = (float) source[index];
        }
    }

    /**
     * Applies white balance and highlight treatment to a row of camera values, in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param count the number of pixels
     */
    void balance(float[] r, float[] g, float[] b, int count) {
        if (prebalanced) {
            if (highlights == RawHighlights.CLIP) {
                for (var index = 0; index < count; index++) {
                    r[index] = Math.min(r[index], 1f);
                    g[index] = Math.min(g[index], 1f);
                    b[index] = Math.min(b[index], 1f);
                }
            }
            return;
        }
        var mr = multipliers[0];
        var mg = multipliers[1];
        var mb = multipliers[2];
        if (highlights == RawHighlights.CLIP) {
            for (var index = 0; index < count; index++) {
                r[index] = Math.min(r[index] * mr, 1f);
                g[index] = Math.min(g[index] * mg, 1f);
                b[index] = Math.min(b[index] * mb, 1f);
            }
            return;
        }
        var span = Math.max(clipSpan - 1, 1e-3f);
        for (var index = 0; index < count; index++) {
            var red = Math.min(r[index] * mr, mr);
            var green = Math.min(g[index] * mg, mg);
            var blue = Math.min(b[index] * mb, mb);
            var max = Math.max(red, Math.max(green, blue));
            if (max <= 1f) {
                r[index] = red;
                g[index] = green;
                b[index] = blue;
                continue;
            }
            var luminance = Math.min((red + green + blue) / 3f, 1f);
            var t = Math.min((max - 1f) / span, 1f);
            t = t * t * (3 - 2 * t);
            r[index] = Math.min(red, 1f) * (1 - t) + luminance * t;
            g[index] = Math.min(green, 1f) * (1 - t) + luminance * t;
            b[index] = Math.min(blue, 1f) * (1 - t) + luminance * t;
        }
    }

    /**
     * Sets the automatic black point.
     *
     * @param black the luminance subtracted, in linear ProPhoto RGB after exposure, from 0 to 0.02
     */
    void black(float black) {
        this.black = black;
    }

    /**
     * Returns the luminance of a white-balanced camera value after exposure, as the automatic black
     * point is measured.
     *
     * @param r the white-balanced red
     * @param g the white-balanced green
     * @param b the white-balanced blue
     * @return the luminance in linear ProPhoto RGB
     */
    float luminance(float r, float g, float b) {
        var p = toProPhoto;
        // the luminance row of ProPhoto RGB
        var red = p[0] * r + p[1] * g + p[2] * b;
        var green = p[3] * r + p[4] * g + p[5] * b;
        var blue = p[6] * r + p[7] * g + p[8] * b;
        return gain * (0.2880402f * red + 0.7118741f * green + 0.0000857f * blue);
    }

    /**
     * Scratch arrays for {@link #profile}, one set per thread.
     */
    static final class Scratch {
        /**
         * The gain tables of a row, or {@code null} without a gain table map.
         */
        final float[] gainTables;

        /**
         * The scratch arrays of the masked color tables, or {@code null} without them.
         */
        final RgbTables.Scratch tables;

        /**
         * Constructs the arrays.
         *
         * @param gainTables the gain tables of a row, or {@code null}
         * @param tables the scratch arrays of the masked color tables, or {@code null}
         */
        Scratch(float[] gainTables, RgbTables.Scratch tables) {
            this.gainTables = gainTables;
            this.tables = tables;
        }
    }

    /**
     * Returns the scratch arrays {@link #profile} works in.
     *
     * @return new scratch arrays
     */
    Scratch scratch() {
        return new Scratch(gainMap != null ? new float[gainMap.pointsH * gainMap.pointsN] : null,
                rgbTables != null ? rgbTables.scratch() : null);
    }

    /**
     * Applies the profile steps to a tile of white-balanced values, producing linear output values,
     * in place. Only used when {@link #profiled} is {@code true}.
     *
     * @param r the red values, row by row
     * @param g the green values
     * @param b the blue values
     * @param columns the pixels of each row
     * @param rows the number of rows
     * @param u the horizontal position of the first pixel's center, relative to the main image's
     * active area
     * @param du the horizontal distance between pixels, relative to the active area
     * @param v the vertical position of the first row's center, relative to the active area
     * @param dv the vertical distance between rows, relative to the active area
     * @param scratch the scratch arrays, from {@link #scratch()}
     */
    void profile(float[] r, float[] g, float[] b, int columns, int rows, double u, double du, double v, double dv,
                 Scratch scratch) {
        var count = columns * rows;
        var p = toProPhoto;
        for (var index = 0; index < count; index++) {
            var red = r[index];
            var green = g[index];
            var blue = b[index];
            r[index] = Math.max(0, p[0] * red + p[1] * green + p[2] * blue);
            g[index] = Math.max(0, p[3] * red + p[4] * green + p[5] * blue);
            b[index] = Math.max(0, p[6] * red + p[7] * green + p[8] * blue);
        }
        if (hueSat != null) {
            ColorKernels.HUE_SAT.apply(hueSat, r, g, b, count);
        }
        for (var index = 0; index < count; index++) {
            r[index] *= gain;
            g[index] *= gain;
            b[index] *= gain;
        }
        if (gainMap != null) {
            for (var row = 0; row < rows; row++) {
                gainMap.apply(r, g, b, row * columns, columns, u, du, v + row * dv, scratch.gainTables);
            }
        }
        if (autoBlack && black > 0) {
            var level = black;
            var scale = 1 / (1 - level);
            var knee = 2 * level;
            var toe = scale / (4 * level);
            for (var index = 0; index < count; index++) {
                r[index] = ramp(r[index], level, knee, scale, toe);
                g[index] = ramp(g[index], level, knee, scale, toe);
                b[index] = ramp(b[index], level, knee, scale, toe);
            }
        }
        if (look != null) {
            ColorKernels.HUE_SAT.apply(look, r, g, b, count);
        }
        if (tone != null) {
            for (var index = 0; index < count; index++) {
                r[index] = tone(r[index]);
                g[index] = tone(g[index]);
                b[index] = tone(b[index]);
            }
        }
        if (rgbTables != null) {
            for (var row = 0; row < rows; row++) {
                rgbTables.apply(r, g, b, row * columns, columns, u, du, v + row * dv, tableMasks, maskValues,
                        scratch.tables);
            }
        }
        var o = fromProPhoto;
        for (var index = 0; index < count; index++) {
            var red = r[index];
            var green = g[index];
            var blue = b[index];
            r[index] = o[0] * red + o[1] * green + o[2] * blue;
            g[index] = o[3] * red + o[4] * green + o[5] * blue;
            b[index] = o[6] * red + o[7] * green + o[8] * blue;
        }
    }

    /**
     * Subtracts the black point from a value: linearly above twice the black point, along a
     * parabola that meets the line with the same slope below it, so that 0 stays 0.
     *
     * @param x the value
     * @param level the black point
     * @param knee twice the black point
     * @param scale the slope of the line, the reciprocal of one minus the black point
     * @param toe the parabola's coefficient, {@code scale / (4 * level)}
     * @return the value with the black point subtracted
     */
    private static float ramp(float x, float level, float knee, float scale, float toe) {
        if (x >= knee) {
            return (x - level) * scale;
        }
        return x > 0 ? x * x * toe : 0;
    }

    /**
     * Evaluates the tone curve, extrapolating linearly above 1.
     *
     * @param x the linear input
     * @return the output
     */
    private float tone(float x) {
        if (!(x > 0)) {
            return 0;
        }
        if (x >= 1) {
            return tone[TONE_STEPS] + (x - 1) * (tone[TONE_STEPS] - tone[TONE_STEPS - 1]) * TONE_STEPS;
        }
        var scaled = x * TONE_STEPS;
        var index = (int) scaled;
        var fraction = scaled - index;
        return tone[index] + (tone[index + 1] - tone[index]) * fraction;
    }

    /**
     * Blends the hue, saturation and value maps of the calibration illuminants with the weights
     * of the white balance: the two weighted maps, or the single weighted one, or the first two
     * when the weighted illuminants have no map.
     *
     * @param calibration the color description
     * @param weights the weight of each calibration illuminant
     * @return the blended table
     */
    private static HueSatTable hueSatTable(ColorCalibration calibration, double[] weights) {
        var maps = calibration.hueSatMap;
        var first = -1;
        var second = -1;
        for (var index = 0; index < 3; index++) {
            if (weights[index] > 0 && maps[index] != null) {
                if (first < 0) {
                    first = index;
                } else {
                    second = index;
                }
            }
        }
        if (first < 0) {
            return HueSatTable.blend(calibration.hueSatDims, maps[0], maps[1], weights[0], calibration.hueSatSrgb);
        }
        if (second < 0) {
            return HueSatTable.blend(calibration.hueSatDims, maps[first], null, 1, calibration.hueSatSrgb);
        }
        return HueSatTable.blend(calibration.hueSatDims, maps[first], maps[second],
                weights[first] / (weights[first] + weights[second]), calibration.hueSatSrgb);
    }
}
