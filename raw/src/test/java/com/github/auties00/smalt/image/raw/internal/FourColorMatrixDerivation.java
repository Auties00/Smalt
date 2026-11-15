package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fits the four-color camera matrices of {@link FourColorMatrices} against the embedded JPEG
 * previews of the CC0 sample files.
 *
 * <p>The linear sensor image and the preview are reduced to the grid of {@link PreviewBalance}.
 * Every cell without a clipped photosite, with photosites of all four colors and with a preview
 * whose linear channels lie between 0.004 and 0.85 is a patch: its preview color, linearized from
 * sRGB, is taken to XYZ (D65), and the {@code 4 x 3} matrix from XYZ to the cell's four camera
 * means that best predicts the patches is fitted by least squares, one camera color at a time.
 * The fit is then refined on the chroma of the patches as the decoder's default rendering renders
 * them against the preview's (see {@code refine}), with a small penalty on the distance from the
 * least-squares fit. Scaled so that the D65 white maps to a largest camera value of 1, it is the
 * DNG {@code ColorMatrix}.
 */
final class FourColorMatrixDerivation {
    /**
     * The linear sRGB to XYZ matrix (D65), IEC 61966-2-1.
     */
    private static final double[] SRGB_TO_XYZ = {
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041
    };

    /**
     * The XYZ of the D65 white, Y 1.
     */
    private static final double[] D65_XYZ = {0.95047, 1, 1.08883};

    /**
     * The smallest linear preview channel of a patch.
     */
    private static final double MINIMUM = 0.004;

    /**
     * The largest linear preview channel of a patch.
     */
    private static final double MAXIMUM = 0.85;

    /**
     * The step of the central differences of the refinement's Jacobian, far above the rounding of
     * the single-precision color pipeline, whose last bits differ between platforms.
     */
    private static final double DERIVATIVE_STEP = 1e-3;

    /**
     * The weight of a parameter's distance from the least-squares fit in the refinement, in CIELAB
     * chroma units per unit of the coefficient: a change of 0.1 costs as much as one patch off by 1.
     */
    private static final double PENALTY = 10;

    /**
     * Prevents instantiation.
     */
    private FourColorMatrixDerivation() {
    }

    /**
     * The fitted matrix of one sample.
     *
     * @param planes the color of each color plane
     * @param d65 the XYZ to camera matrix, {@code 4 x 3}, not rounded
     * @param patches the number of grid cells the fit used
     * @param determination the coefficient of determination of each color plane's fit
     */
    record Fit(CfaColor[] planes, double[] d65, int patches, double[] determination) {
    }

