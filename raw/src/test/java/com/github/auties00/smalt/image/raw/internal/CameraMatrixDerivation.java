package com.github.auties00.smalt.image.raw.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Derives the per-camera color matrices of {@link CameraMatrices} from the camera spectral
 * sensitivities of rawtoaces-data (Apache-2.0).
 *
 * <p>For each camera and each of CIE illuminant A (Planck's law at 2856 K) and D65 (the CIE
 * daylight model at 6504 K, its S0, S1 and S2 components linearly interpolated to 5 nm), the 190
 * training reflectances of rawtoaces-data give camera responses (sensitivity times illuminant
 * times reflectance, summed over 380 to 780 nm in 5 nm steps, each channel divided by the response
 * to the illuminant itself) and CIE 1931 XYZ values (relative to the illuminant's Y). A 3x3 matrix
 * from white-balanced camera values to XYZ that maps the white to the illuminant's white is fitted
 * by least squares in XYZ, then refined with Levenberg-Marquardt iterations on the CIELAB errors
 * of the patches. The DNG {@code ColorMatrix}, from XYZ to camera values, is its inverse with the
 * camera's white balance put back, scaled so that the illuminant's white maps to a largest camera
 * value of 1. Values are rounded to four decimals.
 */
final class CameraMatrixDerivation {
    /**
     * The wavelengths, 380 to 780 nm in 5 nm steps.
     */
    private static final int[] WAVELENGTHS = wavelengths();

    /**
     * The CIE daylight components S0, S1 and S2 from 300 to 830 nm in 10 nm steps, as rawtoaces
     * (Apache-2.0) tabulates them in {@code define.h}.
     */
    private static final double[][] DAYLIGHT = {
            {0.04, 0.02, 0.0}, {6.0, 4.5, 2.0}, {29.6, 22.4, 4.0}, {55.3, 42.0, 8.5}, {57.3, 40.6, 7.8},
            {61.8, 41.6, 6.7}, {61.5, 38.0, 5.3}, {68.8, 42.4, 6.1}, {63.4, 38.5, 3.0}, {65.8, 35.0, 1.2},
            {94.8, 43.4, -1.1}, {104.8, 46.3, -0.5}, {105.9, 43.9, -0.7}, {96.8, 37.1, -1.2}, {113.9, 36.7, -2.6},
            {125.6, 35.9, -2.9}, {125.5, 32.6, -2.8}, {121.3, 27.9, -2.6}, {121.3, 24.3, -2.6}, {113.5, 20.1, -1.8},
            {113.1, 16.2, -1.5}, {110.8, 13.2, -1.3}, {106.5, 8.6, -1.2}, {108.8, 6.1, -1.0}, {105.3, 4.2, -0.5},
            {104.4, 1.9, -0.3}, {100.0, 0.0, 0.0}, {96.0, -1.6, 0.2}, {95.1, -3.5, 0.5}, {89.1, -3.5, 2.1},
            {90.5, -5.8, 3.2}, {90.3, -7.2, 4.1}, {88.4, -8.6, 4.7}, {84.0, -9.5, 5.1}, {85.1, -10.9, 6.7},
            {81.9, -10.7, 7.3}, {82.6, -12.0, 8.6}, {84.9, -14.0, 9.8}, {81.3, -13.6, 10.2}, {71.9, -12.0, 8.3},
            {74.3, -13.3, 9.6}, {76.4, -12.9, 8.5}, {63.3, -10.6, 7.0}, {71.7, -11.6, 7.6}, {77.0, -12.2, 8.0},
            {65.2, -10.2, 6.7}, {47.7, -7.8, 5.2}, {68.6, -11.2, 7.4}, {65.0, -10.4, 6.8}, {66.0, -10.6, 7.0},
            {61.0, -9.7, 6.4}, {53.3, -8.3, 5.5}, {58.9, -9.3, 6.1}, {61.9, -9.8, 6.5}
    };

    /**
     * Prevents instantiation.
     */
    private CameraMatrixDerivation() {
    }

    /**
     * The matrices of one camera.
     *
     * @param make  the manufacturer, as rawtoaces-data names it
     * @param model the model, as rawtoaces-data names it
     * @param a     the XYZ to camera matrix under illuminant A, row by row
     * @param d65   the XYZ to camera matrix under illuminant D65, row by row
     */
    record Camera(String make, String model, double[] a, double[] d65) {
    }

    /**
     * Derives the matrices of every camera of a rawtoaces-data archive.
     *
     * @param archive the {@code .tar.gz} archive of the repository
     * @return the cameras, by file name
     * @throws IOException if the archive cannot be read
     */
    static List<Camera> derive(Path archive) throws IOException {
        var files = untar(archive);
        double[][] cmf = null;
        double[][] training = null;
        var cameras = new TreeMap<String, Map<String, Object>>();
        for (var entry : files.entrySet()) {
            var name = entry.getKey();
            if (name.endsWith("/data/cmf/cmf_1931.json")) {
                cmf = table(Json.parse(entry.getValue()));
            } else if (name.endsWith("/data/training/training_spectral.json")) {
                training = table(Json.parse(entry.getValue()));
            } else if (name.contains("/data/camera/") && name.endsWith(".json")) {
                cameras.put(name.substring(name.lastIndexOf('/') + 1), Json.parse(entry.getValue()));
            }
        }
        if (cmf == null || training == null || cameras.isEmpty()) {
            throw new IOException("not a rawtoaces-data archive");
        }
        var illuminantA = planck(2856);
        var illuminantD65 = daylight(6504);
        var result = new ArrayList<Camera>();
        for (var camera : cameras.values()) {
            @SuppressWarnings("unchecked")
            var header = (Map<String, Object>) camera.get("header");
            var sensitivity = table(camera);
            result.add(new Camera((String) header.get("manufacturer"), (String) header.get("model"),
                    colorMatrix(sensitivity, illuminantA, cmf, training), colorMatrix(sensitivity, illuminantD65, cmf, training)));
        }
        return result;
    }

    /**
     * Derives one XYZ to camera matrix.
     *
     * @param sensitivity the camera's sensitivities, by wavelength then channel
     * @param illuminant  the illuminant's power, by wavelength
     * @param cmf         the CIE 1931 color matching functions, by wavelength then component
     * @param training    the training reflectances, by wavelength then patch
     * @return the matrix, row by row, rounded to four decimals
     */
    static double[] colorMatrix(double[][] sensitivity, double[] illuminant, double[][] cmf, double[][] training) {
        var patches = training[0].length;
        var camera = new double[patches][3];
        var xyz = new double[patches][3];
        var balance = new double[3];
        var white = new double[3];
        for (var k = 0; k < WAVELENGTHS.length; k++) {
            for (var c = 0; c < 3; c++) {
                balance[c] += sensitivity[k][c] * illuminant[k];
                white[c] += cmf[k][c] * illuminant[k];
            }
        }
        var luminance = white[1];
        for (var c = 0; c < 3; c++) {
            white[c] /= luminance;
        }
        for (var p = 0; p < patches; p++) {
            for (var k = 0; k < WAVELENGTHS.length; k++) {
                var light = illuminant[k] * training[k][p];
                for (var c = 0; c < 3; c++) {
                    camera[p][c] += sensitivity[k][c] * light;
                    xyz[p][c] += cmf[k][c] * light;
                }
            }
            for (var c = 0; c < 3; c++) {
                camera[p][c] /= balance[c];
                xyz[p][c] /= luminance;
            }
        }
        var fitted = refine(leastSquares(camera, xyz, white), camera, xyz, white);
        var inverse = ColorMath.invert(fitted);
        var largest = Math.max(balance[0], Math.max(balance[1], balance[2]));
        var matrix = new double[9];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                matrix[row * 3 + column] = Math.round(balance[row] / largest * inverse[row * 3 + column] * 1e4) / 1e4;
            }
        }
        return matrix;
    }

    /**
     * Fits a white-preserving matrix from balanced camera values to XYZ by least squares in XYZ.
     *
     * @param camera the balanced camera values of the patches
     * @param xyz    the XYZ values of the patches
     * @param white  the illuminant's white
     * @return the matrix, row by row
     */
    private static double[] leastSquares(double[][] camera, double[][] xyz, double[] white) {
        var matrix = new double[9];
        for (var row = 0; row < 3; row++) {
            double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
            for (var p = 0; p < camera.length; p++) {
                var u = camera[p][0] - camera[p][2];
                var v = camera[p][1] - camera[p][2];
                var t = xyz[p][row] - white[row] * camera[p][2];
                a11 += u * u;
                a12 += u * v;
                a22 += v * v;
                b1 += u * t;
                b2 += v * t;
            }
            var determinant = a11 * a22 - a12 * a12;
            var m0 = (b1 * a22 - b2 * a12) / determinant;
            var m1 = (a11 * b2 - a12 * b1) / determinant;
            matrix[row * 3] = m0;
            matrix[row * 3 + 1] = m1;
            matrix[row * 3 + 2] = white[row] - m0 - m1;
        }
        return matrix;
    }

    /**
     * Refines a white-preserving matrix with Levenberg-Marquardt iterations on the CIELAB errors,
     * its six free coefficients being the first two of each row.
     *
     * @param start  the starting matrix
     * @param camera the balanced camera values of the patches
     * @param xyz    the XYZ values of the patches
     * @param white  the illuminant's white
     * @return the refined matrix, row by row
     */
    private static double[] refine(double[] start, double[][] camera, double[][] xyz, double[] white) {
        var target = new double[camera.length][];
        for (var p = 0; p < camera.length; p++) {
            target[p] = lab(xyz[p], white);
        }
        var parameters = new double[]{start[0], start[1], start[3], start[4], start[6], start[7]};
        var residuals = residuals(parameters, camera, target, white);
        var cost = squares(residuals);
        var lambda = 1e-3;
        for (var iteration = 0; iteration < 50; iteration++) {
            var jacobian = new double[6][];
            for (var j = 0; j < 6; j++) {
                var moved = parameters.clone();
                moved[j] += 1e-6;
                var shifted = residuals(moved, camera, target, white);
                jacobian[j] = new double[residuals.length];
                for (var k = 0; k < residuals.length; k++) {
                    jacobian[j][k] = (shifted[k] - residuals[k]) / 1e-6;
                }
            }
            var normal = new double[6][6];
            var gradient = new double[6];
            for (var a = 0; a < 6; a++) {
                for (var b = 0; b < 6; b++) {
                    var sum = 0.0;
                    for (var k = 0; k < residuals.length; k++) {
                        sum += jacobian[a][k] * jacobian[b][k];
                    }
                    normal[a][b] = sum;
                }
                var sum = 0.0;
                for (var k = 0; k < residuals.length; k++) {
                    sum += jacobian[a][k] * residuals[k];
                }
                gradient[a] = sum;
            }
            double[] step;
            while (true) {
                var damped = new double[6][7];
                for (var a = 0; a < 6; a++) {
                    System.arraycopy(normal[a], 0, damped[a], 0, 6);
                    damped[a][a] *= 1 + lambda;
                    damped[a][6] = -gradient[a];
                }
                step = solve(damped);
                var candidate = parameters.clone();
                for (var a = 0; a < 6; a++) {
                    candidate[a] += step[a];
                }
                var moved = residuals(candidate, camera, target, white);
                var movedCost = squares(moved);
                if (movedCost < cost) {
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
     * Builds the white-preserving matrix of six coefficients.
     *
     * @param parameters the first two coefficients of each row
     * @param white      the illuminant's white
     * @return the matrix, row by row
     */
    private static double[] matrix(double[] parameters, double[] white) {
        var matrix = new double[9];
        for (var row = 0; row < 3; row++) {
            matrix[row * 3] = parameters[2 * row];
            matrix[row * 3 + 1] = parameters[2 * row + 1];
            matrix[row * 3 + 2] = white[row] - parameters[2 * row] - parameters[2 * row + 1];
        }
        return matrix;
    }

    /**
     * Returns the CIELAB differences of every patch.
     *
     * @param parameters the matrix coefficients
     * @param camera     the balanced camera values
     * @param target     the patches' CIELAB values
     * @param white      the illuminant's white
     * @return the differences, three per patch
     */
    private static double[] residuals(double[] parameters, double[][] camera, double[][] target, double[] white) {
        var matrix = matrix(parameters, white);
        var residuals = new double[camera.length * 3];
        for (var p = 0; p < camera.length; p++) {
            var lab = lab(ColorMath.apply(matrix, camera[p]), white);
            for (var c = 0; c < 3; c++) {
                residuals[p * 3 + c] = lab[c] - target[p][c];
            }
        }
        return residuals;
    }

    /**
     * Returns the sum of squares.
     *
     * @param values the values
     * @return the sum of their squares
     */
    private static double squares(double[] values) {
        var sum = 0.0;
        for (var value : values) {
            sum += value * value;
        }
        return sum;
    }

    /**
     * Solves a linear system by Gauss-Jordan elimination with partial pivoting.
     *
     * @param augmented the system, each row its coefficients then its right-hand side, modified
     * @return the solution
     */
    private static double[] solve(double[][] augmented) {
        var n = augmented.length;
        for (var column = 0; column < n; column++) {
            var pivot = column;
            for (var row = column + 1; row < n; row++) {
                if (Math.abs(augmented[row][column]) > Math.abs(augmented[pivot][column])) {
                    pivot = row;
                }
            }
            var swap = augmented[column];
            augmented[column] = augmented[pivot];
            augmented[pivot] = swap;
            for (var row = 0; row < n; row++) {
                if (row != column) {
                    var factor = augmented[row][column] / augmented[column][column];
                    for (var k = 0; k <= n; k++) {
                        augmented[row][k] -= factor * augmented[column][k];
                    }
                }
            }
        }
        var solution = new double[n];
        for (var row = 0; row < n; row++) {
            solution[row] = augmented[row][n] / augmented[row][row];
        }
        return solution;
    }

    /**
     * Converts XYZ to CIELAB relative to a white.
     *
     * @param xyz   the color
     * @param white the white
     * @return L*, a* and b*
     */
    static double[] lab(double[] xyz, double[] white) {
        var fx = labCurve(xyz[0] / white[0]);
        var fy = labCurve(xyz[1] / white[1]);
        var fz = labCurve(xyz[2] / white[2]);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    /**
     * Evaluates the CIELAB companding function.
     *
     * @param t the relative value
     * @return the companded value
     */
    private static double labCurve(double t) {
        var delta = 6 / 29.0;
        return t > delta * delta * delta ? Math.cbrt(t) : t / (3 * delta * delta) + 4 / 29.0;
    }

    /**
     * Samples CIE illuminant A, Planck's law with the second radiation constant 1.4388e-2 m K,
     * normalized to 100 at 560 nm.
     *
     * @param temperature the temperature in kelvin
     * @return the power by wavelength
     */
    static double[] planck(double temperature) {
        var c2 = 1.4388e-2;
        var power = new double[WAVELENGTHS.length];
        for (var k = 0; k < power.length; k++) {
            var wavelength = WAVELENGTHS[k];
            power[k] = 100 * Math.pow(560.0 / wavelength, 5) * (Math.exp(c2 / (temperature * 560e-9)) - 1)
                    / (Math.exp(c2 / (temperature * wavelength * 1e-9)) - 1);
        }
        return power;
    }

    /**
     * Samples a CIE daylight illuminant: its chromaticity from the temperature, its component
     * weights rounded to three decimals as CIE 15 prescribes, the components interpolated
     * linearly to 5 nm.
     *
     * @param temperature the correlated color temperature in kelvin
     * @return the power by wavelength
     */
    static double[] daylight(double temperature) {
        var t = temperature;
        var x = t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
        var y = -3 * x * x + 2.87 * x - 0.275;
        var m = 0.0241 + 0.2562 * x - 0.7341 * y;
        var m1 = Math.round((-1.3515 - 1.7703 * x + 5.9114 * y) / m * 1000) / 1000.0;
        var m2 = Math.round((0.0300 - 31.4424 * x + 30.0717 * y) / m * 1000) / 1000.0;
        var power = new double[WAVELENGTHS.length];
        for (var k = 0; k < power.length; k++) {
            var wavelength = WAVELENGTHS[k];
            var lower = (wavelength - 300) / 10;
            var fraction = (wavelength - 300 - lower * 10) / 10.0;
            var upper = Math.min(lower + 1, DAYLIGHT.length - 1);
            var s = new double[3];
            for (var c = 0; c < 3; c++) {
                s[c] = DAYLIGHT[lower][c] + (DAYLIGHT[upper][c] - DAYLIGHT[lower][c]) * fraction;
            }
            power[k] = s[0] + m1 * s[1] + m2 * s[2];
        }
        return power;
    }

    /**
     * Reads a rawtoaces-data spectral table at the derivation's wavelengths.
     *
     * @param document the JSON document
     * @return the values by wavelength
     */
    @SuppressWarnings("unchecked")
    private static double[][] table(Map<String, Object> document) {
        var data = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) document.get("spectral_data"))
                .get("data")).get("main");
        var table = new double[WAVELENGTHS.length][];
        for (var k = 0; k < table.length; k++) {
            var values = (List<Object>) data.get(Integer.toString(WAVELENGTHS[k]));
            table[k] = new double[values.size()];
            for (var i = 0; i < values.size(); i++) {
                table[k][i] = ((Number) values.get(i)).doubleValue();
            }
        }
        return table;
    }

    /**
     * Returns the wavelengths.
     *
     * @return 380 to 780 nm in 5 nm steps
     */
    private static int[] wavelengths() {
        var values = new int[81];
        for (var k = 0; k < values.length; k++) {
            values[k] = 380 + 5 * k;
        }
        return values;
    }

    /**
     * Reads the regular files of a {@code .tar.gz} archive.
     *
     * @param archive the archive
     * @return the contents by path
     * @throws IOException if the archive cannot be read
     */
    static Map<String, String> untar(Path archive) throws IOException {
        var files = new LinkedHashMap<String, String>();
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            var header = new byte[512];
            String longName = null;
            while (input.readNBytes(header, 0, 512) == 512) {
                if (header[0] == 0) {
                    break;
                }
                var name = string(header, 0, 100);
                var prefix = string(header, 345, 155);
                var size = Long.parseLong(string(header, 124, 12).trim(), 8);
                var type = header[156];
                var content = input.readNBytes((int) size);
                input.readNBytes((int) ((512 - size % 512) % 512));
                if (type == 'x') {
                    for (var line : new String(content, StandardCharsets.UTF_8).split("\n")) {
                        var at = line.indexOf(" path=");
                        if (at >= 0) {
                            longName = line.substring(at + 6);
                        }
                    }
                    continue;
                }
                if (type == '0' || type == 0) {
                    var path = longName != null ? longName : prefix.isEmpty() ? name : prefix + "/" + name;
                    files.put(path, new String(content, StandardCharsets.UTF_8));
                }
                longName = null;
            }
        }
        return files;
    }

    /**
     * Reads a NUL-terminated field of a tar header.
     *
     * @param header the header
     * @param offset the field's offset
     * @param length the field's length
     * @return the text
     */
    private static String string(byte[] header, int offset, int length) {
        var end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    /**
     * Prints the matrices of a rawtoaces-data archive as the entries of {@link CameraMatrices}.
     *
     * @param arguments the archive's path
     * @throws IOException if the archive cannot be read
     */
    static void main(String[] arguments) throws IOException {
        for (var camera : derive(Path.of(arguments[0]))) {
            System.out.println(CameraMatrices.format(camera.make(), camera.model(), camera.a(), camera.d65()));
        }
    }

    /**
     * Parses the JSON subset rawtoaces-data uses: objects, arrays, strings without escapes other
     * than the common ones, numbers, booleans and {@code null}.
     */
    static final class Json {
        /**
         * The text.
         */
        private final String text;

        /**
         * The position of the next character.
         */
        private int position;

        /**
         * Constructs a parser.
         *
         * @param text the text
         */
        private Json(String text) {
            this.text = text;
        }

        /**
         * Parses a document whose root is an object.
         *
         * @param text the text
         * @return the object
         */
        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String text) {
            return (Map<String, Object>) new Json(text).value();
        }

        /**
         * Parses a value.
         *
         * @return the value
         */
        private Object value() {
            skip();
            var c = text.charAt(position);
            if (c == '{') {
                position++;
                var object = new LinkedHashMap<String, Object>();
                skip();
                if (text.charAt(position) == '}') {
                    position++;
                    return object;
                }
                while (true) {
                    skip();
                    var key = (String) value();
                    skip();
                    position++;
                    object.put(key, value());
                    skip();
                    if (text.charAt(position++) == '}') {
                        return object;
                    }
                }
            }
            if (c == '[') {
                position++;
                var array = new ArrayList<Object>();
                skip();
                if (text.charAt(position) == ']') {
                    position++;
                    return array;
                }
                while (true) {
                    array.add(value());
                    skip();
                    if (text.charAt(position++) == ']') {
                        return array;
                    }
                }
            }
            if (c == '"') {
                var builder = new StringBuilder();
                position++;
                while (text.charAt(position) != '"') {
                    var next = text.charAt(position++);
                    if (next == '\\') {
                        var escaped = text.charAt(position++);
                        builder.append(switch (escaped) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'u' -> {
                                var code = (char) Integer.parseInt(text.substring(position, position + 4), 16);
                                position += 4;
                                yield code;
                            }
                            default -> escaped;
                        });
                    } else {
                        builder.append(next);
                    }
                }
                position++;
                return builder.toString();
            }
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            var start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            return Double.parseDouble(text.substring(start, position));
        }

        /**
         * Skips white space.
         */
        private void skip() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