    /**
     * Fits the matrix of a sample file.
     *
     * @param path the raw file
     * @return the fit
     */
    static Fit fit(Path path) {
        try (var source = MediaSource.of(path)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var sensor = file.sensor;
            var linear = Renderer.linearize(file, sensor, true);
            var cells = PreviewBalance.cells(file, linear, Renderer.window(sensor, linear, true));
            var planes = new CfaColor[4];
            for (var color : sensor.cfa.colors()) {
                planes[sensor.planeOfColor[color.code()]] = color;
            }
            var xyzs = new ArrayList<double[]>();
            var cameras = new ArrayList<double[]>();
            var preview = cells.preview();
            for (var cell = 0; cell < cells.clipped().length; cell++) {
                if (cells.clipped()[cell] || !cells.complete(cell)) {
                    continue;
                }
                var rgb = new double[]{preview[cell * 3], preview[cell * 3 + 1], preview[cell * 3 + 2]};
                if (Math.min(rgb[0], Math.min(rgb[1], rgb[2])) < MINIMUM || Math.max(rgb[0], Math.max(rgb[1], rgb[2])) > MAXIMUM) {
                    continue;
                }
                xyzs.add(ColorMath.transform(SRGB_TO_XYZ, rgb));
                cameras.add(Arrays.copyOfRange(cells.means(), cell * 4, cell * 4 + 4));
            }
            var normal = new double[9];
            var right = new double[4][3];
            for (var patch = 0; patch < xyzs.size(); patch++) {
                var xyz = xyzs.get(patch);
                var camera = cameras.get(patch);
                for (var i = 0; i < 3; i++) {
                    for (var j = 0; j < 3; j++) {
                        normal[i * 3 + j] += xyz[i] * xyz[j];
                    }
                    for (var plane = 0; plane < 4; plane++) {
                        right[plane][i] += xyz[i] * camera[plane];
                    }
                }
            }
            var inverse = ColorMath.invert(normal);
            var xyzToCamera = new double[12];
            for (var plane = 0; plane < 4; plane++) {
                System.arraycopy(ColorMath.transform(inverse, right[plane]), 0, xyzToCamera, plane * 3, 3);
            }
            var determination = new double[4];
            for (var plane = 0; plane < 4; plane++) {
                var mean = 0.0;
                for (var camera : cameras) {
                    mean += camera[plane];
                }
                mean /= cameras.size();
                var residual = 0.0;
                var total = 0.0;
                for (var patch = 0; patch < xyzs.size(); patch++) {
                    var predicted = ColorMath.transform(xyzToCamera, xyzs.get(patch))[plane];
                    var actual = cameras.get(patch)[plane];
                    residual += (actual - predicted) * (actual - predicted);
                    total += (actual - mean) * (actual - mean);
                }
                determination[plane] = 1 - residual / total;
            }
            file.color.asShotNeutral = null;
            PreviewBalance.apply(file, linear, Renderer.window(sensor, linear, true));
            xyzToCamera = refine(xyzToCamera, cameras, xyzs, file.color);
            var white = ColorMath.transform(xyzToCamera, D65_XYZ);
            var largest = Math.max(Math.max(white[0], white[1]), Math.max(white[2], white[3]));
            for (var index = 0; index < 12; index++) {
                xyzToCamera[index] /= largest;
            }
            return new Fit(planes, xyzToCamera, xyzs.size(), determination);
        }
    }

    /**
     * Refines a matrix with Levenberg-Marquardt iterations on the chroma errors of the patches as
     * the decoder renders them: each patch's camera values white balanced by the shot's camera
     * neutral, projected and taken through the color pipeline of the default rendering (its tone
     * curve included) to sRGB, against the preview's color, both in CIELAB under D65. The matrix
     * keeps mapping the D65 white to the camera neutral, so the shot renders as the D65 white
     * balance it was fitted under.
     *
     * @param start the starting matrix, {@code 4 x 3}
     * @param cameras the camera values of the patches
     * @param xyzs the preview XYZ values of the patches
     * @param calibration the file's color description, with the camera neutral of the shot as its
     * as-shot neutral; its matrix is replaced while the refinement runs
     * @return the refined matrix
     */
    private static double[] refine(double[] start, List<double[]> cameras, List<double[]> xyzs,
                                   ColorCalibration calibration) {
        var neutral = calibration.asShotNeutral;
        var targets = new ArrayList<double[]>();
        for (var xyz : xyzs) {
            targets.add(CameraMatrixDerivation.lab(xyz, D65_XYZ));
        }
        var startWhite = ColorMath.transform(start, D65_XYZ);
        var scale = 0.0;
        var norm = 0.0;
        for (var plane = 0; plane < 4; plane++) {
            scale += startWhite[plane] * neutral[plane];
            norm += neutral[plane] * neutral[plane];
        }
        var white = new double[4];
        for (var plane = 0; plane < 4; plane++) {
            white[plane] = neutral[plane] * scale / norm;
        }
        var parameters = new double[8];
        for (var plane = 0; plane < 4; plane++) {
            parameters[2 * plane] = start[plane * 3];
            parameters[2 * plane + 1] = start[plane * 3 + 1];
        }
        var initial = parameters.clone();
        var residuals = penalized(parameters, initial, white, cameras, targets, calibration);
        var cost = squares(residuals);
        var lambda = 1e-3;
        var n = parameters.length;
        for (var iteration = 0; iteration < 100; iteration++) {
            var jacobian = new double[n][];
            for (var j = 0; j < n; j++) {
                var forward = parameters.clone();
                forward[j] += DERIVATIVE_STEP;
                var backward = parameters.clone();
                backward[j] -= DERIVATIVE_STEP;
                var ahead = penalized(forward, initial, white, cameras, targets, calibration);
                var behind = penalized(backward, initial, white, cameras, targets, calibration);
                jacobian[j] = new double[residuals.length];
                for (var k = 0; k < residuals.length; k++) {
                    jacobian[j][k] = (ahead[k] - behind[k]) / (2 * DERIVATIVE_STEP);
                }
            }
            var normal = new double[n * n];
            var gradient = new double[n];
            for (var a = 0; a < n; a++) {
                for (var b = 0; b < n; b++) {
                    var sum = 0.0;
                    for (var k = 0; k < residuals.length; k++) {
                        sum += jacobian[a][k] * jacobian[b][k];
                    }
                    normal[a * n + b] = sum;
                }
                var sum = 0.0;
                for (var k = 0; k < residuals.length; k++) {
                    sum += jacobian[a][k] * residuals[k];
                }
                gradient[a] = -sum;
            }
            double[] step;
            while (true) {
                var damped = normal.clone();
                for (var a = 0; a < n; a++) {
                    damped[a * n + a] *= 1 + lambda;
                }
                var inverse = ColorMath.invert(damped, n);
                if (inverse == null) {
                    lambda *= 4;
                    continue;
                }
                step = ColorMath.transform(inverse, gradient);
                var candidate = parameters.clone();
                for (var a = 0; a < n; a++) {
                    candidate[a] += step[a];
                }
                var moved = penalized(candidate, initial, white, cameras, targets, calibration);
                var movedCost = squares(moved);
                if (movedCost < cost * (1 - 1e-9)) {
                    parameters = candidate;
                    residuals = moved;
                    cost = movedCost;
                    lambda /= 3;
                    break;
                }
                lambda *= 4;
                if (lambda > 1e8) {
                    return matrix(parameters, white);
                }
            }
            var largest = 0.0;
            for (var value : step) {
                largest = Math.max(largest, Math.abs(value));
            }
            if (largest < 1e-9) {
                break;
            }
        }
        return matrix(parameters, white);
    }

    /**
     * Returns the chroma errors of the patches for a parameter vector followed by the penalty on its
     * distance from the least-squares fit, which keeps the minimum unique where the chroma errors
     * leave a direction of the parameters almost free.
     *
     * @param parameters the first two coefficients of each row
     * @param initial the parameters of the least-squares fit
     * @param white the camera values of the D65 white
     * @param cameras the camera values of the patches
     * @param targets the preview CIELAB values of the patches
     * @param calibration the file's color description, whose matrix is replaced
     * @return the a* and b* differences, two per patch, then one penalty per parameter
     */
    private static double[] penalized(double[] parameters, double[] initial, double[] white, List<double[]> cameras,
                                      List<double[]> targets, ColorCalibration calibration) {
        var chroma = residuals(matrix(parameters, white), cameras, targets, calibration);
        var result = Arrays.copyOf(chroma, chroma.length + parameters.length);
        for (var index = 0; index < parameters.length; index++) {
            result[chroma.length + index] = PENALTY * (parameters[index] - initial[index]);
        }
        return result;
    }

    /**
     * Builds the matrix that maps the D65 white to a camera white from the first two coefficients
     * of each row.
     *
     * @param parameters the first two coefficients of each row
     * @param white the camera values of the D65 white
     * @return the {@code 4 x 3} matrix
     */
    private static double[] matrix(double[] parameters, double[] white) {
        var matrix = new double[12];
        for (var plane = 0; plane < 4; plane++) {
            var x = parameters[2 * plane];
            var y = parameters[2 * plane + 1];
            matrix[plane * 3] = x;
            matrix[plane * 3 + 1] = y;
            matrix[plane * 3 + 2] = (white[plane] - x * D65_XYZ[0] - y * D65_XYZ[1]) / D65_XYZ[2];
        }
        return matrix;
    }

    /**
     * Returns the CIELAB chroma differences of every patch as the decoder renders it.
     *
     * @param matrix the XYZ to camera matrix
     * @param cameras the camera values of the patches
     * @param targets the preview CIELAB values of the patches
     * @param calibration the file's color description, whose matrix is set to {@code matrix}
     * @return the a* and b* differences, two per patch
     */
    private static double[] residuals(double[] matrix, List<double[]> cameras, List<double[]> targets,
                                      ColorCalibration calibration) {
        var result = new double[2 * cameras.size()];
        calibration.colorMatrix[0] = matrix;
        CameraColor color;
        try {
            color = CameraColor.of(calibration, RawWhiteBalance.asShot(), null, 4);
        } catch (MediaException exception) {
            Arrays.fill(result, 1e3);
            return result;
        }
        var projection = color.projection();
        var pipeline = new ColorPipeline(color.projected(), calibration, RawDecoderOptions.defaults(), false, true,
                List.of(), 0);
        var count = cameras.size();
        var r = new float[count];
        var g = new float[count];
        var b = new float[count];
        for (var patch = 0; patch < count; patch++) {
            var camera = cameras.get(patch);
            var channels = new double[3];
            for (var plane = 0; plane < 4; plane++) {
                var balanced = Math.min(camera[plane] / color.neutral[plane], 1);
                for (var channel = 0; channel < 3; channel++) {
                    channels[channel] += projection[channel * 4 + plane] * balanced;
                }
            }
            r[patch] = (float) channels[0];
            g[patch] = (float) channels[1];
            b[patch] = (float) channels[2];
        }
        pipeline.balance(r, g, b, count);
        if (pipeline.profiled) {
            pipeline.profile(r, g, b, count, 1, 0, 0, 0, 0, pipeline.scratch());
        } else {
            var m = pipeline.matrix;
            for (var patch = 0; patch < count; patch++) {
                var red = r[patch];
                var green = g[patch];
                var blue = b[patch];
                r[patch] = m[0] * red + m[1] * green + m[2] * blue;
                g[patch] = m[3] * red + m[4] * green + m[5] * blue;
                b[patch] = m[6] * red + m[7] * green + m[8] * blue;
            }
        }
        for (var patch = 0; patch < count; patch++) {
            var rgb = new double[]{Math.clamp(r[patch], 0, 1), Math.clamp(g[patch], 0, 1), Math.clamp(b[patch], 0, 1)};
            var lab = CameraMatrixDerivation.lab(ColorMath.transform(SRGB_TO_XYZ, rgb), D65_XYZ);
            result[2 * patch] = lab[1] - targets.get(patch)[1];
            result[2 * patch + 1] = lab[2] - targets.get(patch)[2];
        }
        return result;
    }

    /**
     * {@return the sum of the squares of values}
     *
     * @param values the values
     */
    private static double squares(double[] values) {
        var sum = 0.0;
        for (var value : values) {
            sum += value * value;
        }
        return sum;
    }

    /**
     * Prints the fitted matrices of the samples, as {@link FourColorMatrices} lists them.
     *
     * @param arguments unused
     */
    static void main(String[] arguments) {
        for (var sample : new RawSamples[]{RawSamples.CRW_G1, RawSamples.NEF_E5700}) {
            var fit = fit(sample.fetch());
            var builder = new StringBuilder(sample.name()).append(" (").append(fit.patches()).append(" patches) ");
            for (var color : fit.planes()) {
                builder.append(color).append(' ');
            }
            builder.append(Arrays.toString(fit.determination())).append(" {");
            for (var index = 0; index < 12; index++) {
                builder.append(index > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.4f", fit.d65()[index]));
            }
            System.out.println(builder.append("}"));
        }
    }
}
